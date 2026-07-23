package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16E-C — the PUBLIC quote surface: {@code GET /public/quotes/{token}} /
 * {@code POST /public/quotes/{token}/viewed} / {@code GET /public/quotes/{token}/pdf}, plus the
 * real {@code <app-base>/q/{token}} link in the quote email body.
 *
 * <p>Self-seeded (Phase 14D go-forward rule) like {@link QuoteSendControllerTest}: each test
 * INSERTs its own {@code sales_order} (+ customer, billing address) via JdbcTemplate, issues the
 * quote through the PROTECTED send-email endpoint, and extracts the plaintext token from the
 * recorded email body — so every public call exercises the real mint → hash → resolve chain. All
 * tests run in the test transaction and roll back. The recording senders are singletons whose
 * state SURVIVES the rollback, so they are {@code reset()} in {@code @BeforeEach}. NO session is
 * ever attached to a public request — the token is the only credential.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class PublicQuoteControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final String VALID_EMAIL = "public.quote@example.com";
    private static final String VALID_MOBILE = "0412345678";

    // The REAL public link (Phase 16E-C): absolute app origin + top-level slugless /q/{token}.
    // app.public-base-url defaults to the Vite dev server in application.properties.
    private static final Pattern ABSOLUTE_LINK_PATTERN =
            Pattern.compile("http://localhost:5173/q/([A-Za-z0-9_-]{43})");

    // A well-formed (43-char URL-safe) token that was never minted.
    private static final String UNKNOWN_TOKEN = "A".repeat(43);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingSmsSender smsSender;

    @PersistenceContext
    private EntityManager entityManager;

    private MockMvc mockMvc;

    private int seq = 90_000;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        quoteEmailSender.reset();
        smsSender.reset();
    }

    // ================================================================
    // Helpers
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

    private static String publicViewedUrl(String token) {
        return publicUrl(token) + "/viewed";
    }

    private static String publicPdfUrl(String token) {
        return publicUrl(token) + "/pdf";
    }

    private static String sendEmailUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/send-email";
    }

    private static String cancelUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/cancel";
    }

    private static String draftUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/draft";
    }

    private static String workspaceUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/workspace";
    }

    /** Detach hydrated entities — tests share one persistence context (the sibling-test trap). */
    private void clearJpaCache() {
        entityManager.clear();
    }

    private long insertOrder(String status) {
        int s = ++seq;
        String orderNumber = "QPUBT.ZZ9." + String.format("%05d", s % 100_000);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, ?::order_status, 1, 2026) "
                        + "RETURNING order_id",
                Long.class,
                BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, s, orderNumber, status);
    }

    private void seedCustomer(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quote', 'Tester', ?, ?)",
                orderId, VALID_EMAIL, VALID_MOBILE);
    }

    private void seedBillingAddress(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Test Street', 'Sydney', 'NSW', '2000')",
                orderId);
    }

    /** Save a two-ITEM itemised draft (Carpet 2×100 + Underlay 1×50 = 250 ex / 275 inc). */
    private void saveItemisedTwoLineDraft(long orderId) throws Exception {
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    /** Save a non-itemised draft (final inc total; header-only, retains dormant rows). */
    private void saveNonItemisedDraft(long orderId, String finalIncTotal) throws Exception {
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemised\": false, \"final_total_inc_gst\": " + finalIncTotal
                                + ", \"lines\": []}"))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    /** A ready-to-send order: LEAD, saved itemised draft, customer + billing address. */
    private long sendReadyOrder() throws Exception {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        seedBillingAddress(orderId);
        saveItemisedTwoLineDraft(orderId);
        return orderId;
    }

    private void sendEmailOk(long orderId) throws Exception {
        clearJpaCache();
        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        clearJpaCache();
    }

    /** Issue by email and return the plaintext token from the LATEST recorded email body. */
    private String issueAndExtractToken(long orderId) throws Exception {
        sendEmailOk(orderId);
        var sent = quoteEmailSender.sentEmails();
        Assertions.assertFalse(sent.isEmpty(), "a quote email must have been recorded");
        return extractToken(sent.get(sent.size() - 1).bodyText());
    }

    /** Extract the plaintext token from the ABSOLUTE public link; must appear exactly once. */
    private static String extractToken(String messageBody) {
        Matcher matcher = ABSOLUTE_LINK_PATTERN.matcher(messageBody);
        Assertions.assertTrue(matcher.find(),
                "message body must contain the absolute /q/{token} link: " + messageBody);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "public link must appear exactly once in the body");
        return token;
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- DB probes ----

    private Map<String, Object> tokenRowByToken(String plainToken) throws Exception {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_token WHERE token_hash = ?", sha256Hex(plainToken));
    }

    private Map<String, Object> issuedVersionRow(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND status = 'ISSUED'", orderId);
    }

    private Map<String, Object> versionRowByNumber(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?",
                orderId, versionNumber);
    }

    private void backdateTokenExpiry(String plainToken) throws Exception {
        int updated = jdbcTemplate.update(
                "UPDATE quote_token SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
                sha256Hex(plainToken));
        Assertions.assertEquals(1, updated, "expected to backdate exactly one token");
        clearJpaCache();
    }

    private void forceTokenStatus(String plainToken, String tokenStatus) throws Exception {
        int updated = jdbcTemplate.update(
                "UPDATE quote_token SET status = ?, dead_at = now() WHERE token_hash = ?",
                tokenStatus, sha256Hex(plainToken));
        Assertions.assertEquals(1, updated, "expected to move exactly one token to " + tokenStatus);
        clearJpaCache();
    }

    private String businessName() {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM business WHERE business_id = ?", String.class, BUSINESS_AUSSIE);
    }

    private static void assertMoneyJson(String expected, Object actual) {
        Assertions.assertNotNull(actual, "expected money value " + expected + " but was null");
        Assertions.assertEquals(0,
                new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
                "expected " + expected + " but was " + actual);
    }

    // ================================================================
    // GET /public/quotes/{token} — ACTIVE payload (snapshot-sourced)
    // ================================================================

    @Test
    void getPublicQuote_activeItemised_returnsFullSnapshotPayload() throws Exception {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        seedBillingAddress(orderId);
        // Deterministic frozen content: per-type terms + details-of-sale set BEFORE the issue.
        // ABN + direct-deposit/Stripe payment config are LIVE presentation context (like
        // name/logo) — set deterministically too.
        jdbcTemplate.update("UPDATE business SET terms_soft = '<p>Public frozen terms</p>', "
                + "abn = '12 345 678 901', account_name = 'Aussie Floors Trading', "
                + "bank_name = 'Test Bank', bsb = '062-000', account_number = '12345678', "
                + "stripe_payment_link_url = 'https://buy.stripe.com/test_public_quote' "
                + "WHERE business_id = ?", BUSINESS_AUSSIE);
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Supply and lay carpet' "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();
        saveItemisedTwoLineDraft(orderId);
        String token = issueAndExtractToken(orderId);
        String orderNumber = jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);

        MvcResult result = mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.business_name").value(businessName()))
                .andExpect(jsonPath("$.data.business_abn").value("12 345 678 901"))
                .andExpect(jsonPath("$.data.payment_account_name").value("Aussie Floors Trading"))
                .andExpect(jsonPath("$.data.payment_bank_name").value("Test Bank"))
                .andExpect(jsonPath("$.data.payment_bsb").value("062-000"))
                .andExpect(jsonPath("$.data.payment_account_number").value("12345678"))
                .andExpect(jsonPath("$.data.payment_stripe_link_url")
                        .value("https://buy.stripe.com/test_public_quote"))
                .andExpect(jsonPath("$.data.order_number").value(orderNumber))
                .andExpect(jsonPath("$.data.flooring_type").value("SOFT"))
                .andExpect(jsonPath("$.data.customer_name").value("Quote Tester"))
                .andExpect(jsonPath("$.data.customer_address_line1").value("12 Test Street"))
                .andExpect(jsonPath("$.data.customer_address_line2").value("Sydney NSW 2000"))
                .andExpect(jsonPath("$.data.details_of_sale").value("Supply and lay carpet"))
                .andExpect(jsonPath("$.data.itemised").value(true))
                .andExpect(jsonPath("$.data.lines", hasSize(2)))
                .andExpect(jsonPath("$.data.lines[0].description").value("Carpet"))
                .andExpect(jsonPath("$.data.lines[1].description").value("Underlay"))
                .andExpect(jsonPath("$.data.terms_html").value("<p>Public frozen terms</p>"))
                .andExpect(jsonPath("$.data.expires_at").value(notNullValue()))
                .andExpect(jsonPath("$.data.message").value(nullValue()))
                .andReturn();

        // Totals + PDF-parity derived money: 250 ex / 25 GST / 275 inc / 110 deposit (40%).
        String body = result.getResponse().getContentAsString();
        com.jayway.jsonpath.DocumentContext json = com.jayway.jsonpath.JsonPath.parse(body);
        assertMoneyJson("250.00", json.read("$.data.quote_total_ex_gst"));
        assertMoneyJson("25.00", json.read("$.data.gst_amount"));
        assertMoneyJson("275.00", json.read("$.data.quote_total_inc_gst"));
        assertMoneyJson("110.00", json.read("$.data.deposit_amount"));
        assertMoneyJson("200.00", json.read("$.data.lines[0].line_total_ex_gst"));
        assertMoneyJson("100.00", json.read("$.data.lines[0].unit_price_ex_gst"));
    }

    @Test
    void getPublicQuote_rendersFrozenSnapshot_notLiveState() throws Exception {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        seedBillingAddress(orderId);
        jdbcTemplate.update("UPDATE business SET terms_soft = '<p>Frozen at issue</p>' "
                + "WHERE business_id = ?", BUSINESS_AUSSIE);
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Frozen details' "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();
        saveItemisedTwoLineDraft(orderId);
        String token = issueAndExtractToken(orderId);

        // The issue must have FROZEN the "Quotation To" identity onto the version row (V17).
        Map<String, Object> issuedRow = issuedVersionRow(orderId);
        Assertions.assertEquals("Quote Tester", issuedRow.get("customer_name_snapshot"));
        Assertions.assertEquals("12 Test Street", issuedRow.get("customer_address_line1_snapshot"));
        Assertions.assertEquals("Sydney NSW 2000", issuedRow.get("customer_address_line2_snapshot"));

        // Mutate EVERY live source after the issue: tenant terms, order details-of-sale, the
        // CUSTOMER + BILLING ADDRESS (the Codex P1 leak — a pre-LAID customer edit must never
        // reach the old token holder), and the draft itself (different line set + totals). None
        // of it may leak into the public view.
        jdbcTemplate.update("UPDATE business SET terms_soft = '<p>Edited later</p>' "
                + "WHERE business_id = ?", BUSINESS_AUSSIE);
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Edited later' "
                + "WHERE order_id = ?", orderId);
        jdbcTemplate.update("UPDATE order_customer SET first_name = 'Changed', "
                + "middle_name = NULL, last_name = 'Person' WHERE order_id = ?", orderId);
        jdbcTemplate.update("UPDATE order_address SET unit_number = NULL, street_number = '99', "
                + "street = 'Changed Street', suburb = 'Melbourne', state_code = 'VIC', "
                + "postcode = '3000' WHERE order_id = ?", orderId);
        clearJpaCache();
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Changed line","quantity":1,"unit_price_ex_gst":999,"line_total_ex_gst":999,"sort_order":0}
                                ]}"""))
                .andExpect(status().isOk());
        clearJpaCache();

        MvcResult result = mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.details_of_sale").value("Frozen details"))
                .andExpect(jsonPath("$.data.terms_html").value("<p>Frozen at issue</p>"))
                // "Quotation To" comes from the V17 snapshot columns — the ORIGINAL identity,
                // never the post-issue "Changed Person"/Melbourne edits.
                .andExpect(jsonPath("$.data.customer_name").value("Quote Tester"))
                .andExpect(jsonPath("$.data.customer_address_line1").value("12 Test Street"))
                .andExpect(jsonPath("$.data.customer_address_line2").value("Sydney NSW 2000"))
                .andExpect(jsonPath("$.data.lines", hasSize(2)))
                .andExpect(jsonPath("$.data.lines[0].description").value("Carpet"))
                .andReturn();
        com.jayway.jsonpath.DocumentContext json =
                com.jayway.jsonpath.JsonPath.parse(result.getResponse().getContentAsString());
        assertMoneyJson("275.00", json.read("$.data.quote_total_inc_gst"));

        // Freeze-at-issue, not freeze-forever: the draft changed above, so a re-send issues a
        // NEW version whose snapshot must carry the EDITED identity ("every new version gets
        // them"); the old link is now dead (SUPERSEDED).
        String newToken = issueAndExtractToken(orderId);
        mockMvc.perform(get(publicUrl(newToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.customer_name").value("Changed Person"))
                .andExpect(jsonPath("$.data.customer_address_line1").value("99 Changed Street"))
                .andExpect(jsonPath("$.data.customer_address_line2").value("Melbourne VIC 3000"));
        mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUPERSEDED"));
    }

    @Test
    void getPublicQuote_nonItemised_returnsSingleAmountAndNoLines() throws Exception {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        seedBillingAddress(orderId);
        // Itemised save FIRST so retained dormant rows exist, then flip non-itemised (§6.1):
        // the issue must snapshot ZERO lines and the public payload must carry none of them.
        saveItemisedTwoLineDraft(orderId);
        saveNonItemisedDraft(orderId, "330.00");
        String token = issueAndExtractToken(orderId);

        MvcResult result = mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.itemised").value(false))
                .andExpect(jsonPath("$.data.lines", hasSize(0)))
                .andReturn();
        com.jayway.jsonpath.DocumentContext json =
                com.jayway.jsonpath.JsonPath.parse(result.getResponse().getContentAsString());
        assertMoneyJson("330.00", json.read("$.data.quote_total_inc_gst"));
        assertMoneyJson("300.00", json.read("$.data.quote_total_ex_gst"));
        assertMoneyJson("30.00", json.read("$.data.gst_amount"));
        assertMoneyJson("132.00", json.read("$.data.deposit_amount"));
    }

    // ================================================================
    // Leak assertions — cost/GP/token/storage/id free
    // ================================================================

    @Test
    void getPublicQuote_leaksNoCostGpTokenHashStorageOrInternalIds() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);

        MvcResult result = mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quote_version_id").doesNotExist())
                .andExpect(jsonPath("$.data.order_id").doesNotExist())
                .andExpect(jsonPath("$.data.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.version_number").doesNotExist())
                .andExpect(jsonPath("$.data.gp_percent").doesNotExist())
                .andExpect(jsonPath("$.data.below_cost").doesNotExist())
                // business_abn + the payment fields (16E-C polish) are serialized on the ACTIVE
                // payload, so they are swept by the exhaustive key scan below like every field.
                .andExpect(jsonPath("$.data.business_abn").hasJsonPath())
                .andExpect(jsonPath("$.data.payment_account_name").hasJsonPath())
                .andExpect(jsonPath("$.data.payment_bank_name").hasJsonPath())
                .andExpect(jsonPath("$.data.payment_bsb").hasJsonPath())
                .andExpect(jsonPath("$.data.payment_account_number").hasJsonPath())
                .andExpect(jsonPath("$.data.payment_stripe_link_url").hasJsonPath())
                .andReturn();
        String body = result.getResponse().getContentAsString();

        // KEY-level scan (values like frozen terms prose may legitimately contain words such as
        // "costs" — the leak rule is about FIELDS and secrets, not customer-facing sentences):
        // no cost/GP field, no token material, no storage field, no file/row id may be keyed in.
        java.util.Set<String> keys = new java.util.TreeSet<>();
        collectJsonKeys(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body), keys);
        for (String key : keys) {
            String k = key.toLowerCase();
            Assertions.assertFalse(k.contains("cost"), "cost-free payload keyed a cost field: " + key);
            Assertions.assertFalse(k.contains("gp"), "GP-free payload keyed a GP field: " + key);
            Assertions.assertFalse(k.contains("token"), "no token field may be keyed: " + key);
            Assertions.assertFalse(k.contains("hash"), "no hash field may be keyed: " + key);
            Assertions.assertFalse(k.contains("storage"), "no storage field may be keyed: " + key);
            Assertions.assertFalse(k.contains("path"), "no path field may be keyed: " + key);
            Assertions.assertFalse(k.contains("file"), "no file field may be keyed: " + key);
            Assertions.assertFalse(k.endsWith("_id"), "no internal id may be keyed: " + key);
        }

        // VALUE-level scan for the actual secrets: the plaintext token, its stored hash, and the
        // issued PDF's real stored_file.storage_path must never appear anywhere in the body.
        // (business_logo_url deliberately carries the PUBLIC branding logo_path — the exact value
        // the public business lookup has always exposed — and is not a stored_file artifact.)
        Assertions.assertFalse(body.contains(token), "the plaintext token must never be echoed");
        Assertions.assertFalse(body.contains(sha256Hex(token)), "the token hash must never leak");
        String pdfStoragePath = jdbcTemplate.queryForObject(
                "SELECT sf.storage_path FROM quote_version v "
                        + "JOIN stored_file sf ON sf.stored_file_id = v.issued_pdf_file_id "
                        + "WHERE v.order_id = ?", String.class, orderId);
        Assertions.assertFalse(body.contains(pdfStoragePath),
                "the stored PDF's storage_path must never leak");
    }

    private static void collectJsonKeys(com.fasterxml.jackson.databind.JsonNode node,
                                        java.util.Set<String> keys) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                keys.add(entry.getKey());
                collectJsonKeys(entry.getValue(), keys);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectJsonKeys(child, keys));
        }
    }

    @Test
    void getPublicQuote_deadToken_returnsMinimalPayload() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);
        // Real ABN + payment config exist on the business — the dead payload must null them all.
        jdbcTemplate.update("UPDATE business SET abn = '12 345 678 901', "
                + "account_name = 'Aussie Floors Trading', bank_name = 'Test Bank', "
                + "bsb = '062-000', account_number = '12345678', "
                + "stripe_payment_link_url = 'https://buy.stripe.com/test_public_quote' "
                + "WHERE business_id = ?", BUSINESS_AUSSIE);
        mockMvc.perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        clearJpaCache();

        mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CANCELLED"))
                .andExpect(jsonPath("$.data.business_name").value(businessName()))
                .andExpect(jsonPath("$.data.message").value(notNullValue()))
                // Minimal payload: a dead link renders NO quote content.
                .andExpect(jsonPath("$.data.business_abn").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_account_name").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_bank_name").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_bsb").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_account_number").value(nullValue()))
                .andExpect(jsonPath("$.data.payment_stripe_link_url").value(nullValue()))
                .andExpect(jsonPath("$.data.order_number").value(nullValue()))
                .andExpect(jsonPath("$.data.customer_name").value(nullValue()))
                .andExpect(jsonPath("$.data.details_of_sale").value(nullValue()))
                .andExpect(jsonPath("$.data.itemised").value(nullValue()))
                .andExpect(jsonPath("$.data.lines").value(nullValue()))
                .andExpect(jsonPath("$.data.quote_total_inc_gst").value(nullValue()))
                .andExpect(jsonPath("$.data.deposit_amount").value(nullValue()))
                .andExpect(jsonPath("$.data.terms_html").value(nullValue()));
    }

    // ================================================================
    // viewed — write-once stamp, idempotent; GET never stamps
    // ================================================================

    @Test
    void getPublicQuote_doesNotStampViewedAt() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);

        mockMvc.perform(get(publicUrl(token))).andExpect(status().isOk());

        Assertions.assertNull(issuedVersionRow(orderId).get("viewed_at"),
                "the GET must not stamp viewed_at — first-view marking is the viewed POST's job");
    }

    @Test
    void markViewed_stampsOnce_secondCallNeverOverwrites() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);

        mockMvc.perform(post(publicViewedUrl(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));

        Timestamp first = (Timestamp) issuedVersionRow(orderId).get("viewed_at");
        Assertions.assertNotNull(first, "first viewed POST must stamp viewed_at");

        // Second view: 200, but the write-once guard leaves the original stamp untouched.
        mockMvc.perform(post(publicViewedUrl(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        Timestamp second = (Timestamp) issuedVersionRow(orderId).get("viewed_at");
        Assertions.assertEquals(first, second, "viewed_at must never be overwritten");
    }

    @Test
    void markViewed_flowsIntoProtectedWorkspaceOpenedState() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);

        mockMvc.perform(post(publicViewedUrl(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        clearJpaCache();

        // The protected Customer Quote tab reads current_issued.viewed_at — "Opened" after refresh.
        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued.viewed_at").value(notNullValue()));
    }

    @Test
    void markViewed_nonEmptyBody_rejected_afterStateGates() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);

        // ACTIVE + non-empty body -> 400 VALIDATION_FAILED, and nothing is stamped.
        mockMvc.perform(post(publicViewedUrl(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        Assertions.assertNull(issuedVersionRow(orderId).get("viewed_at"),
                "a rejected viewed POST must not stamp viewed_at");

        // Malformed JSON -> 400 MALFORMED_JSON.
        mockMvc.perform(post(publicViewedUrl(token))
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));

        // Dead token + bad body -> the 410 state gate fires FIRST (token gates before body).
        mockMvc.perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        clearJpaCache();
        mockMvc.perform(post(publicViewedUrl(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_CANCELLED"));
    }

    // ================================================================
    // Lazy expiry — ACTIVE-only, token + version flip, dead_at stamped
    // ================================================================

    @Test
    void lazyExpiry_activeTokenPastExpiry_flipsTokenAndVersion() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);
        backdateTokenExpiry(token);

        // The GET returns 200 with state EXPIRED (contract: only the actions 410).
        mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("EXPIRED"))
                .andExpect(jsonPath("$.data.message").value(notNullValue()))
                .andExpect(jsonPath("$.data.quote_total_inc_gst").value(nullValue()));

        Map<String, Object> tokenRow = tokenRowByToken(token);
        Assertions.assertEquals("EXPIRED", tokenRow.get("status"), "token must flip to EXPIRED");
        Assertions.assertNotNull(tokenRow.get("dead_at"), "dead_at must be stamped on lazy expiry");
        Assertions.assertEquals("EXPIRED", versionRowByNumber(orderId, 1).get("status"),
                "the ISSUED version must flip to EXPIRED with its token");
    }

    @Test
    void lazyExpiry_rejectsActionsWithExpiredLinkError() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);
        backdateTokenExpiry(token);

        mockMvc.perform(post(publicViewedUrl(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_EXPIRED"));
        Assertions.assertEquals("EXPIRED", tokenRowByToken(token).get("status"),
                "the expiry flip must persist even though the request was rejected");

        mockMvc.perform(get(publicPdfUrl(token)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_EXPIRED"));
    }

    @Test
    void lazyExpiry_neverMutatesAlreadyDeadTokens() throws Exception {
        long orderId = sendReadyOrder();
        String oldToken = issueAndExtractToken(orderId);
        // Unchanged resend: the old ACTIVE token -> REPLACED, a new ACTIVE token is minted.
        sendEmailOk(orderId);
        Assertions.assertEquals("REPLACED", tokenRowByToken(oldToken).get("status"));
        Timestamp deadAt = (Timestamp) tokenRowByToken(oldToken).get("dead_at");

        // Backdate the DEAD token far past expiry — resolving it must NOT re-flip it to EXPIRED.
        backdateTokenExpiry(oldToken);
        mockMvc.perform(get(publicUrl(oldToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUPERSEDED"));

        Map<String, Object> after = tokenRowByToken(oldToken);
        Assertions.assertEquals("REPLACED", after.get("status"),
                "a non-ACTIVE token must never lazy-expire");
        Assertions.assertEquals(deadAt, after.get("dead_at"), "dead_at must not be restamped");
        Assertions.assertEquals("ISSUED", versionRowByNumber(orderId, 1).get("status"),
                "the version must stay ISSUED — its live link is the NEW token");
    }

    // ================================================================
    // Dead token states — every rejected category
    // ================================================================

    @Test
    void deadStates_replacedSupersededCancelledConsumed_unknown_malformed() throws Exception {
        // REPLACED (unchanged resend) — public state SUPERSEDED, pdf/viewed 410 SUPERSEDED.
        long orderA = sendReadyOrder();
        String replacedToken = issueAndExtractToken(orderA);
        sendEmailOk(orderA);
        mockMvc.perform(get(publicUrl(replacedToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUPERSEDED"));
        mockMvc.perform(get(publicPdfUrl(replacedToken)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_SUPERSEDED"));

        // SUPERSEDED (changed draft -> new version) — same public presentation.
        long orderB = sendReadyOrder();
        String supersededToken = issueAndExtractToken(orderB);
        saveNonItemisedDraft(orderB, "440.00");
        sendEmailOk(orderB);
        mockMvc.perform(get(publicUrl(supersededToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUPERSEDED"));
        mockMvc.perform(post(publicViewedUrl(supersededToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_SUPERSEDED"));

        // CANCELLED — cancel kills the link.
        long orderC = sendReadyOrder();
        String cancelledToken = issueAndExtractToken(orderC);
        mockMvc.perform(post(cancelUrl(orderC)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        clearJpaCache();
        mockMvc.perform(get(publicUrl(cancelledToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CANCELLED"));
        mockMvc.perform(get(publicPdfUrl(cancelledToken)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_CANCELLED"));

        // CONSUMED (signed) — no 16E-C API can consume; forced via SQL as 16F data. INACTIVE.
        long orderD = sendReadyOrder();
        String consumedToken = issueAndExtractToken(orderD);
        forceTokenStatus(consumedToken, "CONSUMED");
        mockMvc.perform(get(publicUrl(consumedToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"));
        mockMvc.perform(get(publicPdfUrl(consumedToken)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_INACTIVE"));

        // Unknown (well-formed, never minted) — 404, no leak.
        mockMvc.perform(get(publicUrl(UNKNOWN_TOKEN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_TOKEN_NOT_FOUND"));
        mockMvc.perform(get(publicPdfUrl(UNKNOWN_TOKEN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_TOKEN_NOT_FOUND"));

        // Malformed shapes — identical 404 (indistinguishable from unknown).
        mockMvc.perform(get(publicUrl("short")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_TOKEN_NOT_FOUND"));
        mockMvc.perform(get(publicUrl("A".repeat(42) + "!")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_TOKEN_NOT_FOUND"));
        mockMvc.perform(post(publicViewedUrl("short"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_TOKEN_NOT_FOUND"));
    }

    // ================================================================
    // Public PDF — serves the STORED artifact verbatim
    // ================================================================

    @Test
    void publicPdf_activeToken_streamsStoredIssuedPdfVerbatim() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);
        QuoteEmailRequest email = quoteEmailSender.sentEmails().get(0);

        MvcResult result = mockMvc.perform(get(publicPdfUrl(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        // Byte-identical to the attachment persisted+delivered at issue: the endpoint serves the
        // STORED bytes verbatim (a regenerated PDF could never be byte-identical — metadata
        // timestamps alone would differ).
        Assertions.assertArrayEquals(email.pdfBytes(),
                result.getResponse().getContentAsByteArray(),
                "public PDF must be the stored issued artifact, never a regeneration");
        String disposition = result.getResponse().getHeader("Content-Disposition");
        Assertions.assertNotNull(disposition);
        Assertions.assertTrue(disposition.contains("inline"), disposition);
        Assertions.assertTrue(disposition.contains("quote-"), disposition);
    }

    @Test
    void publicPdf_missingStoredArtifact_returnsQuotePdfNotFound() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = ((Number) issuedVersionRow(orderId).get("quote_version_id")).longValue();
        jdbcTemplate.update(
                "UPDATE quote_version SET issued_pdf_file_id = NULL WHERE quote_version_id = ?",
                versionId);
        clearJpaCache();

        mockMvc.perform(get(publicPdfUrl(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_PDF_NOT_FOUND"));
    }

    // ================================================================
    // Itemised line order + ADJUSTMENT shape (16E-C review additions)
    // ================================================================

    @Test
    void itemisedLines_followSnapshotSortOrder_notInsertionOrder() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);
        long versionId = ((Number) issuedVersionRow(orderId).get("quote_version_id")).longValue();

        // Force sort_order to DIVERGE from PK/insertion order (review finding: with the seed's
        // natural ordering the order assertion could never fail even without an ORDER BY). The
        // snapshot rows are immutable in production — this raw swap exists only to prove the
        // read path really orders by sort_order.
        jdbcTemplate.update(
                "UPDATE quote_version_line SET sort_order = 1 - sort_order WHERE quote_version_id = ?",
                versionId);
        clearJpaCache();

        mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines", hasSize(2)))
                .andExpect(jsonPath("$.data.lines[0].description").value("Underlay"))
                .andExpect(jsonPath("$.data.lines[1].description").value("Carpet"));
    }

    @Test
    void getPublicQuote_adjustmentLine_nullQtyUnit_signedNegativeAmount() throws Exception {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        seedBillingAddress(orderId);
        // Two ITEM lines + a signed NEGATIVE ADJUSTMENT (250 - 50 = 200 ex → 220 inc).
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1},
                                  {"line_type":"ADJUSTMENT","description":"Seasonal discount","line_total_ex_gst":-50,"sort_order":2}
                                ]}"""))
                .andExpect(status().isOk());
        clearJpaCache();
        String token = issueAndExtractToken(orderId);

        MvcResult result = mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines", hasSize(3)))
                .andExpect(jsonPath("$.data.lines[2].description").value("Seasonal discount"))
                .andExpect(jsonPath("$.data.lines[2].quantity").value(nullValue()))
                .andExpect(jsonPath("$.data.lines[2].unit_price_ex_gst").value(nullValue()))
                .andReturn();
        com.jayway.jsonpath.DocumentContext json =
                com.jayway.jsonpath.JsonPath.parse(result.getResponse().getContentAsString());
        assertMoneyJson("-50.00", json.read("$.data.lines[2].line_total_ex_gst"));
        assertMoneyJson("200.00", json.read("$.data.quote_total_ex_gst"));
        assertMoneyJson("20.00", json.read("$.data.gst_amount"));
        assertMoneyJson("220.00", json.read("$.data.quote_total_inc_gst"));
        assertMoneyJson("88.00", json.read("$.data.deposit_amount"));
    }

    // ================================================================
    // viewed — body-variant coverage (16E-C review additions)
    // ================================================================

    @Test
    void markViewed_absentBody_accepted_andNonObjectBodyRejected() throws Exception {
        long orderId = sendReadyOrder();
        String token = issueAndExtractToken(orderId);

        // Non-object JSON (an array) → 400 VALIDATION_FAILED, nothing stamped.
        mockMvc.perform(post(publicViewedUrl(token))
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        Assertions.assertNull(issuedVersionRow(orderId).get("viewed_at"),
                "a rejected non-object body must not stamp viewed_at");

        // Absent body entirely (a bare POST, no content) → accepted; stamps the first view.
        mockMvc.perform(post(publicViewedUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
        Assertions.assertNotNull(issuedVersionRow(orderId).get("viewed_at"),
                "an absent body is a valid empty body and must stamp viewed_at");
    }

    // ================================================================
    // Lazy expiry commit guarantee — NON-transactional (16E-C review addition)
    // ================================================================

    /**
     * The commit-before-410 guarantee cannot be proven inside the class-level test transaction
     * (the service's TransactionTemplate would just JOIN it, so the flip never independently
     * commits — the transactional lazy-expiry tests verify behaviour, not durability). This test
     * opts out of the test transaction, seeds the token layer directly via auto-committing JDBC
     * (no protected send flow → no stored_file row and no PDF on disk to clean), and proves the
     * expiry flip is DURABLE even though the request itself was rejected with 410. Self-cleaning.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lazyExpiry_flipIsCommitted_evenThoughRequestRejected_outsideTestTransaction() throws Exception {
        // 43-char URL-safe token, unique per run so a crashed earlier run cannot collide.
        String plainToken = String.format("NTX%040d", System.nanoTime());
        Long orderId = null;
        Long versionId = null;
        try {
            orderId = insertOrder("LEAD");
            versionId = jdbcTemplate.queryForObject(
                    "INSERT INTO quote_version (order_id, version_number, status, itemised, "
                            + " quote_total_ex_gst, quote_total_inc_gst, flooring_type_snapshot, "
                            + " created_by_user_id) "
                            + "VALUES (?, 1, 'ISSUED', true, 100.00, 110.00, 'SOFT', ?) "
                            + "RETURNING quote_version_id",
                    Long.class, orderId, USER_LIAM);
            jdbcTemplate.update(
                    "INSERT INTO quote_token (quote_version_id, token_hash, status, expires_at) "
                            + "VALUES (?, ?, 'ACTIVE', now() - interval '1 day')",
                    versionId, sha256Hex(plainToken));

            mockMvc.perform(post(publicViewedUrl(plainToken))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_EXPIRED"));

            // No test transaction wraps this method, so these reads see only COMMITTED state:
            // the flip survived the 410 (it committed in its own resolution transaction).
            Map<String, Object> tokenRow = tokenRowByToken(plainToken);
            Assertions.assertEquals("EXPIRED", tokenRow.get("status"),
                    "the token flip must be durable despite the rejected request");
            Assertions.assertNotNull(tokenRow.get("dead_at"), "dead_at must be committed");
            Assertions.assertEquals("EXPIRED", jdbcTemplate.queryForObject(
                            "SELECT status FROM quote_version WHERE quote_version_id = ?",
                            String.class, versionId),
                    "the version flip must be durable despite the rejected request");
        } finally {
            // Auto-commit seeding is real data — remove it even when an assertion failed.
            if (versionId != null) {
                jdbcTemplate.update("DELETE FROM quote_token WHERE quote_version_id = ?", versionId);
                jdbcTemplate.update("DELETE FROM quote_version WHERE quote_version_id = ?", versionId);
            }
            if (orderId != null) {
                jdbcTemplate.update("DELETE FROM sales_order WHERE order_id = ?", orderId);
            }
        }
    }

    // ================================================================
    // Email body — the real absolute /q/{token} link
    // ================================================================

    @Test
    void quoteEmail_carriesAbsoluteSluglessPublicLink_exactlyOnce_thatResolves() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);

        QuoteEmailRequest email = quoteEmailSender.sentEmails().get(0);
        String body = email.bodyText();

        // Absolute app-base link, top-level and SLUGLESS — never /{slug}/q/{token}.
        String token = extractToken(body); // asserts exactly one absolute link
        Assertions.assertFalse(body.contains("/" + SLUG_AUSSIE + "/q/"),
                "the public link must be slugless: " + body);
        // The plaintext token appears in the body exactly once (inside that link).
        Assertions.assertEquals(body.indexOf(token), body.lastIndexOf(token),
                "the plaintext token must appear exactly once in the email body");

        // End-to-end: the delivered link's token resolves on the public surface.
        mockMvc.perform(get(publicUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
    }
}
