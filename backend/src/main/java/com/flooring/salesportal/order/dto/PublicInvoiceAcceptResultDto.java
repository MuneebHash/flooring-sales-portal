package com.flooring.salesportal.order.dto;

/**
 * The public invoice accept result (Phase 16G PR 4; OpenAPI {@code PublicInvoiceAcceptResult}) - the
 * {@code data} of a successful {@code POST /api/v1/public/invoices/{token}/accept}. Always
 * {@code state = "SIGNED"}: the invoice is signed and the link is now dead. Deliberately minimal - no id,
 * amount, name, timestamp, file or storage reference, token or email outcome.
 */
public record PublicInvoiceAcceptResultDto(String state) {
}
