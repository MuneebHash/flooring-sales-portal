package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.QuoteAcceptanceNotificationRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.error.ErrorCode;
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

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16G PR 3 (#69, #123; 16G contract §5.2 "A version committed by another request while this request
 * waits for the lock is caught: the comparison runs after the lock is granted") - CONTROLLED, DETERMINISTIC
 * proof, for each route that carries a version precondition, that the precondition is checked against the
 * row read UNDER the order lock and never against anything read before it:
 * <ul>
 *   <li><b>D.8</b> ({@code POST .../invoices/current/accept}, multipart {@code expected_version_number}): the
 *       request carries the invoice version its client saw (v1) and is OBSERVED waiting on the order-row lock
 *       while a newer invoice version v2 is committed - once by the lock holder itself (it appends v2 inside
 *       its transaction and commits), once by a real payment (D.7) queued AHEAD of D.8 that commits v2 after
 *       the holder releases the row. D.8 then takes the lock and gets 409 {@code INVOICE_VERSION_STALE}.
 *       The matching-version case is proven too: D.8 carrying the number of the version that Path A, queued
 *       AHEAD of it, appends while it waits (v2) passes the stale comparison under the lock - the value is only
 *       compared, never used to look a row up - and the already-accepted check under the same lock refuses
 *       it with D.8's default 409 {@code INVOICE_ALREADY_ACCEPTED}.</li>
 *   <li><b>Path A</b> ({@code POST .../quote/create-invoice}, body {@code {"quote_version_id": N}}): the
 *       request carries the latest ACCEPTED quote version its client saw (v1) and is OBSERVED waiting while
 *       the newer ISSUED v2 becomes the latest accepted version - once by the lock holder flipping v2 to
 *       ACCEPTED (its link CONSUMED) and committing, once by the customer's real public accept of v2 queued
 *       AHEAD of Path A. Path A then takes the lock and gets 409 {@code QUOTE_VERSION_STALE}.</li>
 * </ul>
 * Every refused request must write NOTHING (16G contract §5.2, §6.2): the committed state after the race is
 * asserted to be exactly the seed plus the competing writer's own documented writes - no invoice row,
 * signature / PDF {@code stored_file} row or file on disk, email or email marker ({@code invoice.last_emailed_at},
 * the {@code sales_order} mirror) of the refused request, no D5(c) quote-link cancel (D.8; the seeded ISSUED
 * quote stays ISSUED with its ACTIVE token) and no quote-row change (Path A).
 *
 * <p><b>Fixtures - a fully self-seeded, COMMITTED tenant.</b> The requests run on worker threads with their
 * own pooled connections and real commits, so this class is NOT {@code @Transactional}: it inserts its own
 * business (slug {@value #SLUG} - the tag every row hangs off), store, salesperson and store access with
 * auto-commit SQL, its orders / customers / addresses / charge lines the same way, and drives every invoice
 * and quote through the real endpoints (D.1 create; draft PUT, protected send-email, the token from the
 * recorded quote email, public multipart accept). No V4 identity is used. {@code @BeforeEach} sweeps a
 * crashed run's leftovers and seeds; {@code @AfterEach} sweeps again: FK-safe deletes of every row the
 * tenant owns, then its stored files and its storage directory. It declares no bean overrides, so it shares
 * the cached Spring context of the other quote / invoice suites that write under the same storage base dir.
 *
 * <p><b>Deterministic barrier (no sleeps as barriers, no production hooks).</b> The harness of
 * {@code QuoteInvoiceConversionConcurrencyIntegrationTest}: a HOLDER connection takes
 * {@code SELECT ... FOR NO KEY UPDATE} on the order row; each request is submitted and awaited blocked behind
 * the holder (or queued behind the request ahead of it, through the row's tuple lock), observed through
 * {@code pg_stat_activity} + {@code pg_blocking_pids} with bounded polling. The holder either writes inside
 * its own transaction and COMMITS (publishing the newer version and releasing the row in one step) or rolls
 * back, releasing the row to the queued competitor. The holder is always released and the worker pool always
 * shut down (try-with-resources {@link Race}).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class InvoiceVersionPreconditionConcurrencyIntegrationTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before any file is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant (never a V4 identity). The slug tags everything this class commits. ----
    private static final String SLUG = "ivpc-version-race";
    private static final String BUSINESS_NAME = "Ironbark Version Race Flooring";
    private static final String STORE_CODE = "IVPC-ONE";
    private static final String STORE_EMAIL = "showroom.version.race@example.com";
    private static final String SALESPERSON_CODE = "VP1";
    private static final String CHARGE_CODE_PREFIX = "IVPC";

    private static final String CUSTOMER_FIRST_NAME = "Rowan";
    private static final String CUSTOMER_LAST_NAME = "Precondition";
    private static final String CUSTOMER_NAME = CUSTOMER_FIRST_NAME + " " + CUSTOMER_LAST_NAME;
    private static final String CUSTOMER_EMAIL = "rowan.precondition@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and lay carpet (version precondition race)";

    // The accepted quote v1 (itemised 250.00 ex / 275.00 inc) and the CHANGED v2 re-sent after it (300 / 330).
    private static final String ITEMISED_DRAFT = """
            {"itemised": true, "lines": [
              {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
              {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
            ]}""";
    private static final String CHANGED_NON_ITEMISED_DRAFT =
            "{\"itemised\": false, \"final_total_inc_gst\": 330.00, \"lines\": []}";

    // A payment within the unsigned D.1 v1 balance (110.00).
    private static final String PAYMENT_AMOUNT = "50.00";

    // 16G contract §5.1: the two stale refusals, exactly.
    private static final String INVOICE_VERSION_STALE_MESSAGE = "Invoice updated, please refresh";
    private static final String QUOTE_VERSION_STALE_MESSAGE = "The accepted quote changed while the confirmation "
            + "was open. Review the accepted quote before creating an invoice.";
    // D.8's own already-accepted refusal (its default message, unchanged by PR 3).
    private static final String INVOICE_ALREADY_ACCEPTED_MESSAGE =
            "This invoice has already been accepted. Use Re-send to email it again.";
    // 16G PR 3 (contract §6.1): a converted Path A emails the version it appended; the 201 reports the delivery.
    private static final String PATH_A_EMAILED_MESSAGE =
            "Invoice created from accepted quote and emailed to the customer.";
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String QUOTE_SENT_MESSAGE = "Quote sent by email.";
    private static final String QUOTE_ACCEPTED_MESSAGE = "Quote accepted.";

    // The public link inside the (link-only) quote email body: <app-base>/q/{token}.
    private static final Pattern LINK_TOKEN_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]+)");

    // A real, decodable 1x1 PNG: both signature flows validate it.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // Exact money parsing for response bodies (never a double round-trip).
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    // InvoiceDetail timestamps are formatted to the second.
    private static final DateTimeFormatter JSON_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    // Barrier / harness bounds. The poll interval is NOT a barrier - the barrier is the DB condition.
    private static final long BARRIER_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 10L;
    private static final long RESPONSE_TIMEOUT_SECONDS = 30L;
    private static final long POOL_TERMINATION_SECONDS = 15L;

    /**
     * Backends of THIS database currently waiting on a heavyweight lock held (or queued ahead) by the given
     * pid, while executing an order-row lock statement (the JPA {@code ... from sales_order ... for no key
     * update} of D.8 / Path A / the payment, or the public accept's {@code ... FROM sales_order ... FOR
     * UPDATE}). Runs auto-commit on the test thread, so every poll sees a fresh {@code pg_stat_activity}.
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

    // Remaining FK children of sales_order (deleted after the quote / invoice layers).
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

    // The lock holder's signing of the ISSUED quote v2 (status-guarded, like the public accept's transitions).
    private static final String HOLDER_ACCEPT_ISSUED_VERSION_SQL = """
            UPDATE quote_version
            SET status = 'ACCEPTED', accepted_at = ?, accepted_customer_name = customer_name_snapshot
            WHERE quote_version_id = ? AND status = 'ISSUED'
            """;
    private static final String HOLDER_CONSUME_ACTIVE_TOKEN_SQL = """
            UPDATE quote_token SET status = 'CONSUMED', dead_at = ?
            WHERE quote_version_id = ? AND status = 'ACTIVE'
            """;

    // The order-header columns the public quote accept's D6b price write sets (16F PR1).
    private static final List<String> D6B_HEADER_COLUMNS = List.of(
            "price_adjustment_inc_gst", "sale_price_ex_gst", "total_cost", "gp", "gp_percent", "updated_at");

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
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender notificationSender;

    @Autowired
    private RecordingSmsSender smsSender;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    private TransactionTemplate transactionTemplate;

    // The committed tenant of the running test.
    private long businessId;
    private int storeId;
    private long userId;
    private int orderSequence;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes this tenant's storage directory recursively - never a real storage dir.
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class stores and sweeps its files under the shared quote / invoice test storage dir");
        Assertions.assertTrue(storageRoot().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                () -> "unexpected storage root for a sweeping test class: " + storageRoot());
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
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
            // Singletons: never leak recorded sends or an armed failNextSend to another class.
            resetSenders();
        }
    }

    // ================================================================
    // D.8 - a newer invoice version committed while D.8 waits for the order lock
    // ================================================================

    /**
     * The LOCK HOLDER commits the newer version: D.8 carries v1 (the current version its client saw) and is
     * observed waiting on the holder; inside the holder's transaction invoice v2 is appended (a copy of v1 with
     * its own PDF, invisible to D.8 until the commit); the holder commits, which publishes v2 and releases the
     * row in one step. D.8 then takes the lock, reads the current invoice (v2) and refuses the v1 it carries:
     * 409 {@code INVOICE_VERSION_STALE}. v2 is unsigned and every later D.8 check would pass, so the staleness
     * is the only reason for the refusal. Nothing of D.8's is written.
     */
    @Test
    void d8_newerVersionCommittedByTheLockHolderWhileD8Waits_isCaughtUnderTheLock_409InvoiceVersionStale_writesNothing()
            throws Exception {
        D8Order order = seedD8Order();
        int seenVersion = currentInvoiceVersion(order.orderId());
        Assertions.assertEquals(1, seenVersion, "setup: the D.8 client sees invoice v1");
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        OrderSnapshot before = snapshot(order.orderId());
        // The holder's v2 gets its own real PDF file (v1's bytes), so every stored_file keeps its file on disk.
        byte[] holderPdf = fileStorageService.read(storagePathOf(longValue(invoiceV1.get("stored_file_id"))));
        String holderPdfPath = fileStorageService.store(holderPdf, businessId, order.orderId(), "pdf");

        MvcResult d8Result;
        long holderFileId;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> d8 = race.submit(invoiceAccept(order.orderId(), seenVersion));
            int d8Pid = awaitWaiterBlockedBy(race, race.holderPid(), "D.8 (carrying v1)", d8);

            // INSIDE the holder transaction, while D.8 waits on it: append invoice v2 (published by the commit).
            holderFileId = race.queryLongInHolder(
                    "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                            + "VALUES (?, ?, 'application/pdf', ?) RETURNING stored_file_id",
                    invoicePdfName(order.orderNumber(), 2), holderPdfPath, (long) holderPdf.length);
            Assertions.assertEquals(1, race.updateInHolder(HOLDER_APPEND_COPY_OF_VERSION_SQL,
                            seenVersion + 1, holderFileId, order.orderId(), seenVersion),
                    "the holder appends exactly invoice v2");
            Assertions.assertEquals(List.of(1), invoiceVersions(order.orderId()),
                    "the holder's v2 is invisible to every other transaction until it commits");
            Assertions.assertFalse(d8.isDone(), "D.8 must still be parked on the order lock");
            Assertions.assertTrue(isBlockedBy(d8Pid, race.holderPid()),
                    "D.8 must still be waiting on the holder; activity: " + activity());

            race.commitHolder(); // publishes v2 AND releases the order row in one step

            d8Result = awaitResponse(d8, "D.8 (carrying v1)");
        }

        // D.8 took the lock only after v2 was committed, read the current invoice there and refused its v1.
        assertConflictRefusal(d8Result, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "D.8 (carrying v1, v2 committed by the holder while it waited)");

        // The committed state is exactly the seed plus the holder's v2 and its PDF - nothing of D.8's.
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "no D.8 version: exactly v1 and the holder's v2");
        Map<String, Object> holderV2 = invoiceRow(order.orderId(), 2);
        Assertions.assertEquals(withColumns(invoiceV1, "invoice_id", holderV2.get("invoice_id"),
                        "version_number", 2, "stored_file_id", holderFileId, "created_at", holderV2.get("created_at")),
                holderV2, "invoice v2 is exactly the holder's unsigned, unemailed copy of v1");
        Map<String, Object> holderFile = storedFileRow(holderFileId);
        assertSnapshot(before.withInvoices(append(before.invoices(), holderV2))
                        .withStoredFiles(append(before.storedFiles(), holderFile))
                        .withFilesOnDisk(plus(before.filesOnDisk(), holderPdfPath)),
                snapshot(order.orderId()), "after the holder's v2 and the refused D.8");

        assertNothingOfTheRefusedD8(order, 3);
        assertD5cNotApplied(order);
        assertNoEmailMarkers(order.orderId(), "after the refused D.8");
        assertNoMessagesAtAll("the holder's v2 and the refused D.8");
    }

    /**
     * A REAL REQUEST queued ahead of D.8 commits the newer version: a payment (D.7) is observed waiting on the
     * holder, then D.8 carrying v1 is observed queued BEHIND the payment; the holder rolls back (it wrote
     * nothing), the payment takes the row, appends v2 (110.00 - 50.00 = 60.00 due) and commits while D.8 still
     * waits. D.8 then takes the lock, reads the current invoice (the payment's unsigned v2) and refuses the v1
     * it carries: 409 {@code INVOICE_VERSION_STALE}. Nothing of D.8's is written.
     */
    @Test
    void d8_paymentQueuedAheadCommitsANewerVersionWhileD8Waits_isCaughtUnderTheLock_409InvoiceVersionStale_writesNothing()
            throws Exception {
        D8Order order = seedD8Order();
        int seenVersion = currentInvoiceVersion(order.orderId());
        Assertions.assertEquals(1, seenVersion, "setup: the D.8 client sees invoice v1");
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        OrderSnapshot before = snapshot(order.orderId());

        MvcResult paymentResult;
        MvcResult d8Result;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> payment = race.submit(recordPayment(order.orderId()));
            int paymentPid = awaitWaiterBlockedBy(race, race.holderPid(), "payment (D.7)", payment);
            Future<MvcResult> d8 = race.submit(invoiceAccept(order.orderId(), seenVersion));
            int d8Pid = awaitWaiterBlockedBy(race, paymentPid, "D.8 (carrying v1)", d8, payment);
            assertQueuedInOrder(race.holderPid(), paymentPid, d8Pid, payment, d8);

            race.rollbackHolder(); // the holder wrote nothing: the payment takes the row first, D.8 after its commit

            paymentResult = awaitResponse(payment, "payment (D.7)");
            d8Result = awaitResponse(d8, "D.8 (carrying v1)");
        }

        // The payment (queue head) receipted v1 and committed v2 while D.8 waited behind it.
        assertStatus(paymentResult, 201, "payment (queue head)");
        JsonNode paymentBody = json(paymentResult);
        Assertions.assertEquals(PAYMENT_RECORDED_MESSAGE, paymentBody.path("message").asText(), "payment message");
        JsonNode paidInvoice = paymentBody.path("data").path("current_invoice");
        Assertions.assertEquals(2, paidInvoice.path("version_number").asInt(), "the payment appended invoice v2");
        // D.8 then took the lock, read the current invoice there (the payment's v2) and refused its v1.
        assertConflictRefusal(d8Result, ErrorCode.INVOICE_VERSION_STALE, INVOICE_VERSION_STALE_MESSAGE,
                "D.8 (carrying v1, v2 committed by the payment queued ahead of it)");

        // The committed state is exactly the seed plus the payment's own writes - nothing of D.8's.
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "no D.8 version: exactly v1 and the payment's v2");
        Map<String, Object> paymentV2 = invoiceRow(order.orderId(), 2);
        Assertions.assertEquals(paidInvoice.path("invoice_id").asLong(), longValue(paymentV2.get("invoice_id")),
                "invoice v2 is the payment's version");
        for (String carried : List.of("details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst",
                "due_date", "source_quote_version_id", "terms_snapshot", "customer_name_snapshot",
                "customer_address_line1_snapshot", "customer_address_line2_snapshot")) {
            Assertions.assertEquals(invoiceV1.get(carried), paymentV2.get(carried),
                    "the payment's v2 carries v1's " + carried);
        }
        assertMoney(PAYMENT_AMOUNT, paymentV2.get("total_paid"), "payment v2 total_paid");
        assertMoney("60.00", paymentV2.get("balance_due"), "payment v2 balance_due");
        for (String unsigned : List.of("accepted_at", "accepted_customer_name", "accepted_signature_file_id",
                "last_emailed_at")) {
            Assertions.assertNull(paymentV2.get(unsigned), "the payment's v2 carries no " + unsigned);
        }
        Map<String, Object> paymentPdf = storedFileRow(longValue(paymentV2.get("stored_file_id")));
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), 2), paymentPdf.get("file_name"),
                "the payment's regenerated v2 PDF");
        OrderSnapshot after = snapshot(order.orderId());
        Assertions.assertEquals(1, after.payments().size(), "exactly the race's payment row");
        Map<String, Object> paymentRow = after.payments().get(0);
        assertMoney(PAYMENT_AMOUNT, paymentRow.get("amount"), "payment amount");
        Assertions.assertNull(paymentRow.get("voided_at"), "the payment is active");
        assertSnapshot(before.withInvoices(append(before.invoices(), paymentV2))
                        .withStoredFiles(append(before.storedFiles(), paymentPdf))
                        .withFilesOnDisk(plus(before.filesOnDisk(), (String) paymentPdf.get("storage_path")))
                        .withPayments(List.of(paymentRow)),
                after, "after the payment's v2 and the refused D.8");

        assertNothingOfTheRefusedD8(order, 3);
        assertD5cNotApplied(order);
        assertNoEmailMarkers(order.orderId(), "after the payment and the refused D.8");
        // A payment never emails, and the refused D.8 never reached its post-commit email.
        assertNoMessagesAtAll("the payment and the refused D.8");
    }

    // ================================================================
    // D.8 - Path A appends the very version D.8 carries while D.8 waits (the already-accepted check)
    // ================================================================

    /**
     * The MATCHING-version case (16G contract §5.4 steps 5 and 6): Path A carrying the accepted quote v1's id is
     * observed waiting on the holder, then D.8 carrying {@code expected_version_number = 2} - the number of the
     * version Path A appends - is observed queued BEHIND Path A; the holder rolls back (it wrote nothing). Path A
     * takes the row, appends invoice v2 from the accepted quote (inheriting its signature) and commits while D.8
     * still waits, then emails v2 after its commit. D.8 then takes the lock and reads the current invoice there
     * (Path A's signed v2). The value it carries is only COMPARED with that version - never used to look a row up
     * - so it passes the stale check, and the already-accepted check under the same lock refuses it with D.8's
     * default 409 {@code INVOICE_ALREADY_ACCEPTED}. Nothing of D.8's is written: no signature, invoice version or
     * PDF, no D5(c) cancel of the ISSUED quote v2, no email or marker. (The Path-A-first D.8 race of
     * {@code QuoteInvoiceConversionConcurrencyIntegrationTest} keeps the stale case, D.8 carrying v1.)
     */
    @Test
    void d8_pathAQueuedAheadAppendsTheVersionD8Carries_passesTheStaleCheckUnderTheLock_409InvoiceAlreadyAccepted_writesNothing()
            throws Exception {
        PathAOrder order = seedPathAOrder();
        int d8ExpectedVersion = currentInvoiceVersion(order.orderId()) + 1;
        Assertions.assertEquals(2, d8ExpectedVersion,
                "setup: the D.8 client carries v2 - the version Path A appends while D.8 waits");
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> acceptedV1 = versionRow(order.orderId(), 1);
        Assertions.assertEquals(order.acceptedQuoteVersionId(), longValue(acceptedV1.get("quote_version_id")),
                "setup: quote v1 is the accepted version Path A converts");
        long quoteSignatureFileId = longValue(acceptedV1.get("accepted_signature_file_id"));
        OrderSnapshot before = snapshot(order.orderId());
        LocalDate dayBefore = LocalDate.now();
        LocalDateTime raceStart = LocalDateTime.now();

        MvcResult pathAResult;
        MvcResult d8Result;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> pathA = race.submit(
                    createInvoiceFromQuote(order.orderId(), order.acceptedQuoteVersionId()));
            int pathAPid = awaitWaiterBlockedBy(race, race.holderPid(), "Path A (carrying quote v1)", pathA);
            Future<MvcResult> d8 = race.submit(invoiceAccept(order.orderId(), d8ExpectedVersion));
            int d8Pid = awaitWaiterBlockedBy(race, pathAPid, "D.8 (carrying v2)", d8, pathA);
            assertQueuedInOrder(race.holderPid(), pathAPid, d8Pid, pathA, d8);

            race.rollbackHolder(); // the holder wrote nothing: Path A takes the row first, D.8 after its commit

            pathAResult = awaitResponse(pathA, "Path A (carrying quote v1)");
            d8Result = awaitResponse(d8, "D.8 (carrying v2)");
        }
        LocalDate dayAfter = LocalDate.now();

        // Path A (queue head) converted the accepted quote v1 into invoice v2 and emailed v2 after its commit.
        assertStatus(pathAResult, 201, "Path A (queue head)");
        JsonNode pathABody = json(pathAResult);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(pathABody), "Path A: envelope keys");
        Assertions.assertEquals(PATH_A_EMAILED_MESSAGE, pathABody.path("message").asText(),
                "Path A: the post-commit email of v2 was delivered");
        JsonNode pathAInvoice = pathABody.path("data").path("invoice");
        Assertions.assertEquals(2, pathAInvoice.path("version_number").asInt(), "Path A: data.invoice is v2");
        // D.8 took the lock only after Path A committed v2: the value it carries matched the current version
        // read there, and the already-accepted check under the same lock refused it.
        assertConflictRefusal(d8Result, ErrorCode.INVOICE_ALREADY_ACCEPTED, INVOICE_ALREADY_ACCEPTED_MESSAGE,
                "D.8 (carrying v2, the version Path A appended while it waited)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "no D.8 version: exactly v1 and Path A's v2");
        Map<String, Object> pathAV2 = invoiceRow(order.orderId(), 2);
        Timestamp stamp = (Timestamp) pathAV2.get("last_emailed_at");
        Assertions.assertNotNull(stamp, "Path A's delivered email was stamped on v2");
        LocalDateTime stamped = stamp.toLocalDateTime();
        Assertions.assertFalse(stamped.isBefore(raceStart.minusSeconds(1)) || stamped.isAfter(LocalDateTime.now()),
                "the stamp " + stamped + " was taken during the race (started " + raceStart + ")");
        LocalDate invoiceDate = ((Date) pathAV2.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(dayBefore) || invoiceDate.isAfter(dayAfter),
                "Path A's v2 is dated today, but was " + invoiceDate);
        // v2 is exactly Path A's conversion: the accepted quote v1's frozen snapshot verbatim (details, totals,
        // terms, the V17 Invoice To, the source) and its inherited acceptance - the SAME signature stored_file,
        // never a new one - over the order's own columns as on v1 (order, due date, creator, nothing paid), plus
        // its own PDF and the stamp of its delivered email.
        Assertions.assertEquals(withColumns(invoiceV1,
                        "invoice_id", pathAV2.get("invoice_id"),
                        "version_number", 2,
                        "invoice_date", pathAV2.get("invoice_date"),
                        "details_of_sale_snapshot", acceptedV1.get("details_of_sale_snapshot"),
                        "sale_price_ex_gst", acceptedV1.get("quote_total_ex_gst"),
                        "sale_price_inc_gst", acceptedV1.get("quote_total_inc_gst"),
                        "balance_due", acceptedV1.get("quote_total_inc_gst"),
                        "stored_file_id", pathAV2.get("stored_file_id"),
                        "created_at", pathAV2.get("created_at"),
                        "accepted_at", acceptedV1.get("accepted_at"),
                        "accepted_customer_name", acceptedV1.get("accepted_customer_name"),
                        "accepted_signature_file_id", quoteSignatureFileId,
                        "last_emailed_at", stamp,
                        "source_quote_version_id", order.acceptedQuoteVersionId(),
                        "terms_snapshot", acceptedV1.get("terms_snapshot"),
                        "customer_name_snapshot", acceptedV1.get("customer_name_snapshot"),
                        "customer_address_line1_snapshot", acceptedV1.get("customer_address_line1_snapshot"),
                        "customer_address_line2_snapshot", acceptedV1.get("customer_address_line2_snapshot")),
                pathAV2, "invoice v2 is exactly Path A's conversion of the accepted quote v1");
        Map<String, Object> pathAPdf = storedFileRow(longValue(pathAV2.get("stored_file_id")));
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), 2), pathAPdf.get("file_name"), "Path A's v2 PDF");
        Assertions.assertEquals("application/pdf", pathAPdf.get("mime_type"), "Path A's v2 PDF mime type");
        byte[] pathAPdfBytes = fileStorageService.read((String) pathAPdf.get("storage_path"));
        Assertions.assertEquals(longValue(pathAPdf.get("file_size")), pathAPdfBytes.length,
                "Path A's v2 PDF: stored_file.file_size == the bytes on disk");
        // The 201 describes that committed v2 - its inherited acceptance and its own stamp (16G contract §6.2).
        Assertions.assertEquals(longValue(pathAV2.get("invoice_id")), pathAInvoice.path("invoice_id").asLong(),
                "Path A: data.invoice is the committed v2 row");
        Assertions.assertTrue(pathAInvoice.path("accepted_signature_present").booleanValue(),
                "Path A: v2 carries the inherited quote signature");
        Assertions.assertEquals(JSON_TIMESTAMP.format(((Timestamp) acceptedV1.get("accepted_at")).toLocalDateTime()),
                pathAInvoice.path("accepted_at").asText(), "Path A: accepted_at = the quote's signature time");
        Assertions.assertEquals(JSON_TIMESTAMP.format(stamped), pathAInvoice.path("last_emailed_at").asText(),
                "Path A: the 201 carries v2's own last_emailed_at stamp");

        // The committed state is exactly the seed plus Path A's own writes - v2, its PDF (row + file) and the stamp
        // of its delivered email, mirrored on the header because v2 is current - and nothing of D.8's: no
        // signature or signed-PDF row or file, no third version, no D5(c) cancel (quote rows and tokens as seeded).
        assertSnapshot(before.withHeader(withColumns(before.header(), "last_emailed_at", stamp))
                        .withInvoices(append(before.invoices(), pathAV2))
                        .withStoredFiles(append(before.storedFiles(), pathAPdf))
                        .withFilesOnDisk(plus(before.filesOnDisk(), (String) pathAPdf.get("storage_path"))),
                snapshot(order.orderId()), "after Path A's v2 and the refused D.8");
        String prefix = orderStoragePrefix(businessId, order.orderId()) + "%";
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? "
                + "AND file_name LIKE 'signature-%'", prefix), "the refused D.8 stored no signature row");
        Assertions.assertEquals(List.of(quoteSignatureFileId), jdbcTemplate.queryForList(
                        "SELECT stored_file_id FROM stored_file WHERE storage_path LIKE ? AND mime_type = 'image/png' "
                                + "ORDER BY stored_file_id", Long.class, prefix),
                "the order's only signature image is the accepted quote's, which v2 references");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND file_name = ?",
                prefix, invoicePdfName(order.orderNumber(), 3)), "the refused D.8 stored no signed PDF");
        assertD5cNotApplied(order.orderId(), 2, order.issuedQuoteVersionId());
        Assertions.assertEquals(List.of(2), jdbcTemplate.queryForList(
                        "SELECT version_number FROM invoice WHERE order_id = ? AND last_emailed_at IS NOT NULL "
                                + "ORDER BY version_number", Integer.class, order.orderId()),
                "only Path A's v2 carries an email stamp");
        Assertions.assertEquals(stamp, jdbcTemplate.queryForObject(
                        "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, order.orderId()),
                "the sales_order mirror equals v2's stamp (v2 is current)");

        // Messages: exactly Path A's post-commit email of its own v2, whose attachment is v2's stored PDF; the
        // refused D.8 never reached its email, and neither request sends a quote message.
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no failed invoice email");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "exactly one invoice email during the race: Path A's");
        InvoiceEmailRequest email = sent.get(0);
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), "Path A emailed the saved customer email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), "Path A email subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + order.orderNumber() + " from " + BUSINESS_NAME
                + " attached.\n\nThank you.", email.bodyText(), "Path A email body");
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), 2), email.pdfFileName(), "attachment name");
        Assertions.assertArrayEquals(pathAPdfBytes, email.pdfBytes(), "the attachment is the stored PDF of v2");
        Assertions.assertEquals(order.orderId(), email.orderId(), "email order id");
        Assertions.assertEquals(2, email.invoiceVersionNumber(), "the email carries invoice v2");
        assertNoQuoteEmailNotificationOrSms("the Path A conversion and the refused D.8");
    }

    // ================================================================
    // Path A - a newer accepted quote version committed while Path A waits for the order lock
    // ================================================================

    /**
     * The LOCK HOLDER makes a newer quote version the latest ACCEPTED one: Path A carries v1 (the latest
     * accepted version its client saw) and is observed waiting on the holder; inside the holder's transaction
     * the ISSUED v2 is moved to ACCEPTED (the frozen V17 name, an acceptance time) and its link CONSUMED -
     * invisible to Path A until the commit; the holder commits. Path A then takes the lock, selects the latest
     * ACCEPTED version itself (v2) and refuses the v1 it carries: 409 {@code QUOTE_VERSION_STALE} - before the
     * signature read (v2 has no signature file here, so a Path A that went on would fail differently). Path A
     * writes nothing: no invoice row, PDF, marker, email or quote-row change.
     */
    @Test
    void pathA_newerQuoteAcceptedByTheLockHolderWhilePathAWaits_isCaughtUnderTheLock_409QuoteVersionStale_writesNothing()
            throws Exception {
        PathAOrder order = seedPathAOrder();
        long seenQuoteVersionId = latestAcceptedQuoteVersionId(order.orderId());
        Assertions.assertEquals(order.acceptedQuoteVersionId(), seenQuoteVersionId,
                "setup: the Path A client sees the accepted v1");
        Map<String, Object> issuedV2 = versionRow(order.orderId(), 2);
        Map<String, Object> issuedV2Token = activeTokenRow(order.issuedQuoteVersionId());
        Timestamp holderAcceptedAt = Timestamp.valueOf(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        OrderSnapshot before = snapshot(order.orderId());

        MvcResult pathAResult;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> pathA = race.submit(createInvoiceFromQuote(order.orderId(), seenQuoteVersionId));
            int pathAPid = awaitWaiterBlockedBy(race, race.holderPid(), "Path A (carrying quote v1)", pathA);

            // INSIDE the holder transaction, while Path A waits on it: the ISSUED v2 is signed and its link
            // consumed - once committed, v2 is the order's latest ACCEPTED quote version.
            Assertions.assertEquals(1, race.updateInHolder(HOLDER_ACCEPT_ISSUED_VERSION_SQL,
                    holderAcceptedAt, order.issuedQuoteVersionId()), "the holder accepts exactly the ISSUED v2");
            Assertions.assertEquals(1, race.updateInHolder(HOLDER_CONSUME_ACTIVE_TOKEN_SQL,
                    holderAcceptedAt, order.issuedQuoteVersionId()), "the holder consumes exactly v2's ACTIVE link");
            Assertions.assertEquals(seenQuoteVersionId, latestAcceptedQuoteVersionId(order.orderId()),
                    "the holder's acceptance of v2 is invisible to every other transaction until it commits");
            Assertions.assertFalse(pathA.isDone(), "Path A must still be parked on the order lock");
            Assertions.assertTrue(isBlockedBy(pathAPid, race.holderPid()),
                    "Path A must still be waiting on the holder; activity: " + activity());

            race.commitHolder(); // publishes the newer accepted v2 AND releases the order row in one step

            pathAResult = awaitResponse(pathA, "Path A (carrying quote v1)");
        }

        // Path A took the lock only after v2 was accepted, selected the latest ACCEPTED version there and
        // refused the v1 id it carries.
        assertConflictRefusal(pathAResult, ErrorCode.QUOTE_VERSION_STALE, QUOTE_VERSION_STALE_MESSAGE,
                "Path A (carrying quote v1, v2 accepted by the holder while it waited)");
        Assertions.assertEquals(order.issuedQuoteVersionId(), latestAcceptedQuoteVersionId(order.orderId()),
                "v2 is now the latest ACCEPTED quote version");

        // The committed state is exactly the seed plus the holder's two quote-row changes - nothing of Path A's.
        Map<String, Object> expectedV2 = withColumns(issuedV2, "status", "ACCEPTED", "accepted_at", holderAcceptedAt,
                "accepted_customer_name", issuedV2.get("customer_name_snapshot"));
        Map<String, Object> expectedToken = withColumns(issuedV2Token, "status", "CONSUMED", "dead_at",
                holderAcceptedAt);
        assertSnapshot(before.withQuoteVersions(replaceRow(before.quoteVersions(), "quote_version_id", expectedV2))
                        .withQuoteTokens(replaceRow(before.quoteTokens(), "quote_token_id", expectedToken)),
                snapshot(order.orderId()), "after the holder's acceptance of v2 and the refused Path A");

        assertNothingOfTheRefusedPathA(order);
        assertNoEmailMarkers(order.orderId(), "after the refused Path A");
        assertNoMessagesAtAll("the holder's acceptance of v2 and the refused Path A");
    }

    /**
     * A REAL REQUEST queued ahead of Path A makes a newer quote version the latest ACCEPTED one: the
     * customer's public accept of the ISSUED v2 (token-only, its own signature) is observed waiting on the
     * holder, then Path A carrying v1 is observed queued BEHIND it; the holder rolls back, the public accept
     * takes the row, signs v2 and commits (v2 ACCEPTED, its link CONSUMED, its signature + signed PDF stored,
     * the D6b price write, the post-commit store notification) while Path A still waits. Path A then takes the
     * lock, selects the latest ACCEPTED version (v2) and refuses the v1 id it carries: 409
     * {@code QUOTE_VERSION_STALE}. Path A writes nothing: no invoice row, PDF, marker, email or quote-row change.
     */
    @Test
    void pathA_publicAcceptQueuedAheadAcceptsANewerQuoteWhilePathAWaits_isCaughtUnderTheLock_409QuoteVersionStale_writesNothing()
            throws Exception {
        PathAOrder order = seedPathAOrder();
        long seenQuoteVersionId = latestAcceptedQuoteVersionId(order.orderId());
        Assertions.assertEquals(order.acceptedQuoteVersionId(), seenQuoteVersionId,
                "setup: the Path A client sees the accepted v1");
        Map<String, Object> issuedV2 = versionRow(order.orderId(), 2);
        Map<String, Object> issuedV2Token = activeTokenRow(order.issuedQuoteVersionId());
        OrderSnapshot before = snapshot(order.orderId());

        MvcResult publicAcceptResult;
        MvcResult pathAResult;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> publicAccept = race.submit(publicAccept(order.issuedToken()));
            int publicAcceptPid = awaitWaiterBlockedBy(race, race.holderPid(), "public accept of quote v2",
                    publicAccept);
            Future<MvcResult> pathA = race.submit(createInvoiceFromQuote(order.orderId(), seenQuoteVersionId));
            int pathAPid = awaitWaiterBlockedBy(race, publicAcceptPid, "Path A (carrying quote v1)", pathA,
                    publicAccept);
            assertQueuedInOrder(race.holderPid(), publicAcceptPid, pathAPid, publicAccept, pathA);

            race.rollbackHolder(); // the holder wrote nothing: the public accept takes the row first

            publicAcceptResult = awaitResponse(publicAccept, "public accept of quote v2");
            pathAResult = awaitResponse(pathA, "Path A (carrying quote v1)");
        }

        // The customer's public accept (queue head) signed the ISSUED v2 while Path A waited behind it.
        assertStatus(publicAcceptResult, 201, "public accept of quote v2 (queue head)");
        JsonNode acceptBody = json(publicAcceptResult);
        Assertions.assertEquals("INACTIVE", acceptBody.path("data").path("state").asText(), "public accept state");
        Assertions.assertEquals(QUOTE_ACCEPTED_MESSAGE, acceptBody.path("message").asText(), "public accept message");
        // Path A then took the lock, selected the latest ACCEPTED version (v2) and refused the v1 id it carries.
        assertConflictRefusal(pathAResult, ErrorCode.QUOTE_VERSION_STALE, QUOTE_VERSION_STALE_MESSAGE,
                "Path A (carrying quote v1, v2 accepted by the public accept queued ahead of it)");
        Assertions.assertEquals(order.issuedQuoteVersionId(), latestAcceptedQuoteVersionId(order.orderId()),
                "v2 is now the latest ACCEPTED quote version");

        // The public accept's own writes: v2 ACCEPTED with the frozen V17 name, its signature and signed PDF,
        // its link CONSUMED at the same instant, and the D6b price columns of the header.
        Map<String, Object> acceptedV2 = versionRow(order.orderId(), 2);
        Timestamp acceptedAt = (Timestamp) acceptedV2.get("accepted_at");
        Assertions.assertNotNull(acceptedAt, "the public accept stamped v2's accepted_at");
        Map<String, Object> signature = storedFileRow(longValue(acceptedV2.get("accepted_signature_file_id")));
        Assertions.assertEquals(quoteSignatureName(order.orderNumber(), 2), signature.get("file_name"),
                "v2's signature is the public accept's");
        Map<String, Object> signedPdf = storedFileRow(longValue(acceptedV2.get("signed_pdf_file_id")));
        Assertions.assertEquals(signedPdfName(order.orderNumber(), 2), signedPdf.get("file_name"),
                "v2's signed PDF is the public accept's");
        Map<String, Object> expectedV2 = withColumns(issuedV2, "status", "ACCEPTED", "accepted_at", acceptedAt,
                "accepted_customer_name", issuedV2.get("customer_name_snapshot"),
                "accepted_signature_file_id", signature.get("stored_file_id"),
                "signed_pdf_file_id", signedPdf.get("stored_file_id"));
        Map<String, Object> expectedToken = withColumns(issuedV2Token, "status", "CONSUMED", "dead_at", acceptedAt);
        OrderSnapshot after = snapshot(order.orderId());
        Assertions.assertNotEquals(before.header().get("updated_at"), after.header().get("updated_at"),
                "the public accept's D6b price write moved the header");
        // Expected = the seed + exactly those writes: every other header column - the last_emailed_at mirror
        // included - every invoice row and every other stored file / quote row is unchanged by Path A.
        assertSnapshot(before.withHeader(withColumnsFrom(before.header(), after.header(), D6B_HEADER_COLUMNS))
                        .withQuoteVersions(replaceRow(before.quoteVersions(), "quote_version_id", expectedV2))
                        .withQuoteTokens(replaceRow(before.quoteTokens(), "quote_token_id", expectedToken))
                        .withStoredFiles(append(before.storedFiles(), signature, signedPdf))
                        .withFilesOnDisk(plus(before.filesOnDisk(), (String) signature.get("storage_path"),
                                (String) signedPdf.get("storage_path"))),
                after, "after the public accept of v2 and the refused Path A");

        assertNothingOfTheRefusedPathA(order);
        assertNoEmailMarkers(order.orderId(), "after the refused Path A");
        // The only message of the race: the public accept's post-commit store notification for v2.
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty() && invoiceEmailSender.failedEmails().isEmpty(),
                "the refused Path A sent no signed-PDF email");
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty() && quoteEmailSender.failedEmails().isEmpty(),
                "no quote email during the race");
        Assertions.assertTrue(smsSender.sentMessages().isEmpty() && smsSender.failedMessages().isEmpty(),
                "no SMS during the race");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(), "no failed store notification");
        List<QuoteAcceptanceNotificationRequest> notifications = notificationSender.sentNotifications();
        Assertions.assertEquals(1, notifications.size(), "exactly the public accept's store notification");
        Assertions.assertEquals(STORE_EMAIL, notifications.get(0).recipientEmail(), "notified the store");
        Assertions.assertEquals(order.orderId(), notifications.get(0).orderId(), "notification order");
        Assertions.assertEquals(2, notifications.get(0).quoteVersionNumber(), "notification for v2");
    }

    // ================================================================
    // Race harness (copied from QuoteInvoiceConversionConcurrencyIntegrationTest, plus holder writes)
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

        /** Run one request on a worker thread with its OWN MockMvc; the future yields the response. */
        Future<MvcResult> submit(RequestBuilder request) {
            MockMvc workerMockMvc = MockMvcBuilders.webAppContextSetup(context).build();
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
     * never waited never serialised on the order lock. Every backend observed queued behind
     * {@code blockerPid} is tracked on {@code race}, so its last-resort cancel can reach it (and only it).
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
            Thread thread = new Thread(runnable, "invoice-version-race-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ================================================================
    // Requests (the seeded tenant's session and slug)
    // ================================================================

    private MockHttpSession session() {
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

    private static MockMultipartFile signaturePart() {
        return new MockMultipartFile("signature", "signature.png", "image/png", ONE_PIXEL_PNG);
    }

    /** D.8 (16G PR 3): the signature part + the mandatory {@code expected_version_number}; no name part. */
    private RequestBuilder invoiceAccept(long orderId, int expectedVersionNumber) {
        return multipart(orderUrl(orderId) + "/invoices/current/accept")
                .file(signaturePart())
                .param("expected_version_number", String.valueOf(expectedVersionNumber))
                .session(session());
    }

    /** Path A (16G PR 3): body {@code {"quote_version_id": N}} = the latest accepted version the client saw. */
    private RequestBuilder createInvoiceFromQuote(long orderId, long seenAcceptedQuoteVersionId) {
        return post(orderUrl(orderId) + "/quote/create-invoice").session(session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quote_version_id\": " + seenAcceptedQuoteVersionId + "}");
    }

    /** D.7 record payment of {@value #PAYMENT_AMOUNT} (EFTPOS). */
    private RequestBuilder recordPayment(long orderId) {
        return post(orderUrl(orderId) + "/payments").session(session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\":\"EFTPOS\",\"amount\":" + PAYMENT_AMOUNT
                        + ",\"payment_reference\":null}");
    }

    /** The public, token-only quote accept: ONE signature part, no session, no slug. */
    private static RequestBuilder publicAccept(String token) {
        return multipart("/api/v1/public/quotes/" + token + "/accept").file(signaturePart());
    }

    // ================================================================
    // Committed self-seeding (no test transaction; real flows)
    // ================================================================

    /**
     * The tenant, committed: an active business (ABN + bank details, no terms) with the class slug, one store
     * (with a notification email), one salesperson and that salesperson's store access.
     */
    private void seedTenant() {
        Long business = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number) "
                        + "VALUES (?, ?, TRUE, '51 824 753 556', 'Ironbark Race Bank', '062-000', ?, '12345678') "
                        + "RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, BUSINESS_NAME);
        Assertions.assertNotNull(business, "setup: business insert returned no id");
        businessId = business;
        Integer store = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Ironbark Race Showroom', ?, '0290001234', ?, '1 Race Way', 'Burwood', 'NSW', "
                        + " '2134') RETURNING store_id",
                Integer.class, businessId, STORE_CODE, STORE_EMAIL);
        Assertions.assertNotNull(store, "setup: store insert returned no id");
        storeId = store;
        Long user = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Vic', 'Version', ?, 'vic.version.race@example.com', 'not-a-login-hash') "
                        + "RETURNING user_id",
                Long.class, businessId, SALESPERSON_CODE);
        Assertions.assertNotNull(user, "setup: app_user insert returned no id");
        userId = user;
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        orderSequence = 0;
    }

    /** One committed order of the tenant. */
    private record SeededOrder(long orderId, String orderNumber) {
    }

    /** A D.8 order: an UNSIGNED D.1 invoice v1 plus an ISSUED quote v1 with an ACTIVE link (the D5(c) target). */
    private record D8Order(long orderId, String orderNumber, long issuedQuoteVersionId) {
    }

    /** A Path A order: an UNSIGNED D.1 invoice v1, the publicly ACCEPTED quote v1, then a newer ISSUED v2. */
    private record PathAOrder(long orderId, String orderNumber, long acceptedQuoteVersionId,
                              long issuedQuoteVersionId, String issuedToken) {
    }

    /**
     * A committed order that passes every D.1 / quote / Path A gate: details of sale, proposed lay date
     * 2026-12-01 CONFIRMED, the customer {@value #CUSTOMER_NAME} with a valid email, INSTALLATION and BILLING
     * addresses, and one charge line of 100.00 ex / 40.00 cost (110.00 inc).
     */
    private SeededOrder seedInvoiceReadyOrder() {
        int sequence = ++orderSequence;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        String orderNumber = STORE_CODE + "." + SALESPERSON_CODE + "." + String.format("%05d", sequence);
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 42, 2026, ?, "
                        + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, businessId, storeId, userId, sequence, orderNumber, DETAILS_OF_SALE);
        Assertions.assertNotNull(orderId, "setup: order insert returned no id");
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) VALUES (?, ?, ?, ?, ?)",
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, CUSTOMER_EMAIL, CUSTOMER_MOBILE);
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
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Version race supply and lay', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, storeId, chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Version race supply and lay', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        return new SeededOrder(orderId, orderNumber);
    }

    private D8Order seedD8Order() throws Exception {
        SeededOrder seeded = seedInvoiceReadyOrder();
        createUnsignedInvoiceV1(seeded.orderId());
        saveDraft(seeded.orderId(), ITEMISED_DRAFT);
        sendQuoteAndExtractToken(seeded.orderId(), 1);
        Map<String, Object> issued = versionRow(seeded.orderId(), 1);
        Assertions.assertEquals("ISSUED", issued.get("status"), "setup: quote v1 is ISSUED");
        long issuedId = longValue(issued.get("quote_version_id"));
        Assertions.assertNull(activeTokenRow(issuedId).get("dead_at"), "setup: quote v1 has an ACTIVE link");
        assertNoEmailMarkers(seeded.orderId(), "setup");
        // Every send so far belongs to the seed: the race starts with empty recorders.
        resetSenders();
        return new D8Order(seeded.orderId(), seeded.orderNumber(), issuedId);
    }

    private PathAOrder seedPathAOrder() throws Exception {
        SeededOrder seeded = seedInvoiceReadyOrder();
        long orderId = seeded.orderId();
        // An unsigned Path B v1: Path A would append v2 if it went on (D5(b) allows an unsigned invoice).
        createUnsignedInvoiceV1(orderId);
        // The REAL quote chain: draft -> protected send-email -> token from the recorded email -> public accept.
        saveDraft(orderId, ITEMISED_DRAFT);
        acceptPublicly(sendQuoteAndExtractToken(orderId, 1));
        Map<String, Object> accepted = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", accepted.get("status"), "setup: quote v1 accepted publicly");
        Assertions.assertEquals(CUSTOMER_NAME, accepted.get("accepted_customer_name"), "setup: the V17 name");
        // Decision D9: sending after an acceptance issues a NEW version; the draft is CHANGED too.
        saveDraft(orderId, CHANGED_NON_ITEMISED_DRAFT);
        String issuedToken = sendQuoteAndExtractToken(orderId, 2);
        Map<String, Object> issued = versionRow(orderId, 2);
        Assertions.assertEquals("ISSUED", issued.get("status"), "setup: quote v2 is ISSUED");
        Assertions.assertNull(issued.get("accepted_at"), "setup: quote v2 is not accepted");
        Assertions.assertEquals(CUSTOMER_NAME, issued.get("customer_name_snapshot"), "setup: v2's V17 name");
        long issuedId = longValue(issued.get("quote_version_id"));
        Assertions.assertNull(activeTokenRow(issuedId).get("dead_at"), "setup: quote v2 has an ACTIVE link");
        Assertions.assertEquals(longValue(accepted.get("quote_version_id")), latestAcceptedQuoteVersionId(orderId),
                "setup: the latest ACCEPTED quote version is v1");
        assertNoEmailMarkers(orderId, "setup");
        resetSenders();
        return new PathAOrder(orderId, seeded.orderNumber(), longValue(accepted.get("quote_version_id")),
                issuedId, issuedToken);
    }

    /** D.1 Create through the API: the unsigned, unemailed Path B invoice v1 (110.00 inc). */
    private void createUnsignedInvoiceV1(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: invoice create (D.1)");
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, json(result).path("message").asText(), "setup: D.1 message");
        Assertions.assertEquals(List.of(1), invoiceVersions(orderId), "setup: exactly the D.1 invoice v1");
        Map<String, Object> invoice = invoiceRow(orderId, 1);
        Assertions.assertNull(invoice.get("accepted_at"), "setup: invoice v1 is unsigned");
        assertMoney("110.00", invoice.get("sale_price_inc_gst"), "setup: invoice v1 inc");
        Assertions.assertEquals(CUSTOMER_NAME, invoice.get("customer_name_snapshot"), "setup: v1's frozen name");
    }

    private void saveDraft(long orderId, String body) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(orderId) + "/quote/draft").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertStatus(result, 200, "setup: quote draft save");
    }

    /** Issue through the protected send-email and take the plaintext token from the recorded email. */
    private String sendQuoteAndExtractToken(long orderId, int expectedVersion) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/quote/send-email").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: quote send-email (issue v" + expectedVersion + ")");
        JsonNode sendBody = json(result);
        Assertions.assertEquals(QUOTE_SENT_MESSAGE, sendBody.path("message").asText(), "setup: send message");
        Assertions.assertEquals(expectedVersion, sendBody.path("data").path("version_number").asInt(),
                "setup: issued version number");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly one quote email per send");
        Matcher matcher = LINK_TOKEN_PATTERN.matcher(sent.get(sent.size() - 1).bodyText());
        Assertions.assertTrue(matcher.find(), "setup: the quote email carries the /q/{token} link");
        return matcher.group(1);
    }

    /** The real public accept, sequentially (setup only). */
    private void acceptPublicly(String token) throws Exception {
        MvcResult result = mockMvc.perform(publicAccept(token)).andReturn();
        assertStatus(result, 201, "setup: public quote accept");
        Assertions.assertEquals(QUOTE_ACCEPTED_MESSAGE, json(result).path("message").asText(), "setup: accept message");
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
     * FK-safe order: tokens, invoices (they may reference a quote_version and the quote's signature), versions
     * (cascade lines), the draft (cascade lines), the other order children, the order, then its stored_file
     * rows. The files on disk are deleted only after that commit, then the order directory.
     */
    private void deleteCommittedOrder(long tenantBusinessId, long orderId) {
        String prefix = orderStoragePrefix(tenantBusinessId, orderId);
        List<String> storagePaths = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
            List<Map<String, Object>> files = jdbcTemplate.queryForList(ORDER_FILES_SQL,
                    orderId, orderId, orderId, orderId, orderId, orderId, prefix + "%");
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
        quoteEmailSender.reset();
        notificationSender.reset();
        smsSender.reset();
    }

    // ================================================================
    // Committed-state snapshot
    // ================================================================

    /**
     * Everything on one order the raced requests could write, in committed state: the sales_order row (its
     * last_emailed_at mirror and updated_at included), every invoice row (each version's last_emailed_at
     * included), every stored_file row of the order and the files on disk under its directory, every quote
     * version and token, the draft and its lines, the customer / address / line rows, and the payments.
     */
    private record OrderSnapshot(Map<String, Object> header,
                                 List<Map<String, Object>> invoices,
                                 List<Map<String, Object>> storedFiles,
                                 Set<String> filesOnDisk,
                                 List<Map<String, Object>> quoteVersions,
                                 List<Map<String, Object>> quoteTokens,
                                 List<Map<String, Object>> draft,
                                 List<Map<String, Object>> content,
                                 List<Map<String, Object>> payments) {

        OrderSnapshot withHeader(Map<String, Object> value) {
            return new OrderSnapshot(value, invoices, storedFiles, filesOnDisk, quoteVersions, quoteTokens, draft,
                    content, payments);
        }

        OrderSnapshot withInvoices(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, value, storedFiles, filesOnDisk, quoteVersions, quoteTokens, draft,
                    content, payments);
        }

        OrderSnapshot withStoredFiles(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, invoices, value, filesOnDisk, quoteVersions, quoteTokens, draft,
                    content, payments);
        }

        OrderSnapshot withFilesOnDisk(Set<String> value) {
            return new OrderSnapshot(header, invoices, storedFiles, value, quoteVersions, quoteTokens, draft,
                    content, payments);
        }

        OrderSnapshot withQuoteVersions(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, invoices, storedFiles, filesOnDisk, value, quoteTokens, draft,
                    content, payments);
        }

        OrderSnapshot withQuoteTokens(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, invoices, storedFiles, filesOnDisk, quoteVersions, value, draft,
                    content, payments);
        }

        OrderSnapshot withPayments(List<Map<String, Object>> value) {
            return new OrderSnapshot(header, invoices, storedFiles, filesOnDisk, quoteVersions, quoteTokens, draft,
                    content, value);
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

    /** Part-by-part equality of two snapshots (the expected one = the seed plus the competitor's own writes). */
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
        Assertions.assertEquals(expected.quoteVersions(), actual.quoteVersions(), context + ": the quote_version rows");
        Assertions.assertEquals(expected.quoteTokens(), actual.quoteTokens(), context + ": the quote_token rows");
        Assertions.assertEquals(expected.draft(), actual.draft(), context + ": the quote draft and its lines");
        Assertions.assertEquals(expected.content(), actual.content(),
                context + ": the customer, address, product / charge line and quote version line rows");
        Assertions.assertEquals(expected.payments(), actual.payments(), context + ": the payment rows");
    }

    // ================================================================
    // Assertions
    // ================================================================

    /**
     * A 409 refusal under the order lock - a stale version precondition, or D.8's already-accepted check: the
     * exact code and its contract message (the code's default message), no data, no details.
     */
    private static void assertConflictRefusal(MvcResult result, ErrorCode code, String exactMessage, String label)
            throws Exception {
        assertStatus(result, 409, label);
        JsonNode root = json(result);
        Assertions.assertEquals(Set.of("error"), fieldNames(root), label + ": a refusal carries no data");
        JsonNode error = root.path("error");
        Assertions.assertEquals(code.name(), error.path("code").asText(), label + ": error code");
        Assertions.assertEquals(exactMessage, error.path("message").asText(), label + ": the exact contract message");
        Assertions.assertEquals(exactMessage, code.defaultMessage(), label + ": the code's message is the contract's");
        Assertions.assertFalse(error.has("details"), label + ": a 409 carries no details");
    }

    /**
     * The refused D.8 left no request-owned row or file: no invoice version beyond the competitor's, no
     * signature {@code stored_file} (no {@code signature-*}), no signed PDF of the version it would have
     * appended.
     */
    private void assertNothingOfTheRefusedD8(D8Order order, int wouldBeVersion) {
        String prefix = orderStoragePrefix(businessId, order.orderId()) + "%";
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM invoice WHERE order_id = ? AND version_number >= ?",
                order.orderId(), wouldBeVersion), "the refused D.8 appended no invoice version");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? "
                        + "AND (file_name LIKE 'signature-%' OR mime_type = 'image/png')", prefix),
                "the refused D.8 stored no signature row");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND file_name = ?",
                prefix, invoicePdfName(order.orderNumber(), wouldBeVersion)), "the refused D.8 stored no signed PDF");
    }

    /**
     * D5(c) never ran: the seeded ISSUED quote v1 is still ISSUED with its ACTIVE, live token (only a
     * successful D.8 cancels a quote link, in its own committing transaction).
     */
    private void assertD5cNotApplied(D8Order order) {
        assertD5cNotApplied(order.orderId(), 1, order.issuedQuoteVersionId());
    }

    /** D5(c) never ran: the order's ISSUED quote version is still ISSUED with its ACTIVE, live token. */
    private void assertD5cNotApplied(long orderId, int issuedVersionNumber, long issuedQuoteVersionId) {
        Assertions.assertEquals("ISSUED", versionRow(orderId, issuedVersionNumber).get("status"),
                "the ISSUED quote v" + issuedVersionNumber + " is untouched by the refused D.8 (no D5(c))");
        Map<String, Object> token = activeTokenRow(issuedQuoteVersionId);
        Assertions.assertNull(token.get("dead_at"), "the quote v" + issuedVersionNumber + " link stays ACTIVE and live");
    }

    /** The refused Path A appended no invoice version and stored no invoice PDF (v1 stays the only invoice). */
    private void assertNothingOfTheRefusedPathA(PathAOrder order) {
        Assertions.assertEquals(List.of(1), invoiceVersions(order.orderId()),
                "the refused Path A appended no invoice version");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? AND file_name = ?",
                        orderStoragePrefix(businessId, order.orderId()) + "%", invoicePdfName(order.orderNumber(), 2)),
                "the refused Path A stored no invoice PDF");
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

    private void assertNoMessagesAtAll(String context) {
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty() && invoiceEmailSender.failedEmails().isEmpty(),
                context + ": no invoice email was sent or attempted");
        assertNoQuoteEmailNotificationOrSms(context);
    }

    /** No quote email, store notification or SMS was sent or attempted (invoice emails are asserted apart). */
    private void assertNoQuoteEmailNotificationOrSms(String context) {
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty() && quoteEmailSender.failedEmails().isEmpty(),
                context + ": no quote email was sent or attempted");
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty()
                        && notificationSender.failedNotifications().isEmpty(),
                context + ": no store notification was sent or attempted");
        Assertions.assertTrue(smsSender.sentMessages().isEmpty() && smsSender.failedMessages().isEmpty(),
                context + ": no SMS was sent or attempted");
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

    /** The order's CURRENT invoice version (the highest version_number) in committed state - what a client sees. */
    private int currentInvoiceVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "the order has a current invoice");
        return version;
    }

    private Map<String, Object> versionRow(long orderId, int versionNumber) {
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
    private Map<String, Object> activeTokenRow(long quoteVersionId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_token WHERE quote_version_id = ? AND status = 'ACTIVE'", quoteVersionId);
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

    /** A copy of a row with some columns replaced (the expected state of a row a competitor changed). */
    private static Map<String, Object> withColumns(Map<String, Object> row, Object... columnValuePairs) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        for (int i = 0; i < columnValuePairs.length; i += 2) {
            String column = (String) columnValuePairs[i];
            Assertions.assertTrue(copy.containsKey(column), "unknown column " + column);
            copy.put(column, columnValuePairs[i + 1]);
        }
        return copy;
    }

    /** A copy of {@code base} whose {@code columns} are taken from {@code source} (a competitor's own columns). */
    private static Map<String, Object> withColumnsFrom(Map<String, Object> base, Map<String, Object> source,
                                                       List<String> columns) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        for (String column : columns) {
            Assertions.assertTrue(copy.containsKey(column) && source.containsKey(column), "unknown column " + column);
            copy.put(column, source.get(column));
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

    private static String quoteSignatureName(String orderNumber, int versionNumber) {
        return "quote-signature-" + orderNumber + "-v" + versionNumber + ".png";
    }

    private static String signedPdfName(String orderNumber, int versionNumber) {
        return "quote-" + orderNumber + "-v" + versionNumber + "-signed.pdf";
    }
}
