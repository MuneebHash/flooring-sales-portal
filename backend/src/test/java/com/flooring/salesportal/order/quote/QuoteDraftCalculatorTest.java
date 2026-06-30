package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pure unit tests for {@link QuoteDraftCalculator} — the Phase 16C PR1 quote money core. No Spring.
 * Verifies the locked decisions: ex-primary {@code final_total_inc_gst} conversion + 1¢ tolerance
 * (Q1), the non-itemised synthetic line (Q2), the line-sum invariant, the EXCEEDS rejection, and the
 * below-cost block. Money is BigDecimal scale 2 HALF_UP, GST ×1.10.
 */
class QuoteDraftCalculatorTest {

    private final QuoteDraftCalculator calc = new QuoteDraftCalculator();

    private static final BigDecimal NO_COST = bd("0.00");

    private static QuoteLineInput item(String desc, String qty, String unit, int sort) {
        return new QuoteLineInput("ITEM", desc, bd(qty), bd(unit), null, sort);
    }

    private static QuoteLineInput adjustment(String desc, String total, int sort) {
        return new QuoteLineInput("ADJUSTMENT", desc, null, null, bd(total), sort);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static BigDecimal sumLines(QuoteDraftComputation c) {
        BigDecimal sum = BigDecimal.ZERO;
        for (QuoteComputedLine l : c.lines()) {
            sum = sum.add(l.lineTotalExGst());
        }
        return sum;
    }

    @Test
    void itemisedLines_computeExAndIncTotals() {
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("Carpet", "2.00", "100.00", 0), item("Underlay", "1.00", "50.00", 1)),
                null, NO_COST);

