package com.flooring.salesportal.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.catalog.StoreProductSnapshotRepository;
import com.flooring.salesportal.catalog.StoreProductSnapshotRepository.StoreProductSnapshot;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import com.flooring.salesportal.order.dto.OrderLinesResponse;
import com.flooring.salesportal.order.dto.ProductLineDeleteResponse;
import com.flooring.salesportal.order.dto.ProductLineMutationResponse;
import com.flooring.salesportal.order.dto.ProductLineReadDto;
import com.flooring.salesportal.order.financial.LineFinancials;
import com.flooring.salesportal.order.financial.OrderFinancialCalculator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Chunk 3 Branch 2 — order product lines + the shared live financial summary
 * (D.3 GET /lines, D.4 POST, D.5 PATCH, D.6 DELETE).
 *
 * <p>Gate ordering mirrors the existing Chunk 2 mutation pattern ({@link OrderService}):
 * standard-protected guard → manual {@code orderId}/{@code lineId} parse (VALIDATION_FAILED on
 * {@code order_id}/{@code line_id}) → order scope lookup (404 {@code ORDER_NOT_FOUND}; FOR UPDATE on
 * the mutations) → LAID gate (422 {@code ORDER_LOCKED}; GET has none) → line scope (404
 * {@code LINE_NOT_FOUND}) → body parse/validate (400) → catalog snapshot checks (404/422) →
 * derive + compute → persist → recompute summary. GET is read-only and allowed on LAID orders.
 *
 * <p>The body arrives as a raw {@code String} (parsed here, after the gates) so malformed JSON cannot
 * 400 ahead of 404/422, exactly like {@link OrderService}. Because the shared {@link ObjectMapper}
 * ignores unknown properties, forbidden backend-controlled fields are rejected explicitly via a
 * {@link JsonNode} key scan (locked review decision). All money/quantity arithmetic is
 * {@link BigDecimal} with {@link RoundingMode#HALF_UP}; the strict {@code > 0} DB CHECKs on derived
 * quantity / line_total are pre-validated in Java so a rounded-to-zero value is a clean 400, not a
 * constraint-violation 500.
 */
@Service
public class OrderProductLineService {

    private static final String STATUS_LAID = "LAID";
    private static final String PRICING_UNIT_LM = "LM";

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    // sales_order.gp_percent is DECIMAL(5,2): persist NULL when the true value would overflow
    // (locked review decision R4); the response summary still carries the true value.
    private static final BigDecimal MAX_DB_GP_PERCENT = new BigDecimal("999.99");

    // Backend-controlled fields the client must never send (conventions §10, Chunk 3 F.10).
    private static final Set<String> FORBIDDEN_BASE = Set.of(
            "cost", "cost_snapshot", "line_cost", "line_total", "price_snapshot",
            "pricing_unit_snapshot", "sqm_per_lm_snapshot", "product_code_snapshot",
            "product_name_snapshot", "created_at", "updated_at", "business_id", "store_id",
            "user_id", "order_id", "product_subtotal", "charge_subtotal",
            "calculated_total_inc_gst", "final_sale_price_inc_gst", "sale_price_ex_gst",
            "total_cost", "gp", "gp_percent", "price_adjustment_inc_gst");
    // PATCH cannot change product_id either (snapshots/product are immutable post-create).
    private static final Set<String> FORBIDDEN_PATCH = buildPatchForbidden();

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final StoreProductSnapshotRepository storeProductSnapshotRepository;
    private final OrderProductLineRepository orderProductLineRepository;
    private final OrderChargeLineReadRepository orderChargeLineReadRepository;
    private final SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository;
    private final OrderFinancialCalculator financialCalculator;
    private final ObjectMapper objectMapper;

    public OrderProductLineService(RequestContextGuard requestContextGuard,
                                   SalesOrderRepository salesOrderRepository,
                                   StoreProductSnapshotRepository storeProductSnapshotRepository,
                                   OrderProductLineRepository orderProductLineRepository,
                                   OrderChargeLineReadRepository orderChargeLineReadRepository,
                                   SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository,
                                   OrderFinancialCalculator financialCalculator,
                                   ObjectMapper objectMapper) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.storeProductSnapshotRepository = storeProductSnapshotRepository;
        this.orderProductLineRepository = orderProductLineRepository;
        this.orderChargeLineReadRepository = orderChargeLineReadRepository;
        this.salesOrderFinancialWriteRepository = salesOrderFinancialWriteRepository;
        this.financialCalculator = financialCalculator;
        this.objectMapper = objectMapper;
    }

    /** GET /orders/{orderId}/lines. Read-only; allowed on LAID; computes the summary, never persists. */
    @Transactional(readOnly = true)
    public OrderLinesResponse getLines(String slug, String orderIdRaw, HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        List<ProductLineReadDto> productLines = orderProductLineRepository.findByOrderId(orderId);
        OrderFinancialSummaryDto summary = computeSummary(orderId, order.getPriceAdjustmentIncGst());
        return new OrderLinesResponse(
                productLines,
                orderChargeLineReadRepository.findByOrderId(orderId),
                summary);
    }

    /** POST /orders/{orderId}/product-lines. */
    @Transactional
    public ProductLineMutationResponse addProductLine(String slug,
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

        // Body validation (400) — collected so details[0] is the first offending field.
        List<ErrorDetail> errors = new ArrayList<>();
        rejectForbiddenFields(node, FORBIDDEN_BASE, errors);
        Long productId = readRequiredPositiveLong(node, "product_id", errors);
        boolean hasLm = presentNonNull(node, "quantity_lm");
        boolean hasSqm = presentNonNull(node, "quantity_sqm");
        BigDecimal suppliedLm = readOptionalPositiveDecimal(node, "quantity_lm", errors);
        BigDecimal suppliedSqm = readOptionalPositiveDecimal(node, "quantity_sqm", errors);
        BigDecimal suppliedUnitPrice = readOptionalPositiveDecimal(node, "unit_price", errors);
        if (hasLm == hasSqm) {
            errors.add(new ErrorDetail(null, null, "Provide exactly one of quantity_lm or quantity_sqm."));
        }
        throwIfErrors(errors);

        // Catalog snapshot: 404 (cross-store, no leak) → 422 inactive → 422 flooring mismatch.
        StoreProductSnapshot product = storeProductSnapshotRepository
                .findByProductIdAndStoreId(productId, ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found."));
        if (!product.active()) {
            throw new BusinessRuleException(ErrorCode.PRODUCT_INACTIVE, ErrorCode.PRODUCT_INACTIVE.defaultMessage());
        }
        if (!product.flooringType().equals(order.getFlooringType())) {
            throw new BusinessRuleException(
                    ErrorCode.FLOORING_TYPE_MISMATCH, ErrorCode.FLOORING_TYPE_MISMATCH.defaultMessage());
        }

        BigDecimal sqmPerLm = product.sqmPerLm();
        BigDecimal quantityLm;
        BigDecimal quantitySqm;
        if (hasLm) {
            quantityLm = suppliedLm;
            quantitySqm = scale(suppliedLm.multiply(sqmPerLm));
        } else {
            quantitySqm = suppliedSqm;
            quantityLm = suppliedSqm.divide(sqmPerLm, MONEY_SCALE, ROUNDING);
        }
        BigDecimal unitPriceUsed = suppliedUnitPrice != null ? suppliedUnitPrice : product.price();
        LineValues line = computeLineValues(
                product.pricingUnit(), product.cost(), quantityLm, quantitySqm, unitPriceUsed);

        LocalDateTime now = LocalDateTime.now();
        ProductLineReadDto created = orderProductLineRepository.insert(
                orderId,
                productId,
                product.code(),
                product.name(),
                product.pricingUnit(),
                product.price(),
                product.cost(),
                sqmPerLm,
                line.quantityLm(),
                line.quantitySqm(),
                line.unitPrice(),
                line.lineTotal(),
                line.lineCost(),
                now);

        OrderFinancialSummaryDto summary = recomputeAndPersist(orderId, order.getPriceAdjustmentIncGst(), now);
        return new ProductLineMutationResponse(created, summary);
    }

    /** PATCH /orders/{orderId}/product-lines/{lineId}. */
    @Transactional
    public ProductLineMutationResponse updateProductLine(String slug,
                                                         String orderIdRaw,
                                                         String lineIdRaw,
                                                         String body,
                                                         HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        long lineId = parsePositiveLong(lineIdRaw, "line_id");

        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
        requireNotLaid(order);

        // Line scope (resource) before body validation (gate-first).
        ProductLineReadDto existing = orderProductLineRepository
                .findByLineIdAndOrderId(lineId, orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.LINE_NOT_FOUND, "Order line not found."));

        JsonNode node = parseBodyAfterGates(body);

        List<ErrorDetail> errors = new ArrayList<>();
        rejectForbiddenFields(node, FORBIDDEN_PATCH, errors);
        boolean hasLm = presentNonNull(node, "quantity_lm");
        boolean hasSqm = presentNonNull(node, "quantity_sqm");
        boolean hasUnitPrice = presentNonNull(node, "unit_price");
        BigDecimal suppliedLm = readOptionalPositiveDecimal(node, "quantity_lm", errors);
        BigDecimal suppliedSqm = readOptionalPositiveDecimal(node, "quantity_sqm", errors);
        BigDecimal suppliedUnitPrice = readOptionalPositiveDecimal(node, "unit_price", errors);
        if (hasLm && hasSqm) {
            errors.add(new ErrorDetail(null, null, "Provide at most one of quantity_lm or quantity_sqm."));
        }
        if (!hasLm && !hasSqm && !hasUnitPrice) {
            errors.add(new ErrorDetail(null, null,
                    "At least one of quantity_lm, quantity_sqm, or unit_price is required."));
        }
        throwIfErrors(errors);

        // Re-derive using the line's EXISTING sqm_per_lm_snapshot (immutable; never re-read catalog).
        BigDecimal sqmPerLm = existing.sqmPerLmSnapshot();
        BigDecimal quantityLm;
        BigDecimal quantitySqm;
        if (hasLm) {
            quantityLm = suppliedLm;
            quantitySqm = scale(suppliedLm.multiply(sqmPerLm));
        } else if (hasSqm) {
            quantitySqm = suppliedSqm;
            quantityLm = suppliedSqm.divide(sqmPerLm, MONEY_SCALE, ROUNDING);
        } else {
            quantityLm = existing.quantityLm();
            quantitySqm = existing.quantitySqm();
        }
        BigDecimal unitPriceUsed = hasUnitPrice ? suppliedUnitPrice : existing.unitPrice();
        LineValues line = computeLineValues(
                existing.pricingUnitSnapshot(), existing.costSnapshot(), quantityLm, quantitySqm, unitPriceUsed);

        LocalDateTime now = LocalDateTime.now();
        ProductLineReadDto updated = orderProductLineRepository.update(
                lineId,
                orderId,
                line.quantityLm(),
                line.quantitySqm(),
                line.unitPrice(),
                line.lineTotal(),
                line.lineCost(),
                now);

        OrderFinancialSummaryDto summary = recomputeAndPersist(orderId, order.getPriceAdjustmentIncGst(), now);
        return new ProductLineMutationResponse(updated, summary);
    }

    /** DELETE /orders/{orderId}/product-lines/{lineId}. */
    @Transactional
    public ProductLineDeleteResponse deleteProductLine(String slug,
                                                       String orderIdRaw,
                                                       String lineIdRaw,
                                                       HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        long lineId = parsePositiveLong(lineIdRaw, "line_id");

        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
        requireNotLaid(order);

        int deleted = orderProductLineRepository.deleteByLineIdAndOrderId(lineId, orderId);
        if (deleted == 0) {
            throw new NotFoundException(ErrorCode.LINE_NOT_FOUND, "Order line not found.");
        }

        LocalDateTime now = LocalDateTime.now();
        OrderFinancialSummaryDto summary = recomputeAndPersist(orderId, order.getPriceAdjustmentIncGst(), now);
        return new ProductLineDeleteResponse(lineId, summary);
    }

    // ------------------------------------------------------------------
    // Financial summary
    // ------------------------------------------------------------------

    /** Compute the live summary from current persisted lines + the order's adjustment (no persist). */
    private OrderFinancialSummaryDto computeSummary(long orderId, BigDecimal priceAdjustmentIncGst) {
        LineFinancials products = orderProductLineRepository.sumFinancials(orderId);
        LineFinancials charges = orderChargeLineReadRepository.sumFinancials(orderId);
        return financialCalculator.compute(products, charges, priceAdjustmentIncGst);
    }

    /**
     * Compute the live summary AND persist the {@code sales_order} header scalars (conventions §12).
     * Returns the summary with the TRUE gp_percent; the persisted column is NULLed when that value
     * would overflow {@code DECIMAL(5,2)} (R4). Negative sale_price_ex_gst is NOT clamped (R5).
     */
    private OrderFinancialSummaryDto recomputeAndPersist(long orderId,
                                                         BigDecimal priceAdjustmentIncGst,
                                                         LocalDateTime timestamp) {
        OrderFinancialSummaryDto summary = computeSummary(orderId, priceAdjustmentIncGst);
        salesOrderFinancialWriteRepository.updateHeaderFinancials(
                orderId,
                summary.salePriceExGst(),
                summary.totalCost(),
                summary.gp(),
                persistableGpPercent(summary.gpPercent()),
                timestamp);
        return summary;
    }

    private static BigDecimal persistableGpPercent(BigDecimal gpPercent) {
        if (gpPercent == null) {
            return null;
        }
        return gpPercent.abs().compareTo(MAX_DB_GP_PERCENT) <= 0 ? gpPercent : null;
    }

    // ------------------------------------------------------------------
    // Line computation
    // ------------------------------------------------------------------

    private record LineValues(BigDecimal quantityLm, BigDecimal quantitySqm, BigDecimal unitPrice,
                              BigDecimal lineTotal, BigDecimal lineCost) {
    }

    /**
     * Validate the (already-derived) quantities + unit price against the strict {@code > 0} DB CHECKs,
     * then compute line_total / line_cost off the pricing-unit quantity. A rounded-to-zero derived
     * quantity, a non-positive unit price (e.g. a 0-priced catalog product with no override), or a
     * line_total that rounds to 0.00 is a clean 400 VALIDATION_FAILED — never a DB 500.
     */
    private LineValues computeLineValues(String pricingUnit,
                                         BigDecimal costSnapshot,
                                         BigDecimal quantityLm,
                                         BigDecimal quantitySqm,
                                         BigDecimal unitPriceUsed) {
        List<ErrorDetail> errors = new ArrayList<>();
        if (quantityLm == null || quantityLm.compareTo(ZERO) <= 0) {
            errors.add(new ErrorDetail(null, "quantity_lm", "Quantity (LM) must be greater than 0."));
        }
        if (quantitySqm == null || quantitySqm.compareTo(ZERO) <= 0) {
            errors.add(new ErrorDetail(null, "quantity_sqm", "Quantity (SQM) must be greater than 0."));
        }
        if (unitPriceUsed == null || unitPriceUsed.compareTo(ZERO) <= 0) {
            errors.add(new ErrorDetail(null, "unit_price", "Unit price must be greater than 0."));
        }
        throwIfErrors(errors);

        BigDecimal quantityForCalc = PRICING_UNIT_LM.equals(pricingUnit) ? quantityLm : quantitySqm;
        BigDecimal lineTotal = scale(quantityForCalc.multiply(unitPriceUsed));
        BigDecimal lineCost = scale(quantityForCalc.multiply(costSnapshot));
        if (lineTotal.compareTo(ZERO) <= 0) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, null,
                            "Resulting line total must be greater than 0; increase quantity or unit price.")));
        }
        return new LineValues(quantityLm, quantitySqm, unitPriceUsed, lineTotal, lineCost);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    // ------------------------------------------------------------------
    // Gates / parsing / validation helpers
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
     * Parse the raw body to a {@link JsonNode} AFTER the gates. Null/blank → null (treated as
     * all-fields-missing). Unparseable JSON → {@link MalformedJsonException} (400 MALFORMED_JSON),
     * same wrapper/order semantics as {@link OrderService#parseBodyAfterGates} for the Chunk 2
     * mutations. Reading via JsonNode (rather than a typed record) is required so forbidden
     * backend-controlled fields can be explicitly rejected — the shared ObjectMapper ignores
     * unknown properties.
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

    private static void rejectForbiddenFields(JsonNode node, Set<String> forbidden, List<ErrorDetail> errors) {
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (forbidden.contains(name)) {
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

    private static Long readRequiredPositiveLong(JsonNode node, String field, List<ErrorDetail> errors) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            errors.add(new ErrorDetail(null, field, "Required."));
            return null;
        }
        // isIntegralNumber() rejects fractional values (e.g. 1.5 DoubleNode); canConvertToLong()
        // additionally rejects integers outside the signed-long range (e.g. a BigIntegerNode above
        // Long.MAX_VALUE), which longValue() would otherwise silently truncate/wrap.
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            errors.add(new ErrorDetail(null, field, "Must be a positive integer."));
            return null;
        }
        long parsed = value.longValue();
        if (parsed <= 0) {
            errors.add(new ErrorDetail(null, field, "Must be a positive integer."));
            return null;
        }
        return parsed;
    }

    /**
     * Optional numeric field: absent or explicit JSON null → null (PATCH "unchanged"); a non-number
     * or a value ≤ 0 → VALIDATION_FAILED on {@code field}; otherwise the {@link BigDecimal} value.
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
        // Parse via the textual form so a JSON decimal (e.g. 14.64) is captured exactly, avoiding
        // the binary-floating-point drift of JsonNode.decimalValue() on DoubleNode. Normalize to the
        // 2dp money/quantity scale (HALF_UP) BEFORE any derivation, line_total/line_cost calculation,
        // or persistence, so the stored unit_price/quantity and the computed totals stay internally
        // consistent — a client value like 1.235 becomes 1.24 everywhere, not 1.235 in the math and
        // 1.24 in the DECIMAL(10,2) column. Validate the SCALED value: if it rounds to 0.00 or less,
        // it is a 400 VALIDATION_FAILED.
        BigDecimal parsed = new BigDecimal(value.asText()).setScale(MONEY_SCALE, ROUNDING);
        if (parsed.compareTo(ZERO) <= 0) {
            errors.add(new ErrorDetail(null, field, "Must be greater than 0."));
            return null;
        }
        return parsed;
    }

    private static void throwIfErrors(List<ErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }

    private static Set<String> buildPatchForbidden() {
        Set<String> patch = new HashSet<>(FORBIDDEN_BASE);
        patch.add("product_id");
        return Set.copyOf(patch);
    }
}
