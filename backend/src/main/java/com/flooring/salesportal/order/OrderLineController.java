package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.dto.OrderLinesResponse;
import com.flooring.salesportal.order.dto.ProductLineDeleteResponse;
import com.flooring.salesportal.order.dto.ProductLineMutationResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chunk 3 Branch 2 order line endpoints (D.3–D.6). Shares the {@code /orders/{orderId}} base path
 * with {@code OrderCatalogController}; handler mappings stay distinct by method + sub-path
 * ({@code /lines}, {@code /product-lines}, {@code /product-lines/{lineId}}).
 *
 * <p>{@code orderId} and {@code lineId} are captured as {@code String} (matching the rest of the
 * order endpoints) so non-numeric / non-positive values become VALIDATION_FAILED on
 * {@code order_id} / {@code line_id} in the service rather than path-binding errors. The mutation
 * bodies are taken as a raw {@code String} ({@code required = false}) so JSON parsing happens inside
 * the service, strictly after the guard / order-scope / LAID / line-scope gates.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}")
public class OrderLineController {

    private final OrderProductLineService orderProductLineService;

    public OrderLineController(OrderProductLineService orderProductLineService) {
        this.orderProductLineService = orderProductLineService;
    }

    @GetMapping("/lines")
    public ApiResponse<OrderLinesResponse> getLines(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(orderProductLineService.getLines(slug, orderId, httpRequest));
    }

    @PostMapping("/product-lines")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductLineMutationResponse> addProductLine(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderProductLineService.addProductLine(slug, orderId, body, httpRequest),
                "Product line added.");
    }

    @PatchMapping("/product-lines/{lineId}")
    public ApiResponse<ProductLineMutationResponse> updateProductLine(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @PathVariable("lineId") String lineId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderProductLineService.updateProductLine(slug, orderId, lineId, body, httpRequest),
                "Product line updated.");
    }

    @DeleteMapping("/product-lines/{lineId}")
    public ApiResponse<ProductLineDeleteResponse> deleteProductLine(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @PathVariable("lineId") String lineId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderProductLineService.deleteProductLine(slug, orderId, lineId, httpRequest),
                "Product line deleted.");
    }
}
