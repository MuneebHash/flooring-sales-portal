package com.flooring.salesportal.order.dto;

/**
 * Response body for {@code POST /product-lines} (201) and {@code PATCH /product-lines/{lineId}}
 * (200) — Chunk 3 D.4 / D.5, OpenAPI {@code ProductLineMutationResponse}: the affected product line
 * plus the recomputed live financial summary. Wrapped by the standard {@code ApiResponse}
 * {@code data} envelope with the operation message ("Product line added." / "Product line updated.").
 */
public record ProductLineMutationResponse(
        ProductLineReadDto productLine,
        OrderFinancialSummaryDto orderFinancialSummary
) {
}
