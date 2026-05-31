package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.dto.AddressUpsertRequest;
import com.flooring.salesportal.order.dto.BillingAddressResponse;
import com.flooring.salesportal.order.dto.CreateOrderRequest;
import com.flooring.salesportal.order.dto.CustomerSaveRequest;
import com.flooring.salesportal.order.dto.CustomerSaveResponse;
import com.flooring.salesportal.order.dto.InstallationAddressResponse;
import com.flooring.salesportal.order.dto.OrderHeaderResponse;
import com.flooring.salesportal.order.dto.OrderWorkspaceResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    // orderId is captured as a String so non-numeric and non-positive values both flow through
    // the service's manual validation and produce VALIDATION_FAILED with field "order_id".
    @PutMapping("/{orderId}/customer")
    public ApiResponse<CustomerSaveResponse> saveCustomer(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) CustomerSaveRequest body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(orderService.saveCustomer(slug, orderId, body, httpRequest), "Customer saved.");
    }

    // orderId is captured as a String so non-numeric and non-positive values both flow through
    // the service's manual validation and produce VALIDATION_FAILED with field "order_id".
    // @RequestBody(required = false): a missing/empty body reaches the service as null and is
    // handled by gate-first validation (404/422 before body validation), never an NPE.
    @PutMapping("/{orderId}/addresses/installation")
    public ApiResponse<InstallationAddressResponse> saveInstallationAddress(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) AddressUpsertRequest body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderService.saveInstallationAddress(slug, orderId, body, httpRequest),
                "Installation address saved.");
    }

    // orderId is captured as a String so non-numeric and non-positive values both flow through
    // the service's manual validation and produce VALIDATION_FAILED with field "order_id".
    @PutMapping("/{orderId}/addresses/billing")
    public ApiResponse<BillingAddressResponse> saveBillingAddress(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) AddressUpsertRequest body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderService.saveBillingAddress(slug, orderId, body, httpRequest),
                "Billing address saved.");
    }

    // orderId is captured as a String so non-numeric and non-positive values both flow through
    // the service's manual validation and produce VALIDATION_FAILED with field "order_id".
    // No request body: the backend copies the existing installation address into billing.
    @PostMapping("/{orderId}/addresses/billing/copy-from-installation")
    public ApiResponse<BillingAddressResponse> copyBillingFromInstallation(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderService.copyBillingFromInstallation(slug, orderId, httpRequest),
                "Billing address copied from installation address.");
    }
}
