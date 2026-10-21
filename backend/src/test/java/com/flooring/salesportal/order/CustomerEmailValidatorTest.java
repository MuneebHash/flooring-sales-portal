package com.flooring.salesportal.order;

import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * DB-free unit test for {@link CustomerEmailValidator}'s non-throwing check (Phase 16G PR 3). Path A
 * has no email gate, so {@link CustomerEmailValidator#hasValidCustomerEmail} decides whether its
 * post-commit signed-PDF email is sent or skipped. It must be EXACTLY the throwing gate that D.1, D.2,
 * D.8 and D.9 keep using - the same null/blank check, the same trim and the same format rule - so a
 * value one accepts the other accepts, and the throwing gate's two codes are unchanged. Entity setters
 * don't exist (read-only JPA models), so rows are stubbed via a subclass overriding {@code getEmail()}.
 */
class CustomerEmailValidatorTest {

    private final CustomerEmailValidator validator = new CustomerEmailValidator();

    private static OrderCustomer customerWithEmail(String email) {
        return new OrderCustomer() {
            @Override
            public String getEmail() {
                return email;
            }
        };
    }

    /** null = the throwing gate passes; otherwise the code it throws. */
    private ErrorCode gateOutcome(OrderCustomer customer) {
        try {
            validator.requireValidCustomerEmail(customer);
            return null;
        } catch (BusinessRuleException ex) {
            return ex.getErrorCode();
        }
    }

    @Test
    void validAddresses_passBothForms_includingSurroundingWhitespaceAndATrailingRootDot() {
        for (String email : List.of("jordan.wilson@example.com", "  jordan@example.com  ", "a@b.co",
                "first.last+tag@sub.example.com.au", "jordan@example.com.")) {
            OrderCustomer customer = customerWithEmail(email);
            Assertions.assertNull(gateOutcome(customer), "throwing gate for [" + email + "]");
            Assertions.assertTrue(validator.hasValidCustomerEmail(customer), "non-throwing check for [" + email + "]");
        }
    }

    @Test
    void missingRowNullAndBlank_failBothForms_asRequired() {
        Assertions.assertEquals(ErrorCode.CUSTOMER_EMAIL_REQUIRED, gateOutcome(null));
        Assertions.assertFalse(validator.hasValidCustomerEmail(null), "missing customer row");
        for (String email : Arrays.asList(null, "", "   ", "\t\n")) {
            OrderCustomer customer = customerWithEmail(email);
            Assertions.assertEquals(ErrorCode.CUSTOMER_EMAIL_REQUIRED, gateOutcome(customer), "gate for [" + email + "]");
            Assertions.assertFalse(validator.hasValidCustomerEmail(customer), "check for [" + email + "]");
        }
    }

    @Test
    void malformedAddresses_failBothForms_asInvalid() {
        for (String email : List.of("no-at-sign.example.com", "@example.com", "jordan@", "jordan@localhost",
                "jordan@example", "jordan@.com", "jordan@example.", "two@@example.com", "a@b@example.com",
                "jor dan@example.com", "jordan@exa mple.com", "jor\u0007dan@example.com")) {
            OrderCustomer customer = customerWithEmail(email);
            Assertions.assertEquals(ErrorCode.CUSTOMER_EMAIL_INVALID, gateOutcome(customer), "gate for [" + email + "]");
            Assertions.assertFalse(validator.hasValidCustomerEmail(customer), "check for [" + email + "]");
        }
    }
}
