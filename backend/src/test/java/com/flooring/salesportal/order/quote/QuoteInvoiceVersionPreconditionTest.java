package com.flooring.salesportal.order.quote;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import com.flooring.salesportal.order.OrderInvoiceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
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
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16G PR 3 (#123) - the mandatory Path A version precondition and the Path A post-commit signed-PDF
 * email: {@code POST /api/v1/{slug}/orders/{orderId}/quote/create-invoice} with the body
 * {@code {"quote_version_id": N}} (docs/API-Contracts-Phase16G-Invoice-Link.md sections 5.1, 5.2, 5.3,
 * 5.5, 6.1, 6.2, 11 and the PR 3 row of section 16).
 *
 * <p><b>The rules under test.</b>
 * <ul>
 *   <li>Body (5.3): an absent, empty or blank body, {@code {}} and a missing or null field are 400
 *       {@code VALIDATION_FAILED} {@code quote_version_id} {@code Required.}; the key given more than once in
 *       the body object is {@code Must appear at most once.} (D.8 parity; checked before the value, so
 *       whatever the repeated values are); a value that is not a positive integer is
 *       {@code Must be a positive integer.}; every other field is its own {@code Not allowed.} detail, in
 *       body order, in the same 400, with the {@code quote_version_id} detail LAST; a non-object body (a JSON
 *       {@code null} included) is {@code body} {@code Must be an object.}; unparseable JSON is 400
 *       {@code MALFORMED_JSON}.</li>
 *   <li>Stale (5.1, 5.2): the id is only COMPARED with the latest ACCEPTED version the server selects under
 *       the order lock, so an older accepted version's id, a forged id and another order's (or business's)
 *       accepted id are all 409 {@code QUOTE_VERSION_STALE} with the exact message, never a selector.</li>
 *   <li>Order of checks (5.5): scope (404) before any body look; no accepted quote (422
 *       {@code QUOTE_NOT_ACCEPTED}) before body validation; the stale comparison before the D5(b) signature
 *       precedence (409 {@code INVOICE_ALREADY_ACCEPTED}, Path A message), the collected preconditions (422)
 *       and the overpayment rule (422).</li>
 *   <li>Email (6.1, 6.2, 11): after the conversion, the appended version's own stored PDF is emailed to the
 *       TRIMMED saved customer email; delivered, failed and skipped (missing, blank or app-invalid saved
 *       email: no gate, the invoice is still created) each answer 201 with their exact message, the
 *       matching markers ({@code invoice.last_emailed_at} of the appended version and the
 *       {@code sales_order.last_emailed_at} mirror: the same stamp when delivered, both null otherwise) and
 *       the WARN line of {@code OrderInvoiceService} (order id + version only: no address, no throwable).
 *       Path A's {@code invoice_date} is the conversion day even when the quote was accepted, and the
 *       current invoice dated, on earlier days. Path A never writes a quote version, quote token or quote
 *       draft row and never sends a quote email, a store notification or an SMS.</li>
 * </ul>
 *
 * <p><b>Nothing written on a refusal.</b> Every refused call is wrapped by {@link #assertRefused}: the
 * section 11.1 mirror of each watched order first carries a non-null sentinel (so a mirror reset would
 * show), then the whole state is compared before and after - each watched order's sales_order, customer,
 * address, line, payment, quote draft / version / line / token, invoice and stored_file rows, every file on
 * disk under the tenants' business directories (name + SHA-256), global row counts and every recording
 * sender (invoice sent / failed, quote email, store notification, SMS) - and no WARN line is logged. This
 * class is {@code @Transactional}, so a refused request's programmatic transaction only marks the test
 * transaction rollback-only: any row it wrote before refusing would stay visible here, which is what makes
 * these comparisons meaningful.
 *
 * <p><b>Allowed conversions</b> go through {@link #convertOk}, which also proves: exactly one appended
 * invoice version (every earlier row unchanged), source = the latest accepted version, the inherited
 * acceptance, {@code invoice_date} within the local days read just before and after the request, exactly
 * one new stored_file row and one new file on disk (this version's PDF), no write to any quote / order /
 * line / customer / address / payment row, and the email outcome in full (sender calls, content,
 * attachment bytes, markers, WARN). The service's programmatic transactions JOIN this test transaction, so
 * the post-commit email and a delivered email's success stamp run inside it (in-transaction outcomes).
 *
 * <p><b>Fixtures - fully self-seeded.</b> {@link #setUp} seeds this class's own business (slug
 * {@value #SLUG}), store, salesperson and store access; tests that need other scopes seed a second store of
 * the same business (the same salesperson is granted access) or another business ({@value #OTHER_SLUG}).
 * Orders, customers, addresses and charge lines are raw SQL; every accepted quote comes from the REAL chain:
 * the protected draft PUT, the protected send-email (the plaintext token is read from the recorded
 * link-only quote email) and the PUBLIC multipart accept with no session. Raw SQL otherwise only moves
 * state between those flows (a backdated acceptance, a backdated invoice date, a removed BILLING row, a
 * cleared lay date, a changed saved email, the mirror sentinel) and is followed by a persistence-context
 * clear. The missing and blank saved emails need the transactional constraint relaxation
 * ({@code chk_order_customer_email_format}, the email NOT NULL), which rolls back with the test; the
 * app-invalid {@code jordan@localhost} is DB-valid and needs none. No V4 demo business, store, user or order
 * is used. Everything rolls back with the test transaction and the services' rollback hooks delete the
 * files they wrote under the storage root this class shares with the other quote suites (inside this
 * test's own business directories). The recording senders are singletons, reset before AND after every test.
 */
@ExtendWith(SpringExtension.class)
// The SAME storage property as the other quote suites, so this class joins their cached Spring context.
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class QuoteInvoiceVersionPreconditionTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded scopes ----
    private static final String SLUG = "pr3-quote-version-precondition";
    private static final String BUSINESS_NAME = "Version Precondition Flooring";
    private static final String STORE_CODE = "QVP-A";
    private static final String SALESPERSON_CODE = "QV3";
    /** A second store of the SAME business (the home salesperson is granted access to it). */
    private static final String SECOND_STORE_CODE = "QVP-B";
    /** Another business, with its own slug, store and salesperson. */
    private static final String OTHER_SLUG = "pr3-quote-version-precondition-other";
    private static final String OTHER_BUSINESS_NAME = "Other Precondition Flooring";
    private static final String OTHER_STORE_CODE = "QVO-A";
    private static final String OTHER_SALESPERSON_CODE = "QW3";

    // ---- The seeded customer (its V17 issue-time name is the inherited accepted name) ----
    private static final String CUSTOMER_FIRST_NAME = "Jordan";
    private static final String CUSTOMER_LAST_NAME = "Precondition";
    private static final String CUSTOMER_NAME = "Jordan Precondition";
    private static final String CUSTOMER_EMAIL = "jordan.precondition@example.com";
    /** DB-valid (trim non-empty, contains '@'); the signed invoice must go to the trimmed value. */
    private static final String PADDED_CUSTOMER_EMAIL = "  " + CUSTOMER_EMAIL + "  ";
    /** DB-valid but app-invalid (CustomerEmailValidator: the domain has no interior dot). */
    private static final String APP_INVALID_EMAIL = "jordan@localhost";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS = "Supply and lay carpet for the quote version precondition checks";
    private static final LocalDate LAY_DATE = LocalDate.of(2026, 12, 1);
    // proposed_lay_date minus 2 days.
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 11, 29);
    // Written into sales_order.last_emailed_at before a call so a mirror write can never go unnoticed.
    private static final Timestamp MIRROR_SENTINEL = Timestamp.valueOf("2026-01-02 03:04:05");
    private static final long MISSING_ORDER_ID = 9_999_999_999L;

    // ---- Quote drafts ----
    /** Itemised: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex / 275.00 inc. */
    private static final String ITEMISED_DRAFT = """
            {"itemised": true, "lines": [
              {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
              {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
            ]}""";

    // ---- Messages (the dashes are U+2014 em dashes) ----
    private static final String VALIDATION_MESSAGE = "One or more fields are invalid.";
    private static final String MALFORMED_MESSAGE = "Request body is malformed.";
    private static final String ORDER_NOT_FOUND_MESSAGE = "Order not found.";
    private static final String QUOTE_NOT_ACCEPTED_MESSAGE = "This quote has not been accepted yet.";
    private static final String QUOTE_VERSION_STALE_MESSAGE = "The accepted quote changed while the confirmation "
            + "was open. Review the accepted quote before creating an invoice.";
    private static final String NOT_NEWER_MESSAGE = "The current invoice was signed at the same time or later than "
            + "this quote. A newer signed quote is required to create an invoice from a quote.";
    private static final String PRECONDITIONS_MESSAGE = "Complete required fields before creating invoice.";
    private static final String PAYMENTS_EXCEED_MESSAGE = "Recorded payments exceed the accepted quote total. "
            + "Void the excess payments before creating an invoice from this quote.";
    private static final String CREATED_EMAILED_MESSAGE =
            "Invoice created from accepted quote and emailed to the customer.";
    private static final String CREATED_EMAIL_FAILED_MESSAGE = "Invoice created from accepted quote. "
            + "The invoice could not be emailed — use Re-send Invoice to try again.";
    private static final String CREATED_EMAIL_SKIPPED_MESSAGE = "Invoice created from accepted quote. "
            + "It was not emailed because the customer has no valid email address — add one on the "
            + "Customer tab, then use Re-send Invoice.";

    // ---- Error details ----
    private static final Detail ID_REQUIRED = new Detail(null, "quote_version_id", "Required.");
    private static final Detail ID_NOT_POSITIVE = new Detail(null, "quote_version_id", "Must be a positive integer.");
    private static final Detail ID_REPEATED = new Detail(null, "quote_version_id", "Must appear at most once.");
    private static final Detail BODY_NOT_AN_OBJECT = new Detail(null, "body", "Must be an object.");
    private static final Detail BILLING_REQUIRED =
            new Detail("address", "billing_address", "Billing address is required.");
    private static final Detail LAY_DATE_REQUIRED =
            new Detail("details", "proposed_lay_date", "Proposed lay date is required.");
    private static final Detail LAY_DATE_STATUS_REQUIRED =
            new Detail("details", "lay_date_status", "Lay date status is required.");

    // The plaintext token inside the delivered link .../q/{token} (URL-safe Base64).
    private static final Pattern PUBLIC_LINK_TOKEN = Pattern.compile("/q/([A-Za-z0-9_-]{43,128})");

    // Two different real, decodable PNGs (the public accept decodes and re-encodes the upload).
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final byte[] SECOND_SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEElEQVR42mOQtzWDIAY4CwAhwgNtlqP3IgAAAABJRU5ErkJggg==");

    // The invoice PDF formats (InvoicePdfGenerator) and the whole-second JSON timestamp format.
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter JSON_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

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

    // Path A never sends an SMS (16G is email only); SMS is dormant and recording-only.
    @Autowired
    private RecordingSmsSender smsSender;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    /** This test's own home scope (seeded inside the test transaction by {@link #setUp}). */
    private Scope home;

    private int orderSeq;
    private int chargeSeq;

    @BeforeEach
    void setUp() {
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class reads stored files under the shared quote test storage base dir");
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resetSenders();
        home = seedTenant(SLUG, BUSINESS_NAME, STORE_CODE, SALESPERSON_CODE, "home");
    }

    @AfterEach
    void tearDown() {
        // Singletons: never leak recorded messages or an armed failNextSend into another test or class.
        resetSenders();
    }

    private void resetSenders() {
        invoiceEmailSender.reset();
        quoteEmailSender.reset();
        acceptanceNotificationSender.reset();
        smsSender.reset();
    }

    // ================================================================
    // 1. The body: {"quote_version_id": N} on an order WITH an accepted quote (16G contract 5.3)
    // ================================================================

    /**
     * Every way of not sending the id is one 400 {@code VALIDATION_FAILED} with exactly one detail,
     * {@code quote_version_id} {@code Required.}: no body at all, an empty and a blank body, {@code {}} and a
     * {@code null} field (a JSON {@code null} body is a non-object body: see the non-object test). Nothing is
     * written by any of them, and the same order then converts with the latest accepted id, so each refusal
     * was the body alone.
     */
    @Test
    void pathA_absentEmptyBlankOrEmptyObjectBody_orNullId_is400QuoteVersionIdRequired_nothingWritten_thenTheIdConverts()
            throws Exception {
        long orderId = acceptedQuoteOrder(home);

        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("absent body", null);
        bodies.put("empty body", "");
        bodies.put("blank body", "  ");
        bodies.put("empty object", "{}");
        bodies.put("null quote_version_id", "{\"quote_version_id\": null}");
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            JsonNode error = refuse(orderId, body.getValue(), 400, "VALIDATION_FAILED", VALIDATION_MESSAGE, body.getKey());
            assertDetails(error, List.of(ID_REQUIRED), body.getKey());
        }
        Assertions.assertEquals(0, invoiceCount(orderId), "no refused body created an invoice");

        convertOk(home, orderId, EmailOutcome.DELIVERED);
    }

    /**
     * A {@code quote_version_id} that is present but not a positive integer is one 400 with exactly
     * {@code quote_version_id} {@code Must be a positive integer.}: a JSON string (even the valid id as a
     * string), a fraction, an integral-valued decimal ({@code 1.0}), a boolean, zero, a negative, a number
     * beyond the 64-bit range (and {@code Long.MAX_VALUE + 1}), an object and an array holding the valid id.
     * Nothing is written; the valid id then converts.
     */
    @Test
    void pathA_quoteVersionIdNotAPositiveInteger_is400MustBeAPositiveInteger_nothingWritten_thenTheIdConverts()
            throws Exception {
        long orderId = acceptedQuoteOrder(home);
        long acceptedId = latestAcceptedQuoteVersionId(orderId);

        Map<String, String> values = new LinkedHashMap<>();   // label -> raw JSON value
        values.put("the JSON string \"5\"", "\"5\"");
        values.put("the valid id as a JSON string", "\"" + acceptedId + "\"");
        values.put("the fraction 1.5", "1.5");
        values.put("the integral-valued decimal 1.0", "1.0");
        values.put("the boolean true", "true");
        values.put("zero", "0");
        values.put("the negative -3", "-3");
        values.put("a number beyond the 64-bit range", "99999999999999999999");
        values.put("Long.MAX_VALUE + 1", "9223372036854775808");
        values.put("an object", "{}");
        values.put("an array holding the valid id", "[" + acceptedId + "]");
        for (Map.Entry<String, String> value : values.entrySet()) {
            JsonNode error = refuse(orderId, "{\"quote_version_id\": " + value.getValue() + "}", 400,
                    "VALIDATION_FAILED", VALIDATION_MESSAGE, value.getKey());
            assertDetails(error, List.of(ID_NOT_POSITIVE), value.getKey());
        }
        Assertions.assertEquals(0, invoiceCount(orderId), "no refused body created an invoice");

        Conversion conversion = convertOk(home, orderId, EmailOutcome.DELIVERED);
        Assertions.assertEquals(acceptedId, asLong(conversion.invoice().get("source_quote_version_id")));
    }

    /**
     * Only {@code quote_version_id} is allowed. Each other field is its own {@code Not allowed.} detail, in
     * body order, in ONE 400 together with any {@code quote_version_id} detail, which always comes LAST: an
     * extra field alone ({@code price Not allowed.}, then {@code quote_version_id Required.}); several extras
     * beside a VALID id (one detail per extra, a null-valued extra included, nothing for the id); several
     * extras and no id; extras beside an invalid or null id (the extras first, the id detail last even when
     * the id is written first); a differently cased id name (just another field); and an extra beside a
     * valid-looking but mismatching id (still the 400: the body is validated before the stale comparison).
     * Nothing is written; the bare valid id then converts.
     */
    @Test
    void pathA_extraFields_eachNotAllowedInBodyOrder_inOne400_quoteVersionIdDetailLast_nothingWritten_thenTheIdConverts()
            throws Exception {
        long orderId = acceptedQuoteOrder(home);
        long id = latestAcceptedQuoteVersionId(orderId);

        assertDetails(refuse(orderId, "{\"price\": 1}", 400, "VALIDATION_FAILED", VALIDATION_MESSAGE,
                        "an extra field only"),
                List.of(notAllowed("price"), ID_REQUIRED), "an extra field only");

        String extrasBesideValidId = "{\"zeta\": 1, \"quote_version_id\": " + id + ", \"alpha\": \"x\", \"middle\": null}";
        assertDetails(refuse(orderId, extrasBesideValidId, 400, "VALIDATION_FAILED", VALIDATION_MESSAGE,
                        "extras beside a valid id"),
                List.of(notAllowed("zeta"), notAllowed("alpha"), notAllowed("middle")), "extras beside a valid id");

        assertDetails(refuse(orderId, "{\"terms_html\": \"x\", \"due_date\": \"2026-12-01\"}", 400,
                        "VALIDATION_FAILED", VALIDATION_MESSAGE, "two extras and no id"),
                List.of(notAllowed("terms_html"), notAllowed("due_date"), ID_REQUIRED), "two extras and no id");

        assertDetails(refuse(orderId, "{\"quote_version_id\": 0, \"price\": 1}", 400, "VALIDATION_FAILED",
                        VALIDATION_MESSAGE, "a zero id written first, then an extra"),
                List.of(notAllowed("price"), ID_NOT_POSITIVE), "a zero id written first, then an extra");

        assertDetails(refuse(orderId, "{\"quote_version_id\": \"5\", \"note\": \"x\", \"price\": 2}", 400,
                        "VALIDATION_FAILED", VALIDATION_MESSAGE, "a string id, then two extras"),
                List.of(notAllowed("note"), notAllowed("price"), ID_NOT_POSITIVE), "a string id, then two extras");

        assertDetails(refuse(orderId, "{\"price\": 1, \"quote_version_id\": null}", 400, "VALIDATION_FAILED",
                        VALIDATION_MESSAGE, "an extra, then a null id"),
                List.of(notAllowed("price"), ID_REQUIRED), "an extra, then a null id");

        assertDetails(refuse(orderId, "{\"Quote_Version_Id\": " + id + "}", 400, "VALIDATION_FAILED",
                        VALIDATION_MESSAGE, "a differently cased id name"),
                List.of(notAllowed("Quote_Version_Id"), ID_REQUIRED), "a differently cased id name");

        assertDetails(refuse(orderId, "{\"quote_version_id\": " + Long.MAX_VALUE + ", \"price\": 1}", 400,
                        "VALIDATION_FAILED", VALIDATION_MESSAGE, "an extra beside a mismatching id"),
                List.of(notAllowed("price")), "an extra beside a mismatching id");

        Assertions.assertEquals(0, invoiceCount(orderId), "no refused body created an invoice");
        convertOk(home, orderId, EmailOutcome.DELIVERED);
    }

    /**
     * {@code quote_version_id} given more than once in the body object (5.3, D.8 parity) is one 400
     * {@code VALIDATION_FAILED} with exactly one detail, {@code quote_version_id} {@code Must appear at most
     * once.}, whatever the values are: the repeat is checked before the value. The order's accepted versions
     * are v1 then v2 (the latest accepted is v2), so each single-value read would answer differently: v1's id
     * then v2's (last-wins would convert v2), v2's then v1's (last-wins would be the stale 409, first-wins
     * would convert), v2's id twice and three times (any single read would convert), a repeated invalid
     * {@code "x"} (twice, before v2's id and after it), v2's id then a null, and v1's id then v2's under the
     * same key spelt with a JSON unicode escape (one key once decoded). The bodies are hand-written strings,
     * as a Map cannot hold a duplicate key. Beside extra fields, each extra keeps its own {@code Not allowed.}
     * detail, in body order, FIRST and the repeated detail comes LAST (also when the repeat is split around an
     * extra). Boundary: a {@code quote_version_id} nested inside a value, or the key name as a string value,
     * is not a member of the body object and never counts as a repeat. Nothing is written; the single key with
     * the latest accepted id then converts from v2.
     */
    @Test
    void pathA_quoteVersionIdRepeated_is400MustAppearAtMostOnce_whateverTheValues_extrasNotAllowedFirst_nothingWritten_thenTheSingleLatestIdConverts()
            throws Exception {
        long orderId = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);
        long v1 = acceptQuote(home, orderId, ITEMISED_DRAFT, ONE_PIXEL_PNG);
        long v2 = acceptQuote(home, orderId, nonItemisedDraft("330.00"), SECOND_SIGNATURE_PNG);
        Assertions.assertNotEquals(v1, v2, "fixture: two accepted versions");
        Assertions.assertEquals(List.of("ACCEPTED", "ACCEPTED"), quoteVersionStatuses(orderId), "fixture: both accepted");
        Assertions.assertEquals(v2, latestAcceptedQuoteVersionId(orderId), "fixture: the latest accepted version is v2");

        String idKey = "\"quote_version_id\": ";   // written out once per member: the bodies repeat the key
        Map<String, String> repeated = new LinkedHashMap<>();   // label -> raw JSON body
        repeated.put("v1's id, then the latest v2's", "{" + idKey + v1 + ", " + idKey + v2 + "}");
        repeated.put("the latest v2's id, then v1's", "{" + idKey + v2 + ", " + idKey + v1 + "}");
        repeated.put("the latest v2's id twice", "{" + idKey + v2 + ", " + idKey + v2 + "}");
        repeated.put("the latest v2's id three times", "{" + idKey + v2 + ", " + idKey + v2 + ", " + idKey + v2 + "}");
        repeated.put("the invalid \"x\" twice", "{" + idKey + "\"x\", " + idKey + "\"x\"}");
        repeated.put("the invalid \"x\", then the latest v2's id", "{" + idKey + "\"x\", " + idKey + v2 + "}");
        repeated.put("the latest v2's id, then the invalid \"x\"", "{" + idKey + v2 + ", " + idKey + "\"x\"}");
        repeated.put("the latest v2's id, then null", "{" + idKey + v2 + ", " + idKey + "null}");
        repeated.put("v1's id, then v2's under the key spelt with a JSON unicode escape",
                "{" + idKey + v1 + ", \"quote\\u005fversion_id\": " + v2 + "}");
        for (Map.Entry<String, String> body : repeated.entrySet()) {
            JsonNode error = refuse(orderId, body.getValue(), 400, "VALIDATION_FAILED", VALIDATION_MESSAGE, body.getKey());
            assertDetails(error, List.of(ID_REPEATED), body.getKey());
        }

        // Beside extra fields: one Not allowed. per extra, in body order, then the repeated detail LAST.
        String splitAroundAnExtra = "{" + idKey + v1 + ", \"price\": 1, " + idKey + v2 + "}";
        assertDetails(refuse(orderId, splitAroundAnExtra, 400, "VALIDATION_FAILED", VALIDATION_MESSAGE,
                        "a repeat split around an extra"),
                List.of(notAllowed("price"), ID_REPEATED), "a repeat split around an extra");
        String repeatThenTwoExtras = "{" + idKey + v2 + ", " + idKey + v2 + ", \"note\": \"x\", \"price\": 2}";
        assertDetails(refuse(orderId, repeatThenTwoExtras, 400, "VALIDATION_FAILED", VALIDATION_MESSAGE,
                        "the latest id twice, then two extras"),
                List.of(notAllowed("note"), notAllowed("price"), ID_REPEATED), "the latest id twice, then two extras");
        String extrasAroundARepeatedX = "{\"zeta\": 1, " + idKey + "\"x\", \"alpha\": null, " + idKey + "\"x\"}";
        assertDetails(refuse(orderId, extrasAroundARepeatedX, 400, "VALIDATION_FAILED", VALIDATION_MESSAGE,
                        "extras around a repeated \"x\""),
                List.of(notAllowed("zeta"), notAllowed("alpha"), ID_REPEATED), "extras around a repeated \"x\"");

        // Boundary: only members of the body object count; a nested key or a string value is never a repeat.
        assertDetails(refuse(orderId, "{" + idKey + "{" + idKey + v2 + "}}", 400, "VALIDATION_FAILED",
                        VALIDATION_MESSAGE, "the key nested inside the id's own value"),
                List.of(ID_NOT_POSITIVE), "the key nested inside the id's own value");
        assertDetails(refuse(orderId, "{\"wrapper\": {" + idKey + v1 + "}, " + idKey + v2 + "}", 400,
                        "VALIDATION_FAILED", VALIDATION_MESSAGE, "the key nested inside an extra's value"),
                List.of(notAllowed("wrapper")), "the key nested inside an extra's value");
        assertDetails(refuse(orderId, "{" + idKey + "\"quote_version_id\"}", 400, "VALIDATION_FAILED",
                        VALIDATION_MESSAGE, "the key name as the id's string value"),
                List.of(ID_NOT_POSITIVE), "the key name as the id's string value");

        Assertions.assertEquals(0, invoiceCount(orderId), "no refused body created an invoice");
        Conversion conversion = convertOk(home, orderId, EmailOutcome.DELIVERED);
        Assertions.assertEquals(v2, asLong(conversion.invoice().get("source_quote_version_id")),
                "the single key with the latest accepted id converts from v2");
    }

    /**
     * A body that is JSON but not an object - a JSON {@code null}, an array (empty, or holding the valid body),
     * a scalar number (also the bare valid id), a JSON string, a boolean - is one 400 on {@code body}
     * {@code Must be an object.}; unparseable JSON ({@code "{"}, a truncated valid body, a bare word, a missing
     * value) is 400 {@code MALFORMED_JSON} with no details. Nothing is written; the valid body then converts.
     */
    @Test
    void pathA_nonObjectBody_is400BodyMustBeAnObject_unparseableBody_is400MalformedJson_nothingWritten_thenTheIdConverts()
            throws Exception {
        long orderId = acceptedQuoteOrder(home);
        long id = latestAcceptedQuoteVersionId(orderId);

        for (String body : new String[] {"null", "[]", "5", "\"x\"", "true", "[" + idBody(id) + "]",
                String.valueOf(id)}) {
            JsonNode error = refuse(orderId, body, 400, "VALIDATION_FAILED", VALIDATION_MESSAGE, "non-object body " + body);
            assertDetails(error, List.of(BODY_NOT_AN_OBJECT), "non-object body " + body);
        }
        for (String body : new String[] {"{", "{\"quote_version_id\": " + id, "x", "{\"quote_version_id\": }"}) {
            JsonNode error = refuse(orderId, body, 400, "MALFORMED_JSON", MALFORMED_MESSAGE, "malformed body " + body);
            Assertions.assertFalse(error.has("details"), () -> "MALFORMED_JSON carries no details: " + error);
        }
        Assertions.assertEquals(0, invoiceCount(orderId), "no refused body created an invoice");

        convertOk(home, orderId, EmailOutcome.DELIVERED);
    }

    // ================================================================
    // 2. Stale: the id is only compared with the latest ACCEPTED version (5.1, 5.2)
    // ================================================================

    /**
     * Quote v1 is accepted, then a different quote v2 is issued and accepted, so the latest ACCEPTED version
     * is v2. The confirmation that still shows v1 sends v1's id: 409 {@code QUOTE_VERSION_STALE} with the
     * exact message and no details, nothing written. v2's id converts (source v2, v2's totals and signature).
     * In the D5(b) refusal state that conversion creates, v1's id is STILL the stale 409 (the comparison runs
     * before the signature precedence) and v2's id is now 409 {@code INVOICE_ALREADY_ACCEPTED} with the Path
     * A message.
     */
    @Test
    void pathA_olderAcceptedVersionsId_is409QuoteVersionStale_nothingWritten_latestAcceptedIdConverts_thenStaleBeatsD5b()
            throws Exception {
        long orderId = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);
        long v1 = acceptQuote(home, orderId, ITEMISED_DRAFT, ONE_PIXEL_PNG);
        long v2 = acceptQuote(home, orderId, nonItemisedDraft("330.00"), SECOND_SIGNATURE_PNG);
        Assertions.assertNotEquals(v1, v2, "fixture: two accepted versions");
        Assertions.assertEquals(List.of("ACCEPTED", "ACCEPTED"), quoteVersionStatuses(orderId), "fixture: both accepted");
        Assertions.assertEquals(v2, latestAcceptedQuoteVersionId(orderId), "fixture: the latest accepted version is v2");

        JsonNode stale = refuse(orderId, idBody(v1), 409, "QUOTE_VERSION_STALE", QUOTE_VERSION_STALE_MESSAGE,
                "the older accepted v1's id");
        Assertions.assertFalse(stale.has("details"), "QUOTE_VERSION_STALE carries no details");

        Conversion conversion = convertOk(home, orderId, EmailOutcome.DELIVERED);
        Map<String, Object> invoice = conversion.invoice();
        Assertions.assertEquals(1, conversion.versionNumber(), "the first invoice version");
        Assertions.assertEquals(v2, asLong(invoice.get("source_quote_version_id")), "source = the latest accepted v2");
        assertMoney("300.00", invoice.get("sale_price_ex_gst"), "v2's frozen ex total");
        assertMoney("330.00", invoice.get("sale_price_inc_gst"), "v2's frozen inc total");
        Assertions.assertEquals(asLong(quoteVersionById(v2).get("accepted_signature_file_id")),
                asLong(invoice.get("accepted_signature_file_id")), "v2's signature, never v1's");

        // The D5(b) refusal state (the current invoice carries v2's own signature time).
        JsonNode stillStale = refuse(orderId, idBody(v1), 409, "QUOTE_VERSION_STALE", QUOTE_VERSION_STALE_MESSAGE,
                "v1's id after v2 was converted");
        Assertions.assertFalse(stillStale.has("details"));
        JsonNode notNewer = refuse(orderId, idBody(v2), 409, "INVOICE_ALREADY_ACCEPTED", NOT_NEWER_MESSAGE,
                "v2's id after v2 was converted");
        Assertions.assertFalse(notNewer.has("details"), "the D5(b) 409 carries no details");
    }

    /**
     * Ids the server never selected only mismatch: a forged {@code Long.MAX_VALUE}, an id no quote version
     * has, the accepted id of another order of the same store, and the accepted id of an order of another
     * business. Each is 409 {@code QUOTE_VERSION_STALE} with nothing written on this order NOR on the orders
     * those ids belong to. The order's own latest accepted id then converts from its OWN version (never the
     * named one), and the other orders gain no invoice and keep every row.
     */
    @Test
    void pathA_forgedUnusedOrForeignAcceptedIds_are409QuoteVersionStale_nothingWrittenAnywhere_ownIdConvertsFromItsOwnVersion()
            throws Exception {
        long orderId = acceptedQuoteOrder(home);
        long ownId = latestAcceptedQuoteVersionId(orderId);
        long siblingOrder = acceptedQuoteOrder(home);
        long siblingId = latestAcceptedQuoteVersionId(siblingOrder);
        Scope otherBusiness = seedOtherBusiness();
        long foreignOrder = acceptedQuoteOrder(otherBusiness);
        long foreignId = latestAcceptedQuoteVersionId(foreignOrder);
        long unusedId = maxQuoteVersionId() + 1_000;

        Map<String, Long> staleIds = new LinkedHashMap<>();
        staleIds.put("a forged id (Long.MAX_VALUE)", Long.MAX_VALUE);
        staleIds.put("an id no quote version has", unusedId);
        staleIds.put("another order's accepted id (same store)", siblingId);
        staleIds.put("another business's accepted id", foreignId);
        for (Map.Entry<String, Long> stale : staleIds.entrySet()) {
            Assertions.assertNotEquals(ownId, stale.getValue().longValue(), "fixture: " + stale.getKey());
            JsonNode error = assertRefused(home, orderId, idBody(stale.getValue()), 409, "QUOTE_VERSION_STALE",
                    QUOTE_VERSION_STALE_MESSAGE, stale.getKey(), orderId, siblingOrder, foreignOrder);
            Assertions.assertFalse(error.has("details"), stale.getKey());
        }

        Map<String, Object> siblingBefore = orderState(siblingOrder);
        Map<String, Object> foreignBefore = orderState(foreignOrder);
        Conversion conversion = convertOk(home, orderId, EmailOutcome.DELIVERED);
        Assertions.assertEquals(ownId, asLong(conversion.invoice().get("source_quote_version_id")),
                "the conversion uses the order's own latest accepted version");
        Assertions.assertEquals(siblingBefore, orderState(siblingOrder), "the sibling order is untouched");
        Assertions.assertEquals(foreignBefore, orderState(foreignOrder), "the other business's order is untouched");
        Assertions.assertEquals(0, invoiceCount(siblingOrder));
        Assertions.assertEquals(0, invoiceCount(foreignOrder));
    }

    // ================================================================
    // 3. The order of checks (5.5)
    // ================================================================

    /**
     * Step 3 before step 4: with no ACCEPTED quote version - no quote at all, a saved draft only, or an
     * ISSUED (unsigned) version with an ACTIVE link - every body is the 422 {@code QUOTE_NOT_ACCEPTED} (no
     * details), never a 400 or a 409: absent, blank, a JSON null, {@code {}}, malformed, an array, a scalar,
     * an extra field, a string or zero id, a repeated id, a forged id, another order's ACCEPTED id and the
     * issued version's own id. Nothing is written (the issued version and its token included).
     */
    @Test
    void pathA_noAcceptedQuote_is422QuoteNotAccepted_beforeAnyBodyValidation_whateverTheBody_nothingWritten()
            throws Exception {
        long noQuote = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);
        long draftOnly = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);
        saveDraft(home, draftOnly, ITEMISED_DRAFT);
        long issuedOnly = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);
        saveDraft(home, issuedOnly, ITEMISED_DRAFT);
        sendAndExtractToken(home, issuedOnly);
        Assertions.assertEquals(List.of("ISSUED"), quoteVersionStatuses(issuedOnly), "fixture: v1 issued, unsigned");
        long issuedId = asLong(quoteVersionRow(issuedOnly, 1).get("quote_version_id"));
        long otherAcceptedId = latestAcceptedQuoteVersionId(acceptedQuoteOrder(home));
        Assertions.assertEquals(0, quoteVersionCount(noQuote), "fixture: no quote version");
        Assertions.assertEquals(0, quoteVersionCount(draftOnly), "fixture: a saved draft is never a version");

        String[] bodies = {null, "  ", "null", "{}", "{", "[]", "5", "{\"price\": 1}",
                "{\"quote_version_id\": \"5\"}", "{\"quote_version_id\": 0}",
                "{\"quote_version_id\": 5, \"quote_version_id\": 5}", idBody(Long.MAX_VALUE),
                idBody(otherAcceptedId), idBody(issuedId)};
        Map<String, Long> orders = new LinkedHashMap<>();
        orders.put("no quote", noQuote);
        orders.put("draft only", draftOnly);
        orders.put("issued only", issuedOnly);
        for (Map.Entry<String, Long> order : orders.entrySet()) {
            for (String body : bodies) {
                String label = order.getKey() + ", body " + body;
                JsonNode error = refuse(order.getValue(), body, 422, "QUOTE_NOT_ACCEPTED", QUOTE_NOT_ACCEPTED_MESSAGE,
                        label);
                Assertions.assertFalse(error.has("details"), label + ": QUOTE_NOT_ACCEPTED carries no details");
            }
            Assertions.assertEquals(0, invoiceCount(order.getValue()), order.getKey() + ": no invoice");
        }
    }

    /**
     * Step 2 before everything after it: an order of ANOTHER store of the same business and an order of
     * another business - each with its own ACCEPTED quote, so in scope the bad bodies would be 400 and the
     * valid one would convert - are 404 {@code ORDER_NOT_FOUND} (no details, the order number never echoed)
     * for the home session whatever the body: absent, blank, JSON null, {@code {}}, malformed, non-object,
     * an extra field, a string or zero id, the order's own valid id repeated, a forged id and the order's own
     * valid id. Nothing is written on them. The 404 body is byte-identical to a missing order's (no existence
     * leak), and a missing order with a malformed body is 404 {@code ORDER_NOT_FOUND} too (never 400). In
     * their own scopes both orders then convert: every 404 was the scope.
     */
    @Test
    void pathA_outOfScopeOrders_are404OrderNotFound_beforeAnyBodyValidation_noExistenceLeak_thenConvertInTheirOwnScope()
            throws Exception {
        Scope secondStore = seedSecondStoreOfHome();
        long secondStoreOrder = acceptedQuoteOrder(secondStore);
        Scope otherBusiness = seedOtherBusiness();
        long otherBusinessOrder = acceptedQuoteOrder(otherBusiness);

        MvcResult missing = perform(pathARequest(home, MISSING_ORDER_ID, idBody(Long.MAX_VALUE)));
        assertError(missing, 404, "ORDER_NOT_FOUND", ORDER_NOT_FOUND_MESSAGE, "a missing order");
        String missingBody = bodyOf(missing);

        Map<String, Long> orders = new LinkedHashMap<>();
        orders.put("another store's order", secondStoreOrder);
        orders.put("another business's order", otherBusinessOrder);
        for (Map.Entry<String, Long> order : orders.entrySet()) {
            long orderId = order.getValue();
            long ownId = latestAcceptedQuoteVersionId(orderId);
            String orderNumber = orderNumber(orderId);
            String[] bodies = {null, "  ", "null", "{}", "{", "[]", "5", "{\"price\": 1}",
                    "{\"quote_version_id\": \"5\"}", "{\"quote_version_id\": 0}",
                    "{\"quote_version_id\": " + ownId + ", \"quote_version_id\": " + ownId + "}",
                    idBody(Long.MAX_VALUE), idBody(ownId)};
            for (String body : bodies) {
                String label = order.getKey() + ", body " + body;
                JsonNode error = assertRefused(home, orderId, body, 404, "ORDER_NOT_FOUND", ORDER_NOT_FOUND_MESSAGE,
                        label, orderId);
                Assertions.assertFalse(error.has("details"), label);
            }
            String crossScopeBody = bodyOf(perform(pathARequest(home, orderId, idBody(ownId))));
            Assertions.assertEquals(missingBody, crossScopeBody,
                    order.getKey() + ": the 404 is byte-identical to a missing order's (no existence leak)");
            Assertions.assertFalse(crossScopeBody.contains(orderNumber), order.getKey() + ": the order is never echoed");
            Assertions.assertEquals(0, invoiceCount(orderId), order.getKey() + ": no invoice");
        }
        assertRefused(home, MISSING_ORDER_ID, "{", 404, "ORDER_NOT_FOUND", ORDER_NOT_FOUND_MESSAGE,
                "a missing order with a malformed body");

        convertOk(secondStore, secondStoreOrder, EmailOutcome.DELIVERED);
        convertOk(otherBusiness, otherBusinessOrder, EmailOutcome.DELIVERED);
    }

    /**
     * Step 5 before step 6, in both D5(b) refusal states: (i) after a Path A conversion, whose invoice carries
     * the quote's own signature time (equal times), and (ii) after an in-app D.8 signature strictly later than
     * the quote's. In each, a forged id and another order's accepted id are 409 {@code QUOTE_VERSION_STALE}
     * (never {@code INVOICE_ALREADY_ACCEPTED}), and only the valid id reaches the D5(b) refusal: 409
     * {@code INVOICE_ALREADY_ACCEPTED} with the Path A message (not D.8's default wording). Nothing is written.
     */
    @Test
    void pathA_staleIdInEitherD5bRefusalState_is409QuoteVersionStale_notInvoiceAlreadyAccepted_validIdGetsThePathAD5bRefusal()
            throws Exception {
        // (i) Converted by Path A: the current invoice's accepted_at IS the quote's.
        long converted = acceptedQuoteOrder(home);
        long convertedId = latestAcceptedQuoteVersionId(converted);
        convertOk(home, converted, EmailOutcome.DELIVERED);
        Assertions.assertEquals(quoteVersionById(convertedId).get("accepted_at"), invoiceRow(converted, 1).get("accepted_at"),
                "fixture (i): the current invoice was signed at the same time as the quote");

        // (ii) Signed in-app (D.8) after the quote was accepted: the current invoice's signature is later.
        long signedInApp = acceptedQuoteOrder(home);
        long signedInAppId = latestAcceptedQuoteVersionId(signedInApp);
        createInvoiceD1(home, signedInApp);
        signInvoiceInApp(home, signedInApp);
        Timestamp quoteSignedAt = (Timestamp) quoteVersionById(signedInAppId).get("accepted_at");
        Timestamp invoiceSignedAt = (Timestamp) invoiceRow(signedInApp, 2).get("accepted_at");
        Assertions.assertTrue(invoiceSignedAt.after(quoteSignedAt),
                () -> "fixture (ii): the in-app signature (" + invoiceSignedAt + ") is later than the quote's ("
                        + quoteSignedAt + ")");

        Map<String, long[]> states = new LinkedHashMap<>();   // label -> {order, own id, another order's id}
        states.put("(i) after Path A", new long[] {converted, convertedId, signedInAppId});
        states.put("(ii) after an in-app D.8 signature", new long[] {signedInApp, signedInAppId, convertedId});
        for (Map.Entry<String, long[]> state : states.entrySet()) {
            long orderId = state.getValue()[0];
            long ownId = state.getValue()[1];
            long foreignId = state.getValue()[2];
            for (long staleId : new long[] {Long.MAX_VALUE, foreignId}) {
                String label = state.getKey() + ", stale id " + staleId;
                JsonNode error = refuse(orderId, idBody(staleId), 409, "QUOTE_VERSION_STALE",
                        QUOTE_VERSION_STALE_MESSAGE, label);
                Assertions.assertFalse(error.has("details"), label);
            }
            JsonNode notNewer = refuse(orderId, idBody(ownId), 409, "INVOICE_ALREADY_ACCEPTED", NOT_NEWER_MESSAGE,
                    state.getKey() + ", the valid id");
            Assertions.assertFalse(notNewer.has("details"), state.getKey() + ": the D5(b) 409 carries no details");
        }
    }

    /**
     * Step 5 before step 7: an order whose retained live preconditions fail (no BILLING row, no lay date and
     * no lay date status) and an overpaid order (100.00 recorded against an earlier invoice, then a 66.00
     * quote accepted) both answer a forged or foreign id with 409 {@code QUOTE_VERSION_STALE}; only the
     * valid id reaches the 422s - {@code INVOICE_PRECONDITIONS_NOT_MET} with exactly the three failing
     * details, and {@code BUSINESS_RULE_VIOLATION} with the overpayment message. Nothing is written.
     */
    @Test
    void pathA_staleIdWithFailingPreconditionsOrAnOverpayment_is409QuoteVersionStale_validIdGetsThe422()
            throws Exception {
        // Retained live preconditions broken after the acceptance.
        long broken = acceptedQuoteOrder(home);
        long brokenId = latestAcceptedQuoteVersionId(broken);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "DELETE FROM order_address WHERE order_id = ? AND address_type = 'BILLING'::address_type", broken));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE sales_order SET proposed_lay_date = NULL, lay_date_status = NULL WHERE order_id = ?", broken));
        clearJpaCache();

        // Overpaid: D.1 at the live 110.00 inc, 100.00 paid, then a 66.00 inc (60.00 ex) quote accepted.
        long overpaid = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);
        createInvoiceD1(home, overpaid);
        recordCashPayment(home, overpaid, "100.00");
        long overpaidId = acceptQuote(home, overpaid, nonItemisedDraft("66.00"), ONE_PIXEL_PNG);

        for (long staleId : new long[] {Long.MAX_VALUE, overpaidId}) {
            JsonNode error = refuse(broken, idBody(staleId), 409, "QUOTE_VERSION_STALE", QUOTE_VERSION_STALE_MESSAGE,
                    "failing preconditions, stale id " + staleId);
            Assertions.assertFalse(error.has("details"));
        }
        JsonNode preconditions = refuse(broken, idBody(brokenId), 422, "INVOICE_PRECONDITIONS_NOT_MET",
                PRECONDITIONS_MESSAGE, "failing preconditions, the valid id");
        assertDetails(preconditions, List.of(BILLING_REQUIRED, LAY_DATE_REQUIRED, LAY_DATE_STATUS_REQUIRED),
                "failing preconditions, the valid id");

        for (long staleId : new long[] {Long.MAX_VALUE, brokenId}) {
            JsonNode error = refuse(overpaid, idBody(staleId), 409, "QUOTE_VERSION_STALE", QUOTE_VERSION_STALE_MESSAGE,
                    "overpaid, stale id " + staleId);
            Assertions.assertFalse(error.has("details"));
        }
        JsonNode overpayment = refuse(overpaid, idBody(overpaidId), 422, "BUSINESS_RULE_VIOLATION",
                PAYMENTS_EXCEED_MESSAGE, "overpaid, the valid id");
        Assertions.assertFalse(overpayment.has("details"));
    }

    // ================================================================
    // 4. The post-commit signed-PDF email (6.1, 6.2, 11) - in this transactional context
    // ================================================================

    /**
     * Delivered: the saved email is stored WITH surrounding spaces; the one invoice email goes to the
     * trimmed address, with the tenant's subject and body, the appended version's stored PDF attached byte for
     * byte as {@code invoice-{order}-v1.pdf}; both markers carry the same stamp (shown on the response) and
     * the delivered message is returned, with no WARN. The order also has a NEWER quote v2 that is ISSUED and
     * still waits for a signature (ACTIVE token) and a newer saved draft: Path A writes no quote draft,
     * version, line or token row (v2 stays ISSUED, its link ACTIVE) and sends no quote email, store
     * notification or SMS (all asserted in {@link #convertOk}, and the quote rows again here).
     */
    @Test
    void pathA_emailDelivered_toTheTrimmedSavedEmail_attachmentIsTheAppendedVersionsStoredPdf_bothMarkersSameStamp_noQuoteWrites()
            throws Exception {
        long orderId = seedInvoiceReadyOrder(home, PADDED_CUSTOMER_EMAIL);
        Assertions.assertEquals(PADDED_CUSTOMER_EMAIL, savedCustomerEmail(orderId), "fixture: saved with padding");
        long acceptedId = acceptQuote(home, orderId, ITEMISED_DRAFT, ONE_PIXEL_PNG);
        saveDraft(home, orderId, nonItemisedDraft("330.00"));
        String newerToken = sendAndExtractToken(home, orderId);
        saveDraft(home, orderId, nonItemisedDraft("440.00"));
        Assertions.assertEquals(List.of("ACCEPTED", "ISSUED"), quoteVersionStatuses(orderId),
                "fixture: v1 accepted, the newer v2 issued and unsigned");
        Map<String, Object> quoteBefore = quoteState(orderId);

        Conversion conversion = convertOk(home, orderId, EmailOutcome.DELIVERED);

        Assertions.assertEquals(acceptedId, asLong(conversion.invoice().get("source_quote_version_id")),
                "source = the latest ACCEPTED v1, never the newer ISSUED v2");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "exactly one invoice email");
        Assertions.assertEquals(CUSTOMER_EMAIL, sent.get(0).recipientEmail(), "the TRIMMED saved email");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no failed attempt");
        Timestamp stamp = (Timestamp) invoiceRow(orderId, 1).get("last_emailed_at");
        Assertions.assertNotNull(stamp, "the appended version is stamped");
        Assertions.assertEquals(stamp, mirror(orderId), "the dashboard mirror carries the same stamp");
        Assertions.assertEquals(quoteBefore, quoteState(orderId),
                "Path A writes no quote draft / version / line / token row");
        assertPublicQuoteState(newerToken, "ACTIVE");
    }

    /**
     * Delivery fails ({@code failNextSend}: the sender throws {@code InvoiceEmailException}): the conversion
     * stands - 201 with the could-not-be-emailed message, the appended signed version committed (and read
     * back by D.3) - one recorded failed attempt with the same content, no delivered email, both markers
     * null (the non-null mirror sentinel was reset at persist time) and exactly the WARN failed line.
     */
    @Test
    void pathA_emailDeliveryFails_201FailedMessage_invoiceStillCreated_bothMarkersNull_warnFailedLine() throws Exception {
        long orderId = acceptedQuoteOrder(home);
        invoiceEmailSender.failNextSend();

        Conversion conversion = convertOk(home, orderId, EmailOutcome.FAILED);

        Assertions.assertEquals("Signed invoice email failed for order " + orderId + " invoice v1 (non-fatal; the "
                + "committed invoice stands, markers stay null)", conversion.warning(), "the WARN failed line");
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "nothing delivered");
        Assertions.assertEquals(1, invoiceEmailSender.failedEmails().size(), "one failed attempt");
        assertCurrentInvoiceUnemailed(orderId, 1);
    }

    /**
     * No email gate, missing saved email: the saved email is NULL (transactional constraint relaxation). The
     * conversion still commits (201, the skipped message, the appended signed version), the sender is never
     * called (no delivered and no failed email), both markers stay null and exactly the WARN skip line is
     * logged (order id + version, no address, no throwable).
     */
    @Test
    void pathA_savedEmailMissing_noGate_201SkippedMessage_invoiceCreated_noSenderCall_bothMarkersNull_warnSkipLine()
            throws Exception {
        long orderId = acceptedQuoteOrder(home);
        relaxCustomerEmailConstraints();
        setSavedEmail(orderId, null);
        Assertions.assertNull(savedCustomerEmail(orderId), "fixture: the saved email is NULL");

        assertSkippedConversion(orderId);
    }

    /** As the missing case, with a blank saved email ({@code "   "}, transactional constraint relaxation). */
    @Test
    void pathA_savedEmailBlank_noGate_201SkippedMessage_invoiceCreated_noSenderCall_bothMarkersNull_warnSkipLine()
            throws Exception {
        long orderId = acceptedQuoteOrder(home);
        relaxCustomerEmailConstraints();
        setSavedEmail(orderId, "   ");
        Assertions.assertEquals("   ", savedCustomerEmail(orderId), "fixture: the saved email is blank");

        assertSkippedConversion(orderId);
    }

    /**
     * As the missing case, with an app-invalid but DB-valid saved email ({@code jordan@localhost}: the
     * domain has no interior dot); no constraint is relaxed. The WARN line never carries the saved value.
     */
    @Test
    void pathA_savedEmailAppInvalid_noGate_201SkippedMessage_invoiceCreated_noSenderCall_bothMarkersNull_warnSkipLine()
            throws Exception {
        long orderId = acceptedQuoteOrder(home);
        setSavedEmail(orderId, APP_INVALID_EMAIL);
        Assertions.assertEquals(APP_INVALID_EMAIL, savedCustomerEmail(orderId), "fixture: an app-invalid saved email");

        Conversion conversion = assertSkippedConversion(orderId);
        Assertions.assertFalse(conversion.warning().contains(APP_INVALID_EMAIL), "the WARN never carries the saved value");
    }

    // ================================================================
    // 5. invoice_date: the conversion day (5.5 step 8)
    // ================================================================

    /**
     * Path A dates its version TODAY. The order has an unsigned Path B invoice v1 dated four days ago, and the
     * quote's acceptance is moved to three days ago at 10:15 (both by SQL after the real flows). Path A's v2
     * stores and returns an {@code invoice_date} within the local days read just before and after the request
     * - neither v1's date (Path A does not copy the current version's date, as D.8 now does) nor the
     * acceptance day - while {@code accepted_at} is the quote's earlier signing time. Its stored PDF (the
     * emailed attachment) shows today under "Issued / Due", the due date two days before the lay date and the
     * acceptance caption with the earlier time; v1 is untouched.
     */
    @Test
    void pathA_invoiceDateIsTheConversionDay_evenWhenTheQuoteWasAcceptedAndTheCurrentInvoiceDatedOnEarlierDays()
            throws Exception {
        long orderId = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);
        createInvoiceD1(home, orderId);
        LocalDate testDay = LocalDate.now();
        LocalDate v1Date = testDay.minusDays(4);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET invoice_date = ? WHERE order_id = ? AND version_number = 1",
                Date.valueOf(v1Date), orderId), "fixture: v1 dated four days ago");
        long acceptedId = acceptQuote(home, orderId, ITEMISED_DRAFT, ONE_PIXEL_PNG);
        LocalDateTime acceptedAt = testDay.minusDays(3).atTime(10, 15);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE quote_version SET accepted_at = ? WHERE quote_version_id = ?",
                Timestamp.valueOf(acceptedAt), acceptedId), "fixture: the quote accepted three days ago");
        clearJpaCache();
        Map<String, Object> v1Before = invoiceRow(orderId, 1);

        Conversion conversion = convertOk(home, orderId, EmailOutcome.DELIVERED);

        Assertions.assertEquals(2, conversion.versionNumber(), "Path A appends v2 after the unsigned v1");
        Map<String, Object> v2 = conversion.invoice();
        LocalDate invoiceDate = ((Date) v2.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(conversion.requestStart().toLocalDate())
                        || invoiceDate.isAfter(conversion.requestEnd().toLocalDate()),
                () -> "invoice_date must be the conversion day but was " + invoiceDate);
        Assertions.assertNotEquals(v1Date, invoiceDate, "never the current version's invoice_date");
        Assertions.assertNotEquals(acceptedAt.toLocalDate(), invoiceDate, "never the acceptance day");
        Assertions.assertEquals(Timestamp.valueOf(acceptedAt), v2.get("accepted_at"),
                "accepted_at is the quote's earlier signing time");
        Assertions.assertEquals(DUE_DATE, ((Date) v2.get("due_date")).toLocalDate(), "due two days before the lay date");

        JsonNode invoice = readJson(conversion.result()).path("data").path("invoice");
        Assertions.assertEquals(invoiceDate.toString(), invoice.path("invoice_date").asText(), "JSON invoice_date");
        Assertions.assertEquals(JSON_TIMESTAMP.format(acceptedAt), invoice.path("accepted_at").asText(),
                "JSON accepted_at: the earlier signing time");

        String content = noSpace(pdfText(conversion.pdf())).toUpperCase(Locale.ROOT);
        Assertions.assertTrue(content.contains("ISSUED/DUE" + DISPLAY_DATE.format(invoiceDate)),
                () -> "the PDF shows the conversion day under Issued / Due: " + content);
        Assertions.assertTrue(content.contains("DUE" + DISPLAY_DATE.format(DUE_DATE)),
                () -> "the PDF shows the due date: " + content);
        Assertions.assertFalse(content.contains("ISSUED/DUE" + DISPLAY_DATE.format(v1Date)),
                "the PDF is never dated with v1's invoice_date");
        Assertions.assertFalse(content.contains("ISSUED/DUE" + DISPLAY_DATE.format(acceptedAt.toLocalDate())),
                "the PDF is never dated with the acceptance day");
        String visual = noSpace(pdfTextByPosition(conversion.pdf()));
        String caption = noSpace("Accepted by " + CUSTOMER_NAME + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        Assertions.assertTrue(visual.contains(caption),
                () -> "the acceptance caption shows the earlier signing time '" + caption + "': " + visual);
        Assertions.assertEquals(v1Before, invoiceRow(orderId, 1), "the earlier v1 is untouched");
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

    /** One error detail: section (null, and absent from the JSON, for request-shape errors), field, message. */
    private record Detail(String section, String field, String message) {
    }

    /** How many messages every recording sender holds (sent and failed). */
    private record SenderCounts(int invoiceSent, int invoiceFailed, int quoteSent, int quoteFailed,
                                int notificationsSent, int notificationsFailed, int smsSent, int smsFailed) {
    }

    /** The post-commit signed-PDF email outcome a Path A conversion reports (16G contract 6.1). */
    private enum EmailOutcome {
        DELIVERED(CREATED_EMAILED_MESSAGE),
        FAILED(CREATED_EMAIL_FAILED_MESSAGE),
        SKIPPED(CREATED_EMAIL_SKIPPED_MESSAGE);

        private final String message;

        EmailOutcome(String message) {
            this.message = message;
        }
    }

    /**
     * A 201 Path A call: its result, the appended version (number, row, stored PDF bytes), the local times read
     * just before and after the request, and the one WARN line it logged (null when none).
     */
    private record Conversion(MvcResult result, int versionNumber, Map<String, Object> invoice, byte[] pdf,
                              LocalDateTime requestStart, LocalDateTime requestEnd, String warning) {
    }

    /**
     * A business (active, ABN + bank details, NO terms), its store, one salesperson and that salesperson's
     * store access, all with RETURNING ids. The slug matches chk_business_slug_format and is not reserved.
     */
    private Scope seedTenant(String slug, String businessName, String storeCode, String salespersonCode, String tag) {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number, "
                        + " terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, '51 824 753 556', 'Precondition Bank', '062-000', ?, '12345678', "
                        + " NULL, NULL) RETURNING business_id",
                Long.class, businessName, slug, businessName + " Pty Ltd");
        Assertions.assertNotNull(businessId, "business insert must return an id");
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, ?, ?, '0299990000', ?, '1 Storefront Way', 'Parramatta', 'NSW', '2150') "
                        + "RETURNING store_id",
                Integer.class, businessId, businessName + " Store", storeCode,
                "store." + tag + ".precondition@example.com");
        Assertions.assertNotNull(storeId, "store insert must return an id");
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Sam', 'Seller', ?, ?, 'not-a-real-hash-precondition') RETURNING user_id",
                Long.class, businessId, salespersonCode, "sam.seller." + tag + ".precondition@example.com");
        Assertions.assertNotNull(userId, "app_user insert must return an id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        clearJpaCache();
        return new Scope(businessId, storeId, userId, slug, storeCode, salespersonCode);
    }

    /** A second store of the home business, with REAL access for the home salesperson (the guard passes). */
    private Scope seedSecondStoreOfHome() {
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Version Precondition Second Store', ?, '0299990001', "
                        + " 'store.second.precondition@example.com', '2 Storefront Way', 'Parramatta', 'NSW', '2150') "
                        + "RETURNING store_id",
                Integer.class, home.businessId(), SECOND_STORE_CODE);
        Assertions.assertNotNull(storeId, "store insert must return an id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                home.businessId(), home.userId(), storeId);
        clearJpaCache();
        return new Scope(home.businessId(), storeId, home.userId(), SLUG, SECOND_STORE_CODE, SALESPERSON_CODE);
    }

    private Scope seedOtherBusiness() {
        return seedTenant(OTHER_SLUG, OTHER_BUSINESS_NAME, OTHER_STORE_CODE, OTHER_SALESPERSON_CODE, "other");
    }

    /**
     * A SOFT order of {@code scope} that passes every quote, invoice and Path A gate: details of sale, lay date
     * 2026-12-01 CONFIRMED, the customer Jordan Precondition with {@code customerEmail}, INSTALLATION and
     * BILLING addresses, and one priced charge line of 100.00 ex / 40.00 cost (110.00 inc live).
     */
    private long seedInvoiceReadyOrder(Scope scope, String customerEmail) {
        int s = ++orderSeq;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, flooring_type, "
                        + " order_status, week_number, week_year, details_of_sale, proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 42, 2026, ?, ?, "
                        + " 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, scope.businessId(), scope.storeId(), scope.userId(), s,
                scope.storeCode() + "." + scope.salespersonCode() + "." + String.format("%05d", s),
                DETAILS, Date.valueOf(LAY_DATE));
        Assertions.assertNotNull(orderId, "order insert must return an id");
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, middle_name, last_name, email, mobile) "
                        + "VALUES (?, ?, NULL, ?, ?, ?)",
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, customerEmail, CUSTOMER_MOBILE);
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
        String code = "QVPC" + (++chargeSeq);
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Precondition supply and lay', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, scope.storeId(), code);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Precondition supply and lay', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, code);
        clearJpaCache();
        return orderId;
    }

    /** An invoice-ready order of {@code scope} with the itemised quote v1 (250.00 / 275.00) accepted publicly. */
    private long acceptedQuoteOrder(Scope scope) throws Exception {
        long orderId = seedInvoiceReadyOrder(scope, CUSTOMER_EMAIL);
        acceptQuote(scope, orderId, ITEMISED_DRAFT, ONE_PIXEL_PNG);
        return orderId;
    }

    /** Save {@code draftJson}, issue it (send-email) and accept it publicly; returns the accepted version's id. */
    private long acceptQuote(Scope scope, long orderId, String draftJson, byte[] signaturePng) throws Exception {
        saveDraft(scope, orderId, draftJson);
        String token = sendAndExtractToken(scope, orderId);
        Long issuedId = jdbcTemplate.queryForObject(
                "SELECT quote_version_id FROM quote_version WHERE order_id = ? AND status = 'ISSUED'", Long.class, orderId);
        Assertions.assertNotNull(issuedId, "fixture: the send issued a version");
        acceptPublicly(token, signaturePng);
        Assertions.assertEquals("ACCEPTED", quoteVersionById(issuedId).get("status"), "fixture: accepted publicly");
        Assertions.assertEquals(issuedId.longValue(), latestAcceptedQuoteVersionId(orderId),
                "fixture: the version just accepted is the latest accepted one");
        return issuedId;
    }

    private static String nonItemisedDraft(String finalIncTotal) {
        return "{\"itemised\": false, \"final_total_inc_gst\": " + finalIncTotal + ", \"lines\": []}";
    }

    /** In-transaction DDL (rolled back with the test): let a customer email be NULL or blank. */
    private void relaxCustomerEmailConstraints() {
        jdbcTemplate.execute("ALTER TABLE order_customer DROP CONSTRAINT chk_order_customer_email_format");
        jdbcTemplate.execute("ALTER TABLE order_customer ALTER COLUMN email DROP NOT NULL");
    }

    private void setSavedEmail(long orderId, String email) {
        int updated = email == null
                ? jdbcTemplate.update("UPDATE order_customer SET email = NULL WHERE order_id = ?", orderId)
                : jdbcTemplate.update("UPDATE order_customer SET email = ? WHERE order_id = ?", email, orderId);
        Assertions.assertEquals(1, updated, "one customer row");
        clearJpaCache();
    }

    /** Give the section 11.1 mirror a non-null value when it has none, so any write to it is observable. */
    private void ensureMirrorSentinel(long orderId) {
        if (mirror(orderId) == null) {
            jdbcTemplate.update("UPDATE sales_order SET last_emailed_at = ? WHERE order_id = ?", MIRROR_SENTINEL, orderId);
            clearJpaCache();
        }
        Assertions.assertNotNull(mirror(orderId), "fixture: the mirror carries a non-null value");
    }

    /**
     * Detach every hydrated entity: the MockMvc calls of one test share the test transaction's persistence
     * context, so after a raw JDBC write a later JPA read would otherwise return the stale cached entity.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    // ================================================================
    // Helpers: requests and the real flows
    // ================================================================

    private static String orderUrl(Scope scope, Object orderId) {
        return "/api/v1/" + scope.slug() + "/orders/" + orderId;
    }

    private static String quoteUrl(Scope scope, Object orderId, String suffix) {
        return orderUrl(scope, orderId) + "/quote/" + suffix;
    }

    private static String invoicesUrl(Scope scope, Object orderId) {
        return orderUrl(scope, orderId) + "/invoices";
    }

    private static String publicQuoteUrl(String token) {
        return "/api/v1/public/quotes/" + token;
    }

    /** The Path A request as {@code scope}'s session (JSON when a body is given; a null body sends none). */
    private static MockHttpServletRequestBuilder pathARequest(Scope scope, Object orderId, String body) {
        MockHttpServletRequestBuilder request = post(quoteUrl(scope, orderId, "create-invoice")).session(scope.session());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** The 16G PR 3 Path A body naming one quote version id. */
    private static String idBody(long quoteVersionId) {
        return "{\"quote_version_id\": " + quoteVersionId + "}";
    }

    /** Perform with the JPA cache cleared on both sides (raw SQL edits happen between calls). */
    private MvcResult perform(RequestBuilder request) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        return result;
    }

    private void saveDraft(Scope scope, long orderId, String json) throws Exception {
        MvcResult result = perform(put(quoteUrl(scope, orderId, "draft")).session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content(json));
        assertStatus(result, 200, "PUT quote draft");
    }

    /** Issue (or re-issue) via the protected send-email and return the plaintext token from the link. */
    private String sendAndExtractToken(Scope scope, long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = perform(post(quoteUrl(scope, orderId, "send-email")).session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertStatus(result, 201, "POST quote send-email");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "exactly one quote email must have been recorded");
        String body = sent.get(sent.size() - 1).bodyText();
        Matcher matcher = PUBLIC_LINK_TOKEN.matcher(body);
        Assertions.assertTrue(matcher.find(), () -> "the recorded quote email must carry the /q/{token} link: " + body);
        return matcher.group(1);
    }

    /** The REAL public accept: token-only (NO session), exactly one PNG {@code signature} part. */
    private void acceptPublicly(String token, byte[] signaturePng) throws Exception {
        MvcResult result = perform(multipart(publicQuoteUrl(token) + "/accept")
                .file(new MockMultipartFile("signature", "signature.png", "image/png", signaturePng)));
        assertStatus(result, 201, "public quote accept");
        JsonNode json = readJson(result);
        Assertions.assertEquals("INACTIVE", json.path("data").path("state").asText());
        Assertions.assertEquals("Quote accepted.", json.path("message").asText());
    }

    private void assertPublicQuoteState(String token, String expectedState) throws Exception {
        MvcResult result = perform(get(publicQuoteUrl(token)));
        assertStatus(result, 200, "public quote read");
        Assertions.assertEquals(expectedState, readJson(result).path("data").path("state").asText(),
                "the public link state");
    }

    /** D.1: the unsigned Path B invoice v1 (empty body). */
    private void createInvoiceD1(Scope scope, long orderId) throws Exception {
        MvcResult result = perform(post(invoicesUrl(scope, orderId)).session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertStatus(result, 201, "D.1 create invoice");
        Assertions.assertEquals("Invoice created.", readJson(result).path("message").asText());
    }

    /**
     * D.8 as amended by 16G PR 3: the mandatory {@code expected_version_number} (the order's CURRENT invoice
     * version, read now) and one PNG {@code signature} part; no name part.
     */
    private void signInvoiceInApp(Scope scope, long orderId) throws Exception {
        MvcResult result = perform(multipart(invoicesUrl(scope, orderId) + "/current/accept")
                .file(new MockMultipartFile("signature", "signature.png", "image/png", ONE_PIXEL_PNG))
                .param("expected_version_number", String.valueOf(currentInvoiceVersion(orderId)))
                .session(scope.session()));
        assertStatus(result, 201, "D.8 in-app invoice signature");
    }

    /** D.7: record a CASH payment (appends a new invoice version; never emails). */
    private void recordCashPayment(Scope scope, long orderId, String amount) throws Exception {
        MvcResult result = perform(post(orderUrl(scope, orderId) + "/payments").session(scope.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"));
        assertStatus(result, 201, "D.7 record payment");
        Assertions.assertEquals("Payment recorded. Current invoice updated.", readJson(result).path("message").asText());
    }

    // ================================================================
    // Helpers: Path A refusal and success
    // ================================================================

    /** {@link #assertRefused} as the home session, watching the one order the request names. */
    private JsonNode refuse(long orderId, String body, int status, String code, String message, String label)
            throws Exception {
        return assertRefused(home, orderId, body, status, code, message, label, orderId);
    }

    /**
     * A refused Path A call to {@code orderIdInUrl} as {@code scope}: every watched order's mirror first
     * carries a non-null value (so a reset would show); the call returns the expected error envelope; and
     * NOTHING changes - {@link #refusalState} is equal before and after - and no WARN line is logged.
     */
    private JsonNode assertRefused(Scope scope, long orderIdInUrl, String body, int status, String code,
                                   String message, String label, long... watchedOrders) throws Exception {
        for (long watched : watchedOrders) {
            ensureMirrorSentinel(watched);
        }
        Map<String, Object> before = refusalState(watchedOrders);
        ListAppender<ILoggingEvent> serviceLog = captureServiceLog();
        MvcResult result;
        try {
            result = perform(pathARequest(scope, orderIdInUrl, body));
        } finally {
            releaseServiceLog(serviceLog);
        }
        JsonNode error = assertError(result, status, code, message, label);
        Assertions.assertEquals(before, refusalState(watchedOrders), () -> label + ": a refused Path A call writes nothing");
        Assertions.assertEquals(List.of(), warningTexts(serviceLog), label + ": a refusal attempts no email (no WARN)");
        return error;
    }

    /**
     * A successful Path A call as {@code scope}, with the body {@code {"quote_version_id": N}} naming the order's
     * latest ACCEPTED version (read from the database now), and every invariant of an allowed conversion:
     * exactly one appended invoice version (earlier rows unchanged) from that version with its inherited
     * acceptance and an {@code invoice_date} within the request's local days; exactly one new stored_file row
     * and one new file on disk (this version's PDF, every other file unchanged); no write to any quote / order
     * / line / customer / address / payment row; and the post-commit email for {@code expected}: the 201
     * message, the sender calls (never a quote email, store notification or SMS), the email content and
     * attachment, both markers (the mirror starts non-null) and the WARN line.
     */
    private Conversion convertOk(Scope scope, long orderId, EmailOutcome expected) throws Exception {
        String label = "Path A on order " + orderId + " (" + expected + ")";
        ensureMirrorSentinel(orderId);
        long acceptedId = latestAcceptedQuoteVersionId(orderId);
        Map<String, Object> accepted = quoteVersionById(acceptedId);
        long businessId = businessIdOf(orderId);
        Map<String, Object> untouchedBefore = untouchedState(orderId);
        List<Map<String, Object>> invoicesBefore = invoiceRows(orderId);
        List<Map<String, Object>> storedFilesBefore = storedFileRowsOfOrder(orderId);
        Map<String, String> diskBefore = diskFilesOfBusiness(businessId);
        SenderCounts sendersBefore = senderCounts();
        String savedEmail = savedCustomerEmail(orderId);
        int expectedVersion = invoicesBefore.isEmpty() ? 1
                : ((Number) invoicesBefore.get(invoicesBefore.size() - 1).get("version_number")).intValue() + 1;

        ListAppender<ILoggingEvent> serviceLog = captureServiceLog();
        LocalDateTime requestStart;
        LocalDateTime requestEnd;
        MvcResult result;
        try {
            requestStart = LocalDateTime.now();
            result = perform(pathARequest(scope, orderId, idBody(acceptedId)));
            requestEnd = LocalDateTime.now();
        } finally {
            releaseServiceLog(serviceLog);
        }
        assertStatus(result, 201, label);
        String responseBody = bodyOf(result);
        JsonNode json = JSON.readTree(responseBody);
        Assertions.assertEquals(expected.message, json.path("message").asText(), label + ": the 201 message");

        // ---- exactly one appended version, from the latest accepted quote version ----
        List<Map<String, Object>> invoicesAfter = invoiceRows(orderId);
        Assertions.assertEquals(invoicesBefore.size() + 1, invoicesAfter.size(), label + ": one invoice row appended");
        Assertions.assertEquals(invoicesBefore, invoicesAfter.subList(0, invoicesBefore.size()),
                label + ": every earlier invoice version row is unchanged");
        Map<String, Object> invoice = invoicesAfter.get(invoicesAfter.size() - 1);
        int version = ((Number) invoice.get("version_number")).intValue();
        Assertions.assertEquals(expectedVersion, version, label + ": the next version number");
        Assertions.assertEquals(acceptedId, asLong(invoice.get("source_quote_version_id")),
                label + ": source = the latest accepted quote version");
        Assertions.assertEquals(accepted.get("accepted_at"), invoice.get("accepted_at"), label + ": inherited accepted_at");
        Assertions.assertEquals(CUSTOMER_NAME, invoice.get("accepted_customer_name"), label + ": inherited accepted name");
        Assertions.assertEquals(asLong(accepted.get("accepted_signature_file_id")),
                asLong(invoice.get("accepted_signature_file_id")), label + ": the SAME signature stored_file");
        LocalDate invoiceDate = ((Date) invoice.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(requestStart.toLocalDate()) || invoiceDate.isAfter(requestEnd.toLocalDate()),
                () -> label + ": invoice_date must be the conversion day but was " + invoiceDate);

        // ---- the only new stored_file row and file on disk: this version's PDF ----
        List<Map<String, Object>> storedFilesAfter = storedFileRowsOfOrder(orderId);
        Assertions.assertEquals(storedFilesBefore.size() + 1, storedFilesAfter.size(), label + ": one new stored_file row");
        Assertions.assertEquals(storedFilesBefore, storedFilesAfter.subList(0, storedFilesBefore.size()),
                label + ": existing stored_file rows are unchanged");
        Map<String, Object> pdfFile = storedFilesAfter.get(storedFilesAfter.size() - 1);
        Assertions.assertEquals(asLong(invoice.get("stored_file_id")), asLong(pdfFile.get("stored_file_id")),
                label + ": the new stored_file row is the new version's PDF");
        String orderNumber = orderNumber(orderId);
        Assertions.assertEquals("invoice-" + orderNumber + "-v" + version + ".pdf", pdfFile.get("file_name"),
                label + ": the stored file name");
        String storagePath = (String) pdfFile.get("storage_path");
        byte[] pdf = diskBytes(storagePath);
        Assertions.assertEquals(asLong(pdfFile.get("file_size")), pdf.length, label + ": file_size = the bytes on disk");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII), label + ": PDF magic");
        Map<String, String> expectedDisk = new TreeMap<>(diskBefore);
        Assertions.assertNull(expectedDisk.put(diskKey(businessId, storagePath), sha256Hex(pdf)),
                label + ": the PDF is a new file");
        Assertions.assertEquals(expectedDisk, diskFilesOfBusiness(businessId),
                label + ": the only new file on disk is this version's PDF; every other file is unchanged");

        // ---- no quote / order / line / customer / address / payment write ----
        Assertions.assertEquals(untouchedBefore, untouchedState(orderId),
                label + ": no quote, order (besides the mirror), line, customer, address or payment row is written");

        // ---- the post-commit email: senders and content ----
        int sent = expected == EmailOutcome.DELIVERED ? 1 : 0;
        int failed = expected == EmailOutcome.FAILED ? 1 : 0;
        Assertions.assertEquals(new SenderCounts(sendersBefore.invoiceSent() + sent, sendersBefore.invoiceFailed() + failed,
                        sendersBefore.quoteSent(), sendersBefore.quoteFailed(), sendersBefore.notificationsSent(),
                        sendersBefore.notificationsFailed(), sendersBefore.smsSent(), sendersBefore.smsFailed()),
                senderCounts(), label + ": at most one invoice sender call; never a quote email, a store notification or an SMS");
        if (expected != EmailOutcome.SKIPPED) {
            List<InvoiceEmailRequest> recorded = expected == EmailOutcome.DELIVERED
                    ? invoiceEmailSender.sentEmails() : invoiceEmailSender.failedEmails();
            Assertions.assertNotNull(savedEmail, label + ": fixture: a saved email to send to");
            assertSignedInvoiceEmail(recorded.get(recorded.size() - 1), orderId, version, savedEmail.trim(), pdf, label);
        }

        // ---- the markers: the appended version and the section 11.1 mirror (non-null before the call) ----
        Timestamp invoiceMarker = (Timestamp) invoice.get("last_emailed_at");
        Timestamp mirror = mirror(orderId);
        JsonNode responseInvoice = json.path("data").path("invoice");
        JsonNode responseMarker = responseInvoice.get("last_emailed_at");
        Assertions.assertNotNull(responseMarker, label + ": last_emailed_at is always present");
        if (expected == EmailOutcome.DELIVERED) {
            Assertions.assertNotNull(invoiceMarker, label + ": the delivery is stamped on the appended version");
            Assertions.assertEquals(invoiceMarker, mirror, label + ": the mirror carries the SAME stamp");
            LocalDateTime stamped = invoiceMarker.toLocalDateTime();
            Assertions.assertFalse(stamped.isBefore(requestStart.truncatedTo(ChronoUnit.MICROS)) || stamped.isAfter(requestEnd),
                    () -> label + ": the stamp is the delivery time (" + requestStart + " to " + requestEnd + ") but was "
                            + stamped);
            Assertions.assertEquals(JSON_TIMESTAMP.format(stamped), responseMarker.asText(),
                    label + ": the response shows the stamp (whole seconds)");
        } else {
            Assertions.assertNull(invoiceMarker, label + ": nothing delivered: the appended version stays unemailed");
            Assertions.assertNull(mirror, label + ": the mirror keeps the persist-time reset to null");
            Assertions.assertTrue(responseMarker.isNull(), label + ": the response's last_emailed_at is null");
        }

        // ---- the response is the appended version, with no internal reference ----
        Assertions.assertEquals(version, responseInvoice.path("version_number").asInt(), label + ": response version");
        Assertions.assertEquals(invoiceDate.toString(), responseInvoice.path("invoice_date").asText(),
                label + ": the response's invoice_date is the row's");
        Assertions.assertEquals(CUSTOMER_NAME, responseInvoice.path("accepted_customer_name").asText(), label);
        Assertions.assertTrue(responseInvoice.path("accepted_signature_present").asBoolean(), label);
        for (String internal : List.of("source_quote_version_id", "stored_file_id", "accepted_signature_file_id",
                "storage_path", "/uploads/", "terms_snapshot")) {
            Assertions.assertFalse(responseBody.contains(internal),
                    () -> label + ": '" + internal + "' leaked into the response: " + responseBody);
        }

        // ---- the WARN line (OrderInvoiceService logs nothing else at WARN or above here) ----
        String warning = assertWarning(serviceLog, expected, orderId, version, savedEmail, label);
        return new Conversion(result, version, invoice, pdf, requestStart, requestEnd, warning);
    }

    /**
     * The skipped outcome (no valid saved email): {@link #convertOk} with SKIPPED, the exact skip line, and the
     * committed version read back by D.3 (signed, unemailed).
     */
    private Conversion assertSkippedConversion(long orderId) throws Exception {
        Conversion conversion = convertOk(home, orderId, EmailOutcome.SKIPPED);
        Assertions.assertEquals("Signed invoice email skipped for order " + orderId + " invoice v1: no valid saved "
                + "customer email (non-fatal; markers stay null)", conversion.warning(), "the WARN skip line");
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "no invoice email");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no invoice email attempt");
        assertCurrentInvoiceUnemailed(orderId, 1);
        return conversion;
    }

    /** D.3 reads the committed version back: the expected version, signed (inherited), last_emailed_at null. */
    private void assertCurrentInvoiceUnemailed(long orderId, int expectedVersion) throws Exception {
        MvcResult current = perform(get(invoicesUrl(home, orderId) + "/current").session(home.session()));
        assertStatus(current, 200, "D.3 get current");
        JsonNode invoice = readJson(current).path("data").path("invoice");
        Assertions.assertEquals(expectedVersion, invoice.path("version_number").asInt(), "D.3: the appended version");
        Assertions.assertTrue(invoice.path("accepted_signature_present").asBoolean(), "D.3: signed (inherited)");
        Assertions.assertTrue(invoice.path("last_emailed_at").isNull(), "D.3: last_emailed_at is null");
        Assertions.assertNull(mirror(orderId), "the dashboard mirror is null");
    }

    /** One recorded signed-invoice email (16G contract 6.1: the D.8 / D.9 request shape). */
    private void assertSignedInvoiceEmail(InvoiceEmailRequest email, long orderId, int version, String expectedRecipient,
                                          byte[] expectedPdf, String label) {
        String orderNumber = orderNumber(orderId);
        String businessName = jdbcTemplate.queryForObject(
                "SELECT b.name FROM business b JOIN sales_order o ON o.business_id = b.business_id WHERE o.order_id = ?",
                String.class, orderId);
        Assertions.assertEquals(expectedRecipient, email.recipientEmail(), label + ": recipient = the trimmed saved email");
        Assertions.assertEquals("Your invoice from " + businessName, email.subject(), label + ": subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + orderNumber + " from " + businessName
                + " attached.\n\nThank you.", email.bodyText(), label + ": body");
        Assertions.assertArrayEquals(expectedPdf, email.pdfBytes(),
                label + ": the attachment is the appended version's stored PDF, byte for byte");
        Assertions.assertEquals("invoice-" + orderNumber + "-v" + version + ".pdf", email.pdfFileName(),
                label + ": attachment file name");
        Assertions.assertEquals(orderId, email.orderId(), label + ": order id");
        Assertions.assertEquals(version, email.invoiceVersionNumber(), label + ": the appended version");
    }

    /**
     * The service's WARN lines for one conversion: none when delivered and stamped; otherwise exactly one -
     * WARN level, the exact failed / skip text naming {@code order <id> invoice v<N>}, no throwable, never an
     * email address or the saved value. Returns the line (null when none).
     */
    private static String assertWarning(ListAppender<ILoggingEvent> serviceLog, EmailOutcome expected, long orderId,
                                        int version, String savedEmail, String label) {
        List<ILoggingEvent> warnings = warnings(serviceLog);
        List<String> texts = warnings.stream().map(ILoggingEvent::getFormattedMessage).toList();
        String expectedText = switch (expected) {
            case DELIVERED -> null;
            case FAILED -> "Signed invoice email failed for order " + orderId + " invoice v" + version
                    + " (non-fatal; the committed invoice stands, markers stay null)";
            case SKIPPED -> "Signed invoice email skipped for order " + orderId + " invoice v" + version
                    + ": no valid saved customer email (non-fatal; markers stay null)";
        };
        if (expectedText == null) {
            Assertions.assertEquals(List.of(), texts, label + ": a delivered and stamped email logs no WARN");
            return null;
        }
        Assertions.assertEquals(List.of(expectedText), texts, label + ": exactly one WARN line");
        ILoggingEvent warning = warnings.get(0);
        Assertions.assertEquals(Level.WARN, warning.getLevel(), label + ": logged at WARN");
        String text = warning.getFormattedMessage();
        Assertions.assertTrue(text.contains("order " + orderId + " invoice v" + version), () -> label + ": " + text);
        Assertions.assertNull(warning.getThrowableProxy(), label + ": the WARN carries no throwable");
        Assertions.assertFalse(text.contains("@"), () -> label + ": the WARN never carries an email address: " + text);
        if (savedEmail != null && !savedEmail.isBlank()) {
            Assertions.assertFalse(text.contains(savedEmail.trim()),
                    () -> label + ": the WARN never carries the saved email: " + text);
        }
        return text;
    }

    private static JsonNode assertError(MvcResult result, int status, String code, String message, String label)
            throws IOException {
        assertStatus(result, status, label);
        JsonNode body = readJson(result);
        Assertions.assertFalse(body.has("data"), () -> label + ": an error carries no data: " + body);
        JsonNode error = body.get("error");
        Assertions.assertNotNull(error, () -> label + ": error envelope missing: " + body);
        Assertions.assertEquals(code, error.path("code").asText(), () -> label + ": " + body);
        Assertions.assertEquals(message, error.path("message").asText(), () -> label + ": " + body);
        return error;
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + bodyOf(result));
    }

    /** The exact detail list (fields, messages AND order); request-shape details carry no section key. */
    private static void assertDetails(JsonNode error, List<Detail> expected, String label) {
        Assertions.assertEquals(expected, details(error), () -> label + ": the exact detail list: " + error);
        JsonNode details = error.get("details");
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i).section() == null) {
                int index = i;
                Assertions.assertFalse(details.get(i).has("section"),
                        () -> label + ": detail " + index + " is a request-shape detail without a section: " + error);
            }
        }
    }

    private static Detail notAllowed(String field) {
        return new Detail(null, field, "Not allowed.");
    }

    private static List<Detail> details(JsonNode error) {
        List<Detail> list = new ArrayList<>();
        JsonNode details = error.get("details");
        if (details == null || details.isNull()) {
            return list;
        }
        for (JsonNode detail : details) {
            list.add(new Detail(textOrNull(detail, "section"), textOrNull(detail, "field"), textOrNull(detail, "message")));
        }
        return list;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    // ================================================================
    // Helpers: state snapshots
    // ================================================================

    /** The order's quote draft, draft lines, versions, version lines and tokens (whole rows, ordered). */
    private Map<String, Object> quoteState(long orderId) {
        Map<String, Object> state = new LinkedHashMap<>();
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
        return state;
    }

    /**
     * Everything an ALLOWED Path A call must never write: the sales_order row without last_emailed_at (the
     * mirror, its only allowed order write), the quote rows and the customer, address, line and payment rows.
     */
    private Map<String, Object> untouchedState(long orderId) {
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Object> order = new LinkedHashMap<>(
                jdbcTemplate.queryForMap("SELECT * FROM sales_order WHERE order_id = ?", orderId));
        Assertions.assertTrue(order.containsKey("last_emailed_at"), "sales_order.last_emailed_at must exist");
        order.remove("last_emailed_at");
        state.put("sales_order", order);
        state.putAll(quoteState(orderId));
        for (String table : List.of("order_customer", "order_address", "order_product_line", "order_charge_line",
                "payment_transaction")) {
            state.put(table, jdbcTemplate.queryForList("SELECT * FROM " + table + " WHERE order_id = ? ORDER BY 1", orderId));
        }
        return state;
    }

    /** {@link #untouchedState} with the whole sales_order row (mirror included), its invoices and stored files. */
    private Map<String, Object> orderState(long orderId) {
        Map<String, Object> state = new LinkedHashMap<>(untouchedState(orderId));
        state.put("sales_order", jdbcTemplate.queryForMap("SELECT * FROM sales_order WHERE order_id = ?", orderId));
        state.put("invoice", invoiceRows(orderId));
        state.put("stored_file", storedFileRowsOfOrder(orderId));
        return state;
    }

    /**
     * The refusal snapshot: each watched order's {@link #orderState}, every file on disk under the home business
     * and the watched orders' businesses, global row counts and every recording sender.
     */
    private Map<String, Object> refusalState(long... watchedOrders) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        Set<Long> businesses = new TreeSet<>();
        businesses.add(home.businessId());
        for (long orderId : watchedOrders) {
            state.put("order " + orderId, orderState(orderId));
            businesses.add(businessIdOf(orderId));
        }
        for (long businessId : businesses) {
            state.put("files of business " + businessId, diskFilesOfBusiness(businessId));
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("invoice", "stored_file", "quote_draft", "quote_version", "quote_token",
                "payment_transaction")) {
            counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        state.put("global row counts", counts);
        state.put("senders", senderCounts());
        return state;
    }

    private SenderCounts senderCounts() {
        return new SenderCounts(
                invoiceEmailSender.sentEmails().size(), invoiceEmailSender.failedEmails().size(),
                quoteEmailSender.sentEmails().size(), quoteEmailSender.failedEmails().size(),
                acceptanceNotificationSender.sentNotifications().size(),
                acceptanceNotificationSender.failedNotifications().size(),
                smsSender.sentMessages().size(), smsSender.failedMessages().size());
    }

    // ================================================================
    // Helpers: DB probes
    // ================================================================

    /** The order's LATEST ACCEPTED quote version id (highest version_number among ACCEPTED), from the DB. */
    private long latestAcceptedQuoteVersionId(long orderId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT quote_version_id FROM quote_version WHERE order_id = ? AND status = 'ACCEPTED' "
                        + "ORDER BY version_number DESC LIMIT 1", Long.class, orderId);
        Assertions.assertEquals(1, ids.size(), () -> "fixture: order " + orderId + " has an ACCEPTED quote version");
        return ids.get(0);
    }

    private Map<String, Object> quoteVersionById(long quoteVersionId) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_version WHERE quote_version_id = ?", quoteVersionId);
    }

    private Map<String, Object> quoteVersionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private List<String> quoteVersionStatuses(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM quote_version WHERE order_id = ? ORDER BY version_number", String.class, orderId);
    }

    private int quoteVersionCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private long maxQuoteVersionId() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(quote_version_id), 0) FROM quote_version", Long.class);
        return max == null ? 0 : max;
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private List<Map<String, Object>> invoiceRows(long orderId) {
        return jdbcTemplate.queryForList("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId);
    }

    private int invoiceCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private int currentInvoiceVersion(long orderId) {
        Integer current = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(current, () -> "fixture: order " + orderId + " has a current invoice");
        return current;
    }

    /** stored_file rows physically owned by this order (any business segment), in id order. */
    private List<Map<String, Object>> storedFileRowsOfOrder(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                "/uploads/%/orders/" + orderId + "/%");
    }

    private Timestamp mirror(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId);
    }

    /** The saved {@code order_customer.email}, exactly as stored (null when NULL). */
    private String savedCustomerEmail(long orderId) {
        return jdbcTemplate.queryForObject("SELECT email FROM order_customer WHERE order_id = ?", String.class, orderId);
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private long businessIdOf(long orderId) {
        Long businessId = jdbcTemplate.queryForObject(
                "SELECT business_id FROM sales_order WHERE order_id = ?", Long.class, orderId);
        Assertions.assertNotNull(businessId);
        return businessId;
    }

    // ================================================================
    // Helpers: disk, PDF, logs, values
    // ================================================================

    private Path storageRoot() {
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
    }

    /** FileStorageService writes every order file of a business under {base}/uploads/{businessId}/orders/{orderId}/. */
    private Path businessDir(long businessId) {
        return storageRoot().resolve("uploads").resolve(String.valueOf(businessId));
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

    /** The key {@link #diskFilesOfBusiness} lists a stored file under. */
    private String diskKey(long businessId, String storagePath) {
        return businessDir(businessId).relativize(diskPath(storagePath)).toString();
    }

    /** Every file under the business's directory: relative path to the SHA-256 of its bytes. */
    private Map<String, String> diskFilesOfBusiness(long businessId) throws IOException {
        Path dir = businessDir(businessId);
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(dir.relativize(file).toString(), sha256Hex(Files.readAllBytes(file)));
            }
        }
        return files;
    }

    /** Text in content-stream (document) order. */
    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Text in visual (position-sorted) order: the acceptance caption's inline spans come out as drawn. */
    private static String pdfTextByPosition(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    // PDFBox can inject or drop spaces between glyphs and wraps long text, so text checks ignore whitespace.
    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
    }

    /** Attach a list appender to the OrderInvoiceService logger (detached again by {@link #releaseServiceLog}). */
    private static ListAppender<ILoggingEvent> captureServiceLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger().addAppender(appender);
        return appender;
    }

    private static void releaseServiceLog(ListAppender<ILoggingEvent> appender) {
        serviceLogger().detachAppender(appender);
        appender.stop();
    }

    private static Logger serviceLogger() {
        return (Logger) LoggerFactory.getLogger(OrderInvoiceService.class);
    }

    private static List<ILoggingEvent> warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
    }

    private static List<String> warningTexts(ListAppender<ILoggingEvent> appender) {
        return warnings(appender).stream().map(ILoggingEvent::getFormattedMessage).toList();
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

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertNotNull(actual, () -> label + " must not be null");
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                () -> label + ": expected " + expected + " but was " + actual);
    }

    private static long asLong(Object value) {
        Assertions.assertNotNull(value, "expected a non-null id");
        return ((Number) value).longValue();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
