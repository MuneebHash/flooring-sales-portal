package com.flooring.salesportal.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.catalog.StoreChargeSnapshotRepository;
import com.flooring.salesportal.catalog.StoreChargeSnapshotRepository.StoreChargeSnapshot;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.order.dto.ChargeLineDeleteResponse;
import com.flooring.salesportal.order.dto.ChargeLineMutationResponse;
import com.flooring.salesportal.order.dto.ChargeLineReadDto;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
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
 * Chunk 3 Branch 3 — order charge lines + the shared live financial summary
 * (D.7 POST, D.8 PATCH, D.9 DELETE). The read path (GET /lines + the charge half of the summary) is
 * Branch 2's {@link OrderProductLineService} / {@link OrderChargeLineReadRepository} and is untouched.
 *
 * <p>Gate ordering mirrors the Branch 2 product-line pattern: standard-protected guard → manual
 * {@code orderId}/{@code lineId} parse (VALIDATION_FAILED on {@code order_id}/{@code line_id}) → order
 * scope lookup (404 {@code ORDER_NOT_FOUND}; FOR UPDATE on the mutations) → LAID gate (422
 * {@code ORDER_LOCKED}) → line scope on PATCH/DELETE (404 {@code LINE_NOT_FOUND}) → body parse/validate
 * (400) → charge snapshot checks (404 {@code CHARGE_NOT_FOUND} → 422 {@code CHARGE_INACTIVE} → 422
 * {@code FLOORING_TYPE_MISMATCH}) → compute → persist → recompute summary. The body arrives as a raw
 * {@code String} (parsed here, after the gates) so malformed JSON cannot 400 ahead of 404/422, and
 * forbidden backend-controlled fields are rejected via an explicit {@link JsonNode} key scan (the
 * shared {@link ObjectMapper} ignores unknown properties).
 *
 * <p>Charges have a single {@code quantity} (no LM/SQM conversion, no {@code sqm_per_lm}, no pricing
 * unit — those are product-only). All money/quantity arithmetic is {@link BigDecimal} with
 * {@link RoundingMode#HALF_UP}; the strict {@code > 0} DB CHECKs on {@code quantity}/{@code unit_price}/
 * {@code line_total} are pre-validated in Java so a rounded-to-zero value is a clean 400, not a 500.
 *
 * <p>Per the locked Branch 3 decision the Branch 2 helper logic is DUPLICATED here rather than
 * extracted from {@link OrderProductLineService} (which is left untouched).
 */
@Service
public class OrderChargeLineService {

    private static final String STATUS_LAID = "LAID";

    private static final int MONEY_SCALE = 2;
    // order_charge_line money/quantity columns and the persisted sales_order sale_price_ex_gst /
    // total_cost / gp are all DECIMAL(10,2) (V2). A value scaled to 2dp fits iff it has at most
    // (10 - 2) = 8 integer digits, i.e. |value| <= 99999999.99.
    private static final int MONEY_PRECISION = 10;
    private static final int MONEY_MAX_INTEGER_DIGITS = MONEY_PRECISION - MONEY_SCALE;
    private static final String MONEY_MAX_LABEL = "99999999.99";
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    // sales_order.gp_percent is DECIMAL(5,2): persist NULL when the true value would overflow
    // (locked review decision R4); the response summary still carries the true value.
    private static final BigDecimal MAX_DB_GP_PERCENT = new BigDecimal("999.99");

    // Backend-controlled fields the client must never send (conventions §10, Chunk 3 F.10). Charge
    // lines have no pricing_unit/sqm/product snapshots — only the charge_*_snapshot variants.
    private static final Set<String> FORBIDDEN_BASE = Set.of(
            "cost", "cost_snapshot", "line_cost", "line_total", "price_snapshot",
            "charge_code_snapshot", "charge_name_snapshot", "created_at", "updated_at",
            "business_id", "store_id", "user_id", "order_id", "product_subtotal", "charge_subtotal",
            "calculated_total_inc_gst", "final_sale_price_inc_gst", "sale_price_ex_gst",
            "total_cost", "gp", "gp_percent", "price_adjustment_inc_gst");
    // PATCH cannot change charge_id either (the charge / its snapshots are immutable post-create).
    private static final Set<String> FORBIDDEN_PATCH = buildPatchForbidden();
    // Every "*_snapshot" column is backend-owned and must be rejected if a client sends it, including
    // the product-only pricing_unit_snapshot / sqm_per_lm_snapshot (conventions §10, Chunk 3 F.10).
    private static final String SNAPSHOT_SUFFIX = "_snapshot";
    // The ONLY request-body keys a client may send. Anything else (unknown / misspelled / backend-
    // controlled) is rejected — OpenAPI ChargeLineCreateRequest / ChargeLinePatchRequest both declare
    // additionalProperties: false.
    private static final Set<String> POST_ALLOWED_FIELDS = Set.of("charge_id", "quantity", "unit_price");
    private static final Set<String> PATCH_ALLOWED_FIELDS = Set.of("quantity", "unit_price");

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final StoreChargeSnapshotRepository storeChargeSnapshotRepository;
    private final OrderChargeLineWriteRepository orderChargeLineWriteRepository;
    private final OrderProductLineRepository orderProductLineRepository;
    private final OrderChargeLineReadRepository orderChargeLineReadRepository;
    private final SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository;
    private final OrderFinancialCalculator financialCalculator;
    private final ObjectMapper objectMapper;

    public OrderChargeLineService(RequestContextGuard requestContextGuard,
                                  SalesOrderRepository salesOrderRepository,
                                  StoreChargeSnapshotRepository storeChargeSnapshotRepository,
                                  OrderChargeLineWriteRepository orderChargeLineWriteRepository,
                                  OrderProductLineRepository orderProductLineRepository,
                                  OrderChargeLineReadRepository orderChargeLineReadRepository,
                                  SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository,
                                  OrderFinancialCalculator financialCalculator,
                                  ObjectMapper objectMapper) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.storeChargeSnapshotRepository = storeChargeSnapshotRepository;
        this.orderChargeLineWriteRepository = orderChargeLineWriteRepository;
        this.orderProductLineRepository = orderProductLineRepository;
        this.orderChargeLineReadRepository = orderChargeLineReadRepository;
        this.salesOrderFinancialWriteRepository = salesOrderFinancialWriteRepository;
        this.financialCalculator = financialCalculator;
        this.objectMapper = objectMapper;
    }

    /** POST /orders/{orderId}/charge-lines. */
    @Transactional
    public ChargeLineMutationResponse addChargeLine(String slug,
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
        rejectDisallowedFields(node, FORBIDDEN_BASE, POST_ALLOWED_FIELDS, errors);
        Long chargeId = readRequiredPositiveLong(node, "charge_id", errors);
        BigDecimal suppliedQuantity = readRequiredPositiveDecimal(node, "quantity", errors);
        BigDecimal suppliedUnitPrice = readOptionalPositiveDecimal(node, "unit_price", errors);
        throwIfErrors(errors);

        // Catalog snapshot: 404 (cross-store, no leak) → 422 inactive → 422 flooring mismatch.
        StoreChargeSnapshot charge = storeChargeSnapshotRepository
                .findByChargeIdAndStoreId(chargeId, ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.CHARGE_NOT_FOUND, "Charge not found."));
        if (!charge.active()) {
            throw new BusinessRuleException(ErrorCode.CHARGE_INACTIVE, ErrorCode.CHARGE_INACTIVE.defaultMessage());
        }
        if (!charge.flooringType().equals(order.getFlooringType())) {
            throw new BusinessRuleException(
                    ErrorCode.FLOORING_TYPE_MISMATCH, ErrorCode.FLOORING_TYPE_MISMATCH.defaultMessage());
        }

        BigDecimal unitPriceUsed = suppliedUnitPrice != null ? suppliedUnitPrice : charge.price();
        LineValues line = computeLineValues(charge.cost(), suppliedQuantity, unitPriceUsed);

        // Validate the projected header (width + non-negative total cost) BEFORE the insert, so a
        // rejected mutation writes nothing. Product totals are unchanged; only charges gain this line.
        LineFinancials currentCharges = orderChargeLineReadRepository.sumFinancials(orderId);
        LineFinancials projectedCharges = new LineFinancials(
                currentCharges.subtotal().add(line.lineTotal()),
                currentCharges.cost().add(line.lineCost()));
        OrderFinancialSummaryDto summary =
                projectedSummary(orderId, projectedCharges, order.getPriceAdjustmentIncGst());
        requirePersistableHeader(summary);

        LocalDateTime now = LocalDateTime.now();
        ChargeLineReadDto created = orderChargeLineWriteRepository.insert(
                orderId,
                chargeId,
                charge.code(),
                charge.name(),
                charge.price(),
                charge.cost(),
                line.quantity(),
                line.unitPrice(),
                line.lineTotal(),
                line.lineCost(),
                now);
        persistHeader(orderId, summary, now);
        return new ChargeLineMutationResponse(created, summary);
    }

    /** PATCH /orders/{orderId}/charge-lines/{lineId}. */
    @Transactional
    public ChargeLineMutationResponse updateChargeLine(String slug,
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
        ChargeLineReadDto existing = orderChargeLineWriteRepository
                .findByLineIdAndOrderId(lineId, orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.LINE_NOT_FOUND, "Order line not found."));

        JsonNode node = parseBodyAfterGates(body);

        List<ErrorDetail> errors = new ArrayList<>();
        rejectDisallowedFields(node, FORBIDDEN_PATCH, PATCH_ALLOWED_FIELDS, errors);
        boolean hasQuantity = presentNonNull(node, "quantity");
        boolean hasUnitPrice = presentNonNull(node, "unit_price");
        BigDecimal suppliedQuantity = readOptionalPositiveDecimal(node, "quantity", errors);
        BigDecimal suppliedUnitPrice = readOptionalPositiveDecimal(node, "unit_price", errors);
        if (!hasQuantity && !hasUnitPrice) {
            errors.add(new ErrorDetail(null, null, "At least one of quantity or unit_price is required."));
        }
        throwIfErrors(errors);

        // Explicit null for an optional field means unchanged; recompute uses the EXISTING immutable
        // cost_snapshot (never re-read the catalog).
        BigDecimal quantityUsed = hasQuantity ? suppliedQuantity : existing.quantity();
        BigDecimal unitPriceUsed = hasUnitPrice ? suppliedUnitPrice : existing.unitPrice();
        LineValues line = computeLineValues(existing.costSnapshot(), quantityUsed, unitPriceUsed);

        // Validate the projected header BEFORE the update, so a rejected mutation leaves the line
        // unchanged. Swap the existing line's old contribution for the new one; old line_cost is
        // reconstructed from the immutable cost_snapshot.
        LineFinancials currentCharges = orderChargeLineReadRepository.sumFinancials(orderId);
        LineFinancials projectedCharges = new LineFinancials(
                currentCharges.subtotal().subtract(existing.lineTotal()).add(line.lineTotal()),
                currentCharges.cost().subtract(reconstructLineCost(existing)).add(line.lineCost()));
        OrderFinancialSummaryDto summary =
                projectedSummary(orderId, projectedCharges, order.getPriceAdjustmentIncGst());
        requirePersistableHeader(summary);

        LocalDateTime now = LocalDateTime.now();
        ChargeLineReadDto updated = orderChargeLineWriteRepository.update(
                lineId,
                orderId,
                line.quantity(),
                line.unitPrice(),
                line.lineTotal(),
                line.lineCost(),
                now);
        persistHeader(orderId, summary, now);
        return new ChargeLineMutationResponse(updated, summary);
    }

    /** DELETE /orders/{orderId}/charge-lines/{lineId}. */
    @Transactional
    public ChargeLineDeleteResponse deleteChargeLine(String slug,
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

        // Read the line first (404 if not on this order) so its contribution can be removed from the
        // projected header and validated BEFORE the delete — a rejected mutation deletes nothing.
        ChargeLineReadDto existing = orderChargeLineWriteRepository
                .findByLineIdAndOrderId(lineId, orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.LINE_NOT_FOUND, "Order line not found."));

        LineFinancials currentCharges = orderChargeLineReadRepository.sumFinancials(orderId);
        LineFinancials projectedCharges = new LineFinancials(
                currentCharges.subtotal().subtract(existing.lineTotal()),
                currentCharges.cost().subtract(reconstructLineCost(existing)));
        OrderFinancialSummaryDto summary =
                projectedSummary(orderId, projectedCharges, order.getPriceAdjustmentIncGst());
        requirePersistableHeader(summary);

        LocalDateTime now = LocalDateTime.now();
        orderChargeLineWriteRepository.deleteByLineIdAndOrderId(lineId, orderId);
        persistHeader(orderId, summary, now);
        return new ChargeLineDeleteResponse(lineId, summary);
    }

    // ------------------------------------------------------------------
    // Financial summary
    // ------------------------------------------------------------------

    /**
     * Build the live summary from the order's current PRODUCT totals + the PROJECTED post-mutation
     * charge totals + adjustment, WITHOUT touching the DB. Callers validate this (see
     * {@link #requirePersistableHeader}) BEFORE applying the line write, so a rejected mutation writes
     * nothing — important because a write-then-rollback would still be visible within an open
     * transaction. The projected totals equal a post-write recompute (current sums ± this line's
     * delta), so {@link #persistHeader} persists this same summary after the write with no re-query.
     */
    private OrderFinancialSummaryDto projectedSummary(long orderId, LineFinancials projectedCharges,
                                                      BigDecimal priceAdjustmentIncGst) {
        LineFinancials products = orderProductLineRepository.sumFinancials(orderId);
        return financialCalculator.compute(products, projectedCharges, priceAdjustmentIncGst);
    }

    /**
     * Persist the recomputed {@code sales_order} header scalars (conventions §12). The summary carries
     * the TRUE gp_percent; the persisted column is NULLed when that value would overflow
     * {@code DECIMAL(5,2)} (R4). {@code price_adjustment_inc_gst} is NOT written here — it is owned by
     * the sale-price override endpoint (D.10/D.11) and is only read by the calculator. Call only after
     * {@link #requirePersistableHeader} has passed.
     */
    private void persistHeader(long orderId, OrderFinancialSummaryDto summary, LocalDateTime timestamp) {
        salesOrderFinancialWriteRepository.updateHeaderFinancials(
                orderId,
                summary.salePriceExGst(),
                summary.totalCost(),
                summary.gp(),
                persistableGpPercent(summary.gpPercent()),
                timestamp);
    }

    /**
     * Reconstruct a charge line's {@code line_cost} from its immutable {@code cost_snapshot} and stored
     * {@code quantity}, so a mutation can adjust the projected {@code total_cost} delta without exposing
     * per-line cost. Equals the stored value exactly ({@code line_cost = round(quantity × cost_snapshot, 2)}).
     */
    private static BigDecimal reconstructLineCost(ChargeLineReadDto line) {
        return scale(line.quantity().multiply(line.costSnapshot()));
    }

    /**
     * Guard the persisted {@code sales_order} header scalars against the column CHECK constraints so a
     * charge-line mutation that would violate one becomes a clean 400 VALIDATION_FAILED instead of a
     * PostgreSQL 500. Callers run this on the PROJECTED summary BEFORE the line write.
     *
     * <ul>
     *   <li>{@code sale_price_ex_gst} — DECIMAL(10,2). Negative values are ALLOWED while building or
     *       editing an order (a charge edit/delete on top of a stored negative
     *       {@code price_adjustment_inc_gst} can legitimately drive it below 0). The real value is
     *       persisted — never clamped, floored, or silently changed — so only its DECIMAL(10,2) width
     *       is guarded here. A negative FINAL sale price is blocked only at invoice creation
     *       (conventions §14); the V3 {@code chk_sales_order_sale_price_gte_zero} CHECK was relaxed in
     *       V9 so the negative value can persist.</li>
     *   <li>{@code total_cost} — DECIMAL(10,2) with {@code chk_sales_order_total_cost_gte_zero} (>= 0);
     *       guarded defensively against a DB 500.</li>
     *   <li>{@code gp} — has NO non-negative CHECK (negative GP is allowed, conventions §12); only its
     *       DECIMAL(10,2) width is guarded.</li>
     *   <li>{@code gp_percent} — handled separately (persisted NULL on DECIMAL(5,2) overflow, R4).</li>
     * </ul>
     */
    private static void requirePersistableHeader(OrderFinancialSummaryDto summary) {
        List<ErrorDetail> errors = new ArrayList<>();
        // Negative sale_price_ex_gst is allowed while editing — persist the real value, never clamp
        // or floor it. Only its DECIMAL(10,2) width is guarded so an oversized value is a clean 400.
        BigDecimal salePrice = summary.salePriceExGst();
        if (!fitsMoney(salePrice)) {
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
    // Line computation
    // ------------------------------------------------------------------

    private record LineValues(BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                              BigDecimal lineCost) {
    }

    /**
     * Validate quantity + unit price against the strict {@code > 0} DB CHECKs, then compute
     * line_total / line_cost. A non-positive quantity/unit price or a line_total that rounds to 0.00
     * is a clean 400 VALIDATION_FAILED — never a DB 500. Charges have no LM/SQM derivation, so the
     * quantity used for both products is the single supplied/stored value.
     */
    private LineValues computeLineValues(BigDecimal costSnapshot,
                                         BigDecimal quantity,
                                         BigDecimal unitPrice) {
        List<ErrorDetail> errors = new ArrayList<>();
        validatePositiveInRange(quantity, "quantity", "Quantity", errors);
        validatePositiveInRange(unitPrice, "unit_price", "Unit price", errors);
        throwIfErrors(errors);

        BigDecimal lineTotal = scale(quantity.multiply(unitPrice));
        BigDecimal lineCost = scale(quantity.multiply(costSnapshot));
        if (lineTotal.compareTo(ZERO) <= 0) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, null,
                            "Resulting line total must be greater than 0; increase quantity or unit price.")));
        }
        // line_total and line_cost target DECIMAL(10,2): a product of two in-range values can still
        // overflow the column, so guard both before the native write to avoid a 500.
        if (!fitsMoney(lineTotal)) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, null,
                            "Resulting line total exceeds the maximum supported value (" + MONEY_MAX_LABEL
                                    + "); reduce quantity or unit price.")));
        }
        if (!fitsMoney(lineCost)) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, null,
                            "Resulting line cost exceeds the maximum supported value (" + MONEY_MAX_LABEL
                                    + "); reduce quantity.")));
        }
        return new LineValues(quantity, unitPrice, lineTotal, lineCost);
    }

    private static void validatePositiveInRange(BigDecimal value, String field, String label,
                                                List<ErrorDetail> errors) {
        if (value == null || value.compareTo(ZERO) <= 0) {
            errors.add(new ErrorDetail(null, field, label + " must be greater than 0."));
        } else if (!fitsMoney(value)) {
            errors.add(new ErrorDetail(null, field, label + " exceeds the maximum supported value ("
                    + MONEY_MAX_LABEL + ")."));
        }
    }

    /** True iff {@code value}, scaled to 2dp, fits a DECIMAL(10,2) column (|value| <= 99999999.99). */
    private static boolean fitsMoney(BigDecimal value) {
        BigDecimal scaled = value.setScale(MONEY_SCALE, ROUNDING);
        return scaled.precision() - scaled.scale() <= MONEY_MAX_INTEGER_DIGITS;
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
     * all-fields-missing). Unparseable JSON → {@link MalformedJsonException} (400 MALFORMED_JSON).
     * Reading via JsonNode (rather than a typed record) is required so forbidden backend-controlled
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
        // Scan ONLY the JSON request body keys (never path variables). A key is rejected when it is an
        // explicit backend-controlled field, ends in "_snapshot" (every snapshot column is backend-
        // owned, incl. the product-only pricing_unit_snapshot / sqm_per_lm_snapshot), OR is simply not
        // in the allowed set (OpenAPI additionalProperties: false — unknown/misspelled keys are 400).
        // One "Not allowed." detail per offending key, so a key that is both unknown and forbidden is
        // reported once.
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

    private static Long readRequiredPositiveLong(JsonNode node, String field, List<ErrorDetail> errors) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            errors.add(new ErrorDetail(null, field, "Required."));
            return null;
        }
        // isIntegralNumber() rejects fractional values; canConvertToLong() rejects integers outside
        // the signed-long range (e.g. a BigIntegerNode above Long.MAX_VALUE), which longValue() would
        // otherwise silently truncate/wrap.
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

    /** Required numeric field: absent or explicit JSON null → "Required."; otherwise validated like {@link #readOptionalPositiveDecimal}. */
    private static BigDecimal readRequiredPositiveDecimal(JsonNode node, String field, List<ErrorDetail> errors) {
        if (!presentNonNull(node, field)) {
            errors.add(new ErrorDetail(null, field, "Required."));
            return null;
        }
        return readOptionalPositiveDecimal(node, field, errors);
    }

    /**
     * Optional numeric field: absent or explicit JSON null → null (PATCH "unchanged"); a non-number
     * or a value ≤ 0 → VALIDATION_FAILED on {@code field}; otherwise the {@link BigDecimal} value,
     * normalized to the 2dp money/quantity scale (HALF_UP) BEFORE any line_total/line_cost calculation
     * or persistence, so the stored value and the computed totals stay internally consistent.
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
        // still reports isNumber() == true on a DoubleNode (e.g. 1e309 → "Infinity", -1e309 →
        // "-Infinity", or "NaN") but is NOT a valid BigDecimal, so guard the parse/scale and turn it
        // into a clean 400 VALIDATION_FAILED rather than letting NumberFormatException escape as a 500.
        // Validate the SCALED value.
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
        // Reject values that cannot fit DECIMAL(10,2), so an oversized but positive input is a 400
        // rather than a PostgreSQL numeric-overflow 500 on persist.
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

    private static Set<String> buildPatchForbidden() {
        Set<String> patch = new HashSet<>(FORBIDDEN_BASE);
        patch.add("charge_id");
        return Set.copyOf(patch);
    }
}
