package com.flooring.salesportal.common.email;

/**
 * Everything an {@link InvoiceAcceptanceNotificationSender} needs to deliver one store notification for
 * a remotely signed invoice (Phase 16G PR 4; 16G contract §10.4). Resolved INSIDE the signing
 * transaction and delivered only AFTER it commits - the sender never reads the database.
 *
 * <p>Deliberately minimal and safe: {@code recipientEmail} is the order's own store {@code store.email};
 * {@code subject} / {@code bodyText} carry only the order number, the signed invoice version, the
 * frozen accepted name, the accepted time and the inc-GST total. There is NO attachment field, and the
 * body never carries a public link, token, signature, storage reference, cost or GP.
 * {@code orderId} / {@code invoiceVersionNumber} are carried for logging and test assertions only;
 * they are not part of the delivered email.
 */
public record InvoiceAcceptanceNotificationRequest(
        String recipientEmail,
        String subject,
        String bodyText,
        long orderId,
        int invoiceVersionNumber) {
}
