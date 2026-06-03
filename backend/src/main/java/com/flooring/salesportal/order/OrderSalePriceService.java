package com.flooring.salesportal.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import com.flooring.salesportal.order.dto.SalePriceMutationResponse;
import com.flooring.salesportal.order.financial.LineFinancials;
import com.flooring.salesportal.order.financial.OrderFinancialCalculator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Chunk 3 Branch 6 — manual sale-price override + reset (D.10 PUT /sale-price, D.11 POST
 * /sale-price/reset). The salesperson types a GST-inclusive sale price in Details of Sale; the backend
 * derives and persists {@code sales_order.price_adjustment_inc_gst} (V5 column) and reapplies it on
 * every future recalculation (conventions §12). Reset clears the adjustment back to NULL.
 *
 * <p>Gate ordering mirrors the Branch 2/3 line mutation pattern ({@link OrderProductLineService},
 * {@link OrderChargeLineService}): standard-protected guard → manual {@code orderId} parse
 * (VALIDATION_FAILED on {@code order_id}) → order scope lookup (404 {@code ORDER_NOT_FOUND}; FOR
 * UPDATE) → LAID gate (422 {@code ORDER_LOCKED}; override is a financial edit, conventions §16) →
 * (override only) body parse/validate (400) → recompute → persist → return summary. The override body
 * arrives as a raw {@code String} parsed AFTER the gates so malformed JSON cannot 400 ahead of 404/422,
 * and the only accepted key is {@code final_sale_price_inc_gst} — every other / unknown / backend-
 * controlled field is rejected via an explicit {@link JsonNode} key scan (conventions §10, Chunk 3
 * F.10). Reset takes an empty body and intentionally does not parse it (D.11).
 *
 * <p>Per the locked Branch decision the Branch 2/3 helper logic is DUPLICATED here rather than extracted
 * (the line services are left untouched). The derived {@code price_adjustment_inc_gst} is width-guarded
 * against DECIMAL(10,2) before persist — {@code requirePersistableHeader} covers the derived scalars but
 * not the adjustment, and {@code calculated_total_inc_gst} is a computed value that can exceed the column
 * width, so an oversized adjustment is a clean 400 rather than a PostgreSQL numeric-overflow 500.
 */
@Service
public class OrderSalePriceService {

    private static final String STATUS_LAID = "LAID";

    private static final int MONEY_SCALE = 2;
    // sales_order price_adjustment_inc_gst / sale_price_ex_gst / total_cost / gp are DECIMAL(10,2)
    // (V2/V5). A value scaled to 2dp fits iff it has at most (10 - 2) = 8 integer digits, i.e.
    // |value| <= 99999999.99.
    private static final int MONEY_PRECISION = 10;
    private static final int MONEY_MAX_INTEGER_DIGITS = MONEY_PRECISION - MONEY_SCALE;
    private static final String MONEY_MAX_LABEL = "99999999.99";
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    // sales_order.gp_percent is DECIMAL(5,2): persist NULL when the true value would overflow
    // (locked review decision R4); the response summary still carries the true value.
    private static final BigDecimal MAX_DB_GP_PERCENT = new BigDecimal("999.99");

