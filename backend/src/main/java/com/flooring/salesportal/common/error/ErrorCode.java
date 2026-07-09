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
    // Phase 15D payment void codes (D.10 POST /payments/{paymentTransactionId}/void). PAYMENT_NOT_FOUND
    // is the scoped not-found for a payment id that is missing or belongs to another order (resolved only
    // after the order itself is in scope — so it never leaks cross-tenant/cross-store existence).
    // PAYMENT_ALREADY_VOIDED (409, parallel to INVOICE_ALREADY_ACCEPTED) covers a double-void.
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found."),
    PAYMENT_ALREADY_VOIDED(HttpStatus.CONFLICT, "This payment has already been voided."),
    // Phase 13 customer-email gate (contract §12): evaluated on D.1 Create / D.2 Rewrite BEFORE the 9
    // invoice preconditions, and reused by D.8 Accept / D.9 Resend (where it runs last, after the
    // invoice-state and signature checks).
    CUSTOMER_EMAIL_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "A valid customer email is required before this action."),
    CUSTOMER_EMAIL_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Customer email is not a valid email address."),
    // Phase 13 Accept / Resend / signature codes (contract §12). EMAIL_SEND_FAILED (502) is raised by
    // D.9 Resend ONLY: the non-fatal D.8 accept-send (and the later D.7 payment-after-accepted send)
    // surface an email failure via last_emailed_at = null + a success message, never via this code.
    ACCEPTED_CUSTOMER_NAME_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "Accepted customer name is required."),
    SIGNATURE_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "A customer signature is required to accept the invoice."),
    SIGNATURE_INVALID(HttpStatus.BAD_REQUEST, "Signature must be a PNG image no larger than 2 MB."),
    INVOICE_ALREADY_ACCEPTED(HttpStatus.CONFLICT, "This invoice has already been accepted. Use Re-send to email it again."),
    INVOICE_NOT_ACCEPTED(HttpStatus.UNPROCESSABLE_ENTITY, "This invoice has not been accepted yet."),
    EMAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "The invoice could not be emailed. Please try again."),
    // Phase 16C PR1 quotation money/data core (contract §6/§12). QUOTE_NOT_FOUND was defensive in
    // PR1 (no live throw site); the 16E-A send endpoints are its live throw site now (send with no
    // saved draft). QUOTE_BELOW_COST and QUOTE_TOTAL_EXCEEDS_LINES are enforced by the draft PUT,
    // and below-cost is RE-CHECKED at send (16E-A) before any issue persistence.
    QUOTE_NOT_FOUND(HttpStatus.NOT_FOUND, "No quote was found for this order."),
    QUOTE_BELOW_COST(HttpStatus.UNPROCESSABLE_ENTITY, "This quote is below cost and cannot be saved, sent, or accepted."),
    QUOTE_TOTAL_EXCEEDS_LINES(HttpStatus.UNPROCESSABLE_ENTITY, "The quote total cannot exceed the sum of its lines. Add or raise a line instead."),
    // Phase 16E-A quote delivery codes (contract §12): cancel (QUOTE_NOT_ISSUED 422 /
    // QUOTE_ALREADY_ACCEPTED 409 — the accepted branch is defensive here: no 16E-A endpoint can
    // create an ACCEPTED version, but it is valid data once 16F lands), stored-PDF download
    // (QUOTE_PDF_NOT_FOUND 404), the send-sms recipient gate (422s, mirroring the Phase 13
    // CUSTOMER_EMAIL_* pair), and the send-sms provider failure (502, mirroring EMAIL_SEND_FAILED).
    // The 16F create-invoice code (QUOTE_NOT_ACCEPTED) is added by its owning endpoint, not here.
    QUOTE_NOT_ISSUED(HttpStatus.UNPROCESSABLE_ENTITY, "There is no active quote to cancel."),
    QUOTE_ALREADY_ACCEPTED(HttpStatus.CONFLICT, "This quote has already been accepted."),
    QUOTE_PDF_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested quote PDF is not available."),
    CUSTOMER_MOBILE_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "A valid customer mobile number is required to send by SMS."),
    CUSTOMER_MOBILE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Customer mobile number is not valid."),
    SMS_SEND_FAILED(HttpStatus.BAD_GATEWAY, "The quote could not be sent by SMS. Please try again."),
    // Phase 16E-C public quote-link codes (contract §12): the token-only public surface.
    // QUOTE_TOKEN_NOT_FOUND (404) covers BOTH an unknown and a malformed token — the public GET
    // must never distinguish them (no existence leak). The four QUOTE_LINK_* codes are 410 Gone
    // for a FOUND but dead link, raised only by the public viewed/pdf (and 16F accept) actions;
    // the public GET instead returns 200 with a state so the customer page can show the message
    // (contract §12 clarification). QUOTE_LINK_SUPERSEDED serves both REPLACED and SUPERSEDED
    // tokens; QUOTE_LINK_INACTIVE is the CONSUMED (signed) token — dead like the others.
    QUOTE_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "Quote not found."),
    QUOTE_LINK_EXPIRED(HttpStatus.GONE, "This quote link has expired. Please contact the store."),
    QUOTE_LINK_SUPERSEDED(HttpStatus.GONE, "This quote has been replaced. Please use the latest quote link."),
    QUOTE_LINK_CANCELLED(HttpStatus.GONE, "This quote has been cancelled. Please contact the store."),
    QUOTE_LINK_INACTIVE(HttpStatus.GONE, "This quote link is no longer active. Please contact the store."),
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
