package com.flooring.salesportal.dashboard.dto;

public record DashboardCustomerResponse(
        String firstName,
        String lastName,
        String email
) {
}
