package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.OrderInvoiceService;
import com.flooring.salesportal.order.dto.InvoiceResponse;
import com.flooring.salesportal.order.quote.QuoteSendService.QuoteSignatureImage;
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
 * The public token surface is Phase 16E-C ({@code PublicQuoteController}). Phase 16F PR2 adds
 * {@code POST .../quote/create-invoice} (Path A), which delegates to
 * {@link OrderInvoiceService#createInvoiceFromAcceptedQuote} so the invoice is persisted by the
 * invoice service's own established pattern.
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
    private final OrderInvoiceService orderInvoiceService;

    public QuoteController(QuoteService quoteService,
                           QuoteSendService quoteSendService,
                           OrderInvoiceService orderInvoiceService) {
        this.quoteService = quoteService;
        this.quoteSendService = quoteSendService;
        this.orderInvoiceService = orderInvoiceService;
    }

    /** GET .../quote/workspace — draft + current_issued + accepted (latest ACCEPTED, 16F PR1). LAID read allowed. */
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
     * POST .../quote/send-email — issue (or resend) the quote and email it (link + body; link-only
     * since 16F PR1 — the issued PDF is stored, never attached). Empty body only. LAID write blocked (422); provider failure → 502 EMAIL_SEND_FAILED
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
     * POST .../quote/create-invoice (Phase 16F PR2, Path A): create an invoice version from the order's
     * latest ACCEPTED quote, inheriting its signature. Empty body only (the server selects the quote
     * version). Allowed when LAID; no email. 201 with the InvoiceDetail envelope and the message
     * "Invoice created from accepted quote."; 422 QUOTE_NOT_ACCEPTED / INVOICE_PRECONDITIONS_NOT_MET /
     * BUSINESS_RULE_VIOLATION; 409 INVOICE_ALREADY_ACCEPTED when the current invoice was signed at the
     * same time or later than the quote. All logic lives in {@link OrderInvoiceService}.
     */
    @PostMapping("/create-invoice")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceResponse> createInvoiceFromQuote(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return orderInvoiceService.createInvoiceFromAcceptedQuote(slug, orderId, body, httpRequest);
    }

    /**
     * GET .../quote/pdf?type=issued|accepted — stream a STORED quote PDF (salesperson download;
     * LAID read allowed). type=issued → the active issued version's stored PDF; type=accepted →
     * the latest accepted version's stored SIGNED PDF (16F PR1; portal-only). Missing artifact →
     * 404 QUOTE_PDF_NOT_FOUND. Raw binary like the preview.
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

    /**
     * GET .../quote/accepted/signature — stream the LATEST accepted quote version's stored signature
     * image (16F PR1; the quote analogue of the invoice signature download). Raw {@code image/png}
     * bytes, inline, backend-built file name {@code quote-signature-{order_number}-v{n}.png}. LAID read
     * allowed. No accepted version / no stored signature → 404 QUOTE_SIGNATURE_NOT_FOUND; errors use
     * the standard JSON wrapper. The workspace {@code accepted.signature_download_path} points here.
     */
    @GetMapping("/accepted/signature")
    public ResponseEntity<byte[]> downloadAcceptedSignature(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {
        QuoteSignatureImage signature = quoteSendService.downloadAcceptedSignature(slug, orderId, httpRequest);

        String contentDisposition = ContentDisposition.inline()
                .filename(signature.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(signature.mimeType()))
                .contentLength(signature.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(signature.bytes());
    }
}
