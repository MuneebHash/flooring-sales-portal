package com.flooring.salesportal.dashboard.dto;

public record DashboardInstallAddressResponse(
        String unitNumber,
        String streetNumber,
        String street,
        String suburb,
        String stateCode,
        String postcode
) {
}
