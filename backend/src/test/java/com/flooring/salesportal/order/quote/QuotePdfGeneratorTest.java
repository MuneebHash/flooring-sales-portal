package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.order.quote.QuotePdfModel.QuotePdfLine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * DB-free unit test for {@link QuotePdfGenerator} (Phase 16C PR2). Exercises the real Thymeleaf
 * template ({@code templates/quote.html}) + openhtmltopdf-pdfbox pipeline end-to-end. Asserts the
 * output is a valid PDF (the {@code %PDF-} magic header) AND — via PDFBox text extraction — that the
 * quote model values render and that NO invoice semantics (TAX INVOICE / Payment Made / Balance Due /
 * Customer Acceptance / signature) appear. Guards against a malformed (non-XML-well-formed) template,
 * a missing PDF dependency, or a broken variable binding.
 */
class QuotePdfGeneratorTest {

    private static final QuotePdfGenerator GENERATOR = new QuotePdfGenerator();

    // A real, decodable 1x1 PNG: openhtmltopdf must actually decode the data URI when embedding the
    // logo image, so fake bytes would break the image-render path.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String ONE_PIXEL_PNG_DATA_URI =
            "data:image/png;base64," + Base64.getEncoder().encodeToString(ONE_PIXEL_PNG);

    private static QuotePdfLine item(String desc, String qty, String unit, String total) {
        return new QuotePdfLine("ITEM", desc, bd(qty), bd(unit), bd(total));
    }

