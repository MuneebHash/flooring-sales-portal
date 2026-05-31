package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code POST /orders} 201 response body (Chunk 2 create-order shape / OpenAPI {@code OrderHeader}).
 * Field order follows the contract. Timestamps use ISO local-seconds (conventions §5).
 */
public record OrderHeaderResponse(
        long orderId,
        String orderNumber,
        int orderSequenceNumber,
        String flooringType,
        String orderStatus,
        boolean supplyOnly,
        String planNumbers,
        LocalDate proposedLayDate,
        String layDateStatus,
        String detailsOfSale,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastEmailedAt,
        int weekYear,
        int weekNumber,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt,
        boolean locked
) {
}
