package com.flooring.salesportal.order.quote;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of {@link QuoteDraftCalculator#compute}: the final server-computed line set (including any
 * auto-inserted ADJUSTMENT line or the non-itemised synthetic line) and the derived totals.
 * The invariant {@code quoteTotalExGst == sum(lines.lineTotalExGst)} always holds.
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
