package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code GET /orders/{orderId}} 200 response body (Chunk 2 workspace read / OpenAPI
 * {@code OrderWorkspace}): header + nullable customer + nullable install/billing addresses +
 * always-present {@code persisted_financials}. The live {@code order_financial_summary} block is
 * intentionally NOT included (Chunk 3 owns it).
 */
public record OrderWorkspaceResponse(
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
        boolean locked,
        CustomerDto customer,
        AddressDto installAddress,
        AddressDto billingAddress,
        PersistedFinancialsDto persistedFinancials
) {
}
