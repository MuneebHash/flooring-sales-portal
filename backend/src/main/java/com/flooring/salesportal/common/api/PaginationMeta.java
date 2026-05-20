package com.flooring.salesportal.common.api;

public record PaginationMeta(
        int page,
        int pageSize,
        long totalItems,
        int totalPages
) {
}
