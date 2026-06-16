package com.flooring.salesportal.tenant;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.tenant.dto.TenantInvoiceConfigResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 15A — {@code GET /api/v1/{slug}/invoice-config}.
 *
 * <p>Tenant-scoped, authenticated read of a business's private invoice-legal / bank / payment
 * config (the V12 fields that the public lookup deliberately never exposes). Thin controller
 * mirroring {@code QuickDescriptionController}: it captures {@code slug} + {@code HttpServletRequest}
 * and delegates to the service, which applies the standard-protected guard (login + selected store)
 * and scopes by the authenticated business id. Returns the standard {@code ApiResponse} envelope
 * wrapping a single config object ({@code { "data": { ... } }}).
 */
@RestController
@RequestMapping("/api/v1/{slug}")
public class TenantInvoiceConfigController {

    private final TenantInvoiceConfigService tenantInvoiceConfigService;

    public TenantInvoiceConfigController(TenantInvoiceConfigService tenantInvoiceConfigService) {
        this.tenantInvoiceConfigService = tenantInvoiceConfigService;
    }

    @GetMapping("/invoice-config")
    public ApiResponse<TenantInvoiceConfigResponse> getInvoiceConfig(
            @PathVariable String slug,
            HttpServletRequest httpRequest) {
        return tenantInvoiceConfigService.getInvoiceConfig(slug, httpRequest);
    }
}
