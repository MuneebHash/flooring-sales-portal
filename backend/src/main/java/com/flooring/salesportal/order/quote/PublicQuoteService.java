package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.GoneException;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.SalesOrder;
import com.flooring.salesportal.order.SalesOrderRepository;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteFile;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteTokenRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionLineRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionRow;
import com.flooring.salesportal.order.quote.dto.PublicQuoteViewDto;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessInvoiceConfigView;
import com.flooring.salesportal.tenant.BusinessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 16E-C — the public, token-only quote read surface (contract §7.2/§8): open the quote,
 * mark first view, and stream the stored issued PDF. This is a NEW, SEPARATE unauthenticated
 * surface authenticated ONLY by the secret token — it deliberately never touches the session,
 * never calls {@code RequestContextGuard}, takes no slug, and can expose nothing beyond the
 * single quote behind the presented token. Signing/acceptance is Phase 16F and does not exist
 * here.
 *
 * <p><b>Token resolution (§8).</b> The presented token's basic shape is validated first
 * (URL-safe Base64, minted length or longer — anything else is indistinguishable from unknown:
 * 404 {@code QUOTE_TOKEN_NOT_FOUND}, no leak); it is then SHA-256 hashed and looked up by the
 * unique {@code token_hash} index, and the stored hash is re-verified with the constant-time
 * {@link MessageDigest#isEqual} before the row is trusted. The plaintext token is never logged
 * and never stored.
 *
 * <p><b>Lazy expiry (§8, locked).</b> ONLY an {@code ACTIVE} token past {@code expires_at}
 * expires on access: the token flips to {@code EXPIRED} + {@code dead_at} and its {@code ISSUED}
 * version flips to {@code EXPIRED}. The flip serialises with the protected send/cancel
 * transitions by taking the SAME order row lock first and re-reading the token under it (see
 * {@link #resolve}), and both updates stay status-guarded so an already-dead row is structurally
 * untouchable. The flip is committed in its own resolution transaction BEFORE any 410 is thrown,
 * so a rejected request still persists the expiry (an exception thrown inside the same
 * transaction would roll it back).
 *
 * <p><b>GET vs actions (§12 clarification).</b> The GET returns <b>200 with a state</b> for a
 * FOUND but dead token (the customer holds the link, so the state message is intended); only an
 * unknown/malformed token is 404. The viewed/pdf ACTIONS on a non-ACTIVE token throw the matching
 * 410 ({@code QUOTE_LINK_EXPIRED} / {@code _SUPERSEDED} / {@code _CANCELLED} / {@code _INACTIVE}).
 *
 * <p><b>Snapshot-only body.</b> The ACTIVE payload's quote content comes exclusively from the
 * FROZEN issued snapshot ({@code quote_version} totals/flags, {@code quote_version_line} rows,
 * {@code terms_snapshot}, {@code details_of_sale_snapshot}, {@code flooring_type_snapshot}, and —
 * since V17 (Codex P1 fix) — the {@code customer_*_snapshot} "Quotation To" columns frozen at
 * issue). This service reads NO live order_customer/order_address row: a pre-LAID customer edit
 * after issue can never leak the new person's name/billing to the old token holder. The only live
 * reads are the tenant's OWN self-data (business name/logo/accent/ABN + payment config), exactly
 * as the issued-PDF assembler reads them. The PDF endpoint streams the STORED issued bytes
 * verbatim — nothing is ever regenerated here.
 */
@Service
public class PublicQuoteService {

    private static final String TOKEN_ACTIVE = "ACTIVE";
    private static final String TOKEN_REPLACED = "REPLACED";
    private static final String TOKEN_SUPERSEDED = "SUPERSEDED";
    private static final String TOKEN_EXPIRED = "EXPIRED";
    private static final String TOKEN_CANCELLED = "CANCELLED";
    private static final String TOKEN_CONSUMED = "CONSUMED";

    private static final String STATE_ACTIVE = "ACTIVE";
    private static final String STATE_EXPIRED = "EXPIRED";
    private static final String STATE_SUPERSEDED = "SUPERSEDED";
    private static final String STATE_CANCELLED = "CANCELLED";
    private static final String STATE_INACTIVE = "INACTIVE";

    // Minted tokens are 32 CSPRNG bytes -> 43 URL-safe Base64 chars (no padding). Anything
    // shorter, longer than a defensive cap, or off the URL-safe alphabet cannot be a real token
    // and is rejected as QUOTE_TOKEN_NOT_FOUND without touching the database (same 404 as an
    // unknown token — malformed and unknown must be indistinguishable).
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    // Display-only 40% deposit — the exact QuotePdfGenerator rate/math (duplicated per the locked
    // duplicate-don't-extract decision) so the web document never drifts a cent from the PDF.
    private static final BigDecimal DEPOSIT_RATE = new BigDecimal("0.40");

    // Locked 16E-C response messages (wrapper level; data.message carries the per-state text).
    private static final String LOADED_MESSAGE = "Quote loaded.";
    private static final String VIEWED_MESSAGE = "Quote view recorded.";

    private final QuoteVersionRepository quoteVersionRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final BusinessRepository businessRepository;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;
    // Programmatic transactions (the QuoteSendService pattern): the lazy-expiry flip must COMMIT
    // before a 410 is thrown, so resolution cannot share a declarative transaction with the throw.
    private final TransactionTemplate transactionTemplate;

    public PublicQuoteService(QuoteVersionRepository quoteVersionRepository,
                              SalesOrderRepository salesOrderRepository,
                              BusinessRepository businessRepository,
                              FileStorageService fileStorageService,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.quoteVersionRepository = quoteVersionRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.businessRepository = businessRepository;
        this.fileStorageService = fileStorageService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // GET /public/quotes/{token}
    // ------------------------------------------------------------------

    /**
     * Open the quote (contract §7.2). ACTIVE → the full document payload. A found-but-dead token →
     * 200 with the minimal payload (state + business name + customer-facing message — no quote
     * content). Unknown/malformed → 404 {@code QUOTE_TOKEN_NOT_FOUND}. Deliberately does NOT stamp
     * {@code viewed_at} — first-view marking is the documented viewed POST's job.
     */
    public ApiResponse<PublicQuoteViewDto> getView(String token) {
        ResolvedToken resolved = resolve(token);
        if (!STATE_ACTIVE.equals(resolved.state())) {
            return ApiResponse.ok(inTx(() -> buildDeadView(resolved)), LOADED_MESSAGE);
        }
        return ApiResponse.ok(inTx(() -> buildActiveView(resolved)), LOADED_MESSAGE);
    }

    // ------------------------------------------------------------------
    // POST /public/quotes/{token}/viewed
    // ------------------------------------------------------------------

    /**
     * Mark the first public view (contract §7.2): stamps {@code quote_version.viewed_at} exactly
     * once (the repository's {@code IS NULL} guard makes every later call a no-op — idempotent, no
     * overwrite, no view count). Only meaningful while ACTIVE — a dead link → the matching 410; an
     * unknown token → 404. Body must be empty ({@code {}} / blank / absent), validated AFTER the
     * token gates like every sibling empty-body endpoint. Returns the refreshed ACTIVE view.
     */
    public ApiResponse<PublicQuoteViewDto> markViewed(String token, String body) {
        ResolvedToken resolved = resolve(token);
        requireActive(resolved);
        rejectNonEmptyBody(body);

        LocalDateTime now = LocalDateTime.now();
        PublicQuoteViewDto dto = inTx(() -> {
            quoteVersionRepository.stampViewedAtOnce(resolved.token().quoteVersionId(), now);
            return buildActiveView(resolved);
        });
        return ApiResponse.ok(dto, VIEWED_MESSAGE);
    }

    // ------------------------------------------------------------------
    // GET /public/quotes/{token}/pdf
    // ------------------------------------------------------------------

    /**
     * Stream the STORED issued quote PDF while the link is ACTIVE (contract §7.2): the exact
     * frozen bytes persisted at issue — never regenerated, never rendered from live draft/terms.
     * Dead link → the matching 410; unknown token → 404 {@code QUOTE_TOKEN_NOT_FOUND}; an ACTIVE
     * version whose stored artifact is missing → 404 {@code QUOTE_PDF_NOT_FOUND} (the documented
     * missing-artifact response). The signed PDF is NEVER served here — portal-only (16F).
     */
    public PublicQuotePdf downloadPdf(String token) {
        ResolvedToken resolved = resolve(token);
        requireActive(resolved);

        QuoteFile file = inTx(() ->
                quoteVersionRepository.findIssuedFileByVersionId(resolved.token().quoteVersionId())
                        .orElse(null));
        if (file == null) {
            throw new NotFoundException(ErrorCode.QUOTE_PDF_NOT_FOUND,
                    ErrorCode.QUOTE_PDF_NOT_FOUND.defaultMessage());
        }
        // Missing-on-disk -> UncheckedIOException -> generic 500 via the standard JSON wrapper
        // (the invoice D.4 / protected quote-pdf posture). storage_path is never returned.
        byte[] bytes = fileStorageService.read(file.storagePath());
        return new PublicQuotePdf(bytes, file.fileName());
    }

    /** Carrier for the public stored-PDF binary response (name is the stored quote-...-v{n}.pdf). */
    public record PublicQuotePdf(byte[] bytes, String fileName) {
    }

    // ------------------------------------------------------------------
    // Token resolution (§8) — shape gate, hash lookup, constant-time verify, lazy expiry
    // ------------------------------------------------------------------

    /**
     * Resolve the presented token to its effective public state. Runs in its OWN committed
     * transaction so a lazy-expiry flip survives any 404/410 thrown by the caller afterwards —
     * the not-found paths RETURN null from the callback (nothing to persist) rather than throwing
     * inside it, so the transaction is never marked rollback-only by a mere miss. The
     * malformed-shape gate and the unknown-hash miss both produce the identical 404 — a caller
     * can never distinguish "bad shape" from "not in the database".
     *
     * <p><b>Expiry serialisation (16E-C review P2 fix).</b> When the pre-check sees an over-age
     * ACTIVE token, the flip does NOT proceed on that possibly-stale read: the version's ORDER
     * row is locked first — the exact lock every protected send/cancel transaction holds — and
     * the token is RE-READ under it. Only a token that is still ACTIVE under the lock is flipped
     * (with its ISSUED version); a token that lost the race to a concurrent cancel/resend is
     * reported with its TRUE post-race state (CANCELLED/REPLACED/…), never assumed EXPIRED. This
     * makes it impossible for a resend to mint an ACTIVE token on a version lazy expiry just
     * killed, for a cancel's strict {@code moveIssuedVersionTo} to 500 on a concurrently-expired
     * version, or for the token/version row locks to deadlock on inverted acquisition order —
     * both sides now serialise on the order row before touching either. An over-age token's
     * {@code expires_at} is immutable, so the pre-lock "past expiry" fact cannot go stale.
     */
    private ResolvedToken resolve(String token) {
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            throw tokenNotFound();
        }
        String presentedHash = sha256Hex(token);

        ResolvedToken resolved = inTx(() -> {
            QuoteTokenRow row = quoteVersionRepository.findTokenByHash(presentedHash).orElse(null);
            // Constant-time re-verification of the stored hash (contract §8). The keyed lookup
            // already matched, so a mismatch is defensive-only — treated as not found.
            if (row == null || !MessageDigest.isEqual(
                    presentedHash.getBytes(StandardCharsets.UTF_8),
                    row.tokenHash().getBytes(StandardCharsets.UTF_8))) {
                return null;
            }

            String effectiveStatus = row.status();
            if (TOKEN_ACTIVE.equals(effectiveStatus)
                    && LocalDateTime.now().isAfter(row.expiresAt())) {
                // Lazy expiry — serialise with the protected order-locked transitions, then
                // re-read and flip only what is STILL an over-age ACTIVE token (class javadoc).
                long versionId = row.quoteVersionId();
                long orderId = quoteVersionRepository.findById(versionId)
                        .map(QuoteVersionRow::orderId)
                        .orElseThrow(() -> new IllegalStateException(
                                "quote_version disappeared for quote_version_id: " + versionId));
                quoteVersionRepository.lockOrderRowForPublicTransition(orderId);

                QuoteTokenRow locked = quoteVersionRepository.findTokenByHash(presentedHash)
                        .orElse(null);
                if (locked == null) {
                    // Rows are never deleted — FK-impossible; treated as not found defensively.
                    return null;
                }
                row = locked;
                effectiveStatus = locked.status();
                if (TOKEN_ACTIVE.equals(effectiveStatus)) {
                    // Still ACTIVE under the order lock (and still over-age — expires_at is
                    // immutable on a token row): flip token + ISSUED version. ONLY an ACTIVE
                    // token can expire; a dead row is structurally untouchable (guarded UPDATEs).
                    LocalDateTime now = LocalDateTime.now();
                    if (quoteVersionRepository.expireActiveToken(locked.quoteTokenId(), now)) {
                        quoteVersionRepository.expireIssuedVersion(locked.quoteVersionId());
                    }
                    effectiveStatus = TOKEN_EXPIRED;
                }
                // else: a concurrent cancel/resend killed the token first — report the TRUE
                // post-race state (CANCELLED/REPLACED/…), never an assumed EXPIRED.
            }
            return new ResolvedToken(row, toPublicState(effectiveStatus));
        });
        if (resolved == null) {
            throw tokenNotFound();
        }
        return resolved;
    }

    /** Customer-facing state from the token status (contract §4.5 table). */
    private static String toPublicState(String tokenStatus) {
        return switch (tokenStatus) {
            case TOKEN_ACTIVE -> STATE_ACTIVE;
            case TOKEN_REPLACED, TOKEN_SUPERSEDED -> STATE_SUPERSEDED;
            case TOKEN_EXPIRED -> STATE_EXPIRED;
            case TOKEN_CANCELLED -> STATE_CANCELLED;
            case TOKEN_CONSUMED -> STATE_INACTIVE;
            default -> throw new IllegalStateException("Unknown quote_token status: " + tokenStatus);
        };
    }

    /** The viewed/pdf action gate: any non-ACTIVE state → its documented 410 (contract §12). */
    private static void requireActive(ResolvedToken resolved) {
        ErrorCode code = switch (resolved.state()) {
            case STATE_ACTIVE -> null;
            case STATE_EXPIRED -> ErrorCode.QUOTE_LINK_EXPIRED;
            case STATE_SUPERSEDED -> ErrorCode.QUOTE_LINK_SUPERSEDED;
            case STATE_CANCELLED -> ErrorCode.QUOTE_LINK_CANCELLED;
            default -> ErrorCode.QUOTE_LINK_INACTIVE;
        };
        if (code != null) {
            throw new GoneException(code, code.defaultMessage());
        }
    }

    /** The customer-facing message for a dead state on the 200 GET (same text as the 410 codes). */
    private static String deadStateMessage(String state) {
        return switch (state) {
            case STATE_EXPIRED -> ErrorCode.QUOTE_LINK_EXPIRED.defaultMessage();
            case STATE_SUPERSEDED -> ErrorCode.QUOTE_LINK_SUPERSEDED.defaultMessage();
            case STATE_CANCELLED -> ErrorCode.QUOTE_LINK_CANCELLED.defaultMessage();
            default -> ErrorCode.QUOTE_LINK_INACTIVE.defaultMessage();
        };
    }

    private static NotFoundException tokenNotFound() {
        return new NotFoundException(ErrorCode.QUOTE_TOKEN_NOT_FOUND,
                ErrorCode.QUOTE_TOKEN_NOT_FOUND.defaultMessage());
    }

    /** The resolved token row plus its EFFECTIVE public state (post lazy expiry). Server-internal. */
    private record ResolvedToken(QuoteTokenRow token, String state) {
    }

    // ------------------------------------------------------------------
    // Payload assembly
    // ------------------------------------------------------------------

    /**
     * The full ACTIVE document payload. Quote BODY fields — including, since V17, the
     * "Quotation To" customer name + billing lines — come from the frozen issued snapshot only;
     * the ONLY live reads are the tenant's own branding/payment self-data (the sanctioned set).
     * The line set is re-gated on the snapshot's itemised flag (structurally empty for a
     * non-itemised issue — §6.1).
     */
    private PublicQuoteViewDto buildActiveView(ResolvedToken resolved) {
        long quoteVersionId = resolved.token().quoteVersionId();
        QuoteVersionRow version = quoteVersionRepository.findById(quoteVersionId)
                .orElseThrow(() -> new IllegalStateException(
                        "quote_version disappeared for public token: " + quoteVersionId));
        SalesOrder order = salesOrderRepository.findById(version.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "sales_order disappeared for quote_version: " + quoteVersionId));
        Business business = businessRepository.findById(order.getBusinessId())
                .orElseThrow(() -> new IllegalStateException(
                        "business disappeared for sales_order: " + version.orderId()));

        // The SAME business presentation context the issued PDF reads (QuotePdfModelAssembler):
        // ABN + the direct-deposit payment fields come from the invoice-config view, blank-hidden.
        // The Stripe payment link is the Payments-tab helper link, passed through the same
        // safe-HTTPS-URL treatment PaymentsTab.safeStripePaymentLinkUrl applies. Never any other
        // config field (terms/template stay off this surface — terms ride the frozen snapshot).
        BusinessInvoiceConfigView config = businessRepository
                .findInvoiceConfigByBusinessId(order.getBusinessId())
                .orElse(null);
        String businessAbn = blankToNull(config == null ? null : config.getAbn());
        String paymentAccountName = blankToNull(config == null ? null : config.getAccountName());
        String paymentBankName = blankToNull(config == null ? null : config.getBankName());
        String paymentBsb = blankToNull(config == null ? null : config.getBsb());
        String paymentAccountNumber = blankToNull(config == null ? null : config.getAccountNumber());
        String paymentStripeLinkUrl =
                safeStripeLinkUrl(config == null ? null : config.getStripePaymentLinkUrl());

        List<PublicQuoteViewDto.Line> lines = version.itemised()
                ? quoteVersionRepository.findVersionLines(quoteVersionId).stream()
                        .map(PublicQuoteService::toPublicLine)
                        .toList()
                : List.<PublicQuoteViewDto.Line>of();

        BigDecimal ex = money(version.quoteTotalExGst());
        BigDecimal inc = money(version.quoteTotalIncGst());

        return new PublicQuoteViewDto(
                STATE_ACTIVE,
                business.getName(),
                blankToNull(business.getLogoPath()),
                blankToNull(business.getAccentColour()),
                businessAbn,
                paymentAccountName,
                paymentBankName,
                paymentBsb,
                paymentAccountNumber,
                paymentStripeLinkUrl,
                order.getOrderNumber(),
                version.flooringTypeSnapshot(),
                blankToNull(version.customerNameSnapshot()),
                blankToNull(version.customerAddressLine1Snapshot()),
                blankToNull(version.customerAddressLine2Snapshot()),
                blankToNull(version.detailsOfSaleSnapshot()),
                version.itemised(),
                lines,
                ex,
                inc.subtract(ex),
                inc,
                money(inc.multiply(DEPOSIT_RATE)),
                blankToNull(version.termsSnapshot()),
                resolved.token().expiresAt(),
                null);
    }

    /**
     * The minimal dead-link payload (contract §7.2): state + business display name + the
     * customer-facing message. Every document field is null — a dead link renders NO quote
     * content. The business name is resolved through the token's version → order chain
     * (defensively null if any link is missing; the token row itself stays authoritative).
     */
    private PublicQuoteViewDto buildDeadView(ResolvedToken resolved) {
        String businessName = quoteVersionRepository.findById(resolved.token().quoteVersionId())
                .flatMap(version -> salesOrderRepository.findById(version.orderId()))
                .flatMap(order -> businessRepository.findById(order.getBusinessId()))
                .map(Business::getName)
                .orElse(null);
        return new PublicQuoteViewDto(
                resolved.state(),
                businessName,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                deadStateMessage(resolved.state()));
    }

    private static PublicQuoteViewDto.Line toPublicLine(QuoteVersionLineRow row) {
        return new PublicQuoteViewDto.Line(
                row.description(),
                row.quantity(),
                row.unitPriceExGst(),
                row.lineTotalExGst());
    }

    // ------------------------------------------------------------------
    // Helpers (duplicated per the locked duplicate-don't-extract decision)
    // ------------------------------------------------------------------

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    /** SHA-256 hex of the presented token — matches QuoteSendService's stored-hash derivation. */
    private static String sha256Hex(String plainToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plainToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, ROUNDING);
    }

    // The customer-name/billing-line derivation helpers moved to QuoteSendService (V17): the
    // "Quotation To" identity is now frozen into quote_version at ISSUE and this service renders
    // the snapshot columns verbatim — no live order_customer/order_address read exists here.

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * The PaymentsTab {@code safeStripePaymentLinkUrl} treatment, server-side: blank → null;
     * anything that does not parse as an ABSOLUTE {@code https:} URL → null (a relative value has
     * no scheme and fails the check). Never rewrites the value — it is returned trimmed verbatim
     * or suppressed entirely, so a mis-configured link can never render as a clickable non-HTTPS
     * (or javascript:) anchor on the public page.
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
     * Empty-body rule for the viewed POST — the exact sibling semantics
     * ({@code QuoteSendService.rejectNonEmptyBody}): missing/blank/{@code {}} accepted; malformed
     * JSON → 400 {@code MALFORMED_JSON}; a non-empty object or ANY non-object JSON → 400
     * {@code VALIDATION_FAILED}. Called strictly AFTER the token-resolution + ACTIVE gates.
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
}
