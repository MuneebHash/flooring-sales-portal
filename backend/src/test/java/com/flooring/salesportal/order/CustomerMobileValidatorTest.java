package com.flooring.salesportal.order;

import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit test for {@link CustomerMobileValidator} (Phase 16E-A). The rule is STRICT
 * Australian mobile, parity-locked with the CustomerTab frontend regexes: exactly
 * {@code 04XXXXXXXX} or {@code +614XXXXXXXX} on the trimmed value — no separators, no other
 * prefixes. Entity setters don't exist (read-only JPA models), so rows are stubbed via a
 * subclass overriding {@code getMobile()}.
 */
class CustomerMobileValidatorTest {

    private final CustomerMobileValidator validator = new CustomerMobileValidator();

    private static OrderCustomer customerWithMobile(String mobile) {
        return new OrderCustomer() {
            @Override
            public String getMobile() {
                return mobile;
            }
        };
    }

    private void assertCode(ErrorCode expected, String mobile) {
        BusinessRuleException ex = Assertions.assertThrows(BusinessRuleException.class,
                () -> validator.requireValidCustomerMobile(customerWithMobile(mobile)));
        Assertions.assertEquals(expected, ex.getErrorCode(), "for mobile [" + mobile + "]");
    }

    // ---- accepted shapes ----

    @Test
    void localAuMobile_accepted() {
        Assertions.assertDoesNotThrow(
                () -> validator.requireValidCustomerMobile(customerWithMobile("0412345678")));
    }

    @Test
    void internationalAuMobile_accepted() {
        Assertions.assertDoesNotThrow(
                () -> validator.requireValidCustomerMobile(customerWithMobile("+61412345678")));
    }

    @Test
    void surroundingWhitespace_trimmedThenAccepted() {
        Assertions.assertDoesNotThrow(
                () -> validator.requireValidCustomerMobile(customerWithMobile("  0412345678  ")));
    }

    // ---- required ----

    @Test
    void missingCustomerRow_required() {
        BusinessRuleException ex = Assertions.assertThrows(BusinessRuleException.class,
                () -> validator.requireValidCustomerMobile(null));
        Assertions.assertEquals(ErrorCode.CUSTOMER_MOBILE_REQUIRED, ex.getErrorCode());
    }

    @Test
    void nullMobile_required() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_REQUIRED, null);
    }

    @Test
    void blankMobile_required() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_REQUIRED, "   ");
    }

    // ---- rejected shapes (strict: frontend parity means no normalisation) ----

    @Test
    void interiorSpaces_rejected() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_INVALID, "0412 345 678");
    }

    @Test
    void hyphens_rejected() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_INVALID, "0412-345-678");
    }

    @Test
    void tooShortLocal_rejected() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_INVALID, "041234567");
    }

    @Test
    void tooLongLocal_rejected() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_INVALID, "04123456789");
    }

    @Test
    void landline_rejected() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_INVALID, "0298765432");
    }

    @Test
    void internationalWithoutPlus_rejected() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_INVALID, "61412345678");
    }

    @Test
    void internationalNonMobilePrefix_rejected() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_INVALID, "+61298765432");
    }

    @Test
    void nonDigits_rejected() {
        assertCode(ErrorCode.CUSTOMER_MOBILE_INVALID, "041234567a");
    }
}
