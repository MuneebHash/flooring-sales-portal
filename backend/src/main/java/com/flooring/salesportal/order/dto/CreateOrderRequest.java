package com.flooring.salesportal.order.dto;

/**
 * Request body for {@code POST /orders}: {@code { "flooring_type": "SOFT" | "HARD" }}.
 * Global snake_case Jackson maps {@code flooring_type} to {@code flooringType}.
 * Presence/enum validation happens in {@code OrderService}.
 */
public record CreateOrderRequest(String flooringType) {
}
