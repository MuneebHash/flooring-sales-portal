package com.flooring.salesportal.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 16G PR 3 (#69): the mandatory version precondition and the frozen accepted name of D.8
 * {@code POST /api/v1/{slug}/orders/{orderId}/invoices/current/accept}. Authority:
 * docs/API-Contracts-Phase16G-Invoice-Link.md sections 5.2 to 5.4 (carrier, comparison rules, value
 * validation, D.8 order of checks), 6.1 and 6.2 (the D.8 email after the persist) and the PR 3 row of
 * section 16.
 *
 * <p><b>What is proven.</b> The multipart form field {@code expected_version_number} is mandatory, must
 * appear exactly once and must be a positive integer (400 {@code VALIDATION_FAILED}, exactly one detail on
 * that field); a value that is not the current version - an old one, a future one, a forged one, another
 * order's - is a 409 {@code INVOICE_VERSION_STALE} with the exact message, because the value is only ever
 * COMPARED with the current version read under the order lock. The checks run in the contract order:
 * guard, {@code orderId}, scoped order (404), an existing invoice (422 {@code INVOICE_REQUIRED}, before the
 * value is even looked at), the value (400), the stale comparison (409), already accepted (409), the part
 * allowlist (400; the former {@code accepted_customer_name} part is now just an unexpected part), the
 * current version's FROZEN {@code customer_name_snapshot} (422 {@code ACCEPTED_CUSTOMER_NAME_REQUIRED} when
 * null or blank), the signature (422 / 400) and the saved customer email gate (422). Every refusal writes
 * NOTHING: no invoice row (appended or changed), no change to the order row, no {@code stored_file} row,
 * no file in the order's storage directory and no email sent or attempted. The accepted name is the frozen
 * name verbatim and in full (no trim, no 150-character limit, never the live saved customer); the signing
 * version keeps the CURRENT version's {@code invoice_date} (a cross-day signing prints that date, also when
 * the current version is a later one than v1 - never v1's date, never the signing day) while
 * {@code accepted_at} is the signing time; and the email carries the appended version's own stored
 * PDF to the trimmed saved email, with both email markers stamped with the same value.
 *
 * <p><b>Fixtures (self-seeded, Phase 14D rule).</b> {@link #setUp} inserts, inside the test transaction,
 * a fresh tenant: a business (slug {@value #SLUG}) with SOFT and HARD terms, TWO stores, one salesperson
 * with access to both, and the user-store access rows. The session is always the first store; the second
 * store (and, where needed, a second business seeded by {@link #seedOtherBusiness}) supply orders that are
 * out of the session's scope. Each test inserts its own invoice-ready order (customer "Jordan Wilson" with
 * a valid email, INSTALLATION and BILLING addresses, details of sale, a CONFIRMED lay date 45 days ahead,
 * one 100.00 ex GST charge line) and creates its invoice v1 through D.1, so v1's frozen name is "Jordan
 * Wilson". No V4 business, store, user or order is read or written. Raw SQL is used only to seed and to
 * plant state (a frozen name, an invoice date, a saved email, the LAID status), each write followed by a
 * persistence-context clear. Every expected version is derived from the database, never assumed.
 *
 * <p><b>Transaction.</b> The class is {@code @Transactional}: D.8's programmatic transactions join the test
 * transaction, so its "post-commit" email and success stamp run inside it (the in-transaction variants of
 * section 6.1; the committed variants live in the commit integration classes). Everything rolls back; the
 * services' rollback hooks delete the files written under {@value #STORAGE_BASE_DIR} (each in this test's
 * own business and order directories). The recording invoice email sender is a singleton whose state
 * survives the rollback, so it is reset before and after each test.
 */
@ExtendWith(SpringExtension.class)
// The SAME storage property as the other invoice/quote suites, so this class joins their cached Spring
// context. Files stay apart: every file lives under this test's own business and order directories.
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class InvoiceAcceptVersionPreconditionTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenants (never a V4 identity) ----
    private static final String SLUG = "invoice-accept-version-test";
    private static final String BUSINESS_NAME = "Larkspur Flooring House";
    private static final String STORE_CODE = "LKS-ONE";
    private static final String OTHER_STORE_CODE = "LKS-TWO";
    private static final String SALESPERSON_CODE = "QV1";
    private static final String OTHER_BUSINESS_SLUG = "invoice-accept-version-other";
    private static final String OTHER_BUSINESS_NAME = "Wrenfield Floors";
    private static final String OTHER_BUSINESS_STORE_CODE = "WRF-ONE";
    private static final String OTHER_BUSINESS_SALESPERSON_CODE = "RW1";

    // ---- The saved customer of every order ----
    private static final String CUSTOMER_FIRST_NAME = "Jordan";
    private static final String CUSTOMER_LAST_NAME = "Wilson";
    /** What D.1 freezes from the saved customer into v1's customer_name_snapshot: the D.8 accepted name. */
    private static final String FROZEN_NAME = "Jordan Wilson";
    private static final String CUSTOMER_EMAIL = "jordan.wilson@example.com";
    /** DB-valid (trimmed non-empty, contains '@') but app-invalid (no interior dot in the domain). */
    private static final String APP_INVALID_EMAIL = "jordan@localhost";
    /** A saved email with surrounding spaces, which chk_order_customer_email_format allows: emailed trimmed. */
    private static final String PADDED_CUSTOMER_EMAIL = "  jordan.wilson@example.com  ";
    private static final String DETAILS_OF_SALE = "Supply and lay flooring (Larkspur version precondition test)";
    private static final String SOFT_TERMS = "<p>Larkspur soft terms LKS1.</p>";
    private static final String HARD_TERMS = "<p>Larkspur hard terms LKH1.</p>";

    // ---- Frozen names planted on the current version ----
    /** 43 six-letter words joined by single spaces: exactly 300 characters, so the PDF can wrap it between words. */
    private static final String FROZEN_LONG_NAME = longName();
    /** Edge whitespace and a doubled inner space: only a VERBATIM copy keeps this exact string. */
    private static final String PADDED_FROZEN_NAME = "  Casey  Padded Frozen ";

    // ---- The 16G PR 3 D.8 multipart ----
    private static final String SIGNATURE_PART = "signature";
    private static final String EXPECTED_VERSION_FIELD = "expected_version_number";
    /** The FORMER name part: since 16G PR 3 an unexpected part like any other. */
    private static final String ACCEPTED_NAME_FIELD = "accepted_customer_name";

    // A real, decodable 1x1 PNG: D.8 embeds the uploaded bytes in the signed PDF.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // ---- Response codes and exact messages ----
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String VALIDATION_FAILED_MESSAGE = "One or more fields are invalid.";
    private static final String REQUIRED = "Required.";
    private static final String AT_MOST_ONCE = "Must appear at most once.";
    private static final String POSITIVE_INTEGER = "Must be a positive integer.";
    private static final String NOT_ALLOWED = "Not allowed.";
    private static final String STALE_CODE = "INVOICE_VERSION_STALE";
    private static final String STALE_MESSAGE = "Invoice updated, please refresh";
    private static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    private static final String ORDER_NOT_FOUND_MESSAGE = "Order not found.";
    private static final String INVOICE_REQUIRED = "INVOICE_REQUIRED";
    private static final String INVOICE_REQUIRED_MESSAGE = "An invoice is required for this action.";
    private static final String ALREADY_ACCEPTED = "INVOICE_ALREADY_ACCEPTED";
    private static final String ALREADY_ACCEPTED_MESSAGE =
            "This invoice has already been accepted. Use Re-send to email it again.";
    private static final String NAME_REQUIRED = "ACCEPTED_CUSTOMER_NAME_REQUIRED";
    private static final String NAME_REQUIRED_MESSAGE = "Accepted customer name is required.";
    private static final String SIGNATURE_REQUIRED = "SIGNATURE_REQUIRED";
    private static final String SIGNATURE_REQUIRED_MESSAGE = "A customer signature is required to accept the invoice.";
    private static final String SIGNATURE_INVALID = "SIGNATURE_INVALID";
    private static final String SIGNATURE_INVALID_MESSAGE = "Signature must be a PNG image no larger than 2 MB.";
    private static final String EMAIL_INVALID = "CUSTOMER_EMAIL_INVALID";
    private static final String EMAIL_INVALID_MESSAGE = "Customer email is not a valid email address.";
    private static final String UNAUTHORIZED = "UNAUTHORIZED";
    private static final String UNAUTHORIZED_MESSAGE = "Authentication required.";
    private static final String FORBIDDEN = "FORBIDDEN";
    private static final String FORBIDDEN_MESSAGE = "Access denied.";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String BUSINESS_NOT_FOUND_MESSAGE = "Business not found.";
    private static final String ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";

    // ---- PDF text ----
    /** How the invoice PDF prints the issued / due dates and the acceptance caption's timestamp. */
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);
    /** The "Issued / Due" eyebrow and the next column's "Sales Person / Store" eyebrow (uppercase by CSS, no spaces). */
    private static final String ISSUED_DUE_HEADING = "ISSUED/DUE";
    private static final String NEXT_COLUMN_HEADING = "SALESPERSON/STORE";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    /** The session's store: every D.8 under test is sent from here. */
    private Tenant home;
    /** The second store of the same business: its orders are out of the session's scope. */
    private Tenant homeOtherStore;
    /** Every order's proposed lay date (45 days ahead, so the due date is never today). */
    private LocalDate layDate;
    private final Map<Long, Integer> orderSequences = new HashMap<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        invoiceEmailSender.reset();
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class reads its files under the shared invoice/quote storage dir");
        layDate = LocalDate.now().plusDays(45);
        orderSequences.clear();
        seedHomeBusiness();
    }

    @AfterEach
    void tearDown() {
        // Singleton: never leak recorded messages or an armed failNextSend into another test or class.
        invoiceEmailSender.reset();
    }

    // ================================================================
    // Value validation (contract section 5.3)
    // ================================================================

    /**
     * {@code expected_version_number} missing, repeated or not a positive integer: 400
     * {@code VALIDATION_FAILED} with exactly ONE detail on that field and the exact message, and nothing
     * written. Missing ({@code Required.}) includes a request that carries other, unexpected parts (the
     * value is checked before the allowlist, so no second detail appears) and a request whose only carrier
     * is a FILE part named {@code expected_version_number} (a file part is not the form field). Repeated
     * ({@code Must appear at most once.}) is checked before the value, so a valid and an invalid value
     * together are still "repeated". Blank, zero, negative, non-numeric, fractional, space-padded and
     * beyond-64-bit values are {@code Must be a positive integer.}. The same request with the current
     * version then signs (positive control).
     */
    @Test
    void expectedVersionNumber_missingRepeatedOrNotAPositiveInteger_returns400WithOneDetailOnTheField_nothingWritten()
            throws Exception {
        long orderId = invoicedOrder(home);
        Assertions.assertEquals(1, currentVersion(orderId), "fixture: v1 is the current, unsigned version");

        assertOneDetail(orderId, accept(orderId, pngSignature()), EXPECTED_VERSION_FIELD, REQUIRED, "missing");
        assertOneDetail(orderId, accept(orderId, pngSignature(), field(ACCEPTED_NAME_FIELD, FROZEN_NAME),
                        file(photoPart())),
                EXPECTED_VERSION_FIELD, REQUIRED, "missing, with unexpected parts too");
        assertOneDetail(orderId, accept(orderId, pngSignature(), file(new MockMultipartFile(EXPECTED_VERSION_FIELD,
                        "version.txt", "text/plain", "1".getBytes(StandardCharsets.UTF_8)))),
                EXPECTED_VERSION_FIELD, REQUIRED, "only a FILE part named expected_version_number");

        for (String[] values : List.of(new String[] {"1", "1"}, new String[] {"1", "abc"}, new String[] {"abc", "1"})) {
            assertOneDetail(orderId, accept(orderId, version(values), pngSignature()),
                    EXPECTED_VERSION_FIELD, AT_MOST_ONCE, "repeated " + Arrays.toString(values));
        }

        for (String value : List.of("", " ", "0", "-0", "-1", "abc", "1.0", " 1", "1 ", "0x1", "1e0",
                "99999999999999999999", "9223372036854775808")) {
            assertOneDetail(orderId, accept(orderId, version(value), pngSignature()),
                    EXPECTED_VERSION_FIELD, POSITIVE_INTEGER, "value '" + value + "'");
        }

        assertSigns(orderId, "1", "positive control: the current version");
    }

    /**
     * Only the multipart FORM FIELD is the precondition (contract §5.3: "exactly one
     * {@code expected_version_number} form field"; the conventions take no query-string data on a
     * mutation). The servlet container merges URL query-string parameters into the same parameter lists,
     * so the service takes them out again: a query-string {@code expected_version_number} on its own leaves
     * the form field missing ({@code Required.}, also when the name is percent-encoded); beside a valid
     * form field it never makes the field "repeated" and is refused by the allowlist ({@code Not allowed.})
     * after the stale check, which compares the FORM value only (a matching query copy never rescues a
     * stale form value, and a different query value never makes a current form value stale); any other
     * query-string parameter is {@code Not allowed.} too. Nothing is written by any of these, and the form
     * field alone then signs.
     */
    @Test
    void expectedVersionNumber_onlyTheMultipartFormFieldCounts_aQueryStringCopyIsNeverTheCarrier_andIsRefused()
            throws Exception {
        long orderId = invoicedOrder(home);
        Assertions.assertEquals(1, currentVersion(orderId), "fixture: v1 is the current, unsigned version");

        assertOneDetail(orderId, acceptWithQuery(orderId, EXPECTED_VERSION_FIELD + "=1", pngSignature()),
                EXPECTED_VERSION_FIELD, REQUIRED, "query string only, no form field");
        assertOneDetail(orderId, acceptWithQuery(orderId, "expected%5Fversion%5Fnumber=1", pngSignature()),
                EXPECTED_VERSION_FIELD, REQUIRED, "a percent-encoded query-string name only, no form field");
        assertOneDetail(orderId, acceptWithQuery(orderId, EXPECTED_VERSION_FIELD + "=1", version("1"), pngSignature()),
                EXPECTED_VERSION_FIELD, NOT_ALLOWED, "the same value in the query string and the form field");
        assertOneDetail(orderId, acceptWithQuery(orderId, EXPECTED_VERSION_FIELD + "=7", version("1"), pngSignature()),
                EXPECTED_VERSION_FIELD, NOT_ALLOWED, "a different query value beside the current form value");
        assertStale(orderId, acceptWithQuery(orderId, EXPECTED_VERSION_FIELD + "=1", version("2"), pngSignature()),
                "a stale form value with a matching query copy is still stale");
        assertOneDetail(orderId, acceptWithQuery(orderId, "note=x", version("1"), pngSignature()),
                "note", NOT_ALLOWED, "another query-string parameter");

        assertSigns(orderId, "1", "positive control: the form field alone");
    }

    /**
     * The value is parsed by the {@code orderId} rule ({@code Long.parseLong}, then {@code > 0}), so a
     * leading zero or plus sign names the version its digits name: {@code "01"} signs the current v1,
     * {@code "+2"} then names the now-current, signed v2 (the already-accepted 409, neither a 400 nor a
     * stale 409), and {@code "0001"} names the old v1 (stale).
     */
    @Test
    void expectedVersionNumber_isParsedLikeOrderId_aLeadingZeroOrPlusSignNamesTheVersionItsDigitsName() throws Exception {
        long orderId = invoicedOrder(home);

        assertSigns(orderId, "01", "'01' names the current v1");
        assertRefused(orderId, accept(orderId, version("+2"), pngSignature()), 409, ALREADY_ACCEPTED,
                ALREADY_ACCEPTED_MESSAGE, "'+2' names the current, signed v2");
        assertStale(orderId, accept(orderId, version("0001"), pngSignature()), "'0001' names the old v1");
    }

    // ================================================================
    // Stale rejection (contract sections 5.2 and 5.4 step 5)
    // ================================================================

    /**
     * Only the CURRENT version matches. This order's current version N was created by a payment; the other
     * order of the same store is at a version this order never had. Each of these is 409
     * {@code INVOICE_VERSION_STALE} with the exact message and no details, and writes nothing (neither on
     * this order nor on the other): N-1 (the version the payment replaced), N+1 (a version that does not
     * exist yet), the other order's current version, a forged {@code Long.MAX_VALUE}, and a forged
     * {@code 2^32 + N} (equal to N only if the value were truncated to an int). This order's current version
     * sent to the other order is stale too: the value never selects a row of any order. Then N signs (N+1),
     * N itself is stale from then on, and the other order still signs with its own current version.
     */
    @Test
    void staleExpectedVersion_oldFutureForgedOrAnotherOrdersVersion_returns409InvoiceVersionStale_nothingWritten_onlyTheCurrentSigns()
            throws Exception {
        long orderId = invoicedOrder(home);
        recordCashPayment(orderId, "30.00");
        int current = currentVersion(orderId);
        Assertions.assertEquals(2, current, "fixture: the payment appended the current version");
        assertMoney("30.00", invoiceRow(invoiceId(orderId, current)).get("total_paid"),
                "fixture: the current version is the payment's version");

        long otherOrderId = invoicedOrder(home);
        long otherPaymentId = recordCashPayment(otherOrderId, "30.00");
        voidPayment(otherOrderId, otherPaymentId);
        recordCashPayment(otherOrderId, "20.00");
        int otherCurrent = currentVersion(otherOrderId);
        Assertions.assertEquals(4, otherCurrent, "fixture: the other order's current version");
        Assertions.assertTrue(otherCurrent > current + 1,
                "fixture: the other order's current version was never a version of this order and is not N+1");
        Footprint otherOrderBefore = footprint(otherOrderId);

        Map<String, Long> staleValues = new LinkedHashMap<>();
        staleValues.put("N-1, the version the payment replaced", (long) current - 1);
        staleValues.put("N+1, a version that does not exist yet", (long) current + 1);
        staleValues.put("another order's current version", (long) otherCurrent);
        staleValues.put("a forged Long.MAX_VALUE", Long.MAX_VALUE);
        staleValues.put("a forged 2^32 + N (N only if truncated to an int)", (1L << 32) + current);
        for (Map.Entry<String, Long> stale : staleValues.entrySet()) {
            assertStale(orderId, accept(orderId, version(String.valueOf(stale.getValue())), pngSignature()),
                    stale.getKey());
        }
        assertNothingWritten(otherOrderId, otherOrderBefore, "the other order, after the stale requests to this one");

        assertStale(otherOrderId, accept(otherOrderId, version(String.valueOf(current)), pngSignature()),
                "this order's current version sent to the other order");

        assertSigns(orderId, String.valueOf(current), "positive control: N, the current version");
        assertStale(orderId, accept(orderId, version(String.valueOf(current)), pngSignature()),
                "N again, now that the signing version N+1 is current");
        assertSigns(otherOrderId, String.valueOf(otherCurrent), "the other order's own current version");
    }

    // ================================================================
    // Order of checks (contract section 5.4)
    // ================================================================

    /**
     * Steps 1 to 3 come before the value. The guard answers whatever the request holds: no session 401, a
     * session with no selected store 403, another business's slug with this session 404 {@code NOT_FOUND}
     * (no existence leak). A non-positive or non-numeric {@code orderId}: one detail on {@code order_id}
     * only. An order of the session business's
     * OTHER store, an order of ANOTHER business (both with a current v1) and an order id that does not exist:
     * 404 {@code ORDER_NOT_FOUND} for a missing, invalid, repeated or even matching value, with a name part
     * or without a signature. An in-scope order with NO invoice (and an app-invalid saved email): 422
     * {@code INVOICE_REQUIRED} for every one of those values. Nothing is written by any of them.
     */
    @Test
    void precedence_theGuardTheScopedOrderAndAnExistingInvoiceAreCheckedBeforeTheVersionValue() throws Exception {
        Tenant otherBusiness = seedOtherBusiness();
        long otherStoreOrderId = invoicedOrder(homeOtherStore);
        long otherBusinessOrderId = invoicedOrder(otherBusiness);
        long noInvoiceOrderId = invoiceReadyOrder(home, APP_INVALID_EMAIL);
        long missingOrderId = unusedOrderId();
        Assertions.assertEquals(1, currentVersion(otherStoreOrderId), "fixture: the other store's order has v1");
        Assertions.assertEquals(1, currentVersion(otherBusinessOrderId), "fixture: the other business's order has v1");
        Assertions.assertEquals(0, invoiceCount(noInvoiceOrderId), "fixture: an in-scope order with no invoice");

        Map<String, Part[]> requests = new LinkedHashMap<>();
        requests.put("missing", new Part[] {pngSignature()});
        requests.put("'abc'", new Part[] {version("abc"), pngSignature()});
        requests.put("'0'", new Part[] {version("0"), pngSignature()});
        requests.put("repeated", new Part[] {version("1", "1"), pngSignature()});
        requests.put("'1', the out-of-scope orders' own current version", new Part[] {version("1"), pngSignature()});
        requests.put("'2'", new Part[] {version("2"), pngSignature()});
        requests.put("'1' with a name part and no signature",
                new Part[] {version("1"), field(ACCEPTED_NAME_FIELD, FROZEN_NAME)});

        // 1. The guard, then the orderId: before any order is read.
        assertRefused(noInvoiceOrderId, acceptWithoutSession(noInvoiceOrderId, version("abc"), pngSignature()),
                401, UNAUTHORIZED, UNAUTHORIZED_MESSAGE, "no session");
        assertRefused(noInvoiceOrderId, acceptAs(home, sessionWithoutStore(), noInvoiceOrderId, version("abc"),
                pngSignature()), 403, FORBIDDEN, FORBIDDEN_MESSAGE, "a session with no selected store");
        assertRefused(otherBusinessOrderId, acceptAs(otherBusiness, session(home), otherBusinessOrderId,
                version("abc"), pngSignature()), 404, NOT_FOUND, BUSINESS_NOT_FOUND_MESSAGE,
                "the other business's slug with the home session");
        for (String badOrderId : List.of("0", "-3", "abc")) {
            assertOneDetail(noInvoiceOrderId, accept(badOrderId, version("abc"), pngSignature()),
                    "order_id", POSITIVE_INTEGER, "orderId '" + badOrderId + "'");
        }

        // 2. The scoped order: out of scope or missing -> 404, whatever the version value.
        Map<String, Long> outOfScope = new LinkedHashMap<>();
        outOfScope.put("an order of the session business's other store", otherStoreOrderId);
        outOfScope.put("an order of another business", otherBusinessOrderId);
        outOfScope.put("an order id that does not exist", missingOrderId);
        for (Map.Entry<String, Long> order : outOfScope.entrySet()) {
            for (Map.Entry<String, Part[]> request : requests.entrySet()) {
                assertRefused(order.getValue(), accept(order.getValue(), request.getValue()), 404, ORDER_NOT_FOUND,
                        ORDER_NOT_FOUND_MESSAGE, order.getKey() + ", version " + request.getKey());
            }
        }

        // 3. No current invoice -> 422 before the value is looked at.
        for (Map.Entry<String, Part[]> request : requests.entrySet()) {
            assertRefused(noInvoiceOrderId, accept(noInvoiceOrderId, request.getValue()), 422, INVOICE_REQUIRED,
                    INVOICE_REQUIRED_MESSAGE, "no invoice, version " + request.getKey());
        }

        Assertions.assertNull(invoiceRow(invoiceId(otherStoreOrderId, 1)).get("accepted_at"),
                "the other store's order stays unsigned");
        Assertions.assertNull(invoiceRow(invoiceId(otherBusinessOrderId, 1)).get("accepted_at"),
                "the other business's order stays unsigned");
    }

    /**
     * On a SIGNED current version (v2, signed from v1): the value is validated first (missing, invalid and
     * repeated are 400, never a 409), then compared (the version it signed and a future one are stale), and
     * only the matching value reaches the already-accepted check (409 {@code INVOICE_ALREADY_ACCEPTED} with
     * the default message), which also comes before the part allowlist and the signature check.
     */
    @Test
    void precedence_onASignedCurrentVersion_valueValidationThenStaleThenAlreadyAccepted_beforeThePartChecks()
            throws Exception {
        long orderId = invoicedOrder(home);
        assertSigns(orderId, "1", "fixture: sign v1");
        int current = currentVersion(orderId);
        Assertions.assertEquals(2, current, "fixture: the signing version is current");
        Assertions.assertNotNull(invoiceRow(invoiceId(orderId, current)).get("accepted_at"),
                "fixture: the current version is signed");

        assertOneDetail(orderId, accept(orderId, pngSignature()), EXPECTED_VERSION_FIELD, REQUIRED,
                "signed current, missing value");
        assertOneDetail(orderId, accept(orderId, version("abc"), pngSignature()), EXPECTED_VERSION_FIELD,
                POSITIVE_INTEGER, "signed current, 'abc'");
        assertOneDetail(orderId, accept(orderId, version("2", "2"), pngSignature()), EXPECTED_VERSION_FIELD,
                AT_MOST_ONCE, "signed current, repeated");
        assertStale(orderId, accept(orderId, version("1"), pngSignature()), "signed current, the version it signed");
        assertStale(orderId, accept(orderId, version("3"), pngSignature()), "signed current, a future version");
        assertRefused(orderId, accept(orderId, version("2"), pngSignature()), 409, ALREADY_ACCEPTED,
                ALREADY_ACCEPTED_MESSAGE, "signed current, the matching version");
        assertRefused(orderId, accept(orderId, version("2"), field(ACCEPTED_NAME_FIELD, FROZEN_NAME),
                        field("note", "Leave at the door")),
                409, ALREADY_ACCEPTED, ALREADY_ACCEPTED_MESSAGE,
                "signed current, the matching version with unexpected parts and no signature");
    }

    /**
     * A stale value (the version the last payment replaced) is refused with the stale 409 before every later
     * check could answer: with an {@code accepted_customer_name} part, with no signature, with a non-PNG
     * signature, with a blank and with a NULL frozen name on the current version, with an app-invalid saved
     * email, and with all of those at once. Nothing is written by any of them.
     */
    @Test
    void precedence_aStaleVersionIsRefusedBeforeTheNamePartTheFrozenNameTheSignatureAndTheEmailGate() throws Exception {
        long orderId = invoicedOrder(home);
        recordCashPayment(orderId, "30.00");
        int current = currentVersion(orderId);
        long currentId = invoiceId(orderId, current);
        String stale = String.valueOf(current - 1);

        assertStale(orderId, accept(orderId, version(stale), pngSignature(), field(ACCEPTED_NAME_FIELD, FROZEN_NAME)),
                "stale + an accepted_customer_name part");
        assertStale(orderId, accept(orderId, version(stale)), "stale + no signature");
        assertStale(orderId, accept(orderId, version(stale), file(jpegSignature())), "stale + a non-PNG signature");
        setFrozenName(currentId, "   ");
        assertStale(orderId, accept(orderId, version(stale), pngSignature()), "stale + a blank frozen name");
        setFrozenName(currentId, null);
        assertStale(orderId, accept(orderId, version(stale), pngSignature()), "stale + a NULL frozen name");
        setCustomerEmail(orderId, APP_INVALID_EMAIL);
        assertStale(orderId, accept(orderId, version(stale), pngSignature()), "stale + an app-invalid saved email");
        assertStale(orderId, accept(orderId, version(stale), field(ACCEPTED_NAME_FIELD, ""), file(photoPart())),
                "stale + every later refusal at once");
    }

    /**
     * With the MATCHING version the remaining checks answer in order. The current version carries a blank
     * frozen name and the saved email is app-invalid: an extra form field and an extra file part are each a
     * single {@code Not allowed.} detail (the allowlist answers before the frozen name, the signature and
     * the email), and together they give two details, the file part first; with no extra part the blank
     * frozen name gives 422 {@code ACCEPTED_CUSTOMER_NAME_REQUIRED} although the signature is missing too.
     * Once the version carries a frozen name, a non-PNG signature (400 {@code SIGNATURE_INVALID}), an empty
     * one and a missing one (422 {@code SIGNATURE_REQUIRED}) are refused before the invalid email; a valid
     * signature then meets the email gate (422 {@code CUSTOMER_EMAIL_INVALID}); and with a valid saved email
     * the same request signs. Nothing is written by any refusal.
     */
    @Test
    void precedence_withTheMatchingVersion_thePartAllowlistThenTheFrozenNameThenTheSignatureThenTheEmailGate()
            throws Exception {
        long orderId = invoicedOrder(home);
        long currentId = invoiceId(orderId, 1);
        setFrozenName(currentId, "   ");
        setCustomerEmail(orderId, APP_INVALID_EMAIL);

        assertOneDetail(orderId, accept(orderId, version("1"), field("note", "Leave at the door")), "note",
                NOT_ALLOWED, "an extra form field + a blank frozen name + no signature + an invalid email");
        assertOneDetail(orderId, accept(orderId, version("1"), file(photoPart())), "photo", NOT_ALLOWED,
                "an extra file part + a blank frozen name + no signature + an invalid email");
        JsonNode both = assertRefused(orderId, accept(orderId, version("1"), field("note", "Leave at the door"),
                file(photoPart())), 400, VALIDATION_FAILED, VALIDATION_FAILED_MESSAGE, "an extra form field and file part");
        JsonNode details = both.path("details");
        Assertions.assertEquals(2, details.size(), () -> "one detail per offending part: " + both);
        Assertions.assertEquals("photo", details.get(0).path("field").asText(), "file parts are reported first");
        Assertions.assertEquals(NOT_ALLOWED, details.get(0).path("message").asText());
        Assertions.assertEquals("note", details.get(1).path("field").asText(), "then form fields");
        Assertions.assertEquals(NOT_ALLOWED, details.get(1).path("message").asText());

        assertRefused(orderId, accept(orderId, version("1")), 422, NAME_REQUIRED, NAME_REQUIRED_MESSAGE,
                "a blank frozen name + no signature + an invalid email");

        setFrozenName(currentId, FROZEN_NAME);
        assertRefused(orderId, accept(orderId, version("1"), file(jpegSignature())), 400, SIGNATURE_INVALID,
                SIGNATURE_INVALID_MESSAGE, "a frozen name + a non-PNG signature + an invalid email");
        assertRefused(orderId, accept(orderId, version("1"), file(emptySignature())), 422, SIGNATURE_REQUIRED,
                SIGNATURE_REQUIRED_MESSAGE, "a frozen name + an empty signature + an invalid email");
        assertRefused(orderId, accept(orderId, version("1")), 422, SIGNATURE_REQUIRED, SIGNATURE_REQUIRED_MESSAGE,
                "a frozen name + no signature + an invalid email");
        assertRefused(orderId, accept(orderId, version("1"), pngSignature()), 422, EMAIL_INVALID,
                EMAIL_INVALID_MESSAGE, "a frozen name + a valid signature + an invalid email");

        setCustomerEmail(orderId, CUSTOMER_EMAIL);
        assertSigns(orderId, "1", "positive control: the same request once the saved email is valid");
    }

    // ================================================================
    // The removed name part and the frozen accepted name (contract section 5.4 steps 7 and 8)
    // ================================================================

    /**
     * {@code accepted_customer_name} is now an unexpected part: with the frozen name itself, another name, an
     * empty value, a blank value, repeated, or as a FILE part, it is refused with 400 and exactly one detail
     * ({@code accepted_customer_name}, {@code Not allowed.}), and nothing is written. No name part is needed:
     * the plain request signs and the accepted name is v1's frozen name.
     */
    @Test
    void acceptedCustomerNamePart_withAValueEmptyBlankOrRepeated_returns400NotAllowed_nothingWritten_noNamePartIsNeeded()
            throws Exception {
        long orderId = invoicedOrder(home);
        Assertions.assertEquals(FROZEN_NAME, frozenNameOf(invoiceId(orderId, 1)), "fixture: v1 froze the saved name");

        Map<String, String[]> nameParts = new LinkedHashMap<>();
        nameParts.put("the frozen name itself", new String[] {FROZEN_NAME});
        nameParts.put("another name", new String[] {"Morgan Hale"});
        nameParts.put("an empty value", new String[] {""});
        nameParts.put("a blank value", new String[] {"   "});
        nameParts.put("repeated", new String[] {FROZEN_NAME, FROZEN_NAME});
        nameParts.put("repeated, a value and an empty one", new String[] {FROZEN_NAME, ""});
        for (Map.Entry<String, String[]> namePart : nameParts.entrySet()) {
            assertOneDetail(orderId, accept(orderId, version("1"), pngSignature(),
                            field(ACCEPTED_NAME_FIELD, namePart.getValue())),
                    ACCEPTED_NAME_FIELD, NOT_ALLOWED, "name part: " + namePart.getKey());
        }
        assertOneDetail(orderId, accept(orderId, version("1"), pngSignature(), file(new MockMultipartFile(
                        ACCEPTED_NAME_FIELD, "name.txt", "text/plain", FROZEN_NAME.getBytes(StandardCharsets.UTF_8)))),
                ACCEPTED_NAME_FIELD, NOT_ALLOWED, "a name FILE part");

        JsonNode signed = assertSigns(orderId, "1", "no name part");
        Assertions.assertEquals(FROZEN_NAME, signed.path("accepted_customer_name").asText(),
                "the accepted name is v1's frozen name");
        Assertions.assertEquals(FROZEN_NAME, invoiceRow(invoiceId(orderId, 2)).get("accepted_customer_name"),
                "stored as the accepted name");
    }

    /**
     * The current version's frozen name NULL, empty, spaces only, or tabs / newlines only: 422
     * {@code ACCEPTED_CUSTOMER_NAME_REQUIRED} with the default message and nothing written, although the live
     * saved customer still has a name (it never fills in). Once the version carries a frozen name the same
     * request signs.
     */
    @Test
    void frozenName_nullEmptyOrBlank_returns422AcceptedCustomerNameRequired_nothingWritten_theLiveNameNeverFillsIn()
            throws Exception {
        long orderId = invoicedOrder(home);
        long v1 = invoiceId(orderId, 1);
        Assertions.assertEquals(FROZEN_NAME, savedCustomerName(orderId), "fixture: the live saved customer has a name");

        for (String blank : Arrays.asList(null, "", "   ", "\t\n ")) {
            setFrozenName(v1, blank);
            String label = "frozen name " + (blank == null ? "NULL"
                    : "'" + blank.replace("\t", "\\t").replace("\n", "\\n") + "'");
            assertRefused(orderId, accept(orderId, version("1"), pngSignature()), 422, NAME_REQUIRED,
                    NAME_REQUIRED_MESSAGE, label);
        }

        setFrozenName(v1, FROZEN_NAME);
        assertSigns(orderId, "1", "positive control: the same version with a frozen name");
    }

    /**
     * A frozen name of 300 characters (twice the former request limit), different from the live saved
     * customer, IS the accepted name: the JSON {@code accepted_customer_name} and the appended row's
     * {@code invoice.accepted_customer_name} hold it in full (300 characters, untruncated), the signing
     * version copies it as its Invoice To name, and the signed PDF's caption carries it in full (whitespace
     * normalised, as the PDF wraps it between words), never the live name. The emailed PDF is that stored
     * PDF.
     */
    @Test
    void frozenName_of300Characters_unlikeTheLiveCustomer_isTheAcceptedName_storedReturnedAndCaptionedInFull()
            throws Exception {
        Assertions.assertEquals(300, FROZEN_LONG_NAME.length(), "fixture: a 300-character frozen name");
        long orderId = invoicedOrder(home);
        setFrozenName(invoiceId(orderId, 1), FROZEN_LONG_NAME);
        Assertions.assertNotEquals(FROZEN_LONG_NAME, savedCustomerName(orderId), "fixture: the live saved name differs");

        JsonNode signed = assertSigns(orderId, "1", "D.8 with a 300-character frozen name");

        Assertions.assertEquals(FROZEN_LONG_NAME, signed.path("accepted_customer_name").asText(),
                "JSON accepted_customer_name: the frozen name in full");
        Assertions.assertEquals(FROZEN_LONG_NAME, signed.path("customer_name_snapshot").asText(),
                "JSON customer_name_snapshot: copied verbatim");
        long v2 = invoiceId(orderId, 2);
        Map<String, Object> v2Row = invoiceRow(v2);
        Assertions.assertEquals(FROZEN_LONG_NAME, v2Row.get("accepted_customer_name"),
                "invoice.accepted_customer_name: the frozen name in full");
        Assertions.assertEquals(300, jdbcTemplate.queryForObject(
                        "SELECT char_length(accepted_customer_name) FROM invoice WHERE invoice_id = ?", Integer.class, v2),
                "the stored accepted name is untruncated");
        Assertions.assertEquals(FROZEN_LONG_NAME, v2Row.get("customer_name_snapshot"), "the frozen name is copied");

        byte[] signedPdf = pdfOnDisk(v2);
        assertAcceptedCaption(signedPdf, FROZEN_LONG_NAME, acceptedAt(v2), "the signed PDF");
        assertTextLacks(readingOrderText(signedPdf), "Accepted by " + savedCustomerName(orderId),
                "the signed PDF (the live name is never the caption)");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "D.8 emails once");
        Assertions.assertArrayEquals(signedPdf, sent.get(0).pdfBytes(), "the emailed PDF is the stored signed PDF");
    }

    /**
     * A non-blank frozen name with edge whitespace and a doubled inner space is the accepted name VERBATIM:
     * the D.8 JSON, the appended row and the D.3 read all hold exactly that string (never trimmed), the
     * signing version copies it as its Invoice To name, and the signed PDF's caption carries it.
     */
    @Test
    void frozenName_withSurroundingAndDoubledWhitespace_isStoredAndReturnedVerbatim_neverTrimmed() throws Exception {
        Assertions.assertNotEquals(PADDED_FROZEN_NAME.trim(), PADDED_FROZEN_NAME, "fixture: the name has edge whitespace");
        long orderId = invoicedOrder(home);
        setFrozenName(invoiceId(orderId, 1), PADDED_FROZEN_NAME);

        JsonNode signed = assertSigns(orderId, "1", "D.8 with a padded frozen name");

        Assertions.assertEquals(PADDED_FROZEN_NAME, signed.path("accepted_customer_name").asText(),
                "JSON accepted_customer_name: verbatim");
        Assertions.assertEquals(PADDED_FROZEN_NAME, signed.path("customer_name_snapshot").asText(),
                "JSON customer_name_snapshot: verbatim");
        long v2 = invoiceId(orderId, 2);
        Assertions.assertEquals(PADDED_FROZEN_NAME, invoiceRow(v2).get("accepted_customer_name"),
                "invoice.accepted_customer_name: verbatim, not trimmed");
        Assertions.assertEquals(PADDED_FROZEN_NAME, invoiceRow(v2).get("customer_name_snapshot"),
                "the Invoice To name is copied verbatim");
        Assertions.assertEquals(PADDED_FROZEN_NAME, getCurrent(orderId).path("accepted_customer_name").asText(),
                "D.3 returns the accepted name verbatim");
        assertAcceptedCaption(pdfOnDisk(v2), PADDED_FROZEN_NAME, acceptedAt(v2), "the signed PDF");
    }

    // ================================================================
    // The signing version's date and the email after the persist (contract sections 5.4 steps 11-12, 6)
    // ================================================================

    /**
     * Cross-day signing, in the test transaction: the current version v1 is dated D1, nine days ago (set by
     * SQL), and is signed today (D2). The appended v2 stores {@code invoice_date} = D1 and InvoiceDetail
     * returns D1; its stored signed PDF prints D1 (and the carried due date) in the Issued / Due block and
     * never D2 there, while the caption carries the D2 signing time; {@code accepted_at} is the signing time
     * (within the request window, on D2). The due date, details, money, words, terms and source are carried
     * from v1, v1 itself is untouched, the creator is the session user, and the emailed attachment is
     * byte-identical to the stored signed PDF.
     */
    @Test
    void crossDay_theSigningVersionKeepsTheCurrentVersionsInvoiceDate_itsPdfPrintsIt_acceptedAtIsTheSigningTime()
            throws Exception {
        long orderId = invoicedOrder(home);
        long v1 = invoiceId(orderId, 1);
        LocalDate d1 = LocalDate.now().minusDays(9);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET invoice_date = ? WHERE invoice_id = ?", Date.valueOf(d1), v1),
                "fixture: date the current version D1");
        clearJpaCache();
        Map<String, Object> v1Row = invoiceRow(v1);
        LocalDate dueDate = ((Date) v1Row.get("due_date")).toLocalDate();
        Assertions.assertEquals(layDate.minusDays(2), dueDate, "fixture: D.1 set the due date two days before the lay date");

        LocalDateTime signingStarted = LocalDateTime.now();
        JsonNode signed = assertSigns(orderId, "1", "cross-day D.8");
        LocalDateTime signingEnded = LocalDateTime.now();

        long v2 = invoiceId(orderId, 2);
        Map<String, Object> v2Row = invoiceRow(v2);
        Assertions.assertEquals(d1, ((Date) v2Row.get("invoice_date")).toLocalDate(),
                "the signing version keeps the current version's invoice_date D1");
        Assertions.assertEquals(d1.toString(), signed.path("invoice_date").asText(), "InvoiceDetail invoice_date = D1");

        LocalDateTime acceptedAt = acceptedAt(v2);
        Assertions.assertTrue(!acceptedAt.isBefore(signingStarted.minusSeconds(1))
                        && !acceptedAt.isAfter(signingEnded.plusSeconds(1)),
                () -> "accepted_at is the signing time: " + acceptedAt + " outside [" + signingStarted + ", "
                        + signingEnded + "]");
        LocalDate d2 = acceptedAt.toLocalDate();
        Assertions.assertTrue(!d2.isBefore(signingStarted.toLocalDate()) && !d2.isAfter(signingEnded.toLocalDate()),
                () -> "accepted_at is on the signing day: " + acceptedAt);
        Assertions.assertTrue(d1.isBefore(d2), "fixture: D1 is before the signing day D2");
        Assertions.assertEquals(acceptedAt.truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(signed.path("accepted_at").asText()), "InvoiceDetail accepted_at = the signing time");

        for (String carried : List.of("due_date", "details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst",
                "total_paid", "balance_due", "customer_name_snapshot", "customer_address_line1_snapshot",
                "customer_address_line2_snapshot", "terms_snapshot", "source_quote_version_id")) {
            Assertions.assertEquals(v1Row.get(carried), v2Row.get(carried), () -> "D.8 carries " + carried + " from v1");
        }
        Assertions.assertNotNull(v2Row.get("terms_snapshot"), "fixture: v1 froze the business's SOFT terms");
        Assertions.assertEquals(dueDate.toString(), signed.path("due_date").asText(), "InvoiceDetail due_date carried");
        Assertions.assertEquals(home.userId(), ((Number) v2Row.get("created_by_user_id")).longValue(),
                "the signing version's creator is the session user");
        Assertions.assertEquals(v1Row, invoiceRow(v1), "v1 is untouched (append-only)");

        byte[] storedPdf = pdfOnDisk(v2);
        String issuedDue = issuedDueBlock(storedPdf);
        Assertions.assertEquals(DISPLAY_DATE.format(d1) + "Due" + DISPLAY_DATE.format(dueDate), issuedDue,
                "the signed PDF prints D1 (and the due date) where the invoice date is printed");
        Assertions.assertFalse(issuedDue.contains(DISPLAY_DATE.format(d2)),
                () -> "the signing day D2 is never printed as the invoice date: " + issuedDue);
        assertAcceptedCaption(storedPdf, FROZEN_NAME, acceptedAt, "the signed PDF (the D2 signing time)");

        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "D.8 emails once");
        Assertions.assertEquals(2, sent.get(0).invoiceVersionNumber(), "the email carries the appended version");
        Assertions.assertArrayEquals(storedPdf, sent.get(0).pdfBytes(),
                "the emailed attachment is byte-identical to the stored signed PDF");
    }

    /**
     * Cross-day signing of a LATER version, in the test transaction, so the current version and the first
     * one are different rows with different dates. A D.7 payment appends v2, the current version; by SQL
     * the first version v1 is dated D0, twenty days ago, and v2 is dated D1, nine days ago; v2 is signed
     * today. The appended v3 stores {@code invoice_date} = D1, the CURRENT version's date, and InvoiceDetail
     * returns D1; its stored signed PDF prints D1 and v2's due date in the Issued / Due block, and neither
     * D0 nor today there. A signing version dated from the first, the earliest or a MIN version would show
     * D0, one dated today would show today. {@code accepted_at} is the signing time (within the request
     * window, after D1); v3 carries v2's due date, money (the payment's, unlike v1's), words, terms and
     * source; v1 and v2 are untouched; the emailed attachment is byte-identical to the stored v3 PDF.
     */
    @Test
    void crossDay_signingALaterVersion_keepsThatCurrentVersionsInvoiceDate_neverTheFirstVersionsNorToday_itsPdfPrintsIt()
            throws Exception {
        long orderId = invoicedOrder(home);
        recordCashPayment(orderId, "30.00");
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: the payment appended v2, the current version");
        long v1 = invoiceId(orderId, 1);
        long v2 = invoiceId(orderId, 2);
        LocalDate today = LocalDate.now();
        LocalDate d0 = today.minusDays(20);
        LocalDate d1 = today.minusDays(9);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET invoice_date = ? WHERE invoice_id = ?", Date.valueOf(d0), v1),
                "fixture: date the first version v1 D0");
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET invoice_date = ? WHERE invoice_id = ?", Date.valueOf(d1), v2),
                "fixture: date the current version v2 D1");
        clearJpaCache();
        Map<String, Object> v1Row = invoiceRow(v1);
        Map<String, Object> v2Row = invoiceRow(v2);
        assertMoney("0.00", v1Row.get("total_paid"), "fixture: v1 carries no payment");
        assertMoney("30.00", v2Row.get("total_paid"), "fixture: v2 is the payment's version");
        LocalDate v2DueDate = ((Date) v2Row.get("due_date")).toLocalDate();

        LocalDateTime signingStarted = LocalDateTime.now();
        JsonNode signed = assertSigns(orderId, "2", "cross-day D.8 of the payment version v2");
        LocalDateTime signingEnded = LocalDateTime.now();

        long v3 = invoiceId(orderId, 3);
        Map<String, Object> v3Row = invoiceRow(v3);
        Assertions.assertEquals(d1, ((Date) v3Row.get("invoice_date")).toLocalDate(),
                "the signing version v3 keeps the CURRENT version v2's invoice_date D1, never v1's D0");
        Assertions.assertEquals(d1.toString(), signed.path("invoice_date").asText(), "InvoiceDetail invoice_date = D1");

        LocalDateTime acceptedAt = acceptedAt(v3);
        Assertions.assertTrue(!acceptedAt.isBefore(signingStarted.minusSeconds(1))
                        && !acceptedAt.isAfter(signingEnded.plusSeconds(1)),
                () -> "accepted_at is the signing time: " + acceptedAt + " outside [" + signingStarted + ", "
                        + signingEnded + "]");
        Assertions.assertTrue(d1.isBefore(acceptedAt.toLocalDate()), "fixture: D1 is before the signing day");
        Assertions.assertEquals(acceptedAt.truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(signed.path("accepted_at").asText()), "InvoiceDetail accepted_at = the signing time");

        for (String carried : List.of("due_date", "details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst",
                "total_paid", "balance_due", "customer_name_snapshot", "customer_address_line1_snapshot",
                "customer_address_line2_snapshot", "terms_snapshot", "source_quote_version_id")) {
            Assertions.assertEquals(v2Row.get(carried), v3Row.get(carried),
                    () -> "D.8 carries " + carried + " from the current version v2");
        }
        Assertions.assertEquals(v1Row, invoiceRow(v1), "v1 is untouched (append-only)");
        Assertions.assertEquals(v2Row, invoiceRow(v2), "v2 is untouched (append-only)");

        byte[] storedPdf = pdfOnDisk(v3);
        String issuedDue = issuedDueBlock(storedPdf);
        Assertions.assertEquals(DISPLAY_DATE.format(d1) + "Due" + DISPLAY_DATE.format(v2DueDate), issuedDue,
                "the signed v3 PDF prints D1 and v2's due date where the invoice date is printed");
        Assertions.assertFalse(issuedDue.contains(DISPLAY_DATE.format(d0)),
                () -> "v1's date D0 is never printed as the invoice date: " + issuedDue);
        Set<LocalDate> signingDays = new TreeSet<>(List.of(today, signingStarted.toLocalDate(),
                signingEnded.toLocalDate(), acceptedAt.toLocalDate()));
        for (LocalDate signingDay : signingDays) {
            Assertions.assertFalse(issuedDue.contains(DISPLAY_DATE.format(signingDay)),
                    () -> "today " + signingDay + " is never printed as the invoice date: " + issuedDue);
        }
        assertAcceptedCaption(storedPdf, FROZEN_NAME, acceptedAt, "the signed v3 PDF (the signing time)");

        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "only D.8 emails (D.1 and the payment never do)");
        Assertions.assertEquals(3, sent.get(0).invoiceVersionNumber(), "the email carries the appended v3");
        Assertions.assertArrayEquals(storedPdf, sent.get(0).pdfBytes(),
                "the emailed attachment is byte-identical to the stored signed v3 PDF");
    }

    /**
     * Email success, in the test transaction. The saved email carries surrounding spaces (the DB check allows
     * them). D.8 returns 201 with the emailed message; exactly one email goes to the TRIMMED saved email,
     * with the exact subject and body, the appended version's own stored PDF and its stored file name
     * ({@code invoice-{order_number}-v2.pdf}), the order id and version 2, and no attempt fails. The success
     * stamp sets the appended row's {@code last_emailed_at} and the {@code sales_order.last_emailed_at}
     * mirror to the SAME non-null timestamp (within the request window, returned by InvoiceDetail); v1's
     * marker stays null.
     */
    @Test
    void emailSuccess_theAppendedVersionsPdfGoesToTheTrimmedSavedEmail_bothMarkersCarryTheSameStamp() throws Exception {
        long orderId = invoiceReadyOrder(home, PADDED_CUSTOMER_EMAIL);
        create(home, orderId);
        Assertions.assertEquals(PADDED_CUSTOMER_EMAIL, savedEmail(orderId), "fixture: the saved email keeps its spaces");
        Assertions.assertNotEquals(PADDED_CUSTOMER_EMAIL.trim(), PADDED_CUSTOMER_EMAIL, "fixture: the email is padded");
        String orderNumber = orderNumber(orderId);

        LocalDateTime started = LocalDateTime.now();
        JsonNode signed = assertSigns(orderId, "1", "D.8 with a padded saved email");
        LocalDateTime ended = LocalDateTime.now();

        long v2 = invoiceId(orderId, 2);
        Timestamp invoiceStamp = (Timestamp) invoiceRow(v2).get("last_emailed_at");
        Timestamp mirror = orderLastEmailedAt(orderId);
        Assertions.assertNotNull(invoiceStamp, "the appended row's last_emailed_at is stamped");
        Assertions.assertEquals(invoiceStamp, mirror, "sales_order.last_emailed_at equals the appended row's stamp");
        LocalDateTime stamp = invoiceStamp.toLocalDateTime();
        Assertions.assertTrue(!stamp.isBefore(started.minusSeconds(1)) && !stamp.isAfter(ended.plusSeconds(1)),
                () -> "the stamp is the send time: " + stamp + " outside [" + started + ", " + ended + "]");
        Assertions.assertEquals(stamp.truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(signed.path("last_emailed_at").asText()), "InvoiceDetail last_emailed_at = the stamp");
        Assertions.assertNull(invoiceRow(invoiceId(orderId, 1)).get("last_emailed_at"), "v1's marker stays null");

        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "exactly one email");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no failed attempt");
        InvoiceEmailRequest email = sent.get(0);
        Assertions.assertEquals(PADDED_CUSTOMER_EMAIL.trim(), email.recipientEmail(), "the trimmed saved email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), "subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + orderNumber + " from " + BUSINESS_NAME
                + " attached.\n\nThank you.", email.bodyText(), "body");
        Assertions.assertEquals("invoice-" + orderNumber + "-v2.pdf", email.pdfFileName(), "the appended version's file name");
        Assertions.assertEquals(storedPdfFileName(v2), email.pdfFileName(), "the file name stored for the appended PDF");
        Assertions.assertEquals(orderId, email.orderId(), "order id");
        Assertions.assertEquals(2, email.invoiceVersionNumber(), "the appended version");
        Assertions.assertArrayEquals(pdfOnDisk(v2), email.pdfBytes(), "the appended version's own stored PDF");
    }

    // ================================================================
    // LAID
    // ================================================================

    /**
     * D.8 stays allowed on a LAID order and the precondition applies unchanged: a stale value is still the
     * stale 409 with nothing written, and the matching version signs; the order stays LAID.
     */
    @Test
    void laidOrder_aStaleVersionIsStill409_theMatchingVersionSigns() throws Exception {
        long orderId = invoicedOrder(home);
        setOrderStatus(orderId, "LAID");

        assertStale(orderId, accept(orderId, version("2"), pngSignature()), "LAID + a future version");
        assertSigns(orderId, "1", "LAID + the matching version");
        Assertions.assertEquals("LAID", orderStatus(orderId), "the order stays LAID");
    }

    // ================================================================
    // Fixtures - the self-seeded tenants and orders
    // ================================================================

    /** One store context of a seeded business: what a session and an order of that store use. */
    private record Tenant(String slug, long businessId, int storeId, String storeCode, long userId,
                          String salespersonCode) {
    }

    /** The home business: two stores, one salesperson with access to both; the session uses the first. */
    private void seedHomeBusiness() {
        long businessId = insertBusiness(SLUG, BUSINESS_NAME);
        int storeId = insertStore(businessId, "Larkspur Showroom", STORE_CODE);
        int otherStoreId = insertStore(businessId, "Larkspur Warehouse", OTHER_STORE_CODE);
        long userId = insertUser(businessId, "Quinn", "Vale", SALESPERSON_CODE, "quinn.vale@larkspur.example.com");
        grantStoreAccess(businessId, userId, storeId);
        grantStoreAccess(businessId, userId, otherStoreId);
        home = new Tenant(SLUG, businessId, storeId, STORE_CODE, userId, SALESPERSON_CODE);
        homeOtherStore = new Tenant(SLUG, businessId, otherStoreId, OTHER_STORE_CODE, userId, SALESPERSON_CODE);
        clearJpaCache();
    }

    /** A second business with one store and one salesperson (its orders are out of the home session's scope). */
    private Tenant seedOtherBusiness() {
        long businessId = insertBusiness(OTHER_BUSINESS_SLUG, OTHER_BUSINESS_NAME);
        int storeId = insertStore(businessId, "Wrenfield Showroom", OTHER_BUSINESS_STORE_CODE);
        long userId = insertUser(businessId, "Rory", "Wren", OTHER_BUSINESS_SALESPERSON_CODE,
                "rory.wren@wrenfield.example.com");
        grantStoreAccess(businessId, userId, storeId);
        clearJpaCache();
        return new Tenant(OTHER_BUSINESS_SLUG, businessId, storeId, OTHER_BUSINESS_STORE_CODE, userId,
                OTHER_BUSINESS_SALESPERSON_CODE);
    }

    private long insertBusiness(String slug, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, '51 824 753 556', ?, ?) RETURNING business_id",
                Long.class, name, slug, SOFT_TERMS, HARD_TERMS);
    }

    private int insertStore(long businessId, String name, String storeCode) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, ?, ?, '0290001234', 'showroom@larkspur.example.com', '1 Showroom Way', "
                        + " 'Burwood', 'NSW', '2134') RETURNING store_id",
                Integer.class, businessId, name, storeCode);
    }

    private long insertUser(long businessId, String firstName, String lastName, String salespersonCode, String email) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'not-a-login-hash') RETURNING user_id",
                Long.class, businessId, firstName, lastName, salespersonCode, email);
    }

    private void grantStoreAccess(long businessId, long userId, int storeId) {
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
    }

    /**
     * An invoice-ready SOFT order of {@code tenant}'s store with no invoice yet: every D.1 gate passes when
     * {@code customerEmail} is app-valid (customer "Jordan Wilson", INSTALLATION and BILLING rows, details of
     * sale, a CONFIRMED lay date 45 days ahead, one priced charge line of 100.00 ex / 40.00 cost, so 110.00
     * inc).
     */
    private long invoiceReadyOrder(Tenant tenant, String customerEmail) {
        int sequence = orderSequences.merge(tenant.businessId(), 1, Integer::sum);
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        String orderNumber = tenant.storeCode() + "." + tenant.salespersonCode() + "." + String.format("%05d", sequence);
        long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 42, 2026, ?, ?, "
                        + " 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, tenant.businessId(), tenant.storeId(), tenant.userId(), sequence, orderNumber,
                DETAILS_OF_SALE, Date.valueOf(layDate));
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) VALUES (?, ?, ?, ?, ?)",
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, customerEmail, "0412345678");
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '7', 'Install Lane', 'Ryde', 'NSW', '2112')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Oxford Street', 'Paddington', 'NSW', '2021')",
                orderId);
        String chargeCode = "LKSC" + sequence;
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Larkspur version test charge', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, tenant.storeId(), chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Larkspur version test charge', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        clearJpaCache();
        return orderId;
    }

    /** An invoice-ready order of {@code tenant}'s store with its invoice v1 created through D.1 (unsigned). */
    private long invoicedOrder(Tenant tenant) throws Exception {
        long orderId = invoiceReadyOrder(tenant, CUSTOMER_EMAIL);
        create(tenant, orderId);
        Assertions.assertEquals(FROZEN_NAME, frozenNameOf(invoiceId(orderId, 1)), "fixture: D.1 froze the saved name");
        return orderId;
    }

    /** Plant one version's frozen Invoice To name (the D.8 accepted name), then clear JPA. */
    private void setFrozenName(long invoiceId, String name) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice SET customer_name_snapshot = ? WHERE invoice_id = ?", name, invoiceId),
                "fixture: set exactly one version's frozen name");
        clearJpaCache();
    }

    private void setCustomerEmail(long orderId, String email) {
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

    /** An order id above every existing one (state-derived): no order has it. */
    private long unusedOrderId() {
        return jdbcTemplate.queryForObject("SELECT COALESCE(MAX(order_id), 0) + 1000000 FROM sales_order", Long.class);
    }

    /**
     * Detach every hydrated entity. The MockMvc calls of a test share one transaction and persistence
     * context, so after a raw JDBC write a later JPA read (order, customer, business) would otherwise return
     * the stale cached entity.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    // ================================================================
    // Requests
    // ================================================================

    /** One addition to a D.8 multipart: a form field or a file part. */
    @FunctionalInterface
    private interface Part {
        void addTo(MockMultipartHttpServletRequestBuilder request);
    }

    /** The form field {@code expected_version_number} with these values (more than one = repeated). */
    private static Part version(String... values) {
        return request -> request.param(EXPECTED_VERSION_FIELD, values);
    }

    private static Part field(String name, String... values) {
        return request -> request.param(name, values);
    }

    private static Part file(MockMultipartFile file) {
        return request -> request.file(file);
    }

    /** The valid signature part: a real PNG declared {@code image/png}. */
    private static Part pngSignature() {
        return file(new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", ONE_PIXEL_PNG));
    }

    private static MockMultipartFile jpegSignature() {
        return new MockMultipartFile(SIGNATURE_PART, "signature.jpg", "image/jpeg", ONE_PIXEL_PNG);
    }

    private static MockMultipartFile emptySignature() {
        return new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", new byte[0]);
    }

    private static MockMultipartFile photoPart() {
        return new MockMultipartFile("photo", "photo.png", "image/png", ONE_PIXEL_PNG);
    }

    private MockHttpSession session(Tenant tenant) {
        MockHttpSession session = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        session.setAttribute("user_id", Long.valueOf(tenant.userId()));
        session.setAttribute("business_id", Long.valueOf(tenant.businessId()));
        session.setAttribute("store_id", Integer.valueOf(tenant.storeId()));
        return session;
    }

    private static String orderUrl(Tenant tenant, Object orderId) {
        return "/api/v1/" + tenant.slug() + "/orders/" + orderId;
    }

    /** D.8 from the HOME session carrying exactly {@code parts} (each test adds the parts its case needs). */
    private RequestBuilder accept(Object orderId, Part... parts) {
        return acceptAs(home, session(home), orderId, parts);
    }

    /**
     * D.8 from the HOME session with {@code rawQuery} as the URL query string (sent as given, already
     * encoded: the {@link URI} overload is used so MockMvc does not encode it again) and {@code parts}.
     */
    private RequestBuilder acceptWithQuery(Object orderId, String rawQuery, Part... parts) {
        MockMultipartHttpServletRequestBuilder request = multipart(
                URI.create(orderUrl(home, orderId) + "/invoices/current/accept?" + rawQuery));
        for (Part part : parts) {
            part.addTo(request);
        }
        request.session(session(home));
        return request;
    }

    /** D.8 with no session at all. */
    private RequestBuilder acceptWithoutSession(Object orderId, Part... parts) {
        return acceptAs(home, null, orderId, parts);
    }

    /** D.8 under {@code urlTenant}'s slug with {@code session} (none when null) carrying exactly {@code parts}. */
    private RequestBuilder acceptAs(Tenant urlTenant, MockHttpSession session, Object orderId, Part... parts) {
        MockMultipartHttpServletRequestBuilder request =
                multipart(orderUrl(urlTenant, orderId) + "/invoices/current/accept");
        for (Part part : parts) {
            part.addTo(request);
        }
        if (session != null) {
            request.session(session);
        }
        return request;
    }

    /** The home user and business with NO selected store. */
    private MockHttpSession sessionWithoutStore() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", Long.valueOf(home.userId()));
        session.setAttribute("business_id", Long.valueOf(home.businessId()));
        return session;
    }

    /** Perform a request and assert its status, with the response body in the failure message. */
    private MvcResult perform(RequestBuilder request, int expectedStatus, String label) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        Assertions.assertEquals(expectedStatus, result.getResponse().getStatus(),
                () -> label + ": unexpected status; body: " + bodyOf(result));
        return result;
    }

    /** D.1 Create (empty body) in {@code tenant}'s session: 201. */
    private void create(Tenant tenant, long orderId) throws Exception {
        perform(post(orderUrl(tenant, orderId) + "/invoices").session(session(tenant))
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "fixture: D.1 create");
    }

    /** D.7 CASH payment (appends a version, never emails): 201; returns the payment id. */
    private long recordCashPayment(long orderId, String amount) throws Exception {
        MvcResult result = perform(post(orderUrl(home, orderId) + "/payments").session(session(home))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"), 201, "fixture: D.7 payment");
        long paymentId = readJson(result).path("data").path("payment_transaction").path("payment_transaction_id").asLong();
        Assertions.assertTrue(paymentId > 0, "fixture: D.7 returns the payment id");
        return paymentId;
    }

    /** D.10 void (appends a version, never emails): 201. */
    private void voidPayment(long orderId, long paymentId) throws Exception {
        perform(post(orderUrl(home, orderId) + "/payments/" + paymentId + "/void").session(session(home)), 201,
                "fixture: D.10 void");
    }

    /** D.3 current invoice: 200; returns data.invoice. */
    private JsonNode getCurrent(long orderId) throws Exception {
        MvcResult result = perform(get(orderUrl(home, orderId) + "/invoices/current").session(session(home)), 200,
                "D.3 current");
        return readJson(result).path("data").path("invoice");
    }

    // ================================================================
    // Assertions - responses and "nothing written"
    // ================================================================

    /**
     * D.8 with {@code expectedVersion}, a valid PNG and no name part, which must SIGN: 201 with the emailed
     * message (the recording sender succeeds), data.invoice = the appended version (current + 1, now the
     * current one), signed, with the current version's frozen name, verbatim, as its accepted name. Returns
     * data.invoice.
     *
     * <p>It also proves the {@link Footprint} used by every "nothing written" check SEES a D.8 write: the same
     * footprint shows exactly one appended invoice row (the earlier rows untouched), the signature and PDF
     * {@code stored_file} rows and files in the order's directory, and one email, so a refusal whose footprint
     * is unchanged really wrote nothing.
     */
    private JsonNode assertSigns(long orderId, String expectedVersion, String label) throws Exception {
        int current = currentVersion(orderId);
        String frozenName = frozenNameOf(invoiceId(orderId, current));
        Footprint before = footprint(orderId);
        MvcResult result = perform(accept(orderId, version(expectedVersion), pngSignature()), 201, label);
        Footprint after = footprint(orderId);
        int rowsBefore = before.orderInvoices().size();
        Assertions.assertEquals(rowsBefore + 1, after.orderInvoices().size(), label + ": one invoice row is appended");
        Assertions.assertEquals(before.orderInvoices(), after.orderInvoices().subList(0, rowsBefore),
                label + ": the earlier versions are untouched");
        Assertions.assertEquals(before.allInvoices() + 1, after.allInvoices(), label + ": one invoice row in all");
        Assertions.assertEquals(before.orderStoredFiles() + 2, after.orderStoredFiles(),
                label + ": the signature and the signed PDF stored_file rows, in the order's directory");
        Assertions.assertEquals(before.allStoredFiles() + 2, after.allStoredFiles(), label + ": two stored_file rows in all");
        Assertions.assertEquals(before.orderFiles().size() + 2, after.orderFiles().size(),
                label + ": two new files in the order's directory");
        Assertions.assertTrue(after.orderFiles().containsAll(before.orderFiles()), label + ": no file is removed");
        Assertions.assertEquals(before.sentEmails() + 1, after.sentEmails(), label + ": one email is sent");
        Assertions.assertEquals(before.failedEmails(), after.failedEmails(), label + ": no email attempt fails");
        JsonNode body = readJson(result);
        Assertions.assertEquals(ACCEPT_EMAILED_MESSAGE, body.path("message").asText(), label + ": message");
        JsonNode invoice = body.path("data").path("invoice");
        Assertions.assertEquals(current + 1, invoice.path("version_number").asInt(),
                label + ": D.8 appends the version after the current one");
        Assertions.assertEquals(current + 1, currentVersion(orderId), label + ": the appended version is current");
        Assertions.assertTrue(invoice.path("accepted_signature_present").asBoolean(), label + ": signed");
        Assertions.assertTrue(invoice.path("accepted_customer_name").isTextual(), label + ": an accepted name");
        Assertions.assertEquals(frozenName, invoice.path("accepted_customer_name").asText(),
                label + ": the accepted name is the current version's frozen name, verbatim");
        return invoice;
    }

    /**
     * A request that must be REFUSED: the expected status, code and exact message, no data, and nothing
     * written (see {@link #assertNothingWritten}). {@code footprintOrderId} is the order whose rows, files
     * and storage directory are compared (the database-wide counts and the emails are compared too).
     * Returns the error object.
     */
    private JsonNode assertRefused(long footprintOrderId, RequestBuilder request, int expectedStatus,
                                   String expectedCode, String expectedMessage, String label) throws Exception {
        Footprint before = footprint(footprintOrderId);
        MvcResult result = perform(request, expectedStatus, label);
        JsonNode body = readJson(result);
        JsonNode error = body.path("error");
        Assertions.assertTrue(error.isObject(), () -> label + ": the error envelope: " + body);
        Assertions.assertEquals(expectedCode, error.path("code").asText(), () -> label + ": error code; body: " + body);
        Assertions.assertEquals(expectedMessage, error.path("message").asText(),
                () -> label + ": error message; body: " + body);
        Assertions.assertFalse(body.has("data"), () -> label + ": an error carries no data: " + body);
        assertNothingWritten(footprintOrderId, before, label);
        return error;
    }

    /** 409 INVOICE_VERSION_STALE with the exact message and no details, nothing written. */
    private void assertStale(long orderId, RequestBuilder request, String label) throws Exception {
        JsonNode error = assertRefused(orderId, request, 409, STALE_CODE, STALE_MESSAGE, label);
        Assertions.assertFalse(error.has("details"), () -> label + ": the stale 409 carries no details: " + error);
    }

    /** 400 VALIDATION_FAILED with EXACTLY one detail ({@code field}, {@code message}), nothing written. */
    private void assertOneDetail(long orderId, RequestBuilder request, String field, String message, String label)
            throws Exception {
        JsonNode error = assertRefused(orderId, request, 400, VALIDATION_FAILED, VALIDATION_FAILED_MESSAGE, label);
        JsonNode details = error.path("details");
        Assertions.assertTrue(details.isArray() && details.size() == 1, () -> label + ": exactly one detail: " + error);
        Assertions.assertEquals(field, details.get(0).path("field").asText(), () -> label + ": detail field: " + error);
        Assertions.assertEquals(message, details.get(0).path("message").asText(),
                () -> label + ": detail message: " + error);
    }

    /** Everything a D.8 can write, for one order plus the database-wide counts and the recorded emails. */
    private record Footprint(List<Map<String, Object>> orderInvoices, List<Map<String, Object>> orderRow,
                             int orderStoredFiles, Set<String> orderFiles, long allInvoices, long allStoredFiles,
                             int sentEmails, int failedEmails) {
    }

    private Footprint footprint(long orderId) throws IOException {
        return new Footprint(
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                storedFileCount(orderId),
                filesOnDisk(orderId),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice", Long.class),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file", Long.class),
                invoiceEmailSender.sentEmails().size(),
                invoiceEmailSender.failedEmails().size());
    }

    /**
     * NOTHING written since {@code before}: the order's invoice rows are identical (none appended, none
     * changed), the order row is identical, no invoice or stored_file row was added anywhere, no stored_file
     * row and no file in the order's storage directory, and no email was sent or attempted.
     */
    private void assertNothingWritten(long orderId, Footprint before, String label) throws IOException {
        Footprint after = footprint(orderId);
        Assertions.assertEquals(before.orderInvoices(), after.orderInvoices(),
                label + ": no invoice row is appended or changed");
        Assertions.assertEquals(before.allInvoices(), after.allInvoices(), label + ": no invoice row anywhere");
        Assertions.assertEquals(before.orderRow(), after.orderRow(), label + ": the order row is untouched");
        Assertions.assertEquals(before.orderStoredFiles(), after.orderStoredFiles(),
                label + ": no stored_file row for the order");
        Assertions.assertEquals(before.allStoredFiles(), after.allStoredFiles(), label + ": no stored_file row anywhere");
        Assertions.assertEquals(before.orderFiles(), after.orderFiles(), label + ": no file in the order's directory");
        Assertions.assertEquals(before.sentEmails(), after.sentEmails(), label + ": no email is sent");
        Assertions.assertEquals(before.failedEmails(), after.failedEmails(), label + ": no email is attempted");
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

    // ================================================================
    // Rows and files
    // ================================================================

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
            rows.add(new LinkedHashMap<>(row));
        }
        return rows;
    }

    private long invoiceId(long orderId, int versionNumber) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT invoice_id FROM invoice WHERE order_id = ? AND version_number = ?",
                Long.class, orderId, versionNumber);
        Assertions.assertNotNull(id, () -> "invoice v" + versionNumber + " must exist");
        return id;
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

    /** The whole invoice row, for unchanged-row comparisons. */
    private Map<String, Object> invoiceRow(long invoiceId) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap("SELECT * FROM invoice WHERE invoice_id = ?", invoiceId));
    }

    private String frozenNameOf(long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT customer_name_snapshot FROM invoice WHERE invoice_id = ?", String.class, invoiceId);
    }

    private LocalDateTime acceptedAt(long invoiceId) {
        Object value = invoiceRow(invoiceId).get("accepted_at");
        Assertions.assertNotNull(value, "the version must be accepted");
        return ((Timestamp) value).toLocalDateTime();
    }

    private String savedCustomerName(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT first_name || ' ' || last_name FROM order_customer WHERE order_id = ?", String.class, orderId);
    }

    private String savedEmail(long orderId) {
        return jdbcTemplate.queryForObject("SELECT email FROM order_customer WHERE order_id = ?", String.class, orderId);
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_status::text FROM sales_order WHERE order_id = ?",
                String.class, orderId);
    }

    private Timestamp orderLastEmailedAt(long orderId) {
        return jdbcTemplate.queryForObject("SELECT last_emailed_at FROM sales_order WHERE order_id = ?",
                Timestamp.class, orderId);
    }

    /**
     * The order's storage directory, exactly as FileStorageService lays it out under the base dir
     * ({@code /uploads/{businessId}/orders/{orderId}/}); a missing order uses the home business.
     */
    private String orderStoragePrefix(long orderId) {
        List<Long> businessIds = jdbcTemplate.queryForList(
                "SELECT business_id FROM sales_order WHERE order_id = ?", Long.class, orderId);
        long businessId = businessIds.isEmpty() ? home.businessId() : businessIds.get(0);
        return "/uploads/" + businessId + "/orders/" + orderId + "/";
    }

    /** stored_file rows under the order's storage directory (every invoice PDF and signature of the order). */
    private int storedFileCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ?",
                Integer.class, orderStoragePrefix(orderId) + "%");
        return count == null ? 0 : count;
    }

    /** The file names on disk in the order's storage directory (empty when the directory does not exist). */
    private Set<String> filesOnDisk(long orderId) throws IOException {
        Path directory = Path.of(storageBaseDir).toAbsolutePath().normalize()
                .resolve(orderStoragePrefix(orderId).substring(1)).normalize();
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** The version's stored PDF read from disk (base dir + storage_path), checked against its stored_file row. */
    private byte[] pdfOnDisk(long invoiceId) throws IOException {
        Map<String, Object> file = jdbcTemplate.queryForMap(
                "SELECT sf.storage_path, sf.mime_type, sf.file_size, i.order_id FROM invoice i "
                        + "JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id WHERE i.invoice_id = ?",
                invoiceId);
        Assertions.assertEquals("application/pdf", file.get("mime_type"), "stored_file mime type");
        String storagePath = (String) file.get("storage_path");
        String orderPrefix = orderStoragePrefix(((Number) file.get("order_id")).longValue());
        Assertions.assertTrue(storagePath.startsWith(orderPrefix),
                () -> "the PDF must be stored under its own order directory: " + storagePath);
        Path onDisk = Path.of(storageBaseDir).toAbsolutePath().normalize().resolve(storagePath.substring(1)).normalize();
        Assertions.assertTrue(Files.isRegularFile(onDisk), () -> "the invoice PDF must exist on disk: " + onDisk);
        byte[] bytes = Files.readAllBytes(onDisk);
        Assertions.assertEquals(((Number) file.get("file_size")).longValue(), bytes.length, "file_size matches the bytes");
        Assertions.assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII), "PDF header");
        return bytes;
    }

    /** The stored file name of the version's PDF ({@code stored_file.file_name}). */
    private String storedPdfFileName(long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT sf.file_name FROM invoice i JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id "
                        + "WHERE i.invoice_id = ?", String.class, invoiceId);
    }

    // ================================================================
    // PDF text
    // ================================================================

    /** Content-stream order (the meta row's columns come out one after another). */
    private static String documentText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * Visual reading order: the signed caption is "Accepted by" + a name span + "on" + a timestamp span, and
     * in content-stream order PDFBox emits the static words before the spans.
     */
    private static String readingOrderText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /**
     * The Issued / Due block (content-stream order, all whitespace removed): everything between the "Issued /
     * Due" eyebrow and the next column's "Sales Person / Store" eyebrow - the invoice date, "Due", the due date.
     */
    private static String issuedDueBlock(byte[] pdf) throws IOException {
        String raw = documentText(pdf);
        String text = noSpace(raw);
        int start = text.indexOf(ISSUED_DUE_HEADING);
        int end = text.indexOf(NEXT_COLUMN_HEADING, start + 1);
        Assertions.assertTrue(start >= 0 && end > start, () -> "the PDF must carry the Issued / Due block: " + flat(raw));
        return text.substring(start + ISSUED_DUE_HEADING.length(), end);
    }

    /**
     * The signed caption "Accepted by {name} on {dd/MM/yyyy HH:mm}" in reading order, all whitespace removed on
     * both sides (a long name wraps). Postgres keeps microseconds, so the stored accepted_at may sit up to 1
     * microsecond after the rendered in-memory instant; the rendering of {@code acceptedAt - 1us} is accepted
     * too, which keeps the check exact across a minute boundary.
     */
    private static void assertAcceptedCaption(byte[] pdf, String acceptedName, LocalDateTime acceptedAt, String label)
            throws IOException {
        String readingOrder = readingOrderText(pdf);
        String text = noSpace(readingOrder);
        String exact = noSpace("Accepted by " + acceptedName + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        String roundedBack = noSpace("Accepted by " + acceptedName + " on "
                + DISPLAY_DATE_TIME.format(acceptedAt.minusNanos(1_000)));
        Assertions.assertTrue(text.contains(exact) || text.contains(roundedBack),
                () -> label + ": the PDF must carry the caption '" + exact + "' in: " + flat(readingOrder));
    }

    private static void assertTextLacks(String text, String unexpected, String label) {
        Assertions.assertFalse(noSpace(text).contains(noSpace(unexpected)),
                () -> label + ": the PDF must NOT contain '" + unexpected + "' in: " + flat(text));
    }

    // PDFBox can inject or drop spaces between glyphs and wraps long text, so checks either collapse
    // whitespace runs or compare with all whitespace removed.
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
    }

    private static String longName() {
        String[] words = {"Aldous", "Briony", "Callum", "Dermot", "Esther", "Fergus", "Gideon", "Harlan", "Imogen",
                "Jasper"};
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 43; i++) {
            if (i > 0) {
                name.append(' ');
            }
            name.append(words[i % words.length]);
        }
        return name.toString();
    }
}
