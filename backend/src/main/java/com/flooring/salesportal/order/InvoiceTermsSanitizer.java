package com.flooring.salesportal.order;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 15C — sanitize tenant-supplied invoice terms HTML ({@code terms_hard} / {@code terms_soft})
 * for safe, XML-well-formed embedding in the invoice PDF.
 *
 * <p>The invoice template is parsed by openhtmltopdf with an XML parser, and the sanitized terms are
 * injected via {@code th:utext} (unescaped). So the output must be BOTH (a) restricted to a strict
 * safelist — no script/style/iframe/forms/event-handlers/links/remote images — AND (b) XML-well-formed
 * (balanced tags, self-closed voids, no unknown named entities). jsoup's {@code clean} with an XML
 * {@link Document.OutputSettings} gives us both in one pass.
 *
 * <p>Fail-soft by contract: any null/blank input, parser/sanitizer failure, or content that sanitizes
 * down to nothing returns {@code null}. The caller renders no terms block (SOFT) / no terms page
 * (HARD) rather than letting bad tenant HTML break PDF generation for the whole invoice.
 */
@Component
public class InvoiceTermsSanitizer {

    private static final Logger log = LoggerFactory.getLogger(InvoiceTermsSanitizer.class);

    // Strict safelist: block-ish + inline formatting + lists + tables only. No links, no images (the
    // logo comes through the controlled base64 path, never terms HTML), no styling attributes beyond an
    // optional class for controlled template styling. Safelist.none() => nothing is allowed by default.
    private static final Safelist SAFELIST = Safelist.none()
            .addTags(
                    "p", "br", "strong", "b", "em", "i", "u",
                    "ol", "ul", "li",
                    "table", "thead", "tbody", "tr", "th", "td",
                    "div", "span", "small")
            .addAttributes(":all", "class");

    /**
     * Sanitize tenant terms HTML to a strict, XML-well-formed fragment safe for {@code th:utext} +
     * openhtmltopdf. Returns {@code null} when the input is null/blank, when sanitization fails, or when
     * the result has no visible content (so the caller hides the terms block/page).
     */
    public String sanitize(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return null;
        }
        try {
            // XML syntax => self-closes void elements (<br />) and keeps the fragment well-formed for the
            // XML parser. xhtml escape mode + UTF-8 avoids emitting named entities (e.g. &nbsp;) that an
            // XML parser would reject as undefined.
            Document.OutputSettings output = new Document.OutputSettings()
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .escapeMode(Entities.EscapeMode.xhtml)
                    .charset("UTF-8")
                    .prettyPrint(false);

            String cleaned = Jsoup.clean(rawHtml, "", SAFELIST, output);
            if (cleaned == null || cleaned.isBlank()) {
                return null;
            }
            // Drop terms that sanitize down to markup with no visible text (e.g. "<p></p>"): hide rather
            // than render an empty heading/page.
            if (Jsoup.parse(cleaned).text().isBlank()) {
                return null;
            }
            return cleaned;
        } catch (RuntimeException ex) {
            // Never let malformed/hostile terms HTML take down PDF generation (create/rewrite, accept,
            // payment-regenerated). Hide the terms and continue.
            log.warn("Invoice terms sanitization failed — rendering no terms block for this invoice", ex);
            return null;
        }
    }
}
