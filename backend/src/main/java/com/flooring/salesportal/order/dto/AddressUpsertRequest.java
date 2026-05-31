package com.flooring.salesportal.order.dto;

/**
 * Request body for {@code PUT /orders/{orderId}/addresses/installation} and
 * {@code PUT /orders/{orderId}/addresses/billing}. Full-replace upsert of the one
 * {@code order_address} row for the path-determined {@code address_type}.
 *
 * <p>{@code address_type} is intentionally NOT a component here — the path determines it
 * (INSTALLATION vs BILLING), never the body. Global snake_case Jackson maps the JSON fields to
 * these record components; an {@code address_type} key in the body is an unknown property and is
 * ignored under the project's existing Jackson config. An omitted field and an explicit
 * {@code null} are indistinguishable — both bind to {@code null}, which is the intended replace
 * semantics for the optional {@code unit_number}. Presence, trim/blank, and VARCHAR-length
 * validation happen in {@code OrderService}.
 */
public record AddressUpsertRequest(
        String unitNumber,
        String streetNumber,
        String street,
        String suburb,
        String stateCode,
        String postcode
) {
}
