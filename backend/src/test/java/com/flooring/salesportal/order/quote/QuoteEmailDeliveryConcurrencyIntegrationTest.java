package com.flooring.salesportal.order.quote;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.storage.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 16G PR 6 (#124) — the ATTEMPT-BOUND quote email success stamp under two overlapping resends of ONE issued
 * quote version (16G contract §13.5: "After a successful delivery, the send's follow-up transaction stamps
 * {@code last_emailed_at} only while this attempt is still the latest ... A superseded attempt's late success
 * writes nothing (0 rows, no error, logged at DEBUG)"; the PR 6 row of §16: "two overlapping resends where the
 * newer attempt fails and the older completes later: the older attempt's success writes nothing (0 rows, no
 * error), {@code last_email_delivered} is {@code false} and Customer Quote shows Not delivered").
 *
 * <p><b>The race, deterministic (no sleeps as ordering proof).</b> The order has one ISSUED version with no
 * successful email marker (its first send failed). Then:
 * <ol>
 *   <li>The OLDER attempt 1 (a resend of that version) runs on a worker thread. Its preparation transaction
 *       commits its attempt marker ({@code last_sent_at} T1, a fresh ACTIVE link), and the delivery is HELD
 *       inside the test-scoped {@link HoldingQuoteEmailSender}: the test waits on the hold's "entered" latch
 *       and checks the committed T1.</li>
 *   <li>The test waits (bounded, about a second at most) until the clock is in a later whole second than T1,
 *       then arms {@code failNextSend()} on the recording sender. The wait orders nothing; it only makes T2's
 *       whole-second string differ from T1's, so the 201 below can be told apart from attempt 1's own snapshot.</li>
 *   <li>The NEWER attempt 2 (a resend of the same version) runs to completion on a second worker: it commits
 *       its own marker T2 (later than T1) and its delivery fails, so it answers 502 {@code EMAIL_SEND_FAILED}.</li>
 *   <li>Attempt 1 is released: its delivery succeeds (the recording sender records it), its stamp transaction
 *       finds {@code last_sent_at} = T2 ≠ T1 and updates ZERO rows (the DEBUG diagnostic is captured), and it
 *       still answers its normal 201 — re-read after the zero-row stamp, so it carries T2 and attempt 2's link
 *       expiry (never attempt 1's) and {@code last_email_delivered} is {@code false}.</li>
 * </ol>
 * Every wait is bounded; the hold is always released and the workers always shut down in {@code finally}.
 *
 * <p><b>Why no test transaction.</b> The requests run on worker threads with their own pooled connections and
 * REAL commits (attempt 1's preparation must be committed while it is held), so the fixtures are committed
 * first and every assertion reads committed state. The class is NOT {@code @Transactional}: it seeds its own
 * tenant (business slug {@value #SLUG}, one store, one salesperson with store access — never a V4 identity)
 * and its order with auto-commit SQL, drives the quote through the real draft PUT and send-email endpoints,
 * and sweeps every committed row and stored file of that tenant in {@code @BeforeEach} (crashed-run leftovers)
 * and {@code @AfterEach}, in FK-safe order.
 *
 * <p><b>The delivery hold is test code only.</b> {@link HoldingQuoteEmailSender} is a test-scoped
 * {@code @Primary} {@link QuoteEmailSender} (this class's own nested {@code @TestConfiguration}) that
 * delegates every send to the application's {@link RecordingQuoteEmailSender} — so recording and
 * {@code failNextSend()} are the real ones — and can hold exactly ONE armed send on bounded latches. There is
 * no production hook and no test endpoint. The nested configuration gives this class its own Spring context,
 * with a small connection pool and its own storage directory; the recording sender is reset before and after
 * every test and the hold is disarmed after every test, so no sender state leaks.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = {
        "app.storage.base-dir=" + QuoteEmailDeliveryConcurrencyIntegrationTest.STORAGE_BASE_DIR,
        // Its own context (the nested @TestConfiguration): keep its pool small. A race needs at most two
        // request connections plus the test thread's.
        "spring.datasource.hikari.maximum-pool-size=4"})
class QuoteEmailDeliveryConcurrencyIntegrationTest {

    /** Must equal the {@code app.storage.base-dir} above (asserted before any file is deleted). */
    static final String STORAGE_BASE_DIR = "target/test-storage/quote-email-race";

    // ---- The self-seeded tenant (never a V4 identity). The slug tags everything this class commits. ----
    private static final String SLUG = "pr6-quote-email-race";
    private static final String BUSINESS_NAME = "Wattle Quote Email Race Flooring";
    private static final String STORE_CODE = "QER-ONE";
    private static final String STAFF_CODE = "QE1";
    private static final String CHARGE_CODE = "QERC1";
    private static final String CUSTOMER_EMAIL = "quinn.quoterace@example.com";

    // Itemised 250.00 ex / 275.00 inc (above the 40.00 cost of the seeded charge line).
    private static final String ITEMISED_DRAFT = """
            {"itemised": true, "lines": [
              {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
              {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
            ]}""";

    private static final String QUOTE_SENT_MESSAGE = "Quote sent by email.";
    private static final String EMAIL_SEND_FAILED_MESSAGE = "The quote could not be emailed. Please try again.";
    /** The public link inside the (link-only) quote email: <app-base>/q/{43-char token}. */
    private static final Pattern LINK_TOKEN_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final DateTimeFormatter WHOLE_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    // Bounds of the harness. The latches are the barriers; these only bound the waits.
    private static final long BARRIER_TIMEOUT_SECONDS = 20L;
    private static final long RESPONSE_TIMEOUT_SECONDS = 30L;
    private static final long POOL_TERMINATION_SECONDS = 15L;

    private static final ObjectMapper JSON = new ObjectMapper();

    // Remaining FK children of sales_order (deleted after the quote layers; the *_line quote tables cascade).
    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "payment_transaction", "order_attachment", "order_note", "order_product_line",
            "order_charge_line", "order_address", "order_customer", "order_enquiry");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private RecordingQuoteEmailSender recordingSender;

    @Autowired
    private HoldingQuoteEmailSender holdingSender;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private TransactionTemplate transactionTemplate;

    // The committed tenant of the running test.
    private long businessId;
    private int storeId;
    private long staffUserId;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes this tenant's storage directory recursively - never a real storage dir.
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir, "this class's own test storage dir");
        Assertions.assertTrue(storageRoot().endsWith(Path.of("target", "test-storage", "quote-email-race")),
                () -> "unexpected storage root for a sweeping test class: " + storageRoot());
        transactionTemplate = new TransactionTemplate(transactionManager);
        recordingSender.reset();
        holdingSender.disarm();
        // Leftovers of a crashed earlier run (committed rows + files) must never leak into this one.
        sweepTenant();
        seedTenant();
    }

    @AfterEach
    void tearDown() {
        try {
            sweepTenant();
        } finally {
            holdingSender.disarm();
            recordingSender.reset();
        }
    }

    /**
     * The newer resend fails while the older resend of the SAME version is still delivering; the older
     * delivery then succeeds: its attempt-bound stamp writes nothing, it still answers 201, and both its 201
     * summary and the workspace report last_email_delivered false — the customer's live link is the newer,
     * undelivered one.
     */
    @Test
    void olderResendSucceedsAfterNewerResendFailed_staleSuccessStampsNothing_201AndWorkspaceReportNotDelivered()
            throws Exception {
        long orderId = seedQuoteReadyOrder();
        MockMvc setupMvc = mockMvc();

        // An issued version with NO successful email marker: the first send's delivery fails.
        perform(setupMvc, put(orderUrl(orderId) + "/quote/draft").session(staffSession())
                .contentType(MediaType.APPLICATION_JSON).content(ITEMISED_DRAFT), 200, "setup: draft save");
        recordingSender.failNextSend();
        perform(setupMvc, sendEmail(orderId), 502, "setup: first send (delivery fails)");
        Map<String, Object> issued = versionRow(orderId);
        long versionId = ((Number) issued.get("quote_version_id")).longValue();
        Timestamp firstAttempt = (Timestamp) issued.get("last_sent_at");
        Assertions.assertEquals("ISSUED", issued.get("status"), "setup: one ISSUED version");
        Assertions.assertNotNull(firstAttempt, "setup: the failed first attempt kept its marker");
        Assertions.assertNull(issued.get("last_emailed_at"), "setup: no successful email marker");
        Assertions.assertEquals(1, recordingSender.failedEmails().size(), "setup: one failed delivery");
        Assertions.assertTrue(recordingSender.sentEmails().isEmpty(), "setup: nothing delivered");

        Logs logs = new Logs(QuoteSendService.class);
        ExecutorService workers = Executors.newFixedThreadPool(2, workerThreads());
        Hold hold = holdingSender.holdNextSend();
        try {
            // 1. The OLDER attempt 1: preparation commits its marker, then its delivery is held.
            Future<MvcResult> attempt1 = workers.submit(() -> mockMvc().perform(sendEmail(orderId)).andReturn());
            if (!hold.awaitEntered(BARRIER_TIMEOUT_SECONDS)) {
                Assertions.fail("attempt 1 never reached its (held) delivery: " + describe(attempt1));
            }
            Map<String, Object> afterAttempt1 = versionRow(orderId);
            Timestamp attempt1Marker = (Timestamp) afterAttempt1.get("last_sent_at");
            Assertions.assertEquals(versionId, ((Number) afterAttempt1.get("quote_version_id")).longValue(),
                    "attempt 1 resends the same version");
            Assertions.assertTrue(attempt1Marker.after(firstAttempt),
                    "attempt 1's preparation COMMITTED its own marker before its delivery was held");
            Assertions.assertNull(afterAttempt1.get("last_emailed_at"));
            Assertions.assertEquals(List.of("REPLACED", "ACTIVE"), tokenStatuses(versionId));
            Assertions.assertFalse(attempt1.isDone(), "attempt 1 is still delivering");
            Timestamp attempt1LinkExpiry = activeTokenExpiry(versionId);

            // Attempt 2 must stamp in a LATER whole second than attempt 1, so the whole-second 201 summary
            // can match attempt 2's markers only when it is re-read, never attempt 1's own prepared snapshot.
            awaitWholeSecondAfter(attempt1Marker);

            // 2 + 3. Arm the failure, then run the NEWER attempt 2 to completion: 502.
            recordingSender.failNextSend();
            Future<MvcResult> attempt2 = workers.submit(() -> mockMvc().perform(sendEmail(orderId)).andReturn());
            MvcResult failed = awaitResponse(attempt2, "attempt 2");
            assertStatus(failed, 502, "attempt 2 (newer, delivery fails)");
            JsonNode failedBody = json(failed);
            Assertions.assertEquals("EMAIL_SEND_FAILED", failedBody.path("error").path("code").asText());
            Assertions.assertEquals(EMAIL_SEND_FAILED_MESSAGE, failedBody.path("error").path("message").asText());
            Map<String, Object> afterAttempt2 = versionRow(orderId);
            Timestamp attempt2Marker = (Timestamp) afterAttempt2.get("last_sent_at");
            Assertions.assertTrue(attempt2Marker.after(attempt1Marker), "attempt 2's marker is the latest");
            Assertions.assertNull(afterAttempt2.get("last_emailed_at"), "a failed attempt writes no success marker");
            Assertions.assertEquals(List.of("REPLACED", "REPLACED", "ACTIVE"), tokenStatuses(versionId),
                    "attempt 2 replaced attempt 1's link with its own (undelivered) one");
            Timestamp attempt2LinkExpiry = activeTokenExpiry(versionId);
            Assertions.assertNotEquals(WHOLE_SECONDS.format(attempt1Marker.toLocalDateTime()),
                    WHOLE_SECONDS.format(attempt2Marker.toLocalDateTime()), "the two markers are seconds apart");
            Assertions.assertNotEquals(WHOLE_SECONDS.format(attempt1LinkExpiry.toLocalDateTime()),
                    WHOLE_SECONDS.format(attempt2LinkExpiry.toLocalDateTime()), "so are the two link expiries");
            Assertions.assertFalse(attempt1.isDone(), "attempt 1 is still held while attempt 2 completed");

            // 4. Release attempt 1: its delivery succeeds, its stale success stamps nothing, it answers 201.
            hold.release();
            MvcResult delivered = awaitResponse(attempt1, "attempt 1");
            assertStatus(delivered, 201, "attempt 1 (older, delivered after attempt 2 failed)");
            JsonNode body = json(delivered);
            Assertions.assertEquals(QUOTE_SENT_MESSAGE, body.path("message").asText(), "its normal 201 message");
            JsonNode summary = body.path("data");
            Assertions.assertEquals(1, summary.path("version_number").asInt());
            Assertions.assertEquals("ISSUED", summary.path("status").asText());
            Assertions.assertTrue(summary.path("last_email_delivered").isBoolean(),
                    "last_email_delivered is always a JSON boolean");
            Assertions.assertFalse(summary.path("last_email_delivered").booleanValue(),
                    "the 201 summary is built from the CURRENT markers: the latest attempt (2) was not delivered");
            Assertions.assertTrue(summary.has("last_emailed_at") && summary.get("last_emailed_at").isNull(),
                    "no success marker was written");
            Assertions.assertEquals(WHOLE_SECONDS.format(attempt2Marker.toLocalDateTime()),
                    summary.path("last_sent_at").asText(),
                    "the summary is re-read after the zero-row stamp: attempt 2's marker, not attempt 1's");
            Assertions.assertEquals(WHOLE_SECONDS.format(attempt2LinkExpiry.toLocalDateTime()),
                    summary.path("token_expires_at").asText(),
                    "... and the expiry of the ACTIVE link, attempt 2's, not attempt 1's replaced one");

            // 5. The older success updated ZERO rows: no success marker, attempt 2's persisted marker intact.
            Map<String, Object> after = versionRow(orderId);
            Assertions.assertNull(after.get("last_emailed_at"), "the superseded attempt's success wrote nothing");
            Assertions.assertEquals(attempt2Marker, after.get("last_sent_at"),
                    "last_sent_at stays attempt 2's persisted marker");
            Assertions.assertEquals(1, versionCount(orderId), "both resends reused the one issued version");
            Assertions.assertEquals(List.of("REPLACED", "REPLACED", "ACTIVE"), tokenStatuses(versionId),
                    "the delivered success never revives attempt 1's replaced link");

            // The recording sender saw exactly: the failed first send, attempt 2's failure, attempt 1's delivery
            // — which carried attempt 1's link, now REPLACED.
            Assertions.assertEquals(2, recordingSender.failedEmails().size());
            List<QuoteEmailRequest> sent = recordingSender.sentEmails();
            Assertions.assertEquals(1, sent.size(), "only attempt 1 was delivered");
            String deliveredToken = extractToken(sent.get(0).bodyText());
            Assertions.assertEquals("REPLACED", tokenStatusByHash(sha256Hex(deliveredToken)),
                    "the delivered email carried attempt 1's link, which attempt 2 had already replaced");

            // The zero-row stamp is logged once at DEBUG — order id and version number only, no link.
            List<ILoggingEvent> superseded = logs.events().stream()
                    .filter(event -> event.getLevel() == Level.DEBUG
                            && event.getFormattedMessage().contains("a newer send attempt superseded it"))
                    .toList();
            Assertions.assertEquals(1, superseded.size(), () -> "one superseded-stamp DEBUG event: " + logs.events());
            Assertions.assertEquals("Quote email delivered for order " + orderId + " quote v1, but a newer send "
                            + "attempt superseded it; last_emailed_at not stamped",
                    superseded.get(0).getFormattedMessage());
            for (ILoggingEvent event : logs.events()) {
                String message = event.getFormattedMessage();
                Assertions.assertFalse(message.contains("/q/"), () -> "no bearer link in any log line: " + message);
                Assertions.assertFalse(message.contains(deliveredToken), () -> "no token in any log line: " + message);
                Assertions.assertFalse(message.contains(CUSTOMER_EMAIL), () -> "no recipient in any log line: " + message);
            }

            // 6. The workspace (what the Customer Quote tab reads) reports Not delivered for the email channel.
            MvcResult workspace = perform(mockMvc(), get(orderUrl(orderId) + "/quote/workspace")
                    .session(staffSession()), 200, "workspace after the race");
            JsonNode currentIssued = json(workspace).path("data").path("current_issued");
            Assertions.assertEquals("EMAIL", currentIssued.path("sent_channel").asText());
            Assertions.assertTrue(currentIssued.path("last_email_delivered").isBoolean());
            Assertions.assertFalse(currentIssued.path("last_email_delivered").booleanValue(),
                    "the workspace reports the latest email attempt as not delivered");
            Assertions.assertTrue(currentIssued.get("last_emailed_at").isNull());
        } finally {
            hold.release();
            workers.shutdown();
            if (!workers.awaitTermination(POOL_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
            logs.close();
        }
    }

    // ================================================================
    // The test-scoped delivery hold (test code only — no production hook)
    // ================================================================

    @TestConfiguration
    static class HoldingQuoteEmailSenderConfiguration {

        /**
         * The {@code @Primary} {@link QuoteEmailSender} of THIS test context: {@code QuoteSendService} (the only
         * quote email consumer) gets it; it delegates to the application's {@link RecordingQuoteEmailSender}.
         */
        @Bean
        @Primary
        HoldingQuoteEmailSender holdingQuoteEmailSender(RecordingQuoteEmailSender recordingQuoteEmailSender) {
            return new HoldingQuoteEmailSender(recordingQuoteEmailSender);
        }
    }

    /**
     * Delegates every send to the recording sender (which records it, or throws when its
     * {@code failNextSend()} is armed). One armed {@link Hold} makes the NEXT send — after its preparation
     * transaction committed — signal "entered" and wait (bounded) for its release before delegating.
     */
    static final class HoldingQuoteEmailSender implements QuoteEmailSender {

        private final RecordingQuoteEmailSender delegate;
        private final AtomicReference<Hold> armed = new AtomicReference<>();

        HoldingQuoteEmailSender(RecordingQuoteEmailSender delegate) {
            this.delegate = delegate;
        }

        /** Arm a one-shot hold for the NEXT send. */
        Hold holdNextSend() {
            Hold hold = new Hold();
            if (!armed.compareAndSet(null, hold)) {
                throw new IllegalStateException("a quote email hold is already armed");
            }
            return hold;
        }

        /** Drop an unused hold (and release it, so nothing can stay parked on it). */
        void disarm() {
            Hold hold = armed.getAndSet(null);
            if (hold != null) {
                hold.release();
            }
        }

        @Override
        public void send(QuoteEmailRequest request) {
            Hold hold = armed.getAndSet(null);
            if (hold != null) {
                hold.entered.countDown();
                try {
                    if (!hold.released.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the held quote email was never released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while the quote email was held", ex);
                }
            }
            delegate.send(request);
        }
    }

    /** The two latches of one held send: "entered" (counted down by the sender) and "released" (by the test). */
    static final class Hold {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        boolean awaitEntered(long timeoutSeconds) throws InterruptedException {
            return entered.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }
    }

    /** Captures every event of the given loggers at DEBUG and above while open (the workers' threads included). */
    private static final class Logs implements AutoCloseable {

        private final List<ch.qos.logback.classic.Logger> loggers = new ArrayList<>();
        private final List<Level> previousLevels = new ArrayList<>();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Logs(Class<?>... sources) {
            appender.start();
            for (Class<?> source : sources) {
                ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(source);
                previousLevels.add(logger.getLevel());
                logger.setLevel(Level.DEBUG);
                logger.addAppender(appender);
                loggers.add(logger);
            }
        }

        List<ILoggingEvent> events() {
            synchronized (appender) {
                return List.copyOf(appender.list);
            }
        }

        @Override
        public void close() {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).detachAppender(appender);
                loggers.get(i).setLevel(previousLevels.get(i));
            }
            appender.stop();
        }
    }

    // ================================================================
    // Requests
    // ================================================================

    /** One MockMvc per thread (workers never share one). */
    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private MockHttpSession staffSession() {
        MockHttpSession session = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        session.setAttribute("user_id", Long.valueOf(staffUserId));
        session.setAttribute("business_id", Long.valueOf(businessId));
        session.setAttribute("store_id", Integer.valueOf(storeId));
        return session;
    }

    private String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    /** The protected send-email (issue / resend), with the EmptyObjectRequest {@code {}}. */
    private RequestBuilder sendEmail(long orderId) {
        return post(orderUrl(orderId) + "/quote/send-email").session(staffSession())
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private static MvcResult perform(MockMvc mvc, RequestBuilder request, int expectedStatus, String label)
            throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertStatus(result, expectedStatus, label);
        return result;
    }

    private static void assertStatus(MvcResult result, int expectedStatus, String label) throws Exception {
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertEquals(expectedStatus, status, () -> label + ": unexpected status, body " + body);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static MvcResult awaitResponse(Future<MvcResult> future, String label) throws Exception {
        try {
            return future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new AssertionError(label + " did not complete within " + RESPONSE_TIMEOUT_SECONDS + "s", ex);
        }
    }

    private static String describe(Future<MvcResult> future) {
        if (!future.isDone()) {
            return "still running";
        }
        try {
            MvcResult result = future.get();
            return "completed early with HTTP " + result.getResponse().getStatus() + ": "
                    + result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "failed: " + ex;
        }
    }

    private static java.util.concurrent.ThreadFactory workerThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "quote-email-race-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ================================================================
    // Committed probes
    // ================================================================

    /** The order's single quote_version row (both resends reuse it). */
    private Map<String, Object> versionRow(long orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_version WHERE order_id = ?", orderId);
    }

    private int versionCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    /** The version's link statuses in mint order. */
    private List<String> tokenStatuses(long quoteVersionId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM quote_token WHERE quote_version_id = ? ORDER BY quote_token_id",
                String.class, quoteVersionId);
    }

    private Timestamp activeTokenExpiry(long quoteVersionId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM quote_token WHERE quote_version_id = ? AND status = 'ACTIVE'",
                Timestamp.class, quoteVersionId);
    }

    /**
     * Bounded wait until this JVM's clock (the one the server stamps markers with) is in a later whole second
     * than {@code marker}: at most just over one second.
     */
    private static void awaitWholeSecondAfter(Timestamp marker) throws InterruptedException {
        LocalDateTime markerSecond = marker.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BARRIER_TIMEOUT_SECONDS);
        while (!LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).isAfter(markerSecond)) {
            if (System.nanoTime() - deadline > 0) {
                Assertions.fail("the clock never passed attempt 1's second");
            }
            Thread.sleep(20);
        }
    }

    private String tokenStatusByHash(String tokenHash) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM quote_token WHERE token_hash = ?", String.class, tokenHash);
    }

    private static String extractToken(String messageBody) {
        Matcher matcher = LINK_TOKEN_PATTERN.matcher(messageBody);
        Assertions.assertTrue(matcher.find(), () -> "the email must carry the /q/{token} link: " + messageBody);
        return matcher.group(1);
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ================================================================
    // Committed self-seeding (no test transaction) and cleanup
    // ================================================================

    /** The tenant, committed: an active business with the class slug, one store, one salesperson with access. */
    private void seedTenant() {
        Long business = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active) VALUES (?, ?, TRUE) RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG);
        Assertions.assertNotNull(business, "setup: business insert returned no id");
        businessId = business;
        Integer store = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Wattle Race Showroom', ?, '0290001234', 'showroom.quote.race@example.com', "
                        + " '5 Race Way', 'Burwood', 'NSW', '2134') RETURNING store_id",
                Integer.class, businessId, STORE_CODE);
        Assertions.assertNotNull(store, "setup: store insert returned no id");
        storeId = store;
        Long user = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Quinlan', 'Wattle', ?, 'quinlan.wattle.race@example.com', 'not-a-login-hash') "
                        + "RETURNING user_id",
                Long.class, businessId, STAFF_CODE);
        Assertions.assertNotNull(user, "setup: app_user insert returned no id");
        staffUserId = user;
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, staffUserId, storeId);
    }

    /**
     * A committed LEAD order ready for a quote send: a customer with a valid email, a BILLING address (the V17
     * snapshot source) and one 100.00 ex / 40.00 cost charge line (so the 250.00-ex quote is never below cost).
     */
    private long seedQuoteReadyOrder() {
        String orderNumber = STORE_CODE + "." + STAFF_CODE + ".00001";
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, 1, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 43, 2026) "
                        + "RETURNING order_id",
                Long.class, businessId, storeId, staffUserId, orderNumber);
        Assertions.assertNotNull(orderId, "setup: order insert returned no id");
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quinn', 'Quoterace', ?, '0412345678')",
                orderId, CUSTOMER_EMAIL);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Race Street', 'Burwood', 'NSW', '2134')",
                orderId);
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Quote race supply and lay', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, storeId, CHARGE_CODE);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Quote race supply and lay', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, CHARGE_CODE);
        return orderId;
    }

    /** Remove every committed row and file of this class's tenant (found by its slug), crashed runs included. */
    private void sweepTenant() {
        for (Long tenantBusinessId : jdbcTemplate.queryForList(
                "SELECT business_id FROM business WHERE slug = ?", Long.class, SLUG)) {
            for (Long orderId : jdbcTemplate.queryForList(
                    "SELECT order_id FROM sales_order WHERE business_id = ?", Long.class, tenantBusinessId)) {
                deleteCommittedOrder(orderId);
            }
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
                jdbcTemplate.update("DELETE FROM store_charge WHERE store_id IN "
                        + "(SELECT store_id FROM store WHERE business_id = ?)", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM user_store_access WHERE business_id = ?", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM app_user WHERE business_id = ?", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM store WHERE business_id = ?", tenantBusinessId);
                // Its FK to business has no ON DELETE: wipe it before the business row.
                jdbcTemplate.update("DELETE FROM business_quick_description WHERE business_id = ?", tenantBusinessId);
                jdbcTemplate.update("DELETE FROM business WHERE business_id = ?", tenantBusinessId);
            });
            deleteDirectoryQuietly(storageRoot().resolve("uploads").resolve(String.valueOf(tenantBusinessId)));
        }
    }

    /**
     * Delete one committed order and everything it owns in ONE transaction (bounded by a lock timeout), in
     * FK-safe order — the quote links, the versions (cascade lines), the draft (cascade lines), the other order
     * children, the order — then the issued-PDF stored_file rows; the files on disk only after that commit.
     */
    private void deleteCommittedOrder(long orderId) {
        List<String> storagePaths = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
            List<Map<String, Object>> files = jdbcTemplate.queryForList(
                    "SELECT sf.stored_file_id, sf.storage_path FROM stored_file sf "
                            + "WHERE sf.stored_file_id IN (SELECT issued_pdf_file_id FROM quote_version WHERE order_id = ? "
                            + " UNION SELECT signed_pdf_file_id FROM quote_version WHERE order_id = ? "
                            + " UNION SELECT accepted_signature_file_id FROM quote_version WHERE order_id = ?)",
                    orderId, orderId, orderId);
            jdbcTemplate.update("DELETE FROM quote_token WHERE quote_version_id IN "
                    + "(SELECT quote_version_id FROM quote_version WHERE order_id = ?)", orderId);
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
    }

    private Path storageRoot() {
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
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
}
