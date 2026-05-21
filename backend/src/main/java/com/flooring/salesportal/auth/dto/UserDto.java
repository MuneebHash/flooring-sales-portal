package com.flooring.salesportal.auth.dto;

public record UserDto(
        Long userId,
        String firstName,
        String lastName,
        String salespersonCode
) {
}
