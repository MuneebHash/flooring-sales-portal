package com.flooring.salesportal.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "One or more fields are invalid."),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "Request body is malformed."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid salesperson code or password."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied."),
    NO_STORE_ACCESS(HttpStatus.FORBIDDEN, "You do not have access to any store in this business."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found."),
    LINE_NOT_FOUND(HttpStatus.NOT_FOUND, "Order line not found."),
    CONFLICT(HttpStatus.CONFLICT, "Conflict."),
    STORE_ALREADY_SELECTED(HttpStatus.CONFLICT, "A different store is already selected in this session. Log out to switch stores."),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violation."),
    ORDER_LOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "Order is laid and cannot be edited."),
    PRODUCT_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Product is inactive."),
    FLOORING_TYPE_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Product flooring type does not match the order's flooring type."),
    INSTALLATION_ADDRESS_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "Installation address must exist before copying it to billing."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
