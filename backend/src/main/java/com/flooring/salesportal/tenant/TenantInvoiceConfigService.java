package com.flooring.salesportal.tenant;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.tenant.dto.TenantInvoiceConfigResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 15A — authenticated tenant invoice-config read.
 *
 * <p>Returns a business's private invoice-legal / bank / payment config. Authenticated/protected
 * via {@link RequestContextGuard#requireStandardProtected} (login + selected store) and scoped
 * strictly to the session {@code business_id} — never re-queried by URL slug, because the guard
 * has already validated the slug against the session business. Read-only transaction; mutates
 * nothing. The config columns are read through {@link BusinessInvoiceConfigView} (a native-query
 * projection), so the private fields stay off the {@link Business} entity and cannot leak via the
 * public lookup.
 */
@Service
public class TenantInvoiceConfigService {

    private final RequestContextGuard requestContextGuard;
    private final BusinessRepository businessRepository;

    public TenantInvoiceConfigService(
            RequestContextGuard requestContextGuard,
            BusinessRepository businessRepository) {
        this.requestContextGuard = requestContextGuard;
        this.businessRepository = businessRepository;
    }

    @Transactional(readOnly = true)
    public ApiResponse<TenantInvoiceConfigResponse> getInvoiceConfig(
            String slug, HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);

        // Scope by authenticated business id (NOT the URL slug). Should-not-happen defensive case:
        // the guard already resolved the active business, so a missing config row would only occur
        // if the business row vanished mid-request.
        BusinessInvoiceConfigView view = businessRepository
                .findInvoiceConfigByBusinessId(ctx.businessId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND, "Business not found."));

        TenantInvoiceConfigResponse config = new TenantInvoiceConfigResponse(
                view.getAbn(),
                view.getBankName(),
                view.getBsb(),
                view.getAccountNumber(),
                view.getAccountName(),
                view.getTermsAndConditions(),
                view.getLogoPath(),
                view.getStripePaymentLinkUrl(),
                view.getInvoiceTemplateKey());

        return ApiResponse.ok(config);
    }
}
