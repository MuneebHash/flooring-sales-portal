package com.flooring.salesportal.auth.dto;

public record SelectStoreResponse(
        UserDto user,
        StoreDto activeStore
) {
}
