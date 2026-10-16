package com.flooring.salesportal.order;

import java.util.Objects;

/**
 * The ONE invoice terms rule (Phase 16F PR2, decision D7; amended by Phase 16G PR 2, #125). Every
 * invoice PDF build site (through {@link InvoicePdfModelAssembler.Inputs}) and the InvoiceDetail API
 * mapping ({@code terms_html} / {@code terms_source}) derive their terms from this selection, so the
 * PDF and the API can never drift apart.
 *
 * <p>Since 16G PR 2 every invoice version renders the terms STORED on it ({@code terms_snapshot}),
 * verbatim, whatever its source: there is no live lookup at render or read time. {@code null} means
 * no terms (no terms page), with no fall-back to the business's current terms, on both sources. The
 * stored HTML is never trimmed, re-sanitised or replaced here - it was sanitised once when it was
 * frozen (business terms at Create / Rewrite by {@link InvoiceTermsSanitizer}; quote terms at issue).
 *
 * <ul>
 *   <li><b>{@link Source#QUOTE}</b> - the invoice row carries a {@code source_quote_version_id} (a
 *       Path A invoice, and every payment / void / D.8 version carried forward from it): its terms are
 *       the signed quote's frozen terms, copied by Path A.</li>
 *   <li><b>{@link Source#BUSINESS}</b> - no source quote version (D.1 Create / D.2 Rewrite and their
 *       payment / void / D.8 versions): its terms are the business's per-flooring-type terms, frozen
 *       into the version at Create or Rewrite.</li>
 * </ul>
 *
 * <p>Source PRESENCE selects the value - never whether the HTML is null or blank. {@code terms_source}
 * reports provenance only; it never selects a lookup.
 */
public record InvoiceTermsSelection(Source source, String frozenTermsHtml) {

    /** Serialized verbatim as {@code InvoiceDetail.terms_source}. */
    public enum Source {
        QUOTE,
        BUSINESS
    }

    public InvoiceTermsSelection {
        Objects.requireNonNull(source, "source");
    }

    /**
     * The selection for an invoice row's {@code source_quote_version_id} and {@code terms_snapshot}:
     * source present → {@link Source#QUOTE}, absent → {@link Source#BUSINESS}; the stored terms are
     * carried verbatim either way (null stays null).
     */
    public static InvoiceTermsSelection forInvoice(Long sourceQuoteVersionId, String termsSnapshot) {
        return new InvoiceTermsSelection(
                sourceQuoteVersionId != null ? Source.QUOTE : Source.BUSINESS, termsSnapshot);
    }
}
