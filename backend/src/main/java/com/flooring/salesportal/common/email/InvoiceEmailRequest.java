package com.flooring.salesportal.common.email;

/**
 * Everything an {@link InvoiceEmailSender} needs to deliver one invoice email (Phase 13 §9: simple
 * MVP wording, current invoice PDF attached). Pre-resolved by the service — the sender never reads
 * the database. {@code orderId} / {@code invoiceVersionNumber} are carried for logging and for test
 * assertions against {@link RecordingInvoiceEmailSender}; they are not part of the delivered email.
 */
public record InvoiceEmailRequest(
        String recipientEmail,
        String subject,
        String bodyText,
        byte[] pdfBytes,
        String pdfFileName,
        long orderId,
        int invoiceVersionNumber) {
}
