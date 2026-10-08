package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import com.flooring.salesportal.common.storage.FileStorageService;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16F PR2 - CONTROLLED, DETERMINISTIC concurrency tests for "create invoice from the accepted
 * quote" (Path A, {@code POST /api/v1/{slug}/orders/{orderId}/quote/create-invoice}) against every
 * competing order-locked invoice writer: the in-app invoice acceptance (D.8) in both orders, a payment
 * record (D.7) in both orders, and a second Path A request.
 *
 * <p><b>Why no test transaction.</b> The requests run on worker threads with their own pooled
 * connections and REAL commits, so the seed is committed first and every assertion reads committed
 * state. The class is NOT {@code @Transactional}: it seeds through the real API plus auto-commit SQL
 * (orders tagged {@value #ORDER_NUMBER_PREFIX}, store charges tagged {@value #CHARGE_CODE_PREFIX}) and
 * sweeps every committed row and every stored file (FK-safe deletes, then the files on disk) in
 * {@code @BeforeEach} (crashed-run leftovers) and {@code @AfterEach}. It declares no bean overrides,
 * so it shares the cached Spring context of {@code QuoteAcceptanceConcurrencyIntegrationTest} and the
 * other 16F classes that write files under the same {@code app.storage.base-dir}.
 *
 * <p><b>Seed (real flows).</b> A committed invoice-ready order (customer "Quote Tester" with a valid
 * email, BILLING + INSTALLATION addresses, details of sale, lay date 2026-12-01 CONFIRMED, one charge
 * line 100.00 ex / 40.00 cost), an UNSIGNED Path B invoice v1 created through the real D.1 endpoint
 * (100.00 ex / 110.00 inc), then a REAL accepted quote: itemised draft PUT (250.00 ex / 275.00 inc),
 * protected send-email, token taken from the recorded email, public multipart accept. The ONLY SQL
 * edit on the quote is its frozen {@code terms_snapshot} (set to known sanitizer-style HTML right after
 * the real acceptance) so the frozen terms carried by Path A and by a later payment are provable. The
 * D.8 races additionally re-send a CHANGED draft after the acceptance, so an ISSUED v2 with an ACTIVE
 * token exists (the D5(c) target of a winning D.8, and untouched by Path A).
 *
 * <p><b>Deterministic barrier (no sleeps as barriers, no production hooks).</b> Mirrors
 * {@code QuoteAcceptanceConcurrencyIntegrationTest}: a HOLDER connection takes
 * {@code SELECT ... FOR NO KEY UPDATE} on the order row (it conflicts with the JPA
 * {@code PESSIMISTIC_WRITE} lock every request here takes first). Request 1 is submitted and awaited
 * blocked behind the holder; request 2 is submitted and awaited blocked behind REQUEST 1 (the row's
 * tuple lock), observed through {@code pg_stat_activity} + {@code pg_blocking_pids} with bounded
 * polling. The queue order is asserted, the holder rolls back (it changed nothing), and both
 * responses are collected with bounded timeouts. The holder is always released and the worker pool
 * always shut down (try-with-resources {@link Race}); a straggler is cancelled as a last resort, and
 * only if it is a request backend this race observed queued on its own order row.
 *
 * <p>Every race asserts the HTTP outcomes, the committed invoice versions (consecutive, exactly the
 * expected new rows), the V19 source / frozen terms and the inherited acceptance on every Path A row,
 * the untouched quote / order / content rows, the payment row, the stored_file rows against the files
 * on disk (exactly the expected set, no orphan, none missing, sizes equal), the invoice PDF content,
 * the recorded emails and the workspace {@code invoice_eligible} read.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class QuoteInvoiceConversionConcurrencyIntegrationTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    // Committed seed tags (swept before and after every test, and after a crashed run).
    private static final String ORDER_NUMBER_PREFIX = "NTXQIR.ZZ9.";
    private static final String CHARGE_CODE_PREFIX = "NTXQIR";

    private static final String CUSTOMER_FIRST_NAME = "Quote";
    private static final String CUSTOMER_LAST_NAME = "Tester";
    private static final String CUSTOMER_NAME = CUSTOMER_FIRST_NAME + " " + CUSTOMER_LAST_NAME;
    private static final String CUSTOMER_EMAIL = "quote.invoice.race@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and install carpet (quote to invoice race)";
    private static final LocalDate EXPECTED_DUE_DATE = LocalDate.of(2026, 11, 29); // lay date 2026-12-01 minus 2 days

    // The seeded header is a consistent "no override" state for the 100.00 ex / 40.00 cost charge line
    // (110.00 inc, gp 60.00 = 60.00%) with a fixed updated_at. The quote acceptance (D6b) rewrites it
    // during the seed; the pre-race snapshot is taken after that, so ANY header write by a racer shows.
    private static final Timestamp SEEDED_UPDATED_AT = Timestamp.valueOf("2026-01-01 09:00:00");

    // The accepted quote's frozen totals (itemised Carpet 2 x 100 + Underlay 1 x 50).
    private static final String QUOTE_TOTAL_EX = "250.00";
    private static final String QUOTE_TOTAL_INC = "275.00";
    // The Path B invoice v1 (D.1 from the live charge line, before the quote was accepted).
    private static final String INVOICE_V1_EX = "100.00";
    private static final String INVOICE_V1_INC = "110.00";

    // The ONE SQL edit on the accepted quote: XML-safe, sanitizer-style frozen terms (the template
    // renders them with th:utext), so the Path A copy and the payment carry-forward are observable.
    private static final String FROZEN_QUOTE_TERMS = "<p>Frozen quote terms for the conversion race.</p>";
    private static final String FROZEN_QUOTE_TERMS_TEXT = "Frozen quote terms for the conversion race.";

    // A payment amount valid in BOTH orders: within the v1 balance (110.00) and the quote total (275.00).
    private static final String PAYMENT_AMOUNT = "50.00";

    private static final String ITEMISED_TWO_LINE_DRAFT = """
            {"itemised": true, "lines": [
              {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
              {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
            ]}""";
    // The CHANGED draft re-sent after the acceptance (D.8 races): non-itemised 300.00 ex / 330.00 inc.
    private static final String CHANGED_NON_ITEMISED_DRAFT =
            "{\"itemised\": false, \"final_total_inc_gst\": 330.00, \"lines\": []}";

    private static final String PATH_A_CREATED_MESSAGE = "Invoice created from accepted quote.";
    private static final String PATH_A_SIGNATURE_PRECEDENCE_MESSAGE =
            "The current invoice was signed at the same time or later than this quote. "
                    + "A newer signed quote is required to create an invoice from a quote.";
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String INVOICE_ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String QUOTE_ACCEPTED_MESSAGE = "Quote accepted.";
    private static final String QUOTE_SENT_MESSAGE = "Quote sent by email.";
    private static final String PDF_FOOTER = "Generated by the Flooring Sales Portal";

    // InvoiceDetail (every key always present) and the UNCHANGED payment-response summary shape.
    private static final Set<String> INVOICE_DETAIL_KEYS = Set.of(
            "invoice_id", "order_id", "version_number", "invoice_date", "due_date",
            "details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst", "total_paid",
            "balance_due", "created_by_user_id", "created_at", "pdf_download_path", "accepted_at",
            "accepted_customer_name", "accepted_signature_present", "accepted_signature_download_path",
            "last_emailed_at", "terms_html", "terms_source");
    private static final Set<String> CURRENT_INVOICE_SUMMARY_KEYS = Set.of(
            "invoice_id", "version_number", "invoice_date", "due_date", "sale_price_inc_gst",
            "total_paid", "balance_due", "created_by_user_id", "created_at", "pdf_download_path",
            "accepted_at", "accepted_customer_name", "accepted_signature_present",
            "accepted_signature_download_path", "last_emailed_at");

    // InvoiceDetail timestamps are formatted to the second; the PDF caption to the minute.
    private static final DateTimeFormatter JSON_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter PDF_ACCEPTED_AT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    // The public link inside the (link-only) quote email body: <app-base>/q/{token}.
    private static final Pattern LINK_TOKEN_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]+)");

    // A real, decodable 1x1 PNG: both signature flows validate it; D.8 stores these exact bytes.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // Exact money parsing for response bodies (never a double round-trip).
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    // Barrier / harness bounds. The poll interval is NOT a barrier - the barrier is the DB condition.
    private static final long BARRIER_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 10L;
    private static final long RESPONSE_TIMEOUT_SECONDS = 30L;
    private static final long POOL_TERMINATION_SECONDS = 15L;

    /**
     * Backends of THIS database currently waiting on a heavyweight lock held (or queued ahead) by the
     * given pid, while executing an order-row lock statement (the JPA
     * {@code select ... from sales_order ... for no key update} every request here takes first).
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

    // Every stored_file an order owns: invoice PDFs + signatures, quote issued/signed PDFs +
    // signatures, attachments - plus anything stored under the order's storage directory.
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

    // Remaining FK children of sales_order (deleted after the quote/invoice layers).
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
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingSmsSender smsSender;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender notificationSender;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes order directories recursively - never against a real storage dir.
        Assertions.assertTrue(storageRoot().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                () -> "unexpected storage root for a sweeping test class: " + storageRoot());
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        transactionTemplate = new TransactionTemplate(transactionManager);
        resetSenders();
        // Leftovers of a crashed earlier run (committed rows + files) must never leak into this one.
        sweepCommittedTestData();
    }

    @AfterEach
    void tearDown() {
        try {
            sweepCommittedTestData();
        } finally {
            // Singletons: never leak recorded sends or an armed failNextSend to another class.
            resetSenders();
        }
    }

    // ================================================================
    // 1. Path A vs in-app invoice acceptance (D.8) - D.8 locks first
    // ================================================================

    @Test
    void pathAVsInvoiceAccept_invoiceAcceptLocksFirst_d8SignsNewerVersionAndCancelsIssuedQuote_pathAGets409NewerSignedQuoteRequired()
            throws Exception {
        ConvertibleOrder order = seedConvertibleOrder(true);
        OrderState before = orderState(order.orderId());
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);

        MvcResult invoiceAcceptResult;
        MvcResult pathAResult;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> invoiceAccept = race.submit(invoiceAccept(order.orderId()));
            int invoiceAcceptPid = awaitWaiterBlockedBy(race, race.holderPid(), "in-app invoice accept (D.8)",
                    invoiceAccept);
            Future<MvcResult> pathA = race.submit(createInvoiceFromQuote(order.orderId()));
            int pathAPid = awaitWaiterBlockedBy(race, invoiceAcceptPid, "create invoice from quote (Path A)", pathA,
                    invoiceAccept);
            assertQueuedInOrder(race.holderPid(), invoiceAcceptPid, pathAPid, invoiceAccept, pathA);

            race.rollbackHolder(); // the holder changed nothing - releasing the row is the trigger

            invoiceAcceptResult = awaitResponse(invoiceAccept, "in-app invoice accept (D.8)");
            pathAResult = awaitResponse(pathA, "create invoice from quote (Path A)");
        }

        // D.8 (queue head) signed the unsigned Path B v1: v2 carries v1's snapshot with a NEW signature.
        assertStatus(invoiceAcceptResult, 201, "D.8 (queue head)");
        JsonNode invoiceAcceptBody = json(invoiceAcceptResult);
        Assertions.assertEquals(INVOICE_ACCEPT_EMAILED_MESSAGE, invoiceAcceptBody.path("message").asText(),
                "D.8 (queue head) message");
        JsonNode signedInvoice = invoiceAcceptBody.path("data").path("invoice");
        Assertions.assertEquals(2, signedInvoice.path("version_number").asInt(), "D.8 appended invoice v2");
        Assertions.assertTrue(signedInvoice.path("accepted_signature_present").asBoolean(), "D.8 v2 is signed");
        Assertions.assertEquals(CUSTOMER_NAME, signedInvoice.path("accepted_customer_name").asText());
        Assertions.assertEquals("LIVE", signedInvoice.path("terms_source").asText(),
                "a D.8 version of a Path B invoice has no quote source, so its terms stay LIVE");
        // Path A ran only after D.8 committed: the current invoice is signed LATER than the quote -> 409.
        assertPathARefused(pathAResult, "Path A (queued behind D.8)");

        // Exactly ONE new invoice version (D.8's v2); v1 is never rewritten.
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "exactly one new invoice version (D.8's); Path A appended nothing");
        Assertions.assertEquals(invoiceV1, invoiceRow(order.orderId(), 1), "invoice v1 is never rewritten");
        Map<String, Object> invoiceV2 = invoiceRow(order.orderId(), 2);
        Timestamp invoiceAcceptedAt = (Timestamp) invoiceV2.get("accepted_at");
        Assertions.assertNotNull(invoiceAcceptedAt, "D.8 signed invoice v2");
        Assertions.assertTrue(invoiceAcceptedAt.after(order.quoteAcceptedAt()),
                () -> "the D.8 signature time " + invoiceAcceptedAt
                        + " must be strictly later than the quote signature time " + order.quoteAcceptedAt());
        Assertions.assertEquals(CUSTOMER_NAME, invoiceV2.get("accepted_customer_name"), "D.8 accepted name");
        Assertions.assertNull(invoiceV2.get("source_quote_version_id"), "D.8 carries v1's null quote source");
        Assertions.assertNull(invoiceV2.get("terms_snapshot"), "D.8 carries v1's null frozen terms");
        for (String carried : List.of("details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst",
                "total_paid", "balance_due", "due_date")) {
            Assertions.assertEquals(invoiceV1.get(carried), invoiceV2.get(carried),
                    "D.8 carries v1's " + carried + " forward (never the quote snapshot)");
        }
        assertMoney(INVOICE_V1_INC, invoiceV2.get("sale_price_inc_gst"), "D.8 v2 inc is still the Path B v1 total");
        long d8SignatureFileId = longValue(invoiceV2.get("accepted_signature_file_id"));
        Assertions.assertNotEquals(order.quoteSignatureFileId(), d8SignatureFileId,
                "D.8 stores its OWN signature (it never reuses the quote's)");
        Map<String, Object> d8Signature = storedFileRow(d8SignatureFileId);
        Assertions.assertEquals(invoiceSignatureName(order.orderNumber(), 2), d8Signature.get("file_name"));
        Assertions.assertEquals("image/png", d8Signature.get("mime_type"));
        Assertions.assertArrayEquals(ONE_PIXEL_PNG,
                Files.readAllBytes(physicalPath((String) d8Signature.get("storage_path"))),
                "D.8 stores the uploaded signature bytes on disk");
        Timestamp emailedAt = (Timestamp) invoiceV2.get("last_emailed_at");
        Assertions.assertNotNull(emailedAt, "D.8's post-commit auto-email succeeded and stamped v2");
        Assertions.assertEquals(emailedAt, orderLastEmailedAt(order.orderId()),
                "the sales_order mirror equals the current invoice's last_emailed_at");

        // D5(c): D.8 cancelled the ISSUED quote v2 and its ACTIVE token at the invoice acceptance instant.
        // Nothing else on the quote / order layer moved (Path A wrote nothing at all).
        OrderState after = orderState(order.orderId());
        List<Map<String, Object>> expectedVersions = before.quoteVersions().stream()
                .map(row -> intValue(row.get("version_number")) == 2 ? withColumns(row, "status", "CANCELLED") : row)
                .toList();
        List<Map<String, Object>> expectedTokens = before.quoteTokens().stream()
                .map(row -> longValue(row.get("quote_version_id")) == order.issuedQuoteVersionId()
                        ? withColumns(row, "status", "CANCELLED", "dead_at", invoiceAcceptedAt)
                        : row)
                .toList();
        assertNothingElseMoved(before.withQuoteRows(expectedVersions, expectedTokens), after,
                "after D.8 won and Path A was refused");
        Assertions.assertEquals("CANCELLED", versionRow(order.orderId(), 2).get("status"),
                "the ISSUED quote v2 was cancelled by D.8 (D5(c))");
        Map<String, Object> issuedToken = tokenRow(order.issuedToken());
        Assertions.assertEquals("CANCELLED", issuedToken.get("status"), "D.8 killed the v2 link (D5(c))");
        Assertions.assertEquals(invoiceAcceptedAt, issuedToken.get("dead_at"),
                "token dead_at == the invoice acceptance instant");
        Assertions.assertEquals("ACCEPTED", versionRow(order.orderId(), 1).get("status"),
                "the accepted quote v1 is never revoked (D5(d))");
        Assertions.assertTrue(after.payments().isEmpty(), "no payment exists");

        assertStoredArtifacts(order.orderId(),
                issuedPdfName(order.orderNumber(), 1),
                quoteSignatureName(order.orderNumber(), 1),
                signedPdfName(order.orderNumber(), 1),
                invoicePdfName(order.orderNumber(), 1),
                issuedPdfName(order.orderNumber(), 2),
                invoiceSignatureName(order.orderNumber(), 2),
                invoicePdfName(order.orderNumber(), 2));
        assertStoredFileDiskParity(order.orderId());

        // Emails: exactly D.8's auto-email of its own signed v2 (Path A never emails).
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no failed invoice email");
        List<InvoiceEmailRequest> invoiceEmails = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, invoiceEmails.size(), "only D.8's post-commit auto-email was sent");
        InvoiceEmailRequest email = invoiceEmails.get(0);
        Assertions.assertEquals(2, email.invoiceVersionNumber(), "D.8 emailed its v2");
        Assertions.assertEquals(order.orderId(), email.orderId());
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail());
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), 2), email.pdfFileName());
        Assertions.assertArrayEquals(invoicePdfBytes(order, 2), email.pdfBytes(),
                "the emailed PDF is the stored v2 PDF");
        assertNoQuoteEmailNotificationOrSms();
        assertInvoiceEligible(order.orderId(), false);
    }

    // ================================================================
    // 2. Path A vs in-app invoice acceptance (D.8) - Path A locks first
    // ================================================================

    @Test
    void pathAVsInvoiceAccept_pathALocksFirst_pathAConvertsAcceptedQuote_d8Gets409AlreadyAccepted_issuedQuoteUntouched_noD8Signature()
            throws Exception {
        ConvertibleOrder order = seedConvertibleOrder(true);
        OrderState before = orderState(order.orderId());
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        LocalDate dayBefore = LocalDate.now();

        MvcResult pathAResult;
        MvcResult invoiceAcceptResult;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> pathA = race.submit(createInvoiceFromQuote(order.orderId()));
            int pathAPid = awaitWaiterBlockedBy(race, race.holderPid(), "create invoice from quote (Path A)", pathA);
            Future<MvcResult> invoiceAccept = race.submit(invoiceAccept(order.orderId()));
            int invoiceAcceptPid = awaitWaiterBlockedBy(race, pathAPid, "in-app invoice accept (D.8)", invoiceAccept,
                    pathA);
            assertQueuedInOrder(race.holderPid(), pathAPid, invoiceAcceptPid, pathA, invoiceAccept);

            race.rollbackHolder();

            pathAResult = awaitResponse(pathA, "create invoice from quote (Path A)");
            invoiceAcceptResult = awaitResponse(invoiceAccept, "in-app invoice accept (D.8)");
        }
        LocalDate dayAfter = LocalDate.now();

        // Path A (queue head) appended v2 from the accepted quote v1, inheriting its signature.
        assertPathACreated(pathAResult, order, 2, "0.00", QUOTE_TOTAL_INC, "Path A (queue head)");
        // D.8 re-read the current invoice under the lock: v2 is already signed -> its own default 409.
        assertErrorResponse(invoiceAcceptResult, 409, ErrorCode.INVOICE_ALREADY_ACCEPTED, "D.8 (queued behind Path A)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "exactly one new invoice version (Path A's); D.8 appended nothing");
        Assertions.assertEquals(invoiceV1, invoiceRow(order.orderId(), 1), "invoice v1 is never rewritten");
        assertQuoteBilledInvoiceRow(order, 2, "0.00", QUOTE_TOTAL_INC, dayBefore, dayAfter);

        // Path A never touches the quote layer: the ISSUED v2 and its ACTIVE token stand (only a
        // successful D.8 cancels a link, and D.8 lost). Nothing else on the order moved either.
        OrderState after = orderState(order.orderId());
        assertNothingElseMoved(before, after, "after Path A won and D.8 was refused");
        Assertions.assertEquals("ISSUED", versionRow(order.orderId(), 2).get("status"),
                "the newer ISSUED quote v2 is untouched by Path A");
        Map<String, Object> issuedToken = tokenRow(order.issuedToken());
        Assertions.assertEquals("ACTIVE", issuedToken.get("status"), "the v2 link stays ACTIVE");
        Assertions.assertNull(issuedToken.get("dead_at"), "the v2 link carries no dead_at");
        Assertions.assertTrue(after.payments().isEmpty(), "no payment exists");
        Assertions.assertNull(orderLastEmailedAt(order.orderId()),
                "the sales_order mirror is the unemailed Path A version's null");

        // The losing D.8 wrote nothing: no signature stored_file row and no file.
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ? "
                        + "AND file_name LIKE 'signature-%'", orderStoragePrefix(order.orderId()) + "%"),
                "the refused D.8 stored no signature row");
        assertSingleSignatureIsTheQuotes(order, List.of(2));
        assertStoredArtifacts(order.orderId(),
                issuedPdfName(order.orderNumber(), 1),
                quoteSignatureName(order.orderNumber(), 1),
                signedPdfName(order.orderNumber(), 1),
                invoicePdfName(order.orderNumber(), 1),
                issuedPdfName(order.orderNumber(), 2),
                invoicePdfName(order.orderNumber(), 2));
        assertStoredFileDiskParity(order.orderId());
        assertQuoteBilledInvoicePdf(order, 2, "0.00", QUOTE_TOTAL_INC);

        assertNoEmailAtAll();
        assertInvoiceEligible(order.orderId(), false);
    }

    // ================================================================
    // 3. Path A vs payment (D.7) - the payment locks first
    // ================================================================

    @Test
    void pathAVsPayment_paymentLocksFirst_paymentAppendsV2_pathAAppendsV3BilledFromQuoteNetOfThePayment()
            throws Exception {
        ConvertibleOrder order = seedConvertibleOrder(false);
        OrderState before = orderState(order.orderId());
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        LocalDate dayBefore = LocalDate.now();

        MvcResult paymentResult;
        MvcResult pathAResult;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> payment = race.submit(recordPayment(order.orderId()));
            int paymentPid = awaitWaiterBlockedBy(race, race.holderPid(), "record payment (D.7)", payment);
            Future<MvcResult> pathA = race.submit(createInvoiceFromQuote(order.orderId()));
            int pathAPid = awaitWaiterBlockedBy(race, paymentPid, "create invoice from quote (Path A)", pathA, payment);
            assertQueuedInOrder(race.holderPid(), paymentPid, pathAPid, payment, pathA);

            race.rollbackHolder();

            paymentResult = awaitResponse(payment, "record payment (D.7)");
            pathAResult = awaitResponse(pathA, "create invoice from quote (Path A)");
        }
        LocalDate dayAfter = LocalDate.now();

        // The payment (queue head) receipts the unsigned Path B v1: v2 = 110.00 - 50.00 = 60.00 due.
        assertPaymentRecorded(paymentResult, 2, PAYMENT_AMOUNT, "60.00", false, "record payment (queue head)");
        // Path A then bills the QUOTE total net of the committed payment: v3 = 275.00 - 50.00 = 225.00.
        assertPathACreated(pathAResult, order, 3, PAYMENT_AMOUNT, "225.00", "Path A (queued behind the payment)");

        Assertions.assertEquals(List.of(1, 2, 3), invoiceVersions(order.orderId()), "consecutive versions 1..3");
        Assertions.assertEquals(invoiceV1, invoiceRow(order.orderId(), 1), "invoice v1 is never rewritten");
        Map<String, Object> invoiceV2 = invoiceRow(order.orderId(), 2);
        for (String carried : List.of("details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst",
                "due_date")) {
            Assertions.assertEquals(invoiceV1.get(carried), invoiceV2.get(carried),
                    "the payment version carries v1's " + carried + " forward");
        }
        assertMoney(PAYMENT_AMOUNT, invoiceV2.get("total_paid"), "payment v2 total_paid");
        assertMoney("60.00", invoiceV2.get("balance_due"), "payment v2 balance_due");
        assertUnsignedPathBRow(invoiceV2, "payment v2 (receipt of the Path B v1)");
        assertQuoteBilledInvoiceRow(order, 3, PAYMENT_AMOUNT, "225.00", dayBefore, dayAfter);

        OrderState after = orderState(order.orderId());
        assertNothingElseMoved(before, after, "after the payment then Path A");
        assertSingleActivePayment(after);
        Assertions.assertNull(orderLastEmailedAt(order.orderId()), "no version was ever emailed");

        assertSingleSignatureIsTheQuotes(order, List.of(3));
        assertStoredArtifacts(order.orderId(),
                issuedPdfName(order.orderNumber(), 1),
                quoteSignatureName(order.orderNumber(), 1),
                signedPdfName(order.orderNumber(), 1),
                invoicePdfName(order.orderNumber(), 1),
                invoicePdfName(order.orderNumber(), 2),
                invoicePdfName(order.orderNumber(), 3));
        assertStoredFileDiskParity(order.orderId());
        assertQuoteBilledInvoicePdf(order, 3, PAYMENT_AMOUNT, "225.00");

        assertNoEmailAtAll();
        assertInvoiceEligible(order.orderId(), false);
    }

    // ================================================================
    // 4. Path A vs payment (D.7) - Path A locks first
    // ================================================================

    @Test
    void pathAVsPayment_pathALocksFirst_pathAAppendsV2_paymentAppendsV3CarryingQuoteSourceTermsAndInheritedSignature()
            throws Exception {
        ConvertibleOrder order = seedConvertibleOrder(false);
        OrderState before = orderState(order.orderId());
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        LocalDate dayBefore = LocalDate.now();

        MvcResult pathAResult;
        MvcResult paymentResult;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> pathA = race.submit(createInvoiceFromQuote(order.orderId()));
            int pathAPid = awaitWaiterBlockedBy(race, race.holderPid(), "create invoice from quote (Path A)", pathA);
            Future<MvcResult> payment = race.submit(recordPayment(order.orderId()));
            int paymentPid = awaitWaiterBlockedBy(race, pathAPid, "record payment (D.7)", payment, pathA);
            assertQueuedInOrder(race.holderPid(), pathAPid, paymentPid, pathA, payment);

            race.rollbackHolder();

            pathAResult = awaitResponse(pathA, "create invoice from quote (Path A)");
            paymentResult = awaitResponse(payment, "record payment (D.7)");
        }
        LocalDate dayAfter = LocalDate.now();

        // Path A (queue head) appended v2 from the quote with nothing paid yet.
        assertPathACreated(pathAResult, order, 2, "0.00", QUOTE_TOTAL_INC, "Path A (queue head)");
        // The payment then receipts the Path A v2 (the latest official balance 275.00): 225.00 due.
        assertPaymentRecorded(paymentResult, 3, PAYMENT_AMOUNT, "225.00", true, "record payment (queued behind Path A)");

        Assertions.assertEquals(List.of(1, 2, 3), invoiceVersions(order.orderId()), "consecutive versions 1..3");
        Assertions.assertEquals(invoiceV1, invoiceRow(order.orderId(), 1), "invoice v1 is never rewritten");
        assertQuoteBilledInvoiceRow(order, 2, "0.00", QUOTE_TOTAL_INC, dayBefore, dayAfter);
        Map<String, Object> invoiceV2 = invoiceRow(order.orderId(), 2);
        Map<String, Object> invoiceV3 = invoiceRow(order.orderId(), 3);
        // The payment version carries the V19 pair and the inherited acceptance verbatim from v2.
        for (String carried : List.of("source_quote_version_id", "terms_snapshot", "accepted_at",
                "accepted_customer_name", "accepted_signature_file_id", "details_of_sale_snapshot",
                "sale_price_ex_gst", "sale_price_inc_gst", "due_date")) {
            Assertions.assertEquals(invoiceV2.get(carried), invoiceV3.get(carried),
                    "payment v3 carries v2's " + carried + " verbatim");
        }
        Assertions.assertEquals(order.acceptedQuoteVersionId(), longValue(invoiceV3.get("source_quote_version_id")),
                "payment v3 keeps the quote source");
        Assertions.assertEquals(FROZEN_QUOTE_TERMS, invoiceV3.get("terms_snapshot"),
                "payment v3 keeps the frozen quote terms");
        Assertions.assertEquals(order.quoteAcceptedAt(), invoiceV3.get("accepted_at"),
                "payment v3 keeps the quote signature time (never restamped)");
        Assertions.assertEquals(order.quoteSignatureFileId(), longValue(invoiceV3.get("accepted_signature_file_id")),
                "payment v3 references the SAME quote signature stored_file");
        assertMoney(PAYMENT_AMOUNT, invoiceV3.get("total_paid"), "payment v3 total_paid");
        assertMoney("225.00", invoiceV3.get("balance_due"), "payment v3 balance_due = quote inc - payment");
        Assertions.assertNull(invoiceV3.get("last_emailed_at"), "a payment never emails");
        Assertions.assertEquals(USER_LIAM, longValue(invoiceV3.get("created_by_user_id")));

        OrderState after = orderState(order.orderId());
        assertNothingElseMoved(before, after, "after Path A then the payment");
        assertSingleActivePayment(after);
        Assertions.assertNull(orderLastEmailedAt(order.orderId()), "no version was ever emailed");

        assertSingleSignatureIsTheQuotes(order, List.of(2, 3));
        assertStoredArtifacts(order.orderId(),
                issuedPdfName(order.orderNumber(), 1),
                quoteSignatureName(order.orderNumber(), 1),
                signedPdfName(order.orderNumber(), 1),
                invoicePdfName(order.orderNumber(), 1),
                invoicePdfName(order.orderNumber(), 2),
                invoicePdfName(order.orderNumber(), 3));
        assertStoredFileDiskParity(order.orderId());
        // The payment-regenerated PDF renders the frozen quote terms and the inherited signature caption.
        assertQuoteBilledInvoicePdf(order, 2, "0.00", QUOTE_TOTAL_INC);
        assertQuoteBilledInvoicePdf(order, 3, PAYMENT_AMOUNT, "225.00");

        assertNoEmailAtAll();
        assertInvoiceEligible(order.orderId(), false);
    }

    // ================================================================
    // 5. Double Path A - the queue head converts, the queued duplicate is refused
    // ================================================================

    @Test
    void doublePathA_secondQueuedBehindFirst_firstConverts_secondGets409SameSignatureTime_exactlyOneNewVersion()
            throws Exception {
        ConvertibleOrder order = seedConvertibleOrder(false);
        OrderState before = orderState(order.orderId());
        Map<String, Object> invoiceV1 = invoiceRow(order.orderId(), 1);
        LocalDate dayBefore = LocalDate.now();

        MvcResult firstResult;
        MvcResult secondResult;
        try (Race race = new Race(order.orderId())) {
            Future<MvcResult> first = race.submit(createInvoiceFromQuote(order.orderId()));
            int firstPid = awaitWaiterBlockedBy(race, race.holderPid(), "Path A request 1", first);
            Future<MvcResult> second = race.submit(createInvoiceFromQuote(order.orderId()));
            int secondPid = awaitWaiterBlockedBy(race, firstPid, "Path A request 2", second, first);
            assertQueuedInOrder(race.holderPid(), firstPid, secondPid, first, second);

            race.rollbackHolder();

            firstResult = awaitResponse(first, "Path A request 1");
            secondResult = awaitResponse(second, "Path A request 2");
        }
        LocalDate dayAfter = LocalDate.now();

        assertPathACreated(firstResult, order, 2, "0.00", QUOTE_TOTAL_INC, "Path A request 1 (queue head)");
        // The duplicate re-read the current invoice under the lock: v2 carries the SAME signature time as
        // the quote (Path A copies it), and a tie never replaces a signed invoice.
        assertPathARefused(secondResult, "Path A request 2 (queued behind request 1)");

        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()),
                "exactly one new invoice version for two conversion requests");
        Assertions.assertEquals(invoiceV1, invoiceRow(order.orderId(), 1), "invoice v1 is never rewritten");
        assertQuoteBilledInvoiceRow(order, 2, "0.00", QUOTE_TOTAL_INC, dayBefore, dayAfter);

        OrderState after = orderState(order.orderId());
        assertNothingElseMoved(before, after, "after two queued conversions");
        Assertions.assertTrue(after.payments().isEmpty(), "no payment exists");
        Assertions.assertNull(orderLastEmailedAt(order.orderId()), "no version was ever emailed");

        assertSingleSignatureIsTheQuotes(order, List.of(2));
        assertStoredArtifacts(order.orderId(),
                issuedPdfName(order.orderNumber(), 1),
                quoteSignatureName(order.orderNumber(), 1),
                signedPdfName(order.orderNumber(), 1),
                invoicePdfName(order.orderNumber(), 1),
                invoicePdfName(order.orderNumber(), 2));
        assertStoredFileDiskParity(order.orderId());
        assertQuoteBilledInvoicePdf(order, 2, "0.00", QUOTE_TOTAL_INC);

        assertNoEmailAtAll();
        assertInvoiceEligible(order.orderId(), false);
    }

    // ================================================================
    // Race harness (mirrors QuoteAcceptanceConcurrencyIntegrationTest)
    // ================================================================

    /**
     * One deterministic race over an order row: a holder transaction (its own pooled connection,
     * {@code autoCommit=false}, {@code SET LOCAL lock_timeout} so a lock-order bug errors instead of
     * hanging) holding {@code FOR NO KEY UPDATE} on the order, plus a small executor for the competing
     * requests. {@link #close()} - always reached via try-with-resources - rolls back and closes a
     * still-open holder, lets in-flight requests finish, and as a last resort cancels a straggler, but
     * only one of the request backends this race {@linkplain #track tracked}.
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
     * request's backend is observed waiting on the order-row lock behind {@code blockerPid}; returns
     * that backend's pid. Fails fast if the request (or an earlier queued one) completes first - a
     * request that never waited never serialised on the order lock. Every backend observed queued behind
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
     * holder, the second is queued BEHIND THE FIRST (the row's tuple lock), so the first is guaranteed
     * to take the row lock - and commit or roll back - before the second can.
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
     * Last resort only: cancel the race's TRACKED request backends that are still parked on an
     * order-row lock in this database - never any other backend (an unrelated lock waiter, such as a
     * dev server sharing the database, is never touched). A pooled backend outlives its request, so a
     * tracked pid is skipped once it no longer waits on such a lock, and this backend is never
     * cancelled. No tracked pid means no query at all.
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
            Thread thread = new Thread(runnable, "quote-invoice-race-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ================================================================
    // Requests
    // ================================================================

    private MockHttpSession liamStore1Session() {
        MockHttpSession s = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        s.setAttribute("store_id", STORE_SYD_CBD);
        return s;
    }

    private static String orderUrl(long orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId;
    }

    private static MockMultipartFile signaturePart() {
        return new MockMultipartFile("signature", "signature.png", "image/png", ONE_PIXEL_PNG);
    }

    /** Path A: protected, empty body (the server selects the latest accepted quote version). */
    private RequestBuilder createInvoiceFromQuote(long orderId) {
        return post(orderUrl(orderId) + "/quote/create-invoice").session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    /** D.8 in-app invoice acceptance: signature part + accepted_customer_name field + session. */
    private RequestBuilder invoiceAccept(long orderId) {
        return multipart(orderUrl(orderId) + "/invoices/current/accept")
                .file(signaturePart())
                .param("accepted_customer_name", CUSTOMER_NAME)
                .session(liamStore1Session());
    }

    /** D.7 record payment of {@value #PAYMENT_AMOUNT} (EFTPOS). */
    private RequestBuilder recordPayment(long orderId) {
        return post(orderUrl(orderId) + "/payments").session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\":\"EFTPOS\",\"amount\":" + PAYMENT_AMOUNT + ",\"payment_reference\":null}");
    }

    /** The public, token-only accept: ONE signature part, no session, no slug. */
    private static RequestBuilder publicAccept(String token) {
        return multipart("/api/v1/public/quotes/" + token + "/accept").file(signaturePart());
    }

    // ================================================================
    // Committed self-seeding (no test transaction; real flows)
    // ================================================================

    /**
     * A committed order whose latest ACCEPTED quote is v1 (accepted through the real public endpoint)
     * over an UNSIGNED Path B invoice v1, optionally followed by a newer ISSUED quote v2 with an ACTIVE
     * token. {@code issuedQuoteVersionId} / {@code issuedToken} are null without that v2.
     */
    private record ConvertibleOrder(long orderId, String orderNumber, long acceptedQuoteVersionId,
                                    Timestamp quoteAcceptedAt, long quoteSignatureFileId,
                                    Long issuedQuoteVersionId, String issuedToken) {
    }

    private ConvertibleOrder seedConvertibleOrder(boolean withNewerIssuedQuote) throws Exception {
        long orderId = insertCommittedOrder();
        String orderNumber = jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
        seedCustomer(orderId);
        seedAddresses(orderId);
        seedChargeLine(orderId);
        createUnsignedInvoice(orderId);

        // The REAL quote chain: draft -> protected send-email -> token from the recorded email -> accept.
        saveDraft(orderId, ITEMISED_TWO_LINE_DRAFT);
        String acceptToken = sendQuoteAndExtractToken(orderId, 1);
        MvcResult accepted = mockMvc.perform(publicAccept(acceptToken)).andReturn();
        assertStatus(accepted, 201, "setup: public quote accept");
        JsonNode acceptedBody = json(accepted);
        Assertions.assertEquals("INACTIVE", acceptedBody.path("data").path("state").asText(), "setup: accept state");
        Assertions.assertEquals(QUOTE_ACCEPTED_MESSAGE, acceptedBody.path("message").asText(), "setup: accept message");

        Map<String, Object> quoteV1 = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", quoteV1.get("status"), "setup: v1 accepted through the real public endpoint");
        Timestamp quoteAcceptedAt = (Timestamp) quoteV1.get("accepted_at");
        Assertions.assertNotNull(quoteAcceptedAt, "setup: quote accepted_at");
        Assertions.assertEquals(CUSTOMER_NAME, quoteV1.get("accepted_customer_name"), "setup: the V17 name snapshot");
        assertMoney(QUOTE_TOTAL_EX, quoteV1.get("quote_total_ex_gst"), "setup: frozen quote ex total");
        assertMoney(QUOTE_TOTAL_INC, quoteV1.get("quote_total_inc_gst"), "setup: frozen quote inc total");
        Assertions.assertEquals(DETAILS_OF_SALE, quoteV1.get("details_of_sale_snapshot"), "setup: frozen details");
        long signatureFileId = longValue(quoteV1.get("accepted_signature_file_id"));
        Map<String, Object> signature = storedFileRow(signatureFileId);
        Assertions.assertEquals(quoteSignatureName(orderNumber, 1), signature.get("file_name"), "setup: quote signature");
        Assertions.assertEquals("image/png", signature.get("mime_type"), "setup: quote signature mime");
        Assertions.assertTrue(Files.isRegularFile(physicalPath((String) signature.get("storage_path"))),
                "setup: the quote signature file is on disk");
        Assertions.assertEquals("CONSUMED", tokenRow(acceptToken).get("status"), "setup: the accepted link is consumed");

        // The ONE SQL edit on the quote (spec fixture: SQL-seeded frozen terms, sanitizer-style HTML).
        int frozen = jdbcTemplate.update("UPDATE quote_version SET terms_snapshot = ? "
                + "WHERE order_id = ? AND version_number = 1 AND status = 'ACCEPTED'", FROZEN_QUOTE_TERMS, orderId);
        Assertions.assertEquals(1, frozen, "setup: the accepted v1 carries the known frozen terms");

        Long issuedVersionId = null;
        String issuedToken = null;
        if (withNewerIssuedQuote) {
            // Decision D9: sending after an acceptance issues a NEW version; the draft is CHANGED too.
            saveDraft(orderId, CHANGED_NON_ITEMISED_DRAFT);
            issuedToken = sendQuoteAndExtractToken(orderId, 2);
            Map<String, Object> quoteV2 = versionRow(orderId, 2);
            Assertions.assertEquals("ISSUED", quoteV2.get("status"), "setup: v2 must be ISSUED");
            assertMoney("300.00", quoteV2.get("quote_total_ex_gst"), "setup: changed v2 ex total");
            assertMoney("330.00", quoteV2.get("quote_total_inc_gst"), "setup: changed v2 inc total");
            Assertions.assertNull(quoteV2.get("accepted_at"), "setup: v2 is not accepted");
            issuedVersionId = longValue(quoteV2.get("quote_version_id"));
            Map<String, Object> v2Token = tokenRow(issuedToken);
            Assertions.assertEquals("ACTIVE", v2Token.get("status"), "setup: the v2 link is ACTIVE");
            Assertions.assertEquals(issuedVersionId.longValue(), longValue(v2Token.get("quote_version_id")),
                    "setup: the ACTIVE token belongs to v2");
            Assertions.assertEquals("ACCEPTED", versionRow(orderId, 1).get("status"),
                    "setup: a later send never touches the accepted v1");
        }

        // Pre-race invariants: one unsigned Path B invoice, no payment, unemailed, convertible.
        Assertions.assertEquals(List.of(1), invoiceVersions(orderId), "setup: exactly the D.1 invoice v1");
        Assertions.assertNull(invoiceRow(orderId, 1).get("accepted_at"), "setup: invoice v1 is unsigned");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM payment_transaction WHERE order_id = ?", orderId),
                "setup: no payment yet");
        Assertions.assertNull(orderLastEmailedAt(orderId), "setup: the sales_order mirror is null");
        assertInvoiceEligible(orderId, true);

        // Every send / notification so far belongs to the seed: the race starts with empty recorders.
        resetSenders();
        return new ConvertibleOrder(orderId, orderNumber, longValue(quoteV1.get("quote_version_id")),
                quoteAcceptedAt, signatureFileId, issuedVersionId, issuedToken);
    }

    /**
     * Insert the invoice-ready order header, allocating {@code order_sequence_number} exactly like
     * {@code OrderCreateRepository} (per-business advisory lock + MAX + 1, one transaction) so a
     * committed row can never collide with any other class's fixed sequence range.
     */
    private long insertCommittedOrder() {
        Long orderId = transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", (rs, rowNum) -> Boolean.TRUE,
                    BUSINESS_AUSSIE);
            Integer seq = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(order_sequence_number), 0) + 1 FROM sales_order WHERE business_id = ?",
                    Integer.class, BUSINESS_AUSSIE);
            if (seq == null) {
                throw new IllegalStateException("no next order_sequence_number");
            }
            String orderNumber = ORDER_NUMBER_PREFIX + String.format("%05d", seq % 100_000);
            return jdbcTemplate.queryForObject(
                    "INSERT INTO sales_order "
                            + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                            + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                            + " proposed_lay_date, lay_date_status, "
                            + " price_adjustment_inc_gst, sale_price_ex_gst, total_cost, gp, gp_percent, "
                            + " created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 1, 2026, ?, "
                            + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status, "
                            + " NULL, 100.00, 40.00, 60.00, 60.00, ?, ?) "
                            + "RETURNING order_id",
                    Long.class,
                    BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, seq, orderNumber, DETAILS_OF_SALE,
                    SEEDED_UPDATED_AT, SEEDED_UPDATED_AT);
        });
        Assertions.assertNotNull(orderId, "setup: order insert returned no id");
        return orderId;
    }

    private void seedCustomer(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) VALUES (?, ?, ?, ?, ?)",
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, CUSTOMER_EMAIL, CUSTOMER_MOBILE);
    }

    private void seedAddresses(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Test Street', 'Sydney', 'NSW', '2000')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '7', 'Install Street', 'Sydney', 'NSW', '2000')",
                orderId);
    }

    /** One store_charge (tagged, swept) + one order_charge_line: 100.00 ex, 40.00 cost. */
    private void seedChargeLine(long orderId) {
        String code = CHARGE_CODE_PREFIX + orderId;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Conversion race charge', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, STORE_SYD_CBD, code);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Conversion race charge', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, code);
    }

    /** D.1 Create through the API: the unsigned Path B invoice v1 (110.00 inc from the live summary). */
    private void createUnsignedInvoice(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: invoice create (D.1)");
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, json(result).path("message").asText(), "setup: D.1 message");
        Map<String, Object> invoice = invoiceRow(orderId, 1);
        Assertions.assertNull(invoice.get("accepted_at"), "setup: invoice v1 must be unsigned");
        assertMoney(INVOICE_V1_EX, invoice.get("sale_price_ex_gst"), "setup: invoice v1 ex");
        assertMoney(INVOICE_V1_INC, invoice.get("sale_price_inc_gst"), "setup: invoice v1 inc");
        assertMoney("0.00", invoice.get("total_paid"), "setup: invoice v1 total_paid");
        assertMoney(INVOICE_V1_INC, invoice.get("balance_due"), "setup: invoice v1 balance_due");
        assertUnsignedPathBRow(invoice, "setup: invoice v1 (D.1)");
    }

    private void saveDraft(long orderId, String body) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(orderId) + "/quote/draft").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertStatus(result, 200, "setup: quote draft save");
    }

    /** Issue through the protected send-email and take the plaintext token from the recorded email. */
    private String sendQuoteAndExtractToken(long orderId, int expectedVersion) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/quote/send-email").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: quote send-email (issue v" + expectedVersion + ")");
        JsonNode sendBody = json(result);
        Assertions.assertEquals(QUOTE_SENT_MESSAGE, sendBody.path("message").asText(), "setup: send message");
        Assertions.assertEquals(expectedVersion, sendBody.path("data").path("version_number").asInt(),
                "setup: issued version number");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly one quote email per send");
        QuoteEmailRequest email = sent.get(sent.size() - 1);
        Assertions.assertEquals(expectedVersion, email.quoteVersionNumber(), "setup: the email delivered this version");
        return extractToken(email.bodyText());
    }

    private static String extractToken(String messageBody) {
        Matcher matcher = LINK_TOKEN_PATTERN.matcher(messageBody);
        Assertions.assertTrue(matcher.find(), "message body must contain the /q/{token} link: " + messageBody);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "the link must appear exactly once in the body");
        Assertions.assertEquals(43, token.length(), "a minted token is 43 URL-safe Base64 characters");
        return token;
    }

    // ================================================================
    // Committed cleanup (FK-safe rows, then files on disk)
    // ================================================================

    private void sweepCommittedTestData() {
        List<Long> orderIds = jdbcTemplate.queryForList(
                "SELECT order_id FROM sales_order WHERE business_id = ? AND order_number LIKE ?",
                Long.class, BUSINESS_AUSSIE, ORDER_NUMBER_PREFIX + "%");
        for (Long orderId : orderIds) {
            deleteCommittedOrder(orderId);
        }
        jdbcTemplate.update(
                "DELETE FROM store_charge sc WHERE sc.store_id = ? AND sc.code LIKE ? "
                        + "AND NOT EXISTS (SELECT 1 FROM order_charge_line l WHERE l.charge_id = sc.charge_id)",
                STORE_SYD_CBD, CHARGE_CODE_PREFIX + "%");
    }

    /**
     * Delete one committed order and everything it owns in ONE transaction (bounded by a lock
     * timeout), in FK-safe order: tokens, invoices (they may reference a quote_version and the
     * quote's signature stored_file since V19 / PR2), versions (cascade lines), draft (cascade lines),
     * the other order children, the order, then its stored_file rows. The files on disk are deleted
     * only after that commit, then the order directory.
     */
    private void deleteCommittedOrder(long orderId) {
        List<String> storagePaths = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
            List<Map<String, Object>> files = jdbcTemplate.queryForList(ORDER_FILES_SQL,
                    orderId, orderId, orderId, orderId, orderId, orderId, orderStoragePrefix(orderId) + "%");
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
        deleteDirectoryQuietly(orderDirectory(orderId));
    }

    /** Recursive delete of one order directory; refuses the storage root itself and anything outside it. */
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
        quoteEmailSender.reset();
        smsSender.reset();
        invoiceEmailSender.reset();
        notificationSender.reset();
    }

    // ================================================================
    // Committed-state probes
    // ================================================================

    /**
     * Everything on the order outside the invoice table: the header (status, price columns, updated_at),
     * every quote version + version line + token, the draft + its lines, the customer, addresses,
     * product / charge lines and the payments. Compared before / after each race.
     */
    private record OrderState(Map<String, Object> header,
                              List<Map<String, Object>> quoteVersions,
                              List<Map<String, Object>> quoteVersionLines,
                              List<Map<String, Object>> quoteTokens,
                              List<Map<String, Object>> draft,
                              List<Map<String, Object>> draftLines,
                              List<Map<String, Object>> customer,
                              List<Map<String, Object>> addresses,
                              List<Map<String, Object>> productLines,
                              List<Map<String, Object>> chargeLines,
                              List<Map<String, Object>> payments) {

        OrderState withQuoteRows(List<Map<String, Object>> versions, List<Map<String, Object>> tokens) {
            return new OrderState(header, versions, quoteVersionLines, tokens, draft, draftLines, customer,
                    addresses, productLines, chargeLines, payments);
        }
    }

    private OrderState orderState(long orderId) {
        return new OrderState(
                jdbcTemplate.queryForMap(
                        "SELECT order_number, order_status::text AS order_status, flooring_type::text AS flooring_type, "
                                + "details_of_sale, proposed_lay_date, lay_date_status::text AS lay_date_status, "
                                + "price_adjustment_inc_gst, sale_price_ex_gst, total_cost, gp, gp_percent, updated_at "
                                + "FROM sales_order WHERE order_id = ?", orderId),
                jdbcTemplate.queryForList(
                        "SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId),
                jdbcTemplate.queryForList(
                        "SELECT l.* FROM quote_version_line l "
                                + "JOIN quote_version v ON v.quote_version_id = l.quote_version_id "
                                + "WHERE v.order_id = ? ORDER BY l.quote_version_line_id", orderId),
                jdbcTemplate.queryForList(
                        "SELECT t.* FROM quote_token t "
                                + "JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                                + "WHERE v.order_id = ? ORDER BY t.quote_token_id", orderId),
                jdbcTemplate.queryForList("SELECT * FROM quote_draft WHERE order_id = ?", orderId),
                jdbcTemplate.queryForList(
                        "SELECT l.* FROM quote_draft_line l "
                                + "JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id "
                                + "WHERE d.order_id = ? ORDER BY l.quote_draft_line_id", orderId),
                jdbcTemplate.queryForList(
                        "SELECT * FROM order_customer WHERE order_id = ? ORDER BY order_customer_id", orderId),
                jdbcTemplate.queryForList(
                        "SELECT order_address_id, address_type::text AS address_type, unit_number, street_number, "
                                + "street, suburb, state_code, postcode FROM order_address WHERE order_id = ? "
                                + "ORDER BY order_address_id", orderId),
                jdbcTemplate.queryForList(
                        "SELECT * FROM order_product_line WHERE order_id = ? ORDER BY order_product_line_id", orderId),
                jdbcTemplate.queryForList(
                        "SELECT * FROM order_charge_line WHERE order_id = ? ORDER BY order_charge_line_id", orderId),
                jdbcTemplate.queryForList(
                        "SELECT payment_transaction_id, payment_method::text AS payment_method, amount, "
                                + "payment_reference, voided_at, voided_by_user_id FROM payment_transaction "
                                + "WHERE order_id = ? ORDER BY payment_transaction_id", orderId));
    }

    /**
     * Path A never writes the order header (status, working price / override, GP, cost, updated_at),
     * the quote layer (versions, lines, tokens), the draft, the customer / addresses or the product /
     * charge lines; neither does a payment, and D.8 only cancels the ISSUED quote link (expressed in
     * {@code expected}). Payments are asserted separately by each race.
     */
    private static void assertNothingElseMoved(OrderState expected, OrderState actual, String context) {
        Assertions.assertEquals(expected.header(), actual.header(),
                context + ": the sales_order header (status, price columns, updated_at) is untouched");
        Assertions.assertEquals(expected.quoteVersions(), actual.quoteVersions(), context + ": quote_version rows");
        Assertions.assertEquals(expected.quoteVersionLines(), actual.quoteVersionLines(),
                context + ": quote_version_line rows");
        Assertions.assertEquals(expected.quoteTokens(), actual.quoteTokens(), context + ": quote_token rows");
        Assertions.assertEquals(expected.draft(), actual.draft(), context + ": quote_draft");
        Assertions.assertEquals(expected.draftLines(), actual.draftLines(), context + ": quote_draft_line rows");
        Assertions.assertEquals(expected.customer(), actual.customer(), context + ": order_customer");
        Assertions.assertEquals(expected.addresses(), actual.addresses(), context + ": order_address rows");
        Assertions.assertEquals(expected.productLines(), actual.productLines(), context + ": product lines");
        Assertions.assertEquals(expected.chargeLines(), actual.chargeLines(), context + ": charge lines");
    }

    private Map<String, Object> versionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private Map<String, Object> tokenRow(String plainToken) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_token WHERE token_hash = ?", sha256Hex(plainToken));
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private List<Integer> invoiceVersions(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT version_number FROM invoice WHERE order_id = ? ORDER BY version_number", Integer.class, orderId);
    }

    private Map<String, Object> storedFileRow(long storedFileId) {
        return jdbcTemplate.queryForMap(
                "SELECT file_name, storage_path, mime_type, file_size FROM stored_file WHERE stored_file_id = ?",
                storedFileId);
    }

    private Timestamp orderLastEmailedAt(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ================================================================
    // Assertions: invoice rows, payments, signatures, artifacts
    // ================================================================

    /**
     * The invoice row Path A appended (or that a later payment receipts): the accepted quote's frozen
     * snapshot verbatim - details, ex / inc totals, frozen terms, source = the accepted v1, the
     * inherited acceptance (accepted_at, name and the SAME signature stored_file) - with today's
     * invoice_date, the lay date minus 2 days, {@code totalPaid} active payments and
     * {@code balanceDue} = quote inc - payments; never emailed; created by the session user.
     */
    private void assertQuoteBilledInvoiceRow(ConvertibleOrder order, int versionNumber, String totalPaid,
                                             String balanceDue, LocalDate dayBefore, LocalDate dayAfter) {
        String label = "Path A invoice v" + versionNumber;
        Map<String, Object> invoice = invoiceRow(order.orderId(), versionNumber);
        Map<String, Object> quote = versionRow(order.orderId(), 1);
        Assertions.assertEquals(order.acceptedQuoteVersionId(), longValue(invoice.get("source_quote_version_id")),
                label + ": source_quote_version_id = the latest ACCEPTED quote version");
        Assertions.assertEquals(quote.get("terms_snapshot"), invoice.get("terms_snapshot"),
                label + ": terms_snapshot copied verbatim from the quote");
        Assertions.assertEquals(FROZEN_QUOTE_TERMS, invoice.get("terms_snapshot"), label + ": frozen terms");
        Assertions.assertEquals(quote.get("details_of_sale_snapshot"), invoice.get("details_of_sale_snapshot"),
                label + ": details of sale from the quote snapshot");
        assertMoney(QUOTE_TOTAL_EX, invoice.get("sale_price_ex_gst"), label + ": sale_price_ex_gst = quote ex total");
        assertMoney(QUOTE_TOTAL_INC, invoice.get("sale_price_inc_gst"), label + ": sale_price_inc_gst = quote inc total");
        assertMoney(totalPaid, invoice.get("total_paid"), label + ": total_paid = active payments");
        assertMoney(balanceDue, invoice.get("balance_due"), label + ": balance_due = quote inc - active payments");
        Assertions.assertEquals(order.quoteAcceptedAt(), invoice.get("accepted_at"),
                label + ": accepted_at is the quote's signature time (never now)");
        Assertions.assertEquals(quote.get("accepted_customer_name"), invoice.get("accepted_customer_name"),
                label + ": accepted_customer_name inherited verbatim");
        Assertions.assertEquals(order.quoteSignatureFileId(), longValue(invoice.get("accepted_signature_file_id")),
                label + ": the SAME signature stored_file as the quote");
        Assertions.assertNull(invoice.get("last_emailed_at"), label + ": never emailed");
        Assertions.assertEquals(USER_LIAM, longValue(invoice.get("created_by_user_id")), label + ": session user");
        Assertions.assertEquals(EXPECTED_DUE_DATE, ((Date) invoice.get("due_date")).toLocalDate(),
                label + ": due_date = proposed lay date - 2 days");
        LocalDate invoiceDate = ((Date) invoice.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(dayBefore) || invoiceDate.isAfter(dayAfter),
                label + ": invoice_date is today, but was " + invoiceDate);
    }

    /** A Path B row (D.1 or its payment receipt): no quote source, no frozen terms, unsigned, unemailed. */
    private static void assertUnsignedPathBRow(Map<String, Object> invoice, String label) {
        Assertions.assertNull(invoice.get("source_quote_version_id"), label + ": no quote source");
        Assertions.assertNull(invoice.get("terms_snapshot"), label + ": no frozen terms");
        Assertions.assertNull(invoice.get("accepted_at"), label + ": unsigned");
        Assertions.assertNull(invoice.get("accepted_customer_name"), label + ": no accepted name");
        Assertions.assertNull(invoice.get("accepted_signature_file_id"), label + ": no signature");
        Assertions.assertNull(invoice.get("last_emailed_at"), label + ": never emailed");
    }

    /** Exactly ONE payment row: the race's EFTPOS payment, active (never voided) and the only one summed. */
    private void assertSingleActivePayment(OrderState state) {
        Assertions.assertEquals(1, state.payments().size(), "exactly one payment row");
        Map<String, Object> payment = state.payments().get(0);
        Assertions.assertEquals("EFTPOS", payment.get("payment_method"), "payment method");
        assertMoney(PAYMENT_AMOUNT, payment.get("amount"), "payment amount");
        Assertions.assertNull(payment.get("payment_reference"), "no payment reference");
        Assertions.assertNull(payment.get("voided_at"), "the payment is active (not voided)");
        Assertions.assertNull(payment.get("voided_by_user_id"), "the payment is active (no void actor)");
    }

    /**
     * Exactly ONE signature stored_file exists for the order - the accepted quote's (Path A and the
     * payment carry-forward reuse it; nothing re-uploads, copies or re-normalises it) - and it is still
     * on disk; the invoice versions that reference a signature are exactly {@code referencingVersions}.
     */
    private void assertSingleSignatureIsTheQuotes(ConvertibleOrder order, List<Integer> referencingVersions) {
        List<Long> pngRows = jdbcTemplate.queryForList(
                "SELECT stored_file_id FROM stored_file WHERE storage_path LIKE ? AND mime_type = 'image/png' "
                        + "ORDER BY stored_file_id", Long.class, orderStoragePrefix(order.orderId()) + "%");
        Assertions.assertEquals(List.of(order.quoteSignatureFileId()), pngRows,
                "exactly ONE signature stored_file for the order: the accepted quote's");
        Map<String, Object> signature = storedFileRow(order.quoteSignatureFileId());
        Assertions.assertEquals(quoteSignatureName(order.orderNumber(), 1), signature.get("file_name"));
        Assertions.assertTrue(Files.isRegularFile(physicalPath((String) signature.get("storage_path"))),
                "the inherited quote signature file stays on disk");
        Assertions.assertEquals(referencingVersions, jdbcTemplate.queryForList(
                        "SELECT version_number FROM invoice WHERE order_id = ? AND accepted_signature_file_id = ? "
                                + "ORDER BY version_number", Integer.class, order.orderId(), order.quoteSignatureFileId()),
                "invoice versions referencing the quote signature");
        Assertions.assertEquals(referencingVersions, jdbcTemplate.queryForList(
                        "SELECT version_number FROM invoice WHERE order_id = ? AND accepted_signature_file_id IS NOT NULL "
                                + "ORDER BY version_number", Integer.class, order.orderId()),
                "no invoice version references any other signature");
    }

    /**
     * The order's stored_file rows (by storage path) are EXACTLY {@code expectedFileNames} (no
     * duplicate PDF / signature from a losing request), and the files on disk under the order
     * directory are exactly those rows' files.
     */
    private void assertStoredArtifacts(long orderId, String... expectedFileNames) throws IOException {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT file_name, storage_path FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                orderStoragePrefix(orderId) + "%");
        List<String> actualNames = rows.stream().map(row -> (String) row.get("file_name")).sorted().toList();
        List<String> expectedNames = Arrays.stream(expectedFileNames).sorted().toList();
        Assertions.assertEquals(expectedNames, actualNames, "stored_file rows of order " + orderId);
        Set<String> rowPaths = rows.stream()
                .map(row -> (String) row.get("storage_path"))
                .collect(Collectors.toSet());
        Assertions.assertEquals(rowPaths, storagePathsOnDisk(orderId),
                "files on disk must be exactly the stored_file rows' files (none missing, no orphan)");
    }

    /**
     * Post-race parity: every stored_file the order references or stores (invoice PDFs and signatures,
     * quote PDFs and signatures, attachments, anything under its directory) lives under the order's
     * directory, exists on disk with exactly its recorded size, and every file on disk under that
     * directory has its stored_file row (no orphan left by a rolled-back or losing request).
     */
    private void assertStoredFileDiskParity(long orderId) throws IOException {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(ORDER_FILES_SQL,
                orderId, orderId, orderId, orderId, orderId, orderId, orderStoragePrefix(orderId) + "%");
        Set<String> rowPaths = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            String storagePath = (String) row.get("storage_path");
            Assertions.assertTrue(storagePath.startsWith(orderStoragePrefix(orderId)),
                    "every stored_file of the order lives under its storage directory: " + row);
            Path file = physicalPath(storagePath);
            Assertions.assertTrue(Files.isRegularFile(file), "stored_file row without its file on disk: " + row);
            Assertions.assertEquals(longValue(row.get("file_size")), Files.size(file),
                    "stored_file.file_size must equal the bytes on disk: " + row);
            rowPaths.add(storagePath);
        }
        Assertions.assertEquals(rowPaths, new TreeSet<>(storagePathsOnDisk(orderId)),
                "stored_file rows == files on disk for order " + orderId + " (no orphan, none missing)");
    }

    // ================================================================
    // Assertions: responses
    // ================================================================

    /** Path A 201: the exact envelope, every InvoiceDetail key, the quote snapshot and no internal id. */
    private void assertPathACreated(MvcResult result, ConvertibleOrder order, int versionNumber,
                                    String totalPaid, String balanceDue, String label) throws Exception {
        assertStatus(result, 201, label);
        String raw = body(result);
        JsonNode root = json(result);
        Assertions.assertEquals(new TreeSet<>(Set.of("data", "message")), fieldNames(root), label + ": envelope keys");
        Assertions.assertEquals(PATH_A_CREATED_MESSAGE, root.path("message").asText(), label + ": message");
        Assertions.assertEquals(new TreeSet<>(Set.of("invoice")), fieldNames(root.path("data")), label + ": data keys");
        JsonNode invoice = root.path("data").path("invoice");
        Assertions.assertEquals(new TreeSet<>(INVOICE_DETAIL_KEYS), fieldNames(invoice),
                label + ": InvoiceDetail keys (terms always present, internal ids never serialised)");
        Map<String, Object> row = invoiceRow(order.orderId(), versionNumber);
        Assertions.assertEquals(longValue(row.get("invoice_id")), invoice.path("invoice_id").asLong(), label + ": invoice_id");
        Assertions.assertEquals(order.orderId(), invoice.path("order_id").asLong(), label + ": order_id");
        Assertions.assertEquals(versionNumber, invoice.path("version_number").asInt(), label + ": version_number");
        Assertions.assertEquals(row.get("invoice_date").toString(), invoice.path("invoice_date").asText(),
                label + ": invoice_date");
        Assertions.assertEquals(EXPECTED_DUE_DATE.toString(), invoice.path("due_date").asText(), label + ": due_date");
        Assertions.assertEquals(DETAILS_OF_SALE, invoice.path("details_of_sale_snapshot").asText(),
                label + ": details of sale from the quote snapshot");
        assertJsonMoney(QUOTE_TOTAL_EX, invoice.path("sale_price_ex_gst"), label + ": sale_price_ex_gst");
        assertJsonMoney(QUOTE_TOTAL_INC, invoice.path("sale_price_inc_gst"), label + ": sale_price_inc_gst");
        assertJsonMoney(totalPaid, invoice.path("total_paid"), label + ": total_paid");
        assertJsonMoney(balanceDue, invoice.path("balance_due"), label + ": balance_due");
        Assertions.assertEquals(USER_LIAM, invoice.path("created_by_user_id").asLong(), label + ": created_by_user_id");
        Assertions.assertEquals(orderUrl(order.orderId()) + "/invoices/current/file",
                invoice.path("pdf_download_path").asText(), label + ": pdf_download_path");
        Assertions.assertEquals(JSON_TIMESTAMP.format(order.quoteAcceptedAt().toLocalDateTime()),
                invoice.path("accepted_at").asText(), label + ": accepted_at = the quote's signature time");
        Assertions.assertEquals(CUSTOMER_NAME, invoice.path("accepted_customer_name").asText(),
                label + ": inherited accepted name");
        Assertions.assertTrue(invoice.path("accepted_signature_present").isBoolean()
                && invoice.path("accepted_signature_present").booleanValue(), label + ": inherited signature present");
        Assertions.assertEquals(orderUrl(order.orderId()) + "/invoices/current/signature",
                invoice.path("accepted_signature_download_path").asText(), label + ": signature download path");
        Assertions.assertTrue(invoice.path("last_emailed_at").isNull(), label + ": Path A never emails");
        Assertions.assertEquals("QUOTE", invoice.path("terms_source").asText(), label + ": terms_source");
        Assertions.assertEquals(FROZEN_QUOTE_TERMS, invoice.path("terms_html").asText(),
                label + ": terms_html = the frozen quote terms verbatim");
        for (String leak : List.of("/uploads/", "storage_path", "stored_file_id", "accepted_signature_file_id",
                "source_quote_version_id", "terms_snapshot", "token")) {
            Assertions.assertFalse(raw.contains(leak), label + ": the response must not leak " + leak + ": " + raw);
        }
    }

    /** Path A 409: INVOICE_ALREADY_ACCEPTED with the Path A wording, no details, no data. */
    private void assertPathARefused(MvcResult result, String label) throws Exception {
        assertStatus(result, 409, label);
        JsonNode root = json(result);
        Assertions.assertEquals(new TreeSet<>(Set.of("error")), fieldNames(root), label + ": a refusal carries no data");
        JsonNode error = root.path("error");
        Assertions.assertEquals(ErrorCode.INVOICE_ALREADY_ACCEPTED.name(), error.path("code").asText(), label + ": code");
        Assertions.assertEquals(PATH_A_SIGNATURE_PRECEDENCE_MESSAGE, error.path("message").asText(),
                label + ": the Path A signature-precedence message");
        Assertions.assertFalse(error.has("details"), label + ": no details");
    }

    /** D.7 201: the payment, the summary and the UNCHANGED current-invoice summary shape (no terms keys). */
    private void assertPaymentRecorded(MvcResult result, int expectedVersion, String totalPaid, String balanceDue,
                                       boolean signed, String label) throws Exception {
        assertStatus(result, 201, label);
        JsonNode root = json(result);
        Assertions.assertEquals(PAYMENT_RECORDED_MESSAGE, root.path("message").asText(), label + ": message");
        JsonNode data = root.path("data");
        JsonNode payment = data.path("payment_transaction");
        Assertions.assertEquals("EFTPOS", payment.path("payment_method").asText(), label + ": payment method");
        assertJsonMoney(PAYMENT_AMOUNT, payment.path("amount"), label + ": payment amount");
        Assertions.assertTrue(payment.path("voided_at").isNull(), label + ": the recorded payment is active");
        assertJsonMoney(totalPaid, data.path("payment_summary").path("total_paid"), label + ": summary total_paid");
        assertJsonMoney(balanceDue, data.path("payment_summary").path("balance_due"), label + ": summary balance_due");
        JsonNode current = data.path("current_invoice");
        Assertions.assertEquals(new TreeSet<>(CURRENT_INVOICE_SUMMARY_KEYS), fieldNames(current),
                label + ": current_invoice keeps the unchanged summary shape (no terms keys)");
        Assertions.assertEquals(expectedVersion, current.path("version_number").asInt(), label + ": current version");
        assertJsonMoney(totalPaid, current.path("total_paid"), label + ": current total_paid");
        assertJsonMoney(balanceDue, current.path("balance_due"), label + ": current balance_due");
        Assertions.assertEquals(signed, current.path("accepted_signature_present").booleanValue(),
                label + ": acceptance carried forward from the paid version");
        if (signed) {
            Assertions.assertEquals(CUSTOMER_NAME, current.path("accepted_customer_name").asText(), label);
        } else {
            Assertions.assertTrue(current.path("accepted_customer_name").isNull(), label + ": unsigned");
        }
        Assertions.assertTrue(current.path("last_emailed_at").isNull(), label + ": a payment never emails");
    }

    private void assertErrorResponse(MvcResult result, int status, ErrorCode code, String label) throws Exception {
        assertStatus(result, status, label);
        JsonNode root = json(result);
        Assertions.assertFalse(root.has("data"), label + ": an error carries no data");
        JsonNode error = root.path("error");
        Assertions.assertEquals(code.name(), error.path("code").asText(), label + ": error code");
        Assertions.assertEquals(code.defaultMessage(), error.path("message").asText(), label + ": error message");
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + body(result));
    }

    /** {@code data.accepted.invoice_eligible} of the quote workspace read (the same precedence rule). */
    private void assertInvoiceEligible(long orderId, boolean expected) throws Exception {
        MvcResult result = mockMvc.perform(get(orderUrl(orderId) + "/quote/workspace").session(liamStore1Session()))
                .andReturn();
        assertStatus(result, 200, "quote workspace read");
        JsonNode eligible = json(result).path("data").path("accepted").path("invoice_eligible");
        Assertions.assertTrue(eligible.isBoolean(), "invoice_eligible must be a JSON boolean but was " + eligible);
        Assertions.assertEquals(expected, eligible.booleanValue(), "workspace accepted.invoice_eligible");
    }

    private void assertNoQuoteEmailNotificationOrSms() {
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty() && quoteEmailSender.failedEmails().isEmpty(),
                "no quote email was sent or attempted during the race");
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty()
                        && notificationSender.failedNotifications().isEmpty(),
                "no quote acceptance notification was sent or attempted during the race");
        Assertions.assertTrue(smsSender.sentMessages().isEmpty() && smsSender.failedMessages().isEmpty(),
                "no SMS was sent or attempted during the race");
    }

    private void assertNoEmailAtAll() {
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty() && invoiceEmailSender.failedEmails().isEmpty(),
                "neither Path A nor a payment ever emails an invoice");
        assertNoQuoteEmailNotificationOrSms();
    }

    // ================================================================
    // PDF assertions
    // ================================================================

    /**
     * The stored PDF of an invoice version billed from the quote (Path A, or its payment receipt):
     * a real PDF of exactly two pages (the frozen quote terms on a dedicated page 2, never on page 1),
     * the footer exactly once, the inherited acceptance caption at the quote's signature time, the
     * quote total / payments / balance, and one more embedded image (the inherited signature) than
     * the unsigned D.1 v1 PDF of the same order.
     */
    private void assertQuoteBilledInvoicePdf(ConvertibleOrder order, int versionNumber, String totalPaid,
                                             String balanceDue) throws IOException {
        String label = "invoice v" + versionNumber + " PDF";
        byte[] pdf = invoicePdfBytes(order, versionNumber);
        Assertions.assertEquals(2, pageCount(pdf), label + ": frozen quote terms render on a dedicated page 2");
        String page1 = flat(pageText(pdf, 1));
        String page2 = flat(pageText(pdf, 2));
        Assertions.assertFalse(page1.contains("TERMS"), label + ": page 1 never carries the terms heading: " + page1);
        Assertions.assertTrue(page2.contains("TERMS"), label + ": page 2 carries the terms heading: " + page2);
        Assertions.assertTrue(page2.contains(FROZEN_QUOTE_TERMS_TEXT), label + ": page 2 renders the frozen quote terms: " + page2);
        String text = flat(pdfText(pdf));
        Assertions.assertEquals(1, countOccurrences(text, PDF_FOOTER), label + ": the footer renders exactly once");
        String compact = noSpace(text);
        // The caption's inline spans are emitted out of content-stream order, so it is matched in the
        // VISUAL (position-sorted) reading order - what the customer actually sees on the page.
        String visual = flat(pdfTextByPosition(pdf));
        String caption = "Accepted by " + CUSTOMER_NAME + " on "
                + PDF_ACCEPTED_AT.format(order.quoteAcceptedAt().toLocalDateTime());
        Assertions.assertTrue(noSpace(visual).contains(noSpace(caption)),
                label + ": inherited acceptance caption '" + caption + "' in: " + visual);
        Assertions.assertFalse(text.contains("Customer signature"),
                label + ": the signed layout replaces the blank signature line");
        Assertions.assertTrue(compact.contains(noSpace("Total Inc. GST $" + QUOTE_TOTAL_INC)),
                label + ": total is the quote inc total: " + text);
        Assertions.assertTrue(compact.contains(noSpace("Payment Made (-) $" + totalPaid)),
                label + ": payments made: " + text);
        Assertions.assertTrue(compact.contains(noSpace("Balance Due $" + balanceDue)),
                label + ": balance due: " + text);
        Assertions.assertTrue(text.contains(DETAILS_OF_SALE), label + ": details of sale from the quote snapshot");
        Assertions.assertEquals(countImages(invoicePdfBytes(order, 1)) + 1, countImages(pdf),
                label + ": embeds exactly one more image (the inherited signature) than the unsigned v1 PDF");
    }

    /** The stored PDF of an invoice version, after checking its stored_file row (name, mime, size, magic). */
    private byte[] invoicePdfBytes(ConvertibleOrder order, int versionNumber) throws IOException {
        Map<String, Object> invoice = invoiceRow(order.orderId(), versionNumber);
        Map<String, Object> file = storedFileRow(longValue(invoice.get("stored_file_id")));
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), versionNumber), file.get("file_name"),
                "invoice v" + versionNumber + " stored_file name");
        Assertions.assertEquals("application/pdf", file.get("mime_type"), "invoice v" + versionNumber + " mime");
        byte[] bytes = Files.readAllBytes(physicalPath((String) file.get("storage_path")));
        Assertions.assertEquals(longValue(file.get("file_size")), bytes.length,
                "invoice v" + versionNumber + " stored_file.file_size == bytes on disk");
        Assertions.assertTrue(bytes.length > 5 && "%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII)),
                "invoice v" + versionNumber + " is a PDF");
        return bytes;
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String pdfTextByPosition(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static String pageText(byte[] pdf, int page) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document);
        }
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        }
    }

    private static int countImages(byte[] pdf) throws IOException {
        int images = 0;
        try (PDDocument document = PDDocument.load(pdf)) {
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();
                for (COSName name : resources.getXObjectNames()) {
                    if (resources.isImageXObject(name)) {
                        images++;
                    }
                }
            }
        }
        return images;
    }

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) != -1) {
            n++;
            i += needle.length();
        }
        return n;
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

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertNotNull(actual, label + ": expected " + expected + " but was null");
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
                label + ": expected " + expected + " but was " + actual);
    }

    private static void assertJsonMoney(String expected, JsonNode node, String label) {
        Assertions.assertTrue(node.isNumber(), label + ": expected a JSON number but was " + node);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(node.decimalValue()),
                label + ": expected " + expected + " but was " + node);
    }

    private static long longValue(Object value) {
        Assertions.assertNotNull(value, "expected a non-null id");
        return ((Number) value).longValue();
    }

    private static int intValue(Object value) {
        return ((Number) value).intValue();
    }

    /** A copy of a row with some columns replaced (the expected state of a row a racer changed). */
    private static Map<String, Object> withColumns(Map<String, Object> row, Object... columnValuePairs) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        for (int i = 0; i < columnValuePairs.length; i += 2) {
            String column = (String) columnValuePairs[i];
            Assertions.assertTrue(copy.containsKey(column), "unknown column " + column);
            copy.put(column, columnValuePairs[i + 1]);
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

    private static String orderStoragePrefix(long orderId) {
        return "/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/";
    }

    private Path orderDirectory(long orderId) {
        return physicalPath(orderStoragePrefix(orderId));
    }

    private Set<String> storagePathsOnDisk(long orderId) throws IOException {
        Path directory = orderDirectory(orderId);
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> orderStoragePrefix(orderId) + file.getFileName())
                    .collect(Collectors.toSet());
        }
    }

    private static String issuedPdfName(String orderNumber, int versionNumber) {
        return "quote-" + orderNumber + "-v" + versionNumber + ".pdf";
    }

    private static String quoteSignatureName(String orderNumber, int versionNumber) {
        return "quote-signature-" + orderNumber + "-v" + versionNumber + ".png";
    }

    private static String signedPdfName(String orderNumber, int versionNumber) {
        return "quote-" + orderNumber + "-v" + versionNumber + "-signed.pdf";
    }

    private static String invoicePdfName(String orderNumber, int versionNumber) {
        return "invoice-" + orderNumber + "-v" + versionNumber + ".pdf";
    }

    private static String invoiceSignatureName(String orderNumber, int versionNumber) {
        return "signature-" + orderNumber + "-v" + versionNumber + ".png";
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
