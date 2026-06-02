package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One charge line in the order lines read response (Chunk 3 D.3, OpenAPI {@code ChargeLineRead}).
 *
 * <p>{@code cost_snapshot} IS exposed read-only; per-line {@code line_cost} is backend-only and is
 * deliberately absent. Charge lines have no pricing unit and no {@code sqm_per_lm_snapshot} — the
 * LM↔SQM conversion does not apply to charges (Chunk 3 F.3). Branch 2 only READS charge lines (so
 * already-added charges fold into the financial summary); charge-line CRUD is out of scope.
 */
public record ChargeLineReadDto(
        long orderChargeLineId,
        long chargeId,
        String chargeCodeSnapshot,
        String chargeNameSnapshot,
        BigDecimal priceSnapshot,
        BigDecimal costSnapshot,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
) {
}
