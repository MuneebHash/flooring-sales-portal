package com.flooring.salesportal.common.email;

/**
 * Provider-agnostic transport for the invoice LINK email (Phase 16G PR 4; 16G contract §8): the email
 * the protected send-link endpoint ({@code POST .../invoices/current/send-link}) delivers to the saved
 * customer email after its transaction commits. Deliberately a SEPARATE interface from
 * {@link InvoiceEmailSender} (the signed-PDF email) and {@link QuoteEmailSender}: a link email carries
 * the public invoice link only ({@link InvoiceLinkEmailRequest} has no attachment field), and its
 * recorded sends must never be conflated with the signed-PDF or quote emails in tests or in the
 * Phase 17 provider wiring.
 *
 * <p>On a link send the delivery IS the operation: the caller translates a failure into 502
 * {@code EMAIL_SEND_FAILED} while the already-committed new link stays ACTIVE and any previous link
 * stays REPLACED.
 *
 * <p>The only implementation is {@link RecordingInvoiceLinkEmailSender} (local/dev/test - no real
 * emails leave the system). A real provider is Phase 17; nothing here is vendor-specific.
 */
public interface InvoiceLinkEmailSender {

    /**
     * Send one invoice link email. Returns normally on success; throws {@link InvoiceLinkEmailException}
     * on a delivery/provider failure. Implementations must not touch domain state - the delivery
     * outcome is reported solely via return/throw.
     */
    void send(InvoiceLinkEmailRequest request);
}
