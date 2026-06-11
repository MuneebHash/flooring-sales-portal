package com.flooring.salesportal.common.email;

/**
 * Transport-level delivery failure thrown by an {@link InvoiceEmailSender}. Deliberately NOT an
 * {@code ApiException}: whether a failed send is fatal belongs to the calling endpoint — D.8 Accept
 * catches it and still returns 201 (acceptance is durable; {@code last_emailed_at} stays null), while
 * D.9 Resend translates it to 502 {@code EMAIL_SEND_FAILED}.
 */
public class InvoiceEmailException extends RuntimeException {

    public InvoiceEmailException(String message) {
        super(message);
    }

    public InvoiceEmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
