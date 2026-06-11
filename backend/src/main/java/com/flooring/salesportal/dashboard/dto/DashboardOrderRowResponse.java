package com.flooring.salesportal.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DashboardOrderRowResponse(
        long orderId,
        String orderNumber,
        int orderSequenceNumber,
        String flooringType,
        String orderStatus,
        DashboardCustomerResponse customer,
        DashboardInstallAddressResponse installAddress,
        // Phase 13 §11: whether the order's CURRENT (max version_number) invoice is accepted; false
        // when no invoice exists. Always a boolean — there is deliberately NO invoice-status enum.
        boolean invoiceAccepted,
        // Mirror of the current invoice's last_emailed_at (Phase 13 §11.1) — null until that exact
        // version has been emailed; always present in the JSON (null serializes as null).
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastEmailedAt,
        int weekYear,
        int weekNumber,
        BigDecimal gp
) {
}
