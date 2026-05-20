package com.flooring.salesportal.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ApiResponse<T>(
        T data,
        @JsonInclude(JsonInclude.Include.NON_NULL) String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) PaginationMeta pagination
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null, null);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(data, message, null);
    }

    public static <T> ApiResponse<List<T>> page(List<T> data, PaginationMeta pagination) {
        return new ApiResponse<>(data, null, pagination);
    }
}
