package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * One attachment in the order attachment responses (Chunk 3 D.14 list / D.15 upload, OpenAPI
 * {@code AttachmentRead}).
 *
 * <p>Sourced from {@code order_attachment} joined to {@code stored_file}. Only the seven contract
 * columns are exposed: the internal {@code stored_file.storage_path} (server disk path) and
 * {@code stored_file_id} are NEVER returned (Chunk 3 D.14 / E.4). {@code download_path} is built
 * backend-side from the request slug + ids — the relative URL of the D.17 file-stream endpoint — so
 * the frontend does not assemble it. Jackson's global snake_case strategy renders the field names
 * ({@code orderAttachmentId} → {@code order_attachment_id}); {@code createdAt} matches the existing
 * read DTOs' {@code yyyy-MM-dd'T'HH:mm:ss} format.
 */
public record AttachmentReadDto(
        long orderAttachmentId,
        String attachmentKind,
        String fileName,
        String mimeType,
        long fileSize,
        String downloadPath,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt
) {
}
