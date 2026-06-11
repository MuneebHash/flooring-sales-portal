package com.flooring.salesportal.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "One or more fields are invalid."),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "Request body is malformed."),
    UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "File type is not allowed for attachments."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "File exceeds the 10 MB maximum size."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid salesperson code or password."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied."),
    NO_STORE_ACCESS(HttpStatus.FORBIDDEN, "You do not have access to any store in this business."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found."),
    CHARGE_NOT_FOUND(HttpStatus.NOT_FOUND, "Charge not found."),
    LINE_NOT_FOUND(HttpStatus.NOT_FOUND, "Order line not found."),
    ATTACHMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Attachment not found."),
    CONFLICT(HttpStatus.CONFLICT, "Conflict."),
    STORE_ALREADY_SELECTED(HttpStatus.CONFLICT, "A different store is already selected in this session. Log out to switch stores."),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violation."),
    ORDER_LOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "Order is laid and cannot be edited."),
    PRODUCT_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Product is inactive."),
    CHARGE_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Charge is inactive."),
    FLOORING_TYPE_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Product flooring type does not match the order's flooring type."),
    INSTALLATION_ADDRESS_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "Installation address must exist before copying it to billing."),
    // Phase 12 invoice / payment codes (Chunk 4 D.1/D.2/D.3/D.7). INVOICE_ALREADY_EXISTS and
    // INVOICE_PRECONDITIONS_NOT_MET are used by Branch A (create); INVOICE_REQUIRED, INVOICE_NOT_FOUND,
    // and PAYMENT_EXCEEDS_BALANCE are foundation codes wired by later Phase 12 branches.
    INVOICE_ALREADY_EXISTS(HttpStatus.CONFLICT, "An invoice already exists for this order. Use Rewrite Invoice to create a new version."),
    INVOICE_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "An invoice is required for this action."),
    INVOICE_NOT_FOUND(HttpStatus.NOT_FOUND, "Invoice not found."),
    INVOICE_PRECONDITIONS_NOT_MET(HttpStatus.UNPROCESSABLE_ENTITY, "Complete required fields before creating invoice."),
    PAYMENT_EXCEEDS_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "Payment amount cannot exceed the current balance due."),
    // Phase 13 customer-email gate (contract §12): evaluated on D.1 Create / D.2 Rewrite BEFORE the 9
    // invoice preconditions (and reused by the later Accept / Resend branches). The remaining Phase 13
    // codes (signature / already-accepted / email-send) are added by the branches that use them.
    CUSTOMER_EMAIL_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "A valid customer email is required before this action."),
    CUSTOMER_EMAIL_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Customer email is not a valid email address."),
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
