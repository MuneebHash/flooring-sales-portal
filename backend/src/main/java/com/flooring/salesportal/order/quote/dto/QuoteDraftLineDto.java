package com.flooring.salesportal.order.quote.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * One customer-facing quote draft line in the {@code QuoteDraft} response (openapi {@code QuoteDraftLine}).
 * {@code quoteDraftLineId} is present on the workspace read and omitted on the PUT save response
 * (freshly written lines are not re-read within the write transaction). {@code quantity}/
 * {@code unitPriceExGst} are null for ADJUSTMENT lines. NO cost field is ever included.
 */
public record QuoteDraftLineDto(
        @JsonInclude(JsonInclude.Include.NON_NULL) Long quoteDraftLineId,
        String lineType,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal quantity,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal unitPriceExGst,
        BigDecimal lineTotalExGst,
        int sortOrder
) {
}
