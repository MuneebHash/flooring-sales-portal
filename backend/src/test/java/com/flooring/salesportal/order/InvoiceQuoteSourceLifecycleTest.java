package com.flooring.salesportal.order;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
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
 * run against a quote-origin (Path A) invoice.
 *
 * <p>Part 1 (V19 lifecycle and ownership) proves the write table on the raw invoice rows: Path A writes
 * the selected accepted version id and its frozen terms (including frozen null); D.7 payment and D.10
 * void carry both columns plus the acceptance columns verbatim; D.1 Create writes null/null; D.2
 * Rewrite of a Path A invoice writes null/null and clears the acceptance; D.8 accept carries both
 * columns defensively from the current row (and renders that row's frozen terms, never the live
 * terms). It also proves the shared write boundary: {@link InvoiceRepository#insertInvoice} refuses a
 * missing or cross-order source BEFORE the insert, both when called directly and when a payment
 * regenerates from a row carrying such a source (generic 500, nothing internal leaked).
 *
 * <p>Part 2 (regressions) runs D.9 resend, D.10 signature download, D.8, D.1, D.2 and the PR1
 * accepted-quote reads against a Path A invoice.
 *
 * <p>Every accepted quote comes from the REAL chain (protected draft PUT, protected send-email, token
 * read from the recorded email, PUBLIC multipart accept with no session). Raw SQL is used only where
 * the scenario calls for it: an SQL-seeded quote-sourced invoice for the D.8 defensive carry and the
 * cross-order ownership cases (the single-column V19 FK allows a cross-order reference), bare
 * quote_version rows for the "other order" side, tenant terms, a relaxed email constraint and LAID.
 *
 * <p>Self-seeded (Phase 14D): business 1 / store 1 / user 1 is only the session identity; every order
 * is inserted here with the {@code QINVL.ZZ9.} order-number prefix (sequence base 150_000) and
 * {@code QINVL} store charge codes. Everything runs in the test transaction and rolls back; the
 * services' rollback hooks delete the files they wrote under the shared quote test storage root, so a
 * stored file's physical path is that root plus {@code stored_file.storage_path}. The recording
 * senders are singletons whose state survives a rollback, so all three are reset before AND after
 * every test.
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
    private static final String ORDER_DETAILS = "Supply and lay carpet (QINVL details of sale)";
    private static final String SEEDED_INVOICE_DETAILS = "QINVL seeded invoice details";

    // Tenant terms. Each scenario uses distinct, recognisable text so the rendered PDF and the API can
    // only show the value the rule selects (frozen quote terms, the seeded D.8 terms or the live terms).
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

    private static final String CREATED_FROM_QUOTE_MESSAGE = "Invoice created from accepted quote.";
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

    // InvoiceDetail (D.1 / D.2 / D.8 / D.9 / Path A): the 18 Phase 12 + 13 keys plus the two PR2 terms
    // keys, ALWAYS present. No internal id (source quote version, stored files) is ever a key.
    private static final Set<String> INVOICE_DETAIL_KEYS = Set.of(
            "invoice_id", "order_id", "version_number", "invoice_date", "due_date", "details_of_sale_snapshot",
            "sale_price_ex_gst", "sale_price_inc_gst", "total_paid", "balance_due", "created_by_user_id",
            "created_at", "pdf_download_path", "accepted_at", "accepted_customer_name",
            "accepted_signature_present", "accepted_signature_download_path", "last_emailed_at",
            "terms_html", "terms_source");

    // CurrentInvoiceSummary (payment / void responses): UNCHANGED by PR2, so no terms keys.
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

    /** POST .../quote/create-invoice (Path A) with an empty body: 201 + the Path A message. */
    private MvcResult createInvoiceFromQuote(long orderId) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(post(quoteUrl(orderId, "create-invoice")).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(CREATED_FROM_QUOTE_MESSAGE))
                .andReturn();
        clearJpaCache();
        return result;
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

    /** A valid D.8 request: the signature PNG + the saved customer's name. */
    private MockMultipartHttpServletRequestBuilder signInvoice(long orderId) {
        return (MockMultipartHttpServletRequestBuilder) multipart(invoiceAcceptUrl(orderId))
                .file(signaturePart(ONE_PIXEL_PNG))
                .param("accepted_customer_name", SNAPSHOT_NAME);
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
        long storedFileId = insertSeededInvoicePdfRow(orderId, versionNumber);
        jdbcTemplate.update(
                "INSERT INTO invoice (order_id, version_number, invoice_date, due_date, details_of_sale_snapshot, "
                        + "sale_price_ex_gst, sale_price_inc_gst, total_paid, balance_due, stored_file_id, "
                        + "created_by_user_id, source_quote_version_id, terms_snapshot) "
                        + "VALUES (?, ?, CURRENT_DATE, DATE '2026-11-29', ?, 250.00, 275.00, 0.00, 275.00, ?, ?, ?, ?)",
                orderId, versionNumber, SEEDED_INVOICE_DETAILS, storedFileId, USER_LIAM,
                sourceQuoteVersionId, termsSnapshot);
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
                "InvoiceDetail always carries exactly its keys (terms_html / terms_source included)");
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

    private static void assertLiveTerms(JsonNode invoice, String expectedTermsHtml) {
        Assertions.assertEquals("LIVE", invoice.get("terms_source").asText(), "terms_source");
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

    private static void assertCurrentInvoiceSummaryUnchanged(JsonNode response, String label) {
        JsonNode currentInvoice = response.path("data").path("current_invoice");
        Assertions.assertTrue(currentInvoice.isObject(), () -> label + ": data.current_invoice must be an object");
        Assertions.assertEquals(new TreeSet<>(CURRENT_INVOICE_SUMMARY_KEYS), fieldNames(currentInvoice),
                () -> label + ": CurrentInvoiceSummary keys are unchanged by PR2 (no terms keys)");
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
     * The signed acceptance block (InvoiceAcceptanceControllerTest convention: PDFBox does not extract the
     * caption's inline spans in a fixed order, so the caption and the name are checked separately, and
     * the unsigned blank-area caption must be gone).
     */
    private static void assertSignedCaption(byte[] pdf, String label) throws IOException {
        String text = pdfText(pdf).replaceAll("\\s+", " ");
        Assertions.assertTrue(text.contains("Accepted by"), () -> label + ": missing acceptance caption: " + text);
        Assertions.assertTrue(text.contains(SNAPSHOT_NAME), () -> label + ": missing accepted name: " + text);
        Assertions.assertFalse(text.contains("Customer signature"),
                () -> label + ": a signed PDF must not show the blank-area caption: " + text);
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
    // 8a. Path A writes the selected source id and its frozen terms
    // ================================================================

    /**
     * Path A with populated frozen terms: the new invoice row's source_quote_version_id is the accepted
     * version id and its terms_snapshot is that version's frozen terms_snapshot verbatim, even after the
     * tenant terms change; the response says QUOTE with those terms and the PDF renders them on page 2.
     */
    @Test
    void pathA_withFrozenQuoteTerms_writesAcceptedVersionIdAndItsTermsSnapshotVerbatim() throws Exception {
        long orderId = acceptedQuoteOrder(QUOTE_TERMS_RAW);
        Map<String, Object> quoteV1 = quoteVersionRow(orderId, 1);
        long acceptedVersionId = longValue(quoteV1, "quote_version_id");
        String frozenTerms = (String) quoteV1.get("terms_snapshot");
        Assertions.assertNotNull(frozenTerms, "precondition: the quote froze the tenant terms at issue");
        Assertions.assertTrue(frozenTerms.contains(QUOTE_TERMS_TEXT), frozenTerms);

        // The live tenant terms move on after the signature; the invoice must keep the frozen ones.
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);

        JsonNode invoice = invoiceDetail(createInvoiceFromQuote(orderId));
        Assertions.assertEquals(1, invoice.get("version_number").asInt());
        assertQuoteTerms(invoice, frozenTerms);
        Assertions.assertTrue(invoice.get("accepted_signature_present").booleanValue());

        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(acceptedVersionId, longValue(row, "source_quote_version_id"),
                "source_quote_version_id = the selected accepted quote version");
        Assertions.assertEquals(frozenTerms, row.get("terms_snapshot"),
                "terms_snapshot = the accepted version's frozen terms_snapshot, verbatim");
        Assertions.assertEquals(quoteV1, quoteVersionRow(orderId, 1), "Path A never writes the quote version");

        Map<String, Object> pdfFile = storedFileRow(row.get("stored_file_id"));
        Assertions.assertEquals("invoice-" + orderNumber(orderId) + "-v1.pdf", pdfFile.get("file_name"));
        Assertions.assertEquals("application/pdf", pdfFile.get("mime_type"));
        assertTermsOnPageTwo(storedFileBytes(row.get("stored_file_id")), QUOTE_TERMS_TEXT, "Path A PDF",
                LIVE_SOFT_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
    }

    /**
     * Path A with frozen-null terms (no tenant terms at issue): the row still carries the source id but
     * terms_snapshot stays NULL, with no fallback to the live terms that exist by conversion time.
     */
    @Test
    void pathA_withFrozenNullQuoteTerms_writesSourceIdAndNullTermsSnapshot_noLiveFallback() throws Exception {
        long orderId = acceptedQuoteOrder(null);
        Map<String, Object> quoteV1 = quoteVersionRow(orderId, 1);
        Assertions.assertNull(quoteV1.get("terms_snapshot"), "precondition: the quote froze NO terms");

        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);

        JsonNode invoice = invoiceDetail(createInvoiceFromQuote(orderId));
        assertQuoteTerms(invoice, null);

        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(longValue(quoteV1, "quote_version_id"), longValue(row, "source_quote_version_id"));
        Assertions.assertTrue(row.containsKey("terms_snapshot"));
        Assertions.assertNull(row.get("terms_snapshot"), "frozen null terms stay NULL on the invoice row");
        assertNoTermsPage(storedFileBytes(row.get("stored_file_id")), "Path A frozen-null PDF",
                LIVE_SOFT_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
    }

    // ================================================================
    // 8b. Payment then void carry the V19 pair and the acceptance verbatim
    // ================================================================

    /**
     * D.7 payment then D.10 void on a Path A invoice: v2 and v3 carry source_quote_version_id,
     * terms_snapshot, accepted_at, accepted_customer_name and accepted_signature_file_id verbatim from
     * the Path A row, their regenerated PDFs render the frozen terms (not the changed live terms), the
     * payment / void responses keep the unchanged CurrentInvoiceSummary shape and nothing is emailed.
     */
    @Test
    void paymentThenVoid_onPathAInvoice_carrySourceTermsAndAcceptanceVerbatim_renderFrozenTerms() throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        Map<String, Object> pathA = invoiceRow(orderId, 1);
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);

        JsonNode paid = recordCashPayment(orderId, "50.00");
        assertCurrentInvoiceSummaryUnchanged(paid, "payment");
        Assertions.assertEquals(2, paid.path("data").path("current_invoice").path("version_number").asInt());
        Map<String, Object> v2 = invoiceRow(orderId, 2);
        assertCarriedQuoteSourceAndAcceptance(pathA, v2, "payment v2");
        assertMoney("50.00", v2.get("total_paid"), "v2.total_paid");
        assertMoney("225.00", v2.get("balance_due"), "v2.balance_due");

        JsonNode voided = voidPayment(orderId, latestPaymentId(orderId));
        assertCurrentInvoiceSummaryUnchanged(voided, "void");
        Assertions.assertEquals(3, voided.path("data").path("current_invoice").path("version_number").asInt());
        Map<String, Object> v3 = invoiceRow(orderId, 3);
        assertCarriedQuoteSourceAndAcceptance(pathA, v3, "void v3");
        assertMoney("0.00", v3.get("total_paid"), "v3.total_paid");
        assertMoney("275.00", v3.get("balance_due"), "v3.balance_due");

        for (Map<String, Object> version : List.of(v2, v3)) {
            String label = "regenerated v" + version.get("version_number") + " PDF";
            byte[] pdf = storedFileBytes(version.get("stored_file_id"));
            assertTermsOnPageTwo(pdf, QUOTE_TERMS_TEXT, label, LIVE_SOFT_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
            assertSignedCaption(pdf, label);
        }
        Assertions.assertEquals(pathA, invoiceRow(orderId, 1), "the Path A row itself is never modified");
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "payment and void never email");
    }

    // ================================================================
    // 8c. D.1 Create writes null/null; D.2 Rewrite of Path A writes null/null and clears acceptance
    // ================================================================

    /** D.1 Create (Path B) on an order with an accepted quote: NULL source + NULL terms, LIVE terms. */
    @Test
    void d1Create_onOrderWithAcceptedQuote_writesNullSourceAndNullTerms_respondsLiveTerms() throws Exception {
        long orderId = acceptedQuoteOrder(QUOTE_TERMS_RAW);
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);

        clearJpaCache();
        MvcResult result = mockMvc.perform(post(invoicesUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(INVOICE_CREATED_MESSAGE))
                .andReturn();
        clearJpaCache();

        JsonNode invoice = invoiceDetail(result);
        Assertions.assertEquals(1, invoice.get("version_number").asInt());
        // SOFT order -> business.terms_soft through the sanitizer (never terms_hard, never the quote's).
        assertLiveTerms(invoice, termsSanitizer.sanitize(LIVE_SOFT_TERMS_RAW));

        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertNull(row.get("source_quote_version_id"), "D.1 writes a NULL source");
        Assertions.assertNull(row.get("terms_snapshot"), "D.1 writes NULL terms");
        Assertions.assertNull(row.get("accepted_at"));
    }

    /**
     * D.2 Rewrite of a Path A invoice (order not LAID): the appended v2 writes NULL source + NULL terms
     * and clears accepted_at, accepted_customer_name and accepted_signature_file_id; the response is
     * LIVE with the current tenant terms; the Path A row stays as it was (append-only).
     */
    @Test
    void d2Rewrite_ofPathAInvoice_writesNullSourceAndTerms_clearsAcceptance_respondsLiveTerms() throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        Map<String, Object> pathA = invoiceRow(orderId, 1);
        Assertions.assertNotNull(pathA.get("accepted_at"), "precondition: the Path A invoice is signed");
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);

        clearJpaCache();
        MvcResult result = mockMvc.perform(post(invoicesUrl(orderId) + "/rewrite").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(INVOICE_REWRITTEN_MESSAGE))
                .andReturn();
        clearJpaCache();

        JsonNode invoice = invoiceDetail(result);
        Assertions.assertEquals(2, invoice.get("version_number").asInt());
        assertLiveTerms(invoice, termsSanitizer.sanitize(LIVE_SOFT_TERMS_RAW));
        assertPresentAndNull(invoice, "accepted_at");
        assertPresentAndNull(invoice, "accepted_customer_name");
        assertPresentAndNull(invoice, "accepted_signature_download_path");
        Assertions.assertFalse(invoice.get("accepted_signature_present").booleanValue());

        Map<String, Object> v2 = invoiceRow(orderId, 2);
        for (String column : List.of("source_quote_version_id", "terms_snapshot", "accepted_at",
                "accepted_customer_name", "accepted_signature_file_id")) {
            Assertions.assertNull(v2.get(column), () -> "the rewrite clears " + column);
        }
        Assertions.assertEquals(pathA, invoiceRow(orderId, 1), "the Path A row stays unchanged");
        assertTermsOnPageTwo(storedFileBytes(v2.get("stored_file_id")), LIVE_SOFT_TERMS_TEXT, "rewrite v2 PDF",
                QUOTE_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
    }

    // ================================================================
    // 8d. D.8 carries the V19 pair defensively and renders the row's frozen terms
    // ================================================================

    /**
     * D.8 defensive carry: an UNSIGNED invoice v1 seeded with this order's real accepted quote version as
     * its source and "Seeded frozen terms D8" as terms_snapshot. With different live terms (and different
     * quote terms), D.8 appends v2 carrying the same source + terms, answers QUOTE with the seeded terms,
     * and the signed PDF's page 2 shows the seeded frozen terms only.
     */
    @Test
    void d8Accept_onUnsignedQuoteSourcedInvoice_carriesSourceAndFrozenTerms_signedPdfShowsSeededTerms()
            throws Exception {
        long orderId = acceptedQuoteOrder(QUOTE_TERMS_RAW);
        long acceptedVersionId = quoteVersionId(orderId, 1);
        long quoteSignatureFileId = longValue(quoteVersionRow(orderId, 1), "accepted_signature_file_id");
        seedUnsignedQuoteSourcedInvoice(orderId, 1, acceptedVersionId, D8_SEEDED_TERMS);
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
        Assertions.assertEquals(SNAPSHOT_NAME, invoice.get("accepted_customer_name").asText());

        Map<String, Object> v2 = invoiceRow(orderId, 2);
        Assertions.assertEquals(acceptedVersionId, longValue(v2, "source_quote_version_id"),
                "D.8 carries the current row's source verbatim");
        Assertions.assertEquals(D8_SEEDED_TERMS, v2.get("terms_snapshot"), "D.8 carries the current row's terms");
        Assertions.assertNotNull(v2.get("accepted_at"));
        Assertions.assertEquals(SNAPSHOT_NAME, v2.get("accepted_customer_name"));
        Assertions.assertNotEquals(quoteSignatureFileId, longValue(v2, "accepted_signature_file_id"),
                "D.8 stores its own new signature (it does not inherit the quote's)");
        Assertions.assertNull(invoiceRow(orderId, 1).get("accepted_at"), "the seeded v1 stays unsigned");

        byte[] signedPdf = storedFileBytes(v2.get("stored_file_id"));
        assertTermsOnPageTwo(signedPdf, D8_SEEDED_TERMS_TEXT, "D.8 signed PDF",
                LIVE_SOFT_TERMS_TEXT, LIVE_HARD_TERMS_TEXT, QUOTE_TERMS_TEXT);
        assertSignedCaption(signedPdf, "D.8 signed PDF");

        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "D.8 auto-emails the signed PDF once");
        Assertions.assertArrayEquals(signedPdf, sent.get(0).pdfBytes(), "the email attaches the stored signed PDF");
    }

    // ================================================================
    // 8e. The shared write boundary: insertInvoice verifies source ownership
    // ================================================================

    private static InvoiceRow insertDirect(InvoiceRepository repository, long orderId, long storedFileId,
                                           long sourceQuoteVersionId, String termsSnapshot) {
        return repository.insertInvoice(orderId, 1, LocalDate.now(), LocalDate.of(2026, 11, 29),
                "QINVL direct insert details", new BigDecimal("100.00"), new BigDecimal("110.00"),
                new BigDecimal("0.00"), new BigDecimal("110.00"), storedFileId, USER_LIAM,
                null, null, null, null, sourceQuoteVersionId, termsSnapshot);
    }

    /**
     * insertInvoice for order A with order B's quote_version as the source, or with a quote_version id
     * that does not exist, throws IllegalStateException BEFORE the insert (order A's invoice count stays
     * 0). The repository method itself throws the IllegalStateException; through the autowired Spring
     * bean the same failure arrives wrapped by the @Repository exception translation. With A's own
     * accepted version the insert succeeds and the returned and persisted row carry that source id.
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

        // Control: order A's own accepted version passes the same boundary.
        InvoiceRow row = insertDirect(invoiceRepository, orderA, storedFileId, ownVersionId, "<p>Own terms</p>");
        Assertions.assertEquals(ownVersionId, row.sourceQuoteVersionId());
        Assertions.assertEquals("<p>Own terms</p>", row.termsSnapshot());
        Assertions.assertEquals(orderA, row.orderId());
        Assertions.assertEquals(1, invoiceCount(orderA));
        Map<String, Object> persisted = invoiceRow(orderA, 1);
        Assertions.assertEquals(ownVersionId, longValue(persisted, "source_quote_version_id"));
        Assertions.assertEquals("<p>Own terms</p>", persisted.get("terms_snapshot"));
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
     * the invoice and the sales_order mirror (same value); response terms_source QUOTE.
     */
    @Test
    void d9Resend_onPathAInvoice_emailsStoredPathAPdfVerbatim_stampsInvoiceAndMirror_termsSourceQuote()
            throws Exception {
        long orderId = pathAOrder(QUOTE_TERMS_RAW);
        String orderNumber = orderNumber(orderId);
        Map<String, Object> pathA = invoiceRow(orderId, 1);
        byte[] pathAPdf = storedFileBytes(pathA.get("stored_file_id"));
        Assertions.assertNull(pathA.get("last_emailed_at"), "precondition: Path A never emails");
        Assertions.assertNull(orderLastEmailedAt(orderId), "precondition: the mirror is null after Path A");
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "precondition: no invoice email yet");
        int storedFilesBefore = storedFileCountForOrder(orderId);
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);

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
     * D.8 on a Path A invoice: 409 INVOICE_ALREADY_ACCEPTED with D.8's unchanged default message. A newer
     * quote v2 issued after the acceptance stays ISSUED with its ACTIVE token; nothing is appended,
     * written or emailed.
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
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "no invoice email");
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
