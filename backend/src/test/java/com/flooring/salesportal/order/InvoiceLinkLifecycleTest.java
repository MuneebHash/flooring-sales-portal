package com.flooring.salesportal.order;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceAcceptanceNotificationRequest;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.InvoiceLinkEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.image.SignaturePngNormaliser;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import com.flooring.salesportal.common.storage.FileStorageService;
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
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16G PR 4 (16G contract sections 4.5, 7.5, 10.5 and 12; the PR 4 row of section 16) - the LIFECYCLE of the
 * order-bound invoice link ({@code invoice_token}, {@code V21}) at the REAL commit boundary, driven only through the
 * real endpoints: every token transition with its exact row, the writers that kill a link and the reasons they record,
 * the immutability of a dead reason, the D5(c) quote-link cancellation on remote signing, the LAID / CANCELLED
 * allowances, and the derived {@code InvoiceDetail.invoice_link_expires_at} on every InvoiceDetail response.
 *
 * <p><b>Rules under test.</b>
 * <ul>
 *   <li>Section 7.5 transitions, each with its exact token row: a send mints one {@code ACTIVE} token
 *       ({@code sent_by_user_id} = the session user, {@code expires_at} = {@code sent_at} + 7 days, {@code emailed_at}
 *       stamped after the delivery); a second send turns it {@code REPLACED} with {@code dead_at} = the new
 *       {@code sent_at}; a Rewrite (D.2) records {@code REWRITTEN}; D.8 records {@code SIGNED} with {@code dead_at} =
 *       the signing version's {@code accepted_at}; Path A records {@code SIGNED}; the remote accept records
 *       {@code SIGNED} on the presented token with {@code dead_at} = {@code accepted_at}; a public request on an over-age
 *       {@code ACTIVE} token flips it {@code EXPIRED} (lazy expiry) without touching any invoice row. Every other column
 *       of a killed row is unchanged.</li>
 *   <li>Payment (D.7) and void (D.10) before signing never touch the {@code ACTIVE} token; the link always shows the
 *       order's CURRENT version (section 7.4), the public pdf of an older version is a 409, and the remote accept with
 *       the new version signs it.</li>
 *   <li>A writer that kills an over-age {@code ACTIVE} token no public request has flipped records ITS reason, never
 *       {@code EXPIRED}; a dead row never changes status or {@code dead_at} again and is reported with its true reason
 *       even past its expiry; order status changes never touch a link (section 12).</li>
 *   <li>Section 10.5: the remote accept cancels an {@code ISSUED} quote version and its {@code ACTIVE} quote token
 *       (quote token {@code dead_at} = the invoice {@code accepted_at}), is a no-op without one, never touches an
 *       {@code ACCEPTED} quote and is not blocked by it; D5(b) then applies to Path A unchanged. Quote sends, quote
 *       cancel and the public quote accept never touch an invoice token.</li>
 *   <li>Section 12: send-link, public view / pdf and remote accept work on a LAID and on a CANCELLED order; a Rewrite
 *       of a LAID order is 422 {@code ORDER_LOCKED} and leaves the link {@code ACTIVE}.</li>
 *   <li>Section 4.5: {@code invoice_link_expires_at} is ALWAYS present on InvoiceDetail (D.1, D.2, D.3, D.8, D.9,
 *       Path A): the {@code ACTIVE}, unexpired link's {@code expires_at} in whole seconds, otherwise null (no link, a
 *       dead link of every reason, an over-age {@code ACTIVE} token - whose read writes nothing - and any signed
 *       current version); never on E.1 {@code current_invoice} (payment / void) or on any public invoice payload.</li>
 * </ul>
 *
 * <p><b>Fixtures - a fully self-seeded, COMMITTED tenant.</b> This class is NOT {@code @Transactional}: every MockMvc
 * call commits for real and every assertion reads committed rows (auto-commit JDBC) and the files on disk. Each test
 * seeds its own business (slug {@value #SLUG}, ABN / bank / Stripe / accent / soft terms), store {@value #STORE_CODE}
 * (with a notification email), two salespeople ({@value #FIRST_SALESPERSON_CODE} owns every order,
 * {@value #SECOND_SALESPERSON_CODE} sends some links) with store access, and invoice-ready orders (customer with a
 * PADDED valid email, INSTALLATION + BILLING addresses, details of sale, lay date 2026-12-01 CONFIRMED, one 100.00 ex /
 * 40.00 cost charge line). Invoices, links, payments, quotes and acceptances go through the real endpoints only; the
 * plaintext invoice token is read from the recorded link email. SQL is used only to backdate a token's
 * {@code expires_at}, to build the two artificial histories the API cannot produce (a REPLACED-only history, and an
 * {@code ACTIVE} row over a signed version), and to sweep.
 *
 * <p><b>Public requests</b> go through ONE helper ({@link #performPublicInvoiceRequest}) on a MockMvc that runs the
 * REAL Spring Security filter chain: it never carries a session and asserts, for every status, that no session was
 * created, no {@code Set-Cookie} was sent and no JSON body contains {@code invoice_link_expires_at}.
 *
 * <p><b>Sweep.</b> {@code @BeforeEach} and {@code @AfterEach} remove everything the class commits, FK-safe: per order
 * invoice_token, quote_token, invoice, quote_version, quote_draft, payment_transaction, the other order children,
 * sales_order and the order's stored_file rows (plus its files); then the tenant's store_charge / store_product,
 * user_store_access, any stray invoice_token of its users, app_user, store, business_quick_description and business
 * rows and the tenant's storage directory. Every recording sender is reset in both. No bean override: the class shares
 * the cached context of the other suites that write under the same storage base dir. Single-threaded.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class InvoiceLinkLifecycleTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before anything is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant: the slug is the sweep's only key ----
    private static final String SLUG = "pr4-link-life";
    private static final String BUSINESS_NAME = "Lifecycle Link Flooring";
    private static final String STORE_CODE = "ILL-ONE";
    private static final String STORE_EMAIL = "showroom.link.life@example.com";
    private static final String FIRST_SALESPERSON_CODE = "LL1";
    private static final String SECOND_SALESPERSON_CODE = "LL2";
    private static final String CHARGE_CODE_PREFIX = "ILLIF";

    // ---- The business presentation the ACTIVE public view reads live ----
    private static final String ABN = "51 824 753 556";
    private static final String BANK_NAME = "Lifecycle Bank";
    private static final String BSB = "062-000";
    private static final String ACCOUNT_NAME = "Lifecycle Link Flooring Pty Ltd";
    private static final String ACCOUNT_NUMBER = "12345678";
    private static final String STRIPE_LINK = "https://buy.stripe.com/test_lifecycle_link";
    private static final String ACCENT_COLOUR = "#0F766E";
    private static final String SOFT_TERMS = "<p>Lifecycle soft-floor terms apply.</p>";

    // ---- The seeded customer ----
    private static final String CUSTOMER_FIRST_NAME = "Morgan";
    private static final String CUSTOMER_LAST_NAME = "Lifecycle";
    /** The D.1 freeze of the saved name: every version's customer_name_snapshot, i.e. the accepted name. */
    private static final String FROZEN_CUSTOMER_NAME = "Morgan Lifecycle";
    private static final String CUSTOMER_EMAIL = "morgan.lifecycle@example.com";
    /** Saved with edge whitespace (DB-valid, app-valid once trimmed): every recipient must be the trimmed value. */
    private static final String SAVED_CUSTOMER_EMAIL = "  " + CUSTOMER_EMAIL + "  ";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and lay carpet (invoice link lifecycle)";

    /** {@code app.public-base-url} of the shared test context (application.properties). */
    private static final String PUBLIC_BASE_URL = "http://localhost:5173";
    private static final String PUBLIC_INVOICES = "/api/v1/public/invoices/";
    private static final String PUBLIC_QUOTES = "/api/v1/public/quotes/";

    // ---- Quote drafts: the accepted v1 (itemised 250.00 ex / 275.00 inc) and a CHANGED v2 (300.00 / 330.00) ----
    private static final String ITEMISED_DRAFT = """
            {"itemised": true, "lines": [
              {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
              {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
            ]}""";
    private static final String CHANGED_NON_ITEMISED_DRAFT =
            "{\"itemised\": false, \"final_total_inc_gst\": 330.00, \"lines\": []}";

    // ---- Messages (exact) ----
    private static final String LINK_SENT_MESSAGE = "Invoice link emailed to the customer.";
    private static final String INVOICE_LOADED_MESSAGE = "Invoice loaded.";
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String INVOICE_REWRITTEN_MESSAGE = "Invoice rewritten.";
    private static final String D8_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String RESEND_MESSAGE = "Invoice re-sent to the customer.";
    private static final String PATH_A_EMAILED_MESSAGE =
            "Invoice created from accepted quote and emailed to the customer.";
    private static final String PATH_A_NOT_NEWER_MESSAGE = "The current invoice was signed at the same time or later "
            + "than this quote. A newer signed quote is required to create an invoice from a quote.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String PAYMENT_VOIDED_MESSAGE = "Payment voided. Current invoice updated.";
    private static final String STATUS_UPDATED_MESSAGE = "Order status updated.";
    private static final String QUOTE_SENT_MESSAGE = "Quote sent by email.";
    private static final String QUOTE_CANCELLED_MESSAGE = "Quote cancelled.";
    private static final String QUOTE_ACCEPTED_MESSAGE = "Quote accepted.";
    private static final String ORDER_LOCKED_MESSAGE = "Order is laid and cannot be edited.";
    private static final String INVOICE_VERSION_STALE_MESSAGE = "Invoice updated, please refresh";
    /** The exact 201 body of a successful remote accept (16G contract section 9.4). */
    private static final String REMOTE_ACCEPTED_BODY = "{\"data\":{\"state\":\"SIGNED\"},\"message\":\"Invoice accepted.\"}";

    // ---- The four invoice dead reasons (16G contract section 7.2): public state, 410 code, message ----
    private static final DeadReason REPLACED = new DeadReason("REPLACED", "INVOICE_LINK_REPLACED",
            "This invoice link has been replaced. Please use the latest invoice link.");
    private static final DeadReason REWRITTEN = new DeadReason("REWRITTEN", "INVOICE_LINK_REWRITTEN",
            "This invoice has been revised. Please contact the store for the updated invoice.");
    private static final DeadReason SIGNED = new DeadReason("SIGNED", "INVOICE_LINK_SIGNED",
            "This invoice has already been signed. Please contact the store if you need a copy.");
    private static final DeadReason EXPIRED = new DeadReason("EXPIRED", "INVOICE_LINK_EXPIRED",
            "This invoice link has expired. Please contact the store.");

    // ---- Response shapes ----
    /** PublicInvoiceView: exactly these 27 keys, always present (16G contract section 9.3). */
    private static final Set<String> PUBLIC_VIEW_KEYS = Set.of(
            "state", "business_name", "business_logo_url", "accent_color", "business_abn", "payment_account_name",
            "payment_bank_name", "payment_bsb", "payment_account_number", "payment_stripe_link_url", "order_number",
            "flooring_type", "version_number", "invoice_date", "due_date", "customer_name", "customer_address_line1",
            "customer_address_line2", "details_of_sale", "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst",
            "total_paid", "balance_due", "terms_html", "expires_at", "message");

    /** InvoiceDetail (D.1 / D.2 / D.3 / D.8 / D.9 / Path A): the 23 earlier keys + the derived link field (PR 4). */
    private static final Set<String> INVOICE_DETAIL_KEYS = Set.of(
            "invoice_id", "order_id", "version_number", "invoice_date", "due_date", "details_of_sale_snapshot",
            "sale_price_ex_gst", "sale_price_inc_gst", "total_paid", "balance_due", "created_by_user_id",
            "created_at", "pdf_download_path", "accepted_at", "accepted_customer_name",
            "accepted_signature_present", "accepted_signature_download_path", "last_emailed_at",
            "terms_html", "terms_source",
            "customer_name_snapshot", "customer_address_line1_snapshot", "customer_address_line2_snapshot",
            "invoice_link_expires_at");

    /** E.1 current_invoice_summary (payment / void responses): unchanged by PR 4 - never the link field. */
    private static final Set<String> CURRENT_INVOICE_SUMMARY_KEYS = Set.of(
            "invoice_id", "version_number", "invoice_date", "due_date", "sale_price_inc_gst", "total_paid",
            "balance_due", "created_by_user_id", "created_at", "pdf_download_path", "accepted_at",
            "accepted_customer_name", "accepted_signature_present", "accepted_signature_download_path",
            "last_emailed_at");

    private static final String LINK_FIELD = "invoice_link_expires_at";

    // The bearer link in the link-only email: <app.public-base-url>/i/{43-char token}.
    private static final Pattern INVOICE_LINK_PATTERN = Pattern.compile("/i/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    // The public link in the link-only quote email: <app.public-base-url>/q/{43-char token}.
    private static final Pattern QUOTE_LINK_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final Pattern WHOLE_SECOND_TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

    // A real, decodable 1x1 PNG: the public accept validates and re-encodes it; D.8 stores it as uploaded.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // Exact money parsing for response bodies (never a double round-trip).
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final DateTimeFormatter JSON_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    // Children of sales_order deleted after invoice_token / quote_token / invoice / quote_version / quote_draft /
    // payment_transaction (every child FK is RESTRICT; the two *_line quote tables CASCADE from their parent).
    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "order_attachment", "order_note", "order_product_line", "order_charge_line",
            "order_address", "order_customer", "order_enquiry");

    // Every stored_file an order owns: invoice PDFs + signatures, quote issued / signed PDFs + signatures,
    // attachments - plus anything stored under the order's storage directory.
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

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FileStorageService fileStorageService;

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

    @Autowired
    private RecordingSmsSender smsSender;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    // The rollback probes of section 8 (names unique to this class; dropped in setUp / tearDown).
    private static final String ROLLBACK_PROBE_FUNCTION = "illif_rollback_probe";
    private static final String ROLLBACK_PROBE_SEQUENCE = "illif_rollback_probe_seq";
    private static final String ROLLBACK_TRIGGER_PREFIX = "illif_rollback_";
    private static final long PROBE_NOT_FIRED = 100L;
    private static final long PROBE_ACTIVE = 1L;
    private static final long PROBE_REWRITTEN = 2L;
    private static final long PROBE_SIGNED = 3L;

    private MockMvc mockMvc;

    private TransactionTemplate transactionTemplate;

    /** This test's committed tenant (seeded by {@link #setUp} after the sweep). */
    private Tenant tenant;

    private int orderSequence;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes files recursively - never let it run against a real storage dir.
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class writes and sweeps files only under the shared test storage dir");
        Assertions.assertTrue(storageRoot().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                () -> "unexpected storage root for a sweeping test class: " + storageRoot());
        // The REAL Spring Security filter chain (FilterChainProxy) in front of every request.
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        transactionTemplate = new TransactionTemplate(transactionManager);
        resetRecorders();
        dropRollbackProbes();
        sweepTenant();
        tenant = seedTenant();
    }

    @AfterEach
    void tearDown() {
        try {
            dropRollbackProbes();
            sweepTenant();
            Assertions.assertEquals(0, count("SELECT COUNT(*) FROM business WHERE slug = ?", SLUG),
                    "no tenant of this class may survive the sweep");
        } finally {
            // Singletons: never leak recorded sends or an armed failure to another class.
            resetRecorders();
        }
    }

    // ================================================================
    // 1. Every transition, with its exact token row (16G contract section 7.5)
    // ================================================================

    /**
     * The first send mints exactly ONE {@code ACTIVE} token for the order: its hash is the SHA-256 of the emailed
     * plaintext, {@code sent_by_user_id} is the session user, {@code sent_at} falls inside the request,
     * {@code expires_at} = {@code sent_at} + 7 days, {@code emailed_at} is stamped after the delivery and
     * {@code dead_at} is null; the 201 carries exactly {@code sent_at} / {@code expires_at} (whole seconds). The link
     * email is link-only, to the TRIMMED saved email, with the link exactly once. Nothing else is written: no invoice
     * version, file, marker ({@code invoice.last_emailed_at}, the {@code sales_order} mirror), signed-PDF email or
     * notification. The link opens the current version, and D.3 reports its expiry; neither read writes.
     */
    @Test
    void sendLink_firstSend_mintsExactlyOneActiveTokenWithItsExactRow_201SentAndExpiry_linkOnlyEmail_writesNothingElse()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        OrderState before = orderState(order.orderId());

        SentLink link = sendLink(order, tenant.first());

        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()), "exactly the one minted token row");
        assertSameState(before, orderState(order.orderId()), "a link send writes only its token row");
        assertNoSignedInvoiceEmailOrNotification("the first link send");
        assertNoQuoteMessages("the first link send");

        assertActiveView(publicView(link.token()), order, invoiceRow(order.orderId(), 1), link.row(),
                "public GET of the new link");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, link.expiresAtText(), "D.3 with the ACTIVE link");
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()), "the public and staff reads write nothing");
        assertSameState(before, orderState(order.orderId()), "after the reads");
    }

    /**
     * A second send replaces the first link in the same transaction: the first row becomes {@code REPLACED} with
     * {@code dead_at} = the NEW token's {@code sent_at} (exact) and every other column unchanged (its
     * {@code emailed_at} kept); the new row is {@code ACTIVE}, sent by the second salesperson. The old link is dead on
     * every public endpoint ({@code REPLACED} payload, 410 {@code INVOICE_LINK_REPLACED}); the new link shows the
     * same current version and D.3 reports the new expiry.
     */
    @Test
    void sendLink_again_replacesTheActiveToken_deadAtEqualsTheNewSentAt_restUnchanged_newTokenActiveBySecondSender_oldLinkDeadEverywhere()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink first = sendLink(order, tenant.first());
        OrderState before = orderState(order.orderId());

        SentLink second = sendLink(order, tenant.second());

        Assertions.assertEquals(tenant.second().userId(), longValue(second.row().get("sent_by_user_id")),
                "the new link records the salesperson who sent it");
        Map<String, Object> replaced = tokenRow(first.token());
        Assertions.assertEquals(withColumns(first.row(), "status", "REPLACED", "dead_at", second.row().get("sent_at")),
                replaced, "the first link is REPLACED with dead_at = the new sent_at; nothing else changes");
        Assertions.assertEquals(List.of(replaced, second.row()), tokenRows(order.orderId()),
                "exactly the REPLACED first row and the ACTIVE second row");
        assertSameState(before, orderState(order.orderId()), "a resend writes only token rows");

        assertDeadView(publicView(first.token()), REPLACED, "public GET of the replaced link");
        assertGone(publicAccept(first.token(), 1), REPLACED, "public accept of the replaced link");
        assertGone(publicPdf(first.token(), "1"), REPLACED, "public pdf of the replaced link");
        assertActiveView(publicView(second.token()), order, invoiceRow(order.orderId(), 1), second.row(),
                "public GET of the new link");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, second.expiresAtText(), "D.3 after the resend");
        Assertions.assertEquals(List.of(replaced, second.row()), tokenRows(order.orderId()),
                "the public requests changed no token row");
        assertSameState(before, orderState(order.orderId()), "the refused public requests wrote nothing");
    }

    /**
     * A manual Rewrite (D.2) kills the {@code ACTIVE} link as {@code REWRITTEN} in its own transaction: {@code dead_at}
     * falls inside the Rewrite request, every other column is unchanged. The D.2 InvoiceDetail carries
     * {@code invoice_link_expires_at} null (the key present). The link is dead on every public endpoint, and D.3 of the
     * new unsigned version reports null because the only link is dead.
     */
    @Test
    void rewrite_killsTheActiveTokenAsRewritten_deadAtWithinTheRewrite_d2ResponseLinkFieldNull_linkDeadEverywhere()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());

        Timed rewrite = rewrite(order.orderId());

        assertStatus(rewrite.result(), 201, "D.2 Rewrite");
        JsonNode body = json(rewrite.result());
        Assertions.assertEquals(INVOICE_REWRITTEN_MESSAGE, body.path("message").asText(), "D.2 message");
        assertInvoiceDetail(body.path("data").path("invoice"), 2, null, "D.2 response (the link was killed)");
        assertKilledWithin(link.row(), tokenRow(link.token()), REWRITTEN, rewrite, "the Rewrite's kill");
        Assertions.assertEquals(List.of(2), versionsAfter(order.orderId(), 1), "the Rewrite appended exactly v2");

        assertDeadView(publicView(link.token()), REWRITTEN, "public GET of the rewritten link");
        assertGone(publicAccept(link.token(), 2), REWRITTEN, "public accept of the rewritten link");
        assertGone(publicPdf(link.token(), "2"), REWRITTEN, "public pdf of the rewritten link");
        Map<String, Object> dead = tokenRow(link.token());
        Assertions.assertEquals("REWRITTEN", dead.get("status"), "the public requests never relabel a dead link");
        Assertions.assertNull(invoiceRow(order.orderId(), 2).get("accepted_at"), "v2 is unsigned");
        assertInvoiceDetail(currentInvoice(order.orderId()), 2, null, "D.3 after REWRITTEN");
        Assertions.assertEquals(List.of(dead), tokenRows(order.orderId()), "D.3 wrote nothing");
    }

    /**
     * In-store D.8 kills the {@code ACTIVE} link as {@code SIGNED} with {@code dead_at} EXACTLY the signing version's
     * {@code accepted_at}, every other column unchanged. The D.8 response, D.3 and the D.9 Re-send response all carry
     * {@code invoice_link_expires_at} null (the version is signed). The link is dead on every public endpoint.
     */
    @Test
    void inStoreD8_killsTheActiveTokenAsSigned_deadAtEqualsTheSigningVersionAcceptedAt_d8D3AndD9ResponsesLinkFieldNull()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());

        MvcResult d8 = inStoreAccept(order.orderId(), 1);

        assertStatus(d8, 201, "D.8");
        JsonNode body = json(d8);
        Assertions.assertEquals(D8_EMAILED_MESSAGE, body.path("message").asText(), "D.8 message");
        JsonNode invoice = body.path("data").path("invoice");
        assertInvoiceDetail(invoice, 2, null, "D.8 response (the link was killed, the version is signed)");
        Map<String, Object> signedVersion = invoiceRow(order.orderId(), 2);
        Timestamp acceptedAt = (Timestamp) signedVersion.get("accepted_at");
        Assertions.assertNotNull(acceptedAt, "D.8 signed v2");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED", "dead_at", acceptedAt),
                tokenRow(link.token()), "D.8 kills the link as SIGNED with dead_at = v2's accepted_at, nothing else");

        assertDeadView(publicView(link.token()), SIGNED, "public GET after D.8");
        assertGone(publicAccept(link.token(), 2), SIGNED, "public accept after D.8");
        assertGone(publicPdf(link.token(), "2"), SIGNED, "public pdf after D.8");
        assertInvoiceDetail(currentInvoice(order.orderId()), 2, null, "D.3 after SIGNED (D.8)");

        MvcResult resend = resend(order.orderId());
        assertStatus(resend, 200, "D.9 Re-send");
        JsonNode resendBody = json(resend);
        Assertions.assertEquals(RESEND_MESSAGE, resendBody.path("message").asText(), "D.9 message");
        assertInvoiceDetail(resendBody.path("data").path("invoice"), 2, null, "D.9 response (signed)");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED", "dead_at", acceptedAt),
                tokenRow(link.token()), "D.3, D.9 and the public requests never touch the dead row");
    }

    /**
     * Path A on an UNSIGNED current invoice with an ACCEPTED quote signed earlier (D5(b) allows it) kills the
     * {@code ACTIVE} link as {@code SIGNED}: {@code dead_at} falls inside the conversion request - after the inherited
     * quote signature time - and every other column is unchanged. The link was sent BEFORE the quote was issued and
     * accepted: neither the quote send nor the public quote accept touched it. The Path A InvoiceDetail and D.3 carry
     * {@code invoice_link_expires_at} null.
     */
    @Test
    void pathA_withAnUnsignedCurrentInvoiceAndAnEarlierAcceptedQuote_killsTheActiveTokenAsSigned_deadAtWithinTheConversion_responseLinkFieldNull()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());

        saveDraft(order.orderId(), ITEMISED_DRAFT);
        String quoteToken = sendQuote(order.orderId(), 1);
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()), "a quote send never touches the link");
        acceptQuotePublicly(quoteToken);
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()),
                "a public quote accept never touches the invoice link");
        Map<String, Object> acceptedQuote = quoteVersionRow(order.orderId(), 1);
        Assertions.assertEquals("ACCEPTED", acceptedQuote.get("status"), "setup: quote v1 accepted publicly");

        Timed pathA = pathA(order.orderId(), longValue(acceptedQuote.get("quote_version_id")));

        assertStatus(pathA.result(), 201, "Path A");
        JsonNode body = json(pathA.result());
        Assertions.assertEquals(PATH_A_EMAILED_MESSAGE, body.path("message").asText(), "Path A message");
        JsonNode invoice = body.path("data").path("invoice");
        assertInvoiceDetail(invoice, 2, null, "Path A response (the link was killed, the version is signed)");
        Assertions.assertEquals(JSON_TIMESTAMP.format(timestamp(acceptedQuote, "accepted_at")),
                invoice.path("accepted_at").asText(), "Path A inherits the quote's signature time");
        Map<String, Object> killed = tokenRow(link.token());
        assertKilledWithin(link.row(), killed, SIGNED, pathA, "Path A's kill");
        Assertions.assertTrue(timestamp(killed, "dead_at").isAfter(timestamp(acceptedQuote, "accepted_at")),
                "dead_at is the conversion time, not the inherited (earlier) quote signature time");

        assertDeadView(publicView(link.token()), SIGNED, "public GET after Path A");
        assertGone(publicAccept(link.token(), 2), SIGNED, "public accept after Path A");
        assertInvoiceDetail(currentInvoice(order.orderId()), 2, null, "D.3 after SIGNED (Path A)");
        Assertions.assertEquals(List.of(killed), tokenRows(order.orderId()), "the reads wrote nothing");
    }

    /**
     * The remote accept signs the current version and kills the PRESENTED token as {@code SIGNED} with {@code dead_at}
     * EXACTLY the new version's {@code accepted_at} (inside the request), every other column unchanged. The 201 body
     * is exactly {@code {state: SIGNED}} + {@code Invoice accepted.}. The appended v2 copies v1's dates, words and
     * money, takes the frozen name as the accepted name, stores the server-normalised signature and its own PDF, and
     * is created by the salesperson who SENT the link (not the order's owner). With no quote at all, D5(c) is a no-op.
     * After commit the signed PDF went to the trimmed saved email and the store was notified.
     */
    @Test
    void remoteAccept_signsTheCurrentVersion_killsThePresentedTokenAsSigned_deadAtEqualsAcceptedAt_createdByTheSender()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.second());
        Map<String, Object> v1 = invoiceRow(order.orderId(), 1);
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM quote_version WHERE order_id = ?", order.orderId()),
                "setup: no quote at all");

        Timed accept = timedPublicAccept(link.token(), 1);

        assertRemoteAccepted(accept.result(), "remote accept");
        Assertions.assertEquals(List.of(2), versionsAfter(order.orderId(), 1), "the remote accept appended exactly v2");
        Map<String, Object> v2 = invoiceRow(order.orderId(), 2);
        Timestamp acceptedAt = (Timestamp) v2.get("accepted_at");
        Assertions.assertNotNull(acceptedAt, "v2 is signed");
        assertWithin(acceptedAt.toLocalDateTime(), accept.start(), accept.end(), "accepted_at is the signing time");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED", "dead_at", acceptedAt),
                tokenRow(link.token()), "the presented token is SIGNED with dead_at = accepted_at, nothing else");

        // The signing version: v1 carried forward, signed, created by the link's sender.
        for (String carried : List.of("invoice_date", "due_date", "details_of_sale_snapshot", "sale_price_ex_gst",
                "sale_price_inc_gst", "total_paid", "balance_due", "source_quote_version_id", "terms_snapshot",
                "customer_name_snapshot", "customer_address_line1_snapshot", "customer_address_line2_snapshot")) {
            Assertions.assertEquals(v1.get(carried), v2.get(carried), "v2 copies v1's " + carried);
        }
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, v2.get("accepted_customer_name"), "the frozen name, verbatim");
        Assertions.assertEquals(tenant.second().userId(), longValue(v2.get("created_by_user_id")),
                "created_by_user_id = the token's sent_by_user_id (the sender, not the order's owner)");
        Assertions.assertNotEquals(tenant.first().userId(), tenant.second().userId(), "setup: two distinct staff");
        Map<String, Object> signature = storedFileRow(longValue(v2.get("accepted_signature_file_id")));
        Assertions.assertEquals("signature-" + order.orderNumber() + "-v2.png", signature.get("file_name"));
        Assertions.assertEquals("image/png", signature.get("mime_type"));
        Assertions.assertArrayEquals(SignaturePngNormaliser.normalise(ONE_PIXEL_PNG),
                fileStorageService.read((String) signature.get("storage_path")),
                "only the server-normalised signature is stored");
        Map<String, Object> pdf = storedFileRow(longValue(v2.get("stored_file_id")));
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), 2), pdf.get("file_name"));
        byte[] storedPdf = fileStorageService.read((String) pdf.get("storage_path"));
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM quote_version WHERE order_id = ?", order.orderId()),
                "no ISSUED quote: D5(c) is a no-op and creates no quote row");

        // After commit: the signed PDF of v2 to the trimmed saved email (stamped), and the store notification.
        List<InvoiceEmailRequest> emails = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, emails.size(), "exactly one signed-PDF email");
        Assertions.assertEquals(CUSTOMER_EMAIL, emails.get(0).recipientEmail(), "the TRIMMED saved email");
        Assertions.assertEquals(2, emails.get(0).invoiceVersionNumber(), "the email carries v2");
        Assertions.assertArrayEquals(storedPdf, emails.get(0).pdfBytes(), "the attachment is v2's stored PDF");
        Assertions.assertNotNull(v2.get("last_emailed_at"), "the delivered email was stamped on v2");
        Assertions.assertEquals(v2.get("last_emailed_at"), jdbcTemplate.queryForObject(
                        "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, order.orderId()),
                "the mirror equals the current version's stamp");
        List<InvoiceAcceptanceNotificationRequest> notifications = invoiceNotificationSender.sentNotifications();
        Assertions.assertEquals(1, notifications.size(), "exactly one store notification");
        InvoiceAcceptanceNotificationRequest notification = notifications.get(0);
        Assertions.assertEquals(STORE_EMAIL, notification.recipientEmail(), "the order's store email");
        Assertions.assertEquals("Invoice accepted: " + order.orderNumber(), notification.subject());
        assertNotificationBody(notification.bodyText(), order, 2, acceptedAt.toLocalDateTime(), "110.00");
        Assertions.assertEquals(2, notification.invoiceVersionNumber(), "the notification names v2");

        assertDeadView(publicView(link.token()), SIGNED, "public GET after the remote accept");
        assertGone(publicAccept(link.token(), 2), SIGNED, "a second remote accept");
        assertGone(publicPdf(link.token(), "2"), SIGNED, "public pdf after the remote accept");
        JsonNode d3 = currentInvoice(order.orderId());
        assertInvoiceDetail(d3, 2, null, "D.3 after SIGNED (remote accept)");
        Assertions.assertEquals(tenant.second().userId(), d3.path("created_by_user_id").asLong(),
                "D.3 reports the sender as the creator of the signed version");
        Assertions.assertEquals(1, invoiceEmailSender.sentEmails().size(), "the refused requests emailed nothing");
        Assertions.assertEquals(1, invoiceNotificationSender.sentNotifications().size(),
                "the refused requests notified nothing");

        // A send over the signed current version is refused (409, default wording): no link, the SIGNED row untouched.
        List<Map<String, Object>> tokensAfterSigning = tokenRows(order.orderId());
        int linkEmailsBefore = invoiceLinkEmailSender.sentEmails().size();
        MvcResult refusedSend = perform(protectedJson(post(orderUrl(order.orderId()) + "/invoices/current/send-link"),
                "{}"));
        assertErrorEnvelope(refusedSend, 409, "INVOICE_ALREADY_ACCEPTED",
                "This invoice has already been accepted. Use Re-send to email it again.", "send-link on the signed v2");
        Assertions.assertEquals(tokensAfterSigning, tokenRows(order.orderId()),
                "the refused send creates no link and never touches the SIGNED row");
        Assertions.assertEquals(linkEmailsBefore, invoiceLinkEmailSender.sentEmails().size(), "no link email");
        Assertions.assertTrue(invoiceLinkEmailSender.failedEmails().isEmpty(), "no link email attempted");
    }

    /**
     * Lazy expiry (16G contract section 7.7): an {@code ACTIVE} token whose {@code expires_at} was backdated is flipped
     * to {@code EXPIRED} by the next public GET (200 with the {@code EXPIRED} payload), {@code dead_at} inside that GET,
     * every other column unchanged; no invoice, order, file, payment or quote row changes. The dead link then answers
     * 410 {@code INVOICE_LINK_EXPIRED} on accept and pdf, a second GET stays {@code EXPIRED} with the row unchanged,
     * and D.3 reports null.
     */
    @Test
    void lazyExpiry_publicGetOfAnOverAgeActiveToken_flipsItToExpired_deadAtWithinTheGet_noInvoiceOrOrderRowTouched()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        Map<String, Object> overAge = backdateExpiry(link.token());
        OrderState before = orderState(order.orderId());

        Timed view = timedPublicView(link.token());

        assertDeadView(view.result(), EXPIRED, "public GET of the over-age link");
        Map<String, Object> expired = tokenRow(link.token());
        assertKilledWithin(overAge, expired, EXPIRED, view, "the lazy expiry");
        assertSameState(before, orderState(order.orderId()), "an expiry flip never alters an invoice or order row");

        assertGone(publicAccept(link.token(), 1), EXPIRED, "public accept of the expired link");
        assertGone(publicPdf(link.token(), "1"), EXPIRED, "public pdf of the expired link");
        assertDeadView(publicView(link.token()), EXPIRED, "a second public GET");
        Assertions.assertEquals(List.of(expired), tokenRows(order.orderId()), "a dead row never changes again");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, null, "D.3 after EXPIRED");
        assertSameState(before, orderState(order.orderId()), "nothing else was written");
        assertNoSignedInvoiceEmailOrNotification("the expired link's requests");
    }

    /**
     * Lazy expiry runs on ANY public request (16G contract section 7.7), not only the GET: when the accept - or, on a
     * second order, the pdf - is the FIRST public request on an over-age ACTIVE token, the gate flips it to
     * {@code EXPIRED} and that flip is COMMITTED (dead_at inside that request) before the 410
     * {@code INVOICE_LINK_EXPIRED}; nothing else is written (no signature, PDF, invoice version, email or
     * notification) and the GET then reports {@code EXPIRED}.
     */
    @Test
    void lazyExpiry_acceptOrPdfAsTheFirstPublicRequestOnAnOverAgeToken_commitsTheExpiredFlipThen410_writesNothingElse()
            throws Exception {
        Order acceptOrder = seedOrderWithInvoiceV1();
        SentLink acceptLink = sendLink(acceptOrder, tenant.first());
        Map<String, Object> acceptOverAge = backdateExpiry(acceptLink.token());
        OrderState acceptBefore = orderState(acceptOrder.orderId());

        Timed accept = timedPublicAccept(acceptLink.token(), 1);

        assertGone(accept.result(), EXPIRED, "the remote accept as the first request on the over-age link");
        assertKilledWithin(acceptOverAge, tokenRow(acceptLink.token()), EXPIRED, accept,
                "the accept's committed expiry flip");
        assertSameState(acceptBefore, orderState(acceptOrder.orderId()), "the expired accept wrote nothing else");
        assertNoSignedInvoiceEmailOrNotification("the expired accept");
        assertDeadView(publicView(acceptLink.token()), EXPIRED, "the GET after the accept's flip");

        Order pdfOrder = seedOrderWithInvoiceV1();
        SentLink pdfLink = sendLink(pdfOrder, tenant.first());
        Map<String, Object> pdfOverAge = backdateExpiry(pdfLink.token());
        OrderState pdfBefore = orderState(pdfOrder.orderId());

        LocalDateTime pdfStart = LocalDateTime.now();
        MvcResult pdf = publicPdf(pdfLink.token(), "1");
        Timed timedPdf = new Timed(pdf, pdfStart, LocalDateTime.now());

        assertGone(pdf, EXPIRED, "the pdf as the first request on the over-age link");
        assertKilledWithin(pdfOverAge, tokenRow(pdfLink.token()), EXPIRED, timedPdf, "the pdf's committed expiry flip");
        assertSameState(pdfBefore, orderState(pdfOrder.orderId()), "the expired pdf wrote nothing else");
        assertDeadView(publicView(pdfLink.token()), EXPIRED, "the GET after the pdf's flip");
        assertInvoiceDetail(currentInvoice(pdfOrder.orderId()), 1, null, "D.3 after the pdf's flip");
    }

    // ================================================================
    // 2. Payment and void before signing: the link survives and follows the current version
    // ================================================================

    /**
     * A payment (D.7) before signing appends v2 and leaves the {@code ACTIVE} token exactly as it was (whole row).
     * The public view now shows v2 (its version number, {@code total_paid} 50.00, {@code balance_due} 60.00); the pdf
     * of v1 is a 409 {@code INVOICE_VERSION_STALE} while v2's stored PDF streams; D.3 still reports the link; the E.1
     * {@code current_invoice} of the payment response never carries the link field. A remote accept carrying v1 is
     * refused as stale with nothing written; carrying v2 it signs v2 (copying its paid amounts) and kills the link.
     */
    @Test
    void payment_beforeSigning_leavesTheActiveTokenUntouched_publicViewShowsThePaymentVersion_oldVersionPdfStale_remoteAcceptSignsTheNewVersion()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        assertActiveViewMoney(publicView(link.token()), 1, "0.00", "110.00", "the link before the payment");

        JsonNode payment = recordPayment(order.orderId(), "50.00");

        assertCurrentInvoiceSummary(payment.path("data").path("current_invoice"), 2, "payment response E.1");
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()),
                "the payment left the ACTIVE token exactly as it was (status, dead_at, emailed_at, expiry)");
        Map<String, Object> v2 = invoiceRow(order.orderId(), 2);
        assertMoney("50.00", v2.get("total_paid"), "payment v2 total_paid");
        assertMoney("60.00", v2.get("balance_due"), "payment v2 balance_due");
        assertActiveView(publicView(link.token()), order, v2, link.row(), "the link after the payment");
        assertActiveViewMoney(publicView(link.token()), 2, "50.00", "60.00", "the link after the payment");
        assertStale(publicPdf(link.token(), "1"), "public pdf of v1 after the payment");
        assertPdfStreams(publicPdf(link.token(), "2"), order, 2, "public pdf of v2");
        assertInvoiceDetail(currentInvoice(order.orderId()), 2, link.expiresAtText(), "D.3 after the payment");

        OrderState beforeStale = orderState(order.orderId());
        assertStale(publicAccept(link.token(), 1), "a remote accept carrying v1 after the payment");
        assertSameState(beforeStale, orderState(order.orderId()), "the stale remote accept wrote nothing");
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()), "the stale accept left the link ACTIVE");
        assertNoSignedInvoiceEmailOrNotification("the stale remote accept");

        assertRemoteAccepted(publicAccept(link.token(), 2), "a remote accept carrying v2");
        Map<String, Object> v3 = invoiceRow(order.orderId(), 3);
        Assertions.assertNotNull(v3.get("accepted_at"), "the remote accept signed v3 from v2");
        assertMoney("50.00", v3.get("total_paid"), "the signing version copies v2's total_paid");
        assertMoney("60.00", v3.get("balance_due"), "the signing version copies v2's balance_due");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED", "dead_at", v3.get("accepted_at")),
                tokenRow(link.token()), "the remote accept killed the surviving link as SIGNED");
    }

    /**
     * A payment then its void (D.10) before signing: each appends a version and neither touches the {@code ACTIVE}
     * token (whole row). The public view follows to the void's v3 ({@code total_paid} 0.00, {@code balance_due}
     * 110.00); the pdf of v1 and v2 are stale while v3 streams; neither E.1 {@code current_invoice} carries the link
     * field; the remote accept carrying v3 signs it and kills the link.
     */
    @Test
    void paymentThenVoid_beforeSigning_leaveTheActiveTokenUntouched_publicViewShowsTheVoidVersion_olderPdfsStale_remoteAcceptSignsIt()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());

        JsonNode payment = recordPayment(order.orderId(), "50.00");
        assertCurrentInvoiceSummary(payment.path("data").path("current_invoice"), 2, "payment response E.1");
        long paymentId = payment.path("data").path("payment_transaction").path("payment_transaction_id").asLong();
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()), "the payment left the token untouched");

        JsonNode voided = voidPayment(order.orderId(), paymentId);

        assertCurrentInvoiceSummary(voided.path("data").path("current_invoice"), 3, "void response E.1");
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()),
                "the void left the ACTIVE token exactly as it was (status, dead_at, emailed_at, expiry)");
        Map<String, Object> v3 = invoiceRow(order.orderId(), 3);
        assertMoney("0.00", v3.get("total_paid"), "void v3 total_paid");
        assertMoney("110.00", v3.get("balance_due"), "void v3 balance_due");
        assertActiveView(publicView(link.token()), order, v3, link.row(), "the link after the void");
        assertActiveViewMoney(publicView(link.token()), 3, "0.00", "110.00", "the link after the void");
        assertStale(publicPdf(link.token(), "2"), "public pdf of the payment's v2 after the void");
        assertStale(publicPdf(link.token(), "1"), "public pdf of v1 after the void");
        assertPdfStreams(publicPdf(link.token(), "3"), order, 3, "public pdf of the void's v3");
        assertInvoiceDetail(currentInvoice(order.orderId()), 3, link.expiresAtText(), "D.3 after the void");

        assertRemoteAccepted(publicAccept(link.token(), 3), "a remote accept carrying v3");
        Map<String, Object> v4 = invoiceRow(order.orderId(), 4);
        Assertions.assertNotNull(v4.get("accepted_at"), "the remote accept signed v4 from v3");
        assertMoney("0.00", v4.get("total_paid"), "the signing version copies v3's total_paid");
        assertMoney("110.00", v4.get("balance_due"), "the signing version copies v3's balance_due");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED", "dead_at", v4.get("accepted_at")),
                tokenRow(link.token()), "the remote accept killed the surviving link as SIGNED");
    }

    // ================================================================
    // 3. A writer that kills an over-age ACTIVE token records ITS reason, never EXPIRED
    // ================================================================

    /** An over-age ACTIVE link no public request flipped: a Rewrite records {@code REWRITTEN}, not {@code EXPIRED}. */
    @Test
    void rewrite_ofAnOverAgeActiveTokenThatNoPublicRequestFlipped_recordsRewrittenNeverExpired()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        Map<String, Object> overAge = backdateExpiry(link.token());

        Timed rewrite = rewrite(order.orderId());

        assertStatus(rewrite.result(), 201, "D.2 Rewrite");
        assertKilledWithin(overAge, tokenRow(link.token()), REWRITTEN, rewrite, "the Rewrite of an over-age link");
        assertWriterReasonSticks(link.token(), REWRITTEN, 2);
    }

    /** An over-age ACTIVE link: D.8 records {@code SIGNED} with {@code dead_at} = {@code accepted_at}, not EXPIRED. */
    @Test
    void inStoreD8_ofAnOverAgeActiveTokenThatNoPublicRequestFlipped_recordsSignedNeverExpired_deadAtEqualsAcceptedAt()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        Map<String, Object> overAge = backdateExpiry(link.token());

        MvcResult d8 = inStoreAccept(order.orderId(), 1);

        assertStatus(d8, 201, "D.8");
        Assertions.assertEquals(withColumns(overAge, "status", "SIGNED",
                        "dead_at", invoiceRow(order.orderId(), 2).get("accepted_at")), tokenRow(link.token()),
                "D.8 records SIGNED (dead_at = accepted_at) on the over-age link, never EXPIRED");
        assertWriterReasonSticks(link.token(), SIGNED, 2);
    }

    /**
     * An over-age ACTIVE link: a NEW send records {@code REPLACED} with {@code dead_at} = the new {@code sent_at}, not
     * EXPIRED; the new link is ACTIVE with a fresh expiry.
     */
    @Test
    void newSend_overAnOverAgeActiveTokenThatNoPublicRequestFlipped_recordsReplacedNeverExpired_deadAtEqualsTheNewSentAt()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink first = sendLink(order, tenant.first());
        Map<String, Object> overAge = backdateExpiry(first.token());

        SentLink second = sendLink(order, tenant.first());

        Assertions.assertEquals(withColumns(overAge, "status", "REPLACED", "dead_at", second.row().get("sent_at")),
                tokenRow(first.token()), "the new send records REPLACED on the over-age link, never EXPIRED");
        assertWriterReasonSticks(first.token(), REPLACED, 1);
        assertActiveView(publicView(second.token()), order, invoiceRow(order.orderId(), 1), second.row(),
                "the new link");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, second.expiresAtText(), "D.3 reports the new link");
    }

    /**
     * An over-age ACTIVE link: Path A (unsigned current invoice, accepted quote) records {@code SIGNED}, not EXPIRED,
     * with {@code dead_at} inside the conversion.
     */
    @Test
    void pathA_ofAnOverAgeActiveTokenThatNoPublicRequestFlipped_recordsSignedNeverExpired()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        long acceptedQuoteVersionId = acceptQuoteV1Publicly(order);
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()), "the quote flow never touched the link");
        Map<String, Object> overAge = backdateExpiry(link.token());

        Timed pathA = pathA(order.orderId(), acceptedQuoteVersionId);

        assertStatus(pathA.result(), 201, "Path A");
        assertKilledWithin(overAge, tokenRow(link.token()), SIGNED, pathA, "Path A of an over-age link");
        assertWriterReasonSticks(link.token(), SIGNED, 2);
    }

    // ================================================================
    // 4. Dead reasons are immutable; status changes never touch a link
    // ================================================================

    /**
     * Dead rows never change again. Link A is REPLACED by link B; a Rewrite then kills B as REWRITTEN (A untouched);
     * a third link C leaves the REWRITTEN B untouched; D.8 kills C as SIGNED (A and B untouched). With every
     * {@code expires_at} then backdated, each public GET reports the TRUE reason (REPLACED / REWRITTEN / SIGNED), never
     * EXPIRED, accept and pdf answer that reason's 410, and no row changes. D.3 reports null.
     */
    @Test
    void deadReasons_areImmutable_replacedSurvivesRewriteAndD8_rewrittenSurvivesALaterSend_pastExpiryDeadRowsKeepTheirTrueReason()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink a = sendLink(order, tenant.first());
        SentLink b = sendLink(order, tenant.first());
        Map<String, Object> aReplaced = tokenRow(a.token());
        Assertions.assertEquals(withColumns(a.row(), "status", "REPLACED", "dead_at", b.row().get("sent_at")), aReplaced,
                "setup: A is REPLACED by B");

        Timed rewrite = rewrite(order.orderId());
        assertStatus(rewrite.result(), 201, "D.2 Rewrite");
        Assertions.assertEquals(aReplaced, tokenRow(a.token()), "the Rewrite never touches the REPLACED A");
        Map<String, Object> bRewritten = tokenRow(b.token());
        assertKilledWithin(b.row(), bRewritten, REWRITTEN, rewrite, "the Rewrite kills B");

        SentLink c = sendLink(order, tenant.first());
        Assertions.assertEquals(aReplaced, tokenRow(a.token()), "a later send never touches the REPLACED A");
        Assertions.assertEquals(bRewritten, tokenRow(b.token()), "a later send never touches the REWRITTEN B");

        MvcResult d8 = inStoreAccept(order.orderId(), 2);
        assertStatus(d8, 201, "D.8 of v2");
        Map<String, Object> cSigned = tokenRow(c.token());
        Assertions.assertEquals(withColumns(c.row(), "status", "SIGNED",
                        "dead_at", invoiceRow(order.orderId(), 3).get("accepted_at")), cSigned, "D.8 kills C");
        Assertions.assertEquals(aReplaced, tokenRow(a.token()), "D.8 never touches the REPLACED A");
        Assertions.assertEquals(bRewritten, tokenRow(b.token()), "D.8 never touches the REWRITTEN B");

        // Every dead row past its expiry: reported with its true reason, never EXPIRED, and never relabelled.
        Map<String, Object> aPast = backdateExpiry(a.token());
        Map<String, Object> bPast = backdateExpiry(b.token());
        Map<String, Object> cPast = backdateExpiry(c.token());
        Assertions.assertEquals(withColumns(aReplaced, "expires_at", aPast.get("expires_at")), aPast, "setup: only the expiry moved");
        assertDeadView(publicView(a.token()), REPLACED, "public GET of the past-expiry REPLACED A");
        assertDeadView(publicView(b.token()), REWRITTEN, "public GET of the past-expiry REWRITTEN B");
        assertDeadView(publicView(c.token()), SIGNED, "public GET of the past-expiry SIGNED C");
        assertGone(publicAccept(a.token(), 3), REPLACED, "public accept of the past-expiry A");
        assertGone(publicPdf(b.token(), "3"), REWRITTEN, "public pdf of the past-expiry B");
        assertGone(publicAccept(c.token(), 3), SIGNED, "public accept of the past-expiry C");
        assertGone(publicPdf(a.token(), "3"), REPLACED, "public pdf of the past-expiry A");
        Assertions.assertEquals(List.of(aPast, bPast, cPast), tokenRows(order.orderId()),
                "no public request relabels a dead row as EXPIRED or moves its dead_at");
        assertInvoiceDetail(currentInvoice(order.orderId()), 3, null, "D.3 with only dead links");
    }

    /**
     * Ordinary order status changes through the real status endpoint (LEAD → FOLLOW_UP → NEW_ACHIEVED_SALE →
     * ACCEPTED → LAID → CANCELLED → LEAD) never touch an invoice token: the REPLACED and the ACTIVE row stay exactly as
     * they were after every change, the ACTIVE link still opens and D.3 still reports it.
     */
    @Test
    void orderStatusChanges_throughTheStatusEndpoint_neverTouchTheActiveOrADeadInvoiceToken()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink replaced = sendLink(order, tenant.first());
        SentLink active = sendLink(order, tenant.first());
        List<Map<String, Object>> tokens = tokenRows(order.orderId());
        Assertions.assertEquals("REPLACED", tokens.get(0).get("status"), "setup: the first link is REPLACED");
        Assertions.assertEquals("ACTIVE", tokens.get(1).get("status"), "setup: the second link is ACTIVE");

        for (String status : List.of("FOLLOW_UP", "NEW_ACHIEVED_SALE", "ACCEPTED", "LAID", "CANCELLED", "LEAD")) {
            changeStatus(order.orderId(), status);
            Assertions.assertEquals(tokens, tokenRows(order.orderId()),
                    "the status change to " + status + " never touches an invoice token");
        }

        assertActiveView(publicView(active.token()), order, invoiceRow(order.orderId(), 1), active.row(),
                "the ACTIVE link after the status changes");
        assertDeadView(publicView(replaced.token()), REPLACED, "the REPLACED link after the status changes");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, active.expiresAtText(), "D.3 after the status changes");
        Assertions.assertEquals(tokens, tokenRows(order.orderId()), "the reads wrote nothing");
    }

    // ================================================================
    // 5. D5(c) on remote signing; D5(b) afterwards; quote flows never touch an invoice token
    // ================================================================

    /**
     * D5(c) extended (16G contract section 10.5): an ISSUED quote version with an ACTIVE quote link (real send-email,
     * AFTER the invoice link was sent - which it did not touch) is cancelled by the remote invoice accept, in its
     * transaction: the version {@code CANCELLED} (nothing else), its token {@code CANCELLED} with {@code dead_at} =
     * the invoice's {@code accepted_at} (nothing else). The quote link is then dead on the public quote surface.
     */
    @Test
    void remoteAccept_withAnIssuedQuoteAndItsActiveLink_cancelsBoth_quoteTokenDeadAtEqualsTheInvoiceAcceptedAt()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        saveDraft(order.orderId(), ITEMISED_DRAFT);
        String quoteToken = sendQuote(order.orderId(), 1);
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()),
                "the quote send never touches the invoice link");
        Map<String, Object> issued = quoteVersionRow(order.orderId(), 1);
        Assertions.assertEquals("ISSUED", issued.get("status"), "setup: quote v1 ISSUED");
        Map<String, Object> quoteTokenRow = quoteTokenRow(quoteToken);
        Assertions.assertEquals("ACTIVE", quoteTokenRow.get("status"), "setup: the quote link is ACTIVE");
        Assertions.assertEquals("ACTIVE", publicQuoteState(quoteToken), "setup: the quote link opens");

        assertRemoteAccepted(publicAccept(link.token(), 1), "remote invoice accept");

        Timestamp acceptedAt = (Timestamp) invoiceRow(order.orderId(), 2).get("accepted_at");
        Assertions.assertNotNull(acceptedAt, "the remote accept signed v2");
        Assertions.assertEquals(withColumns(issued, "status", "CANCELLED"), quoteVersionRow(order.orderId(), 1),
                "D5(c): the ISSUED quote version is CANCELLED, nothing else changes");
        Assertions.assertEquals(withColumns(quoteTokenRow, "status", "CANCELLED", "dead_at", acceptedAt),
                quoteTokenRow(quoteToken), "D5(c): the quote link is CANCELLED with dead_at = the invoice accepted_at");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED", "dead_at", acceptedAt),
                tokenRow(link.token()), "the invoice link is SIGNED at the same instant");
        Assertions.assertEquals("CANCELLED", publicQuoteState(quoteToken), "the quote link is dead on the quote surface");
    }

    /**
     * D5(c) and D5(d) together: with an ACCEPTED quote v1 (real public quote accept) and a newer ISSUED v2 with an
     * ACTIVE link (real send-email after the acceptance), the remote invoice accept cancels ONLY v2 and its link
     * (link {@code dead_at} = the invoice {@code accepted_at}); the ACCEPTED v1 and its consumed link are untouched.
     */
    @Test
    void remoteAccept_withAnAcceptedV1AndANewerIssuedV2_cancelsOnlyTheIssuedV2AndItsLink_theAcceptedV1AndItsConsumedLinkUntouched()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        long acceptedV1Id = acceptQuoteV1Publicly(order);
        saveDraft(order.orderId(), CHANGED_NON_ITEMISED_DRAFT);
        String issuedV2Token = sendQuote(order.orderId(), 2);
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()),
                "the quote send, accept and resend never touched the invoice link");
        Map<String, Object> acceptedV1 = quoteVersionRow(order.orderId(), 1);
        Assertions.assertEquals(acceptedV1Id, longValue(acceptedV1.get("quote_version_id")), "setup: v1 is the accepted one");
        List<Map<String, Object>> acceptedV1Tokens = rows(
                "SELECT * FROM quote_token WHERE quote_version_id = ? ORDER BY quote_token_id", acceptedV1Id);
        Map<String, Object> issuedV2 = quoteVersionRow(order.orderId(), 2);
        Assertions.assertEquals("ISSUED", issuedV2.get("status"), "setup: quote v2 ISSUED");
        Map<String, Object> issuedV2TokenRow = quoteTokenRow(issuedV2Token);
        Assertions.assertEquals("ACTIVE", issuedV2TokenRow.get("status"), "setup: v2's link is ACTIVE");

        assertRemoteAccepted(publicAccept(link.token(), 1), "remote invoice accept");

        Timestamp acceptedAt = (Timestamp) invoiceRow(order.orderId(), 2).get("accepted_at");
        Assertions.assertNotNull(acceptedAt, "the remote accept signed v2");
        Assertions.assertEquals(acceptedV1, quoteVersionRow(order.orderId(), 1), "D5(d): the ACCEPTED v1 is untouched");
        Assertions.assertEquals(acceptedV1Tokens, rows(
                        "SELECT * FROM quote_token WHERE quote_version_id = ? ORDER BY quote_token_id", acceptedV1Id),
                "D5(d): the accepted v1's consumed link is untouched");
        Assertions.assertEquals(withColumns(issuedV2, "status", "CANCELLED"), quoteVersionRow(order.orderId(), 2),
                "D5(c): only the ISSUED v2 is CANCELLED");
        Assertions.assertEquals(withColumns(issuedV2TokenRow, "status", "CANCELLED", "dead_at", acceptedAt),
                quoteTokenRow(issuedV2Token), "D5(c): v2's link is CANCELLED with dead_at = the invoice accepted_at");
        Assertions.assertEquals("CANCELLED", publicQuoteState(issuedV2Token), "v2's link is dead on the quote surface");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED", "dead_at", acceptedAt),
                tokenRow(link.token()), "the invoice link is SIGNED");
    }

    /**
     * With no ISSUED quote, D5(c) is a no-op: a quote issued and then cancelled through the REAL cancel endpoint (both
     * steps leave the ACTIVE invoice link untouched) keeps every quote row exactly as it was through the remote
     * invoice accept, which succeeds.
     */
    @Test
    void remoteAccept_withNoIssuedQuote_isANoOpOnQuoteRows_andTheQuoteSendAndCancelNeverTouchTheInvoiceLink()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        saveDraft(order.orderId(), ITEMISED_DRAFT);
        sendQuote(order.orderId(), 1);
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()), "the quote send never touches the link");
        cancelQuote(order.orderId());
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()),
                "the quote cancel never touches the invoice link");
        Assertions.assertEquals("CANCELLED", quoteVersionRow(order.orderId(), 1).get("status"),
                "setup: no ISSUED quote remains");
        List<Map<String, Object>> quoteVersions = quoteVersionRows(order.orderId());
        List<Map<String, Object>> quoteTokens = quoteTokenRows(order.orderId());

        assertRemoteAccepted(publicAccept(link.token(), 1), "remote invoice accept");

        Assertions.assertEquals(quoteVersions, quoteVersionRows(order.orderId()), "no ISSUED quote: no quote_version change");
        Assertions.assertEquals(quoteTokens, quoteTokenRows(order.orderId()), "no ISSUED quote: no quote_token change");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED",
                        "dead_at", invoiceRow(order.orderId(), 2).get("accepted_at")), tokenRow(link.token()),
                "the invoice link is SIGNED");
    }

    /**
     * D5(d) and D5(b) around a remote signature. A quote accepted through the REAL public quote accept (while the
     * invoice link was ACTIVE - untouched by the quote send and accept) neither blocks the remote invoice accept nor is
     * touched by it. The remote signature is LATER than the quote's, so Path A of that quote is refused: 409
     * {@code INVOICE_ALREADY_ACCEPTED} with the Path A message and nothing written. A NEW quote issued and accepted
     * strictly after the remote signature converts (201): v3 from quote v2 with its signature time, the dead invoice
     * link untouched by the quote send, the quote accept and Path A.
     */
    @Test
    void remoteAccept_leavesAnAcceptedQuoteUntouchedAndIsNotBlockedByIt_thenPathAOfThatEarlierQuoteIs409_aNewerAcceptedQuoteConverts()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        saveDraft(order.orderId(), ITEMISED_DRAFT);
        String firstQuoteToken = sendQuote(order.orderId(), 1);
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()), "the quote send never touches the link");
        acceptQuotePublicly(firstQuoteToken);
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()),
                "the public quote accept never touches the invoice link");
        List<Map<String, Object>> acceptedQuoteVersions = quoteVersionRows(order.orderId());
        List<Map<String, Object>> acceptedQuoteTokens = quoteTokenRows(order.orderId());
        Map<String, Object> acceptedQuote = quoteVersionRow(order.orderId(), 1);
        Assertions.assertEquals("ACCEPTED", acceptedQuote.get("status"), "setup: quote v1 ACCEPTED");

        assertRemoteAccepted(publicAccept(link.token(), 1), "remote invoice accept beside an ACCEPTED quote");

        Assertions.assertEquals(acceptedQuoteVersions, quoteVersionRows(order.orderId()),
                "D5(d): the ACCEPTED quote version is never touched");
        Assertions.assertEquals(acceptedQuoteTokens, quoteTokenRows(order.orderId()),
                "D5(d): the accepted quote's consumed link is never touched");
        Map<String, Object> signedV2 = invoiceRow(order.orderId(), 2);
        LocalDateTime invoiceSignedAt = timestamp(signedV2, "accepted_at");
        Assertions.assertTrue(invoiceSignedAt.isAfter(timestamp(acceptedQuote, "accepted_at")),
                "setup: the remote invoice signature is later than the quote's");
        Map<String, Object> signedLink = withColumns(link.row(), "status", "SIGNED", "dead_at", signedV2.get("accepted_at"));
        Assertions.assertEquals(signedLink, tokenRow(link.token()), "the invoice link is SIGNED");

        // D5(b): the quote signed BEFORE the current invoice's remote signature cannot be converted.
        OrderState beforeRefusal = orderState(order.orderId());
        int emailsBefore = invoiceEmailSender.sentEmails().size();
        Timed refused = pathA(order.orderId(), longValue(acceptedQuote.get("quote_version_id")));
        assertErrorEnvelope(refused.result(), 409, "INVOICE_ALREADY_ACCEPTED", PATH_A_NOT_NEWER_MESSAGE,
                "Path A of the earlier quote");
        assertSameState(beforeRefusal, orderState(order.orderId()), "the refused Path A wrote nothing");
        Assertions.assertEquals(emailsBefore, invoiceEmailSender.sentEmails().size(), "the refused Path A emailed nothing");
        Assertions.assertEquals(List.of(signedLink), tokenRows(order.orderId()), "the refused Path A never touches the link");

        // A NEW quote, issued and accepted strictly after the remote signature, converts.
        saveDraft(order.orderId(), CHANGED_NON_ITEMISED_DRAFT);
        String secondQuoteToken = sendQuote(order.orderId(), 2);
        Assertions.assertEquals(List.of(signedLink), tokenRows(order.orderId()),
                "the quote send never touches the (dead) invoice link");
        acceptQuotePublicly(secondQuoteToken);
        Assertions.assertEquals(List.of(signedLink), tokenRows(order.orderId()),
                "the public quote accept never touches the (dead) invoice link");
        Map<String, Object> newerQuote = quoteVersionRow(order.orderId(), 2);
        Assertions.assertEquals("ACCEPTED", newerQuote.get("status"), "setup: quote v2 ACCEPTED");
        Assertions.assertTrue(timestamp(newerQuote, "accepted_at").isAfter(invoiceSignedAt),
                "setup: quote v2 was signed strictly after the remote invoice signature");

        Timed converted = pathA(order.orderId(), longValue(newerQuote.get("quote_version_id")));

        assertStatus(converted.result(), 201, "Path A of the newer quote");
        JsonNode body = json(converted.result());
        Assertions.assertEquals(PATH_A_EMAILED_MESSAGE, body.path("message").asText(), "Path A message");
        assertInvoiceDetail(body.path("data").path("invoice"), 3, null, "Path A response");
        Map<String, Object> v3 = invoiceRow(order.orderId(), 3);
        Assertions.assertEquals(newerQuote.get("quote_version_id"), v3.get("source_quote_version_id"),
                "v3 is converted from quote v2");
        Assertions.assertEquals(newerQuote.get("accepted_at"), v3.get("accepted_at"), "v3 inherits quote v2's signature time");
        Assertions.assertEquals(List.of(signedLink), tokenRows(order.orderId()),
                "Path A never touches a dead invoice link (it kills only an ACTIVE one)");
    }

    // ================================================================
    // 6. LAID and CANCELLED (16G contract section 12)
    // ================================================================

    /**
     * On a LAID order: send-link 201, public GET 200 {@code ACTIVE}, public pdf 200 (the stored v1 bytes with the
     * exact inline Content-Disposition), a Rewrite is 422 {@code ORDER_LOCKED} that leaves the link exactly as it was
     * (still ACTIVE, D.3 still reports it), and the remote accept signs (201), killing the link as SIGNED.
     */
    @Test
    void laidOrder_sendLinkPublicViewPdfAndRemoteAcceptAllWork_rewriteIs422OrderLockedAndLeavesTheTokenActive()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        changeStatus(order.orderId(), "LAID");

        SentLink link = sendLink(order, tenant.first());

        assertActiveView(publicView(link.token()), order, invoiceRow(order.orderId(), 1), link.row(),
                "public GET on a LAID order");
        assertPdfStreams(publicPdf(link.token(), "1"), order, 1, "public pdf on a LAID order");
        OrderState beforeRewrite = orderState(order.orderId());
        Timed rewrite = rewrite(order.orderId());
        assertErrorEnvelope(rewrite.result(), 422, "ORDER_LOCKED", ORDER_LOCKED_MESSAGE, "Rewrite of a LAID order");
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()),
                "the refused Rewrite leaves the ACTIVE link exactly as it was");
        assertSameState(beforeRewrite, orderState(order.orderId()), "the refused Rewrite wrote nothing");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, link.expiresAtText(), "D.3 after the refused Rewrite");

        assertRemoteAccepted(publicAccept(link.token(), 1), "remote accept on a LAID order");

        Map<String, Object> v2 = invoiceRow(order.orderId(), 2);
        Assertions.assertNotNull(v2.get("accepted_at"), "the LAID order's invoice is signed remotely");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED", "dead_at", v2.get("accepted_at")),
                tokenRow(link.token()), "the link is SIGNED");
        Assertions.assertEquals("LAID", orderStatus(order.orderId()), "no order status change");
    }

    /**
     * On a CANCELLED order there is no special rule: send-link (201), the public view and pdf (200) and the remote
     * accept (201) all work.
     */
    @Test
    void cancelledOrder_sendLinkAndRemoteAcceptBothWork_noCancelledRule()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        changeStatus(order.orderId(), "CANCELLED");

        SentLink link = sendLink(order, tenant.first());

        assertActiveView(publicView(link.token()), order, invoiceRow(order.orderId(), 1), link.row(),
                "public GET on a CANCELLED order");
        assertPdfStreams(publicPdf(link.token(), "1"), order, 1, "public pdf on a CANCELLED order");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, link.expiresAtText(), "D.3 on a CANCELLED order");
        assertRemoteAccepted(publicAccept(link.token(), 1), "remote accept on a CANCELLED order");
        Map<String, Object> v2 = invoiceRow(order.orderId(), 2);
        Assertions.assertNotNull(v2.get("accepted_at"), "the CANCELLED order's invoice is signed remotely");
        Assertions.assertEquals(withColumns(link.row(), "status", "SIGNED", "dead_at", v2.get("accepted_at")),
                tokenRow(link.token()), "the link is SIGNED");
        Assertions.assertEquals("CANCELLED", orderStatus(order.orderId()), "no order status change");
    }

    // ================================================================
    // 7. InvoiceDetail.invoice_link_expires_at (16G contract section 4.5)
    // ================================================================

    /**
     * The field is ALWAYS present on InvoiceDetail. D.1 (no link can exist) and D.3 with no link: null. D.3 with an
     * ACTIVE, unexpired link: exactly that link's {@code expires_at}, formatted to whole seconds (the send response's
     * {@code expires_at} and the stored row's, truncated). Repeated reads return the same value and write nothing.
     */
    @Test
    void invoiceDetailLinkField_alwaysPresent_nullOnD1AndOnD3WithoutALink_theActiveLinksExpiryInWholeSecondsOnD3_readsWriteNothing()
            throws Exception {
        Order order = seedInvoiceReadyOrder();
        JsonNode created = createInvoice(order.orderId());
        Assertions.assertTrue(created.has(LINK_FIELD) && created.get(LINK_FIELD).isNull(), "D.1: present and null");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, null, "D.3 with no link ever sent");
        Assertions.assertTrue(tokenRows(order.orderId()).isEmpty(), "no link was sent");

        SentLink link = sendLink(order, tenant.first());

        JsonNode d3 = currentInvoice(order.orderId());
        assertInvoiceDetail(d3, 1, link.expiresAtText(), "D.3 with an ACTIVE, unexpired link");
        String reported = d3.get(LINK_FIELD).asText();
        Assertions.assertTrue(WHOLE_SECOND_TIMESTAMP.matcher(reported).matches(), "whole seconds: " + reported);
        Assertions.assertEquals(JSON_TIMESTAMP.format(timestamp(link.row(), "expires_at")), reported,
                "the stored expires_at, to the second");
        Assertions.assertEquals(link.expiresAtText(), reported, "the send response's expires_at");
        OrderState before = orderState(order.orderId());
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, link.expiresAtText(), "a repeated D.3");
        Assertions.assertEquals(List.of(link.row()), tokenRows(order.orderId()), "D.3 writes no token row");
        assertSameState(before, orderState(order.orderId()), "D.3 writes nothing");
    }

    /**
     * D.3 with an ACTIVE token already past {@code expires_at} (no public request has flipped it) reports null and
     * WRITES NOTHING: the whole token row is identical before and after (still ACTIVE, dead_at null, no expiry flip),
     * as is every invoice / order row, however often staff read. Only a public request flips it (EXPIRED), after which
     * D.3 is still null.
     */
    @Test
    void invoiceDetailLinkField_d3OfAnActiveTokenPastExpiry_isNull_andTheReadWritesNothingNorFlipsTheExpiry()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        Map<String, Object> overAge = backdateExpiry(link.token());
        OrderState before = orderState(order.orderId());

        assertInvoiceDetail(currentInvoice(order.orderId()), 1, null, "D.3 of an over-age ACTIVE link");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, null, "a repeated D.3 of the over-age link");

        Assertions.assertEquals(List.of(overAge), tokenRows(order.orderId()),
                "the staff read wrote nothing: the token is still ACTIVE, dead_at null, no expiry flip");
        Assertions.assertEquals("ACTIVE", tokenRow(link.token()).get("status"), "still ACTIVE");
        Assertions.assertNull(tokenRow(link.token()).get("dead_at"), "still alive");
        assertSameState(before, orderState(order.orderId()), "the staff read wrote nothing at all");

        assertDeadView(publicView(link.token()), EXPIRED, "only a public request flips the expiry");
        Assertions.assertEquals("EXPIRED", tokenRow(link.token()).get("status"), "flipped by the public GET");
        assertInvoiceDetail(currentInvoice(order.orderId()), 1, null, "D.3 after the public flip (EXPIRED)");
    }

    /**
     * A REPLACED-only history - a REPLACED row whose {@code expires_at} is still in the future and no ACTIVE row (built
     * by SQL: a real resend always leaves the replacing ACTIVE row) - gives D.3 null: only an ACTIVE row counts.
     */
    @Test
    void invoiceDetailLinkField_d3WithAReplacedOnlyHistory_isNull_evenThoughThatRowsExpiryIsInTheFuture()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice_token SET status = 'REPLACED', dead_at = ? WHERE invoice_token_id = ?",
                        Timestamp.valueOf(LocalDateTime.now()), link.tokenId()),
                "setup: the only link becomes a REPLACED history row");
        Map<String, Object> replacedOnly = tokenRow(link.token());
        Assertions.assertTrue(timestamp(replacedOnly, "expires_at").isAfter(LocalDateTime.now()),
                "setup: the REPLACED row's expires_at is still in the future");

        assertInvoiceDetail(currentInvoice(order.orderId()), 1, null, "D.3 with a REPLACED-only history");
        assertDeadView(publicView(link.token()), REPLACED, "the public GET agrees: REPLACED");
        Assertions.assertEquals(List.of(replacedOnly), tokenRows(order.orderId()), "the reads wrote nothing");
    }

    /**
     * A signed current version always gives null (16G contract section 4.5 / 7.6). After a remote signature, D.3 and
     * the D.9 Re-send response report null. Even an ACTIVE, unexpired row over the signed version (an invariant
     * breach no endpoint can create; inserted by SQL) does not change that: D.3 and D.9 still report null.
     */
    @Test
    void invoiceDetailLinkField_d9AndD3OfASignedCurrentVersion_areNull_evenWithAnActiveUnexpiredRowOverIt()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        assertRemoteAccepted(publicAccept(link.token(), 1), "remote accept");

        assertInvoiceDetail(currentInvoice(order.orderId()), 2, null, "D.3 of the remotely signed version");
        MvcResult resend = resend(order.orderId());
        assertStatus(resend, 200, "D.9 Re-send");
        Assertions.assertEquals(RESEND_MESSAGE, json(resend).path("message").asText(), "D.9 message");
        assertInvoiceDetail(json(resend).path("data").path("invoice"), 2, null, "D.9 response (signed)");

        // The invariant-breaking ACTIVE row over the signed v2 (SQL only): the signed version still gives null.
        String foreignToken = randomToken();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO invoice_token (order_id, token_hash, status, sent_by_user_id, sent_at, "
                        + "expires_at) VALUES (?, ?, 'ACTIVE', ?, ?, ?)", order.orderId(), sha256Hex(foreignToken),
                tenant.first().userId(), Timestamp.valueOf(now), Timestamp.valueOf(now.plusDays(7)));
        assertInvoiceDetail(currentInvoice(order.orderId()), 2, null, "D.3: a signed current version always gives null");
        MvcResult resendAgain = resend(order.orderId());
        assertStatus(resendAgain, 200, "D.9 Re-send again");
        assertInvoiceDetail(json(resendAgain).path("data").path("invoice"), 2, null,
                "D.9: a signed current version always gives null");
    }

    // ================================================================
    // 8. A rolled-back writer leaves the link untouched (16G contract section 7.5)
    // ================================================================

    /**
     * Contract 7.5: "A rolled-back writer leaves the token unchanged." The Rewrite's own transaction fails at COMMIT
     * (a deferred constraint trigger on its invoice INSERT). The trigger, firing at commit after the whole body ran,
     * records the link's in-flight status through a non-transactional sequence - REWRITTEN, so the kill really ran
     * in that transaction - and then raises. Everything rolls back together: a generic 500, the link row
     * byte-identical (ACTIVE, dead_at null), no invoice version, no file left, nothing sent. Without the fault the
     * same Rewrite then kills the link.
     */
    @Test
    void rewrite_failingAtCommitAfterItsInFlightKill_rollsTheKillBackWithEverythingElse_linkStaysActiveUnchanged_thenRewriteKillsIt()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        resetRecorders();
        Map<String, Object> linkBefore = tokenRow(link.token());
        OrderState before = orderState(order.orderId());

        String trigger = installCommitFailureProbe(order.orderId());
        Timed failed;
        try {
            failed = rewrite(order.orderId());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }

        assertRolledBackWriter(failed.result(), order, link, linkBefore, before, PROBE_REWRITTEN,
                "a Rewrite failing at commit");
        Timed retry = rewrite(order.orderId());
        assertStatus(retry.result(), 201, "the same Rewrite without the fault");
        Assertions.assertEquals("REWRITTEN", tokenRow(link.token()).get("status"), "the committed Rewrite kills the link");
    }

    /**
     * The in-store accept (D.8) fails at COMMIT after its in-flight SIGNED kill (recorded by the probe): the kill
     * rolls back with the signing - a generic 500, the link row byte-identical, no signed version, no signature or
     * PDF file left, no email. Without the fault the same D.8 then signs and kills the link.
     */
    @Test
    void inStoreD8_failingAtCommitAfterItsInFlightKill_rollsTheKillBackWithTheSigning_linkStaysActiveUnchanged_thenD8KillsIt()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        resetRecorders();
        Map<String, Object> linkBefore = tokenRow(link.token());
        OrderState before = orderState(order.orderId());

        String trigger = installCommitFailureProbe(order.orderId());
        MvcResult failed;
        try {
            failed = inStoreAccept(order.orderId(), 1);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }

        assertRolledBackWriter(failed, order, link, linkBefore, before, PROBE_SIGNED, "a D.8 failing at commit");
        MvcResult retry = inStoreAccept(order.orderId(), 1);
        assertStatus(retry, 201, "the same D.8 without the fault");
        Assertions.assertEquals("SIGNED", tokenRow(link.token()).get("status"), "the committed D.8 kills the link");
    }

    /**
     * Path A fails at COMMIT after its in-flight SIGNED kill (recorded by the probe): the kill rolls back with the
     * conversion - a generic 500, the link row byte-identical, no new version, no PDF left, no email, the accepted
     * quote untouched. Without the fault the same Path A then converts and kills the link.
     */
    @Test
    void pathA_failingAtCommitAfterItsInFlightKill_rollsTheKillBackWithTheConversion_linkStaysActiveUnchanged_thenPathAKillsIt()
            throws Exception {
        Order order = seedOrderWithInvoiceV1();
        SentLink link = sendLink(order, tenant.first());
        saveDraft(order.orderId(), ITEMISED_DRAFT);
        acceptQuotePublicly(sendQuote(order.orderId(), 1));
        Map<String, Object> acceptedQuote = quoteVersionRow(order.orderId(), 1);
        Assertions.assertEquals("ACCEPTED", acceptedQuote.get("status"), "setup: quote v1 accepted publicly");
        long quoteVersionId = longValue(acceptedQuote.get("quote_version_id"));
        resetRecorders();
        Map<String, Object> linkBefore = tokenRow(link.token());
        OrderState before = orderState(order.orderId());

        String trigger = installCommitFailureProbe(order.orderId());
        Timed failed;
        try {
            failed = pathA(order.orderId(), quoteVersionId);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }

        assertRolledBackWriter(failed.result(), order, link, linkBefore, before, PROBE_SIGNED, "a Path A failing at commit");
        Timed retry = pathA(order.orderId(), quoteVersionId);
        assertStatus(retry.result(), 201, "the same Path A without the fault");
        Assertions.assertEquals("SIGNED", tokenRow(link.token()).get("status"), "the committed Path A kills the link");
    }

    /**
     * The generic 500 of a writer whose commit failed, the probe's proof that its kill had run in flight, and the
     * rollback of everything: the link row byte-identical, no other link row, the order's rows and files as before,
     * nothing sent.
     */
    private void assertRolledBackWriter(MvcResult result, Order order, SentLink link, Map<String, Object> linkBefore,
                                        OrderState before, long inFlightProbe, String label) throws Exception {
        assertStatus(result, 500, label);
        JsonNode error = json(result).path("error");
        Assertions.assertEquals("INTERNAL_SERVER_ERROR", error.path("code").asText(), label + ": the generic code");
        Assertions.assertEquals("An unexpected error occurred.", error.path("message").asText(),
                label + ": the generic message");
        for (String leak : List.of("illif", "P0001", "trigger", "commit")) {
            Assertions.assertFalse(body(result).contains(leak), label + ": the 500 leaks no '" + leak + "'");
        }
        Assertions.assertEquals(inFlightProbe, probeValue(),
                label + ": in flight - at its commit - the writer had already killed the link");
        Assertions.assertEquals(linkBefore, tokenRow(link.token()),
                label + ": the rollback leaves the link row byte-identical (ACTIVE, dead_at null)");
        Assertions.assertEquals(List.of(linkBefore), tokenRows(order.orderId()), label + ": no other link row");
        assertSameState(before, orderState(order.orderId()), label + ": nothing else committed");
        assertNoSignedInvoiceEmailOrNotification(label);
    }

    /**
     * A deferred constraint trigger raising at COMMIT for the order's invoice INSERT. Its function first records the
     * order's link status AS SEEN INSIDE THE COMMITTING TRANSACTION in a sequence (setval is never rolled back):
     * {@link #PROBE_ACTIVE}, {@link #PROBE_REWRITTEN} or {@link #PROBE_SIGNED}. Returns the trigger's name.
     */
    private String installCommitFailureProbe(long orderId) {
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS " + ROLLBACK_PROBE_SEQUENCE);
        jdbcTemplate.queryForObject("SELECT setval('" + ROLLBACK_PROBE_SEQUENCE + "', " + PROBE_NOT_FIRED + ")", Long.class);
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION " + ROLLBACK_PROBE_FUNCTION + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$\n"
                + "DECLARE in_flight text;\n"
                + "BEGIN\n"
                + "  SELECT status INTO in_flight FROM invoice_token WHERE order_id = NEW.order_id "
                + "ORDER BY invoice_token_id DESC LIMIT 1;\n"
                + "  PERFORM setval('" + ROLLBACK_PROBE_SEQUENCE + "', CASE in_flight WHEN 'ACTIVE' THEN " + PROBE_ACTIVE
                + " WHEN 'REWRITTEN' THEN " + PROBE_REWRITTEN + " WHEN 'SIGNED' THEN " + PROBE_SIGNED + " ELSE 9 END);\n"
                + "  RAISE EXCEPTION 'illif injected commit-time failure' USING ERRCODE = 'P0001';\n"
                + "END;\n$$");
        String trigger = ROLLBACK_TRIGGER_PREFIX + orderId;
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger + " AFTER INSERT ON invoice "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.order_id = " + orderId + ") "
                + "EXECUTE FUNCTION " + ROLLBACK_PROBE_FUNCTION + "()");
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice'::regclass "
                + "AND tgname = ?", trigger), "setup: the commit-failure probe is installed");
        return trigger;
    }

    private long probeValue() {
        Long value = jdbcTemplate.queryForObject("SELECT last_value FROM " + ROLLBACK_PROBE_SEQUENCE, Long.class);
        return value == null ? -1L : value;
    }

    /** Drop every rollback probe this class may have left (triggers, function, sequence); crashed runs included. */
    private void dropRollbackProbes() {
        for (String trigger : jdbcTemplate.queryForList("SELECT tgname FROM pg_trigger WHERE tgrelid = "
                + "'invoice'::regclass AND tgname LIKE 'illif\\_rollback\\_%'", String.class)) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + ROLLBACK_PROBE_FUNCTION + "()");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS " + ROLLBACK_PROBE_SEQUENCE);
    }

    // ================================================================
    // Requests - protected (the seeded tenant's session and slug)
    // ================================================================

    private MockHttpSession session(Staff staff) {
        MockHttpSession session = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        session.setAttribute("user_id", Long.valueOf(staff.userId()));
        session.setAttribute("business_id", Long.valueOf(tenant.businessId()));
        session.setAttribute("store_id", Integer.valueOf(tenant.storeId()));
        return session;
    }

    private String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    private MvcResult perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private Timed timed(RequestBuilder request) throws Exception {
        LocalDateTime start = LocalDateTime.now();
        MvcResult result = mockMvc.perform(request).andReturn();
        return new Timed(result, start, LocalDateTime.now());
    }

    private MockHttpServletRequestBuilder protectedJson(MockHttpServletRequestBuilder request, String json) {
        return request.session(session(tenant.first())).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** D.1 Create: 201, {@code Invoice created.}, InvoiceDetail v1 with the link field present and null. */
    private JsonNode createInvoice(long orderId) throws Exception {
        MvcResult result = perform(protectedJson(post(orderUrl(orderId) + "/invoices"), "{}"));
        assertStatus(result, 201, "D.1 Create");
        JsonNode body = json(result);
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, body.path("message").asText(), "D.1 message");
        JsonNode invoice = body.path("data").path("invoice");
        assertInvoiceDetail(invoice, 1, null, "D.1 response (no link can exist)");
        Assertions.assertTrue(invoice.path("accepted_at").isNull(), "D.1: v1 is unsigned");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, invoice.path("customer_name_snapshot").asText(),
                "D.1: the frozen Invoice To name");
        return invoice;
    }

    private Timed rewrite(long orderId) throws Exception {
        return timed(protectedJson(post(orderUrl(orderId) + "/invoices/rewrite"), "{}"));
    }

    /** D.3: 200 with only {@code data.invoice} (no message). */
    private JsonNode currentInvoice(long orderId) throws Exception {
        MvcResult result = perform(get(orderUrl(orderId) + "/invoices/current").session(session(tenant.first())));
        assertStatus(result, 200, "D.3");
        JsonNode body = json(result);
        Assertions.assertEquals(Set.of("data"), fieldNames(body), "D.3: data only, no message");
        return body.path("data").path("invoice");
    }

    /** D.8 (16G PR 3): the signature part + the mandatory {@code expected_version_number}; no name part. */
    private MvcResult inStoreAccept(long orderId, int expectedVersion) throws Exception {
        return perform(multipart(orderUrl(orderId) + "/invoices/current/accept")
                .file(signaturePart())
                .param("expected_version_number", String.valueOf(expectedVersion))
                .session(session(tenant.first())));
    }

    private MvcResult resend(long orderId) throws Exception {
        return perform(protectedJson(post(orderUrl(orderId) + "/invoices/current/resend"), "{}"));
    }

    /** Path A (16G PR 3): body {@code {"quote_version_id": N}}. */
    private Timed pathA(long orderId, long quoteVersionId) throws Exception {
        return timed(protectedJson(post(orderUrl(orderId) + "/quote/create-invoice"),
                "{\"quote_version_id\": " + quoteVersionId + "}"));
    }

    /** D.7: 201 {@code Payment recorded. ...}; returns the response body. */
    private JsonNode recordPayment(long orderId, String amount) throws Exception {
        MvcResult result = perform(protectedJson(post(orderUrl(orderId) + "/payments"),
                "{\"payment_method\":\"EFTPOS\",\"amount\":" + amount + ",\"payment_reference\":null}"));
        assertStatus(result, 201, "D.7 payment");
        JsonNode body = json(result);
        Assertions.assertEquals(PAYMENT_RECORDED_MESSAGE, body.path("message").asText(), "payment message");
        Assertions.assertFalse(body.toString().contains(LINK_FIELD), "the payment response never carries the link field");
        return body;
    }

    /** D.10: 201 {@code Payment voided. ...}; returns the response body. */
    private JsonNode voidPayment(long orderId, long paymentTransactionId) throws Exception {
        MvcResult result = perform(protectedJson(
                post(orderUrl(orderId) + "/payments/" + paymentTransactionId + "/void"), "{}"));
        assertStatus(result, 201, "D.10 void");
        JsonNode body = json(result);
        Assertions.assertEquals(PAYMENT_VOIDED_MESSAGE, body.path("message").asText(), "void message");
        Assertions.assertFalse(body.toString().contains(LINK_FIELD), "the void response never carries the link field");
        return body;
    }

    /** The real status endpoint (PATCH .../status), 200 {@code Order status updated.}. */
    private void changeStatus(long orderId, String status) throws Exception {
        MvcResult result = perform(protectedJson(patch(orderUrl(orderId) + "/status"),
                "{\"order_status\":\"" + status + "\"}"));
        assertStatus(result, 200, "status change to " + status);
        JsonNode body = json(result);
        Assertions.assertEquals(STATUS_UPDATED_MESSAGE, body.path("message").asText(), "status message");
        Assertions.assertEquals(status, orderStatus(orderId), "the status change committed");
    }

    private void saveDraft(long orderId, String draftJson) throws Exception {
        MvcResult result = perform(protectedJson(put(orderUrl(orderId) + "/quote/draft"), draftJson));
        assertStatus(result, 200, "quote draft save");
    }

    /** Issue (or resend) through the protected send-email; the plaintext quote token from the recorded email. */
    private String sendQuote(long orderId, int expectedVersion) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = perform(protectedJson(post(orderUrl(orderId) + "/quote/send-email"), "{}"));
        assertStatus(result, 201, "quote send-email (v" + expectedVersion + ")");
        JsonNode body = json(result);
        Assertions.assertEquals(QUOTE_SENT_MESSAGE, body.path("message").asText(), "quote send message");
        Assertions.assertEquals(expectedVersion, body.path("data").path("version_number").asInt(),
                "the issued quote version");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "exactly one quote email per send");
        Matcher matcher = QUOTE_LINK_PATTERN.matcher(sent.get(sent.size() - 1).bodyText());
        Assertions.assertTrue(matcher.find(), "the quote email carries the /q/{token} link");
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "the quote link appears exactly once");
        return token;
    }

    private void cancelQuote(long orderId) throws Exception {
        MvcResult result = perform(protectedJson(post(orderUrl(orderId) + "/quote/cancel"), "{}"));
        assertStatus(result, 200, "quote cancel");
        Assertions.assertEquals(QUOTE_CANCELLED_MESSAGE, json(result).path("message").asText(), "cancel message");
    }

    /** The real public quote accept (token-only, ONE signature part, no session). */
    private void acceptQuotePublicly(String quoteToken) throws Exception {
        MvcResult result = perform(multipart(PUBLIC_QUOTES + quoteToken + "/accept").file(signaturePart()));
        assertStatus(result, 201, "public quote accept");
        Assertions.assertEquals(QUOTE_ACCEPTED_MESSAGE, json(result).path("message").asText(), "quote accept message");
    }

    /** The public quote link's customer-facing state (the quote GET writes nothing relevant here). */
    private String publicQuoteState(String quoteToken) throws Exception {
        MvcResult result = perform(get(PUBLIC_QUOTES + quoteToken));
        assertStatus(result, 200, "public quote GET");
        return json(result).path("data").path("state").asText();
    }

    /** The draft, sent, then accepted through the real public quote accept: returns the accepted quote v1's id. */
    private long acceptQuoteV1Publicly(Order order) throws Exception {
        saveDraft(order.orderId(), ITEMISED_DRAFT);
        acceptQuotePublicly(sendQuote(order.orderId(), 1));
        Map<String, Object> accepted = quoteVersionRow(order.orderId(), 1);
        Assertions.assertEquals("ACCEPTED", accepted.get("status"), "setup: quote v1 accepted publicly");
        return longValue(accepted.get("quote_version_id"));
    }

    private static MockMultipartFile signaturePart() {
        return new MockMultipartFile("signature", "signature.png", "image/png", ONE_PIXEL_PNG);
    }

    // ================================================================
    // Requests - the protected send-link (Accept Remotely)
    // ================================================================

    /**
     * POST .../invoices/current/send-link with {@code {}}: asserts the exact 201, the link-only email (exactly one
     * new one, to the trimmed saved email, the bearer link exactly once, the current version) and the minted row
     * (hash, ACTIVE, sender, {@code sent_at} inside the request, {@code expires_at} = {@code sent_at} + 7 days,
     * {@code emailed_at} stamped, {@code dead_at} null), and that it is the order's only ACTIVE row.
     */
    private SentLink sendLink(Order order, Staff staff) throws Exception {
        int currentVersion = currentInvoiceVersion(order.orderId());
        int linkEmailsBefore = invoiceLinkEmailSender.sentEmails().size();
        int signedEmailsBefore = invoiceEmailSender.sentEmails().size();
        int notificationsBefore = invoiceNotificationSender.sentNotifications().size();
        LocalDateTime start = LocalDateTime.now();
        MvcResult result = perform(post(orderUrl(order.orderId()) + "/invoices/current/send-link")
                .session(session(staff)).contentType(MediaType.APPLICATION_JSON).content("{}"));
        LocalDateTime end = LocalDateTime.now();

        assertStatus(result, 201, "send-link");
        JsonNode body = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(body), "send-link: envelope keys");
        Assertions.assertEquals(LINK_SENT_MESSAGE, body.path("message").asText(), "send-link: message");
        Assertions.assertEquals(Set.of("invoice_link"), fieldNames(body.path("data")), "send-link: data keys");
        JsonNode responseLink = body.path("data").path("invoice_link");
        Assertions.assertEquals(Set.of("sent_at", "expires_at"), fieldNames(responseLink), "send-link: link keys");
        String sentAtText = responseLink.path("sent_at").asText();
        String expiresAtText = responseLink.path("expires_at").asText();
        Assertions.assertTrue(WHOLE_SECOND_TIMESTAMP.matcher(sentAtText).matches(), "sent_at whole seconds: " + sentAtText);
        Assertions.assertTrue(WHOLE_SECOND_TIMESTAMP.matcher(expiresAtText).matches(),
                "expires_at whole seconds: " + expiresAtText);

        // The link-only email.
        Assertions.assertTrue(invoiceLinkEmailSender.failedEmails().isEmpty(), "no failed link email");
        List<InvoiceLinkEmailRequest> sent = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(linkEmailsBefore + 1, sent.size(), "exactly one link email per send");
        InvoiceLinkEmailRequest email = sent.get(sent.size() - 1);
        String token = extractInvoiceToken(email.bodyText());
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), "link email: the TRIMMED saved customer email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), "link email: subject");
        Assertions.assertEquals("Hi,\n\nYour invoice " + order.orderNumber() + " from " + BUSINESS_NAME
                + " is ready to review and sign. You can view and sign your invoice online here: " + PUBLIC_BASE_URL
                + "/i/" + token + "\n\nThank you.", email.bodyText(), "link email: body");
        Assertions.assertEquals(order.orderId(), email.orderId(), "link email: order id");
        Assertions.assertEquals(currentVersion, email.invoiceVersionNumber(), "link email: the current version");
        Assertions.assertEquals(signedEmailsBefore, invoiceEmailSender.sentEmails().size(),
                "a link send is never a signed-PDF email");
        Assertions.assertEquals(notificationsBefore, invoiceNotificationSender.sentNotifications().size(),
                "a link send never notifies the store");

        // The minted row.
        Map<String, Object> row = tokenRow(token);
        Assertions.assertEquals(order.orderId(), longValue(row.get("order_id")), "token: order");
        Assertions.assertEquals(sha256Hex(token), row.get("token_hash"), "token: only the SHA-256 hex hash is stored");
        Assertions.assertEquals("ACTIVE", row.get("status"), "token: ACTIVE");
        Assertions.assertEquals(staff.userId(), longValue(row.get("sent_by_user_id")), "token: the session user sent it");
        LocalDateTime sentAt = timestamp(row, "sent_at");
        assertWithin(sentAt, start, end, "token: sent_at is the send time");
        Assertions.assertEquals(sentAt.plusDays(7), timestamp(row, "expires_at"), "token: expires_at = sent_at + 7 days");
        LocalDateTime emailedAt = timestamp(row, "emailed_at");
        Assertions.assertNotNull(emailedAt, "token: the delivered link's emailed_at is stamped");
        Assertions.assertFalse(emailedAt.isBefore(sentAt), "token: emailed_at is not before sent_at");
        assertWithin(emailedAt, start, end, "token: emailed_at is stamped by this send");
        Assertions.assertNull(row.get("dead_at"), "token: alive");
        Assertions.assertNotNull(row.get("created_at"), "token: created_at");
        Assertions.assertEquals(JSON_TIMESTAMP.format(sentAt), sentAtText, "the 201 sent_at is the stored sent_at");
        Assertions.assertEquals(JSON_TIMESTAMP.format(timestamp(row, "expires_at")), expiresAtText,
                "the 201 expires_at is the stored expires_at");
        long tokenId = longValue(row.get("invoice_token_id"));
        Assertions.assertEquals(List.of(tokenId), jdbcTemplate.queryForList(
                        "SELECT invoice_token_id FROM invoice_token WHERE order_id = ? AND status = 'ACTIVE'",
                        Long.class, order.orderId()),
                "exactly one ACTIVE link per order: the new one");
        return new SentLink(token, tokenId, row, sentAtText, expiresAtText);
    }

    private static String extractInvoiceToken(String bodyText) {
        Matcher matcher = INVOICE_LINK_PATTERN.matcher(bodyText);
        Assertions.assertTrue(matcher.find(), "the link email carries the /i/{token} link: " + bodyText);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "the invoice link appears exactly once");
        Assertions.assertEquals(1, countOccurrences(bodyText, token), "the token appears exactly once in the body");
        return token;
    }

    // ================================================================
    // Requests - public, token-only invoice surface (ONE helper for every request)
    // ================================================================

    /**
     * EVERY public invoice request goes through here, on the MockMvc that runs the real Spring Security filter chain.
     * The request never carries a session (asserted on the built request), and for every status the response created
     * no session, set no cookie and - for a JSON body - never mentions the protected {@code invoice_link_expires_at}.
     */
    private MvcResult performPublicInvoiceRequest(MockHttpServletRequestBuilder request, String label) throws Exception {
        MvcResult result = mockMvc.perform(request.with(built -> {
            Assertions.assertNull(built.getSession(false), label + ": a public invoice request never carries a session");
            return built;
        })).andReturn();
        Assertions.assertNull(result.getRequest().getSession(false),
                label + ": a public invoice request never creates a session (status "
                        + result.getResponse().getStatus() + ")");
        Assertions.assertTrue(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).isEmpty(),
                label + ": a public invoice response never sets a cookie");
        Assertions.assertEquals(0, result.getResponse().getCookies().length, label + ": no cookie");
        String contentType = result.getResponse().getContentType();
        if (contentType != null && MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON)) {
            Assertions.assertFalse(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(LINK_FIELD),
                    label + ": no public payload carries invoice_link_expires_at");
        }
        return result;
    }

    private MvcResult publicView(String token) throws Exception {
        return performPublicInvoiceRequest(get(PUBLIC_INVOICES + token), "public GET");
    }

    private Timed timedPublicView(String token) throws Exception {
        LocalDateTime start = LocalDateTime.now();
        MvcResult result = publicView(token);
        return new Timed(result, start, LocalDateTime.now());
    }

    private MvcResult publicPdf(String token, String versionNumber) throws Exception {
        MockHttpServletRequestBuilder request = get(PUBLIC_INVOICES + token + "/pdf");
        if (versionNumber != null) {
            request.param("version_number", versionNumber);
        }
        return performPublicInvoiceRequest(request, "public pdf");
    }

    /** The remote accept exactly as the page sends it: the form field + one PNG signature part. */
    private MvcResult publicAccept(String token, int expectedVersion) throws Exception {
        return performPublicInvoiceRequest(multipart(PUBLIC_INVOICES + token + "/accept")
                .file(signaturePart())
                .param("expected_version_number", String.valueOf(expectedVersion)), "public accept");
    }

    private Timed timedPublicAccept(String token, int expectedVersion) throws Exception {
        LocalDateTime start = LocalDateTime.now();
        MvcResult result = publicAccept(token, expectedVersion);
        return new Timed(result, start, LocalDateTime.now());
    }

    // ================================================================
    // Assertions - responses
    // ================================================================

    /** InvoiceDetail: exactly its 24 keys, the version, and the derived link field PRESENT with the expected value. */
    private static void assertInvoiceDetail(JsonNode invoice, int expectedVersion, String expectedLinkExpiresAt,
                                            String label) {
        Assertions.assertEquals(INVOICE_DETAIL_KEYS, fieldNames(invoice), label + ": InvoiceDetail keys");
        Assertions.assertEquals(expectedVersion, invoice.path("version_number").asInt(), label + ": version_number");
        Assertions.assertTrue(invoice.has(LINK_FIELD), label + ": invoice_link_expires_at is always present");
        if (expectedLinkExpiresAt == null) {
            Assertions.assertTrue(invoice.get(LINK_FIELD).isNull(),
                    label + ": invoice_link_expires_at must be null but was " + invoice.get(LINK_FIELD));
        } else {
            Assertions.assertEquals(expectedLinkExpiresAt, invoice.get(LINK_FIELD).asText(),
                    label + ": invoice_link_expires_at");
        }
    }

    /** E.1 current_invoice (payment / void): exactly its 15 keys - never the link field. */
    private static void assertCurrentInvoiceSummary(JsonNode currentInvoice, int expectedVersion, String label) {
        Assertions.assertEquals(CURRENT_INVOICE_SUMMARY_KEYS, fieldNames(currentInvoice), label + ": E.1 keys");
        Assertions.assertFalse(currentInvoice.has(LINK_FIELD), label + ": E.1 never carries invoice_link_expires_at");
        Assertions.assertEquals(expectedVersion, currentInvoice.path("version_number").asInt(), label + ": version");
    }

    /**
     * The ACTIVE public view: 200 {@code Invoice loaded.}, exactly the 27 keys, the current version's stored words,
     * dates and money verbatim ({@code gst_amount} = inc - ex), the order's number and flooring type, the live
     * business presentation, the presented token's {@code expires_at} and a null message.
     */
    private JsonNode assertActiveView(MvcResult result, Order order, Map<String, Object> current,
                                      Map<String, Object> token, String label) throws Exception {
        assertStatus(result, 200, label);
        JsonNode body = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(body), label + ": envelope keys");
        Assertions.assertEquals(INVOICE_LOADED_MESSAGE, body.path("message").asText(), label + ": message");
        JsonNode data = body.path("data");
        Assertions.assertEquals(PUBLIC_VIEW_KEYS, fieldNames(data), label + ": exactly the 27 keys");
        assertText("ACTIVE", data, "state", label);
        assertText(BUSINESS_NAME, data, "business_name", label);
        Assertions.assertTrue(data.path("business_logo_url").isNull(), label + ": no logo seeded");
        assertText(ACCENT_COLOUR, data, "accent_color", label);
        assertText(ABN, data, "business_abn", label);
        assertText(ACCOUNT_NAME, data, "payment_account_name", label);
        assertText(BANK_NAME, data, "payment_bank_name", label);
        assertText(BSB, data, "payment_bsb", label);
        assertText(ACCOUNT_NUMBER, data, "payment_account_number", label);
        assertText(STRIPE_LINK, data, "payment_stripe_link_url", label);
        assertText(order.orderNumber(), data, "order_number", label);
        assertText("SOFT", data, "flooring_type", label);
        Assertions.assertEquals(intValue(current.get("version_number")), data.path("version_number").asInt(),
                label + ": the CURRENT version");
        assertText(((Date) current.get("invoice_date")).toLocalDate().toString(), data, "invoice_date", label);
        assertText(((Date) current.get("due_date")).toLocalDate().toString(), data, "due_date", label);
        assertNullableText((String) current.get("customer_name_snapshot"), data, "customer_name", label);
        assertNullableText((String) current.get("customer_address_line1_snapshot"), data, "customer_address_line1", label);
        assertNullableText((String) current.get("customer_address_line2_snapshot"), data, "customer_address_line2", label);
        assertNullableText((String) current.get("details_of_sale_snapshot"), data, "details_of_sale", label);
        BigDecimal ex = (BigDecimal) current.get("sale_price_ex_gst");
        BigDecimal inc = (BigDecimal) current.get("sale_price_inc_gst");
        assertJsonMoney(ex, data.path("sale_price_ex_gst"), label + ": sale_price_ex_gst");
        assertJsonMoney(inc.subtract(ex), data.path("gst_amount"), label + ": gst_amount = inc - ex");
        assertJsonMoney(inc, data.path("sale_price_inc_gst"), label + ": sale_price_inc_gst");
        assertJsonMoney((BigDecimal) current.get("total_paid"), data.path("total_paid"), label + ": total_paid");
        assertJsonMoney((BigDecimal) current.get("balance_due"), data.path("balance_due"), label + ": balance_due");
        assertNullableText((String) current.get("terms_snapshot"), data, "terms_html", label);
        assertText(JSON_TIMESTAMP.format(timestamp(token, "expires_at")), data, "expires_at", label);
        Assertions.assertTrue(data.path("message").isNull(), label + ": no message while ACTIVE");
        return data;
    }

    /** The ACTIVE view's version and paid amounts (the payment / void follow-through, readably). */
    private void assertActiveViewMoney(MvcResult result, int version, String totalPaid, String balanceDue, String label)
            throws Exception {
        assertStatus(result, 200, label);
        JsonNode data = json(result).path("data");
        assertText("ACTIVE", data, "state", label);
        Assertions.assertEquals(version, data.path("version_number").asInt(), label + ": version_number");
        assertJsonMoney(new BigDecimal(totalPaid), data.path("total_paid"), label + ": total_paid");
        assertJsonMoney(new BigDecimal(balanceDue), data.path("balance_due"), label + ": balance_due");
    }

    /** A dead public view: 200 {@code Invoice loaded.}, the 27 keys, only state / business_name / message set. */
    private static void assertDeadView(MvcResult result, DeadReason reason, String label) throws Exception {
        assertStatus(result, 200, label);
        JsonNode body = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(body), label + ": envelope keys");
        Assertions.assertEquals(INVOICE_LOADED_MESSAGE, body.path("message").asText(), label + ": message");
        JsonNode data = body.path("data");
        Assertions.assertEquals(PUBLIC_VIEW_KEYS, fieldNames(data), label + ": exactly the 27 keys");
        for (String key : PUBLIC_VIEW_KEYS) {
            switch (key) {
                case "state" -> assertText(reason.state(), data, key, label);
                case "business_name" -> assertText(BUSINESS_NAME, data, key, label);
                case "message" -> assertText(reason.message(), data, key, label);
                default -> Assertions.assertTrue(data.path(key).isNull(),
                        label + ": " + key + " must be null on a dead link but was " + data.path(key));
            }
        }
    }

    /** 410 with exactly {@code {error: {code, message}}} for the dead reason. */
    private static void assertGone(MvcResult result, DeadReason reason, String label) throws Exception {
        assertErrorEnvelope(result, 410, reason.code(), reason.message(), label);
    }

    /** 409 {@code INVOICE_VERSION_STALE}, exactly. */
    private static void assertStale(MvcResult result, String label) throws Exception {
        assertErrorEnvelope(result, 409, "INVOICE_VERSION_STALE", INVOICE_VERSION_STALE_MESSAGE, label);
    }

    private static void assertErrorEnvelope(MvcResult result, int status, String code, String message, String label)
            throws Exception {
        assertStatus(result, status, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("error"), fieldNames(root), label + ": a refusal carries no data");
        JsonNode error = root.path("error");
        Assertions.assertEquals(Set.of("code", "message"), fieldNames(error), label + ": no details");
        Assertions.assertEquals(code, error.path("code").asText(), label + ": error code");
        Assertions.assertEquals(message, error.path("message").asText(), label + ": error message");
    }

    /** The exact 201 of a successful remote accept. */
    private static void assertRemoteAccepted(MvcResult result, String label) throws Exception {
        assertStatus(result, 201, label);
        Assertions.assertEquals(JSON.readTree(REMOTE_ACCEPTED_BODY), json(result),
                label + ": exactly {data:{state:SIGNED}, message:Invoice accepted.}");
    }

    /** 200 application/pdf with the exact inline Content-Disposition and the version's stored bytes verbatim. */
    private void assertPdfStreams(MvcResult result, Order order, int version, String label) throws Exception {
        assertStatus(result, 200, label);
        Assertions.assertEquals(MediaType.APPLICATION_PDF_VALUE, result.getResponse().getContentType(),
                label + ": content type");
        Assertions.assertEquals("inline; filename=\"" + invoicePdfName(order.orderNumber(), version) + "\"",
                result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION), label + ": Content-Disposition");
        Map<String, Object> stored = storedFileRow(longValue(invoiceRow(order.orderId(), version).get("stored_file_id")));
        Assertions.assertArrayEquals(fileStorageService.read((String) stored.get("storage_path")),
                result.getResponse().getContentAsByteArray(), label + ": the stored bytes of v" + version + ", verbatim");
    }

    /** The store notification body; the minute may be rendered from the instant before micro-rounding. */
    private static void assertNotificationBody(String body, Order order, int version, LocalDateTime acceptedAt,
                                               String incTotal) {
        String prefix = "Invoice " + order.orderNumber() + " (version " + version + ") was accepted online by "
                + FROZEN_CUSTOMER_NAME + " on ";
        String suffix = ".\n\nInvoice total (inc GST): $" + incTotal + ".\n\nThe signed invoice is available in the "
                + "sales portal.";
        String exact = prefix + DISPLAY_DATE_TIME.format(acceptedAt) + suffix;
        String roundedBack = prefix + DISPLAY_DATE_TIME.format(acceptedAt.minusNanos(1_000)) + suffix;
        Assertions.assertTrue(body.equals(exact) || body.equals(roundedBack),
                "store notification body; expected " + exact + " but was " + body);
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + body(result));
    }

    private static void assertText(String expected, JsonNode data, String key, String label) {
        Assertions.assertTrue(data.path(key).isTextual(), label + ": " + key + " must be a string, was " + data.path(key));
        Assertions.assertEquals(expected, data.path(key).asText(), label + ": " + key);
    }

    private static void assertNullableText(String expected, JsonNode data, String key, String label) {
        if (expected == null) {
            Assertions.assertTrue(data.path(key).isNull(), label + ": " + key + " must be null, was " + data.path(key));
        } else {
            assertText(expected, data, key, label);
        }
    }

    private static void assertJsonMoney(BigDecimal expected, JsonNode actual, String label) {
        Assertions.assertTrue(actual.isNumber(), label + ": must be a number, was " + actual);
        Assertions.assertEquals(0, expected.compareTo(actual.decimalValue()),
                label + ": expected " + expected + " but was " + actual);
    }

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertNotNull(actual, label + ": expected " + expected + " but was null");
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
                label + ": expected " + expected + " but was " + actual);
    }

    // ================================================================
    // Assertions - token rows
    // ================================================================

    /**
     * A writer's kill: the row now carries {@code reason} with {@code dead_at} inside the writer's request, and every
     * other column exactly as {@code before}.
     */
    private static void assertKilledWithin(Map<String, Object> before, Map<String, Object> after, DeadReason reason,
                                           Timed request, String label) {
        Assertions.assertEquals(reason.state(), after.get("status"), label + ": the recorded reason");
        LocalDateTime deadAt = timestamp(after, "dead_at");
        Assertions.assertNotNull(deadAt, label + ": dead_at is set");
        assertWithin(deadAt, request.start(), request.end(), label + ": dead_at is the time of this request");
        Assertions.assertEquals(withColumns(before, "status", reason.state(), "dead_at", after.get("dead_at")), after,
                label + ": nothing but status and dead_at changed");
    }

    /**
     * The writer's reason on a past-expiry dead row sticks: the public GET reports it (never EXPIRED), accept and pdf
     * answer its 410, and the row is identical before and after those public requests.
     */
    private void assertWriterReasonSticks(String token, DeadReason reason, int currentVersion) throws Exception {
        Map<String, Object> dead = tokenRow(token);
        Assertions.assertTrue(timestamp(dead, "expires_at").isBefore(LocalDateTime.now()), "setup: past its expiry");
        assertDeadView(publicView(token), reason, "public GET of the writer-killed over-age link");
        assertGone(publicAccept(token, currentVersion), reason, "public accept of the writer-killed over-age link");
        assertGone(publicPdf(token, String.valueOf(currentVersion)), reason, "public pdf of the writer-killed link");
        Assertions.assertEquals(dead, tokenRow(token), "the public requests never relabel the writer's reason");
    }

    private static void assertWithin(LocalDateTime value, LocalDateTime start, LocalDateTime end, String label) {
        Assertions.assertNotNull(value, label + ": null");
        // TIMESTAMP keeps microseconds (rounded): allow the bounds' own micro-rounding, nothing more.
        LocalDateTime lower = start.truncatedTo(ChronoUnit.MICROS);
        LocalDateTime upper = end.truncatedTo(ChronoUnit.MICROS).plusNanos(1_000);
        Assertions.assertFalse(value.isBefore(lower) || value.isAfter(upper),
                label + ": " + value + " is not within [" + start + ", " + end + "]");
    }

    private void assertNoSignedInvoiceEmailOrNotification(String context) {
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty() && invoiceEmailSender.failedEmails().isEmpty(),
                context + ": no signed-invoice email was sent or attempted");
        Assertions.assertTrue(invoiceNotificationSender.sentNotifications().isEmpty()
                        && invoiceNotificationSender.failedNotifications().isEmpty(),
                context + ": no store notification was sent or attempted");
    }

    private void assertNoQuoteMessages(String context) {
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty() && quoteEmailSender.failedEmails().isEmpty(),
                context + ": no quote email");
        Assertions.assertTrue(quoteNotificationSender.sentNotifications().isEmpty()
                && quoteNotificationSender.failedNotifications().isEmpty(), context + ": no quote notification");
        Assertions.assertTrue(smsSender.sentMessages().isEmpty() && smsSender.failedMessages().isEmpty(),
                context + ": no SMS");
    }

    // ================================================================
    // Committed-state probes (auto-commit JDBC - committed data only)
    // ================================================================

    /** Everything on one order a link operation must not touch, plus the order's files on disk. */
    private OrderState orderState(long orderId) {
        return new OrderState(
                row("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                        orderStoragePrefix(tenant.businessId(), orderId) + "%"),
                filesOnDisk(orderId),
                rows("SELECT * FROM payment_transaction WHERE order_id = ? ORDER BY payment_transaction_id", orderId),
                quoteVersionRows(orderId),
                quoteTokenRows(orderId));
    }

    private static void assertSameState(OrderState expected, OrderState actual, String label) {
        Assertions.assertEquals(expected.salesOrder(), actual.salesOrder(), label + ": the sales_order row (mirror incl.)");
        Assertions.assertEquals(expected.invoices(), actual.invoices(), label + ": the invoice rows (markers incl.)");
        Assertions.assertEquals(expected.storedFiles(), actual.storedFiles(), label + ": the order's stored_file rows");
        Assertions.assertEquals(expected.filesOnDisk(), actual.filesOnDisk(), label + ": the order's files on disk");
        Assertions.assertEquals(expected.payments(), actual.payments(), label + ": the payment rows");
        Assertions.assertEquals(expected.quoteVersions(), actual.quoteVersions(), label + ": the quote_version rows");
        Assertions.assertEquals(expected.quoteTokens(), actual.quoteTokens(), label + ": the quote_token rows");
    }

    private Map<String, Object> tokenRow(String token) {
        return row("SELECT * FROM invoice_token WHERE token_hash = ?", sha256Hex(token));
    }

    private List<Map<String, Object>> tokenRows(long orderId) {
        return rows("SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId);
    }

    /** Move a token's expiry into the past (JVM time, the clock the services compare with); returns the new row. */
    private Map<String, Object> backdateExpiry(String token) {
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice_token SET expires_at = ? WHERE token_hash = ?",
                        Timestamp.valueOf(LocalDateTime.now().minusDays(2)), sha256Hex(token)),
                "setup: backdate exactly one token");
        return tokenRow(token);
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return row("SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    /** The order's invoice versions above {@code version} (what a request appended). */
    private List<Integer> versionsAfter(long orderId, int version) {
        return jdbcTemplate.queryForList("SELECT version_number FROM invoice WHERE order_id = ? AND version_number > ? "
                + "ORDER BY version_number", Integer.class, orderId, version);
    }

    private int currentInvoiceVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "the order has a current invoice");
        return version;
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_status::text FROM sales_order WHERE order_id = ?",
                String.class, orderId);
    }

    private Map<String, Object> quoteVersionRow(long orderId, int versionNumber) {
        return row("SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private List<Map<String, Object>> quoteVersionRows(long orderId) {
        return rows("SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId);
    }

    private Map<String, Object> quoteTokenRow(String quoteToken) {
        return row("SELECT * FROM quote_token WHERE token_hash = ?", sha256Hex(quoteToken));
    }

    private List<Map<String, Object>> quoteTokenRows(long orderId) {
        return rows("SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                + "WHERE v.order_id = ? ORDER BY t.quote_token_id", orderId);
    }

    private Map<String, Object> storedFileRow(long storedFileId) {
        return row("SELECT * FROM stored_file WHERE stored_file_id = ?", storedFileId);
    }

    private Map<String, Object> row(String sql, Object... args) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap(sql, args));
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> found : jdbcTemplate.queryForList(sql, args)) {
            copies.add(new LinkedHashMap<>(found));
        }
        return copies;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ================================================================
    // Committed self-seeding (no test transaction; real flows)
    // ================================================================

    /**
     * The tenant, committed: an active business (ABN, bank, Stripe link, accent colour, soft terms) with the class
     * slug, one store with a notification email, two salespeople and their store access.
     */
    private Tenant seedTenant() {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number, "
                        + " stripe_payment_link_url, accent_colour, terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, ?, ?, ?, ?, ?, ?, ?, ?, NULL) RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, ABN, BANK_NAME, BSB, ACCOUNT_NAME, ACCOUNT_NUMBER, STRIPE_LINK,
                ACCENT_COLOUR, SOFT_TERMS);
        Assertions.assertNotNull(businessId, "setup: business insert returned no id");
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Lifecycle Link Showroom', ?, '0290004321', ?, '9 Link Lane', 'Parramatta', "
                        + " 'NSW', '2150') RETURNING store_id",
                Integer.class, businessId, STORE_CODE, STORE_EMAIL);
        Assertions.assertNotNull(storeId, "setup: store insert returned no id");
        Staff first = seedStaff(businessId, storeId, "Lena", "Link", FIRST_SALESPERSON_CODE,
                "lena.link.life@example.com");
        Staff second = seedStaff(businessId, storeId, "Luca", "Lifecycle", SECOND_SALESPERSON_CODE,
                "luca.link.life@example.com");
        orderSequence = 0;
        return new Tenant(businessId, storeId, first, second);
    }

    private Staff seedStaff(long businessId, int storeId, String firstName, String lastName, String code, String email) {
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'not-a-login-hash-pr4-link-life') RETURNING user_id",
                Long.class, businessId, firstName, lastName, code, email);
        Assertions.assertNotNull(userId, "setup: app_user insert returned no id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        return new Staff(userId, code);
    }

    /**
     * A committed order that passes every D.1 / quote / Path A gate, owned by {@value #FIRST_SALESPERSON_CODE}:
     * details of sale, proposed lay date 2026-12-01 CONFIRMED, the customer {@value #FROZEN_CUSTOMER_NAME} with a
     * PADDED valid email, INSTALLATION and BILLING addresses, one charge line of 100.00 ex / 40.00 cost (110.00 inc).
     */
    private Order seedInvoiceReadyOrder() {
        int sequence = ++orderSequence;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        String orderNumber = STORE_CODE + "." + FIRST_SALESPERSON_CODE + "." + String.format("%05d", sequence);
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 43, 2026, ?, "
                        + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, tenant.businessId(), tenant.storeId(), tenant.first().userId(), sequence, orderNumber,
                DETAILS_OF_SALE);
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
                        + "VALUES (?, 'BILLING'::address_type, '3', '21', 'Lifecycle Road', 'Parramatta', 'NSW', '2150')",
                orderId);
        String chargeCode = CHARGE_CODE_PREFIX + sequence;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Lifecycle supply and lay', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, tenant.storeId(), chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Lifecycle supply and lay', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        return new Order(orderId, orderNumber);
    }

    /** An invoice-ready order with its unsigned, unemailed D.1 invoice v1 (110.00 inc), created through the API. */
    private Order seedOrderWithInvoiceV1() throws Exception {
        Order order = seedInvoiceReadyOrder();
        createInvoice(order.orderId());
        Map<String, Object> v1 = invoiceRow(order.orderId(), 1);
        Assertions.assertNull(v1.get("accepted_at"), "setup: v1 is unsigned");
        assertMoney("110.00", v1.get("sale_price_inc_gst"), "setup: v1 inc");
        Assertions.assertEquals("3/21 Lifecycle Road", v1.get("customer_address_line1_snapshot"), "setup: billing 1");
        Assertions.assertNotNull(v1.get("terms_snapshot"), "setup: v1 froze the soft terms");
        Assertions.assertTrue(tokenRows(order.orderId()).isEmpty(), "setup: D.1 creates no link");
        return order;
    }

    // ================================================================
    // Committed cleanup (FK-safe rows, then files on disk)
    // ================================================================

    private void resetRecorders() {
        invoiceEmailSender.reset();
        invoiceLinkEmailSender.reset();
        invoiceNotificationSender.reset();
        quoteEmailSender.reset();
        quoteNotificationSender.reset();
        smsSender.reset();
    }

    /** Remove every committed row and file of this class's tenant (found by its slug), crashed runs included. */
    private void sweepTenant() {
        for (Long businessId : jdbcTemplate.queryForList(
                "SELECT business_id FROM business WHERE slug = ?", Long.class, SLUG)) {
            for (Long orderId : jdbcTemplate.queryForList(
                    "SELECT order_id FROM sales_order WHERE business_id = ? ORDER BY order_id", Long.class, businessId)) {
                deleteOrder(businessId, orderId);
            }
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
                jdbcTemplate.update("DELETE FROM store_charge WHERE store_id IN "
                        + "(SELECT store_id FROM store WHERE business_id = ?)", businessId);
                jdbcTemplate.update("DELETE FROM store_product WHERE store_id IN "
                        + "(SELECT store_id FROM store WHERE business_id = ?)", businessId);
                jdbcTemplate.update("DELETE FROM user_store_access WHERE business_id = ?", businessId);
                // fk_invoice_token_sent_by: any token one of these users sent goes before the users.
                jdbcTemplate.update("DELETE FROM invoice_token WHERE sent_by_user_id IN "
                        + "(SELECT user_id FROM app_user WHERE business_id = ?)", businessId);
                jdbcTemplate.update("DELETE FROM app_user WHERE business_id = ?", businessId);
                jdbcTemplate.update("DELETE FROM store WHERE business_id = ?", businessId);
                // Its FK to business has no ON DELETE: wipe it before the business row.
                jdbcTemplate.update("DELETE FROM business_quick_description WHERE business_id = ?", businessId);
                jdbcTemplate.update("DELETE FROM business WHERE business_id = ?", businessId);
            });
            deleteDirectoryQuietly(physicalPath("/uploads/" + businessId + "/"));
        }
    }

    /**
     * Delete one committed order and everything it owns in ONE transaction, FK-safe: its invoice tokens, quote
     * tokens, invoices (they may reference a quote_version and the quote's signature), quote versions (cascade
     * lines), the draft (cascade lines), payments, the other order children, the order, then its stored_file rows.
     * The files on disk are deleted only after that commit, then the order directory.
     */
    private void deleteOrder(long businessId, long orderId) {
        String prefix = orderStoragePrefix(businessId, orderId);
        List<String> storagePaths = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
            List<Map<String, Object>> files = jdbcTemplate.queryForList(ORDER_FILES_SQL,
                    orderId, orderId, orderId, orderId, orderId, orderId, prefix + "%");
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
            List<String> paths = new ArrayList<>();
            for (Map<String, Object> file : files) {
                jdbcTemplate.update("DELETE FROM stored_file WHERE stored_file_id = ?", file.get("stored_file_id"));
                paths.add((String) file.get("storage_path"));
            }
            return paths;
        });
        if (storagePaths != null) {
            storagePaths.forEach(fileStorageService::deleteQuietly);
        }
        deleteDirectoryQuietly(physicalPath(prefix));
    }

    /** Recursive delete of one directory strictly inside the storage root (never the root, never outside it). */
    private void deleteDirectoryQuietly(Path directory) {
        Path root = storageRoot();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || target.equals(root.resolve("uploads"))
                || !Files.exists(target)) {
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
    // Storage paths + names
    // ================================================================

    private Path storageRoot() {
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
    }

    /** Physical path = base-dir + storage_path (the FileStorageService resolution). */
    private Path physicalPath(String storagePath) {
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        return storageRoot().resolve(relative).normalize();
    }

    private static String orderStoragePrefix(long businessId, long orderId) {
        return "/uploads/" + businessId + "/orders/" + orderId + "/";
    }

    /** File names currently in the order's storage dir (empty when it does not exist). */
    private Set<String> filesOnDisk(long orderId) {
        Path directory = physicalPath(orderStoragePrefix(tenant.businessId(), orderId));
        if (!Files.isDirectory(directory)) {
            return new TreeSet<>();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String invoicePdfName(String orderNumber, int versionNumber) {
        return "invoice-" + orderNumber + "-v" + versionNumber + ".pdf";
    }

    // ================================================================
    // Small value helpers
    // ================================================================

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
        Assertions.assertNotNull(value, "expected a non-null id");
        return ((Number) value).longValue();
    }

    private static int intValue(Object value) {
        Assertions.assertNotNull(value, "expected a non-null number");
        return ((Number) value).intValue();
    }

    private static LocalDateTime timestamp(Map<String, Object> row, String column) {
        Assertions.assertTrue(row.containsKey(column), "unknown column " + column);
        Timestamp value = (Timestamp) row.get(column);
        return value == null ? null : value.toLocalDateTime();
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

    /** A token-shaped value (32 random bytes, unpadded URL-safe Base64) that no send minted. */
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ================================================================
    // Records
    // ================================================================

    /** The committed tenant: its business, store and two salespeople. */
    private record Tenant(long businessId, int storeId, Staff first, Staff second) {
    }

    private record Staff(long userId, String code) {
    }

    private record Order(long orderId, String orderNumber) {
    }

    /** One sent link: the plaintext token (from the recorded email), its row as minted and the 201's two timestamps. */
    private record SentLink(String token, long tokenId, Map<String, Object> row, String sentAtText,
                            String expiresAtText) {
    }

    /** A response and the JVM time window around its request. */
    private record Timed(MvcResult result, LocalDateTime start, LocalDateTime end) {
    }

    /** One invoice dead reason: the public state, its 410 code and its message (16G contract section 7.2). */
    private record DeadReason(String state, String code, String message) {
    }

    /** Every committed row of one order a link operation must not touch, plus its files on disk. */
    private record OrderState(Map<String, Object> salesOrder,
                              List<Map<String, Object>> invoices,
                              List<Map<String, Object>> storedFiles,
                              Set<String> filesOnDisk,
                              List<Map<String, Object>> payments,
                              List<Map<String, Object>> quoteVersions,
                              List<Map<String, Object>> quoteTokens) {
    }
}