        assertMoney("250.00", c.quoteTotalExGst());
        assertMoney("275.00", c.quoteTotalIncGst()); // 250 * 1.10
        assertMoney("100.00", c.gpPercent());        // (250 - 0) / 250 * 100
        assertEquals(2, c.lines().size());
        assertEquals(0, c.quoteTotalExGst().compareTo(sumLines(c)), "invariant: total == sum(lines)");
    }

    @Test
    void itemLineTotal_isRecomputedServerSide_notTakenFromClient() {
        // 3 * 33.33 = 99.99 (HALF_UP). The calculator computes this regardless of any client total.
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("Tiles", "3.00", "33.33", 0)), null, NO_COST);
        assertMoney("99.99", c.quoteTotalExGst());
        assertMoney("99.99", c.lines().get(0).lineTotalExGst());
    }

    @Test
    void finalBelowLineSum_insertsNegativeAdjustment_invariantHolds() {
        // lines ex 250 (inc 275). final 220 inc => target_ex 200 => adjustment -50.
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("Carpet", "2.00", "100.00", 0), item("Underlay", "1.00", "50.00", 1)),
                bd("220.00"), NO_COST);

        assertMoney("200.00", c.quoteTotalExGst());
        assertMoney("220.00", c.quoteTotalIncGst());
        assertEquals(3, c.lines().size());
        QuoteComputedLine adj = c.lines().get(2);
        assertEquals("ADJUSTMENT", adj.lineType());
        assertMoney("-50.00", adj.lineTotalExGst());
        assertNull(adj.quantity());
        assertNull(adj.unitPriceExGst());
        assertEquals(2, adj.sortOrder()); // max existing (1) + 1
        assertEquals(0, c.quoteTotalExGst().compareTo(sumLines(c)), "invariant after adjustment");
    }

    @Test
    void finalAboveLineSum_throwsExceeds() {
        // lines ex 250. final 300 inc => target_ex 272.73 => delta +22.73 > 1c.
        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> calc.compute(true,
                List.of(item("Carpet", "2.00", "100.00", 0), item("Underlay", "1.00", "50.00", 1)),
                bd("300.00"), NO_COST));
        assertSame(ErrorCode.QUOTE_TOTAL_EXCEEDS_LINES, ex.getErrorCode());
    }

    @Test
    void finalEqualToLineSumInc_withinTolerance_noAdjustment_noError() {
        // No-op resave shape: final == round(lineSum * 1.10). delta ~ 0 => treat as equal.
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("Carpet", "2.00", "100.00", 0), item("Underlay", "1.00", "50.00", 1)),
                bd("275.00"), NO_COST);
        assertMoney("250.00", c.quoteTotalExGst());
        assertEquals(2, c.lines().size()); // no adjustment inserted
    }

    @Test
    void finalOneCentAbove_withinTolerance_treatedEqual() {
        // lines ex 100 (inc 110.00). final 110.01 => target_ex 100.01 => delta +0.01 (== tolerance).
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("Carpet", "1.00", "100.00", 0)), bd("110.01"), NO_COST);
        assertMoney("100.00", c.quoteTotalExGst());
        assertEquals(1, c.lines().size());
    }

    @Test
    void finalTwoCentsAbove_outsideTolerance_throwsExceeds() {
        // lines ex 100. final 110.02 => target_ex 100.02 => delta +0.02 > tolerance.
        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> calc.compute(true,
                List.of(item("Carpet", "1.00", "100.00", 0)), bd("110.02"), NO_COST));
        assertSame(ErrorCode.QUOTE_TOTAL_EXCEEDS_LINES, ex.getErrorCode());
    }

    @Test
    void belowCost_throwsBelowCost() {
        // quote ex 40 < order cost ex 50 => GP negative.
        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> calc.compute(true,
                List.of(item("Carpet", "1.00", "40.00", 0)), null, bd("50.00")));
        assertSame(ErrorCode.QUOTE_BELOW_COST, ex.getErrorCode());
    }

    @Test
    void atCost_gpZero_allowed() {
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("Carpet", "1.00", "50.00", 0)), null, bd("50.00"));
        assertMoney("50.00", c.quoteTotalExGst());
        assertMoney("0.00", c.gpPercent());
    }

    @Test
    void adjustmentLine_signedTotalUsedAsIs() {
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("Carpet", "2.00", "100.00", 0), adjustment("Discount", "-30.00", 1)),
                null, NO_COST);
        assertMoney("170.00", c.quoteTotalExGst()); // 200 - 30
        assertEquals(2, c.lines().size());
        assertEquals(0, c.quoteTotalExGst().compareTo(sumLines(c)));
    }

    @Test
    void nonItemisedWithFinal_replacesWithSingleSyntheticLine() {
        // itemised=false + final 550 inc => single ITEM "Quoted works" qty 1 unit 500 total 500.
        QuoteDraftComputation c = calc.compute(false,
                List.of(item("ignored", "9.00", "9.00", 0)), bd("550.00"), NO_COST);
        assertEquals(1, c.lines().size());
        QuoteComputedLine line = c.lines().get(0);
        assertEquals("ITEM", line.lineType());
        assertEquals("Quoted works", line.description());
        assertMoney("1.00", line.quantity());
        assertMoney("500.00", line.unitPriceExGst());
        assertMoney("500.00", line.lineTotalExGst());
        assertMoney("500.00", c.quoteTotalExGst());
        assertMoney("550.00", c.quoteTotalIncGst());
    }

    @Test
    void zeroTotal_gpPercentNull_allowedWhenNoCost() {
        QuoteDraftComputation c = calc.compute(true, List.of(), null, NO_COST);
        assertMoney("0.00", c.quoteTotalExGst());
        assertMoney("0.00", c.quoteTotalIncGst());
        assertNull(c.gpPercent());
        assertTrue(c.lines().isEmpty());
    }

    @Test
    void gpPercentHelper_nullOnZeroExTotal() {
        assertNull(calc.gpPercent(bd("0.00"), bd("0.00")));
        assertMoney("20.00", calc.gpPercent(bd("100.00"), bd("80.00"))); // (100-80)/100*100
    }

    @Test
    void belowCostHelper_flagsCorrectly() {
        assertTrue(calc.belowCost(bd("40.00"), bd("50.00")));
        assertFalse(calc.belowCost(bd("50.00"), bd("50.00")));
        assertFalse(calc.belowCost(bd("60.00"), bd("50.00")));
    }

    // ================================================================
    // Sort-order fix-forward (PR1 resave-lockout): the generated ADJUSTMENT sort_order must never
    // exceed MAX_SORT_ORDER, so a returned draft is always resaveable unchanged.
    // ================================================================

    @Test
    void maxSortOrder_constant_isOneMillion() {
        assertEquals(1_000_000, QuoteDraftCalculator.MAX_SORT_ORDER);
    }

    @Test
    void directReduction_lineAtMaxSortOrder_adjustmentUsesLowestUnused_notMaxPlusOne() {
        // A single ITEM at the cap (1,000,000) + a direct reduction forces an ADJUSTMENT. The OLD code
        // generated max+1 = 1,000,001 (out of range -> unsaveable on resave). The fix uses the lowest
        // unused sort_order in [0, MAX] -> 0 here (only 1,000,000 is used).
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("Carpet", "1.00", "100.00", 1_000_000)),
                bd("55.00"), NO_COST); // target ex 50 => adjustment -50
        assertEquals(2, c.lines().size());
        QuoteComputedLine adj = c.lines().get(1);
        assertEquals("ADJUSTMENT", adj.lineType());
        assertMoney("-50.00", adj.lineTotalExGst());
        assertEquals(0, adj.sortOrder(), "adjustment must take the lowest unused slot, not 1,000,001");
        assertTrue(adj.sortOrder() >= 0 && adj.sortOrder() <= QuoteDraftCalculator.MAX_SORT_ORDER,
                "generated adjustment sort_order must stay in [0, MAX_SORT_ORDER]");
    }

    @Test
    void directReduction_lineAtMaxSortOrder_lowestUnusedSkipsUsedSlots() {
        // Two ITEMs at sort 0 and the cap: lowest unused is 1 (0 is taken), never a duplicate, never max+1.
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("A", "1.00", "60.00", 0), item("B", "1.00", "40.00", 1_000_000)),
                bd("55.00"), NO_COST); // line sum 100, target ex 50 => adjustment -50
        assertEquals(3, c.lines().size());
        QuoteComputedLine adj = c.lines().get(2);
        assertEquals("ADJUSTMENT", adj.lineType());
        assertEquals(1, adj.sortOrder(), "lowest unused slot skips the used 0 and the used cap");
    }

    @Test
    void directReduction_normalCase_stillAppendsAdjustmentAtMaxPlusOne() {
        // Unchanged normal behavior: max existing sort < cap => append at max+1.
        QuoteDraftComputation c = calc.compute(true,
                List.of(item("Carpet", "2.00", "100.00", 0), item("Underlay", "1.00", "50.00", 1)),
                bd("220.00"), NO_COST);
        assertEquals(3, c.lines().size());
        QuoteComputedLine adj = c.lines().get(2);
        assertEquals("ADJUSTMENT", adj.lineType());
        assertEquals(2, adj.sortOrder(), "max existing (1) + 1");
    }
}
