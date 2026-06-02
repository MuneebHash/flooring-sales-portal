package com.flooring.salesportal.order.dto;

import java.util.List;

/**
 * {@code GET /orders/{orderId}/lines} 200 response body (Chunk 3 D.3, OpenAPI
 * {@code OrderLinesResponse}): all product lines + all charge lines + the live financial summary.
 * Intentionally not paginated — the workspace needs every line and the summary depends on all of
 * them. Lines are ordered by {@code created_at} ASC then id ASC. Wrapped by the standard
 * {@code ApiResponse} {@code data} envelope by the controller.
 */
public record OrderLinesResponse(
        List<ProductLineReadDto> productLines,
        List<ChargeLineReadDto> chargeLines,
        OrderFinancialSummaryDto orderFinancialSummary
) {
}
