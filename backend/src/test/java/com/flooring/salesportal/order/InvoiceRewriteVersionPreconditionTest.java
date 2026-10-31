package com.flooring.salesportal.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.RecordingInvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Batch A PR 2 (#149): the mandatory version precondition of D.2 Rewrite
 * {@code POST /api/v1/{slug}/orders/{orderId}/invoices/rewrite} with the JSON body
 * {@code {"expected_version_number": N}} (docs/API-Contracts-Phase16G-Invoice-Link.md section 5 as amended by
 * Batch A PR 2 - the Rewrite carrier, its JSON rules and the D.2 order of checks - and
 * docs/API-Contracts-Chunk-4.md D.2).
 *
 * <p><b>The rules under test.</b>
 * <ul>
 *   <li>Body: an absent, empty or blank body, {@code {}} and a missing or null field are 400
 *       {@code VALIDATION_FAILED} {@code expected_version_number} {@code Required.}; the key given more than
 *       once at the top level of the body object (an escaped spelling of it included) is {@code Must appear at
 *       most once.}, whatever the values; a value that is not a positive integer is {@code Must be a positive
 *       integer.}; every other field is its own {@code Not allowed.} detail, in body order, in the same 400,
 *       with the {@code expected_version_number} detail LAST; a non-object body (a JSON {@code null} included)
 *       is {@code body} {@code Must be an object.}; unparseable JSON is 400 {@code MALFORMED_JSON}. The empty
 *       body the endpoint took before Batch A PR 2 is now the {@code Required.} 400.</li>
 *   <li>Stale: N is only COMPARED with the current version read under the order lock, so an older, a future
 *       and a forged value are all 409 {@code INVOICE_VERSION_STALE} with the exact message and no details;
 *       the new version is always the server's current + 1.</li>
 *   <li>Order of checks: guard, {@code orderId}, the scoped order (404, no existence leak), LAID (422
 *       {@code ORDER_LOCKED}), an existing invoice (422 {@code INVOICE_REQUIRED}), the body (400), the stale
 *       comparison (409), the Phase 13 email gate (422), the preconditions (422) and the overpaid backstop.
 *       There is no already-accepted refusal: a matching Rewrite of a signed current version appends an
 *       unsigned version.</li>
 *   <li>D.1 Create is unchanged: it still takes only the empty body, so {@code expected_version_number} is
 *       just a field it does not allow.</li>
 * </ul>
 *
 * <p><b>Nothing written on a refusal.</b> Every refused call goes through {@link #assertRefused}: each watched
 * order's {@code sales_order.last_emailed_at} mirror first carries a non-null sentinel (so a reset would
 * show), then the whole state is compared before and after - each watched order's sales_order row, invoice
 * rows (signatures included), stored_file rows, invoice link rows, quote draft / version / line / token rows
 * and customer, address, line and payment rows, every file on disk under the tenants' business directories
 * (path + SHA-256), global row counts and every recording sender (invoice email, invoice link email, invoice
 * acceptance notification, quote email). This class is {@code @Transactional}, so a refused request only
 * marks the test transaction rollback-only: any row it wrote before refusing would stay visible here, which
 * is what makes the comparison meaningful.
 *
 * <p><b>Allowed Rewrites</b> go through {@link #assertRewrites}, which also proves the comparison above WOULD
 * see a Rewrite's writes: exactly one appended, unsigned version (every earlier row unchanged), one new
 * stored_file row and one new file (the PDF), the mirror reset to null, an {@code ACTIVE} link turned
 * {@code REWRITTEN}, and nothing sent.
 *
 * <p><b>Fixtures - fully self-seeded (Phase 14D rule).</b> {@link #setUp} seeds this class's own business
 * (slug {@value #SLUG}) with SOFT and HARD terms, a store, a salesperson and the store access; tests that need
 * other scopes seed a second store of the same business (the same salesperson is granted access) or another
 * business ({@value #OTHER_SLUG}). Orders, customers, addresses and charge lines are raw SQL; invoices,
 * payments, voids, in-app signatures, invoice links and quotes come from the real protected endpoints. Raw SQL
 * otherwise only plants state (the LAID status, a saved email, a removed BILLING row, cleared details of sale,
 * a price adjustment, the mirror sentinel), each write followed by a persistence-context clear. No V4 demo
 * business, store, user or order is used, and every expected version is derived from the database.
 * Everything rolls back with the test transaction and the services' rollback hooks delete the files they
 * wrote under the storage root this class shares with the other invoice and quote suites (inside this test's
 * own business directories). The recording senders are singletons, reset before AND after every test.
 */
@ExtendWith(SpringExtension.class)
// The SAME storage property as the other invoice/quote suites, so this class joins their cached Spring
// context. Files stay apart: every file lives under this test's own business and order directories.
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class InvoiceRewriteVersionPreconditionTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded scopes (never a V4 identity) ----
    private static final String SLUG = "invoice-rewrite-version-test";
    private static final String BUSINESS_NAME = "Rowan Rewrite Flooring";
    private static final String STORE_CODE = "RWV-ONE";
    private static final String SALESPERSON_CODE = "RV1";
    /** A second store of the SAME business (the home salesperson is granted access to it). */
    private static final String SECOND_STORE_CODE = "RWV-TWO";
    /** Another business, with its own slug, store and salesperson. */
    private static final String OTHER_SLUG = "invoice-rewrite-version-other";
    private static final String OTHER_BUSINESS_NAME = "Thornbury Floors";
    private static final String OTHER_STORE_CODE = "TBF-ONE";
    private static final String OTHER_SALESPERSON_CODE = "TB1";

    // ---- The saved customer of every order ----
    private static final String CUSTOMER_FIRST_NAME = "Riley";
    private static final String CUSTOMER_LAST_NAME = "Morgan";
    private static final String CUSTOMER_EMAIL = "riley.morgan@example.com";
    /** DB-valid (trimmed non-empty, contains '@') but app-invalid (no interior dot in the domain). */
    private static final String APP_INVALID_EMAIL = "riley@localhost";
    private static final String DETAILS_OF_SALE = "Supply and lay flooring (rewrite version precondition test)";
    private static final String SOFT_TERMS = "<p>Rowan soft terms RWS1.</p>";
    private static final String HARD_TERMS = "<p>Rowan hard terms RWH1.</p>";
    /** Every order's proposed lay date (CONFIRMED). */
    private static final LocalDate LAY_DATE = LocalDate.of(2026, 12, 1);
    /** Written into sales_order.last_emailed_at before a call so a mirror write can never go unnoticed. */
    private static final Timestamp MIRROR_SENTINEL = Timestamp.valueOf("2026-01-02 03:04:05");
    private static final long MISSING_ORDER_ID = 9_999_999_999L;
    /** A non-itemised quote draft (275.00 inc), well above the order's 40.00 cost. */
    private static final String QUOTE_DRAFT = "{\"itemised\": false, \"final_total_inc_gst\": 275.00, \"lines\": []}";

    // ---- The Batch A PR 2 D.2 carrier ----
    private static final String EXPECTED_VERSION_FIELD = "expected_version_number";

    // A real, decodable 1x1 PNG: D.8 embeds the uploaded bytes in the signed PDF.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // ---- Response codes and exact messages ----
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String VALIDATION_MESSAGE = "One or more fields are invalid.";
    private static final String MALFORMED_JSON = "MALFORMED_JSON";
    private static final String MALFORMED_MESSAGE = "Request body is malformed.";
    private static final String STALE_CODE = "INVOICE_VERSION_STALE";
    private static final String STALE_MESSAGE = "Invoice updated, please refresh";
    private static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    private static final String ORDER_NOT_FOUND_MESSAGE = "Order not found.";
    private static final String ORDER_LOCKED = "ORDER_LOCKED";
    private static final String ORDER_LOCKED_MESSAGE = "Order is laid and cannot be edited.";
    private static final String INVOICE_REQUIRED = "INVOICE_REQUIRED";
    private static final String INVOICE_REQUIRED_MESSAGE = "An invoice is required for this action.";
    private static final String INVOICE_ALREADY_EXISTS = "INVOICE_ALREADY_EXISTS";
    private static final String INVOICE_ALREADY_EXISTS_MESSAGE =
            "An invoice already exists for this order. Use Rewrite Invoice to create a new version.";
    private static final String EMAIL_INVALID = "CUSTOMER_EMAIL_INVALID";
    private static final String EMAIL_INVALID_MESSAGE = "Customer email is not a valid email address.";
    private static final String PRECONDITIONS = "INVOICE_PRECONDITIONS_NOT_MET";
    private static final String PRECONDITIONS_MESSAGE = "Complete required fields before creating invoice.";
    private static final String UNAUTHORIZED = "UNAUTHORIZED";
    private static final String UNAUTHORIZED_MESSAGE = "Authentication required.";
    private static final String FORBIDDEN = "FORBIDDEN";
    private static final String FORBIDDEN_MESSAGE = "Access denied.";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String BUSINESS_NOT_FOUND_MESSAGE = "Business not found.";
    private static final String CREATED_MESSAGE = "Invoice created.";
    private static final String REWRITTEN_MESSAGE = "Invoice rewritten.";

    // ---- Error details ----
    private static final Detail VERSION_REQUIRED = new Detail(null, EXPECTED_VERSION_FIELD, "Required.");
    private static final Detail VERSION_NOT_POSITIVE = new Detail(null, EXPECTED_VERSION_FIELD, "Must be a positive integer.");
    private static final Detail VERSION_REPEATED = new Detail(null, EXPECTED_VERSION_FIELD, "Must appear at most once.");
    private static final Detail BODY_NOT_AN_OBJECT = new Detail(null, "body", "Must be an object.");
    private static final Detail ORDER_ID_NOT_POSITIVE = new Detail(null, "order_id", "Must be a positive integer.");
    private static final Detail BILLING_REQUIRED = new Detail("address", "billing_address", "Billing address is required.");
    private static final Detail DETAILS_REQUIRED = new Detail("details", "details_of_sale", "Details of sale is required.");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingInvoiceLinkEmailSender invoiceLinkEmailSender;

    @Autowired
    private RecordingInvoiceAcceptanceNotificationSender invoiceNotificationSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

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
                "this class reads its files under the shared invoice/quote storage dir");
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
        invoiceLinkEmailSender.reset();
        invoiceNotificationSender.reset();
        quoteEmailSender.reset();
    }

    // ================================================================
    // 1. The body: {"expected_version_number": N}
    // ================================================================

    /**
     * Every way of not sending the version is one 400 {@code VALIDATION_FAILED} with exactly one detail,
     * {@code expected_version_number} {@code Required.}: no body at all, an empty and a blank body (the empty
     * body Rewrite took before Batch A PR 2 included), {@code {}} and a {@code null} field (a JSON {@code null}
     * body is a non-object body: see the non-object test). Nothing is written by any of them, and the same
     * order then rewrites with its current version, so each refusal was the body alone.
     */
    @Test
    void rewrite_absentEmptyBlankEmptyObjectOrNullExpectedVersion_is400Required_nothingWritten_thenTheCurrentVersionRewrites()
            throws Exception {
        long orderId = invoicedOrder(home);

        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("absent body", null);
        bodies.put("empty body", "");
        bodies.put("blank body", "  \n\t ");
        bodies.put("empty object", "{}");
        bodies.put("null expected_version_number", "{\"expected_version_number\": null}");
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            JsonNode error = refuse(orderId, body.getValue(), 400, VALIDATION_FAILED, VALIDATION_MESSAGE, body.getKey());
            assertDetails(error, List.of(VERSION_REQUIRED), body.getKey());
        }
        Assertions.assertEquals(1, currentVersion(orderId), "no refused body appended a version");

        assertRewrites(home, orderId, "the current version after the refusals");
    }

    /**
     * An {@code expected_version_number} that is present but not a positive integer is one 400 with exactly
     * {@code expected_version_number} {@code Must be a positive integer.}: the current version as a JSON
     * string, a fraction, an integral-valued decimal ({@code 1.0}, also in exponent form), a boolean, zero, a
     * negative, {@code 2^63} ({@code Long.MAX_VALUE + 1}), {@code 10^20}, an object and an array holding the
     * current version. Nothing is written; the bare current version then rewrites.
     */
    @Test
    void rewrite_expectedVersionNotAPositiveInteger_is400MustBeAPositiveInteger_nothingWritten() throws Exception {
        long orderId = invoicedOrder(home);

        Map<String, String> values = new LinkedHashMap<>();   // label -> raw JSON value
        values.put("the current version as the JSON string \"1\"", "\"1\"");
        values.put("the fraction 1.5", "1.5");
        values.put("the integral-valued decimal 1.0", "1.0");
        values.put("the integral-valued decimal 1E0", "1E0");
        values.put("the boolean true", "true");
        values.put("zero", "0");
        values.put("the negative -1", "-1");
        values.put("2^63 (Long.MAX_VALUE + 1)", "9223372036854775808");
        values.put("10^20", "100000000000000000000");
        values.put("an object", "{}");
        values.put("an array holding the current version", "[1]");
        for (Map.Entry<String, String> value : values.entrySet()) {
            JsonNode error = refuse(orderId, "{\"expected_version_number\": " + value.getValue() + "}", 400,
                    VALIDATION_FAILED, VALIDATION_MESSAGE, value.getKey());
            assertDetails(error, List.of(VERSION_NOT_POSITIVE), value.getKey());
        }
        Assertions.assertEquals(1, currentVersion(orderId), "no refused body appended a version");

        assertRewrites(home, orderId, "the bare current version after the refusals");
    }

    /**
     * Only {@code expected_version_number} is allowed. Each other field is its own {@code Not allowed.}
     * detail, in body order, in ONE 400 together with any {@code expected_version_number} detail, which always
     * comes LAST: an extra field alone ({@code due_date Not allowed.}, then {@code Required.}); several extras
     * beside the matching version (one detail per extra, a null-valued extra included, nothing for the
     * version); several extras and no version; extras beside an invalid or null version (the extras first,
     * the version detail last even when the version is written first); the D.8 and Path A carriers and a
     * differently cased or camel-cased name (each just another field); and an extra beside a mismatching
     * version (still the 400: the body is validated before the stale comparison). Nothing is written; the
     * bare current version then rewrites.
     */
    @Test
    void rewrite_extraFields_eachNotAllowedInBodyOrder_versionDetailLast_inOne400_nothingWritten() throws Exception {
        long orderId = invoicedOrder(home);

        Map<String, String> bodies = new LinkedHashMap<>();   // label -> raw JSON body
        Map<String, List<Detail>> expected = new LinkedHashMap<>();
        bodies.put("an extra field only", "{\"due_date\": \"2026-12-01\"}");
        expected.put("an extra field only", List.of(notAllowed("due_date"), VERSION_REQUIRED));
        bodies.put("extras beside the matching version",
                "{\"zeta\": 1, \"expected_version_number\": 1, \"alpha\": \"x\", \"middle\": null}");
        expected.put("extras beside the matching version",
                List.of(notAllowed("zeta"), notAllowed("alpha"), notAllowed("middle")));
        bodies.put("two extras and no version", "{\"terms_html\": \"x\", \"due_date\": \"2026-12-01\"}");
        expected.put("two extras and no version", List.of(notAllowed("terms_html"), notAllowed("due_date"),
                VERSION_REQUIRED));
        bodies.put("a zero version written first, then an extra", "{\"expected_version_number\": 0, \"price\": 1}");
        expected.put("a zero version written first, then an extra", List.of(notAllowed("price"), VERSION_NOT_POSITIVE));
        bodies.put("a string version, then two extras",
                "{\"expected_version_number\": \"1\", \"note\": \"x\", \"price\": 2}");
        expected.put("a string version, then two extras",
                List.of(notAllowed("note"), notAllowed("price"), VERSION_NOT_POSITIVE));
        bodies.put("an extra, then a null version", "{\"price\": 1, \"expected_version_number\": null}");
        expected.put("an extra, then a null version", List.of(notAllowed("price"), VERSION_REQUIRED));
        bodies.put("a differently cased name", "{\"Expected_Version_Number\": 1}");
        expected.put("a differently cased name", List.of(notAllowed("Expected_Version_Number"), VERSION_REQUIRED));
        bodies.put("a camel-cased name", "{\"expectedVersionNumber\": 1}");
        expected.put("a camel-cased name", List.of(notAllowed("expectedVersionNumber"), VERSION_REQUIRED));
        bodies.put("the Path A carrier", "{\"quote_version_id\": 1}");
        expected.put("the Path A carrier", List.of(notAllowed("quote_version_id"), VERSION_REQUIRED));
        bodies.put("the D.8 signature part name beside the matching version",
                "{\"signature\": \"x\", \"expected_version_number\": 1}");
        expected.put("the D.8 signature part name beside the matching version", List.of(notAllowed("signature")));
        bodies.put("an extra beside a mismatching version", "{\"expected_version_number\": 2, \"price\": 1}");
        expected.put("an extra beside a mismatching version", List.of(notAllowed("price")));
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            JsonNode error = refuse(orderId, body.getValue(), 400, VALIDATION_FAILED, VALIDATION_MESSAGE, body.getKey());
            assertDetails(error, expected.get(body.getKey()), body.getKey());
        }
        Assertions.assertEquals(1, currentVersion(orderId), "no refused body appended a version");

        assertRewrites(home, orderId, "the bare current version after the refusals");
    }

    /**
     * {@code expected_version_number} given more than once in the body object is one 400
     * {@code VALIDATION_FAILED} with exactly one detail, {@code expected_version_number} {@code Must appear at
     * most once.}, whatever the values are: the repeat is checked before the value. The order's current
     * version is v2 (v1 then a payment), so each single-value read would answer differently: v1 then v2
     * (last-wins would rewrite), v2 then v1 (last-wins would be the stale 409, first-wins would rewrite), v2
     * twice and three times (any single read would rewrite), a repeated invalid {@code "x"} (twice, before v2
     * and after it), a null beside v2 (either order), and v1 then v2 under the same key spelt with a JSON
     * unicode escape (one key once decoded). The bodies are hand-written strings, as a Map cannot hold a
     * duplicate key. Beside extra fields, each extra keeps its own {@code Not allowed.} detail, in body order,
     * FIRST and the repeated detail comes LAST. Boundary: an {@code expected_version_number} nested inside a
     * value (an object or an array) or the key name as a string value is not a member of the body object and
     * never counts as a repeat. Nothing is written; the single key with v2 then rewrites.
     */
    @Test
    void rewrite_expectedVersionRepeated_is400MustAppearAtMostOnce_whateverTheValues_escapedKeyCounts_nestedKeyDoesNot()
            throws Exception {
        long orderId = invoicedOrder(home);
        recordCashPayment(home, orderId, "10.00");
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: the payment appended v2");

        String key = "\"expected_version_number\": ";   // written out once per member: the bodies repeat the key
        Map<String, String> repeated = new LinkedHashMap<>();   // label -> raw JSON body
        repeated.put("v1, then the current v2", "{" + key + "1, " + key + "2}");
        repeated.put("the current v2, then v1", "{" + key + "2, " + key + "1}");
        repeated.put("the current v2 twice", "{" + key + "2, " + key + "2}");
        repeated.put("the current v2 three times", "{" + key + "2, " + key + "2, " + key + "2}");
        repeated.put("the invalid \"x\" twice", "{" + key + "\"x\", " + key + "\"x\"}");
        repeated.put("the invalid \"x\", then the current v2", "{" + key + "\"x\", " + key + "2}");
        repeated.put("the current v2, then the invalid \"x\"", "{" + key + "2, " + key + "\"x\"}");
        repeated.put("null, then the current v2", "{" + key + "null, " + key + "2}");
        repeated.put("the current v2, then null", "{" + key + "2, " + key + "null}");
        repeated.put("v1, then v2 under the key spelt with a JSON unicode escape",
                "{" + key + "1, \"expected\\u005fversion_number\": 2}");
        for (Map.Entry<String, String> body : repeated.entrySet()) {
            JsonNode error = refuse(orderId, body.getValue(), 400, VALIDATION_FAILED, VALIDATION_MESSAGE, body.getKey());
            assertDetails(error, List.of(VERSION_REPEATED), body.getKey());
        }

        // Beside extra fields: one Not allowed. per extra, in body order, then the repeated detail LAST.
        assertDetails(refuse(orderId, "{" + key + "1, \"price\": 1, " + key + "2}", 400, VALIDATION_FAILED,
                        VALIDATION_MESSAGE, "a repeat split around an extra"),
                List.of(notAllowed("price"), VERSION_REPEATED), "a repeat split around an extra");
        assertDetails(refuse(orderId, "{\"zeta\": 1, " + key + "\"x\", \"alpha\": null, " + key + "\"x\"}", 400,
                        VALIDATION_FAILED, VALIDATION_MESSAGE, "extras around a repeated \"x\""),
                List.of(notAllowed("zeta"), notAllowed("alpha"), VERSION_REPEATED), "extras around a repeated \"x\"");

        // Boundary: only members of the body object count; a nested key or a string value is never a repeat.
        assertDetails(refuse(orderId, "{" + key + "{" + key + "2}}", 400, VALIDATION_FAILED, VALIDATION_MESSAGE,
                        "the key nested inside the version's own value"),
                List.of(VERSION_NOT_POSITIVE), "the key nested inside the version's own value");
        assertDetails(refuse(orderId, "{\"wrapper\": {" + key + "1}, " + key + "2}", 400, VALIDATION_FAILED,
                        VALIDATION_MESSAGE, "the key nested inside an extra's object value"),
                List.of(notAllowed("wrapper")), "the key nested inside an extra's object value");
        assertDetails(refuse(orderId, "{\"list\": [{" + key + "1}], " + key + "2}", 400, VALIDATION_FAILED,
                        VALIDATION_MESSAGE, "the key nested inside an extra's array value"),
                List.of(notAllowed("list")), "the key nested inside an extra's array value");
        assertDetails(refuse(orderId, "{" + key + "\"expected_version_number\"}", 400, VALIDATION_FAILED,
                        VALIDATION_MESSAGE, "the key name as the version's string value"),
                List.of(VERSION_NOT_POSITIVE), "the key name as the version's string value");
        Assertions.assertEquals(2, currentVersion(orderId), "no refused body appended a version");

        JsonNode invoice = assertRewrites(home, orderId, "the single key with the current v2");
        Assertions.assertEquals(3, invoice.path("version_number").asInt(), "v2 rewrites to v3");
    }

    /**
     * A body that is JSON but not an object - a JSON {@code null}, an array (empty, or holding the valid body),
     * a scalar number (also the bare current version), a JSON string, a boolean - is one 400 on {@code body}
     * {@code Must be an object.}; unparseable JSON ({@code "{"}, a truncated valid body, a bare word, a missing
     * value, a trailing comma) is 400 {@code MALFORMED_JSON} with no details. Nothing is written; the valid
     * body then rewrites.
     */
    @Test
    void rewrite_nonObjectBody_is400BodyMustBeAnObject_unparseableBody_is400MalformedJson_nothingWritten()
            throws Exception {
        long orderId = invoicedOrder(home);

        for (String body : new String[] {"null", "[]", "5", "\"x\"", "true", "[" + versionBody(1) + "]", "1"}) {
            JsonNode error = refuse(orderId, body, 400, VALIDATION_FAILED, VALIDATION_MESSAGE, "non-object body " + body);
            assertDetails(error, List.of(BODY_NOT_AN_OBJECT), "non-object body " + body);
        }
        for (String body : new String[] {"{", "{\"expected_version_number\": 1", "x", "{\"expected_version_number\": }",
                "{\"expected_version_number\": 1,}"}) {
            JsonNode error = refuse(orderId, body, 400, MALFORMED_JSON, MALFORMED_MESSAGE, "malformed body " + body);
            Assertions.assertFalse(error.has("details"), () -> "MALFORMED_JSON carries no details: " + error);
        }
        Assertions.assertEquals(1, currentVersion(orderId), "no refused body appended a version");

        assertRewrites(home, orderId, "the valid body after the refusals");
    }

    // ================================================================
    // 2. Stale: the value is only compared with the current version
    // ================================================================

    /**
     * The order's current version is v3 (D.1, a payment, its void). Every value that is not 3 - the older v1
     * and v2 (v1 is also another order's current version), the future v4 and v5, and the forged 999999 and
     * {@code Long.MAX_VALUE} - is 409 {@code INVOICE_VERSION_STALE} with the exact message and NO details,
     * nothing written: the value is compared, never used to pick a version. 3 then rewrites to v4 (the
     * server's current + 1), after which 3 is itself stale: only the current version rewrites.
     */
    @Test
    void rewrite_oldFutureOrForgedVersion_is409InvoiceVersionStale_exactMessage_noDetails_onlyTheCurrentRewrites()
            throws Exception {
        long orderId = invoicedOrder(home);
        long paymentId = recordCashPayment(home, orderId, "10.00");
        voidPayment(home, orderId, paymentId);
        Assertions.assertEquals(3, currentVersion(orderId), "fixture: D.1, the payment and its void make v3 current");
        long otherOrder = invoicedOrder(home);
        Assertions.assertEquals(1, currentVersion(otherOrder), "fixture: another order's current version is 1");

        for (long stale : new long[] {1, 2, 4, 5, 999_999, Long.MAX_VALUE}) {
            assertStale(orderId, versionBody(stale), "stale version " + stale);
        }
        Assertions.assertEquals(3, currentVersion(orderId), "no stale value appended a version");

        JsonNode invoice = assertRewrites(home, orderId, "the current version 3");
        Assertions.assertEquals(4, invoice.path("version_number").asInt(), "the new version is the server's current + 1");
        assertStale(orderId, versionBody(3), "3 once v4 is current");
        Assertions.assertEquals(1, currentVersion(otherOrder), "the other order is untouched");
    }

    /**
     * A stale Rewrite of an order with live state everywhere writes NOTHING: the order has a REPLACED and an
     * ACTIVE invoice link, an ISSUED quote with its ACTIVE quote link, and a payment that made v2 current. The
     * older v1, the future v3 and a forged value are each 409 with no invoice row, no stored_file row and no PDF
     * on disk; the ACTIVE link stays ACTIVE (no {@code dead_at}), the mirror keeps its sentinel and no invoice,
     * link, notification or quote email is sent (all inside {@link #assertRefused}, and the link and mirror
     * again explicitly). The matching v2 then proves those checks would have seen a Rewrite: one version, one
     * PDF, the mirror reset, the ACTIVE link REWRITTEN (the REPLACED one unchanged) - while the quote stays
     * ISSUED and nothing is sent.
     */
    @Test
    void rewrite_staleVersion_writesNoInvoiceStoredFileOrPdf_activeLinkStaysActive_mirrorAndEmailsUnchanged()
            throws Exception {
        long orderId = invoicedOrder(home);
        issueQuote(home, orderId);
        sendLink(home, orderId);
        sendLink(home, orderId);
        recordCashPayment(home, orderId, "10.00");
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: the payment made v2 current");
        Assertions.assertEquals(List.of("REPLACED", "ACTIVE"), invoiceTokenStatuses(orderId),
                "fixture: the second send replaced the first link and a payment never kills a link");
        Assertions.assertEquals(List.of("ISSUED"), quoteVersionStatuses(orderId), "fixture: the quote is issued");
        Assertions.assertEquals(List.of("ACTIVE"), quoteTokenStatuses(orderId), "fixture: its link is active");

        for (long stale : new long[] {1, 3, Long.MAX_VALUE}) {
            assertStale(orderId, versionBody(stale), "stale version " + stale);
        }
        Assertions.assertEquals(2, invoiceCount(orderId), "no invoice row is appended");
        Assertions.assertEquals(List.of("REPLACED", "ACTIVE"), invoiceTokenStatuses(orderId), "the ACTIVE link stays ACTIVE");
        Assertions.assertNull(invoiceTokenRows(orderId).get(1).get("dead_at"), "the ACTIVE link has no dead_at");
        Assertions.assertEquals(MIRROR_SENTINEL, mirror(orderId), "the mirror keeps its value");
        Assertions.assertEquals(0, invoiceEmailSender.sentEmails().size(), "no invoice email");
        Assertions.assertEquals(2, invoiceLinkEmailSender.sentEmails().size(), "only the two fixture link emails");

        assertRewrites(home, orderId, "the matching version 2");
        Assertions.assertEquals(List.of("REPLACED", "REWRITTEN"), invoiceTokenStatuses(orderId),
                "the matching Rewrite kills the ACTIVE link as REWRITTEN");
        Assertions.assertEquals(List.of("ISSUED"), quoteVersionStatuses(orderId), "a Rewrite never touches the quote");
        Assertions.assertEquals(List.of("ACTIVE"), quoteTokenStatuses(orderId), "nor its link");
    }

    /**
     * No already-accepted refusal: D.8 signs v1 (v2 is the signed current version). Rewrite carrying the
     * unsigned v1 is the stale 409 (nothing written: v2 stays signed, its signature file stays on disk), and
     * the matching v2 is 201 with an UNSIGNED v3 (no acceptance time, name or signature file, no email
     * marker) appended after the untouched signed v2: a Rewrite clears the signature by design.
     */
    @Test
    void rewrite_matchingVersionOfASignedCurrentVersion_201_appendsUnsignedVersion() throws Exception {
        long orderId = invoicedOrder(home);
        signInApp(home, orderId);
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: D.8 appended the signed v2");
        Map<String, Object> signed = invoiceRow(orderId, 2);
        Assertions.assertNotNull(signed.get("accepted_at"), "fixture: v2 is signed");
        Assertions.assertNotNull(signed.get("accepted_signature_file_id"), "fixture: v2 has a signature file");

        assertStale(orderId, versionBody(1), "the unsigned v1 the screen still shows");
        Assertions.assertEquals(signed, invoiceRow(orderId, 2), "the signed v2 is untouched by the 409");

        JsonNode invoice = assertRewrites(home, orderId, "the matching signed version 2");
        Assertions.assertEquals(3, invoice.path("version_number").asInt(), "the signed v2 rewrites to v3");
        Assertions.assertTrue(invoice.path("accepted_signature_download_path").isNull(), "no signature to download");
        Assertions.assertEquals(signed, invoiceRow(orderId, 2), "the signed v2 is kept as it was");
    }

    // ================================================================
    // 3. Order of checks: what comes before the value and what comes after it
    // ================================================================

    /**
     * Everything up to the scoped order comes before the body. The guard answers whatever the body is: no
     * session 401, a session with no selected store 403, another business's slug with the home session 404
     * {@code NOT_FOUND}; a non-positive or non-numeric {@code orderId} is one detail on {@code order_id}. An
     * order of ANOTHER store of the same business and an order of another business - each with its own v1, so
     * in scope the bad bodies would be 400 and v1 would rewrite - are 404 {@code ORDER_NOT_FOUND} (no details,
     * the order number never echoed) for the home session whatever the body: absent, blank, JSON null,
     * {@code {}}, malformed, non-object, an extra field, a string or zero version, a repeated version, a stale
     * or forged version and the order's own current version. The 404 body is byte-identical to a missing
     * order's (no existence leak), and a missing order with a malformed body is 404 too (never 400). Nothing is
     * written; in their own scopes both orders then rewrite: every 404 was the scope.
     */
    @Test
    void rewrite_outOfScopeOrdersAndForeignSlug_are404BeforeAnyBodyCheck_evenWithStaleOrMalformedBodies()
            throws Exception {
        Scope secondStore = seedSecondStoreOfHome();
        long secondStoreOrder = invoicedOrder(secondStore);
        Scope otherBusiness = seedOtherBusiness();
        long otherBusinessOrder = invoicedOrder(otherBusiness);
        long homeOrder = invoicedOrder(home);

        // The guard and the orderId: before any order is read.
        assertRefused(rewriteRequest(home, null, homeOrder, "{"), 401, UNAUTHORIZED, UNAUTHORIZED_MESSAGE,
                "no session", homeOrder);
        assertRefused(rewriteRequest(home, sessionWithoutStore(), homeOrder, versionBody(2)), 403, FORBIDDEN,
                FORBIDDEN_MESSAGE, "a session with no selected store", homeOrder);
        assertRefused(rewriteRequest(otherBusiness, home.session(), otherBusinessOrder, "{"), 404, NOT_FOUND,
                BUSINESS_NOT_FOUND_MESSAGE, "the other business's slug with the home session", otherBusinessOrder);
        for (String badOrderId : List.of("0", "-3", "abc")) {
            JsonNode error = assertRefused(rewriteRequest(home, home.session(), badOrderId, "{"), 400,
                    VALIDATION_FAILED, VALIDATION_MESSAGE, "orderId '" + badOrderId + "'", homeOrder);
            assertDetails(error, List.of(ORDER_ID_NOT_POSITIVE), "orderId '" + badOrderId + "'");
        }

        // The scoped order: out of scope or missing -> 404 whatever the body, with no existence leak.
        MvcResult missing = perform(rewriteRequest(home, home.session(), MISSING_ORDER_ID, versionBody(1)));
        assertError(missing, 404, ORDER_NOT_FOUND, ORDER_NOT_FOUND_MESSAGE, "a missing order");
        String missingBody = bodyOf(missing);
        Map<String, Long> orders = new LinkedHashMap<>();
        orders.put("another store's order", secondStoreOrder);
        orders.put("another business's order", otherBusinessOrder);
        String[] bodies = {null, "  ", "null", "{}", "{", "[]", "5", "{\"price\": 1}",
                "{\"expected_version_number\": \"1\"}", "{\"expected_version_number\": 0}",
                "{\"expected_version_number\": 1, \"expected_version_number\": 1}", versionBody(2),
                versionBody(Long.MAX_VALUE), versionBody(1)};
        for (Map.Entry<String, Long> order : orders.entrySet()) {
            long orderId = order.getValue();
            Assertions.assertEquals(1, currentVersion(orderId), order.getKey() + ": fixture v1");
            for (String body : bodies) {
                String label = order.getKey() + ", body " + body;
                JsonNode error = assertRefused(rewriteRequest(home, home.session(), orderId, body), 404,
                        ORDER_NOT_FOUND, ORDER_NOT_FOUND_MESSAGE, label, orderId);
                Assertions.assertFalse(error.has("details"), label + ": the 404 carries no details");
            }
            String crossScopeBody = bodyOf(perform(rewriteRequest(home, home.session(), orderId, versionBody(1))));
            Assertions.assertEquals(missingBody, crossScopeBody,
                    order.getKey() + ": the 404 is byte-identical to a missing order's (no existence leak)");
            Assertions.assertFalse(crossScopeBody.contains(orderNumber(orderId)),
                    order.getKey() + ": the order is never echoed");
        }
        assertRefused(rewriteRequest(home, home.session(), MISSING_ORDER_ID, "{"), 404, ORDER_NOT_FOUND,
                ORDER_NOT_FOUND_MESSAGE, "a missing order with a malformed body");

        assertRewrites(secondStore, secondStoreOrder, "the other store's order in its own scope");
        assertRewrites(otherBusiness, otherBusinessOrder, "the other business's order in its own scope");
        Assertions.assertEquals(1, currentVersion(homeOrder), "the home order is untouched");
    }

    /**
     * LAID comes before the invoice read and the body: an invoiced LAID order is 422 {@code ORDER_LOCKED} (no
     * details) for every body - absent, blank, JSON null, {@code {}}, malformed, non-object, an extra field, a
     * string or zero version, a repeated version, a stale or forged version and its matching current version -
     * and so is a LAID order with NO invoice (never {@code INVOICE_REQUIRED}). Nothing is written; once the
     * order is no longer LAID its current version rewrites.
     */
    @Test
    void rewrite_laidOrder_is422OrderLocked_whateverTheBody() throws Exception {
        long laid = invoicedOrder(home);
        setOrderStatus(laid, "LAID");
        long laidWithoutInvoice = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);
        setOrderStatus(laidWithoutInvoice, "LAID");

        String[] bodies = {null, "  ", "null", "{}", "{", "[]", "5", "{\"due_date\": \"2026-12-01\"}",
                "{\"expected_version_number\": \"1\"}", "{\"expected_version_number\": 0}",
                "{\"expected_version_number\": 1, \"expected_version_number\": 1}", versionBody(2),
                versionBody(Long.MAX_VALUE), versionBody(1)};
        Map<String, Long> orders = new LinkedHashMap<>();
        orders.put("a LAID order with v1", laid);
        orders.put("a LAID order with no invoice", laidWithoutInvoice);
        for (Map.Entry<String, Long> order : orders.entrySet()) {
            for (String body : bodies) {
                String label = order.getKey() + ", body " + body;
                JsonNode error = refuse(order.getValue(), body, 422, ORDER_LOCKED, ORDER_LOCKED_MESSAGE, label);
                Assertions.assertFalse(error.has("details"), label + ": ORDER_LOCKED carries no details");
            }
        }
        Assertions.assertEquals(1, currentVersion(laid), "no LAID refusal appended a version");
        Assertions.assertEquals(0, invoiceCount(laidWithoutInvoice), "no invoice for the order without one");

        setOrderStatus(laid, "ACCEPTED");
        assertRewrites(home, laid, "the same order once it is no longer LAID");
    }

    /**
     * The chosen order (Batch A PR 2): the current invoice is read BEFORE the body, so an in-scope order with no
     * invoice - and an app-invalid saved email, which the later email gate would refuse - is 422
     * {@code INVOICE_REQUIRED} (no details) for every body: absent, blank, JSON null, {@code {}}, malformed,
     * non-object, an extra field, a string or zero version, a repeated version, version 1 and a forged version.
     * Nothing is written. With a valid email and D.1's v1 the same order then rewrites with 1.
     */
    @Test
    void rewrite_noInvoice_bodyVersusInvoiceRequired_pinsTheChosenOrder() throws Exception {
        long orderId = seedInvoiceReadyOrder(home, APP_INVALID_EMAIL);

        String[] bodies = {null, "  ", "null", "{}", "{", "[]", "5", "{\"due_date\": \"2026-12-01\"}",
                "{\"expected_version_number\": \"1\"}", "{\"expected_version_number\": 0}",
                "{\"expected_version_number\": 1, \"expected_version_number\": 1}", versionBody(1),
                versionBody(Long.MAX_VALUE)};
        for (String body : bodies) {
            String label = "no invoice, body " + body;
            JsonNode error = refuse(orderId, body, 422, INVOICE_REQUIRED, INVOICE_REQUIRED_MESSAGE, label);
            Assertions.assertFalse(error.has("details"), label + ": INVOICE_REQUIRED carries no details");
        }
        Assertions.assertEquals(0, invoiceCount(orderId), "no refusal created an invoice");

        setSavedEmail(orderId, CUSTOMER_EMAIL);
        createInvoice(home, orderId);
        assertRewrites(home, orderId, "version 1 once D.1 created it");
    }

    /**
     * The stale comparison comes before the Phase 13 email gate and the preconditions. With an app-invalid
     * saved email, a stale value is 409 and only the matching version reaches 422 {@code CUSTOMER_EMAIL_INVALID};
     * with a valid email but no BILLING row and no details of sale, a stale value is 409 and only the matching
     * version reaches 422 {@code INVOICE_PRECONDITIONS_NOT_MET} with exactly the two failing details. Nothing is
     * written by any of them; with the state restored the matching version rewrites.
     */
    @Test
    void rewrite_staleBeatsEmailGateAndPreconditions_matchingVersionGetsThe422() throws Exception {
        long orderId = invoicedOrder(home);

        setSavedEmail(orderId, APP_INVALID_EMAIL);
        for (long stale : new long[] {2, Long.MAX_VALUE}) {
            assertStale(orderId, versionBody(stale), "app-invalid email, stale version " + stale);
        }
        JsonNode emailError = refuse(orderId, versionBody(1), 422, EMAIL_INVALID, EMAIL_INVALID_MESSAGE,
                "app-invalid email, the matching version");
        Assertions.assertFalse(emailError.has("details"), "the email gate carries no details");

        setSavedEmail(orderId, CUSTOMER_EMAIL);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "DELETE FROM order_address WHERE order_id = ? AND address_type = 'BILLING'::address_type", orderId),
                "fixture: remove the BILLING row");
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE sales_order SET details_of_sale = NULL WHERE order_id = ?", orderId),
                "fixture: clear the details of sale");
        clearJpaCache();
        for (long stale : new long[] {2, Long.MAX_VALUE}) {
            assertStale(orderId, versionBody(stale), "failing preconditions, stale version " + stale);
        }
        JsonNode preconditions = refuse(orderId, versionBody(1), 422, PRECONDITIONS, PRECONDITIONS_MESSAGE,
                "failing preconditions, the matching version");
        assertDetails(preconditions, List.of(BILLING_REQUIRED, DETAILS_REQUIRED), "failing preconditions, the matching version");
        Assertions.assertEquals(1, currentVersion(orderId), "no refusal appended a version");

        insertBillingAddress(orderId);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE sales_order SET details_of_sale = ? WHERE order_id = ?", DETAILS_OF_SALE, orderId),
                "fixture: restore the details of sale");
        clearJpaCache();
        assertRewrites(home, orderId, "the matching version with the state restored");
    }

    /**
     * The stale comparison also comes before the overpaid backstop: 110.00 is paid against v1's 110.00 (v2 is
     * current, balance 0.00), then a -60.00 price adjustment drops the live price to 50.00, below what is
     * paid. An older, a future and a forged value are each 409 {@code INVOICE_VERSION_STALE} with nothing
     * written. The matching version would reach the overpaid backstop, a known 500 tracked as #135 (Batch B);
     * it is deliberately not sent here.
     */
    @Test
    void rewrite_staleVersionOnAnOverpaidOrder_is409_beforeTheBalanceBackstop() throws Exception {
        long orderId = invoicedOrder(home);
        recordCashPayment(home, orderId, "110.00");
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: the full payment appended v2");
        assertMoney("0.00", invoiceRow(orderId, 2).get("balance_due"), "fixture: v2 is fully paid");
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE sales_order SET price_adjustment_inc_gst = -60.00 WHERE order_id = ?", orderId),
                "fixture: the live price drops to 50.00, below the 110.00 paid");
        clearJpaCache();

        for (long stale : new long[] {1, 3, Long.MAX_VALUE}) {
            assertStale(orderId, versionBody(stale), "overpaid, stale version " + stale);
        }
        Assertions.assertEquals(2, currentVersion(orderId), "no stale value appended a version");
    }

    // ================================================================
    // 4. D.1 Create is unchanged
    // ================================================================

    /**
     * D.1 still takes only the empty body: {@code expected_version_number} is just a field it does not allow
     * (400, {@code Not allowed.}, one detail per field in body order), nothing written. {@code {}} creates v1
     * and an absent body creates another order's v1. On an order WITH an invoice the body is still checked
     * first (400), and {@code {}} is the 409 {@code INVOICE_ALREADY_EXISTS}.
     */
    @Test
    void create_d1StillTakesTheEmptyBody_expectedVersionNumberIsNotAllowed() throws Exception {
        long orderId = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);

        assertDetails(assertRefused(createRequest(home, orderId, versionBody(1)), 400, VALIDATION_FAILED,
                        VALIDATION_MESSAGE, "D.1 with expected_version_number", orderId),
                List.of(notAllowed(EXPECTED_VERSION_FIELD)), "D.1 with expected_version_number");
        assertDetails(assertRefused(createRequest(home, orderId,
                                "{\"expected_version_number\": 1, \"due_date\": \"2026-12-01\"}"), 400,
                        VALIDATION_FAILED, VALIDATION_MESSAGE, "D.1 with two fields", orderId),
                List.of(notAllowed(EXPECTED_VERSION_FIELD), notAllowed("due_date")), "D.1 with two fields");
        Assertions.assertEquals(0, invoiceCount(orderId), "no refused D.1 created an invoice");

        createInvoice(home, orderId);
        Assertions.assertEquals(1, currentVersion(orderId), "{} created v1");
        long absentBodyOrder = seedInvoiceReadyOrder(home, CUSTOMER_EMAIL);
        MvcResult absent = perform(createRequest(home, absentBodyOrder, null));
        assertStatus(absent, 201, "D.1 with an absent body");
        Assertions.assertEquals(CREATED_MESSAGE, readJson(absent).path("message").asText(), "D.1 message");
        Assertions.assertEquals(1, currentVersion(absentBodyOrder), "an absent body created v1");

        assertDetails(assertRefused(createRequest(home, orderId, versionBody(1)), 400, VALIDATION_FAILED,
                        VALIDATION_MESSAGE, "D.1 with expected_version_number on an invoiced order", orderId),
                List.of(notAllowed(EXPECTED_VERSION_FIELD)), "D.1 with expected_version_number on an invoiced order");
        assertRefused(createRequest(home, orderId, "{}"), 409, INVOICE_ALREADY_EXISTS, INVOICE_ALREADY_EXISTS_MESSAGE,
                "D.1 on an invoiced order", orderId);
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
    private record SenderCounts(int invoiceSent, int invoiceFailed, int linkSent, int linkFailed,
                                int notificationsSent, int notificationsFailed, int quoteSent, int quoteFailed) {
    }

    /**
     * A business (active, ABN, SOFT and HARD terms), its store, one salesperson and that salesperson's store
     * access, all with RETURNING ids. The slug matches chk_business_slug_format and is not reserved.
     */
    private Scope seedTenant(String slug, String businessName, String storeCode, String salespersonCode, String tag) {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, '51 824 753 556', ?, ?) RETURNING business_id",
                Long.class, businessName, slug, SOFT_TERMS, HARD_TERMS);
        Assertions.assertNotNull(businessId, "business insert must return an id");
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, ?, ?, '0290004321', ?, '3 Showroom Road', 'Burwood', 'NSW', '2134') "
                        + "RETURNING store_id",
                Integer.class, businessId, businessName + " Showroom", storeCode, "store." + tag + ".rewrite@example.com");
        Assertions.assertNotNull(storeId, "store insert must return an id");
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Rowan', 'Vale', ?, ?, 'not-a-login-hash') RETURNING user_id",
                Long.class, businessId, salespersonCode, "rowan.vale." + tag + ".rewrite@example.com");
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
                        + "VALUES (?, 'Rowan Rewrite Warehouse', ?, '0290004322', 'store.second.rewrite@example.com', "
                        + " '4 Showroom Road', 'Burwood', 'NSW', '2134') RETURNING store_id",
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

    /** The home user and business with NO selected store. */
    private MockHttpSession sessionWithoutStore() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", home.userId());
        session.setAttribute("business_id", home.businessId());
        return session;
    }

    /**
     * A SOFT order of {@code scope} with no invoice that passes every D.1 / D.2 gate when {@code customerEmail}
     * is app-valid: the customer Riley Morgan, INSTALLATION and BILLING addresses, details of sale, a CONFIRMED
     * lay date 2026-12-01 and one priced charge line of 100.00 ex / 40.00 cost (110.00 inc live).
     */
    private long seedInvoiceReadyOrder(Scope scope, String customerEmail) {
        int sequence = ++orderSeq;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 42, 2026, ?, ?, "
                        + " 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, scope.businessId(), scope.storeId(), scope.userId(), sequence,
                scope.storeCode() + "." + scope.salespersonCode() + "." + String.format("%05d", sequence),
                DETAILS_OF_SALE, Date.valueOf(LAY_DATE));
        Assertions.assertNotNull(orderId, "order insert must return an id");
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) VALUES (?, ?, ?, ?, ?)",
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, customerEmail, "0412345678");
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '7', 'Install Lane', 'Ryde', 'NSW', '2112')",
                orderId);
        insertBillingAddress(orderId);
        String chargeCode = "RWVC" + (++chargeSeq);
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Rowan rewrite test charge', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, scope.storeId(), chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Rowan rewrite test charge', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        clearJpaCache();
        return orderId;
    }

    private void insertBillingAddress(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Oxford Street', 'Paddington', 'NSW', '2021')",
                orderId);
        clearJpaCache();
    }

    /** An invoice-ready order of {@code scope} with its invoice v1 created through D.1 (unsigned). */
    private long invoicedOrder(Scope scope) throws Exception {
        long orderId = seedInvoiceReadyOrder(scope, CUSTOMER_EMAIL);
        createInvoice(scope, orderId);
        Assertions.assertEquals(1, currentVersion(orderId), "fixture: D.1 created v1");
        return orderId;
    }

    private void setSavedEmail(long orderId, String email) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_customer SET email = ? WHERE order_id = ?", email, orderId), "fixture: set the saved email");
        clearJpaCache();
    }

    private void setOrderStatus(long orderId, String status) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE sales_order SET order_status = ?::order_status WHERE order_id = ?", status, orderId),
                "fixture: set the order status");
        clearJpaCache();
    }

    /** Give the mirror a non-null value when it has none, so any write to it is observable. */
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

    /** D.2 under {@code urlScope}'s slug with {@code session} (none when null); a null body sends none. */
    private static MockHttpServletRequestBuilder rewriteRequest(Scope urlScope, MockHttpSession session,
                                                                Object orderId, String body) {
        MockHttpServletRequestBuilder request = post(orderUrl(urlScope, orderId) + "/invoices/rewrite");
        if (session != null) {
            request.session(session);
        }
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** D.1 as {@code scope}'s session; a null body sends none. */
    private static MockHttpServletRequestBuilder createRequest(Scope scope, Object orderId, String body) {
        MockHttpServletRequestBuilder request = post(orderUrl(scope, orderId) + "/invoices").session(scope.session());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** The Batch A PR 2 D.2 body naming one version. */
    private static String versionBody(long versionNumber) {
        return "{\"expected_version_number\": " + versionNumber + "}";
    }

    /** Perform with the JPA cache cleared on both sides (raw SQL edits happen between calls). */
    private MvcResult perform(RequestBuilder request) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        return result;
    }

    /** D.1 (empty body): 201 "Invoice created.". */
    private void createInvoice(Scope scope, long orderId) throws Exception {
        MvcResult result = perform(createRequest(scope, orderId, "{}"));
        assertStatus(result, 201, "fixture: D.1 create");
        Assertions.assertEquals(CREATED_MESSAGE, readJson(result).path("message").asText(), "fixture: D.1 message");
    }

    /** D.7 CASH payment (appends a version, never emails): 201; returns the payment id. */
    private long recordCashPayment(Scope scope, long orderId, String amount) throws Exception {
        MvcResult result = perform(post(orderUrl(scope, orderId) + "/payments").session(scope.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"));
        assertStatus(result, 201, "fixture: D.7 payment");
        long paymentId = readJson(result).path("data").path("payment_transaction").path("payment_transaction_id").asLong();
        Assertions.assertTrue(paymentId > 0, "fixture: D.7 returns the payment id");
        return paymentId;
    }

    /** D.10 void (appends a version, never emails): 201. */
    private void voidPayment(Scope scope, long orderId, long paymentId) throws Exception {
        assertStatus(perform(post(orderUrl(scope, orderId) + "/payments/" + paymentId + "/void").session(scope.session())),
                201, "fixture: D.10 void");
    }

    /** D.8 with the order's current version (read now) and one PNG signature part: 201. */
    private void signInApp(Scope scope, long orderId) throws Exception {
        MvcResult result = perform(multipart(orderUrl(scope, orderId) + "/invoices/current/accept")
                .file(new MockMultipartFile("signature", "signature.png", "image/png", ONE_PIXEL_PNG))
                .param(EXPECTED_VERSION_FIELD, String.valueOf(currentVersion(orderId)))
                .session(scope.session()));
        assertStatus(result, 201, "fixture: D.8 in-app signature");
    }

    /** The protected send-link (16G PR 4): 201 and one more recorded link email. */
    private void sendLink(Scope scope, long orderId) throws Exception {
        int before = invoiceLinkEmailSender.sentEmails().size();
        assertStatus(perform(post(orderUrl(scope, orderId) + "/invoices/current/send-link").session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 201, "fixture: send-link");
        Assertions.assertEquals(before + 1, invoiceLinkEmailSender.sentEmails().size(), "fixture: one link email");
    }

    /** Save a non-itemised quote draft and issue it by email: one ISSUED version with an ACTIVE quote link. */
    private void issueQuote(Scope scope, long orderId) throws Exception {
        assertStatus(perform(put(orderUrl(scope, orderId) + "/quote/draft").session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content(QUOTE_DRAFT)), 200, "fixture: quote draft");
        int before = quoteEmailSender.sentEmails().size();
        assertStatus(perform(post(orderUrl(scope, orderId) + "/quote/send-email").session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 201, "fixture: quote send-email");
        Assertions.assertEquals(before + 1, quoteEmailSender.sentEmails().size(), "fixture: one quote email");
    }

    // ================================================================
    // Helpers: refusals and allowed Rewrites
    // ================================================================

    /** A refused D.2 as the home session, watching the one order the request names. */
    private JsonNode refuse(long orderId, String body, int status, String code, String message, String label)
            throws Exception {
        return assertRefused(rewriteRequest(home, home.session(), orderId, body), status, code, message, label, orderId);
    }

    /** 409 {@code INVOICE_VERSION_STALE} with the exact message and no details, nothing written. */
    private void assertStale(long orderId, String body, String label) throws Exception {
        JsonNode error = refuse(orderId, body, 409, STALE_CODE, STALE_MESSAGE, label);
        Assertions.assertFalse(error.has("details"), () -> label + ": the stale 409 carries no details: " + error);
    }

    /**
     * A refused call: every watched order's mirror first carries a non-null value (so a reset would show), the
     * call returns the expected error envelope, and NOTHING changes - {@link #refusalState} is equal before and
     * after. Returns the error object.
     */
    private JsonNode assertRefused(RequestBuilder request, int status, String code, String message, String label,
                                   long... watchedOrders) throws Exception {
        for (long watched : watchedOrders) {
            ensureMirrorSentinel(watched);
        }
        Map<String, Object> before = refusalState(watchedOrders);
        MvcResult result = perform(request);
        JsonNode error = assertError(result, status, code, message, label);
        Assertions.assertEquals(before, refusalState(watchedOrders), () -> label + ": a refused call writes nothing");
        return error;
    }

    /**
     * A matching D.2 as {@code scope}'s session, carrying the order's CURRENT version (read from the database
     * now), with every invariant of an allowed Rewrite: 201 "Invoice rewritten." with the server's current + 1,
     * unsigned and unemailed; exactly one appended row (earlier rows unchanged) with no acceptance, signature,
     * email marker or quote source; the mirror reset from its non-null value to null; one new stored_file row and
     * one new file on disk (every other file unchanged); an {@code ACTIVE} link turned {@code REWRITTEN} with a
     * {@code dead_at} (every other link row unchanged, none minted); no write to any other order, customer,
     * address, line, payment or quote row; and nothing sent. Returns data.invoice.
     */
    private JsonNode assertRewrites(Scope scope, long orderId, String label) throws Exception {
        int current = currentVersion(orderId);
        ensureMirrorSentinel(orderId);
        Map<String, Object> untouchedBefore = untouchedState(orderId);
        List<Map<String, Object>> invoicesBefore = invoiceRows(orderId);
        List<Map<String, Object>> storedFilesBefore = storedFileRows(orderId);
        Map<String, String> filesBefore = diskFilesOfBusiness(scope.businessId());
        List<Map<String, Object>> tokensBefore = invoiceTokenRows(orderId);
        SenderCounts sendersBefore = senderCounts();

        MvcResult result = perform(rewriteRequest(scope, scope.session(), orderId, versionBody(current)));
        assertStatus(result, 201, label);
        JsonNode body = readJson(result);
        Assertions.assertEquals(REWRITTEN_MESSAGE, body.path("message").asText(), label + ": message");
        JsonNode invoice = body.path("data").path("invoice");
        Assertions.assertEquals(current + 1, invoice.path("version_number").asInt(), label + ": the server's current + 1");
        Assertions.assertTrue(invoice.path("accepted_at").isNull(), label + ": unsigned (accepted_at)");
        Assertions.assertTrue(invoice.path("accepted_customer_name").isNull(), label + ": unsigned (name)");
        Assertions.assertFalse(invoice.path("accepted_signature_present").asBoolean(true), label + ": no signature");
        Assertions.assertTrue(invoice.path("last_emailed_at").isNull(), label + ": unemailed");

        List<Map<String, Object>> invoicesAfter = invoiceRows(orderId);
        Assertions.assertEquals(invoicesBefore.size() + 1, invoicesAfter.size(), label + ": one invoice row is appended");
        Assertions.assertEquals(invoicesBefore, invoicesAfter.subList(0, invoicesBefore.size()),
                label + ": the earlier versions are untouched");
        Map<String, Object> appended = invoicesAfter.get(invoicesAfter.size() - 1);
        Assertions.assertEquals(current + 1, ((Number) appended.get("version_number")).intValue(), label + ": row version");
        for (String column : List.of("accepted_at", "accepted_customer_name", "accepted_signature_file_id",
                "last_emailed_at", "source_quote_version_id")) {
            Assertions.assertNull(appended.get(column), label + ": the appended row has no " + column);
        }
        Assertions.assertNull(mirror(orderId), label + ": the mirror follows the new version's null marker");

        List<Map<String, Object>> storedFilesAfter = storedFileRows(orderId);
        Assertions.assertEquals(storedFilesBefore.size() + 1, storedFilesAfter.size(), label + ": one stored_file row (the PDF)");
        Assertions.assertTrue(storedFilesAfter.containsAll(storedFilesBefore), label + ": no stored_file row changes");
        Map<String, String> filesAfter = diskFilesOfBusiness(scope.businessId());
        Assertions.assertEquals(filesBefore.size() + 1, filesAfter.size(), label + ": one new file on disk (the PDF)");
        Assertions.assertTrue(filesAfter.entrySet().containsAll(filesBefore.entrySet()),
                label + ": no file on disk is changed or removed");

        List<Map<String, Object>> tokensAfter = invoiceTokenRows(orderId);
        Assertions.assertEquals(tokensBefore.size(), tokensAfter.size(), label + ": no invoice link is minted");
        for (int i = 0; i < tokensBefore.size(); i++) {
            Map<String, Object> was = new LinkedHashMap<>(tokensBefore.get(i));
            Map<String, Object> now = new LinkedHashMap<>(tokensAfter.get(i));
            if ("ACTIVE".equals(was.get("status"))) {
                Assertions.assertEquals("REWRITTEN", now.get("status"), label + ": the ACTIVE link dies as REWRITTEN");
                Assertions.assertNotNull(now.get("dead_at"), label + ": the killed link has a dead_at");
                for (String column : List.of("status", "dead_at")) {
                    was.remove(column);
                    now.remove(column);
                }
            }
            Assertions.assertEquals(was, now, label + ": the link row is otherwise unchanged");
        }
        Assertions.assertEquals(sendersBefore, senderCounts(), label + ": a Rewrite sends nothing");
        Assertions.assertEquals(untouchedBefore, untouchedState(orderId),
                label + ": no order (besides the mirror), customer, address, line, payment or quote write");
        return invoice;
    }

    private static JsonNode assertError(MvcResult result, int status, String code, String message, String label)
            throws IOException {
        assertStatus(result, status, label);
        JsonNode body = readJson(result);
        Assertions.assertFalse(body.has("data"), () -> label + ": an error carries no data: " + body);
        JsonNode error = body.get("error");
        Assertions.assertNotNull(error, () -> label + ": error envelope missing: " + body);
        Assertions.assertEquals(code, error.path("code").asText(), () -> label + ": error code; body: " + body);
        Assertions.assertEquals(message, error.path("message").asText(), () -> label + ": error message; body: " + body);
        return error;
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + ": unexpected status; body: " + bodyOf(result));
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
        state.put("quote_draft", rows("SELECT * FROM quote_draft WHERE order_id = ? ORDER BY 1", orderId));
        state.put("quote_draft_line", rows(
                "SELECT l.* FROM quote_draft_line l JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id "
                        + "WHERE d.order_id = ? ORDER BY 1", orderId));
        state.put("quote_version", rows("SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId));
        state.put("quote_version_line", rows(
                "SELECT l.* FROM quote_version_line l JOIN quote_version v ON v.quote_version_id = l.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY 1", orderId));
        state.put("quote_token", rows(
                "SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY 1", orderId));
        return state;
    }

    /**
     * Everything an ALLOWED Rewrite must never write: the sales_order row without last_emailed_at (the mirror,
     * its only order write), the quote rows and the customer, address, line and payment rows.
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
            state.put(table, rows("SELECT * FROM " + table + " WHERE order_id = ? ORDER BY 1", orderId));
        }
        return state;
    }

    /** {@link #untouchedState} with the whole sales_order row (mirror included), its invoices, files and links. */
    private Map<String, Object> orderState(long orderId) {
        Map<String, Object> state = new LinkedHashMap<>(untouchedState(orderId));
        state.put("sales_order", rows("SELECT * FROM sales_order WHERE order_id = ?", orderId));
        state.put("invoice", invoiceRows(orderId));
        state.put("stored_file", storedFileRows(orderId));
        state.put("invoice_token", invoiceTokenRows(orderId));
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
        for (String table : List.of("sales_order", "invoice", "stored_file", "invoice_token", "quote_draft",
                "quote_version", "quote_token", "payment_transaction")) {
            counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        state.put("global row counts", counts);
        state.put("senders", senderCounts());
        return state;
    }

    private SenderCounts senderCounts() {
        return new SenderCounts(
                invoiceEmailSender.sentEmails().size(), invoiceEmailSender.failedEmails().size(),
                invoiceLinkEmailSender.sentEmails().size(), invoiceLinkEmailSender.failedEmails().size(),
                invoiceNotificationSender.sentNotifications().size(),
                invoiceNotificationSender.failedNotifications().size(),
                quoteEmailSender.sentEmails().size(), quoteEmailSender.failedEmails().size());
    }

    // ================================================================
    // Helpers: DB probes
    // ================================================================

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
            rows.add(new LinkedHashMap<>(row));
        }
        return rows;
    }

    private List<Map<String, Object>> invoiceRows(long orderId) {
        return rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId);
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber));
    }

    private int invoiceCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice WHERE order_id = ?",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    /** The order's current invoice version: the highest version_number (state-derived, never assumed). */
    private int currentVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "the order must have a current invoice");
        return version;
    }

    private List<Map<String, Object>> invoiceTokenRows(long orderId) {
        return rows("SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId);
    }

    private List<String> invoiceTokenStatuses(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", String.class, orderId);
    }

    private List<String> quoteVersionStatuses(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM quote_version WHERE order_id = ? ORDER BY version_number", String.class, orderId);
    }

    private List<String> quoteTokenStatuses(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT t.status FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY t.quote_token_id", String.class, orderId);
    }

    /** stored_file rows under the order's storage directory (every invoice PDF and signature of the order). */
    private List<Map<String, Object>> storedFileRows(long orderId) {
        return rows("SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                orderStoragePrefix(orderId) + "%");
    }

    private Timestamp mirror(long orderId) {
        return jdbcTemplate.queryForObject("SELECT last_emailed_at FROM sales_order WHERE order_id = ?",
                Timestamp.class, orderId);
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private long businessIdOf(long orderId) {
        Long businessId = jdbcTemplate.queryForObject(
                "SELECT business_id FROM sales_order WHERE order_id = ?", Long.class, orderId);
        Assertions.assertNotNull(businessId, "the watched order must exist");
        return businessId;
    }

    // ================================================================
    // Helpers: disk and values
    // ================================================================

    /** FileStorageService lays every order file out under {base}/uploads/{businessId}/orders/{orderId}/. */
    private String orderStoragePrefix(long orderId) {
        return "/uploads/" + businessIdOf(orderId) + "/orders/" + orderId + "/";
    }

    private Path businessDir(long businessId) {
        return Path.of(storageBaseDir).toAbsolutePath().normalize().resolve("uploads").resolve(String.valueOf(businessId));
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
        Assertions.assertTrue(actual instanceof BigDecimal, () -> label + " must be a BigDecimal but was " + actual);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                () -> label + ": expected " + expected + " but was " + actual);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