    private static final String FIELD_FINAL_SALE_PRICE = "final_sale_price_inc_gst";
    private static final String SNAPSHOT_SUFFIX = "_snapshot";
    // The ONLY request-body key a client may send (OpenAPI SalePriceOverrideRequest declares
    // additionalProperties: false). Anything else is rejected by the allow-list scan below.
    private static final Set<String> OVERRIDE_ALLOWED_FIELDS = Set.of(FIELD_FINAL_SALE_PRICE);
    // Backend-controlled / derived fields the client must never send (conventions §10, Chunk 3 F.10).
    // final_sale_price_inc_gst is deliberately NOT here — it is the one allowed input. The allow-list
    // scan already rejects any non-allowed key; this set just gives the well-known offenders a clear name.
    private static final Set<String> FORBIDDEN_BASE = Set.of(
            "price_adjustment_inc_gst", "calculated_total_inc_gst", "product_subtotal", "charge_subtotal",
            "sale_price_ex_gst", "total_cost", "gp", "gp_percent", "gp_warning",
            "business_id", "store_id", "user_id", "order_id", "created_at", "updated_at",
            "cost", "cost_snapshot", "line_cost", "line_total", "price_snapshot",
            "unit_price", "quantity", "quantity_lm", "quantity_sqm", "product_id", "charge_id");

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderProductLineRepository orderProductLineRepository;
    private final OrderChargeLineReadRepository orderChargeLineReadRepository;
    private final SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository;
    private final OrderFinancialCalculator financialCalculator;
    private final ObjectMapper objectMapper;

