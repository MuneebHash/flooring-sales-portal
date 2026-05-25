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
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastEmailedAt,
        int weekYear,
        int weekNumber,
        BigDecimal gp
) {
}
