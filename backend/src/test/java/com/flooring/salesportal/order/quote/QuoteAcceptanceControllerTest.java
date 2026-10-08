package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16F PR1 — the PUBLIC remote quote acceptance
 * {@code POST /api/v1/public/quotes/{token}/accept} ({@link PublicQuoteController} →
 * {@link QuoteAcceptanceService}): the exact 201 body, the persisted acceptance (version / token /
 * signature + signed-PDF stored files on disk), the snapshot-only signed PDF, the token-gate-first
 * ordering, the multipart allowlist + signature rules, every dead-link state, the post-acceptance
 * public surface, LAID allowance and the "acceptance mutates nothing else" guarantee.
 *
 * <p>Self-seeded (Phase 14D go-forward rule) like {@link PublicQuoteControllerTest}: each test INSERTs
 * its own {@code sales_order} (+ customer / billing address / cost lines where needed) via
 * JdbcTemplate, issues the quote through the PROTECTED send-email endpoint, extracts the plaintext
 * token from the recorded link-only email body, then signs through the public endpoint WITHOUT any
 * session — the token is the only credential. Only business 1 / store 1 / user 1 and the slug come
 * from the V4–V6 seed; no V4 demo order or invoice is used.
 *
 * <p><b>Storage.</b> {@code app.storage.base-dir} is {@code target/test-storage/quote-acceptance} (the
 * single shared context string of every 16F class that asserts files on disk); the physical file of a
 * {@code stored_file} row is {@code <base-dir> + storage_path}. The acceptance
 * {@code TransactionTemplate} JOINS the class-level test transaction, so the written files stay on disk
 * for the assertions and are removed by the services' rollback hooks when the test transaction rolls
 * back. Post-commit durability (the store notification after a REAL commit, a committed lazy-expiry
 * flip) cannot be proven inside a test transaction and is not asserted here.
 *
 * <p>The recording senders are singletons whose state survives the rollback, so they are reset in
 * BOTH {@code @BeforeEach} and {@code @AfterEach}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class QuoteAcceptanceControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    private static final String VALID_EMAIL = "quote.acceptance@example.com";
    private static final String VALID_MOBILE = "0412345678";

    /** The V17 "Quotation To" name frozen at issue for the default seeded customer (Quote / Tester). */
    private static final String SNAPSHOT_NAME = "Quote Tester";

    // Base-URL agnostic: the plaintext token is whatever follows /q/ in the link-only email body.
    private static final Pattern LINK_TOKEN_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]{43,128})");

    // A well-formed (43 URL-safe chars) token that was never minted.
    private static final String UNKNOWN_TOKEN = "A".repeat(43);

    private static final String ACCEPTED_BODY =
            "{\"data\":{\"state\":\"INACTIVE\"},\"message\":\"Quote accepted.\"}";

    private static final String CONTACT_STORE_MESSAGE =
            "This quote can no longer be accepted online. Please contact the store.";
    private static final String SIGNATURE_REQUIRED_MESSAGE = "A signature is required to accept this quote.";
    private static final String SIGNATURE_INVALID_MESSAGE = "Signature must be a PNG image no larger than 2 MB.";
    private static final String VALIDATION_FAILED_MESSAGE = "One or more fields are invalid.";
    private static final String TOKEN_NOT_FOUND_MESSAGE = "Quote not found.";
    private static final String LINK_EXPIRED_MESSAGE = "This quote link has expired. Please contact the store.";
    private static final String LINK_SUPERSEDED_MESSAGE =
            "This quote has been replaced. Please use the latest quote link.";
    private static final String LINK_CANCELLED_MESSAGE = "This quote has been cancelled. Please contact the store.";
    private static final String LINK_INACTIVE_MESSAGE =
            "This quote link is no longer active. Please contact the store.";

    private static final int MAX_SIGNATURE_BYTES = 2_097_152;

    // A real, decodable 1x1 PNG (the QuotePdfGeneratorTest / InvoiceAcceptanceControllerTest constant):
    // the accept flow decodes it during validation and embeds it into the signed PDF as a data URI, so
    // fake bytes would be rejected (or break rendering) instead of exercising the flow.
    private static final byte[] SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // IHDR layout after the 8-byte magic: length(4) "IHDR"(4) width(4) height(4) bitDepth(1) colourType(1)
    // compression(1) filter(1) interlace(1), then CRC(4) over type + data.
    private static final int PNG_MAGIC_LENGTH = 8;
    private static final int IHDR_TYPE_OFFSET = 12;
    private static final int IHDR_BIT_DEPTH_OFFSET = 24;
    private static final int IHDR_COLOUR_TYPE_OFFSET = 25;
    private static final int IHDR_CRC_OFFSET = 29;
    private static final int IHDR_CHUNK_END = 33;

    /** Payload of an ancillary tEXt chunk added to an upload — server normalisation must drop it. */
    private static final String TEXT_CHUNK_MARKER = "quote-acceptance-ancillary-text-marker";

    // Spec §1 safe-decode bounds (the accept's IHDR pre-check), restated for fixture preconditions.
    private static final int MAX_SIGNATURE_SIDE_PX = 8_192;
    private static final long MAX_SIGNATURE_PIXELS = 4_000_000L;

    /** Partial alphas (1..254, both extremes included) written explicitly into the semi-transparent fixture. */
    private static final int[] PARTIAL_ALPHAS = {1, 2, 9, 31, 64, 100, 127, 128, 129, 180, 222, 253, 254};

    // The signed-caption timestamp pattern (QuotePdfGenerator / InvoicePdfGenerator).
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    // "QUOTATION".contains("QUOTE") is true, so the title is matched as a standalone word.
    private static final Pattern STANDALONE_QUOTATION = Pattern.compile("\\bQUOTATION\\b");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender acceptanceNotificationSender;

    @Autowired
    private RecordingSmsSender smsSender;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    private int seq = 100_000;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resetRecordingSenders();
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class asserts files under its own storage base dir");
    }

    @AfterEach
    void tearDown() {
        // Singletons — never leak recorded sends or an armed failNextSend into another test/class.
        resetRecordingSenders();
    }

    private void resetRecordingSenders() {
        quoteEmailSender.reset();
        acceptanceNotificationSender.reset();
        smsSender.reset();
        invoiceEmailSender.reset();
    }

    // ================================================================
    // Helpers — URLs, session, JPA cache
    // ================================================================

    private MockHttpSession liamStore1Session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        s.setAttribute("store_id", STORE_SYD_CBD);
        return s;
    }

    private static String publicUrl(String token) {
        return "/api/v1/public/quotes/" + token;
    }

    private static String acceptUrl(String token) {
        return publicUrl(token) + "/accept";
    }

    private static String viewedUrl(String token) {
        return publicUrl(token) + "/viewed";
    }

    private static String publicPdfUrl(String token) {
        return publicUrl(token) + "/pdf";
    }

    private static String draftUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/draft";
    }

    private static String sendEmailUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/send-email";
    }

    private static String cancelUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/cancel";
    }

    /**
     * Detach every hydrated entity. Tests share ONE transaction/persistence context across MockMvc
     * calls, so after a raw JDBC write a later JPA read could return a stale cached entity (production
     * requests each get a fresh persistence context).
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    // ================================================================
    // Helpers — self-seeding
    // ================================================================

    private long insertOrder(String status) {
        int s = ++seq;
        String orderNumber = "QACPT.ZZ9." + String.format("%05d", s % 100_000);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, ?::order_status, 1, 2026) "
                        + "RETURNING order_id",
                Long.class,
                BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, s, orderNumber, status);
    }

    /** Customer "Quote" (no middle name) "Tester" — snapshot name {@value #SNAPSHOT_NAME}. */
    private void seedCustomer(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quote', 'Tester', ?, ?)",
                orderId, VALID_EMAIL, VALID_MOBILE);
    }

    private void seedCustomerWithFullName(long orderId, String first, String middle, String last) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, middle_name, last_name, email, mobile) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, first, middle, last, VALID_EMAIL, VALID_MOBILE);
    }

    /** Billing "12 Test Street" / "Sydney NSW 2000". */
    private void seedBillingAddress(long orderId) {
        seedBillingAddress(orderId, "12", "Test Street", "Sydney", "NSW", "2000");
    }

    private void seedBillingAddress(long orderId, String streetNumber, String street,
                                    String suburb, String stateCode, String postcode) {
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, ?, ?, ?, ?, ?)",
                orderId, streetNumber, street, suburb, stateCode, postcode);
    }

    /** Deterministic "no tenant terms" (a dev-seeded terms text could otherwise fog the PDF checks). */
    private void clearTenantTerms() {
        jdbcTemplate.update("UPDATE business SET terms_soft = NULL, terms_hard = NULL WHERE business_id = ?",
                BUSINESS_AUSSIE);
        clearJpaCache();
    }

    private void setTenantTerms(String termsHtml) {
        jdbcTemplate.update("UPDATE business SET terms_soft = ?, terms_hard = ? WHERE business_id = ?",
                termsHtml, termsHtml, BUSINESS_AUSSIE);
        clearJpaCache();
    }

    private void setDetailsOfSale(long orderId, String details) {
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = ? WHERE order_id = ?", details, orderId);
        clearJpaCache();
    }

    /** A cost basis line on the order: one store_product + order_product_line (SQM, qty 1). */
    private void seedProductLine(long orderId, String lineTotal, String lineCost) {
        int s = ++seq;
        String code = "QAP" + (s % 100_000);
        long productId = jdbcTemplate.queryForObject(
                "INSERT INTO store_product (store_id, flooring_type, code, name, pricing_unit, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Quote acceptance test product', "
                        + "'SQM'::pricing_unit, ?, ?) RETURNING product_id",
                Long.class, STORE_SYD_CBD, code, new BigDecimal(lineTotal), new BigDecimal(lineCost));
        jdbcTemplate.update(
                "INSERT INTO order_product_line "
                        + "(order_id, product_id, product_code_snapshot, product_name_snapshot, "
                        + " pricing_unit_snapshot, price_snapshot, cost_snapshot, quantity_lm, quantity_sqm, "
                        + " unit_price, line_total, line_cost, sqm_per_lm_snapshot) "
                        + "VALUES (?, ?, ?, 'Quote acceptance test product', 'SQM'::pricing_unit, ?, ?, "
                        + " 1.00, 3.66, ?, ?, ?, 3.66)",
                orderId, productId, code,
                new BigDecimal(lineTotal), new BigDecimal(lineCost),
                new BigDecimal(lineTotal), new BigDecimal(lineTotal), new BigDecimal(lineCost));
    }

    /** A cost basis line on the order: one store_charge + order_charge_line (qty 1). */
    private void seedChargeLine(long orderId, String lineTotal, String lineCost) {
        int s = ++seq;
        String code = "QAC" + (s % 100_000);
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Quote acceptance test charge', ?, ?) "
                        + "RETURNING charge_id",
                Long.class, STORE_SYD_CBD, code, new BigDecimal(lineTotal), new BigDecimal(lineCost));
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Quote acceptance test charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code,
                new BigDecimal(lineTotal), new BigDecimal(lineCost),
                new BigDecimal(lineTotal), new BigDecimal(lineTotal), new BigDecimal(lineCost));
    }

    /** An unsigned v1 invoice (own stored_file row) + one active payment, seeded directly. */
    private void seedInvoiceAndPayment(long orderId) {
        long invoiceFileId = jdbcTemplate.queryForObject(
                "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                        + "VALUES (?, ?, 'application/pdf', 1024) RETURNING stored_file_id",
                Long.class, "invoice-seeded-v1.pdf",
                "/uploads/" + BUSINESS_AUSSIE + "/seeded-invoices/" + orderId + "-v1.pdf");
        jdbcTemplate.update(
                "INSERT INTO invoice (order_id, version_number, details_of_sale_snapshot, sale_price_ex_gst, "
                        + " sale_price_inc_gst, total_paid, balance_due, stored_file_id, created_by_user_id) "
                        + "VALUES (?, 1, 'Seeded invoice details', 150.00, 165.00, 50.00, 115.00, ?, ?)",
                orderId, invoiceFileId, USER_LIAM);
        jdbcTemplate.update(
                "INSERT INTO payment_transaction (order_id, payment_method, amount) "
                        + "VALUES (?, 'CASH'::payment_method, 50.00)",
                orderId);
    }

    private void saveDraft(long orderId, String json) throws Exception {
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    /** Two ITEM lines: Carpet 2×100 + Underlay 1×50 = 250.00 ex / 275.00 inc. */
    private void saveItemisedTwoLineDraft(long orderId) throws Exception {
        saveDraft(orderId, """
                {"itemised": true, "lines": [
                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                ]}""");
    }

    /** Non-itemised draft (final inc total; header-only — previously saved itemised rows stay DORMANT). */
    private void saveNonItemisedDraft(long orderId, String finalIncTotal) throws Exception {
        saveDraft(orderId, "{\"itemised\": false, \"final_total_inc_gst\": " + finalIncTotal + ", \"lines\": []}");
    }

    /** LEAD order + customer "Quote Tester" + billing address + NO tenant terms + the itemised 2-line draft. */
    private long itemisedReadyOrder() throws Exception {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        seedBillingAddress(orderId);
        clearTenantTerms();
        saveItemisedTwoLineDraft(orderId);
        return orderId;
    }

    // ================================================================
    // Helpers — protected issue / cancel and the public token
    // ================================================================

    private void sendEmailOk(long orderId) throws Exception {
        clearJpaCache();
        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        clearJpaCache();
    }

    /** Issue (or resend) by email and return the plaintext token from the LATEST recorded email body. */
    private String issueAndExtractToken(long orderId) throws Exception {
        sendEmailOk(orderId);
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertFalse(sent.isEmpty(), "a quote email must have been recorded");
        return extractToken(sent.get(sent.size() - 1).bodyText());
    }

    private static String extractToken(String messageBody) {
        Matcher matcher = LINK_TOKEN_PATTERN.matcher(messageBody);
        Assertions.assertTrue(matcher.find(), "message body must contain the /q/{token} link: " + messageBody);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "the public link must appear exactly once in the body");
        return token;
    }

    private void cancelOk(long orderId) throws Exception {
        clearJpaCache();
        mockMvc.perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    /** Move the token's expiry into the past using JVM time (the clock the services compare with). */
    private void backdateTokenExpiry(String plainToken) {
        int updated = jdbcTemplate.update("UPDATE quote_token SET expires_at = ? WHERE token_hash = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(2)), sha256Hex(plainToken));
        Assertions.assertEquals(1, updated, "expected to backdate exactly one token");
        clearJpaCache();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ================================================================
    // Helpers — the public accept call (NEVER with a session)
    // ================================================================

    private static MockMultipartFile signaturePart(byte[] bytes) {
        return new MockMultipartFile("signature", "signature.png", "image/png", bytes);
    }

    private static MockMultipartFile extraPhotoPart() {
        return new MockMultipartFile("photo", "photo.png", "image/png", SIGNATURE_PNG);
    }

    private ResultActions postAccept(String token, MockMultipartFile... parts) throws Exception {
        return postAcceptWithFields(token, Map.of(), parts);
    }

    private ResultActions postAcceptWithFields(String token, Map<String, String> formFields,
                                               MockMultipartFile... parts) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(acceptUrl(token));
        for (MockMultipartFile part : parts) {
            request.file(part);
        }
        for (Map.Entry<String, String> field : formFields.entrySet()) {
            request.param(field.getKey(), field.getValue());
        }
        clearJpaCache();
        // No .session(...) — the public surface is token-only.
        ResultActions actions = mockMvc.perform(request);
        clearJpaCache();
        return actions;
    }

    /** A successful accept: 201 and EXACTLY {data:{state:"INACTIVE"}, message:"Quote accepted."}. */
    private void acceptOk(String token, byte[] signaturePng) throws Exception {
        MvcResult result = postAccept(token, signaturePart(signaturePng)).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertEquals(201, result.getResponse().getStatus(), "accept must be 201 — body: " + body);
        String contentType = result.getResponse().getContentType();
        Assertions.assertNotNull(contentType, "accept must answer with a JSON body");
        Assertions.assertTrue(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON),
                "accept must answer with JSON, was " + contentType);
        Assertions.assertEquals(MAPPER.readTree(ACCEPTED_BODY), MAPPER.readTree(body),
                "the 201 body must be exactly {data:{state:INACTIVE}, message} — no id, amount, name, "
                        + "timestamp, file reference, token or hash: " + body);
    }

    /** Assert a JSON error response and return its {@code error} node (for detail checks). */
    private static JsonNode assertError(ResultActions actions, int expectedStatus, String expectedCode,
                                        String expectedMessage, String label) throws Exception {
        MvcResult result = actions.andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertEquals(expectedStatus, result.getResponse().getStatus(), label + " — body: " + body);
        JsonNode error = MAPPER.readTree(body).path("error");
        Assertions.assertEquals(expectedCode, error.path("code").asText(null), label + " — body: " + body);
        Assertions.assertEquals(expectedMessage, error.path("message").asText(null), label + " — body: " + body);
        return error;
    }

    // ================================================================
    // Helpers — DB / disk / PDF probes
    // ================================================================

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static LocalDateTime asLocalDateTime(Object value) {
        return ((Timestamp) value).toLocalDateTime();
    }

    private static void assertMoney(String expected, Object actual) {
        Assertions.assertTrue(actual instanceof BigDecimal
                        && new BigDecimal(expected).compareTo((BigDecimal) actual) == 0,
                "expected " + expected + " but was " + actual);
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private String businessName() {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM business WHERE business_id = ?", String.class, BUSINESS_AUSSIE);
    }

    private Map<String, Object> issuedVersionRow(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND status = 'ISSUED'", orderId);
    }

    private Map<String, Object> versionRowById(long quoteVersionId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE quote_version_id = ?", quoteVersionId);
    }

    private Map<String, Object> versionRowByNumber(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private Map<String, Object> tokenRow(String plainToken) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_token WHERE token_hash = ?", sha256Hex(plainToken));
    }

    private Map<String, Object> storedFileRow(Object storedFileId) {
        return jdbcTemplate.queryForMap("SELECT * FROM stored_file WHERE stored_file_id = ?", storedFileId);
    }

    private String storagePathOf(Object storedFileId) {
        return (String) storedFileRow(storedFileId).get("storage_path");
    }

    private int versionLineCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version_line l "
                        + "JOIN quote_version v ON l.quote_version_id = v.quote_version_id "
                        + "WHERE v.order_id = ?", Integer.class, orderId);
    }

    private int draftLineCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_draft_line l JOIN quote_draft d ON l.quote_draft_id = d.quote_draft_id "
                        + "WHERE d.order_id = ?", Integer.class, orderId);
    }

    private BigDecimal orderMoneyColumn(long orderId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM sales_order WHERE order_id = ?", BigDecimal.class, orderId);
    }

    /** FileStorageService writes every order file under /uploads/{businessId}/orders/{orderId}/. */
    private static String orderStoragePrefix(long orderId) {
        return "/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/";
    }

    /** stored_file rows physically owned by this order (precise — independent of other data in the DB). */
    private int storedFileCountForOrder(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ?",
                Integer.class, orderStoragePrefix(orderId) + "%");
    }

    private Path storageRoot() {
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
    }

    /** Physical file of a stored_file row: base-dir + storage_path (which starts with "/uploads/"). */
    private Path diskPath(String storagePath) {
        Assertions.assertTrue(storagePath.startsWith("/uploads/"), "unexpected storage_path " + storagePath);
        return storageRoot().resolve(storagePath.substring(1)).normalize();
    }

    private byte[] diskBytes(String storagePath) throws IOException {
        Path file = diskPath(storagePath);
        Assertions.assertTrue(Files.isRegularFile(file), "expected a file on disk at " + file);
        return Files.readAllBytes(file);
    }

    private Set<String> diskFilesForOrder(long orderId) throws IOException {
        Path dir = storageRoot().resolve(orderStoragePrefix(orderId).substring(1));
        if (!Files.isDirectory(dir)) {
            return new TreeSet<>();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String fileNameOf(String storagePath) {
        return storagePath.substring(storagePath.lastIndexOf('/') + 1);
    }

    /**
     * Everything a REJECTED accept must leave untouched, as one comparable snapshot: every version and
     * token row of the order, the order header (status, price columns, email marker, updated_at), the
     * order's stored_file rows and files on disk, and the store-notification recorder.
     */
    private Map<String, Object> sideEffectState(long orderId) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("quote_version", jdbcTemplate.queryForList(
                "SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId));
        state.put("quote_token", jdbcTemplate.queryForList(
                "SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY t.quote_token_id", orderId));
        state.put("sales_order", jdbcTemplate.queryForMap(
                "SELECT order_status, price_adjustment_inc_gst, sale_price_ex_gst, total_cost, gp, gp_percent, "
                        + "last_emailed_at, updated_at FROM sales_order WHERE order_id = ?", orderId));
        state.put("stored_file_rows", storedFileCountForOrder(orderId));
        state.put("disk_files", diskFilesForOrder(orderId));
        state.put("notifications_sent", acceptanceNotificationSender.sentNotifications().size());
        state.put("notifications_failed", acceptanceNotificationSender.failedNotifications().size());
        return state;
    }

    private void assertNoSideEffects(long orderId, Map<String, Object> before, String label) throws IOException {
        Assertions.assertEquals(before, sideEffectState(orderId),
                label + ": a rejected accept must write nothing (version, token, order price, stored files, "
                        + "disk files, notification)");
    }

    private void assertStillIssuedWithActiveToken(long quoteVersionId, String token) {
        Map<String, Object> version = versionRowById(quoteVersionId);
        Assertions.assertEquals("ISSUED", version.get("status"), "the version must stay ISSUED");
        Assertions.assertNull(version.get("accepted_at"));
        Assertions.assertNull(version.get("accepted_customer_name"));
        Assertions.assertNull(version.get("accepted_signature_file_id"));
        Assertions.assertNull(version.get("signed_pdf_file_id"));
        Map<String, Object> tokenRow = tokenRow(token);
        Assertions.assertEquals("ACTIVE", tokenRow.get("status"), "the link must stay ACTIVE");
        Assertions.assertNull(tokenRow.get("dead_at"));
    }

    private static void assertPdfBytes(byte[] pdf) {
        Assertions.assertNotNull(pdf);
        Assertions.assertTrue(pdf.length > 500, "PDF unexpectedly small: " + pdf.length + " bytes");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII),
                "file must begin with the PDF magic header");
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
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

    // PDFBox can inject/drop spaces between glyphs and wraps long text, so most checks either collapse
    // whitespace runs or compare with all whitespace removed (QuotePdfGeneratorTest conventions).
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

    /**
     * The signed caption is ONE text node "Accepted by {name} on {dd/MM/yyyy HH:mm}", rendered from the
     * same LocalDateTime that is persisted as accepted_at. Postgres keeps TIMESTAMP at microsecond
     * precision (rounded), so the stored value can sit < 1µs AFTER the rendered instant; accepting the
     * rendering of {@code acceptedAt - 1µs} as well keeps the check exact yet immune to a minute boundary.
     */
    private static void assertAcceptedCaption(String pdfText, String acceptedName, LocalDateTime acceptedAt) {
        String text = noSpace(pdfText);
        String exact = noSpace("Accepted by " + acceptedName + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        String roundedBack = noSpace("Accepted by " + acceptedName + " on "
                + DISPLAY_DATE_TIME.format(acceptedAt.minusNanos(1_000)));
        Assertions.assertTrue(text.contains(exact) || text.contains(roundedBack),
                "signed PDF must carry the caption '" + exact + "' in: " + pdfText);
    }

    private static byte[] pngOfSize(int width, int height, int imageType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Assertions.assertTrue(ImageIO.write(new BufferedImage(width, height, imageType), "png", out),
                "a PNG ImageWriter must be available");
        return out.toByteArray();
    }

    /** The width/height a PNG header DECLARES (IHDR bytes 16..23, big-endian) — no pixel decoding. */
    private static int[] declaredPngDimensions(byte[] png) {
        ByteBuffer header = ByteBuffer.wrap(png);
        return new int[] {header.getInt(16), header.getInt(20)};
    }

    private static byte[] pngMagicFollowedByGarbage() {
        byte[] garbage = "this-is-not-an-IHDR-chunk-just-garbage-after-the-magic"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[8 + garbage.length];
        System.arraycopy(SIGNATURE_PNG, 0, bytes, 0, 8);
        System.arraycopy(garbage, 0, bytes, 8, garbage.length);
        return bytes;
    }

    /**
     * A signature-pad-like PNG: a transparent 48x16 ARGB canvas with a dark, NON-antialiased stroke, so
     * every pixel is fully opaque or fully transparent and the pixel comparison covers many pixels.
     */
    private static byte[] strokeSignaturePng() throws IOException {
        BufferedImage image = new BufferedImage(48, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(new Color(0x1A, 0x2B, 0x3C));
            graphics.setStroke(new BasicStroke(2f));
            graphics.drawLine(2, 12, 20, 3);
            graphics.drawLine(20, 3, 30, 13);
            graphics.drawLine(30, 13, 45, 4);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Assertions.assertTrue(ImageIO.write(image, "png", out), "a PNG ImageWriter must be available");
        return out.toByteArray();
    }

    /** {@code png} with an ancillary {@code tEXt} chunk ({@code keyword}\0{@code text}) inserted right after IHDR. */
    private static byte[] withTextChunk(byte[] png, String keyword, String text) {
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        byte[] data = (keyword + '\0' + text).getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        byte[] chunk = ByteBuffer.allocate(4 + type.length + data.length + 4)
                .putInt(data.length).put(type).put(data).putInt((int) crc.getValue())
                .array();
        byte[] result = new byte[png.length + chunk.length];
        System.arraycopy(png, 0, result, 0, IHDR_CHUNK_END);
        System.arraycopy(chunk, 0, result, IHDR_CHUNK_END, chunk.length);
        System.arraycopy(png, IHDR_CHUNK_END, result, IHDR_CHUNK_END + chunk.length, png.length - IHDR_CHUNK_END);
        return result;
    }

    /** {@code png} with one IHDR byte replaced and the IHDR CRC recomputed, so only that field is invalid. */
    private static byte[] withIhdrByte(byte[] png, int offset, int value) {
        byte[] patched = png.clone();
        patched[offset] = (byte) value;
        CRC32 crc = new CRC32();
        crc.update(patched, IHDR_TYPE_OFFSET, IHDR_CRC_OFFSET - IHDR_TYPE_OFFSET);
        ByteBuffer.wrap(patched).putInt(IHDR_CRC_OFFSET, (int) crc.getValue());
        return patched;
    }

    /**
     * The chunk types of a PNG stream, walked chunk by chunk; asserts the stream ENDS exactly at the IEND
     * chunk (no bytes after IEND).
     */
    private static List<String> pngChunkTypes(byte[] png, String label) {
        List<String> types = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(png);
        int offset = PNG_MAGIC_LENGTH;
        while (true) {
            Assertions.assertTrue(offset + 12 <= png.length, label + ": truncated PNG chunk at offset " + offset);
            int length = buffer.getInt(offset);
            Assertions.assertTrue(length >= 0 && offset + 12L + length <= png.length,
                    label + ": PNG chunk at offset " + offset + " overruns the stream");
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            types.add(type);
            offset += 12 + length;
            if ("IEND".equals(type)) {
                Assertions.assertEquals(png.length, offset,
                        label + ": the stream must end exactly at IEND (no trailing bytes), chunks " + types);
                return types;
            }
        }
    }

    private static BufferedImage decodePng(byte[] png, String label) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        Assertions.assertNotNull(image, label + ": not a decodable PNG");
        return image;
    }

    /**
     * Spec §1 SERVER NORMALISATION: the stored signature is the decoded upload RE-ENCODED as a clean PNG, so
     * it is never compared byte-for-byte with the upload. Instead: PNG magic; a chunk stream that starts
     * with IHDR and ends exactly at IEND (trailing bytes dropped) with no textual ancillary chunk (tEXt /
     * zTXt / iTXt dropped); the SAME width/height; and the same pixels — alpha for every pixel, RGB for
     * every pixel that is not fully transparent (a fully transparent pixel has no defined colour).
     */
    private static void assertNormalisedSignature(byte[] uploaded, byte[] stored, String label) throws IOException {
        Assertions.assertTrue(stored.length > PNG_MAGIC_LENGTH, label + ": stored signature too short");
        Assertions.assertArrayEquals(Arrays.copyOf(SIGNATURE_PNG, PNG_MAGIC_LENGTH),
                Arrays.copyOf(stored, PNG_MAGIC_LENGTH), label + ": the stored signature must start with the PNG magic");
        List<String> chunks = pngChunkTypes(stored, label + " (stored)");
        Assertions.assertEquals("IHDR", chunks.get(0), label + ": IHDR must be the first chunk " + chunks);
        for (String textual : List.of("tEXt", "zTXt", "iTXt")) {
            Assertions.assertFalse(chunks.contains(textual),
                    label + ": the uploaded ancillary " + textual + " chunk must not be stored " + chunks);
        }

        BufferedImage expected = decodePng(uploaded, label + " (upload)");
        BufferedImage actual = decodePng(stored, label + " (stored)");
        Assertions.assertEquals(expected.getWidth(), actual.getWidth(), label + ": the stored width must match");
        Assertions.assertEquals(expected.getHeight(), actual.getHeight(), label + ": the stored height must match");
        int visiblePixels = 0;
        int mismatches = 0;
        String firstMismatch = null;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y);
                int got = actual.getRGB(x, y);
                int wantAlpha = want >>> 24;
                if (wantAlpha != 0) {
                    visiblePixels++;
                }
                boolean same = wantAlpha == (got >>> 24)
                        && (wantAlpha == 0 || (want & 0xFFFFFF) == (got & 0xFFFFFF));
                if (!same) {
                    mismatches++;
                    if (firstMismatch == null) {
                        firstMismatch = String.format(Locale.ROOT, "(%d,%d) upload %08x stored %08x", x, y, want, got);
                    }
                }
            }
        }
        Assertions.assertTrue(visiblePixels > 0, label + ": the upload must have visible pixels to compare");
        Assertions.assertEquals(0, mismatches,
                label + ": the stored pixels must equal the uploaded pixels; first mismatch " + firstMismatch);
    }

    /** EXACT ARGB equality ({@link BufferedImage#getRGB}) for EVERY pixel — alpha and colour, partial alpha included. */
    private static void assertSameArgbForEveryPixel(BufferedImage expected, BufferedImage actual, String label) {
        Assertions.assertEquals(expected.getWidth(), actual.getWidth(), label + ": width");
        Assertions.assertEquals(expected.getHeight(), actual.getHeight(), label + ": height");
        int mismatches = 0;
        String firstMismatch = null;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y);
                int got = actual.getRGB(x, y);
                if (want != got) {
                    mismatches++;
                    if (firstMismatch == null) {
                        firstMismatch = String.format(Locale.ROOT, "(%d,%d) expected %08x but was %08x", x, y, want, got);
                    }
                }
            }
        }
        Assertions.assertEquals(0, mismatches,
                label + ": every pixel's ARGB must be identical; " + mismatches + " differ, first " + firstMismatch);
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Assertions.assertTrue(ImageIO.write(image, "png", out), "a PNG ImageWriter must be available");
        return out.toByteArray();
    }

    /**
     * A signature-pad-like 64x24 ARGB image: ANTI-ALIASED strokes (a gradient paint plus a translucent
     * colour, so stroke edges carry many partial alphas over varied RGB) and one explicit bottom row holding
     * every {@link #PARTIAL_ALPHAS} value (1 and 254 included) on its own RGB.
     */
    private static BufferedImage semiTransparentSignatureImage() {
        int width = 64;
        int height = 24;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.setPaint(new GradientPaint(0f, 0f, new Color(0x12, 0x34, 0xA6),
                    width, height, new Color(0xC8, 0x3A, 0x1E)));
            graphics.draw(new QuadCurve2D.Double(2.3, 17.6, 15.7, -3.2, 29.4, 14.1));
            graphics.draw(new QuadCurve2D.Double(29.4, 14.1, 41.8, 22.9, 61.2, 3.7));
            graphics.setColor(new Color(0x2E, 0x8B, 0x57, 150));   // a translucent stroke colour
            graphics.draw(new Line2D.Double(5.5, 8.25, 58.75, 12.5));
        } finally {
            graphics.dispose();
        }
        for (int i = 0; i < PARTIAL_ALPHAS.length; i++) {
            int rgb = ((17 * i + 40) & 0xFF) << 16 | ((53 * i + 7) & 0xFF) << 8 | ((91 * i + 150) & 0xFF);
            image.setRGB(2 + i * 4, height - 1, (PARTIAL_ALPHAS[i] << 24) | rgb);
        }
        return image;
    }

    /** Offset of the first {@code type} chunk (walking the chunk stream after the magic), or -1. */
    private static int chunkOffset(byte[] png, String type) {
        ByteBuffer buffer = ByteBuffer.wrap(png);
        int offset = PNG_MAGIC_LENGTH;
        while (offset + 8 <= png.length) {
            if (type.equals(new String(png, offset + 4, 4, StandardCharsets.US_ASCII))) {
                return offset;
            }
            offset += 12 + buffer.getInt(offset);
        }
        return -1;
    }

    /** True iff the chunk at {@code chunkOffset} is complete and its CRC (over type + data) is correct. */
    private static boolean chunkCrcValid(byte[] png, int chunkOffset) {
        int length = ByteBuffer.wrap(png).getInt(chunkOffset);
        if (length < 0 || chunkOffset + 12L + length > png.length) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(png, chunkOffset + 4, 4 + length);
        return ByteBuffer.wrap(png).getInt(chunkOffset + 8 + length) == (int) crc.getValue();
    }

    /** {@code png} with its first IDAT payload overwritten by non-zlib bytes and that chunk's CRC recomputed. */
    private static byte[] withGarbageIdatPayload(byte[] png) {
        byte[] corrupt = png.clone();
        int idat = chunkOffset(corrupt, "IDAT");
        Assertions.assertTrue(idat > 0, "the source PNG must have an IDAT chunk");
        int length = ByteBuffer.wrap(corrupt).getInt(idat);
        byte[] garbage = "corrupt-idat-payload|not-a-zlib-stream|".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < length; i++) {
            corrupt[idat + 8 + i] = garbage[i % garbage.length];
        }
        CRC32 crc = new CRC32();
        crc.update(corrupt, idat + 4, 4 + length);
        ByteBuffer.wrap(corrupt).putInt(idat + 8 + length, (int) crc.getValue());
        Assertions.assertTrue(chunkCrcValid(corrupt, idat), "the corrupted IDAT chunk must keep a valid CRC");
        return corrupt;
    }

    /** {@code png} cut off half-way through its IDAT payload (no IDAT CRC, no IEND). */
    private static byte[] truncatedInsideIdat(byte[] png) {
        int idat = chunkOffset(png, "IDAT");
        Assertions.assertTrue(idat > 0, "the source PNG must have an IDAT chunk");
        int length = ByteBuffer.wrap(png).getInt(idat);
        Assertions.assertTrue(length >= 8, "the IDAT payload must be long enough to cut in half: " + length);
        return Arrays.copyOf(png, idat + 8 + length / 2);
    }

    /**
     * Precondition of a decode-failure fixture: everything the accept checks BEFORE decoding passes — the
     * PNG magic, a first chunk that is a 13-byte IHDR with a valid CRC, dimensions within the safe-decode
     * bounds, bit depth 8 and colour type 6 (RGBA).
     */
    private static void assertPreDecodeHeaderPasses(byte[] png, String label) {
        Assertions.assertArrayEquals(Arrays.copyOf(SIGNATURE_PNG, PNG_MAGIC_LENGTH),
                Arrays.copyOf(png, PNG_MAGIC_LENGTH), label + ": PNG magic");
        Assertions.assertEquals(13, ByteBuffer.wrap(png).getInt(PNG_MAGIC_LENGTH), label + ": IHDR length 13");
        Assertions.assertEquals("IHDR", new String(png, IHDR_TYPE_OFFSET, 4, StandardCharsets.US_ASCII),
                label + ": the first chunk must be IHDR");
        Assertions.assertTrue(chunkCrcValid(png, PNG_MAGIC_LENGTH), label + ": the IHDR CRC must be valid");
        int[] dimensions = declaredPngDimensions(png);
        Assertions.assertTrue(dimensions[0] > 0 && dimensions[1] > 0
                        && dimensions[0] <= MAX_SIGNATURE_SIDE_PX && dimensions[1] <= MAX_SIGNATURE_SIDE_PX
                        && (long) dimensions[0] * dimensions[1] <= MAX_SIGNATURE_PIXELS,
                label + ": dimensions within the safe-decode bounds: " + dimensions[0] + "x" + dimensions[1]);
        Assertions.assertEquals(8, png[IHDR_BIT_DEPTH_OFFSET] & 0xFF, label + ": bit depth 8");
        Assertions.assertEquals(6, png[IHDR_COLOUR_TYPE_OFFSET] & 0xFF, label + ": colour type 6 (RGBA)");
    }

    /** The JDK PNG reader (the ImageIO the accept decodes with) cannot decode {@code png}: it throws or returns null. */
    private static void assertImageIoCannotDecode(byte[] png, String label) {
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException expected) {
            return;
        }
        Assertions.assertNull(decoded, label + ": ImageIO must not decode the fixture");
    }

    // ================================================================
    // 1. Happy path — itemised
    // ================================================================

    @Test
    void accept_itemised_returns201ExactBody_persistsAcceptanceSignatureAndSignedPdf_issuedPdfUntouched()
            throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        String orderNumber = orderNumber(orderId);

        Map<String, Object> issued = issuedVersionRow(orderId);
        long versionId = asLong(issued.get("quote_version_id"));
        Assertions.assertEquals(SNAPSHOT_NAME, issued.get("customer_name_snapshot"));
        Object issuedFileId = issued.get("issued_pdf_file_id");
        Assertions.assertNotNull(issuedFileId, "the issue stored its PDF");
        Map<String, Object> issuedFileBefore = storedFileRow(issuedFileId);
        String issuedPath = (String) issuedFileBefore.get("storage_path");
        byte[] issuedPdfBefore = diskBytes(issuedPath);
        assertPdfBytes(issuedPdfBefore);
        Set<String> diskBefore = diskFilesForOrder(orderId);
        Assertions.assertTrue(diskBefore.contains(fileNameOf(issuedPath)), "issued PDF on disk before acceptance");
        Assertions.assertEquals(1, storedFileCountForOrder(orderId));

        LocalDateTime before = LocalDateTime.now();
        acceptOk(token, SIGNATURE_PNG);
        LocalDateTime after = LocalDateTime.now();

        // quote_version: ISSUED -> ACCEPTED with the frozen acceptance fields.
        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        Assertions.assertNotNull(version.get("accepted_at"), "accepted_at must be stamped");
        LocalDateTime acceptedAt = asLocalDateTime(version.get("accepted_at"));
        Assertions.assertFalse(acceptedAt.isBefore(before.minusSeconds(1)), "accepted_at too early: " + acceptedAt);
        Assertions.assertFalse(acceptedAt.isAfter(after.plusSeconds(1)), "accepted_at too late: " + acceptedAt);
        Assertions.assertEquals(SNAPSHOT_NAME, version.get("accepted_customer_name"),
                "the accepted name is the V17 issue snapshot");
        Assertions.assertEquals(version.get("customer_name_snapshot"), version.get("accepted_customer_name"));
        Assertions.assertNotNull(version.get("accepted_signature_file_id"));
        Assertions.assertNotNull(version.get("signed_pdf_file_id"));
        Assertions.assertEquals(issuedFileId, version.get("issued_pdf_file_id"), "the issued PDF ref never moves");
        Assertions.assertEquals(2, versionLineCount(orderId), "the snapshot lines are untouched");

        // quote_token: ACTIVE -> CONSUMED, dead_at == accepted_at (== the order header's updated_at).
        Map<String, Object> consumed = tokenRow(token);
        Assertions.assertEquals("CONSUMED", consumed.get("status"));
        Assertions.assertEquals(versionId, asLong(consumed.get("quote_version_id")));
        Assertions.assertEquals(version.get("accepted_at"), consumed.get("dead_at"),
                "the token dies at the acceptance instant");
        Assertions.assertEquals(version.get("accepted_at"), jdbcTemplate.queryForObject(
                        "SELECT updated_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId),
                "the order header write shares the acceptance instant");

        // Signature: stored_file row + the SERVER-NORMALISED re-encoding of the upload on disk (spec §1 —
        // the same image, never the raw upload bytes).
        Map<String, Object> signatureFile = storedFileRow(version.get("accepted_signature_file_id"));
        Assertions.assertEquals("quote-signature-" + orderNumber + "-v1.png", signatureFile.get("file_name"));
        Assertions.assertEquals("image/png", signatureFile.get("mime_type"));
        String signaturePath = (String) signatureFile.get("storage_path");
        Assertions.assertTrue(signaturePath.startsWith(orderStoragePrefix(orderId)) && signaturePath.endsWith(".png"),
                "unexpected signature storage_path " + signaturePath);
        byte[] storedSignature = diskBytes(signaturePath);
        Assertions.assertEquals((long) storedSignature.length, asLong(signatureFile.get("file_size")),
                "stored_file.file_size is the stored (normalised) byte length");
        assertNormalisedSignature(SIGNATURE_PNG, storedSignature, "stored signature");

        // Signed PDF: stored_file row + a real PDF on disk.
        Map<String, Object> signedFile = storedFileRow(version.get("signed_pdf_file_id"));
        Assertions.assertEquals("quote-" + orderNumber + "-v1-signed.pdf", signedFile.get("file_name"));
        Assertions.assertEquals("application/pdf", signedFile.get("mime_type"));
        String signedPath = (String) signedFile.get("storage_path");
        Assertions.assertTrue(signedPath.startsWith(orderStoragePrefix(orderId)) && signedPath.endsWith(".pdf"),
                "unexpected signed PDF storage_path " + signedPath);
        Assertions.assertNotEquals(issuedPath, signedPath, "the signed PDF is a NEW file, never the issued one");
        byte[] signedPdf = diskBytes(signedPath);
        assertPdfBytes(signedPdf);
        Assertions.assertEquals((long) signedPdf.length, asLong(signedFile.get("file_size")));

        String text = pdfText(signedPdf);
        String flatText = flat(text);
        Assertions.assertTrue(STANDALONE_QUOTATION.matcher(flatText).find(), "missing QUOTATION title: " + flatText);
        Assertions.assertTrue(flatText.contains("QUOTATION TO"), "missing Quotation To block: " + flatText);
        Assertions.assertTrue(flatText.contains(SNAPSHOT_NAME), "missing frozen customer name: " + flatText);
        Assertions.assertTrue(flatText.contains("12 Test Street"), "missing frozen billing line: " + flatText);
        Assertions.assertTrue(flatText.contains("Carpet"), "missing snapshot line Carpet: " + flatText);
        Assertions.assertTrue(flatText.contains("Underlay"), "missing snapshot line Underlay: " + flatText);
        Assertions.assertTrue(noSpace(text).contains(noSpace(orderNumber)), "missing order number: " + flatText);
        Assertions.assertTrue(noSpace(text).contains("$275.00"), "missing frozen inc-GST total: " + flatText);
        Assertions.assertTrue(noSpace(text).contains("$110.00"), "missing 40% deposit: " + flatText);
        assertAcceptedCaption(text, SNAPSHOT_NAME, acceptedAt);
        Assertions.assertFalse(flatText.contains("Customer signature"),
                "the signed render replaces the blank 'Customer signature' caption: " + flatText);
        Assertions.assertEquals(countImages(issuedPdfBefore) + 1, countImages(signedPdf),
                "the signature is embedded as exactly one extra image");

        // The issued PDF is untouched: same stored_file row, same bytes on disk, still unsigned.
        Assertions.assertEquals(issuedFileBefore, storedFileRow(issuedFileId));
        Assertions.assertArrayEquals(issuedPdfBefore, diskBytes(issuedPath),
                "the issued PDF on disk must be byte-identical after acceptance");
        Assertions.assertFalse(pdfText(issuedPdfBefore).contains("Accepted by"), "the issued PDF stays unsigned");

        // Exactly two new files for the order: the signature and the signed PDF.
        Assertions.assertEquals(3, storedFileCountForOrder(orderId));
        Set<String> expectedDisk = new TreeSet<>(diskBefore);
        expectedDisk.add(fileNameOf(signaturePath));
        expectedDisk.add(fileNameOf(signedPath));
        Assertions.assertEquals(expectedDisk, diskFilesForOrder(orderId));
    }

    // ================================================================
    // 2. Non-itemised with dormant draft rows
    // ================================================================

    @Test
    void accept_nonItemisedWithDormantDraftRows_signedPdfShowsDetailsAndTotals_neverTheDormantLines()
            throws Exception {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        seedBillingAddress(orderId);
        clearTenantTerms();
        setDetailsOfSale(orderId, "Supply only, non-itemised acceptance");
        saveItemisedTwoLineDraft(orderId);          // persists Carpet + Underlay
        saveNonItemisedDraft(orderId, "330.00");    // header-only: the two rows stay DORMANT
        Assertions.assertEquals(2, draftLineCount(orderId), "dormant draft rows retained before the issue");
        String token = issueAndExtractToken(orderId);
        Map<String, Object> issued = issuedVersionRow(orderId);
        long versionId = asLong(issued.get("quote_version_id"));
        Assertions.assertEquals(Boolean.FALSE, issued.get("itemised"));

        acceptOk(token, SIGNATURE_PNG);

        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        Assertions.assertEquals(0, versionLineCount(orderId),
                "a non-itemised version has (and keeps) ZERO quote_version_line rows");
        Assertions.assertEquals(2, draftLineCount(orderId), "acceptance never touches the dormant draft rows");

        String text = pdfText(diskBytes(storagePathOf(version.get("signed_pdf_file_id"))));
        String flatText = flat(text);
        Assertions.assertFalse(flatText.contains("Carpet"), "dormant draft line rendered: " + flatText);
        Assertions.assertFalse(flatText.contains("Underlay"), "dormant draft line rendered: " + flatText);
        Assertions.assertFalse(flatText.toUpperCase(Locale.ROOT).contains("DESCRIPTION"),
                "a non-itemised signed quote has no line table: " + flatText);
        Assertions.assertTrue(flatText.contains("Supply only, non-itemised acceptance"),
                "missing frozen details of sale: " + flatText);
        String compact = noSpace(text);
        Assertions.assertTrue(compact.contains("$300.00"), "missing subtotal ex GST: " + flatText);
        Assertions.assertTrue(compact.contains("$30.00"), "missing GST: " + flatText);
        Assertions.assertTrue(compact.contains("$330.00"), "missing total inc GST: " + flatText);
        Assertions.assertTrue(compact.contains("$132.00"), "missing 40% deposit: " + flatText);
        assertAcceptedCaption(text, SNAPSHOT_NAME, asLocalDateTime(version.get("accepted_at")));
    }

    // ================================================================
    // 3. Snapshot drift — everything live changes after the issue
    // ================================================================

    @Test
    void accept_afterLiveDrift_acceptsIssueSnapshotName_andSignedPdfRendersOnlyIssueTimeContent() throws Exception {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        // A billing suburb distinct from the store's own "Sydney NSW 2000" address line.
        seedBillingAddress(orderId, "12", "Test Street", "Bondi", "NSW", "2026");
        setTenantTerms("<p>Issue time terms alpha</p>");
        setDetailsOfSale(orderId, "Issue time details");
        saveItemisedTwoLineDraft(orderId);
        String token = issueAndExtractToken(orderId);

        Map<String, Object> issued = issuedVersionRow(orderId);
        long versionId = asLong(issued.get("quote_version_id"));
        Assertions.assertEquals(SNAPSHOT_NAME, issued.get("customer_name_snapshot"));
        Assertions.assertEquals("12 Test Street", issued.get("customer_address_line1_snapshot"));
        Assertions.assertEquals("Bondi NSW 2026", issued.get("customer_address_line2_snapshot"));
        Assertions.assertEquals("Issue time details", issued.get("details_of_sale_snapshot"));
        Assertions.assertEquals("<p>Issue time terms alpha</p>", issued.get("terms_snapshot"));

        // Drift EVERY live source after the issue (no resend: v1 stays the ISSUED version behind the link).
        saveDraft(orderId, """
                {"itemised": true, "lines": [
                  {"line_type":"ITEM","description":"Drifted line","quantity":1,"unit_price_ex_gst":999,"line_total_ex_gst":999,"sort_order":0}
                ]}""");
        setDetailsOfSale(orderId, "Drifted details");
        jdbcTemplate.update("UPDATE order_customer SET first_name = 'Changed', middle_name = NULL, "
                + "last_name = 'Person' WHERE order_id = ?", orderId);
        jdbcTemplate.update("UPDATE order_address SET unit_number = NULL, street_number = '99', "
                + "street = 'Changed Street', suburb = 'Melbourne', state_code = 'VIC', postcode = '3000' "
                + "WHERE order_id = ?", orderId);
        setTenantTerms("<p>Drifted terms omega</p>");
        Assertions.assertEquals("ISSUED", versionRowById(versionId).get("status"));

        acceptOk(token, SIGNATURE_PNG);

        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        Assertions.assertEquals(SNAPSHOT_NAME, version.get("accepted_customer_name"),
                "the accepted name is the ISSUE snapshot, never the live 'Changed Person'");
        // The frozen snapshot columns themselves are never rewritten by acceptance.
        Assertions.assertEquals(SNAPSHOT_NAME, version.get("customer_name_snapshot"));
        Assertions.assertEquals("Bondi NSW 2026", version.get("customer_address_line2_snapshot"));
        Assertions.assertEquals("Issue time details", version.get("details_of_sale_snapshot"));
        Assertions.assertEquals("<p>Issue time terms alpha</p>", version.get("terms_snapshot"));
        assertMoney("275.00", version.get("quote_total_inc_gst"));
        // The order-price write follows the FROZEN inc total (no cost lines: 275.00 - 0.00), never the
        // drifted 1,098.90 draft.
        assertMoney("275.00", orderMoneyColumn(orderId, "price_adjustment_inc_gst"));
        assertMoney("250.00", orderMoneyColumn(orderId, "sale_price_ex_gst"));

        String text = pdfText(diskBytes(storagePathOf(version.get("signed_pdf_file_id"))));
        String flatText = flat(text);
        String compact = noSpace(text);
        for (String expected : List.of("Issue time details", SNAPSHOT_NAME, "12 Test Street", "Bondi NSW 2026",
                "Issue time terms alpha", "Carpet", "Underlay")) {
            Assertions.assertTrue(flatText.contains(expected),
                    "signed PDF must carry the issue-time value '" + expected + "': " + flatText);
        }
        Assertions.assertTrue(compact.contains("$275.00"), "missing frozen total: " + flatText);
        for (String drifted : List.of("Drifted", "Changed", "Melbourne", "VIC 3000")) {
            Assertions.assertFalse(flatText.contains(drifted),
                    "signed PDF leaked the post-issue value '" + drifted + "': " + flatText);
        }
        Assertions.assertFalse(compact.contains("$1,098.90"), "drifted draft total leaked: " + flatText);
        Assertions.assertFalse(compact.contains("$999.00"), "drifted draft line leaked: " + flatText);
        assertAcceptedCaption(text, SNAPSHOT_NAME, asLocalDateTime(version.get("accepted_at")));
    }

    // ================================================================
    // 4. Terms absent at issue stay absent (frozen absence)
    // ================================================================

    @Test
    void accept_termsAbsentAtIssue_setBeforeAccept_signedPdfHasNoTermsPage() throws Exception {
        long orderId = itemisedReadyOrder();   // tenant terms NULL at issue
        String token = issueAndExtractToken(orderId);
        Map<String, Object> issued = issuedVersionRow(orderId);
        long versionId = asLong(issued.get("quote_version_id"));
        Assertions.assertNull(issued.get("terms_snapshot"), "no terms were frozen at issue");
        byte[] issuedPdf = diskBytes(storagePathOf(issued.get("issued_pdf_file_id")));
        Assertions.assertEquals(1, pageCount(issuedPdf), "the issued PDF has no terms page");

        setTenantTerms("<p>Late terms must never reach the signed quote</p>");

        acceptOk(token, SIGNATURE_PNG);

        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        Assertions.assertNull(version.get("terms_snapshot"), "acceptance never re-freezes terms");
        byte[] signedPdf = diskBytes(storagePathOf(version.get("signed_pdf_file_id")));
        String text = pdfText(signedPdf);
        Assertions.assertEquals(1, pageCount(signedPdf), "no terms page on the signed quote: " + flat(text));
        Assertions.assertFalse(text.contains("Late terms"), "live terms leaked into the signed PDF: " + flat(text));
        Assertions.assertFalse(text.contains("TERMS"), "no terms heading on the signed PDF: " + flat(text));
        Assertions.assertEquals(1, countOccurrences(text, "Generated by"), "footer renders exactly once");
        assertAcceptedCaption(text, SNAPSHOT_NAME, asLocalDateTime(version.get("accepted_at")));
    }

    // ================================================================
    // 5. Long frozen name (> 150 chars) — stored and rendered untruncated
    // ================================================================

    @Test
    void accept_longFrozenNameOver150Chars_isAccepted_storedAndRenderedUntruncated() throws Exception {
        String first = "Bartholomew Maximilian Alexander Fitzgerald Montgomery Wellington Harrington Smithers";
        String middle = "Evangeline Seraphina Josephine Annabelle Clementine Rosalind Genevieve Wilhelmina";
        String last = "Abernathy-Kensington Fairweather Thistlewood Underhill Blackwood Ravenscroft Pemberton";
        Assertions.assertTrue(first.length() <= 100 && middle.length() <= 100 && last.length() <= 100,
                "each part must fit order_customer's VARCHAR(100)");
        String fullName = first + " " + middle + " " + last;
        Assertions.assertTrue(fullName.length() > 150, "the frozen name must exceed the old 150 cap");

        long orderId = insertOrder("LEAD");
        seedCustomerWithFullName(orderId, first, middle, last);
        seedBillingAddress(orderId);
        clearTenantTerms();
        saveItemisedTwoLineDraft(orderId);
        String token = issueAndExtractToken(orderId);
        Map<String, Object> issued = issuedVersionRow(orderId);
        long versionId = asLong(issued.get("quote_version_id"));
        Assertions.assertEquals(fullName, issued.get("customer_name_snapshot"));

        acceptOk(token, SIGNATURE_PNG);

        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        String acceptedName = (String) version.get("accepted_customer_name");
        Assertions.assertEquals(fullName, acceptedName, "the accepted name is stored in full (never truncated)");
        Assertions.assertTrue(acceptedName.length() > 150, "stored length " + acceptedName.length());

        String text = pdfText(diskBytes(storagePathOf(version.get("signed_pdf_file_id"))));
        String compact = noSpace(text);
        Assertions.assertTrue(compact.contains(noSpace("Accepted by " + fullName + " on ")),
                "the caption must carry the FULL name: " + flat(text));
        Assertions.assertTrue(countOccurrences(compact, noSpace(fullName)) >= 2,
                "the full name must render in both Quotation To and the caption: " + flat(text));
        assertAcceptedCaption(text, fullName, asLocalDateTime(version.get("accepted_at")));
    }

    // ================================================================
    // 6. Missing / blank frozen name — 422, nothing written, no live fallback
    // ================================================================

    @Test
    void accept_missingOrBlankFrozenName_returns422ContactStore_noStateChange_noLiveFallback() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));

        for (String frozenNameSql : List.of("NULL", "'   '")) {
            jdbcTemplate.update("UPDATE quote_version SET customer_name_snapshot = " + frozenNameSql
                    + " WHERE quote_version_id = ?", versionId);
            clearJpaCache();
            Map<String, Object> before = sideEffectState(orderId);
            String label = "customer_name_snapshot = " + frozenNameSql;

            assertError(postAccept(token, signaturePart(SIGNATURE_PNG)),
                    422, "ACCEPTED_CUSTOMER_NAME_REQUIRED", CONTACT_STORE_MESSAGE, label);

            assertNoSideEffects(orderId, before, label);
            assertStillIssuedWithActiveToken(versionId, token);
            Assertions.assertNull(orderMoneyColumn(orderId, "price_adjustment_inc_gst"),
                    label + ": the order price must not be written");
        }

        // The live customer record still has a perfectly good name — the accept never falls back to it.
        Assertions.assertEquals("Quote", jdbcTemplate.queryForObject(
                "SELECT first_name FROM order_customer WHERE order_id = ?", String.class, orderId));
        // The link is still live for the customer.
        mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
    }

    // ================================================================
    // 7. Multipart allowlist — exactly one signature part, nothing else
    // ================================================================

    @Test
    void accept_multipartAllowlistViolations_return400ValidationFailed_beforeSignatureRules_noStateChange()
            throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        Map<String, Object> before = sideEffectState(orderId);

        // (a) An extra FILE part next to a valid signature.
        JsonNode error = assertError(postAccept(token, signaturePart(SIGNATURE_PNG), extraPhotoPart()),
                400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE, "extra file part");
        assertSingleDetail(error, "photo", "Not allowed.", "extra file part");
        assertNoSideEffects(orderId, before, "extra file part");

        // (b)-(d) ANY form field is rejected: a typed name, a declaration flag, a money value.
        Map<String, String> forbiddenFields = new LinkedHashMap<>();
        forbiddenFields.put("accepted_customer_name", "Mallory Typed-Name");
        forbiddenFields.put("declaration", "true");
        forbiddenFields.put("quote_total_inc_gst", "1.00");
        for (Map.Entry<String, String> field : forbiddenFields.entrySet()) {
            String label = "form field " + field.getKey();
            JsonNode fieldError = assertError(
                    postAcceptWithFields(token, Map.of(field.getKey(), field.getValue()), signaturePart(SIGNATURE_PNG)),
                    400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE, label);
            assertSingleDetail(fieldError, field.getKey(), "Not allowed.", label);
            assertNoSideEffects(orderId, before, label);
        }

        // (e) Two "signature" parts.
        JsonNode duplicate = assertError(
                postAccept(token, signaturePart(SIGNATURE_PNG), signaturePart(SIGNATURE_PNG)),
                400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE, "duplicate signature parts");
        assertSingleDetail(duplicate, "signature", "Must appear at most once.", "duplicate signature parts");
        assertNoSideEffects(orderId, before, "duplicate signature parts");

        // (f) The allowlist runs BEFORE the signature rules: a form field with NO signature is a 400, not 422.
        JsonNode noSignature = assertError(postAcceptWithFields(token, Map.of("declaration", "true")),
                400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE, "form field without a signature");
        assertSingleDetail(noSignature, "declaration", "Not allowed.", "form field without a signature");
        assertNoSideEffects(orderId, before, "form field without a signature");

        assertStillIssuedWithActiveToken(versionId, token);
    }

    private static void assertSingleDetail(JsonNode error, String field, String message, String label) {
        JsonNode details = error.path("details");
        Assertions.assertTrue(details.isArray() && details.size() == 1,
                label + ": expected exactly one error detail, got " + details);
        Assertions.assertEquals(field, details.get(0).path("field").asText(null), label + ": " + details);
        Assertions.assertEquals(message, details.get(0).path("message").asText(null), label + ": " + details);
    }

    // ================================================================
    // 8. Signature rules
    // ================================================================

    @Test
    void accept_missingOrEmptySignature_returns422SignatureRequired_noStateChange() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        Map<String, Object> before = sideEffectState(orderId);

        assertError(postAccept(token), 422, "SIGNATURE_REQUIRED", SIGNATURE_REQUIRED_MESSAGE,
                "no signature part");
        assertNoSideEffects(orderId, before, "no signature part");

        assertError(postAccept(token, signaturePart(new byte[0])), 422, "SIGNATURE_REQUIRED",
                SIGNATURE_REQUIRED_MESSAGE, "empty signature part");
        assertNoSideEffects(orderId, before, "empty signature part");

        assertStillIssuedWithActiveToken(versionId, token);
    }

    @Test
    void accept_invalidSignature_typeBytesDimensionsOrSize_returns400SignatureInvalid_noStateChange()
            throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        Map<String, Object> before = sideEffectState(orderId);

        // Spec §1 safe-decode bounds: <= 8,192 px per side, <= 4,000,000 px, bit depth <= 8, valid colour type.
        // A small, valid PNG whose header DECLARES 8193x1: one pixel over the per-side bound (8,193 px in
        // total — far under the pixel cap, so only the per-side rule can reject it).
        byte[] tooWide = pngOfSize(8_193, 1, BufferedImage.TYPE_INT_RGB);
        Assertions.assertArrayEquals(new int[] {8_193, 1}, declaredPngDimensions(tooWide));
        Assertions.assertTrue(tooWide.length < MAX_SIGNATURE_BYTES, "rejection must be about dimensions, not size");
        // A small, valid PNG declaring 2000x2001: each side within 8,192 but 4,002,000 px > 4,000,000.
        byte[] tooManyPixels = pngOfSize(2_000, 2_001, BufferedImage.TYPE_BYTE_BINARY);
        Assertions.assertArrayEquals(new int[] {2_000, 2_001}, declaredPngDimensions(tooManyPixels));
        Assertions.assertTrue(tooManyPixels.length < MAX_SIGNATURE_BYTES, "rejection must be about pixels, not size");
        // A small 16-bit greyscale PNG the JDK decodes fine: only the bit-depth <= 8 rule can reject it.
        byte[] sixteenBit = pngOfSize(4, 4, BufferedImage.TYPE_USHORT_GRAY);
        Assertions.assertEquals(16, sixteenBit[IHDR_BIT_DEPTH_OFFSET] & 0xFF, "IHDR must declare bit depth 16");
        Assertions.assertNotNull(ImageIO.read(new ByteArrayInputStream(sixteenBit)),
                "rejection must be about bit depth, not decodability");
        // The valid 1x1 PNG with its IHDR colour type set to 1 (not a PNG colour type), CRC recomputed.
        byte[] invalidColourType = withIhdrByte(SIGNATURE_PNG, IHDR_COLOUR_TYPE_OFFSET, 1);
        // A VALID PNG padded with zeros after IEND to one byte over the 2 MB cap.
        byte[] oneByteOver = Arrays.copyOf(SIGNATURE_PNG, MAX_SIGNATURE_BYTES + 1);

        Map<String, MockMultipartFile> invalid = new LinkedHashMap<>();
        invalid.put("declared image/jpeg (valid PNG bytes)",
                new MockMultipartFile("signature", "signature.jpg", "image/jpeg", SIGNATURE_PNG));
        invalid.put("declared image/png but not PNG bytes",
                signaturePart("definitely not a png image".getBytes(StandardCharsets.US_ASCII)));
        invalid.put("PNG magic followed by garbage", signaturePart(pngMagicFollowedByGarbage()));
        invalid.put("header declares 8193x1", signaturePart(tooWide));
        invalid.put("header declares 2000x2001", signaturePart(tooManyPixels));
        invalid.put("16-bit greyscale PNG", signaturePart(sixteenBit));
        invalid.put("IHDR colour type 1", signaturePart(invalidColourType));
        invalid.put("valid PNG padded to 2,097,153 bytes", signaturePart(oneByteOver));

        for (Map.Entry<String, MockMultipartFile> invalidCase : invalid.entrySet()) {
            assertError(postAccept(token, invalidCase.getValue()), 400, "SIGNATURE_INVALID",
                    SIGNATURE_INVALID_MESSAGE, invalidCase.getKey());
            assertNoSideEffects(orderId, before, invalidCase.getKey());
        }
        assertStillIssuedWithActiveToken(versionId, token);
    }

    @Test
    void accept_validPngPaddedToExactly2MiB_isAccepted_andStoredAsTheNormalisedImage() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        // A signature-like PNG carrying an ancillary tEXt chunk, padded with zeros AFTER IEND (still
        // decodable) to EXACTLY 2,097,152 bytes — the largest allowed upload.
        byte[] upload = withTextChunk(strokeSignaturePng(), "Comment", TEXT_CHUNK_MARKER);
        Assertions.assertTrue(pngChunkTypes(upload, "upload").contains("tEXt"), "the upload must carry a tEXt chunk");
        byte[] exactlyTwoMiB = Arrays.copyOf(upload, MAX_SIGNATURE_BYTES);
        Assertions.assertEquals(2_097_152, exactlyTwoMiB.length);

        acceptOk(token, exactlyTwoMiB);

        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        Assertions.assertEquals("CONSUMED", tokenRow(token).get("status"));
        Map<String, Object> signatureFile = storedFileRow(version.get("accepted_signature_file_id"));
        byte[] storedSignature = diskBytes((String) signatureFile.get("storage_path"));
        // Spec §1: the server stores its OWN re-encoding — the zero padding and the tEXt chunk are gone,
        // while the image (dimensions + pixels) is the uploaded one.
        Assertions.assertEquals((long) storedSignature.length, asLong(signatureFile.get("file_size")),
                "stored_file.file_size is the stored (normalised) byte length, never the 2 MiB upload size");
        Assertions.assertTrue(storedSignature.length < MAX_SIGNATURE_BYTES,
                "the post-IEND padding must not be stored: " + storedSignature.length + " bytes");
        Assertions.assertFalse(new String(storedSignature, StandardCharsets.ISO_8859_1).contains(TEXT_CHUNK_MARKER),
                "the uploaded tEXt payload must not be stored");
        assertNormalisedSignature(exactlyTwoMiB, storedSignature, "2 MiB upload");
        byte[] signedPdf = diskBytes(storagePathOf(version.get("signed_pdf_file_id")));
        assertPdfBytes(signedPdf);
        assertAcceptedCaption(pdfText(signedPdf), SNAPSHOT_NAME, asLocalDateTime(version.get("accepted_at")));
    }

    /**
     * The DECODE-FAILURE branch: a real 48x16 RGBA PNG whose header passes every pre-decode rule (magic,
     * 13-byte IHDR with a valid CRC, dimensions within bounds, bit depth 8, colour type 6) but whose pixel
     * data cannot be decoded — (1) the IDAT payload replaced with non-zlib bytes (chunk CRC recomputed, so
     * the chunk stream itself is well-formed) and (2) the stream truncated half-way through IDAT. ImageIO
     * cannot decode either, and the accept answers 400 SIGNATURE_INVALID with nothing written.
     */
    @Test
    void accept_validHeaderButUndecodableIdat_returns400SignatureInvalid_noStateChange() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        Map<String, Object> before = sideEffectState(orderId);

        byte[] source = strokeSignaturePng();   // 48x16 TYPE_INT_ARGB -> 8-bit RGBA (colour type 6)
        Assertions.assertNotNull(ImageIO.read(new ByteArrayInputStream(source)), "the source PNG itself decodes");
        Map<String, byte[]> undecodable = new LinkedHashMap<>();
        undecodable.put("IDAT payload replaced with garbage (CRC recomputed)", withGarbageIdatPayload(source));
        undecodable.put("stream truncated half-way through IDAT", truncatedInsideIdat(source));

        for (Map.Entry<String, byte[]> fixture : undecodable.entrySet()) {
            String label = fixture.getKey();
            byte[] png = fixture.getValue();
            Assertions.assertTrue(png.length < MAX_SIGNATURE_BYTES, label + ": rejection must not be about size");
            Assertions.assertArrayEquals(new int[] {48, 16}, declaredPngDimensions(png), label);
            assertPreDecodeHeaderPasses(png, label);
            assertImageIoCannotDecode(png, label);

            assertError(postAccept(token, signaturePart(png)), 400, "SIGNATURE_INVALID",
                    SIGNATURE_INVALID_MESSAGE, label);
            assertNoSideEffects(orderId, before, label);
        }
        assertStillIssuedWithActiveToken(versionId, token);
    }

    /**
     * Server normalisation keeps a SEMI-TRANSPARENT signature exact: an anti-aliased ARGB signature (many
     * partial alphas 1..254 over varied RGB) is accepted and the STORED re-encoded PNG decodes to exactly
     * the same ARGB value for EVERY pixel as the upload — partial-alpha pixels keep both alpha and colour.
     */
    @Test
    void accept_semiTransparentAntiAliasedSignature_storedSignatureKeepsExactArgbOfEveryPixel() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));

        BufferedImage drawn = semiTransparentSignatureImage();
        byte[] upload = encodePng(drawn);
        Assertions.assertEquals(8, upload[IHDR_BIT_DEPTH_OFFSET] & 0xFF, "an 8-bit PNG (a canvas export)");
        Assertions.assertEquals(6, upload[IHDR_COLOUR_TYPE_OFFSET] & 0xFF, "an RGBA PNG (colour type 6)");
        BufferedImage uploaded = decodePng(upload, "upload");
        // The fixture is meaningful: its PNG encoding is the drawn ARGB exactly, and it carries many
        // partial-alpha pixels (both extremes) over varied colours.
        assertSameArgbForEveryPixel(drawn, uploaded, "the PNG encoding of the drawn fixture");
        int partialAlphaPixels = 0;
        Set<Integer> partialAlphas = new TreeSet<>();
        Set<Integer> partialAlphaColours = new TreeSet<>();
        for (int y = 0; y < uploaded.getHeight(); y++) {
            for (int x = 0; x < uploaded.getWidth(); x++) {
                int argb = uploaded.getRGB(x, y);
                int alpha = argb >>> 24;
                if (alpha > 0 && alpha < 255) {
                    partialAlphaPixels++;
                    partialAlphas.add(alpha);
                    partialAlphaColours.add(argb & 0xFFFFFF);
                }
            }
        }
        Assertions.assertTrue(partialAlphaPixels >= 40, "partial-alpha pixels: " + partialAlphaPixels);
        Assertions.assertTrue(partialAlphas.contains(1) && partialAlphas.contains(254),
                "both partial-alpha extremes present: " + partialAlphas);
        Assertions.assertTrue(partialAlphas.size() >= PARTIAL_ALPHAS.length,
                "many distinct partial alphas: " + partialAlphas);
        Assertions.assertTrue(partialAlphaColours.size() >= 10,
                "varied colours on partial-alpha pixels: " + partialAlphaColours.size());

        acceptOk(token, upload);

        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        Map<String, Object> signatureFile = storedFileRow(version.get("accepted_signature_file_id"));
        byte[] stored = diskBytes((String) signatureFile.get("storage_path"));
        Assertions.assertEquals((long) stored.length, asLong(signatureFile.get("file_size")),
                "stored_file.file_size is the stored (normalised) byte length");
        Assertions.assertEquals("IHDR", pngChunkTypes(stored, "stored signature").get(0));
        assertSameArgbForEveryPixel(uploaded, decodePng(stored, "stored signature"),
                "the stored signature vs the upload");
    }

    // ================================================================
    // 9. Token gate first — a dead link wins over an invalid body
    // ================================================================

    @Test
    void accept_cancelledLinkWithInvalidBody_returns410Cancelled_tokenGateBeforeBodyValidation() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        cancelOk(orderId);
        Map<String, Object> before = sideEffectState(orderId);

        assertError(postAccept(token, signaturePart(SIGNATURE_PNG), extraPhotoPart()),
                410, "QUOTE_LINK_CANCELLED", LINK_CANCELLED_MESSAGE, "cancelled + extra file part");
        assertError(postAcceptWithFields(token, Map.of("accepted_customer_name", "Mallory"),
                        signaturePart(SIGNATURE_PNG)),
                410, "QUOTE_LINK_CANCELLED", LINK_CANCELLED_MESSAGE, "cancelled + form field");
        assertError(postAccept(token),
                410, "QUOTE_LINK_CANCELLED", LINK_CANCELLED_MESSAGE, "cancelled + no signature");
        assertError(postAccept(token, new MockMultipartFile("signature", "signature.jpg", "image/jpeg", SIGNATURE_PNG)),
                410, "QUOTE_LINK_CANCELLED", LINK_CANCELLED_MESSAGE, "cancelled + JPEG signature");

        assertNoSideEffects(orderId, before, "cancelled link");
        Assertions.assertEquals("CANCELLED", versionRowById(versionId).get("status"));
        Assertions.assertEquals("CANCELLED", tokenRow(token).get("status"));
    }

    // ================================================================
    // 10. Token states
    // ================================================================

    @Test
    void accept_malformedOrUnknownToken_returns404QuoteTokenNotFound_evenWithAnInvalidBody() throws Exception {
        for (String badToken : List.of("short", "A".repeat(42) + "!", "A".repeat(129), UNKNOWN_TOKEN)) {
            assertError(postAccept(badToken, signaturePart(SIGNATURE_PNG)),
                    404, "QUOTE_TOKEN_NOT_FOUND", TOKEN_NOT_FOUND_MESSAGE, "token " + badToken);
            // The token gate precedes body validation: an invalid body still yields the 404.
            assertError(postAcceptWithFields(badToken, Map.of("declaration", "true")),
                    404, "QUOTE_TOKEN_NOT_FOUND", TOKEN_NOT_FOUND_MESSAGE, "token " + badToken + " + invalid body");
        }
        Assertions.assertTrue(acceptanceNotificationSender.sentNotifications().isEmpty());
    }

    @Test
    void accept_replacedTokenAfterUnchangedResend_returns410Superseded_latestLinkStillAccepts() throws Exception {
        long orderId = itemisedReadyOrder();
        String oldToken = issueAndExtractToken(orderId);
        String newToken = issueAndExtractToken(orderId);   // unchanged draft -> same version, old token REPLACED
        Assertions.assertNotEquals(oldToken, newToken);
        Assertions.assertEquals("REPLACED", tokenRow(oldToken).get("status"));
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        Map<String, Object> before = sideEffectState(orderId);

        assertError(postAccept(oldToken, signaturePart(SIGNATURE_PNG)),
                410, "QUOTE_LINK_SUPERSEDED", LINK_SUPERSEDED_MESSAGE, "replaced token");
        assertNoSideEffects(orderId, before, "replaced token");
        assertStillIssuedWithActiveToken(versionId, newToken);

        // The latest link still signs the same (only) version; the dead token is never touched.
        Map<String, Object> replacedRow = tokenRow(oldToken);
        acceptOk(newToken, SIGNATURE_PNG);
        Assertions.assertEquals("ACCEPTED", versionRowById(versionId).get("status"));
        Assertions.assertEquals("CONSUMED", tokenRow(newToken).get("status"));
        Assertions.assertEquals(replacedRow, tokenRow(oldToken), "the REPLACED token row must not change");
    }

    @Test
    void accept_supersededTokenAfterChangedResend_returns410Superseded() throws Exception {
        long orderId = itemisedReadyOrder();
        String v1Token = issueAndExtractToken(orderId);
        saveNonItemisedDraft(orderId, "440.00");           // changed draft -> NEW version on resend
        String v2Token = issueAndExtractToken(orderId);
        Assertions.assertEquals("SUPERSEDED", tokenRow(v1Token).get("status"));
        Assertions.assertEquals("SUPERSEDED", versionRowByNumber(orderId, 1).get("status"));
        long v2Id = asLong(versionRowByNumber(orderId, 2).get("quote_version_id"));
        Map<String, Object> before = sideEffectState(orderId);

        assertError(postAccept(v1Token, signaturePart(SIGNATURE_PNG)),
                410, "QUOTE_LINK_SUPERSEDED", LINK_SUPERSEDED_MESSAGE, "superseded token");

        assertNoSideEffects(orderId, before, "superseded token");
        Assertions.assertEquals("SUPERSEDED", versionRowByNumber(orderId, 1).get("status"));
        assertStillIssuedWithActiveToken(v2Id, v2Token);
    }

    @Test
    void accept_cancelledLink_returns410Cancelled_noStateChange() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        cancelOk(orderId);
        Map<String, Object> before = sideEffectState(orderId);

        assertError(postAccept(token, signaturePart(SIGNATURE_PNG)),
                410, "QUOTE_LINK_CANCELLED", LINK_CANCELLED_MESSAGE, "cancelled link");

        assertNoSideEffects(orderId, before, "cancelled link");
        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("CANCELLED", version.get("status"));
        Assertions.assertNull(version.get("accepted_at"));
        Assertions.assertEquals("CANCELLED", tokenRow(token).get("status"));
    }

    @Test
    void accept_expiredLink_returns410Expired_andTheLazyFlipPersists() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        backdateTokenExpiry(token);
        int storedFilesBefore = storedFileCountForOrder(orderId);
        Set<String> diskBefore = diskFilesForOrder(orderId);

        assertError(postAccept(token, signaturePart(SIGNATURE_PNG)),
                410, "QUOTE_LINK_EXPIRED", LINK_EXPIRED_MESSAGE, "expired link");

        Map<String, Object> expiredToken = tokenRow(token);
        Assertions.assertEquals("EXPIRED", expiredToken.get("status"), "the token must flip to EXPIRED");
        Assertions.assertNotNull(expiredToken.get("dead_at"), "dead_at must be stamped by the flip");
        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("EXPIRED", version.get("status"), "the ISSUED version flips with its token");
        Assertions.assertNull(version.get("accepted_at"));
        Assertions.assertNull(version.get("accepted_signature_file_id"));
        Assertions.assertNull(version.get("signed_pdf_file_id"));
        Assertions.assertEquals(storedFilesBefore, storedFileCountForOrder(orderId), "no stored_file rows written");
        Assertions.assertEquals(diskBefore, diskFilesForOrder(orderId), "no files written");
        Assertions.assertNull(orderMoneyColumn(orderId, "price_adjustment_inc_gst"), "no price write");
        Assertions.assertTrue(acceptanceNotificationSender.sentNotifications().isEmpty(), "no notification");

        // A retry (even with an invalid body) still reports EXPIRED and never re-flips the token.
        assertError(postAccept(token, signaturePart(SIGNATURE_PNG), extraPhotoPart()),
                410, "QUOTE_LINK_EXPIRED", LINK_EXPIRED_MESSAGE, "expired link retry");
        Assertions.assertEquals(expiredToken, tokenRow(token), "an EXPIRED token row is never re-stamped");
    }

    @Test
    void accept_secondAcceptAfterSuccess_returns410Inactive_nothingReplaced() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        acceptOk(token, SIGNATURE_PNG);
        Map<String, Object> before = sideEffectState(orderId);

        assertError(postAccept(token, signaturePart(SIGNATURE_PNG)),
                410, "QUOTE_LINK_INACTIVE", LINK_INACTIVE_MESSAGE, "second accept");
        byte[] otherSignature = pngOfSize(40, 12, BufferedImage.TYPE_INT_ARGB);
        assertError(postAccept(token, signaturePart(otherSignature)),
                410, "QUOTE_LINK_INACTIVE", LINK_INACTIVE_MESSAGE, "second accept with another signature");

        assertNoSideEffects(orderId, before, "second accept");
    }

    // ================================================================
    // 11. The public surface after acceptance
    // ================================================================

    @Test
    void afterAcceptance_publicGetIsInactiveMinimal_viewedAndPdfReturn410Inactive() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        acceptOk(token, SIGNATURE_PNG);

        assertInactiveMinimalPublicView(token);

        assertError(mockMvc.perform(post(viewedUrl(token)).contentType(MediaType.APPLICATION_JSON).content("{}")),
                410, "QUOTE_LINK_INACTIVE", LINK_INACTIVE_MESSAGE, "viewed after acceptance");
        assertError(mockMvc.perform(get(publicPdfUrl(token))),
                410, "QUOTE_LINK_INACTIVE", LINK_INACTIVE_MESSAGE, "public pdf after acceptance");
        Assertions.assertNull(versionRowById(versionId).get("viewed_at"), "a rejected viewed POST stamps nothing");
    }

    private void assertInactiveMinimalPublicView(String token) throws Exception {
        clearJpaCache();
        mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"))
                .andExpect(jsonPath("$.data.message").value(LINK_INACTIVE_MESSAGE))
                .andExpect(jsonPath("$.data.business_name").value(businessName()))
                // Minimal payload: an accepted (dead) link renders NO quote content.
                .andExpect(jsonPath("$.data.business_logo_url").value(nullValue()))
                .andExpect(jsonPath("$.data.accent_color").value(nullValue()))
                .andExpect(jsonPath("$.data.business_abn").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_account_name").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_bank_name").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_bsb").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_account_number").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_stripe_link_url").value(nullValue()))
                .andExpect(jsonPath("$.data.order_number").value(nullValue()))
                .andExpect(jsonPath("$.data.flooring_type").value(nullValue()))
                .andExpect(jsonPath("$.data.customer_name").value(nullValue()))
                .andExpect(jsonPath("$.data.customer_address_line1").value(nullValue()))
                .andExpect(jsonPath("$.data.customer_address_line2").value(nullValue()))
                .andExpect(jsonPath("$.data.details_of_sale").value(nullValue()))
                .andExpect(jsonPath("$.data.itemised").value(nullValue()))
                .andExpect(jsonPath("$.data.lines").value(nullValue()))
                .andExpect(jsonPath("$.data.quote_total_ex_gst").value(nullValue()))
                .andExpect(jsonPath("$.data.gst_amount").value(nullValue()))
                .andExpect(jsonPath("$.data.quote_total_inc_gst").value(nullValue()))
                .andExpect(jsonPath("$.data.deposit_amount").value(nullValue()))
                .andExpect(jsonPath("$.data.terms_html").value(nullValue()))
                .andExpect(jsonPath("$.data.expires_at").value(nullValue()));
        clearJpaCache();
    }

    // ================================================================
    // 12. Accepted / consumed rows are immune to lazy expiry
    // ================================================================

    @Test
    void afterAcceptance_tokenPastExpiry_staysConsumedAndAccepted_publicStateInactive_retry410Inactive()
            throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        acceptOk(token, SIGNATURE_PNG);
        Map<String, Object> acceptedVersion = versionRowById(versionId);
        Assertions.assertEquals("ACCEPTED", acceptedVersion.get("status"));

        backdateTokenExpiry(token);
        Map<String, Object> backdatedToken = tokenRow(token);
        Assertions.assertEquals("CONSUMED", backdatedToken.get("status"));

        // The GET reports INACTIVE (never EXPIRED) and flips nothing.
        assertInactiveMinimalPublicView(token);
        Assertions.assertEquals(backdatedToken, tokenRow(token), "a CONSUMED token can never lazy-expire");
        Assertions.assertEquals(acceptedVersion, versionRowById(versionId), "an ACCEPTED version can never expire");

        // A retry is INACTIVE (never EXPIRED) and still flips nothing.
        assertError(postAccept(token, signaturePart(SIGNATURE_PNG)),
                410, "QUOTE_LINK_INACTIVE", LINK_INACTIVE_MESSAGE, "accept after acceptance, past expiry");
        Assertions.assertEquals(backdatedToken, tokenRow(token));
        Assertions.assertEquals(acceptedVersion, versionRowById(versionId));
    }

    // ================================================================
    // 13. LAID orders may still be accepted remotely
    // ================================================================

    @Test
    void accept_laidOrder_isAllowed_andWritesTheSignedPrice() throws Exception {
        long orderId = itemisedReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
        clearJpaCache();

        acceptOk(token, SIGNATURE_PNG);

        Map<String, Object> version = versionRowById(versionId);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        Assertions.assertEquals(SNAPSHOT_NAME, version.get("accepted_customer_name"));
        Assertions.assertNotNull(version.get("signed_pdf_file_id"));
        Assertions.assertEquals("CONSUMED", tokenRow(token).get("status"));
        Assertions.assertEquals("LAID", jdbcTemplate.queryForObject(
                "SELECT order_status::text FROM sales_order WHERE order_id = ?", String.class, orderId),
                "acceptance never changes the order status");
        // No LAID gate on the internal D6b write: no cost lines -> adjustment = 275.00 - 0.00.
        assertMoney("275.00", orderMoneyColumn(orderId, "price_adjustment_inc_gst"));
        assertMoney("250.00", orderMoneyColumn(orderId, "sale_price_ex_gst"));
    }

    // ================================================================
    // 14. Acceptance mutates nothing else on the order
    // ================================================================

    @Test
    void accept_neverMutatesDraftLinesSnapshotStatusEmailMarkerInvoicesOrPayments() throws Exception {
        long orderId = insertOrder("FOLLOW_UP");
        seedCustomer(orderId);
        seedBillingAddress(orderId);
        clearTenantTerms();
        seedProductLine(orderId, "100.00", "40.00");
        seedChargeLine(orderId, "50.00", "20.00");
        Timestamp lastEmailedAt = Timestamp.valueOf(LocalDateTime.of(2026, 1, 15, 10, 30));
        jdbcTemplate.update("UPDATE sales_order SET last_emailed_at = ? WHERE order_id = ?", lastEmailedAt, orderId);
        seedInvoiceAndPayment(orderId);
        clearJpaCache();
        saveItemisedTwoLineDraft(orderId);
        String token = issueAndExtractToken(orderId);
        long versionId = asLong(issuedVersionRow(orderId).get("quote_version_id"));

        Map<String, Object> before = untouchedByAcceptanceState(orderId);
        // Make the comparison meaningful: every compared collection is populated.
        Assertions.assertEquals(1, ((List<?>) before.get("quote_draft")).size());
        Assertions.assertEquals(2, ((List<?>) before.get("quote_draft_line")).size());
        Assertions.assertEquals(2, ((List<?>) before.get("quote_version_line")).size());
        Assertions.assertEquals(1, ((List<?>) before.get("order_product_line")).size());
        Assertions.assertEquals(1, ((List<?>) before.get("order_charge_line")).size());
        Assertions.assertEquals(1, ((List<?>) before.get("invoice")).size());
        Assertions.assertEquals(1, ((List<?>) before.get("payment_transaction")).size());

        acceptOk(token, SIGNATURE_PNG);

        Assertions.assertEquals("ACCEPTED", versionRowById(versionId).get("status"), "the acceptance happened");
        Assertions.assertEquals(before, untouchedByAcceptanceState(orderId),
                "acceptance must not touch the draft, the lines, the issued snapshot, the order status / "
                        + "email marker, invoices or payments");
        Assertions.assertEquals("FOLLOW_UP", jdbcTemplate.queryForObject(
                "SELECT order_status::text FROM sales_order WHERE order_id = ?", String.class, orderId));
        Assertions.assertEquals(lastEmailedAt, jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId));
    }

    /** Every row/column acceptance must never mutate (price columns are deliberately excluded — D6b). */
    private Map<String, Object> untouchedByAcceptanceState(long orderId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("quote_draft", jdbcTemplate.queryForList(
                "SELECT * FROM quote_draft WHERE order_id = ?", orderId));
        state.put("quote_draft_line", jdbcTemplate.queryForList(
                "SELECT l.* FROM quote_draft_line l JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id "
                        + "WHERE d.order_id = ? ORDER BY l.quote_draft_line_id", orderId));
        state.put("quote_version_line", jdbcTemplate.queryForList(
                "SELECT l.* FROM quote_version_line l JOIN quote_version v ON v.quote_version_id = l.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY l.quote_version_line_id", orderId));
        // The frozen issued snapshot columns (status + the acceptance columns legitimately change).
        state.put("issued_snapshot", jdbcTemplate.queryForList(
                "SELECT quote_version_id, order_id, version_number, itemised, quote_total_ex_gst, "
                        + "quote_total_inc_gst, flooring_type_snapshot, terms_snapshot, details_of_sale_snapshot, "
                        + "customer_name_snapshot, customer_address_line1_snapshot, customer_address_line2_snapshot, "
                        + "sent_channel, first_sent_at, last_sent_at, last_emailed_at, viewed_at, "
                        + "issued_pdf_file_id, created_by_user_id, created_at "
                        + "FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId));
        state.put("order_product_line", jdbcTemplate.queryForList(
                "SELECT * FROM order_product_line WHERE order_id = ? ORDER BY order_product_line_id", orderId));
        state.put("order_charge_line", jdbcTemplate.queryForList(
                "SELECT * FROM order_charge_line WHERE order_id = ? ORDER BY order_charge_line_id", orderId));
        state.put("sales_order", jdbcTemplate.queryForMap(
                "SELECT order_status::text AS order_status, last_emailed_at, details_of_sale, flooring_type::text "
                        + "AS flooring_type FROM sales_order WHERE order_id = ?", orderId));
        state.put("invoice", jdbcTemplate.queryForList(
                "SELECT * FROM invoice WHERE order_id = ? ORDER BY invoice_id", orderId));
        state.put("payment_transaction", jdbcTemplate.queryForList(
                "SELECT * FROM payment_transaction WHERE order_id = ? ORDER BY payment_transaction_id", orderId));
        return state;
    }
}
