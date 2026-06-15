package com.flooring.salesportal.tenant;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 14C — tenant quick-add descriptions read.
 *
 * <p>Returns a business's per-tenant "details of sale" presets, ordered by {@code sort_order}
 * (id tiebreak), with the literal token {@code [BUSINESS_NAME]} substituted with the business
 * name. Authenticated/protected via {@link RequestContextGuard#requireStandardProtected} and
 * scoped strictly to the session {@code business_id}. The response carries the description
 * strings ONLY — never {@code business_id}, {@code sort_order}, or any private tenant config
 * (ABN/bank/T&Cs/Stripe/invoice fields). Read-only transaction.
 */
@Service
public class QuickDescriptionService {

    static final String BUSINESS_NAME_TOKEN = "[BUSINESS_NAME]";

    private final RequestContextGuard requestContextGuard;
    private final BusinessQuickDescriptionRepository businessQuickDescriptionRepository;

    public QuickDescriptionService(
            RequestContextGuard requestContextGuard,
            BusinessQuickDescriptionRepository businessQuickDescriptionRepository) {
        this.requestContextGuard = requestContextGuard;
        this.businessQuickDescriptionRepository = businessQuickDescriptionRepository;
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<String>> listQuickDescriptions(String slug, HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        String businessName = ctx.business().getName();

        List<String> descriptions = businessQuickDescriptionRepository
                .findByBusinessIdOrderBySortOrderAscBusinessQuickDescriptionIdAsc(ctx.businessId())
                .stream()
                .map(row -> applyBusinessName(row.getDescription(), businessName))
                .toList();

        return ApiResponse.ok(descriptions);
    }

    /**
     * Replace every literal {@code [BUSINESS_NAME]} occurrence with the business name. Uses
     * {@link String#replace(CharSequence, CharSequence)} (literal, all occurrences) — NOT
     * {@code replaceAll}, whose regex would treat the token's {@code [} / {@code ]} as
     * metacharacters.
     */
    private static String applyBusinessName(String description, String businessName) {
        return description.replace(BUSINESS_NAME_TOKEN, businessName);
    }
}
