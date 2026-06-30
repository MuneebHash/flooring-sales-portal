package com.flooring.salesportal.order.quote;

import java.math.BigDecimal;

/**
 * A quote-draft line AFTER server money computation — what {@link QuoteDraftCalculator} returns and
 * {@link QuoteDraftWriteRepository} persists. For an ITEM line {@code lineTotalExGst} has been
 * recomputed as {@code quantity * unitPriceExGst} (HALF_UP, 2dp); for an ADJUSTMENT line it is the
 * signed value and {@code quantity}/{@code unitPriceExGst} are null.
 */
public record QuoteComputedLine(
        String lineType,
        String description,
        BigDecimal quantity,
        BigDecimal unitPriceExGst,
        BigDecimal lineTotalExGst,
        int sortOrder
) {
}
