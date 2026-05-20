package com.flooring.salesportal.common.error;

import com.flooring.salesportal.common.api.ErrorDetail;

import java.util.List;

public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    public BusinessRuleException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessRuleException(ErrorCode errorCode, String message, List<ErrorDetail> details) {
        super(errorCode, message, details);
    }
}
