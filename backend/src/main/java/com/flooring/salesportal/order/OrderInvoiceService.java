package com.flooring.salesportal.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.email.InvoiceEmailException;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.InvoiceEmailSender;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ConflictException;
import com.flooring.salesportal.common.error.EmailSendException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.FileUploadException;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoicePdfModelAssembler.Inputs;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceFile;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
import com.flooring.salesportal.order.dto.InvoiceDetailDto;
import com.flooring.salesportal.order.dto.InvoiceResponse;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import com.flooring.salesportal.order.financial.OrderFinancialCalculator;
import com.flooring.salesportal.order.quote.QuoteVersionRepository;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.AcceptedQuoteVersionRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionRow;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Phase 12 Chunk 4 invoice endpoints — D.1 Create, D.2 Rewrite, D.3 read current, D.4 stream the
 * current PDF — plus the Phase 13 acceptance flows: D.8 Accept (signature multipart + signed PDF +
 * post-commit auto-email), D.9 Resend (re-email the accepted PDF), and D.10 signature download.
 * Payments (D.6/D.7) live in {@code OrderPaymentService}.
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
 *
 * <p>Phase 16F PR2 adds Path A ({@link #createInvoiceFromAcceptedQuote}, reached through
 * {@code POST /orders/{orderId}/quote/create-invoice}): an invoice version built from the order's
 * latest ACCEPTED quote snapshot, inheriting the quote's signature, using the same file-write-first /
 * rollback-cleanup persistence pattern as the flows above. It also adds the {@code V19} columns
 * ({@code source_quote_version_id}, {@code terms_snapshot}) to every invoice write here (Path A writes
 * them, D.1 / D.2 write nulls, D.8 carries them) and the InvoiceDetail {@code terms_html} /
 * {@code terms_source} fields, both through {@link InvoiceTermsSelection}.
 */
@Service
public class OrderInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(OrderInvoiceService.class);

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final int FIRST_VERSION = 1;
    private static final int DUE_DATE_OFFSET_DAYS = 2;
    private static final String PDF_MIME = "application/pdf";
    private static final String PDF_EXTENSION = "pdf";
    private static final String ADDRESS_BILLING = "BILLING";
    private static final String STATUS_LAID = "LAID";
    // Phase 16F PR1 D5(c): the quote version / token status written when D.8 kills an active link.
    private static final String QUOTE_VERSION_CANCELLED = "CANCELLED";
    private static final String QUOTE_TOKEN_CANCELLED = "CANCELLED";

    // Phase 13 D.8 Accept multipart contract (§5.1): exactly two parts, any other part name -> 400.
    private static final String SIGNATURE_PART = "signature";
    private static final String ACCEPTED_NAME_FIELD = "accepted_customer_name";
    private static final String SIGNATURE_MIME = "image/png";
    private static final String SIGNATURE_EXTENSION = "png";
    // Exactly 2 MB. size > this -> 400 SIGNATURE_INVALID; size == this is allowed.
    private static final long MAX_SIGNATURE_SIZE_BYTES = 2_097_152L;
    // The D.8 REQUEST rule (Phase 13 §5.1: max 150 chars), a clean 400 that mirrors the
    // payment_reference length guard. The column itself is TEXT since V19 (16F PR1 — remotely signed
    // quotes inherit untruncated frozen names); widening storage does not widen this request rule.
    private static final int MAX_ACCEPTED_NAME_LENGTH = 150;

    // Locked Phase 13 response messages (§5.1 / §5.2).
    private static final String ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String ACCEPT_EMAIL_FAILED_MESSAGE =
            "Invoice accepted. The invoice could not be emailed — use Re-send Invoice to try again.";
    private static final String RESEND_MESSAGE = "Invoice re-sent to the customer.";

    // Phase 16F PR2 Path A (create invoice from the accepted quote) messages. The 409 reuses
    // INVOICE_ALREADY_ACCEPTED with this Path A wording; D.8's default message is unchanged.
    private static final String CREATED_FROM_ACCEPTED_QUOTE_MESSAGE = "Invoice created from accepted quote.";
    private static final String QUOTE_NOT_NEWER_THAN_SIGNED_INVOICE_MESSAGE =
            "The current invoice was signed at the same time or later than this quote. "
                    + "A newer signed quote is required to create an invoice from a quote.";
    private static final String PAYMENTS_EXCEED_ACCEPTED_QUOTE_MESSAGE =
            "Recorded payments exceed the accepted quote total. "
                    + "Void the excess payments before creating an invoice from this quote.";

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
    private final InvoicePdfModelAssembler invoicePdfModelAssembler;
    private final FileStorageService fileStorageService;
    private final InvoiceEmailSender invoiceEmailSender;
    private final QuoteVersionRepository quoteVersionRepository;
    private final ObjectMapper objectMapper;
    // Programmatic transactions for the Phase 13 accept/resend flows: the email send must run AFTER
    // the acceptance commit (and the email-success stamp in its own follow-up transaction), so those
    // public methods cannot be @Transactional. Spring's proxy ignores self-invocation, so a private
    // @Transactional helper would not work either — TransactionTemplate is the supported alternative.
    private final TransactionTemplate transactionTemplate;

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
                               InvoicePdfModelAssembler invoicePdfModelAssembler,
                               FileStorageService fileStorageService,
                               InvoiceEmailSender invoiceEmailSender,
                               QuoteVersionRepository quoteVersionRepository,
                               PlatformTransactionManager transactionManager,
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
        this.invoicePdfModelAssembler = invoicePdfModelAssembler;
        this.fileStorageService = fileStorageService;
        this.invoiceEmailSender = invoiceEmailSender;
        this.quoteVersionRepository = quoteVersionRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
        // Acceptance fields are null on Create AND Rewrite: a new manual snapshot is always unaccepted
        // (Phase 13 §7 — Rewrite CLEARS acceptance; the customer must sign the new version again). The
        // assembler enriches the snapshot with tenant config / store / salesperson / logo / terms.
        // Phase 16F PR2: Create / Rewrite are Path B, so the terms are explicitly LIVE and both V19
        // columns are written null - a Rewrite of a Path A invoice therefore clears the quote source
        // and the frozen terms along with the acceptance (back to Path B semantics).
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
                totalPaid,
                balanceDue,
                null, null, null,
                InvoiceTermsSelection.live())));

        String storagePath = fileStorageService.store(pdfBytes, ctx.businessId(), orderId, PDF_EXTENSION);
        deleteFileOnRollback(storagePath);
        try {
            long storedFileId = invoiceRepository.insertStoredFile(fileName, storagePath, PDF_MIME, pdfBytes.length);
            InvoiceRow row = invoiceRepository.insertInvoice(
                    orderId, versionNumber, invoiceDate, dueDate, detailsSnapshot,
                    salePriceExGst, salePriceIncGst, totalPaid, balanceDue,
                    storedFileId, ctx.userId(),
                    null, null, null, null,
                    null, null);

            // Dashboard mirror invariant (Phase 13 §11.1): whenever a new current invoice version is
            // created, sales_order.last_emailed_at is set to that version's last_emailed_at — null here
            // (Create / Rewrite never email), so the dashboard cannot show a stale emailed time from a
            // previous version. Same transaction as the insert; the order row is held FOR UPDATE.
            invoiceRepository.updateSalesOrderLastEmailedAt(orderId, row.lastEmailedAt());

            InvoiceDetailDto dto = toDto(slug, orderId, row, liveTerms(ctx, order));
            return ApiResponse.ok(new InvoiceResponse(dto), successMessage);
        } catch (RuntimeException ex) {
            fileStorageService.deleteQuietly(storagePath);
            throw ex;
        }
    }

    // ------------------------------------------------------------------
    // Phase 16F PR2 - Path A: POST /orders/{orderId}/quote/create-invoice (QuoteController delegates)
    // ------------------------------------------------------------------

    /**
     * Create an invoice version from the order's LATEST ACCEPTED quote (Phase 16F PR2, "Path A";
     * decisions D4, D5(a) and D5(b) as amended on 8 October 2026, D6, D7). The quote's signature is
     * inherited, so the customer does not re-sign.
     *
     * <p><b>Order of checks</b> (all under the order row lock, which is held through the checks, the PDF
     * and file work, the insert and the mirror reset): standard-protected guard → positive
     * {@code orderId} (400) → scoped {@code FOR UPDATE} order lookup (404 {@code ORDER_NOT_FOUND}; no
     * existence leak) → empty body only ({@code {}} / blank; any field, including a quote-version
     * selector, price, terms, signature id or due date → 400 {@code VALIDATION_FAILED}; unparseable →
     * 400 {@code MALFORMED_JSON}) → the latest ACCEPTED quote version, selected by the server (none → 422
     * {@code QUOTE_NOT_ACCEPTED}; a newer draft / ISSUED / CANCELLED / EXPIRED version never replaces it)
     * → signature precedence against the CURRENT invoice ({@link AcceptedQuoteInvoiceEligibility}: no
     * invoice, an unsigned invoice, or a STRICTLY newer quote signature is allowed; an equal or older
     * quote signature → 409 {@code INVOICE_ALREADY_ACCEPTED} with the Path A message) → the collected
     * Path A preconditions (422 {@code INVOICE_PRECONDITIONS_NOT_MET}, every failing item) → active
     * payments must not exceed the signed inc-GST total (422 {@code BUSINESS_RULE_VIOLATION}) → read the
     * inherited signature → persist.
     *
     * <p><b>No LAID gate in any branch</b> ({@code requireNotLaid} is never called): Path A follows the
     * D.1 / D4 rule and appends with rewrite / payment version mechanics, not the D.2 LAID block.
     * <b>No customer-email gate and no email</b>: a missing or invalid customer email never blocks this
     * endpoint, and nothing is sent (manual Re-send stays the only invoice email path).
     *
     * <p><b>Fields - the signed snapshot only, never the live sale:</b> details of sale and the ex / inc
     * totals verbatim from the accepted version (each copied independently, never recomputed); its frozen
     * {@code terms_snapshot} verbatim, including null; {@code source_quote_version_id} = the selected
     * version; {@code accepted_at} / {@code accepted_customer_name} / {@code accepted_signature_file_id}
     * inherited verbatim (the SAME signature {@code stored_file} row: no upload, copy, re-normalisation or
     * new file, and no D.8 name-length gate). {@code invoice_date} = today; {@code due_date} = the
     * order's current {@code proposed_lay_date} minus 2 days; Invoice To = the live saved customer and
     * billing rows (the existing derivation); {@code total_paid} = active (non-voided) payments;
     * {@code balance_due} = signed inc total − active payments; {@code last_emailed_at} null. The invoice
     * stays details + totals even for an itemised quote (no invoice lines, nothing copied into Products
     * &amp; Charges).
     *
     * <p><b>Writes:</b> the new invoice PDF is written FIRST with rollback cleanup (in-method + an
     * {@code afterCompletion} hook), then its {@code stored_file} row, the invoice row (both {@code V19}
     * columns set; source ownership verified by {@link InvoiceRepository#insertInvoice}) and the section 11.1
     * mirror reset ({@code sales_order.last_emailed_at} = null - the only order write). Any render,
     * storage, insert or commit failure rolls every write back and deletes only this request's PDF;
     * the inherited signature and every pre-existing PDF are never registered for deletion. The order's
     * status, working price / override, GP / cost / header financials, product and charge lines, the
     * quote draft / versions / tokens (an ISSUED version and its ACTIVE token stay untouched - only a
     * successful D.8 cancels a link, D5(c)), customer / address rows and payments are never written; the
     * D6b price write happened at quote acceptance and is not rerun here.
     *
     * <p>{@code @Transactional} is real here: {@code QuoteController} calls this public method through
     * the Spring proxy (no self-invocation).
     */
    @Transactional
    public ApiResponse<InvoiceResponse> createInvoiceFromAcceptedQuote(String slug,
                                                                       String orderIdRaw,
                                                                       String body,
                                                                       HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        // 1. Scope + lock FIRST. Every price / payment / version / quote input below is read after this,
        // so it serialises with D.8, payment / void, rewrite and the public quote accept.
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // 2. Empty body only - the server selects the quote version; clients send no fields.
        rejectNonEmptyBody(body);

        // 3. The LATEST ACCEPTED version (max version_number among ACCEPTED) - not the newest version.
        AcceptedQuoteVersionRow accepted = quoteVersionRepository.findLatestAcceptedByOrderId(orderId)
                .orElseThrow(() -> new BusinessRuleException(
                        ErrorCode.QUOTE_NOT_ACCEPTED, ErrorCode.QUOTE_NOT_ACCEPTED.defaultMessage()));
        QuoteVersionRow quote = accepted.version();

        // 4. D5(b) signature precedence vs the CURRENT invoice (max version_number), by signature time.
        Optional<InvoiceRow> current = invoiceRepository.findCurrentByOrderId(orderId);
        LocalDateTime currentInvoiceAcceptedAt = current.map(InvoiceRow::acceptedAt).orElse(null);
        if (!AcceptedQuoteInvoiceEligibility.allows(accepted.acceptedAt(), currentInvoiceAcceptedAt)) {
            throw new ConflictException(ErrorCode.INVOICE_ALREADY_ACCEPTED, QUOTE_NOT_NEWER_THAN_SIGNED_INVOICE_MESSAGE);
        }

        // 5. Path A preconditions, all collected: the retained live identity / address / lay-date checks
        // plus the signed snapshot's details and totals (never the live details, lines or price).
        OrderCustomer customer = orderCustomerRepository.findByOrderId(orderId).orElse(null);
        List<OrderAddress> addresses = orderAddressRepository.findByOrderId(orderId);
        List<ErrorDetail> failures = preconditionValidator.collectAcceptedQuoteFailures(
                order, customer, addresses,
                quote.detailsOfSaleSnapshot(), quote.quoteTotalExGst(), quote.quoteTotalIncGst());
        if (!failures.isEmpty()) {
            throw new BusinessRuleException(
                    ErrorCode.INVOICE_PRECONDITIONS_NOT_MET,
                    ErrorCode.INVOICE_PRECONDITIONS_NOT_MET.defaultMessage(),
                    failures);
        }

        // 6. Active (non-voided) payments vs the signed inc-GST total. Reachable (payments recorded
        // against an earlier, higher invoice), so a clean 422 with nothing written; exactly paid is fine.
        BigDecimal salePriceExGst = quote.quoteTotalExGst();
        BigDecimal salePriceIncGst = quote.quoteTotalIncGst();
        BigDecimal totalPaid = paymentTransactionRepository.sumAmountByOrderId(orderId);
        BigDecimal balanceDue = salePriceIncGst.subtract(totalPaid).setScale(MONEY_SCALE, ROUNDING);
        if (balanceDue.signum() < 0) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION, PAYMENTS_EXCEED_ACCEPTED_QUOTE_MESSAGE);
        }

        // 7. The inherited signature bytes, through the quote's EXISTING stored_file reference.
        byte[] signaturePng = readInheritedQuoteSignature(accepted);

        // 8. Persist: render, write the new PDF FIRST (rollback cleanup), then the stored_file + invoice
        // rows and the mirror reset - the established file-write-first pattern of D.1 / D.2.
        int versionNumber = current.map(row -> row.versionNumber() + 1).orElse(FIRST_VERSION);
        LocalDate invoiceDate = LocalDate.now();
        LocalDate dueDate = order.getProposedLayDate().minusDays(DUE_DATE_OFFSET_DAYS);
        String detailsSnapshot = quote.detailsOfSaleSnapshot();
        String termsSnapshot = quote.termsSnapshot();

        String fileName = "invoice-" + order.getOrderNumber() + "-v" + versionNumber + "." + PDF_EXTENSION;
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
                totalPaid,
                balanceDue,
                accepted.acceptedAt(),
                accepted.acceptedCustomerName(),
                signaturePng,
                InvoiceTermsSelection.frozenQuoteTerms(termsSnapshot))));

        String storagePath = fileStorageService.store(pdfBytes, ctx.businessId(), orderId, PDF_EXTENSION);
        deleteFileOnRollback(storagePath);
        try {
            long storedFileId = invoiceRepository.insertStoredFile(fileName, storagePath, PDF_MIME, pdfBytes.length);
            InvoiceRow row = invoiceRepository.insertInvoice(
                    orderId, versionNumber, invoiceDate, dueDate, detailsSnapshot,
                    salePriceExGst, salePriceIncGst, totalPaid, balanceDue,
                    storedFileId, ctx.userId(),
                    accepted.acceptedAt(), accepted.acceptedCustomerName(), accepted.acceptedSignatureFileId(), null,
                    quote.quoteVersionId(), termsSnapshot);

            // Dashboard mirror invariant (Phase 13 section 11.1): the new current version is unemailed, so the
            // sales_order mirror is reset to its null last_emailed_at in the same transaction.
            invoiceRepository.updateSalesOrderLastEmailedAt(orderId, row.lastEmailedAt());

            InvoiceDetailDto dto = toDto(slug, orderId, row, liveTerms(ctx, order));
            return ApiResponse.ok(new InvoiceResponse(dto), CREATED_FROM_ACCEPTED_QUOTE_MESSAGE);
        } catch (RuntimeException ex) {
            fileStorageService.deleteQuietly(storagePath);
            throw ex;
        }
    }

    /**
     * Path A: read the accepted quote's stored signature through its EXISTING signature
     * {@code stored_file} reference - the invoice references that same row (no upload, no copy, no
     * re-normalisation, no new signature row or file, no multipart validation). Incomplete acceptance
     * metadata, a missing {@code stored_file} row or an unreadable file is an invariant / storage failure
     * (500, nothing written): never an unsigned invoice, never another signature.
     */
    private byte[] readInheritedQuoteSignature(AcceptedQuoteVersionRow accepted) {
        long quoteVersionId = accepted.version().quoteVersionId();
        if (accepted.acceptedAt() == null
                || accepted.acceptedCustomerName() == null
                || accepted.acceptedCustomerName().isBlank()
                || accepted.acceptedSignatureFileId() == null) {
            throw new IllegalStateException(
                    "Accepted quote_version " + quoteVersionId + " has incomplete acceptance metadata");
        }
        InvoiceFile signatureFile = invoiceRepository.findStoredFileById(accepted.acceptedSignatureFileId())
                .orElseThrow(() -> new IllegalStateException(
                        "Signature stored_file row missing for accepted quote_version " + quoteVersionId));
        // Missing / unreadable on disk -> UncheckedIOException -> 500 (still before any write).
        return fileStorageService.read(signatureFile.storagePath());
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
        SalesOrder order = requireOrderInScope(orderId, ctx);

        InvoiceRow row = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.INVOICE_NOT_FOUND,
                        ErrorCode.INVOICE_NOT_FOUND.defaultMessage()));

        return ApiResponse.ok(new InvoiceResponse(
                toDto(slug, orderId, row, liveTerms(ctx, order))));
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
    // D.8 POST /orders/{orderId}/invoices/current/accept — accept + sign + auto-email (Phase 13B)
    // ------------------------------------------------------------------

    /**
     * Accept the current invoice (Phase 13 §5.1): capture the customer signature + accepted name,
     * APPEND a new current version (max + 1) carrying the sale snapshot forward unchanged, generate
     * the signed PDF, then auto-email it to the customer. ALLOWED when LAID (parallels payment, not
     * rewrite — Chunk 4 F.8): acceptance carries the snapshot forward and never makes unsent live
     * edits official, so {@code requireNotLaid} is deliberately not called.
     *
     * <p>Deliberately NOT {@code @Transactional}: the persist runs in ONE programmatic transaction
     * (signature {@code stored_file} + signed-PDF {@code stored_file} + appended {@code invoice} row +
     * §11.1 mirror reset — any failure rolls all of it back and the rollback hooks remove both
     * just-written files), the email is sent strictly AFTER that commit, and on send success the
     * delivery timestamp is stamped in a short follow-up transaction. An email failure NEVER rolls
     * back acceptance and never errors this endpoint: the response is still 201 with
     * {@code last_emailed_at = null} and a message pointing at Re-send (contract §13 — D.9 Resend is
     * the only path that surfaces {@code EMAIL_SEND_FAILED}). The response DTO is built from the FINAL
     * post-email state, so {@code last_emailed_at} reflects the actual send outcome.
     *
     * <p>Phase 16F PR1 (decision D5(c)/(d)): the same persist transaction also cancels the order's
     * ISSUED quote version + its ACTIVE token, if one exists, so a quote link can no longer be signed
     * once the invoice is signed in-app. An accepted quote never blocks this endpoint and is never
     * revoked by it; the request contract, validation order, LAID allowance and email behaviour are
     * unchanged.
     */
    public ApiResponse<InvoiceResponse> acceptCurrentInvoice(String slug,
                                                             String orderIdRaw,
                                                             MultipartHttpServletRequest request) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, request);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        AcceptedInvoice accepted = transactionTemplate.execute(
                status -> validateAndPersistAcceptance(orderId, ctx, request));

        // Post-commit auto-email (non-fatal). The acceptance above is durable regardless of outcome.
        boolean emailed = sendInvoiceEmailQuietly(ctx, orderId, accepted);

        InvoiceRow finalRow = emailed
                ? transactionTemplate.execute(status ->
                        stampEmailSuccess(orderId, ctx, accepted.row().invoiceId(), LocalDateTime.now()))
                : accepted.row();

        return ApiResponse.ok(new InvoiceResponse(toDto(slug, orderId, finalRow, accepted::liveTermsHtml)),
                emailed ? ACCEPT_EMAILED_MESSAGE : ACCEPT_EMAIL_FAILED_MESSAGE);
    }

    /**
     * The D.8 validation chain (contract §5.1, exact order) + single-transaction persist. Runs inside
     * {@code transactionTemplate.execute}, so the order row is held {@code FOR UPDATE} for the whole
     * block — version allocation and the already-accepted check are serialised against a concurrent
     * accept / rewrite / payment (the race loser gets 409 here, not a constraint violation).
     */
    private AcceptedInvoice validateAndPersistAcceptance(long orderId,
                                                         RequestContext ctx,
                                                         MultipartHttpServletRequest request) {
        // 1. Scope. FOR UPDATE — see method javadoc. No LAID gate (accept is LAID-allowed).
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // 2. A current invoice must exist (accept the FIRST invoice via D.1 Create before signing).
        InvoiceRow current = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new BusinessRuleException(
                        ErrorCode.INVOICE_REQUIRED, ErrorCode.INVOICE_REQUIRED.defaultMessage()));

        // 3. Not already accepted — checked under the order lock (double-submit / race -> 409).
        if (current.acceptedAt() != null) {
            throw new ConflictException(ErrorCode.INVOICE_ALREADY_ACCEPTED,
                    ErrorCode.INVOICE_ALREADY_ACCEPTED.defaultMessage());
        }

        // 4-6. Multipart shape: unknown parts first (400, mirrors the attachment-upload structural
        // check), then the name (422), then the signature (422 missing/empty; 400 bad MIME/oversize).
        rejectUnexpectedAcceptParts(request);
        String acceptedCustomerName = validateAcceptedCustomerName(request.getParameter(ACCEPTED_NAME_FIELD));
        byte[] signatureBytes = validateSignature(request.getFile(SIGNATURE_PART));

        // 7. Email gate LAST (contract §5.1 order — contrast D.1/D.2 where it runs first).
        OrderCustomer customer = orderCustomerRepository.findByOrderId(orderId).orElse(null);
        customerEmailValidator.requireValidCustomerEmail(customer);

        // 8. Persist in THIS transaction. Both files are written before their rows with a rollback
        // hook each + in-method cleanup (the established file-write-first pattern, here twice).
        int versionNumber = current.versionNumber() + 1;
        LocalDateTime acceptedAt = LocalDateTime.now();
        LocalDate invoiceDate = LocalDate.now();

        String signatureFileName = "signature-" + order.getOrderNumber() + "-v" + versionNumber
                + "." + SIGNATURE_EXTENSION;
        String signaturePath = fileStorageService.store(
                signatureBytes, ctx.businessId(), orderId, SIGNATURE_EXTENSION);
        deleteFileOnRollback(signaturePath);
        try {
            long signatureFileId = invoiceRepository.insertStoredFile(
                    signatureFileName, signaturePath, SIGNATURE_MIME, signatureBytes.length);

            // Signed PDF: sale snapshot + totals carried forward from the current row UNCHANGED (no
            // live re-read — accepting never makes unsent edits official); customer/billing lines are
            // presentational (mirrors the payment receipt). The acceptance block embeds the signature.
            // Phase 16F PR2: the two V19 columns are carried verbatim from the current row (defensive -
            // a Path A row is already accepted, so it is refused above) and the terms follow that
            // row's source: frozen quote terms when it has one, live terms otherwise.
            List<OrderAddress> addresses = orderAddressRepository.findByOrderId(orderId);
            String pdfFileName = "invoice-" + order.getOrderNumber() + "-v" + versionNumber + "." + PDF_EXTENSION;
            byte[] pdfBytes = invoicePdfGenerator.render(invoicePdfModelAssembler.assemble(new Inputs(
                    ctx.business(),
                    order,
                    versionNumber,
                    invoiceDate,
                    current.dueDate(),
                    customerName(customer),
                    billingLine1(addresses),
                    billingLine2(addresses),
                    current.detailsOfSaleSnapshot(),
                    current.salePriceIncGst(),
                    current.totalPaid(),
                    current.balanceDue(),
                    acceptedAt,
                    acceptedCustomerName,
                    signatureBytes,
                    InvoiceTermsSelection.forInvoice(current.sourceQuoteVersionId(), current.termsSnapshot()))));

            String pdfPath = fileStorageService.store(pdfBytes, ctx.businessId(), orderId, PDF_EXTENSION);
            deleteFileOnRollback(pdfPath);
            try {
                long pdfFileId = invoiceRepository.insertStoredFile(
                        pdfFileName, pdfPath, PDF_MIME, pdfBytes.length);
                InvoiceRow row = invoiceRepository.insertInvoice(
                        orderId, versionNumber, invoiceDate, current.dueDate(),
                        current.detailsOfSaleSnapshot(), current.salePriceExGst(), current.salePriceIncGst(),
                        current.totalPaid(), current.balanceDue(),
                        pdfFileId, ctx.userId(),
                        acceptedAt, acceptedCustomerName, signatureFileId, null,
                        current.sourceQuoteVersionId(), current.termsSnapshot());

                // §11.1 mirror: the new current version starts unemailed, so the dashboard mirror is
                // reset to null here; the post-commit email-success stamp sets both to the same value.
                invoiceRepository.updateSalesOrderLastEmailedAt(orderId, row.lastEmailedAt());

                // Phase 16F PR1 — decision D5(c): the in-app invoice signature kills an ACTIVE quote
                // link so the quote can no longer be signed remotely. Same transaction and same order
                // lock as this acceptance, so both commit or roll back together (never post-commit,
                // never dependent on the email below). One direction only (D5(d)): an already
                // ACCEPTED quote is never touched and never blocks D.8; no ISSUED version → no-op.
                cancelActiveQuoteLink(orderId, acceptedAt);

                // Phase 16F PR2: the response's live terms are resolved HERE, inside the acceptance
                // transaction, so building the response after the commit and email needs no DB read.
                String liveTermsHtml = invoicePdfModelAssembler.liveTermsHtml(ctx.businessId(), order.getFlooringType());

                return new AcceptedInvoice(row, order.getOrderNumber(), liveTermsHtml,
                        customer.getEmail().trim(), pdfBytes, pdfFileName);
            } catch (RuntimeException ex) {
                fileStorageService.deleteQuietly(pdfPath);
                throw ex;
            }
        } catch (RuntimeException ex) {
            fileStorageService.deleteQuietly(signaturePath);
            throw ex;
        }
    }

    /**
     * Phase 16F PR1 — decision D5(c). Cancel the order's ISSUED quote version (if any) and kill its
     * ACTIVE token with the {@code CANCELLED} reason, inside the caller's D.8 persist transaction
     * (the order row is already held {@code FOR UPDATE}). Deliberately NOT the protected "Cancel
     * quote" endpoint: that one fails with 422 {@code QUOTE_NOT_ISSUED} / 409
     * {@code QUOTE_ALREADY_ACCEPTED} when nothing is issued, which must never block an invoice
     * acceptance. Here no ISSUED version is a silent no-op; ACCEPTED / SUPERSEDED / EXPIRED /
     * CANCELLED versions and their (already dead) tokens are never touched. The guarded
     * {@code moveIssuedVersionTo} cannot miss under the held order lock (every quote transition,
     * including the public lazy expiry and accept, takes the same lock).
     */
    private void cancelActiveQuoteLink(long orderId, LocalDateTime cancelledAt) {
        quoteVersionRepository.findIssuedByOrderId(orderId).ifPresent(issued -> {
            quoteVersionRepository.moveIssuedVersionTo(issued.quoteVersionId(), QUOTE_VERSION_CANCELLED);
            quoteVersionRepository.killActiveToken(issued.quoteVersionId(), QUOTE_TOKEN_CANCELLED, cancelledAt);
        });
    }

    // ------------------------------------------------------------------
    // D.9 POST /orders/{orderId}/invoices/current/resend — re-email the accepted invoice (Phase 13B)
    // ------------------------------------------------------------------

    /**
     * Re-email the current ACCEPTED invoice PDF (Phase 13 §5.2). Allowed when LAID (re-emails an
     * existing artifact). No PDF regeneration, no new invoice version, no file writes — the only write
     * is the in-place {@code last_emailed_at} stamp (invoice + §11.1 mirror, same timestamp, one
     * transaction) and ONLY on send success. Here the send IS the operation, so a provider failure is
     * fatal: 502 {@code EMAIL_SEND_FAILED} with NOTHING written (both timestamps unchanged — the
     * salesperson may simply retry). Works when the invoice is accepted but {@code last_emailed_at} is
     * still null (a failed D.8 auto-email — contract line 41: Re-send does not depend on a prior
     * successful send). Gate order: scope (404) -> empty body (400) -> invoice required (422) ->
     * accepted (422 {@code INVOICE_NOT_ACCEPTED}) -> email gate (422) -> send.
     */
    public ApiResponse<InvoiceResponse> resendCurrentInvoice(String slug,
                                                             String orderIdRaw,
                                                             String body,
                                                             HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");

        ResendEmail email = transactionTemplate.execute(status -> validateResend(orderId, ctx, body));

        try {
            invoiceEmailSender.send(new InvoiceEmailRequest(
                    email.recipientEmail(),
                    emailSubject(ctx.business().getName()),
                    emailBody(email.orderNumber(), ctx.business().getName()),
                    email.pdfBytes(),
                    email.pdfFileName(),
                    orderId,
                    email.versionNumber()));
        } catch (InvoiceEmailException ex) {
            // D.9 only: the send is the whole operation -> 502; nothing has been written.
            log.warn("Resend email failed for order {} invoice v{}", orderId, email.versionNumber(), ex);
            throw new EmailSendException(ErrorCode.EMAIL_SEND_FAILED.defaultMessage());
        }

        InvoiceRow finalRow = transactionTemplate.execute(status ->
                stampEmailSuccess(orderId, ctx, email.invoiceId(), LocalDateTime.now()));
        return ApiResponse.ok(new InvoiceResponse(toDto(slug, orderId, finalRow, email::liveTermsHtml)), RESEND_MESSAGE);
    }

    /** D.9 validation chain + current-PDF load (read-only; runs in its own short transaction). */
    private ResendEmail validateResend(long orderId, RequestContext ctx, String body) {
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // Body must be empty ({} / blank) — any field -> 400 VALIDATION_FAILED (after the scope gate).
        rejectNonEmptyBody(body);

        InvoiceRow current = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new BusinessRuleException(
                        ErrorCode.INVOICE_REQUIRED, ErrorCode.INVOICE_REQUIRED.defaultMessage()));

        if (current.acceptedAt() == null) {
            throw new BusinessRuleException(ErrorCode.INVOICE_NOT_ACCEPTED,
                    ErrorCode.INVOICE_NOT_ACCEPTED.defaultMessage());
        }

        OrderCustomer customer = orderCustomerRepository.findByOrderId(orderId).orElse(null);
        customerEmailValidator.requireValidCustomerEmail(customer);

        // The current invoice exists, so its NOT NULL stored_file row exists too.
        InvoiceFile file = invoiceRepository.findCurrentFileByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Current invoice has no stored_file row for order " + orderId));
        // No regeneration: the stored signed PDF is re-sent as-is. Missing-on-disk -> 500.
        byte[] pdfBytes = fileStorageService.read(file.storagePath());

        // Phase 16F PR2: the response's live terms are resolved here, before the send, so building the
        // response after the send and the stamp needs no DB read.
        String liveTermsHtml = invoicePdfModelAssembler.liveTermsHtml(ctx.businessId(), order.getFlooringType());

        return new ResendEmail(current.invoiceId(), current.versionNumber(), order.getOrderNumber(),
                liveTermsHtml, customer.getEmail().trim(), pdfBytes, file.fileName());
    }

    // ------------------------------------------------------------------
    // D.10 GET /orders/{orderId}/invoices/current/signature — stream the accepted signature (Phase 13B)
    // ------------------------------------------------------------------

    /**
     * Stream the current invoice's accepted signature image (Phase 13 §5.3). Read-only; allowed
     * regardless of LAID. Gate order: scope (404 {@code ORDER_NOT_FOUND}) -> current invoice exists
     * (404 {@code INVOICE_NOT_FOUND} — the empty state) -> signature present (422
     * {@code INVOICE_NOT_ACCEPTED}; defensive — the frontend only calls this when
     * {@code accepted_signature_present == true}). The download filename is built from the locked
     * order number + the CURRENT {@code version_number} (NOT {@code stored_file.file_name}: once 13C
     * carries a signature forward, the stored name keeps the version that originally captured it).
     * {@code storage_path} is used only to read the bytes and is never returned; missing-on-disk ->
     * 500 via the standard JSON wrapper.
     */
    @Transactional(readOnly = true)
    public InvoiceFileDownload downloadCurrentSignature(String slug,
                                                        String orderIdRaw,
                                                        HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        InvoiceRow current = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.INVOICE_NOT_FOUND,
                        ErrorCode.INVOICE_NOT_FOUND.defaultMessage()));

        if (current.acceptedSignatureFileId() == null) {
            throw new BusinessRuleException(ErrorCode.INVOICE_NOT_ACCEPTED,
                    ErrorCode.INVOICE_NOT_ACCEPTED.defaultMessage());
        }

        InvoiceFile file = invoiceRepository.findStoredFileById(current.acceptedSignatureFileId())
                .orElseThrow(() -> new IllegalStateException(
                        "Signature stored_file row missing for invoice " + current.invoiceId()));

        byte[] bytes = fileStorageService.read(file.storagePath());
        String downloadName = "signature-" + order.getOrderNumber()
                + "-v" + current.versionNumber() + "." + SIGNATURE_EXTENSION;
        return new InvoiceFileDownload(bytes, file.mimeType(), downloadName, file.fileSize());
    }

    // ------------------------------------------------------------------
    // Phase 13 shared email helpers
    // ------------------------------------------------------------------

    /**
     * Attempt the D.8 post-commit auto-email; report success/failure WITHOUT throwing. Only the
     * transport failure ({@link InvoiceEmailException}) is non-fatal by contract — anything else is a
     * real bug and propagates as 500.
     */
    private boolean sendInvoiceEmailQuietly(RequestContext ctx, long orderId, AcceptedInvoice accepted) {
        try {
            invoiceEmailSender.send(new InvoiceEmailRequest(
                    accepted.recipientEmail(),
                    emailSubject(ctx.business().getName()),
                    emailBody(accepted.orderNumber(), ctx.business().getName()),
                    accepted.pdfBytes(),
                    accepted.pdfFileName(),
                    orderId,
                    accepted.row().versionNumber()));
            return true;
        } catch (InvoiceEmailException ex) {
            // Acceptance is already committed and durable; last_emailed_at stays null on BOTH the
            // invoice and the sales_order mirror, and the 201 message points the user at Re-send.
            log.warn("Accept auto-email failed for order {} invoice v{} (non-fatal; acceptance persisted)",
                    orderId, accepted.row().versionNumber(), ex);
            return false;
        }
    }

    /**
     * Email-success stamp (one short transaction): set {@code invoice.last_emailed_at} in place, then
     * set the §11.1 mirror to the CURRENT invoice's value re-read from the database — so both columns
     * carry the SAME timestamp. The scoped order row is taken {@code FOR UPDATE} FIRST (the same lock
     * every version-creating transaction holds from its first statement), so the current-invoice
     * re-read cannot race a concurrent rewrite/payment and overwrite the mirror with a stale value.
     * Returns the current row, from which the response DTO is built.
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

    // Simple MVP wording (contract §9): subject + short body, PDF attached.
    private static String emailSubject(String businessName) {
        return "Your invoice from " + businessName;
    }

    private static String emailBody(String orderNumber, String businessName) {
        return "Hi,\n\nPlease find your invoice " + orderNumber + " from " + businessName
                + " attached.\n\nThank you.";
    }

    // ------------------------------------------------------------------
    // D.8 multipart validation (contract §5.1 rules 4-6)
    // ------------------------------------------------------------------

    /**
     * Multipart shape check (contract §5.1): any part / form field other than {@code signature} and
     * {@code accepted_customer_name} -> one 400 {@code VALIDATION_FAILED} naming every offending part
     * (mirrors the attachment upload). The two allowed parts must also appear AT MOST ONCE — a
     * duplicated part would otherwise be silently collapsed by {@code getFile}/{@code getParameter}
     * picking one value, so a repeated {@code signature} or {@code accepted_customer_name} is rejected
     * here too (presence/required-ness stays with the later 422 checks).
     */
    private static void rejectUnexpectedAcceptParts(MultipartHttpServletRequest request) {
        List<ErrorDetail> errors = new ArrayList<>();
        for (Iterator<String> fileNames = request.getFileNames(); fileNames.hasNext();) {
            String name = fileNames.next();
            if (!SIGNATURE_PART.equals(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
        for (Enumeration<String> paramNames = request.getParameterNames(); paramNames.hasMoreElements();) {
            String name = paramNames.nextElement();
            if (!ACCEPTED_NAME_FIELD.equals(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
        if (request.getFiles(SIGNATURE_PART).size() > 1) {
            errors.add(new ErrorDetail(null, SIGNATURE_PART, "Must appear at most once."));
        }
        String[] nameValues = request.getParameterValues(ACCEPTED_NAME_FIELD);
        if (nameValues != null && nameValues.length > 1) {
            errors.add(new ErrorDetail(null, ACCEPTED_NAME_FIELD, "Must appear at most once."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }

    /** Missing/blank -> 422 ACCEPTED_CUSTOMER_NAME_REQUIRED; over 150 chars after trim -> 400. */
    private static String validateAcceptedCustomerName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessRuleException(ErrorCode.ACCEPTED_CUSTOMER_NAME_REQUIRED,
                    ErrorCode.ACCEPTED_CUSTOMER_NAME_REQUIRED.defaultMessage());
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_ACCEPTED_NAME_LENGTH) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, ACCEPTED_NAME_FIELD, "Must be at most 150 characters.")));
        }
        return trimmed;
    }

    /**
     * Missing/empty part -> 422 {@code SIGNATURE_REQUIRED}; wrong MIME or over 2 MB -> 400
     * {@code SIGNATURE_INVALID} (one code for both, per §12 — exactly 2 MB is allowed). The declared
     * part Content-Type is trusted, mirroring the attachment upload; the contract specifies no
     * magic-byte sniffing.
     */
    private byte[] validateSignature(MultipartFile signature) {
        if (signature == null || signature.isEmpty()) {
            throw new BusinessRuleException(ErrorCode.SIGNATURE_REQUIRED,
                    ErrorCode.SIGNATURE_REQUIRED.defaultMessage());
        }
        if (!SIGNATURE_MIME.equals(signature.getContentType())
                || signature.getSize() > MAX_SIGNATURE_SIZE_BYTES) {
            throw new FileUploadException(ErrorCode.SIGNATURE_INVALID,
                    ErrorCode.SIGNATURE_INVALID.defaultMessage());
        }
        try {
            return signature.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded signature", e);
        }
    }

    // ------------------------------------------------------------------
    // Scoping (read)
    // ------------------------------------------------------------------

    /** Read scope (no lock). 404 ORDER_NOT_FOUND when out of the session's (business, store). */
    private SalesOrder requireOrderInScope(long orderId, RequestContext ctx) {
        return salesOrderRepository
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

    /**
     * Map an invoice row to the E.2 InvoiceDetail. {@code liveTermsHtml} supplies the business's current
     * per-flooring-type terms and is only called for a LIVE row: in-transaction callers pass
     * {@link #liveTerms}, while D.8 / D.9 pass a value they resolved inside their transaction BEFORE the
     * email send, so no database read happens after a commit or a send.
     */
    private static InvoiceDetailDto toDto(String slug, long orderId, InvoiceRow row, Supplier<String> liveTermsHtml) {
        // accepted_signature_present / the download path are DERIVED from the internal
        // accepted_signature_file_id, which itself is never serialized (mirrors how stored_file_id is
        // hidden behind pdf_download_path). The path is built only when a signature is actually stored.
        boolean signaturePresent = row.acceptedSignatureFileId() != null;
        // Phase 16F PR2 (D7): terms_html / terms_source by the SAME rule the invoice PDF uses - the
        // row's source quote version decides (QUOTE: frozen terms_snapshot verbatim, even null; LIVE:
        // the business's current per-flooring-type terms, sanitised). The source id is never exposed.
        InvoiceTermsSelection terms = InvoiceTermsSelection.forInvoice(row.sourceQuoteVersionId(), row.termsSnapshot());
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
                row.lastEmailedAt(),
                terms.resolve(liveTermsHtml),
                terms.source().name());
    }

    /** Lazy live-terms reader for the InvoiceDetail of a LIVE row; call only inside a transaction. */
    private Supplier<String> liveTerms(RequestContext ctx, SalesOrder order) {
        return () -> invoicePdfModelAssembler.liveTermsHtml(ctx.businessId(), order.getFlooringType());
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

    /**
     * Committed D.8 acceptance + what the post-commit auto-email needs (never serialized).
     * {@code liveTermsHtml} is the business's live terms resolved inside the acceptance transaction,
     * used for the response's terms_html when the final row is LIVE (Phase 16F PR2).
     */
    private record AcceptedInvoice(InvoiceRow row, String orderNumber, String liveTermsHtml,
                                   String recipientEmail, byte[] pdfBytes, String pdfFileName) {
    }

    /**
     * Validated D.9 resend input: the current accepted invoice + its stored PDF (never serialized).
     * {@code liveTermsHtml} is resolved in the validation transaction for the response's terms_html
     * when the final row is LIVE (Phase 16F PR2).
     */
    private record ResendEmail(long invoiceId, int versionNumber, String orderNumber, String liveTermsHtml,
                               String recipientEmail, byte[] pdfBytes, String pdfFileName) {
    }
}
