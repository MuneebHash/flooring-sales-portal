package com.flooring.salesportal.common.email;

/**
 * Everything a {@link QuoteAcceptanceNotificationSender} needs to deliver one store notification
 * (Phase 16F PR1). Resolved by the acceptance service INSIDE the acceptance transaction and delivered
 * only AFTER it commits — the sender never reads the database.
 *
 * <p>Deliberately minimal and safe (16F decision D3): {@code recipientEmail} is the order's store
 * {@code store.email}; {@code subject} / {@code bodyText} carry only the order number, quote version,
 * frozen accepted name, accepted time and the accepted inc-GST total. There is NO attachment field
 * (the signed PDF stays portal-only), and the body never carries a public link, token, signature
 * bytes, storage reference, cost or GP. {@code orderId} / {@code quoteVersionNumber} are carried for
 * logging and test assertions only; they are not part of the delivered email.
 */
public record QuoteAcceptanceNotificationRequest(
        String recipientEmail,
        String subject,
        String bodyText,
        long orderId,
        int quoteVersionNumber) {
}
