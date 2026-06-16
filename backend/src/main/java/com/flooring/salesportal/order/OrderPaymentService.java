package com.flooring.salesportal.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.api.PaginationMeta;
import com.flooring.salesportal.common.email.InvoiceEmailException;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.InvoiceEmailSender;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoicePdfModelAssembler.Inputs;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceFile;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
import com.flooring.salesportal.order.PaymentTransactionRepository.PaymentRow;
import com.flooring.salesportal.order.dto.CurrentInvoiceSummaryDto;
import com.flooring.salesportal.order.dto.PaymentSummaryDto;
import com.flooring.salesportal.order.dto.PaymentTransactionReadDto;
import com.flooring.salesportal.order.dto.PaymentsListResponse;
import com.flooring.salesportal.order.dto.RecordPaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Phase 12 Chunk 4 payment endpoints (Branch D): D.6 GET /orders/{orderId}/payments (list payments +
 * current official payment summary) and D.7 POST /orders/{orderId}/payments (record a manual payment
 * against the latest official invoice balance and atomically regenerate the current invoice). Shares
 * the {@code /orders/{orderId}} base path with the sibling order sub-resource controllers; mappings
 * stay distinct by method + the {@code /payments} sub-path.
 *
 * <p>Both endpoints are standard-protected and scope the order to the session's
 * {@code (business_id, store_id)} (missing / cross-store / cross-business -> 404 {@code ORDER_NOT_FOUND},
 * no existence leak). Gate ordering mirrors the sibling endpoints: standard-protected guard -> manual
 * {@code orderId} parse (VALIDATION_FAILED on {@code order_id}) -> (GET: validate {@code page}/{@code
 * page_size}) -> scoped order lookup -> (POST: parse body / validate 400 -> invoice-first 422 ->
 * balance 422 -> persist). The POST body arrives as a raw {@code String} parsed AFTER the gates (so a
 * malformed / bad body cannot 400 ahead of a 404).
 *
 * <p>UNLIKE product/charge-line mutations, recording a payment is EXEMPT from the LAID lock
 * (conventions §16: add payment is in the LAID-allowed list), so {@code requireNotLaid} is deliberately
 * NOT called and {@code ORDER_LOCKED} is never used here.
 *
 * <p>D.7 is the key "no unsent live edits become official" rule: the regenerated invoice version
 * carries the sale snapshot fields FORWARD from the latest official invoice
 * ({@code details_of_sale_snapshot}, {@code sale_price_ex_gst}, {@code sale_price_inc_gst},
 * {@code due_date}) and only recomputes {@code total_paid} / {@code balance_due}. It never re-reads live
 * order lines / details / sale price (that is what manual Rewrite, D.2, is for). The payment insert,
 * the new {@code stored_file}, and the new {@code invoice} row are one atomic DB transaction; the PDF is
 * written to disk before the rows with a rollback-cleanup hook (mirroring {@code OrderInvoiceService} /
 * {@code OrderAttachmentService}) so a rollback never orphans the file.
 */
