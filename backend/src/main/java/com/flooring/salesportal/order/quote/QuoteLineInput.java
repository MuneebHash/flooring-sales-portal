package com.flooring.salesportal.order.quote;

import java.math.BigDecimal;

/**
 * A validated quote-draft line as parsed from the request body, BEFORE money recomputation. The
 * service produces these after the allow-list / shape validation; {@link QuoteDraftCalculator} then
 * recomputes ITEM line totals server-side (qty * unit) and uses the signed ADJUSTMENT total as-is.
 * Values are already scaled to 2dp HALF_UP by the service parser.
 */
public record QuoteLineInput(
        String lineType,
        String description,
        BigDecimal quantity,
        BigDecimal unitPriceExGst,
        BigDecimal lineTotalExGst,
        int sortOrder
) {

    public boolean isItem() {
        return "ITEM".equals(lineType);
    }
}
