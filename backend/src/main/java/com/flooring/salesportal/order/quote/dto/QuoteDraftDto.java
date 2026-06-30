package com.flooring.salesportal.order.quote.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The editable quote draft in the workspace read and the PUT save response (openapi {@code QuoteDraft}).
 *
 * <p>{@code quoteTotalExGst} is the sum of the lines; {@code quoteTotalIncGst} is the GST-grossed
 * total. {@code gpPercent} is the server-computed GP on the quote total (null when not computable —
 * e.g. a zero total); it is serialized as JSON null (no NON_NULL) so the frontend can distinguish
 * "not computable" from a real percent. {@code belowCost} is the live below-cost flag (save is
 * blocked when true, but a workspace read can surface true if the order's cost lines changed after
 * the last save). No raw cost / line cost is ever exposed.
 */
public record QuoteDraftDto(
        boolean itemised,
        BigDecimal quoteTotalExGst,
        BigDecimal quoteTotalIncGst,
        BigDecimal gpPercent,
        boolean belowCost,
        List<QuoteDraftLineDto> lines,
        LocalDateTime updatedAt
) {
}
