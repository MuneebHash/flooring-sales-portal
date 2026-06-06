package com.flooring.salesportal.order.dto;

/**
 * Wrapper so the D.1 Create response serializes as {@code { "data": { "invoice": { ...E.2... } },
 * "message": "Invoice created." } } (Chunk 4 D.1). Mirrors {@code AttachmentUploadResponse}'s
 * {@code data.attachment} nesting — the entity sits one level under {@code data}.
 */
public record InvoiceResponse(InvoiceDetailDto invoice) {
}
