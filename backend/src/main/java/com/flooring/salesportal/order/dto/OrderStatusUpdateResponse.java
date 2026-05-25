package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record OrderStatusUpdateResponse(
        long orderId,
        String orderNumber,
        String orderStatus,
        String previousOrderStatus,
        boolean locked,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
) {
}
