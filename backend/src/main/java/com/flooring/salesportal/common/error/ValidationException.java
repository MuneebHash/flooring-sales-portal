package com.flooring.salesportal.common.error;

import com.flooring.salesportal.common.api.ErrorDetail;

import java.util.List;

public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }

    public ValidationException(String message, List<ErrorDetail> details) {
        super(ErrorCode.VALIDATION_FAILED, message, details);
    }
}
