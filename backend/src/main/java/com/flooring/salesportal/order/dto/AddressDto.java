package com.flooring.salesportal.order.dto;

/**
 * Nested address object on the GET workspace read (install or billing). All {@code order_address}
 * columns except IDs, {@code address_type}, and timestamps. {@code null} when the row is absent.
 */
public record AddressDto(
        String unitNumber,
        String streetNumber,
        String street,
        String suburb,
        String stateCode,
        String postcode
) {
}
