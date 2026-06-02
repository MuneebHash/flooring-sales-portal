package com.flooring.salesportal.order.dto;

/**
 * Response body for {@code DELETE /product-lines/{lineId}} (200) — Chunk 3 D.6, OpenAPI
 * {@code ProductLineDeleteResponse}: the id of the removed line plus the recomputed live financial
 * summary (the order may now have zero product lines, but existing charge lines still count toward
 * the summary). Wrapped by the standard {@code ApiResponse} {@code data} envelope with the message
 * "Product line deleted.".
 */
public record ProductLineDeleteResponse(
        long deletedProductLineId,
        OrderFinancialSummaryDto orderFinancialSummary
) {
}
