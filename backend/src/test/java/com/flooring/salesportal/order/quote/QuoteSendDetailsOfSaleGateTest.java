package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Batch A PR 2 (#134) - the quote send's saved Details of Sale gate. {@code QuoteSendService.prepareSend}, shared by
 * {@code POST .../quote/send-email} and the dormant {@code .../quote/send-sms}, refuses a send or a resend with 422
 * {@code QUOTE_DETAILS_OF_SALE_REQUIRED} ("Details of sale is required before sending a quote.", no details array)
 * while the order's saved {@code details_of_sale} is null or blank, read from the order it holds {@code FOR UPDATE}.
 * The gate sits AFTER the scope lookup, LAID, the body, the channel recipient, the draft and the live below-cost
 * re-check, and BEFORE changed-detection, so every earlier refusal keeps its precedence and a refused send writes
 * nothing and delivers nothing: no {@code quote_version}, {@code quote_version_line}, {@code stored_file} or file,
 * no {@code quote_token} change, no attempt marker, no email and no SMS. Frozen snapshots are never modified: a
 * legacy version frozen with blank details is never re-sent unchanged, and once the live details are filled the
 * resend issues a new version that supersedes it (its token SUPERSEDED, never REPLACED). The draft preview is not a
 * send and still renders with blank details.
 *
 * <p>Self-seeded (no V4 identity): an own business, store and salesperson, plus a second store of that business and
 * another business for the scope cases, all inside the test transaction. The recording senders are singletons, reset
 * before AND after every test.
 */
@ExtendWith(SpringExtension.class)
// The SAME storage property as the other quote suites, so this class joins their cached Spring context.
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class QuoteSendDetailsOfSaleGateTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded scopes ----
    private static final String SLUG = "pr2-quote-details-gate";
    private static final String BUSINESS_NAME = "Details Gate Flooring";
    private static final String STORE_CODE = "QDG-A";
    private static final String SALESPERSON_CODE = "QD2";
    private static final String SECOND_STORE_CODE = "QDG-B";
    private static final String OTHER_SLUG = "pr2-quote-details-gate-other";
    private static final String OTHER_BUSINESS_NAME = "Other Details Gate Flooring";
    private static final String OTHER_STORE_CODE = "QDO-A";
    private static final String OTHER_SALESPERSON_CODE = "QE2";

    // ---- The seeded order ----
    private static final String CUSTOMER_FIRST_NAME = "Riley";
    private static final String CUSTOMER_LAST_NAME = "Detailsgate";
    private static final String CUSTOMER_EMAIL = "riley.detailsgate@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS = "Supply and install as quoted (details gate test).";
    /** Every blank form the gate refuses: null, empty and whitespace-only (the API stores null; SQL can store any). */
    private static final List<String> BLANK_DETAILS = Arrays.asList(null, "", "   ", " \t\n ");

    // ---- The gate ----
    private static final String GATE_CODE = "QUOTE_DETAILS_OF_SALE_REQUIRED";
    private static final String GATE_MESSAGE = "Details of sale is required before sending a quote.";

    // ---- Quote draft: Supply 2 x 100 + Fit 1 x 50 = 250.00 ex / 275.00 inc (cost basis 40.00) ----
    private static final String ITEMISED_DRAFT = """
            {"itemised": true, "lines": [
              {"line_type":"ITEM","description":"Supply","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
              {"line_type":"ITEM","description":"Fit","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
            ]}""";

    /** A 1 x 1 PNG the public accept decodes (the QuoteSendControllerTest fixture). */
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final Pattern PUBLIC_LINK_TOKEN = Pattern.compile("/q/([A-Za-z0-9_-]+)");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingSmsSender smsSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender notificationSender;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;
    private Scope home;
    private int orderSeq;
    private int chargeSeq;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resetSenders();
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir, "the class must use its own storage property");
        orderSeq = 0;
        chargeSeq = 0;
        home = seedTenant(SLUG, BUSINESS_NAME, STORE_CODE, SALESPERSON_CODE, "home");
    }

    @AfterEach
    void tearDown() {
        resetSenders();
    }

    // ================================================================
    // The gate on a first issue, a resend and after an acceptance
    // ================================================================

    @Test
    void sendEmail_firstIssue_detailsNullEmptyOrWhitespace_isRefused_noVersionLinePdfTokenMarkerOrEmail_thenNonBlankDetailsIssues()
            throws Exception {
        long orderId = seedOrder(home, null, CUSTOMER_EMAIL);
        saveDraft(home, orderId, ITEMISED_DRAFT);

        for (String blank : BLANK_DETAILS) {
            setLiveDetails(orderId, blank);
            assertGateRefused(home, orderId, sendEmail(home, orderId), "first send, details " + describe(blank));
        }
        Assertions.assertEquals(0, versionCount(orderId), "no version was ever issued");

        // The same order with non-blank saved details issues normally, freezing those details.
        setLiveDetails(orderId, DETAILS);
        MvcResult issued = perform(sendEmail(home, orderId));
        assertStatus(issued, 201, "first send with non-blank details");
        JsonNode data = readJson(issued).path("data");
        Assertions.assertEquals(1, data.path("version_number").asInt(), "v1 issued");
        Assertions.assertEquals("ISSUED", data.path("status").asText());
        Assertions.assertEquals(DETAILS, data.path("details_of_sale").asText(), "the frozen body carries the details");
        Map<String, Object> v1 = versionRow(orderId, 1);
        Assertions.assertEquals(DETAILS, v1.get("details_of_sale_snapshot"), "details frozen from the locked order");
        Assertions.assertEquals(2, versionLineCount(orderId), "the itemised issue snapshots its lines");
        Assertions.assertEquals(List.of("ACTIVE"), tokenStatuses(orderId), "one ACTIVE link");
        Assertions.assertEquals(1, storedFileRows(home, orderId).size(), "the issued PDF's stored_file row");
        Assertions.assertEquals(1, filesOnDisk(home, orderId).size(), "the issued PDF on disk");
        Assertions.assertEquals(1, quoteEmailSender.sentEmails().size(), "exactly one quote email");
        Assertions.assertEquals(CUSTOMER_EMAIL, quoteEmailSender.sentEmails().get(0).recipientEmail());
    }

    @Test
    void sendEmail_resendAfterDetailsBlanked_isRefused_issuedVersionActiveTokenAndMarkersUnchanged_noEmail()
            throws Exception {
        long orderId = seedOrder(home, DETAILS, CUSTOMER_EMAIL);
        saveDraft(home, orderId, ITEMISED_DRAFT);
        sendAndExtractToken(home, orderId);
        Map<String, Object> issuedBefore = versionRow(orderId, 1);
        Map<String, Object> tokenBefore = singleToken(orderId);

        for (String blank : BLANK_DETAILS) {
            setLiveDetails(orderId, blank);
            assertGateRefused(home, orderId, sendEmail(home, orderId), "resend, details " + describe(blank));
        }

        Assertions.assertEquals(issuedBefore, versionRow(orderId, 1),
                "v1 keeps its status, frozen details and attempt markers (no re-stamp)");
        Assertions.assertEquals(List.of(tokenBefore), tokenRows(orderId), "the ACTIVE link is neither replaced nor killed");
        Assertions.assertEquals(1, quoteEmailSender.sentEmails().size(), "only the original send emailed");
    }

    @Test
    void sendEmail_legacyIssuedVersionWithBlankFrozenDetails_unchangedResend_isRefused_tokenNotReplaced()
            throws Exception {
        long orderId = seedOrder(home, DETAILS, CUSTOMER_EMAIL);
        saveDraft(home, orderId, ITEMISED_DRAFT);
        sendAndExtractToken(home, orderId);
        // A legacy version frozen with blank details (only possible before Batch A PR 2) and still-blank live
        // details: changed-detection would call this an unchanged resend, but the gate refuses it first.
        seedLegacyBlankSnapshot(orderId);
        setLiveDetails(orderId, null);
        Map<String, Object> legacyVersion = versionRow(orderId, 1);
        Map<String, Object> activeToken = singleToken(orderId);

        assertGateRefused(home, orderId, sendEmail(home, orderId), "unchanged resend of a legacy blank version");

        Assertions.assertEquals(legacyVersion, versionRow(orderId, 1), "the legacy version is untouched");
        Assertions.assertNull(versionRow(orderId, 1).get("details_of_sale_snapshot"), "its blank snapshot stays blank");
        Assertions.assertEquals(List.of(activeToken), tokenRows(orderId),
                "its link stays ACTIVE: not REPLACED, no new token");
        Assertions.assertEquals(1, quoteEmailSender.sentEmails().size(), "only the original send emailed");
    }

    @Test
    void sendEmail_legacyBlankSnapshot_liveNowFilled_issuesNewVersion_oldSuperseded() throws Exception {
        long orderId = seedOrder(home, DETAILS, CUSTOMER_EMAIL);
        saveDraft(home, orderId, ITEMISED_DRAFT);
        sendAndExtractToken(home, orderId);
        seedLegacyBlankSnapshot(orderId);
        Map<String, Object> oldToken = singleToken(orderId);

        // The live details are non-blank, so the gate passes; they differ from the blank frozen snapshot, so
        // changed-detection issues a NEW version and supersedes the legacy one (the snapshot is never mutated).
        MvcResult resend = perform(sendEmail(home, orderId));
        assertStatus(resend, 201, "resend with filled live details");
        Assertions.assertEquals(2, readJson(resend).path("data").path("version_number").asInt(), "v2 issued");

        Assertions.assertEquals("SUPERSEDED", versionRow(orderId, 1).get("status"), "the legacy v1 is superseded");
        Assertions.assertNull(versionRow(orderId, 1).get("details_of_sale_snapshot"),
                "the legacy snapshot is never modified");
        Map<String, Object> v2 = versionRow(orderId, 2);
        Assertions.assertEquals("ISSUED", v2.get("status"));
        Assertions.assertEquals(DETAILS, v2.get("details_of_sale_snapshot"), "v2 freezes the filled details");

        Map<String, Object> deadToken = tokenRow(((Number) oldToken.get("quote_token_id")).longValue());
        Assertions.assertEquals("SUPERSEDED", deadToken.get("status"),
                "a changed-content resend supersedes the old link (REPLACED is only for an unchanged resend)");
        Assertions.assertNotNull(deadToken.get("dead_at"), "the old link is dead");
        Assertions.assertEquals(List.of("SUPERSEDED", "ACTIVE"), tokenStatuses(orderId), "one new ACTIVE link");
        Assertions.assertEquals(2, quoteEmailSender.sentEmails().size(), "the original send and this resend");
    }

    @Test
    void sendEmail_afterAcceptance_blankDetails_isRefused_acceptedVersionUntouched() throws Exception {
        long orderId = seedOrder(home, DETAILS, CUSTOMER_EMAIL);
        saveDraft(home, orderId, ITEMISED_DRAFT);
        String token = sendAndExtractToken(home, orderId);
        acceptPublicly(token);
        Assertions.assertEquals("ACCEPTED", versionRow(orderId, 1).get("status"), "fixture: v1 accepted publicly");
        setLiveDetails(orderId, null);
        Map<String, Object> accepted = versionRow(orderId, 1);
        List<Map<String, Object>> tokens = tokenRows(orderId);

        // With no ISSUED version a send would issue v2 (decision D9): the gate refuses it before anything.
        assertGateRefused(home, orderId, sendEmail(home, orderId), "send after the acceptance with blank details");

        Assertions.assertEquals(accepted, versionRow(orderId, 1),
                "the accepted version (signature, signed PDF, accepted name and time) is untouched");
        Assertions.assertEquals(tokens, tokenRows(orderId), "the CONSUMED link is untouched and no link is minted");
        Assertions.assertEquals(1, versionCount(orderId), "no v2");
    }

    @Test
    void sendSms_blankDetails_isRefusedLikeEmail_noSms() throws Exception {
        long orderId = seedOrder(home, null, CUSTOMER_EMAIL);
        saveDraft(home, orderId, ITEMISED_DRAFT);

        for (String blank : BLANK_DETAILS) {
            setLiveDetails(orderId, blank);
            assertGateRefused(home, orderId, sendSms(home, orderId), "dormant send-sms, details " + describe(blank));
        }
        Assertions.assertEquals(List.of(), smsSender.sentMessages(), "no SMS was sent");
        Assertions.assertEquals(List.of(), smsSender.failedMessages(), "no SMS was attempted");
        Assertions.assertEquals(0, versionCount(orderId), "nothing issued");
    }

    // ================================================================
    // Precedence: every earlier refusal wins over the details gate
    // ================================================================

    @Test
    void sendEmail_detailsGatePrecedence_scope404_laid422_body400_recipient422_draft404_belowCost422_thenDetails()
            throws Exception {
        // Scope: another store of the same business, and another business - 404 before anything else, even with
        // blank details and a malformed body.
        Scope secondStore = seedSecondStoreOfHome();
        long otherStoreOrder = seedOrder(secondStore, null, CUSTOMER_EMAIL);
        assertRefused(secondStore, otherStoreOrder, sendEmail(home, otherStoreOrder, "{not json"), 404,
                "ORDER_NOT_FOUND", "an order of another store");
        Scope otherBusiness = seedTenant(OTHER_SLUG, OTHER_BUSINESS_NAME, OTHER_STORE_CODE, OTHER_SALESPERSON_CODE,
                "other");
        long otherBusinessOrder = seedOrder(otherBusiness, null, CUSTOMER_EMAIL);
        assertRefused(otherBusiness, otherBusinessOrder, sendEmail(home, otherBusinessOrder, "{not json"), 404,
                "ORDER_NOT_FOUND", "an order of another business");

        // LAID beats the body and the details.
        long laid = seedOrder(home, null, CUSTOMER_EMAIL);
        saveDraft(home, laid, ITEMISED_DRAFT);
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", laid);
        clearJpaCache();
        assertRefused(home, laid, sendEmail(home, laid, "{\"channel\":\"EMAIL\"}"), 422, "ORDER_LOCKED",
                "a LAID order");

        // The body beats the recipient, the draft, below cost and the details.
        long badBody = seedOrder(home, null, CUSTOMER_EMAIL);
        saveDraft(home, badBody, ITEMISED_DRAFT);
        assertRefused(home, badBody, sendEmail(home, badBody, "{\"channel\":\"EMAIL\"}"), 400, "VALIDATION_FAILED",
                "a body with a field");

        // The recipient beats the draft, below cost and the details.
        long noEmail = seedOrder(home, null, null);
        assertRefused(home, noEmail, sendEmail(home, noEmail), 422, "CUSTOMER_EMAIL_REQUIRED",
                "no saved customer email (and no draft)");

        // The draft beats below cost and the details.
        long noDraft = seedOrder(home, null, CUSTOMER_EMAIL);
        assertRefused(home, noDraft, sendEmail(home, noDraft), 404, "QUOTE_NOT_FOUND", "no saved draft");

        // Below cost beats the details: the draft saved above cost, then the live cost rose past it.
        long belowCost = seedOrder(home, null, CUSTOMER_EMAIL);
        saveDraft(home, belowCost, ITEMISED_DRAFT);
        addChargeLine(home, belowCost, "400.00", "400.00");
        assertRefused(home, belowCost, sendEmail(home, belowCost), 422, "QUOTE_BELOW_COST",
                "live cost above the quote total");

        // Everything else passes: the details gate is the refusal.
        long blankOnly = seedOrder(home, null, CUSTOMER_EMAIL);
        saveDraft(home, blankOnly, ITEMISED_DRAFT);
        assertGateRefused(home, blankOnly, sendEmail(home, blankOnly), "only the details are blank");

        Assertions.assertEquals(List.of(), quoteEmailSender.sentEmails(), "no refused send emailed");
    }

    // ================================================================
    // The draft preview is not a send
    // ================================================================

    @Test
    void previewPdf_blankDetails_stillReturns200() throws Exception {
        long orderId = seedOrder(home, null, CUSTOMER_EMAIL);
        saveDraft(home, orderId, ITEMISED_DRAFT);
        Footprint before = footprint(home, orderId);

        MvcResult preview = perform(post(quoteUrl(home, orderId, "preview-pdf")).session(home.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertStatus(preview, 200, "draft preview with blank details");
        Assertions.assertEquals(MediaType.APPLICATION_PDF_VALUE, preview.getResponse().getContentType());
        byte[] pdf = preview.getResponse().getContentAsByteArray();
        Assertions.assertTrue(pdf.length > 5 && new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"),
                "a PDF was rendered");
        Assertions.assertEquals(before, footprint(home, orderId), "a preview writes nothing and sends nothing");
    }

    // ================================================================
    // Helpers: assertions
    // ================================================================

    /** The gate's exact refusal: 422, the code and message, no details array, no data, nothing written or sent. */
    private void assertGateRefused(Scope scope, long orderId, RequestBuilder request, String label) throws Exception {
        JsonNode error = assertRefused(scope, orderId, request, 422, GATE_CODE, label);
        Assertions.assertEquals(GATE_MESSAGE, error.path("message").asText(), () -> label + ": the gate message");
        Assertions.assertEquals(ErrorCode.QUOTE_DETAILS_OF_SALE_REQUIRED.defaultMessage(), GATE_MESSAGE,
                "the gate message is the code's default");
        Assertions.assertFalse(error.has("details"), () -> label + ": the gate carries no details: " + error);
    }

    /** A refused send: the status and code, no data, and the order's whole quote footprint unchanged. */
    private JsonNode assertRefused(Scope footprintScope, long footprintOrderId, RequestBuilder request,
                                   int expectedStatus, String expectedCode, String label) throws Exception {
        Footprint before = footprint(footprintScope, footprintOrderId);
        MvcResult result = perform(request);
        assertStatus(result, expectedStatus, label);
        JsonNode body = readJson(result);
        JsonNode error = body.path("error");
        Assertions.assertEquals(expectedCode, error.path("code").asText(), () -> label + ": error code; body: " + body);
        Assertions.assertFalse(body.has("data"), () -> label + ": an error carries no data: " + body);
        Assertions.assertEquals(before, footprint(footprintScope, footprintOrderId),
                () -> label + ": nothing written, nothing delivered");
        return error;
    }

    private static void assertStatus(MvcResult result, int expected, String label) throws Exception {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                label + ": status; body: " + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * Everything a send can write or deliver for one order: its quote versions (status, snapshots, attempt markers),
     * their lines and tokens, the order row, the draft and its lines, the stored_file rows and files under the
     * order's storage directory, and every recording sender's counts.
     */
    private record Footprint(List<Map<String, Object>> versions, List<Map<String, Object>> versionLines,
                             List<Map<String, Object>> tokens, List<Map<String, Object>> orderRow,
                             List<Map<String, Object>> draft, List<Map<String, Object>> draftLines,
                             List<Map<String, Object>> storedFiles, Set<String> files,
                             int quoteEmailsSent, int quoteEmailsFailed, int smsSent, int smsFailed,
                             int notificationsSent, int notificationsFailed) {
    }

    private Footprint footprint(Scope scope, long orderId) throws IOException {
        clearJpaCache();
        return new Footprint(
                rows("SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT l.* FROM quote_version_line l JOIN quote_version v ON v.quote_version_id = l.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY l.quote_version_line_id", orderId),
                tokenRows(orderId),
                rows("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                rows("SELECT * FROM quote_draft WHERE order_id = ?", orderId),
                rows("SELECT l.* FROM quote_draft_line l JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id "
                        + "WHERE d.order_id = ? ORDER BY l.quote_draft_line_id", orderId),
                storedFileRows(scope, orderId),
                filesOnDisk(scope, orderId),
                quoteEmailSender.sentEmails().size(), quoteEmailSender.failedEmails().size(),
                smsSender.sentMessages().size(), smsSender.failedMessages().size(),
                notificationSender.sentNotifications().size(), notificationSender.failedNotifications().size());
    }

    // ================================================================
    // Helpers: requests
    // ================================================================

    private RequestBuilder sendEmail(Scope scope, long orderId) {
        return sendEmail(scope, orderId, "{}");
    }

    private RequestBuilder sendEmail(Scope scope, long orderId, String body) {
        return post(quoteUrl(scope, orderId, "send-email")).session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestBuilder sendSms(Scope scope, long orderId) {
        return post(quoteUrl(scope, orderId, "send-sms")).session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private void saveDraft(Scope scope, long orderId, String json) throws Exception {
        MvcResult result = perform(put(quoteUrl(scope, orderId, "draft")).session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content(json));
        assertStatus(result, 200, "PUT quote draft");
    }

    /** Issue (or re-issue) via send-email and return the plaintext token from the recorded link. */
    private String sendAndExtractToken(Scope scope, long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = perform(sendEmail(scope, orderId));
        assertStatus(result, 201, "POST quote send-email");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "exactly one quote email must have been recorded");
        String body = sent.get(sent.size() - 1).bodyText();
        Matcher matcher = PUBLIC_LINK_TOKEN.matcher(body);
        Assertions.assertTrue(matcher.find(), () -> "the quote email must carry the /q/{token} link: " + body);
        return matcher.group(1);
    }

    /** The real public accept: token-only (no session), exactly one PNG signature part. */
    private void acceptPublicly(String token) throws Exception {
        MvcResult result = perform(multipart("/api/v1/public/quotes/" + token + "/accept")
                .file(new MockMultipartFile("signature", "signature.png", "image/png", ONE_PIXEL_PNG)));
        assertStatus(result, 201, "public quote accept");
    }

    private MvcResult perform(RequestBuilder request) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        return result;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String quoteUrl(Scope scope, long orderId, String action) {
        return "/api/v1/" + scope.slug() + "/orders/" + orderId + "/quote/" + action;
    }

    private static String describe(String details) {
        return details == null ? "null" : "'" + details.replace("\t", "\\t").replace("\n", "\\n") + "'";
    }

    // ================================================================
    // Helpers: fixtures
    // ================================================================

    /** One session scope: a business (by slug), one of its stores and a salesperson with access to it. */
    private record Scope(long businessId, int storeId, long userId, String slug, String storeCode,
                         String salespersonCode) {

        /** The RequestContextGuard session. Type trap: (Long) user_id / business_id, (Integer) store_id. */
        MockHttpSession session() {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("user_id", userId);
            session.setAttribute("business_id", businessId);
            session.setAttribute("store_id", storeId);
            return session;
        }
    }

    /** A business (active, no terms), its store, one salesperson and that salesperson's store access. */
    private Scope seedTenant(String slug, String businessName, String storeCode, String salespersonCode, String tag) {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, '51 824 753 556', NULL, NULL) RETURNING business_id",
                Long.class, businessName, slug);
        Assertions.assertNotNull(businessId, "business insert must return an id");
        Integer storeId = insertStore(businessId, businessName + " Store", storeCode, tag);
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Dana', 'Gate', ?, ?, 'not-a-real-hash-details-gate') RETURNING user_id",
                Long.class, businessId, salespersonCode, "dana.gate." + tag + ".details@example.com");
        Assertions.assertNotNull(userId, "app_user insert must return an id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        clearJpaCache();
        return new Scope(businessId, storeId, userId, slug, storeCode, salespersonCode);
    }

    /** A second store of the home business with access for the home salesperson (the out-of-store scope). */
    private Scope seedSecondStoreOfHome() {
        Integer storeId = insertStore(home.businessId(), "Details Gate Second Store", SECOND_STORE_CODE, "second");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                home.businessId(), home.userId(), storeId);
        clearJpaCache();
        return new Scope(home.businessId(), storeId, home.userId(), SLUG, SECOND_STORE_CODE, SALESPERSON_CODE);
    }

    private Integer insertStore(long businessId, String name, String storeCode, String tag) {
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, ?, ?, '0299990000', ?, '1 Gate Lane', 'Parramatta', 'NSW', '2150') "
                        + "RETURNING store_id",
                Integer.class, businessId, name, storeCode, "store." + tag + ".details.gate@example.com");
        Assertions.assertNotNull(storeId, "store insert must return an id");
        return storeId;
    }

    /**
     * A SOFT LEAD order of {@code scope} with the given saved details of sale (null = none), the customer Riley
     * Detailsgate with {@code email} and a valid AU mobile (a null {@code email} seeds NO customer row: the column is
     * NOT NULL, so a missing customer is how "no saved email" is reached), INSTALLATION and BILLING addresses, and one
     * priced charge line of 100.00 ex / 40.00 cost (so the 250.00 ex quote draft is above cost).
     */
    private long seedOrder(Scope scope, String detailsOfSale, String email) {
        int s = ++orderSeq;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, flooring_type, "
                        + " order_status, week_number, week_year, details_of_sale) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 44, 2026, ?) "
                        + "RETURNING order_id",
                Long.class, scope.businessId(), scope.storeId(), scope.userId(), s,
                scope.storeCode() + "." + scope.salespersonCode() + "." + String.format("%05d", s), detailsOfSale);
        Assertions.assertNotNull(orderId, "order insert must return an id");
        if (email != null) {
            jdbcTemplate.update(
                    "INSERT INTO order_customer (order_id, first_name, middle_name, last_name, email, mobile) "
                            + "VALUES (?, ?, NULL, ?, ?, ?)",
                    orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, email, CUSTOMER_MOBILE);
        }
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '9', 'Install Close', 'Ryde', 'NSW', '2112')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Billing Street', 'Sydney', 'NSW', '2000')",
                orderId);
        addChargeLine(scope, orderId, "100.00", "40.00");
        return orderId;
    }

    /** One priced charge line (quantity 1) of {@code price} ex and {@code cost} on the order. */
    private void addChargeLine(Scope scope, long orderId, String price, String cost) {
        String code = "QDGC" + (++chargeSeq);
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Details gate supply and lay', CAST(? AS NUMERIC), "
                        + " CAST(? AS NUMERIC)) RETURNING charge_id",
                Long.class, scope.storeId(), code, price, cost);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Details gate supply and lay', CAST(? AS NUMERIC), CAST(? AS NUMERIC), 1, "
                        + " CAST(? AS NUMERIC), CAST(? AS NUMERIC), CAST(? AS NUMERIC))",
                orderId, chargeId, code, price, cost, price, price, cost);
        clearJpaCache();
    }

    /** The order's saved details of sale, written as given (null, empty and whitespace are all storable). */
    private void setLiveDetails(long orderId, String details) {
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = ? WHERE order_id = ?", details, orderId);
        clearJpaCache();
    }

    /**
     * A legacy issued version frozen with blank details: before Batch A PR 2 a send could freeze NULL, so the v1
     * this test issued legitimately gets that legacy snapshot (the QuoteInvoiceConversionControllerTest pattern).
     */
    private void seedLegacyBlankSnapshot(long orderId) {
        jdbcTemplate.update("UPDATE quote_version SET details_of_sale_snapshot = NULL "
                + "WHERE order_id = ? AND version_number = 1", orderId);
        clearJpaCache();
    }

    // ================================================================
    // Helpers: probes
    // ================================================================

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return new ArrayList<>(jdbcTemplate.queryForList(sql, args));
    }

    private int versionCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> versionRow(long orderId, int versionNumber) {
        clearJpaCache();
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private int versionLineCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version_line l JOIN quote_version v ON v.quote_version_id = l.quote_version_id "
                        + "WHERE v.order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> tokenRows(long orderId) {
        return rows("SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                + "WHERE v.order_id = ? ORDER BY t.quote_token_id", orderId);
    }

    private List<String> tokenStatuses(long orderId) {
        return tokenRows(orderId).stream().map(row -> (String) row.get("status")).collect(Collectors.toList());
    }

    private Map<String, Object> singleToken(long orderId) {
        List<Map<String, Object>> tokens = tokenRows(orderId);
        Assertions.assertEquals(1, tokens.size(), "exactly one quote token");
        return tokens.get(0);
    }

    private Map<String, Object> tokenRow(long quoteTokenId) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_token WHERE quote_token_id = ?", quoteTokenId);
    }

    /** The order's storage directory, as FileStorageService lays it out: /uploads/{businessId}/orders/{orderId}/. */
    private static String orderStoragePrefix(Scope scope, long orderId) {
        return "/uploads/" + scope.businessId() + "/orders/" + orderId + "/";
    }

    private List<Map<String, Object>> storedFileRows(Scope scope, long orderId) {
        return rows("SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                orderStoragePrefix(scope, orderId) + "%");
    }

    /** The file names on disk in the order's storage directory (empty when the directory does not exist). */
    private Set<String> filesOnDisk(Scope scope, long orderId) throws IOException {
        Path directory = Path.of(storageBaseDir).toAbsolutePath().normalize()
                .resolve(orderStoragePrefix(scope, orderId).substring(1)).normalize();
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private void resetSenders() {
        quoteEmailSender.reset();
        smsSender.reset();
        notificationSender.reset();
    }

    /** Services mix JPA reads with native writes; production requests each get a fresh persistence context. */
    private void clearJpaCache() {
        entityManager.clear();
    }
}
