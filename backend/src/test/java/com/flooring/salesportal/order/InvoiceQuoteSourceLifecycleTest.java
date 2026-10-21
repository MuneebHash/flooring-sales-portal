package com.flooring.salesportal.order;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16F PR2 - the V19 invoice quote-source columns ({@code invoice.source_quote_version_id} and
 * {@code invoice.terms_snapshot}) across the whole invoice lifecycle, and the existing invoice flows
 * run against a quote-origin (Path A) invoice. Phase 16G PR 2 (#125) adds the version's own words:
 * every version stores and renders its terms ({@code terms_snapshot}) and its V20 Invoice To name and
 * billing lines ({@code customer_name_snapshot}, {@code customer_address_line1_snapshot},
 * {@code customer_address_line2_snapshot}); nothing customer-facing is read live at render or read time.
 *
 * <p>Part 1 (lifecycle and ownership) proves the write table on the raw invoice rows: Path A writes the
 * selected accepted version id, its frozen terms (including frozen null) and its V17 Invoice To
 * snapshot, even after the saved customer moved on; D.7 payment and D.10 void carry the source, the
 * words and the acceptance columns verbatim; D.1 Create writes a null source and freezes the saved
 * customer, its BILLING lines and the business's sanitised per-type terms (terms_source BUSINESS, never
 * the quote's terms or identity); D.2 Rewrite of a Path A invoice writes a null source, freezes the
 * same fresh words and clears the acceptance; D.8 accept carries the source and the words defensively
 * from the current row, null Invoice To words included (and renders that row's frozen terms, never the
 * business's current terms). It also proves the shared write boundary:
 * {@link InvoiceRepository#insertInvoice} refuses a missing or cross-order source BEFORE the insert,
 * both when called directly and when a payment regenerates from a row carrying such a source (generic
 * 500, nothing internal leaked).
 *
 * <p>Part 2 (regressions) runs D.9 resend, D.10 signature download, D.8, D.1, D.2 and the PR1
 * accepted-quote reads against a Path A invoice.
 *
 * <p>Amended for Phase 16G PR 3 (#69, #123; 16G contract sections 5 and 6): every Path A request carries the
 * mandatory {@code {"quote_version_id": N}} (N = the order's latest ACCEPTED quote version id, read from the
 * database) and, once converted, emails the signed PDF of the version it appended (the delivered message;
 * in this transactional context the success stamp sets that version's {@code last_emailed_at} and the
 * dashboard mirror) - payment and void still never email. Every D.8 request carries the mandatory
 * {@code expected_version_number} (the current invoice version) and no name part: D.8's accepted name is
 * the current version's frozen {@code customer_name_snapshot}.
 *
 * <p>Every accepted quote comes from the REAL chain (protected draft PUT, protected send-email, token
 * read from the recorded email, PUBLIC multipart accept with no session). Raw SQL is used only where
 * the scenario calls for it: an SQL-seeded quote-sourced invoice for the D.8 defensive carry and the
 * cross-order ownership cases (the single-column V19 FK allows a cross-order reference), bare
 * quote_version rows for the "other order" side, tenant terms, the saved-customer edits made after an
 * acceptance, a relaxed email constraint and LAID.
 *
 * <p>Self-seeded (Phase 14D): business 1 / store 1 / user 1 is only the session identity; every order
 * is inserted here with the {@code QINVL.ZZ9.} order-number prefix (sequence base 150_000) and
 * {@code QINVL} store charge codes. Everything runs in the test transaction and rolls back; the
 * services' rollback hooks delete the files they wrote under the shared quote test storage root, so a
 * stored file's physical path is that root plus {@code stored_file.storage_path}. The recording
 * senders are singletons whose state survives a rollback, so all four (invoice email, quote email, store
 * notification, SMS) are reset before AND after every test.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class InvoiceQuoteSourceLifecycleTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final String ORDER_NUMBER_PREFIX = "QINVL.ZZ9.";
    private static final String CHARGE_CODE_PREFIX = "QINVL";
    private static final String VALID_EMAIL = "quote.invoice.lifecycle@example.com";
    private static final String VALID_MOBILE = "0412345678";
    // The V17 issue-time snapshot of the seeded customer ('Quote' + 'Tester'): the quote's accepted name.
    private static final String SNAPSHOT_NAME = "Quote Tester";
    // The rest of that V17 snapshot: the seeded BILLING row's two lines. Line 2 is also store 1's
    // locality on the invoice PDF, so PDF checks read it inside the Invoice To block only.
    private static final String SNAPSHOT_LINE1 = "12 Test Street";
    private static final String SNAPSHOT_LINE2 = "Sydney NSW 2000";
    // 16G PR 2 (#125): the Invoice To words of the saved customer after moveSavedCustomerOn (renamed,
    // BILLING row moved) - edited AFTER the quote froze its snapshot, so a version can only show the
    // words its writer froze or copied.
    private static final String MOVED_NAME = "Renata Movedon";
    private static final String MOVED_LINE1 = "5/88 Relocated Avenue";
    private static final String MOVED_LINE2 = "Newtown NSW 2042";
    private static final String ORDER_DETAILS = "Supply and lay carpet (QINVL details of sale)";
    private static final String SEEDED_INVOICE_DETAILS = "QINVL seeded invoice details";
    // 16G PR 3: the frozen Invoice To name of the SQL-seeded quote-sourced v1 in the D.8 carry test. D.8 now
    // takes its accepted name from the current version's customer_name_snapshot (blank -> 422), so the seed
    // carries one, deliberately different from the saved customer and the quote's V17 name (SNAPSHOT_NAME).
    private static final String D8_SEEDED_NAME = "Seeda Frozensigner";

    // Tenant terms. Each scenario uses distinct, recognisable text so the rendered PDF and the API can
    // only show the value the rule selects (frozen quote terms, the seeded D.8 terms, or the business's
    // current LIVE_* terms that D.1 / D.2 freeze into the version they write).
    private static final String QUOTE_TERMS_RAW = "<p>QINVL frozen quote terms alpha</p>";
    private static final String QUOTE_TERMS_TEXT = "QINVL frozen quote terms alpha";
    private static final String LIVE_SOFT_TERMS_RAW = "<p>QINVL live soft terms beta</p>";
    private static final String LIVE_SOFT_TERMS_TEXT = "QINVL live soft terms beta";
    private static final String LIVE_HARD_TERMS_RAW = "<p>QINVL live hard terms gamma</p>";
    private static final String LIVE_HARD_TERMS_TEXT = "QINVL live hard terms gamma";
    private static final String D8_SEEDED_TERMS = "<p>Seeded frozen terms D8</p>";
    private static final String D8_SEEDED_TERMS_TEXT = "Seeded frozen terms D8";
    private static final String CROSS_ORDER_SEEDED_TERMS = "<p>QINVL cross order seeded terms</p>";

    private static final String FOOTER_TEXT = "Generated by the Flooring Sales Portal";

    // 16G PR 3 (contract 6.1): the Path A 201 message when the post-commit signed-PDF email was delivered.
    private static final String CREATED_FROM_QUOTE_MESSAGE =
            "Invoice created from accepted quote and emailed to the customer.";
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String INVOICE_REWRITTEN_MESSAGE = "Invoice rewritten.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String PAYMENT_VOIDED_MESSAGE = "Payment voided. Current invoice updated.";
    private static final String ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String RESEND_MESSAGE = "Invoice re-sent to the customer.";
    private static final String D8_ALREADY_ACCEPTED_MESSAGE =
            "This invoice has already been accepted. Use Re-send to email it again.";
    private static final String INVOICE_ALREADY_EXISTS_MESSAGE =
            "An invoice already exists for this order. Use Rewrite Invoice to create a new version.";
    private static final String CUSTOMER_EMAIL_REQUIRED_MESSAGE =
            "A valid customer email is required before this action.";
    private static final String ORDER_LOCKED_MESSAGE = "Order is laid and cannot be edited.";
    private static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred.";

    // The plaintext token inside the delivered link .../q/{token} (URL-safe Base64).
    private static final Pattern PUBLIC_LINK_TOKEN = Pattern.compile("/q/([A-Za-z0-9_-]{43,128})");

    // A real, decodable 1x1 PNG (the public accept validates and re-encodes the upload; D.8 stores it raw).
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // InvoiceDetail (D.1 / D.2 / D.8 / D.9 / Path A): the 18 Phase 12 + 13 keys, the two 16F PR2 terms
    // keys and the three 16G PR 2 Invoice To keys, ALWAYS present. No internal id (source quote version,
    // stored files) is ever a key.
    private static final Set<String> INVOICE_DETAIL_KEYS = Set.of(
            "invoice_id", "order_id", "version_number", "invoice_date", "due_date", "details_of_sale_snapshot",
            "sale_price_ex_gst", "sale_price_inc_gst", "total_paid", "balance_due", "created_by_user_id",
            "created_at", "pdf_download_path", "accepted_at", "accepted_customer_name",
            "accepted_signature_present", "accepted_signature_download_path", "last_emailed_at",
            "terms_html", "terms_source",
            // 16G PR 2 (#125): the version's frozen Invoice To words, always present (nullable).
            "customer_name_snapshot", "customer_address_line1_snapshot", "customer_address_line2_snapshot",
            // 16G PR 4: the derived link field, always present (nullable; never on E.1 or a public payload).
            "invoice_link_expires_at");

    // CurrentInvoiceSummary (payment / void responses): UNCHANGED by 16F PR2 and by 16G PR 2, so no terms
    // or Invoice To keys.
    private static final Set<String> CURRENT_INVOICE_SUMMARY_KEYS = Set.of(
            "invoice_id", "version_number", "invoice_date", "due_date", "sale_price_inc_gst", "total_paid",
            "balance_due", "created_by_user_id", "created_at", "pdf_download_path", "accepted_at",
            "accepted_customer_name", "accepted_signature_present", "accepted_signature_download_path",
            "last_emailed_at");

    // Exact money comparisons: JSON decimals are read as BigDecimal (never through a double).
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceTermsSanitizer termsSanitizer;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender acceptanceNotificationSender;

    // Path A never sends an SMS (16G PR 3 keeps it email only); SMS is dormant and recording-only.
    @Autowired
    private RecordingSmsSender smsSender;

    @PersistenceContext
    private EntityManager entityManager;

    // The EFFECTIVE storage root (the class-level property), resolved exactly like FileStorageService.
    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    private int seq = 150_000;

    @BeforeEach
    void setUp() {
        Assertions.assertTrue(
                Path.of(storageBaseDir).endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                () -> "unexpected storage root " + storageBaseDir);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resetSenders();
    }

    @AfterEach
    void tearDown() {
        // Singletons: never leak recorded messages or an armed failNextSend into another test class.
        resetSenders();
    }

    private void resetSenders() {
        invoiceEmailSender.reset();
        quoteEmailSender.reset();
        acceptanceNotificationSender.reset();
        smsSender.reset();
    }

    // ================================================================
    // Helpers - sessions and URLs
    // ================================================================

    private static MockHttpSession liamStore1Session() {
        MockHttpSession s = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        s.setAttribute("store_id", STORE_SYD_CBD);
        return s;
    }

    private static String orderUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId;
    }

    private static String quoteUrl(Object orderId, String suffix) {
        return orderUrl(orderId) + "/quote/" + suffix;
    }

    private static String invoicesUrl(Object orderId) {
        return orderUrl(orderId) + "/invoices";
    }

    private static String invoiceAcceptUrl(Object orderId) {
        return invoicesUrl(orderId) + "/current/accept";
    }

    private static String invoiceResendUrl(Object orderId) {
        return invoicesUrl(orderId) + "/current/resend";
    }

    private static String invoiceSignatureUrl(Object orderId) {
        return invoicesUrl(orderId) + "/current/signature";
    }

    private static String paymentsUrl(Object orderId) {
        return orderUrl(orderId) + "/payments";
    }

    private static String publicQuoteUrl(String token) {
        return "/api/v1/public/quotes/" + token;
    }

    /**
     * Detach every hydrated entity. The MockMvc calls of one test share the test transaction's
     * persistence context, so after a raw JDBC write (or a native write inside a request) a later JPA
     * read would otherwise return a stale cached entity.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    // ================================================================
    // Helpers - self-seeding (business 1 / store 1 / user 1 only)
    // ================================================================

    private long insertOrder(String status) {
        int s = ++seq;
        // order_number must match chk_sales_order_number_format (V6): {code}.{LL#}.{#####}
        String orderNumber = ORDER_NUMBER_PREFIX + String.format("%05d", s % 100_000);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, ?::order_status, 1, 2026) "
                        + "RETURNING order_id",
                Long.class,
                BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, s, orderNumber, status);
    }

    /** A LEAD order with a send-ready customer (valid email) and a billing address (Invoice To rows). */
    private long readyOrder() {
        long orderId = insertOrder("LEAD");
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quote', 'Tester', ?, ?)",
                orderId, VALID_EMAIL, VALID_MOBILE);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Test Street', 'Sydney', 'NSW', '2000')",
                orderId);
        clearJpaCache();
        return orderId;
    }

    /**
     * A ready order that also passes the Path A preconditions and the D.1 / D.2 email gate plus the 9
     * live preconditions: details of sale, proposed lay date 2026-12-01 + CONFIRMED, an INSTALLATION
     * address and ONE priced charge line (100.00 ex, cost 40.00 - below every quote total used here).
     */
    private long invoiceReadyOrder() {
        long orderId = readyOrder();
        jdbcTemplate.update(
                "UPDATE sales_order SET details_of_sale = ?, proposed_lay_date = DATE '2026-12-01', "
                        + "lay_date_status = 'CONFIRMED'::lay_date_status WHERE order_id = ?",
                ORDER_DETAILS, orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '7', 'Install Street', 'Sydney', 'NSW', '2000')",
                orderId);
        String code = CHARGE_CODE_PREFIX + (++seq);
        BigDecimal lineTotal = new BigDecimal("100.00");
        BigDecimal lineCost = new BigDecimal("40.00");
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Quote invoice lifecycle charge', ?, ?) "
                        + "RETURNING charge_id",
                Long.class, STORE_SYD_CBD, code, lineTotal, lineCost);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Quote invoice lifecycle charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code, lineTotal, lineCost, lineTotal, lineTotal, lineCost);
        clearJpaCache();
        return orderId;
    }

    /** Tenant terms per flooring type (V13 columns; the legacy terms_and_conditions is never used). */
    private void setTenantTerms(String softTerms, String hardTerms) {
        jdbcTemplate.update("UPDATE business SET terms_soft = ?, terms_hard = ? WHERE business_id = ?",
                softTerms, hardTerms, BUSINESS_AUSSIE);
        clearJpaCache();
    }

    private void clearTenantTerms() {
        jdbcTemplate.update("UPDATE business SET terms_soft = NULL, terms_hard = NULL WHERE business_id = ?",
                BUSINESS_AUSSIE);
        clearJpaCache();
    }

    /**
     * 16G PR 2 (#125): rename the saved customer and move its BILLING row to the {@code MOVED_*} words.
     * Called after the quote froze its V17 snapshot; every Path A / D.1 / D.2 precondition still passes
     * (first and last name, both address rows and the email stay valid).
     */
    private void moveSavedCustomerOn(long orderId) {
        // Each edit must hit exactly one row, so the "moved-on words never render" checks are never vacuous.
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_customer SET first_name = 'Renata', last_name = 'Movedon' WHERE order_id = ?",
                orderId), "fixture: rename the saved customer");
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_address SET unit_number = '5', street_number = '88', street = 'Relocated Avenue', "
                        + "suburb = 'Newtown', state_code = 'NSW', postcode = '2042' "
                        + "WHERE order_id = ? AND address_type = 'BILLING'::address_type",
                orderId), "fixture: move the saved BILLING row");
        clearJpaCache();
    }

    private void saveDraft(long orderId, String body) throws Exception {
        clearJpaCache();
        mockMvc.perform(put(quoteUrl(orderId, "draft")).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    /** Itemised: Carpet 2x100 + Underlay 1x50 = 250.00 ex / 275.00 inc. */
    private void saveItemisedTwoLineDraft(long orderId) throws Exception {
        saveDraft(orderId, """
                {"itemised": true, "lines": [
                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                ]}""");
    }

    /** Non-itemised 300.00 ex / 330.00 inc (header-only save). */
    private void saveNonItemisedDraft(long orderId) throws Exception {
        saveDraft(orderId, "{\"itemised\": false, \"final_total_inc_gst\": 330.00, \"lines\": []}");
    }

    /** Issue (or re-issue) via the protected send-email and return the plaintext token from the link. */
    private String sendAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        clearJpaCache();
        mockMvc.perform(post(quoteUrl(orderId, "send-email")).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        clearJpaCache();
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "exactly one quote email must have been recorded");
        String body = sent.get(sent.size() - 1).bodyText();
        Matcher matcher = PUBLIC_LINK_TOKEN.matcher(body);
        Assertions.assertTrue(matcher.find(), () -> "the quote email must carry the /q/{token} link: " + body);
        return matcher.group(1);
    }

    private static MockMultipartFile signaturePart(byte[] png) {
        return new MockMultipartFile("signature", "signature.png", "image/png", png);
    }

    /** The REAL public accept: token-only (NO session), exactly one PNG {@code signature} part. */
    private void acceptPublicly(String token) throws Exception {
        clearJpaCache();
        mockMvc.perform(multipart(publicQuoteUrl(token) + "/accept").file(signaturePart(ONE_PIXEL_PNG)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"))
                .andExpect(jsonPath("$.message").value("Quote accepted."));
        clearJpaCache();
    }

    /**
     * Invoice-ready order whose itemised 250.00 / 275.00 quote v1 is issued while the tenant SOFT terms
     * are {@code softTermsAtIssue} ({@code null} = no terms, so the quote freezes a NULL
     * terms_snapshot), then publicly accepted.
     */
    private long acceptedQuoteOrder(String softTermsAtIssue) throws Exception {
        long orderId = invoiceReadyOrder();
        if (softTermsAtIssue == null) {
            clearTenantTerms();
        } else {
            setTenantTerms(softTermsAtIssue, LIVE_HARD_TERMS_RAW);
        }
        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId));
        Assertions.assertEquals("ACCEPTED", quoteVersionRow(orderId, 1).get("status"),
                "precondition: quote v1 accepted through the real public endpoint");
        return orderId;
    }

    /**
     * POST .../quote/create-invoice (Path A, 16G PR 3) with the mandatory {@code {"quote_version_id": N}}, N =
     * the id of the order's latest ACCEPTED quote version read from the database: 201 + the delivered Path A
     * message. Every call also proves the post-commit email: exactly ONE more invoice email - the appended
     * version's stored PDF byte for byte, to the trimmed saved customer email - and no failed attempt, no
     * quote email, no store notification and no SMS; and (this transactional context) the success stamp on
     * the appended version, the dashboard mirror and the response.
     */
    private MvcResult createInvoiceFromQuote(long orderId) throws Exception {
        long acceptedQuoteVersionId = latestAcceptedQuoteVersionId(orderId);
        SenderCounts before = senderCounts();
        clearJpaCache();
        MvcResult result = mockMvc.perform(post(quoteUrl(orderId, "create-invoice")).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quote_version_id\": " + acceptedQuoteVersionId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(CREATED_FROM_QUOTE_MESSAGE))
                .andReturn();
        clearJpaCache();
        int versionNumber = readJson(result).path("data").path("invoice").path("version_number").asInt();
        Assertions.assertEquals(before.plusOneInvoiceEmail(), senderCounts(),
                "Path A sends exactly one invoice email (its signed PDF) and nothing else");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        assertSignedPdfEmail(orderId, versionNumber, sent.get(sent.size() - 1), "Path A");
        assertBothMarkersStamped(orderId, versionNumber, result, "Path A");
        return result;
    }

    /** The id of the order's LATEST ACCEPTED quote version (highest version_number among ACCEPTED). */
    private long latestAcceptedQuoteVersionId(long orderId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT quote_version_id FROM quote_version WHERE order_id = ? AND status = 'ACCEPTED' "
                        + "ORDER BY version_number DESC LIMIT 1", Long.class, orderId);
        Assertions.assertNotNull(id, "precondition: the order has an ACCEPTED quote version");
        return id;
    }

    /** The order's CURRENT (highest) invoice version_number. */
    private int currentInvoiceVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "precondition: the order has an invoice");
        return version;
    }

    /** How many messages every recording sender holds (sent and failed): what one request sent is the difference. */
    private record SenderCounts(int invoiceSent, int invoiceFailed, int quoteSent, int quoteFailed,
                                int notificationsSent, int notificationsFailed, int smsSent, int smsFailed) {

        /** The same counts plus exactly one more delivered invoice email. */
        SenderCounts plusOneInvoiceEmail() {
            return new SenderCounts(invoiceSent + 1, invoiceFailed, quoteSent, quoteFailed,
                    notificationsSent, notificationsFailed, smsSent, smsFailed);
        }
    }

    private SenderCounts senderCounts() {
        return new SenderCounts(
                invoiceEmailSender.sentEmails().size(), invoiceEmailSender.failedEmails().size(),
                quoteEmailSender.sentEmails().size(), quoteEmailSender.failedEmails().size(),
                acceptanceNotificationSender.sentNotifications().size(),
                acceptanceNotificationSender.failedNotifications().size(),
                smsSender.sentMessages().size(), smsSender.failedMessages().size());
    }

    /**
     * One signed-PDF invoice email (16G contract 6.1): to the trimmed saved customer email, the standard
     * subject and body, and the stored PDF of invoice v{@code versionNumber} attached byte for byte under
     * its own file name.
     */
    private void assertSignedPdfEmail(long orderId, int versionNumber, InvoiceEmailRequest email, String label)
            throws IOException {
        String orderNumber = orderNumber(orderId);
        String businessName = businessName();
        String savedEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM order_customer WHERE order_id = ?", String.class, orderId);
        Assertions.assertNotNull(savedEmail, "precondition: a saved customer email");
        Assertions.assertEquals(savedEmail.trim(), email.recipientEmail(), label + ": the trimmed saved customer email");
        Assertions.assertEquals("Your invoice from " + businessName, email.subject(), label + ": email subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + orderNumber + " from " + businessName
                + " attached.\n\nThank you.", email.bodyText(), label + ": email body");
        Assertions.assertArrayEquals(storedFileBytes(invoiceRow(orderId, versionNumber).get("stored_file_id")),
                email.pdfBytes(), label + ": the attachment is invoice v" + versionNumber + "'s stored PDF, byte for byte");
        Assertions.assertEquals("invoice-" + orderNumber + "-v" + versionNumber + ".pdf", email.pdfFileName(),
                label + ": attachment file name");
        Assertions.assertEquals(orderId, email.orderId(), label + ": email order id");
        Assertions.assertEquals(versionNumber, email.invoiceVersionNumber(), label + ": email invoice version");
    }

    /**
     * Delivered and stamped (this transactional context): invoice v{@code versionNumber}'s last_emailed_at
     * is set, the sales_order mirror carries the SAME value (section 11.1), and the response's
     * last_emailed_at shows it (formatted to the second).
     */
    private void assertBothMarkersStamped(long orderId, int versionNumber, MvcResult result, String label)
            throws Exception {
        Object stamp = invoiceRow(orderId, versionNumber).get("last_emailed_at");
        Assertions.assertNotNull(stamp, label + ": the appended version's last_emailed_at is stamped");
        Timestamp invoiceStamp = (Timestamp) stamp;
        Assertions.assertEquals(invoiceStamp, orderLastEmailedAt(orderId),
                label + ": the dashboard mirror carries the same stamp");
        JsonNode responseStamp = readJson(result).path("data").path("invoice").path("last_emailed_at");
        Assertions.assertTrue(responseStamp.isTextual(),
                () -> label + ": the response's last_emailed_at must be the stamp but was " + responseStamp);
        Assertions.assertEquals(invoiceStamp.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(responseStamp.asText()), label + ": the response's last_emailed_at");
    }

    /** {@link #acceptedQuoteOrder} + Path A: the order's invoice v1 is the quote-origin invoice. */
    private long pathAOrder(String softTermsAtIssue) throws Exception {
        long orderId = acceptedQuoteOrder(softTermsAtIssue);
        createInvoiceFromQuote(orderId);
        Assertions.assertEquals(1, invoiceCount(orderId), "precondition: Path A created invoice v1");
        Assertions.assertNotNull(invoiceRow(orderId, 1).get("source_quote_version_id"),
                "precondition: invoice v1 is quote-sourced");
        return orderId;
    }

    /** D.7 record a CASH payment; returns the response JSON (payment_transaction id is read from the DB). */
    private JsonNode recordCashPayment(long orderId, String amount) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(post(paymentsUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(PAYMENT_RECORDED_MESSAGE))
                .andReturn();
        clearJpaCache();
        return readJson(result);
    }

    private long latestPaymentId(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT payment_transaction_id FROM payment_transaction WHERE order_id = ? "
                        + "ORDER BY payment_transaction_id DESC LIMIT 1", Long.class, orderId);
    }

    /** D.10 (payments) soft-void a payment (no body); returns the response JSON. */
    private JsonNode voidPayment(long orderId, long paymentId) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(post(paymentsUrl(orderId) + "/" + paymentId + "/void")
                        .session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(PAYMENT_VOIDED_MESSAGE))
                .andReturn();
        clearJpaCache();
        return readJson(result);
    }

    /**
     * A valid D.8 request (16G PR 3): the signature PNG + the mandatory expected_version_number = the order's
     * CURRENT invoice version, read from the database when the request is built. No name part: the accepted
     * name is the current version's frozen customer_name_snapshot.
     */
    private MockMultipartHttpServletRequestBuilder signInvoice(long orderId) {
        return (MockMultipartHttpServletRequestBuilder) multipart(invoiceAcceptUrl(orderId))
                .file(signaturePart(ONE_PIXEL_PNG))
                .param("expected_version_number", String.valueOf(currentInvoiceVersion(orderId)));
    }

    /** Bare quote_version row (QuoteMigrationConstraintsTest shape): enough to be a referenced source. */
    private long insertBareQuoteVersion(long orderId, int versionNumber, String status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO quote_version (order_id, version_number, status, itemised, quote_total_ex_gst, "
                        + " quote_total_inc_gst, flooring_type_snapshot, created_by_user_id) "
                        + "VALUES (?, ?, ?, TRUE, 100.00, 110.00, 'SOFT', ?) RETURNING quote_version_id",
                Long.class, orderId, versionNumber, status, USER_LIAM);
    }

    /** A stored_file row for an SQL-seeded invoice PDF. No physical file: D.8 and D.7 never read it. */
    private long insertSeededInvoicePdfRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                        + "VALUES (?, ?, 'application/pdf', 1024) RETURNING stored_file_id",
                Long.class,
                "invoice-" + orderNumber(orderId) + "-v" + versionNumber + ".pdf",
                orderStoragePrefix(orderId) + "qinvl-seeded-invoice-v" + versionNumber + ".pdf");
    }

    /**
     * SQL-seed an UNSIGNED invoice version carrying the given V19 source + terms, with its own
     * stored_file row (250.00 ex / 275.00 inc, nothing paid, due 2026-11-29).
     */
    private void seedUnsignedQuoteSourcedInvoice(long orderId, int versionNumber, long sourceQuoteVersionId,
                                                 String termsSnapshot) {
        seedUnsignedQuoteSourcedInvoice(orderId, versionNumber, sourceQuoteVersionId, termsSnapshot, null);
    }

    /**
     * As above, with the V20 frozen Invoice To name {@code customerNameSnapshot} (null = none); both billing
     * lines stay NULL.
     */
    private void seedUnsignedQuoteSourcedInvoice(long orderId, int versionNumber, long sourceQuoteVersionId,
                                                 String termsSnapshot, String customerNameSnapshot) {
        long storedFileId = insertSeededInvoicePdfRow(orderId, versionNumber);
        jdbcTemplate.update(
                "INSERT INTO invoice (order_id, version_number, invoice_date, due_date, details_of_sale_snapshot, "
                        + "sale_price_ex_gst, sale_price_inc_gst, total_paid, balance_due, stored_file_id, "
                        + "created_by_user_id, source_quote_version_id, terms_snapshot, customer_name_snapshot) "
                        + "VALUES (?, ?, CURRENT_DATE, DATE '2026-11-29', ?, 250.00, 275.00, 0.00, 275.00, ?, ?, ?, ?, ?)",
                orderId, versionNumber, SEEDED_INVOICE_DETAILS, storedFileId, USER_LIAM,
                sourceQuoteVersionId, termsSnapshot, customerNameSnapshot);
        clearJpaCache();
    }

    /** Drop the email NOT NULL + format CHECK (transactional DDL, rolled back with the test). */
    private void relaxCustomerEmailDbConstraints() {
        jdbcTemplate.execute("ALTER TABLE order_customer DROP CONSTRAINT chk_order_customer_email_format");
        jdbcTemplate.execute("ALTER TABLE order_customer ALTER COLUMN email DROP NOT NULL");
    }

    // ================================================================
    // Helpers - DB probes and disk
    // ================================================================

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private int invoiceCount(long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice WHERE order_id = ?", Integer.class, orderId);
    }

    private Map<String, Object> quoteVersionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private long quoteVersionId(long orderId, int versionNumber) {
        return longValue(quoteVersionRow(orderId, versionNumber), "quote_version_id");
    }

    /** The token row behind a plaintext token (only its SHA-256 hash is stored). */
    private Map<String, Object> quoteTokenRow(String plainToken) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = HexFormat.of().formatHex(digest.digest(plainToken.getBytes(StandardCharsets.UTF_8)));
        return jdbcTemplate.queryForMap("SELECT * FROM quote_token WHERE token_hash = ?", hash);
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_number FROM sales_order WHERE order_id = ?",
                String.class, orderId);
    }

    private Timestamp orderLastEmailedAt(long orderId) {
        return jdbcTemplate.queryForObject("SELECT last_emailed_at FROM sales_order WHERE order_id = ?",
                Timestamp.class, orderId);
    }

    private String businessName() {
        return jdbcTemplate.queryForObject("SELECT name FROM business WHERE business_id = ?",
                String.class, BUSINESS_AUSSIE);
    }

    private static String orderStoragePrefix(long orderId) {
        return "/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/";
    }

    private int storedFileCountForOrder(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ?", Integer.class,
                orderStoragePrefix(orderId) + "%");
    }

    private Map<String, Object> storedFileRow(Object storedFileId) {
        Assertions.assertNotNull(storedFileId, "the stored_file reference must be set");
        return jdbcTemplate.queryForMap("SELECT * FROM stored_file WHERE stored_file_id = ?",
                ((Number) storedFileId).longValue());
    }

    /** Physical file of a stored_file row: base-dir + storage_path (which starts with "/uploads/"). */
    private Path diskPath(String storagePath) {
        Assertions.assertTrue(storagePath.startsWith("/uploads/"), () -> "unexpected storage_path " + storagePath);
        return Path.of(storageBaseDir).toAbsolutePath().normalize().resolve(storagePath.substring(1)).normalize();
    }

    /** The bytes on disk behind a stored_file row (and the row's file_size matches them). */
    private byte[] storedFileBytes(Object storedFileId) throws IOException {
        Map<String, Object> row = storedFileRow(storedFileId);
        Path file = diskPath((String) row.get("storage_path"));
        Assertions.assertTrue(Files.isRegularFile(file), () -> "expected a file on disk at " + file);
        byte[] bytes = Files.readAllBytes(file);
        Assertions.assertEquals(longValue(row, "file_size"), bytes.length,
                () -> "stored_file.file_size must match the bytes on disk for " + row.get("file_name"));
        return bytes;
    }

    private static long longValue(Map<String, Object> row, String column) {
        Object value = row.get(column);
        Assertions.assertNotNull(value, () -> column + " must not be null");
        return ((Number) value).longValue();
    }

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertNotNull(actual, () -> label + " must be present");
        BigDecimal value = actual instanceof JsonNode node ? node.decimalValue() : (BigDecimal) actual;
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(value),
                () -> label + ": expected " + expected + " but was " + value);
    }

    /**
     * The quote-source and acceptance columns of {@code carried} equal those of {@code origin}, verbatim
     * (the V19 pair plus accepted_at, accepted_customer_name and accepted_signature_file_id).
     */
    private static void assertCarriedQuoteSourceAndAcceptance(Map<String, Object> origin, Map<String, Object> carried,
                                                              String label) {
        for (String column : List.of("source_quote_version_id", "terms_snapshot", "accepted_at",
                "accepted_customer_name", "accepted_signature_file_id")) {
            Assertions.assertNotNull(origin.get(column), () -> "precondition: the Path A row has " + column);
            Assertions.assertEquals(origin.get(column), carried.get(column),
                    () -> label + ": " + column + " must be carried verbatim from the Path A row");
        }
    }

    // 16G PR 2 (#125): the Invoice To words. quote_version (V17, frozen at issue) and invoice (V20, frozen
    // or copied by each writer) use the same three column names, which are also the InvoiceDetail keys.
    private static final List<String> INVOICE_TO_COLUMNS = List.of(
            "customer_name_snapshot", "customer_address_line1_snapshot", "customer_address_line2_snapshot");

    /** The row's three Invoice To columns hold exactly these words ({@code null} = SQL NULL). */
    private static void assertRowInvoiceTo(Map<String, Object> row, String name, String line1, String line2,
                                           String label) {
        Assertions.assertEquals(name, row.get("customer_name_snapshot"), () -> label + ": customer_name_snapshot");
        Assertions.assertEquals(line1, row.get("customer_address_line1_snapshot"),
                () -> label + ": customer_address_line1_snapshot");
        Assertions.assertEquals(line2, row.get("customer_address_line2_snapshot"),
                () -> label + ": customer_address_line2_snapshot");
    }

    /** {@code copy}'s three Invoice To columns equal {@code origin}'s verbatim (a quote_version or invoice row). */
    private static void assertSameInvoiceTo(Map<String, Object> origin, Map<String, Object> copy, String label) {
        for (String column : INVOICE_TO_COLUMNS) {
            Assertions.assertTrue(origin.containsKey(column) && copy.containsKey(column),
                    () -> label + ": both rows must expose " + column);
            Assertions.assertEquals(origin.get(column), copy.get(column),
                    () -> label + ": " + column + " must be copied verbatim");
        }
    }

    // ================================================================
    // Helpers - JSON and PDF assertions
    // ================================================================

    private static JsonNode readJson(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            names.add(property.getKey());
        }
        return names;
    }

    /** {@code data.invoice} of an InvoiceDetail response: exactly the InvoiceDetail keys, nothing internal. */
    private static JsonNode invoiceDetail(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode invoice = JSON.readTree(body).path("data").path("invoice");
        Assertions.assertTrue(invoice.isObject(), () -> "data.invoice must be an object: " + body);
        Assertions.assertEquals(new TreeSet<>(INVOICE_DETAIL_KEYS), fieldNames(invoice),
                "InvoiceDetail always carries exactly its keys (the terms and Invoice To keys included)");
        for (String internal : List.of("source_quote_version_id", "stored_file", "accepted_signature_file_id",
                "signed_pdf_file_id", "storage_path", "/uploads/")) {
            Assertions.assertFalse(body.contains(internal), () -> "internal '" + internal + "' leaked: " + body);
        }
        return invoice;
    }

    /** terms_source QUOTE with terms_html == {@code expectedTermsHtml} (null = present-but-null). */
    private static void assertQuoteTerms(JsonNode invoice, String expectedTermsHtml) {
        Assertions.assertEquals("QUOTE", invoice.get("terms_source").asText(), "terms_source");
        assertTermsHtml(invoice, expectedTermsHtml);
    }

    /**
     * terms_source BUSINESS (no source quote version: the business's per-type terms, frozen into the
     * version at Create / Rewrite) with terms_html == {@code expectedTermsHtml} (null = present-but-null).
     */
    private static void assertBusinessTerms(JsonNode invoice, String expectedTermsHtml) {
        Assertions.assertEquals("BUSINESS", invoice.get("terms_source").asText(), "terms_source");
        assertTermsHtml(invoice, expectedTermsHtml);
    }

    private static void assertTermsHtml(JsonNode invoice, String expectedTermsHtml) {
        Assertions.assertTrue(invoice.has("terms_html"), "terms_html is always present");
        if (expectedTermsHtml == null) {
            Assertions.assertTrue(invoice.get("terms_html").isNull(),
                    () -> "terms_html must be JSON null but was " + invoice.get("terms_html"));
        } else {
            Assertions.assertTrue(invoice.get("terms_html").isTextual(),
                    () -> "terms_html must be a string but was " + invoice.get("terms_html"));
            Assertions.assertEquals(expectedTermsHtml, invoice.get("terms_html").asText(), "terms_html");
        }
    }

    private static void assertPresentAndNull(JsonNode node, String key) {
        Assertions.assertTrue(node.has(key) && node.get(key).isNull(),
                () -> key + " must be present and null but was " + node.get(key));
    }

    /** InvoiceDetail's three Invoice To keys (16G PR 2): each exactly the expected text, or present-but-null. */
    private static void assertInvoiceTo(JsonNode invoice, String name, String line1, String line2, String label) {
        String[] expected = {name, line1, line2};
        for (int i = 0; i < INVOICE_TO_COLUMNS.size(); i++) {
            String key = INVOICE_TO_COLUMNS.get(i);
            String value = expected[i];
            if (value == null) {
                assertPresentAndNull(invoice, key);
            } else {
                Assertions.assertTrue(invoice.path(key).isTextual(),
                        () -> label + ": " + key + " must be a string but was " + invoice.get(key));
                Assertions.assertEquals(value, invoice.get(key).asText(), () -> label + ": " + key);
            }
        }
    }

    private static void assertCurrentInvoiceSummaryUnchanged(JsonNode response, String label) {
        JsonNode currentInvoice = response.path("data").path("current_invoice");
        Assertions.assertTrue(currentInvoice.isObject(), () -> label + ": data.current_invoice must be an object");
        Assertions.assertEquals(new TreeSet<>(CURRENT_INVOICE_SUMMARY_KEYS), fieldNames(currentInvoice),
                () -> label + ": CurrentInvoiceSummary keys are unchanged (no terms or Invoice To keys)");
    }

    private static void assertPdfMagic(byte[] pdf, String label) {
        Assertions.assertNotNull(pdf, label);
        Assertions.assertTrue(pdf.length > 500, () -> label + ": PDF unexpectedly small: " + pdf.length + " bytes");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII), label + ": PDF magic");
    }

    private static String pdfText(byte[] pdf) throws IOException {
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

    // PDFBox can inject or drop spaces between glyphs and wraps text, so comparisons ignore whitespace.
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

    /**
     * Terms on a dedicated page 2 showing {@code expectedTermsText} under the uppercase "TERMS" heading;
     * page 1 carries no terms; the footer renders exactly once; none of {@code mustNotRender} appears.
     */
    private static void assertTermsOnPageTwo(byte[] pdf, String expectedTermsText, String label,
                                             String... mustNotRender) throws IOException {
        assertPdfMagic(pdf, label);
        Assertions.assertEquals(2, pageCount(pdf), () -> label + ": terms render on a dedicated second page");
        String page1 = noSpace(pageText(pdf, 1));
        String page2 = noSpace(pageText(pdf, 2));
        Assertions.assertFalse(page1.contains("TERMS"), () -> label + ": page 1 never carries terms: " + page1);
        Assertions.assertTrue(page2.contains("TERMS"), () -> label + ": page 2 carries the terms heading: " + page2);
        Assertions.assertTrue(page2.contains(noSpace(expectedTermsText)),
                () -> label + ": page 2 must show '" + expectedTermsText + "': " + page2);
        String all = noSpace(pdfText(pdf));
        for (String absent : mustNotRender) {
            Assertions.assertFalse(all.contains(noSpace(absent)),
                    () -> label + ": '" + absent + "' must not render: " + all);
        }
        Assertions.assertEquals(1, countOccurrences(all, noSpace(FOOTER_TEXT)),
                () -> label + ": the footer renders exactly once");
    }

    /** No terms at all: one page, no "TERMS", footer once, none of {@code mustNotRender} appears. */
    private static void assertNoTermsPage(byte[] pdf, String label, String... mustNotRender) throws IOException {
        assertPdfMagic(pdf, label);
        Assertions.assertEquals(1, pageCount(pdf), () -> label + ": no terms means a single page");
        String all = noSpace(pdfText(pdf));
        Assertions.assertFalse(all.contains("TERMS"), () -> label + ": no terms heading: " + all);
        for (String absent : mustNotRender) {
            Assertions.assertFalse(all.contains(noSpace(absent)),
                    () -> label + ": '" + absent + "' must not render: " + all);
        }
        Assertions.assertEquals(1, countOccurrences(all, noSpace(FOOTER_TEXT)),
                () -> label + ": the footer renders exactly once");
    }

    /**
     * 16G PR 2 (#125): page 1's Invoice To column shows exactly {@code name}, {@code line1} and
     * {@code line2}, in that order, between its "INVOICE TO" heading and the next column's "ISSUED / DUE"
     * heading (a null word renders as nothing), and none of {@code mustNotRender} appears anywhere in the
     * PDF. The block is read as a whole because a word can legitimately appear elsewhere: the accepted
     * name in the signed caption, the seeded line 2 as store 1's locality.
     */
    private static void assertPdfInvoiceTo(byte[] pdf, String name, String line1, String line2, String label,
                                           String... mustNotRender) throws IOException {
        StringBuilder words = new StringBuilder();
        for (String word : new String[] {name, line1, line2}) {
            if (word != null) {
                words.append(noSpace(word));
            }
        }
        String block = "INVOICETO" + words + "ISSUED/DUE";
        String page1 = noSpace(pageText(pdf, 1));
        Assertions.assertTrue(page1.contains(block),
                () -> label + ": the Invoice To block must read '" + block + "': " + page1);
        String all = noSpace(pdfText(pdf));
        for (String absent : mustNotRender) {
            Assertions.assertFalse(all.contains(noSpace(absent)),
                    () -> label + ": '" + absent + "' must not render: " + all);
        }
    }

    /**
     * The signed acceptance block (InvoiceAcceptanceControllerTest convention: PDFBox does not extract the
     * caption's inline spans in a fixed order, so the caption and the name are checked separately, and
     * the unsigned blank-area caption must be gone). Since 16G PR 3 the name is also checked IN the caption,
     * read in visual (position-sorted) order: "Accepted by {acceptedName} on".
     */
    private static void assertSignedCaption(byte[] pdf, String acceptedName, String label) throws IOException {
        String text = pdfText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("Accepted by"), () -> label + ": missing acceptance caption: " + text);
        Assertions.assertTrue(text.contains(acceptedName), () -> label + ": missing accepted name: " + text);
        Assertions.assertFalse(text.contains("Customer signature"),
                () -> label + ": a signed PDF must not show the blank-area caption: " + text);
        String readingOrder = noSpace(pdfTextInReadingOrder(pdf));
        Assertions.assertTrue(readingOrder.contains(noSpace("Accepted by " + acceptedName + " on")),
                () -> label + ": the caption must read 'Accepted by " + acceptedName + " on': " + readingOrder);
    }

    /** Text in VISUAL reading order (position-sorted), the order a reader sees the caption's spans in. */
    private static String pdfTextInReadingOrder(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static void assertInlineFilename(MvcResult result, String expectedFileName) {
        String header = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        Assertions.assertNotNull(header, "Content-Disposition must be set");
        ContentDisposition disposition = ContentDisposition.parse(header);
        Assertions.assertTrue(disposition.isInline(), () -> "inline disposition expected: " + header);
        Assertions.assertEquals(expectedFileName, disposition.getFilename(), header);
        Assertions.assertFalse(header.contains("/uploads/"), () -> "storage path must never leak: " + header);
    }

    /**
     * Through the autowired bean (the {@code @Repository} proxy) Spring's persistence-exception
     * translation re-throws the ownership {@link IllegalStateException} as the direct cause of an
     * {@link InvalidDataAccessApiUsageException}; assert that wrapper carries exactly
     * {@code rawFailure}'s message (the same check fired, before any insert).
     */
    private static void assertSameOwnershipFailureViaBean(InvalidDataAccessApiUsageException viaBean,
                                                          IllegalStateException rawFailure) {
        Assertions.assertInstanceOf(IllegalStateException.class, viaBean.getCause(),
                () -> "the wrapped cause must be the ownership IllegalStateException: " + viaBean);
        Assertions.assertEquals(rawFailure.getMessage(), viaBean.getCause().getMessage());
    }

    // ================================================================
    // 8a. Path A writes the selected source id, its frozen terms and its V17 Invoice To
    // ================================================================

    /**
     * Path A with populated frozen terms: the new invoice row's source_quote_version_id is the accepted
     * version id and its terms_snapshot is that version's frozen terms_snapshot verbatim, even after the
     * tenant terms change; the response says QUOTE with those terms and the PDF renders them on page 2.
     * 16G PR 2: the row's Invoice To is that version's V17 snapshot verbatim, even after the saved
     * customer is renamed and its BILLING row moved; the response and the PDF show the same words and
     * the moved-on ones nowhere.
     */
    @Test
    void pathA_withFrozenQuoteTerms_writesAcceptedVersionIdAndItsTermsSnapshotVerbatim() throws Exception {
        long orderId = acceptedQuoteOrder(QUOTE_TERMS_RAW);
        Map<String, Object> quoteV1 = quoteVersionRow(orderId, 1);
        long acceptedVersionId = longValue(quoteV1, "quote_version_id");
        String frozenTerms = (String) quoteV1.get("terms_snapshot");
        Assertions.assertNotNull(frozenTerms, "precondition: the quote froze the tenant terms at issue");
        Assertions.assertTrue(frozenTerms.contains(QUOTE_TERMS_TEXT), frozenTerms);
        assertRowInvoiceTo(quoteV1, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2,
                "precondition: the quote froze the seeded customer's V17 snapshot at issue");

        // The live tenant terms and the saved customer move on after the signature; the invoice must
        // keep the frozen ones.
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        moveSavedCustomerOn(orderId);

        JsonNode invoice = invoiceDetail(createInvoiceFromQuote(orderId));
        Assertions.assertEquals(1, invoice.get("version_number").asInt());
        assertQuoteTerms(invoice, frozenTerms);
        assertInvoiceTo(invoice, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2, "Path A response");
        Assertions.assertTrue(invoice.get("accepted_signature_present").booleanValue());

        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(acceptedVersionId, longValue(row, "source_quote_version_id"),
                "source_quote_version_id = the selected accepted quote version");
        Assertions.assertEquals(frozenTerms, row.get("terms_snapshot"),
                "terms_snapshot = the accepted version's frozen terms_snapshot, verbatim");
        assertSameInvoiceTo(quoteV1, row, "Path A row: Invoice To = the accepted version's V17 snapshot");
        Assertions.assertEquals(quoteV1, quoteVersionRow(orderId, 1), "Path A never writes the quote version");

        Map<String, Object> pdfFile = storedFileRow(row.get("stored_file_id"));
        Assertions.assertEquals("invoice-" + orderNumber(orderId) + "-v1.pdf", pdfFile.get("file_name"));
        Assertions.assertEquals("application/pdf", pdfFile.get("mime_type"));
        byte[] pdf = storedFileBytes(row.get("stored_file_id"));
        assertTermsOnPageTwo(pdf, QUOTE_TERMS_TEXT, "Path A PDF", LIVE_SOFT_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
        assertPdfInvoiceTo(pdf, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2, "Path A PDF",
                MOVED_NAME, MOVED_LINE1, MOVED_LINE2);
    }

    /**
     * Path A with frozen-null terms (no tenant terms at issue): the row still carries the source id but
     * terms_snapshot stays NULL, with no fallback to the live terms that exist by conversion time. Its
     * Invoice To is still the accepted version's V17 snapshot, never the moved-on saved customer.
     */
    @Test
    void pathA_withFrozenNullQuoteTerms_writesSourceIdAndNullTermsSnapshot_noLiveFallback() throws Exception {
        long orderId = acceptedQuoteOrder(null);
        Map<String, Object> quoteV1 = quoteVersionRow(orderId, 1);
        Assertions.assertNull(quoteV1.get("terms_snapshot"), "precondition: the quote froze NO terms");

        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        moveSavedCustomerOn(orderId);

        JsonNode invoice = invoiceDetail(createInvoiceFromQuote(orderId));
        assertQuoteTerms(invoice, null);
        assertInvoiceTo(invoice, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2, "Path A frozen-null response");

        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(longValue(quoteV1, "quote_version_id"), longValue(row, "source_quote_version_id"));
        Assertions.assertTrue(row.containsKey("terms_snapshot"));
        Assertions.assertNull(row.get("terms_snapshot"), "frozen null terms stay NULL on the invoice row");
        assertSameInvoiceTo(quoteV1, row, "Path A frozen-null row: Invoice To = the V17 snapshot");
        byte[] pdf = storedFileBytes(row.get("stored_file_id"));
        assertNoTermsPage(pdf, "Path A frozen-null PDF", LIVE_SOFT_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
        assertPdfInvoiceTo(pdf, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2, "Path A frozen-null PDF",
                MOVED_NAME, MOVED_LINE1, MOVED_LINE2);
    }

    // ================================================================
    // 8b. Payment then void carry the V19 pair, the words and the acceptance verbatim
    // ================================================================

    /**
     * D.7 payment then D.10 void on a Path A invoice: v2 and v3 carry source_quote_version_id,
     * terms_snapshot, accepted_at, accepted_customer_name and accepted_signature_file_id verbatim from
     * the Path A row, their regenerated PDFs render the frozen terms (not the changed live terms), the
     * payment / void responses keep the unchanged CurrentInvoiceSummary shape and neither emails (the only
     * invoice email is Path A's own signed-PDF email, 16G PR 3).
     * 16G PR 2: both also copy the Path A row's Invoice To (the quote's V17 snapshot) verbatim, so their
     * PDFs show it and never the saved customer that was renamed and moved after Path A.
     */
    @Test
    void paymentThenVoid_onPathAInvoice_carrySourceTermsAndAcceptanceVerbatim_renderFrozenTerms() throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        // 16G PR 3: Path A emailed its own signed PDF (proven by createInvoiceFromQuote); from here on the
        // payment and the void must add no email.
        List<InvoiceEmailRequest> emailsAfterPathA = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, emailsAfterPathA.size(), "precondition: Path A's one signed-PDF email");
        Map<String, Object> pathA = invoiceRow(orderId, 1);
        assertRowInvoiceTo(pathA, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2,
                "precondition: the Path A row carries the quote's V17 Invoice To");
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        moveSavedCustomerOn(orderId);

        JsonNode paid = recordCashPayment(orderId, "50.00");
        assertCurrentInvoiceSummaryUnchanged(paid, "payment");
        Assertions.assertEquals(2, paid.path("data").path("current_invoice").path("version_number").asInt());
        Map<String, Object> v2 = invoiceRow(orderId, 2);
        assertCarriedQuoteSourceAndAcceptance(pathA, v2, "payment v2");
        assertSameInvoiceTo(pathA, v2, "payment v2");
        assertMoney("50.00", v2.get("total_paid"), "v2.total_paid");
        assertMoney("225.00", v2.get("balance_due"), "v2.balance_due");

        JsonNode voided = voidPayment(orderId, latestPaymentId(orderId));
        assertCurrentInvoiceSummaryUnchanged(voided, "void");
        Assertions.assertEquals(3, voided.path("data").path("current_invoice").path("version_number").asInt());
        Map<String, Object> v3 = invoiceRow(orderId, 3);
        assertCarriedQuoteSourceAndAcceptance(pathA, v3, "void v3");
        assertSameInvoiceTo(pathA, v3, "void v3");
        assertMoney("0.00", v3.get("total_paid"), "v3.total_paid");
        assertMoney("275.00", v3.get("balance_due"), "v3.balance_due");

        for (Map<String, Object> version : List.of(v2, v3)) {
            String label = "regenerated v" + version.get("version_number") + " PDF";
            byte[] pdf = storedFileBytes(version.get("stored_file_id"));
            assertTermsOnPageTwo(pdf, QUOTE_TERMS_TEXT, label, LIVE_SOFT_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
            assertPdfInvoiceTo(pdf, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2, label,
                    MOVED_NAME, MOVED_LINE1, MOVED_LINE2);
            assertSignedCaption(pdf, SNAPSHOT_NAME, label);
        }
        Assertions.assertEquals(pathA, invoiceRow(orderId, 1), "the Path A row itself is never modified");
        Assertions.assertEquals(emailsAfterPathA, invoiceEmailSender.sentEmails(),
                "payment and void never email (the only invoice email is Path A's own signed-PDF email)");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "payment and void never attempt an email");
    }

    // ================================================================
    // 8c. D.1 Create and D.2 Rewrite of Path A write a NULL source and freeze fresh words; D.2 also
    //     clears the acceptance
    // ================================================================

    /**
     * D.1 Create (Path B) on an order with an accepted quote, after the business's terms and the saved
     * customer both moved on from what the quote froze: NULL source, and the row freezes the sanitised
     * SOFT business terms and the saved customer's Invoice To (never the quote's terms or its V17
     * snapshot); the response reports exactly those stored words with terms_source BUSINESS, and the
     * PDF renders them.
     */
    @Test
    void d1Create_onOrderWithAcceptedQuote_writesNullSource_freezesSanitizedBusinessTermsAndSavedInvoiceTo()
            throws Exception {
        long orderId = acceptedQuoteOrder(QUOTE_TERMS_RAW);
        Map<String, Object> quoteV1 = quoteVersionRow(orderId, 1);
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        moveSavedCustomerOn(orderId);
        // SOFT order -> business.terms_soft, sanitised once and frozen (never terms_hard, never the quote's).
        String businessSoftTerms = termsSanitizer.sanitize(LIVE_SOFT_TERMS_RAW);
        Assertions.assertNotNull(businessSoftTerms, "precondition: the SOFT business terms sanitise to HTML");
        Assertions.assertNotEquals(quoteV1.get("terms_snapshot"), businessSoftTerms,
                "precondition: the quote froze different terms at issue");
        assertRowInvoiceTo(quoteV1, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2,
                "precondition: the quote froze the seeded customer's V17 snapshot");

        clearJpaCache();
        MvcResult result = mockMvc.perform(post(invoicesUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(INVOICE_CREATED_MESSAGE))
                .andReturn();
        clearJpaCache();

        JsonNode invoice = invoiceDetail(result);
        Assertions.assertEquals(1, invoice.get("version_number").asInt());
        assertBusinessTerms(invoice, businessSoftTerms);
        assertInvoiceTo(invoice, MOVED_NAME, MOVED_LINE1, MOVED_LINE2, "D.1 response");

        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertNull(row.get("source_quote_version_id"), "D.1 writes a NULL source");
        Assertions.assertEquals(businessSoftTerms, row.get("terms_snapshot"),
                "D.1 freezes the sanitised SOFT business terms, never the quote's terms");
        assertRowInvoiceTo(row, MOVED_NAME, MOVED_LINE1, MOVED_LINE2, "D.1 row: the saved customer, frozen");
        Assertions.assertNull(row.get("accepted_at"));

        byte[] pdf = storedFileBytes(row.get("stored_file_id"));
        assertTermsOnPageTwo(pdf, LIVE_SOFT_TERMS_TEXT, "D.1 PDF", QUOTE_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
        assertPdfInvoiceTo(pdf, MOVED_NAME, MOVED_LINE1, MOVED_LINE2, "D.1 PDF", SNAPSHOT_NAME, SNAPSHOT_LINE1);
    }

    /**
     * D.2 Rewrite of a Path A invoice (order not LAID), after the business's terms and the saved
     * customer moved on: the appended v2 writes a NULL source, clears accepted_at, accepted_customer_name
     * and accepted_signature_file_id, and freezes FRESH words - the sanitised current business terms and
     * the current saved customer + BILLING lines, not the quote's V17 snapshot that v1 carries; the
     * response reports those stored words with terms_source BUSINESS and the PDF renders them; the Path A
     * row stays as it was (append-only).
     */
    @Test
    void d2Rewrite_ofPathAInvoice_writesNullSource_clearsAcceptance_freezesFreshBusinessTermsAndSavedInvoiceTo()
            throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        Map<String, Object> pathA = invoiceRow(orderId, 1);
        Assertions.assertNotNull(pathA.get("accepted_at"), "precondition: the Path A invoice is signed");
        assertRowInvoiceTo(pathA, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2,
                "precondition: the Path A row carries the quote's V17 Invoice To");
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        moveSavedCustomerOn(orderId);
        String businessSoftTerms = termsSanitizer.sanitize(LIVE_SOFT_TERMS_RAW);
        Assertions.assertNotNull(businessSoftTerms, "precondition: the SOFT business terms sanitise to HTML");

        clearJpaCache();
        MvcResult result = mockMvc.perform(post(invoicesUrl(orderId) + "/rewrite").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(INVOICE_REWRITTEN_MESSAGE))
                .andReturn();
        clearJpaCache();

        JsonNode invoice = invoiceDetail(result);
        Assertions.assertEquals(2, invoice.get("version_number").asInt());
        assertBusinessTerms(invoice, businessSoftTerms);
        assertInvoiceTo(invoice, MOVED_NAME, MOVED_LINE1, MOVED_LINE2, "D.2 response");
        assertPresentAndNull(invoice, "accepted_at");
        assertPresentAndNull(invoice, "accepted_customer_name");
        assertPresentAndNull(invoice, "accepted_signature_download_path");
        Assertions.assertFalse(invoice.get("accepted_signature_present").booleanValue());

        Map<String, Object> v2 = invoiceRow(orderId, 2);
        for (String column : List.of("source_quote_version_id", "accepted_at", "accepted_customer_name",
                "accepted_signature_file_id")) {
            Assertions.assertNull(v2.get(column), () -> "the rewrite clears " + column);
        }
        Assertions.assertEquals(businessSoftTerms, v2.get("terms_snapshot"),
                "the rewrite freezes the business's current sanitised SOFT terms, not the quote's");
        assertRowInvoiceTo(v2, MOVED_NAME, MOVED_LINE1, MOVED_LINE2,
                "rewrite v2 row: the current saved customer, not the quote's V17 snapshot");
        Assertions.assertEquals(pathA, invoiceRow(orderId, 1), "the Path A row stays unchanged");

        byte[] pdf = storedFileBytes(v2.get("stored_file_id"));
        assertTermsOnPageTwo(pdf, LIVE_SOFT_TERMS_TEXT, "rewrite v2 PDF", QUOTE_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
        assertPdfInvoiceTo(pdf, MOVED_NAME, MOVED_LINE1, MOVED_LINE2, "rewrite v2 PDF",
                SNAPSHOT_NAME, SNAPSHOT_LINE1);
    }

    // ================================================================
    // 8d. D.8 carries the V19 pair defensively and renders the row's frozen terms
    // ================================================================

    /**
     * D.8 defensive carry: an UNSIGNED invoice v1 seeded with this order's real accepted quote version as
     * its source and "Seeded frozen terms D8" as terms_snapshot. With different live terms (and different
     * quote terms), D.8 appends v2 carrying the same source + terms, answers QUOTE with the seeded terms,
     * and the signed PDF's page 2 shows the seeded frozen terms only. 16G PR 2: the seeded v1 has no
     * billing lines (its V20 line columns are NULL), and D.8 copies them as NULL - the row, the response and
     * the PDF's Invoice To carry no billing line and the saved customer's billing line is never substituted.
     * 16G PR 3: D.8 takes its accepted name from the current version's frozen customer_name_snapshot (a null
     * or blank one is refused with 422), so the seeded v1 carries a frozen name that differs from the saved
     * customer's; D.8 copies it into Invoice To AND accepts with it verbatim (row, response, caption), and
     * the saved customer's name renders nowhere in the signed PDF.
     */
    @Test
    void d8Accept_onUnsignedQuoteSourcedInvoice_carriesSourceAndFrozenTerms_signedPdfShowsSeededTerms()
            throws Exception {
        long orderId = acceptedQuoteOrder(QUOTE_TERMS_RAW);
        long acceptedVersionId = quoteVersionId(orderId, 1);
        long quoteSignatureFileId = longValue(quoteVersionRow(orderId, 1), "accepted_signature_file_id");
        seedUnsignedQuoteSourcedInvoice(orderId, 1, acceptedVersionId, D8_SEEDED_TERMS, D8_SEEDED_NAME);
        assertRowInvoiceTo(invoiceRow(orderId, 1), D8_SEEDED_NAME, null, null,
                "precondition: the SQL-seeded v1 carries a frozen name and no billing lines");
        Assertions.assertNotEquals(SNAPSHOT_NAME, D8_SEEDED_NAME,
                "fixture: the frozen name differs from the saved customer's name");
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);

        clearJpaCache();
        MvcResult result = mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAILED_MESSAGE))
                .andReturn();
        clearJpaCache();

        JsonNode invoice = invoiceDetail(result);
        Assertions.assertEquals(2, invoice.get("version_number").asInt());
        assertQuoteTerms(invoice, D8_SEEDED_TERMS);
        assertInvoiceTo(invoice, D8_SEEDED_NAME, null, null, "D.8 response");
        Assertions.assertEquals(D8_SEEDED_NAME, invoice.get("accepted_customer_name").asText(),
                "the accepted name is v1's frozen name, never the saved customer's");

        Map<String, Object> v2 = invoiceRow(orderId, 2);
        Assertions.assertEquals(acceptedVersionId, longValue(v2, "source_quote_version_id"),
                "D.8 carries the current row's source verbatim");
        Assertions.assertEquals(D8_SEEDED_TERMS, v2.get("terms_snapshot"), "D.8 carries the current row's terms");
        assertRowInvoiceTo(v2, D8_SEEDED_NAME, null, null,
                "D.8 v2 row: the current row's words, its null billing lines included, copied");
        Assertions.assertNotNull(v2.get("accepted_at"));
        Assertions.assertEquals(D8_SEEDED_NAME, v2.get("accepted_customer_name"),
                "the stored accepted name is the frozen name verbatim");
        Assertions.assertNotEquals(quoteSignatureFileId, longValue(v2, "accepted_signature_file_id"),
                "D.8 stores its own new signature (it does not inherit the quote's)");
        Assertions.assertNull(invoiceRow(orderId, 1).get("accepted_at"), "the seeded v1 stays unsigned");

        byte[] signedPdf = storedFileBytes(v2.get("stored_file_id"));
        assertTermsOnPageTwo(signedPdf, D8_SEEDED_TERMS_TEXT, "D.8 signed PDF",
                LIVE_SOFT_TERMS_TEXT, LIVE_HARD_TERMS_TEXT, QUOTE_TERMS_TEXT);
        // The saved customer's name and billing line render nowhere (neither in Invoice To nor in the caption).
        assertPdfInvoiceTo(signedPdf, D8_SEEDED_NAME, null, null, "D.8 signed PDF", SNAPSHOT_LINE1, SNAPSHOT_NAME);
        assertSignedCaption(signedPdf, D8_SEEDED_NAME, "D.8 signed PDF");
        Assertions.assertEquals(2, countOccurrences(noSpace(pdfText(signedPdf)), noSpace(D8_SEEDED_NAME)),
                "the frozen name renders twice: Invoice To and the acceptance caption");

        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "D.8 auto-emails the signed PDF once");
        Assertions.assertArrayEquals(signedPdf, sent.get(0).pdfBytes(), "the email attaches the stored signed PDF");
    }

    // ================================================================
    // 8e. The shared write boundary: insertInvoice verifies source ownership
    // ================================================================

    private static InvoiceRow insertDirect(InvoiceRepository repository, long orderId, long storedFileId,
                                           long sourceQuoteVersionId, String termsSnapshot) {
        return insertDirect(repository, orderId, storedFileId, sourceQuoteVersionId, termsSnapshot,
                null, null, null);
    }

    /** As above, with the 16G PR 2 Invoice To words (the three trailing insertInvoice arguments). */
    private static InvoiceRow insertDirect(InvoiceRepository repository, long orderId, long storedFileId,
                                           long sourceQuoteVersionId, String termsSnapshot,
                                           String customerNameSnapshot, String customerAddressLine1Snapshot,
                                           String customerAddressLine2Snapshot) {
        return repository.insertInvoice(orderId, 1, LocalDate.now(), LocalDate.of(2026, 11, 29),
                "QINVL direct insert details", new BigDecimal("100.00"), new BigDecimal("110.00"),
                new BigDecimal("0.00"), new BigDecimal("110.00"), storedFileId, USER_LIAM,
                null, null, null, null, sourceQuoteVersionId, termsSnapshot,
                customerNameSnapshot, customerAddressLine1Snapshot, customerAddressLine2Snapshot);
    }

    /**
     * insertInvoice for order A with order B's quote_version as the source, or with a quote_version id
     * that does not exist, throws IllegalStateException BEFORE the insert (order A's invoice count stays
     * 0). The repository method itself throws the IllegalStateException; through the autowired Spring
     * bean the same failure arrives wrapped by the @Repository exception translation. With A's own
     * accepted version the insert succeeds and the returned and persisted row carry that source id and
     * (16G PR 2) the Invoice To words passed in, verbatim.
     */
    @Test
    void insertInvoice_crossOrderOrMissingSource_throwsIllegalStateBeforeInsert_ownSourceSucceeds()
            throws Exception {
        long orderA = acceptedQuoteOrder(null);
        long ownVersionId = quoteVersionId(orderA, 1);
        long orderB = insertOrder("LEAD");
        long foreignVersionId = insertBareQuoteVersion(orderB, 1, "ACCEPTED");
        long missingVersionId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(quote_version_id), 0) + 1000 FROM quote_version", Long.class);
        long storedFileId = insertSeededInvoicePdfRow(orderA, 1);
        Assertions.assertEquals(0, invoiceCount(orderA), "precondition: order A has no invoice");

        // The autowired bean is the @Repository proxy; the raw target is the repository method itself.
        Assertions.assertTrue(AopUtils.isAopProxy(invoiceRepository), "the autowired repository is a proxy");
        InvoiceRepository rawRepository = AopTestUtils.getUltimateTargetObject(invoiceRepository);
        Assertions.assertFalse(AopUtils.isAopProxy(rawRepository), "the raw repository is not a proxy");

        // Cross-order source: the method throws IllegalStateException before inserting anything ...
        IllegalStateException crossOrder = Assertions.assertThrows(IllegalStateException.class,
                () -> insertDirect(rawRepository, orderA, storedFileId, foreignVersionId, "<p>t</p>"));
        Assertions.assertTrue(crossOrder.getMessage().contains("quote_version " + foreignVersionId),
                crossOrder.getMessage());
        Assertions.assertEquals(0, invoiceCount(orderA), "no row is inserted for a cross-order source");
        // ... and through the Spring bean the same ownership failure surfaces (translated wrapper).
        InvalidDataAccessApiUsageException crossOrderViaBean = Assertions.assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> insertDirect(invoiceRepository, orderA, storedFileId, foreignVersionId, "<p>t</p>"));
        assertSameOwnershipFailureViaBean(crossOrderViaBean, crossOrder);
        Assertions.assertEquals(0, invoiceCount(orderA), "no row is inserted for a cross-order source (bean)");

        // Non-existent source.
        IllegalStateException missing = Assertions.assertThrows(IllegalStateException.class,
                () -> insertDirect(rawRepository, orderA, storedFileId, missingVersionId, "<p>t</p>"));
        Assertions.assertTrue(missing.getMessage().contains("quote_version " + missingVersionId),
                missing.getMessage());
        InvalidDataAccessApiUsageException missingViaBean = Assertions.assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> insertDirect(invoiceRepository, orderA, storedFileId, missingVersionId, "<p>t</p>"));
        assertSameOwnershipFailureViaBean(missingViaBean, missing);
        Assertions.assertEquals(0, invoiceCount(orderA), "no row is inserted for a missing source");

        // Control: order A's own accepted version passes the same boundary, and the Invoice To words
        // (distinct from the saved customer's) round-trip verbatim.
        String directName = "QINVL Direct Insert Name";
        String directLine1 = "9/31 Direct Insert Lane";
        String directLine2 = "Direct Town NSW 2999";
        InvoiceRow row = insertDirect(invoiceRepository, orderA, storedFileId, ownVersionId, "<p>Own terms</p>",
                directName, directLine1, directLine2);
        Assertions.assertEquals(ownVersionId, row.sourceQuoteVersionId());
        Assertions.assertEquals("<p>Own terms</p>", row.termsSnapshot());
        Assertions.assertEquals(directName, row.customerNameSnapshot());
        Assertions.assertEquals(directLine1, row.customerAddressLine1Snapshot());
        Assertions.assertEquals(directLine2, row.customerAddressLine2Snapshot());
        Assertions.assertEquals(orderA, row.orderId());
        Assertions.assertEquals(1, invoiceCount(orderA));
        Map<String, Object> persisted = invoiceRow(orderA, 1);
        Assertions.assertEquals(ownVersionId, longValue(persisted, "source_quote_version_id"));
        Assertions.assertEquals("<p>Own terms</p>", persisted.get("terms_snapshot"));
        assertRowInvoiceTo(persisted, directName, directLine1, directLine2, "direct insert row");
    }

    /**
     * Via HTTP: a payment on an order whose current invoice was SQL-seeded with ANOTHER order's quote
     * version as its source (the single-column FK allows it) answers 500 INTERNAL_SERVER_ERROR with the
     * generic message only (no ids, no table names) and appends no invoice version. The control order,
     * seeded the same way with its OWN quote version, records the payment (201) and carries the source.
     */
    @Test
    void recordPayment_onInvoiceSeededWithCrossOrderSource_returnsGeneric500_ownSourceControlSucceeds()
            throws Exception {
        clearTenantTerms();

        // Control: identical seed shape, but the source is the order's own quote version.
        long controlOrder = readyOrder();
        long controlVersionId = insertBareQuoteVersion(controlOrder, 1, "ACCEPTED");
        seedUnsignedQuoteSourcedInvoice(controlOrder, 1, controlVersionId, CROSS_ORDER_SEEDED_TERMS);
        recordCashPayment(controlOrder, "10.00");
        Map<String, Object> controlV2 = invoiceRow(controlOrder, 2);
        Assertions.assertEquals(controlVersionId, longValue(controlV2, "source_quote_version_id"));
        Assertions.assertEquals(CROSS_ORDER_SEEDED_TERMS, controlV2.get("terms_snapshot"));

        // Cross-order: order A's current invoice references order B's quote version.
        long orderA = readyOrder();
        long orderB = insertOrder("LEAD");
        long foreignVersionId = insertBareQuoteVersion(orderB, 1, "ACCEPTED");
        seedUnsignedQuoteSourcedInvoice(orderA, 1, foreignVersionId, CROSS_ORDER_SEEDED_TERMS);
        Assertions.assertEquals(1, invoiceCount(orderA));

        clearJpaCache();
        MvcResult result = mockMvc.perform(post(paymentsUrl(orderA)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\": \"CASH\", \"amount\": 10.00}"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = JSON.readTree(body);
        Assertions.assertEquals(Set.of("error"), fieldNames(root), () -> "only the error envelope: " + body);
        JsonNode error = root.get("error");
        Assertions.assertEquals(new TreeSet<>(Set.of("code", "message")), fieldNames(error),
                () -> "no details on a generic 500: " + body);
        Assertions.assertEquals("INTERNAL_SERVER_ERROR", error.get("code").asText());
        Assertions.assertEquals(INTERNAL_ERROR_MESSAGE, error.get("message").asText());
        for (String leak : List.of("quote_version", "invoice", "sales_order", "source", "belongs",
                String.valueOf(foreignVersionId), String.valueOf(orderA), String.valueOf(orderB))) {
            Assertions.assertFalse(body.contains(leak), () -> "the 500 body must not leak '" + leak + "': " + body);
        }
        Assertions.assertEquals(1, invoiceCount(orderA), "the ownership check fires before any invoice insert");
    }

    // ================================================================
    // 12a. D.9 resend of a Path A invoice
    // ================================================================

    /**
     * D.9 resend on a Path A invoice: 200 with the resend message; exactly one invoice email whose PDF is
     * the stored Path A PDF verbatim, named invoice-{order}-v1.pdf; last_emailed_at stamped in place on
     * the invoice and the sales_order mirror (same value); response terms_source QUOTE and (16G PR 2) the
     * Path A row's frozen V17 Invoice To, not the saved customer renamed and moved after Path A. Since 16G
     * PR 3 Path A itself already emailed that stored PDF and stamped v1 and the mirror, so the resend
     * re-emails the same bytes and re-stamps v1 in place with its own send time.
     */
    @Test
    void d9Resend_onPathAInvoice_emailsStoredPathAPdfVerbatim_stampsInvoiceAndMirror_termsSourceQuote()
            throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        String orderNumber = orderNumber(orderId);
        Map<String, Object> pathA = invoiceRow(orderId, 1);
        byte[] pathAPdf = storedFileBytes(pathA.get("stored_file_id"));
        // 16G PR 3: Path A's own post-commit email (proven in full by createInvoiceFromQuote) sent this stored
        // PDF and stamped v1 and the mirror.
        Timestamp pathAStamp = (Timestamp) pathA.get("last_emailed_at");
        Assertions.assertNotNull(pathAStamp, "precondition: Path A's own email stamped v1");
        Assertions.assertEquals(pathAStamp, orderLastEmailedAt(orderId), "precondition: the mirror carries Path A's stamp");
        List<InvoiceEmailRequest> pathAEmails = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, pathAEmails.size(), "precondition: Path A sent exactly one invoice email");
        Assertions.assertArrayEquals(pathAPdf, pathAEmails.get(0).pdfBytes(),
                "precondition: Path A emailed its stored PDF");
        // From here on, only the resend's email.
        resetSenders();
        assertRowInvoiceTo(pathA, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2,
                "precondition: the Path A row carries the quote's V17 Invoice To");
        int storedFilesBefore = storedFileCountForOrder(orderId);
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        moveSavedCustomerOn(orderId);

        clearJpaCache();
        MvcResult result = mockMvc.perform(post(invoiceResendUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESEND_MESSAGE))
                .andReturn();
        clearJpaCache();

        JsonNode invoice = invoiceDetail(result);
        Assertions.assertEquals(1, invoice.get("version_number").asInt());
        assertQuoteTerms(invoice, (String) pathA.get("terms_snapshot"));
        assertInvoiceTo(invoice, SNAPSHOT_NAME, SNAPSHOT_LINE1, SNAPSHOT_LINE2, "D.9 response");
        Assertions.assertTrue(invoice.get("last_emailed_at").isTextual(), "last_emailed_at is stamped");

        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "exactly one invoice email");
        InvoiceEmailRequest email = sent.get(0);
        Assertions.assertArrayEquals(pathAPdf, email.pdfBytes(), "the stored Path A PDF is re-sent verbatim");
        Assertions.assertEquals("invoice-" + orderNumber + "-v1.pdf", email.pdfFileName());
        Assertions.assertEquals(1, email.invoiceVersionNumber());
        Assertions.assertEquals(orderId, email.orderId());
        Assertions.assertEquals(VALID_EMAIL, email.recipientEmail());
        Assertions.assertEquals("Your invoice from " + businessName(), email.subject());

        Timestamp invoiceStamp = (Timestamp) invoiceRow(orderId, 1).get("last_emailed_at");
        Assertions.assertNotNull(invoiceStamp, "invoice.last_emailed_at is stamped in place");
        Assertions.assertNotEquals(pathAStamp, invoiceStamp,
                "the resend re-stamps v1 in place with its own send time, replacing Path A's stamp");
        Assertions.assertEquals(invoiceStamp, orderLastEmailedAt(orderId), "the sales_order mirror equals the stamp");
        Assertions.assertEquals(1, invoiceCount(orderId), "resend appends no version");
        Assertions.assertEquals(storedFilesBefore, storedFileCountForOrder(orderId), "resend writes no file");
        Assertions.assertArrayEquals(pathAPdf, storedFileBytes(pathA.get("stored_file_id")),
                "the stored Path A PDF is untouched");
    }

    // ================================================================
    // 12b. D.10 signature download of a Path A invoice
    // ================================================================

    /**
     * D.10 on a Path A invoice streams the INHERITED quote signature file (bytes equal to the quote's
     * stored signature on disk) as image/png named by the CURRENT invoice version, also after a payment
     * appends v2.
     */
    @Test
    void d10SignatureDownload_onPathAInvoice_streamsInheritedQuoteSignature_namedByCurrentInvoiceVersion()
            throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        String orderNumber = orderNumber(orderId);
        Object quoteSignatureFileId = quoteVersionRow(orderId, 1).get("accepted_signature_file_id");
        Assertions.assertEquals(quoteSignatureFileId, invoiceRow(orderId, 1).get("accepted_signature_file_id"),
                "precondition: the Path A invoice references the quote's signature stored_file");
        Assertions.assertEquals("quote-signature-" + orderNumber + "-v1.png",
                storedFileRow(quoteSignatureFileId).get("file_name"));
        byte[] quoteSignatureOnDisk = storedFileBytes(quoteSignatureFileId);

        for (int currentVersion : new int[] {1, 2}) {
            if (currentVersion == 2) {
                recordCashPayment(orderId, "25.00");
                Assertions.assertEquals(quoteSignatureFileId,
                        invoiceRow(orderId, 2).get("accepted_signature_file_id"));
            }
            clearJpaCache();
            MvcResult download = mockMvc.perform(get(invoiceSignatureUrl(orderId)).session(liamStore1Session()))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                    .andReturn();
            byte[] streamed = download.getResponse().getContentAsByteArray();
            Assertions.assertArrayEquals(quoteSignatureOnDisk, streamed,
                    "v" + currentVersion + ": D.10 streams the quote's stored signature file verbatim");
            Assertions.assertEquals(streamed.length, download.getResponse().getContentLength());
            assertInlineFilename(download, "signature-" + orderNumber + "-v" + currentVersion + ".png");
        }
    }

    // ================================================================
    // 12c. D.8 on a Path A invoice is refused; a newer ISSUED quote keeps its live link
    // ================================================================

    /**
     * D.8 on a Path A invoice: 409 INVOICE_ALREADY_ACCEPTED with D.8's unchanged default message. The request
     * carries the CURRENT version as expected_version_number (16G PR 3), so it passes the stale check and is
     * refused by the already-accepted rule, never as INVOICE_VERSION_STALE. A newer quote v2 issued after the
     * acceptance stays ISSUED with its ACTIVE token; nothing is appended, written or emailed.
     */
    @Test
    void d8Accept_onPathAInvoice_returns409WithDefaultMessage_newerIssuedQuoteKeepsActiveLink() throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        saveNonItemisedDraft(orderId);
        String token2 = sendAndExtractToken(orderId);
        Map<String, Object> v2Before = quoteVersionRow(orderId, 2);
        Map<String, Object> tokenBefore = quoteTokenRow(token2);
        Assertions.assertEquals("ISSUED", v2Before.get("status"), "precondition: v2 issued after the acceptance");
        Assertions.assertEquals("ACTIVE", tokenBefore.get("status"), "precondition: v2's link is ACTIVE");
        Map<String, Object> pathA = invoiceRow(orderId, 1);
        int storedFilesBefore = storedFileCountForOrder(orderId);
        Assertions.assertEquals(1, currentInvoiceVersion(orderId),
                "precondition: the signed Path A v1 is current, so D.8's expected_version_number matches it");
        // Path A's own signed-PDF email (16G PR 3) is the only invoice email so far.
        List<InvoiceEmailRequest> emailsBefore = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, emailsBefore.size(), "precondition: Path A's one signed-PDF email");

        clearJpaCache();
        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVOICE_ALREADY_ACCEPTED"))
                .andExpect(jsonPath("$.error.message").value(D8_ALREADY_ACCEPTED_MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());
        clearJpaCache();

        Assertions.assertEquals(v2Before, quoteVersionRow(orderId, 2), "v2 stays ISSUED, untouched");
        Assertions.assertEquals(tokenBefore, quoteTokenRow(token2), "v2's token stays ACTIVE, untouched");
        Assertions.assertEquals(1, invoiceCount(orderId), "nothing appended");
        Assertions.assertEquals(pathA, invoiceRow(orderId, 1), "the Path A invoice is untouched");
        Assertions.assertEquals(storedFilesBefore, storedFileCountForOrder(orderId), "no file written");
        Assertions.assertEquals(emailsBefore, invoiceEmailSender.sentEmails(), "no invoice email from the refused D.8");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no invoice email attempted");
        mockMvc.perform(get(publicQuoteUrl(token2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
    }

    // ================================================================
    // 12d. D.1 after Path A
    // ================================================================

    @Test
    void d1Create_afterPathA_returns409InvoiceAlreadyExists_nothingAppended() throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        Map<String, Object> pathA = invoiceRow(orderId, 1);

        clearJpaCache();
        mockMvc.perform(post(invoicesUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVOICE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.error.message").value(INVOICE_ALREADY_EXISTS_MESSAGE));
        clearJpaCache();

        Assertions.assertEquals(1, invoiceCount(orderId));
        Assertions.assertEquals(pathA, invoiceRow(orderId, 1));
    }

    // ================================================================
    // 12e. D.2 after Path A keeps its email gate and its LAID block
    // ================================================================

    /**
     * D.2 Rewrite of a Path A invoice still requires a valid customer email (NULL email -> 422
     * CUSTOMER_EMAIL_REQUIRED) and is still blocked on a LAID order (422 ORDER_LOCKED); neither refusal
     * appends a version or writes a file.
     */
    @Test
    void d2Rewrite_afterPathA_stillRequiresCustomerEmail_andIsLockedWhenLaid() throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        Map<String, Object> pathA = invoiceRow(orderId, 1);
        int storedFilesBefore = storedFileCountForOrder(orderId);

        relaxCustomerEmailDbConstraints();
        jdbcTemplate.update("UPDATE order_customer SET email = NULL WHERE order_id = ?", orderId);
        clearJpaCache();
        mockMvc.perform(post(invoicesUrl(orderId) + "/rewrite").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"))
                .andExpect(jsonPath("$.error.message").value(CUSTOMER_EMAIL_REQUIRED_MESSAGE));
        clearJpaCache();
        Assertions.assertEquals(1, invoiceCount(orderId), "no version appended without a customer email");

        jdbcTemplate.update("UPDATE order_customer SET email = ? WHERE order_id = ?", VALID_EMAIL, orderId);
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
        clearJpaCache();
        mockMvc.perform(post(invoicesUrl(orderId) + "/rewrite").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"))
                .andExpect(jsonPath("$.error.message").value(ORDER_LOCKED_MESSAGE));
        clearJpaCache();

        Assertions.assertEquals(1, invoiceCount(orderId), "no version appended on a LAID order");
        Assertions.assertEquals(pathA, invoiceRow(orderId, 1));
        Assertions.assertEquals(storedFilesBefore, storedFileCountForOrder(orderId), "no file written");
    }

    // ================================================================
    // 12f. PR1 accepted-quote reads after the conversion
    // ================================================================

    /**
     * After Path A: the workspace still surfaces the SAME accepted version, now invoice_eligible false
     * (the invoice carries the quote's own signature time); type=accepted still streams the stored signed
     * quote PDF; the quote signature download streams the same bytes as the invoice signature download.
     */
    @Test
    void acceptedQuoteReads_afterPathA_sameVersionNowIneligible_signedPdfAndSignatureStillStream() throws Exception {
        long orderId = acceptedQuoteOrder(QUOTE_TERMS_RAW);
        String orderNumber = orderNumber(orderId);
        Map<String, Object> quoteV1 = quoteVersionRow(orderId, 1);
        JsonNode before = workspaceAccepted(orderId);
        Assertions.assertTrue(before.get("invoice_eligible").booleanValue(), "precondition: no invoice -> eligible");

        createInvoiceFromQuote(orderId);
        Assertions.assertEquals(quoteV1, quoteVersionRow(orderId, 1), "Path A never writes the quote version");
        Assertions.assertEquals(quoteV1.get("accepted_at"), invoiceRow(orderId, 1).get("accepted_at"),
                "precondition: Path A copied the quote's signature time");

        JsonNode accepted = workspaceAccepted(orderId);
        Assertions.assertEquals(longValue(quoteV1, "quote_version_id"), accepted.get("quote_version_id").asLong());
        Assertions.assertEquals(1, accepted.get("version_number").asInt());
        Assertions.assertEquals(((Timestamp) quoteV1.get("accepted_at")).toLocalDateTime(),
                LocalDateTime.parse(accepted.get("accepted_at").asText()));
        Assertions.assertEquals(SNAPSHOT_NAME, accepted.get("accepted_customer_name").asText());
        Assertions.assertTrue(accepted.get("invoice_eligible").isBoolean());
        Assertions.assertFalse(accepted.get("invoice_eligible").booleanValue(),
                "the converted quote is no longer newer than the current invoice's signature");

        clearJpaCache();
        MvcResult signedPdf = mockMvc.perform(get(quoteUrl(orderId, "pdf?type=accepted")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andReturn();
        Assertions.assertArrayEquals(storedFileBytes(quoteV1.get("signed_pdf_file_id")),
                signedPdf.getResponse().getContentAsByteArray(), "type=accepted streams the stored signed quote PDF");
        assertInlineFilename(signedPdf, "quote-" + orderNumber + "-v1-signed.pdf");

        clearJpaCache();
        MvcResult quoteSignature = mockMvc.perform(get(quoteUrl(orderId, "accepted/signature"))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andReturn();
        MvcResult invoiceSignature = mockMvc.perform(get(invoiceSignatureUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andReturn();
        byte[] quoteSignatureBytes = quoteSignature.getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(invoiceSignature.getResponse().getContentAsByteArray(), quoteSignatureBytes,
                "the quote and invoice signature downloads stream the same inherited file");
        Assertions.assertArrayEquals(storedFileBytes(quoteV1.get("accepted_signature_file_id")), quoteSignatureBytes);
        assertInlineFilename(quoteSignature, "quote-signature-" + orderNumber + "-v1.png");
    }

    private JsonNode workspaceAccepted(long orderId) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(get(quoteUrl(orderId, "workspace")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Quote workspace loaded."))
                .andReturn();
        JsonNode accepted = readJson(result).path("data").path("accepted");
        Assertions.assertTrue(accepted.isObject(), () -> "data.accepted must be populated: " + accepted);
        return accepted;
    }
}
