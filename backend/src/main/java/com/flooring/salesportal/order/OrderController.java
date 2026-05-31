package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.dto.CreateOrderRequest;
import com.flooring.salesportal.order.dto.OrderHeaderResponse;
import com.flooring.salesportal.order.dto.OrderWorkspaceResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 10A order shell + workspace read. Shares the {@code /orders} base path with
 * {@code DashboardOrderController} (GET /orders) — handler mappings stay distinct by method/path:
 * POST /orders and GET /orders/{orderId} here vs. GET /orders there.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderHeaderResponse> createOrder(
            @PathVariable String slug,
            @RequestBody(required = false) CreateOrderRequest body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(orderService.createOrder(slug, body, httpRequest), "Order created.");
    }

    // orderId is captured as a String so non-numeric and non-positive values both flow through
    // the service's manual validation and produce VALIDATION_FAILED with field "order_id".
    @GetMapping("/{orderId}")
    public ApiResponse<OrderWorkspaceResponse> getOrder(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(orderService.getOrder(slug, orderId, httpRequest));
    }
}
