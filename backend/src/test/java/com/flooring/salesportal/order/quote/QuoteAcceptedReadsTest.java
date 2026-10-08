package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16F PR1 — the PROTECTED accepted-quote reads: the {@code accepted} member of
 * {@code GET .../quote/workspace}, {@code GET .../quote/pdf?type=accepted} (the stored SIGNED quote
 * PDF) and the new {@code GET .../quote/accepted/signature} (the stored signature PNG).
 *
 * <p>Every accepted version in this class is created through the REAL chain — protected draft save →
 * protected send-email (the plaintext token is read from the recorded email's public link) → the PUBLIC
 * multipart accept endpoint ({@code POST /api/v1/public/quotes/{token}/accept}, no session) — never by
 * raw SQL, so the reads are proven against what an acceptance actually persists: the frozen
 * {@code quote_version} / {@code quote_version_line} rows, the {@code stored_file} metadata and the
 * physical files. Raw SQL is used only to move LIVE state on afterwards (details, customer name, LAID)
 * or to null a stored reference for the defensive 404 branches.
 *
 * <p>Self-seeded (Phase 14D go-forward rule): orders are inserted under business 1 / store 1 / user 1
 * (slug {@code aussie-floors-group}) with the {@code QARDS.ZZ9.} order-number prefix (seq base 120_000);
 * nothing depends on the V4 demo orders or invoices. Cross-tenant sessions discover another store /
 * business / user from the DB (the {@link QuoteControllerTest} helpers) and self-grant store access
 * inside the test transaction. Everything rolls back; the issue/accept rollback hooks delete the files
 * they wrote. Files land under the shared test storage root
 * ({@code app.storage.base-dir=target/test-storage/quote-acceptance}), so a stored file's physical path
 * is that base dir + {@code stored_file.storage_path}. The recording senders are singletons whose state
 * survives the rollback, so every sender this class drives is reset before AND after each test.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class QuoteAcceptedReadsTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;
    private static final long BUSINESS_PREMIER = 2L;

    private static final String ORDER_NUMBER_PREFIX = "QARDS.ZZ9.";
    private static final String VALID_EMAIL = "quote.accepted.reads@example.com";
    private static final String VALID_MOBILE = "0412345678";
    // V17 issue-time snapshot of the seeded customer ('Quote' + 'Tester') — the frozen accepted name.
    private static final String SNAPSHOT_NAME = "Quote Tester";
    private static final String FROZEN_DETAILS = "Supply and lay carpet (frozen at issue)";

    // The plaintext token inside the delivered link .../q/{token} (URL-safe Base64; public shape gate).
    private static final Pattern PUBLIC_LINK_TOKEN = Pattern.compile("/q/([A-Za-z0-9_-]{43,128})");

    // Two DIFFERENT real, decodable PNGs: the accept flow validates the PNG (magic + ImageIO decode)
    // and embeds it in the signed PDF, so fake bytes would be rejected. Spec §1 SERVER NORMALISATION:
    // the server stores (and the signature endpoint streams) a RE-ENCODED PNG of the decoded pixels,
    // never the uploaded bytes — so reads are compared with the stored file on disk, and the stored
    // file with the upload by IMAGE (dimensions + pixels). 1x1 RGBA (70 bytes) and 3x2 RGB (73 bytes):
    // their different dimensions prove WHICH version's signature is streamed.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final byte[] SECOND_SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEElEQVR42mOQtzWDIAY4CwAhwgNtlqP3IgAAAABJRU5ErkJggg==");

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    // openapi QuoteAcceptedSummary: every key is ALWAYS serialized (nullable ones as JSON null).
    private static final Set<String> ACCEPTED_KEYS = Set.of(
            "quote_version_id", "version_number", "quote_total_ex_gst", "quote_total_inc_gst",
            "itemised", "flooring_type", "details_of_sale", "lines", "accepted_at",
            "accepted_customer_name", "accepted_signature_present", "signature_download_path",
            "signed_pdf_available", "invoice_eligible");

    // openapi QuoteIssuedLine — customer-facing snapshot line fields only.
    private static final Set<String> LINE_KEYS = Set.of(
            "line_type", "description", "quantity", "unit_price_ex_gst", "line_total_ex_gst", "sort_order");

    // No server-internal reference may be KEYED anywhere inside data.accepted.
    private static final List<String> FORBIDDEN_KEY_FRAGMENTS =
            List.of("file_id", "storage", "token", "hash", "cost", "gp");

    // Exact money comparisons: JSON decimals are read as BigDecimal (never through a double).
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender acceptanceNotificationSender;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @PersistenceContext
    private EntityManager entityManager;

    // The EFFECTIVE storage root (the class-level property) — resolved exactly like FileStorageService.
    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    private int seq = 120_000;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resetSenders();
    }

    @AfterEach
    void tearDown() {
        // Singletons: never leak recorded messages or an armed failNextSend into another test class.
        resetSenders();
    }

    private void resetSenders() {
        quoteEmailSender.reset();
        acceptanceNotificationSender.reset();
        invoiceEmailSender.reset();
    }

    // ================================================================
    // Helpers — sessions + URLs
    // ================================================================

    private static MockHttpSession session(long userId, long businessId, int storeId) {
        MockHttpSession s = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        s.setAttribute("user_id", userId);
        s.setAttribute("business_id", businessId);
        s.setAttribute("store_id", storeId);
        return s;
    }

    private static MockHttpSession liamStore1Session() {
        return session(USER_LIAM, BUSINESS_AUSSIE, STORE_SYD_CBD);
    }

    private static MockHttpSession liamSessionNoStore() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        return s;
    }

    private static String quoteUrl(String slug, Object orderId, String suffix) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/quote/" + suffix;
    }

    private static String workspaceUrl(Object orderId) {
        return workspaceUrl(SLUG_AUSSIE, orderId);
    }

    private static String workspaceUrl(String slug, Object orderId) {
        return quoteUrl(slug, orderId, "workspace");
    }

    private static String draftUrl(Object orderId) {
        return quoteUrl(SLUG_AUSSIE, orderId, "draft");
    }

    private static String sendEmailUrl(Object orderId) {
        return quoteUrl(SLUG_AUSSIE, orderId, "send-email");
    }

    private static String cancelUrl(Object orderId) {
        return quoteUrl(SLUG_AUSSIE, orderId, "cancel");
    }

    private static String pdfUrl(Object orderId, String type) {
        return pdfUrl(SLUG_AUSSIE, orderId, type);
    }

    private static String pdfUrl(String slug, Object orderId, String type) {
        return quoteUrl(slug, orderId, "pdf?type=" + type);
    }

    private static String acceptedSignatureUrl(Object orderId) {
        return acceptedSignatureUrl(SLUG_AUSSIE, orderId);
    }

    private static String acceptedSignatureUrl(String slug, Object orderId) {
        return quoteUrl(slug, orderId, "accepted/signature");
    }

    private static String publicAcceptUrl(String token) {
        return "/api/v1/public/quotes/" + token + "/accept";
    }

    private static String invoiceAcceptUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/invoices/current/accept";
    }

    private static String invoicesUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/invoices";
    }

    private static String invoiceRewriteUrl(Object orderId) {
        return invoicesUrl(orderId) + "/rewrite";
    }

    private static String paymentsUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/payments";
    }

    /**
     * Detach every hydrated entity. Tests share ONE transaction/persistence context across MockMvc
     * calls, so after a raw JDBC write (or a native draft/version write) a later JPA read would return
     * the STALE cached entity — the same trap the sibling quote tests clear the same way.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    // ================================================================
    // Helpers — self-seeding (business 1 / store 1 / user 1 only)
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

    private void setDetailsOfSale(long orderId, String details) {
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = ? WHERE order_id = ?", details, orderId);
        clearJpaCache();
    }

    /** LEAD order with a send-ready customer (valid email) + billing address (V17 snapshot source). */
    private long readyOrder() {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        seedBillingAddress(orderId);
        return orderId;
    }

    private long readyOrderWithItemisedDraft() throws Exception {
        long orderId = readyOrder();
        saveItemisedTwoLineDraft(orderId);
        return orderId;
    }

    /**
     * A ready order that ALSO passes the D.1 Create / D.2 Rewrite email gate + 9 invoice preconditions:
     * details of sale, proposed lay date + lay date status, an INSTALLATION address next to the billing one,
     * and ONE priced charge line (100.00 ex, cost 40.00 — below the 250.00-ex quote, so not below cost).
     * Seeded with raw SQL like the other self-seeded rows; the charge line leaves the header untouched (the
     * acceptance's D6b write and the invoice snapshots read the lines directly).
     */
    private long invoiceReadyOrder() {
        long orderId = readyOrder();
        jdbcTemplate.update(
                "UPDATE sales_order SET details_of_sale = ?, proposed_lay_date = DATE '2026-12-01', "
                        + "lay_date_status = 'CONFIRMED'::lay_date_status WHERE order_id = ?",
                FROZEN_DETAILS, orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '7', 'Install Street', 'Sydney', 'NSW', '2000')",
                orderId);
        String code = "QARDSC" + (++seq);
        BigDecimal lineTotal = new BigDecimal("100.00");
        BigDecimal lineCost = new BigDecimal("40.00");
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Accepted reads test charge', ?, ?) RETURNING charge_id",
                Long.class, STORE_SYD_CBD, code, lineTotal, lineCost);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Accepted reads test charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code, lineTotal, lineCost, lineTotal, lineTotal, lineCost);
        clearJpaCache();
        return orderId;
    }

    private void saveDraft(long orderId, String body) throws Exception {
        clearJpaCache();
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    /** Itemised: Carpet 2×100 + Underlay 1×50 = 250.00 ex / 275.00 inc. */
    private void saveItemisedTwoLineDraft(long orderId) throws Exception {
        saveDraft(orderId, """
                {"itemised": true, "lines": [
                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                ]}""");
    }

    /** Itemised: Carpet 3×100 + Underlay 1×50 + Labour 2×25 = 400.00 ex / 440.00 inc. */
    private void saveItemisedThreeLineDraft(long orderId) throws Exception {
        saveDraft(orderId, """
                {"itemised": true, "lines": [
                  {"line_type":"ITEM","description":"Carpet","quantity":3,"unit_price_ex_gst":100,"line_total_ex_gst":300,"sort_order":0},
                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1},
                  {"line_type":"ITEM","description":"Labour","quantity":2,"unit_price_ex_gst":25,"line_total_ex_gst":50,"sort_order":2}
                ]}""");
    }

    /**
     * Itemised lines submitted OUT of sort order (Underlay sort 1 first, Carpet sort 0 second) with a
     * direct reduction to 264.00 inc: the server appends a negative ADJUSTMENT line ("Adjustment",
     * -10.00 ex, sort_order 2) → 240.00 ex / 264.00 inc. Snapshot order = (sort_order, PK).
     */
    private void saveItemisedDraftWithAdjustment(long orderId) throws Exception {
        saveDraft(orderId, """
                {"itemised": true, "final_total_inc_gst": 264.00, "lines": [
                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1},
                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0}
                ]}""");
    }

    /** Non-itemised (header-only; previously persisted itemised rows are RETAINED as dormant rows). */
    private void saveNonItemisedDraft(long orderId, String finalIncTotal) throws Exception {
        saveDraft(orderId, "{\"itemised\": false, \"final_total_inc_gst\": " + finalIncTotal + ", \"lines\": []}");
    }

    /** Issue (or re-issue) via the protected send-email and return the plaintext token from the link. */
    private String sendAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        clearJpaCache();
        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        clearJpaCache();
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "exactly one quote email must have been recorded");
        return extractToken(sent.get(sent.size() - 1).bodyText());
    }

    private static String extractToken(String messageBody) {
        Matcher matcher = PUBLIC_LINK_TOKEN.matcher(messageBody);
        Assertions.assertTrue(matcher.find(), () -> "message body must contain the /q/{token} link: " + messageBody);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "the public link must appear exactly once in the body");
        return token;
    }

    private void cancelIssuedQuote(long orderId) throws Exception {
        clearJpaCache();
        mockMvc.perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    private static MockMultipartFile signaturePart(byte[] png) {
        return new MockMultipartFile("signature", "signature.png", "image/png", png);
    }

    /** The REAL public accept: token-only (NO session), exactly one PNG {@code signature} part. */
    private void acceptPublicly(String token, byte[] signaturePng) throws Exception {
        clearJpaCache();
        mockMvc.perform(multipart(publicAcceptUrl(token)).file(signaturePart(signaturePng)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"))
                .andExpect(jsonPath("$.message").value("Quote accepted."));
        clearJpaCache();
    }

    /** Ready order + itemised 250/275 draft → send v1 → publicly accept v1 with {@code signaturePng}. */
    private long acceptedItemisedOrder(byte[] signaturePng) throws Exception {
        long orderId = readyOrderWithItemisedDraft();
        acceptPublicly(sendAndExtractToken(orderId), signaturePng);
        Assertions.assertEquals("ACCEPTED", versionStatus(orderId, 1),
                "precondition: v1 accepted through the real public endpoint");
        return orderId;
    }

    /** Minimal UNSIGNED current invoice (v1) for the order — D.8 never reads the previous PDF bytes. */
    private void insertUnsignedInvoice(long orderId) {
        long storedFileId = jdbcTemplate.queryForObject(
                "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                        + "VALUES (?, ?, 'application/pdf', 1024) RETURNING stored_file_id",
                Long.class,
                "invoice-" + orderNumber(orderId) + "-v1.pdf",
                "/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/qards-seeded-invoice-v1.pdf");
        jdbcTemplate.update(
                "INSERT INTO invoice (order_id, version_number, invoice_date, due_date, "
                        + "details_of_sale_snapshot, sale_price_ex_gst, sale_price_inc_gst, total_paid, "
                        + "balance_due, stored_file_id, created_by_user_id) "
                        + "VALUES (?, 1, CURRENT_DATE, CURRENT_DATE, 'Supply and lay carpet', "
                        + "250.00, 275.00, 0.00, 275.00, ?, ?)",
                orderId, storedFileId, USER_LIAM);
        clearJpaCache();
    }

    /** The in-app invoice acceptance (D.8): signature + typed name, protected session. */
    private void acceptInvoiceInApp(long orderId) throws Exception {
        clearJpaCache();
        mockMvc.perform(multipart(invoiceAcceptUrl(orderId))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .param("accepted_customer_name", SNAPSHOT_NAME)
                        .session(liamStore1Session()))
                .andExpect(status().isCreated());
        clearJpaCache();
    }

    /** D.1 Create — the unsigned invoice v1 from the live order (empty body). */
    private void createInvoice(long orderId) throws Exception {
        clearJpaCache();
        mockMvc.perform(post(invoicesUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Invoice created."))
                .andExpect(jsonPath("$.data.invoice.version_number").value(1))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(false));
        clearJpaCache();
    }

    /** D.7 — record a CASH payment (regenerates the current invoice, carrying any acceptance forward). */
    private void recordCashPayment(long orderId, String amount) throws Exception {
        clearJpaCache();
        mockMvc.perform(post(paymentsUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"))
                .andExpect(status().isCreated());
        clearJpaCache();
    }

    /** D.2 manual Rewrite (empty body) — a new UNSIGNED current version from the live order. */
    private void rewriteInvoice(long orderId, int expectedVersion) throws Exception {
        clearJpaCache();
        mockMvc.perform(post(invoiceRewriteUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Invoice rewritten."))
                .andExpect(jsonPath("$.data.invoice.version_number").value(expectedVersion))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(false));
        clearJpaCache();
    }

    // ================================================================
    // Helpers — cross-tenant discovery (QuoteControllerTest pattern)
    // ================================================================

    private int storeInBusiness(long businessId, Integer excludeStore) {
        if (excludeStore == null) {
            return jdbcTemplate.queryForObject(
                    "SELECT store_id FROM store WHERE business_id = ? AND is_active = TRUE "
                            + "ORDER BY store_id LIMIT 1", Integer.class, businessId);
        }
        return jdbcTemplate.queryForObject(
                "SELECT store_id FROM store WHERE business_id = ? AND is_active = TRUE AND store_id <> ? "
                        + "ORDER BY store_id LIMIT 1", Integer.class, businessId, excludeStore);
    }

    private long userInBusiness(long businessId) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM app_user WHERE business_id = ? AND is_active = TRUE ORDER BY user_id LIMIT 1",
                Long.class, businessId);
    }

    private String businessSlug(long businessId) {
        return jdbcTemplate.queryForObject(
                "SELECT slug FROM business WHERE business_id = ?", String.class, businessId);
    }

    /** Give a user REAL access to a store (so the guard passes and only the order scope can miss). */
    private void grantStoreAccess(long businessId, long userId, int storeId) {
        jdbcTemplate.update(
                "INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id, store_id) DO NOTHING",
                businessId, userId, storeId);
    }

    // ================================================================
    // Helpers — DB probes + disk
    // ================================================================

    private Map<String, Object> versionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private String versionStatus(long orderId, int versionNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM quote_version WHERE order_id = ? AND version_number = ?",
                String.class, orderId, versionNumber);
    }

    private int versionCountByStatus(long orderId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ? AND status = ?",
                Integer.class, orderId, status);
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

    private Map<String, Object> storedFileRow(Object storedFileId) {
        Assertions.assertNotNull(storedFileId, "the stored_file reference must be set");
        return jdbcTemplate.queryForMap(
                "SELECT file_name, storage_path, mime_type, file_size FROM stored_file WHERE stored_file_id = ?",
                ((Number) storedFileId).longValue());
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private int invoiceCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE order_id = ?", Integer.class, orderId);
    }

    private Timestamp currentInvoiceAcceptedAt(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT accepted_at FROM invoice WHERE order_id = ? ORDER BY version_number DESC LIMIT 1",
                Timestamp.class, orderId);
    }

    /** The CURRENT (max version_number) invoice row. */
    private Map<String, Object> currentInvoiceRow(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT version_number, accepted_at, accepted_customer_name, accepted_signature_file_id, "
                        + "sale_price_inc_gst, total_paid, balance_due "
                        + "FROM invoice WHERE order_id = ? ORDER BY version_number DESC LIMIT 1",
                orderId);
    }

    private int signedInvoiceVersionCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE order_id = ? AND accepted_at IS NOT NULL", Integer.class, orderId);
    }

    private static long longValue(Map<String, Object> row, String column) {
        Object value = row.get(column);
        Assertions.assertNotNull(value, () -> column + " must not be null");
        return ((Number) value).longValue();
    }

    /** Physical path = the effective app.storage.base-dir + storage_path (FileStorageService.resolve). */
    private Path diskPath(String storagePath) {
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        return Path.of(storageBaseDir).toAbsolutePath().normalize().resolve(relative).normalize();
    }

    private byte[] diskBytes(Map<String, Object> storedFile) throws IOException {
        return Files.readAllBytes(diskPath((String) storedFile.get("storage_path")));
    }

    /**
     * The accepted version's STORED signature file, read from disk. Its {@code stored_file.file_size} is
     * the stored (server-normalised) byte length, never the upload's (spec §1).
     */
    private byte[] storedSignatureBytes(long orderId, int versionNumber) throws IOException {
        Map<String, Object> signatureFile =
                storedFileRow(versionRow(orderId, versionNumber).get("accepted_signature_file_id"));
        byte[] onDisk = diskBytes(signatureFile);
        Assertions.assertEquals(longValue(signatureFile, "file_size"), onDisk.length,
                () -> "v" + versionNumber + ": stored_file.file_size is the stored (normalised) byte length");
        return onDisk;
    }

    private static BufferedImage decodePng(byte[] png, String label) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        Assertions.assertNotNull(image, () -> label + ": not a decodable PNG");
        return image;
    }

    /**
     * Spec §1 SERVER NORMALISATION: the stored signature is the decoded upload RE-ENCODED as a clean PNG,
     * so it is never compared byte-for-byte with the upload. Instead: PNG magic, the SAME width/height and
     * the same pixels: alpha for every pixel, and RGB for every pixel that is not fully transparent (a
     * fully transparent pixel has no defined colour). This is the same rule as {@code QuoteAcceptanceControllerTest}.
     */
    private static void assertNormalisedSignatureOf(byte[] uploaded, byte[] stored, String label) throws IOException {
        Assertions.assertArrayEquals(PNG_MAGIC, Arrays.copyOf(stored, PNG_MAGIC.length),
                () -> label + ": the stored signature must start with the PNG magic");
        BufferedImage expected = decodePng(uploaded, label + " (upload)");
        BufferedImage actual = decodePng(stored, label + " (stored)");
        Assertions.assertEquals(expected.getWidth(), actual.getWidth(),
                () -> label + ": the stored width must match");
        Assertions.assertEquals(expected.getHeight(), actual.getHeight(),
                () -> label + ": the stored height must match");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y);
                int got = actual.getRGB(x, y);
                int wantAlpha = want >>> 24;
                boolean same = wantAlpha == (got >>> 24)
                        && (wantAlpha == 0 || (want & 0xFFFFFF) == (got & 0xFFFFFF));
                int px = x;
                int py = y;
                Assertions.assertTrue(same, () -> String.format(Locale.ROOT,
                        "%s: pixel (%d,%d) upload %08x but stored %08x", label, px, py, want, got));
            }
        }
    }

    /**
     * Spec §5: the signature endpoint streams the version's STORED signature file verbatim. That file is
     * the server-normalised re-encoding (NOT the upload), so the response must be byte-identical to the
     * file on disk, and the stored file must be the same IMAGE as {@code uploaded}. The two fixtures differ
     * in dimensions, so this also proves WHICH version's signature was streamed.
     */
    private void assertStreamsStoredSignature(MvcResult download, long orderId, int versionNumber,
                                              byte[] uploaded, String label) throws IOException {
        byte[] stored = storedSignatureBytes(orderId, versionNumber);
        Assertions.assertArrayEquals(stored, download.getResponse().getContentAsByteArray(),
                () -> label + ": the endpoint streams the stored v" + versionNumber + " signature file verbatim");
        assertNormalisedSignatureOf(uploaded, stored, label);
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String pdfTextWithoutWhitespace(byte[] pdfBytes) throws IOException {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", "");
        }
    }

    // ================================================================
    // Helpers — protected reads + JSON assertions
    // ================================================================

    private MvcResult performWorkspace(long orderId) throws Exception {
        clearJpaCache();
        return mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Quote workspace loaded."))
                .andReturn();
    }

    /** The workspace {@code data} object ({@code draft} / {@code current_issued} / {@code accepted}). */
    private JsonNode workspaceData(long orderId) throws Exception {
        return readJson(performWorkspace(orderId)).get("data");
    }

    /** {@code data.accepted.invoice_eligible} — asserted to be a JSON boolean. */
    private boolean invoiceEligible(long orderId) throws Exception {
        JsonNode eligible = workspaceData(orderId).get("accepted").get("invoice_eligible");
        Assertions.assertTrue(eligible != null && eligible.isBoolean(),
                () -> "invoice_eligible must be a JSON boolean but was " + eligible);
        return eligible.booleanValue();
    }

    private static JsonNode readJson(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult downloadAcceptedPdf(long orderId) throws Exception {
        clearJpaCache();
        return mockMvc.perform(get(pdfUrl(orderId, "accepted")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andReturn();
    }

    private MvcResult downloadIssuedPdf(long orderId) throws Exception {
        clearJpaCache();
        return mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andReturn();
    }

    private MvcResult downloadAcceptedSignature(long orderId) throws Exception {
        clearJpaCache();
        return mockMvc.perform(get(acceptedSignatureUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andReturn();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            names.add(property.getKey());
        }
        return names;
    }

    private static void assertAcceptedPresentAndNull(JsonNode workspaceData, String state) {
        Assertions.assertTrue(workspaceData.has("accepted"), () -> "the accepted key is always present (" + state + ")");
        Assertions.assertTrue(workspaceData.get("accepted").isNull(),
                () -> "accepted must be null when no version is ACCEPTED (" + state + "): " + workspaceData);
    }

    /** Walk the JSON: no server-internal reference may be KEYED anywhere under {@code path}. */
    private static void assertNoInternalKeys(JsonNode node, String path) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String key = property.getKey().toLowerCase(Locale.ROOT);
                for (String fragment : FORBIDDEN_KEY_FRAGMENTS) {
                    Assertions.assertFalse(key.contains(fragment),
                            () -> "internal key '" + property.getKey() + "' (contains '" + fragment
                                    + "') leaked at " + path);
                }
                assertNoInternalKeys(property.getValue(), path + "." + property.getKey());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertNoInternalKeys(node.get(i), path + "[" + i + "]");
            }
        }
    }

    private static void assertMoney(String expected, JsonNode node, String label) {
        Assertions.assertNotNull(node, () -> label + " must be present");
        Assertions.assertTrue(node.isNumber(), () -> label + " must be a JSON number but was " + node);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(node.decimalValue()),
                () -> label + ": expected " + expected + " but was " + node);
    }

    private static void assertItemLine(JsonNode line, String description, String quantity,
                                       String unitPriceExGst, String lineTotalExGst, int sortOrder) {
        Assertions.assertEquals(new TreeSet<>(LINE_KEYS), fieldNames(line), "QuoteIssuedLine keys");
        Assertions.assertEquals("ITEM", line.get("line_type").asText());
        Assertions.assertEquals(description, line.get("description").asText());
        assertMoney(quantity, line.get("quantity"), description + ".quantity");
        assertMoney(unitPriceExGst, line.get("unit_price_ex_gst"), description + ".unit_price_ex_gst");
        assertMoney(lineTotalExGst, line.get("line_total_ex_gst"), description + ".line_total_ex_gst");
        Assertions.assertEquals(sortOrder, line.get("sort_order").asInt(), description + ".sort_order");
    }

    private static void assertAdjustmentLine(JsonNode line, String description, String lineTotalExGst,
                                             int sortOrder) {
        Assertions.assertEquals(new TreeSet<>(LINE_KEYS), fieldNames(line), "QuoteIssuedLine keys");
        Assertions.assertEquals("ADJUSTMENT", line.get("line_type").asText());
        Assertions.assertEquals(description, line.get("description").asText());
        Assertions.assertTrue(line.get("quantity").isNull(), "an ADJUSTMENT line has a null quantity");
        Assertions.assertTrue(line.get("unit_price_ex_gst").isNull(), "an ADJUSTMENT line has a null unit price");
        assertMoney(lineTotalExGst, line.get("line_total_ex_gst"), "adjustment.line_total_ex_gst");
        Assertions.assertEquals(sortOrder, line.get("sort_order").asInt(), "adjustment.sort_order");
    }

    /** Content-Disposition is inline with EXACTLY the expected (backend-built) file name; no path leak. */
    private static void assertInlineFilename(MvcResult result, String expectedFileName) {
        String header = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        Assertions.assertNotNull(header, "Content-Disposition must be set");
        ContentDisposition disposition = ContentDisposition.parse(header);
        Assertions.assertTrue(disposition.isInline(), () -> "inline disposition expected: " + header);
        Assertions.assertEquals(expectedFileName, disposition.getFilename(), header);
        Assertions.assertFalse(header.contains("/uploads/"), () -> "storage path must never leak: " + header);
    }

    /** A scoped miss: 404 ORDER_NOT_FOUND, no data, the order never echoed (no existence leak). */
    private void assertOrderNotFound(RequestBuilder request, String orderNumber) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Order not found."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertFalse(body.contains(orderNumber), () -> "a scope miss must not echo the order: " + body);
    }

    // ================================================================
    // 1. workspace.accepted is null until a version is ACCEPTED
    // ================================================================

    /** accepted is a present-but-null key for draft only, issued only, superseded and cancelled states. */
    @Test
    void workspace_nothingAccepted_acceptedIsNull_forDraftOnlyIssuedSupersededAndCancelled() throws Exception {
        long orderId = readyOrderWithItemisedDraft();

        // Draft only.
        JsonNode draftOnly = workspaceData(orderId);
        Assertions.assertFalse(draftOnly.get("draft").isNull(), "the saved draft is returned");
        Assertions.assertTrue(draftOnly.get("current_issued").isNull());
        assertAcceptedPresentAndNull(draftOnly, "draft only");

        // Issued only: v1 ISSUED with an ACTIVE link.
        sendAndExtractToken(orderId);
        JsonNode issuedOnly = workspaceData(orderId);
        Assertions.assertEquals(1, issuedOnly.get("current_issued").get("version_number").asInt());
        Assertions.assertEquals("ISSUED", issuedOnly.get("current_issued").get("status").asText());
        assertAcceptedPresentAndNull(issuedOnly, "issued only");

        // A changed draft re-sent: v1 SUPERSEDED, v2 ISSUED — still nothing signed.
        saveNonItemisedDraft(orderId, "330.00");
        sendAndExtractToken(orderId);
        Assertions.assertEquals("SUPERSEDED", versionStatus(orderId, 1));
        JsonNode superseded = workspaceData(orderId);
        Assertions.assertEquals(2, superseded.get("current_issued").get("version_number").asInt());
        assertAcceptedPresentAndNull(superseded, "superseded + issued");

        // Cancelled: a dead, never-signed version is not 'accepted'.
        cancelIssuedQuote(orderId);
        Assertions.assertEquals("CANCELLED", versionStatus(orderId, 2));
        JsonNode cancelled = workspaceData(orderId);
        Assertions.assertTrue(cancelled.get("current_issued").isNull());
        assertAcceptedPresentAndNull(cancelled, "superseded + cancelled");
        Assertions.assertEquals(0, versionCountByStatus(orderId, "ACCEPTED"));
    }

    // ================================================================
    // 2. After acceptance: every key, frozen values, no internal keys
    // ================================================================

    /**
     * Every QuoteAcceptedSummary key is present with the FROZEN accepted-version values — even after the
     * live details, customer name and draft move on — and nothing internal is keyed under data.accepted.
     */
    @Test
    void workspace_afterAcceptance_everyKeyPresentWithFrozenValues_noInternalKeys() throws Exception {
        long orderId = readyOrder();
        setDetailsOfSale(orderId, FROZEN_DETAILS);
        saveItemisedDraftWithAdjustment(orderId);   // 240.00 ex / 264.00 inc, 3 snapshot lines
        String token = sendAndExtractToken(orderId);
        String tokenHash = sha256Hex(token);
        acceptPublicly(token, ONE_PIXEL_PNG);
        Map<String, Object> v1 = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", v1.get("status"));
        Assertions.assertEquals(3, versionLineCount(orderId), "the itemised issue snapshotted 3 lines");

        // LIVE state moves on after the signature — none of it may reach the accepted summary.
        setDetailsOfSale(orderId, "Live details edited after acceptance");
        jdbcTemplate.update("UPDATE order_customer SET first_name = 'Renamed' WHERE order_id = ?", orderId);
        saveItemisedTwoLineDraft(orderId);          // live draft 250.00 ex / 275.00 inc

        MvcResult result = performWorkspace(orderId);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode data = JSON.readTree(body).get("data");
        JsonNode accepted = data.get("accepted");
        Assertions.assertTrue(accepted.isObject(), () -> "accepted must be populated: " + data);

        // Every key, always present (nullable ones would be JSON null — none are null here).
        Assertions.assertEquals(new TreeSet<>(ACCEPTED_KEYS), fieldNames(accepted));

        Assertions.assertEquals(longValue(v1, "quote_version_id"), accepted.get("quote_version_id").asLong());
        Assertions.assertEquals(1, accepted.get("version_number").asInt());
        assertMoney("240.00", accepted.get("quote_total_ex_gst"), "quote_total_ex_gst");
        assertMoney("264.00", accepted.get("quote_total_inc_gst"), "quote_total_inc_gst");
        Assertions.assertTrue(accepted.get("itemised").isBoolean() && accepted.get("itemised").booleanValue());
        Assertions.assertEquals("SOFT", accepted.get("flooring_type").asText());
        Assertions.assertEquals(FROZEN_DETAILS, accepted.get("details_of_sale").asText(),
                "details_of_sale is the frozen issue snapshot, never the live order text");

        // Lines: the frozen snapshot in (sort_order, PK) order — not the submission order.
        JsonNode lines = accepted.get("lines");
        Assertions.assertTrue(lines.isArray());
        Assertions.assertEquals(3, lines.size());
        assertItemLine(lines.get(0), "Carpet", "2.00", "100.00", "200.00", 0);
        assertItemLine(lines.get(1), "Underlay", "1.00", "50.00", "50.00", 1);
        assertAdjustmentLine(lines.get(2), "Adjustment", "-10.00", 2);

        LocalDateTime acceptedAtDb = ((Timestamp) v1.get("accepted_at")).toLocalDateTime();
        Assertions.assertEquals(acceptedAtDb, LocalDateTime.parse(accepted.get("accepted_at").asText()));
        Assertions.assertEquals(SNAPSHOT_NAME, accepted.get("accepted_customer_name").asText(),
                "the accepted name is the V17 issue snapshot, never the live (renamed) customer");
        Assertions.assertEquals(SNAPSHOT_NAME, v1.get("accepted_customer_name"));
        Assertions.assertTrue(accepted.get("accepted_signature_present").booleanValue());
        Assertions.assertEquals(
                "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/accepted/signature",
                accepted.get("signature_download_path").asText());
        Assertions.assertTrue(accepted.get("signed_pdf_available").booleanValue());
        Assertions.assertTrue(accepted.get("invoice_eligible").booleanValue(), "no invoice exists yet");

        // The live draft is independent of the accepted snapshot; v1 is no longer ISSUED, so no
        // version is active-issued.
        assertMoney("275.00", data.get("draft").get("quote_total_inc_gst"), "draft.quote_total_inc_gst");
        Assertions.assertTrue(data.get("current_issued").isNull());

        // Leak sweep: no internal KEY under data.accepted; no internal VALUE anywhere in the body.
        assertNoInternalKeys(accepted, "data.accepted");
        Assertions.assertFalse(accepted.toString().contains("/uploads/"), "no storage path value");
        Assertions.assertFalse(body.contains(token), "the plaintext token never appears");
        Assertions.assertFalse(body.contains(tokenHash), "the token hash never appears");
        Assertions.assertFalse(body.contains("/uploads/"), "no storage path anywhere");
        Assertions.assertFalse(body.contains("storage_path"));
        Assertions.assertFalse(body.contains("accepted_signature_file_id"));
        Assertions.assertFalse(body.contains("signed_pdf_file_id"));

        // The backend-built path is consumed VERBATIM and streams the STORED (server-normalised)
        // signature: the v1 file on disk, i.e. the same image as the uploaded PNG.
        MvcResult signature = mockMvc.perform(get(accepted.get("signature_download_path").asText())
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andReturn();
        assertStreamsStoredSignature(signature, orderId, 1, ONE_PIXEL_PNG, "signature_download_path");
    }

    // ================================================================
    // 3. Non-itemised acceptance → lines == []
    // ================================================================

    /** A non-itemised accepted version reports lines == [] even while dormant itemised draft rows exist. */
    @Test
    void workspace_nonItemisedAcceptance_linesAlwaysEmpty_dormantDraftRowsIgnored() throws Exception {
        long orderId = readyOrder();
        saveItemisedTwoLineDraft(orderId);              // persisted itemised rows ...
        saveNonItemisedDraft(orderId, "550.00");        // ... retained as DORMANT rows (header-only save)
        Assertions.assertEquals(2, draftLineCount(orderId), "precondition: dormant draft rows retained");
        String token = sendAndExtractToken(orderId);
        Assertions.assertEquals(0, versionLineCount(orderId), "a non-itemised issue snapshots zero lines");
        acceptPublicly(token, ONE_PIXEL_PNG);

        JsonNode data = workspaceData(orderId);
        JsonNode accepted = data.get("accepted");
        Assertions.assertEquals(new TreeSet<>(ACCEPTED_KEYS), fieldNames(accepted));
        Assertions.assertEquals(1, accepted.get("version_number").asInt());
        Assertions.assertFalse(accepted.get("itemised").booleanValue());
        Assertions.assertTrue(accepted.get("lines").isArray(), "lines is always an array");
        Assertions.assertEquals(0, accepted.get("lines").size(), "non-itemised accepted lines are always []");
        assertMoney("500.00", accepted.get("quote_total_ex_gst"), "quote_total_ex_gst");
        assertMoney("550.00", accepted.get("quote_total_inc_gst"), "quote_total_inc_gst");
        Assertions.assertTrue(accepted.get("details_of_sale").isNull(),
                "details were never saved: the frozen null serializes as JSON null (key still present)");
        Assertions.assertTrue(accepted.get("signed_pdf_available").booleanValue());
        assertNoInternalKeys(accepted, "data.accepted");

        // The dormant rows stay draft-workspace state only.
        Assertions.assertFalse(data.get("draft").get("itemised").booleanValue());
        Assertions.assertEquals(2, data.get("draft").get("lines").size());
    }

    // ================================================================
    // 4. Latest of multiple accepted versions
    // ================================================================

    /** With v1 and v2 both ACCEPTED, the workspace surfaces v2 (max version_number); v1 stays ACCEPTED. */
    @Test
    void workspace_twoAcceptedVersions_surfacesLatest_olderStaysAccepted() throws Exception {
        long orderId = acceptedItemisedOrder(ONE_PIXEL_PNG);        // v1: 250.00 / 275.00
        Map<String, Object> v1Before = versionRow(orderId, 1);

        saveItemisedThreeLineDraft(orderId);                         // 400.00 / 440.00
        String token2 = sendAndExtractToken(orderId);
        Assertions.assertEquals("ISSUED", versionStatus(orderId, 2), "sending after an acceptance issues v2");
        acceptPublicly(token2, SECOND_SIGNATURE_PNG);
        Map<String, Object> v2 = versionRow(orderId, 2);

        JsonNode data = workspaceData(orderId);
        JsonNode accepted = data.get("accepted");
        Assertions.assertEquals(new TreeSet<>(ACCEPTED_KEYS), fieldNames(accepted));
        Assertions.assertEquals(2, accepted.get("version_number").asInt());
        Assertions.assertEquals(longValue(v2, "quote_version_id"), accepted.get("quote_version_id").asLong());
        assertMoney("400.00", accepted.get("quote_total_ex_gst"), "quote_total_ex_gst");
        assertMoney("440.00", accepted.get("quote_total_inc_gst"), "quote_total_inc_gst");
        JsonNode lines = accepted.get("lines");
        Assertions.assertEquals(3, lines.size());
        assertItemLine(lines.get(0), "Carpet", "3.00", "100.00", "300.00", 0);
        assertItemLine(lines.get(1), "Underlay", "1.00", "50.00", "50.00", 1);
        assertItemLine(lines.get(2), "Labour", "2.00", "25.00", "50.00", 2);
        Assertions.assertEquals(((Timestamp) v2.get("accepted_at")).toLocalDateTime(),
                LocalDateTime.parse(accepted.get("accepted_at").asText()));
        Assertions.assertEquals(SNAPSHOT_NAME, accepted.get("accepted_customer_name").asText());
        Assertions.assertTrue(accepted.get("accepted_signature_present").booleanValue());
        Assertions.assertTrue(accepted.get("signed_pdf_available").booleanValue());
        Assertions.assertTrue(data.get("current_issued").isNull(), "no ISSUED version remains");

        // v1 stays signed history, untouched.
        Map<String, Object> v1After = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", v1After.get("status"));
        Assertions.assertEquals(v1Before.get("accepted_at"), v1After.get("accepted_at"));
        Assertions.assertEquals(v1Before.get("accepted_customer_name"), v1After.get("accepted_customer_name"));
        Assertions.assertEquals(v1Before.get("accepted_signature_file_id"), v1After.get("accepted_signature_file_id"));
        Assertions.assertEquals(v1Before.get("signed_pdf_file_id"), v1After.get("signed_pdf_file_id"));
        Assertions.assertNotEquals(longValue(v1After, "signed_pdf_file_id"), longValue(v2, "signed_pdf_file_id"));
        Assertions.assertEquals(2, versionCountByStatus(orderId, "ACCEPTED"));
    }

    // ================================================================
    // 5. Coexistence: draft + current_issued (v2) + accepted (v1)
    // ================================================================

    /** After accepting v1, a changed draft sent as v2 (ISSUED) coexists with accepted v1 in one workspace. */
    @Test
    void workspace_acceptedCoexistsWithNewerDraftAndIssuedVersion() throws Exception {
        long orderId = acceptedItemisedOrder(ONE_PIXEL_PNG);        // v1 ACCEPTED: 250.00 / 275.00
        String orderNumber = orderNumber(orderId);
        saveNonItemisedDraft(orderId, "330.00");                     // unsigned live draft: 300.00 / 330.00
        sendAndExtractToken(orderId);                                // v2 ISSUED, not accepted
        Assertions.assertEquals("ACCEPTED", versionStatus(orderId, 1));
        Assertions.assertEquals("ISSUED", versionStatus(orderId, 2));

        JsonNode data = workspaceData(orderId);
        JsonNode draft = data.get("draft");
        JsonNode issued = data.get("current_issued");
        JsonNode accepted = data.get("accepted");
        Assertions.assertTrue(draft.isObject() && issued.isObject() && accepted.isObject(),
                () -> "draft, current_issued and accepted must all be populated: " + data);

        Assertions.assertFalse(draft.get("itemised").booleanValue());
        assertMoney("330.00", draft.get("quote_total_inc_gst"), "draft.quote_total_inc_gst");

        Assertions.assertEquals(2, issued.get("version_number").asInt());
        Assertions.assertEquals("ISSUED", issued.get("status").asText());
        assertMoney("330.00", issued.get("quote_total_inc_gst"), "current_issued.quote_total_inc_gst");

        Assertions.assertEquals(1, accepted.get("version_number").asInt());
        Assertions.assertTrue(accepted.get("itemised").booleanValue());
        assertMoney("275.00", accepted.get("quote_total_inc_gst"), "accepted.quote_total_inc_gst");
        Assertions.assertEquals(2, accepted.get("lines").size());
        Assertions.assertTrue(accepted.get("invoice_eligible").booleanValue(),
                "a newer unsigned issued quote never makes the accepted quote ineligible");

        // The downloads stay type-specific: issued → the v2 issued artifact; accepted → v1's signed PDF.
        MvcResult issuedPdf = downloadIssuedPdf(orderId);
        Assertions.assertArrayEquals(diskBytes(storedFileRow(versionRow(orderId, 2).get("issued_pdf_file_id"))),
                issuedPdf.getResponse().getContentAsByteArray(), "type=issued streams the ACTIVE issued v2 PDF");
        assertInlineFilename(issuedPdf, "quote-" + orderNumber + "-v2.pdf");

        MvcResult acceptedPdf = downloadAcceptedPdf(orderId);
        Assertions.assertArrayEquals(diskBytes(storedFileRow(versionRow(orderId, 1).get("signed_pdf_file_id"))),
                acceptedPdf.getResponse().getContentAsByteArray(), "type=accepted streams v1's signed PDF");
        assertInlineFilename(acceptedPdf, "quote-" + orderNumber + "-v1-signed.pdf");

        MvcResult signature = downloadAcceptedSignature(orderId);
        assertStreamsStoredSignature(signature, orderId, 1, ONE_PIXEL_PNG, "accepted v1 signature");
        assertInlineFilename(signature, "quote-signature-" + orderNumber + "-v1.png");
    }

    // ================================================================
    // 6. invoice_eligible
    // ================================================================

    /** invoice_eligible: true with no invoice, true with an unsigned current invoice, false after D.8. */
    @Test
    void workspace_invoiceEligible_noInvoice_unsignedInvoice_thenFalseAfterInAppInvoiceAcceptance() throws Exception {
        long orderId = acceptedItemisedOrder(ONE_PIXEL_PNG);

        Assertions.assertEquals(0, invoiceCount(orderId));
        Assertions.assertTrue(workspaceData(orderId).get("accepted").get("invoice_eligible").booleanValue(),
                "no invoice → eligible");

        insertUnsignedInvoice(orderId);
        Assertions.assertNull(currentInvoiceAcceptedAt(orderId), "precondition: the current invoice is unsigned");
        Assertions.assertTrue(workspaceData(orderId).get("accepted").get("invoice_eligible").booleanValue(),
                "an UNSIGNED current invoice keeps the accepted quote eligible");

        // The in-app invoice acceptance (D.8) signs the CURRENT invoice (appends an accepted version).
        acceptInvoiceInApp(orderId);
        Assertions.assertNotNull(currentInvoiceAcceptedAt(orderId), "precondition: the current invoice is accepted");

        JsonNode accepted = workspaceData(orderId).get("accepted");
        Assertions.assertFalse(accepted.get("invoice_eligible").booleanValue(),
                "an ACCEPTED current invoice makes the accepted quote ineligible");
        // D.8 never touches the accepted quote itself.
        Assertions.assertEquals(1, accepted.get("version_number").asInt());
        Assertions.assertEquals(SNAPSHOT_NAME, accepted.get("accepted_customer_name").asText());
        Assertions.assertEquals("ACCEPTED", versionStatus(orderId, 1));
    }

    /**
     * invoice_eligible follows the CURRENT invoice (max version_number) only. On an invoice-ready order:
     * no invoice → true; D.1 Create (unsigned v1) → true; D.8 in-app acceptance (signed v2) → false;
     * (a) a payment appends v3 with the acceptance CARRIED FORWARD → still false; (b) a manual Rewrite
     * appends an UNSIGNED v4 (rewrite clears acceptance) → true again, although the signed v2/v3 remain
     * in the invoice history. None of it touches the accepted quote.
     */
    @Test
    void workspace_invoiceEligible_followsCurrentInvoiceOnly_paymentKeepsFalse_rewriteMakesTrueAgain()
            throws Exception {
        long orderId = invoiceReadyOrder();
        saveItemisedTwoLineDraft(orderId);                           // 250.00 ex / 275.00 inc
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);   // v1 ACCEPTED; D6b working price 275.00
        Map<String, Object> acceptedQuoteRow = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", acceptedQuoteRow.get("status"));
        Assertions.assertEquals(0, invoiceCount(orderId));
        Assertions.assertTrue(invoiceEligible(orderId), "no invoice → eligible");

        // D.1 Create: the unsigned current invoice keeps the accepted quote eligible.
        createInvoice(orderId);
        Assertions.assertNull(currentInvoiceAcceptedAt(orderId), "precondition: v1 is unsigned");
        Assertions.assertTrue(invoiceEligible(orderId), "an UNSIGNED current invoice → eligible");

        // D.8: the in-app signature appends the signed current v2 → ineligible.
        acceptInvoiceInApp(orderId);
        Map<String, Object> signed = currentInvoiceRow(orderId);
        Assertions.assertEquals(2, ((Number) signed.get("version_number")).intValue());
        Assertions.assertNotNull(signed.get("accepted_at"), "precondition: the current v2 is signed");
        Assertions.assertFalse(invoiceEligible(orderId), "an ACCEPTED current invoice → ineligible");

        // (a) A payment appends v3 carrying the acceptance forward (the customer does not re-sign).
        recordCashPayment(orderId, "50.00");
        Map<String, Object> paid = currentInvoiceRow(orderId);
        Assertions.assertEquals(3, ((Number) paid.get("version_number")).intValue(), "the payment appends v3");
        Assertions.assertEquals(signed.get("accepted_at"), paid.get("accepted_at"), "acceptance carried forward");
        Assertions.assertEquals(signed.get("accepted_customer_name"), paid.get("accepted_customer_name"));
        Assertions.assertEquals(signed.get("accepted_signature_file_id"), paid.get("accepted_signature_file_id"));
        Assertions.assertEquals(0, new BigDecimal("50.00").compareTo((BigDecimal) paid.get("total_paid")));
        Assertions.assertFalse(invoiceEligible(orderId),
                "the payment version still carries the acceptance → still ineligible");

        // (b) A manual Rewrite appends an UNSIGNED v4 from the live order → eligible again.
        rewriteInvoice(orderId, 4);
        Map<String, Object> rewritten = currentInvoiceRow(orderId);
        Assertions.assertEquals(4, ((Number) rewritten.get("version_number")).intValue());
        Assertions.assertNull(rewritten.get("accepted_at"), "Rewrite clears acceptance on the new version");
        Assertions.assertNull(rewritten.get("accepted_customer_name"));
        Assertions.assertNull(rewritten.get("accepted_signature_file_id"));
        Assertions.assertEquals(2, signedInvoiceVersionCount(orderId),
                "the signed v2 + v3 stay in the invoice history");
        Assertions.assertTrue(invoiceEligible(orderId),
                "invoice_eligible follows the CURRENT (unsigned, rewritten) invoice only");

        // The invoice flow never touches the accepted quote.
        Assertions.assertEquals(acceptedQuoteRow, versionRow(orderId, 1), "the accepted quote row is unchanged");
        JsonNode accepted = workspaceData(orderId).get("accepted");
        Assertions.assertEquals(1, accepted.get("version_number").asInt());
        Assertions.assertEquals(SNAPSHOT_NAME, accepted.get("accepted_customer_name").asText());
    }

    // ================================================================
    // 7. GET .../quote/pdf?type=accepted
    // ================================================================

    /** No accepted version → 404 QUOTE_PDF_NOT_FOUND (draft-only, issued-only); type=issued still streams. */
    @Test
    void acceptedPdf_nothingAccepted_returns404QuotePdfNotFound_issuedTypeUnchanged() throws Exception {
        long draftOnlyOrder = readyOrderWithItemisedDraft();
        long issuedOrder = readyOrderWithItemisedDraft();
        sendAndExtractToken(issuedOrder);

        // type=issued is unchanged: the ACTIVE issued version's stored PDF, verbatim.
        MvcResult issuedPdf = downloadIssuedPdf(issuedOrder);
        Assertions.assertArrayEquals(
                diskBytes(storedFileRow(versionRow(issuedOrder, 1).get("issued_pdf_file_id"))),
                issuedPdf.getResponse().getContentAsByteArray());
        assertInlineFilename(issuedPdf, "quote-" + orderNumber(issuedOrder) + "-v1.pdf");

        for (long orderId : new long[] {draftOnlyOrder, issuedOrder}) {
            clearJpaCache();
            mockMvc.perform(get(pdfUrl(orderId, "accepted")).session(liamStore1Session()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUOTE_PDF_NOT_FOUND"))
                    .andExpect(jsonPath("$.error.message").value("The requested quote PDF is not available."));
        }
    }

    /**
     * type=accepted streams the stored SIGNED PDF verbatim (bytes == the file on disk), inline as
     * quote-{order}-v1-signed.pdf; after the acceptance type=issued has no ACTIVE issued version → 404.
     */
    @Test
    void acceptedPdf_afterAcceptance_streamsStoredSignedPdfVerbatimInline() throws Exception {
        long orderId = acceptedItemisedOrder(ONE_PIXEL_PNG);
        String orderNumber = orderNumber(orderId);
        Map<String, Object> v1 = versionRow(orderId, 1);

        Map<String, Object> signedFile = storedFileRow(v1.get("signed_pdf_file_id"));
        Assertions.assertEquals("quote-" + orderNumber + "-v1-signed.pdf", signedFile.get("file_name"));
        Assertions.assertEquals("application/pdf", signedFile.get("mime_type"));
        String signedPath = (String) signedFile.get("storage_path");
        Assertions.assertTrue(signedPath.startsWith("/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/"),
                signedPath);
        byte[] onDisk = diskBytes(signedFile);
        Assertions.assertEquals(longValue(signedFile, "file_size"), onDisk.length);

        MvcResult result = downloadAcceptedPdf(orderId);
        byte[] streamed = result.getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(onDisk, streamed, "the stored signed PDF is streamed verbatim");
        Assertions.assertEquals(streamed.length, result.getResponse().getContentLength());
        Assertions.assertEquals("%PDF-", new String(streamed, 0, 5, StandardCharsets.US_ASCII));
        assertInlineFilename(result, "quote-" + orderNumber + "-v1-signed.pdf");

        // It is the SIGNED artifact — not the (still stored) issued PDF.
        Map<String, Object> issuedFile = storedFileRow(v1.get("issued_pdf_file_id"));
        Assertions.assertTrue(Files.exists(diskPath((String) issuedFile.get("storage_path"))),
                "the issued PDF stays on disk after the acceptance");
        Assertions.assertFalse(Arrays.equals(diskBytes(issuedFile), streamed), "signed != issued artifact");
        Assertions.assertTrue(pdfTextWithoutWhitespace(streamed).contains("Acceptedby" + SNAPSHOT_NAME.replace(" ", "")),
                "the signed PDF carries the acceptance caption");

        // type=issued keeps its meaning (the ACTIVE issued version only) — v1 is ACCEPTED now.
        clearJpaCache();
        mockMvc.perform(get(pdfUrl(orderId, "issued")).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_PDF_NOT_FOUND"));
    }

    // ================================================================
    // 7 + 8. A newer acceptance moves both downloads to the newer version
    // ================================================================

    /** After v2 is accepted, type=accepted streams v2's signed PDF and the signature endpoint v2's PNG. */
    @Test
    void acceptedDownloads_afterNewerAcceptance_streamNewerVersionPdfAndSignature() throws Exception {
        long orderId = acceptedItemisedOrder(ONE_PIXEL_PNG);
        String orderNumber = orderNumber(orderId);
        byte[] v1Signed = downloadAcceptedPdf(orderId).getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(diskBytes(storedFileRow(versionRow(orderId, 1).get("signed_pdf_file_id"))),
                v1Signed);
        MvcResult v1SignatureDownload = downloadAcceptedSignature(orderId);
        assertStreamsStoredSignature(v1SignatureDownload, orderId, 1, ONE_PIXEL_PNG, "v1 signature before v2");
        byte[] v1Signature = v1SignatureDownload.getResponse().getContentAsByteArray();

        saveItemisedThreeLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), SECOND_SIGNATURE_PNG);
        Assertions.assertEquals("ACCEPTED", versionStatus(orderId, 2));

        MvcResult pdf = downloadAcceptedPdf(orderId);
        byte[] v2Streamed = pdf.getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(diskBytes(storedFileRow(versionRow(orderId, 2).get("signed_pdf_file_id"))),
                v2Streamed, "the LATEST accepted version's signed PDF");
        Assertions.assertFalse(Arrays.equals(v1Signed, v2Streamed));
        assertInlineFilename(pdf, "quote-" + orderNumber + "-v2-signed.pdf");

        MvcResult signature = downloadAcceptedSignature(orderId);
        assertStreamsStoredSignature(signature, orderId, 2, SECOND_SIGNATURE_PNG,
                "the LATEST accepted version's signature");
        Assertions.assertFalse(Arrays.equals(v1Signature, signature.getResponse().getContentAsByteArray()),
                "the v1 signature is no longer the one streamed");
        // v1 stays signed history: its stored signature file is untouched by the newer acceptance.
        Assertions.assertArrayEquals(v1Signature, storedSignatureBytes(orderId, 1));
        assertInlineFilename(signature, "quote-signature-" + orderNumber + "-v2.png");
    }

    // ================================================================
    // 8. GET .../quote/accepted/signature
    // ================================================================

    /** No accepted version (bare / draft-only / issued-only / cancelled) → 404 QUOTE_SIGNATURE_NOT_FOUND. */
    @Test
    void acceptedSignature_nothingAccepted_returns404QuoteSignatureNotFound() throws Exception {
        long bareOrder = insertOrder("LEAD");
        long draftOnlyOrder = readyOrderWithItemisedDraft();
        long issuedOrder = readyOrderWithItemisedDraft();
        sendAndExtractToken(issuedOrder);
        long cancelledOrder = readyOrderWithItemisedDraft();
        sendAndExtractToken(cancelledOrder);
        cancelIssuedQuote(cancelledOrder);

        for (long orderId : new long[] {bareOrder, draftOnlyOrder, issuedOrder, cancelledOrder}) {
            clearJpaCache();
            mockMvc.perform(get(acceptedSignatureUrl(orderId)).session(liamStore1Session()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUOTE_SIGNATURE_NOT_FOUND"))
                    .andExpect(jsonPath("$.error.message")
                            .value("No accepted quote signature is available for this order."));
        }
    }

    /**
     * The signature streams the STORED, server-normalised PNG inline as quote-signature-{order}-v1.png. The
     * response is byte-identical to the file on disk, which is the re-encoding of the upload (same image),
     * never the raw upload (spec §1 / §5). Once the referenced file is gone from disk the read is a generic
     * 500 JSON envelope (no path leak).
     */
    @Test
    void acceptedSignature_afterAcceptance_streamsStoredNormalisedPngInline_missingFileIs500() throws Exception {
        long orderId = acceptedItemisedOrder(SECOND_SIGNATURE_PNG);
        String orderNumber = orderNumber(orderId);

        Map<String, Object> signatureFile = storedFileRow(versionRow(orderId, 1).get("accepted_signature_file_id"));
        Assertions.assertEquals("quote-signature-" + orderNumber + "-v1.png", signatureFile.get("file_name"));
        Assertions.assertEquals("image/png", signatureFile.get("mime_type"));
        String storagePath = (String) signatureFile.get("storage_path");
        Assertions.assertTrue(storagePath.startsWith("/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/"),
                storagePath);
        Path onDisk = diskPath(storagePath);
        byte[] stored = Files.readAllBytes(onDisk);
        Assertions.assertEquals(longValue(signatureFile, "file_size"), stored.length,
                "stored_file.file_size is the stored (normalised) byte length, not the upload's");
        assertNormalisedSignatureOf(SECOND_SIGNATURE_PNG, stored, "stored signature");
        // The 3x2 RGB upload (PNG colour type 2) is re-encoded from an ARGB raster, so the stored bytes can
        // never be the raw upload.
        Assertions.assertFalse(Arrays.equals(SECOND_SIGNATURE_PNG, stored),
                "the stored signature is the server-normalised re-encoding, never the raw upload bytes");

        MvcResult result = downloadAcceptedSignature(orderId);
        byte[] streamed = result.getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(stored, streamed, "the streamed bytes are the stored file, verbatim");
        Assertions.assertEquals(streamed.length, result.getResponse().getContentLength());
        assertInlineFilename(result, "quote-signature-" + orderNumber + "-v1.png");

        // The referenced file disappears from disk → generic 500 through the standard JSON wrapper.
        Files.delete(onDisk);
        clearJpaCache();
        MvcResult failure = mockMvc.perform(get(acceptedSignatureUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."))
                .andReturn();
        String failureBody = failure.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertFalse(failureBody.contains("/uploads/"), () -> "no storage path leak: " + failureBody);
        Assertions.assertFalse(failureBody.contains("storage_path"), () -> "no storage path leak: " + failureBody);
    }

    /**
     * Defensive branches: an accepted version whose signature / signed-PDF references are NULL keeps
     * every key (signature_present false, download path null, signed_pdf_available false) and both
     * downloads 404 with their own codes.
     */
    @Test
    void acceptedReads_nullStoredReferences_report404_andWorkspaceReportsAbsence() throws Exception {
        long orderId = acceptedItemisedOrder(ONE_PIXEL_PNG);
        jdbcTemplate.update(
                "UPDATE quote_version SET accepted_signature_file_id = NULL, signed_pdf_file_id = NULL "
                        + "WHERE order_id = ? AND version_number = 1", orderId);
        clearJpaCache();

        JsonNode accepted = workspaceData(orderId).get("accepted");
        Assertions.assertEquals(new TreeSet<>(ACCEPTED_KEYS), fieldNames(accepted), "keys stay present");
        Assertions.assertFalse(accepted.get("accepted_signature_present").booleanValue());
        Assertions.assertTrue(accepted.get("signature_download_path").isNull(),
                "no signature → no download path (JSON null)");
        Assertions.assertFalse(accepted.get("signed_pdf_available").booleanValue());
        Assertions.assertEquals(SNAPSHOT_NAME, accepted.get("accepted_customer_name").asText());

        clearJpaCache();
        mockMvc.perform(get(acceptedSignatureUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_SIGNATURE_NOT_FOUND"));
        clearJpaCache();
        mockMvc.perform(get(pdfUrl(orderId, "accepted")).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_PDF_NOT_FOUND"));
    }

    // ================================================================
    // 9. LAID — reads stay allowed
    // ================================================================

    /** A LAID order still serves the workspace, the signed PDF and the signature (200). */
    @Test
    void acceptedReads_laidOrder_allReturn200() throws Exception {
        long orderId = acceptedItemisedOrder(ONE_PIXEL_PNG);
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
        clearJpaCache();
        Assertions.assertEquals("LAID", jdbcTemplate.queryForObject(
                "SELECT order_status::text FROM sales_order WHERE order_id = ?", String.class, orderId));

        JsonNode accepted = workspaceData(orderId).get("accepted");
        Assertions.assertEquals(1, accepted.get("version_number").asInt());
        Assertions.assertTrue(accepted.get("signed_pdf_available").booleanValue());

        Assertions.assertArrayEquals(diskBytes(storedFileRow(versionRow(orderId, 1).get("signed_pdf_file_id"))),
                downloadAcceptedPdf(orderId).getResponse().getContentAsByteArray());
        assertStreamsStoredSignature(downloadAcceptedSignature(orderId), orderId, 1, ONE_PIXEL_PNG,
                "LAID accepted signature");
    }

    // ================================================================
    // 10. Isolation — another store / another business never sees the order
    // ================================================================

    /**
     * The three reads 404 ORDER_NOT_FOUND for a same-business session on ANOTHER store (with real access
     * to it) and for a business-2 session on its own slug — byte-identical to a missing order.
     */
    @Test
    void acceptedReads_otherStoreAndOtherBusiness_return404OrderNotFound() throws Exception {
        long orderId = acceptedItemisedOrder(ONE_PIXEL_PNG);
        String orderNumber = orderNumber(orderId);

        // Sanity: in scope (Liam / business 1 / store 1) all three reads succeed.
        Assertions.assertTrue(workspaceData(orderId).get("accepted").isObject());
        downloadAcceptedPdf(orderId);
        downloadAcceptedSignature(orderId);

        // Same business, ANOTHER store the session user genuinely has access to (guard passes).
        int otherStore = storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD);
        grantStoreAccess(BUSINESS_AUSSIE, USER_LIAM, otherStore);
        MockHttpSession otherStoreSession = session(USER_LIAM, BUSINESS_AUSSIE, otherStore);
        assertOrderNotFound(get(workspaceUrl(orderId)).session(otherStoreSession), orderNumber);
        assertOrderNotFound(get(pdfUrl(orderId, "accepted")).session(otherStoreSession), orderNumber);
        assertOrderNotFound(get(acceptedSignatureUrl(orderId)).session(otherStoreSession), orderNumber);

        // No existence leak: the cross-store miss is byte-identical to a missing order's 404.
        clearJpaCache();
        String crossStoreBody = mockMvc.perform(get(acceptedSignatureUrl(orderId)).session(otherStoreSession))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        clearJpaCache();
        String missingOrderBody = mockMvc.perform(get(acceptedSignatureUrl(9_999_999_999L)).session(otherStoreSession))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertEquals(missingOrderBody, crossStoreBody);

        // Another business, through ITS OWN slug (guard passes; the business-scoped order lookup misses).
        String otherSlug = businessSlug(BUSINESS_PREMIER);
        int otherBusinessStore = storeInBusiness(BUSINESS_PREMIER, null);
        long otherBusinessUser = userInBusiness(BUSINESS_PREMIER);
        grantStoreAccess(BUSINESS_PREMIER, otherBusinessUser, otherBusinessStore);
        MockHttpSession otherBusinessSession = session(otherBusinessUser, BUSINESS_PREMIER, otherBusinessStore);
        assertOrderNotFound(get(workspaceUrl(otherSlug, orderId)).session(otherBusinessSession), orderNumber);
        assertOrderNotFound(get(pdfUrl(otherSlug, orderId, "accepted")).session(otherBusinessSession), orderNumber);
        assertOrderNotFound(get(acceptedSignatureUrl(otherSlug, orderId)).session(otherBusinessSession), orderNumber);

        // ... and through THIS tenant's slug the tenant guard 404s first (never confirms anything).
        clearJpaCache();
        mockMvc.perform(get(acceptedSignatureUrl(orderId)).session(otherBusinessSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ================================================================
    // 11. Standard protected gates on the new signature endpoint
    // ================================================================

    /** No session → 401; no store → 403; non-numeric id → 400 (order_id); unknown order → 404. */
    @Test
    void acceptedSignature_noSession401_noStore403_badOrderId400_missingOrder404() throws Exception {
        long orderId = acceptedItemisedOrder(ONE_PIXEL_PNG);

        clearJpaCache();
        mockMvc.perform(get(acceptedSignatureUrl(orderId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        // Same gate as the sibling protected quote reads.
        mockMvc.perform(get(pdfUrl(orderId, "accepted")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(workspaceUrl(orderId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(get(acceptedSignatureUrl(orderId)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(get(acceptedSignatureUrl("not-a-number")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));

        mockMvc.perform(get(acceptedSignatureUrl(9_999_999_999L)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }
}
