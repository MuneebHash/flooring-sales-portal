package com.flooring.salesportal.order.dto;

/**
 * Response {@code data} for {@code PUT /orders/{orderId}/addresses/installation}:
 * {@code { "install_address": {...} }}. Wraps the saved {@link AddressDto} (same 6 columns the
 * GET workspace read exposes — no internal IDs, {@code address_type}, timestamps, or order fields).
 */
public record InstallationAddressResponse(AddressDto installAddress) {
}
