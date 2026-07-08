package com.flooring.salesportal.common.email;

/**
 * Transport-level delivery failure thrown by a {@link QuoteEmailSender}. Deliberately NOT an
 * {@code ApiException}: the calling endpoint decides fatality. On the Phase 16E-A quote
 * {@code send-email} the send IS the operation, so the caller translates this into 502
 * {@code EMAIL_SEND_FAILED} while keeping the already-persisted issued version / PDF / token
 * (contract §7.1 send-failure rule).
 */
public class QuoteEmailException extends RuntimeException {

    public QuoteEmailException(String message) {
        super(message);
    }

    public QuoteEmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
