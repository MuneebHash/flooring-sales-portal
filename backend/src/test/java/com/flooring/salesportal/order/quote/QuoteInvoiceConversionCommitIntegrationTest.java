package com.flooring.salesportal.order.quote;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
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
import com.flooring.salesportal.order.InvoicePdfGenerator;
import com.flooring.salesportal.order.InvoiceRepository;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
import com.flooring.salesportal.order.OrderInvoiceService;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16F PR2 - FAILURE ATOMICITY of Path A ({@code POST /api/v1/{slug}/orders/{orderId}/quote/create-invoice},
 * "create the invoice from the accepted quote") and of the payment carry-forward of a quote-sourced invoice,
 * proven OUTSIDE any test transaction; since Phase 16G PR 3 also the Path A POST-COMMIT signed-PDF email
 * (16G contract §5.5 step 9, §6.1, §6.2 and §11), proven at the real commit boundary.
 *
 * <p><b>Why a separate, non-transactional class.</b> Inside a {@code @Transactional} test the Path A
 * transaction (since 16G PR 3 one programmatic {@code TransactionTemplate} transaction) and the payment's
 * {@code TransactionTemplate} JOIN the test transaction, so nothing ever really commits: "a failure commits
 * nothing", "a commit-time failure deletes the written PDF through the afterCompletion hook", "the inherited
 * signature and every earlier PDF survive" and "the email is sent only after the conversion committed"
 * cannot be observed there. This class has NO {@code @Transactional} anywhere: every seed statement
 * auto-commits, every MockMvc call commits for real, and every assertion reads COMMITTED state through pooled
 * auto-commit connections plus the files on disk.
 *
 * <p><b>16G PR 3.</b> Every Path A request carries the mandatory body {@code {"quote_version_id": N}}, N = the
 * id of the fixture's latest ACCEPTED quote version. A failure BEFORE the commit (render, storage, insert,
 * mirror reset, commit, an inherited-signature defect, the payment's cross-order source) emails nothing: the
 * invoice email sender spy is never called and nothing is recorded. A committed conversion is emailed after
 * its commit: every successful retry sends exactly one email of the version it appended (its stored PDF, byte
 * for byte, to the trimmed saved customer email) and stamps that version and the {@code sales_order} mirror
 * with ONE timestamp. Section 8 proves, on a self-seeded committed tenant, that every post-commit outcome -
 * delivered, a delivery failure, another sender runtime failure, an email-success stamp failing before its
 * first write, after its invoice write and at its commit, and the skip of an app-invalid saved email - leaves
 * the conversion COMMITTED with a 201 whose message reports the delivery outcome, whose markers report what
 * was recorded, and whose WARN line carries the order id and version only (no throwable, no recipient). It
 * also proves that the recipient AND the skip decision come from the saved email the COMMITTING transaction
 * read (an address committed right after the commit, before the email, is never used), and that the 201
 * always describes the version that request appended, with its own stamp, even when another version became
 * current between the conversion's commit and its stamp (the sales_order mirror then follows that current
 * version).
 *
 * <p><b>Fault injection</b> (no production hooks):
 * <ul>
 *   <li>mid-transaction and post-commit - {@link MockitoSpyBean} spies (real behaviour unless stubbed) on
 *       {@link InvoicePdfGenerator} (the render), {@link FileStorageService} (the invoice PDF write),
 *       {@link InvoiceRepository} (the invoice insert, the sales_order {@code last_emailed_at} mirror reset,
 *       which is the LAST conversion write, and the two writes of the email-success stamp) and
 *       {@link RecordingInvoiceEmailSender} (the post-commit send). Stubs are installed only after the fixture
 *       is committed, so the seeding flows (D.1, payment, quote issue and accept) always run for real;</li>
 *   <li>at COMMIT - {@code DEFERRABLE INITIALLY DEFERRED} constraint triggers scoped to the test order:
 *       {@code AFTER INSERT ON invoice} fails the conversion's commit, {@code AFTER UPDATE ON invoice} when
 *       {@code last_emailed_at} becomes non-null fails the email-success stamp's commit. Each is created in
 *       auto-commit right before the request and dropped in {@code finally} and by the sweep;</li>
 *   <li>at the insert boundary - a committed cross-order {@code invoice.source_quote_version_id} (the
 *       single-column V19 FK allows it), which the payment carry-forward must refuse through the
 *       {@link InvoiceRepository#insertInvoice} source-ownership check;</li>
 *   <li>before the render - a committed defect in the accepted quote's inherited signature: its file moved
 *       off disk (into a holding dir INSIDE the order's storage dir, so the sweep removes it too), or a NULL
 *       {@code accepted_customer_name} / {@code accepted_signature_file_id} on the ACCEPTED
 *       {@code quote_version} (no CHECK forbids either); each defect is undone before the retry;</li>
 *   <li>at the saved email - a committed {@code order_customer.email} the database accepts but the app rule
 *       refuses ({@code jordan@localhost}); the constraint is never dropped in this committed class;</li>
 *   <li>right after the conversion's COMMIT - an {@code afterCommit} synchronization, registered on the
 *       conversion's last in-transaction write (the mirror reset to null), commits a moved saved customer
 *       email on a RAW pooled auto-commit connection before the post-commit email runs;</li>
 *   <li>at the post-commit send - before the real send, a newer invoice version (a copy of the appended row
 *       with its own stored_file row and file, unemailed) and the mirror reset are committed on a RAW pooled
 *       auto-commit connection, so another version is current when the email-success stamp runs.</li>
 * </ul>
 * The four spies are EXACTLY those of {@code order/InvoiceAcceptanceCommitIntegrationTest} (same types, same
 * field names) under the same {@code @SpringBootTest} property, so both classes share ONE cached Spring
 * context: never add, rename or re-annotate a spy here alone. Files go to the shared test storage dir
 * {@code target/test-storage/quote-acceptance}; physical file = base dir + {@code storage_path}.
 *
 * <p><b>Seed / cleanup.</b> Sections 1-7 keep their fixtures: V4 business 1 / store 1 / user 1
 * ({@code aussie-floors-group}) as the session identity, every order self-seeded and tagged
 * {@code NTXQIC.ZZ9.nnnnn}, its sequence allocated the {@code OrderCreateRepository} way (per-business advisory
 * lock + MAX + 1), its charge tagged {@code NTXQIC{orderId}}. Section 8 self-seeds its OWN committed tenant
 * (business {@value #EMAIL_TENANT_SLUG}, one store, one salesperson and the {@code user_store_access} row) and
 * its orders, with no V4 identity at all. Every row of every tagged order and of every tenant order (FK-safe
 * order: tokens, invoices before quote versions, drafts, payments, the other children, the order), their
 * stored files (rows + disk + the order dirs), the tagged charges, the tenant's own rows (charges, products,
 * access, user, store, quick descriptions, business) and its storage dir, and any leftover test trigger or
 * trigger function are swept in BOTH {@code @BeforeEach} and {@code @AfterEach}. The singleton recording
 * senders and every spy are reset in both as well. Single-threaded.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class QuoteInvoiceConversionCommitIntegrationTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted before any file is deleted). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    // Committed seed rows are tagged so the sweep finds this class's orders and charges - and only them.
    private static final String ORDER_NUMBER_PREFIX = "NTXQIC.ZZ9.";
    private static final String ORDER_SWEEP_PATTERN = "NTXQIC.%";
    private static final String CHARGE_CODE_PREFIX = "NTXQIC";

    /** The V4 session identity sections 1-7 keep (their fixtures are unchanged by 16G PR 3). */
    private static final Tenant AUSSIE =
            new Tenant(BUSINESS_AUSSIE, SLUG_AUSSIE, STORE_SYD_CBD, USER_LIAM, ORDER_NUMBER_PREFIX);

    // Section 8's own committed tenant (never a V4 identity), found and swept by its unique slug. Its store
    // code and salesperson code give order numbers NTXQEM.QE1.nnnnn (chk_sales_order_number_format).
    private static final String EMAIL_TENANT_SLUG = "ntxqic-email-tenant";
    private static final String EMAIL_TENANT_NAME = "Ntxqic Email Floors";
    private static final String EMAIL_TENANT_STORE_CODE = "NTXQEM";
    private static final String EMAIL_TENANT_SALESPERSON_CODE = "QE1";

    // Commit-time failure injection (class-specific names: no collision with any other test class).
    private static final String TRIGGER_FUNCTION = "ntxqic_fail_at_commit";
    private static final String TRIGGER_PREFIX = "ntxqic_fail_commit_";
    // 16G PR 3: the email-success stamp's commit (AFTER UPDATE ON invoice, last_emailed_at becoming non-null).
    private static final String STAMP_TRIGGER_FUNCTION = "ntxqic_fail_stamp_at_commit";
    private static final String STAMP_TRIGGER_PREFIX = "ntxqic_fail_stamp_";

    // Where the inherited signature file waits while it is "missing on disk": a sub-directory of the order's
    // storage dir, so filesOnDisk (regular files only) does not list it and the order-dir sweep removes it.
    private static final String SIGNATURE_MOVED_AWAY_DIR = "ntxqic-moved-away";

    // Children of sales_order deleted after quote_token / invoice / quote_version / quote_draft /
    // payment_transaction (every child FK is RESTRICT; the two *_line quote tables CASCADE from their parent).
    private static final List<String> ORDER_CHILD_TABLES = List.of(
            "order_attachment", "order_note", "order_product_line", "order_charge_line",
            "order_address", "order_customer", "order_enquiry");

    private static final String CUSTOMER_EMAIL = "quote.conversion.commit@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    // Section 8: the address the quote is issued to, and the saved email the conversion reads later - padded
    // (the DB CHECK allows it), so only the TRIMMED value can match the recipient.
    private static final String QUOTE_ISSUE_EMAIL = "quote.issue@example.com";
    private static final String PADDED_SAVED_EMAIL = "  nia.customer@example.com  ";
    // The padded saved email trimmed, written out literally: the only address the conversion may email.
    private static final String TRIMMED_SAVED_EMAIL = "nia.customer@example.com";
    // A VALID address committed as the saved email right AFTER the conversion commits (before its email):
    // never the recipient, never a reason to send.
    private static final String MOVED_AFTER_COMMIT_EMAIL = "moved.after.commit@example.com";
    // Every write the raw-connection hooks make is bounded, so a regression that held the conversion's locks
    // across the send can only fail the test, never hang it.
    private static final int HOOK_STATEMENT_TIMEOUT_SECONDS = 30;
    // DB-valid (non-blank, contains '@': chk_order_customer_email_format) but app-invalid (no interior dot in
    // the domain): CustomerEmailValidator refuses it, so Path A skips its email.
    private static final String APP_INVALID_EMAIL = "jordan@localhost";
    // The V17 issue-time customer_name_snapshot of the seeded customer: the frozen accepted name.
    private static final String FROZEN_CUSTOMER_NAME = "Quote Tester";
    private static final String DETAILS_OF_SALE = "Supply and install carpet (conversion commit test)";
    // proposed_lay_date 2026-12-01 minus 2 days.
    private static final LocalDate EXPECTED_DUE_DATE = LocalDate.of(2026, 11, 29);

    // The itemised two-line draft: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex / 275.00 inc GST.
    private static final String QUOTE_TOTAL_EX_GST = "250.00";
    private static final String QUOTE_TOTAL_INC_GST = "275.00";
    // The seeded live sale (one 100.00 ex / 40.00 cost charge line) invoiced through D.1: 110.00 inc.
    private static final String EARLIER_INVOICE_INC_GST = "110.00";
    private static final String EARLIER_PAYMENT = "20.00";

    // A fixed header updated_at, so ANY later header write is detectable.
    private static final Timestamp SEEDED_UPDATED_AT = Timestamp.valueOf("2026-01-01 09:00:00");
    // A NON-NULL committed sales_order.last_emailed_at: Path A's conversion resets it to null, so a failed
    // conversion must leave exactly this value behind, and a committed one never keeps it.
    private static final Timestamp SENTINEL_LAST_EMAILED_AT = Timestamp.valueOf("2026-02-03 04:05:06");

    // 16G PR 3 (16G contract §6.1): the Path A 201 message reports the post-commit email outcome. The former
    // "Invoice created from accepted quote." no longer exists. The dashes are U+2014 (em dash).
    private static final String CREATED_FROM_QUOTE_EMAILED_MESSAGE =
            "Invoice created from accepted quote and emailed to the customer.";
    private static final String CREATED_FROM_QUOTE_NOT_EMAILED_MESSAGE =
            "Invoice created from accepted quote. The invoice could not be emailed — use Re-send Invoice to try again.";
    private static final String CREATED_FROM_QUOTE_SKIPPED_MESSAGE =
            "Invoice created from accepted quote. It was not emailed because the customer has no valid email "
                    + "address — add one on the Customer tab, then use Re-send Invoice.";
    private static final String INVOICE_CREATED_MESSAGE = "Invoice created.";
    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";
    private static final String QUOTE_ACCEPTED_MESSAGE = "Quote accepted.";
    // The generic 500 envelope: no data, no details, no exception or database text.
    private static final String GENERIC_500_BODY =
            "{\"error\":{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"An unexpected error occurred.\"}}";
    private static final String FOOTER_TEXT = "Generated by the Flooring Sales Portal";

    // The real public link in the link-only quote email: <app.public-base-url>/q/{43-char token}.
    private static final Pattern PUBLIC_LINK_PATTERN =
            Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    // The invoice PDF acceptance caption format ("Accepted by {name} on {dd/MM/yyyy HH:mm}").
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    // A real, decodable 1x1 PNG: it passes the public accept's validation and embeds into the signed PDFs.
    private static final byte[] SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

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

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender notificationSender;

    // Spies call the REAL methods unless a test stubs them (doX().when(spy) form only). The InvoiceRepository
    // field is the @Repository CGLIB proxy around the spy; Spring's SpringMockResolver lets doX().when(..),
    // verify(..), clearInvocations(..) and reset(..) resolve through it. EXACTLY this set of four (types and
    // field names) is shared with order/InvoiceAcceptanceCommitIntegrationTest: one cached Spring context.
    @MockitoSpyBean
    private InvoicePdfGenerator invoicePdfGenerator;

    @MockitoSpyBean
    private FileStorageService fileStorageService;

    @MockitoSpyBean
    private InvoiceRepository invoiceRepository;

    // The singleton recording sender, spied: its own recorders (sentEmails / failedEmails / failNextSend) keep
    // working through the spy, and verify(..) proves whether the post-commit send was even attempted.
    @MockitoSpyBean
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes files recursively - never let it run against a real storage dir.
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class reads and deletes files only under the shared quote-acceptance test storage dir");
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
    // 1. Render failure (before any file write)
    // ================================================================

    /**
     * The invoice PDF render throws. 500 with the generic body; the committed state is exactly as before
     * (no invoice v3, no stored_file row, the sentinel mirror kept, quote versions / tokens / payments
     * untouched, the newer ISSUED quote v2 still ISSUED with an ACTIVE link); no file is written at all; the
     * inherited signature, every quote PDF and both earlier invoice PDFs are byte-identical; no email is even
     * attempted (16G PR 3: the sender is never called). A retry without the fault creates v3 from the accepted
     * v1 and emails it after its commit.
     */
    @Test
    void renderFailure_returns500_commitsAndEmailsNothing_writesNoFile_inheritedAndEarlierFilesIntact_retryCreatesAndEmailsV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        doThrow(new UncheckedIOException("Failed to generate invoice PDF",
                new IOException("injected invoice PDF render failure")))
                .when(invoicePdfGenerator).render(any());
        MvcResult result;
        try {
            result = createInvoiceFromQuote(fixture);
            // The injected failure point was really reached, exactly once.
            verify(invoicePdfGenerator).render(any());
        } finally {
            Mockito.reset(invoicePdfGenerator);
        }
        assertGeneric500(result, "Path A with a failing invoice PDF render");

        Assertions.assertEquals(List.of(), writes.attemptedExtensions(),
                "a render failure happens before the PDF write: no file may even be attempted");
        Assertions.assertEquals(List.of(), writes.writtenPaths(), "no file may be written");
        verify(invoiceRepository, never()).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), any());

        assertNothingCommitted(fixture, before);
        assertRetryCreatesQuoteInvoice(fixture, before);
    }

    // ================================================================
    // 2. Storage failure (the invoice PDF write)
    // ================================================================

    /**
     * The invoice PDF write ({@code FileStorageService.store} for extension "pdf") throws after a real render.
     * 500; nothing committed; no file written; the inherited and earlier files intact; no email attempted; a
     * retry creates v3 and emails it after its commit.
     */
    @Test
    void pdfStorageFailure_returns500_commitsAndEmailsNothing_writesNoFile_inheritedAndEarlierFilesIntact_retryCreatesAndEmailsV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(true);
        MvcResult result;
        try {
            result = createInvoiceFromQuote(fixture);
        } finally {
            Mockito.reset(fileStorageService);
        }
        assertGeneric500(result, "Path A with a failing invoice PDF write");

        verify(invoicePdfGenerator).render(any());
        Assertions.assertEquals(List.of("pdf"), writes.attemptedExtensions(),
                "exactly the invoice PDF write was attempted (the inherited signature is never re-stored)");
        Assertions.assertEquals(List.of(), writes.writtenPaths(), "the failed write must leave no file");
        verify(invoiceRepository, never()).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), any());

        assertNothingCommitted(fixture, before);
        assertRetryCreatesQuoteInvoice(fixture, before);
    }

    // ================================================================
    // 3. Persistence failure on the LAST write (after the file write and the insert)
    // ================================================================

    /**
     * The PDF is written, its stored_file row and the invoice v3 row are inserted inside the transaction, then
     * the sales_order mirror reset (the last write) throws. 500; the inserted stored_file and invoice rows are
     * NOT committed; the written PDF is gone from disk, deleted by BOTH cleanups (the in-method catch and the
     * afterCompletion rollback hook: exactly two deletes of that path); the sentinel mirror survives; the
     * inherited and earlier files are intact; no email is attempted; a retry creates v3 and emails it.
     */
    @Test
    void mirrorResetFailureAfterInsert_returns500_insertedRowsNotCommitted_writtenPdfDeleted_nothingEmailed_retryCreatesAndEmailsV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        List<InvoiceRow> insertedInvoices = recordInsertedInvoiceRows(fixture.orderId());
        doThrow(new DataAccessResourceFailureException("injected failure on the sales_order last_emailed_at mirror reset"))
                .when(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), any());
        MvcResult result;
        try {
            result = createInvoiceFromQuote(fixture);
            // The failure point (the reset to the new version's null last_emailed_at) was really reached.
            verify(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
        } finally {
            Mockito.reset(invoiceRepository);
        }
        assertGeneric500(result, "Path A with a failing mirror reset after the insert");

        // The file write and both inserts ran inside the transaction before the failure.
        Assertions.assertEquals(List.of("pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(1, writes.writtenPaths().size(), "the invoice PDF was written before the failure");
        String writtenPdf = writes.writtenPaths().get(0);
        Assertions.assertEquals(1, insertedStoredFileIds.size(), "the PDF stored_file row was inserted in-transaction");
        Assertions.assertEquals(1, insertedInvoices.size(), "the invoice row was inserted in-transaction");
        assertInsertedPathAInvoiceRow(fixture, insertedInvoices.get(0));

        assertFilesRemoved(List.of(writtenPdf), "a failure after the file write must remove the written PDF");
        // Both cleanups ran: the in-method catch (the mirror reset threw inside the method body) AND the
        // afterCompletion hook (the transaction rolled back). Losing either one drops this count.
        verify(fileStorageService, times(2)).deleteQuietly(writtenPdf);
        assertRowsNotCommitted(insertedStoredFileIds.get(0), insertedInvoices.get(0).invoiceId(), writtenPdf);

        assertNothingCommitted(fixture, before);
        assertRetryCreatesQuoteInvoice(fixture, before);
    }

    // ================================================================
    // 4. Persistence failure thrown by the invoice insert
    // ================================================================

    /**
     * The PDF is written and its stored_file row inserted, then the invoice insert throws. 500; the stored_file
     * row is NOT committed; the mirror reset never runs; the written PDF is gone from disk, deleted by BOTH
     * cleanups (the in-method catch and the afterCompletion rollback hook: exactly two deletes of that path);
     * the inherited and earlier files intact; no email is attempted; a retry creates v3 and emails it.
     */
    @Test
    void invoiceInsertFailure_returns500_storedFileRowNotCommitted_writtenPdfDeleted_nothingEmailed_retryCreatesAndEmailsV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        insertInvoiceFor(doThrow(new DataIntegrityViolationException("injected failure on the Path A invoice insert"))
                .when(invoiceRepository), fixture.orderId());
        MvcResult result;
        try {
            result = createInvoiceFromQuote(fixture);
            // The insert was really reached, for the next version and the selected quote version.
            verify(invoiceRepository).insertInvoice(eq(fixture.orderId()), eq(fixture.expectedVersion()),
                    any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong(),
                    any(), any(), any(), any(), eq(fixture.quoteVersionId()), any(),
                    any(), any(), any());
            verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());
        } finally {
            Mockito.reset(invoiceRepository);
        }
        assertGeneric500(result, "Path A with a failing invoice insert");

        Assertions.assertEquals(List.of("pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(1, writes.writtenPaths().size(), "the invoice PDF was written before the insert");
        String writtenPdf = writes.writtenPaths().get(0);
        Assertions.assertEquals(1, insertedStoredFileIds.size(), "the PDF stored_file row was inserted in-transaction");

        assertFilesRemoved(List.of(writtenPdf), "a failed insert must remove the written PDF");
        // Both cleanups ran: the in-method catch (the insert threw inside the method body) AND the
        // afterCompletion hook (the transaction rolled back). Losing either one drops this count.
        verify(fileStorageService, times(2)).deleteQuietly(writtenPdf);
        assertRowsNotCommitted(insertedStoredFileIds.get(0), null, writtenPdf);

        assertNothingCommitted(fixture, before);
        assertRetryCreatesQuoteInvoice(fixture, before);
    }

    // ================================================================
    // 5. Commit-time failure (deferred constraint trigger on invoice)
    // ================================================================

    /**
     * Every in-transaction step succeeds (render, PDF write, stored_file insert, invoice insert, mirror reset,
     * the post-commit email inputs) and the failure happens AT COMMIT. 500 with the generic body (no database
     * detail); nothing is committed; the written PDF is deleted by the afterCompletion hook ALONE (the method
     * body returned normally, so the in-method catch never ran: exactly one delete of that path); the inherited
     * and earlier files intact; the email, which runs only after a commit, is never attempted; a retry after
     * the trigger is dropped creates v3 and emails it.
     */
    @Test
    void commitTimeFailure_deferredInvoiceTrigger_returns500WithoutDbDetail_allWritesRanButNothingCommittedOrEmailed_hookDeletesPdf_retryCreatesAndEmailsV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        List<InvoiceRow> insertedInvoices = recordInsertedInvoiceRows(fixture.orderId());
        String trigger = TRIGGER_PREFIX + fixture.orderId();
        MvcResult result;
        try {
            installCommitFailureTrigger(trigger, fixture.orderId());
            Assertions.assertEquals(1L, count(
                            "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice'::regclass AND tgname = ?",
                            trigger),
                    "the commit-failure trigger must be installed before the request");
            result = createInvoiceFromQuote(fixture);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }
        assertGeneric500(result, "Path A failing at commit");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String leak : List.of("ntxqic", "P0001", "trigger", "commit", "constraint", "SQL")) {
            Assertions.assertFalse(body.contains(leak), "the 500 body must not leak database detail '" + leak + "': " + body);
        }

        // Every in-transaction write ran (so the failure was at COMMIT).
        Assertions.assertEquals(List.of("pdf"), writes.attemptedExtensions());
        Assertions.assertEquals(1, writes.writtenPaths().size(), "the invoice PDF was written");
        String writtenPdf = writes.writtenPaths().get(0);
        verify(invoiceRepository).insertStoredFile(
                eq(invoicePdfName(fixture.orderNumber(), fixture.expectedVersion())), eq(writtenPdf),
                eq("application/pdf"), anyLong());
        Assertions.assertEquals(1, insertedStoredFileIds.size(), "the PDF stored_file row was inserted in-transaction");
        Assertions.assertEquals(1, insertedInvoices.size(), "the invoice row was inserted in-transaction");
        assertInsertedPathAInvoiceRow(fixture, insertedInvoices.get(0));
        verify(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());

        // Only the afterCompletion rollback hook can have removed the file: the body completed normally.
        assertFilesRemoved(List.of(writtenPdf),
                "a commit-time rollback must delete the written PDF via the afterCompletion hook");
        verify(fileStorageService, times(1)).deleteQuietly(writtenPdf);
        assertRowsNotCommitted(insertedStoredFileIds.get(0), insertedInvoices.get(0).invoiceId(), writtenPdf);
        // The email-success stamp can only follow a delivered email, which can only follow a commit.
        verify(invoiceRepository, never()).updateInvoiceLastEmailedAt(anyLong(), any());

        assertNothingCommitted(fixture, before);
        assertRetryCreatesQuoteInvoice(fixture, before);
    }

    // ================================================================
    // 6. Payment carry-forward of a cross-order quote source (item 8 rollback)
    // ================================================================

    /**
     * Order A holds a REAL Path A invoice v1 (16G PR 3: converted and emailed after its commit); order B has
     * its own accepted quote. A's current invoice is then pointed (committed, through the single-column V19 FK)
     * at B's accepted version. Recording a payment on A carries that source forward and the source-ownership
     * check at the insert boundary refuses it (IllegalStateException) - 500 with the generic body. The refused
     * v2 insert carried total_paid 10.00 / balance_due 265.00, values only an in-transaction sum that already
     * sees the new payment row can produce, so the payment row really was written inside the transaction that
     * rolled back. Committed: no payment_transaction row, no invoice v2, no new stored_file row (none
     * references the regenerated PDF), the regenerated PDF that the payment wrote is gone from disk (deleted
     * by BOTH cleanups: the in-method catch and the afterCompletion rollback hook), order B untouched, no email
     * attempted (a payment never emails). With A's own source restored, the same payment commits v2 carrying
     * A's source forward, still without any email.
     */
    @Test
    void paymentCarryForward_crossOrderQuoteSource_returns500_noPaymentNoVersionNoFileRow_regeneratedPdfDeleted()
            throws Exception {
        // Order A: a real Path A invoice v1 (source = A's own accepted quote version).
        ConvertibleOrder orderA = seedConvertibleOrder(false, false);
        MvcResult converted = createInvoiceFromQuote(orderA);
        assertStatus(converted, 201, "setup: Path A on order A");
        Assertions.assertEquals(CREATED_FROM_QUOTE_EMAILED_MESSAGE, readJson(converted).path("message").asText(),
                "setup: the committed Path A conversion is emailed after its commit");
        Map<String, Object> pathAInvoice = row(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = 1", orderA.orderId());
        long pathAInvoiceId = toLong(pathAInvoice.get("invoice_id"));
        Assertions.assertEquals(orderA.quoteVersionId(), toLong(pathAInvoice.get("source_quote_version_id")),
                "setup: A's v1 is a Path A invoice sourced from A's accepted quote");
        Assertions.assertEquals(orderA.signatureFileId(), toLong(pathAInvoice.get("accepted_signature_file_id")),
                "setup: A's v1 inherits A's quote signature");
        ProtectedFile pathAPdf = protectedFile("order A Path A invoice v1 PDF", toLong(pathAInvoice.get("stored_file_id")));

        // Order B: its own accepted quote (never converted).
        ConvertibleOrder orderB = seedConvertibleOrder(false, false);

        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice SET source_quote_version_id = ? WHERE invoice_id = ?",
                        orderB.quoteVersionId(), pathAInvoiceId),
                "setup: the single-column V19 FK accepts another order's quote version");

        CommittedState beforeA = committedState(orderA);
        CommittedState beforeB = committedState(orderB);
        Assertions.assertTrue(beforeA.payments().isEmpty(), "setup: order A has no payment yet");
        Assertions.assertEquals(1, beforeA.invoices().size(), "setup: order A has exactly its Path A v1");
        resetSenders();
        clearSpyInvocations();
        StorageWrites writes = recordStorageWrites(false);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        List<Throwable> insertFailures = recordInsertInvoiceFailures(orderA.orderId());

        MvcResult result;
        try {
            result = recordPayment(orderA.tenant(), orderA.orderId(), "10.00");
            // The carried (cross-order) source reached the insert boundary for v2. Its total_paid (10.00) and
            // balance_due (275.00 - 10.00 = 265.00) come from sumAmountByOrderId run AFTER the payment insert:
            // order A had no payment before, so only the uncommitted 10.00 row of this same (rolled-back)
            // transaction can make the sum 10.00.
            verify(invoiceRepository).insertInvoice(eq(orderA.orderId()), eq(2),
                    any(), any(), any(), any(), any(),
                    argThat(totalPaid -> totalPaid != null && totalPaid.compareTo(new BigDecimal("10.00")) == 0),
                    argThat(balanceDue -> balanceDue != null && balanceDue.compareTo(new BigDecimal("265.00")) == 0),
                    anyLong(), anyLong(),
                    any(), any(), any(), any(), eq(orderB.quoteVersionId()), any(),
                    any(), any(), any());
            verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(orderA.orderId()), any());
        } finally {
            Mockito.reset(invoiceRepository);
        }
        assertGeneric500(result, "payment on an invoice carrying a cross-order quote source");

        // The 500 is the source-ownership refusal, thrown before the insert.
        Assertions.assertEquals(1, insertFailures.size(), "exactly one refused invoice insert");
        Throwable refusal = insertFailures.get(0);
        Assertions.assertInstanceOf(IllegalStateException.class, refusal, "the ownership check throws IllegalStateException");
        Assertions.assertTrue(refusal.getMessage().contains("belongs to order " + orderB.orderId()
                        + ", not to the invoice's order " + orderA.orderId()),
                "the refusal names the cross-order source: " + refusal.getMessage());

        // The regenerated PDF was written inside the transaction, then removed; no stored_file row references it.
        Assertions.assertEquals(List.of("pdf"), writes.attemptedExtensions(),
                "only the regenerated invoice PDF is written by a payment");
        Assertions.assertEquals(1, writes.writtenPaths().size(), "the regenerated PDF was written");
        String regeneratedPdf = writes.writtenPaths().get(0);
        assertFilesRemoved(List.of(regeneratedPdf), "the refused payment must remove the regenerated PDF it wrote");
        // Both cleanups ran: the in-method catch of the regeneration (the insert threw inside it) AND the
        // afterCompletion hook (the payment's TransactionTemplate rolled back). Losing either one drops this count.
        verify(fileStorageService, times(2)).deleteQuietly(regeneratedPdf);
        Assertions.assertEquals(1, insertedStoredFileIds.size(),
                "the regenerated PDF's stored_file row was inserted inside the payment transaction");
        assertRowsNotCommitted(insertedStoredFileIds.get(0), null, regeneratedPdf);

        CommittedState afterA = committedState(orderA);
        assertSameCommittedState(beforeA, afterA, "order A after the refused payment");
        Assertions.assertTrue(afterA.payments().isEmpty(), "no payment_transaction row may be committed");
        Assertions.assertEquals(List.of(1), invoiceVersions(orderA.orderId()), "no invoice v2 may be committed");
        Assertions.assertEquals(beforeA.storedFiles().size(), afterA.storedFiles().size(),
                "no stored_file row may be committed for order A");
        assertSameCommittedState(beforeB, committedState(orderB), "order B (the foreign source) after the refused payment");
        assertProtectedFilesIntact(orderA);
        assertProtectedFilesIntact(orderB);
        assertProtectedFileIntact(pathAPdf);
        assertNoEmailOrNotification();

        // Retry with A's own source restored: the same payment commits v2 carrying A's source forward.
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET source_quote_version_id = ? WHERE invoice_id = ?",
                orderA.quoteVersionId(), pathAInvoiceId));
        Mockito.reset(invoicePdfGenerator, fileStorageService, invoiceRepository, invoiceEmailSender);
        StorageWrites retryWrites = recordStorageWrites(false);
        MvcResult retried = recordPayment(orderA.tenant(), orderA.orderId(), "10.00");
        assertStatus(retried, 201, "the payment retried with A's own source");
        Assertions.assertEquals(PAYMENT_RECORDED_MESSAGE, readJson(retried).path("message").asText());

        Map<String, Object> v1 = row("SELECT * FROM invoice WHERE order_id = ? AND version_number = 1", orderA.orderId());
        Map<String, Object> v2 = row("SELECT * FROM invoice WHERE order_id = ? AND version_number = 2", orderA.orderId());
        Assertions.assertEquals(orderA.quoteVersionId(), toLong(v2.get("source_quote_version_id")),
                "the payment version carries A's own source forward");
        Assertions.assertEquals(v1.get("terms_snapshot"), v2.get("terms_snapshot"), "frozen terms carried verbatim");
        Assertions.assertEquals(v1.get("accepted_at"), v2.get("accepted_at"), "signature time carried");
        Assertions.assertEquals(v1.get("accepted_customer_name"), v2.get("accepted_customer_name"));
        Assertions.assertEquals(v1.get("accepted_signature_file_id"), v2.get("accepted_signature_file_id"),
                "the SAME inherited signature stored_file is referenced");
        assertMoney("10.00", v2.get("total_paid"), "v2 total_paid");
        assertMoney("265.00", v2.get("balance_due"), "v2 balance_due");
        Assertions.assertNull(v2.get("last_emailed_at"), "a payment version is never emailed");
        List<Map<String, Object>> payments = rows(
                "SELECT * FROM payment_transaction WHERE order_id = ? ORDER BY payment_transaction_id", orderA.orderId());
        Assertions.assertEquals(1, payments.size(), "exactly the retried payment is committed");
        assertMoney("10.00", payments.get(0).get("amount"), "committed payment amount");
        Assertions.assertNull(payments.get(0).get("voided_at"), "the committed payment is active");
        Assertions.assertEquals(1, retryWrites.writtenPaths().size(), "the retry wrote one regenerated PDF");
        Map<String, Object> v2File = storedFileRow(toLong(v2.get("stored_file_id")));
        Assertions.assertNotNull(v2File, "v2 has its stored_file row");
        Assertions.assertEquals(invoicePdfName(orderA.orderNumber(), 2), v2File.get("file_name"));
        Assertions.assertEquals(retryWrites.writtenPaths().get(0), v2File.get("storage_path"),
                "v2's stored_file is the file the retry wrote");
        Assertions.assertTrue(Files.isRegularFile(physicalPath(retryWrites.writtenPaths().get(0))),
                "the committed regenerated PDF stays on disk");
        assertNoEmailOrNotification();
    }

    // ================================================================
    // 7. Inherited-signature read failures (before the render: never an unsigned invoice)
    // ================================================================

    /**
     * The accepted quote's signature stored_file row is intact but its FILE is missing on disk, so reading the
     * inherited signature (FileStorageService.read, UncheckedIOException) fails before anything is rendered or
     * written. 500 with the generic body; the request stopped before the persist step, no email is attempted,
     * and the committed state, rows and the order's files on disk alike, is exactly as it was right before the
     * request. With the file moved back, every protected file is intact, the committed state equals the seed
     * state, and a retry creates v3 with the inherited signature embedded and emails it.
     */
    @Test
    void inheritedSignatureFileMissingOnDisk_returns500BeforeRender_writesAndEmailsNothing_restoredRetryCreatesAndEmailsSignedV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture);
        ProtectedFile signature = inheritedSignatureFile(fixture);
        Path signatureFile = physicalPath(signature.storagePath());
        Path orderDir = orderStorageDir(fixture);
        Assertions.assertEquals(orderDir, signatureFile.getParent(),
                "setup: the inherited signature file lives directly in the order's storage dir");
        Assertions.assertTrue(before.filesOnDisk().contains(fileName(signature.storagePath())),
                "setup: the inherited signature file is listed on disk");

        // Move the file away (its bytes are kept for the restore). If the test fails before the restore, the
        // order-dir sweep removes the holding dir along with everything else.
        Path movedAway = orderDir.resolve(SIGNATURE_MOVED_AWAY_DIR).resolve(signatureFile.getFileName());
        Files.createDirectories(movedAway.getParent());
        Files.move(signatureFile, movedAway);
        Assertions.assertFalse(Files.exists(signatureFile), "setup: the inherited signature file is missing on disk");
        Assertions.assertEquals(signature.row(), storedFileRow(fixture.signatureFileId()),
                "setup: only the file is missing - its (FK-protected) stored_file row is unchanged");

        assertInheritedSignatureFailureWritesNothing(fixture,
                "Path A with the inherited signature file missing on disk");
        // The failure point was really reached: the inherited signature's own storage path was read, exactly once.
        verify(fileStorageService).read(signature.storagePath());

        // Restore the same file, then the committed state must equal the seed state again (this re-checks every
        // protected file byte for byte, the inherited signature included) and a retry must succeed.
        Files.move(movedAway, signatureFile);
        Files.delete(movedAway.getParent());
        assertNothingCommitted(fixture, before);
        assertRetryCreatesQuoteInvoice(fixture, before);
    }

    /**
     * The ACCEPTED quote version carries a NULL accepted_customer_name (committed through SQL; no CHECK forbids
     * it). Incomplete acceptance metadata is an invariant failure: 500 with the generic body before anything is
     * rendered or written (never an invoice with an invented or missing accepted name, never an unsigned one),
     * no email is attempted, and the committed state is exactly as it was right before the request. With the
     * frozen name restored, the committed state equals the seed state and a retry creates v3 with the inherited
     * signature embedded and emails it.
     */
    @Test
    void acceptedQuoteCustomerNameNull_returns500BeforeRender_writesAndEmailsNothing_restoredRetryCreatesAndEmailsSignedV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture);
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE quote_version SET accepted_customer_name = NULL "
                                + "WHERE quote_version_id = ? AND status = 'ACCEPTED'",
                        fixture.quoteVersionId()),
                "setup: the ACCEPTED quote version loses its accepted_customer_name");

        assertInheritedSignatureFailureWritesNothing(fixture,
                "Path A with a NULL accepted_customer_name on the accepted quote version");

        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE quote_version SET accepted_customer_name = ? WHERE quote_version_id = ?",
                        FROZEN_CUSTOMER_NAME, fixture.quoteVersionId()),
                "restore the frozen accepted name");
        assertNothingCommitted(fixture, before);
        assertRetryCreatesQuoteInvoice(fixture, before);
    }

    /**
     * The ACCEPTED quote version carries a NULL accepted_signature_file_id (committed through SQL; no CHECK
     * forbids it; the signature stored_file row and its file stay in place). 500 with the generic body before
     * anything is rendered or written; no other signature is looked up or read in its place; no email is
     * attempted; the committed state is exactly as it was right before the request. With the reference
     * restored, the committed state equals the seed state and a retry creates v3 inheriting that SAME signature
     * stored_file, embedded, and emails it.
     */
    @Test
    void acceptedQuoteSignatureFileIdNull_returns500BeforeRender_noSubstituteSignature_writesAndEmailsNothing_restoredRetryCreatesAndEmailsSignedV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture);
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE quote_version SET accepted_signature_file_id = NULL "
                                + "WHERE quote_version_id = ? AND status = 'ACCEPTED'",
                        fixture.quoteVersionId()),
                "setup: the ACCEPTED quote version loses its accepted_signature_file_id");

        assertInheritedSignatureFailureWritesNothing(fixture,
                "Path A with a NULL accepted_signature_file_id on the accepted quote version");
        // No substitute signature: no stored_file is looked up and no file is read in place of the missing reference.
        verify(invoiceRepository, never()).findStoredFileById(anyLong());
        verify(fileStorageService, never()).read(any());

        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE quote_version SET accepted_signature_file_id = ? WHERE quote_version_id = ?",
                        fixture.signatureFileId(), fixture.quoteVersionId()),
                "restore the inherited signature reference");
        assertNothingCommitted(fixture, before);
        assertRetryCreatesQuoteInvoice(fixture, before);
    }

    // ================================================================
    // 8. Post-commit signed-PDF email (16G PR 3; contract §6.1, §6.2, §11): every outcome leaves the
    //    conversion COMMITTED with a 201. Self-seeded committed tenant; no V4 identity.
    // ================================================================

    /**
     * Delivery succeeds. The conversion has COMMITTED before the send: at send time no transaction is active on
     * the request thread, and a separate connection already sees the appended v3 and the mirror reset to null.
     * Exactly one email: the appended version's stored PDF, byte for byte (not an earlier version's), named
     * {@code invoice-{order}-v3.pdf}, with the business subject and body, addressed to the saved customer email,
     * TRIMMED - the saved email moved on (padded) after the quote was issued to another address, so neither the
     * quote's recipient nor the raw value is used (that it is the email the COMMITTING transaction read, never
     * one saved after the commit, is proven by the recipient test further down). The stamp then
     * writes the appended version's last_emailed_at once and the sales_order mirror with that same re-read
     * value: both committed markers equal, non-null and within the request; the earlier v1 and v2 rows stay
     * untouched; 201 with the emailed message and the stamped row; no WARN from the service.
     */
    @Test
    void postCommitEmail_delivered_201Emailed_sentAfterTheCommit_appendedVersionsStoredPdfToTheTrimmedSavedEmail_versionAndMirrorStampedAlike()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, QUOTE_ISSUE_EMAIL, true, false);
        Assertions.assertEquals(3, fixture.expectedVersion(), "setup: D.1 v1 + payment v2, so Path A appends v3");
        setSavedCustomerEmail(fixture, PADDED_SAVED_EMAIL);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        List<SendObservation> sends = observeSends(fixture);

        Conversion conversion = convert(fixture);

        CommittedConversion committed = assertConversionCommitted(fixture, before, conversion, writes,
                CREATED_FROM_QUOTE_EMAILED_MESSAGE, true);
        assertDeliveredOnce(fixture, committed, TRIMMED_SAVED_EMAIL);
        Assertions.assertEquals("nia.customer@example.com", invoiceEmailSender.sentEmails().get(0).recipientEmail(),
                "the recipient is the saved email at conversion time, trimmed - never the quote's recipient");

        // Sent strictly after the conversion committed, before the stamp.
        Assertions.assertEquals(1, sends.size(), "exactly one send");
        SendObservation send = sends.get(0);
        Assertions.assertFalse(send.transactionActive(), "the email is sent outside any transaction");
        Assertions.assertEquals(IntStream.rangeClosed(1, fixture.expectedVersion()).boxed().toList(),
                send.committedVersions(),
                "at send time the appended version was already COMMITTED (seen through a separate connection)");
        Assertions.assertNull(send.committedMirror(),
                "at send time the conversion's mirror reset was committed and the stamp had not run yet");

        // The stamp: one invoice write on the appended version, then the mirror set to the re-read value.
        long appendedInvoiceId = toLong(committed.created().get("invoice_id"));
        LocalDateTime stampedAt = ((Timestamp) committed.created().get("last_emailed_at")).toLocalDateTime();
        verify(invoiceRepository, times(1)).updateInvoiceLastEmailedAt(anyLong(), any());
        verify(invoiceRepository).updateInvoiceLastEmailedAt(eq(appendedInvoiceId), notNull());
        verify(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), eq(stampedAt));
        verify(invoiceRepository, times(1)).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
        verify(invoiceRepository, times(2)).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), any());
        assertNoServiceWarn(conversion);
    }

    /**
     * The transport refuses the delivery ({@code failNextSend}: one {@link
     * com.flooring.salesportal.common.email.InvoiceEmailException}). The conversion stays COMMITTED (v1, its
     * PDF, the mirror reset): 201 with the could-not-be-emailed message, both markers null (no stamp ran), the
     * one attempt recorded as failed with the appended version's stored PDF to the trimmed saved email, and
     * exactly one WARN - the failed line with the order id and version only: no throwable, no recipient, no
     * transport text.
     */
    @Test
    void postCommitEmail_deliveryFailure_201NotEmailed_conversionCommitted_bothMarkersNull_failedWarnWithoutThrowable()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, PADDED_SAVED_EMAIL, false, false);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        invoiceEmailSender.failNextSend();

        Conversion conversion = convert(fixture);

        CommittedConversion committed = assertConversionCommitted(fixture, before, conversion, writes,
                CREATED_FROM_QUOTE_NOT_EMAILED_MESSAGE, false);
        verify(invoiceEmailSender, times(1)).send(any());
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "nothing was delivered");
        List<InvoiceEmailRequest> failed = invoiceEmailSender.failedEmails();
        Assertions.assertEquals(1, failed.size(), "exactly one attempt, refused by the transport");
        assertSignedInvoiceEmail(fixture, committed, failed.get(0), TRIMMED_SAVED_EMAIL, "the refused attempt");
        assertStampNeverRan(fixture);
        assertOnlyServiceWarn(conversion, failedWarnLine(fixture),
                warnLeakMarkers(fixture, "forced by RecordingInvoiceEmailSender", "Email send failed"));
    }

    /**
     * The sender throws a runtime exception OTHER than the transport's ({@code IllegalStateException} whose text
     * even names the recipient). It is non-fatal exactly like a delivery failure: the conversion stays
     * COMMITTED, 201 with the could-not-be-emailed message, both markers null (no stamp ran), the one attempt
     * carried the appended version's stored PDF to the trimmed saved email (nothing recorded: the real send
     * never ran), and the single WARN is the failed line without any throwable or exception text.
     */
    @Test
    void postCommitEmail_otherSenderRuntimeException_201NotEmailed_conversionCommitted_bothMarkersNull_warnWithoutExceptionText()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, PADDED_SAVED_EMAIL, false, false);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        String crashText = "injected sender crash while mailing " + PADDED_SAVED_EMAIL.trim();
        doThrow(new IllegalStateException(crashText)).when(invoiceEmailSender).send(any());

        Conversion conversion = convert(fixture);

        CommittedConversion committed = assertConversionCommitted(fixture, before, conversion, writes,
                CREATED_FROM_QUOTE_NOT_EMAILED_MESSAGE, false);
        ArgumentCaptor<InvoiceEmailRequest> attempted = ArgumentCaptor.forClass(InvoiceEmailRequest.class);
        verify(invoiceEmailSender, times(1)).send(attempted.capture());
        assertSignedInvoiceEmail(fixture, committed, attempted.getValue(), TRIMMED_SAVED_EMAIL, "the crashed attempt");
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "the stubbed send recorded no delivery");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "the stubbed send recorded no transport failure");
        assertStampNeverRan(fixture);
        assertOnlyServiceWarn(conversion, failedWarnLine(fixture),
                warnLeakMarkers(fixture, crashText, "injected sender crash", "IllegalStateException"));
    }

    /**
     * Delivered, then the email-success stamp fails BEFORE ANY WRITE (its invoice update throws). The stamp
     * transaction wrote nothing and rolls back: the conversion stays COMMITTED, 201 with the EMAILED message
     * (the message reports the delivery), both markers null (the response is the committed row), the email
     * recorded exactly once, the mirror never set to a value, and the single WARN is the stamp line without a
     * throwable.
     */
    @Test
    void postCommitEmail_stampFailsBeforeAnyWrite_201Emailed_conversionCommitted_bothMarkersNull_emailedOnce_stampWarn()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, PADDED_SAVED_EMAIL, false, false);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        String injected = "injected failure on the email-success stamp's invoice update";
        doThrow(new DataAccessResourceFailureException(injected))
                .when(invoiceRepository).updateInvoiceLastEmailedAt(anyLong(), any());

        Conversion conversion = convert(fixture);

        CommittedConversion committed = assertConversionCommitted(fixture, before, conversion, writes,
                CREATED_FROM_QUOTE_EMAILED_MESSAGE, false);
        assertDeliveredOnce(fixture, committed, TRIMMED_SAVED_EMAIL);
        long appendedInvoiceId = toLong(committed.created().get("invoice_id"));
        // The stamp was reached (after the delivery) and failed at its first write; the mirror got no value.
        verify(invoiceRepository, times(1)).updateInvoiceLastEmailedAt(anyLong(), any());
        verify(invoiceRepository).updateInvoiceLastEmailedAt(eq(appendedInvoiceId), notNull());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), notNull());
        verify(invoiceRepository, times(1)).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
        assertOnlyServiceWarn(conversion, stampWarnLine(fixture), warnLeakMarkers(fixture, injected));
    }

    /**
     * Delivered, then the email-success stamp fails AFTER an attempted write: its invoice update runs (inside
     * the stamp transaction the appended version reads stamped, with exactly the value then passed to the
     * mirror), and its mirror update throws. The stamp transaction rolls back as a whole: both committed
     * markers are null - the invoice write is gone too. The conversion stays COMMITTED; 201 with the EMAILED
     * message; the email recorded exactly once; the single WARN is the stamp line without a throwable.
     */
    @Test
    void postCommitEmail_stampFailsAfterItsInvoiceWrite_201Emailed_stampRolledBack_bothMarkersNull_stampWarn()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, PADDED_SAVED_EMAIL, false, false);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        List<StampObservation> stamps = observeStampMirrorUpdate(fixture, true);

        Conversion conversion = convert(fixture);

        CommittedConversion committed = assertConversionCommitted(fixture, before, conversion, writes,
                CREATED_FROM_QUOTE_EMAILED_MESSAGE, false);
        assertDeliveredOnce(fixture, committed, TRIMMED_SAVED_EMAIL);
        long appendedInvoiceId = toLong(committed.created().get("invoice_id"));
        verify(invoiceRepository, times(1)).updateInvoiceLastEmailedAt(anyLong(), any());
        verify(invoiceRepository).updateInvoiceLastEmailedAt(eq(appendedInvoiceId), notNull());

        Assertions.assertEquals(1, stamps.size(), "the stamp reached its mirror update exactly once");
        StampObservation stamp = stamps.get(0);
        Assertions.assertNotNull(stamp.mirrorValue(), "the stamp passes the re-read current value to the mirror");
        Assertions.assertTrue(stamp.transactionActive(), "the stamp runs in its own transaction");
        Assertions.assertNotNull(stamp.invoiceInTransaction(),
                "the stamp's invoice write ran: inside its transaction the appended version reads stamped");
        Assertions.assertEquals(stamp.mirrorValue(), stamp.invoiceInTransaction().toLocalDateTime(),
                "the invoice value the stamp wrote is the one it passed to the mirror");
        Assertions.assertNull(stamp.mirrorInTransaction(),
                "the mirror write never happened (the injected failure replaced it)");
        // The committed markers (asserted null above) prove the stamp transaction rolled the invoice write back.
        assertOnlyServiceWarn(conversion, stampWarnLine(fixture),
                warnLeakMarkers(fixture, "injected failure on the email-success stamp"));
    }

    /**
     * Delivered, then the email-success stamp fails AT ITS COMMIT: a scoped {@code DEFERRABLE INITIALLY
     * DEFERRED} constraint trigger {@code AFTER UPDATE ON invoice} raises when the test order's
     * {@code last_emailed_at} becomes non-null. Both stamp writes ran (inside the stamp transaction the
     * appended version and the mirror both read the stamped value), so the failure is the commit itself, and it
     * rolls the whole stamp back: both committed markers are null. The conversion stays COMMITTED; 201 with the
     * EMAILED message; the email recorded exactly once; the single WARN is the stamp line, with no throwable and
     * no database text.
     */
    @Test
    void postCommitEmail_stampFailsAtItsCommit_201Emailed_stampRolledBack_bothMarkersNull_stampWarn()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, PADDED_SAVED_EMAIL, false, false);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        List<StampObservation> stamps = observeStampMirrorUpdate(fixture, false);
        String trigger = STAMP_TRIGGER_PREFIX + fixture.orderId();
        Conversion conversion;
        try {
            installStampCommitFailureTrigger(trigger, fixture.orderId());
            Assertions.assertEquals(1L, count(
                            "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice'::regclass AND tgname = ?",
                            trigger),
                    "the stamp commit-failure trigger must be installed before the request");
            conversion = convert(fixture);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        }

        CommittedConversion committed = assertConversionCommitted(fixture, before, conversion, writes,
                CREATED_FROM_QUOTE_EMAILED_MESSAGE, false);
        assertDeliveredOnce(fixture, committed, TRIMMED_SAVED_EMAIL);
        long appendedInvoiceId = toLong(committed.created().get("invoice_id"));
        verify(invoiceRepository, times(1)).updateInvoiceLastEmailedAt(anyLong(), any());
        verify(invoiceRepository).updateInvoiceLastEmailedAt(eq(appendedInvoiceId), notNull());

        // The stamp body ran to its end: the failure can only have been its commit.
        Assertions.assertEquals(1, stamps.size(), "the stamp reached its mirror update exactly once");
        StampObservation stamp = stamps.get(0);
        Assertions.assertNotNull(stamp.mirrorValue(), "the stamp passes the re-read current value to the mirror");
        Assertions.assertTrue(stamp.transactionActive(), "the stamp runs in its own transaction");
        Assertions.assertNotNull(stamp.invoiceInTransaction(), "the stamp's invoice write ran");
        Assertions.assertEquals(stamp.mirrorValue(), stamp.invoiceInTransaction().toLocalDateTime(),
                "inside the stamp transaction the appended version reads the stamped value");
        Assertions.assertNotNull(stamp.mirrorInTransaction(), "the stamp's mirror write ran");
        Assertions.assertEquals(stamp.mirrorValue(), stamp.mirrorInTransaction().toLocalDateTime(),
                "inside the stamp transaction the mirror reads the same stamped value");
        assertOnlyServiceWarn(conversion, stampWarnLine(fixture),
                warnLeakMarkers(fixture, "ntxqic", "P0001", "trigger"));
    }

    /**
     * The saved customer email is one the database accepts but the app rule refuses ({@code jordan@localhost},
     * no interior dot in the domain; saved after the quote was issued and signed). Path A has NO email gate: the
     * conversion is COMMITTED (v1, its PDF, the inherited signature, the mirror reset), 201 with the skipped
     * message, the sender is never called and nothing is recorded, both markers null (no stamp), and the single
     * WARN is the skip line with the order id and version only - not the address.
     */
    @Test
    void postCommitEmail_appInvalidSavedEmail_201Skipped_noSenderCall_conversionCommitted_bothMarkersNull_skipWarn()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, PADDED_SAVED_EMAIL, false, false);
        setSavedCustomerEmail(fixture, APP_INVALID_EMAIL);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);

        Conversion conversion = convert(fixture);

        assertConversionCommitted(fixture, before, conversion, writes, CREATED_FROM_QUOTE_SKIPPED_MESSAGE, false);
        assertNoInvoiceEmailAttempted("Path A with an app-invalid saved customer email");
        assertStampNeverRan(fixture);
        assertOnlyServiceWarn(conversion, skipWarnLine(fixture), warnLeakMarkers(fixture, "localhost"));
    }

    /**
     * The recipient is the saved email AS THE COMMITTING TRANSACTION READ IT, trimmed - never one saved after
     * the commit (16G contract §6.1: "read inside the committing transaction", D.8 parity). The saved email is
     * padded; an {@code afterCommit} hook registered on the conversion's last in-transaction write (the mirror
     * reset, after its customer read) commits a different, VALID address on another connection right after the
     * conversion commits, so at the send the committed saved email is ALREADY the moved one (a post-commit
     * re-read would find it). Exactly one email, to the ORIGINAL address trimmed; 201 with the emailed message;
     * both markers stamped alike; the committed customer row differs from the seed in its email only.
     */
    @Test
    void postCommitEmail_recipientIsTheTrimmedEmailTheCommittingTransactionRead_anEmailSavedAfterTheCommitIsNeverUsed_201Emailed_markersStampedAlike()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, PADDED_SAVED_EMAIL, false, false);
        CommittedState before = committedState(fixture);
        Assertions.assertEquals(List.of(PADDED_SAVED_EMAIL),
                before.customers().stream().map(customer -> customer.get("email")).toList(),
                "setup: the committed saved email is the padded original, and the order has exactly one customer");
        StorageWrites writes = recordStorageWrites(false);
        List<SendObservation> sends = observeSends(fixture);
        SavedEmailMoves moves = moveSavedEmailRightAfterTheConversionCommits(fixture, MOVED_AFTER_COMMIT_EMAIL);

        Conversion conversion = convert(fixture);

        String label = "Path A with the saved email moved to a valid address right after its commit";
        // ---- The hook ran exactly once, at the commit boundary: after the conversion committed, before the send ----
        assertSavedEmailMovedOnceRightAfterTheCommit(fixture, moves, PADDED_SAVED_EMAIL, MOVED_AFTER_COMMIT_EMAIL, label);
        Assertions.assertEquals(1, sends.size(), label + ": exactly one send");
        Assertions.assertEquals(MOVED_AFTER_COMMIT_EMAIL, sends.get(0).committedCustomerEmail(),
                label + ": at send time the committed saved email was ALREADY the moved one, so a post-commit"
                        + " re-read would have found it");

        // ---- The full committed conversion, delivered and stamped; the customer row differs in its email only ----
        CommittedConversion committed = assertConversionCommitted(fixture, before, conversion, writes,
                CREATED_FROM_QUOTE_EMAILED_MESSAGE, true, withSavedEmail(before.customers(), MOVED_AFTER_COMMIT_EMAIL),
                null);
        assertDeliveredOnce(fixture, committed, TRIMMED_SAVED_EMAIL);

        // ---- Restated literally: the one recipient, the markers, the moved email ----
        InvoiceEmailRequest sent = invoiceEmailSender.sentEmails().get(0);
        Assertions.assertEquals("nia.customer@example.com", sent.recipientEmail(),
                label + ": the recipient is the ORIGINAL saved email, trimmed, as the committing transaction read it");
        Assertions.assertNotEquals(MOVED_AFTER_COMMIT_EMAIL, sent.recipientEmail(),
                label + ": the email saved after the commit is never the recipient");
        Map<String, Object> markers = row("SELECT i.last_emailed_at AS invoice_marker, o.last_emailed_at AS mirror_marker "
                + "FROM invoice i JOIN sales_order o ON o.order_id = i.order_id WHERE i.invoice_id = ?",
                toLong(committed.created().get("invoice_id")));
        Assertions.assertNotNull(markers.get("invoice_marker"), label + ": the appended version's last_emailed_at is stamped");
        Assertions.assertEquals(markers.get("invoice_marker"), markers.get("mirror_marker"),
                label + ": both markers carry the same stamp");
        Assertions.assertEquals(MOVED_AFTER_COMMIT_EMAIL, savedCustomerEmail(fixture.orderId()),
                label + ": the committed saved email is the moved value");
        assertNoServiceWarn(conversion);
    }

    /**
     * The skip is decided on the saved email AS THE COMMITTING TRANSACTION READ IT too - never on one saved after
     * the commit. The saved email at the commit is app-invalid ({@code jordan@localhost}); the same
     * {@code afterCommit} hook commits a VALID address on another connection right after the conversion commits,
     * before the post-commit email runs (a post-commit re-read would find a valid address and send). Still
     * skipped: 201 with the skipped message, the sender never called and nothing recorded, both markers null (no
     * stamp), and the single WARN is the skip line with the order id and version only - no address; the
     * committed customer row differs from the seed in its email only.
     */
    @Test
    void postCommitEmail_skipIsDecidedOnTheEmailTheCommittingTransactionRead_aValidEmailSavedAfterTheCommitIsNeverUsed_201Skipped_noSenderCall_bothMarkersNull_skipWarn()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, PADDED_SAVED_EMAIL, false, false);
        setSavedCustomerEmail(fixture, APP_INVALID_EMAIL);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        SavedEmailMoves moves = moveSavedEmailRightAfterTheConversionCommits(fixture, MOVED_AFTER_COMMIT_EMAIL);

        Conversion conversion = convert(fixture);

        String label = "Path A with an app-invalid saved email moved to a valid address right after its commit";
        assertSavedEmailMovedOnceRightAfterTheCommit(fixture, moves, APP_INVALID_EMAIL, MOVED_AFTER_COMMIT_EMAIL, label);
        assertConversionCommitted(fixture, before, conversion, writes, CREATED_FROM_QUOTE_SKIPPED_MESSAGE, false,
                withSavedEmail(before.customers(), MOVED_AFTER_COMMIT_EMAIL), null);
        assertNoInvoiceEmailAttempted(label);
        assertStampNeverRan(fixture);
        assertOnlyServiceWarn(conversion, skipWarnLine(fixture),
                warnLeakMarkers(fixture, APP_INVALID_EMAIL, "localhost", MOVED_AFTER_COMMIT_EMAIL));
    }

    /**
     * The Path A 201 describes the version THIS REQUEST APPENDED, with its own stamp, even when another request
     * makes a newer version current between the conversion's commit and its email-success stamp (16G PR 3: the
     * response is re-read by invoice_id inside the stamp transaction; D.8 keeps answering with the stamp's
     * re-read CURRENT row and is not tested here). At the send - after the commit, before the stamp - a newer
     * v(N+1) is committed on another connection the way a payment would: a copy of the appended row with its own
     * stored_file row and file, unemailed, the mirror reset to null. The email is delivered; the stamp writes the
     * APPENDED version, then sets the mirror to the CURRENT version's value. 201 with the emailed message whose
     * data.invoice is version N (its own invoice_id; accepted, terms_source QUOTE) with a last_emailed_at equal,
     * to the second, to N's committed stamp; N+1 stays current, exactly as committed and unemailed, and the
     * sales_order mirror follows it: null.
     */
    @Test
    void postCommitEmail_newerVersionCommittedBetweenTheCommitAndTheStamp_201DescribesTheVersionThisRequestAppended_withItsOwnStamp_mirrorFollowsTheCurrentVersion()
            throws Exception {
        Tenant tenant = seedEmailTenant();
        ConvertibleOrder fixture = seedConvertibleOrder(tenant, PADDED_SAVED_EMAIL, false, false);
        CommittedState before = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        NewerVersionCommits commits = commitNewerVersionAtTheSend(fixture);

        Conversion conversion = convert(fixture);

        // ---- The hook committed exactly one newer, unemailed version N+1 at the one send ----
        Assertions.assertEquals(List.of(), commits.failures(), "the at-send hook itself must not fail");
        Assertions.assertEquals(1, commits.committed().size(), "the hook committed exactly one newer version, at the one send");
        NewerVersion newer = commits.committed().get(0);
        int appendedVersion = fixture.expectedVersion();
        Assertions.assertEquals(appendedVersion + 1, ((Number) newer.invoice().get("version_number")).intValue(),
                "setup: the hook's version is N+1");
        Assertions.assertNull(newer.invoice().get("last_emailed_at"), "setup: the hook's version N+1 is unemailed");

        // ---- The full committed conversion, delivered and stamped, plus exactly the hook's N+1 rows and file ----
        CommittedConversion committed = assertConversionCommitted(fixture, before, conversion, writes,
                CREATED_FROM_QUOTE_EMAILED_MESSAGE, true, before.customers(), newer);
        assertDeliveredOnce(fixture, committed, TRIMMED_SAVED_EMAIL);

        // ---- Restated: data.invoice is the APPENDED version N, with its own stamp ----
        long appendedInvoiceId = toLong(committed.created().get("invoice_id"));
        long newerInvoiceId = toLong(newer.invoice().get("invoice_id"));
        JsonNode response = committed.responseInvoice();
        Assertions.assertEquals(appendedVersion, response.path("version_number").asInt(),
                "data.invoice is the APPENDED version N, not the newer current N+1");
        Assertions.assertEquals(appendedInvoiceId, response.path("invoice_id").asLong(),
                "data.invoice is the appended row itself");
        Assertions.assertNotEquals(newerInvoiceId, response.path("invoice_id").asLong(), "never the newer current row");
        Assertions.assertTrue(response.path("accepted_signature_present").asBoolean(),
                "the appended version is accepted (the inherited signature)");
        Assertions.assertEquals(fixture.quoteAcceptedAt().toLocalDateTime().truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(response.path("accepted_at").asText()),
                "accepted at the quote's acceptance time (to the second, the InvoiceDetail format)");
        Assertions.assertEquals("QUOTE", response.path("terms_source").asText(), "the appended version is quote-sourced");
        Timestamp appendedStamp = jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM invoice WHERE invoice_id = ?", Timestamp.class, appendedInvoiceId);
        Assertions.assertNotNull(appendedStamp, "version N is stamped");
        Assertions.assertFalse(response.get("last_emailed_at").isNull(), "data.invoice carries N's own stamp");
        Assertions.assertEquals(appendedStamp.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(response.get("last_emailed_at").asText()),
                "data.invoice last_emailed_at is N's committed stamp (to the second, the InvoiceDetail format)");

        // ---- The CURRENT version is the newer N+1, unemailed; the sales_order mirror follows it ----
        Map<String, Object> current = row(
                "SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number DESC LIMIT 1", fixture.orderId());
        Assertions.assertEquals(newerInvoiceId, toLong(current.get("invoice_id")), "N+1 is the current version");
        Assertions.assertNull(current.get("last_emailed_at"),
                "the current N+1 stays unemailed: the stamp writes only the appended version");
        Assertions.assertNull(jdbcTemplate.queryForObject(
                        "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, fixture.orderId()),
                "the sales_order mirror follows the CURRENT version (null), never the stamped appended one");

        // ---- The stamp: one invoice write, of N; its mirror write carried the current N+1's null ----
        verify(invoiceRepository, times(1)).updateInvoiceLastEmailedAt(anyLong(), any());
        verify(invoiceRepository).updateInvoiceLastEmailedAt(eq(appendedInvoiceId), notNull());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), notNull());
        verify(invoiceRepository, times(2)).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
        assertNoServiceWarn(conversion);
    }

    // ================================================================
    // Shared assertions
    // ================================================================

    /** 500 with EXACTLY the generic error envelope (no data, no details, no exception text). */
    private void assertGeneric500(MvcResult result, String label) throws Exception {
        assertStatus(result, 500, label);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertEquals(JSON.readTree(GENERIC_500_BODY), JSON.readTree(body),
                label + ": the 500 body must be exactly the generic error envelope, but was " + body);
    }

    /** The in-transaction Path A row carried the expected version, source and inherited signature. */
    private static void assertInsertedPathAInvoiceRow(ConvertibleOrder fixture, InvoiceRow row) {
        Assertions.assertTrue(row.invoiceId() > 0, "the in-transaction insert returned a generated invoice_id");
        Assertions.assertEquals(fixture.orderId(), row.orderId());
        Assertions.assertEquals(fixture.expectedVersion(), row.versionNumber(), "the next invoice version");
        Assertions.assertEquals(fixture.quoteVersionId(), row.sourceQuoteVersionId(), "the selected quote version");
        Assertions.assertEquals(fixture.signatureFileId(), row.acceptedSignatureFileId(), "the inherited signature");
    }

    /** Neither the in-transaction stored_file row, the invoice row nor any row for the written path is committed. */
    private void assertRowsNotCommitted(Long storedFileId, Long invoiceId, String writtenPath) {
        if (storedFileId != null) {
            Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM stored_file WHERE stored_file_id = ?", storedFileId),
                    "the stored_file row inserted inside the failed transaction must not be committed");
        }
        if (invoiceId != null) {
            Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM invoice WHERE invoice_id = ?", invoiceId),
                    "the invoice row inserted inside the failed transaction must not be committed");
        }
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM stored_file WHERE storage_path = ?", writtenPath),
                "no committed stored_file row may reference the written file");
    }

    /**
     * A failed conversion left the committed state EXACTLY as before and sent nothing: the invoice email
     * sender was never even called (a pre-commit failure never reaches the post-commit email).
     */
    private void assertNothingCommitted(ConvertibleOrder fixture, CommittedState before) throws IOException {
        CommittedState after = committedState(fixture);
        assertSameCommittedState(before, after, "a failed conversion");
        // Readable restatements of the key invariants (all implied by the equality above).
        Assertions.assertEquals(fixture.expectedVersion() - 1, after.invoices().size(),
                "no invoice version may be committed");
        Assertions.assertEquals(SENTINEL_LAST_EMAILED_AT, after.salesOrder().get("last_emailed_at"),
                "the committed sales_order.last_emailed_at mirror sentinel must survive");
        Assertions.assertEquals("ACCEPTED", after.quoteVersions().get(0).get("status"), "the quote stays ACCEPTED");
        assertNewerIssuedQuoteStillLive(fixture);
        assertProtectedFilesIntact(fixture);
        assertNoEmailOrNotification();
    }

    /**
     * Run Path A against an accepted quote whose inherited signature cannot be read (the caller has already
     * committed the defect) and prove the request failed BEFORE the persist step, so it can never have produced
     * an unsigned invoice: the generic 500; the request reached the D5(b) read of the current invoice (so the
     * scope lock, the body check and the latest-ACCEPTED selection all passed); nothing was rendered; no file
     * write was even attempted; no cleanup delete ran against any path (the inherited signature included); no
     * stored_file / invoice insert and no mirror reset; no email was attempted; and the committed state, every
     * row and the order's files on disk, is exactly the state right before the request.
     */
    private void assertInheritedSignatureFailureWritesNothing(ConvertibleOrder fixture, String label) throws Exception {
        CommittedState defective = committedState(fixture);
        StorageWrites writes = recordStorageWrites(false);
        MvcResult result = createInvoiceFromQuote(fixture);
        assertGeneric500(result, label);

        verify(invoiceRepository).findCurrentByOrderId(fixture.orderId());
        verify(invoicePdfGenerator, never()).render(any());
        Assertions.assertEquals(List.of(), writes.attemptedExtensions(), label + ": no file write may even be attempted");
        Assertions.assertEquals(List.of(), writes.writtenPaths(), label + ": no file may be written");
        verify(fileStorageService, never()).deleteQuietly(any());
        verify(invoiceRepository, never()).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());

        assertSameCommittedState(defective, committedState(fixture), label);
        assertNoEmailOrNotification();
    }

    /** A newer ISSUED quote version (when the fixture has one) stays ISSUED and its link stays ACTIVE. */
    private void assertNewerIssuedQuoteStillLive(ConvertibleOrder fixture) {
        if (fixture.newerIssuedVersionId() == null) {
            return;
        }
        Assertions.assertEquals("ISSUED", jdbcTemplate.queryForObject(
                        "SELECT status FROM quote_version WHERE quote_version_id = ?", String.class,
                        fixture.newerIssuedVersionId()),
                "the newer ISSUED quote version must stay ISSUED");
        Assertions.assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                        "SELECT status FROM quote_token WHERE quote_version_id = ?", String.class,
                        fixture.newerIssuedVersionId()),
                "the newer quote's link must stay ACTIVE");
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

    /**
     * Remove every injected fault (spies back to real behaviour, no trigger left) and convert again: the
     * conversion commits (one appended invoice row - the signed quote snapshot inheriting the quote's
     * signature row - one new PDF stored_file row + file, the mirror reset; everything else identical), and,
     * since 16G PR 3, exactly that version is emailed after the commit (its stored PDF, to the trimmed saved
     * customer email) and stamped: the appended version's last_emailed_at and the sales_order mirror carry ONE
     * timestamp. 201 with the emailed message; no quote email or store notification; no WARN.
     */
    private void assertRetryCreatesQuoteInvoice(ConvertibleOrder fixture, CommittedState before) throws Exception {
        Mockito.reset(invoicePdfGenerator, fileStorageService, invoiceRepository, invoiceEmailSender);
        dropCommitFailureTriggers();
        StorageWrites writes = recordStorageWrites(false);
        // The saved email as the retry's committing transaction will read it (nothing moves it meanwhile).
        String expectedRecipient = savedCustomerEmail(fixture.orderId()).trim();
        Conversion conversion = convert(fixture);
        CommittedConversion committed = assertConversionCommitted(fixture, before, conversion, writes,
                CREATED_FROM_QUOTE_EMAILED_MESSAGE, true);
        assertDeliveredOnce(fixture, committed, expectedRecipient);
        assertNoServiceWarn(conversion);
    }

    /**
     * A COMMITTED Path A conversion, whatever its post-commit email did: 201 with {@code expectedMessage}; the
     * response is the expected next version with the signed quote's totals, the inherited signature and frozen
     * terms, and no internal field; the ONLY committed changes are one appended invoice row (the signed quote
     * snapshot, inheriting the quote's signature row), one new PDF stored_file row + file, and
     * {@code sales_order.last_emailed_at}. The markers report what was recorded: {@code expectStamped} - the
     * appended version's last_emailed_at and the mirror carry ONE non-null timestamp from within the request,
     * and the response is that stamped row; otherwise both are null and so is the response's. Every earlier
     * invoice row, the quote versions / lines / tokens / draft, payments, customer, addresses, lines and every
     * other sales_order column are identical; the protected files are intact; the committed PDF is the signed
     * quote snapshot with the inherited signature embedded; no quote email or store notification. The email
     * outcome itself (sent / failed / not attempted) is the caller's to assert.
     */
    private CommittedConversion assertConversionCommitted(ConvertibleOrder fixture, CommittedState before,
                                                          Conversion conversion, StorageWrites writes,
                                                          String expectedMessage, boolean expectStamped)
            throws Exception {
        return assertConversionCommitted(fixture, before, conversion, writes, expectedMessage, expectStamped,
                before.customers(), null);
    }

    /**
     * {@link #assertConversionCommitted(ConvertibleOrder, CommittedState, Conversion, StorageWrites, String,
     * boolean)} for a test that itself committed something around the conversion. {@code expectedCustomers}: the
     * committed order_customer rows expected after the request - Path A never writes them, so these are
     * {@code before.customers()} unless the test moved the saved email after the commit. {@code newer}, when not
     * null: a newer invoice version the test committed between the conversion's commit and its email stamp. It
     * must be the current version, exactly as committed (the stamp never writes it), its stored_file row and file
     * the only other additions; the sales_order mirror then follows IT (the §11.1 current version), while the
     * appended version keeps its own stamp and stays the response row.
     */
    private CommittedConversion assertConversionCommitted(ConvertibleOrder fixture, CommittedState before,
                                                          Conversion conversion, StorageWrites writes,
                                                          String expectedMessage, boolean expectStamped,
                                                          List<Map<String, Object>> expectedCustomers,
                                                          NewerVersion newer)
            throws Exception {
        MvcResult result = conversion.result();
        assertStatus(result, 201, "the committed Path A conversion");

        BigDecimal expectedBalance = new BigDecimal(QUOTE_TOTAL_INC_GST).subtract(new BigDecimal(fixture.activePayments()));
        JsonNode root = readJson(result);
        Assertions.assertEquals(expectedMessage, root.path("message").asText(),
                "the 201 message reports the post-commit email outcome");
        JsonNode invoice = root.path("data").path("invoice");
        Assertions.assertEquals(fixture.expectedVersion(), invoice.path("version_number").asInt(), "response version");
        assertMoney(QUOTE_TOTAL_EX_GST, invoice.path("sale_price_ex_gst"), "response sale_price_ex_gst");
        assertMoney(QUOTE_TOTAL_INC_GST, invoice.path("sale_price_inc_gst"), "response sale_price_inc_gst");
        assertMoney(fixture.activePayments(), invoice.path("total_paid"), "response total_paid");
        assertMoney(expectedBalance.toPlainString(), invoice.path("balance_due"), "response balance_due");
        Assertions.assertTrue(invoice.path("accepted_signature_present").asBoolean(), "the signature is inherited");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, invoice.path("accepted_customer_name").asText());
        Assertions.assertEquals("QUOTE", invoice.path("terms_source").asText(), "a quote-sourced invoice");
        Assertions.assertTrue(invoice.has("terms_html"), "terms_html is always present");
        String responseTerms = invoice.get("terms_html").isNull() ? null : invoice.get("terms_html").asText();
        Assertions.assertEquals(fixture.quoteTermsSnapshot(), responseTerms, "terms_html = the frozen quote terms verbatim");
        Assertions.assertTrue(invoice.has("last_emailed_at"), "last_emailed_at is always serialized");
        for (String internal : List.of("source_quote_version_id", "stored_file_id", "accepted_signature_file_id",
                "terms_snapshot", "storage_path")) {
            Assertions.assertFalse(invoice.has(internal), "internal field must never be serialized: " + internal);
        }

        Assertions.assertEquals(List.of("pdf"), writes.attemptedExtensions(), "the conversion writes only the invoice PDF");
        Assertions.assertEquals(1, writes.writtenPaths().size());
        String pdfPath = writes.writtenPaths().get(0);

        CommittedState after = committedState(fixture);
        int previousInvoices = before.invoices().size();
        if (newer == null) {
            Assertions.assertEquals(previousInvoices + 1, after.invoices().size(), "exactly one invoice row appended");
        } else {
            Assertions.assertEquals(previousInvoices + 2, after.invoices().size(),
                    "exactly the appended invoice row plus the newer version the test committed");
            Assertions.assertEquals(newer.invoice(), after.invoices().get(previousInvoices + 1),
                    "the newer version is the current (last) one, exactly as committed: the stamp never writes it");
        }
        Assertions.assertEquals(before.invoices(), after.invoices().subList(0, previousInvoices),
                "every earlier invoice row is untouched (the stamp writes only the appended version)");
        Map<String, Object> created = after.invoices().get(previousInvoices);
        Assertions.assertEquals(fixture.expectedVersion(), ((Number) created.get("version_number")).intValue());
        Assertions.assertEquals(toLong(created.get("invoice_id")), invoice.path("invoice_id").asLong(),
                "the response describes the version this conversion appended (its own invoice_id)");
        LocalDate invoiceDate = ((Date) created.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(conversion.startedAt().toLocalDate())
                        || invoiceDate.isAfter(conversion.finishedAt().toLocalDate()),
                "invoice_date is today: " + invoiceDate);
        Assertions.assertEquals(EXPECTED_DUE_DATE, ((Date) created.get("due_date")).toLocalDate(),
                "due_date = proposed_lay_date - 2 days");
        Assertions.assertEquals(fixture.quoteDetailsOfSale(), created.get("details_of_sale_snapshot"),
                "details of sale = the quote's frozen snapshot");
        assertMoney(QUOTE_TOTAL_EX_GST, created.get("sale_price_ex_gst"), "invoice sale_price_ex_gst");
        assertMoney(QUOTE_TOTAL_INC_GST, created.get("sale_price_inc_gst"), "invoice sale_price_inc_gst");
        assertMoney(fixture.activePayments(), created.get("total_paid"), "invoice total_paid = active payments");
        assertMoney(expectedBalance.toPlainString(), created.get("balance_due"), "invoice balance_due");
        Assertions.assertEquals(fixture.quoteAcceptedAt(), created.get("accepted_at"),
                "accepted_at = the quote's accepted_at (copied, not now)");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, created.get("accepted_customer_name"));
        Assertions.assertEquals(fixture.signatureFileId(), toLong(created.get("accepted_signature_file_id")),
                "the invoice references the quote's SAME signature stored_file");
        Assertions.assertEquals(fixture.tenant().userId(), toLong(created.get("created_by_user_id")),
                "created by the session user");
        Assertions.assertEquals(fixture.quoteVersionId(), toLong(created.get("source_quote_version_id")),
                "source_quote_version_id = the accepted quote version");
        Assertions.assertTrue(Objects.equals(fixture.quoteTermsSnapshot(), created.get("terms_snapshot")),
                "terms_snapshot = the quote's frozen terms verbatim (including null)");

        // The post-commit markers (16G contract §11): what was recorded, never what was attempted.
        Timestamp versionMarker = (Timestamp) created.get("last_emailed_at");
        Timestamp mirrorMarker = (Timestamp) after.salesOrder().get("last_emailed_at");
        // §11.1: the mirror follows the CURRENT version - the appended one, or the test's newer one when it exists.
        Timestamp expectedMirror = newer == null ? versionMarker : (Timestamp) newer.invoice().get("last_emailed_at");
        if (expectStamped) {
            Assertions.assertNotNull(versionMarker, "a delivered and stamped email sets the appended version's last_emailed_at");
            if (newer == null) {
                Assertions.assertEquals(versionMarker, mirrorMarker,
                        "the sales_order mirror carries the SAME timestamp as the appended version");
            } else {
                Assertions.assertEquals(expectedMirror, mirrorMarker,
                        "the sales_order mirror follows the CURRENT (newer) version, not the stamped appended one");
            }
            LocalDateTime stampedAt = versionMarker.toLocalDateTime();
            Assertions.assertFalse(stampedAt.isBefore(conversion.startedAt().truncatedTo(ChronoUnit.MICROS))
                            || stampedAt.isAfter(conversion.finishedAt().truncatedTo(ChronoUnit.MICROS)
                            .plus(1, ChronoUnit.MICROS)),
                    "the stamp time lies within the request: " + stampedAt);
            Assertions.assertFalse(invoice.get("last_emailed_at").isNull(), "the response is the stamped appended row");
            // InvoiceDetail serializes last_emailed_at as yyyy-MM-dd'T'HH:mm:ss (whole seconds).
            Assertions.assertEquals(stampedAt.truncatedTo(ChronoUnit.SECONDS),
                    LocalDateTime.parse(invoice.get("last_emailed_at").asText()),
                    "the response last_emailed_at is the committed stamp (to the second, the InvoiceDetail format)");
        } else {
            Assertions.assertNull(versionMarker, "nothing recorded: the appended version's last_emailed_at stays null");
            Assertions.assertNull(mirrorMarker, "nothing recorded: the sales_order mirror stays null (reset by the conversion)");
            Assertions.assertTrue(invoice.get("last_emailed_at").isNull(),
                    "the response is the committed, unstamped row");
        }

        // Exactly one new stored_file row (the invoice PDF) and one new file on disk; no new signature row/file
        // (plus, when the test committed a newer version, exactly that version's own stored_file row and file).
        int previousFiles = before.storedFiles().size();
        if (newer == null) {
            Assertions.assertEquals(previousFiles + 1, after.storedFiles().size(), "exactly one stored_file row added");
        } else {
            Assertions.assertEquals(previousFiles + 2, after.storedFiles().size(),
                    "exactly the conversion's PDF row plus the newer version's own stored_file row");
            Assertions.assertEquals(newer.storedFile(), after.storedFiles().get(previousFiles + 1),
                    "the newer version's stored_file row is exactly as committed");
            Assertions.assertEquals(toLong(newer.invoice().get("stored_file_id")),
                    toLong(newer.storedFile().get("stored_file_id")), "the newer version references its own PDF row");
        }
        Assertions.assertEquals(before.storedFiles(), after.storedFiles().subList(0, previousFiles),
                "every earlier stored_file row (inherited signature, quote PDFs, earlier invoices) is untouched");
        Map<String, Object> pdfRow = after.storedFiles().get(previousFiles);
        Assertions.assertEquals(toLong(created.get("stored_file_id")), toLong(pdfRow.get("stored_file_id")),
                "the new stored_file row is the new invoice's PDF");
        Assertions.assertEquals(invoicePdfName(fixture.orderNumber(), fixture.expectedVersion()), pdfRow.get("file_name"));
        Assertions.assertEquals("application/pdf", pdfRow.get("mime_type"));
        Assertions.assertEquals(pdfPath, pdfRow.get("storage_path"), "the committed row is the file this conversion wrote");
        Set<String> expectedFiles = new TreeSet<>(before.filesOnDisk());
        expectedFiles.add(fileName(pdfPath));
        if (newer != null) {
            expectedFiles.add(fileName((String) newer.storedFile().get("storage_path")));
        }
        Assertions.assertEquals(expectedFiles, after.filesOnDisk(), newer == null
                ? "exactly the new invoice PDF was added on disk"
                : "exactly the new invoice PDF and the newer version's file were added on disk");

        // The only order write is the mirror: reset to null by the conversion, then (when stamped) set to the
        // CURRENT version's value - the appended version's stamp, or the test's newer version's null.
        Map<String, Object> expectedOrder = new LinkedHashMap<>(before.salesOrder());
        expectedOrder.put("last_emailed_at", expectedMirror);
        Assertions.assertEquals(expectedOrder, after.salesOrder(),
                "Path A writes only sales_order.last_emailed_at (the mirror of the current version)");
        Assertions.assertEquals(before.quoteVersions(), after.quoteVersions(), "quote versions untouched");
        Assertions.assertEquals(before.quoteVersionLines(), after.quoteVersionLines(), "quote version lines untouched");
        Assertions.assertEquals(before.quoteTokens(), after.quoteTokens(), "quote tokens untouched");
        Assertions.assertEquals(before.quoteDraft(), after.quoteDraft(), "quote draft untouched");
        Assertions.assertEquals(before.quoteDraftLines(), after.quoteDraftLines(), "quote draft lines untouched");
        Assertions.assertEquals(before.payments(), after.payments(), "payments untouched");
        Assertions.assertEquals(expectedCustomers, after.customers(), expectedCustomers.equals(before.customers())
                ? "customer untouched"
                : "the customer row differs only by what the test itself committed after the conversion");
        Assertions.assertEquals(before.addresses(), after.addresses(), "addresses untouched");
        Assertions.assertEquals(before.chargeLines(), after.chargeLines(), "charge lines untouched");
        Assertions.assertEquals(before.productLines(), after.productLines(), "product lines untouched");
        if (fixture.newerIssuedVersionId() != null) {
            Assertions.assertNotEquals(fixture.newerIssuedVersionId(), toLong(created.get("source_quote_version_id")),
                    "Path A selects the latest ACCEPTED version, never the newer ISSUED one");
        }
        assertNewerIssuedQuoteStillLive(fixture);
        assertProtectedFilesIntact(fixture);

        // The committed PDF: the signed quote snapshot with the inherited signature embedded.
        byte[] pdf = Files.readAllBytes(physicalPath(pdfPath));
        Assertions.assertTrue(startsWithPdfMagic(pdf), "the stored invoice must be a PDF");
        Assertions.assertEquals(toLong(pdfRow.get("file_size")), (long) pdf.length,
                "stored_file.file_size matches the PDF on disk");
        String text = pdfText(pdf);
        String flatText = text.replaceAll("\\s+", " ");
        // The caption's inline spans are emitted out of content-stream order, so the caption is read from the
        // position-sorted text (left to right on its line).
        assertAcceptedCaption(pdfTextSortedByPosition(pdf), FROZEN_CUSTOMER_NAME,
                fixture.quoteAcceptedAt().toLocalDateTime());
        Assertions.assertFalse(flatText.contains("Customer signature"),
                "a signed invoice never shows the blank-signature caption: " + flatText);
        Assertions.assertTrue(flatText.contains(fixture.orderNumber()), "the PDF names the order: " + flatText);
        Assertions.assertEquals(1, countOccurrences(flatText, FOOTER_TEXT), "the footer renders exactly once");
        if (fixture.earlierUnsignedInvoicePdf() != null) {
            Assertions.assertEquals(countImages(fixture.earlierUnsignedInvoicePdf()) + 1, countImages(pdf),
                    "the inherited signature is embedded as exactly one extra image vs the earlier unsigned invoice");
        }
        assertNoQuoteEmailOrNotification();
        return new CommittedConversion(created, pdfPath, pdf, invoice);
    }

    /**
     * The appended version was delivered to {@code expectedRecipient}: exactly one send, recorded as sent (none
     * failed), of exactly it.
     */
    private void assertDeliveredOnce(ConvertibleOrder fixture, CommittedConversion committed, String expectedRecipient) {
        verify(invoiceEmailSender, times(1)).send(any());
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no failed attempt may be recorded");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "exactly one signed invoice email");
        assertSignedInvoiceEmail(fixture, committed, sent.get(0), expectedRecipient, "the delivered email");
    }

    /**
     * One signed-invoice email request (16G contract §6.1): to {@code expectedRecipient} - the saved customer
     * email as the committing transaction read it, trimmed, stated by the caller (never re-read after the
     * request, when a test may have moved it); the business subject and body naming the order; the appended
     * version's OWN PDF, byte-identical to its stored file (never an earlier version's), under
     * {@code invoice-{order}-v{N}.pdf}; the order id and version N.
     */
    private void assertSignedInvoiceEmail(ConvertibleOrder fixture, CommittedConversion committed,
                                          InvoiceEmailRequest email, String expectedRecipient, String label) {
        String businessName = businessName(fixture.tenant());
        Assertions.assertEquals(expectedRecipient, email.recipientEmail(),
                label + ": the recipient is the saved customer email the committing transaction read, trimmed");
        Assertions.assertEquals("Your invoice from " + businessName, email.subject(), label + ": subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + fixture.orderNumber() + " from " + businessName
                + " attached.\n\nThank you.", email.bodyText(), label + ": body");
        Assertions.assertArrayEquals(committed.pdf(), email.pdfBytes(),
                label + ": the attachment is the appended version's stored PDF, byte for byte");
        Assertions.assertEquals(invoicePdfName(fixture.orderNumber(), fixture.expectedVersion()), email.pdfFileName(),
                label + ": attachment name");
        Assertions.assertEquals(fixture.orderId(), email.orderId(), label + ": order id");
        Assertions.assertEquals(fixture.expectedVersion(), email.invoiceVersionNumber(), label + ": the appended version");
        if (fixture.earlierUnsignedInvoicePdf() != null) {
            Assertions.assertFalse(Arrays.equals(fixture.earlierUnsignedInvoicePdf(), email.pdfBytes()),
                    label + ": never an earlier version's PDF");
        }
    }

    /**
     * After a committed conversion the stamp never ran: no invoice marker write at all and no non-null mirror
     * write; the conversion's own mirror reset to null ran exactly once.
     */
    private void assertStampNeverRan(ConvertibleOrder fixture) {
        verify(invoiceRepository, never()).updateInvoiceLastEmailedAt(anyLong(), any());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), notNull());
        verify(invoiceRepository, times(1)).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), isNull());
    }

    /**
     * The afterCommit email move ran exactly once, at the commit boundary: it was reached once, inside the
     * conversion's transaction; it ran on a raw pooled connection in auto-commit that already saw the appended
     * version COMMITTED and the committed saved email still {@code emailAtCommit}; it moved exactly the order's
     * one customer row; and {@code movedEmail} is now the committed saved email.
     */
    private void assertSavedEmailMovedOnceRightAfterTheCommit(ConvertibleOrder fixture, SavedEmailMoves moves,
                                                             String emailAtCommit, String movedEmail, String label) {
        Assertions.assertEquals(List.of(), moves.failures(), label + ": the afterCommit email move itself must not fail");
        Assertions.assertEquals(List.of(true), moves.hookInsideConversionTransaction(),
                label + ": the hook was reached exactly once, at the conversion's mirror reset, inside its transaction");
        Assertions.assertEquals(List.of(true), moves.autoCommit(),
                label + ": the move ran on a raw pooled connection in auto-commit (it commits on its own)");
        Assertions.assertEquals(List.of(IntStream.rangeClosed(1, fixture.expectedVersion()).boxed().toList()),
                moves.committedVersionsAtMove(),
                label + ": afterCommit ran after the conversion committed (its own connection already sees the"
                        + " appended version)");
        Assertions.assertEquals(List.of(emailAtCommit), moves.committedEmailsBeforeMove(),
                label + ": the conversion committed the saved email untouched");
        Assertions.assertEquals(List.of(1), moves.rowsMoved(),
                label + ": afterCommit ran exactly once and moved exactly the order's one customer row");
        Assertions.assertEquals(movedEmail, savedCustomerEmail(fixture.orderId()),
                label + ": the committed saved email is now the moved value");
    }

    private void assertProtectedFilesIntact(ConvertibleOrder fixture) throws IOException {
        for (ProtectedFile file : fixture.protectedFiles()) {
            assertProtectedFileIntact(file);
        }
    }

    /** The stored_file row is unchanged and the file on disk still exists with identical bytes. */
    private void assertProtectedFileIntact(ProtectedFile file) throws IOException {
        Assertions.assertEquals(file.row(), storedFileRow(file.storedFileId()),
                file.label() + ": its stored_file row must remain, unchanged");
        Path path = physicalPath(file.storagePath());
        Assertions.assertTrue(Files.isRegularFile(path), file.label() + " must still exist on disk");
        Assertions.assertArrayEquals(file.bytes(), Files.readAllBytes(path), file.label() + " bytes must be identical");
    }

    /** Nothing emailed or notified: no invoice email attempted (16G PR 3), no quote email, no store notification. */
    private void assertNoEmailOrNotification() {
        assertNoInvoiceEmailAttempted("no email or notification");
        assertNoQuoteEmailOrNotification();
    }

    /**
     * No signed-invoice email was attempted: the sender spy saw no {@code send} since its invocations were last
     * cleared (at the end of every seeding, and by every reset), and its recorders hold nothing.
     */
    private void assertNoInvoiceEmailAttempted(String label) {
        verify(invoiceEmailSender, never()).send(any());
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), label + ": no invoice email may be sent");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), label + ": no invoice email may be attempted");
    }

    private void assertNoQuoteEmailOrNotification() {
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty(), "no quote email may be sent");
        Assertions.assertTrue(quoteEmailSender.failedEmails().isEmpty(), "no quote email may be attempted");
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(), "no store notification may be sent");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(),
                "no store notification may be attempted");
    }

    /** No WARN (or worse) from OrderInvoiceService during the conversion. */
    private static void assertNoServiceWarn(Conversion conversion) {
        List<ILoggingEvent> warnings = warnings(conversion);
        Assertions.assertTrue(warnings.isEmpty(), () -> "no WARN may be logged, but was " + describe(warnings));
    }

    /**
     * Exactly one WARN (or worse) from OrderInvoiceService during the conversion: level WARN, exactly
     * {@code expectedLine} (which names {@code order <id> invoice v<N>}), no throwable attached, and none of the
     * leak markers (recipient, email content, exception text).
     */
    private static void assertOnlyServiceWarn(Conversion conversion, String expectedLine, List<String> leakMarkers) {
        List<ILoggingEvent> warnings = warnings(conversion);
        Assertions.assertEquals(1, warnings.size(), () -> "exactly one WARN expected, but was " + describe(warnings));
        ILoggingEvent warning = warnings.get(0);
        Assertions.assertEquals(Level.WARN, warning.getLevel(), "logged at WARN");
        String line = warning.getFormattedMessage();
        Assertions.assertEquals(expectedLine, line, "the WARN line");
        Assertions.assertNull(warning.getThrowableProxy(), "the WARN carries no throwable: " + line);
        for (String leak : leakMarkers) {
            Assertions.assertFalse(line.contains(leak), "the WARN must not contain '" + leak + "': " + line);
        }
    }

    private static List<ILoggingEvent> warnings(Conversion conversion) {
        return conversion.serviceLog().stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
    }

    private static String describe(List<ILoggingEvent> events) {
        return events.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList().toString();
    }

    private static String skipWarnLine(ConvertibleOrder fixture) {
        return "Signed invoice email skipped for order " + fixture.orderId() + " invoice v" + fixture.expectedVersion()
                + ": no valid saved customer email (non-fatal; markers stay null)";
    }

    private static String failedWarnLine(ConvertibleOrder fixture) {
        return "Signed invoice email failed for order " + fixture.orderId() + " invoice v" + fixture.expectedVersion()
                + " (non-fatal; the committed invoice stands, markers stay null)";
    }

    private static String stampWarnLine(ConvertibleOrder fixture) {
        return "Email-success stamp failed for order " + fixture.orderId() + " invoice v" + fixture.expectedVersion()
                + " after delivery (non-fatal; markers stay null)";
    }

    /** What no post-commit WARN may contain: any address, the saved email, the email content, exception text. */
    private List<String> warnLeakMarkers(ConvertibleOrder fixture, String... extra) {
        List<String> markers = new ArrayList<>(List.of("@", "Your invoice from", "Please find your invoice",
                "Exception", savedCustomerEmail(fixture.orderId()).trim()));
        markers.addAll(List.of(extra));
        return markers;
    }

    private void assertFilesRemoved(List<String> storagePaths, String message) {
        for (String storagePath : storagePaths) {
            Assertions.assertFalse(Files.exists(physicalPath(storagePath)), message + ": " + storagePath);
        }
    }

    private static void assertAcceptedCaption(String pdfText, String acceptedName, LocalDateTime acceptedAt) {
        String text = noSpace(pdfText);
        String exact = noSpace("Accepted by " + acceptedName + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        Assertions.assertTrue(text.contains(exact),
                "the signed invoice PDF must carry the caption '" + exact + "' in: " + pdfText);
    }

    private static void assertStatus(MvcResult result, int expected, String label) {
        Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + body(result));
    }

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertNotNull(actual, label + ": expected " + expected + " but was null");
        String text = actual instanceof JsonNode node ? node.asText() : actual.toString();
        Assertions.assertFalse(text.isBlank(), label + ": expected " + expected + " but was blank");
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(text)),
                label + ": expected " + expected + " but was " + actual);
    }

    // ================================================================
    // Spy recorders / stubs (installed only after the fixture is committed)
    // ================================================================

    /**
     * Record every storage write made from now on (the path returned by the REAL store), optionally failing the
     * invoice PDF ("pdf") write before anything reaches the disk.
     */
    private StorageWrites recordStorageWrites(boolean failPdfWrite) {
        StorageWrites writes = new StorageWrites();
        doAnswer(invocation -> {
            String extension = invocation.getArgument(3);
            writes.attempted.add(extension);
            if (failPdfWrite && "pdf".equals(extension)) {
                throw new UncheckedIOException("Failed to store attachment file",
                        new IOException("injected disk failure while writing the invoice PDF"));
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

    /** The rows returned by every real invoice insert for this order from now on (inside the request transaction). */
    private List<InvoiceRow> recordInsertedInvoiceRows(long orderId) {
        List<InvoiceRow> rows = new CopyOnWriteArrayList<>();
        insertInvoiceFor(doAnswer(invocation -> {
            InvoiceRow row = (InvoiceRow) invocation.callRealMethod();
            rows.add(row);
            return row;
        }).when(invoiceRepository), orderId);
        return rows;
    }

    /** Every exception the REAL invoice insert throws for this order from now on (rethrown unchanged). */
    private List<Throwable> recordInsertInvoiceFailures(long orderId) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        insertInvoiceFor(doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (Throwable failure) {
                failures.add(failure);
                throw failure;
            }
        }).when(invoiceRepository), orderId);
        return failures;
    }

    /** {@code insertInvoice} (20 parameters) for this order with any other argument - stub or verify target. */
    private static InvoiceRow insertInvoiceFor(InvoiceRepository target, long orderId) {
        return target.insertInvoice(eq(orderId), anyInt(), any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Observe every post-commit send (then run the REAL send): whether a transaction is active on the request
     * thread at that moment, and the order's COMMITTED invoice versions, mirror and saved customer email as a
     * separate connection sees them.
     */
    private List<SendObservation> observeSends(ConvertibleOrder fixture) {
        List<SendObservation> observations = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            CommittedView view = committedViewOnOwnConnection(fixture.orderId());
            observations.add(new SendObservation(TransactionSynchronizationManager.isActualTransactionActive(),
                    view.invoiceVersions(), view.mirror(), view.customerEmail()));
            return invocation.callRealMethod();
        }).when(invoiceEmailSender).send(any());
        return observations;
    }

    /**
     * For this order's conversion mirror reset from now on ({@code updateSalesOrderLastEmailedAt(order, null)}:
     * the conversion's LAST in-transaction write, after its customer read; the email-success stamp's mirror write
     * carries a non-null value and is not matched): call the REAL write, then register a synchronization on the
     * conversion's transaction whose {@code afterCommit} commits {@code movedEmail} as the order's saved customer
     * email. {@code afterCommit} runs inside the TransactionTemplate's commit, so after the conversion is durable
     * and before the post-commit email (its send or its skip). The move uses a RAW pooled connection in
     * auto-commit, never {@code jdbcTemplate}: inside {@code afterCommit} that would still join the finished
     * transaction's connection, and nothing would commit it. The conversion's order lock is released by then and
     * it never writes order_customer, so the move cannot block (it is bounded anyway). On that connection the
     * hook also records the committed invoice versions and the committed saved email before the move. Failures
     * are recorded rather than thrown, so the test can report them.
     */
    private SavedEmailMoves moveSavedEmailRightAfterTheConversionCommits(ConvertibleOrder fixture, String movedEmail) {
        SavedEmailMoves moves = new SavedEmailMoves();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            moves.hookInsideConversionTransaction.add(TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try (Connection connection = dataSource.getConnection()) {
                        moves.autoCommit.add(connection.getAutoCommit());
                        moves.committedVersionsAtMove.add(invoiceVersionsOn(connection, fixture.orderId()));
                        moves.committedEmailsBeforeMove.add(customerEmailOn(connection, fixture.orderId()));
                        try (PreparedStatement move = connection.prepareStatement(
                                "UPDATE order_customer SET email = ? WHERE order_id = ?")) {
                            move.setQueryTimeout(HOOK_STATEMENT_TIMEOUT_SECONDS);
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

    /**
     * Make ANOTHER version current between the conversion's commit and its email-success stamp, the way a request
     * queued on the order (a payment) would: at the post-commit send, BEFORE the real send runs (no transaction
     * is active on the request thread there), commit a newer version - see {@link #commitNewerInvoiceVersion} -
     * then run the real send; the stamp follows it. Each send records what it committed; a failure is recorded,
     * then rethrown to the service (whose send failure handling then applies), so the test can report it.
     */
    private NewerVersionCommits commitNewerVersionAtTheSend(ConvertibleOrder fixture) {
        NewerVersionCommits commits = new NewerVersionCommits();
        doAnswer(invocation -> {
            try {
                commits.committed.add(commitNewerInvoiceVersion(fixture));
            } catch (Exception ex) {
                commits.failures.add(ex.toString());
                throw ex;
            }
            return invocation.callRealMethod();
        }).when(invoiceEmailSender).send(any());
        return commits;
    }

    /**
     * Commit, on a RAW pooled connection in auto-commit, a version v(N+1) that copies the appended row N (same
     * money, words, acceptance and source; created now; {@code last_emailed_at} NULL - a new version is
     * unemailed) with its OWN new stored_file row and file: a copy of N's stored PDF written straight to disk
     * (never through the spied FileStorageService, whose recorder counts only the conversion's own write) under
     * the FileStorageService naming in the order's storage dir - so the order sweep, which removes every
     * stored_file an invoice of the order references plus the whole order dir, cleans the row and the file up.
     * Then reset the sales_order mirror to that null, as a payment version does. Returns the committed invoice
     * and stored_file rows, read back through the JdbcTemplate in the shape {@link #committedState} reads.
     */
    private NewerVersion commitNewerInvoiceVersion(ConvertibleOrder fixture) throws SQLException, IOException {
        long orderId = fixture.orderId();
        int newerVersion = fixture.expectedVersion() + 1;
        Map<String, Object> appended = row(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, fixture.expectedVersion());
        Map<String, Object> appendedPdf = storedFileRow(toLong(appended.get("stored_file_id")));
        if (appendedPdf == null) {
            throw new IllegalStateException("the appended version's PDF stored_file row is missing");
        }
        byte[] pdfBytes = Files.readAllBytes(physicalPath((String) appendedPdf.get("storage_path")));
        String storagePath = orderStoragePrefix(fixture.tenant().businessId(), orderId) + UUID.randomUUID() + ".pdf";
        Files.write(physicalPath(storagePath), pdfBytes, StandardOpenOption.CREATE_NEW);

        long storedFileId;
        long invoiceId;
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("the newer version must commit on an auto-commit connection");
            }
            storedFileId = insertReturningId(connection,
                    "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                            + "VALUES (?, ?, 'application/pdf', ?) RETURNING stored_file_id",
                    invoicePdfName(fixture.orderNumber(), newerVersion), storagePath, (long) pdfBytes.length);
            invoiceId = insertReturningId(connection, """
                            INSERT INTO invoice
                                (order_id, version_number, invoice_date, due_date, details_of_sale_snapshot,
                                 sale_price_ex_gst, sale_price_inc_gst, total_paid, balance_due,
                                 stored_file_id, created_by_user_id,
                                 accepted_at, accepted_customer_name, accepted_signature_file_id, last_emailed_at,
                                 source_quote_version_id, terms_snapshot,
                                 customer_name_snapshot, customer_address_line1_snapshot, customer_address_line2_snapshot)
                            SELECT order_id, ?, invoice_date, due_date, details_of_sale_snapshot,
                                   sale_price_ex_gst, sale_price_inc_gst, total_paid, balance_due,
                                   ?, created_by_user_id,
                                   accepted_at, accepted_customer_name, accepted_signature_file_id, NULL,
                                   source_quote_version_id, terms_snapshot,
                                   customer_name_snapshot, customer_address_line1_snapshot, customer_address_line2_snapshot
                            FROM invoice WHERE invoice_id = ?
                            RETURNING invoice_id
                            """,
                    newerVersion, storedFileId, toLong(appended.get("invoice_id")));
            try (PreparedStatement mirror = connection.prepareStatement(
                    "UPDATE sales_order SET last_emailed_at = NULL WHERE order_id = ?")) {
                mirror.setQueryTimeout(HOOK_STATEMENT_TIMEOUT_SECONDS);
                mirror.setLong(1, orderId);
                if (mirror.executeUpdate() != 1) {
                    throw new IllegalStateException("the newer version's mirror reset must update the order row");
                }
            }
        }
        return new NewerVersion(row("SELECT * FROM invoice WHERE invoice_id = ?", invoiceId), storedFileRow(storedFileId));
    }

    /** One bounded {@code INSERT ... RETURNING <id>} on {@code connection}; the generated id. */
    private static long insertReturningId(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(HOOK_STATEMENT_TIMEOUT_SECONDS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("the insert returned no id: " + sql);
                }
                return resultSet.getLong(1);
            }
        }
    }

    /**
     * Observe - and with {@code failMirrorUpdate} fail - the email-success stamp's mirror update: the only
     * {@code updateSalesOrderLastEmailedAt} call for the order with a NON-NULL value (the conversion's own
     * reset passes null and runs for real, untouched). Inside the stamp transaction (JdbcTemplate joins the
     * thread's transaction connection) it records whether a transaction is active and the appended version's
     * and the mirror's last_emailed_at as that transaction sees them: after the stamp's invoice write, and
     * after its real mirror write unless {@code failMirrorUpdate}, which replaces that write with a
     * DataAccessException.
     */
    private List<StampObservation> observeStampMirrorUpdate(ConvertibleOrder fixture, boolean failMirrorUpdate) {
        List<StampObservation> observations = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            LocalDateTime mirrorValue = invocation.getArgument(1);
            if (mirrorValue == null) {
                return invocation.callRealMethod();
            }
            if (!failMirrorUpdate) {
                invocation.callRealMethod();
            }
            observations.add(new StampObservation(mirrorValue,
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    jdbcTemplate.queryForObject(
                            "SELECT last_emailed_at FROM invoice WHERE order_id = ? AND version_number = ?",
                            Timestamp.class, fixture.orderId(), fixture.expectedVersion()),
                    jdbcTemplate.queryForObject(
                            "SELECT last_emailed_at FROM sales_order WHERE order_id = ?",
                            Timestamp.class, fixture.orderId())));
            if (failMirrorUpdate) {
                throw new DataAccessResourceFailureException(
                        "injected failure on the email-success stamp's sales_order mirror update");
            }
            return null;
        }).when(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), any());
        return observations;
    }

    private void clearSpyInvocations() {
        Mockito.clearInvocations(invoicePdfGenerator, fileStorageService, invoiceRepository, invoiceEmailSender);
    }

    private void resetSenders() {
        invoiceEmailSender.reset();
        quoteEmailSender.reset();
        notificationSender.reset();
    }

    /**
     * A deferred constraint trigger that raises at COMMIT for any invoice row inserted for this order (the WHEN
     * clause is evaluated at INSERT time; the function runs at commit).
     */
    private void installCommitFailureTrigger(String trigger, long orderId) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION ntxqic_fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'ntxqic injected commit-time failure' USING ERRCODE = 'P0001';
                END;
                $$
                """);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger
                + " AFTER INSERT ON invoice"
                + " DEFERRABLE INITIALLY DEFERRED"
                + " FOR EACH ROW"
                + " WHEN (NEW.order_id = " + orderId + ")"
                + " EXECUTE FUNCTION " + TRIGGER_FUNCTION + "()");
    }

    /**
     * 16G PR 3: a deferred constraint trigger that raises at the COMMIT of the email-success stamp - any update
     * of one of this order's invoice rows whose last_emailed_at goes from null to a value (the WHEN clause is
     * evaluated at UPDATE time; the function runs at commit). The conversion only INSERTs invoice rows, so it
     * never fires there.
     */
    private void installStampCommitFailureTrigger(String trigger, long orderId) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION ntxqic_fail_stamp_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'ntxqic injected stamp commit-time failure' USING ERRCODE = 'P0001';
                END;
                $$
                """);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger
                + " AFTER UPDATE ON invoice"
                + " DEFERRABLE INITIALLY DEFERRED"
                + " FOR EACH ROW"
                + " WHEN (NEW.order_id = " + orderId
                + " AND OLD.last_emailed_at IS NULL AND NEW.last_emailed_at IS NOT NULL)"
                + " EXECUTE FUNCTION " + STAMP_TRIGGER_FUNCTION + "()");
    }

    // ================================================================
    // Committed-state probes (auto-commit JDBC - committed data only)
    // ================================================================

    /** Every row Path A, its stamp or a payment could touch for the order, plus the order's storage dir. */
    private CommittedState committedState(ConvertibleOrder fixture) {
        long orderId = fixture.orderId();
        long businessId = fixture.tenant().businessId();
        return new CommittedState(
                row("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                        orderStoragePrefix(businessId, orderId) + "%"),
                filesOnDisk(businessId, orderId),
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

    /**
     * The order's COMMITTED invoice versions, sales_order mirror and saved customer email, read through a NEW
     * pooled connection taken straight from the DataSource - never the connection bound to the calling thread's
     * transaction, if any - so it sees committed data only.
     */
    private CommittedView committedViewOnOwnConnection(long orderId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return new CommittedView(invoiceVersionsOn(connection, orderId), mirrorOn(connection, orderId),
                    customerEmailOn(connection, orderId));
        }
    }

    /** The order's invoice version numbers, ascending, as {@code connection} sees them. */
    private static List<Integer> invoiceVersionsOn(Connection connection, long orderId) throws SQLException {
        List<Integer> versions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version_number FROM invoice WHERE order_id = ? ORDER BY version_number")) {
            statement.setLong(1, orderId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    versions.add(resultSet.getInt(1));
                }
            }
        }
        return List.copyOf(versions);
    }

    /** The order's sales_order.last_emailed_at as {@code connection} sees it (null when unset or no row). */
    private static Timestamp mirrorOn(Connection connection, long orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT last_emailed_at FROM sales_order WHERE order_id = ?")) {
            statement.setLong(1, orderId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getTimestamp(1) : null;
            }
        }
    }

    /** The order's saved order_customer.email, raw, as {@code connection} sees it (a marker when no row). */
    private static String customerEmailOn(Connection connection, long orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT email FROM order_customer WHERE order_id = ?")) {
            statement.setLong(1, orderId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : "<no order_customer row>";
            }
        }
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

    /**
     * The saved order_customer.email, raw, as committed NOW - after a request it is the expected recipient only
     * when nothing moved it after that request's commit.
     */
    private String savedCustomerEmail(long orderId) {
        return jdbcTemplate.queryForObject("SELECT email FROM order_customer WHERE order_id = ?", String.class, orderId);
    }

    /** Copies of {@code customers} with their email replaced by {@code email} (the input rows are untouched). */
    private static List<Map<String, Object>> withSavedEmail(List<Map<String, Object>> customers, String email) {
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> customer : customers) {
            Map<String, Object> copy = new LinkedHashMap<>(customer);
            copy.put("email", email);
            changed.add(copy);
        }
        return changed;
    }

    private String businessName(Tenant tenant) {
        return jdbcTemplate.queryForObject("SELECT name FROM business WHERE business_id = ?", String.class,
                tenant.businessId());
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

    /** Sections 1-7: a convertible order of the V4 session identity, with the class's valid customer email. */
    private ConvertibleOrder seedConvertibleOrder(boolean withEarlierInvoiceAndPayment, boolean withNewerIssuedQuote)
            throws Exception {
        return seedConvertibleOrder(AUSSIE, CUSTOMER_EMAIL, withEarlierInvoiceAndPayment, withNewerIssuedQuote);
    }

    /**
     * A committed, invoice-ready order of {@code tenant} with a REAL accepted quote: self-seeded header (details
     * of sale, proposed lay date 2026-12-01, CONFIRMED), customer 'Quote' 'Tester' with {@code customerEmail}
     * (valid for the app rule: the quote is issued to it), BILLING + INSTALLATION addresses and one 100.00 ex /
     * 40.00 cost charge line; optionally an unsigned D.1 invoice v1 (110.00 inc) and a 20.00 payment (invoice
     * v2) through the real API; then the itemised 250.00 / 275.00 draft saved, issued by email and accepted
     * through the real public endpoint; optionally a changed non-itemised 330.00 draft issued afterwards as a
     * NEWER quote v2 (ISSUED, with an ACTIVE token) that Path A must never select or touch. The quote files and
     * every earlier invoice PDF are captured (row + bytes), a non-null mirror sentinel is committed, and the
     * senders and spy invocations (the invoice email sender's included) are cleared so later assertions see
     * only the request under test.
     */
    private ConvertibleOrder seedConvertibleOrder(Tenant tenant, String customerEmail,
                                                  boolean withEarlierInvoiceAndPayment, boolean withNewerIssuedQuote)
            throws Exception {
        SeededOrder order = insertCommittedOrder(tenant);
        long orderId = order.orderId();
        seedCustomer(orderId, customerEmail);
        seedAddresses(orderId);
        seedChargeLine(tenant, orderId);

        String activePayments = "0.00";
        if (withEarlierInvoiceAndPayment) {
            MvcResult created = mockMvc.perform(post(orderUrl(tenant, orderId) + "/invoices").session(session(tenant))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();
            assertStatus(created, 201, "setup: D.1 invoice create");
            Assertions.assertEquals(INVOICE_CREATED_MESSAGE, readJson(created).path("message").asText());
            MvcResult paid = recordPayment(tenant, orderId, EARLIER_PAYMENT);
            assertStatus(paid, 201, "setup: payment on the unsigned invoice");
            Assertions.assertEquals(PAYMENT_RECORDED_MESSAGE, readJson(paid).path("message").asText());
            activePayments = EARLIER_PAYMENT;

            List<Map<String, Object>> invoices = rows(
                    "SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId);
            Assertions.assertEquals(2, invoices.size(), "setup: D.1 v1 + payment v2");
            for (Map<String, Object> invoice : invoices) {
                Assertions.assertNull(invoice.get("accepted_at"), "setup: the earlier invoices are unsigned");
                Assertions.assertNull(invoice.get("source_quote_version_id"), "setup: the earlier invoices are Path B");
                assertMoney(EARLIER_INVOICE_INC_GST, invoice.get("sale_price_inc_gst"), "setup: earlier invoice inc");
            }
            assertMoney(EARLIER_PAYMENT, invoices.get(1).get("total_paid"), "setup: v2 total_paid");
        }

        saveItemisedTwoLineDraft(tenant, orderId);
        String token = issueAndExtractToken(tenant, orderId);
        acceptPublicly(token);

        Map<String, Object> version = row("SELECT * FROM quote_version WHERE order_id = ? AND version_number = 1", orderId);
        Assertions.assertEquals("ACCEPTED", version.get("status"), "setup: v1 accepted through the real public endpoint");
        Assertions.assertEquals(FROZEN_CUSTOMER_NAME, version.get("accepted_customer_name"), "setup: frozen V17 name");
        assertMoney(QUOTE_TOTAL_EX_GST, version.get("quote_total_ex_gst"), "setup: frozen quote ex total");
        assertMoney(QUOTE_TOTAL_INC_GST, version.get("quote_total_inc_gst"), "setup: frozen quote inc total");
        Assertions.assertEquals(DETAILS_OF_SALE, version.get("details_of_sale_snapshot"), "setup: frozen details");
        Timestamp quoteAcceptedAt = (Timestamp) version.get("accepted_at");
        Assertions.assertNotNull(quoteAcceptedAt, "setup: quote accepted_at");
        Long signatureFileId = toLong(version.get("accepted_signature_file_id"));
        Long issuedPdfFileId = toLong(version.get("issued_pdf_file_id"));
        Long signedPdfFileId = toLong(version.get("signed_pdf_file_id"));
        Assertions.assertNotNull(signatureFileId, "setup: the quote signature is stored");
        Assertions.assertNotNull(issuedPdfFileId, "setup: the issued quote PDF is stored");
        Assertions.assertNotNull(signedPdfFileId, "setup: the signed quote PDF is stored");
        Assertions.assertEquals("CONSUMED", jdbcTemplate.queryForObject(
                        "SELECT t.status FROM quote_token t JOIN quote_version v "
                                + "ON v.quote_version_id = t.quote_version_id WHERE v.order_id = ? AND v.version_number = 1",
                        String.class, orderId),
                "setup: the accepted link is consumed");

        List<ProtectedFile> files = new ArrayList<>();
        files.add(protectedFile("the inherited quote signature", signatureFileId));
        files.add(protectedFile("the issued quote PDF", issuedPdfFileId));
        files.add(protectedFile("the signed quote PDF", signedPdfFileId));
        Assertions.assertEquals("image/png", files.get(0).row().get("mime_type"), "setup: the signature is a PNG");

        Long newerIssuedVersionId = null;
        if (withNewerIssuedQuote) {
            saveNonItemisedDraft(tenant, orderId, "330.00");
            issueAndExtractToken(tenant, orderId);
            Map<String, Object> newer = row(
                    "SELECT * FROM quote_version WHERE order_id = ? AND version_number = 2", orderId);
            Assertions.assertEquals("ISSUED", newer.get("status"), "setup: the newer quote v2 is ISSUED");
            assertMoney("330.00", newer.get("quote_total_inc_gst"), "setup: the newer quote v2 inc total");
            Assertions.assertEquals("ACCEPTED", row("SELECT status FROM quote_version WHERE order_id = ? AND version_number = 1",
                    orderId).get("status"), "setup: issuing v2 leaves the accepted v1 ACCEPTED");
            newerIssuedVersionId = toLong(newer.get("quote_version_id"));
            Assertions.assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                            "SELECT status FROM quote_token WHERE quote_version_id = ?", String.class, newerIssuedVersionId),
                    "setup: the newer quote's link is ACTIVE");
            files.add(protectedFile("the newer issued quote v2 PDF", toLong(newer.get("issued_pdf_file_id"))));
        }
        byte[] earlierUnsignedInvoicePdf = null;
        for (Map<String, Object> invoice : rows(
                "SELECT version_number, stored_file_id FROM invoice WHERE order_id = ? ORDER BY version_number",
                orderId)) {
            ProtectedFile earlier = protectedFile("the earlier invoice v" + invoice.get("version_number") + " PDF",
                    toLong(invoice.get("stored_file_id")));
            files.add(earlier);
            earlierUnsignedInvoicePdf = earlier.bytes();
        }
        int expectedVersion = invoiceVersions(orderId).size() + 1;

        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE sales_order SET last_emailed_at = ? WHERE order_id = ?",
                SENTINEL_LAST_EMAILED_AT, orderId), "setup: mirror sentinel");

        resetSenders();
        clearSpyInvocations();
        return new ConvertibleOrder(tenant, orderId, order.orderNumber(), toLong(version.get("quote_version_id")),
                quoteAcceptedAt, signatureFileId, (String) version.get("terms_snapshot"),
                (String) version.get("details_of_sale_snapshot"), expectedVersion, activePayments,
                newerIssuedVersionId, List.copyOf(files), earlierUnsignedInvoicePdf);
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

    /** The fixture's inherited quote signature, one of its protected files. */
    private static ProtectedFile inheritedSignatureFile(ConvertibleOrder fixture) {
        return fixture.protectedFiles().stream()
                .filter(file -> file.storedFileId() == fixture.signatureFileId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("setup: the inherited signature must be a protected file"));
    }

    /**
     * Section 8's own COMMITTED tenant (auto-commit; found and swept by its slug): the business, one store, one
     * salesperson and the user_store_access row - the session identity of its requests. No V4 row is used.
     */
    private Tenant seedEmailTenant() {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active) VALUES (?, ?, TRUE) RETURNING business_id",
                Long.class, EMAIL_TENANT_NAME, EMAIL_TENANT_SLUG);
        Assertions.assertNotNull(businessId, "setup: the tenant business insert must return an id");
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Ntxqic Email Showroom', ?, '0290001234', 'showroom@ntxqic-email.example.com', "
                        + " '1 Showroom Way', 'Burwood', 'NSW', '2134') RETURNING store_id",
                Integer.class, businessId, EMAIL_TENANT_STORE_CODE);
        Assertions.assertNotNull(storeId, "setup: the tenant store insert must return an id");
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Nia', 'Mailer', ?, 'nia.mailer@ntxqic-email.example.com', 'not-a-login-hash') "
                        + "RETURNING user_id",
                Long.class, businessId, EMAIL_TENANT_SALESPERSON_CODE);
        Assertions.assertNotNull(userId, "setup: the tenant salesperson insert must return an id");
        Assertions.assertEquals(1, jdbcTemplate.update(
                "INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId), "setup: the salesperson's store access");
        return new Tenant(businessId, EMAIL_TENANT_SLUG, storeId, userId,
                EMAIL_TENANT_STORE_CODE + "." + EMAIL_TENANT_SALESPERSON_CODE + ".");
    }

    /** The OrderCreateRepository allocation: per-business advisory lock, MAX + 1, insert - one commit. */
    private SeededOrder insertCommittedOrder(Tenant tenant) {
        SeededOrder seeded = new TransactionTemplate(transactionManager).execute(txStatus -> {
            jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", (rs, rowNum) -> Boolean.TRUE,
                    tenant.businessId());
            Integer next = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(order_sequence_number), 0) + 1 FROM sales_order WHERE business_id = ?",
                    Integer.class, tenant.businessId());
            int sequence = next == null ? 1 : next;
            String orderNumber = tenant.orderNumberPrefix() + String.format("%05d", sequence % 100_000);
            Long orderId = jdbcTemplate.queryForObject(
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
                    Long.class, tenant.businessId(), tenant.storeId(), tenant.userId(), sequence, orderNumber,
                    DETAILS_OF_SALE, SEEDED_UPDATED_AT, SEEDED_UPDATED_AT);
            Assertions.assertNotNull(orderId, "order insert must return an id");
            return new SeededOrder(orderId, orderNumber);
        });
        Assertions.assertNotNull(seeded, "order seed transaction must return the order");
        return seeded;
    }

    private void seedCustomer(long orderId, String email) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quote', 'Tester', ?, ?)",
                orderId, email, CUSTOMER_MOBILE);
    }

    /** Commit a new saved customer email (DB-valid: chk_order_customer_email_format is never relaxed here). */
    private void setSavedCustomerEmail(ConvertibleOrder fixture, String email) {
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE order_customer SET email = ? WHERE order_id = ?",
                email, fixture.orderId()), "setup: the saved customer email moves on after the acceptance");
        Assertions.assertEquals(email, savedCustomerEmail(fixture.orderId()), "setup: the saved email is stored as given");
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

    /** One tagged store_charge of the tenant's store (swept) + one order_charge_line: 100.00 ex, 40.00 cost. */
    private void seedChargeLine(Tenant tenant, long orderId) {
        String code = CHARGE_CODE_PREFIX + orderId;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Conversion commit test charge', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, tenant.storeId(), code);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Conversion commit test charge', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, code);
    }

    /** Itemised draft through the API: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex / 275.00 inc. */
    private void saveItemisedTwoLineDraft(Tenant tenant, long orderId) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(tenant, orderId) + "/quote/draft").session(session(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andReturn();
        assertStatus(result, 200, "setup: quote draft save");
    }

    /** Non-itemised draft through the API: only the final inc-GST total, no lines. */
    private void saveNonItemisedDraft(Tenant tenant, long orderId, String finalTotalIncGst) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(tenant, orderId) + "/quote/draft").session(session(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemised\": false, \"final_total_inc_gst\": " + finalTotalIncGst + ", \"lines\": []}"))
                .andReturn();
        assertStatus(result, 200, "setup: non-itemised quote draft save");
    }

    /** Issue the current draft through the protected send-email and take the plaintext token from the recorded email. */
    private String issueAndExtractToken(Tenant tenant, long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(tenant, orderId) + "/quote/send-email").session(session(tenant))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertStatus(result, 201, "setup: quote send-email (issue)");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "setup: exactly the issue email is recorded");
        return extractToken(sent.get(sent.size() - 1).bodyText());
    }

    /** The REAL public accept: token-only (NO session), exactly one PNG signature part. */
    private void acceptPublicly(String token) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/public/quotes/" + token + "/accept")
                        .file(new MockMultipartFile("signature", "signature.png", "image/png", SIGNATURE_PNG)))
                .andReturn();
        assertStatus(result, 201, "setup: public quote accept");
        JsonNode root = readJson(result);
        Assertions.assertEquals("INACTIVE", root.path("data").path("state").asText(), "setup: accepted link state");
        Assertions.assertEquals(QUOTE_ACCEPTED_MESSAGE, root.path("message").asText());
    }

    // ================================================================
    // Requests / URLs / token / PDF helpers
    // ================================================================

    /**
     * POST .../quote/create-invoice (Path A) for the fixture's session, with the mandatory 16G PR 3 body
     * {@code {"quote_version_id": N}}, N = the fixture's latest ACCEPTED quote version (so it is never stale).
     */
    private MvcResult createInvoiceFromQuote(ConvertibleOrder fixture) throws Exception {
        return mockMvc.perform(post(orderUrl(fixture.tenant(), fixture.orderId()) + "/quote/create-invoice")
                        .session(session(fixture.tenant()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quote_version_id\": " + fixture.quoteVersionId() + "}"))
                .andReturn();
    }

    /**
     * Path A for the fixture with a Logback ListAppender on the OrderInvoiceService logger (detached in
     * finally), and the request's wall-clock window.
     */
    private Conversion convert(ConvertibleOrder fixture) throws Exception {
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(OrderInvoiceService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            LocalDateTime startedAt = LocalDateTime.now();
            MvcResult result = createInvoiceFromQuote(fixture);
            LocalDateTime finishedAt = LocalDateTime.now();
            return new Conversion(result, List.copyOf(appender.list), startedAt, finishedAt);
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private MvcResult recordPayment(Tenant tenant, long orderId, String amount) throws Exception {
        return mockMvc.perform(post(orderUrl(tenant, orderId) + "/payments").session(session(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\":\"EFTPOS\",\"amount\":" + amount + ",\"payment_reference\":null}"))
                .andReturn();
    }

    private static MockHttpSession session(Tenant tenant) {
        MockHttpSession session = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        session.setAttribute("user_id", Long.valueOf(tenant.userId()));
        session.setAttribute("business_id", Long.valueOf(tenant.businessId()));
        session.setAttribute("store_id", Integer.valueOf(tenant.storeId()));
        return session;
    }

    private static String orderUrl(Tenant tenant, long orderId) {
        return "/api/v1/" + tenant.slug() + "/orders/" + orderId;
    }

    /** The plaintext token from the link-only quote email; the link must appear exactly once. */
    private static String extractToken(String body) {
        Matcher matcher = PUBLIC_LINK_PATTERN.matcher(body);
        Assertions.assertTrue(matcher.find(), "the quote email must carry the public /q/{token} link: " + body);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "the public link must appear exactly once in the email body");
        return token;
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

    private static String invoicePdfName(String orderNumber, int versionNumber) {
        return "invoice-" + orderNumber + "-v" + versionNumber + ".pdf";
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

    private static String orderStoragePrefix(long businessId, long orderId) {
        return "/uploads/" + businessId + "/orders/" + orderId + "/";
    }

    private Path businessStorageDir(long businessId) {
        return storageBase().resolve("uploads").resolve(Long.toString(businessId));
    }

    private Path orderStorageDir(long businessId, long orderId) {
        return businessStorageDir(businessId).resolve("orders").resolve(Long.toString(orderId));
    }

    private Path orderStorageDir(ConvertibleOrder fixture) {
        return orderStorageDir(fixture.tenant().businessId(), fixture.orderId());
    }

    /** File names currently in the order's storage dir (empty when the dir does not exist). */
    private Set<String> filesOnDisk(long businessId, long orderId) {
        Path dir = orderStorageDir(businessId, orderId);
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
                this::sweepTestOrders,
                this::sweepTaggedCharges,
                this::sweepEmailTenant);
        assertSweptClean();
    }

    /**
     * The sweep really removed everything this class commits: tagged orders and charges, the section 8
     * tenant, both trigger families and both trigger functions.
     */
    private void assertSweptClean() {
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM sales_order WHERE business_id = ? AND order_number LIKE ?",
                BUSINESS_AUSSIE, ORDER_SWEEP_PATTERN), "no tagged order may survive the sweep");
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM store_charge WHERE store_id = ? AND code LIKE ?",
                STORE_SYD_CBD, CHARGE_CODE_PREFIX + "%"), "no tagged store_charge may survive the sweep");
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM business WHERE slug = ?", EMAIL_TENANT_SLUG),
                "the section 8 tenant may not survive the sweep");
        for (String prefix : List.of(TRIGGER_PREFIX, STAMP_TRIGGER_PREFIX)) {
            Assertions.assertEquals(0L, count(
                            "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice'::regclass AND tgname LIKE ?",
                            likePrefix(prefix)),
                    "no commit-failure trigger " + prefix + "* may survive the sweep");
        }
        for (String function : List.of(TRIGGER_FUNCTION, STAMP_TRIGGER_FUNCTION)) {
            Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM pg_proc WHERE proname = ?", function),
                    "the trigger function " + function + " must be dropped");
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

    /** Drop every leftover commit-failure trigger of this class on invoice (any order id, both families), then their functions. */
    private void dropCommitFailureTriggers() {
        for (String prefix : List.of(TRIGGER_PREFIX, STAMP_TRIGGER_PREFIX)) {
            List<String> triggers = jdbcTemplate.queryForList(
                    "SELECT tgname FROM pg_trigger WHERE tgrelid = 'invoice'::regclass AND tgname LIKE ?",
                    String.class, likePrefix(prefix));
            for (String trigger : triggers) {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON invoice");
            }
        }
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + TRIGGER_FUNCTION + "()");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + STAMP_TRIGGER_FUNCTION + "()");
    }

    /** A LIKE pattern matching names that start with {@code prefix} literally ('_' escaped). */
    private static String likePrefix(String prefix) {
        return prefix.replace("_", "\\_") + "%";
    }

    /** Delete every committed row of every NTXQIC.* order of business 1 (FK-safe), its stored_file rows and its files. */
    private void sweepTestOrders() {
        List<Long> orderIds = jdbcTemplate.queryForList(
                "SELECT order_id FROM sales_order WHERE business_id = ? AND order_number LIKE ?",
                Long.class, BUSINESS_AUSSIE, ORDER_SWEEP_PATTERN);
        if (orderIds.isEmpty()) {
            return;
        }
        // The carry-forward test points one tagged order's invoice at ANOTHER tagged order's quote version; detach
        // every such cross-order reference first, or that version's delete would hit the V19 FK.
        jdbcTemplate.update(
                "UPDATE invoice i SET source_quote_version_id = NULL "
                        + "WHERE i.order_id IN (SELECT order_id FROM sales_order WHERE business_id = ? AND order_number LIKE ?) "
                        + "AND i.source_quote_version_id IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM quote_version v "
                        + "WHERE v.quote_version_id = i.source_quote_version_id AND v.order_id = i.order_id)",
                BUSINESS_AUSSIE, ORDER_SWEEP_PATTERN);
        for (Long orderId : orderIds) {
            deleteOrderAndFiles(BUSINESS_AUSSIE, orderId);
        }
    }

    /**
     * The section 8 tenant, found by its slug: every order of it (rows + files, FK-safe), then its own rows -
     * charges and products of its stores, store access, salespeople, stores, quick descriptions (their FK to
     * business has no ON DELETE) and the business - and finally its storage dir.
     */
    private void sweepEmailTenant() {
        List<Long> businessIds = jdbcTemplate.queryForList(
                "SELECT business_id FROM business WHERE slug = ?", Long.class, EMAIL_TENANT_SLUG);
        for (Long businessId : businessIds) {
            for (Long orderId : jdbcTemplate.queryForList(
                    "SELECT order_id FROM sales_order WHERE business_id = ? ORDER BY order_id", Long.class, businessId)) {
                deleteOrderAndFiles(businessId, orderId);
            }
            new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
                jdbcTemplate.update("DELETE FROM store_charge WHERE store_id IN "
                        + "(SELECT store_id FROM store WHERE business_id = ?)", businessId);
                jdbcTemplate.update("DELETE FROM store_product WHERE store_id IN "
                        + "(SELECT store_id FROM store WHERE business_id = ?)", businessId);
                jdbcTemplate.update("DELETE FROM user_store_access WHERE business_id = ?", businessId);
                jdbcTemplate.update("DELETE FROM app_user WHERE business_id = ?", businessId);
                jdbcTemplate.update("DELETE FROM store WHERE business_id = ?", businessId);
                jdbcTemplate.update("DELETE FROM business_quick_description WHERE business_id = ?", businessId);
                jdbcTemplate.update("DELETE FROM business WHERE business_id = ?", businessId);
            });
            deleteDirectoryQuietly(businessStorageDir(businessId));
        }
    }

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
                orderId, orderId, orderId, orderId, orderId, orderId, "/uploads/%/orders/" + orderId + "/%");
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
                    jdbcTemplate.update("DELETE FROM stored_file WHERE stored_file_id = ?",
                            file.get("stored_file_id"));
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

    /** The tagged store_charge rows of store 1 (their order_charge_line rows are swept with the orders). */
    private void sweepTaggedCharges() {
        jdbcTemplate.update(
                "DELETE FROM store_charge sc WHERE sc.store_id = ? AND sc.code LIKE ? "
                        + "AND NOT EXISTS (SELECT 1 FROM order_charge_line l WHERE l.charge_id = sc.charge_id)",
                STORE_SYD_CBD, CHARGE_CODE_PREFIX + "%");
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

    /** The session identity of a fixture's requests (business, slug, store, salesperson) and its order-number prefix. */
    private record Tenant(long businessId, String slug, int storeId, long userId, String orderNumberPrefix) {
    }

    private record SeededOrder(long orderId, String orderNumber) {
    }

    /**
     * A committed order with a REAL accepted quote, ready for Path A: its tenant, the selected quote version, its
     * frozen values, the next invoice version, the active payments, the newer ISSUED quote version when one was
     * issued after the acceptance, every file no failure may touch, and the latest earlier (unsigned) invoice PDF
     * when one exists.
     */
    private record ConvertibleOrder(Tenant tenant,
                                    long orderId,
                                    String orderNumber,
                                    long quoteVersionId,
                                    Timestamp quoteAcceptedAt,
                                    long signatureFileId,
                                    String quoteTermsSnapshot,
                                    String quoteDetailsOfSale,
                                    int expectedVersion,
                                    String activePayments,
                                    Long newerIssuedVersionId,
                                    List<ProtectedFile> protectedFiles,
                                    byte[] earlierUnsignedInvoicePdf) {
    }

    /** One pre-existing file: its stored_file row and its bytes on disk at seed time. */
    private record ProtectedFile(String label, long storedFileId, Map<String, Object> row, String storagePath,
                                 byte[] bytes) {
    }

    /** Every committed row Path A or a payment could touch, plus the order's storage dir (equality = unchanged). */
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

    /** One Path A request: its result, what OrderInvoiceService logged meanwhile, and its wall-clock window. */
    private record Conversion(MvcResult result, List<ILoggingEvent> serviceLog,
                              LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** A committed conversion: the appended invoice row, its stored PDF (path + bytes) and the response invoice. */
    private record CommittedConversion(Map<String, Object> created, String pdfPath, byte[] pdf,
                                       JsonNode responseInvoice) {
    }

    /** The order's committed invoice versions, mirror and saved customer email, as a separate connection saw them. */
    private record CommittedView(List<Integer> invoiceVersions, Timestamp mirror, String customerEmail) {
    }

    /** At a post-commit send: whether a transaction was active, and what was committed by then. */
    private record SendObservation(boolean transactionActive, List<Integer> committedVersions,
                                   Timestamp committedMirror, String committedCustomerEmail) {
    }

    /**
     * A newer invoice version a test committed between the conversion's commit and its email stamp: its invoice
     * row and its own stored_file row, read back right after that commit (the shape committedState reads).
     */
    private record NewerVersion(Map<String, Object> invoice, Map<String, Object> storedFile) {
    }

    /**
     * What the afterCommit saved-email move saw and did, one entry per run (failures recorded, never thrown, so
     * the test can report them).
     */
    private static final class SavedEmailMoves {
        private final List<Boolean> hookInsideConversionTransaction = new CopyOnWriteArrayList<>();
        private final List<Boolean> autoCommit = new CopyOnWriteArrayList<>();
        private final List<List<Integer>> committedVersionsAtMove = new CopyOnWriteArrayList<>();
        private final List<String> committedEmailsBeforeMove = new CopyOnWriteArrayList<>();
        private final List<Integer> rowsMoved = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();

        /** One entry per time the hook was reached: true when inside an active, synchronized transaction. */
        List<Boolean> hookInsideConversionTransaction() {
            return List.copyOf(hookInsideConversionTransaction);
        }

        List<Boolean> autoCommit() {
            return List.copyOf(autoCommit);
        }

        /** The committed invoice versions, read by afterCommit on its own connection before the move. */
        List<List<Integer>> committedVersionsAtMove() {
            return List.copyOf(committedVersionsAtMove);
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

    /** What the at-send hook committed, one entry per send (failures recorded, then rethrown to the service). */
    private static final class NewerVersionCommits {
        private final List<NewerVersion> committed = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();

        List<NewerVersion> committed() {
            return List.copyOf(committed);
        }

        List<String> failures() {
            return List.copyOf(failures);
        }
    }

    /**
     * At the stamp's mirror update: the value passed to it, whether a transaction was active, and the appended
     * version's and the mirror's last_emailed_at as the stamp transaction saw them.
     */
    private record StampObservation(LocalDateTime mirrorValue, boolean transactionActive,
                                    Timestamp invoiceInTransaction, Timestamp mirrorInTransaction) {
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
