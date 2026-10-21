package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.PublicInvoiceService.PublicInvoicePdf;
import com.flooring.salesportal.order.dto.PublicInvoiceAcceptResultDto;
import com.flooring.salesportal.order.dto.PublicInvoiceViewDto;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.nio.charset.StandardCharsets;

/**
 * Phase 16G PR 4 - the PUBLIC invoice endpoints ({@code /api/v1/public/invoices/{token}}; 16G contract §9):
 * open the order's current invoice, sign it remotely, and stream its stored PDF. Slugless and token-only:
 * the secret invoice token is the ONLY credential. Like {@code PublicQuoteController} this controller takes
 * no {@code slug}, never touches the {@code HttpSession} and never calls {@code RequestContextGuard} (the
 * "public" first path segment is a reserved business slug, so these mappings never collide with a tenant
 * route). There is no viewed-tracking endpoint. All resolution, state and leak rules live in
 * {@link PublicInvoiceService}.
 *
 * <p>The accept takes the raw {@code MultipartHttpServletRequest} (nothing is bound here) so the token gate
 * runs before any part is validated; the pdf {@code version_number} is taken as an optional raw string for
 * the same reason. The pdf returns a raw {@code ResponseEntity<byte[]>} (the file-binary exception); every
 * error path still flows through the standard JSON {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/public/invoices")
public class PublicInvoiceController {

    private final PublicInvoiceService publicInvoiceService;

    public PublicInvoiceController(PublicInvoiceService publicInvoiceService) {
        this.publicInvoiceService = publicInvoiceService;
    }

    /**
     * GET /public/invoices/{token} - 200 {@code Invoice loaded.} with a {@code state} for every FOUND token
     * (ACTIVE = the current invoice document; REPLACED / REWRITTEN / SIGNED / EXPIRED = the minimal payload
     * and message); 404 INVOICE_TOKEN_NOT_FOUND for an unknown or malformed token. Lazily expires an
     * over-age ACTIVE token; nothing is stamped on a read.
     */
    @GetMapping("/{token}")
    public ApiResponse<PublicInvoiceViewDto> getPublicInvoice(@PathVariable String token) {
        return publicInvoiceService.getView(token);
    }

    /**
     * POST /public/invoices/{token}/accept - the customer signs the invoice version the page shows.
     * {@code multipart/form-data} with exactly the {@code expected_version_number} form field and one
     * {@code signature} PNG part. 201 {@code {"state": "SIGNED"}} {@code Invoice accepted.}; 404
     * INVOICE_TOKEN_NOT_FOUND; 410 INVOICE_LINK_* (gated BEFORE body validation); 400 VALIDATION_FAILED /
     * SIGNATURE_INVALID; 409 INVOICE_VERSION_STALE; 422 SIGNATURE_REQUIRED / ACCEPTED_CUSTOMER_NAME_REQUIRED.
     * Allowed when the order is LAID.
     */
    @PostMapping(path = "/{token}/accept", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PublicInvoiceAcceptResultDto> acceptPublicInvoice(
            @PathVariable String token,
            MultipartHttpServletRequest request) {
        return publicInvoiceService.accept(token, request);
    }

    /**
     * GET /public/invoices/{token}/pdf?version_number=N - the STORED PDF of the version the page shows
     * (never regenerated; no other version reachable). 400 VALIDATION_FAILED (version_number); 404
     * INVOICE_TOKEN_NOT_FOUND; 409 INVOICE_VERSION_STALE; 410 INVOICE_LINK_*. Raw binary with
     * {@code Content-Disposition: inline; filename="invoice-{order_number}-v{version_number}.pdf"} (the stored
     * name, 16G contract §9.5), fetched without credentials by the public page.
     */
    @GetMapping("/{token}/pdf")
    public ResponseEntity<byte[]> downloadPublicInvoicePdf(
            @PathVariable String token,
            @RequestParam(name = "version_number", required = false) String versionNumber) {
        PublicInvoicePdf pdf = publicInvoiceService.downloadPdf(token, versionNumber);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, inlineDisposition(pdf.fileName()))
                .body(pdf.bytes());
    }

    /**
     * The contract's exact {@code inline; filename="..."} header for the stored (ASCII) name; a stored name
     * outside printable ASCII - impossible with the locked order-number format - falls back to Spring's
     * UTF-8 (RFC 5987) encoding so it is still transmitted safely.
     */
    private static String inlineDisposition(String fileName) {
        boolean printableAscii = fileName.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
        ContentDisposition disposition = printableAscii
                ? ContentDisposition.inline().filename(fileName).build()
                : ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build();
        return disposition.toString();
    }
}
