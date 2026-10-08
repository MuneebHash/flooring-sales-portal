package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * Phase 16F PR1 — CONTROLLED, DETERMINISTIC concurrency tests for the public quote acceptance
 * ({@code POST /api/v1/public/quotes/{token}/accept}) against every competing order-locked
 * transition: a second accept, a protected resend, a protected cancel, a lazy expiry that falls due
 * while the accept waits, and the in-app invoice acceptance (D.8, decision D5(c)).
 *
 * <p><b>Why no test transaction.</b> The requests run on worker threads with their own pooled
 * connections and REAL commits, so the seed must be committed first and every assertion reads
 * committed state. The class is therefore NOT {@code @Transactional}: it seeds with auto-commit SQL
 * (orders tagged {@value #ORDER_NUMBER_PREFIX}) and sweeps every committed row + every stored file
 * (FK-safe deletes, then the files on disk) in {@code @BeforeEach} (crashed-run leftovers) and
 * {@code @AfterEach}. It shares the Spring context of the other 16F classes that write files under
 * the same {@code app.storage.base-dir} (no bean overrides, no extra pool).
 *
 * <p><b>Deterministic barrier (no sleeps as barriers, no production hooks).</b> A HOLDER connection
 * opens a transaction and takes {@code SELECT ... FOR NO KEY UPDATE} on the order row — a lock that
 * conflicts with BOTH order-lock shapes the code uses (the public accept / lazy expiry's native
 * {@code FOR UPDATE} and the protected paths' JPA {@code PESSIMISTIC_WRITE}). Each request is then
 * submitted to an executor (one MockMvc per worker) and the test polls {@code pg_stat_activity} +
 * {@code pg_blocking_pids} every ~10ms until that request's backend is observed waiting on the
 * order-row lock behind a KNOWN pid: the first request behind the holder, the second behind the
 * FIRST request's pid (it is queued on the row's tuple lock, so it can only run after the first one
 * has taken the row lock and committed). A request that completes before it was observed blocked
 * fails the test immediately (it never serialised on the lock). Only then is the holder released
 * (rollback, or commit when the holder itself carries the competing change), and both responses are
 * collected with bounded timeouts. The holder is always released and the pool always shut down in a
 * {@code finally} (try-with-resources {@link Race}); a straggler still blocked after that is
 * cancelled with {@code pg_cancel_backend} as a last resort.
 *
 * <p>Every case asserts the HTTP outcomes, the committed quote / token / invoice rows, the stored
 * artifacts (stored_file rows == files on disk, no orphan left by a losing request), the
 * {@code sales_order} price columns (D6b written exactly once by the winning acceptance, or
 * untouched — including {@code updated_at}) and the recorded store notifications.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class QuoteAcceptanceConcurrencyIntegrationTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    // Committed seed tags (swept before and after every test, and after a crashed run).
    private static final String ORDER_NUMBER_PREFIX = "NTXCON.ZZ9.";
    private static final String CHARGE_CODE_PREFIX = "NTXRACE";

    private static final String CUSTOMER_FIRST_NAME = "Quote";
    private static final String CUSTOMER_LAST_NAME = "Tester";
    private static final String CUSTOMER_NAME = CUSTOMER_FIRST_NAME + " " + CUSTOMER_LAST_NAME;
    private static final String CUSTOMER_EMAIL = "race.quote@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and install carpet (concurrency test)";

    // The seeded header is a consistent "no override" state for the 200.00 ex / 120.00 cost charge
    // line (220.00 inc, gp 80.00 = 40.00%), stamped with a fixed updated_at so ANY later header write
    // (the D6b price write is the only one in these flows) is detectable.
    private static final Timestamp SEEDED_UPDATED_AT = Timestamp.valueOf("2026-01-01 09:00:00");

    private static final String ACCEPTED_MESSAGE = "Quote accepted.";
    private static final String SENT_EMAIL_MESSAGE = "Quote sent by email.";
    private static final String CANCELLED_MESSAGE = "Quote cancelled.";
    private static final String INVOICE_ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";

    // The public link inside the (link-only) quote email body: <app-base>/q/{token}.
    private static final Pattern LINK_TOKEN_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]+)");

    // QuoteAcceptanceService notification display format.
    private static final DateTimeFormatter NOTIFICATION_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    // A real, decodable 1x1 PNG (the InvoiceAcceptanceControllerTest constant): both accept flows
    // validate it and embed it into a signed PDF, so fake bytes would 400/500 instead of racing.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    // The server stores its OWN re-encoding of that upload (spec §1 server normalisation): asserted as
    // the same image (magic, ends at IEND, same dimensions + pixels), never as the same bytes.
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND_CHUNK =
            {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    // Barrier / harness bounds. The poll interval is NOT a barrier — the barrier is the DB condition.
    private static final long BARRIER_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 10L;
    private static final long RESPONSE_TIMEOUT_SECONDS = 30L;
    private static final long POOL_TERMINATION_SECONDS = 15L;

    /**
     * Backends of THIS database currently waiting on a heavyweight lock held (or queued ahead) by
     * the given pid, while executing an order-row lock statement (the native
     * {@code SELECT order_id FROM sales_order ... FOR UPDATE} or the JPA
     * {@code select ... from sales_order ... for no key update}). Runs auto-commit on the test thread,
     * so every poll sees a fresh {@code pg_stat_activity} snapshot.
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
    // signatures, attachments — plus anything stored under the order's storage directory.
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
    private ObjectMapper objectMapper;

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
    // 1. Double accept — the queue head accepts, the queued duplicate gets 410 INACTIVE
    // ================================================================

    @Test
    void doubleAccept_secondQueuedBehindFirst_onlyQueueHeadAccepts_secondGets410Inactive() throws Exception {
        IssuedQuote quote = seedIssuedQuote(false);

        MvcResult resultA;
        MvcResult resultB;
        try (Race race = new Race(quote.orderId())) {
            Future<MvcResult> acceptA = race.submit(publicAccept(quote.token()));
            int pidA = awaitWaiterBlockedBy(race.holderPid(), "public accept A", acceptA);
            Future<MvcResult> acceptB = race.submit(publicAccept(quote.token()));
            int pidB = awaitWaiterBlockedBy(pidA, "public accept B", acceptB, acceptA);
            assertQueuedInOrder(race.holderPid(), pidA, pidB, acceptA, acceptB);
            // Both requests are parked at the order lock, which the accept path only reaches AFTER its
            // pre-lock token gate (resolve + requireActive) and the multipart validation — so both
            // passed the gate with the token ACTIVE, and it is still ACTIVE (nothing committed yet).
            Assertions.assertEquals("ACTIVE", tokenRow(quote.token()).get("status"),
                    "both accepts must have passed the pre-lock gate on a still-ACTIVE token");

            race.rollbackHolder(); // the holder changed nothing — releasing the row is the trigger

            resultA = awaitResponse(acceptA, "public accept A");
            resultB = awaitResponse(acceptB, "public accept B");
        }

        // Exactly one 201 (the queue head) and one 410 INACTIVE (its locked re-read sees CONSUMED).
        assertPublicAccept201(resultA, "public accept A (queue head)");
        assertErrorResponse(resultB, 410, ErrorCode.QUOTE_LINK_INACTIVE, "public accept B (queued behind A)");

        Timestamp acceptedAt = assertAcceptedVersion(quote);
        Map<String, Object> token = tokenRow(quote.token());
        Assertions.assertEquals("CONSUMED", token.get("status"), "the single acceptance consumes the token");
        Assertions.assertEquals(acceptedAt, token.get("dead_at"), "token dead_at == accepted_at (one instant)");
        Assertions.assertEquals(1, versionCount(quote.orderId()), "no additional quote_version");
        Assertions.assertEquals(1, tokenCount(quote.orderId()), "no additional quote_token");

        assertPriceWrittenByAcceptance(quote.orderId(), acceptedAt);
        assertOrderContentUntouched(quote.orderId());
        // ONE signature + ONE signed PDF (rows and files) — the loser wrote nothing and left no orphan.
        assertStoredArtifacts(quote.orderId(),
                issuedPdfName(quote.orderNumber(), 1),
                quoteSignatureName(quote.orderNumber(), 1),
                signedPdfName(quote.orderNumber(), 1));
        assertExactlyOneStoreNotification(quote, acceptedAt);
        Assertions.assertEquals(1, quoteEmailSender.sentEmails().size(),
                "acceptance never emails the customer (only the original issue email exists)");
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "no invoice email");
    }

    // ================================================================
    // 2. Accept vs resend — the resend commits first
    // ================================================================

    @Test
    void acceptVsResend_resendLocksFirst_tokenReplaced_acceptGets410Superseded_nothingAccepted() throws Exception {
        IssuedQuote quote = seedIssuedQuote(false);

        MvcResult resendResult;
        MvcResult acceptResult;
        try (Race race = new Race(quote.orderId())) {
            Future<MvcResult> resend = race.submit(protectedSendEmail(quote.orderId()));
            int resendPid = awaitWaiterBlockedBy(race.holderPid(), "protected resend", resend);
            Future<MvcResult> accept = race.submit(publicAccept(quote.token()));
            int acceptPid = awaitWaiterBlockedBy(resendPid, "public accept", accept, resend);
            assertQueuedInOrder(race.holderPid(), resendPid, acceptPid, resend, accept);
            Assertions.assertEquals("ACTIVE", tokenRow(quote.token()).get("status"),
                    "the accept passed its pre-lock gate on a still-ACTIVE token");

            race.rollbackHolder();

            resendResult = awaitResponse(resend, "protected resend");
            acceptResult = awaitResponse(accept, "public accept");
        }

        // Resend 201: unchanged draft → the SAME version v1, re-delivered with a fresh token.
        assertStatus(resendResult, 201, "protected resend");
        JsonNode resendBody = json(resendResult);
        Assertions.assertEquals(1, resendBody.path("data").path("version_number").asInt(),
                "an unchanged draft resends the SAME version");
        Assertions.assertEquals("ISSUED", resendBody.path("data").path("status").asText());
        Assertions.assertEquals(SENT_EMAIL_MESSAGE, resendBody.path("message").asText());
        // The accept re-read the token under the lock AFTER the resend committed: REPLACED → 410.
        assertErrorResponse(acceptResult, 410, ErrorCode.QUOTE_LINK_SUPERSEDED, "public accept (queued behind the resend)");

        Map<String, Object> oldToken = tokenRow(quote.token());
        Assertions.assertEquals("REPLACED", oldToken.get("status"), "the resend replaced the presented token");
        Assertions.assertNotNull(oldToken.get("dead_at"), "the replaced token carries dead_at");
        List<QuoteEmailRequest> emails = quoteEmailSender.sentEmails();
        Assertions.assertEquals(2, emails.size(), "the original issue + the resend");
        Assertions.assertEquals(1, emails.get(1).quoteVersionNumber(), "the resend re-delivered v1");
        String freshToken = extractToken(emails.get(1).bodyText());
        Assertions.assertNotEquals(quote.token(), freshToken, "the resend minted a new token");
        Map<String, Object> newToken = tokenRow(freshToken);
        Assertions.assertEquals("ACTIVE", newToken.get("status"), "the fresh token is the live link");
        Assertions.assertEquals(quote.quoteVersionId(), ((Number) newToken.get("quote_version_id")).longValue(),
                "the fresh token belongs to the same v1");
        Assertions.assertNull(newToken.get("dead_at"));
        Assertions.assertEquals(1, versionCount(quote.orderId()), "a resend never creates a version");
        Assertions.assertEquals(2, tokenCount(quote.orderId()), "old (REPLACED) + new (ACTIVE)");

        // The losing accept wrote NOTHING: no acceptance fields, files, price write or notification.
        assertVersionNotAccepted(quote, "ISSUED");
        assertPriceUntouched(quote.orderId());
        assertOrderContentUntouched(quote.orderId());
        assertStoredArtifacts(quote.orderId(), issuedPdfName(quote.orderNumber(), 1));
        assertNoStoreNotification();
    }

    // ================================================================
    // 3. Accept vs resend — the accept commits first
    // ================================================================

    @Test
    void acceptVsResend_acceptLocksFirst_acceptCommits_resendIssuesV2WithoutPriceWrite() throws Exception {
        IssuedQuote quote = seedIssuedQuote(false);

        MvcResult acceptResult;
        MvcResult resendResult;
        try (Race race = new Race(quote.orderId())) {
            Future<MvcResult> accept = race.submit(publicAccept(quote.token()));
            int acceptPid = awaitWaiterBlockedBy(race.holderPid(), "public accept", accept);
            Future<MvcResult> resend = race.submit(protectedSendEmail(quote.orderId()));
            int resendPid = awaitWaiterBlockedBy(acceptPid, "protected resend", resend, accept);
            assertQueuedInOrder(race.holderPid(), acceptPid, resendPid, accept, resend);

            race.rollbackHolder();

            acceptResult = awaitResponse(accept, "public accept");
            resendResult = awaitResponse(resend, "protected resend");
        }

        assertPublicAccept201(acceptResult, "public accept (queue head)");
        // The send ran AFTER the acceptance committed: no ISSUED version exists any more, so it issues
        // a NEW version v2 (decision D9 — sending after an acceptance is allowed).
        assertStatus(resendResult, 201, "protected send after the acceptance");
        JsonNode resendBody = json(resendResult);
        Assertions.assertEquals(2, resendBody.path("data").path("version_number").asInt(),
                "a send after acceptance issues a NEW version (max + 1)");
        Assertions.assertEquals("ISSUED", resendBody.path("data").path("status").asText());
        Assertions.assertEquals(SENT_EMAIL_MESSAGE, resendBody.path("message").asText());

        // v1 is accepted and untouched by the later send.
        Timestamp acceptedAt = assertAcceptedVersion(quote);
        Map<String, Object> consumed = tokenRow(quote.token());
        Assertions.assertEquals("CONSUMED", consumed.get("status"), "the accepted link stays consumed");
        Assertions.assertEquals(acceptedAt, consumed.get("dead_at"), "token dead_at == accepted_at");

        // v2: a fresh ISSUED version with its own ACTIVE token and issued PDF, no acceptance fields.
        Map<String, Object> v2 = versionRow(quote.orderId(), 2);
        Assertions.assertEquals("ISSUED", v2.get("status"));
        Assertions.assertNull(v2.get("accepted_at"));
        Assertions.assertNull(v2.get("accepted_customer_name"));
        Assertions.assertNull(v2.get("accepted_signature_file_id"));
        Assertions.assertNull(v2.get("signed_pdf_file_id"));
        Assertions.assertNotNull(v2.get("issued_pdf_file_id"), "v2 stored its own issued PDF");
        List<QuoteEmailRequest> emails = quoteEmailSender.sentEmails();
        Assertions.assertEquals(2, emails.size(), "the original issue + the post-acceptance send");
        Assertions.assertEquals(2, emails.get(1).quoteVersionNumber(), "the second email delivered v2");
        Map<String, Object> v2Token = tokenRow(extractToken(emails.get(1).bodyText()));
        Assertions.assertEquals("ACTIVE", v2Token.get("status"), "v2 has a live link");
        Assertions.assertEquals(((Number) v2.get("quote_version_id")).longValue(),
                ((Number) v2Token.get("quote_version_id")).longValue(), "the new token belongs to v2");
        Assertions.assertEquals(2, versionCount(quote.orderId()));
        Assertions.assertEquals(2, tokenCount(quote.orderId()));
        Assertions.assertEquals(1, tokenCountByStatus(quote.orderId(), "ACTIVE"), "one live link per order");

        // The price is the accepted v1 total, written ONCE by the acceptance: updated_at is still the
        // acceptance instant, so the later send wrote no price.
        assertPriceWrittenByAcceptance(quote.orderId(), acceptedAt);
        assertOrderContentUntouched(quote.orderId());
        assertStoredArtifacts(quote.orderId(),
                issuedPdfName(quote.orderNumber(), 1),
                quoteSignatureName(quote.orderNumber(), 1),
                signedPdfName(quote.orderNumber(), 1),
                issuedPdfName(quote.orderNumber(), 2));
        assertExactlyOneStoreNotification(quote, acceptedAt);
    }

    // ================================================================
    // 4. Accept vs protected cancel — both orders
    // ================================================================

    @Test
    void acceptVsCancel_cancelLocksFirst_cancelWins_acceptGets410Cancelled_nothingAccepted() throws Exception {
        IssuedQuote quote = seedIssuedQuote(false);

        MvcResult cancelResult;
        MvcResult acceptResult;
        try (Race race = new Race(quote.orderId())) {
            Future<MvcResult> cancel = race.submit(protectedCancel(quote.orderId()));
            int cancelPid = awaitWaiterBlockedBy(race.holderPid(), "protected cancel", cancel);
            Future<MvcResult> accept = race.submit(publicAccept(quote.token()));
            int acceptPid = awaitWaiterBlockedBy(cancelPid, "public accept", accept, cancel);
            assertQueuedInOrder(race.holderPid(), cancelPid, acceptPid, cancel, accept);
            Assertions.assertEquals("ACTIVE", tokenRow(quote.token()).get("status"),
                    "the accept passed its pre-lock gate on a still-ACTIVE token");

            race.rollbackHolder();

            cancelResult = awaitResponse(cancel, "protected cancel");
            acceptResult = awaitResponse(accept, "public accept");
        }

        assertStatus(cancelResult, 200, "protected cancel");
        JsonNode cancelBody = json(cancelResult);
        Assertions.assertEquals("CANCELLED", cancelBody.path("data").path("status").asText());
        Assertions.assertEquals(CANCELLED_MESSAGE, cancelBody.path("message").asText());
        assertErrorResponse(acceptResult, 410, ErrorCode.QUOTE_LINK_CANCELLED, "public accept (queued behind the cancel)");

        Map<String, Object> token = tokenRow(quote.token());
        Assertions.assertEquals("CANCELLED", token.get("status"), "the cancel killed the link");
        Assertions.assertNotNull(token.get("dead_at"));
        assertVersionNotAccepted(quote, "CANCELLED");
        Assertions.assertEquals(1, versionCount(quote.orderId()));
        Assertions.assertEquals(1, tokenCount(quote.orderId()));

        assertPriceUntouched(quote.orderId());
        assertOrderContentUntouched(quote.orderId());
        assertStoredArtifacts(quote.orderId(), issuedPdfName(quote.orderNumber(), 1));
        assertNoStoreNotification();
    }

    @Test
    void acceptVsCancel_acceptLocksFirst_acceptWins_cancelGets409AlreadyAccepted() throws Exception {
        IssuedQuote quote = seedIssuedQuote(false);

        MvcResult acceptResult;
        MvcResult cancelResult;
        try (Race race = new Race(quote.orderId())) {
            Future<MvcResult> accept = race.submit(publicAccept(quote.token()));
            int acceptPid = awaitWaiterBlockedBy(race.holderPid(), "public accept", accept);
            Future<MvcResult> cancel = race.submit(protectedCancel(quote.orderId()));
            int cancelPid = awaitWaiterBlockedBy(acceptPid, "protected cancel", cancel, accept);
            assertQueuedInOrder(race.holderPid(), acceptPid, cancelPid, accept, cancel);

            race.rollbackHolder();

            acceptResult = awaitResponse(accept, "public accept");
            cancelResult = awaitResponse(cancel, "protected cancel");
        }

        assertPublicAccept201(acceptResult, "public accept (queue head)");
        // No ISSUED version is left for the cancel, and an ACCEPTED one exists → 409.
        assertErrorResponse(cancelResult, 409, ErrorCode.QUOTE_ALREADY_ACCEPTED, "protected cancel (queued behind the accept)");

        Timestamp acceptedAt = assertAcceptedVersion(quote);
        Map<String, Object> token = tokenRow(quote.token());
        Assertions.assertEquals("CONSUMED", token.get("status"), "the failed cancel never touches the consumed token");
        Assertions.assertEquals(acceptedAt, token.get("dead_at"));
        Assertions.assertEquals(1, versionCount(quote.orderId()));
        Assertions.assertEquals(1, tokenCount(quote.orderId()));

        assertPriceWrittenByAcceptance(quote.orderId(), acceptedAt);
        assertOrderContentUntouched(quote.orderId());
        assertStoredArtifacts(quote.orderId(),
                issuedPdfName(quote.orderNumber(), 1),
                quoteSignatureName(quote.orderNumber(), 1),
                signedPdfName(quote.orderNumber(), 1));
        assertExactlyOneStoreNotification(quote, acceptedAt);
    }

    // ================================================================
    // 5. Expiry falls due at the locked re-read
    // ================================================================

    @Test
    void accept_expiryFallsDueWhileQueued_lockedReReadCommitsExpiryFlip_410Expired() throws Exception {
        IssuedQuote quote = seedIssuedQuote(false);

        MvcResult acceptResult;
        try (Race race = new Race(quote.orderId())) {
            Future<MvcResult> accept = race.submit(publicAccept(quote.token()));
            int acceptPid = awaitWaiterBlockedBy(race.holderPid(), "public accept", accept);
            // Parked at the order lock ⇒ the pre-lock gate already accepted the token as ACTIVE and
            // UNEXPIRED (an over-age token would have been flipped/410'd by resolve() itself).
            Map<String, Object> beforeExpiry = tokenRow(quote.token());
            Assertions.assertEquals("ACTIVE", beforeExpiry.get("status"));
            Assertions.assertTrue(((Timestamp) beforeExpiry.get("expires_at")).toLocalDateTime()
                    .isAfter(LocalDateTime.now()), "the link was unexpired when the accept passed its gate");

            // INSIDE the holder transaction: the expiry falls due. LEAST(DB now, JVM now) − 1 minute is
            // past due on BOTH clocks, so the service's JVM-clock check cannot be fooled by DB/JVM skew.
            int backdated = race.updateInHolder(
                    "UPDATE quote_token "
                            + "SET expires_at = LEAST(now()::timestamp, CAST(? AS timestamp)) - interval '1 minute' "
                            + "WHERE token_hash = ? AND status = 'ACTIVE'",
                    Timestamp.valueOf(LocalDateTime.now()), sha256Hex(quote.token()));
            Assertions.assertEquals(1, backdated, "the holder must backdate exactly the presented token");
            Assertions.assertFalse(accept.isDone(), "the accept must still be parked on the order lock");
            Assertions.assertTrue(isBlockedBy(acceptPid, race.holderPid()),
                    "the accept must still be waiting on the holder; activity: " + activity());

            race.commitHolder(); // publishes the due expiry AND releases the row in one step

            acceptResult = awaitResponse(accept, "public accept");
        }

        assertErrorResponse(acceptResult, 410, ErrorCode.QUOTE_LINK_EXPIRED, "public accept (expiry fell due while queued)");

        // The guarded lazy-expiry flip discovered under the lock is COMMITTED before the 410.
        Map<String, Object> token = tokenRow(quote.token());
        Assertions.assertEquals("EXPIRED", token.get("status"), "the locked re-read flipped the token");
        Assertions.assertNotNull(token.get("dead_at"), "the expiry flip stamps dead_at");
        assertVersionNotAccepted(quote, "EXPIRED");
        Assertions.assertEquals(1, versionCount(quote.orderId()));
        Assertions.assertEquals(1, tokenCount(quote.orderId()));

        assertPriceUntouched(quote.orderId());
        assertOrderContentUntouched(quote.orderId());
        assertStoredArtifacts(quote.orderId(), issuedPdfName(quote.orderNumber(), 1));
        assertNoStoreNotification();
    }

    // ================================================================
    // 6. Public accept vs in-app invoice acceptance (D.8) — both orders
    // ================================================================

    @Test
    void acceptVsInvoiceAccept_invoiceAcceptLocksFirst_quoteLinkCancelled_acceptGets410Cancelled() throws Exception {
        IssuedQuote quote = seedIssuedQuote(true);

        MvcResult invoiceResult;
        MvcResult acceptResult;
        try (Race race = new Race(quote.orderId())) {
            Future<MvcResult> invoiceAcceptance = race.submit(invoiceAccept(quote.orderId()));
            int invoicePid = awaitWaiterBlockedBy(race.holderPid(), "in-app invoice accept (D.8)", invoiceAcceptance);
            Future<MvcResult> accept = race.submit(publicAccept(quote.token()));
            int acceptPid = awaitWaiterBlockedBy(invoicePid, "public quote accept", accept, invoiceAcceptance);
            assertQueuedInOrder(race.holderPid(), invoicePid, acceptPid, invoiceAcceptance, accept);
            Assertions.assertEquals("ACTIVE", tokenRow(quote.token()).get("status"),
                    "the accept passed its pre-lock gate on a still-ACTIVE token");

            race.rollbackHolder();

            invoiceResult = awaitResponse(invoiceAcceptance, "in-app invoice accept (D.8)");
            acceptResult = awaitResponse(accept, "public quote accept");
        }

        assertStatus(invoiceResult, 201, "in-app invoice accept (D.8)");
        Assertions.assertEquals(INVOICE_ACCEPT_EMAILED_MESSAGE, json(invoiceResult).path("message").asText());
        assertErrorResponse(acceptResult, 410, ErrorCode.QUOTE_LINK_CANCELLED, "public quote accept (queued behind D.8)");

        // D.8 appended the accepted invoice v2 (carry-forward; PR1 never writes the quote-source columns).
        Assertions.assertEquals(2, invoiceCount(quote.orderId()));
        Assertions.assertNull(invoiceRow(quote.orderId(), 1).get("accepted_at"), "v1 stays the unsigned version");
        Map<String, Object> invoiceV2 = invoiceRow(quote.orderId(), 2);
        Timestamp invoiceAcceptedAt = (Timestamp) invoiceV2.get("accepted_at");
        Assertions.assertNotNull(invoiceAcceptedAt, "D.8 accepted the invoice");
        Assertions.assertEquals(CUSTOMER_NAME, invoiceV2.get("accepted_customer_name"));
        Assertions.assertNotNull(invoiceV2.get("accepted_signature_file_id"));
        Assertions.assertNull(invoiceV2.get("source_quote_version_id"), "PR1 never writes invoice.source_quote_version_id");
        Assertions.assertNull(invoiceV2.get("terms_snapshot"), "PR1 never writes invoice.terms_snapshot");

        // D5(c): the ISSUED quote and its ACTIVE link were cancelled INSIDE the committed D.8 transaction,
        // at the invoice acceptance instant — which is exactly what the queued accept then re-read.
        assertVersionNotAccepted(quote, "CANCELLED");
        Map<String, Object> token = tokenRow(quote.token());
        Assertions.assertEquals("CANCELLED", token.get("status"), "D.8 killed the quote link (D5(c))");
        Assertions.assertEquals(invoiceAcceptedAt, token.get("dead_at"), "token dead_at == the invoice acceptance time");
        Assertions.assertEquals(1, versionCount(quote.orderId()));
        Assertions.assertEquals(1, tokenCount(quote.orderId()));

        assertPriceUntouched(quote.orderId());
        assertOrderContentUntouched(quote.orderId());
        assertStoredArtifacts(quote.orderId(),
                invoicePdfName(quote.orderNumber(), 1),
                issuedPdfName(quote.orderNumber(), 1),
                invoiceSignatureName(quote.orderNumber(), 2),
                invoicePdfName(quote.orderNumber(), 2));
        assertNoStoreNotification();
        Assertions.assertEquals(1, invoiceEmailSender.sentEmails().size(), "D.8 auto-emailed the signed invoice");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty());
    }

    @Test
    void acceptVsInvoiceAccept_quoteAcceptLocksFirst_bothSucceed_acceptedQuoteNotRevoked() throws Exception {
        IssuedQuote quote = seedIssuedQuote(true);

        MvcResult acceptResult;
        MvcResult invoiceResult;
        try (Race race = new Race(quote.orderId())) {
            Future<MvcResult> accept = race.submit(publicAccept(quote.token()));
            int acceptPid = awaitWaiterBlockedBy(race.holderPid(), "public quote accept", accept);
            Future<MvcResult> invoiceAcceptance = race.submit(invoiceAccept(quote.orderId()));
            int invoicePid = awaitWaiterBlockedBy(acceptPid, "in-app invoice accept (D.8)", invoiceAcceptance, accept);
            assertQueuedInOrder(race.holderPid(), acceptPid, invoicePid, accept, invoiceAcceptance);

            race.rollbackHolder();

            acceptResult = awaitResponse(accept, "public quote accept");
            invoiceResult = awaitResponse(invoiceAcceptance, "in-app invoice accept (D.8)");
        }

        assertPublicAccept201(acceptResult, "public quote accept (queue head)");
        // An accepted quote never blocks D.8, and D.8 never revokes it (D5(d)).
        assertStatus(invoiceResult, 201, "in-app invoice accept (D.8) after the quote acceptance");
        Assertions.assertEquals(INVOICE_ACCEPT_EMAILED_MESSAGE, json(invoiceResult).path("message").asText());

        Timestamp acceptedAt = assertAcceptedVersion(quote);
        Map<String, Object> token = tokenRow(quote.token());
        Assertions.assertEquals("CONSUMED", token.get("status"), "D.8 never touches the consumed token");
        Assertions.assertEquals(acceptedAt, token.get("dead_at"), "token dead_at unchanged by D.8");
        Assertions.assertEquals(1, versionCount(quote.orderId()));
        Assertions.assertEquals(1, tokenCount(quote.orderId()));

        // D.8 accepted the invoice normally: v2 carries v1's snapshot forward (it never re-reads the
        // live — now quote-driven — order price) and leaves the PR2 quote-source columns NULL.
        Assertions.assertEquals(2, invoiceCount(quote.orderId()));
        Map<String, Object> invoiceV1 = invoiceRow(quote.orderId(), 1);
        Map<String, Object> invoiceV2 = invoiceRow(quote.orderId(), 2);
        Assertions.assertNull(invoiceV1.get("accepted_at"));
        Assertions.assertNotNull(invoiceV2.get("accepted_at"), "D.8 accepted the invoice");
        Assertions.assertEquals(CUSTOMER_NAME, invoiceV2.get("accepted_customer_name"));
        assertMoney("220.00", invoiceV1.get("sale_price_inc_gst"), "invoice v1 inc (created before the race)");
        assertMoney("220.00", invoiceV2.get("sale_price_inc_gst"), "invoice v2 carries v1's snapshot forward");
        Assertions.assertNull(invoiceV2.get("source_quote_version_id"), "PR1 never writes invoice.source_quote_version_id");
        Assertions.assertNull(invoiceV2.get("terms_snapshot"), "PR1 never writes invoice.terms_snapshot");

        // D.8 only writes the last_emailed_at mirror on sales_order — the quote's D6b price stands and
        // updated_at is still the quote acceptance instant.
        assertPriceWrittenByAcceptance(quote.orderId(), acceptedAt);
        assertOrderContentUntouched(quote.orderId());
        assertStoredArtifacts(quote.orderId(),
                invoicePdfName(quote.orderNumber(), 1),
                issuedPdfName(quote.orderNumber(), 1),
                quoteSignatureName(quote.orderNumber(), 1),
                signedPdfName(quote.orderNumber(), 1),
                invoiceSignatureName(quote.orderNumber(), 2),
                invoicePdfName(quote.orderNumber(), 2));
        assertExactlyOneStoreNotification(quote, acceptedAt);
        Assertions.assertEquals(1, invoiceEmailSender.sentEmails().size(), "D.8 auto-emailed the signed invoice");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty());
    }

    // ================================================================
    // Race harness
    // ================================================================

    /**
     * One deterministic race over an order row: a holder transaction (its own pooled connection,
     * {@code autoCommit=false}, {@code SET LOCAL lock_timeout} so a lock-order bug errors instead of
     * hanging) holding {@code FOR NO KEY UPDATE} on the order, plus a small executor for the competing
     * requests. {@link #close()} — always reached via try-with-resources — rolls back and closes a
     * still-open holder, lets in-flight requests finish, and cancels any straggler as a last resort.
     */
    private final class Race implements AutoCloseable {

        private final Connection holder;
        private final int holderPid;
        private final ExecutorService workers;
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

        /** Run one request on a worker thread with its OWN MockMvc; the future yields the response. */
        Future<MvcResult> submit(RequestBuilder request) {
            MockMvc workerMockMvc = MockMvcBuilders.webAppContextSetup(context).build();
            Callable<MvcResult> call = () -> workerMockMvc.perform(request).andReturn();
            return workers.submit(call);
        }

        /** Execute a write INSIDE the holder transaction (published only by {@link #commitHolder()}). */
        int updateInHolder(String sql, Object... args) throws SQLException {
            try (PreparedStatement statement = holder.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                return statement.executeUpdate();
            }
        }

        /** Commit the holder's changes and release the order row in one step. */
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
                // best effort — the transaction already ended
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
                        cancelOrderLockWaiters();
                        workers.shutdownNow();
                        workers.awaitTermination(POOL_TERMINATION_SECONDS, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelOrderLockWaiters();
                    workers.shutdownNow();
                }
            }
        }
    }

    /**
     * Poll (every ~{@value #POLL_INTERVAL_MILLIS}ms, up to {@value #BARRIER_TIMEOUT_MILLIS}ms) until the
     * request's backend is observed waiting on the order-row lock behind {@code blockerPid}; returns
     * that backend's pid. Fails fast if the request (or an earlier queued one) completes first — a
     * request that never waited never serialised on the order lock.
     */
    private int awaitWaiterBlockedBy(int blockerPid, String label, Future<MvcResult> waiter,
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
     * to take the row lock — and commit — before the second can.
     */
    private void assertQueuedInOrder(int holderPid, int firstPid, int secondPid,
                                     Future<MvcResult> first, Future<MvcResult> second) {
        Assertions.assertNotEquals(firstPid, secondPid, "the two requests must run on distinct backends");
        Assertions.assertNotEquals(holderPid, firstPid, "a request must never run on the holder connection");
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

    /** Last resort only: cancel backends of this database still parked on an order-row lock. */
    private void cancelOrderLockWaiters() {
        try {
            jdbcTemplate.queryForList(
                    "SELECT pg_cancel_backend(a.pid) FROM pg_stat_activity a "
                            + "WHERE a.datname = current_database() AND a.pid <> pg_backend_pid() "
                            + "AND a.wait_event_type = 'Lock' AND a.query ILIKE '%sales_order%'",
                    Boolean.class);
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
            Thread thread = new Thread(runnable, "quote-accept-race-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ================================================================
    // Requests
    // ================================================================

    private MockHttpSession liamStore1Session() {
        MockHttpSession s = new MockHttpSession();
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

    /** The public, token-only accept: ONE signature part, no session, no slug. */
    private static RequestBuilder publicAccept(String token) {
        return multipart("/api/v1/public/quotes/" + token + "/accept").file(signaturePart());
    }

    /** Protected issue/resend by email (empty body). */
    private RequestBuilder protectedSendEmail(long orderId) {
        return post(orderUrl(orderId) + "/quote/send-email").session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    /** Protected "Cancel quote" (empty body). */
    private RequestBuilder protectedCancel(long orderId) {
        return post(orderUrl(orderId) + "/quote/cancel").session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    /** D.8 in-app invoice acceptance: signature part + accepted_customer_name field + session. */
    private RequestBuilder invoiceAccept(long orderId) {
        return multipart(orderUrl(orderId) + "/invoices/current/accept")
                .file(signaturePart())
                .param("accepted_customer_name", CUSTOMER_NAME)
                .session(liamStore1Session());
    }

    // ================================================================
    // Committed self-seeding (no test transaction)
    // ================================================================

    /** A committed order with ONE issued quote v1 (+ its ACTIVE token) and, optionally, an unsigned invoice. */
    private record IssuedQuote(long orderId, String orderNumber, String token,
                               long quoteVersionId, long issuedPdfFileId) {
    }

    /**
     * Seed (committed): an order header with a deterministic financial state, the customer
     * ("Quote Tester" + a valid email), BILLING + INSTALLATION addresses and one 200.00 / 120.00-cost
     * charge line; optionally create the unsigned invoice v1 through D.1; then save the itemised
     * 250.00 ex / 275.00 inc draft and issue it through the protected send-email — so the token is a
     * real minted one, taken from the recorded (link-only) email.
     */
    private IssuedQuote seedIssuedQuote(boolean withUnsignedInvoice) throws Exception {
        long orderId = insertCommittedOrder();
        String orderNumber = jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
        seedCustomer(orderId);
        seedAddresses(orderId);
        seedChargeLine(orderId);
        if (withUnsignedInvoice) {
            createUnsignedInvoice(orderId);
        }
        saveItemisedTwoLineDraft(orderId);
        String token = issueAndExtractToken(orderId);

        // Pre-race invariants: v1 ISSUED with its stored PDF, the link ACTIVE and unexpired, the V17
        // name frozen, the price untouched and no notification yet.
        Map<String, Object> version = versionRow(orderId, 1);
        Assertions.assertEquals("ISSUED", version.get("status"), "setup: v1 must be ISSUED");
        assertMoney("250.00", version.get("quote_total_ex_gst"), "setup: frozen ex total");
        assertMoney("275.00", version.get("quote_total_inc_gst"), "setup: frozen inc total");
        Assertions.assertEquals(CUSTOMER_NAME, version.get("customer_name_snapshot"), "setup: V17 name snapshot");
        Assertions.assertNotNull(version.get("issued_pdf_file_id"), "setup: the issued PDF is stored");
        Map<String, Object> tokenRow = tokenRow(token);
        Assertions.assertEquals("ACTIVE", tokenRow.get("status"), "setup: the link must be ACTIVE");
        Assertions.assertTrue(((Timestamp) tokenRow.get("expires_at")).toLocalDateTime().isAfter(LocalDateTime.now()),
                "setup: the link must be unexpired");
        assertPriceUntouched(orderId);
        assertNoStoreNotification();

        return new IssuedQuote(orderId, orderNumber, token,
                ((Number) version.get("quote_version_id")).longValue(),
                ((Number) version.get("issued_pdf_file_id")).longValue());
    }

    /**
     * Insert the order header, allocating {@code order_sequence_number} exactly like
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
                            + " NULL, 200.00, 120.00, 80.00, 40.00, ?, ?) "
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
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '34', 'Install Road', 'Sydney', 'NSW', '2000')",
                orderId);
    }

    /** One store_charge (tagged, swept) + one order_charge_line: 200.00 ex, 120.00 cost. */
    private void seedChargeLine(long orderId) {
        String code = CHARGE_CODE_PREFIX + orderId;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Concurrency test charge', 200.00, 120.00) "
                        + "RETURNING charge_id",
                Long.class, STORE_SYD_CBD, code);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Concurrency test charge', 200.00, 120.00, 1, 200.00, 200.00, 120.00)",
                orderId, chargeId, code);
    }

    /** D.1 Create through the API: the unsigned invoice v1 (220.00 inc from the live summary). */
    private void createUnsignedInvoice(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: invoice create (D.1)");
        Map<String, Object> invoice = invoiceRow(orderId, 1);
        Assertions.assertNull(invoice.get("accepted_at"), "setup: invoice v1 must be unsigned");
        assertMoney("220.00", invoice.get("sale_price_inc_gst"), "setup: invoice v1 inc");
    }

    /** Itemised draft through the API: Carpet 2×100 + Underlay 1×50 = 250.00 ex / 275.00 inc. */
    private void saveItemisedTwoLineDraft(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(orderId) + "/quote/draft").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andReturn();
        assertStatus(result, 200, "setup: quote draft save");
    }

    /** Issue v1 through the protected send-email and take the plaintext token from the recorded email. */
    private String issueAndExtractToken(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(protectedSendEmail(orderId)).andReturn();
        assertStatus(result, 201, "setup: quote send-email (issue)");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "setup: exactly the issue email is recorded");
        return extractToken(sent.get(0).bodyText());
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
     * timeout), in FK-safe order: tokens → invoices (they may reference a quote_version since V19) →
     * versions (cascade lines) → draft (cascade lines) → the other order children → the order → its
     * stored_file rows. The files on disk are deleted only after that commit, then the order directory.
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

    private static void deleteDirectoryQuietly(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort — target/ is disposable
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
    // Committed-state probes + assertions
    // ================================================================

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

    private Map<String, Object> storedFileRow(long storedFileId) {
        return jdbcTemplate.queryForMap(
                "SELECT file_name, storage_path, mime_type, file_size FROM stored_file WHERE stored_file_id = ?",
                storedFileId);
    }

    private Map<String, Object> orderHeader(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT order_status::text AS order_status, price_adjustment_inc_gst, sale_price_ex_gst, "
                        + "total_cost, gp, gp_percent, updated_at FROM sales_order WHERE order_id = ?",
                orderId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int versionCount(long orderId) {
        return count("SELECT COUNT(*) FROM quote_version WHERE order_id = ?", orderId);
    }

    private int tokenCount(long orderId) {
        return count("SELECT COUNT(*) FROM quote_token t "
                + "JOIN quote_version v ON t.quote_version_id = v.quote_version_id WHERE v.order_id = ?", orderId);
    }

    private int tokenCountByStatus(long orderId, String status) {
        return count("SELECT COUNT(*) FROM quote_token t "
                + "JOIN quote_version v ON t.quote_version_id = v.quote_version_id "
                + "WHERE v.order_id = ? AND t.status = ?", orderId, status);
    }

    private int invoiceCount(long orderId) {
        return count("SELECT COUNT(*) FROM invoice WHERE order_id = ?", orderId);
    }

    /**
     * v1 is ACCEPTED exactly once: frozen V17 name, the issued-PDF reference untouched, ONE signature
     * (image/png on disk: the server-normalised re-encoding of the uploaded image) and ONE signed PDF
     * (application/pdf, on disk). Returns accepted_at — the single instant also stamped on the token's
     * dead_at and the D6b updated_at.
     */
    private Timestamp assertAcceptedVersion(IssuedQuote quote) throws IOException {
        Map<String, Object> version = versionRow(quote.orderId(), 1);
        Assertions.assertEquals("ACCEPTED", version.get("status"), "quote v1 must be ACCEPTED");
        Timestamp acceptedAt = (Timestamp) version.get("accepted_at");
        Assertions.assertNotNull(acceptedAt, "accepted_at must be stamped");
        Assertions.assertEquals(CUSTOMER_NAME, version.get("accepted_customer_name"),
                "the accepted name is the V17 issue snapshot");
        Assertions.assertEquals(quote.issuedPdfFileId(), ((Number) version.get("issued_pdf_file_id")).longValue(),
                "the issued PDF reference is never touched by the acceptance");
        Object signatureFileId = version.get("accepted_signature_file_id");
        Object signedPdfFileId = version.get("signed_pdf_file_id");
        Assertions.assertNotNull(signatureFileId, "accepted_signature_file_id");
        Assertions.assertNotNull(signedPdfFileId, "signed_pdf_file_id");

        Map<String, Object> signature = storedFileRow(((Number) signatureFileId).longValue());
        Assertions.assertEquals(quoteSignatureName(quote.orderNumber(), 1), signature.get("file_name"));
        Assertions.assertEquals("image/png", signature.get("mime_type"));
        // Spec §1 SERVER NORMALISATION: the stored signature is the server's re-encoding of the upload
        // (the same image, NOT the same bytes), and stored_file.file_size is THAT stored length.
        byte[] storedSignature = Files.readAllBytes(physicalPath((String) signature.get("storage_path")));
        Assertions.assertEquals(((Number) signature.get("file_size")).longValue(), (long) storedSignature.length,
                "stored_file.file_size matches the stored (server-normalised) signature on disk");
        assertNormalisedSignature(ONE_PIXEL_PNG, storedSignature);

        Map<String, Object> signedPdf = storedFileRow(((Number) signedPdfFileId).longValue());
        Assertions.assertEquals(signedPdfName(quote.orderNumber(), 1), signedPdf.get("file_name"));
        Assertions.assertEquals("application/pdf", signedPdf.get("mime_type"));
        byte[] pdfBytes = Files.readAllBytes(physicalPath((String) signedPdf.get("storage_path")));
        Assertions.assertEquals(((Number) signedPdf.get("file_size")).longValue(), (long) pdfBytes.length,
                "stored_file.file_size matches the signed PDF on disk");
        Assertions.assertTrue(pdfBytes.length > 4
                        && new String(pdfBytes, 0, 4, StandardCharsets.ISO_8859_1).equals("%PDF"),
                "the signed artifact is a PDF");
        return acceptedAt;
    }

    /**
     * Spec §1 SERVER NORMALISATION: the stored signature is the decoded upload re-encoded as a clean PNG —
     * PNG magic, the stream ends exactly at IEND, the SAME width/height and the same pixels (alpha for every
     * pixel; RGB wherever the pixel is not fully transparent, since a fully transparent pixel has no colour).
     */
    private static void assertNormalisedSignature(byte[] uploaded, byte[] stored) throws IOException {
        Assertions.assertTrue(stored.length > PNG_MAGIC.length + PNG_IEND_CHUNK.length,
                "stored signature too short: " + stored.length + " bytes");
        Assertions.assertArrayEquals(PNG_MAGIC, Arrays.copyOfRange(stored, 0, PNG_MAGIC.length),
                "the stored signature must start with the PNG magic");
        Assertions.assertArrayEquals(PNG_IEND_CHUNK,
                Arrays.copyOfRange(stored, stored.length - PNG_IEND_CHUNK.length, stored.length),
                "the stored signature must end exactly at the IEND chunk (no trailing bytes)");

        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(uploaded));
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(stored));
        Assertions.assertNotNull(expected, "the uploaded signature must be a decodable PNG");
        Assertions.assertNotNull(actual, "the stored signature must be a decodable PNG");
        Assertions.assertEquals(expected.getWidth(), actual.getWidth(), "the stored signature keeps the width");
        Assertions.assertEquals(expected.getHeight(), actual.getHeight(), "the stored signature keeps the height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y);
                int got = actual.getRGB(x, y);
                int wantAlpha = want >>> 24;
                boolean same = wantAlpha == (got >>> 24)
                        && (wantAlpha == 0 || (want & 0xFFFFFF) == (got & 0xFFFFFF));
                Assertions.assertTrue(same, String.format(Locale.ROOT,
                        "the stored signature keeps the pixels: (%d,%d) upload %08x stored %08x", x, y, want, got));
            }
        }
    }

    /** v1 in {@code expectedStatus} with NO acceptance fields, and its issued-PDF reference untouched. */
    private void assertVersionNotAccepted(IssuedQuote quote, String expectedStatus) {
        Map<String, Object> version = versionRow(quote.orderId(), 1);
        Assertions.assertEquals(expectedStatus, version.get("status"), "quote v1 status");
        Assertions.assertNull(version.get("accepted_at"), "no accepted_at");
        Assertions.assertNull(version.get("accepted_customer_name"), "no accepted_customer_name");
        Assertions.assertNull(version.get("accepted_signature_file_id"), "no signature reference");
        Assertions.assertNull(version.get("signed_pdf_file_id"), "no signed PDF reference");
        Assertions.assertEquals(quote.issuedPdfFileId(), ((Number) version.get("issued_pdf_file_id")).longValue(),
                "the issued PDF reference is untouched");
    }

    /** The seeded header, byte for byte — including updated_at, so NO header write happened at all. */
    private void assertPriceUntouched(long orderId) {
        Map<String, Object> header = orderHeader(orderId);
        Assertions.assertNull(header.get("price_adjustment_inc_gst"), "no sale-price override was written");
        assertMoney("200.00", header.get("sale_price_ex_gst"), "sale_price_ex_gst untouched");
        assertMoney("120.00", header.get("total_cost"), "total_cost untouched");
        assertMoney("80.00", header.get("gp"), "gp untouched");
        assertMoney("40.00", header.get("gp_percent"), "gp_percent untouched");
        Assertions.assertEquals(SEEDED_UPDATED_AT, header.get("updated_at"), "sales_order.updated_at untouched");
    }

    /**
     * D6b written by the winning acceptance: adjustment = 275.00 accepted − 220.00 calculated = 55.00;
     * final 275.00 inc → 250.00 ex; cost 120.00; gp 130.00 = 52.00%. updated_at == accepted_at proves
     * the ONE write came from that acceptance (any later header write would restamp it).
     */
    private void assertPriceWrittenByAcceptance(long orderId, Timestamp acceptedAt) {
        Map<String, Object> header = orderHeader(orderId);
        assertMoney("55.00", header.get("price_adjustment_inc_gst"), "D6b price_adjustment_inc_gst");
        assertMoney("250.00", header.get("sale_price_ex_gst"), "D6b sale_price_ex_gst");
        assertMoney("120.00", header.get("total_cost"), "D6b total_cost");
        assertMoney("130.00", header.get("gp"), "D6b gp");
        assertMoney("52.00", header.get("gp_percent"), "D6b gp_percent");
        Assertions.assertEquals(acceptedAt, header.get("updated_at"),
                "the single D6b write is stamped with the acceptance instant");
    }

    /** Nothing else on the order moves: status, draft header + rows, product/charge lines. */
    private void assertOrderContentUntouched(long orderId) {
        Assertions.assertEquals("LEAD", orderHeader(orderId).get("order_status"), "order_status never changes");
        Map<String, Object> draft = jdbcTemplate.queryForMap(
                "SELECT itemised, quote_total_ex_gst, quote_total_inc_gst FROM quote_draft WHERE order_id = ?", orderId);
        Assertions.assertEquals(Boolean.TRUE, draft.get("itemised"), "draft mode untouched");
        assertMoney("250.00", draft.get("quote_total_ex_gst"), "draft ex total untouched");
        assertMoney("275.00", draft.get("quote_total_inc_gst"), "draft inc total untouched");
        Assertions.assertEquals(2, count("SELECT COUNT(*) FROM quote_draft_line l "
                + "JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id WHERE d.order_id = ?", orderId),
                "draft lines untouched");
        Map<String, Object> charges = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS n, SUM(line_total) AS total, SUM(line_cost) AS cost "
                        + "FROM order_charge_line WHERE order_id = ?", orderId);
        Assertions.assertEquals(1L, ((Number) charges.get("n")).longValue(), "charge lines untouched");
        assertMoney("200.00", charges.get("total"), "charge line total untouched");
        assertMoney("120.00", charges.get("cost"), "charge line cost untouched");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM order_product_line WHERE order_id = ?", orderId),
                "no product line appears");
    }

    /**
     * The order's stored_file rows are EXACTLY {@code expectedFileNames} (no duplicate signature / PDF
     * from a losing request), and the files on disk under the order directory are exactly those rows'
     * files (every artifact present, no orphan left behind by a rolled-back or losing request).
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

    private void assertExactlyOneStoreNotification(IssuedQuote quote, Timestamp acceptedAt) {
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(), "no failed notification");
        List<QuoteAcceptanceNotificationRequest> sent = notificationSender.sentNotifications();
        Assertions.assertEquals(1, sent.size(), "exactly ONE store notification for the single acceptance");
        QuoteAcceptanceNotificationRequest notification = sent.get(0);
        Assertions.assertEquals(storeEmail(), notification.recipientEmail(), "sent to the order's store email");
        Assertions.assertEquals("Quote accepted: " + quote.orderNumber(), notification.subject());
        Assertions.assertEquals("Quote " + quote.orderNumber() + " (version 1) was accepted online by "
                        + CUSTOMER_NAME + " on " + NOTIFICATION_DATE_TIME.format(acceptedAt.toLocalDateTime()) + ".\n\n"
                        + "Accepted total (inc GST): $275.00.\n\n"
                        + "The signed quote is available in the sales portal.",
                notification.bodyText());
        Assertions.assertEquals(quote.orderId(), notification.orderId());
        Assertions.assertEquals(1, notification.quoteVersionNumber());
        Assertions.assertFalse(notification.bodyText().contains(quote.token()), "the notification never carries the token");
        Assertions.assertFalse(notification.bodyText().contains("/q/"), "the notification never carries the link");
    }

    private void assertNoStoreNotification() {
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(),
                "no store notification may be sent without a committed acceptance");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(),
                "no store notification may even be attempted without a committed acceptance");
    }

    private String storeEmail() {
        String email = jdbcTemplate.queryForObject(
                "SELECT email FROM store WHERE store_id = ? AND business_id = ?",
                String.class, STORE_SYD_CBD, BUSINESS_AUSSIE);
        Assertions.assertNotNull(email, "precondition: V4 seeds store 1 with an email (the notification recipient)");
        Assertions.assertFalse(email.isBlank(), "precondition: store 1 email must not be blank");
        return email.trim();
    }

    // ================================================================
    // Responses
    // ================================================================

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + body(result));
    }

    /** 201 with EXACTLY {@code {"data":{"state":"INACTIVE"},"message":"Quote accepted."}}. */
    private void assertPublicAccept201(MvcResult result, String label) throws Exception {
        assertStatus(result, 201, label);
        JsonNode root = json(result);
        Assertions.assertEquals("INACTIVE", root.path("data").path("state").asText(), label);
        Assertions.assertEquals(1, root.path("data").size(), label + ": the 201 data carries the state only");
        Assertions.assertEquals(ACCEPTED_MESSAGE, root.path("message").asText(), label);
    }

    private void assertErrorResponse(MvcResult result, int status, ErrorCode code, String label) throws Exception {
        assertStatus(result, status, label);
        JsonNode error = json(result).path("error");
        Assertions.assertEquals(code.name(), error.path("code").asText(), label + ": error code");
        Assertions.assertEquals(code.defaultMessage(), error.path("message").asText(), label + ": error message");
    }

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertNotNull(actual, label + ": expected " + expected + " but was null");
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
                label + ": expected " + expected + " but was " + actual);
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
