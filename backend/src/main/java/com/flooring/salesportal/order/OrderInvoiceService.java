package com.flooring.salesportal.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ConflictException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoicePdfGenerator.InvoicePdfModel;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceFile;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
import com.flooring.salesportal.order.dto.InvoiceDetailDto;
import com.flooring.salesportal.order.dto.InvoiceResponse;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import com.flooring.salesportal.order.financial.OrderFinancialCalculator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Phase 12 Chunk 4 invoice endpoints: Branch A — create the current invoice (D.1 POST
 * /orders/{orderId}/invoices); Branch B — read the current invoice (D.3 GET
 * /orders/{orderId}/invoices/current) and stream its PDF (D.4 GET
 * /orders/{orderId}/invoices/current/file). Rewrite (D.2) and payments (D.6/D.7) are later branches.
 *
 * <p>Gate ordering mirrors the sibling order endpoints: standard-protected guard -> manual
 * {@code orderId} parse (VALIDATION_FAILED on {@code order_id}) -> scoped {@code FOR UPDATE} order
 * lookup (missing / cross-store / cross-business -> 404 {@code ORDER_NOT_FOUND}, no existence leak)
 * -> empty-body check ({@code {}} only; any field -> 400 {@code VALIDATION_FAILED}; unparseable ->
 * 400 {@code MALFORMED_JSON}) -> already-exists (409 {@code INVOICE_ALREADY_EXISTS}) -> the 9
 * preconditions (422 {@code INVOICE_PRECONDITIONS_NOT_MET}) -> snapshot + PDF + insert. The body is
 * taken as a raw {@code String} and parsed AFTER the gates so a malformed / extra-field body cannot
 * 400 ahead of a 404.
 *
 * <p>LAID: D.1 is ALLOWED when the order is LAID provided no invoice exists yet (conventions §16
 * blocks only manual Rewrite). There is therefore no LAID gate here — an existing invoice already
 * yields 409 regardless of status. The {@code FOR UPDATE} lock serialises version allocation (this
 * branch always inserts {@code version_number = 1}). The PDF is written before the DB rows with a
 * rollback-cleanup hook (mirroring {@code OrderAttachmentService}) so a rollback never orphans the
 * file. Snapshot values come from the LIVE financial summary at create time (frozen onto the
 * {@code invoice} row); {@code sale_price_inc_gst} is taken directly from
 * {@code final_sale_price_inc_gst} (not re-derived from ex-GST) so each money column is frozen
 * independently and the {@code inc >= ex} CHECK holds.
 */
