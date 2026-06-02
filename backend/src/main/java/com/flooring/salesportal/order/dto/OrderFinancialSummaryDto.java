package com.flooring.salesportal.order.dto;

import java.math.BigDecimal;

/**
 * Live order financial summary (conventions §12, Chunk 3 E.1, OpenAPI {@code OrderFinancialSummary}).
 * Returned by GET /lines and every product-line mutation. All fields are always present;
 * {@code priceAdjustmentIncGst} and {@code gpPercent} are nullable and serialized as JSON {@code null}
 * (conventions §3 null-vs-absent), never omitted. Money values are {@link BigDecimal} at scale 2.
 *
 * <p>This is computed live (never persisted as a row). On a mutation the backend additionally
 * persists the {@code sales_order} header scalars ({@code sale_price_ex_gst}, {@code total_cost},
 * {@code gp}, {@code gp_percent}); this DTO always carries the TRUE computed {@code gpPercent} even
 * when the persisted column value would overflow {@code DECIMAL(5,2)} and is stored as NULL.
 */
public record OrderFinancialSummaryDto(
        BigDecimal productSubtotal,
        BigDecimal chargeSubtotal,
        BigDecimal calculatedTotalIncGst,
        BigDecimal priceAdjustmentIncGst,
        BigDecimal finalSalePriceIncGst,
        BigDecimal salePriceExGst,
        BigDecimal totalCost,
        BigDecimal gp,
        BigDecimal gpPercent,
        boolean gpWarning
) {
}
