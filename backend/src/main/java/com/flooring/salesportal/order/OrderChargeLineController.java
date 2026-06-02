package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.dto.ChargeLineDeleteResponse;
import com.flooring.salesportal.order.dto.ChargeLineMutationResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chunk 3 Branch 3 order charge-line endpoints (D.7–D.9). Shares the {@code /orders/{orderId}} base
 * path with {@code OrderLineController} (product lines + GET /lines) and {@code OrderCatalogController};
 * handler mappings stay distinct by method + sub-path ({@code /charge-lines},
 * {@code /charge-lines/{lineId}}).
 *
 * <p>{@code orderId} and {@code lineId} are captured as {@code String} (matching the rest of the order
 * endpoints) so non-numeric / non-positive values become VALIDATION_FAILED on {@code order_id} /
 * {@code line_id} in the service rather than path-binding errors. The mutation bodies are taken as a
 * raw {@code String} ({@code required = false}) so JSON parsing happens inside the service, strictly
 * after the guard / order-scope / LAID / line-scope gates.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}")
public class OrderChargeLineController {

    private final OrderChargeLineService orderChargeLineService;

    public OrderChargeLineController(OrderChargeLineService orderChargeLineService) {
        this.orderChargeLineService = orderChargeLineService;
    }

    @PostMapping("/charge-lines")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChargeLineMutationResponse> addChargeLine(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderChargeLineService.addChargeLine(slug, orderId, body, httpRequest),
                "Charge line added.");
    }

    @PatchMapping("/charge-lines/{lineId}")
    public ApiResponse<ChargeLineMutationResponse> updateChargeLine(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @PathVariable("lineId") String lineId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderChargeLineService.updateChargeLine(slug, orderId, lineId, body, httpRequest),
                "Charge line updated.");
    }

    @DeleteMapping("/charge-lines/{lineId}")
    public ApiResponse<ChargeLineDeleteResponse> deleteChargeLine(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @PathVariable("lineId") String lineId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                orderChargeLineService.deleteChargeLine(slug, orderId, lineId, httpRequest),
                "Charge line deleted.");
    }
}
