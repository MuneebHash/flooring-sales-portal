package com.flooring.salesportal.common.email;

/**
 * Transport-level delivery failure thrown by a {@link QuoteAcceptanceNotificationSender}.
 * Deliberately NOT an {@code ApiException}: a store notification is post-commit and non-fatal, so the
 * acceptance service catches it, logs a WARN (order id + quote version only) and still returns the
 * successful acceptance (Phase 16F PR1, decision D12).
 */
public class QuoteAcceptanceNotificationException extends RuntimeException {

    public QuoteAcceptanceNotificationException(String message) {
        super(message);
    }

    public QuoteAcceptanceNotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
