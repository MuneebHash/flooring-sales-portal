package com.flooring.salesportal.order;

import com.flooring.salesportal.order.InvoicePdfGenerator.InvoicePdfModel;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * DB-free unit test for {@link InvoicePdfGenerator} (Phase 12 Branch A). Exercises the real Thymeleaf
 * template ({@code templates/invoice.html}) + openhtmltopdf-pdfbox pipeline end-to-end. Asserts the
 * output is a valid PDF (the {@code %PDF-} magic header) AND — via PDFBox text extraction — that the
 * model values actually render into the document (so a dropped/typo'd template variable is caught).
 * This is the only new Branch A code that does not need a database, so it runs in any environment and
 * guards against a malformed (non-XML-well-formed) template, a missing PDF dependency, or a broken
 * variable binding.
 */
class InvoicePdfGeneratorTest {

    private static final InvoicePdfGenerator GENERATOR = new InvoicePdfGenerator();

    private static InvoicePdfModel sampleModel(LocalDate dueDate) {
        return new InvoicePdfModel(
                "Aussie Floors Group",
                "SYD-CBD.LC1.00001",
                1,
                LocalDate.of(2026, 4, 14),
                dueDate,
                "James Wilson",
                "42 Oxford Street",
                "Paddington NSW 2021",
                "Supply and install plush carpet to lounge and dining rooms. Furniture to be moved by installer.",
                new BigDecimal("924.00"),
                new BigDecimal("500.00"),
                new BigDecimal("424.00"));
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static void assertPdfHeader(byte[] pdf) {
        Assertions.assertNotNull(pdf);
        Assertions.assertTrue(pdf.length > 500, () -> "PDF unexpectedly small: " + pdf.length + " bytes");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII),
                "output must begin with the PDF magic header");
    }

    @Test
    void render_producesValidPdfContainingModelValues() throws IOException {
        byte[] pdf = GENERATOR.render(sampleModel(LocalDate.of(2026, 4, 29)));
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("TAX INVOICE"), () -> "missing title in: " + text);
        Assertions.assertTrue(text.contains("Aussie Floors Group"), () -> "missing business name in: " + text);
        Assertions.assertTrue(text.contains("SYD-CBD.LC1.00001"), () -> "missing order number in: " + text);
        Assertions.assertTrue(text.contains("James Wilson"), () -> "missing customer in: " + text);
        Assertions.assertTrue(text.contains("42 Oxford Street"), () -> "missing billing line in: " + text);
        Assertions.assertTrue(text.contains("29/04/2026"), () -> "missing due date in: " + text);
        Assertions.assertTrue(text.contains("Supply and install plush carpet"), () -> "missing details in: " + text);
        // Money is locale-pinned (English) -> "924.00" / "500.00" / "424.00".
        Assertions.assertTrue(text.contains("924.00"), () -> "missing total inc GST in: " + text);
        Assertions.assertTrue(text.contains("500.00"), () -> "missing payment made in: " + text);
        Assertions.assertTrue(text.contains("424.00"), () -> "missing balance due in: " + text);
    }

    @Test
    void render_withNullDueDate_stillRendersPlaceholder() throws IOException {
        // Branch A always sets a due date, but the generator must not NPE if a null is ever passed.
        byte[] pdf = GENERATOR.render(sampleModel(null));
        assertPdfHeader(pdf);
        Assertions.assertTrue(extractText(pdf).contains("SYD-CBD.LC1.00001"));
    }

    @Test
    void render_escapesUserTextSafelyAndKeepsItVisible() throws IOException {
        // Details / name containing XML-significant characters must not break the (XML-parsed) template,
        // and the literal characters must still be visible in the rendered document.
        InvoicePdfModel model = new InvoicePdfModel(
                "Aussie Floors Group", "SYD-CBD.LC1.00001", 1,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 29),
                "James Wilson & Co", "42 Oxford Street", "Paddington NSW 2021",
                "Tile & timber to lounge < dining > hallway; premium finish.",
                new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("1000.00"));
        byte[] pdf = GENERATOR.render(model);
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("Tile & timber"), () -> "escaped ampersand not rendered: " + text);
        Assertions.assertTrue(text.contains("Wilson & Co"), () -> "escaped name not rendered: " + text);
        Assertions.assertTrue(text.contains("1,000.00"), () -> "grouped amount not rendered: " + text);
    }
}