    private static QuotePdfLine adjustment(String desc, String total) {
        return new QuotePdfLine("ADJUSTMENT", desc, null, null, bd(total));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /**
     * Builder for the (large) {@link QuotePdfModel} so each test sets only what it cares about. Defaults
     * are a plausible itemised SOFT quote with all optional tenant fields null/absent.
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
        String salespersonName = null;
        String customerName = "James Wilson";
        String billingLine1 = "42 Oxford Street";
        String billingLine2 = "Paddington NSW 2021";
        String detailsOfSale = "Supply and install plush carpet to lounge and dining rooms.";
        boolean itemised = true;
        List<QuotePdfLine> lines = new ArrayList<>(List.of(item("Carpet", "2.00", "111.00", "222.00")));
        BigDecimal quoteTotalExGst = bd("222.00");
        BigDecimal quoteTotalIncGst = bd("244.20");
        String bankName = null;
        String bsb = null;
        String accountName = null;
        String accountNumber = null;
        String termsHtml = null;

        QuotePdfModel build() {
            return new QuotePdfModel(
                    businessName, abn, logoDataUri, flooringTypeLabel,
                    storeName, storeAddressLine1, storeAddressLine2, storePhone, storeEmail,
                    orderNumber, salespersonName, customerName, billingLine1, billingLine2, detailsOfSale,
                    itemised, lines, quoteTotalExGst, quoteTotalIncGst,
                    bankName, bsb, accountName, accountNumber,
                    termsHtml);
        }
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    // PDFBox can inject spaces between glyphs (notably in right-aligned cells), so amount/order-number
    // assertions compare with all whitespace removed.
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

    /** Every quote render must read as a QUOTE and carry NONE of the invoice-only semantics. */
    private static void assertQuoteNotInvoice(String text) {
        Assertions.assertTrue(text.contains("QUOTE"), () -> "missing QUOTE title in: " + text);
        Assertions.assertFalse(text.contains("TAX INVOICE"), () -> "quote must not say TAX INVOICE: " + text);
        Assertions.assertFalse(text.contains("Payment Made"), () -> "quote must not show Payment Made: " + text);
        Assertions.assertFalse(text.contains("Balance Due"), () -> "quote must not show Balance Due: " + text);
        Assertions.assertFalse(text.toUpperCase().contains("CUSTOMER ACCEPTANCE"),
                () -> "quote must not show acceptance: " + text);
        Assertions.assertFalse(text.contains("Customer signature"), () -> "quote must not show signature line: " + text);
        Assertions.assertFalse(text.contains("Accepted by"), () -> "quote must not show acceptance text: " + text);
    }

    @Test
    void render_producesValidPdf_containingQuoteValues() throws IOException {
        byte[] pdf = GENERATOR.render(new M().build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertQuoteNotInvoice(text);
        Assertions.assertTrue(text.contains("Aussie Floors Group"), () -> "missing business name in: " + text);
        Assertions.assertTrue(noSpace(text).contains("SYD-CBD.LC1.00001"), () -> "missing order number in: " + text);
        Assertions.assertTrue(text.contains("James Wilson"), () -> "missing customer in: " + text);
        Assertions.assertTrue(text.contains("42 Oxford Street"), () -> "missing billing line in: " + text);
        Assertions.assertTrue(text.contains("Supply and install plush carpet"), () -> "missing details in: " + text);
        Assertions.assertTrue(text.contains("Carpet"), () -> "missing line description in: " + text);
    }

    @Test
    void render_itemised_rendersLineTableWithUnitAmountAndTotals() throws IOException {
        byte[] pdf = GENERATOR.render(new M().build());
        String text = noSpace(extractText(pdf));
        // unit ($111.00), line amount + subtotal ($222.00), GST (244.20 - 222.00 = 22.20), total ($244.20).
        Assertions.assertTrue(text.contains("111.00"), () -> "missing unit price in: " + text);
        Assertions.assertTrue(text.contains("222.00"), () -> "missing line amount / subtotal in: " + text);
        Assertions.assertTrue(text.contains("22.20"), () -> "missing GST amount in: " + text);
        Assertions.assertTrue(text.contains("244.20"), () -> "missing inc-GST total in: " + text);
    }

    @Test
    void render_adjustmentLine_rendersNegativeAmount() throws IOException {
        M m = new M();
        m.lines = new ArrayList<>(List.of(
                item("Carpet", "2.00", "100.00", "200.00"),
                adjustment("Discount", "-50.00")));
        m.quoteTotalExGst = bd("150.00");
        m.quoteTotalIncGst = bd("165.00");
        byte[] pdf = GENERATOR.render(m.build());
        String text = noSpace(extractText(pdf));
        Assertions.assertTrue(text.contains("Discount"), () -> "missing adjustment description in: " + text);
        Assertions.assertTrue(text.contains("-$50.00") || text.contains("-50.00"),
                () -> "missing signed negative adjustment amount in: " + text);
        Assertions.assertTrue(text.contains("150.00"), () -> "missing subtotal in: " + text);
    }

    @Test
    void render_nonItemised_rendersSingleAmount_noLineTableHeader() throws IOException {
        M m = new M();
        m.itemised = false;
        m.lines = new ArrayList<>();
        m.quoteTotalExGst = bd("500.00");
        m.quoteTotalIncGst = bd("550.00");
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertQuoteNotInvoice(text);
        // The .sec-h heading is uppercased by CSS text-transform (PDFBox extracts it uppercased).
        Assertions.assertTrue(text.toUpperCase().contains("QUOTED WORKS"), () -> "missing non-itemised heading in: " + text);
        Assertions.assertFalse(text.contains("Qty"), () -> "non-itemised quote must not render the line-table header: " + text);
        Assertions.assertTrue(noSpace(text).contains("550.00"), () -> "missing inc-GST total in: " + text);
    }

    @Test
    void render_nonItemised_withRetainedLines_ignoresLines_totalsFromModel() throws IOException {
        // Phase 16D-B PR2A: retained dormant draft lines legitimately accompany a non-itemised
        // draft (post toggle-OFF). The non-itemised render must ignore them entirely — no line
        // table, no line descriptions/amounts — and take the totals from the draft header only.
        M m = new M();
        m.itemised = false;
        m.lines = new ArrayList<>(List.of(
                item("Retained carpet line", "2.00", "100.00", "200.00"),
                adjustment("Retained discount", "-30.00")));
        m.quoteTotalExGst = bd("900.00");
        m.quoteTotalIncGst = bd("990.00");
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertFalse(text.contains("Qty"), () -> "retained lines must not render a line table: " + text);
        Assertions.assertFalse(text.contains("Retained carpet line"), () -> "retained ITEM row leaked into the PDF: " + text);
        Assertions.assertFalse(text.contains("Retained discount"), () -> "retained ADJUSTMENT row leaked into the PDF: " + text);
        Assertions.assertFalse(noSpace(text).contains("200.00"), () -> "retained line amount leaked into the PDF: " + text);
        Assertions.assertTrue(noSpace(text).contains("990.00"), () -> "missing header-derived inc-GST total in: " + text);
        Assertions.assertTrue(noSpace(text).contains("900.00"), () -> "missing header-derived ex-GST subtotal in: " + text);
    }

    @Test
    void render_softTerms_onDedicatedSecondPage_page1HasNoTerms() throws IOException {
        M m = new M();
        m.flooringTypeLabel = "Soft Flooring";
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
        Assertions.assertTrue(noSpace(page1).contains("SYD-CBD.LC1.00001"), () -> "page 1 must keep the quote summary: " + page1);
    }

    @Test
    void render_hardTerms_onDedicatedSecondPage_page1HasNoTerms() throws IOException {
        M m = new M();
        m.flooringTypeLabel = "Hard Flooring";
        m.termsHtml = "<p>Hard flooring care and installation clause one.</p>";
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        Assertions.assertEquals(2, pageCount(pdf), "HARD quote must have a dedicated terms page");
        String page1 = extractPageText(pdf, 1).replaceAll("\\s+", " ");
        String page2 = extractPageText(pdf, 2).replaceAll("\\s+", " ");
        Assertions.assertFalse(page1.contains("Hard flooring care"), () -> "page 1 must NOT contain hard terms: " + page1);
        Assertions.assertTrue(page2.contains("Hard flooring care"), () -> "page 2 must contain hard terms: " + page2);
    }

    @Test
    void render_noTerms_singlePage_footerOnce() throws IOException {
        byte[] pdf = GENERATOR.render(new M().build());
        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertEquals(1, pageCount(pdf), "no terms -> single page (no terms page created)");
        Assertions.assertFalse(text.contains("TERMS"), "no terms -> no terms heading");
        Assertions.assertEquals(1, countOccurrences(text, "Generated by the Flooring Sales Portal"),
                () -> "footer must render exactly once when there are no terms: " + text);
    }

    @Test
    void render_withTerms_footerRendersExactlyOnceOnTermsPage() throws IOException {
        M m = new M();
        m.termsHtml = "<p>Some quote terms apply.</p>";
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
        M m = new M();
        m.termsHtml = "<ol><li>First clause.</li><li>Second clause.</li></ol>";
        byte[] pdf = GENERATOR.render(m.build());
        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("First clause."), () -> "missing list item 1: " + text);
        Assertions.assertTrue(text.contains("Second clause."), () -> "missing list item 2: " + text);
        Assertions.assertFalse(text.contains("<li>"), () -> "raw tags leaked as text: " + text);
    }

    @Test
    void render_escapesUserTextSafelyAndKeepsItVisible() throws IOException {
        M m = new M();
        m.customerName = "James Wilson & Co";
        m.detailsOfSale = "Tile & timber to lounge < dining > hallway; premium finish.";
        m.lines = new ArrayList<>(List.of(item("Carpet & underlay", "1.00", "1000.00", "1000.00")));
        m.quoteTotalExGst = bd("1000.00");
        m.quoteTotalIncGst = bd("1100.00");
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("Tile & timber"), () -> "escaped ampersand not rendered: " + text);
        Assertions.assertTrue(text.contains("Wilson & Co"), () -> "escaped name not rendered: " + text);
        Assertions.assertTrue(text.contains("1,000.00"), () -> "grouped amount not rendered: " + text);
    }

