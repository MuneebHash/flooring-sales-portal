package com.flooring.salesportal.common.email;

/**
 * Provider-agnostic STORE notification transport for a remotely signed invoice (Phase 16G PR 4; 16G
 * contract §10.4). Deliberately a SEPARATE interface from {@link QuoteAcceptanceNotificationSender} (the
 * quote notification), {@link InvoiceEmailSender} (the customer's signed-PDF email) and
 * {@link InvoiceLinkEmailSender} (the customer's link email) - the 16F D3 pattern: a store notification
 * goes to the STORE, never the customer, carries no attachment and no public link, and its recorded
 * sends must never be conflated with customer emails in tests or in the Phase 17 provider wiring.
 *
 * <p>The notification is strictly post-commit and NON-FATAL: the caller catches any failure and logs a
 * WARN (order id + invoice version only) - the signed invoice stays committed. There is no retry and no
 * persisted marker, and it never stamps {@code invoice.last_emailed_at} or the dashboard mirror.
 *
 * <p>The only implementation is {@link RecordingInvoiceAcceptanceNotificationSender} (local/dev/test -
 * no real email leaves the system). A real provider is Phase 17; nothing here is vendor-specific.
 */
public interface InvoiceAcceptanceNotificationSender {

    /**
     * Deliver one store notification. Returns normally on success; throws
     * {@link InvoiceAcceptanceNotificationException} on a delivery/provider failure. Implementations must
     * not touch domain state - the outcome is reported solely via return/throw.
     */
    void send(InvoiceAcceptanceNotificationRequest request);
}
