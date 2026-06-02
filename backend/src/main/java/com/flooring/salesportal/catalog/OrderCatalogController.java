package com.flooring.salesportal.catalog;

import com.flooring.salesportal.catalog.dto.AvailableChargeResponse;
import com.flooring.salesportal.catalog.dto.AvailableProductResponse;
import com.flooring.salesportal.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Chunk 3 catalog search endpoints (D.1 / D.2):
 * <ul>
 *   <li>{@code GET /api/v1/{slug}/orders/{orderId}/available-products}</li>
 *   <li>{@code GET /api/v1/{slug}/orders/{orderId}/available-charges}</li>
 * </ul>
 *
 * <p>Maps under the {@code /orders/{orderId}} sub-path; the extra path segment keeps it distinct
 * from {@code OrderController}'s {@code GET /orders/{orderId}} and {@code DashboardOrderController}'s
 * {@code GET /orders}. {@code orderId} is captured as a String (matching {@code OrderController})
 * so non-numeric / non-positive values produce VALIDATION_FAILED on field {@code order_id} in the
 * service rather than a path-binding error. {@code page} / {@code page_size} are captured as
 * Integer (matching {@code DashboardOrderController}) so a non-numeric value becomes
 * VALIDATION_FAILED on that parameter via the global type-mismatch handler.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}")
public class OrderCatalogController {

    private final OrderCatalogService orderCatalogService;

    public OrderCatalogController(OrderCatalogService orderCatalogService) {
        this.orderCatalogService = orderCatalogService;
    }

    @GetMapping("/available-products")
    public ApiResponse<List<AvailableProductResponse>> listAvailableProducts(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "search", required = false) String search,
            HttpServletRequest httpRequest) {

        return orderCatalogService.listProducts(slug, orderId, page, pageSize, search, httpRequest);
    }

    @GetMapping("/available-charges")
    public ApiResponse<List<AvailableChargeResponse>> listAvailableCharges(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "search", required = false) String search,
            HttpServletRequest httpRequest) {

        return orderCatalogService.listCharges(slug, orderId, page, pageSize, search, httpRequest);
    }
}
