package com.flooring.salesportal.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 16G PR 2 (#125): every Path B invoice version STORES and RENDERS its own words, namely the
 * Invoice To name, both billing lines and the terms ({@code invoice.customer_name_snapshot},
 * {@code customer_address_line1_snapshot}, {@code customer_address_line2_snapshot} and
 * {@code terms_snapshot}). Authority: docs/API-Contracts-Phase16G-Invoice-Link.md section 4 and the
 * PR 2 row of section 16.
 *
 * <p><b>Routes covered (Path B only).</b> D.1 Create and D.2 Rewrite FREEZE the words from the saved
 * customer, the saved BILLING address (the V17 derivation, blank or missing stored as NULL, never
 * {@code ""}) and the business's per-flooring-type terms, sanitised once. D.7 payment, D.10 void and
 * D.8 in-store accept COPY the current version's words verbatim, nulls included. D.3 and D.9 return
 * the stored words in InvoiceDetail, and D.4 streams the stored PDF. Path A (requirement 3) and the
 * V20 migration and backfill (requirement 6) are covered by other classes.
 *
 * <p><b>Why these tests fail on the pre-PR-2 code.</b> Before PR 2 every render read the Invoice To
 * name and billing lines LIVE, every Path B render and InvoiceDetail read the business's terms LIVE,
 * {@code terms_source} was {@code LIVE}, D.1 and D.2 wrote {@code terms_snapshot} NULL, and the three
 * identity columns and InvoiceDetail keys did not exist. Each test therefore either (a) moves the
 * saved state on after a version is written (a rename, a billing move, a terms edit, terms set where
 * there were none) and proves that the version and every later copy still show the earlier words, or
 * (b) plants stored words by SQL that differ from the live state and proves that the copies carry the
 * stored words and never the live ones. A live-reading implementation fails both. Every InvoiceDetail
 * response is also checked for the exact PR 2 key set and {@code terms_source} {@code BUSINESS}, which
 * the old code fails too. Each test's Javadoc names its own discriminating assertions.
 *
 * <p><b>Fixtures (self-seeded, Phase 14D rule).</b> {@link #setUp} inserts, inside the test
 * transaction, a fresh tenant: a business (slug {@value #SLUG}) with an ABN, bank details, raw SOFT
 * and HARD terms and a legacy {@code terms_and_conditions} block that must never be used; one store;
 * one salesperson; and the user-store access row. Each test inserts its own invoice-ready order:
 * customer, INSTALLATION and BILLING addresses, details of sale, a CONFIRMED lay date and one priced
 * charge line of 100.00 ex GST (110.00 inc). No V4 business, store, user or order is read or written.
 * The protected endpoints are called with a MockHttpSession holding the seeded ids under the seeded
 * slug. Raw SQL is used only to seed, to move saved state on, to set business terms and to plant
 * stored words on the current version, and each such write is followed by a persistence-context
 * clear, so the next request reads the database.
 *
 * <p>Everything rolls back with the test transaction. The PDFs are written under
 * {@value #STORAGE_BASE_DIR} and read back from disk through their {@code stored_file} row; the
 * services' rollback hooks delete them when the transaction rolls back. The recording email senders
 * are singletons whose state survives the rollback, so all three are reset before and after each test.
 */
@ExtendWith(SpringExtension.class)
// The SAME storage property as the other invoice/quote suites, so this class joins their cached Spring
// context (each distinct property set starts another context with its own connection pool). Files stay
// apart: every PDF lives under this test's own business and order directories.
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class InvoiceFrozenWordsTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant (never a V4 identity) ----
    private static final String SLUG = "invoice-frozen-words-test";
    private static final String BUSINESS_NAME = "Kestrel Floor Studio";
    private static final String STORE_CODE = "KST-ONE";
    private static final String SALESPERSON_CODE = "PV1";
    private static final String CUSTOMER_EMAIL = "jordan.wilson@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and lay flooring (Kestrel invoice words test)";

    private static final String SOFT = "SOFT";
    private static final String HARD = "HARD";

    // ---- Saved identities, raw as the order_customer and BILLING order_address rows hold them ----

    /** Padded first and last names, a middle name and a padded unit number. */
    private static final SavedIdentity ORIGINAL = new SavedIdentity(
            "  Jordan ", "Lee", " Wilson  ", " 4 ", "12", "Oxford Street", "Paddington", "NSW", "2021");
    private static final String ORIGINAL_NAME = "Jordan Lee Wilson";
    private static final String ORIGINAL_LINE1 = "4/12 Oxford Street";
    private static final String ORIGINAL_LINE2 = "Paddington NSW 2021";

    /**
     * A whitespace-only middle name, no unit number, and an edge-padded street number and postcode (the
     * schema allows them): only the whole-line trim of the freeze turns them into clean lines.
     */
    private static final SavedIdentity BLANK_MIDDLE_NO_UNIT = new SavedIdentity(
            "Avery", "   ", "Stone", null, " 12", "Oxford Street", "Paddington", "NSW", "2021 ");
    private static final String BLANK_MIDDLE_NAME = "Avery Stone";
    private static final String NO_UNIT_LINE1 = "12 Oxford Street";

    /** What the saved state moves on to after a version is written: a rename and a billing move. */
    private static final SavedIdentity EDITED = new SavedIdentity(
            "Morgan", "Quinn", "Hale", null, "88", "Harbour Road", "Manly", "NSW", "2095");
    private static final String EDITED_NAME = "Morgan Quinn Hale";
    private static final String EDITED_LINE1 = "88 Harbour Road";
    private static final String EDITED_LINE2 = "Manly NSW 2095";

    // ---- Business terms. Each raw value carries markup the sanitizer strips, so a stored value
    //      equal to sanitize(raw), and unequal to raw, proves the freeze sanitised it. ----
    private static final String SOFT_TERMS_TEXT = "Soft terms frozen FWS1.";
    private static final String RAW_SOFT_TERMS =
            "<p style=\"color:red\" onclick=\"steal()\">" + SOFT_TERMS_TEXT + "</p><script>alert(1)</script>";
    private static final String HARD_TERMS_TEXT = "Hard terms frozen FWH1.";
    private static final String RAW_HARD_TERMS =
            "<ul><li id=\"h1\" onclick=\"steal()\">" + HARD_TERMS_TEXT + "</li></ul>";
    private static final String EDITED_SOFT_TERMS_TEXT = "Soft terms edited FWS2.";
    private static final String RAW_EDITED_SOFT_TERMS =
            "<p class=\"t\" onmouseover=\"steal()\">" + EDITED_SOFT_TERMS_TEXT + "</p>";
    private static final String EDITED_HARD_TERMS_TEXT = "Hard terms edited FWH2.";
    private static final String RAW_EDITED_HARD_TERMS =
            "<div id=\"e\"><strong>" + EDITED_HARD_TERMS_TEXT + "</strong></div>";
    /** The legacy single terms block: PR 2 never falls back to it. */
    private static final String LEGACY_TERMS_TEXT = "Legacy terms FWL9.";
    private static final String LEGACY_TERMS = "<p>" + LEGACY_TERMS_TEXT + "</p>";
    /** The frozen terms of the bare quote version used as a source in the Rewrite test. */
    private static final String QUOTE_TERMS_TEXT = "Quote terms FWQ1.";
    private static final String QUOTE_TERMS = "<p>" + QUOTE_TERMS_TEXT + "</p>";

    // ---- Stored words planted by SQL: values no fresh freeze of the saved state would produce ----
    private static final String STORED_TERMS_TEXT = "Stored terms PR2";
    /** Not sanitizer output (id is off the safelist), with leading spaces and a trailing newline. */
    private static final String STORED_TERMS = "  <p id=\"k\" class=\"x\">" + STORED_TERMS_TEXT + "</p>\n";
    /**
     * Edge padding and a double inner space: a fresh freeze trims and single-spaces, so only a VERBATIM copy
     * keeps these exact strings (a trim or blankToNull applied to a copied word would change them).
     */
    private static final String STORED_NAME = "  Casey  Storedname ";
    private static final String STORED_LINE1 = " 21  Snapshot Avenue  ";

    /** The D.8 name part in the legacy test: neither the live nor any stored name. */
    private static final String COUNTER_SIGNER = "Dana Countersign";

    // ---- PDF text ----
    private static final String FOOTER = "Generated by the Flooring Sales Portal";
    /** The "Terms &amp; Conditions" heading renders uppercase (CSS); page 1 only says "terms" in lowercase. */
    private static final String TERMS_HEADING = "TERMS";
    /** The Invoice To eyebrow and the eyebrow of the next column ("Issued / Due"), uppercase by CSS. */
    private static final String INVOICE_TO_HEADING = "INVOICE TO";
    private static final String NEXT_COLUMN_HEADING = "ISSUED";
    private static final String ACCEPTED_CAPTION = "Accepted by";
    /** The signed-caption timestamp pattern (InvoicePdfGenerator). */
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    // ---- Response contracts ----

    /** E.2 InvoiceDetail: every key ALWAYS serialized, including the three PR 2 identity keys. */
    private static final Set<String> INVOICE_DETAIL_KEYS = Set.of(
            "invoice_id", "order_id", "version_number", "invoice_date", "due_date", "details_of_sale_snapshot",
            "sale_price_ex_gst", "sale_price_inc_gst", "total_paid", "balance_due", "created_by_user_id",
            "created_at", "pdf_download_path",
            "customer_name_snapshot", "customer_address_line1_snapshot", "customer_address_line2_snapshot",
            "accepted_at", "accepted_customer_name", "accepted_signature_present",
            "accepted_signature_download_path", "last_emailed_at", "terms_html", "terms_source");

    /** Internal provenance and storage values that no InvoiceDetail body may contain. */
    private static final List<String> DETAIL_LEAK_MARKERS = List.of(
            "source_quote_version_id", "terms_snapshot", "stored_file_id", "accepted_signature_file_id",
            "storage_path", "/uploads/");

    /** E.1 current_invoice_summary (payment and void responses): UNCHANGED by PR 2. */
    private static final Set<String> CURRENT_INVOICE_SUMMARY_KEYS = Set.of(
            "invoice_id", "version_number", "invoice_date", "due_date", "sale_price_inc_gst",
            "total_paid", "balance_due", "created_by_user_id", "created_at", "pdf_download_path",
            "accepted_at", "accepted_customer_name", "accepted_signature_present",
            "accepted_signature_download_path", "last_emailed_at");

    /**
     * Never in an E.1 body: a word key, a terms key, a provenance or storage value, and no Invoice To
     * VALUE (every billing line of this class and the planted name). Customer names are deliberately not
     * listed: E.1 carries {@code accepted_customer_name}, which legitimately holds the submitted name.
     */
    private static final List<String> SUMMARY_ABSENT_MARKERS = List.of(
            "customer_name_snapshot", "customer_address_line1_snapshot", "customer_address_line2_snapshot",
            "terms_html", "terms_source", "terms_snapshot", "source_quote_version_id", "storage_path",
            "/uploads/", STORED_NAME.trim(), ORIGINAL_LINE1, ORIGINAL_LINE2, NO_UNIT_LINE1, EDITED_LINE1, EDITED_LINE2,
            STORED_LINE1.trim());

    private static final String CREATED_MESSAGE = "Invoice created.";
    private static final String REWRITTEN_MESSAGE = "Invoice rewritten.";
    private static final String ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String RESEND_MESSAGE = "Invoice re-sent to the customer.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String PAYMENT_VOIDED_MESSAGE = "Payment voided. Current invoice updated.";

    // A real, decodable 1x1 PNG: D.8 embeds the uploaded bytes in the signed PDF.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender acceptanceNotificationSender;

    /** The production sanitizer: a frozen business term must equal exactly what it makes of the raw value. */
    @Autowired
    private InvoiceTermsSanitizer termsSanitizer;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    // The seeded tenant of the running test.
    private long businessId;
    private int storeId;
    private long userId;
    private int orderSequence;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resetSenders();
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir, "this class reads its PDFs under the shared invoice/quote storage dir");
        seedTenant();
    }

    @AfterEach
    void tearDown() {
        // Singletons: never leak recorded messages or an armed failNextSend into another test or class.
        resetSenders();
    }

    // ================================================================
    // Requirement 1 - D.1 Create and D.2 Rewrite freeze the words
    // ================================================================

    /**
     * D.1 Create on a SOFT order freezes, once, the saved customer's Invoice To name, both BILLING lines
     * and the business's SOFT terms through the sanitizer. The saved customer exercises the V17
     * derivation: padded first and last names are trimmed, a present middle name sits between them
     * ("Jordan Lee Wilson"), and a padded unit gives "4/12 Oxford Street"; line 2 is "Paddington NSW
     * 2021". The same words are in the row (with no source quote version), in the D.1 and D.3
     * InvoiceDetail, and in the stored PDF: Invoice To on page 1 holding exactly them, and the SOFT terms
     * alone on page 2 (never the HARD terms or the legacy block).
     *
     * <p>Fails before PR 2: the identity columns and keys did not exist, {@code terms_snapshot} was
     * written NULL and {@code terms_source} was LIVE.
     */
    @Test
    void create_softOrder_freezesTheSavedNameBillingLinesAndSanitisedSoftTerms() throws Exception {
        long orderId = invoiceReadyOrder(SOFT, ORIGINAL);
        Words frozen = new Words(ORIGINAL_NAME, ORIGINAL_LINE1, ORIGINAL_LINE2, sanitisedTerms(RAW_SOFT_TERMS));

        assertDetailWords(create(orderId), 1, frozen, "D.1 SOFT");

        long v1 = invoiceId(orderId, 1);
        Assertions.assertEquals(frozen, storedWords(v1), "D.1 SOFT row: the frozen words");
        Assertions.assertNull(invoiceRow(v1).get("source_quote_version_id"), "D.1 SOFT row: no source quote version");
        assertPdfWords(pdfOnDisk(v1), frozen, SOFT_TERMS_TEXT, "D.1 SOFT PDF", HARD_TERMS_TEXT, LEGACY_TERMS_TEXT);
        assertDetailWords(getCurrent(orderId), 1, frozen, "D.3 after D.1 SOFT");
        assertNoBlankWordStored(orderId);
    }

    /**
     * D.1 Create on a HARD order freezes sanitize(terms_hard), never the SOFT terms or the legacy block.
     * A whitespace-only middle name is skipped ("Avery Stone", one space) and a BILLING address with no
     * unit gives "12 Oxford Street" (no "/" prefix); its padded street number (" 12") and postcode
     * ("2021 ") are trimmed with the whole line, as the V17 rule does. Row, D.1 and D.3 InvoiceDetail and
     * PDF agree.
     *
     * <p>Fails before PR 2: as the SOFT test (no identity columns or keys, NULL {@code terms_snapshot},
     * LIVE).
     */
    @Test
    void create_hardOrder_freezesHardTerms_skipsABlankMiddleName_andAddsNoUnitPrefix() throws Exception {
        long orderId = invoiceReadyOrder(HARD, BLANK_MIDDLE_NO_UNIT);
        Words frozen = new Words(BLANK_MIDDLE_NAME, NO_UNIT_LINE1, ORIGINAL_LINE2, sanitisedTerms(RAW_HARD_TERMS));

        assertDetailWords(create(orderId), 1, frozen, "D.1 HARD");

        long v1 = invoiceId(orderId, 1);
        Assertions.assertEquals(frozen, storedWords(v1), "D.1 HARD row: the frozen words");
        Assertions.assertNull(invoiceRow(v1).get("source_quote_version_id"), "D.1 HARD row: no source quote version");
        assertPdfWords(pdfOnDisk(v1), frozen, HARD_TERMS_TEXT, "D.1 HARD PDF", SOFT_TERMS_TEXT, LEGACY_TERMS_TEXT);
        assertDetailWords(getCurrent(orderId), 1, frozen, "D.3 after D.1 HARD");
        assertNoBlankWordStored(orderId);
    }

    /**
     * D.2 Rewrite freezes the EDITED saved state. The current version (v2) is signed in store (D.8) and
     * also carries a source quote version, set by SQL as a Path A version would carry one. Then the
     * customer is renamed, the BILLING address moves and the business edits its terms. Rewrite (v3)
     * stores "Morgan Quinn Hale" / "88 Harbour Road" / "Manly NSW 2095" and sanitize(edited terms_soft),
     * clears the acceptance (accepted_at, accepted name, signature) and the source, and reports
     * BUSINESS. Its PDF shows only the edited words with no acceptance caption. v1 and v2 keep their
     * whole rows and their stored PDF bytes.
     *
     * <p>Fails before PR 2: v3's {@code terms_snapshot} was written NULL, the identity columns and keys
     * did not exist, and {@code terms_source} was LIVE.
     */
    @Test
    void rewrite_freezesTheEditedSavedValuesAndEditedTerms_clearsAcceptanceAndSource_leavesEarlierVersionsUntouched()
            throws Exception {
        long orderId = invoiceReadyOrder(SOFT, ORIGINAL);
        Words original = new Words(ORIGINAL_NAME, ORIGINAL_LINE1, ORIGINAL_LINE2, sanitisedTerms(RAW_SOFT_TERMS));
        create(orderId);
        acceptInStore(orderId, ORIGINAL_NAME);
        long v1 = invoiceId(orderId, 1);
        long v2 = invoiceId(orderId, 2);
        long quoteVersionId = insertAcceptedQuoteVersion(orderId);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET source_quote_version_id = ? WHERE invoice_id = ?", quoteVersionId, v2));
        clearJpaCache();

        // Precondition: the current version is signed and quote-sourced, and carries v1's words.
        JsonNode beforeRewrite = getCurrent(orderId);
        Assertions.assertEquals("QUOTE", beforeRewrite.path("terms_source").asText(), "precondition: v2 is quote-sourced");
        Assertions.assertTrue(beforeRewrite.path("accepted_signature_present").asBoolean(), "precondition: v2 is signed");
        Assertions.assertNotNull(invoiceRow(v2).get("accepted_signature_file_id"), "precondition: v2 has a signature file");
        Assertions.assertEquals(original, storedWords(v2), "precondition: D.8 copied v1's words");
        Map<String, Object> v1Row = invoiceRow(v1);
        Map<String, Object> v2Row = invoiceRow(v2);
        byte[] v1Pdf = pdfOnDisk(v1);
        byte[] v2Pdf = pdfOnDisk(v2);

        moveSavedStateOn(orderId);
        Words edited = new Words(EDITED_NAME, EDITED_LINE1, EDITED_LINE2, sanitisedTerms(RAW_EDITED_SOFT_TERMS));

        JsonNode rewritten = rewrite(orderId);

        assertDetailWords(rewritten, 3, edited, "D.2");
        Assertions.assertTrue(rewritten.path("accepted_at").isNull(), "D.2 clears accepted_at");
        Assertions.assertTrue(rewritten.path("accepted_customer_name").isNull(), "D.2 clears the accepted name");
        Assertions.assertFalse(rewritten.path("accepted_signature_present").asBoolean(), "D.2 clears the signature");
        Assertions.assertTrue(rewritten.path("accepted_signature_download_path").isNull(), "D.2: no signature path");
        long v3 = invoiceId(orderId, 3);
        Assertions.assertEquals(edited, storedWords(v3), "D.2 row: the edited saved words and the edited terms");
        Map<String, Object> v3Row = invoiceRow(v3);
        for (String cleared : List.of("source_quote_version_id", "accepted_at", "accepted_customer_name",
                "accepted_signature_file_id")) {
            Assertions.assertNull(v3Row.get(cleared), () -> "D.2 row: " + cleared + " must be NULL");
        }
        assertPdfWords(pdfOnDisk(v3), edited, EDITED_SOFT_TERMS_TEXT, "D.2 PDF",
                ORIGINAL_NAME, "Oxford Street", "Paddington", SOFT_TERMS_TEXT, QUOTE_TERMS_TEXT,
                EDITED_HARD_TERMS_TEXT, ACCEPTED_CAPTION);

        // Append-only: the earlier versions keep their whole rows and their stored PDFs.
        Assertions.assertEquals(v1Row, invoiceRow(v1), "v1 row unchanged by the rewrite");
        Assertions.assertEquals(v2Row, invoiceRow(v2), "v2 row unchanged by the rewrite");
        Assertions.assertArrayEquals(v1Pdf, pdfOnDisk(v1), "v1 stored PDF unchanged");
        Assertions.assertArrayEquals(v2Pdf, pdfOnDisk(v2), "v2 stored PDF unchanged");
        assertDetailWords(getCurrent(orderId), 3, edited, "D.3 after D.2");
        Assertions.assertEquals(3, invoiceCount(orderId), "three versions");
        assertNoBlankWordStored(orderId);
    }

    // ================================================================
    // Requirement 2 - later saved-state changes never reach a written version or its copies
    // ================================================================

    /**
     * UNSIGNED flow. After D.1 freezes v1, the customer is renamed, the BILLING address moves and the
     * business edits both terms. D.3 still returns v1's words, v1's row is unchanged, v1's stored PDF
     * bytes are unchanged, and D.4 streams exactly those bytes. The next payment (v2) and its void (v3)
     * change the money but copy v1's words into their rows and render them (Invoice To and terms), and
     * none of the moved-on name, billing lines or terms appears in either PDF.
     *
     * <p>Fails before PR 2: D.3 returned the edited terms, and the payment and void PDFs rendered the
     * renamed customer, the new billing lines and the edited terms.
     */
    @Test
    void savedStateMovesOnAfterCreate_d3StoredPdfAndD4KeepV1_andTheUnsignedPaymentAndVoidCopyV1sWords()
            throws Exception {
        long orderId = invoiceReadyOrder(SOFT, ORIGINAL);
        Words frozen = new Words(ORIGINAL_NAME, ORIGINAL_LINE1, ORIGINAL_LINE2, sanitisedTerms(RAW_SOFT_TERMS));
        create(orderId);
        long v1 = invoiceId(orderId, 1);
        Map<String, Object> v1Row = invoiceRow(v1);
        byte[] v1Pdf = pdfOnDisk(v1);
        assertPdfWords(v1Pdf, frozen, SOFT_TERMS_TEXT, "D.1 PDF");

        moveSavedStateOn(orderId);

        assertDetailWords(getCurrent(orderId), 1, frozen, "D.3 after the edits");
        Assertions.assertEquals(v1Row, invoiceRow(v1), "v1 row unchanged by the edits");
        Assertions.assertArrayEquals(v1Pdf, pdfOnDisk(v1), "v1 stored PDF unchanged by the edits");
        Assertions.assertArrayEquals(v1Pdf, downloadCurrentFile(orderId), "D.4 streams v1's stored bytes");

        long paymentId = recordCashPayment(orderId, "30.00", 2);
        long v2 = invoiceId(orderId, 2);
        Assertions.assertEquals(frozen, storedWords(v2), "D.7 v2 row copies v1's words");
        assertMoney("30.00", invoiceRow(v2).get("total_paid"), "D.7 v2 total_paid");
        assertPdfWords(pdfOnDisk(v2), frozen, SOFT_TERMS_TEXT, "D.7 v2 PDF",
                EDITED_NAME, EDITED_LINE1, EDITED_LINE2, EDITED_SOFT_TERMS_TEXT, EDITED_HARD_TERMS_TEXT,
                ACCEPTED_CAPTION);

        voidPayment(orderId, paymentId, 3);
        long v3 = invoiceId(orderId, 3);
        Assertions.assertEquals(frozen, storedWords(v3), "D.10 v3 row copies v2's words");
        assertMoney("0.00", invoiceRow(v3).get("total_paid"), "D.10 v3 total_paid");
        byte[] v3Pdf = pdfOnDisk(v3);
        assertPdfWords(v3Pdf, frozen, SOFT_TERMS_TEXT, "D.10 v3 PDF",
                EDITED_NAME, EDITED_LINE1, EDITED_LINE2, EDITED_SOFT_TERMS_TEXT, EDITED_HARD_TERMS_TEXT,
                ACCEPTED_CAPTION);

        assertDetailWords(getCurrent(orderId), 3, frozen, "D.3 after the void");
        Assertions.assertArrayEquals(v3Pdf, downloadCurrentFile(orderId), "D.4 streams the current version's stored bytes");
        Assertions.assertArrayEquals(v1Pdf, pdfOnDisk(v1), "v1 stored PDF still unchanged");
        Assertions.assertEquals(v1Row, invoiceRow(v1), "v1 row still unchanged");
        Assertions.assertEquals(3, invoiceCount(orderId), "three versions");
        assertNoBlankWordStored(orderId);
    }

    /**
     * SIGNED flow, HARD order. After D.1 freezes v1, the saved state moves on as in the unsigned test.
     * D.8 (v2) submits the renamed name, as the Invoice tab does today: its accepted name and its PDF
     * caption ("Accepted by Morgan Quinn Hale on ...") follow the SUBMITTED part (unchanged until PR 3),
     * while its row, its InvoiceDetail and its PDF keep v1's Invoice To and HARD terms, and the emailed
     * PDF is that stored PDF. The signed payment (v3) and void (v4) carry the acceptance and copy the
     * same words; no PDF shows the new billing lines or the edited terms, and Invoice To never shows the
     * renamed customer.
     *
     * <p>Fails before PR 2: the D.8, payment and void PDFs rendered the renamed customer and new billing
     * lines in Invoice To and the edited HARD terms on page 2; D.8 returned LIVE terms.
     */
    @Test
    void savedStateMovesOnAfterCreate_d8AndTheSignedPaymentAndVoidCopyV1sWords_captionFollowsTheSubmittedName()
            throws Exception {
        long orderId = invoiceReadyOrder(HARD, ORIGINAL);
        Words frozen = new Words(ORIGINAL_NAME, ORIGINAL_LINE1, ORIGINAL_LINE2, sanitisedTerms(RAW_HARD_TERMS));
        create(orderId);
        moveSavedStateOn(orderId);

        JsonNode accepted = acceptInStore(orderId, EDITED_NAME);

        assertDetailWords(accepted, 2, frozen, "D.8");
        long v2 = invoiceId(orderId, 2);
        Map<String, Object> v2Row = invoiceRow(v2);
        Assertions.assertEquals(frozen, storedWords(v2), "D.8 v2 row copies v1's words");
        Assertions.assertEquals(EDITED_NAME, v2Row.get("accepted_customer_name"), "D.8 v2: the submitted accepted name");
        LocalDateTime acceptedAt = ((Timestamp) v2Row.get("accepted_at")).toLocalDateTime();
        byte[] v2Pdf = pdfOnDisk(v2);
        assertPdfWords(v2Pdf, frozen, HARD_TERMS_TEXT, "D.8 v2 PDF",
                EDITED_LINE1, EDITED_LINE2, EDITED_SOFT_TERMS_TEXT, EDITED_HARD_TERMS_TEXT);
        assertAcceptedCaption(v2Pdf, EDITED_NAME, acceptedAt, "D.8 v2 PDF");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "D.8 emails the signed invoice once");
        Assertions.assertEquals(2, sent.get(0).invoiceVersionNumber(), "D.8 emails v2");
        Assertions.assertArrayEquals(v2Pdf, sent.get(0).pdfBytes(), "the emailed PDF is v2's stored PDF");

        long paymentId = recordCashPayment(orderId, "30.00", 3);
        long v3 = invoiceId(orderId, 3);
        Assertions.assertEquals(frozen, storedWords(v3), "signed D.7 v3 row copies v2's words");
        assertCarriesAcceptance(v2Row, invoiceRow(v3), "signed D.7 v3");
        byte[] v3Pdf = pdfOnDisk(v3);
        assertPdfWords(v3Pdf, frozen, HARD_TERMS_TEXT, "signed D.7 v3 PDF",
                EDITED_LINE1, EDITED_LINE2, EDITED_SOFT_TERMS_TEXT, EDITED_HARD_TERMS_TEXT);
        assertAcceptedCaption(v3Pdf, EDITED_NAME, acceptedAt, "signed D.7 v3 PDF");

        voidPayment(orderId, paymentId, 4);
        long v4 = invoiceId(orderId, 4);
        Assertions.assertEquals(frozen, storedWords(v4), "signed D.10 v4 row copies v3's words");
        assertCarriesAcceptance(v2Row, invoiceRow(v4), "signed D.10 v4");
        byte[] v4Pdf = pdfOnDisk(v4);
        assertPdfWords(v4Pdf, frozen, HARD_TERMS_TEXT, "signed D.10 v4 PDF",
                EDITED_LINE1, EDITED_LINE2, EDITED_SOFT_TERMS_TEXT, EDITED_HARD_TERMS_TEXT);
        assertAcceptedCaption(v4Pdf, EDITED_NAME, acceptedAt, "signed D.10 v4 PDF");

        assertDetailWords(getCurrent(orderId), 4, frozen, "D.3 after the signed void");
        Assertions.assertEquals(4, invoiceCount(orderId), "four versions");
        Assertions.assertEquals(1, invoiceEmailSender.sentEmails().size(), "payment and void never email");
        assertNoBlankWordStored(orderId);
    }

    /**
     * Copies are EXACT. The current version's stored words are set by SQL to values no fresh freeze of
     * the saved state would produce: a name and a line 1 with edge padding and a double inner space (a
     * trim or blankToNull applied to a copy would change them), a NULL line 2 while
     * the saved BILLING row has a suburb, and terms that are not sanitizer output (an {@code id}
     * attribute, leading spaces, a trailing newline; the fixture first proves sanitize() would change
     * them). D.3, the payment (v2), the void (v3), D.8 (v4) and D.9 return or store exactly those
     * strings and that null; the PDFs render them (Invoice To holds only the stored name and line 1,
     * page 2 the stored terms) and never the saved customer's billing lines or the business's current
     * terms. D.8's caption shows its submitted name; D.9 re-sends v4's stored PDF.
     *
     * <p>Fails before PR 2: D.3 and D.8 returned the business's live sanitised terms, and the copy PDFs
     * rendered the live customer, live billing lines and live terms.
     */
    @Test
    void paymentVoidAndD8_copyTheStoredWordsExactly_includingANullLineAndTermsASanitiserWouldRewrite()
            throws Exception {
        Assertions.assertNotEquals(STORED_TERMS, termsSanitizer.sanitize(STORED_TERMS),
                "fixture: sanitising the stored terms again would change them");
        long orderId = invoiceReadyOrder(SOFT, ORIGINAL);
        create(orderId);
        long v1 = invoiceId(orderId, 1);
        Words stored = new Words(STORED_NAME, STORED_LINE1, null, STORED_TERMS);
        setStoredWords(v1, stored);
        assertDetailWords(getCurrent(orderId), 1, stored, "D.3 of the planted words");

        long paymentId = recordCashPayment(orderId, "30.00", 2);
        long v2 = invoiceId(orderId, 2);
        Assertions.assertEquals(stored, storedWords(v2), "D.7 v2 row copies the planted words exactly");
        assertPdfWords(pdfOnDisk(v2), stored, STORED_TERMS_TEXT, "D.7 v2 PDF",
                ORIGINAL_NAME, "Oxford Street", "Paddington", SOFT_TERMS_TEXT);

        voidPayment(orderId, paymentId, 3);
        long v3 = invoiceId(orderId, 3);
        Assertions.assertEquals(stored, storedWords(v3), "D.10 v3 row copies the planted words exactly");
        assertPdfWords(pdfOnDisk(v3), stored, STORED_TERMS_TEXT, "D.10 v3 PDF",
                ORIGINAL_NAME, "Oxford Street", "Paddington", SOFT_TERMS_TEXT);

        JsonNode accepted = acceptInStore(orderId, ORIGINAL_NAME);
        assertDetailWords(accepted, 4, stored, "D.8");
        long v4 = invoiceId(orderId, 4);
        Assertions.assertEquals(stored, storedWords(v4), "D.8 v4 row copies the planted words exactly");
        byte[] v4Pdf = pdfOnDisk(v4);
        assertPdfWords(v4Pdf, stored, STORED_TERMS_TEXT, "D.8 v4 PDF", "Oxford Street", "Paddington", SOFT_TERMS_TEXT);
        assertAcceptedCaption(v4Pdf, ORIGINAL_NAME, acceptedAt(v4), "D.8 v4 PDF");

        assertDetailWords(resend(orderId), 4, stored, "D.9");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(2, sent.size(), "D.8 and D.9 each email once");
        Assertions.assertArrayEquals(v4Pdf, sent.get(1).pdfBytes(), "D.9 re-sends v4's stored PDF");
        assertDetailWords(getCurrent(orderId), 4, stored, "D.3 after D.9");
        Assertions.assertEquals(4, invoiceCount(orderId), "four versions");
        assertNoBlankWordStored(orderId);
    }

    // ================================================================
    // Requirement 4 - null business terms and legacy null words never fall back to live values
    // ================================================================

    /**
     * (a) The business has no SOFT terms at Create (terms_soft NULL, while terms_hard and the legacy
     * block are set): v1 freezes NULL terms, InvoiceDetail carries a present null terms_html with
     * BUSINESS, and the PDF is one page with no terms heading and the footer once. The business then
     * SETS SOFT terms: D.3 still returns null terms, and the payment (v2), the void (v3) and D.8 (v4)
     * keep NULL terms and render no terms page. A later Rewrite (v5) freezes the business's terms as
     * they are then, on page 2.
     *
     * <p>Fails before PR 2: {@code terms_source} was LIVE; after the business set terms, D.3, D.8 and
     * the payment, void and D.8 PDFs used the live SOFT terms; and the Rewrite wrote NULL terms.
     */
    @Test
    void noBusinessTermsAtCreate_freezesNullTerms_laterTermsNeverReachPaymentVoidOrD8_untilARewriteFreezesThem()
            throws Exception {
        setBusinessTerms(null, RAW_HARD_TERMS);
        long orderId = invoiceReadyOrder(SOFT, ORIGINAL);
        Words frozen = new Words(ORIGINAL_NAME, ORIGINAL_LINE1, ORIGINAL_LINE2, null);

        assertDetailWords(create(orderId), 1, frozen, "D.1 with no SOFT terms");
        long v1 = invoiceId(orderId, 1);
        Assertions.assertEquals(frozen, storedWords(v1), "D.1 row: NULL terms");
        assertPdfWords(pdfOnDisk(v1), frozen, null, "D.1 PDF", HARD_TERMS_TEXT, LEGACY_TERMS_TEXT);

        setBusinessTerms(RAW_SOFT_TERMS, RAW_HARD_TERMS);

        assertDetailWords(getCurrent(orderId), 1, frozen, "D.3 after the business sets SOFT terms");
        long paymentId = recordCashPayment(orderId, "30.00", 2);
        long v2 = invoiceId(orderId, 2);
        Assertions.assertEquals(frozen, storedWords(v2), "D.7 v2 row keeps NULL terms");
        assertPdfWords(pdfOnDisk(v2), frozen, null, "D.7 v2 PDF", SOFT_TERMS_TEXT, HARD_TERMS_TEXT, LEGACY_TERMS_TEXT);

        voidPayment(orderId, paymentId, 3);
        long v3 = invoiceId(orderId, 3);
        Assertions.assertEquals(frozen, storedWords(v3), "D.10 v3 row keeps NULL terms");
        assertPdfWords(pdfOnDisk(v3), frozen, null, "D.10 v3 PDF", SOFT_TERMS_TEXT, HARD_TERMS_TEXT, LEGACY_TERMS_TEXT);

        assertDetailWords(acceptInStore(orderId, ORIGINAL_NAME), 4, frozen, "D.8 with NULL terms");
        long v4 = invoiceId(orderId, 4);
        Assertions.assertEquals(frozen, storedWords(v4), "D.8 v4 row keeps NULL terms");
        byte[] v4Pdf = pdfOnDisk(v4);
        assertPdfWords(v4Pdf, frozen, null, "D.8 v4 PDF", SOFT_TERMS_TEXT, HARD_TERMS_TEXT, LEGACY_TERMS_TEXT);
        assertAcceptedCaption(v4Pdf, ORIGINAL_NAME, acceptedAt(v4), "D.8 v4 PDF");
        assertDetailWords(getCurrent(orderId), 4, frozen, "D.3 after D.8");

        Words refrozen = new Words(ORIGINAL_NAME, ORIGINAL_LINE1, ORIGINAL_LINE2, sanitisedTerms(RAW_SOFT_TERMS));
        assertDetailWords(rewrite(orderId), 5, refrozen, "D.2 after the business set SOFT terms");
        long v5 = invoiceId(orderId, 5);
        Assertions.assertEquals(refrozen, storedWords(v5), "D.2 v5 row freezes the business's terms now");
        assertPdfWords(pdfOnDisk(v5), refrozen, SOFT_TERMS_TEXT, "D.2 v5 PDF", HARD_TERMS_TEXT, LEGACY_TERMS_TEXT);
        Assertions.assertEquals(5, invoiceCount(orderId), "five versions");
        assertNoBlankWordStored(orderId);
    }

    /**
     * (b) A legacy version: the current version's identity and terms are all NULL (set by SQL, as the
     * V20 backfill leaves a version whose customer or BILLING row was missing, with the NULL terms of
     * every pre-PR-2 Path B version). D.3 returns the three identity keys present and null and a null
     * terms_html with BUSINESS. The payment (v2), the void (v3) and D.8 (v4, whose name part is neither
     * the live nor a stored name) store all four NULLs, never {@code ""}, and their PDFs show an empty
     * Invoice To, no terms page, and neither the live customer's name nor the live billing street or
     * suburb anywhere. A Rewrite (v5) then freezes the current saved values and business terms.
     *
     * <p>Fails before PR 2: every copy PDF rendered the live customer and billing lines and the live
     * SOFT terms, and D.3 returned the live terms with LIVE.
     */
    @Test
    void legacyVersionWithNullWords_readsAsPresentNulls_copiesKeepEveryNull_pdfsShowNoLiveWords_untilARewrite()
            throws Exception {
        long orderId = invoiceReadyOrder(SOFT, ORIGINAL);
        create(orderId);
        long v1 = invoiceId(orderId, 1);
        Words legacy = new Words(null, null, null, null);
        setStoredWords(v1, legacy);
        assertDetailWords(getCurrent(orderId), 1, legacy, "D.3 of a legacy version");

        long paymentId = recordCashPayment(orderId, "30.00", 2);
        long v2 = invoiceId(orderId, 2);
        Assertions.assertEquals(legacy, storedWords(v2), "D.7 v2 row keeps all four NULLs");
        assertPdfWords(pdfOnDisk(v2), legacy, null, "D.7 v2 PDF",
                "Jordan", "Wilson", "Oxford Street", "Paddington", SOFT_TERMS_TEXT, ACCEPTED_CAPTION);

        voidPayment(orderId, paymentId, 3);
        long v3 = invoiceId(orderId, 3);
        Assertions.assertEquals(legacy, storedWords(v3), "D.10 v3 row keeps all four NULLs");
        assertPdfWords(pdfOnDisk(v3), legacy, null, "D.10 v3 PDF",
                "Jordan", "Wilson", "Oxford Street", "Paddington", SOFT_TERMS_TEXT, ACCEPTED_CAPTION);

        assertDetailWords(acceptInStore(orderId, COUNTER_SIGNER), 4, legacy, "D.8 of a legacy version");
        long v4 = invoiceId(orderId, 4);
        Assertions.assertEquals(legacy, storedWords(v4), "D.8 v4 row keeps all four NULLs");
        byte[] v4Pdf = pdfOnDisk(v4);
        assertPdfWords(v4Pdf, legacy, null, "D.8 v4 PDF",
                "Jordan", "Wilson", "Oxford Street", "Paddington", SOFT_TERMS_TEXT);
        assertAcceptedCaption(v4Pdf, COUNTER_SIGNER, acceptedAt(v4), "D.8 v4 PDF");

        Words refrozen = new Words(ORIGINAL_NAME, ORIGINAL_LINE1, ORIGINAL_LINE2, sanitisedTerms(RAW_SOFT_TERMS));
        assertDetailWords(rewrite(orderId), 5, refrozen, "D.2 of a legacy version");
        long v5 = invoiceId(orderId, 5);
        Assertions.assertEquals(refrozen, storedWords(v5), "D.2 v5 row freezes the saved values");
        assertPdfWords(pdfOnDisk(v5), refrozen, SOFT_TERMS_TEXT, "D.2 v5 PDF", COUNTER_SIGNER, ACCEPTED_CAPTION);
        for (int version = 1; version <= 4; version++) {
            Assertions.assertEquals(legacy, storedWords(invoiceId(orderId, version)),
                    "v" + version + " keeps its NULL words after the rewrite");
        }
        Assertions.assertEquals(5, invoiceCount(orderId), "five versions");
        assertNoBlankWordStored(orderId);
    }

    // ================================================================
    // Requirement 5 - response shapes on every Path B route
    // ================================================================

    /**
     * Every InvoiceDetail-producing Path B route, in one flow: D.1 (frozen identity, terms_soft blank so
     * NULL terms), D.3 (identity set to NULL by SQL), D.8 and D.9 (the copied NULLs), and D.2 (after the
     * business sets SOFT terms). Each response carries EXACTLY the InvoiceDetail key set, so the three
     * identity keys are present even when null (D.3, D.8, D.9; Create and Rewrite always find a saved
     * customer and BILLING row), {@code terms_source} is exactly BUSINESS including with null terms, and
     * no source id, {@code terms_snapshot}, stored-file id, storage path or {@code /uploads/} appears.
     * The payment and void responses keep EXACTLY the unchanged E.1 current_invoice key set and carry no
     * identity or terms key and no customer name or street. No version gains a source quote version.
     *
     * <p>Fails before PR 2: the identity keys were absent from every InvoiceDetail and
     * {@code terms_source} was LIVE.
     */
    @Test
    void everyPathBInvoiceDetailRoute_carriesTheIdentityKeysEvenWhenNull_andBusinessSource_paymentAndVoidKeepE1()
            throws Exception {
        setBusinessTerms("   ", RAW_HARD_TERMS);
        long orderId = invoiceReadyOrder(SOFT, ORIGINAL);
        Words frozenWithoutTerms = new Words(ORIGINAL_NAME, ORIGINAL_LINE1, ORIGINAL_LINE2, null);
        Words nothing = new Words(null, null, null, null);

        assertDetailWords(create(orderId), 1, frozenWithoutTerms, "D.1 with blank SOFT terms");
        setStoredWords(invoiceId(orderId, 1), nothing);
        assertDetailWords(getCurrent(orderId), 1, nothing, "D.3 with NULL identity");

        long paymentId = recordCashPayment(orderId, "30.00", 2);
        voidPayment(orderId, paymentId, 3);

        assertDetailWords(acceptInStore(orderId, ORIGINAL_NAME), 4, nothing, "D.8 with NULL identity");
        assertDetailWords(resend(orderId), 4, nothing, "D.9 with NULL identity");

        setBusinessTerms(RAW_SOFT_TERMS, RAW_HARD_TERMS);
        Words refrozen = new Words(ORIGINAL_NAME, ORIGINAL_LINE1, ORIGINAL_LINE2, sanitisedTerms(RAW_SOFT_TERMS));
        assertDetailWords(rewrite(orderId), 5, refrozen, "D.2 after the business sets SOFT terms");

        for (int version = 1; version <= 5; version++) {
            Assertions.assertNull(invoiceRow(invoiceId(orderId, version)).get("source_quote_version_id"),
                    "v" + version + ": a Path B version has no source quote version");
        }
        Assertions.assertEquals(5, invoiceCount(orderId), "five versions");
        assertNoBlankWordStored(orderId);
    }

    // ================================================================
    // Fixtures - the self-seeded tenant and orders
    // ================================================================

    /** A saved customer and BILLING address, raw as the rows hold them (untrimmed). */
    private record SavedIdentity(String firstName, String middleName, String lastName, String unitNumber,
                                 String streetNumber, String street, String suburb, String stateCode,
                                 String postcode) {
    }

    /** The four stored words of one invoice version, compared as a unit (null-safe record equality). */
    private record Words(String name, String line1, String line2, String terms) {
    }

    /**
     * A fresh tenant inside the test transaction: the business (with ABN, bank details, raw SOFT and
     * HARD terms and a legacy terms block), one store, one salesperson and the user-store access row.
     */
    private void seedTenant() {
        businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number, "
                        + " terms_soft, terms_hard, terms_and_conditions) "
                        + "VALUES (?, ?, TRUE, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, "51 824 753 556", "Kestrel Bank", "062-000", BUSINESS_NAME,
                "12345678", RAW_SOFT_TERMS, RAW_HARD_TERMS, LEGACY_TERMS);
        storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Kestrel Showroom', ?, '0290001234', 'showroom@kestrel.example.com', "
                        + " '1 Showroom Way', 'Burwood', 'NSW', '2134') RETURNING store_id",
                Integer.class, businessId, STORE_CODE);
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Pat', 'Vendor', ?, 'pat.vendor@kestrel.example.com', 'not-a-login-hash') "
                        + "RETURNING user_id",
                Long.class, businessId, SALESPERSON_CODE);
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        orderSequence = 0;
        clearJpaCache();
    }

    /**
     * An invoice-ready order of the seeded tenant: every D.1 gate passes (customer with a valid email,
     * INSTALLATION and BILLING rows, details of sale, proposed lay date 2026-12-01 CONFIRMED, one priced
     * charge line of 100.00 ex / 40.00 cost, so 110.00 inc). The INSTALLATION address never renders.
     */
    private long invoiceReadyOrder(String flooringType, SavedIdentity who) {
        int sequence = ++orderSequence;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        String orderNumber = STORE_CODE + "." + SALESPERSON_CODE + "." + String.format("%05d", sequence);
        long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?::flooring_type, 'LEAD'::order_status, 42, 2026, ?, "
                        + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, businessId, storeId, userId, sequence, orderNumber, flooringType, DETAILS_OF_SALE);
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, middle_name, last_name, email, mobile) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, who.firstName(), who.middleName(), who.lastName(), CUSTOMER_EMAIL, CUSTOMER_MOBILE);
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '7', 'Install Lane', 'Ryde', 'NSW', '2112')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) VALUES (?, 'BILLING'::address_type, ?, ?, ?, ?, ?, ?)",
                orderId, who.unitNumber(), who.streetNumber(), who.street(), who.suburb(), who.stateCode(),
                who.postcode());
        String chargeCode = "KSTC" + sequence;
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, ?::flooring_type, ?, 'Kestrel words test charge', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, storeId, flooringType, chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Kestrel words test charge', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        clearJpaCache();
        return orderId;
    }

    /** The saved customer is renamed and the saved BILLING row rewritten to {@code who}. */
    private void saveIdentity(long orderId, SavedIdentity who) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_customer SET first_name = ?, middle_name = ?, last_name = ? WHERE order_id = ?",
                who.firstName(), who.middleName(), who.lastName(), orderId));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_address SET unit_number = ?, street_number = ?, street = ?, suburb = ?, "
                        + " state_code = ?, postcode = ? WHERE order_id = ? AND address_type = 'BILLING'::address_type",
                who.unitNumber(), who.streetNumber(), who.street(), who.suburb(), who.stateCode(), who.postcode(),
                orderId));
        clearJpaCache();
    }

    /** The seeded business's per-flooring-type terms (V13 columns). */
    private void setBusinessTerms(String termsSoft, String termsHard) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE business SET terms_soft = ?, terms_hard = ? WHERE business_id = ?",
                termsSoft, termsHard, businessId));
        clearJpaCache();
    }

    /** After a version is written, the saved state moves on: rename, billing move and both terms edited. */
    private void moveSavedStateOn(long orderId) {
        saveIdentity(orderId, EDITED);
        setBusinessTerms(RAW_EDITED_SOFT_TERMS, RAW_EDITED_HARD_TERMS);
    }

    /** Plant the stored words of one version by SQL (the fixture for exact and legacy copies). */
    private void setStoredWords(long invoiceId, Words words) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET customer_name_snapshot = ?, customer_address_line1_snapshot = ?, "
                        + " customer_address_line2_snapshot = ?, terms_snapshot = ? WHERE invoice_id = ?",
                words.name(), words.line1(), words.line2(), words.terms(), invoiceId));
        Assertions.assertEquals(words, storedWords(invoiceId), "fixture: the planted words are stored as given");
        clearJpaCache();
    }

    /**
     * A bare ACCEPTED quote version of THIS order (the V16 NOT NULL columns plus its own terms), used only
     * as the source a quote-sourced invoice version references. D.2 never reads the source quote.
     */
    private long insertAcceptedQuoteVersion(long orderId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO quote_version (order_id, version_number, status, itemised, quote_total_ex_gst, "
                        + " quote_total_inc_gst, flooring_type_snapshot, terms_snapshot, accepted_at, "
                        + " accepted_customer_name, created_by_user_id) "
                        + "VALUES (?, 1, 'ACCEPTED', FALSE, 100.00, 110.00, 'SOFT', ?, "
                        + " TIMESTAMP '2026-10-01 10:15:30', ?, ?) RETURNING quote_version_id",
                Long.class, orderId, QUOTE_TERMS, ORIGINAL_NAME, userId);
    }

    /** The business terms exactly as a freeze stores them: sanitised once, and different from the raw value. */
    private String sanitisedTerms(String rawTerms) {
        String html = termsSanitizer.sanitize(rawTerms);
        Assertions.assertNotNull(html, () -> "fixture: the raw terms must keep visible text: " + rawTerms);
        Assertions.assertNotEquals(rawTerms, html, () -> "fixture: the raw terms must carry markup the sanitizer strips");
        return html;
    }

    private void resetSenders() {
        invoiceEmailSender.reset();
        quoteEmailSender.reset();
        acceptanceNotificationSender.reset();
    }

    /**
     * Detach every hydrated entity. The MockMvc calls of a test share one transaction and persistence
     * context, so after a raw JDBC write a later JPA read (customer, address, order, business) would
     * otherwise return the stale cached entity.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    // ================================================================
    // Requests (the seeded session and slug)
    // ================================================================

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        session.setAttribute("user_id", Long.valueOf(userId));
        session.setAttribute("business_id", Long.valueOf(businessId));
        session.setAttribute("store_id", Integer.valueOf(storeId));
        return session;
    }

    private String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    /** Perform a request and assert its status, with the response body in the failure message. */
    private MvcResult perform(RequestBuilder request, int expectedStatus, String label) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        Assertions.assertEquals(expectedStatus, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + bodyOf(result));
        return result;
    }

    /** D.1 Create (empty body): 201 with its message; returns the checked data.invoice. */
    private JsonNode create(long orderId) throws Exception {
        MvcResult result = perform(post(orderUrl(orderId) + "/invoices").session(session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "D.1 create");
        Assertions.assertEquals(CREATED_MESSAGE, readJson(result).path("message").asText(), "D.1 message");
        return invoiceDetail(result, "D.1 create");
    }

    /** D.2 Rewrite (empty body): 201 with its message; returns the checked data.invoice. */
    private JsonNode rewrite(long orderId) throws Exception {
        MvcResult result = perform(post(orderUrl(orderId) + "/invoices/rewrite").session(session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "D.2 rewrite");
        Assertions.assertEquals(REWRITTEN_MESSAGE, readJson(result).path("message").asText(), "D.2 message");
        return invoiceDetail(result, "D.2 rewrite");
    }

    /** D.3 GET current: 200 with no top-level message; returns the checked data.invoice. */
    private JsonNode getCurrent(long orderId) throws Exception {
        MvcResult result = perform(get(orderUrl(orderId) + "/invoices/current").session(session()),
                200, "D.3 current");
        Assertions.assertFalse(readJson(result).has("message"), "D.3 carries no top-level message");
        return invoiceDetail(result, "D.3 current");
    }

    /** D.4: the current version's stored PDF, as streamed. */
    private byte[] downloadCurrentFile(long orderId) throws Exception {
        MvcResult result = perform(get(orderUrl(orderId) + "/invoices/current/file").session(session()),
                200, "D.4 current file");
        Assertions.assertEquals(MediaType.APPLICATION_PDF_VALUE, result.getResponse().getContentType(), "D.4 type");
        return result.getResponse().getContentAsByteArray();
    }

    /**
     * D.8 in-store accept with today's request (PR 2 leaves it unchanged): a PNG {@code signature} part
     * and the {@code accepted_customer_name} part, which is the accepted name. The recording sender
     * succeeds, so the response is 201 with the emailed message.
     */
    private JsonNode acceptInStore(long orderId, String acceptedCustomerName) throws Exception {
        MvcResult result = perform(multipart(orderUrl(orderId) + "/invoices/current/accept")
                .file(new MockMultipartFile("signature", "signature.png", "image/png", ONE_PIXEL_PNG))
                .param("accepted_customer_name", acceptedCustomerName)
                .session(session()), 201, "D.8 accept");
        Assertions.assertEquals(ACCEPT_EMAILED_MESSAGE, readJson(result).path("message").asText(), "D.8 message");
        JsonNode invoice = invoiceDetail(result, "D.8 accept");
        Assertions.assertEquals(acceptedCustomerName, invoice.path("accepted_customer_name").asText(),
                "D.8: the accepted name is the submitted part");
        Assertions.assertTrue(invoice.path("accepted_signature_present").asBoolean(), "D.8: signed");
        return invoice;
    }

    /** D.9 resend (empty body): 200 with its message; returns the checked data.invoice. */
    private JsonNode resend(long orderId) throws Exception {
        MvcResult result = perform(post(orderUrl(orderId) + "/invoices/current/resend").session(session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200, "D.9 resend");
        Assertions.assertEquals(RESEND_MESSAGE, readJson(result).path("message").asText(), "D.9 message");
        return invoiceDetail(result, "D.9 resend");
    }

    /** D.7 CASH payment: 201, the unchanged E.1 summary at {@code expectedVersion}; returns the payment id. */
    private long recordCashPayment(long orderId, String amount, int expectedVersion) throws Exception {
        MvcResult result = perform(post(orderUrl(orderId) + "/payments").session(session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"), 201, "D.7 payment");
        JsonNode json = readJson(result);
        Assertions.assertEquals(PAYMENT_RECORDED_MESSAGE, json.path("message").asText(), "D.7 message");
        assertCurrentInvoiceSummary(result, expectedVersion, "D.7 payment");
        long paymentId = json.path("data").path("payment_transaction").path("payment_transaction_id").asLong();
        Assertions.assertTrue(paymentId > 0, "D.7 returns the payment id");
        return paymentId;
    }

    /** D.10 void (no body): 201, the unchanged E.1 summary at {@code expectedVersion}. */
    private void voidPayment(long orderId, long paymentId, int expectedVersion) throws Exception {
        MvcResult result = perform(post(orderUrl(orderId) + "/payments/" + paymentId + "/void").session(session()),
                201, "D.10 void");
        Assertions.assertEquals(PAYMENT_VOIDED_MESSAGE, readJson(result).path("message").asText(), "D.10 message");
        assertCurrentInvoiceSummary(result, expectedVersion, "D.10 void");
    }

    // ================================================================
    // Assertions - API responses
    // ================================================================

    private static String bodyOf(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode readJson(MvcResult result) throws IOException {
        return JSON.readTree(bodyOf(result));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * data.invoice of an InvoiceDetail response: EXACTLY the InvoiceDetail keys (so every identity and
     * terms key is present, null or not) and no internal provenance or storage value in the body.
     */
    private static JsonNode invoiceDetail(MvcResult result, String label) throws IOException {
        String body = bodyOf(result);
        JsonNode invoice = JSON.readTree(body).path("data").path("invoice");
        Assertions.assertTrue(invoice.isObject(), () -> label + ": data.invoice must be an object: " + body);
        Assertions.assertEquals(new TreeSet<>(INVOICE_DETAIL_KEYS), fieldNames(invoice),
                () -> label + ": InvoiceDetail key set: " + body);
        for (String marker : DETAIL_LEAK_MARKERS) {
            Assertions.assertFalse(body.contains(marker), () -> label + ": '" + marker + "' must never appear: " + body);
        }
        return invoice;
    }

    /**
     * The InvoiceDetail words equal the expected words EXACTLY: each identity key and terms_html is the
     * expected string, or a PRESENT JSON null when the expected value is null; terms_source is BUSINESS.
     */
    private static void assertDetailWords(JsonNode invoice, int expectedVersion, Words expected, String label) {
        Assertions.assertEquals(expectedVersion, invoice.path("version_number").asInt(), label + ": version_number");
        assertNullableString(invoice, "customer_name_snapshot", expected.name(), label);
        assertNullableString(invoice, "customer_address_line1_snapshot", expected.line1(), label);
        assertNullableString(invoice, "customer_address_line2_snapshot", expected.line2(), label);
        assertNullableString(invoice, "terms_html", expected.terms(), label);
        JsonNode source = invoice.get("terms_source");
        Assertions.assertTrue(source != null && source.isTextual(), () -> label + ": terms_source must be a string");
        Assertions.assertEquals("BUSINESS", source.asText(), label + ": terms_source");
    }

    private static void assertNullableString(JsonNode node, String key, String expected, String label) {
        Assertions.assertTrue(node.has(key), () -> label + ": " + key + " must be present: " + node);
        JsonNode value = node.get(key);
        if (expected == null) {
            Assertions.assertTrue(value.isNull(), () -> label + ": " + key + " must be JSON null but was " + value);
        } else {
            Assertions.assertTrue(value.isTextual(), () -> label + ": " + key + " must be a string but was " + value);
            Assertions.assertEquals(expected, value.asText(), label + ": " + key);
        }
    }

    /** The payment / void data.current_invoice: EXACTLY the unchanged E.1 keys, no word or terms key or value. */
    private static void assertCurrentInvoiceSummary(MvcResult result, int expectedVersion, String label)
            throws IOException {
        String body = bodyOf(result);
        JsonNode current = JSON.readTree(body).path("data").path("current_invoice");
        Assertions.assertTrue(current.isObject(), () -> label + ": data.current_invoice must be an object: " + body);
        Assertions.assertEquals(new TreeSet<>(CURRENT_INVOICE_SUMMARY_KEYS), fieldNames(current),
                () -> label + ": the E.1 current_invoice key set is unchanged: " + body);
        Assertions.assertEquals(expectedVersion, current.path("version_number").asInt(), label + ": current version");
        for (String absent : SUMMARY_ABSENT_MARKERS) {
            Assertions.assertFalse(body.contains(absent), () -> label + ": '" + absent + "' must not appear: " + body);
        }
    }

    // ================================================================
    // Assertions - rows
    // ================================================================

    private long invoiceId(long orderId, int versionNumber) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT invoice_id FROM invoice WHERE order_id = ? AND version_number = ?",
                Long.class, orderId, versionNumber);
        Assertions.assertNotNull(id, () -> "invoice v" + versionNumber + " must exist");
        return id;
    }

    private int invoiceCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice WHERE order_id = ?",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    /** The whole invoice row, for append-only (unchanged row) comparisons. */
    private Map<String, Object> invoiceRow(long invoiceId) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap("SELECT * FROM invoice WHERE invoice_id = ?", invoiceId));
    }

    /** The version's four stored words, exactly as the columns hold them. */
    private Words storedWords(long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT customer_name_snapshot, customer_address_line1_snapshot, customer_address_line2_snapshot, "
                        + " terms_snapshot FROM invoice WHERE invoice_id = ?",
                (rs, rowNum) -> new Words(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                invoiceId);
    }

    private LocalDateTime acceptedAt(long invoiceId) {
        Object value = invoiceRow(invoiceId).get("accepted_at");
        Assertions.assertNotNull(value, "the version must be accepted");
        return ((Timestamp) value).toLocalDateTime();
    }

    /** A carried acceptance: accepted_at, the accepted name and the SAME signature stored_file. */
    private static void assertCarriesAcceptance(Map<String, Object> signedRow, Map<String, Object> row, String label) {
        for (String column : List.of("accepted_at", "accepted_customer_name", "accepted_signature_file_id")) {
            Assertions.assertNotNull(row.get(column), () -> label + ": " + column + " must be carried");
            Assertions.assertEquals(signedRow.get(column), row.get(column), () -> label + ": " + column + " carried verbatim");
        }
    }

    /** No word of any version of the order is stored as "" (or whitespace only): absent words are NULL. */
    private void assertNoBlankWordStored(long orderId) {
        Integer blanks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE order_id = ? AND (BTRIM(customer_name_snapshot) = '' "
                        + " OR BTRIM(customer_address_line1_snapshot) = '' OR BTRIM(customer_address_line2_snapshot) = '' "
                        + " OR BTRIM(terms_snapshot) = '')",
                Integer.class, orderId);
        Assertions.assertEquals(0, blanks, "no stored word may be blank: absent words are NULL");
    }

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertTrue(actual instanceof BigDecimal, () -> label + " must be a BigDecimal but was " + actual);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                () -> label + ": expected " + expected + " but was " + actual);
    }

    // ================================================================
    // Assertions - PDFs
    // ================================================================

    /** The version's stored PDF read from disk (base dir + storage_path), checked against its stored_file row. */
    private byte[] pdfOnDisk(long invoiceId) throws IOException {
        Map<String, Object> file = jdbcTemplate.queryForMap(
                "SELECT sf.storage_path, sf.mime_type, sf.file_size FROM invoice i "
                        + "JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id WHERE i.invoice_id = ?",
                invoiceId);
        Assertions.assertEquals("application/pdf", file.get("mime_type"), "stored_file mime type");
        String storagePath = (String) file.get("storage_path");
        Assertions.assertTrue(storagePath.startsWith("/uploads/" + businessId + "/orders/"),
                () -> "the PDF must be stored under the seeded tenant: " + storagePath);
        Path onDisk = Path.of(storageBaseDir).toAbsolutePath().normalize().resolve(storagePath.substring(1)).normalize();
        Assertions.assertTrue(Files.isRegularFile(onDisk), () -> "the invoice PDF must exist on disk: " + onDisk);
        byte[] bytes = Files.readAllBytes(onDisk);
        Assertions.assertEquals(((Number) file.get("file_size")).longValue(), bytes.length, "file_size matches the bytes");
        Assertions.assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII), "PDF header");
        return bytes;
    }

    /**
     * The PDF words: Invoice To on page 1 holds EXACTLY the stored name and billing lines (nothing when
     * all are null); the terms page shows {@code termsText}, or there is no terms page when it is null;
     * the footer renders once; and none of {@code absentTexts} appears anywhere in the document.
     */
    private static void assertPdfWords(byte[] pdf, Words words, String termsText, String label,
                                       String... absentTexts) throws IOException {
        assertInvoiceTo(pdf, words, label);
        String all = documentText(pdf);
        Assertions.assertEquals(1, countOccurrences(flat(all), FOOTER),
                () -> label + ": the footer must render exactly once: " + flat(all));
        if (termsText == null) {
            Assertions.assertEquals(1, pageCount(pdf), () -> label + ": no terms -> a single page: " + flat(all));
            Assertions.assertFalse(all.contains(TERMS_HEADING), () -> label + ": no terms heading: " + flat(all));
        } else {
            Assertions.assertEquals(2, pageCount(pdf), () -> label + ": terms -> a dedicated page 2: " + flat(all));
            String page1 = pageText(pdf, 1);
            String page2 = pageText(pdf, 2);
            Assertions.assertFalse(page1.contains(TERMS_HEADING), () -> label + ": page 1 never carries the terms");
            assertTextLacks(page1, termsText, label + " (page 1)");
            Assertions.assertTrue(page2.contains(TERMS_HEADING), () -> label + ": page 2 carries the terms heading");
            assertTextContains(page2, termsText, label + " (page 2)");
        }
        for (String absent : absentTexts) {
            assertTextLacks(all, absent, label);
        }
    }

    /**
     * Invoice To EXACTLY: the flattened page-1 text between the "Invoice To" eyebrow and the next
     * column's eyebrow equals the version's non-null stored name and lines, in order.
     */
    private static void assertInvoiceTo(byte[] pdf, Words words, String label) throws IOException {
        String expected = Stream.of(words.name(), words.line1(), words.line2())
                .filter(Objects::nonNull)
                .map(value -> flat(value).trim())
                .collect(Collectors.joining(" "));
        String page1 = flat(pageText(pdf, 1));
        int heading = page1.indexOf(INVOICE_TO_HEADING);
        Assertions.assertTrue(heading >= 0, () -> label + ": page 1 must carry the Invoice To heading: " + page1);
        int next = page1.indexOf(NEXT_COLUMN_HEADING, heading + INVOICE_TO_HEADING.length());
        Assertions.assertTrue(next > heading, () -> label + ": the next column must follow Invoice To: " + page1);
        String block = page1.substring(heading + INVOICE_TO_HEADING.length(), next).trim();
        Assertions.assertEquals(expected, block,
                () -> label + ": Invoice To must hold exactly the version's stored name and billing lines: " + page1);
    }

    /**
     * The signed caption "Accepted by {name} on {dd/MM/yyyy HH:mm}", read in visual order (PDFBox emits
     * the static words before the inline spans in stream order). Postgres keeps microseconds, so the
     * stored accepted_at may sit up to 1 microsecond after the rendered in-memory instant; the rendering
     * of {@code acceptedAt - 1us} is accepted too, which keeps the check exact across a minute boundary.
     */
    private static void assertAcceptedCaption(byte[] pdf, String acceptedName, LocalDateTime acceptedAt, String label)
            throws IOException {
        String readingOrder;
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            readingOrder = stripper.getText(document);
        }
        String text = noSpace(readingOrder);
        String exact = noSpace(ACCEPTED_CAPTION + " " + acceptedName + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        String roundedBack = noSpace(ACCEPTED_CAPTION + " " + acceptedName + " on "
                + DISPLAY_DATE_TIME.format(acceptedAt.minusNanos(1_000)));
        Assertions.assertTrue(text.contains(exact) || text.contains(roundedBack),
                () -> label + ": the PDF must carry the caption '" + exact + "' in: " + flat(readingOrder));
    }

    private static String documentText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String pageText(byte[] pdf, int page) throws IOException {
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

    // PDFBox can inject or drop spaces between glyphs and wraps long text, so checks either collapse
    // whitespace runs or compare with all whitespace removed.
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void assertTextContains(String text, String expected, String label) {
        Assertions.assertTrue(noSpace(text).contains(noSpace(expected)),
                () -> label + ": the PDF must contain '" + expected + "' in: " + flat(text));
    }

    private static void assertTextLacks(String text, String unexpected, String label) {
        Assertions.assertFalse(noSpace(text).toUpperCase(Locale.ROOT).contains(noSpace(unexpected).toUpperCase(Locale.ROOT)),
                () -> label + ": the PDF must NOT contain '" + unexpected + "' in: " + flat(text));
    }
}
