package com.flooring.salesportal.common.error;

/**
 * Thrown for the file-upload rejection cases that carry their own (non-{@code VALIDATION_FAILED})
 * 400 error code — Chunk 3 D.15: {@link ErrorCode#UNSUPPORTED_FILE_TYPE} and
 * {@link ErrorCode#FILE_TOO_LARGE}; Phase 13 D.8: {@link ErrorCode#SIGNATURE_INVALID}. All are 400s
 * (deliberately NOT 413/415); the HTTP status comes from the supplied {@link ErrorCode}, so the
 * standard {@code GlobalExceptionHandler.handleApiException} path renders the correct JSON error wrapper.
 */
public class FileUploadException extends ApiException {

    public FileUploadException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
