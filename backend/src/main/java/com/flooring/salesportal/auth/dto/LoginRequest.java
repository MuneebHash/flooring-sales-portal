package com.flooring.salesportal.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Salesperson code is required.") String salespersonCode,
        @NotBlank(message = "Password is required.") String password
) {
}
