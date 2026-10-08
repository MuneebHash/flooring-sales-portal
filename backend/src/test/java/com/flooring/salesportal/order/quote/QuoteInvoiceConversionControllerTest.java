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
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16F PR2 - Path A: {@code POST /api/v1/{slug}/orders/{orderId}/quote/create-invoice} creates an
 * invoice version from the order's LATEST ACCEPTED quote, inheriting the quote's signature.
 *
 * <p>Covered here: version selection and scope (no accepted version, highest ACCEPTED version despite
 * newer draft / issued / superseded / cancelled / expired history, tenant and store isolation, auth
 * and order-id gates, the empty-body rule and the gate order), every D5(b) signature-precedence row
 * against the CURRENT invoice (with the workspace {@code accepted.invoice_eligible} flag read before and
 * after), the collected Path A preconditions, the payment / overpayment rule, and the LAID behaviour. Some
 * mapped sources are proven only by fixtures where the right and a likely wrong source differ: due_date
 * follows the order's CURRENT lay date (moved after D.1 and again after the acceptance),
 * created_by_user_id is the session user (a second business-1 user, not the order owner), invoice_date
 * lies within the local dates read just before and after the request, signature times are compared
 * (never creation or email times), and a replaced in-app signature never reaches the Path A PDF.
 *
 * <p>Every accepted quote version is created through the REAL chain: protected draft save, protected
 * send-email (the plaintext token is read from the recorded link-only email), then the PUBLIC multipart
 * accept endpoint with no session. Raw SQL only seeds rows (order, customer, addresses, charge lines,
 * tenant terms) and moves state around those real flows, wherever a scenario needs it: before the issue
 * (for example null live details, or the lay date in force at issue), between the issue and the
 * acceptance (LAID), or after the acceptance (for example breaking live rows, zeroing a frozen total,
 * forcing a signature tie, moving the lay date or a payment version's creation time), plus the section
 * 11.1 mirror sentinel written before a Path A call. Edits to rows a request may already have loaded are
 * followed by clearing the JPA cache, and every MockMvc call clears it on both sides as well. Constraint
 * relaxations ({@code chk_order_customer_email_format}, the email NOT NULL and
 * {@code chk_sales_order_lay_date_pair}) are transactional DDL and roll back with the test.
 *
 * <p>Allowed conversions are proven against the persisted rows: the new {@code invoice} row, its new
 * {@code stored_file} row and the PDF bytes on disk (and the PDF text), the untouched earlier invoice
 * versions (SELECT * per version plus their PDF bytes), the untouched quote / order / line / customer /
 * address / payment rows, the section 11.1 mirror reset and the silent recording senders. Refusals are
 * proven to write nothing at all (same rows, same stored files, same files on disk, same mirror).
 *
 * <p>Self-seeded (Phase 14D): orders are inserted under business 1 / store 1 / user 1 with the
 * {@code QINVC.ZZ9.} order-number prefix (sequence base 130_000) and {@code QINVC} store_charge codes; no
 * V4 demo order, invoice or payment is used (one conversion runs as another active business-1 user,
 * granted store 1 inside the test transaction). Everything rolls back with the test transaction and the
 * services' rollback hooks delete the files they wrote under the shared quote test storage root. The
 * recording senders are singletons, so they are reset before AND after every test.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class QuoteInvoiceConversionControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;
    private static final long BUSINESS_PREMIER = 2L;

    private static final String ORDER_NUMBER_PREFIX = "QINVC.ZZ9.";
    private static final String CHARGE_CODE_PREFIX = "QINVC";
    private static final String VALID_EMAIL = "quote.invoice.conversion@example.com";
    private static final String VALID_MOBILE = "0412345678";
    // The V17 issue-time snapshot of the seeded customer ('Quote' + 'Tester') - the frozen accepted name.
    private static final String SNAPSHOT_NAME = "Quote Tester";
    private static final String FROZEN_DETAILS = "Supply and lay carpet (frozen at issue)";
    // Sanitizer-stable tenant terms: frozen into terms_snapshot when a quote is issued.
    private static final String FROZEN_TERMS = "<p>Quote conversion frozen terms</p>";
    private static final String FROZEN_TERMS_TEXT = "Quote conversion frozen terms";
    private static final String LIVE_TERMS = "<p>Live terms edited after acceptance</p>";
    private static final String LIVE_TERMS_TEXT = "Live terms edited after acceptance";
    // proposed_lay_date 2026-12-01 minus 2 days.
    private static final LocalDate EXPECTED_DUE_DATE = LocalDate.of(2026, 11, 29);
    // Lay-date moves for the due_date source test: one in force while the quote is issued and accepted,
    // and the CURRENT one (moved after the acceptance) that Path A must use, minus 2 days.
    private static final LocalDate LAY_DATE_AT_ISSUE = LocalDate.of(2026, 12, 20);
    private static final LocalDate CURRENT_LAY_DATE = LocalDate.of(2027, 1, 15);
    private static final LocalDate CURRENT_LAY_DATE_DUE = LocalDate.of(2027, 1, 13);
    // The in-app (D.8) signer of an invoice that a newer quote signature later replaces.
    private static final String IN_APP_SIGNER_NAME = "Invoice Signer";
    // Written into sales_order.last_emailed_at before a call so a mirror write can never go unnoticed.
    private static final Timestamp MIRROR_SENTINEL = Timestamp.valueOf("2026-01-02 03:04:05");
    private static final long MISSING_ORDER_ID = 9_999_999_999L;

    private static final String CREATED_MESSAGE = "Invoice created from accepted quote.";
    private static final String QUOTE_NOT_ACCEPTED_MESSAGE = "This quote has not been accepted yet.";
    private static final String NOT_NEWER_MESSAGE = "The current invoice was signed at the same time or later "
            + "than this quote. A newer signed quote is required to create an invoice from a quote.";
    private static final String D8_ALREADY_ACCEPTED_MESSAGE =
            "This invoice has already been accepted. Use Re-send to email it again.";
    private static final String PRECONDITIONS_MESSAGE = "Complete required fields before creating invoice.";
    private static final String PAYMENTS_EXCEED_MESSAGE = "Recorded payments exceed the accepted quote total. "
            + "Void the excess payments before creating an invoice from this quote.";
    private static final String VALIDATION_MESSAGE = "One or more fields are invalid.";
    private static final String MALFORMED_MESSAGE = "Request body is malformed.";
    private static final String ORDER_LOCKED_MESSAGE = "Order is laid and cannot be edited.";

    // The collected Path A precondition details (section / field / message).
    private static final Detail FIRST_NAME =
            new Detail("customer", "first_name", "Customer first name is required.");
    private static final Detail LAST_NAME =
            new Detail("customer", "last_name", "Customer last name is required.");
    private static final Detail INSTALLATION =
            new Detail("address", "installation_address", "Installation address is required.");
    private static final Detail BILLING =
            new Detail("address", "billing_address", "Billing address is required.");
    private static final Detail QUOTE_DETAILS =
            new Detail("details", "details_of_sale", "Details of sale on the accepted quote is required.");
    private static final Detail LAY_DATE =
            new Detail("details", "proposed_lay_date", "Proposed lay date is required.");
    private static final Detail LAY_DATE_STATUS =
            new Detail("details", "lay_date_status", "Lay date status is required.");
    private static final Detail QUOTE_EX = new Detail("financial", "sale_price_ex_gst",
            "The accepted quote total (ex GST) must be greater than zero.");
    private static final Detail QUOTE_INC = new Detail("financial", "sale_price_inc_gst",
            "The accepted quote total (inc GST) must be greater than zero.");

    // openapi InvoiceDetail: every key ALWAYS present; no internal id (stored_file / signature / source).
    private static final Set<String> INVOICE_DETAIL_KEYS = Set.of(
            "invoice_id", "order_id", "version_number", "invoice_date", "due_date",
            "details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst", "total_paid",
            "balance_due", "created_by_user_id", "created_at", "pdf_download_path", "accepted_at",
            "accepted_customer_name", "accepted_signature_present", "accepted_signature_download_path",
            "last_emailed_at", "terms_html", "terms_source");

    // The plaintext token inside the delivered link .../q/{token} (URL-safe Base64).
    private static final Pattern PUBLIC_LINK_TOKEN = Pattern.compile("/q/([A-Za-z0-9_-]{43,128})");

    // Two different real, decodable PNGs (the public accept decodes and re-encodes the upload).
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final byte[] SECOND_SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEElEQVR42mOQtzWDIAY4CwAhwgNtlqP3IgAAAABJRU5ErkJggg==");

    private static final DateTimeFormatter JSON_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // Exact money comparisons: JSON decimals are read as BigDecimal (never through a double).
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

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

    @PersistenceContext
    private EntityManager entityManager;

    // The EFFECTIVE storage root (the class-level property), resolved exactly like FileStorageService.
    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    private int seq = 130_000;

    @BeforeEach
    void setUp() {
        Assertions.assertTrue(storageRoot().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                () -> "unexpected storage root " + storageRoot());
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
    // 1a. No ACCEPTED version -> 422 QUOTE_NOT_ACCEPTED, nothing written
    // ================================================================

    @Test
    void createInvoice_orderWithNoQuoteAtAll_returns422QuoteNotAccepted_nothingWritten() throws Exception {
        long orderId = invoiceReadyOrder();

        JsonNode error = assertRefused(orderId, "{}", 422, "QUOTE_NOT_ACCEPTED", QUOTE_NOT_ACCEPTED_MESSAGE,
                "no quote at all");
        Assertions.assertFalse(error.has("details"), "QUOTE_NOT_ACCEPTED carries no details");
        Assertions.assertEquals(0, invoiceCount(orderId));
        Assertions.assertEquals(0, quoteVersionCount(orderId));
    }

    @Test
    void createInvoice_draftOnly_returns422QuoteNotAccepted_nothingWritten() throws Exception {
        long orderId = invoiceReadyOrder();
        saveItemisedTwoLineDraft(orderId);
        Assertions.assertEquals(0, quoteVersionCount(orderId), "fixture: a saved draft is never a version");

        assertRefused(orderId, "{}", 422, "QUOTE_NOT_ACCEPTED", QUOTE_NOT_ACCEPTED_MESSAGE, "draft only");
        Assertions.assertEquals(0, invoiceCount(orderId));
    }

    @Test
    void createInvoice_onlyAnIssuedVersion_returns422QuoteNotAccepted_issuedLinkStaysActive() throws Exception {
        long orderId = invoiceReadyOrder();
        saveItemisedTwoLineDraft(orderId);
        String token = sendAndExtractToken(orderId);
        Map<String, Object> issued = quoteVersionRow(orderId, 1);
        Map<String, Object> tokenBefore = tokenRow(token);
        Assertions.assertEquals("ISSUED", issued.get("status"));

        assertRefused(orderId, "{}", 422, "QUOTE_NOT_ACCEPTED", QUOTE_NOT_ACCEPTED_MESSAGE, "issued only");

        Assertions.assertEquals(issued, quoteVersionRow(orderId, 1), "the ISSUED version is untouched");
        Assertions.assertEquals(tokenBefore, tokenRow(token), "the ACTIVE token is untouched");
        assertPublicState(token, "ACTIVE");
        Assertions.assertEquals(0, invoiceCount(orderId));
    }

    @Test
    void createInvoice_issuedThenCancelled_returns422QuoteNotAccepted_nothingWritten() throws Exception {
        long orderId = invoiceReadyOrder();
        saveItemisedTwoLineDraft(orderId);
        String token = sendAndExtractToken(orderId);
        cancelIssuedQuote(orderId);
        Assertions.assertEquals("CANCELLED", versionStatus(orderId, 1));
        Assertions.assertEquals("CANCELLED", tokenRow(token).get("status"));

        assertRefused(orderId, "{}", 422, "QUOTE_NOT_ACCEPTED", QUOTE_NOT_ACCEPTED_MESSAGE, "issued then cancelled");
        Assertions.assertEquals(0, invoiceCount(orderId));
    }

    @Test
    void createInvoice_issuedThenLazilyExpired_returns422QuoteNotAccepted_nothingWritten() throws Exception {
        long orderId = invoiceReadyOrder();
        saveItemisedTwoLineDraft(orderId);
        String token = sendAndExtractToken(orderId);
        expireLazily(orderId, token, 1);

        assertRefused(orderId, "{}", 422, "QUOTE_NOT_ACCEPTED", QUOTE_NOT_ACCEPTED_MESSAGE, "issued then expired");
        Assertions.assertEquals(0, invoiceCount(orderId));
    }

    // ================================================================
    // 1b. The HIGHEST ACCEPTED version is selected, never a newer non-accepted one
    // ================================================================

    @Test
    void createInvoice_v1AcceptedThenDifferentDraftIssuedAsV2_usesAcceptedV1_v2StaysIssuedWithActiveLink()
            throws Exception {
        long orderId = acceptedQuoteOrder();                      // v1 ACCEPTED: 250.00 / 275.00 itemised
        saveNonItemisedDraft(orderId, "330.00");
        String token2 = sendAndExtractToken(orderId);             // v2 ISSUED: 300.00 / 330.00
        Assertions.assertEquals("ISSUED", versionStatus(orderId, 2));
        Map<String, Object> v2Before = quoteVersionRow(orderId, 2);
        Map<String, Object> token2Before = tokenRow(token2);
        Assertions.assertTrue(invoiceEligible(orderId), "a newer ISSUED version never makes v1 ineligible");

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals(asLong(quoteVersionRow(orderId, 1).get("quote_version_id")),
                asLong(invoiceRow(orderId, 1).get("source_quote_version_id")), "source = the ACCEPTED v1");

        Assertions.assertEquals(v2Before, quoteVersionRow(orderId, 2), "the newer ISSUED v2 is untouched");
        Assertions.assertEquals(token2Before, tokenRow(token2), "its ACTIVE token is untouched");
        assertPublicState(token2, "ACTIVE");
        Assertions.assertFalse(invoiceEligible(orderId));
    }

    @Test
    void createInvoice_v1AcceptedThenSupersededAndIssuedVersionsAndANewerDraft_usesV1_thenV3OnceV3IsAccepted()
            throws Exception {
        long orderId = acceptedQuoteOrder();                      // v1 ACCEPTED: 250.00 / 275.00
        saveNonItemisedDraft(orderId, "330.00");
        sendAndExtractToken(orderId);                             // v2 ISSUED
        saveNonItemisedDraft(orderId, "440.00");
        String token3 = sendAndExtractToken(orderId);             // v3 ISSUED: 400.00 / 440.00, v2 SUPERSEDED
        saveNonItemisedDraft(orderId, "550.00");                  // a newer, never-sent draft
        Assertions.assertEquals("SUPERSEDED", versionStatus(orderId, 2));
        Assertions.assertEquals("ISSUED", versionStatus(orderId, 3));
        Assertions.assertTrue(invoiceEligible(orderId), "newer draft / superseded / issued history never feeds it");

        Conversion first = convertOk(orderId);
        assertConverted(orderId, first, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals("SUPERSEDED", versionStatus(orderId, 2));
        Assertions.assertEquals("ISSUED", versionStatus(orderId, 3));
        assertPublicState(token3, "ACTIVE");
        Assertions.assertFalse(invoiceEligible(orderId), "the conversion tied the invoice with v1's signature");

        // The still-ACTIVE v3 link is signed later: a strictly newer signature makes the order eligible again.
        acceptPublicly(token3, SECOND_SIGNATURE_PNG);
        Assertions.assertTrue(invoiceEligible(orderId));
        Conversion second = convertOk(orderId);
        assertConverted(orderId, second, 2, 3, "400.00", "440.00", "0.00", "440.00");
        Assertions.assertFalse(invoiceEligible(orderId));
    }

    @Test
    void createInvoice_v1AndV2BothAccepted_usesTheHighestAcceptedV2() throws Exception {
        long orderId = acceptedQuoteOrder();                      // v1 ACCEPTED: 250.00 / 275.00
        saveNonItemisedDraft(orderId, "330.00");
        acceptPublicly(sendAndExtractToken(orderId), SECOND_SIGNATURE_PNG);   // v2 ACCEPTED: 300.00 / 330.00
        Assertions.assertEquals("ACCEPTED", versionStatus(orderId, 1));
        Assertions.assertEquals("ACCEPTED", versionStatus(orderId, 2));

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 1, 2, "300.00", "330.00", "0.00", "330.00");
        Map<String, Object> invoice = invoiceRow(orderId, 1);
        Assertions.assertNotEquals(asLong(quoteVersionRow(orderId, 1).get("accepted_signature_file_id")),
                asLong(invoice.get("accepted_signature_file_id")), "never v1's signature");
        Assertions.assertNotEquals(quoteVersionRow(orderId, 1).get("accepted_at"), invoice.get("accepted_at"));
    }

    @Test
    void createInvoice_v1AcceptedThenV2IssuedAndCancelled_usesV1() throws Exception {
        long orderId = acceptedQuoteOrder();
        saveNonItemisedDraft(orderId, "330.00");
        String token2 = sendAndExtractToken(orderId);
        cancelIssuedQuote(orderId);
        Assertions.assertEquals("CANCELLED", versionStatus(orderId, 2));
        Assertions.assertEquals("CANCELLED", tokenRow(token2).get("status"));

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals("CANCELLED", versionStatus(orderId, 2));
    }

    @Test
    void createInvoice_v1AcceptedThenV2IssuedAndLazilyExpired_usesV1() throws Exception {
        long orderId = acceptedQuoteOrder();
        saveNonItemisedDraft(orderId, "330.00");
        String token2 = sendAndExtractToken(orderId);
        expireLazily(orderId, token2, 2);

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals("EXPIRED", versionStatus(orderId, 2));
    }

    // ================================================================
    // 1c. Scope, tenant guard, auth and order-id gates
    // ================================================================

    @Test
    void createInvoice_otherStoreAndOtherBusinessSessions_return404OrderNotFound_scopeBeforeBody_nothingWritten()
            throws Exception {
        long orderId = acceptedQuoteOrder();
        String orderNumber = orderNumber(orderId);
        ensureMirrorSentinel(orderId);
        Map<String, Object> before = refusalState(orderId);

        // Same business, ANOTHER store the session user genuinely has access to (the guard passes).
        int otherStore = storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD);
        grantStoreAccess(BUSINESS_AUSSIE, USER_LIAM, otherStore);
        MockHttpSession otherStoreSession = session(USER_LIAM, BUSINESS_AUSSIE, otherStore);

        // Another business, through ITS OWN slug (the guard passes; the business-scoped lookup misses).
        String otherSlug = businessSlug(BUSINESS_PREMIER);
        int otherBusinessStore = storeInBusiness(BUSINESS_PREMIER, null);
        long otherBusinessUser = userInBusiness(BUSINESS_PREMIER);
        grantStoreAccess(BUSINESS_PREMIER, otherBusinessUser, otherBusinessStore);
        MockHttpSession otherBusinessSession = session(otherBusinessUser, BUSINESS_PREMIER, otherBusinessStore);

        // The scope check runs before the body: a forbidden field, a non-object and malformed JSON still 404.
        for (String body : new String[] {"{}", null, "{\"quote_version_id\":1}", "[]", "{bad"}) {
            String label = "body " + body;
            assertOrderNotFound(withBody(post(createInvoiceUrl(SLUG_AUSSIE, orderId)).session(otherStoreSession), body),
                    orderNumber, "other store, " + label);
            assertOrderNotFound(withBody(post(createInvoiceUrl(otherSlug, orderId)).session(otherBusinessSession), body),
                    orderNumber, "other business, " + label);
        }

        // No existence leak: the cross-store miss is byte-identical to a missing order's 404.
        String crossStore = bodyOf(perform(withBody(post(createInvoiceUrl(SLUG_AUSSIE, orderId))
                .session(otherStoreSession), "{}")));
        String missing = bodyOf(perform(withBody(post(createInvoiceUrl(SLUG_AUSSIE, MISSING_ORDER_ID))
                .session(otherStoreSession), "{}")));
        Assertions.assertEquals(missing, crossStore);

        Assertions.assertEquals(before, refusalState(orderId), "out-of-scope requests write nothing");

        // In scope the very same order converts: every 404 above was the scope alone.
        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
    }

    @Test
    void createInvoice_ordersOwnedByAnotherStoreOrBusiness_homeSession_return404OrderNotFound_evenWithABadBody()
            throws Exception {
        long otherStoreOrder = insertOrderIn(BUSINESS_AUSSIE,
                storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD), USER_LIAM, "LEAD");
        long otherBusinessOrder = insertOrderIn(BUSINESS_PREMIER,
                storeInBusiness(BUSINESS_PREMIER, null), userInBusiness(BUSINESS_PREMIER), "LEAD");

        for (long orderId : new long[] {otherStoreOrder, otherBusinessOrder}) {
            String orderNumber = orderNumber(orderId);
            ensureMirrorSentinel(orderId);
            Map<String, Object> before = refusalState(orderId);
            // Neither order has a quote: in scope these would be 422 QUOTE_NOT_ACCEPTED / 400; scope wins.
            for (String body : new String[] {"{}", "{\"due_date\":\"2026-12-01\"}", "{bad"}) {
                assertOrderNotFound(createInvoiceRequest(orderId, body), orderNumber,
                        "order " + orderId + " body " + body);
            }
            Assertions.assertEquals(before, refusalState(orderId), "nothing written for order " + orderId);
        }
    }

    @Test
    void createInvoice_otherBusinessSlugWithTheBusiness1Session_returns404NotFoundFromTheTenantGuard() throws Exception {
        long orderId = acceptedQuoteOrder();
        String orderNumber = orderNumber(orderId);
        ensureMirrorSentinel(orderId);
        Map<String, Object> before = refusalState(orderId);

        MvcResult result = perform(withBody(post(createInvoiceUrl(businessSlug(BUSINESS_PREMIER), orderId))
                .session(liamStore1Session()), "{}"));
        JsonNode error = assertError(result, 404, "NOT_FOUND", "Business not found.", "cross-business slug");
        Assertions.assertFalse(error.has("details"));
        Assertions.assertFalse(bodyOf(result).contains(orderNumber), "the tenant guard never echoes the order");
        Assertions.assertEquals(before, refusalState(orderId));
    }

    @Test
    void createInvoice_noSession401_noStore403_nonNumericZeroOrNegativeOrderId400_nothingWritten() throws Exception {
        long orderId = acceptedQuoteOrder();
        ensureMirrorSentinel(orderId);
        Map<String, Object> before = refusalState(orderId);

        MvcResult noSession = perform(withBody(post(createInvoiceUrl(SLUG_AUSSIE, orderId)), "{}"));
        assertError(noSession, 401, "UNAUTHORIZED", "Authentication required.", "no session");

        MvcResult noStore = perform(withBody(post(createInvoiceUrl(SLUG_AUSSIE, orderId))
                .session(liamSessionNoStore()), "{}"));
        assertError(noStore, 403, "FORBIDDEN", "Access denied.", "session without store_id");

        for (String badId : new String[] {"abc", "0", "-5"}) {
            MvcResult result = perform(withBody(post(createInvoiceUrl(SLUG_AUSSIE, badId))
                    .session(liamStore1Session()), "{}"));
            JsonNode error = assertError(result, 400, "VALIDATION_FAILED", VALIDATION_MESSAGE, "order id " + badId);
            Assertions.assertEquals(List.of(new Detail(null, "order_id", "Must be a positive integer.")),
                    details(error), "order id " + badId);
        }

        Assertions.assertEquals(before, refusalState(orderId));
    }

    // ================================================================
    // 1d. Body: empty only
    // ================================================================

    @Test
    void createInvoice_absentBody_isAccepted_201() throws Exception {
        long orderId = acceptedQuoteOrder();

        Conversion result = convertOk(orderId, null);
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
    }

    @Test
    void createInvoice_eachClientSuppliedField_returns400NotAllowed_nothingWritten_thenEmptyBodyConverts()
            throws Exception {
        long orderId = acceptedQuoteOrder();

        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("quote_version_id", "{\"quote_version_id\":1}");
        bodies.put("sale_price_inc_gst", "{\"sale_price_inc_gst\":1}");
        bodies.put("terms_html", "{\"terms_html\":\"x\"}");
        bodies.put("accepted_signature_file_id", "{\"accepted_signature_file_id\":1}");
        bodies.put("due_date", "{\"due_date\":\"2026-12-01\"}");
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            JsonNode error = assertRefused(orderId, entry.getValue(), 400, "VALIDATION_FAILED", VALIDATION_MESSAGE,
                    "field " + entry.getKey());
            Assertions.assertEquals(List.of(new Detail(null, entry.getKey(), "Not allowed.")), details(error),
                    "field " + entry.getKey());
        }

        // One detail per offending field.
        JsonNode two = assertRefused(orderId, "{\"quote_version_id\":1,\"due_date\":\"2026-12-01\"}", 400,
                "VALIDATION_FAILED", VALIDATION_MESSAGE, "two fields");
        List<Detail> twoDetails = details(two);
        Assertions.assertEquals(2, twoDetails.size());
        Assertions.assertEquals(Set.of(new Detail(null, "quote_version_id", "Not allowed."),
                new Detail(null, "due_date", "Not allowed.")), new HashSet<>(twoDetails));

        // The same order converts with "{}": every refusal above was the body alone.
        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
    }

    @Test
    void createInvoice_nonObjectBody400OnBody_malformedJson400_nothingWritten() throws Exception {
        long orderId = acceptedQuoteOrder();

        JsonNode array = assertRefused(orderId, "[]", 400, "VALIDATION_FAILED", VALIDATION_MESSAGE, "array body");
        Assertions.assertEquals(List.of(new Detail(null, "body", "Request body must be empty.")), details(array));

        JsonNode malformed = assertRefused(orderId, "{bad", 400, "MALFORMED_JSON", MALFORMED_MESSAGE, "malformed");
        Assertions.assertFalse(malformed.has("details"));

        Assertions.assertEquals(0, invoiceCount(orderId));
    }

    @Test
    void createInvoice_bodyValidationRunsBeforeTheQuoteNotAcceptedCheck() throws Exception {
        long orderId = invoiceReadyOrder();   // no quote at all: an empty body is 422 QUOTE_NOT_ACCEPTED

        assertRefused(orderId, "{}", 422, "QUOTE_NOT_ACCEPTED", QUOTE_NOT_ACCEPTED_MESSAGE, "baseline");
        JsonNode field = assertRefused(orderId, "{\"quote_version_id\":1}", 400, "VALIDATION_FAILED",
                VALIDATION_MESSAGE, "field before QUOTE_NOT_ACCEPTED");
        Assertions.assertEquals(List.of(new Detail(null, "quote_version_id", "Not allowed.")), details(field));
        JsonNode array = assertRefused(orderId, "[]", 400, "VALIDATION_FAILED", VALIDATION_MESSAGE,
                "array before QUOTE_NOT_ACCEPTED");
        Assertions.assertEquals(List.of(new Detail(null, "body", "Request body must be empty.")), details(array));
        assertRefused(orderId, "{bad", 400, "MALFORMED_JSON", MALFORMED_MESSAGE, "malformed before QUOTE_NOT_ACCEPTED");
    }

    // ================================================================
    // 1e. Gate-order spot checks
    // ================================================================

    @Test
    void createInvoice_signedCurrentInvoiceButNoAcceptedQuote_returns422QuoteNotAccepted_not409() throws Exception {
        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);
        signInvoiceInApp(orderId);
        Assertions.assertNotNull(invoiceRow(orderId, 2).get("accepted_at"), "fixture: the current invoice is signed");

        JsonNode error = assertRefused(orderId, "{}", 422, "QUOTE_NOT_ACCEPTED", QUOTE_NOT_ACCEPTED_MESSAGE,
                "signed invoice, no accepted quote");
        Assertions.assertFalse(error.has("details"));
    }

    @Test
    void createInvoice_signatureRefusalWithANullLayDate_returns409_beforeThePreconditions() throws Exception {
        long orderId = acceptedQuoteOrder();
        createInvoiceD1(orderId);
        signInvoiceInApp(orderId);                       // the invoice signature is later than the quote's
        jdbcTemplate.update("UPDATE sales_order SET proposed_lay_date = NULL, lay_date_status = NULL "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();

        JsonNode error = assertRefused(orderId, "{}", 409, "INVOICE_ALREADY_ACCEPTED", NOT_NEWER_MESSAGE,
                "D5(b) refusal with a null lay date");
        Assertions.assertFalse(error.has("details"), "the 409 fires before any precondition is collected");
    }

    @Test
    void createInvoice_missingBillingAddressOnAnOverpaidOrder_returns422Preconditions_beforeTheOverpaymentRule()
            throws Exception {
        long orderId = overpaidAcceptedOrder();
        jdbcTemplate.update("DELETE FROM order_address WHERE order_id = ? AND address_type = 'BILLING'::address_type",
                orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "missing billing on an overpaid order", BILLING);
    }

    // ================================================================
    // 2. D5(b) signature precedence against the CURRENT invoice
    // ================================================================

    @Test
    void createInvoice_noInvoice_createsVersion1FromTheSignedSnapshot_inheritingTheQuoteSignature() throws Exception {
        long orderId = acceptedQuoteOrder();
        String orderNumber = orderNumber(orderId);
        Map<String, Object> quote = quoteVersionRow(orderId, 1);
        Assertions.assertEquals(FROZEN_DETAILS, quote.get("details_of_sale_snapshot"));
        Assertions.assertEquals(FROZEN_TERMS, quote.get("terms_snapshot"), "fixture: the issue froze the tenant terms");
        Map<String, Object> signatureFileBefore = storedFileRow(quote.get("accepted_signature_file_id"));
        byte[] quoteSignature = diskBytes((String) signatureFileBefore.get("storage_path"));

        // LIVE state moves on after the signature: only the Invoice To block may follow it.
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'Live details edited after acceptance', "
                + "price_adjustment_inc_gst = 99.00 WHERE order_id = ?", orderId);
        jdbcTemplate.update("UPDATE order_customer SET first_name = 'Renamed' WHERE order_id = ?", orderId);
        setTenantTerms(LIVE_TERMS);

        Assertions.assertEquals(0, invoiceCount(orderId));
        Assertions.assertTrue(invoiceEligible(orderId), "no invoice: eligible");

        Conversion result = convertOk(orderId);
        JsonNode invoice = assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals(FROZEN_DETAILS, invoice.get("details_of_sale_snapshot").asText());
        Assertions.assertEquals(FROZEN_TERMS, invoice.get("terms_html").asText(), "the frozen quote terms, never live");

        // The inherited signature is the SAME stored_file row and the same bytes on disk (no copy).
        Assertions.assertEquals(signatureFileBefore, storedFileRow(quote.get("accepted_signature_file_id")));
        Assertions.assertArrayEquals(quoteSignature, diskBytes((String) signatureFileBefore.get("storage_path")));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND mime_type = 'image/png'",
                Integer.class, orderStoragePrefix(orderId) + "%"), "no new signature file was stored");

        // PDF: Invoice To follows the LIVE customer + billing rows; acceptance, details and terms are frozen.
        byte[] pdf = currentInvoicePdf(orderId);
        String text = noSpace(pdfText(pdf));
        Assertions.assertTrue(text.contains("RenamedTester"), "Invoice To is the live customer");
        Assertions.assertTrue(text.contains("12BillingStreet"), "Invoice To is the live billing address");
        Assertions.assertFalse(text.contains(noSpace("Live details edited after acceptance")));
        Assertions.assertTrue(text.contains(noSpace(FROZEN_TERMS_TEXT)), "the frozen terms are rendered");
        Assertions.assertFalse(text.contains(noSpace(LIVE_TERMS_TEXT)), "the live terms are never rendered");
        Assertions.assertEquals(2, pageCount(pdf), "the frozen terms render on a dedicated page 2");
        Assertions.assertFalse(pageText(pdf, 1).contains("TERMS"), "page 1 never carries the terms");
        Assertions.assertTrue(pageText(pdf, 2).contains("TERMS"), "page 2 carries the terms heading");
        Assertions.assertEquals(1, countOccurrences(text, noSpace("Generated by the Flooring Sales Portal")));

        // D.3 / D.4 / D.10 resolve the Path A version.
        MvcResult d3 = perform(get(invoicesUrl(orderId) + "/current").session(liamStore1Session()));
        assertStatus(d3, 200, "D.3 current invoice");
        Assertions.assertEquals(invoice, readJson(d3).get("data").get("invoice"), "D.3 reads the same InvoiceDetail");
        MvcResult d4 = perform(get(pdfDownloadPath(orderId)).session(liamStore1Session()));
        assertStatus(d4, 200, "D.4 current invoice file");
        Assertions.assertArrayEquals(pdf, d4.getResponse().getContentAsByteArray(), "D.4 streams the stored PDF");
        assertInlineFilename(d4, "invoice-" + orderNumber + "-v1.pdf");
        MvcResult d10 = perform(get(signatureDownloadPath(orderId)).session(liamStore1Session()));
        assertStatus(d10, 200, "D.10 current signature");
        Assertions.assertArrayEquals(quoteSignature, d10.getResponse().getContentAsByteArray(),
                "D.10 streams the inherited quote signature file verbatim");
        assertInlineFilename(d10, "signature-" + orderNumber + "-v1.png");

        Assertions.assertFalse(invoiceEligible(orderId), "the converted invoice ties the quote signature");
    }

    @Test
    void createInvoice_unsignedCurrentInvoice_appendsVersion2_earlierVersionAndPdfUntouched() throws Exception {
        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);                                  // unsigned v1 at the live 110.00
        Map<String, Object> v1 = invoiceRow(orderId, 1);
        assertMoney("110.00", v1.get("sale_price_inc_gst"), "fixture: D.1 billed the live price");
        Assertions.assertNull(v1.get("accepted_at"));
        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);
        Assertions.assertTrue(invoiceEligible(orderId), "an unsigned current invoice: eligible");

        Conversion result = convertOk(orderId);                    // also proves v1 + its PDF are untouched
        assertConverted(orderId, result, 2, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals(v1, invoiceRow(orderId, 1));
        Assertions.assertFalse(invoiceEligible(orderId));
    }

    @Test
    void createInvoice_signedInvoiceThenAStrictlyNewerQuoteSignature_appendsVersion3_mirrorResetFromTheEmailedValue()
            throws Exception {
        clearBusinessLogo();                                       // a signature is then the only image in a PDF
        // Two signatures of DIFFERENT sizes: 3 x 2 signed in-app (D.8), 7 x 3 signed on the public quote page.
        BufferedImage inAppSignature = decodePng(SECOND_SIGNATURE_PNG, "in-app signature upload");
        byte[] quoteSignatureUpload = distinctiveSignaturePng();
        BufferedImage quoteSignatureUploaded = decodePng(quoteSignatureUpload, "quote signature upload");
        List<Integer> inAppSize = List.of(inAppSignature.getWidth(), inAppSignature.getHeight());
        Assertions.assertEquals(List.of(3, 2), inAppSize, "fixture: the in-app signature is 3 x 2");
        Assertions.assertEquals(List.of(7, 3), List.of(quoteSignatureUploaded.getWidth(),
                quoteSignatureUploaded.getHeight()), "fixture: the quote signature is 7 x 3");

        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);
        signInvoiceInApp(orderId, SECOND_SIGNATURE_PNG, IN_APP_SIGNER_NAME);   // v2 signed (and auto-emailed) FIRST
        Timestamp invoiceSignedAt = (Timestamp) invoiceRow(orderId, 2).get("accepted_at");
        Assertions.assertNotNull(invoiceSignedAt);
        Assertions.assertNotNull(mirror(orderId), "fixture: the D.8 auto-email stamped the mirror");
        // Fixture: the replaced v2 PDF embeds the 3 x 2 in-app signature and names the in-app signer.
        byte[] replacedPdf = invoicePdfsByVersion(orderId).get(2);
        List<EmbeddedImage> replacedImages = embeddedImages(replacedPdf);
        Assertions.assertEquals(1, replacedImages.size(), "fixture: the D.8 PDF embeds exactly one image");
        assertSamePixels(inAppSignature, replacedImages.get(0).pixels(), "fixture: the D.8 PDF signature");
        Assertions.assertTrue(noSpace(pdfTextByPosition(replacedPdf))
                        .contains(noSpace("Accepted by " + IN_APP_SIGNER_NAME)),
                "fixture: the D.8 PDF names the in-app signer");

        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), quoteSignatureUpload);
        Map<String, Object> quote = quoteVersionRow(orderId, 1);
        Timestamp quoteSignedAt = (Timestamp) quote.get("accepted_at");
        Assertions.assertTrue(quoteSignedAt.after(invoiceSignedAt), "fixture: the quote signature is strictly newer");
        Assertions.assertTrue(invoiceEligible(orderId), "a strictly newer quote signature: eligible");
        BufferedImage quoteSignature = decodePng(
                diskBytes((String) storedFileRow(quote.get("accepted_signature_file_id")).get("storage_path")),
                "stored quote signature");
        assertSamePixels(quoteSignatureUploaded, quoteSignature, "fixture: the stored (normalised) quote signature");

        Conversion result = convertOk(orderId);                    // asserts the mirror is reset to null
        assertConverted(orderId, result, 3, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertFalse(invoiceEligible(orderId));

        // The Path A PDF embeds exactly the QUOTE's stored signature, never the replaced in-app one.
        byte[] pdf = currentInvoicePdf(orderId);
        List<EmbeddedImage> images = embeddedImages(pdf);
        Assertions.assertEquals(1, images.size(), "the inherited quote signature is the only image in the Path A PDF");
        EmbeddedImage embedded = images.get(0);
        Assertions.assertEquals(quoteSignature.getWidth(), embedded.width(),
                "image XObject width = the quote signature's");
        Assertions.assertEquals(quoteSignature.getHeight(), embedded.height(),
                "image XObject height = the quote signature's");
        Assertions.assertNotEquals(inAppSize, List.of(embedded.width(), embedded.height()),
                "never the replaced in-app signature's size");
        assertSamePixels(quoteSignature, embedded.pixels(), "the Path A PDF signature");
        Assertions.assertFalse(noSpace(pdfTextByPosition(pdf)).contains(noSpace(IN_APP_SIGNER_NAME)),
                "the replaced in-app signer is never named on the Path A PDF");
    }

    @Test
    void createInvoice_quoteSignedBeforeTheCurrentInvoiceSignature_returns409_nothingAppended() throws Exception {
        long orderId = acceptedQuoteOrder();                      // the quote is signed FIRST
        createInvoiceD1(orderId);
        signInvoiceInApp(orderId);                                 // then the invoice (v2), later
        Timestamp quoteSignedAt = (Timestamp) quoteVersionRow(orderId, 1).get("accepted_at");
        Timestamp invoiceSignedAt = (Timestamp) invoiceRow(orderId, 2).get("accepted_at");
        Assertions.assertTrue(invoiceSignedAt.after(quoteSignedAt), "fixture: the invoice signature is newer");
        Assertions.assertFalse(invoiceEligible(orderId), "an older quote signature: ineligible");

        JsonNode error = assertRefused(orderId, "{}", 409, "INVOICE_ALREADY_ACCEPTED", NOT_NEWER_MESSAGE,
                "older quote signature");
        Assertions.assertFalse(error.has("details"));
        Assertions.assertEquals(2, invoiceCount(orderId));
        Assertions.assertFalse(invoiceEligible(orderId));
    }

    @Test
    void createInvoice_quoteSignatureEqualToTheCurrentInvoiceSignature_returns409_nothingAppended() throws Exception {
        long orderId = acceptedQuoteOrder();
        createInvoiceD1(orderId);
        signInvoiceInApp(orderId);
        long quoteVersionId = asLong(quoteVersionRow(orderId, 1).get("quote_version_id"));
        int tied = jdbcTemplate.update("UPDATE invoice SET accepted_at = "
                        + "(SELECT accepted_at FROM quote_version WHERE quote_version_id = ?) "
                        + "WHERE order_id = ? AND version_number = 2", quoteVersionId, orderId);
        Assertions.assertEquals(1, tied);
        clearJpaCache();
        Assertions.assertEquals(quoteVersionRow(orderId, 1).get("accepted_at"), invoiceRow(orderId, 2).get("accepted_at"),
                "fixture: identical signature timestamps");
        Assertions.assertFalse(invoiceEligible(orderId), "a tie: ineligible");

        JsonNode error = assertRefused(orderId, "{}", 409, "INVOICE_ALREADY_ACCEPTED", NOT_NEWER_MESSAGE,
                "equal signatures");
        Assertions.assertFalse(error.has("details"));
        Assertions.assertEquals(2, invoiceCount(orderId));
        Assertions.assertFalse(invoiceEligible(orderId));
    }

    @Test
    void createInvoice_paymentVersionNewerByCreationButOlderBySignature_allowsVersion4_activePaymentsCarried()
            throws Exception {
        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);                                  // v1 unsigned, 110.00
        signInvoiceInApp(orderId);                                 // v2 signed at T1
        Timestamp invoiceSignedAt = (Timestamp) invoiceRow(orderId, 2).get("accepted_at");
        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);   // quote signed at T2 > T1
        Map<String, Object> quote = quoteVersionRow(orderId, 1);
        Timestamp quoteSignedAt = (Timestamp) quote.get("accepted_at");
        recordPayment(orderId, "50.00");                            // v3 carries T1

        // The test transaction makes every created_at default the transaction START (PostgreSQL now()),
        // which is older than both JVM-stamped signatures. Move v3's creation time to one second after the
        // quote signature, so v3 is genuinely NEWER by creation while still OLDER by signature.
        int moved = jdbcTemplate.update("UPDATE invoice SET created_at = "
                        + "(SELECT accepted_at FROM quote_version WHERE quote_version_id = ?) + INTERVAL '1 second' "
                        + "WHERE order_id = ? AND version_number = 3",
                asLong(quote.get("quote_version_id")), orderId);
        Assertions.assertEquals(1, moved, "fixture: exactly the payment version's creation time moved");
        clearJpaCache();
        Map<String, Object> v3 = invoiceRow(orderId, 3);
        Timestamp v3CreatedAt = (Timestamp) v3.get("created_at");
        Timestamp v3SignedAt = (Timestamp) v3.get("accepted_at");
        Timestamp quoteSignedAtInDb = (Timestamp) quoteVersionRow(orderId, 1).get("accepted_at");
        Assertions.assertEquals(quoteSignedAt, quoteSignedAtInDb, "fixture: the quote signature time is unchanged");
        Assertions.assertEquals(invoiceSignedAt, v3SignedAt, "the payment version carries T1");
        Assertions.assertTrue(v3CreatedAt.after(quoteSignedAtInDb),
                () -> "fixture: v3 is created after the quote signature: " + v3CreatedAt + " vs " + quoteSignedAtInDb);
        Assertions.assertTrue(quoteSignedAtInDb.after(v3SignedAt),
                () -> "fixture: the quote signature is newer than v3's: " + quoteSignedAtInDb + " vs " + v3SignedAt);
        Assertions.assertTrue(invoiceEligible(orderId), "signature times are compared, never creation times");

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 4, 1, "250.00", "275.00", "50.00", "225.00");
        Assertions.assertFalse(invoiceEligible(orderId));
    }

    @Test
    void createInvoice_signedInvoiceResentAfterTheQuoteSignature_emailTimeIsNeverCompared_appendsVersion3()
            throws Exception {
        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);                                  // v1 unsigned
        signInvoiceInApp(orderId);                                 // v2 signed at T1 (and auto-emailed)
        Map<String, Object> signed = invoiceRow(orderId, 2);
        Timestamp invoiceSignedAt = (Timestamp) signed.get("accepted_at");
        Timestamp emailedByD8 = (Timestamp) signed.get("last_emailed_at");
        Assertions.assertNotNull(invoiceSignedAt);
        Assertions.assertNotNull(emailedByD8, "fixture: the D.8 auto-email stamped v2");
        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);   // quote signed at T2 > T1
        Timestamp quoteSignedAt = (Timestamp) quoteVersionRow(orderId, 1).get("accepted_at");

        resendInvoice(orderId);                                    // D.9 AFTER the quote signature: T3 > T2
        Map<String, Object> resent = invoiceRow(orderId, 2);
        Timestamp resentAt = (Timestamp) resent.get("last_emailed_at");
        Assertions.assertEquals(2, invoiceCount(orderId), "fixture: D.9 appends no version");
        Assertions.assertEquals(invoiceSignedAt, resent.get("accepted_at"), "fixture: D.9 keeps the signature time T1");
        Assertions.assertTrue(quoteSignedAt.after(invoiceSignedAt),
                () -> "fixture: the quote signature is strictly newer: " + quoteSignedAt + " vs " + invoiceSignedAt);
        Assertions.assertNotNull(resentAt, "fixture: D.9 stamped v2");
        Assertions.assertTrue(resentAt.after(emailedByD8), "fixture: D.9 re-stamped the email time");
        Assertions.assertTrue(resentAt.after(quoteSignedAt),
                () -> "fixture: the invoice was emailed AFTER the quote signature: "
                        + resentAt + " vs " + quoteSignedAt);
        Assertions.assertEquals(resentAt, mirror(orderId), "fixture: the mirror carries the D.9 stamp");
        Assertions.assertTrue(invoiceEligible(orderId), "email times are never compared: eligible");

        Conversion result = convertOk(orderId);                    // the D.9-stamped mirror is reset to null
        assertConverted(orderId, result, 3, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertFalse(invoiceEligible(orderId));
    }

    @Test
    void createInvoice_convertingTheSameQuoteTwice_secondReturns409_andD8KeepsItsOwnConflictMessage() throws Exception {
        long orderId = acceptedQuoteOrder();
        Conversion first = convertOk(orderId);
        assertConverted(orderId, first, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals(quoteVersionRow(orderId, 1).get("accepted_at"), invoiceRow(orderId, 1).get("accepted_at"),
                "the conversion copied the quote signature time");
        Assertions.assertFalse(invoiceEligible(orderId), "a tie after the first conversion: ineligible");

        JsonNode error = assertRefused(orderId, "{}", 409, "INVOICE_ALREADY_ACCEPTED", NOT_NEWER_MESSAGE,
                "second conversion");
        Assertions.assertFalse(error.has("details"));
        Assertions.assertEquals(1, invoiceCount(orderId));

        // D.8 on the converted (already signed) invoice keeps its own unchanged 409 wording.
        Map<String, Object> before = refusalState(orderId);
        MvcResult d8 = perform(signInvoiceRequest(orderId));
        assertError(d8, 409, "INVOICE_ALREADY_ACCEPTED", D8_ALREADY_ACCEPTED_MESSAGE, "D.8 on a Path A invoice");
        Assertions.assertEquals(before, refusalState(orderId));
    }

    // ================================================================
    // 3. Mapped sources proven with discriminating fixtures (due_date, created_by_user_id)
    // ================================================================

    @Test
    void createInvoice_layDateMovedAfterD1AndAgainAfterTheAcceptance_dueDateFollowsTheCurrentLayDate_v1KeepsItsOwn()
            throws Exception {
        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);                                  // v1 due two days before 2026-12-01
        Map<String, Object> v1 = invoiceRow(orderId, 1);
        Assertions.assertEquals(EXPECTED_DUE_DATE, ((Date) v1.get("due_date")).toLocalDate(), "fixture: v1's due date");
        setProposedLayDate(orderId, LAY_DATE_AT_ISSUE);            // in force while the quote is issued and signed
        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);
        setProposedLayDate(orderId, CURRENT_LAY_DATE);             // moved again AFTER the acceptance
        Assertions.assertTrue(invoiceEligible(orderId), "an unsigned current invoice: eligible");

        // The due date is the CURRENT lay date minus 2: never the seeded lay date, the lay date at issue or
        // acceptance, or the current invoice's due date.
        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 2, 1, "250.00", "275.00", "0.00", "275.00", CURRENT_LAY_DATE_DUE, USER_LIAM);
        Assertions.assertEquals(CURRENT_LAY_DATE_DUE, CURRENT_LAY_DATE.minusDays(2), "fixture: the expected due date");
        Assertions.assertNotEquals(LAY_DATE_AT_ISSUE.minusDays(2), CURRENT_LAY_DATE_DUE,
                "fixture: the lay date at issue / acceptance gives another due date");

        Map<String, Object> v1After = invoiceRow(orderId, 1);
        Assertions.assertEquals(EXPECTED_DUE_DATE, ((Date) v1After.get("due_date")).toLocalDate(),
                "the earlier version keeps its own due date");
        Assertions.assertEquals(v1, v1After, "the earlier version row is unchanged");
    }

    @Test
    void createInvoice_convertedByASecondBusiness1User_createdByIsTheSessionUser_notTheOrderOwnerOrQuoteIssuer()
            throws Exception {
        long orderId = acceptedQuoteOrder();                      // order inserted and quote issued as Liam
        long orderOwner = jdbcTemplate.queryForObject(
                "SELECT user_id FROM sales_order WHERE order_id = ?", Long.class, orderId);
        long quoteIssuer = asLong(quoteVersionRow(orderId, 1).get("created_by_user_id"));
        Assertions.assertEquals(USER_LIAM, orderOwner, "fixture: Liam owns the order");
        Assertions.assertEquals(USER_LIAM, quoteIssuer, "fixture: Liam issued the quote");
        long secondUser = otherUserInBusiness(BUSINESS_AUSSIE, USER_LIAM);
        Assertions.assertNotEquals(USER_LIAM, secondUser, "fixture: a different business-1 user");
        grantStoreAccess(BUSINESS_AUSSIE, secondUser, STORE_SYD_CBD);

        Conversion result = convertOk(orderId, "{}", session(secondUser, BUSINESS_AUSSIE, STORE_SYD_CBD));
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00", EXPECTED_DUE_DATE, secondUser);

        long createdBy = asLong(invoiceRow(orderId, 1).get("created_by_user_id"));
        Assertions.assertNotEquals(orderOwner, createdBy, "created_by_user_id is never the order owner");
        Assertions.assertNotEquals(quoteIssuer, createdBy, "created_by_user_id is never the quote issuer");
        Assertions.assertEquals(USER_LIAM, jdbcTemplate.queryForObject(
                "SELECT user_id FROM sales_order WHERE order_id = ?", Long.class, orderId),
                "the order owner is unchanged");
    }

    // ================================================================
    // 4. Path A preconditions (live rows broken AFTER a real acceptance)
    // ================================================================

    @Test
    void createInvoice_customerRowDeletedAfterAcceptance_reportsFirstAndLastName() throws Exception {
        long orderId = acceptedQuoteOrder();
        jdbcTemplate.update("DELETE FROM order_customer WHERE order_id = ?", orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "no customer row", FIRST_NAME, LAST_NAME);
    }

    @Test
    void createInvoice_installationAddressDeleted_reportsInstallationAddressOnly() throws Exception {
        long orderId = acceptedQuoteOrder();
        jdbcTemplate.update("DELETE FROM order_address WHERE order_id = ? "
                + "AND address_type = 'INSTALLATION'::address_type", orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "no installation address", INSTALLATION);
    }

    @Test
    void createInvoice_billingAddressDeleted_reportsBillingAddressOnly() throws Exception {
        long orderId = acceptedQuoteOrder();
        jdbcTemplate.update("DELETE FROM order_address WHERE order_id = ? AND address_type = 'BILLING'::address_type",
                orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "no billing address", BILLING);
    }

    @Test
    void createInvoice_proposedLayDateNull_reportsProposedLayDateOnly() throws Exception {
        long orderId = acceptedQuoteOrder();
        // chk_sales_order_lay_date_pair forbids a lone null; relaxed in-transaction (rolled back).
        relaxLayDatePairConstraint();
        jdbcTemplate.update("UPDATE sales_order SET proposed_lay_date = NULL WHERE order_id = ?", orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "no proposed lay date", LAY_DATE);
    }

    @Test
    void createInvoice_layDateStatusNull_reportsLayDateStatusOnly() throws Exception {
        long orderId = acceptedQuoteOrder();
        relaxLayDatePairConstraint();
        jdbcTemplate.update("UPDATE sales_order SET lay_date_status = NULL WHERE order_id = ?", orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "no lay date status", LAY_DATE_STATUS);
    }

    @Test
    void createInvoice_allSixLiveChecksBrokenPlusFrozenDetailsNull_reportsExactlySevenDetails() throws Exception {
        long orderId = acceptedQuoteOrder();
        jdbcTemplate.update("DELETE FROM order_customer WHERE order_id = ?", orderId);
        jdbcTemplate.update("DELETE FROM order_address WHERE order_id = ?", orderId);
        jdbcTemplate.update("UPDATE sales_order SET proposed_lay_date = NULL, lay_date_status = NULL "
                + "WHERE order_id = ?", orderId);
        jdbcTemplate.update("UPDATE quote_version SET details_of_sale_snapshot = NULL "
                + "WHERE order_id = ? AND version_number = 1", orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "seven failures",
                FIRST_NAME, LAST_NAME, INSTALLATION, BILLING, QUOTE_DETAILS, LAY_DATE, LAY_DATE_STATUS);
    }

    @Test
    void createInvoice_detailsNullWhenTheQuoteWasIssued_reportsQuoteDetails_evenWithValidLiveDetails() throws Exception {
        long orderId = invoiceReadyOrder();
        setDetailsOfSale(orderId, null);                           // nothing to freeze at issue
        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);
        Assertions.assertNull(quoteVersionRow(orderId, 1).get("details_of_sale_snapshot"),
                "fixture: the frozen details are null");
        setDetailsOfSale(orderId, "Valid live details written after the acceptance");

        assertPreconditionsRefused(orderId, "frozen details null at issue", QUOTE_DETAILS);
    }

    @Test
    void createInvoice_frozenDetailsWhitespaceOnly_reportsQuoteDetails() throws Exception {
        long orderId = acceptedQuoteOrder();
        jdbcTemplate.update("UPDATE quote_version SET details_of_sale_snapshot = '   ' "
                + "WHERE order_id = ? AND version_number = 1", orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "frozen details blank", QUOTE_DETAILS);
    }

    @Test
    void createInvoice_zeroTotalQuoteAcceptedWithoutCostLines_reportsBothFinancialDetails_evenWithAPositiveLivePrice()
            throws Exception {
        long orderId = invoiceReadyOrderWithoutLines();            // no cost line: a 0 quote is not below cost
        saveNonItemisedDraft(orderId, "0.00");
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);
        Map<String, Object> quote = quoteVersionRow(orderId, 1);
        assertMoney("0.00", quote.get("quote_total_ex_gst"), "fixture: frozen ex total");
        assertMoney("0.00", quote.get("quote_total_inc_gst"), "fixture: frozen inc total");

        // A priced line after the acceptance: the LIVE price is now positive (110.00 + a 0.00 adjustment).
        seedChargeLine(orderId, "100.00", "40.00");
        BigDecimal adjustment = jdbcTemplate.queryForObject(
                "SELECT price_adjustment_inc_gst FROM sales_order WHERE order_id = ?", BigDecimal.class, orderId);
        Assertions.assertTrue(adjustment == null || adjustment.signum() == 0,
                () -> "fixture: the D6b adjustment for a 0.00 quote over no lines is zero, was " + adjustment);

        assertPreconditionsRefused(orderId, "zero quote totals", QUOTE_EX, QUOTE_INC);
    }

    @Test
    void createInvoice_onlyTheFrozenExTotalZero_reportsOnlyTheExDetail() throws Exception {
        long orderId = acceptedQuoteOrder();
        jdbcTemplate.update("UPDATE quote_version SET quote_total_ex_gst = 0.00 "
                + "WHERE order_id = ? AND version_number = 1", orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "ex total zero", QUOTE_EX);
    }

    @Test
    void createInvoice_onlyTheFrozenIncTotalZero_reportsOnlyTheIncDetail() throws Exception {
        long orderId = acceptedQuoteOrder();
        jdbcTemplate.update("UPDATE quote_version SET quote_total_inc_gst = 0.00 "
                + "WHERE order_id = ? AND version_number = 1", orderId);
        clearJpaCache();

        assertPreconditionsRefused(orderId, "inc total zero", QUOTE_INC);
    }

    @Test
    void createInvoice_noLiveLinesNonPositiveLivePriceAndNullLiveDetails_stillConvertsFromTheFrozenSnapshot()
            throws Exception {
        long orderId = acceptedQuoteOrder();
        jdbcTemplate.update("DELETE FROM order_charge_line WHERE order_id = ?", orderId);
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = -50.00, details_of_sale = NULL "
                + "WHERE order_id = ?", orderId);
        clearJpaCache();
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_product_line WHERE order_id = ?", Integer.class, orderId)
                + jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_charge_line WHERE order_id = ?", Integer.class, orderId),
                "fixture: no live priced line, so the live final price is the -50.00 adjustment");

        Conversion result = convertOk(orderId);
        JsonNode invoice = assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals(FROZEN_DETAILS, invoice.get("details_of_sale_snapshot").asText(),
                "the frozen details, although the live details are null");
    }

    @Test
    void createInvoice_customerEmailNull_neverBlocks_andNothingIsEmailed() throws Exception {
        long orderId = acceptedQuoteOrder();
        relaxCustomerEmailConstraints();
        jdbcTemplate.update("UPDATE order_customer SET email = NULL WHERE order_id = ?", orderId);
        clearJpaCache();

        Conversion result = convertOk(orderId);                    // also asserts every sender stayed silent
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "no invoice email");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no invoice email attempt");
    }

    @Test
    void createInvoice_customerEmailInvalid_neverBlocks_andNothingIsEmailed() throws Exception {
        long orderId = acceptedQuoteOrder();
        relaxCustomerEmailConstraints();
        jdbcTemplate.update("UPDATE order_customer SET email = 'not-an-email' WHERE order_id = ?", orderId);
        clearJpaCache();

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "no invoice email");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no invoice email attempt");
    }

    // ================================================================
    // 5. Payments
    // ================================================================

    @Test
    void createInvoice_paymentRecordedBeforeTheAcceptance_isCarried_balanceIsSignedTotalMinusPayments()
            throws Exception {
        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);                                  // v1 at the live 110.00
        assertMoney("110.00", invoiceRow(orderId, 1).get("sale_price_inc_gst"), "fixture: live price billed");
        recordPayment(orderId, "50.00");                           // v2
        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);

        Conversion result = convertOk(orderId);                    // payments are in the untouched state
        assertConverted(orderId, result, 3, 1, "250.00", "275.00", "50.00", "225.00");
    }

    @Test
    void createInvoice_voidedPaymentIsExcludedFromTotalPaid() throws Exception {
        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);                                  // v1 110.00
        recordPayment(orderId, "50.00");                           // v2
        long voided = recordPayment(orderId, "30.00");             // v3
        voidPayment(orderId, voided);                              // v4
        Assertions.assertNotNull(jdbcTemplate.queryForObject(
                "SELECT voided_at FROM payment_transaction WHERE payment_transaction_id = ?", Timestamp.class, voided));
        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 5, 1, "250.00", "275.00", "50.00", "225.00");
    }

    @Test
    void createInvoice_paymentsExactlyEqualToTheSignedTotal_convert201_withZeroBalance() throws Exception {
        long orderId = invoiceReadyOrder();                        // 100.00 ex line
        seedChargeLine(orderId, "200.00", "80.00");                 // live 300.00 ex / 330.00 inc
        createInvoiceD1(orderId);
        assertMoney("330.00", invoiceRow(orderId, 1).get("sale_price_inc_gst"), "fixture: D.1 billed 330.00");
        recordPayment(orderId, "200.00");                          // v2
        recordPayment(orderId, "75.00");                           // v3: 275.00 paid in total
        saveItemisedTwoLineDraft(orderId);                         // 275.00 inc, above the 120.00 cost
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 4, 1, "250.00", "275.00", "275.00", "0.00");
    }

    @Test
    void createInvoice_activePaymentsExceedTheSignedTotal_returns422BusinessRuleViolation_nothingWritten()
            throws Exception {
        long orderId = overpaidAcceptedOrder();
        Assertions.assertTrue(invoiceEligible(orderId), "eligibility never looks at payments");

        JsonNode error = assertRefused(orderId, "{}", 422, "BUSINESS_RULE_VIOLATION", PAYMENTS_EXCEED_MESSAGE,
                "overpaid");
        Assertions.assertFalse(error.has("details"));
        Assertions.assertEquals(2, invoiceCount(orderId), "no new version");
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transaction WHERE order_id = ? AND voided_at IS NULL",
                Integer.class, orderId), "the payment stays recorded and active");
    }

    // ================================================================
    // 6. LAID: no LAID gate in any allowed branch; refusals still apply; D.2 stays locked
    // ================================================================

    @Test
    void createInvoice_laidOrderWithNoInvoice_converts201_statusStaysLaid_rewriteStaysLocked() throws Exception {
        long orderId = invoiceReadyOrder();
        saveItemisedTwoLineDraft(orderId);
        String token = sendAndExtractToken(orderId);
        setLaid(orderId);
        acceptPublicly(token, ONE_PIXEL_PNG);                      // public accept is allowed on LAID
        Assertions.assertTrue(invoiceEligible(orderId), "LAID never feeds eligibility");

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 1, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals("LAID", orderStatus(orderId));
        assertRewriteLocked(orderId);
    }

    @Test
    void createInvoice_laidOrderWithAnUnsignedInvoice_converts201() throws Exception {
        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);
        saveItemisedTwoLineDraft(orderId);
        String token = sendAndExtractToken(orderId);
        setLaid(orderId);
        acceptPublicly(token, ONE_PIXEL_PNG);

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 2, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals("LAID", orderStatus(orderId));
    }

    @Test
    void createInvoice_laidOrderWithASignedInvoiceAndANewerQuote_converts201() throws Exception {
        long orderId = invoiceReadyOrder();
        createInvoiceD1(orderId);
        signInvoiceInApp(orderId);
        saveItemisedTwoLineDraft(orderId);
        String token = sendAndExtractToken(orderId);
        setLaid(orderId);
        acceptPublicly(token, ONE_PIXEL_PNG);
        Assertions.assertTrue(invoiceEligible(orderId));

        Conversion result = convertOk(orderId);
        assertConverted(orderId, result, 3, 1, "250.00", "275.00", "0.00", "275.00");
        Assertions.assertEquals("LAID", orderStatus(orderId));
    }

    @Test
    void createInvoice_laidOrderWithAQuoteOlderThanTheSignedInvoice_returns409_andRewriteStaysLocked() throws Exception {
        long orderId = invoiceReadyOrder();
        saveItemisedTwoLineDraft(orderId);
        String token = sendAndExtractToken(orderId);
        setLaid(orderId);
        acceptPublicly(token, ONE_PIXEL_PNG);                      // the quote is signed first
        createInvoiceD1(orderId);                                  // D.1 is allowed on LAID with no invoice
        signInvoiceInApp(orderId);                                 // D.8 is allowed on LAID; signed later
        Assertions.assertFalse(invoiceEligible(orderId));

        assertRefused(orderId, "{}", 409, "INVOICE_ALREADY_ACCEPTED", NOT_NEWER_MESSAGE, "LAID, older quote");
        Assertions.assertEquals(2, invoiceCount(orderId));
        assertRewriteLocked(orderId);
    }

    @Test
    void createInvoice_laidOrderWithEqualSignatures_returns409_andRewriteStaysLocked() throws Exception {
        long orderId = invoiceReadyOrder();
        saveItemisedTwoLineDraft(orderId);
        String token = sendAndExtractToken(orderId);
        setLaid(orderId);
        acceptPublicly(token, ONE_PIXEL_PNG);
        createInvoiceD1(orderId);
        signInvoiceInApp(orderId);
        long quoteVersionId = asLong(quoteVersionRow(orderId, 1).get("quote_version_id"));
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice SET accepted_at = "
                + "(SELECT accepted_at FROM quote_version WHERE quote_version_id = ?) "
                + "WHERE order_id = ? AND version_number = 2", quoteVersionId, orderId));
        clearJpaCache();
        Assertions.assertFalse(invoiceEligible(orderId));

        assertRefused(orderId, "{}", 409, "INVOICE_ALREADY_ACCEPTED", NOT_NEWER_MESSAGE, "LAID, equal signatures");
        Assertions.assertEquals(2, invoiceCount(orderId));
        assertRewriteLocked(orderId);
    }

    // ================================================================
    // Helpers: sessions, URLs, requests
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

    private static String orderUrl(String slug, Object orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId;
    }

    private static String createInvoiceUrl(String slug, Object orderId) {
        return orderUrl(slug, orderId) + "/quote/create-invoice";
    }

    private static String quoteUrl(Object orderId, String suffix) {
        return orderUrl(SLUG_AUSSIE, orderId) + "/quote/" + suffix;
    }

    private static String invoicesUrl(Object orderId) {
        return orderUrl(SLUG_AUSSIE, orderId) + "/invoices";
    }

    private static String paymentsUrl(Object orderId) {
        return orderUrl(SLUG_AUSSIE, orderId) + "/payments";
    }

    private static String publicQuoteUrl(String token) {
        return "/api/v1/public/quotes/" + token;
    }

    private static String pdfDownloadPath(long orderId) {
        return invoicesUrl(orderId) + "/current/file";
    }

    private static String signatureDownloadPath(long orderId) {
        return invoicesUrl(orderId) + "/current/signature";
    }

    private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, String body) {
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** The Path A request for Liam / business 1 / store 1; a null body sends no body at all. */
    private static MockHttpServletRequestBuilder createInvoiceRequest(long orderId, String body) {
        return withBody(post(createInvoiceUrl(SLUG_AUSSIE, orderId)).session(liamStore1Session()), body);
    }

    private static RequestBuilder signInvoiceRequest(long orderId) {
        return signInvoiceRequest(orderId, ONE_PIXEL_PNG, SNAPSHOT_NAME);
    }

    /** D.8 multipart: one PNG {@code signature} part plus the typed {@code accepted_customer_name}. */
    private static RequestBuilder signInvoiceRequest(long orderId, byte[] signaturePng, String acceptedName) {
        return multipart(invoicesUrl(orderId) + "/current/accept")
                .file(signaturePart(signaturePng))
                .param("accepted_customer_name", acceptedName)
                .session(liamStore1Session());
    }

    private static MockMultipartFile signaturePart(byte[] png) {
        return new MockMultipartFile("signature", "signature.png", "image/png", png);
    }

    /**
     * Detach every hydrated entity. All MockMvc calls of a test share the test transaction's persistence
     * context, so after a raw JDBC write a later JPA read could return a stale cached entity.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    /** Perform with the JPA cache cleared on both sides (raw SQL edits happen between calls). */
    private MvcResult perform(RequestBuilder request) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        return result;
    }

    // ================================================================
    // Helpers: self-seeding (business 1 / store 1 / user 1 unless a test needs another scope)
    // ================================================================

    private long insertOrder(String status) {
        return insertOrderIn(BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, status);
    }

    private long insertOrderIn(long businessId, int storeId, long userId, String status) {
        int s = ++seq;
        // order_number must match chk_sales_order_number_format (V6): {code}.{LL#}.{#####}
        String orderNumber = ORDER_NUMBER_PREFIX + String.format("%05d", s % 100_000);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, ?::order_status, 1, 2026) "
                        + "RETURNING order_id",
                Long.class, businessId, storeId, userId, s, orderNumber, status);
    }

    private void seedCustomer(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quote', 'Tester', ?, ?)",
                orderId, VALID_EMAIL, VALID_MOBILE);
    }

    private void seedAddress(long orderId, String addressType, String streetNumber, String street) {
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, ?::address_type, NULL, ?, ?, 'Sydney', 'NSW', '2000')",
                orderId, addressType, streetNumber, street);
    }

    /** One priced charge line on a self-seeded store_charge (the header is left untouched). */
    private void seedChargeLine(long orderId, String lineTotal, String lineCost) {
        String code = CHARGE_CODE_PREFIX + (++seq);
        BigDecimal total = new BigDecimal(lineTotal);
        BigDecimal cost = new BigDecimal(lineCost);
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Quote conversion test charge', ?, ?) "
                        + "RETURNING charge_id",
                Long.class, STORE_SYD_CBD, code, total, cost);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Quote conversion test charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code, total, cost, total, total, cost);
        clearJpaCache();
    }

    private void setDetailsOfSale(long orderId, String details) {
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = ? WHERE order_id = ?", details, orderId);
        clearJpaCache();
    }

    private void setTenantTerms(String termsHtml) {
        jdbcTemplate.update("UPDATE business SET terms_soft = ?, terms_hard = ? WHERE business_id = ?",
                termsHtml, termsHtml, BUSINESS_AUSSIE);
        clearJpaCache();
    }

    private void setLaid(long orderId) {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
        clearJpaCache();
        Assertions.assertEquals("LAID", orderStatus(orderId));
    }

    /**
     * Move the proposed lay date only: lay_date_status stays CONFIRMED (non-null), so
     * chk_sales_order_lay_date_pair keeps holding without any constraint relaxation.
     */
    private void setProposedLayDate(long orderId, LocalDate layDate) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE sales_order SET proposed_lay_date = ? WHERE order_id = ?", Date.valueOf(layDate), orderId));
        clearJpaCache();
        Map<String, Object> layDateRow = jdbcTemplate.queryForMap(
                "SELECT proposed_lay_date, lay_date_status::text AS status FROM sales_order WHERE order_id = ?",
                orderId);
        Assertions.assertEquals(layDate, ((Date) layDateRow.get("proposed_lay_date")).toLocalDate());
        Assertions.assertEquals("CONFIRMED", layDateRow.get("status"), "the lay date status stays set");
    }

    /** No tenant logo (rolled back with the test): a signature is then the only image an invoice PDF embeds. */
    private void clearBusinessLogo() {
        jdbcTemplate.update("UPDATE business SET logo_path = NULL WHERE business_id = ?", BUSINESS_AUSSIE);
        clearJpaCache();
    }

    /** In-transaction DDL (rolled back with the test): let a customer email be NULL or malformed. */
    private void relaxCustomerEmailConstraints() {
        jdbcTemplate.execute("ALTER TABLE order_customer DROP CONSTRAINT chk_order_customer_email_format");
        jdbcTemplate.execute("ALTER TABLE order_customer ALTER COLUMN email DROP NOT NULL");
    }

    /** In-transaction DDL (rolled back with the test): allow ONE of the lay date pair to be null. */
    private void relaxLayDatePairConstraint() {
        jdbcTemplate.execute("ALTER TABLE sales_order DROP CONSTRAINT chk_sales_order_lay_date_pair");
    }

    /**
     * Customer (valid email), INSTALLATION + BILLING addresses, details of sale, proposed lay date
     * 2026-12-01 + CONFIRMED, and the tenant terms set explicitly (frozen into every quote issued here),
     * but NO priced line.
     */
    private long invoiceReadyOrderWithoutLines() {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId);
        seedAddress(orderId, "BILLING", "12", "Billing Street");
        seedAddress(orderId, "INSTALLATION", "7", "Install Street");
        jdbcTemplate.update(
                "UPDATE sales_order SET details_of_sale = ?, proposed_lay_date = DATE '2026-12-01', "
                        + "lay_date_status = 'CONFIRMED'::lay_date_status WHERE order_id = ?",
                FROZEN_DETAILS, orderId);
        setTenantTerms(FROZEN_TERMS);
        clearJpaCache();
        return orderId;
    }

    private long invoiceReadyOrder(String lineTotal, String lineCost) {
        long orderId = invoiceReadyOrderWithoutLines();
        seedChargeLine(orderId, lineTotal, lineCost);
        return orderId;
    }

    /** The shared fixture: invoice-ready with one charge line of 100.00 ex / 40.00 cost (110.00 inc live). */
    private long invoiceReadyOrder() {
        return invoiceReadyOrder("100.00", "40.00");
    }

    /** Invoice-ready order + itemised 250.00 / 275.00 draft sent as v1 and publicly accepted. */
    private long acceptedQuoteOrder() throws Exception {
        long orderId = invoiceReadyOrder();
        saveItemisedTwoLineDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);
        Assertions.assertEquals("ACCEPTED", versionStatus(orderId, 1), "fixture: v1 accepted through the public endpoint");
        return orderId;
    }

    /** D.1 at 550.00 inc (one 500.00-ex line), 400.00 paid, then a 275.00 quote accepted. */
    private long overpaidAcceptedOrder() throws Exception {
        long orderId = invoiceReadyOrder("500.00", "200.00");
        createInvoiceD1(orderId);
        assertMoney("550.00", invoiceRow(orderId, 1).get("sale_price_inc_gst"), "fixture: D.1 billed 550.00 inc");
        recordPayment(orderId, "400.00");                          // v2, balance 150.00
        saveItemisedTwoLineDraft(orderId);                         // 250.00 ex: not below the 200.00 cost
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);
        Assertions.assertEquals("ACCEPTED", versionStatus(orderId, 1));
        return orderId;
    }

    // ================================================================
    // Helpers: the real quote and invoice flows
    // ================================================================

    private void saveDraft(long orderId, String body) throws Exception {
        MvcResult result = perform(put(quoteUrl(orderId, "draft")).session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertStatus(result, 200, "save quote draft");
    }

    /** Itemised: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex / 275.00 inc. */
    private void saveItemisedTwoLineDraft(long orderId) throws Exception {
        saveDraft(orderId, """
                {"itemised": true, "lines": [
                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                ]}""");
    }

    private void saveNonItemisedDraft(long orderId, String finalIncTotal) throws Exception {
        saveDraft(orderId, "{\"itemised\": false, \"final_total_inc_gst\": " + finalIncTotal + ", \"lines\": []}");
    }

    /** Issue (or re-issue) via the protected send-email and return the plaintext token from the link. */
    private String sendAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = perform(post(quoteUrl(orderId, "send-email")).session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertStatus(result, 201, "send quote email");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "exactly one quote email must have been recorded");
        String body = sent.get(sent.size() - 1).bodyText();
        Matcher matcher = PUBLIC_LINK_TOKEN.matcher(body);
        Assertions.assertTrue(matcher.find(), () -> "the quote email must carry the /q/{token} link: " + body);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "the public link must appear exactly once in the body");
        return token;
    }

    /** The REAL public accept: token-only (NO session), exactly one PNG {@code signature} part. */
    private void acceptPublicly(String token, byte[] signaturePng) throws Exception {
        MvcResult result = perform(multipart(publicQuoteUrl(token) + "/accept").file(signaturePart(signaturePng)));
        assertStatus(result, 201, "public quote accept");
        JsonNode body = readJson(result);
        Assertions.assertEquals("INACTIVE", body.get("data").get("state").asText());
        Assertions.assertEquals("Quote accepted.", body.get("message").asText());
    }

    private void cancelIssuedQuote(long orderId) throws Exception {
        MvcResult result = perform(post(quoteUrl(orderId, "cancel")).session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertStatus(result, 200, "cancel quote");
    }

    /**
     * Lazy expiry the way production reaches it: the ACTIVE token's expires_at is moved into the past with
     * JVM time (the clock the services compare with), then the public GET flips token + version.
     */
    private void expireLazily(long orderId, String token, int versionNumber) throws Exception {
        int updated = jdbcTemplate.update(
                "UPDATE quote_token SET expires_at = ? WHERE token_hash = ? AND status = 'ACTIVE'",
                Timestamp.valueOf(LocalDateTime.now().minusDays(2)), sha256Hex(token));
        Assertions.assertEquals(1, updated, "expected to backdate exactly one ACTIVE token");
        clearJpaCache();
        assertPublicState(token, "EXPIRED");
        Assertions.assertEquals("EXPIRED", versionStatus(orderId, versionNumber), "the ISSUED version expired");
        Assertions.assertEquals("EXPIRED", tokenRow(token).get("status"), "the token expired");
    }

    private void assertPublicState(String token, String expectedState) throws Exception {
        MvcResult result = perform(get(publicQuoteUrl(token)));
        assertStatus(result, 200, "public quote read");
        Assertions.assertEquals(expectedState, readJson(result).get("data").get("state").asText());
    }

    /** D.1: the unsigned invoice v1 from the live order (empty body). */
    private void createInvoiceD1(long orderId) throws Exception {
        MvcResult result = perform(post(invoicesUrl(orderId)).session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertStatus(result, 201, "D.1 create invoice");
        Assertions.assertEquals(1, readJson(result).get("data").get("invoice").get("version_number").asInt());
    }

    /** D.8: the in-app invoice signature (appends a signed version and auto-emails it). */
    private void signInvoiceInApp(long orderId) throws Exception {
        signInvoiceInApp(orderId, ONE_PIXEL_PNG, SNAPSHOT_NAME);
    }

    /** D.8 with a chosen signature image and signer name (D.8 stores the uploaded bytes as they are). */
    private void signInvoiceInApp(long orderId, byte[] signaturePng, String acceptedName) throws Exception {
        MvcResult result = perform(signInvoiceRequest(orderId, signaturePng, acceptedName));
        assertStatus(result, 201, "D.8 in-app invoice acceptance");
    }

    /**
     * D.9: re-email the current signed invoice. Its only writes are the in-place last_emailed_at stamps
     * (the invoice and the section 11.1 mirror); the signature time is never touched.
     */
    private void resendInvoice(long orderId) throws Exception {
        int sentBefore = invoiceEmailSender.sentEmails().size();
        MvcResult result = perform(post(invoicesUrl(orderId) + "/current/resend").session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertStatus(result, 200, "D.9 resend invoice");
        Assertions.assertEquals(sentBefore + 1, invoiceEmailSender.sentEmails().size(),
                "D.9 records exactly one invoice email");
    }

    private long recordPayment(long orderId, String amount) throws Exception {
        MvcResult result = perform(post(paymentsUrl(orderId)).session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\":\"CASH\",\"amount\":" + amount + "}"));
        assertStatus(result, 201, "record payment " + amount);
        return jdbcTemplate.queryForObject(
                "SELECT payment_transaction_id FROM payment_transaction WHERE order_id = ? "
                        + "ORDER BY payment_transaction_id DESC LIMIT 1", Long.class, orderId);
    }

    private void voidPayment(long orderId, long paymentId) throws Exception {
        MvcResult result = perform(post(paymentsUrl(orderId) + "/" + paymentId + "/void").session(liamStore1Session()));
        assertStatus(result, 201, "void payment " + paymentId);
    }

    /** D.2 on a LAID order is still 422 ORDER_LOCKED and writes nothing. */
    private void assertRewriteLocked(long orderId) throws Exception {
        Map<String, Object> before = refusalState(orderId);
        MvcResult rewrite = perform(post(invoicesUrl(orderId) + "/rewrite").session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertError(rewrite, 422, "ORDER_LOCKED", ORDER_LOCKED_MESSAGE, "D.2 rewrite on a LAID order");
        Assertions.assertEquals(before, refusalState(orderId), "a locked rewrite writes nothing");
    }

    /** {@code data.accepted.invoice_eligible} from the workspace read, asserted to be a JSON boolean. */
    private boolean invoiceEligible(long orderId) throws Exception {
        MvcResult result = perform(get(quoteUrl(orderId, "workspace")).session(liamStore1Session()));
        assertStatus(result, 200, "quote workspace");
        JsonNode accepted = readJson(result).get("data").get("accepted");
        Assertions.assertTrue(accepted != null && accepted.isObject(), () -> "accepted must be populated: " + accepted);
        JsonNode eligible = accepted.get("invoice_eligible");
        Assertions.assertTrue(eligible != null && eligible.isBoolean(),
                () -> "invoice_eligible must be a JSON boolean but was " + eligible);
        return eligible.booleanValue();
    }

    // ================================================================
    // Helpers: Path A success and refusal
    // ================================================================

    private Conversion convertOk(long orderId) throws Exception {
        return convertOk(orderId, "{}");
    }

    private Conversion convertOk(long orderId, String body) throws Exception {
        return convertOk(orderId, body, liamStore1Session());
    }

    /**
     * A successful Path A call (as the given session) plus the invariants every allowed conversion must
     * keep: no write to the quote / order / line / customer / address / payment rows, every earlier invoice
     * version (SELECT *) and its PDF bytes untouched, exactly ONE new stored_file row and ONE new file on
     * disk (the new invoice PDF: no signature copy), the section 11.1 mirror reset to null (from a non-null
     * value) and no email of any kind. The local date is read immediately before and after the request, so
     * the invoice_date checks hold even when a conversion straddles midnight.
     */
    private Conversion convertOk(long orderId, String body, MockHttpSession session) throws Exception {
        ensureMirrorSentinel(orderId);
        Map<String, Object> untouched = untouchedState(orderId);
        List<Map<String, Object>> invoicesBefore = invoiceRows(orderId);
        Map<Integer, byte[]> pdfsBefore = invoicePdfsByVersion(orderId);
        List<Map<String, Object>> storedFilesBefore = storedFileRows(orderId);
        Map<String, String> diskBefore = diskFilesForOrder(orderId);
        SenderCounts sendersBefore = senderCounts();

        LocalDate dayBefore = LocalDate.now();
        MvcResult result = perform(withBody(post(createInvoiceUrl(SLUG_AUSSIE, orderId)).session(session), body));
        LocalDate dayAfter = LocalDate.now();
        assertStatus(result, 201, "Path A conversion");

        Assertions.assertEquals(untouched, untouchedState(orderId),
                "Path A writes no quote / order / line / customer / address / payment row");
        List<Map<String, Object>> invoicesAfter = invoiceRows(orderId);
        Assertions.assertEquals(invoicesBefore.size() + 1, invoicesAfter.size(), "exactly one invoice row appended");
        Assertions.assertEquals(invoicesBefore, invoicesAfter.subList(0, invoicesBefore.size()),
                "every earlier invoice version row is unchanged (SELECT *)");
        Map<Integer, byte[]> pdfsAfter = invoicePdfsByVersion(orderId);
        for (Map.Entry<Integer, byte[]> earlier : pdfsBefore.entrySet()) {
            Assertions.assertArrayEquals(earlier.getValue(), pdfsAfter.get(earlier.getKey()),
                    "the PDF bytes of invoice v" + earlier.getKey() + " are unchanged on disk");
        }

        Map<String, Object> newInvoice = invoicesAfter.get(invoicesAfter.size() - 1);
        List<Map<String, Object>> storedFilesAfter = storedFileRows(orderId);
        Assertions.assertEquals(storedFilesBefore.size() + 1, storedFilesAfter.size(),
                "exactly one new stored_file row (the invoice PDF)");
        Assertions.assertEquals(storedFilesBefore, storedFilesAfter.subList(0, storedFilesBefore.size()),
                "existing stored_file rows are unchanged");
        Map<String, Object> newStoredFile = storedFilesAfter.get(storedFilesAfter.size() - 1);
        Assertions.assertEquals(asLong(newInvoice.get("stored_file_id")), asLong(newStoredFile.get("stored_file_id")),
                "the one new stored_file row is the new invoice's PDF");

        Map<String, String> diskAfter = diskFilesForOrder(orderId);
        for (Map.Entry<String, String> file : diskBefore.entrySet()) {
            Assertions.assertEquals(file.getValue(), diskAfter.get(file.getKey()),
                    "pre-existing file " + file.getKey() + " is unchanged on disk");
        }
        Set<String> added = new TreeSet<>(diskAfter.keySet());
        added.removeAll(diskBefore.keySet());
        Assertions.assertEquals(Set.of(fileNameOf((String) newStoredFile.get("storage_path"))), added,
                "the only new file on disk is the new invoice PDF");

        Assertions.assertNull(mirror(orderId), "the section 11.1 mirror is reset to the new version's null");
        Assertions.assertEquals(sendersBefore, senderCounts(), "Path A sends no email of any kind");
        return new Conversion(result, dayBefore, dayAfter);
    }

    /**
     * {@link #assertConverted(long, Conversion, int, int, String, String, String, String, LocalDate, long)} for
     * the shared fixture: due two days before the seeded 2026-12-01 lay date, converted by Liam.
     */
    private JsonNode assertConverted(long orderId, Conversion conversion, int expectedVersion, int quoteVersionNumber,
                                     String ex, String inc, String totalPaid, String balance) throws Exception {
        return assertConverted(orderId, conversion, expectedVersion, quoteVersionNumber, ex, inc, totalPaid, balance,
                EXPECTED_DUE_DATE, USER_LIAM);
    }

    /**
     * The persisted row, the PDF on disk and the 201 body of a Path A conversion to invoice version
     * {@code expectedVersion} from quote version {@code quoteVersionNumber}: due on {@code expectedDueDate},
     * created by {@code expectedCreatedBy}, dated within the conversion's before / after day window.
     */
    private JsonNode assertConverted(long orderId, Conversion conversion, int expectedVersion, int quoteVersionNumber,
                                     String ex, String inc, String totalPaid, String balance,
                                     LocalDate expectedDueDate, long expectedCreatedBy) throws Exception {
        MvcResult result = conversion.result();
        String label = "Path A invoice v" + expectedVersion + " from quote v" + quoteVersionNumber;
        String orderNumber = orderNumber(orderId);
        Map<String, Object> quote = quoteVersionRow(orderId, quoteVersionNumber);
        Assertions.assertEquals("ACCEPTED", quote.get("status"), label);
        Assertions.assertNotNull(quote.get("accepted_at"), label);
        LocalDateTime quoteAcceptedAt = ((Timestamp) quote.get("accepted_at")).toLocalDateTime();

        // Sequential version allocation: 1..n, no gap, no reuse.
        Assertions.assertEquals(IntStream.rangeClosed(1, expectedVersion).boxed().toList(), invoiceVersions(orderId),
                label + ": versions are allocated sequentially");

        // ---- the persisted invoice row ----
        Map<String, Object> row = invoiceRow(orderId, expectedVersion);
        Assertions.assertEquals(quote.get("accepted_at"), row.get("accepted_at"),
                label + ": accepted_at is the quote's signature time, never now");
        Assertions.assertEquals(SNAPSHOT_NAME, row.get("accepted_customer_name"), label);
        Assertions.assertEquals(quote.get("accepted_customer_name"), row.get("accepted_customer_name"), label);
        Assertions.assertEquals(asLong(quote.get("accepted_signature_file_id")),
                asLong(row.get("accepted_signature_file_id")), label + ": the SAME signature stored_file");
        Assertions.assertEquals(asLong(quote.get("quote_version_id")), asLong(row.get("source_quote_version_id")),
                label + ": source_quote_version_id");
        Assertions.assertEquals(quote.get("terms_snapshot"), row.get("terms_snapshot"), label + ": frozen terms");
        Assertions.assertEquals(quote.get("details_of_sale_snapshot"), row.get("details_of_sale_snapshot"),
                label + ": frozen details");
        assertMoney(ex, row.get("sale_price_ex_gst"), label + " sale_price_ex_gst");
        assertMoney(inc, row.get("sale_price_inc_gst"), label + " sale_price_inc_gst");
        Assertions.assertEquals(quote.get("quote_total_ex_gst"), row.get("sale_price_ex_gst"),
                label + ": ex copied verbatim");
        Assertions.assertEquals(quote.get("quote_total_inc_gst"), row.get("sale_price_inc_gst"),
                label + ": inc copied verbatim");
        assertMoney(totalPaid, row.get("total_paid"), label + " total_paid");
        assertMoney(balance, row.get("balance_due"), label + " balance_due");
        Assertions.assertEquals(expectedDueDate, ((Date) row.get("due_date")).toLocalDate(), label + " due_date");
        LocalDate invoiceDate = ((Date) row.get("invoice_date")).toLocalDate();
        assertConversionDay(invoiceDate, conversion, label + " invoice_date");
        Assertions.assertNull(row.get("last_emailed_at"), label + ": never emailed");
        Assertions.assertEquals(expectedCreatedBy, asLong(row.get("created_by_user_id")), label + ": the session user");

        // ---- the new invoice PDF: stored_file row + bytes on disk + text ----
        Map<String, Object> pdfFile = storedFileRow(row.get("stored_file_id"));
        Assertions.assertEquals("invoice-" + orderNumber + "-v" + expectedVersion + ".pdf", pdfFile.get("file_name"));
        Assertions.assertEquals("application/pdf", pdfFile.get("mime_type"));
        String pdfPath = (String) pdfFile.get("storage_path");
        Assertions.assertTrue(pdfPath.startsWith(orderStoragePrefix(orderId)), () -> "unexpected path " + pdfPath);
        byte[] pdf = diskBytes(pdfPath);
        Assertions.assertEquals(asLong(pdfFile.get("file_size")), pdf.length, label + ": file_size is the stored length");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII), label + ": PDF magic");
        Assertions.assertNotEquals(asLong(row.get("stored_file_id")), asLong(row.get("accepted_signature_file_id")));
        assertInvoicePdfText(pdf, orderNumber, (String) quote.get("details_of_sale_snapshot"), inc, totalPaid, balance,
                expectedDueDate, quoteAcceptedAt, label);

        // ---- the inherited signature file is the quote's own, still on disk ----
        Map<String, Object> signatureFile = storedFileRow(row.get("accepted_signature_file_id"));
        Assertions.assertEquals("quote-signature-" + orderNumber + "-v" + quoteVersionNumber + ".png",
                signatureFile.get("file_name"), label);
        Assertions.assertEquals("image/png", signatureFile.get("mime_type"), label);
        Assertions.assertTrue(Files.isRegularFile(diskPath((String) signatureFile.get("storage_path"))),
                label + ": the inherited signature file is still on disk");

        // ---- the 201 body ----
        String raw = bodyOf(result);
        JsonNode body = JSON.readTree(raw);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(body), label + ": envelope keys");
        Assertions.assertEquals(CREATED_MESSAGE, body.get("message").asText(), label);
        JsonNode data = body.get("data");
        Assertions.assertEquals(Set.of("invoice"), fieldNames(data), label + ": data keys");
        JsonNode invoice = data.get("invoice");
        Assertions.assertEquals(new TreeSet<>(INVOICE_DETAIL_KEYS), fieldNames(invoice),
                label + ": InvoiceDetail keys (no internal id)");
        Assertions.assertEquals(asLong(row.get("invoice_id")), invoice.get("invoice_id").asLong(), label);
        Assertions.assertEquals(orderId, invoice.get("order_id").asLong(), label);
        Assertions.assertEquals(expectedVersion, invoice.get("version_number").asInt(), label);
        LocalDate jsonInvoiceDate = LocalDate.parse(invoice.get("invoice_date").asText());
        assertConversionDay(jsonInvoiceDate, conversion, label + " json invoice_date");
        Assertions.assertEquals(invoiceDate, jsonInvoiceDate, label + ": the JSON invoice_date is the row's");
        Assertions.assertEquals(expectedDueDate.toString(), invoice.get("due_date").asText(), label + " json due_date");
        assertJsonTextOrNull((String) quote.get("details_of_sale_snapshot"), invoice.get("details_of_sale_snapshot"),
                label + " details_of_sale_snapshot");
        assertJsonMoney(ex, invoice.get("sale_price_ex_gst"), label + " json sale_price_ex_gst");
        assertJsonMoney(inc, invoice.get("sale_price_inc_gst"), label + " json sale_price_inc_gst");
        assertJsonMoney(totalPaid, invoice.get("total_paid"), label + " json total_paid");
        assertJsonMoney(balance, invoice.get("balance_due"), label + " json balance_due");
        Assertions.assertEquals(expectedCreatedBy, invoice.get("created_by_user_id").asLong(),
                label + " json created_by_user_id");
        Assertions.assertFalse(invoice.get("created_at").isNull(), label);
        Assertions.assertEquals(pdfDownloadPath(orderId), invoice.get("pdf_download_path").asText(), label);
        Assertions.assertEquals(JSON_TIMESTAMP.format(quoteAcceptedAt), invoice.get("accepted_at").asText(),
                label + ": the quote signature time (seconds precision on the wire)");
        Assertions.assertEquals(SNAPSHOT_NAME, invoice.get("accepted_customer_name").asText(), label);
        Assertions.assertTrue(invoice.get("accepted_signature_present").booleanValue(), label);
        Assertions.assertEquals(signatureDownloadPath(orderId), invoice.get("accepted_signature_download_path").asText(),
                label);
        Assertions.assertTrue(invoice.get("last_emailed_at").isNull(), label + ": last_emailed_at present and null");
        Assertions.assertEquals("QUOTE", invoice.get("terms_source").asText(), label);
        assertJsonTextOrNull((String) quote.get("terms_snapshot"), invoice.get("terms_html"), label + " terms_html");

        // No internal reference leaks anywhere in the body.
        for (String forbidden : List.of("source_quote_version_id", "stored_file_id", "accepted_signature_file_id",
                "storage_path", "/uploads/", "terms_snapshot")) {
            Assertions.assertFalse(raw.contains(forbidden), () -> label + ": '" + forbidden + "' leaked: " + raw);
        }
        return invoice;
    }

    /** The signed, quote-sourced invoice PDF text. */
    private static void assertInvoicePdfText(byte[] pdf, String orderNumber, String details, String inc, String paid,
                                             String balance, LocalDate dueDate, LocalDateTime acceptedAt, String label)
            throws IOException {
        String raw = pdfText(pdf);
        String text = noSpace(raw);
        Assertions.assertTrue(text.contains("TAXINVOICE"), () -> label + ": title missing: " + raw);
        Assertions.assertTrue(text.contains(noSpace(orderNumber)), () -> label + ": order number missing: " + raw);
        if (details != null) {
            Assertions.assertTrue(text.contains(noSpace(details)), () -> label + ": frozen details missing: " + raw);
        }
        Assertions.assertTrue(text.contains(dollars(inc)), () -> label + ": total " + dollars(inc) + " missing: " + raw);
        Assertions.assertTrue(text.contains(dollars(paid)), () -> label + ": paid " + dollars(paid) + " missing: " + raw);
        Assertions.assertTrue(text.contains(dollars(balance)),
                () -> label + ": balance " + dollars(balance) + " missing: " + raw);
        Assertions.assertTrue(text.contains("Due" + DISPLAY_DATE.format(dueDate)),
                () -> label + ": due date " + DISPLAY_DATE.format(dueDate) + " missing: " + raw);
        // Path A copies the stored accepted_at, so the caption renders the DB value; the minus-1-microsecond
        // variant only guards a rounding edge. The caption is built from inline spans, which the default
        // stripper extracts out of order, so it is matched on the position-sorted (visual order) text.
        String visual = noSpace(pdfTextByPosition(pdf));
        String exact = noSpace("Accepted by " + SNAPSHOT_NAME + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        String roundedBack = noSpace("Accepted by " + SNAPSHOT_NAME + " on "
                + DISPLAY_DATE_TIME.format(acceptedAt.minusNanos(1_000)));
        Assertions.assertTrue(visual.contains(exact) || visual.contains(roundedBack),
                () -> label + ": acceptance caption '" + exact + "' missing (visual order): " + visual);
        Assertions.assertFalse(raw.contains("Customer signature"),
                () -> label + ": a signed invoice never shows the blank signature caption: " + raw);
        Assertions.assertTrue(countImages(pdf) >= 1, label + ": the inherited signature image is embedded");
    }

    /**
     * A refused Path A call: the mirror carries a non-null value first (so a write is visible), the call
     * returns the expected error envelope, and NOTHING changes (rows, stored files, files on disk, mirror,
     * senders).
     */
    private JsonNode assertRefused(long orderId, String body, int status, String code, String message, String label)
            throws Exception {
        ensureMirrorSentinel(orderId);
        Map<String, Object> before = refusalState(orderId);
        MvcResult result = perform(createInvoiceRequest(orderId, body));
        JsonNode error = assertError(result, status, code, message, label);
        Assertions.assertEquals(before, refusalState(orderId), label + ": a refused conversion writes nothing");
        return error;
    }

    /**
     * A 422 INVOICE_PRECONDITIONS_NOT_MET carrying exactly {@code expected} (order-insensitive), with nothing
     * written. Every caller's current invoice is absent or unsigned, so the workspace flag stays true on
     * both sides: missing preconditions, blank frozen details and zero totals never feed eligibility.
     */
    private void assertPreconditionsRefused(long orderId, String label, Detail... expected) throws Exception {
        Assertions.assertTrue(invoiceEligible(orderId), label + ": preconditions never feed invoice_eligible");
        JsonNode error = assertRefused(orderId, "{}", 422, "INVOICE_PRECONDITIONS_NOT_MET", PRECONDITIONS_MESSAGE, label);
        List<Detail> actual = details(error);
        Assertions.assertEquals(expected.length, actual.size(), () -> label + ": exactly the failing items: " + actual);
        Assertions.assertEquals(Set.of(expected), new HashSet<>(actual), label + ": section / field / message");
        Assertions.assertTrue(invoiceEligible(orderId), label + ": still eligible after the refusal");
    }

    private void assertOrderNotFound(RequestBuilder request, String orderNumber, String label) throws Exception {
        MvcResult result = perform(request);
        JsonNode error = assertError(result, 404, "ORDER_NOT_FOUND", "Order not found.", label);
        Assertions.assertFalse(error.has("details"), label);
        String body = bodyOf(result);
        Assertions.assertFalse(body.contains(orderNumber), () -> label + ": a scope miss must not echo the order: " + body);
    }

    private static JsonNode assertError(MvcResult result, int status, String code, String message, String label)
            throws Exception {
        assertStatus(result, status, label);
        JsonNode body = readJson(result);
        Assertions.assertFalse(body.has("data"), () -> label + ": an error carries no data: " + body);
        JsonNode error = body.get("error");
        Assertions.assertNotNull(error, () -> label + ": error envelope missing: " + body);
        Assertions.assertEquals(code, error.get("code").asText(), () -> label + ": " + body);
        Assertions.assertEquals(message, error.get("message").asText(), () -> label + ": " + body);
        return error;
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + bodyOf(result));
    }

    // ================================================================
    // Helpers: state snapshots
    // ================================================================

    /**
     * Everything Path A must never write, as one comparable snapshot (whole rows, ordered). The order row
     * is compared in full except last_emailed_at, the section 11.1 mirror and the ONLY order column Path A
     * writes (asserted separately).
     */
    private Map<String, Object> untouchedState(long orderId) {
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Object> order = new LinkedHashMap<>(
                jdbcTemplate.queryForMap("SELECT * FROM sales_order WHERE order_id = ?", orderId));
        Assertions.assertTrue(order.containsKey("last_emailed_at"), "sales_order.last_emailed_at must exist");
        order.remove("last_emailed_at");
        state.put("sales_order", order);
        state.put("quote_draft", jdbcTemplate.queryForList(
                "SELECT * FROM quote_draft WHERE order_id = ? ORDER BY 1", orderId));
        state.put("quote_draft_line", jdbcTemplate.queryForList(
                "SELECT l.* FROM quote_draft_line l JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id "
                        + "WHERE d.order_id = ? ORDER BY 1", orderId));
        state.put("quote_version", jdbcTemplate.queryForList(
                "SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId));
        state.put("quote_version_line", jdbcTemplate.queryForList(
                "SELECT l.* FROM quote_version_line l JOIN quote_version v ON v.quote_version_id = l.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY 1", orderId));
        state.put("quote_token", jdbcTemplate.queryForList(
                "SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY 1", orderId));
        state.put("order_product_line", jdbcTemplate.queryForList(
                "SELECT * FROM order_product_line WHERE order_id = ? ORDER BY 1", orderId));
        state.put("order_charge_line", jdbcTemplate.queryForList(
                "SELECT * FROM order_charge_line WHERE order_id = ? ORDER BY 1", orderId));
        state.put("order_customer", jdbcTemplate.queryForList(
                "SELECT * FROM order_customer WHERE order_id = ? ORDER BY 1", orderId));
        state.put("order_address", jdbcTemplate.queryForList(
                "SELECT * FROM order_address WHERE order_id = ? ORDER BY 1", orderId));
        state.put("payment_transaction", jdbcTemplate.queryForList(
                "SELECT * FROM payment_transaction WHERE order_id = ? ORDER BY 1", orderId));
        return state;
    }

    /** {@link #untouchedState} plus every invoice row, stored file, file on disk, the mirror and the senders. */
    private Map<String, Object> refusalState(long orderId) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>(untouchedState(orderId));
        state.put("invoice", invoiceRows(orderId));
        state.put("stored_file", storedFileRows(orderId));
        state.put("disk_files", diskFilesForOrder(orderId));
        state.put("mirror", mirror(orderId));
        state.put("senders", senderCounts());
        return state;
    }

    /** Give the section 11.1 mirror a non-null value when it has none, so any write to it is observable. */
    private void ensureMirrorSentinel(long orderId) {
        if (mirror(orderId) == null) {
            jdbcTemplate.update("UPDATE sales_order SET last_emailed_at = ? WHERE order_id = ?", MIRROR_SENTINEL, orderId);
            clearJpaCache();
        }
        Assertions.assertNotNull(mirror(orderId));
    }

    private Timestamp mirror(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId);
    }

    private SenderCounts senderCounts() {
        return new SenderCounts(
                invoiceEmailSender.sentEmails().size(), invoiceEmailSender.failedEmails().size(),
                quoteEmailSender.sentEmails().size(), quoteEmailSender.failedEmails().size(),
                acceptanceNotificationSender.sentNotifications().size(),
                acceptanceNotificationSender.failedNotifications().size());
    }

    // ================================================================
    // Helpers: DB probes
    // ================================================================

    private Map<String, Object> quoteVersionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private String versionStatus(long orderId, int versionNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM quote_version WHERE order_id = ? AND version_number = ?",
                String.class, orderId, versionNumber);
    }

    private int quoteVersionCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
    }

    private Map<String, Object> tokenRow(String plainToken) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_token WHERE token_hash = ?", sha256Hex(plainToken));
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private List<Map<String, Object>> invoiceRows(long orderId) {
        return jdbcTemplate.queryForList("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId);
    }

    private List<Integer> invoiceVersions(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT version_number FROM invoice WHERE order_id = ? ORDER BY version_number", Integer.class, orderId);
    }

    private int invoiceCount(long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice WHERE order_id = ?", Integer.class, orderId);
    }

    /** Every invoice version's PDF bytes, read from disk through its stored_file row. */
    private Map<Integer, byte[]> invoicePdfsByVersion(long orderId) throws IOException {
        Map<Integer, byte[]> pdfs = new TreeMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT i.version_number, sf.storage_path FROM invoice i "
                        + "JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id "
                        + "WHERE i.order_id = ? ORDER BY i.version_number", orderId)) {
            pdfs.put(((Number) row.get("version_number")).intValue(), diskBytes((String) row.get("storage_path")));
        }
        return pdfs;
    }

    private byte[] currentInvoicePdf(long orderId) throws IOException {
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT sf.storage_path FROM invoice i JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id "
                        + "WHERE i.order_id = ? ORDER BY i.version_number DESC LIMIT 1", String.class, orderId);
        return diskBytes(storagePath);
    }

    private Map<String, Object> storedFileRow(Object storedFileId) {
        Assertions.assertNotNull(storedFileId, "the stored_file reference must be set");
        return jdbcTemplate.queryForMap("SELECT * FROM stored_file WHERE stored_file_id = ?", asLong(storedFileId));
    }

    /** stored_file rows physically owned by this order (any business segment), in id order. */
    private List<Map<String, Object>> storedFileRows(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                "/uploads/%/orders/" + orderId + "/%");
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_status::text FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private int storeInBusiness(long businessId, Integer excludeStore) {
        if (excludeStore == null) {
            return jdbcTemplate.queryForObject(
                    "SELECT store_id FROM store WHERE business_id = ? AND is_active = TRUE ORDER BY store_id LIMIT 1",
                    Integer.class, businessId);
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

    /** An active user of {@code businessId} other than {@code excludedUser} (lowest user_id first). */
    private long otherUserInBusiness(long businessId, long excludedUser) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM app_user WHERE business_id = ? AND is_active = TRUE AND user_id <> ? "
                        + "ORDER BY user_id LIMIT 1", Long.class, businessId, excludedUser);
    }

    private String businessSlug(long businessId) {
        return jdbcTemplate.queryForObject("SELECT slug FROM business WHERE business_id = ?", String.class, businessId);
    }

    /** Give a user REAL access to a store (so the guard passes and only the order scope can miss). */
    private void grantStoreAccess(long businessId, long userId, int storeId) {
        jdbcTemplate.update(
                "INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id, store_id) DO NOTHING",
                businessId, userId, storeId);
    }

    // ================================================================
    // Helpers: disk, PDF, JSON, values
    // ================================================================

    private Path storageRoot() {
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
    }

    /** FileStorageService writes every business-1 order file under /uploads/1/orders/{orderId}/. */
    private static String orderStoragePrefix(long orderId) {
        return "/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/";
    }

    /** Physical file of a stored_file row: base dir + storage_path (which starts with "/uploads/"). */
    private Path diskPath(String storagePath) {
        Assertions.assertTrue(storagePath.startsWith("/uploads/"), () -> "unexpected storage_path " + storagePath);
        return storageRoot().resolve(storagePath.substring(1)).normalize();
    }

    private byte[] diskBytes(String storagePath) throws IOException {
        Path file = diskPath(storagePath);
        Assertions.assertTrue(Files.isRegularFile(file), () -> "expected a file on disk at " + file);
        return Files.readAllBytes(file);
    }

    /** The order's directory listing: file name to the SHA-256 of its bytes (so content changes show). */
    private Map<String, String> diskFilesForOrder(long orderId) throws IOException {
        Path dir = storageRoot().resolve(orderStoragePrefix(orderId).substring(1));
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> listing = Files.list(dir)) {
            for (Path file : listing.filter(Files::isRegularFile).toList()) {
                files.put(file.getFileName().toString(), sha256Hex(Files.readAllBytes(file)));
            }
        }
        return files;
    }

    private static String fileNameOf(String storagePath) {
        return storagePath.substring(storagePath.lastIndexOf('/') + 1);
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** The text in visual (position-sorted) order: inline spans come out as they are drawn. */
    private static String pdfTextByPosition(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
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

    /** Every image XObject in the pages' resources: its declared size and its decoded pixels. */
    private static List<EmbeddedImage> embeddedImages(byte[] pdf) throws IOException {
        List<EmbeddedImage> images = new ArrayList<>();
        try (PDDocument document = PDDocument.load(pdf)) {
            for (var page : document.getPages()) {
                var resources = page.getResources();
                for (var name : resources.getXObjectNames()) {
                    if (resources.isImageXObject(name)) {
                        PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                        images.add(new EmbeddedImage(image.getWidth(), image.getHeight(), image.getImage()));
                    }
                }
            }
        }
        return images;
    }

    /**
     * A distinctive 7 x 3 opaque RGB signature (every pixel a different colour), so it can be told apart
     * from any other signature in a PDF by its size AND its pixels.
     */
    private static byte[] distinctiveSignaturePng() throws IOException {
        BufferedImage image = new BufferedImage(7, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 7; x++) {
                image.setRGB(x, y, ((30 + x * 30) << 16) | ((40 + y * 90) << 8) | (200 - x * 20));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Assertions.assertTrue(ImageIO.write(image, "png", out), "a PNG ImageWriter must be available");
        return out.toByteArray();
    }

    private static BufferedImage decodePng(byte[] png, String label) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        Assertions.assertNotNull(image, () -> label + ": not a decodable PNG");
        return image;
    }

    /** Same size and the same RGB value at every pixel (the fixtures are fully opaque). */
    private static void assertSamePixels(BufferedImage expected, BufferedImage actual, String label) {
        Assertions.assertEquals(expected.getWidth(), actual.getWidth(), label + ": width");
        Assertions.assertEquals(expected.getHeight(), actual.getHeight(), label + ": height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y) & 0xFFFFFF;
                int got = actual.getRGB(x, y) & 0xFFFFFF;
                int px = x;
                int py = y;
                Assertions.assertEquals(want, got, () -> String.format(Locale.ROOT,
                        "%s: pixel (%d,%d) expected %06x but was %06x", label, px, py, want, got));
            }
        }
    }

    // PDFBox can inject or drop spaces between glyphs and wraps long text, so text checks compare with all
    // whitespace removed.
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

    /** The invoice PDF money format ("$" + %,.2f), whitespace-free. */
    private static String dollars(String amount) {
        return "$" + String.format(java.util.Locale.ENGLISH, "%,.2f", new BigDecimal(amount));
    }

    private static void assertInlineFilename(MvcResult result, String expectedFileName) {
        String header = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        Assertions.assertNotNull(header, "Content-Disposition must be set");
        ContentDisposition disposition = ContentDisposition.parse(header);
        Assertions.assertTrue(disposition.isInline(), () -> "inline disposition expected: " + header);
        Assertions.assertEquals(expectedFileName, disposition.getFilename(), header);
        Assertions.assertFalse(header.contains("/uploads/"), () -> "storage path must never leak: " + header);
    }

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
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            names.add(property.getKey());
        }
        return names;
    }

    private static List<Detail> details(JsonNode error) {
        List<Detail> list = new ArrayList<>();
        JsonNode details = error.get("details");
        if (details == null || details.isNull()) {
            return list;
        }
        for (JsonNode detail : details) {
            list.add(new Detail(textOrNull(detail, "section"), textOrNull(detail, "field"),
                    textOrNull(detail, "message")));
        }
        return list;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void assertJsonTextOrNull(String expected, JsonNode actual, String label) {
        Assertions.assertNotNull(actual, () -> label + ": key must be present");
        if (expected == null) {
            Assertions.assertTrue(actual.isNull(), () -> label + ": expected JSON null but was " + actual);
        } else {
            Assertions.assertEquals(expected, actual.asText(), label);
        }
    }

    /** Midnight-safe "today": the date lies within the local dates read just before and after the request. */
    private static void assertConversionDay(LocalDate actual, Conversion conversion, String label) {
        Assertions.assertNotNull(actual, () -> label + " must not be null");
        Assertions.assertFalse(actual.isBefore(conversion.dayBefore()) || actual.isAfter(conversion.dayAfter()),
                () -> label + " must be the conversion day (" + conversion.dayBefore() + " to "
                        + conversion.dayAfter() + ") but was " + actual);
    }

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertNotNull(actual, () -> label + " must not be null");
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                () -> label + ": expected " + expected + " but was " + actual);
    }

    private static void assertJsonMoney(String expected, JsonNode node, String label) {
        Assertions.assertNotNull(node, () -> label + " must be present");
        Assertions.assertTrue(node.isNumber(), () -> label + " must be a JSON number but was " + node);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(node.decimalValue()),
                () -> label + ": expected " + expected + " but was " + node);
    }

    private static long asLong(Object value) {
        Assertions.assertNotNull(value, "expected a non-null id");
        return ((Number) value).longValue();
    }

    private static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** One error detail: section (null for request-shape errors), field, message. */
    private record Detail(String section, String field, String message) {
    }

    /** Recorded-message counts of the three senders (equality = nothing sent). */
    private record SenderCounts(int invoiceSent, int invoiceFailed, int quoteSent, int quoteFailed,
                                int notificationsSent, int notificationsFailed) {
    }

    /** A 201 Path A call: its result plus the local dates read immediately before and after the request. */
    private record Conversion(MvcResult result, LocalDate dayBefore, LocalDate dayAfter) {
    }

    /** One image XObject found in a PDF page's resources: its declared size and its decoded pixels. */
    private record EmbeddedImage(int width, int height, BufferedImage pixels) {
    }
}
