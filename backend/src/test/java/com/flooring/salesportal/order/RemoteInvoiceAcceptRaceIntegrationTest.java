package com.flooring.salesportal.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.image.SignaturePngNormaliser;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import com.flooring.salesportal.common.storage.FileStorageService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Phase 16G PR 4 (16G contract sections 5.2, 7.5, 7.6, 9.4 and 10; the PR 4 row of section 16: "holder-connection
 * interleavings in both lock orders for a double remote accept and for remote accept against D.8, payment, void,
 * Rewrite and Path A") - part A: the remote invoice accept ({@code POST /api/v1/public/invoices/{token}/accept})
 * raced, on the ORDER ROW LOCK, against the three other writers that end an invoice link - in-store D.8, Path A
 * ({@code POST .../quote/create-invoice}) and a manual Rewrite (D.2) - and against itself, in BOTH lock orders:
 * <ul>
 *   <li><b>Remote accept vs D.8.</b> Remote first: the remote signs v1 into v2 (the link SIGNED at the signing
 *       time, the ISSUED quote cancelled by D5(c)), and D.8, still carrying the v1 its client saw, is refused
 *       under the lock with 409 {@code INVOICE_VERSION_STALE}. D.8 first: D.8 signs v2 and kills the ACTIVE link
 *       as SIGNED in its own transaction, and the queued remote accept re-reads its token under the lock and gets
 *       410 {@code INVOICE_LINK_SIGNED} - no email and no store notification for it.</li>
 *   <li><b>Remote accept vs Path A</b> (the quote was signed BEFORE the race; the current invoice is unsigned).
 *       Remote first: the remote signs at a time strictly later than the quote signature, so the queued Path A
 *       is refused by the strict newer-signature rule D5(b): 409 {@code INVOICE_ALREADY_ACCEPTED} with the Path A
 *       wording. Path A first: Path A converts the accepted quote into a signed v2 and kills the link as SIGNED;
 *       the queued remote accept gets 410 {@code INVOICE_LINK_SIGNED}.</li>
 *   <li><b>Remote accept vs Rewrite.</b> Remote first: the remote signs v2; the queued Rewrite, still carrying the
 *       v1 its confirmation was bound to, is refused under the lock with 409 {@code INVOICE_VERSION_STALE} (Batch
 *       A PR 2, #149; before it the Rewrite appended an unsigned v3 over the signature) - the dead link stays
 *       SIGNED. Rewrite first: Rewrite (carrying v1) appends an unsigned v2 and kills the link as REWRITTEN; the
 *       queued remote accept gets 410 {@code INVOICE_LINK_REWRITTEN} from its token gate, before any version
 *       comparison.</li>
 *   <li><b>Double remote accept</b> with the same token and the same expected version: the queue head signs, the
 *       queued duplicate gets 410 {@code INVOICE_LINK_SIGNED}; exactly one appended version, one signature file,
 *       one PDF, one customer email and one store notification.</li>
 * </ul>
 * Every loser writes NOTHING (16G contract sections 5.2, 6.2, 9.4 and 10.6): after each race the committed state
 * is asserted to be EXACTLY the seed plus the winner's own documented writes - every invoice row (each version's
 * {@code last_emailed_at} included), every {@code stored_file} row of the order and every file under its storage
 * directory (no orphan either way), the {@code invoice_token} rows, the quote versions and tokens, the draft, the
 * customer / address / line rows, the payments and the {@code sales_order} row (its {@code last_emailed_at}
 * mirror included) - and the recorded messages are exactly the winner's: its signed-PDF email (the stored PDF of
 * the version it appended, to the TRIMMED saved email) and, for a remote winner only, its store notification. No
 * link email, quote email, quote notification or SMS is sent during a race, and neither service logs a WARN. The
 * final token status, the current version, its {@code accepted_at} and the mirror are asserted, and the dead link
 * is read back through the public GET (200, the minimal dead payload) and the staff D.3 read
 * ({@code invoice_link_expires_at} null: no ACTIVE link is left).
 *
 * <p><b>Who wrote what.</b> The link is sent by a second salesperson ({@value #SENDER_CODE}); every staff request
 * of the race (D.8, Path A, Rewrite) runs as {@value #STAFF_CODE}. A remotely signed version records the LINK
 * SENDER as {@code created_by_user_id} (16G contract section 10.2) and stores the SERVER-NORMALISED signature of
 * its upload (a 6 x 3 stroke, or 5 x 4 for the duplicate), while D.8 stores its 1 x 1 upload verbatim as the
 * session user - so the creator and the signature bytes identify the winner beyond doubt.
 *
 * <p><b>Fixtures - a fully self-seeded, COMMITTED tenant.</b> The requests run on worker threads with their own
 * pooled connections and real commits, so this class is NOT {@code @Transactional}: it inserts its own business
 * (slug {@value #SLUG}, ABN and bank details, no terms), store {@value #STORE_CODE} (with a notification email),
 * two salespeople and their store access, and each order (customer with a PADDED valid email, INSTALLATION +
 * BILLING, details of sale, lay date 2026-12-01 CONFIRMED, one 100.00 ex / 40.00 cost charge line) with
 * auto-commit SQL. Everything else goes through the real endpoints: D.1 create (then committed-dated on an earlier
 * day so a copied invoice date is visible), the quote draft PUT and protected send-email (the plaintext quote
 * token from the recorded email), the public quote accept, and the protected send-link (the plaintext invoice
 * token from the recorded link email, matched to its row by SHA-256). No V4 identity is used. It declares no
 * bean overrides, so it shares the cached Spring context of the other quote / invoice suites that write under
 * the same storage base dir.
 *
 * <p><b>Deterministic barrier (no sleeps as barriers, no production hooks).</b> The harness of
 * {@code InvoiceVersionPreconditionConcurrencyIntegrationTest}: a HOLDER connection takes
 * {@code SELECT ... FOR NO KEY UPDATE} on the order row; the first request is submitted and observed blocked
 * behind the holder, the second observed queued behind the FIRST (through the row's tuple lock), both through
 * {@code pg_stat_activity} + {@code pg_blocking_pids} with bounded polling; then the holder ROLLS BACK, so the
 * first request takes the row, commits or rolls back, and only then the second. The remote accept takes the lock
 * through {@code QuoteVersionRepository.lockOrderRowForPublicTransition}
 * ({@code SELECT order_id FROM sales_order WHERE order_id = :orderId FOR UPDATE}), which the probe matches. Every
 * request runs on its own MockMvc built with {@code springSecurity()} (the REAL filter chain), and every public
 * request goes through {@link #performPublic}, which asserts that no session exists and no {@code Set-Cookie} is
 * sent, whatever the status. The holder is always released and the worker pool always shut down
 * (try-with-resources {@link Race}); a straggler is cancelled only among the backends the race tracked.
 *
 * <p><b>Sweep.</b> {@code @BeforeEach} and {@code @AfterEach} remove everything the class commits, in FK-safe
 * order: per order {@code invoice_token} (before {@code sales_order} and {@code app_user}), quote_token, invoice,
 * quote_version, quote_draft, payment_transaction, the other order children, sales_order and the order's
 * stored_file rows (plus its files and directory); then the tenant's store_charge, store_product,
 * user_store_access, app_user, store, business_quick_description and business rows and its storage directory.
 * Every recording sender is reset in both.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class RemoteInvoiceAcceptRaceIntegrationTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before any file is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant (never a V4 identity). The slug tags everything this class commits. ----
    private static final String SLUG = "pr4-remote-race-a";
    private static final String BUSINESS_NAME = "Riverbend Remote Race Flooring";
    private static final String STORE_CODE = "IRA-ONE";
    private static final String STORE_EMAIL = "showroom.remote.race.a@example.com";
    /** Runs every staff request of the races (D.8, Path A, Rewrite) and the seed's D.1 / quote steps. */
    private static final String STAFF_CODE = "RA1";
    /** Sends the invoice links: the creator of every remotely signed version (16G contract section 10.2). */
    private static final String SENDER_CODE = "RA2";
    private static final String CHARGE_CODE_PREFIX = "IRARC";
    private static final String THREAD_NAME_PREFIX = "remote-invoice-race-a-";

    // ---- The seeded customer ----
    private static final String CUSTOMER_FIRST_NAME = "Morgan";
    private static final String CUSTOMER_LAST_NAME = "Remoteley";
    /** The D.1 freeze of the saved name: v1's customer_name_snapshot, i.e. every signing's accepted name. */
    private static final String CUSTOMER_NAME = CUSTOMER_FIRST_NAME + " " + CUSTOMER_LAST_NAME;
    private static final String CUSTOMER_EMAIL = "morgan.remoteley@example.com";
    /** Saved with edge whitespace (DB-valid, app-valid once trimmed): every recipient must be the trimmed value. */
    private static final String SAVED_CUSTOMER_EMAIL = "  " + CUSTOMER_EMAIL + " ";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String BILLING_LINE_1 = "12 Race Street";
    private static final String BILLING_LINE_2 = "Burwood NSW 2134";
    private static final String DETAILS_OF_SALE = "Supply and lay carpet (remote invoice accept race)";

    // ---- Dates and money ----
    /** v1 is committed-dated on an earlier day: a signing version COPIES it; Rewrite and Path A date today. */
    private static final LocalDate V1_INVOICE_DATE = LocalDate.of(2026, 9, 15);
    /** proposed_lay_date 2026-12-01 minus 2 days. */
    private static final LocalDate EXPECTED_DUE_DATE = LocalDate.of(2026, 11, 29);
    /** The unsigned D.1 v1: 100.00 ex + GST = 110.00 inc - the total a remote signing of v1 notifies. */
    private static final String V1_INC_GST = "110.00";
    private static final String V1_INC_GST_DISPLAY = "$110.00";

    // The accepted quote v1 (itemised 250.00 ex / 275.00 inc) and the CHANGED v2 issued after it (330.00 inc).
    private static final String ITEMISED_DRAFT = """
            {"itemised": true, "lines": [
              {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
              {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
            ]}""";
    private static final String CHANGED_NON_ITEMISED_DRAFT =
            "{\"itemised\": false, \"final_total_inc_gst\": 330.00, \"lines\": []}";

    // ---- Messages (exact) ----
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String INVOICE_REWRITTEN_MESSAGE = "Invoice rewritten.";
    private static final String INVOICE_LINK_SENT_MESSAGE = "Invoice link emailed to the customer.";
    private static final String REMOTE_ACCEPTED_MESSAGE = "Invoice accepted.";
    private static final String INVOICE_LOADED_MESSAGE = "Invoice loaded.";
    private static final String D8_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String PATH_A_EMAILED_MESSAGE =
            "Invoice created from accepted quote and emailed to the customer.";
    /** Path A's own wording for its D5(b) refusal (the code's default message is D.8's). */
    private static final String PATH_A_SIGNATURE_PRECEDENCE_MESSAGE =
            "The current invoice was signed at the same time or later than this quote. "
                    + "A newer signed quote is required to create an invoice from a quote.";
    private static final String INVOICE_VERSION_STALE_MESSAGE = "Invoice updated, please refresh";
    private static final String INVOICE_LINK_SIGNED_MESSAGE =
            "This invoice has already been signed. Please contact the store if you need a copy.";
    private static final String INVOICE_LINK_REWRITTEN_MESSAGE =
            "This invoice has been revised. Please contact the store for the updated invoice.";
    private static final String QUOTE_SENT_MESSAGE = "Quote sent by email.";
    private static final String QUOTE_ACCEPTED_MESSAGE = "Quote accepted.";

    // ---- Public links ----
    private static final String PUBLIC_BASE_URL = "http://localhost:5173";
    /** The public link inside the link-only invoice email: <app-base>/i/{43-char token}. */
    private static final Pattern INVOICE_LINK_PATTERN = Pattern.compile("/i/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    /** The public link inside the link-only quote email: <app-base>/q/{43-char token}. */
    private static final Pattern QUOTE_LINK_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    /** The 27 keys of PublicInvoiceView, always present (16G contract section 9.3). */
    private static final Set<String> PUBLIC_VIEW_KEYS = new TreeSet<>(List.of(
            "state", "business_name", "business_logo_url", "accent_color", "business_abn",
            "payment_account_name", "payment_bank_name", "payment_bsb", "payment_account_number",
            "payment_stripe_link_url", "order_number", "flooring_type", "version_number", "invoice_date",
            "due_date", "customer_name", "customer_address_line1", "customer_address_line2", "details_of_sale",
            "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst", "total_paid", "balance_due", "terms_html",
            "expires_at", "message"));

    // ---- Signatures: each writer uploads a DIFFERENT image, so the stored bytes name the winner ----
    /** A real, decodable 1x1 PNG: D.8 stores it verbatim; the quote accept signs with it. */
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    /** The remote accept's upload (6 x 3): stored as its server-normalised PNG. */
    private static final byte[] REMOTE_SIGNATURE_PNG = strokePng(6, 3);
    /** The duplicate remote accept's upload (5 x 4): it must never be stored. */
    private static final byte[] SECOND_REMOTE_SIGNATURE_PNG = strokePng(5, 4);

    // Exact money parsing for response bodies (never a double round-trip).
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    // InvoiceDetail / send-link timestamps are formatted to the second.
    private static final DateTimeFormatter JSON_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
    // The signed-PDF caption and the store notification format ("dd/MM/yyyy HH:mm").
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    // Barrier / harness bounds. The poll interval is NOT a barrier - the barrier is the DB condition.
    private static final long BARRIER_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 10L;
    private static final long RESPONSE_TIMEOUT_SECONDS = 30L;
    private static final long POOL_TERMINATION_SECONDS = 15L;

    /**
     * Backends of THIS database currently waiting on a heavyweight lock held (or queued ahead) by the given pid,
     * while executing an order-row lock statement: the JPA {@code ... from sales_order ... for no key update} of
     * D.8 / Path A / Rewrite, or the public accept's {@code SELECT order_id FROM sales_order ... FOR UPDATE}.
     * Runs auto-commit on the test thread, so every poll sees a fresh {@code pg_stat_activity}.
     */
    private static final String WAITER_PROBE_SQL = """
            SELECT a.pid
            FROM pg_stat_activity a
            WHERE a.datname = current_database()
              AND a.pid <> pg_backend_pid()
              AND a.wait_event_type = 'Lock'
              AND ? = ANY(pg_blocking_pids(a.pid))
              AND a.query ILIKE '%from sales_order%'
              AND a.query ILIKE '%for %update%'
            """;

    private static final String ACTIVITY_SQL = """
            SELECT pid, state, wait_event_type, wait_event,
                   pg_blocking_pids(pid)::text AS blocked_by, left(query, 160) AS query
            FROM pg_stat_activity
            WHERE datname = current_database() AND pid <> pg_backend_pid()
            """;

    // Every stored_file an order owns: invoice PDFs + signatures, quote issued / signed PDFs + signatures,
    // attachments - plus anything stored under the order's storage directory.
    private static final String ORDER_FILES_SQL = """
            SELECT stored_file_id, file_name, storage_path, mime_type, file_size
            FROM stored_file
            WHERE stored_file_id IN (
                      SELECT stored_file_id FROM invoice WHERE order_id = ?
                      UNION SELECT accepted_signature_file_id FROM invoice WHERE order_id = ?
                      UNION SELECT issued_pdf_file_id FROM quote_version WHERE order_id = ?
                      UNION SELECT signed_pdf_file_id FROM quote_version WHERE order_id = ?
                      UNION SELECT accepted_signature_file_id FROM quote_version WHERE order_id = ?
                      UNION SELECT stored_file_id FROM order_attachment WHERE order_id = ?)
               OR storage_path LIKE ?
            ORDER BY stored_file_id
            """;

    // Remaining FK children of sales_order (deleted after the token / quote / invoice layers; every child FK is
    // RESTRICT, the two *_line quote tables CASCADE from their parent).
    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "payment_transaction", "order_attachment", "order_note", "order_product_line",
            "order_charge_line", "order_address", "order_customer", "order_enquiry");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

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

    private MockMvc mockMvc;

    private TransactionTemplate transactionTemplate;

    // The committed tenant of the running test.
    private long businessId;
    private int storeId;
    private long staffUserId;
    private long senderUserId;
    private int orderSequence;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes this tenant's storage directory recursively - never a real storage dir.
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class stores and sweeps its files under the shared quote / invoice test storage dir");
        Assertions.assertTrue(storageRoot().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                () -> "unexpected storage root for a sweeping test class: " + storageRoot());
        mockMvc = securedMockMvc();
        transactionTemplate = new TransactionTemplate(transactionManager);
        resetSenders();
        // Leftovers of a crashed earlier run (committed rows + files) must never leak into this one.
        sweepTenant();
        seedTenant();
    }

    @AfterEach
    void tearDown() {
        try {
            sweepTenant();
        } finally {
            // Singletons: never leak recorded sends or an armed failure to another class.
            resetSenders();
        }
    }

    // ================================================================
    // 1. Remote accept vs D.8
    // ================================================================

    /**
     * The REMOTE ACCEPT locks first: it is observed waiting on the holder, then D.8 carrying the v1 its client saw
     * is observed queued BEHIND it; the holder rolls back. The remote accept takes the row, signs v1 into v2 (the
     * link SIGNED at the signing time, the ISSUED quote and its link cancelled by D5(c)), commits, then emails v2's
     * signed PDF and notifies the store. D.8 then takes the lock, reads the current invoice there (the remote's
     * v2) and refuses the v1 it carries: 409 {@code INVOICE_VERSION_STALE}. Nothing of D.8's is written - no
     * signature, PDF, version, email or marker - and its 1 x 1 upload is nowhere on disk.
     */
    @Test
    void remoteAcceptVsD8_remoteLocksFirst_remoteSignsV2AndKillsTheLinkSigned_d8CarryingV1Gets409InvoiceVersionStale_writesNothing()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        Map<String, Object> issuedQuote = quoteVersionRow(order.orderId(), 1);
        Map<String, Object> issuedQuoteToken = activeQuoteTokenRow(order.issuedQuoteVersionId());
        OrderSnapshot before = snapshot(order.orderId());

        RaceResults race = raceInOrder(order.orderId(),
                publicStep("remote accept (carrying v1)", remoteAccept(order.invoiceToken(), 1, REMOTE_SIGNATURE_PNG)),
                staffStep("D.8 (carrying v1)", invoiceAccept(order.orderId(), 1)));

        // The queue head signed; D.8 took the lock only after that commit and refused the v1 it carries.
        assertRemoteAccepted(race.first(), "remote accept (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "D.8 (carrying v1, queued behind the remote accept)");
        Assertions.assertEquals(INVOICE_VERSION_STALE_MESSAGE, ErrorCode.INVOICE_VERSION_STALE.defaultMessage(),
                "the stale message is the code's default");

        // The winner's writes: exactly v2, signed remotely (the link sender as its creator, the normalised 6 x 3
        // signature), stamped by its delivered email.
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "exactly v1 and the remote accept's v2 - no D.8 version");
        SignedVersion signed = assertSigningVersion(order.orderId(), order.orderNumber(), invoiceV1, 2, senderUserId,
                REMOTE_SIGNATURE_PNG, SignaturePngNormaliser.normalise(REMOTE_SIGNATURE_PNG), race,
                "the remote accept's v2");

        // The committed state is exactly the seed plus the remote accept's own writes - nothing of D.8's.
        assertSnapshot(before.withHeader(withColumns(before.header(), "last_emailed_at", signed.stamp()))
                        .withInvoices(append(before.invoices(), signed.row()))
                        .withStoredFiles(append(before.storedFiles(), signed.signatureFile(), signed.pdfFile()))
                        .withFilesOnDisk(plus(before.filesOnDisk(), signed.signaturePath(), signed.pdfPath()))
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "SIGNED", "dead_at", signed.acceptedAt())))
                        .withQuoteVersions(replaceRow(before.quoteVersions(), "quote_version_id",
                                withColumns(issuedQuote, "status", "CANCELLED")))
                        .withQuoteTokens(replaceRow(before.quoteTokens(), "quote_token_id",
                                withColumns(issuedQuoteToken, "status", "CANCELLED", "dead_at", signed.acceptedAt()))),
                snapshot(order.orderId()), "after the remote signing and the refused D.8");
        assertOnlySignatureImages(order.orderId(), List.of(signed.signatureFile()),
                "the refused D.8 stored no signature (its 1 x 1 upload)");

        // Messages: exactly the remote accept's signed-PDF email of v2 and its store notification.
        assertRaceMessages(order.orderId(), order.orderNumber(), signed, signed, "remote first vs D.8");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN: every post-commit step delivered");

        // Final: the link SIGNED (at the remote's signing time), v2 current and signed, the mirror = v2's stamp.
        assertFinalLink(order.orderId(), order.invoiceTokenId(), "SIGNED", signed.acceptedAt());
        assertCurrentVersionAndMirror(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
        assertPublicDeadView(order.invoiceToken(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE);
        assertStaffReadShowsNoLink(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
    }

    /**
     * D.8 locks first: D.8 carrying v1 is observed waiting on the holder, then the remote accept carrying v1 - its
     * token gate and body validation already passed, the link still ACTIVE - is observed queued BEHIND D.8; the
     * holder rolls back. D.8 signs v1 into v2 as the session user, kills the ACTIVE link as SIGNED at its signing
     * time and cancels the ISSUED quote (D5(c)) in its own transaction, commits and emails v2. The remote accept
     * then takes the lock, re-reads its token (SIGNED) and gets 410 {@code INVOICE_LINK_SIGNED}: nothing of the
     * remote's is written, and it sends neither a customer email nor a store notification.
     */
    @Test
    void remoteAcceptVsD8_d8LocksFirst_d8SignsV2AndKillsTheLinkSigned_remoteGets410InvoiceLinkSigned_writesNothingSendsNothing()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        Map<String, Object> issuedQuote = quoteVersionRow(order.orderId(), 1);
        Map<String, Object> issuedQuoteToken = activeQuoteTokenRow(order.issuedQuoteVersionId());
        OrderSnapshot before = snapshot(order.orderId());

        RaceResults race = raceInOrder(order.orderId(),
                staffStep("D.8 (carrying v1)", invoiceAccept(order.orderId(), 1)),
                publicStep("remote accept (carrying v1)", remoteAccept(order.invoiceToken(), 1, REMOTE_SIGNATURE_PNG)));

        // D.8 (queue head) signed v2 and emailed it; the remote accept re-read its token under the lock: SIGNED.
        assertStatus(race.first(), 201, "D.8 (queue head)");
        JsonNode d8Body = json(race.first());
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(d8Body), "D.8: envelope keys");
        Assertions.assertEquals(D8_EMAILED_MESSAGE, d8Body.path("message").asText(), "D.8: delivered message");
        assertRefusal(race.second(), 410, ErrorCode.INVOICE_LINK_SIGNED, INVOICE_LINK_SIGNED_MESSAGE,
                "remote accept (queued behind D.8)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "exactly v1 and D.8's v2 - no remote version");
        // D.8's v2: the SESSION user as its creator and its 1 x 1 upload stored verbatim (D.8 trusts the declared type).
        SignedVersion signed = assertSigningVersion(order.orderId(), order.orderNumber(), invoiceV1, 2, staffUserId,
                ONE_PIXEL_PNG, ONE_PIXEL_PNG, race, "D.8's v2");
        assertSignedInvoiceDetail(d8Body.path("data").path("invoice"), signed, CUSTOMER_NAME, "D.8 201");

        assertSnapshot(before.withHeader(withColumns(before.header(), "last_emailed_at", signed.stamp()))
                        .withInvoices(append(before.invoices(), signed.row()))
                        .withStoredFiles(append(before.storedFiles(), signed.signatureFile(), signed.pdfFile()))
                        .withFilesOnDisk(plus(before.filesOnDisk(), signed.signaturePath(), signed.pdfPath()))
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "SIGNED", "dead_at", signed.acceptedAt())))
                        .withQuoteVersions(replaceRow(before.quoteVersions(), "quote_version_id",
                                withColumns(issuedQuote, "status", "CANCELLED")))
                        .withQuoteTokens(replaceRow(before.quoteTokens(), "quote_token_id",
                                withColumns(issuedQuoteToken, "status", "CANCELLED", "dead_at", signed.acceptedAt()))),
                snapshot(order.orderId()), "after D.8's signing and the refused remote accept");
        assertOnlySignatureImages(order.orderId(), List.of(signed.signatureFile()),
                "the refused remote accept stored no signature (its 6 x 3 upload)");

        // Messages: exactly D.8's signed-PDF email of v2 - no store notification (D.8 sends none, the refused
        // remote accept never reached its post-commit steps).
        assertRaceMessages(order.orderId(), order.orderNumber(), signed, null, "D.8 first vs remote");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from either service");

        assertFinalLink(order.orderId(), order.invoiceTokenId(), "SIGNED", signed.acceptedAt());
        assertCurrentVersionAndMirror(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
        assertPublicDeadView(order.invoiceToken(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE);
        assertStaffReadShowsNoLink(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
    }

    // ================================================================
    // 2. Remote accept vs Path A (the quote signed BEFORE the race)
    // ================================================================

    /**
     * The REMOTE ACCEPT locks first: it is observed waiting on the holder, then Path A carrying the accepted quote
     * v1's id is observed queued BEHIND it; the holder rolls back. The remote accept signs the unsigned invoice v1
     * into v2 - at a time strictly LATER than the quote's signature, which was taken before the race - and its
     * D5(c) cancels the ISSUED quote v2 and its link (the ACCEPTED quote v1 is untouched). Path A then takes the
     * lock: its quote id still matches the latest ACCEPTED version, but the current invoice is now signed later
     * than the quote, so the strict rule D5(b) refuses it - 409 {@code INVOICE_ALREADY_ACCEPTED} with the Path A
     * wording. Nothing of Path A's is written.
     */
    @Test
    void remoteAcceptVsPathA_remoteLocksFirst_remoteSignsLaterThanTheQuote_pathAGets409InvoiceAlreadyAccepted_writesNothing()
            throws Exception {
        PathAOrder order = seedPathAOrder();
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        Map<String, Object> acceptedQuote = quoteVersionRow(order.orderId(), 1);
        Map<String, Object> issuedQuote = quoteVersionRow(order.orderId(), 2);
        Map<String, Object> issuedQuoteToken = activeQuoteTokenRow(order.issuedQuoteVersionId());
        Timestamp quoteSignedAt = (Timestamp) acceptedQuote.get("accepted_at");
        Assertions.assertNotNull(quoteSignedAt, "setup: the quote was signed before the race");
        OrderSnapshot before = snapshot(order.orderId());

        RaceResults race = raceInOrder(order.orderId(),
                publicStep("remote accept (carrying v1)", remoteAccept(order.invoiceToken(), 1, REMOTE_SIGNATURE_PNG)),
                staffStep("Path A (carrying quote v1)",
                        createInvoiceFromQuote(order.orderId(), order.acceptedQuoteVersionId())));

        assertRemoteAccepted(race.first(), "remote accept (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_ALREADY_ACCEPTED, PATH_A_SIGNATURE_PRECEDENCE_MESSAGE,
                "Path A (queued behind the remote accept)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "exactly v1 and the remote accept's v2 - no Path A version");
        SignedVersion signed = assertSigningVersion(order.orderId(), order.orderNumber(), invoiceV1, 2, senderUserId,
                REMOTE_SIGNATURE_PNG, SignaturePngNormaliser.normalise(REMOTE_SIGNATURE_PNG), race,
                "the remote accept's v2");
        Assertions.assertTrue(signed.acceptedAt().after(quoteSignedAt),
                "the remote signing (" + signed.acceptedAt() + ") is strictly later than the quote signature ("
                        + quoteSignedAt + ") - the D5(b) refusal of Path A");
        Assertions.assertNull(signed.row().get("source_quote_version_id"),
                "the remote signing of the Path B v1 carries no quote source");

        // D5(c) of the remote signing: the ISSUED v2 and its ACTIVE link CANCELLED at the signing time; the
        // ACCEPTED v1 and its CONSUMED link untouched. Path A never changes a quote row.
        assertSnapshot(before.withHeader(withColumns(before.header(), "last_emailed_at", signed.stamp()))
                        .withInvoices(append(before.invoices(), signed.row()))
                        .withStoredFiles(append(before.storedFiles(), signed.signatureFile(), signed.pdfFile()))
                        .withFilesOnDisk(plus(before.filesOnDisk(), signed.signaturePath(), signed.pdfPath()))
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "SIGNED", "dead_at", signed.acceptedAt())))
                        .withQuoteVersions(replaceRow(before.quoteVersions(), "quote_version_id",
                                withColumns(issuedQuote, "status", "CANCELLED")))
                        .withQuoteTokens(replaceRow(before.quoteTokens(), "quote_token_id",
                                withColumns(issuedQuoteToken, "status", "CANCELLED", "dead_at", signed.acceptedAt()))),
                snapshot(order.orderId()), "after the remote signing and the refused Path A");
        Assertions.assertEquals(acceptedQuote, quoteVersionRow(order.orderId(), 1),
                "the ACCEPTED quote v1 is never revoked by a remote invoice signing (D5(d))");
        Assertions.assertEquals(order.acceptedQuoteVersionId(), latestAcceptedQuoteVersionId(order.orderId()),
                "the accepted quote v1 is still the latest ACCEPTED version");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND file_name = ?",
                        orderStoragePrefix(order.orderId()) + "%", invoicePdfName(order.orderNumber(), 3)),
                "the refused Path A stored no invoice PDF");

        assertRaceMessages(order.orderId(), order.orderNumber(), signed, signed, "remote first vs Path A");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN: every post-commit step delivered");

        assertFinalLink(order.orderId(), order.invoiceTokenId(), "SIGNED", signed.acceptedAt());
        assertCurrentVersionAndMirror(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
        assertPublicDeadView(order.invoiceToken(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE);
        assertStaffReadShowsNoLink(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
    }

    /**
     * PATH A locks first: Path A carrying the accepted quote v1's id is observed waiting on the holder, then the
     * remote accept carrying v1 is observed queued BEHIND it; the holder rolls back. Path A converts the accepted
     * quote into invoice v2 (the quote's frozen snapshot and inherited signature; D5(b) allows it over the
     * unsigned v1), kills the ACTIVE invoice link as SIGNED in its own transaction - and, as ever, never touches a
     * quote row (the ISSUED quote v2 keeps its ACTIVE link) - commits and emails v2. The remote accept then takes
     * the lock, re-reads its token (SIGNED) and gets 410 {@code INVOICE_LINK_SIGNED}: nothing of the remote's is
     * written, no D5(c) runs, and it sends neither a customer email nor a store notification.
     */
    @Test
    void remoteAcceptVsPathA_pathALocksFirst_pathAConvertsAndKillsTheLinkSigned_remoteGets410InvoiceLinkSigned_writesNothingSendsNothing()
            throws Exception {
        PathAOrder order = seedPathAOrder();
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        Map<String, Object> acceptedQuote = quoteVersionRow(order.orderId(), 1);
        OrderSnapshot before = snapshot(order.orderId());
        LocalDate dayBefore = LocalDate.now();

        RaceResults race = raceInOrder(order.orderId(),
                staffStep("Path A (carrying quote v1)",
                        createInvoiceFromQuote(order.orderId(), order.acceptedQuoteVersionId())),
                publicStep("remote accept (carrying v1)", remoteAccept(order.invoiceToken(), 1, REMOTE_SIGNATURE_PNG)));
        LocalDate dayAfter = LocalDate.now();

        assertStatus(race.first(), 201, "Path A (queue head)");
        JsonNode pathABody = json(race.first());
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(pathABody), "Path A: envelope keys");
        Assertions.assertEquals(PATH_A_EMAILED_MESSAGE, pathABody.path("message").asText(),
                "Path A: the post-commit email of v2 was delivered");
        assertRefusal(race.second(), 410, ErrorCode.INVOICE_LINK_SIGNED, INVOICE_LINK_SIGNED_MESSAGE,
                "remote accept (queued behind Path A)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "exactly v1 and Path A's v2 - no remote version");
        SignedVersion converted = assertPathAVersion(order, invoiceV1, acceptedQuote, dayBefore, dayAfter, race);
        assertSignedInvoiceDetail(pathABody.path("data").path("invoice"), converted, CUSTOMER_NAME, "Path A 201");

        // The link died as SIGNED in Path A's transaction (at its own kill time, during the race).
        Map<String, Object> deadLink = invoiceTokenRow(order.invoiceTokenId());
        Timestamp killedAt = (Timestamp) deadLink.get("dead_at");
        Assertions.assertNotNull(killedAt, "Path A killed the ACTIVE link");
        assertWithinRace(killedAt, race, "Path A's link kill time");
        assertSnapshot(before.withHeader(withColumns(before.header(), "last_emailed_at", converted.stamp()))
                        .withInvoices(append(before.invoices(), converted.row()))
                        .withStoredFiles(append(before.storedFiles(), converted.pdfFile()))
                        .withFilesOnDisk(plus(before.filesOnDisk(), converted.pdfPath()))
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "SIGNED", "dead_at", killedAt))),
                snapshot(order.orderId()), "after Path A's conversion and the refused remote accept");
        // No new signature at all: v2 references the accepted quote's signature; the remote's upload is nowhere.
        assertOnlySignatureImages(order.orderId(),
                List.of(storedFileRow(longValue(acceptedQuote.get("accepted_signature_file_id")))),
                "the only signature image is the accepted quote's (Path A inherits it, the remote stored none)");

        assertRaceMessages(order.orderId(), order.orderNumber(), converted, null, "Path A first vs remote");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from either service");

        assertFinalLink(order.orderId(), order.invoiceTokenId(), "SIGNED", killedAt);
        assertCurrentVersionAndMirror(order.orderId(), 2, converted.acceptedAt(), converted.stamp());
        assertPublicDeadView(order.invoiceToken(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE);
        assertStaffReadShowsNoLink(order.orderId(), 2, converted.acceptedAt(), converted.stamp());
    }

    // ================================================================
    // 3. Remote accept vs Rewrite (D.2)
    // ================================================================

    /**
     * The REMOTE ACCEPT locks first: it is observed waiting on the holder, then a Rewrite carrying the v1 its
     * screen was bound to is observed queued BEHIND it; the holder rolls back. The remote accept signs v1 into v2
     * (the link SIGNED, D5(c) applied), commits, emails v2 and notifies the store. The Rewrite then takes the lock,
     * reads the current invoice there (the remote's signed v2) and refuses the v1 it carries: 409
     * {@code INVOICE_VERSION_STALE} (Batch A PR 2, #149). Nothing of the Rewrite's is written - no unsigned v3, no
     * PDF or stored_file, no mirror reset, no link kill - and it sends nothing, so the remote signature the
     * salesperson never saw is not cleared. (Before #149 this queued Rewrite appended an unsigned v3.)
     */
    @Test
    void remoteAcceptVsRewrite_remoteLocksFirst_remoteSignsV2_rewriteCarryingV1Gets409InvoiceVersionStale_writesNothing_signedLinkStaysSigned()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        Map<String, Object> issuedQuote = quoteVersionRow(order.orderId(), 1);
        Map<String, Object> issuedQuoteToken = activeQuoteTokenRow(order.issuedQuoteVersionId());
        OrderSnapshot before = snapshot(order.orderId());

        RaceResults race = raceInOrder(order.orderId(),
                publicStep("remote accept (carrying v1)", remoteAccept(order.invoiceToken(), 1, REMOTE_SIGNATURE_PNG)),
                staffStep("Rewrite (carrying v1)", rewriteInvoice(order.orderId(), 1)));

        // The queue head signed; the Rewrite took the lock only after that commit and refused the v1 it carries.
        assertRemoteAccepted(race.first(), "remote accept (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "Rewrite (carrying v1, queued behind the remote accept)");

        // The winner's writes only: exactly v2, signed remotely and stamped by its delivered email - no v3.
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "exactly v1 and the remote accept's v2 - no unsigned Rewrite version");
        SignedVersion signed = assertSigningVersion(order.orderId(), order.orderNumber(), invoiceV1, 2, senderUserId,
                REMOTE_SIGNATURE_PNG, SignaturePngNormaliser.normalise(REMOTE_SIGNATURE_PNG), race,
                "the remote accept's v2");

        // The committed state is exactly the seed plus the remote accept's own writes (its stamp moved the mirror
        // to v2's delivery time) - nothing of the refused Rewrite's.
        assertSnapshot(before.withHeader(withColumns(before.header(), "last_emailed_at", signed.stamp()))
                        .withInvoices(append(before.invoices(), signed.row()))
                        .withStoredFiles(append(before.storedFiles(), signed.signatureFile(), signed.pdfFile()))
                        .withFilesOnDisk(plus(before.filesOnDisk(), signed.signaturePath(), signed.pdfPath()))
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "SIGNED", "dead_at", signed.acceptedAt())))
                        .withQuoteVersions(replaceRow(before.quoteVersions(), "quote_version_id",
                                withColumns(issuedQuote, "status", "CANCELLED")))
                        .withQuoteTokens(replaceRow(before.quoteTokens(), "quote_token_id",
                                withColumns(issuedQuoteToken, "status", "CANCELLED", "dead_at", signed.acceptedAt()))),
                snapshot(order.orderId()), "after the remote signing and the refused Rewrite queued behind it");
        assertOnlySignatureImages(order.orderId(), List.of(signed.signatureFile()),
                "only the remote accept's signature image");

        // Messages: exactly the remote accept's email of v2 and its notification - the Rewrite sends nothing.
        assertRaceMessages(order.orderId(), order.orderNumber(), signed, signed, "remote first vs Rewrite");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from either service");

        // Final: the link stays SIGNED (a dead token never changes status, and the refused Rewrite killed nothing);
        // v2 current and signed; the mirror = v2's stamp.
        assertFinalLink(order.orderId(), order.invoiceTokenId(), "SIGNED", signed.acceptedAt());
        assertCurrentVersionAndMirror(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
        assertPublicDeadView(order.invoiceToken(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE);
        assertStaffReadShowsNoLink(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
    }

    /**
     * The REWRITE locks first: it is observed waiting on the holder, then the remote accept carrying v1 is
     * observed queued BEHIND it; the holder rolls back. The Rewrite appends the unsigned v2 from the live order and
     * kills the ACTIVE link as REWRITTEN in its own transaction, then commits (it emails nothing). The remote
     * accept then takes the lock, re-reads its token (REWRITTEN) and gets 410 {@code INVOICE_LINK_REWRITTEN}:
     * nothing of the remote's is written (the ISSUED quote keeps its ACTIVE link - no D5(c)) and nothing at all
     * is sent during the race.
     */
    @Test
    void remoteAcceptVsRewrite_rewriteLocksFirst_rewriteAppendsV2AndKillsTheLinkRewritten_remoteGets410InvoiceLinkRewritten_writesNothingSendsNothing()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(order.orderId());
        LocalDate dayBefore = LocalDate.now();

        RaceResults race = raceInOrder(order.orderId(),
                staffStep("Rewrite (carrying v1)", rewriteInvoice(order.orderId(), 1)),
                publicStep("remote accept (carrying v1)", remoteAccept(order.invoiceToken(), 1, REMOTE_SIGNATURE_PNG)));
        LocalDate dayAfter = LocalDate.now();

        assertStatus(race.first(), 201, "Rewrite (queue head)");
        JsonNode rewriteBody = json(race.first());
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(rewriteBody), "Rewrite: envelope keys");
        Assertions.assertEquals(INVOICE_REWRITTEN_MESSAGE, rewriteBody.path("message").asText(), "Rewrite: message");
        assertRefusal(race.second(), 410, ErrorCode.INVOICE_LINK_REWRITTEN, INVOICE_LINK_REWRITTEN_MESSAGE,
                "remote accept (queued behind the Rewrite)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "exactly v1 and the Rewrite's v2 - no remote version");
        RewrittenVersion rewritten = assertRewriteVersion(order, invoiceV1, 2, dayBefore, dayAfter);
        assertUnsignedInvoiceDetail(rewriteBody.path("data").path("invoice"), rewritten, "Rewrite 201");

        Map<String, Object> deadLink = invoiceTokenRow(order.invoiceTokenId());
        Timestamp killedAt = (Timestamp) deadLink.get("dead_at");
        Assertions.assertNotNull(killedAt, "the Rewrite killed the ACTIVE link");
        assertWithinRace(killedAt, race, "the Rewrite's link kill time");
        // The quote rows, the header (mirror null) and everything else as seeded: only the Rewrite's own writes.
        assertSnapshot(before.withInvoices(append(before.invoices(), rewritten.row()))
                        .withStoredFiles(append(before.storedFiles(), rewritten.pdfFile()))
                        .withFilesOnDisk(plus(before.filesOnDisk(), rewritten.pdfPath()))
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "REWRITTEN", "dead_at", killedAt))),
                snapshot(order.orderId()), "after the Rewrite and the refused remote accept");
        assertOnlySignatureImages(order.orderId(), List.of(), "the refused remote accept stored no signature");
        Assertions.assertEquals("ISSUED", quoteVersionRow(order.orderId(), 1).get("status"),
                "no D5(c): the ISSUED quote stays ISSUED");
        Assertions.assertNull(activeQuoteTokenRow(order.issuedQuoteVersionId()).get("dead_at"),
                "no D5(c): the quote link stays ACTIVE and live");

        assertRaceMessages(order.orderId(), order.orderNumber(), null, null, "Rewrite first vs remote");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from either service");

        assertFinalLink(order.orderId(), order.invoiceTokenId(), "REWRITTEN", killedAt);
        assertCurrentVersionAndMirror(order.orderId(), 2, null, null);
        assertPublicDeadView(order.invoiceToken(), "REWRITTEN", INVOICE_LINK_REWRITTEN_MESSAGE);
        assertStaffReadShowsNoLink(order.orderId(), 2, null, null);
    }

    // ================================================================
    // 4. Double remote accept (the same token, the same expected version)
    // ================================================================

    /**
     * Two remote accepts with the SAME token and the SAME expected version (v1), each with its own upload: both
     * pass the token gate and the body validation while the link is ACTIVE; the first is observed waiting on the
     * holder, the second queued BEHIND the first; the holder rolls back. The queue head signs v1 into v2 with ITS
     * signature (6 x 3), commits, emails and notifies; the duplicate then takes the lock, re-reads the token
     * (SIGNED) and gets 410 {@code INVOICE_LINK_SIGNED} before any stale comparison. Exactly one appended version,
     * one signature file (the head's, never the duplicate's 5 x 4), one PDF, one customer email and one store
     * notification.
     */
    @Test
    void doubleRemoteAccept_sameTokenAndVersion_firstSignsV2_secondGets410InvoiceLinkSigned_exactlyOneVersionSignaturePdfEmailAndNotification()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        Map<String, Object> issuedQuote = quoteVersionRow(order.orderId(), 1);
        Map<String, Object> issuedQuoteToken = activeQuoteTokenRow(order.issuedQuoteVersionId());
        OrderSnapshot before = snapshot(order.orderId());

        RaceResults race = raceInOrder(order.orderId(),
                publicStep("remote accept #1 (carrying v1)",
                        remoteAccept(order.invoiceToken(), 1, REMOTE_SIGNATURE_PNG)),
                publicStep("remote accept #2 (same token, carrying v1)",
                        remoteAccept(order.invoiceToken(), 1, SECOND_REMOTE_SIGNATURE_PNG)));

        assertRemoteAccepted(race.first(), "remote accept #1 (queue head)");
        assertRefusal(race.second(), 410, ErrorCode.INVOICE_LINK_SIGNED, INVOICE_LINK_SIGNED_MESSAGE,
                "remote accept #2 (the queued duplicate)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()), "exactly ONE appended version");
        SignedVersion signed = assertSigningVersion(order.orderId(), order.orderNumber(), invoiceV1, 2, senderUserId,
                REMOTE_SIGNATURE_PNG, SignaturePngNormaliser.normalise(REMOTE_SIGNATURE_PNG), race,
                "the queue head's v2");
        Assertions.assertFalse(java.util.Arrays.equals(SignaturePngNormaliser.normalise(SECOND_REMOTE_SIGNATURE_PNG),
                        fileStorageService.read(signed.signaturePath())),
                "the stored signature is never the duplicate's upload");

        assertSnapshot(before.withHeader(withColumns(before.header(), "last_emailed_at", signed.stamp()))
                        .withInvoices(append(before.invoices(), signed.row()))
                        .withStoredFiles(append(before.storedFiles(), signed.signatureFile(), signed.pdfFile()))
                        .withFilesOnDisk(plus(before.filesOnDisk(), signed.signaturePath(), signed.pdfPath()))
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "SIGNED", "dead_at", signed.acceptedAt())))
                        .withQuoteVersions(replaceRow(before.quoteVersions(), "quote_version_id",
                                withColumns(issuedQuote, "status", "CANCELLED")))
                        .withQuoteTokens(replaceRow(before.quoteTokens(), "quote_token_id",
                                withColumns(issuedQuoteToken, "status", "CANCELLED", "dead_at", signed.acceptedAt()))),
                snapshot(order.orderId()), "after the head's signing and the refused duplicate");
        assertOnlySignatureImages(order.orderId(), List.of(signed.signatureFile()), "exactly ONE signature file");
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND file_name = ?",
                        orderStoragePrefix(order.orderId()) + "%", invoicePdfName(order.orderNumber(), 2)),
                "exactly ONE v2 PDF");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND file_name = ?",
                        orderStoragePrefix(order.orderId()) + "%", invoicePdfName(order.orderNumber(), 3)),
                "no v3 PDF");

        // Exactly one customer email and one store notification, both for the head's v2.
        assertRaceMessages(order.orderId(), order.orderNumber(), signed, signed, "double remote accept");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN: the duplicate never reached a send");

        assertFinalLink(order.orderId(), order.invoiceTokenId(), "SIGNED", signed.acceptedAt());
        assertCurrentVersionAndMirror(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
        assertPublicDeadView(order.invoiceToken(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE);
        assertStaffReadShowsNoLink(order.orderId(), 2, signed.acceptedAt(), signed.stamp());
    }

    // ================================================================
    // Race harness (copied from InvoiceVersionPreconditionConcurrencyIntegrationTest; secured workers, public
    // requests through performPublic)
    // ================================================================

    /** One raced request: its label, its request, and whether it is a public (token-only) request. */
    private record RaceStep(String label, RequestBuilder request, boolean isPublic) {
    }

    private static RaceStep publicStep(String label, RequestBuilder request) {
        return new RaceStep(label, request, true);
    }

    private static RaceStep staffStep(String label, RequestBuilder request) {
        return new RaceStep(label, request, false);
    }

    /** The two responses (queue head first), the race window (JVM clock) and every WARN+ the services logged. */
    private record RaceResults(MvcResult first, MvcResult second, LocalDateTime startedAt, LocalDateTime finishedAt,
                               List<String> serviceWarnings) {
    }

    /**
     * Run one interleaving in a fixed lock order: {@code first} is observed waiting on the holder's row lock,
     * {@code second} is observed queued BEHIND {@code first} (the row's tuple lock), the queue order is proven,
     * and only then the holder rolls back (it wrote nothing), so {@code first} takes the row - and commits or
     * rolls back - before {@code second} can. Both responses are awaited (a response is produced only after the
     * request's post-commit email and notification), and the WARN+ events of both invoice services are captured
     * for the whole race.
     */
    private RaceResults raceInOrder(long orderId, RaceStep first, RaceStep second) throws Exception {
        LocalDateTime startedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        MvcResult firstResult;
        MvcResult secondResult;
        try (ServiceLogs logs = new ServiceLogs(PublicInvoiceService.class, OrderInvoiceService.class)) {
            try (Race race = new Race(orderId)) {
                Future<MvcResult> firstFuture = race.submit(first);
                int firstPid = awaitWaiterBlockedBy(race, race.holderPid(), first.label(), firstFuture);
                Future<MvcResult> secondFuture = race.submit(second);
                int secondPid = awaitWaiterBlockedBy(race, firstPid, second.label(), secondFuture, firstFuture);
                assertQueuedInOrder(race.holderPid(), firstPid, secondPid, firstFuture, secondFuture);

                race.rollbackHolder(); // the holder wrote nothing: the first request takes the row first

                firstResult = awaitResponse(firstFuture, first.label());
                secondResult = awaitResponse(secondFuture, second.label());
            }
            return new RaceResults(firstResult, secondResult, startedAt, LocalDateTime.now(), logs.warningsAndErrors());
        }
    }

    /**
     * One deterministic race over an order row: a holder transaction (its own pooled connection,
     * {@code autoCommit=false}, {@code SET LOCAL lock_timeout} so a lock-order bug errors instead of hanging)
     * holding {@code FOR NO KEY UPDATE} on the order, plus a small executor for the competing requests, each on
     * its own MockMvc with the real security filter chain. {@link #close()} - always reached via
     * try-with-resources - rolls back and closes a still-open holder, lets in-flight requests finish, and as a
     * last resort cancels a straggler, but only one of the request backends this race {@linkplain #track tracked}.
     */
    private final class Race implements AutoCloseable {

        private final Connection holder;
        private final int holderPid;
        private final ExecutorService workers;
        // Every backend awaitWaiterBlockedBy observed queued on this race's order row: the ONLY pids the
        // last-resort cancel may target (never another lock-waiting backend of the database).
        private final Set<Integer> trackedPids = new TreeSet<>();
        private boolean holderOpen;

        Race(long orderId) throws SQLException {
            Connection connection = dataSource.getConnection();
            int pid;
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    try (ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
                        if (!rs.next()) {
                            throw new IllegalStateException("pg_backend_pid() returned no row");
                        }
                        pid = rs.getInt(1);
                    }
                    statement.execute("SET LOCAL lock_timeout = '5s'");
                }
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT order_id FROM sales_order WHERE order_id = ? FOR NO KEY UPDATE")) {
                    lock.setLong(1, orderId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("holder found no sales_order row " + orderId);
                        }
                    }
                }
            } catch (SQLException | RuntimeException e) {
                discard(connection);
                throw e;
            }
            this.holder = connection;
            this.holderPid = pid;
            this.holderOpen = true;
            this.workers = Executors.newFixedThreadPool(2, raceThreadFactory());
        }

        int holderPid() {
            return holderPid;
        }

        /** Record a request backend observed queued on this race's order row (a last-resort cancel target). */
        void track(int pid) {
            trackedPids.add(pid);
        }

        /**
         * Run one request on a worker thread with its OWN MockMvc (real Spring Security filter chain); a public
         * request goes through {@link #performPublic} (no session, no Set-Cookie). The future yields the response.
         */
        Future<MvcResult> submit(RaceStep step) {
            MockMvc workerMockMvc = securedMockMvc();
            Callable<MvcResult> call = step.isPublic()
                    ? () -> performPublic(workerMockMvc, step.request())
                    : () -> workerMockMvc.perform(step.request()).andReturn();
            return workers.submit(call);
        }

        /** Release the order row without publishing anything. */
        void rollbackHolder() throws SQLException {
            holder.rollback();
            releaseHolder();
        }

        private void releaseHolder() {
            holderOpen = false;
            try {
                holder.setAutoCommit(true);
            } catch (SQLException ignored) {
                // the pool resets auto-commit on return anyway
            }
            try {
                holder.close();
            } catch (SQLException ignored) {
                // best effort - the transaction already ended
            }
        }

        @Override
        public void close() {
            try {
                if (holderOpen) {
                    holderOpen = false;
                    discard(holder); // never leave a holder transaction open
                }
            } finally {
                // Let in-flight requests finish now that the row is free; never hang the build.
                workers.shutdown();
                try {
                    if (!workers.awaitTermination(POOL_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                        // Last resort: a request still parked on an order lock after the holder is gone.
                        cancelOrderLockWaiters(trackedPids);
                        workers.shutdownNow();
                        workers.awaitTermination(POOL_TERMINATION_SECONDS, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelOrderLockWaiters(trackedPids);
                    workers.shutdownNow();
                }
            }
        }
    }

    /**
     * Poll (every ~{@value #POLL_INTERVAL_MILLIS}ms, up to {@value #BARRIER_TIMEOUT_MILLIS}ms) until the
     * request's backend is observed waiting on the order-row lock behind {@code blockerPid}; returns that
     * backend's pid. Fails fast if the request (or an earlier queued one) completes first - a request that
     * never waited never serialised on the order lock (a public request refused before the lock, at its token
     * gate or body validation, never queues). Every backend observed queued behind {@code blockerPid} is
     * tracked on {@code race}, so its last-resort cancel can reach it (and only it).
     */
    private int awaitWaiterBlockedBy(Race race, int blockerPid, String label, Future<MvcResult> waiter,
                                     Future<?>... mustStillBeWaiting) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BARRIER_TIMEOUT_MILLIS);
        while (true) {
            if (waiter.isDone()) {
                throw new AssertionError(label + " completed before it was ever observed waiting on the order "
                        + "row lock behind pid " + blockerPid + " (it never serialised on the lock): "
                        + describe(waiter) + "; activity: " + activity());
            }
            for (Future<?> earlier : mustStillBeWaiting) {
                if (earlier.isDone()) {
                    throw new AssertionError("an earlier queued request completed while the order row was still "
                            + "held by the holder: " + describe(earlier) + "; activity: " + activity());
                }
            }
            List<Integer> waiters = jdbcTemplate.queryForList(WAITER_PROBE_SQL, Integer.class, blockerPid);
            waiters.forEach(race::track);
            if (waiters.size() > 1) {
                throw new AssertionError("expected exactly one backend queued behind pid " + blockerPid
                        + " but saw " + waiters + "; activity: " + activity());
            }
            if (waiters.size() == 1) {
                return waiters.get(0);
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(label + " was not observed blocked on the order row lock behind pid "
                        + blockerPid + " within " + BARRIER_TIMEOUT_MILLIS + "ms; activity: " + activity());
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
    }

    /**
     * The queue-order proof taken right before the holder is released: the first request waits on the
     * holder, the second is queued BEHIND THE FIRST (the row's tuple lock), so the first is guaranteed to take
     * the row lock - and commit or roll back - before the second can.
     */
    private void assertQueuedInOrder(int holderPid, int firstPid, int secondPid,
                                     Future<MvcResult> first, Future<MvcResult> second) {
        Assertions.assertNotEquals(firstPid, secondPid, "the two requests must run on distinct backends");
        Assertions.assertNotEquals(holderPid, firstPid, "a request must never run on the holder connection");
        Assertions.assertNotEquals(holderPid, secondPid, "a request must never run on the holder connection");
        Assertions.assertTrue(isBlockedBy(firstPid, holderPid),
                "the first request must be waiting on the holder; activity: " + activity());
        Assertions.assertTrue(isBlockedBy(secondPid, firstPid),
                "the second request must be queued behind the first request; activity: " + activity());
        Assertions.assertFalse(first.isDone(), "the first request must still be parked on the order lock");
        Assertions.assertFalse(second.isDone(), "the second request must still be parked on the order lock");
    }

    private boolean isBlockedBy(int waiterPid, int blockerPid) {
        Boolean blocked = jdbcTemplate.queryForObject(
                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blockerPid, waiterPid);
        return Boolean.TRUE.equals(blocked);
    }

    private MvcResult awaitResponse(Future<MvcResult> future, String label) throws InterruptedException {
        try {
            return future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(label + " did not respond within " + RESPONSE_TIMEOUT_SECONDS
                    + "s after the order row was released; activity: " + activity(), e);
        } catch (ExecutionException e) {
            throw new AssertionError(label + " threw instead of producing an HTTP response", e.getCause());
        }
    }

    private String activity() {
        try {
            return jdbcTemplate.queryForList(ACTIVITY_SQL).toString();
        } catch (RuntimeException e) {
            return "<pg_stat_activity unavailable: " + e.getMessage() + ">";
        }
    }

    private static String describe(Future<?> future) {
        if (!future.isDone()) {
            return "still running";
        }
        try {
            Object outcome = future.get();
            if (outcome instanceof MvcResult result) {
                return "HTTP " + result.getResponse().getStatus() + " " + body(result);
            }
            return String.valueOf(outcome);
        } catch (ExecutionException e) {
            return "threw " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "<interrupted while describing>";
        } catch (RuntimeException e) {
            return "<" + e + ">";
        }
    }

    /**
     * Last resort only: cancel the race's TRACKED request backends that are still parked on an order-row
     * lock in this database - never any other backend. A pooled backend outlives its request, so a tracked
     * pid is skipped once it no longer waits on such a lock, and this backend is never cancelled.
     */
    private void cancelOrderLockWaiters(Set<Integer> trackedPids) {
        if (trackedPids.isEmpty()) {
            return;
        }
        String pidArray = trackedPids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        try {
            jdbcTemplate.queryForList(
                    "SELECT pg_cancel_backend(a.pid) FROM pg_stat_activity a "
                            + "WHERE a.pid = ANY(?::int[]) "
                            + "AND a.datname = current_database() AND a.pid <> pg_backend_pid() "
                            + "AND a.wait_event_type = 'Lock' AND a.query ILIKE '%sales_order%'",
                    Boolean.class, pidArray);
        } catch (RuntimeException ignored) {
            // never mask the original failure
        }
    }

    private static void discard(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best effort
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // best effort
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private static ThreadFactory raceThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Captures every event of the given service loggers while open (the race's worker threads included - the
     * appender is attached to the logger, not to a thread). The list is read only after the workers finished
     * (each response is awaited through its future, which publishes everything the request logged).
     */
    private static final class ServiceLogs implements AutoCloseable {

        private final List<ch.qos.logback.classic.Logger> loggers = new ArrayList<>();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        ServiceLogs(Class<?>... sources) {
            appender.start();
            for (Class<?> source : sources) {
                ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(source);
                logger.addAppender(appender);
                loggers.add(logger);
            }
        }

        /** Every captured WARN or ERROR, as "LEVEL logger: message". */
        List<String> warningsAndErrors() {
            synchronized (appender) {
                return appender.list.stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                        .map(event -> event.getLevel() + " " + event.getLoggerName() + ": " + event.getFormattedMessage())
                        .toList();
            }
        }

        @Override
        public void close() {
            for (ch.qos.logback.classic.Logger logger : loggers) {
                logger.detachAppender(appender);
            }
            appender.stop();
        }
    }

    // ================================================================
    // Requests
    // ================================================================

    /** A MockMvc on the REAL Spring Security filter chain (every request of this class uses one). */
    private MockMvc securedMockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * THE one way this class performs a public (token-only) request: never with a session, and whatever the
     * status (200, 201, 404, 409, 410, ...), the request must end without any session and the response without a
     * {@code Set-Cookie} - the public surface never reads, creates or populates the HttpSession.
     */
    private static MvcResult performPublic(MockMvc mvc, RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        Assertions.assertNull(result.getRequest().getSession(false),
                () -> "a public request (HTTP " + status + ") must never carry or create a session");
        Assertions.assertTrue(result.getResponse().getHeaders("Set-Cookie").isEmpty(),
                () -> "a public response (HTTP " + status + ") must never set a cookie: "
                        + result.getResponse().getHeaders("Set-Cookie"));
        return result;
    }

    private MockHttpSession session(long userId) {
        MockHttpSession session = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        session.setAttribute("user_id", Long.valueOf(userId));
        session.setAttribute("business_id", Long.valueOf(businessId));
        session.setAttribute("store_id", Integer.valueOf(storeId));
        return session;
    }

    private String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    private static MockMultipartFile signaturePart(byte[] png) {
        return new MockMultipartFile("signature", "signature.png", "image/png", png);
    }

    /**
     * The public remote accept exactly as the page sends it: the {@code expected_version_number} FORM field and
     * one PNG {@code signature} part; no session, no slug, no query string.
     */
    private static RequestBuilder remoteAccept(String token, int expectedVersionNumber, byte[] png) {
        return multipart("/api/v1/public/invoices/" + token + "/accept")
                .file(signaturePart(png))
                .param("expected_version_number", String.valueOf(expectedVersionNumber));
    }

    /** D.8 as the Invoice tab sends it (16G PR 3): the signature + the mandatory expected version; no name part. */
    private RequestBuilder invoiceAccept(long orderId, int expectedVersionNumber) {
        return multipart(orderUrl(orderId) + "/invoices/current/accept")
                .file(signaturePart(ONE_PIXEL_PNG))
                .param("expected_version_number", String.valueOf(expectedVersionNumber))
                .session(session(staffUserId));
    }

    /** Path A (16G PR 3): body {@code {"quote_version_id": N}} = the latest accepted version the client saw. */
    private RequestBuilder createInvoiceFromQuote(long orderId, long seenAcceptedQuoteVersionId) {
        return post(orderUrl(orderId) + "/quote/create-invoice").session(session(staffUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quote_version_id\": " + seenAcceptedQuoteVersionId + "}");
    }

    /**
     * D.2 manual Rewrite (Batch A PR 2, #149): body {@code {"expected_version_number": N}} = the current invoice
     * version the client's confirmation was bound to.
     */
    private RequestBuilder rewriteInvoice(long orderId, int seenVersion) {
        return post(orderUrl(orderId) + "/invoices/rewrite").session(session(staffUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_version_number\": " + seenVersion + "}");
    }

    // ================================================================
    // Committed self-seeding (no test transaction; real flows)
    // ================================================================

    /**
     * The tenant, committed: an active business (ABN + bank details, no terms) with the class slug, one store
     * (with a notification email), two salespeople - {@value #STAFF_CODE} (the race's staff requests) and
     * {@value #SENDER_CODE} (the link sender) - and their store access.
     */
    private void seedTenant() {
        Long business = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number) "
                        + "VALUES (?, ?, TRUE, '53 004 085 616', 'Riverbend Race Bank', '062-111', ?, '87654321') "
                        + "RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, BUSINESS_NAME + " Pty Ltd");
        Assertions.assertNotNull(business, "setup: business insert returned no id");
        businessId = business;
        Integer store = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Riverbend Race Showroom', ?, '0290005678', ?, '5 Remote Way', 'Burwood', 'NSW', "
                        + " '2134') RETURNING store_id",
                Integer.class, businessId, STORE_CODE, STORE_EMAIL);
        Assertions.assertNotNull(store, "setup: store insert returned no id");
        storeId = store;
        staffUserId = insertUser("Rory", "Staffer", STAFF_CODE, "rory.staffer.remote.race.a@example.com");
        senderUserId = insertUser("Sasha", "Sender", SENDER_CODE, "sasha.sender.remote.race.a@example.com");
        Assertions.assertNotEquals(staffUserId, senderUserId, "setup: two distinct salespeople");
        orderSequence = 0;
    }

    private long insertUser(String firstName, String lastName, String salespersonCode, String email) {
        Long user = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'not-a-login-hash-remote-race-a') RETURNING user_id",
                Long.class, businessId, firstName, lastName, salespersonCode, email);
        Assertions.assertNotNull(user, "setup: app_user insert returned no id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, user, storeId);
        return user;
    }

    /** One committed order of the tenant. */
    private record SeededOrder(long orderId, String orderNumber) {
    }

    /**
     * An order whose UNSIGNED D.1 invoice v1 has an ACTIVE invoice link (sent by {@value #SENDER_CODE}), plus an
     * ISSUED quote v1 with its ACTIVE link (the D5(c) target of a signing).
     */
    private record LinkedOrder(long orderId, String orderNumber, long issuedQuoteVersionId, String invoiceToken,
                               long invoiceTokenId) {
    }

    /**
     * A Path A order: the UNSIGNED D.1 invoice v1 with an ACTIVE invoice link, the publicly ACCEPTED quote v1
     * (signed before any race) and the newer ISSUED quote v2 with its ACTIVE link.
     */
    private record PathAOrder(long orderId, String orderNumber, long acceptedQuoteVersionId,
                              long issuedQuoteVersionId, String invoiceToken, long invoiceTokenId) {
    }

    /** A sent invoice link: the plaintext token (from the recorded email) and its row id. */
    private record SentLink(String token, long invoiceTokenId) {
    }

    /**
     * A committed order that passes every D.1 / quote / Path A gate: details of sale, proposed lay date
     * 2026-12-01 CONFIRMED, the customer {@value #CUSTOMER_NAME} with a PADDED valid email, INSTALLATION and
     * BILLING addresses, and one charge line of 100.00 ex / 40.00 cost (110.00 inc).
     */
    private SeededOrder seedInvoiceReadyOrder() {
        int sequence = ++orderSequence;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        String orderNumber = STORE_CODE + "." + STAFF_CODE + "." + String.format("%05d", sequence);
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 42, 2026, ?, "
                        + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, businessId, storeId, staffUserId, sequence, orderNumber, DETAILS_OF_SALE);
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
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Race Street', 'Burwood', 'NSW', '2134')",
                orderId);
        String chargeCode = CHARGE_CODE_PREFIX + sequence;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Remote race supply and lay', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, storeId, chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Remote race supply and lay', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        return new SeededOrder(orderId, orderNumber);
    }

    private LinkedOrder seedLinkedOrder() throws Exception {
        SeededOrder seeded = seedInvoiceReadyOrder();
        long orderId = seeded.orderId();
        createUnsignedInvoiceV1(orderId);
        saveDraft(orderId, ITEMISED_DRAFT);
        sendQuoteAndExtractToken(orderId, 1);
        Map<String, Object> issued = quoteVersionRow(orderId, 1);
        Assertions.assertEquals("ISSUED", issued.get("status"), "setup: quote v1 is ISSUED");
        long issuedId = longValue(issued.get("quote_version_id"));
        Assertions.assertNull(activeQuoteTokenRow(issuedId).get("dead_at"), "setup: quote v1 has an ACTIVE link");
        SentLink link = sendInvoiceLink(seeded);
        assertNoEmailMarkers(orderId, "setup");
        // Every send so far belongs to the seed: the race starts with empty recorders.
        resetSenders();
        return new LinkedOrder(orderId, seeded.orderNumber(), issuedId, link.token(), link.invoiceTokenId());
    }

    private PathAOrder seedPathAOrder() throws Exception {
        SeededOrder seeded = seedInvoiceReadyOrder();
        long orderId = seeded.orderId();
        // An unsigned Path B v1: D5(b) allows Path A over an unsigned current invoice.
        createUnsignedInvoiceV1(orderId);
        // The REAL quote chain: draft -> protected send-email -> token from the recorded email -> public accept.
        saveDraft(orderId, ITEMISED_DRAFT);
        acceptQuotePublicly(sendQuoteAndExtractToken(orderId, 1));
        Map<String, Object> accepted = quoteVersionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", accepted.get("status"), "setup: quote v1 accepted publicly");
        Assertions.assertEquals(CUSTOMER_NAME, accepted.get("accepted_customer_name"), "setup: the V17 name");
        Assertions.assertNotNull(accepted.get("accepted_signature_file_id"), "setup: quote v1's stored signature");
        // Decision D9: sending after an acceptance issues a NEW version; the draft is CHANGED too.
        saveDraft(orderId, CHANGED_NON_ITEMISED_DRAFT);
        sendQuoteAndExtractToken(orderId, 2);
        Map<String, Object> issued = quoteVersionRow(orderId, 2);
        Assertions.assertEquals("ISSUED", issued.get("status"), "setup: quote v2 is ISSUED");
        long issuedId = longValue(issued.get("quote_version_id"));
        Assertions.assertNull(activeQuoteTokenRow(issuedId).get("dead_at"), "setup: quote v2 has an ACTIVE link");
        Assertions.assertEquals(longValue(accepted.get("quote_version_id")), latestAcceptedQuoteVersionId(orderId),
                "setup: the latest ACCEPTED quote version is v1");
        // The invoice link last: quote acceptance and quote sends never touch invoice links anyway.
        SentLink link = sendInvoiceLink(seeded);
        assertNoEmailMarkers(orderId, "setup");
        resetSenders();
        return new PathAOrder(orderId, seeded.orderNumber(), longValue(accepted.get("quote_version_id")), issuedId,
                link.token(), link.invoiceTokenId());
    }

    /**
     * D.1 Create through the API: the unsigned, unemailed Path B invoice v1 (110.00 inc, the frozen Invoice To,
     * no terms), then committed-dated {@link #V1_INVOICE_DATE} so a signing version's COPIED date is visible.
     */
    private void createUnsignedInvoiceV1(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(session(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: invoice create (D.1)");
        JsonNode body = json(result);
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, body.path("message").asText(), "setup: D.1 message");
        JsonNode created = body.path("data").path("invoice");
        Assertions.assertTrue(created.has("invoice_link_expires_at") && created.get("invoice_link_expires_at").isNull(),
                "setup: D.1's InvoiceDetail carries invoice_link_expires_at = null (no link can exist yet)");
        Assertions.assertEquals(List.of(1), invoiceVersions(orderId), "setup: exactly the D.1 invoice v1");
        Map<String, Object> invoice = invoiceRow(orderId, 1);
        Assertions.assertNull(invoice.get("accepted_at"), "setup: invoice v1 is unsigned");
        assertMoney(V1_INC_GST, invoice.get("sale_price_inc_gst"), "setup: invoice v1 inc");
        Assertions.assertEquals(CUSTOMER_NAME, invoice.get("customer_name_snapshot"), "setup: v1's frozen name");
        Assertions.assertEquals(BILLING_LINE_1, invoice.get("customer_address_line1_snapshot"), "setup: v1 line 1");
        Assertions.assertEquals(BILLING_LINE_2, invoice.get("customer_address_line2_snapshot"), "setup: v1 line 2");
        Assertions.assertNull(invoice.get("terms_snapshot"), "setup: the business has no terms");
        Assertions.assertEquals(staffUserId, longValue(invoice.get("created_by_user_id")), "setup: v1's creator");
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice SET invoice_date = ? WHERE invoice_id = ?",
                Date.valueOf(V1_INVOICE_DATE), invoice.get("invoice_id")), "setup: date v1 on an earlier day");
    }

    private void saveDraft(long orderId, String body) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(orderId) + "/quote/draft").session(session(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertStatus(result, 200, "setup: quote draft save");
    }

    /** Issue through the protected send-email and take the plaintext token from the recorded email. */
    private String sendQuoteAndExtractToken(long orderId, int expectedVersion) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/quote/send-email").session(session(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: quote send-email (issue v" + expectedVersion + ")");
        JsonNode sendBody = json(result);
        Assertions.assertEquals(QUOTE_SENT_MESSAGE, sendBody.path("message").asText(), "setup: send message");
        Assertions.assertEquals(expectedVersion, sendBody.path("data").path("version_number").asInt(),
                "setup: issued version number");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly one quote email per send");
        Matcher matcher = QUOTE_LINK_PATTERN.matcher(sent.get(sent.size() - 1).bodyText());
        Assertions.assertTrue(matcher.find(), "setup: the quote email carries the /q/{token} link");
        return matcher.group(1);
    }

    /** The real public quote accept, sequentially (setup only; one signature part, no session, no slug). */
    private void acceptQuotePublicly(String token) throws Exception {
        MvcResult result = performPublic(mockMvc,
                multipart("/api/v1/public/quotes/" + token + "/accept").file(signaturePart(ONE_PIXEL_PNG)));
        assertStatus(result, 201, "setup: public quote accept");
        Assertions.assertEquals(QUOTE_ACCEPTED_MESSAGE, json(result).path("message").asText(),
                "setup: quote accept message");
    }

    /**
     * The protected send-link (16G contract section 8) as {@value #SENDER_CODE}: 201 with only the link's two
     * timestamps; exactly one link-only email to the TRIMMED saved address whose body carries the
     * {@code /i/{token}} link exactly once; the token's row found by its SHA-256 hash (ACTIVE, sent by the session
     * user, {@code expires_at} = {@code sent_at} + 7 days, delivered); D.3 reports that expiry, and the link is
     * ACTIVE on the public GET (which writes nothing).
     */
    private SentLink sendInvoiceLink(SeededOrder order) throws Exception {
        long orderId = order.orderId();
        int sentBefore = invoiceLinkEmailSender.sentEmails().size();
        LocalDateTime sendStarted = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/invoices/current/send-link")
                        .session(session(senderUserId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        LocalDateTime sendFinished = LocalDateTime.now();
        assertStatus(result, 201, "setup: send-link");
        JsonNode body = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(body), "setup: send-link envelope keys");
        Assertions.assertEquals(INVOICE_LINK_SENT_MESSAGE, body.path("message").asText(), "setup: send-link message");
        JsonNode sentLink = body.path("data").path("invoice_link");
        Assertions.assertEquals(Set.of("invoice_link"), fieldNames(body.path("data")), "setup: send-link data keys");
        Assertions.assertEquals(Set.of("sent_at", "expires_at"), fieldNames(sentLink), "setup: invoice_link keys");

        List<InvoiceLinkEmailRequest> sent = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly one link email per send");
        Assertions.assertTrue(invoiceLinkEmailSender.failedEmails().isEmpty(), "setup: no failed link email");
        InvoiceLinkEmailRequest email = sent.get(sent.size() - 1);
        Matcher matcher = INVOICE_LINK_PATTERN.matcher(email.bodyText());
        Assertions.assertTrue(matcher.find(), "setup: the link email carries the /i/{token} link");
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "setup: the link appears exactly once in the email body");
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), "setup: the TRIMMED saved customer email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), "setup: link email subject");
        Assertions.assertEquals("Hi,\n\nYour invoice " + order.orderNumber() + " from " + BUSINESS_NAME
                        + " is ready to review and sign. You can view and sign your invoice online here: "
                        + PUBLIC_BASE_URL + "/i/" + token + "\n\nThank you.", email.bodyText(),
                "setup: link email body");
        Assertions.assertEquals(orderId, email.orderId(), "setup: link email order id");
        Assertions.assertEquals(1, email.invoiceVersionNumber(), "setup: the link was sent for invoice v1");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM invoice_token WHERE token_hash = ?", sha256Hex(token));
        long invoiceTokenId = longValue(row.get("invoice_token_id"));
        Assertions.assertEquals(orderId, longValue(row.get("order_id")), "setup: the link is the order's");
        Assertions.assertEquals("ACTIVE", row.get("status"), "setup: the link is ACTIVE");
        Assertions.assertEquals(senderUserId, longValue(row.get("sent_by_user_id")), "setup: sent by the session user");
        Assertions.assertNull(row.get("dead_at"), "setup: the link is alive");
        Timestamp sentAt = (Timestamp) row.get("sent_at");
        Timestamp expiresAt = (Timestamp) row.get("expires_at");
        Assertions.assertFalse(sentAt.toLocalDateTime().isBefore(sendStarted)
                        || sentAt.toLocalDateTime().isAfter(sendFinished),
                "setup: sent_at " + sentAt + " is the send's own time");
        Assertions.assertEquals(sentAt.toLocalDateTime().plusDays(7), expiresAt.toLocalDateTime(),
                "setup: expires_at = sent_at + 7 days");
        Assertions.assertNotNull(row.get("emailed_at"), "setup: the delivered link email stamped emailed_at");
        Assertions.assertEquals(JSON_TIMESTAMP.format(sentAt.toLocalDateTime()), sentLink.path("sent_at").asText(),
                "setup: the 201 sent_at");
        Assertions.assertEquals(JSON_TIMESTAMP.format(expiresAt.toLocalDateTime()),
                sentLink.path("expires_at").asText(), "setup: the 201 expires_at");
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM invoice_token WHERE order_id = ?", orderId),
                "setup: the order's only invoice link");

        // D.3 derives invoice_link_expires_at from the ACTIVE, unexpired link (reading writes nothing).
        JsonNode current = currentInvoiceDetail(orderId);
        Assertions.assertEquals(JSON_TIMESTAMP.format(expiresAt.toLocalDateTime()),
                current.path("invoice_link_expires_at").asText(), "setup: D.3 invoice_link_expires_at");
        // The link works publicly before the race (the GET stamps nothing).
        MvcResult view = performPublic(mockMvc, get("/api/v1/public/invoices/" + token));
        assertStatus(view, 200, "setup: public view of the ACTIVE link");
        JsonNode viewData = json(view).path("data");
        Assertions.assertEquals("ACTIVE", viewData.path("state").asText(), "setup: the link is ACTIVE publicly");
        Assertions.assertEquals(1, viewData.path("version_number").asInt(), "setup: the public page shows v1");
        Assertions.assertEquals(row, jdbcTemplate.queryForMap(
                        "SELECT * FROM invoice_token WHERE invoice_token_id = ?", invoiceTokenId),
                "setup: neither read changed the link");
        return new SentLink(token, invoiceTokenId);
    }

    // ================================================================
    // Committed cleanup (FK-safe rows, then files on disk)
    // ================================================================

    /** Remove every committed row and file of this class's tenant (found by its slug), crashed runs included. */
    private void sweepTenant() {
        for (Long tenantBusinessId : jdbcTemplate.queryForList(
                "SELECT business_id FROM business WHERE slug = ?", Long.class, SLUG)) {
            for (Long orderId : jdbcTemplate.queryForList(
                    "SELECT order_id FROM sales_order WHERE business_id = ? ORDER BY order_id", Long.class,
                    tenantBusinessId)) {
                deleteCommittedOrder(tenantBusinessId, orderId);
            }
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
                // invoice_token.sent_by_user_id references app_user (no ON DELETE): defensively drop any link a
                // tenant user sent before the users go (per-order deletes above normally removed them all).
                jdbcTemplate.update("DELETE FROM invoice_token WHERE sent_by_user_id IN "
                        + "(SELECT user_id FROM app_user WHERE business_id = ?)", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM store_charge WHERE store_id IN "
                        + "(SELECT store_id FROM store WHERE business_id = ?)", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM store_product WHERE store_id IN "
                        + "(SELECT store_id FROM store WHERE business_id = ?)", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM user_store_access WHERE business_id = ?", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM app_user WHERE business_id = ?", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM store WHERE business_id = ?", tenantBusinessId);
                // Its FK to business has no ON DELETE: wipe it before the business row.
                jdbcTemplate.update("DELETE FROM business_quick_description WHERE business_id = ?", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM business WHERE business_id = ?", tenantBusinessId);
            });
            deleteDirectoryQuietly(physicalPath("/uploads/" + tenantBusinessId + "/"));
        }
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM business WHERE slug = ?", SLUG),
                "no tenant of this class may survive the sweep");
    }

    /**
     * Delete one committed order and everything it owns in ONE transaction (bounded by a lock timeout), in
     * FK-safe order: its invoice links (they reference sales_order and app_user), quote tokens, invoices (they may
     * reference a quote_version and the quote's signature), versions (cascade lines), the draft (cascade lines),
     * the other order children, the order, then its stored_file rows. The files on disk are deleted only after
     * that commit, then the order directory (which also catches orphans whose rows never committed).
     */
    private void deleteCommittedOrder(long tenantBusinessId, long orderId) {
        String prefix = orderStoragePrefix(tenantBusinessId, orderId);
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

    private void resetSenders() {
        invoiceEmailSender.reset();
        invoiceLinkEmailSender.reset();
        invoiceNotificationSender.reset();
        quoteEmailSender.reset();
        quoteNotificationSender.reset();
        smsSender.reset();
    }

    // ================================================================
    // Committed-state snapshot
    // ================================================================

    /**
     * Everything on one order the raced requests could write, in committed state: the sales_order row (its
     * last_emailed_at mirror and updated_at included), every invoice row (each version's last_emailed_at
     * included), every stored_file row of the order and the files on disk under its directory, every invoice
     * link, every quote version and token, the draft and its lines, the customer / address / line rows, and the
     * payments.
     */
    private record OrderSnapshot(Map<String, Object> header,
                                 List<Map<String, Object>> invoices,
                                 List<Map<String, Object>> storedFiles,
                                 Set<String> filesOnDisk,
                                 List<Map<String, Object>> invoiceTokens,
                                 List<Map<String, Object>> quoteVersions,
                                 List<Map<String, Object>> quoteTokens,
                                 List<Map<String, Object>> draft,
                                 List<Map<String, Object>> content,
                                 List<Map<String, Object>> payments) {

        OrderSnapshot withHeader(Map<String, Object> value) {
            return new OrderSnapshot(value, invoices, storedFiles, filesOnDisk, invoiceTokens, quoteVersions,
                    quoteTokens, draft, content, payments);
        }

        OrderSnapshot withInvoices(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, value, storedFiles, filesOnDisk, invoiceTokens, quoteVersions,
                    quoteTokens, draft, content, payments);
        }

        OrderSnapshot withStoredFiles(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, invoices, value, filesOnDisk, invoiceTokens, quoteVersions,
                    quoteTokens, draft, content, payments);
        }

        OrderSnapshot withFilesOnDisk(Set<String> value) {
            return new OrderSnapshot(header, invoices, storedFiles, value, invoiceTokens, quoteVersions,
                    quoteTokens, draft, content, payments);
        }

        OrderSnapshot withInvoiceTokens(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, invoices, storedFiles, filesOnDisk, value, quoteVersions,
                    quoteTokens, draft, content, payments);
        }

        OrderSnapshot withQuoteVersions(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, invoices, storedFiles, filesOnDisk, invoiceTokens, value,
                    quoteTokens, draft, content, payments);
        }

        OrderSnapshot withQuoteTokens(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, invoices, storedFiles, filesOnDisk, invoiceTokens, quoteVersions,
                    value, draft, content, payments);
        }
    }

    private OrderSnapshot snapshot(long orderId) throws IOException {
        List<Map<String, Object>> draft = new ArrayList<>(
                jdbcTemplate.queryForList("SELECT * FROM quote_draft WHERE order_id = ?", orderId));
        draft.addAll(jdbcTemplate.queryForList(
                "SELECT l.* FROM quote_draft_line l JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id "
                        + "WHERE d.order_id = ? ORDER BY l.quote_draft_line_id", orderId));
        List<Map<String, Object>> content = new ArrayList<>();
        content.addAll(jdbcTemplate.queryForList(
                "SELECT * FROM order_customer WHERE order_id = ? ORDER BY order_customer_id", orderId));
        content.addAll(jdbcTemplate.queryForList(
                "SELECT * FROM order_address WHERE order_id = ? ORDER BY order_address_id", orderId));
        content.addAll(jdbcTemplate.queryForList(
                "SELECT * FROM order_product_line WHERE order_id = ? ORDER BY order_product_line_id", orderId));
        content.addAll(jdbcTemplate.queryForList(
                "SELECT * FROM order_charge_line WHERE order_id = ? ORDER BY order_charge_line_id", orderId));
        content.addAll(jdbcTemplate.queryForList(
                "SELECT l.* FROM quote_version_line l JOIN quote_version v ON v.quote_version_id = l.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY l.quote_version_line_id", orderId));
        return new OrderSnapshot(
                jdbcTemplate.queryForMap("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                jdbcTemplate.queryForList("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId),
                jdbcTemplate.queryForList(ORDER_FILES_SQL, orderId, orderId, orderId, orderId, orderId, orderId,
                        orderStoragePrefix(orderId) + "%"),
                storagePathsOnDisk(orderId),
                jdbcTemplate.queryForList(
                        "SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId),
                jdbcTemplate.queryForList(
                        "SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId),
                jdbcTemplate.queryForList(
                        "SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                                + "WHERE v.order_id = ? ORDER BY t.quote_token_id", orderId),
                draft,
                content,
                jdbcTemplate.queryForList(
                        "SELECT * FROM payment_transaction WHERE order_id = ? ORDER BY payment_transaction_id",
                        orderId));
    }

    /** Part-by-part equality of two snapshots (the expected one = the seed plus the winner's own writes). */
    private static void assertSnapshot(OrderSnapshot expected, OrderSnapshot actual, String context) {
        Assertions.assertEquals(expected.header(), actual.header(),
                context + ": the sales_order row (incl. its last_emailed_at mirror)");
        Assertions.assertEquals(expected.invoices(), actual.invoices(),
                context + ": the invoice rows (incl. each version's last_emailed_at)");
        Assertions.assertEquals(expected.storedFiles(), actual.storedFiles(),
                context + ": the order's stored_file rows");
        Assertions.assertEquals(expected.filesOnDisk(), actual.filesOnDisk(),
                context + ": the files on disk under the order's directory");
        Assertions.assertEquals(actual.storedFiles().stream().map(row -> (String) row.get("storage_path"))
                        .collect(Collectors.toCollection(TreeSet::new)), actual.filesOnDisk(),
                context + ": every stored_file row has its file and every file its row (no orphan)");
        Assertions.assertEquals(expected.invoiceTokens(), actual.invoiceTokens(), context + ": the invoice_token rows");
        Assertions.assertEquals(expected.quoteVersions(), actual.quoteVersions(), context + ": the quote_version rows");
        Assertions.assertEquals(expected.quoteTokens(), actual.quoteTokens(), context + ": the quote_token rows");
        Assertions.assertEquals(expected.draft(), actual.draft(), context + ": the quote draft and its lines");
        Assertions.assertEquals(expected.content(), actual.content(),
                context + ": the customer, address, product / charge line and quote version line rows");
        Assertions.assertEquals(expected.payments(), actual.payments(), context + ": the payment rows");
    }

    // ================================================================
    // The winners' versions
    // ================================================================

    /** A signed version a race appended: its row, its files, its PDF bytes, its signing time and its email stamp. */
    private record SignedVersion(int version, Map<String, Object> row, Map<String, Object> pdfFile,
                                 Map<String, Object> signatureFile, byte[] pdfBytes, Timestamp acceptedAt,
                                 Timestamp stamp) {

        String pdfPath() {
            return (String) pdfFile.get("storage_path");
        }

        String signaturePath() {
            return (String) signatureFile.get("storage_path");
        }
    }

    /** An unsigned version a Rewrite appended: its row and its PDF. */
    private record RewrittenVersion(int version, Map<String, Object> row, Map<String, Object> pdfFile) {

        String pdfPath() {
            return (String) pdfFile.get("storage_path");
        }
    }

    /**
     * The signing version a remote accept or D.8 appended over {@code current} (16G contract sections 5.4, 10.1,
     * 10.2): EXACTLY {@code current} carried forward - its invoice date (copied, not the signing day), due date,
     * details, money, frozen words, terms and quote source - plus its own id / version / PDF / creation time, the
     * signing time as {@code accepted_at} (during the race), the frozen name verbatim as the accepted name, its
     * own signature file, the expected creator ({@code sent_by_user_id} for a remote signing, the session user for
     * D.8) and the stamp of its delivered email. Its signature file holds exactly {@code expectedStoredSignature}
     * (the server-normalised PNG of a remote upload, or D.8's upload verbatim) with the upload's dimensions, and
     * its PDF carries the acceptance caption of THIS signing.
     */
    private SignedVersion assertSigningVersion(long orderId, String orderNumber, Map<String, Object> current,
                                               int version, long expectedCreatorUserId, byte[] uploadedSignature,
                                               byte[] expectedStoredSignature, RaceResults race, String label)
            throws IOException {
        Map<String, Object> row = invoiceRow(orderId, version);
        Timestamp acceptedAt = (Timestamp) row.get("accepted_at");
        Assertions.assertNotNull(acceptedAt, label + ": the appended version is signed");
        assertWithinRace(acceptedAt, race, label + ": accepted_at is the signing time");
        Timestamp stamp = (Timestamp) row.get("last_emailed_at");
        Assertions.assertNotNull(stamp, label + ": the delivered signed-PDF email stamped the appended version");
        assertWithinRace(stamp, race, label + ": the email stamp");
        Assertions.assertFalse(stamp.before(acceptedAt), label + ": stamped after the signing");
        long pdfFileId = longValue(row.get("stored_file_id"));
        long signatureFileId = longValue(row.get("accepted_signature_file_id"));
        Assertions.assertEquals(withColumns(current,
                        "invoice_id", row.get("invoice_id"),
                        "version_number", version,
                        "stored_file_id", pdfFileId,
                        "created_by_user_id", expectedCreatorUserId,
                        "created_at", row.get("created_at"),
                        "accepted_at", acceptedAt,
                        "accepted_customer_name", current.get("customer_name_snapshot"),
                        "accepted_signature_file_id", signatureFileId,
                        "last_emailed_at", stamp),
                row, label + ": exactly the current version carried forward, signed by this request");
        // Readable restatements of the key carried / signed columns (all implied by the equality above).
        Assertions.assertEquals(V1_INVOICE_DATE, ((Date) row.get("invoice_date")).toLocalDate(),
                label + ": invoice_date is COPIED from the version signed, not the signing day");
        Assertions.assertEquals(EXPECTED_DUE_DATE, ((Date) row.get("due_date")).toLocalDate(), label + ": due_date");
        Assertions.assertEquals(CUSTOMER_NAME, row.get("accepted_customer_name"),
                label + ": the accepted name is the frozen customer_name_snapshot, verbatim");
        Assertions.assertNotEquals(longValue(current.get("invoice_id")), longValue(row.get("invoice_id")),
                label + ": a NEW invoice row (append-only)");

        Map<String, Object> signatureFile = storedFileRow(signatureFileId);
        Assertions.assertEquals(signatureFileName(orderNumber, version), signatureFile.get("file_name"),
                label + ": signature file name");
        Assertions.assertEquals("image/png", signatureFile.get("mime_type"), label + ": signature mime type");
        byte[] storedSignature = fileStorageService.read((String) signatureFile.get("storage_path"));
        Assertions.assertArrayEquals(expectedStoredSignature, storedSignature,
                label + ": the stored signature is exactly this request's (normalised) upload");
        Assertions.assertEquals((long) storedSignature.length, longValue(signatureFile.get("file_size")),
                label + ": signature stored_file.file_size == the bytes on disk");
        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(uploadedSignature));
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(storedSignature));
        Assertions.assertNotNull(stored, label + ": the stored signature decodes as an image");
        Assertions.assertEquals(uploaded.getWidth() + "x" + uploaded.getHeight(),
                stored.getWidth() + "x" + stored.getHeight(), label + ": the stored signature is this request's image");

        Map<String, Object> pdfFile = storedFileRow(pdfFileId);
        Assertions.assertEquals(invoicePdfName(orderNumber, version), pdfFile.get("file_name"), label + ": PDF name");
        Assertions.assertEquals("application/pdf", pdfFile.get("mime_type"), label + ": PDF mime type");
        byte[] pdf = fileStorageService.read((String) pdfFile.get("storage_path"));
        Assertions.assertTrue(startsWithPdfMagic(pdf), label + ": the stored signed invoice is a PDF");
        Assertions.assertEquals((long) pdf.length, longValue(pdfFile.get("file_size")),
                label + ": PDF stored_file.file_size == the bytes on disk");
        assertAcceptedCaption(pdf, CUSTOMER_NAME, acceptedAt.toLocalDateTime(), label);
        return new SignedVersion(version, row, pdfFile, signatureFile, pdf, acceptedAt, stamp);
    }

    /**
     * Path A's v2 (16G contract sections 4.2, 5.5; 16B section 7.1): v1's order-bound columns (creator, due
     * date, nothing paid) with the ACCEPTED quote v1's frozen snapshot verbatim - details, ex / inc totals (the
     * balance = inc), terms, the V17 Invoice To, the source - and its INHERITED acceptance (the quote's signing
     * time and name and the SAME signature stored_file, never a new one), dated today, with its own PDF and the
     * stamp of its delivered email.
     */
    private SignedVersion assertPathAVersion(PathAOrder order, Map<String, Object> invoiceV1,
                                             Map<String, Object> acceptedQuote, LocalDate dayBefore,
                                             LocalDate dayAfter, RaceResults race) throws IOException {
        String label = "Path A's v2";
        Map<String, Object> row = invoiceRow(order.orderId(), 2);
        Timestamp stamp = (Timestamp) row.get("last_emailed_at");
        Assertions.assertNotNull(stamp, label + ": the delivered signed-PDF email stamped v2");
        assertWithinRace(stamp, race, label + ": the email stamp");
        LocalDate invoiceDate = ((Date) row.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(dayBefore) || invoiceDate.isAfter(dayAfter),
                label + ": dated the day it is written, but was " + invoiceDate);
        Assertions.assertEquals(withColumns(invoiceV1,
                        "invoice_id", row.get("invoice_id"),
                        "version_number", 2,
                        "invoice_date", row.get("invoice_date"),
                        "details_of_sale_snapshot", acceptedQuote.get("details_of_sale_snapshot"),
                        "sale_price_ex_gst", acceptedQuote.get("quote_total_ex_gst"),
                        "sale_price_inc_gst", acceptedQuote.get("quote_total_inc_gst"),
                        "balance_due", acceptedQuote.get("quote_total_inc_gst"),
                        "stored_file_id", row.get("stored_file_id"),
                        "created_at", row.get("created_at"),
                        "accepted_at", acceptedQuote.get("accepted_at"),
                        "accepted_customer_name", acceptedQuote.get("accepted_customer_name"),
                        "accepted_signature_file_id", acceptedQuote.get("accepted_signature_file_id"),
                        "last_emailed_at", stamp,
                        "source_quote_version_id", order.acceptedQuoteVersionId(),
                        "terms_snapshot", acceptedQuote.get("terms_snapshot"),
                        "customer_name_snapshot", acceptedQuote.get("customer_name_snapshot"),
                        "customer_address_line1_snapshot", acceptedQuote.get("customer_address_line1_snapshot"),
                        "customer_address_line2_snapshot", acceptedQuote.get("customer_address_line2_snapshot")),
                row, label + ": exactly Path A's conversion of the accepted quote v1");
        Assertions.assertEquals(staffUserId, longValue(row.get("created_by_user_id")), label + ": the session user");
        Map<String, Object> pdfFile = storedFileRow(longValue(row.get("stored_file_id")));
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), 2), pdfFile.get("file_name"), label + ": PDF name");
        Assertions.assertEquals("application/pdf", pdfFile.get("mime_type"), label + ": PDF mime type");
        byte[] pdf = fileStorageService.read((String) pdfFile.get("storage_path"));
        Assertions.assertTrue(startsWithPdfMagic(pdf), label + ": the stored invoice is a PDF");
        Assertions.assertEquals((long) pdf.length, longValue(pdfFile.get("file_size")),
                label + ": PDF stored_file.file_size == the bytes on disk");
        Timestamp acceptedAt = (Timestamp) acceptedQuote.get("accepted_at");
        assertAcceptedCaption(pdf, (String) acceptedQuote.get("accepted_customer_name"),
                acceptedAt.toLocalDateTime(), label);
        return new SignedVersion(2, row, pdfFile, null, pdf, acceptedAt, stamp);
    }

    /**
     * A Rewrite's version (Chunk 4 D.2, 16G contract section 4.2): a fresh snapshot of the LIVE order, which the
     * race never changed - so exactly the D.1 v1's columns (creator = the session user, details, money, frozen
     * words, no terms, no source) with its own id / version / PDF / creation time, dated today, UNSIGNED
     * (Rewrite clears any signature) and unemailed.
     */
    private RewrittenVersion assertRewriteVersion(LinkedOrder order, Map<String, Object> invoiceV1, int version,
                                                  LocalDate dayBefore, LocalDate dayAfter) {
        String label = "the Rewrite's v" + version;
        Map<String, Object> row = invoiceRow(order.orderId(), version);
        LocalDate invoiceDate = ((Date) row.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(dayBefore) || invoiceDate.isAfter(dayAfter),
                label + ": dated the day it is written, but was " + invoiceDate);
        Assertions.assertEquals(withColumns(invoiceV1,
                        "invoice_id", row.get("invoice_id"),
                        "version_number", version,
                        "invoice_date", row.get("invoice_date"),
                        "stored_file_id", row.get("stored_file_id"),
                        "created_at", row.get("created_at")),
                row, label + ": a fresh, unsigned snapshot of the unchanged live order");
        for (String unsigned : List.of("accepted_at", "accepted_customer_name", "accepted_signature_file_id",
                "last_emailed_at", "source_quote_version_id")) {
            Assertions.assertNull(row.get(unsigned), label + ": carries no " + unsigned);
        }
        Map<String, Object> pdfFile = storedFileRow(longValue(row.get("stored_file_id")));
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), version), pdfFile.get("file_name"),
                label + ": PDF name");
        Assertions.assertEquals("application/pdf", pdfFile.get("mime_type"), label + ": PDF mime type");
        return new RewrittenVersion(version, row, pdfFile);
    }

    // ================================================================
    // Assertions
    // ================================================================

    /** The remote accept's 201: exactly {@code {"data":{"state":"SIGNED"},"message":"Invoice accepted."}}. */
    private static void assertRemoteAccepted(MvcResult result, String label) throws Exception {
        assertStatus(result, 201, label);
        JsonNode root = json(result);
        Assertions.assertEquals(JSON.readTree("{\"data\":{\"state\":\"SIGNED\"},\"message\":\""
                + REMOTE_ACCEPTED_MESSAGE + "\"}"), root, label + ": exactly the documented 201 body");
    }

    /** A refusal: the exact status, code and message, no data and no details. */
    private static void assertRefusal(MvcResult result, int status, ErrorCode code, String exactMessage,
                                      String label) throws Exception {
        assertStatus(result, status, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("error"), fieldNames(root), label + ": a refusal carries no data");
        JsonNode error = root.path("error");
        Assertions.assertEquals(Set.of("code", "message"), fieldNames(error), label + ": no details");
        Assertions.assertEquals(code.name(), error.path("code").asText(), label + ": error code");
        Assertions.assertEquals(exactMessage, error.path("message").asText(), label + ": the exact message");
        Assertions.assertEquals(status, code.status().value(), label + ": the code's status");
    }

    /** A signed version's 201 InvoiceDetail: that committed row, signed, stamped, and no live link. */
    private static void assertSignedInvoiceDetail(JsonNode invoice, SignedVersion signed, String acceptedName,
                                                  String label) {
        Assertions.assertEquals(longValue(signed.row().get("invoice_id")), invoice.path("invoice_id").asLong(),
                label + ": data.invoice is the committed row");
        Assertions.assertEquals(signed.version(), invoice.path("version_number").asInt(), label + ": version");
        Assertions.assertTrue(invoice.path("accepted_signature_present").booleanValue(), label + ": signed");
        Assertions.assertEquals(acceptedName, invoice.path("accepted_customer_name").asText(), label + ": name");
        Assertions.assertEquals(JSON_TIMESTAMP.format(signed.acceptedAt().toLocalDateTime()),
                invoice.path("accepted_at").asText(), label + ": accepted_at");
        Assertions.assertEquals(JSON_TIMESTAMP.format(signed.stamp().toLocalDateTime()),
                invoice.path("last_emailed_at").asText(), label + ": the delivered email's stamp");
        Assertions.assertTrue(invoice.has("invoice_link_expires_at") && invoice.get("invoice_link_expires_at").isNull(),
                label + ": invoice_link_expires_at is present and null (the route killed the link)");
    }

    /** A Rewrite's 201 InvoiceDetail: that committed, UNSIGNED row, and no live link. */
    private static void assertUnsignedInvoiceDetail(JsonNode invoice, RewrittenVersion rewritten, String label) {
        Assertions.assertEquals(longValue(rewritten.row().get("invoice_id")), invoice.path("invoice_id").asLong(),
                label + ": data.invoice is the committed row");
        Assertions.assertEquals(rewritten.version(), invoice.path("version_number").asInt(), label + ": version");
        Assertions.assertFalse(invoice.path("accepted_signature_present").booleanValue(),
                label + ": Rewrite clears the signature");
        for (String unsigned : List.of("accepted_at", "accepted_customer_name", "accepted_signature_download_path",
                "last_emailed_at", "invoice_link_expires_at")) {
            Assertions.assertTrue(invoice.has(unsigned) && invoice.get(unsigned).isNull(),
                    label + ": " + unsigned + " is present and null");
        }
        Assertions.assertEquals("BUSINESS", invoice.path("terms_source").asText(), label + ": terms_source");
    }

    /**
     * The race's messages, exactly: one signed-PDF email for {@code emailed} (or none when null) - the stored PDF
     * of that version, to the TRIMMED saved email - and one store notification for {@code notified} (or none);
     * nothing failed, and no link email, quote email, quote notification or SMS at all.
     */
    private void assertRaceMessages(long orderId, String orderNumber, SignedVersion emailed, SignedVersion notified,
                                    String context) {
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), context + ": no failed signed-PDF email");
        List<InvoiceEmailRequest> emails = invoiceEmailSender.sentEmails();
        if (emailed == null) {
            Assertions.assertEquals(List.of(), emails, context + ": no signed-PDF email at all");
        } else {
            Assertions.assertEquals(1, emails.size(), context + ": exactly one signed-PDF email");
            InvoiceEmailRequest email = emails.get(0);
            Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(),
                    context + ": the recipient is the TRIMMED saved customer email");
            Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), context + ": subject");
            Assertions.assertEquals("Hi,\n\nPlease find your invoice " + orderNumber + " from " + BUSINESS_NAME
                    + " attached.\n\nThank you.", email.bodyText(), context + ": body");
            Assertions.assertArrayEquals(emailed.pdfBytes(), email.pdfBytes(),
                    context + ": the attachment is the stored PDF of the version the winner appended");
            Assertions.assertEquals(invoicePdfName(orderNumber, emailed.version()), email.pdfFileName(),
                    context + ": attachment name");
            Assertions.assertEquals(orderId, email.orderId(), context + ": email order id");
            Assertions.assertEquals(emailed.version(), email.invoiceVersionNumber(), context + ": email version");
        }

        Assertions.assertTrue(invoiceNotificationSender.failedNotifications().isEmpty(),
                context + ": no failed store notification");
        List<InvoiceAcceptanceNotificationRequest> notifications = invoiceNotificationSender.sentNotifications();
        if (notified == null) {
            Assertions.assertEquals(List.of(), notifications, context + ": no store notification at all");
        } else {
            Assertions.assertEquals(1, notifications.size(), context + ": exactly one store notification");
            InvoiceAcceptanceNotificationRequest notification = notifications.get(0);
            Assertions.assertEquals(STORE_EMAIL, notification.recipientEmail(), context + ": the order's store email");
            Assertions.assertEquals("Invoice accepted: " + orderNumber, notification.subject(),
                    context + ": notification subject");
            assertMoney(V1_INC_GST, notified.row().get("sale_price_inc_gst"), context + ": the signed version's inc");
            LocalDateTime acceptedAt = notified.acceptedAt().toLocalDateTime();
            List<String> acceptable = new ArrayList<>();
            // Postgres keeps microseconds: the in-memory signing instant may sit up to 1us before the stored one.
            for (LocalDateTime at : List.of(acceptedAt, acceptedAt.minusNanos(1_000))) {
                acceptable.add("Invoice " + orderNumber + " (version " + notified.version()
                        + ") was accepted online by " + CUSTOMER_NAME + " on " + DISPLAY_DATE_TIME.format(at)
                        + ".\n\nInvoice total (inc GST): " + V1_INC_GST_DISPLAY
                        + ".\n\nThe signed invoice is available in the sales portal.");
            }
            Assertions.assertTrue(acceptable.contains(notification.bodyText()),
                    context + ": notification body, expected one of " + acceptable + " but was "
                            + notification.bodyText());
            Assertions.assertEquals(orderId, notification.orderId(), context + ": notification order id");
            Assertions.assertEquals(notified.version(), notification.invoiceVersionNumber(),
                    context + ": notification version");
        }

        Assertions.assertTrue(invoiceLinkEmailSender.sentEmails().isEmpty()
                && invoiceLinkEmailSender.failedEmails().isEmpty(), context + ": no invoice link email");
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty() && quoteEmailSender.failedEmails().isEmpty(),
                context + ": no quote email");
        Assertions.assertTrue(quoteNotificationSender.sentNotifications().isEmpty()
                && quoteNotificationSender.failedNotifications().isEmpty(), context + ": no quote notification");
        Assertions.assertTrue(smsSender.sentMessages().isEmpty() && smsSender.failedMessages().isEmpty(),
                context + ": no SMS");
    }

    /**
     * The order's only invoice link ended with the expected status and {@code dead_at}; no ACTIVE link is left
     * (nothing minted a new one) and the link's send markers are as they were.
     */
    private void assertFinalLink(long orderId, long invoiceTokenId, String status, Timestamp deadAt) {
        Map<String, Object> link = invoiceTokenRow(invoiceTokenId);
        Assertions.assertEquals(status, link.get("status"), "the final link status");
        Assertions.assertEquals(deadAt, link.get("dead_at"), "the link's dead_at");
        Assertions.assertNotNull(link.get("emailed_at"), "the link's own delivery marker is untouched");
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM invoice_token WHERE order_id = ?", orderId),
                "no new link was minted");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM invoice_token WHERE order_id = ? AND status = 'ACTIVE'",
                orderId), "no ACTIVE link is left");
    }

    /**
     * The current version (the highest) is {@code version} with the given {@code accepted_at} (null = unsigned)
     * and {@code last_emailed_at}, and the sales_order mirror equals the CURRENT version's value.
     */
    private void assertCurrentVersionAndMirror(long orderId, int version, Timestamp acceptedAt, Timestamp stamp) {
        Assertions.assertEquals(version, currentInvoiceVersion(orderId), "the current invoice version");
        Map<String, Object> current = invoiceRow(orderId, version);
        Assertions.assertEquals(acceptedAt, current.get("accepted_at"), "the current version's accepted_at");
        Assertions.assertEquals(stamp, current.get("last_emailed_at"), "the current version's last_emailed_at");
        Assertions.assertEquals(stamp, jdbcTemplate.queryForObject(
                        "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId),
                "the sales_order mirror equals the current version's last_emailed_at");
    }

    /** The dead link through the REAL public GET: 200, the minimal dead payload (all 27 keys), nothing else. */
    private void assertPublicDeadView(String token, String state, String message) throws Exception {
        MvcResult result = performPublic(mockMvc, get("/api/v1/public/invoices/" + token));
        assertStatus(result, 200, "public view of the dead link");
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), "public view: envelope keys");
        Assertions.assertEquals(INVOICE_LOADED_MESSAGE, root.path("message").asText(), "public view: message");
        JsonNode data = root.path("data");
        Assertions.assertEquals(PUBLIC_VIEW_KEYS, fieldNames(data), "public view: exactly the 27 keys");
        for (String key : PUBLIC_VIEW_KEYS) {
            switch (key) {
                case "state" -> Assertions.assertEquals(state, data.get(key).asText(), "public view: state");
                case "business_name" -> Assertions.assertEquals(BUSINESS_NAME, data.get(key).asText(),
                        "public view: business_name");
                case "message" -> Assertions.assertEquals(message, data.get(key).asText(), "public view: message");
                default -> Assertions.assertTrue(data.get(key).isNull(), "public view: " + key + " is null when dead");
            }
        }
    }

    /**
     * The staff D.3 read after the race: the current version, its acceptance and email stamp, and
     * {@code invoice_link_expires_at} present and null - no ACTIVE link is left (the read writes nothing).
     */
    private void assertStaffReadShowsNoLink(long orderId, int version, Timestamp acceptedAt, Timestamp stamp)
            throws Exception {
        List<Map<String, Object>> linksBefore = jdbcTemplate.queryForList(
                "SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId);
        JsonNode invoice = currentInvoiceDetail(orderId);
        Assertions.assertEquals(version, invoice.path("version_number").asInt(), "D.3: the current version");
        Assertions.assertTrue(invoice.has("invoice_link_expires_at") && invoice.get("invoice_link_expires_at").isNull(),
                "D.3: invoice_link_expires_at is present and null");
        if (acceptedAt == null) {
            Assertions.assertTrue(invoice.get("accepted_at").isNull(), "D.3: unsigned");
            Assertions.assertFalse(invoice.path("accepted_signature_present").booleanValue(), "D.3: no signature");
        } else {
            Assertions.assertEquals(JSON_TIMESTAMP.format(acceptedAt.toLocalDateTime()),
                    invoice.path("accepted_at").asText(), "D.3: accepted_at");
            Assertions.assertTrue(invoice.path("accepted_signature_present").booleanValue(), "D.3: signed");
        }
        if (stamp == null) {
            Assertions.assertTrue(invoice.get("last_emailed_at").isNull(), "D.3: unemailed");
        } else {
            Assertions.assertEquals(JSON_TIMESTAMP.format(stamp.toLocalDateTime()),
                    invoice.path("last_emailed_at").asText(), "D.3: last_emailed_at");
        }
        Assertions.assertEquals(linksBefore, jdbcTemplate.queryForList(
                        "SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId),
                "D.3 wrote nothing to the links");
    }

    /** The order's image/png stored files (its signature images) are exactly {@code expected}. */
    private void assertOnlySignatureImages(long orderId, List<Map<String, Object>> expected, String label) {
        Assertions.assertEquals(expected, jdbcTemplate.queryForList(
                        "SELECT stored_file_id, file_name, storage_path, mime_type, file_size FROM stored_file "
                                + "WHERE storage_path LIKE ? AND mime_type = 'image/png' ORDER BY stored_file_id",
                        orderStoragePrefix(orderId) + "%"),
                label);
    }

    /** No invoice version carries an email stamp and the sales_order mirror is null. */
    private void assertNoEmailMarkers(long orderId, String label) {
        Assertions.assertEquals(0, count(
                        "SELECT COUNT(*) FROM invoice WHERE order_id = ? AND last_emailed_at IS NOT NULL", orderId),
                label + ": no invoice version carries a last_emailed_at stamp");
        Assertions.assertNull(jdbcTemplate.queryForObject(
                        "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId),
                label + ": the sales_order last_emailed_at mirror is null");
    }

    /**
     * The signed caption "Accepted by {name} on {dd/MM/yyyy HH:mm}" in reading order, whitespace removed. Postgres
     * keeps microseconds, so the stored instant may sit up to 1 microsecond after the rendered in-memory one; the
     * rendering of {@code acceptedAt - 1us} is accepted too, which keeps the check exact across a minute boundary.
     */
    private static void assertAcceptedCaption(byte[] pdf, String acceptedName, LocalDateTime acceptedAt, String label)
            throws IOException {
        String text = noSpace(readingOrderText(pdf));
        String exact = noSpace("Accepted by " + acceptedName + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        String roundedBack = noSpace("Accepted by " + acceptedName + " on "
                + DISPLAY_DATE_TIME.format(acceptedAt.minusNanos(1_000)));
        Assertions.assertTrue(text.contains(exact) || text.contains(roundedBack),
                () -> label + ": the PDF must carry the caption '" + exact + "' in: " + text);
    }

    private static void assertWithinRace(Timestamp value, RaceResults race, String label) {
        LocalDateTime at = value.toLocalDateTime();
        Assertions.assertFalse(at.isBefore(race.startedAt()) || at.isAfter(race.finishedAt()),
                label + ": " + at + " must fall within the race [" + race.startedAt() + ", " + race.finishedAt() + "]");
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + body(result));
    }

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertNotNull(actual, label + ": expected " + expected + " but was null");
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
                label + ": expected " + expected + " but was " + actual);
    }

    // ================================================================
    // Committed-state probes
    // ================================================================

    /** D.3 read of the current invoice (the staff session; no message). */
    private JsonNode currentInvoiceDetail(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(get(orderUrl(orderId) + "/invoices/current").session(session(staffUserId)))
                .andReturn();
        assertStatus(result, 200, "D.3 current invoice read");
        return json(result).path("data").path("invoice");
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private List<Integer> invoiceVersions(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT version_number FROM invoice WHERE order_id = ? ORDER BY version_number",
                Integer.class, orderId);
    }

    /** The order's CURRENT invoice version (the highest version_number) in committed state. */
    private int currentInvoiceVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "the order has a current invoice");
        return version;
    }

    private Map<String, Object> invoiceTokenRow(long invoiceTokenId) {
        return jdbcTemplate.queryForMap("SELECT * FROM invoice_token WHERE invoice_token_id = ?", invoiceTokenId);
    }

    private Map<String, Object> quoteVersionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    /** The id of the order's latest ACCEPTED quote version (highest version_number among ACCEPTED). */
    private long latestAcceptedQuoteVersionId(long orderId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT quote_version_id FROM quote_version WHERE order_id = ? AND status = 'ACCEPTED' "
                        + "ORDER BY version_number DESC LIMIT 1", Long.class, orderId);
        Assertions.assertNotNull(id, "the order has an ACCEPTED quote version");
        return id;
    }

    /** The one ACTIVE token of a quote version (exactly one row, or the lookup fails). */
    private Map<String, Object> activeQuoteTokenRow(long quoteVersionId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_token WHERE quote_version_id = ? AND status = 'ACTIVE'", quoteVersionId);
    }

    /** One stored_file row, in the same columns as the snapshot's stored_file rows. */
    private Map<String, Object> storedFileRow(long storedFileId) {
        return jdbcTemplate.queryForMap(
                "SELECT stored_file_id, file_name, storage_path, mime_type, file_size FROM stored_file "
                        + "WHERE stored_file_id = ?", storedFileId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
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

    /** A copy of a row with some columns replaced (the expected state of a row a winner changed). */
    private static Map<String, Object> withColumns(Map<String, Object> row, Object... columnValuePairs) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        for (int i = 0; i < columnValuePairs.length; i += 2) {
            String column = (String) columnValuePairs[i];
            Assertions.assertTrue(copy.containsKey(column), "unknown column " + column);
            copy.put(column, columnValuePairs[i + 1]);
        }
        return copy;
    }

    /** {@code rows} with the row whose {@code idColumn} equals {@code replacement}'s replaced by it. */
    private static List<Map<String, Object>> replaceRow(List<Map<String, Object>> rows, String idColumn,
                                                        Map<String, Object> replacement) {
        Object id = replacement.get(idColumn);
        Assertions.assertEquals(1, rows.stream().filter(row -> id.equals(row.get(idColumn))).count(),
                "exactly one row with " + idColumn + " = " + id);
        return rows.stream().map(row -> id.equals(row.get(idColumn)) ? replacement : row).toList();
    }

    @SafeVarargs
    private static List<Map<String, Object>> append(List<Map<String, Object>> rows, Map<String, Object>... more) {
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        copy.addAll(List.of(more));
        return copy;
    }

    private static Set<String> plus(Set<String> paths, String... more) {
        Set<String> copy = new TreeSet<>(paths);
        for (String path : more) {
            Assertions.assertTrue(copy.add(path), "the added file " + path + " is new");
        }
        return copy;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A signature-pad-like 8-bit ARGB PNG of the given size: transparent with one dark horizontal stroke. */
    private static byte[] strokePng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            image.setRGB(x, height / 2, 0xFF1A1A1A);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("a PNG ImageWriter must be available");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static boolean startsWithPdfMagic(byte[] bytes) {
        return bytes.length >= 5 && "%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII));
    }

    /** Visual reading order (the signed caption's spans are emitted out of content-stream order). */
    private static String readingOrderText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
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

    private String orderStoragePrefix(long orderId) {
        return orderStoragePrefix(businessId, orderId);
    }

    private static String orderStoragePrefix(long ownerBusinessId, long orderId) {
        return "/uploads/" + ownerBusinessId + "/orders/" + orderId + "/";
    }

    private Set<String> storagePathsOnDisk(long orderId) throws IOException {
        String prefix = orderStoragePrefix(orderId);
        Path directory = physicalPath(prefix);
        if (!Files.isDirectory(directory)) {
            return new TreeSet<>();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> prefix + file.getFileName())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String invoicePdfName(String orderNumber, int versionNumber) {
        return "invoice-" + orderNumber + "-v" + versionNumber + ".pdf";
    }

    private static String signatureFileName(String orderNumber, int versionNumber) {
        return "signature-" + orderNumber + "-v" + versionNumber + ".png";
    }
}