@Service
public class OrderInvoiceService {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final int FIRST_VERSION = 1;
    private static final int DUE_DATE_OFFSET_DAYS = 2;
    private static final String PDF_MIME = "application/pdf";
    private static final String PDF_EXTENSION = "pdf";
    private static final String ADDRESS_BILLING = "BILLING";
    private static final String STATUS_LAID = "LAID";

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderCustomerRepository orderCustomerRepository;
    private final OrderAddressRepository orderAddressRepository;
    private final OrderProductLineRepository orderProductLineRepository;
    private final OrderChargeLineReadRepository orderChargeLineReadRepository;
    private final OrderFinancialCalculator financialCalculator;
    private final InvoicePreconditionValidator preconditionValidator;
    private final CustomerEmailValidator customerEmailValidator;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoicePdfGenerator invoicePdfGenerator;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public OrderInvoiceService(RequestContextGuard requestContextGuard,
                               SalesOrderRepository salesOrderRepository,
                               OrderCustomerRepository orderCustomerRepository,
                               OrderAddressRepository orderAddressRepository,
                               OrderProductLineRepository orderProductLineRepository,
                               OrderChargeLineReadRepository orderChargeLineReadRepository,
                               OrderFinancialCalculator financialCalculator,
                               InvoicePreconditionValidator preconditionValidator,
                               CustomerEmailValidator customerEmailValidator,
                               PaymentTransactionRepository paymentTransactionRepository,
                               InvoiceRepository invoiceRepository,
                               InvoicePdfGenerator invoicePdfGenerator,
                               FileStorageService fileStorageService,
                               ObjectMapper objectMapper) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.orderCustomerRepository = orderCustomerRepository;
        this.orderAddressRepository = orderAddressRepository;
        this.orderProductLineRepository = orderProductLineRepository;
        this.orderChargeLineReadRepository = orderChargeLineReadRepository;
        this.financialCalculator = financialCalculator;
        this.preconditionValidator = preconditionValidator;
        this.customerEmailValidator = customerEmailValidator;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoicePdfGenerator = invoicePdfGenerator;
        this.fileStorageService = fileStorageService;
        this.objectMapper = objectMapper;
    }

    /** POST /orders/{orderId}/invoices — create the first (version 1) invoice. */
    @Transactional
    public ApiResponse<InvoiceResponse> createInvoice(String slug,
                                                      String orderIdRaw,
                                                      String body,
                                                      HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        // Scope (resource) before body / business rules. FOR UPDATE serialises version allocation and
        // the exists-check against a concurrent create; missing / cross-store / cross-business -> 404.
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // Body must be empty ({} / blank). Any field (including due_date) -> 400 VALIDATION_FAILED;
        // unparseable -> 400 MALFORMED_JSON. No LAID gate: D.1 is allowed on a LAID order with no
        // invoice; an existing invoice is handled by the 409 below regardless of status.
        rejectNonEmptyBody(body);

        if (invoiceRepository.existsByOrderId(orderId)) {
            throw new ConflictException(ErrorCode.INVOICE_ALREADY_EXISTS,
                    ErrorCode.INVOICE_ALREADY_EXISTS.defaultMessage());
        }

        // Snapshot the live order state onto version 1 (shared with D.2 Rewrite). Payments are
        // invoice-first (D.7 requires an existing invoice), so a brand-new order's total_paid is 0 and
        // balance_due = inc - 0 = inc > 0 (precondition 9).
        return snapshotAndPersistInvoice(slug, orderId, order, ctx, FIRST_VERSION, "Invoice created.");
    }

    // ------------------------------------------------------------------
    // D.2 POST /orders/{orderId}/invoices/rewrite — regenerate the current invoice (Branch C)
    // ------------------------------------------------------------------

    /**
     * Rewrite (regenerate) the current invoice from the CURRENT live order state (Chunk 4 D.2). The new
     * row is the next {@code version_number} (= current max + 1); older rows are never modified and stay
     * internal (MVP exposes only the current invoice). Manual Rewrite is what makes new live edits
     * official, so the snapshot is taken from live state (NOT carried forward from the previous invoice).
     * After commit, D.3 / D.4 resolve this new row as the current invoice.
     *
     * <p>Gate order follows the approved mutation ordering in {@code OrderService}: guard -> orderId
     * parse (400 {@code VALIDATION_FAILED} on {@code order_id}) -> scoped {@code FOR UPDATE} order lookup
     * (missing / cross-store / cross-business -> 404 {@code ORDER_NOT_FOUND}, no existence leak) -> LAID
     * gate (422 {@code ORDER_LOCKED}; conventions §16 blocks manual Rewrite) -> empty-body check
     * ({@code {}} only; any field -> 400 {@code VALIDATION_FAILED}; unparseable -> 400
     * {@code MALFORMED_JSON}) -> require an existing invoice (else 422 {@code INVOICE_REQUIRED}) -> the 9
     * preconditions (422 {@code INVOICE_PRECONDITIONS_NOT_MET}) -> snapshot + PDF + insert. The LAID gate
     * runs BEFORE the body parse (mirroring {@code OrderService}) so a locked in-scope order is
     * {@code ORDER_LOCKED} regardless of body content. The {@code FOR UPDATE} order lock serialises
     * version allocation against a concurrent rewrite, so {@code uq_invoice_order_version} cannot be raced.
     */
    @Transactional
    public ApiResponse<InvoiceResponse> rewriteInvoice(String slug,
                                                       String orderIdRaw,
                                                       String body,
                                                       HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        // Scope (resource) before LAID / body / business rules. FOR UPDATE serialises version allocation
        // against a concurrent rewrite; missing / cross-store / cross-business -> 404.
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // Manual Rewrite is blocked on a LAID order (conventions §16 / Chunk 4 D.2). Gate-first (before
        // the body parse), mirroring OrderService: a LAID in-scope order is ORDER_LOCKED regardless of body.
        requireNotLaid(order);

        // Body must be empty ({} / blank). Any field (including due_date) -> 400 VALIDATION_FAILED;
        // unparseable -> 400 MALFORMED_JSON.
        rejectNonEmptyBody(body);

        // Rewrite requires an existing invoice; the current (max version) row drives the next version.
        InvoiceRow current = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new BusinessRuleException(
                        ErrorCode.INVOICE_REQUIRED, ErrorCode.INVOICE_REQUIRED.defaultMessage()));

        return snapshotAndPersistInvoice(
                slug, orderId, order, ctx, current.versionNumber() + 1, "Invoice rewritten.");
    }

    // ------------------------------------------------------------------
    // Shared snapshot + PDF + persist (D.1 Create / D.2 Rewrite)
    // ------------------------------------------------------------------

    /**
     * Snapshot the CURRENT live order state onto a new {@code invoice} row (version {@code versionNumber}),
     * render + store its PDF, and return the E.2 detail wrapped with {@code successMessage}. Shared by D.1
     * Create (version 1) and D.2 Rewrite (max + 1): both make the current live order state official, so
     * the snapshot is identical apart from the version number and the response message. Caller has already
     * passed the gates (scope, and — for rewrite — LAID + invoice-exists) and holds the order
     * {@code FOR UPDATE} inside its transaction.
     *
     * <p>Runs the 9 preconditions against the live summary (422 {@code INVOICE_PRECONDITIONS_NOT_MET}).
     * {@code sale_price_inc_gst} is taken directly from {@code final_sale_price_inc_gst} (not re-derived
     * from ex-GST) so each money column is frozen independently and the {@code inc >= ex} CHECK holds.
     * The PDF is written to disk BEFORE the {@code stored_file} / {@code invoice} rows with a
     * rollback-cleanup hook (mirrors {@code OrderAttachmentService}): a disk-write failure throws before
     * any DB row exists; a DB failure removes the just-written file (rollback hook + in-method catch), so
     * a rollback never orphans the file. {@code invoice.stored_file_id} is NOT NULL + UNIQUE, so the file
     * row must exist before the invoice row.
     */
    private ApiResponse<InvoiceResponse> snapshotAndPersistInvoice(String slug,
                                                                   long orderId,
                                                                   SalesOrder order,
                                                                   RequestContext ctx,
                                                                   int versionNumber,
                                                                   String successMessage) {
        // Phase 13 email gate (contract §12): a valid customer email is required BEFORE the 9
        // preconditions are evaluated; when it fails, the 9-check evaluation is skipped entirely. A
        // missing customer row is therefore 422 CUSTOMER_EMAIL_REQUIRED, never
        // INVOICE_PRECONDITIONS_NOT_MET. Create / Rewrite still send NO email — the gate only
        // guarantees the later Accept auto-email can always be addressed.
        OrderCustomer customer = orderCustomerRepository.findByOrderId(orderId).orElse(null);
        customerEmailValidator.requireValidCustomerEmail(customer);

        // Live financial summary (never persisted as a row) — products + charges + price adjustment.
        OrderFinancialSummaryDto summary = financialCalculator.compute(
                orderProductLineRepository.sumFinancials(orderId),
                orderChargeLineReadRepository.sumFinancials(orderId),
                order.getPriceAdjustmentIncGst());

        List<OrderAddress> addresses = orderAddressRepository.findByOrderId(orderId);

        List<ErrorDetail> failures = preconditionValidator.collectFailures(order, customer, addresses, summary);
        if (!failures.isEmpty()) {
            throw new BusinessRuleException(
                    ErrorCode.INVOICE_PRECONDITIONS_NOT_MET,
                    ErrorCode.INVOICE_PRECONDITIONS_NOT_MET.defaultMessage(),
                    failures);
        }

        // Snapshot from the live summary, frozen onto the invoice row.
        LocalDate invoiceDate = LocalDate.now();
        LocalDate dueDate = order.getProposedLayDate().minusDays(DUE_DATE_OFFSET_DAYS);
        String detailsSnapshot = order.getDetailsOfSale();
        BigDecimal salePriceExGst = summary.salePriceExGst();
        BigDecimal salePriceIncGst = summary.finalSalePriceIncGst();
        BigDecimal totalPaid = paymentTransactionRepository.sumAmountByOrderId(orderId);
        BigDecimal balanceDue = salePriceIncGst.subtract(totalPaid).setScale(MONEY_SCALE, ROUNDING);

        // Defensive backstop against an over-paid order (only reachable via inconsistent seeded/manual
        // data): fail fast with a clear message rather than tripping the DB CHECK
        // chk_invoice_balance_due_gte_zero with a cryptic constraint violation. Payments are invoice-first
        // and capped at the latest balance on D.7, so total_paid never legitimately exceeds the recomputed
        // inc-GST amount here. (Overpayment is handled as PAYMENT_EXCEEDS_BALANCE on the D.7 branch.)
        if (balanceDue.signum() < 0) {
            throw new IllegalStateException(
                    "Computed balance_due is negative (payments exceed the invoice amount) for order " + orderId);
        }

        // Render the PDF and write the file FIRST, then register rollback cleanup, then insert the
        // stored_file + invoice rows (mirrors OrderAttachmentService).
        String fileName = "invoice-" + order.getOrderNumber() + "-v" + versionNumber + "." + PDF_EXTENSION;
        byte[] pdfBytes = invoicePdfGenerator.render(new InvoicePdfModel(
                ctx.business().getName(),
                order.getOrderNumber(),
                versionNumber,
                invoiceDate,
                dueDate,
                customerName(customer),
                billingLine1(addresses),
                billingLine2(addresses),
                detailsSnapshot,
                salePriceIncGst,
                totalPaid,
                balanceDue));

        String storagePath = fileStorageService.store(pdfBytes, ctx.businessId(), orderId, PDF_EXTENSION);
        deleteFileOnRollback(storagePath);
        try {
            long storedFileId = invoiceRepository.insertStoredFile(fileName, storagePath, PDF_MIME, pdfBytes.length);
            InvoiceRow row = invoiceRepository.insertInvoice(
                    orderId, versionNumber, invoiceDate, dueDate, detailsSnapshot,
                    salePriceExGst, salePriceIncGst, totalPaid, balanceDue,
                    storedFileId, ctx.userId());

            // Dashboard mirror invariant (Phase 13 §11.1): whenever a new current invoice version is
            // created, sales_order.last_emailed_at is set to that version's last_emailed_at — null here
            // (Create / Rewrite never email), so the dashboard cannot show a stale emailed time from a
            // previous version. Same transaction as the insert; the order row is held FOR UPDATE.
            invoiceRepository.updateSalesOrderLastEmailedAt(orderId, row.lastEmailedAt());

            InvoiceDetailDto dto = toDto(slug, orderId, row);
            return ApiResponse.ok(new InvoiceResponse(dto), successMessage);
        } catch (RuntimeException ex) {
            fileStorageService.deleteQuietly(storagePath);
            throw ex;
        }
    }

    // ------------------------------------------------------------------
    // D.3 GET /orders/{orderId}/invoices/current — read the current invoice (no message field)
    // ------------------------------------------------------------------

    /**
     * Return the current (highest {@code version_number}) invoice for the scoped order. Read-only;
     * allowed regardless of LAID. Gate order: guard -> orderId parse (400) -> scoped order lookup
     * (404 {@code ORDER_NOT_FOUND}, no existence leak) -> current invoice lookup (404
     * {@code INVOICE_NOT_FOUND} when none). The response is the E.2 invoice_detail with NO top-level
     * {@code message} (D.3), so {@link ApiResponse#ok(Object)} is used (message stays null/omitted).
     */
    @Transactional(readOnly = true)
    public ApiResponse<InvoiceResponse> getCurrentInvoice(String slug,
                                                          String orderIdRaw,
                                                          HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        requireOrderInScope(orderId, ctx);

        InvoiceRow row = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.INVOICE_NOT_FOUND,
                        ErrorCode.INVOICE_NOT_FOUND.defaultMessage()));

        return ApiResponse.ok(new InvoiceResponse(toDto(slug, orderId, row)));
    }

    // ------------------------------------------------------------------
    // D.4 GET /orders/{orderId}/invoices/current/file — stream the current invoice PDF
    // ------------------------------------------------------------------

    /**
     * Resolve the current invoice's linked {@code stored_file} and read its bytes for streaming (D.4).
     * Read-only; allowed regardless of LAID. Same gates as D.3. No invoice -> 404
     * {@code INVOICE_NOT_FOUND}; a file row whose bytes are missing/unreadable on disk ->
     * {@link java.io.UncheckedIOException} from {@link FileStorageService#read} -> generic 500 via the
     * standard JSON error wrapper (the controller never gets a body to stream, so the error is JSON).
     * {@code storage_path} is used only to read the bytes and is never returned.
     */
    @Transactional(readOnly = true)
    public InvoiceFileDownload downloadCurrentInvoiceFile(String slug,
                                                          String orderIdRaw,
                                                          HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        requireOrderInScope(orderId, ctx);

        InvoiceFile file = invoiceRepository.findCurrentFileByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.INVOICE_NOT_FOUND,
                        ErrorCode.INVOICE_NOT_FOUND.defaultMessage()));

        // Missing-on-disk -> UncheckedIOException -> generic 500 with the standard JSON wrapper (D.4).
        byte[] bytes = fileStorageService.read(file.storagePath());
        return new InvoiceFileDownload(bytes, file.mimeType(), file.fileName(), file.fileSize());
    }

    // ------------------------------------------------------------------
    // Scoping (read)
    // ------------------------------------------------------------------

    /** Read scope (no lock). 404 ORDER_NOT_FOUND when out of the session's (business, store). */
    private void requireOrderInScope(long orderId, RequestContext ctx) {
        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
    }

    /**
     * Manual Rewrite Invoice (D.2) is blocked on a LAID order (conventions §16). 422 {@code ORDER_LOCKED}.
     * Mirrors the {@code requireNotLaid} guard in the sibling order-mutation services. (Create/D.1 has no
     * such gate — it is allowed on a LAID order when no invoice exists yet.)
     */
    private void requireNotLaid(SalesOrder order) {
        if (STATUS_LAID.equals(order.getOrderStatus())) {
            throw new BusinessRuleException(ErrorCode.ORDER_LOCKED, ErrorCode.ORDER_LOCKED.defaultMessage());
        }
    }

    // ------------------------------------------------------------------
    // Body / id validation
    // ------------------------------------------------------------------

    /**
     * D.1 takes no request body. A null/blank body or {@code {}} passes; any field (including
     * {@code due_date}) -> 400 {@code VALIDATION_FAILED} with the offending key; a non-object JSON
     * (array/scalar) -> 400 on {@code body}; unparseable JSON -> 400 {@code MALFORMED_JSON}. Parsed
     * after the gates so it cannot 400 ahead of the 404 scope check.
     */
    private void rejectNonEmptyBody(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new MalformedJsonException();
        }
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw new ValidationException(
                    ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, "body", "Request body must be empty.")));
        }
        List<ErrorDetail> errors = new ArrayList<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            errors.add(new ErrorDetail(null, names.next(), "Not allowed."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
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

    // ------------------------------------------------------------------
    // Rollback cleanup (mirrors OrderAttachmentService)
    // ------------------------------------------------------------------

    /**
     * Delete a just-written PDF file IFF the surrounding transaction does NOT commit. The file is
     * written before the {@code stored_file} / {@code invoice} rows, so a rollback (commit-time
     * failure, or rollback of an outer transaction) would otherwise orphan it on disk.
     * {@code afterCompletion} fires on both commit and rollback; the file is removed only when
     * {@code status != STATUS_COMMITTED}. {@link FileStorageService#deleteQuietly} is best-effort and
     * idempotent, so it is safe even when the in-method catch already removed the file.
     */
    private void deleteFileOnRollback(String storagePath) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        fileStorageService.deleteQuietly(storagePath);
                    }
                }
            });
        }
        // else: no active transaction synchronization → nothing deferred to clean up.
    }

    // ------------------------------------------------------------------
    // DTO / PDF model helpers
    // ------------------------------------------------------------------

    private static InvoiceDetailDto toDto(String slug, long orderId, InvoiceRow row) {
        // accepted_signature_present / the download path are DERIVED from the internal
        // accepted_signature_file_id, which itself is never serialized (mirrors how stored_file_id is
        // hidden behind pdf_download_path). The path is built only when a signature is actually stored.
        boolean signaturePresent = row.acceptedSignatureFileId() != null;
        return new InvoiceDetailDto(
                row.invoiceId(),
                row.orderId(),
                row.versionNumber(),
                row.invoiceDate(),
                row.dueDate(),
                row.detailsOfSaleSnapshot(),
                row.salePriceExGst(),
                row.salePriceIncGst(),
                row.totalPaid(),
                row.balanceDue(),
                row.createdByUserId(),
                row.createdAt(),
                buildPdfDownloadPath(slug, orderId),
                row.acceptedAt(),
                row.acceptedCustomerName(),
                signaturePresent,
                signaturePresent ? buildSignatureDownloadPath(slug, orderId) : null,
                row.lastEmailedAt());
    }

    // Stable current-invoice file path (always resolves to the current version at request time).
    private static String buildPdfDownloadPath(String slug, long orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/invoices/current/file";
    }

    // Stable current-invoice signature path (Phase 13 D.10 — the endpoint itself is a later branch).
    private static String buildSignatureDownloadPath(String slug, long orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/invoices/current/signature";
    }

    private static String customerName(OrderCustomer customer) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, customer.getFirstName());
        appendIfPresent(sb, customer.getMiddleName());
        appendIfPresent(sb, customer.getLastName());
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value.trim());
        }
    }

    private static String billingLine1(List<OrderAddress> addresses) {
        OrderAddress billing = billing(addresses);
        if (billing == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (billing.getUnitNumber() != null && !billing.getUnitNumber().isBlank()) {
            sb.append(billing.getUnitNumber().trim()).append('/');
        }
        sb.append(billing.getStreetNumber()).append(' ').append(billing.getStreet());
        return sb.toString();
    }

    private static String billingLine2(List<OrderAddress> addresses) {
        OrderAddress billing = billing(addresses);
        if (billing == null) {
            return "";
        }
        return billing.getSuburb() + " " + billing.getStateCode() + " " + billing.getPostcode();
    }

    private static OrderAddress billing(List<OrderAddress> addresses) {
        return addresses.stream()
                .filter(a -> ADDRESS_BILLING.equals(a.getAddressType()))
                .findFirst()
                .orElse(null);
    }

    /** Carrier for the D.4 binary response — the controller turns this into a raw {@code ResponseEntity}. */
    public record InvoiceFileDownload(byte[] bytes, String mimeType, String fileName, long fileSize) {
    }
}
