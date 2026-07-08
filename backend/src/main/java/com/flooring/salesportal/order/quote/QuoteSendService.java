package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.email.QuoteEmailException;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailSender;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ConflictException;
import com.flooring.salesportal.common.error.EmailSendException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.SmsSendException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.common.sms.SmsException;
import com.flooring.salesportal.common.sms.SmsRequest;
import com.flooring.salesportal.common.sms.SmsSender;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.CustomerEmailValidator;
import com.flooring.salesportal.order.CustomerMobileValidator;
import com.flooring.salesportal.order.InvoiceTermsSanitizer;
import com.flooring.salesportal.order.OrderChargeLineReadRepository;
import com.flooring.salesportal.order.OrderCustomer;
import com.flooring.salesportal.order.OrderCustomerRepository;
import com.flooring.salesportal.order.OrderProductLineRepository;
import com.flooring.salesportal.order.SalesOrder;
import com.flooring.salesportal.order.SalesOrderRepository;
import com.flooring.salesportal.order.financial.LineFinancials;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteFile;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionLineRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionRow;
import com.flooring.salesportal.order.quote.dto.QuoteIssuedSummaryDto;
import com.flooring.salesportal.tenant.BusinessInvoiceConfigView;
import com.flooring.salesportal.tenant.BusinessRepository;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 16E-A — quote delivery: issue/resend by email or SMS, cancel, and the stored issued-PDF
 * download (contract §5/§7.1/§8/§9). Sits beside {@link QuoteService} (draft layer) and owns the
 * append-only issued layer ({@code quote_version} / {@code quote_version_line} /
 * {@code quote_token}) via {@link QuoteVersionRepository}.
 *
 * <p><b>Send choreography (locked §7.1 send-failure rule; the {@code OrderInvoiceService}
 * TransactionTemplate pattern).</b> The send methods are deliberately NOT {@code @Transactional}:
 * <ol>
 *   <li><b>Transaction 1</b> ({@link #prepareSend}, order held {@code FOR UPDATE} from its first
 *       statement): gates → recipient gate → draft load → live below-cost re-check →
 *       changed-detection → persist (new version + snapshot lines + stored issued PDF, or reuse
 *       the unchanged issued version + its stored PDF) → token transitions + fresh {@code ACTIVE}
 *       token mint → pre-delivery ATTEMPT markers ({@code sent_channel} / {@code first_sent_at} /
 *       {@code last_sent_at}). Commit. Everything here is durable regardless of delivery.</li>
 *   <li><b>Delivery</b> strictly after commit. A provider failure → 502
 *       ({@code EMAIL_SEND_FAILED} / {@code SMS_SEND_FAILED}) and the issued version / PDF / token
 *       / attempt markers are all KEPT — the salesperson simply resends (the unchanged draft then
 *       reuses the same version with a fresh token).</li>
 *   <li><b>Transaction 2</b> (email success only): stamp the success-only
 *       {@code quote_version.last_emailed_at} in place ({@link #stampEmailSuccess}; mirrors the
 *       invoice stamp). An SMS send never touches {@code last_emailed_at}. Neither path EVER
 *       touches {@code sales_order.last_emailed_at} — that dashboard mirror is invoice-only.</li>
 * </ol>
 *
 * <p><b>Changed-detection (locked).</b> Content comparison only — never {@code updated_at} /
 * {@code created_at} / any timestamp (autosave refreshes {@code updated_at} on no-op resaves). See
 * {@link #draftChangedSinceIssue} for the locked field order; a non-itemised draft compares header
 * fields only, so dormant retained itemised rows can never force a new version.
 *
 * <p><b>Dormant-row guard.</b> The issued line set is derived in exactly one place
 * ({@link QuoteIssueSnapshot#of} — mode-scoped), so a non-itemised issue structurally snapshots
 * ZERO {@code quote_version_line} rows and its PDF renders the single-amount presentation.
 *
 * <p><b>Token security (§8).</b> 32 CSPRNG bytes → URL-safe Base64 plaintext (held in method scope
 * only; delivered inside the message body's public link and NEVER persisted or serialized in any
 * protected response); only its SHA-256 hex hash is stored. One {@code ACTIVE} token per order is
 * maintained by doing every version/token transition inside the order-locked transaction: only the
 * single ISSUED version (V16 partial unique index) ever holds an {@code ACTIVE} token (V16
 * per-version partial unique index), and whenever a version leaves ISSUED its token is killed in
 * the same transaction.
 */
@Service
public class QuoteSendService {

    private static final Logger log = LoggerFactory.getLogger(QuoteSendService.class);

    private static final String STATUS_LAID = "LAID";
    private static final String FLOORING_SOFT = "SOFT";

    private static final String CHANNEL_EMAIL = "EMAIL";
    private static final String CHANNEL_SMS = "SMS";

    private static final String VERSION_SUPERSEDED = "SUPERSEDED";
    private static final String VERSION_CANCELLED = "CANCELLED";
    private static final String TOKEN_REPLACED = "REPLACED";
    private static final String TOKEN_SUPERSEDED = "SUPERSEDED";
    private static final String TOKEN_CANCELLED = "CANCELLED";

    private static final String PDF_MIME = "application/pdf";
    private static final String PDF_EXTENSION = "pdf";

    private static final String TYPE_ISSUED = "issued";
    private static final String TYPE_ACCEPTED = "accepted";

    // Contract §8: token is ≥32 random bytes, URL-safe, expires 7 days after send.
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_TTL_DAYS = 7;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    // Locked 16E-A response messages.
    private static final String SENT_EMAIL_MESSAGE = "Quote sent by email.";
    private static final String SENT_SMS_MESSAGE = "Quote sent by SMS.";
    private static final String CANCELLED_MESSAGE = "Quote cancelled.";

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderCustomerRepository orderCustomerRepository;
    private final CustomerEmailValidator customerEmailValidator;
    private final CustomerMobileValidator customerMobileValidator;
    private final OrderProductLineRepository orderProductLineRepository;
    private final OrderChargeLineReadRepository orderChargeLineReadRepository;
    private final QuoteDraftRepository quoteDraftRepository;
    private final QuoteDraftLineRepository quoteDraftLineRepository;
    private final QuoteDraftCalculator quoteDraftCalculator;
    private final QuoteVersionRepository quoteVersionRepository;
    private final BusinessRepository businessRepository;
    private final InvoiceTermsSanitizer termsSanitizer;
    private final QuotePdfModelAssembler quotePdfModelAssembler;
    private final QuotePdfGenerator quotePdfGenerator;
    private final FileStorageService fileStorageService;
    private final QuoteEmailSender quoteEmailSender;
    private final SmsSender smsSender;
    private final ObjectMapper objectMapper;
    // Programmatic transactions: delivery must run strictly AFTER the issue commit, and the
    // email-success stamp in its own follow-up transaction — so the send methods cannot be
    // @Transactional (the OrderInvoiceService accept/resend precedent).
    private final TransactionTemplate transactionTemplate;

    public QuoteSendService(RequestContextGuard requestContextGuard,
                            SalesOrderRepository salesOrderRepository,
                            OrderCustomerRepository orderCustomerRepository,
                            CustomerEmailValidator customerEmailValidator,
                            CustomerMobileValidator customerMobileValidator,
                            OrderProductLineRepository orderProductLineRepository,
                            OrderChargeLineReadRepository orderChargeLineReadRepository,
                            QuoteDraftRepository quoteDraftRepository,
                            QuoteDraftLineRepository quoteDraftLineRepository,
                            QuoteDraftCalculator quoteDraftCalculator,
                            QuoteVersionRepository quoteVersionRepository,
                            BusinessRepository businessRepository,
                            InvoiceTermsSanitizer termsSanitizer,
                            QuotePdfModelAssembler quotePdfModelAssembler,
                            QuotePdfGenerator quotePdfGenerator,
                            FileStorageService fileStorageService,
                            QuoteEmailSender quoteEmailSender,
                            SmsSender smsSender,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.orderCustomerRepository = orderCustomerRepository;
        this.customerEmailValidator = customerEmailValidator;
        this.customerMobileValidator = customerMobileValidator;
        this.orderProductLineRepository = orderProductLineRepository;
        this.orderChargeLineReadRepository = orderChargeLineReadRepository;
        this.quoteDraftRepository = quoteDraftRepository;
        this.quoteDraftLineRepository = quoteDraftLineRepository;
        this.quoteDraftCalculator = quoteDraftCalculator;
        this.quoteVersionRepository = quoteVersionRepository;
        this.businessRepository = businessRepository;
        this.termsSanitizer = termsSanitizer;
        this.quotePdfModelAssembler = quotePdfModelAssembler;
        this.quotePdfGenerator = quotePdfGenerator;
        this.fileStorageService = fileStorageService;
        this.quoteEmailSender = quoteEmailSender;
        this.smsSender = smsSender;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // POST /orders/{orderId}/quote/send-email
    // ------------------------------------------------------------------

    /**
     * Issue (or resend) the quote and email it: issued PDF attachment + public link + plain-text
     * body (contract §7.1). Provider failure → 502 {@code EMAIL_SEND_FAILED} with the issued
     * version / PDF / token / attempt markers KEPT. On success the success-only
     * {@code last_emailed_at} is stamped in a short follow-up transaction. 201 issued summary.
     */
    public ApiResponse<QuoteIssuedSummaryDto> sendEmail(String slug,
                                                        String orderIdRaw,
                                                        String body,
                                                        HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parseOrderId(orderIdRaw);

        PreparedSend prepared = transactionTemplate.execute(status ->
                prepareSend(ctx, slug, orderId, body, CHANNEL_EMAIL));

        try {
            quoteEmailSender.send(new QuoteEmailRequest(
                    prepared.recipient(),
                    emailSubject(ctx.business().getName()),
                    emailBody(prepared.orderNumber(), ctx.business().getName(), prepared.publicLink()),
                    prepared.pdfBytes(),
                    prepared.pdfFileName(),
                    orderId,
                    prepared.row().versionNumber()));
        } catch (QuoteEmailException ex) {
            // The send is the operation → 502. The issued version/PDF/token and the attempt
            // markers persisted in transaction 1 are all KEPT (locked §7.1 send-failure rule);
            // last_emailed_at is deliberately NOT stamped.
            log.warn("Quote email send failed for order {} quote v{} (issued version/PDF/token kept)",
                    orderId, prepared.row().versionNumber(), ex);
            throw new EmailSendException("The quote could not be emailed. Please try again.");
        }

        QuoteIssuedSummaryDto dto = transactionTemplate.execute(status ->
                stampEmailSuccess(ctx, orderId, prepared.row().quoteVersionId(), LocalDateTime.now()));
        return ApiResponse.ok(dto, SENT_EMAIL_MESSAGE);
    }

    // ------------------------------------------------------------------
    // POST /orders/{orderId}/quote/send-sms
    // ------------------------------------------------------------------

    /**
     * Issue (or resend) the quote and SMS the public link ONLY — no PDF attachment (contract §9);
     * identical issue/resend/token logic to {@link #sendEmail}. Provider failure → 502
     * {@code SMS_SEND_FAILED} with everything persisted KEPT. An SMS send never stamps
     * {@code last_emailed_at} (email-only, success-only marker). 201 issued summary.
     */
    public ApiResponse<QuoteIssuedSummaryDto> sendSms(String slug,
                                                      String orderIdRaw,
                                                      String body,
                                                      HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parseOrderId(orderIdRaw);

        PreparedSend prepared = transactionTemplate.execute(status ->
                prepareSend(ctx, slug, orderId, body, CHANNEL_SMS));

        try {
            smsSender.send(new SmsRequest(
                    prepared.recipient(),
                    smsText(prepared.orderNumber(), ctx.business().getName(), prepared.publicLink()),
                    orderId,
                    prepared.row().versionNumber()));
        } catch (SmsException ex) {
            log.warn("Quote SMS send failed for order {} quote v{} (issued version/PDF/token kept)",
                    orderId, prepared.row().versionNumber(), ex);
            throw new SmsSendException(ErrorCode.SMS_SEND_FAILED.defaultMessage());
        }

        // No follow-up stamp on the SMS path: the attempt markers were already persisted in
        // transaction 1 and last_emailed_at is email-only. The summary basis was re-read after
        // those stamps, so it is the accurate final state.
        return ApiResponse.ok(
                QuoteIssuedSummaryDto.from(prepared.row(), prepared.tokenExpiresAt()), SENT_SMS_MESSAGE);
    }

    // ------------------------------------------------------------------
    // POST /orders/{orderId}/quote/cancel
    // ------------------------------------------------------------------

    /**
     * Cancel the active issued quote (contract §7.1): version → {@code CANCELLED}, its ACTIVE
     * token → {@code CANCELLED} (kept for messaging; link dead). No active ISSUED version → 422
     * {@code QUOTE_NOT_ISSUED}; an already-accepted quote (defensive — unreachable via the 16E-A
     * API, valid data once 16F lands) → 409 {@code QUOTE_ALREADY_ACCEPTED}. <b>ALLOWED when LAID</b>
     * as the contract's narrow exception: its only effect is killing a public link — it is not an
     * order mutation — so {@code requireNotLaid} is deliberately not called. 200 cancelled summary.
     */
    @Transactional
    public ApiResponse<QuoteIssuedSummaryDto> cancel(String slug,
                                                     String orderIdRaw,
                                                     String body,
                                                     HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parseOrderId(orderIdRaw);

        // FOR UPDATE: cancel is a version/token transition and must serialise with sends.
        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        rejectNonEmptyBody(body);

        Optional<QuoteVersionRow> issuedOpt = quoteVersionRepository.findIssuedByOrderId(orderId);
        if (issuedOpt.isEmpty()) {
            if (quoteVersionRepository.existsAcceptedByOrderId(orderId)) {
                throw new ConflictException(ErrorCode.QUOTE_ALREADY_ACCEPTED,
                        ErrorCode.QUOTE_ALREADY_ACCEPTED.defaultMessage());
            }
            throw new BusinessRuleException(ErrorCode.QUOTE_NOT_ISSUED,
                    ErrorCode.QUOTE_NOT_ISSUED.defaultMessage());
        }

        long quoteVersionId = issuedOpt.get().quoteVersionId();
        LocalDateTime now = LocalDateTime.now();
        quoteVersionRepository.moveIssuedVersionTo(quoteVersionId, VERSION_CANCELLED);
        quoteVersionRepository.killActiveToken(quoteVersionId, TOKEN_CANCELLED, now);

        QuoteVersionRow cancelled = quoteVersionRepository.findById(quoteVersionId)
                .orElseThrow(() -> new IllegalStateException(
                        "quote_version disappeared while cancelling: " + quoteVersionId));
        // token_expires_at is null on a cancelled summary — the link is dead by definition.
        return ApiResponse.ok(QuoteIssuedSummaryDto.from(cancelled, null), CANCELLED_MESSAGE);
    }

    // ------------------------------------------------------------------
    // GET /orders/{orderId}/quote/pdf?type=issued|accepted
    // ------------------------------------------------------------------

    /**
     * Salesperson download of a STORED quote PDF (contract §7.1). {@code type=issued} streams the
     * active issued version's stored PDF; {@code type=accepted} is the 16F signed-PDF artifact —
     * no acceptance surface exists in 16E-A, so no signed PDF can exist and the contract-safe
     * response is always 404 {@code QUOTE_PDF_NOT_FOUND}. Read-only; allowed when LAID. The type
     * parameter is validated AFTER the scoped order lookup so a bad value can never 400 ahead of
     * the 404 (no existence leak).
     */
    @Transactional(readOnly = true)
    public QuoteStoredPdf downloadStoredPdf(String slug,
                                            String orderIdRaw,
                                            String type,
                                            HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parseOrderId(orderIdRaw);

        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        if (!TYPE_ISSUED.equals(type) && !TYPE_ACCEPTED.equals(type)) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, "type", "Must be issued or accepted.")));
        }
        if (TYPE_ACCEPTED.equals(type)) {
            // 16F artifact (signed PDF). Nothing in 16E-A can create one → contract-safe 404.
            throw new NotFoundException(ErrorCode.QUOTE_PDF_NOT_FOUND,
                    ErrorCode.QUOTE_PDF_NOT_FOUND.defaultMessage());
        }

        QuoteFile file = quoteVersionRepository.findIssuedFileByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.QUOTE_PDF_NOT_FOUND,
                        ErrorCode.QUOTE_PDF_NOT_FOUND.defaultMessage()));

        // Missing-on-disk -> UncheckedIOException -> generic 500 via the standard JSON wrapper
        // (same posture as the invoice D.4 download). storage_path is never returned.
        byte[] bytes = fileStorageService.read(file.storagePath());
        return new QuoteStoredPdf(bytes, file.fileName());
    }

    /** Carrier for the stored-PDF binary response (file name is the stored quote-...-v{n}.pdf). */
    public record QuoteStoredPdf(byte[] bytes, String fileName) {
    }

    // ------------------------------------------------------------------
    // Transaction 1 — issue/resend + persist (shared by both channels)
    // ------------------------------------------------------------------

    /**
     * The locked pre-delivery transaction (see class javadoc). Gate order mirrors the sibling
     * quote/order mutations: scoped {@code FOR UPDATE} lookup (404, never 403/leak) → LAID gate
     * (422 {@code ORDER_LOCKED}, before the body parse) → empty-body check → recipient gate (422,
     * before the draft-exists check — locked order) → draft load (404 {@code QUOTE_NOT_FOUND}) →
     * live below-cost re-check (422 {@code QUOTE_BELOW_COST}, before ANY persistence) →
     * changed-detection → persist → token mint → attempt markers.
     */
    private PreparedSend prepareSend(RequestContext ctx, String slug, long orderId, String body, String channel) {
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
        requireNotLaid(order);
        rejectNonEmptyBody(body);

        // Recipient gate — a fatal 422 precondition before any state change (contract §9). Email
        // reuses the Phase 13 validator; SMS uses the strict AU mobile rule (CustomerTab parity).
        OrderCustomer customer = orderCustomerRepository.findByOrderId(orderId).orElse(null);
        String recipient;
        if (CHANNEL_EMAIL.equals(channel)) {
            customerEmailValidator.requireValidCustomerEmail(customer);
            recipient = customer.getEmail().trim();
        } else {
            customerMobileValidator.requireValidCustomerMobile(customer);
            recipient = customer.getMobile().trim();
        }

        QuoteDraft draft = quoteDraftRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.QUOTE_NOT_FOUND,
                        ErrorCode.QUOTE_NOT_FOUND.defaultMessage()));
        List<QuoteDraftLine> draftLines =
                quoteDraftLineRepository.findByQuoteDraftIdOrderBySortOrderAscQuoteDraftLineIdAsc(draft.getQuoteDraftId());

        // Below-cost RE-CHECK against the order's LIVE product/charge cost lines (contract §6:
        // blocked at save AND send — cost lines may have changed since the last successful save).
        // Runs before any version/PDF/token persistence.
        BigDecimal totalCostExGst = orderTotalCostExGst(orderId);
        if (quoteDraftCalculator.belowCost(draft.getQuoteTotalExGst(), totalCostExGst)) {
            throw new BusinessRuleException(ErrorCode.QUOTE_BELOW_COST,
                    ErrorCode.QUOTE_BELOW_COST.defaultMessage());
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<QuoteVersionRow> issuedOpt = quoteVersionRepository.findIssuedByOrderId(orderId);
        boolean resend = issuedOpt.isPresent()
                && !draftChangedSinceIssue(draft, draftLines, order, issuedOpt.get());

        long quoteVersionId;
        byte[] pdfBytes;
        String pdfFileName;
        if (resend) {
            // Unchanged draft → resend the SAME version and its stored PDF verbatim (no
            // regeneration — the D.9 precedent). Old ACTIVE token → REPLACED (kept), new one below.
            QuoteVersionRow issued = issuedOpt.get();
            quoteVersionId = issued.quoteVersionId();
            QuoteFile file = quoteVersionRepository.findIssuedFileByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Issued quote version has no stored PDF for order " + orderId));
            pdfBytes = fileStorageService.read(file.storagePath());
            pdfFileName = file.fileName();
            quoteVersionRepository.killActiveToken(quoteVersionId, TOKEN_REPLACED, now);
        } else {
            // Changed draft (or no active issued version) → NEW issued version from a fresh
            // snapshot. The snapshot object feeds BOTH the DB rows and the PDF (they can't drift),
            // and its line set is mode-scoped (the §6.1 dormant-row guard).
            QuoteIssueSnapshot snapshot = buildIssueSnapshot(ctx.businessId(), order, draft, draftLines);
            int versionNumber = quoteVersionRepository.maxVersionNumber(orderId) + 1;
            pdfFileName = "quote-" + order.getOrderNumber() + "-v" + versionNumber + "." + PDF_EXTENSION;
            pdfBytes = quotePdfGenerator.render(
                    quotePdfModelAssembler.assembleIssued(ctx.business(), order, orderId, snapshot));

            // File-write-first with rollback cleanup (the OrderAttachment/Invoice pattern): a disk
            // failure throws before any DB row exists; a DB failure removes the just-written file.
            String storagePath = fileStorageService.store(pdfBytes, ctx.businessId(), orderId, PDF_EXTENSION);
            deleteFileOnRollback(storagePath);
            try {
                long storedFileId = quoteVersionRepository.insertStoredFile(
                        pdfFileName, storagePath, PDF_MIME, pdfBytes.length);
                // Supersede the prior ISSUED version (and kill its token) BEFORE inserting the new
                // ISSUED row — the V16 partial unique index allows only one ISSUED row per order.
                if (issuedOpt.isPresent()) {
                    long priorVersionId = issuedOpt.get().quoteVersionId();
                    quoteVersionRepository.moveIssuedVersionTo(priorVersionId, VERSION_SUPERSEDED);
                    quoteVersionRepository.killActiveToken(priorVersionId, TOKEN_SUPERSEDED, now);
                }
                QuoteVersionRow inserted = quoteVersionRepository.insertVersion(
                        orderId, versionNumber, snapshot, storedFileId, ctx.userId());
                quoteVersionId = inserted.quoteVersionId();
                quoteVersionRepository.insertVersionLines(quoteVersionId, snapshot.lines());
            } catch (RuntimeException ex) {
                fileStorageService.deleteQuietly(storagePath);
                throw ex;
            }
        }

        // Fresh ACTIVE token (hash-only storage; plaintext lives in method scope + the message
        // body's public link ONLY) and the pre-delivery attempt markers (kept on failure).
        String plainToken = mintPlainToken();
        LocalDateTime expiresAt = now.plusDays(TOKEN_TTL_DAYS);
        quoteVersionRepository.insertActiveToken(quoteVersionId, hashToken(plainToken), expiresAt);
        quoteVersionRepository.stampAttemptMarkers(quoteVersionId, channel, now);

        // Re-read the stamped row so the summary basis reflects the persisted final state.
        QuoteVersionRow finalRow = quoteVersionRepository.findById(quoteVersionId)
                .orElseThrow(() -> new IllegalStateException(
                        "quote_version disappeared while preparing send: " + quoteVersionId));

        // Public page URL shape is /{slug}/q/{token} (contract §8); the absolute origin is a
        // 16E-C/Phase 17 delivery concern — no real message leaves the system in 16E-A.
        String publicLink = "/" + slug + "/q/" + plainToken;
        return new PreparedSend(finalRow, expiresAt, recipient, pdfBytes, pdfFileName,
                publicLink, order.getOrderNumber());
    }

    /**
     * Freeze the issue snapshot: per-flooring-type tenant terms are read LIVE, sanitized ONCE
     * ({@code InvoiceTermsSanitizer}) and frozen into {@code terms_snapshot}; flooring type and
     * details-of-sale are frozen from the order; the line set is mode-scoped by
     * {@link QuoteIssueSnapshot#of} (dormant retained rows are never snapshotted).
     */
    private QuoteIssueSnapshot buildIssueSnapshot(long businessId, SalesOrder order,
                                                  QuoteDraft draft, List<QuoteDraftLine> draftLines) {
        BusinessInvoiceConfigView config = businessRepository
                .findInvoiceConfigByBusinessId(businessId)
                .orElse(null);
        // SOFT -> terms_soft, HARD -> terms_hard; NO fallback to legacy terms (Phase 15C rule).
        String rawTerms = config == null
                ? null
                : (FLOORING_SOFT.equals(order.getFlooringType()) ? config.getTermsSoft() : config.getTermsHard());
        String termsHtml = termsSanitizer.sanitize(rawTerms);
        return QuoteIssueSnapshot.of(draft, draftLines, order.getFlooringType(), termsHtml,
                order.getDetailsOfSale());
    }

    // ------------------------------------------------------------------
    // Changed-detection (locked rule — content only, never timestamps)
    // ------------------------------------------------------------------

    /**
     * True when the draft differs from the active ISSUED version's snapshot. Locked comparison
     * order: (1) itemised flag; (2) quote totals via {@code BigDecimal.compareTo} (never
     * {@code equals} — scale noise); (3) order flooring type vs {@code flooring_type_snapshot};
     * (4) order details-of-sale text vs {@code details_of_sale_snapshot}; (5) ITEMISED MODE ONLY:
     * the ordered line lists field-by-field (line_type / description / quantity /
     * unit_price_ex_gst / line_total_ex_gst / sort_order). A NON-itemised draft compares header
     * fields only — its {@code lines[]} are the retained DORMANT itemised rows (contract §6.1) and
     * must never force a new version. Tenant terms/config are deliberately excluded: a resend
     * re-delivers the frozen artifact; terms re-freeze only when the draft itself changes.
     *
     * <p>Both line lists are read {@code ORDER BY sort_order, <pk>} — the PK tiebreaker makes the
     * positional compare DETERMINISTIC when duplicate sort_order values exist (contract-legal via
     * direct API clients; without it, Postgres's unspecified tie order could flag an unchanged
     * draft as changed and spuriously supersede the issued version — 16E-A review finding).
     */
    private boolean draftChangedSinceIssue(QuoteDraft draft, List<QuoteDraftLine> draftLines,
                                           SalesOrder order, QuoteVersionRow issued) {
        if (draft.isItemised() != issued.itemised()) {
            return true;
        }
        if (moneyDiffers(draft.getQuoteTotalExGst(), issued.quoteTotalExGst())
                || moneyDiffers(draft.getQuoteTotalIncGst(), issued.quoteTotalIncGst())) {
            return true;
        }
        if (!Objects.equals(order.getFlooringType(), issued.flooringTypeSnapshot())) {
            return true;
        }
        if (!Objects.equals(order.getDetailsOfSale(), issued.detailsOfSaleSnapshot())) {
            return true;
        }
        if (draft.isItemised()) {
            List<QuoteVersionLineRow> versionLines =
                    quoteVersionRepository.findVersionLines(issued.quoteVersionId());
            if (draftLines.size() != versionLines.size()) {
                return true;
            }
            for (int i = 0; i < draftLines.size(); i++) {
                QuoteDraftLine d = draftLines.get(i);
                QuoteVersionLineRow v = versionLines.get(i);
                if (!Objects.equals(d.getLineType(), v.lineType())
                        || !Objects.equals(d.getDescription(), v.description())
                        || moneyDiffers(d.getQuantity(), v.quantity())
                        || moneyDiffers(d.getUnitPriceExGst(), v.unitPriceExGst())
                        || moneyDiffers(d.getLineTotalExGst(), v.lineTotalExGst())
                        || d.getSortOrder() != v.sortOrder()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Null-safe money difference by {@code compareTo} (2.5 == 2.50; two nulls are equal). */
    private static boolean moneyDiffers(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return false;
        }
        if (a == null || b == null) {
            return true;
        }
        return a.compareTo(b) != 0;
    }

    // ------------------------------------------------------------------
    // Transaction 2 — success-only email stamp
    // ------------------------------------------------------------------

    /**
     * Stamp {@code quote_version.last_emailed_at} in place after a successful email delivery
     * (mirrors {@code OrderInvoiceService.stampEmailSuccess}): the scoped order row is re-taken
     * {@code FOR UPDATE} first so the stamp serialises with any concurrent send/cancel, then the
     * row is re-read for the response. Deliberately NEVER touches
     * {@code sales_order.last_emailed_at} — that §11.1 dashboard mirror is invoice-only.
     */
    private QuoteIssuedSummaryDto stampEmailSuccess(RequestContext ctx, long orderId,
                                                    long quoteVersionId, LocalDateTime sentAt) {
        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new IllegalStateException(
                        "Order disappeared while stamping quote last_emailed_at: " + orderId));
        quoteVersionRepository.stampLastEmailedAt(quoteVersionId, sentAt);
        QuoteVersionRow row = quoteVersionRepository.findById(quoteVersionId)
                .orElseThrow(() -> new IllegalStateException(
                        "quote_version disappeared while stamping last_emailed_at: " + quoteVersionId));
        LocalDateTime tokenExpiresAt = quoteVersionRepository
                .findActiveTokenExpiry(quoteVersionId)
                .orElse(null);
        return QuoteIssuedSummaryDto.from(row, tokenExpiresAt);
    }

    // ------------------------------------------------------------------
    // Token mint (contract §8)
    // ------------------------------------------------------------------

    /** 32 CSPRNG bytes, URL-safe Base64 (no padding) — never persisted, never in a protected response. */
    private static String mintPlainToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of the plaintext token — the ONLY token material ever stored ({@code token_hash}). */
    private static String hashToken(String plainToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plainToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ------------------------------------------------------------------
    // Message content (Phase 16E-A plain text; redesign is a Phase 17 concern)
    // ------------------------------------------------------------------

    private static String emailSubject(String businessName) {
        return "Your quote from " + businessName;
    }

    // The public link is the ONLY place the plaintext token appears — exactly once, no internal IDs.
    private static String emailBody(String orderNumber, String businessName, String publicLink) {
        return "Hi,\n\nPlease find your quote " + orderNumber + " from " + businessName
                + " attached.\n\nYou can view your quote online here: " + publicLink
                + "\n\nThank you.";
    }

    // SMS carries the LINK ONLY (contract §9) in one short line.
    private static String smsText(String orderNumber, String businessName, String publicLink) {
        return "Your quote " + orderNumber + " from " + businessName + ": " + publicLink;
    }

    // ------------------------------------------------------------------
    // Gates / helpers (duplicated per the locked duplicate-don't-extract decision)
    // ------------------------------------------------------------------

    private void requireNotLaid(SalesOrder order) {
        if (STATUS_LAID.equals(order.getOrderStatus())) {
            throw new BusinessRuleException(ErrorCode.ORDER_LOCKED, ErrorCode.ORDER_LOCKED.defaultMessage());
        }
    }

    private BigDecimal orderTotalCostExGst(long orderId) {
        LineFinancials products = orderProductLineRepository.sumFinancials(orderId);
        LineFinancials charges = orderChargeLineReadRepository.sumFinancials(orderId);
        return money(products.cost()).add(money(charges.cost())).setScale(MONEY_SCALE, ROUNDING);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, ROUNDING);
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

    /**
     * Empty-body rule for the EmptyObjectRequest endpoints (send-email / send-sms / cancel), the
     * exact {@code QuoteService.validatePreviewBody} semantics: missing/blank/{@code {}} accepted;
     * malformed JSON → 400 {@code MALFORMED_JSON}; a non-empty object (any field) or ANY non-object
     * JSON (literal {@code null} / array / scalar) → 400 {@code VALIDATION_FAILED}. Always called
     * strictly AFTER the scoped lookup + LAID gates.
     */
    private void rejectNonEmptyBody(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new MalformedJsonException();
        }
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
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }

    /**
     * Delete a just-written issued PDF IFF the surrounding transaction does NOT commit (the
     * {@code OrderInvoiceService} rollback-cleanup pattern): the file is written before the
     * {@code stored_file}/{@code quote_version} rows, so a rollback would otherwise orphan it.
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

    /**
     * Everything transaction 1 hands to the post-commit delivery + response building: the re-read
     * version row (summary basis), the fresh token's expiry, the resolved recipient, the PDF bytes
     * + file name (stored bytes reused verbatim on a resend), the public link carrying the
     * plaintext token (never serialized in any response), and the order number for the message
     * wording. Never serialized itself.
     */
    private record PreparedSend(QuoteVersionRow row,
                                LocalDateTime tokenExpiresAt,
                                String recipient,
                                byte[] pdfBytes,
                                String pdfFileName,
                                String publicLink,
                                String orderNumber) {
    }
}
