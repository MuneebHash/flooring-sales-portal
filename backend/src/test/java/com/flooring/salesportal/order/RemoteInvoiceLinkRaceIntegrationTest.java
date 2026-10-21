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
import com.flooring.salesportal.common.email.RecordingInvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.GlobalExceptionHandler;
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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Phase 16G PR 4 (16G contract sections 5.2, 7.5, 7.7, 8, 9.3 to 9.5, 10 and 11; the PR 4 row of section 16:
 * "Remote stale rejection, including a version committed while the request waits; holder-connection
 * interleavings in both lock orders for ... remote accept against ... payment, void ... and link send against
 * remote accept; lazy expiry at the locked re-read with no invoice change") - part B: the remote invoice accept
 * ({@code POST /api/v1/public/invoices/{token}/accept}) raced, on the ORDER ROW LOCK, against the writers that
 * do NOT end an invoice link (payment D.7, payment void D.10), against the protected send-link that REPLACES it,
 * and against changes the lock holder itself commits while the accept waits - and, through the token gate's lazy
 * expiry, a public GET, pdf or accept waiting on the same lock:
 * <ul>
 *   <li><b>Remote accept vs payment</b>, both lock orders. Remote first: the remote signs v1 into v2 (the link
 *       SIGNED at the signing time, the ISSUED quote and its link cancelled by D5(c)); the queued payment then
 *       appends v3 carrying v2's acceptance and signature, and the dead link stays SIGNED. Payment first: the
 *       payment appends v2, and the queued remote accept, still carrying the v1 its page showed, is refused under
 *       the lock with 409 {@code INVOICE_VERSION_STALE}; the link stays ACTIVE, the next public read shows v2,
 *       and a fresh accept carrying v2 then signs it.</li>
 *   <li><b>Remote accept vs void</b> (a payment is seeded after the send, so the link already shows its v2), both
 *       lock orders, with the analogous outcomes.</li>
 *   <li><b>Send-link vs remote accept</b>, both lock orders. Send first: the old link is REPLACED (dead at the
 *       new link's send time) and a new ACTIVE link is minted and emailed; the queued remote accept presenting the
 *       OLD token re-reads it under the lock and gets 410 {@code INVOICE_LINK_REPLACED}; the new link then signs.
 *       Remote first: the remote signs v2; the queued send finds a signed current invoice and is refused with
 *       409 {@code INVOICE_ALREADY_ACCEPTED} - no new token row, no link email.</li>
 *   <li><b>A newer version committed by the lock holder</b> while the accept waits: 409
 *       {@code INVOICE_VERSION_STALE}, nothing of the accept written, the link ACTIVE.</li>
 *   <li><b>Expiry falling due at the locked re-read</b>: the accept passed its gate on an unexpired link; the
 *       holder backdates {@code expires_at} and commits; the accept's locked re-read COMMITS the EXPIRED flip and
 *       answers 410 {@code INVOICE_LINK_EXPIRED}, with no invoice change and no message.</li>
 *   <li><b>A link killed (REWRITTEN) by the holder</b> while the accept waits, its {@code expires_at} also in the
 *       past: 410 {@code INVOICE_LINK_REWRITTEN} - the true reason, never EXPIRED, and never the stale 409 although
 *       the holder also appended a newer version.</li>
 *   <li><b>An over-age link no longer past expiry at the token gate's locked re-read</b> (16G contract section 7.7:
 *       flipped to EXPIRED "only if it is still ACTIVE and still past expiry"): a public GET, pdf or accept arrives
 *       on a link that is ALREADY over-age, so its token gate takes the order lock to expire it, and is observed
 *       parked there; the holder moves the link's {@code expires_at} into the future and commits - a TEST FIXTURE
 *       only ({@code expires_at} never changes in production), standing in for a JVM wall clock that steps back
 *       while the gate waits (it pins the comparison with the re-read row, not when the clock is read). The locked
 *       re-read finds the link ACTIVE and no longer past due and writes nothing: the GET answers 200 with the full
 *       ACTIVE document, the pdf the exact stored bytes, and the accept signs (201, the link straight from ACTIVE
 *       to SIGNED) - never an EXPIRED flip.</li>
 * </ul>
 * Every loser writes NOTHING (16G contract sections 5.2, 6.2, 9.4 and 10.6): after each race the committed state
 * is asserted to be EXACTLY the seed plus the winner's own documented writes - every invoice row (each version's
 * {@code last_emailed_at} included), every {@code stored_file} row of the order and every file under its storage
 * directory (no orphan either way), the {@code invoice_token} rows, the quote versions and tokens, the draft, the
 * customer / address / line rows, the payments and the {@code sales_order} row (its {@code last_emailed_at}
 * mirror included) - and the recorded messages are exactly the winner's. No service WARN or ERROR is logged.
 *
 * <p><b>Who wrote what.</b> The invoice links are sent by a second salesperson ({@value #SENDER_CODE}); D.1, the
 * quote steps, every payment and every void run as the order's salesperson ({@value #STAFF_CODE}). A remotely
 * signed version records the LINK SENDER as {@code created_by_user_id} (16G contract section 10.2) and stores the
 * SERVER-NORMALISED signature of its upload (a 7 x 3 stroke in a race, a 4 x 5 stroke in a follow-up accept),
 * while a payment or void version records {@value #STAFF_CODE}, so the creator and the signature image identify
 * the writer of every version. The current version is committed-dated on an earlier day before each race, so a
 * signing version's COPIED invoice date is visible (payment and void versions date the day they are written).
 *
 * <p><b>Fixtures - a fully self-seeded, COMMITTED tenant.</b> The requests run on worker threads with their own
 * pooled connections and real commits, so this class is NOT {@code @Transactional}: it inserts its own business
 * (slug {@value #SLUG}, ABN and bank details, no terms), store {@value #STORE_CODE} (with a notification email),
 * two salespeople and their store access, and each order (customer with a PADDED valid email, INSTALLATION +
 * BILLING, details of sale, lay date 2026-12-01 CONFIRMED, one 100.00 ex / 40.00 cost charge line) with
 * auto-commit SQL. Everything else goes through the real endpoints: D.1 create, the quote draft PUT and protected
 * send-email (an ISSUED quote with an ACTIVE link: the D5(c) target), the protected send-link (the plaintext
 * invoice token from the recorded link email, matched to its row by SHA-256) and, for the void races, a payment.
 * No V4 identity is used. It declares no bean overrides, so it shares the cached Spring context of the other
 * quote / invoice suites that write under the same storage base dir.
 *
 * <p><b>Deterministic barrier (no sleeps as barriers, no production hooks).</b> The harness of
 * {@code InvoiceVersionPreconditionConcurrencyIntegrationTest}: a HOLDER connection takes
 * {@code SELECT ... FOR NO KEY UPDATE} on the order row; the first request is submitted and observed blocked
 * behind the holder, the second observed queued behind the FIRST (through the row's tuple lock), both through
 * {@code pg_stat_activity} + {@code pg_blocking_pids} with bounded polling. Then the holder either ROLLS BACK
 * (the first request takes the row, commits or rolls back, and only then the second) or writes inside its own
 * transaction and COMMITS (publishing its change and releasing the row in one step). The remote accept (and the
 * token gate's lazy expiry of any public request) takes the lock through
 * {@code QuoteVersionRepository.lockOrderRowForPublicTransition}
 * ({@code SELECT order_id FROM sales_order WHERE order_id = :orderId FOR UPDATE}), the send-link, payment and void
 * through the scoped JPA {@code ... from sales_order ... for no key update}; the probe matches both. Every request
 * runs on its own MockMvc built with {@code springSecurity()} (the REAL filter chain), and every public request
 * goes through {@link #performPublic}, which asserts that no session exists and no {@code Set-Cookie} is sent,
 * whatever the status. The holder is always released and the worker pool always shut down (try-with-resources
 * {@link Race}); a straggler is cancelled only among the backends the race tracked.
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
class RemoteInvoiceLinkRaceIntegrationTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before any file is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant (never a V4 identity). The slug tags everything this class commits. ----
    private static final String SLUG = "pr4-remote-race-b";
    private static final String BUSINESS_NAME = "Banksia Remote Race Flooring";
    private static final String BUSINESS_ABN = "53 004 085 616";
    private static final String BUSINESS_BANK_NAME = "Banksia Race Bank";
    private static final String BUSINESS_BSB = "062-001";
    private static final String BUSINESS_ACCOUNT_NUMBER = "87654321";
    private static final String STORE_CODE = "IRB-ONE";
    private static final String STORE_EMAIL = "showroom.remote.race.b@example.com";
    /** The order's salesperson: runs D.1, the quote steps, every payment and every void. */
    private static final String STAFF_CODE = "RB1";
    private static final String STAFF_FIRST_NAME = "Rory";
    private static final String STAFF_LAST_NAME = "Banksia";
    /** Sends every invoice link: the creator of every remotely signed version (16G contract section 10.2). */
    private static final String SENDER_CODE = "RB2";
    private static final String CHARGE_CODE_PREFIX = "IRBRC";
    private static final String THREAD_NAME_PREFIX = "remote-invoice-race-b-";
    /** The locked order number format: {store_code}.{salesperson_code}.{seq5}. */
    private static final Pattern ORDER_NUMBER_FORMAT = Pattern.compile("^[A-Z0-9-]+\\.[A-Z]{2}[0-9]\\.[0-9]{5}$");

    // ---- The seeded customer ----
    private static final String CUSTOMER_FIRST_NAME = "Quinn";
    private static final String CUSTOMER_LAST_NAME = "Racewood";
    /** The D.1 freeze of the saved name: v1's customer_name_snapshot, i.e. every signing's accepted name. */
    private static final String CUSTOMER_NAME = CUSTOMER_FIRST_NAME + " " + CUSTOMER_LAST_NAME;
    private static final String CUSTOMER_EMAIL = "quinn.racewood@example.com";
    /** Saved with edge whitespace (DB-valid, app-valid once trimmed): every recipient must be the trimmed value. */
    private static final String SAVED_CUSTOMER_EMAIL = "  " + CUSTOMER_EMAIL + " ";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String BILLING_LINE_1 = "12 Race Street";
    private static final String BILLING_LINE_2 = "Burwood NSW 2134";
    private static final String DETAILS_OF_SALE = "Supply and lay carpet (remote invoice link race, part B)";

    // ---- Dates and money ----
    /** The current version is committed-dated on this earlier day before each race: a signing version COPIES it. */
    private static final LocalDate SIGNED_FROM_INVOICE_DATE = LocalDate.of(2026, 9, 15);
    /** proposed_lay_date 2026-12-01 minus 2 days. */
    private static final LocalDate EXPECTED_DUE_DATE = LocalDate.of(2026, 11, 29);
    /** The D.1 v1: 100.00 ex + GST = 110.00 inc; payments and voids never change the sale price. */
    private static final String SALE_EX_GST = "100.00";
    private static final String GST_AMOUNT = "10.00";
    private static final String SALE_INC_GST = "110.00";
    private static final String INC_GST_DISPLAY = "$110.00";
    private static final String PAYMENT_AMOUNT = "50.00";
    private static final String NOTHING_PAID = "0.00";
    private static final String BALANCE_AFTER_PAYMENT = "60.00";

    // The ISSUED quote v1 (itemised 250.00 ex / 275.00 inc): the D5(c) target of every remote signing.
    private static final String ITEMISED_DRAFT = """
            {"itemised": true, "lines": [
              {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
              {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
            ]}""";

    // ---- Messages (exact) ----
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String INVOICE_LINK_SENT_MESSAGE = "Invoice link emailed to the customer.";
    private static final String REMOTE_ACCEPTED_MESSAGE = "Invoice accepted.";
    private static final String INVOICE_LOADED_MESSAGE = "Invoice loaded.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String PAYMENT_VOIDED_MESSAGE = "Payment voided. Current invoice updated.";
    private static final String QUOTE_SENT_MESSAGE = "Quote sent by email.";
    private static final String INVOICE_VERSION_STALE_MESSAGE = "Invoice updated, please refresh";
    private static final String INVOICE_ALREADY_ACCEPTED_MESSAGE =
            "This invoice has already been accepted. Use Re-send to email it again.";
    private static final String INVOICE_LINK_REPLACED_MESSAGE =
            "This invoice link has been replaced. Please use the latest invoice link.";
    private static final String INVOICE_LINK_REWRITTEN_MESSAGE =
            "This invoice has been revised. Please contact the store for the updated invoice.";
    private static final String INVOICE_LINK_SIGNED_MESSAGE =
            "This invoice has already been signed. Please contact the store if you need a copy.";
    private static final String INVOICE_LINK_EXPIRED_MESSAGE =
            "This invoice link has expired. Please contact the store.";

    // ---- The public surface ----
    private static final String PUBLIC_INVOICES = "/api/v1/public/invoices/";
    private static final String PUBLIC_BASE_URL = "http://localhost:5173";
    /** The public link inside the link-only invoice email: <app-base>/i/{43-char token}. */
    private static final Pattern INVOICE_LINK_PATTERN = Pattern.compile("/i/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    /** The 27 keys of PublicInvoiceView, always present (16G contract section 9.3). */
    private static final Set<String> PUBLIC_VIEW_KEYS = new TreeSet<>(List.of(
            "state", "business_name", "business_logo_url", "accent_color", "business_abn",
            "payment_account_name", "payment_bank_name", "payment_bsb", "payment_account_number",
            "payment_stripe_link_url", "order_number", "flooring_type", "version_number", "invoice_date",
            "due_date", "customer_name", "customer_address_line1", "customer_address_line2", "details_of_sale",
            "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst", "total_paid", "balance_due", "terms_html",
            "expires_at", "message"));

    // ---- Signatures: each signing uploads a DIFFERENT image, so the stored image names the signing ----
    /** The racing remote accept's upload (7 x 3): stored only as its server-normalised PNG, and only if it signs. */
    private static final byte[] REMOTE_SIGNATURE_PNG = strokePng(7, 3);
    /** A follow-up remote accept's upload (4 x 5), after a race whose remote accept lost. */
    private static final byte[] FOLLOW_UP_SIGNATURE_PNG = strokePng(4, 5);
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND_CHUNK = {
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    // Exact money parsing for response bodies (never a double round-trip).
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    // InvoiceDetail / send-link / public view timestamps are formatted to the second.
    private static final DateTimeFormatter JSON_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
    // The signed-PDF caption and the store notification format; the invoice PDF date format.
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    // Barrier / harness bounds. The poll interval is NOT a barrier - the barrier is the DB condition.
    private static final long BARRIER_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 10L;
    private static final long RESPONSE_TIMEOUT_SECONDS = 30L;
    private static final long POOL_TERMINATION_SECONDS = 15L;

    /**
     * Backends of THIS database currently waiting on a heavyweight lock held (or queued ahead) by the given pid,
     * while executing an order-row lock statement: the JPA {@code ... from sales_order ... for no key update} of
     * send-link / payment / void, or the public {@code SELECT order_id FROM sales_order ... FOR UPDATE} (the
     * accept's, and the token gate's lazy expiry). Runs auto-commit on the test thread, so every poll sees a fresh
     * {@code pg_stat_activity}.
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

    /**
     * Whether the given backend's OPEN transaction already holds a lock on {@code invoice_token}: it READ the link
     * before it queued on the order row. The token gate reads the link by hash and only then takes the order lock
     * for a lazy expiry, while the accept's signing transaction takes the order lock as its FIRST statement - so a
     * public request queued on the order row while holding this lock is parked in its token gate.
     */
    private static final String HOLDS_INVOICE_TOKEN_LOCK_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM pg_locks
                WHERE pid = ? AND locktype = 'relation' AND relation = 'invoice_token'::regclass AND granted)
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

    /**
     * The lock holder's newer invoice version: a copy of the given version as the given next version number,
     * with its own {@code stored_file} - what a Rewrite or a payment appends (unsigned, unemailed). Published
     * only when the holder commits.
     */
    private static final String HOLDER_APPEND_COPY_OF_VERSION_SQL = """
            INSERT INTO invoice (order_id, version_number, invoice_date, due_date, details_of_sale_snapshot,
                                 sale_price_ex_gst, sale_price_inc_gst, total_paid, balance_due, stored_file_id,
                                 created_by_user_id, source_quote_version_id, terms_snapshot,
                                 customer_name_snapshot, customer_address_line1_snapshot,
                                 customer_address_line2_snapshot)
            SELECT order_id, ?, invoice_date, due_date, details_of_sale_snapshot, sale_price_ex_gst,
                   sale_price_inc_gst, total_paid, balance_due, ?, created_by_user_id, source_quote_version_id,
                   terms_snapshot, customer_name_snapshot, customer_address_line1_snapshot,
                   customer_address_line2_snapshot
            FROM invoice
            WHERE order_id = ? AND version_number = ?
            """;

    private static final String HOLDER_INSERT_PDF_FILE_SQL = """
            INSERT INTO stored_file (file_name, storage_path, mime_type, file_size)
            VALUES (?, ?, 'application/pdf', ?)
            RETURNING stored_file_id
            """;

    /**
     * The lock holder makes the ACTIVE link's expiry fall due: LEAST(DB now, JVM now) - 1 minute is past due on
     * BOTH clocks, so the service's JVM-clock check cannot be fooled by DB / JVM skew. Status-guarded. Also run
     * auto-committed before a race, to seed a link that is ALREADY over-age when the public request arrives.
     */
    private static final String HOLDER_BACKDATE_LINK_EXPIRY_SQL = """
            UPDATE invoice_token
            SET expires_at = LEAST(now()::timestamp, CAST(? AS timestamp)) - interval '1 minute'
            WHERE invoice_token_id = ? AND status = 'ACTIVE'
            """;

    /**
     * The lock holder kills the ACTIVE link as REWRITTEN (what a Rewrite does in its own transaction) at a given
     * time, and its expires_at is ALSO past due on both clocks. Status-guarded.
     */
    private static final String HOLDER_KILL_LINK_REWRITTEN_PAST_EXPIRY_SQL = """
            UPDATE invoice_token
            SET status = 'REWRITTEN', dead_at = ?,
                expires_at = LEAST(now()::timestamp, CAST(? AS timestamp)) - interval '1 minute'
            WHERE invoice_token_id = ? AND status = 'ACTIVE'
            """;

    /**
     * TEST FIXTURE ONLY - {@code expires_at} never changes in production: the lock holder moves the over-age ACTIVE
     * link's expiry to the given FUTURE time, so a token gate parked on the order lock re-reads a link that is no
     * longer past due - what it would see if the JVM wall clock stepped back while it waited (an NTP step, the
     * daylight-saving fall-back), without touching the machine clock. Status-guarded. Limit: this pins that the
     * locked re-read compares the time with the RE-READ row and writes nothing when it is no longer past due; it
     * cannot show that the clock is read after the lock (there is no clock seam) - that rests on the order of the
     * statements in {@code PublicInvoiceService.expireUnderLock}.
     */
    private static final String HOLDER_MOVE_LINK_EXPIRY_INTO_THE_FUTURE_SQL = """
            UPDATE invoice_token
            SET expires_at = ?
            WHERE invoice_token_id = ? AND status = 'ACTIVE'
            """;

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

    /** WARN / ERROR capture on the services the races run through (attached for the whole test). */
    private ServiceLogs serviceLogs;

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
        serviceLogs = new ServiceLogs(PublicInvoiceService.class, OrderInvoiceService.class,
                OrderPaymentService.class, GlobalExceptionHandler.class);
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
            if (serviceLogs != null) {
                serviceLogs.close();
            }
        }
    }

    // ================================================================
    // 1. Remote accept vs payment (D.7) - both lock orders
    // ================================================================

    /**
     * The REMOTE ACCEPT locks first: it is observed waiting on the holder, then a payment is observed queued
     * BEHIND it; the holder rolls back. The remote accept takes the row, signs v1 into v2 (copying v1's invoice
     * date, money and words; the link SIGNED at the signing time; the ISSUED quote and its link cancelled by
     * D5(c)), commits, then emails v2's signed PDF and notifies the store. The payment then takes the row and
     * appends v3 on top of the SIGNED v2: the acceptance and the same signature carried forward (the customer
     * does not re-sign), 50.00 paid, 60.00 due, no email. A payment never touches the link, so the dead link stays
     * SIGNED - on the public read too. The final mirror follows the current (unemailed) v3: null.
     */
    @Test
    void remoteAcceptVsPayment_remoteLocksFirst_remoteSignsV2_paymentAppendsV3CarryingTheSignature_linkStaysSigned()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> linkBefore = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);
        LocalDate dayBefore = LocalDate.now();
        LocalDateTime raceStart = LocalDateTime.now();

        MvcResult remoteResult;
        MvcResult paymentResult;
        try (Race race = new Race(orderId)) {
            Future<MvcResult> remote = race.submitPublic(remoteAccept(order.token(), 1, REMOTE_SIGNATURE_PNG));
            int remotePid = awaitWaiterBlockedBy(race, race.holderPid(), "remote accept (carrying v1)", remote);
            Future<MvcResult> payment = race.submitStaff(recordPayment(orderId));
            int paymentPid = awaitWaiterBlockedBy(race, remotePid, "payment (D.7)", payment, remote);
            assertQueuedInOrder(race.holderPid(), remotePid, paymentPid, remote, payment);
            Assertions.assertEquals(linkBefore, invoiceTokenRow(order.invoiceTokenId()),
                    "the remote accept passed its gate on the ACTIVE link and wrote nothing before the lock");

            race.rollbackHolder(); // the holder wrote nothing: the remote accept takes the row first

            remoteResult = awaitResponse(remote, "remote accept (carrying v1)");
            paymentResult = awaitResponse(payment, "payment (D.7)");
        }
        LocalDateTime raceEnd = LocalDateTime.now();
        LocalDate dayAfter = LocalDate.now();

        // The remote accept (queue head) signed v1 into v2 and committed before the payment took the row.
        assertRemoteAccepted(remoteResult, "remote accept (queue head)");
        // The payment then appended v3 on top of the SIGNED v2.
        JsonNode paymentData = assertPaymentMutation(paymentResult, PAYMENT_RECORDED_MESSAGE, 3, PAYMENT_AMOUNT,
                BALANCE_AFTER_PAYMENT, true, "payment (queued behind the remote accept)");

        Assertions.assertEquals(List.of(1, 2, 3), invoiceVersions(orderId),
                "exactly v1, the remote signing's v2 and the payment's v3");
        SignedVersion signed = assertRemoteSignedVersion(order, invoiceV1, REMOTE_SIGNATURE_PNG, raceStart, raceEnd,
                "the remote signing (queue head)");
        CarriedVersion paid = assertCarriedVersion(order, signed.row(), 3, PAYMENT_AMOUNT, BALANCE_AFTER_PAYMENT,
                dayBefore, dayAfter, "the payment's v3 (after the remote signing)");
        Assertions.assertEquals(longValue(paid.row().get("invoice_id")),
                paymentData.path("current_invoice").path("invoice_id").asLong(),
                "the payment's response describes its own v3");
        assertAcceptedCaption(paid.pdfBytes(), CUSTOMER_NAME, signed.acceptedAt().toLocalDateTime(),
                "the payment's regenerated v3 PDF carries the remote acceptance");

        // The link died as SIGNED in the remote's own transaction, at the signing time; the payment never touched it.
        Map<String, Object> expectedLink = withColumns(linkBefore, "status", "SIGNED", "dead_at", signed.acceptedAt());
        OrderSnapshot after = snapshot(orderId);
        Map<String, Object> paymentRow = assertSinglePayment(after, PAYMENT_AMOUNT, "the race's payment");
        assertSnapshot(plusCarriedVersion(withD5cApplied(plusSignedVersion(before, signed), order, signed.acceptedAt()),
                                paid)
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id", expectedLink))
                        .withPayments(List.of(paymentRow)),
                after, "after the remote signing (v2) and the payment (v3)");
        Assertions.assertNull(after.header().get("last_emailed_at"),
                "the mirror follows the CURRENT version - the payment's unemailed v3 - while v2 keeps its own stamp");

        // Messages: exactly the remote signing's signed-PDF email of v2 and its store notification.
        assertRemoteSigningMessages(order, signed, "the remote signing and the payment");
        assertNoServiceWarnings("the remote signing and the payment");

        // The dead link stays SIGNED: the public read shows the minimal SIGNED state and writes nothing; the
        // staff read reports no live link.
        assertPublicDeadView(order.token(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE, "public read after the race");
        assertStaffInvoice(orderId, 3, true, null, "D.3 after the race");
        Assertions.assertEquals(expectedLink, invoiceTokenRow(order.invoiceTokenId()),
                "a dead link never changes again (the public read included)");
    }

    /**
     * The PAYMENT locks first: it is observed waiting on the holder, then the remote accept still carrying v1 is
     * observed queued BEHIND it; the holder rolls back. The payment appends v2 (50.00 paid, 60.00 due) and commits
     * while the remote accept waits; the remote accept then takes the lock, re-reads its link (ACTIVE), reads the
     * current invoice there (the payment's v2) and refuses the v1 it carries: 409 {@code INVOICE_VERSION_STALE}.
     * Nothing of the remote accept is written (no signature, PDF, version, token change, D5(c), email or
     * notification). The link survives the payment: the next public read shows v2 with its new amounts, the staff
     * read still reports the link, and a fresh accept carrying v2 then signs it into v3.
     */
    @Test
    void remoteAcceptVsPayment_paymentLocksFirst_paymentAppendsV2_remoteCarryingV1Gets409InvoiceVersionStale_linkStaysActive_freshAcceptOfV2Signs()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> linkBefore = invoiceTokenRow(order.invoiceTokenId());
        Timestamp linkExpiresAt = (Timestamp) linkBefore.get("expires_at");
        OrderSnapshot before = snapshot(orderId);
        LocalDate dayBefore = LocalDate.now();

        MvcResult paymentResult;
        MvcResult remoteResult;
        try (Race race = new Race(orderId)) {
            Future<MvcResult> payment = race.submitStaff(recordPayment(orderId));
            int paymentPid = awaitWaiterBlockedBy(race, race.holderPid(), "payment (D.7)", payment);
            Future<MvcResult> remote = race.submitPublic(remoteAccept(order.token(), 1, REMOTE_SIGNATURE_PNG));
            int remotePid = awaitWaiterBlockedBy(race, paymentPid, "remote accept (carrying v1)", remote, payment);
            assertQueuedInOrder(race.holderPid(), paymentPid, remotePid, payment, remote);

            race.rollbackHolder(); // the holder wrote nothing: the payment takes the row first

            paymentResult = awaitResponse(payment, "payment (D.7)");
            remoteResult = awaitResponse(remote, "remote accept (carrying v1)");
        }
        LocalDate dayAfter = LocalDate.now();

        assertPaymentMutation(paymentResult, PAYMENT_RECORDED_MESSAGE, 2, PAYMENT_AMOUNT, BALANCE_AFTER_PAYMENT, false,
                "payment (queue head)");
        // The remote accept took the lock only after v2 was committed, read the current invoice there and refused
        // the v1 it carries - the authoritative comparison under the lock.
        assertRefusal(remoteResult, 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "remote accept (carrying v1, v2 committed by the payment queued ahead of it)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId),
                "no remote version: exactly v1 and the payment's v2");
        CarriedVersion paid = assertCarriedVersion(order, invoiceV1, 2, PAYMENT_AMOUNT, BALANCE_AFTER_PAYMENT,
                dayBefore, dayAfter, "the payment's v2");
        OrderSnapshot after = snapshot(orderId);
        Map<String, Object> paymentRow = assertSinglePayment(after, PAYMENT_AMOUNT, "the race's payment");
        // Expected = the seed + exactly the payment's writes: the link row, the ISSUED quote and its link, the
        // header (mirror) are exactly as seeded.
        assertSnapshot(plusCarriedVersion(before, paid).withPayments(List.of(paymentRow)), after,
                "after the payment's v2 and the refused remote accept");
        assertNothingOfTheRefusedRemote(order, 3, "the refused remote accept (stale v1)");
        assertNoMessagesAtAll("the payment and the refused remote accept");
        assertNoServiceWarnings("the payment and the refused remote accept");

        // The link survived the payment (16G contract section 7.5): the public read shows the payment's v2 and
        // its amounts, and the staff read still reports the ACTIVE, unexpired link.
        assertPublicActiveView(order, 2, PAYMENT_AMOUNT, BALANCE_AFTER_PAYMENT, linkExpiresAt,
                "public read after the refused remote accept");
        assertStaffInvoice(orderId, 2, false, linkExpiresAt, "D.3 after the refused remote accept");

        // A fresh accept carrying v2 - the version now shown - signs it.
        signThroughTheLinkAndAssert(order, order.token(), order.invoiceTokenId(), 2, "fresh accept carrying v2");
    }

    // ================================================================
    // 2. Remote accept vs payment void (D.10) - both lock orders
    // ================================================================

    /**
     * The REMOTE ACCEPT locks first (the link already shows the payment's v2: 50.00 paid): it is observed waiting
     * on the holder, then the void of that payment is observed queued BEHIND it; the holder rolls back. The remote
     * accept signs v2 into v3 (copying v2: 50.00 paid, 60.00 due, its invoice date), the link SIGNED, D5(c); the
     * void then takes the row, voids the payment and appends v4 carrying v3's acceptance and signature (nothing
     * paid, 110.00 due, no email). The void never touches the link: it stays SIGNED.
     */
    @Test
    void remoteAcceptVsVoid_remoteLocksFirst_remoteSignsV3_voidAppendsV4CarryingTheSignature_linkStaysSigned()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(true);
        long orderId = order.orderId();
        Map<String, Object> paidV2 = invoiceRow(orderId, 2);
        Map<String, Object> linkBefore = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);
        Map<String, Object> paymentBefore = singleRow(before.payments(), "the seeded payment");
        LocalDate dayBefore = LocalDate.now();
        LocalDateTime raceStart = LocalDateTime.now();

        MvcResult remoteResult;
        MvcResult voidResult;
        try (Race race = new Race(orderId)) {
            Future<MvcResult> remote = race.submitPublic(remoteAccept(order.token(), 2, REMOTE_SIGNATURE_PNG));
            int remotePid = awaitWaiterBlockedBy(race, race.holderPid(), "remote accept (carrying v2)", remote);
            Future<MvcResult> voiding = race.submitStaff(voidPayment(orderId, order.paymentId()));
            int voidPid = awaitWaiterBlockedBy(race, remotePid, "payment void (D.10)", voiding, remote);
            assertQueuedInOrder(race.holderPid(), remotePid, voidPid, remote, voiding);
            Assertions.assertEquals(linkBefore, invoiceTokenRow(order.invoiceTokenId()),
                    "the remote accept passed its gate on the ACTIVE link and wrote nothing before the lock");

            race.rollbackHolder(); // the holder wrote nothing: the remote accept takes the row first

            remoteResult = awaitResponse(remote, "remote accept (carrying v2)");
            voidResult = awaitResponse(voiding, "payment void (D.10)");
        }
        LocalDateTime raceEnd = LocalDateTime.now();
        LocalDate dayAfter = LocalDate.now();

        assertRemoteAccepted(remoteResult, "remote accept (queue head)");
        JsonNode voidData = assertPaymentMutation(voidResult, PAYMENT_VOIDED_MESSAGE, 4, NOTHING_PAID, SALE_INC_GST,
                true, "payment void (queued behind the remote accept)");
        assertVoidedPaymentTransaction(voidData.path("payment_transaction"), order.paymentId(),
                "payment void (queued behind the remote accept)");

        Assertions.assertEquals(List.of(1, 2, 3, 4), invoiceVersions(orderId),
                "exactly v1, the seeded payment's v2, the remote signing's v3 and the void's v4");
        SignedVersion signed = assertRemoteSignedVersion(order, paidV2, REMOTE_SIGNATURE_PNG, raceStart, raceEnd,
                "the remote signing (queue head)");
        CarriedVersion voided = assertCarriedVersion(order, signed.row(), 4, NOTHING_PAID, SALE_INC_GST,
                dayBefore, dayAfter, "the void's v4 (after the remote signing)");
        assertAcceptedCaption(voided.pdfBytes(), CUSTOMER_NAME, signed.acceptedAt().toLocalDateTime(),
                "the void's regenerated v4 PDF carries the remote acceptance");

        Map<String, Object> expectedLink = withColumns(linkBefore, "status", "SIGNED", "dead_at", signed.acceptedAt());
        OrderSnapshot after = snapshot(orderId);
        Map<String, Object> voidedPayment = assertVoidedPaymentRow(after, paymentBefore, raceStart, raceEnd,
                "the voided payment");
        assertSnapshot(plusCarriedVersion(withD5cApplied(plusSignedVersion(before, signed), order, signed.acceptedAt()),
                                voided)
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id", expectedLink))
                        .withPayments(List.of(voidedPayment)),
                after, "after the remote signing (v3) and the void (v4)");
        Assertions.assertNull(after.header().get("last_emailed_at"),
                "the mirror follows the CURRENT version - the void's unemailed v4 - while v3 keeps its own stamp");

        assertRemoteSigningMessages(order, signed, "the remote signing and the void");
        assertNoServiceWarnings("the remote signing and the void");

        assertPublicDeadView(order.token(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE, "public read after the race");
        assertStaffInvoice(orderId, 4, true, null, "D.3 after the race");
        Assertions.assertEquals(expectedLink, invoiceTokenRow(order.invoiceTokenId()),
                "a dead link never changes again (the public read included)");
    }

    /**
     * The VOID locks first: it is observed waiting on the holder, then the remote accept carrying v2 (the
     * payment's version its page showed) is observed queued BEHIND it; the holder rolls back. The void voids the
     * payment and appends v3 (nothing paid, 110.00 due) while the remote accept waits; the remote accept then reads
     * the current invoice under the lock (v3) and refuses the v2 it carries: 409 {@code INVOICE_VERSION_STALE},
     * nothing of it written. The link survives the void: the next public read shows v3, and a fresh accept
     * carrying v3 then signs it into v4.
     */
    @Test
    void remoteAcceptVsVoid_voidLocksFirst_voidAppendsV3_remoteCarryingV2Gets409InvoiceVersionStale_linkStaysActive_freshAcceptOfV3Signs()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(true);
        long orderId = order.orderId();
        Map<String, Object> paidV2 = invoiceRow(orderId, 2);
        Map<String, Object> linkBefore = invoiceTokenRow(order.invoiceTokenId());
        Timestamp linkExpiresAt = (Timestamp) linkBefore.get("expires_at");
        OrderSnapshot before = snapshot(orderId);
        Map<String, Object> paymentBefore = singleRow(before.payments(), "the seeded payment");
        LocalDate dayBefore = LocalDate.now();
        LocalDateTime raceStart = LocalDateTime.now();

        MvcResult voidResult;
        MvcResult remoteResult;
        try (Race race = new Race(orderId)) {
            Future<MvcResult> voiding = race.submitStaff(voidPayment(orderId, order.paymentId()));
            int voidPid = awaitWaiterBlockedBy(race, race.holderPid(), "payment void (D.10)", voiding);
            Future<MvcResult> remote = race.submitPublic(remoteAccept(order.token(), 2, REMOTE_SIGNATURE_PNG));
            int remotePid = awaitWaiterBlockedBy(race, voidPid, "remote accept (carrying v2)", remote, voiding);
            assertQueuedInOrder(race.holderPid(), voidPid, remotePid, voiding, remote);

            race.rollbackHolder(); // the holder wrote nothing: the void takes the row first

            voidResult = awaitResponse(voiding, "payment void (D.10)");
            remoteResult = awaitResponse(remote, "remote accept (carrying v2)");
        }
        LocalDateTime raceEnd = LocalDateTime.now();
        LocalDate dayAfter = LocalDate.now();

        JsonNode voidData = assertPaymentMutation(voidResult, PAYMENT_VOIDED_MESSAGE, 3, NOTHING_PAID, SALE_INC_GST,
                false, "payment void (queue head)");
        assertVoidedPaymentTransaction(voidData.path("payment_transaction"), order.paymentId(),
                "payment void (queue head)");
        assertRefusal(remoteResult, 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "remote accept (carrying v2, v3 committed by the void queued ahead of it)");

        Assertions.assertEquals(List.of(1, 2, 3), invoiceVersions(orderId),
                "no remote version: exactly v1, the seeded payment's v2 and the void's v3");
        CarriedVersion voided = assertCarriedVersion(order, paidV2, 3, NOTHING_PAID, SALE_INC_GST,
                dayBefore, dayAfter, "the void's v3");
        OrderSnapshot after = snapshot(orderId);
        Map<String, Object> voidedPayment = assertVoidedPaymentRow(after, paymentBefore, raceStart, raceEnd,
                "the voided payment");
        assertSnapshot(plusCarriedVersion(before, voided).withPayments(List.of(voidedPayment)), after,
                "after the void's v3 and the refused remote accept");
        assertNothingOfTheRefusedRemote(order, 4, "the refused remote accept (stale v2)");
        assertNoMessagesAtAll("the void and the refused remote accept");
        assertNoServiceWarnings("the void and the refused remote accept");

        assertPublicActiveView(order, 3, NOTHING_PAID, SALE_INC_GST, linkExpiresAt,
                "public read after the refused remote accept");
        assertStaffInvoice(orderId, 3, false, linkExpiresAt, "D.3 after the refused remote accept");

        // A fresh accept carrying v3 - the version now shown - signs it.
        signThroughTheLinkAndAssert(order, order.token(), order.invoiceTokenId(), 3, "fresh accept carrying v3");
    }

    // ================================================================
    // 3. Send-link vs remote accept - both lock orders
    // ================================================================

    /**
     * The SEND-LINK (Accept Remotely used again) locks first: it is observed waiting on the holder, then the remote
     * accept presenting the OLD link is observed queued BEHIND it; the holder rolls back. The send marks the old
     * link REPLACED (dead at the new link's send time), mints a new ACTIVE link, commits, emails it link-only and
     * stamps that link's {@code emailed_at}. The remote accept then takes the lock, re-reads the token it presented
     * - REPLACED - and gets 410 {@code INVOICE_LINK_REPLACED}: no invoice, file, quote or message of its own. The
     * new link is the order's only ACTIVE link (the staff read reports it); signing through it then works, and
     * the replaced link keeps its own reason.
     */
    @Test
    void sendLinkVsRemoteAccept_sendLocksFirst_oldLinkReplacedNewLinkActive_remoteWithTheOldLinkGets410InvoiceLinkReplaced_writesNothing()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> linkBefore = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);
        LocalDateTime raceStart = LocalDateTime.now();

        MvcResult sendResult;
        MvcResult remoteResult;
        try (Race race = new Race(orderId)) {
            Future<MvcResult> send = race.submitStaff(sendLink(orderId));
            int sendPid = awaitWaiterBlockedBy(race, race.holderPid(), "send-link (Accept Remotely again)", send);
            Future<MvcResult> remote = race.submitPublic(remoteAccept(order.token(), 1, REMOTE_SIGNATURE_PNG));
            int remotePid = awaitWaiterBlockedBy(race, sendPid, "remote accept with the old link", remote, send);
            assertQueuedInOrder(race.holderPid(), sendPid, remotePid, send, remote);
            Assertions.assertEquals(linkBefore, invoiceTokenRow(order.invoiceTokenId()),
                    "the remote accept passed its gate while its link was still ACTIVE");

            race.rollbackHolder(); // the holder wrote nothing: the send takes the row first

            sendResult = awaitResponse(send, "send-link (Accept Remotely again)");
            remoteResult = awaitResponse(remote, "remote accept with the old link");
        }
        LocalDateTime raceEnd = LocalDateTime.now();

        // The send (queue head) replaced the link and emailed the new one, exactly once.
        SentLink replacement = assertLinkSent(sendResult, orderId, order.orderNumber(), 1, raceStart, raceEnd,
                "send-link (queue head)");
        Assertions.assertNotEquals(order.token(), replacement.token(), "a send mints a NEW token");
        // The remote accept re-read its OLD token under the lock: REPLACED.
        assertRefusal(remoteResult, 410, ErrorCode.INVOICE_LINK_REPLACED, INVOICE_LINK_REPLACED_MESSAGE,
                "remote accept with the old link (queued behind the send)");

        Timestamp replacedAt = (Timestamp) replacement.row().get("sent_at");
        Map<String, Object> expectedOld = withColumns(linkBefore, "status", "REPLACED", "dead_at", replacedAt);
        Assertions.assertEquals(List.of(expectedOld, replacement.row()), invoiceTokenRows(orderId),
                "the old link REPLACED (dead at the new send time), then the new ACTIVE link - and no other token");
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM invoice_token WHERE order_id = ? AND status = 'ACTIVE'",
                orderId), "exactly one ACTIVE link for the order");
        // Everything else exactly as seeded: no invoice version, file, quote change, payment or mirror move.
        assertSnapshot(before.withInvoiceTokens(List.of(expectedOld, replacement.row())), snapshot(orderId),
                "after the replacing send and the refused remote accept");
        assertNothingOfTheRefusedRemote(order, 2, "the refused remote accept (replaced link)");
        // Messages: exactly the new link's email (asserted above); no signed-PDF email, notification or quote message.
        assertNoSignedInvoiceMessages("the replacing send and the refused remote accept");
        assertNoQuoteOrSmsMessages("the replacing send and the refused remote accept");
        assertNoServiceWarnings("the replacing send and the refused remote accept");

        // The old link reads REPLACED; the new link shows the current v1; the staff read reports the new link.
        assertPublicDeadView(order.token(), "REPLACED", INVOICE_LINK_REPLACED_MESSAGE, "public read of the old link");
        assertPublicActiveView(order, 1, NOTHING_PAID, SALE_INC_GST, (Timestamp) replacement.row().get("expires_at"),
                replacement.token(), "public read of the new link");
        assertStaffInvoice(orderId, 1, false, (Timestamp) replacement.row().get("expires_at"),
                "D.3 after the replacing send");

        // Signing through the new link kills it as SIGNED; the replaced link keeps its own reason.
        resetSenders();
        signThroughTheLinkAndAssert(order, replacement.token(), replacement.invoiceTokenId(), 1,
                "accept through the new link");
        Assertions.assertEquals(expectedOld, invoiceTokenRow(order.invoiceTokenId()),
                "the REPLACED link never changes again");
        assertPublicDeadView(order.token(), "REPLACED", INVOICE_LINK_REPLACED_MESSAGE,
                "public read of the old link after the signing");
    }

    /**
     * The REMOTE ACCEPT locks first: it is observed waiting on the holder, then a send-link is observed queued
     * BEHIND it; the holder rolls back. The remote accept signs v1 into v2 (its link SIGNED, D5(c)) and commits;
     * the send then takes the lock, finds a SIGNED current invoice and is refused with 409
     * {@code INVOICE_ALREADY_ACCEPTED} (the default staff wording): no token row is inserted, the SIGNED link is
     * untouched and no link email is sent or attempted. The mirror follows the current, emailed v2.
     */
    @Test
    void sendLinkVsRemoteAccept_remoteLocksFirst_remoteSignsV2_sendGets409InvoiceAlreadyAccepted_noNewTokenNoLinkEmail()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> linkBefore = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);
        LocalDateTime raceStart = LocalDateTime.now();

        MvcResult remoteResult;
        MvcResult sendResult;
        try (Race race = new Race(orderId)) {
            Future<MvcResult> remote = race.submitPublic(remoteAccept(order.token(), 1, REMOTE_SIGNATURE_PNG));
            int remotePid = awaitWaiterBlockedBy(race, race.holderPid(), "remote accept (carrying v1)", remote);
            Future<MvcResult> send = race.submitStaff(sendLink(orderId));
            int sendPid = awaitWaiterBlockedBy(race, remotePid, "send-link (Accept Remotely again)", send, remote);
            assertQueuedInOrder(race.holderPid(), remotePid, sendPid, remote, send);

            race.rollbackHolder(); // the holder wrote nothing: the remote accept takes the row first

            remoteResult = awaitResponse(remote, "remote accept (carrying v1)");
            sendResult = awaitResponse(send, "send-link (Accept Remotely again)");
        }
        LocalDateTime raceEnd = LocalDateTime.now();

        assertRemoteAccepted(remoteResult, "remote accept (queue head)");
        // The send took the lock only after the signing committed: a signed current invoice is never sent.
        assertRefusal(sendResult, 409, ErrorCode.INVOICE_ALREADY_ACCEPTED, INVOICE_ALREADY_ACCEPTED_MESSAGE,
                "send-link (queued behind the remote signing)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId), "exactly v1 and the remote signing's v2");
        SignedVersion signed = assertRemoteSignedVersion(order, invoiceV1, REMOTE_SIGNATURE_PNG, raceStart, raceEnd,
                "the remote signing (queue head)");
        Map<String, Object> expectedLink = withColumns(linkBefore, "status", "SIGNED", "dead_at", signed.acceptedAt());
        Assertions.assertEquals(List.of(expectedLink), invoiceTokenRows(orderId),
                "the refused send inserted no token row: the order's only link is the SIGNED one");
        assertSnapshot(withD5cApplied(plusSignedVersion(before, signed), order, signed.acceptedAt())
                        .withHeader(withColumns(before.header(), "last_emailed_at", signed.stamp()))
                        .withInvoiceTokens(List.of(expectedLink)),
                snapshot(orderId), "after the remote signing (v2) and the refused send");

        // Messages: exactly the remote signing's email and notification; no link email sent or attempted.
        assertRemoteSigningMessages(order, signed, "the remote signing and the refused send");
        assertNoServiceWarnings("the remote signing and the refused send");

        assertPublicDeadView(order.token(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE, "public read after the race");
        assertStaffInvoice(orderId, 2, true, null, "D.3 after the race");
    }

    // ================================================================
    // 4. A newer version committed by the lock holder while the remote accept waits
    // ================================================================

    /**
     * The remote accept carrying v1 (the version its page showed) is observed waiting on the holder; INSIDE the
     * holder's transaction invoice v2 is appended (a copy of v1 with its own real PDF, invisible to the accept until
     * the commit) and the holder COMMITS, which publishes v2 and releases the row in one step. The remote accept
     * then takes the lock, re-reads its link (ACTIVE, unexpired), reads the current invoice (v2) and refuses the v1
     * it carries: 409 {@code INVOICE_VERSION_STALE}. v2 is unsigned and its frozen name is set, so the staleness is
     * the only reason. Nothing of the accept is written and the link stays ACTIVE (now showing v2).
     */
    @Test
    void remoteAccept_newerVersionCommittedByTheLockHolderWhileItWaits_409InvoiceVersionStale_writesNothing_linkStaysActive()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> linkBefore = invoiceTokenRow(order.invoiceTokenId());
        Timestamp linkExpiresAt = (Timestamp) linkBefore.get("expires_at");
        OrderSnapshot before = snapshot(orderId);
        // The holder's v2 gets its own real PDF file (v1's bytes), so every stored_file keeps its file on disk.
        byte[] holderPdf = fileStorageService.read(storagePathOf(longValue(invoiceV1.get("stored_file_id"))));
        String holderPdfPath = fileStorageService.store(holderPdf, businessId, orderId, "pdf");

        MvcResult remoteResult;
        long holderFileId;
        try (Race race = new Race(orderId)) {
            Future<MvcResult> remote = race.submitPublic(remoteAccept(order.token(), 1, REMOTE_SIGNATURE_PNG));
            int remotePid = awaitWaiterBlockedBy(race, race.holderPid(), "remote accept (carrying v1)", remote);

            // INSIDE the holder transaction, while the accept waits on it: append invoice v2.
            holderFileId = race.queryLongInHolder(HOLDER_INSERT_PDF_FILE_SQL,
                    invoicePdfName(order.orderNumber(), 2), holderPdfPath, (long) holderPdf.length);
            Assertions.assertEquals(1, race.updateInHolder(HOLDER_APPEND_COPY_OF_VERSION_SQL, 2, holderFileId,
                    orderId, 1), "the holder appends exactly invoice v2");
            Assertions.assertEquals(List.of(1), invoiceVersions(orderId),
                    "the holder's v2 is invisible to every other transaction until it commits");
            Assertions.assertFalse(remote.isDone(), "the remote accept must still be parked on the order lock");
            Assertions.assertTrue(isBlockedBy(remotePid, race.holderPid()),
                    "the remote accept must still be waiting on the holder; activity: " + activity());

            race.commitHolder(); // publishes v2 AND releases the order row in one step

            remoteResult = awaitResponse(remote, "remote accept (carrying v1)");
        }

        assertRefusal(remoteResult, 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "remote accept (carrying v1, v2 committed by the holder while it waited)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId),
                "no remote version: exactly v1 and the holder's v2");
        Map<String, Object> holderV2 = invoiceRow(orderId, 2);
        Assertions.assertEquals(withColumns(invoiceV1, "invoice_id", holderV2.get("invoice_id"), "version_number", 2,
                        "stored_file_id", holderFileId, "created_at", holderV2.get("created_at")),
                holderV2, "invoice v2 is exactly the holder's unsigned, unemailed copy of v1");
        Map<String, Object> holderFile = storedFileRow(holderFileId);
        // Expected = the seed + exactly the holder's writes: the link row is as seeded (ACTIVE).
        assertSnapshot(before.withInvoices(append(before.invoices(), holderV2))
                        .withStoredFiles(append(before.storedFiles(), holderFile))
                        .withFilesOnDisk(plus(before.filesOnDisk(), holderPdfPath)),
                snapshot(orderId), "after the holder's v2 and the refused remote accept");
        Assertions.assertEquals(linkBefore, invoiceTokenRow(order.invoiceTokenId()),
                "the link stays ACTIVE, untouched");
        assertNothingOfTheRefusedRemote(order, 3, "the refused remote accept (stale v1)");
        assertNoMessagesAtAll("the holder's v2 and the refused remote accept");
        assertNoServiceWarnings("the holder's v2 and the refused remote accept");

        // The link now shows the current v2; the staff read still reports it.
        assertPublicActiveView(order, 2, NOTHING_PAID, SALE_INC_GST, linkExpiresAt,
                "public read after the refused remote accept");
        assertStaffInvoice(orderId, 2, false, linkExpiresAt, "D.3 after the refused remote accept");
        Assertions.assertEquals(linkBefore, invoiceTokenRow(order.invoiceTokenId()), "reads never change the link");
    }

    // ================================================================
    // 5. Expiry falling due at the locked re-read
    // ================================================================

    /**
     * The remote accept passes its token gate on an ACTIVE, UNEXPIRED link (so the gate took no lock and flipped
     * nothing) and is observed queued behind the holder. INSIDE the holder's transaction the link's
     * {@code expires_at} is backdated past due on both clocks, and the holder commits. The accept's locked re-read
     * then finds the link still ACTIVE but over-age: the guarded EXPIRED flip is COMMITTED (read back on a fresh
     * connection) and the answer is 410 {@code INVOICE_LINK_EXPIRED}. No invoice row, file, quote, payment or
     * mirror changes, and no email, notification or link email is sent. Afterwards the public read shows EXPIRED,
     * another accept is refused at its gate, and neither changes the dead link again.
     */
    @Test
    void remoteAccept_expiryFallsDueWhileItWaits_lockedReReadCommitsTheExpiredFlip_410InvoiceLinkExpired_noInvoiceChange()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> linkBefore = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);

        MvcResult remoteResult;
        LocalDateTime beforeHolderCommit;
        try (Race race = new Race(orderId)) {
            Future<MvcResult> remote = race.submitPublic(remoteAccept(order.token(), 1, REMOTE_SIGNATURE_PNG));
            int remotePid = awaitWaiterBlockedBy(race, race.holderPid(), "remote accept (carrying v1)", remote);
            // Parked at the order lock => its gate already accepted the link as ACTIVE and UNEXPIRED (an over-age
            // link would have been flipped and 410'd by the gate itself).
            Map<String, Object> asTheGateSawIt = invoiceTokenRow(order.invoiceTokenId());
            Assertions.assertEquals(linkBefore, asTheGateSawIt, "the gate wrote nothing");
            Assertions.assertEquals("ACTIVE", asTheGateSawIt.get("status"), "the link was ACTIVE at the gate");
            Assertions.assertTrue(((Timestamp) asTheGateSawIt.get("expires_at")).toLocalDateTime()
                    .isAfter(LocalDateTime.now()), "the link was unexpired when the accept passed its gate");

            // INSIDE the holder transaction: the expiry falls due.
            Assertions.assertEquals(1, race.updateInHolder(HOLDER_BACKDATE_LINK_EXPIRY_SQL,
                            Timestamp.valueOf(LocalDateTime.now()), order.invoiceTokenId()),
                    "the holder backdates exactly the presented link");
            Assertions.assertFalse(remote.isDone(), "the remote accept must still be parked on the order lock");
            Assertions.assertTrue(isBlockedBy(remotePid, race.holderPid()),
                    "the remote accept must still be waiting on the holder; activity: " + activity());

            beforeHolderCommit = LocalDateTime.now();
            race.commitHolder(); // publishes the due expiry AND releases the order row in one step

            remoteResult = awaitResponse(remote, "remote accept (carrying v1)");
        }
        LocalDateTime raceEnd = LocalDateTime.now();

        assertRefusal(remoteResult, 410, ErrorCode.INVOICE_LINK_EXPIRED, INVOICE_LINK_EXPIRED_MESSAGE,
                "remote accept (the expiry fell due while it waited)");

        // The guarded lazy-expiry flip found under the lock is COMMITTED before the 410: read on a fresh connection.
        Map<String, Object> expired = invoiceTokenRowOnFreshConnection(order.invoiceTokenId());
        Assertions.assertEquals("EXPIRED", expired.get("status"), "the locked re-read flipped the link to EXPIRED");
        Timestamp deadAt = (Timestamp) expired.get("dead_at");
        Assertions.assertNotNull(deadAt, "the expiry flip stamps dead_at");
        assertWithin(deadAt, beforeHolderCommit, raceEnd,
                "the flip happened under the lock, after the holder's commit");
        Timestamp backdatedExpiry = (Timestamp) expired.get("expires_at");
        Assertions.assertTrue(backdatedExpiry.toLocalDateTime().isBefore(beforeHolderCommit),
                "expires_at is the holder's past-due value");
        Assertions.assertTrue(backdatedExpiry.before(deadAt), "the link died after its expiry");
        Map<String, Object> expectedLink = withColumns(linkBefore, "status", "EXPIRED", "dead_at", deadAt,
                "expires_at", backdatedExpiry);
        Assertions.assertEquals(expectedLink, expired,
                "only the status, dead_at and the holder's expires_at changed (sent_at, emailed_at, sender kept)");
        // Everything else exactly as seeded: no invoice row, file, quote, payment or mirror change.
        assertSnapshot(before.withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id", expectedLink)),
                snapshot(orderId), "after the committed expiry flip and the 410");
        assertNothingOfTheRefusedRemote(order, 2, "the refused remote accept (expired link)");
        assertNoMessagesAtAll("the expired remote accept");
        assertNoServiceWarnings("the expired remote accept");

        // The dead link reads EXPIRED, refuses another accept at its gate, and never changes again.
        assertPublicDeadView(order.token(), "EXPIRED", INVOICE_LINK_EXPIRED_MESSAGE, "public read of the expired link");
        assertRefusal(performPublic(mockMvc, remoteAccept(order.token(), 1, FOLLOW_UP_SIGNATURE_PNG)), 410,
                ErrorCode.INVOICE_LINK_EXPIRED, INVOICE_LINK_EXPIRED_MESSAGE, "another accept of the expired link");
        Assertions.assertEquals(expectedLink, invoiceTokenRowOnFreshConnection(order.invoiceTokenId()),
                "a dead link never changes again");
        assertStaffInvoice(orderId, 1, false, null, "D.3 after the expiry");
        assertSnapshot(before.withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id", expectedLink)),
                snapshot(orderId), "after the reads of the expired link");
        assertNoMessagesAtAll("the reads of the expired link");
    }

    // ================================================================
    // 6. A link killed (REWRITTEN) by the lock holder while the remote accept waits
    // ================================================================

    /**
     * The remote accept carrying v1 is observed waiting on the holder. INSIDE the holder's transaction the work of a
     * Rewrite is done: invoice v2 is appended (unsigned, with its own real PDF) and the ACTIVE link is killed as
     * REWRITTEN at the holder's time - its {@code expires_at} ALSO backdated past due on both clocks - and the
     * holder commits. The accept's locked re-read finds the link REWRITTEN and answers with that TRUE reason: 410
     * {@code INVOICE_LINK_REWRITTEN} - never EXPIRED although the link is over-age (a dead link is never relabelled),
     * and never the stale 409 although v2 is newer than the v1 it carries (the token re-read comes first). Nothing
     * of the accept is written; the dead link keeps its status, {@code dead_at} and {@code expires_at} through the
     * later public read, accept and pdf requests.
     */
    @Test
    void remoteAccept_linkKilledRewrittenByTheLockHolderWhileItWaits_alsoPastExpiry_410InvoiceLinkRewrittenNeverExpired_writesNothing()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> linkBefore = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);
        byte[] holderPdf = fileStorageService.read(storagePathOf(longValue(invoiceV1.get("stored_file_id"))));
        String holderPdfPath = fileStorageService.store(holderPdf, businessId, orderId, "pdf");
        Timestamp holderKilledAt = Timestamp.valueOf(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));

        MvcResult remoteResult;
        long holderFileId;
        LocalDateTime beforeHolderCommit;
        try (Race race = new Race(orderId)) {
            Future<MvcResult> remote = race.submitPublic(remoteAccept(order.token(), 1, REMOTE_SIGNATURE_PNG));
            int remotePid = awaitWaiterBlockedBy(race, race.holderPid(), "remote accept (carrying v1)", remote);
            Assertions.assertEquals(linkBefore, invoiceTokenRow(order.invoiceTokenId()),
                    "the remote accept passed its gate on the ACTIVE, unexpired link and wrote nothing");

            // INSIDE the holder transaction: a Rewrite's work - append v2 and kill the link REWRITTEN (over-age too).
            holderFileId = race.queryLongInHolder(HOLDER_INSERT_PDF_FILE_SQL,
                    invoicePdfName(order.orderNumber(), 2), holderPdfPath, (long) holderPdf.length);
            Assertions.assertEquals(1, race.updateInHolder(HOLDER_APPEND_COPY_OF_VERSION_SQL, 2, holderFileId,
                    orderId, 1), "the holder appends exactly invoice v2");
            Assertions.assertEquals(1, race.updateInHolder(HOLDER_KILL_LINK_REWRITTEN_PAST_EXPIRY_SQL,
                            holderKilledAt, Timestamp.valueOf(LocalDateTime.now()), order.invoiceTokenId()),
                    "the holder kills exactly the presented link as REWRITTEN");
            Assertions.assertEquals(linkBefore, invoiceTokenRow(order.invoiceTokenId()),
                    "the holder's kill is invisible to every other transaction until it commits");
            Assertions.assertFalse(remote.isDone(), "the remote accept must still be parked on the order lock");
            Assertions.assertTrue(isBlockedBy(remotePid, race.holderPid()),
                    "the remote accept must still be waiting on the holder; activity: " + activity());

            beforeHolderCommit = LocalDateTime.now();
            race.commitHolder(); // publishes v2 and the REWRITTEN link AND releases the order row in one step

            remoteResult = awaitResponse(remote, "remote accept (carrying v1)");
        }

        assertRefusal(remoteResult, 410, ErrorCode.INVOICE_LINK_REWRITTEN, INVOICE_LINK_REWRITTEN_MESSAGE,
                "remote accept (its link killed REWRITTEN, and over-age, while it waited)");

        Map<String, Object> rewritten = invoiceTokenRowOnFreshConnection(order.invoiceTokenId());
        Timestamp backdatedExpiry = (Timestamp) rewritten.get("expires_at");
        Assertions.assertTrue(backdatedExpiry.toLocalDateTime().isBefore(beforeHolderCommit),
                "the REWRITTEN link is also past its expiry");
        Map<String, Object> expectedLink = withColumns(linkBefore, "status", "REWRITTEN", "dead_at", holderKilledAt,
                "expires_at", backdatedExpiry);
        Assertions.assertEquals(expectedLink, rewritten,
                "the link keeps the holder's REWRITTEN and dead_at: the over-age dead link was never "
                        + "flipped to EXPIRED");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId),
                "no remote version: exactly v1 and the holder's v2");
        Map<String, Object> holderV2 = invoiceRow(orderId, 2);
        Assertions.assertEquals(withColumns(invoiceV1, "invoice_id", holderV2.get("invoice_id"), "version_number", 2,
                        "stored_file_id", holderFileId, "created_at", holderV2.get("created_at")),
                holderV2, "invoice v2 is exactly the holder's unsigned copy of v1");
        Map<String, Object> holderFile = storedFileRow(holderFileId);
        OrderSnapshot expected = before.withInvoices(append(before.invoices(), holderV2))
                .withStoredFiles(append(before.storedFiles(), holderFile))
                .withFilesOnDisk(plus(before.filesOnDisk(), holderPdfPath))
                .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id", expectedLink));
        assertSnapshot(expected, snapshot(orderId), "after the holder's Rewrite work and the refused remote accept");
        assertNothingOfTheRefusedRemote(order, 3, "the refused remote accept (rewritten link)");
        assertNoMessagesAtAll("the holder's Rewrite work and the refused remote accept");
        assertNoServiceWarnings("the holder's Rewrite work and the refused remote accept");

        // Every later public request reports the same true reason (the gate never expires a dead link) and writes
        // nothing: the GET, another accept (with a valid body) and the pdf of the current version.
        assertPublicDeadView(order.token(), "REWRITTEN", INVOICE_LINK_REWRITTEN_MESSAGE,
                "public read of the rewritten, over-age link");
        assertRefusal(performPublic(mockMvc, remoteAccept(order.token(), 2, FOLLOW_UP_SIGNATURE_PNG)), 410,
                ErrorCode.INVOICE_LINK_REWRITTEN, INVOICE_LINK_REWRITTEN_MESSAGE,
                "another accept of the rewritten link (carrying the current v2)");
        assertRefusal(performPublic(mockMvc, get(PUBLIC_INVOICES + order.token() + "/pdf?version_number=2")), 410,
                ErrorCode.INVOICE_LINK_REWRITTEN, INVOICE_LINK_REWRITTEN_MESSAGE, "pdf of the rewritten link");
        Assertions.assertEquals(expectedLink, invoiceTokenRowOnFreshConnection(order.invoiceTokenId()),
                "a dead link never changes again - never relabelled EXPIRED");
        assertStaffInvoice(orderId, 2, false, null, "D.3 after the rewrite");
        assertSnapshot(expected, snapshot(orderId), "after the later public requests of the rewritten link");
        assertNoMessagesAtAll("the later public requests of the rewritten link");
    }

    // ================================================================
    // 7. The token gate's lazy expiry: an over-age link no longer past expiry at the locked re-read
    // ================================================================

    /**
     * A public GET arrives on a link that is ALREADY over-age (committed before the race), so its token gate takes
     * the order lock to expire it and is observed parked there. Inside the holder the link's {@code expires_at} is
     * moved into the future (the test fixture standing in for a wall clock that steps back while the gate waits) and
     * the holder commits. The gate's locked re-read finds the link still ACTIVE but no longer past expiry and writes
     * nothing: the GET answers 200 with the full ACTIVE document of v1 (its {@code expires_at} the re-read one), and
     * the link stays ACTIVE with {@code dead_at} null - apart from the fixture's expiry, exactly as sent. Nothing
     * else changes and nothing is sent; the staff read reports the link.
     */
    @Test
    void gateExpiresOnlyIfStillPastExpiryUnderTheLock_publicGet_noLongerPastDueAtTheLockedReRead_200FullActiveDocument_linkStaysActiveUnwritten()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> linkAsSent = invoiceTokenRow(order.invoiceTokenId());
        Map<String, Object> overAgeLink = backdateTheLinkPastDue(order);
        OrderSnapshot before = snapshot(orderId);
        Timestamp movedExpiry = Timestamp.valueOf(LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MICROS));

        MvcResult viewResult = raceTheGatesLazyExpiry(order, overAgeLink, get(PUBLIC_INVOICES + order.token()),
                movedExpiry, "public GET of the over-age link");

        // 200 with the full ACTIVE document of the current v1 - never the EXPIRED dead payload.
        assertPublicActiveView(publicView(viewResult, "public GET of the over-age link"), order, 1, NOTHING_PAID,
                SALE_INC_GST, movedExpiry, "public GET (no longer past expiry at the gate's locked re-read)");
        assertTheLinkStayedActiveUnwritten(order, linkAsSent, movedExpiry, before, "after the GET");
        assertNoMessagesAtAll("the GET of the link");
        assertNoServiceWarnings("the GET of the link");
        assertStaffInvoice(orderId, 1, false, movedExpiry, "D.3 after the GET");
    }

    /**
     * The same race for the public pdf of the shown v1: the gate parked on the order lock re-reads the link no longer
     * past expiry and writes nothing, so the pdf answers 200 with exactly v1's stored bytes - never the 410
     * {@code INVOICE_LINK_EXPIRED} - and the link stays ACTIVE and unwritten, as for the GET.
     */
    @Test
    void gateExpiresOnlyIfStillPastExpiryUnderTheLock_publicPdf_noLongerPastDueAtTheLockedReRead_200ExactStoredPdf_linkStaysActiveUnwritten()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> linkAsSent = invoiceTokenRow(order.invoiceTokenId());
        Map<String, Object> overAgeLink = backdateTheLinkPastDue(order);
        OrderSnapshot before = snapshot(orderId);
        Timestamp movedExpiry = Timestamp.valueOf(LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MICROS));

        MvcResult pdfResult = raceTheGatesLazyExpiry(order, overAgeLink,
                get(PUBLIC_INVOICES + order.token() + "/pdf?version_number=1"), movedExpiry,
                "public pdf of the over-age link");

        // 200 with exactly v1's stored PDF - never the 410 INVOICE_LINK_EXPIRED.
        assertServesStoredPdf(pdfResult, order, 1, "public pdf (no longer past expiry at the gate's locked re-read)");
        assertTheLinkStayedActiveUnwritten(order, linkAsSent, movedExpiry, before, "after the pdf");
        assertNoMessagesAtAll("the pdf of the link");
        assertNoServiceWarnings("the pdf of the link");
        assertStaffInvoice(orderId, 1, false, movedExpiry, "D.3 after the pdf");
    }

    /**
     * The same race for a remote accept carrying v1: the gate parked on the order lock re-reads the link no longer
     * past expiry and passes it on as ACTIVE, writing nothing; the accept then takes the order lock itself, re-reads
     * the link (ACTIVE, not past due) and signs v1 into v2 (201 {@code SIGNED}). The link goes straight from ACTIVE
     * to SIGNED with {@code dead_at} = the signing's {@code accepted_at} - an EXPIRED flip is permanent and would
     * have refused this accept with 410 - and, apart from the fixture's expiry, the rest of its row is as sent. The
     * committed state is exactly the signing's documented writes (D5(c) included), and the messages are exactly its
     * signed-PDF email and store notification.
     */
    @Test
    void gateExpiresOnlyIfStillPastExpiryUnderTheLock_remoteAccept_noLongerPastDueAtTheLockedReRead_201SignsV2_linkActiveStraightToSigned()
            throws Exception {
        LinkedOrder order = seedLinkedOrder(false);
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> linkAsSent = invoiceTokenRow(order.invoiceTokenId());
        Map<String, Object> overAgeLink = backdateTheLinkPastDue(order);
        OrderSnapshot before = snapshot(orderId);
        Timestamp movedExpiry = Timestamp.valueOf(LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MICROS));
        LocalDateTime raceStart = LocalDateTime.now();

        MvcResult acceptResult = raceTheGatesLazyExpiry(order, overAgeLink,
                remoteAccept(order.token(), 1, REMOTE_SIGNATURE_PNG), movedExpiry,
                "remote accept (carrying v1) of the over-age link");
        LocalDateTime raceEnd = LocalDateTime.now();

        // The gate passed the link on as ACTIVE (never EXPIRED, never a 410), and the accept signed v1 into v2.
        assertRemoteAccepted(acceptResult, "remote accept (no longer past expiry at the gate's locked re-read)");
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId), "exactly v1 and the remote signing's v2");
        SignedVersion signed = assertRemoteSignedVersion(order, invoiceV1, REMOTE_SIGNATURE_PNG, raceStart, raceEnd,
                "the remote signing through the formerly over-age link");

        // ACTIVE -> SIGNED directly, dead_at = accepted_at; apart from the fixture's expiry the row is as sent.
        Map<String, Object> expectedLink = withColumns(linkAsSent, "status", "SIGNED", "dead_at", signed.acceptedAt(),
                "expires_at", movedExpiry);
        Assertions.assertEquals(expectedLink, invoiceTokenRowOnFreshConnection(order.invoiceTokenId()),
                "the link went straight from ACTIVE to SIGNED at the signing time - never EXPIRED");
        assertSnapshot(withD5cApplied(plusSignedVersion(before, signed), order, signed.acceptedAt())
                        .withHeader(withColumns(before.header(), "last_emailed_at", signed.stamp()))
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id", expectedLink)),
                snapshot(orderId), "after the remote signing: exactly its writes");
        assertRemoteSigningMessages(order, signed, "the remote signing through the formerly over-age link");
        assertNoServiceWarnings("the remote signing through the formerly over-age link");
        assertPublicDeadView(order.token(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE, "public read after the signing");
        assertStaffInvoice(orderId, 2, true, null, "D.3 after the signing");
    }

    /**
     * Commits the presented link OVER-AGE before any race (the holder's backdate statement, auto-committed: past due
     * on both clocks, still ACTIVE), so the next public request's token gate takes the lazy-expiry path. Returns the
     * link row as committed.
     */
    private Map<String, Object> backdateTheLinkPastDue(LinkedOrder order) {
        Assertions.assertEquals(1, jdbcTemplate.update(HOLDER_BACKDATE_LINK_EXPIRY_SQL,
                        Timestamp.valueOf(LocalDateTime.now()), order.invoiceTokenId()),
                "setup: the ACTIVE link is backdated past due (committed)");
        Map<String, Object> overAge = invoiceTokenRow(order.invoiceTokenId());
        Assertions.assertEquals("ACTIVE", overAge.get("status"), "setup: the over-age link is still ACTIVE");
        Assertions.assertTrue(((Timestamp) overAge.get("expires_at")).toLocalDateTime().isBefore(LocalDateTime.now()),
                "setup: the link is past expiry by the JVM clock (the service's clock)");
        return overAge;
    }

    /**
     * One public request through the token gate's lazy expiry, raced on the order lock: {@code request} (a GET, pdf
     * or accept of the order's link, committed OVER-AGE) is submitted while the holder has the order row, and is
     * observed parked on it IN ITS TOKEN GATE - {@code pg_blocking_pids} shows it waiting on the holder, its open
     * transaction already read the link (so this is not a signing transaction's lock) and the link is still exactly
     * as committed (ACTIVE, over-age, unwritten): {@code resolve} saw it past due and {@code expireUnderLock} waits.
     * INSIDE the holder the link's {@code expires_at} is moved to {@code movedExpiry} (the fixture), and the holder
     * commits, releasing the row. Returns the response.
     */
    private MvcResult raceTheGatesLazyExpiry(LinkedOrder order, Map<String, Object> overAgeLink, RequestBuilder request,
                                            Timestamp movedExpiry, String label) throws Exception {
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> pending = race.submitPublic(request);
            int pid = awaitWaiterBlockedBy(race, race.holderPid(), label, pending);
            Assertions.assertEquals(Boolean.TRUE,
                    jdbcTemplate.queryForObject(HOLDS_INVOICE_TOKEN_LOCK_SQL, Boolean.class, pid),
                    label + ": its open transaction already read the link - parked in the token gate's lazy expiry; "
                            + "activity: " + activity());
            Assertions.assertEquals(overAgeLink, invoiceTokenRow(order.invoiceTokenId()),
                    label + ": the gate found the link ACTIVE and past due, and has written nothing");

            // INSIDE the holder transaction - the TEST FIXTURE: no longer past due when the gate re-reads the link.
            Assertions.assertEquals(1, race.updateInHolder(HOLDER_MOVE_LINK_EXPIRY_INTO_THE_FUTURE_SQL, movedExpiry,
                    order.invoiceTokenId()), label + ": the holder moves exactly the presented link's expiry");
            Assertions.assertFalse(pending.isDone(), label + ": must still be parked on the order lock");
            Assertions.assertTrue(isBlockedBy(pid, race.holderPid()),
                    label + ": must still be waiting on the holder; activity: " + activity());

            race.commitHolder(); // publishes the moved expiry AND releases the order row in one step

            return awaitResponse(pending, label);
        }
    }

    /**
     * No EXPIRED flip and no write: the link is ACTIVE with {@code dead_at} null and its row is exactly as sent apart
     * from the fixture's {@code movedExpiry}; the order's whole committed state is the seed plus that fixture change.
     */
    private void assertTheLinkStayedActiveUnwritten(LinkedOrder order, Map<String, Object> linkAsSent,
                                                    Timestamp movedExpiry, OrderSnapshot before, String label)
            throws SQLException, IOException {
        Map<String, Object> link = invoiceTokenRowOnFreshConnection(order.invoiceTokenId());
        Assertions.assertEquals("ACTIVE", link.get("status"), label + ": the gate never flipped the link to EXPIRED");
        Assertions.assertNull(link.get("dead_at"), label + ": the link never died");
        Map<String, Object> expectedLink = withColumns(linkAsSent, "expires_at", movedExpiry);
        Assertions.assertEquals(expectedLink, link,
                label + ": apart from the fixture's expiry, the link row is exactly as sent");
        assertSnapshot(before.withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id", expectedLink)),
                snapshot(order.orderId()), label + ": the committed state is the seed plus the fixture's expiry");
    }

    // ================================================================
    // Follow-up signing (after a race whose remote accept lost)
    // ================================================================

    /**
     * A sequential remote accept through {@code token} carrying {@code expectedVersion} (the order's current
     * version): 201 {@code SIGNED}; exactly the signing's writes - version {@code expectedVersion + 1} copied from
     * the current one, its signature and PDF, the link {@code ACTIVE -> SIGNED} at the signing time, D5(c), the
     * mirror equal to the delivered email's stamp - and exactly its email and store notification.
     */
    private void signThroughTheLinkAndAssert(LinkedOrder order, String token, long invoiceTokenId, int expectedVersion,
                                             String label) throws Exception {
        long orderId = order.orderId();
        Assertions.assertEquals(expectedVersion, currentInvoiceVersion(orderId),
                label + ": carries the current version");
        Map<String, Object> current = invoiceRow(orderId, expectedVersion);
        Map<String, Object> linkBefore = invoiceTokenRow(invoiceTokenId);
        Assertions.assertEquals("ACTIVE", linkBefore.get("status"), label + ": signs through an ACTIVE link");
        OrderSnapshot before = snapshot(orderId);
        serviceLogs.clear();
        LocalDateTime start = LocalDateTime.now();

        MvcResult result = performPublic(mockMvc, remoteAccept(token, expectedVersion, FOLLOW_UP_SIGNATURE_PNG));

        LocalDateTime end = LocalDateTime.now();
        assertRemoteAccepted(result, label);
        Assertions.assertEquals(expectedVersion + 1, currentInvoiceVersion(orderId), label + ": appended one version");
        SignedVersion signed = assertRemoteSignedVersion(order, current, FOLLOW_UP_SIGNATURE_PNG, start, end, label);
        Map<String, Object> expectedLink = withColumns(linkBefore, "status", "SIGNED", "dead_at", signed.acceptedAt());
        assertSnapshot(withD5cApplied(plusSignedVersion(before, signed), order, signed.acceptedAt())
                        .withHeader(withColumns(before.header(), "last_emailed_at", signed.stamp()))
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id", expectedLink)),
                snapshot(orderId), label + ": the committed state");
        assertRemoteSigningMessages(order, signed, label);
        assertNoServiceWarnings(label);
        assertPublicDeadView(token, "SIGNED", INVOICE_LINK_SIGNED_MESSAGE, label + ": public read afterwards");
        assertStaffInvoice(orderId, expectedVersion + 1, true, null, label + ": D.3 afterwards");
    }

    // ================================================================
    // Race harness (copied from InvoiceVersionPreconditionConcurrencyIntegrationTest; real security chain)
    // ================================================================

    /**
     * One deterministic race over an order row: a holder transaction (its own pooled connection,
     * {@code autoCommit=false}, {@code SET LOCAL lock_timeout} so a lock-order bug errors instead of hanging)
     * holding {@code FOR NO KEY UPDATE} on the order, plus a small executor for the competing requests. The
     * holder may write inside its transaction ({@link #updateInHolder} / {@link #queryLongInHolder}) and then
     * {@link #commitHolder()} or {@link #rollbackHolder()}. {@link #close()} - always reached via
     * try-with-resources - rolls back and closes a still-open holder, lets in-flight requests finish, and as a
     * last resort cancels a straggler, but only one of the request backends this race {@linkplain #track
     * tracked}.
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
         * Run one PUBLIC (token-only) request on a worker thread with its OWN MockMvc on the real Spring Security
         * filter chain, through {@link #performPublic} (no session, no Set-Cookie, whatever the status).
         */
        Future<MvcResult> submitPublic(RequestBuilder request) {
            MockMvc workerMockMvc = securedMockMvc();
            Callable<MvcResult> call = () -> performPublic(workerMockMvc, request);
            return workers.submit(call);
        }

        /** Run one STAFF request (it carries its own session) on a worker thread with its OWN secured MockMvc. */
        Future<MvcResult> submitStaff(RequestBuilder request) {
            MockMvc workerMockMvc = securedMockMvc();
            Callable<MvcResult> call = () -> workerMockMvc.perform(request).andReturn();
            return workers.submit(call);
        }

        /** Execute a write INSIDE the holder transaction (published only by {@link #commitHolder()}). */
        int updateInHolder(String sql, Object... args) throws SQLException {
            try (PreparedStatement statement = holder.prepareStatement(sql)) {
                bind(statement, args);
                return statement.executeUpdate();
            }
        }

        /** Execute a single-value query (e.g. {@code INSERT ... RETURNING id}) INSIDE the holder transaction. */
        long queryLongInHolder(String sql, Object... args) throws SQLException {
            try (PreparedStatement statement = holder.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("holder query returned no row: " + sql);
                    }
                    return rs.getLong(1);
                }
            }
        }

        /** Commit the holder's writes and release the order row in one step. */
        void commitHolder() throws SQLException {
            holder.commit();
            releaseHolder();
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

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
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
     * appender is attached to the logger, not to a thread). Read only after the workers finished (each response is
     * awaited through its future, which publishes everything the request logged).
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

        /** Forget everything captured so far (the seed's events). */
        void clear() {
            synchronized (appender) {
                appender.list.clear();
            }
        }

        /** Every captured WARN or ERROR, as "LEVEL logger: message". */
        List<String> warningsAndErrors() {
            synchronized (appender) {
                return appender.list.stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                        .map(event -> event.getLevel() + " " + event.getLoggerName() + ": "
                                + event.getFormattedMessage())
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

    /** The public, token-only remote accept: exactly the expected_version_number form field and one PNG signature. */
    private static RequestBuilder remoteAccept(String token, int expectedVersionNumber, byte[] signaturePng) {
        return multipart(PUBLIC_INVOICES + token + "/accept")
                .file(new MockMultipartFile("signature", "signature.png", "image/png", signaturePng))
                .param("expected_version_number", String.valueOf(expectedVersionNumber));
    }

    /** The protected send-link (Accept Remotely) by the link sender, with the EmptyObjectRequest {@code {}}. */
    private RequestBuilder sendLink(long orderId) {
        return post(orderUrl(orderId) + "/invoices/current/send-link").session(session(senderUserId))
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    /** D.7 record payment of {@value #PAYMENT_AMOUNT} (EFTPOS), by the order's salesperson. */
    private RequestBuilder recordPayment(long orderId) {
        return post(orderUrl(orderId) + "/payments").session(session(staffUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\":\"EFTPOS\",\"amount\":" + PAYMENT_AMOUNT
                        + ",\"payment_reference\":null}");
    }

    /** D.10 void of a recorded payment (empty object body), by the order's salesperson. */
    private RequestBuilder voidPayment(long orderId, long paymentTransactionId) {
        return post(orderUrl(orderId) + "/payments/" + paymentTransactionId + "/void").session(session(staffUserId))
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    // ================================================================
    // Committed self-seeding (no test transaction; real flows)
    // ================================================================

    /**
     * The tenant, committed: an active business (ABN + bank details, no terms, no logo / accent / Stripe link)
     * with the class slug, one store (with a notification email), the order's salesperson and the link sender, and
     * both salespeople's store access.
     */
    private void seedTenant() {
        Long business = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number) "
                        + "VALUES (?, ?, TRUE, ?, ?, ?, ?, ?) RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, BUSINESS_ABN, BUSINESS_BANK_NAME, BUSINESS_BSB, BUSINESS_NAME,
                BUSINESS_ACCOUNT_NUMBER);
        Assertions.assertNotNull(business, "setup: business insert returned no id");
        businessId = business;
        Integer store = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Banksia Race Showroom', ?, '0290005678', ?, '3 Race Way', 'Burwood', 'NSW', "
                        + " '2134') RETURNING store_id",
                Integer.class, businessId, STORE_CODE, STORE_EMAIL);
        Assertions.assertNotNull(store, "setup: store insert returned no id");
        storeId = store;
        staffUserId = insertUser(STAFF_FIRST_NAME, STAFF_LAST_NAME, STAFF_CODE, "rory.banksia.race.b@example.com");
        senderUserId = insertUser("Remy", "Bellbird", SENDER_CODE, "remy.bellbird.race.b@example.com");
        orderSequence = 0;
    }

    private long insertUser(String firstName, String lastName, String salespersonCode, String email) {
        Long user = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'not-a-login-hash') RETURNING user_id",
                Long.class, businessId, firstName, lastName, salespersonCode, email);
        Assertions.assertNotNull(user, "setup: app_user insert returned no id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, user, storeId);
        return user;
    }

    /** One committed order of the tenant. */
    private record SeededOrder(long orderId, String orderNumber) {
    }

    /** The ISSUED quote v1 and its ACTIVE quote link (the D5(c) target). */
    private record IssuedQuote(long quoteVersionId, long quoteTokenId) {
    }

    /** A link the protected send-link minted: the plaintext token (from its email), its row id and its row. */
    private record SentLink(String token, long invoiceTokenId, Map<String, Object> row) {
    }

    /**
     * A race-ready order: an unsigned D.1 invoice v1, an ISSUED quote v1 with an ACTIVE quote link, an ACTIVE
     * emailed invoice link (sent by {@value #SENDER_CODE}) and, for the void races, a payment ({@code paymentId})
     * recorded after the send. The current version is committed-dated {@link #SIGNED_FROM_INVOICE_DATE}.
     */
    private record LinkedOrder(long orderId, String orderNumber, long issuedQuoteVersionId, long issuedQuoteTokenId,
                               String token, long invoiceTokenId, Long paymentId) {
    }

    /**
     * A committed order that passes every D.1 / quote / send-link gate: details of sale, proposed lay date
     * 2026-12-01 CONFIRMED, the customer {@value #CUSTOMER_NAME} with a PADDED valid email, INSTALLATION and
     * BILLING addresses, and one charge line of 100.00 ex / 40.00 cost (110.00 inc).
     */
    private SeededOrder seedInvoiceReadyOrder() {
        int sequence = ++orderSequence;
        String orderNumber = STORE_CODE + "." + STAFF_CODE + "." + String.format("%05d", sequence);
        Assertions.assertTrue(ORDER_NUMBER_FORMAT.matcher(orderNumber).matches(),
                "setup: the order number follows the locked format: " + orderNumber);
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

    private LinkedOrder seedLinkedOrder(boolean withPaymentAfterTheSend) throws Exception {
        SeededOrder seeded = seedInvoiceReadyOrder();
        long orderId = seeded.orderId();
        createUnsignedInvoiceV1(orderId);
        IssuedQuote quote = issueQuote(orderId);

        // The real protected send-link (Accept Remotely) by the link sender; the plaintext token from its email.
        resetSenders();
        LocalDateTime sendStart = LocalDateTime.now();
        MvcResult sendResult = mockMvc.perform(sendLink(orderId)).andReturn();
        SentLink link = assertLinkSent(sendResult, orderId, seeded.orderNumber(), 1, sendStart, LocalDateTime.now(),
                "setup: send-link");

        Long paymentId = null;
        if (withPaymentAfterTheSend) {
            MvcResult paymentResult = mockMvc.perform(recordPayment(orderId)).andReturn();
            JsonNode data = assertPaymentMutation(paymentResult, PAYMENT_RECORDED_MESSAGE, 2, PAYMENT_AMOUNT,
                    BALANCE_AFTER_PAYMENT, false, "setup: payment (D.7) after the send");
            paymentId = data.path("payment_transaction").path("payment_transaction_id").asLong();
            Assertions.assertTrue(paymentId > 0, "setup: the payment id");
            Assertions.assertEquals(link.row(), invoiceTokenRow(link.invoiceTokenId()),
                    "setup: a payment never touches the ACTIVE link");
        }
        // The current version is committed-dated an earlier day, so a signing version's COPIED invoice date shows.
        int current = currentInvoiceVersion(orderId);
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice SET invoice_date = ? WHERE order_id = ? AND version_number = ?",
                        Date.valueOf(SIGNED_FROM_INVOICE_DATE), orderId, current),
                "setup: the current invoice version is committed-dated " + SIGNED_FROM_INVOICE_DATE);
        assertNoEmailMarkers(orderId, "setup");
        // Every send so far belongs to the seed: the race starts with empty recorders and no captured log.
        resetSenders();
        serviceLogs.clear();
        return new LinkedOrder(orderId, seeded.orderNumber(), quote.quoteVersionId(), quote.quoteTokenId(),
                link.token(), link.invoiceTokenId(), paymentId);
    }

    /** D.1 Create through the API: the unsigned, unemailed invoice v1 (110.00 inc) with the frozen words. */
    private void createUnsignedInvoiceV1(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(session(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: invoice create (D.1)");
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, json(result).path("message").asText(), "setup: D.1 message");
        Assertions.assertEquals(List.of(1), invoiceVersions(orderId), "setup: exactly the D.1 invoice v1");
        Map<String, Object> invoice = invoiceRow(orderId, 1);
        Assertions.assertNull(invoice.get("accepted_at"), "setup: invoice v1 is unsigned");
        assertMoney(SALE_INC_GST, invoice.get("sale_price_inc_gst"), "setup: invoice v1 inc");
        Assertions.assertEquals(CUSTOMER_NAME, invoice.get("customer_name_snapshot"), "setup: v1's frozen name");
        Assertions.assertEquals(BILLING_LINE_1, invoice.get("customer_address_line1_snapshot"), "setup: v1's line 1");
        Assertions.assertEquals(BILLING_LINE_2, invoice.get("customer_address_line2_snapshot"), "setup: v1's line 2");
        Assertions.assertNull(invoice.get("terms_snapshot"), "setup: no business terms are set, so none are frozen");
        Assertions.assertEquals(Date.valueOf(EXPECTED_DUE_DATE), invoice.get("due_date"), "setup: v1's due date");
    }

    /** The real quote chain: the draft PUT, then the protected send-email - an ISSUED v1 with an ACTIVE link. */
    private IssuedQuote issueQuote(long orderId) throws Exception {
        MvcResult draft = mockMvc.perform(put(orderUrl(orderId) + "/quote/draft").session(session(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content(ITEMISED_DRAFT))
                .andReturn();
        assertStatus(draft, 200, "setup: quote draft save");
        int quoteEmailsBefore = quoteEmailSender.sentEmails().size();
        MvcResult send = mockMvc.perform(post(orderUrl(orderId) + "/quote/send-email").session(session(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(send, 201, "setup: quote send-email (issue v1)");
        JsonNode sendBody = json(send);
        Assertions.assertEquals(QUOTE_SENT_MESSAGE, sendBody.path("message").asText(), "setup: quote send message");
        Assertions.assertEquals(1, sendBody.path("data").path("version_number").asInt(), "setup: issued quote v1");
        Assertions.assertEquals(quoteEmailsBefore + 1, quoteEmailSender.sentEmails().size(),
                "setup: exactly one quote email");
        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = 1", orderId);
        Assertions.assertEquals("ISSUED", version.get("status"), "setup: quote v1 is ISSUED");
        long versionId = longValue(version.get("quote_version_id"));
        Map<String, Object> token = jdbcTemplate.queryForMap(
                "SELECT * FROM quote_token WHERE quote_version_id = ? AND status = 'ACTIVE'", versionId);
        Assertions.assertNull(token.get("dead_at"), "setup: quote v1 has an ACTIVE, live link");
        return new IssuedQuote(versionId, longValue(token.get("quote_token_id")));
    }

    // ================================================================
    // Committed cleanup (FK-safe rows, then files on disk)
    // ================================================================

    /** Remove every committed row and file of this class's tenant (found by its slug), crashed runs included. */
    private void sweepTenant() {
        for (Long tenantBusinessId : jdbcTemplate.queryForList(
                "SELECT business_id FROM business WHERE slug = ?", Long.class, SLUG)) {
            for (Long orderId : jdbcTemplate.queryForList(
                    "SELECT order_id FROM sales_order WHERE business_id = ?", Long.class, tenantBusinessId)) {
                deleteCommittedOrder(tenantBusinessId, orderId);
            }
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
                // invoice_token.sent_by_user_id references app_user (no ON DELETE): defensively drop any link a
                // tenant user sent before the users go (the order sweep above already removed the order-bound ones).
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
    }

    /**
     * Delete one committed order and everything it owns in ONE transaction (bounded by a lock timeout), in
     * FK-safe order: the invoice links, the quote links, invoices (they may reference a quote_version and the
     * quote's signature), versions (cascade lines), the draft (cascade lines), the other order children, the order,
     * then its stored_file rows. The files on disk are deleted only after that commit, then the order directory.
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
        if (!target.startsWith(root) || target.equals(root) || !Files.exists(target)) {
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
     * included), every stored_file row of the order and the files on disk under its directory, every invoice link,
     * every quote version and token, the draft and its lines, the customer / address / line rows, and the payments.
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

        OrderSnapshot withPayments(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, invoices, storedFiles, filesOnDisk, invoiceTokens, quoteVersions,
                    quoteTokens, draft, content, value);
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
                        orderStoragePrefix(businessId, orderId) + "%"),
                storagePathsOnDisk(orderId),
                invoiceTokenRows(orderId),
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
                context + ": every stored_file row has its file and every file its row");
        Assertions.assertEquals(expected.invoiceTokens(), actual.invoiceTokens(), context + ": the invoice_token rows");
        Assertions.assertEquals(expected.quoteVersions(), actual.quoteVersions(), context + ": the quote_version rows");
        Assertions.assertEquals(expected.quoteTokens(), actual.quoteTokens(), context + ": the quote_token rows");
        Assertions.assertEquals(expected.draft(), actual.draft(), context + ": the quote draft and its lines");
        Assertions.assertEquals(expected.content(), actual.content(),
                context + ": the customer, address, product / charge line and quote version line rows");
        Assertions.assertEquals(expected.payments(), actual.payments(), context + ": the payment rows");
    }

    /** {@code base} plus a remote signing's invoice row, signature and PDF (rows and files). */
    private static OrderSnapshot plusSignedVersion(OrderSnapshot base, SignedVersion signed) {
        return base.withInvoices(append(base.invoices(), signed.row()))
                .withStoredFiles(append(base.storedFiles(), signed.signatureFile(), signed.pdfFile()))
                .withFilesOnDisk(plus(base.filesOnDisk(), storagePath(signed.signatureFile()),
                        storagePath(signed.pdfFile())));
    }

    /** {@code base} plus a payment's or void's invoice row and its regenerated PDF (row and file). */
    private static OrderSnapshot plusCarriedVersion(OrderSnapshot base, CarriedVersion carried) {
        return base.withInvoices(append(base.invoices(), carried.row()))
                .withStoredFiles(append(base.storedFiles(), carried.pdfFile()))
                .withFilesOnDisk(plus(base.filesOnDisk(), storagePath(carried.pdfFile())));
    }

    /**
     * {@code base} with D5(c) applied at {@code at} (16G contract section 10.5): the ISSUED quote v1 CANCELLED and
     * its ACTIVE quote link CANCELLED with {@code dead_at = at} - nothing else of either row changes.
     */
    private static OrderSnapshot withD5cApplied(OrderSnapshot base, LinkedOrder order, Timestamp at) {
        Map<String, Object> version = rowWithId(base.quoteVersions(), "quote_version_id", order.issuedQuoteVersionId());
        Assertions.assertEquals("ISSUED", version.get("status"), "D5(c) applies to the ISSUED quote version");
        Map<String, Object> token = rowWithId(base.quoteTokens(), "quote_token_id", order.issuedQuoteTokenId());
        Assertions.assertEquals("ACTIVE", token.get("status"), "D5(c) applies to the ACTIVE quote link");
        return base.withQuoteVersions(replaceRow(base.quoteVersions(), "quote_version_id",
                        withColumns(version, "status", "CANCELLED")))
                .withQuoteTokens(replaceRow(base.quoteTokens(), "quote_token_id",
                        withColumns(token, "status", "CANCELLED", "dead_at", at)));
    }

    // ================================================================
    // Writer-specific assertions
    // ================================================================

    /** A remote signing's appended version, as committed (and as verified by {@link #assertRemoteSignedVersion}). */
    private record SignedVersion(Map<String, Object> row, int version, Timestamp acceptedAt, Timestamp stamp,
                                 Map<String, Object> signatureFile, Map<String, Object> pdfFile, byte[] pdfBytes) {
    }

    /** A payment's or void's appended version, as committed (and as verified by {@link #assertCarriedVersion}). */
    private record CarriedVersion(Map<String, Object> row, int version, Map<String, Object> pdfFile, byte[] pdfBytes) {
    }

    /**
     * The remote signing's appended version (16G contract section 10.1): version {@code signedFrom + 1} copying
     * the signed-from version verbatim - invoice date (the date the customer reviewed), due date, details, ex / inc,
     * total paid, balance due, source quote version, terms and the three frozen identity columns - with
     * {@code accepted_at} = the signing time (within the window), {@code accepted_customer_name} = the frozen name,
     * its own signature and PDF, {@code created_by_user_id} = the LINK SENDER, and {@code last_emailed_at} = the stamp
     * of its delivered post-commit email. The signature {@code stored_file} is
     * {@code signature-{order}-v{N}.png} / {@code image/png} holding the SERVER-NORMALISED image of
     * {@code uploadedPng}; the PDF is {@code invoice-{order}-v{N}.pdf} / {@code application/pdf}, carries the
     * acceptance caption and shows the copied invoice date. Both files exist with their recorded sizes.
     */
    private SignedVersion assertRemoteSignedVersion(LinkedOrder order, Map<String, Object> signedFrom,
                                                    byte[] uploadedPng, LocalDateTime windowStart,
                                                    LocalDateTime windowEnd, String label) throws IOException {
        int version = intValue(signedFrom.get("version_number")) + 1;
        Map<String, Object> row = invoiceRow(order.orderId(), version);
        Timestamp acceptedAt = (Timestamp) row.get("accepted_at");
        Assertions.assertNotNull(acceptedAt, label + ": v" + version + " is signed");
        assertWithin(acceptedAt, windowStart, windowEnd, label + ": accepted_at is the signing time");
        Timestamp stamp = (Timestamp) row.get("last_emailed_at");
        Assertions.assertNotNull(stamp,
                label + ": the delivered signed-PDF email of v" + version + " was stamped on it");
        Assertions.assertFalse(stamp.before(acceptedAt), label + ": the email stamp follows the signing");
        assertWithin(stamp, windowStart, windowEnd, label + ": the email stamp was taken during the request");
        long signatureFileId = longValue(row.get("accepted_signature_file_id"));
        long pdfFileId = longValue(row.get("stored_file_id"));
        Assertions.assertEquals(withColumns(signedFrom,
                        "invoice_id", row.get("invoice_id"),
                        "version_number", version,
                        "stored_file_id", pdfFileId,
                        "created_by_user_id", senderUserId,
                        "created_at", row.get("created_at"),
                        "accepted_at", acceptedAt,
                        "accepted_customer_name", CUSTOMER_NAME,
                        "accepted_signature_file_id", signatureFileId,
                        "last_emailed_at", stamp),
                row, label + ": v" + version + " copies v" + (version - 1) + " verbatim (invoice date, due date, "
                        + "details, money, source, terms, frozen words) plus the acceptance, created by the link "
                        + "sender");

        Map<String, Object> signatureFile = storedFileRow(signatureFileId);
        assertStoredFile(signatureFile, invoiceSignatureName(order.orderNumber(), version), "image/png",
                order.orderId(), label + ": the signature stored_file");
        byte[] storedSignature = fileStorageService.read(storagePath(signatureFile));
        Assertions.assertEquals(longValue(signatureFile.get("file_size")), storedSignature.length,
                label + ": the signature's file_size is its bytes on disk");
        assertNormalisedSignature(uploadedPng, storedSignature, label + ": the stored signature");

        Map<String, Object> pdfFile = storedFileRow(pdfFileId);
        assertStoredFile(pdfFile, invoicePdfName(order.orderNumber(), version), "application/pdf", order.orderId(),
                label + ": the signed PDF stored_file");
        byte[] pdfBytes = fileStorageService.read(storagePath(pdfFile));
        Assertions.assertEquals(longValue(pdfFile.get("file_size")), pdfBytes.length,
                label + ": the signed PDF's file_size is its bytes on disk");
        assertIsPdf(pdfBytes, label + ": the signed PDF");
        assertAcceptedCaption(pdfBytes, CUSTOMER_NAME, acceptedAt.toLocalDateTime(), label + ": the signed PDF");
        LocalDate invoiceDate = ((Date) row.get("invoice_date")).toLocalDate();
        Assertions.assertTrue(noSpace(documentText(pdfBytes)).contains(DISPLAY_DATE.format(invoiceDate)),
                label + ": the signed PDF shows the copied invoice date " + invoiceDate);
        return new SignedVersion(row, version, acceptedAt, stamp, signatureFile, pdfFile, pdfBytes);
    }

    /**
     * A payment's or void's appended version (unchanged behaviour, outside 16G): version {@code version} carrying
     * {@code carriedFrom} forward - the due date, details, ex / inc, source, terms, frozen words AND its
     * acceptance (signature included, unchanged) - with the new {@code total_paid} / {@code balance_due}, dated the
     * day it is written, created by the order's salesperson, unemailed, with its own regenerated PDF.
     */
    private CarriedVersion assertCarriedVersion(LinkedOrder order, Map<String, Object> carriedFrom, int version,
                                                String totalPaid, String balanceDue, LocalDate dayBefore,
                                                LocalDate dayAfter, String label) throws IOException {
        Map<String, Object> row = invoiceRow(order.orderId(), version);
        LocalDate invoiceDate = ((Date) row.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(dayBefore) || invoiceDate.isAfter(dayAfter),
                label + ": a payment / void version is dated the day it is written, but was " + invoiceDate);
        long pdfFileId = longValue(row.get("stored_file_id"));
        Assertions.assertEquals(withColumns(carriedFrom,
                        "invoice_id", row.get("invoice_id"),
                        "version_number", version,
                        "invoice_date", row.get("invoice_date"),
                        "total_paid", new BigDecimal(totalPaid),
                        "balance_due", new BigDecimal(balanceDue),
                        "stored_file_id", pdfFileId,
                        "created_by_user_id", staffUserId,
                        "created_at", row.get("created_at"),
                        "last_emailed_at", null),
                row, label + ": v" + version + " carries v" + intValue(carriedFrom.get("version_number"))
                        + " forward (acceptance included) with the new totals, unemailed, by the order's salesperson");
        Map<String, Object> pdfFile = storedFileRow(pdfFileId);
        assertStoredFile(pdfFile, invoicePdfName(order.orderNumber(), version), "application/pdf", order.orderId(),
                label + ": the regenerated PDF stored_file");
        byte[] pdfBytes = fileStorageService.read(storagePath(pdfFile));
        Assertions.assertEquals(longValue(pdfFile.get("file_size")), pdfBytes.length,
                label + ": the PDF's file_size is its bytes on disk");
        assertIsPdf(pdfBytes, label + ": the regenerated PDF");
        return new CarriedVersion(row, version, pdfFile, pdfBytes);
    }

    /** A payment (D.7) or void (D.10) 201: its message, totals and E.1 {@code current_invoice} (no link field). */
    private static JsonNode assertPaymentMutation(MvcResult result, String message, int version, String totalPaid,
                                                  String balanceDue, boolean signed, String label) throws Exception {
        assertStatus(result, 201, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": envelope keys");
        Assertions.assertEquals(message, root.path("message").asText(), label + ": message");
        JsonNode data = root.path("data");
        Assertions.assertEquals(Set.of("payment_transaction", "payment_summary", "current_invoice"), fieldNames(data),
                label + ": data keys");
        assertMoney(totalPaid, data.path("payment_summary").path("total_paid").decimalValue(),
                label + ": payment_summary.total_paid");
        assertMoney(balanceDue, data.path("payment_summary").path("balance_due").decimalValue(),
                label + ": payment_summary.balance_due");
        JsonNode invoice = data.path("current_invoice");
        Assertions.assertEquals(version, invoice.path("version_number").asInt(), label + ": current_invoice version");
        assertMoney(totalPaid, invoice.path("total_paid").decimalValue(), label + ": current_invoice.total_paid");
        assertMoney(balanceDue, invoice.path("balance_due").decimalValue(), label + ": current_invoice.balance_due");
        Assertions.assertEquals(signed, invoice.path("accepted_signature_present").booleanValue(),
                label + ": current_invoice carries " + (signed ? "the acceptance" : "no acceptance"));
        Assertions.assertTrue(invoice.path("last_emailed_at").isNull(),
                label + ": a payment / void version is never emailed");
        Assertions.assertFalse(invoice.has("invoice_link_expires_at"),
                label + ": E.1 current_invoice never carries invoice_link_expires_at");
        return data;
    }

    /** The void response's payment_transaction: the voided row, its voided_at and the session actor's name. */
    private static void assertVoidedPaymentTransaction(JsonNode transaction, long paymentId, String label) {
        Assertions.assertEquals(paymentId, transaction.path("payment_transaction_id").asLong(),
                label + ": the voided payment");
        Assertions.assertTrue(transaction.path("voided_at").isTextual(), label + ": voided_at is set");
        Assertions.assertEquals(STAFF_FIRST_NAME + " " + STAFF_LAST_NAME, transaction.path("voided_by_name").asText(),
                label + ": voided by the session actor");
    }

    /** The order's one payment row: the race's EFTPOS payment of {@code amount}, still active. */
    private static Map<String, Object> assertSinglePayment(OrderSnapshot after, String amount, String label) {
        Map<String, Object> row = singleRow(after.payments(), label);
        assertMoney(amount, row.get("amount"), label + ": amount");
        Assertions.assertEquals("EFTPOS", String.valueOf(row.get("payment_method")), label + ": method");
        Assertions.assertNull(row.get("voided_at"), label + ": active");
        Assertions.assertNull(row.get("voided_by_user_id"), label + ": never voided");
        return row;
    }

    /** The seeded payment, now voided by the order's salesperson during the race - nothing else of it changed. */
    private Map<String, Object> assertVoidedPaymentRow(OrderSnapshot after, Map<String, Object> paymentBefore,
                                                       LocalDateTime windowStart, LocalDateTime windowEnd,
                                                       String label) {
        Map<String, Object> row = singleRow(after.payments(), label);
        Timestamp voidedAt = (Timestamp) row.get("voided_at");
        Assertions.assertNotNull(voidedAt, label + ": voided_at is set");
        assertWithin(voidedAt, windowStart, windowEnd, label + ": voided during the race");
        Assertions.assertEquals(withColumns(paymentBefore, "voided_at", voidedAt, "voided_by_user_id", staffUserId),
                row, label + ": soft-voided in place by the session actor (the row is kept)");
        return row;
    }

    /**
     * The send-link 201 (16G contract section 8): exactly {@code {"data":{"invoice_link":{"sent_at","expires_at"}},
     * "message":"Invoice link emailed to the customer."}}; exactly one link email (to the TRIMMED saved email, the
     * exact subject and body, the public link exactly once, the order id and the current version); and its token
     * row by SHA-256: ACTIVE, sent by the link sender within the window, expiring exactly 7 days after its send,
     * {@code emailed_at} stamped after delivery, not dead. The response's two timestamps are the row's, to the second.
     */
    private SentLink assertLinkSent(MvcResult result, long orderId, String orderNumber, int currentVersion,
                                    LocalDateTime windowStart, LocalDateTime windowEnd, String label) throws Exception {
        assertStatus(result, 201, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": envelope keys");
        Assertions.assertEquals(INVOICE_LINK_SENT_MESSAGE, root.path("message").asText(), label + ": message");
        Assertions.assertEquals(Set.of("invoice_link"), fieldNames(root.path("data")), label + ": data keys");
        JsonNode link = root.path("data").path("invoice_link");
        Assertions.assertEquals(Set.of("sent_at", "expires_at"), fieldNames(link), label + ": invoice_link keys");

        Assertions.assertTrue(invoiceLinkEmailSender.failedEmails().isEmpty(), label + ": no failed link email");
        List<InvoiceLinkEmailRequest> emails = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(1, emails.size(), label + ": exactly one link email");
        InvoiceLinkEmailRequest email = emails.get(0);
        Matcher matcher = INVOICE_LINK_PATTERN.matcher(email.bodyText());
        Assertions.assertTrue(matcher.find(), label + ": the link email carries the /i/{token} link");
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), label + ": the link appears exactly once");
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), label + ": the TRIMMED saved customer email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), label + ": subject");
        Assertions.assertEquals("Hi,\n\nYour invoice " + orderNumber + " from " + BUSINESS_NAME
                        + " is ready to review and sign. You can view and sign your invoice online here: "
                        + PUBLIC_BASE_URL + "/i/" + token + "\n\nThank you.",
                email.bodyText(), label + ": body");
        Assertions.assertEquals(orderId, email.orderId(), label + ": the email's order id");
        Assertions.assertEquals(currentVersion, email.invoiceVersionNumber(), label + ": the email's current version");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM invoice_token WHERE token_hash = ?", sha256Hex(token));
        Assertions.assertEquals(orderId, longValue(row.get("order_id")), label + ": the link belongs to the order");
        Assertions.assertEquals("ACTIVE", row.get("status"), label + ": the new link is ACTIVE");
        Assertions.assertEquals(senderUserId, longValue(row.get("sent_by_user_id")),
                label + ": sent by the session user");
        Timestamp sentAt = (Timestamp) row.get("sent_at");
        assertWithin(sentAt, windowStart, windowEnd, label + ": sent_at is the send time");
        Timestamp expiresAt = (Timestamp) row.get("expires_at");
        Assertions.assertEquals(sentAt.toLocalDateTime().plusDays(7), expiresAt.toLocalDateTime(),
                label + ": expires_at = sent_at + 7 days");
        Timestamp emailedAt = (Timestamp) row.get("emailed_at");
        Assertions.assertNotNull(emailedAt, label + ": the delivered link's emailed_at is stamped");
        Assertions.assertFalse(emailedAt.before(sentAt), label + ": emailed_at follows sent_at");
        assertWithin(emailedAt, windowStart, windowEnd, label + ": emailed_at is stamped during the request");
        Assertions.assertNull(row.get("dead_at"), label + ": the new link is not dead");
        assertWholeSecond(sentAt, link.path("sent_at"), label + ": invoice_link.sent_at");
        assertWholeSecond(expiresAt, link.path("expires_at"), label + ": invoice_link.expires_at");
        return new SentLink(token, longValue(row.get("invoice_token_id")), row);
    }

    // ================================================================
    // Response / public-surface assertions
    // ================================================================

    /** The remote accept's only success body: {@code {"data":{"state":"SIGNED"},"message":"Invoice accepted."}}. */
    private static void assertRemoteAccepted(MvcResult result, String label) throws Exception {
        assertStatus(result, 201, label);
        Assertions.assertEquals(JSON.readTree("{\"data\":{\"state\":\"SIGNED\"},\"message\":\""
                        + REMOTE_ACCEPTED_MESSAGE + "\"}"), json(result),
                label + ": exactly {data:{state:SIGNED}, message:Invoice accepted.} - no id, amount, name or file");
    }

    /** A refusal: the exact status, code and contract message (the code's default message), no data, no details. */
    private static void assertRefusal(MvcResult result, int status, ErrorCode code, String exactMessage, String label)
            throws Exception {
        assertStatus(result, status, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("error"), fieldNames(root), label + ": a refusal carries no data");
        JsonNode error = root.path("error");
        Assertions.assertEquals(Set.of("code", "message"), fieldNames(error), label + ": no details");
        Assertions.assertEquals(code.name(), error.path("code").asText(), label + ": error code");
        Assertions.assertEquals(exactMessage, error.path("message").asText(), label + ": the exact contract message");
        Assertions.assertEquals(exactMessage, code.defaultMessage(), label + ": the code's message is the contract's");
    }

    /** The public GET 200 {@code Invoice loaded.} with exactly the 27 PublicInvoiceView keys; returns its data. */
    private JsonNode publicView(String token, String label) throws Exception {
        return publicView(performPublic(mockMvc, get(PUBLIC_INVOICES + token)), label);
    }

    /** {@link #publicView(String, String)} on the response of a public GET already performed (a raced request's). */
    private static JsonNode publicView(MvcResult result, String label) throws Exception {
        assertStatus(result, 200, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": envelope keys");
        Assertions.assertEquals(INVOICE_LOADED_MESSAGE, root.path("message").asText(), label + ": message");
        JsonNode data = root.path("data");
        Assertions.assertEquals(PUBLIC_VIEW_KEYS, fieldNames(data), label + ": exactly the 27 PublicInvoiceView keys");
        return data;
    }

    /** The minimal dead payload: the state, the business name and the per-state message; every other key null. */
    private void assertPublicDeadView(String token, String state, String message, String label) throws Exception {
        JsonNode data = publicView(token, label);
        for (String key : PUBLIC_VIEW_KEYS) {
            switch (key) {
                case "state" -> Assertions.assertEquals(state, data.path(key).asText(), label + ": state");
                case "business_name" -> Assertions.assertEquals(BUSINESS_NAME, data.path(key).asText(),
                        label + ": business_name");
                case "message" -> Assertions.assertEquals(message, data.path(key).asText(), label + ": message");
                default -> Assertions.assertTrue(data.path(key).isNull(), label + ": a dead link shows no " + key);
            }
        }
    }

    private void assertPublicActiveView(LinkedOrder order, int version, String totalPaid, String balanceDue,
                                        Timestamp expiresAt, String label) throws Exception {
        assertPublicActiveView(order, version, totalPaid, balanceDue, expiresAt, order.token(), label);
    }

    /**
     * The ACTIVE view of the order's CURRENT version {@code version} (16G contract section 9.3): its stored words,
     * dates and money, the order's number and flooring type, the presented link's {@code expires_at}, the live
     * business presentation (seeded: ABN and bank details; no logo, accent or Stripe link), no message.
     */
    private void assertPublicActiveView(LinkedOrder order, int version, String totalPaid, String balanceDue,
                                        Timestamp expiresAt, String token, String label) throws Exception {
        assertPublicActiveView(publicView(token, label), order, version, totalPaid, balanceDue, expiresAt, label);
    }

    /** The same ACTIVE view assertions on the {@code data} of a public GET already performed (a raced request's). */
    private void assertPublicActiveView(JsonNode data, LinkedOrder order, int version, String totalPaid,
                                        String balanceDue, Timestamp expiresAt, String label) {
        Map<String, Object> current = invoiceRow(order.orderId(), version);
        Assertions.assertEquals("ACTIVE", data.path("state").asText(), label + ": state");
        Assertions.assertEquals(BUSINESS_NAME, data.path("business_name").asText(), label + ": business_name");
        Assertions.assertTrue(data.path("business_logo_url").isNull(), label + ": no logo is set");
        Assertions.assertTrue(data.path("accent_color").isNull(), label + ": no accent is set");
        Assertions.assertEquals(BUSINESS_ABN, data.path("business_abn").asText(), label + ": ABN (live)");
        Assertions.assertEquals(BUSINESS_NAME, data.path("payment_account_name").asText(), label + ": account name");
        Assertions.assertEquals(BUSINESS_BANK_NAME, data.path("payment_bank_name").asText(), label + ": bank name");
        Assertions.assertEquals(BUSINESS_BSB, data.path("payment_bsb").asText(), label + ": BSB");
        Assertions.assertEquals(BUSINESS_ACCOUNT_NUMBER, data.path("payment_account_number").asText(),
                label + ": account number");
        Assertions.assertTrue(data.path("payment_stripe_link_url").isNull(), label + ": no Stripe link is set");
        Assertions.assertEquals(order.orderNumber(), data.path("order_number").asText(), label + ": order_number");
        Assertions.assertEquals("SOFT", data.path("flooring_type").asText(), label + ": flooring_type");
        Assertions.assertEquals(version, data.path("version_number").asInt(), label + ": the CURRENT version");
        Assertions.assertEquals(((Date) current.get("invoice_date")).toLocalDate().toString(),
                data.path("invoice_date").asText(), label + ": the version's invoice_date");
        Assertions.assertEquals(EXPECTED_DUE_DATE.toString(), data.path("due_date").asText(), label + ": due_date");
        Assertions.assertEquals(CUSTOMER_NAME, data.path("customer_name").asText(), label + ": the frozen name");
        Assertions.assertEquals(BILLING_LINE_1, data.path("customer_address_line1").asText(), label + ": line 1");
        Assertions.assertEquals(BILLING_LINE_2, data.path("customer_address_line2").asText(), label + ": line 2");
        Assertions.assertEquals(DETAILS_OF_SALE, data.path("details_of_sale").asText(), label + ": details");
        assertMoney(SALE_EX_GST, data.path("sale_price_ex_gst").decimalValue(), label + ": ex GST");
        assertMoney(GST_AMOUNT, data.path("gst_amount").decimalValue(), label + ": GST = inc - ex");
        assertMoney(SALE_INC_GST, data.path("sale_price_inc_gst").decimalValue(), label + ": inc GST");
        assertMoney(totalPaid, data.path("total_paid").decimalValue(), label + ": total_paid");
        assertMoney(balanceDue, data.path("balance_due").decimalValue(), label + ": balance_due");
        Assertions.assertTrue(data.path("terms_html").isNull(), label + ": no terms were frozen");
        assertWholeSecond(expiresAt, data.path("expires_at"), label + ": the presented link's expires_at");
        Assertions.assertTrue(data.path("message").isNull(), label + ": no message while ACTIVE");
    }

    /**
     * The public pdf's 200 (16G contract section 9.5): {@code application/pdf}, {@code Content-Disposition} exactly
     * {@code inline; filename="invoice-{order_number}-v{N}.pdf"} (the stored name), {@code Content-Length} = the
     * stored size, and the body byte-identical to version N's stored file on disk.
     */
    private void assertServesStoredPdf(MvcResult result, LinkedOrder order, int versionNumber, String label) {
        assertStatus(result, 200, label);
        Map<String, Object> pdfFile = storedFileRow(longValue(invoiceRow(order.orderId(), versionNumber)
                .get("stored_file_id")));
        String expectedName = invoicePdfName(order.orderNumber(), versionNumber);
        assertStoredFile(pdfFile, expectedName, "application/pdf", order.orderId(), label + ": v" + versionNumber
                + "'s stored PDF");
        byte[] stored = fileStorageService.read(storagePath(pdfFile));
        Assertions.assertEquals(longValue(pdfFile.get("file_size")), stored.length,
                label + ": the stored file_size is its bytes on disk");
        Assertions.assertEquals("application/pdf", result.getResponse().getContentType(), label + ": Content-Type");
        Assertions.assertEquals(List.of("inline; filename=\"" + expectedName + "\""),
                result.getResponse().getHeaders(HttpHeaders.CONTENT_DISPOSITION),
                label + ": Content-Disposition, exactly");
        Assertions.assertEquals(String.valueOf(stored.length), result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH),
                label + ": Content-Length = the stored size");
        Assertions.assertArrayEquals(stored, result.getResponse().getContentAsByteArray(),
                label + ": version " + versionNumber + "'s stored bytes, verbatim");
        assertIsPdf(stored, label + ": the stored file");
    }

    /**
     * The staff D.3 read: the current version, whether it is signed, and the derived
     * {@code invoice_link_expires_at} (always present; the ACTIVE, unexpired link's {@code expires_at}, else null).
     */
    private void assertStaffInvoice(long orderId, int version, boolean signed, Timestamp linkExpiresAt, String label)
            throws Exception {
        MvcResult result = mockMvc.perform(get(orderUrl(orderId) + "/invoices/current").session(session(staffUserId)))
                .andReturn();
        assertStatus(result, 200, label);
        JsonNode invoice = json(result).path("data").path("invoice");
        Assertions.assertEquals(version, invoice.path("version_number").asInt(), label + ": the current version");
        Assertions.assertEquals(signed, invoice.path("accepted_signature_present").booleanValue(), label + ": signed");
        Assertions.assertTrue(invoice.has("invoice_link_expires_at"),
                label + ": InvoiceDetail always carries invoice_link_expires_at");
        if (linkExpiresAt == null) {
            Assertions.assertTrue(invoice.path("invoice_link_expires_at").isNull(),
                    label + ": no ACTIVE, unexpired link: invoice_link_expires_at is null");
        } else {
            assertWholeSecond(linkExpiresAt, invoice.path("invoice_link_expires_at"),
                    label + ": invoice_link_expires_at is the ACTIVE link's expires_at");
        }
    }

    // ================================================================
    // Message / log assertions
    // ================================================================

    /**
     * Exactly the remote signing's two post-commit messages and nothing else: the signed-PDF email of the version
     * it appended (to the TRIMMED saved email, the exact subject and body, that version's STORED PDF bytes and file
     * name, its order and version) and the store notification (to the order's store email, the exact subject and
     * body with the version, the frozen name, the signing time and the inc-GST total, its order and version). No
     * link email, quote email, quote notification or SMS.
     */
    private void assertRemoteSigningMessages(LinkedOrder order, SignedVersion signed, String label) {
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), label + ": no failed signed-PDF email");
        List<InvoiceEmailRequest> emails = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, emails.size(), label + ": exactly one signed-PDF email: the remote signing's");
        InvoiceEmailRequest email = emails.get(0);
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(),
                label + ": to the TRIMMED saved customer email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), label + ": email subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + order.orderNumber() + " from " + BUSINESS_NAME
                + " attached.\n\nThank you.", email.bodyText(), label + ": email body");
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), signed.version()), email.pdfFileName(),
                label + ": attachment name");
        Assertions.assertArrayEquals(signed.pdfBytes(), email.pdfBytes(),
                label + ": the attachment is the STORED signed PDF of v" + signed.version());
        Assertions.assertEquals(order.orderId(), email.orderId(), label + ": email order id");
        Assertions.assertEquals(signed.version(), email.invoiceVersionNumber(), label + ": email version");

        Assertions.assertTrue(invoiceNotificationSender.failedNotifications().isEmpty(),
                label + ": no failed store notification");
        List<InvoiceAcceptanceNotificationRequest> notifications = invoiceNotificationSender.sentNotifications();
        Assertions.assertEquals(1, notifications.size(),
                label + ": exactly one store notification: the remote signing's");
        InvoiceAcceptanceNotificationRequest notification = notifications.get(0);
        Assertions.assertEquals(STORE_EMAIL, notification.recipientEmail(), label + ": to the order's store email");
        Assertions.assertEquals("Invoice accepted: " + order.orderNumber(), notification.subject(),
                label + ": notification subject");
        LocalDateTime acceptedAt = signed.acceptedAt().toLocalDateTime();
        String exact = notificationBody(order.orderNumber(), signed.version(), acceptedAt);
        String roundedBack = notificationBody(order.orderNumber(), signed.version(), acceptedAt.minusNanos(1_000));
        Assertions.assertTrue(exact.equals(notification.bodyText()) || roundedBack.equals(notification.bodyText()),
                () -> label + ": notification body\nexpected: " + exact + "\nactual:   " + notification.bodyText());
        Assertions.assertEquals(order.orderId(), notification.orderId(), label + ": notification order id");
        Assertions.assertEquals(signed.version(), notification.invoiceVersionNumber(),
                label + ": notification version");

        assertNoLinkEmails(label);
        assertNoQuoteOrSmsMessages(label);
    }

    private static String notificationBody(String orderNumber, int version, LocalDateTime acceptedAt) {
        return "Invoice " + orderNumber + " (version " + version + ") was accepted online by " + CUSTOMER_NAME
                + " on " + DISPLAY_DATE_TIME.format(acceptedAt) + ".\n\nInvoice total (inc GST): " + INC_GST_DISPLAY
                + ".\n\nThe signed invoice is available in the sales portal.";
    }

    private void assertNoMessagesAtAll(String label) {
        assertNoSignedInvoiceMessages(label);
        assertNoLinkEmails(label);
        assertNoQuoteOrSmsMessages(label);
    }

    private void assertNoSignedInvoiceMessages(String label) {
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty() && invoiceEmailSender.failedEmails().isEmpty(),
                label + ": no signed-PDF email was sent or attempted");
        Assertions.assertTrue(invoiceNotificationSender.sentNotifications().isEmpty()
                        && invoiceNotificationSender.failedNotifications().isEmpty(),
                label + ": no store notification was sent or attempted");
    }

    private void assertNoLinkEmails(String label) {
        Assertions.assertTrue(invoiceLinkEmailSender.sentEmails().isEmpty()
                        && invoiceLinkEmailSender.failedEmails().isEmpty(),
                label + ": no invoice link email was sent or attempted");
    }

    private void assertNoQuoteOrSmsMessages(String label) {
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty() && quoteEmailSender.failedEmails().isEmpty(),
                label + ": no quote email was sent or attempted");
        Assertions.assertTrue(quoteNotificationSender.sentNotifications().isEmpty()
                        && quoteNotificationSender.failedNotifications().isEmpty(),
                label + ": no quote notification was sent or attempted");
        Assertions.assertTrue(smsSender.sentMessages().isEmpty() && smsSender.failedMessages().isEmpty(),
                label + ": no SMS was sent or attempted");
    }

    private void assertNoServiceWarnings(String label) {
        Assertions.assertEquals(List.of(), serviceLogs.warningsAndErrors(),
                label + ": no WARN or ERROR from the invoice, public invoice or payment services");
    }

    // ================================================================
    // Committed-state assertions
    // ================================================================

    /**
     * The refused remote accept left no request-owned row or file: no invoice version from
     * {@code wouldBeVersion} on, no signed version at all, no signature {@code stored_file} (no
     * {@code signature-*} / {@code image/png} row) and no PNG on disk, no PDF of the version it would have appended.
     */
    private void assertNothingOfTheRefusedRemote(LinkedOrder order, int wouldBeVersion, String label)
            throws IOException {
        String prefix = orderStoragePrefix(businessId, order.orderId()) + "%";
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM invoice WHERE order_id = ? AND version_number >= ?",
                order.orderId(), wouldBeVersion), label + ": appended no invoice version");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM invoice WHERE order_id = ? AND accepted_at IS NOT NULL",
                order.orderId()), label + ": no invoice version is signed");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? "
                        + "AND (file_name LIKE 'signature-%' OR mime_type = 'image/png')", prefix),
                label + ": stored no signature row");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND file_name = ?",
                prefix, invoicePdfName(order.orderNumber(), wouldBeVersion)), label + ": stored no signed PDF");
        Assertions.assertTrue(storagePathsOnDisk(order.orderId()).stream().noneMatch(path -> path.endsWith(".png")),
                label + ": left no signature image on disk");
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

    private static void assertStoredFile(Map<String, Object> file, String fileName, String mimeType, long orderId,
                                         String label) {
        Assertions.assertEquals(fileName, file.get("file_name"), label + ": file_name");
        Assertions.assertEquals(mimeType, file.get("mime_type"), label + ": mime_type");
        Assertions.assertTrue(storagePath(file).startsWith("/uploads/"), label + ": a virtual storage path");
        Assertions.assertTrue(storagePath(file).contains("/orders/" + orderId + "/"),
                label + ": stored under the order's directory");
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

    /** {@code value} lies in [start - 1s, end + 1s] (microsecond rounding of the stored value, DB / JVM clocks). */
    private static void assertWithin(Timestamp value, LocalDateTime start, LocalDateTime end, String label) {
        LocalDateTime at = value.toLocalDateTime();
        Assertions.assertFalse(at.isBefore(start.minusSeconds(1)) || at.isAfter(end.plusSeconds(1)),
                label + ": " + at + " is outside [" + start + ", " + end + "]");
    }

    /**
     * A whole-second JSON timestamp of a stored value: the stored value formatted, or - when the in-memory value the
     * response was built from was rounded UP to the next microsecond on store - the value 1 microsecond earlier.
     */
    private static void assertWholeSecond(Timestamp stored, JsonNode actual, String label) {
        LocalDateTime value = stored.toLocalDateTime();
        String exact = JSON_TIMESTAMP.format(value);
        String roundedBack = JSON_TIMESTAMP.format(value.minusNanos(1_000));
        Assertions.assertTrue(actual.isTextual()
                        && (exact.equals(actual.asText()) || roundedBack.equals(actual.asText())),
                label + ": expected " + exact + " but was " + actual);
    }

    // ================================================================
    // Committed-state probes
    // ================================================================

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private List<Integer> invoiceVersions(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT version_number FROM invoice WHERE order_id = ? ORDER BY version_number",
                Integer.class, orderId);
    }

    /** The order's CURRENT invoice version (the highest version_number) in committed state - what a client sees. */
    private int currentInvoiceVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "the order has a current invoice");
        return version;
    }

    private Map<String, Object> invoiceTokenRow(long invoiceTokenId) {
        return jdbcTemplate.queryForMap("SELECT * FROM invoice_token WHERE invoice_token_id = ?", invoiceTokenId);
    }

    private List<Map<String, Object>> invoiceTokenRows(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId);
    }

    /** The link's COMMITTED row, read on a freshly checked-out pooled connection (auto-commit, no open transaction). */
    private Map<String, Object> invoiceTokenRowOnFreshConnection(long invoiceTokenId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM invoice_token WHERE invoice_token_id = ?")) {
                statement.setLong(1, invoiceTokenId);
                try (ResultSet rs = statement.executeQuery()) {
                    Assertions.assertTrue(rs.next(), "invoice_token " + invoiceTokenId + " exists");
                    ResultSetMetaData meta = rs.getMetaData();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= meta.getColumnCount(); column++) {
                        row.put(meta.getColumnLabel(column), rs.getObject(column));
                    }
                    Assertions.assertFalse(rs.next(), "exactly one invoice_token " + invoiceTokenId);
                    return row;
                }
            }
        }
    }

    /** One stored_file row, in the same columns as the snapshot's stored_file rows. */
    private Map<String, Object> storedFileRow(long storedFileId) {
        return jdbcTemplate.queryForMap(
                "SELECT stored_file_id, file_name, storage_path, mime_type, file_size FROM stored_file "
                        + "WHERE stored_file_id = ?", storedFileId);
    }

    private String storagePathOf(long storedFileId) {
        return jdbcTemplate.queryForObject(
                "SELECT storage_path FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ================================================================
    // Signatures and PDF text
    // ================================================================

    /** A small, decodable 8-bit ARGB PNG with one dark horizontal stroke (a canvas-like signature export). */
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

    /**
     * The SERVER-NORMALISED signature (16G contract section 9.4): the decoded upload re-encoded as a clean PNG -
     * PNG magic, the stream ends exactly at IEND, the SAME width / height and the same pixels (alpha for every pixel;
     * RGB wherever the pixel is not fully transparent).
     */
    private static void assertNormalisedSignature(byte[] uploaded, byte[] stored, String label) throws IOException {
        Assertions.assertTrue(stored.length > PNG_MAGIC.length + PNG_IEND_CHUNK.length,
                label + ": too short: " + stored.length + " bytes");
        Assertions.assertArrayEquals(PNG_MAGIC, Arrays.copyOfRange(stored, 0, PNG_MAGIC.length),
                label + ": starts with the PNG magic");
        Assertions.assertArrayEquals(PNG_IEND_CHUNK,
                Arrays.copyOfRange(stored, stored.length - PNG_IEND_CHUNK.length, stored.length),
                label + ": ends exactly at the IEND chunk (no trailing bytes)");
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(uploaded));
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(stored));
        Assertions.assertNotNull(expected, label + ": the upload is a decodable PNG");
        Assertions.assertNotNull(actual, label + ": the stored signature is a decodable PNG");
        Assertions.assertEquals(expected.getWidth(), actual.getWidth(), label + ": keeps the upload's width");
        Assertions.assertEquals(expected.getHeight(), actual.getHeight(), label + ": keeps the upload's height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y);
                int got = actual.getRGB(x, y);
                int wantAlpha = want >>> 24;
                boolean same = wantAlpha == (got >>> 24) && (wantAlpha == 0 || (want & 0xFFFFFF) == (got & 0xFFFFFF));
                Assertions.assertTrue(same, String.format(Locale.ROOT,
                        "%s: keeps the pixels: (%d,%d) upload %08x stored %08x", label, x, y, want, got));
            }
        }
    }

    private static void assertIsPdf(byte[] bytes, String label) {
        Assertions.assertTrue(bytes.length > 4 && new String(bytes, 0, 4, StandardCharsets.ISO_8859_1).equals("%PDF"),
                label + ": is a PDF");
    }

    /** Content-stream order. */
    private static String documentText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Visual reading order: the caption is "Accepted by" + a name span + "on" + a timestamp span. */
    private static String readingOrderText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /**
     * The signed caption "Accepted by {name} on {dd/MM/yyyy HH:mm}" in reading order, all whitespace removed. The
     * stored accepted_at may sit up to 1 microsecond after the rendered in-memory instant, so the rendering of
     * {@code acceptedAt - 1us} is accepted too (exact across a minute boundary).
     */
    private static void assertAcceptedCaption(byte[] pdf, String acceptedName, LocalDateTime acceptedAt, String label)
            throws IOException {
        String readingOrder = readingOrderText(pdf);
        String text = noSpace(readingOrder);
        String exact = noSpace("Accepted by " + acceptedName + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        String roundedBack = noSpace("Accepted by " + acceptedName + " on "
                + DISPLAY_DATE_TIME.format(acceptedAt.minusNanos(1_000)));
        Assertions.assertTrue(text.contains(exact) || text.contains(roundedBack),
                () -> label + ": the PDF must carry the caption '" + exact + "' in: "
                        + readingOrder.replaceAll("\\s+", " "));
    }

    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
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

    private static String storagePath(Map<String, Object> storedFile) {
        return (String) storedFile.get("storage_path");
    }

    private static Map<String, Object> singleRow(List<Map<String, Object>> rows, String label) {
        Assertions.assertEquals(1, rows.size(), label + ": exactly one row");
        return rows.get(0);
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

    private static Map<String, Object> rowWithId(List<Map<String, Object>> rows, String idColumn, long id) {
        List<Map<String, Object>> matches = rows.stream()
                .filter(row -> row.get(idColumn) instanceof Number number && number.longValue() == id)
                .toList();
        Assertions.assertEquals(1, matches.size(), "exactly one row with " + idColumn + " = " + id);
        return matches.get(0);
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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

    private static String orderStoragePrefix(long ownerBusinessId, long orderId) {
        return "/uploads/" + ownerBusinessId + "/orders/" + orderId + "/";
    }

    private Set<String> storagePathsOnDisk(long orderId) throws IOException {
        String prefix = orderStoragePrefix(businessId, orderId);
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

    private static String invoiceSignatureName(String orderNumber, int versionNumber) {
        return "signature-" + orderNumber + "-v" + versionNumber + ".png";
    }
}
