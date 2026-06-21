package com.flooring.salesportal.order.dto;

/**
 * Response {@code data} for {@code PUT /orders/{orderId}/enquiry}: {@code { "enquiry": {...} }}.
 * Wraps the saved {@link EnquiryDto} (the same 19 columns the GET workspace read exposes under
 * {@code enquiry} — no internal IDs, timestamps, or order header fields).
 */
public record EnquirySaveResponse(EnquiryDto enquiry) {
}
