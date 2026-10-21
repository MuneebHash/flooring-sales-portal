package com.flooring.salesportal.common.email;

/**
 * Transport-level delivery failure thrown by an {@link InvoiceLinkEmailSender}. Deliberately NOT an
 * {@code ApiException}: the calling endpoint decides fatality. On the Phase 16G PR 4 send-link the send
 * IS the operation, so the caller translates a failure into 502 {@code EMAIL_SEND_FAILED} while keeping
 * the already-committed new link ACTIVE (16G contract §8).
 */
public class InvoiceLinkEmailException extends RuntimeException {

    public InvoiceLinkEmailException(String message) {
        super(message);
    }

    public InvoiceLinkEmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
