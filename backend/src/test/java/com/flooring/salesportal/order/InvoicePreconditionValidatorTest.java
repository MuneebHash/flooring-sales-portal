package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // ================================================================
    // Phase 16F PR2 - Path A: collectAcceptedQuoteFailures (the signed quote snapshot preconditions)
    // ================================================================
    //
    // Retained from Path B with the SAME section / field / message: customer first + last name,
    // installation + billing address, proposed lay date, lay date status. Replaced by the signed
    // snapshot: the frozen details of sale (never the live one) and each quote total (ex and inc, checked
    // independently). Never checked: live lines, live price, live details of sale, customer email.

    private static final String FROZEN_DETAILS = "Supply and lay carpet (frozen on the signed quote).";
    private static final BigDecimal QUOTE_EX = new BigDecimal("250.00");
    private static final BigDecimal QUOTE_INC = new BigDecimal("275.00");

    private static final ErrorDetail FIRST_NAME_REQUIRED =
            new ErrorDetail("customer", "first_name", "Customer first name is required.");
    private static final ErrorDetail LAST_NAME_REQUIRED =
            new ErrorDetail("customer", "last_name", "Customer last name is required.");
    private static final ErrorDetail INSTALLATION_ADDRESS_REQUIRED =
            new ErrorDetail("address", "installation_address", "Installation address is required.");
    private static final ErrorDetail BILLING_ADDRESS_REQUIRED =
            new ErrorDetail("address", "billing_address", "Billing address is required.");
    private static final ErrorDetail QUOTE_DETAILS_REQUIRED =
            new ErrorDetail("details", "details_of_sale", "Details of sale on the accepted quote is required.");
    private static final ErrorDetail LAY_DATE_REQUIRED =
            new ErrorDetail("details", "proposed_lay_date", "Proposed lay date is required.");
    private static final ErrorDetail LAY_DATE_STATUS_REQUIRED =
            new ErrorDetail("details", "lay_date_status", "Lay date status is required.");
    private static final ErrorDetail QUOTE_EX_NOT_POSITIVE = new ErrorDetail("financial", "sale_price_ex_gst",
            "The accepted quote total (ex GST) must be greater than zero.");
    private static final ErrorDetail QUOTE_INC_NOT_POSITIVE = new ErrorDetail("financial", "sale_price_inc_gst",
            "The accepted quote total (inc GST) must be greater than zero.");

    /** A mocked order with the given LIVE details of sale, proposed lay date and lay date status. */
    private static SalesOrder order(String liveDetailsOfSale, LocalDate proposedLayDate, String layDateStatus) {
        SalesOrder order = mock(SalesOrder.class);
        when(order.getDetailsOfSale()).thenReturn(liveDetailsOfSale);
        when(order.getProposedLayDate()).thenReturn(proposedLayDate);
        when(order.getLayDateStatus()).thenReturn(layDateStatus);
        return order;
    }

    private static OrderCustomer customer(String firstName, String lastName) {
        OrderCustomer customer = mock(OrderCustomer.class);
        when(customer.getFirstName()).thenReturn(firstName);
        when(customer.getLastName()).thenReturn(lastName);
        return customer;
    }

    private static List<OrderAddress> bothAddresses() {
        return List.of(address("INSTALLATION"), address("BILLING"));
    }

    private List<ErrorDetail> acceptedQuoteFailures(SalesOrder order, OrderCustomer customer,
                                                    List<OrderAddress> addresses) {
        return validator.collectAcceptedQuoteFailures(order, customer, addresses, FROZEN_DETAILS, QUOTE_EX, QUOTE_INC);
    }

    @Test
    void acceptedQuote_allPreconditionsMet_returnsNoFailures() {
        List<ErrorDetail> failures = acceptedQuoteFailures(fullOrder(), fullCustomer(), bothAddresses());

        Assertions.assertTrue(failures.isEmpty(), () -> "expected no failures, got " + failures);
    }

    @Test
    void acceptedQuote_eachRetainedCheck_producesExactlyThePathBDetail() {
        LocalDate layDate = LocalDate.of(2026, 12, 1);
        String liveDetails = "Supply and install plush carpet.";
        record RetainedCase(String label, SalesOrder order, OrderCustomer customer, List<OrderAddress> addresses,
                            List<ErrorDetail> expected) {
        }
        List<RetainedCase> cases = List.of(
                new RetainedCase("no customer row", order(liveDetails, layDate, "CONFIRMED"), null,
                        bothAddresses(), List.of(FIRST_NAME_REQUIRED, LAST_NAME_REQUIRED)),
                new RetainedCase("blank first name", order(liveDetails, layDate, "CONFIRMED"),
                        customer("   ", "Wilson"), bothAddresses(), List.of(FIRST_NAME_REQUIRED)),
                new RetainedCase("null last name", order(liveDetails, layDate, "CONFIRMED"),
                        customer("James", null), bothAddresses(), List.of(LAST_NAME_REQUIRED)),
                new RetainedCase("no installation address", order(liveDetails, layDate, "CONFIRMED"),
                        fullCustomer(), List.of(address("BILLING")), List.of(INSTALLATION_ADDRESS_REQUIRED)),
                new RetainedCase("no billing address", order(liveDetails, layDate, "CONFIRMED"),
                        fullCustomer(), List.of(address("INSTALLATION")), List.of(BILLING_ADDRESS_REQUIRED)),
                new RetainedCase("no proposed lay date", order(liveDetails, null, "CONFIRMED"),
                        fullCustomer(), bothAddresses(), List.of(LAY_DATE_REQUIRED)),
                new RetainedCase("null lay date status", order(liveDetails, layDate, null),
                        fullCustomer(), bothAddresses(), List.of(LAY_DATE_STATUS_REQUIRED)),
                new RetainedCase("blank lay date status", order(liveDetails, layDate, "  "),
                        fullCustomer(), bothAddresses(), List.of(LAY_DATE_STATUS_REQUIRED)));

        for (RetainedCase c : cases) {
            List<ErrorDetail> pathA = acceptedQuoteFailures(c.order(), c.customer(), c.addresses());
            List<ErrorDetail> pathB = validator.collectFailures(
                    c.order(), c.customer(), c.addresses(), summary("360.00", "480.00", "924.00"));

            Assertions.assertEquals(c.expected(), pathA, () -> c.label() + ": Path A detail(s)");
            Assertions.assertEquals(pathB, pathA,
                    () -> c.label() + ": a retained check must give exactly the Path B section/field/message");
        }
    }

    @Test
    void acceptedQuote_frozenDetailsNullOrBlank_failWithTheAcceptedQuoteDetail() {
        for (String frozenDetails : Arrays.asList(null, "", "   ", "\t\n")) {
            List<ErrorDetail> failures = validator.collectAcceptedQuoteFailures(
                    fullOrder(), fullCustomer(), bothAddresses(), frozenDetails, QUOTE_EX, QUOTE_INC);

            Assertions.assertEquals(List.of(QUOTE_DETAILS_REQUIRED), failures,
                    () -> "frozen details [" + frozenDetails + "] must fail only details/details_of_sale");
        }
    }

    @Test
    void acceptedQuote_blankLiveDetailsOfSale_isNotChecked_andNeverRead() {
        for (String liveDetails : Arrays.asList(null, "", "   ")) {
            SalesOrder order = order(liveDetails, LocalDate.of(2026, 12, 1), "CONFIRMED");

            List<ErrorDetail> failures = acceptedQuoteFailures(order, fullCustomer(), bothAddresses());

            Assertions.assertTrue(failures.isEmpty(),
                    () -> "a blank LIVE details of sale [" + liveDetails + "] must not fail Path A: " + failures);
            // Path A never even reads the live details of sale, nor the live order price.
            verify(order, never()).getDetailsOfSale();
            verify(order, never()).getSalePriceExGst();
            verify(order, never()).getPriceAdjustmentIncGst();

            // Contrast: the same order fails the live check on Path B.
            Assertions.assertTrue(validator.collectFailures(order, fullCustomer(), bothAddresses(),
                            summary("360.00", "480.00", "924.00"))
                    .contains(new ErrorDetail("details", "details_of_sale", "Details of sale is required.")));
        }
    }

    @Test
    void acceptedQuote_neverChecksTheCustomerEmail_orTheLiveLines() {
        // The customer has names but NO email; the Path A signature takes no live summary at all.
        OrderCustomer customerWithoutEmail = customer("James", "Wilson");

        List<ErrorDetail> failures = acceptedQuoteFailures(fullOrder(), customerWithoutEmail, bothAddresses());

        Assertions.assertTrue(failures.isEmpty(), () -> "no email gate on Path A: " + failures);
        verify(customerWithoutEmail, never()).getEmail();
    }

    @Test
    void acceptedQuote_exTotalNotPositive_failsOnlySalePriceExGst() {
        for (String ex : List.of("0", "0.00", "-0.01", "-250.00")) {
            List<ErrorDetail> failures = validator.collectAcceptedQuoteFailures(
                    fullOrder(), fullCustomer(), bothAddresses(), FROZEN_DETAILS, new BigDecimal(ex), QUOTE_INC);

            Assertions.assertEquals(List.of(QUOTE_EX_NOT_POSITIVE), failures,
                    () -> "ex " + ex + " with a positive inc must fail ONLY financial/sale_price_ex_gst");
        }
    }

    @Test
    void acceptedQuote_incTotalNotPositive_failsOnlySalePriceIncGst() {
        for (String inc : List.of("0", "0.00", "-0.01", "-275.00")) {
            List<ErrorDetail> failures = validator.collectAcceptedQuoteFailures(
                    fullOrder(), fullCustomer(), bothAddresses(), FROZEN_DETAILS, QUOTE_EX, new BigDecimal(inc));

            Assertions.assertEquals(List.of(QUOTE_INC_NOT_POSITIVE), failures,
                    () -> "inc " + inc + " with a positive ex must fail ONLY financial/sale_price_inc_gst");
        }
    }

    @Test
    void acceptedQuote_nullTotals_failEachFinancialCheckIndependently() {
        Assertions.assertEquals(List.of(QUOTE_EX_NOT_POSITIVE, QUOTE_INC_NOT_POSITIVE),
                validator.collectAcceptedQuoteFailures(
                        fullOrder(), fullCustomer(), bothAddresses(), FROZEN_DETAILS, null, null));
        Assertions.assertEquals(List.of(QUOTE_EX_NOT_POSITIVE),
                validator.collectAcceptedQuoteFailures(
                        fullOrder(), fullCustomer(), bothAddresses(), FROZEN_DETAILS, null, QUOTE_INC));
        Assertions.assertEquals(List.of(QUOTE_INC_NOT_POSITIVE),
                validator.collectAcceptedQuoteFailures(
                        fullOrder(), fullCustomer(), bothAddresses(), FROZEN_DETAILS, QUOTE_EX, null));
    }

    @Test
    void acceptedQuote_everythingMissing_aggregatesAllNineDetailsInOrder() {
        List<ErrorDetail> expected = List.of(
                FIRST_NAME_REQUIRED, LAST_NAME_REQUIRED,
                INSTALLATION_ADDRESS_REQUIRED, BILLING_ADDRESS_REQUIRED,
                QUOTE_DETAILS_REQUIRED,
                LAY_DATE_REQUIRED, LAY_DATE_STATUS_REQUIRED,
                QUOTE_EX_NOT_POSITIVE, QUOTE_INC_NOT_POSITIVE);

        // Null everything (an all-null order mock, no customer, no addresses, null snapshot values)...
        Assertions.assertEquals(expected, validator.collectAcceptedQuoteFailures(
                mock(SalesOrder.class), null, List.of(), null, null, null));
        // ...and blank / zero snapshot values aggregate identically.
        Assertions.assertEquals(expected, validator.collectAcceptedQuoteFailures(
                order("   ", null, " "), customer(" ", ""), List.of(), "  ",
                new BigDecimal("0.00"), new BigDecimal("0.00")));
    }
}
