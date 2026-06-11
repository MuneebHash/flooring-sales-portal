package com.flooring.salesportal.common.error;

/**
 * 502 {@code EMAIL_SEND_FAILED} (Phase 13 §12 / conventions §4: an upstream provider dependency failed
 * on the caller's behalf — an internal bug is still 500). Raised by D.9 Resend ONLY, where the email
 * send is the whole operation; the non-fatal D.8 accept-send and the later D.7 payment-after-accepted
 * send never use it. Mirrors {@link FileUploadException}: a thin {@link ApiException} whose status
 * rides on the {@link ErrorCode}.
 */
public class EmailSendException extends ApiException {

    public EmailSendException(String message) {
        super(ErrorCode.EMAIL_SEND_FAILED, message);
    }
}
