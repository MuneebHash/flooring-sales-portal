package com.flooring.salesportal.common.sms;

/**
 * Provider-agnostic SMS transport (Phase 16E-A; contract §3/§9: the SMS provider — Twilio is the
 * intended production provider — sits behind a provider-independent interface, exactly like the
 * Phase 13 email provider). Implementations deliver one short text message (the quote public link;
 * SMS carries the LINK ONLY, never a PDF — contract §9) and throw {@link SmsException} when
 * delivery fails.
 *
 * <p>On a quote send the delivery IS the operation (contract §7.1): the caller translates an
 * {@link SmsException} into 502 {@code SMS_SEND_FAILED}, while the already-persisted issued
 * version / PDF / token are kept (send-failure rule, locked for MVP).
 *
 * <p>The only Phase 16E-A implementation is {@link RecordingSmsSender} (local/dev/test — no real
 * SMS leaves the system). A real Twilio sender is a Phase 17 deployment branch and would be the
 * one to add the dependency, account, number, and credentials; nothing here is vendor-specific and
 * no credentials exist in this repo.
 */
public interface SmsSender {

    /**
     * Send one SMS. Returns normally on success; throws {@link SmsException} on a
     * delivery/provider failure. Implementations must not partially apply domain state — delivery
     * outcome is reported solely via return/throw.
     */
    void send(SmsRequest request);
}
