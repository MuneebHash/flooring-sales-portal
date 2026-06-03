package com.flooring.salesportal.order.dto;

/**
 * Response payload for {@code POST /orders/{orderId}/attachments} (201) — Chunk 3 D.15, OpenAPI
 * {@code AttachmentResponse}: the created attachment nested under an {@code attachment} key
 * (mirroring the notes {@code AddNoteResponse} shape). Wrapped by the standard {@code ApiResponse}
 * {@code data} envelope with the message "Attachment uploaded.", so the serialized body is
 * {@code { "data": { "attachment": { ... } }, "message": "Attachment uploaded." }}.
 *
 * <p>Uploading an attachment does NOT affect order financials, so — unlike the line mutation
 * responses — there is no {@code order_financial_summary} on this payload (Chunk 3 D.15 / F.5).
 */
public record AttachmentUploadResponse(
        AttachmentReadDto attachment
) {
}
