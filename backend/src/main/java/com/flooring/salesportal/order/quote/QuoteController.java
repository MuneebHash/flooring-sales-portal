package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.quote.dto.QuoteDraftDto;
import com.flooring.salesportal.order.quote.dto.QuoteWorkspaceDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 16C PR1 — protected quote money/data-core endpoints, scoped under one order
 * ({@code /api/v1/{slug}/orders/{orderId}/quote}). Thin controller: all session/scope/LAID gating,
 * validation, money, and persistence live in {@link QuoteService}.
 *
 * <p>{@code orderId} is captured as a {@code String} (same as the order/enquiry endpoints) so a
 * non-numeric / non-positive value flows through the service's manual validation and produces
 * {@code VALIDATION_FAILED} (field {@code order_id}). The draft body is taken as a raw {@code String}
 * so JSON parsing happens INSIDE the service, strictly after the guard / orderId / scoped-lookup /
 * 404 / LAID gates — so malformed JSON can never 400 ahead of them.
 *
 * <p>Only the workspace GET and the draft PUT exist in PR1. Preview PDF, send (email/SMS), cancel,
 * create-invoice, the stored-PDF download, and the public token surface are Phase 16C-PR2 / 16E / 16F.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}/quote")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    /** GET .../quote/workspace — draft (issued/accepted are null in PR1). LAID read allowed. */
    @GetMapping("/workspace")
    public ApiResponse<QuoteWorkspaceDto> getWorkspace(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(quoteService.getWorkspace(slug, orderId, httpRequest), "Quote workspace loaded.");
    }

    /** PUT .../quote/draft — full-replace upsert of the editable draft. LAID write blocked (422). */
    @PutMapping("/draft")
    public ApiResponse<QuoteDraftDto> upsertDraft(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(quoteService.saveDraft(slug, orderId, body, httpRequest), "Quote draft saved.");
    }
}
