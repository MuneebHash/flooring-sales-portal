package com.flooring.salesportal.common.email;

/**
 * Provider-agnostic STORE notification transport for a remotely accepted quote (Phase 16F PR1;
 * contract §7.2 / §9: "email the store an acceptance notification — post-commit, non-fatal").
 * Deliberately a SEPARATE interface from {@link QuoteEmailSender} (customer quote delivery) and
 * {@link InvoiceEmailSender} — the locked "duplicate, don't extract" decision for the quote feature:
 * a store notification goes to the STORE, never the customer, carries no attachment and no public
 * link, and its recorded sends must never be conflated with customer emails in tests or in the later
 * Phase 17 provider wiring.
 *
 * <p>The notification is strictly post-commit and NON-FATAL: the caller catches
 * {@link QuoteAcceptanceNotificationException} and logs a WARN — the acceptance, signature, signed
 * PDF, consumed token and order price write stay committed. There is no retry and no persisted
 * delivery marker (16F decision D12).
 *
 * <p>The only implementation is {@link RecordingQuoteAcceptanceNotificationSender} (local/dev/test —
 * no real email leaves the system). A real provider is Phase 17; nothing here is vendor-specific.
 */
public interface QuoteAcceptanceNotificationSender {

    /**
     * Deliver one store acceptance notification. Returns normally on success; throws
     * {@link QuoteAcceptanceNotificationException} on a delivery/provider failure. Implementations
     * must not touch domain state — the outcome is reported solely via return/throw.
     */
    void send(QuoteAcceptanceNotificationRequest request);
}