    public OrderSalePriceService(RequestContextGuard requestContextGuard,
                                 SalesOrderRepository salesOrderRepository,
                                 OrderProductLineRepository orderProductLineRepository,
                                 OrderChargeLineReadRepository orderChargeLineReadRepository,
                                 SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository,
                                 OrderFinancialCalculator financialCalculator,
                                 ObjectMapper objectMapper) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.orderProductLineRepository = orderProductLineRepository;
        this.orderChargeLineReadRepository = orderChargeLineReadRepository;
        this.salesOrderFinancialWriteRepository = salesOrderFinancialWriteRepository;
        this.financialCalculator = financialCalculator;
        this.objectMapper = objectMapper;
    }

    /** PUT /orders/{orderId}/sale-price (D.10). */
    @Transactional
    public SalePriceMutationResponse overrideSalePrice(String slug,
                                                       String orderIdRaw,
                                                       String body,
                                                       HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
        requireNotLaid(order);

        JsonNode node = parseBodyAfterGates(body);

        List<ErrorDetail> errors = new ArrayList<>();
        rejectDisallowedFields(node, FORBIDDEN_BASE, OVERRIDE_ALLOWED_FIELDS, errors);
        BigDecimal finalSalePriceIncGst = readRequiredPositiveDecimal(node, FIELD_FINAL_SALE_PRICE, errors);
        throwIfErrors(errors);

        // Derive the GST-inclusive adjustment from the order's CURRENT persisted lines (conventions §12):
        // price_adjustment_inc_gst = round(final_sale_price_inc_gst − calculated_total_inc_gst, 2).
        LineFinancials products = orderProductLineRepository.sumFinancials(orderId);
        LineFinancials charges = orderChargeLineReadRepository.sumFinancials(orderId);
        BigDecimal calculatedTotalIncGst =
                financialCalculator.compute(products, charges, null).calculatedTotalIncGst();
        BigDecimal priceAdjustmentIncGst =
                finalSalePriceIncGst.subtract(calculatedTotalIncGst).setScale(MONEY_SCALE, ROUNDING);
        // The derived adjustment targets DECIMAL(10,2). calculated_total_inc_gst is a computed value that
        // can exceed the column width, so a far-below-cost final price could yield an out-of-range
        // adjustment — guard it as a clean 400, not a persist-time 500. Zero is in range and is stored.
        if (!fitsMoney(priceAdjustmentIncGst)) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, FIELD_FINAL_SALE_PRICE,
                            "Resulting price adjustment exceeds the maximum supported value ("
                                    + MONEY_MAX_LABEL + ").")));
        }

        OrderFinancialSummaryDto summary = financialCalculator.compute(products, charges, priceAdjustmentIncGst);
        requirePersistableHeader(summary);

        LocalDateTime now = LocalDateTime.now();
        salesOrderFinancialWriteRepository.updateHeaderFinancialsWithAdjustment(
                orderId,
                priceAdjustmentIncGst,
                summary.salePriceExGst(),
                summary.totalCost(),
                summary.gp(),
                persistableGpPercent(summary.gpPercent()),
                now);
        return new SalePriceMutationResponse(summary);
    }

    /** POST /orders/{orderId}/sale-price/reset (D.11). Empty body; clears the adjustment to NULL. */
    @Transactional
    public SalePriceMutationResponse resetSalePrice(String slug,
                                                    String orderIdRaw,
                                                    HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
        requireNotLaid(order);

        // Empty-body endpoint: the body is intentionally NOT parsed or validated (D.11). Clearing the
        // adjustment is idempotent — a reset with no current adjustment recomputes the same summary and
        // re-persists it (updated_at may change; acceptable per the locked decision).
        LineFinancials products = orderProductLineRepository.sumFinancials(orderId);
        LineFinancials charges = orderChargeLineReadRepository.sumFinancials(orderId);
        OrderFinancialSummaryDto summary = financialCalculator.compute(products, charges, null);
        requirePersistableHeader(summary);

        LocalDateTime now = LocalDateTime.now();
        salesOrderFinancialWriteRepository.updateHeaderFinancialsWithAdjustment(
                orderId,
                null,
                summary.salePriceExGst(),
                summary.totalCost(),
                summary.gp(),
                persistableGpPercent(summary.gpPercent()),
                now);
        return new SalePriceMutationResponse(summary);
    }

    // ------------------------------------------------------------------
    // Persistable-header guards (conventions §12; duplicated from the line services)
    // ------------------------------------------------------------------

    /**
     * Guard the persisted {@code sales_order} header scalars against the column CHECK constraints /
     * widths so a recompute that would violate one becomes a clean 400 VALIDATION_FAILED instead of a
     * PostgreSQL 500. Negative {@code sale_price_ex_gst} is allowed (V9 relaxed the non-negative CHECK);
     * only its DECIMAL(10,2) width is guarded. {@code gp_percent} is handled separately (NULL on
     * DECIMAL(5,2) overflow, R4).
     */
    private static void requirePersistableHeader(OrderFinancialSummaryDto summary) {
        List<ErrorDetail> errors = new ArrayList<>();
        if (!fitsMoney(summary.salePriceExGst())) {
            errors.add(new ErrorDetail(null, "sale_price_ex_gst",
                    "Order sale price exceeds the maximum supported value (" + MONEY_MAX_LABEL + ")."));
        }
        BigDecimal totalCost = summary.totalCost();
        if (totalCost.compareTo(ZERO) < 0) {
            errors.add(new ErrorDetail(null, "total_cost", "Order total cost would be negative."));
        } else if (!fitsMoney(totalCost)) {
            errors.add(new ErrorDetail(null, "total_cost",
                    "Order total cost exceeds the maximum supported value (" + MONEY_MAX_LABEL + ")."));
        }
        if (!fitsMoney(summary.gp())) {
            errors.add(new ErrorDetail(null, null,
                    "Order gross profit exceeds the maximum supported value (" + MONEY_MAX_LABEL + ")."));
        }
        throwIfErrors(errors);
    }

    private static BigDecimal persistableGpPercent(BigDecimal gpPercent) {
        if (gpPercent == null) {
            return null;
        }
        return gpPercent.abs().compareTo(MAX_DB_GP_PERCENT) <= 0 ? gpPercent : null;
    }

    // ------------------------------------------------------------------
    // Gates / parsing / validation helpers (duplicated from the line services)
    // ------------------------------------------------------------------

    private void requireNotLaid(SalesOrder order) {
        if (STATUS_LAID.equals(order.getOrderStatus())) {
            throw new BusinessRuleException(ErrorCode.ORDER_LOCKED, ErrorCode.ORDER_LOCKED.defaultMessage());
        }
    }

    private static long parsePositiveLong(String raw, String field) {
        if (raw != null && !raw.isBlank()) {
            try {
                long parsed = Long.parseLong(raw);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to a single VALIDATION_FAILED below
            }
        }
        throw new ValidationException(
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new ErrorDetail(null, field, "Must be a positive integer.")));
    }

    /**
     * Parse the raw body to a {@link JsonNode} AFTER the gates. Null/blank → null (treated as the
     * required field missing). Unparseable JSON → {@link MalformedJsonException} (400 MALFORMED_JSON).
     * Reading via JsonNode (rather than a typed record) is required so non-allowed backend-controlled
     * fields can be explicitly rejected — the shared ObjectMapper ignores unknown properties.
     */
    private JsonNode parseBodyAfterGates(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (JsonProcessingException ex) {
            throw new MalformedJsonException();
        }
    }

    private static void rejectDisallowedFields(JsonNode node, Set<String> forbidden, Set<String> allowed,
                                               List<ErrorDetail> errors) {
        if (node == null || !node.isObject()) {
            return;
        }
        // Reject any key that is an explicit backend-controlled field, ends in "_snapshot", OR is simply
        // not in the allowed set (OpenAPI additionalProperties: false). One "Not allowed." detail per key.
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (forbidden.contains(name) || name.endsWith(SNAPSHOT_SUFFIX) || !allowed.contains(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
    }

    private static boolean presentNonNull(JsonNode node, String field) {
        if (node == null) {
            return false;
        }
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }

    /** Required numeric field: absent or explicit JSON null → "Required."; otherwise validated like {@link #readOptionalPositiveDecimal}. */
    private static BigDecimal readRequiredPositiveDecimal(JsonNode node, String field, List<ErrorDetail> errors) {
        if (!presentNonNull(node, field)) {
            errors.add(new ErrorDetail(null, field, "Required."));
            return null;
        }
        return readOptionalPositiveDecimal(node, field, errors);
    }

    /**
     * Optional numeric field: absent or explicit JSON null → null; a non-number or a value ≤ 0 →
     * VALIDATION_FAILED on {@code field}; otherwise the {@link BigDecimal} value normalized to the 2dp
     * money scale (HALF_UP) — mirrors the line endpoints (extra decimals are rounded, not rejected).
     * An in-range-positive but DECIMAL(10,2)-overflowing value is a 400, not a persist-time 500.
     */
    private static BigDecimal readOptionalPositiveDecimal(JsonNode node, String field, List<ErrorDetail> errors) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            errors.add(new ErrorDetail(null, field, "Must be a number greater than 0."));
            return null;
        }
        // Parse via the textual form so a JSON decimal is captured exactly, avoiding the binary
        // floating-point drift of JsonNode.decimalValue() on DoubleNode. A non-finite numeric token
        // still reports isNumber() == true on a DoubleNode (e.g. 1e309 → "Infinity", or "NaN") but is
        // NOT a valid BigDecimal, so guard the parse/scale and turn it into a clean 400
        // VALIDATION_FAILED rather than letting NumberFormatException escape as a 500. Validate the
        // SCALED value.
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value.asText()).setScale(MONEY_SCALE, ROUNDING);
        } catch (NumberFormatException | ArithmeticException ex) {
            errors.add(new ErrorDetail(null, field, "Must be a number greater than 0."));
            return null;
        }
        if (parsed.compareTo(ZERO) <= 0) {
            errors.add(new ErrorDetail(null, field, "Must be greater than 0."));
            return null;
        }
        if (!fitsMoney(parsed)) {
            errors.add(new ErrorDetail(null, field, "Must be at most " + MONEY_MAX_LABEL + "."));
            return null;
        }
        return parsed;
    }

    private static void throwIfErrors(List<ErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }

    /** True iff {@code value}, scaled to 2dp, fits a DECIMAL(10,2) column (|value| <= 99999999.99). */
    private static boolean fitsMoney(BigDecimal value) {
        BigDecimal scaled = value.setScale(MONEY_SCALE, ROUNDING);
        return scaled.precision() - scaled.scale() <= MONEY_MAX_INTEGER_DIGITS;
    }
}
