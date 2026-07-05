package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.ValidationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure quote-draft money computation (Phase 16C PR1; contract §6). No persistence, no Spring deps
 * beyond {@code @Component} — unit-testable in isolation. Mirrors the existing money discipline
 * ({@link com.flooring.salesportal.order.financial.OrderFinancialCalculator}): {@link BigDecimal},
 * scale 2, {@link RoundingMode#HALF_UP}, GST multiplier {@code 1.10}. The {@code ×1.10} semantics are
 * duplicated here (per the locked "duplicate, don't extract" decision) rather than reusing the order
 * calculator, which operates on product/charge {@code LineFinancials}, not arbitrary quote lines.
 *
 * <p><b>Locked decisions applied:</b>
 * <ul>
 *   <li><b>Q1 — ex-primary + 1¢ tolerance.</b> The request's {@code final_total_inc_gst} lever is
 *       GST-inclusive but quote lines are ex-GST; it is converted to ex
 *       ({@code target_ex = round(final/1.10)}) and compared to the ex line sum.
 *       {@code delta = target_ex − line_sum_ex}: {@code > 0.01} → 422 QUOTE_TOTAL_EXCEEDS_LINES;
 *       within ±0.01 → treat as equal (no adjustment, total = line sum, so a no-op resave never
 *       trips EXCEEDS); {@code < −0.01} → append a negative ADJUSTMENT line for the difference.</li>
 *   <li><b>Q2 (amended Phase 16D-B PR2A) — non-itemised carries the total on the draft header.</b>
 *       {@code itemised=false} with a {@code final_total_inc_gst} computes
 *       {@code target_ex = round(final/1.10)} and returns NO lines — the synthetic "Quoted works"
 *       line is gone. Any supplied lines are ignored (never summed, never EXCEEDS-checked); the
 *       service retains previously persisted itemised rows untouched (contract §6.1). The
 *       {@code total == Σ lines} invariant is therefore MODE-SCOPED: it holds for itemised
 *       computations only.</li>
 * </ul>
 * An itemised quote total never exceeds the sum of its lines; below cost (quote ex &lt; order total
 * cost ex) is blocked with 422 QUOTE_BELOW_COST in BOTH modes (this calculator throws before
 * returning — non-itemised uses the final-total-derived ex).
 */
@Component
public class QuoteDraftCalculator {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal GST_MULTIPLIER = new BigDecimal("1.10");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE);
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    private static final String ADJUSTMENT_DESCRIPTION = "Adjustment";

    // quote_total_*/line_total_ex_gst are DECIMAL(10,2): a 2dp value fits iff |value| <= 99999999.99.
    private static final int MONEY_MAX_INTEGER_DIGITS = 10 - MONEY_SCALE;
    private static final String MONEY_MAX_LABEL = "99999999.99";

    /**
     * Upper bound on a line's {@code sort_order} — the SINGLE source of truth for both the generated
     * ADJUSTMENT sort_order (here) and the request-validation cap ({@code QuoteService} references this
     * constant). A persisted/generated sort_order is always in {@code [0, MAX_SORT_ORDER]} so a returned
     * draft is always resaveable: {@code QuoteService}'s body validation rejects any client value above
     * this, and {@link #nextSortOrder} never generates a value above it (the resave-lockout fix-forward).
     */
    public static final int MAX_SORT_ORDER = 1_000_000;

    /**
     * Recompute and validate the draft money. For an itemised draft, returns the final line set
     * (including any auto-inserted ADJUSTMENT line) plus the derived ex/inc totals and GP percent.
     * For a non-itemised draft, returns NO lines — the totals are derived from
     * {@code final_total_inc_gst} alone (Phase 16D-B PR2A; the service retains previously
     * persisted itemised rows untouched).
     *
     * @param itemised          the draft itemised flag
     * @param inputs            validated request lines (ITEM totals are recomputed here)
     * @param finalTotalIncGst  optional GST-inclusive direct total (null = use the line sum)
     * @param totalCostExGst    the order's product+charge total cost ex-GST (for below-cost)
     * @throws BusinessRuleException QUOTE_TOTAL_EXCEEDS_LINES (422, itemised only) / QUOTE_BELOW_COST (422)
     * @throws ValidationException   400 when a computed money value overflows DECIMAL(10,2)
     */
    public QuoteDraftComputation compute(boolean itemised,
                                         List<QuoteLineInput> inputs,
                                         BigDecimal finalTotalIncGst,
                                         BigDecimal totalCostExGst) {
        List<QuoteComputedLine> lines;
        BigDecimal quoteTotalExGst;

        if (!itemised && finalTotalIncGst != null) {
            // Phase 16D-B PR2A: a non-itemised quote carries its total on the draft header only —
            // NO synthetic line is generated and any supplied lines are ignored (never summed,
            // never EXCEEDS-checked). The service leaves previously persisted itemised rows
            // untouched (retention, contract §6.1); this computation contributes no lines.
            lines = new ArrayList<>();
            quoteTotalExGst = finalTotalIncGst.divide(GST_MULTIPLIER, MONEY_SCALE, ROUNDING);
        } else {
            lines = recomputeLineTotals(inputs);
            BigDecimal lineSumExGst = sumLineTotals(lines);

            if (finalTotalIncGst == null) {
                quoteTotalExGst = lineSumExGst;
            } else {
                BigDecimal targetEx = finalTotalIncGst.divide(GST_MULTIPLIER, MONEY_SCALE, ROUNDING);
                BigDecimal deltaEx = targetEx.subtract(lineSumExGst).setScale(MONEY_SCALE, ROUNDING);
                if (deltaEx.compareTo(ONE_CENT) > 0) {
                    // Direct total set ABOVE the line sum: must raise a line / add a positive line.
                    throw new BusinessRuleException(ErrorCode.QUOTE_TOTAL_EXCEEDS_LINES,
                            ErrorCode.QUOTE_TOTAL_EXCEEDS_LINES.defaultMessage());
                } else if (deltaEx.compareTo(ONE_CENT.negate()) >= 0) {
                    // Within ±1¢ of the line sum (inc->ex rounding noise): treat as equal, no
                    // adjustment, total = line sum. Keeps a no-op resave from tripping EXCEEDS.
                    quoteTotalExGst = lineSumExGst;
                } else {
                    // Direct reduction below the line sum: insert a visible negative ADJUSTMENT line.
                    int nextSort = nextSortOrder(lines);
                    lines.add(new QuoteComputedLine(
                            "ADJUSTMENT", ADJUSTMENT_DESCRIPTION, null, null, deltaEx, nextSort));
                    quoteTotalExGst = targetEx;
                }
            }
        }

        quoteTotalExGst = quoteTotalExGst.setScale(MONEY_SCALE, ROUNDING);
        requireFitsMoney(quoteTotalExGst, "quote_total_ex_gst");
        for (QuoteComputedLine line : lines) {
            requireFitsMoney(line.lineTotalExGst(), "line_total_ex_gst");
        }

        BigDecimal quoteTotalIncGst = quoteTotalExGst.multiply(GST_MULTIPLIER).setScale(MONEY_SCALE, ROUNDING);
        requireFitsMoney(quoteTotalIncGst, "quote_total_inc_gst");

        // Below cost: GP = quote sale ex-GST − order total cost ex-GST (from the order's product/
        // charge cost lines). Negative GP is blocked on quote save (contract §6) — warning-band GP
        // is allowed. This block is quote-only; the manual order sale-price override never blocks it.
        BigDecimal cost = totalCostExGst == null ? ZERO : totalCostExGst.setScale(MONEY_SCALE, ROUNDING);
        BigDecimal gp = quoteTotalExGst.subtract(cost).setScale(MONEY_SCALE, ROUNDING);
        if (gp.signum() < 0) {
            throw new BusinessRuleException(ErrorCode.QUOTE_BELOW_COST,
                    ErrorCode.QUOTE_BELOW_COST.defaultMessage());
        }

        BigDecimal gpPercent = gpPercent(quoteTotalExGst, cost);
        return new QuoteDraftComputation(lines, quoteTotalExGst, quoteTotalIncGst, gpPercent);
    }

    /**
     * Live GP percent on the quote total, or null when not computable (zero / non-positive ex total).
     * Used by the workspace read (which never throws on below-cost). Margin on sale ex-GST:
     * {@code (quoteEx − cost) / quoteEx × 100}, HALF_UP 2dp.
     */
    public BigDecimal gpPercent(BigDecimal quoteTotalExGst, BigDecimal totalCostExGst) {
        if (quoteTotalExGst == null || quoteTotalExGst.signum() <= 0) {
            return null;
        }
        BigDecimal cost = totalCostExGst == null ? ZERO : totalCostExGst;
        BigDecimal gp = quoteTotalExGst.subtract(cost);
        return gp.multiply(HUNDRED).divide(quoteTotalExGst, MONEY_SCALE, ROUNDING);
    }

    /** Live below-cost flag for the workspace read: quote sale ex-GST below the order total cost ex-GST. */
    public boolean belowCost(BigDecimal quoteTotalExGst, BigDecimal totalCostExGst) {
        if (quoteTotalExGst == null) {
            return false;
        }
        BigDecimal cost = totalCostExGst == null ? ZERO : totalCostExGst;
        return quoteTotalExGst.subtract(cost).signum() < 0;
    }

    private static List<QuoteComputedLine> recomputeLineTotals(List<QuoteLineInput> inputs) {
        List<QuoteComputedLine> result = new ArrayList<>(inputs.size());
        for (QuoteLineInput in : inputs) {
            if (in.isItem()) {
                // Server recomputes the ITEM total — never trust the client line_total_ex_gst.
                BigDecimal lineTotal = in.quantity().multiply(in.unitPriceExGst()).setScale(MONEY_SCALE, ROUNDING);
                result.add(new QuoteComputedLine("ITEM", in.description(),
                        in.quantity().setScale(MONEY_SCALE, ROUNDING),
                        in.unitPriceExGst().setScale(MONEY_SCALE, ROUNDING),
                        lineTotal, in.sortOrder()));
            } else {
                // ADJUSTMENT: signed total as supplied (qty/unit are null).
                result.add(new QuoteComputedLine("ADJUSTMENT", in.description(), null, null,
                        in.lineTotalExGst().setScale(MONEY_SCALE, ROUNDING), in.sortOrder()));
            }
        }
        return result;
    }

    private static BigDecimal sumLineTotals(List<QuoteComputedLine> lines) {
        BigDecimal sum = ZERO;
        for (QuoteComputedLine line : lines) {
            sum = sum.add(line.lineTotalExGst());
        }
        return sum.setScale(MONEY_SCALE, ROUNDING);
    }

    /**
     * The {@code sort_order} for an auto-inserted ADJUSTMENT line. Normally one past the current max so
     * the adjustment appends after every customer line ({@code max + 1}). EDGE: when a customer line
     * already sits at the {@link #MAX_SORT_ORDER} cap, {@code max + 1} would be {@code MAX_SORT_ORDER + 1}
     * — out of range — and the returned draft would then be REJECTED on an unchanged resave (the
     * resave-lockout: {@code QuoteService.readRequiredNonNegativeInt} rejects {@code > MAX_SORT_ORDER}).
     * In that edge we take the LOWEST UNUSED sort_order in {@code [0, MAX_SORT_ORDER]} instead: always a
     * valid, resaveable, non-duplicate value — never {@code 1_000_001} and never a duplicate of the cap
     * (a duplicate is what {@code Math.min(max + 1, MAX_SORT_ORDER)} would produce). Existing customer
     * lines are never reordered.
     */
    private static int nextSortOrder(List<QuoteComputedLine> lines) {
        int max = -1;
        for (QuoteComputedLine line : lines) {
            max = Math.max(max, line.sortOrder());
        }
        if (max < MAX_SORT_ORDER) {
            return max + 1;
        }
        return lowestUnusedSortOrder(lines);
    }

    /** Lowest {@code sort_order} in {@code [0, MAX_SORT_ORDER]} not already used by a line (in range, no duplicate). */
    private static int lowestUnusedSortOrder(List<QuoteComputedLine> lines) {
        Set<Integer> used = new HashSet<>(Math.max(8, lines.size() * 2));
        for (QuoteComputedLine line : lines) {
            used.add(line.sortOrder());
        }
        for (int candidate = 0; candidate <= MAX_SORT_ORDER; candidate++) {
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        // Unreachable with any realistic line count (all 1,000,001 slots filled); deterministic backstop.
        return MAX_SORT_ORDER;
    }

    private static void requireFitsMoney(BigDecimal value, String field) {
        BigDecimal scaled = value.setScale(MONEY_SCALE, ROUNDING);
        if (scaled.precision() - scaled.scale() > MONEY_MAX_INTEGER_DIGITS) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, field,
                            "Must be at most " + MONEY_MAX_LABEL + ".")));
        }
    }
}
