package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoicePdfGenerator;
import com.flooring.salesportal.order.InvoiceRepository;
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
import org.mockito.Mockito;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * proven OUTSIDE any test transaction.
 *
 * <p><b>Why a separate, non-transactional class.</b> Inside a {@code @Transactional} test the Path A service
 * method (itself {@code @Transactional}) and the payment's {@code TransactionTemplate} JOIN the test transaction,
 * so nothing ever really commits: "a failure commits nothing", "a commit-time failure deletes the written PDF
 * through the afterCompletion hook" and "the inherited signature and every earlier PDF survive" cannot be
 * observed there. This class has NO {@code @Transactional} anywhere: every seed statement auto-commits, every
 * MockMvc call commits for real, and every assertion reads COMMITTED state through pooled auto-commit
 * connections plus the files on disk.
 *
 * <p><b>Fault injection</b> (no production hooks):
 * <ul>
 *   <li>mid-transaction - {@link MockitoSpyBean} spies (real behaviour unless stubbed) on
 *       {@link InvoicePdfGenerator} (the render), {@link FileStorageService} (the invoice PDF write) and
 *       {@link InvoiceRepository} (the invoice insert, and the sales_order {@code last_emailed_at} mirror reset,
 *       which is the LAST write). Stubs are installed only after the fixture is committed, so the seeding
 *       flows (D.1, payment, quote issue and accept) always run for real;</li>
 *   <li>at COMMIT - a {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger {@code AFTER INSERT ON invoice},
 *       scoped to the test order, created in auto-commit right before the request and dropped in
 *       {@code finally} and by the sweep;</li>
 *   <li>at the insert boundary - a committed cross-order {@code invoice.source_quote_version_id} (the
 *       single-column V19 FK allows it), which the payment carry-forward must refuse through the
 *       {@link InvoiceRepository#insertInvoice} source-ownership check;</li>
 *   <li>before the render - a committed defect in the accepted quote's inherited signature: its file moved
 *       off disk (into a holding dir INSIDE the order's storage dir, so the sweep removes it too), or a NULL
 *       {@code accepted_customer_name} / {@code accepted_signature_file_id} on the ACCEPTED
 *       {@code quote_version} (no CHECK forbids either); each defect is undone before the retry.</li>
 * </ul>
 * The bean overrides give this class its own Spring context (by design). Files go to the shared test storage
 * dir {@code target/test-storage/quote-acceptance}; physical file = base dir + {@code storage_path}.
 *
 * <p><b>Seed / cleanup.</b> Only V4 business 1 / store 1 / user 1 ({@code aussie-floors-group}) are reused as
 * the session identity. Every order is self-seeded and tagged {@code NTXQIC.ZZ9.nnnnn}, its sequence allocated
 * the {@code OrderCreateRepository} way (per-business advisory lock + MAX + 1), and its charge is tagged
 * {@code NTXQIC{orderId}}. Every row of every tagged order (FK-safe order: tokens, invoices before quote
 * versions, drafts, payments, the other children, the order), its stored files (rows + disk + the order dir),
 * the tagged charges and any leftover test trigger are swept in BOTH {@code @BeforeEach} and
 * {@code @AfterEach}. The singleton recording senders and every spy are reset in both as well. Single-threaded.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class QuoteInvoiceConversionCommitIntegrationTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    // Committed seed rows are tagged so the sweep finds this class's orders and charges - and only them.
    private static final String ORDER_NUMBER_PREFIX = "NTXQIC.ZZ9.";
    private static final String ORDER_SWEEP_PATTERN = "NTXQIC.%";
    private static final String CHARGE_CODE_PREFIX = "NTXQIC";

    // Commit-time failure injection (class-specific names: no collision with any other test class).
    private static final String TRIGGER_FUNCTION = "ntxqic_fail_at_commit";
    private static final String TRIGGER_PREFIX = "ntxqic_fail_commit_";

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
    // A NON-NULL committed sales_order.last_emailed_at: Path A's only order write resets it to null, so a
    // failed conversion must leave exactly this value behind.
    private static final Timestamp SENTINEL_LAST_EMAILED_AT = Timestamp.valueOf("2026-02-03 04:05:06");

    private static final String CREATED_FROM_QUOTE_MESSAGE = "Invoice created from accepted quote.";
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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender notificationSender;

    // Spies call the REAL methods unless a test stubs them (doX().when(spy) form only). The InvoiceRepository
    // field is the @Repository CGLIB proxy around the spy; Spring's SpringMockResolver lets doX().when(..),
    // verify(..), clearInvocations(..) and reset(..) resolve through it.
    @MockitoSpyBean
    private InvoicePdfGenerator invoicePdfGenerator;

    @MockitoSpyBean
    private FileStorageService fileStorageService;

    @MockitoSpyBean
    private InvoiceRepository invoiceRepository;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Defensive: the sweep deletes files recursively - never let it run against a real storage dir.
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
     * inherited signature, every quote PDF and both earlier invoice PDFs are byte-identical; nothing is emailed.
     * A retry without the fault creates v3 from the accepted v1.
     */
    @Test
    void renderFailure_returns500_commitsNothing_writesNoFile_inheritedAndEarlierFilesIntact_retryCreatesV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture.orderId());
        StorageWrites writes = recordStorageWrites(false);
        doThrow(new UncheckedIOException("Failed to generate invoice PDF",
                new IOException("injected invoice PDF render failure")))
                .when(invoicePdfGenerator).render(any());
        MvcResult result;
        try {
            result = createInvoiceFromQuote(fixture.orderId());
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
     * 500; nothing committed; no file written; the inherited and earlier files intact; nothing emailed; a retry
     * creates v3.
     */
    @Test
    void pdfStorageFailure_returns500_commitsNothing_writesNoFile_inheritedAndEarlierFilesIntact_retryCreatesV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture.orderId());
        StorageWrites writes = recordStorageWrites(true);
        MvcResult result;
        try {
            result = createInvoiceFromQuote(fixture.orderId());
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
     * inherited and earlier files are intact; a retry creates v3.
     */
    @Test
    void mirrorResetFailureAfterInsert_returns500_insertedRowsNotCommitted_writtenPdfDeleted_retryCreatesV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture.orderId());
        StorageWrites writes = recordStorageWrites(false);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        List<InvoiceRow> insertedInvoices = recordInsertedInvoiceRows(fixture.orderId());
        doThrow(new DataAccessResourceFailureException("injected failure on the sales_order last_emailed_at mirror reset"))
                .when(invoiceRepository).updateSalesOrderLastEmailedAt(eq(fixture.orderId()), any());
        MvcResult result;
        try {
            result = createInvoiceFromQuote(fixture.orderId());
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
     * the inherited and earlier files intact; a retry creates v3.
     */
    @Test
    void invoiceInsertFailure_returns500_storedFileRowNotCommitted_writtenPdfDeleted_retryCreatesV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture.orderId());
        StorageWrites writes = recordStorageWrites(false);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        insertInvoiceFor(doThrow(new DataIntegrityViolationException("injected failure on the Path A invoice insert"))
                .when(invoiceRepository), fixture.orderId());
        MvcResult result;
        try {
            result = createInvoiceFromQuote(fixture.orderId());
            // The insert was really reached, for the next version and the selected quote version.
            verify(invoiceRepository).insertInvoice(eq(fixture.orderId()), eq(fixture.expectedVersion()),
                    any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong(),
                    any(), any(), any(), any(), eq(fixture.quoteVersionId()), any());
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
     * response DTO) and the failure happens AT COMMIT. 500 with the generic body (no database detail); nothing
     * is committed; the written PDF is deleted by the afterCompletion hook ALONE (the method body returned
     * normally, so the in-method catch never ran: exactly one delete of that path); the inherited and earlier
     * files intact; a retry after the trigger is dropped creates v3.
     */
    @Test
    void commitTimeFailure_deferredInvoiceTrigger_returns500WithoutDbDetail_allWritesRanButNothingCommitted_hookDeletesPdf()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture.orderId());
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
            result = createInvoiceFromQuote(fixture.orderId());
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

        assertNothingCommitted(fixture, before);
        assertRetryCreatesQuoteInvoice(fixture, before);
    }

    // ================================================================
    // 6. Payment carry-forward of a cross-order quote source (item 8 rollback)
    // ================================================================

    /**
     * Order A holds a REAL Path A invoice v1; order B has its own accepted quote. A's current invoice is then
     * pointed (committed, through the single-column V19 FK) at B's accepted version. Recording a payment on A
     * carries that source forward and the source-ownership check at the insert boundary refuses it
     * (IllegalStateException) - 500 with the generic body. The refused v2 insert carried total_paid 10.00 /
     * balance_due 265.00, values only an in-transaction sum that already sees the new payment row can produce,
     * so the payment row really was written inside the transaction that rolled back. Committed: no
     * payment_transaction row, no invoice v2, no new stored_file row (none references the regenerated PDF), the
     * regenerated PDF that the payment wrote is gone from disk (deleted by BOTH cleanups: the in-method catch and
     * the afterCompletion rollback hook), order B untouched, nothing emailed. With A's own source restored, the
     * same payment commits v2 carrying A's source forward.
     */
    @Test
    void paymentCarryForward_crossOrderQuoteSource_returns500_noPaymentNoVersionNoFileRow_regeneratedPdfDeleted()
            throws Exception {
        // Order A: a real Path A invoice v1 (source = A's own accepted quote version).
        ConvertibleOrder orderA = seedConvertibleOrder(false, false);
        MvcResult converted = createInvoiceFromQuote(orderA.orderId());
        assertStatus(converted, 201, "setup: Path A on order A");
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

        CommittedState beforeA = committedState(orderA.orderId());
        CommittedState beforeB = committedState(orderB.orderId());
        Assertions.assertTrue(beforeA.payments().isEmpty(), "setup: order A has no payment yet");
        Assertions.assertEquals(1, beforeA.invoices().size(), "setup: order A has exactly its Path A v1");
        resetSenders();
        clearSpyInvocations();
        StorageWrites writes = recordStorageWrites(false);
        List<Long> insertedStoredFileIds = recordInsertedStoredFileIds();
        List<Throwable> insertFailures = recordInsertInvoiceFailures(orderA.orderId());

        MvcResult result;
        try {
            result = recordPayment(orderA.orderId(), "10.00");
            // The carried (cross-order) source reached the insert boundary for v2. Its total_paid (10.00) and
            // balance_due (275.00 - 10.00 = 265.00) come from sumAmountByOrderId run AFTER the payment insert:
            // order A had no payment before, so only the uncommitted 10.00 row of this same (rolled-back)
            // transaction can make the sum 10.00.
            verify(invoiceRepository).insertInvoice(eq(orderA.orderId()), eq(2),
                    any(), any(), any(), any(), any(),
                    argThat(totalPaid -> totalPaid != null && totalPaid.compareTo(new BigDecimal("10.00")) == 0),
                    argThat(balanceDue -> balanceDue != null && balanceDue.compareTo(new BigDecimal("265.00")) == 0),
                    anyLong(), anyLong(),
                    any(), any(), any(), any(), eq(orderB.quoteVersionId()), any());
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

        CommittedState afterA = committedState(orderA.orderId());
        assertSameCommittedState(beforeA, afterA, "order A after the refused payment");
        Assertions.assertTrue(afterA.payments().isEmpty(), "no payment_transaction row may be committed");
        Assertions.assertEquals(List.of(1), invoiceVersions(orderA.orderId()), "no invoice v2 may be committed");
        Assertions.assertEquals(beforeA.storedFiles().size(), afterA.storedFiles().size(),
                "no stored_file row may be committed for order A");
        assertSameCommittedState(beforeB, committedState(orderB.orderId()), "order B (the foreign source) after the refused payment");
        assertProtectedFilesIntact(orderA);
        assertProtectedFilesIntact(orderB);
        assertProtectedFileIntact(pathAPdf);
        assertNoEmailOrNotification();

        // Retry with A's own source restored: the same payment commits v2 carrying A's source forward.
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET source_quote_version_id = ? WHERE invoice_id = ?",
                orderA.quoteVersionId(), pathAInvoiceId));
        Mockito.reset(invoicePdfGenerator, fileStorageService, invoiceRepository);
        StorageWrites retryWrites = recordStorageWrites(false);
        MvcResult retried = recordPayment(orderA.orderId(), "10.00");
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
     * written. 500 with the generic body; the request stopped before the persist step and the committed state,
     * rows and the order's files on disk alike, is exactly as it was right before the request. With the file
     * moved back, every protected file is intact, the committed state equals the seed state, and a retry
     * creates v3 with the inherited signature embedded.
     */
    @Test
    void inheritedSignatureFileMissingOnDisk_returns500BeforeRender_writesNothing_restoredRetryCreatesSignedV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture.orderId());
        ProtectedFile signature = inheritedSignatureFile(fixture);
        Path signatureFile = physicalPath(signature.storagePath());
        Path orderDir = orderStorageDir(fixture.orderId());
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
     * and the committed state is exactly as it was right before the request. With the frozen name restored, the
     * committed state equals the seed state and a retry creates v3 with the inherited signature embedded.
     */
    @Test
    void acceptedQuoteCustomerNameNull_returns500BeforeRender_writesNothing_restoredRetryCreatesSignedV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture.orderId());
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
     * anything is rendered or written; no other signature is looked up or read in its place; the committed
     * state is exactly as it was right before the request. With the reference restored, the committed state
     * equals the seed state and a retry creates v3 inheriting that SAME signature stored_file, embedded.
     */
    @Test
    void acceptedQuoteSignatureFileIdNull_returns500BeforeRender_noSubstituteSignature_writesNothing_restoredRetryCreatesSignedV3()
            throws Exception {
        ConvertibleOrder fixture = seedConvertibleOrder(true, true);
        CommittedState before = committedState(fixture.orderId());
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

    /** A failed conversion left the committed state EXACTLY as before and sent nothing. */
    private void assertNothingCommitted(ConvertibleOrder fixture, CommittedState before) throws IOException {
        CommittedState after = committedState(fixture.orderId());
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
     * stored_file / invoice insert and no mirror reset; nothing was emailed; and the committed state, every row
     * and the order's files on disk, is exactly the state right before the request.
     */
    private void assertInheritedSignatureFailureWritesNothing(ConvertibleOrder fixture, String label) throws Exception {
        CommittedState defective = committedState(fixture.orderId());
        StorageWrites writes = recordStorageWrites(false);
        MvcResult result = createInvoiceFromQuote(fixture.orderId());
        assertGeneric500(result, label);

        verify(invoiceRepository).findCurrentByOrderId(fixture.orderId());
        verify(invoicePdfGenerator, never()).render(any());
        Assertions.assertEquals(List.of(), writes.attemptedExtensions(), label + ": no file write may even be attempted");
        Assertions.assertEquals(List.of(), writes.writtenPaths(), label + ": no file may be written");
        verify(fileStorageService, never()).deleteQuietly(any());
        verify(invoiceRepository, never()).insertStoredFile(anyString(), anyString(), anyString(), anyLong());
        insertInvoiceFor(verify(invoiceRepository, never()), fixture.orderId());
        verify(invoiceRepository, never()).updateSalesOrderLastEmailedAt(anyLong(), any());

        assertSameCommittedState(defective, committedState(fixture.orderId()), label);
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
     * Remove every injected fault (spies back to real behaviour, no trigger left) and convert again: 201 with
     * the expected next version, and the ONLY committed changes are one appended invoice row (the signed quote
     * snapshot, inheriting the quote's signature row), one new PDF stored_file row + file, and the mirror reset
     * to null. Everything else - earlier invoices, quote versions / lines / tokens / draft, payments, customer,
     * addresses, lines, every other sales_order column - is identical, and nothing is emailed.
     */
    private void assertRetryCreatesQuoteInvoice(ConvertibleOrder fixture, CommittedState before) throws Exception {
        Mockito.reset(invoicePdfGenerator, fileStorageService, invoiceRepository);
        dropCommitFailureTriggers();
        StorageWrites writes = recordStorageWrites(false);
        LocalDate firstDay = LocalDate.now();
        MvcResult result = createInvoiceFromQuote(fixture.orderId());
        LocalDate lastDay = LocalDate.now();
        assertStatus(result, 201, "retry after the fault was removed");

        BigDecimal expectedBalance = new BigDecimal(QUOTE_TOTAL_INC_GST).subtract(new BigDecimal(fixture.activePayments()));
        JsonNode root = readJson(result);
        Assertions.assertEquals(CREATED_FROM_QUOTE_MESSAGE, root.path("message").asText());
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
        Assertions.assertTrue(invoice.has("last_emailed_at") && invoice.get("last_emailed_at").isNull(),
                "the new version is unemailed");
        for (String internal : List.of("source_quote_version_id", "stored_file_id", "accepted_signature_file_id",
                "terms_snapshot", "storage_path")) {
            Assertions.assertFalse(invoice.has(internal), "internal field must never be serialized: " + internal);
        }

        Assertions.assertEquals(List.of("pdf"), writes.attemptedExtensions(), "the retry writes only the invoice PDF");
        Assertions.assertEquals(1, writes.writtenPaths().size());
        String pdfPath = writes.writtenPaths().get(0);

        CommittedState after = committedState(fixture.orderId());
        int previousInvoices = before.invoices().size();
        Assertions.assertEquals(previousInvoices + 1, after.invoices().size(), "exactly one invoice row appended");
        Assertions.assertEquals(before.invoices(), after.invoices().subList(0, previousInvoices),
                "every earlier invoice row is untouched");
        Map<String, Object> created = after.invoices().get(previousInvoices);
        Assertions.assertEquals(fixture.expectedVersion(), ((Number) created.get("version_number")).intValue());
        LocalDate invoiceDate = ((Date) created.get("invoice_date")).toLocalDate();
        Assertions.assertFalse(invoiceDate.isBefore(firstDay) || invoiceDate.isAfter(lastDay),
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
        Assertions.assertNull(created.get("last_emailed_at"), "the new version is unemailed");
        Assertions.assertEquals(USER_LIAM, toLong(created.get("created_by_user_id")), "created by the session user");
        Assertions.assertEquals(fixture.quoteVersionId(), toLong(created.get("source_quote_version_id")),
                "source_quote_version_id = the accepted quote version");
        Assertions.assertTrue(Objects.equals(fixture.quoteTermsSnapshot(), created.get("terms_snapshot")),
                "terms_snapshot = the quote's frozen terms verbatim (including null)");

        // Exactly one new stored_file row (the invoice PDF) and one new file on disk; no new signature row/file.
        int previousFiles = before.storedFiles().size();
        Assertions.assertEquals(previousFiles + 1, after.storedFiles().size(), "exactly one stored_file row added");
        Assertions.assertEquals(before.storedFiles(), after.storedFiles().subList(0, previousFiles),
                "every earlier stored_file row (inherited signature, quote PDFs, earlier invoices) is untouched");
        Map<String, Object> pdfRow = after.storedFiles().get(previousFiles);
        Assertions.assertEquals(toLong(created.get("stored_file_id")), toLong(pdfRow.get("stored_file_id")),
                "the new stored_file row is the new invoice's PDF");
        Assertions.assertEquals(invoicePdfName(fixture.orderNumber(), fixture.expectedVersion()), pdfRow.get("file_name"));
        Assertions.assertEquals("application/pdf", pdfRow.get("mime_type"));
        Assertions.assertEquals(pdfPath, pdfRow.get("storage_path"), "the committed row is the file this retry wrote");
        Set<String> expectedFiles = new TreeSet<>(before.filesOnDisk());
        expectedFiles.add(fileName(pdfPath));
        Assertions.assertEquals(expectedFiles, after.filesOnDisk(), "exactly the new invoice PDF was added on disk");

        // The only order write is the mirror reset to the new version's null last_emailed_at.
        Map<String, Object> expectedOrder = new LinkedHashMap<>(before.salesOrder());
        expectedOrder.put("last_emailed_at", null);
        Assertions.assertEquals(expectedOrder, after.salesOrder(),
                "Path A writes only sales_order.last_emailed_at (reset to null)");
        Assertions.assertEquals(before.quoteVersions(), after.quoteVersions(), "quote versions untouched");
        Assertions.assertEquals(before.quoteVersionLines(), after.quoteVersionLines(), "quote version lines untouched");
        Assertions.assertEquals(before.quoteTokens(), after.quoteTokens(), "quote tokens untouched");
        Assertions.assertEquals(before.quoteDraft(), after.quoteDraft(), "quote draft untouched");
        Assertions.assertEquals(before.quoteDraftLines(), after.quoteDraftLines(), "quote draft lines untouched");
        Assertions.assertEquals(before.payments(), after.payments(), "payments untouched");
        Assertions.assertEquals(before.customers(), after.customers(), "customer untouched");
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
        assertNoEmailOrNotification();
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

    private void assertNoEmailOrNotification() {
        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "no invoice email may be sent");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no invoice email may be attempted");
        Assertions.assertTrue(quoteEmailSender.sentEmails().isEmpty(), "no quote email may be sent");
        Assertions.assertTrue(quoteEmailSender.failedEmails().isEmpty(), "no quote email may be attempted");
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(), "no store notification may be sent");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(),
                "no store notification may be attempted");
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

    /** {@code insertInvoice} (17 parameters) for this order with any other argument - stub or verify target. */
    private static InvoiceRow insertInvoiceFor(InvoiceRepository target, long orderId) {
        return target.insertInvoice(eq(orderId), anyInt(), any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    private void clearSpyInvocations() {
        Mockito.clearInvocations(invoicePdfGenerator, fileStorageService, invoiceRepository);
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

    // ================================================================
    // Committed-state probes (auto-commit JDBC - committed data only)
    // ================================================================

    /** Every row Path A or a payment could touch for the order, plus the order's storage dir. */
    private CommittedState committedState(long orderId) {
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

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    // ================================================================
    // Seeding (committed for real: auto-commit JDBC + real API calls)
    // ================================================================

    /**
     * A committed, invoice-ready order with a REAL accepted quote: self-seeded header (details of sale,
     * proposed lay date 2026-12-01, CONFIRMED), customer 'Quote' 'Tester' with a valid email, BILLING +
     * INSTALLATION addresses and one 100.00 ex / 40.00 cost charge line; optionally an unsigned D.1 invoice v1
     * (110.00 inc) and a 20.00 payment (invoice v2) through the real API; then the itemised 250.00 / 275.00 draft
     * saved, issued by email and accepted through the real public endpoint; optionally a changed non-itemised
     * 330.00 draft issued afterwards as a NEWER quote v2 (ISSUED, with an ACTIVE token) that Path A must never
     * select or touch. The quote files and every earlier invoice PDF are captured (row + bytes), a non-null
     * mirror sentinel is committed, and the senders and spy invocations are cleared so later assertions see only
     * the request under test.
     */
    private ConvertibleOrder seedConvertibleOrder(boolean withEarlierInvoiceAndPayment, boolean withNewerIssuedQuote)
            throws Exception {
        SeededOrder order = insertCommittedOrder();
        long orderId = order.orderId();
        seedCustomer(orderId);
        seedAddresses(orderId);
        seedChargeLine(orderId);

        String activePayments = "0.00";
        if (withEarlierInvoiceAndPayment) {
            MvcResult created = mockMvc.perform(post(orderUrl(orderId) + "/invoices").session(liamStore1Session())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();
            assertStatus(created, 201, "setup: D.1 invoice create");
            Assertions.assertEquals(INVOICE_CREATED_MESSAGE, readJson(created).path("message").asText());
            MvcResult paid = recordPayment(orderId, EARLIER_PAYMENT);
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

        saveItemisedTwoLineDraft(orderId);
        String token = issueAndExtractToken(orderId);
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
            saveNonItemisedDraft(orderId, "330.00");
            issueAndExtractToken(orderId);
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
        return new ConvertibleOrder(orderId, order.orderNumber(), toLong(version.get("quote_version_id")),
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

    /** The OrderCreateRepository allocation: per-business advisory lock, MAX + 1, insert - one commit. */
    private SeededOrder insertCommittedOrder() {
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
                            + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                            + " proposed_lay_date, lay_date_status, "
                            + " price_adjustment_inc_gst, sale_price_ex_gst, total_cost, gp, gp_percent, "
                            + " created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 1, 2026, ?, "
                            + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status, "
                            + " NULL, 100.00, 40.00, 60.00, 60.00, ?, ?) "
                            + "RETURNING order_id",
                    Long.class, BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, sequence, orderNumber, DETAILS_OF_SALE,
                    SEEDED_UPDATED_AT, SEEDED_UPDATED_AT);
            Assertions.assertNotNull(orderId, "order insert must return an id");
            return new SeededOrder(orderId, orderNumber);
        });
        Assertions.assertNotNull(seeded, "order seed transaction must return the order");
        return seeded;
    }

    private void seedCustomer(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quote', 'Tester', ?, ?)",
                orderId, CUSTOMER_EMAIL, CUSTOMER_MOBILE);
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

    /** One tagged store_charge (swept) + one order_charge_line: 100.00 ex, 40.00 cost. */
    private void seedChargeLine(long orderId) {
        String code = CHARGE_CODE_PREFIX + orderId;
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Conversion commit test charge', 100.00, 40.00) "
                        + "RETURNING charge_id",
                Long.class, STORE_SYD_CBD, code);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Conversion commit test charge', 100.00, 40.00, 1, 100.00, 100.00, 40.00)",
                orderId, chargeId, code);
    }

    /** Itemised draft through the API: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex / 275.00 inc. */
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

    /** Non-itemised draft through the API: only the final inc-GST total, no lines. */
    private void saveNonItemisedDraft(long orderId, String finalTotalIncGst) throws Exception {
        MvcResult result = mockMvc.perform(put(orderUrl(orderId) + "/quote/draft").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemised\": false, \"final_total_inc_gst\": " + finalTotalIncGst + ", \"lines\": []}"))
                .andReturn();
        assertStatus(result, 200, "setup: non-itemised quote draft save");
    }

    /** Issue the current draft through the protected send-email and take the plaintext token from the recorded email. */
    private String issueAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        MvcResult result = mockMvc.perform(post(orderUrl(orderId) + "/quote/send-email").session(liamStore1Session())
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

    /** POST .../quote/create-invoice with {@code {}} (Path A) for Liam's store-1 session. */
    private MvcResult createInvoiceFromQuote(long orderId) throws Exception {
        return mockMvc.perform(post(orderUrl(orderId) + "/quote/create-invoice").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
    }

    private MvcResult recordPayment(long orderId, String amount) throws Exception {
        return mockMvc.perform(post(orderUrl(orderId) + "/payments").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\":\"EFTPOS\",\"amount\":" + amount + ",\"payment_reference\":null}"))
                .andReturn();
    }

    private static MockHttpSession liamStore1Session() {
        MockHttpSession session = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        session.setAttribute("user_id", USER_LIAM);
        session.setAttribute("business_id", BUSINESS_AUSSIE);
        session.setAttribute("store_id", STORE_SYD_CBD);
        return session;
    }

    private static String orderUrl(long orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId;
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
                () -> Mockito.reset(invoicePdfGenerator, fileStorageService, invoiceRepository),
                invoiceEmailSender::reset,
                quoteEmailSender::reset,
                notificationSender::reset,
                this::dropCommitFailureTriggers,
                this::sweepTestOrders,
                this::sweepTaggedCharges);
        assertSweptClean();
    }

    /** The sweep really removed everything this class commits: tagged orders and charges, triggers, function. */
    private void assertSweptClean() {
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM sales_order WHERE business_id = ? AND order_number LIKE ?",
                BUSINESS_AUSSIE, ORDER_SWEEP_PATTERN), "no tagged order may survive the sweep");
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM store_charge WHERE store_id = ? AND code LIKE ?",
                STORE_SYD_CBD, CHARGE_CODE_PREFIX + "%"), "no tagged store_charge may survive the sweep");
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'invoice'::regclass AND tgname LIKE ?",
                TRIGGER_PREFIX.replace("_", "\\_") + "%"), "no commit-failure trigger may survive the sweep");
        Assertions.assertEquals(0L, count("SELECT COUNT(*) FROM pg_proc WHERE proname = ?", TRIGGER_FUNCTION),
                "the commit-failure trigger function must be dropped");
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

    /** Delete every committed row of every NTXQIC.* order (FK-safe), its stored_file rows and its files. */
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
            deleteDirectoryQuietly(orderStorageDir(orderId));
        }
    }

    /** The tagged store_charge rows (their order_charge_line rows are swept with the orders). */
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

    private record SeededOrder(long orderId, String orderNumber) {
    }

    /**
     * A committed order with a REAL accepted quote, ready for Path A: the selected quote version, its frozen
     * values, the next invoice version, the active payments, the newer ISSUED quote version when one was issued
     * after the acceptance, every file no failure may touch, and the latest earlier (unsigned) invoice PDF when
     * one exists.
     */
    private record ConvertibleOrder(long orderId,
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
