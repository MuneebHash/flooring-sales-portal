package com.flooring.salesportal.order.dto;

/**
 * Request body for {@code PUT /orders/{orderId}/customer}. Full-replace upsert of the one
 * {@code order_customer} row. Global snake_case Jackson maps the JSON fields to these record
 * components. An omitted field and an explicit {@code null} are indistinguishable here — both
 * bind to {@code null}, which is the intended replace semantics for optional fields. Presence,
 * trim/blank, and {@code @} validation happen in {@code OrderService}.
 */
public record CustomerSaveRequest(
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
