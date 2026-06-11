package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.OrderInvoiceService.InvoiceFileDownload;
import com.flooring.salesportal.order.dto.InvoiceResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.nio.charset.StandardCharsets;

/**
 * Phase 12 Chunk 4 invoice endpoints: Branch A — create (D.1 POST /orders/{orderId}/invoices);
 * Branch B — read the current invoice (D.3 GET /orders/{orderId}/invoices/current) and stream its PDF
 * (D.4 GET /orders/{orderId}/invoices/current/file); Branch C — rewrite the current invoice (D.2 POST
 * /orders/{orderId}/invoices/rewrite). Shares the {@code /orders/{orderId}} base path with the other
 * order sub-resource controllers; mappings stay distinct by method + the {@code /invoices...} sub-path.
 *
 * <p>{@code orderId} is captured as a {@code String} (matching the rest of the order endpoints) so a
 * non-numeric / non-positive value becomes VALIDATION_FAILED on {@code order_id} in the service
 * rather than a path-binding error. The create / rewrite bodies are taken as a raw {@code String}
 * ({@code required = false}) so they are parsed inside the service strictly after the guard / order-scope
 * (and, for rewrite, LAID) gates (D.1 / D.2 take no body — {@code {}} only). The JSON endpoints return
 * the service-built {@code ApiResponse} envelope; D.4 returns a raw {@code ResponseEntity<byte[]>} (the
 * file-binary exception, mirroring D.17 attachment download) while its error paths still flow through the
 * standard JSON {@code GlobalExceptionHandler}. Payments (D.6/D.7) are a later branch.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}")
public class OrderInvoiceController {

    private final OrderInvoiceService orderInvoiceService;

    public OrderInvoiceController(OrderInvoiceService orderInvoiceService) {
        this.orderInvoiceService = orderInvoiceService;
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceResponse> createInvoice(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {

        return orderInvoiceService.createInvoice(slug, orderId, body, httpRequest);
    }

    @PostMapping("/invoices/rewrite")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceResponse> rewriteInvoice(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {

        return orderInvoiceService.rewriteInvoice(slug, orderId, body, httpRequest);
    }

    @GetMapping("/invoices/current")
    public ApiResponse<InvoiceResponse> getCurrentInvoice(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {

        return orderInvoiceService.getCurrentInvoice(slug, orderId, httpRequest);
    }

    @GetMapping("/invoices/current/file")
    public ResponseEntity<byte[]> downloadCurrentInvoiceFile(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {

        InvoiceFileDownload download =
                orderInvoiceService.downloadCurrentInvoiceFile(slug, orderId, httpRequest);

        // Raw binary (NOT ApiResponse): stored MIME type (application/pdf), stored size, inline
        // disposition with the safe stored file name (D.4: invoice-{order_number}-v{version}.pdf, which
        // is exactly stored_file.file_name). Spring's ContentDisposition RFC 5987-encodes the filename
        // with UTF-8 so it is transmitted safely; storage_path is never exposed.
        String contentDisposition = ContentDisposition.inline()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(download.bytes());
    }

    /**
     * D.8 Accept (Phase 13 §5.1): the only multipart endpoint on this controller (file-binary
     * exception, mirroring the attachment upload). The {@code MultipartHttpServletRequest} is also the
     * {@code HttpServletRequest} the service guard reads the session from; the {@code signature} file
     * part + {@code accepted_customer_name} form field are validated inside the service. 201 on
     * acceptance regardless of the auto-email outcome (an email failure is reported via the message +
     * {@code last_emailed_at = null}, never an error status).
     */
    @PostMapping(path = "/invoices/current/accept", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceResponse> acceptCurrentInvoice(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            MultipartHttpServletRequest request) {

        return orderInvoiceService.acceptCurrentInvoice(slug, orderId, request);
    }

    /** D.9 Resend (Phase 13 §5.2): 200 on success; 502 EMAIL_SEND_FAILED when the provider fails. */
    @PostMapping("/invoices/current/resend")
    public ApiResponse<InvoiceResponse> resendCurrentInvoice(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {

        return orderInvoiceService.resendCurrentInvoice(slug, orderId, body, httpRequest);
    }

    /**
     * D.10 signature download (Phase 13 §5.3): raw {@code image/png} bytes (file-binary exception,
     * same header recipe as D.4). The inline filename is
     * {@code signature-{order_number}-v{version_number}.png}, built by the service from the locked
     * order number + the CURRENT version; storage_path is never exposed.
     */
    @GetMapping("/invoices/current/signature")
    public ResponseEntity<byte[]> downloadCurrentSignature(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {

        InvoiceFileDownload download =
                orderInvoiceService.downloadCurrentSignature(slug, orderId, httpRequest);

        String contentDisposition = ContentDisposition.inline()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(download.bytes());
    }
}
