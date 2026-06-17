package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.dto.PaymentsListResponse;
import com.flooring.salesportal.order.dto.RecordPaymentResponse;
import com.flooring.salesportal.order.dto.VoidPaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 12 Chunk 4 payment endpoints (Branch D): D.6 GET /orders/{orderId}/payments (list) and D.7 POST
 * /orders/{orderId}/payments (record a payment + atomically regenerate the current invoice). Shares the
 * {@code /orders/{orderId}} base path with the other order sub-resource controllers; handler mappings
 * stay distinct by method + the {@code /payments} sub-path.
 *
 * <p>{@code orderId} is captured as a {@code String} (matching the rest of the order endpoints) so a
 * non-numeric / non-positive value becomes VALIDATION_FAILED on {@code order_id} in the service rather
 * than a path-binding error. {@code page} / {@code page_size} are captured as {@code Integer} (matching
 * {@code OrderNoteController}) so a non-numeric value becomes VALIDATION_FAILED on that parameter via the
 * global type-mismatch handler. The POST body is taken as a raw {@code String} ({@code required = false})
 * so JSON parsing happens inside the service, strictly after the guard / order-scope gates. The service
 * builds the {@code ApiResponse} envelope for both endpoints.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}")
public class OrderPaymentController {

    private final OrderPaymentService orderPaymentService;

    public OrderPaymentController(OrderPaymentService orderPaymentService) {
        this.orderPaymentService = orderPaymentService;
    }

    @GetMapping("/payments")
    public ApiResponse<PaymentsListResponse> listPayments(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            HttpServletRequest httpRequest) {

        return orderPaymentService.getPayments(slug, orderId, page, pageSize, httpRequest);
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RecordPaymentResponse> recordPayment(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {

        return orderPaymentService.recordPayment(slug, orderId, body, httpRequest);
    }

    /**
     * D.10 (Phase 15D) soft-void a recorded payment + regenerate the current invoice. 201 Created (the
     * void creates a new current invoice version). The endpoint takes no meaningful body: like the no-body
     * invoice endpoints (Create/Rewrite/Resend) the raw body is bound {@code required = false} and parsed
     * in the service — an empty / blank / {@code {}} body is accepted, but a non-empty JSON object (or a
     * non-object / malformed body) is rejected (400) so unexpected fields cannot ride along. {@code
     * paymentTransactionId} is a {@code String} (matching {@code orderId}) so a non-numeric / non-positive
     * value becomes VALIDATION_FAILED on {@code payment_transaction_id} in the service rather than a
     * path-binding error.
     */
    @PostMapping("/payments/{paymentTransactionId}/void")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VoidPaymentResponse> voidPayment(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @PathVariable("paymentTransactionId") String paymentTransactionId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {

        return orderPaymentService.voidPayment(slug, orderId, paymentTransactionId, body, httpRequest);
    }
}
