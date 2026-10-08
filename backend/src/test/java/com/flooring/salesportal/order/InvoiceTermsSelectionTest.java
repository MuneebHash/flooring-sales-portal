package com.flooring.salesportal.order;

import com.flooring.salesportal.order.InvoiceTermsSelection.Source;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Phase 16F PR2 (decision D7) - plain unit tests for {@link InvoiceTermsSelection}, the ONE invoice
 * terms-source rule shared by every invoice PDF build site and the InvoiceDetail {@code terms_html} /
 * {@code terms_source} mapping.
 *
 * <p>Source PRESENCE selects the branch, never the HTML: an invoice row carrying a
 * {@code source_quote_version_id} is {@code QUOTE} with its {@code terms_snapshot} kept verbatim
 * (null included - frozen "no terms"); a row without one is {@code LIVE}, whatever its snapshot holds.
 * A {@code LIVE} selection can never carry frozen HTML, and the source is mandatory.
 */
class InvoiceTermsSelectionTest {

    private static final Long SOURCE_QUOTE_VERSION_ID = 4_242L;
    private static final String FROZEN_TERMS = "<p>Frozen quote terms.</p>";

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    @Test
    void live_isTheLiveSourceWithNoFrozenHtml() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.live();

        Assertions.assertEquals(Source.LIVE, selection.source());
        Assertions.assertNull(selection.frozenTermsHtml(), "a LIVE selection carries no frozen terms");
    }

    @Test
    void frozenQuoteTerms_keepsTheSnapshotVerbatim_evenMarkupASanitizerWouldStrip() {
        // Surrounding whitespace plus a style attribute: nothing is trimmed, re-sanitised or rewritten.
        String frozen = "  <p class=\"q\" style=\"color:red\">Frozen quote terms.</p>\n";

        InvoiceTermsSelection selection = InvoiceTermsSelection.frozenQuoteTerms(frozen);

        Assertions.assertEquals(Source.QUOTE, selection.source());
        Assertions.assertEquals(frozen, selection.frozenTermsHtml(), "the frozen snapshot must be kept verbatim");
    }

    @Test
    void frozenQuoteTerms_null_isQuoteWithNullHtml_frozenNoTerms() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.frozenQuoteTerms(null);

        Assertions.assertEquals(Source.QUOTE, selection.source(),
                "frozen 'no terms' is still the QUOTE source - it never becomes LIVE");
        Assertions.assertNull(selection.frozenTermsHtml());
    }

    // ------------------------------------------------------------------
    // forInvoice(source_quote_version_id, terms_snapshot) - the per-row selection
    // ------------------------------------------------------------------

    @Test
    void forInvoice_sourcePresentWithNullSnapshot_isQuoteWithNullHtml_neverLive() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.forInvoice(SOURCE_QUOTE_VERSION_ID, null);

        Assertions.assertEquals(Source.QUOTE, selection.source(),
                "a quote-sourced row with null terms must not fall back to the live terms");
        Assertions.assertNull(selection.frozenTermsHtml());
        Assertions.assertEquals(InvoiceTermsSelection.frozenQuoteTerms(null), selection);
    }

    @Test
    void forInvoice_sourcePresentWithSnapshot_isQuoteWithThatSnapshot() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.forInvoice(SOURCE_QUOTE_VERSION_ID, FROZEN_TERMS);

        Assertions.assertEquals(Source.QUOTE, selection.source());
        Assertions.assertEquals(FROZEN_TERMS, selection.frozenTermsHtml());
        Assertions.assertEquals(InvoiceTermsSelection.frozenQuoteTerms(FROZEN_TERMS), selection);
    }

    @Test
    void forInvoice_sourcePresentWithBlankSnapshot_isStillQuote_sourcePresenceDecides() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.forInvoice(SOURCE_QUOTE_VERSION_ID, "   ");

        Assertions.assertEquals(Source.QUOTE, selection.source(),
                "the HTML's blankness never selects the branch - the source id does");
        Assertions.assertEquals("   ", selection.frozenTermsHtml());
    }

    @Test
    void forInvoice_sourceAbsent_isLive_andIgnoresAnySnapshot() {
        InvoiceTermsSelection withSnapshot = InvoiceTermsSelection.forInvoice(null, "x");

        Assertions.assertEquals(Source.LIVE, withSnapshot.source(),
                "without a source quote version the row is LIVE, whatever terms_snapshot holds");
        Assertions.assertNull(withSnapshot.frozenTermsHtml(), "the stray snapshot must be ignored, not carried");
        Assertions.assertEquals(InvoiceTermsSelection.live(), withSnapshot);
    }

    @Test
    void forInvoice_sourceAbsentAndNullSnapshot_isLive() {
        InvoiceTermsSelection selection = InvoiceTermsSelection.forInvoice(null, null);

        Assertions.assertEquals(Source.LIVE, selection.source());
        Assertions.assertNull(selection.frozenTermsHtml());
        Assertions.assertEquals(InvoiceTermsSelection.live(), selection);
    }

    // ------------------------------------------------------------------
    // Canonical-constructor invariants
    // ------------------------------------------------------------------

    @Test
    void liveSelectionWithFrozenHtml_isRejected() {
        // ANY non-null HTML (even empty or blank) on a LIVE selection is refused: frozen terms can never be
        // smuggled into a live render.
        for (String html : List.of("x", FROZEN_TERMS, "", "   ")) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new InvoiceTermsSelection(Source.LIVE, html),
                    () -> "a LIVE selection must reject frozen HTML [" + html + "]");
        }
    }

    @Test
    void nullSource_isRejected() {
        for (String html : Arrays.asList(null, "x")) {
            Assertions.assertThrows(NullPointerException.class,
                    () -> new InvoiceTermsSelection(null, html),
                    () -> "the terms source is mandatory (html [" + html + "])");
        }
    }

    @Test
    void quoteSelection_acceptsNullOrAnyHtml_throughTheCanonicalConstructor() {
        Assertions.assertNull(new InvoiceTermsSelection(Source.QUOTE, null).frozenTermsHtml());
        Assertions.assertEquals(FROZEN_TERMS, new InvoiceTermsSelection(Source.QUOTE, FROZEN_TERMS).frozenTermsHtml());
    }

    @Test
    void sourceNames_areExactlyTheSerializedTermsSourceValues() {
        // InvoiceDetail.terms_source is Source.name() verbatim: exactly QUOTE and LIVE.
        Assertions.assertEquals(List.of("QUOTE", "LIVE"),
                Arrays.stream(Source.values()).map(Enum::name).toList());
    }

    // ------------------------------------------------------------------
    // resolve - the one place the rule is applied (PDF assembly and InvoiceDetail mapping)
    // ------------------------------------------------------------------

    @Test
    void resolve_quote_returnsTheFrozenHtmlVerbatim_includingNull_andNeverCallsTheLiveSupplier() {
        AtomicInteger liveCalls = new AtomicInteger();
        Supplier<String> live = () -> {
            liveCalls.incrementAndGet();
            return "<p>Live terms that must never be used.</p>";
        };

        Assertions.assertEquals(FROZEN_TERMS, InvoiceTermsSelection.frozenQuoteTerms(FROZEN_TERMS).resolve(live));
        Assertions.assertNull(InvoiceTermsSelection.frozenQuoteTerms(null).resolve(live),
                "frozen 'no terms' never falls back to the live terms");
        Assertions.assertNull(InvoiceTermsSelection.forInvoice(SOURCE_QUOTE_VERSION_ID, null).resolve(live));
        Assertions.assertEquals(0, liveCalls.get(), "a QUOTE selection must never read the live terms");
    }

    @Test
    void resolve_live_returnsTheSuppliedLiveTerms_includingNull_calledOnce() {
        AtomicInteger liveCalls = new AtomicInteger();
        Assertions.assertEquals("<p>Live.</p>", InvoiceTermsSelection.live().resolve(() -> {
            liveCalls.incrementAndGet();
            return "<p>Live.</p>";
        }));
        Assertions.assertEquals(1, liveCalls.get());
        Assertions.assertNull(InvoiceTermsSelection.live().resolve(() -> null), "no live terms -> null");
        // A row without a source is LIVE whatever its snapshot holds: the snapshot is ignored.
        Assertions.assertEquals("<p>Live.</p>",
                InvoiceTermsSelection.forInvoice(null, FROZEN_TERMS).resolve(() -> "<p>Live.</p>"));
    }
}
