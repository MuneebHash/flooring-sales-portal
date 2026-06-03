package com.flooring.salesportal.order.dto;

/**
 * Response body for {@code POST /charge-lines} (201) and {@code PATCH /charge-lines/{lineId}}
 * (200) — Chunk 3 D.7 / D.8, OpenAPI {@code ChargeLineMutationResponse}: the affected charge line
 * plus the recomputed live financial summary. Wrapped by the standard {@code ApiResponse}
 * {@code data} envelope with the operation message ("Charge line added." / "Charge line updated.").
 */
public record ChargeLineMutationResponse(
        ChargeLineReadDto chargeLine,
        OrderFinancialSummaryDto orderFinancialSummary
) {
}
