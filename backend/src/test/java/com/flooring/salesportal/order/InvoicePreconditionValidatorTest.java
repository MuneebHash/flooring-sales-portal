package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB-free unit test for {@link InvoicePreconditionValidator} (the 9 invoice preconditions, Chunk 4
 * F.1). The JPA entities ({@link SalesOrder}, {@link OrderCustomer}, {@link OrderAddress}) expose only
 * getters, so they are mocked. Covers all-pass, all-fail (9 details), single-precondition failures,
 * the charge-only line case, and blank-vs-null handling — none of which needs a database.
 */
class InvoicePreconditionValidatorTest {

    private final InvoicePreconditionValidator validator = new InvoicePreconditionValidator();

    private static OrderFinancialSummaryDto summary(String productSubtotal, String chargeSubtotal, String finalInc) {
        return new OrderFinancialSummaryDto(
                new BigDecimal(productSubtotal),
                new BigDecimal(chargeSubtotal),
                null,
                null,
                new BigDecimal(finalInc),
                null,
                null,
                null,
                null,
                false);
    }

    private static SalesOrder fullOrder() {
        SalesOrder order = mock(SalesOrder.class);
        when(order.getDetailsOfSale()).thenReturn("Supply and install plush carpet.");
        when(order.getProposedLayDate()).thenReturn(LocalDate.of(2026, 5, 1));
        when(order.getLayDateStatus()).thenReturn("CONFIRMED");
        return order;
    }

    private static OrderCustomer fullCustomer() {
        OrderCustomer customer = mock(OrderCustomer.class);
        when(customer.getFirstName()).thenReturn("James");
        when(customer.getLastName()).thenReturn("Wilson");
        return customer;
    }

    private static OrderAddress address(String type) {
        OrderAddress address = mock(OrderAddress.class);
        when(address.getAddressType()).thenReturn(type);
        return address;
    }

    private static Set<String> failingFields(List<ErrorDetail> failures) {
        return failures.stream().map(ErrorDetail::field).collect(Collectors.toSet());
    }

    @Test
    void allPreconditionsMet_returnsNoFailures() {
        List<ErrorDetail> failures = validator.collectFailures(
                fullOrder(),
                fullCustomer(),
                List.of(address("INSTALLATION"), address("BILLING")),
                summary("360.00", "480.00", "924.00"));

        Assertions.assertTrue(failures.isEmpty(), () -> "expected no failures, got " + failingFields(failures));
    }

    @Test
    void everythingMissing_returnsAllNineFailures() {
        SalesOrder emptyOrder = mock(SalesOrder.class); // all getters return null

        List<ErrorDetail> failures = validator.collectFailures(
                emptyOrder,
                null,
                List.of(),
                summary("0.00", "0.00", "0.00"));

        Assertions.assertEquals(9, failures.size(), () -> "fields: " + failingFields(failures));
        Assertions.assertEquals(
                Set.of("first_name", "last_name", "installation_address", "billing_address", "lines",
                        "details_of_sale", "proposed_lay_date", "lay_date_status", "final_sale_price_inc_gst"),
                failingFields(failures));
    }

    @Test
    void missingCustomerOnly_returnsTwoNameFailures() {
        List<ErrorDetail> failures = validator.collectFailures(
                fullOrder(),
                null,
                List.of(address("INSTALLATION"), address("BILLING")),
                summary("360.00", "480.00", "924.00"));

        Assertions.assertEquals(Set.of("first_name", "last_name"), failingFields(failures));
    }

    @Test
    void blankCustomerNames_treatedAsMissing() {
        OrderCustomer blank = mock(OrderCustomer.class);
        when(blank.getFirstName()).thenReturn("   ");
        when(blank.getLastName()).thenReturn("");

        List<ErrorDetail> failures = validator.collectFailures(
                fullOrder(),
                blank,
                List.of(address("INSTALLATION"), address("BILLING")),
                summary("360.00", "480.00", "924.00"));

        Assertions.assertEquals(Set.of("first_name", "last_name"), failingFields(failures));
    }

    @Test
    void chargeOnlyLine_satisfiesLinePrecondition() {
        // product subtotal 0, charge subtotal > 0 → at least one priced line exists.
        List<ErrorDetail> failures = validator.collectFailures(
                fullOrder(),
                fullCustomer(),
                List.of(address("INSTALLATION"), address("BILLING")),
                summary("0.00", "100.00", "110.00"));

        Assertions.assertTrue(failures.isEmpty(), () -> "fields: " + failingFields(failures));
    }

    @Test
    void missingBillingAddressOnly_returnsBillingFailure() {
        List<ErrorDetail> failures = validator.collectFailures(
                fullOrder(),
                fullCustomer(),
                List.of(address("INSTALLATION")),
                summary("360.00", "480.00", "924.00"));

        Assertions.assertEquals(Set.of("billing_address"), failingFields(failures));
    }

    @Test
    void zeroFinalSalePrice_failsFinancialPrecondition() {
        // No lines at all → line precondition AND financial precondition both fail.
        List<ErrorDetail> failures = validator.collectFailures(
                fullOrder(),
                fullCustomer(),
                List.of(address("INSTALLATION"), address("BILLING")),
                summary("0.00", "0.00", "0.00"));

        Assertions.assertEquals(Set.of("lines", "final_sale_price_inc_gst"), failingFields(failures));
    }
}
