package com.flooring.salesportal.order.dto;

/**
 * Nested customer object on the GET workspace read. All {@code order_customer} columns except
 * IDs and timestamps. {@code null} for the whole object when no customer row exists.
 */
public record CustomerDto(
        String firstName,
        String middleName,
        String lastName,
        String email,
        String mobile,
        String homePhone,
        String workPhone,
        String companyName
) {
}
