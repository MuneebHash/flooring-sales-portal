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

import java.nio.charset.StandardCharsets;

/**
 * Phase 12 Chunk 4 invoice endpoints: Branch A — create (D.1 POST /orders/{orderId}/invoices);
 * Branch B — read the current invoice (D.3 GET /orders/{orderId}/invoices/current) and stream its PDF
 * (D.4 GET /orders/{orderId}/invoices/current/file). Shares the {@code /orders/{orderId}} base path
 * with the other order sub-resource controllers; mappings stay distinct by method + the
 * {@code /invoices...} sub-path.
 *
 * <p>{@code orderId} is captured as a {@code String} (matching the rest of the order endpoints) so a
 * non-numeric / non-positive value becomes VALIDATION_FAILED on {@code order_id} in the service
 * rather than a path-binding error. The create body is taken as a raw {@code String}
 * ({@code required = false}) so it is parsed inside the service strictly after the guard / order-scope
 * gates (D.1 takes no body — {@code {}} only). The two JSON endpoints return the service-built
 * {@code ApiResponse} envelope; D.4 returns a raw {@code ResponseEntity<byte[]>} (the file-binary
 * exception, mirroring D.17 attachment download) while its error paths still flow through the standard
 * JSON {@code GlobalExceptionHandler}. Rewrite (D.2) and payments (D.6/D.7) are later branches.
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
}
