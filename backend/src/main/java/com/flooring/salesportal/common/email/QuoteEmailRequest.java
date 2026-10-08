package com.flooring.salesportal.common.email;

/**
 * Everything a {@link QuoteEmailSender} needs to deliver one quote email. Pre-resolved by the service
 * — the sender never reads the database.
 *
 * <p><b>Link-only (Phase 16F PR1, decision "Email").</b> A quote email carries the public quote
 * link plus a short plain-text body and NOTHING else: there is deliberately no attachment field, so
 * no transport can attach the issued PDF by accident. The immutable issued PDF is still generated
 * and stored on issue; the customer opens it from the public page (Download PDF) and the salesperson
 * from the protected issued-PDF read. (16E-A attached the issued PDF; that was removed in 16F.)
 *
 * <p>{@code bodyText} contains the public quote link — the ONLY place the plaintext token ever
 * appears (it is never persisted and never serialized in a protected API response).
 * {@code orderId} / {@code quoteVersionNumber} are carried for logging and for test assertions
 * against {@link RecordingQuoteEmailSender}; they are not part of the delivered email.
 */
public record QuoteEmailRequest(
        String recipientEmail,
        String subject,
        String bodyText,
        long orderId,
        int quoteVersionNumber) {
}
