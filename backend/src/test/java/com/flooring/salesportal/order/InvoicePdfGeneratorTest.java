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
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * DB-free unit test for {@link InvoicePdfGenerator} (Phase 12 Branch A; extended for the Phase 15C
 * tenant invoice layout). Exercises the real Thymeleaf template ({@code templates/invoice.html}) +
 * openhtmltopdf-pdfbox pipeline end-to-end. Asserts the output is a valid PDF (the {@code %PDF-} magic
 * header) AND — via PDFBox text extraction — that the model values actually render into the document
 * (so a dropped/typo'd template variable is caught). Runs in any environment and guards against a
 * malformed (non-XML-well-formed) template, a missing PDF dependency, or a broken variable binding.
 */
class InvoicePdfGeneratorTest {

    private static final InvoicePdfGenerator GENERATOR = new InvoicePdfGenerator();

    // A real, decodable 1x1 PNG: openhtmltopdf must actually decode the data URI when embedding the
    // signature / logo image, so fake bytes would break the image-render path.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String ONE_PIXEL_PNG_DATA_URI =
            "data:image/png;base64," + Base64.getEncoder().encodeToString(ONE_PIXEL_PNG);

    /**
     * Builder for the (large) {@link InvoicePdfModel} so each test sets only what it cares about and the
     * 30-arg constructor lives in exactly one place. Defaults are a plausible unaccepted SOFT invoice
     * with all optional tenant fields null/absent.
     */
    private static final class M {
        String businessName = "Aussie Floors Group";
        String abn = null;
        String logoDataUri = null;
        String flooringTypeLabel = null;
        String storeName = null;
        String storeAddressLine1 = null;
        String storeAddressLine2 = null;
        String storePhone = null;
        String storeEmail = null;
        String orderNumber = "SYD-CBD.LC1.00001";
        int versionNumber = 1;
        LocalDate invoiceDate = LocalDate.of(2026, 4, 14);
        LocalDate dueDate = LocalDate.of(2026, 4, 29);
        String salespersonName = null;
        String customerName = "James Wilson";
        String billingLine1 = "42 Oxford Street";
        String billingLine2 = "Paddington NSW 2021";
        String detailsOfSale = "Supply and install plush carpet to lounge and dining rooms.";
        BigDecimal salePriceIncGst = new BigDecimal("924.00");
        BigDecimal totalPaid = new BigDecimal("500.00");
        BigDecimal balanceDue = new BigDecimal("424.00");
        String bankName = null;
        String bsb = null;
        String accountName = null;
        String accountNumber = null;
        LocalDateTime acceptedAt = null;
        String acceptedCustomerName = null;
        byte[] signaturePng = null;
        String termsHtml = null;
        boolean termsOnSeparatePage = false;

        InvoicePdfModel build() {
            return new InvoicePdfModel(
                    businessName, abn, logoDataUri, flooringTypeLabel,
                    storeName, storeAddressLine1, storeAddressLine2, storePhone, storeEmail,
                    orderNumber, versionNumber, invoiceDate, dueDate, salespersonName,
                    customerName, billingLine1, billingLine2,
                    detailsOfSale, salePriceIncGst, totalPaid, balanceDue,
                    bankName, bsb, accountName, accountNumber,
                    acceptedAt, acceptedCustomerName, signaturePng,
                    termsHtml, termsOnSeparatePage);
        }
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    // PDFBox can inject spaces between glyphs (notably in the monospace order number / right-aligned
    // cells), so order-number assertions compare with all whitespace removed.
    private static String noSpace(String s) {
        return s.replaceAll("\\s", "");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) != -1) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static String extractPageText(byte[] pdf, int page) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document);
        }
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        }
    }

    private static int countImages(byte[] pdf) throws IOException {
        int images = 0;
        try (PDDocument document = PDDocument.load(pdf)) {
            for (var page : document.getPages()) {
                var resources = page.getResources();
                for (var name : resources.getXObjectNames()) {
                    if (resources.isImageXObject(name)) {
                        images++;
                    }
                }
            }
        }
        return images;
    }

    private static void assertPdfHeader(byte[] pdf) {
        Assertions.assertNotNull(pdf);
        Assertions.assertTrue(pdf.length > 500, () -> "PDF unexpectedly small: " + pdf.length + " bytes");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII),
                "output must begin with the PDF magic header");
    }

    @Test
    void render_producesValidPdfContainingModelValues() throws IOException {
        byte[] pdf = GENERATOR.render(new M().build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("TAX INVOICE"), () -> "missing title in: " + text);
        Assertions.assertTrue(text.contains("Aussie Floors Group"), () -> "missing business name in: " + text);
        Assertions.assertTrue(noSpace(text).contains("SYD-CBD.LC1.00001"), () -> "missing order number in: " + text);
        Assertions.assertTrue(text.contains("James Wilson"), () -> "missing customer in: " + text);
        Assertions.assertTrue(text.contains("42 Oxford Street"), () -> "missing billing line in: " + text);
        Assertions.assertTrue(text.contains("29/04/2026"), () -> "missing due date in: " + text);
        Assertions.assertTrue(text.contains("Supply and install plush carpet"), () -> "missing details in: " + text);
        Assertions.assertTrue(text.contains("924.00"), () -> "missing total inc GST in: " + text);
        Assertions.assertTrue(text.contains("500.00"), () -> "missing payment made in: " + text);
        Assertions.assertTrue(text.contains("424.00"), () -> "missing balance due in: " + text);
    }

    @Test
    void render_doesNotRenderRevisionOrVersionInBody() throws IOException {
        // Phase 15C: no revision/version number in the PDF body.
        M m = new M();
        m.versionNumber = 7;
        String text = extractText(GENERATOR.render(m.build())).replaceAll("\\s+", " ");
        Assertions.assertFalse(text.toLowerCase().contains("version"), () -> "version label leaked: " + text);
        Assertions.assertFalse(text.contains("v7"), () -> "version number leaked: " + text);
    }

    @Test
    void render_withNullDueDate_stillRendersPlaceholder() throws IOException {
        M m = new M();
        m.dueDate = null;
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);
        Assertions.assertTrue(noSpace(extractText(pdf)).contains("SYD-CBD.LC1.00001"));
    }

    @Test
    void render_escapesUserTextSafelyAndKeepsItVisible() throws IOException {
        M m = new M();
        m.customerName = "James Wilson & Co";
        m.detailsOfSale = "Tile & timber to lounge < dining > hallway; premium finish.";
        m.salePriceIncGst = new BigDecimal("1000.00");
        m.totalPaid = new BigDecimal("0.00");
        m.balanceDue = new BigDecimal("1000.00");
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("Tile & timber"), () -> "escaped ampersand not rendered: " + text);
        Assertions.assertTrue(text.contains("Wilson & Co"), () -> "escaped name not rendered: " + text);
        Assertions.assertTrue(text.contains("1,000.00"), () -> "grouped amount not rendered: " + text);
    }

    // ------------------------------------------------------------------
    // Phase 15C — tenant / store / salesperson / bank layout fields
    // ------------------------------------------------------------------

    @Test
    void render_withTenantLayoutData_rendersAbnStoreSalespersonAndBank() throws IOException {
        M m = new M();
        m.abn = "11 222 333 444";
        m.flooringTypeLabel = "Soft Flooring";
        m.storeName = "Sydney CBD";
        m.storeAddressLine1 = "100 George Street";
        m.storeAddressLine2 = "Sydney NSW 2000";
        m.storePhone = "02 9000 0000";
        m.storeEmail = "cbd@aussiefloors.example";
        m.salespersonName = "Liam Carter";
        m.bankName = "Example Bank";
        m.bsb = "062-000";
        m.accountName = "Aussie Floors Pty Ltd";
        m.accountNumber = "12345678";
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("11 222 333 444"), () -> "missing ABN: " + text);
        Assertions.assertTrue(text.contains("Sydney CBD"), () -> "missing store name: " + text);
        Assertions.assertTrue(text.contains("100 George Street"), () -> "missing store street: " + text);
        Assertions.assertTrue(text.contains("Sydney NSW 2000"), () -> "missing store locality: " + text);
        Assertions.assertTrue(text.contains("02 9000 0000"), () -> "missing store phone: " + text);
        Assertions.assertTrue(text.contains("cbd@aussiefloors.example"), () -> "missing store email: " + text);
        Assertions.assertTrue(text.contains("Liam Carter"), () -> "missing salesperson: " + text);
        Assertions.assertTrue(text.contains("Example Bank"), () -> "missing bank name: " + text);
        Assertions.assertTrue(text.contains("062-000"), () -> "missing BSB: " + text);
        Assertions.assertTrue(text.contains("Aussie Floors Pty Ltd"), () -> "missing account name: " + text);
        Assertions.assertTrue(text.contains("12345678"), () -> "missing account number: " + text);
    }

    @Test
    void render_withAllTenantFieldsNull_doesNotNpeOrLeakEmptyLabels() throws IOException {
        // Default builder leaves every optional tenant field null — must render cleanly.
        byte[] pdf = GENERATOR.render(new M().build());
        assertPdfHeader(pdf);
        String text = extractText(pdf).replaceAll("\\s+", " ");
        // Phase 16A PR3: the payment heading was renamed "Payment details" -> "Payment Methods"
        // (rendered uppercase by the section-title text-transform, which PDFBox extracts uppercased).
        Assertions.assertFalse(text.contains("PAYMENT METHODS"), () -> "bank heading shown with no bank data: " + text);
        Assertions.assertFalse(text.contains("Sales person"), () -> "salesperson label shown with no name: " + text);
    }

    @Test
    void render_withLogoDataUri_embedsLogoImage() throws IOException {
        M m = new M();
        m.logoDataUri = ONE_PIXEL_PNG_DATA_URI;
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);
        Assertions.assertTrue(countImages(pdf) >= 1, "logo data URI must embed an image");
        // Phase 16A PR3: when a logo is present the logo carries the brand, so the plain business-name
        // text is intentionally suppressed (the no-logo fallback is covered by
        // render_withNullLogo_rendersBusinessNameAndNoImage).
        Assertions.assertFalse(extractText(pdf).contains("Aussie Floors Group"),
                "plain business name should be suppressed when a logo is present");
    }

    @Test
    void render_withNullLogo_rendersBusinessNameAndNoImage() throws IOException {
        byte[] pdf = GENERATOR.render(new M().build());
        assertPdfHeader(pdf);
        Assertions.assertEquals(0, countImages(pdf), "no logo + no signature -> no embedded image");
        Assertions.assertTrue(extractText(pdf).contains("Aussie Floors Group"));
    }

    // ------------------------------------------------------------------
    // Phase 16A PR3 — terms ALWAYS start on a dedicated page 2 (SOFT and HARD); page 1 never has terms
    // ------------------------------------------------------------------

    @Test
    void render_softTerms_onDedicatedSecondPage_page1HasNoTerms() throws IOException {
        // Phase 16A PR3: SOFT terms no longer render inline on page 1 — they start on page 2 like HARD.
        M m = new M();
        m.flooringTypeLabel = "Soft Flooring";
        m.termsOnSeparatePage = false;
        m.termsHtml = "<p>Soft flooring stain warranty applies for twelve months.</p>";
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        Assertions.assertEquals(2, pageCount(pdf), "terms must start on a dedicated second page");
        String page1 = extractPageText(pdf, 1).replaceAll("\\s+", " ");
        String page2 = extractPageText(pdf, 2).replaceAll("\\s+", " ");
        Assertions.assertFalse(page1.contains("Soft flooring stain warranty"), () -> "page 1 must NOT contain soft terms: " + page1);
        Assertions.assertFalse(page1.contains("TERMS"), () -> "page 1 must NOT contain a terms heading: " + page1);
        Assertions.assertTrue(page2.contains("Soft flooring stain warranty"), () -> "page 2 must contain soft terms: " + page2);
        Assertions.assertTrue(page2.contains("TERMS"), () -> "page 2 must contain the terms heading: " + page2);
        Assertions.assertTrue(noSpace(page1).contains("SYD-CBD.LC1.00001"), () -> "page 1 must keep the invoice summary: " + page1);
    }

    @Test
    void render_hardTerms_onDedicatedSecondPage_page1HasNoTerms() throws IOException {
        M m = new M();
        m.flooringTypeLabel = "Hard Flooring";
        m.termsOnSeparatePage = true;
        m.termsHtml = "<p>Hard flooring care and installation clause one.</p>";
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        Assertions.assertEquals(2, pageCount(pdf), "HARD invoice must have a dedicated terms page");
        String page1 = extractPageText(pdf, 1).replaceAll("\\s+", " ");
        String page2 = extractPageText(pdf, 2).replaceAll("\\s+", " ");
        Assertions.assertFalse(page1.contains("Hard flooring care"), () -> "page 1 must NOT contain hard terms: " + page1);
        Assertions.assertFalse(page1.contains("TERMS"), () -> "page 1 must NOT contain a terms heading: " + page1);
        Assertions.assertTrue(page2.contains("Hard flooring care"), () -> "page 2 must contain hard terms: " + page2);
        Assertions.assertTrue(noSpace(page1).contains("SYD-CBD.LC1.00001"), () -> "page 1 must keep the invoice summary: " + page1);
    }

    @Test
    void render_noTerms_rendersNoTermsHeadingAndSinglePage() throws IOException {
        // termsHtml null (e.g. sanitized to blank) -> no terms block / no extra page on either type.
        M soft = new M();
        soft.termsOnSeparatePage = false;
        soft.termsHtml = null;
        byte[] softPdf = GENERATOR.render(soft.build());
        Assertions.assertEquals(1, pageCount(softPdf));
        Assertions.assertFalse(extractText(softPdf).contains("TERMS"), "no terms -> no heading (SOFT)");

        M hard = new M();
        hard.termsOnSeparatePage = true;
        hard.termsHtml = null;
        byte[] hardPdf = GENERATOR.render(hard.build());
        Assertions.assertEquals(1, pageCount(hardPdf), "no hard terms -> no second page");
        Assertions.assertFalse(extractText(hardPdf).contains("TERMS"), "no terms -> no heading (HARD)");
    }

    @Test
    void render_noTerms_stillRendersFooterExactlyOnceAndNoTermsPage() throws IOException {
        // Codex fix: with no terms there is no terms page, but the footer must still render exactly once
        // (the terms-page footer is gone, so a no-terms fallback footer renders at the end instead).
        for (boolean separate : new boolean[]{false, true}) {
            M m = new M();
            m.termsOnSeparatePage = separate;
            m.termsHtml = null;
            byte[] pdf = GENERATOR.render(m.build());
            String text = extractText(pdf).replaceAll("\\s+", " ");
            Assertions.assertEquals(1, pageCount(pdf), "no terms -> single page (no terms page created)");
            Assertions.assertFalse(text.contains("TERMS"), "no terms -> no terms heading");
            Assertions.assertEquals(1, countOccurrences(text, "Generated by the Flooring Sales Portal"),
                    () -> "footer must render exactly once when there are no terms: " + text);
        }
    }

    @Test
    void render_withTerms_footerRendersExactlyOnceOnTermsPage() throws IOException {
        // The terms-page footer and the no-terms fallback footer are mutually exclusive (termsHtml != null
        // vs == null), so the footer is never duplicated when terms exist.
        M m = new M();
        m.termsHtml = "<p>Some terms apply.</p>";
        byte[] pdf = GENERATOR.render(m.build());
        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertEquals(1, countOccurrences(text, "Generated by the Flooring Sales Portal"),
                () -> "footer must render exactly once when terms exist (no duplicate): " + text);
        Assertions.assertEquals(2, pageCount(pdf), "terms exist -> terms on a dedicated second page");
        String page2 = extractPageText(pdf, 2).replaceAll("\\s+", " ");
        Assertions.assertTrue(page2.contains("Generated by the Flooring Sales Portal"),
                () -> "footer must appear on the terms page: " + page2);
    }

    @Test
    void render_termsHtml_rendersStructureAsRichText() throws IOException {
        // Sanitized list HTML should render its item text (proves th:utext, not escaped th:text).
        M m = new M();
        m.termsHtml = "<ol><li>First clause.</li><li>Second clause.</li></ol>";
        byte[] pdf = GENERATOR.render(m.build());
        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("First clause."), () -> "missing list item 1: " + text);
        Assertions.assertTrue(text.contains("Second clause."), () -> "missing list item 2: " + text);
        // The literal tags must NOT appear as text (would mean th:text escaping instead of th:utext).
        Assertions.assertFalse(text.contains("<li>"), () -> "raw tags leaked as text: " + text);
    }

    // ------------------------------------------------------------------
    // Phase 13 §8 — acceptance/signature block
    // ------------------------------------------------------------------

    @Test
    void render_unaccepted_rendersBlankSignatureAreaWithoutAcceptanceText() throws IOException {
        byte[] pdf = GENERATOR.render(new M().build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("CUSTOMER ACCEPTANCE"), () -> "missing acceptance section: " + text);
        Assertions.assertTrue(text.contains("Customer signature"), () -> "missing blank-area caption: " + text);
        Assertions.assertFalse(text.contains("Accepted by"), () -> "unaccepted PDF must not show acceptance: " + text);
        Assertions.assertEquals(0, countImages(pdf), "unaccepted PDF must embed no signature image");
    }

    @Test
    void render_accepted_embedsSignatureImageNameAndTimestamp() throws IOException {
        M m = new M();
        m.versionNumber = 2;
        m.invoiceDate = LocalDate.of(2026, 4, 22);
        m.acceptedAt = LocalDateTime.of(2026, 4, 22, 14, 31, 10);
        m.acceptedCustomerName = "James Wilson Jr";
        m.signaturePng = ONE_PIXEL_PNG;
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("CUSTOMER ACCEPTANCE"), () -> "missing acceptance section: " + text);
        Assertions.assertTrue(text.contains("Accepted by"), () -> "missing acceptance caption: " + text);
        Assertions.assertTrue(text.contains("James Wilson Jr"), () -> "missing accepted name: " + text);
        Assertions.assertTrue(text.contains("22/04/2026 14:31"), () -> "missing accepted timestamp: " + text);
        Assertions.assertFalse(text.contains("Customer signature"),
                () -> "accepted PDF must not show the blank-area caption: " + text);
        Assertions.assertTrue(countImages(pdf) >= 1, "accepted PDF must embed the signature image");
    }

    @Test
    void render_accepted_withoutSignatureBytes_stillRendersCaption() throws IOException {
        M m = new M();
        m.acceptedAt = LocalDateTime.of(2026, 4, 22, 14, 31, 10);
        m.acceptedCustomerName = "James Wilson Jr";
        m.signaturePng = null;
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);
        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("Accepted by"), () -> "missing caption: " + text);
        Assertions.assertTrue(text.contains("James Wilson Jr"), () -> "missing accepted name: " + text);
        Assertions.assertEquals(0, countImages(pdf), "no signature bytes -> no embedded image");
    }
}
