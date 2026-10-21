package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * The {@code data} of a successful {@code POST /orders/{orderId}/invoices/current/send-link} (Phase 16G
 * PR 4; OpenAPI {@code InvoiceLinkSendResult}): {@code { "invoice_link": { "sent_at", "expires_at" } }}.
 * The new link's send and expiry times only - never the token, its hash, the link URL, a storage path or
 * the recipient. {@code expires_at} is {@code sent_at} + 7 days and is informational (the Invoice tab
 * never times anything from it). Timestamps are whole seconds ({@code YYYY-MM-DDTHH:mm:ss}).
 */
public record InvoiceLinkSendResultDto(InvoiceLink invoiceLink) {

    /** The minted link's two timestamps (OpenAPI {@code InvoiceLinkSendResult.invoice_link}). */
    public record InvoiceLink(
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime sentAt,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime expiresAt) {
    }
}
