package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.order.quote.QuotePdfModel.QuotePdfLine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DB-free unit test for {@link QuotePdfGenerator} (Phase 16C PR2, extended Phase 16D-C). Exercises
 * the real Thymeleaf template ({@code templates/quote.html}) + openhtmltopdf-pdfbox pipeline
 * end-to-end. Asserts the output is a valid PDF (the {@code %PDF-} magic header) AND — via PDFBox
 * text extraction — that the quote model values render, that the document reads as a QUOTATION
 * (16D-C: title/recipient wording, deposit line, blank invoice-style "Customer Acceptance" area
 * adapted invoice-to-quotation), and that NO invoice-only semantics (TAX INVOICE / Invoice To /
 * Payment Made / Balance Due / Accepted by / "of this invoice" / "value shown on this invoice")
 * appear. The Customer Acceptance heading and the "Customer signature" caption are deliberately
 * SHARED with the invoice document (16D-C fix round 2). Guards against a malformed
 * (non-XML-well-formed) template, a missing PDF dependency, or a broken variable binding.
 *
 * <p>Phase 16F PR1 adds the SIGNED render (acceptance fields set on the model): the one quote
 * document where "Accepted by {name} on {dd/MM/yyyy HH:mm}" legitimately appears, with the stored
 * signature embedded as an image and both declaration squares ticked (pure CSS fills, proven via the
 * page content stream). The default {@link M} model stays UNSIGNED; signed tests opt in through
 * {@link M#buildSigned}.
 */
class QuotePdfGeneratorTest {

    private static final QuotePdfGenerator GENERATOR = new QuotePdfGenerator();

    // A real, decodable 1x1 PNG: openhtmltopdf must actually decode the data URI when embedding the
    // logo image, so fake bytes would break the image-render path.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String ONE_PIXEL_PNG_DATA_URI =
            "data:image/png;base64," + Base64.getEncoder().encodeToString(ONE_PIXEL_PNG);

    // Phase 16F PR1: a second real, decodable PNG (2x2) whose bytes DIFFER from ONE_PIXEL_PNG.
    // openhtmltopdf reuses ONE image XObject for byte-identical data URIs, so a signature identical
    // to the logo would never add a second image — logo + signature tests must use distinct bytes.
    private static final byte[] TWO_BY_TWO_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4XmPgtzUDIgYIBQANqgIJTdLlxQAAAABJRU5ErkJggg==");

    // Phase 16F PR1 acceptance time. Single-digit day/month/hour/minute + non-zero seconds prove the
    // caption uses the zero-padded "dd/MM/yyyy HH:mm" pattern and drops the seconds.
    private static final LocalDateTime ACCEPTED_AT = LocalDateTime.of(2026, 3, 7, 9, 5, 42);
    private static final String ACCEPTED_AT_DISPLAY = "07/03/2026 09:05";

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

        /**
         * Phase 16F PR1 — the SAME quote as {@link #build()} but SIGNED (canonical constructor with the
         * acceptance fields set), so a test can render one model both unsigned and signed and compare.
         */
        QuotePdfModel buildSigned(LocalDateTime acceptedAt, String acceptedCustomerName, byte[] signaturePng) {
            return new QuotePdfModel(
                    businessName, abn, logoDataUri, flooringTypeLabel,
                    storeName, storeAddressLine1, storeAddressLine2, storePhone, storeEmail,
                    orderNumber, salespersonName, customerName, billingLine1, billingLine2, detailsOfSale,
                    itemised, lines, quoteTotalExGst, quoteTotalIncGst,
                    bankName, bsb, accountName, accountNumber,
                    termsHtml,
                    acceptedAt, acceptedCustomerName, signaturePng);
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

    private static int countImagesOnPage(byte[] pdf, int page) throws IOException {
        int images = 0;
        try (PDDocument document = PDDocument.load(pdf)) {
            var resources = document.getPage(page - 1).getResources();
            for (var name : resources.getXObjectNames()) {
                if (resources.isImageXObject(name)) {
                    images++;
                }
            }
        }
        return images;
    }

    /**
     * Phase 16F PR1 — what one page's content stream PAINTS: {@code fills} = the number of fill
     * operators; {@code tickSquares} = how many of those fills paint a small (3–7pt) square.
     */
    private record PagePaint(int fills, int tickSquares) {
    }

    /**
     * Tokenises one page's (decoded) content stream with PDFBox's {@link PDFStreamParser} and counts
     * the painted shapes. openhtmltopdf emits every CSS border and background as a closed path
     * ({@code m}/{@code l}/{@code h}) in page points followed by a fill operator ({@code f}); it emits
     * no {@code re} operators, but {@code re} is handled too. The bounding box of the current path is
     * tracked so each fill can be classified: a declaration square's 1pt border paints four thin 9pt x
     * 1pt trapezoids, table rules paint long thin strips, and only the signed render's inner tick
     * ({@code .chk-tick}, a 5pt x 5pt background) paints a small square. Clip paths ({@code W n}) and
     * strokes reset the box without counting as fills.
     */
    private static PagePaint pagePaint(byte[] pdf, int page) throws IOException {
        byte[] content;
        try (PDDocument document = PDDocument.load(pdf);
             InputStream in = document.getPage(page - 1).getContents()) {
            content = in.readAllBytes();
        }
        PDFStreamParser parser = new PDFStreamParser(content);
        List<Float> operands = new ArrayList<>();
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        int fills = 0;
        int tickSquares = 0;
        Object token;
        while ((token = parser.parseNextToken()) != null) {
            if (!(token instanceof Operator op)) {
                if (token instanceof COSNumber number) {
                    operands.add(number.floatValue());
                }
                continue;
            }
            switch (op.getName()) {
                case "m", "l", "c" -> {
                    for (int i = 0; i + 1 < operands.size(); i += 2) {
                        minX = Math.min(minX, operands.get(i));
                        maxX = Math.max(maxX, operands.get(i));
                        minY = Math.min(minY, operands.get(i + 1));
                        maxY = Math.max(maxY, operands.get(i + 1));
                    }
                }
                case "re" -> {
                    if (operands.size() == 4) {
                        float x = operands.get(0);
                        float y = operands.get(1);
                        float w = operands.get(2);
                        float h = operands.get(3);
                        minX = Math.min(minX, Math.min(x, x + w));
                        maxX = Math.max(maxX, Math.max(x, x + w));
                        minY = Math.min(minY, Math.min(y, y + h));
                        maxY = Math.max(maxY, Math.max(y, y + h));
                    }
                }
                case "f", "F", "f*", "B", "B*", "b", "b*" -> {
                    fills++;
                    float width = maxX - minX;
                    float height = maxY - minY;
                    if (width >= 3f && width <= 7f && height >= 3f && height <= 7f
                            && Math.abs(width - height) < 0.5f) {
                        tickSquares++;
                    }
                    minX = Float.MAX_VALUE;
                    minY = Float.MAX_VALUE;
                    maxX = -Float.MAX_VALUE;
                    maxY = -Float.MAX_VALUE;
                }
                case "S", "s", "n" -> {
                    minX = Float.MAX_VALUE;
                    minY = Float.MAX_VALUE;
                    maxX = -Float.MAX_VALUE;
                    maxY = -Float.MAX_VALUE;
                }
                default -> {
                    // Text, colour, state and image operators paint no path.
                }
            }
            operands.clear();
        }
        return new PagePaint(fills, tickSquares);
    }

    private static void assertPdfHeader(byte[] pdf) {
        Assertions.assertNotNull(pdf);
        Assertions.assertTrue(pdf.length > 500, () -> "PDF unexpectedly small: " + pdf.length + " bytes");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII),
                "output must begin with the PDF magic header");
    }

    // Word-boundary regexes (Phase 16D-C): "QUOTATION".contains("QUOTE") is true, so plain contains()
    // could pass silently against a stale QUOTE title. \bQUOTE\b matches a standalone QUOTE word only
    // (not QUOTATION / QUOTED), and \bQUOTATION\b requires the real retitled document.
    private static final Pattern STANDALONE_QUOTATION = Pattern.compile("\\bQUOTATION\\b");
    private static final Pattern STANDALONE_QUOTE = Pattern.compile("\\bQUOTE\\b");

    /**
     * Every quote render must read as a QUOTATION and carry NONE of the invoice-only semantics.
     * 16D-C fix round 2: the "Customer Acceptance" heading and the "Customer signature" caption are
     * legitimately SHARED with the invoice document (the quote mirrors the invoice acceptance idiom),
     * so they are NOT banned here. What stays banned is everything invoice-only: TAX INVOICE,
     * Invoice To, Payment Made, Balance Due, "Accepted by" (accepted-state caption), and the
     * invoice-specific declaration fragments "of this invoice" / "value shown on this invoice".
     * The recipient label is CSS-uppercased, so PDFBox extracts it as "QUOTATION TO".
     * UNSIGNED renders only — a Phase 16F PR1 signed render REQUIRES the accepted caption; use
     * {@link #assertSignedQuotationNotInvoice} for those.
     */
    private static void assertQuoteNotInvoice(String text) {
        Assertions.assertTrue(STANDALONE_QUOTATION.matcher(text).find(),
                () -> "missing standalone QUOTATION title in: " + text);
        Assertions.assertFalse(STANDALONE_QUOTE.matcher(text).find(),
                () -> "standalone QUOTE word must no longer appear: " + text);
        Assertions.assertTrue(text.contains("QUOTATION TO"), () -> "missing Quotation To block in: " + text);
        Assertions.assertFalse(text.contains("QUOTE TO"), () -> "stale Quote To block in: " + text);
        Assertions.assertFalse(text.contains("TAX INVOICE"), () -> "quote must not say TAX INVOICE: " + text);
        Assertions.assertFalse(text.toUpperCase().contains("INVOICE TO"), () -> "quote must not say Invoice To: " + text);
        Assertions.assertFalse(text.contains("Payment Made"), () -> "quote must not show Payment Made: " + text);
        Assertions.assertFalse(text.contains("Balance Due"), () -> "quote must not show Balance Due: " + text);
        Assertions.assertFalse(text.contains("Accepted by"), () -> "quote must not show invoice acceptance text: " + text);
        Assertions.assertFalse(text.contains("of this invoice"),
                () -> "invoice declaration wording leaked into the quote: " + text);
        Assertions.assertFalse(text.contains("value shown on this invoice"),
                () -> "invoice agreement wording leaked into the quote: " + text);
    }

    /**
     * Phase 16F PR1 — the SIGNED counterpart of {@link #assertQuoteNotInvoice}: the same
     * QUOTATION-not-invoice wording checks, except the accepted caption ("Accepted by ...") is now
     * REQUIRED exactly once (it is no longer invoice-only) and the blank "Customer signature"
     * caption must be gone (the signed cell replaces it). Expects whitespace-normalised text.
     */
    private static void assertSignedQuotationNotInvoice(String text) {
        Assertions.assertTrue(STANDALONE_QUOTATION.matcher(text).find(),
                () -> "signed render lost the standalone QUOTATION title: " + text);
        Assertions.assertFalse(STANDALONE_QUOTE.matcher(text).find(),
                () -> "standalone QUOTE word must not appear on the signed render: " + text);
        Assertions.assertTrue(text.contains("QUOTATION TO"), () -> "signed render lost Quotation To: " + text);
        Assertions.assertFalse(text.contains("QUOTE TO"), () -> "stale Quote To block in: " + text);
        Assertions.assertFalse(text.contains("TAX INVOICE"), () -> "signed quote must not say TAX INVOICE: " + text);
        Assertions.assertFalse(text.toUpperCase().contains("INVOICE TO"),
                () -> "signed quote must not say Invoice To: " + text);
        Assertions.assertFalse(text.contains("Payment Made"), () -> "signed quote must not show Payment Made: " + text);
        Assertions.assertFalse(text.contains("Balance Due"), () -> "signed quote must not show Balance Due: " + text);
        Assertions.assertFalse(text.contains("of this invoice"),
                () -> "invoice declaration wording leaked into the signed quote: " + text);
        Assertions.assertFalse(text.contains("value shown on this invoice"),
                () -> "invoice agreement wording leaked into the signed quote: " + text);
        Assertions.assertEquals(1, countOccurrences(text, "Accepted by"),
                () -> "signed quote must carry exactly one accepted caption: " + text);
        Assertions.assertFalse(text.contains("Customer signature"),
                () -> "the blank Customer signature caption must not render on a signed quote: " + text);
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
    void render_nonItemised_noLineTable_showsTotalsDepositAndAcceptance() throws IOException {
        // Phase 16D-C removed the filler "Quoted Works" block: a non-itemised quotation is
        // customer/details/totals/deposit/acceptance/terms — no line table and no fake section.
        M m = new M();
        m.itemised = false;
        m.lines = new ArrayList<>();
        m.quoteTotalExGst = bd("500.00");
        m.quoteTotalIncGst = bd("550.00");
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertQuoteNotInvoice(text);
        Assertions.assertFalse(text.toUpperCase().contains("QUOTED WORKS"),
                () -> "stale Quoted Works heading must be gone: " + text);
        Assertions.assertFalse(text.contains("A single quoted amount"),
                () -> "stale single-amount filler sentence must be gone: " + text);
        Assertions.assertFalse(text.contains("Qty"), () -> "non-itemised quote must not render the line-table header: " + text);
        Assertions.assertTrue(noSpace(text).contains("550.00"), () -> "missing inc-GST total in: " + text);
        Assertions.assertTrue(noSpace(text).contains("$220.00"),
                () -> "missing 40% deposit (550.00 x 0.40) in: " + text);
        Assertions.assertTrue(text.contains("CUSTOMER ACCEPTANCE"),
                () -> "missing acceptance section in: " + text);
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
        // Phase 16D-C: the deposit must come from the header total too (990.00 x 0.40), never from lines.
        Assertions.assertTrue(noSpace(text).contains("$396.00"),
                () -> "missing header-derived 40% deposit in: " + text);
    }

    // ------------------------------------------------------------------
    // Phase 16D-C — QUOTATION wording, 40% deposit, acceptance/signing area
    // ------------------------------------------------------------------

    @Test
    void render_titleAndRecipient_useQuotationWording() throws IOException {
        byte[] pdf = GENERATOR.render(new M().build());
        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(STANDALONE_QUOTATION.matcher(text).find(),
                () -> "missing standalone QUOTATION in: " + text);
        Assertions.assertFalse(STANDALONE_QUOTE.matcher(text).find(),
                () -> "standalone QUOTE word must no longer appear: " + text);
        // The recipient eyebrow is CSS-uppercased, so PDFBox extracts "QUOTATION TO".
        Assertions.assertTrue(text.contains("QUOTATION TO"), () -> "missing Quotation To label in: " + text);
        Assertions.assertFalse(text.contains("QUOTE TO"), () -> "stale Quote To label in: " + text);
        Assertions.assertFalse(text.toUpperCase().contains("QUOTED WORKS"),
                () -> "stale Quoted Works heading in: " + text);
        Assertions.assertFalse(text.contains("A single quoted amount"),
                () -> "stale single-amount filler sentence in: " + text);
    }

    @Test
    void render_itemised_showsDeposit40PercentOfIncTotal() throws IOException {
        // Default itemised model: inc total 244.20 -> deposit 244.20 x 0.40 = 97.68. The sentence
        // wraps the amount: "A deposit of $97.68 is required to proceed with this quotation."
        byte[] pdf = GENERATOR.render(new M().build());
        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("A deposit of"),
                () -> "missing deposit sentence lead in: " + text);
        Assertions.assertTrue(text.contains("is required to proceed with this quotation."),
                () -> "missing deposit sentence tail in: " + text);
        Assertions.assertTrue(noSpace(text).contains("$97.68"),
                () -> "missing 40% deposit amount (244.20 x 0.40) in: " + text);
    }

    @Test
    void render_deposit_roundsHalfUpTo2dp() throws IOException {
        // 100.99 x 0.40 = 40.396 -> HALF_UP 2dp -> $40.40 (a truncating implementation shows $40.39).
        M m = new M();
        m.itemised = false;
        m.lines = new ArrayList<>();
        m.quoteTotalExGst = bd("91.81");
        m.quoteTotalIncGst = bd("100.99");
        byte[] pdf = GENERATOR.render(m.build());
        String text = noSpace(extractText(pdf));
        Assertions.assertTrue(text.contains("$40.40"), () -> "missing HALF_UP-rounded deposit in: " + text);
        Assertions.assertFalse(text.contains("40.39"), () -> "deposit was truncated, not rounded HALF_UP: " + text);
    }

    @Test
    void render_acceptanceSection_rendersAcceptanceWordingAndBlankSignatureLine() throws IOException {
        // 16D-C fix round 2: the acceptance section mirrors the invoice's two-column idiom — the
        // invoice-tab wording adapted invoice-to-quotation on the left, one blank signature line with
        // the shared "Customer signature" caption on the right. No name/date fields, no accepted state.
        byte[] pdf = GENERATOR.render(new M().build());
        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertQuoteNotInvoice(text);

        // Section heading (.sec-h is CSS-uppercased on extraction) — deliberately the invoice heading.
        Assertions.assertTrue(text.contains("CUSTOMER ACCEPTANCE"),
                () -> "missing Customer Acceptance heading in: " + text);
        // The five acceptance lines, verbatim (sentence-case, never CSS-uppercased; ’ = curly
        // apostrophe, template entity &#8217;). The intro is compared whitespace-stripped because
        // PDFBox injects a stray space after the ’ glyph ("customer’ s") on extraction.
        Assertions.assertTrue(
                noSpace(text).contains(noSpace(
                        "Furniture removal and replacement, take up of old floor coverings, floor preparation "
                        + "and adjustment of door heights are the customer’s responsibility unless otherwise stated above.")),
                () -> "missing responsibility intro sentence in: " + text);
        Assertions.assertTrue(text.contains("I agree to pay the balance before the installation date."),
                () -> "missing balance checkbox line in: " + text);
        Assertions.assertTrue(
                text.contains("I agree that no floor preparation costs are included unless otherwise stated above."),
                () -> "missing floor-preparation checkbox line in: " + text);
        Assertions.assertTrue(
                text.contains("This agreement is for the sale and installation of the goods described above at the "
                        + "value shown on this quotation and upon the terms and conditions stated herein."),
                () -> "missing agreement sentence in: " + text);
        Assertions.assertTrue(text.contains("I accept the terms and conditions of this quotation."),
                () -> "missing acceptance sentence in: " + text);
        // Blank signing line caption (shared with the invoice); no accepted-state text ever.
        Assertions.assertTrue(text.contains("Customer signature"),
                () -> "missing Customer signature caption in: " + text);
        Assertions.assertFalse(text.contains("Accepted by"),
                () -> "blank quote acceptance must not show Accepted by: " + text);
    }

    @Test
    void render_footer_saysQuotation_withBusinessName() throws IOException {
        byte[] pdf = GENERATOR.render(new M().build());
        String text = extractText(pdf).replaceAll("\\s+", " ");
        // 16D-C fix round 5: the footer carries the business name ("Generated by <business name> ·
        // Quotation · GST included where applicable"). The name is an inline span, which PDFBox may
        // emit out of visual order, so the footer is asserted by its stable fragments; the
        // name-in-footer itself is proven by the logo test (where the header name is suppressed and
        // the footer is the only occurrence). · = the footer's middle dot (template entity &#183;).
        Assertions.assertTrue(text.contains("Generated by"), () -> "missing footer lead in: " + text);
        Assertions.assertTrue(text.contains("Quotation · GST included where applicable"),
                () -> "footer must carry the Quotation document word: " + text);
        Assertions.assertFalse(text.contains("the Flooring Sales Portal"),
                () -> "footer must use the business name, not the generic fallback: " + text);
        Assertions.assertEquals(1, countOccurrences(text, "GST included where applicable"),
                () -> "footer must render exactly once: " + text);
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
        // Count anchor is the stable footer tail (the lead now carries the tenant business name).
        Assertions.assertEquals(1, countOccurrences(text, "GST included where applicable"),
                () -> "footer must render exactly once when there are no terms: " + text);
    }

    @Test
    void render_withTerms_footerRendersExactlyOnceOnTermsPage() throws IOException {
        M m = new M();
        m.termsHtml = "<p>Some quote terms apply.</p>";
        byte[] pdf = GENERATOR.render(m.build());
        String text = extractText(pdf).replaceAll("\\s+", " ");
        // Count anchor is the stable footer tail (the lead now carries the tenant business name).
        Assertions.assertEquals(1, countOccurrences(text, "GST included where applicable"),
                () -> "footer must render exactly once when terms exist (no duplicate): " + text);
        Assertions.assertEquals(2, pageCount(pdf), "terms exist -> terms on a dedicated second page");
        String page2 = extractPageText(pdf, 2).replaceAll("\\s+", " ");
        Assertions.assertTrue(page2.contains("GST included where applicable"),
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
    void render_withLogoDataUri_embedsLogoImage_suppressesHeaderName_keepsAbnAndFooterName() throws IOException {
        // The user-facing demo scenario: tenant logo + ABN. The logo replaces the header business-name
        // text, but the ABN line under the logo and the business name in the footer must still render.
        M m = new M();
        m.logoDataUri = ONE_PIXEL_PNG_DATA_URI;
        m.abn = "11 222 333 444";
        byte[] pdf = GENERATOR.render(m.build());
        assertPdfHeader(pdf);
        Assertions.assertTrue(countImages(pdf) >= 1, "logo data URI must embed an image");
        String text = extractText(pdf).replaceAll("\\s+", " ");
        // Header mark suppressed -> the footer is the ONLY occurrence of the business name.
        Assertions.assertEquals(1, countOccurrences(text, "Aussie Floors Group"),
                () -> "with a logo the business name must appear exactly once (the footer): " + text);
        // ABN regression guard (16D-C fix round 5): label + value, whitespace-insensitive.
        Assertions.assertTrue(noSpace(text).contains("ABN11222333444"),
                () -> "missing ABN line under the logo: " + text);
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
        // ABN regression guard (16D-C fix round 5): assert the label AND the value render together.
        Assertions.assertTrue(noSpace(text).contains("ABN11222333444"),
                () -> "missing ABN label/value: " + text);
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

    // ------------------------------------------------------------------
    // Phase 16F PR1 — SIGNED quote PDF (remote acceptance)
    // ------------------------------------------------------------------

    @Test
    void render_signed_showsAcceptedCaptionOnce_keepsQuotationDepositTotals_addsSignatureImage() throws IOException {
        M m = new M();
        byte[] unsigned = GENERATOR.render(m.build());
        byte[] signed = GENERATOR.render(m.buildSigned(ACCEPTED_AT, "James Wilson", ONE_PIXEL_PNG));
        assertPdfHeader(signed);

        String text = extractText(signed).replaceAll("\\s+", " ");
        assertSignedQuotationNotInvoice(text);
        // ONE server-built caption node, so the name and zero-padded time stay in reading order.
        String caption = "Accepted by James Wilson on " + ACCEPTED_AT_DISPLAY;
        Assertions.assertEquals(1, countOccurrences(text, caption),
                () -> "expected exactly one '" + caption + "' caption in: " + text);
        Assertions.assertFalse(text.contains("09:05:42"), () -> "the caption must not show seconds: " + text);

        // Still the same QUOTATION body: deposit sentence (40% of 244.20 = 97.68) and the totals.
        Assertions.assertTrue(text.contains("A deposit of"), () -> "missing deposit sentence lead in: " + text);
        Assertions.assertTrue(text.contains("is required to proceed with this quotation."),
                () -> "missing deposit sentence tail in: " + text);
        Assertions.assertTrue(noSpace(text).contains("$97.68"), () -> "missing 40% deposit amount in: " + text);
        Assertions.assertTrue(noSpace(text).contains("Subtotal(exGST)$222.00"), () -> "missing subtotal in: " + text);
        Assertions.assertTrue(noSpace(text).contains("GST$22.20"), () -> "missing GST in: " + text);
        Assertions.assertTrue(noSpace(text).contains("Total(incGST)$244.20"), () -> "missing inc-GST total in: " + text);
        Assertions.assertTrue(text.contains("I accept the terms and conditions of this quotation."),
                () -> "missing acceptance sentence in: " + text);
        Assertions.assertEquals(1, countOccurrences(text, "GST included where applicable"),
                () -> "footer must still render exactly once on the signed quote: " + text);

        // The signature is the ONE extra embedded image (no logo on this model: 0 -> 1).
        Assertions.assertEquals(0, countImages(unsigned), "unsigned model without a logo embeds no image");
        Assertions.assertEquals(countImages(unsigned) + 1, countImages(signed),
                "the signed render must embed exactly one more image (the signature) than the unsigned render");
    }

    @Test
    void render_signed_ticksBothDeclarationSquares_twoExtraFilledSquaresInPage1ContentStream() throws IOException {
        // The ticks are pure CSS (an inner .chk-tick div with a background fill inside each bordered
        // square) and carry no glyph, so text extraction cannot see them. Technique: render the SAME
        // model unsigned and signed, tokenise each page 1 content stream with PDFBox's PDFStreamParser
        // (see pagePaint) and compare what is painted. openhtmltopdf paints every border/background as
        // a closed path + fill operator ('f'), so the signed page must carry at least two more fills,
        // and exactly two small filled squares (the 5pt ticks) where the unsigned page has none (its
        // empty squares' borders are thin 9pt x 1pt trapezoids, never small squares).
        M m = new M();
        PagePaint unsignedPaint = pagePaint(GENERATOR.render(m.build()), 1);
        PagePaint signedPaint = pagePaint(GENERATOR.render(m.buildSigned(ACCEPTED_AT, "James Wilson", ONE_PIXEL_PNG)), 1);

        Assertions.assertTrue(signedPaint.fills() - unsignedPaint.fills() >= 2,
                () -> "signed page 1 must paint at least two more filled shapes (the ticks): unsigned="
                        + unsignedPaint + " signed=" + signedPaint);
        Assertions.assertEquals(0, unsignedPaint.tickSquares(),
                () -> "unsigned declaration squares must stay empty (no tick fill): " + unsignedPaint);
        Assertions.assertEquals(2, signedPaint.tickSquares(),
                () -> "both declaration squares must be ticked on the signed render: " + signedPaint);
    }

    @Test
    void render_unsignedModel_from24ArgConstructor_keepsBlankAcceptanceArea() throws IOException {
        // Regression: the draft preview and the issued PDF build their model through the unsigned
        // 24-argument constructor. It must leave every acceptance field null and render the blank
        // pre-16F print-and-sign area: the "Customer signature" caption, no "Accepted by", no embedded
        // image (no logo here) and empty declaration squares.
        List<QuotePdfLine> lines = new ArrayList<>(List.of(item("Carpet", "2.00", "111.00", "222.00")));
        QuotePdfModel unsigned = new QuotePdfModel(
                "Aussie Floors Group", null, null, null,
                null, null, null, null, null,
                "SYD-CBD.LC1.00001", null, "James Wilson", "42 Oxford Street", "Paddington NSW 2021",
                "Supply and install plush carpet to lounge and dining rooms.",
                true, lines, bd("222.00"), bd("244.20"),
                null, null, null, null,
                null);
        Assertions.assertNull(unsigned.acceptedAt(), "24-arg constructor must leave acceptedAt null");
        Assertions.assertNull(unsigned.acceptedCustomerName(), "24-arg constructor must leave the accepted name null");
        Assertions.assertNull(unsigned.signaturePng(), "24-arg constructor must leave the signature null");
        Assertions.assertFalse(unsigned.accepted(), "a model without acceptedAt is not accepted");
        Assertions.assertEquals(new QuotePdfModel(
                        "Aussie Floors Group", null, null, null,
                        null, null, null, null, null,
                        "SYD-CBD.LC1.00001", null, "James Wilson", "42 Oxford Street", "Paddington NSW 2021",
                        "Supply and install plush carpet to lounge and dining rooms.",
                        true, lines, bd("222.00"), bd("244.20"),
                        null, null, null, null,
                        null,
                        null, null, null),
                unsigned, "the 24-arg constructor must equal the canonical one with null acceptance fields");

        byte[] pdf = GENERATOR.render(unsigned);
        assertPdfHeader(pdf);
        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertQuoteNotInvoice(text);
        Assertions.assertEquals(1, countOccurrences(text, "Customer signature"),
                () -> "the blank signature caption must render exactly once: " + text);
        Assertions.assertEquals(0, countImages(pdf), "unsigned render without a logo must embed no image");
        Assertions.assertEquals(0, pagePaint(pdf, 1).tickSquares(), "unsigned declaration squares must stay empty");

        // With a logo the unsigned image count is unchanged: exactly the logo.
        M withLogo = new M();
        withLogo.logoDataUri = ONE_PIXEL_PNG_DATA_URI;
        Assertions.assertEquals(1, countImages(GENERATOR.render(withLogo.build())),
                "an unsigned render with a logo must embed exactly the logo");
    }

    @Test
    void render_acceptedAtNull_ignoresStrayNameAndSignature_rendersUnsigned() throws IOException {
        // The signed state is driven SOLELY by acceptedAt: a model that carries a name + signature bytes
        // but no acceptedAt must still render the blank area — no signature can leak into an unsigned
        // (preview / issued) PDF.
        byte[] pdf = GENERATOR.render(new M().buildSigned(null, "Stray Name", ONE_PIXEL_PNG));
        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertQuoteNotInvoice(text);
        Assertions.assertTrue(text.contains("Customer signature"), () -> "blank caption must render: " + text);
        Assertions.assertFalse(text.contains("Stray Name"), () -> "a stray accepted name leaked into: " + text);
        Assertions.assertEquals(0, countImages(pdf), "a stray signature must not be embedded without acceptedAt");
        Assertions.assertEquals(0, pagePaint(pdf, 1).tickSquares(), "squares must stay empty without acceptedAt");
    }

    @Test
    void render_signed_withLogo_embedsSignatureAsSecondImageOnPage1() throws IOException {
        // The signature is embedded IN ADDITION to the tenant logo (both on page 1). TWO_BY_TWO_PNG keeps
        // the signature bytes distinct from the logo (identical data URIs share one XObject).
        M m = new M();
        m.logoDataUri = ONE_PIXEL_PNG_DATA_URI;
        byte[] unsigned = GENERATOR.render(m.build());
        byte[] signed = GENERATOR.render(m.buildSigned(ACCEPTED_AT, "James Wilson", TWO_BY_TWO_PNG));

        Assertions.assertEquals(1, countImagesOnPage(unsigned, 1), "unsigned page 1 embeds only the logo");
        Assertions.assertEquals(2, countImagesOnPage(signed, 1), "signed page 1 embeds the logo AND the signature");
        Assertions.assertEquals(countImages(unsigned) + 1, countImages(signed),
                "the signature is the only additional image");
        String text = extractText(signed).replaceAll("\\s+", " ");
        Assertions.assertEquals(1, countOccurrences(text, "Accepted by James Wilson on " + ACCEPTED_AT_DISPLAY),
                () -> "missing accepted caption in: " + text);
    }

    @Test
    void render_signed_withTerms_acceptanceOnPage1_termsAloneOnPage2() throws IOException {
        // The signed render keeps the locked layout: acceptance (caption, signature, ticks) on page 1,
        // the frozen terms on their own page 2, footer once.
        M m = new M();
        m.flooringTypeLabel = "Soft Flooring";
        m.termsHtml = "<p>Soft flooring stain warranty applies for twelve months.</p>";
        byte[] pdf = GENERATOR.render(m.buildSigned(ACCEPTED_AT, "James Wilson", ONE_PIXEL_PNG));
        assertPdfHeader(pdf);

        Assertions.assertEquals(2, pageCount(pdf), "signed quote with terms keeps the dedicated terms page");
        String page1 = extractPageText(pdf, 1).replaceAll("\\s+", " ");
        String page2 = extractPageText(pdf, 2).replaceAll("\\s+", " ");
        Assertions.assertTrue(page1.contains("Accepted by James Wilson on " + ACCEPTED_AT_DISPLAY),
                () -> "the accepted caption belongs on page 1: " + page1);
        Assertions.assertFalse(page1.contains("Soft flooring stain warranty"), () -> "page 1 must not carry terms: " + page1);
        Assertions.assertFalse(page1.contains("TERMS"), () -> "page 1 must not carry a terms heading: " + page1);
        Assertions.assertTrue(page2.contains("Soft flooring stain warranty"), () -> "page 2 must carry the terms: " + page2);
        Assertions.assertFalse(page2.contains("Accepted by"), () -> "page 2 must not repeat the caption: " + page2);
        Assertions.assertEquals(1, countImagesOnPage(pdf, 1), "the signature image is on page 1");
        Assertions.assertEquals(0, countImagesOnPage(pdf, 2), "the terms page embeds no image");
        Assertions.assertEquals(2, pagePaint(pdf, 1).tickSquares(), "both ticks render on page 1");
        String all = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertEquals(1, countOccurrences(all, "GST included where applicable"),
                () -> "footer must render exactly once on a signed quote with terms: " + all);
    }

    @Test
    void render_signed_longAcceptedName_over150Chars_rendersInFull() throws IOException {
        // V19 widened accepted_customer_name to TEXT: the frozen name is never truncated. The long
        // name wraps across several caption lines, so the check is whitespace-stripped containment
        // of the WHOLE caption (prefix + full name + time, in order).
        String longName = "Alexandria Catherine Montgomery-Fitzwilliam Beauchamp-Wellesley Pemberton-Ashworth "
                + "Worthington-Fairfax Kensington-Huntingdon Carrington-Delacourt Ravensworth-Ellingham Stratford";
        Assertions.assertTrue(longName.length() > 150, "fixture must exceed the old VARCHAR(150) cap");

        byte[] pdf = GENERATOR.render(new M().buildSigned(ACCEPTED_AT, longName, ONE_PIXEL_PNG));
        assertPdfHeader(pdf);
        String text = extractText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(
                noSpace(text).contains(noSpace("Accepted by " + longName + " on " + ACCEPTED_AT_DISPLAY)),
                () -> "the long accepted name must render in full inside the caption: " + text);
        Assertions.assertEquals(1, countOccurrences(text, "Accepted by"),
                () -> "exactly one accepted caption expected: " + text);
    }

    @Test
    void render_signedNonItemised_ignoresLinesPassedIn_rendersHeaderTotalsAndCaption() throws IOException {
        // The line table is gated on the itemised flag alone: a signed NON-itemised model renders no line
        // table and none of the lines handed to it — details + header totals + deposit + acceptance only.
        M m = new M();
        m.itemised = false;
        m.lines = new ArrayList<>(List.of(
                item("Dormant carpet row", "2.00", "100.00", "200.00"),
                adjustment("Dormant discount", "-30.00")));
        m.quoteTotalExGst = bd("900.00");
        m.quoteTotalIncGst = bd("990.00");
        byte[] pdf = GENERATOR.render(m.buildSigned(ACCEPTED_AT, "James Wilson", ONE_PIXEL_PNG));
        assertPdfHeader(pdf);

        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertSignedQuotationNotInvoice(text);
        Assertions.assertFalse(text.contains("Qty"), () -> "signed non-itemised quote rendered a line table: " + text);
        Assertions.assertFalse(text.contains("Dormant carpet row"), () -> "ITEM line leaked into the signed PDF: " + text);
        Assertions.assertFalse(text.contains("Dormant discount"), () -> "ADJUSTMENT line leaked into the signed PDF: " + text);
        Assertions.assertFalse(noSpace(text).contains("200.00"), () -> "line amount leaked into the signed PDF: " + text);
        Assertions.assertTrue(noSpace(text).contains("990.00"), () -> "missing header inc-GST total in: " + text);
        Assertions.assertTrue(noSpace(text).contains("900.00"), () -> "missing header ex-GST subtotal in: " + text);
        Assertions.assertTrue(noSpace(text).contains("$396.00"), () -> "missing header-derived 40% deposit in: " + text);
        Assertions.assertTrue(text.contains("Supply and install plush carpet"), () -> "missing details of sale in: " + text);
        Assertions.assertEquals(1, countOccurrences(text, "Accepted by James Wilson on " + ACCEPTED_AT_DISPLAY),
                () -> "missing accepted caption in: " + text);
    }
}
