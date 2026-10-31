package com.flooring.salesportal.order;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flooring.salesportal.common.email.InvoiceLinkEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.error.ApiException;
import com.flooring.salesportal.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16G PR 4 - the token gate, the token-kind isolation, the lazy expiry and the stored-PDF read of the
 * PUBLIC invoice surface ({@code /api/v1/public/invoices/{token}}, {@code .../accept},
 * {@code .../pdf?version_number=N}). Authority: docs/API-Contracts-Phase16G-Invoice-Link.md sections 7.3
 * (shape gate before any database access), 7.7 (lazy expiry, committed before the response), 7.8 (surface
 * isolation), 9.2 (unknown and malformed parity, no session, no {@code Set-Cookie}), 9.4 step 1 (a dead link
 * gets its 410 before any body check) and 9.5 (the pdf read), and the PR 4 row of section 16.
 *
 * <p><b>What is proven.</b> Every malformed token (too short, too long, a character outside the URL-safe
 * alphabet: {@code +}, {@code =}, {@code .}, a {@code %20}-encoded space, {@code ~}, {@code !}, {@code *})
 * and every unknown well-shaped token gives the IDENTICAL 404 body {@code INVOICE_TOKEN_NOT_FOUND} /
 * {@code Invoice not found.} on the GET, the accept and the pdf, and a row planted for a malformed string's
 * hash is never found while the same row re-keyed to a well-shaped string is; that the lookup never even RAN
 * is proven with {@code invoice_token} locked {@code ACCESS EXCLUSIVE} by another transaction (malformed
 * requests complete, a well-shaped lookup waits on the lock). A {@code /} can never reach the gate over HTTP
 * (the real filter chain refuses an encoded slash) and the service gate rejects it before any lookup. A live
 * quote token is unknown on the invoice surface and a live invoice token is unknown on the quote surface (404
 * {@code QUOTE_TOKEN_NOT_FOUND} / {@code Quote not found.}), changing neither - not even lazily expiring an
 * over-age one; one hash stored in both tables resolves each surface to its own order. The pdf validates
 * {@code version_number} only after the gate (missing {@code Required.}; anything else that is not a positive
 * integer {@code Must be a positive integer.}), serves exactly the CURRENT version's stored bytes with the
 * exact headers, refuses any other version with 409 {@code INVOICE_VERSION_STALE} (history included), follows
 * the current version after a payment, and answers a missing file or a signed current version under an
 * {@code ACTIVE} link with the generic 500. An over-age {@code ACTIVE} link flips to {@code EXPIRED} on the
 * GET (200 dead payload), the pdf (410) and the accept (410, whatever the body), and the flip is COMMITTED
 * (read back on a fresh connection) with no invoice row touched; a staff read never flips, a writer's kill of
 * an over-age link takes the writer's reason, and a dead link past its expiry keeps its own state. Every dead
 * state gets its 410 on the accept and the pdf whatever the body or {@code version_number}. No public response,
 * whatever its status, carries a session or a {@code Set-Cookie} ({@link #publicCall}).
 *
 * <p><b>Fixtures - fully self-seeded and COMMITTED (no {@code @Transactional} anywhere).</b> Each test seeds
 * its own tenant (slug {@value #SLUG}, store {@value #STORE_CODE}, salesperson {@value #SALESPERSON_CODE}),
 * invoice-ready orders, invoices (D.1), links (send-link), payments, Rewrites, D.8 signings, quote drafts and
 * quote sends through the REAL protected endpoints, and signs remotely through the real public accept. Raw SQL
 * only seeds, plants token rows and backdates an expiry. MockMvc runs the REAL Spring Security filter chain.
 *
 * <p><b>Sweep.</b> {@code @BeforeEach} and {@code @AfterEach} remove everything the class commits, FK-safe:
 * per order invoice_token, quote_token, invoice, quote_version, quote_draft, payment_transaction, the other
 * order children, sales_order and the order's stored_file rows (plus its files and directory); then the
 * tenant's store_charge / store_product, user_store_access, app_user, store, business_quick_description and
 * business rows and its storage directory. The five recording senders are reset in both.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class PublicInvoiceTokenAndPdfTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before anything is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant (class-unique identifiers; the slug is the sweep's only key) ----
    private static final String SLUG = "pr4-public-tokpdf";
    private static final String BUSINESS_NAME = "Tokenvale Public Flooring";
    private static final String STORE_CODE = "IPT-ONE";
    private static final String STORE_EMAIL = "showroom@tokenvale-public.example.com";
    private static final String SALESPERSON_CODE = "PT1";
    private static final String ORDER_NUMBER_PREFIX = STORE_CODE + "." + SALESPERSON_CODE + ".";
    private static final String CHARGE_CODE_PREFIX = "IPTPD";

    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "order_attachment", "order_note", "order_product_line", "order_charge_line",
            "order_address", "order_customer", "order_enquiry");

    // ---- The saved customer ----
    private static final String CUSTOMER_FIRST_NAME = "Rowan";
    private static final String CUSTOMER_LAST_NAME = "Tokenfield";
    private static final String CUSTOMER_EMAIL = "rowan.tokenfield@example.com";
    private static final String DETAILS_OF_SALE = "Supply and lay carpet (public invoice token and pdf test)";
    private static final String SOFT_TERMS = "<p>Tokenvale soft terms.</p>";

    // ---- The public surface ----
    private static final String PUBLIC_INVOICES = "/api/v1/public/invoices/";
    private static final String PUBLIC_QUOTES = "/api/v1/public/quotes/";
    private static final String PUBLIC_BASE_URL = "http://localhost:5173";
    private static final Pattern INVOICE_LINK_PATTERN = Pattern.compile("/i/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final Pattern QUOTE_LINK_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    /** The 27 keys of PublicInvoiceView (16G contract 9.3), always present. */
    private static final List<String> PUBLIC_VIEW_KEYS = List.of(
            "state", "business_name", "business_logo_url", "accent_color", "business_abn",
            "payment_account_name", "payment_bank_name", "payment_bsb", "payment_account_number",
            "payment_stripe_link_url", "order_number", "flooring_type", "version_number", "invoice_date",
            "due_date", "customer_name", "customer_address_line1", "customer_address_line2", "details_of_sale",
            "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst", "total_paid", "balance_due", "terms_html",
            "expires_at", "message");

    // ---- Codes and exact messages ----
    private static final String TOKEN_NOT_FOUND = "INVOICE_TOKEN_NOT_FOUND";
    private static final String TOKEN_NOT_FOUND_MESSAGE = "Invoice not found.";
    private static final String QUOTE_TOKEN_NOT_FOUND = "QUOTE_TOKEN_NOT_FOUND";
    private static final String QUOTE_TOKEN_NOT_FOUND_MESSAGE = "Quote not found.";
    private static final String STALE = "INVOICE_VERSION_STALE";
    private static final String STALE_MESSAGE = "Invoice updated, please refresh";
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String VALIDATION_FAILED_MESSAGE = "One or more fields are invalid.";
    private static final String REQUIRED = "Required.";
    private static final String POSITIVE_INTEGER = "Must be a positive integer.";
    private static final String INTERNAL_ERROR = "INTERNAL_SERVER_ERROR";
    private static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred.";
    private static final String SIGNATURE_REQUIRED = "SIGNATURE_REQUIRED";
    private static final String SIGNATURE_REQUIRED_MESSAGE = "A customer signature is required to accept the invoice.";
    private static final String LOADED_MESSAGE = "Invoice loaded.";
    private static final String ACCEPTED_MESSAGE = "Invoice accepted.";
    private static final String LINK_SENT_MESSAGE = "Invoice link emailed to the customer.";

    private static final String STATE_REPLACED = "REPLACED";
    private static final String STATE_REWRITTEN = "REWRITTEN";
    private static final String STATE_SIGNED = "SIGNED";
    private static final String STATE_EXPIRED = "EXPIRED";
    /** Each dead state: its 410 code (the 200 GET and the 410 share the message, 16G contract 7.2). */
    private static final Map<String, String> DEAD_CODES = Map.of(
            STATE_REPLACED, "INVOICE_LINK_REPLACED",
            STATE_REWRITTEN, "INVOICE_LINK_REWRITTEN",
            STATE_SIGNED, "INVOICE_LINK_SIGNED",
            STATE_EXPIRED, "INVOICE_LINK_EXPIRED");
    private static final Map<String, String> DEAD_MESSAGES = Map.of(
            STATE_REPLACED, "This invoice link has been replaced. Please use the latest invoice link.",
            STATE_REWRITTEN, "This invoice has been revised. Please contact the store for the updated invoice.",
            STATE_SIGNED, "This invoice has already been signed. Please contact the store if you need a copy.",
            STATE_EXPIRED, "This invoice link has expired. Please contact the store.");

    // ---- The multipart contract (16G contract 9.4) ----
    private static final String SIGNATURE_PART = "signature";
    private static final String EXPECTED_VERSION_FIELD = "expected_version_number";

    /** A real, decodable 1x1 PNG (passes the shared PNG rules; embedded into the signed PDF). */
    private static final byte[] SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final DateTimeFormatter WHOLE_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH);

    // ---- The table-lock probe: bounds (the poll interval is not a barrier; the barrier is the DB condition) ----
    private static final long LOCK_PROBE_TIMEOUT_SECONDS = 20L;
    private static final long BARRIER_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 10L;
    /** Backends of THIS database waiting on a heavyweight lock held by the given pid while reading invoice_token. */
    private static final String TABLE_LOCK_WAITER_SQL = """
            SELECT a.pid
            FROM pg_stat_activity a
            WHERE a.datname = current_database()
              AND a.pid <> pg_backend_pid()
              AND a.wait_event_type = 'Lock'
              AND ? = ANY(pg_blocking_pids(a.pid))
              AND a.query ILIKE '%invoice_token%'
            """;

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Raw pooled connections (auto-commit) for the committed-state reads of the lazy-expiry tests. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Only for the service-level gate check of a token that cannot travel in one path segment ('/'). */
    @Autowired
    private PublicInvoiceService publicInvoiceService;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingInvoiceLinkEmailSender invoiceLinkEmailSender;

    @Autowired
    private RecordingInvoiceAcceptanceNotificationSender invoiceNotificationSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender quoteNotificationSender;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    /** This test's committed tenant (seeded after the sweep). */
    private Tenant tenant;

    private int orderSequence;

    /** Every HTTP status a public request of the current test answered with (see {@link #publicCallOn}). */
    private final Set<Integer> publicStatusesSeen = new ConcurrentSkipListSet<>();

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes files recursively - never let it run against a real storage dir.
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class writes and sweeps files only under the shared test storage dir");
        Assertions.assertTrue(storageBase().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                "this class may only write under its own test storage dir, but was " + storageBase());
        // The REAL Spring Security filter chain (FilterChainProxy, firewall, session management).
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        publicStatusesSeen.clear();
        cleanUpEverything();
        tenant = seedTenant();
    }

    @AfterEach
    void tearDown() {
        cleanUpEverything();
    }

    // ================================================================
    // 1. Shape gate and unknown / malformed parity (16G contract 7.3, 9.2)
    // ================================================================

    /**
     * Every malformed token - 42 and 129 characters, a {@code +}, a {@code =}, a {@code .}, a
     * {@code %20}-encoded space, and one character outside the URL-safe alphabet at a valid length ({@code ~},
     * {@code !}, {@code *}), plus near misses of the LIVE token (cut to 42, padded with {@code =}) - and every
     * unknown well-shaped token (43 and 128 characters, the live token plus one character) answers the GET, the
     * accept (with a fully valid signing body and with no parts at all) and the pdf (a valid, a missing and a
     * non-integer {@code version_number}) with the SAME 404 body, byte for byte as JSON:
     * {@code {"error":{"code":"INVOICE_TOKEN_NOT_FOUND","message":"Invoice not found."}}}. The token gate runs
     * before the body and before {@code version_number}. Only the transport check comes first: a non-multipart
     * accept is the framework's 415 for any token. Nothing is written and the live link keeps working.
     */
    @Test
    void malformedAndUnknownTokens_identical404InvoiceTokenNotFound_onGetAcceptAndPdf_fullErrorBodiesEqual_nothingWritten()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String liveToken = sendLink(order);
        resetSenders();
        OrderState before = orderState(order);

        Map<String, String> unknown = new LinkedHashMap<>();
        unknown.put("an unknown 43-character token", randomUrlSafe(43));
        unknown.put("an unknown 128-character token (the longest well-shaped one)", randomUrlSafe(128));
        unknown.put("the live token plus one character (well-shaped, never minted)", liveToken + "A");

        Map<String, String> malformed = new LinkedHashMap<>();
        malformed.put("42 characters", randomUrlSafe(42));
        malformed.put("129 characters", randomUrlSafe(129));
        malformed.put("a '+' (standard Base64)", randomUrlSafe(21) + "+" + randomUrlSafe(21));
        malformed.put("a '=' (Base64 padding)", randomUrlSafe(42) + "=");
        malformed.put("a '.'", randomUrlSafe(21) + "." + randomUrlSafe(21));
        malformed.put("a %20-encoded space", randomUrlSafe(21) + "%20" + randomUrlSafe(21));
        malformed.put("one invalid character '~' at a valid length", randomUrlSafe(21) + "~" + randomUrlSafe(21));
        malformed.put("one invalid character '!' at a valid length", randomUrlSafe(42) + "!");
        malformed.put("one invalid character '*' at a valid length", "*" + randomUrlSafe(42));
        malformed.put("the live token cut to 42 characters", liveToken.substring(0, 42));
        malformed.put("the live token with a '=' pad", liveToken + "=");

        JsonNode expected = errorJson(TOKEN_NOT_FOUND, TOKEN_NOT_FOUND_MESSAGE);
        String reference = unknown.values().iterator().next();
        Map<String, NotFoundResponse> referenceResponses = notFoundResponses(reference, "reference unknown token");
        for (Map.Entry<String, NotFoundResponse> response : referenceResponses.entrySet()) {
            Assertions.assertEquals(expected, JSON.readTree(response.getValue().body()),
                    "the unknown token's " + response.getKey() + " must be exactly the INVOICE_TOKEN_NOT_FOUND body");
        }

        Map<String, String> all = new LinkedHashMap<>(unknown);
        all.putAll(malformed);
        for (Map.Entry<String, String> token : all.entrySet()) {
            Map<String, NotFoundResponse> responses = notFoundResponses(token.getValue(), token.getKey());
            Assertions.assertEquals(referenceResponses, responses, token.getKey()
                    + ": every endpoint must answer exactly like an unknown token (status, content type, raw body)");
        }

        // Stage 0 (the framework) precedes the gate: a non-multipart accept is 415 for any token.
        for (String token : List.of(reference, malformed.get("42 characters"), liveToken)) {
            MvcResult notMultipart = publicCall(post(URI.create(PUBLIC_INVOICES + token + "/accept"))
                    .contentType(MediaType.APPLICATION_JSON).content("{}"), "non-multipart accept");
            assertExactError(notMultipart, 415, VALIDATION_FAILED, VALIDATION_FAILED_MESSAGE,
                    "a non-multipart accept is the transport's 415, before the token gate");
        }

        assertOrderStateUnchanged(before, orderState(order), "unknown and malformed tokens");
        MvcResult live = publicInvoiceGet(liveToken, "the live link afterwards");
        assertStatus(live, 200, "the live link afterwards");
        Assertions.assertEquals("ACTIVE", readJson(live).path("data").path("state").asText());
    }

    /**
     * The shape gate runs BEFORE any database access (16G contract 7.3). An ACTIVE row is planted whose
     * {@code token_hash} is the SHA-256 of a malformed 42-character string, on an order whose unsigned v1 it
     * could sign; REPLACED rows are planted the same way for a 129-character string and for strings holding
     * {@code +}, {@code =}, {@code .}, a space (sent {@code %20}-encoded) and {@code ~}. Presenting each string
     * gives the 404 on the GET, the accept (a fully valid signing body) and the pdf - had the lookup run it would
     * have found its row (200 / 201 / 200 for the ACTIVE row; 200 dead / 410 for a REPLACED one) - and nothing
     * is signed or written. That the lookup never even RAN is proven with {@code invoice_token} locked
     * {@code ACCESS EXCLUSIVE} by another transaction: every malformed request still completes with the 404,
     * while a well-shaped token's lookup is observed waiting on that lock until it is released (the probe is
     * sensitive). Positive controls: the SAME rows re-keyed to well-shaped strings ARE found (the ACTIVE one: GET
     * 200 ACTIVE with the order's v1 and its stored PDF; a REPLACED one: GET 200 REPLACED and a pdf 410).
     */
    @Test
    void shapeGate_runsBeforeAnyLookup_rowsPlantedForMalformedTokensAreNeverFound_theSameRowsRekeyedToWellShapedTokensAre()
            throws Exception {
        SeededOrder order = invoicedOrder();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.SECONDS);

        String malformed42 = randomUrlSafe(42);
        long activeRowId = plantInvoiceToken(order.orderId(), malformed42, "ACTIVE", expiresAt);

        // label -> {raw path segment, the decoded token the server sees}
        Map<String, String[]> plantedDead = new LinkedHashMap<>();
        String long129 = randomUrlSafe(129);
        plantedDead.put("129 characters", new String[] {long129, long129});
        String plus = randomUrlSafe(21) + "+" + randomUrlSafe(21);
        plantedDead.put("a '+'", new String[] {plus, plus});
        String equals = randomUrlSafe(42) + "=";
        plantedDead.put("a '='", new String[] {equals, equals});
        String dot = randomUrlSafe(21) + "." + randomUrlSafe(21);
        plantedDead.put("a '.'", new String[] {dot, dot});
        String left = randomUrlSafe(21);
        String right = randomUrlSafe(21);
        plantedDead.put("a %20-encoded space", new String[] {left + "%20" + right, left + " " + right});
        String tilde = randomUrlSafe(21) + "~" + randomUrlSafe(21);
        plantedDead.put("a '~'", new String[] {tilde, tilde});
        Map<String, Long> deadRowIds = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> planted : plantedDead.entrySet()) {
            deadRowIds.put(planted.getKey(),
                    plantInvoiceToken(order.orderId(), planted.getValue()[1], STATE_REPLACED, expiresAt));
        }
        Assertions.assertEquals(1L, count("SELECT COUNT(*) FROM invoice_token WHERE token_hash = ?", sha256Hex(malformed42)),
                "setup: the malformed 42-character string's hash is in invoice_token");

        OrderState before = orderState(order);
        Map<String, String> presented = new LinkedHashMap<>();
        presented.put("42 characters (planted ACTIVE)", malformed42);
        for (Map.Entry<String, String[]> planted : plantedDead.entrySet()) {
            presented.put(planted.getKey() + " (planted REPLACED)", planted.getValue()[0]);
        }
        for (Map.Entry<String, String> token : presented.entrySet()) {
            String label = token.getKey();
            assertTokenNotFound(publicInvoiceGet(token.getValue(), label + ": GET"), label + ": GET");
            assertTokenNotFound(publicInvoiceAccept(token.getValue(), label + ": accept", version("1"), pngSignature()),
                    label + ": accept with a valid signing body");
            assertTokenNotFound(publicInvoicePdf(token.getValue(), "version_number=1", label + ": pdf"),
                    label + ": pdf of the current version");
        }
        // Nothing was found, so nothing was signed, flipped or written: the planted rows are untouched too.
        assertOrderStateUnchanged(before, orderState(order), "malformed tokens with planted rows");

        // The lookup never even RAN. With invoice_token locked ACCESS EXCLUSIVE by another transaction (any read
        // of the table waits on it), every malformed request still COMPLETES with the 404, while a well-shaped
        // token's lookup is observed WAITING on the lock (the probe is sensitive) until it is released.
        try (InvoiceTokenTableLock lock = new InvoiceTokenTableLock()) {
            for (Map.Entry<String, String> token : presented.entrySet()) {
                String label = token.getKey() + ", invoice_token locked";
                String raw = token.getValue();
                assertTokenNotFound(lock.completes(get(URI.create(PUBLIC_INVOICES + raw)), label + ": GET"),
                        label + ": GET");
                assertTokenNotFound(lock.completes(withParts(multipart(URI.create(PUBLIC_INVOICES + raw + "/accept")),
                        version("1"), pngSignature()), label + ": accept"), label + ": accept");
                assertTokenNotFound(lock.completes(get(URI.create(PUBLIC_INVOICES + raw + "/pdf?version_number=1")),
                        label + ": pdf"), label + ": pdf");
            }
            Future<MvcResult> lookup = lock.submitPublic(get(URI.create(PUBLIC_INVOICES + randomUrlSafe(43))),
                    "control: a well-shaped unknown token, invoice_token locked");
            lock.awaitLookupWaiting(lookup, "control: a well-shaped unknown token");
            Assertions.assertFalse(lookup.isDone(), "control: the well-shaped token's lookup waits on the lock");
            lock.release();
            assertTokenNotFound(lock.await(lookup, "control: the well-shaped token after the release"),
                    "control: the well-shaped token's lookup completes after the release: an unknown token");
        }
        assertOrderStateUnchanged(before, orderState(order), "malformed tokens under the table lock");

        // Positive control 1: the planted ACTIVE row, re-keyed to a WELL-SHAPED string, IS found.
        String wellShaped = randomUrlSafe(43);
        rekeyInvoiceToken(activeRowId, wellShaped);
        MvcResult found = publicInvoiceGet(wellShaped, "control: the re-keyed ACTIVE row");
        assertStatus(found, 200, "control: the re-keyed ACTIVE row");
        JsonNode view = readJson(found).path("data");
        Assertions.assertEquals("ACTIVE", view.path("state").asText(), "control: the planted row is a live link");
        Assertions.assertEquals(order.orderNumber(), view.path("order_number").asText(), "control: its order");
        Assertions.assertEquals(1, view.path("version_number").asInt(), "control: its order's current version");
        Assertions.assertEquals(WHOLE_SECONDS.format(expiresAt), view.path("expires_at").asText(),
                "control: the planted row's own expiry");
        assertServesStoredPdf(publicInvoicePdf(wellShaped, "version_number=1", "control: pdf"), order, 1,
                "control: the re-keyed ACTIVE row serves the stored v1 PDF");

        // Positive control 2: a planted REPLACED row, re-keyed to a well-shaped string, IS found (dead).
        String wellShapedDead = randomUrlSafe(43);
        rekeyInvoiceToken(deadRowIds.get("a '+'"), wellShapedDead);
        MvcResult foundDead = publicInvoiceGet(wellShapedDead, "control: the re-keyed REPLACED row");
        assertStatus(foundDead, 200, "control: the re-keyed REPLACED row");
        Assertions.assertEquals(deadPayload(STATE_REPLACED), readJson(foundDead),
                "control: the re-keyed REPLACED row gives the REPLACED dead payload");
        assertGone(publicInvoicePdf(wellShapedDead, "version_number=1", "control: dead pdf"), STATE_REPLACED,
                "control: the re-keyed REPLACED row's pdf");
    }

    /**
     * A {@code /} (standard Base64) cannot travel inside ONE path segment over HTTP: percent-encoded as
     * {@code %2F} it is refused by the real Spring Security filter chain (its firewall rejects encoded slashes:
     * a bodiless 400, before any controller, so no lookup and no session), and unencoded it would simply be a
     * different, longer URL path. The token gate itself rejects the decoded value before any lookup: called
     * directly, the GET, the pdf and the accept of the service all throw the SAME
     * {@code INVOICE_TOKEN_NOT_FOUND} / {@code Invoice not found.} for a {@code /}-token whose hash was planted
     * as an ACTIVE row on an order with an unsigned invoice - and nothing is signed. All of it completes while
     * {@code invoice_token} is locked {@code ACCESS EXCLUSIVE} by another transaction, so none of it read the
     * table.
     */
    @Test
    void slashInAToken_neverReachesTheGateOverHttp_firewallRefusesTheEncodedSlash_andTheServiceGateRejectsItBeforeAnyLookup()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String slashToken = randomUrlSafe(21) + "/" + randomUrlSafe(21);
        plantInvoiceToken(order.orderId(), slashToken, "ACTIVE",
                LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.SECONDS));
        OrderState before = orderState(order);
        String encoded = slashToken.replace("/", "%2F");

        MockMultipartHttpServletRequest signingBody = new MockMultipartHttpServletRequest();
        signingBody.addFile(new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", SIGNATURE_PNG));
        signingBody.addParameter(EXPECTED_VERSION_FIELD, "1");

        // Everything below runs while invoice_token is locked ACCESS EXCLUSIVE: none of it may read the table.
        try (InvoiceTokenTableLock lock = new InvoiceTokenTableLock()) {
            List<MvcResult> refused = List.of(
                    lock.completes(get(URI.create(PUBLIC_INVOICES + encoded)), "GET with %2F"),
                    lock.completes(withParts(multipart(URI.create(PUBLIC_INVOICES + encoded + "/accept")),
                            version("1"), pngSignature()), "accept with %2F"),
                    lock.completes(get(URI.create(PUBLIC_INVOICES + encoded + "/pdf?version_number=1")), "pdf with %2F"));
            for (MvcResult result : refused) {
                assertStatus(result, 400, "an encoded slash is refused by the filter chain's firewall");
                Assertions.assertEquals(0, result.getResponse().getContentAsByteArray().length,
                        "the firewall's 400 has no body (no controller, no token gate ran)");
            }

            // The gate itself: the decoded value is malformed, so it is the unknown-token 404 before any lookup.
            assertServiceGateRejects(lock.rejection(() -> publicInvoiceService.getView(slashToken), "service GET"),
                    "service GET");
            assertServiceGateRejects(lock.rejection(() -> publicInvoiceService.downloadPdf(slashToken, "1"),
                    "service pdf"), "service pdf");
            assertServiceGateRejects(lock.rejection(() -> publicInvoiceService.accept(slashToken, signingBody),
                    "service accept"), "service accept");
        }

        assertOrderStateUnchanged(before, orderState(order), "a token holding '/'");
    }

    // ================================================================
    // 2. Token-kind isolation (16G contract 7.8)
    // ================================================================

    /**
     * One order holds BOTH a live ACTIVE invoice link (real send-link) and a live ACTIVE quote link (real quote
     * send-email), so a lookup in the wrong table would find this very order. The quote token on the invoice
     * GET, accept (a fully valid signing body, and no parts) and pdf: 404 {@code INVOICE_TOKEN_NOT_FOUND}. The
     * invoice token on the quote GET, {@code /viewed} (with {@code {}} and with no body), {@code /pdf} and
     * {@code /accept} (a valid PNG): 404 {@code QUOTE_TOKEN_NOT_FOUND} / {@code Quote not found.}. Neither
     * token's row changes, nothing is viewed, signed, accepted or written, and each token still works on its own
     * surface.
     */
    @Test
    void tokenKindIsolation_liveQuoteTokenIsUnknownOnTheInvoiceSurface_liveInvoiceTokenIsUnknownOnTheQuoteSurface_neitherChanges()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String invoiceToken = sendLink(order);
        saveItemisedQuoteDraft(order.orderId());
        String quoteToken = issueQuoteAndExtractToken(order.orderId());
        Assertions.assertNotEquals(invoiceToken, quoteToken, "setup: two different live tokens");
        Assertions.assertEquals("ACTIVE", invoiceTokenRow(invoiceToken).get("status"), "setup: the invoice link is ACTIVE");
        Assertions.assertEquals("ACTIVE", quoteTokenRow(quoteToken).get("status"), "setup: the quote link is ACTIVE");
        resetSenders();
        OrderState before = orderState(order);

        // The quote token on the invoice surface.
        assertTokenNotFound(publicInvoiceGet(quoteToken, "quote token: invoice GET"), "quote token: invoice GET");
        assertTokenNotFound(publicInvoiceAccept(quoteToken, "quote token: invoice accept", version("1"), pngSignature()),
                "quote token: invoice accept with a valid signing body");
        assertTokenNotFound(publicInvoiceAccept(quoteToken, "quote token: invoice accept, no parts"),
                "quote token: invoice accept with no parts");
        assertTokenNotFound(publicInvoicePdf(quoteToken, "version_number=1", "quote token: invoice pdf"),
                "quote token: invoice pdf");

        // The invoice token on the quote surface.
        assertQuoteTokenNotFound(publicCall(get(URI.create(PUBLIC_QUOTES + invoiceToken)), "invoice token: quote GET"),
                "invoice token: quote GET");
        assertQuoteTokenNotFound(publicCall(post(URI.create(PUBLIC_QUOTES + invoiceToken + "/viewed"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"), "invoice token: quote viewed"),
                "invoice token: quote viewed with {}");
        assertQuoteTokenNotFound(publicCall(post(URI.create(PUBLIC_QUOTES + invoiceToken + "/viewed")),
                "invoice token: quote viewed, no body"), "invoice token: quote viewed with no body");
        assertQuoteTokenNotFound(publicCall(get(URI.create(PUBLIC_QUOTES + invoiceToken + "/pdf")),
                "invoice token: quote pdf"), "invoice token: quote pdf");
        assertQuoteTokenNotFound(publicCall(withParts(multipart(URI.create(PUBLIC_QUOTES + invoiceToken + "/accept")),
                pngSignature()), "invoice token: quote accept"), "invoice token: quote accept with a valid PNG");

        // Neither token's state (nor anything else of the order) changed.
        assertOrderStateUnchanged(before, orderState(order), "cross-surface tokens");
        Assertions.assertNull(issuedQuoteVersion(order.orderId()).get("viewed_at"),
                "the quote was never marked viewed by the invoice token");

        // Each token still works on its own surface (and those GETs write nothing either).
        MvcResult invoiceView = publicInvoiceGet(invoiceToken, "invoice token: invoice GET");
        assertStatus(invoiceView, 200, "invoice token: invoice GET");
        Assertions.assertEquals("ACTIVE", readJson(invoiceView).path("data").path("state").asText());
        Assertions.assertEquals(order.orderNumber(), readJson(invoiceView).path("data").path("order_number").asText());
        MvcResult quoteView = publicCall(get(URI.create(PUBLIC_QUOTES + quoteToken)), "quote token: quote GET");
        assertStatus(quoteView, 200, "quote token: quote GET");
        Assertions.assertEquals("ACTIVE", readJson(quoteView).path("data").path("state").asText());
        assertOrderStateUnchanged(before, orderState(order), "after each token's own GET");

        // Lazy expiry stays inside each surface's own table: with BOTH links over-age, each one presented to the
        // OTHER surface is still the unknown-token 404 and neither is flipped (no write at all).
        backdateExpiry(invoiceToken);
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE quote_token SET expires_at = ? WHERE token_hash = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)), sha256Hex(quoteToken)), "backdate the quote link");
        OrderState overAge = orderState(order);
        assertTokenNotFound(publicInvoiceGet(quoteToken, "over-age quote token: invoice GET"),
                "an over-age quote token on the invoice GET");
        assertTokenNotFound(publicInvoicePdf(quoteToken, "version_number=1", "over-age quote token: invoice pdf"),
                "an over-age quote token on the invoice pdf");
        assertTokenNotFound(publicInvoiceAccept(quoteToken, "over-age quote token: invoice accept", version("1"),
                pngSignature()), "an over-age quote token on the invoice accept");
        assertQuoteTokenNotFound(publicCall(get(URI.create(PUBLIC_QUOTES + invoiceToken)),
                "over-age invoice token: quote GET"), "an over-age invoice token on the quote GET");
        assertQuoteTokenNotFound(publicCall(post(URI.create(PUBLIC_QUOTES + invoiceToken + "/viewed"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"), "over-age invoice token: quote viewed"),
                "an over-age invoice token on the quote viewed");
        assertQuoteTokenNotFound(publicCall(get(URI.create(PUBLIC_QUOTES + invoiceToken + "/pdf")),
                "over-age invoice token: quote pdf"), "an over-age invoice token on the quote pdf");
        assertQuoteTokenNotFound(publicCall(withParts(multipart(URI.create(PUBLIC_QUOTES + invoiceToken + "/accept")),
                pngSignature()), "over-age invoice token: quote accept"), "an over-age invoice token on the quote accept");
        assertOrderStateUnchanged(overAge, orderState(order), "over-age tokens on the other surface: no expiry flip");
        Assertions.assertEquals("ACTIVE", invoiceTokenRow(invoiceToken).get("status"), "the invoice link is still ACTIVE");
        Assertions.assertEquals("ACTIVE", quoteTokenRow(quoteToken).get("status"), "the quote link is still ACTIVE");

        // Sensitivity control: on its own surface each over-age link really does expire (committed).
        MvcResult invoiceExpired = publicInvoiceGet(invoiceToken, "over-age invoice token: invoice GET");
        assertStatus(invoiceExpired, 200, "over-age invoice token: invoice GET");
        Assertions.assertEquals(deadPayload(STATE_EXPIRED), readJson(invoiceExpired), "the invoice link expires on its own surface");
        Assertions.assertEquals(STATE_EXPIRED, invoiceTokenRow(invoiceToken).get("status"));
        MvcResult quoteExpired = publicCall(get(URI.create(PUBLIC_QUOTES + quoteToken)), "over-age quote token: quote GET");
        assertStatus(quoteExpired, 200, "over-age quote token: quote GET");
        Assertions.assertEquals("EXPIRED", readJson(quoteExpired).path("data").path("state").asText(),
                "the quote link expires on its own surface");
        Assertions.assertEquals("EXPIRED", quoteTokenRow(quoteToken).get("status"));
    }

    /**
     * Hash uniqueness holds within each table and a match in the other table is never consulted (16G contract
     * 7.8). Order B's REAL quote send minted an ACTIVE quote token; an ACTIVE invoice_token row with the SAME
     * {@code token_hash} is inserted for order A (unsigned invoice v1). The one plaintext then opens order A's
     * invoice on the invoice surface (view and stored PDF) and order B's quote on the quote surface (view and
     * stored issued PDF); neither row changes.
     */
    @Test
    void sameTokenHashInBothTables_eachSurfaceResolvesOnlyItsOwnTable_invoiceSurfaceOpensOrderA_quoteSurfaceOpensOrderB()
            throws Exception {
        SeededOrder invoiceOrder = invoicedOrder();
        SeededOrder quoteOrder = insertInvoiceReadyOrder();
        saveItemisedQuoteDraft(quoteOrder.orderId());
        String shared = issueQuoteAndExtractToken(quoteOrder.orderId());
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.SECONDS);
        long invoiceTokenId = plantInvoiceToken(invoiceOrder.orderId(), shared, "ACTIVE", expiresAt);
        String hash = sha256Hex(shared);
        Assertions.assertEquals(List.of(quoteOrder.orderId()), jdbcTemplate.queryForList(
                        "SELECT v.order_id FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                                + "WHERE t.token_hash = ?", Long.class, hash),
                "setup: the hash is order B's quote token");
        Assertions.assertEquals(List.of(invoiceOrder.orderId()), jdbcTemplate.queryForList(
                        "SELECT order_id FROM invoice_token WHERE token_hash = ?", Long.class, hash),
                "setup: the same hash is order A's invoice token");
        resetSenders();
        OrderState invoiceOrderBefore = orderState(invoiceOrder);
        OrderState quoteOrderBefore = orderState(quoteOrder);

        // The invoice surface: order A only.
        MvcResult invoiceView = publicInvoiceGet(shared, "invoice surface GET");
        assertStatus(invoiceView, 200, "invoice surface GET");
        JsonNode invoiceData = readJson(invoiceView).path("data");
        assertKeys(invoiceData, "invoice surface GET");
        Assertions.assertEquals("ACTIVE", invoiceData.path("state").asText());
        Assertions.assertEquals(invoiceOrder.orderNumber(), invoiceData.path("order_number").asText(),
                "the invoice surface resolves invoice_token only: order A");
        Assertions.assertEquals(1, invoiceData.path("version_number").asInt());
        Assertions.assertEquals(WHOLE_SECONDS.format(expiresAt), invoiceData.path("expires_at").asText(),
                "the expiry of order A's invoice_token row");
        assertServesStoredPdf(publicInvoicePdf(shared, "version_number=1", "invoice surface pdf"), invoiceOrder, 1,
                "the invoice surface serves order A's stored v1 PDF");

        // The quote surface: order B only.
        MvcResult quoteView = publicCall(get(URI.create(PUBLIC_QUOTES + shared)), "quote surface GET");
        assertStatus(quoteView, 200, "quote surface GET");
        JsonNode quoteData = readJson(quoteView).path("data");
        Assertions.assertEquals("ACTIVE", quoteData.path("state").asText());
        Assertions.assertEquals(quoteOrder.orderNumber(), quoteData.path("order_number").asText(),
                "the quote surface resolves quote_token only: order B");
        MvcResult quotePdf = publicCall(get(URI.create(PUBLIC_QUOTES + shared + "/pdf")), "quote surface pdf");
        assertStatus(quotePdf, 200, "quote surface pdf");
        Assertions.assertArrayEquals(issuedQuotePdfBytes(quoteOrder.orderId()), quotePdf.getResponse().getContentAsByteArray(),
                "the quote surface serves order B's stored issued quote PDF");

        assertOrderStateUnchanged(invoiceOrderBefore, orderState(invoiceOrder), "order A after both surfaces");
        assertOrderStateUnchanged(quoteOrderBefore, orderState(quoteOrder), "order B after both surfaces");
        Assertions.assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM invoice_token WHERE invoice_token_id = ?", String.class, invoiceTokenId));
    }

    // ================================================================
    // 3. The pdf read (16G contract 9.5)
    // ================================================================

    /**
     * {@code version_number} on an ACTIVE link (checked after the gate): missing gives 400
     * {@code VALIDATION_FAILED} with exactly one detail {@code version_number} / {@code Required.}; blank (empty,
     * a {@code %20} space), space-padded, zero ({@code 0}, {@code 00}, {@code -0}), negative, non-numeric,
     * fractional, comma-separated, hexadecimal, exponent and beyond-64-bit values, and the parameter REPEATED
     * (whatever its values) give {@code Must be a positive integer.}. No PDF bytes, nothing written; the current
     * version then streams.
     */
    @Test
    void pdf_versionNumberMissingOrNotAPositiveInteger_returns400ValidationFailedOnVersionNumber_noPdfBytes_nothingWritten()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String token = sendLink(order);
        resetSenders();
        OrderState before = orderState(order);

        assertOneDetail(publicInvoicePdf(token, null, "missing"), "version_number", REQUIRED, "missing");
        Map<String, String> notPositive = new LinkedHashMap<>();
        // (A bare "?version_number" with no '=' is not sent: MockMvc models it as a NULL value - i.e. absent,
        // "Required." - where a servlet container such as Tomcat gives "", which is the "empty" case below.)
        notPositive.put("empty", "version_number=");
        notPositive.put("a %20 space", "version_number=%20");
        notPositive.put("' 1' (a leading space)", "version_number=%201");
        notPositive.put("'1 ' (a trailing space)", "version_number=1%20");
        notPositive.put("0", "version_number=0");
        notPositive.put("00", "version_number=00");
        notPositive.put("-0", "version_number=-0");
        notPositive.put("-1", "version_number=-1");
        notPositive.put("abc", "version_number=abc");
        notPositive.put("1.5", "version_number=1.5");
        notPositive.put("1,2 (one value)", "version_number=1,2");
        notPositive.put("1,2 (the parameter repeated)", "version_number=1&version_number=2");
        notPositive.put("1 and 1 (the parameter repeated with the current version)", "version_number=1&version_number=1");
        notPositive.put("0x1", "version_number=0x1");
        notPositive.put("1e0", "version_number=1e0");
        notPositive.put("beyond 64 bits", "version_number=9223372036854775808");
        for (Map.Entry<String, String> query : notPositive.entrySet()) {
            assertOneDetail(publicInvoicePdf(token, query.getValue(), query.getKey()), "version_number", POSITIVE_INTEGER,
                    query.getKey());
        }
        assertOrderStateUnchanged(before, orderState(order), "invalid version_number values");

        assertServesStoredPdf(publicInvoicePdf(token, "version_number=1", "positive control"), order, 1,
                "positive control: the current version");
    }

    /**
     * The pdf serves EXACTLY the current version's stored file. After D.1 (v1) and a payment (v2, its own new
     * PDF) on a link sent at v1: version 2 is 200 {@code application/pdf}, {@code Content-Length} = the stored
     * size, {@code Content-Disposition} exactly {@code inline; filename="invoice-{order_number}-v2.pdf"} (the
     * stored name), the stored bytes verbatim (read from disk through invoice.stored_file_id). Version 1 - a
     * HISTORY version whose own stored PDF is on disk - and version 3 (the future) and a forged
     * {@code Long.MAX_VALUE} are 409 {@code INVOICE_VERSION_STALE} with no PDF bytes. A second payment (v3) moves
     * the served version: 2 is now 409 and 3 returns v3's (different) stored bytes. The link stays ACTIVE and no
     * read writes anything.
     */
    @Test
    void pdf_servesTheCurrentVersionsStoredBytesVerbatimWithExactHeaders_historyAndFutureVersionsAre409_aPaymentMovesTheServedVersion()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String token = sendLink(order);
        recordCashPayment(order, "30.00");
        Assertions.assertEquals(2, currentVersion(order.orderId()), "setup: the payment appended v2");
        Assertions.assertEquals("ACTIVE", invoiceTokenRow(token).get("status"), "setup: a payment never kills the link");
        StoredPdf v1 = storedPdf(order.orderId(), 1);
        Assertions.assertTrue(startsWithPdfMagic(v1.bytes()), "setup: the history version v1 has its own stored PDF");
        resetSenders();
        OrderState before = orderState(order);

        assertServesStoredPdf(publicInvoicePdf(token, "version_number=2", "v2"), order, 2, "the current v2");
        Assertions.assertFalse(java.util.Arrays.equals(v1.bytes(), storedPdf(order.orderId(), 2).bytes()),
                "setup: v1 and v2 are different stored PDFs");
        for (String version : List.of("1", "3", String.valueOf(Long.MAX_VALUE))) {
            assertStalePdf(publicInvoicePdf(token, "version_number=" + version, "v" + version),
                    "version " + version + " while v2 is current");
        }
        assertOrderStateUnchanged(before, orderState(order), "pdf reads at v2");

        // The page re-reads, sees v2 bound to the link, and v2 is the only version it can preview.
        MvcResult view = publicInvoiceGet(token, "GET at v2");
        assertStatus(view, 200, "GET at v2");
        Assertions.assertEquals(2, readJson(view).path("data").path("version_number").asInt(),
                "the order-bound link shows the current version");

        recordCashPayment(order, "20.00");
        Assertions.assertEquals(3, currentVersion(order.orderId()), "the second payment appended v3");
        resetSenders();
        OrderState afterSecondPayment = orderState(order);
        assertStalePdf(publicInvoicePdf(token, "version_number=2", "v2 after the second payment"),
                "the previously current v2 after the payment");
        assertStalePdf(publicInvoicePdf(token, "version_number=4", "v4"), "the future v4");
        assertServesStoredPdf(publicInvoicePdf(token, "version_number=3", "v3"), order, 3, "the new current v3");
        Assertions.assertFalse(java.util.Arrays.equals(storedPdf(order.orderId(), 2).bytes(),
                storedPdf(order.orderId(), 3).bytes()), "v3's stored PDF is a new file, not v2's");
        assertOrderStateUnchanged(afterSecondPayment, orderState(order), "pdf reads at v3");
        Assertions.assertEquals("ACTIVE", invoiceTokenRow(token).get("status"), "the link stays ACTIVE throughout");
    }

    /**
     * The current version's stored file is missing on disk (moved away): the pdf is the generic 500 JSON
     * envelope with no PDF bytes, no session and no cookie, and nothing changes. Moved back, the same request
     * streams the stored bytes again (the file was the only cause).
     */
    @Test
    void pdf_currentVersionsFileMissingOnDisk_returnsTheGeneric500_noPdfBytes_nothingChanges_fileRestoredStreamsAgain()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String token = sendLink(order);
        resetSenders();
        StoredPdf v1 = storedPdf(order.orderId(), 1);
        Path file = physicalPath(v1.storagePath());
        Path moved = file.resolveSibling(file.getFileName() + ".moved-away");
        Files.move(file, moved);
        try {
            OrderState before = orderState(order);
            MvcResult result = publicInvoicePdf(token, "version_number=1", "the file is missing on disk");
            assertExactError(result, 500, INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE, "a missing stored file");
            Assertions.assertFalse(startsWithPdfMagic(result.getResponse().getContentAsByteArray()),
                    "no PDF bytes on a 500");
            Assertions.assertNull(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION),
                    "no attachment header on a 500");
            assertOrderStateUnchanged(before, orderState(order), "a pdf read that failed on disk");
            Assertions.assertEquals("ACTIVE", invoiceTokenRow(token).get("status"), "the link is untouched");
        } finally {
            Files.move(moved, file);
        }
        assertServesStoredPdf(publicInvoicePdf(token, "version_number=1", "the file restored"), order, 1,
                "the restored file streams again");
    }

    /**
     * The never-signable-when-signed invariant on the pdf (16G contract 7.6): after a D.8 signing (v2 signed,
     * the link SIGNED) the link row is forced back to ACTIVE. The pdf of the current, SIGNED v2 is the generic
     * 500 (a signed version is never presented under a live link), with no PDF bytes; the stale comparison
     * still comes first (v1: 409, the 16G contract 9.4 order). Nothing is written.
     */
    @Test
    void pdf_activeLinkOverASignedCurrentVersion_returnsTheGeneric500_noPdfBytes_aMismatchedVersionIsStill409()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String token = sendLink(order);
        signInStore(order, 1);
        Assertions.assertEquals(2, currentVersion(order.orderId()), "setup: D.8 appended the signed v2");
        Assertions.assertNotNull(invoiceRow(order.orderId(), 2).get("accepted_at"), "setup: v2 is signed");
        Assertions.assertEquals(STATE_SIGNED, invoiceTokenRow(token).get("status"), "setup: D.8 killed the link as SIGNED");
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice_token SET status = 'ACTIVE', dead_at = NULL WHERE token_hash = ?", sha256Hex(token)),
                "setup: force the link back to ACTIVE over the signed v2 (an invariant breach)");
        resetSenders();
        OrderState before = orderState(order);

        MvcResult signed = publicInvoicePdf(token, "version_number=2", "signed current version");
        assertExactError(signed, 500, INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE, "an ACTIVE link over a signed version");
        Assertions.assertFalse(startsWithPdfMagic(signed.getResponse().getContentAsByteArray()), "no PDF bytes");
        assertStalePdf(publicInvoicePdf(token, "version_number=1", "v1 under the breach"),
                "a mismatched version under the breach");
        assertOrderStateUnchanged(before, orderState(order), "the invariant breach");
    }

    // ================================================================
    // 4. Lazy expiry with a COMMITTED flip (16G contract 7.7)
    // ================================================================

    /**
     * An ACTIVE link past {@code expires_at}: the GET returns 200 with exactly the EXPIRED dead payload (27
     * keys, all null except {@code state}, {@code business_name} and {@code message}). Read afterwards on a
     * fresh auto-commit connection, the token row is EXPIRED with {@code dead_at} stamped during the request and
     * nothing else of it changed; the invoice rows, the order row, its stored files and files on disk are
     * untouched. A second GET is the same payload and never re-stamps {@code dead_at}.
     */
    @Test
    void lazyExpiry_getOnAnOverAgeActiveLink_returns200ExpiredDeadPayload_flipCommittedOnAFreshConnection_invoiceRowsUntouched()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String token = sendLink(order);
        backdateExpiry(token);
        resetSenders();
        Map<String, Object> tokenBefore = invoiceTokenRow(token);
        OrderState before = orderState(order);

        LocalDateTime startedAt = LocalDateTime.now();
        MvcResult result = publicInvoiceGet(token, "GET of an over-age link");
        LocalDateTime finishedAt = LocalDateTime.now();
        assertStatus(result, 200, "GET of an over-age link");
        Assertions.assertEquals(deadPayload(STATE_EXPIRED), readJson(result), "the EXPIRED dead payload");

        Timestamp deadAt = assertExpiryCommitted(token, tokenBefore, startedAt, finishedAt, "the GET");
        assertInvoiceSideUntouched(before, orderState(order), "the GET's expiry flip");

        MvcResult again = publicInvoiceGet(token, "a second GET");
        assertStatus(again, 200, "a second GET");
        Assertions.assertEquals(deadPayload(STATE_EXPIRED), readJson(again), "still the EXPIRED dead payload");
        Assertions.assertEquals(deadAt, committedTokenOnFreshConnection(token).get("dead_at"),
                "a dead link never changes again: dead_at is not re-stamped");
    }

    /**
     * An ACTIVE link past {@code expires_at}: the pdf of the current version returns 410
     * {@code INVOICE_LINK_EXPIRED} (no PDF bytes) and the flip is COMMITTED (fresh connection: EXPIRED,
     * {@code dead_at} stamped during the request), with no invoice row touched; the GET then shows EXPIRED.
     */
    @Test
    void lazyExpiry_pdfOnAnOverAgeActiveLink_returns410InvoiceLinkExpired_flipCommitted_invoiceRowsUntouched()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String token = sendLink(order);
        backdateExpiry(token);
        resetSenders();
        Map<String, Object> tokenBefore = invoiceTokenRow(token);
        OrderState before = orderState(order);

        LocalDateTime startedAt = LocalDateTime.now();
        MvcResult result = publicInvoicePdf(token, "version_number=1", "pdf of an over-age link");
        LocalDateTime finishedAt = LocalDateTime.now();
        assertGone(result, STATE_EXPIRED, "pdf of an over-age link");
        Assertions.assertFalse(startsWithPdfMagic(result.getResponse().getContentAsByteArray()), "no PDF bytes");

        Timestamp deadAt = assertExpiryCommitted(token, tokenBefore, startedAt, finishedAt, "the pdf");
        assertInvoiceSideUntouched(before, orderState(order), "the pdf's expiry flip");

        MvcResult view = publicInvoiceGet(token, "GET after the pdf");
        assertStatus(view, 200, "GET after the pdf");
        Assertions.assertEquals(deadPayload(STATE_EXPIRED), readJson(view), "the committed flip shows on the GET");
        Assertions.assertEquals(deadAt, committedTokenOnFreshConnection(token).get("dead_at"));
    }

    /**
     * An ACTIVE link past {@code expires_at}: the accept with an INVALID body (an unknown file part, an
     * unknown form field, a repeated {@code expected_version_number} and no signature - a 400 on a live link)
     * returns 410 {@code INVOICE_LINK_EXPIRED}; the flip is COMMITTED (fresh connection) and no invoice row,
     * stored file, file on disk, email or store notification appears. A fully valid signing body afterwards is
     * still 410 (a dead link is never signable) and changes nothing.
     */
    @Test
    void lazyExpiry_acceptOnAnOverAgeActiveLink_evenWithAnInvalidBody_returns410InvoiceLinkExpired_flipCommitted_noInvoiceChange()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String token = sendLink(order);
        backdateExpiry(token);
        resetSenders();
        Map<String, Object> tokenBefore = invoiceTokenRow(token);
        OrderState before = orderState(order);

        LocalDateTime startedAt = LocalDateTime.now();
        MvcResult result = publicInvoiceAccept(token, "accept of an over-age link with an invalid body",
                file(photoPart()), field("note", "x"), version("1", "1"));
        LocalDateTime finishedAt = LocalDateTime.now();
        assertGone(result, STATE_EXPIRED, "accept of an over-age link with an invalid body");

        Timestamp deadAt = assertExpiryCommitted(token, tokenBefore, startedAt, finishedAt, "the accept");
        assertInvoiceSideUntouched(before, orderState(order), "the accept's expiry flip");

        assertGone(publicInvoiceAccept(token, "a valid signing body afterwards", version("1"), pngSignature()),
                STATE_EXPIRED, "a valid signing body on the expired link");
        assertInvoiceSideUntouched(before, orderState(order), "the second accept");
        Assertions.assertEquals(deadAt, committedTokenOnFreshConnection(token).get("dead_at"),
                "dead_at is not re-stamped");
    }

    // ================================================================
    // 5. Dead-state gate precedence (16G contract 9.4 step 1, 9.5 step 1)
    // ================================================================

    /**
     * One order carries one link in each dead state, each produced by its real cause: REPLACED (a second
     * send), REWRITTEN (a Rewrite), SIGNED (a remote signing), EXPIRED (a committed lazy expiry); its current v4
     * is unsigned with no live link. For EVERY dead link the accept answers that state's 410 - code and exact
     * message - whatever the body: no parts at all, only unknown parts, a query-string parameter, no signature,
     * an empty signature, a non-PNG signature, a repeated or non-integer {@code expected_version_number}, a
     * stale version, and even a fully valid signing body for the current v4. Never a 400, 409 or 422, and
     * nothing is written (no invoice version, no file, no token change, no email or notification).
     */
    @Test
    void deadLinks_acceptWithAnInvalidOrEmptyBody_returnsEachDeadStates410_neverA400Or409Or422_nothingWritten()
            throws Exception {
        DeadLinks links = deadLinksOnOneOrder();
        OrderState before = orderState(links.order());
        String current = String.valueOf(links.currentVersion());

        for (Map.Entry<String, String> dead : links.tokensByState().entrySet()) {
            String state = dead.getKey();
            String token = dead.getValue();
            String label = state + " link: accept with ";
            assertGone(publicInvoiceAccept(token, label + "no parts at all"), state, label + "no parts at all");
            assertGone(publicInvoiceAccept(token, label + "only unknown parts", file(photoPart()), field("note", "x"),
                    field("accepted_customer_name", "Someone Else")), state, label + "only unknown parts");
            assertGone(publicInvoiceAcceptWithQuery(token, EXPECTED_VERSION_FIELD + "=" + current + "&x=1",
                            label + "query-string parameters", version(current), pngSignature()), state,
                    label + "query-string parameters");
            assertGone(publicInvoiceAccept(token, label + "no signature", version(current)), state,
                    label + "no signature (a 422 on a live link)");
            assertGone(publicInvoiceAccept(token, label + "an empty signature", version(current),
                    file(new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", new byte[0]))), state,
                    label + "an empty signature");
            assertGone(publicInvoiceAccept(token, label + "a JPEG signature", version(current),
                    file(new MockMultipartFile(SIGNATURE_PART, "signature.jpg", "image/jpeg", SIGNATURE_PNG))), state,
                    label + "a non-PNG signature (a 400 on a live link)");
            assertGone(publicInvoiceAccept(token, label + "a repeated version", version(current, current),
                    pngSignature()), state, label + "a repeated expected_version_number");
            assertGone(publicInvoiceAccept(token, label + "a non-integer version", version("abc"), pngSignature()),
                    state, label + "a non-integer expected_version_number");
            assertGone(publicInvoiceAccept(token, label + "a stale version", version("1"), pngSignature()), state,
                    label + "a stale version (a 409 on a live link)");
            assertGone(publicInvoiceAccept(token, label + "a valid signing body", version(current), pngSignature()),
                    state, label + "a fully valid signing body for the current version");
        }
        assertOrderStateUnchanged(before, orderState(links.order()), "accepts on the four dead links");
        Assertions.assertNull(invoiceRow(links.order().orderId(), links.currentVersion()).get("accepted_at"),
                "the current version stays unsigned");
    }

    /**
     * The pdf gate comes first too: for every dead link (REPLACED, REWRITTEN, SIGNED, EXPIRED), a missing, a
     * blank, a zero, a non-integer, a stale and the current {@code version_number} all give that state's 410
     * (never a 400 or 409), no PDF bytes; the GET is the 200 minimal dead payload with the same message. An
     * unknown token with an invalid or missing {@code version_number} is the 404. Nothing is written.
     */
    @Test
    void deadLinks_pdfGateComesFirst_missingOrInvalidVersionNumberStillGetsEachDeadStates410_getIsTheDeadPayload()
            throws Exception {
        DeadLinks links = deadLinksOnOneOrder();
        OrderState before = orderState(links.order());
        String current = String.valueOf(links.currentVersion());

        for (Map.Entry<String, String> dead : links.tokensByState().entrySet()) {
            String state = dead.getKey();
            String token = dead.getValue();
            Map<String, String> queries = new LinkedHashMap<>();
            queries.put("a missing version_number", null);
            queries.put("a blank version_number", "version_number=");
            queries.put("version_number 0", "version_number=0");
            queries.put("a non-integer version_number", "version_number=abc");
            queries.put("a stale version_number", "version_number=1");
            queries.put("the current version_number", "version_number=" + current);
            for (Map.Entry<String, String> query : queries.entrySet()) {
                String label = state + " link: pdf with " + query.getKey();
                MvcResult result = publicInvoicePdf(token, query.getValue(), label);
                assertGone(result, state, label);
                Assertions.assertFalse(startsWithPdfMagic(result.getResponse().getContentAsByteArray()),
                        label + ": no PDF bytes");
            }
            MvcResult view = publicInvoiceGet(token, state + " link: GET");
            assertStatus(view, 200, state + " link: GET");
            Assertions.assertEquals(deadPayload(state), readJson(view), state + " link: the minimal dead payload");
        }

        String unknown = randomUrlSafe(43);
        for (String query : new String[] {null, "version_number=abc", "version_number=0", "version_number="}) {
            assertTokenNotFound(publicInvoicePdf(unknown, query, "unknown token, query " + query),
                    "an unknown token with version query " + query);
        }
        assertOrderStateUnchanged(before, orderState(links.order()), "pdf reads and GETs on the four dead links");

        // Only an ACTIVE link can expire (16G contract 7.5, 7.7): every dead link, its expiry backdated, keeps its
        // own state on the GET, the pdf and the accept, and no row changes (no relabel, no dead_at re-stamp).
        for (String state : List.of(STATE_REPLACED, STATE_REWRITTEN, STATE_SIGNED)) {
            backdateExpiry(links.tokensByState().get(state));
        }
        OrderState backdated = orderState(links.order());
        for (Map.Entry<String, String> dead : links.tokensByState().entrySet()) {
            String state = dead.getKey();
            String label = state + " link past expires_at";
            MvcResult view = publicInvoiceGet(dead.getValue(), label + ": GET");
            assertStatus(view, 200, label + ": GET");
            Assertions.assertEquals(deadPayload(state), readJson(view), label + ": still its own dead payload");
            assertGone(publicInvoicePdf(dead.getValue(), "version_number=" + current, label + ": pdf"), state,
                    label + ": pdf");
            assertGone(publicInvoiceAccept(dead.getValue(), label + ": accept", version(current), pngSignature()),
                    state, label + ": accept");
        }
        assertOrderStateUnchanged(backdated, orderState(links.order()), "dead links past expires_at");
    }

    /**
     * Lazy expiry is public-request-only (16G contract 7.5, 7.7): an over-age ACTIVE link read by staff (D.3)
     * reports {@code invoice_link_expires_at} null and is NOT flipped (the row is unchanged, still ACTIVE); a
     * Rewrite then kills it with the writer's own reason, REWRITTEN - never EXPIRED - and the public GET, pdf and
     * accept show REWRITTEN.
     */
    @Test
    void lazyExpiry_isPublicOnly_aStaffReadNeverFlipsAnOverAgeLink_aRewriteKillsItAsRewrittenNotExpired()
            throws Exception {
        SeededOrder order = invoicedOrder();
        String token = sendLink(order);
        backdateExpiry(token);
        Map<String, Object> overAge = invoiceTokenRow(token);

        MvcResult current = mockMvc.perform(get(orderUrl(order.orderId()) + "/invoices/current")
                .session(tenant.session())).andReturn();
        assertStatus(current, 200, "D.3 of an order with an over-age link");
        JsonNode invoice = readJson(current).path("data").path("invoice");
        Assertions.assertTrue(invoice.has("invoice_link_expires_at") && invoice.path("invoice_link_expires_at").isNull(),
                "an over-age ACTIVE link is reported as null on InvoiceDetail: " + invoice);
        Assertions.assertEquals(overAge, invoiceTokenRow(token), "a staff read never flips (or writes) the link");

        rewrite(order, 1);
        Map<String, Object> killed = invoiceTokenRow(token);
        Assertions.assertEquals(STATE_REWRITTEN, killed.get("status"), "the writer's reason, not EXPIRED");
        Assertions.assertNotNull(killed.get("dead_at"), "the Rewrite stamped dead_at");

        MvcResult view = publicInvoiceGet(token, "GET after the Rewrite");
        assertStatus(view, 200, "GET after the Rewrite");
        Assertions.assertEquals(deadPayload(STATE_REWRITTEN), readJson(view), "the public GET shows REWRITTEN");
        assertGone(publicInvoicePdf(token, "version_number=2", "pdf after the Rewrite"), STATE_REWRITTEN,
                "pdf after the Rewrite");
        assertGone(publicInvoiceAccept(token, "accept after the Rewrite", version("2"), pngSignature()), STATE_REWRITTEN,
                "accept after the Rewrite");
        Assertions.assertEquals(killed, invoiceTokenRow(token), "a dead link never changes again");
    }

    // ================================================================
    // 6. No session, no Set-Cookie, whatever the status (16G contract 9.2)
    // ================================================================

    /**
     * One link through its life on the real filter chain, plus the misses: 200 (GET, pdf), 400 (pdf without
     * {@code version_number}; accept with a non-integer version), 404 (unknown token on all three), 409 (pdf and
     * accept of a stale version), 410 (accept and pdf after the signing), 415 (a non-multipart accept), 422 (no
     * signature), 500 (the stored file missing) and 201 (the remote signing itself). Every one of these public
     * responses - asserted inside {@link #publicCall} for every public request of this class - has no session
     * (none carried, none created) and no {@code Set-Cookie}; this test proves each status was really exercised.
     */
    @Test
    void everyPublicResponse_200_201_400_404_409_410_415_422_500_carriesNoSessionAndNoSetCookie() throws Exception {
        SeededOrder order = invoicedOrder();
        String token = sendLink(order);
        resetSenders();

        assertStatus(publicInvoiceGet(token, "200 GET"), 200, "200 GET");
        assertServesStoredPdf(publicInvoicePdf(token, "version_number=1", "200 pdf"), order, 1, "200 pdf");
        assertOneDetail(publicInvoicePdf(token, null, "400 pdf"), "version_number", REQUIRED, "400 pdf");
        assertOneDetail(publicInvoiceAccept(token, "400 accept", version("abc"), pngSignature()),
                EXPECTED_VERSION_FIELD, POSITIVE_INTEGER, "400 accept");
        String unknown = randomUrlSafe(43);
        assertTokenNotFound(publicInvoiceGet(unknown, "404 GET"), "404 GET");
        assertTokenNotFound(publicInvoiceAccept(unknown, "404 accept", version("1"), pngSignature()), "404 accept");
        assertTokenNotFound(publicInvoicePdf(unknown, "version_number=1", "404 pdf"), "404 pdf");
        assertStalePdf(publicInvoicePdf(token, "version_number=2", "409 pdf"), "409 pdf");
        assertExactError(publicInvoiceAccept(token, "409 accept", version("2"), pngSignature()), 409, STALE,
                STALE_MESSAGE, "409 accept");
        assertExactError(publicCall(post(URI.create(PUBLIC_INVOICES + token + "/accept"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"), "415 accept"),
                415, VALIDATION_FAILED, VALIDATION_FAILED_MESSAGE, "415 accept");
        assertExactError(publicInvoiceAccept(token, "422 accept", version("1")), 422, SIGNATURE_REQUIRED,
                SIGNATURE_REQUIRED_MESSAGE, "422 accept");

        StoredPdf v1 = storedPdf(order.orderId(), 1);
        Path file = physicalPath(v1.storagePath());
        Path moved = file.resolveSibling(file.getFileName() + ".moved-away");
        Files.move(file, moved);
        try {
            assertExactError(publicInvoicePdf(token, "version_number=1", "500 pdf"), 500, INTERNAL_ERROR,
                    INTERNAL_ERROR_MESSAGE, "500 pdf");
        } finally {
            Files.move(moved, file);
        }

        acceptRemotelyOk(token, 1, "201 accept");
        assertGone(publicInvoiceAccept(token, "410 accept", version("2"), pngSignature()), STATE_SIGNED, "410 accept");
        assertGone(publicInvoicePdf(token, "version_number=2", "410 pdf"), STATE_SIGNED, "410 pdf");
        MvcResult deadView = publicInvoiceGet(token, "200 dead GET");
        assertStatus(deadView, 200, "200 dead GET");
        Assertions.assertEquals(deadPayload(STATE_SIGNED), readJson(deadView), "the SIGNED dead payload");

        Assertions.assertEquals(new TreeSet<>(List.of(200, 201, 400, 404, 409, 410, 415, 422, 500)), publicStatusesSeen,
                "every status was exercised on the public surface (each asserted session- and cookie-free)");
    }

    // ================================================================
    // Fixture: one order with a link in each dead state
    // ================================================================

    /**
     * v1 (D.1) → link 1 → link 2 (link 1 REPLACED) → Rewrite v2 (link 2 REWRITTEN) → link 3 → remote signing
     * v3 (link 3 SIGNED) → Rewrite v4 (no live link) → link 4 → expiry backdated → public GET (link 4 EXPIRED,
     * committed). Each cause is the real one; the current v4 is unsigned and has no ACTIVE link.
     */
    private DeadLinks deadLinksOnOneOrder() throws Exception {
        SeededOrder order = invoicedOrder();
        String replaced = sendLink(order);
        String rewritten = sendLink(order);
        Map<String, Object> rewrittenRow = invoiceTokenRow(rewritten);
        Assertions.assertEquals(STATE_REPLACED, invoiceTokenRow(replaced).get("status"), "setup: the second send replaced link 1");
        Assertions.assertEquals(rewrittenRow.get("sent_at"), invoiceTokenRow(replaced).get("dead_at"),
                "setup: link 1 died at link 2's sent_at");

        rewrite(order, 1);
        Assertions.assertEquals(2, currentVersion(order.orderId()), "setup: the Rewrite appended v2");
        Assertions.assertEquals(STATE_REWRITTEN, invoiceTokenRow(rewritten).get("status"), "setup: the Rewrite killed link 2");

        String signed = sendLink(order);
        acceptRemotelyOk(signed, 2, "setup: remote signing of v2");
        Assertions.assertEquals(3, currentVersion(order.orderId()), "setup: the remote signing appended v3");
        Map<String, Object> v3 = invoiceRow(order.orderId(), 3);
        Assertions.assertNotNull(v3.get("accepted_at"), "setup: v3 is signed");
        Assertions.assertEquals(STATE_SIGNED, invoiceTokenRow(signed).get("status"), "setup: the signing killed link 3");
        Assertions.assertEquals(v3.get("accepted_at"), invoiceTokenRow(signed).get("dead_at"),
                "setup: link 3 died at the signing time");

        rewrite(order, 3);
        Assertions.assertEquals(4, currentVersion(order.orderId()), "setup: the Rewrite appended the unsigned v4");
        String expired = sendLink(order);
        backdateExpiry(expired);
        MvcResult flip = publicInvoiceGet(expired, "setup: lazy expiry of link 4");
        assertStatus(flip, 200, "setup: lazy expiry of link 4");
        Assertions.assertEquals(STATE_EXPIRED, invoiceTokenRow(expired).get("status"), "setup: link 4 flipped to EXPIRED");

        Assertions.assertNull(invoiceRow(order.orderId(), 4).get("accepted_at"), "setup: v4 is unsigned");
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM invoice_token WHERE order_id = ? AND status = 'ACTIVE'",
                order.orderId()), "setup: no live link remains");
        resetSenders();
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put(STATE_REPLACED, replaced);
        tokens.put(STATE_REWRITTEN, rewritten);
        tokens.put(STATE_SIGNED, signed);
        tokens.put(STATE_EXPIRED, expired);
        return new DeadLinks(order, 4, tokens);
    }

    // ================================================================
    // Public requests (EVERY public request goes through publicCall)
    // ================================================================

    /**
     * Perform one public request on the real filter chain and assert, whatever the status, that it carries no
     * session (none sent, none created) and that the response sets no cookie.
     */
    private MvcResult publicCall(RequestBuilder request, String label) throws Exception {
        return publicCallOn(mockMvc, request, label);
    }

    /** {@link #publicCall} on a given MockMvc (a worker thread's own instance, also on the real filter chain). */
    private MvcResult publicCallOn(MockMvc mvc, RequestBuilder request, String label) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        Assertions.assertNull(result.getRequest().getSession(false),
                () -> label + ": a public request neither carries nor creates a session (status "
                        + result.getResponse().getStatus() + ")");
        Assertions.assertTrue(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).isEmpty(),
                () -> label + ": a public response sets no cookie, but was " + result.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        Assertions.assertEquals(0, result.getResponse().getCookies().length,
                () -> label + ": a public response adds no cookie");
        publicStatusesSeen.add(result.getResponse().getStatus());
        return result;
    }

    /** GET /public/invoices/{token}; {@code rawToken} is sent as given (already URL-encoded where needed). */
    private MvcResult publicInvoiceGet(String rawToken, String label) throws Exception {
        return publicCall(get(URI.create(PUBLIC_INVOICES + rawToken)), label);
    }

    /** GET /public/invoices/{token}/pdf with {@code rawQuery} as the query string (none when null). */
    private MvcResult publicInvoicePdf(String rawToken, String rawQuery, String label) throws Exception {
        String uri = PUBLIC_INVOICES + rawToken + "/pdf" + (rawQuery == null ? "" : "?" + rawQuery);
        return publicCall(get(URI.create(uri)), label);
    }

    /** POST /public/invoices/{token}/accept as multipart carrying exactly {@code parts} (none = an empty multipart). */
    private MvcResult publicInvoiceAccept(String rawToken, String label, Part... parts) throws Exception {
        return publicInvoiceAcceptWithQuery(rawToken, null, label, parts);
    }

    private MvcResult publicInvoiceAcceptWithQuery(String rawToken, String rawQuery, String label, Part... parts)
            throws Exception {
        String uri = PUBLIC_INVOICES + rawToken + "/accept" + (rawQuery == null ? "" : "?" + rawQuery);
        return publicCall(withParts(multipart(URI.create(uri)), parts), label);
    }

    private static MockMultipartHttpServletRequestBuilder withParts(MockMultipartHttpServletRequestBuilder request,
                                                                    Part... parts) {
        for (Part part : parts) {
            part.addTo(request);
        }
        return request;
    }

    /**
     * The GET, the accept (a valid signing body, and no parts) and the pdf (a valid, a missing and a non-integer
     * {@code version_number}) responses to {@code rawToken}, each asserted 404 with a JSON body.
     */
    private Map<String, NotFoundResponse> notFoundResponses(String rawToken, String label) throws Exception {
        Map<String, NotFoundResponse> responses = new LinkedHashMap<>();
        responses.put("GET", notFound(publicInvoiceGet(rawToken, label + ": GET"), label + ": GET"));
        responses.put("accept with a valid signing body", notFound(
                publicInvoiceAccept(rawToken, label + ": accept", version("1"), pngSignature()), label + ": accept"));
        responses.put("accept with no parts", notFound(
                publicInvoiceAccept(rawToken, label + ": empty accept"), label + ": empty accept"));
        responses.put("pdf of version 1", notFound(
                publicInvoicePdf(rawToken, "version_number=1", label + ": pdf"), label + ": pdf"));
        responses.put("pdf without version_number", notFound(
                publicInvoicePdf(rawToken, null, label + ": pdf, no version"), label + ": pdf, no version"));
        responses.put("pdf of a non-integer version", notFound(
                publicInvoicePdf(rawToken, "version_number=abc", label + ": pdf, abc"), label + ": pdf, abc"));
        return responses;
    }

    private static NotFoundResponse notFound(MvcResult result, String label) throws Exception {
        assertStatus(result, 404, label);
        assertJsonContentType(result, label);
        return new NotFoundResponse(result.getResponse().getStatus(), result.getResponse().getContentType(),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ================================================================
    // Response assertions
    // ================================================================

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + ": unexpected status; body: " + body(result));
    }

    private static void assertJsonContentType(MvcResult result, String label) {
        String contentType = result.getResponse().getContentType();
        Assertions.assertNotNull(contentType, label + ": a JSON body is expected");
        Assertions.assertTrue(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON),
                label + ": a JSON body is expected, was " + contentType);
    }

    /** {@code status} with EXACTLY {@code {"error":{"code":..,"message":..}}} (no details, no data). */
    private static void assertExactError(MvcResult result, int status, String code, String message, String label)
            throws Exception {
        assertStatus(result, status, label);
        assertJsonContentType(result, label);
        Assertions.assertEquals(errorJson(code, message), readJson(result), label + ": the exact error body");
    }

    private static void assertTokenNotFound(MvcResult result, String label) throws Exception {
        assertExactError(result, 404, TOKEN_NOT_FOUND, TOKEN_NOT_FOUND_MESSAGE, label);
    }

    private static void assertQuoteTokenNotFound(MvcResult result, String label) throws Exception {
        assertExactError(result, 404, QUOTE_TOKEN_NOT_FOUND, QUOTE_TOKEN_NOT_FOUND_MESSAGE, label);
    }

    private static void assertGone(MvcResult result, String state, String label) throws Exception {
        assertExactError(result, 410, DEAD_CODES.get(state), DEAD_MESSAGES.get(state), label);
    }

    /** 409 INVOICE_VERSION_STALE, exactly, with no PDF bytes (history is never served). */
    private static void assertStalePdf(MvcResult result, String label) throws Exception {
        assertExactError(result, 409, STALE, STALE_MESSAGE, label);
        Assertions.assertFalse(startsWithPdfMagic(result.getResponse().getContentAsByteArray()), label + ": no PDF bytes");
        Assertions.assertNull(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION),
                label + ": no Content-Disposition");
    }

    /** 400 VALIDATION_FAILED with EXACTLY one detail ({@code field}, {@code message}). */
    private static void assertOneDetail(MvcResult result, String field, String message, String label) throws Exception {
        assertStatus(result, 400, label);
        assertJsonContentType(result, label);
        ObjectNode detail = JSON.createObjectNode();
        detail.put("field", field);
        detail.put("message", message);
        ArrayNode details = JSON.createArrayNode();
        details.add(detail);
        ObjectNode error = JSON.createObjectNode();
        error.put("code", VALIDATION_FAILED);
        error.put("message", VALIDATION_FAILED_MESSAGE);
        error.set("details", details);
        ObjectNode root = JSON.createObjectNode();
        root.set("error", error);
        Assertions.assertEquals(root, readJson(result), label + ": exactly one detail on " + field);
        Assertions.assertFalse(startsWithPdfMagic(result.getResponse().getContentAsByteArray()), label + ": no PDF bytes");
    }

    /**
     * 200 {@code application/pdf}; {@code Content-Disposition} exactly
     * {@code inline; filename="invoice-{order_number}-v{N}.pdf"} (the stored name); {@code Content-Length} = the
     * stored size; the body byte-identical to the version's stored file on disk.
     */
    private void assertServesStoredPdf(MvcResult result, SeededOrder order, int versionNumber, String label)
            throws Exception {
        assertStatus(result, 200, label);
        StoredPdf stored = storedPdf(order.orderId(), versionNumber);
        String expectedName = "invoice-" + order.orderNumber() + "-v" + versionNumber + ".pdf";
        Assertions.assertEquals(expectedName, stored.fileName(), label + ": the stored file name");
        Assertions.assertEquals("application/pdf", stored.mimeType(), label + ": the stored mime type");
        Assertions.assertEquals("application/pdf", result.getResponse().getContentType(), label + ": Content-Type");
        Assertions.assertEquals(List.of("inline; filename=\"" + expectedName + "\""),
                result.getResponse().getHeaders(HttpHeaders.CONTENT_DISPOSITION), label + ": Content-Disposition, exactly");
        Assertions.assertEquals(String.valueOf(stored.fileSize()), result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH),
                label + ": Content-Length = the stored size");
        byte[] served = result.getResponse().getContentAsByteArray();
        Assertions.assertEquals(stored.fileSize(), served.length, label + ": the served length");
        Assertions.assertArrayEquals(stored.bytes(), served, label + ": the stored bytes, verbatim");
        Assertions.assertTrue(startsWithPdfMagic(served), label + ": a PDF");
    }

    /** The minimal dead payload: state, business_name and message; every other key present and null. */
    private static JsonNode deadPayload(String state) {
        ObjectNode data = JSON.createObjectNode();
        for (String key : PUBLIC_VIEW_KEYS) {
            data.putNull(key);
        }
        data.put("state", state);
        data.put("business_name", BUSINESS_NAME);
        data.put("message", DEAD_MESSAGES.get(state));
        ObjectNode root = JSON.createObjectNode();
        root.set("data", data);
        root.put("message", LOADED_MESSAGE);
        return root;
    }

    private static void assertKeys(JsonNode data, String label) {
        Set<String> keys = new LinkedHashSet<>();
        data.fieldNames().forEachRemaining(keys::add);
        Assertions.assertEquals(new TreeSet<>(PUBLIC_VIEW_KEYS), new TreeSet<>(keys), label + ": exactly the 27 keys");
    }

    private static JsonNode errorJson(String code, String message) {
        ObjectNode error = JSON.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        ObjectNode root = JSON.createObjectNode();
        root.set("error", error);
        return root;
    }

    private static void assertServiceGateRejects(ApiException ex, String label) {
        Assertions.assertNotNull(ex, label + ": the gate must reject the token");
        Assertions.assertEquals(ErrorCode.INVOICE_TOKEN_NOT_FOUND, ex.getErrorCode(), label + ": the code");
        Assertions.assertEquals(TOKEN_NOT_FOUND_MESSAGE, ex.getMessage(), label + ": the message");
        Assertions.assertNull(ex.getDetails(), label + ": no details");
    }

    // ================================================================
    // Lazy-expiry assertions (committed state, fresh connection)
    // ================================================================

    /**
     * Read back on a fresh auto-commit connection (no transaction on the test thread): the token is EXPIRED,
     * {@code dead_at} was stamped during the request, and nothing else of the row changed. Returns dead_at.
     */
    private Timestamp assertExpiryCommitted(String token, Map<String, Object> tokenBefore, LocalDateTime startedAt,
                                            LocalDateTime finishedAt, String label) throws SQLException {
        Assertions.assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                label + ": the test thread holds no transaction, so the reads see committed data only");
        Map<String, Object> committed = committedTokenOnFreshConnection(token);
        Assertions.assertEquals(STATE_EXPIRED, committed.get("status"), label + ": the flip to EXPIRED is committed");
        Timestamp deadAt = (Timestamp) committed.get("dead_at");
        Assertions.assertNotNull(deadAt, label + ": dead_at is committed");
        LocalDateTime dead = deadAt.toLocalDateTime();
        Assertions.assertFalse(dead.isBefore(startedAt.minusSeconds(1)) || dead.isAfter(finishedAt.plusSeconds(1)),
                label + ": dead_at " + dead + " was stamped during the request [" + startedAt + ", " + finishedAt + "]");
        for (String column : List.of("sent_at", "expires_at", "emailed_at", "sent_by_user_id", "order_id")) {
            Assertions.assertEquals(tokenBefore.get(column), committed.get(column),
                    label + ": the expiry changes only status and dead_at, not " + column);
        }
        return deadAt;
    }

    /** One committed invoice_token row, read on a fresh pooled connection in auto-commit mode. */
    private Map<String, Object> committedTokenOnFreshConnection(String token) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status, dead_at, sent_at, expires_at, emailed_at, sent_by_user_id, order_id "
                             + "FROM invoice_token WHERE token_hash = ?")) {
            Assertions.assertTrue(connection.getAutoCommit(), "a fresh auto-commit connection");
            statement.setString(1, sha256Hex(token));
            try (ResultSet rs = statement.executeQuery()) {
                Assertions.assertTrue(rs.next(), "the token row exists");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("status", rs.getString("status"));
                row.put("dead_at", rs.getTimestamp("dead_at"));
                row.put("sent_at", rs.getTimestamp("sent_at"));
                row.put("expires_at", rs.getTimestamp("expires_at"));
                row.put("emailed_at", rs.getTimestamp("emailed_at"));
                row.put("sent_by_user_id", rs.getLong("sent_by_user_id"));
                row.put("order_id", rs.getLong("order_id"));
                Assertions.assertFalse(rs.next(), "exactly one row per hash");
                return row;
            }
        }
    }

    /** Everything of the order except its invoice links: no invoice row, order row, file or message moved. */
    private static void assertInvoiceSideUntouched(OrderState before, OrderState after, String label) {
        Assertions.assertEquals(before.invoices(), after.invoices(), label + ": no invoice row may change");
        Assertions.assertEquals(before.salesOrder(), after.salesOrder(), label + ": the order row is untouched");
        Assertions.assertEquals(before.storedFiles(), after.storedFiles(), label + ": no stored_file row");
        Assertions.assertEquals(before.filesOnDisk(), after.filesOnDisk(), label + ": no file on disk");
        Assertions.assertEquals(before.quoteVersions(), after.quoteVersions(), label + ": no quote_version change");
        Assertions.assertEquals(before.quoteTokens(), after.quoteTokens(), label + ": no quote_token change");
        Assertions.assertEquals(before.recorders(), after.recorders(), label + ": no email or notification attempted");
    }

    // ================================================================
    // Order state snapshots ("nothing written")
    // ================================================================

    /** Every row a public invoice request (or a misrouted quote one) could touch for the order, plus its files. */
    private OrderState orderState(SeededOrder order) {
        long orderId = order.orderId();
        return new OrderState(
                row("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId),
                rows("SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY t.quote_token_id", orderId),
                rows("SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                        orderStoragePrefix(orderId) + "%"),
                filesOnDisk(orderId),
                recorderCounts());
    }

    private static void assertOrderStateUnchanged(OrderState before, OrderState after, String label) {
        Assertions.assertEquals(before.salesOrder(), after.salesOrder(), label + ": the sales_order row");
        Assertions.assertEquals(before.invoices(), after.invoices(), label + ": the invoice rows");
        Assertions.assertEquals(before.invoiceTokens(), after.invoiceTokens(), label + ": the invoice_token rows");
        Assertions.assertEquals(before.quoteVersions(), after.quoteVersions(), label + ": the quote_version rows");
        Assertions.assertEquals(before.quoteTokens(), after.quoteTokens(), label + ": the quote_token rows");
        Assertions.assertEquals(before.storedFiles(), after.storedFiles(), label + ": the stored_file rows");
        Assertions.assertEquals(before.filesOnDisk(), after.filesOnDisk(), label + ": the files on disk");
        Assertions.assertEquals(before.recorders(), after.recorders(), label + ": the recorded emails and notifications");
        Assertions.assertEquals(before, after, label + ": the whole order state");
    }

    /** Sent and failed counts of the five recording senders. */
    private List<Integer> recorderCounts() {
        return List.of(
                invoiceEmailSender.sentEmails().size(), invoiceEmailSender.failedEmails().size(),
                invoiceLinkEmailSender.sentEmails().size(), invoiceLinkEmailSender.failedEmails().size(),
                invoiceNotificationSender.sentNotifications().size(), invoiceNotificationSender.failedNotifications().size(),
                quoteEmailSender.sentEmails().size(), quoteEmailSender.failedEmails().size(),
                quoteNotificationSender.sentNotifications().size(), quoteNotificationSender.failedNotifications().size());
    }

    // ================================================================
    // Protected requests (staff session; committed for real)
    // ================================================================

    private MvcResult protectedPost(String url, String json, int expectedStatus, String label) throws Exception {
        MvcResult result = mockMvc.perform(post(url).session(tenant.session())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertStatus(result, expectedStatus, label);
        return result;
    }

    /** D.1 Create (empty body): 201, v1. */
    private void createInvoice(SeededOrder order) throws Exception {
        protectedPost(orderUrl(order.orderId()) + "/invoices", "{}", 201, "setup: D.1 create");
        Assertions.assertEquals(1, currentVersion(order.orderId()), "setup: D.1 committed v1");
    }

    /** D.2 Rewrite carrying {@code currentVersion} (Batch A PR 2, #149): 201, a new unsigned version. */
    private void rewrite(SeededOrder order, int currentVersion) throws Exception {
        protectedPost(orderUrl(order.orderId()) + "/invoices/rewrite",
                "{\"expected_version_number\":" + currentVersion + "}", 201, "setup: D.2 rewrite");
    }

    /** D.7 CASH payment: 201, a new version with its own PDF (never emails). */
    private void recordCashPayment(SeededOrder order, String amount) throws Exception {
        protectedPost(orderUrl(order.orderId()) + "/payments",
                "{\"payment_method\": \"CASH\", \"amount\": " + amount + "}", 201, "setup: D.7 payment");
    }

    /** D.8 in-store signing of the current version: 201. */
    private void signInStore(SeededOrder order, int expectedVersion) throws Exception {
        MvcResult result = mockMvc.perform(multipart(orderUrl(order.orderId()) + "/invoices/current/accept")
                        .file(new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", SIGNATURE_PNG))
                        .param(EXPECTED_VERSION_FIELD, Integer.toString(expectedVersion))
                        .session(tenant.session()))
                .andReturn();
        assertStatus(result, 201, "setup: D.8 in-store signing");
    }

    /**
     * POST send-link {} on the order's current invoice: 201 with the link times; the plaintext token is taken
     * from the recorded link-only email (the link exactly once), whose recipient, subject, body, order id and
     * version are the send's; the token row is the ACTIVE row of that hash, sent by the session user.
     */
    private String sendLink(SeededOrder order) throws Exception {
        int current = currentVersion(order.orderId());
        int sentBefore = invoiceLinkEmailSender.sentEmails().size();
        MvcResult result = protectedPost(orderUrl(order.orderId()) + "/invoices/current/send-link", "{}", 201,
                "setup: send-link");
        JsonNode body = readJson(result);
        Assertions.assertEquals(LINK_SENT_MESSAGE, body.path("message").asText(), "setup: send-link message");
        List<InvoiceLinkEmailRequest> sent = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly one link email recorded");
        InvoiceLinkEmailRequest email = sent.get(sent.size() - 1);
        String token = extractToken(INVOICE_LINK_PATTERN, email.bodyText(), "/i/");
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), "setup: the link email recipient");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), "setup: the link email subject");
        Assertions.assertEquals("Hi,\n\nYour invoice " + order.orderNumber() + " from " + BUSINESS_NAME
                        + " is ready to review and sign. You can view and sign your invoice online here: "
                        + PUBLIC_BASE_URL + "/i/" + token + "\n\nThank you.", email.bodyText(),
                "setup: the link email body");
        Assertions.assertEquals(order.orderId(), email.orderId(), "setup: the link email order id");
        Assertions.assertEquals(current, email.invoiceVersionNumber(), "setup: the link email version");
        Map<String, Object> row = invoiceTokenRow(token);
        Assertions.assertEquals("ACTIVE", row.get("status"), "setup: the new link is ACTIVE");
        Assertions.assertEquals(order.orderId(), toLong(row.get("order_id")), "setup: the link's order");
        Assertions.assertEquals(tenant.userId(), toLong(row.get("sent_by_user_id")), "setup: sent by the session user");
        Assertions.assertEquals(body.path("data").path("invoice_link").path("expires_at").asText(),
                WHOLE_SECONDS.format(((Timestamp) row.get("expires_at")).toLocalDateTime()),
                "setup: the 201 reports the stored expiry");
        return token;
    }

    /** The public remote signing of {@code expectedVersion}: 201 with exactly {"data":{"state":"SIGNED"},...}. */
    private void acceptRemotelyOk(String token, int expectedVersion, String label) throws Exception {
        MvcResult result = publicInvoiceAccept(token, label, version(String.valueOf(expectedVersion)), pngSignature());
        assertStatus(result, 201, label);
        ObjectNode data = JSON.createObjectNode();
        data.put("state", STATE_SIGNED);
        ObjectNode expected = JSON.createObjectNode();
        expected.set("data", data);
        expected.put("message", ACCEPTED_MESSAGE);
        Assertions.assertEquals(expected, readJson(result), label + ": the exact 201 body");
    }

    /** Itemised quote draft through the API: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex (above the 40.00 cost). */
    private void saveItemisedQuoteDraft(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(orderId) + "/quote/draft").session(tenant.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andReturn();
        assertStatus(result, 200, "setup: quote draft save");
    }

    /** Issue the draft through the protected send-email and take the plaintext token from the recorded email. */
    private String issueQuoteAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        protectedPost(orderUrl(orderId) + "/quote/send-email", "{}", 201, "setup: quote send-email");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly the quote email is recorded");
        return extractToken(QUOTE_LINK_PATTERN, sent.get(sent.size() - 1).bodyText(), "/q/");
    }

    private static String extractToken(Pattern pattern, String bodyText, String route) {
        Matcher matcher = pattern.matcher(bodyText);
        Assertions.assertTrue(matcher.find(), "setup: the email must carry the " + route + "{token} link: " + bodyText);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "setup: the link must appear exactly once in the email body");
        Assertions.assertEquals(1, countOccurrences(bodyText, token), "setup: the plaintext token appears exactly once");
        Assertions.assertEquals(1, countOccurrences(bodyText, PUBLIC_BASE_URL + route + token),
                "setup: the absolute link appears exactly once");
        return token;
    }

    private String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    // ================================================================
    // Multipart parts
    // ================================================================

    @FunctionalInterface
    private interface Part {
        void addTo(MockMultipartHttpServletRequestBuilder request);
    }

    private static Part version(String... values) {
        return request -> request.param(EXPECTED_VERSION_FIELD, values);
    }

    private static Part field(String name, String... values) {
        return request -> request.param(name, values);
    }

    private static Part file(MockMultipartFile file) {
        return request -> request.file(file);
    }

    private static Part pngSignature() {
        return file(new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", SIGNATURE_PNG));
    }

    private static MockMultipartFile photoPart() {
        return new MockMultipartFile("photo", "photo.png", "image/png", SIGNATURE_PNG);
    }

    // ================================================================
    // Token rows (planting, re-keying, backdating, reading)
    // ================================================================

    /** Insert one invoice_token row for {@code hashedPlaintext}'s SHA-256 (a dead status gets dead_at = sent_at). */
    private long plantInvoiceToken(long orderId, String hashedPlaintext, String status, LocalDateTime expiresAt) {
        LocalDateTime sentAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Timestamp deadAt = "ACTIVE".equals(status) ? null : Timestamp.valueOf(sentAt);
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO invoice_token (order_id, token_hash, status, sent_by_user_id, sent_at, expires_at, dead_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING invoice_token_id",
                Long.class, orderId, sha256Hex(hashedPlaintext), status, tenant.userId(), Timestamp.valueOf(sentAt),
                Timestamp.valueOf(expiresAt), deadAt);
        Assertions.assertNotNull(id, "setup: the planted invoice_token row");
        return id;
    }

    private void rekeyInvoiceToken(long invoiceTokenId, String newPlaintext) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice_token SET token_hash = ? WHERE invoice_token_id = ?", sha256Hex(newPlaintext), invoiceTokenId),
                "control: re-key exactly one planted row");
    }

    /** Move the link's expiry into the past by the JVM clock (the clock the service compares with). */
    private void backdateExpiry(String token) {
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice_token SET expires_at = ? WHERE token_hash = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)), sha256Hex(token)), "setup: backdate exactly one link");
    }

    private Map<String, Object> invoiceTokenRow(String token) {
        return row("SELECT * FROM invoice_token WHERE token_hash = ?", sha256Hex(token));
    }

    private Map<String, Object> quoteTokenRow(String token) {
        return row("SELECT * FROM quote_token WHERE token_hash = ?", sha256Hex(token));
    }

    private Map<String, Object> issuedQuoteVersion(long orderId) {
        return row("SELECT * FROM quote_version WHERE order_id = ? AND status = 'ISSUED'", orderId);
    }

    private byte[] issuedQuotePdfBytes(long orderId) throws IOException {
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT sf.storage_path FROM quote_version v JOIN stored_file sf ON sf.stored_file_id = v.issued_pdf_file_id "
                        + "WHERE v.order_id = ? AND v.status = 'ISSUED'", String.class, orderId);
        return Files.readAllBytes(physicalPath(storagePath));
    }

    // ================================================================
    // Rows, files and PDFs
    // ================================================================

    private Map<String, Object> row(String sql, Object... args) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap(sql, args));
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
            copies.add(new LinkedHashMap<>(row));
        }
        return copies;
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private int currentVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "the order must have a current invoice");
        return version;
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return row("SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    /** A version's stored PDF: invoice.stored_file_id → stored_file → the bytes on disk (size-checked). */
    private StoredPdf storedPdf(long orderId, int versionNumber) throws IOException {
        Map<String, Object> file = row(
                "SELECT sf.file_name, sf.storage_path, sf.mime_type, sf.file_size FROM invoice i "
                        + "JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id "
                        + "WHERE i.order_id = ? AND i.version_number = ?", orderId, versionNumber);
        String storagePath = (String) file.get("storage_path");
        Assertions.assertTrue(storagePath.startsWith(orderStoragePrefix(orderId)),
                "the PDF is stored under its order's directory: " + storagePath);
        byte[] bytes = Files.readAllBytes(physicalPath(storagePath));
        long fileSize = ((Number) file.get("file_size")).longValue();
        Assertions.assertEquals(fileSize, bytes.length, "stored_file.file_size matches the bytes on disk");
        return new StoredPdf((String) file.get("file_name"), storagePath, (String) file.get("mime_type"), fileSize, bytes);
    }

    private static boolean startsWithPdfMagic(byte[] bytes) {
        return bytes.length >= 5 && "%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII));
    }

    private static JsonNode readJson(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
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

    /** {@code length} characters of the URL-safe Base64 alphabet (never minted, never planted elsewhere). */
    private static String randomUrlSafe(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }

    // ================================================================
    // Storage paths (physical file = base dir + storage_path)
    // ================================================================

    private Path storageBase() {
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
    }

    private Path physicalPath(String storagePath) {
        Path resolved = resolveInsideStorage(storagePath);
        Assertions.assertNotNull(resolved, "storage path escapes the test storage dir: " + storagePath);
        return resolved;
    }

    private Path resolveInsideStorage(String storagePath) {
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        Path base = storageBase();
        Path resolved = base.resolve(relative).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    private String orderStoragePrefix(long orderId) {
        return "/uploads/" + tenant.businessId() + "/orders/" + orderId + "/";
    }

    private Path businessStorageDir(long businessId) {
        return storageBase().resolve("uploads").resolve(Long.toString(businessId));
    }

    private Path orderStorageDir(long businessId, long orderId) {
        return businessStorageDir(businessId).resolve("orders").resolve(Long.toString(orderId));
    }

    private Set<String> filesOnDisk(long orderId) {
        Path dir = orderStorageDir(tenant.businessId(), orderId);
        if (!Files.isDirectory(dir)) {
            return new TreeSet<>();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ================================================================
    // Seeding (committed: auto-commit JDBC + real API calls)
    // ================================================================

    /** The class's own tenant: business (unique slug, ABN + bank details, SOFT terms), one store, one salesperson. */
    private Tenant seedTenant() {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number, "
                        + " terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, '51 824 753 556', 'Tokenvale Bank', '062-000', ?, '12345678', ?, NULL) "
                        + "RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, BUSINESS_NAME + " Pty Ltd", SOFT_TERMS);
        Assertions.assertNotNull(businessId, "setup: business insert must return an id");
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Tokenvale Showroom', ?, '0290005555', ?, '5 Token Parade', 'Parramatta', 'NSW', '2150') "
                        + "RETURNING store_id",
                Integer.class, businessId, STORE_CODE, STORE_EMAIL);
        Assertions.assertNotNull(storeId, "setup: store insert must return an id");
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Piper', 'Token', ?, 'piper.token@tokenvale-public.example.com', "
                        + " 'not-a-login-hash-tokpdf') RETURNING user_id",
                Long.class, businessId, SALESPERSON_CODE);
        Assertions.assertNotNull(userId, "setup: app_user insert must return an id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        orderSequence = 0;
        return new Tenant(businessId, storeId, userId);
    }

    /**
     * An invoice-ready LEAD SOFT order: details of sale, proposed lay date 45 days ahead CONFIRMED, customer
     * Rowan Tokenfield with a valid email, INSTALLATION + BILLING addresses, one 100.00 ex / 40.00 cost charge.
     */
    private SeededOrder insertInvoiceReadyOrder() {
        int sequence = ++orderSequence;
        // order_number must match chk_sales_order_number_format: {store}.{LL#}.{#####}
        String orderNumber = ORDER_NUMBER_PREFIX + String.format("%05d", sequence);
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 42, 2026, ?, ?, "
                        + " 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, tenant.businessId(), tenant.storeId(), tenant.userId(), sequence, orderNumber,
                DETAILS_OF_SALE, Date.valueOf(LocalDate.now().plusDays(45)));
        Assertions.assertNotNull(orderId, "setup: order insert must return an id");
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) VALUES (?, ?, ?, ?, ?)",
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, CUSTOMER_EMAIL, "0412345678");
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '9', 'Install Way', 'Ryde', 'NSW', '2112')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, '3', '21', 'Billing Road', 'Sydney', 'NSW', '2000')",
                orderId);
        String chargeCode = CHARGE_CODE_PREFIX + sequence;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Tokenvale public test charge', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, tenant.storeId(), chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Tokenvale public test charge', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        return new SeededOrder(orderId, orderNumber);
    }

    /** An invoice-ready order with its unsigned v1 created through the real D.1 endpoint. */
    private SeededOrder invoicedOrder() throws Exception {
        SeededOrder order = insertInvoiceReadyOrder();
        createInvoice(order);
        return order;
    }

    // ================================================================
    // Cleanup (runs in @BeforeEach AND @AfterEach; every step runs even if an earlier one fails)
    // ================================================================

    private void cleanUpEverything() {
        runAll(this::resetSenders, this::sweepTenants);
        assertSweptClean();
    }

    private void resetSenders() {
        invoiceEmailSender.reset();
        invoiceLinkEmailSender.reset();
        invoiceNotificationSender.reset();
        quoteEmailSender.reset();
        quoteNotificationSender.reset();
    }

    private void assertSweptClean() {
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM business WHERE slug = ?", SLUG),
                "no tenant of this class may survive the sweep");
        if (tenant != null) {
            Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM invoice_token WHERE sent_by_user_id = ?",
                    tenant.userId()), "no invoice link of this class may survive the sweep");
            Assertions.assertFalse(Files.exists(businessStorageDir(tenant.businessId())),
                    "the tenant's storage dir must be removed");
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

    /** Every tenant of this class (normally none or one, found by its unique slug), FK-safe. */
    private void sweepTenants() {
        for (Long businessId : jdbcTemplate.queryForList(
                "SELECT business_id FROM business WHERE slug = ?", Long.class, SLUG)) {
            sweepTenant(businessId);
        }
    }

    private void sweepTenant(long businessId) {
        for (Long orderId : jdbcTemplate.queryForList(
                "SELECT order_id FROM sales_order WHERE business_id = ? ORDER BY order_id", Long.class, businessId)) {
            deleteOrderAndFiles(businessId, orderId);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
            // invoice_token.sent_by_user_id references app_user: no link of this tenant may outlive its users.
            jdbcTemplate.update("DELETE FROM invoice_token WHERE sent_by_user_id IN "
                    + "(SELECT user_id FROM app_user WHERE business_id = ?)", businessId);
            jdbcTemplate.update("DELETE FROM store_charge WHERE store_id IN (SELECT store_id FROM store WHERE business_id = ?)",
                    businessId);
            jdbcTemplate.update("DELETE FROM store_product WHERE store_id IN (SELECT store_id FROM store WHERE business_id = ?)",
                    businessId);
            jdbcTemplate.update("DELETE FROM user_store_access WHERE business_id = ?", businessId);
            jdbcTemplate.update("DELETE FROM app_user WHERE business_id = ?", businessId);
            jdbcTemplate.update("DELETE FROM store WHERE business_id = ?", businessId);
            // Its FK to business has no ON DELETE: wipe it before the business row.
            jdbcTemplate.update("DELETE FROM business_quick_description WHERE business_id = ?", businessId);
            jdbcTemplate.update("DELETE FROM business WHERE business_id = ?", businessId);
        });
        deleteDirectoryQuietly(businessStorageDir(businessId));
    }

    /** Delete every committed row of one order (FK-safe), its stored_file rows, its files and its directory. */
    private void deleteOrderAndFiles(long businessId, long orderId) {
        List<Map<String, Object>> files = jdbcTemplate.queryForList("""
                        SELECT stored_file_id, storage_path FROM stored_file
                        WHERE stored_file_id IN (
                                SELECT stored_file_id FROM invoice WHERE order_id = ?
                                UNION SELECT accepted_signature_file_id FROM invoice WHERE order_id = ?
                                UNION SELECT issued_pdf_file_id FROM quote_version WHERE order_id = ?
                                UNION SELECT accepted_signature_file_id FROM quote_version WHERE order_id = ?
                                UNION SELECT signed_pdf_file_id FROM quote_version WHERE order_id = ?
                                UNION SELECT stored_file_id FROM order_attachment WHERE order_id = ?)
                           OR storage_path LIKE ?
                        """,
                orderId, orderId, orderId, orderId, orderId, orderId,
                "/uploads/" + businessId + "/orders/" + orderId + "/%");
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
                // 16G PR 4: the order-bound invoice links go first (FKs to sales_order and app_user).
                jdbcTemplate.update("DELETE FROM invoice_token WHERE order_id = ?", orderId);
                jdbcTemplate.update("DELETE FROM quote_token WHERE quote_version_id IN "
                        + "(SELECT quote_version_id FROM quote_version WHERE order_id = ?)", orderId);
                // invoice before quote_version (V19 invoice.source_quote_version_id FK).
                jdbcTemplate.update("DELETE FROM invoice WHERE order_id = ?", orderId);
                jdbcTemplate.update("DELETE FROM quote_version WHERE order_id = ?", orderId); // cascades lines
                jdbcTemplate.update("DELETE FROM quote_draft WHERE order_id = ?", orderId);   // cascades lines
                jdbcTemplate.update("DELETE FROM payment_transaction WHERE order_id = ?", orderId);
                for (String childTable : ORDER_CHILD_TABLES) {
                    jdbcTemplate.update("DELETE FROM " + childTable + " WHERE order_id = ?", orderId);
                }
                jdbcTemplate.update("DELETE FROM sales_order WHERE order_id = ?", orderId);
                for (Map<String, Object> file : files) {
                    jdbcTemplate.update("DELETE FROM stored_file WHERE stored_file_id = ?", file.get("stored_file_id"));
                }
            });
        } finally {
            for (Map<String, Object> file : files) {
                deleteStoredFileQuietly((String) file.get("storage_path"));
            }
            deleteDirectoryQuietly(orderStorageDir(businessId, orderId));
        }
    }

    private void deleteStoredFileQuietly(String storagePath) {
        if (storagePath == null) {
            return;
        }
        Path file = resolveInsideStorage(storagePath);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best-effort cleanup; the directory sweep retries.
        }
    }

    private void deleteDirectoryQuietly(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        Path base = storageBase();
        if (!normalized.startsWith(base) || normalized.equals(base) || normalized.equals(base.resolve("uploads"))
                || !Files.isDirectory(normalized)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(normalized)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup of test files only.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of test files only.
        }
    }

    // ================================================================
    // The invoice_token table-lock probe (proof that a request never reads the table)
    // ================================================================

    /**
     * A transaction on its own pooled connection holding {@code ACCESS EXCLUSIVE} on {@code invoice_token}:
     * every statement that reads the table - the token lookup included - waits on it until {@link #release()}.
     * Work runs on a worker thread (public requests on their own MockMvc, the real filter chain, through
     * {@link #publicCallOn}), so work that does read the table can never hang the test thread; {@link #close()}
     * always releases the lock first, then lets the worker finish. Nothing on the test thread reads
     * invoice_token while the lock is held (the waiter probe reads only {@code pg_stat_activity}).
     */
    private final class InvoiceTokenTableLock implements AutoCloseable {

        private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tokpdf-table-lock-worker");
            thread.setDaemon(true);
            return thread;
        });
        private final MockMvc workerMockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        private final Connection holder;
        private final int holderPid;
        private boolean held;

        InvoiceTokenTableLock() throws SQLException {
            Connection connection = dataSource.getConnection();
            int pid;
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    try (ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
                        Assertions.assertTrue(rs.next(), "pg_backend_pid() returned a row");
                        pid = rs.getInt(1);
                    }
                    statement.execute("SET LOCAL lock_timeout = '5s'");
                    statement.execute("LOCK TABLE invoice_token IN ACCESS EXCLUSIVE MODE");
                }
            } catch (SQLException | RuntimeException e) {
                discard(connection);
                worker.shutdownNow();
                throw e;
            }
            this.holder = connection;
            this.holderPid = pid;
            this.held = true;
        }

        /** Run one public request on the worker; it must COMPLETE while the table stays locked. */
        MvcResult completes(RequestBuilder request, String label) throws Exception {
            Assertions.assertTrue(held, label + ": the table must still be locked");
            return await(submitPublic(request, label), label);
        }

        Future<MvcResult> submitPublic(RequestBuilder request, String label) {
            return worker.submit(() -> publicCallOn(workerMockMvc, request, label));
        }

        /** Run {@code call} on the worker; it must complete (while the table stays locked) by throwing an ApiException. */
        ApiException rejection(Callable<?> call, String label) throws Exception {
            Assertions.assertTrue(held, label + ": the table must still be locked");
            Future<ApiException> future = worker.submit(() -> {
                try {
                    call.call();
                } catch (ApiException expected) {
                    return expected;
                }
                return null;
            });
            return await(future, label);
        }

        <T> T await(Future<T> future, String label) throws Exception {
            try {
                return future.get(LOCK_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                Assertions.fail(label + ": did not complete within " + LOCK_PROBE_TIMEOUT_SECONDS
                        + "s - it is waiting on the invoice_token lock, i.e. it READ the table; activity: " + lockActivity());
                throw e;
            }
        }

        /** Bounded poll until a backend is observed waiting on THIS lock while reading invoice_token. */
        void awaitLookupWaiting(Future<?> request, String label) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BARRIER_TIMEOUT_MILLIS);
            while (System.nanoTime() < deadline) {
                if (!jdbcTemplate.queryForList(TABLE_LOCK_WAITER_SQL, Integer.class, holderPid).isEmpty()) {
                    return;
                }
                Assertions.assertFalse(request.isDone(),
                        label + ": completed without ever waiting on the invoice_token lock");
                Thread.sleep(POLL_INTERVAL_MILLIS); // a poll interval, not a barrier: the barrier is the DB condition
            }
            Assertions.fail(label + ": no read of invoice_token was observed waiting on the lock; activity: "
                    + lockActivity());
        }

        void release() {
            if (held) {
                held = false;
                discard(holder);
            }
        }

        @Override
        public void close() throws InterruptedException {
            try {
                release();
            } finally {
                worker.shutdown();
                if (!worker.awaitTermination(LOCK_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    worker.shutdownNow();
                }
            }
        }
    }

    /** End a holder transaction (rollback) and return its connection; never throws. */
    private static void discard(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the connection is returned below either way
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // the pool resets auto-commit on return anyway
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    /** The other backends of this database (diagnostics for a lock-probe failure). */
    private String lockActivity() {
        return jdbcTemplate.queryForList("""
                SELECT pid, state, wait_event_type, wait_event, pg_blocking_pids(pid)::text AS blocked_by,
                       left(query, 120) AS query
                FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid()
                """).toString();
    }

    // ================================================================
    // Records
    // ================================================================

    /** The committed tenant. The session follows the SessionContext casts: (Long) ids, (Integer) store. */
    private record Tenant(long businessId, int storeId, long userId) {

        MockHttpSession session() {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("user_id", Long.valueOf(userId));
            session.setAttribute("business_id", Long.valueOf(businessId));
            session.setAttribute("store_id", Integer.valueOf(storeId));
            return session;
        }
    }

    private record SeededOrder(long orderId, String orderNumber) {
    }

    /** One order, its current (unsigned, link-less) version, and one link per dead state. */
    private record DeadLinks(SeededOrder order, int currentVersion, Map<String, String> tokensByState) {
    }

    /** A version's stored PDF: its stored_file row values and its bytes on disk. */
    private record StoredPdf(String fileName, String storagePath, String mimeType, long fileSize, byte[] bytes) {
    }

    /** One 404 as the client sees it: status, content type and the raw body text (equality = identical). */
    private record NotFoundResponse(int status, String contentType, String body) {
    }

    /** Everything of one order a public request could change (equality = unchanged). */
    private record OrderState(
            Map<String, Object> salesOrder,
            List<Map<String, Object>> invoices,
            List<Map<String, Object>> invoiceTokens,
            List<Map<String, Object>> quoteVersions,
            List<Map<String, Object>> quoteTokens,
            List<Map<String, Object>> storedFiles,
            Set<String> filesOnDisk,
            List<Integer> recorders) {
    }
}
