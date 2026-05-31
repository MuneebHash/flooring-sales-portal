package com.flooring.salesportal.common.error;

/**
 * Thrown when a request body that is parsed manually <em>after</em> the mutation gates
 * (guard → orderId → scoped lookup → 404 → LAID → 422) turns out to be unreadable JSON.
 *
 * <p>Routes through {@link ErrorCode#MALFORMED_JSON}, so {@code GlobalExceptionHandler}'s
 * {@code handleApiException} returns the identical 400 body/shape as Spring's
 * {@code HttpMessageNotReadableException} path — no duplicated handler logic. Used by the order
 * mutation endpoints that accept the body as a raw {@code String} to keep body parsing strictly
 * after all session/scope/LAID gates.
 */
public class MalformedJsonException extends ApiException {

    public MalformedJsonException() {
        super(ErrorCode.MALFORMED_JSON, ErrorCode.MALFORMED_JSON.defaultMessage());
    }
}
