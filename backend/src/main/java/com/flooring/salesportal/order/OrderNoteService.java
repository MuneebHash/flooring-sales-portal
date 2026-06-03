package com.flooring.salesportal.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.api.PaginationMeta;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.order.dto.AddNoteResponse;
import com.flooring.salesportal.order.dto.NoteReadDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Chunk 3 Branch 4 — order notes (D.12 GET list, D.13 POST append). Notes are append-only and
 * immutable in the MVP, so there is no edit/delete path.
 *
 * <p>Both endpoints are standard-protected and scope the order to the session's
 * {@code (business_id, store_id)} (missing / cross-store / cross-business → 404 {@code ORDER_NOT_FOUND},
 * no existence leak). Gate ordering mirrors the sibling order endpoints: standard-protected guard →
 * manual {@code orderId} parse (VALIDATION_FAILED on {@code order_id}) → (GET: validate
 * {@code page}/{@code page_size}) → scoped order lookup (404 {@code ORDER_NOT_FOUND}) → (POST: parse
 * body / validate). The POST body arrives as a raw {@code String} parsed AFTER the gates (so malformed
 * JSON cannot 400 ahead of a 404), and forbidden / unknown fields are rejected via an explicit
 * {@link JsonNode} key scan (the shared {@link ObjectMapper} ignores unknown properties) — OpenAPI
 * {@code AddNoteRequest} declares {@code additionalProperties: false}.
 *
 * <p>UNLIKE product/charge lines, notes are EXEMPT from the LAID lock (conventions §16 / Chunk 3 D.12
 * L780 + D.13 L832): both GET and POST are allowed when the order is LAID, so {@code requireNotLaid}
 * is deliberately NOT called and {@code ORDER_LOCKED} is never used here. Adding a note does NOT
 * recompute or persist the {@code sales_order} financial header.
 */
