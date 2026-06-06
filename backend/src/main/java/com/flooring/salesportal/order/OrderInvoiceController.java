package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.dto.InvoiceResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 12 Chunk 4 Branch A — current invoice creation (D.1 POST /orders/{orderId}/invoices). Shares
 * the {@code /orders/{orderId}} base path with the other order sub-resource controllers; the mapping
 * stays distinct by method + the {@code /invoices} sub-path.
 *
 * <p>{@code orderId} is captured as a {@code String} (matching the rest of the order endpoints) so a
 * non-numeric / non-positive value becomes VALIDATION_FAILED on {@code order_id} in the service
 * rather than a path-binding error. The body is taken as a raw {@code String}
 * ({@code required = false}) so it is parsed inside the service strictly after the guard / order-scope
 * gates (D.1 takes no body — {@code {}} only). The service builds the {@code ApiResponse} envelope.
 * Rewrite (D.2), read/file (D.3/D.4), and payments (D.6/D.7) are later Phase 12 branches.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}")
public class OrderInvoiceController {

    private final OrderInvoiceService orderInvoiceService;

    public OrderInvoiceController(OrderInvoiceService orderInvoiceService) {
        this.orderInvoiceService = orderInvoiceService;
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceResponse> createInvoice(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {

        return orderInvoiceService.createInvoice(slug, orderId, body, httpRequest);
    }
}
