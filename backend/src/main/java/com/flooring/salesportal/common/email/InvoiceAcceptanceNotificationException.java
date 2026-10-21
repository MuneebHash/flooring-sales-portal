package com.flooring.salesportal.common.email;

/**
 * Transport-level delivery failure thrown by an {@link InvoiceAcceptanceNotificationSender}.
 * Deliberately NOT an {@code ApiException}: a store notification is post-commit and non-fatal, so the
 * public invoice accept catches it, logs a WARN (order id + invoice version only) and still returns its
 * successful 201 (Phase 16G PR 4; 16G contract §10.4).
 */
public class InvoiceAcceptanceNotificationException extends RuntimeException {

    public InvoiceAcceptanceNotificationException(String message) {
        super(message);
    }

    public InvoiceAcceptanceNotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
