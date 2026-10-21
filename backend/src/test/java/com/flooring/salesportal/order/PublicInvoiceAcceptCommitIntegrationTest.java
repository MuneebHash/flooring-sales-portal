package com.flooring.salesportal.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
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
import com.flooring.salesportal.common.error.GlobalExceptionHandler;
import com.flooring.salesportal.common.image.SignaturePngNormaliser;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
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
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16G PR 4 (16G contract sections 6, 9.4, 10, 11 and the PR 4 row of section 16) - the PUBLIC remote
 * invoice accept {@code POST /api/v1/public/invoices/{token}/accept} at the REAL commit boundary: what the
 * customer's signed-PDF email and the store notification do strictly AFTER the signing transaction committed,
 * and what is left behind when something fails BEFORE (or AT) that commit.
 *
 * <p><b>Why a separate, non-transactional class.</b> Inside a {@code @Transactional} test the public accept's
 * {@code TransactionTemplate} would join the test transaction, so nothing would commit: "the email and the
 * notification run only after the commit", "a post-commit failure leaves the signing committed", "the stamp
 * transaction rolls back as a whole" and "a commit-time failure deletes only this request's files through the
 * afterCompletion hooks" could not be observed. This class has NO {@code @Transactional} anywhere: every seed
 * statement auto-commits, every MockMvc call commits for real, and every assertion reads COMMITTED state through
 * pooled auto-commit connections plus the files on disk.
 *
 * <p><b>Rules under test.</b> Section 10.3 / 6.1 / 11: after commit the signed PDF of the appended version goes
 * to the TRIMMED saved customer email captured by the signing transaction (no email gate: a missing or invalid
 * saved email skips the send with a WARN); success stamps {@code invoice.last_emailed_at} of the appended
 * version and the {@code sales_order.last_emailed_at} mirror with the same value; a delivery failure, any other
 * sender failure, a skip and a stamp failure (before a write, after a write or at commit) leave both markers
 * null. Section 10.4: the store notification to the order's own (trimmed) store email, with the exact subject
 * and text body, skipped with a WARN when the store has no email, WARN-only on any sender failure, never a
 * marker. Section 6.2 / 9.4: the public response is ALWAYS exactly {@code {"data":{"state":"SIGNED"},
 * "message":"Invoice accepted."}} once the signing committed, and the two post-commit side effects are
 * independent of each other. Every WARN carries only the order id and the invoice version (no throwable, no
 * address, no token, no link). Section 6.2 / 10.1 / 10.6: before or at commit, any failure (render, signature
 * or PDF storage, either stored_file insert, the invoice insert, the mirror reset, the token kill, the commit
 * itself) gives the generic 500 without any database detail, rolls EVERYTHING back (no invoice or stored_file
 * row, the link still ACTIVE with no dead_at, the issued quote and its token not cancelled - D5(c) rolled back -
 * the mirror sentinel kept), deletes only this request's files (twice - in-method catch plus afterCompletion
 * hook - for a mid-transaction failure; once - the hook alone - at commit), leaves every older PDF and
 * signature byte-identical, sends no email and no notification and logs no WARN; the same link then signs
 * normally. A stale (409) or dead-link (410) refusal sends nothing either.
 *
 * <p><b>Fault injection</b> (no production hooks) - EXACTLY the four {@link MockitoSpyBean} spies of
 * {@link InvoiceAcceptanceCommitIntegrationTest} and the Path A commit class (same types, same field names, so
 * all three share one cached Spring context), real behaviour unless stubbed (doX().when(spy) form only), stubbed
 * only after the fixture is committed: {@link InvoicePdfGenerator} (the signed-PDF render), {@link
 * FileStorageService} (the signature write, then the signed-PDF write), {@link InvoiceRepository} (the two
 * stored_file inserts, the invoice insert, the mirror reset and the stamp's two writes) and {@link
 * RecordingInvoiceEmailSender} (the post-commit customer send). The store notification and the link email use
 * their singleton recording senders' own one-shot failure hooks. At or around COMMIT, triggers scoped to the
 * test order (prefix {@value #TRIGGER_PREFIX}): a {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger
 * {@code AFTER INSERT ON invoice} (the signing fails at its commit) or {@code AFTER UPDATE ON invoice} when
 * {@code last_emailed_at} becomes non-null (the email-success stamp fails at its commit), and an ordinary
 * {@code BEFORE UPDATE ON invoice_token} trigger refusing the {@code ACTIVE -> SIGNED} kill (the token kill
 * fails mid-transaction). Each 500 is tied to its injected fault through the cause chain of the single
 * {@link GlobalExceptionHandler} ERROR event, while the response body stays the generic envelope.
 *
 * <p><b>Fixtures - fully self-seeded and COMMITTED.</b> Each test seeds its own tenant: business
 * {@value #SLUG} (ABN, bank details and SOFT terms), store {@value #STORE_CODE} with a PADDED email, the
 * salesperson {@value #ORDER_USER_CODE} (owns the orders, creates invoice v1, issues the quotes) and the
 * salesperson {@value #LINK_SENDER_CODE} (sends the invoice link), both with store access. Each order is
 * invoice-ready (customer with a PADDED valid email, INSTALLATION + BILLING, details of sale, lay date
 * 2026-12-01 CONFIRMED, one 1,234.50 ex / 40.00 cost charge line) and gets: the unsigned invoice v1 through the
 * real D.1 endpoint (then committed-dated {@value #V1_INVOICE_DATE_TEXT}, so the copied invoice date of the
 * signing version is visible); quote v1 issued and ACCEPTED on the real public quote page (an older signature
 * and signed PDF that no failure may touch, and an ACCEPTED quote D5(c) must never revoke); quote v2 ISSUED with
 * an ACTIVE token; and the ACTIVE invoice link through the real protected send-link by {@value
 * #LINK_SENDER_CODE} (the plaintext token read from the recorded link-only email). A non-null mirror sentinel is
 * committed last. No V4 business, store, user or order is read or written.
 *
 * <p><b>Requests.</b> MockMvc runs the REAL Spring Security filter chain ({@code springSecurity()}). Every
 * public request goes through {@link #performPublic}, which also asserts that no session exists afterwards and
 * that no {@code Set-Cookie} was sent - for every status. Staff requests carry a {@link MockHttpSession} with the
 * {@code SessionContext} attribute types.
 *
 * <p><b>Sweep.</b> {@code @BeforeEach} and {@code @AfterEach} remove everything the class commits, in FK-safe
 * order: per order invoice_token, quote_token, invoice, quote_version, quote_draft, payment_transaction, the
 * other order children, sales_order and the order's stored_file rows (plus its files and directory); then the
 * tenant's store_charge / store_product, user_store_access, app_user, store, business_quick_description and
 * business rows, the tenant's storage directory, and any leftover {@value #TRIGGER_PREFIX} trigger and function.
 * The four spies and the five recording senders are reset in both. Single-threaded.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class PublicInvoiceAcceptCommitIntegrationTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before anything is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant: the slug is the sweep's only key ----
    private static final String SLUG = "pr4-accept-commit";
    private static final String BUSINESS_NAME = "Acceptance Commit Flooring";
    private static final String STORE_CODE = "IAC-ONE";
    /** Owns the orders, creates invoice v1 and issues the quotes. */
    private static final String ORDER_USER_CODE = "AC1";
    /** Sends the invoice link: the creator of the remotely signed version (16G contract 10.2). */
    private static final String LINK_SENDER_CODE = "AC2";
    private static final String ORDER_NUMBER_PREFIX = STORE_CODE + "." + ORDER_USER_CODE + ".";
    private static final String CHARGE_CODE_PREFIX = "IACMT";
    private static final String STORE_EMAIL = "accounts@iac-one.example.com";
    /** Saved with edge whitespace (chk_store_email_format allows it): the notification recipient must be trimmed. */
    private static final String SAVED_STORE_EMAIL = "  " + STORE_EMAIL + "  ";
    /** Committed as the store email right AFTER the signing commit (section D): never the notification recipient. */
    private static final String MOVED_STORE_EMAIL = "moved.store@iac-one.example.com";
    /** The business SOFT terms D.1 freezes into v1 (so a COPIED non-null terms_snapshot is observable). */
    private static final String TERMS_SOFT = "<p>IAC commit terms: deposits are non-refundable.</p>";
    private static final String TERMS_TEXT = "deposits are non-refundable";

    // ---- Failure injection at or around COMMIT (class-unique prefix: no other class starts with it) ----
    private static final String TRIGGER_PREFIX = "iacmt_";
    private static final String COMMIT_FAILURE_FUNCTION = "iacmt_fail_at_commit";
    private static final String TOKEN_KILL_FAILURE_FUNCTION = "iacmt_fail_token_kill";
    private static final String INSERT_TRIGGER_PREFIX = TRIGGER_PREFIX + "insert_";
    private static final String STAMP_TRIGGER_PREFIX = TRIGGER_PREFIX + "stamp_";
    private static final String TOKEN_KILL_TRIGGER_PREFIX = TRIGGER_PREFIX + "kill_";
    private static final List<String> TRIGGER_TABLES = List.of("invoice", "invoice_token");
    private static final String COMMIT_FAILURE_TEXT = "iacmt injected commit-time failure";
    private static final String TOKEN_KILL_FAILURE_TEXT = "iacmt injected invoice_token kill failure";

    // Children of sales_order deleted after invoice_token / quote_token / invoice / quote_version / quote_draft /
    // payment_transaction (every child FK is RESTRICT; the two *_line quote tables CASCADE from their parent).
    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "order_attachment", "order_note", "order_product_line", "order_charge_line",
            "order_address", "order_customer", "order_enquiry");

    // ---- The seeded customer ----
    private static final String CUSTOMER_FIRST_NAME = "Rowan";
    private static final String CUSTOMER_LAST_NAME = "Acceptline";
    /** The D.1 freeze of the saved name: v1's customer_name_snapshot, i.e. the remote accepted name. */
    private static final String FROZEN_CUSTOMER_NAME = "Rowan Acceptline";
    private static final String CUSTOMER_EMAIL = "rowan.acceptline@example.com";
    /** Saved with edge whitespace (DB-valid, app-valid once trimmed): every recipient must be the trimmed value. */
    private static final String SAVED_CUSTOMER_EMAIL = "  " + CUSTOMER_EMAIL + "  ";
    /** DB-valid (chk_order_customer_email_format) but app-invalid (no interior dot in the domain). */
    private static final String APP_INVALID_EMAIL = "rowan@localhost";
    /** Committed as the saved email right AFTER the signing commit (section D): never the recipient. */
    private static final String MOVED_CUSTOMER_EMAIL = "moved.after.commit@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and install carpet (remote accept commit boundary test)";
    private static final String BILLING_LINE_1 = "12 Billing Street";
    private static final String BILLING_LINE_2 = "Sydney NSW 2000";

    // ---- Money (one 1,234.50 ex charge line: 123.45 GST) ----
    private static final BigDecimal INVOICE_EX_GST = new BigDecimal("1234.50");
    private static final BigDecimal INVOICE_INC_GST = new BigDecimal("1357.95");
    /** The store notification's inc-GST total, written out literally (grouping separator included). */
    private static final String INVOICE_INC_GST_DISPLAY = "$1,357.95";
    private static final String PAYMENT_AMOUNT = "20.00";

    // ---- Dates ----
    private static final String V1_INVOICE_DATE_TEXT = "2026-09-15";
    /** v1 is committed-dated on an earlier day: the signing version must COPY it (16G contract 10.1). */
    private static final LocalDate V1_INVOICE_DATE = LocalDate.parse(V1_INVOICE_DATE_TEXT);
    /** proposed_lay_date 2026-12-01 minus 2 days. */
    private static final LocalDate EXPECTED_DUE_DATE = LocalDate.of(2026, 11, 29);
    private static final String DUE_DATE_DISPLAY = "29/11/2026";
    /** A NON-NULL committed mirror: a failed signing must leave exactly this value behind. */
    private static final Timestamp SENTINEL_LAST_EMAILED_AT = Timestamp.valueOf("2026-02-03 04:05:06");

    // ---- Response bodies and messages ----
    private static final String PUBLIC_ACCEPTED_BODY =
            "{\"data\":{\"state\":\"SIGNED\"},\"message\":\"Invoice accepted.\"}";
    /** The generic 500 envelope: no data, no details, no exception or database text. */
    private static final String GENERIC_500_BODY =
            "{\"error\":{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"An unexpected error occurred.\"}}";
    private static final String STALE_BODY =
            "{\"error\":{\"code\":\"INVOICE_VERSION_STALE\",\"message\":\"Invoice updated, please refresh\"}}";
    private static final String REPLACED_STATE_MESSAGE =
            "This invoice link has been replaced. Please use the latest invoice link.";
    private static final String SIGNED_STATE_MESSAGE =
            "This invoice has already been signed. Please contact the store if you need a copy.";
    private static final String LINK_REPLACED_BODY =
            "{\"error\":{\"code\":\"INVOICE_LINK_REPLACED\",\"message\":\"" + REPLACED_STATE_MESSAGE + "\"}}";
    private static final String LINK_SIGNED_BODY =
            "{\"error\":{\"code\":\"INVOICE_LINK_SIGNED\",\"message\":\"" + SIGNED_STATE_MESSAGE + "\"}}";
    private static final String INVOICE_LOADED_MESSAGE = "Invoice loaded.";
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String LINK_SENT_MESSAGE = "Invoice link emailed to the customer.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String FOOTER_TEXT = "Generated by the Flooring Sales Portal";
    /** app.public-base-url (application.properties): the origin of the emailed public link. */
    private static final String PUBLIC_BASE_URL = "http://localhost:5173";

    // ---- The public accept multipart contract (16G contract 9.4) ----
    private static final String SIGNATURE_PART = "signature";
    private static final String EXPECTED_VERSION_FIELD = "expected_version_number";

    /** Copied from the current version onto the signing version (16G contract 10.1), compared column by column. */
    private static final List<String> CARRIED_COLUMNS = List.of(
            "invoice_date", "due_date", "details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst",
            "total_paid", "balance_due", "customer_name_snapshot", "customer_address_line1_snapshot",
            "customer_address_line2_snapshot", "terms_snapshot", "source_quote_version_id");

    // The real public links in the recorded link-only emails: <app.public-base-url>/{q|i}/{43-char token}.
    private static final Pattern QUOTE_LINK_PATTERN =
            Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final Pattern INVOICE_LINK_PATTERN =
            Pattern.compile("/i/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    // The acceptance caption / notification time format and the invoice date format of the PDF.
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    // A real, decodable 1x1 PNG: it passes the public PNG rules and embeds into the signed PDFs.
    private static final byte[] SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    /** The server-normalised signature (16G contract 9.4): the ONLY bytes the remote accept may store. */
    private static final byte[] NORMALISED_SIGNATURE_PNG = SignaturePngNormaliser.normalise(SIGNATURE_PNG);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Raw pooled connections (auto-commit), for the afterCommit email moves only - never the thread's transaction. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender quoteNotificationSender;

    @Autowired
    private RecordingInvoiceLinkEmailSender invoiceLinkEmailSender;

    @Autowired
    private RecordingInvoiceAcceptanceNotificationSender invoiceNotificationSender;

    // Spies call the REAL methods unless a test stubs them (doX().when(spy) form only). The InvoiceRepository
    // field is the @Repository CGLIB proxy around the spy; Spring's SpringMockResolver lets doX().when(..),
    // verify(..), clearInvocations(..) and reset(..) resolve through it. EXACTLY this set of four (types and
    // field names) is shared with InvoiceAcceptanceCommitIntegrationTest and the Path A commit class.
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
        Assertions.assertNotNull(NORMALISED_SIGNATURE_PNG, "setup: the fixture PNG passes the public PNG rules");
        // The REAL Spring Security filter chain (PublicBusinessControllerTest precedent).
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUpEverything();
        tenant = seedTenant();
    }

    @AfterEach
    void tearDown() {
        cleanUpEverything();
    }

    // ================================================================
    // A. The customer's signed-PDF email after commit (the store notification delivered alongside)
    // ================================================================

    /**
     * Baseline (16G contract 10.3, 10.4, 11): the delivery succeeds, the stamp commits and the store is notified.
     * Exactly the fixed 201; the appended signing version (a copy of v1 with the acceptance, created by the link
     * sender), its normalised signature and signed PDF, the SIGNED link and the D5(c) cancellation are committed;
     * the customer email was sent once, outside any transaction, after the signing was visible to another
     * connection, to the TRIMMED saved email, carrying the stored signed PDF byte for byte; both markers carry
     * the same timestamp; the exact store notification went to the trimmed store email; no WARN.
     */
    @Test
    void remoteAccept_emailDeliveredAndStoreNotified_returnsTheFixed201_signingCommittedBeforeTheSend_exactEmailCarriesTheStoredSignedPdf_markersStampedEqual()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.DELIVERED, NotificationOutcome.DELIVERED);
    }

    /**
     * The saved email is app-INVALID when the customer signs (DB-valid, no interior dot in the domain). There is
     * no email gate on the remote accept (16G contract 9.4 step 3.5, 6.1): the signing commits, the send is
     * SKIPPED with one WARN, nothing reaches the transport, both markers stay null - and the store is still
     * notified (independence), which never stamps a marker.
     */
    @Test
    void remoteAccept_savedEmailAppInvalidAtSigning_emailSkippedWithWarn_noEmailGate_returns201_signingCommitted_markersNull_storeStillNotified()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        setSavedCustomerEmail(fixture, APP_INVALID_EMAIL);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.SKIPPED_INVALID_EMAIL, NotificationOutcome.DELIVERED);
    }

    /**
     * There is NO saved customer row when the customer signs ({@code order_customer.email} is NOT NULL and
     * non-blank in the schema, so a missing saved email means a missing customer row). The frozen words of the
     * version are unaffected: the signing commits, the send is SKIPPED with one WARN, both markers stay null and
     * the store is still notified.
     */
    @Test
    void remoteAccept_noSavedCustomerRowAtSigning_emailSkippedWithWarn_returns201_signingCommittedFromTheFrozenWords_markersNull_storeStillNotified()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        Assertions.assertEquals(1, jdbcTemplate.update("DELETE FROM order_customer WHERE order_id = ?", fixture.orderId()),
                "setup: the saved customer row (the only holder of the saved email) is removed after the link was sent");
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.SKIPPED_NO_CUSTOMER, NotificationOutcome.DELIVERED);
    }

    /**
     * The transport fails ({@code failNextSend}: {@code InvoiceEmailException}). Still the fixed 201; the
     * signing, the SIGNED link and the D5(c) cancellation stay committed; both markers null (the persist's mirror
     * reset committed, no stamp was attempted); the failed attempt carried the stored signed PDF; one "failed"
     * WARN without a throwable; the store is still notified.
     */
    @Test
    void remoteAccept_emailTransportFails_returnsTheFixed201_signingCommitted_markersNull_warnsWithoutThrowable_storeStillNotified()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
        invoiceEmailSender.failNextSend();

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.TRANSPORT_FAILED, NotificationOutcome.DELIVERED);
    }

    /**
     * The customer sender throws a DIFFERENT runtime exception ({@link IllegalStateException}, not the
     * transport's {@code InvoiceEmailException}): exactly like a delivery failure - the fixed 201, the signing
     * committed, both markers null, nothing recorded by the transport, one "failed" WARN, the store notified.
     */
    @Test
    void remoteAccept_emailSenderThrowsOtherRuntimeException_returnsTheFixed201_signingCommitted_markersNull_warnsWithoutThrowable_storeStillNotified()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, true);

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.RUNTIME_FAILED, NotificationOutcome.DELIVERED);
    }

    /**
     * The delivery succeeds, then the stamp transaction fails BEFORE any write (its first write,
     * {@code updateInvoiceLastEmailedAt}, throws). The fixed 201; both markers null; the stamp never reached its
     * mirror write; one "Email-success stamp failed" WARN; the store notified.
     */
    @Test
    void remoteAccept_stampFailsBeforeAnyWrite_returnsTheFixed201_signingCommitted_markersNull_warnsWithoutThrowable_storeStillNotified()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
        doThrow(new DataAccessResourceFailureException("injected failure on the stamp's invoice last_emailed_at write"))
                .when(invoiceRepository).updateInvoiceLastEmailedAt(anyLong(), any());

        AcceptAttempt attempt = performPublicAccept(fixture);

        long signedInvoiceId = assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.STAMP_FAILED, NotificationOutcome.DELIVERED);
        // The failure point was really reached (the stamp's first write, for the appended version), and the
        // stamp stopped there: its mirror write never ran.
        verify(invoiceRepository).updateInvoiceLastEmailedAt(eq(signedInvoiceId), notNull());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), notNull());
    }

    /**
     * The delivery succeeds; inside the stamp transaction the invoice stamp IS written (read back on the stamp's
     * own connection), then the mirror write - stubbed to fail only for a non-null value, so the signing
     * persist's null reset stays real - throws. The WHOLE stamp rolls back: both committed markers null. The
     * fixed 201, one stamp WARN, the store notified.
     */
    @Test
    void remoteAccept_stampMirrorWriteFailsAfterTheInvoiceStampWasWritten_wholeStampRolledBack_returnsTheFixed201_markersNull_storeStillNotified()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
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

        AcceptAttempt attempt = performPublicAccept(fixture);

        long signedInvoiceId = assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.STAMP_FAILED, NotificationOutcome.DELIVERED);
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
     * The delivery succeeds and every stamp write succeeds in flight, then the stamp transaction fails AT COMMIT
     * (a deferred constraint trigger {@code AFTER UPDATE ON invoice} when {@code last_emailed_at} becomes
     * non-null). The whole stamp rolls back: both committed markers null; the fixed 201; one stamp WARN; the
     * signing (committed by its own, earlier transaction) stands; the store notified.
     */
    @Test
    void remoteAccept_stampFailsAtCommit_deferredInvoiceUpdateTrigger_wholeStampRolledBack_returnsTheFixed201_markersNull_storeStillNotified()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
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
            Assertions.assertEquals(1L, triggerCount("invoice", trigger),
                    "the stamp commit-failure trigger must be installed before the request");
            attempt = performPublicAccept(fixture);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }

        long signedInvoiceId = assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.STAMP_FAILED, NotificationOutcome.DELIVERED);
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
    // B. The store notification after commit (the customer email delivered and stamped alongside)
    // ================================================================

    /**
     * The order's store has no email (null; whitespace-only is refused by {@code chk_store_email_format}). The
     * notification is SKIPPED with one WARN, nothing reaches its sender, there is no business, salesperson or
     * customer fallback - and the customer email is still delivered and stamped (independence).
     */
    @Test
    void remoteAccept_storeHasNoEmail_notificationSkippedWithWarn_noFallbackRecipient_customerEmailStillDeliveredAndStamped_returnsTheFixed201()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE store SET email = NULL WHERE store_id = ?", tenant.storeId()),
                "setup: the order's store has no email when the customer signs");
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.DELIVERED, NotificationOutcome.SKIPPED_NO_STORE_EMAIL);
    }

    /**
     * The notification transport fails ({@code failNextSend}): one WARN without a throwable, the failed attempt
     * was the exact request, no marker of any kind - and the customer's delivered email keeps its committed
     * stamp on both markers (independence). The fixed 201.
     */
    @Test
    void remoteAccept_storeNotificationTransportFails_warnsWithoutThrowable_customerStampStands_returnsTheFixed201()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
        invoiceNotificationSender.failNextSend();

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.DELIVERED, NotificationOutcome.TRANSPORT_FAILED);
    }

    /**
     * The notification sender throws a non-transport runtime exception ({@code failNextSendWith}): the same
     * single WARN, the customer email delivered and stamped, the fixed 201.
     */
    @Test
    void remoteAccept_storeNotificationSenderThrowsOtherRuntimeException_warnsWithoutThrowable_customerStampStands_returnsTheFixed201()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
        invoiceNotificationSender.failNextSendWith(
                new IllegalStateException("injected non-transport failure inside the store notification sender"));

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.DELIVERED, NotificationOutcome.RUNTIME_FAILED);
    }

    // ================================================================
    // C. Independence: both post-commit side effects fail or skip in the same request
    // ================================================================

    /** Both transports fail: two WARNs (one each), the signing committed, both markers null, the fixed 201. */
    @Test
    void remoteAccept_emailAndNotificationTransportsBothFail_eachWarnsOnce_signingCommitted_markersNull_returnsTheFixed201()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
        invoiceEmailSender.failNextSend();
        invoiceNotificationSender.failNextSend();

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.TRANSPORT_FAILED, NotificationOutcome.TRANSPORT_FAILED);
    }

    /** Both are skipped (app-invalid saved email, no store email): two skip WARNs, nothing sent, the fixed 201. */
    @Test
    void remoteAccept_emailSkippedAndNotificationSkipped_eachWarnsOnce_nothingSent_signingCommitted_markersNull_returnsTheFixed201()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        setSavedCustomerEmail(fixture, APP_INVALID_EMAIL);
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE store SET email = NULL WHERE store_id = ?", tenant.storeId()));
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.SKIPPED_INVALID_EMAIL, NotificationOutcome.SKIPPED_NO_STORE_EMAIL);
    }

    /**
     * The customer email is delivered but its stamp fails, and the notification sender throws: one WARN each,
     * both markers null (the notification never stamps, and the failed stamp recorded nothing), the fixed 201.
     */
    @Test
    void remoteAccept_stampFailsAndNotificationSenderThrows_eachWarnsOnce_signingCommitted_markersNull_returnsTheFixed201()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
        doThrow(new DataAccessResourceFailureException("injected failure on the stamp's invoice last_emailed_at write"))
                .when(invoiceRepository).updateInvoiceLastEmailedAt(anyLong(), any());
        invoiceNotificationSender.failNextSendWith(
                new IllegalStateException("injected non-transport failure inside the store notification sender"));

        AcceptAttempt attempt = performPublicAccept(fixture);

        assertCommittedSigning(fixture, before, writes, sends, attempt,
                CustomerOutcome.STAMP_FAILED, NotificationOutcome.RUNTIME_FAILED);
    }

    // ================================================================
    // D. Both plans are captured INSIDE the signing transaction, never re-read after the commit
    // ================================================================

    /**
     * 16G contract 6.1 (the recipient is the saved email "read inside the committing transaction") and 10.4 (the
     * store notification resolved inside the signing transaction). From the signing insert (a doAnswer that
     * registers the hook, then calls the real insert), an {@code afterCommit} synchronization on the signing
     * transaction commits a NEW saved customer email and a NEW store email on a raw auto-commit connection. It runs
     * inside the TransactionTemplate's commit: after the signing is durable, before either post-commit side
     * effect. The customer email still goes to the ORIGINAL trimmed saved email and the notification to the
     * ORIGINAL trimmed store email, although both committed values were already the moved ones when they were
     * sent.
     */
    @Test
    void remoteAccept_customerAndStoreEmailsMovedRightAfterTheSigningCommit_emailAndNotificationStillGoToTheRecipientsTheSigningTransactionCaptured()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        Assertions.assertEquals(List.of(SAVED_CUSTOMER_EMAIL),
                before.customers().stream().map(customer -> customer.get("email")).toList(),
                "setup: the committed saved email is the padded original, and the order has exactly one customer");
        Assertions.assertEquals(SAVED_STORE_EMAIL, before.store().get("email"),
                "setup: the committed store email is the padded original");
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
        EmailMoves moves = moveBothEmailsAfterSigningCommit(fixture);

        AcceptAttempt attempt = performPublicAccept(fixture);

        String label = "remote accept with both emails moved right after the signing commit";
        // ---- The hook ran exactly once, at the commit boundary: after the signing commit, before the sends ----
        Assertions.assertEquals(List.of(), moves.failures, label + ": the afterCommit moves themselves must not fail");
        Assertions.assertEquals(List.of(true), moves.hookInsideSigningTransaction,
                label + ": the hook was registered exactly once, from the signing insert, inside the signing transaction");
        Assertions.assertEquals(List.of(true), moves.autoCommit,
                label + ": the moves ran on a raw pooled connection in auto-commit (they commit on their own)");
        Assertions.assertEquals(List.of(1L), moves.signedVersionsCommittedAtMove,
                label + ": afterCommit ran after the signing committed (its own connection already sees the signed version)");
        Assertions.assertEquals(List.of(SAVED_CUSTOMER_EMAIL), moves.customerEmailsBeforeMove,
                label + ": the signing committed the saved customer email untouched");
        Assertions.assertEquals(List.of(SAVED_STORE_EMAIL), moves.storeEmailsBeforeMove,
                label + ": the signing committed the store email untouched");
        Assertions.assertEquals(List.of(1), moves.customerRowsMoved, label + ": exactly the one customer row moved");
        Assertions.assertEquals(List.of(1), moves.storeRowsMoved, label + ": exactly the order's store row moved");
        Assertions.assertEquals(List.of(MOVED_CUSTOMER_EMAIL), sends.committedCustomerEmails(),
                label + ": at send time the committed saved email was ALREADY the moved one");
        Assertions.assertEquals(List.of(MOVED_STORE_EMAIL), sends.committedStoreEmails(),
                label + ": at send time the committed store email was ALREADY the moved one");

        // ---- The full committed signing; only the two moved emails differ from the seed ----
        List<Map<String, Object>> expectedCustomers =
                withRowChanged(before.customers(), "order_id", fixture.orderId(), "email", MOVED_CUSTOMER_EMAIL);
        Map<String, Object> expectedStore = new LinkedHashMap<>(before.store());
        expectedStore.put("email", MOVED_STORE_EMAIL);
        assertCommittedSigning(fixture, before, expectedCustomers, expectedStore, writes, sends, attempt,
                CustomerOutcome.DELIVERED, NotificationOutcome.DELIVERED);

        // ---- Restated literally: the two recipients are the ORIGINAL trimmed values ----
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), label + ": exactly one signed-invoice email delivered");
        Assertions.assertEquals("rowan.acceptline@example.com", sent.get(0).recipientEmail(),
                label + ": the customer email went to the ORIGINAL saved email, trimmed");
        List<InvoiceAcceptanceNotificationRequest> notified = invoiceNotificationSender.sentNotifications();
        Assertions.assertEquals(1, notified.size(), label + ": exactly one store notification delivered");
        Assertions.assertEquals("accounts@iac-one.example.com", notified.get(0).recipientEmail(),
                label + ": the notification went to the ORIGINAL store email, trimmed");
        Assertions.assertEquals(MOVED_CUSTOMER_EMAIL, jdbcTemplate.queryForObject(
                        "SELECT email FROM order_customer WHERE order_id = ?", String.class, fixture.orderId()),
                label + ": the committed saved customer email is the moved value");
        Assertions.assertEquals(MOVED_STORE_EMAIL, jdbcTemplate.queryForObject(
                        "SELECT email FROM store WHERE store_id = ?", String.class, tenant.storeId()),
                label + ": the committed store email is the moved value");
    }

    // ================================================================
    // E. Before or at commit: nothing is committed, only the request's files are removed, nothing is sent
    // ================================================================

    /**
     * The signed-PDF render throws (after the signature file was written and its stored_file row inserted): the
     * generic 500; nothing committed; the written signature removed by BOTH cleanups; no PDF write; no email, no
     * notification, no WARN. The same link then signs normally.
     */
    @Test
    void remoteAccept_renderFailure_returnsGeneric500_commitsNothing_linkStaysActive_writtenSignatureDeletedTwice_nothingSent_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds(0);
        String marker = "injected signed invoice PDF render failure";
        doThrow(new UncheckedIOException("Failed to generate invoice PDF", new IOException(marker)))
                .when(invoicePdfGenerator).render(any());

        AcceptAttempt attempt = performPublicAccept(fixture);

        String label = "remote accept with a failing signed-PDF render";
        assertGeneric500(attempt.result(), label);
        verify(invoicePdfGenerator).render(any());
        Assertions.assertEquals(List.of("png"), writes.attemptedExtensions(),
                "only the signature file is written before the render; the PDF write is never attempted");
        Assertions.assertEquals(1, writes.writtenPaths().size(), "the signature file was written before the render");
        String signaturePath = writes.writtenPaths().get(0);
        Assertions.assertEquals(1, insertedStoredFileIds.size(), "the signature stored_file row was inserted in-transaction");
        verify(invoiceRepository).insertStoredFile(eq(signatureFileName(fixture)), eq(signaturePath),
                eq("image/png"), eq((long) NORMALISED_SIGNATURE_PNG.length));
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        // Both cleanups ran: the in-method catch AND the afterCompletion rollback hook.
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, null, attempt, marker, label);
        assertRetrySigns(fixture, before);
    }

    /**
     * The FIRST storage write - the signature file - throws. Nothing was written, so nothing needs deleting: the
     * generic 500; nothing committed; no render, no stored_file or invoice insert, no cleanup delete at all.
     */
    @Test
    void remoteAccept_signatureStorageFailure_returnsGeneric500_commitsNothing_linkStaysActive_writesAndDeletesNoFile_nothingSent_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites("png");
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds(0);

        AcceptAttempt attempt = performPublicAccept(fixture);

        String label = "remote accept with a failing signature file write";
        assertGeneric500(attempt.result(), label);
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
                "injected disk failure while writing the .png file", label);
        assertRetrySigns(fixture, before);
    }

    /**
     * The SECOND storage write - the signed PDF - throws, after the signature file was written, its row inserted
     * and the PDF rendered: the generic 500; nothing committed; the signature file deleted by both cleanups.
     */
    @Test
    void remoteAccept_signedPdfStorageFailure_returnsGeneric500_commitsNothing_linkStaysActive_writtenSignatureDeletedTwice_nothingSent_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites("pdf");
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds(0);

        AcceptAttempt attempt = performPublicAccept(fixture);

        String label = "remote accept with a failing signed-PDF write";
        assertGeneric500(attempt.result(), label);
        verify(invoicePdfGenerator).render(any());
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions(),
                "the signature write came first, the failing signed-PDF write second");
        Assertions.assertEquals(1, writes.writtenPaths().size(), "only the signature file was written");
        String signaturePath = writes.writtenPaths().get(0);
        Assertions.assertTrue(signaturePath.endsWith(".png"), "the written file is the signature: " + signaturePath);
        Assertions.assertEquals(1, insertedStoredFileIds.size(), "the signature stored_file row was inserted in-transaction");
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, null, attempt,
                "injected disk failure while writing the .pdf file", label);
        assertRetrySigns(fixture, before);
    }

    /**
     * The FIRST stored_file insert - the signature's row - throws, after the signature file was written: the
     * generic 500; nothing committed; no render; the signature file deleted by both cleanups.
     */
    @Test
    void remoteAccept_signatureStoredFileInsertFailure_returnsGeneric500_commitsNothing_linkStaysActive_writtenSignatureDeletedTwice_nothingSent_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds(1);

        AcceptAttempt attempt = performPublicAccept(fixture);

        String label = "remote accept with a failing signature stored_file insert";
        assertGeneric500(attempt.result(), label);
        Assertions.assertEquals(List.of("png"), writes.attemptedExtensions(), "only the signature file was written");
        Assertions.assertEquals(1, writes.writtenPaths().size());
        String signaturePath = writes.writtenPaths().get(0);
        verify(invoiceRepository, times(1)).insertStoredFile(eq(signatureFileName(fixture)), eq(signaturePath),
                eq("image/png"), eq((long) NORMALISED_SIGNATURE_PNG.length));
        verify(invoiceRepository, times(1)).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        Assertions.assertEquals(List.of(), insertedStoredFileIds, "the failing insert returned no row");
        verify(invoicePdfGenerator, never()).render(any());
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, null, attempt,
                "injected failure on stored_file insert #1", label);
        assertRetrySigns(fixture, before);
    }

    /**
     * The SECOND stored_file insert - the signed PDF's row - throws, after both files were written and the
     * signature row inserted: the generic 500; nothing committed; BOTH files deleted by both cleanups each.
     */
    @Test
    void remoteAccept_signedPdfStoredFileInsertFailure_returnsGeneric500_commitsNothing_linkStaysActive_bothWrittenFilesDeletedTwice_nothingSent_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds(2);

        AcceptAttempt attempt = performPublicAccept(fixture);

        String label = "remote accept with a failing signed-PDF stored_file insert";
        assertGeneric500(attempt.result(), label);
        verify(invoicePdfGenerator).render(any());
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(2, writes.writtenPaths().size(), "both files were written before the failing insert");
        String signaturePath = writes.writtenPaths().get(0);
        String pdfPath = writes.writtenPaths().get(1);
        verify(invoiceRepository).insertStoredFile(eq(invoicePdfName(fixture.orderNumber(), fixture.currentVersion() + 1)),
                eq(pdfPath), eq("application/pdf"), anyLong());
        verify(invoiceRepository, times(2)).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        Assertions.assertEquals(1, insertedStoredFileIds.size(), "the signature row was inserted in-transaction");
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(pdfPath);
        verify(fileStorageService, times(4)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, null, attempt,
                "injected failure on stored_file insert #2", label);
        assertRetrySigns(fixture, before);
    }

    /**
     * Both files are written and both stored_file rows inserted, then the invoice insert - reached for the
     * signing version with exactly the copied values, the frozen name, a signature reference, no marker and the
     * link SENDER as creator - throws: the generic 500; nothing committed; both files deleted twice each.
     */
    @Test
    void remoteAccept_invoiceInsertFailure_returnsGeneric500_commitsNothing_linkStaysActive_bothWrittenFilesDeletedTwice_nothingSent_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds(0);
        String marker = "injected failure on the signed invoice insert";
        insertInvoiceFor(doThrow(new DataIntegrityViolationException(marker)).when(invoiceRepository), fixture.orderId());

        AcceptAttempt attempt = performPublicAccept(fixture);

        String label = "remote accept with a failing invoice insert";
        assertGeneric500(attempt.result(), label);
        // The insert was really reached, for the signing version, with the current version's values copied.
        Map<String, Object> current = before.invoices().get(before.invoices().size() - 1);
        verify(invoiceRepository).insertInvoice(eq(fixture.orderId()), eq(fixture.currentVersion() + 1),
                eq(V1_INVOICE_DATE), eq(EXPECTED_DUE_DATE), eq(DETAILS_OF_SALE),
                eq((BigDecimal) current.get("sale_price_ex_gst")), eq((BigDecimal) current.get("sale_price_inc_gst")),
                eq((BigDecimal) current.get("total_paid")), eq((BigDecimal) current.get("balance_due")),
                anyLong(), eq(fixture.linkSenderUserId()),
                notNull(), eq(FROZEN_CUSTOMER_NAME), notNull(), isNull(),
                isNull(), eq((String) current.get("terms_snapshot")),
                eq(FROZEN_CUSTOMER_NAME), eq(BILLING_LINE_1), eq(BILLING_LINE_2));
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(2, writes.writtenPaths().size(), "both files were written before the insert");
        String signaturePath = writes.writtenPaths().get(0);
        String pdfPath = writes.writtenPaths().get(1);
        Assertions.assertEquals(2, insertedStoredFileIds.size(), "both stored_file rows were inserted in-transaction");
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(pdfPath);
        verify(fileStorageService, times(4)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, null, attempt, marker, label);
        assertRetrySigns(fixture, before);
    }

    /**
     * The signing persist's mirror reset ({@code updateSalesOrderLastEmailedAt(order, null)}) throws after the
     * invoice insert: the generic 500; nothing committed (the inserted version rolled back, the sentinel kept);
     * both files deleted twice each.
     */
    @Test
    void remoteAccept_mirrorResetFailure_returnsGeneric500_commitsNothing_sentinelKept_linkStaysActive_bothWrittenFilesDeletedTwice_nothingSent_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds(0);
        List<Long> inFlightInvoiceIds = recordInsertedInvoiceIds(fixture);
        String marker = "injected failure on the signing mirror reset";
        doThrow(new DataAccessResourceFailureException(marker))
                .when(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());

        AcceptAttempt attempt = performPublicAccept(fixture);

        String label = "remote accept with a failing mirror reset";
        assertGeneric500(attempt.result(), label);
        insertInvoiceFor(verify(invoiceRepository, times(1)), fixture.orderId());
        verify(invoiceRepository, times(1)).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
        Assertions.assertEquals(1, inFlightInvoiceIds.size(), "the signing version was inserted in-transaction");
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(2, writes.writtenPaths().size());
        String signaturePath = writes.writtenPaths().get(0);
        String pdfPath = writes.writtenPaths().get(1);
        Assertions.assertEquals(2, insertedStoredFileIds.size());
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(pdfPath);
        verify(fileStorageService, times(4)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, inFlightInvoiceIds.get(0), attempt,
                marker, label);
        assertRetrySigns(fixture, before);
    }

    /**
     * The token kill {@code ACTIVE -> SIGNED} is refused by an ordinary {@code BEFORE UPDATE ON invoice_token}
     * trigger, after the files, rows, invoice insert and mirror reset: the generic 500 without the trigger text;
     * nothing committed (the link still ACTIVE, dead_at null); both files deleted twice each.
     */
    @Test
    void remoteAccept_tokenKillFailure_nonDeferredInvoiceTokenTrigger_returnsGeneric500WithoutDetail_commitsNothing_linkStaysActive_bothFilesDeletedTwice_nothingSent_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds(0);
        List<Long> inFlightInvoiceIds = recordInsertedInvoiceIds(fixture);
        String trigger = TOKEN_KILL_TRIGGER_PREFIX + fixture.orderId();
        AcceptAttempt attempt;
        try {
            installTokenKillFailureTrigger(trigger, fixture.orderId());
            Assertions.assertEquals(1L, triggerCount("invoice_token", trigger),
                    "the token-kill failure trigger must be installed before the request");
            attempt = performPublicAccept(fixture);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice_token");
        }

        String label = "remote accept whose token kill fails";
        assertGeneric500(attempt.result(), label);
        // Every write before the kill ran: the files, both rows, the invoice insert and the mirror reset.
        Assertions.assertEquals(List.of("png", "pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(2, writes.writtenPaths().size());
        String signaturePath = writes.writtenPaths().get(0);
        String pdfPath = writes.writtenPaths().get(1);
        Assertions.assertEquals(2, insertedStoredFileIds.size());
        insertInvoiceFor(verify(invoiceRepository, times(1)), fixture.orderId());
        Assertions.assertEquals(1, inFlightInvoiceIds.size(), "the signing version was inserted in-transaction");
        verify(invoiceRepository, times(1)).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
        verify(fileStorageService, times(2)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(2)).deleteQuietly(pdfPath);
        verify(fileStorageService, times(4)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, inFlightInvoiceIds.get(0), attempt,
                TOKEN_KILL_FAILURE_TEXT, label);
        assertRetrySigns(fixture, before);
    }

    /**
     * Every in-transaction step succeeds - both files and rows, the invoice insert, the mirror reset, the token
     * kill AND the D5(c) cancellation, all observed in flight in the transaction's own beforeCommit - and the
     * transaction fails AT COMMIT (a deferred constraint trigger {@code AFTER INSERT ON invoice}). The generic
     * 500 without any database detail; nothing committed (the link ACTIVE again, the issued quote ISSUED and its
     * token ACTIVE); both files removed by the afterCompletion hooks ALONE (exactly one delete each).
     */
    @Test
    void remoteAccept_commitTimeFailure_deferredInvoiceInsertTrigger_returnsGeneric500WithoutDetail_tokenKillAndQuoteCancelRolledBack_hooksDeleteBothFilesOnce_nothingSent_retrySigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds(0);
        List<InFlightSigning> inFlight = probeSigningBeforeCommit(fixture);
        String trigger = INSERT_TRIGGER_PREFIX + fixture.orderId();
        AcceptAttempt attempt;
        try {
            installSigningCommitFailureTrigger(trigger, fixture.orderId());
            Assertions.assertEquals(1L, triggerCount("invoice", trigger),
                    "the insert commit-failure trigger must be installed before the request");
            attempt = performPublicAccept(fixture);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }

        String label = "remote accept failing at commit";
        assertGeneric500(attempt.result(), label);
        // Every in-transaction write ran (so the failure was AT COMMIT).
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
        Assertions.assertEquals(fixture.linkSenderUserId(), probe.invoiceCreatedByUserId(),
                "in flight, the appended version was created by the link sender");
        Assertions.assertEquals("SIGNED", probe.invoiceTokenStatus(),
                "in flight, the presented link had already died as SIGNED in this same transaction");
        Assertions.assertEquals(probe.invoiceAcceptedAt(), probe.invoiceTokenDeadAt(),
                "in flight, the link died at the signing time");
        Assertions.assertEquals("CANCELLED", probe.issuedQuoteStatus(),
                "in flight, D5(c) had already cancelled the ISSUED quote version in this same transaction");
        Assertions.assertEquals("CANCELLED", probe.issuedQuoteTokenStatus(),
                "in flight, D5(c) had already killed the ACTIVE quote token in this same transaction");
        Assertions.assertEquals(probe.invoiceAcceptedAt(), probe.issuedQuoteTokenDeadAt(),
                "in flight, the quote token died at the signing time");
        Assertions.assertEquals("ACCEPTED", probe.acceptedQuoteStatus(),
                "in flight, the ACCEPTED quote version was untouched (D5(d))");
        Assertions.assertNull(probe.mirror(), "in flight, the mirror had been reset to the new version's null");

        // Only the afterCompletion rollback hooks can have removed the files: the method body completed normally.
        verify(fileStorageService, times(1)).deleteQuietly(signaturePath);
        verify(fileStorageService, times(1)).deleteQuietly(pdfPath);
        verify(fileStorageService, times(2)).deleteQuietly(any());

        assertNothingCommitted(fixture, before, writes, insertedStoredFileIds, probe.invoiceId(), attempt,
                COMMIT_FAILURE_TEXT, label);
        assertRetrySigns(fixture, before);
    }

    // ================================================================
    // F. Refusals send nothing: a stale version (409) and a dead link (410)
    // ================================================================

    /**
     * A payment after the page loaded appended v2 (the link survives a payment): an accept for the v1 the page
     * showed is refused under the lock with the exact 409, before any file write - nothing written, no email, no
     * notification, no WARN, the link still ACTIVE showing v2. Re-read and signed for v2, it commits normally.
     */
    @Test
    void remoteAccept_staleExpectedVersionAfterAPaymentVersion_returns409_writesNothing_sendsNoEmailAndNoNotification_thenTheReReadVersionSigns()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        MvcResult payment = recordPayment(fixture.orderId(), PAYMENT_AMOUNT);
        assertStatus(payment, 201, "setup: a payment recorded after the page loaded");
        Assertions.assertEquals(PAYMENT_RECORDED_MESSAGE, readJson(payment).path("message").asText());
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(fixture.orderId()), "setup: the payment appended v2");
        Assertions.assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                        "SELECT status FROM invoice_token WHERE invoice_token_id = ?", String.class, fixture.invoiceTokenId()),
                "setup: a payment never kills the invoice link");
        resetSenders();
        clearSpyInvocations();
        CommittedState before = committedState(fixture);

        AcceptAttempt stale = performPublicAccept(fixture.invoiceToken(), fixture.currentVersion());

        String label = "remote accept for the version the page showed, after a payment version";
        assertRefusedWithoutSideEffects(fixture, before, stale, 409, STALE_BODY, label);
        assertPublicInvoiceActive(fixture.invoiceToken(), 2, label + ": the link still works and shows v2");
        assertPublicQuoteState(fixture.issuedQuoteToken(), "ACTIVE", label + ": the quote link is not cancelled");

        // The page re-reads (v2) and the customer signs again: the full committed signing of v3.
        Map<String, Object> v2 = row("SELECT * FROM invoice WHERE order_id = ? AND version_number = 2", fixture.orderId());
        ProtectedFile v2Pdf = protectedFile("the unsigned payment version v2 PDF", toLong(v2.get("stored_file_id")));
        SignableOrder afterPayment = fixture.withCurrentVersion(2, v2Pdf);
        clearSpyInvocations();
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(afterPayment, false);
        AcceptAttempt retry = performPublicAccept(afterPayment);
        assertCommittedSigning(afterPayment, before, writes, sends, retry,
                CustomerOutcome.DELIVERED, NotificationOutcome.DELIVERED);
    }

    /**
     * Accept Remotely was used again (by {@value #ORDER_USER_CODE}): the first link is REPLACED. An accept through
     * it gets the exact 410 at the token gate - nothing written, no email, no notification, no WARN. The newer
     * link then signs, and its own sender becomes the creator of the signed version.
     */
    @Test
    void remoteAccept_replacedLink_returns410Replaced_writesNothing_sendsNoEmailAndNoNotification_thenTheNewerLinkSignsWithItsOwnSenderAsCreator()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        SentLink newer = sendInvoiceLink(fixture.orderId(), fixture.orderNumber(), tenant.orderUserSession(),
                tenant.orderUserId(), fixture.currentVersion());
        Map<String, Object> replaced = row("SELECT * FROM invoice_token WHERE invoice_token_id = ?", fixture.invoiceTokenId());
        Assertions.assertEquals("REPLACED", replaced.get("status"), "setup: the first link is REPLACED");
        Assertions.assertEquals(Timestamp.valueOf(newer.sentAt()), replaced.get("dead_at"),
                "setup: the first link died at the newer send");
        resetSenders();
        clearSpyInvocations();
        CommittedState before = committedState(fixture);

        AcceptAttempt dead = performPublicAccept(fixture.invoiceToken(), fixture.currentVersion());

        String label = "remote accept through a replaced link";
        assertRefusedWithoutSideEffects(fixture, before, dead, 410, LINK_REPLACED_BODY, label);
        assertPublicInvoiceDead(fixture.invoiceToken(), "REPLACED", REPLACED_STATE_MESSAGE, label);
        assertPublicQuoteState(fixture.issuedQuoteToken(), "ACTIVE", label + ": the quote link is not cancelled");

        SignableOrder viaNewer = fixture.viaLink(newer, tenant.orderUserId());
        clearSpyInvocations();
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(viaNewer, false);
        AcceptAttempt retry = performPublicAccept(viaNewer);
        assertCommittedSigning(viaNewer, before, writes, sends, retry,
                CustomerOutcome.DELIVERED, NotificationOutcome.DELIVERED);
        Assertions.assertEquals("REPLACED", jdbcTemplate.queryForObject(
                        "SELECT status FROM invoice_token WHERE invoice_token_id = ?", String.class, fixture.invoiceTokenId()),
                "a dead link never changes status again");
    }

    /**
     * After a committed remote signing the link is dead: the same accept again gets the exact 410
     * {@code INVOICE_LINK_SIGNED} at the token gate - no second version, no second email, no second notification,
     * no WARN.
     */
    @Test
    void remoteAccept_repeatedAfterACommittedSigning_returns410Signed_writesNothing_sendsNoSecondEmailOrNotification()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
        AcceptAttempt first = performPublicAccept(fixture);
        assertCommittedSigning(fixture, before, writes, sends, first,
                CustomerOutcome.DELIVERED, NotificationOutcome.DELIVERED);
        resetSenders();
        Mockito.reset(invoicePdfGenerator, fileStorageService, invoiceRepository, invoiceEmailSender);
        CommittedState afterFirst = committedState(fixture);

        AcceptAttempt again = performPublicAccept(fixture);

        String label = "the same remote accept repeated after a committed signing";
        assertRefusedWithoutSideEffects(fixture, afterFirst, again, 410, LINK_SIGNED_BODY, label);
        assertPublicInvoiceDead(fixture.invoiceToken(), "SIGNED", SIGNED_STATE_MESSAGE, label);
    }

    /**
     * Send-link captures its recipient inside its own transaction (16G contract §8, step 8: the link email goes
     * to "the saved customer email" read under the order lock). Here the saved customer email is moved right
     * AFTER the send transaction commits and BEFORE the delivery - from an afterCommit hook registered inside
     * that transaction, on a raw auto-commit connection. The link email still goes to the trimmed email the
     * send transaction read, never to the moved one; the previous link is REPLACED, the new one ACTIVE and its
     * delivery stamped; no invoice email, invoice row or mirror moves.
     */
    @Test
    void sendLink_customerEmailMovedRightAfterTheSendCommit_linkEmailStillGoesToTheRecipientTheSendTransactionCaptured()
            throws Exception {
        SignableOrder fixture = seedSignableOrder();
        String label = "send-link with the saved customer email moved right after its commit";
        Map<String, Object> mirrorBefore = row("SELECT last_emailed_at FROM sales_order WHERE order_id = ?", fixture.orderId());
        List<Map<String, Object>> invoicesBefore =
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", fixture.orderId());

        AtomicBoolean armed = new AtomicBoolean(true);
        List<Boolean> hookInsideSendTransaction = new CopyOnWriteArrayList<>();
        List<Integer> customerRowsMoved = new CopyOnWriteArrayList<>();
        List<String> moveFailures = new CopyOnWriteArrayList<>();
        // The send transaction's current-invoice read (after its scoped lock and body check) registers the move.
        doAnswer(invocation -> {
            if (armed.compareAndSet(true, false)) {
                hookInsideSendTransaction.add(TransactionSynchronizationManager.isActualTransactionActive()
                        && TransactionSynchronizationManager.isSynchronizationActive());
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try (Connection connection = dataSource.getConnection();
                             PreparedStatement move = connection.prepareStatement(
                                     "UPDATE order_customer SET email = ? WHERE order_id = ?")) {
                            move.setQueryTimeout(30);
                            move.setString(1, MOVED_CUSTOMER_EMAIL);
                            move.setLong(2, fixture.orderId());
                            customerRowsMoved.add(move.executeUpdate());
                        } catch (SQLException | RuntimeException ex) {
                            moveFailures.add(ex.toString());
                        }
                    }
                });
            }
            return invocation.callRealMethod();
        }).when(invoiceRepository).findCurrentByOrderId(eq(fixture.orderId()));

        MvcResult result;
        try {
            result = mockMvc.perform(post(orderUrl(fixture.orderId()) + "/invoices/current/send-link")
                            .session(tenant.linkSenderSession())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();
        } finally {
            Mockito.reset(invoiceRepository);
        }

        assertStatus(result, 201, label);
        Assertions.assertEquals(LINK_SENT_MESSAGE, readJson(result).path("message").asText(), label + ": message");
        Assertions.assertEquals(List.of(true), hookInsideSendTransaction,
                label + ": the move was registered from inside the send transaction");
        Assertions.assertEquals(List.of(), moveFailures, label + ": the post-commit move itself succeeded");
        Assertions.assertEquals(List.of(1), customerRowsMoved, label + ": the saved email was moved after the commit");
        Assertions.assertEquals(MOVED_CUSTOMER_EMAIL, jdbcTemplate.queryForObject(
                        "SELECT email FROM order_customer WHERE order_id = ?", String.class, fixture.orderId()),
                label + ": the move is committed (it ran before the delivery)");

        List<InvoiceLinkEmailRequest> sent = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), label + ": exactly one link email");
        Assertions.assertTrue(invoiceLinkEmailSender.failedEmails().isEmpty(), label + ": no failed link email");
        Assertions.assertEquals(CUSTOMER_EMAIL, sent.get(0).recipientEmail(),
                label + ": the link email goes to the trimmed email the send transaction read, not the moved one");
        Matcher matcher = INVOICE_LINK_PATTERN.matcher(sent.get(0).bodyText());
        Assertions.assertTrue(matcher.find(), label + ": the link email carries the public link");
        String newToken = matcher.group(1);

        Map<String, Object> oldLink = row("SELECT * FROM invoice_token WHERE invoice_token_id = ?", fixture.invoiceTokenId());
        Assertions.assertEquals("REPLACED", oldLink.get("status"), label + ": the previous link was replaced");
        Map<String, Object> newLink = row("SELECT * FROM invoice_token WHERE token_hash = ?", sha256Hex(newToken));
        Assertions.assertEquals("ACTIVE", newLink.get("status"), label + ": the new link is ACTIVE");
        Assertions.assertNotNull(newLink.get("emailed_at"), label + ": the new link's delivery was stamped");
        Assertions.assertEquals(oldLink.get("dead_at"), newLink.get("sent_at"),
                label + ": the previous link died at the new send's time");

        Assertions.assertEquals(invoicesBefore,
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", fixture.orderId()),
                label + ": a link send changes no invoice row or marker");
        Assertions.assertEquals(mirrorBefore,
                row("SELECT last_emailed_at FROM sales_order WHERE order_id = ?", fixture.orderId()),
                label + ": a link send never moves the dashboard mirror");
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), label + ": no signed-PDF email");
        Assertions.assertTrue(invoiceNotificationSender.sentNotifications().isEmpty(), label + ": no store notification");
    }

    // ================================================================
    // Shared assertions - a committed signing
    // ================================================================

    /** The post-commit outcome of the customer's signed-PDF email. */
    private enum CustomerOutcome {
        /** Delivered and stamped. */
        DELIVERED(true, true, true),
        /** The saved email is app-invalid: the send is skipped. */
        SKIPPED_INVALID_EMAIL(false, false, false),
        /** No saved customer row: the send is skipped. */
        SKIPPED_NO_CUSTOMER(false, false, false),
        /** The transport threw InvoiceEmailException (failNextSend). */
        TRANSPORT_FAILED(true, false, false),
        /** The sender threw another runtime exception. */
        RUNTIME_FAILED(true, false, false),
        /** Delivered, then the stamp transaction failed (before a write, after a write, or at commit). */
        STAMP_FAILED(true, true, false);

        private final boolean attempted;
        private final boolean delivered;
        private final boolean stamped;

        CustomerOutcome(boolean attempted, boolean delivered, boolean stamped) {
            this.attempted = attempted;
            this.delivered = delivered;
            this.stamped = stamped;
        }
    }

    /** The post-commit outcome of the store notification. */
    private enum NotificationOutcome {
        DELIVERED,
        /** The order's store has no email: skipped. */
        SKIPPED_NO_STORE_EMAIL,
        /** The transport threw InvoiceAcceptanceNotificationException (failNextSend). */
        TRANSPORT_FAILED,
        /** The sender threw another runtime exception (failNextSendWith). */
        RUNTIME_FAILED
    }

    private long assertCommittedSigning(SignableOrder fixture, CommittedState before, StorageWrites writes,
                                        SendObservations sends, AcceptAttempt attempt,
                                        CustomerOutcome customer, NotificationOutcome notification) throws Exception {
        return assertCommittedSigning(fixture, before, before.customers(), before.store(), writes, sends, attempt,
                customer, notification);
    }

    /**
     * Everything a COMMITTED remote signing must leave behind, for the given post-commit outcomes, relative to the
     * committed state right before the request. The order_customer rows and the store row expected afterwards are
     * given explicitly: they differ from {@code before} only when the test itself committed a change after the
     * signing commit (section D); the signing never writes them. Returns the appended (signed) invoice id.
     */
    private long assertCommittedSigning(SignableOrder fixture, CommittedState before,
                                        List<Map<String, Object>> expectedCustomers, Map<String, Object> expectedStore,
                                        StorageWrites writes, SendObservations sends, AcceptAttempt attempt,
                                        CustomerOutcome customer, NotificationOutcome notification) throws Exception {
        String label = "remote accept (customer email " + customer + ", store notification " + notification + ")";
        long orderId = fixture.orderId();
        int version = fixture.currentVersion() + 1;

        // ---- The public response: exactly the fixed 201, whatever happened after the commit ----
        assertPublicAccepted(attempt.result(), label);

        // ---- The committed signing: exactly one appended version, carrying the current one forward ----
        CommittedState after = committedState(fixture);
        int previous = before.invoices().size();
        Assertions.assertEquals(previous + 1, after.invoices().size(), label + ": exactly one invoice version committed");
        Assertions.assertEquals(before.invoices(), after.invoices().subList(0, previous),
                label + ": every earlier invoice row is untouched");
        Map<String, Object> current = before.invoices().get(previous - 1);
        Assertions.assertEquals(fixture.currentVersion(), toInt(current.get("version_number")),
                label + ": setup - the version the page showed is the current one");
        Assertions.assertNull(current.get("accepted_at"), label + ": setup - the current version is unsigned");
        Assertions.assertNotNull(current.get("terms_snapshot"),
                label + ": setup - the current version carries frozen terms, so their copy is observable");
        Map<String, Object> signed = after.invoices().get(previous);
        long signedInvoiceId = toLong(signed.get("invoice_id"));
        Assertions.assertEquals(version, toInt(signed.get("version_number")), label + ": version = current + 1");
        for (String carried : CARRIED_COLUMNS) {
            Assertions.assertEquals(current.get(carried), signed.get(carried),
                    label + ": " + carried + " is copied verbatim from the current version");
        }
        LocalDate currentInvoiceDate = ((Date) current.get("invoice_date")).toLocalDate();
        if (fixture.currentVersion() == 1) {
            Assertions.assertEquals(V1_INVOICE_DATE, ((Date) signed.get("invoice_date")).toLocalDate(),
                    label + ": invoice_date is v1's earlier date (copied), not the signing day");
        }
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
                label + ": the accepted name IS the frozen customer_name_snapshot, verbatim");
        Map<String, Object> presentedTokenBefore =
                findRow(before.invoiceTokens(), "invoice_token_id", fixture.invoiceTokenId());
        Assertions.assertEquals("ACTIVE", presentedTokenBefore.get("status"), label + ": setup - the presented link is ACTIVE");
        Assertions.assertEquals(toLong(presentedTokenBefore.get("sent_by_user_id")), toLong(signed.get("created_by_user_id")),
                label + ": created_by_user_id = invoice_token.sent_by_user_id of the presented link");
        Assertions.assertEquals(fixture.linkSenderUserId(), toLong(signed.get("created_by_user_id")),
                label + ": the creator is the staff member who sent the link");
        Long signatureFileId = toLong(signed.get("accepted_signature_file_id"));
        Long pdfFileId = toLong(signed.get("stored_file_id"));
        Assertions.assertNotNull(signatureFileId, label + ": the signature is referenced");
        Assertions.assertNotNull(pdfFileId, label + ": the signed PDF is referenced");

        // ---- The markers: set together by a committed stamp, otherwise both null - the customer outcome only ----
        Timestamp invoiceMarker = (Timestamp) signed.get("last_emailed_at");
        Timestamp mirrorMarker = (Timestamp) after.salesOrder().get("last_emailed_at");
        if (customer.stamped) {
            Assertions.assertNotNull(invoiceMarker, label + ": invoice.last_emailed_at is stamped");
            Assertions.assertEquals(invoiceMarker, mirrorMarker,
                    label + ": the sales_order mirror carries the SAME timestamp as the invoice");
            Assertions.assertFalse(invoiceMarker.before(acceptedAt), label + ": stamped after the signing");
        } else {
            Assertions.assertNull(invoiceMarker, label + ": invoice.last_emailed_at stays null");
            Assertions.assertNull(mirrorMarker,
                    label + ": the mirror stays null (the persist's reset committed; nothing of a stamp did)");
        }
        Map<String, Object> expectedOrder = new LinkedHashMap<>(before.salesOrder());
        expectedOrder.put("last_emailed_at", customer.stamped ? invoiceMarker : null);
        Assertions.assertEquals(expectedOrder, after.salesOrder(),
                label + ": the signing writes only sales_order.last_emailed_at (no price, status or other order write)");

        // ---- The files: the normalised signature and the signed PDF, rows and bytes, both committed ----
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
        Assertions.assertEquals(signatureFileName(fixture), signatureRow.get("file_name"), label + ": signature file name");
        Assertions.assertEquals("image/png", signatureRow.get("mime_type"), label + ": signature mime type");
        Assertions.assertEquals(signaturePath, signatureRow.get("storage_path"), label + ": the committed signature file");
        Assertions.assertEquals((long) NORMALISED_SIGNATURE_PNG.length, toLong(signatureRow.get("file_size")),
                label + ": stored_file.file_size is the normalised signature's length");
        Assertions.assertEquals(pdfFileId, toLong(pdfRow.get("stored_file_id")), label + ": signed PDF row");
        Assertions.assertEquals(invoicePdfName(fixture.orderNumber(), version), pdfRow.get("file_name"),
                label + ": signed PDF file name");
        Assertions.assertEquals("application/pdf", pdfRow.get("mime_type"), label + ": signed PDF mime type");
        Assertions.assertEquals(pdfPath, pdfRow.get("storage_path"), label + ": the committed signed PDF file");
        byte[] storedSignature = Files.readAllBytes(physicalPath(signaturePath));
        Assertions.assertArrayEquals(NORMALISED_SIGNATURE_PNG, storedSignature,
                label + ": the stored signature is the server-normalised PNG");
        assertSamePixels(SIGNATURE_PNG, storedSignature, label + ": the stored signature");
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

        // ---- The presented link died as SIGNED at the signing time; no other link changed ----
        Assertions.assertEquals(
                withRowChanged(before.invoiceTokens(), "invoice_token_id", fixture.invoiceTokenId(),
                        "status", "SIGNED", "dead_at", acceptedAt),
                after.invoiceTokens(), label + ": ONLY the presented link changed: ACTIVE -> SIGNED, dead_at = accepted_at");

        // ---- D5(c): the ISSUED quote version and its ACTIVE token cancelled; the ACCEPTED quote untouched ----
        Assertions.assertEquals(
                withRowChanged(before.quoteVersions(), "quote_version_id", fixture.issuedQuoteVersionId(),
                        "status", "CANCELLED"),
                after.quoteVersions(), label + ": D5(c) changed ONLY the ISSUED version's status (the ACCEPTED one stays)");
        Assertions.assertEquals(
                withRowChanged(before.quoteTokens(), "quote_token_id", fixture.issuedQuoteTokenId(),
                        "status", "CANCELLED", "dead_at", acceptedAt),
                after.quoteTokens(), label + ": D5(c) killed ONLY the ACTIVE quote token, at the signing time");
        Assertions.assertEquals("ACCEPTED", findRow(after.quoteVersions(), "quote_version_id",
                fixture.acceptedQuoteVersionId()).get("status"), label + ": the ACCEPTED quote is never revoked");

        // ---- Nothing else ----
        Assertions.assertEquals(before.quoteVersionLines(), after.quoteVersionLines(), label + ": quote version lines");
        Assertions.assertEquals(before.quoteDraft(), after.quoteDraft(), label + ": quote draft");
        Assertions.assertEquals(before.quoteDraftLines(), after.quoteDraftLines(), label + ": quote draft lines");
        Assertions.assertEquals(before.payments(), after.payments(), label + ": payments");
        Assertions.assertEquals(expectedCustomers, after.customers(), label + ": customer");
        Assertions.assertEquals(before.addresses(), after.addresses(), label + ": addresses");
        Assertions.assertEquals(before.chargeLines(), after.chargeLines(), label + ": charge lines");
        Assertions.assertEquals(before.productLines(), after.productLines(), label + ": product lines");
        Assertions.assertEquals(before.business(), after.business(), label + ": business row");
        Assertions.assertEquals(expectedStore, after.store(), label + ": store row");

        // ---- The persist's mirror reset and the stamp's writes ----
        verify(invoiceRepository, times(1)).updateSalesOrderLastEmailedAt(eq(orderId), isNull());
        if (customer == CustomerOutcome.DELIVERED) {
            verify(invoiceRepository, times(1)).updateInvoiceLastEmailedAt(eq(signedInvoiceId), notNull());
            verify(invoiceRepository, times(1)).updateSalesOrderLastEmailedAt(eq(orderId), notNull());
        } else if (!customer.delivered) {
            verify(invoiceRepository, never()).updateInvoiceLastEmailedAt(anyLong(), any());
            verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(orderId), notNull());
        }

        // ---- The customer email: strictly after the commit, with this version's stored PDF ----
        if (!customer.attempted) {
            verify(invoiceEmailSender, never()).send(any());
            Assertions.assertEquals(0, sends.size(), label + ": the customer send was never attempted");
            Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), label + ": nothing delivered");
            Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), label + ": nothing reached the transport");
        } else {
            ArgumentCaptor<InvoiceEmailRequest> captor = ArgumentCaptor.forClass(InvoiceEmailRequest.class);
            verify(invoiceEmailSender, times(1)).send(captor.capture());
            InvoiceEmailRequest email = captor.getValue();
            assertSignedInvoiceEmail(email, fixture, version, storedPdf, label);
            Assertions.assertEquals(1, sends.size(), label + ": exactly one customer send observed");
            Assertions.assertFalse(sends.transactionActive().get(0),
                    label + ": the customer email is sent outside any transaction (after the signing commit)");
            Assertions.assertEquals(1L, sends.committedSignedVersions().get(0),
                    label + ": at send time the signed version was already committed (visible to another connection)");
            Assertions.assertEquals("SIGNED", sends.committedInvoiceTokenStatus().get(0),
                    label + ": at send time the SIGNED link was already committed");
            Assertions.assertEquals("CANCELLED", sends.committedQuoteTokenStatus().get(0),
                    label + ": at send time the D5(c) quote-link cancellation was already committed");
            switch (customer) {
                case DELIVERED, STAMP_FAILED -> {
                    Assertions.assertEquals(1, invoiceEmailSender.sentEmails().size(), label + ": delivered once");
                    Assertions.assertSame(email, invoiceEmailSender.sentEmails().get(0), label + ": the delivered request");
                    Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), label + ": no failed attempt");
                }
                case TRANSPORT_FAILED -> {
                    Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), label + ": nothing delivered");
                    Assertions.assertEquals(1, invoiceEmailSender.failedEmails().size(), label + ": one failed attempt");
                    Assertions.assertSame(email, invoiceEmailSender.failedEmails().get(0), label + ": the failed request");
                }
                case RUNTIME_FAILED -> {
                    Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), label + ": nothing delivered");
                    Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(),
                            label + ": the non-transport failure never reached the recording transport");
                }
                default -> Assertions.fail("unexpected customer outcome " + customer);
            }
        }

        // ---- The store notification: the exact request, after the commit, never a marker ----
        InvoiceAcceptanceNotificationRequest expectedNotification = new InvoiceAcceptanceNotificationRequest(
                STORE_EMAIL,
                "Invoice accepted: " + fixture.orderNumber(),
                "Invoice " + fixture.orderNumber() + " (version " + version + ") was accepted online by "
                        + FROZEN_CUSTOMER_NAME + " on " + DISPLAY_DATE_TIME.format(acceptedAtTime) + ".\n\n"
                        + "Invoice total (inc GST): " + INVOICE_INC_GST_DISPLAY + ".\n\n"
                        + "The signed invoice is available in the sales portal.",
                orderId,
                version);
        switch (notification) {
            case DELIVERED -> {
                Assertions.assertEquals(List.of(expectedNotification), invoiceNotificationSender.sentNotifications(),
                        label + ": exactly the expected store notification was delivered");
                Assertions.assertEquals(List.of(), invoiceNotificationSender.failedNotifications(), label + ": no failed notification");
                Assertions.assertEquals(1, attempt.notificationSends().size(), label + ": the delivery was observed once");
                NotificationSend observed = attempt.notificationSends().get(0);
                Assertions.assertFalse(observed.transactionActive(),
                        label + ": the store notification is sent outside any transaction");
                Assertions.assertEquals(1L, observed.committedSignedVersions(),
                        label + ": at notification time the signed version was already committed");
            }
            case SKIPPED_NO_STORE_EMAIL -> {
                Assertions.assertEquals(List.of(), invoiceNotificationSender.sentNotifications(), label + ": nothing notified");
                Assertions.assertEquals(List.of(), invoiceNotificationSender.failedNotifications(),
                        label + ": nothing reached the notification sender (no fallback recipient)");
                Assertions.assertEquals(List.of(), attempt.notificationSends(), label + ": no delivery observed");
            }
            case TRANSPORT_FAILED, RUNTIME_FAILED -> {
                Assertions.assertEquals(List.of(), invoiceNotificationSender.sentNotifications(), label + ": nothing delivered");
                Assertions.assertEquals(List.of(expectedNotification), invoiceNotificationSender.failedNotifications(),
                        label + ": the failed attempt was exactly the expected request");
                Assertions.assertEquals(List.of(), attempt.notificationSends(), label + ": no delivery observed");
            }
            default -> Assertions.fail("unexpected notification outcome " + notification);
        }
        for (InvoiceAcceptanceNotificationRequest request : concat(invoiceNotificationSender.sentNotifications(),
                invoiceNotificationSender.failedNotifications())) {
            String text = request.subject() + "\n" + request.bodyText();
            for (String leak : List.of(fixture.invoiceToken(), "/i/", "http", CUSTOMER_EMAIL, "40.00", "GP")) {
                Assertions.assertFalse(text.contains(leak), label + ": the store notification never carries '" + leak + "'");
            }
        }

        // ---- The signed PDF: the frozen name, the signing time, the copied dates and terms, the signature ----
        String flatText = noSpace(pdfText(storedPdf));
        // The caption's inline spans are emitted out of content-stream order: read it position-sorted.
        String caption = noSpace("Accepted by " + FROZEN_CUSTOMER_NAME + " on " + DISPLAY_DATE_TIME.format(acceptedAtTime));
        Assertions.assertTrue(noSpace(pdfTextSortedByPosition(storedPdf)).contains(caption),
                label + ": the signed PDF carries the caption '" + caption + "'");
        Assertions.assertTrue(flatText.contains(DISPLAY_DATE.format(currentInvoiceDate)),
                label + ": the signed PDF shows the copied invoice date " + DISPLAY_DATE.format(currentInvoiceDate));
        Assertions.assertTrue(flatText.contains(DUE_DATE_DISPLAY), label + ": the signed PDF shows the due date");
        Assertions.assertTrue(flatText.contains(noSpace(fixture.orderNumber())), label + ": the PDF names the order");
        Assertions.assertTrue(flatText.contains(noSpace(TERMS_TEXT)), label + ": the copied frozen terms are rendered");
        Assertions.assertFalse(flatText.contains(noSpace("Customer signature")),
                label + ": a signed invoice never shows the blank-signature caption");
        Assertions.assertEquals(1, countOccurrences(flatText, noSpace(FOOTER_TEXT)), label + ": the footer renders once");
        Assertions.assertEquals(countImages(fixture.currentPdfBytes()) + 1, countImages(storedPdf),
                label + ": the signature is embedded as exactly one extra image vs the unsigned current version");

        // ---- The WARN lines: only the PublicInvoiceService logger, order id + version only ----
        List<String> expectedWarns = new ArrayList<>();
        String customerWarn = customerWarn(customer, orderId, version);
        if (customerWarn != null) {
            expectedWarns.add(customerWarn);
        }
        String notificationWarn = notificationWarn(notification, orderId, version);
        if (notificationWarn != null) {
            expectedWarns.add(notificationWarn);
        }
        assertServiceWarns(attempt.publicLog(), expectedWarns, fixture, version, label);
        Assertions.assertEquals(List.of(), describe(attempt.staffLog()),
                label + ": the remote accept never logs on the OrderInvoiceService logger");
        Assertions.assertEquals(List.of(), describe(attempt.errorLog()), label + ": no unhandled exception");

        // ---- Nothing else: no link or quote mail, earlier files intact, both links dead publicly ----
        assertNoLinkOrQuoteMail(label);
        assertProtectedFilesIntact(fixture);
        assertPublicInvoiceDead(fixture.invoiceToken(), "SIGNED", SIGNED_STATE_MESSAGE, label);
        assertPublicQuoteState(fixture.issuedQuoteToken(), "CANCELLED", label);
        return signedInvoiceId;
    }

    /** The post-commit email: the TRIMMED saved address, the fixed wording, this version's own stored PDF, no link. */
    private static void assertSignedInvoiceEmail(InvoiceEmailRequest email, SignableOrder fixture, int version,
                                                 byte[] storedPdf, String label) {
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(),
                label + ": the recipient is the TRIMMED saved customer email the signing transaction read");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), label + ": subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + fixture.orderNumber() + " from " + BUSINESS_NAME
                + " attached.\n\nThank you.", email.bodyText(), label + ": body");
        Assertions.assertArrayEquals(storedPdf, email.pdfBytes(),
                label + ": the emailed PDF is byte-identical to the appended version's stored file");
        Assertions.assertEquals(invoicePdfName(fixture.orderNumber(), version), email.pdfFileName(), label + ": file name");
        Assertions.assertEquals(fixture.orderId(), email.orderId(), label + ": order id");
        Assertions.assertEquals(version, email.invoiceVersionNumber(), label + ": the appended version");
        Assertions.assertFalse(email.bodyText().contains(fixture.invoiceToken()) || email.bodyText().contains("/i/"),
                label + ": the signed-PDF email never carries the link");
    }

    private static String customerWarn(CustomerOutcome outcome, long orderId, int version) {
        return switch (outcome) {
            case DELIVERED -> null;
            case SKIPPED_INVALID_EMAIL, SKIPPED_NO_CUSTOMER -> "Signed invoice email skipped for order " + orderId
                    + " invoice v" + version + ": no valid saved customer email (non-fatal; markers stay null)";
            case TRANSPORT_FAILED, RUNTIME_FAILED -> "Signed invoice email failed for order " + orderId + " invoice v"
                    + version + " (non-fatal; the committed invoice stands, markers stay null)";
            case STAMP_FAILED -> "Email-success stamp failed for order " + orderId + " invoice v" + version
                    + " after delivery (non-fatal; markers stay null)";
        };
    }

    private static String notificationWarn(NotificationOutcome outcome, long orderId, int version) {
        return switch (outcome) {
            case DELIVERED -> null;
            case SKIPPED_NO_STORE_EMAIL -> "Invoice acceptance store notification skipped: no store email for the "
                    + "order's store (order " + orderId + " invoice v" + version + "; signing persisted)";
            case TRANSPORT_FAILED, RUNTIME_FAILED -> "Invoice acceptance store notification failed (order " + orderId
                    + " invoice v" + version + "; signing persisted)";
        };
    }

    /**
     * Exactly the expected PublicInvoiceService events (order-independent: the two side effects are independent),
     * each a WARN with the order id and the version as its only arguments, no throwable, and never an address,
     * the token, its hash or the link.
     */
    private static void assertServiceWarns(List<ILoggingEvent> events, List<String> expectedMessages,
                                           SignableOrder fixture, int version, String label) {
        List<String> actual = describe(events).stream().sorted().toList();
        List<String> expected = expectedMessages.stream().map(message -> Level.WARN + " " + message).sorted().toList();
        Assertions.assertEquals(expected, actual, label + ": exactly the expected PublicInvoiceService WARN lines");
        for (ILoggingEvent event : events) {
            String message = event.getFormattedMessage();
            Assertions.assertEquals(Level.WARN, event.getLevel(), label + ": logged at WARN: " + message);
            Assertions.assertNull(event.getThrowableProxy(), label + ": the WARN carries no throwable: " + message);
            Object[] arguments = event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray();
            Assertions.assertEquals(List.of(String.valueOf(fixture.orderId()), String.valueOf(version)),
                    Arrays.stream(arguments).map(String::valueOf).toList(),
                    label + ": the WARN's only arguments are the order id and the version: " + message);
            for (String leak : List.of("@", fixture.invoiceToken(), sha256Hex(fixture.invoiceToken()), "/i/",
                    PUBLIC_BASE_URL)) {
                Assertions.assertFalse(message.contains(leak), label + ": the WARN never carries '" + leak + "': " + message);
            }
        }
    }

    private static List<String> describe(List<ILoggingEvent> events) {
        return events.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
    }

    // ================================================================
    // Shared assertions - a refused or failed signing
    // ================================================================

    /** 201 with EXACTLY the fixed public body: no id, amount, name, timestamp, file reference, token or outcome. */
    private static void assertPublicAccepted(MvcResult result, String label) throws Exception {
        assertStatus(result, 201, label);
        String contentType = result.getResponse().getContentType();
        Assertions.assertNotNull(contentType, label + ": the accept answers with a JSON body");
        Assertions.assertTrue(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON),
                label + ": the accept answers with JSON, was " + contentType);
        Assertions.assertEquals(JSON.readTree(PUBLIC_ACCEPTED_BODY), readJson(result),
                label + ": the 201 body is exactly the fixed one, whatever the email and notification outcomes");
    }

    /** 500 with EXACTLY the generic error envelope, and none of the injected fault's or the database's text. */
    private static void assertGeneric500(MvcResult result, String label) throws Exception {
        assertStatus(result, 500, label);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertEquals(JSON.readTree(GENERIC_500_BODY), JSON.readTree(body),
                label + ": the 500 body must be exactly the generic error envelope, but was " + body);
        for (String leak : List.of("iacmt", "P0001", "trigger", "commit", "constraint", "SQL", "invoice_token",
                "injected", "stored_file")) {
            Assertions.assertFalse(body.contains(leak), label + ": the 500 body must not leak '" + leak + "': " + body);
        }
    }

    /**
     * A failed signing committed NOTHING and sent nothing: the request's own files are gone, its in-flight rows were
     * never committed, the committed state is exactly the state before the request (the link ACTIVE with no
     * dead_at, the mirror sentinel kept, the ISSUED quote version and its ACTIVE token unchanged), every earlier
     * file is byte-identical, no email or notification was even attempted, no WARN was logged, the single ERROR is
     * the injected fault, and both links still work on the real public surface.
     */
    private void assertNothingCommitted(SignableOrder fixture, CommittedState before, StorageWrites writes,
                                        List<Long> inFlightStoredFileIds, Long inFlightInvoiceId,
                                        AcceptAttempt attempt, String injectedFault, String label) throws Exception {
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
        Map<String, Object> link = findRow(after.invoiceTokens(), "invoice_token_id", fixture.invoiceTokenId());
        Assertions.assertEquals("ACTIVE", link.get("status"), label + ": the link stays ACTIVE (the kill rolled back)");
        Assertions.assertNull(link.get("dead_at"), label + ": the link has no dead_at");
        Assertions.assertEquals("ISSUED", findRow(after.quoteVersions(), "quote_version_id",
                fixture.issuedQuoteVersionId()).get("status"), label + ": the quote version stays ISSUED (D5(c) rolled back)");
        Assertions.assertEquals("ACTIVE", findRow(after.quoteTokens(), "quote_token_id",
                fixture.issuedQuoteTokenId()).get("status"), label + ": the quote token stays ACTIVE (D5(c) rolled back)");
        Assertions.assertEquals("ACCEPTED", findRow(after.quoteVersions(), "quote_version_id",
                fixture.acceptedQuoteVersionId()).get("status"), label + ": the ACCEPTED quote stays ACCEPTED");
        assertProtectedFilesIntact(fixture);

        verify(invoiceEmailSender, never()).send(any());
        verify(invoiceRepository, never()).updateInvoiceLastEmailedAt(anyLong(), any());
        assertNoEmailOrNotificationSent(label);
        Assertions.assertEquals(List.of(), attempt.notificationSends(), label + ": no store notification was delivered");
        Assertions.assertEquals(List.of(), describe(attempt.publicLog()),
                label + ": the post-commit steps never ran, so PublicInvoiceService logged nothing");
        Assertions.assertEquals(List.of(), describe(attempt.staffLog()), label + ": nothing on the OrderInvoiceService logger");
        assertInjectedFaultCausedThe500(attempt, injectedFault, label);

        assertPublicInvoiceActive(fixture.invoiceToken(), fixture.currentVersion(), label + ": the link still works");
        assertPublicQuoteState(fixture.issuedQuoteToken(), "ACTIVE", label + ": the quote link still works");
    }

    /**
     * The single unhandled-exception ERROR logged for the 500 has the injected fault in its cause chain: the 500
     * came from exactly this fault (while the response body stays generic).
     */
    private static void assertInjectedFaultCausedThe500(AcceptAttempt attempt, String injectedFault, String label) {
        Assertions.assertEquals(1, attempt.errorLog().size(),
                label + ": exactly one unhandled-exception event, but was " + describe(attempt.errorLog()));
        ILoggingEvent event = attempt.errorLog().get(0);
        Assertions.assertEquals(Level.ERROR, event.getLevel(), label + ": the unhandled exception is logged at ERROR");
        List<String> chain = throwableChain(event.getThrowableProxy());
        Assertions.assertTrue(chain.stream().anyMatch(message -> message.contains(injectedFault)),
                label + ": the 500 must be caused by the injected fault '" + injectedFault + "', but the chain was " + chain);
    }

    private static List<String> throwableChain(IThrowableProxy proxy) {
        List<String> chain = new ArrayList<>();
        List<IThrowableProxy> pending = new ArrayList<>();
        if (proxy != null) {
            pending.add(proxy);
        }
        while (!pending.isEmpty() && chain.size() < 64) {
            IThrowableProxy next = pending.remove(0);
            chain.add(next.getClassName() + ": " + next.getMessage());
            if (next.getCause() != null) {
                pending.add(next.getCause());
            }
            if (next.getSuppressed() != null) {
                pending.addAll(Arrays.asList(next.getSuppressed()));
            }
        }
        return chain;
    }

    /**
     * Remove every injected fault (spies back to real behaviour, no trigger left) and sign again with the same link
     * and expected version: the full committed, delivered, stamped and notified signing, relative to the same
     * committed state as before the failed attempt (which committed nothing).
     */
    private void assertRetrySigns(SignableOrder fixture, CommittedState before) throws Exception {
        Mockito.reset(invoicePdfGenerator, fileStorageService, invoiceRepository, invoiceEmailSender);
        dropFailureTriggers();
        StorageWrites writes = recordStorageWrites(null);
        SendObservations sends = observeSends(fixture, false);
        AcceptAttempt retry = performPublicAccept(fixture);
        assertCommittedSigning(fixture, before, writes, sends, retry, CustomerOutcome.DELIVERED, NotificationOutcome.DELIVERED);
    }

    /**
     * A refusal (409 stale, 410 dead link) wrote and sent nothing: the exact error envelope, the committed state
     * unchanged, no file written or deleted, no render, no row insert or marker write, no email, link email or
     * notification, and no log line at all.
     */
    private void assertRefusedWithoutSideEffects(SignableOrder fixture, CommittedState before, AcceptAttempt attempt,
                                                 int expectedStatus, String expectedBody, String label) throws Exception {
        assertStatus(attempt.result(), expectedStatus, label);
        Assertions.assertEquals(JSON.readTree(expectedBody), readJson(attempt.result()),
                label + ": exactly the error envelope " + expectedBody);
        assertSameCommittedState(before, committedState(fixture), label);
        verify(fileStorageService, never()).store(any(byte[].class), anyLong(), anyLong(), anyString());
        verify(fileStorageService, never()).deleteQuietly(any());
        verify(invoicePdfGenerator, never()).render(any());
        verify(invoiceRepository, never()).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        verify(invoiceRepository, never()).updateInvoiceLastEmailedAt(anyLong(), any());
        verify(invoiceEmailSender, never()).send(any());
        assertNoEmailOrNotificationSent(label);
        Assertions.assertEquals(List.of(), attempt.notificationSends(), label + ": no store notification was delivered");
        Assertions.assertEquals(List.of(), describe(attempt.publicLog()), label + ": PublicInvoiceService logged nothing");
        Assertions.assertEquals(List.of(), describe(attempt.staffLog()), label + ": nothing on the OrderInvoiceService logger");
        Assertions.assertEquals(List.of(), describe(attempt.errorLog()), label + ": a refusal is not an unhandled exception");
        assertProtectedFilesIntact(fixture);
    }

    /** Component-by-component (readable failures), then the whole snapshot. */
    private static void assertSameCommittedState(CommittedState before, CommittedState after, String label) {
        Assertions.assertEquals(before.business(), after.business(), label + ": business row");
        Assertions.assertEquals(before.store(), after.store(), label + ": store row");
        Assertions.assertEquals(before.salesOrder(), after.salesOrder(), label + ": sales_order row");
        Assertions.assertEquals(before.invoices(), after.invoices(), label + ": invoice rows");
        Assertions.assertEquals(before.storedFiles(), after.storedFiles(), label + ": stored_file rows of the order");
        Assertions.assertEquals(before.filesOnDisk(), after.filesOnDisk(), label + ": files on disk");
        Assertions.assertEquals(before.invoiceTokens(), after.invoiceTokens(), label + ": invoice_token rows");
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

    /** No signed-invoice email, store notification, link email, quote email or quote notification at all. */
    private void assertNoEmailOrNotificationSent(String label) {
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), label + ": no invoice email may be sent");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), label + ": no invoice email may be attempted");
        Assertions.assertTrue(invoiceNotificationSender.sentNotifications().isEmpty(),
                label + ": no store notification may be sent");
        Assertions.assertTrue(invoiceNotificationSender.failedNotifications().isEmpty(),
                label + ": no store notification may be attempted");
        assertNoLinkOrQuoteMail(label);
    }

    private void assertNoLinkOrQuoteMail(String label) {
        Assertions.assertTrue(invoiceLinkEmailSender.sentEmails().isEmpty(), label + ": no invoice link email");
        Assertions.assertTrue(invoiceLinkEmailSender.failedEmails().isEmpty(), label + ": no invoice link email attempt");
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty(), label + ": no quote email");
        Assertions.assertTrue(quoteEmailSender.failedEmails().isEmpty(), label + ": no quote email attempt");
        Assertions.assertTrue(quoteNotificationSender.sentNotifications().isEmpty(), label + ": no quote notification");
        Assertions.assertTrue(quoteNotificationSender.failedNotifications().isEmpty(),
                label + ": no quote notification attempt");
    }

    /** Public GET (no session): 200 "Invoice loaded." with all 27 keys, the ACTIVE document of {@code version}. */
    private void assertPublicInvoiceActive(String token, int version, String label) throws Exception {
        JsonNode data = publicInvoiceView(token, label);
        Assertions.assertEquals("ACTIVE", data.path("state").asText(), label + ": public state");
        Assertions.assertEquals(version, data.path("version_number").asInt(), label + ": the current version is shown");
        Assertions.assertEquals(BUSINESS_NAME, data.path("business_name").asText(), label + ": business name");
        Assertions.assertTrue(data.get("message").isNull(), label + ": no dead-state message");
    }

    /** Public GET (no session): 200 "Invoice loaded." with all 27 keys, the minimal dead-state payload. */
    private void assertPublicInvoiceDead(String token, String state, String message, String label) throws Exception {
        JsonNode data = publicInvoiceView(token, label);
        Assertions.assertEquals(state, data.path("state").asText(), label + ": public state");
        Assertions.assertEquals(message, data.path("message").asText(), label + ": the per-state message");
        Assertions.assertEquals(BUSINESS_NAME, data.path("business_name").asText(), label + ": business name");
        for (String field : List.of("version_number", "order_number", "customer_name", "sale_price_inc_gst",
                "terms_html", "expires_at")) {
            Assertions.assertTrue(data.get(field).isNull(), label + ": a dead link carries no " + field);
        }
    }

    private JsonNode publicInvoiceView(String token, String label) throws Exception {
        MvcResult result = performPublic(get(publicInvoiceUrl(token)));
        assertStatus(result, 200, label + ": public invoice view");
        JsonNode root = readJson(result);
        Assertions.assertEquals(INVOICE_LOADED_MESSAGE, root.path("message").asText(), label + ": public view message");
        JsonNode data = root.path("data");
        Assertions.assertEquals(27, data.size(), label + ": the public view always carries exactly 27 keys");
        return data;
    }

    /** Public quote GET (no session): 200 with the link's customer-facing state (the GET writes nothing). */
    private void assertPublicQuoteState(String token, String expectedState, String label) throws Exception {
        MvcResult result = performPublic(get(publicQuoteUrl(token)));
        assertStatus(result, 200, label + ": public quote view");
        Assertions.assertEquals(expectedState, readJson(result).path("data").path("state").asText(),
                label + ": the public quote link state");
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + body(result));
    }

    // ================================================================
    // The requests under test
    // ================================================================

    /**
     * Every public request of this class: token-only, never a session. Afterwards the request has no session
     * (none was carried, none was created) and the response set no cookie - for every status.
     */
    private MvcResult performPublic(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String uri = result.getRequest().getRequestURI();
        Assertions.assertTrue(uri.startsWith("/api/v1/public/"), "only public requests go through here: " + uri);
        Assertions.assertNull(result.getRequest().getSession(false),
                "a public request never carries or creates a session (" + uri + ", status "
                        + result.getResponse().getStatus() + ")");
        Assertions.assertEquals(List.of(), result.getResponse().getHeaders("Set-Cookie"),
                "a public response never sets a cookie (" + uri + ", status " + result.getResponse().getStatus() + ")");
        Assertions.assertEquals(0, result.getResponse().getCookies().length,
                "a public response never adds a cookie (" + uri + ")");
        return result;
    }

    private AcceptAttempt performPublicAccept(SignableOrder fixture) throws Exception {
        return performPublicAccept(fixture.invoiceToken(), fixture.currentVersion());
    }

    /**
     * The remote accept exactly as the public page sends it: the {@code expected_version_number} form field and one
     * PNG {@code signature} part, no session. For the duration of the request only, the PublicInvoiceService,
     * OrderInvoiceService and GlobalExceptionHandler loggers are captured, and every DELIVERED store notification
     * is observed at the moment it happens (the recording sender's DEBUG line, enabled for the request only).
     */
    private AcceptAttempt performPublicAccept(String token, int expectedVersion) throws Exception {
        ch.qos.logback.classic.Logger publicLogger = logbackLogger(PublicInvoiceService.class);
        ch.qos.logback.classic.Logger staffLogger = logbackLogger(OrderInvoiceService.class);
        ch.qos.logback.classic.Logger errorLogger = logbackLogger(GlobalExceptionHandler.class);
        ch.qos.logback.classic.Logger notificationLogger = logbackLogger(RecordingInvoiceAcceptanceNotificationSender.class);
        ListAppender<ILoggingEvent> publicEvents = attach(publicLogger);
        ListAppender<ILoggingEvent> staffEvents = attach(staffLogger);
        ListAppender<ILoggingEvent> errorEvents = attach(errorLogger);
        NotificationSendProbe probe = new NotificationSendProbe();
        probe.setContext(notificationLogger.getLoggerContext());
        probe.start();
        Level notificationLevel = notificationLogger.getLevel();
        notificationLogger.setLevel(Level.DEBUG);
        notificationLogger.addAppender(probe);
        LocalDateTime startedAt = LocalDateTime.now();
        MvcResult result;
        try {
            result = performPublic(multipart(publicInvoiceUrl(token) + "/accept")
                    .file(new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", SIGNATURE_PNG))
                    .param(EXPECTED_VERSION_FIELD, Integer.toString(expectedVersion)));
        } finally {
            notificationLogger.detachAppender(probe);
            notificationLogger.setLevel(notificationLevel);
            probe.stop();
            detach(publicLogger, publicEvents);
            detach(staffLogger, staffEvents);
            detach(errorLogger, errorEvents);
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        return new AcceptAttempt(result, List.copyOf(publicEvents.list), List.copyOf(staffEvents.list),
                List.copyOf(errorEvents.list), probe.observed(), startedAt, finishedAt);
    }

    private static ch.qos.logback.classic.Logger logbackLogger(Class<?> type) {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
    }

    private static ListAppender<ILoggingEvent> attach(ch.qos.logback.classic.Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ch.qos.logback.classic.Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * Observes each DELIVERED store notification synchronously, on the request thread, at the moment the recording
     * sender records it (its DEBUG line carries only the order id and the version): whether a transaction is active
     * and - on a pooled auto-commit connection, so COMMITTED data only - whether the signed version is committed.
     */
    private final class NotificationSendProbe extends AppenderBase<ILoggingEvent> {
        private final List<NotificationSend> observed = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            Object[] arguments = event.getArgumentArray();
            long orderId = ((Number) arguments[0]).longValue();
            int version = ((Number) arguments[1]).intValue();
            observed.add(new NotificationSend(
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    count("SELECT COUNT(*) FROM invoice WHERE order_id = ? AND version_number = ? "
                            + "AND accepted_at IS NOT NULL", orderId, version)));
        }

        List<NotificationSend> observed() {
            return List.copyOf(observed);
        }
    }

    // ================================================================
    // Spy recorders / stubs (installed only after the fixture is committed)
    // ================================================================

    /**
     * Record every storage write made from now on (the path returned by the REAL store, in call order), optionally
     * failing the write of one extension ("png" = the signature, "pdf" = the signed PDF) before anything reaches
     * the disk.
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

    /**
     * The ids of every stored_file row inserted from now on (real insert, inside the request transaction), failing
     * the {@code failOnCall}-th insert (1 = the signature's row, 2 = the signed PDF's; 0 = never).
     */
    private List<Long> recordInsertedStoredFileIds(int failOnCall) {
        List<Long> ids = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == failOnCall) {
                throw new DataIntegrityViolationException("injected failure on stored_file insert #" + call);
            }
            Long id = (Long) invocation.callRealMethod();
            ids.add(id);
            return id;
        }).when(invoiceRepository).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        return ids;
    }

    /** The ids of this order's invoice rows inserted from now on (real insert, inside the request transaction). */
    private List<Long> recordInsertedInvoiceIds(SignableOrder fixture) {
        List<Long> ids = new CopyOnWriteArrayList<>();
        insertInvoiceFor(doAnswer(invocation -> {
            InvoiceRow row = (InvoiceRow) invocation.callRealMethod();
            ids.add(row.invoiceId());
            return row;
        }).when(invoiceRepository), fixture.orderId());
        return ids;
    }

    /**
     * Observe every customer send from now on, at the moment it happens: whether a transaction is active on the
     * request thread, and - through pooled auto-commit connections, so COMMITTED data only - whether the signed
     * version, the SIGNED link and the D5(c) quote-token kill are already committed, and what the committed saved
     * customer email and store email are. Then either really send (the recording transport, honouring
     * {@code failNextSend}) or throw a non-transport runtime failure.
     */
    private SendObservations observeSends(SignableOrder fixture, boolean throwOtherRuntimeFailure) {
        SendObservations observations = new SendObservations();
        doAnswer(invocation -> {
            InvoiceEmailRequest request = invocation.getArgument(0);
            observations.transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            observations.committedSignedVersions.add(count(
                    "SELECT COUNT(*) FROM invoice WHERE order_id = ? AND version_number = ? AND accepted_at IS NOT NULL",
                    request.orderId(), request.invoiceVersionNumber()));
            observations.committedInvoiceTokenStatus.add(jdbcTemplate.queryForObject(
                    "SELECT status FROM invoice_token WHERE invoice_token_id = ?", String.class, fixture.invoiceTokenId()));
            observations.committedQuoteTokenStatus.add(jdbcTemplate.queryForObject(
                    "SELECT status FROM quote_token WHERE quote_token_id = ?", String.class, fixture.issuedQuoteTokenId()));
            List<String> savedEmails = jdbcTemplate.queryForList(
                    "SELECT email FROM order_customer WHERE order_id = ?", String.class, request.orderId());
            observations.committedCustomerEmails.add(savedEmails.isEmpty() ? "<no order_customer row>" : savedEmails.get(0));
            List<String> storeEmails = jdbcTemplate.queryForList(
                    "SELECT email FROM store WHERE store_id = ?", String.class, tenant.storeId());
            observations.committedStoreEmails.add(storeEmails.isEmpty() ? "<no store row>"
                    : Objects.toString(storeEmails.get(0), "<null>"));
            if (throwOtherRuntimeFailure) {
                throw new IllegalStateException("injected non-transport failure inside the invoice email sender");
            }
            return invocation.callRealMethod();
        }).when(invoiceEmailSender).send(any());
        return observations;
    }

    /**
     * For this order's signing insert from now on: register a synchronization on the signing transaction whose
     * {@code afterCommit} commits {@value #MOVED_CUSTOMER_EMAIL} as the saved customer email and {@value
     * #MOVED_STORE_EMAIL} as the store email, then call the REAL insert. {@code afterCommit} runs inside the
     * TransactionTemplate's commit, so after the signing is durable and before either post-commit side effect.
     * The moves use a RAW pooled connection in auto-commit, never {@code jdbcTemplate}: inside {@code afterCommit}
     * that would still join the finished transaction's connection, and nothing would commit it. On that connection
     * the hook also records that the signed version is already committed and what the committed emails were
     * before the moves. Failures are recorded rather than thrown, so the test can report them.
     */
    private EmailMoves moveBothEmailsAfterSigningCommit(SignableOrder fixture) {
        EmailMoves moves = new EmailMoves();
        int signedVersion = fixture.currentVersion() + 1;
        insertInvoiceFor(doAnswer(invocation -> {
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
                            signed.setQueryTimeout(30);
                            signed.setLong(1, fixture.orderId());
                            signed.setInt(2, signedVersion);
                            try (ResultSet resultSet = signed.executeQuery()) {
                                resultSet.next();
                                moves.signedVersionsCommittedAtMove.add(resultSet.getLong(1));
                            }
                        }
                        moves.customerEmailsBeforeMove.add(singleString(connection,
                                "SELECT email FROM order_customer WHERE order_id = ?", fixture.orderId()));
                        moves.storeEmailsBeforeMove.add(singleString(connection,
                                "SELECT email FROM store WHERE store_id = ?", tenant.storeId()));
                        try (PreparedStatement move = connection.prepareStatement(
                                "UPDATE order_customer SET email = ? WHERE order_id = ?")) {
                            move.setQueryTimeout(30);
                            move.setString(1, MOVED_CUSTOMER_EMAIL);
                            move.setLong(2, fixture.orderId());
                            moves.customerRowsMoved.add(move.executeUpdate());
                        }
                        try (PreparedStatement move = connection.prepareStatement(
                                "UPDATE store SET email = ? WHERE store_id = ?")) {
                            move.setQueryTimeout(30);
                            move.setString(1, MOVED_STORE_EMAIL);
                            move.setInt(2, tenant.storeId());
                            moves.storeRowsMoved.add(move.executeUpdate());
                        }
                    } catch (SQLException | RuntimeException ex) {
                        moves.failures.add(ex.toString());
                    }
                }
            });
            return invocation.callRealMethod();
        }).when(invoiceRepository), fixture.orderId());
        return moves;
    }

    private static String singleString(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(30);
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : "<no row>";
            }
        }
    }

    /**
     * For this order's signing insert from now on: call the REAL insert, then register a synchronization on the
     * signing transaction whose {@code beforeCommit} reads - on that transaction's own connection, after every
     * statement of the persist has run and before the COMMIT - the in-flight invoice, link, quote and mirror values.
     */
    private List<InFlightSigning> probeSigningBeforeCommit(SignableOrder fixture) {
        List<InFlightSigning> probes = new CopyOnWriteArrayList<>();
        insertInvoiceFor(doAnswer(invocation -> {
            InvoiceRow row = (InvoiceRow) invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    Map<String, Object> invoice = jdbcTemplate.queryForMap(
                            "SELECT accepted_at, created_by_user_id FROM invoice WHERE invoice_id = ?", row.invoiceId());
                    Map<String, Object> link = jdbcTemplate.queryForMap(
                            "SELECT status, dead_at FROM invoice_token WHERE invoice_token_id = ?", fixture.invoiceTokenId());
                    Map<String, Object> quoteToken = jdbcTemplate.queryForMap(
                            "SELECT status, dead_at FROM quote_token WHERE quote_token_id = ?", fixture.issuedQuoteTokenId());
                    probes.add(new InFlightSigning(
                            row.invoiceId(),
                            row.versionNumber(),
                            (Timestamp) invoice.get("accepted_at"),
                            toLong(invoice.get("created_by_user_id")),
                            (String) link.get("status"),
                            (Timestamp) link.get("dead_at"),
                            jdbcTemplate.queryForObject("SELECT status FROM quote_version WHERE quote_version_id = ?",
                                    String.class, fixture.issuedQuoteVersionId()),
                            (String) quoteToken.get("status"),
                            (Timestamp) quoteToken.get("dead_at"),
                            jdbcTemplate.queryForObject("SELECT status FROM quote_version WHERE quote_version_id = ?",
                                    String.class, fixture.acceptedQuoteVersionId()),
                            jdbcTemplate.queryForObject("SELECT last_emailed_at FROM sales_order WHERE order_id = ?",
                                    Timestamp.class, fixture.orderId())));
                }
            });
            return row;
        }).when(invoiceRepository), fixture.orderId());
        return probes;
    }

    /** {@code insertInvoice} (20 parameters) for this order with any other argument - stub or verify target. */
    private static InvoiceRow insertInvoiceFor(InvoiceRepository target, long orderId) {
        return target.insertInvoice(eq(orderId), anyInt(), any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A deferred constraint trigger that raises at COMMIT for any invoice row inserted for this order (the WHEN
     * clause is evaluated at INSERT time; the function runs at commit).
     */
    private void installSigningCommitFailureTrigger(String trigger, long orderId) {
        createCommitFailureFunction();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger
                + " AFTER INSERT ON invoice"
                + " DEFERRABLE INITIALLY DEFERRED"
                + " FOR EACH ROW"
                + " WHEN (NEW.order_id = " + orderId + ")"
                + " EXECUTE FUNCTION " + COMMIT_FAILURE_FUNCTION + "()");
    }

    /**
     * A deferred constraint trigger that raises at COMMIT when an invoice row of this order gets its
     * {@code last_emailed_at} set (null to non-null) - i.e. at the commit of the email-success stamp. The signing
     * persist only INSERTs invoice rows, so it never queues this trigger.
     */
    private void installStampCommitFailureTrigger(String trigger, long orderId) {
        createCommitFailureFunction();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger
                + " AFTER UPDATE ON invoice"
                + " DEFERRABLE INITIALLY DEFERRED"
                + " FOR EACH ROW"
                + " WHEN (NEW.order_id = " + orderId
                + " AND OLD.last_emailed_at IS NULL AND NEW.last_emailed_at IS NOT NULL)"
                + " EXECUTE FUNCTION " + COMMIT_FAILURE_FUNCTION + "()");
    }

    /**
     * An ordinary (non-deferred) trigger that refuses this order's {@code ACTIVE -> SIGNED} invoice_token update
     * when the statement runs - i.e. the remote accept's token kill, mid-transaction.
     */
    private void installTokenKillFailureTrigger(String trigger, long orderId) {
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION " + TOKEN_KILL_FAILURE_FUNCTION
                + "() RETURNS trigger LANGUAGE plpgsql AS $$\n"
                + "BEGIN\n"
                + "    RAISE EXCEPTION '" + TOKEN_KILL_FAILURE_TEXT + "' USING ERRCODE = 'P0001';\n"
                + "END;\n"
                + "$$");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice_token");
        jdbcTemplate.execute("CREATE TRIGGER " + trigger
                + " BEFORE UPDATE ON invoice_token"
                + " FOR EACH ROW"
                + " WHEN (NEW.order_id = " + orderId + " AND OLD.status = 'ACTIVE' AND NEW.status = 'SIGNED')"
                + " EXECUTE FUNCTION " + TOKEN_KILL_FAILURE_FUNCTION + "()");
    }

    private void createCommitFailureFunction() {
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION " + COMMIT_FAILURE_FUNCTION
                + "() RETURNS trigger LANGUAGE plpgsql AS $$\n"
                + "BEGIN\n"
                + "    RAISE EXCEPTION '" + COMMIT_FAILURE_TEXT + "' USING ERRCODE = 'P0001';\n"
                + "END;\n"
                + "$$");
    }

    private long triggerCount(String table, String trigger) {
        return count("SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = to_regclass(?) AND tgname = ?", table, trigger);
    }

    private void clearSpyInvocations() {
        Mockito.clearInvocations(invoicePdfGenerator, fileStorageService, invoiceRepository, invoiceEmailSender);
    }

    private void resetSenders() {
        invoiceEmailSender.reset();
        invoiceLinkEmailSender.reset();
        invoiceNotificationSender.reset();
        quoteEmailSender.reset();
        quoteNotificationSender.reset();
    }

    // ================================================================
    // Committed-state probes (auto-commit JDBC - committed data only)
    // ================================================================

    /** Every row the remote accept (or its D5(c) step) could touch for the order, plus the order's storage dir. */
    private CommittedState committedState(SignableOrder fixture) {
        long orderId = fixture.orderId();
        return new CommittedState(
                row("SELECT * FROM business WHERE business_id = ?", tenant.businessId()),
                row("SELECT * FROM store WHERE store_id = ?", tenant.storeId()),
                row("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                        orderStoragePrefix(orderId) + "%"),
                filesOnDisk(orderId),
                rows("SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId),
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

    /** The one row whose {@code idColumn} = {@code id} (asserted to exist exactly once). */
    private static Map<String, Object> findRow(List<Map<String, Object>> rows, String idColumn, long id) {
        List<Map<String, Object>> matches = rows.stream()
                .filter(row -> Objects.equals(toLong(row.get(idColumn)), id))
                .toList();
        Assertions.assertEquals(1, matches.size(), "exactly one row with " + idColumn + " = " + id + " must exist");
        return matches.get(0);
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

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
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
     * The class's own COMMITTED tenant: business (unique slug, ABN + bank details, SOFT terms), one store with a
     * padded email, the order salesperson and the link sender, both with access to the store.
     */
    private Tenant seedTenant() {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number, "
                        + " terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, '53 004 085 616', 'Acceptance Commit Bank', '062-111', ?, '87654321', ?, NULL) "
                        + "RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, BUSINESS_NAME + " Pty Ltd", TERMS_SOFT);
        Assertions.assertNotNull(businessId, "setup: business insert must return an id");
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'IAC One Showroom', ?, '0290004321', ?, '9 Commit Road', 'Parramatta', 'NSW', '2150') "
                        + "RETURNING store_id",
                Integer.class, businessId, STORE_CODE, SAVED_STORE_EMAIL);
        Assertions.assertNotNull(storeId, "setup: store insert must return an id");
        long orderUserId = insertUser(businessId, storeId, "Ada", "Order", ORDER_USER_CODE, "ada.order@iac-one.example.com");
        long linkSenderUserId = insertUser(businessId, storeId, "Ben", "Sender", LINK_SENDER_CODE,
                "ben.sender@iac-one.example.com");
        orderSequence = 0;
        return new Tenant(businessId, storeId, orderUserId, linkSenderUserId);
    }

    private long insertUser(long businessId, int storeId, String firstName, String lastName, String code, String email) {
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'not-a-login-hash-iacmt') RETURNING user_id",
                Long.class, businessId, firstName, lastName, code, email);
        Assertions.assertNotNull(userId, "setup: app_user insert must return an id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        return userId;
    }

    /**
     * A committed order ready for the remote accept: invoice-ready header and children; the unsigned invoice v1
     * through the REAL D.1 endpoint, then committed-dated {@value #V1_INVOICE_DATE_TEXT}; quote v1 issued and
     * ACCEPTED on the real public quote page; quote v2 ISSUED with an ACTIVE token; the ACTIVE invoice link sent
     * through the real send-link by {@value #LINK_SENDER_CODE}; every older file captured (row + bytes); a non-null
     * mirror sentinel committed; senders and spy invocations cleared.
     */
    private SignableOrder seedSignableOrder() throws Exception {
        SeededOrder order = insertInvoiceReadyOrder();
        long orderId = order.orderId();

        // ---- Invoice v1, through the real D.1 endpoint ----
        MvcResult created = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(tenant.orderUserSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(created, 201, "setup: D.1 invoice create");
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, readJson(created).path("message").asText());
        Assertions.assertEquals(List.of(1), invoiceVersions(orderId), "setup: D.1 committed exactly one invoice version");
        Map<String, Object> v1 = row("SELECT * FROM invoice WHERE order_id = ? AND version_number = 1", orderId);
        Assertions.assertNull(v1.get("accepted_at"), "setup: v1 is unsigned");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, v1.get("customer_name_snapshot"), "setup: v1's frozen name");
        Assertions.assertEquals(BILLING_LINE_1, v1.get("customer_address_line1_snapshot"), "setup: v1's billing line 1");
        Assertions.assertEquals(BILLING_LINE_2, v1.get("customer_address_line2_snapshot"), "setup: v1's billing line 2");
        Assertions.assertTrue(String.valueOf(v1.get("terms_snapshot")).contains(TERMS_TEXT),
                "setup: v1 froze the business SOFT terms");
        Assertions.assertNull(v1.get("source_quote_version_id"), "setup: v1 has no source quote version");
        Assertions.assertEquals(0, INVOICE_EX_GST.compareTo((BigDecimal) v1.get("sale_price_ex_gst")), "setup: v1 ex GST");
        Assertions.assertEquals(0, INVOICE_INC_GST.compareTo((BigDecimal) v1.get("sale_price_inc_gst")), "setup: v1 inc GST");
        Assertions.assertEquals(tenant.orderUserId(), toLong(v1.get("created_by_user_id")), "setup: v1's creator");
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice SET invoice_date = ? WHERE invoice_id = ?",
                Date.valueOf(V1_INVOICE_DATE), v1.get("invoice_id")), "setup: date v1 on an earlier day");

        // ---- Quote v1 issued and ACCEPTED on the real public page: an older signature and signed PDF ----
        saveItemisedQuoteDraft(orderId);
        String acceptedQuoteToken = issueQuoteAndExtractToken(orderId);
        acceptQuotePublicly(acceptedQuoteToken);
        Map<String, Object> acceptedQuote = row("SELECT * FROM quote_version WHERE order_id = ? AND version_number = 1", orderId);
        Assertions.assertEquals("ACCEPTED", acceptedQuote.get("status"), "setup: quote v1 is ACCEPTED");
        Assertions.assertNotNull(acceptedQuote.get("accepted_signature_file_id"), "setup: quote v1 has a stored signature");
        Assertions.assertNotNull(acceptedQuote.get("signed_pdf_file_id"), "setup: quote v1 has a stored signed PDF");
        long acceptedQuoteVersionId = toLong(acceptedQuote.get("quote_version_id"));

        // ---- Quote v2 ISSUED with an ACTIVE token: the link D5(c) must cancel ----
        String issuedQuoteToken = issueQuoteAndExtractToken(orderId);
        Map<String, Object> issued = row("SELECT * FROM quote_version WHERE order_id = ? AND version_number = 2", orderId);
        Assertions.assertEquals("ISSUED", issued.get("status"), "setup: quote v2 is ISSUED");
        long issuedQuoteVersionId = toLong(issued.get("quote_version_id"));
        List<Map<String, Object>> issuedTokens =
                rows("SELECT * FROM quote_token WHERE quote_version_id = ?", issuedQuoteVersionId);
        Assertions.assertEquals(1, issuedTokens.size(), "setup: exactly one token for quote v2");
        Assertions.assertEquals("ACTIVE", issuedTokens.get(0).get("status"), "setup: the quote v2 token is ACTIVE");
        Assertions.assertNull(issuedTokens.get(0).get("dead_at"), "setup: the quote v2 token is alive");
        Assertions.assertEquals(sha256Hex(issuedQuoteToken), issuedTokens.get(0).get("token_hash"),
                "setup: the emailed quote link carries the plaintext of the stored hash");
        long issuedQuoteTokenId = toLong(issuedTokens.get(0).get("quote_token_id"));
        Map<String, Object> v1AfterQuotes = row("SELECT * FROM invoice WHERE order_id = ? AND version_number = 1", orderId);
        Assertions.assertEquals(List.of(1), invoiceVersions(orderId), "setup: quote acceptance never creates an invoice");
        Assertions.assertEquals(0, INVOICE_INC_GST.compareTo((BigDecimal) v1AfterQuotes.get("sale_price_inc_gst")),
                "setup: the quote acceptance (D6b) moved the order price, never v1's money");

        // ---- The ACTIVE invoice link, sent by the link sender through the real send-link ----
        SentLink link = sendInvoiceLink(orderId, order.orderNumber(), tenant.linkSenderSession(),
                tenant.linkSenderUserId(), 1);

        // ---- Every older file no failure may touch ----
        ProtectedFile v1Pdf = protectedFile("the unsigned invoice v1 PDF", toLong(v1.get("stored_file_id")));
        List<ProtectedFile> protectedFiles = List.of(
                v1Pdf,
                protectedFile("the issued quote v1 PDF", toLong(acceptedQuote.get("issued_pdf_file_id"))),
                protectedFile("the accepted quote v1 signature", toLong(acceptedQuote.get("accepted_signature_file_id"))),
                protectedFile("the signed quote v1 PDF", toLong(acceptedQuote.get("signed_pdf_file_id"))),
                protectedFile("the issued quote v2 PDF", toLong(issued.get("issued_pdf_file_id"))));

        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE sales_order SET last_emailed_at = ? WHERE order_id = ?",
                SENTINEL_LAST_EMAILED_AT, orderId), "setup: mirror sentinel");

        resetSenders();
        clearSpyInvocations();
        return new SignableOrder(orderId, order.orderNumber(), 1, link.token(), link.invoiceTokenId(),
                tenant.linkSenderUserId(), acceptedQuoteVersionId, issuedQuoteVersionId, issuedQuoteTokenId,
                issuedQuoteToken, v1Pdf.bytes(), protectedFiles);
    }

    /**
     * Header (details of sale, proposed lay date 2026-12-01 CONFIRMED), customer 'Rowan' 'Acceptline' with the
     * padded valid email, INSTALLATION + BILLING addresses and one tagged 1,234.50 ex / 40.00 cost charge.
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
                Long.class, tenant.businessId(), tenant.storeId(), tenant.orderUserId(), sequence, orderNumber,
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
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'IAC commit test charge', 1234.50, 40.00) "
                        + "RETURNING charge_id",
                Long.class, tenant.storeId(), chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'IAC commit test charge', 1234.50, 40.00, 1, 1234.50, 1234.50, 40.00)",
                orderId, chargeId, chargeCode);
        return new SeededOrder(orderId, orderNumber);
    }

    /** Itemised draft through the API: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex / 275.00 inc (above cost). */
    private void saveItemisedQuoteDraft(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(orderId) + "/quote/draft").session(tenant.orderUserSession())
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
    private String issueQuoteAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/quote/send-email").session(tenant.orderUserSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: quote send-email (issue)");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly the issue email is recorded");
        Matcher matcher = QUOTE_LINK_PATTERN.matcher(sent.get(sent.size() - 1).bodyText());
        Assertions.assertTrue(matcher.find(), "setup: the quote email must carry the public /q/{token} link");
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "setup: the public quote link must appear exactly once in the email body");
        return token;
    }

    /** The customer signs the issued quote on the REAL public quote page (no session). */
    private void acceptQuotePublicly(String quoteToken) throws Exception {
        MvcResult result = performPublic(multipart(publicQuoteUrl(quoteToken) + "/accept")
                .file(new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", SIGNATURE_PNG)));
        assertStatus(result, 201, "setup: public quote accept");
        Assertions.assertEquals("INACTIVE", readJson(result).path("data").path("state").asText(),
                "setup: the signed quote link is dead");
    }

    /**
     * Accept Remotely through the REAL protected send-link: 201 with the link's times, exactly one link-only email
     * to the trimmed saved email whose body carries the {@code /i/{token}} link exactly once, and the matching
     * {@code ACTIVE} token row (sender, expiry = sent_at + 7 days, delivery stamped). Returns the plaintext token.
     */
    private SentLink sendInvoiceLink(long orderId, String orderNumber, MockHttpSession session, long senderUserId,
                                     int currentVersion) throws Exception {
        int sentBefore = invoiceLinkEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/invoices/current/send-link").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: invoice send-link");
        JsonNode root = readJson(result);
        Assertions.assertEquals(LINK_SENT_MESSAGE, root.path("message").asText(), "setup: send-link message");
        List<InvoiceLinkEmailRequest> sent = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly one link email delivered");
        InvoiceLinkEmailRequest email = sent.get(sent.size() - 1);
        Matcher matcher = INVOICE_LINK_PATTERN.matcher(email.bodyText());
        Assertions.assertTrue(matcher.find(), "setup: the link email must carry the public /i/{token} link");
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "setup: the public invoice link must appear exactly once");
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), "setup: the link email goes to the trimmed saved email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), "setup: link email subject");
        Assertions.assertEquals("Hi,\n\nYour invoice " + orderNumber + " from " + BUSINESS_NAME
                        + " is ready to review and sign. You can view and sign your invoice online here: "
                        + PUBLIC_BASE_URL + "/i/" + token + "\n\nThank you.",
                email.bodyText(), "setup: link email body");
        Assertions.assertEquals(orderId, email.orderId(), "setup: link email order id");
        Assertions.assertEquals(currentVersion, email.invoiceVersionNumber(), "setup: link email version");
        Map<String, Object> tokenRow = row("SELECT * FROM invoice_token WHERE token_hash = ?", sha256Hex(token));
        Assertions.assertEquals(orderId, toLong(tokenRow.get("order_id")), "setup: the link belongs to the order");
        Assertions.assertEquals("ACTIVE", tokenRow.get("status"), "setup: the new link is ACTIVE");
        Assertions.assertEquals(senderUserId, toLong(tokenRow.get("sent_by_user_id")), "setup: the link's sender");
        Assertions.assertNotNull(tokenRow.get("emailed_at"), "setup: the link delivery was stamped");
        Assertions.assertNull(tokenRow.get("dead_at"), "setup: the new link is alive");
        LocalDateTime sentAt = ((Timestamp) tokenRow.get("sent_at")).toLocalDateTime();
        LocalDateTime expiresAt = ((Timestamp) tokenRow.get("expires_at")).toLocalDateTime();
        Assertions.assertEquals(sentAt.plusDays(7), expiresAt, "setup: expires_at = sent_at + 7 days");
        JsonNode sentLink = root.path("data").path("invoice_link");
        Assertions.assertEquals(sentAt.truncatedTo(ChronoUnit.SECONDS), LocalDateTime.parse(sentLink.path("sent_at").asText()),
                "setup: the response's sent_at");
        Assertions.assertEquals(expiresAt.truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(sentLink.path("expires_at").asText()), "setup: the response's expires_at");
        return new SentLink(token, toLong(tokenRow.get("invoice_token_id")), sentAt);
    }

    private MvcResult recordPayment(long orderId, String amount) throws Exception {
        return mockMvc.perform(post(orderUrl(orderId) + "/payments").session(tenant.orderUserSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\":\"EFTPOS\",\"amount\":" + amount + ",\"payment_reference\":null}"))
                .andReturn();
    }

    private void setSavedCustomerEmail(SignableOrder fixture, String email) {
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE order_customer SET email = ? WHERE order_id = ?",
                email, fixture.orderId()), "setup: the saved customer email changed after the link was sent");
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
    // URLs / JSON / PDF / PNG helpers
    // ================================================================

    private static String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    private static String publicInvoiceUrl(String token) {
        return "/api/v1/public/invoices/" + token;
    }

    private static String publicQuoteUrl(String token) {
        return "/api/v1/public/quotes/" + token;
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

    /**
     * The stored (normalised) signature decodes to the uploaded image: the same dimensions, the same alpha for every
     * pixel and the same colour for every visible pixel.
     */
    private static void assertSamePixels(byte[] uploaded, byte[] stored, String label) throws IOException {
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(uploaded));
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(stored));
        Assertions.assertNotNull(expected, label + ": the upload decodes");
        Assertions.assertNotNull(actual, label + ": the stored bytes decode");
        Assertions.assertEquals(expected.getWidth(), actual.getWidth(), label + ": width");
        Assertions.assertEquals(expected.getHeight(), actual.getHeight(), label + ": height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y);
                int got = actual.getRGB(x, y);
                Assertions.assertEquals(want >>> 24, got >>> 24, label + ": alpha at (" + x + "," + y + ")");
                if ((want >>> 24) != 0) {
                    Assertions.assertEquals(want & 0xFFFFFF, got & 0xFFFFFF, label + ": colour at (" + x + "," + y + ")");
                }
            }
        }
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
                invoiceLinkEmailSender::reset,
                invoiceNotificationSender::reset,
                quoteEmailSender::reset,
                quoteNotificationSender::reset,
                this::dropFailureTriggers,
                this::sweepTenants);
        assertSweptClean();
    }

    /** The sweep really removed everything this class commits: the tenant (by slug), its triggers and functions. */
    private void assertSweptClean() {
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM business WHERE slug = ?", SLUG),
                "no tenant of this class may survive the sweep");
        for (String table : TRIGGER_TABLES) {
            Assertions.assertEquals(0L, count(
                            "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = to_regclass(?) AND tgname LIKE ?",
                            table, likePrefix(TRIGGER_PREFIX)),
                    "no failure trigger of this class may survive the sweep on " + table);
        }
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM pg_proc WHERE proname IN (?, ?)",
                        COMMIT_FAILURE_FUNCTION, TOKEN_KILL_FAILURE_FUNCTION),
                "the failure trigger functions must be dropped");
        if (tenant != null) {
            Assertions.assertFalse(Files.exists(businessStorageDir(tenant.businessId())),
                    "the tenant's storage dir must be removed");
        }
    }

    private static String likePrefix(String prefix) {
        return prefix.replace("_", "\\_") + "%";
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

    /** Drop every leftover failure trigger of this class (any order id) on invoice and invoice_token, then the functions. */
    private void dropFailureTriggers() {
        for (String table : TRIGGER_TABLES) {
            List<String> triggers = jdbcTemplate.queryForList(
                    "SELECT tgname FROM pg_trigger WHERE tgrelid = to_regclass(?) AND tgname LIKE ?",
                    String.class, table, likePrefix(TRIGGER_PREFIX));
            for (String trigger : triggers) {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
            }
        }
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + COMMIT_FAILURE_FUNCTION + "()");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + TOKEN_KILL_FAILURE_FUNCTION + "()");
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
            // Defensive: invoice_token.sent_by_user_id references app_user (the per-order delete above normally
            // removed every link of this tenant already).
            jdbcTemplate.update("DELETE FROM invoice_token WHERE sent_by_user_id IN "
                    + "(SELECT user_id FROM app_user WHERE business_id = ?)", businessId);
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
                // invoice_token references sales_order and app_user: it goes first.
                jdbcTemplate.update("DELETE FROM invoice_token WHERE order_id = ?", orderId);
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

    /** The committed tenant. Sessions follow the RequestContextGuard type trap: (Long) ids, (Integer) store. */
    private record Tenant(long businessId, int storeId, long orderUserId, long linkSenderUserId) {

        MockHttpSession orderUserSession() {
            return session(orderUserId);
        }

        MockHttpSession linkSenderSession() {
            return session(linkSenderUserId);
        }

        private MockHttpSession session(long userId) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("user_id", Long.valueOf(userId));
            session.setAttribute("business_id", Long.valueOf(businessId));
            session.setAttribute("store_id", Integer.valueOf(storeId));
            return session;
        }
    }

    private record SeededOrder(long orderId, String orderNumber) {
    }

    /** One delivered invoice link: the plaintext token (from the recorded email), its row id and its send time. */
    private record SentLink(String token, long invoiceTokenId, LocalDateTime sentAt) {
    }

    /**
     * A committed order ready for the remote accept: its current (unsigned) version and that version's PDF bytes,
     * the presented link (plaintext, id, sender), the ACCEPTED quote version, the ISSUED quote version and its ACTIVE
     * token (id + plaintext), and every file no failure may touch.
     */
    private record SignableOrder(long orderId,
                                 String orderNumber,
                                 int currentVersion,
                                 String invoiceToken,
                                 long invoiceTokenId,
                                 long linkSenderUserId,
                                 long acceptedQuoteVersionId,
                                 long issuedQuoteVersionId,
                                 long issuedQuoteTokenId,
                                 String issuedQuoteToken,
                                 byte[] currentPdfBytes,
                                 List<ProtectedFile> protectedFiles) {

        /** The same order, presented through another (newer) link sent by {@code senderUserId}. */
        SignableOrder viaLink(SentLink link, long senderUserId) {
            return new SignableOrder(orderId, orderNumber, currentVersion, link.token(), link.invoiceTokenId(),
                    senderUserId, acceptedQuoteVersionId, issuedQuoteVersionId, issuedQuoteTokenId, issuedQuoteToken,
                    currentPdfBytes, protectedFiles);
        }

        /** The same order after another request appended an unsigned current version (its PDF becomes protected). */
        SignableOrder withCurrentVersion(int version, ProtectedFile currentPdf) {
            List<ProtectedFile> files = new ArrayList<>(protectedFiles);
            files.add(currentPdf);
            return new SignableOrder(orderId, orderNumber, version, invoiceToken, invoiceTokenId, linkSenderUserId,
                    acceptedQuoteVersionId, issuedQuoteVersionId, issuedQuoteTokenId, issuedQuoteToken,
                    currentPdf.bytes(), List.copyOf(files));
        }
    }

    /** One pre-existing file: its stored_file row and its bytes on disk at seed time. */
    private record ProtectedFile(String label, long storedFileId, Map<String, Object> row, String storagePath,
                                 byte[] bytes) {
    }

    /**
     * One public accept: the response, the log events it produced on the PublicInvoiceService, OrderInvoiceService
     * and GlobalExceptionHandler loggers, the delivered store notifications observed at send time, and its window.
     */
    private record AcceptAttempt(MvcResult result, List<ILoggingEvent> publicLog, List<ILoggingEvent> staffLog,
                                 List<ILoggingEvent> errorLog, List<NotificationSend> notificationSends,
                                 LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** A delivered store notification as observed at send time. */
    private record NotificationSend(boolean transactionActive, long committedSignedVersions) {
    }

    /** The signing transaction as seen from inside, in its beforeCommit (after every statement, before COMMIT). */
    private record InFlightSigning(long invoiceId, int invoiceVersion, Timestamp invoiceAcceptedAt,
                                   Long invoiceCreatedByUserId, String invoiceTokenStatus, Timestamp invoiceTokenDeadAt,
                                   String issuedQuoteStatus, String issuedQuoteTokenStatus,
                                   Timestamp issuedQuoteTokenDeadAt, String acceptedQuoteStatus, Timestamp mirror) {
    }

    /** Every committed row the remote accept could touch, plus the order's storage dir (equality = unchanged). */
    private record CommittedState(
            Map<String, Object> business,
            Map<String, Object> store,
            Map<String, Object> salesOrder,
            List<Map<String, Object>> invoices,
            List<Map<String, Object>> storedFiles,
            Set<String> filesOnDisk,
            List<Map<String, Object>> invoiceTokens,
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

    /** What each observed customer send saw at the moment it was attempted. */
    private static final class SendObservations {
        private final List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
        private final List<Long> committedSignedVersions = new CopyOnWriteArrayList<>();
        private final List<String> committedInvoiceTokenStatus = new CopyOnWriteArrayList<>();
        private final List<String> committedQuoteTokenStatus = new CopyOnWriteArrayList<>();
        private final List<String> committedCustomerEmails = new CopyOnWriteArrayList<>();
        private final List<String> committedStoreEmails = new CopyOnWriteArrayList<>();

        int size() {
            return transactionActive.size();
        }

        List<Boolean> transactionActive() {
            return List.copyOf(transactionActive);
        }

        List<Long> committedSignedVersions() {
            return List.copyOf(committedSignedVersions);
        }

        List<String> committedInvoiceTokenStatus() {
            return List.copyOf(committedInvoiceTokenStatus);
        }

        List<String> committedQuoteTokenStatus() {
            return List.copyOf(committedQuoteTokenStatus);
        }

        /** The committed (saved) {@code order_customer.email} at send time, exactly as stored (untrimmed). */
        List<String> committedCustomerEmails() {
            return List.copyOf(committedCustomerEmails);
        }

        /** The committed {@code store.email} at send time, exactly as stored. */
        List<String> committedStoreEmails() {
            return List.copyOf(committedStoreEmails);
        }
    }

    /** What the afterCommit email moves observed and did (single-threaded test; one entry per run). */
    private static final class EmailMoves {
        private final List<Boolean> hookInsideSigningTransaction = new CopyOnWriteArrayList<>();
        private final List<Boolean> autoCommit = new CopyOnWriteArrayList<>();
        private final List<Long> signedVersionsCommittedAtMove = new CopyOnWriteArrayList<>();
        private final List<String> customerEmailsBeforeMove = new CopyOnWriteArrayList<>();
        private final List<String> storeEmailsBeforeMove = new CopyOnWriteArrayList<>();
        private final List<Integer> customerRowsMoved = new CopyOnWriteArrayList<>();
        private final List<Integer> storeRowsMoved = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();
    }
}
