package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One product line in the order lines read / mutation responses (Chunk 3 D.3 / D.4 / D.5,
 * OpenAPI {@code ProductLineRead}).
 *
 * <p>{@code cost_snapshot} and {@code sqm_per_lm_snapshot} ARE exposed read-only for salesperson
 * display (conventions §10 / Chunk 3 F.2). Per-line {@code line_cost} is backend-only and is
 * deliberately NOT a field here, so mapping this record can never leak it — only the aggregate
 * {@code total_cost}/{@code gp}/{@code gp_percent} inside {@link OrderFinancialSummaryDto} expose
 * cost. Money / factor columns are {@link BigDecimal} so the {@code DECIMAL} scale is preserved
 * (e.g. {@code 45.00}, {@code 3.66}); timestamps use the project's ISO-local format, matching
 * {@code OrderWorkspaceResponse}.
 */
public record ProductLineReadDto(
        long orderProductLineId,
        long productId,
        String productCodeSnapshot,
        String productNameSnapshot,
        String pricingUnitSnapshot,
        BigDecimal priceSnapshot,
        BigDecimal costSnapshot,
        BigDecimal sqmPerLmSnapshot,
        BigDecimal quantityLm,
        BigDecimal quantitySqm,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
) {
}
