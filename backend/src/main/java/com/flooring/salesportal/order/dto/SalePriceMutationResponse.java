package com.flooring.salesportal.order.dto;

/**
 * Response body for {@code PUT /orders/{orderId}/sale-price} (D.10) and
 * {@code POST /orders/{orderId}/sale-price/reset} (D.11) — OpenAPI {@code SalePriceResponse}: just the
 * recomputed live financial summary, no lines. Wrapped by the standard {@code ApiResponse}
 * {@code data} envelope with the operation message ("Sale price updated." / "Sale price reset.").
 */
public record SalePriceMutationResponse(
        OrderFinancialSummaryDto orderFinancialSummary
) {
}
