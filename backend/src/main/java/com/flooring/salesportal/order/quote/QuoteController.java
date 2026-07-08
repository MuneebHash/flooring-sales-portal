package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.quote.QuoteSendService.QuoteStoredPdf;
import com.flooring.salesportal.order.quote.QuoteService.QuotePreviewResult;
import com.flooring.salesportal.order.quote.dto.QuoteDraftDto;
import com.flooring.salesportal.order.quote.dto.QuoteIssuedSummaryDto;
import com.flooring.salesportal.order.quote.dto.QuoteWorkspaceDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Phase 16C PR1 — protected quote money/data-core endpoints, scoped under one order
 * ({@code /api/v1/{slug}/orders/{orderId}/quote}). Thin controller: all session/scope/LAID gating,
 * validation, money, and persistence live in {@link QuoteService}.
 *
 * <p>{@code orderId} is captured as a {@code String} (same as the order/enquiry endpoints) so a
 * non-numeric / non-positive value flows through the service's manual validation and produces
 * {@code VALIDATION_FAILED} (field {@code order_id}). The draft body is taken as a raw {@code String}
 * so JSON parsing happens INSIDE the service, strictly after the guard / orderId / scoped-lookup /
 * 404 / LAID gates — so malformed JSON can never 400 ahead of them.
 *
 * <p>PR1 added the workspace GET and the draft PUT; PR2 added the on-demand preview PDF
 * ({@code POST .../quote/preview-pdf}); Phase 16E-A adds send-email / send-sms / cancel / the
 * stored issued-PDF download (all delivery/lifecycle logic lives in {@link QuoteSendService}).
 * Create-invoice and the public token surface are Phase 16F / 16E-C.
 *
 * <p>The preview returns a raw {@code ResponseEntity<byte[]>} (the file-binary exception, mirroring the
 * invoice file download D.4) rather than the {@code ApiResponse} envelope; its error paths still flow
 * through the standard JSON {@code GlobalExceptionHandler}. The body is taken as a raw {@code String}
 * ({@code required = false}) so it is validated inside the service strictly after the guard / orderId /
 * scoped-lookup gates — and the preview takes no meaningful body ({@code {}} / blank / absent only).
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}/quote")
public class QuoteController {

    private final QuoteService quoteService;
    private final QuoteSendService quoteSendService;

    public QuoteController(QuoteService quoteService, QuoteSendService quoteSendService) {
        this.quoteService = quoteService;
        this.quoteSendService = quoteSendService;
    }

    /** GET .../quote/workspace — draft + current_issued (accepted is null until 16F). LAID read allowed. */
    @GetMapping("/workspace")
    public ApiResponse<QuoteWorkspaceDto> getWorkspace(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(quoteService.getWorkspace(slug, orderId, httpRequest), "Quote workspace loaded.");
    }

    /** PUT .../quote/draft — full-replace upsert of the editable draft. LAID write blocked (422). */
    @PutMapping("/draft")
    public ApiResponse<QuoteDraftDto> upsertDraft(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(quoteService.saveDraft(slug, orderId, body, httpRequest), "Quote draft saved.");
    }

    /**
     * POST .../quote/preview-pdf — on-demand preview PDF of the current editable draft. Read-only, NOT
     * stored. 200 {@code application/pdf}; {@code Content-Disposition: inline; filename="quote-preview-
     * {order_number}.pdf"}. LAID allowed; below-cost allowed; no quote draft → 404 QUOTE_NOT_FOUND.
     */
    @PostMapping("/preview-pdf")
    public ResponseEntity<byte[]> previewPdf(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        QuotePreviewResult preview = quoteService.previewPdf(slug, orderId, body, httpRequest);

        // Raw binary (NOT ApiResponse): application/pdf, byte-length content-length, inline disposition
        // with the safe filename quote-preview-{order_number}.pdf. Spring RFC 5987-encodes the filename
        // with UTF-8. Nothing is stored — these bytes are generated on demand and streamed.
        String contentDisposition = ContentDisposition.inline()
                .filename(preview.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(preview.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(preview.bytes());
    }

    /**
     * POST .../quote/send-email — issue (or resend) the quote and email it (issued PDF + link +
     * body). Empty body only. LAID write blocked (422); provider failure → 502 EMAIL_SEND_FAILED
     * with the issued version/PDF/token kept. 201 issued summary (never the token).
     */
    @PostMapping("/send-email")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<QuoteIssuedSummaryDto> sendEmail(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return quoteSendService.sendEmail(slug, orderId, body, httpRequest);
    }

    /**
     * POST .../quote/send-sms — issue (or resend) the quote and SMS the public link only (no PDF).
     * Empty body only. LAID write blocked (422); provider failure → 502 SMS_SEND_FAILED with the
     * issued version/PDF/token kept. 201 issued summary (never the token).
     */
    @PostMapping("/send-sms")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<QuoteIssuedSummaryDto> sendSms(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return quoteSendService.sendSms(slug, orderId, body, httpRequest);
    }

    /**
     * POST .../quote/cancel — cancel the active issued quote (version + token → CANCELLED; rows
     * kept). Empty body only. ALLOWED when LAID (narrow exception — kills a public link only).
     * 422 QUOTE_NOT_ISSUED / 409 QUOTE_ALREADY_ACCEPTED. 200 cancelled summary.
     */
    @PostMapping("/cancel")
    public ApiResponse<QuoteIssuedSummaryDto> cancel(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return quoteSendService.cancel(slug, orderId, body, httpRequest);
    }

    /**
     * GET .../quote/pdf?type=issued|accepted — stream a STORED quote PDF (salesperson download;
     * LAID read allowed). type=issued → the active issued version's stored PDF; type=accepted →
     * the 16F signed PDF (always 404 QUOTE_PDF_NOT_FOUND in 16E-A). Raw binary like the preview.
     */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> downloadStoredPdf(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestParam(value = "type", required = false) String type,
            HttpServletRequest httpRequest) {
        QuoteStoredPdf pdf = quoteSendService.downloadStoredPdf(slug, orderId, type, httpRequest);

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
