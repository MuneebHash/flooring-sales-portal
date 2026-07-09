package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import com.flooring.salesportal.common.sms.SmsRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16E-A — {@code POST /quote/send-email} / {@code POST /quote/send-sms} /
 * {@code POST /quote/cancel} / {@code GET /quote/pdf} + the workspace {@code current_issued}
 * summary.
 *
 * <p>Self-seeded (Phase 14D go-forward rule) like {@link QuoteControllerTest}: each test INSERTs
 * its own {@code sales_order} (+ {@code order_customer} recipient and, where a cost basis is
 * needed, a charge line) via JdbcTemplate. Session identity is Liam / business 1 / store 1. All
 * tests run in the test transaction and roll back. The recording senders are singletons whose
 * state SURVIVES the rollback, so they are {@code reset()} in {@code @BeforeEach}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class QuoteSendControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final String VALID_EMAIL = "quote.customer@example.com";
    private static final String VALID_MOBILE_LOCAL = "0412345678";
    private static final String VALID_MOBILE_INTL = "+61412345678";

    // The public link inside a message body: /{slug}/q/{token} (URL-safe Base64 token).
    private static final Pattern LINK_TOKEN_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]+)");

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

    private int seq = 80_000;

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

    private static String sendEmailUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/send-email";
    }

    private static String sendSmsUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/send-sms";
    }

    private static String cancelUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/cancel";
    }

    private static String pdfUrl(Object orderId, String type) {
        String base = "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/pdf";
        return type == null ? base : base + "?type=" + type;
    }

    private static String workspaceUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/workspace";
    }

    private static String draftUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/draft";
    }

    /**
     * Detach every hydrated entity. Tests share ONE transaction/persistence context across MockMvc
     * calls, so after a raw JDBC UPDATE (or a native draft upsert) a later JPA read would return
     * the STALE cached entity — the exact trap {@link QuoteControllerTest} clears the same way.
     * Production requests each get a fresh persistence context, so this is test-only hygiene.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    private long insertOrder(long businessId, int storeId, long userId, String status) {
        int s = ++seq;
        String orderNumber = "QSENDT.ZZ9." + String.format("%05d", s % 100_000);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, ?::order_status, 1, 2026) "
                        + "RETURNING order_id",
                Long.class,
                businessId, storeId, userId, s, orderNumber, status);
    }

    private long leadOrderInSession() {
        return insertOrder(BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, "LEAD");
    }

    private void seedCustomer(long orderId, String email, String mobile) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quote', 'Tester', ?, ?)",
                orderId, email, mobile);
    }

    /** Give an order a cost basis by seeding one store_charge + order_charge_line. */
    private void seedChargeLine(long orderId, int storeId, String lineTotal, String lineCost) {
        int s = ++seq;
        String code = "QS" + (s % 100_000);
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Quote send test charge', ?, ?) RETURNING charge_id",
                Long.class, storeId, code, new BigDecimal(lineTotal), new BigDecimal(lineCost));
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Quote send test charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code,
                new BigDecimal(lineTotal), new BigDecimal(lineCost),
                new BigDecimal(lineTotal), new BigDecimal(lineTotal), new BigDecimal(lineCost));
    }

    /** Save a two-ITEM itemised draft (Carpet 2×100 + Underlay 1×50 = 250 ex / 275 inc) via the API. */
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

    /** Save a non-itemised draft (final 330.00 inc → 300.00 ex) via the API (retains dormant rows). */
    private void saveNonItemisedDraft(long orderId, String finalIncTotal) throws Exception {
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemised\": false, \"final_total_inc_gst\": " + finalIncTotal
                                + ", \"lines\": []}"))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    /** A ready-to-send order: LEAD, saved itemised draft, valid customer email + mobile. */
    private long sendReadyOrder() throws Exception {
        long orderId = leadOrderInSession();
        seedCustomer(orderId, VALID_EMAIL, VALID_MOBILE_LOCAL);
        saveItemisedTwoLineDraft(orderId);
        return orderId;
    }

    private MvcResult sendEmailOk(long orderId) throws Exception {
        clearJpaCache();
        return mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    // ---- DB probes ----

    private int versionCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
    }

    private int versionCountByStatus(long orderId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ? AND status = ?",
                Integer.class, orderId, status);
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

    private int tokenCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_token t "
                        + "JOIN quote_version v ON t.quote_version_id = v.quote_version_id "
                        + "WHERE v.order_id = ?", Integer.class, orderId);
    }

    private int tokenCountByStatus(long orderId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_token t "
                        + "JOIN quote_version v ON t.quote_version_id = v.quote_version_id "
                        + "WHERE v.order_id = ? AND t.status = ?", Integer.class, orderId, status);
    }

    private Map<String, Object> singleTokenRow(long orderId, String status) {
        return jdbcTemplate.queryForMap(
                "SELECT t.* FROM quote_token t "
                        + "JOIN quote_version v ON t.quote_version_id = v.quote_version_id "
                        + "WHERE v.order_id = ? AND t.status = ?", orderId, status);
    }

    /** The one-ACTIVE-token-per-order + one-ISSUED-version-per-order invariants, in one probe. */
    private void assertOrderInvariants(long orderId) {
        Assertions.assertTrue(versionCountByStatus(orderId, "ISSUED") <= 1,
                "at most one ISSUED quote_version per order");
        Assertions.assertTrue(tokenCountByStatus(orderId, "ACTIVE") <= 1,
                "at most one ACTIVE quote_token per order");
    }

    private static void assertMoney(String expected, Object actual) {
        Assertions.assertTrue(actual instanceof BigDecimal
                        && new BigDecimal(expected).compareTo((BigDecimal) actual) == 0,
                "expected " + expected + " but was " + actual);
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** Extract the plaintext token from a delivered message body's /{slug}/q/{token} link. */
    private static String extractToken(String messageBody) {
        Matcher matcher = LINK_TOKEN_PATTERN.matcher(messageBody);
        Assertions.assertTrue(matcher.find(), "message body must contain the /q/{token} link: " + messageBody);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "token link must appear exactly once in the body");
        return token;
    }

    private static String pdfText(byte[] pdfBytes) throws Exception {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    // ================================================================
    // First send (email) — itemised issue
    // ================================================================

    @Test
    void sendEmail_firstSend_itemised_issuesVersionStoresPdfMintsToken() throws Exception {
        long orderId = sendReadyOrder();
        LocalDateTime before = LocalDateTime.now();

        MvcResult result = mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Quote sent by email."))
                .andExpect(jsonPath("$.data.version_number").value(1))
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.itemised").value(true))
                .andExpect(jsonPath("$.data.quote_total_ex_gst").value(250.00))
                .andExpect(jsonPath("$.data.quote_total_inc_gst").value(275.00))
                .andExpect(jsonPath("$.data.flooring_type").value("SOFT"))
                .andExpect(jsonPath("$.data.sent_channel").value("EMAIL"))
                .andExpect(jsonPath("$.data.first_sent_at").isNotEmpty())
                .andExpect(jsonPath("$.data.last_sent_at").isNotEmpty())
                .andExpect(jsonPath("$.data.last_emailed_at").isNotEmpty())
                .andExpect(jsonPath("$.data.viewed_at").value(nullValue()))
                .andExpect(jsonPath("$.data.token_expires_at").isNotEmpty())
                // 16E-B frozen body snapshot: no details were saved on this order (null), and the
                // itemised issue snapshots BOTH lines in (sort_order, PK) order with the full
                // customer-facing field set — never a cost, id or snapshot-internal field.
                .andExpect(jsonPath("$.data.details_of_sale").value(nullValue()))
                .andExpect(jsonPath("$.data.lines", hasSize(2)))
                .andExpect(jsonPath("$.data.lines[0].line_type").value("ITEM"))
                .andExpect(jsonPath("$.data.lines[0].description").value("Carpet"))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(2.00))
                .andExpect(jsonPath("$.data.lines[0].unit_price_ex_gst").value(100.00))
                .andExpect(jsonPath("$.data.lines[0].line_total_ex_gst").value(200.00))
                .andExpect(jsonPath("$.data.lines[0].sort_order").value(0))
                .andExpect(jsonPath("$.data.lines[1].line_type").value("ITEM"))
                .andExpect(jsonPath("$.data.lines[1].description").value("Underlay"))
                .andExpect(jsonPath("$.data.lines[1].quantity").value(1.00))
                .andExpect(jsonPath("$.data.lines[1].unit_price_ex_gst").value(50.00))
                .andExpect(jsonPath("$.data.lines[1].line_total_ex_gst").value(50.00))
                .andExpect(jsonPath("$.data.lines[1].sort_order").value(1))
                .andReturn();

        // Version snapshot persisted.
        Map<String, Object> version = issuedVersionRow(orderId);
        Assertions.assertEquals(Boolean.TRUE, version.get("itemised"));
        assertMoney("250.00", version.get("quote_total_ex_gst"));
        assertMoney("275.00", version.get("quote_total_inc_gst"));
        Assertions.assertEquals("SOFT", version.get("flooring_type_snapshot"));
        Assertions.assertEquals("EMAIL", version.get("sent_channel"));
        Assertions.assertNotNull(version.get("first_sent_at"));
        Assertions.assertNotNull(version.get("last_sent_at"));
        Assertions.assertNotNull(version.get("last_emailed_at"));
        Assertions.assertNotNull(version.get("issued_pdf_file_id"), "issued PDF stored_file linked");
        Assertions.assertEquals(2, versionLineCount(orderId), "itemised issue snapshots the lines");

        // stored_file row present with the locked file name.
        String fileName = jdbcTemplate.queryForObject(
                "SELECT file_name FROM stored_file WHERE stored_file_id = ?",
                String.class, ((Number) version.get("issued_pdf_file_id")).longValue());
        String orderNumber = jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
        Assertions.assertEquals("quote-" + orderNumber + "-v1.pdf", fileName);

        // One ACTIVE token, hash-only, ~7-day expiry.
        Assertions.assertEquals(1, tokenCount(orderId));
        Map<String, Object> token = singleTokenRow(orderId, "ACTIVE");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size());
        QuoteEmailRequest email = sent.get(0);
        Assertions.assertEquals(VALID_EMAIL, email.recipientEmail());
        Assertions.assertTrue(email.pdfBytes().length > 0, "issued PDF attached");
        Assertions.assertEquals("quote-" + orderNumber + "-v1.pdf", email.pdfFileName());

        String plainToken = extractToken(email.bodyText());
        Assertions.assertTrue(plainToken.length() >= 43, "32 random bytes → ≥43 URL-safe chars");
        Assertions.assertEquals(sha256Hex(plainToken), token.get("token_hash"),
                "only the SHA-256 hash of the token is stored");
        java.sql.Timestamp expiresAt = (java.sql.Timestamp) token.get("expires_at");
        Assertions.assertTrue(expiresAt.toLocalDateTime().isAfter(before.plusDays(6)), "expiry ≈ +7d (lower)");
        Assertions.assertTrue(expiresAt.toLocalDateTime().isBefore(before.plusDays(8)), "expiry ≈ +7d (upper)");

        // The protected response never carries the plaintext token or the hash.
        String responseBody = result.getResponse().getContentAsString();
        Assertions.assertFalse(responseBody.contains(plainToken), "201 body must not leak the token");
        Assertions.assertFalse(responseBody.contains((String) token.get("token_hash")),
                "201 body must not leak the token hash");

        assertOrderInvariants(orderId);
    }

    // ================================================================
    // Non-itemised issue — the dormant-row guard (HIGH priority)
    // ================================================================

    @Test
    void sendEmail_nonItemisedWithDormantRows_snapshotsZeroLines_keepsDormantRows() throws Exception {
        long orderId = leadOrderInSession();
        seedCustomer(orderId, VALID_EMAIL, VALID_MOBILE_LOCAL);
        saveItemisedTwoLineDraft(orderId);          // persists 2 itemised rows
        saveNonItemisedDraft(orderId, "330.00");    // header-only; rows retained as DORMANT
        Assertions.assertEquals(2, draftLineCount(orderId), "dormant rows retained before send");
        // Blank the tenant terms: the seeded demo terms text mentions "Carpet", which would fog the
        // dormant-line PDF assertions below (the frozen terms page is expected content; a rendered
        // dormant line is the bug being guarded).
        jdbcTemplate.update("UPDATE business SET terms_soft = NULL, terms_hard = NULL "
                + "WHERE business_id = ?", BUSINESS_AUSSIE);
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Supply only, non-itemised' "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();

        // 16E-B summary body on a NON-itemised issue: lines is ALWAYS an empty list (the dormant
        // retained rows are never snapshotted or serialized) while quote_total_ex_gst and
        // details_of_sale still ride on the summary.
        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.itemised").value(false))
                .andExpect(jsonPath("$.data.quote_total_ex_gst").value(300.00))
                .andExpect(jsonPath("$.data.quote_total_inc_gst").value(330.00))
                .andExpect(jsonPath("$.data.details_of_sale").value("Supply only, non-itemised"))
                .andExpect(jsonPath("$.data.lines").isArray())
                .andExpect(jsonPath("$.data.lines", hasSize(0)));

        // The workspace current_issued mirrors the same empty-lines / details snapshot shape.
        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued.quote_total_ex_gst").value(300.00))
                .andExpect(jsonPath("$.data.current_issued.details_of_sale")
                        .value("Supply only, non-itemised"))
                .andExpect(jsonPath("$.data.current_issued.lines", hasSize(0)));

        Map<String, Object> version = issuedVersionRow(orderId);
        Assertions.assertEquals(Boolean.FALSE, version.get("itemised"));
        assertMoney("300.00", version.get("quote_total_ex_gst"));
        assertMoney("330.00", version.get("quote_total_inc_gst"));
        Assertions.assertEquals(0, versionLineCount(orderId),
                "a non-itemised issue must snapshot ZERO quote_version_line rows");
        Assertions.assertEquals(2, draftLineCount(orderId),
                "dormant draft rows survive the issue (still restorable by an itemised toggle)");

        // The issued PDF renders the single-amount presentation — never the dormant rows.
        byte[] pdf = mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String text = pdfText(pdf);
        Assertions.assertFalse(text.contains("Carpet"), "dormant line must not render on the issued PDF");
        Assertions.assertFalse(text.contains("Underlay"), "dormant line must not render on the issued PDF");
        Assertions.assertTrue(text.contains("$330.00"), "non-itemised total renders");
    }

    // ================================================================
    // Changed-detection
    // ================================================================

    @Test
    void sendEmail_unchangedDraft_resendsSameVersion_replacesToken() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);
        long v1Id = ((Number) issuedVersionRow(orderId).get("quote_version_id")).longValue();

        // A NO-OP resave (identical body) refreshes quote_draft.updated_at — timestamps must play
        // no part in changed-detection (locked rule).
        saveItemisedTwoLineDraft(orderId);

        sendEmailOk(orderId);

        Assertions.assertEquals(1, versionCount(orderId), "unchanged draft resends the SAME version");
        Assertions.assertEquals(v1Id, ((Number) issuedVersionRow(orderId).get("quote_version_id")).longValue());
        Assertions.assertEquals(2, tokenCount(orderId), "each send mints a token");
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "REPLACED"), "old token → REPLACED");
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "ACTIVE"), "new token → ACTIVE");
        Assertions.assertNotNull(singleTokenRow(orderId, "REPLACED").get("dead_at"));
        Assertions.assertEquals(2, quoteEmailSender.sentEmails().size());
        // Resend reuses the STORED bytes verbatim.
        Assertions.assertArrayEquals(quoteEmailSender.sentEmails().get(0).pdfBytes(),
                quoteEmailSender.sentEmails().get(1).pdfBytes());
        assertOrderInvariants(orderId);
    }

    @Test
    void sendEmail_changedLine_createsNewVersion_supersedesOld() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);
        long v1Id = ((Number) issuedVersionRow(orderId).get("quote_version_id")).longValue();
        Object v1FileId = versionRowByNumber(orderId, 1).get("issued_pdf_file_id");

        // Change one line's unit price (275 -> 297 inc).
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":110,"line_total_ex_gst":220,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andExpect(status().isOk());
        clearJpaCache();

        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version_number").value(2));

        Assertions.assertEquals(2, versionCount(orderId));
        Map<String, Object> v1 = versionRowByNumber(orderId, 1);
        Assertions.assertEquals("SUPERSEDED", v1.get("status"));
        Map<String, Object> v2 = issuedVersionRow(orderId);
        Assertions.assertEquals(2, v2.get("version_number"));
        assertMoney("297.00", v2.get("quote_total_inc_gst"));
        Assertions.assertNotEquals(((Number) v1FileId).longValue(),
                ((Number) v2.get("issued_pdf_file_id")).longValue(), "new version stores a NEW PDF");
        // v1's stored PDF row is untouched history.
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE stored_file_id = ?",
                Integer.class, ((Number) v1FileId).longValue()));

        // Token transitions: v1's token SUPERSEDED (dead), v2 has the single ACTIVE token.
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "SUPERSEDED"));
        Assertions.assertNotNull(singleTokenRow(orderId, "SUPERSEDED").get("dead_at"));
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "ACTIVE"));
        Assertions.assertEquals(v1Id, ((Number) jdbcTemplate.queryForObject(
                        "SELECT quote_version_id FROM quote_token t WHERE t.status = 'SUPERSEDED' "
                                + "AND t.quote_version_id IN (SELECT quote_version_id FROM quote_version WHERE order_id = ?)",
                        Long.class, orderId)).longValue(),
                "the superseded token belongs to v1");
        assertOrderInvariants(orderId);
    }

    @Test
    void sendEmail_changedDetailsOfSale_createsNewVersion() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);

        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Supply and lay to lounge' "
                + "WHERE order_id = ?", orderId);

        sendEmailOk(orderId);

        Assertions.assertEquals(2, versionCount(orderId), "details change → new version");
        Assertions.assertEquals("Supply and lay to lounge",
                issuedVersionRow(orderId).get("details_of_sale_snapshot"));
        assertOrderInvariants(orderId);
    }

    @Test
    void sendEmail_changedFlooringType_createsNewVersion() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);

        jdbcTemplate.update("UPDATE sales_order SET flooring_type = 'HARD'::flooring_type "
                + "WHERE order_id = ?", orderId);

        sendEmailOk(orderId);

        Assertions.assertEquals(2, versionCount(orderId), "flooring type change → new version");
        Assertions.assertEquals("HARD", issuedVersionRow(orderId).get("flooring_type_snapshot"));
        assertOrderInvariants(orderId);
    }

    @Test
    void sendEmail_duplicateSortOrders_unchangedResendStillReusesVersion() throws Exception {
        // Duplicate sort_order values are contract-legal via direct API clients (no uniqueness
        // rule). The PK tiebreaker on both line reads must keep the positional changed-detection
        // compare deterministic — an unchanged draft must never spuriously issue a new version
        // (16E-A adversarial-review finding).
        long orderId = leadOrderInSession();
        seedCustomer(orderId, VALID_EMAIL, VALID_MOBILE_LOCAL);
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Tied A","quantity":1,"unit_price_ex_gst":100,"line_total_ex_gst":100,"sort_order":0},
                                  {"line_type":"ITEM","description":"Tied B","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":0}
                                ]}"""))
                .andExpect(status().isOk());
        clearJpaCache();

        sendEmailOk(orderId);
        sendEmailOk(orderId);

        Assertions.assertEquals(1, versionCount(orderId),
                "tied sort_orders must not make an unchanged draft look changed");
        Assertions.assertEquals(2, versionLineCount(orderId));
        assertOrderInvariants(orderId);
    }

    @Test
    void sendEmail_nonItemisedResend_dormantRowsNeverForceNewVersion() throws Exception {
        long orderId = leadOrderInSession();
        seedCustomer(orderId, VALID_EMAIL, VALID_MOBILE_LOCAL);
        saveItemisedTwoLineDraft(orderId);
        saveNonItemisedDraft(orderId, "330.00");
        sendEmailOk(orderId);

        // Another header-only non-itemised save; the dormant rows are still returned on reads but
        // must be EXCLUDED from the comparison (a non-itemised issue snapshots zero lines).
        saveNonItemisedDraft(orderId, "330.00");
        sendEmailOk(orderId);

        Assertions.assertEquals(1, versionCount(orderId),
                "dormant retained rows must never make an unchanged non-itemised draft look changed");
        assertOrderInvariants(orderId);
    }

    // ================================================================
    // Below-cost re-check at send
    // ================================================================

    @Test
    void sendEmail_belowCost_returns422_persistsNothing() throws Exception {
        long orderId = sendReadyOrder(); // draft ex total 250.00, no cost lines yet
        // Costs rise AFTER the successful save: live cost 400 > quote ex 250 → below cost at send.
        seedChargeLine(orderId, STORE_SYD_CBD, "500.00", "400.00");

        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("QUOTE_BELOW_COST"));

        Assertions.assertEquals(0, versionCount(orderId), "no quote_version persisted");
        Assertions.assertEquals(0, tokenCount(orderId), "no quote_token persisted");
        Assertions.assertEquals(0, versionLineCount(orderId), "no quote_version_line persisted");
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty(), "nothing delivered");
    }

    // ================================================================
    // LAID behaviour
    // ================================================================

    @Test
    void sendEmail_laidOrder_returns422OrderLocked() throws Exception {
        long orderId = sendReadyOrder();
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();

        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));

        Assertions.assertEquals(0, versionCount(orderId));
        Assertions.assertEquals(0, tokenCount(orderId));
    }

    @Test
    void sendSms_laidOrder_returns422OrderLocked() throws Exception {
        long orderId = sendReadyOrder();
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();

        mockMvc.perform(post(sendSmsUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));

        Assertions.assertEquals(0, versionCount(orderId));
    }

    @Test
    void cancel_laidOrder_allowed() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();

        mockMvc.perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void downloadIssuedPdf_laidOrder_allowed() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();

        mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isOk());
    }

    // ================================================================
    // Delivery failure — 502, everything persisted KEPT
    // ================================================================

    @Test
    void sendEmail_providerFailure_returns502_keepsVersionPdfTokenAndAttemptMarkers() throws Exception {
        long orderId = sendReadyOrder();
        quoteEmailSender.failNextSend();

        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("EMAIL_SEND_FAILED"));

        // Locked §7.1 send-failure rule: issued version + PDF + token + ATTEMPT markers persist.
        Map<String, Object> version = issuedVersionRow(orderId);
        Assertions.assertEquals("EMAIL", version.get("sent_channel"));
        Assertions.assertNotNull(version.get("first_sent_at"), "attempt marker kept on failure");
        Assertions.assertNotNull(version.get("last_sent_at"), "attempt marker kept on failure");
        Assertions.assertNull(version.get("last_emailed_at"), "success-only marker NOT stamped");
        Assertions.assertNotNull(version.get("issued_pdf_file_id"), "stored PDF kept");
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "ACTIVE"), "token kept");
        Assertions.assertEquals(1, quoteEmailSender.failedEmails().size());

        // Retry: the draft is unchanged relative to the persisted version → resend path.
        sendEmailOk(orderId);
        Assertions.assertEquals(1, versionCount(orderId), "retry reuses the persisted version");
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "REPLACED"));
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "ACTIVE"));
        Assertions.assertNotNull(issuedVersionRow(orderId).get("last_emailed_at"),
                "success stamp lands on the retry");
        assertOrderInvariants(orderId);
    }

    @Test
    void sendSms_providerFailure_returns502_keepsEverything() throws Exception {
        long orderId = sendReadyOrder();
        smsSender.failNextSend();

        mockMvc.perform(post(sendSmsUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("SMS_SEND_FAILED"));

        Map<String, Object> version = issuedVersionRow(orderId);
        Assertions.assertEquals("SMS", version.get("sent_channel"));
        Assertions.assertNotNull(version.get("first_sent_at"));
        Assertions.assertNull(version.get("last_emailed_at"));
        Assertions.assertNotNull(version.get("issued_pdf_file_id"));
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "ACTIVE"));
        Assertions.assertEquals(1, smsSender.failedMessages().size());
    }

    // ================================================================
    // SMS success path
    // ================================================================

    @Test
    void sendSms_localMobile_issuesAndDeliversLinkOnly_neverStampsLastEmailedAt() throws Exception {
        long orderId = leadOrderInSession();
        seedCustomer(orderId, VALID_EMAIL, VALID_MOBILE_LOCAL);
        saveItemisedTwoLineDraft(orderId);

        mockMvc.perform(post(sendSmsUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Quote sent by SMS."))
                .andExpect(jsonPath("$.data.sent_channel").value("SMS"))
                .andExpect(jsonPath("$.data.last_emailed_at").value(nullValue()))
                // 16E-B: the SMS 201 summary carries the frozen body snapshot too (its own
                // plumbing — PreparedSend.summaryLines — distinct from the email path's).
                .andExpect(jsonPath("$.data.quote_total_ex_gst").value(250.00))
                .andExpect(jsonPath("$.data.lines", hasSize(2)));

        Map<String, Object> version = issuedVersionRow(orderId);
        Assertions.assertEquals("SMS", version.get("sent_channel"));
        Assertions.assertNull(version.get("last_emailed_at"), "SMS never stamps last_emailed_at");

        List<SmsRequest> sent = smsSender.sentMessages();
        Assertions.assertEquals(1, sent.size());
        Assertions.assertEquals(VALID_MOBILE_LOCAL, sent.get(0).recipientMobile());
        String token = extractToken(sent.get(0).messageText());
        Assertions.assertEquals(sha256Hex(token), singleTokenRow(orderId, "ACTIVE").get("token_hash"));
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty(), "SMS path sends no email");
        assertOrderInvariants(orderId);
    }

    @Test
    void sendSms_internationalMobile_accepted() throws Exception {
        long orderId = leadOrderInSession();
        seedCustomer(orderId, VALID_EMAIL, VALID_MOBILE_INTL);
        saveItemisedTwoLineDraft(orderId);

        mockMvc.perform(post(sendSmsUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        Assertions.assertEquals(VALID_MOBILE_INTL, smsSender.sentMessages().get(0).recipientMobile());
    }

    @Test
    void sendSms_afterEmail_keepsLastEmailedAt_switchesChannel() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);
        Object emailedAt = issuedVersionRow(orderId).get("last_emailed_at");
        Assertions.assertNotNull(emailedAt);

        mockMvc.perform(post(sendSmsUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        Map<String, Object> version = issuedVersionRow(orderId);
        Assertions.assertEquals("SMS", version.get("sent_channel"), "latest channel wins");
        Assertions.assertEquals(emailedAt, version.get("last_emailed_at"),
                "an SMS resend never touches last_emailed_at");
        Assertions.assertEquals(1, versionCount(orderId), "unchanged draft — same version across channels");
        assertOrderInvariants(orderId);
    }

    // ================================================================
    // Recipient gates
    // ================================================================

    @Test
    void sendEmail_missingCustomer_returns422EmailRequired() throws Exception {
        long orderId = leadOrderInSession();
        // No order_customer row at all; the recipient gate fires BEFORE the draft check.
        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"));
    }

    @Test
    void sendEmail_invalidEmail_returns422EmailInvalid() throws Exception {
        long orderId = leadOrderInSession();
        // Passes the DB CHECK (contains '@') but fails the validator (no interior dot in the domain).
        seedCustomer(orderId, "bad@email", VALID_MOBILE_LOCAL);
        saveItemisedTwoLineDraft(orderId);

        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_INVALID"));

        Assertions.assertEquals(0, versionCount(orderId));
    }

    @Test
    void sendSms_missingCustomer_returns422MobileRequired() throws Exception {
        long orderId = leadOrderInSession();
        mockMvc.perform(post(sendSmsUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_MOBILE_REQUIRED"));
    }

    @Test
    void sendSms_invalidMobile_returns422MobileInvalid_nothingPersisted() throws Exception {
        long orderId = leadOrderInSession();
        seedCustomer(orderId, VALID_EMAIL, "0412 345 678"); // separators → strict rule rejects
        saveItemisedTwoLineDraft(orderId);

        mockMvc.perform(post(sendSmsUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_MOBILE_INVALID"));

        Assertions.assertEquals(0, versionCount(orderId));
        Assertions.assertTrue(smsSender.sentMessages().isEmpty());
    }

    // ================================================================
    // No draft / body / scope gates
    // ================================================================

    @Test
    void sendEmail_noDraft_returns404QuoteNotFound() throws Exception {
        long orderId = leadOrderInSession();
        seedCustomer(orderId, VALID_EMAIL, VALID_MOBILE_LOCAL);

        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_NOT_FOUND"));
    }

    @Test
    void sendEmail_bodyWithFields_returns400Validation() throws Exception {
        long orderId = sendReadyOrder();
        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"EMAIL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        Assertions.assertEquals(0, versionCount(orderId));
    }

    @Test
    void sendEmail_blankBody_allowed() throws Exception {
        long orderId = sendReadyOrder();
        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isCreated());
    }

    @Test
    void sendEmail_crossStore_returns404_writesNothing() throws Exception {
        long orderId = insertOrder(BUSINESS_AUSSIE, storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD),
                USER_LIAM, "LEAD");
        seedCustomer(orderId, VALID_EMAIL, VALID_MOBILE_LOCAL);

        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        Assertions.assertEquals(0, versionCount(orderId));
    }

    @Test
    void sendEmail_noSession_returns401() throws Exception {
        mockMvc.perform(post(sendEmailUrl(123)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private int storeInBusiness(long businessId, Integer excludeStore) {
        String sql = "SELECT store_id FROM store WHERE business_id = ? AND is_active = TRUE"
                + (excludeStore == null ? "" : " AND store_id <> " + excludeStore)
                + " ORDER BY store_id LIMIT 1";
        return jdbcTemplate.queryForObject(sql, Integer.class, businessId);
    }

    // ================================================================
    // Cancel
    // ================================================================

    @Test
    void cancel_activeIssued_cancelsVersionAndToken_keepsRows() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);

        mockMvc.perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Quote cancelled."))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.token_expires_at").value(nullValue()))
                // 16E-B: the cancel response carries the frozen body snapshot too.
                .andExpect(jsonPath("$.data.quote_total_ex_gst").value(250.00))
                .andExpect(jsonPath("$.data.lines", hasSize(2)));

        Assertions.assertEquals(1, versionCountByStatus(orderId, "CANCELLED"));
        Assertions.assertEquals(0, versionCountByStatus(orderId, "ISSUED"));
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "CANCELLED"), "token kept with reason");
        Assertions.assertNotNull(singleTokenRow(orderId, "CANCELLED").get("dead_at"));
        Assertions.assertEquals(0, tokenCountByStatus(orderId, "ACTIVE"));

        // The workspace no longer surfaces a current issued quote.
        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()));
    }

    @Test
    void cancel_noIssuedVersion_returns422QuoteNotIssued() throws Exception {
        long orderId = sendReadyOrder(); // draft exists but nothing sent
        mockMvc.perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("QUOTE_NOT_ISSUED"));
    }

    @Test
    void cancel_acceptedVersionOnly_returns409QuoteAlreadyAccepted() throws Exception {
        long orderId = sendReadyOrder();
        // Defensive branch: an ACCEPTED version is unreachable via the 16E-A API but is valid data
        // once 16F lands — seed it directly (the QuoteMigrationConstraintsTest pattern).
        jdbcTemplate.update(
                "INSERT INTO quote_version (order_id, version_number, status, itemised, quote_total_ex_gst, "
                        + " quote_total_inc_gst, flooring_type_snapshot, created_by_user_id) "
                        + "VALUES (?, 1, 'ACCEPTED', TRUE, 250.00, 275.00, 'SOFT', ?)",
                orderId, USER_LIAM);

        mockMvc.perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUOTE_ALREADY_ACCEPTED"));
    }

    // ================================================================
    // Stored-PDF download
    // ================================================================

    @Test
    void downloadPdf_issued_streamsInlineWithVersionedFilename() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);
        String orderNumber = jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);

        byte[] bytes = mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("quote-" + orderNumber + "-v1.pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        Assertions.assertTrue(bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P', "PDF magic header");
        // The streamed bytes are the exact stored/emailed artifact.
        Assertions.assertArrayEquals(quoteEmailSender.sentEmails().get(0).pdfBytes(), bytes);
    }

    @Test
    void downloadPdf_noIssuedVersion_returns404QuotePdfNotFound() throws Exception {
        long orderId = sendReadyOrder(); // nothing sent
        mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_PDF_NOT_FOUND"));
    }

    @Test
    void downloadPdf_acceptedType_returns404QuotePdfNotFound() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);
        // 16F artifact: no signed PDF can exist in 16E-A → contract-safe 404.
        mockMvc.perform(get(pdfUrl(orderId, "accepted")).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_PDF_NOT_FOUND"));
    }

    @Test
    void downloadPdf_missingOrBogusType_returns400() throws Exception {
        long orderId = sendReadyOrder();
        sendEmailOk(orderId);
        mockMvc.perform(get(pdfUrl(orderId, null)).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(pdfUrl(orderId, "signed")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void downloadPdf_crossStore_returns404BeforeTypeValidation() throws Exception {
        long orderId = insertOrder(BUSINESS_AUSSIE, storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD),
                USER_LIAM, "LEAD");
        // Bogus type + out-of-scope order: the scoped 404 must win (no existence leak).
        mockMvc.perform(get(pdfUrl(orderId, "signed")).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // ================================================================
    // Issued PDF is a frozen snapshot
    // ================================================================

    @Test
    void issuedPdf_termsFrozenAtIssue_liveTermsChangesNeverAlterIt() throws Exception {
        long orderId = sendReadyOrder();
        jdbcTemplate.update("UPDATE business SET terms_soft = '<p>Original quote terms.</p>' "
                + "WHERE business_id = ?", BUSINESS_AUSSIE);

        sendEmailOk(orderId);

        Map<String, Object> v1 = issuedVersionRow(orderId);
        String frozenTerms = (String) v1.get("terms_snapshot");
        Assertions.assertNotNull(frozenTerms);
        Assertions.assertTrue(frozenTerms.contains("Original quote terms."),
                "sanitized live terms frozen into terms_snapshot at issue");
        byte[] issuedBytes = mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Assertions.assertTrue(pdfText(issuedBytes).contains("Original quote terms."));

        // Tenant terms change AFTER issue: an unchanged-draft resend must reuse the same version,
        // the same terms_snapshot, and the same stored PDF bytes (terms are excluded from
        // changed-detection; the stored artifact is immutable).
        jdbcTemplate.update("UPDATE business SET terms_soft = '<p>Rewritten terms.</p>' "
                + "WHERE business_id = ?", BUSINESS_AUSSIE);
        sendEmailOk(orderId);

        Assertions.assertEquals(1, versionCount(orderId), "terms change alone never re-issues");
        Assertions.assertEquals(frozenTerms, issuedVersionRow(orderId).get("terms_snapshot"));
        byte[] afterBytes = mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(issuedBytes, afterBytes, "stored issued PDF is immutable");
        Assertions.assertFalse(pdfText(afterBytes).contains("Rewritten terms."));
    }

    @Test
    void issuedPdf_detailsChangeAfterIssue_storedPdfUnchangedUntilNewVersionIssued() throws Exception {
        long orderId = sendReadyOrder();
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Original details' "
                + "WHERE order_id = ?", orderId);
        sendEmailOk(orderId);
        byte[] v1Bytes = mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Assertions.assertTrue(pdfText(v1Bytes).contains("Original details"));

        // Live details change: the stored v1 PDF must be untouched until a new version is issued.
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Updated details' "
                + "WHERE order_id = ?", orderId);
        byte[] stillV1 = mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(v1Bytes, stillV1, "stored PDF never re-renders from live data");

        // The next send sees the changed details → NEW version with the new snapshot.
        sendEmailOk(orderId);
        Assertions.assertEquals(2, versionCount(orderId));
        byte[] v2Bytes = mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Assertions.assertTrue(pdfText(v2Bytes).contains("Updated details"));
        assertOrderInvariants(orderId);
    }

    // ================================================================
    // Workspace summary
    // ================================================================

    @Test
    void workspace_currentIssuedPopulatedAfterSend_acceptedNull_noLeakage() throws Exception {
        long orderId = sendReadyOrder();
        MvcResult sendResult = sendEmailOk(orderId);
        String plainToken = extractToken(quoteEmailSender.sentEmails().get(0).bodyText());
        String tokenHash = (String) singleTokenRow(orderId, "ACTIVE").get("token_hash");

        MvcResult result = mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft.itemised").value(true))
                .andExpect(jsonPath("$.data.current_issued.version_number").value(1))
                .andExpect(jsonPath("$.data.current_issued.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.current_issued.itemised").value(true))
                .andExpect(jsonPath("$.data.current_issued.quote_total_ex_gst").value(250.00))
                .andExpect(jsonPath("$.data.current_issued.quote_total_inc_gst").value(275.00))
                .andExpect(jsonPath("$.data.current_issued.flooring_type").value("SOFT"))
                .andExpect(jsonPath("$.data.current_issued.sent_channel").value("EMAIL"))
                .andExpect(jsonPath("$.data.current_issued.first_sent_at").isNotEmpty())
                .andExpect(jsonPath("$.data.current_issued.last_sent_at").isNotEmpty())
                .andExpect(jsonPath("$.data.current_issued.last_emailed_at").isNotEmpty())
                .andExpect(jsonPath("$.data.current_issued.viewed_at").value(nullValue()))
                .andExpect(jsonPath("$.data.current_issued.token_expires_at").isNotEmpty())
                // 16E-B: the workspace summary carries the frozen body snapshot — details (null
                // here; never saved) and the ordered issued lines.
                .andExpect(jsonPath("$.data.current_issued.details_of_sale").value(nullValue()))
                .andExpect(jsonPath("$.data.current_issued.lines", hasSize(2)))
                .andExpect(jsonPath("$.data.current_issued.lines[0].description").value("Carpet"))
                .andExpect(jsonPath("$.data.current_issued.lines[0].sort_order").value(0))
                .andExpect(jsonPath("$.data.current_issued.lines[1].description").value("Underlay"))
                .andExpect(jsonPath("$.data.current_issued.lines[1].sort_order").value(1))
                .andExpect(jsonPath("$.data.accepted").value(nullValue()))
                .andReturn();

        // Leak sweep across both response bodies: token material, storage paths, cost keys.
        for (String body : List.of(result.getResponse().getContentAsString(),
                sendResult.getResponse().getContentAsString())) {
            Assertions.assertFalse(body.contains(plainToken), "no plaintext token in any response");
            Assertions.assertFalse(body.contains(tokenHash), "no token hash in any response");
            Assertions.assertFalse(body.contains("token_hash"), "no token_hash key");
            Assertions.assertFalse(body.contains("storage_path"), "no storage path");
            Assertions.assertFalse(body.toLowerCase().contains("cost_snapshot"), "no cost snapshot key");
            Assertions.assertFalse(body.contains("\"cost\""), "no cost key");
        }
    }

    @Test
    void workspace_noSend_currentIssuedNull() throws Exception {
        long orderId = sendReadyOrder();
        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()))
                .andExpect(jsonPath("$.data.accepted").value(nullValue()));
    }

    @Test
    void workspace_currentIssuedDetails_areFrozenSnapshot_notLiveOrder() throws Exception {
        long orderId = sendReadyOrder();
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Frozen at issue' "
                + "WHERE order_id = ?", orderId);
        sendEmailOk(orderId);

        // Live details change AFTER the issue: the summary must keep the FROZEN
        // details_of_sale_snapshot — the Customer Quote surface renders from current_issued
        // only, never from live order/draft state (16E-B locked rule).
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Changed after issue' "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();

        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued.details_of_sale")
                        .value("Frozen at issue"));
    }

    // ================================================================
    // Lifecycle walk — the order-level invariants hold at every step
    // ================================================================

    @Test
    void lifecycleWalk_invariantsHoldAtEveryStep() throws Exception {
        long orderId = sendReadyOrder();

        sendEmailOk(orderId);                 // issue v1
        assertOrderInvariants(orderId);

        sendEmailOk(orderId);                 // resend v1 (new token)
        assertOrderInvariants(orderId);

        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'walk change' "
                + "WHERE order_id = ?", orderId);
        sendEmailOk(orderId);                 // supersede v1 → issue v2
        assertOrderInvariants(orderId);
        Assertions.assertEquals(2, versionCount(orderId));

        mockMvc.perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());  // cancel v2
        assertOrderInvariants(orderId);
        Assertions.assertEquals(0, versionCountByStatus(orderId, "ISSUED"));
        Assertions.assertEquals(0, tokenCountByStatus(orderId, "ACTIVE"));
        // Dead rows all kept, each with its reason: v1's first token (REPLACED by the resend),
        // v1's second token (SUPERSEDED by v2), and v2's token (CANCELLED).
        Assertions.assertEquals(3, tokenCount(orderId));
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "REPLACED"));
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "SUPERSEDED"));
        Assertions.assertEquals(1, tokenCountByStatus(orderId, "CANCELLED"));
    }

    // ================================================================
    // Order pricing / invoice mirror stay untouched
    // ================================================================

    @Test
    void sendEmail_neverTouchesOrderPricingOrInvoiceEmailMirror() throws Exception {
        long orderId = sendReadyOrder();
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = 55.50 "
                + "WHERE order_id = ?", orderId);

        sendEmailOk(orderId);

        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT price_adjustment_inc_gst, last_emailed_at FROM sales_order WHERE order_id = ?",
                orderId);
        assertMoney("55.50", order.get("price_adjustment_inc_gst"));
        Assertions.assertNull(order.get("last_emailed_at"),
                "sales_order.last_emailed_at is the INVOICE mirror — quote sends must never touch it");
    }
}
