package com.flooring.salesportal.order;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.InvoiceLinkEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceFile;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
import com.flooring.salesportal.order.InvoiceTokenRepository.InvoiceTokenRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 16G PR 4 (16G contract §9.3 "One snapshot", §9.5 step 3, §7.6 "Never mixed"; the PR 4 row of §16) -
 * the public, token-only invoice GET view and pdf read the token status, the order, the current invoice
 * version and (pdf) its stored file in ONE read-only {@code REPEATABLE_READ} transaction that is separate
 * from the token gate. Two consequences are proven here, deterministically, with REAL concurrent writers
 * that really commit on their own connections:
 * <ol>
 *   <li><b>Gate-to-snapshot</b> (sections 1, 2 and 5): a link killed AFTER the gate found it {@code ACTIVE}
 *       and BEFORE the snapshot's first read is reported in its dead state - the GET is 200 with the minimal
 *       dead payload, the pdf is 410 with the dead code - never with {@code ACTIVE} content or PDF bytes;
 *       and a dead link wins over a mismatching {@code version_number} (410, not 409).</li>
 *   <li><b>Within the read</b> (sections 3 and 4): a writer that commits AFTER the snapshot read the link
 *       {@code ACTIVE} - a new signed version (D.8), a new payment version, a Rewrite - is invisible to the
 *       rest of that snapshot: the response is the pre-writer moment as a whole (the old version's number,
 *       words, dates and money; for the pdf exactly the compared version's stored bytes), never a 500, never
 *       the writer's version, never a mix of the two moments.</li>
 * </ol>
 *
 * <p><b>Deterministic interleavings (no sleeps, no production hooks).</b> The two {@link MockitoSpyBean}
 * spies below call the REAL methods. Right before the public request under test a {@link SnapshotRace}
 * installs {@code doAnswer(..).when(spy)} answers on four reads: the gate's hash lookup
 * ({@code invoiceTokenRepository.findByTokenHash}, observed only), the snapshot's token re-read
 * ({@code invoiceTokenRepository.findById}), the current version read
 * ({@code invoiceRepository.findCurrentByOrderId}) and the current file read
 * ({@code invoiceRepository.findCurrentFileByOrderId}). ONE of them is the race's hook point: the first time
 * that read runs ON THE REQUEST THREAD (MockMvc runs the request on the calling test thread) - and, for the
 * within-read points, only inside the snapshot transaction after its token re-read - the answer submits a
 * real writer request to another thread (its own MockMvc on the real Spring Security filter chain, its own
 * pooled connections, real commits), WAITS until it has finished and committed, and only then calls the real
 * read. The interleaving is therefore forced by construction. The writer calls the same spied methods on its
 * own thread; the answers pass those (and every call outside the request under test) straight to the real
 * method, so each hook fires once and only on the request thread. Every recorded read also captures the
 * request thread's transaction (active, isolation, read-only, and the identity of the bound
 * {@code ConnectionHolder}) so each case proves WHERE its hook fired: the gate in its own transaction, the
 * re-read and the hooked read inside the one {@code REPEATABLE_READ}, read-only snapshot. Inside the
 * snapshot, after the writer committed, the hook also queries the request's OWN connection
 * ({@code SHOW transaction_isolation}, {@code SHOW transaction_read_only}, the order's highest invoice
 * version and the presented token's status) and the writer thread reads the COMMITTED state right after its
 * request, so every case shows both moments side by side. The writer is always joined and its HTTP status
 * and message asserted; the spies are reset when the race closes and in {@code @AfterEach}.
 *
 * <p><b>What READ COMMITTED would break.</b> In PostgreSQL a {@code REPEATABLE_READ} snapshot is taken by
 * the transaction's first statement, here the token re-read. Sections 3 and 4 commit their writer AFTER that
 * statement, so under {@code READ COMMITTED} (a fresh snapshot per statement) the later reads would see the
 * writer's rows: the GET would hit the signed version under the {@code ACTIVE} link (the 500 invariant) or
 * render the payment / Rewrite version, and the pdf would compare against or stream the payment's version.
 * Sections 1, 2 and 5 commit their writer BEFORE the snapshot's first statement, so they are deliberately
 * NOT isolation-sensitive (each test says so): they pin that the snapshot RE-READS the token instead of
 * trusting the gate, and that the dead state is checked before the version.
 *
 * <p><b>Context and fixtures.</b> A NEW cached Spring context (the two spy bean overrides): the hook has to
 * run inside the production read path, between the gate and the snapshot's first read or inside the
 * snapshot, which no endpoint and no database trigger can reach. The class is NOT {@code @Transactional}:
 * the writers must commit for real on other connections while the request's snapshot is open, and every
 * assertion reads committed state. Each test seeds its own COMMITTED tenant (slug {@value #SLUG}, store
 * {@value #STORE_CODE} with an email, salesperson {@value #SALESPERSON_CODE}, ABN, bank details, accent,
 * HTTPS Stripe link and soft terms) and an invoice-ready order (customer with a valid email, INSTALLATION and
 * BILLING addresses, one {@value #CHARGE_CODE_PREFIX}-coded 100.00 ex / 40.00 cost charge line, details of
 * sale, lay date 2026-12-01 CONFIRMED); invoice v1 comes from the REAL D.1 endpoint (then committed-dated
 * 2026-09-15 so a version written today is distinguishable by its date) and the link from the REAL send-link
 * endpoint (the plaintext token read from the recorded link email). Writers use the real endpoints: D.2
 * Rewrite, D.8 in-store signing, send-link (a resend) and D.7 payment. No V4 identity is used.
 *
 * <p><b>Sweep.</b> {@code @BeforeEach} and {@code @AfterEach} reset both spies and every recording sender,
 * then remove everything the class commits, FK-safe: per order invoice_token (before sales_order and
 * app_user), quote_token, invoice, quote_version, quote_draft, payment_transaction, the other order
 * children, sales_order and the order's stored_file rows (then its files and directory); then the tenant's
 * store_charge / store_product, user_store_access, app_user, store, business_quick_description and business
 * rows and its storage directory.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class PublicInvoiceSnapshotIntegrationTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before anything is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant (never a V4 identity); the slug is the sweep's only key ----
    private static final String SLUG = "pr4-public-snapshot";
    private static final String BUSINESS_NAME = "Ironbark Snapshot Flooring";
    private static final String ABN = "51 824 753 556";
    private static final String BANK_NAME = "Ironbark Snapshot Bank";
    private static final String BSB = "062-000";
    private static final String ACCOUNT_NAME = "Ironbark Snapshot Flooring Pty Ltd";
    private static final String ACCOUNT_NUMBER = "12345678";
    private static final String ACCENT_COLOUR = "#0F766E";
    private static final String STRIPE_LINK = "https://buy.stripe.com/test_public_snapshot";
    private static final String SOFT_TERMS = "<p>Snapshot soft terms: the balance is due before installation.</p>";
    private static final String STORE_CODE = "IPS-ONE";
    private static final String STORE_EMAIL = "showroom.public.snapshot@example.com";
    private static final String SALESPERSON_CODE = "PS1";
    private static final String ORDER_NUMBER_PREFIX = STORE_CODE + "." + SALESPERSON_CODE + ".";
    private static final String CHARGE_CODE_PREFIX = "IPSNP";

    // ---- The seeded customer and sale ----
    private static final String CUSTOMER_FIRST_NAME = "Rowan";
    private static final String CUSTOMER_LAST_NAME = "Snapshot";
    /** The D.1 freeze of the saved name: v1's customer_name_snapshot. */
    private static final String CUSTOMER_NAME = CUSTOMER_FIRST_NAME + " " + CUSTOMER_LAST_NAME;
    private static final String CUSTOMER_EMAIL = "rowan.snapshot@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String DETAILS_OF_SALE = "Supply and lay carpet (public invoice snapshot test)";
    /** The live sale revised after v1 (section 3, Rewrite): the words and amounts a Rewrite freezes into v2. */
    private static final String REVISED_FIRST_NAME = "Rowena";
    private static final String REVISED_CUSTOMER_NAME = REVISED_FIRST_NAME + " " + CUSTOMER_LAST_NAME;
    private static final String REVISED_DETAILS_OF_SALE = "Supply and lay carpet and underlay (revised before the rewrite)";
    /** v1 is committed-dated on an earlier day; a payment or Rewrite version is dated today. */
    private static final LocalDate V1_INVOICE_DATE = LocalDate.of(2026, 9, 15);
    /** proposed_lay_date 2026-12-01 minus 2 days. */
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 11, 29);
    private static final String PAYMENT_AMOUNT = "50.00";

    // ---- Statuses, messages and codes (the 16G contract, exactly) ----
    private static final String TOKEN_ACTIVE = "ACTIVE";
    private static final String TOKEN_REPLACED = "REPLACED";
    private static final String TOKEN_REWRITTEN = "REWRITTEN";
    private static final String TOKEN_SIGNED = "SIGNED";
    private static final String TOKEN_EXPIRED = "EXPIRED";
    private static final String LOADED_MESSAGE = "Invoice loaded.";
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String INVOICE_REWRITTEN_MESSAGE = "Invoice rewritten.";
    private static final String ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String LINK_SENT_MESSAGE = "Invoice link emailed to the customer.";
    private static final String STALE_CODE = "INVOICE_VERSION_STALE";
    private static final String STALE_MESSAGE = "Invoice updated, please refresh";
    private static final Map<String, String> DEAD_CODES = Map.of(
            TOKEN_REPLACED, "INVOICE_LINK_REPLACED",
            TOKEN_REWRITTEN, "INVOICE_LINK_REWRITTEN",
            TOKEN_SIGNED, "INVOICE_LINK_SIGNED",
            "EXPIRED", "INVOICE_LINK_EXPIRED");
    private static final Map<String, String> DEAD_MESSAGES = Map.of(
            TOKEN_REPLACED, "This invoice link has been replaced. Please use the latest invoice link.",
            TOKEN_REWRITTEN, "This invoice has been revised. Please contact the store for the updated invoice.",
            TOKEN_SIGNED, "This invoice has already been signed. Please contact the store if you need a copy.",
            "EXPIRED", "This invoice link has expired. Please contact the store.");

    /** PublicInvoiceView: exactly these 27 keys, always present (16G contract §9.3). */
    private static final List<String> PUBLIC_VIEW_KEYS = List.of(
            "state", "business_name", "business_logo_url", "accent_color", "business_abn",
            "payment_account_name", "payment_bank_name", "payment_bsb", "payment_account_number",
            "payment_stripe_link_url", "order_number", "flooring_type", "version_number", "invoice_date",
            "due_date", "customer_name", "customer_address_line1", "customer_address_line2", "details_of_sale",
            "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst", "total_paid", "balance_due", "terms_html",
            "expires_at", "message");

    // ---- Routes and link format ----
    private static final String PUBLIC_INVOICES = "/api/v1/public/invoices/";
    private static final String PUBLIC_BASE_URL = "http://localhost:5173";
    private static final Pattern INVOICE_LINK_PATTERN = Pattern.compile("/i/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    // Children of sales_order deleted after invoice_token / quote_token / invoice / quote_version / quote_draft /
    // payment_transaction (every child FK is RESTRICT; the two *_line quote tables CASCADE from their parent).
    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "order_attachment", "order_note", "order_product_line", "order_charge_line",
            "order_address", "order_customer", "order_enquiry");

    // A real, decodable 1x1 PNG (the D.8 writer's signature).
    private static final byte[] SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final DateTimeFormatter WHOLE_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    /** Upper bound for one writer request run from inside a hook (it never waits on the request's snapshot). */
    private static final long WRITER_TIMEOUT_SECONDS = 60L;
    private static final long POOL_TERMINATION_SECONDS = 15L;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The key under which a transaction binds its ConnectionHolder (the identity recorded per read). */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    // Spies call the REAL methods unless a race stubs them (doAnswer().when(spy) form only). Each field is the
    // @Repository CGLIB proxy around the spy; Spring's SpringMockResolver lets doAnswer().when(..) and
    // reset(..) resolve through it.
    @MockitoSpyBean
    private InvoiceTokenRepository invoiceTokenRepository;

    @MockitoSpyBean
    private InvoiceRepository invoiceRepository;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    /** The request thread's MockMvc, on the REAL Spring Security filter chain. */
    private MockMvc mockMvc;

    /** This test's committed tenant (seeded by {@link #setUp} after the sweep). */
    private Tenant tenant;

    private int orderSequence;

    /** The day this test started: a version written during the test is dated between it and now. */
    private LocalDate testStartedOn;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes files recursively - never let it run against a real storage dir.
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class writes and sweeps files only under the shared test storage dir");
        Assertions.assertTrue(storageBase().endsWith(Path.of("target", "test-storage", "quote-acceptance")),
                "this class may only write under its own test storage dir, but was " + storageBase());
        testStartedOn = LocalDate.now();
        mockMvc = newMockMvc();
        cleanUpEverything();
        tenant = seedTenant();
    }

    @AfterEach
    void tearDown() {
        cleanUpEverything();
    }

    // ================================================================
    // 1. Gate-to-snapshot, GET view: the link dies after the gate and before the snapshot's first read
    // ================================================================

    /**
     * A manual Rewrite (D.2) commits after the gate found the link {@code ACTIVE} and before the snapshot's
     * token re-read: v2 is appended and the link dies {@code REWRITTEN} in the Rewrite's own transaction. The
     * snapshot, pinned by that re-read after the commit, sees {@code REWRITTEN}: 200 with exactly the minimal
     * {@code REWRITTEN} payload - neither v1's {@code ACTIVE} document (the gate's moment) nor the revised v2's
     * (the snapshot's moment). The read writes nothing.
     *
     * <p>READ COMMITTED: not isolation-sensitive - the re-read is the snapshot's first statement, so READ
     * COMMITTED would see the committed {@code REWRITTEN} too and this test would still pass. What it pins is
     * the re-read itself: a GET that trusted the gate's {@code ACTIVE} result would render v2's {@code ACTIVE}
     * document behind a {@code REWRITTEN} link.
     */
    @Test
    void view_rewriteCommitsBetweenTheGateAndTheSnapshotTokenReRead_returns200RewrittenDeadPayload_neverActiveContent()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "GET view, a Rewrite committed between the gate and the snapshot";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.TOKEN_RE_READ, order,
                writer("Rewrite (D.2)", order, rewrite(order, 1)))) {
            result = race.perform(publicView(order.token()));
            writer = race.assertGateToSnapshot(201, INVOICE_REWRITTEN_MESSAGE, TOKEN_REWRITTEN);
        }

        assertDeadView(result, TOKEN_REWRITTEN, label);
        assertRewriteCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the GET wrote nothing");
        assertMessages(0, 0, label);
    }

    /**
     * An in-store signing (D.8) commits after the gate found the link {@code ACTIVE} and before the
     * snapshot's token re-read: the signed v2 is appended and the link dies {@code SIGNED} at the signing
     * time. The snapshot sees {@code SIGNED}: 200 with exactly the minimal {@code SIGNED} payload - never the
     * signable v1 document, never the signed v2 presented as signable, never the 500 of the invariant.
     *
     * <p>READ COMMITTED: not isolation-sensitive (the re-read is the first statement and would see
     * {@code SIGNED} under READ COMMITTED as well). A GET that trusted the gate would instead reach the signed
     * current version under an {@code ACTIVE} state.
     */
    @Test
    void view_inStoreSigningCommitsBetweenTheGateAndTheSnapshotTokenReRead_returns200SignedDeadPayload_neverActiveContent()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "GET view, an in-store signing committed between the gate and the snapshot";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.TOKEN_RE_READ, order,
                writer("in-store signing (D.8) of v1", order, inStoreSigning(order, 1)))) {
            result = race.perform(publicView(order.token()));
            writer = race.assertGateToSnapshot(201, ACCEPT_EMAILED_MESSAGE, TOKEN_SIGNED);
        }

        assertDeadView(result, TOKEN_SIGNED, label);
        assertInStoreSigningCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the GET wrote nothing");
        assertMessages(1, 0, label);
        assertSignedPdfEmail(order, 2, label);
    }

    /**
     * A resend (send-link while the link is {@code ACTIVE}) commits after the gate and before the snapshot's
     * token re-read: the presented link dies {@code REPLACED} and a new {@code ACTIVE} link is minted. The
     * snapshot sees the presented link {@code REPLACED}: 200 with exactly the minimal {@code REPLACED} payload,
     * although the invoice itself (v1) did not change. The replacement link shows the unchanged v1.
     *
     * <p>READ COMMITTED: not isolation-sensitive (the re-read is the first statement). A GET that trusted the
     * gate would show the live v1 document through a replaced link.
     */
    @Test
    void view_resendCommitsBetweenTheGateAndTheSnapshotTokenReRead_returns200ReplacedDeadPayload_neverActiveContent()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        Map<String, Object> v1 = invoiceRow(order.orderId(), 1);
        String label = "GET view, a resend committed between the gate and the snapshot";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.TOKEN_RE_READ, order,
                writer("resend of the invoice link", order, sendLinkRequest(order.orderId())))) {
            result = race.perform(publicView(order.token()));
            writer = race.assertGateToSnapshot(201, LINK_SENT_MESSAGE, TOKEN_REPLACED);
        }

        assertDeadView(result, TOKEN_REPLACED, label);
        String newToken = assertResendCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the GET wrote nothing");
        assertMessages(0, 1, label);

        // Control: the replacement link is live and shows the same, unchanged current version.
        assertActiveView(publicCall(mockMvc, publicView(newToken), label + ": GET through the replacement link"),
                order, v1, tokenRowByHash(newToken), label + ": GET through the replacement link");
    }

    // ================================================================
    // 2. Gate-to-snapshot, pdf: the link dies after the gate and before the snapshot's first read
    // ================================================================

    /**
     * A Rewrite commits between the gate and the snapshot's token re-read, and the request asks for
     * {@code version_number=2} - exactly the version the Rewrite makes current, so the version check would
     * pass and the dead link is the only reason to refuse. 410 {@code INVOICE_LINK_REWRITTEN}, the exact error
     * body, no PDF bytes and no Content-Disposition. Control: v2 IS servable through a new live link.
     *
     * <p>READ COMMITTED: not isolation-sensitive (the re-read is the first statement). A pdf that trusted the
     * gate would stream the rewritten v2's PDF through a {@code REWRITTEN} link.
     */
    @Test
    void pdf_rewriteCommitsBetweenTheGateAndTheSnapshotTokenReRead_versionNumberOfTheNewVersion_returns410Rewritten_noPdfBytes()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "pdf version_number=2, a Rewrite committed between the gate and the snapshot";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.TOKEN_RE_READ, order,
                writer("Rewrite (D.2)", order, rewrite(order, 1)))) {
            result = race.perform(publicPdf(order.token(), "2"));
            writer = race.assertGateToSnapshot(201, INVOICE_REWRITTEN_MESSAGE, TOKEN_REWRITTEN);
        }

        assertGone(result, TOKEN_REWRITTEN, label);
        assertRewriteCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the pdf read wrote nothing");
        assertMessages(0, 0, label);

        // Control: version 2 is the current version and is served through a live link - the 410 above was the
        // dead link alone.
        String newToken = sendLink(order.orderId(), order.orderNumber(), 2, label + ": a new link to v2");
        assertServesStoredPdf(publicCall(mockMvc, publicPdf(newToken, "2"), label + ": v2 through a new link"),
                order, 2, label + ": v2 through a new link");
    }

    /**
     * An in-store signing commits between the gate and the snapshot's token re-read, and the request asks for
     * {@code version_number=2} - the signed version D.8 makes current. 410 {@code INVOICE_LINK_SIGNED}, no PDF
     * bytes: never the signed PDF through the link and never the 500 of the signed-under-{@code ACTIVE}
     * invariant.
     *
     * <p>READ COMMITTED: not isolation-sensitive (the re-read is the first statement). A pdf that trusted the
     * gate would reach the signed current version under an {@code ACTIVE} state.
     */
    @Test
    void pdf_inStoreSigningCommitsBetweenTheGateAndTheSnapshotTokenReRead_versionNumberOfTheSignedVersion_returns410Signed_noPdfBytes()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "pdf version_number=2, an in-store signing committed between the gate and the snapshot";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.TOKEN_RE_READ, order,
                writer("in-store signing (D.8) of v1", order, inStoreSigning(order, 1)))) {
            result = race.perform(publicPdf(order.token(), "2"));
            writer = race.assertGateToSnapshot(201, ACCEPT_EMAILED_MESSAGE, TOKEN_SIGNED);
        }

        assertGone(result, TOKEN_SIGNED, label);
        assertInStoreSigningCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the pdf read wrote nothing");
        assertMessages(1, 0, label);
        assertSignedPdfEmail(order, 2, label);
    }

    /**
     * A resend commits between the gate and the snapshot's token re-read, and the request asks for
     * {@code version_number=1} - still the current version (a send never creates a version). 410
     * {@code INVOICE_LINK_REPLACED}, no PDF bytes. Control: the replacement link serves v1's stored bytes.
     *
     * <p>READ COMMITTED: not isolation-sensitive (the re-read is the first statement). A pdf that trusted the
     * gate would stream v1's PDF through a replaced link.
     */
    @Test
    void pdf_resendCommitsBetweenTheGateAndTheSnapshotTokenReRead_currentVersionNumber_returns410Replaced_noPdfBytes()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "pdf version_number=1, a resend committed between the gate and the snapshot";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.TOKEN_RE_READ, order,
                writer("resend of the invoice link", order, sendLinkRequest(order.orderId())))) {
            result = race.perform(publicPdf(order.token(), "1"));
            writer = race.assertGateToSnapshot(201, LINK_SENT_MESSAGE, TOKEN_REPLACED);
        }

        assertGone(result, TOKEN_REPLACED, label);
        String newToken = assertResendCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the pdf read wrote nothing");
        assertMessages(0, 1, label);

        // Control: the same version through the replacement link is served.
        assertServesStoredPdf(publicCall(mockMvc, publicPdf(newToken, "1"), label + ": v1 through the replacement link"),
                order, 1, label + ": v1 through the replacement link");
    }

    // ================================================================
    // 3. Within the GET snapshot: a writer commits after the snapshot read the link ACTIVE
    // ================================================================

    /**
     * The snapshot has re-read the link {@code ACTIVE}; then, before it reads the current version, an
     * in-store signing commits the signed v2 and kills the link {@code SIGNED}. Under the snapshot the current
     * version is still the unsigned v1, consistent with the {@code ACTIVE} link read a moment earlier: 200
     * {@code ACTIVE} with v1's number, words, dates and money exactly - never a 500, never the signed v2's
     * content, never a mix. The next GET (a new snapshot) reports {@code SIGNED}.
     *
     * <p>READ COMMITTED: the version read would be a new statement-level snapshot and return the committed,
     * SIGNED v2 under the {@code ACTIVE} link read before it - the signed-under-{@code ACTIVE} invariant breach,
     * a 500 (or, without that check, a signed version presented as signable).
     */
    @Test
    void view_inStoreSigningCommitsAfterTheSnapshotReadTheActiveLink_returns200ActiveWithTheOldUnsignedVersion_never500()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        Map<String, Object> v1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> token = tokenRow(order.tokenId());
        String label = "GET view, an in-store signing committed inside the snapshot before its version read";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.CURRENT_VERSION_READ, order,
                writer("in-store signing (D.8) of v1", order, inStoreSigning(order, 1)))) {
            result = race.perform(publicView(order.token()));
            writer = race.assertWithinRead(201, ACCEPT_EMAILED_MESSAGE, "v1 unsigned", 1, TOKEN_SIGNED, 2);
        }

        assertActiveView(result, order, v1, token, label);
        assertInStoreSigningCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the GET wrote nothing");
        assertMessages(1, 0, label);
        assertSignedPdfEmail(order, 2, label);

        // A new read is a new snapshot: the committed truth is the SIGNED link.
        assertDeadView(publicCall(mockMvc, publicView(order.token()), label + ": the next GET"), TOKEN_SIGNED,
                label + ": the next GET");
    }

    /**
     * The snapshot has re-read the link {@code ACTIVE}; then, before it reads the current version, a payment
     * commits v2 (dated today, 50.00 paid, 60.00 due). Under the snapshot the current version is still v1:
     * 200 {@code ACTIVE} with v1's number, its 2026-09-15 date and its 0.00 paid / 110.00 due - consistently,
     * never v2's amounts with v1's number or the other way round. The link survives a payment, so the next GET
     * (a new snapshot) shows v2.
     *
     * <p>READ COMMITTED: the version read would return the committed payment version v2, so the response
     * would carry version 2, today's date and the new amounts although the snapshot began before the payment.
     */
    @Test
    void view_paymentCommitsAfterTheSnapshotReadTheActiveLink_returns200ActiveWithTheOldVersionAndOldAmountsConsistently()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        Map<String, Object> v1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> token = tokenRow(order.tokenId());
        String label = "GET view, a payment committed inside the snapshot before its version read";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.CURRENT_VERSION_READ, order,
                writer("payment of " + PAYMENT_AMOUNT, order, recordPayment(order, PAYMENT_AMOUNT)))) {
            result = race.perform(publicView(order.token()));
            writer = race.assertWithinRead(201, PAYMENT_RECORDED_MESSAGE, "v1 unsigned", 1, TOKEN_ACTIVE, 2);
        }

        assertActiveView(result, order, v1, token, label);
        Map<String, Object> v2 = assertPaymentCommitted(order, v1, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the GET wrote nothing");
        assertMessages(0, 0, label);

        // The link is order-bound (16G contract §7.4): a new snapshot shows the payment version.
        assertActiveView(publicCall(mockMvc, publicView(order.token()), label + ": the next GET"), order, v2, token,
                label + ": the next GET");
    }

    /**
     * The live sale is revised (customer first name, details of sale, a doubled charge line), then the snapshot
     * re-reads the link {@code ACTIVE}; before it reads the current version a Rewrite commits v2 with the
     * revised words and amounts and kills the link {@code REWRITTEN}. Under the snapshot the current version is
     * still v1: 200 {@code ACTIVE} with v1's words ({@value #CUSTOMER_NAME}, the original details), date and
     * money - never the revised v2's content beside the {@code ACTIVE} state. The next GET reports
     * {@code REWRITTEN}.
     *
     * <p>READ COMMITTED: the version read would return the committed Rewrite version, so the response would
     * mix the {@code ACTIVE} state read before the Rewrite with the revised name, details, date and totals the
     * Rewrite made official - content the {@code REWRITTEN} link must never show.
     */
    @Test
    void view_rewriteCommitsAfterTheSnapshotReadTheActiveLink_returns200ActiveWithTheOldVersionsWords_neverTheRewrittenWords()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        reviseLiveSale(order);
        Map<String, Object> v1 = invoiceRow(order.orderId(), 1);
        Map<String, Object> token = tokenRow(order.tokenId());
        String label = "GET view, a Rewrite of the revised sale committed inside the snapshot before its version read";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.CURRENT_VERSION_READ, order,
                writer("Rewrite (D.2) of the revised sale", order, rewrite(order, 1)))) {
            result = race.perform(publicView(order.token()));
            writer = race.assertWithinRead(201, INVOICE_REWRITTEN_MESSAGE, "v1 unsigned", 1, TOKEN_REWRITTEN, 2);
        }

        assertActiveView(result, order, v1, token, label);
        assertRewriteCommitted(order, writer.committed(), label);
        // The discriminator: the committed v2 carries different words, date and money than the v1 just served.
        Map<String, Object> v2 = writer.committed().invoice(2);
        Assertions.assertEquals(REVISED_CUSTOMER_NAME, v2.get("customer_name_snapshot"),
                label + ": the Rewrite froze the revised name into v2");
        Assertions.assertEquals(REVISED_DETAILS_OF_SALE, v2.get("details_of_sale_snapshot"),
                label + ": the Rewrite froze the revised details into v2");
        assertMoneyEquals(new BigDecimal("220.00"), v2.get("sale_price_inc_gst"), label + ": v2's revised inc total");
        assertDatedToday(v2.get("invoice_date"), label + ": v2 (the Rewrite dates its version today)");
        Assertions.assertEquals(CUSTOMER_NAME, v1.get("customer_name_snapshot"), label + ": v1 kept its frozen name");
        Assertions.assertEquals(DETAILS_OF_SALE, v1.get("details_of_sale_snapshot"), label + ": v1 kept its details");
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the GET wrote nothing");
        assertMessages(0, 0, label);

        assertDeadView(publicCall(mockMvc, publicView(order.token()), label + ": the next GET"), TOKEN_REWRITTEN,
                label + ": the next GET");
    }

    // ================================================================
    // 4. Within the pdf snapshot: a payment commits after the snapshot read the link ACTIVE
    // ================================================================

    /**
     * {@code version_number=1}: the snapshot re-reads the link {@code ACTIVE}, reads v1 and compares it (equal),
     * then - before it reads the current version's stored file - a payment commits v2 with its OWN stored PDF.
     * Under the snapshot the current file is still v1's: 200 {@code application/pdf} with exactly v1's stored
     * bytes and {@code Content-Disposition: inline; filename="invoice-{order}-v1.pdf"}, never v2's file under
     * v1's number. Afterwards (new snapshots) {@code version_number=1} is stale (409) and v2 is served.
     *
     * <p>READ COMMITTED: the file read would be a new statement-level snapshot and return v2's committed file
     * row, so the response would stream the payment version's PDF (and name) for a request whose version
     * comparison matched v1.
     */
    @Test
    void pdf_paymentCommitsBetweenTheVersionComparisonAndTheStoredFileRead_servesExactlyTheComparedVersionsStoredBytes()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "pdf version_number=1, a payment committed inside the snapshot before its file read";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.CURRENT_FILE_READ, order,
                writer("payment of " + PAYMENT_AMOUNT, order, recordPayment(order, PAYMENT_AMOUNT)))) {
            result = race.perform(publicPdf(order.token(), "1"));
            writer = race.assertWithinRead(201, PAYMENT_RECORDED_MESSAGE, invoicePdfName(order.orderNumber(), 1), 1,
                    TOKEN_ACTIVE, 2);
            race.assertVersionComparedInTheSnapshotBeforeTheHook("v1 unsigned");
        }

        assertServesStoredPdf(result, order, 1, label);
        assertPaymentCommitted(order, invoiceRow(order.orderId(), 1), writer.committed(), label);
        assertDistinctStoredPdfs(order, result, label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the pdf read wrote nothing");
        assertMessages(0, 0, label);

        // New snapshots: v1 is no longer current (409, never its bytes), v2 is served.
        assertStalePdf(publicCall(mockMvc, publicPdf(order.token(), "1"), label + ": v1 afterwards"),
                label + ": v1 afterwards");
        assertServesStoredPdf(publicCall(mockMvc, publicPdf(order.token(), "2"), label + ": v2 afterwards"),
                order, 2, label + ": v2 afterwards");
    }

    /**
     * {@code version_number=1}: the snapshot re-reads the link {@code ACTIVE}; then, before it reads the
     * current version, a payment commits v2. Under the snapshot the current version is still v1, so
     * {@code version_number=1} matches (no 409) and the file read in the same snapshot is v1's: 200 with
     * exactly v1's stored bytes.
     *
     * <p>READ COMMITTED: the version read would return the committed v2, so {@code version_number=1} would be
     * refused 409 {@code INVOICE_VERSION_STALE} although the request began (and its link was read) before the
     * payment.
     */
    @Test
    void pdf_paymentCommitsAfterTheSnapshotReadTheActiveLink_versionNumberComparesWithTheSnapshotVersion_servesItsStoredBytes()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "pdf version_number=1, a payment committed inside the snapshot before its version read";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.CURRENT_VERSION_READ, order,
                writer("payment of " + PAYMENT_AMOUNT, order, recordPayment(order, PAYMENT_AMOUNT)))) {
            result = race.perform(publicPdf(order.token(), "1"));
            writer = race.assertWithinRead(201, PAYMENT_RECORDED_MESSAGE, "v1 unsigned", 1, TOKEN_ACTIVE, 2);
            race.assertFileReadInTheSnapshotAfterTheHook(invoicePdfName(order.orderNumber(), 1));
        }

        assertServesStoredPdf(result, order, 1, label);
        assertPaymentCommitted(order, invoiceRow(order.orderId(), 1), writer.committed(), label);
        assertDistinctStoredPdfs(order, result, label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the pdf read wrote nothing");
        assertMessages(0, 0, label);

        assertStalePdf(publicCall(mockMvc, publicPdf(order.token(), "1"), label + ": v1 afterwards"),
                label + ": v1 afterwards");
        assertServesStoredPdf(publicCall(mockMvc, publicPdf(order.token(), "2"), label + ": v2 afterwards"),
                order, 2, label + ": v2 afterwards");
    }

    // ================================================================
    // 5. Dead before stale inside the pdf snapshot
    // ================================================================

    /**
     * A Rewrite commits between the gate and the snapshot's token re-read and the request carries
     * {@code version_number=1}, the version its page showed - inside the snapshot the link is {@code REWRITTEN}
     * AND the version is stale (current v2, seen by the snapshot's own probe). Dead first: 410
     * {@code INVOICE_LINK_REWRITTEN}, not 409. Control: through a new live link the same
     * {@code version_number=1} IS refused 409, so the 410 is the precedence, not a missing staleness check.
     *
     * <p>READ COMMITTED: not isolation-sensitive (both facts are committed before the snapshot's first
     * statement). It pins the order inside the snapshot: the token status is checked before the version.
     */
    @Test
    void pdf_rewriteKillsTheLinkBeforeTheSnapshotAndItsVersionNumberIsStale_returns410RewrittenNot409()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "pdf version_number=1, REWRITTEN and stale inside the snapshot";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.TOKEN_RE_READ, order,
                writer("Rewrite (D.2)", order, rewrite(order, 1)))) {
            result = race.perform(publicPdf(order.token(), "1"));
            writer = race.assertGateToSnapshot(201, INVOICE_REWRITTEN_MESSAGE, TOKEN_REWRITTEN);
            Assertions.assertEquals(2, race.probe().maxInvoiceVersion(),
                    label + ": inside the snapshot version_number=1 is stale (the current version is v2)");
        }

        assertGone(result, TOKEN_REWRITTEN, label);
        assertRewriteCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the pdf read wrote nothing");
        assertMessages(0, 0, label);

        String newToken = sendLink(order.orderId(), order.orderNumber(), 2, label + ": a new link to v2");
        assertStalePdf(publicCall(mockMvc, publicPdf(newToken, "1"), label + ": version 1 through a live link"),
                label + ": version 1 through a live link");
    }

    /**
     * An in-store signing commits between the gate and the snapshot's token re-read and the request carries
     * {@code version_number=1}: inside the snapshot the link is {@code SIGNED}, the version is stale (current
     * v2) and v2 is signed. Dead first: 410 {@code INVOICE_LINK_SIGNED} - not the 409, and not the 500 of the
     * signed-under-{@code ACTIVE} invariant.
     *
     * <p>READ COMMITTED: not isolation-sensitive (everything is committed before the snapshot's first
     * statement); it pins that the dead status is checked first.
     */
    @Test
    void pdf_inStoreSigningKillsTheLinkBeforeTheSnapshotAndItsVersionNumberIsStale_returns410SignedNot409Not500()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "pdf version_number=1, SIGNED and stale inside the snapshot";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.TOKEN_RE_READ, order,
                writer("in-store signing (D.8) of v1", order, inStoreSigning(order, 1)))) {
            result = race.perform(publicPdf(order.token(), "1"));
            writer = race.assertGateToSnapshot(201, ACCEPT_EMAILED_MESSAGE, TOKEN_SIGNED);
            Assertions.assertEquals(2, race.probe().maxInvoiceVersion(),
                    label + ": inside the snapshot version_number=1 is stale (the current version is v2)");
        }

        assertGone(result, TOKEN_SIGNED, label);
        assertInStoreSigningCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the pdf read wrote nothing");
        assertMessages(1, 0, label);
        assertSignedPdfEmail(order, 2, label);
    }

    /**
     * A resend commits between the gate and the snapshot's token re-read and the request carries
     * {@code version_number=2}, a version that does not exist (current v1): inside the snapshot the presented
     * link is {@code REPLACED} AND the number mismatches. Dead first: 410 {@code INVOICE_LINK_REPLACED}, not
     * 409. Control: the live replacement link refuses {@code version_number=2} 409 and serves version 1.
     *
     * <p>READ COMMITTED: not isolation-sensitive (the kill is committed before the snapshot's first
     * statement); it pins that the dead status is checked first.
     */
    @Test
    void pdf_resendKillsTheLinkBeforeTheSnapshotAndItsVersionNumberMismatches_returns410ReplacedNot409()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "pdf version_number=2, REPLACED and mismatching inside the snapshot";

        MvcResult result;
        WriterOutcome writer;
        try (SnapshotRace race = new SnapshotRace(label, HookPoint.TOKEN_RE_READ, order,
                writer("resend of the invoice link", order, sendLinkRequest(order.orderId())))) {
            result = race.perform(publicPdf(order.token(), "2"));
            writer = race.assertGateToSnapshot(201, LINK_SENT_MESSAGE, TOKEN_REPLACED);
            Assertions.assertEquals(1, race.probe().maxInvoiceVersion(),
                    label + ": inside the snapshot the current version is v1, so version_number=2 mismatches");
        }

        assertGone(result, TOKEN_REPLACED, label);
        String newToken = assertResendCommitted(order, writer.committed(), label);
        assertSameCommittedState(writer.committed(), committedState(order), label + ": the pdf read wrote nothing");
        assertMessages(0, 1, label);

        assertStalePdf(publicCall(mockMvc, publicPdf(newToken, "2"), label + ": version 2 through the live link"),
                label + ": version 2 through the live link");
        assertServesStoredPdf(publicCall(mockMvc, publicPdf(newToken, "1"), label + ": version 1 through the live link"),
                order, 1, label + ": version 1 through the live link");
    }

    // ================================================================
    // 6. Expiry falling due between the gate and the snapshot: the gate runs again, its flip commits first
    // ================================================================

    /**
     * The link is ACTIVE and unexpired at the gate; its expiry then falls due (its {@code expires_at} moved into
     * the past on a separate auto-commit connection) just before the snapshot's first statement, so the snapshot
     * reads it ACTIVE but over-age. The view neither presents it nor writes inside its read-only snapshot: it
     * sends the request through the gate once more, whose lazy expiry takes the order lock, re-reads the link,
     * flips it to {@code EXPIRED} and COMMITS, and only then reads the - now dead - state (16G contract §7.7:
     * the flip commits before the dead response). 200 with exactly the minimal {@code EXPIRED} payload; the
     * link row is {@code EXPIRED} with {@code dead_at} set, read afterwards on auto-commit JDBC; the order,
     * invoice and stored-file rows and the files on disk are untouched; nothing is sent.
     */
    @Test
    void view_expiryFallsDueBetweenTheGateAndTheSnapshot_theGateRunsAgainAndCommitsTheFlip_returns200ExpiredDeadPayload()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        CommittedState before = committedState(order);
        String label = "GET view, the link's expiry falling due between the gate and the snapshot";

        MvcResult result;
        try (ExpiryDueBeforeSnapshot hook = new ExpiryDueBeforeSnapshot(order)) {
            result = hook.perform(publicView(order.token()), label);
            hook.assertTheGateRanAgain(List.of(TOKEN_ACTIVE, TOKEN_ACTIVE, TOKEN_EXPIRED), label);
        }

        assertDeadView(result, TOKEN_EXPIRED, label);
        assertExpiryFlipCommittedAndNothingElse(order, before, label);
        assertMessages(0, 0, label);
    }

    /**
     * The pdf twin of the previous test: the snapshot reads the link ACTIVE but over-age, the gate runs again
     * and commits the {@code EXPIRED} flip, and the request is refused with its 410 - 410
     * {@code INVOICE_LINK_EXPIRED}, no PDF bytes, no Content-Disposition - with nothing else written.
     */
    @Test
    void pdf_expiryFallsDueBetweenTheGateAndTheSnapshot_theGateRunsAgainAndCommitsTheFlip_returns410Expired_noPdfBytes()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        CommittedState before = committedState(order);
        String label = "pdf version_number=1, the link's expiry falling due between the gate and the snapshot";

        MvcResult result;
        try (ExpiryDueBeforeSnapshot hook = new ExpiryDueBeforeSnapshot(order)) {
            result = hook.perform(publicPdf(order.token(), "1"), label);
            // The second gate's locked re-read flips the link; its 410 ends the request (no second snapshot).
            hook.assertTheGateRanAgain(List.of(TOKEN_ACTIVE, TOKEN_ACTIVE), label);
        }

        assertGone(result, TOKEN_EXPIRED, label);
        assertExpiryFlipCommittedAndNothingElse(order, before, label);
        assertMessages(0, 0, label);
    }

    /** The committed lazy-expiry flip (read on auto-commit JDBC) and no other committed change of the order. */
    private void assertExpiryFlipCommittedAndNothingElse(LinkedOrder order, CommittedState before, String label) {
        Map<String, Object> link = tokenRow(order.tokenId());
        Assertions.assertEquals(TOKEN_EXPIRED, link.get("status"), label + ": the EXPIRED flip is committed");
        Assertions.assertNotNull(link.get("dead_at"), label + ": the flip stamped dead_at");
        CommittedState after = committedState(order);
        Assertions.assertEquals(before.order(), after.order(), label + ": the order row is untouched");
        Assertions.assertEquals(before.invoices(), after.invoices(), label + ": no invoice row changes on expiry");
        Assertions.assertEquals(before.storedFiles(), after.storedFiles(), label + ": no stored_file row changes");
        Assertions.assertEquals(before.filesOnDisk(), after.filesOnDisk(), label + ": no file changes");
        Assertions.assertEquals(before.tokens().size(), after.tokens().size(), label + ": no link row added");
    }

    /**
     * A one-shot hook at the FIRST {@code invoiceTokenRepository.findById} on the request thread - the
     * snapshot's first statement, because the gate looks an unexpired link up by its hash only. Before that real
     * read runs, the link's {@code expires_at} is moved into the past on a separate auto-commit connection (the
     * JVM and database clocks both behind it), so the snapshot reads it ACTIVE but over-age. Every
     * {@code findById} and gate lookup on the request thread is recorded; the spies are reset on close.
     */
    private final class ExpiryDueBeforeSnapshot implements AutoCloseable {

        private final LinkedOrder order;
        private final AtomicBoolean fired = new AtomicBoolean(false);
        private final AtomicInteger gateLookups = new AtomicInteger();
        private final List<String> tokenReads = new CopyOnWriteArrayList<>();
        private volatile Thread requestThread;
        private volatile boolean recording;

        ExpiryDueBeforeSnapshot(LinkedOrder order) {
            this.order = order;
            doAnswer(invocation -> {
                if (recording && Thread.currentThread() == requestThread) {
                    gateLookups.incrementAndGet();
                }
                return invocation.callRealMethod();
            }).when(invoiceTokenRepository).findByTokenHash(anyString());
            doAnswer(invocation -> {
                boolean onRequestThread = recording && Thread.currentThread() == requestThread;
                if (onRequestThread && fired.compareAndSet(false, true)) {
                    moveExpiryIntoThePastOnAnotherConnection();
                }
                Object result = invocation.callRealMethod();
                if (onRequestThread) {
                    @SuppressWarnings("unchecked")
                    Optional<InvoiceTokenRow> read = (Optional<InvoiceTokenRow>) result;
                    tokenReads.add(read.map(InvoiceTokenRow::status).orElse("<no row>"));
                }
                return result;
            }).when(invoiceTokenRepository).findById(anyLong());
        }

        MvcResult perform(RequestBuilder publicRequest, String label) throws Exception {
            requestThread = Thread.currentThread();
            recording = true;
            try {
                return publicCall(mockMvc, publicRequest, label);
            } finally {
                recording = false;
            }
        }

        private void moveExpiryIntoThePastOnAnotherConnection() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE invoice_token SET expires_at = LEAST(now(), ?) - interval '1 minute' "
                                + "WHERE invoice_token_id = ? AND status = 'ACTIVE'")) {
                    update.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                    update.setLong(2, order.tokenId());
                    Assertions.assertEquals(1, update.executeUpdate(),
                            "the still-ACTIVE link's expiry is moved into the past (committed)");
                }
            }
        }

        /**
         * The hook fired at the snapshot's first read; the gate ran twice (the second run flipped the expiry);
         * the token reads on the request thread, in order, returned {@code expectedTokenReads}: the first
         * snapshot's ACTIVE-but-over-age read, the second gate's locked re-read (still ACTIVE before its flip)
         * and, for the view, the second snapshot's EXPIRED read.
         */
        void assertTheGateRanAgain(List<String> expectedTokenReads, String label) {
            Assertions.assertTrue(fired.get(), label + ": the hook fired at the snapshot's first token read");
            Assertions.assertEquals(2, gateLookups.get(),
                    label + ": the gate ran twice - again after the snapshot found the expiry due");
            Assertions.assertEquals(expectedTokenReads, tokenReads, label + ": the token reads on the request thread");
        }

        @Override
        public void close() {
            recording = false;
            Mockito.reset(invoiceTokenRepository, invoiceRepository);
        }
    }

    // ================================================================
    // 7. The staff read (D.3): the current version and the link in ONE snapshot
    // ================================================================

    /**
     * D.3 (the staff {@code GET .../invoices/current}) reads the current version and then the order's
     * {@code ACTIVE} link for the derived {@code invoice_link_expires_at}. An in-store signing (D.8) commits
     * between those two reads: it appends the signed v2 and kills the link {@code SIGNED} in one transaction.
     * D.3's read-only REPEATABLE_READ snapshot (pinned by its first statement, before the signing) answers with
     * one consistent moment: v1 unsigned WITH the link's expiry - never v1 unsigned with a null expiry, a pair
     * that never existed. A fresh D.3 afterwards shows the signed v2 with null.
     *
     * <p>READ COMMITTED: the link read would see the committed {@code SIGNED} link and return null next to the
     * unsigned v1 - the torn pair this test pins (a poller would stop on it without ever seeing the signature).
     */
    @Test
    void staffRead_inStoreSigningCommitsBetweenD3sVersionReadAndItsLinkRead_returnsOneConsistentPreSigningMoment()
            throws Exception {
        LinkedOrder order = seedLinkedOrder();
        String label = "D.3 with an in-store signing committed between its version read and its link read";
        String expectedExpiry = WHOLE_SECONDS.format(toLocalDateTime(tokenRow(order.tokenId()).get("expires_at")));

        Thread requestThread = Thread.currentThread();
        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicReference<WriterOutcome> signing = new AtomicReference<>();
        ExecutorService writerPool = Executors.newSingleThreadExecutor(writerThreadFactory());
        Callable<WriterOutcome> d8 = writer("in-store signing (D.8) of v1", order, inStoreSigning(order, 1));
        doAnswer(invocation -> {
            if (Thread.currentThread() == requestThread && armed.compareAndSet(true, false)) {
                // D.3 has already read v1 (unsigned) in its snapshot; the signing commits before the link read.
                signing.set(writerPool.submit(d8).get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(invoiceTokenRepository).findActiveByOrderId(anyLong());

        MvcResult read;
        try {
            read = mockMvc.perform(get(orderUrl(order.orderId()) + "/invoices/current").session(tenant.session()))
                    .andReturn();
        } finally {
            Mockito.reset(invoiceTokenRepository, invoiceRepository);
            writerPool.shutdownNow();
        }

        WriterOutcome outcome = signing.get();
        Assertions.assertNotNull(outcome, label + ": the signing ran between D.3's two reads");
        assertStatus(outcome.result(), 201, label + ": the concurrent in-store signing");
        Assertions.assertEquals(TOKEN_SIGNED, tokenRow(order.tokenId()).get("status"),
                label + ": the link is committed SIGNED");
        Assertions.assertEquals(List.of(1, 2), invoiceVersions(order.orderId()), label + ": the signed v2 is committed");

        assertStatus(read, 200, label);
        JsonNode snapshot = readJson(read).path("data").path("invoice");
        Assertions.assertEquals(1, snapshot.path("version_number").asInt(), label + ": D.3's snapshot version");
        Assertions.assertTrue(snapshot.path("accepted_at").isNull(), label + ": v1, unsigned");
        Assertions.assertEquals(expectedExpiry, snapshot.path("invoice_link_expires_at").asText(),
                label + ": the link as it was at that same moment (never a torn null)");

        MvcResult after = mockMvc.perform(get(orderUrl(order.orderId()) + "/invoices/current")
                .session(tenant.session())).andReturn();
        assertStatus(after, 200, label + ": D.3 after the signing");
        JsonNode signed = readJson(after).path("data").path("invoice");
        Assertions.assertEquals(2, signed.path("version_number").asInt(), label + ": the signed v2 is current");
        Assertions.assertFalse(signed.path("accepted_at").isNull(), label + ": v2 is signed");
        Assertions.assertTrue(signed.path("invoice_link_expires_at").isNull(),
                label + ": a signed current version has no live link");
        assertSignedPdfEmail(order, 2, label);
    }

    // ================================================================
    // The race harness
    // ================================================================

    /** The spied reads a race observes; every point except the gate lookup can be the race's hook point. */
    private enum HookPoint {
        /** {@code invoiceTokenRepository.findByTokenHash}: the gate's lookup (observed only, never a hook). */
        GATE_LOOKUP,
        /** {@code invoiceTokenRepository.findById}: the snapshot's token re-read (its FIRST statement). */
        TOKEN_RE_READ,
        /** {@code invoiceRepository.findCurrentByOrderId}: the snapshot's current-version read. */
        CURRENT_VERSION_READ,
        /** {@code invoiceRepository.findCurrentFileByOrderId}: the pdf snapshot's current stored-file read. */
        CURRENT_FILE_READ
    }

    /**
     * The request thread's transaction at a read: whether one is active, its isolation and read-only flags as
     * Spring bound them, and the identity of its bound {@code ConnectionHolder} (one per transaction).
     */
    private record TxContext(boolean active, Integer isolationLevel, boolean readOnly, Object connectionHolder) {

        boolean insideRepeatableReadSnapshot() {
            return active && readOnly && connectionHolder != null
                    && Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(isolationLevel);
        }

        boolean sameTransactionAs(TxContext other) {
            return connectionHolder != null && connectionHolder == other.connectionHolder;
        }
    }

    /** One instrumented read on the request thread: where it ran and what it returned (summarised). */
    private record ObservedRead(HookPoint point, TxContext tx, String result) {
    }

    /** What the request's OWN snapshot connection saw right after the writer committed. */
    private record SnapshotProbe(String isolation, String readOnly, Integer maxInvoiceVersion, String tokenStatus) {
    }

    /** The joined writer: its thread, its response, and the COMMITTED state read on its thread right after. */
    private record WriterOutcome(String label, String threadName, MvcResult result, boolean transactionActiveAtProbe,
                                 CommittedState committed) {
    }

    /**
     * One deterministic interleaving: the answers are installed on construction, armed for exactly one public
     * request ({@link #perform}), and the spies are reset on {@link #close}. The hook fires once: the first
     * time its point runs on the request thread (for the within-read points: inside the snapshot transaction
     * after its token re-read), it runs the writer to completion on another thread, probes the request's own
     * snapshot, and only then lets the real read proceed.
     */
    private final class SnapshotRace implements AutoCloseable {

        private final String label;
        private final HookPoint hookPoint;
        private final LinkedOrder order;
        private final Callable<WriterOutcome> writer;
        private final ExecutorService writerPool = Executors.newSingleThreadExecutor(writerThreadFactory());
        private final List<ObservedRead> reads = new CopyOnWriteArrayList<>();
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private final AtomicInteger firings = new AtomicInteger();
        private volatile boolean recording;
        private volatile Thread requestThread;
        private volatile ObservedRead hookedRead;
        private volatile SnapshotProbe probe;
        private volatile WriterOutcome writerOutcome;
        private volatile Throwable failure;

        SnapshotRace(String label, HookPoint hookPoint, LinkedOrder order, Callable<WriterOutcome> writer) {
            Assertions.assertNotEquals(HookPoint.GATE_LOOKUP, hookPoint, "the gate lookup is observed, never hooked");
            this.label = label;
            this.hookPoint = hookPoint;
            this.order = order;
            this.writer = writer;
            doAnswer(invocation -> onRead(HookPoint.GATE_LOOKUP, invocation))
                    .when(invoiceTokenRepository).findByTokenHash(anyString());
            doAnswer(invocation -> onRead(HookPoint.TOKEN_RE_READ, invocation))
                    .when(invoiceTokenRepository).findById(anyLong());
            doAnswer(invocation -> onRead(HookPoint.CURRENT_VERSION_READ, invocation))
                    .when(invoiceRepository).findCurrentByOrderId(anyLong());
            doAnswer(invocation -> onRead(HookPoint.CURRENT_FILE_READ, invocation))
                    .when(invoiceRepository).findCurrentFileByOrderId(anyLong());
        }

        /** The public request under test, on THIS thread (MockMvc runs it synchronously): the request thread. */
        MvcResult perform(RequestBuilder publicRequest) throws Exception {
            requestThread = Thread.currentThread();
            armed.set(true);
            recording = true;
            try {
                return publicCall(mockMvc, publicRequest, label);
            } finally {
                recording = false;
                armed.set(false);
            }
        }

        SnapshotProbe probe() {
            Assertions.assertNotNull(probe, () -> label + ": the snapshot was never probed; " + describe());
            return probe;
        }

        private Object onRead(HookPoint point, InvocationOnMock invocation) throws Throwable {
            if (!recording || Thread.currentThread() != requestThread) {
                // The writer's own calls (its thread) and everything outside the request under test.
                return invocation.callRealMethod();
            }
            TxContext tx = currentTx();
            boolean fire = point == hookPoint && mayFireAt(point, tx) && armed.compareAndSet(true, false);
            if (fire) {
                firings.incrementAndGet();
                runWriterToCompletion();
                if (point != HookPoint.TOKEN_RE_READ) {
                    // Within the read: the snapshot was pinned by its token re-read BEFORE the writer started.
                    probe = probeOwnSnapshot();
                }
            }
            Object result = invocation.callRealMethod();
            ObservedRead read = new ObservedRead(point, tx, summarise(result));
            reads.add(read);
            if (fire) {
                hookedRead = read;
                if (point == HookPoint.TOKEN_RE_READ) {
                    // Gate-to-snapshot: the real re-read - the snapshot's first statement - has just pinned it.
                    probe = probeOwnSnapshot();
                }
            }
            return result;
        }

        /**
         * The token re-read hook fires at the FIRST findById on the request thread; the within-read hooks only
         * inside the snapshot transaction, after its token re-read ran in that same transaction.
         */
        private boolean mayFireAt(HookPoint point, TxContext tx) {
            if (point == HookPoint.TOKEN_RE_READ) {
                return true;
            }
            return tx.insideRepeatableReadSnapshot() && reads.stream()
                    .anyMatch(read -> read.point() == HookPoint.TOKEN_RE_READ && read.tx().sameTransactionAs(tx));
        }

        /** Submit the writer to its own thread and wait until it has finished (committed) or failed. */
        private void runWriterToCompletion() {
            Future<WriterOutcome> future = writerPool.submit(writer);
            try {
                writerOutcome = future.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                failure = e.getCause();
                throw new IllegalStateException(label + ": the concurrent writer threw", e.getCause());
            } catch (TimeoutException e) {
                future.cancel(true);
                failure = e;
                throw new IllegalStateException(label + ": the concurrent writer did not finish", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = e;
                throw new IllegalStateException(label + ": interrupted while waiting for the writer", e);
            }
        }

        /**
         * On the request thread, inside its snapshot transaction: the transaction's real isolation and
         * read-only setting as PostgreSQL reports them, and what THIS snapshot sees of the order's invoice
         * versions and of the presented token after the writer committed.
         */
        private SnapshotProbe probeOwnSnapshot() {
            try {
                return new SnapshotProbe(
                        jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class),
                        jdbcTemplate.queryForObject("SHOW transaction_read_only", String.class),
                        jdbcTemplate.queryForObject("SELECT MAX(version_number) FROM invoice WHERE order_id = ?",
                                Integer.class, order.orderId()),
                        jdbcTemplate.queryForObject("SELECT status FROM invoice_token WHERE invoice_token_id = ?",
                                String.class, order.tokenId()));
            } catch (RuntimeException e) {
                failure = e;
                throw e;
            }
        }

        private TxContext currentTx() {
            return new TxContext(
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    TransactionSynchronizationManager.getResource(dataSource));
        }

        // ---- assertions on the interleaving (call after perform, inside the try-with-resources) ----

        /**
         * Gate-to-snapshot: the gate read the link ACTIVE in its own transaction; the hook fired at the FIRST
         * token re-read, inside the read-only REPEATABLE_READ snapshot (another transaction), after the writer
         * committed; that re-read returned the writer's dead status, and the snapshot (pinned by it) sees the
         * writer's committed versions.
         */
        WriterOutcome assertGateToSnapshot(int writerStatus, String writerMessage, String snapshotTokenStatus)
                throws Exception {
            WriterOutcome outcome = assertFiredOnceAndWriterSucceeded(writerStatus, writerMessage);
            ObservedRead gate = assertGateSawTheLinkActive();
            ObservedRead reRead = hookedRead;
            Assertions.assertSame(firstRead(HookPoint.TOKEN_RE_READ), reRead,
                    () -> label + ": the hook fired at the FIRST token re-read of the request thread; " + describe());
            assertInsideTheSnapshot(reRead, "the snapshot's token re-read");
            Assertions.assertNotSame(gate.tx().connectionHolder(), reRead.tx().connectionHolder(),
                    () -> label + ": the snapshot is a transaction of its own, separate from the gate's; " + describe());
            Assertions.assertTrue(indexOf(gate) < indexOf(reRead),
                    () -> label + ": the gate ran before the snapshot; " + describe());
            Assertions.assertEquals(snapshotTokenStatus, reRead.result(),
                    () -> label + ": the snapshot's re-read saw the writer's committed kill; " + describe());
            assertProbe(snapshotTokenStatus, outcome.committed().maxVersion());
            Assertions.assertEquals(snapshotTokenStatus, outcome.committed().token(order.tokenId()).get("status"),
                    label + ": the writer committed the presented link's death before the snapshot began");
            return outcome;
        }

        /**
         * Within the read: the gate and the snapshot's re-read both saw the link ACTIVE (the writer had not
         * run); the hook fired later, at {@code hookPoint}, in the SAME read-only REPEATABLE_READ transaction;
         * the writer committed {@code committedVersion} / {@code committedTokenStatus} there and then, yet the
         * hooked read and the request's own probe still saw the pre-writer moment.
         */
        WriterOutcome assertWithinRead(int writerStatus, String writerMessage, String hookedResult, int snapshotVersion,
                                       String committedTokenStatus, int committedVersion) throws Exception {
            WriterOutcome outcome = assertFiredOnceAndWriterSucceeded(writerStatus, writerMessage);
            ObservedRead gate = assertGateSawTheLinkActive();
            ObservedRead reRead = firstRead(HookPoint.TOKEN_RE_READ);
            Assertions.assertNotNull(reRead, () -> label + ": the snapshot re-read the token; " + describe());
            assertInsideTheSnapshot(reRead, "the snapshot's token re-read");
            Assertions.assertEquals(TOKEN_ACTIVE, reRead.result(),
                    () -> label + ": the snapshot's re-read ran before the writer and saw the link ACTIVE; " + describe());
            Assertions.assertEquals(hookPoint, hookedRead.point(), () -> label + ": the hook point; " + describe());
            assertInsideTheSnapshot(hookedRead, "the hooked read");
            Assertions.assertSame(reRead.tx().connectionHolder(), hookedRead.tx().connectionHolder(),
                    () -> label + ": the hooked read ran in the SAME transaction as the token re-read; " + describe());
            Assertions.assertNotSame(gate.tx().connectionHolder(), reRead.tx().connectionHolder(),
                    () -> label + ": the snapshot is a transaction of its own, separate from the gate's; " + describe());
            Assertions.assertTrue(indexOf(gate) < indexOf(reRead) && indexOf(reRead) < indexOf(hookedRead),
                    () -> label + ": gate, then token re-read, then the hooked read; " + describe());
            Assertions.assertEquals(hookedResult, hookedRead.result(),
                    () -> label + ": after the writer committed, the hooked read still returned the snapshot's row; "
                            + describe());
            assertProbe(TOKEN_ACTIVE, snapshotVersion);
            Assertions.assertEquals(committedTokenStatus, outcome.committed().token(order.tokenId()).get("status"),
                    label + ": the presented link's COMMITTED status after the writer");
            Assertions.assertEquals(committedVersion, outcome.committed().maxVersion(),
                    label + ": the order's COMMITTED current version after the writer");
            return outcome;
        }

        /** pdf, file-read hook: the version compared before the hook was read in the same snapshot. */
        void assertVersionComparedInTheSnapshotBeforeTheHook(String expectedResult) {
            ObservedRead versionRead = firstRead(HookPoint.CURRENT_VERSION_READ);
            Assertions.assertNotNull(versionRead, () -> label + ": the snapshot read the current version; " + describe());
            Assertions.assertSame(hookedRead.tx().connectionHolder(), versionRead.tx().connectionHolder(),
                    () -> label + ": the version read ran in the same snapshot as the file read; " + describe());
            Assertions.assertTrue(indexOf(versionRead) < indexOf(hookedRead),
                    () -> label + ": the version was read (and compared) before the file read; " + describe());
            Assertions.assertEquals(expectedResult, versionRead.result(),
                    () -> label + ": the compared version; " + describe());
        }

        /** pdf, version-read hook: the stored file was then read in the same snapshot and was the compared one's. */
        void assertFileReadInTheSnapshotAfterTheHook(String expectedFileName) {
            ObservedRead fileRead = firstRead(HookPoint.CURRENT_FILE_READ);
            Assertions.assertNotNull(fileRead, () -> label + ": the snapshot read the current file; " + describe());
            Assertions.assertSame(hookedRead.tx().connectionHolder(), fileRead.tx().connectionHolder(),
                    () -> label + ": the file read ran in the same snapshot as the version read; " + describe());
            Assertions.assertTrue(indexOf(hookedRead) < indexOf(fileRead),
                    () -> label + ": the file was read after the (hooked) version read; " + describe());
            Assertions.assertEquals(expectedFileName, fileRead.result(),
                    () -> label + ": the file the snapshot resolved; " + describe());
        }

        private WriterOutcome assertFiredOnceAndWriterSucceeded(int writerStatus, String writerMessage) throws Exception {
            Assertions.assertNull(failure, () -> label + ": the hook or its writer failed: " + stackTrace(failure));
            Assertions.assertEquals(1, firings.get(),
                    () -> label + ": the hook fires exactly once, at " + hookPoint + " on the request thread; "
                            + describe());
            Assertions.assertNotNull(hookedRead, () -> label + ": the hooked read completed; " + describe());
            WriterOutcome outcome = writerOutcome;
            Assertions.assertNotNull(outcome, () -> label + ": the writer was joined; " + describe());
            Assertions.assertNotEquals(requestThread.getName(), outcome.threadName(),
                    label + ": the writer ran on a thread of its own");
            Assertions.assertFalse(outcome.transactionActiveAtProbe(),
                    label + ": the writer thread read COMMITTED state (no transaction left open)");
            assertStatus(outcome.result(), writerStatus, label + ": the writer (" + outcome.label() + ")");
            Assertions.assertEquals(writerMessage, readJson(outcome.result()).path("message").asText(),
                    label + ": the writer's (" + outcome.label() + ") message");
            return outcome;
        }

        private ObservedRead assertGateSawTheLinkActive() {
            ObservedRead gate = firstRead(HookPoint.GATE_LOOKUP);
            Assertions.assertNotNull(gate, () -> label + ": the gate looked the token up by hash; " + describe());
            Assertions.assertEquals(TOKEN_ACTIVE, gate.result(),
                    () -> label + ": the gate found the presented link ACTIVE (the writer had not run); " + describe());
            Assertions.assertTrue(gate.tx().active() && gate.tx().connectionHolder() != null,
                    () -> label + ": the gate ran in a transaction of its own; " + describe());
            Assertions.assertFalse(gate.tx().readOnly(),
                    () -> label + ": the gate's transaction can commit a lazy-expiry flip (not read-only); " + describe());
            return gate;
        }

        private void assertInsideTheSnapshot(ObservedRead read, String what) {
            Assertions.assertTrue(read.tx().insideRepeatableReadSnapshot(),
                    () -> label + ": " + what + " ran inside the read-only REPEATABLE_READ snapshot transaction; "
                            + describe());
        }

        private void assertProbe(String tokenStatus, int maxVersion) {
            SnapshotProbe seen = probe();
            Assertions.assertEquals("repeatable read", seen.isolation(),
                    label + ": PostgreSQL runs the snapshot at REPEATABLE READ");
            Assertions.assertEquals("on", seen.readOnly(), label + ": PostgreSQL runs the snapshot read-only");
            Assertions.assertEquals(tokenStatus, seen.tokenStatus(),
                    label + ": the presented link's status inside the request's own snapshot");
            Assertions.assertEquals(maxVersion, seen.maxInvoiceVersion(),
                    label + ": the order's current version inside the request's own snapshot");
        }

        private ObservedRead firstRead(HookPoint point) {
            return reads.stream().filter(read -> read.point() == point).findFirst().orElse(null);
        }

        private int indexOf(ObservedRead read) {
            for (int i = 0; i < reads.size(); i++) {
                if (reads.get(i) == read) {
                    return i;
                }
            }
            return -1;
        }

        private String describe() {
            return "hook " + hookPoint + ", firings " + firings.get() + ", reads " + reads + ", probe " + probe
                    + ", writer " + (writerOutcome == null ? "<not joined>"
                    : writerOutcome.label() + " HTTP " + writerOutcome.result().getResponse().getStatus());
        }

        @Override
        public void close() {
            recording = false;
            armed.set(false);
            writerPool.shutdownNow();
            try {
                if (!writerPool.awaitTermination(POOL_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                    failure = failure == null ? new IllegalStateException(label + ": writer pool did not stop") : failure;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Mockito.reset(invoiceTokenRepository, invoiceRepository);
        }
    }

    /**
     * A writer: the given protected request on its own MockMvc (built here, on the real filter chain),
     * performed on the writer thread; right after it returns, the order's COMMITTED state is read on that same
     * thread (no transaction open there, so auto-commit reads).
     */
    private Callable<WriterOutcome> writer(String label, LinkedOrder order, RequestBuilder request) {
        MockMvc writerMvc = newMockMvc();
        return () -> {
            MvcResult result = writerMvc.perform(request).andReturn();
            boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            return new WriterOutcome(label, Thread.currentThread().getName(), result, transactionActive,
                    committedState(order));
        };
    }

    private static ThreadFactory writerThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "public-invoice-snapshot-writer-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String summarise(Object result) {
        if (!(result instanceof Optional<?> optional)) {
            return String.valueOf(result);
        }
        if (optional.isEmpty()) {
            return "<empty>";
        }
        Object value = optional.get();
        if (value instanceof InvoiceTokenRow token) {
            return token.status();
        }
        if (value instanceof InvoiceRow row) {
            return "v" + row.versionNumber() + (row.acceptedAt() == null ? " unsigned" : " signed");
        }
        if (value instanceof InvoiceFile file) {
            return file.fileName();
        }
        return String.valueOf(value);
    }

    private static String stackTrace(Throwable throwable) {
        if (throwable == null) {
            return "<none>";
        }
        StringWriter out = new StringWriter();
        throwable.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    // ================================================================
    // Public requests (EVERY public invoice request goes through publicCall)
    // ================================================================

    /**
     * Perform one public invoice request on the given MockMvc (the real filter chain) and assert, whatever
     * the status, that it neither carried nor created a session and that the response sets no cookie.
     */
    private static MvcResult publicCall(MockMvc mvc, RequestBuilder request, String label) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        Assertions.assertNull(result.getRequest().getSession(false),
                () -> label + ": a public request neither carries nor creates a session (status "
                        + result.getResponse().getStatus() + ")");
        Assertions.assertTrue(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).isEmpty(),
                () -> label + ": a public response sets no cookie, but was "
                        + result.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        Assertions.assertEquals(0, result.getResponse().getCookies().length,
                () -> label + ": a public response adds no cookie");
        return result;
    }

    private static RequestBuilder publicView(String token) {
        return get(URI.create(PUBLIC_INVOICES + token));
    }

    private static RequestBuilder publicPdf(String token, String versionNumber) {
        return get(URI.create(PUBLIC_INVOICES + token + "/pdf?version_number=" + versionNumber));
    }

    // ================================================================
    // Protected requests (the tenant's session; the writers and the setup)
    // ================================================================

    /**
     * The D.2 Rewrite request carrying {@code currentVersion} as its expected_version_number (Batch A PR 2, #149):
     * built before the race, so it carries the version that is current when the writer starts.
     */
    private RequestBuilder rewrite(LinkedOrder order, int currentVersion) {
        return post(orderUrl(order.orderId()) + "/invoices/rewrite").session(tenant.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_version_number\":" + currentVersion + "}");
    }

    /** D.8 exactly as the Invoice tab sends it: the expected version form field and one PNG signature part. */
    private RequestBuilder inStoreSigning(LinkedOrder order, int expectedVersion) {
        return multipart(orderUrl(order.orderId()) + "/invoices/current/accept")
                .file(new MockMultipartFile("signature", "signature.png", "image/png", SIGNATURE_PNG))
                .param("expected_version_number", Integer.toString(expectedVersion))
                .session(tenant.session());
    }

    private RequestBuilder sendLinkRequest(long orderId) {
        return post(orderUrl(orderId) + "/invoices/current/send-link").session(tenant.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private RequestBuilder recordPayment(LinkedOrder order, String amount) {
        return post(orderUrl(order.orderId()) + "/payments").session(tenant.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\":\"EFTPOS\",\"amount\":" + amount + ",\"payment_reference\":null}");
    }

    private String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    private MockMvc newMockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ================================================================
    // Response assertions
    // ================================================================

    /**
     * 200 {@code Invoice loaded.} with exactly the 27 keys and the ACTIVE document of {@code version} (an
     * invoice row): its stored words, dates and money verbatim ({@code gst_amount} = inc - ex), the order's
     * number and flooring type, the live business presentation and {@code token}'s {@code expires_at}.
     */
    private static void assertActiveView(MvcResult result, LinkedOrder order, Map<String, Object> version,
                                         Map<String, Object> token, String label) throws Exception {
        assertStatus(result, 200, label);
        assertJsonContentType(result, label);
        JsonNode root = readJson(result);
        Assertions.assertEquals(Set.of("data", "message"), fieldNames(root), label + ": the top-level keys");
        Assertions.assertEquals(LOADED_MESSAGE, root.path("message").asText(), label + ": the message");
        JsonNode data = root.path("data");
        Assertions.assertEquals(new TreeSet<>(PUBLIC_VIEW_KEYS), fieldNames(data), label + ": exactly the 27 keys");

        assertText(data, "state", TOKEN_ACTIVE, label);
        assertText(data, "business_name", BUSINESS_NAME, label);
        assertText(data, "business_logo_url", null, label);
        assertText(data, "accent_color", ACCENT_COLOUR, label);
        assertText(data, "business_abn", ABN, label);
        assertText(data, "payment_account_name", ACCOUNT_NAME, label);
        assertText(data, "payment_bank_name", BANK_NAME, label);
        assertText(data, "payment_bsb", BSB, label);
        assertText(data, "payment_account_number", ACCOUNT_NUMBER, label);
        assertText(data, "payment_stripe_link_url", STRIPE_LINK, label);
        assertText(data, "order_number", order.orderNumber(), label);
        assertText(data, "flooring_type", "SOFT", label);
        Assertions.assertTrue(data.path("version_number").isInt(), label + ": version_number is an integer");
        Assertions.assertEquals(toInt(version.get("version_number")), data.path("version_number").intValue(),
                label + ": version_number");
        assertText(data, "invoice_date", dateText(version.get("invoice_date")), label);
        assertText(data, "due_date", dateText(version.get("due_date")), label);
        assertText(data, "customer_name", (String) version.get("customer_name_snapshot"), label);
        assertText(data, "customer_address_line1", (String) version.get("customer_address_line1_snapshot"), label);
        assertText(data, "customer_address_line2", (String) version.get("customer_address_line2_snapshot"), label);
        assertText(data, "details_of_sale", (String) version.get("details_of_sale_snapshot"), label);
        BigDecimal ex = (BigDecimal) version.get("sale_price_ex_gst");
        BigDecimal inc = (BigDecimal) version.get("sale_price_inc_gst");
        assertMoney(data, "sale_price_ex_gst", ex, label);
        assertMoney(data, "gst_amount", inc.subtract(ex), label);
        assertMoney(data, "sale_price_inc_gst", inc, label);
        assertMoney(data, "total_paid", (BigDecimal) version.get("total_paid"), label);
        assertMoney(data, "balance_due", (BigDecimal) version.get("balance_due"), label);
        assertText(data, "terms_html", (String) version.get("terms_snapshot"), label);
        assertText(data, "expires_at", WHOLE_SECONDS.format(toLocalDateTime(token.get("expires_at"))), label);
        Assertions.assertTrue(data.get("message").isNull(), label + ": message is null when ACTIVE");
    }

    /** 200 {@code Invoice loaded.} with exactly the minimal dead payload: no document content at all. */
    private static void assertDeadView(MvcResult result, String state, String label) throws Exception {
        assertStatus(result, 200, label);
        assertJsonContentType(result, label);
        Assertions.assertEquals(deadPayload(state), readJson(result), label + ": exactly the minimal " + state + " payload");
    }

    /** 410 with exactly {@code {"error":{"code","message"}}} of the dead state; no PDF bytes, no disposition. */
    private static void assertGone(MvcResult result, String state, String label) throws Exception {
        assertExactError(result, 410, DEAD_CODES.get(state), DEAD_MESSAGES.get(state), label);
        Assertions.assertFalse(startsWithPdfMagic(result.getResponse().getContentAsByteArray()), label + ": no PDF bytes");
        Assertions.assertNull(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION),
                label + ": no Content-Disposition");
    }

    /** 409 INVOICE_VERSION_STALE exactly; no PDF bytes (another version is never served). */
    private static void assertStalePdf(MvcResult result, String label) throws Exception {
        assertExactError(result, 409, STALE_CODE, STALE_MESSAGE, label);
        Assertions.assertFalse(startsWithPdfMagic(result.getResponse().getContentAsByteArray()), label + ": no PDF bytes");
        Assertions.assertNull(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION),
                label + ": no Content-Disposition");
    }

    /**
     * 200 {@code application/pdf}, {@code Content-Disposition} exactly
     * {@code inline; filename="invoice-{order_number}-v{N}.pdf"} (the stored name), the stored length, and the
     * body byte-identical to version N's stored file on disk.
     */
    private void assertServesStoredPdf(MvcResult result, LinkedOrder order, int versionNumber, String label)
            throws Exception {
        assertStatus(result, 200, label);
        StoredPdf stored = storedPdf(order.orderId(), versionNumber);
        String expectedName = invoicePdfName(order.orderNumber(), versionNumber);
        Assertions.assertEquals(expectedName, stored.fileName(), label + ": the stored file name");
        Assertions.assertEquals("application/pdf", stored.mimeType(), label + ": the stored mime type");
        Assertions.assertEquals("application/pdf", result.getResponse().getContentType(), label + ": Content-Type");
        Assertions.assertEquals(List.of("inline; filename=\"" + expectedName + "\""),
                result.getResponse().getHeaders(HttpHeaders.CONTENT_DISPOSITION), label + ": Content-Disposition, exactly");
        Assertions.assertEquals(String.valueOf(stored.fileSize()), result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH),
                label + ": Content-Length = the stored size");
        byte[] served = result.getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(stored.bytes(), served, label + ": version " + versionNumber + "'s stored bytes, verbatim");
        Assertions.assertTrue(startsWithPdfMagic(served), label + ": a PDF");
    }

    /** The served v1 bytes are not v2's stored file: the payment version really has a PDF of its own. */
    private void assertDistinctStoredPdfs(LinkedOrder order, MvcResult servedV1, String label) throws IOException {
        StoredPdf v1 = storedPdf(order.orderId(), 1);
        StoredPdf v2 = storedPdf(order.orderId(), 2);
        Assertions.assertNotEquals(v1.storagePath(), v2.storagePath(), label + ": v2 has its own stored file");
        Assertions.assertFalse(Arrays.equals(v1.bytes(), v2.bytes()), label + ": v2's PDF differs from v1's");
        Assertions.assertFalse(Arrays.equals(v2.bytes(), servedV1.getResponse().getContentAsByteArray()),
                label + ": never v2's file under version 1");
    }

    private static void assertExactError(MvcResult result, int status, String code, String message, String label)
            throws Exception {
        assertStatus(result, status, label);
        assertJsonContentType(result, label);
        ObjectNode error = JSON.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        ObjectNode expected = JSON.createObjectNode();
        expected.set("error", error);
        Assertions.assertEquals(expected, readJson(result), label + ": the exact error body");
    }

    private static JsonNode deadPayload(String state) {
        ObjectNode data = JSON.createObjectNode();
        for (String key : PUBLIC_VIEW_KEYS) {
            data.putNull(key);
        }
        data.put("state", state);
        data.put("business_name", BUSINESS_NAME);
        data.put("message", DEAD_MESSAGES.get(state));
        ObjectNode root = JSON.createObjectNode();
        root.set("data", data);
        root.put("message", LOADED_MESSAGE);
        return root;
    }

    private static void assertText(JsonNode data, String key, String expected, String label) {
        JsonNode node = data.get(key);
        Assertions.assertNotNull(node, label + ": key " + key + " is present");
        if (expected == null) {
            Assertions.assertTrue(node.isNull(), label + ": " + key + " is null, but was " + node);
        } else {
            Assertions.assertTrue(node.isTextual(), label + ": " + key + " is a string, but was " + node);
            Assertions.assertEquals(expected, node.textValue(), label + ": " + key);
        }
    }

    private static void assertMoney(JsonNode data, String key, BigDecimal expected, String label) {
        JsonNode node = data.get(key);
        Assertions.assertNotNull(node, label + ": key " + key + " is present");
        Assertions.assertTrue(node.isNumber(), label + ": " + key + " is a number, but was " + node);
        Assertions.assertEquals(0, expected.compareTo(node.decimalValue()),
                label + ": " + key + " expected " + expected + " but was " + node.decimalValue());
    }

    /** A version written during this test is dated the day it was written (midnight-safe: start day to now). */
    private void assertDatedToday(Object invoiceDate, String label) {
        Assertions.assertNotNull(invoiceDate, label + ": an invoice date");
        LocalDate date = ((Date) invoiceDate).toLocalDate();
        Assertions.assertFalse(date.isBefore(testStartedOn) || date.isAfter(LocalDate.now()),
                label + ": dated the day it was written, but was " + date);
        Assertions.assertNotEquals(V1_INVOICE_DATE, date, label + ": not v1's committed date");
    }

    private static void assertMoneyEquals(BigDecimal expected, Object actual, String label) {
        Assertions.assertNotNull(actual, label + ": expected " + expected + " but was null");
        Assertions.assertEquals(0, expected.compareTo(new BigDecimal(actual.toString())),
                label + ": expected " + expected + " but was " + actual);
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + ": unexpected status; body: " + body(result));
    }

    private static void assertJsonContentType(MvcResult result, String label) {
        String contentType = result.getResponse().getContentType();
        Assertions.assertNotNull(contentType, label + ": a JSON body is expected");
        Assertions.assertTrue(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON),
                label + ": a JSON body is expected, was " + contentType);
    }

    // ================================================================
    // Committed-effect assertions (what each writer committed)
    // ================================================================

    /** D.2: v2 appended unsigned; the presented link REWRITTEN with dead_at; no live link left. */
    private static void assertRewriteCommitted(LinkedOrder order, CommittedState committed, String label) {
        Assertions.assertEquals(List.of(1, 2), committed.versions(), label + ": the Rewrite appended exactly v2");
        Assertions.assertNull(committed.invoice(2).get("accepted_at"), label + ": the rewritten v2 is unsigned");
        Map<String, Object> token = committed.token(order.tokenId());
        Assertions.assertEquals(TOKEN_REWRITTEN, token.get("status"), label + ": the Rewrite killed the link REWRITTEN");
        Assertions.assertNotNull(token.get("dead_at"), label + ": the REWRITTEN link has its dead_at");
        Assertions.assertTrue(committed.activeTokens().isEmpty(), label + ": no live link after the Rewrite");
    }

    /** D.8: v2 appended signed with the frozen name; the presented link SIGNED with dead_at = accepted_at. */
    private static void assertInStoreSigningCommitted(LinkedOrder order, CommittedState committed, String label) {
        Assertions.assertEquals(List.of(1, 2), committed.versions(), label + ": D.8 appended exactly v2");
        Map<String, Object> v2 = committed.invoice(2);
        Assertions.assertNotNull(v2.get("accepted_at"), label + ": v2 is signed");
        Assertions.assertEquals(CUSTOMER_NAME, v2.get("accepted_customer_name"), label + ": v2's accepted name");
        Assertions.assertNull(committed.invoice(1).get("accepted_at"), label + ": v1 stays unsigned");
        Map<String, Object> token = committed.token(order.tokenId());
        Assertions.assertEquals(TOKEN_SIGNED, token.get("status"), label + ": D.8 killed the link SIGNED");
        Assertions.assertEquals(v2.get("accepted_at"), token.get("dead_at"),
                label + ": the link died at the signing time");
        Assertions.assertTrue(committed.activeTokens().isEmpty(), label + ": no live link after the signing");
    }

    /**
     * D.7: v2 appended (dated today, 50.00 paid, the balance reduced, the sale carried), unsigned; the link
     * survives untouched. Returns v2's committed row.
     */
    private Map<String, Object> assertPaymentCommitted(LinkedOrder order, Map<String, Object> v1,
                                                       CommittedState committed, String label) {
        Assertions.assertEquals(List.of(1, 2), committed.versions(), label + ": the payment appended exactly v2");
        Map<String, Object> v2 = committed.invoice(2);
        assertMoneyEquals(new BigDecimal(PAYMENT_AMOUNT), v2.get("total_paid"), label + ": v2 total_paid");
        assertMoneyEquals(((BigDecimal) v1.get("balance_due")).subtract(new BigDecimal(PAYMENT_AMOUNT)),
                v2.get("balance_due"), label + ": v2 balance_due");
        assertMoneyEquals((BigDecimal) v1.get("sale_price_inc_gst"), v2.get("sale_price_inc_gst"),
                label + ": v2 carries the sale");
        Assertions.assertNotEquals(0, ((BigDecimal) v1.get("total_paid")).compareTo((BigDecimal) v2.get("total_paid")),
                label + ": v1 and v2 differ in total_paid (the discriminator)");
        assertDatedToday(v2.get("invoice_date"), label + ": v2 (a payment dates its version today)");
        Assertions.assertNotEquals(v1.get("invoice_date"), v2.get("invoice_date"),
                label + ": v1 and v2 differ in invoice_date (the discriminator)");
        Assertions.assertNull(v2.get("accepted_at"), label + ": v2 is unsigned");
        Map<String, Object> token = committed.token(order.tokenId());
        Assertions.assertEquals(TOKEN_ACTIVE, token.get("status"), label + ": a payment never kills the link");
        Assertions.assertNull(token.get("dead_at"), label + ": the link is alive");
        return v2;
    }

    /**
     * A resend: v1 unchanged; the presented link REPLACED (dead_at = the new link's sent_at) and one new
     * ACTIVE link, whose plaintext is taken from the resend's (only) recorded link email. Returns it.
     */
    private String assertResendCommitted(LinkedOrder order, CommittedState committed, String label) {
        Assertions.assertEquals(List.of(1), committed.versions(), label + ": a send creates no invoice version");
        List<InvoiceLinkEmailRequest> sent = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), label + ": the resend delivered exactly one link email");
        String newToken = assertLinkEmail(sent.get(0), order.orderId(), order.orderNumber(), 1, label + ": the resend");
        Assertions.assertNotEquals(order.token(), newToken, label + ": the resend minted a new token");
        Map<String, Object> oldRow = committed.token(order.tokenId());
        Map<String, Object> newRow = committed.tokenByHash(sha256Hex(newToken));
        Assertions.assertEquals(TOKEN_REPLACED, oldRow.get("status"), label + ": the presented link is REPLACED");
        Assertions.assertEquals(newRow.get("sent_at"), oldRow.get("dead_at"),
                label + ": the presented link died at the new link's send time");
        Assertions.assertEquals(TOKEN_ACTIVE, newRow.get("status"), label + ": the new link is ACTIVE");
        Assertions.assertEquals(List.of(newRow), committed.activeTokens(), label + ": exactly one live link");
        return newToken;
    }

    /** The D.8 writer's own signed-PDF email: the appended version's stored PDF to the saved email. */
    private void assertSignedPdfEmail(LinkedOrder order, int versionNumber, String label) throws IOException {
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), label + ": exactly the D.8 writer's signed-PDF email");
        InvoiceEmailRequest email = sent.get(0);
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), label + ": the signed-PDF email recipient");
        Assertions.assertEquals(order.orderId(), email.orderId(), label + ": the signed-PDF email order");
        Assertions.assertEquals(versionNumber, email.invoiceVersionNumber(), label + ": the signed-PDF email version");
        Assertions.assertEquals(invoicePdfName(order.orderNumber(), versionNumber), email.pdfFileName(),
                label + ": the signed-PDF email attachment name");
        Assertions.assertArrayEquals(storedPdf(order.orderId(), versionNumber).bytes(), email.pdfBytes(),
                label + ": the signed-PDF email carries the appended version's stored PDF");
    }

    /**
     * Exactly the given numbers of signed-PDF and link emails (the writer's own); no failed attempt, no
     * store notification, no quote email or quote notification. A public read never sends anything.
     */
    private void assertMessages(int signedPdfEmails, int linkEmails, String label) {
        Assertions.assertEquals(signedPdfEmails, invoiceEmailSender.sentEmails().size(), label + ": signed-PDF emails");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), label + ": no failed signed-PDF email");
        Assertions.assertEquals(linkEmails, invoiceLinkEmailSender.sentEmails().size(), label + ": link emails");
        Assertions.assertTrue(invoiceLinkEmailSender.failedEmails().isEmpty(), label + ": no failed link email");
        Assertions.assertTrue(invoiceNotificationSender.sentNotifications().isEmpty()
                && invoiceNotificationSender.failedNotifications().isEmpty(), label + ": no store notification");
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty() && quoteEmailSender.failedEmails().isEmpty(),
                label + ": no quote email");
        Assertions.assertTrue(quoteNotificationSender.sentNotifications().isEmpty()
                && quoteNotificationSender.failedNotifications().isEmpty(), label + ": no quote notification");
    }

    private static void assertSameCommittedState(CommittedState expected, CommittedState actual, String label) {
        Assertions.assertEquals(expected.order(), actual.order(), label + ": the sales_order row");
        Assertions.assertEquals(expected.invoices(), actual.invoices(), label + ": the invoice rows");
        Assertions.assertEquals(expected.tokens(), actual.tokens(), label + ": the invoice_token rows");
        Assertions.assertEquals(expected.storedFiles(), actual.storedFiles(), label + ": the order's stored_file rows");
        Assertions.assertEquals(expected.filesOnDisk(), actual.filesOnDisk(), label + ": the order's files on disk");
    }

    /** The link email: recipient, subject, the exact body with the /i/{token} link exactly once, order, version. */
    private static String assertLinkEmail(InvoiceLinkEmailRequest email, long orderId, String orderNumber,
                                          int versionNumber, String label) {
        Matcher matcher = INVOICE_LINK_PATTERN.matcher(email.bodyText());
        Assertions.assertTrue(matcher.find(), label + ": the link email carries an /i/{token} link");
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), label + ": the link appears exactly once");
        Assertions.assertEquals(1, countOccurrences(email.bodyText(), "/i/"), label + ": one /i/ link in the body");
        Assertions.assertEquals(CUSTOMER_EMAIL, email.recipientEmail(), label + ": the link email recipient");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), label + ": the link email subject");
        Assertions.assertEquals("Hi,\n\nYour invoice " + orderNumber + " from " + BUSINESS_NAME
                        + " is ready to review and sign. You can view and sign your invoice online here: "
                        + PUBLIC_BASE_URL + "/i/" + token + "\n\nThank you.", email.bodyText(),
                label + ": the link email body");
        Assertions.assertEquals(orderId, email.orderId(), label + ": the link email order id");
        Assertions.assertEquals(versionNumber, email.invoiceVersionNumber(), label + ": the link email version");
        return token;
    }

    // ================================================================
    // Seeding (committed for real: auto-commit JDBC + real API calls)
    // ================================================================

    /**
     * The class's own COMMITTED tenant: business (unique slug, ABN, bank details, accent, HTTPS Stripe link,
     * soft terms, no logo), one store with an email, one salesperson and that salesperson's store access.
     */
    private Tenant seedTenant() {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number, "
                        + " accent_colour, stripe_payment_link_url, terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, ?, ?, ?, ?, ?, ?, ?, ?, NULL) RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, ABN, BANK_NAME, BSB, ACCOUNT_NAME, ACCOUNT_NUMBER, ACCENT_COLOUR,
                STRIPE_LINK, SOFT_TERMS);
        Assertions.assertNotNull(businessId, "setup: business insert must return an id");
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Ironbark Snapshot Showroom', ?, '0290005555', ?, '5 Snapshot Parade', "
                        + " 'Chatswood', 'NSW', '2067') RETURNING store_id",
                Integer.class, businessId, STORE_CODE, STORE_EMAIL);
        Assertions.assertNotNull(storeId, "setup: store insert must return an id");
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Piper', 'Snapshot', ?, 'piper.snapshot@ironbark-snapshot.example.com', "
                        + " 'not-a-login-hash-pr4-snapshot') RETURNING user_id",
                Long.class, businessId, SALESPERSON_CODE);
        Assertions.assertNotNull(userId, "setup: app_user insert must return an id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        orderSequence = 0;
        return new Tenant(businessId, storeId, userId);
    }

    /**
     * A committed order with an ACTIVE link: invoice-ready header and children; the unsigned v1 through the
     * REAL D.1 endpoint (then committed-dated {@link #V1_INVOICE_DATE}); the link through the REAL send-link
     * endpoint (the plaintext from the recorded email); the recorders reset so each race starts clean.
     */
    private LinkedOrder seedLinkedOrder() throws Exception {
        SeededOrder order = insertInvoiceReadyOrder();
        long orderId = order.orderId();

        MvcResult created = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(tenant.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(created, 201, "setup: D.1 invoice create");
        Assertions.assertEquals(INVOICE_CREATED_MESSAGE, readJson(created).path("message").asText(), "setup: D.1 message");
        Assertions.assertEquals(List.of(1), invoiceVersions(orderId), "setup: D.1 committed exactly v1");
        Map<String, Object> v1 = invoiceRow(orderId, 1);
        Assertions.assertNull(v1.get("accepted_at"), "setup: v1 is unsigned");
        Assertions.assertEquals(CUSTOMER_NAME, v1.get("customer_name_snapshot"), "setup: v1's frozen name");
        Assertions.assertEquals(DETAILS_OF_SALE, v1.get("details_of_sale_snapshot"), "setup: v1's details");
        Assertions.assertNotNull(v1.get("terms_snapshot"), "setup: v1 froze the business's soft terms");
        assertMoneyEquals(new BigDecimal("110.00"), v1.get("sale_price_inc_gst"), "setup: v1 inc");
        assertMoneyEquals(new BigDecimal("0.00"), v1.get("total_paid"), "setup: v1 total_paid");
        Assertions.assertEquals(Date.valueOf(DUE_DATE), v1.get("due_date"), "setup: v1 due date");
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice SET invoice_date = ? WHERE order_id = ? AND version_number = 1",
                        Date.valueOf(V1_INVOICE_DATE), orderId),
                "setup: date v1 on an earlier day (a version written today is then distinguishable)");

        String token = sendLink(orderId, order.orderNumber(), 1, "setup: send-link");
        Map<String, Object> tokenRow = tokenRowByHash(token);
        resetRecorders();
        return new LinkedOrder(orderId, order.orderNumber(), token, toLong(tokenRow.get("invoice_token_id")));
    }

    /**
     * POST send-link {} on the current invoice: 201 with the link times; the plaintext token from the recorded
     * link email (the exact body, the link exactly once); the token row ACTIVE, sent by the session user,
     * expiring 7 days after its send, delivered (emailed_at), alive.
     */
    private String sendLink(long orderId, String orderNumber, int currentVersion, String label) throws Exception {
        int sentBefore = invoiceLinkEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(sendLinkRequest(orderId)).andReturn();
        assertStatus(result, 201, label);
        JsonNode body = readJson(result);
        Assertions.assertEquals(LINK_SENT_MESSAGE, body.path("message").asText(), label + ": message");
        List<InvoiceLinkEmailRequest> sent = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), label + ": exactly one link email recorded");
        String token = assertLinkEmail(sent.get(sent.size() - 1), orderId, orderNumber, currentVersion, label);
        Map<String, Object> row = tokenRowByHash(token);
        Assertions.assertEquals(TOKEN_ACTIVE, row.get("status"), label + ": the new link is ACTIVE");
        Assertions.assertEquals(orderId, toLong(row.get("order_id")), label + ": the link's order");
        Assertions.assertEquals(tenant.userId(), toLong(row.get("sent_by_user_id")), label + ": sent by the session user");
        LocalDateTime sentAt = toLocalDateTime(row.get("sent_at"));
        LocalDateTime expiresAt = toLocalDateTime(row.get("expires_at"));
        Assertions.assertEquals(sentAt.plusDays(7), expiresAt, label + ": expires 7 days after its send");
        Assertions.assertNotNull(row.get("emailed_at"), label + ": the delivered link is stamped");
        Assertions.assertNull(row.get("dead_at"), label + ": the new link is alive");
        Assertions.assertEquals(WHOLE_SECONDS.format(sentAt), body.path("data").path("invoice_link").path("sent_at").asText(),
                label + ": the 201 reports the stored sent_at");
        Assertions.assertEquals(WHOLE_SECONDS.format(expiresAt),
                body.path("data").path("invoice_link").path("expires_at").asText(),
                label + ": the 201 reports the stored expires_at");
        return token;
    }

    /**
     * Header (details of sale, proposed lay date 2026-12-01 CONFIRMED), customer {@value #CUSTOMER_NAME} with a
     * valid email, INSTALLATION + BILLING addresses and one tagged 100.00 ex / 40.00 cost charge line.
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
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, CUSTOMER_EMAIL, CUSTOMER_MOBILE);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '7', 'Install Lane', 'Ryde', 'NSW', '2112')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Snapshot Street', 'Burwood', 'NSW', '2134')",
                orderId);
        String chargeCode = CHARGE_CODE_PREFIX + sequence;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Snapshot supply and lay', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, tenant.storeId(), chargeCode);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Snapshot supply and lay', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, chargeCode);
        return new SeededOrder(orderId, orderNumber);
    }

    /**
     * Revise the LIVE sale after v1 (committed): the customer's first name, the details of sale and the charge
     * line doubled (200.00 ex / 220.00 inc). v1's frozen words never change; a Rewrite freezes these into v2.
     */
    private void reviseLiveSale(LinkedOrder order) {
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE order_customer SET first_name = ? WHERE order_id = ?",
                REVISED_FIRST_NAME, order.orderId()), "setup: revise the saved first name");
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE sales_order SET details_of_sale = ? WHERE order_id = ?",
                REVISED_DETAILS_OF_SALE, order.orderId()), "setup: revise the details of sale");
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_charge_line SET quantity = 2, line_total = 200.00, line_cost = 80.00 WHERE order_id = ?",
                order.orderId()), "setup: double the charge line");
    }

    private void resetRecorders() {
        invoiceEmailSender.reset();
        invoiceLinkEmailSender.reset();
        invoiceNotificationSender.reset();
        quoteEmailSender.reset();
        quoteNotificationSender.reset();
    }

    // ================================================================
    // Committed-state probes (auto-commit JDBC - committed data only)
    // ================================================================

    /** The order's rows a public read could change (it must change none) and its files on disk. */
    private CommittedState committedState(LinkedOrder order) {
        long orderId = order.orderId();
        return new CommittedState(
                row("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId),
                rows("SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                        orderStoragePrefix(orderId) + "%"),
                filesOnDisk(orderId));
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return row("SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private List<Integer> invoiceVersions(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT version_number FROM invoice WHERE order_id = ? ORDER BY version_number", Integer.class, orderId);
    }

    private Map<String, Object> tokenRow(long invoiceTokenId) {
        return row("SELECT * FROM invoice_token WHERE invoice_token_id = ?", invoiceTokenId);
    }

    /** The invoice_token row of a plaintext token: looked up by its SHA-256 hex hash only. */
    private Map<String, Object> tokenRowByHash(String plaintextToken) {
        return row("SELECT * FROM invoice_token WHERE token_hash = ?", sha256Hex(plaintextToken));
    }

    /** Version N's stored PDF: its stored_file row and the bytes of its file on disk. */
    private StoredPdf storedPdf(long orderId, int versionNumber) throws IOException {
        Map<String, Object> file = row("SELECT sf.file_name, sf.storage_path, sf.mime_type, sf.file_size "
                + "FROM invoice i JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id "
                + "WHERE i.order_id = ? AND i.version_number = ?", orderId, versionNumber);
        String storagePath = (String) file.get("storage_path");
        return new StoredPdf((String) file.get("file_name"), storagePath, (String) file.get("mime_type"),
                toLong(file.get("file_size")), Files.readAllBytes(physicalPath(storagePath)));
    }

    private Map<String, Object> row(String sql, Object... args) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap(sql, args));
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> found : jdbcTemplate.queryForList(sql, args)) {
            copies.add(new LinkedHashMap<>(found));
        }
        return copies;
    }

    // ================================================================
    // Small value helpers
    // ================================================================

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

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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

    private static int countOccurrences(String haystack, String needle) {
        int occurrences = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            occurrences++;
            from += needle.length();
        }
        return occurrences;
    }

    private static long toLong(Object value) {
        Assertions.assertNotNull(value, "expected a non-null number");
        return ((Number) value).longValue();
    }

    private static int toInt(Object value) {
        Assertions.assertNotNull(value, "expected a non-null number");
        return ((Number) value).intValue();
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        Assertions.assertNotNull(value, "expected a non-null timestamp");
        return ((Timestamp) value).toLocalDateTime();
    }

    private static String dateText(Object value) {
        return value == null ? null : ((Date) value).toLocalDate().toString();
    }

    private static String invoicePdfName(String orderNumber, int versionNumber) {
        return "invoice-" + orderNumber + "-v" + versionNumber + ".pdf";
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

    // ================================================================
    // Cleanup (runs in @BeforeEach AND @AfterEach; every step runs even if an earlier one fails)
    // ================================================================

    private void cleanUpEverything() {
        runAll(
                () -> Mockito.reset(invoiceTokenRepository, invoiceRepository),
                this::resetRecorders,
                this::sweepTenants);
        assertSweptClean();
    }

    /** The sweep really removed everything this class commits: the tenant (by slug) and its storage dir. */
    private void assertSweptClean() {
        Long tenants = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM business WHERE slug = ?", Long.class, SLUG);
        Assertions.assertEquals(0L, tenants, "no tenant of this class may survive the sweep");
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
                // invoice_token first: its FKs reference sales_order and app_user (sent_by_user_id).
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

    /** A committed order whose unsigned v1 has an ACTIVE link: the plaintext token and its row id. */
    private record LinkedOrder(long orderId, String orderNumber, String token, long tokenId) {
    }

    private record StoredPdf(String fileName, String storagePath, String mimeType, long fileSize, byte[] bytes) {
    }

    /** The committed rows of one order a public read must never change, plus its files on disk. */
    private record CommittedState(Map<String, Object> order,
                                  List<Map<String, Object>> invoices,
                                  List<Map<String, Object>> tokens,
                                  List<Map<String, Object>> storedFiles,
                                  Set<String> filesOnDisk) {

        List<Integer> versions() {
            return invoices.stream().map(invoice -> toInt(invoice.get("version_number"))).toList();
        }

        int maxVersion() {
            return versions().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        Map<String, Object> invoice(int versionNumber) {
            List<Map<String, Object>> found = invoices.stream()
                    .filter(invoice -> toInt(invoice.get("version_number")) == versionNumber).toList();
            Assertions.assertEquals(1, found.size(), "exactly one committed invoice v" + versionNumber);
            return found.get(0);
        }

        Map<String, Object> token(long invoiceTokenId) {
            List<Map<String, Object>> found = tokens.stream()
                    .filter(token -> toLong(token.get("invoice_token_id")) == invoiceTokenId).toList();
            Assertions.assertEquals(1, found.size(), "exactly one committed invoice_token " + invoiceTokenId);
            return found.get(0);
        }

        Map<String, Object> tokenByHash(String tokenHash) {
            List<Map<String, Object>> found = tokens.stream()
                    .filter(token -> tokenHash.equals(token.get("token_hash"))).toList();
            Assertions.assertEquals(1, found.size(), "exactly one committed invoice_token with the hash");
            return found.get(0);
        }

        List<Map<String, Object>> activeTokens() {
            return tokens.stream().filter(token -> TOKEN_ACTIVE.equals(token.get("status"))).toList();
        }
    }
}
