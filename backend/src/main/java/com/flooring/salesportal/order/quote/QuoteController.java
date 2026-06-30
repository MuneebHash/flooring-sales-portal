package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.quote.QuoteService.QuotePreviewResult;
import com.flooring.salesportal.order.quote.dto.QuoteDraftDto;
import com.flooring.salesportal.order.quote.dto.QuoteWorkspaceDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * <p>PR1 added the workspace GET and the draft PUT; PR2 adds the on-demand preview PDF
 * ({@code POST .../quote/preview-pdf}). Send (email/SMS), cancel, create-invoice, the stored-PDF
 * download, and the public token surface are Phase 16E / 16F.
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

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    /** GET .../quote/workspace — draft (issued/accepted are null in PR1). LAID read allowed. */
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
}
