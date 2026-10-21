package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.email.InvoiceAcceptanceNotificationRequest;
import com.flooring.salesportal.common.email.InvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ConflictException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.FileUploadException;
import com.flooring.salesportal.common.error.GoneException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.image.SignaturePngNormaliser;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.common.token.TokenHash;
import com.flooring.salesportal.order.InvoicePdfModelAssembler.Inputs;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceFile;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
import com.flooring.salesportal.order.InvoiceTokenRepository.InvoiceTokenRow;
import com.flooring.salesportal.order.SignedInvoiceEmailer.CommittedSignedInvoice;
import com.flooring.salesportal.order.dto.PublicInvoiceAcceptResultDto;
import com.flooring.salesportal.order.dto.PublicInvoiceViewDto;
import com.flooring.salesportal.order.quote.QuoteVersionRepository;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessInvoiceConfigView;
import com.flooring.salesportal.tenant.BusinessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Phase 16G PR 4 - the public, token-only invoice surface (16G contract §7 to §10): open the order's
 * current invoice behind a link, stream its stored PDF, and sign it remotely. A NEW, SEPARATE
 * unauthenticated surface authenticated ONLY by the secret invoice token: it never reads or creates a
 * session, never calls {@code RequestContextGuard}, takes no slug and derives the order, business and
 * store from the token's order. Nothing beyond the documented view of that order's current invoice is
 * readable, and a successful accept (plus lazy expiry) is the only write it can perform.
 *
 * <p><b>Token gate ({@link #resolve}, its own transaction).</b> A presented token that does not match
 * {@code ^[A-Za-z0-9_-]{43,128}$} is rejected before any database access exactly like an unknown token
 * (404 {@code INVOICE_TOKEN_NOT_FOUND} "Invoice not found."); otherwise it is SHA-256 hashed
 * ({@link TokenHash}), looked up in {@code invoice_token} ONLY (a quote token is never consulted, so it is
 * an unknown token here) and the stored hash is re-verified in constant time. Lazy expiry: an
 * {@code ACTIVE} token past {@code expires_at} takes the order row lock
 * ({@link QuoteVersionRepository#lockOrderRowForPublicTransition}, the public quote surface's unscoped
 * lock, which serialises with every protected order mutation), is re-read by id and is flipped to
 * {@code EXPIRED} only if it is still {@code ACTIVE} and still past expiry - a writer's true dead reason is
 * never relabelled, and a token no longer past expiry by a fresh clock reading under the lock stays
 * {@code ACTIVE}, unwritten - and the flip COMMITS before any dead GET or 410 is returned. No invoice row is
 * changed by expiry.
 *
 * <p><b>One snapshot per read.</b> The GET view and the pdf re-read the token status, the order, the
 * current invoice version and (GET) the live business presentation / (pdf) the current stored file in ONE
 * read-only {@code REPEATABLE_READ} transaction (the {@code QuoteService.getWorkspace} precedent, here an
 * explicit {@link TransactionTemplate}), separate from the gate: a token killed between the gate and the
 * read is reported in its dead state, never with {@code ACTIVE} content, and no response mixes one
 * version's content with another's. An {@code ACTIVE} token over a SIGNED current version breaks the
 * never-signable-when-signed invariant (§7.6): a generic 500 with no content. An expiry that falls due
 * between the gate and the read sends the request through the gate once more, so its flip commits first.
 *
 * <p><b>Remote accept ({@link #accept}).</b> Token gate (a dead link gets its 410 whatever the body) →
 * body validation with no database access (one aggregated 400, then 422 / 400 for the signature, the
 * public quote accept's PNG rules via {@link SignaturePngNormaliser}) → ONE transaction under the order
 * row lock: token re-read (dead → its 410; newly over-age → the committed {@code EXPIRED} flip, then 410),
 * the current version, the authoritative stale comparison (409 {@code INVOICE_VERSION_STALE}), the
 * invariant, the frozen accepted name (422, customer-neutral wording), then the D.8 file-write-first
 * persistence of a signing version copied from the current one, the mirror reset, the token
 * {@code ACTIVE -> SIGNED} and the D5(c) quote-link cancellation, atomically → strictly after commit, the
 * customer's signed-PDF email ({@link SignedInvoiceEmailer}) and the store notification, each independent
 * and non-fatal. No customer-email, quote-state, LAID or CANCELLED gate, and no order price, status, line,
 * draft or payment write.
 *
 * <p><b>Logging.</b> Never the token, its hash, the link, a recipient or a body; every WARN carries only
 * the order id and invoice version.
 */
@Service
public class PublicInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(PublicInvoiceService.class);

    // invoice_token statuses = the public states (16G contract §7.2); no mapping, no quote states.
    private static final String TOKEN_ACTIVE = "ACTIVE";
    private static final String TOKEN_REPLACED = "REPLACED";
    private static final String TOKEN_REWRITTEN = "REWRITTEN";
    private static final String TOKEN_SIGNED = "SIGNED";
    private static final String TOKEN_EXPIRED = "EXPIRED";

    // Minted tokens are 32 CSPRNG bytes -> 43 URL-safe Base64 characters. Any other shape cannot be a real
    // token and is the unknown-token 404 without touching the database (malformed and unknown must be
    // indistinguishable).
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");

    // Locked 16G PR 4 response messages.
    private static final String LOADED_MESSAGE = "Invoice loaded.";
    private static final String ACCEPTED_MESSAGE = "Invoice accepted.";
    /** The customer-neutral wording of the one business-rule refusal of the remote accept (16G §9.4). */
    static final String PUBLIC_CONTACT_STORE_MESSAGE =
            "This invoice can no longer be accepted online. Please contact the store.";

    // Multipart contract (16G contract §9.4): exactly the form field expected_version_number and the file
    // part signature. No name, declaration or money part.
    private static final String SIGNATURE_PART = "signature";
    private static final String EXPECTED_VERSION_FIELD = "expected_version_number";
    private static final String SIGNATURE_MIME = "image/png";
    private static final String SIGNATURE_EXTENSION = "png";
    // Exactly 2 MB: size > this -> 400 SIGNATURE_INVALID; size == this is allowed.
    private static final long MAX_SIGNATURE_SIZE_BYTES = 2_097_152L;
    // The pdf query parameter (16G contract §9.5): the version the page shows, compared only.
    private static final String VERSION_NUMBER_PARAM = "version_number";

    private static final String PDF_MIME = "application/pdf";
    private static final String PDF_EXTENSION = "pdf";

    // Store notification display formats (the signed-PDF caption / quote notification patterns).
    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final Locale DISPLAY_LOCALE = Locale.ENGLISH;
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", DISPLAY_LOCALE);

    private final InvoiceTokenRepository invoiceTokenRepository;
    private final InvoiceRepository invoiceRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderCustomerRepository orderCustomerRepository;
    private final CustomerEmailValidator customerEmailValidator;
    private final BusinessRepository businessRepository;
    private final StoreRepository storeRepository;
    private final QuoteVersionRepository quoteVersionRepository;
    private final InvoicePdfGenerator invoicePdfGenerator;
    private final InvoicePdfModelAssembler invoicePdfModelAssembler;
    private final FileStorageService fileStorageService;
    private final SignedInvoiceEmailer signedInvoiceEmailer;
    private final InvoiceAcceptanceNotificationSender notificationSender;
    // Programmatic transactions: the lazy-expiry flip must COMMIT before a dead GET or a 410, and the
    // customer email and store notification must run strictly AFTER the signing commit - so this service
    // cannot be @Transactional (the PublicQuoteService / QuoteAcceptanceService precedent).
    private final TransactionTemplate transactionTemplate;
    // The one-snapshot read of the GET view and the pdf: read-only REPEATABLE_READ, no row locks, never
    // blocks a writer (the QuoteService.getWorkspace isolation, as an explicit boundary).
    private final TransactionTemplate snapshotTemplate;

    public PublicInvoiceService(InvoiceTokenRepository invoiceTokenRepository,
                                InvoiceRepository invoiceRepository,
                                SalesOrderRepository salesOrderRepository,
                                OrderCustomerRepository orderCustomerRepository,
                                CustomerEmailValidator customerEmailValidator,
                                BusinessRepository businessRepository,
                                StoreRepository storeRepository,
                                QuoteVersionRepository quoteVersionRepository,
                                InvoicePdfGenerator invoicePdfGenerator,
                                InvoicePdfModelAssembler invoicePdfModelAssembler,
                                FileStorageService fileStorageService,
                                SignedInvoiceEmailer signedInvoiceEmailer,
                                InvoiceAcceptanceNotificationSender notificationSender,
                                PlatformTransactionManager transactionManager) {
        this.invoiceTokenRepository = invoiceTokenRepository;
        this.invoiceRepository = invoiceRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.orderCustomerRepository = orderCustomerRepository;
        this.customerEmailValidator = customerEmailValidator;
        this.businessRepository = businessRepository;
        this.storeRepository = storeRepository;
        this.quoteVersionRepository = quoteVersionRepository;
        this.invoicePdfGenerator = invoicePdfGenerator;
        this.invoicePdfModelAssembler = invoicePdfModelAssembler;
        this.fileStorageService = fileStorageService;
        this.signedInvoiceEmailer = signedInvoiceEmailer;
        this.notificationSender = notificationSender;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        this.snapshotTemplate = snapshot;
    }

    // ------------------------------------------------------------------
    // GET /public/invoices/{token}
    // ------------------------------------------------------------------

    /**
     * Open the invoice behind {@code token} (16G contract §9.3): 200 {@code Invoice loaded.} with the
     * {@code ACTIVE} document, or the minimal dead-state payload for a found dead link; 404 for an unknown
     * or malformed token. Nothing is stamped on a read.
     */
    public ApiResponse<PublicInvoiceViewDto> getView(String token) {
        ResolvedInvoiceToken resolved = resolve(token);
        ViewRead read = readView(resolved);
        if (read.expiryDue()) {
            // The link's expiry fell due between the gate and the snapshot: run the gate again (it takes the
            // lock, flips the still-ACTIVE token and commits), then read the - now dead - state.
            resolved = resolve(token);
            read = readView(resolved);
            if (read.expiryDue()) {
                throw new IllegalStateException("Invoice link still ACTIVE past expiry after the gate flipped it, order "
                        + resolved.orderId());
            }
        }
        return ApiResponse.ok(read.view(), LOADED_MESSAGE);
    }

    /**
     * The one-snapshot GET read (read-only {@code REPEATABLE_READ}): the token re-read FIRST (it pins the
     * snapshot), then the order, the business, and - only for an {@code ACTIVE} token - the current
     * version and the invoice configuration, all from the same committed moment.
     */
    private ViewRead readView(ResolvedInvoiceToken resolved) {
        return snapshotTemplate.execute(status -> {
            InvoiceTokenRow token = invoiceTokenRepository.findById(resolved.invoiceTokenId())
                    .orElseThrow(() -> new IllegalStateException(
                            "invoice_token disappeared after the gate, order " + resolved.orderId()));
            SalesOrder order = salesOrderRepository.findById(token.orderId())
                    .orElseThrow(() -> new IllegalStateException(
                            "sales_order disappeared for an invoice link, order " + token.orderId()));
            Business business = businessRepository.findById(order.getBusinessId())
                    .orElseThrow(() -> new IllegalStateException(
                            "business disappeared for an invoice link, order " + token.orderId()));

            if (!TOKEN_ACTIVE.equals(token.status())) {
                // Dead (in this snapshot): state, business name and the per-state message only.
                return ViewRead.of(PublicInvoiceViewDto.dead(
                        token.status(), business.getName(), deadStateMessage(token.status())));
            }
            if (LocalDateTime.now().isAfter(token.expiresAt())) {
                return ViewRead.expiryIsDue();
            }

            InvoiceRow current = requireCurrentUnsignedInvoice(token.orderId());
            BusinessInvoiceConfigView config = businessRepository
                    .findInvoiceConfigByBusinessId(order.getBusinessId())
                    .orElse(null);
            return ViewRead.of(activeView(business, config, order, current, token));
        });
    }

    /**
     * The {@code ACTIVE} document (16G contract §9.3): the current version's stored words, dates and money
     * VERBATIM (nulls and whitespace kept; no live customer, address or terms fallback; {@code gst_amount}
     * = inc − ex of the version, never recomputed from live lines), the order's number and flooring type,
     * the presented token's {@code expires_at}, and the business presentation read live - the name, the
     * stored logo path verbatim, the accent, the ABN and direct-deposit details (blank-hidden, the public
     * quote page's treatment) and the Stripe link under the HTTPS-only rule.
     */
    private static PublicInvoiceViewDto activeView(Business business, BusinessInvoiceConfigView config,
                                                   SalesOrder order, InvoiceRow current, InvoiceTokenRow token) {
        return new PublicInvoiceViewDto(
                TOKEN_ACTIVE,
                business.getName(),
                business.getLogoPath(),
                blankToNull(business.getAccentColour()),
                blankToNull(config == null ? null : config.getAbn()),
                blankToNull(config == null ? null : config.getAccountName()),
                blankToNull(config == null ? null : config.getBankName()),
                blankToNull(config == null ? null : config.getBsb()),
                blankToNull(config == null ? null : config.getAccountNumber()),
                safeStripeLinkUrl(config == null ? null : config.getStripePaymentLinkUrl()),
                order.getOrderNumber(),
                order.getFlooringType(),
                current.versionNumber(),
                current.invoiceDate(),
                current.dueDate(),
                current.customerNameSnapshot(),
                current.customerAddressLine1Snapshot(),
                current.customerAddressLine2Snapshot(),
                current.detailsOfSaleSnapshot(),
                current.salePriceExGst(),
                current.salePriceIncGst().subtract(current.salePriceExGst()),
                current.salePriceIncGst(),
                current.totalPaid(),
                current.balanceDue(),
                current.termsSnapshot(),
                token.expiresAt(),
                null);
    }

    // ------------------------------------------------------------------
    // GET /public/invoices/{token}/pdf?version_number=N
    // ------------------------------------------------------------------

    /**
     * Stream the STORED PDF of the invoice version the page shows (16G contract §9.5), never regenerated:
     * token gate (404; committed lazy expiry; 410 for a dead link) → the {@code version_number} query
     * parameter (400) → one read-only snapshot of the token status (dead → 410), the current version
     * ({@code version_number} ≠ current → 409 {@code INVOICE_VERSION_STALE}) and its stored file, whose
     * bytes are read in the same snapshot. The value is only compared, so no other version - history
     * included - is reachable, and the bytes are always those of the version the 200 names. A file missing
     * on disk is a generic 500.
     */
    public PublicInvoicePdf downloadPdf(String token, String versionNumberRaw) {
        ResolvedInvoiceToken resolved = resolve(token);
        requireActiveState(resolved.state());
        long versionNumber = parseVersionNumber(versionNumberRaw);

        PdfRead read = readPdf(resolved, versionNumber);
        if (read.expiryDue()) {
            // The expiry fell due after the gate: the gate runs again (flip committed) and gives its 410.
            resolved = resolve(token);
            requireActiveState(resolved.state());
            read = readPdf(resolved, versionNumber);
            if (read.expiryDue()) {
                throw new IllegalStateException("Invoice link still ACTIVE past expiry after the gate flipped it, order "
                        + resolved.orderId());
            }
        }
        return read.pdf();
    }

    private PdfRead readPdf(ResolvedInvoiceToken resolved, long versionNumber) {
        return snapshotTemplate.execute(status -> {
            InvoiceTokenRow token = invoiceTokenRepository.findById(resolved.invoiceTokenId())
                    .orElseThrow(() -> new IllegalStateException(
                            "invoice_token disappeared after the gate, order " + resolved.orderId()));
            requireActiveState(token.status());
            if (LocalDateTime.now().isAfter(token.expiresAt())) {
                return PdfRead.expiryIsDue();
            }
            InvoiceRow current = invoiceRepository.findCurrentByOrderId(token.orderId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ACTIVE invoice link with no invoice, order " + token.orderId()));
            if (versionNumber != current.versionNumber()) {
                throw new ConflictException(ErrorCode.INVOICE_VERSION_STALE,
                        ErrorCode.INVOICE_VERSION_STALE.defaultMessage());
            }
            requireUnsigned(current);
            // The current version's stored file, read in the same snapshot as the version compared above,
            // so these bytes are that version's: the D.4 storage read (missing on disk -> 500).
            InvoiceFile file = invoiceRepository.findCurrentFileByOrderId(token.orderId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Current invoice has no stored_file row, order " + token.orderId()));
            byte[] bytes = fileStorageService.read(file.storagePath());
            return PdfRead.of(new PublicInvoicePdf(bytes, file.fileName()));
        });
    }

    /** Carrier for the public stored-PDF response (the stored {@code invoice-{order_number}-v{N}.pdf} name). */
    public record PublicInvoicePdf(byte[] bytes, String fileName) {
    }

    // ------------------------------------------------------------------
    // POST /public/invoices/{token}/accept
    // ------------------------------------------------------------------

    /**
     * Sign the invoice version the page shows (16G contract §9.4, §10; see the class javadoc for the
     * stages). 201 {@code {"state": "SIGNED"}} with {@code Invoice accepted.} whatever the post-commit email
     * and notification outcomes.
     */
    public ApiResponse<PublicInvoiceAcceptResultDto> accept(String token, MultipartHttpServletRequest request) {
        // 1. Token gate (own transaction, committed lazy expiry) and the dead-link 410 - before any body
        // validation, so a dead link with an invalid body still gets its 410.
        ResolvedInvoiceToken resolved = resolve(token);
        requireActiveState(resolved.state());

        // 2. Body validation: no database access, no lock.
        AcceptBody body = validateAcceptBody(request);

        // 3. One committing transaction under the order row lock.
        AcceptOutcome outcome = transactionTemplate.execute(status -> acceptUnderLock(resolved, body));
        if (outcome == null) {
            throw new IllegalStateException("Invoice acceptance transaction returned no outcome");
        }
        if (outcome.tokenMissing()) {
            // Token rows are never deleted - the unknown-token 404, defensively.
            throw tokenNotFound();
        }
        if (outcome.deadState() != null) {
            // The link died (or its expiry fell due) while this request waited for the lock; an expiry flip
            // is ALREADY COMMITTED. Translate the true state to its 410 now.
            requireActiveState(outcome.deadState());
            throw new IllegalStateException("Dead invoice link outcome did not map to a 410: " + outcome.deadState());
        }

        // 4. Strictly after commit: the customer's signed-PDF email and the store notification, attempted
        // independently - neither's failure, skip or stamp failure affects the other or the 201.
        emailCustomerQuietly(outcome.committed());
        notifyStoreQuietly(outcome.notification());
        return ApiResponse.ok(new PublicInvoiceAcceptResultDto(TOKEN_SIGNED), ACCEPTED_MESSAGE);
    }

    /**
     * The signing transaction body (inside {@code transactionTemplate.execute}): the order row lock FIRST,
     * then the authoritative re-reads and checks, in the 16G contract §9.4 order. Dead / newly-expired
     * outcomes are RETURNED (so an expiry flip commits); refusals are THROWN before any write (nothing to
     * keep); persistence failures are THROWN after writes (rollback + this request's file cleanup).
     */
    private AcceptOutcome acceptUnderLock(ResolvedInvoiceToken resolved, AcceptBody body) {
        long orderId = resolved.orderId();
        quoteVersionRepository.lockOrderRowForPublicTransition(orderId);

        // 1. The token, re-read under the lock (the gate's read may be stale after waiting).
        InvoiceTokenRow token = invoiceTokenRepository.findById(resolved.invoiceTokenId()).orElse(null);
        if (token == null) {
            return AcceptOutcome.missingToken();
        }
        if (!TOKEN_ACTIVE.equals(token.status())) {
            // A concurrent send, Rewrite, D.8, Path A or remote accept won the lock first: its true reason.
            return AcceptOutcome.dead(token.status());
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(token.expiresAt())) {
            // The expiry fell due while waiting: the guarded flip, COMMITTED by returning (an exception here
            // would roll it back) and translated to 410 after commit. No invoice row changes.
            if (!invoiceTokenRepository.expireActive(token.invoiceTokenId(), now)) {
                throw new IllegalStateException("invoice_token was not ACTIVE under the order lock, order " + orderId);
            }
            return AcceptOutcome.dead(TOKEN_EXPIRED);
        }

        // 2. The current version. Under an ACTIVE link it always exists (a link is sent only for one).
        InvoiceRow current = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("ACTIVE invoice link with no invoice, order " + orderId));

        // 3. The authoritative stale comparison: the value is only compared, never used to select a row.
        if (body.expectedVersionNumber() != current.versionNumber()) {
            throw new ConflictException(ErrorCode.INVOICE_VERSION_STALE,
                    ErrorCode.INVOICE_VERSION_STALE.defaultMessage());
        }

        // 4. Invariant (§7.6): an ACTIVE link never sits over a signed current version.
        requireUnsigned(current);

        // 5. The accepted name is the version's FROZEN customer_name_snapshot, verbatim and in full; null or
        // blank is the only business-rule refusal (no email, quote, LAID or CANCELLED gate here).
        String acceptedCustomerName = current.customerNameSnapshot();
        if (acceptedCustomerName == null || acceptedCustomerName.isBlank()) {
            throw new BusinessRuleException(ErrorCode.ACCEPTED_CUSTOMER_NAME_REQUIRED, PUBLIC_CONTACT_STORE_MESSAGE);
        }

        // 6. Persist - the D.8 signing version (16G contract §10.1), every check above done before the first
        // file write.
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("sales_order disappeared under its lock: " + orderId));
        Business business = businessRepository.findById(order.getBusinessId())
                .orElseThrow(() -> new IllegalStateException("business disappeared for order " + orderId));
        // The post-commit email's recipient, read under the lock (D.8 parity): the trimmed saved customer
        // email, or null when it is missing or invalid (the send is then skipped - there is no email gate).
        OrderCustomer customer = orderCustomerRepository.findByOrderId(orderId).orElse(null);
        String recipientEmail = customerEmailValidator.hasValidCustomerEmail(customer)
                ? customer.getEmail().trim()
                : null;

        int versionNumber = current.versionNumber() + 1;
        LocalDateTime acceptedAt = now;
        // The signing version keeps the current version's invoice_date (the invoice the customer reviewed
        // and the stale check matched); accepted_at is the signing time (16G contract §4.2, §10.1).
        String signatureFileName = "signature-" + order.getOrderNumber() + "-v" + versionNumber
                + "." + SIGNATURE_EXTENSION;
        byte[] signaturePng = body.signaturePng();
        String signaturePath = fileStorageService.store(signaturePng, order.getBusinessId(), orderId, SIGNATURE_EXTENSION);
        deleteFileOnRollback(signaturePath);
        try {
            long signatureFileId = invoiceRepository.insertStoredFile(
                    signatureFileName, signaturePath, SIGNATURE_MIME, signaturePng.length);

            // The signed PDF: the current version carried forward unchanged - its words (Invoice To name,
            // billing lines, terms; nulls too), money and dates - with the acceptance block filled. The
            // existing invoice template and caption; no live re-read.
            String pdfFileName = "invoice-" + order.getOrderNumber() + "-v" + versionNumber + "." + PDF_EXTENSION;
            byte[] pdfBytes = invoicePdfGenerator.render(invoicePdfModelAssembler.assemble(new Inputs(
                    business,
                    order,
                    versionNumber,
                    current.invoiceDate(),
                    current.dueDate(),
                    current.customerNameSnapshot(),
                    current.customerAddressLine1Snapshot(),
                    current.customerAddressLine2Snapshot(),
                    current.detailsOfSaleSnapshot(),
                    current.salePriceIncGst(),
                    current.totalPaid(),
                    current.balanceDue(),
                    acceptedAt,
                    acceptedCustomerName,
                    signaturePng,
                    InvoiceTermsSelection.forInvoice(current.sourceQuoteVersionId(), current.termsSnapshot()))));

            String pdfPath = fileStorageService.store(pdfBytes, order.getBusinessId(), orderId, PDF_EXTENSION);
            deleteFileOnRollback(pdfPath);
            try {
                long pdfFileId = invoiceRepository.insertStoredFile(pdfFileName, pdfPath, PDF_MIME, pdfBytes.length);
                // created_by_user_id = the staff member who sent the link (16G contract §10.2).
                InvoiceRow row = invoiceRepository.insertInvoice(
                        orderId, versionNumber, current.invoiceDate(), current.dueDate(),
                        current.detailsOfSaleSnapshot(), current.salePriceExGst(), current.salePriceIncGst(),
                        current.totalPaid(), current.balanceDue(),
                        pdfFileId, token.sentByUserId(),
                        acceptedAt, acceptedCustomerName, signatureFileId, null,
                        current.sourceQuoteVersionId(), current.termsSnapshot(),
                        current.customerNameSnapshot(), current.customerAddressLine1Snapshot(),
                        current.customerAddressLine2Snapshot());

                // §11.1 mirror: the new current version starts unemailed; the post-commit stamp sets both.
                invoiceRepository.updateSalesOrderLastEmailedAt(orderId, row.lastEmailedAt());

                // The presented link dies as SIGNED at the signing time (guarded; exactly this token).
                if (invoiceTokenRepository.killActiveById(token.invoiceTokenId(), TOKEN_SIGNED, acceptedAt) != 1) {
                    throw new IllegalStateException("invoice_token was not ACTIVE when signing, order " + orderId);
                }

                // D5(c), extended to remote signing (16G contract §10.5): the exact D.8 transition - an ISSUED
                // quote version and its ACTIVE quote token become CANCELLED; an ACCEPTED quote is untouched.
                OrderInvoiceService.cancelActiveQuoteLink(quoteVersionRepository, orderId, acceptedAt);

                // Everything the two post-commit side effects need, captured in THIS transaction.
                NotificationPlan notification = notificationPlan(order, versionNumber, acceptedCustomerName,
                        acceptedAt, current.salePriceIncGst());
                CommittedSignedInvoice committed = new CommittedSignedInvoice(row, order.getOrderNumber(),
                        business.getName(), order.getBusinessId(), order.getStoreId(),
                        recipientEmail, pdfBytes, pdfFileName);
                return AcceptOutcome.signed(committed, notification);
            } catch (RuntimeException ex) {
                fileStorageService.deleteQuietly(pdfPath);
                throw ex;
            }
        } catch (RuntimeException ex) {
            fileStorageService.deleteQuietly(signaturePath);
            throw ex;
        }
    }

    // ------------------------------------------------------------------
    // Body validation (application level; no DB, no lock)
    // ------------------------------------------------------------------

    /**
     * The accept body (16G contract §9.4 step 2): first ONE 400 {@code VALIDATION_FAILED} listing every
     * offending part - an unknown file part or form field, and every URL query-string parameter
     * ({@code Not allowed.}; only the multipart form field is the carrier, the D.8 form/query distinction),
     * a repeated {@code signature} or {@code expected_version_number} ({@code Must appear at most once.}), a
     * missing {@code expected_version_number} ({@code Required.}) or one that is not a positive integer,
     * blank included ({@code Must be a positive integer.}); then a missing or empty {@code signature} → 422
     * {@code SIGNATURE_REQUIRED}; then a declared type other than {@code image/png}, more than 2,097,152
     * bytes or a PNG outside the shared rules → 400 {@code SIGNATURE_INVALID} (default messages). Returns
     * the expected version and the SERVER-NORMALISED signature (the only bytes stored and embedded).
     */
    private static AcceptBody validateAcceptBody(MultipartHttpServletRequest request) {
        List<ErrorDetail> errors = new ArrayList<>();
        for (Iterator<String> fileNames = request.getFileNames(); fileNames.hasNext();) {
            String name = fileNames.next();
            if (!SIGNATURE_PART.equals(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
        Map<String, List<String>> queryParameters = MultipartFormFields.queryStringParameters(request);
        for (Enumeration<String> paramNames = request.getParameterNames(); paramNames.hasMoreElements();) {
            String name = paramNames.nextElement();
            if (!EXPECTED_VERSION_FIELD.equals(name) || queryParameters.containsKey(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
        if (request.getFiles(SIGNATURE_PART).size() > 1) {
            errors.add(new ErrorDetail(null, SIGNATURE_PART, "Must appear at most once."));
        }
        List<String> versionValues = MultipartFormFields.formFieldValues(request, EXPECTED_VERSION_FIELD);
        Long expectedVersionNumber = null;
        if (versionValues.isEmpty()) {
            errors.add(new ErrorDetail(null, EXPECTED_VERSION_FIELD, "Required."));
        } else if (versionValues.size() > 1) {
            errors.add(new ErrorDetail(null, EXPECTED_VERSION_FIELD, "Must appear at most once."));
        } else {
            expectedVersionNumber = positiveLongOrNull(versionValues.get(0));
            if (expectedVersionNumber == null) {
                errors.add(new ErrorDetail(null, EXPECTED_VERSION_FIELD, "Must be a positive integer."));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }

        MultipartFile signature = request.getFile(SIGNATURE_PART);
        if (signature == null || signature.isEmpty()) {
            throw new BusinessRuleException(ErrorCode.SIGNATURE_REQUIRED, ErrorCode.SIGNATURE_REQUIRED.defaultMessage());
        }
        if (!SIGNATURE_MIME.equals(signature.getContentType())
                || signature.getSize() > MAX_SIGNATURE_SIZE_BYTES) {
            throw signatureInvalid();
        }
        byte[] bytes;
        try {
            bytes = signature.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded invoice signature", e);
        }
        byte[] normalised = SignaturePngNormaliser.normalise(bytes);
        if (normalised == null) {
            throw signatureInvalid();
        }
        return new AcceptBody(expectedVersionNumber, normalised);
    }

    /** The pdf {@code version_number}: missing → {@code Required.}; not a positive integer → 400. */
    private static long parseVersionNumber(String raw) {
        if (raw == null) {
            throw fieldValidation(VERSION_NUMBER_PARAM, "Required.");
        }
        Long parsed = positiveLongOrNull(raw);
        if (parsed == null) {
            throw fieldValidation(VERSION_NUMBER_PARAM, "Must be a positive integer.");
        }
        return parsed;
    }

    /** A positive integer (the D.8 {@code expected_version_number} rule), else null; blank included. */
    private static Long positiveLongOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(raw);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static ValidationException fieldValidation(String field, String message) {
        return new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new ErrorDetail(null, field, message)));
    }

    private static FileUploadException signatureInvalid() {
        return new FileUploadException(ErrorCode.SIGNATURE_INVALID, ErrorCode.SIGNATURE_INVALID.defaultMessage());
    }

    // ------------------------------------------------------------------
    // Token gate (16G contract §7.3, §7.7, §9.2)
    // ------------------------------------------------------------------

    /**
     * Resolve the presented token to its effective state, in its OWN committed transaction so a lazy-expiry
     * flip survives any 404 / 410 the caller throws afterwards (the misses RETURN null rather than throwing
     * inside the callback). The shape gate and the unknown-hash miss give the identical 404.
     */
    ResolvedInvoiceToken resolve(String token) {
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            throw tokenNotFound();
        }
        String presentedHash = TokenHash.sha256Hex(token);

        ResolvedInvoiceToken resolved = transactionTemplate.execute(status -> {
            InvoiceTokenRow row = invoiceTokenRepository.findByTokenHash(presentedHash).orElse(null);
            // Constant-time re-verification of the stored hash; the keyed lookup already matched, so a
            // mismatch is defensive-only - treated as not found.
            if (row == null || !MessageDigest.isEqual(
                    presentedHash.getBytes(StandardCharsets.UTF_8),
                    row.tokenHash().getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            if (TOKEN_ACTIVE.equals(row.status()) && LocalDateTime.now().isAfter(row.expiresAt())) {
                return expireUnderLock(row);
            }
            return new ResolvedInvoiceToken(row.invoiceTokenId(), row.orderId(), row.status());
        });
        if (resolved == null) {
            throw tokenNotFound();
        }
        return resolved;
    }

    /**
     * Lazy expiry inside the gate's transaction: take the order row lock (the lock every protected token
     * transition holds), re-read the token by id and flip it to {@code EXPIRED} only if it is STILL
     * {@code ACTIVE} and STILL past expiry. A token that a concurrent writer killed first is reported with
     * that TRUE reason, never as {@code EXPIRED}. The time is compared again under the lock with one fresh
     * reading (also the flip's {@code dead_at}), as {@link #acceptUnderLock} does: the gate's comparison
     * before the lock can be stale after waiting, because {@code LocalDateTime.now()} is wall-clock time and
     * can step back (an NTP step, the daylight-saving fall-back). A token no longer past expiry is returned
     * {@code ACTIVE}, unwritten.
     */
    private ResolvedInvoiceToken expireUnderLock(InvoiceTokenRow seen) {
        quoteVersionRepository.lockOrderRowForPublicTransition(seen.orderId());
        InvoiceTokenRow locked = invoiceTokenRepository.findById(seen.invoiceTokenId()).orElse(null);
        if (locked == null) {
            // Rows are never deleted - FK-impossible; treated as not found defensively.
            return null;
        }
        if (!TOKEN_ACTIVE.equals(locked.status())) {
            return new ResolvedInvoiceToken(locked.invoiceTokenId(), locked.orderId(), locked.status());
        }
        LocalDateTime now = LocalDateTime.now();
        if (!now.isAfter(locked.expiresAt())) {
            // No longer past expiry at the locked re-read (the clock stepped back while this request waited):
            // the token stays ACTIVE and nothing is written.
            return new ResolvedInvoiceToken(locked.invoiceTokenId(), locked.orderId(), TOKEN_ACTIVE);
        }
        if (!invoiceTokenRepository.expireActive(locked.invoiceTokenId(), now)) {
            throw new IllegalStateException("invoice_token was not ACTIVE under the order lock, order " + locked.orderId());
        }
        return new ResolvedInvoiceToken(locked.invoiceTokenId(), locked.orderId(), TOKEN_EXPIRED);
    }

    /** The accept / pdf action gate: any dead state → its 410 {@code INVOICE_LINK_*} (ACTIVE passes). */
    private static void requireActiveState(String state) {
        ErrorCode code = switch (state) {
            case TOKEN_ACTIVE -> null;
            case TOKEN_REPLACED -> ErrorCode.INVOICE_LINK_REPLACED;
            case TOKEN_REWRITTEN -> ErrorCode.INVOICE_LINK_REWRITTEN;
            case TOKEN_SIGNED -> ErrorCode.INVOICE_LINK_SIGNED;
            case TOKEN_EXPIRED -> ErrorCode.INVOICE_LINK_EXPIRED;
            default -> throw new IllegalStateException("Unknown invoice_token status: " + state);
        };
        if (code != null) {
            throw new GoneException(code, code.defaultMessage());
        }
    }

    /** The per-state message of a dead link on the 200 GET (the same text as its 410). */
    private static String deadStateMessage(String state) {
        return switch (state) {
            case TOKEN_REPLACED -> ErrorCode.INVOICE_LINK_REPLACED.defaultMessage();
            case TOKEN_REWRITTEN -> ErrorCode.INVOICE_LINK_REWRITTEN.defaultMessage();
            case TOKEN_SIGNED -> ErrorCode.INVOICE_LINK_SIGNED.defaultMessage();
            case TOKEN_EXPIRED -> ErrorCode.INVOICE_LINK_EXPIRED.defaultMessage();
            default -> throw new IllegalStateException("Not a dead invoice_token status: " + state);
        };
    }

    private static NotFoundException tokenNotFound() {
        return new NotFoundException(ErrorCode.INVOICE_TOKEN_NOT_FOUND, ErrorCode.INVOICE_TOKEN_NOT_FOUND.defaultMessage());
    }

    /** The current version under an ACTIVE link: it exists, and it is unsigned (the §7.6 invariant). */
    private InvoiceRow requireCurrentUnsignedInvoice(long orderId) {
        InvoiceRow current = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("ACTIVE invoice link with no invoice, order " + orderId));
        requireUnsigned(current);
        return current;
    }

    /**
     * Never present a signed version as signable (16G contract §7.6): an {@code ACTIVE} link over a signed
     * current version is an invariant breach - a generic 500, no content and nothing written.
     */
    private static void requireUnsigned(InvoiceRow current) {
        if (current.acceptedAt() != null) {
            throw new IllegalStateException("ACTIVE invoice link over a signed current invoice version, order "
                    + current.orderId() + " v" + current.versionNumber());
        }
    }

    // ------------------------------------------------------------------
    // Post-commit side effects (16G contract §10.3, §10.4)
    // ------------------------------------------------------------------

    /**
     * The customer's signed-PDF email of the appended version (the shared D.8 / Path A path; skip, failure
     * and stamp failure are all non-fatal inside it). Guarded once more so that nothing it could raise can
     * prevent the store notification or turn the committed signing into an error.
     */
    private void emailCustomerQuietly(CommittedSignedInvoice committed) {
        try {
            signedInvoiceEmailer.emailAfterCommit(log, committed.row().orderId(), committed, true);
        } catch (RuntimeException ex) {
            log.warn("Signed invoice email failed unexpectedly for order {} invoice v{} (non-fatal; the signed "
                    + "invoice stands)", committed.row().orderId(), committed.row().versionNumber());
        }
    }

    /**
     * Resolve the store notification inside the signing transaction (16G contract §10.4): recipient = the
     * order's OWN store {@code email}, trimmed (blank → no recipient; never a customer, salesperson or
     * business fallback); subject {@code Invoice accepted: {order_number}}; a text body with the order
     * number, the signed version, the accepted name and time and the inc-GST total - no attachment, link,
     * token, signature, storage reference, cost or GP.
     */
    private NotificationPlan notificationPlan(SalesOrder order, int signedVersionNumber, String acceptedCustomerName,
                                              LocalDateTime acceptedAt, BigDecimal salePriceIncGst) {
        Store store = storeRepository.findByStoreIdAndBusinessId(order.getStoreId(), order.getBusinessId())
                .orElse(null);
        String recipient = store == null ? null : blankToNull(store.getEmail());
        String subject = "Invoice accepted: " + order.getOrderNumber();
        String body = "Invoice " + order.getOrderNumber() + " (version " + signedVersionNumber
                + ") was accepted online by " + acceptedCustomerName
                + " on " + DISPLAY_DATE_TIME.format(acceptedAt) + ".\n\n"
                + "Invoice total (inc GST): " + displayMoney(salePriceIncGst) + ".\n\n"
                + "The signed invoice is available in the sales portal.";
        return new NotificationPlan(recipient, subject, body, order.getOrderId(), signedVersionNumber);
    }

    /**
     * Deliver the store notification AFTER the signing commit. A blank store email skips with a WARN; any
     * sender failure is caught and WARN-logged. Never a marker, never the invoice email stamps. Logs carry
     * ONLY the order id and invoice version - never the recipient, body, exception or its class.
     */
    private void notifyStoreQuietly(NotificationPlan plan) {
        if (plan.recipient() == null) {
            log.warn("Invoice acceptance store notification skipped: no store email for the order's store "
                    + "(order {} invoice v{}; signing persisted)", plan.orderId(), plan.versionNumber());
            return;
        }
        try {
            notificationSender.send(new InvoiceAcceptanceNotificationRequest(
                    plan.recipient(), plan.subject(), plan.body(), plan.orderId(), plan.versionNumber()));
        } catch (RuntimeException ex) {
            log.warn("Invoice acceptance store notification failed (order {} invoice v{}; signing persisted)",
                    plan.orderId(), plan.versionNumber());
        }
    }

    private static String displayMoney(BigDecimal value) {
        BigDecimal scaled = (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, ROUNDING);
        String sign = scaled.signum() < 0 ? "-" : "";
        return sign + "$" + String.format(DISPLAY_LOCALE, "%,.2f", scaled.abs());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * The public quote page's Stripe-link rule ({@code PublicQuoteService.safeStripeLinkUrl}, duplicated):
     * blank → null; anything that does not parse as an ABSOLUTE {@code https:} URL → null. Never rewrites
     * the value - it is returned trimmed or suppressed, so a mis-configured link can never render as a
     * clickable non-HTTPS (or {@code javascript:}) anchor on the public page.
     */
    private static String safeStripeLinkUrl(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return "https".equalsIgnoreCase(new java.net.URI(trimmed).getScheme()) ? trimmed : null;
        } catch (java.net.URISyntaxException ex) {
            return null;
        }
    }

    /**
     * Delete a just-written file IFF the surrounding transaction does NOT commit (the
     * {@code OrderInvoiceService} rollback-cleanup pattern): files are written before their
     * {@code stored_file} rows, so a commit-time rollback would otherwise orphan them. Only this request's
     * files are registered; every older PDF and signature is never touched.
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

    /** The resolved token: its id, its order and its effective state (post lazy expiry). Never serialized. */
    record ResolvedInvoiceToken(long invoiceTokenId, long orderId, String state) {
    }

    /** The validated accept body: the expected version (compared only) and the normalised signature. */
    private record AcceptBody(long expectedVersionNumber, byte[] signaturePng) {
    }

    /** The store notification resolved inside the transaction, delivered after commit. Never serialized. */
    private record NotificationPlan(String recipient, String subject, String body,
                                    long orderId, int versionNumber) {
    }

    /**
     * The signing transaction's outcome: SIGNED (with the two post-commit plans), a dead link state found
     * under the lock (translated to its 410 AFTER commit so an expiry flip persists), or the defensive
     * missing-token case. Never serialized.
     */
    private record AcceptOutcome(CommittedSignedInvoice committed, NotificationPlan notification,
                                 String deadState, boolean tokenMissing) {

        static AcceptOutcome signed(CommittedSignedInvoice committed, NotificationPlan notification) {
            return new AcceptOutcome(committed, notification, null, false);
        }

        static AcceptOutcome dead(String state) {
            return new AcceptOutcome(null, null, state, false);
        }

        static AcceptOutcome missingToken() {
            return new AcceptOutcome(null, null, null, true);
        }
    }

    /** A GET snapshot: the view, or "the expiry fell due after the gate" (re-run the gate). */
    private record ViewRead(PublicInvoiceViewDto view, boolean expiryDue) {

        static ViewRead of(PublicInvoiceViewDto view) {
            return new ViewRead(view, false);
        }

        static ViewRead expiryIsDue() {
            return new ViewRead(null, true);
        }
    }

    /** A pdf snapshot: the stored PDF, or "the expiry fell due after the gate" (re-run the gate). */
    private record PdfRead(PublicInvoicePdf pdf, boolean expiryDue) {

        static PdfRead of(PublicInvoicePdf pdf) {
            return new PdfRead(pdf, false);
        }

        static PdfRead expiryIsDue() {
            return new PdfRead(null, true);
        }
    }
}
