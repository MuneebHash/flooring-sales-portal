package com.flooring.salesportal.common.error;

/**
 * 410 Gone — a public quote link that resolved to a real token row but is no longer usable
 * (Phase 16E-C; contract §12: {@code QUOTE_LINK_EXPIRED} / {@code QUOTE_LINK_SUPERSEDED} /
 * {@code QUOTE_LINK_CANCELLED} / {@code QUOTE_LINK_INACTIVE}). Raised only by the public
 * viewed/pdf (and, in 16F, accept) actions on a dead link; the public GET never throws this —
 * it returns 200 with a {@code state} instead so the customer page can render the message.
 */
public class GoneException extends ApiException {

    public GoneException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
