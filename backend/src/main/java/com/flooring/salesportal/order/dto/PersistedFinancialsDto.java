package com.flooring.salesportal.order.dto;

import java.math.BigDecimal;

/**
 * Readback of the nullable persisted financial scalars on {@code sales_order}. All four are
 * {@code null} until Chunk 3 logic writes them. This is NOT the live {@code order_financial_summary}
 * (conventions §12), which Chunk 2 GET deliberately omits.
 */
public record PersistedFinancialsDto(
        BigDecimal salePriceExGst,
        BigDecimal totalCost,
        BigDecimal gp,
        BigDecimal gpPercent
) {
}
