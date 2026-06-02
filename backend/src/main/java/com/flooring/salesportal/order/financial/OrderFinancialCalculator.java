package com.flooring.salesportal.order.financial;

import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, shared computation of the live {@code order_financial_summary} (conventions §12, Chunk 3
 * E.1 / F.5). One instance is used by GET /lines (compute only) and by every product-line mutation
 * (compute, then the service persists the {@code sales_order} header scalars), so a read after a
 * write always returns the same numbers for the same persisted state.
 *
 * <p>All arithmetic is {@link BigDecimal} with {@link RoundingMode#HALF_UP} on every
 * {@code setScale}/{@code divide} — the rounding mode is unspecified in the docs, and
 * {@code setScale(2)} without a mode would throw {@link ArithmeticException} on the non-terminating
 * {@code / 1.10} division (e.g. {@code 1000.00 / 1.10}). HALF_UP matches the locked worked examples
 * (conventions §12: {@code 924.00 → 840.00}; {@code 822.00 / 1.10 = 747.27}).
 *
 * <p>Inputs are the per-table aggregates ({@link LineFinancials}) for products and charges plus the
 * persisted {@code sales_order.price_adjustment_inc_gst} (nullable; Branch 2 never writes it). The
 * caller folds in already-existing charge lines so subtotals/cost are complete even though
 * charge-line CRUD is out of Branch 2 scope.
 */
@Component
public class OrderFinancialCalculator {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal GST_MULTIPLIER = new BigDecimal("1.10");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal GP_WARNING_THRESHOLD = new BigDecimal("15");

    public OrderFinancialSummaryDto compute(LineFinancials productLines,
                                            LineFinancials chargeLines,
                                            BigDecimal priceAdjustmentIncGst) {
        BigDecimal productSubtotal = money(productLines.subtotal());
        BigDecimal chargeSubtotal = money(chargeLines.subtotal());

        BigDecimal calculatedTotalIncGst = productSubtotal.add(chargeSubtotal)
                .multiply(GST_MULTIPLIER)
                .setScale(MONEY_SCALE, ROUNDING);

        BigDecimal finalSalePriceIncGst = priceAdjustmentIncGst == null
                ? calculatedTotalIncGst
                : calculatedTotalIncGst.add(priceAdjustmentIncGst).setScale(MONEY_SCALE, ROUNDING);

        BigDecimal salePriceExGst = finalSalePriceIncGst.divide(GST_MULTIPLIER, MONEY_SCALE, ROUNDING);

        BigDecimal totalCost = money(productLines.cost()).add(money(chargeLines.cost()))
                .setScale(MONEY_SCALE, ROUNDING);

        BigDecimal gp = salePriceExGst.subtract(totalCost).setScale(MONEY_SCALE, ROUNDING);

        BigDecimal gpPercent = salePriceExGst.compareTo(BigDecimal.ZERO) > 0
                ? gp.multiply(HUNDRED).divide(salePriceExGst, MONEY_SCALE, ROUNDING)
                : null;

        boolean gpWarning = gpPercent != null && gpPercent.compareTo(GP_WARNING_THRESHOLD) < 0;

        return new OrderFinancialSummaryDto(
                productSubtotal,
                chargeSubtotal,
                calculatedTotalIncGst,
                priceAdjustmentIncGst,
                finalSalePriceIncGst,
                salePriceExGst,
                totalCost,
                gp,
                gpPercent,
                gpWarning);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, ROUNDING);
    }
}
