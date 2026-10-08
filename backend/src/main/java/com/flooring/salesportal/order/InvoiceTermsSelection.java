package com.flooring.salesportal.order;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Phase 16F PR2 (decision D7) - the ONE invoice terms-source rule. Every invoice PDF build site
 * (through {@link InvoicePdfModelAssembler.Inputs}) and the InvoiceDetail API mapping
 * ({@code terms_html} / {@code terms_source}) derive their terms from this selection, so the PDF and
 * the API can never drift apart.
 *
 * <ul>
 *   <li><b>{@link Source#QUOTE}</b> - the invoice row carries a {@code source_quote_version_id} (a
 *       Path A invoice, and every payment / void / D.8 version carried forward from it). The terms are
 *       the invoice's frozen {@code terms_snapshot} used VERBATIM, including {@code null} (frozen "no
 *       terms": no terms page, even when the business has live terms today). They are never trimmed,
 *       re-sanitised, re-frozen, or replaced from the source quote's current row or the live tenant
 *       terms - the quote snapshot was sanitised once at issue ({@link InvoiceTermsSanitizer}).</li>
 *   <li><b>{@link Source#LIVE}</b> - no source reference (Path B: D.1 Create / D.2 Rewrite and their
 *       payment / void / D.8 versions). The business's CURRENT per-flooring-type terms through the
 *       existing {@link InvoiceTermsSanitizer}, exactly as before PR2.</li>
 * </ul>
 *
 * <p>Source PRESENCE selects the branch - never whether the HTML is null or blank. A LIVE selection
 * carries no frozen HTML; that is enforced so a caller cannot smuggle frozen terms into a live render.
 */
public record InvoiceTermsSelection(Source source, String frozenTermsHtml) {

    /** Serialized verbatim as {@code InvoiceDetail.terms_source}. */
    public enum Source {
        QUOTE,
        LIVE
    }

    public InvoiceTermsSelection {
        Objects.requireNonNull(source, "source");
        if (source == Source.LIVE && frozenTermsHtml != null) {
            throw new IllegalArgumentException("A LIVE terms selection carries no frozen terms HTML");
        }
    }

    /** Path B: the business's current per-flooring-type terms (sanitised at render/read time). */
    public static InvoiceTermsSelection live() {
        return new InvoiceTermsSelection(Source.LIVE, null);
    }

    /** Path A: the frozen quote terms snapshot, verbatim - {@code null} stays "no terms". */
    public static InvoiceTermsSelection frozenQuoteTerms(String termsSnapshot) {
        return new InvoiceTermsSelection(Source.QUOTE, termsSnapshot);
    }

    /**
     * The selection for an invoice row's two {@code V19} columns: {@code source_quote_version_id}
     * present → {@link Source#QUOTE} with {@code terms_snapshot} verbatim (even null); absent →
     * {@link Source#LIVE}.
     */
    public static InvoiceTermsSelection forInvoice(Long sourceQuoteVersionId, String termsSnapshot) {
        return sourceQuoteVersionId != null ? frozenQuoteTerms(termsSnapshot) : live();
    }

    /**
     * The terms HTML this selection stands for, the one place the rule is applied (PDF assembly and
     * InvoiceDetail mapping both call it): {@link Source#QUOTE} returns the frozen snapshot verbatim
     * (null stays null) and never calls {@code liveTermsHtml}; {@link Source#LIVE} returns the supplied
     * current live terms.
     */
    public String resolve(Supplier<String> liveTermsHtml) {
        return source == Source.QUOTE ? frozenTermsHtml : liveTermsHtml.get();
    }
}
