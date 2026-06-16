package com.flooring.salesportal.order;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Phase 15C — unit tests for {@link InvoiceTermsSanitizer}. Proves the strict safelist (safe structure
 * kept, unsafe tags/attributes/links/images stripped), the XML-well-formed output required by
 * openhtmltopdf, and the fail-soft contract (null/blank/empty-after-sanitize -> null).
 */
class InvoiceTermsSanitizerTest {

    private final InvoiceTermsSanitizer sanitizer = new InvoiceTermsSanitizer();

    @Test
    void keepsSafeStructuralAndInlineTags() {
        String out = sanitizer.sanitize(
                "<p>Pay within <strong>14 days</strong>.</p><ul><li>Item one</li><li>Item two</li></ul>");
        Assertions.assertNotNull(out);
        Assertions.assertTrue(out.contains("<p>"), () -> "p kept: " + out);
        Assertions.assertTrue(out.contains("<strong>"), () -> "strong kept: " + out);
        Assertions.assertTrue(out.contains("<ul>"), () -> "ul kept: " + out);
        Assertions.assertTrue(out.contains("<li>"), () -> "li kept: " + out);
        Assertions.assertTrue(out.contains("14 days"), () -> "text kept: " + out);
    }

    @Test
    void keepsTables() {
        String out = sanitizer.sanitize(
                "<table><thead><tr><th>A</th></tr></thead><tbody><tr><td>B</td></tr></tbody></table>");
        Assertions.assertNotNull(out);
        Assertions.assertTrue(out.contains("<table>"));
        Assertions.assertTrue(out.contains("<th>"));
        Assertions.assertTrue(out.contains("<td>"));
    }

    @Test
    void stripsScriptTagAndContents() {
        // The <script> element and its contents are removed; with nothing else, the result is empty -> null.
        Assertions.assertNull(sanitizer.sanitize("<script>alert('xss')</script>"));
        // Script embedded with surrounding safe text: the text survives, the script is gone.
        String out = sanitizer.sanitize("<p>Safe.<script>alert('xss')</script></p>");
        Assertions.assertNotNull(out);
        Assertions.assertTrue(out.contains("Safe."), () -> "safe text dropped: " + out);
        Assertions.assertFalse(out.toLowerCase().contains("script"), () -> "script leaked: " + out);
        Assertions.assertFalse(out.contains("alert"), () -> "script body leaked: " + out);
    }

    @Test
    void stripsEventHandlersAndStyleAndIdAttributes() {
        String out = sanitizer.sanitize("<p id=\"x\" style=\"color:red\" onclick=\"steal()\">Hello</p>");
        Assertions.assertNotNull(out);
        Assertions.assertTrue(out.contains("Hello"));
        Assertions.assertFalse(out.contains("onclick"), () -> "event handler leaked: " + out);
        Assertions.assertFalse(out.contains("style="), () -> "style attr leaked: " + out);
        Assertions.assertFalse(out.contains("id="), () -> "id attr leaked: " + out);
    }

    @Test
    void keepsClassAttribute() {
        String out = sanitizer.sanitize("<p class=\"lead\">Hello</p>");
        Assertions.assertNotNull(out);
        Assertions.assertTrue(out.contains("class=\"lead\""), () -> "class attr dropped: " + out);
    }

    @Test
    void stripsLinksImagesIframesAndForms() {
        // None of a/img/iframe/form/input are in the safelist: tags removed, only safe text remains.
        String out = sanitizer.sanitize(
                "<p>Visit <a href=\"javascript:steal()\">here</a> "
                        + "<img src=\"http://evil/x.png\"/></p>"
                        + "<iframe src=\"http://evil\"></iframe>"
                        + "<form><input name=\"card\"/></form>");
        Assertions.assertNotNull(out);
        Assertions.assertTrue(out.contains("here"), () -> "anchor text dropped: " + out);
        Assertions.assertFalse(out.contains("<a"), () -> "anchor leaked: " + out);
        Assertions.assertFalse(out.toLowerCase().contains("javascript:"), () -> "js URL leaked: " + out);
        Assertions.assertFalse(out.contains("<img"), () -> "image leaked: " + out);
        Assertions.assertFalse(out.toLowerCase().contains("iframe"), () -> "iframe leaked: " + out);
        Assertions.assertFalse(out.toLowerCase().contains("<form"), () -> "form leaked: " + out);
        Assertions.assertFalse(out.toLowerCase().contains("<input"), () -> "input leaked: " + out);
    }

    @Test
    void emitsXmlWellFormedSelfClosedVoidTags() {
        String out = sanitizer.sanitize("Line one<br>Line two");
        Assertions.assertNotNull(out);
        // XML syntax self-closes <br>; collapse whitespace to be robust about the exact spacing.
        Assertions.assertTrue(out.replaceAll("\\s", "").contains("<br/>"),
                () -> "void tag not self-closed for XML parser: " + out);
        Assertions.assertTrue(out.contains("Line one") && out.contains("Line two"));
    }

    @Test
    void failsSoftToNullOnNullBlankOrEmptyContent() {
        Assertions.assertNull(sanitizer.sanitize(null));
        Assertions.assertNull(sanitizer.sanitize(""));
        Assertions.assertNull(sanitizer.sanitize("   "));
        // Markup with no visible text sanitizes down to nothing -> null (so the caller hides the block).
        Assertions.assertNull(sanitizer.sanitize("<p></p>"));
        Assertions.assertNull(sanitizer.sanitize("<style>.x{color:red}</style>"));
    }

    @Test
    void doesNotThrowOnMalformedHtml() {
        // jsoup is lenient; assert we never propagate an exception regardless of how broken the input is.
        Assertions.assertDoesNotThrow(() ->
                sanitizer.sanitize("<p>unclosed <strong>bold <ul><li>x</p></li>"));
        Assertions.assertDoesNotThrow(() -> sanitizer.sanitize("<<<>>> &nbsp; <p>ok</p>"));
    }
}
