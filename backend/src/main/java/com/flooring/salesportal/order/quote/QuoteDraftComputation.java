package com.flooring.salesportal.order.quote;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of {@link QuoteDraftCalculator#compute}: the final server-computed line set (including any
 * auto-inserted ADJUSTMENT line) and the derived totals. For a NON-itemised computation the line set
 * is EMPTY (Phase 16D-B PR2A — the total lives on the draft header; previously persisted itemised
 * rows are retained by the service, not represented here). The invariant
 * {@code quoteTotalExGst == sum(lines.lineTotalExGst)} holds for ITEMISED computations only.
 * {@code gpPercent} is null when not computable (zero ex total). Below-cost is not represented here:
 * the calculator throws {@code QUOTE_BELOW_COST} before returning when the quote is below cost.
 */
public record QuoteDraftComputation(
        List<QuoteComputedLine> lines,
        BigDecimal quoteTotalExGst,
        BigDecimal quoteTotalIncGst,
        BigDecimal gpPercent
) {
}
