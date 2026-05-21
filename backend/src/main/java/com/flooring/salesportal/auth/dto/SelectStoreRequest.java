package com.flooring.salesportal.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SelectStoreRequest(
        @NotNull(message = "Store id is required.")
        @Positive(message = "Store id must be a positive integer.")
        Integer storeId
) {
}
