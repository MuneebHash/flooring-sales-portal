package com.flooring.salesportal.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16G PR 3 (16G contract sections 5.4, 6.1, 6.2 and 11; the PR 3 row of section 16) - D.8
 * {@code POST /api/v1/{slug}/orders/{orderId}/invoices/current/accept} at the REAL commit boundary: what is
 * durable when something fails AFTER the signing transaction committed (the post-commit email and its
 * success stamp), and what is left behind when something fails BEFORE it (render, storage, insert,
 * commit).
 *
 * <p><b>Why a separate, non-transactional class.</b> Inside a {@code @Transactional} test D.8's
 * {@code TransactionTemplate} joins the test transaction, so nothing commits: "the email is sent strictly
 * after the commit", "a post-commit failure leaves the acceptance and the D5(c) link cancellation
 * committed", "the stamp transaction rolls back as a whole" and "a commit-time failure deletes the
 * request's files through the afterCompletion hooks" cannot be observed there. This class has NO
 * {@code @Transactional} anywhere: every seed statement auto-commits, every MockMvc call commits for real,
 * and every assertion reads COMMITTED state through pooled auto-commit connections plus the files on disk.
 *
 * <p><b>Rules under test.</b> Section 6.2: after commit, a delivery failure, any other sender failure and a
 * failure of the stamp transaction (before its first write, after a write, or at its commit) are non-fatal
 * - 201, the acceptance and the D5(c) quote-link cancellation stay committed, the message reports the
 * DELIVERY outcome only, both markers ({@code invoice.last_emailed_at} of the appended version and the
 * {@code sales_order.last_emailed_at} mirror) are null unless the stamp committed, and each failure logs
 * one WARN with the order id and version only (no recipient, no throwable). Before commit, any failure
 * gives the generic 500, commits nothing (no invoice / stored_file row, no D5(c) change, the mirror
 * untouched), deletes exactly the request's own signature and PDF files, leaves every earlier file
 * byte-identical and sends no email; a retry without the fault then signs normally. Section 6.1 /
 * 5.4 step 12: the email carries the appended version's own PDF (byte-identical to its stored file) to
 * the TRIMMED saved customer email that the signing transaction read and passed through the D.8 email
 * gate; an email saved after the commit (valid or app-invalid) is never used, re-read or re-validated.
 *
 * <p><b>Fault injection</b> (no production hooks) - the four {@link MockitoSpyBean} spies below, real
 * behaviour unless stubbed (doX().when(spy) form only), stubbed only after the fixture is committed:
 * {@link InvoicePdfGenerator} (the signed-PDF render), {@link FileStorageService} (the signature write,
 * then the signed-PDF write), {@link InvoiceRepository} (the invoice insert and the stamp's two writes)
 * and {@link RecordingInvoiceEmailSender} (the post-commit send). At COMMIT, a {@code DEFERRABLE
 * INITIALLY DEFERRED} constraint trigger scoped to the test order: {@code AFTER INSERT ON invoice} (the
 * signing transaction fails at its commit) or {@code AFTER UPDATE ON invoice} when {@code last_emailed_at}
 * becomes non-null (the stamp transaction fails at its commit). Triggers are created in auto-commit right
 * before the request and dropped in {@code finally} and by the sweep. AFTER COMMIT (section C), an
 * {@code afterCommit} synchronization registered on the signing transaction from the {@link InvoiceRepository}
 * spy's mirror-reset call commits a NEW saved customer email on a raw auto-commit connection, before the
 * post-commit send. The spy set (types and field names) is identical to the Path A commit class, so both
 * share one cached Spring context (the autowired {@link DataSource} is not a bean override).
 *
 * <p><b>Fixtures - fully self-seeded and COMMITTED.</b> Each test seeds its own tenant: a business with the
 * class-unique slug {@value #SLUG}, one store, one salesperson and its store access. Each order is
 * invoice-ready (customer with a PADDED valid email, INSTALLATION + BILLING, details of sale, lay date
 * 2026-12-01 CONFIRMED, one 100.00 ex / 40.00 cost charge line), gets an unsigned invoice v1 through the
 * real D.1 endpoint (then committed-dated {@value #V1_INVOICE_DATE_TEXT}, so the copied invoice date of
 * the signing version is visible), and an ISSUED quote with an ACTIVE token through the real protected
 * draft PUT and send-email (the plaintext token is read from the recorded link-only quote email). A
 * non-null mirror sentinel is committed last. No V4 business, store, user or order is read or written.
 *
 * <p><b>Sweep.</b> {@code @BeforeEach} and {@code @AfterEach} remove everything the class commits, in
 * FK-safe order: per order quote_token, invoice, quote_version, quote_draft, payment_transaction, the other
 * order children, sales_order and the order's stored_file rows (plus its files and directory); then the
 * tenant's store_charge / store_product, user_store_access, app_user, store, business_quick_description and
 * business rows, the tenant's storage directory, and any leftover {@code iacit_} trigger and its function.
 * The four spies and the three singleton recording senders are reset in both. Single-threaded.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class InvoiceAcceptanceCommitIntegrationTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before anything is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant: the slug is the sweep's only key ----
    private static final String SLUG = "iacit-d8-commit";
    private static final String BUSINESS_NAME = "Ironbark Acceptance Flooring";
    private static final String STORE_CODE = "IACIT";
    private static final String SALESPERSON_CODE = "IC1";
    private static final String ORDER_NUMBER_PREFIX = STORE_CODE + "." + SALESPERSON_CODE + ".";
    private static final String CHARGE_CODE_PREFIX = "IACITC";

    // ---- Commit-time failure injection (class-specific names: no collision with any other class) ----
    private static final String TRIGGER_FUNCTION = "iacit_fail_at_commit";
    private static final String TRIGGER_PREFIX = "iacit_fail_";
    private static final String INSERT_TRIGGER_PREFIX = TRIGGER_PREFIX + "insert_";
    private static final String STAMP_TRIGGER_PREFIX = TRIGGER_PREFIX + "stamp_";

    // Children of sales_order deleted after quote_token / invoice / quote_version / quote_draft /
    // payment_transaction (every child FK is RESTRICT; the two *_line quote tables CASCADE from their parent).
    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "order_attachment", "order_note", "order_product_line", "order_charge_line",
            "order_address", "order_customer", "order_enquiry");

    // ---- The seeded customer ----
    private static final String CUSTOMER_FIRST_NAME = "Harper";
    private static final String CUSTOMER_LAST_NAME = "Ironbark";
    /** The D.1 freeze of the saved name: v1's customer_name_snapshot, i.e. the D.8 accepted name. */
    private static final String FROZEN_CUSTOMER_NAME = "Harper Ironbark";
    private static final String CUSTOMER_EMAIL = "harper.ironbark@example.com";
    /** Saved with edge whitespace (DB-valid, app-valid once trimmed): the recipient must be the trimmed value. */
    private static final String SAVED_CUSTOMER_EMAIL = "  " + CUSTOMER_EMAIL + "  ";
    /** Committed as the saved email AFTER the signing commit (section C): app-valid, so a re-read would send to it. */
    private static final String MOVED_AFTER_COMMIT_EMAIL = "moved.after.commit@example.com";
    /**
     * Committed as the saved email AFTER the signing commit (section C): DB-valid (passes
     * {@code chk_order_customer_email_format}) but app-invalid (no interior dot in the domain), so a post-commit
     * re-validation would skip or fail the send.
     */
    private static final String APP_INVALID_AFTER_COMMIT_EMAIL = "jordan@localhost";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and install carpet (D.8 commit boundary test)";

    // ---- Dates ----
    private static final String V1_INVOICE_DATE_TEXT = "2026-09-15";
    /** v1 is committed-dated on an earlier day: D.8 must COPY it (16G contract 5.4 step 11), never use today. */
    private static final LocalDate V1_INVOICE_DATE = LocalDate.parse(V1_INVOICE_DATE_TEXT);
    private static final String V1_INVOICE_DATE_DISPLAY = "15/09/2026";
    /** proposed_lay_date 2026-12-01 minus 2 days. */
    private static final LocalDate EXPECTED_DUE_DATE = LocalDate.of(2026, 11, 29);
    private static final String DUE_DATE_DISPLAY = "29/11/2026";
    /** A NON-NULL committed mirror: a failed signing must leave exactly this value behind. */
    private static final Timestamp SENTINEL_LAST_EMAILED_AT = Timestamp.valueOf("2026-02-03 04:05:06");

    // ---- Messages ----
    private static final String ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    /** Note the em dash (U+2014). */
    private static final String ACCEPT_EMAIL_FAILED_MESSAGE =
            "Invoice accepted. The invoice could not be emailed — use Re-send Invoice to try again.";
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    /** The generic 500 envelope: no data, no details, no exception or database text. */
    private static final String GENERIC_500_BODY =
            "{\"error\":{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"An unexpected error occurred.\"}}";
    private static final String FOOTER_TEXT = "Generated by the Flooring Sales Portal";

    // ---- The D.8 multipart contract (16G contract 5.4) ----
    private static final String SIGNATURE_PART = "signature";
    private static final String EXPECTED_VERSION_FIELD = "expected_version_number";

    // The real public link in the link-only quote email: <app.public-base-url>/q/{43-char token}.
    private static final Pattern PUBLIC_LINK_PATTERN =
            Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    // The invoice PDF acceptance caption format ("Accepted by {name} on {dd/MM/yyyy HH:mm}").
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    // A real, decodable 1x1 PNG: D.8 embeds the uploaded bytes into the signed PDF.
    private static final byte[] SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Raw pooled connections (auto-commit), for the afterCommit email move only - never the thread's transaction. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender notificationSender;

    // Spies call the REAL methods unless a test stubs them (doX().when(spy) form only). The InvoiceRepository
    // field is the @Repository CGLIB proxy around the spy; Spring's SpringMockResolver lets doX().when(..),
    // verify(..), clearInvocations(..) and reset(..) resolve through it. Same four types and field names as
    // the Path A commit class (one shared cached context).
    @MockitoSpyBean
    private InvoicePdfGenerator invoicePdfGenerator;

    @MockitoSpyBean
    private FileStorageService fileStorageService;

    @MockitoSpyBean
    private InvoiceRepository invoiceRepository;

    @MockitoSpyBean
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    /** This test's committed tenant (seeded by {@link #setUp} after the sweep). */
    private Tenant tenant;

    private int orderSequence;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes files recursively - never let it run against a real storage dir.
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class writes and sweeps files only under the shared test storage dir");
        Assertions.assertTrue(storageBase().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                "this class may only write under its own test storage dir, but was " + storageBase());
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        cleanUpEverything();
        tenant = seedTenant();
    }

    @AfterEach
    void tearDown() {
        cleanUpEverything();
    }

    // ================================================================
    // A. After commit: the signing stands, whatever the email and its stamp do
    // ================================================================

    /**
     * Baseline: the delivery succeeds and the stamp commits. 201 with the emailed message; the appended
     * signed version, its signature and signed-PDF stored_file rows and both files, and the D5(c)
     * cancellation of the ISSUED quote version and its ACTIVE token are all committed; the email was
     * attempted exactly once, strictly after the commit (no transaction active, the signed row and the dead
     * link already visible to another connection), to the TRIMMED saved email, with the appended version's
     * own PDF byte-identical to its stored file; both markers carry the SAME non-null timestamp; no WARN.
     */
    @Test
    void accept_emailDelivered_returns201Emailed_signingAndQuoteLinkCancelCommitted_markersStampedEqual_emailCarriesTheStoredSignedPdf()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(false);

        AcceptAttempt attempt = performAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt, EmailOutcome.DELIVERED);
    }

    /**
     * The transport fails ({@code failNextSend}: {@code InvoiceEmailException}). 201 with the not-emailed
     * message; the signing and the D5(c) cancellation stay committed; both markers null (the persist's
     * mirror reset committed, the sentinel is gone, no stamp was attempted); the failed attempt carried the
     * stored signed PDF; one WARN "Signed invoice email failed ..." without a throwable or the address.
     */
    @Test
    void accept_deliveryFailure_returns201NotEmailed_signingAndQuoteLinkCancelStayCommitted_markersNull_warnsWithoutThrowable()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(false);
        invoiceEmailSender.failNextSend();

        AcceptAttempt attempt = performAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt, EmailOutcome.DELIVERY_FAILED);
    }

    /**
     * The sender throws a DIFFERENT runtime exception ({@link IllegalStateException}, not the transport's
     * {@code InvoiceEmailException}). Before PR 3 this escaped as a 500 after commit; now it is non-fatal
     * exactly like a delivery failure: 201 with the not-emailed message, the signing and the D5(c)
     * cancellation committed, both markers null, nothing recorded as sent, one "failed" WARN.
     */
    @Test
    void accept_senderThrowsOtherRuntimeException_returns201NotEmailed_signingAndQuoteLinkCancelStayCommitted_markersNull_warnsWithoutThrowable()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(true);

        AcceptAttempt attempt = performAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt, EmailOutcome.SENDER_FAILED);
    }

    /**
     * The delivery succeeds, then the stamp transaction fails BEFORE any write (its first write,
     * {@code updateInvoiceLastEmailedAt}, throws). 201 with the EMAILED message (the message reports the
     * delivery), both markers null, the response built from the committed row; the stamp never reached the
     * mirror write; one WARN "Email-success stamp failed ..." without a throwable.
     */
    @Test
    void accept_stampFailsBeforeAnyWrite_returns201Emailed_signingCommitted_markersNull_warnsWithoutThrowable()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(false);
        doThrow(new DataAccessResourceFailureException("injected failure on the stamp's invoice last_emailed_at write"))
                .when(invoiceRepository).updateInvoiceLastEmailedAt(anyLong(), any());

        AcceptAttempt attempt = performAccept(fixture);

        long signedInvoiceId = assertCommittedSigning(fixture, before, writes, sends, attempt, EmailOutcome.STAMP_FAILED);
        // The failure point was really reached (the stamp's first write, for the appended version), and the
        // stamp stopped there: its mirror write never ran.
        verify(invoiceRepository).updateInvoiceLastEmailedAt(eq(signedInvoiceId), notNull());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), notNull());
    }

    /**
     * The delivery succeeds; inside the stamp transaction the invoice stamp IS written (read back on the
     * stamp's own connection: non-null, equal to the value handed to the mirror), then the mirror write
     * (stubbed to fail only for a non-null timestamp, so the persist's null reset stays real) throws. The
     * WHOLE stamp rolls back: both committed markers are null, although the invoice stamp had been written.
     * 201 with the emailed message and one stamp WARN.
     */
    @Test
    void accept_stampMirrorWriteFailsAfterTheInvoiceStampWasWritten_returns201Emailed_wholeStampRolledBack_markersNull()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(false);
        int signedVersion = fixture.currentVersion() + 1;
        List<LocalDateTime> mirrorStampValues = new CopyOnWriteArrayList<>();
        List<Timestamp> inFlightInvoiceStamps = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            mirrorStampValues.add(invocation.getArgument(1));
            // Same thread, so the SAME (stamp) transaction connection: the uncommitted invoice stamp is visible.
            inFlightInvoiceStamps.add(jdbcTemplate.queryForObject(
                    "SELECT last_emailed_at FROM invoice WHERE order_id = ? AND version_number = ?",
                    Timestamp.class, fixture.orderId(), signedVersion));
            throw new DataAccessResourceFailureException("injected failure on the stamp's sales_order mirror write");
        }).when(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), notNull());

        AcceptAttempt attempt = performAccept(fixture);

        long signedInvoiceId = assertCommittedSigning(fixture, before, writes, sends, attempt, EmailOutcome.STAMP_FAILED);
        verify(invoiceRepository).updateInvoiceLastEmailedAt(eq(signedInvoiceId), notNull());
        Assertions.assertEquals(1, mirrorStampValues.size(), "the stamp reached its mirror write exactly once");
        Assertions.assertNotNull(mirrorStampValues.get(0), "the mirror write carried the re-read stamp");
        Assertions.assertEquals(1, inFlightInvoiceStamps.size());
        Assertions.assertNotNull(inFlightInvoiceStamps.get(0),
                "the invoice stamp was written inside the stamp transaction before the mirror write failed");
        Assertions.assertEquals(Timestamp.valueOf(mirrorStampValues.get(0)), inFlightInvoiceStamps.get(0),
                "the mirror write carried exactly the in-flight invoice stamp (same timestamp on both columns)");
        // ...and yet nothing of the stamp is committed (assertCommittedSigning: both markers null).
    }

    /**
     * The delivery succeeds and every stamp write succeeds in flight (the invoice stamp and the mirror both
     * read back non-null and equal on the stamp's connection), then the stamp transaction fails AT COMMIT (a
     * deferred constraint trigger {@code AFTER UPDATE ON invoice} when {@code last_emailed_at} becomes
     * non-null). The whole stamp rolls back: both committed markers null; 201 with the emailed message; one
     * stamp WARN; the signing (committed by its own, earlier transaction) and the D5(c) cancellation stand.
     */
    @Test
    void accept_stampFailsAtCommit_deferredInvoiceUpdateTrigger_returns201Emailed_wholeStampRolledBack_markersNull()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(false);
        int signedVersion = fixture.currentVersion() + 1;
        List<LocalDateTime> mirrorStampValues = new CopyOnWriteArrayList<>();
        List<Timestamp> inFlightInvoiceStamps = new CopyOnWriteArrayList<>();
        List<Timestamp> inFlightMirrorStamps = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            mirrorStampValues.add(invocation.getArgument(1));
            inFlightInvoiceStamps.add(jdbcTemplate.queryForObject(
                    "SELECT last_emailed_at FROM invoice WHERE order_id = ? AND version_number = ?",
                    Timestamp.class, fixture.orderId(), signedVersion));
            inFlightMirrorStamps.add(jdbcTemplate.queryForObject(
                    "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, fixture.orderId()));
            return null;
        }).when(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), notNull());
        String trigger = STAMP_TRIGGER_PREFIX + fixture.orderId();
        AcceptAttempt attempt;
        try {
            installStampCommitFailureTrigger(trigger, fixture.orderId());
            Assertions.assertEquals(1L, count(
                            "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice'::regclass AND tgname = ?", trigger),
                    "the stamp commit-failure trigger must be installed before the request");
            attempt = performAccept(fixture);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }

        long signedInvoiceId = assertCommittedSigning(fixture, before, writes, sends, attempt, EmailOutcome.STAMP_FAILED);
        // Every stamp write ran and succeeded in flight, so the failure was AT COMMIT.
        verify(invoiceRepository).updateInvoiceLastEmailedAt(eq(signedInvoiceId), notNull());
        Assertions.assertEquals(1, mirrorStampValues.size(), "the stamp's mirror write ran exactly once");
        Assertions.assertNotNull(inFlightInvoiceStamps.get(0), "the invoice stamp was written in flight");
        Assertions.assertEquals(Timestamp.valueOf(mirrorStampValues.get(0)), inFlightInvoiceStamps.get(0),
                "in flight, the mirror value is the invoice stamp");
        Assertions.assertEquals(inFlightInvoiceStamps.get(0), inFlightMirrorStamps.get(0),
                "in flight, both columns carried the same stamp before the commit failed");
    }

    // ================================================================
    // B. Before commit: nothing is committed, the request's files are removed, no email
    // ================================================================

    /**
     * The signed-PDF render throws. It runs after the signature file was written and its stored_file row
     * inserted, so: 500 with the generic body; nothing committed (no invoice v2, no stored_file row, the
     * mirror sentinel kept, the ISSUED quote and its ACTIVE token untouched - D5(c) never ran); the written
     * signature file removed by BOTH cleanups (the in-method catch and the afterCompletion hook); no PDF
     * write attempted; v1's PDF and the issued quote PDF byte-identical; no email attempted, no stamp, no
     * WARN. A retry without the fault signs, commits and emails.
     */
    @Test
    void accept_renderFailure_returns500_commitsNothing_writtenSignatureDeleted_quoteLinkStaysActive_noEmail_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        doThrow(new UncheckedIOException("Failed to generate invoice PDF",
                new IOException("injected signed invoice PDF render failure")))
                .when(invoicePdfGenerator).render(any());

        AcceptAttempt attempt = performAccept(fixture);

        assertGeneric500(attempt.result(), "D.8 with a failing signed-PDF render");
        verify(invoicePdfGenerator).render(any());
        Assertions.assertEquals(List.of("png"), writes.attemptedExtensions(),
                "only the signature file is written before the render; the PDF write is never attempted");
        Assertions.assertEquals(1, writes.writtenPaths().size(), "the signature file was written before the render");
        String signaturePath = writes.writtenPaths().get(0);
        Assertions.assertEquals(1, insertedStoredFileIds.size(), "the signature stored_file row was inserted in-transaction");
        verify(invoiceRepository).insertStoredFile(eq(signatureFileName(fixture)), eq(signaturePath),
                eq("image/png"), eq((long) SIGNATURE_PNG.length));
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        // Both cleanups ran: the in-method catch AND the afterCompletion rollback hook.
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, null, attempt,
                "D.8 with a failing signed-PDF render");
        assertRetrySigns(fixture, before);
    }

    /**
     * The FIRST storage write - the signature file - throws. Nothing was written, so nothing needs deleting:
     * 500 with the generic body; nothing committed; no render, no stored_file insert, no invoice insert, no
     * cleanup delete at all; no email. A retry without the fault signs, commits and emails.
     */
    @Test
    void accept_signatureStorageFailure_returns500_commitsNothing_writesNoFile_quoteLinkStaysActive_noEmail_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites("png");
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();

        AcceptAttempt attempt = performAccept(fixture);

        assertGeneric500(attempt.result(), "D.8 with a failing signature file write");
        Assertions.assertEquals(List.of("png"), writes.attemptedExtensions(),
                "the signature file write is the first storage call, and the last one attempted");
        Assertions.assertEquals(List.of(), writes.writtenPaths(), "the failed write must leave no file");
        verify(invoicePdfGenerator, never()).render(any());
        verify(invoiceRepository, never()).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        verify(fileStorageService, never()).deleteQuietly(any());
        Assertions.assertEquals(List.of(), insertedStoredFileIds);

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, null, attempt,
                "D.8 with a failing signature file write");
        assertRetrySigns(fixture, before);
    }

    /**
     * The SECOND storage write - the signed PDF - throws, after the signature file was written, its row
     * inserted and the PDF rendered. 500 with the generic body; nothing committed; the already-written
     * signature file is deleted (both cleanups) and is gone from disk; no invoice insert; no email. A retry
     * without the fault signs, commits and emails.
     */
    @Test
    void accept_signedPdfStorageFailure_returns500_commitsNothing_writtenSignatureDeleted_quoteLinkStaysActive_noEmail_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites("pdf");
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();

        AcceptAttempt attempt = performAccept(fixture);

        assertGeneric500(attempt.result(), "D.8 with a failing signed-PDF write");
        verify(invoicePdfGenerator).render(any());
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions(),
                "the signature write came first, the failing signed-PDF write second");
        Assertions.assertEquals(1, writes.writtenPaths().size(), "only the signature file was written");
        String signaturePath = writes.writtenPaths().get(0);
        Assertions.assertTrue(signaturePath.endsWith(".png"), "the written file is the signature: " + signaturePath);
        Assertions.assertEquals(1, insertedStoredFileIds.size(), "the signature stored_file row was inserted in-transaction");
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        // The signature file written BEFORE the failure is removed by both cleanups; nothing else is deleted.
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, null, attempt,
                "D.8 with a failing signed-PDF write");
        assertRetrySigns(fixture, before);
    }

    /**
     * Both files are written and both stored_file rows inserted, then the invoice insert (reached for the
     * signing version: version 2, v1's invoice date, the frozen name, a signature reference, no marker)
     * throws. 500; nothing committed; BOTH files deleted by both cleanups each; no mirror reset; no email. A
     * retry without the fault signs, commits and emails.
     */
    @Test
    void accept_invoiceInsertFailure_returns500_commitsNothing_bothWrittenFilesDeleted_quoteLinkStaysActive_noEmail_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        insertInvoiceFor(doThrow(new DataIntegrityViolationException("injected failure on the signed invoice insert"))
                .when(invoiceRepository), fixture.orderId());

        AcceptAttempt attempt = performAccept(fixture);

        assertGeneric500(attempt.result(), "D.8 with a failing invoice insert");
        // The insert was really reached, for the signing version.
        verify(invoiceRepository).insertInvoice(eq(fixture.orderId()), eq(fixture.currentVersion() + 1),
                eq(V1_INVOICE_DATE), eq(EXPECTED_DUE_DATE), any(), any(), any(), any(), any(),
                anyLong(), eq(tenant.userId()),
                notNull(), eq(FROZEN_CUSTOMER_NAME), notNull(), isNull(),
                isNull(), any(), eq(FROZEN_CUSTOMER_NAME), any(), any());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(2, writes.writtenPaths().size(), "both files were written before the insert");
        String signaturePath = writes.writtenPaths().get(0);
        String pdfPath = writes.writtenPaths().get(1);
        Assertions.assertEquals(2, insertedStoredFileIds.size(), "both stored_file rows were inserted in-transaction");
        // Both cleanups ran for BOTH files: the in-method catches AND the afterCompletion rollback hooks.
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(pdfPath);
        verify(fileStorageService, times(4)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, null, attempt,
                "D.8 with a failing invoice insert");
        assertRetrySigns(fixture, before);
    }

    /**
     * Every in-transaction step succeeds - both files and rows, the invoice insert, the mirror reset AND the
     * D5(c) cancellation (observed in flight, in the transaction's own beforeCommit: the ISSUED version and
     * its ACTIVE token already CANCELLED, the token's dead_at = the in-flight accepted_at) - and the
     * transaction fails AT COMMIT (a deferred constraint trigger {@code AFTER INSERT ON invoice}). 500 with
     * the generic body and no database detail; nothing committed, so the committed quote version is still
     * ISSUED and its token ACTIVE (the D5(c) cancellation rolled back with the acceptance); both files
     * removed by the afterCompletion hooks ALONE (exactly one delete each: the method body returned
     * normally); no email. A retry after the trigger is dropped signs, commits and emails.
     */
    @Test
    void accept_commitTimeFailure_deferredInvoiceInsertTrigger_returns500_quoteLinkCancelRolledBack_hooksDeleteBothFiles_noEmail_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        List<InFlightSigning> inFlight = probeSigningBeforeCommit(fixture);
        String trigger = INSERT_TRIGGER_PREFIX + fixture.orderId();
        AcceptAttempt attempt;
        try {
            installInsertCommitFailureTrigger(trigger, fixture.orderId());
            Assertions.assertEquals(1L, count(
                            "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice'::regclass AND tgname = ?", trigger),
                    "the insert commit-failure trigger must be installed before the request");
            attempt = performAccept(fixture);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }

        assertGeneric500(attempt.result(), "D.8 failing at commit");
        String body = attempt.result().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String leak : List.of("iacit", "P0001", "trigger", "commit", "constraint", "SQL")) {
            Assertions.assertFalse(body.contains(leak), "the 500 body must not leak database detail '" + leak + "': " + body);
        }

        // Every in-transaction write ran (so the failure was at COMMIT).
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(2, writes.writtenPaths().size(), "both files were written");
        String signaturePath = writes.writtenPaths().get(0);
        String pdfPath = writes.writtenPaths().get(1);
        Assertions.assertEquals(2, insertedStoredFileIds.size(), "both stored_file rows were inserted in-transaction");
        verify(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
        Assertions.assertEquals(1, inFlight.size(), "the signing transaction reached its commit exactly once");
        InFlightSigning probe = inFlight.get(0);
        Assertions.assertEquals(fixture.currentVersion() + 1, probe.invoiceVersion(), "the in-flight signing version");
        Assertions.assertNotNull(probe.invoiceAcceptedAt(), "in flight, the appended version was signed");
        Assertions.assertEquals("CANCELLED", probe.quoteVersionStatus(),
                "in flight, D5(c) had already cancelled the ISSUED quote version in this same transaction");
        Assertions.assertEquals("CANCELLED", probe.quoteTokenStatus(),
                "in flight, D5(c) had already killed the ACTIVE token in this same transaction");
        Assertions.assertEquals(probe.invoiceAcceptedAt(), probe.quoteTokenDeadAt(),
                "in flight, the token died at the signing time");
        Assertions.assertNull(probe.mirror(), "in flight, the mirror had been reset to the new version's null");

        // Only the afterCompletion rollback hooks can have removed the files: the method body completed normally.
        verify(fileStorageService, times(1)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(1)).deleteQuietly(pdfPath);
        verify(fileStorageService, times(2)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, probe.invoiceId(), attempt,
                "D.8 failing at commit");
        assertRetrySigns(fixture, before);
    }

    // ================================================================
    // C. The recipient is fixed by the signing transaction, never re-read after the commit
    // ================================================================

    /**
     * 16G contract 6.1 (recipient: the saved email "read inside the committing transaction") and 5.4 step 12.
     * The signing transaction reads the saved email, gates it and captures it trimmed. Then, from the persist's
     * mirror reset (its last write through the spies, after that read), an {@code afterCommit} hook on the same
     * transaction commits {@value #MOVED_AFTER_COMMIT_EMAIL} as the saved email on a raw auto-commit connection.
     * That runs inside the TransactionTemplate's commit, so it lands after the signing commit and before the
     * post-commit send: at send time the committed saved email is already the moved one. The hook runs exactly
     * once and moves exactly one row. The result is still 201 with the emailed message and exactly one delivered
     * email, addressed to the ORIGINAL trimmed {@code harper.ironbark@example.com} and carrying the stored signed
     * PDF byte for byte. Both markers are stamped alike, and the committed customer row differs from the seed in
     * its email only, which is the moved value.
     */
    @Test
    void accept_recipientIsTheTrimmedEmailTheSigningTransactionGated_notOneSavedAfterTheCommit_returns201Emailed_storedSignedPdf_markersStampedEqual()
            throws Exception {
        assertSigningTransactionFixesTheRecipient(MOVED_AFTER_COMMIT_EMAIL);
    }

    /**
     * The same commit-boundary hook, but the email saved after the commit is app-INVALID
     * ({@value #APP_INVALID_AFTER_COMMIT_EMAIL}: DB-valid, no interior dot in the domain). D.8's email gate ran
     * inside the signing transaction and passed, so contract 6.1 ("saved email missing or invalid at send time:
     * not reachable (gated)") holds. The send is never re-validated, skipped or failed: 201 with the emailed
     * message, exactly one delivered email to the ORIGINAL trimmed address carrying the stored signed PDF, both
     * markers stamped alike, no WARN, and the committed customer email is the app-invalid value.
     */
    @Test
    void accept_appInvalidEmailSavedAfterTheCommit_stillDeliveredToTheTrimmedEmailTheSigningTransactionGated_neverRevalidated_returns201Emailed()
            throws Exception {
        assertSigningTransactionFixesTheRecipient(APP_INVALID_AFTER_COMMIT_EMAIL);
    }

    /**
     * One committed, delivered and stamped D.8 whose saved customer email is moved to {@code emailSavedAfterCommit}
     * by an {@code afterCommit} hook on the signing transaction, before the post-commit send. Asserts that the
     * hook really ran once at that boundary, then the full committed signing (the customer row expected with
     * only its email moved), then the recipient, PDF bytes, markers and committed email, restated literally.
     */
    private void assertSigningTransactionFixesTheRecipient(String emailSavedAfterCommit) throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        Assertions.assertEquals(List.of(SAVED_CUSTOMER_EMAIL),
                before.customers().stream().map(customer -> customer.get("email")).toList(),
                "setup: the committed saved email is the padded original, and the order has exactly one customer");
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(false);
        SavedEmailMoves moves = moveSavedEmailAfterSigningCommit(fixture, emailSavedAfterCommit);

        AcceptAttempt attempt = performAccept(fixture);

        String label = "D.8 with the saved email moved to '" + emailSavedAfterCommit + "' after the signing commit";
        // ---- The hook ran exactly once, at the commit boundary: after the signing commit, before the send ----
        Assertions.assertEquals(List.of(), moves.failures(), label + ": the afterCommit email move itself must not fail");
        Assertions.assertEquals(List.of(true), moves.hookInsideSigningTransaction(),
                label + ": the hook was reached exactly once, at the persist's mirror reset, inside the signing transaction");
        Assertions.assertEquals(List.of(true), moves.autoCommit(),
                label + ": the move ran on a raw pooled connection in auto-commit (it commits on its own)");
        Assertions.assertEquals(List.of(1L), moves.signedVersionsCommittedAtMove(),
                label + ": afterCommit ran after the signing committed (its own connection already sees the signed version)");
        Assertions.assertEquals(List.of(SAVED_CUSTOMER_EMAIL), moves.committedEmailsBeforeMove(),
                label + ": the signing committed the saved email untouched (the padded original)");
        Assertions.assertEquals(List.of(1), moves.rowsMoved(),
                label + ": afterCommit ran exactly once and moved exactly the order's one customer row");
        Assertions.assertEquals(List.of(emailSavedAfterCommit), sends.committedCustomerEmails(),
                label + ": at send time the committed saved email was ALREADY the moved one, so a post-commit"
                        + " re-read would have found it");

        // ---- The full committed signing, delivered and stamped; the customer row differs in its email only ----
        List<Map<String, Object>> expectedCustomers =
                withRowChanged(before.customers(), "order_id", fixture.orderId(), "email", emailSavedAfterCommit);
        long signedInvoiceId = assertCommittedSigning(fixture, before, expectedCustomers, writes, sends, attempt,
                EmailOutcome.DELIVERED);

        // ---- Restated literally: the message, the one recipient, the PDF bytes, the markers, the moved email ----
        Assertions.assertEquals(ACCEPT_EMAILED_MESSAGE, readJson(attempt.result()).path("message").asText(),
                label + ": 201 with the emailed message");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), label + ": exactly one signed-invoice email delivered");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), label + ": no failed attempt");
        Assertions.assertEquals("harper.ironbark@example.com", sent.get(0).recipientEmail(),
                label + ": the recipient is the ORIGINAL saved email, trimmed, as the signing transaction read and gated it");
        Assertions.assertNotEquals(emailSavedAfterCommit, sent.get(0).recipientEmail(),
                label + ": the email saved after the commit is never the recipient");
        Map<String, Object> signedPdfRow = row("SELECT f.* FROM stored_file f JOIN invoice i "
                + "ON i.stored_file_id = f.stored_file_id WHERE i.invoice_id = ?", signedInvoiceId);
        Assertions.assertArrayEquals(Files.readAllBytes(physicalPath((String) signedPdfRow.get("storage_path"))),
                sent.get(0).pdfBytes(), label + ": the emailed PDF is the signed version's stored file, byte for byte");
        Map<String, Object> markers = row("SELECT i.last_emailed_at AS invoice_marker, o.last_emailed_at AS mirror_marker "
                + "FROM invoice i JOIN sales_order o ON o.order_id = i.order_id WHERE i.invoice_id = ?", signedInvoiceId);
        Assertions.assertNotNull(markers.get("invoice_marker"), label + ": the signed version's last_emailed_at is stamped");
        Assertions.assertEquals(markers.get("invoice_marker"), markers.get("mirror_marker"),
                label + ": both markers carry the same stamp");
        Assertions.assertEquals(emailSavedAfterCommit, jdbcTemplate.queryForObject(
                        "SELECT email FROM order_customer WHERE order_id = ?", String.class, fixture.orderId()),
                label + ": the committed saved customer email is the value moved after the commit");
    }

    // ================================================================
    // Shared assertions - a committed signing
    // ================================================================

    /** The post-commit outcome of one D.8 whose signing transaction committed. */
    private enum EmailOutcome {
        /** Delivered and stamped. */
        DELIVERED(true, true),
        /** The transport threw InvoiceEmailException (failNextSend). */
        DELIVERY_FAILED(false, false),
        /** The sender threw another runtime exception. */
        SENDER_FAILED(false, false),
        /** Delivered, then the stamp transaction failed (before a write, after a write, or at commit). */
        STAMP_FAILED(true, false);

        private final boolean delivered;
        private final boolean stamped;

        EmailOutcome(boolean delivered, boolean stamped) {
            this.delivered = delivered;
            this.stamped = stamped;
        }
    }

    /**
     * Everything a COMMITTED D.8 must leave behind, for the given post-commit outcome, relative to the
     * committed state right before the request (D.8 never writes the customer row, so it stays as it was).
     * Returns the appended (signed) invoice id.
     */
    private long assertCommittedSigning(SignableOrder fixture, CommittedState before, StorageWrites writes,
                                        SendObservations sends, AcceptAttempt attempt, EmailOutcome outcome)
            throws Exception {
        return assertCommittedSigning(fixture, before, before.customers(), writes, sends, attempt, outcome);
    }

    /**
     * As above, with the committed order_customer rows expected after the request given explicitly: they
     * differ from {@code before} only when the test itself committed a customer change after the signing
     * commit (section C). D.8 never writes them.
     */
    private long assertCommittedSigning(SignableOrder fixture, CommittedState before,
                                        List<Map<String, Object>> expectedCustomers, StorageWrites writes,
                                        SendObservations sends, AcceptAttempt attempt, EmailOutcome outcome)
            throws Exception {
        String label = "D.8 (" + outcome + ")";
        int version = fixture.currentVersion() + 1;
        assertStatus(attempt.result(), 201, label);
        JsonNode root = readJson(attempt.result());
        Assertions.assertEquals(outcome.delivered ? ACCEPT_EMAILED_MESSAGE : ACCEPT_EMAIL_FAILED_MESSAGE,
                root.path("message").asText(), label + ": the message reports the delivery outcome only");

        // ---- The committed signing: exactly one appended version, carrying the current one forward ----
        CommittedState after = committedState(fixture);
        int previous = before.invoices().size();
        Assertions.assertEquals(previous + 1, after.invoices().size(), label + ": exactly one invoice version committed");
        Assertions.assertEquals(before.invoices(), after.invoices().subList(0, previous),
                label + ": every earlier invoice row is untouched");
        Map<String, Object> current = before.invoices().get(previous - 1);
        Map<String, Object> signed = after.invoices().get(previous);
        long signedInvoiceId = toLong(signed.get("invoice_id"));
        Assertions.assertEquals(version, toInt(signed.get("version_number")), label + ": the next version");
        for (String carried : List.of("invoice_date", "due_date", "details_of_sale_snapshot", "sale_price_ex_gst",
                "sale_price_inc_gst", "total_paid", "balance_due", "customer_name_snapshot",
                "customer_address_line1_snapshot", "customer_address_line2_snapshot", "terms_snapshot",
                "source_quote_version_id")) {
            Assertions.assertEquals(current.get(carried), signed.get(carried),
                    label + ": " + carried + " is carried from the current version");
        }
        Assertions.assertEquals(V1_INVOICE_DATE, ((Date) signed.get("invoice_date")).toLocalDate(),
                label + ": invoice_date is the CURRENT version's date (copied), not the signing day");
        Assertions.assertEquals(EXPECTED_DUE_DATE, ((Date) signed.get("due_date")).toLocalDate(), label + ": due_date");
        Timestamp acceptedAt = (Timestamp) signed.get("accepted_at");
        Assertions.assertNotNull(acceptedAt, label + ": the appended version is signed");
        LocalDateTime acceptedAtTime = acceptedAt.toLocalDateTime();
        Assertions.assertFalse(acceptedAtTime.isBefore(attempt.startedAt().truncatedTo(ChronoUnit.SECONDS))
                        || acceptedAtTime.isAfter(attempt.finishedAt().plusSeconds(1)),
                label + ": accepted_at is the real signing time: " + acceptedAtTime);
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, signed.get("accepted_customer_name"),
                label + ": the accepted name is the current version's frozen name");
        Assertions.assertEquals(current.get("customer_name_snapshot"), signed.get("accepted_customer_name"),
                label + ": the accepted name IS the frozen customer_name_snapshot");
        Assertions.assertEquals(tenant.userId(), toLong(signed.get("created_by_user_id")), label + ": the session user");
        Long signatureFileId = toLong(signed.get("accepted_signature_file_id"));
        Long pdfFileId = toLong(signed.get("stored_file_id"));
        Assertions.assertNotNull(signatureFileId, label + ": the signature is referenced");
        Assertions.assertNotNull(pdfFileId, label + ": the signed PDF is referenced");

        // ---- The markers: set together by a committed stamp, otherwise both null ----
        Timestamp invoiceMarker = (Timestamp) signed.get("last_emailed_at");
        Timestamp mirrorMarker = (Timestamp) after.salesOrder().get("last_emailed_at");
        if (outcome.stamped) {
            Assertions.assertNotNull(invoiceMarker, label + ": invoice.last_emailed_at is stamped");
            Assertions.assertEquals(invoiceMarker, mirrorMarker,
                    label + ": the sales_order mirror carries the SAME timestamp as the invoice");
            Assertions.assertFalse(invoiceMarker.before(acceptedAt), label + ": stamped after the signing");
        } else {
            Assertions.assertNull(invoiceMarker, label + ": invoice.last_emailed_at stays null");
            Assertions.assertNull(mirrorMarker,
                    label + ": the mirror stays null (the persist's reset committed; nothing of the stamp did)");
        }
        Map<String, Object> expectedOrder = new LinkedHashMap<>(before.salesOrder());
        expectedOrder.put("last_emailed_at", outcome.stamped ? invoiceMarker : null);
        Assertions.assertEquals(expectedOrder, after.salesOrder(),
                label + ": D.8 writes only sales_order.last_emailed_at");

        // ---- The files: the signature and the signed PDF, rows and bytes, both committed ----
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions(),
                label + ": the signature file is written first, then the signed PDF");
        Assertions.assertEquals(2, writes.writtenPaths().size(), label + ": two files written");
        String signaturePath = writes.writtenPaths().get(0);
        String pdfPath = writes.writtenPaths().get(1);
        int previousFiles = before.storedFiles().size();
        Assertions.assertEquals(previousFiles + 2, after.storedFiles().size(), label + ": exactly two stored_file rows added");
        Assertions.assertEquals(before.storedFiles(), after.storedFiles().subList(0, previousFiles),
                label + ": every earlier stored_file row is untouched");
        Map<String, Object> signatureRow = after.storedFiles().get(previousFiles);
        Map<String, Object> pdfRow = after.storedFiles().get(previousFiles + 1);
        Assertions.assertEquals(signatureFileId, toLong(signatureRow.get("stored_file_id")), label + ": signature row");
        Assertions.assertEquals(signatureFileName(fixture), signatureRow.get("file_name"));
        Assertions.assertEquals("image/png", signatureRow.get("mime_type"));
        Assertions.assertEquals(signaturePath, signatureRow.get("storage_path"), label + ": the committed signature file");
        Assertions.assertEquals((long) SIGNATURE_PNG.length, toLong(signatureRow.get("file_size")));
        Assertions.assertEquals(pdfFileId, toLong(pdfRow.get("stored_file_id")), label + ": signed PDF row");
        Assertions.assertEquals(invoicePdfName(fixture.orderNumber(), version), pdfRow.get("file_name"));
        Assertions.assertEquals("application/pdf", pdfRow.get("mime_type"));
        Assertions.assertEquals(pdfPath, pdfRow.get("storage_path"), label + ": the committed signed PDF file");
        Assertions.assertArrayEquals(SIGNATURE_PNG, Files.readAllBytes(physicalPath(signaturePath)),
                label + ": the stored signature is the uploaded PNG");
        byte[] storedPdf = Files.readAllBytes(physicalPath(pdfPath));
        Assertions.assertTrue(startsWithPdfMagic(storedPdf), label + ": the stored signed invoice is a PDF");
        Assertions.assertEquals(toLong(pdfRow.get("file_size")), (long) storedPdf.length,
                label + ": stored_file.file_size matches the PDF on disk");
        Set<String> expectedFiles = new TreeSet<>(before.filesOnDisk());
        expectedFiles.add(fileName(signaturePath));
        expectedFiles.add(fileName(pdfPath));
        Assertions.assertEquals(expectedFiles, after.filesOnDisk(), label + ": exactly the two new files were added on disk");
        verify(fileStorageService, never()).deleteQuietly(any());
        verify(invoicePdfGenerator, times(1)).render(any());

        // ---- D5(c): the ISSUED version and its ACTIVE token cancelled, committed with the signing ----
        Assertions.assertEquals(
                withRowChanged(before.quoteVersions(), "quote_version_id", fixture.issuedQuoteVersionId(),
                        "status", "CANCELLED"),
                after.quoteVersions(), label + ": D5(c) changed ONLY the ISSUED version's status, and committed");
        Assertions.assertEquals(
                withRowChanged(before.quoteTokens(), "quote_token_id", fixture.quoteTokenId(),
                        "status", "CANCELLED", "dead_at", acceptedAt),
                after.quoteTokens(), label + ": D5(c) killed ONLY the ACTIVE token, at the signing time, and committed");
        Assertions.assertEquals(before.quoteVersionLines(), after.quoteVersionLines(), label + ": quote version lines");
        Assertions.assertEquals(before.quoteDraft(), after.quoteDraft(), label + ": quote draft");
        Assertions.assertEquals(before.quoteDraftLines(), after.quoteDraftLines(), label + ": quote draft lines");
        Assertions.assertEquals(before.payments(), after.payments(), label + ": payments");
        Assertions.assertEquals(expectedCustomers, after.customers(), label + ": customer");
        Assertions.assertEquals(before.addresses(), after.addresses(), label + ": addresses");
        Assertions.assertEquals(before.chargeLines(), after.chargeLines(), label + ": charge lines");
        Assertions.assertEquals(before.productLines(), after.productLines(), label + ": product lines");

        // ---- The persist's mirror reset and the stamp's writes ----
        verify(invoiceRepository, times(1)).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
        if (outcome == EmailOutcome.DELIVERED) {
            verify(invoiceRepository, times(1)).updateInvoiceLastEmailedAt(eq(signedInvoiceId), notNull());
            verify(invoiceRepository, times(1)).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), notNull());
        } else if (!outcome.delivered) {
            verify(invoiceRepository, never()).updateInvoiceLastEmailedAt(anyLong(), any());
            verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), notNull());
        }

        // ---- The response: the committed (or stamped) row ----
        JsonNode invoice = root.path("data").path("invoice");
        Assertions.assertEquals(signedInvoiceId, invoice.path("invoice_id").asLong(), label + ": response invoice_id");
        Assertions.assertEquals(version, invoice.path("version_number").asInt(), label + ": response version");
        Assertions.assertEquals(V1_INVOICE_DATE_TEXT, invoice.path("invoice_date").asText(), label + ": response invoice_date");
        Assertions.assertEquals(EXPECTED_DUE_DATE.toString(), invoice.path("due_date").asText(), label + ": response due_date");
        Assertions.assertTrue(invoice.path("accepted_signature_present").asBoolean(), label + ": response signed");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, invoice.path("accepted_customer_name").asText());
        // InvoiceDetail serializes its timestamps as whole seconds (yyyy-MM-dd'T'HH:mm:ss, truncated).
        Assertions.assertEquals(acceptedAtTime.truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(invoice.path("accepted_at").asText()), label + ": response accepted_at");
        Assertions.assertTrue(invoice.has("last_emailed_at"), label + ": last_emailed_at is always serialized");
        if (outcome.stamped) {
            Assertions.assertEquals(invoiceMarker.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS),
                    LocalDateTime.parse(invoice.path("last_emailed_at").asText()),
                    label + ": the response carries the stamped marker");
        } else {
            Assertions.assertTrue(invoice.get("last_emailed_at").isNull(),
                    label + ": the response falls back to the committed row (null marker)");
        }
        for (String internal : List.of("source_quote_version_id", "stored_file_id", "accepted_signature_file_id",
                "terms_snapshot", "storage_path")) {
            Assertions.assertFalse(invoice.has(internal), label + ": internal field must never be serialized: " + internal);
        }

        // ---- The email: attempted once, strictly after the commit, with this version's stored PDF ----
        ArgumentCaptor<InvoiceEmailRequest> captor = ArgumentCaptor.forClass(InvoiceEmailRequest.class);
        verify(invoiceEmailSender, times(1)).send(captor.capture());
        InvoiceEmailRequest email = captor.getValue();
        assertSignedInvoiceEmail(email, fixture, version, storedPdf, label);
        Assertions.assertEquals(1, sends.size(), label + ": exactly one send observed");
        Assertions.assertFalse(sends.transactionActive().get(0),
                label + ": the email is sent outside any transaction (after the signing commit)");
        Assertions.assertEquals(1L, sends.committedSignedVersions().get(0),
                label + ": at send time the signed version was already committed (visible to another connection)");
        Assertions.assertEquals("CANCELLED", sends.committedTokenStatus().get(0),
                label + ": at send time the D5(c) link cancellation was already committed");
        switch (outcome) {
            case DELIVERED, STAMP_FAILED -> {
                Assertions.assertEquals(1, invoiceEmailSender.sentEmails().size(), label + ": delivered once");
                Assertions.assertSame(email, invoiceEmailSender.sentEmails().get(0), label + ": the delivered request");
                Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), label + ": no failed attempt");
            }
            case DELIVERY_FAILED -> {
                Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), label + ": nothing delivered");
                Assertions.assertEquals(1, invoiceEmailSender.failedEmails().size(), label + ": one failed attempt");
                Assertions.assertSame(email, invoiceEmailSender.failedEmails().get(0), label + ": the failed request");
            }
            case SENDER_FAILED -> {
                Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), label + ": nothing delivered");
                Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(),
                        label + ": the non-transport failure never reached the recording transport");
            }
            default -> Assertions.fail("unexpected outcome " + outcome);
        }

        // ---- The signed PDF: the frozen name, the signing time, the copied invoice date, the signature ----
        String flatText = noSpace(pdfText(storedPdf));
        // The caption's inline spans are emitted out of content-stream order: read it position-sorted.
        String caption = noSpace("Accepted by " + FROZEN_CUSTOMER_NAME + " on " + DISPLAY_DATE_TIME.format(acceptedAtTime));
        Assertions.assertTrue(noSpace(pdfTextSortedByPosition(storedPdf)).contains(caption),
                label + ": the signed PDF carries the caption '" + caption + "'");
        Assertions.assertTrue(flatText.contains(V1_INVOICE_DATE_DISPLAY),
                label + ": the signed PDF shows the copied invoice date " + V1_INVOICE_DATE_DISPLAY);
        Assertions.assertTrue(flatText.contains(DUE_DATE_DISPLAY), label + ": the signed PDF shows the due date");
        Assertions.assertTrue(flatText.contains(noSpace(fixture.orderNumber())), label + ": the PDF names the order");
        Assertions.assertFalse(flatText.contains(noSpace("Customer signature")),
                label + ": a signed invoice never shows the blank-signature caption");
        Assertions.assertEquals(1, countOccurrences(flatText, noSpace(FOOTER_TEXT)), label + ": the footer renders once");
        Assertions.assertEquals(countImages(fixture.currentPdfBytes()) + 1, countImages(storedPdf),
                label + ": the signature is embedded as exactly one extra image vs the unsigned current version");

        // ---- The WARN line: order id + version only, no throwable, never the address ----
        switch (outcome) {
            case DELIVERED -> Assertions.assertEquals(List.of(), describe(attempt.serviceLog()),
                    label + ": a delivered and stamped signing logs nothing");
            case DELIVERY_FAILED, SENDER_FAILED -> assertSingleWarn(attempt.serviceLog(),
                    "Signed invoice email failed for order " + fixture.orderId() + " invoice v" + version
                            + " (non-fatal; the committed invoice stands, markers stay null)",
                    fixture.orderId(), version, label);
            case STAMP_FAILED -> assertSingleWarn(attempt.serviceLog(),
                    "Email-success stamp failed for order " + fixture.orderId() + " invoice v" + version
                            + " after delivery (non-fatal; markers stay null)",
                    fixture.orderId(), version, label);
            default -> Assertions.fail("unexpected outcome " + outcome);
        }

        // ---- Nothing else: earlier files intact, no quote email / notification, the link dead publicly ----
        assertProtectedFilesIntact(fixture);
        assertNoQuoteEmailOrNotification();
        assertPublicQuoteState(fixture.quoteToken(), "CANCELLED");
        return signedInvoiceId;
    }

    /** The post-commit email: the TRIMMED saved address, the fixed wording, this version's own stored PDF. */
    private static void assertSignedInvoiceEmail(InvoiceEmailRequest email, SignableOrder fixture, int version,
                                                 byte[] storedPdf, String label) {
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(),
                label + ": the recipient is the TRIMMED saved customer email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), label + ": subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + fixture.orderNumber() + " from " + BUSINESS_NAME
                + " attached.\n\nThank you.", email.bodyText(), label + ": body");
        Assertions.assertArrayEquals(storedPdf, email.pdfBytes(),
                label + ": the emailed PDF is byte-identical to the appended version's stored file");
        Assertions.assertEquals(invoicePdfName(fixture.orderNumber(), version), email.pdfFileName(), label + ": file name");
        Assertions.assertEquals(fixture.orderId(), email.orderId(), label + ": order id");
        Assertions.assertEquals(version, email.invoiceVersionNumber(), label + ": the appended version");
    }

    /** Exactly one OrderInvoiceService event: WARN, the exact text, order id + version, no throwable, no address. */
    private static void assertSingleWarn(List<ILoggingEvent> events, String expectedMessage, long orderId, int version,
                                         String label) {
        Assertions.assertEquals(1, events.size(),
                label + ": exactly one OrderInvoiceService log event, but was " + describe(events));
        ILoggingEvent event = events.get(0);
        Assertions.assertEquals(Level.WARN, event.getLevel(), label + ": logged at WARN");
        String message = event.getFormattedMessage();
        Assertions.assertEquals(expectedMessage, message, label + ": the WARN text");
        Assertions.assertTrue(message.contains("order " + orderId + " invoice v" + version),
                label + ": the WARN names the order and the version: " + message);
        Assertions.assertFalse(message.contains(CUSTOMER_EMAIL) || message.contains("@"),
                label + ": the WARN never carries the recipient: " + message);
        Assertions.assertNull(event.getThrowableProxy(), label + ": the WARN carries no throwable");
    }

    private static List<String> describe(List<ILoggingEvent> events) {
        return events.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
    }

    // ================================================================
    // Shared assertions - a refused or failed signing
    // ================================================================

    /** 500 with EXACTLY the generic error envelope (no data, no details, no exception text). */
    private void assertGeneric500(MvcResult result, String label) throws Exception {
        assertStatus(result, 500, label);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertEquals(JSON.readTree(GENERIC_500_BODY), JSON.readTree(body),
                label + ": the 500 body must be exactly the generic error envelope, but was " + body);
    }

    /**
     * A failed signing committed NOTHING and sent nothing: the request's own files are gone, its in-flight
     * rows were never committed, the committed state is exactly the state before the request (the mirror
     * sentinel kept, the ISSUED quote version and its ACTIVE token unchanged - so the link still works on
     * the real public surface), every earlier file is byte-identical, no email was even attempted, no stamp
     * ran and the service logged nothing.
     */
    private void assertNothingCommitted(SignableOrder fixture, CommittedState before, StorageWrites writes,
                                        List<Long> inFlightStoredFileIds, Long inFlightInvoiceId,
                                        AcceptAttempt attempt, String label) throws Exception {
        for (String storagePath : writes.writtenPaths()) {
            Assertions.assertFalse(Files.exists(physicalPath(storagePath)),
                    label + ": the request-owned file must be deleted from disk: " + storagePath);
            Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM stored_file WHERE storage_path = ?", storagePath),
                    label + ": no committed stored_file row may reference the written file");
        }
        for (Long storedFileId : inFlightStoredFileIds) {
            Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM stored_file WHERE stored_file_id = ?", storedFileId),
                    label + ": the stored_file row inserted inside the failed transaction must not be committed");
        }
        if (inFlightInvoiceId != null) {
            Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM invoice WHERE invoice_id = ?", inFlightInvoiceId),
                    label + ": the invoice row inserted inside the failed transaction must not be committed");
        }

        CommittedState after = committedState(fixture);
        assertSameCommittedState(before, after, label);
        // Readable restatements of the key invariants (all implied by the equality above).
        Assertions.assertEquals(List.of(fixture.currentVersion()), invoiceVersions(fixture.orderId()),
                label + ": no invoice version may be committed");
        Assertions.assertNull(after.invoices().get(after.invoices().size() - 1).get("accepted_at"),
                label + ": the current invoice stays unsigned");
        Assertions.assertEquals(SENTINEL_LAST_EMAILED_AT, after.salesOrder().get("last_emailed_at"),
                label + ": the committed mirror sentinel must survive (the mirror reset rolled back)");
        Assertions.assertEquals("ISSUED", jdbcTemplate.queryForObject(
                        "SELECT status FROM quote_version WHERE quote_version_id = ?", String.class,
                        fixture.issuedQuoteVersionId()),
                label + ": the committed quote version stays ISSUED (no D5(c) change committed)");
        Assertions.assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                        "SELECT status FROM quote_token WHERE quote_token_id = ?", String.class, fixture.quoteTokenId()),
                label + ": the committed token stays ACTIVE (no D5(c) change committed)");
        assertProtectedFilesIntact(fixture);

        verify(invoiceEmailSender, never()).send(any());
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), label + ": no invoice email may be sent");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), label + ": no invoice email may be attempted");
        verify(invoiceRepository, never()).updateInvoiceLastEmailedAt(anyLong(), any());
        Assertions.assertEquals(List.of(), describe(attempt.serviceLog()),
                label + ": the post-commit step never ran, so the service logged nothing");
        assertNoQuoteEmailOrNotification();
        assertPublicQuoteState(fixture.quoteToken(), "ACTIVE");
    }

    /**
     * Remove every injected fault (spies back to real behaviour, no trigger left) and sign again with the
     * same expected version: the full committed, delivered and stamped signing, relative to the same
     * committed state as before the failed attempt (which committed nothing).
     */
    private void assertRetrySigns(SignableOrder fixture, CommittedState before) throws Exception {
        Mockito.reset(invoicePdfGenerator, fileStorageService, invoiceRepository, invoiceEmailSender);
        dropCommitFailureTriggers();
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(false);
        AcceptAttempt retry = performAccept(fixture);
        assertCommittedSigning(fixture, before, writes, sends, retry, EmailOutcome.DELIVERED);
    }

    /** Component-by-component (readable failures), then the whole snapshot. */
    private static void assertSameCommittedState(CommittedState before, CommittedState after, String label) {
        Assertions.assertEquals(before.salesOrder(), after.salesOrder(), label + ": sales_order row");
        Assertions.assertEquals(before.invoices(), after.invoices(), label + ": invoice rows");
        Assertions.assertEquals(before.storedFiles(), after.storedFiles(), label + ": stored_file rows of the order");
        Assertions.assertEquals(before.filesOnDisk(), after.filesOnDisk(), label + ": files on disk");
        Assertions.assertEquals(before.quoteVersions(), after.quoteVersions(), label + ": quote_version rows");
        Assertions.assertEquals(before.quoteVersionLines(), after.quoteVersionLines(), label + ": quote_version_line rows");
        Assertions.assertEquals(before.quoteTokens(), after.quoteTokens(), label + ": quote_token rows");
        Assertions.assertEquals(before.quoteDraft(), after.quoteDraft(), label + ": quote_draft rows");
        Assertions.assertEquals(before.quoteDraftLines(), after.quoteDraftLines(), label + ": quote_draft_line rows");
        Assertions.assertEquals(before.payments(), after.payments(), label + ": payment_transaction rows");
        Assertions.assertEquals(before.customers(), after.customers(), label + ": order_customer rows");
        Assertions.assertEquals(before.addresses(), after.addresses(), label + ": order_address rows");
        Assertions.assertEquals(before.chargeLines(), after.chargeLines(), label + ": order_charge_line rows");
        Assertions.assertEquals(before.productLines(), after.productLines(), label + ": order_product_line rows");
        Assertions.assertEquals(before, after, label + ": committed state");
    }

    private void assertProtectedFilesIntact(SignableOrder fixture) throws IOException {
        for (ProtectedFile file : fixture.protectedFiles()) {
            Assertions.assertEquals(file.row(), storedFileRow(file.storedFileId()),
                    file.label() + ": its stored_file row must remain, unchanged");
            Path path = physicalPath(file.storagePath());
            Assertions.assertTrue(Files.isRegularFile(path), file.label() + " must still exist on disk");
            Assertions.assertArrayEquals(file.bytes(), Files.readAllBytes(path), file.label() + " bytes must be identical");
        }
    }

    private void assertNoQuoteEmailOrNotification() {
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty(), "no quote email may be sent");
        Assertions.assertTrue(quoteEmailSender.failedEmails().isEmpty(), "no quote email may be attempted");
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(), "no store notification may be sent");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(),
                "no store notification may be attempted");
    }

    /** Public GET (no session): 200 with the link's customer-facing state (the GET writes nothing). */
    private void assertPublicQuoteState(String token, String expectedState) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/quotes/" + token)).andReturn();
        assertStatus(result, 200, "public quote view");
        Assertions.assertEquals(expectedState, readJson(result).path("data").path("state").asText(),
                "the public quote link state");
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + body(result));
    }

    // ================================================================
    // The request under test
    // ================================================================

    /**
     * D.8 exactly as the PR 3 Invoice tab sends it: the {@code expected_version_number} form field (the
     * current version) and one PNG {@code signature} part, no name part. The OrderInvoiceService logger is
     * captured for the duration of the request only.
     */
    private AcceptAttempt performAccept(SignableOrder fixture) throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OrderInvoiceService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        LocalDateTime startedAt = LocalDateTime.now();
        MvcResult result;
        try {
            result = mockMvc.perform(multipart(orderUrl(fixture.orderId()) + "/invoices/current/accept")
                            .file(new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", SIGNATURE_PNG))
                            .param(EXPECTED_VERSION_FIELD, Integer.toString(fixture.currentVersion()))
                            .session(tenant.session()))
                    .andReturn();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        return new AcceptAttempt(result, List.copyOf(appender.list), startedAt, finishedAt);
    }

    // ================================================================
    // Spy recorders / stubs (installed only after the fixture is committed)
    // ================================================================

    /**
     * Record every storage write made from now on (the path returned by the REAL store, in call order),
     * optionally failing the write of one extension ("png" = the signature, "pdf" = the signed PDF) before
     * anything reaches the disk.
     */
    private StorageWrites recordStorageWrites(String failExtension) {
        StorageWrites writes = new StorageWrites();
        doAnswer(invocation -> {
            String extension = invocation.getArgument(3);
            writes.attempted.add(extension);
            if (extension.equals(failExtension)) {
                throw new UncheckedIOException("Failed to store attachment file",
                        new IOException("injected disk failure while writing the ." + extension + " file"));
            }
            String storagePath = (String) invocation.callRealMethod();
            writes.written.add(storagePath);
            return storagePath;
        }).when(fileStorageService).store(any(byte[].class), anyLong(), anyLong(), anyString());
        return writes;
    }

    /** The ids of every stored_file row inserted from now on (real insert, inside the request transaction). */
    private List<Long> recordInsertedStoredFileIds() {
        List<Long> ids = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            Long id = (Long) invocation.callRealMethod();
            ids.add(id);
            return id;
        }).when(invoiceRepository).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        return ids;
    }

    /**
     * Observe every send from now on, at the moment it happens: whether a transaction is active on the
     * request thread, and - through pooled auto-commit connections, so COMMITTED data only - whether the
     * signed version and the D5(c) token kill are already committed, and what the committed saved customer
     * email is. Then either really send (the recording transport, honouring {@code failNextSend}) or throw a
     * non-transport runtime failure.
     */
    private SendObservations observeSends(boolean throwOtherRuntimeFailure) {
        SendObservations observations = new SendObservations();
        doAnswer(invocation -> {
            InvoiceEmailRequest request = invocation.getArgument(0);
            observations.transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            observations.committedSignedVersions.add(count(
                    "SELECT COUNT(*) FROM invoice WHERE order_id = ? AND version_number = ? AND accepted_at IS NOT NULL",
                    request.orderId(), request.invoiceVersionNumber()));
            observations.committedTokenStatus.add(jdbcTemplate.queryForObject(
                    "SELECT t.status FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                            + "WHERE v.order_id = ? AND v.status IN ('ISSUED', 'CANCELLED')",
                    String.class, request.orderId()));
            List<String> savedEmails = jdbcTemplate.queryForList(
                    "SELECT email FROM order_customer WHERE order_id = ?", String.class, request.orderId());
            observations.committedCustomerEmails.add(savedEmails.isEmpty() ? "<no order_customer row>" : savedEmails.get(0));
            if (throwOtherRuntimeFailure) {
                throw new IllegalStateException("injected non-transport failure inside the invoice email sender");
            }
            return invocation.callRealMethod();
        }).when(invoiceEmailSender).send(any());
        return observations;
    }

    /**
     * For this order's signing insert from now on: call the REAL insert, then register a synchronization on
     * the signing transaction whose {@code beforeCommit} reads - on that transaction's own connection, after
     * every statement of the persist has run and before the COMMIT - the in-flight invoice, quote version,
     * token and mirror values.
     */
    private List<InFlightSigning> probeSigningBeforeCommit(SignableOrder fixture) {
        List<InFlightSigning> probes = new CopyOnWriteArrayList<>();
        insertInvoiceFor(doAnswer(invocation -> {
            InvoiceRow row = (InvoiceRow) invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    Map<String, Object> token = jdbcTemplate.queryForMap(
                            "SELECT status, dead_at FROM quote_token WHERE quote_token_id = ?", fixture.quoteTokenId());
                    probes.add(new InFlightSigning(
                            row.invoiceId(),
                            row.versionNumber(),
                            jdbcTemplate.queryForObject("SELECT accepted_at FROM invoice WHERE invoice_id = ?",
                                    Timestamp.class, row.invoiceId()),
                            jdbcTemplate.queryForObject("SELECT status FROM quote_version WHERE quote_version_id = ?",
                                    String.class, fixture.issuedQuoteVersionId()),
                            (String) token.get("status"),
                            (Timestamp) token.get("dead_at"),
                            jdbcTemplate.queryForObject("SELECT last_emailed_at FROM sales_order WHERE order_id = ?",
                                    Timestamp.class, fixture.orderId())));
                }
            });
            return row;
        }).when(invoiceRepository), fixture.orderId());
        return probes;
    }

    /**
     * For this order's persist mirror reset from now on ({@code updateSalesOrderLastEmailedAt(order, null)}, the
     * signing transaction's last write through the spies, after the email gate's customer read; the stamp's
     * mirror write carries a non-null value and is not matched): call the REAL write, then register a
     * synchronization on the signing transaction whose {@code afterCommit} commits {@code movedEmail} as the
     * order's saved customer email. {@code afterCommit} runs inside the TransactionTemplate's commit, so after
     * the signing is durable and before the post-commit send. The move uses a RAW pooled connection in
     * auto-commit, never {@code jdbcTemplate}: inside {@code afterCommit} that would still join the finished
     * transaction's connection, and nothing would commit it. On that connection the hook also records that the
     * signed version is already committed and what the committed saved email was before the move. Failures are
     * recorded rather than thrown, so the test can report them.
     */
    private SavedEmailMoves moveSavedEmailAfterSigningCommit(SignableOrder fixture, String movedEmail) {
        SavedEmailMoves moves = new SavedEmailMoves();
        int signedVersion = fixture.currentVersion() + 1;
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            moves.hookInsideSigningTransaction.add(TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try (Connection connection = dataSource.getConnection()) {
                        moves.autoCommit.add(connection.getAutoCommit());
                        try (PreparedStatement signed = connection.prepareStatement(
                                "SELECT COUNT(*) FROM invoice WHERE order_id = ? AND version_number = ? "
                                        + "AND accepted_at IS NOT NULL")) {
                            signed.setLong(1, fixture.orderId());
                            signed.setInt(2, signedVersion);
                            try (ResultSet resultSet = signed.executeQuery()) {
                                resultSet.next();
                                moves.signedVersionsCommittedAtMove.add(resultSet.getLong(1));
                            }
                        }
                        try (PreparedStatement saved = connection.prepareStatement(
                                "SELECT email FROM order_customer WHERE order_id = ?")) {
                            saved.setLong(1, fixture.orderId());
                            try (ResultSet resultSet = saved.executeQuery()) {
                                moves.committedEmailsBeforeMove.add(
                                        resultSet.next() ? resultSet.getString(1) : "<no order_customer row>");
                            }
                        }
                        try (PreparedStatement move = connection.prepareStatement(
                                "UPDATE order_customer SET email = ? WHERE order_id = ?")) {
                            move.setString(1, movedEmail);
                            move.setLong(2, fixture.orderId());
                            moves.rowsMoved.add(move.executeUpdate());
                        }
                    } catch (SQLException | RuntimeException ex) {
                        moves.failures.add(ex.toString());
                    }
                }
            });
            return result;
        }).when(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
        return moves;
    }

    /** {@code insertInvoice} (20 parameters) for this order with any other argument - stub or verify target. */
    private static InvoiceRow insertInvoiceFor(InvoiceRepository target, long orderId) {
        return target.insertInvoice(eq(orderId), anyInt(), any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A deferred constraint trigger that raises at COMMIT for any invoice row inserted for this order (the
     * WHEN clause is evaluated at INSERT time; the function runs at commit).
     */
    private void installInsertCommitFailureTrigger(String trigger, long orderId) {
        createFailAtCommitFunction();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger
                + " AFTER INSERT ON invoice"
                + " DEFERRABLE INITIALLY DEFERRED"
                + " FOR EACH ROW"
                + " WHEN (NEW.order_id = " + orderId + ")"
                + " EXECUTE FUNCTION " + TRIGGER_FUNCTION + "()");
    }

    /**
     * A deferred constraint trigger that raises at COMMIT when an invoice row of this order gets its
     * {@code last_emailed_at} set (null to non-null) - i.e. at the commit of the email-success stamp. The
     * signing persist only INSERTs invoice rows, so it never queues this trigger.
     */
    private void installStampCommitFailureTrigger(String trigger, long orderId) {
        createFailAtCommitFunction();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger
                + " AFTER UPDATE ON invoice"
                + " DEFERRABLE INITIALLY DEFERRED"
                + " FOR EACH ROW"
                + " WHEN (NEW.order_id = " + orderId
                + " AND OLD.last_emailed_at IS NULL AND NEW.last_emailed_at IS NOT NULL)"
                + " EXECUTE FUNCTION " + TRIGGER_FUNCTION + "()");
    }

    private void createFailAtCommitFunction() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION iacit_fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'iacit injected commit-time failure' USING ERRCODE = 'P0001';
                END;
                $$
                """);
    }

    private void clearSpyInvocations() {
        Mockito.clearInvocations(invoicePdfGenerator, fileStorageService, invoiceRepository, invoiceEmailSender);
    }

    private void resetSenders() {
        invoiceEmailSender.reset();
        quoteEmailSender.reset();
        notificationSender.reset();
    }

    // ================================================================
    // Committed-state probes (auto-commit JDBC - committed data only)
    // ================================================================

    /** Every row D.8 (or its D5(c) step) could touch for the order, plus the order's storage dir. */
    private CommittedState committedState(SignableOrder fixture) {
        long orderId = fixture.orderId();
        return new CommittedState(
                row("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                        orderStoragePrefix(orderId) + "%"),
                filesOnDisk(orderId),
                rows("SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT l.* FROM quote_version_line l JOIN quote_version v "
                        + "ON v.quote_version_id = l.quote_version_id WHERE v.order_id = ? "
                        + "ORDER BY l.quote_version_line_id", orderId),
                rows("SELECT t.* FROM quote_token t JOIN quote_version v "
                        + "ON v.quote_version_id = t.quote_version_id WHERE v.order_id = ? "
                        + "ORDER BY t.quote_token_id", orderId),
                rows("SELECT * FROM quote_draft WHERE order_id = ? ORDER BY quote_draft_id", orderId),
                rows("SELECT l.* FROM quote_draft_line l JOIN quote_draft d "
                        + "ON d.quote_draft_id = l.quote_draft_id WHERE d.order_id = ? "
                        + "ORDER BY l.quote_draft_line_id", orderId),
                rows("SELECT * FROM payment_transaction WHERE order_id = ? ORDER BY payment_transaction_id", orderId),
                rows("SELECT * FROM order_customer WHERE order_id = ? ORDER BY order_customer_id", orderId),
                rows("SELECT * FROM order_address WHERE order_id = ? ORDER BY order_address_id", orderId),
                rows("SELECT * FROM order_charge_line WHERE order_id = ? ORDER BY order_charge_line_id", orderId),
                rows("SELECT * FROM order_product_line WHERE order_id = ? ORDER BY order_product_line_id", orderId));
    }

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

    /** The full stored_file row, or null when it does not exist. */
    private Map<String, Object> storedFileRow(long storedFileId) {
        List<Map<String, Object>> found = rows("SELECT * FROM stored_file WHERE stored_file_id = ?", storedFileId);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<Integer> invoiceVersions(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT version_number FROM invoice WHERE order_id = ? ORDER BY version_number", Integer.class, orderId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    /** A copy of {@code rows} with the row whose {@code idColumn} = {@code id} changed by the column/value pairs. */
    private static List<Map<String, Object>> withRowChanged(List<Map<String, Object>> rows, String idColumn, long id,
                                                            Object... columnValuePairs) {
        List<Map<String, Object>> copies = new ArrayList<>();
        int matched = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            if (Objects.equals(toLong(row.get(idColumn)), id)) {
                matched++;
                for (int i = 0; i < columnValuePairs.length; i += 2) {
                    copy.put((String) columnValuePairs[i], columnValuePairs[i + 1]);
                }
            }
            copies.add(copy);
        }
        Assertions.assertEquals(1, matched, "exactly one row with " + idColumn + " = " + id + " must exist");
        return copies;
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static int toInt(Object value) {
        return ((Number) value).intValue();
    }

    // ================================================================
    // Seeding (committed for real: auto-commit JDBC + real API calls)
    // ================================================================

    /**
     * The class's own COMMITTED tenant: business (unique slug, ABN + bank details, no terms), one store,
     * one salesperson and that salesperson's store access.
     */
    private Tenant seedTenant() {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number, "
                        + " terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, '51 824 753 556', 'Ironbark Bank', '062-000', ?, '12345678', NULL, NULL) "
                        + "RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, BUSINESS_NAME + " Pty Ltd");
        Assertions.assertNotNull(businessId, "setup: business insert must return an id");
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Ironbark Showroom', ?, '0290007777', 'showroom@ironbark-acceptance.example.com', "
                        + " '3 Showroom Parade', 'Chatswood', 'NSW', '2067') RETURNING store_id",
                Integer.class, businessId, STORE_CODE);
        Assertions.assertNotNull(storeId, "setup: store insert must return an id");
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Ira', 'Closer', ?, 'ira.closer@ironbark-acceptance.example.com', "
                        + " 'not-a-login-hash-iacit') RETURNING user_id",
                Long.class, businessId, SALESPERSON_CODE);
        Assertions.assertNotNull(userId, "setup: app_user insert must return an id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        orderSequence = 0;
        return new Tenant(businessId, storeId, userId);
    }

    /**
     * A committed order ready for D.8: invoice-ready header and children; the unsigned invoice v1 through
     * the REAL D.1 endpoint, then committed-dated {@value #V1_INVOICE_DATE_TEXT}; the itemised quote draft
     * saved and ISSUED by email through the real protected endpoints (quote v1 ISSUED, one ACTIVE token,
     * the plaintext token taken from the recorded link-only email); v1's PDF and the issued quote PDF
     * captured (row + bytes); a non-null mirror sentinel committed; senders and spy invocations cleared.
     */
    private SignableOrder seedSignableOrder() throws Exception {
        SeededOrder order = insertInvoiceReadyOrder();
        long orderId = order.orderId();

        MvcResult created = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(tenant.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(created, 201, "setup: D.1 invoice create");
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, readJson(created).path("message").asText());
        List<Integer> versions = invoiceVersions(orderId);
        Assertions.assertEquals(1, versions.size(), "setup: D.1 committed exactly one invoice version");
        int currentVersion = versions.get(0);
        Map<String, Object> v1 = row("SELECT * FROM invoice WHERE order_id = ? AND version_number = ?",
                orderId, currentVersion);
        Assertions.assertNull(v1.get("accepted_at"), "setup: v1 is unsigned");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, v1.get("customer_name_snapshot"), "setup: v1's frozen name");
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice SET invoice_date = ? WHERE invoice_id = ?",
                Date.valueOf(V1_INVOICE_DATE), v1.get("invoice_id")), "setup: date v1 on an earlier day");

        saveItemisedQuoteDraft(orderId);
        String token = issueAndExtractToken(orderId);
        Map<String, Object> issued = row("SELECT * FROM quote_version WHERE order_id = ? AND version_number = 1", orderId);
        Assertions.assertEquals("ISSUED", issued.get("status"), "setup: quote v1 is ISSUED");
        long issuedQuoteVersionId = toLong(issued.get("quote_version_id"));
        List<Map<String, Object>> tokens = rows("SELECT * FROM quote_token WHERE quote_version_id = ?", issuedQuoteVersionId);
        Assertions.assertEquals(1, tokens.size(), "setup: exactly one token");
        Assertions.assertEquals("ACTIVE", tokens.get(0).get("status"), "setup: the token is ACTIVE");
        Assertions.assertNull(tokens.get(0).get("dead_at"), "setup: the token is alive");
        Assertions.assertEquals(sha256Hex(token), tokens.get(0).get("token_hash"),
                "setup: the emailed link carries the plaintext of the stored token hash");
        long quoteTokenId = toLong(tokens.get(0).get("quote_token_id"));

        ProtectedFile v1Pdf = protectedFile("the unsigned invoice v1 PDF", toLong(v1.get("stored_file_id")));
        ProtectedFile issuedQuotePdf = protectedFile("the issued quote v1 PDF", toLong(issued.get("issued_pdf_file_id")));

        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE sales_order SET last_emailed_at = ? WHERE order_id = ?",
                SENTINEL_LAST_EMAILED_AT, orderId), "setup: mirror sentinel");

        resetSenders();
        clearSpyInvocations();
        return new SignableOrder(orderId, order.orderNumber(), currentVersion, issuedQuoteVersionId, quoteTokenId,
                token, v1Pdf.bytes(), List.of(v1Pdf, issuedQuotePdf));
    }

    /**
     * Header (details of sale, proposed lay date 2026-12-01 CONFIRMED), customer 'Harper' 'Ironbark' with
     * the padded valid email, INSTALLATION + BILLING addresses and one tagged 100.00 ex / 40.00 cost charge.
     */
    private SeededOrder insertInvoiceReadyOrder() {
        int sequence = ++orderSequence;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        String orderNumber = ORDER_NUMBER_PREFIX + String.format("%05d", sequence);
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 42, 2026, ?, "
                        + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, tenant.businessId(), tenant.storeId(), tenant.userId(), sequence, orderNumber,
                DETAILS_OF_SALE);
        Assertions.assertNotNull(orderId, "setup: order insert must return an id");
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
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Billing Street', 'Sydney', 'NSW', '2000')",
                orderId);
        String chargeCode = CHARGE_CODE_PREFIX + sequence;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Ironbark commit test charge', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, tenant.storeId(), chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Ironbark commit test charge', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        return new SeededOrder(orderId, orderNumber);
    }

    /** Itemised draft through the API: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex / 275.00 inc (above cost). */
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
    private String issueAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/quote/send-email").session(tenant.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: quote send-email (issue)");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly the issue email is recorded");
        Matcher matcher = PUBLIC_LINK_PATTERN.matcher(sent.get(sent.size() - 1).bodyText());
        Assertions.assertTrue(matcher.find(), "setup: the quote email must carry the public /q/{token} link");
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "setup: the public link must appear exactly once in the email body");
        return token;
    }

    /** The stored_file row + the bytes on disk of one file that no failure may touch. */
    private ProtectedFile protectedFile(String label, long storedFileId) throws IOException {
        Map<String, Object> row = storedFileRow(storedFileId);
        Assertions.assertNotNull(row, "setup: " + label + " must have a stored_file row");
        String storagePath = (String) row.get("storage_path");
        byte[] bytes = Files.readAllBytes(physicalPath(storagePath));
        Assertions.assertEquals(toLong(row.get("file_size")), (long) bytes.length,
                "setup: " + label + " file_size must match the file on disk");
        return new ProtectedFile(label, storedFileId, row, storagePath, bytes);
    }

    // ================================================================
    // URLs / JSON / PDF helpers
    // ================================================================

    private String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    private static String signatureFileName(SignableOrder fixture) {
        return "signature-" + fixture.orderNumber() + "-v" + (fixture.currentVersion() + 1) + ".png";
    }

    private static String invoicePdfName(String orderNumber, int versionNumber) {
        return "invoice-" + orderNumber + "-v" + versionNumber + ".pdf";
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

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean startsWithPdfMagic(byte[] bytes) {
        return bytes.length >= 5 && "%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII));
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Text in reading order by position (each line left to right), independent of content-stream order. */
    private static String pdfTextSortedByPosition(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
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

    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
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

    private String orderStoragePrefix(long orderId) {
        return "/uploads/" + tenant.businessId() + "/orders/" + orderId + "/";
    }

    private Path businessStorageDir(long businessId) {
        return storageBase().resolve("uploads").resolve(Long.toString(businessId));
    }

    private Path orderStorageDir(long businessId, long orderId) {
        return businessStorageDir(businessId).resolve("orders").resolve(Long.toString(orderId));
    }

    /** File names currently in the order's storage dir (empty when the dir does not exist). */
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

    private static String fileName(String storagePath) {
        return storagePath.substring(storagePath.lastIndexOf('/') + 1);
    }

    // ================================================================
    // Cleanup (runs in @BeforeEach AND @AfterEach; every step runs even if an earlier one fails)
    // ================================================================

    private void cleanUpEverything() {
        runAll(
                () -> Mockito.reset(invoicePdfGenerator, fileStorageService, invoiceRepository, invoiceEmailSender),
                invoiceEmailSender::reset,
                quoteEmailSender::reset,
                notificationSender::reset,
                this::dropCommitFailureTriggers,
                this::sweepTenants);
        assertSweptClean();
    }

    /** The sweep really removed everything this class commits: the tenant (by slug), its triggers and function. */
    private void assertSweptClean() {
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM business WHERE slug = ?", SLUG),
                "no tenant of this class may survive the sweep");
        Assertions.assertEquals(0L, count(
                        "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice'::regclass AND tgname LIKE ?",
                        TRIGGER_PREFIX.replace("_", "\\_") + "%"),
                "no commit-failure trigger of this class may survive the sweep");
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM pg_proc WHERE proname = ?", TRIGGER_FUNCTION),
                "the commit-failure trigger function must be dropped");
        if (tenant != null) {
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

    /** Drop every leftover commit-failure trigger of this class on invoice (any order id), then its function. */
    private void dropCommitFailureTriggers() {
        List<String> triggers = jdbcTemplate.queryForList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'invoice'::regclass AND tgname LIKE ?",
                String.class, TRIGGER_PREFIX.replace("_", "\\_") + "%");
        for (String trigger : triggers) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + TRIGGER_FUNCTION + "()");
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
                orderId, orderId, orderId, orderId, orderId, orderId,
                "/uploads/" + businessId + "/orders/" + orderId + "/%");
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
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
            // Also catches orphans whose rows never committed (exactly what the failure tests assert against).
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
            // Best-effort cleanup; the directory sweep below retries.
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
    // Records
    // ================================================================

    /** The committed tenant. The session follows the RequestContextGuard type trap: (Long) ids, (Integer) store. */
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

    /**
     * A committed order ready for D.8: its current (unsigned) version, the ISSUED quote version and its
     * ACTIVE token (id + plaintext), the current version's PDF bytes and every file no failure may touch.
     */
    private record SignableOrder(long orderId,
                                 String orderNumber,
                                 int currentVersion,
                                 long issuedQuoteVersionId,
                                 long quoteTokenId,
                                 String quoteToken,
                                 byte[] currentPdfBytes,
                                 List<ProtectedFile> protectedFiles) {
    }

    /** One pre-existing file: its stored_file row and its bytes on disk at seed time. */
    private record ProtectedFile(String label, long storedFileId, Map<String, Object> row, String storagePath,
                                 byte[] bytes) {
    }

    /** One D.8 request: the response, the OrderInvoiceService log events it produced, and its time window. */
    private record AcceptAttempt(MvcResult result, List<ILoggingEvent> serviceLog,
                                 LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** The signing transaction as seen from inside, in its beforeCommit (after every statement, before COMMIT). */
    private record InFlightSigning(long invoiceId, int invoiceVersion, Timestamp invoiceAcceptedAt,
                                   String quoteVersionStatus, String quoteTokenStatus, Timestamp quoteTokenDeadAt,
                                   Timestamp mirror) {
    }

    /** Every committed row D.8 could touch, plus the order's storage dir (equality = unchanged). */
    private record CommittedState(
            Map<String, Object> salesOrder,
            List<Map<String, Object>> invoices,
            List<Map<String, Object>> storedFiles,
            Set<String> filesOnDisk,
            List<Map<String, Object>> quoteVersions,
            List<Map<String, Object>> quoteVersionLines,
            List<Map<String, Object>> quoteTokens,
            List<Map<String, Object>> quoteDraft,
            List<Map<String, Object>> quoteDraftLines,
            List<Map<String, Object>> payments,
            List<Map<String, Object>> customers,
            List<Map<String, Object>> addresses,
            List<Map<String, Object>> chargeLines,
            List<Map<String, Object>> productLines) {
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

    /** What each observed send saw at the moment it was attempted. */
    private static final class SendObservations {
        private final List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
        private final List<Long> committedSignedVersions = new CopyOnWriteArrayList<>();
        private final List<String> committedTokenStatus = new CopyOnWriteArrayList<>();
        private final List<String> committedCustomerEmails = new CopyOnWriteArrayList<>();

        int size() {
            return transactionActive.size();
        }

        List<Boolean> transactionActive() {
            return List.copyOf(transactionActive);
        }

        List<Long> committedSignedVersions() {
            return List.copyOf(committedSignedVersions);
        }

        List<String> committedTokenStatus() {
            return List.copyOf(committedTokenStatus);
        }

        /** The committed (saved) {@code order_customer.email} at send time, exactly as stored (untrimmed). */
        List<String> committedCustomerEmails() {
            return List.copyOf(committedCustomerEmails);
        }
    }

    /** What the afterCommit saved-email move observed and did (single-threaded test; one entry per run). */
    private static final class SavedEmailMoves {
        private final List<Boolean> hookInsideSigningTransaction = new CopyOnWriteArrayList<>();
        private final List<Boolean> autoCommit = new CopyOnWriteArrayList<>();
        private final List<Long> signedVersionsCommittedAtMove = new CopyOnWriteArrayList<>();
        private final List<String> committedEmailsBeforeMove = new CopyOnWriteArrayList<>();
        private final List<Integer> rowsMoved = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();

        /** One entry per time the hook was reached: true when inside an active, synchronized transaction. */
        List<Boolean> hookInsideSigningTransaction() {
            return List.copyOf(hookInsideSigningTransaction);
        }

        List<Boolean> autoCommit() {
            return List.copyOf(autoCommit);
        }

        /** The committed count of the signed version, read by afterCommit on its own connection. */
        List<Long> signedVersionsCommittedAtMove() {
            return List.copyOf(signedVersionsCommittedAtMove);
        }

        List<String> committedEmailsBeforeMove() {
            return List.copyOf(committedEmailsBeforeMove);
        }

        /** One entry per afterCommit run: the rows its UPDATE changed. */
        List<Integer> rowsMoved() {
            return List.copyOf(rowsMoved);
        }

        List<String> failures() {
            return List.copyOf(failures);
        }
    }
}
