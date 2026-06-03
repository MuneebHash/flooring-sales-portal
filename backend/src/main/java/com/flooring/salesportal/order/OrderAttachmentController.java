package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.OrderAttachmentService.AttachmentDownload;
import com.flooring.salesportal.order.dto.AttachmentDeleteResponse;
import com.flooring.salesportal.order.dto.AttachmentReadDto;
import com.flooring.salesportal.order.dto.AttachmentUploadResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.List;

/**
 * Chunk 3 Branch 5 order attachment / photo endpoints (D.14 GET list, D.15 POST upload, D.16 DELETE,
 * D.17 GET file). Shares the {@code /orders/{orderId}} base path with the other order sub-resource
 * controllers; handler mappings stay distinct by method + the {@code /attachments} sub-path.
 *
 * <p>{@code orderId} / {@code attachmentId} are captured as {@code String} (matching the rest of the
 * order endpoints) so non-numeric / non-positive values become VALIDATION_FAILED on {@code order_id} /
 * {@code attachment_id} in the service rather than path-binding errors. {@code page} / {@code page_size}
 * are {@code Integer} (matching the sibling list endpoints). The service builds the {@code ApiResponse}
 * envelope for the three JSON endpoints.
 *
 * <p>Upload (D.15) is the only {@code multipart/form-data} endpoint and download (D.17) is the only
 * binary response (the accepted file-endpoints amendment to conventions §3 / Chunk 3 F.7): D.17
 * returns a raw {@code ResponseEntity<byte[]>} — NOT wrapped in {@code ApiResponse} — while its error
 * paths still flow through the standard JSON {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}")
public class OrderAttachmentController {

    private final OrderAttachmentService orderAttachmentService;

    public OrderAttachmentController(OrderAttachmentService orderAttachmentService) {
        this.orderAttachmentService = orderAttachmentService;
    }

    @GetMapping("/attachments")
    public ApiResponse<List<AttachmentReadDto>> listAttachments(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            HttpServletRequest httpRequest) {

        return orderAttachmentService.listAttachments(slug, orderId, page, pageSize, httpRequest);
    }

    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttachmentUploadResponse> uploadAttachment(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            MultipartHttpServletRequest request) {

        // The MultipartHttpServletRequest is also the HttpServletRequest the service guard reads the
        // session from; the file part + attachment_kind form field are validated inside the service.
        return orderAttachmentService.uploadAttachment(slug, orderId, request);
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ApiResponse<AttachmentDeleteResponse> deleteAttachment(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @PathVariable("attachmentId") String attachmentId,
            HttpServletRequest httpRequest) {

        return orderAttachmentService.deleteAttachment(slug, orderId, attachmentId, httpRequest);
    }

    @GetMapping("/attachments/{attachmentId}/file")
    public ResponseEntity<byte[]> downloadAttachmentFile(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @PathVariable("attachmentId") String attachmentId,
            HttpServletRequest httpRequest) {

        AttachmentDownload download =
                orderAttachmentService.downloadAttachment(slug, orderId, attachmentId, httpRequest);

        // Raw binary (NOT ApiResponse): stored MIME type, stored size, inline disposition with the
        // safe display filename (Chunk 3 D.17). filename has no quotes/CR/LF (sanitised in the service).
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + download.fileName() + "\"")
                .body(download.bytes());
    }
}
