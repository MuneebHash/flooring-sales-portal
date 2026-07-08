package com.flooring.salesportal.order;

import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Phase 16E-A customer-mobile gate for the quote {@code send-sms} endpoint (contract §9: the SMS
 * gate is a fatal 422 precondition, evaluated before any state change). The recipient is always
 * the saved Customer-tab mobile — no send-time override.
 *
 * <p>Validation is STRICT Australian mobile and mirrors the CustomerTab frontend rule
 * ({@code MOBILE_LOCAL_REGEX} / {@code MOBILE_INTL_REGEX} in {@code CustomerTab.tsx}) exactly, so
 * a mobile the Customer tab saves can never dead-end send-sms with
 * {@code CUSTOMER_MOBILE_INVALID} — the same parity-lock discipline as
 * {@link CustomerEmailValidator}. Accepted, on the trimmed value, in exactly two shapes:
 * <ul>
 *   <li>local  — {@code 04XXXXXXXX} (10 digits)</li>
 *   <li>intl   — {@code +614XXXXXXXX} ({@code +614} + 8 digits)</li>
 * </ul>
 * No separators (spaces/hyphens/parentheses) are accepted — the frontend rejects them too. If this
 * rule ever changes, BOTH sides must change together.
 */
@Component
public class CustomerMobileValidator {

    // Keep byte-for-byte in sync with frontend CustomerTab.tsx MOBILE_LOCAL_REGEX / MOBILE_INTL_REGEX.
    private static final Pattern MOBILE_LOCAL = Pattern.compile("^04\\d{8}$");
    private static final Pattern MOBILE_INTL = Pattern.compile("^\\+614\\d{8}$");

    /**
     * Throws 422 {@code CUSTOMER_MOBILE_REQUIRED} when the customer row is missing or its mobile is
     * null/blank, and 422 {@code CUSTOMER_MOBILE_INVALID} when the mobile is present but not a valid
     * AU mobile in one of the two accepted shapes. Surrounding whitespace is trimmed before the
     * format check (it is not part of the number).
     */
    public void requireValidCustomerMobile(OrderCustomer customer) {
        String mobile = customer == null ? null : customer.getMobile();
        if (mobile == null || mobile.isBlank()) {
            throw new BusinessRuleException(
                    ErrorCode.CUSTOMER_MOBILE_REQUIRED, ErrorCode.CUSTOMER_MOBILE_REQUIRED.defaultMessage());
        }
        String trimmed = mobile.trim();
        if (!MOBILE_LOCAL.matcher(trimmed).matches() && !MOBILE_INTL.matcher(trimmed).matches()) {
            throw new BusinessRuleException(
                    ErrorCode.CUSTOMER_MOBILE_INVALID, ErrorCode.CUSTOMER_MOBILE_INVALID.defaultMessage());
        }
    }
}
