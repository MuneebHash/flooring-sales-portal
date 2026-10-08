package com.flooring.salesportal.order.quote.dto;

/**
 * The public accept result (openapi {@code PublicQuoteAcceptResult}) — the {@code data} of a successful
 * {@code POST /api/v1/public/quotes/{token}/accept} (Phase 16F PR1). Always {@code state = "INACTIVE"}:
 * the quote is signed and the link is now dead (its token is {@code CONSUMED}). Deliberately minimal —
 * no id, amount, name, timestamp, file/storage reference, token or hash; the signed quote lives only
 * in the protected portal.
 */
public record PublicQuoteAcceptResultDto(String state) {
}
