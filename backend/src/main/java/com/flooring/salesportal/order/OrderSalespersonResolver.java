package com.flooring.salesportal.order;

import com.flooring.salesportal.user.AppUser;
import com.flooring.salesportal.user.AppUserRepository;
import org.springframework.stereotype.Component;

/**
 * Phase 15C PR2 — single source of truth for an order's salesperson display name.
 *
 * <p>The salesperson is ORDER-BOUND: {@code sales_order.user_id -> app_user.first_name + last_name},
 * never the current session/logged-in user and never the salesperson_code. The lookup is tenant-scoped
 * by business id so a crossed/forged user id can never render a name from another tenant.
 *
 * <p>Both the on-screen invoice ({@code OrderService.getOrder} -> OrderWorkspaceResponse) and the
 * invoice PDF ({@link InvoicePdfModelAssembler}) resolve through this one method, so the two can never
 * diverge. Fail-soft: a null user id, a missing user row, or a blank joined name all return {@code null}
 * (the caller hides the row / falls back to business-name text); this method never throws.
 */
@Component
public class OrderSalespersonResolver {

    private final AppUserRepository appUserRepository;

    public OrderSalespersonResolver(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    /**
     * Resolve the order-bound salesperson display name.
     *
     * @param userId     the order's {@code sales_order.user_id} (NOT the session user)
     * @param businessId the session business id the lookup is scoped to
     * @return {@code "First Last"} (single space, trimmed), or {@code null} when {@code userId} is null,
     *         no matching user exists in this business, or the joined name is blank
     */
    public String resolveName(Long userId, Long businessId) {
        if (userId == null) {
            return null;
        }
        AppUser user = appUserRepository.findByUserIdAndBusinessId(userId, businessId).orElse(null);
        if (user == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, user.getFirstName());
        appendIfPresent(sb, user.getLastName());
        return blankToNull(sb.toString());
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value.trim());
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
