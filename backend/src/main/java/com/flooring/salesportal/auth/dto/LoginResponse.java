package com.flooring.salesportal.auth.dto;

import java.util.List;

public record LoginResponse(
        UserDto user,
        List<StoreDto> stores,
        Integer activeStoreId,
        boolean storeSelectionRequired
) {
}
