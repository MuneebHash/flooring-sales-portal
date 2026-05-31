package com.flooring.salesportal.order.dto;

/**
 * Response {@code data} for {@code PUT /orders/{orderId}/customer}: {@code { "customer": {...} }}.
 * Wraps the saved {@link CustomerDto} (same 8 columns the GET workspace read exposes — no internal
 * IDs, timestamps, or order header fields).
 */
public record CustomerSaveResponse(CustomerDto customer) {
}
