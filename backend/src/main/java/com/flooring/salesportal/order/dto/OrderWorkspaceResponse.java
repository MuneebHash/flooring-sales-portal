package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code GET /orders/{orderId}} 200 response body (Chunk 2 workspace read / OpenAPI
 * {@code OrderWorkspace}): header + order-bound {@code salesperson_name} + nullable customer +
 * nullable install/billing addresses + always-present {@code persisted_financials}. The live
 * {@code order_financial_summary} block is intentionally NOT included (Chunk 3 owns it).
 *
 * <p>{@code salespersonName} (Phase 15C PR2) is the order's salesperson — {@code sales_order.user_id}
 * resolved to {@code app_user.first_name + last_name} via {@code OrderSalespersonResolver}, the same
 * source the invoice PDF uses, NOT the session user. Null when the user row is missing.
 *
 * <p>{@code enquiry} (Phase 15F lead enquiry form) is the nullable one-per-order {@code order_enquiry}
 * object — {@code null} when no enquiry row has been saved yet (read-only here; the write is the
 * separate {@code PUT /orders/{orderId}/enquiry}).
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
        String salespersonName,
        CustomerDto customer,
        AddressDto installAddress,
        AddressDto billingAddress,
        PersistedFinancialsDto persistedFinancials,
        EnquiryDto enquiry
) {
}
