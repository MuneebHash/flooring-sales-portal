package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.dto.OrderStatusUpdateRequest;
import com.flooring.salesportal.order.dto.OrderStatusUpdateResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}/status")
public class OrderStatusController {

    private final OrderStatusService orderStatusService;

    public OrderStatusController(OrderStatusService orderStatusService) {
        this.orderStatusService = orderStatusService;
    }

    // orderId is captured as a String so non-numeric and non-positive values both flow through
    // the service's manual validation and produce VALIDATION_FAILED with field "order_id".
    @PatchMapping
    public ApiResponse<OrderStatusUpdateResponse> updateStatus(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody OrderStatusUpdateRequest body,
            HttpServletRequest httpRequest) {
        return orderStatusService.updateStatus(slug, orderId, body, httpRequest);
    }
}
