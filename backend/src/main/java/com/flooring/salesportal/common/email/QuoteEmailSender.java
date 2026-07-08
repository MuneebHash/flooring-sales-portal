package com.flooring.salesportal.common.email;

/**
 * Provider-agnostic QUOTE email transport (Phase 16E-A; contract §9: providers are
 * provider-independent behind interfaces, per the Phase 13 email precedent). Deliberately a
 * SEPARATE interface from {@link InvoiceEmailSender} — the locked "duplicate, don't extract"
 * decision for the quote feature: a quote email carries the issued quote PDF plus the public
 * signing link, and its recorded sends must never be conflated with invoice sends in tests or in
 * the later Phase 17 provider wiring.
 *
 * <p>On a quote send the delivery IS the operation (contract §7.1): the caller translates a
 * {@link QuoteEmailException} into 502 {@code EMAIL_SEND_FAILED}, while the already-persisted
 * issued version / PDF / token are kept (send-failure rule, locked for MVP).
 *
 * <p>The only Phase 16E-A implementation is {@link RecordingQuoteEmailSender} (local/dev/test — no
 * real emails leave the system). A real SMTP/provider sender is a Phase 17 deployment branch and
 * would be the one to add configuration/credentials; nothing here is vendor-specific.
 */
public interface QuoteEmailSender {

    /**
     * Send one quote email. Returns normally on success; throws {@link QuoteEmailException} on a
     * delivery/provider failure. Implementations must not partially apply domain state — delivery
     * outcome is reported solely via return/throw.
     */
    void send(QuoteEmailRequest request);
}
