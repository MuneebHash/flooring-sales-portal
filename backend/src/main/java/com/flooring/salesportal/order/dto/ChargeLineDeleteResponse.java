package com.flooring.salesportal.order.dto;

/**
 * Response body for {@code DELETE /charge-lines/{lineId}} (200) — Chunk 3 D.9, OpenAPI
 * {@code ChargeLineDeleteResponse}: the id of the removed charge line plus the recomputed live
 * financial summary (the order may now have zero charge lines, but existing product lines still
 * count toward the summary). Wrapped by the standard {@code ApiResponse} {@code data} envelope with
 * the message "Charge line deleted.".
 */
public record ChargeLineDeleteResponse(
        long deletedChargeLineId,
        OrderFinancialSummaryDto orderFinancialSummary
) {
}
