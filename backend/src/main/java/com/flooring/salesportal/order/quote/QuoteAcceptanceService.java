package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.email.QuoteAcceptanceNotificationException;
import com.flooring.salesportal.common.email.QuoteAcceptanceNotificationRequest;
import com.flooring.salesportal.common.email.QuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.FileUploadException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.OrderChargeLineReadRepository;
import com.flooring.salesportal.order.OrderProductLineRepository;
import com.flooring.salesportal.order.SalesOrder;
import com.flooring.salesportal.order.SalesOrderFinancialWriteRepository;
import com.flooring.salesportal.order.SalesOrderRepository;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import com.flooring.salesportal.order.financial.LineFinancials;
import com.flooring.salesportal.order.financial.OrderFinancialCalculator;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteTokenRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionLineRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionRow;
import com.flooring.salesportal.order.quote.dto.PublicQuoteAcceptResultDto;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Phase 16F PR1 — remote quote ACCEPTANCE on the public, token-only surface
 * ({@code POST /api/v1/public/quotes/{token}/accept}; contract §7.2). The customer signs the issued
 * quote behind the link; the issued snapshot is locked as the legal billing number, a signed quote PDF
 * is stored, the link dies, the signed inc-GST total becomes the order's working sale price (D6b) and
 * the store is notified after commit.
 *
 * <p>Same security posture as {@link PublicQuoteService}: the secret token is the ONLY credential — no
 * session, no slug, no {@code RequestContextGuard}, and nothing beyond the single quote behind the
 * token is readable or writable. The 201 body is only {@code {state: "INACTIVE"}}.
 *
 * <p><b>Ordering (locked).</b>
 * <ol>
 *   <li><b>Token gate</b> — {@link PublicQuoteService#resolve} (shape gate → hash lookup →
 *       constant-time verify → committed lazy expiry): unknown/malformed → 404
 *       {@code QUOTE_TOKEN_NOT_FOUND}; a found dead token → its 410 {@code QUOTE_LINK_*}. Token state is
 *       gated BEFORE any application-level multipart validation.</li>
 *   <li><b>Multipart validation</b> — no database access and no lock: exactly one {@code signature}
 *       file part and nothing else (unknown file/form parts or a duplicate → 400
 *       {@code VALIDATION_FAILED}); missing/empty → 422 {@code SIGNATURE_REQUIRED}; not
 *       {@code image/png}, over 2,097,152 bytes, wrong PNG magic or not safely decodable → 400
 *       {@code SIGNATURE_INVALID}. There is NO name part — the accepted name is never typed.</li>
 *   <li><b>One transaction under the order row lock</b> — the SAME {@code sales_order} row lock every
 *       protected quote / invoice / line / price mutation holds. Under it the token and the issued
 *       version are RE-READ (the pre-lock resolution is never trusted after waiting): a token that
 *       died meanwhile → its 410; a token whose expiry fell due meanwhile → the guarded lazy-expiry
 *       flip is COMMITTED (returned as an outcome, never thrown inside the transaction) and then
 *       translated to 410 {@code QUOTE_LINK_EXPIRED}. For a still-ACTIVE token + ISSUED version the
 *       frozen name, the below-cost re-check and the D6b financials are validated BEFORE anything is
 *       written, then the acceptance is committed atomically: signature PNG + stored_file → signed PDF
 *       + stored_file → guarded {@code ISSUED -> ACCEPTED} → guarded {@code ACTIVE -> CONSUMED} → the
 *       D6b order-price write.</li>
 *   <li><b>After commit</b> — the store notification (non-fatal), then 201.</li>
 * </ol>
 *
 * <p><b>Rollback.</b> Both files are written before their rows (file-write-first) with an in-method
 * delete AND a {@code TransactionSynchronization} rollback hook, so any persistence / render / storage /
 * guard / commit failure rolls back every domain write and deletes ONLY the files this request wrote
 * (the original issued PDF and every pre-existing file are never touched). A guarded transition that
 * does not move exactly one row is an invariant breach → rollback.
 *
 * <p><b>Public-safe failures.</b> A missing frozen name (422 {@code ACCEPTED_CUSTOMER_NAME_REQUIRED}),
 * a below-cost quote (422 {@code QUOTE_BELOW_COST}, decision D11) and a non-persistable D6b price
 * (422 {@code BUSINESS_RULE_VIOLATION}) all carry the same customer-facing message and never include
 * cost / GP figures or financial validation internals. Staff wording elsewhere is unchanged.
 *
 * <p><b>D6b (approved exception to the 16D-A quote/order price separation).</b> The accepted quote's
 * frozen inc-GST total is written as the order's sale-price override through the existing financial
 * model — {@code price_adjustment_inc_gst = accepted inc total − calculated order inc total} (current
 * persisted lines, HALF_UP 2dp) plus the recomputed header scalars via
 * {@link SalesOrderFinancialWriteRepository#updateHeaderFinancialsWithAdjustment} — the
 * {@code OrderSalePriceService} override math, duplicated per the locked duplicate-don't-extract
 * convention. It is an internal write of this public transaction: the protected manual-override
 * endpoint and its LAID gate are untouched, and acceptance is ALLOWED when the order is LAID (decision
 * D4). Draft saves and sends never write the order price. Nothing else on the order changes: draft
 * rows/totals, product/charge lines, order status, invoices, payments and delivery markers.
 *
 * <p><b>Accepted name (decision D1).</b> Exclusively the issue-time V17
 * {@code customer_name_snapshot} — never the live {@code order_customer}, never typed, never
 * truncated (stored in the TEXT column V19 widened). Null/blank → 422.
 */
@Service
public class QuoteAcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(QuoteAcceptanceService.class);

    /** The single customer-facing message for every business-rule rejection of an acceptance. */
    static final String PUBLIC_CONTACT_STORE_MESSAGE =
            "This quote can no longer be accepted online. Please contact the store.";
    /** Quotation-appropriate SIGNATURE_REQUIRED wording (the code's default text says "invoice"). */
    static final String QUOTE_SIGNATURE_REQUIRED_MESSAGE = "A signature is required to accept this quote.";
    private static final String ACCEPTED_MESSAGE = "Quote accepted.";

    private static final String TOKEN_ACTIVE = "ACTIVE";
    private static final String VERSION_ISSUED = "ISSUED";
    private static final String STATE_EXPIRED = "EXPIRED";
    private static final String STATE_INACTIVE = "INACTIVE";

    // Multipart contract (contract §7.2): ONE part, the PNG signature. No name / declaration / money part.
    private static final String SIGNATURE_PART = "signature";
    private static final String SIGNATURE_MIME = "image/png";
    private static final String SIGNATURE_EXTENSION = "png";
    // Exactly 2 MB: size > this -> 400 SIGNATURE_INVALID; size == this is allowed (Phase 13 D.8 parity).
    private static final long MAX_SIGNATURE_SIZE_BYTES = 2_097_152L;
    // Safe-decode bounds, checked from the IHDR header BEFORE any pixel is decoded, so a tiny compressed
    // file declaring enormous dimensions or 16-bit samples (decompression bomb) is rejected as
    // SIGNATURE_INVALID instead of exhausting memory on this unauthenticated surface. A browser canvas
    // export (canvas.toBlob('image/png')) is always 8-bit, and a typical DPR-scaled signature pad stays
    // well below these bounds; at most 4,000,000 8-bit pixels keeps one decoded RGBA raster at or below
    // 16 MiB. An uncapped canvas at extreme browser zoom CAN exceed 4,000,000 px, so the PR3 public
    // signing pad must cap its export size to these bounds (documented in the quotation contract).
    private static final int MAX_SIGNATURE_SIDE_PX = 8_192;
    private static final long MAX_SIGNATURE_PIXELS = 4_000_000L;
    private static final int MAX_SIGNATURE_BIT_DEPTH = 8;
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    // IHDR: 8-byte magic, then the 4-byte length (13) + "IHDR" + width(4) height(4) bitDepth(1)
    // colourType(1) compression(1) filter(1) interlace(1) + CRC(4).
    private static final int IHDR_LENGTH_OFFSET = 8;
    private static final int IHDR_TYPE_OFFSET = 12;
    private static final int IHDR_WIDTH_OFFSET = 16;
    private static final int IHDR_HEIGHT_OFFSET = 20;
    private static final int IHDR_BIT_DEPTH_OFFSET = 24;
    private static final int IHDR_COLOUR_TYPE_OFFSET = 25;
    private static final int IHDR_MIN_FILE_LENGTH = 33;

    private static final String PDF_MIME = "application/pdf";
    private static final String PDF_EXTENSION = "pdf";

    // D6b money rules — the OrderSalePriceService / line-service constants (duplicated, not extracted).
    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final int MONEY_MAX_INTEGER_DIGITS = 10 - MONEY_SCALE; // DECIMAL(10,2)
    // sales_order.gp_percent is DECIMAL(5,2): persist NULL when the true value would overflow (R4).
    private static final BigDecimal MAX_DB_GP_PERCENT = new BigDecimal("999.99");

    // Notification display formats (the signed-invoice / signed-quote PDF patterns).
    private static final Locale DISPLAY_LOCALE = Locale.ENGLISH;
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", DISPLAY_LOCALE);

    private final PublicQuoteService publicQuoteService;
    private final QuoteVersionRepository quoteVersionRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final BusinessRepository businessRepository;
    private final StoreRepository storeRepository;
    private final OrderProductLineRepository orderProductLineRepository;
    private final OrderChargeLineReadRepository orderChargeLineReadRepository;
    private final QuoteDraftCalculator quoteDraftCalculator;
    private final OrderFinancialCalculator financialCalculator;
    private final SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository;
    private final QuotePdfModelAssembler quotePdfModelAssembler;
    private final QuotePdfGenerator quotePdfGenerator;
    private final FileStorageService fileStorageService;
    private final QuoteAcceptanceNotificationSender notificationSender;
    // Programmatic transaction: the notification must run strictly AFTER the acceptance commit, and a
    // lazy-expiry flip discovered under the lock must COMMIT before its 410 — so this service cannot
    // be @Transactional (the OrderInvoiceService accept / PublicQuoteService precedent).
    private final TransactionTemplate transactionTemplate;

    public QuoteAcceptanceService(PublicQuoteService publicQuoteService,
                                  QuoteVersionRepository quoteVersionRepository,
                                  SalesOrderRepository salesOrderRepository,
                                  BusinessRepository businessRepository,
                                  StoreRepository storeRepository,
                                  OrderProductLineRepository orderProductLineRepository,
                                  OrderChargeLineReadRepository orderChargeLineReadRepository,
                                  QuoteDraftCalculator quoteDraftCalculator,
                                  OrderFinancialCalculator financialCalculator,
                                  SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository,
                                  QuotePdfModelAssembler quotePdfModelAssembler,
                                  QuotePdfGenerator quotePdfGenerator,
                                  FileStorageService fileStorageService,
                                  QuoteAcceptanceNotificationSender notificationSender,
                                  PlatformTransactionManager transactionManager) {
        this.publicQuoteService = publicQuoteService;
        this.quoteVersionRepository = quoteVersionRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.businessRepository = businessRepository;
        this.storeRepository = storeRepository;
        this.orderProductLineRepository = orderProductLineRepository;
        this.orderChargeLineReadRepository = orderChargeLineReadRepository;
        this.quoteDraftCalculator = quoteDraftCalculator;
        this.financialCalculator = financialCalculator;
        this.salesOrderFinancialWriteRepository = salesOrderFinancialWriteRepository;
        this.quotePdfModelAssembler = quotePdfModelAssembler;
        this.quotePdfGenerator = quotePdfGenerator;
        this.fileStorageService = fileStorageService;
        this.notificationSender = notificationSender;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // POST /public/quotes/{token}/accept
    // ------------------------------------------------------------------

    /**
     * Accept (sign) the issued quote behind {@code token} (see the class javadoc for the locked
     * ordering). 201 {@code {state: "INACTIVE"}} on success; 404 / 410 / 400 / 422 otherwise.
     */
    public ApiResponse<PublicQuoteAcceptResultDto> accept(String token, MultipartHttpServletRequest request) {
        // 1. Token gate — the exact public resolution (committed lazy expiry) + the 410 mapping.
        PublicQuoteService.ResolvedToken resolved = publicQuoteService.resolve(token);
        PublicQuoteService.requireActive(resolved);

        // 2. Application-level multipart validation — no database access, no lock.
        byte[] signaturePng = validateSignatureUpload(request);

        // 3. The acceptance transaction (order row lock + locked re-read + atomic persist).
        AcceptOutcome outcome = transactionTemplate.execute(status -> acceptUnderLock(resolved, signaturePng));
        if (outcome == null) {
            throw new IllegalStateException("Quote acceptance transaction returned no outcome");
        }
        if (outcome.tokenMissing()) {
            // FK-impossible (token rows are never deleted) — the unknown-token 404, defensively.
            throw new NotFoundException(ErrorCode.QUOTE_TOKEN_NOT_FOUND,
                    ErrorCode.QUOTE_TOKEN_NOT_FOUND.defaultMessage());
        }
        if (outcome.deadState() != null) {
            // The token died (or its expiry fell due) while this request waited for the lock. Any
            // lazy-expiry flip is ALREADY COMMITTED; translate the true state to its 410 now.
            PublicQuoteService.requireActiveState(outcome.deadState());
            throw new IllegalStateException("Dead quote link outcome did not map to a 410: " + outcome.deadState());
        }

        // 4. Strictly post-commit, non-fatal store notification.
        notifyStoreQuietly(outcome.notification());
        return ApiResponse.ok(new PublicQuoteAcceptResultDto(STATE_INACTIVE), ACCEPTED_MESSAGE);
    }

    /**
     * The acceptance transaction body (runs inside {@code transactionTemplate.execute}). Takes the
     * order row lock FIRST, then re-reads the token + version under it. Dead / expired outcomes are
     * RETURNED (so a lazy-expiry flip commits); business-rule rejections are THROWN before any write
     * (nothing to keep); persistence failures are THROWN after writes (rollback + file cleanup).
     */
    private AcceptOutcome acceptUnderLock(PublicQuoteService.ResolvedToken resolved, byte[] signaturePng) {
        long quoteVersionId = resolved.token().quoteVersionId();
        long orderId = quoteVersionRepository.findById(quoteVersionId)
                .map(QuoteVersionRow::orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "quote_version disappeared for public token: " + quoteVersionId));

        // The SAME sales_order row lock the protected quote / invoice / line / price mutations hold.
        quoteVersionRepository.lockOrderRowForPublicTransition(orderId);

        // Re-read the token UNDER the lock — the pre-lock resolution may be stale after waiting.
        QuoteTokenRow token = quoteVersionRepository.findTokenByHash(resolved.token().tokenHash()).orElse(null);
        if (token == null) {
            return AcceptOutcome.missingToken();
        }
        if (!TOKEN_ACTIVE.equals(token.status())) {
            // A concurrent resend / supersede / cancel / D.8 / acceptance won the lock first.
            return AcceptOutcome.dead(PublicQuoteService.toPublicState(token.status()));
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(token.expiresAt())) {
            // The link's expiry fell due while waiting: the guarded lazy-expiry flip, COMMITTED by
            // returning (an exception here would roll it back) and translated to 410 after commit.
            if (quoteVersionRepository.expireActiveToken(token.quoteTokenId(), now)) {
                quoteVersionRepository.expireIssuedVersion(token.quoteVersionId());
            }
            return AcceptOutcome.dead(STATE_EXPIRED);
        }

        QuoteVersionRow version = quoteVersionRepository.findById(quoteVersionId)
                .orElseThrow(() -> new IllegalStateException(
                        "quote_version disappeared under the order lock: " + quoteVersionId));
        if (!VERSION_ISSUED.equals(version.status())) {
            // One ACTIVE token per ISSUED version (every transition kills the token in the same
            // locked transaction), so an ACTIVE token on a non-ISSUED version is an invariant breach.
            throw new IllegalStateException(
                    "ACTIVE quote token on a non-ISSUED quote_version " + quoteVersionId + " (" + version.status() + ")");
        }
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("sales_order disappeared for quote_version: " + quoteVersionId));

        // (a) The frozen accepted name — the V17 issue snapshot ONLY (decision D1). No live fallback.
        String acceptedCustomerName = blankToNull(version.customerNameSnapshot());
        if (acceptedCustomerName == null) {
            throw new BusinessRuleException(ErrorCode.ACCEPTED_CUSTOMER_NAME_REQUIRED, PUBLIC_CONTACT_STORE_MESSAGE);
        }

        // (b) Below-cost re-check: the FROZEN quote ex-GST total vs the LIVE persisted product/charge
        // line cost (read under the lock every line mutation also takes). Mode-agnostic: the frozen ex
        // total is the snapshot line sum (itemised) or final_total / 1.10 (non-itemised) — dormant
        // draft rows never reach a version.
        LineFinancials products = orderProductLineRepository.sumFinancials(orderId);
        LineFinancials charges = orderChargeLineReadRepository.sumFinancials(orderId);
        BigDecimal totalCostExGst = money(products.cost()).add(money(charges.cost())).setScale(MONEY_SCALE, ROUNDING);
        if (quoteDraftCalculator.belowCost(version.quoteTotalExGst(), totalCostExGst)) {
            throw new BusinessRuleException(ErrorCode.QUOTE_BELOW_COST, PUBLIC_CONTACT_STORE_MESSAGE);
        }

        // (c) D6b: derive + validate the order-price write BEFORE anything is written.
        AcceptedPriceWrite priceWrite = planAcceptedPrice(products, charges, version.quoteTotalIncGst());

        // The store notification is resolved inside the transaction (consistent snapshot of the
        // order's store) but delivered only after commit.
        NotificationPlan notification = notificationPlan(order, version, acceptedCustomerName, now);

        // Persist atomically. File-write-first with an in-method delete AND a rollback hook per file
        // (the OrderInvoiceService D.8 pattern, here twice): a storage failure throws before its row
        // exists; any later failure (render, insert, guard, price write, commit) removes both new files.
        String signatureFileName = "quote-signature-" + order.getOrderNumber()
                + "-v" + version.versionNumber() + "." + SIGNATURE_EXTENSION;
        String signaturePath = fileStorageService.store(
                signaturePng, order.getBusinessId(), orderId, SIGNATURE_EXTENSION);
        deleteFileOnRollback(signaturePath);
        try {
            long signatureFileId = quoteVersionRepository.insertStoredFile(
                    signatureFileName, signaturePath, SIGNATURE_MIME, signaturePng.length);

            // The signed quote PDF — rendered ONLY from the immutable issued version + its snapshot
            // lines + V17 identity, plus the accepted name / time / signature.
            Business business = businessRepository.findById(order.getBusinessId())
                    .orElseThrow(() -> new IllegalStateException(
                            "business disappeared for sales_order: " + orderId));
            List<QuoteVersionLineRow> versionLines = quoteVersionRepository.findVersionLines(quoteVersionId);
            byte[] pdfBytes = quotePdfGenerator.render(quotePdfModelAssembler.assembleAccepted(
                    business, order, version, versionLines, now, acceptedCustomerName, signaturePng));
            String pdfFileName = "quote-" + order.getOrderNumber()
                    + "-v" + version.versionNumber() + "-signed." + PDF_EXTENSION;
            String pdfPath = fileStorageService.store(pdfBytes, order.getBusinessId(), orderId, PDF_EXTENSION);
            deleteFileOnRollback(pdfPath);
            try {
                long signedPdfFileId = quoteVersionRepository.insertStoredFile(
                        pdfFileName, pdfPath, PDF_MIME, pdfBytes.length);

                requireExactlyOneRow(quoteVersionRepository.acceptIssuedVersion(
                                quoteVersionId, now, acceptedCustomerName, signatureFileId, signedPdfFileId),
                        "quote_version " + quoteVersionId + " was not ISSUED when accepting");
                requireExactlyOneRow(quoteVersionRepository.consumeActiveToken(token.quoteTokenId(), now),
                        "quote_token " + token.quoteTokenId() + " was not ACTIVE when consuming");

                // D6b — the signed inc-GST total becomes the order's working sale price.
                salesOrderFinancialWriteRepository.updateHeaderFinancialsWithAdjustment(
                        orderId,
                        priceWrite.priceAdjustmentIncGst(),
                        priceWrite.salePriceExGst(),
                        priceWrite.totalCost(),
                        priceWrite.gp(),
                        priceWrite.gpPercent(),
                        now);
            } catch (RuntimeException ex) {
                fileStorageService.deleteQuietly(pdfPath);
                throw ex;
            }
        } catch (RuntimeException ex) {
            fileStorageService.deleteQuietly(signaturePath);
            throw ex;
        }

        return AcceptOutcome.accepted(notification);
    }

    // ------------------------------------------------------------------
    // D6b — accepted quote total becomes the order sale-price override
    // ------------------------------------------------------------------

    /**
     * The {@code OrderSalePriceService} override derivation for a GIVEN inc-GST total (duplicated per
     * the locked convention): {@code price_adjustment_inc_gst = round(acceptedInc − calculated_total_inc_gst, 2)}
     * over the CURRENT persisted lines, then the recomputed header scalars. A non-persistable result
     * (DECIMAL(10,2) overflow of the adjustment or a header scalar, or a negative total cost) is a
     * public-safe 422 — no internals, nothing written. Unlike the protected override endpoint there is
     * deliberately NO positive-price input gate: a zero-total quote that passed the acceptance rules
     * is accepted (D6's blank-details / $0 guards belong to Path A conversion, PR2).
     */
    private AcceptedPriceWrite planAcceptedPrice(LineFinancials products, LineFinancials charges,
                                                 BigDecimal acceptedIncGst) {
        BigDecimal calculatedTotalIncGst =
                financialCalculator.compute(products, charges, null).calculatedTotalIncGst();
        BigDecimal priceAdjustmentIncGst =
                acceptedIncGst.subtract(calculatedTotalIncGst).setScale(MONEY_SCALE, ROUNDING);
        if (!fitsMoney(priceAdjustmentIncGst)) {
            throw financialFailure();
        }
        OrderFinancialSummaryDto summary = financialCalculator.compute(products, charges, priceAdjustmentIncGst);
        if (!fitsMoney(summary.salePriceExGst())
                || summary.totalCost().compareTo(BigDecimal.ZERO) < 0
                || !fitsMoney(summary.totalCost())
                || !fitsMoney(summary.gp())) {
            throw financialFailure();
        }
        return new AcceptedPriceWrite(
                priceAdjustmentIncGst,
                summary.salePriceExGst(),
                summary.totalCost(),
                summary.gp(),
                persistableGpPercent(summary.gpPercent()));
    }

    private static BusinessRuleException financialFailure() {
        return new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION, PUBLIC_CONTACT_STORE_MESSAGE);
    }

    /** True iff {@code value}, scaled to 2dp, fits a DECIMAL(10,2) column (|value| <= 99999999.99). */
    private static boolean fitsMoney(BigDecimal value) {
        BigDecimal scaled = value.setScale(MONEY_SCALE, ROUNDING);
        return scaled.precision() - scaled.scale() <= MONEY_MAX_INTEGER_DIGITS;
    }

    /** DECIMAL(5,2) overflow -> NULL (locked review decision R4; the OrderSalePriceService rule). */
    private static BigDecimal persistableGpPercent(BigDecimal gpPercent) {
        if (gpPercent == null) {
            return null;
        }
        return gpPercent.abs().compareTo(MAX_DB_GP_PERCENT) <= 0 ? gpPercent : null;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, ROUNDING);
    }

    // ------------------------------------------------------------------
    // Multipart validation (application level; no DB, no lock)
    // ------------------------------------------------------------------

    /**
     * The accept body: exactly ONE {@code signature} file part. Order: unknown file/form parts and a
     * duplicate {@code signature} → 400 {@code VALIDATION_FAILED} (one detail per offending part);
     * missing/empty → 422 {@code SIGNATURE_REQUIRED} (quote wording); declared type not
     * {@code image/png} or over 2,097,152 bytes → 400 {@code SIGNATURE_INVALID}; wrong PNG magic, an
     * IHDR outside the safe-decode bounds (dimensions / bit depth) or not decodable → 400
     * {@code SIGNATURE_INVALID}. Global upload limits are unchanged.
     *
     * <p>Returns the SERVER-NORMALISED signature: the decoded pixels re-encoded as a clean PNG. Only
     * these bytes are stored and embedded in the signed PDF — ancillary chunks (iCCP / zTXt / iTXt /
     * tEXt …), bytes after IEND and over-long IDAT streams never reach storage or PDFBox, so a
     * compressed payload that the bounded decode does not inflate cannot be inflated later by the PDF
     * render (decompression bomb). The image itself (dimensions + pixels) is unchanged.
     */
    private static byte[] validateSignatureUpload(MultipartHttpServletRequest request) {
        List<ErrorDetail> errors = new ArrayList<>();
        for (Iterator<String> fileNames = request.getFileNames(); fileNames.hasNext();) {
            String name = fileNames.next();
            if (!SIGNATURE_PART.equals(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
        // No form field is accepted at all — not a name, not a declaration flag, not a money value.
        for (Enumeration<String> paramNames = request.getParameterNames(); paramNames.hasMoreElements();) {
            errors.add(new ErrorDetail(null, paramNames.nextElement(), "Not allowed."));
        }
        if (request.getFiles(SIGNATURE_PART).size() > 1) {
            errors.add(new ErrorDetail(null, SIGNATURE_PART, "Must appear at most once."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }

        MultipartFile signature = request.getFile(SIGNATURE_PART);
        if (signature == null || signature.isEmpty()) {
            throw new BusinessRuleException(ErrorCode.SIGNATURE_REQUIRED, QUOTE_SIGNATURE_REQUIRED_MESSAGE);
        }
        if (!SIGNATURE_MIME.equals(signature.getContentType())
                || signature.getSize() > MAX_SIGNATURE_SIZE_BYTES) {
            throw signatureInvalid();
        }
        byte[] bytes;
        try {
            bytes = signature.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded quote signature", e);
        }
        if (bytes.length == 0 || bytes.length > MAX_SIGNATURE_SIZE_BYTES
                || !hasPngMagic(bytes) || !hasSafePngHeader(bytes)) {
            throw signatureInvalid();
        }
        byte[] normalised = decodeAndReencodePng(bytes);
        if (normalised == null) {
            throw signatureInvalid();
        }
        return normalised;
    }

    private static FileUploadException signatureInvalid() {
        return new FileUploadException(ErrorCode.SIGNATURE_INVALID, ErrorCode.SIGNATURE_INVALID.defaultMessage());
    }

    private static boolean hasPngMagic(byte[] bytes) {
        if (bytes.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (bytes[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The IHDR pre-check, from the raw bytes BEFORE any decoder runs: the first chunk must be a
     * 13-byte {@code IHDR}; width/height within the bounds (each side at most 8,192 px, at most
     * 4,000,000 px); bit depth at most 8 (a canvas export is 8-bit; 16-bit samples would double the
     * decoded raster); a valid PNG colour type (0, 2, 3, 4 or 6).
     */
    private static boolean hasSafePngHeader(byte[] bytes) {
        if (bytes.length < IHDR_MIN_FILE_LENGTH) {
            return false;
        }
        if (readInt(bytes, IHDR_LENGTH_OFFSET) != 13
                || bytes[IHDR_TYPE_OFFSET] != 'I' || bytes[IHDR_TYPE_OFFSET + 1] != 'H'
                || bytes[IHDR_TYPE_OFFSET + 2] != 'D' || bytes[IHDR_TYPE_OFFSET + 3] != 'R') {
            return false;
        }
        long width = readInt(bytes, IHDR_WIDTH_OFFSET) & 0xFFFFFFFFL;
        long height = readInt(bytes, IHDR_HEIGHT_OFFSET) & 0xFFFFFFFFL;
        int bitDepth = bytes[IHDR_BIT_DEPTH_OFFSET] & 0xFF;
        int colourType = bytes[IHDR_COLOUR_TYPE_OFFSET] & 0xFF;
        if (width <= 0 || height <= 0
                || width > MAX_SIGNATURE_SIDE_PX || height > MAX_SIGNATURE_SIDE_PX
                || width * height > MAX_SIGNATURE_PIXELS) {
            return false;
        }
        if (bitDepth > MAX_SIGNATURE_BIT_DEPTH) {
            return false;
        }
        return colourType == 0 || colourType == 2 || colourType == 3 || colourType == 4 || colourType == 6;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    /**
     * Decode the (header-checked) PNG with the JDK PNG reader — metadata ignored, dimensions re-checked
     * from the reader — and RE-ENCODE the pixels as a clean 8-bit ARGB PNG. Returns the re-encoded bytes,
     * or null when the stream is not a decodable PNG within the bounds. Recoverable decoder failures
     * (I/O, malformed data, runtime exceptions from corrupt input) are rejections; a JVM
     * {@link Error} is not swallowed.
     */
    private static byte[] decodeAndReencodePng(byte[] bytes) {
        // In-memory stream: no ImageIO disk-cache temp file per attempt.
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            BufferedImage decoded;
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    return null;
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || width > MAX_SIGNATURE_SIDE_PX || height > MAX_SIGNATURE_SIDE_PX
                        || (long) width * height > MAX_SIGNATURE_PIXELS) {
                    return null;
                }
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
            if (decoded == null) {
                return null;
            }
            // Canonical sRGB ARGB copy (transparency kept), then a fresh PNG encode: only IHDR / IDAT /
            // IEND (+ standard chunks the writer emits) — none of the uploaded ancillary data survives.
            BufferedImage argb = new BufferedImage(decoded.getWidth(), decoded.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = argb.createGraphics();
            try {
                // Src (not the default SrcOver): an exact per-pixel copy/convert with NO blending, so
                // semi-transparent (anti-aliased) stroke pixels keep their exact colour + alpha.
                graphics.setComposite(java.awt.AlphaComposite.Src);
                graphics.drawImage(decoded, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!ImageIO.write(argb, "png", out)) {
                return null;
            }
            byte[] reencoded = out.toByteArray();
            return reencoded.length == 0 ? null : reencoded;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Store notification (post-commit, non-fatal; decisions D3 / D12)
    // ------------------------------------------------------------------

    /**
     * Resolve the notification inside the transaction: recipient = the order's store {@code email}
     * (blank → no recipient; delivery is skipped with a WARN — never a customer / salesperson / business
     * fallback). Content: order number, quote version, accepted name + time, accepted inc-GST total —
     * no attachment, link, token, signature, storage reference, cost or GP.
     */
    private NotificationPlan notificationPlan(SalesOrder order, QuoteVersionRow version,
                                              String acceptedCustomerName, LocalDateTime acceptedAt) {
        Store store = storeRepository.findByStoreIdAndBusinessId(order.getStoreId(), order.getBusinessId())
                .orElse(null);
        String recipient = store == null ? null : blankToNull(store.getEmail());
        String subject = "Quote accepted: " + order.getOrderNumber();
        String body = "Quote " + order.getOrderNumber() + " (version " + version.versionNumber()
                + ") was accepted online by " + acceptedCustomerName
                + " on " + DISPLAY_DATE_TIME.format(acceptedAt) + ".\n\n"
                + "Accepted total (inc GST): " + displayMoney(version.quoteTotalIncGst()) + ".\n\n"
                + "The signed quote is available in the sales portal.";
        return new NotificationPlan(recipient, subject, body, order.getOrderId(), version.versionNumber());
    }

    /**
     * Deliver the store notification AFTER the acceptance commit. A blank store email skips delivery
     * with a WARN; a transport failure (or any other sender runtime failure) is caught and logged at
     * WARN. Either way the committed acceptance (and the 201) stands. Logs carry ONLY the order id + quote version and a safe outcome — never the
     * recipient, the body, or the exception's content.
     */
    private void notifyStoreQuietly(NotificationPlan plan) {
        if (plan.recipient() == null) {
            log.warn("Quote acceptance store notification skipped: no store email for the order's store "
                    + "(order {} quote v{}; acceptance persisted)", plan.orderId(), plan.versionNumber());
            return;
        }
        try {
            notificationSender.send(new QuoteAcceptanceNotificationRequest(
                    plan.recipient(), plan.subject(), plan.body(), plan.orderId(), plan.versionNumber()));
        } catch (QuoteAcceptanceNotificationException ex) {
            log.warn("Quote acceptance store notification failed (order {} quote v{}; acceptance persisted)",
                    plan.orderId(), plan.versionNumber());
        } catch (RuntimeException ex) {
            // Any other sender failure is equally non-fatal here: the acceptance is ALREADY committed,
            // so surfacing a 500 would only make the customer retry into a 410 INACTIVE. Only the
            // exception type is logged — never its message or the notification content.
            log.warn("Quote acceptance store notification failed unexpectedly ({}) (order {} quote v{}; "
                    + "acceptance persisted)", ex.getClass().getSimpleName(), plan.orderId(), plan.versionNumber());
        }
    }

    private static String displayMoney(BigDecimal value) {
        BigDecimal scaled = (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, ROUNDING);
        String sign = scaled.signum() < 0 ? "-" : "";
        return sign + "$" + String.format(DISPLAY_LOCALE, "%,.2f", scaled.abs());
    }

    // ------------------------------------------------------------------
    // Helpers (duplicated per the locked duplicate-don't-extract decision)
    // ------------------------------------------------------------------

    private static void requireExactlyOneRow(int updated, String invariant) {
        if (updated != 1) {
            throw new IllegalStateException(invariant + " (" + updated + " rows)");
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * Delete a just-written file IFF the surrounding transaction does NOT commit (the
     * {@code OrderInvoiceService} / {@code QuoteSendService} rollback-cleanup pattern): the file is
     * written before its {@code stored_file} row, so a commit-time rollback would otherwise orphan it.
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

    /** The validated D6b header write (computed before any persistence). Server-internal. */
    private record AcceptedPriceWrite(BigDecimal priceAdjustmentIncGst,
                                      BigDecimal salePriceExGst,
                                      BigDecimal totalCost,
                                      BigDecimal gp,
                                      BigDecimal gpPercent) {
    }

    /** The store notification resolved inside the transaction, delivered after commit. Never serialized. */
    private record NotificationPlan(String recipient, String subject, String body,
                                    long orderId, int versionNumber) {
    }

    /**
     * The acceptance transaction's outcome: ACCEPTED (with the notification to deliver after commit),
     * a dead link state discovered under the lock (translated to its 410 AFTER commit so a lazy-expiry
     * flip persists), or the defensive missing-token case. Never serialized.
     */
    private record AcceptOutcome(NotificationPlan notification, String deadState, boolean tokenMissing) {

        static AcceptOutcome accepted(NotificationPlan notification) {
            return new AcceptOutcome(notification, null, false);
        }

        static AcceptOutcome dead(String state) {
            return new AcceptOutcome(null, state, false);
        }

        static AcceptOutcome missingToken() {
            return new AcceptOutcome(null, null, true);
        }
    }
}
