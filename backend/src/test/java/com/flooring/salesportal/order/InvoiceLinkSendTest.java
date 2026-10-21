package com.flooring.salesportal.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceLinkEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 16G PR 4 (16G contract §8, §4.5, §7.5, §7.6, §11, §12 and §14; the PR 4 row of §16) - the protected
 * send-link {@code POST /api/v1/{slug}/orders/{orderId}/invoices/current/send-link} (Accept Remotely), at the
 * REAL commit boundary and through the REAL Spring Security filter chain:
 * <ul>
 *   <li><b>Order of checks</b>: the standard protected guard (unknown slug 404, no session 401, another
 *       business's session 404, no store / no store access 403) → a positive {@code orderId} (400 on
 *       {@code order_id}) → the scoped, locked order (404 {@code ORDER_NOT_FOUND} for another business's or
 *       another store's order, before the body) → the unchanged {@code EmptyObjectRequest} rule (the same
 *       answers as D.9 Re-send and D.1 Create; before {@code INVOICE_REQUIRED}) → a current invoice (422
 *       {@code INVOICE_REQUIRED}) → an unsigned current invoice (409 {@code INVOICE_ALREADY_ACCEPTED}, default
 *       message, before the email gate) → the saved customer email (422 {@code CUSTOMER_EMAIL_REQUIRED} /
 *       {@code CUSTOMER_EMAIL_INVALID}). Every refusal writes nothing (no {@code invoice_token}, invoice,
 *       marker, {@code stored_file} or file change) and sends nothing of any kind.</li>
 *   <li><b>Success</b>: 201 {@code {"data":{"invoice_link":{"sent_at","expires_at"}}}} in whole seconds,
 *       {@code expires_at = sent_at + 7 days}; one new {@code ACTIVE} row (the session user as sender, the
 *       hash of the emailed token); one link-only email to the TRIMMED saved email with the link exactly once;
 *       {@code emailed_at} stamped; nothing else written or sent (both email markers keep their sentinels); no log
 *       line at INFO or above, from any logger, carries the token, its hash, the link or the recipient.</li>
 *   <li><b>Replacement</b> (an older {@code ACTIVE} link dies {@code REPLACED} at the new send's
 *       {@code sent_at}; the public surface answers the old link {@code REPLACED}), <b>delivery failure</b>
 *       (502 {@code EMAIL_SEND_FAILED} with the route's message; the new link stays {@code ACTIVE} and
 *       undelivered, the previous one stays {@code REPLACED}; one WARN), <b>stamp failure</b> at the statement
 *       and at commit (201; the delivered link stays {@code ACTIVE} with no {@code emailed_at}; one WARN), a
 *       <b>failure inside the send transaction</b> (the mint INSERT: generic 500, the in-flight {@code REPLACED}
 *       kill rolls back with it, nothing sent),
 *       <b>LAID / CANCELLED</b> (allowed; a status change never kills a link) and InvoiceDetail's derived
 *       {@code invoice_link_expires_at} (D.1 / D.3 / D.8; never on E.1; a staff read never expires a link).</li>
 * </ul>
 *
 * <p><b>Fixtures - fully self-seeded and COMMITTED</b> (this class is NOT {@code @Transactional}: the send
 * commits before its delivery). Two tenants, keyed by their slugs: {@value #SLUG} (stores {@value #STORE_CODE}
 * and {@value #SECOND_STORE_CODE}; salespeople {@value #OWNER_CODE} with access to both stores and
 * {@value #SENDER_CODE} with access to {@value #STORE_CODE} only) and {@value #OTHER_SLUG} (store
 * {@value #OTHER_STORE_CODE}, salesperson {@value #OTHER_CODE}). Each order is invoice-ready (customer with a
 * PADDED valid email, INSTALLATION + BILLING, details of sale, lay date 2026-12-01 CONFIRMED, one
 * {@value #CHARGE_CODE_PREFIX}-coded 100.00 ex / 40.00 cost charge line) and gets its invoice through the real
 * D.1 endpoint; links, D.8, payments, voids and status changes go through their real endpoints too. The
 * plaintext token is read from the recorded link email. No V4 identity is read or written.
 *
 * <p><b>Fault injection</b> - no bean overrides: the recording link sender's own one-shot hooks
 * ({@code failNextSend()} / {@code failNextSendWith(...)}) for delivery failures; for the {@code emailed_at}
 * stamp, a class-scoped plpgsql trigger on {@code invoice_token} (non-deferred, or a
 * {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger) that bumps a non-transactional sequence and raises
 * when THIS order's token gets its first {@code emailed_at}; for the mint, a {@code BEFORE INSERT} trigger for
 * THIS order that also records (with the never-rolled-back {@code setval}) what the INSERT saw in flight. The
 * NULL / blank saved-email cases run inside a programmatic transaction that is always rolled back. Triggers,
 * their functions and sequences are dropped in {@code finally} and by the sweep.
 *
 * <p><b>Sweep</b> ({@code @BeforeEach} and {@code @AfterEach}): per order, in one bounded transaction,
 * {@code invoice_token} (before {@code sales_order} and {@code app_user}), {@code quote_token}, {@code invoice},
 * {@code quote_version}, {@code quote_draft}, {@code payment_transaction}, the other order children,
 * {@code sales_order} and the order's {@code stored_file} rows, then its files and directory; per tenant,
 * {@code store_charge}, {@code store_product}, {@code user_store_access}, {@code app_user}, {@code store},
 * {@code business_quick_description} and {@code business}, then the tenant's storage directory; then every
 * {@value #TRIGGER_PREFIX} trigger, function and sequence. Every recording sender is reset in both.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class InvoiceLinkSendTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before anything is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The two self-seeded tenants; the slugs are the sweep's only keys ----
    private static final String SLUG = "pr4-link-send";
    private static final String OTHER_SLUG = "pr4-link-send-other";
    /** Never inserted: the guard's unknown-business case. */
    private static final String UNKNOWN_SLUG = "pr4-link-send-unknown";
    private static final String BUSINESS_NAME = "Lanternfield Link Flooring";
    private static final String OTHER_BUSINESS_NAME = "Lanternfield Elsewhere Flooring";
    private static final String STORE_CODE = "ILS-ONE";
    private static final String SECOND_STORE_CODE = "ILS-TWO";
    private static final String OTHER_STORE_CODE = "ILS-OTH";
    /** The orders' salesperson ({@code sales_order.user_id}); access to ILS-ONE and ILS-TWO. */
    private static final String OWNER_CODE = "LS1";
    /** A second staff member with access to ILS-ONE only: the sender that proves {@code sent_by_user_id}. */
    private static final String SENDER_CODE = "LS2";
    private static final String OTHER_CODE = "LO1";
    private static final String CHARGE_CODE_PREFIX = "ILSND";
    private static final String BUSINESS_ABN = "51 824 753 556";

    // ---- invoice_token write-failure injection (class-unique names; the sweep drops every ilsnd_ trigger) ----
    private static final String TRIGGER_PREFIX = "ilsnd_";
    private static final String TOKEN_WRITE_FAILURE_FUNCTION = "ilsnd_fail_token_write";
    private static final String TOKEN_WRITE_FIRES_SEQUENCE = "ilsnd_token_write_fires";
    private static final String MINT_FAILURE_FUNCTION = "ilsnd_fail_token_mint";
    /** Records (setval, never rolled back) 100 + the order's ACTIVE rows seen by the failing mint INSERT. */
    private static final String MINT_ACTIVE_SEEN_SEQUENCE = "ilsnd_mint_active_seen";
    private static final long MINT_ACTIVE_SEEN_OFFSET = 100L;

    // ---- The seeded customer ----
    private static final String CUSTOMER_FIRST_NAME = "Willow";
    private static final String CUSTOMER_LAST_NAME = "Lantern";
    /** D.1's freeze of the saved name (customer_name_snapshot). */
    private static final String FROZEN_CUSTOMER_NAME = "Willow Lantern";
    private static final String CUSTOMER_EMAIL = "willow.lantern@example.com";
    /** Saved with edge whitespace (DB-valid; app-valid once trimmed): every recipient must be the trimmed value. */
    private static final String SAVED_CUSTOMER_EMAIL = "  " + CUSTOMER_EMAIL + "  ";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and install wool carpet (invoice link send test)";

    // ---- Committed marker sentinels: a link send must move neither ----
    private static final Timestamp INVOICE_MARKER_SENTINEL = Timestamp.valueOf("2026-01-02 03:04:05.123456");
    private static final Timestamp MIRROR_MARKER_SENTINEL = Timestamp.valueOf("2026-02-03 04:05:06.654321");

    // ---- The public link: {app.public-base-url}/i/{43-char token} ----
    private static final String PUBLIC_BASE_URL = "http://localhost:5173";
    private static final Pattern LINK_PATTERN =
            Pattern.compile(Pattern.quote(PUBLIC_BASE_URL + "/i/") + "([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final Pattern WHOLE_SECOND_TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    private static final DateTimeFormatter JSON_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    // ---- Exact contract wording ----
    private static final String LINK_SENT_MESSAGE = "Invoice link emailed to the customer.";
    private static final String LINK_EMAIL_FAILED_MESSAGE = "The invoice link could not be emailed. Please try again.";
    private static final String INVOICE_ALREADY_ACCEPTED_MESSAGE =
            "This invoice has already been accepted. Use Re-send to email it again.";
    private static final String INVOICE_REQUIRED_MESSAGE = "An invoice is required for this action.";
    private static final String CUSTOMER_EMAIL_REQUIRED_MESSAGE = "A valid customer email is required before this action.";
    private static final String CUSTOMER_EMAIL_INVALID_MESSAGE = "Customer email is not a valid email address.";
    private static final String VALIDATION_FAILED_MESSAGE = "One or more fields are invalid.";
    private static final String MALFORMED_JSON_MESSAGE = "Request body is malformed.";
    private static final String ORDER_NOT_FOUND_MESSAGE = "Order not found.";
    private static final String BUSINESS_NOT_FOUND_MESSAGE = "Business not found.";
    private static final String UNAUTHORIZED_MESSAGE = "Authentication required.";
    private static final String FORBIDDEN_MESSAGE = "Access denied.";
    private static final String INVOICE_ALREADY_EXISTS_MESSAGE =
            "An invoice already exists for this order. Use Rewrite Invoice to create a new version.";
    private static final String LINK_REPLACED_MESSAGE =
            "This invoice link has been replaced. Please use the latest invoice link.";
    private static final String INVOICE_LOADED_MESSAGE = "Invoice loaded.";
    private static final String GENERIC_500_MESSAGE = "An unexpected error occurred.";
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String PAYMENT_VOIDED_MESSAGE = "Payment voided. Current invoice updated.";
    private static final String STATUS_UPDATED_MESSAGE = "Order status updated.";
    // The OrderInvoiceService WARN lines (order id and version only; never a recipient, token, link or exception).
    private static final String DELIVERY_FAILED_WARN = "Invoice link email failed for order %d invoice v%d "
            + "(the new link stays ACTIVE and undelivered; a previous link stays REPLACED)";
    private static final String STAMP_FAILED_WARN = "Invoice link emailed_at stamp failed for order %d invoice v%d "
            + "after delivery (non-fatal; the delivered link stays ACTIVE)";

    // ---- Error details ----
    private static final List<Detail> NO_DETAILS = List.of();
    private static final List<Detail> ORDER_ID_DETAIL = List.of(new Detail("order_id", "Must be a positive integer."));
    private static final List<Detail> BODY_MUST_BE_EMPTY = List.of(new Detail("body", "Request body must be empty."));

    // ---- The EmptyObjectRequest forms (16G contract §8 "Request"; the shared rule, unchanged) ----
    private static final Body EMPTY_OBJECT = new Body("{}", "{}");
    private static final List<Body> PASSING_BODIES = List.of(
            new Body("no body", null),
            new Body("an empty string", ""),
            new Body("a whitespace-only string", "   "),
            EMPTY_OBJECT,
            new Body("JSON null", "null"));
    private static final List<BodyCase> BODY_ERROR_CASES = List.of(
            new BodyCase(new Body("{\"a\":1,\"b\":2}", "{\"a\":1,\"b\":2}"), 400, "VALIDATION_FAILED",
                    VALIDATION_FAILED_MESSAGE, List.of(new Detail("a", "Not allowed."), new Detail("b", "Not allowed."))),
            new BodyCase(new Body("[]", "[]"), 400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE, BODY_MUST_BE_EMPTY),
            new BodyCase(new Body("\"x\"", "\"x\""), 400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE,
                    BODY_MUST_BE_EMPTY),
            new BodyCase(new Body("1", "1"), 400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE, BODY_MUST_BE_EMPTY),
            new BodyCase(new Body("true", "true"), 400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE,
                    BODY_MUST_BE_EMPTY),
            new BodyCase(new Body("{", "{"), 400, "MALFORMED_JSON", MALFORMED_JSON_MESSAGE, NO_DETAILS),
            new BodyCase(new Body("{\"a\":", "{\"a\":"), 400, "MALFORMED_JSON", MALFORMED_JSON_MESSAGE, NO_DETAILS));

    // ---- The public invoice view: exactly these 27 keys, always present (16G contract §9.3) ----
    private static final Set<String> PUBLIC_VIEW_KEYS = Set.of(
            "state", "business_name", "business_logo_url", "accent_color", "business_abn",
            "payment_account_name", "payment_bank_name", "payment_bsb", "payment_account_number",
            "payment_stripe_link_url", "order_number", "flooring_type", "version_number", "invoice_date",
            "due_date", "customer_name", "customer_address_line1", "customer_address_line2", "details_of_sale",
            "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst", "total_paid", "balance_due", "terms_html",
            "expires_at", "message");
    private static final Set<String> DEAD_VIEW_NON_NULL_KEYS = Set.of("state", "business_name", "message");

    // Every stored_file an order owns (invoice PDFs + signatures, quote PDFs + signatures, attachments) plus
    // anything stored under the order's storage directory.
    private static final String ORDER_FILES_SQL = """
            SELECT stored_file_id, storage_path
            FROM stored_file
            WHERE stored_file_id IN (
                      SELECT stored_file_id FROM invoice WHERE order_id = ?
                      UNION SELECT accepted_signature_file_id FROM invoice WHERE order_id = ?
                      UNION SELECT issued_pdf_file_id FROM quote_version WHERE order_id = ?
                      UNION SELECT signed_pdf_file_id FROM quote_version WHERE order_id = ?
                      UNION SELECT accepted_signature_file_id FROM quote_version WHERE order_id = ?
                      UNION SELECT stored_file_id FROM order_attachment WHERE order_id = ?)
               OR storage_path LIKE ?
            """;

    // Children of sales_order deleted after invoice_token / quote_token / invoice / quote_version / quote_draft
    // / payment_transaction (every child FK is RESTRICT; the two *_line quote tables CASCADE from their parent).
    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "order_attachment", "order_note", "order_product_line", "order_charge_line",
            "order_address", "order_customer", "order_enquiry");

    // A real, decodable 1x1 PNG (D.8 and the public accept both take it).
    private static final byte[] SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // Exact money parsing for response bodies (never a double round-trip).
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RecordingInvoiceLinkEmailSender linkEmailSender;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingInvoiceAcceptanceNotificationSender invoiceNotificationSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender quoteNotificationSender;

    @Autowired
    private RecordingSmsSender smsSender;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    @Value("${app.public-base-url}")
    private String publicBaseUrl;

    private MockMvc mockMvc;

    private TransactionTemplate transactionTemplate;

    // The committed tenants of the running test (seeded by setUp after the sweep).
    private long homeBusinessId;
    private long otherBusinessId;
    private int storeTwoId;
    private long ownerUserId;
    private long senderUserId;
    /** LS1 at ILS-ONE: the home orders' scope. */
    private Scope homeStoreOne;
    /** LS1 at ILS-TWO: the other store of the same business. */
    private Scope homeStoreTwo;
    /** LS2 at ILS-ONE: the second staff member (sender). */
    private Scope senderStoreOne;
    /** LO1 at ILS-OTH: the other business. */
    private Scope otherBusiness;
    private final Map<Long, Integer> orderSequences = new HashMap<>();

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes files recursively - never against a real storage dir.
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class writes and sweeps files only under the shared quote / invoice test storage dir");
        Assertions.assertTrue(storageRoot().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                () -> "unexpected storage root for a sweeping test class: " + storageRoot());
        Assertions.assertEquals(PUBLIC_BASE_URL, publicBaseUrl,
                "the link email carries {app.public-base-url}/i/{token}; this class pins the dev value");
        // apply(springSecurity()): every request runs the REAL Spring Security filter chain.
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        transactionTemplate = new TransactionTemplate(transactionManager);
        cleanUpEverything();
        seedTenants();
    }

    @AfterEach
    void tearDown() {
        cleanUpEverything();
    }

    // ================================================================
    // A. Guard and scope precedence (16G contract §8 steps 1 and 2)
    // ================================================================

    /**
     * The standard protected guard runs first, on a fully sendable order (unsigned invoice, valid saved email)
     * that already has a live link: an unknown slug is 404 whatever the session carries (even none); no
     * session, or a session without a user / business id, is 401; a session of ANOTHER business on this slug is
     * 404 {@code NOT_FOUND} (no existence leak); no selected store, a store the user cannot access and a store of
     * another business are 403. The guard answers before a bad order id or a bad body is looked at. Every
     * refusal writes and sends nothing - the live link is untouched.
     */
    @Test
    void sendLink_guardFirst_unknownSlug404_noSession401_otherBusinessSession404_noStoreOrNoStoreAccess403_eachRefusalWritesAndSendsNothing()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink live = sendLinkOk(order, homeStoreOne.session(), "setup: the live link no refusal may touch");
        plantMarkerSentinels(order);

        assertRefused(sendLinkRequest(UNKNOWN_SLUG, order.orderId(), homeStoreOne.session(), EMPTY_OBJECT),
                404, "NOT_FOUND", BUSINESS_NOT_FOUND_MESSAGE, NO_DETAILS, "an unknown slug with a valid session");
        assertRefused(sendLinkRequest(UNKNOWN_SLUG, order.orderId(), null, EMPTY_OBJECT),
                404, "NOT_FOUND", BUSINESS_NOT_FOUND_MESSAGE, NO_DETAILS, "an unknown slug without a session");
        assertRefused(sendLinkRequest(SLUG, order.orderId(), null, EMPTY_OBJECT),
                401, "UNAUTHORIZED", UNAUTHORIZED_MESSAGE, NO_DETAILS, "no session");
        assertRefused(sendLinkRequest(SLUG, order.orderId(), new MockHttpSession(), EMPTY_OBJECT),
                401, "UNAUTHORIZED", UNAUTHORIZED_MESSAGE, NO_DETAILS, "a session without a user or business id");
        assertRefused(sendLinkRequest(SLUG, order.orderId(), otherBusiness.session(), EMPTY_OBJECT),
                404, "NOT_FOUND", BUSINESS_NOT_FOUND_MESSAGE, NO_DETAILS, "a session of another business");
        assertRefused(sendLinkRequest(SLUG, order.orderId(), sessionOf(ownerUserId, homeBusinessId, null), EMPTY_OBJECT),
                403, "FORBIDDEN", FORBIDDEN_MESSAGE, NO_DETAILS, "a session without a selected store");
        assertRefused(sendLinkRequest(SLUG, order.orderId(), sessionOf(senderUserId, homeBusinessId, storeTwoId),
                        EMPTY_OBJECT),
                403, "FORBIDDEN", FORBIDDEN_MESSAGE, NO_DETAILS, "LS2 with ILS-TWO selected (no access to it)");
        assertRefused(sendLinkRequest(SLUG, order.orderId(),
                        sessionOf(ownerUserId, homeBusinessId, otherBusiness.storeId()), EMPTY_OBJECT),
                403, "FORBIDDEN", FORBIDDEN_MESSAGE, NO_DETAILS, "a store of another business selected");

        // The guard answers before the order id and the body are looked at.
        assertRefused(sendLinkRequest(SLUG, "abc", null, new Body("[]", "[]")),
                401, "UNAUTHORIZED", UNAUTHORIZED_MESSAGE, NO_DETAILS, "no session, a bad order id and a bad body");
        assertRefused(sendLinkRequest(UNKNOWN_SLUG, "0", homeStoreOne.session(), new Body("{", "{")),
                404, "NOT_FOUND", BUSINESS_NOT_FOUND_MESSAGE, NO_DETAILS, "an unknown slug, a bad order id, bad JSON");
        assertRefused(sendLinkRequest(SLUG, "abc", sessionOf(ownerUserId, homeBusinessId, null),
                        new Body("{\"a\":1}", "{\"a\":1}")),
                403, "FORBIDDEN", FORBIDDEN_MESSAGE, NO_DETAILS, "no store, a bad order id and a field");

        assertLinkUntouched(live, "after every guard refusal");
        assertMarkerSentinelsKept(order, "after every guard refusal");
    }

    /**
     * After the guard, {@code orderId} must be a positive integer (400 {@code VALIDATION_FAILED} on
     * {@code order_id}, {@code Must be a positive integer.}) - checked before the scoped lookup and before the
     * body, so a bad id with a bad body is still the {@code order_id} 400. Nothing is written or sent.
     */
    @Test
    void sendLink_orderIdNotAPositiveInteger_returns400OnOrderId_beforeTheScopedLookupAndTheBody_writesNothing()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink live = sendLinkOk(order, homeStoreOne.session(), "setup: a live link");
        plantMarkerSentinels(order);

        for (String raw : List.of("abc", "0", "-1", "1.5", "9223372036854775808")) {
            assertRefused(sendLinkRequest(SLUG, raw, homeStoreOne.session(), EMPTY_OBJECT),
                    400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE, ORDER_ID_DETAIL, "order id '" + raw + "'");
        }
        assertRefused(sendLinkRequest(SLUG, "abc", homeStoreOne.session(), new Body("{\"a\":1}", "{\"a\":1}")),
                400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE, ORDER_ID_DETAIL, "order id 'abc' with a field");
        assertRefused(sendLinkRequest(SLUG, "0", homeStoreOne.session(), new Body("{", "{")),
                400, "VALIDATION_FAILED", VALIDATION_FAILED_MESSAGE, ORDER_ID_DETAIL, "order id '0' with bad JSON");

        assertLinkUntouched(live, "after every order-id refusal");
        assertMarkerSentinelsKept(order, "after every order-id refusal");
    }

    /**
     * The order is scoped to the session's business AND store before anything else about the request: an
     * order of another business, an order of another store of the same business and an unknown order id all
     * give 404 {@code ORDER_NOT_FOUND} {@code Order not found.} - for every body, valid or not (scope before
     * body). Each foreign order is fully sendable in its OWN scope (its own session sent it a live link), so the
     * 404 is purely the scope; its live link is untouched and nothing is written or sent.
     */
    @Test
    void sendLink_orderOutsideTheSessionScope_anotherBusinessAnotherStoreOrUnknown_returns404OrderNotFound_evenWithAnInvalidBody_writesNothing()
            throws Exception {
        SeededOrder homeOrder = invoicedOrder(homeStoreOne);
        SentLink homeLink = sendLinkOk(homeOrder, homeStoreOne.session(), "setup: an ILS-ONE order's link");
        SeededOrder foreignOrder = invoicedOrder(otherBusiness);
        SentLink foreignLink = sendLinkOk(foreignOrder, otherBusiness.session(),
                "setup: the other business sends its own order's link");
        SeededOrder storeTwoOrder = invoicedOrder(homeStoreTwo);
        SentLink storeTwoLink = sendLinkOk(storeTwoOrder, homeStoreTwo.session(),
                "setup: the ILS-TWO session sends its own order's link");
        for (SeededOrder seeded : List.of(homeOrder, foreignOrder, storeTwoOrder)) {
            plantMarkerSentinels(seeded);
        }
        long unknownOrderId = unusedOrderId();

        List<Body> bodies = new ArrayList<>(List.of(EMPTY_OBJECT, PASSING_BODIES.get(0)));
        BODY_ERROR_CASES.forEach(bodyCase -> bodies.add(bodyCase.body()));
        Map<String, Object> targets = new LinkedHashMap<>();
        targets.put("another business's order", foreignOrder.orderId());
        targets.put("another store's order", storeTwoOrder.orderId());
        targets.put("an unknown order id", unknownOrderId);
        Map<String, MockHttpSession> sessions = new LinkedHashMap<>();
        sessions.put("LS1 at ILS-ONE", homeStoreOne.session());
        sessions.put("LS2 at ILS-ONE", senderStoreOne.session());
        for (Map.Entry<String, Object> target : targets.entrySet()) {
            for (Map.Entry<String, MockHttpSession> session : sessions.entrySet()) {
                for (Body body : bodies) {
                    assertRefused(sendLinkRequest(SLUG, target.getValue(), session.getValue(), body),
                            404, "ORDER_NOT_FOUND", ORDER_NOT_FOUND_MESSAGE, NO_DETAILS,
                            target.getKey() + " from " + session.getKey() + " with " + body);
                }
            }
        }
        // The reverse direction: the ILS-TWO session cannot reach an ILS-ONE order either.
        for (Body body : bodies) {
            assertRefused(sendLinkRequest(SLUG, homeOrder.orderId(), homeStoreTwo.session(), body),
                    404, "ORDER_NOT_FOUND", ORDER_NOT_FOUND_MESSAGE, NO_DETAILS,
                    "an ILS-ONE order from LS1 at ILS-TWO with " + body);
        }

        assertLinkUntouched(homeLink, "the ILS-ONE order's link");
        assertLinkUntouched(foreignLink, "the other business's link");
        assertLinkUntouched(storeTwoLink, "the ILS-TWO order's link");
        for (SeededOrder seeded : List.of(homeOrder, foreignOrder, storeTwoOrder)) {
            assertMarkerSentinelsKept(seeded, "order " + seeded.orderNumber() + " after every scope refusal");
        }
    }

    // ================================================================
    // B. The EmptyObjectRequest body (16G contract §8 "Request" and step 3)
    // ================================================================

    static Stream<BodyCase> emptyObjectBodyMatrix() {
        Stream<BodyCase> passing = PASSING_BODIES.stream().map(body ->
                new BodyCase(body, 422, "INVOICE_REQUIRED", INVOICE_REQUIRED_MESSAGE, NO_DETAILS));
        return Stream.concat(passing, BODY_ERROR_CASES.stream());
    }

    /**
     * The body rule is the shared, unchanged {@code EmptyObjectRequest} rule, and it runs BEFORE the
     * {@code INVOICE_REQUIRED} gate: on an in-scope order with no invoice, the five passing forms (no body, an
     * empty string, whitespace, {@code {}}, JSON {@code null}) reach the next gate (422
     * {@code INVOICE_REQUIRED}); each field is {@code Not allowed.} (one detail per field, in body order); an
     * array or a scalar is {@code Request body must be empty.} on {@code body}; unparseable JSON is 400
     * {@code MALFORMED_JSON}. D.9 Re-send, another {@code EmptyObjectRequest} route with the same next gate
     * here, answers every form identically. Nothing is written or sent.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("emptyObjectBodyMatrix")
    void sendLink_bodyMatrix_onAnOrderWithoutAnInvoice_isTheSharedEmptyObjectRule_identicalToD9Resend_bodyBeforeInvoiceRequired(
            BodyCase bodyCase) throws Exception {
        SeededOrder order = invoiceReadyOrder(homeStoreOne);

        MvcResult sendLink = assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), bodyCase.body()),
                bodyCase.status(), bodyCase.code(), bodyCase.message(), bodyCase.details(), "send-link with " + bodyCase);
        MvcResult resend = assertRefused(withBody(post(orderUrl(order) + "/invoices/current/resend")
                        .session(homeStoreOne.session()), bodyCase.body()),
                bodyCase.status(), bodyCase.code(), bodyCase.message(), bodyCase.details(), "D.9 resend with " + bodyCase);
        Assertions.assertEquals(json(resend), json(sendLink),
                "send-link answers " + bodyCase.body() + " exactly as D.9 Re-send does (the shared EmptyObjectRequest rule)");
        Assertions.assertEquals(List.of(), tokenRows(order.orderId()), "no invoice link was minted");
    }

    /**
     * Each passing body form really reaches the send on an invoiced order: five sends in a row, each 201 with
     * the exact shape and one delivered email, each minting a new {@code ACTIVE} link and replacing the previous
     * one ({@code REPLACED} at exactly the next send's {@code sent_at}), so the order always has exactly one live
     * link and five distinct tokens.
     */
    @Test
    void sendLink_everyPassingBodyForm_reachesTheSend_201_eachSendReplacingThePreviousLink_exactlyOneActiveLink()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        List<SentLink> sends = new ArrayList<>();
        for (Body body : PASSING_BODIES) {
            sends.add(sendLinkOk(order, homeStoreOne.session(), body, "send-link with " + body));
            Assertions.assertEquals(sends.size(), tokenRows(order.orderId()).size(), "one row per send");
            Assertions.assertEquals(1L, activeLinkCount(order.orderId()), "exactly one ACTIVE link after each send");
        }
        Assertions.assertEquals(PASSING_BODIES.size(), sends.stream().map(SentLink::token).distinct().count(),
                "every send minted a different token");
        for (int i = 0; i < sends.size() - 1; i++) {
            Map<String, Object> replacing = tokenRowByHash(sends.get(i + 1).hash());
            Assertions.assertEquals(
                    withColumns(sends.get(i).row(), "status", "REPLACED", "dead_at", replacing.get("sent_at")),
                    tokenRowByHash(sends.get(i).hash()),
                    "send " + (i + 1) + "'s link was REPLACED at exactly send " + (i + 2) + "'s sent_at, nothing else changed");
        }
        Assertions.assertEquals(sends.get(sends.size() - 1).row(), tokenRowByHash(sends.get(sends.size() - 1).hash()),
                "the last link is the live one, as minted");
    }

    /**
     * A body refusal on an invoiced order that already has a live link: every non-empty or unparseable body is
     * its 400 - exactly the answer D.1 Create gives the same body on the same order (its body check also runs
     * before its next gate) - and the live link is neither replaced nor re-minted; nothing is written or sent.
     */
    @Test
    void sendLink_bodyErrorsOnAnInvoicedOrderWithALiveLink_return400_identicalToD1_theLiveLinkIsNotReplaced_writesNothing()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink live = sendLinkOk(order, homeStoreOne.session(), "setup: a live link");
        plantMarkerSentinels(order);

        for (BodyCase bodyCase : BODY_ERROR_CASES) {
            MvcResult sendLink = assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), bodyCase.body()),
                    bodyCase.status(), bodyCase.code(), bodyCase.message(), bodyCase.details(), "send-link with " + bodyCase);
            MvcResult create = assertRefused(withBody(post(orderUrl(order) + "/invoices").session(homeStoreOne.session()),
                            bodyCase.body()),
                    bodyCase.status(), bodyCase.code(), bodyCase.message(), bodyCase.details(), "D.1 with " + bodyCase);
            Assertions.assertEquals(json(create), json(sendLink),
                    "send-link answers " + bodyCase.body() + " exactly as D.1 Create does");
        }
        // Control: D.1 with a passing body reaches ITS next gate on this order (an invoice exists).
        assertRefused(withBody(post(orderUrl(order) + "/invoices").session(homeStoreOne.session()), EMPTY_OBJECT),
                409, "INVOICE_ALREADY_EXISTS", INVOICE_ALREADY_EXISTS_MESSAGE, NO_DETAILS, "control: D.1 with {}");

        assertLinkUntouched(live, "after every body refusal");
        assertMarkerSentinelsKept(order, "after every body refusal");
    }

    // ================================================================
    // C. The invoice-state and customer-email gates (16G contract §8 steps 4 to 6)
    // ================================================================

    /** No current invoice: 422 {@code INVOICE_REQUIRED}; no link minted, nothing written or sent. */
    @Test
    void sendLink_noInvoice_returns422InvoiceRequired_mintsNoLink_writesAndSendsNothing() throws Exception {
        SeededOrder order = invoiceReadyOrder(homeStoreOne);

        assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), EMPTY_OBJECT),
                422, "INVOICE_REQUIRED", INVOICE_REQUIRED_MESSAGE, NO_DETAILS, "no invoice (LS1)");
        assertRefused(sendLinkRequest(SLUG, order.orderId(), senderStoreOne.session(), PASSING_BODIES.get(0)),
                422, "INVOICE_REQUIRED", INVOICE_REQUIRED_MESSAGE, NO_DETAILS, "no invoice (LS2, no body)");

        Assertions.assertEquals(List.of(), tokenRows(order.orderId()), "no invoice link was minted");
    }

    /**
     * A current invoice signed in store through the REAL D.8: D.8 itself kills the live link as {@code SIGNED}
     * with {@code dead_at} = the signing version's {@code accepted_at} (full precision) and returns
     * {@code invoice_link_expires_at} null, as D.3 does afterwards. The send then refuses the signed current
     * version with 409 {@code INVOICE_ALREADY_ACCEPTED} and its DEFAULT message, before the email gate (a
     * malformed saved email or no customer row at all still gives the 409). The SIGNED row keeps its status and
     * {@code dead_at}; no link is created; nothing is written or sent.
     */
    @Test
    void sendLink_signedCurrentInvoice_viaTheRealD8_returns409InvoiceAlreadyAcceptedDefaultMessage_beforeTheEmailGate_theSignedLinkRowIsUntouched()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink live = sendLinkOk(order, homeStoreOne.session(), "setup: the link D.8 kills");

        MvcResult signed = perform(d8Accept(order, 1), 201, "setup: D.8 in-store signing of v1");
        JsonNode signedRoot = json(signed);
        Assertions.assertEquals(ACCEPT_EMAILED_MESSAGE, signedRoot.path("message").asText(), "setup: D.8 message");
        JsonNode signedInvoice = signedRoot.path("data").path("invoice");
        Assertions.assertEquals(2, signedInvoice.path("version_number").asInt(), "setup: D.8 appended v2");
        assertPresentAndNull(signedInvoice, "invoice_link_expires_at", "D.8's InvoiceDetail (it killed the link)");
        Map<String, Object> v2 = invoiceRow(order.orderId(), 2);
        Assertions.assertNotNull(v2.get("accepted_at"), "setup: v2 is signed");
        Map<String, Object> killed = tokenRowByHash(live.hash());
        Assertions.assertEquals(withColumns(live.row(), "status", "SIGNED", "dead_at", v2.get("accepted_at")), killed,
                "D.8 killed the live link as SIGNED at exactly the signing time; nothing else of the row changed");
        assertPresentAndNull(getCurrentInvoice(order), "invoice_link_expires_at", "D.3 of the signed current version");
        MvcResult resent = perform(post(orderUrl(order) + "/invoices/current/resend").session(homeStoreOne.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200, "D.9 re-send of the signed version");
        Assertions.assertEquals(2, json(resent).path("data").path("invoice").path("version_number").asInt(),
                "D.9 re-sent the signed v2");
        assertPresentAndNull(json(resent).path("data").path("invoice"), "invoice_link_expires_at",
                "D.9's InvoiceDetail of the signed current version");

        assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), EMPTY_OBJECT),
                409, "INVOICE_ALREADY_ACCEPTED", INVOICE_ALREADY_ACCEPTED_MESSAGE, NO_DETAILS,
                "send-link on the signed current version");
        assertRefused(sendLinkRequest(SLUG, order.orderId(), senderStoreOne.session(), PASSING_BODIES.get(0)),
                409, "INVOICE_ALREADY_ACCEPTED", INVOICE_ALREADY_ACCEPTED_MESSAGE, NO_DETAILS,
                "send-link on the signed current version (LS2, no body)");
        // The signed check precedes the email gate (a saved email is NOT NULL and non-blank in the schema, so
        // "missing" is the absent customer row).
        setCustomerEmail(order, "willow@localhost");
        assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), EMPTY_OBJECT),
                409, "INVOICE_ALREADY_ACCEPTED", INVOICE_ALREADY_ACCEPTED_MESSAGE, NO_DETAILS,
                "signed + a malformed saved email");
        withSavedEmailInARolledBackTransaction(order, null, "signed + a NULL saved email (rolled back)", () ->
                assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), EMPTY_OBJECT),
                        409, "INVOICE_ALREADY_ACCEPTED", INVOICE_ALREADY_ACCEPTED_MESSAGE, NO_DETAILS,
                        "signed + a NULL saved email (rolled back)"));
        deleteCustomerRow(order);
        assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), EMPTY_OBJECT),
                409, "INVOICE_ALREADY_ACCEPTED", INVOICE_ALREADY_ACCEPTED_MESSAGE, NO_DETAILS,
                "signed + no customer row");

        Assertions.assertEquals(List.of(killed), tokenRows(order.orderId()),
                "the order's only link is still the SIGNED row D.8 left, with its status and dead_at");
    }

    /**
     * The saved customer email gate, as D.1, on an unsigned invoice with a live link: a DB-valid but malformed
     * email (no interior dot in the domain, two {@code @}, interior whitespace, a trailing-dot-only domain) is
     * 422 {@code CUSTOMER_EMAIL_INVALID}; no customer row (the committed form of "missing": the schema keeps a
     * saved email NOT NULL and non-blank) is 422 {@code CUSTOMER_EMAIL_REQUIRED}. The gate runs before the
     * replace step: the live link is NOT replaced, nothing is written or sent.
     */
    @Test
    void sendLink_missingOrMalformedSavedEmail_returns422RequiredOrInvalid_beforeTheReplace_theLiveLinkStaysActive_writesNothing()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink live = sendLinkOk(order, homeStoreOne.session(), "setup: a live link");
        plantMarkerSentinels(order);

        for (String malformed : List.of("willow@localhost", "willow@lantern@example.com", "wil low@example.com",
                "willow@example.")) {
            setCustomerEmail(order, malformed);
            assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), EMPTY_OBJECT),
                    422, "CUSTOMER_EMAIL_INVALID", CUSTOMER_EMAIL_INVALID_MESSAGE, NO_DETAILS,
                    "a malformed saved email '" + malformed + "'");
        }
        deleteCustomerRow(order);
        assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), EMPTY_OBJECT),
                422, "CUSTOMER_EMAIL_REQUIRED", CUSTOMER_EMAIL_REQUIRED_MESSAGE, NO_DETAILS, "no customer row");

        assertLinkUntouched(live, "after every email-gate refusal");
        assertMarkerSentinelsKept(order, "after every email-gate refusal");
        Assertions.assertEquals(1L, activeLinkCount(order.orderId()), "the live link is still the order's one ACTIVE link");
    }

    /**
     * A NULL, empty or blank saved email - which the schema forbids ({@code email NOT NULL} and
     * {@code chk_order_customer_email_format}) - is 422 {@code CUSTOMER_EMAIL_REQUIRED}, the validator's own
     * null / blank branch. Each case runs inside ONE programmatic transaction that is always rolled back: it
     * relaxes the two constraints, plants the value and sends (the route's transaction joins it on this thread,
     * the refusal happens before any write), so neither the relaxed schema nor the planted value is ever
     * committed. Afterwards the schema, the saved email and the live link are exactly as committed.
     */
    @Test
    void sendLink_nullEmptyOrBlankSavedEmail_onlyReachableWithTheSchemaRelaxed_returns422CustomerEmailRequired_inARolledBackTransaction()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink live = sendLinkOk(order, homeStoreOne.session(), "setup: a live link");

        plantMarkerSentinels(order);

        for (String email : Arrays.asList(null, "", "   ")) {
            String label = "a saved email " + (email == null ? "NULL" : "'" + email + "'") + " (rolled back)";
            withSavedEmailInARolledBackTransaction(order, email, label, () ->
                    assertRefused(sendLinkRequest(SLUG, order.orderId(), homeStoreOne.session(), EMPTY_OBJECT),
                            422, "CUSTOMER_EMAIL_REQUIRED", CUSTOMER_EMAIL_REQUIRED_MESSAGE, NO_DETAILS, label));
        }

        assertMarkerSentinelsKept(order, "after the rolled-back null / blank email refusals");
        Assertions.assertEquals(SAVED_CUSTOMER_EMAIL, jdbcTemplate.queryForObject(
                        "SELECT email FROM order_customer WHERE order_id = ?", String.class, order.orderId()),
                "the committed saved email is the original");
        assertLinkUntouched(live, "after the rolled-back null / blank email refusals");
    }

    // ================================================================
    // D. Success (16G contract §8 steps 7 to 9, §7.1, §7.3, §11)
    // ================================================================

    /**
     * The first send, by LS2 - a staff member who is NOT the order's salesperson (LS1, who also created the
     * invoice): 201 with exactly {@code {"data":{"invoice_link":{"sent_at","expires_at"}},"message"}}, both
     * whole-second timestamps and {@code expires_at = sent_at + 7 days}. Exactly one new {@code invoice_token}
     * row: {@code ACTIVE}, this order, {@code sent_by_user_id} = LS2, {@code sent_at} = the response's second,
     * {@code expires_at = sent_at + 7 days} at full precision, {@code emailed_at} stamped between the send and
     * the response, no {@code dead_at}, {@code token_hash} = SHA-256 of the 43-character emailed token. Exactly
     * one link email: the TRIMMED saved email, the exact subject and body with the link exactly once, the order
     * id and the current version. The response carries no token, hash, link or recipient. Nothing else is
     * written - no invoice version, both email markers keep their committed sentinels, no stored_file or file -
     * and nothing else is sent; the service logs nothing.
     */
    @Test
    void sendLink_success_201ExactShape_oneActiveLinkForTheSessionUser_linkEmailedOnceToTheTrimmedSavedEmail_nothingElseWrittenOrSent()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        Map<String, Object> v1 = invoiceRow(order.orderId(), 1);
        Assertions.assertEquals(ownerUserId, longValue(v1.get("created_by_user_id")), "setup: LS1 created the invoice");
        Assertions.assertEquals(ownerUserId, longValue(jdbcTemplate.queryForObject(
                "SELECT user_id FROM sales_order WHERE order_id = ?", Long.class, order.orderId())),
                "setup: LS1 is the order's salesperson");
        Assertions.assertNotEquals(ownerUserId, senderUserId, "setup: the sender is a different staff member");
        plantMarkerSentinels(order);
        resetSenders();
        WorldState before = world();

        Captured attempt = performCapturing(
                sendLinkRequest(SLUG, order.orderId(), senderStoreOne.session(), EMPTY_OBJECT));

        // ---- The response ----
        LinkTimes times = assertLinkSentResponse(attempt.result(), "send-link");
        String responseBody = body(attempt.result());

        // ---- The one link email ----
        Assertions.assertEquals(List.of(), linkEmailSender.failedEmails(), "no failed link email");
        List<InvoiceLinkEmailRequest> emails = linkEmailSender.sentEmails();
        Assertions.assertEquals(1, emails.size(), "exactly one link email delivered");
        InvoiceLinkEmailRequest email = emails.get(0);
        String token = assertLinkEmail(email, order, 1, "the link email");
        assertNothingSecretLoggedAtInfoOrAbove(attempt, token, "a successful send");
        Assertions.assertEquals(32, Base64.getUrlDecoder().decode(token).length,
                "the token is 32 bytes as unpadded URL-safe Base64 (43 characters)");
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), "the recipient is the TRIMMED saved email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), "the subject");
        Assertions.assertEquals("Hi,\n\nYour invoice " + order.orderNumber() + " from " + BUSINESS_NAME
                        + " is ready to review and sign. You can view and sign your invoice online here: "
                        + PUBLIC_BASE_URL + "/i/" + token + "\n\nThank you.",
                email.bodyText(), "the exact link-only body");
        Assertions.assertEquals(order.orderId(), email.orderId(), "the email's order id");
        Assertions.assertEquals(1, email.invoiceVersionNumber(), "the email's version = the current version (v1)");

        // ---- The one token row ----
        List<Map<String, Object>> rows = tokenRows(order.orderId());
        Assertions.assertEquals(1, rows.size(), "exactly one invoice_token row");
        Map<String, Object> row = rows.get(0);
        Assertions.assertEquals(sha256Hex(token), row.get("token_hash"), "token_hash = SHA-256 hex of the emailed token");
        Assertions.assertEquals(64, ((String) row.get("token_hash")).length(), "a 64-character lowercase hex hash");
        Assertions.assertEquals("ACTIVE", row.get("status"), "the new link is ACTIVE");
        Assertions.assertEquals(order.orderId(), longValue(row.get("order_id")), "the link belongs to the order");
        Assertions.assertEquals(senderUserId, longValue(row.get("sent_by_user_id")),
                "sent_by_user_id is the SESSION user (LS2), not the order's salesperson or the invoice's creator");
        LocalDateTime sentAt = localDateTime(row.get("sent_at"));
        LocalDateTime expiresAt = localDateTime(row.get("expires_at"));
        Assertions.assertEquals(sentAt.plusDays(7), expiresAt, "expires_at = sent_at + 7 days, at full precision");
        assertSameWholeSecond(times.sentAt(), sentAt, "the response's sent_at vs the stored sent_at");
        assertSameWholeSecond(times.expiresAt(), expiresAt, "the response's expires_at vs the stored expires_at");
        Assertions.assertFalse(sentAt.isBefore(attempt.startedAt().minus(1, ChronoUnit.MILLIS))
                        || sentAt.isAfter(attempt.finishedAt()),
                "sent_at " + sentAt + " is the send's own time (" + attempt.startedAt() + " .. " + attempt.finishedAt() + ")");
        Assertions.assertNotNull(row.get("emailed_at"), "the delivered link's emailed_at is stamped");
        LocalDateTime emailedAt = localDateTime(row.get("emailed_at"));
        Assertions.assertFalse(emailedAt.isBefore(sentAt) || emailedAt.isAfter(attempt.finishedAt()),
                "emailed_at " + emailedAt + " is stamped after the send (" + sentAt + ") and before the response ("
                        + attempt.finishedAt() + ")");
        Assertions.assertNull(row.get("dead_at"), "a live link has no dead_at");
        Assertions.assertNotNull(row.get("created_at"), "created_at defaulted");

        // ---- The response never carries the secret, its hash, the link or the recipient ----
        for (String leak : List.of(token, sha256Hex(token), "/i/", PUBLIC_BASE_URL, CUSTOMER_EMAIL, "@", "token",
                "hash", "recipient", "storage")) {
            Assertions.assertFalse(responseBody.contains(leak), "the 201 body must not contain '" + leak + "': "
                    + responseBody);
        }

        // ---- Nothing else written: no invoice version, both markers keep their sentinels, no file ----
        WorldState after = world();
        assertSameStateExceptLinks(before, after, "after a successful send");
        Assertions.assertEquals(List.of(), before.invoiceTokens(), "setup: no link before the send");
        Assertions.assertEquals(List.of(row), after.invoiceTokens(), "the send's only write is its one token row");
        assertMarkerSentinelsKept(order, "a successful link send");
        Assertions.assertEquals(List.of(1), invoiceVersions(order.orderId()), "no invoice version appended");

        // ---- Nothing else sent; nothing logged ----
        assertNoOtherMessages("a successful link send");
        Assertions.assertEquals(List.of(), describe(attempt.serviceLog()), "a delivered and stamped send logs nothing");

        // ---- The staff read now reports this link's expiry ----
        Assertions.assertEquals(JSON_TIMESTAMP.format(expiresAt),
                getCurrentInvoice(order).path("invoice_link_expires_at").asText(),
                "D.3's invoice_link_expires_at is the new ACTIVE link's expires_at, in whole seconds");
    }

    // ================================================================
    // E. Replacement (16G contract §7.5, §8 step 7) and the public view of the replaced link
    // ================================================================

    /**
     * A second send while a link is live: the first link becomes {@code REPLACED} with {@code dead_at} equal to
     * the second send's {@code sent_at} at full precision (every other column, its {@code emailed_at} included,
     * unchanged); the second is the order's only {@code ACTIVE} link; the two plaintext tokens and hashes
     * differ. On the token-only public surface (no session, no cookie) the old link reads 200 {@code REPLACED}
     * with the minimal 27-key payload, and its accept (with an otherwise valid body) and pdf are 410
     * {@code INVOICE_LINK_REPLACED}; the new link reads {@code ACTIVE}. The public calls write and send nothing.
     */
    @Test
    void sendLink_secondSend_replacesTheFirstLinkAtTheSecondSendsSentAt_onlyTheNewLinkIsLive_publicSurfaceAnswersReplacedForTheOldLink()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink first = sendLinkOk(order, homeStoreOne.session(), "the first send");
        SentLink second = sendLinkOk(order, senderStoreOne.session(), "the second send");

        Assertions.assertNotEquals(first.token(), second.token(), "a resend mints a new token");
        Assertions.assertNotEquals(first.hash(), second.hash(), "a resend stores a new hash");
        Map<String, Object> secondNow = tokenRowByHash(second.hash());
        Assertions.assertEquals(
                withColumns(first.row(), "status", "REPLACED", "dead_at", secondNow.get("sent_at")),
                tokenRowByHash(first.hash()),
                "the first link was REPLACED at exactly the second send's sent_at; nothing else of it changed");
        Assertions.assertNotNull(first.row().get("emailed_at"), "setup: the first link had been delivered");
        Assertions.assertEquals(second.row(), secondNow, "the second link is live, as minted and stamped");
        Assertions.assertEquals(1L, activeLinkCount(order.orderId()), "exactly one ACTIVE link");
        Assertions.assertEquals(2, tokenRows(order.orderId()).size(), "two rows: the dead one is kept");

        // ---- The public surface ----
        resetSenders();
        WorldState before = world();
        JsonNode oldView = json(publicGet(first.token(), 200, "public GET of the replaced link"));
        assertDeadView(oldView, "REPLACED", LINK_REPLACED_MESSAGE, "public GET of the replaced link");
        assertError(publicAccept(first.token(), 1, 410, "public accept of the replaced link"),
                410, "INVOICE_LINK_REPLACED", LINK_REPLACED_MESSAGE, NO_DETAILS, "public accept of the replaced link");
        assertError(publicPdf(first.token(), 1, 410, "public pdf of the replaced link"),
                410, "INVOICE_LINK_REPLACED", LINK_REPLACED_MESSAGE, NO_DETAILS, "public pdf of the replaced link");
        JsonNode newView = json(publicGet(second.token(), 200, "public GET of the new link"));
        assertActiveView(newView, order, 1, secondNow, "public GET of the new link");
        assertSameState(before, world(), "the public reads and the refused accept / pdf");
        assertNoMessages("the public reads and the refused accept / pdf");
    }

    // ================================================================
    // F. Delivery failure (16G contract §8 "Delivery failure")
    // ================================================================

    /**
     * The link email's transport fails ({@code failNextSend}): 502 {@code EMAIL_SEND_FAILED} with the route's
     * message. The send had committed: the new link stays {@code ACTIVE} with no {@code emailed_at}, the
     * previous delivered link stays {@code REPLACED} (never restored); exactly one WARN on the
     * OrderInvoiceService logger, without a throwable, a recipient, the token or the link. A later successful
     * retry replaces the undelivered link and delivers a new one.
     */
    @Test
    void sendLink_transportFailure_returns502EmailSendFailed_newLinkStaysActiveUndelivered_previousStaysReplaced_oneWarn_aRetryReplacesIt()
            throws Exception {
        assertUndeliveredSendThenRetry(RecordingInvoiceLinkEmailSender::failNextSend, null, "a transport failure");
    }

    /**
     * The sender throws a DIFFERENT runtime exception ({@link IllegalStateException}, not the transport's
     * exception): exactly the same 502, the same committed state, the same single WARN (which never carries the
     * exception's text) and the same retry.
     */
    @Test
    void sendLink_senderRuntimeFailure_returns502EmailSendFailed_sameStateAsATransportFailure_oneWarn_aRetryReplacesIt()
            throws Exception {
        String failureText = "injected non-transport failure inside the invoice link sender";
        assertUndeliveredSendThenRetry(sender -> sender.failNextSendWith(new IllegalStateException(failureText)),
                failureText, "a sender runtime failure");
    }

    /**
     * The order's FIRST link (it never had one) and its delivery fails: 502 {@code EMAIL_SEND_FAILED} with the
     * route's message; the send had committed, so the one new link stays {@code ACTIVE} and undelivered (there
     * is no earlier row to replace or restore); one WARN; nothing else written or sent. A retry replaces it and
     * delivers.
     */
    @Test
    void sendLink_firstEverSendDeliveryFails_returns502_theOnlyLinkStaysActiveUndelivered_oneWarn_aRetryReplacesIt()
            throws Exception {
        String label = "a first send whose delivery fails";
        SeededOrder order = invoicedOrder(homeStoreOne);
        plantMarkerSentinels(order);
        resetSenders();
        Assertions.assertEquals(List.of(), tokenRows(order.orderId()), label + ": setup: the order never had a link");
        WorldState before = world();
        linkEmailSender.failNextSend();

        Captured attempt = performCapturing(
                sendLinkRequest(SLUG, order.orderId(), senderStoreOne.session(), EMPTY_OBJECT));

        assertError(attempt.result(), 502, "EMAIL_SEND_FAILED", LINK_EMAIL_FAILED_MESSAGE, NO_DETAILS, label);
        Assertions.assertEquals(List.of(), linkEmailSender.sentEmails(), label + ": nothing delivered");
        List<InvoiceLinkEmailRequest> failed = linkEmailSender.failedEmails();
        Assertions.assertEquals(1, failed.size(), label + ": exactly one failed attempt");
        String undeliveredToken = assertLinkEmail(failed.get(0), order, 1, label + ": the failed attempt");
        assertNothingSecretLoggedAtInfoOrAbove(attempt, undeliveredToken, label);

        Assertions.assertEquals(1, tokenRows(order.orderId()).size(), label + ": exactly the one new link row");
        Map<String, Object> undelivered = tokenRowByHash(sha256Hex(undeliveredToken));
        Assertions.assertEquals("ACTIVE", undelivered.get("status"), label + ": the only link stays ACTIVE");
        Assertions.assertNull(undelivered.get("emailed_at"), label + ": it has no emailed_at");
        Assertions.assertNull(undelivered.get("dead_at"), label + ": it is not dead");
        Assertions.assertEquals(senderUserId, longValue(undelivered.get("sent_by_user_id")), label + ": its sender");
        Assertions.assertEquals(localDateTime(undelivered.get("sent_at")).plusDays(7),
                localDateTime(undelivered.get("expires_at")), label + ": expires_at = sent_at + 7 days");
        assertSameStateExceptLinks(before, world(), label);
        assertMarkerSentinelsKept(order, label);
        assertNoOtherMessages(label);
        assertSingleWarn(attempt.serviceLog(), String.format(DELIVERY_FAILED_WARN, order.orderId(), 1),
                List.of(undeliveredToken, sha256Hex(undeliveredToken), CUSTOMER_EMAIL), label);

        resetSenders();
        SentLink retry = sendLinkOk(order, senderStoreOne.session(), label + ": the retry");
        Assertions.assertNotEquals(undeliveredToken, retry.token(), label + ": the retry mints a new token");
        Assertions.assertEquals(
                withColumns(undelivered, "status", "REPLACED", "dead_at", retry.row().get("sent_at")),
                tokenRowByHash(sha256Hex(undeliveredToken)),
                label + ": the retry REPLACED the undelivered link at its own sent_at");
        Assertions.assertEquals(2, tokenRows(order.orderId()).size(), label + ": two rows, none deleted");
        Assertions.assertEquals(1L, activeLinkCount(order.orderId()), label + ": the retry's link is the only live one");
        assertMarkerSentinelsKept(order, label + ": after the retry");
        assertNoOtherMessages(label + ": the retry");
    }

    private void assertUndeliveredSendThenRetry(Consumer<RecordingInvoiceLinkEmailSender> armFailure, String failureText,
                                                String label) throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink previous = sendLinkOk(order, homeStoreOne.session(), label + ": setup: a delivered link");
        plantMarkerSentinels(order);
        resetSenders();
        WorldState before = world();
        armFailure.accept(linkEmailSender);

        Captured attempt = performCapturing(
                sendLinkRequest(SLUG, order.orderId(), senderStoreOne.session(), EMPTY_OBJECT));

        assertError(attempt.result(), 502, "EMAIL_SEND_FAILED", LINK_EMAIL_FAILED_MESSAGE, NO_DETAILS, label);
        // The one attempt carried the NEW link; nothing was delivered.
        Assertions.assertEquals(List.of(), linkEmailSender.sentEmails(), label + ": nothing delivered");
        List<InvoiceLinkEmailRequest> failed = linkEmailSender.failedEmails();
        Assertions.assertEquals(1, failed.size(), label + ": exactly one failed attempt");
        String undeliveredToken = assertLinkEmail(failed.get(0), order, 1, label + ": the failed attempt");
        Assertions.assertNotEquals(previous.token(), undeliveredToken, label + ": the attempt carried a new token");
        assertNothingSecretLoggedAtInfoOrAbove(attempt, undeliveredToken, label);

        // The send committed before its delivery: the new link stays ACTIVE (undelivered), the old stays REPLACED.
        Assertions.assertEquals(2, tokenRows(order.orderId()).size(), label + ": one new row");
        Map<String, Object> undelivered = tokenRowByHash(sha256Hex(undeliveredToken));
        Assertions.assertEquals("ACTIVE", undelivered.get("status"), label + ": the new link stays ACTIVE");
        Assertions.assertNull(undelivered.get("emailed_at"), label + ": the undelivered link has no emailed_at");
        Assertions.assertNull(undelivered.get("dead_at"), label + ": the undelivered link is not dead");
        Assertions.assertEquals(order.orderId(), longValue(undelivered.get("order_id")), label + ": its order");
        Assertions.assertEquals(senderUserId, longValue(undelivered.get("sent_by_user_id")), label + ": its sender");
        Assertions.assertEquals(localDateTime(undelivered.get("sent_at")).plusDays(7),
                localDateTime(undelivered.get("expires_at")), label + ": expires_at = sent_at + 7 days");
        Assertions.assertEquals(
                withColumns(previous.row(), "status", "REPLACED", "dead_at", undelivered.get("sent_at")),
                tokenRowByHash(previous.hash()),
                label + ": the previous link stays REPLACED at the failed send's sent_at (never restored)");
        Assertions.assertEquals(1L, activeLinkCount(order.orderId()), label + ": exactly one ACTIVE link");

        // Nothing else was written or sent; both email markers keep their committed sentinels.
        assertSameStateExceptLinks(before, world(), label);
        assertMarkerSentinelsKept(order, label);
        assertNoOtherMessages(label);

        // One WARN, order id + version only.
        List<String> secrets = new ArrayList<>(List.of(undeliveredToken, sha256Hex(undeliveredToken), CUSTOMER_EMAIL));
        if (failureText != null) {
            secrets.add(failureText);
        }
        assertSingleWarn(attempt.serviceLog(), String.format(DELIVERY_FAILED_WARN, order.orderId(), 1), secrets, label);
        assertRootCaptureSaw(attempt, String.format(DELIVERY_FAILED_WARN, order.orderId(), 1), label);

        // The staff read reports the undelivered ACTIVE link (the Invoice tab polls on it).
        Assertions.assertEquals(JSON_TIMESTAMP.format(localDateTime(undelivered.get("expires_at"))),
                getCurrentInvoice(order).path("invoice_link_expires_at").asText(),
                label + ": D.3 reports the undelivered, still ACTIVE link's expiry");

        // A retry is another send: it replaces the undelivered link and delivers a new one.
        resetSenders();
        SentLink retry = sendLinkOk(order, senderStoreOne.session(), label + ": the retry");
        Assertions.assertNotEquals(undeliveredToken, retry.token(), label + ": the retry mints a new token");
        Assertions.assertEquals(
                withColumns(undelivered, "status", "REPLACED", "dead_at", retry.row().get("sent_at")),
                tokenRowByHash(sha256Hex(undeliveredToken)),
                label + ": the retry REPLACED the undelivered link at its own sent_at");
        Assertions.assertEquals(
                withColumns(previous.row(), "status", "REPLACED", "dead_at", undelivered.get("sent_at")),
                tokenRowByHash(previous.hash()), label + ": a dead link never changes again");
        Assertions.assertEquals(3, tokenRows(order.orderId()).size(), label + ": three rows, none deleted");
        Assertions.assertEquals(1L, activeLinkCount(order.orderId()), label + ": the retry's link is the only live one");
        assertMarkerSentinelsKept(order, label + ": after the retry");
        assertNoOtherMessages(label + ": the retry");
    }

    // ================================================================
    // G. The emailed_at stamp failing after delivery (16G contract §8 step 9)
    // ================================================================

    /**
     * The delivery succeeds and the stamp's UPDATE fails at once (a NON-deferred trigger on
     * {@code invoice_token} raising when this order's token gets its first {@code emailed_at}): 201 with the
     * normal body; the delivered link stays {@code ACTIVE} with no {@code emailed_at}; the email is recorded as
     * sent; exactly one stamp WARN without a throwable. The trigger fired exactly once (the stamp, never the
     * kill of the previous link).
     */
    @Test
    void sendLink_stampFailsAtItsUpdate_nonDeferredTrigger_returns201NormalBody_deliveredLinkStaysActiveUnstamped_oneStampWarn()
            throws Exception {
        assertStampFailureIsNonFatal(false);
    }

    /**
     * As above, but the stamp fails AT COMMIT (a {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger on the
     * same condition, so its UPDATE succeeded in flight): the same 201, the same unstamped {@code ACTIVE} link
     * and the same single WARN.
     */
    @Test
    void sendLink_stampFailsAtCommit_deferredConstraintTrigger_returns201NormalBody_deliveredLinkStaysActiveUnstamped_oneStampWarn()
            throws Exception {
        assertStampFailureIsNonFatal(true);
    }

    private void assertStampFailureIsNonFatal(boolean atCommit) throws Exception {
        String label = atCommit ? "the stamp failing at commit" : "the stamp failing at its UPDATE";
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink previous = sendLinkOk(order, homeStoreOne.session(), label + ": setup: a delivered, stamped link");
        String trigger = TRIGGER_PREFIX + (atCommit ? "stamp_at_commit_" : "stamp_at_update_") + order.orderId();
        plantMarkerSentinels(order);
        resetSenders();
        WorldState before = world();
        Captured attempt;
        long triggerFires;
        try {
            installStampFailureTrigger(trigger, order.orderId(), atCommit);
            Assertions.assertEquals(1, count(
                            "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice_token'::regclass AND tgname = ?",
                            trigger),
                    label + ": the stamp-failure trigger must be installed before the request");
            Assertions.assertEquals(0L, tokenWriteTriggerFires(), label + ": the trigger has not fired yet");
            attempt = performCapturing(sendLinkRequest(SLUG, order.orderId(), senderStoreOne.session(), EMPTY_OBJECT));
            triggerFires = tokenWriteTriggerFires();
        } finally {
            dropTokenWriteFailureArtifacts();
        }

        // 201 with the normal body: the link WAS delivered.
        LinkTimes times = assertLinkSentResponse(attempt.result(), label);
        Assertions.assertEquals(1L, triggerFires,
                label + ": the trigger fired exactly once - on this send's emailed_at stamp, never on the REPLACED kill");
        Assertions.assertEquals(List.of(), linkEmailSender.failedEmails(), label + ": no failed attempt");
        List<InvoiceLinkEmailRequest> sent = linkEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), label + ": the link email was delivered once");
        String token = assertLinkEmail(sent.get(0), order, 1, label + ": the delivered email");
        assertNothingSecretLoggedAtInfoOrAbove(attempt, token, label);

        Map<String, Object> delivered = tokenRowByHash(sha256Hex(token));
        Assertions.assertEquals("ACTIVE", delivered.get("status"), label + ": the delivered link stays ACTIVE");
        Assertions.assertNull(delivered.get("emailed_at"), label + ": the stamp rolled back: no emailed_at");
        Assertions.assertNull(delivered.get("dead_at"), label + ": the delivered link is live");
        Assertions.assertEquals(senderUserId, longValue(delivered.get("sent_by_user_id")), label + ": its sender");
        assertSameWholeSecond(times.sentAt(), localDateTime(delivered.get("sent_at")), label + ": sent_at");
        Assertions.assertEquals(
                withColumns(previous.row(), "status", "REPLACED", "dead_at", delivered.get("sent_at")),
                tokenRowByHash(previous.hash()),
                label + ": the previous link was REPLACED as usual (its emailed_at kept)");
        Assertions.assertEquals(2, tokenRows(order.orderId()).size(), label + ": one new row");
        Assertions.assertEquals(1L, activeLinkCount(order.orderId()), label + ": exactly one ACTIVE link");
        assertSameStateExceptLinks(before, world(), label);
        assertMarkerSentinelsKept(order, label);
        assertNoOtherMessages(label);
        assertSingleWarn(attempt.serviceLog(), String.format(STAMP_FAILED_WARN, order.orderId(), 1),
                List.of(token, sha256Hex(token), CUSTOMER_EMAIL, "ilsnd"), label);
        assertRootCaptureSaw(attempt, String.format(STAMP_FAILED_WARN, order.orderId(), 1), label);

        // With the trigger gone, the next send stamps as usual (the failure was the injected one).
        SentLink next = sendLinkOk(order, senderStoreOne.session(), label + ": a send after the trigger was dropped");
        Assertions.assertNotNull(next.row().get("emailed_at"), label + ": the next send is stamped");
    }

    /**
     * The send's replace and mint are ONE transaction (16G contract §7.5; §8's "500 (transactional rollback)"):
     * the mint INSERT fails (a {@code BEFORE INSERT} trigger on {@code invoice_token} for this order) after the
     * same transaction had already marked the previous live link {@code REPLACED} - the trigger records that
     * the INSERT saw NO active row in flight. The generic 500 (no data, no details, no database text); the
     * whole transaction rolled back, so the previous link is still {@code ACTIVE} with no {@code dead_at}, no
     * row was added and nothing else was written; no email of any kind was attempted (delivery only follows a
     * commit) and the OrderInvoiceService logged nothing. With the trigger gone, the next send replaces it.
     */
    @Test
    void sendLink_mintFailsInsideTheSendTransaction_returns500Generic_theInFlightReplaceRollsBack_previousLinkStaysActive_nothingSent()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink live = sendLinkOk(order, homeStoreOne.session(), "setup: the live link the failed send must not kill");
        plantMarkerSentinels(order);
        String trigger = TRIGGER_PREFIX + "mint_" + order.orderId();
        resetSenders();
        WorldState before = world();
        Captured attempt;
        long triggerFires;
        long activeRowsSeenByTheMint;
        try {
            installMintFailureTrigger(trigger, order.orderId());
            Assertions.assertEquals(1, count(
                            "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice_token'::regclass AND tgname = ?",
                            trigger),
                    "the mint-failure trigger must be installed before the request");
            attempt = performCapturing(sendLinkRequest(SLUG, order.orderId(), senderStoreOne.session(), EMPTY_OBJECT));
            triggerFires = tokenWriteTriggerFires();
            activeRowsSeenByTheMint = mintActiveRowsSeenInFlight();
        } finally {
            dropTokenWriteFailureArtifacts();
        }

        assertError(attempt.result(), 500, "INTERNAL_SERVER_ERROR", GENERIC_500_MESSAGE, NO_DETAILS, "a failing mint");
        String responseBody = body(attempt.result());
        for (String leak : List.of("ilsnd", "P0001", "trigger", "invoice_token", "SQL", "REPLACED")) {
            Assertions.assertFalse(responseBody.contains(leak),
                    "the 500 body must not leak database detail '" + leak + "': " + responseBody);
        }
        Assertions.assertEquals(1L, triggerFires, "the mint INSERT was attempted exactly once");
        Assertions.assertEquals(0L, activeRowsSeenByTheMint,
                "in flight, the send had already marked the previous link REPLACED before its mint INSERT");
        assertSameState(before, world(), "a send whose mint failed (the in-flight REPLACED kill rolled back)");
        assertLinkUntouched(live, "the previous link is still exactly as it was");
        Assertions.assertEquals(1L, activeLinkCount(order.orderId()), "the previous link is the order's one ACTIVE link");
        assertMarkerSentinelsKept(order, "a send whose mint failed");
        assertNoMessages("a send whose mint failed");
        Assertions.assertEquals(List.of(), describe(attempt.serviceLog()),
                "a rolled-back send logs nothing on the OrderInvoiceService logger");

        SentLink next = sendLinkOk(order, senderStoreOne.session(), "a send after the trigger was dropped");
        Assertions.assertEquals(
                withColumns(live.row(), "status", "REPLACED", "dead_at", next.row().get("sent_at")),
                tokenRowByHash(live.hash()), "the next send replaced the previous link as usual");
    }

    // ================================================================
    // H. LAID and CANCELLED (16G contract §12)
    // ================================================================

    /**
     * Send link is allowed on a LAID order and on a CANCELLED order (there is no CANCELLED rule): the status is
     * changed through the REAL dashboard status endpoint while a link is live - which never kills it - and the
     * send then returns 201, replacing that link; the new link reads {@code ACTIVE} publicly.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"LAID", "CANCELLED"})
    void sendLink_onALaidOrCancelledOrder_viaTheRealStatusEndpoint_isAllowed_201_andTheStatusChangeNeverKilledTheLiveLink(
            String status) throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne);
        SentLink live = sendLinkOk(order, homeStoreOne.session(), "setup: a live link before the status change");

        MvcResult changed = perform(patch(orderUrl(order) + "/status").session(homeStoreOne.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"order_status\":\"" + status + "\"}"),
                200, "the status change to " + status);
        JsonNode changedRoot = json(changed);
        Assertions.assertEquals(STATUS_UPDATED_MESSAGE, changedRoot.path("message").asText(), "status message");
        Assertions.assertEquals(status, changedRoot.path("data").path("order_status").asText(), "the new status");
        Assertions.assertEquals(status, jdbcTemplate.queryForObject(
                "SELECT order_status::text FROM sales_order WHERE order_id = ?", String.class, order.orderId()),
                "the committed status");
        assertLinkUntouched(live, "a status change to " + status + " never kills an invoice link");

        SentLink next = sendLinkOk(order, senderStoreOne.session(), "send-link on a " + status + " order");
        Assertions.assertEquals(
                withColumns(live.row(), "status", "REPLACED", "dead_at", next.row().get("sent_at")),
                tokenRowByHash(live.hash()), "the send on a " + status + " order replaced the live link");
        JsonNode view = json(publicGet(next.token(), 200, "public GET of the new link on a " + status + " order"));
        assertActiveView(view, order, 1, tokenRowByHash(next.hash()), "the new link on a " + status + " order");
    }

    // ================================================================
    // I. InvoiceDetail.invoice_link_expires_at (16G contract §4.5, §7.7)
    // ================================================================

    /**
     * The derived {@code invoice_link_expires_at}: D.1's InvoiceDetail and D.3 before any send carry it present
     * and null; after a send D.3 gives that link's {@code expires_at} in whole seconds; after a second send the
     * NEW link's; a payment and a void (E.1 {@code current_invoice}, which never carries the key) leave the link
     * ACTIVE and untouched, D.3 still reporting it for the new versions; a send after them emails the current
     * version number; once the ACTIVE link is over-age (its {@code expires_at} backdated) D.3 gives null - and
     * that staff read writes nothing: the token stays {@code ACTIVE} with no {@code dead_at}.
     */
    @Test
    void invoiceDetail_invoiceLinkExpiresAt_followsTheActiveUnexpiredLink_survivesPaymentAndVoid_nullOnceOverAge_aStaffReadNeverExpiresTheLink()
            throws Exception {
        SeededOrder order = invoicedOrder(homeStoreOne); // D.1's InvoiceDetail: present and null (asserted there)
        assertPresentAndNull(getCurrentInvoice(order), "invoice_link_expires_at", "D.3 before any send");

        SentLink first = sendLinkOk(order, homeStoreOne.session(), "the first send");
        Assertions.assertEquals(wholeSeconds(first.row().get("expires_at")),
                getCurrentInvoice(order).path("invoice_link_expires_at").asText(), "D.3 after the first send");
        // Make the first link's expiry distinctive (still in the future), so the next comparison discriminates.
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice_token SET expires_at = expires_at - INTERVAL '2 days' WHERE token_hash = ?",
                        first.hash()), "setup: shift the first link's expiry");
        Map<String, Object> firstShifted = tokenRowByHash(first.hash());
        Assertions.assertEquals(wholeSeconds(firstShifted.get("expires_at")),
                getCurrentInvoice(order).path("invoice_link_expires_at").asText(),
                "D.3 reads the ACTIVE link's stored expires_at");

        SentLink second = sendLinkOk(order, senderStoreOne.session(), "the second send");
        String secondExpiry = wholeSeconds(second.row().get("expires_at"));
        Assertions.assertNotEquals(wholeSeconds(firstShifted.get("expires_at")), secondExpiry,
                "setup: the two links' expiries differ");
        Assertions.assertEquals(secondExpiry, getCurrentInvoice(order).path("invoice_link_expires_at").asText(),
                "D.3 after the second send reports the NEW link's expiry");

        // A payment (D.7): E.1 never carries the field; the link survives untouched; D.3 (v2) still reports it.
        MvcResult paid = perform(recordPayment(order, "50.00"), 201, "D.7 payment");
        JsonNode paidRoot = json(paid);
        Assertions.assertEquals(PAYMENT_RECORDED_MESSAGE, paidRoot.path("message").asText(), "payment message");
        assertE1WithoutLinkField(paidRoot, 2, "the payment's current_invoice");
        assertLinkUntouched(second, "a payment never touches the invoice link");
        JsonNode afterPayment = getCurrentInvoice(order);
        Assertions.assertEquals(2, afterPayment.path("version_number").asInt(), "D.3 shows the payment's v2");
        Assertions.assertEquals(secondExpiry, afterPayment.path("invoice_link_expires_at").asText(),
                "D.3 of v2 still reports the order's live link");

        // A void (D.10): the same.
        long paymentId = paidRoot.path("data").path("payment_transaction").path("payment_transaction_id").asLong();
        Assertions.assertTrue(paymentId > 0, "setup: the payment id");
        MvcResult voided = perform(post(orderUrl(order) + "/payments/" + paymentId + "/void")
                .session(homeStoreOne.session()), 201, "D.10 void");
        JsonNode voidedRoot = json(voided);
        Assertions.assertEquals(PAYMENT_VOIDED_MESSAGE, voidedRoot.path("message").asText(), "void message");
        assertE1WithoutLinkField(voidedRoot, 3, "the void's current_invoice");
        assertLinkUntouched(second, "a void never touches the invoice link");
        JsonNode afterVoid = getCurrentInvoice(order);
        Assertions.assertEquals(3, afterVoid.path("version_number").asInt(), "D.3 shows the void's v3");
        Assertions.assertEquals(secondExpiry, afterVoid.path("invoice_link_expires_at").asText(),
                "D.3 of v3 still reports the order's live link");

        // A send now emails the CURRENT version number (v3) and moves the field to its own link.
        SentLink third = sendLinkOk(order, senderStoreOne.session(), "the send after the payment and the void");
        Assertions.assertEquals(3, third.email().invoiceVersionNumber(), "the link email carries the current version v3");
        Assertions.assertEquals(wholeSeconds(third.row().get("expires_at")),
                getCurrentInvoice(order).path("invoice_link_expires_at").asText(), "D.3 after the third send");

        // Over-age but still ACTIVE: null, and the read writes nothing (no expiry flip on a staff read).
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice_token SET expires_at = ? WHERE token_hash = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), third.hash()), "setup: backdate the live link");
        Map<String, Object> overAge = tokenRowByHash(third.hash());
        Assertions.assertEquals("ACTIVE", overAge.get("status"), "setup: the over-age link is still ACTIVE");
        resetSenders();
        WorldState before = world();
        assertPresentAndNull(getCurrentInvoice(order), "invoice_link_expires_at", "D.3 of an over-age ACTIVE link");
        assertSameState(before, world(), "the staff read of an over-age link");
        Map<String, Object> afterRead = tokenRowByHash(third.hash());
        Assertions.assertEquals(overAge, afterRead, "the staff read never expires the link");
        Assertions.assertEquals("ACTIVE", afterRead.get("status"), "the over-age link stays ACTIVE");
        Assertions.assertNull(afterRead.get("dead_at"), "the over-age link has no dead_at");
        assertNoMessages("the staff read of an over-age link");
    }

    // ================================================================
    // Requests
    // ================================================================

    /** The send-link request: the given slug, raw order id, session (none when null) and body form. */
    private static MockHttpServletRequestBuilder sendLinkRequest(String slug, Object orderId, MockHttpSession session,
                                                                Body body) {
        MockHttpServletRequestBuilder request =
                post("/api/v1/" + slug + "/orders/" + orderId + "/invoices/current/send-link");
        if (session != null) {
            request.session(session);
        }
        return withBody(request, body);
    }

    /** Add a body form to a JSON request: no body at all, or the content as {@code application/json}. */
    private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, Body body) {
        if (body.content() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body.content());
        }
        return request;
    }

    /** D.8 in store (16G PR 3): the mandatory {@code expected_version_number} and one PNG signature part. */
    private RequestBuilder d8Accept(SeededOrder order, int expectedVersion) {
        return multipart(orderUrl(order) + "/invoices/current/accept")
                .file(new MockMultipartFile("signature", "signature.png", "image/png", SIGNATURE_PNG))
                .param("expected_version_number", Integer.toString(expectedVersion))
                .session(order.scope().session());
    }

    /** D.7: a CASH payment within the balance. */
    private static RequestBuilder recordPayment(SeededOrder order, String amount) {
        return post(orderUrl(order) + "/payments").session(order.scope().session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\":\"CASH\",\"amount\":" + amount + ",\"payment_reference\":null}");
    }

    private MvcResult perform(RequestBuilder request, int expectedStatus, String label) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        assertStatus(result, expectedStatus, label);
        return result;
    }

    /**
     * Perform a request with the OrderInvoiceService logger, and the root logger (every additive logger's events
     * at their effective levels), captured for exactly its duration.
     */
    private Captured performCapturing(RequestBuilder request) throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OrderInvoiceService.class);
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        ListAppender<ILoggingEvent> rootAppender = new ListAppender<>();
        appender.start();
        rootAppender.start();
        logger.addAppender(appender);
        root.addAppender(rootAppender);
        LocalDateTime startedAt = LocalDateTime.now();
        MvcResult result;
        try {
            result = mockMvc.perform(request).andReturn();
        } finally {
            logger.detachAppender(appender);
            root.detachAppender(rootAppender);
            appender.stop();
            rootAppender.stop();
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        return new Captured(result, List.copyOf(appender.list), List.copyOf(rootAppender.list), startedAt, finishedAt);
    }

    /**
     * 16G contract §7.3: the plaintext token (and so the link) is never logged at INFO or above - nor its hash or
     * the recipient. Checks every captured event at INFO+ from any logger, its message and its throwable chain.
     */
    private static void assertNothingSecretLoggedAtInfoOrAbove(Captured attempt, String token, String label) {
        List<String> secrets = List.of(token, sha256Hex(token), "/i/" + token, CUSTOMER_EMAIL);
        for (ILoggingEvent event : attempt.allLog()) {
            if (!event.getLevel().isGreaterOrEqual(Level.INFO)) {
                continue;
            }
            StringBuilder text = new StringBuilder(event.getFormattedMessage());
            for (ch.qos.logback.classic.spi.IThrowableProxy proxy = event.getThrowableProxy(); proxy != null;
                 proxy = proxy.getCause()) {
                text.append(' ').append(proxy.getMessage());
            }
            for (String secret : secrets) {
                Assertions.assertFalse(text.toString().contains(secret), label + ": a " + event.getLevel()
                        + " log line of " + event.getLoggerName() + " carries a secret: " + text);
            }
        }
    }

    // ---- The token-only public surface: every request goes through performPublic ----

    private MvcResult publicGet(String token, int expectedStatus, String label) throws Exception {
        return performPublic(get("/api/v1/public/invoices/" + token), expectedStatus, label);
    }

    /** An otherwise VALID public accept (the expected version form field + one PNG signature part). */
    private MvcResult publicAccept(String token, int expectedVersion, int expectedStatus, String label) throws Exception {
        return performPublic(multipart("/api/v1/public/invoices/" + token + "/accept")
                        .file(new MockMultipartFile("signature", "signature.png", "image/png", SIGNATURE_PNG))
                        .param("expected_version_number", Integer.toString(expectedVersion)),
                expectedStatus, label);
    }

    private MvcResult publicPdf(String token, int versionNumber, int expectedStatus, String label) throws Exception {
        return performPublic(get("/api/v1/public/invoices/" + token + "/pdf?version_number=" + versionNumber),
                expectedStatus, label);
    }

    /**
     * The ONE way this class sends a public invoice request: token-only, never with a session. Whatever the
     * status, the request must neither carry nor create a session, and the response must set no cookie.
     */
    private MvcResult performPublic(RequestBuilder request, int expectedStatus, String label) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        assertStatus(result, expectedStatus, label);
        Assertions.assertTrue(result.getRequest().getRequestURI().startsWith("/api/v1/public/invoices/"),
                label + ": a public invoice route");
        Assertions.assertNull(result.getRequest().getSession(false),
                label + ": a public invoice request never carries or creates a session");
        Assertions.assertEquals(List.of(), result.getResponse().getHeaders("Set-Cookie"),
                label + ": a public invoice response sets no cookie");
        Assertions.assertEquals(0, result.getResponse().getCookies().length, label + ": no cookie at all");
        return result;
    }

    // ================================================================
    // Successful sends
    // ================================================================

    private SentLink sendLinkOk(SeededOrder order, MockHttpSession session, String label) throws Exception {
        return sendLinkOk(order, session, EMPTY_OBJECT, label);
    }

    /**
     * One successful send of {@code order}'s link: 201 with the exact shape; exactly one delivered link email
     * (the exact wording, the trimmed saved email, the current version); exactly one new row - ACTIVE, the hash
     * of the emailed token, the session user, {@code expires_at = sent_at + 7 days}, stamped, alive - and the
     * only ACTIVE row of the order; no OrderInvoiceService log line. Returns the token and its committed row.
     */
    private SentLink sendLinkOk(SeededOrder order, MockHttpSession session, Body body, String label) throws Exception {
        int deliveredBefore = linkEmailSender.sentEmails().size();
        int failedBefore = linkEmailSender.failedEmails().size();
        int rowsBefore = tokenRows(order.orderId()).size();
        int currentVersion = currentInvoiceVersion(order.orderId());

        Captured attempt = performCapturing(sendLinkRequest(order.scope().slug(), order.orderId(), session, body));

        LinkTimes times = assertLinkSentResponse(attempt.result(), label);
        Assertions.assertEquals(failedBefore, linkEmailSender.failedEmails().size(), label + ": no failed link email");
        List<InvoiceLinkEmailRequest> delivered = linkEmailSender.sentEmails();
        Assertions.assertEquals(deliveredBefore + 1, delivered.size(), label + ": exactly one link email delivered");
        InvoiceLinkEmailRequest email = delivered.get(delivered.size() - 1);
        String token = assertLinkEmail(email, order, currentVersion, label);
        String hash = sha256Hex(token);
        assertNothingSecretLoggedAtInfoOrAbove(attempt, token, label);

        List<Map<String, Object>> rows = tokenRows(order.orderId());
        Assertions.assertEquals(rowsBefore + 1, rows.size(), label + ": exactly one invoice_token row minted");
        Map<String, Object> row = rows.get(rows.size() - 1);
        Assertions.assertEquals(hash, row.get("token_hash"), label + ": the new row stores the emailed token's hash");
        Assertions.assertEquals("ACTIVE", row.get("status"), label + ": the new link is ACTIVE");
        Assertions.assertEquals(order.orderId(), longValue(row.get("order_id")), label + ": the new link's order");
        Assertions.assertEquals(((Long) session.getAttribute("user_id")).longValue(), longValue(row.get("sent_by_user_id")),
                label + ": sent_by_user_id is the session user");
        LocalDateTime sentAt = localDateTime(row.get("sent_at"));
        Assertions.assertEquals(sentAt.plusDays(7), localDateTime(row.get("expires_at")),
                label + ": expires_at = sent_at + 7 days");
        assertSameWholeSecond(times.sentAt(), sentAt, label + ": sent_at");
        assertSameWholeSecond(times.expiresAt(), localDateTime(row.get("expires_at")), label + ": expires_at");
        Assertions.assertNotNull(row.get("emailed_at"), label + ": the delivered link's emailed_at is stamped");
        Assertions.assertNull(row.get("dead_at"), label + ": the new link is alive");
        Assertions.assertEquals(1L, activeLinkCount(order.orderId()), label + ": exactly one ACTIVE link");
        Assertions.assertEquals(List.of(), describe(attempt.serviceLog()), label + ": a delivered, stamped send logs nothing");
        return new SentLink(token, hash, row, times, email);
    }

    /**
     * The 201: exactly {@code {"data":{"invoice_link":{"sent_at","expires_at"}},"message"}}, both timestamps in
     * whole seconds and {@code expires_at = sent_at + 7 days} exactly.
     */
    private static LinkTimes assertLinkSentResponse(MvcResult result, String label) throws Exception {
        assertStatus(result, 201, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": top-level keys");
        Assertions.assertEquals(LINK_SENT_MESSAGE, root.path("message").asText(), label + ": message");
        JsonNode data = root.path("data");
        Assertions.assertEquals(Set.of("invoice_link"), fieldNames(data), label + ": data keys");
        JsonNode link = data.path("invoice_link");
        Assertions.assertEquals(Set.of("sent_at", "expires_at"), fieldNames(link), label + ": invoice_link keys");
        Assertions.assertTrue(link.get("sent_at").isTextual() && link.get("expires_at").isTextual(),
                label + ": both timestamps are strings");
        String sentText = link.get("sent_at").asText();
        String expiresText = link.get("expires_at").asText();
        Assertions.assertTrue(WHOLE_SECOND_TIMESTAMP.matcher(sentText).matches(),
                label + ": sent_at is yyyy-MM-ddTHH:mm:ss, but was " + sentText);
        Assertions.assertTrue(WHOLE_SECOND_TIMESTAMP.matcher(expiresText).matches(),
                label + ": expires_at is yyyy-MM-ddTHH:mm:ss, but was " + expiresText);
        LocalDateTime sentAt = LocalDateTime.parse(sentText);
        LocalDateTime expiresAt = LocalDateTime.parse(expiresText);
        Assertions.assertEquals(sentAt.plusDays(7), expiresAt, label + ": expires_at = sent_at + 7 days exactly");
        return new LinkTimes(sentText, expiresText, sentAt, expiresAt);
    }

    /**
     * One link email (delivered or failed) for {@code order}: the public link exactly once, the TRIMMED saved
     * email, the exact subject and link-only body, the order id and {@code expectedVersion}. Returns the token.
     */
    private static String assertLinkEmail(InvoiceLinkEmailRequest email, SeededOrder order, int expectedVersion,
                                          String label) {
        Matcher matcher = LINK_PATTERN.matcher(email.bodyText());
        Assertions.assertTrue(matcher.find(), label + ": the email carries the public /i/{token} link: " + email.bodyText());
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), label + ": the link appears exactly once");
        Assertions.assertEquals(1, countOccurrences(email.bodyText(), "/i/"), label + ": '/i/' appears exactly once");
        Assertions.assertEquals(1, countOccurrences(email.bodyText(), token), label + ": the token appears exactly once");
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), label + ": the TRIMMED saved customer email");
        String businessName = order.scope().businessName();
        Assertions.assertEquals("Your invoice from " + businessName, email.subject(), label + ": subject");
        Assertions.assertEquals("Hi,\n\nYour invoice " + order.orderNumber() + " from " + businessName
                        + " is ready to review and sign. You can view and sign your invoice online here: "
                        + PUBLIC_BASE_URL + "/i/" + token + "\n\nThank you.",
                email.bodyText(), label + ": the exact link-only body");
        Assertions.assertEquals(order.orderId(), email.orderId(), label + ": order id");
        Assertions.assertEquals(expectedVersion, email.invoiceVersionNumber(), label + ": the current invoice version");
        return token;
    }

    // ================================================================
    // Refusals
    // ================================================================

    /**
     * A refused send-link (or a refused sibling-route request used for parity): the exact error envelope, and
     * NOTHING written or sent - every committed row of both tenants that any invoice route could touch
     * ({@code invoice_token}, {@code invoice} with its markers, {@code sales_order} with its mirror,
     * {@code stored_file}, files on disk, quote rows, payments, customers) is unchanged, no message of any kind
     * was sent or attempted (the recorders are cleared first), and the OrderInvoiceService logged nothing.
     */
    private MvcResult assertRefused(RequestBuilder request, int status, String code, String message, List<Detail> details,
                                    String label) throws Exception {
        resetSenders();
        WorldState before = world();
        Captured attempt = performCapturing(request);
        assertError(attempt.result(), status, code, message, details, label);
        assertSameState(before, world(), label);
        assertNoMessages(label);
        Assertions.assertEquals(List.of(), describe(attempt.serviceLog()),
                label + ": a refusal logs nothing on the OrderInvoiceService logger");
        return attempt.result();
    }

    /**
     * The exact error envelope: only {@code error}; {@code code} and {@code message}; {@code details} present
     * exactly when expected, each detail exactly {@code field} + {@code message}, in order.
     */
    private static void assertError(MvcResult result, int status, String code, String message, List<Detail> details,
                                    String label) throws Exception {
        assertStatus(result, status, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("error"), fieldNames(root), label + ": an error envelope carries no data");
        JsonNode error = root.path("error");
        Assertions.assertEquals(details.isEmpty() ? Set.of("code", "message") : Set.of("code", "message", "details"),
                fieldNames(error), label + ": error keys");
        Assertions.assertEquals(code, error.path("code").asText(), label + ": error.code");
        Assertions.assertEquals(message, error.path("message").asText(), label + ": error.message");
        if (!details.isEmpty()) {
            JsonNode array = error.path("details");
            Assertions.assertTrue(array.isArray(), label + ": error.details is an array");
            List<Detail> actual = new ArrayList<>();
            for (JsonNode detail : array) {
                Assertions.assertEquals(Set.of("field", "message"), fieldNames(detail),
                        label + ": each detail is exactly field + message");
                actual.add(new Detail(detail.path("field").asText(), detail.path("message").asText()));
            }
            Assertions.assertEquals(details, actual, label + ": error.details");
        }
    }

    // ================================================================
    // Public view assertions
    // ================================================================

    /** A dead link's 200: exactly the 27 keys; only state, business_name and the per-state message non-null. */
    private static void assertDeadView(JsonNode root, String state, String message, String label) {
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": envelope keys");
        Assertions.assertEquals(INVOICE_LOADED_MESSAGE, root.path("message").asText(), label + ": envelope message");
        JsonNode data = root.path("data");
        Assertions.assertEquals(PUBLIC_VIEW_KEYS, fieldNames(data), label + ": exactly the 27 public view keys");
        Assertions.assertEquals(27, data.size(), label + ": 27 keys");
        for (String key : PUBLIC_VIEW_KEYS) {
            if (DEAD_VIEW_NON_NULL_KEYS.contains(key)) {
                Assertions.assertFalse(data.get(key).isNull(), label + ": " + key + " is present and non-null");
            } else {
                Assertions.assertTrue(data.get(key).isNull(), label + ": " + key + " is null on a dead link");
            }
        }
        Assertions.assertEquals(state, data.path("state").asText(), label + ": state");
        Assertions.assertEquals(BUSINESS_NAME, data.path("business_name").asText(), label + ": business_name");
        Assertions.assertEquals(message, data.path("message").asText(), label + ": message");
    }

    /** A live link's 200: the 27 keys, state ACTIVE, the current version's document and the token's own expiry. */
    private static void assertActiveView(JsonNode root, SeededOrder order, int versionNumber, Map<String, Object> tokenRow,
                                         String label) {
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": envelope keys");
        Assertions.assertEquals(INVOICE_LOADED_MESSAGE, root.path("message").asText(), label + ": envelope message");
        JsonNode data = root.path("data");
        Assertions.assertEquals(PUBLIC_VIEW_KEYS, fieldNames(data), label + ": exactly the 27 public view keys");
        Assertions.assertEquals("ACTIVE", data.path("state").asText(), label + ": state");
        Assertions.assertTrue(data.get("message").isNull(), label + ": no message on a live link");
        Assertions.assertEquals(order.scope().businessName(), data.path("business_name").asText(), label + ": business");
        Assertions.assertEquals(BUSINESS_ABN, data.path("business_abn").asText(), label + ": ABN");
        Assertions.assertEquals(order.orderNumber(), data.path("order_number").asText(), label + ": order number");
        Assertions.assertEquals("SOFT", data.path("flooring_type").asText(), label + ": flooring type");
        Assertions.assertEquals(versionNumber, data.path("version_number").asInt(), label + ": the current version");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, data.path("customer_name").asText(), label + ": frozen name");
        Assertions.assertEquals(DETAILS_OF_SALE, data.path("details_of_sale").asText(), label + ": details of sale");
        assertMoney("100.00", data.path("sale_price_ex_gst").decimalValue(), label + ": ex GST");
        assertMoney("10.00", data.path("gst_amount").decimalValue(), label + ": GST");
        assertMoney("110.00", data.path("sale_price_inc_gst").decimalValue(), label + ": inc GST");
        Assertions.assertEquals(wholeSeconds(tokenRow.get("expires_at")), data.path("expires_at").asText(),
                label + ": expires_at is the presented token's");
    }

    // ================================================================
    // InvoiceDetail / E.1 assertions
    // ================================================================

    /** D.3 (200, no message); asserts the derived field is always present and returns data.invoice. */
    private JsonNode getCurrentInvoice(SeededOrder order) throws Exception {
        MvcResult result = perform(get(orderUrl(order) + "/invoices/current").session(order.scope().session()), 200,
                "D.3 current invoice");
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("data"), fieldNames(root), "D.3 carries data only");
        JsonNode invoice = root.path("data").path("invoice");
        Assertions.assertTrue(invoice.has("invoice_link_expires_at"), "D.3 always carries invoice_link_expires_at");
        JsonNode value = invoice.get("invoice_link_expires_at");
        Assertions.assertTrue(value.isNull() || WHOLE_SECOND_TIMESTAMP.matcher(value.asText()).matches(),
                "invoice_link_expires_at is null or whole seconds, but was " + value);
        return invoice;
    }

    /** A payment / void response: its E.1 current_invoice is the new version and never carries the link field. */
    private static void assertE1WithoutLinkField(JsonNode root, int expectedVersion, String label) {
        JsonNode currentInvoice = root.path("data").path("current_invoice");
        Assertions.assertEquals(expectedVersion, currentInvoice.path("version_number").asInt(), label + ": version");
        Assertions.assertTrue(currentInvoice.has("invoice_id") && currentInvoice.has("balance_due"),
                label + ": the E.1 summary is there");
        Assertions.assertFalse(currentInvoice.has("invoice_link_expires_at"),
                label + ": E.1 current_invoice never carries invoice_link_expires_at");
        Assertions.assertFalse(root.toString().contains("invoice_link_expires_at"),
                label + ": no part of the response carries invoice_link_expires_at");
    }

    private static void assertPresentAndNull(JsonNode node, String field, String label) {
        Assertions.assertTrue(node.has(field), label + ": " + field + " is always present");
        Assertions.assertTrue(node.get(field).isNull(), label + ": " + field + " is null, but was " + node.get(field));
    }

    // ================================================================
    // State, message and log assertions
    // ================================================================

    private void assertLinkUntouched(SentLink link, String label) {
        Assertions.assertEquals(link.row(), tokenRowByHash(link.hash()), label + ": the link's row is unchanged");
    }

    private static void assertSameState(WorldState before, WorldState after, String label) {
        assertSameStateExceptLinks(before, after, label);
        Assertions.assertEquals(before.invoiceTokens(), after.invoiceTokens(), label + ": invoice_token rows");
    }

    private static void assertSameStateExceptLinks(WorldState before, WorldState after, String label) {
        Assertions.assertEquals(before.salesOrders(), after.salesOrders(),
                label + ": sales_order rows (incl. the last_emailed_at mirror)");
        Assertions.assertEquals(before.invoices(), after.invoices(),
                label + ": invoice rows (incl. each version's last_emailed_at)");
        Assertions.assertEquals(before.storedFiles(), after.storedFiles(), label + ": stored_file rows");
        Assertions.assertEquals(before.filesOnDisk(), after.filesOnDisk(), label + ": files on disk");
        Assertions.assertEquals(before.quoteVersions(), after.quoteVersions(), label + ": quote_version rows");
        Assertions.assertEquals(before.quoteTokens(), after.quoteTokens(), label + ": quote_token rows");
        Assertions.assertEquals(before.payments(), after.payments(), label + ": payment_transaction rows");
        Assertions.assertEquals(before.customers(), after.customers(), label + ": order_customer rows");
    }

    /** Nothing of any kind was sent or attempted - the link email included. */
    private void assertNoMessages(String label) {
        Assertions.assertTrue(linkEmailSender.sentEmails().isEmpty() && linkEmailSender.failedEmails().isEmpty(),
                label + ": no link email was sent or attempted");
        assertNoOtherMessages(label);
    }

    /** No signed-invoice email, store notification, quote email, quote notification or SMS was sent or attempted. */
    private void assertNoOtherMessages(String label) {
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty() && invoiceEmailSender.failedEmails().isEmpty(),
                label + ": no signed-invoice email was sent or attempted");
        Assertions.assertTrue(invoiceNotificationSender.sentNotifications().isEmpty()
                        && invoiceNotificationSender.failedNotifications().isEmpty(),
                label + ": no invoice store notification was sent or attempted");
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty() && quoteEmailSender.failedEmails().isEmpty(),
                label + ": no quote email was sent or attempted");
        Assertions.assertTrue(quoteNotificationSender.sentNotifications().isEmpty()
                        && quoteNotificationSender.failedNotifications().isEmpty(),
                label + ": no quote store notification was sent or attempted");
        Assertions.assertTrue(smsSender.sentMessages().isEmpty() && smsSender.failedMessages().isEmpty(),
                label + ": no SMS was sent or attempted");
    }

    /**
     * Exactly one OrderInvoiceService event: WARN, the exact text, no throwable, and none of the given secrets,
     * no address, no link.
     */
    private static void assertSingleWarn(List<ILoggingEvent> events, String expectedMessage, List<String> secrets,
                                         String label) {
        Assertions.assertEquals(1, events.size(),
                label + ": exactly one OrderInvoiceService log event, but was " + describe(events));
        ILoggingEvent event = events.get(0);
        Assertions.assertEquals(Level.WARN, event.getLevel(), label + ": logged at WARN");
        String message = event.getFormattedMessage();
        Assertions.assertEquals(expectedMessage, message, label + ": the WARN text");
        Assertions.assertNull(event.getThrowableProxy(), label + ": the WARN carries no throwable");
        for (String leak : List.of("@", "/i/", "http")) {
            Assertions.assertFalse(message.contains(leak), label + ": the WARN never carries '" + leak + "': " + message);
        }
        for (String secret : secrets) {
            Assertions.assertFalse(message.contains(secret), label + ": the WARN never carries '" + secret + "'");
        }
    }

    /** The root-logger capture is live: it saw the expected WARN as well (so its INFO+ secret scan is not vacuous). */
    private static void assertRootCaptureSaw(Captured attempt, String expectedMessage, String label) {
        Assertions.assertTrue(attempt.allLog().stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && expectedMessage.equals(event.getFormattedMessage())),
                label + ": the root-logger capture saw the WARN too, but was " + describe(attempt.allLog()));
    }

    private static List<String> describe(List<ILoggingEvent> events) {
        return events.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + body(result));
    }

    private static void assertMoney(String expected, BigDecimal actual, String label) {
        Assertions.assertNotNull(actual, label + ": expected " + expected + " but was null");
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actual),
                label + ": expected " + expected + " but was " + actual);
    }

    /**
     * A response timestamp is the stored JVM value formatted to whole seconds (truncation). The stored value is
     * that JVM value at PostgreSQL's microsecond precision, so it truncates to the response's second - or, only
     * when a nanosecond JVM clock was rounded up across a second boundary, it is exactly the next whole second.
     */
    private static void assertSameWholeSecond(LocalDateTime responseValue, LocalDateTime storedValue, String label) {
        Assertions.assertEquals(0, responseValue.getNano(), label + ": the response value is whole seconds");
        boolean sameSecond = storedValue.truncatedTo(ChronoUnit.SECONDS).equals(responseValue);
        boolean microsecondRoundingCarry = storedValue.equals(responseValue.plusSeconds(1));
        Assertions.assertTrue(sameSecond || microsecondRoundingCarry,
                label + ": the stored " + storedValue + " is the response's second " + responseValue);
    }

    // ================================================================
    // Committed-state probes (auto-commit JDBC - committed data only)
    // ================================================================

    /** Every committed row of BOTH tenants that an invoice or link route could touch, plus their files on disk. */
    private WorldState world() throws IOException {
        String orders = "SELECT order_id FROM sales_order WHERE business_id IN (?, ?)";
        Object[] businessIds = {homeBusinessId, otherBusinessId};
        return new WorldState(
                rows("SELECT * FROM sales_order WHERE business_id IN (?, ?) ORDER BY order_id", businessIds),
                rows("SELECT * FROM invoice WHERE order_id IN (" + orders + ") ORDER BY invoice_id", businessIds),
                rows("SELECT * FROM invoice_token WHERE order_id IN (" + orders + ") ORDER BY invoice_token_id",
                        businessIds),
                rows("SELECT * FROM stored_file WHERE storage_path LIKE ? OR storage_path LIKE ? ORDER BY stored_file_id",
                        "/uploads/" + homeBusinessId + "/%", "/uploads/" + otherBusinessId + "/%"),
                filesOnDisk(),
                rows("SELECT * FROM quote_version WHERE order_id IN (" + orders + ") ORDER BY quote_version_id",
                        businessIds),
                rows("SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id IN (" + orders + ") ORDER BY t.quote_token_id", businessIds),
                rows("SELECT * FROM payment_transaction WHERE order_id IN (" + orders + ") "
                        + "ORDER BY payment_transaction_id", businessIds),
                rows("SELECT * FROM order_customer WHERE order_id IN (" + orders + ") ORDER BY order_customer_id",
                        businessIds));
    }

    /** Every file under both tenants' storage directories, as virtual storage paths. */
    private Set<String> filesOnDisk() throws IOException {
        Set<String> paths = new TreeSet<>();
        for (long businessId : List.of(homeBusinessId, otherBusinessId)) {
            Path directory = businessStorageDir(businessId);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(directory)) {
                files.filter(Files::isRegularFile)
                        .map(file -> "/" + storageRoot().relativize(file).toString().replace('\\', '/'))
                        .forEach(paths::add);
            }
        }
        return paths;
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
            copies.add(new LinkedHashMap<>(row));
        }
        return copies;
    }

    /** The order's invoice links, oldest first. */
    private List<Map<String, Object>> tokenRows(long orderId) {
        return rows("SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId);
    }

    /** The one invoice_token row with this hash (exactly one, by uq_invoice_token_hash). */
    private Map<String, Object> tokenRowByHash(String tokenHash) {
        List<Map<String, Object>> found = rows("SELECT * FROM invoice_token WHERE token_hash = ?", tokenHash);
        Assertions.assertEquals(1, found.size(), "exactly one invoice_token row with hash " + tokenHash);
        return found.get(0);
    }

    private long activeLinkCount(long orderId) {
        return count("SELECT COUNT(*) FROM invoice_token WHERE order_id = ? AND status = 'ACTIVE'", orderId);
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber));
    }

    private List<Integer> invoiceVersions(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT version_number FROM invoice WHERE order_id = ? ORDER BY version_number", Integer.class, orderId);
    }

    private int currentInvoiceVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "the order has a current invoice");
        return version;
    }

    /** An order id above every existing one (state-derived): no order has it. */
    private long unusedOrderId() {
        Long id = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(order_id), 0) + 1000000 FROM sales_order", Long.class);
        Assertions.assertNotNull(id, "an unused order id");
        return id;
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    // ================================================================
    // invoice_token write-failure triggers (class-scoped; dropped in finally and by the sweep)
    // ================================================================

    /**
     * The trigger function: bumps a NON-transactional sequence (so a firing stays visible after the rollback it
     * causes) and raises.
     */
    private void createTokenWriteFailureFunction() {
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS " + TOKEN_WRITE_FIRES_SEQUENCE);
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION " + TOKEN_WRITE_FAILURE_FUNCTION + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$\n"
                + "BEGIN\n"
                + "    PERFORM nextval('" + TOKEN_WRITE_FIRES_SEQUENCE + "');\n"
                + "    RAISE EXCEPTION 'ilsnd injected invoice_token write failure' USING ERRCODE = 'P0001';\n"
                + "END;\n"
                + "$$");
    }

    /**
     * A trigger on {@code invoice_token} that fires only when THIS order's token gets its first
     * {@code emailed_at} (the send's success stamp; never the status-only kill of a previous link). Non-deferred:
     * {@code BEFORE UPDATE}, failing the stamp's UPDATE itself. Deferred: a {@code DEFERRABLE INITIALLY DEFERRED}
     * constraint trigger {@code AFTER UPDATE}, failing the stamp transaction at COMMIT.
     */
    private void installStampFailureTrigger(String trigger, long orderId, boolean deferred) {
        createTokenWriteFailureFunction();
        String when = " WHEN (NEW.order_id = " + orderId
                + " AND OLD.emailed_at IS NULL AND NEW.emailed_at IS NOT NULL)";
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice_token");
        if (deferred) {
            jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger + " AFTER UPDATE ON invoice_token"
                    + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW" + when
                    + " EXECUTE FUNCTION " + TOKEN_WRITE_FAILURE_FUNCTION + "()");
        } else {
            jdbcTemplate.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON invoice_token FOR EACH ROW" + when
                    + " EXECUTE FUNCTION " + TOKEN_WRITE_FAILURE_FUNCTION + "()");
        }
    }

    /**
     * A {@code BEFORE INSERT} trigger on {@code invoice_token} for THIS order: the send's mint INSERT fails
     * inside the send transaction, AFTER its {@code REPLACED} kill of the previous link (an UPDATE, which this
     * trigger never sees). Before raising, its function bumps the fire count and records - with {@code setval},
     * which is never rolled back - how many ACTIVE rows of the order the INSERT saw in flight.
     */
    private void installMintFailureTrigger(String trigger, long orderId) {
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS " + TOKEN_WRITE_FIRES_SEQUENCE);
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS " + MINT_ACTIVE_SEEN_SEQUENCE);
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION " + MINT_FAILURE_FUNCTION + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$\n"
                + "BEGIN\n"
                + "    PERFORM nextval('" + TOKEN_WRITE_FIRES_SEQUENCE + "');\n"
                + "    PERFORM setval('" + MINT_ACTIVE_SEEN_SEQUENCE + "', " + MINT_ACTIVE_SEEN_OFFSET
                + " + (SELECT count(*) FROM invoice_token WHERE order_id = NEW.order_id AND status = 'ACTIVE'));\n"
                + "    RAISE EXCEPTION 'ilsnd injected invoice_token write failure' USING ERRCODE = 'P0001';\n"
                + "END;\n"
                + "$$");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice_token");
        jdbcTemplate.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON invoice_token FOR EACH ROW"
                + " WHEN (NEW.order_id = " + orderId + ")"
                + " EXECUTE FUNCTION " + MINT_FAILURE_FUNCTION + "()");
    }

    /** The ACTIVE rows of the order the failing mint INSERT saw in flight (-1 when it never recorded any). */
    private long mintActiveRowsSeenInFlight() {
        Long recorded = jdbcTemplate.queryForObject(
                "SELECT CASE WHEN is_called THEN last_value ELSE NULL END FROM " + MINT_ACTIVE_SEEN_SEQUENCE, Long.class);
        return recorded == null ? -1L : recorded - MINT_ACTIVE_SEEN_OFFSET;
    }

    /** How many times a write-failure trigger has fired (the sequence's position; never rolled back). */
    private long tokenWriteTriggerFires() {
        Long fires = jdbcTemplate.queryForObject(
                "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM " + TOKEN_WRITE_FIRES_SEQUENCE, Long.class);
        return fires == null ? 0L : fires;
    }

    /** Drop every ilsnd_ trigger on invoice_token, then the function and the sequence. */
    private void dropTokenWriteFailureArtifacts() {
        for (String trigger : jdbcTemplate.queryForList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'invoice_token'::regclass AND tgname LIKE ?",
                String.class, TRIGGER_PREFIX.replace("_", "\\_") + "%")) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice_token");
        }
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + TOKEN_WRITE_FAILURE_FUNCTION + "()");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + MINT_FAILURE_FUNCTION + "()");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS " + TOKEN_WRITE_FIRES_SEQUENCE);
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS " + MINT_ACTIVE_SEEN_SEQUENCE);
    }

    // ================================================================
    // Committed self-seeding (auto-commit JDBC + real API calls)
    // ================================================================

    /**
     * The two COMMITTED tenants: {@value #SLUG} (ABN + bank details, no terms; ILS-ONE and ILS-TWO, each with a
     * store email; LS1 with access to both stores, LS2 with access to ILS-ONE only) and {@value #OTHER_SLUG}
     * (ILS-OTH; LO1 with access to it).
     */
    private void seedTenants() {
        homeBusinessId = insertBusiness(SLUG, BUSINESS_NAME);
        int storeOneId = insertStore(homeBusinessId, "Lanternfield Showroom", STORE_CODE,
                "showroom@lanternfield-link.example.com");
        storeTwoId = insertStore(homeBusinessId, "Lanternfield Warehouse", SECOND_STORE_CODE,
                "warehouse@lanternfield-link.example.com");
        ownerUserId = insertUser(homeBusinessId, "Ada", "Wick", OWNER_CODE, "ada.wick@lanternfield-link.example.com");
        senderUserId = insertUser(homeBusinessId, "Bram", "Flint", SENDER_CODE,
                "bram.flint@lanternfield-link.example.com");
        grantStoreAccess(homeBusinessId, ownerUserId, storeOneId);
        grantStoreAccess(homeBusinessId, ownerUserId, storeTwoId);
        grantStoreAccess(homeBusinessId, senderUserId, storeOneId);
        homeStoreOne = new Scope(SLUG, BUSINESS_NAME, homeBusinessId, storeOneId, STORE_CODE, ownerUserId, OWNER_CODE);
        homeStoreTwo = new Scope(SLUG, BUSINESS_NAME, homeBusinessId, storeTwoId, SECOND_STORE_CODE, ownerUserId,
                OWNER_CODE);
        senderStoreOne = new Scope(SLUG, BUSINESS_NAME, homeBusinessId, storeOneId, STORE_CODE, senderUserId,
                SENDER_CODE);

        otherBusinessId = insertBusiness(OTHER_SLUG, OTHER_BUSINESS_NAME);
        int otherStoreId = insertStore(otherBusinessId, "Lanternfield Elsewhere Showroom", OTHER_STORE_CODE,
                "showroom@lanternfield-elsewhere.example.com");
        long otherUserId = insertUser(otherBusinessId, "Cato", "Reed", OTHER_CODE,
                "cato.reed@lanternfield-elsewhere.example.com");
        grantStoreAccess(otherBusinessId, otherUserId, otherStoreId);
        otherBusiness = new Scope(OTHER_SLUG, OTHER_BUSINESS_NAME, otherBusinessId, otherStoreId, OTHER_STORE_CODE,
                otherUserId, OTHER_CODE);
        orderSequences.clear();
    }

    private long insertBusiness(String slug, String name) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number) "
                        + "VALUES (?, ?, TRUE, ?, 'Lanternfield Bank', '062-000', ?, '12345678') RETURNING business_id",
                Long.class, name, slug, BUSINESS_ABN, name + " Pty Ltd");
        Assertions.assertNotNull(id, "setup: business insert returned no id");
        return id;
    }

    private int insertStore(long businessId, String name, String storeCode, String email) {
        Integer id = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, ?, ?, '0290004321', ?, '9 Lantern Parade', 'Leichhardt', 'NSW', '2040') "
                        + "RETURNING store_id",
                Integer.class, businessId, name, storeCode, email);
        Assertions.assertNotNull(id, "setup: store insert returned no id");
        return id;
    }

    private long insertUser(long businessId, String firstName, String lastName, String salespersonCode, String email) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'not-a-login-hash-ilsnd') RETURNING user_id",
                Long.class, businessId, firstName, lastName, salespersonCode, email);
        Assertions.assertNotNull(id, "setup: app_user insert returned no id");
        return id;
    }

    private void grantStoreAccess(long businessId, long userId, int storeId) {
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
    }

    /**
     * A committed, invoice-ready SOFT order of {@code scope}'s store and salesperson with NO invoice: customer
     * "Willow Lantern" with the PADDED valid email, INSTALLATION + BILLING, details of sale, proposed lay date
     * 2026-12-01 CONFIRMED, one {@value #CHARGE_CODE_PREFIX}-coded charge line of 100.00 ex / 40.00 cost
     * (110.00 inc).
     */
    private SeededOrder invoiceReadyOrder(Scope scope) {
        int sequence = orderSequences.merge(scope.businessId(), 1, Integer::sum);
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        String orderNumber = scope.storeCode() + "." + scope.salespersonCode() + "." + String.format("%05d", sequence);
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 43, 2026, ?, "
                        + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, scope.businessId(), scope.storeId(), scope.userId(), sequence, orderNumber, DETAILS_OF_SALE);
        Assertions.assertNotNull(orderId, "setup: order insert returned no id");
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) VALUES (?, ?, ?, ?, ?)",
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, SAVED_CUSTOMER_EMAIL, CUSTOMER_MOBILE);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '7', 'Install Lane', 'Ryde', 'NSW', '2112')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Lantern Street', 'Paddington', 'NSW', '2021')",
                orderId);
        String chargeCode = CHARGE_CODE_PREFIX + sequence;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Lanternfield supply and lay', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, scope.storeId(), chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Lanternfield supply and lay', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        return new SeededOrder(scope, orderId, orderNumber);
    }

    /**
     * An invoice-ready order with its unsigned, unemailed invoice v1 created through the REAL D.1 endpoint by
     * {@code scope}'s session; D.1's InvoiceDetail carries {@code invoice_link_expires_at} present and null.
     */
    private SeededOrder invoicedOrder(Scope scope) throws Exception {
        SeededOrder order = invoiceReadyOrder(scope);
        MvcResult created = perform(post(orderUrl(order) + "/invoices").session(scope.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "setup: D.1 invoice create");
        JsonNode root = json(created);
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, root.path("message").asText(), "setup: D.1 message");
        JsonNode invoice = root.path("data").path("invoice");
        Assertions.assertEquals(1, invoice.path("version_number").asInt(), "setup: D.1 created v1");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, invoice.path("customer_name_snapshot").asText(),
                "setup: v1's frozen name");
        assertPresentAndNull(invoice, "invoice_link_expires_at", "D.1's InvoiceDetail (no link can exist yet)");
        Assertions.assertEquals(List.of(1), invoiceVersions(order.orderId()), "setup: exactly invoice v1");
        Assertions.assertNull(invoiceRow(order.orderId(), 1).get("accepted_at"), "setup: v1 is unsigned");
        return order;
    }

    /**
     * Commit NON-NULL sentinels on every invoice version's {@code last_emailed_at} and on the
     * {@code sales_order.last_emailed_at} mirror, so "a link send never touches them" is provable (a reset to
     * null or a stamp would both change them).
     */
    private void plantMarkerSentinels(SeededOrder order) {
        Assertions.assertEquals(invoiceVersions(order.orderId()).size(), jdbcTemplate.update(
                        "UPDATE invoice SET last_emailed_at = ? WHERE order_id = ?", INVOICE_MARKER_SENTINEL, order.orderId()),
                "setup: invoice marker sentinel on every version");
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE sales_order SET last_emailed_at = ? WHERE order_id = ?", MIRROR_MARKER_SENTINEL, order.orderId()),
                "setup: mirror sentinel");
    }

    private void assertMarkerSentinelsKept(SeededOrder order, String label) {
        Assertions.assertEquals(List.of(INVOICE_MARKER_SENTINEL), jdbcTemplate.queryForList(
                        "SELECT DISTINCT last_emailed_at FROM invoice WHERE order_id = ?", Timestamp.class, order.orderId()),
                label + ": every invoice version's last_emailed_at keeps its sentinel (a link send never touches it)");
        Assertions.assertEquals(MIRROR_MARKER_SENTINEL, jdbcTemplate.queryForObject(
                        "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, order.orderId()),
                label + ": the sales_order.last_emailed_at mirror keeps its sentinel (a link send never touches it)");
    }

    /**
     * Run {@code refusal} with {@code email} (NULL or blank: forbidden by {@code order_customer.email NOT NULL}
     * and {@code chk_order_customer_email_format}) as the order's saved email, inside ONE programmatic
     * transaction that is ALWAYS rolled back: it relaxes the two constraints and plants the value on this
     * thread's connection, and the route's own transaction joins it and refuses before any write. Neither the
     * relaxed schema nor the planted value is ever committed; afterwards both constraints are asserted back.
     */
    private void withSavedEmailInARolledBackTransaction(SeededOrder order, String email, String label,
                                                        ThrowingRunnable refusal) {
        String committedEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM order_customer WHERE order_id = ?", String.class, order.orderId());
        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly(); // nothing below is ever committed
            jdbcTemplate.execute("ALTER TABLE order_customer DROP CONSTRAINT chk_order_customer_email_format");
            jdbcTemplate.execute("ALTER TABLE order_customer ALTER COLUMN email DROP NOT NULL");
            Assertions.assertEquals(1, jdbcTemplate.update(
                    "UPDATE order_customer SET email = ? WHERE order_id = ?", email, order.orderId()),
                    label + ": plant the saved email");
            try {
                refusal.run();
            } catch (Exception e) {
                throw new IllegalStateException(label + " failed", e);
            }
        });
        Assertions.assertEquals("NO", jdbcTemplate.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_schema = 'public' AND table_name = 'order_customer' AND column_name = 'email'",
                        String.class),
                label + ": the rollback restored order_customer.email NOT NULL");
        Assertions.assertEquals(1L, count("SELECT COUNT(*) FROM pg_constraint WHERE conname = ?",
                "chk_order_customer_email_format"), label + ": the rollback restored chk_order_customer_email_format");
        Assertions.assertEquals(committedEmail, jdbcTemplate.queryForObject(
                        "SELECT email FROM order_customer WHERE order_id = ?", String.class, order.orderId()),
                label + ": the committed saved email is unchanged");
    }

    /** A refusal step that may throw (MockMvc's checked exceptions). */
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void setCustomerEmail(SeededOrder order, String email) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_customer SET email = ? WHERE order_id = ?", email, order.orderId()),
                "setup: set the saved customer email");
    }

    private void deleteCustomerRow(SeededOrder order) {
        Assertions.assertEquals(1, jdbcTemplate.update("DELETE FROM order_customer WHERE order_id = ?", order.orderId()),
                "setup: delete the order's customer row");
    }

    // ================================================================
    // Cleanup (runs in @BeforeEach AND @AfterEach; every step runs even if an earlier one fails)
    // ================================================================

    private void cleanUpEverything() {
        runAll(this::resetSenders, this::dropTokenWriteFailureArtifacts, this::sweepTenants);
        assertSweptClean();
    }

    private void resetSenders() {
        linkEmailSender.reset();
        invoiceEmailSender.reset();
        invoiceNotificationSender.reset();
        quoteEmailSender.reset();
        quoteNotificationSender.reset();
        smsSender.reset();
    }

    /** The sweep removed everything this class commits: both tenants, their files, the triggers, function, sequence. */
    private void assertSweptClean() {
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM business WHERE slug IN (?, ?)", SLUG, OTHER_SLUG),
                "no tenant of this class may survive the sweep");
        Assertions.assertEquals(0L, count(
                        "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice_token'::regclass AND tgname LIKE ?",
                        TRIGGER_PREFIX.replace("_", "\\_") + "%"),
                "no write-failure trigger of this class may survive the sweep");
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM pg_proc WHERE proname LIKE ?",
                TRIGGER_PREFIX.replace("_", "\\_") + "%"), "every write-failure function of this class must be dropped");
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM pg_class WHERE relname LIKE ? AND relkind = 'S'",
                TRIGGER_PREFIX.replace("_", "\\_") + "%"), "every write-failure sequence of this class must be dropped");
        if (homeBusinessId > 0) {
            Assertions.assertFalse(Files.exists(businessStorageDir(homeBusinessId)), "the home storage dir is removed");
        }
        if (otherBusinessId > 0) {
            Assertions.assertFalse(Files.exists(businessStorageDir(otherBusinessId)), "the other storage dir is removed");
        }
    }

    private static void runAll(Runnable... steps) {
        RuntimeException failure = null;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Every tenant of this class (normally none, or the two of a crashed run), FK-safe. */
    private void sweepTenants() {
        for (Long businessId : jdbcTemplate.queryForList(
                "SELECT business_id FROM business WHERE slug IN (?, ?) ORDER BY business_id", Long.class, SLUG, OTHER_SLUG)) {
            sweepBusiness(businessId);
        }
    }

    private void sweepBusiness(long businessId) {
        for (Long orderId : jdbcTemplate.queryForList(
                "SELECT order_id FROM sales_order WHERE business_id = ? ORDER BY order_id", Long.class, businessId)) {
            deleteOrderAndFiles(businessId, orderId);
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
            // invoice_token.sent_by_user_id references app_user: any link this tenant's users sent goes first.
            jdbcTemplate.update("DELETE FROM invoice_token WHERE sent_by_user_id IN "
                    + "(SELECT user_id FROM app_user WHERE business_id = ?)", businessId);
            jdbcTemplate.update("DELETE FROM store_charge WHERE store_id IN "
                    + "(SELECT store_id FROM store WHERE business_id = ?)", businessId);
            jdbcTemplate.update("DELETE FROM store_product WHERE store_id IN "
                    + "(SELECT store_id FROM store WHERE business_id = ?)", businessId);
            jdbcTemplate.update("DELETE FROM user_store_access WHERE business_id = ?", businessId);
            jdbcTemplate.update("DELETE FROM app_user WHERE business_id = ?", businessId);
            jdbcTemplate.update("DELETE FROM store WHERE business_id = ?", businessId);
            // Its FK to business has no ON DELETE: wipe it before the business row.
            jdbcTemplate.update("DELETE FROM business_quick_description WHERE business_id = ?", businessId);
            jdbcTemplate.update("DELETE FROM business WHERE business_id = ?", businessId);
        });
        deleteDirectoryQuietly(businessStorageDir(businessId));
    }

    /**
     * Delete one committed order and everything it owns in ONE bounded transaction, FK-safe: its invoice links
     * (before sales_order and app_user), quote tokens, invoices (they may reference a quote version), quote
     * versions (cascade lines), the draft (cascade lines), payments, the other children, the order, then its
     * stored_file rows; the files and the order directory after that commit.
     */
    private void deleteOrderAndFiles(long businessId, long orderId) {
        String prefix = orderStoragePrefix(businessId, orderId);
        List<Map<String, Object>> files = jdbcTemplate.queryForList(ORDER_FILES_SQL,
                orderId, orderId, orderId, orderId, orderId, orderId, prefix + "%");
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
                jdbcTemplate.update("DELETE FROM invoice_token WHERE order_id = ?", orderId);
                jdbcTemplate.update("DELETE FROM quote_token WHERE quote_version_id IN "
                        + "(SELECT quote_version_id FROM quote_version WHERE order_id = ?)", orderId);
                jdbcTemplate.update("DELETE FROM invoice WHERE order_id = ?", orderId);
                jdbcTemplate.update("DELETE FROM quote_version WHERE order_id = ?", orderId);
                jdbcTemplate.update("DELETE FROM quote_draft WHERE order_id = ?", orderId);
                jdbcTemplate.update("DELETE FROM payment_transaction WHERE order_id = ?", orderId);
                for (String child : ORDER_CHILD_TABLES) {
                    jdbcTemplate.update("DELETE FROM " + child + " WHERE order_id = ?", orderId);
                }
                jdbcTemplate.update("DELETE FROM sales_order WHERE order_id = ?", orderId);
                for (Map<String, Object> file : files) {
                    jdbcTemplate.update("DELETE FROM stored_file WHERE stored_file_id = ?", file.get("stored_file_id"));
                }
            });
        } finally {
            for (Map<String, Object> file : files) {
                deleteFileQuietly((String) file.get("storage_path"));
            }
            deleteDirectoryQuietly(physicalPath(prefix));
        }
    }

    private void deleteFileQuietly(String storagePath) {
        if (storagePath == null) {
            return;
        }
        Path file = physicalPath(storagePath);
        if (!file.startsWith(storageRoot())) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort - the directory sweep retries
        }
    }

    /** Recursive delete of one directory strictly inside the storage root (never the root or its uploads dir). */
    private void deleteDirectoryQuietly(Path directory) {
        Path root = storageRoot();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || target.equals(root.resolve("uploads"))
                || !Files.isDirectory(target)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort - target/ is disposable
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ================================================================
    // Paths, sessions, JSON and small helpers
    // ================================================================

    private Path storageRoot() {
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
    }

    /** Physical path = base dir + storage_path (the FileStorageService resolution). */
    private Path physicalPath(String storagePath) {
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        return storageRoot().resolve(relative).normalize();
    }

    private Path businessStorageDir(long businessId) {
        return storageRoot().resolve("uploads").resolve(Long.toString(businessId));
    }

    private static String orderStoragePrefix(long businessId, long orderId) {
        return "/uploads/" + businessId + "/orders/" + orderId + "/";
    }

    private static String orderUrl(SeededOrder order) {
        return "/api/v1/" + order.scope().slug() + "/orders/" + order.orderId();
    }

    /** A protected session; SessionContext casts (Long) user_id / business_id and (Integer) store_id. */
    private static MockHttpSession sessionOf(long userId, long businessId, Integer storeId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", Long.valueOf(userId));
        session.setAttribute("business_id", Long.valueOf(businessId));
        if (storeId != null) {
            session.setAttribute("store_id", storeId);
        }
        return session;
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static long longValue(Object value) {
        Assertions.assertNotNull(value, "expected a non-null number");
        return ((Number) value).longValue();
    }

    private static LocalDateTime localDateTime(Object value) {
        Assertions.assertNotNull(value, "expected a non-null timestamp");
        return ((Timestamp) value).toLocalDateTime();
    }

    /** A stored timestamp formatted the way every InvoiceDetail / public timestamp is: whole seconds. */
    private static String wholeSeconds(Object timestamp) {
        return JSON_TIMESTAMP.format(localDateTime(timestamp));
    }

    /** A copy of a row with some columns replaced (the expected state of a row a writer changed). */
    private static Map<String, Object> withColumns(Map<String, Object> row, Object... columnValuePairs) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        for (int i = 0; i < columnValuePairs.length; i += 2) {
            String column = (String) columnValuePairs[i];
            Assertions.assertTrue(copy.containsKey(column), "unknown column " + column);
            copy.put(column, columnValuePairs[i + 1]);
        }
        return copy;
    }

    private static int countOccurrences(String haystack, String needle) {
        int occurrences = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            occurrences++;
            from += needle.length();
        }
        return occurrences;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ================================================================
    // Records
    // ================================================================

    /** One staff scope: the slug and business, the selected store, the session user. */
    record Scope(String slug, String businessName, long businessId, int storeId, String storeCode, long userId,
                 String salespersonCode) {

        MockHttpSession session() {
            return sessionOf(userId, businessId, storeId);
        }
    }

    /** One committed order of a scope. */
    record SeededOrder(Scope scope, long orderId, String orderNumber) {
    }

    /** A request body form: {@code content} null = no body at all. */
    record Body(String label, String content) {

        @Override
        public String toString() {
            return label;
        }
    }

    /** One body form and the exact answer it gets. */
    record BodyCase(Body body, int status, String code, String message, List<Detail> details) {

        @Override
        public String toString() {
            return body.label() + " -> " + status + " " + code;
        }
    }

    record Detail(String field, String message) {
    }

    /** The send-link 201's two timestamps, as returned and parsed. */
    record LinkTimes(String sentAtText, String expiresAtText, LocalDateTime sentAt, LocalDateTime expiresAt) {
    }

    /** One delivered link: the plaintext token (from the email), its hash, its committed row, the 201 times. */
    record SentLink(String token, String hash, Map<String, Object> row, LinkTimes times, InvoiceLinkEmailRequest email) {
    }

    /** One request, the OrderInvoiceService log events and every root-logger event it produced, its time window. */
    record Captured(MvcResult result, List<ILoggingEvent> serviceLog, List<ILoggingEvent> allLog,
                    LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** Every committed row of both tenants an invoice or link route could touch, plus their files on disk. */
    record WorldState(List<Map<String, Object>> salesOrders,
                      List<Map<String, Object>> invoices,
                      List<Map<String, Object>> invoiceTokens,
                      List<Map<String, Object>> storedFiles,
                      Set<String> filesOnDisk,
                      List<Map<String, Object>> quoteVersions,
                      List<Map<String, Object>> quoteTokens,
                      List<Map<String, Object>> payments,
                      List<Map<String, Object>> customers) {
    }
}
