package com.flooring.salesportal.common.email;

/**
 * Everything a {@link QuoteEmailSender} needs to deliver one quote email (Phase 16E-A; contract
 * §9: issued PDF attachment + signing link + short plain-text body). Pre-resolved by the service —
 * the sender never reads the database. {@code bodyText} contains the public quote link (the ONLY
 * place the plaintext token ever appears; it is never persisted and never serialized in a
 * protected API response). {@code orderId} / {@code quoteVersionNumber} are carried for logging
 * and for test assertions against {@link RecordingQuoteEmailSender}; they are not part of the
 * delivered email.
 */
public record QuoteEmailRequest(
        String recipientEmail,
        String subject,
        String bodyText,
        byte[] pdfBytes,
        String pdfFileName,
        long orderId,
        int quoteVersionNumber) {
}
