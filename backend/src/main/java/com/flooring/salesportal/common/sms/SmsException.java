package com.flooring.salesportal.common.sms;

/**
 * Transport-level delivery failure thrown by an {@link SmsSender}. Deliberately NOT an
 * {@code ApiException} (mirrors {@code InvoiceEmailException} / {@code QuoteEmailException}): the
 * calling endpoint decides fatality. On the Phase 16E-A quote {@code send-sms} the send IS the
 * operation, so the caller translates this into 502 {@code SMS_SEND_FAILED} while keeping the
 * already-persisted issued version / PDF / token (contract §7.1 send-failure rule).
 */
public class SmsException extends RuntimeException {

    public SmsException(String message) {
        super(message);
    }

    public SmsException(String message, Throwable cause) {
        super(message, cause);
    }
}