    @Test
    void render_withLogoDataUri_embedsLogoImage_andSuppressesBusinessNameText() throws IOException {
        M m = new M();
        m.logoDataUri = ONE_PIXEL_PNG_DATA_URI;
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);
        Assertions.assertTrue(countImages(pdf) >= 1, "logo data URI must embed an image");
        Assertions.assertFalse(extractText(pdf).contains("Aussie Floors Group"),
                "plain business name should be suppressed when a logo is present");
    }

    @Test
    void render_withNullLogo_rendersBusinessNameAndNoImage() throws IOException {
        byte[] pdf = GENERATOR.render(new M().build());
        assertPdfHeader(pdf);
        Assertions.assertEquals(0, countImages(pdf), "no logo -> no embedded image");
        Assertions.assertTrue(extractText(pdf).contains("Aussie Floors Group"));
    }

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
        Assertions.assertTrue(text.contains("Liam Carter"), () -> "missing salesperson: " + text);
        Assertions.assertTrue(text.contains("Example Bank"), () -> "missing bank name: " + text);
        Assertions.assertTrue(text.contains("12345678"), () -> "missing account number: " + text);
    }

    @Test
    void render_withAllOptionalFieldsNull_doesNotNpe() throws IOException {
        QuotePdfModel model = new QuotePdfModel(
                "Aussie Floors Group", null, null, null,
                null, null, null, null, null,
                "SYD-CBD.LC1.00099", null, null, null, null, null,
                true, new ArrayList<>(), bd("0.00"), bd("0.00"),
                null, null, null, null,
                null);
        byte[] pdf = GENERATOR.render(model);
        assertPdfHeader(pdf);
        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertQuoteNotInvoice(text);
        Assertions.assertFalse(text.contains("PAYMENT METHODS"), () -> "bank heading shown with no bank data: " + text);
    }
}
