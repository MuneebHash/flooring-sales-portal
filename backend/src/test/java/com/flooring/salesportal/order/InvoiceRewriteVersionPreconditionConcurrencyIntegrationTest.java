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
 * Batch A PR 2 (#149) - the D.2 Rewrite version precondition ({@code {"expected_version_number": N}}) raced, on
 * the ORDER ROW LOCK, against every other invoice version writer and against itself, in BOTH lock orders, plus a
 * newer version committed by the lock holder while the Rewrite waits (docs/API-Contracts-Phase16G-Invoice-Link.md
 * section 5 as amended by Batch A PR 2). The value a Rewrite carries is only COMPARED with the current version
 * read under the lock, so the outcome is asymmetric:
 * <ul>
 *   <li><b>The other writer first</b> - a remote accept, D.8, a payment, a void or Path A appends a newer version
 *       and commits; the queued Rewrite, still carrying the version its confirmation was bound to, is refused with
 *       409 {@code INVOICE_VERSION_STALE} and writes NOTHING (a dead link stays as the winner left it).</li>
 *   <li><b>The Rewrite first</b> - it appends the unsigned next version (killing an ACTIVE link as REWRITTEN) and
 *       commits. A queued remote accept is refused by its token gate (410 {@code INVOICE_LINK_REWRITTEN}, before
 *       any version comparison) and a queued D.8 carrying the old version by its own precondition (409); a payment
 *       and a void carry no version and succeed against the Rewrite's version; Path A succeeds from the accepted
 *       quote, because its own preconditions still hold (the Rewrite's version is unsigned).</li>
 *   <li><b>Two Rewrites</b> carrying the same version: exactly one appends; the other is the stale 409 - one
 *       version, one PDF, one link kill.</li>
 *   <li><b>The lock holder commits</b> a newer version while the Rewrite waits: the Rewrite reads it under the
 *       lock and is the stale 409; the ACTIVE link is untouched.</li>
 * </ul>
 * After each race the committed state is asserted to be EXACTLY the seed plus the winners' own documented writes -
 * the {@code sales_order} row (its {@code last_emailed_at} mirror included), every invoice row, every
 * {@code stored_file} row of the order and every file under its storage directory (no orphan either way), the
 * {@code invoice_token} rows, the quote rows, the draft, the customer / address / line rows and the payments - and
 * the recorded messages are exactly the winners' (a signed-PDF email for D.8, a remote accept and Path A; a store
 * notification for a remote accept only; nothing for a Rewrite, a payment or a void). Neither invoice service nor
 * the payment service logs a WARN.
 *
 * <p><b>Fixtures - a fully self-seeded, COMMITTED tenant.</b> The requests run on worker threads with their own
 * pooled connections and real commits, so this class is NOT {@code @Transactional}: it inserts its own business
 * (slug {@value #SLUG}, ABN and bank details, no terms), store {@value #STORE_CODE} (with a notification email),
 * two salespeople - {@value #STAFF_CODE} for every staff request and {@value #SENDER_CODE} for the invoice links -
 * and their store access, and each order (customer with a PADDED valid email, INSTALLATION + BILLING, details of
 * sale, lay date 2026-12-01 CONFIRMED, one 100.00 ex / 40.00 cost charge line) with auto-commit SQL. Everything
 * else goes through the real endpoints: D.1, payments, the quote draft PUT, the protected quote send-email and the
 * public quote accept, and the protected send-link (the plaintext invoice token from the recorded link email,
 * matched to its row by SHA-256). No V4 identity is used. It declares no bean overrides, so it shares the cached
 * Spring context of the other quote / invoice suites that write under the same storage base dir.
 *
 * <p><b>Deterministic barrier (no sleeps as barriers, no production hooks).</b> The harness of
 * {@code RemoteInvoiceAcceptRaceIntegrationTest} (itself from {@code InvoiceVersionPreconditionConcurrencyIntegrationTest}):
 * a HOLDER connection takes {@code SELECT ... FOR NO KEY UPDATE} on the order row; the first request is submitted
 * and observed blocked behind the holder, the second observed queued behind the FIRST (through the row's tuple
 * lock), both through {@code pg_stat_activity} + {@code pg_blocking_pids} with bounded polling; then the holder
 * ROLLS BACK, so the first request takes the row, commits or rolls back, and only then the second. For the
 * holder-commit case the holder appends a version inside its own transaction and COMMITS, which publishes it and
 * releases the row in one step (the commit-capable holder of {@code InvoiceVersionPreconditionConcurrencyIntegrationTest}).
 * Every request runs on its own MockMvc built with {@code springSecurity()} (the REAL filter chain), and every
 * public request goes through {@link #performPublic}, which asserts that no session exists and no
 * {@code Set-Cookie} is sent. The holder is always released and the worker pool always shut down
 * (try-with-resources {@link Race}); a straggler is cancelled only among the backends the race tracked.
 *
 * <p><b>Sweep.</b> {@code @BeforeEach} and {@code @AfterEach} remove everything the class commits, in FK-safe
 * order: per order {@code invoice_token}, quote_token, invoice, quote_version, quote_draft, the order children,
 * sales_order and the order's stored_file rows (plus its files and directory); then the tenant's store_charge,
 * store_product, user_store_access, app_user, store, business_quick_description and business rows and its storage
 * directory. Every recording sender is reset in both.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class InvoiceRewriteVersionPreconditionConcurrencyIntegrationTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before any file is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant (never a V4 identity). The slug tags everything this class commits. ----
    private static final String SLUG = "batch-a-pr2-rewrite-race";
    private static final String BUSINESS_NAME = "Kestrel Rewrite Race Flooring";
    private static final String STORE_CODE = "RWR-ONE";
    private static final String STORE_EMAIL = "showroom.rewrite.race@example.com";
    /** Runs every staff request (Rewrite, D.8, payments, voids, Path A) and the seed's D.1 / quote steps. */
    private static final String STAFF_CODE = "RR1";
    /** Sends the invoice links: the creator of a remotely signed version (16G contract section 10.2). */
    private static final String SENDER_CODE = "RR2";
    private static final String CHARGE_CODE_PREFIX = "RWRC";
    private static final String THREAD_NAME_PREFIX = "rewrite-version-race-";

    // ---- The seeded customer ----
    private static final String CUSTOMER_FIRST_NAME = "Casey";
    private static final String CUSTOMER_LAST_NAME = "Racewell";
    /** The D.1 freeze of the saved name: v1's customer_name_snapshot, i.e. every signing's accepted name. */
    private static final String CUSTOMER_NAME = CUSTOMER_FIRST_NAME + " " + CUSTOMER_LAST_NAME;
    private static final String CUSTOMER_EMAIL = "casey.racewell@example.com";
    /** Saved with edge whitespace (DB-valid, app-valid once trimmed): every recipient must be the trimmed value. */
    private static final String SAVED_CUSTOMER_EMAIL = "  " + CUSTOMER_EMAIL + " ";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and lay carpet (rewrite version race)";

    // ---- Money ----
    /** The live order: 100.00 ex / 110.00 inc (the D.1 v1 and every Rewrite of a LinkedOrder or SeededOrder). */
    private static final String PAYMENT_AMOUNT = "50.00";
    /** The accepted itemised quote v1: 250.00 ex / 275.00 inc - also the live price after its D6b write. */
    private static final String ITEMISED_DRAFT = """
            {"itemised": true, "lines": [
              {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
              {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
            ]}""";

    // ---- Messages (exact) ----
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String INVOICE_REWRITTEN_MESSAGE = "Invoice rewritten.";
    private static final String INVOICE_LINK_SENT_MESSAGE = "Invoice link emailed to the customer.";
    private static final String REMOTE_ACCEPTED_MESSAGE = "Invoice accepted.";
    private static final String INVOICE_LOADED_MESSAGE = "Invoice loaded.";
    private static final String D8_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String PATH_A_EMAILED_MESSAGE =
            "Invoice created from accepted quote and emailed to the customer.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String PAYMENT_VOIDED_MESSAGE = "Payment voided. Current invoice updated.";
    private static final String INVOICE_VERSION_STALE_MESSAGE = "Invoice updated, please refresh";
    private static final String INVOICE_LINK_SIGNED_MESSAGE =
            "This invoice has already been signed. Please contact the store if you need a copy.";
    private static final String INVOICE_LINK_REWRITTEN_MESSAGE =
            "This invoice has been revised. Please contact the store for the updated invoice.";
    private static final String QUOTE_SENT_MESSAGE = "Quote sent by email.";
    private static final String QUOTE_ACCEPTED_MESSAGE = "Quote accepted.";

    // ---- Public links ----
    /** The public link inside the link-only invoice email: <app-base>/i/{43-char token}. */
    private static final Pattern INVOICE_LINK_PATTERN = Pattern.compile("/i/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    /** The public link inside the link-only quote email: <app-base>/q/{43-char token}. */
    private static final Pattern QUOTE_LINK_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    // ---- Signatures: each signer uploads a DIFFERENT image, so the stored bytes name the winner ----
    /** A real, decodable 1x1 PNG: D.8 stores it verbatim; the quote accept signs with it. */
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    /** The remote accept's upload (6 x 3): stored as its server-normalised PNG. */
    private static final byte[] REMOTE_SIGNATURE_PNG = strokePng(6, 3);

    // Exact money parsing for response bodies (never a double round-trip).
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    // The signed-PDF caption format ("dd/MM/yyyy HH:mm").
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
     * Rewrite / D.8 / payment / void / Path A, or the public accept's
     * {@code SELECT order_id FROM sales_order ... FOR UPDATE}. Runs auto-commit on the test thread, so every poll
     * sees a fresh {@code pg_stat_activity}.
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

    /**
     * The lock holder's newer invoice version: a copy of the given version as the given next version number, with
     * its own {@code stored_file} - what a payment appends (unsigned, unemailed). Published only when the holder
     * commits.
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
    // 1. Rewrite vs remote accept
    // ================================================================

    /**
     * The REMOTE ACCEPT locks first: it is observed waiting on the holder, then a Rewrite carrying the v1 its
     * confirmation was bound to is observed queued BEHIND it; the holder rolls back. The remote accept signs v1
     * into v2 (the link SIGNED at the signing time), commits, emails v2's signed PDF and notifies the store. The
     * Rewrite then takes the lock, reads the current invoice there (the remote's signed v2) and refuses the v1 it
     * carries: 409 {@code INVOICE_VERSION_STALE}. Nothing of the Rewrite's is written - no unsigned v3, no PDF, no
     * mirror reset - the dead link stays SIGNED and the Rewrite sends nothing, so the remote signature the
     * salesperson never saw is not cleared.
     */
    @Test
    void rewriteVsRemoteAccept_remoteLocksFirst_rewriteCarryingV1Gets409_linkStaysSigned_writesNothing() throws Exception {
        LinkedOrder order = seedLinkedOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);

        RaceResults race = raceInOrder(orderId,
                publicStep("remote accept (carrying v1)", remoteAccept(order.invoiceToken(), 1, REMOTE_SIGNATURE_PNG)),
                staffStep("Rewrite (carrying v1)", rewriteInvoice(orderId, 1)));

        assertRemoteAccepted(race.first(), "remote accept (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "Rewrite (carrying v1, queued behind the remote accept)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId),
                "exactly v1 and the remote accept's v2 - no unsigned Rewrite version");
        AppendedVersion signed = assertSigningVersion(order.seeded(), invoiceV1, 2, senderUserId, REMOTE_SIGNATURE_PNG,
                SignaturePngNormaliser.normalise(REMOTE_SIGNATURE_PNG), race, "the remote accept's v2");
        assertSnapshot(plus(before, signed)
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "SIGNED", "dead_at", signed.acceptedAt()))),
                snapshot(orderId), "after the remote signing and the refused Rewrite queued behind it");
        assertOnlySignatureImages(orderId, List.of(signed.signatureFile()), "only the remote accept's signature image");

        assertRaceMessages(orderId, order.orderNumber(), signed, true, "remote first vs Rewrite");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertFinalLink(orderId, order.invoiceTokenId(), "SIGNED", signed.acceptedAt());
        assertCurrentVersionAndMirror(orderId, 2, signed.acceptedAt(), signed.stamp());
        assertPublicDeadView(order.invoiceToken(), "SIGNED", INVOICE_LINK_SIGNED_MESSAGE);
    }

    /**
     * The REWRITE locks first: it is observed waiting on the holder, then the remote accept carrying v1 is observed
     * queued BEHIND it; the holder rolls back. The Rewrite (carrying v1, the current version) appends the unsigned
     * v2 from the live order and kills the ACTIVE link as REWRITTEN in its own transaction, then commits (it emails
     * nothing). The remote accept then takes the lock, re-reads its token (REWRITTEN) and gets 410
     * {@code INVOICE_LINK_REWRITTEN} from its token gate, before any version comparison: nothing of the remote's is
     * written and nothing at all is sent during the race.
     */
    @Test
    void rewriteVsRemoteAccept_rewriteLocksFirst_rewriteAppendsV2KillsLinkRewritten_remoteGets410() throws Exception {
        LinkedOrder order = seedLinkedOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);

        RaceResults race = raceInOrder(orderId,
                staffStep("Rewrite (carrying v1)", rewriteInvoice(orderId, 1)),
                publicStep("remote accept (carrying v1)", remoteAccept(order.invoiceToken(), 1, REMOTE_SIGNATURE_PNG)));

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId),
                "exactly v1 and the Rewrite's v2 - no remote version");
        AppendedVersion rewritten = assertRewriteVersion(order.seeded(), invoiceV1, 2, race, "the Rewrite's v2");
        assertRewriteResponse(race.first(), rewritten, "Rewrite (queue head)");
        assertRefusal(race.second(), 410, ErrorCode.INVOICE_LINK_REWRITTEN, INVOICE_LINK_REWRITTEN_MESSAGE,
                "remote accept (queued behind the Rewrite)");

        Timestamp killedAt = (Timestamp) invoiceTokenRow(order.invoiceTokenId()).get("dead_at");
        Assertions.assertNotNull(killedAt, "the Rewrite killed the ACTIVE link");
        assertWithinRace(killedAt, race, "the Rewrite's link kill time");
        assertSnapshot(plus(before, rewritten)
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "REWRITTEN", "dead_at", killedAt))),
                snapshot(orderId), "after the Rewrite and the refused remote accept");
        assertOnlySignatureImages(orderId, List.of(), "the refused remote accept stored no signature");

        assertRaceMessages(orderId, order.orderNumber(), null, false, "Rewrite first vs remote");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertFinalLink(orderId, order.invoiceTokenId(), "REWRITTEN", killedAt);
        assertCurrentVersionAndMirror(orderId, 2, null, null);
        assertPublicDeadView(order.invoiceToken(), "REWRITTEN", INVOICE_LINK_REWRITTEN_MESSAGE);
    }

    // ================================================================
    // 2. Rewrite vs D.8 (in-app signing)
    // ================================================================

    /**
     * D.8 locks first: D.8 carrying v1 is observed waiting on the holder, then the Rewrite carrying v1 queued
     * BEHIND it; the holder rolls back. D.8 signs v1 into v2 (its 1 x 1 upload stored verbatim), commits and emails
     * v2's signed PDF. The Rewrite then reads the signed v2 under the lock and refuses the v1 it carries: 409
     * {@code INVOICE_VERSION_STALE}, nothing written, nothing sent.
     */
    @Test
    void rewriteVsD8_d8LocksFirst_rewriteCarryingOldVersionGets409_writesNothing() throws Exception {
        SeededOrder order = seedInvoicedOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        OrderSnapshot before = snapshot(orderId);

        RaceResults race = raceInOrder(orderId,
                staffStep("D.8 (carrying v1)", invoiceAccept(orderId, 1)),
                staffStep("Rewrite (carrying v1)", rewriteInvoice(orderId, 1)));

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId), "exactly v1 and D.8's v2 - no Rewrite version");
        AppendedVersion signed = assertSigningVersion(order, invoiceV1, 2, staffUserId, ONE_PIXEL_PNG, ONE_PIXEL_PNG,
                race, "D.8's v2");
        assertSignedResponse(race.first(), D8_EMAILED_MESSAGE, signed, "D.8 (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "Rewrite (carrying v1, queued behind D.8)");

        assertSnapshot(plus(before, signed), snapshot(orderId), "after D.8's signing and the refused Rewrite");
        assertOnlySignatureImages(orderId, List.of(signed.signatureFile()), "only D.8's signature image");
        assertRaceMessages(orderId, order.orderNumber(), signed, false, "D.8 first vs Rewrite");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertCurrentVersionAndMirror(orderId, 2, signed.acceptedAt(), signed.stamp());
    }

    /**
     * The REWRITE locks first: the Rewrite carrying v1 is observed waiting on the holder, then D.8 carrying v1
     * queued BEHIND it; the holder rolls back. The Rewrite appends the unsigned v2 and commits; D.8 then reads v2
     * under the lock and refuses the v1 it carries: 409 {@code INVOICE_VERSION_STALE} - no signature, version, PDF,
     * email or marker of D.8's.
     */
    @Test
    void rewriteVsD8_rewriteLocksFirst_d8CarryingOldVersionGets409_writesNothing() throws Exception {
        SeededOrder order = seedInvoicedOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        OrderSnapshot before = snapshot(orderId);

        RaceResults race = raceInOrder(orderId,
                staffStep("Rewrite (carrying v1)", rewriteInvoice(orderId, 1)),
                staffStep("D.8 (carrying v1)", invoiceAccept(orderId, 1)));

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId), "exactly v1 and the Rewrite's v2 - no D.8 version");
        AppendedVersion rewritten = assertRewriteVersion(order, invoiceV1, 2, race, "the Rewrite's v2");
        assertRewriteResponse(race.first(), rewritten, "Rewrite (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "D.8 (carrying v1, queued behind the Rewrite)");

        assertSnapshot(plus(before, rewritten), snapshot(orderId), "after the Rewrite and the refused D.8");
        assertOnlySignatureImages(orderId, List.of(), "the refused D.8 stored no signature");
        assertRaceMessages(orderId, order.orderNumber(), null, false, "Rewrite first vs D.8");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertCurrentVersionAndMirror(orderId, 2, null, null);
    }

    // ================================================================
    // 3. Rewrite vs payment (D.7)
    // ================================================================

    /**
     * The PAYMENT locks first: it is observed waiting on the holder, then the Rewrite carrying v1 queued BEHIND it;
     * the holder rolls back. The payment appends v2 (50.00 paid, 60.00 due) and commits; the Rewrite then reads v2
     * under the lock and refuses the v1 it carries: 409 {@code INVOICE_VERSION_STALE}, nothing written.
     */
    @Test
    void rewriteVsPayment_paymentLocksFirst_rewriteCarryingOldVersionGets409_writesNothing() throws Exception {
        SeededOrder order = seedInvoicedOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        OrderSnapshot before = snapshot(orderId);

        RaceResults race = raceInOrder(orderId,
                staffStep("payment (D.7)", recordPayment(orderId)),
                staffStep("Rewrite (carrying v1)", rewriteInvoice(orderId, 1)));

        assertPaymentMutation(race.first(), PAYMENT_RECORDED_MESSAGE, 2, PAYMENT_AMOUNT, "60.00", "payment (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "Rewrite (carrying v1, queued behind the payment)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId), "exactly v1 and the payment's v2 - no Rewrite version");
        AppendedVersion paid = assertCarriedVersion(order, invoiceV1, 2, PAYMENT_AMOUNT, "60.00", race, "the payment's v2");
        OrderSnapshot after = snapshot(orderId);
        Map<String, Object> paymentRow = assertSinglePayment(after, "the race's payment");
        assertSnapshot(plus(before, paid).withPayments(List.of(paymentRow)), after,
                "after the payment and the refused Rewrite");
        assertRaceMessages(orderId, order.orderNumber(), null, false, "payment first vs Rewrite");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertCurrentVersionAndMirror(orderId, 2, null, null);
    }

    /**
     * The REWRITE locks first: the Rewrite carrying v1 appends the unsigned v2 and commits; the payment queued
     * BEHIND it carries no version, so it succeeds against the NEW current version: v3 carries v2 forward with
     * 50.00 paid and 60.00 due. Neither sends anything.
     */
    @Test
    void rewriteVsPayment_rewriteLocksFirst_paymentSucceedsAgainstNewCurrentVersion() throws Exception {
        SeededOrder order = seedInvoicedOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        OrderSnapshot before = snapshot(orderId);

        RaceResults race = raceInOrder(orderId,
                staffStep("Rewrite (carrying v1)", rewriteInvoice(orderId, 1)),
                staffStep("payment (D.7)", recordPayment(orderId)));

        Assertions.assertEquals(List.of(1, 2, 3), invoiceVersions(orderId),
                "v1, the Rewrite's v2 and the payment's v3 on top of it");
        AppendedVersion rewritten = assertRewriteVersion(order, invoiceV1, 2, race, "the Rewrite's v2");
        assertRewriteResponse(race.first(), rewritten, "Rewrite (queue head)");
        assertPaymentMutation(race.second(), PAYMENT_RECORDED_MESSAGE, 3, PAYMENT_AMOUNT, "60.00",
                "payment (queued behind the Rewrite)");
        AppendedVersion paid = assertCarriedVersion(order, rewritten.row(), 3, PAYMENT_AMOUNT, "60.00", race,
                "the payment's v3 (carrying the Rewrite's v2)");

        OrderSnapshot after = snapshot(orderId);
        Map<String, Object> paymentRow = assertSinglePayment(after, "the race's payment");
        assertSnapshot(plus(plus(before, rewritten), paid).withPayments(List.of(paymentRow)), after,
                "after the Rewrite and the payment queued behind it");
        assertRaceMessages(orderId, order.orderNumber(), null, false, "Rewrite first vs payment");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertCurrentVersionAndMirror(orderId, 3, null, null);
    }

    // ================================================================
    // 4. Rewrite vs payment void (D.10)
    // ================================================================

    /**
     * The seed already has a 50.00 payment (v2: 50.00 paid, 60.00 due). The VOID locks first: it voids the payment
     * and appends v3 (nothing paid, 110.00 due) and commits; the Rewrite queued BEHIND it, carrying v2, reads v3
     * under the lock and is refused: 409 {@code INVOICE_VERSION_STALE}, nothing written.
     */
    @Test
    void rewriteVsVoid_voidLocksFirst_rewriteCarryingOldVersionGets409_writesNothing() throws Exception {
        SeededOrder order = seedInvoicedOrder();
        long orderId = order.orderId();
        long paymentId = recordPaymentForSeed(orderId);
        Map<String, Object> invoiceV2 = invoiceRow(orderId, 2);
        OrderSnapshot before = snapshot(orderId);
        Map<String, Object> paymentBefore = before.payments().get(0);

        RaceResults race = raceInOrder(orderId,
                staffStep("void (D.10)", voidPayment(orderId, paymentId)),
                staffStep("Rewrite (carrying v2)", rewriteInvoice(orderId, 2)));

        assertPaymentMutation(race.first(), PAYMENT_VOIDED_MESSAGE, 3, "0.00", "110.00", "void (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "Rewrite (carrying v2, queued behind the void)");

        Assertions.assertEquals(List.of(1, 2, 3), invoiceVersions(orderId), "v1, the seed's v2 and the void's v3 - no Rewrite version");
        AppendedVersion voided = assertCarriedVersion(order, invoiceV2, 3, "0.00", "110.00", race, "the void's v3");
        OrderSnapshot after = snapshot(orderId);
        Map<String, Object> voidedRow = assertVoidedPaymentRow(after, paymentBefore, race, "the voided payment");
        assertSnapshot(plus(before, voided).withPayments(List.of(voidedRow)), after,
                "after the void and the refused Rewrite");
        assertRaceMessages(orderId, order.orderNumber(), null, false, "void first vs Rewrite");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertCurrentVersionAndMirror(orderId, 3, null, null);
    }

    /**
     * The REWRITE locks first: carrying v2 (the seed's payment version), it appends v3 from the live order (50.00
     * paid, 60.00 due) and commits; the void queued BEHIND it carries no version, so it succeeds against the NEW
     * current version: the payment is voided and v4 carries v3 forward with nothing paid and 110.00 due.
     */
    @Test
    void rewriteVsVoid_rewriteLocksFirst_voidSucceedsAgainstNewCurrentVersion() throws Exception {
        SeededOrder order = seedInvoicedOrder();
        long orderId = order.orderId();
        long paymentId = recordPaymentForSeed(orderId);
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        OrderSnapshot before = snapshot(orderId);
        Map<String, Object> paymentBefore = before.payments().get(0);

        RaceResults race = raceInOrder(orderId,
                staffStep("Rewrite (carrying v2)", rewriteInvoice(orderId, 2)),
                staffStep("void (D.10)", voidPayment(orderId, paymentId)));

        Assertions.assertEquals(List.of(1, 2, 3, 4), invoiceVersions(orderId),
                "v1, the seed's v2, the Rewrite's v3 and the void's v4 on top of it");
        AppendedVersion rewritten = assertRewriteVersion(order, invoiceV1, 3, race, "the Rewrite's v3",
                "total_paid", new BigDecimal(PAYMENT_AMOUNT), "balance_due", new BigDecimal("60.00"));
        assertRewriteResponse(race.first(), rewritten, "Rewrite (queue head)");
        assertPaymentMutation(race.second(), PAYMENT_VOIDED_MESSAGE, 4, "0.00", "110.00", "void (queued behind the Rewrite)");
        AppendedVersion voided = assertCarriedVersion(order, rewritten.row(), 4, "0.00", "110.00", race,
                "the void's v4 (carrying the Rewrite's v3)");

        OrderSnapshot after = snapshot(orderId);
        Map<String, Object> voidedRow = assertVoidedPaymentRow(after, paymentBefore, race, "the voided payment");
        assertSnapshot(plus(plus(before, rewritten), voided).withPayments(List.of(voidedRow)), after,
                "after the Rewrite and the void queued behind it");
        assertRaceMessages(orderId, order.orderNumber(), null, false, "Rewrite first vs void");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertCurrentVersionAndMirror(orderId, 4, null, null);
    }

    // ================================================================
    // 5. Rewrite vs Path A (Create Invoice from the accepted quote)
    // ================================================================

    /**
     * The quote v1 (250.00 ex / 275.00 inc) was accepted publicly BEFORE the race; the current invoice v1 is
     * unsigned. PATH A locks first: it converts the accepted quote into invoice v2 (the quote's frozen snapshot
     * and inherited signature), commits and emails v2. The Rewrite queued BEHIND it, carrying v1, reads Path A's
     * signed v2 under the lock and is refused: 409 {@code INVOICE_VERSION_STALE} - the signature Path A carried
     * over is not cleared and nothing of the Rewrite's is written.
     */
    @Test
    void rewriteVsPathA_pathALocksFirst_rewriteCarryingOldVersionGets409_writesNothing() throws Exception {
        PathAOrder order = seedPathAOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> acceptedQuote = quoteVersionRow(orderId, 1);
        List<Map<String, Object>> signatureImages = signatureImages(orderId);
        OrderSnapshot before = snapshot(orderId);

        RaceResults race = raceInOrder(orderId,
                staffStep("Path A (carrying quote v1)", createInvoiceFromQuote(orderId, order.acceptedQuoteVersionId())),
                staffStep("Rewrite (carrying v1)", rewriteInvoice(orderId, 1)));

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId), "exactly v1 and Path A's v2 - no Rewrite version");
        AppendedVersion converted = assertPathAVersion(order, invoiceV1, acceptedQuote, 2, race, "Path A's v2");
        assertSignedResponse(race.first(), PATH_A_EMAILED_MESSAGE, converted, "Path A (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "Rewrite (carrying v1, queued behind Path A)");

        assertSnapshot(plus(before, converted), snapshot(orderId), "after Path A's conversion and the refused Rewrite");
        Assertions.assertEquals(signatureImages, signatureImages(orderId),
                "no new signature image: Path A inherits the accepted quote's");
        assertRaceMessages(orderId, order.orderNumber(), converted, false, "Path A first vs Rewrite");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertCurrentVersionAndMirror(orderId, 2, converted.acceptedAt(), converted.stamp());
    }

    /**
     * The REWRITE locks first: carrying v1, it appends the unsigned v2 from the LIVE order (the accepted quote's
     * D6b price, 250.00 ex / 275.00 inc) and commits. Path A queued BEHIND it carries the accepted quote's id, which
     * is still the latest accepted version, and the current invoice (the Rewrite's v2) is unsigned, so its
     * preconditions hold: it converts the accepted quote into v3 with the inherited signature and emails it.
     */
    @Test
    void rewriteVsPathA_rewriteLocksFirst_pathASucceedsFromAcceptedQuote() throws Exception {
        PathAOrder order = seedPathAOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> acceptedQuote = quoteVersionRow(orderId, 1);
        List<Map<String, Object>> signatureImages = signatureImages(orderId);
        OrderSnapshot before = snapshot(orderId);

        RaceResults race = raceInOrder(orderId,
                staffStep("Rewrite (carrying v1)", rewriteInvoice(orderId, 1)),
                staffStep("Path A (carrying quote v1)", createInvoiceFromQuote(orderId, order.acceptedQuoteVersionId())));

        Assertions.assertEquals(List.of(1, 2, 3), invoiceVersions(orderId),
                "v1, the Rewrite's v2 and Path A's v3 on top of it");
        AppendedVersion rewritten = assertRewriteVersion(order.seeded(), invoiceV1, 2, race, "the Rewrite's v2",
                "sale_price_ex_gst", new BigDecimal("250.00"), "sale_price_inc_gst", new BigDecimal("275.00"),
                "balance_due", new BigDecimal("275.00"));
        assertRewriteResponse(race.first(), rewritten, "Rewrite (queue head)");
        AppendedVersion converted = assertPathAVersion(order, invoiceV1, acceptedQuote, 3, race,
                "Path A's v3 (after the Rewrite's unsigned v2)");
        assertSignedResponse(race.second(), PATH_A_EMAILED_MESSAGE, converted, "Path A (queued behind the Rewrite)");

        assertSnapshot(plus(plus(before, rewritten), converted), snapshot(orderId),
                "after the Rewrite and Path A queued behind it");
        Assertions.assertEquals(signatureImages, signatureImages(orderId),
                "no new signature image: Path A inherits the accepted quote's");
        assertRaceMessages(orderId, order.orderNumber(), converted, false, "Rewrite first vs Path A");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertCurrentVersionAndMirror(orderId, 3, converted.acceptedAt(), converted.stamp());
    }

    // ================================================================
    // 6. Double Rewrite (the same expected version)
    // ================================================================

    /**
     * Two Rewrites carrying the SAME version (v1, the current one, with an ACTIVE link): the first is observed
     * waiting on the holder, the second queued BEHIND the first; the holder rolls back. The queue head appends v2
     * and kills the link as REWRITTEN; the duplicate then reads v2 under the lock and is refused: 409
     * {@code INVOICE_VERSION_STALE}. Exactly one appended version, one PDF and one link kill.
     */
    @Test
    void doubleRewrite_sameExpectedVersion_secondGets409_exactlyOneVersionOnePdfOneLinkKill() throws Exception {
        LinkedOrder order = seedLinkedOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);

        RaceResults race = raceInOrder(orderId,
                staffStep("Rewrite #1 (carrying v1)", rewriteInvoice(orderId, 1)),
                staffStep("Rewrite #2 (carrying v1)", rewriteInvoice(orderId, 1)));

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId), "exactly ONE appended version");
        AppendedVersion rewritten = assertRewriteVersion(order.seeded(), invoiceV1, 2, race, "the queue head's v2");
        assertRewriteResponse(race.first(), rewritten, "Rewrite #1 (queue head)");
        assertRefusal(race.second(), 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "Rewrite #2 (the queued duplicate)");

        Timestamp killedAt = (Timestamp) invoiceTokenRow(order.invoiceTokenId()).get("dead_at");
        Assertions.assertNotNull(killedAt, "the queue head killed the ACTIVE link");
        assertWithinRace(killedAt, race, "the link kill time");
        assertSnapshot(plus(before, rewritten)
                        .withInvoiceTokens(replaceRow(before.invoiceTokens(), "invoice_token_id",
                                withColumns(link, "status", "REWRITTEN", "dead_at", killedAt))),
                snapshot(orderId), "after the queue head's Rewrite and the refused duplicate");
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND file_name = ?",
                orderStoragePrefix(orderId) + "%", invoicePdfName(order.orderNumber(), 2)), "exactly ONE v2 PDF");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND file_name = ?",
                orderStoragePrefix(orderId) + "%", invoicePdfName(order.orderNumber(), 3)), "no v3 PDF");

        assertRaceMessages(orderId, order.orderNumber(), null, false, "double Rewrite");
        Assertions.assertEquals(List.of(), race.serviceWarnings(), "no WARN from any service");
        assertFinalLink(orderId, order.invoiceTokenId(), "REWRITTEN", killedAt);
        assertCurrentVersionAndMirror(orderId, 2, null, null);
    }

    // ================================================================
    // 7. A newer version committed by the lock holder while the Rewrite waits
    // ================================================================

    /**
     * The LOCK HOLDER commits the newer version: the Rewrite carries v1 (the current version its confirmation was
     * bound to) and is observed waiting on the holder; inside the holder's transaction invoice v2 is appended (an
     * unsigned copy of v1 with its own PDF, invisible to the Rewrite until the commit, the way a payment appends
     * one); the holder commits, which publishes v2 and releases the row in one step. The Rewrite then takes the
     * lock, reads the current invoice (v2) and refuses the v1 it carries: 409 {@code INVOICE_VERSION_STALE}.
     * Nothing of the Rewrite's is written: no v3, no PDF, no mirror reset, and the ACTIVE link is untouched - a
     * fresh Rewrite carrying v2 then appends v3 and kills it as REWRITTEN, so staleness was the only reason.
     */
    @Test
    void rewrite_newerVersionCommittedByTheLockHolderWhileItWaits_409_activeLinkUntouched_writesNothing()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        long orderId = order.orderId();
        Map<String, Object> invoiceV1 = invoiceRow(orderId, 1);
        Map<String, Object> link = invoiceTokenRow(order.invoiceTokenId());
        OrderSnapshot before = snapshot(orderId);
        // The holder's v2 gets its own real PDF file (v1's bytes), so every stored_file keeps its file on disk.
        byte[] holderPdf = fileStorageService.read(
                (String) storedFileRow(longValue(invoiceV1.get("stored_file_id"))).get("storage_path"));
        String holderPdfPath = fileStorageService.store(holderPdf, businessId, orderId, "pdf");

        MvcResult rewriteResult;
        long holderFileId;
        List<String> warnings;
        try (ServiceLogs logs = new ServiceLogs(PublicInvoiceService.class, OrderInvoiceService.class,
                OrderPaymentService.class)) {
            try (Race race = new Race(orderId)) {
                Future<MvcResult> rewrite = race.submit(staffStep("Rewrite (carrying v1)", rewriteInvoice(orderId, 1)));
                int rewritePid = awaitWaiterBlockedBy(race, race.holderPid(), "Rewrite (carrying v1)", rewrite);

                // INSIDE the holder transaction, while the Rewrite waits on it: append v2 (published by the commit).
                holderFileId = race.queryLongInHolder(
                        "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                                + "VALUES (?, ?, 'application/pdf', ?) RETURNING stored_file_id",
                        invoicePdfName(order.orderNumber(), 2), holderPdfPath, (long) holderPdf.length);
                Assertions.assertEquals(1, race.updateInHolder(HOLDER_APPEND_COPY_OF_VERSION_SQL,
                        2, holderFileId, orderId, 1), "the holder appends exactly invoice v2");
                Assertions.assertEquals(List.of(1), invoiceVersions(orderId),
                        "the holder's v2 is invisible to every other transaction until it commits");
                Assertions.assertFalse(rewrite.isDone(), "the Rewrite must still be parked on the order lock");
                Assertions.assertTrue(isBlockedBy(rewritePid, race.holderPid()),
                        "the Rewrite must still be waiting on the holder; activity: " + activity());

                race.commitHolder(); // publishes v2 AND releases the order row in one step

                rewriteResult = awaitResponse(rewrite, "Rewrite (carrying v1)");
            }
            warnings = logs.warningsAndErrors();
        }

        // The Rewrite took the lock only after v2 was committed, read the current invoice there and refused its v1.
        assertRefusal(rewriteResult, 409, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "Rewrite (carrying v1, v2 committed by the holder while it waited)");
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId), "no Rewrite version: exactly v1 and the holder's v2");
        Map<String, Object> holderV2 = invoiceRow(orderId, 2);
        Assertions.assertEquals(withColumns(invoiceV1, "invoice_id", holderV2.get("invoice_id"), "version_number", 2,
                        "stored_file_id", holderFileId, "created_at", holderV2.get("created_at")),
                holderV2, "invoice v2 is exactly the holder's unsigned, unemailed copy of v1");
        Map<String, Object> holderFile = storedFileRow(holderFileId);
        assertSnapshot(before.withInvoices(append(before.invoices(), holderV2))
                        .withStoredFiles(sortedFiles(append(before.storedFiles(), holderFile)))
                        .withFilesOnDisk(plus(before.filesOnDisk(), holderPdfPath)),
                snapshot(orderId), "after the holder's v2 and the refused Rewrite");
        Assertions.assertEquals(link, invoiceTokenRow(order.invoiceTokenId()), "the ACTIVE link is untouched");
        Assertions.assertEquals("ACTIVE", link.get("status"), "the link is still ACTIVE");
        assertRaceMessages(orderId, order.orderNumber(), null, false, "the holder's v2 and the refused Rewrite");
        Assertions.assertEquals(List.of(), warnings, "no WARN from any service");
        assertCurrentVersionAndMirror(orderId, 2, null, null);

        // A fresh Rewrite carrying the version now current succeeds and only then kills the link.
        MvcResult fresh = mockMvc.perform(rewriteInvoice(orderId, 2)).andReturn();
        assertStatus(fresh, 201, "a fresh Rewrite carrying v2");
        Assertions.assertEquals(List.of(1, 2, 3), invoiceVersions(orderId), "the fresh Rewrite appended v3");
        Map<String, Object> deadLink = invoiceTokenRow(order.invoiceTokenId());
        Assertions.assertEquals("REWRITTEN", deadLink.get("status"), "the fresh Rewrite killed the link");
        Assertions.assertNotNull(deadLink.get("dead_at"), "the killed link has a dead_at");
    }

    // ================================================================
    // Race harness (copied from RemoteInvoiceAcceptRaceIntegrationTest; the commit-capable holder from
    // InvoiceVersionPreconditionConcurrencyIntegrationTest)
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
     * request's post-commit email and notification), and the WARN+ events of both invoice services and the
     * payment service are captured for the whole race.
     */
    private RaceResults raceInOrder(long orderId, RaceStep first, RaceStep second) throws Exception {
        LocalDateTime startedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        MvcResult firstResult;
        MvcResult secondResult;
        try (ServiceLogs logs = new ServiceLogs(PublicInvoiceService.class, OrderInvoiceService.class,
                OrderPaymentService.class)) {
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
     * holding {@code FOR NO KEY UPDATE} on the order, plus a small executor for the competing requests, each on its
     * own MockMvc with the real security filter chain. The holder may write inside its transaction
     * ({@link #updateInHolder} / {@link #queryLongInHolder}) and then {@link #commitHolder()} or
     * {@link #rollbackHolder()}. {@link #close()} - always reached via try-with-resources - rolls back and closes
     * a still-open holder, lets in-flight requests finish, and as a last resort cancels a straggler, but only one
     * of the request backends this race {@linkplain #track tracked}.
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
     * Poll (every ~{@value #POLL_INTERVAL_MILLIS}ms, up to {@value #BARRIER_TIMEOUT_MILLIS}ms) until the request's
     * backend is observed waiting on the order-row lock behind {@code blockerPid}; returns that backend's pid. Fails
     * fast if the request (or an earlier queued one) completes first - a request that never waited never serialised
     * on the order lock. Every backend observed queued behind {@code blockerPid} is tracked on {@code race}, so its
     * last-resort cancel can reach it (and only it).
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
     * The queue-order proof taken right before the holder is released: the first request waits on the holder,
     * the second is queued BEHIND THE FIRST (the row's tuple lock), so the first is guaranteed to take the row
     * lock - and commit or roll back - before the second can.
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
     * Last resort only: cancel the race's TRACKED request backends that are still parked on an order-row lock in
     * this database - never any other backend. A pooled backend outlives its request, so a tracked pid is skipped
     * once it no longer waits on such a lock, and this backend is never cancelled.
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
     * appender is attached to the logger, not to a thread). The list is read only after the workers finished (each
     * response is awaited through its future, which publishes everything the request logged).
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
     * THE one way this class performs a public (token-only) request: never with a session, and whatever the status,
     * the request must end without any session and the response without a {@code Set-Cookie}.
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

    /** D.2 manual Rewrite (Batch A PR 2): body {@code {"expected_version_number": N}} = the version the client saw. */
    private RequestBuilder rewriteInvoice(long orderId, int seenVersion) {
        return post(orderUrl(orderId) + "/invoices/rewrite").session(session(staffUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_version_number\": " + seenVersion + "}");
    }

    /**
     * The public remote accept exactly as the page sends it: the {@code expected_version_number} FORM field and one
     * PNG {@code signature} part; no session, no slug, no query string.
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

    /** D.7: a 50.00 EFTPOS payment by the order's salesperson. */
    private RequestBuilder recordPayment(long orderId) {
        return post(orderUrl(orderId) + "/payments").session(session(staffUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\":\"EFTPOS\",\"amount\":" + PAYMENT_AMOUNT + ",\"payment_reference\":null}");
    }

    /** D.10 void of a recorded payment (empty object body), by the order's salesperson. */
    private RequestBuilder voidPayment(long orderId, long paymentTransactionId) {
        return post(orderUrl(orderId) + "/payments/" + paymentTransactionId + "/void").session(session(staffUserId))
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    /** Path A (16G PR 3): body {@code {"quote_version_id": N}} = the latest accepted version the client saw. */
    private RequestBuilder createInvoiceFromQuote(long orderId, long seenAcceptedQuoteVersionId) {
        return post(orderUrl(orderId) + "/quote/create-invoice").session(session(staffUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quote_version_id\": " + seenAcceptedQuoteVersionId + "}");
    }

    // ================================================================
    // Committed self-seeding (no test transaction; real flows)
    // ================================================================

    /**
     * The tenant, committed: an active business (ABN + bank details, no terms) with the class slug, one store
     * (with a notification email), two salespeople - {@value #STAFF_CODE} (the staff requests) and
     * {@value #SENDER_CODE} (the link sender) - and their store access.
     */
    private void seedTenant() {
        Long business = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number) "
                        + "VALUES (?, ?, TRUE, '53 004 085 616', 'Kestrel Race Bank', '062-222', ?, '76543210') "
                        + "RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, BUSINESS_NAME + " Pty Ltd");
        Assertions.assertNotNull(business, "setup: business insert returned no id");
        businessId = business;
        Integer store = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Kestrel Race Showroom', ?, '0290006789', ?, '6 Rewrite Way', 'Burwood', 'NSW', "
                        + " '2134') RETURNING store_id",
                Integer.class, businessId, STORE_CODE, STORE_EMAIL);
        Assertions.assertNotNull(store, "setup: store insert returned no id");
        storeId = store;
        staffUserId = insertUser("Robin", "Staffer", STAFF_CODE, "robin.staffer.rewrite.race@example.com");
        senderUserId = insertUser("Sam", "Sender", SENDER_CODE, "sam.sender.rewrite.race@example.com");
        Assertions.assertNotEquals(staffUserId, senderUserId, "setup: two distinct salespeople");
        orderSequence = 0;
    }

    private long insertUser(String firstName, String lastName, String salespersonCode, String email) {
        Long user = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'not-a-login-hash-rewrite-race') RETURNING user_id",
                Long.class, businessId, firstName, lastName, salespersonCode, email);
        Assertions.assertNotNull(user, "setup: app_user insert returned no id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, user, storeId);
        return user;
    }

    /** One committed order of the tenant. */
    private record SeededOrder(long orderId, String orderNumber) {
    }

    /** An order whose UNSIGNED D.1 invoice v1 has an ACTIVE invoice link (sent by {@value #SENDER_CODE}). */
    private record LinkedOrder(SeededOrder seeded, String invoiceToken, long invoiceTokenId) {

        long orderId() {
            return seeded.orderId();
        }

        String orderNumber() {
            return seeded.orderNumber();
        }
    }

    /** A Path A order: the UNSIGNED D.1 invoice v1 and the publicly ACCEPTED quote v1 (signed before any race). */
    private record PathAOrder(SeededOrder seeded, long acceptedQuoteVersionId) {

        long orderId() {
            return seeded.orderId();
        }

        String orderNumber() {
            return seeded.orderNumber();
        }
    }

    /** A sent invoice link: the plaintext token (from the recorded email) and its row id. */
    private record SentLink(String token, long invoiceTokenId) {
    }

    /**
     * A committed order that passes every D.1 / quote / Path A gate: details of sale, proposed lay date 2026-12-01
     * CONFIRMED, the customer {@value #CUSTOMER_NAME} with a PADDED valid email, INSTALLATION and BILLING addresses,
     * and one charge line of 100.00 ex / 40.00 cost (110.00 inc).
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
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Rewrite race supply and lay', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, storeId, chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Rewrite race supply and lay', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        return new SeededOrder(orderId, orderNumber);
    }

    /** An invoice-ready order with its unsigned D.1 v1; the race starts with empty recorders. */
    private SeededOrder seedInvoicedOrder() throws Exception {
        SeededOrder seeded = seedInvoiceReadyOrder();
        createUnsignedInvoiceV1(seeded.orderId());
        resetSenders();
        return seeded;
    }

    private LinkedOrder seedLinkedOrder() throws Exception {
        SeededOrder seeded = seedInvoiceReadyOrder();
        createUnsignedInvoiceV1(seeded.orderId());
        SentLink link = sendInvoiceLink(seeded);
        assertNoEmailMarkers(seeded.orderId(), "setup");
        // Every send so far belongs to the seed: the race starts with empty recorders.
        resetSenders();
        return new LinkedOrder(seeded, link.token(), link.invoiceTokenId());
    }

    private PathAOrder seedPathAOrder() throws Exception {
        SeededOrder seeded = seedInvoiceReadyOrder();
        long orderId = seeded.orderId();
        // An unsigned Path B v1: D5(b) allows Path A over an unsigned current invoice.
        createUnsignedInvoiceV1(orderId);
        // The REAL quote chain: draft -> protected send-email -> token from the recorded email -> public accept.
        saveDraft(orderId, ITEMISED_DRAFT);
        acceptQuotePublicly(sendQuoteAndExtractToken(orderId));
        Map<String, Object> accepted = quoteVersionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", accepted.get("status"), "setup: quote v1 accepted publicly");
        Assertions.assertEquals(CUSTOMER_NAME, accepted.get("accepted_customer_name"), "setup: the V17 name");
        Assertions.assertNotNull(accepted.get("accepted_signature_file_id"), "setup: quote v1's stored signature");
        assertMoney("275.00", accepted.get("quote_total_inc_gst"), "setup: the accepted quote's inc total");
        assertNoEmailMarkers(orderId, "setup");
        resetSenders();
        return new PathAOrder(seeded, longValue(accepted.get("quote_version_id")));
    }

    /** D.1 Create through the API: the unsigned, unemailed Path B invoice v1 (110.00 inc, the frozen Invoice To). */
    private void createUnsignedInvoiceV1(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(session(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: invoice create (D.1)");
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, json(result).path("message").asText(), "setup: D.1 message");
        Assertions.assertEquals(List.of(1), invoiceVersions(orderId), "setup: exactly the D.1 invoice v1");
        Map<String, Object> invoice = invoiceRow(orderId, 1);
        Assertions.assertNull(invoice.get("accepted_at"), "setup: invoice v1 is unsigned");
        assertMoney("110.00", invoice.get("sale_price_inc_gst"), "setup: invoice v1 inc");
        Assertions.assertEquals(CUSTOMER_NAME, invoice.get("customer_name_snapshot"), "setup: v1's frozen name");
        Assertions.assertNull(invoice.get("terms_snapshot"), "setup: the business has no terms");
        Assertions.assertEquals(staffUserId, longValue(invoice.get("created_by_user_id")), "setup: v1's creator");
    }

    /** A 50.00 payment before the race (v2: 50.00 paid, 60.00 due); returns its id. */
    private long recordPaymentForSeed(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(recordPayment(orderId)).andReturn();
        assertStatus(result, 201, "setup: payment (D.7)");
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(orderId), "setup: the payment appended v2");
        long paymentId = json(result).path("data").path("payment_transaction").path("payment_transaction_id").asLong();
        Assertions.assertTrue(paymentId > 0, "setup: the payment id");
        resetSenders();
        return paymentId;
    }

    private void saveDraft(long orderId, String body) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(orderId) + "/quote/draft").session(session(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertStatus(result, 200, "setup: quote draft save");
    }

    /** Issue quote v1 through the protected send-email and take the plaintext token from the recorded email. */
    private String sendQuoteAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/quote/send-email").session(session(staffUserId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: quote send-email (issue v1)");
        Assertions.assertEquals(QUOTE_SENT_MESSAGE, json(result).path("message").asText(), "setup: send message");
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
     * The protected send-link (16G contract section 8) as {@value #SENDER_CODE}: 201, exactly one link-only email
     * to the TRIMMED saved address carrying the {@code /i/{token}} link, and the token's ACTIVE row found by its
     * SHA-256 hash.
     */
    private SentLink sendInvoiceLink(SeededOrder order) throws Exception {
        int sentBefore = invoiceLinkEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(order.orderId()) + "/invoices/current/send-link")
                        .session(session(senderUserId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: send-link");
        Assertions.assertEquals(INVOICE_LINK_SENT_MESSAGE, json(result).path("message").asText(), "setup: send-link message");
        List<InvoiceLinkEmailRequest> sent = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly one link email per send");
        InvoiceLinkEmailRequest email = sent.get(sent.size() - 1);
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), "setup: the TRIMMED saved customer email");
        Matcher matcher = INVOICE_LINK_PATTERN.matcher(email.bodyText());
        Assertions.assertTrue(matcher.find(), "setup: the link email carries the /i/{token} link");
        String token = matcher.group(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM invoice_token WHERE token_hash = ?", sha256Hex(token));
        Assertions.assertEquals(order.orderId(), longValue(row.get("order_id")), "setup: the link is the order's");
        Assertions.assertEquals("ACTIVE", row.get("status"), "setup: the link is ACTIVE");
        Assertions.assertNull(row.get("dead_at"), "setup: the link is alive");
        Assertions.assertEquals(1, count("SELECT COUNT(*) FROM invoice_token WHERE order_id = ?", order.orderId()),
                "setup: the order's only invoice link");
        return new SentLink(token, longValue(row.get("invoice_token_id")));
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
     * Delete one committed order and everything it owns in ONE transaction (bounded by a lock timeout), in FK-safe
     * order: its invoice links, quote tokens, invoices (they may reference a quote_version and the quote's
     * signature), versions (cascade lines), the draft (cascade lines), the other order children, the order, then
     * its stored_file rows. The files on disk are deleted only after that commit, then the order directory (which
     * also catches orphans whose rows never committed).
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
     * last_emailed_at mirror and updated_at included), every invoice row (each version's last_emailed_at included),
     * every stored_file row of the order and the files on disk under its directory, every invoice link, every quote
     * version and token, the draft and its lines, the customer / address / line rows, and the payments.
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

    /**
     * {@code base} plus one appended version: its invoice row, its new stored_file rows (its PDF and, for a new
     * signature, the signature image) and their files on disk, and the sales_order mirror set to the new current
     * version's {@code last_emailed_at} (Phase 13 section 11.1). Nothing else of the order changes.
     */
    private static OrderSnapshot plus(OrderSnapshot base, AppendedVersion version) {
        List<Map<String, Object>> files = new ArrayList<>(base.storedFiles());
        Set<String> paths = new TreeSet<>(base.filesOnDisk());
        if (version.signatureFile() != null) {
            files.add(version.signatureFile());
            Assertions.assertTrue(paths.add((String) version.signatureFile().get("storage_path")),
                    "the signature file is new");
        }
        files.add(version.pdfFile());
        Assertions.assertTrue(paths.add(version.pdfPath()), "the PDF file is new");
        return base.withHeader(withColumns(base.header(), "last_emailed_at", version.row().get("last_emailed_at")))
                .withInvoices(append(base.invoices(), version.row()))
                .withStoredFiles(sortedFiles(files))
                .withFilesOnDisk(paths);
    }

    /** Part-by-part equality of two snapshots (the expected one = the seed plus the winners' own writes). */
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

    /**
     * A version a race appended: its row, its PDF stored_file, and its signature stored_file when it stored a NEW
     * signature (null for a Rewrite, a payment, a void, and Path A, which inherits the quote's).
     */
    private record AppendedVersion(Map<String, Object> row, Map<String, Object> pdfFile,
                                   Map<String, Object> signatureFile) {

        int version() {
            return ((Number) row.get("version_number")).intValue();
        }

        Timestamp acceptedAt() {
            return (Timestamp) row.get("accepted_at");
        }

        Timestamp stamp() {
            return (Timestamp) row.get("last_emailed_at");
        }

        String pdfPath() {
            return (String) pdfFile.get("storage_path");
        }
    }

    /**
     * A Rewrite's version (Chunk 4 D.2): a fresh snapshot of the LIVE order - exactly {@code template}'s columns
     * (the D.1 v1: creator = the session user, details, money, frozen words, no terms, no source) with
     * {@code overrides} (the live totals where they differ from v1's), its own id / version / PDF / creation time,
     * dated within the race, UNSIGNED (a Rewrite clears any signature) and unemailed.
     */
    private AppendedVersion assertRewriteVersion(SeededOrder order, Map<String, Object> template, int version,
                                                 RaceResults race, String label, Object... overrides)
            throws IOException {
        Map<String, Object> row = invoiceRow(order.orderId(), version);
        assertDatedWithinRace(row, race, label);
        Map<String, Object> expected = withColumns(template,
                "invoice_id", row.get("invoice_id"),
                "version_number", version,
                "invoice_date", row.get("invoice_date"),
                "stored_file_id", row.get("stored_file_id"),
                "created_at", row.get("created_at"),
                "accepted_at", null,
                "accepted_customer_name", null,
                "accepted_signature_file_id", null,
                "last_emailed_at", null,
                "source_quote_version_id", null);
        Assertions.assertEquals(withColumns(expected, overrides), row,
                label + ": a fresh, unsigned, unemailed snapshot of the live order");
        return new AppendedVersion(row, assertPdfFile(order, row, version, label), null);
    }

    /**
     * A payment's or void's version: {@code carriedFrom} carried forward (due date, details, ex / inc, source,
     * terms, frozen words AND its acceptance) with the new {@code total_paid} / {@code balance_due}, dated within the
     * race, created by the order's salesperson, unemailed, with its own regenerated PDF.
     */
    private AppendedVersion assertCarriedVersion(SeededOrder order, Map<String, Object> carriedFrom, int version,
                                                 String totalPaid, String balanceDue, RaceResults race, String label)
            throws IOException {
        Map<String, Object> row = invoiceRow(order.orderId(), version);
        assertDatedWithinRace(row, race, label);
        Assertions.assertEquals(withColumns(carriedFrom,
                        "invoice_id", row.get("invoice_id"),
                        "version_number", version,
                        "invoice_date", row.get("invoice_date"),
                        "total_paid", new BigDecimal(totalPaid),
                        "balance_due", new BigDecimal(balanceDue),
                        "stored_file_id", row.get("stored_file_id"),
                        "created_by_user_id", staffUserId,
                        "created_at", row.get("created_at"),
                        "last_emailed_at", null),
                row, label + ": v" + version + " carries v" + ((Number) carriedFrom.get("version_number")).intValue()
                        + " forward with the new totals, unemailed");
        return new AppendedVersion(row, assertPdfFile(order, row, version, label), null);
    }

    /**
     * A signing version (D.8 or a remote accept) appended over {@code current}: EXACTLY {@code current} carried
     * forward (its invoice date, due date, details, money, frozen words, terms and source) plus its own id / version
     * / PDF / creation time, the signing time as {@code accepted_at} (within the race), the frozen name verbatim as
     * the accepted name, its own signature file, the expected creator and the stamp of its delivered email. The
     * signature file holds exactly {@code expectedStoredSignature} with the upload's dimensions, and the PDF carries
     * the acceptance caption of THIS signing.
     */
    private AppendedVersion assertSigningVersion(SeededOrder order, Map<String, Object> current, int version,
                                                 long expectedCreatorUserId, byte[] uploadedSignature,
                                                 byte[] expectedStoredSignature, RaceResults race, String label)
            throws IOException {
        Map<String, Object> row = invoiceRow(order.orderId(), version);
        Timestamp acceptedAt = (Timestamp) row.get("accepted_at");
        Assertions.assertNotNull(acceptedAt, label + ": the appended version is signed");
        assertWithinRace(acceptedAt, race, label + ": accepted_at is the signing time");
        Timestamp stamp = (Timestamp) row.get("last_emailed_at");
        Assertions.assertNotNull(stamp, label + ": the delivered signed-PDF email stamped the appended version");
        assertWithinRace(stamp, race, label + ": the email stamp");
        long signatureFileId = longValue(row.get("accepted_signature_file_id"));
        Assertions.assertEquals(withColumns(current,
                        "invoice_id", row.get("invoice_id"),
                        "version_number", version,
                        "stored_file_id", row.get("stored_file_id"),
                        "created_by_user_id", expectedCreatorUserId,
                        "created_at", row.get("created_at"),
                        "accepted_at", acceptedAt,
                        "accepted_customer_name", current.get("customer_name_snapshot"),
                        "accepted_signature_file_id", signatureFileId,
                        "last_emailed_at", stamp),
                row, label + ": exactly the current version carried forward, signed by this request");
        Assertions.assertEquals(CUSTOMER_NAME, row.get("accepted_customer_name"),
                label + ": the accepted name is the frozen customer_name_snapshot, verbatim");

        Map<String, Object> signatureFile = storedFileRow(signatureFileId);
        Assertions.assertEquals(signatureFileName(order.orderNumber(), version), signatureFile.get("file_name"),
                label + ": signature file name");
        Assertions.assertEquals("image/png", signatureFile.get("mime_type"), label + ": signature mime type");
        byte[] storedSignature = fileStorageService.read((String) signatureFile.get("storage_path"));
        Assertions.assertArrayEquals(expectedStoredSignature, storedSignature,
                label + ": the stored signature is exactly this request's upload (normalised for a remote one)");
        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(uploadedSignature));
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(storedSignature));
        Assertions.assertNotNull(stored, label + ": the stored signature decodes as an image");
        Assertions.assertEquals(uploaded.getWidth() + "x" + uploaded.getHeight(),
                stored.getWidth() + "x" + stored.getHeight(), label + ": the stored signature is this request's image");

        Map<String, Object> pdfFile = assertPdfFile(order, row, version, label);
        assertAcceptedCaption(fileStorageService.read((String) pdfFile.get("storage_path")), CUSTOMER_NAME,
                acceptedAt.toLocalDateTime(), label);
        return new AppendedVersion(row, pdfFile, signatureFile);
    }

    /**
     * Path A's version (16G contract sections 4.2, 5.5): {@code template}'s order-bound columns (the D.1 v1:
     * creator, due date, nothing paid) with the ACCEPTED quote's frozen snapshot verbatim - details, ex / inc totals
     * (the balance = inc), terms, the V17 Invoice To, the source - and its INHERITED acceptance (the quote's signing
     * time and name and the SAME signature stored_file, never a new one), dated within the race, with its own PDF and
     * the stamp of its delivered email.
     */
    private AppendedVersion assertPathAVersion(PathAOrder order, Map<String, Object> template,
                                               Map<String, Object> acceptedQuote, int version, RaceResults race,
                                               String label) throws IOException {
        Map<String, Object> row = invoiceRow(order.orderId(), version);
        assertDatedWithinRace(row, race, label);
        Timestamp stamp = (Timestamp) row.get("last_emailed_at");
        Assertions.assertNotNull(stamp, label + ": the delivered signed-PDF email stamped the version");
        assertWithinRace(stamp, race, label + ": the email stamp");
        Assertions.assertEquals(withColumns(template,
                        "invoice_id", row.get("invoice_id"),
                        "version_number", version,
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
        Map<String, Object> pdfFile = assertPdfFile(order.seeded(), row, version, label);
        Timestamp acceptedAt = (Timestamp) acceptedQuote.get("accepted_at");
        assertAcceptedCaption(fileStorageService.read((String) pdfFile.get("storage_path")),
                (String) acceptedQuote.get("accepted_customer_name"), acceptedAt.toLocalDateTime(), label);
        return new AppendedVersion(row, pdfFile, null);
    }

    /** The version's PDF stored_file: its name and mime type, a real PDF on disk of the recorded size. */
    private Map<String, Object> assertPdfFile(SeededOrder order, Map<String, Object> row, int version, String label)
            throws IOException {
        Map<String, Object> pdfFile = storedFileRow(longValue(row.get("stored_file_id")));
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), version), pdfFile.get("file_name"), label + ": PDF name");
        Assertions.assertEquals("application/pdf", pdfFile.get("mime_type"), label + ": PDF mime type");
        byte[] pdf = fileStorageService.read((String) pdfFile.get("storage_path"));
        Assertions.assertTrue(startsWithPdfMagic(pdf), label + ": the stored invoice is a PDF");
        Assertions.assertEquals((long) pdf.length, longValue(pdfFile.get("file_size")),
                label + ": PDF stored_file.file_size == the bytes on disk");
        return pdfFile;
    }

    // ================================================================
    // Assertions
    // ================================================================

    /** The remote accept's 201: exactly {@code {"data":{"state":"SIGNED"},"message":"Invoice accepted."}}. */
    private static void assertRemoteAccepted(MvcResult result, String label) throws Exception {
        assertStatus(result, 201, label);
        Assertions.assertEquals(JSON.readTree("{\"data\":{\"state\":\"SIGNED\"},\"message\":\""
                + REMOTE_ACCEPTED_MESSAGE + "\"}"), json(result), label + ": exactly the documented 201 body");
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

    /** A Rewrite's 201: "Invoice rewritten." and data.invoice = that committed, UNSIGNED row with no live link. */
    private static void assertRewriteResponse(MvcResult result, AppendedVersion rewritten, String label)
            throws Exception {
        assertStatus(result, 201, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": envelope keys");
        Assertions.assertEquals(INVOICE_REWRITTEN_MESSAGE, root.path("message").asText(), label + ": message");
        JsonNode invoice = root.path("data").path("invoice");
        Assertions.assertEquals(longValue(rewritten.row().get("invoice_id")), invoice.path("invoice_id").asLong(),
                label + ": data.invoice is the committed row");
        Assertions.assertEquals(rewritten.version(), invoice.path("version_number").asInt(), label + ": version");
        Assertions.assertFalse(invoice.path("accepted_signature_present").booleanValue(),
                label + ": a Rewrite clears the signature");
        for (String unsigned : List.of("accepted_at", "accepted_customer_name", "accepted_signature_download_path",
                "last_emailed_at", "invoice_link_expires_at")) {
            Assertions.assertTrue(invoice.has(unsigned) && invoice.get(unsigned).isNull(),
                    label + ": " + unsigned + " is present and null");
        }
    }

    /** A signing 201 (D.8 or Path A): the exact message and data.invoice = that committed, signed, stamped row. */
    private static void assertSignedResponse(MvcResult result, String message, AppendedVersion signed, String label)
            throws Exception {
        assertStatus(result, 201, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": envelope keys");
        Assertions.assertEquals(message, root.path("message").asText(), label + ": the delivered-email message");
        JsonNode invoice = root.path("data").path("invoice");
        Assertions.assertEquals(longValue(signed.row().get("invoice_id")), invoice.path("invoice_id").asLong(),
                label + ": data.invoice is the committed row");
        Assertions.assertEquals(signed.version(), invoice.path("version_number").asInt(), label + ": version");
        Assertions.assertTrue(invoice.path("accepted_signature_present").booleanValue(), label + ": signed");
        Assertions.assertEquals(CUSTOMER_NAME, invoice.path("accepted_customer_name").asText(), label + ": name");
        Assertions.assertFalse(invoice.path("last_emailed_at").isNull(), label + ": the delivered email's stamp");
    }

    /** A payment (D.7) or void (D.10) 201: its message, totals and E.1 {@code current_invoice}. */
    private static void assertPaymentMutation(MvcResult result, String message, int version, String totalPaid,
                                              String balanceDue, String label) throws Exception {
        assertStatus(result, 201, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": envelope keys");
        Assertions.assertEquals(message, root.path("message").asText(), label + ": message");
        JsonNode data = root.path("data");
        assertMoney(totalPaid, data.path("payment_summary").path("total_paid").decimalValue(),
                label + ": payment_summary.total_paid");
        assertMoney(balanceDue, data.path("payment_summary").path("balance_due").decimalValue(),
                label + ": payment_summary.balance_due");
        JsonNode invoice = data.path("current_invoice");
        Assertions.assertEquals(version, invoice.path("version_number").asInt(), label + ": current_invoice version");
        assertMoney(totalPaid, invoice.path("total_paid").decimalValue(), label + ": current_invoice.total_paid");
        assertMoney(balanceDue, invoice.path("balance_due").decimalValue(), label + ": current_invoice.balance_due");
        Assertions.assertFalse(invoice.path("accepted_signature_present").booleanValue(),
                label + ": current_invoice carries no acceptance");
        Assertions.assertTrue(invoice.path("last_emailed_at").isNull(), label + ": a payment / void version is never emailed");
    }

    /** The race's payment row: active, 50.00 EFTPOS. */
    private static Map<String, Object> assertSinglePayment(OrderSnapshot after, String label) {
        Assertions.assertEquals(1, after.payments().size(), label + ": exactly one payment row");
        Map<String, Object> row = after.payments().get(0);
        assertMoney(PAYMENT_AMOUNT, row.get("amount"), label + ": amount");
        Assertions.assertEquals("EFTPOS", String.valueOf(row.get("payment_method")), label + ": method");
        Assertions.assertNull(row.get("voided_at"), label + ": active");
        Assertions.assertNull(row.get("voided_by_user_id"), label + ": never voided");
        return row;
    }

    /** The seeded payment, now soft-voided in place by the session actor during the race - nothing else changed. */
    private Map<String, Object> assertVoidedPaymentRow(OrderSnapshot after, Map<String, Object> paymentBefore,
                                                       RaceResults race, String label) {
        Assertions.assertEquals(1, after.payments().size(), label + ": the payment row is kept (soft void)");
        Map<String, Object> row = after.payments().get(0);
        Timestamp voidedAt = (Timestamp) row.get("voided_at");
        Assertions.assertNotNull(voidedAt, label + ": voided_at is set");
        assertWithinRace(voidedAt, race, label + ": voided during the race");
        Assertions.assertEquals(withColumns(paymentBefore, "voided_at", voidedAt, "voided_by_user_id", staffUserId),
                row, label + ": soft-voided in place by the session actor");
        return row;
    }

    /**
     * The race's messages, exactly: one signed-PDF email for {@code emailed} (or none when null) - the stored PDF of
     * that version, to the TRIMMED saved email - and, when {@code storeNotified}, one store notification for that
     * same version; nothing failed, and no link email, quote email, quote notification or SMS at all.
     */
    private void assertRaceMessages(long orderId, String orderNumber, AppendedVersion emailed, boolean storeNotified,
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
            Assertions.assertArrayEquals(fileStorageService.read(emailed.pdfPath()), email.pdfBytes(),
                    context + ": the attachment is the stored PDF of the version the winner appended");
            Assertions.assertEquals(invoicePdfName(orderNumber, emailed.version()), email.pdfFileName(),
                    context + ": attachment name");
            Assertions.assertEquals(orderId, email.orderId(), context + ": email order id");
            Assertions.assertEquals(emailed.version(), email.invoiceVersionNumber(), context + ": email version");
        }

        Assertions.assertTrue(invoiceNotificationSender.failedNotifications().isEmpty(),
                context + ": no failed store notification");
        List<InvoiceAcceptanceNotificationRequest> notifications = invoiceNotificationSender.sentNotifications();
        if (!storeNotified) {
            Assertions.assertEquals(List.of(), notifications, context + ": no store notification at all");
        } else {
            Assertions.assertEquals(1, notifications.size(), context + ": exactly one store notification");
            InvoiceAcceptanceNotificationRequest notification = notifications.get(0);
            Assertions.assertEquals(STORE_EMAIL, notification.recipientEmail(), context + ": the order's store email");
            Assertions.assertEquals("Invoice accepted: " + orderNumber, notification.subject(),
                    context + ": notification subject");
            Assertions.assertEquals(orderId, notification.orderId(), context + ": notification order id");
            Assertions.assertEquals(emailed.version(), notification.invoiceVersionNumber(),
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
     * (nothing minted a new one) and the link's own delivery marker is untouched.
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
     * The current version (the highest) is {@code version} with the given {@code accepted_at} (null = unsigned) and
     * {@code last_emailed_at}, and the sales_order mirror equals the CURRENT version's value.
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

    /** The dead link through the REAL public GET: 200 with the dead state and its exact message. */
    private void assertPublicDeadView(String token, String state, String message) throws Exception {
        MvcResult result = performPublic(mockMvc, get("/api/v1/public/invoices/" + token));
        assertStatus(result, 200, "public view of the dead link");
        JsonNode root = json(result);
        Assertions.assertEquals(INVOICE_LOADED_MESSAGE, root.path("message").asText(), "public view: message");
        Assertions.assertEquals(state, root.path("data").path("state").asText(), "public view: state");
        Assertions.assertEquals(message, root.path("data").path("message").asText(), "public view: the dead message");
        Assertions.assertTrue(root.path("data").path("version_number").isNull(), "public view: no invoice content");
    }

    /** The order's image/png stored files (its signature images) are exactly {@code expected}. */
    private void assertOnlySignatureImages(long orderId, List<Map<String, Object>> expected, String label) {
        Assertions.assertEquals(expected, signatureImages(orderId), label);
    }

    private List<Map<String, Object>> signatureImages(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT stored_file_id, file_name, storage_path, mime_type, file_size FROM stored_file "
                        + "WHERE storage_path LIKE ? AND mime_type = 'image/png' ORDER BY stored_file_id",
                orderStoragePrefix(orderId) + "%");
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

    /** The version is dated the day it is written: within the race's local days. */
    private static void assertDatedWithinRace(Map<String, Object> row, RaceResults race, String label) {
        LocalDate invoiceDate = ((Date) row.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(race.startedAt().toLocalDate())
                        || invoiceDate.isAfter(race.finishedAt().toLocalDate()),
                label + ": dated the day it is written, but was " + invoiceDate);
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

    /** stored_file rows in the snapshot's order (by stored_file_id). */
    private static List<Map<String, Object>> sortedFiles(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparingLong(row -> longValue(row.get("stored_file_id"))));
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
