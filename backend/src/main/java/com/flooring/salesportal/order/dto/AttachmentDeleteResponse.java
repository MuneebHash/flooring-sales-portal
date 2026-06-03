package com.flooring.salesportal.order.dto;

/**
 * Response body for {@code DELETE /orders/{orderId}/attachments/{attachmentId}} (200) — Chunk 3
 * D.16, OpenAPI {@code AttachmentDeleteResponse}: just the id of the removed attachment. Wrapped by
 * the standard {@code ApiResponse} {@code data} envelope with the message "Attachment removed.", so
 * the serialized body is {@code { "data": { "deleted_attachment_id": 7 }, "message": "Attachment
 * removed." }}.
 *
 * <p>Deleting an attachment does NOT affect order financials, so there is no
 * {@code order_financial_summary} on this payload (Chunk 3 D.16 / F.5).
 */
public record AttachmentDeleteResponse(
        long deletedAttachmentId
) {
}
