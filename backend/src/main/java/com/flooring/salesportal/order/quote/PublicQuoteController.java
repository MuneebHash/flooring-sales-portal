package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.quote.PublicQuoteService.PublicQuotePdf;
import com.flooring.salesportal.order.quote.dto.PublicQuoteViewDto;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Phase 16E-C — the PUBLIC quote endpoints ({@code /api/v1/public/quotes/{token}}): open, mark
 * viewed, and download the stored issued PDF (contract §7.2). Slugless and token-only: the secret
 * token is the ONLY credential. This controller deliberately takes no {@code slug}, never touches
 * the {@code HttpSession}, and never calls {@code RequestContextGuard} — the same posture as
 * {@link com.flooring.salesportal.tenant.PublicBusinessController} (the "public" first path
 * segment is a reserved business slug precisely so these mappings can never collide with a
 * tenant route). All resolution/state/leak rules live in {@link PublicQuoteService}.
 *
 * <p>The 16F accept (remote signing) endpoint does NOT exist here — nothing on this surface can
 * mutate a quote beyond the contract's lazy expiry and the write-once {@code viewed_at} stamp.
 *
 * <p>The viewed body is taken as a raw {@code String} (required = false) so it is validated
 * inside the service strictly AFTER the token gates (missing/blank/{@code {}} accepted — the
 * sibling empty-body rule). The PDF returns a raw {@code ResponseEntity<byte[]>} (file-binary
 * exception); its error paths still flow through the standard JSON {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/public/quotes")
public class PublicQuoteController {

    private final PublicQuoteService publicQuoteService;

    public PublicQuoteController(PublicQuoteService publicQuoteService) {
        this.publicQuoteService = publicQuoteService;
    }

    /**
     * GET /public/quotes/{token} — open the quote. 200 with a {@code state} for every FOUND token
     * (ACTIVE = full document payload; EXPIRED/SUPERSEDED/CANCELLED/INACTIVE = minimal payload +
     * message); 404 QUOTE_TOKEN_NOT_FOUND for an unknown or malformed token. Never stamps
     * viewed_at (that is the viewed POST); lazily expires an over-age ACTIVE token on access.
     */
    @GetMapping("/{token}")
    public ApiResponse<PublicQuoteViewDto> getPublicQuote(@PathVariable String token) {
        return publicQuoteService.getView(token);
    }

    /**
     * POST /public/quotes/{token}/viewed — mark the first public view (write-once viewed_at;
     * idempotent). Empty body only. 200 refreshed view; 410 QUOTE_LINK_* on a dead link; 404
     * QUOTE_TOKEN_NOT_FOUND on an unknown/malformed token.
     */
    @PostMapping("/{token}/viewed")
    public ApiResponse<PublicQuoteViewDto> markViewed(
            @PathVariable String token,
            @RequestBody(required = false) String body) {
        return publicQuoteService.markViewed(token, body);
    }

    /**
     * GET /public/quotes/{token}/pdf — stream the STORED issued PDF while the link is ACTIVE
     * (never regenerated; the signed PDF is never served publicly). 410 QUOTE_LINK_* on a dead
     * link; 404 QUOTE_TOKEN_NOT_FOUND / QUOTE_PDF_NOT_FOUND. Raw binary, inline disposition —
     * fetched WITHOUT credentials by the public page (no session, no cookies).
     */
    @GetMapping("/{token}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable String token) {
        PublicQuotePdf pdf = publicQuoteService.downloadPdf(token);

        String contentDisposition = ContentDisposition.inline()
                .filename(pdf.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(pdf.bytes());
    }
}
