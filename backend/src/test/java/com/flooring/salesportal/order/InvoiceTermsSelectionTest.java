package com.flooring.salesportal.order;

import com.flooring.salesportal.order.InvoiceTermsSelection.Source;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Plain unit tests for {@link InvoiceTermsSelection}, the ONE invoice terms rule shared by every
 * invoice PDF build site and the InvoiceDetail {@code terms_html} / {@code terms_source} mapping
 * (Phase 16F PR2, decision D7; amended by Phase 16G PR 2, #125).
 *
 * <p>Since 16G PR 2 a selection always carries the version's STORED {@code terms_snapshot}, verbatim
 * (null included - no terms page), whatever the source; there is no live branch and no way to ask for
 * the business's current terms. Source PRESENCE only decides the reported provenance: a row with a
 * {@code source_quote_version_id} is {@code QUOTE}, a row without one is {@code BUSINESS}. The source is
 * mandatory.
 */
class InvoiceTermsSelectionTest {

    private static final Long SOURCE_QUOTE_VERSION_ID = 4_242L;
    private static final String FROZEN_TERMS = "<p>Frozen terms.</p>";
    // Surrounding whitespace plus attributes the sanitizer would strip: any trim, re-sanitising or
    // rewrite of a stored value would be visible.
    private static final String UNSANITISED_FROZEN_TERMS = "  <p class=\"q\" style=\"color:red\">Frozen terms.</p>\n";

    // ------------------------------------------------------------------
    // forInvoice(source_quote_version_id, terms_snapshot) - the per-row selection
    // ------------------------------------------------------------------

    @Test
    void forInvoice_sourcePresent_isQuote_withTheStoredSnapshotVerbatim() {
        InvoiceTermsSelection selection =
                InvoiceTermsSelection.forInvoice(SOURCE_QUOTE_VERSION_ID, UNSANITISED_FROZEN_TERMS);

        Assertions.assertEquals(Source.QUOTE, selection.source());
        Assertions.assertEquals(UNSANITISED_FROZEN_TERMS, selection.frozenTermsHtml(),
                "the stored snapshot must be carried verbatim - never trimmed or re-sanitised");
    }

    @Test
    void forInvoice_sourcePresentWithNullSnapshot_isQuoteWithNullHtml_noTermsAndNoLiveFallback() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.forInvoice(SOURCE_QUOTE_VERSION_ID, null);

        Assertions.assertEquals(Source.QUOTE, selection.source(),
                "a quote-sourced row with null terms stays QUOTE - it never becomes BUSINESS");
        Assertions.assertNull(selection.frozenTermsHtml(), "stored null terms stay null (no terms page)");
    }

    @Test
    void forInvoice_sourcePresentWithBlankSnapshot_isStillQuote_sourcePresenceDecides() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.forInvoice(SOURCE_QUOTE_VERSION_ID, "   ");

        Assertions.assertEquals(Source.QUOTE, selection.source(),
                "the HTML's blankness never selects the source - the source id does");
        Assertions.assertEquals("   ", selection.frozenTermsHtml());
    }

    @Test
    void forInvoice_sourceAbsent_isBusiness_withTheStoredSnapshotVerbatim_notIgnored() {
        // 16F (LIVE) ignored a source-less row's snapshot and read the business's current terms;
        // since 16G PR 2 the business terms frozen into the row ARE its terms, carried verbatim.
        InvoiceTermsSelection selection = InvoiceTermsSelection.forInvoice(null, UNSANITISED_FROZEN_TERMS);

        Assertions.assertEquals(Source.BUSINESS, selection.source(),
                "without a source quote version the row's terms are the business's frozen terms");
        Assertions.assertEquals(UNSANITISED_FROZEN_TERMS, selection.frozenTermsHtml(),
                "the stored business terms must be carried verbatim, not ignored or re-read");
    }

    @Test
    void forInvoice_sourceAbsentWithNullSnapshot_isBusinessWithNullHtml_noTermsAndNoLiveFallback() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.forInvoice(null, null);

        Assertions.assertEquals(Source.BUSINESS, selection.source());
        Assertions.assertNull(selection.frozenTermsHtml(),
                "null business terms (none set when frozen, or a legacy row) stay null - no live fallback");
    }

    @Test
    void forInvoice_sourceAbsentWithBlankSnapshot_isStillBusiness_carriedVerbatim() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.forInvoice(null, "   ");

        Assertions.assertEquals(Source.BUSINESS, selection.source());
        Assertions.assertEquals("   ", selection.frozenTermsHtml());
    }

    @Test
    void forInvoice_isAValueOfItsTwoInputs_equalForEqualRows() {
        Assertions.assertEquals(new InvoiceTermsSelection(Source.QUOTE, FROZEN_TERMS),
                InvoiceTermsSelection.forInvoice(SOURCE_QUOTE_VERSION_ID, FROZEN_TERMS));
        Assertions.assertEquals(new InvoiceTermsSelection(Source.BUSINESS, FROZEN_TERMS),
                InvoiceTermsSelection.forInvoice(null, FROZEN_TERMS));
        Assertions.assertEquals(new InvoiceTermsSelection(Source.BUSINESS, null),
                InvoiceTermsSelection.forInvoice(null, null));
    }

    // ------------------------------------------------------------------
    // Canonical-constructor invariants
    // ------------------------------------------------------------------

    @Test
    void nullSource_isRejected() {
        for (String html : Arrays.asList(null, "x")) {
            Assertions.assertThrows(NullPointerException.class,
                    () -> new InvoiceTermsSelection(null, html),
                    () -> "the terms source is mandatory (html [" + html + "])");
        }
    }

    @Test
    void bothSources_acceptNullOrAnyHtml_throughTheCanonicalConstructor() {
        // A BUSINESS selection now carries frozen HTML (the 16F rule that LIVE could not is gone).
        for (Source source : Source.values()) {
            Assertions.assertNull(new InvoiceTermsSelection(source, null).frozenTermsHtml());
            Assertions.assertEquals(FROZEN_TERMS, new InvoiceTermsSelection(source, FROZEN_TERMS).frozenTermsHtml());
            Assertions.assertEquals("", new InvoiceTermsSelection(source, "").frozenTermsHtml());
        }
    }

    @Test
    void sourceNames_areExactlyTheSerializedTermsSourceValues_quoteAndBusiness() {
        // InvoiceDetail.terms_source is Source.name() verbatim: exactly QUOTE and BUSINESS (LIVE is gone).
        Assertions.assertEquals(List.of("QUOTE", "BUSINESS"),
                Arrays.stream(Source.values()).map(Enum::name).toList());
    }

    @Test
    void theSelectionOffersNoLiveTermsLookup() {
        // Structural guard for "no live fallback": nothing on the type takes a supplier of live terms
        // (the 16F resolve(Supplier) is removed), and the stored HTML is the only terms accessor.
        for (Method method : InvoiceTermsSelection.class.getDeclaredMethods()) {
            boolean takesSupplier = Arrays.stream(method.getParameterTypes()).anyMatch(Supplier.class::isAssignableFrom);
            Assertions.assertFalse(takesSupplier, "no live-terms supplier parameter: " + method);
            Assertions.assertNotEquals("resolve", method.getName(), "the 16F live resolve(...) must be gone");
        }
    }
}
