package com.flooring.salesportal.common.email;

/**
 * Everything an {@link InvoiceLinkEmailSender} needs to deliver one invoice LINK email (Phase 16G PR 4;
 * 16G contract §8). Pre-resolved by the service - the sender never reads the database.
 *
 * <p><b>Link-only.</b> An invoice link email carries the public invoice link plus a short plain-text
 * body and NOTHING else: there is deliberately no attachment field, so no transport can attach an
 * invoice PDF by accident. (The signed-PDF email of a signed invoice is a different email, sent
 * through {@link InvoiceEmailSender}.)
 *
 * <p>{@code bodyText} contains the public invoice link {@code {app.public-base-url}/i/{token}} - the
 * ONLY place the plaintext token ever appears (it is never persisted and never serialized in any API
 * response). {@code orderId} / {@code invoiceVersionNumber} (the current invoice version when the
 * link was sent) are carried for logging and for test assertions against
 * {@link RecordingInvoiceLinkEmailSender}; they are not part of the delivered email.
 */
public record InvoiceLinkEmailRequest(
        String recipientEmail,
        String subject,
        String bodyText,
        long orderId,
        int invoiceVersionNumber) {
}
