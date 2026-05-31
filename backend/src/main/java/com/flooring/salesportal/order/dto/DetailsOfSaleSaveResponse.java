package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * Response {@code data} for {@code PUT /orders/{orderId}/details-of-sale}:
 * {@code { "details_of_sale_fields": {...}, "updated_at": "..." }}. These two keys are the entire
 * payload — the response intentionally carries NO {@code order_financial_summary}, sale price /
 * cost / GP fields, customer, addresses, or internal IDs (Chunk 2 §D.7 / §E.7). Timestamp uses
 * ISO local-seconds (conventions §5).
 */
public record DetailsOfSaleSaveResponse(
        DetailsOfSaleFieldsDto detailsOfSaleFields,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
) {
}
