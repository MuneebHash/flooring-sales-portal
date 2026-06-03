package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.dto.SalePriceMutationResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chunk 3 Branch 6 sale-price endpoints (D.10 PUT /sale-price, D.11 POST /sale-price/reset). Shares the
 * {@code /orders/{orderId}} base path with the other order controllers; handler mappings stay distinct
 * by method + sub-path. Both endpoints return 200 (the reset is a POST but creates no resource, so no
 * {@code @ResponseStatus(CREATED)}).
 *
 * <p>{@code orderId} is captured as {@code String} (matching the rest of the order endpoints) so a
 * non-numeric / non-positive value becomes VALIDATION_FAILED on {@code order_id} in the service rather
 * than a path-binding error. The override body is taken as a raw {@code String} ({@code required =
 * false}) so JSON parsing happens inside the service, strictly after the guard / order-scope / LAID
 * gates. Reset takes no body.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}")
public class OrderSalePriceController {

    private final OrderSalePriceService orderSalePriceService;

    public OrderSalePriceController(OrderSalePriceService orderSalePriceService) {
        this.orderSalePriceService = orderSalePriceService;
    }

    @PutMapping("/sale-price")
    public ApiResponse<SalePriceMutationResponse> overrideSalePrice(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderSalePriceService.overrideSalePrice(slug, orderId, body, httpRequest),
                "Sale price updated.");
    }

    @PostMapping("/sale-price/reset")
    public ApiResponse<SalePriceMutationResponse> resetSalePrice(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderSalePriceService.resetSalePrice(slug, orderId, httpRequest),
                "Sale price reset.");
    }
}
