package com.flooring.salesportal.common.email;

/**
 * Provider-agnostic invoice email transport (Phase 13 contract §9: the provider — SendGrid / SES /
 * SMTP / … — is deliberately NOT decided in the MVP, so domain code depends only on this interface).
 * Implementations deliver one email with the current invoice PDF attached and throw
 * {@link InvoiceEmailException} when delivery fails.
 *
 * <p>Fatality is the CALLER's decision, not the sender's: the D.8 Accept auto-email is non-fatal
 * (acceptance persists, {@code last_emailed_at} stays null, the 201 message points at Re-send), while
 * on D.9 Resend the send IS the operation, so a failure becomes 502 {@code EMAIL_SEND_FAILED}.
 *
 * <p>The only Phase 13B implementation is {@link RecordingInvoiceEmailSender} (local/dev/test — no
 * real emails leave the system). A real SMTP/provider sender is a later deployment-phase branch and
 * would be the one to add {@code spring-boot-starter-mail} + configuration; nothing here is
 * vendor-specific and no credentials exist in this repo.
 */
public interface InvoiceEmailSender {

    /**
     * Send one invoice email. Returns normally on success; throws {@link InvoiceEmailException} on a
     * delivery/provider failure. Implementations must not partially apply domain state — delivery
     * outcome is reported solely via return/throw.
     */
    void send(InvoiceEmailRequest request);
}
