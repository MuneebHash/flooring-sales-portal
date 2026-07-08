package com.flooring.salesportal.common.error;

/**
 * 502 {@code SMS_SEND_FAILED} (Phase 16E-A; contract §9 / conventions §4: an upstream provider
 * dependency failed on the caller's behalf — an internal bug is still 500). Raised by the quote
 * {@code send-sms} endpoint ONLY, where the SMS delivery is the whole operation; the already-
 * persisted issued version / PDF / token are kept (contract §7.1 send-failure rule). Mirrors
 * {@link EmailSendException}: a thin {@link ApiException} whose status rides on the
 * {@link ErrorCode}.
 */
public class SmsSendException extends ApiException {

    public SmsSendException(String message) {
        super(ErrorCode.SMS_SEND_FAILED, message);
    }
}