@Service
public class OrderNoteService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE = 1;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    // Every "*_snapshot" column is backend-owned and must be rejected if a client sends it.
    private static final String SNAPSHOT_SUFFIX = "_snapshot";
    // The ONLY request-body key a client may send. Anything else (unknown / misspelled / backend-
    // controlled) is rejected — OpenAPI AddNoteRequest declares additionalProperties: false.
    private static final Set<String> POST_ALLOWED_FIELDS = Set.of("note_text");
    // Backend-controlled fields the client must never send (conventions §10, Chunk 3 F.10). order_note
    // has no created_by_user_id/updated_at columns, but the client is still explicitly barred from
    // sending them (and any *_snapshot key) so a misspelled/forged attribution is a clean 400.
    private static final Set<String> FORBIDDEN_BASE = Set.of(
            "order_note_id", "order_id", "business_id", "store_id", "user_id",
            "created_by_user_id", "created_at", "updated_at");

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderNoteRepository orderNoteRepository;
    private final ObjectMapper objectMapper;

    public OrderNoteService(RequestContextGuard requestContextGuard,
                            SalesOrderRepository salesOrderRepository,
                            OrderNoteRepository orderNoteRepository,
                            ObjectMapper objectMapper) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.orderNoteRepository = orderNoteRepository;
        this.objectMapper = objectMapper;
    }

    /** GET /orders/{orderId}/notes. Read-only; allowed on LAID; no financial summary. */
    @Transactional(readOnly = true)
    public ApiResponse<List<NoteReadDto>> getNotes(String slug,
                                                   String orderIdRaw,
                                                   Integer page,
                                                   Integer pageSize,
                                                   HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        Paging paging = validatePaging(page, pageSize);
        requireOrderInScope(orderId, ctx);

        long totalItems = orderNoteRepository.countByOrderId(orderId);
        int totalPages = totalPages(totalItems, paging.pageSize());

        List<NoteReadDto> rows = totalItems == 0
                ? List.of()
                : orderNoteRepository.findByOrderId(orderId, paging.pageSize(), offset(paging));

        return ApiResponse.page(rows, new PaginationMeta(paging.page(), paging.pageSize(), totalItems, totalPages));
    }

    /** POST /orders/{orderId}/notes. Append-only; allowed on LAID; does not touch order financials. */
    @Transactional
    public ApiResponse<AddNoteResponse> addNote(String slug,
                                                String orderIdRaw,
                                                String body,
                                                HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        // Scope (resource) before body validation (gate-first). FOR UPDATE mirrors the write-endpoint
        // scoping; missing / cross-store / cross-business → 404 ORDER_NOT_FOUND. No LAID gate: notes
        // may be added on a LAID order (conventions §16).
        requireOrderInScopeForUpdate(orderId, ctx);

        JsonNode node = parseBodyAfterGates(body);

        List<ErrorDetail> errors = new ArrayList<>();
        rejectDisallowedFields(node, FORBIDDEN_BASE, POST_ALLOWED_FIELDS, errors);
        String noteText = readRequiredNonBlankText(node, "note_text", errors);
        throwIfErrors(errors);

        NoteReadDto note = orderNoteRepository.insert(orderId, noteText, LocalDateTime.now());
        return ApiResponse.ok(new AddNoteResponse(note), "Note added.");
    }

    // ------------------------------------------------------------------
    // Scoping
    // ------------------------------------------------------------------

    /** Read scope (no lock — GET). 404 ORDER_NOT_FOUND when out of the session's (business, store). */
    private void requireOrderInScope(long orderId, RequestContext ctx) {
        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
    }

    /** Write scope (FOR UPDATE — POST). 404 ORDER_NOT_FOUND when out of the session's (business, store). */
    private void requireOrderInScopeForUpdate(long orderId, RequestContext ctx) {
        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
    }

    // ------------------------------------------------------------------
    // Pagination
    // ------------------------------------------------------------------

    private static Paging validatePaging(Integer pageRaw, Integer pageSizeRaw) {
        int page = pageRaw == null ? DEFAULT_PAGE : pageRaw;
        int pageSize = pageSizeRaw == null ? DEFAULT_PAGE_SIZE : pageSizeRaw;

        List<ErrorDetail> errors = new ArrayList<>();
        if (page < MIN_PAGE) {
            errors.add(new ErrorDetail(null, "page", "Must be greater than or equal to 1."));
        }
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            errors.add(new ErrorDetail(null, "page_size", "Must be between 1 and 100."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
        return new Paging(page, pageSize);
    }

    private static int totalPages(long totalItems, int pageSize) {
        return totalItems == 0
                ? 0
                : (int) ((totalItems + pageSize - 1) / pageSize);
    }

    // long to avoid int overflow when (page - 1) * pageSize exceeds Integer.MAX_VALUE.
    private static long offset(Paging paging) {
        return ((long) (paging.page() - 1)) * (long) paging.pageSize();
    }

    // ------------------------------------------------------------------
    // Gates / parsing / validation helpers
    // ------------------------------------------------------------------

    // Mirrors OrderService.parseOrderId: non-numeric / non-positive both produce a single
    // VALIDATION_FAILED on the given field rather than a 404 or a type-mismatch.
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
     * and unknown fields can be explicitly rejected — the shared ObjectMapper ignores unknown properties.
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
        // explicit backend-controlled field, ends in "_snapshot", OR is simply not in the allowed set
        // (OpenAPI additionalProperties: false — unknown/misspelled keys are 400). One "Not allowed."
        // detail per offending key.
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (forbidden.contains(name) || name.endsWith(SNAPSHOT_SUFFIX) || !allowed.contains(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
    }

    /**
     * Required text field {@code note_text}: absent or explicit JSON null → "Required."; a non-string
     * → "Must be a string."; blank after trim → "Must not be blank." (mirrors the DB CHECK
     * {@code chk_order_note_text_not_blank}). Returns the TRIMMED value so the stored text has no
     * leading/trailing whitespace.
     */
    private static String readRequiredNonBlankText(JsonNode node, String field, List<ErrorDetail> errors) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            errors.add(new ErrorDetail(null, field, "Required."));
            return null;
        }
        if (!value.isTextual()) {
            errors.add(new ErrorDetail(null, field, "Must be a string."));
            return null;
        }
        String trimmed = value.asText().trim();
        if (trimmed.isEmpty()) {
            errors.add(new ErrorDetail(null, field, "Must not be blank."));
            return null;
        }
        return trimmed;
    }

    private static void throwIfErrors(List<ErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }

    private record Paging(int page, int pageSize) {
    }
}