@Service
public class OrderPaymentService {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentService.class);

    // Locked D.7 response messages (Phase 13 contract §6): the email-success / email-failure variants
    // apply ONLY when the paid invoice was accepted (the carry-forward + best-effort re-email path);
    // a payment on an unaccepted invoice keeps the original Chunk 4 message and sends nothing.
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String PAYMENT_EMAILED_MESSAGE =
            "Payment recorded. Current invoice updated and emailed to the customer.";
    private static final String PAYMENT_EMAIL_FAILED_MESSAGE =
            "Payment recorded. Current invoice updated, but the invoice could not be emailed — use Re-send Invoice to try again.";

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE = 1;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    // Matches payment_transaction.payment_reference VARCHAR(100): reject over-length here (400) rather
    // than letting the DB raise a value-too-long error (which would surface as a generic 500).
    private static final int MAX_PAYMENT_REFERENCE_LENGTH = 100;
    private static final String PDF_MIME = "application/pdf";
    private static final String PDF_EXTENSION = "pdf";
    private static final String ADDRESS_BILLING = "BILLING";

    // OpenAPI PaymentCreateRequest declares additionalProperties: false. The ONLY request-body keys a
    // client may send.
    private static final Set<String> ALLOWED_FIELDS = Set.of("payment_method", "amount", "payment_reference");
    // Backend-controlled fields the client must never send (conventions §10). The gateway fields are
    // listed explicitly to document intent, though anything not in ALLOWED_FIELDS is already rejected.
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "payment_transaction_id", "order_id", "business_id", "store_id", "user_id",
            "gateway_transaction_id", "response_status", "response_message", "created_at");
    private static final String SNAPSHOT_SUFFIX = "_snapshot";
    // Locked payment_method enum (conventions §13 / OpenAPI PaymentMethod).
    private static final Set<String> PAYMENT_METHODS = Set.of("CASH", "CREDIT_CARD", "EFTPOS", "BANK_TRANSFER");

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderCustomerRepository orderCustomerRepository;
    private final OrderAddressRepository orderAddressRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoicePdfGenerator invoicePdfGenerator;
    private final InvoicePdfModelAssembler invoicePdfModelAssembler;
    private final FileStorageService fileStorageService;
    private final InvoiceEmailSender invoiceEmailSender;
    private final ObjectMapper objectMapper;
    // Programmatic transaction for D.7 (mirrors OrderInvoiceService): the payment-after-accepted email
    // must run AFTER the payment + new invoice version commit (and the email-success stamp in its own
    // follow-up transaction), so recordPayment cannot be @Transactional.
    private final TransactionTemplate transactionTemplate;

    public OrderPaymentService(RequestContextGuard requestContextGuard,
                               SalesOrderRepository salesOrderRepository,
                               OrderCustomerRepository orderCustomerRepository,
                               OrderAddressRepository orderAddressRepository,
                               PaymentTransactionRepository paymentTransactionRepository,
                               InvoiceRepository invoiceRepository,
                               InvoicePdfGenerator invoicePdfGenerator,
                               InvoicePdfModelAssembler invoicePdfModelAssembler,
                               FileStorageService fileStorageService,
                               InvoiceEmailSender invoiceEmailSender,
                               PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.orderCustomerRepository = orderCustomerRepository;
        this.orderAddressRepository = orderAddressRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoicePdfGenerator = invoicePdfGenerator;
        this.invoicePdfModelAssembler = invoicePdfModelAssembler;
        this.fileStorageService = fileStorageService;
        this.invoiceEmailSender = invoiceEmailSender;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // D.6 GET /orders/{orderId}/payments — list payments + current payment summary (no message field)
    // ------------------------------------------------------------------

    /**
     * List the order's payments (newest first) plus the current official {@code payment_summary}.
     * Read-only; allowed regardless of LAID. {@code total_paid} is the rounded sum of all payments;
     * {@code balance_due} is the latest official invoice's {@code balance_due} (NULL when no invoice
     * exists yet). The response nests {@code pagination} inside {@code data} with NO top-level
     * {@code message}, so {@link ApiResponse#ok(Object)} is used.
     */
    @Transactional(readOnly = true)
    public ApiResponse<PaymentsListResponse> getPayments(String slug,
                                                         String orderIdRaw,
                                                         Integer page,
                                                         Integer pageSize,
                                                         HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        Paging paging = validatePaging(page, pageSize);
        requireOrderInScope(orderId, ctx);

        BigDecimal totalPaid = paymentTransactionRepository.sumAmountByOrderId(orderId);
        // balance_due = latest official invoice's balance (NOT derived from live order state); null if none.
        BigDecimal balanceDue = invoiceRepository.findCurrentByOrderId(orderId)
                .map(InvoiceRow::balanceDue)
                .orElse(null);

        long totalItems = paymentTransactionRepository.countByOrderId(orderId);
        int totalPages = totalPages(totalItems, paging.pageSize());
        List<PaymentTransactionReadDto> rows = totalItems == 0
                ? List.of()
                : paymentTransactionRepository.findByOrderId(orderId, paging.pageSize(), offset(paging))
                        .stream().map(OrderPaymentService::toReadDto).toList();

        PaymentsListResponse data = new PaymentsListResponse(
                rows,
                new PaymentSummaryDto(totalPaid, balanceDue),
                new PaginationMeta(paging.page(), paging.pageSize(), totalItems, totalPages));
        return ApiResponse.ok(data);
    }

    // ------------------------------------------------------------------
    // D.7 POST /orders/{orderId}/payments — record a payment + regenerate the current invoice
    // ------------------------------------------------------------------

    /**
     * Record a manual payment against the latest official invoice balance and atomically regenerate the
     * current invoice (internally the next {@code version_number}). Allowed when LAID. Gate order: guard
     * -> orderId parse (400 {@code VALIDATION_FAILED}) -> scoped {@code FOR UPDATE} order lookup (404
     * {@code ORDER_NOT_FOUND}, no existence leak) -> body parse + field validation (400) -> require an
     * existing invoice (else 422 {@code INVOICE_REQUIRED}) -> amount {@literal <=} latest balance (else
     * 422 {@code PAYMENT_EXCEEDS_BALANCE}) -> persist payment + regenerate invoice. The {@code FOR UPDATE}
     * lock serialises version allocation against a concurrent payment / rewrite / accept so
     * {@code uq_invoice_order_version} cannot be raced.
     *
     * <p>Phase 13 §6: when the paid invoice was ACCEPTED, the new version carries the acceptance/
     * signature metadata forward (the customer does NOT re-sign), the regenerated PDF embeds the
     * carried signature, and after commit the updated signed PDF is emailed BEST-EFFORT — the email
     * NEVER gates the payment: there is no customer-email validation here, a missing/unusable email at
     * send time is a non-fatal failure, and D.7 never returns 502. On send success the delivery
     * timestamp is stamped on the invoice + §11.1 mirror (same timestamp, one follow-up transaction);
     * on failure both stay null and the message points at Re-send. Deliberately NOT
     * {@code @Transactional} for that reason (mirrors {@code OrderInvoiceService.acceptCurrentInvoice});
     * the persist runs in one programmatic transaction below. The response is built from the FINAL
     * post-email state. A payment on an UNACCEPTED invoice is unchanged: no acceptance fields, no
     * signature, no email attempted.
     */
    public ApiResponse<RecordPaymentResponse> recordPayment(String slug,
                                                            String orderIdRaw,
                                                            String body,
                                                            HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        PaymentPersisted persisted = transactionTemplate.execute(
                status -> validateAndPersistPayment(orderId, ctx, body));

        // Post-commit best-effort email — ONLY when acceptance was carried forward (§6). Never fatal.
        boolean emailed = persisted.carriedAcceptanceForward()
                && sendPaymentEmailQuietly(ctx, orderId, persisted);

        InvoiceRow finalRow = emailed
                ? transactionTemplate.execute(status ->
                        stampEmailSuccess(orderId, ctx, persisted.newVersion().invoiceId(), LocalDateTime.now()))
                : persisted.newVersion();

        String message = !persisted.carriedAcceptanceForward()
                ? PAYMENT_RECORDED_MESSAGE
                : (emailed ? PAYMENT_EMAILED_MESSAGE : PAYMENT_EMAIL_FAILED_MESSAGE);

        RecordPaymentResponse data = new RecordPaymentResponse(
                toReadDto(persisted.payment()),
                new PaymentSummaryDto(finalRow.totalPaid(), finalRow.balanceDue()),
                toSummaryDto(slug, orderId, finalRow));
        return ApiResponse.ok(data, message);
    }

    /**
     * The D.7 validation chain + single-transaction persist (payment row + regenerated invoice version
     * + §11.1 mirror reset). Runs inside {@code transactionTemplate.execute}, so the order row is held
     * {@code FOR UPDATE} for the whole block exactly as before the Phase 13 restructure.
     */
    private PaymentPersisted validateAndPersistPayment(long orderId, RequestContext ctx, String body) {
        // Scope (resource) before body / business rules. FOR UPDATE serialises version allocation; missing
        // / cross-store / cross-business -> 404. No LAID gate: add payment is allowed on a LAID order.
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // Validation (400) before the invoice-first / balance business rules (422), so a bad body cannot
        // pass ahead of a real validation error (mirrors the invoice-create ordering: body before 422).
        PaymentInput input = validatePaymentBody(parseBodyAfterGates(body));

        // Payment is invoice-first: the latest official invoice (max version) drives carry-forward + balance.
        InvoiceRow latest = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new BusinessRuleException(
                        ErrorCode.INVOICE_REQUIRED, ErrorCode.INVOICE_REQUIRED.defaultMessage()));

        // No overpayment: amount must not exceed the latest official balance (DB CHECK requires balance >= 0).
        if (input.amount().compareTo(latest.balanceDue()) > 0) {
            throw new BusinessRuleException(
                    ErrorCode.PAYMENT_EXCEEDS_BALANCE, ErrorCode.PAYMENT_EXCEEDS_BALANCE.defaultMessage());
        }

        // Persist the payment, then regenerate the current invoice (carry-forward snapshot + new totals).
        PaymentRow payment = paymentTransactionRepository.insert(
                orderId, input.paymentMethod(), input.amount(), input.paymentReference(), LocalDateTime.now());

        BigDecimal totalPaidAfter = paymentTransactionRepository.sumAmountByOrderId(orderId);
        BigDecimal newBalance = latest.salePriceIncGst().subtract(totalPaidAfter).setScale(MONEY_SCALE, ROUNDING);

        RegeneratedInvoice regenerated =
                regenerateInvoiceVersion(ctx, order, orderId, latest, totalPaidAfter, newBalance);

        // Deliberately NO email validation: payment never blocks on the customer email (§6). The raw
        // value is carried out for the post-commit send; null/blank is handled there as a non-fatal
        // email failure.
        OrderCustomer customer = orderCustomerRepository.findByOrderId(orderId).orElse(null);
        String recipientEmail = customer == null ? null : customer.getEmail();

        return new PaymentPersisted(
                payment,
                regenerated.row(),
                order.getOrderNumber(),
                recipientEmail,
                regenerated.pdfBytes(),
                regenerated.pdfFileName(),
                latest.acceptedAt() != null);
    }

    /**
     * Create the next {@code invoice} version as a payment-receipt snapshot: sale fields are carried
     * forward from {@code latest} (NOT re-read from live order state) and only {@code total_paid} /
     * {@code balance_due} change. Renders + stores a fresh PDF and writes the {@code stored_file} +
     * {@code invoice} rows. The PDF is written before the rows with a rollback-cleanup hook so a rollback
     * (commit-time failure, or an exception thrown after the file write) never orphans the file —
     * {@code invoice.stored_file_id} is NOT NULL + UNIQUE so the file row must exist before the invoice
     * row. The customer / billing lines on the receipt PDF are read live (presentational only — they are
     * not {@code invoice} columns and so cannot make a sale edit "official").
     *
     * <p>Phase 13 §6 carry-forward: when {@code latest} is accepted, the new version copies
     * {@code accepted_at} / {@code accepted_customer_name} / {@code accepted_signature_file_id}
     * UNCHANGED (the non-unique V10 FK exists exactly so versions share one signature stored_file — NO
     * new signature row is created) and the regenerated PDF embeds the carried-forward signature image
     * read from the existing stored_file. A signature row missing from the database or its file missing
     * on disk is a data-integrity failure -> 500 and full rollback (consistent with D.4). When
     * {@code latest} is unaccepted, all acceptance fields stay null. {@code last_emailed_at} starts
     * null on EVERY payment-created version; the email-success stamp is post-commit.
     */
    private RegeneratedInvoice regenerateInvoiceVersion(RequestContext ctx,
                                                        SalesOrder order,
                                                        long orderId,
                                                        InvoiceRow latest,
                                                        BigDecimal totalPaidAfter,
                                                        BigDecimal newBalance) {
        int versionNumber = latest.versionNumber() + 1;
        LocalDate invoiceDate = LocalDate.now();
        LocalDate dueDate = latest.dueDate();                       // carried forward
        String detailsSnapshot = latest.detailsOfSaleSnapshot();    // carried forward
        BigDecimal salePriceExGst = latest.salePriceExGst();        // carried forward
        BigDecimal salePriceIncGst = latest.salePriceIncGst();      // carried forward

        // Acceptance metadata carried forward UNCHANGED from the paid version (§6) — the customer does
        // not re-sign, and the same signature stored_file is referenced (never duplicated).
        LocalDateTime acceptedAt = latest.acceptedAt();
        String acceptedCustomerName = latest.acceptedCustomerName();
        Long acceptedSignatureFileId = latest.acceptedSignatureFileId();
        byte[] signaturePng = null;
        if (acceptedSignatureFileId != null) {
            InvoiceFile signatureFile = invoiceRepository.findStoredFileById(acceptedSignatureFileId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Signature stored_file row missing for invoice " + latest.invoiceId()));
            // Missing-on-disk -> UncheckedIOException -> 500, full rollback (payment is not recorded
            // against an invoice whose signed PDF cannot be regenerated faithfully).
            signaturePng = fileStorageService.read(signatureFile.storagePath());
        }

        OrderCustomer customer = orderCustomerRepository.findByOrderId(orderId).orElse(null);
        List<OrderAddress> addresses = orderAddressRepository.findByOrderId(orderId);

        String fileName = "invoice-" + order.getOrderNumber() + "-v" + versionNumber + "." + PDF_EXTENSION;
        // Phase 15C: the assembler enriches the carried-forward snapshot with tenant config / store /
        // salesperson / logo / terms so the payment-regenerated PDF matches the other flows' layout.
        byte[] pdfBytes = invoicePdfGenerator.render(invoicePdfModelAssembler.assemble(new Inputs(
                ctx.business(),
                order,
                versionNumber,
                invoiceDate,
                dueDate,
                customerName(customer),
                billingLine1(addresses),
                billingLine2(addresses),
                detailsSnapshot,
                salePriceIncGst,
                totalPaidAfter,
                newBalance,
                acceptedAt, acceptedCustomerName, signaturePng)));

        String storagePath = fileStorageService.store(pdfBytes, ctx.businessId(), orderId, PDF_EXTENSION);
        deleteFileOnRollback(storagePath);
        try {
            long storedFileId = invoiceRepository.insertStoredFile(fileName, storagePath, PDF_MIME, pdfBytes.length);
            InvoiceRow newVersion = invoiceRepository.insertInvoice(
                    orderId, versionNumber, invoiceDate, dueDate, detailsSnapshot,
                    salePriceExGst, salePriceIncGst, totalPaidAfter, newBalance,
                    storedFileId, ctx.userId(),
                    acceptedAt, acceptedCustomerName, acceptedSignatureFileId, null);

            // Dashboard mirror invariant (Phase 13 §11.1): whenever a new current invoice version is
            // created, sales_order.last_emailed_at is set to that version's last_emailed_at — null here
            // (a payment-created version starts unemailed; on a carried-forward acceptance the
            // post-commit email-success stamp sets both columns to the same timestamp). Same transaction
            // as the insert; the order row is held FOR UPDATE.
            invoiceRepository.updateSalesOrderLastEmailedAt(orderId, newVersion.lastEmailedAt());
            return new RegeneratedInvoice(newVersion, pdfBytes, fileName);
        } catch (RuntimeException ex) {
            fileStorageService.deleteQuietly(storagePath);
            throw ex;
        }
    }

    // ------------------------------------------------------------------
    // Phase 13 §6 — post-commit best-effort email + delivery stamp (mirrors OrderInvoiceService)
    // ------------------------------------------------------------------

    /**
     * Attempt the payment-after-accepted email; report success/failure WITHOUT throwing. The payment
     * NEVER gates on the customer email: a missing/blank recipient at send time is treated exactly like
     * a provider failure (non-fatal — payment + carried acceptance persist, both {@code last_emailed_at}
     * columns stay null, the message points at Re-send). Only the transport failure
     * ({@link InvoiceEmailException}) is swallowed — anything else is a real bug and propagates as 500.
     * D.7 never returns 502 for an email failure (§6).
     */
    private boolean sendPaymentEmailQuietly(RequestContext ctx, long orderId, PaymentPersisted persisted) {
        String recipient = persisted.recipientEmail();
        if (recipient == null || recipient.isBlank()) {
            log.warn("Payment-after-accepted email skipped for order {} invoice v{}: no usable customer email",
                    orderId, persisted.newVersion().versionNumber());
            return false;
        }
        try {
            invoiceEmailSender.send(new InvoiceEmailRequest(
                    recipient.trim(),
                    emailSubject(ctx.business().getName()),
                    emailBody(persisted.orderNumber(), ctx.business().getName()),
                    persisted.pdfBytes(),
                    persisted.pdfFileName(),
                    orderId,
                    persisted.newVersion().versionNumber()));
            return true;
        } catch (InvoiceEmailException ex) {
            log.warn("Payment-after-accepted email failed for order {} invoice v{} (non-fatal; payment persisted)",
                    orderId, persisted.newVersion().versionNumber(), ex);
            return false;
        }
    }

    /**
     * Email-success stamp (one short transaction; mirrors {@code OrderInvoiceService.stampEmailSuccess}):
     * the scoped order row is taken {@code FOR UPDATE} FIRST so the current-invoice re-read cannot race
     * a concurrent rewrite/payment/accept, then {@code invoice.last_emailed_at} is set in place and the
     * §11.1 mirror is set to the CURRENT invoice's value — both columns carry the SAME timestamp.
     */
    private InvoiceRow stampEmailSuccess(long orderId, RequestContext ctx, long invoiceId, LocalDateTime sentAt) {
        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new IllegalStateException(
                        "Order disappeared while stamping last_emailed_at: " + orderId));
        invoiceRepository.updateInvoiceLastEmailedAt(invoiceId, sentAt);
        InvoiceRow current = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Current invoice disappeared while stamping last_emailed_at for order " + orderId));
        invoiceRepository.updateSalesOrderLastEmailedAt(orderId, current.lastEmailedAt());
        return current;
    }

    // Simple MVP wording (contract §9), duplicated per service by the locked Branch D convention.
    private static String emailSubject(String businessName) {
        return "Your invoice from " + businessName;
    }

    private static String emailBody(String orderNumber, String businessName) {
        return "Hi,\n\nPlease find your invoice " + orderNumber + " from " + businessName
                + " attached.\n\nThank you.";
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

    // ------------------------------------------------------------------
    // Pagination (mirrors OrderNoteService)
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
        return totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
    }

    // long to avoid int overflow when (page - 1) * pageSize exceeds Integer.MAX_VALUE.
    private static long offset(Paging paging) {
        return ((long) (paging.page() - 1)) * (long) paging.pageSize();
    }

    // ------------------------------------------------------------------
    // Body parse / field validation (POST)
    // ------------------------------------------------------------------

    /**
     * Parse the raw body to a {@link JsonNode} AFTER the gates. Null/blank -> null (all fields missing).
     * Unparseable -> {@link MalformedJsonException} (400 MALFORMED_JSON). Reading via JsonNode is required
     * so forbidden / unknown keys can be rejected explicitly (the shared ObjectMapper ignores unknowns).
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

    private PaymentInput validatePaymentBody(JsonNode node) {
        // A present-but-non-object body (array / scalar) is a clean validation error on `body`.
        if (node != null && !node.isObject()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, "body", "Request body must be a JSON object.")));
        }
        List<ErrorDetail> errors = new ArrayList<>();
        rejectDisallowedFields(node, errors);
        String method = readPaymentMethod(node, errors);
        BigDecimal amount = readAmount(node, errors);
        String reference = readPaymentReference(node, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
        return new PaymentInput(method, amount, reference);
    }

    private static void rejectDisallowedFields(JsonNode node, List<ErrorDetail> errors) {
        if (node == null || !node.isObject()) {
            return;
        }
        // Reject any key that is backend-controlled, ends in "_snapshot", or is simply not allowed
        // (OpenAPI additionalProperties: false). The forbidden gateway fields fall under this too.
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (FORBIDDEN_FIELDS.contains(name) || name.endsWith(SNAPSHOT_SUFFIX) || !ALLOWED_FIELDS.contains(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
    }

    private static String readPaymentMethod(JsonNode node, List<ErrorDetail> errors) {
        JsonNode value = node == null ? null : node.get("payment_method");
        if (value == null || value.isNull()) {
            errors.add(new ErrorDetail(null, "payment_method", "Required."));
            return null;
        }
        if (!value.isTextual()) {
            errors.add(new ErrorDetail(null, "payment_method", "Must be a string."));
            return null;
        }
        String method = value.asText();
        if (!PAYMENT_METHODS.contains(method)) {
            errors.add(new ErrorDetail(null, "payment_method",
                    "Must be one of CASH, CREDIT_CARD, EFTPOS, BANK_TRANSFER."));
            return null;
        }
        return method;
    }

    /**
     * Required money amount &gt; 0. Absent / null -> "Required."; non-number -> "Must be a number.";
     * rounded to money scale 2 (HALF_UP) and then required to be strictly positive (so a sub-cent value
     * that rounds to {@code 0.00} is rejected rather than tripping the {@code amount > 0} DB CHECK).
     */
    private static BigDecimal readAmount(JsonNode node, List<ErrorDetail> errors) {
        JsonNode value = node == null ? null : node.get("amount");
        if (value == null || value.isNull()) {
            errors.add(new ErrorDetail(null, "amount", "Required."));
            return null;
        }
        if (!value.isNumber()) {
            errors.add(new ErrorDetail(null, "amount", "Must be a number."));
            return null;
        }
        BigDecimal amount = value.decimalValue().setScale(MONEY_SCALE, ROUNDING);
        if (amount.signum() <= 0) {
            errors.add(new ErrorDetail(null, "amount", "Must be greater than 0."));
            return null;
        }
        return amount;
    }

    /**
     * Optional {@code payment_reference}: absent or explicit JSON null -> null (persist SQL NULL);
     * a non-string -> "Must be a string."; blank after trim -> "Must not be blank." (mirrors the DB CHECK
     * {@code chk_payment_reference_not_blank}); longer than 100 chars after trim -> "Must be at most 100
     * characters." (matches the {@code VARCHAR(100)} column, so an over-length value is a clean 400 rather
     * than a DB value-too-long 500). Returns the TRIMMED value when present.
     */
    private static String readPaymentReference(JsonNode node, List<ErrorDetail> errors) {
        JsonNode value = node == null ? null : node.get("payment_reference");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            errors.add(new ErrorDetail(null, "payment_reference", "Must be a string."));
            return null;
        }
        String trimmed = value.asText().trim();
        if (trimmed.isEmpty()) {
            errors.add(new ErrorDetail(null, "payment_reference", "Must not be blank."));
            return null;
        }
        if (trimmed.length() > MAX_PAYMENT_REFERENCE_LENGTH) {
            errors.add(new ErrorDetail(null, "payment_reference", "Must be at most 100 characters."));
            return null;
        }
        return trimmed;
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
    // Rollback cleanup (mirrors OrderInvoiceService / OrderAttachmentService)
    // ------------------------------------------------------------------

    /**
     * Delete a just-written PDF file IFF the surrounding transaction does NOT commit. The file is written
     * before the {@code stored_file} / {@code invoice} rows, so a rollback would otherwise orphan it on
     * disk. {@code afterCompletion} fires on commit and rollback; the file is removed only when
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
    }

    // ------------------------------------------------------------------
    // DTO / PDF model helpers
    // ------------------------------------------------------------------

    private static PaymentTransactionReadDto toReadDto(PaymentRow row) {
        return new PaymentTransactionReadDto(
                row.paymentTransactionId(),
                row.paymentMethod(),
                row.amount(),
                row.paymentReference(),
                row.createdAt());
    }

    private static CurrentInvoiceSummaryDto toSummaryDto(String slug, long orderId, InvoiceRow row) {
        // accepted_signature_present / the download path are DERIVED from the internal
        // accepted_signature_file_id, which itself is never serialized (mirrors how stored_file_id is
        // hidden behind pdf_download_path). A payment on an accepted invoice carries the acceptance
        // forward (§6), so these resolve to the carried values; on an unaccepted invoice they stay
        // null/false.
        boolean signaturePresent = row.acceptedSignatureFileId() != null;
        return new CurrentInvoiceSummaryDto(
                row.invoiceId(),
                row.versionNumber(),
                row.invoiceDate(),
                row.dueDate(),
                row.salePriceIncGst(),
                row.totalPaid(),
                row.balanceDue(),
                row.createdByUserId(),
                row.createdAt(),
                "/api/v1/" + slug + "/orders/" + orderId + "/invoices/current/file",
                row.acceptedAt(),
                row.acceptedCustomerName(),
                signaturePresent,
                signaturePresent
                        ? "/api/v1/" + slug + "/orders/" + orderId + "/invoices/current/signature"
                        : null,
                row.lastEmailedAt());
    }

    // Presentational PDF helpers (mirror OrderInvoiceService — kept local so Branch D stays self-contained
    // and does not refactor the merged Branch A/C invoice code). Null-safe for a deleted customer.
    private static String customerName(OrderCustomer customer) {
        if (customer == null) {
            return "";
        }
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

    private record Paging(int page, int pageSize) {
    }

    private record PaymentInput(String paymentMethod, BigDecimal amount, String paymentReference) {
    }

    /**
     * Committed D.7 result + what the post-commit §6 email needs (never serialized).
     * {@code recipientEmail} is the RAW customer email (possibly null/blank — payment never gates on
     * it); {@code carriedAcceptanceForward} is true iff the PAID invoice was accepted.
     */
    private record PaymentPersisted(PaymentRow payment, InvoiceRow newVersion, String orderNumber,
                                    String recipientEmail, byte[] pdfBytes, String pdfFileName,
                                    boolean carriedAcceptanceForward) {
    }

    /** A freshly persisted payment-created invoice version + its rendered PDF (never serialized). */
    private record RegeneratedInvoice(InvoiceRow row, byte[] pdfBytes, String pdfFileName) {
    }
}
