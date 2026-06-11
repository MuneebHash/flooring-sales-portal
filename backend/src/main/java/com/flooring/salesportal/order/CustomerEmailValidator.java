package com.flooring.salesportal.order;

import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Phase 13 customer-email gate (contract §12 / conventions §14 addendum): a valid customer email must
 * exist before Create Invoice and Rewrite Invoice (and, on later Phase 13 branches, Accept / Resend) so
 * the accepted invoice can always be emailed. Evaluated BEFORE the 9
 * {@code INVOICE_PRECONDITIONS_NOT_MET} checks; when this gate fails, the 9-check evaluation is skipped
 * entirely — a missing {@code order_customer} row is 422 {@code CUSTOMER_EMAIL_REQUIRED}, never
 * {@code INVOICE_PRECONDITIONS_NOT_MET}.
 *
 * <p>Validation is deliberately STRICTER than the DB CHECK ({@code trim(email) <> '' AND email LIKE
 * '%@%'}) and mirrors the frontend Customer-tab rule ({@code /^[^\s@]+@[^\s@]+\.[^\s@]+$/}): exactly one
 * {@code @}, a non-empty local part, a domain containing an INTERIOR dot (at least one character on each
 * side — a trailing root dot like {@code "example.com."} is tolerated, exactly as the frontend regex
 * tolerates it via backtracking), and no whitespace anywhere. Keeping the two rules aligned matters: an
 * email the Customer tab saves must never dead-end Create/Rewrite Invoice with
 * {@code CUSTOMER_EMAIL_INVALID} — the §10 UX would send the user back to a tab that reports the email
 * valid. The backend additionally rejects ISO control characters, which the form cannot produce. No new
 * dependency is introduced — this is a small conservative format check, not full RFC 5322 parsing.
 */
@Component
public class CustomerEmailValidator {

    /**
     * Throws 422 {@code CUSTOMER_EMAIL_REQUIRED} when the customer row is missing or its email is
     * null/blank, and 422 {@code CUSTOMER_EMAIL_INVALID} when the email is present but malformed.
     * Surrounding whitespace is trimmed before the format check (it is not part of the address).
     */
    public void requireValidCustomerEmail(OrderCustomer customer) {
        String email = customer == null ? null : customer.getEmail();
        if (email == null || email.isBlank()) {
            throw new BusinessRuleException(
                    ErrorCode.CUSTOMER_EMAIL_REQUIRED, ErrorCode.CUSTOMER_EMAIL_REQUIRED.defaultMessage());
        }
        if (!isPlausibleEmail(email.trim())) {
            throw new BusinessRuleException(
                    ErrorCode.CUSTOMER_EMAIL_INVALID, ErrorCode.CUSTOMER_EMAIL_INVALID.defaultMessage());
        }
    }

    /**
     * Conservative format check on a trimmed, non-blank value: rejects a missing/duplicated {@code @},
     * a missing local part, a missing domain, a domain without an interior dot, and any interior
     * whitespace or control character.
     */
    private static boolean isPlausibleEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@')) {
            return false; // missing '@', empty local part, or more than one '@'
        }
        for (int i = 0; i < email.length(); i++) {
            char c = email.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        String domain = email.substring(at + 1);
        // The domain needs an INTERIOR dot — at least one character on each side (e.g. "email.com").
        // The FIRST dot at index >= 1 decides equivalence with the frontend regex: interior -> both
        // accept; final-character or absent -> no interior dot exists and both reject. This tolerates a
        // trailing dot AFTER an interior one ("example.com.", as iPad keyboards can auto-insert), so a
        // Customer-tab-saved email never fails this gate.
        int dot = domain.indexOf('.', 1);
        return dot > 0 && dot < domain.length() - 1;
    }
}
