package com.flooring.salesportal.common.error;

import com.flooring.salesportal.common.api.ErrorDetail;
import org.springframework.http.HttpStatus;

import java.util.List;

public abstract class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorDetail> details;

    protected ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    protected ApiException(ErrorCode errorCode, String message, List<ErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<ErrorDetail> getDetails() {
        return details;
    }

    public HttpStatus getStatus() {
        return errorCode.status();
    }
}
