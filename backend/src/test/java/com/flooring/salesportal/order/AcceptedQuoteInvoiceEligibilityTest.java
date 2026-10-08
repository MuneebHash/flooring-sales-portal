package com.flooring.salesportal.order;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

/**
 * Phase 16F PR2 - plain unit tests for {@link AcceptedQuoteInvoiceEligibility}, decision D5(b) as
 * amended on 8 October 2026: the ONE signature-precedence rule shared by the create-invoice endpoint
 * (Path A) and the workspace flag {@code accepted.invoice_eligible}.
 *
 * <p>Allowed when the order has no signed current invoice ({@code null} invoice signature time), or when
 * the quote's signature is STRICTLY later than the current invoice's. An equal or earlier quote
 * signature, or a missing quote signature time against a signed invoice, is refused. Timestamps carry
 * the database's microsecond precision, so a one-microsecond difference must already decide.
 */
class AcceptedQuoteInvoiceEligibilityTest {

    // A microsecond-precision instant, like the values read back from a TIMESTAMP column.
    private static final LocalDateTime INVOICE_SIGNED_AT = LocalDateTime.of(2026, 10, 8, 14, 30, 15, 123_456_000);
    private static final long ONE_MICROSECOND_IN_NANOS = 1_000L;

    @Test
    void noSignedCurrentInvoice_allows() {
        // null = no invoice at all, or an unsigned current invoice.
        Assertions.assertTrue(AcceptedQuoteInvoiceEligibility.allows(INVOICE_SIGNED_AT, null),
                "no signed current invoice -> the accepted quote may be invoiced");
        Assertions.assertTrue(AcceptedQuoteInvoiceEligibility.allows(INVOICE_SIGNED_AT.minusYears(1), null),
                "with no invoice signature there is nothing to compare against");
    }

    @Test
    void noSignedCurrentInvoice_allowsEvenWithoutAQuoteSignatureTime() {
        Assertions.assertTrue(AcceptedQuoteInvoiceEligibility.allows(null, null));
    }

    @Test
    void quoteSignedStrictlyLater_allows() {
        Assertions.assertTrue(AcceptedQuoteInvoiceEligibility.allows(INVOICE_SIGNED_AT.plusDays(1), INVOICE_SIGNED_AT));
        Assertions.assertTrue(AcceptedQuoteInvoiceEligibility.allows(INVOICE_SIGNED_AT.plusSeconds(1), INVOICE_SIGNED_AT));
    }

    @Test
    void quoteSignedOneMicrosecondLater_allows() {
        LocalDateTime quoteSignedAt = INVOICE_SIGNED_AT.plusNanos(ONE_MICROSECOND_IN_NANOS);

        Assertions.assertTrue(AcceptedQuoteInvoiceEligibility.allows(quoteSignedAt, INVOICE_SIGNED_AT),
                "one microsecond (the TIMESTAMP precision) later is strictly newer");
    }

    @Test
    void equalSignatureTimes_refuse() {
        // An equal value held in a DIFFERENT instance (as when both are read back from the database):
        // equality is a refusal, so converting the same signed quote twice is refused.
        LocalDateTime sameInstant = LocalDateTime.of(2026, 10, 8, 14, 30, 15, 123_456_000);

        Assertions.assertFalse(AcceptedQuoteInvoiceEligibility.allows(sameInstant, INVOICE_SIGNED_AT),
                "a tie never replaces a signed invoice");
        Assertions.assertFalse(AcceptedQuoteInvoiceEligibility.allows(INVOICE_SIGNED_AT, INVOICE_SIGNED_AT));
    }

    @Test
    void quoteSignedEarlier_refuses() {
        Assertions.assertFalse(AcceptedQuoteInvoiceEligibility.allows(
                        INVOICE_SIGNED_AT.minusNanos(ONE_MICROSECOND_IN_NANOS), INVOICE_SIGNED_AT),
                "a quote signed one microsecond before the invoice is older -> refused");
        Assertions.assertFalse(AcceptedQuoteInvoiceEligibility.allows(INVOICE_SIGNED_AT.minusDays(3), INVOICE_SIGNED_AT));
    }

    @Test
    void missingQuoteSignatureTime_withSignedInvoice_refuses() {
        Assertions.assertFalse(AcceptedQuoteInvoiceEligibility.allows(null, INVOICE_SIGNED_AT),
                "a missing quote signature time can never be newer than a signed invoice");
    }
}
