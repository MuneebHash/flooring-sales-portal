package com.flooring.salesportal.order.dto;

/**
 * Response {@code data} for {@code PUT /orders/{orderId}/addresses/billing} and
 * {@code POST /orders/{orderId}/addresses/billing/copy-from-installation}:
 * {@code { "billing_address": {...} }}. Wraps the saved {@link AddressDto} (same 6 columns the
 * GET workspace read exposes — no internal IDs, {@code address_type}, timestamps, or order fields).
 */
public record BillingAddressResponse(AddressDto billingAddress) {
}
