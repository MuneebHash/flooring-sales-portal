package com.flooring.salesportal.order.quote;

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
import com.flooring.salesportal.order.OrderChargeLineReadRepository;
import com.flooring.salesportal.order.OrderProductLineRepository;
import com.flooring.salesportal.order.SalesOrder;
import com.flooring.salesportal.order.SalesOrderFinancialWriteRepository;
import com.flooring.salesportal.order.SalesOrderRepository;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import com.flooring.salesportal.order.financial.LineFinancials;
import com.flooring.salesportal.order.financial.OrderFinancialCalculator;
import com.flooring.salesportal.order.quote.dto.QuoteDraftDto;
import com.flooring.salesportal.order.quote.dto.QuoteDraftLineDto;
import com.flooring.salesportal.order.quote.dto.QuoteWorkspaceDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 16C PR1 — quote money/data core. Backs the two protected endpoints:
 * <ul>
 *   <li>GET {@code /orders/{orderId}/quote/workspace} — loads the draft (issued/accepted are null in PR1).</li>
 *   <li>PUT {@code /orders/{orderId}/quote/draft} — full-replace upsert of the editable draft.</li>
 * </ul>
 *
 * <p>The draft PUT follows the locked protected-mutation gate order (mirrors
 * {@link com.flooring.salesportal.order.OrderSalePriceService} / {@code OrderService.saveEnquiry}):
 * standard-protected guard → manual {@code orderId} parse (400 {@code VALIDATION_FAILED}) → scoped
 * {@code FOR UPDATE} order lookup (404 {@code ORDER_NOT_FOUND}; never 403/leak) → LAID gate (422
 * {@code ORDER_LOCKED}) → raw-body parse (400 {@code MALFORMED_JSON}) → validate → compute → persist
 * → update the order sale-price override — all inside one transaction holding the order lock.
 *
 * <p>Money rules ({@link QuoteDraftCalculator}): quote total = sum of lines; a direct reduction
 * inserts a negative ADJUSTMENT line; a direct increase above the line sum is rejected with
 * {@code QUOTE_TOTAL_EXCEEDS_LINES}; a below-cost quote is rejected with {@code QUOTE_BELOW_COST}.
 * Saving pushes {@code quote_total_inc_gst} into the order's cosmetic sale-price override by reusing
 * the existing D.10 derivation ({@link OrderFinancialCalculator} +
 * {@link SalesOrderFinancialWriteRepository#updateHeaderFinancialsWithAdjustment}) — never a new
 * adjustment-SQL path, never a below-cost block on the manual override path. A zero-total quote
 * skips the override push (leaves the existing override untouched). Quote lines never carry or
 * expose cost; the order's product/charge lines are never mutated by a quote save.
 */
@Service
public class QuoteService {

    private static final String STATUS_LAID = "LAID";

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int MONEY_MAX_INTEGER_DIGITS = 10 - MONEY_SCALE; // DECIMAL(10,2)
    private static final String MONEY_MAX_LABEL = "99999999.99";
    // sales_order.gp_percent is DECIMAL(5,2): persist NULL when the true value would overflow (R4).
    private static final BigDecimal MAX_DB_GP_PERCENT = new BigDecimal("999.99");
    // Upper bound on a line's sort_order. Caps client input so the auto-inserted ADJUSTMENT line's
    // sort_order can never overflow Integer (Codex finding). SINGLE SOURCE OF TRUTH lives on
    // QuoteDraftCalculator so the validation cap (here) and the generation cap (the adjustment
    // sort_order in QuoteDraftCalculator.nextSortOrder) can never drift apart.
    private static final int MAX_SORT_ORDER = QuoteDraftCalculator.MAX_SORT_ORDER;

    private static final String FIELD_ITEMISED = "itemised";
    private static final String FIELD_FINAL_TOTAL = "final_total_inc_gst";
    private static final String FIELD_LINES = "lines";
    private static final String SNAPSHOT_SUFFIX = "_snapshot";
    private static final String COST_FRAGMENT = "cost";

    private static final Set<String> TOP_ALLOWED =
            Set.of(FIELD_ITEMISED, FIELD_FINAL_TOTAL, FIELD_LINES);
    private static final Set<String> LINE_ALLOWED = Set.of(
            "line_type", "description", "quantity", "unit_price_ex_gst",
            "line_total_ex_gst", "sort_order", "quote_draft_line_id");

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderProductLineRepository orderProductLineRepository;
    private final OrderChargeLineReadRepository orderChargeLineReadRepository;
    private final OrderFinancialCalculator financialCalculator;
    private final SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository;
    private final QuoteDraftRepository quoteDraftRepository;
    private final QuoteDraftLineRepository quoteDraftLineRepository;
    private final QuoteDraftWriteRepository quoteDraftWriteRepository;
    private final QuoteDraftCalculator quoteDraftCalculator;
    private final QuotePdfModelAssembler quotePdfModelAssembler;
    private final QuotePdfGenerator quotePdfGenerator;
    private final ObjectMapper objectMapper;

    public QuoteService(RequestContextGuard requestContextGuard,
                        SalesOrderRepository salesOrderRepository,
                        OrderProductLineRepository orderProductLineRepository,
                        OrderChargeLineReadRepository orderChargeLineReadRepository,
                        OrderFinancialCalculator financialCalculator,
                        SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository,
                        QuoteDraftRepository quoteDraftRepository,
                        QuoteDraftLineRepository quoteDraftLineRepository,
                        QuoteDraftWriteRepository quoteDraftWriteRepository,
                        QuoteDraftCalculator quoteDraftCalculator,
                        QuotePdfModelAssembler quotePdfModelAssembler,
                        QuotePdfGenerator quotePdfGenerator,
                        ObjectMapper objectMapper) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.orderProductLineRepository = orderProductLineRepository;
        this.orderChargeLineReadRepository = orderChargeLineReadRepository;
        this.financialCalculator = financialCalculator;
        this.salesOrderFinancialWriteRepository = salesOrderFinancialWriteRepository;
        this.quoteDraftRepository = quoteDraftRepository;
        this.quoteDraftLineRepository = quoteDraftLineRepository;
        this.quoteDraftWriteRepository = quoteDraftWriteRepository;
        this.quoteDraftCalculator = quoteDraftCalculator;
        this.quotePdfModelAssembler = quotePdfModelAssembler;
        this.quotePdfGenerator = quotePdfGenerator;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // GET /orders/{orderId}/quote/workspace
    // ------------------------------------------------------------------

    /** Read the quote workspace. LAID read is allowed. current_issued / accepted are null in PR1. */
    @Transactional(readOnly = true)
    public QuoteWorkspaceDto getWorkspace(String slug, String orderIdRaw, HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parseOrderId(orderIdRaw);

        // Scoped, non-locking read. Out-of-scope / missing → 404 (never confirms cross-tenant existence).
        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        Optional<QuoteDraft> draftOpt = quoteDraftRepository.findByOrderId(orderId);
        if (draftOpt.isEmpty()) {
            return QuoteWorkspaceDto.draftOnly(null);
        }

        QuoteDraft draft = draftOpt.get();
        List<QuoteDraftLine> lineEntities =
                quoteDraftLineRepository.findByQuoteDraftIdOrderBySortOrderAsc(draft.getQuoteDraftId());

        // Live GP / below-cost against the order's CURRENT product/charge cost lines: a workspace read
        // can show below_cost = true even though the last save passed, if cost lines changed since.
        BigDecimal totalCostExGst = orderTotalCostExGst(orderId);
        BigDecimal gpPercent = quoteDraftCalculator.gpPercent(draft.getQuoteTotalExGst(), totalCostExGst);
        boolean belowCost = quoteDraftCalculator.belowCost(draft.getQuoteTotalExGst(), totalCostExGst);

        QuoteDraftDto draftDto = new QuoteDraftDto(
                draft.isItemised(),
                draft.getQuoteTotalExGst(),
                draft.getQuoteTotalIncGst(),
                gpPercent,
                belowCost,
                toLineDtos(lineEntities),
                draft.getUpdatedAt());
        return QuoteWorkspaceDto.draftOnly(draftDto);
    }

    // ------------------------------------------------------------------
    // POST /orders/{orderId}/quote/preview-pdf
    // ------------------------------------------------------------------

    /**
     * Render the current quote DRAFT to a preview PDF (Phase 16C PR2, contract §7.1). Read-only and
     * side-effect-free: NOTHING is persisted (no stored_file, no quote_version, no quote_token, no draft
     * timestamp touch, no sale-price override write). The gate order mirrors the workspace read —
     * standard-protected guard → orderId parse (400 VALIDATION_FAILED) → scoped NON-LOCKING order lookup
     * (404 ORDER_NOT_FOUND; never 403/leak) → empty-body validation (400 MALFORMED_JSON /
     * VALIDATION_FAILED, strictly AFTER the scoped lookup) → load draft (404 QUOTE_NOT_FOUND when absent).
     * LAID is allowed (no requireNotLaid) and below-cost is allowed (the persisted draft totals are read;
     * the QUOTE_BELOW_COST guard in {@link QuoteDraftCalculator#compute} is never invoked on preview).
     *
     * <p><b>Consistent snapshot (REPEATABLE_READ).</b> The draft header, draft lines, order, customer,
     * address, store, and terms are read in separate statements; under READ COMMITTED a concurrent
     * {@code PUT /quote/draft} could commit between them and the PDF would mix stale totals with newer
     * lines. REPEATABLE_READ pins every read to one snapshot. It stays read-only and takes NO row locks
     * (no {@code FOR UPDATE}), so it never blocks a concurrent draft save.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuotePreviewResult previewPdf(String slug, String orderIdRaw, String body, HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parseOrderId(orderIdRaw);

        // Scoped, non-locking read. Out-of-scope / missing → 404 (never confirms cross-tenant existence).
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // Body validated AFTER the scoped lookup (so malformed JSON cannot 400 ahead of the 404). The
        // preview takes no meaningful body: missing/blank/{} are accepted; any field or a non-object is
        // 400 VALIDATION_FAILED; malformed JSON is 400 MALFORMED_JSON.
        validatePreviewBody(body);

        QuoteDraft draft = quoteDraftRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.QUOTE_NOT_FOUND,
                        ErrorCode.QUOTE_NOT_FOUND.defaultMessage()));
        List<QuoteDraftLine> lines =
                quoteDraftLineRepository.findByQuoteDraftIdOrderBySortOrderAsc(draft.getQuoteDraftId());

        QuotePdfModel model = quotePdfModelAssembler.assemble(ctx.business(), order, orderId, draft, lines);
        byte[] bytes = quotePdfGenerator.render(model);
        String fileName = "quote-preview-" + order.getOrderNumber() + ".pdf";
        return new QuotePreviewResult(fileName, bytes);
    }

    /** Rendered preview PDF + the inline file name the controller streams (never a stored file). */
    public record QuotePreviewResult(String fileName, byte[] bytes) {
    }

    /**
     * Validate the preview request body: missing/blank/{@code {}} accepted; malformed JSON →
     * MALFORMED_JSON; a non-empty object (any field) or ANY non-object JSON (literal {@code null},
     * {@code []}, string, number, boolean) → VALIDATION_FAILED. Mirrors the OpenAPI EmptyObjectRequest
     * (type object, additionalProperties false) but never REQUIRES a body.
     */
    private void validatePreviewBody(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new MalformedJsonException();
        }
        // root == null / MissingNode means nothing parseable (defensive) -> treat as no body. A JSON
        // literal `null` parses to a NullNode (isNull()), which is NOT an object -> validation failure.
        if (root == null || root.isMissingNode()) {
            return;
        }
        List<ErrorDetail> errors = new ArrayList<>();
        if (!root.isObject()) {
            errors.add(new ErrorDetail(null, null, "Request body must be an empty JSON object."));
        } else {
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                errors.add(new ErrorDetail(null, names.next(), "Not allowed."));
            }
        }
        throwIfErrors(errors);
    }

    // ------------------------------------------------------------------
    // PUT /orders/{orderId}/quote/draft
    // ------------------------------------------------------------------

    @Transactional
    public QuoteDraftDto saveDraft(String slug, String orderIdRaw, String body, HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parseOrderId(orderIdRaw);

        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
        requireNotLaid(order);

        // Body parsed AFTER the gates so malformed JSON cannot 400 ahead of 404/422.
        JsonNode root = parseBodyAfterGates(body);
        ParsedDraft parsed = validateAndParse(root);

        // Order cost basis (product/charge lines): fetched ONCE and reused for below-cost + the
        // sale-price override derivation. Quote lines are never mutated; these are read-only sums.
        LineFinancials products = orderProductLineRepository.sumFinancials(orderId);
        LineFinancials charges = orderChargeLineReadRepository.sumFinancials(orderId);
        BigDecimal totalCostExGst = money(products.cost()).add(money(charges.cost())).setScale(MONEY_SCALE, ROUNDING);

        QuoteDraftComputation computation = quoteDraftCalculator.compute(
                parsed.itemised(), parsed.lines(), parsed.finalTotalIncGst(), totalCostExGst);

        LocalDateTime now = LocalDateTime.now();
        long quoteDraftId = quoteDraftWriteRepository.upsertDraft(
                orderId, parsed.itemised(), computation.quoteTotalExGst(), computation.quoteTotalIncGst(), now);
        quoteDraftWriteRepository.deleteLines(quoteDraftId);
        quoteDraftWriteRepository.insertLines(quoteDraftId, computation.lines());

        // Update the order's cosmetic sale-price override from the quote inc-GST total (reuse D.10).
        // Q4: a zero-total quote does NOT push into the override — leave the existing override as-is.
        if (computation.quoteTotalIncGst().signum() != 0) {
            applySalePriceOverride(orderId, computation.quoteTotalIncGst(), products, charges, now);
        }

        // Re-read the persisted lines so the save response carries real quote_draft_line_id values
        // (same shape as the workspace read). The native insert is in this transaction, so the JPA
        // query sees the rows just written.
        List<QuoteDraftLine> persistedLines =
                quoteDraftLineRepository.findByQuoteDraftIdOrderBySortOrderAsc(quoteDraftId);

        // We only reach here when the quote is NOT below cost, so below_cost is false on the response.
        return new QuoteDraftDto(
                parsed.itemised(),
                computation.quoteTotalExGst(),
                computation.quoteTotalIncGst(),
                computation.gpPercent(),
                false,
                toLineDtos(persistedLines),
                now);
    }

    /**
     * Derive the GST-inclusive {@code price_adjustment_inc_gst} from the quote total and persist the
     * recomputed header scalars — identical derivation to the manual sale-price override (D.10,
     * {@link com.flooring.salesportal.order.OrderSalePriceService}). Never blocks below-cost here
     * (that is the quote-save concern, already enforced); never touches a quote table or a line.
     */
    private void applySalePriceOverride(long orderId,
                                        BigDecimal quoteTotalIncGst,
                                        LineFinancials products,
                                        LineFinancials charges,
                                        LocalDateTime now) {
        BigDecimal calculatedTotalIncGst =
                financialCalculator.compute(products, charges, null).calculatedTotalIncGst();
        BigDecimal priceAdjustmentIncGst =
                quoteTotalIncGst.subtract(calculatedTotalIncGst).setScale(MONEY_SCALE, ROUNDING);
        // A far-below/above adjustment could overflow DECIMAL(10,2): clean 400, not a persist-time 500.
        if (!fitsMoney(priceAdjustmentIncGst)) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, FIELD_FINAL_TOTAL,
                            "Resulting price adjustment exceeds the maximum supported value ("
                                    + MONEY_MAX_LABEL + ").")));
        }
        OrderFinancialSummaryDto summary = financialCalculator.compute(products, charges, priceAdjustmentIncGst);
        requirePersistableHeader(summary);
        salesOrderFinancialWriteRepository.updateHeaderFinancialsWithAdjustment(
                orderId,
                priceAdjustmentIncGst,
                summary.salePriceExGst(),
                summary.totalCost(),
                summary.gp(),
                persistableGpPercent(summary.gpPercent()),
                now);
    }

    private BigDecimal orderTotalCostExGst(long orderId) {
        LineFinancials products = orderProductLineRepository.sumFinancials(orderId);
        LineFinancials charges = orderChargeLineReadRepository.sumFinancials(orderId);
        return money(products.cost()).add(money(charges.cost())).setScale(MONEY_SCALE, ROUNDING);
    }

    /** Map persisted draft-line entities to response DTOs (used by both the workspace read and the save response). */
    private List<QuoteDraftLineDto> toLineDtos(List<QuoteDraftLine> entities) {
        List<QuoteDraftLineDto> dtos = new ArrayList<>(entities.size());
        for (QuoteDraftLine line : entities) {
            dtos.add(new QuoteDraftLineDto(
                    line.getQuoteDraftLineId(),
                    line.getLineType(),
                    line.getDescription(),
                    line.getQuantity(),
                    line.getUnitPriceExGst(),
                    line.getLineTotalExGst(),
                    line.getSortOrder()));
        }
        return dtos;
    }

    // ------------------------------------------------------------------
    // Body validation / parsing (allow-list; cost discipline)
    // ------------------------------------------------------------------

    private record ParsedDraft(boolean itemised, BigDecimal finalTotalIncGst, List<QuoteLineInput> lines) {
    }

    private ParsedDraft validateAndParse(JsonNode root) {
        List<ErrorDetail> errors = new ArrayList<>();

        if (root == null || !root.isObject()) {
            // Absent/blank body or a non-object JSON: itemised + lines are required.
            errors.add(new ErrorDetail(null, FIELD_ITEMISED, "Required."));
            errors.add(new ErrorDetail(null, FIELD_LINES, "Required."));
            throwIfErrors(errors);
        }

        rejectDisallowedFields(root, null, TOP_ALLOWED, errors);

        boolean itemised = readRequiredBoolean(root, FIELD_ITEMISED, errors);
        BigDecimal finalTotalIncGst = readOptionalNonNegativeMoney(root, FIELD_FINAL_TOTAL, errors);

        List<QuoteLineInput> lines = new ArrayList<>();
        JsonNode linesNode = root.get(FIELD_LINES);
        if (linesNode == null || linesNode.isNull()) {
            errors.add(new ErrorDetail(null, FIELD_LINES, "Required."));
        } else if (!linesNode.isArray()) {
            errors.add(new ErrorDetail(null, FIELD_LINES, "Must be an array."));
        } else {
            for (int i = 0; i < linesNode.size(); i++) {
                parseLine(linesNode.get(i), i, lines, errors);
            }
        }

        // Non-itemised strictness (Q2): a single quoted amount, no client line breakdown. A genuine
        // itemised=false REQUIRES final_total_inc_gst and an EMPTY lines array — the backend then
        // generates the one synthetic line. Only enforced when itemised is explicitly the boolean
        // false; a missing/malformed itemised is already reported by readRequiredBoolean above.
        JsonNode itemisedNode = root.get(FIELD_ITEMISED);
        boolean explicitlyNonItemised =
                itemisedNode != null && itemisedNode.isBoolean() && !itemisedNode.booleanValue();
        if (explicitlyNonItemised) {
            if (!presentNonNull(root, FIELD_FINAL_TOTAL)) {
                errors.add(new ErrorDetail(null, FIELD_FINAL_TOTAL, "Required when itemised is false."));
            }
            if (!lines.isEmpty()) {
                errors.add(new ErrorDetail(null, FIELD_LINES, "Must be empty when itemised is false."));
            }
        }

        throwIfErrors(errors);
        return new ParsedDraft(itemised, finalTotalIncGst, lines);
    }

    private void parseLine(JsonNode lineNode, int index, List<QuoteLineInput> out, List<ErrorDetail> errors) {
        String prefix = FIELD_LINES + "[" + index + "]";
        if (lineNode == null || !lineNode.isObject()) {
            errors.add(new ErrorDetail(null, prefix, "Must be an object."));
            return;
        }
        rejectDisallowedFields(lineNode, prefix + ".", LINE_ALLOWED, errors);

        String lineType = readRequiredString(lineNode, "line_type", prefix + ".line_type", errors, 16);
        if (lineType != null && !"ITEM".equals(lineType) && !"ADJUSTMENT".equals(lineType)) {
            errors.add(new ErrorDetail(null, prefix + ".line_type", "Must be ITEM or ADJUSTMENT."));
            lineType = null;
        }
        String description = readRequiredString(lineNode, "description", prefix + ".description", errors, 500);
        Integer sortOrder = readRequiredNonNegativeInt(lineNode, "sort_order", prefix + ".sort_order", errors);

        if (lineType == null || sortOrder == null) {
            return; // shape unknown / unusable; field errors already recorded
        }

        if ("ITEM".equals(lineType)) {
            BigDecimal quantity = readMoney(lineNode, "quantity", prefix + ".quantity", errors);
            BigDecimal unitPrice = readMoney(lineNode, "unit_price_ex_gst", prefix + ".unit_price_ex_gst", errors);
            boolean qtyOk = quantity != null && quantity.signum() > 0;
            boolean unitOk = unitPrice != null && unitPrice.signum() >= 0;
            if (quantity == null) {
                errors.add(new ErrorDetail(null, prefix + ".quantity", "Required and must be greater than 0 for an item line."));
            } else if (quantity.signum() <= 0) {
                errors.add(new ErrorDetail(null, prefix + ".quantity", "Must be greater than 0."));
            }
            if (unitPrice == null) {
                errors.add(new ErrorDetail(null, prefix + ".unit_price_ex_gst", "Required for an item line."));
            } else if (unitPrice.signum() < 0) {
                errors.add(new ErrorDetail(null, prefix + ".unit_price_ex_gst", "Must not be negative."));
            }
            // line_total_ex_gst is REQUIRED + numeric + DECIMAL(10,2) (OpenAPI QuoteDraftLineInput),
            // but the submitted value is IGNORED for calculation — the server recomputes qty * unit.
            boolean lineTotalOk;
            if (!presentNonNull(lineNode, "line_total_ex_gst")) {
                errors.add(new ErrorDetail(null, prefix + ".line_total_ex_gst", "Required for an item line."));
                lineTotalOk = false;
            } else {
                lineTotalOk = readMoney(lineNode, "line_total_ex_gst", prefix + ".line_total_ex_gst", errors) != null;
            }
            if (description != null && qtyOk && unitOk && lineTotalOk) {
                out.add(new QuoteLineInput("ITEM", description, quantity, unitPrice, null, sortOrder));
            }
        } else { // ADJUSTMENT
            if (presentNonNull(lineNode, "quantity")) {
                errors.add(new ErrorDetail(null, prefix + ".quantity", "Must be null for an adjustment line."));
            }
            if (presentNonNull(lineNode, "unit_price_ex_gst")) {
                errors.add(new ErrorDetail(null, prefix + ".unit_price_ex_gst", "Must be null for an adjustment line."));
            }
            BigDecimal lineTotal = readSignedMoney(lineNode, "line_total_ex_gst", prefix + ".line_total_ex_gst", errors);
            if (lineTotal == null) {
                errors.add(new ErrorDetail(null, prefix + ".line_total_ex_gst", "Required for an adjustment line."));
            }
            if (description != null && lineTotal != null
                    && !presentNonNull(lineNode, "quantity") && !presentNonNull(lineNode, "unit_price_ex_gst")) {
                out.add(new QuoteLineInput("ADJUSTMENT", description, null, null, lineTotal, sortOrder));
            }
        }
    }

    private static void rejectDisallowedFields(JsonNode node, String fieldPrefix, Set<String> allowed,
                                               List<ErrorDetail> errors) {
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            // Reject any cost / *_snapshot field (cost discipline) and any key not in the allow-list.
            if (name.toLowerCase().contains(COST_FRAGMENT) || name.endsWith(SNAPSHOT_SUFFIX) || !allowed.contains(name)) {
                String field = fieldPrefix == null ? name : fieldPrefix + name;
                errors.add(new ErrorDetail(null, field, "Not allowed."));
            }
        }
    }

    private static boolean presentNonNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull();
    }

    private static boolean readRequiredBoolean(JsonNode node, String field, List<ErrorDetail> errors) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            errors.add(new ErrorDetail(null, field, "Required."));
            return false;
        }
        if (!v.isBoolean()) {
            errors.add(new ErrorDetail(null, field, "Must be true or false."));
            return false;
        }
        return v.booleanValue();
    }

    private static String readRequiredString(JsonNode node, String key, String field, List<ErrorDetail> errors, int max) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull() || !v.isTextual()) {
            errors.add(new ErrorDetail(null, field, "Required."));
            return null;
        }
        String text = v.asText().trim();
        if (text.isEmpty()) {
            errors.add(new ErrorDetail(null, field, "Must not be blank."));
            return null;
        }
        if (text.length() > max) {
            errors.add(new ErrorDetail(null, field, "Must be at most " + max + " characters."));
            return null;
        }
        return text;
    }

    /**
     * Required sort_order: an integer in {@code [0, MAX_SORT_ORDER]}. The upper bound caps client
     * input so the auto-inserted ADJUSTMENT line's {@code max(sort_order) + 1} can never overflow
     * Integer (Codex finding). An out-of-range value is a clean 400 — never a silent wrap, never a
     * DB-CHECK 500.
     */
    private static Integer readRequiredNonNegativeInt(JsonNode node, String key, String field, List<ErrorDetail> errors) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            errors.add(new ErrorDetail(null, field, "Required."));
            return null;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt()) {
            errors.add(new ErrorDetail(null, field, "Must be an integer between 0 and " + MAX_SORT_ORDER + "."));
            return null;
        }
        int value = v.intValue();
        if (value < 0 || value > MAX_SORT_ORDER) {
            errors.add(new ErrorDetail(null, field, "Must be an integer between 0 and " + MAX_SORT_ORDER + "."));
            return null;
        }
        return value;
    }

    /** Optional non-negative money (final_total_inc_gst): absent/null → null; negative/overflow → error. */
    private static BigDecimal readOptionalNonNegativeMoney(JsonNode node, String field, List<ErrorDetail> errors) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        BigDecimal parsed = parseScaledMoney(v, field, errors);
        if (parsed == null) {
            return null;
        }
        if (parsed.signum() < 0) {
            errors.add(new ErrorDetail(null, field, "Must not be negative."));
            return null;
        }
        if (!fitsMoney(parsed)) {
            errors.add(new ErrorDetail(null, field, "Must be at most " + MONEY_MAX_LABEL + "."));
            return null;
        }
        return parsed;
    }

    /** Optional money used for ITEM quantity / unit price; absent/null → null; non-number/overflow → error. */
    private static BigDecimal readMoney(JsonNode node, String key, String field, List<ErrorDetail> errors) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        BigDecimal parsed = parseScaledMoney(v, field, errors);
        if (parsed != null && !fitsMoney(parsed)) {
            errors.add(new ErrorDetail(null, field, "Must be at most " + MONEY_MAX_LABEL + "."));
            return null;
        }
        return parsed;
    }

    /** Signed money for an ADJUSTMENT line total (may be negative). */
    private static BigDecimal readSignedMoney(JsonNode node, String key, String field, List<ErrorDetail> errors) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        BigDecimal parsed = parseScaledMoney(v, field, errors);
        if (parsed != null && !fitsMoney(parsed)) {
            errors.add(new ErrorDetail(null, field, "Must be between -" + MONEY_MAX_LABEL + " and " + MONEY_MAX_LABEL + "."));
            return null;
        }
        return parsed;
    }

    /** Parse a JSON number to a 2dp HALF_UP BigDecimal via its textual form (no float drift); guard NaN/Infinity. */
    private static BigDecimal parseScaledMoney(JsonNode v, String field, List<ErrorDetail> errors) {
        if (!v.isNumber()) {
            errors.add(new ErrorDetail(null, field, "Must be a number."));
            return null;
        }
        try {
            return new BigDecimal(v.asText()).setScale(MONEY_SCALE, ROUNDING);
        } catch (NumberFormatException | ArithmeticException ex) {
            errors.add(new ErrorDetail(null, field, "Must be a number."));
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Gates / persist guards (duplicated from OrderSalePriceService per the locked decision)
    // ------------------------------------------------------------------

    private void requireNotLaid(SalesOrder order) {
        if (STATUS_LAID.equals(order.getOrderStatus())) {
            throw new BusinessRuleException(ErrorCode.ORDER_LOCKED, ErrorCode.ORDER_LOCKED.defaultMessage());
        }
    }

    private static long parseOrderId(String raw) {
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
        throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new ErrorDetail(null, "order_id", "Must be a positive integer.")));
    }

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

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, ROUNDING);
    }

    private static boolean fitsMoney(BigDecimal value) {
        BigDecimal scaled = value.setScale(MONEY_SCALE, ROUNDING);
        return scaled.precision() - scaled.scale() <= MONEY_MAX_INTEGER_DIGITS;
    }

    private static void throwIfErrors(List<ErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }
}
