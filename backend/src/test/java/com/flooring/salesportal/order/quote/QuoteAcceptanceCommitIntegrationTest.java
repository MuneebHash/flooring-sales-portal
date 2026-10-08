package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.email.QuoteAcceptanceNotificationRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.SalesOrderFinancialWriteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16F PR1 — DURABLE post-commit behaviour and FAILURE ATOMICITY of the public quote accept
 * ({@code POST /api/v1/public/quotes/{token}/accept}), proven OUTSIDE any test transaction.
 *
 * <p><b>Why a separate, non-transactional class.</b> Inside a {@code @Transactional} test the
 * accept's {@code TransactionTemplate} JOINS the test transaction, so nothing ever really commits:
 * "the store is notified only after commit", "a commit-time failure leaves nothing behind" and "the
 * rollback hook deletes only this request's files" cannot be observed there. This class has NO
 * {@code @Transactional} anywhere: every seed statement auto-commits, every API call commits for
 * real, and every assertion reads COMMITTED state through a pooled auto-commit connection.
 *
 * <p><b>Failure injection</b> (no production hooks):
 * <ul>
 *   <li>at COMMIT — a {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger on
 *       {@code quote_version}, scoped to the test order and to the {@code ISSUED -> ACCEPTED} update,
 *       raises when the transaction commits (created in auto-commit right before the request,
 *       dropped in {@code finally} and by the sweep);</li>
 *   <li>mid-transaction — {@link MockitoSpyBean} spies (real behaviour unless stubbed) throw at one
 *       exact step: the D6b {@code sales_order} header write (the LAST write), the signed-PDF render,
 *       or the signed-PDF file write (the SECOND file write). Stubs are installed only AFTER the
 *       quote was issued, so the issue path's own render/store are never affected.</li>
 *   <li>after commit — the notification-sender spy throws a GENERIC {@code RuntimeException}; the
 *       committed acceptance must stand and the WARN (captured with {@link OutputCaptureExtension})
 *       must name only the exception type + order id + quote version.</li>
 * </ul>
 * The bean overrides give this class its own Spring context (by design). Files go to the shared
 * test storage dir {@code target/test-storage/quote-acceptance}; physical file = base dir +
 * {@code storage_path}.
 *
 * <p><b>Seed / cleanup.</b> Only V4 business 1 / store 1 / user 1 ({@code aussie-floors-group}) are
 * reused; every order is self-seeded and tagged {@code NTXCMT.ZZ9.nnnnn}, its sequence allocated the
 * {@code OrderCreateRepository} way (per-business advisory lock + MAX + 1) so it can never collide.
 * Every row of every {@code NTXCMT.*} order (FK-safe order), its stored files (rows + disk) and any
 * leftover test trigger are swept in BOTH {@code @BeforeEach} and {@code @AfterEach}, so a failed
 * assertion or a crashed earlier run never leaks committed data. The singleton recording senders and
 * every spy are reset in both as well. Single-threaded — no timeouts anywhere.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class QuoteAcceptanceCommitIntegrationTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    // Committed seed rows are tagged so the sweep finds this class's orders — and only them.
    private static final String ORDER_NUMBER_PREFIX = "NTXCMT.ZZ9.";
    private static final String ORDER_SWEEP_PATTERN = "NTXCMT.%";

    // Children of sales_order deleted after quote_token / invoice / quote_version / quote_draft /
    // payment_transaction (every child FK is RESTRICT; the two *_line tables CASCADE from their parent).
    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "order_attachment", "order_note", "order_product_line", "order_charge_line",
            "order_address", "order_customer", "order_enquiry");

    private static final String CUSTOMER_EMAIL = "quote.commit@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    // The V17 issue-time customer_name_snapshot of the seeded customer (first + last) — the frozen
    // accepted name (never typed, never the live order_customer).
    private static final String FROZEN_CUSTOMER_NAME = "Quote Tester";

    // The itemised two-line draft: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex / 275.00 inc GST.
    private static final String QUOTE_TOTAL_EX_GST = "250.00";
    private static final String QUOTE_TOTAL_INC_GST = "275.00";

    // A pre-existing, NON-NULL committed order price, so "unchanged" is checked against real values
    // and the accepted price visibly REPLACES it. Consistent with OrderFinancialCalculator for an order
    // with no product/charge lines: final 12.34 inc -> 11.22 ex, cost 0.00, GP 11.22 (100.00 %).
    private static final String SENTINEL_PRICE_ADJUSTMENT = "12.34";
    private static final String SENTINEL_SALE_PRICE_EX_GST = "11.22";
    private static final String SENTINEL_UPDATED_AT = "2026-01-02 03:04:05";

    // Commit-time failure injection (class-specific names: no collision with any other test class).
    private static final String TRIGGER_FUNCTION = "ntxcmt_fail_at_commit";
    private static final String TRIGGER_PREFIX = "ntxcmt_fail_commit_";

    private static final String ACCEPTED_MESSAGE = "Quote accepted.";
    private static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred.";

    // The real public link in the link-only quote email: <app.public-base-url>/q/{43-char token}.
    private static final Pattern PUBLIC_LINK_PATTERN =
            Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    // The notification's accepted-time format (QuoteAcceptanceService DISPLAY_DATE_TIME).
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    // A real, decodable 1x1 PNG (the InvoiceAcceptanceControllerTest constant): it passes the accept's
    // magic-byte + ImageIO safe-decode validation and embeds cleanly into the signed PDF. The server
    // stores (and embeds) its OWN re-encoding of it (spec §1 server normalisation) — the same image,
    // never these bytes verbatim.
    private static final byte[] SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // PNG framing: the 8-byte signature and the fixed 12-byte IEND chunk (length 0, "IEND", CRC AE426082).
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND_CHUNK =
            {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    // Spies call the REAL methods unless a test stubs them (doX().when(spy) form only). The
    // SalesOrderFinancialWriteRepository field is the @Repository CGLIB proxy around the spy; Spring's
    // SpringMockResolver lets doX().when(..) / verify(..) / Mockito.reset(..) resolve through it.
    @MockitoSpyBean
    private RecordingQuoteAcceptanceNotificationSender notificationSender;

    @MockitoSpyBean
    private FileStorageService fileStorageService;

    @MockitoSpyBean
    private QuotePdfGenerator quotePdfGenerator;

    @MockitoSpyBean
    private SalesOrderFinancialWriteRepository salesOrderFinancialWriteRepository;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes files recursively — never let it run against a real storage dir.
        Assertions.assertTrue(storageBase().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                "this class may only write under its own test storage dir, but was " + storageBase());
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        cleanUpEverything();
    }

    @AfterEach
    void tearDown() {
        cleanUpEverything();
    }

    // ================================================================
    // 1. Durable post-commit notification
    // ================================================================

    /**
     * The store notification is delivered strictly AFTER the acceptance transaction committed: at send
     * time (observed from inside the sender, on the request thread) no transaction or transaction
     * synchronization is active and no connection is bound, so the probe's auto-commit read can only
     * see COMMITTED data — and it already shows ACCEPTED + CONSUMED + the new order price + both stored
     * files. Nothing is written after the notification.
     */
    @Test
    void notification_isSentOnlyAfterCommit_onRequestThreadWithNoTransaction_dbAlreadyShowsDurableAcceptance()
            throws Exception {
        IssuedQuote quote = issueReadyQuote();
        Thread requestThread = Thread.currentThread(); // MockMvc dispatches synchronously on the caller
        AtomicInteger sendCalls = new AtomicInteger();
        AtomicReference<SendTimeObservation> atSend = new AtomicReference<>();
        doAnswer(invocation -> {
            sendCalls.incrementAndGet();
            atSend.set(observeCommittedStateNow(quote));
            return invocation.callRealMethod();
        }).when(notificationSender).send(any(QuoteAcceptanceNotificationRequest.class));

        acceptQuote(quote.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"))
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));

        Assertions.assertEquals(1, sendCalls.get(), "the store notification must be attempted exactly once");
        SendTimeObservation observation = atSend.get();
        Assertions.assertNotNull(observation, "the send-time probe must have run");
        Assertions.assertNull(observation.probeFailure(),
                () -> "the send-time DB probe failed: " + observation.probeFailure());
        Assertions.assertSame(requestThread, observation.thread(),
                "the notification is sent on the request thread, after the transaction returned");
        Assertions.assertFalse(observation.actualTransactionActive(),
                "no transaction may be active when the store notification is sent");
        Assertions.assertFalse(observation.synchronizationActive(),
                "the acceptance transaction must be fully completed (no synchronization) at send time");
        Assertions.assertFalse(observation.connectionBound(),
                "no transactional connection may be bound at send time (the probe reads committed data only)");

        CommittedState committedAtSend = observation.committed();
        Assertions.assertEquals("ACCEPTED", committedAtSend.versionStatus(),
                "the version must already be durably ACCEPTED when the store is notified");
        Assertions.assertNotNull(committedAtSend.acceptedAt(), "accepted_at must already be committed");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, committedAtSend.acceptedCustomerName());
        Assertions.assertNotNull(committedAtSend.acceptedSignatureFileId(), "signature ref must be committed");
        Assertions.assertNotNull(committedAtSend.signedPdfFileId(), "signed PDF ref must be committed");
        Assertions.assertEquals("CONSUMED", committedAtSend.tokenStatus(),
                "the token must already be durably CONSUMED when the store is notified");
        Assertions.assertEquals(committedAtSend.acceptedAt(), committedAtSend.tokenDeadAt(),
                "token dead_at and accepted_at are the same committed instant");
        assertMoney(QUOTE_TOTAL_INC_GST, committedAtSend.priceAdjustmentIncGst(),
                "the D6b order price must already be committed when the store is notified");
        assertMoney(QUOTE_TOTAL_EX_GST, committedAtSend.salePriceExGst(), "committed sale_price_ex_gst");
        Assertions.assertEquals(3L, committedAtSend.storedFileRows(),
                "issued PDF + signature + signed PDF rows must all be committed at send time");
        Assertions.assertEquals(3, committedAtSend.filesOnDisk().size(),
                "issued PDF + signature + signed PDF must all be on disk at send time");

        // Nothing is written after the notification: the state seen at send time IS the final state.
        Assertions.assertEquals(committedAtSend, committedState(quote),
                "no write may happen after the post-commit notification");
        assertAcceptedAndCommitted(quote);
    }

    // ================================================================
    // 2. Commit-time failure (deferred constraint trigger)
    // ================================================================

    /**
     * Every in-transaction step succeeds (both files written, every row written, the D6b write runs)
     * and the failure happens AT COMMIT. Response 500; the committed state is exactly as before
     * (version ISSUED, token ACTIVE, price unchanged, no new stored_file row); the rollback hook
     * removed exactly this request's two files (the method body had already returned, so only the
     * afterCompletion hook can have done it) while the issued PDF is untouched; no notification was
     * sent or attempted. The link stays live: a retry after the fault is removed succeeds.
     */
    @Test
    void commitTimeFailure_deferredTrigger_returns500_commitsNothing_deletesOnlyThisRequestsFiles_noNotification()
            throws Exception {
        IssuedQuote quote = issueReadyQuote();
        CommittedState before = committedState(quote);
        StorageWrites writes = recordStorageWrites(false);
        String trigger = TRIGGER_PREFIX + quote.orderId();
        MvcResult result;
        try {
            installCommitFailureTrigger(trigger, quote.orderId());
            Assertions.assertEquals(1L, count(
                            "SELECT COUNT(*) FROM pg_trigger "
                                    + "WHERE tgrelid = 'quote_version'::regclass AND tgname = ?", trigger),
                    "the commit-failure trigger must be installed before the request");

            result = acceptQuote(quote.token())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(jsonPath("$.error.message").value(INTERNAL_ERROR_MESSAGE))
                    .andReturn();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON quote_version");
        }
        Assertions.assertFalse(result.getResponse().getContentAsString().contains("ntxcmt"),
                "the 500 body must not leak the database failure detail");

        // Every in-transaction write ran (so the failure was at COMMIT): both files, then the D6b write.
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions(),
                "the signature PNG then the signed PDF must have been written inside the transaction");
        Assertions.assertEquals(2, writes.writtenPaths().size(), "both new files were written");
        verify(salesOrderFinancialWriteRepository).updateHeaderFinancialsWithAdjustment(
                eq(quote.orderId()), any(), any(), any(), any(), any(), any());
        assertFilesRemoved(writes.writtenPaths(),
                "a commit-time rollback must delete this request's files via the rollback hook");

        assertNothingCommitted(quote, before);
        retryAcceptSucceeds(quote);
    }

    // ================================================================
    // 3. Persistence failure on the LAST write (mid-transaction)
    // ================================================================

    /**
     * The D6b {@code sales_order} header write — the LAST write — throws. Response 500; nothing is
     * committed (the guarded ACCEPTED / CONSUMED updates and both stored_file inserts that already ran
     * are rolled back); both new files are removed from disk; the issued PDF is intact; no notification.
     */
    @Test
    void lastWriteFailure_d6bPriceWriteThrows_returns500_rollsBackEverything_removesBothNewFiles_noNotification()
            throws Exception {
        IssuedQuote quote = issueReadyQuote();
        CommittedState before = committedState(quote);
        StorageWrites writes = recordStorageWrites(false);
        doThrow(new DataAccessResourceFailureException("injected failure on the D6b sales_order header write"))
                .when(salesOrderFinancialWriteRepository)
                .updateHeaderFinancialsWithAdjustment(anyLong(), any(), any(), any(), any(), any(), any());
        try {
            acceptQuote(quote.token())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(jsonPath("$.error.message").value(INTERNAL_ERROR_MESSAGE));

            // The injected failure point was really reached (once), after both files were written.
            verify(salesOrderFinancialWriteRepository).updateHeaderFinancialsWithAdjustment(
                    eq(quote.orderId()), any(), any(), any(), any(), any(), any());
        } finally {
            Mockito.reset(salesOrderFinancialWriteRepository);
        }
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(2, writes.writtenPaths().size(), "both new files were written before the failure");
        assertFilesRemoved(writes.writtenPaths(),
                "a failure on the last write must remove BOTH files this request wrote");

        assertNothingCommitted(quote, before);
        retryAcceptSucceeds(quote);
    }

    // ================================================================
    // 4. Signed-PDF render failure
    // ================================================================

    /**
     * The signed-PDF render throws (only for the SIGNED model — the issued render path is real).
     * Response 500; the already-written signature file is removed; the signature's stored_file insert
     * is rolled back; nothing is committed; no notification.
     */
    @Test
    void signedPdfRenderFailure_returns500_removesSignatureFile_commitsNothing_noNotification() throws Exception {
        IssuedQuote quote = issueReadyQuote();
        CommittedState before = committedState(quote);
        StorageWrites writes = recordStorageWrites(false);
        AtomicInteger signedRenderAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            QuotePdfModel model = invocation.getArgument(0);
            if (model.accepted()) {
                signedRenderAttempts.incrementAndGet();
                throw new UncheckedIOException("Failed to generate quote PDF",
                        new IOException("injected signed-PDF render failure"));
            }
            return invocation.callRealMethod();
        }).when(quotePdfGenerator).render(any(QuotePdfModel.class));
        try {
            acceptQuote(quote.token())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(jsonPath("$.error.message").value(INTERNAL_ERROR_MESSAGE));
        } finally {
            Mockito.reset(quotePdfGenerator);
        }
        Assertions.assertEquals(1, signedRenderAttempts.get(), "the SIGNED render must have been attempted once");
        Assertions.assertEquals(List.of("png"), writes.attemptedExtensions(),
                "only the signature is written before the render; the signed PDF never is");
        Assertions.assertEquals(1, writes.writtenPaths().size(), "the signature file was written");
        assertFilesRemoved(writes.writtenPaths(), "a render failure must remove the just-written signature file");

        assertNothingCommitted(quote, before);
        retryAcceptSucceeds(quote);
    }

    // ================================================================
    // 5. File-write failure on the SECOND write
    // ================================================================

    /**
     * The signature PNG is written for real; the signed-PDF file write (the SECOND write) throws.
     * Response 500; the just-written signature file is removed; nothing is committed; no notification.
     * The storage stub is installed only after the issue, so the issued PDF's own store was real.
     */
    @Test
    void signedPdfStorageFailure_secondWriteThrows_returns500_removesSignatureFile_commitsNothing_noNotification()
            throws Exception {
        IssuedQuote quote = issueReadyQuote();
        CommittedState before = committedState(quote);
        // Installed only now — the issue's own issued-PDF store above was the real one.
        StorageWrites writes = recordStorageWrites(true);
        try {
            acceptQuote(quote.token())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(jsonPath("$.error.message").value(INTERNAL_ERROR_MESSAGE));
        } finally {
            Mockito.reset(fileStorageService);
        }
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions(),
                "the signature write succeeded and the signed-PDF write was attempted (and failed)");
        Assertions.assertEquals(1, writes.writtenPaths().size(), "only the signature file was written");
        Assertions.assertTrue(writes.writtenPaths().get(0).endsWith(".png"), "the written file is the signature");
        assertFilesRemoved(writes.writtenPaths(),
                "a failed second write must remove the just-written signature file");

        assertNothingCommitted(quote, before);
        retryAcceptSucceeds(quote);
    }

    // ================================================================
    // 6. Successful accept outside a transaction
    // ================================================================

    /**
     * A successful accept with no test transaction: 201, and through auto-commit reads the acceptance
     * (ACCEPTED + frozen name), the CONSUMED token, the D6b price (replacing the old one) and both new
     * stored_file rows are COMMITTED, both new files exist on disk (the signature as the server-normalised
     * PNG of the upload) next to the untouched issued PDF, and exactly one store notification was
     * delivered. The link is dead afterwards and a second accept neither writes nor notifies again.
     */
    @Test
    void successfulAccept_outsideAnyTransaction_commitsAcceptanceTokenAndPrice_bothFilesOnDisk_notifiesExactlyOnce()
            throws Exception {
        IssuedQuote quote = issueReadyQuote();
        StorageWrites writes = recordStorageWrites(false);

        acceptQuote(quote.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"))
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));

        AcceptedFiles accepted = assertAcceptedAndCommitted(quote);
        Assertions.assertEquals(List.of(accepted.signaturePath(), accepted.signedPdfPath()), writes.writtenPaths(),
                "the two files this request wrote are exactly the two committed stored_file references");
        CommittedState afterAccept = committedState(quote);

        // The link is dead; a second accept is rejected at the token gate — no write, no second notification.
        mockMvc.perform(get(publicUrl(quote.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"));
        acceptQuote(quote.token())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_INACTIVE"));
        Assertions.assertEquals(2, writes.writtenPaths().size(), "a rejected second accept must not write a file");
        Assertions.assertEquals(afterAccept, committedState(quote), "a rejected second accept must not change anything");
        Assertions.assertEquals(1, notificationSender.sentNotifications().size(),
                "the store notification is never duplicated");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty());
    }

    // ================================================================
    // 7. Generic (non-transport) notification failure after commit
    // ================================================================

    /**
     * The GENERIC sender-failure branch: the sender throws an {@link IllegalStateException} (not a
     * {@code QuoteAcceptanceNotificationException}) whose message names a recipient address. The
     * acceptance is ALREADY COMMITTED when the sender runs, so the customer still gets 201 and the
     * acceptance, the CONSUMED token, the D6b price and both stored files stay durable. The WARN names
     * only the exception TYPE plus the order id + quote version — never the exception's message (the
     * sensitive marker), the recipient, the accepted name or the token. The dead link never re-attempts
     * the notification.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void notificationGenericRuntimeFailure_afterCommit_201_acceptanceDurable_warnNamesOnlyTypeOrderAndVersion(
            CapturedOutput output) throws Exception {
        IssuedQuote quote = issueReadyQuote();
        String sensitiveMarker = "secret-recipient@example.com";
        String exceptionMessage = "SMTP relay refused " + sensitiveMarker + " (mailbox unavailable)";
        doThrow(new IllegalStateException(exceptionMessage))
                .when(notificationSender).send(any(QuoteAcceptanceNotificationRequest.class));

        int logMark = output.getAll().length();
        acceptQuote(quote.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"))
                .andExpect(jsonPath("$.message").value(ACCEPTED_MESSAGE));
        String acceptLog = output.getAll().substring(logMark);

        // The post-commit send was attempted exactly once — for this order's v1, to the order's store.
        ArgumentCaptor<QuoteAcceptanceNotificationRequest> attempted =
                ArgumentCaptor.forClass(QuoteAcceptanceNotificationRequest.class);
        verify(notificationSender).send(attempted.capture());
        Assertions.assertEquals(quote.orderId(), attempted.getValue().orderId());
        Assertions.assertEquals(1, attempted.getValue().quoteVersionNumber());
        Assertions.assertEquals(storeEmail(), attempted.getValue().recipientEmail());
        // The stub threw before the recording sender ran: nothing recorded as sent OR as a transport failure.
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(), "nothing was delivered");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(),
                "the generic failure is not the recording sender's transport failure");

        // Durably committed regardless of the sender failure (auto-commit reads + disk).
        assertAcceptanceDurablyCommitted(quote);
        CommittedState afterAccept = committedState(quote);

        // The WARN: exception type + order id + quote version only.
        List<String> notificationLines = acceptLog.lines()
                .filter(line -> line.contains("Quote acceptance store notification"))
                .toList();
        Assertions.assertEquals(1, notificationLines.size(),
                "exactly one store-notification log line; request output:\n" + acceptLog);
        String warn = notificationLines.get(0);
        Assertions.assertTrue(warn.contains("WARN"), "logged at WARN: " + warn);
        Assertions.assertTrue(warn.contains("notification failed unexpectedly (IllegalStateException)"),
                "the generic branch logs the exception type only: " + warn);
        Assertions.assertTrue(warn.contains("order " + quote.orderId() + " quote v1"),
                "the log identifies the order id + quote version: " + warn);
        Assertions.assertFalse(warn.contains("@"), "no email address on the notification log line: " + warn);
        Assertions.assertFalse(acceptLog.contains(sensitiveMarker),
                "the exception message (sensitive marker) must never be logged:\n" + acceptLog);
        Assertions.assertFalse(acceptLog.contains("SMTP relay refused"),
                "no part of the exception message may be logged:\n" + acceptLog);
        Assertions.assertFalse(acceptLog.contains(storeEmail()), "the recipient is never logged:\n" + acceptLog);
        Assertions.assertFalse(acceptLog.contains(FROZEN_CUSTOMER_NAME),
                "the accepted name is never logged:\n" + acceptLog);
        Assertions.assertFalse(acceptLog.contains(quote.token()), "the token is never logged:\n" + acceptLog);

        // The link is dead: a retry is 410 INACTIVE, changes nothing and never re-attempts the notification.
        acceptQuote(quote.token())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_INACTIVE"));
        verify(notificationSender).send(any(QuoteAcceptanceNotificationRequest.class));
        Assertions.assertEquals(afterAccept, committedState(quote), "a rejected retry must not change anything");
    }

    // ================================================================
    // Shared assertions
    // ================================================================

    /** A failed accept left the committed state EXACTLY as before and never touched the notifier. */
    private void assertNothingCommitted(IssuedQuote quote, CommittedState before) throws IOException {
        CommittedState after = committedState(quote);
        Assertions.assertEquals(before, after, "a failed acceptance must leave the committed state exactly as before");
        // Readable restatements of the key invariants (all implied by the equality above).
        Assertions.assertEquals("ISSUED", after.versionStatus(), "the version must still be ISSUED");
        Assertions.assertNull(after.acceptedAt(), "accepted_at must not be committed");
        Assertions.assertNull(after.acceptedCustomerName(), "accepted_customer_name must not be committed");
        Assertions.assertNull(after.acceptedSignatureFileId(), "no signature reference may be committed");
        Assertions.assertNull(after.signedPdfFileId(), "no signed PDF reference may be committed");
        Assertions.assertEquals("ACTIVE", after.tokenStatus(), "the token must still be ACTIVE");
        Assertions.assertNull(after.tokenDeadAt(), "token dead_at must not be committed");
        assertMoney(SENTINEL_PRICE_ADJUSTMENT, after.priceAdjustmentIncGst(), "the order price must be unchanged");
        Assertions.assertEquals(1L, after.storedFileRows(), "no new stored_file row may be committed");
        Assertions.assertEquals(Set.of(fileName(quote.issuedPdfPath())), after.filesOnDisk(),
                "the order's storage dir must hold exactly the issued PDF (no signature, no signed PDF)");
        assertIssuedPdfIntact(quote);
        assertNoNotificationAttempted();
    }

    /** After a failure: remove every injected fault and accept again — the still-live link must work. */
    private void retryAcceptSucceeds(IssuedQuote quote) throws Exception {
        Mockito.reset(fileStorageService, quotePdfGenerator, salesOrderFinancialWriteRepository);
        acceptQuote(quote.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"));
        assertAcceptedAndCommitted(quote);
    }

    /**
     * The full committed acceptance ({@link #assertAcceptanceDurablyCommitted}) plus exactly ONE delivered
     * store notification with the post-commit content.
     */
    private AcceptedFiles assertAcceptedAndCommitted(IssuedQuote quote) throws IOException {
        AcceptedFiles files = assertAcceptanceDurablyCommitted(quote);
        Timestamp acceptedAt = committedState(quote).acceptedAt();

        // Exactly ONE store notification, delivered (never failed), with the post-commit content.
        List<QuoteAcceptanceNotificationRequest> sent = notificationSender.sentNotifications();
        Assertions.assertEquals(1, sent.size(), "exactly one store notification must be delivered");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(), "no notification may fail");
        QuoteAcceptanceNotificationRequest notification = sent.get(0);
        Assertions.assertEquals(storeEmail(), notification.recipientEmail(), "recipient = the order's store email");
        Assertions.assertEquals("Quote accepted: " + quote.orderNumber(), notification.subject());
        Assertions.assertEquals("Quote " + quote.orderNumber() + " (version 1) was accepted online by "
                        + FROZEN_CUSTOMER_NAME + " on " + DISPLAY_DATE_TIME.format(acceptedAt.toLocalDateTime())
                        + ".\n\nAccepted total (inc GST): $275.00.\n\n"
                        + "The signed quote is available in the sales portal.",
                notification.bodyText());
        Assertions.assertEquals(quote.orderId(), notification.orderId());
        Assertions.assertEquals(1, notification.quoteVersionNumber());
        return files;
    }

    /**
     * The committed acceptance itself, read via auto-commit JDBC (no notification expectation): version
     * ACCEPTED (frozen name, both file refs), token CONSUMED at the same instant, D6b price committed
     * (REPLACING the sentinel), both new stored_file rows + files (the signature as the server-normalised
     * re-encoding of the upload: same image, file_size = stored length), the issued PDF untouched and
     * exactly these three files in the order dir.
     */
    private AcceptedFiles assertAcceptanceDurablyCommitted(IssuedQuote quote) throws IOException {
        CommittedState state = committedState(quote);
        Assertions.assertEquals("ACCEPTED", state.versionStatus(), "the version must be committed ACCEPTED");
        Timestamp acceptedAt = state.acceptedAt();
        Assertions.assertNotNull(acceptedAt, "accepted_at must be committed");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, state.acceptedCustomerName(),
                "the accepted name is the frozen V17 snapshot");
        Assertions.assertNotNull(state.acceptedSignatureFileId(), "the signature reference must be committed");
        Assertions.assertNotNull(state.signedPdfFileId(), "the signed PDF reference must be committed");
        Assertions.assertEquals(1L, state.versionRows(), "acceptance never inserts a quote_version row");
        Assertions.assertEquals("CONSUMED", state.tokenStatus(), "the token must be committed CONSUMED");
        Assertions.assertEquals(acceptedAt, state.tokenDeadAt(), "token dead_at = accepted_at (one instant)");
        Assertions.assertEquals(1L, state.tokenRows(), "acceptance never mints a token");

        // D6b: the signed inc-GST total is the order's working sale price (no lines -> adjustment = total).
        assertMoney(QUOTE_TOTAL_INC_GST, state.priceAdjustmentIncGst(), "price_adjustment_inc_gst");
        assertMoney(QUOTE_TOTAL_EX_GST, state.salePriceExGst(), "sale_price_ex_gst");
        assertMoney("0.00", state.totalCost(), "total_cost");
        assertMoney(QUOTE_TOTAL_EX_GST, state.gp(), "gp");
        assertMoney("100.00", state.gpPercent(), "gp_percent");
        Assertions.assertEquals(acceptedAt, state.orderUpdatedAt(), "sales_order.updated_at = accepted_at");

        Map<String, Object> signatureFile = storedFileRow(state.acceptedSignatureFileId());
        Map<String, Object> signedPdfFile = storedFileRow(state.signedPdfFileId());
        Assertions.assertEquals("quote-signature-" + quote.orderNumber() + "-v1.png", signatureFile.get("file_name"));
        Assertions.assertEquals("image/png", signatureFile.get("mime_type"));
        Assertions.assertEquals("quote-" + quote.orderNumber() + "-v1-signed.pdf", signedPdfFile.get("file_name"));
        Assertions.assertEquals("application/pdf", signedPdfFile.get("mime_type"));

        String signaturePath = (String) signatureFile.get("storage_path");
        String signedPdfPath = (String) signedPdfFile.get("storage_path");
        // Spec §1 SERVER NORMALISATION: the committed signature file is the server's re-encoding of the
        // upload (same image, not the same bytes), and stored_file.file_size is THAT stored length.
        byte[] storedSignature = Files.readAllBytes(physicalPath(signaturePath));
        Assertions.assertEquals(((Number) signatureFile.get("file_size")).longValue(), storedSignature.length,
                "stored_file.file_size must match the stored (server-normalised) signature on disk");
        assertNormalisedSignature(storedSignature);
        byte[] signedPdf = Files.readAllBytes(physicalPath(signedPdfPath));
        Assertions.assertTrue(startsWithPdfMagic(signedPdf), "the stored signed quote must be a PDF");
        Assertions.assertEquals(((Number) signedPdfFile.get("file_size")).longValue(), signedPdf.length,
                "stored_file.file_size must match the signed PDF on disk");
        assertIssuedPdfIntact(quote);
        Assertions.assertEquals(
                Set.of(fileName(quote.issuedPdfPath()), fileName(signaturePath), fileName(signedPdfPath)),
                state.filesOnDisk(),
                "the order dir must hold exactly the issued PDF + the committed signature + the signed PDF");
        Assertions.assertEquals(3L, state.storedFileRows(), "exactly two stored_file rows were added");
        return new AcceptedFiles(signaturePath, signedPdfPath);
    }

    private void assertNoNotificationAttempted() {
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(), "no notification may be sent");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(), "no notification may be attempted");
        verify(notificationSender, never()).send(any());
    }

    /**
     * Spec §1 SERVER NORMALISATION: the stored signature is the decoded upload re-encoded as a clean PNG,
     * so it is compared as an IMAGE, never byte-for-byte with {@link #SIGNATURE_PNG}: PNG magic, the
     * stream ends exactly at IEND (no trailing bytes), the SAME width/height and the same pixels (alpha
     * for every pixel; RGB wherever the pixel is not fully transparent, since a fully transparent pixel
     * has no colour).
     */
    private static void assertNormalisedSignature(byte[] stored) throws IOException {
        Assertions.assertTrue(stored.length > PNG_MAGIC.length + PNG_IEND_CHUNK.length,
                "stored signature too short: " + stored.length + " bytes");
        Assertions.assertArrayEquals(PNG_MAGIC, Arrays.copyOfRange(stored, 0, PNG_MAGIC.length),
                "the stored signature must start with the PNG magic");
        Assertions.assertArrayEquals(PNG_IEND_CHUNK,
                Arrays.copyOfRange(stored, stored.length - PNG_IEND_CHUNK.length, stored.length),
                "the stored signature must end exactly at the IEND chunk (no trailing bytes)");

        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(SIGNATURE_PNG));
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

    private void assertIssuedPdfIntact(IssuedQuote quote) throws IOException {
        Path issued = physicalPath(quote.issuedPdfPath());
        Assertions.assertTrue(Files.exists(issued), "the original issued PDF must never be deleted");
        Assertions.assertArrayEquals(quote.issuedPdfBytes(), Files.readAllBytes(issued),
                "the original issued PDF bytes must be unchanged");
    }

    private void assertFilesRemoved(List<String> storagePaths, String message) {
        for (String storagePath : storagePaths) {
            Assertions.assertFalse(Files.exists(physicalPath(storagePath)), message + ": " + storagePath);
        }
    }

    private static void assertMoney(String expected, Object actual, String what) {
        Assertions.assertNotNull(actual, what + ": expected " + expected + " but was null");
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
                what + ": expected " + expected + " but was " + actual);
    }

    // ================================================================
    // Committed-state probes (auto-commit JDBC — committed data only)
    // ================================================================

    /** Snapshot of everything an acceptance writes, read from COMMITTED state + the order's storage dir. */
    private CommittedState committedState(IssuedQuote quote) {
        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT status, accepted_at, accepted_customer_name, accepted_signature_file_id, signed_pdf_file_id "
                        + "FROM quote_version WHERE quote_version_id = ?",
                quote.versionId());
        Map<String, Object> token = jdbcTemplate.queryForMap(
                "SELECT status, dead_at FROM quote_token WHERE token_hash = ?", quote.tokenHash());
        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT price_adjustment_inc_gst, sale_price_ex_gst, total_cost, gp, gp_percent, updated_at "
                        + "FROM sales_order WHERE order_id = ?",
                quote.orderId());
        return new CommittedState(
                (String) version.get("status"),
                (Timestamp) version.get("accepted_at"),
                (String) version.get("accepted_customer_name"),
                toLong(version.get("accepted_signature_file_id")),
                toLong(version.get("signed_pdf_file_id")),
                count("SELECT COUNT(*) FROM quote_version WHERE order_id = ?", quote.orderId()),
                (String) token.get("status"),
                (Timestamp) token.get("dead_at"),
                count("SELECT COUNT(*) FROM quote_token t JOIN quote_version v "
                        + "ON v.quote_version_id = t.quote_version_id WHERE v.order_id = ?", quote.orderId()),
                (BigDecimal) order.get("price_adjustment_inc_gst"),
                (BigDecimal) order.get("sale_price_ex_gst"),
                (BigDecimal) order.get("total_cost"),
                (BigDecimal) order.get("gp"),
                (BigDecimal) order.get("gp_percent"),
                (Timestamp) order.get("updated_at"),
                count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ?",
                        orderStoragePrefix(quote.orderId()) + "%"),
                filesOnDisk(quote.orderId()));
    }

    /** Runs INSIDE the notification sender (request thread): the transaction context + committed state. */
    private SendTimeObservation observeCommittedStateNow(IssuedQuote quote) {
        boolean actualTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();
        boolean synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
        boolean connectionBound = TransactionSynchronizationManager.hasResource(dataSource);
        try {
            return new SendTimeObservation(Thread.currentThread(), actualTransactionActive, synchronizationActive,
                    connectionBound, committedState(quote), null);
        } catch (RuntimeException ex) {
            return new SendTimeObservation(Thread.currentThread(), actualTransactionActive, synchronizationActive,
                    connectionBound, null, ex);
        }
    }

    private Map<String, Object> storedFileRow(Long storedFileId) {
        return jdbcTemplate.queryForMap(
                "SELECT file_name, storage_path, mime_type, file_size FROM stored_file WHERE stored_file_id = ?",
                storedFileId);
    }

    private String storeEmail() {
        String email = jdbcTemplate.queryForObject(
                "SELECT email FROM store WHERE store_id = ? AND business_id = ?",
                String.class, STORE_SYD_CBD, BUSINESS_AUSSIE);
        Assertions.assertNotNull(email, "store 1 must have an email (V4 seed) for the notification to be delivered");
        Assertions.assertFalse(email.isBlank(), "store 1 email must not be blank");
        return email.trim();
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    // ================================================================
    // Seeding (committed for real: auto-commit JDBC + real API calls)
    // ================================================================

    /**
     * A committed, ISSUED, ready-to-accept quote: self-seeded LEAD order (+ customer + billing
     * address), an itemised draft saved and issued by email through the real protected API (the issued
     * PDF is stored on disk), the plaintext token taken from the recorded link-only email, and a
     * non-NULL sentinel order price. Preconditions asserted on committed state.
     */
    private IssuedQuote issueReadyQuote() throws Exception {
        SeededOrder order = seedOrder();
        long orderId = order.orderId();
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quote', 'Tester', ?, ?)",
                orderId, CUSTOMER_EMAIL, CUSTOMER_MOBILE);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Test Street', 'Sydney', 'NSW', '2000')",
                orderId);

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        List<QuoteEmailRequest> emails = quoteEmailSender.sentEmails();
        Assertions.assertEquals(1, emails.size(), "the issue must have recorded exactly one quote email");
        String token = extractToken(emails.get(0).bodyText());

        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT quote_version_id, version_number, quote_total_ex_gst, quote_total_inc_gst, "
                        + "customer_name_snapshot FROM quote_version WHERE order_id = ? AND status = 'ISSUED'",
                orderId);
        long versionId = ((Number) version.get("quote_version_id")).longValue();
        Assertions.assertEquals(1, ((Number) version.get("version_number")).intValue(), "first issue is v1");
        assertMoney(QUOTE_TOTAL_EX_GST, version.get("quote_total_ex_gst"), "issued quote_total_ex_gst");
        assertMoney(QUOTE_TOTAL_INC_GST, version.get("quote_total_inc_gst"), "issued quote_total_inc_gst");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, version.get("customer_name_snapshot"),
                "the V17 snapshot is the name acceptance will freeze");

        String issuedPdfPath = jdbcTemplate.queryForObject(
                "SELECT sf.storage_path FROM quote_version v "
                        + "JOIN stored_file sf ON sf.stored_file_id = v.issued_pdf_file_id "
                        + "WHERE v.quote_version_id = ?",
                String.class, versionId);
        Assertions.assertNotNull(issuedPdfPath, "the issued PDF must be stored");
        byte[] issuedPdfBytes = Files.readAllBytes(physicalPath(issuedPdfPath));
        Assertions.assertTrue(startsWithPdfMagic(issuedPdfBytes), "the stored issued PDF must be a PDF");

        // A real (non-NULL) committed order price so failure paths are checked against actual values.
        jdbcTemplate.update(
                "UPDATE sales_order SET price_adjustment_inc_gst = ?, sale_price_ex_gst = ?, total_cost = 0.00, "
                        + "gp = ?, gp_percent = 100.00, updated_at = CAST(? AS TIMESTAMP) WHERE order_id = ?",
                new BigDecimal(SENTINEL_PRICE_ADJUSTMENT), new BigDecimal(SENTINEL_SALE_PRICE_EX_GST),
                new BigDecimal(SENTINEL_SALE_PRICE_EX_GST), SENTINEL_UPDATED_AT, orderId);

        IssuedQuote quote = new IssuedQuote(orderId, order.orderNumber(), versionId, token, sha256Hex(token),
                issuedPdfPath, issuedPdfBytes);

        CommittedState state = committedState(quote);
        Assertions.assertEquals("ISSUED", state.versionStatus(), "precondition: version ISSUED");
        Assertions.assertEquals("ACTIVE", state.tokenStatus(), "precondition: token ACTIVE");
        Assertions.assertEquals(1L, state.storedFileRows(), "precondition: only the issued PDF row");
        Assertions.assertEquals(Set.of(fileName(issuedPdfPath)), state.filesOnDisk(),
                "precondition: only the issued PDF on disk");
        assertMoney(SENTINEL_PRICE_ADJUSTMENT, state.priceAdjustmentIncGst(), "precondition: sentinel price");
        assertNoNotificationAttempted();
        return quote;
    }

    /** The OrderCreateRepository allocation: per-business advisory lock, MAX + 1, insert — one commit. */
    private SeededOrder seedOrder() {
        SeededOrder seeded = new TransactionTemplate(transactionManager).execute(txStatus -> {
            jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", (rs, rowNum) -> Boolean.TRUE,
                    BUSINESS_AUSSIE);
            Integer next = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(order_sequence_number), 0) + 1 FROM sales_order WHERE business_id = ?",
                    Integer.class, BUSINESS_AUSSIE);
            int sequence = next == null ? 1 : next;
            String orderNumber = ORDER_NUMBER_PREFIX + String.format("%05d", sequence % 100_000);
            Long orderId = jdbcTemplate.queryForObject(
                    "INSERT INTO sales_order "
                            + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                            + " flooring_type, order_status, week_number, week_year) "
                            + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 1, 2026) "
                            + "RETURNING order_id",
                    Long.class, BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, sequence, orderNumber);
            Assertions.assertNotNull(orderId, "order insert must return an id");
            return new SeededOrder(orderId, orderNumber);
        });
        Assertions.assertNotNull(seeded, "order seed transaction must return the order");
        return seeded;
    }

    /**
     * Record every storage write made from now on (the path returned by the REAL store), optionally
     * failing the signed-PDF ("pdf") write. Installed only AFTER the issue so the issued PDF's own
     * store is never intercepted.
     */
    private StorageWrites recordStorageWrites(boolean failSignedPdfWrite) {
        StorageWrites writes = new StorageWrites();
        doAnswer(invocation -> {
            String extension = invocation.getArgument(3);
            writes.attempted.add(extension);
            if (failSignedPdfWrite && "pdf".equals(extension)) {
                throw new UncheckedIOException("Failed to store attachment file",
                        new IOException("injected disk failure while writing the signed quote PDF"));
            }
            String storagePath = (String) invocation.callRealMethod();
            writes.written.add(storagePath);
            return storagePath;
        }).when(fileStorageService).store(any(byte[].class), anyLong(), anyLong(), anyString());
        return writes;
    }

    /**
     * A deferred constraint trigger that raises at COMMIT for this order's ISSUED -> ACCEPTED update
     * only (the WHEN clause is evaluated at UPDATE time; the function runs at commit).
     */
    private void installCommitFailureTrigger(String trigger, long orderId) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION ntxcmt_fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'ntxcmt injected commit-time failure' USING ERRCODE = 'P0001';
                END;
                $$
                """);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON quote_version");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger
                + " AFTER UPDATE ON quote_version"
                + " DEFERRABLE INITIALLY DEFERRED"
                + " FOR EACH ROW"
                + " WHEN (NEW.order_id = " + orderId + " AND NEW.status = 'ACCEPTED')"
                + " EXECUTE FUNCTION " + TRIGGER_FUNCTION + "()");
    }

    // ================================================================
    // Requests / URLs / token
    // ================================================================

    /** The public accept: multipart with ONE signature PNG part, NO session (the token is the credential). */
    private ResultActions acceptQuote(String token) throws Exception {
        return mockMvc.perform(multipart(publicUrl(token) + "/accept")
                .file(new MockMultipartFile("signature", "signature.png", "image/png", SIGNATURE_PNG)));
    }

    private MockHttpSession liamStore1Session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", USER_LIAM);
        session.setAttribute("business_id", BUSINESS_AUSSIE);
        session.setAttribute("store_id", STORE_SYD_CBD);
        return session;
    }

    private static String publicUrl(String token) {
        return "/api/v1/public/quotes/" + token;
    }

    private static String draftUrl(long orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/draft";
    }

    private static String sendEmailUrl(long orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/send-email";
    }

    /** The plaintext token from the link-only quote email; the link must appear exactly once. */
    private static String extractToken(String body) {
        Matcher matcher = PUBLIC_LINK_PATTERN.matcher(body);
        Assertions.assertTrue(matcher.find(), "the quote email must carry the public /q/{token} link: " + body);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "the public link must appear exactly once in the email body");
        return token;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean startsWithPdfMagic(byte[] bytes) {
        return bytes.length >= 5
                && "%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII));
    }

    // ================================================================
    // Storage paths (physical file = base dir + storage_path)
    // ================================================================

    private Path storageBase() {
        // Same resolution as FileStorageService (relative to the JVM working directory).
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
    }

    private Path physicalPath(String storagePath) {
        Path resolved = resolveInsideStorage(storagePath);
        Assertions.assertNotNull(resolved, "storage path escapes the test storage dir: " + storagePath);
        return resolved;
    }

    /** {@code base + storage_path}, or null when it would escape the test storage dir. */
    private Path resolveInsideStorage(String storagePath) {
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        Path base = storageBase();
        Path resolved = base.resolve(relative).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    private static String orderStoragePrefix(long orderId) {
        return "/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/";
    }

    private Path orderStorageDir(long orderId) {
        return storageBase().resolve("uploads").resolve(Long.toString(BUSINESS_AUSSIE))
                .resolve("orders").resolve(Long.toString(orderId));
    }

    /** File names currently in the order's storage dir (empty when the dir does not exist). */
    private Set<String> filesOnDisk(long orderId) {
        Path dir = orderStorageDir(orderId);
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

    private static String fileName(String storagePath) {
        return storagePath.substring(storagePath.lastIndexOf('/') + 1);
    }

    // ================================================================
    // Cleanup (runs in @BeforeEach AND @AfterEach; every step runs even if an earlier one fails)
    // ================================================================

    private void cleanUpEverything() {
        runAll(
                () -> Mockito.reset(fileStorageService, quotePdfGenerator, salesOrderFinancialWriteRepository,
                        notificationSender),
                notificationSender::reset,
                quoteEmailSender::reset,
                this::dropCommitFailureTriggers,
                this::sweepTestOrders);
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

    /** Drop every leftover commit-failure trigger of this class (any order id), then its function. */
    private void dropCommitFailureTriggers() {
        List<String> triggers = jdbcTemplate.queryForList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'quote_version'::regclass AND tgname LIKE ?",
                String.class, TRIGGER_PREFIX.replace("_", "\\_") + "%");
        for (String trigger : triggers) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON quote_version");
        }
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + TRIGGER_FUNCTION + "()");
    }

    /** Delete every committed row of every NTXCMT.* order (FK-safe), its stored_file rows and its files. */
    private void sweepTestOrders() {
        List<Long> orderIds = jdbcTemplate.queryForList(
                "SELECT order_id FROM sales_order WHERE business_id = ? AND order_number LIKE ?",
                Long.class, BUSINESS_AUSSIE, ORDER_SWEEP_PATTERN);
        for (Long orderId : orderIds) {
            deleteOrderAndFiles(orderId);
        }
    }

    private void deleteOrderAndFiles(long orderId) {
        // Collect the order's files first (every stored_file reference + anything under its storage dir).
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
                orderId, orderId, orderId, orderId, orderId, orderId, "/uploads/%/orders/" + orderId + "/%");
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
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
                    jdbcTemplate.update("DELETE FROM stored_file WHERE stored_file_id = ?",
                            file.get("stored_file_id"));
                }
            });
        } finally {
            for (Map<String, Object> file : files) {
                deleteStoredFileQuietly((String) file.get("storage_path"));
            }
            // Also catches orphans whose rows never committed (exactly what the failure tests assert against).
            deleteDirectoryQuietly(orderStorageDir(orderId));
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
            // Best-effort cleanup; the directory sweep below retries.
        }
    }

    private void deleteDirectoryQuietly(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!normalized.startsWith(storageBase()) || normalized.equals(storageBase()) || !Files.isDirectory(normalized)) {
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
    // Records
    // ================================================================

    private record SeededOrder(long orderId, String orderNumber) {
    }

    /** A committed, ISSUED quote ready to accept (plus what the failure tests compare against). */
    private record IssuedQuote(long orderId, String orderNumber, long versionId, String token, String tokenHash,
                               String issuedPdfPath, byte[] issuedPdfBytes) {
    }

    /** Everything an acceptance can write, as COMMITTED (record equality = "unchanged"). */
    private record CommittedState(
            String versionStatus,
            Timestamp acceptedAt,
            String acceptedCustomerName,
            Long acceptedSignatureFileId,
            Long signedPdfFileId,
            long versionRows,
            String tokenStatus,
            Timestamp tokenDeadAt,
            long tokenRows,
            BigDecimal priceAdjustmentIncGst,
            BigDecimal salePriceExGst,
            BigDecimal totalCost,
            BigDecimal gp,
            BigDecimal gpPercent,
            Timestamp orderUpdatedAt,
            long storedFileRows,
            Set<String> filesOnDisk) {
    }

    /** What the notification sender observed at send time (thread, transaction context, committed DB). */
    private record SendTimeObservation(
            Thread thread,
            boolean actualTransactionActive,
            boolean synchronizationActive,
            boolean connectionBound,
            CommittedState committed,
            Throwable probeFailure) {
    }

    private record AcceptedFiles(String signaturePath, String signedPdfPath) {
    }

    /** Storage writes observed through the FileStorageService spy (single-threaded test). */
    private static final class StorageWrites {
        private final List<String> attempted = new CopyOnWriteArrayList<>();
        private final List<String> written = new CopyOnWriteArrayList<>();

        List<String> attemptedExtensions() {
            return List.copyOf(attempted);
        }

        List<String> writtenPaths() {
            return List.copyOf(written);
        }
    }
}
