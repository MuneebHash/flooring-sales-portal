package com.flooring.salesportal.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Phase 12 Chunk 4 invoices: Branch A — D.1 POST /orders/{orderId}/invoices
 * (create the current invoice); Branch B — D.3/D.4 read the current invoice + PDF; Branch C — D.2 POST
 * /orders/{orderId}/invoices/rewrite (regenerate the current invoice from live order state).
 *
 * <p>Mirrors {@code OrderAttachmentControllerTest} / {@code OrderChargeLineControllerTest}:
 * {@code @SpringBootTest @Transactional}, MockMvc, a {@code MockHttpSession} carrying the seeded
 * Liam / business 1 / store 1 identity, and {@code JdbcTemplate} for data tweaks + persistence
 * assertions (rolled back with the test transaction). Invoice PDFs are written to a JUnit
 * {@link TempDir} (via {@code app.storage.base-dir} bound through {@link DynamicPropertySource}), so
 * nothing is written into the repo. The {@code FileStorageService.store} write happens during the
 * request, so the on-disk PDF is visible within the open test transaction; the service's
 * rollback-cleanup hook deletes it when the transaction rolls back, leaving no orphan.
 *
 * <p>Seed (V4 + V6): order 1 ({@code SYD-CBD.LC1.00001}, SOFT/ACCEPTED, store 1, business 1) is fully
 * populated — customer (James Wilson), install + billing addresses, product line (line_total 360.00)
 * + charge line (line_total 480.00) → live subtotal 840.00 / inc-GST 924.00, details_of_sale set,
 * proposed_lay_date 2026-05-01, lay_date_status CONFIRMED, one payment (500.00 EFTPOS), and ONE
 * seeded invoice (id 1, v1). Tests that need a "fully populated, no invoice" target call
 * {@link #clearSeededInvoice()} (rolled back with the test). Order 2 = header-only (LEAD), the
 * all-preconditions-fail target. Order 5 = store 2 (cross-store), order 9 = business 2
 * (cross-business), order 99999 = nonexistent.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderInvoiceControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final long ORDER_FULL = 1L;            // fully populated + has a seeded invoice
    private static final long ORDER_EMPTY = 2L;           // header-only LEAD (all preconditions fail)
    private static final long ORDER_OTHER_STORE = 5L;     // store 2, business 1 (cross-store)
    private static final long ORDER_OTHER_BUSINESS = 9L;  // business 2 (cross-business)
    private static final long ORDER_DOES_NOT_EXIST = 99_999L;

    private static final String ORDER_FULL_NUMBER = "SYD-CBD.LC1.00001";
    private static final String EXPECTED_PDF_NAME = "invoice-" + ORDER_FULL_NUMBER + "-v1.pdf";
    private static final String EXPECTED_PDF_PATH = "/api/v1/" + SLUG_AUSSIE + "/orders/1/invoices/current/file";

    @TempDir
    static Path tempStorageDir;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.base-dir", () -> tempStorageDir.toString());
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private MockHttpSession liamStore1Session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        s.setAttribute("store_id", STORE_SYD_CBD);
        return s;
    }

    private MockHttpSession liamSessionNoStore() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        return s;
    }

    private static String invoicesUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/invoices";
    }

    private static String invoicesUrl(String slug, Object orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/invoices";
    }

    private void laidOrder(long orderId) {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
    }

    /** Remove order 1's seeded invoice (+ its PDF stored_file) so it has all 9 preconditions but NO invoice. */
    private void clearSeededInvoice() {
        Long storedFileId = jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE order_id = ? ORDER BY version_number DESC LIMIT 1",
                Long.class, ORDER_FULL);
        jdbcTemplate.update("DELETE FROM invoice WHERE order_id = ?", ORDER_FULL);
        if (storedFileId != null) {
            jdbcTemplate.update("DELETE FROM stored_file WHERE stored_file_id = ?", storedFileId);
        }
    }

    private int countInvoices(long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice WHERE order_id = ?", Integer.class, orderId);
    }

    private long latestInvoiceId(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT invoice_id FROM invoice WHERE order_id = ? ORDER BY version_number DESC LIMIT 1",
                Long.class, orderId);
    }

    private BigDecimal invoiceMoney(String column, long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM invoice WHERE invoice_id = ?", BigDecimal.class, invoiceId);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        Assertions.assertNotNull(actual);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private boolean diskFileExists(String storagePath) {
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        return Files.exists(tempStorageDir.resolve(relative));
    }

    // ---- Phase 13 helpers (email gate / acceptance fields / dashboard mirror) ----

    private void setCustomerEmail(long orderId, String email) {
        jdbcTemplate.update("UPDATE order_customer SET email = ? WHERE order_id = ?", email, orderId);
    }

    /**
     * The schema forbids a blank/NULL customer email (chk_order_customer_email_format + NOT NULL), so
     * those gate branches are unreachable through normal data. Drop both rules (transactional DDL,
     * rolled back with the test) to exercise the backend email gate's required-vs-invalid split.
     */
    private void relaxCustomerEmailDbConstraints() {
        jdbcTemplate.execute("ALTER TABLE order_customer DROP CONSTRAINT chk_order_customer_email_format");
        jdbcTemplate.execute("ALTER TABLE order_customer ALTER COLUMN email DROP NOT NULL");
    }

    /** Minimal valid customer row for an order that has none (order 2), so only non-customer gates fire. */
    private void seedMinimalCustomer(long orderId, String email) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Test', 'Customer', ?, '0400000000')",
                orderId, email);
    }

    private Timestamp orderLastEmailedAt(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId);
    }

    private int maxInvoiceVersion(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) FROM invoice WHERE order_id = ?", Integer.class, orderId);
    }

    private void setOrderLastEmailedAt(long orderId, String timestamp) {
        jdbcTemplate.update(
                "UPDATE sales_order SET last_emailed_at = CAST(? AS timestamp) WHERE order_id = ?",
                timestamp, orderId);
    }

    // ================================================================
    // D.1 happy path
    // ================================================================

    @Test
    void create_validOrder_returns201_withInvoiceDetail() throws Exception {
        clearSeededInvoice();

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Invoice created."))
                .andExpect(jsonPath("$.data.invoice.invoice_id").isNumber())
                .andExpect(jsonPath("$.data.invoice.order_id").value(1))
                .andExpect(jsonPath("$.data.invoice.version_number").value(1))
                .andExpect(jsonPath("$.data.invoice.invoice_date").exists())
                .andExpect(jsonPath("$.data.invoice.due_date").value("2026-04-29")) // 2026-05-01 minus 2 days
                .andExpect(jsonPath("$.data.invoice.details_of_sale_snapshot",
                        startsWith("Supply and install plush carpet")))
                .andExpect(jsonPath("$.data.invoice.sale_price_ex_gst").value(840.00))
                .andExpect(jsonPath("$.data.invoice.sale_price_inc_gst").value(924.00))
                .andExpect(jsonPath("$.data.invoice.total_paid").value(500.00))
                .andExpect(jsonPath("$.data.invoice.balance_due").value(424.00))
                .andExpect(jsonPath("$.data.invoice.created_by_user_id").value(1))
                .andExpect(jsonPath("$.data.invoice.created_at").exists())
                .andExpect(jsonPath("$.data.invoice.pdf_download_path").value(EXPECTED_PDF_PATH));
    }

    @Test
    void create_emptyBody_alsoAccepted() throws Exception {
        // No body at all (the contract allows {} or an empty body).
        clearSeededInvoice();
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.version_number").value(1));
    }

    @Test
    void create_persistsInvoiceRowWithFrozenSnapshot() throws Exception {
        clearSeededInvoice();
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        Assertions.assertEquals(1, countInvoices(ORDER_FULL));
        long invoiceId = latestInvoiceId(ORDER_FULL);
        assertMoney("840.00", invoiceMoney("sale_price_ex_gst", invoiceId));
        assertMoney("924.00", invoiceMoney("sale_price_inc_gst", invoiceId));
        assertMoney("500.00", invoiceMoney("total_paid", invoiceId));
        assertMoney("424.00", invoiceMoney("balance_due", invoiceId));

        Integer version = jdbcTemplate.queryForObject(
                "SELECT version_number FROM invoice WHERE invoice_id = ?", Integer.class, invoiceId);
        Assertions.assertEquals(1, version);
    }

    @Test
    void create_writesStoredFilePdfRowAndDiskFile() throws Exception {
        clearSeededInvoice();
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        long invoiceId = latestInvoiceId(ORDER_FULL);
        Long storedFileId = jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE invoice_id = ?", Long.class, invoiceId);
        Assertions.assertNotNull(storedFileId);

        String fileName = jdbcTemplate.queryForObject(
                "SELECT file_name FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
        String mimeType = jdbcTemplate.queryForObject(
                "SELECT mime_type FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
        long fileSize = jdbcTemplate.queryForObject(
                "SELECT file_size FROM stored_file WHERE stored_file_id = ?", Long.class, storedFileId);
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);

        Assertions.assertEquals(EXPECTED_PDF_NAME, fileName);
        Assertions.assertEquals("application/pdf", mimeType);
        Assertions.assertTrue(fileSize > 0, "file_size must be positive");
        Assertions.assertTrue(storagePath.startsWith("/uploads/1/orders/1/"), () -> "storage_path=" + storagePath);
        Assertions.assertTrue(storagePath.endsWith(".pdf"), () -> "storage_path=" + storagePath);

        // The PDF is written during the request, so it is on disk within the open transaction.
        Assertions.assertTrue(diskFileExists(storagePath), "invoice PDF should exist on disk");
        byte[] bytes = Files.readAllBytes(tempStorageDir.resolve(storagePath.substring(1)));
        Assertions.assertEquals((int) fileSize, bytes.length, "stored file_size matches the bytes on disk");
        Assertions.assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII), "valid PDF header");
    }

    @Test
    void create_response_doesNotExposeStoredFileIdOrStoragePath() throws Exception {
        clearSeededInvoice();
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.stored_file_id").doesNotExist())
                .andExpect(jsonPath("$.data.invoice.storage_path").doesNotExist())
                .andExpect(jsonPath("$.data.invoice.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.invoice.store_id").doesNotExist());
    }

    @Test
    void create_usesLiveFinancialSummaryIncludingPriceAdjustment() throws Exception {
        // Live summary, not the persisted header: a price adjustment makes final_sale_price_inc_gst
        // 1000.00 (924.00 + 76.00), ex-GST = 1000.00 / 1.10 = 909.09. The snapshot must reflect it.
        clearSeededInvoice();
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = 76.00 WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.sale_price_inc_gst").value(1000.00))
                .andExpect(jsonPath("$.data.invoice.sale_price_ex_gst").value(909.09))
                .andExpect(jsonPath("$.data.invoice.balance_due").value(500.00));
    }

    @Test
    void create_laidOrderWithNoInvoice_returns201() throws Exception {
        // D.1 is allowed on a LAID order when no invoice exists yet (only manual Rewrite is blocked).
        clearSeededInvoice();
        laidOrder(ORDER_FULL);

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.version_number").value(1));
        Assertions.assertEquals(1, countInvoices(ORDER_FULL));
    }

    @Test
    void create_snapshotIsFrozenAgainstLaterOrderEdits() throws Exception {
        // After creation, the invoice's details_of_sale_snapshot must NOT track later live edits to the
        // order (it is a frozen snapshot — the basis of the "no unsent live edits" rule).
        clearSeededInvoice();
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        long invoiceId = latestInvoiceId(ORDER_FULL);
        String snapshotBefore = jdbcTemplate.queryForObject(
                "SELECT details_of_sale_snapshot FROM invoice WHERE invoice_id = ?", String.class, invoiceId);
        Assertions.assertTrue(snapshotBefore.startsWith("Supply and install plush carpet"));

        // Mutate the live order; the already-created invoice snapshot must be unchanged.
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'CHANGED AFTER INVOICE' WHERE order_id = ?",
                ORDER_FULL);
        String snapshotAfter = jdbcTemplate.queryForObject(
                "SELECT details_of_sale_snapshot FROM invoice WHERE invoice_id = ?", String.class, invoiceId);
        Assertions.assertEquals(snapshotBefore, snapshotAfter, "invoice snapshot must be frozen");
    }

    @Test
    void create_transactionRollback_removesPdfFileAndRollsBackRows() throws Exception {
        // The PDF is written to disk during the request; on a rollback the service's afterCompletion
        // cleanup hook must delete it so no orphan file is left (mirrors the attachment suite).
        clearSeededInvoice();
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        long invoiceId = latestInvoiceId(ORDER_FULL);
        Long storedFileId = jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE invoice_id = ?", Long.class, invoiceId);
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
        // Within the still-open transaction both the row and the file exist.
        Assertions.assertTrue(diskFileExists(storagePath), "PDF written by the create");

        // Roll the transaction back — the cleanup hook (afterCompletion, status != committed) deletes it.
        TestTransaction.flagForRollback();
        TestTransaction.end();

        // The created invoice + stored_file are rolled back; the seeded invoice is restored (count 1).
        Assertions.assertEquals(1, countInvoices(ORDER_FULL), "created invoice rolled back, seed restored");
        Assertions.assertFalse(diskFileExists(storagePath), "PDF removed on rollback (no orphan)");
    }

    // ================================================================
    // D.1 business rules
    // ================================================================

    @Test
    void create_invoiceAlreadyExists_returns409() throws Exception {
        // Order 1 already has the seeded invoice (not cleared here).
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVOICE_ALREADY_EXISTS"));
        // The seeded invoice is untouched (still exactly one).
        Assertions.assertEquals(1, countInvoices(ORDER_FULL));
    }

    @Test
    void create_laidOrderWithExistingInvoice_returns409_notLocked() throws Exception {
        // No LAID gate on create: a LAID order that already has an invoice is 409 INVOICE_ALREADY_EXISTS,
        // NOT 422 ORDER_LOCKED. Keep the seeded invoice and flip the order to LAID.
        laidOrder(ORDER_FULL);
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVOICE_ALREADY_EXISTS"));
        Assertions.assertEquals(1, countInvoices(ORDER_FULL));
    }

    @Test
    void create_emptyOrderWithValidEmail_returns422_withSevenPreconditionDetails() throws Exception {
        // Phase 13: the email gate runs FIRST, so the all-9-fail case (no customer row) now returns
        // CUSTOMER_EMAIL_REQUIRED (asserted separately). With a valid customer row in place (names +
        // email pass), the remaining 7 preconditions all fail and are reported together.
        seedMinimalCustomer(ORDER_EMPTY, "test.customer@email.com");

        mockMvc.perform(post(invoicesUrl(ORDER_EMPTY)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_PRECONDITIONS_NOT_MET"))
                .andExpect(jsonPath("$.error.details", hasSize(7)))
                .andExpect(jsonPath("$.error.details[*].field", hasItems(
                        "installation_address", "billing_address", "lines",
                        "details_of_sale", "proposed_lay_date", "lay_date_status", "final_sale_price_inc_gst")));
        Assertions.assertEquals(0, countInvoices(ORDER_EMPTY));
    }

    @Test
    void create_preconditionDetailCarriesSection() throws Exception {
        // With a valid customer (email gate + name preconditions pass), every remaining failing
        // precondition still carries its section label.
        seedMinimalCustomer(ORDER_EMPTY, "test.customer@email.com");

        mockMvc.perform(post(invoicesUrl(ORDER_EMPTY)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details[*].section", hasItems(
                        "address", "lines", "details", "financial")));
    }

    // ================================================================
    // D.1 body / id validation (gate ordering)
    // ================================================================

    @Test
    void create_nonEmptyBody_returns400_validationFailed() throws Exception {
        // Body validation (400) precedes the already-exists business rule (409): order 1 still has an
        // invoice, but a non-empty body is rejected first. Any field (incl. due_date) → VALIDATION_FAILED.
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"due_date\":\"2026-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("due_date"));
    }

    @Test
    void create_malformedJson_returns400_malformedJson() throws Exception {
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void create_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(post(invoicesUrl("abc")).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ================================================================
    // D.1 scoping / auth (no existence leak)
    // ================================================================

    @Test
    void create_noSession_returns401() throws Exception {
        mockMvc.perform(post(invoicesUrl(ORDER_FULL))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void create_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void create_crossStoreOrder_returns404_orderNotFound() throws Exception {
        mockMvc.perform(post(invoicesUrl(ORDER_OTHER_STORE)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void create_crossBusinessOrder_returns404_orderNotFound() throws Exception {
        mockMvc.perform(post(invoicesUrl(ORDER_OTHER_BUSINESS)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void create_nonexistentOrder_returns404_orderNotFound() throws Exception {
        mockMvc.perform(post(invoicesUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void create_crossBusinessSlug_returns404_notFound() throws Exception {
        mockMvc.perform(post(invoicesUrl(SLUG_PREMIER, ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void create_crossStoreOrderWithNonEmptyBody_returns404_existenceWins() throws Exception {
        // Gate ordering: the 404 scope check precedes body validation, so a cross-store order with a
        // bad body is still 404 (never 400) — no existence leak.
        mockMvc.perform(post(invoicesUrl(ORDER_OTHER_STORE)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"due_date\":\"2026-01-01\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // ================================================================
    // Branch B helpers
    // ================================================================

    private static String currentInvoiceUrl(Object orderId) {
        return invoicesUrl(orderId) + "/current";
    }

    private static String currentInvoiceUrl(String slug, Object orderId) {
        return invoicesUrl(slug, orderId) + "/current";
    }

    private static String currentFileUrl(Object orderId) {
        return invoicesUrl(orderId) + "/current/file";
    }

    /** Insert a stored_file row (application/pdf) and return its id. */
    private long insertStoredFileRow(String fileName, String storagePath, long fileSize) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                        + "VALUES (?, ?, 'application/pdf', ?) RETURNING stored_file_id",
                Long.class, fileName, storagePath, fileSize);
    }

    /** Insert an extra invoice version for the order (so current = MAX(version_number) can be exercised). */
    private long insertInvoiceVersion(long orderId, int version, long storedFileId,
                                      String exGst, String incGst, String paid, String balance) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO invoice (order_id, version_number, invoice_date, due_date, "
                        + "details_of_sale_snapshot, sale_price_ex_gst, sale_price_inc_gst, total_paid, "
                        + "balance_due, stored_file_id, created_by_user_id) "
                        + "VALUES (?, ?, CURRENT_DATE, CURRENT_DATE, ?, ?, ?, ?, ?, ?, ?) RETURNING invoice_id",
                Long.class, orderId, version, "snapshot v" + version,
                new BigDecimal(exGst), new BigDecimal(incGst), new BigDecimal(paid), new BigDecimal(balance),
                storedFileId, USER_LIAM);
    }

    private void writeStorageFile(String storagePath, byte[] bytes) throws IOException {
        Path target = tempStorageDir.resolve(storagePath.startsWith("/") ? storagePath.substring(1) : storagePath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private void createInvoiceOnFullOrder() throws Exception {
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    // ================================================================
    // D.3 GET /invoices/current
    // ================================================================

    @Test
    void getCurrent_returnsInvoiceDetail() throws Exception {
        // Order 1 has the seeded invoice (id 1, v1): ex 840.00 / inc 924.00 / paid 500.00 / balance 424.00.
        mockMvc.perform(get(currentInvoiceUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice.invoice_id").isNumber())
                .andExpect(jsonPath("$.data.invoice.order_id").value(1))
                .andExpect(jsonPath("$.data.invoice.version_number").value(1))
                .andExpect(jsonPath("$.data.invoice.invoice_date").value("2026-04-14"))
                // Seed invoice due_date: V4 inserts 2026-04-28, then V5 updates it to
                // proposed_lay_date (2026-05-01) - 2 days = 2026-04-29 (locked due-date rule).
                .andExpect(jsonPath("$.data.invoice.due_date").value("2026-04-29"))
                .andExpect(jsonPath("$.data.invoice.details_of_sale_snapshot",
                        startsWith("Supply and install plush carpet")))
                .andExpect(jsonPath("$.data.invoice.sale_price_ex_gst").value(840.00))
                .andExpect(jsonPath("$.data.invoice.sale_price_inc_gst").value(924.00))
                .andExpect(jsonPath("$.data.invoice.total_paid").value(500.00))
                .andExpect(jsonPath("$.data.invoice.balance_due").value(424.00))
                .andExpect(jsonPath("$.data.invoice.created_by_user_id").value(1))
                .andExpect(jsonPath("$.data.invoice.created_at").exists())
                .andExpect(jsonPath("$.data.invoice.pdf_download_path").value(EXPECTED_PDF_PATH));
    }

    @Test
    void getCurrent_responseHasNoTopLevelMessageField() throws Exception {
        mockMvc.perform(get(currentInvoiceUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice").exists())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void getCurrent_choosesHighestVersionNumber() throws Exception {
        // Order 1 already has v1 (seed). Add a v2 with distinct totals; current must resolve to v2.
        long sf2 = insertStoredFileRow("invoice-" + ORDER_FULL_NUMBER + "-v2.pdf",
                "/uploads/1/orders/1/v2-detail.pdf", 2222);
        insertInvoiceVersion(ORDER_FULL, 2, sf2, "1000.00", "1100.00", "0.00", "1100.00");

        mockMvc.perform(get(currentInvoiceUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.invoice.sale_price_ex_gst").value(1000.00))
                .andExpect(jsonPath("$.data.invoice.sale_price_inc_gst").value(1100.00))
                .andExpect(jsonPath("$.data.invoice.balance_due").value(1100.00));
    }

    @Test
    void getCurrent_noInvoice_returns404InvoiceNotFound() throws Exception {
        clearSeededInvoice();
        mockMvc.perform(get(currentInvoiceUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_FOUND"));
    }

    @Test
    void getCurrent_doesNotExposeStoredFileIdOrStoragePath() throws Exception {
        mockMvc.perform(get(currentInvoiceUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice.stored_file_id").doesNotExist())
                .andExpect(jsonPath("$.data.invoice.storage_path").doesNotExist())
                .andExpect(jsonPath("$.data.invoice.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.invoice.store_id").doesNotExist());
    }

    @Test
    void getCurrent_noSession_returns401() throws Exception {
        mockMvc.perform(get(currentInvoiceUrl(ORDER_FULL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void getCurrent_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(get(currentInvoiceUrl(ORDER_FULL)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void getCurrent_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(get(currentInvoiceUrl("abc")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void getCurrent_crossStoreOrder_returns404OrderNotFound() throws Exception {
        mockMvc.perform(get(currentInvoiceUrl(ORDER_OTHER_STORE)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getCurrent_crossBusinessOrder_returns404OrderNotFound() throws Exception {
        mockMvc.perform(get(currentInvoiceUrl(ORDER_OTHER_BUSINESS)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getCurrent_nonexistentOrder_returns404OrderNotFound() throws Exception {
        mockMvc.perform(get(currentInvoiceUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getCurrent_crossBusinessSlug_returns404NotFound() throws Exception {
        mockMvc.perform(get(currentInvoiceUrl(SLUG_PREMIER, ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ================================================================
    // D.4 GET /invoices/current/file
    // ================================================================

    @Test
    void getCurrentFile_returns200WithPdfBytes() throws Exception {
        // Create a fresh invoice so its PDF actually exists on disk (the seed only inserts a DB row).
        clearSeededInvoice();
        createInvoiceOnFullOrder();

        MvcResult result = mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        Assertions.assertTrue(body.length > 0, "PDF body must be non-empty");
        Assertions.assertEquals("%PDF-", new String(body, 0, 5, StandardCharsets.US_ASCII), "valid PDF header");
        Assertions.assertEquals(body.length, result.getResponse().getContentLength(),
                "Content-Length matches the streamed bytes");
    }

    @Test
    void getCurrentFile_contentDispositionHasSafePdfFilename() throws Exception {
        clearSeededInvoice();
        createInvoiceOnFullOrder();

        MvcResult result = mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(EXPECTED_PDF_NAME)))
                .andReturn();

        String cd = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        Assertions.assertNotNull(cd);
        Assertions.assertTrue(cd.startsWith("inline"), () -> "disposition: " + cd);
    }

    @Test
    void getCurrentFile_choosesHighestVersionNumber() throws Exception {
        // v1 (real PDF written by create) + a v2 with its own real file; current/file must stream v2.
        clearSeededInvoice();
        createInvoiceOnFullOrder();

        byte[] v2Bytes = "%PDF-1.4 fake-v2-invoice-bytes".getBytes(StandardCharsets.US_ASCII);
        String v2Path = "/uploads/1/orders/1/v2-real.pdf";
        writeStorageFile(v2Path, v2Bytes);
        long sf2 = insertStoredFileRow("invoice-" + ORDER_FULL_NUMBER + "-v2.pdf", v2Path, v2Bytes.length);
        insertInvoiceVersion(ORDER_FULL, 2, sf2, "1000.00", "1100.00", "0.00", "1100.00");

        MvcResult result = mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("invoice-" + ORDER_FULL_NUMBER + "-v2.pdf")))
                .andReturn();

        Assertions.assertArrayEquals(v2Bytes, result.getResponse().getContentAsByteArray(),
                "must stream the highest-version (v2) PDF bytes");
    }

    @Test
    void getCurrentFile_noInvoice_returns404InvoiceNotFound() throws Exception {
        clearSeededInvoice();
        mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_FOUND"));
    }

    @Test
    void getCurrentFile_missingOnDisk_returns500JsonError_noStoragePathLeak() throws Exception {
        // The seeded invoice's stored_file points to a path that is NOT present under the test temp dir.
        MvcResult result = mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Assertions.assertFalse(body.contains("storage_path"), () -> "must not leak storage_path: " + body);
        Assertions.assertFalse(body.contains("/uploads/"), () -> "must not leak the on-disk path: " + body);
    }

    @Test
    void getCurrentFile_noSession_returns401() throws Exception {
        mockMvc.perform(get(currentFileUrl(ORDER_FULL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void getCurrentFile_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void getCurrentFile_crossStoreOrder_returns404OrderNotFound() throws Exception {
        mockMvc.perform(get(currentFileUrl(ORDER_OTHER_STORE)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getCurrentFile_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(get(currentFileUrl("abc")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ================================================================
    // Scope guard: no payment / history / by-id / old-PDF endpoint creep
    // ================================================================

    @Test
    void noInvoiceHistoryByIdOrOldPdfEndpointsImplemented() throws Exception {
        // The invoice controller exposes ONLY current-invoice routes (D.1/D.2/D.3/D.4). The deferred
        // history / by-id / old-version-PDF routes (D.5) must remain unmapped -> a 4xx (no handler),
        // never a 2xx. Payments (D.6/D.7) are a separate controller (Branch D) and are intentionally not
        // asserted here.
        mockMvc.perform(get(invoicesUrl(ORDER_FULL)).session(liamStore1Session())) // history list
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get(invoicesUrl(ORDER_FULL) + "/1").session(liamStore1Session())) // detail by-id
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get(invoicesUrl(ORDER_FULL) + "/1/file").session(liamStore1Session())) // old-version PDF
                .andExpect(status().is4xxClientError());
    }

    // ================================================================
    // D.2 POST /invoices/rewrite (Branch C)
    // ================================================================

    private static String rewriteUrl(Object orderId) {
        return invoicesUrl(orderId) + "/rewrite";
    }

    private static String rewriteUrl(String slug, Object orderId) {
        return invoicesUrl(slug, orderId) + "/rewrite";
    }

    /** Insert an extra payment row for the order so total_paid can be exercised (identity id, post-seed). */
    private void insertPayment(long orderId, String amount, String reference) {
        jdbcTemplate.update(
                "INSERT INTO payment_transaction (order_id, payment_method, amount, payment_reference) "
                        + "VALUES (?, 'EFTPOS'::payment_method, ?, ?)",
                orderId, new BigDecimal(amount), reference);
    }

    // ---- happy path -------------------------------------------------

    @Test
    void rewrite_existingInvoice_returns201_withRewrittenInvoiceDetail() throws Exception {
        // Order 1 has the seeded v1 (ex 840 / inc 924 / paid 500 / balance 424). Rewrite snapshots the
        // current live state into v2 with the same live totals and the same due_date rule (2026-05-01 - 2).
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Invoice rewritten."))
                .andExpect(jsonPath("$.data.invoice.invoice_id").isNumber())
                .andExpect(jsonPath("$.data.invoice.order_id").value(1))
                .andExpect(jsonPath("$.data.invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.invoice.invoice_date").exists())
                .andExpect(jsonPath("$.data.invoice.due_date").value("2026-04-29")) // 2026-05-01 minus 2 days
                .andExpect(jsonPath("$.data.invoice.details_of_sale_snapshot",
                        startsWith("Supply and install plush carpet")))
                .andExpect(jsonPath("$.data.invoice.sale_price_ex_gst").value(840.00))
                .andExpect(jsonPath("$.data.invoice.sale_price_inc_gst").value(924.00))
                .andExpect(jsonPath("$.data.invoice.total_paid").value(500.00))
                .andExpect(jsonPath("$.data.invoice.balance_due").value(424.00))
                .andExpect(jsonPath("$.data.invoice.created_by_user_id").value(1))
                .andExpect(jsonPath("$.data.invoice.created_at").exists())
                .andExpect(jsonPath("$.data.invoice.pdf_download_path").value(EXPECTED_PDF_PATH));
    }

    @Test
    void rewrite_emptyBody_alsoAccepted() throws Exception {
        // No body at all (the contract allows {} or an empty body).
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.version_number").value(2));
    }

    @Test
    void rewrite_incrementsVersion_newHighestBecomesCurrent() throws Exception {
        // Seeded v1 only. After rewrite there are exactly two invoice rows and the new MAX(version) is 2.
        Assertions.assertEquals(1, countInvoices(ORDER_FULL));
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        Assertions.assertEquals(2, countInvoices(ORDER_FULL), "rewrite appends a new version (v1 retained)");
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, ORDER_FULL);
        Assertions.assertEquals(2, maxVersion);
        // The current (max-version) row is the freshly rewritten one.
        long currentId = latestInvoiceId(ORDER_FULL);
        Integer currentVersion = jdbcTemplate.queryForObject(
                "SELECT version_number FROM invoice WHERE invoice_id = ?", Integer.class, currentId);
        Assertions.assertEquals(2, currentVersion);
    }

    @Test
    void rewrite_fromV3_nextVersionIsMaxPlusOne() throws Exception {
        // Seed v1 + a manually inserted v3 (gaps allowed): rewrite must use MAX(version)+1 = 4, not count+1.
        long sf3 = insertStoredFileRow("invoice-" + ORDER_FULL_NUMBER + "-v3.pdf",
                "/uploads/1/orders/1/v3-detail.pdf", 3333);
        insertInvoiceVersion(ORDER_FULL, 3, sf3, "1000.00", "1100.00", "0.00", "1100.00");

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.version_number").value(4));
    }

    @Test
    void rewrite_regeneratesPdfStoredFileForNewVersion() throws Exception {
        // Capture v1's stored_file, rewrite, then assert v2 got a brand-new stored_file (different id,
        // versioned name, real PDF bytes on disk).
        long v1InvoiceId = latestInvoiceId(ORDER_FULL);
        Long v1StoredFileId = jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE invoice_id = ?", Long.class, v1InvoiceId);

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        long v2InvoiceId = latestInvoiceId(ORDER_FULL);
        Long v2StoredFileId = jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE invoice_id = ?", Long.class, v2InvoiceId);
        Assertions.assertNotNull(v2StoredFileId);
        Assertions.assertNotEquals(v1StoredFileId, v2StoredFileId, "rewrite writes a NEW stored_file");

        String fileName = jdbcTemplate.queryForObject(
                "SELECT file_name FROM stored_file WHERE stored_file_id = ?", String.class, v2StoredFileId);
        String mimeType = jdbcTemplate.queryForObject(
                "SELECT mime_type FROM stored_file WHERE stored_file_id = ?", String.class, v2StoredFileId);
        long fileSize = jdbcTemplate.queryForObject(
                "SELECT file_size FROM stored_file WHERE stored_file_id = ?", Long.class, v2StoredFileId);
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM stored_file WHERE stored_file_id = ?", String.class, v2StoredFileId);

        Assertions.assertEquals("invoice-" + ORDER_FULL_NUMBER + "-v2.pdf", fileName);
        Assertions.assertEquals("application/pdf", mimeType);
        Assertions.assertTrue(fileSize > 0, "file_size must be positive");
        Assertions.assertTrue(diskFileExists(storagePath), "rewritten invoice PDF should exist on disk");
        byte[] bytes = Files.readAllBytes(tempStorageDir.resolve(storagePath.substring(1)));
        Assertions.assertEquals((int) fileSize, bytes.length, "stored file_size matches the bytes on disk");
        Assertions.assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII), "valid PDF header");
    }

    @Test
    void rewrite_thenGetCurrent_returnsRewrittenInvoice() throws Exception {
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        long rewrittenId = latestInvoiceId(ORDER_FULL);

        mockMvc.perform(get(currentInvoiceUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice.invoice_id").value((int) rewrittenId))
                .andExpect(jsonPath("$.data.invoice.version_number").value(2));
    }

    @Test
    void rewrite_thenGetCurrentFile_returnsRewrittenPdf() throws Exception {
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("invoice-" + ORDER_FULL_NUMBER + "-v2.pdf")))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        Assertions.assertEquals("%PDF-", new String(body, 0, 5, StandardCharsets.US_ASCII),
                "current/file must stream the rewritten (v2) PDF");
    }

    @Test
    void rewrite_usesLiveFinancialSummaryAfterSalePriceChange() throws Exception {
        // Live saved order state, not the previous snapshot: a price adjustment makes
        // final_sale_price_inc_gst 1000.00 (924.00 + 76.00), ex-GST = 909.09. v1 keeps 924/840; the
        // rewritten v2 must reflect the live summary. balance = 1000.00 - 500.00 (existing payment) = 500.00.
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = 76.00 WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.invoice.sale_price_inc_gst").value(1000.00))
                .andExpect(jsonPath("$.data.invoice.sale_price_ex_gst").value(909.09))
                .andExpect(jsonPath("$.data.invoice.balance_due").value(500.00));
    }

    @Test
    void rewrite_carriesTotalPaidFromPaymentsAndRecalculatesBalance() throws Exception {
        // total_paid is the live SUM(payment_transaction.amount): seeded 500.00 + an extra 100.00 = 600.00,
        // so balance_due = inc 924.00 - 600.00 = 324.00 (recalculated against the live summary).
        insertPayment(ORDER_FULL, "100.00", "EFTPOS-EXTRA");

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.total_paid").value(600.00))
                .andExpect(jsonPath("$.data.invoice.balance_due").value(324.00));
    }

    @Test
    void rewrite_snapshotsLiveDetailsIntoNewVersion_olderVersionUnchanged() throws Exception {
        // D.2 step 5: rewrite re-snapshots the CURRENT live order state onto the new version (it is what
        // makes live edits official), while older rows are never modified. Capture v1's frozen snapshot,
        // change the live order + rewrite into v2, then assert: (a) v2 picked up the changed live details,
        // and (b) v1's columns are untouched.
        long v1InvoiceId = latestInvoiceId(ORDER_FULL);
        BigDecimal v1Inc = invoiceMoney("sale_price_inc_gst", v1InvoiceId);
        String v1Details = jdbcTemplate.queryForObject(
                "SELECT details_of_sale_snapshot FROM invoice WHERE invoice_id = ?", String.class, v1InvoiceId);

        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = 76.00, "
                + "details_of_sale = 'CHANGED BEFORE REWRITE' WHERE order_id = ?", ORDER_FULL);
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                // (a) the new version reflects the live (changed) details + live summary, not a carry-forward.
                .andExpect(jsonPath("$.data.invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.invoice.details_of_sale_snapshot").value("CHANGED BEFORE REWRITE"))
                .andExpect(jsonPath("$.data.invoice.sale_price_inc_gst").value(1000.00));

        // (b) the older version (v1) snapshot is untouched.
        assertMoney(v1Inc.toPlainString(), invoiceMoney("sale_price_inc_gst", v1InvoiceId));
        String v1DetailsAfter = jdbcTemplate.queryForObject(
                "SELECT details_of_sale_snapshot FROM invoice WHERE invoice_id = ?", String.class, v1InvoiceId);
        Assertions.assertEquals(v1Details, v1DetailsAfter, "older version snapshot must be untouched");
    }

    @Test
    void rewrite_response_doesNotExposeStoredFileIdOrStoragePath() throws Exception {
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.stored_file_id").doesNotExist())
                .andExpect(jsonPath("$.data.invoice.storage_path").doesNotExist())
                .andExpect(jsonPath("$.data.invoice.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.invoice.store_id").doesNotExist());
    }

    // ---- business rules ---------------------------------------------

    @Test
    void rewrite_noExistingInvoice_returns422_invoiceRequired() throws Exception {
        // Rewrite requires a prior invoice. Order 1 with its invoice cleared (still ACCEPTED, all
        // preconditions pass) -> 422 INVOICE_REQUIRED, and no invoice is created.
        clearSeededInvoice();
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_REQUIRED"));
        Assertions.assertEquals(0, countInvoices(ORDER_FULL));
    }

    @Test
    void rewrite_laidOrder_returns422_orderLocked() throws Exception {
        // Manual Rewrite is blocked when LAID (conventions §16). Order 1 keeps its seeded invoice and is
        // flipped to LAID -> 422 ORDER_LOCKED, and no new version is created.
        laidOrder(ORDER_FULL);
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
        Assertions.assertEquals(1, countInvoices(ORDER_FULL), "no new version on a locked order");
    }

    @Test
    void rewrite_laidOrderWithNonEmptyBody_returns422_orderLockedWinsOverBody() throws Exception {
        // Gate-first: the LAID gate runs before body validation (mirrors OrderService). A locked order
        // with a bad body is still ORDER_LOCKED (422), never 400.
        laidOrder(ORDER_FULL);
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"due_date\":\"2026-01-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    @Test
    void rewrite_preconditionsFail_returns422_withDetails() throws Exception {
        // Order 1 has an invoice (INVOICE_REQUIRED passes) and a valid customer email (the Phase 13
        // gate passes), then loses its details_of_sale so precondition 6 fails -> 422
        // INVOICE_PRECONDITIONS_NOT_MET with the detail; no new version created. (Deleting the customer
        // now trips the email gate first — asserted separately.)
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = NULL WHERE order_id = ?", ORDER_FULL);
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_PRECONDITIONS_NOT_MET"))
                .andExpect(jsonPath("$.error.details", hasSize(1)))
                .andExpect(jsonPath("$.error.details[*].field", hasItems("details_of_sale")));
        Assertions.assertEquals(1, countInvoices(ORDER_FULL), "no new version when preconditions fail");
    }

    // ---- body / id validation (gate ordering) -----------------------

    @Test
    void rewrite_nonEmptyBody_returns400_validationFailed() throws Exception {
        // Order 1 (ACCEPTED, has an invoice): passes the LAID gate, then a non-empty body is rejected.
        // Any field (incl. due_date) -> VALIDATION_FAILED.
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"due_date\":\"2026-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("due_date"));
    }

    @Test
    void rewrite_malformedJson_returns400_malformedJson() throws Exception {
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void rewrite_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(post(rewriteUrl("abc")).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ---- scoping / auth (no existence leak) -------------------------

    @Test
    void rewrite_noSession_returns401() throws Exception {
        mockMvc.perform(post(rewriteUrl(ORDER_FULL))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void rewrite_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void rewrite_crossStoreOrder_returns404_orderNotFound() throws Exception {
        mockMvc.perform(post(rewriteUrl(ORDER_OTHER_STORE)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void rewrite_crossBusinessOrder_returns404_orderNotFound() throws Exception {
        mockMvc.perform(post(rewriteUrl(ORDER_OTHER_BUSINESS)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void rewrite_nonexistentOrder_returns404_orderNotFound() throws Exception {
        mockMvc.perform(post(rewriteUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void rewrite_crossBusinessSlug_returns404_notFound() throws Exception {
        mockMvc.perform(post(rewriteUrl(SLUG_PREMIER, ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void rewrite_crossStoreOrderWithNonEmptyBody_returns404_existenceWins() throws Exception {
        // Gate ordering: the 404 scope check precedes the LAID/body gates, so a cross-store order with a
        // bad body is still 404 (never 400/422) — no existence leak.
        mockMvc.perform(post(rewriteUrl(ORDER_OTHER_STORE)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"due_date\":\"2026-01-01\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // ================================================================
    // Phase 13 foundation — V10 schema (acceptance/email columns)
    // ================================================================

    @Test
    void v10_invoiceAcceptanceColumns_existNullable_withNonUniqueFk() throws Exception {
        // The four Phase 13 columns exist on invoice and are all nullable.
        Integer nullableColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'invoice'
                  AND column_name IN ('accepted_at', 'accepted_customer_name',
                                      'accepted_signature_file_id', 'last_emailed_at')
                  AND is_nullable = 'YES'
                """, Integer.class);
        Assertions.assertEquals(4, nullableColumns, "all four Phase 13 invoice columns exist and are nullable");

        // The signature FK exists…
        Integer fk = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_name = 'invoice'
                  AND constraint_name = 'fk_invoice_accepted_signature_file'
                  AND constraint_type = 'FOREIGN KEY'
                """, Integer.class);
        Assertions.assertEquals(1, fk, "fk_invoice_accepted_signature_file exists");

        // …and is NOT unique (no unique constraint/index covers accepted_signature_file_id).
        Integer uniqueOnSignature = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE tablename = 'invoice'
                  AND indexdef ILIKE '%accepted_signature_file_id%'
                  AND indexdef ILIKE '%unique%'
                """, Integer.class);
        Assertions.assertEquals(0, uniqueOnSignature, "accepted_signature_file_id must NOT be unique");
    }

    @Test
    void v10_acceptedSignatureFileId_allowsTwoVersionsSharingOneFile() throws Exception {
        // Behavioural non-uniqueness proof: two invoice versions referencing the SAME signature
        // stored_file must both insert (a payment-carried-forward version reuses the signature file).
        int next = maxInvoiceVersion(ORDER_FULL) + 1;
        long signatureFile = insertStoredFileRow("signature-shared.png", "/uploads/1/orders/1/sig.png", 100);
        long sf2 = insertStoredFileRow("invoice-v2.pdf", "/uploads/1/orders/1/v2-sig.pdf", 2222);
        long sf3 = insertStoredFileRow("invoice-v3.pdf", "/uploads/1/orders/1/v3-sig.pdf", 3333);
        long v2 = insertInvoiceVersion(ORDER_FULL, next, sf2, "840.00", "924.00", "500.00", "424.00");
        long v3 = insertInvoiceVersion(ORDER_FULL, next + 1, sf3, "840.00", "924.00", "500.00", "424.00");

        jdbcTemplate.update("UPDATE invoice SET accepted_signature_file_id = ? WHERE invoice_id = ?",
                signatureFile, v2);
        jdbcTemplate.update("UPDATE invoice SET accepted_signature_file_id = ? WHERE invoice_id = ?",
                signatureFile, v3);

        Integer sharing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE accepted_signature_file_id = ?", Integer.class, signatureFile);
        Assertions.assertEquals(2, sharing, "two versions may reference the same signature stored_file");
    }

    // ================================================================
    // Phase 13 email gate — D.1 create
    // ================================================================

    @Test
    void create_missingCustomerRow_returns422_customerEmailRequired() throws Exception {
        // No order_customer row at all -> the email gate (which runs BEFORE the 9 preconditions)
        // answers CUSTOMER_EMAIL_REQUIRED, never INVOICE_PRECONDITIONS_NOT_MET.
        clearSeededInvoice();
        jdbcTemplate.update("DELETE FROM order_customer WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"));
        Assertions.assertEquals(0, countInvoices(ORDER_FULL), "no invoice created when the email gate fails");
    }

    @Test
    void create_blankEmail_returns422_customerEmailRequired() throws Exception {
        clearSeededInvoice();
        relaxCustomerEmailDbConstraints();
        setCustomerEmail(ORDER_FULL, "   ");

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"));
        Assertions.assertEquals(0, countInvoices(ORDER_FULL));
    }

    @Test
    void create_nullEmail_returns422_customerEmailRequired() throws Exception {
        clearSeededInvoice();
        relaxCustomerEmailDbConstraints();
        setCustomerEmail(ORDER_FULL, null);

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"));
        Assertions.assertEquals(0, countInvoices(ORDER_FULL));
    }

    @Test
    void create_malformedEmail_returns422_customerEmailInvalid() throws Exception {
        // "james@" passes the DB CHECK (non-blank, LIKE '%@%') but has no domain — the backend gate is
        // deliberately stricter than the DB rule.
        clearSeededInvoice();
        setCustomerEmail(ORDER_FULL, "james@");

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_INVALID"));
        Assertions.assertEquals(0, countInvoices(ORDER_FULL));
    }

    @Test
    void create_malformedEmailVariants_allReturnCustomerEmailInvalid() throws Exception {
        // Every variant passes the DB CHECK (non-blank + '@') but is obviously malformed: missing local
        // part, missing domain dot, interior whitespace, double '@', leading/trailing domain dot.
        clearSeededInvoice();
        List<String> malformed = List.of(
                "@email.com", "james@email", "james wilson@email.com",
                "james@@email.com", "james@.com", "james@email.");
        for (String bad : malformed) {
            setCustomerEmail(ORDER_FULL, bad);
            mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_INVALID"));
        }
        Assertions.assertEquals(0, countInvoices(ORDER_FULL));
    }

    @Test
    void create_frontendAcceptedTrailingDotDomain_passesTheGate() throws Exception {
        // "john@example.com." passes the frontend Customer-tab regex (/^[^\s@]+@[^\s@]+\.[^\s@]+$/ —
        // backtracking tolerates the trailing root dot), so the backend gate must accept it too:
        // otherwise a saved customer email dead-ends invoice creation on an error the Customer tab
        // says is not there. The gate requires an INTERIOR domain dot, which this address has.
        clearSeededInvoice();
        setCustomerEmail(ORDER_FULL, "john@example.com.");

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(false));
        Assertions.assertEquals(1, countInvoices(ORDER_FULL));
    }

    @Test
    void create_emailGateFiresBeforePreconditions_missingCustomer() throws Exception {
        // Order 2 would fail ALL 9 preconditions AND has no customer row. The email gate must answer
        // first: CUSTOMER_EMAIL_REQUIRED with NO precondition details (the 9-check evaluation is skipped).
        mockMvc.perform(post(invoicesUrl(ORDER_EMPTY)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
        Assertions.assertEquals(0, countInvoices(ORDER_EMPTY));
    }

    @Test
    void create_emailGateFiresBeforePreconditions_invalidEmail() throws Exception {
        // Order 2 with a customer whose email is malformed (but DB-acceptable): the gate answers
        // CUSTOMER_EMAIL_INVALID even though every other precondition would also fail.
        seedMinimalCustomer(ORDER_EMPTY, "noreply@invalid");

        mockMvc.perform(post(invoicesUrl(ORDER_EMPTY)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_INVALID"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    void create_invoiceAlreadyExistsStillPrecedesEmailGate() throws Exception {
        // Existing gate ordering is unchanged: with the seeded invoice in place, the 409 already-exists
        // check fires before the email gate even when the customer row is gone.
        jdbcTemplate.update("DELETE FROM order_customer WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVOICE_ALREADY_EXISTS"));
    }

    // ================================================================
    // Phase 13 email gate — D.2 rewrite
    // ================================================================

    @Test
    void rewrite_missingCustomerRow_returns422_customerEmailRequired() throws Exception {
        int invoicesBefore = countInvoices(ORDER_FULL);
        jdbcTemplate.update("DELETE FROM order_customer WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"));
        Assertions.assertEquals(invoicesBefore, countInvoices(ORDER_FULL),
                "no new version when the email gate fails");
    }

    @Test
    void rewrite_blankEmail_returns422_customerEmailRequired() throws Exception {
        int invoicesBefore = countInvoices(ORDER_FULL);
        relaxCustomerEmailDbConstraints();
        setCustomerEmail(ORDER_FULL, "   ");

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"));
        Assertions.assertEquals(invoicesBefore, countInvoices(ORDER_FULL));
    }

    @Test
    void rewrite_nullEmail_returns422_customerEmailRequired() throws Exception {
        int invoicesBefore = countInvoices(ORDER_FULL);
        relaxCustomerEmailDbConstraints();
        setCustomerEmail(ORDER_FULL, null);

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"));
        Assertions.assertEquals(invoicesBefore, countInvoices(ORDER_FULL));
    }

    @Test
    void rewrite_malformedEmail_returns422_customerEmailInvalid() throws Exception {
        int invoicesBefore = countInvoices(ORDER_FULL);
        setCustomerEmail(ORDER_FULL, "james@");

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_INVALID"));
        Assertions.assertEquals(invoicesBefore, countInvoices(ORDER_FULL));
    }

    @Test
    void rewrite_emailGateFiresBeforePreconditions() throws Exception {
        // Both the email gate (malformed email) and a precondition (details_of_sale gone) would fail —
        // the email gate answers first and the 9-check evaluation is skipped (no details list).
        setCustomerEmail(ORDER_FULL, "james@");
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = NULL WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_INVALID"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    void rewrite_invoiceRequiredStillPrecedesEmailGate() throws Exception {
        // Existing gate ordering is unchanged: with no invoice at all, INVOICE_REQUIRED fires before
        // the email gate even when the customer row is gone.
        clearSeededInvoice();
        jdbcTemplate.update("DELETE FROM order_customer WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_REQUIRED"));
    }

    // ================================================================
    // Phase 13 acceptance/email fields — D.1 / D.2 / D.3 responses + persisted columns
    // ================================================================

    @Test
    void create_returnsUnsignedAcceptanceAndEmailFields() throws Exception {
        clearSeededInvoice();

        MvcResult result = mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(false))
                .andReturn();

        // The five Phase 13 fields are ALWAYS present; null values serialize as null (the jsonPath
        // doesNotExist() matcher treats JSON null as absent, so assert on the raw body).
        String json = result.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"accepted_at\":null"), () -> "accepted_at null key: " + json);
        Assertions.assertTrue(json.contains("\"accepted_customer_name\":null"),
                () -> "accepted_customer_name null key: " + json);
        Assertions.assertTrue(json.contains("\"accepted_signature_download_path\":null"),
                () -> "accepted_signature_download_path null key: " + json);
        Assertions.assertTrue(json.contains("\"last_emailed_at\":null"), () -> "last_emailed_at null key: " + json);
        // The internal signature file id is never serialized.
        Assertions.assertFalse(json.contains("accepted_signature_file_id"),
                () -> "must not leak accepted_signature_file_id: " + json);
    }

    @Test
    void rewrite_returnsUnsignedAcceptanceAndEmailFields() throws Exception {
        int nextVersion = maxInvoiceVersion(ORDER_FULL) + 1;
        MvcResult result = mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.version_number").value(nextVersion))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(false))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"accepted_at\":null"), () -> "accepted_at null key: " + json);
        Assertions.assertTrue(json.contains("\"accepted_customer_name\":null"),
                () -> "accepted_customer_name null key: " + json);
        Assertions.assertTrue(json.contains("\"accepted_signature_download_path\":null"),
                () -> "accepted_signature_download_path null key: " + json);
        Assertions.assertTrue(json.contains("\"last_emailed_at\":null"), () -> "last_emailed_at null key: " + json);
        Assertions.assertFalse(json.contains("accepted_signature_file_id"),
                () -> "must not leak accepted_signature_file_id: " + json);
    }

    @Test
    void getCurrent_returnsUnsignedAcceptanceAndEmailFields() throws Exception {
        // D.3 reads the seeded (unaccepted) invoice — the five fields are present with null/false values.
        MvcResult result = mockMvc.perform(get(currentInvoiceUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(false))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"accepted_at\":null"), () -> "accepted_at null key: " + json);
        Assertions.assertTrue(json.contains("\"accepted_customer_name\":null"),
                () -> "accepted_customer_name null key: " + json);
        Assertions.assertTrue(json.contains("\"accepted_signature_download_path\":null"),
                () -> "accepted_signature_download_path null key: " + json);
        Assertions.assertTrue(json.contains("\"last_emailed_at\":null"), () -> "last_emailed_at null key: " + json);
    }

    @Test
    void create_persistsNullAcceptanceColumnsOnNewRow() throws Exception {
        clearSeededInvoice();
        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        long invoiceId = latestInvoiceId(ORDER_FULL);
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT accepted_at FROM invoice WHERE invoice_id = ?", Timestamp.class, invoiceId));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT accepted_customer_name FROM invoice WHERE invoice_id = ?", String.class, invoiceId));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT accepted_signature_file_id FROM invoice WHERE invoice_id = ?", Long.class, invoiceId));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM invoice WHERE invoice_id = ?", Timestamp.class, invoiceId));
    }

    @Test
    void rewrite_afterAcceptedCurrentInvoice_newVersionIsUnaccepted() throws Exception {
        // Phase 13 §7: rewriting a previously accepted invoice produces a new UNSIGNED/UNACCEPTED
        // current version (the customer must re-accept). The acceptance columns are simply not carried.
        long acceptedInvoiceId = latestInvoiceId(ORDER_FULL);
        jdbcTemplate.update("UPDATE invoice SET accepted_at = TIMESTAMP '2026-04-22 14:31:10', "
                + "accepted_customer_name = 'James Wilson' WHERE invoice_id = ?", acceptedInvoiceId);

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(false));

        long newVersionId = latestInvoiceId(ORDER_FULL);
        Assertions.assertNotEquals(acceptedInvoiceId, newVersionId, "rewrite appended a new current version");
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT accepted_at FROM invoice WHERE invoice_id = ?", Timestamp.class, newVersionId));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT accepted_customer_name FROM invoice WHERE invoice_id = ?", String.class, newVersionId));
        // The older accepted version is untouched (append-only).
        Assertions.assertNotNull(jdbcTemplate.queryForObject(
                "SELECT accepted_at FROM invoice WHERE invoice_id = ?", Timestamp.class, acceptedInvoiceId));
    }

    // ================================================================
    // Phase 13 dashboard mirror — sales_order.last_emailed_at reset on new versions
    // ================================================================

    @Test
    void create_resetsSalesOrderLastEmailedAtMirrorToNull() throws Exception {
        // §11.1: a new current invoice version starts unemailed, so the dashboard mirror must be reset
        // to null — never left showing a stale emailed time from a previous version.
        clearSeededInvoice();
        setOrderLastEmailedAt(ORDER_FULL, "2026-01-05 09:30:00");

        mockMvc.perform(post(invoicesUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        Assertions.assertNull(orderLastEmailedAt(ORDER_FULL), "mirror reset to the new version's null value");
    }

    @Test
    void rewrite_resetsSalesOrderLastEmailedAtMirrorToNull() throws Exception {
        setOrderLastEmailedAt(ORDER_FULL, "2026-01-05 09:30:00");

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        Assertions.assertNull(orderLastEmailedAt(ORDER_FULL), "mirror reset to the new version's null value");
    }

    @Test
    void rewrite_failedPreconditions_leaveMirrorUntouched() throws Exception {
        // No new version -> no mirror write: a failed rewrite must not clear the existing mirror value.
        setOrderLastEmailedAt(ORDER_FULL, "2026-01-05 09:30:00");
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = NULL WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(post(rewriteUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_PRECONDITIONS_NOT_MET"));

        Assertions.assertNotNull(orderLastEmailedAt(ORDER_FULL), "mirror unchanged when no version is created");
    }

    // ================================================================
    // Phase 16F PR2 - terms_html / terms_source on Path B invoices (D.1 / D.2 / D.3) + the V19 columns
    // ================================================================
    //
    // A Path B invoice (no source quote version) reports terms_source LIVE and terms_html = the business's
    // CURRENT per-flooring-type terms (terms_soft for SOFT, terms_hard for HARD) through the
    // InvoiceTermsSanitizer, or JSON null (key present) when there are none. D.1 Create and D.2 Rewrite
    // write source_quote_version_id and terms_snapshot as NULL, and the PDF follows the same terms rule.
    //
    // Self-seeded (Phase 14D go-forward rule): every test below INSERTs its own invoice-ready order under
    // the V4 business 1 / store 1 / user 1 identity (OITRM.ZZ9. prefix, seq base 141_000; never the V4
    // demo orders) and sets business 1's per-type terms itself, so nothing depends on the terms the shared
    // database happens to hold. Everything rolls back with the test; the PDFs land in this class's TempDir.

    private static final String TERMS_ORDER_PREFIX = "OITRM.ZZ9.";
    private static final String TERMS_CHARGE_CODE_PREFIX = "OITRM";
    private static final String TERMS_DETAILS_OF_SALE = "Supply and lay flooring (terms source test)";

    // Raw tenant terms holding markup the sanitizer strips (style, event handler, id, script), so a
    // terms_html equal to the SANITIZED value (and unequal to the raw one) proves the sanitizer ran.
    private static final String SOFT_TERMS_TEXT = "Live soft flooring terms apply.";
    private static final String HARD_TERMS_TEXT = "Live hard flooring terms apply.";
    private static final String UPDATED_SOFT_TERMS_TEXT = "Updated soft flooring terms apply.";
    private static final String UPDATED_HARD_TERMS_TEXT = "Updated hard flooring terms apply.";
    private static final String LIVE_SOFT_TERMS_RAW =
            "<p style=\"color:red\" onclick=\"steal()\">" + SOFT_TERMS_TEXT + "</p><script>alert(1)</script>";
    private static final String LIVE_HARD_TERMS_RAW = "<p id=\"hard\">" + HARD_TERMS_TEXT + "</p>";
    private static final String UPDATED_SOFT_TERMS_RAW =
            "<p class=\"terms\" style=\"font-weight:bold\">" + UPDATED_SOFT_TERMS_TEXT + "</p>";
    private static final String UPDATED_HARD_TERMS_RAW =
            "<ul><li onclick=\"steal()\">" + UPDATED_HARD_TERMS_TEXT + "</li></ul>";
    // XML-safe, sanitizer-style frozen terms for a SQL-seeded quote-sourced row.
    private static final String FROZEN_TERMS_TEXT = "Seeded frozen terms";
    private static final String FROZEN_TERMS_SNAPSHOT = "<p>" + FROZEN_TERMS_TEXT + "</p>";
    private static final String PDF_FOOTER = "Generated by the Flooring Sales Portal";

    // InvoiceDetail: every key is ALWAYS serialized (terms_html / terms_source included) and no internal
    // id (source_quote_version_id, stored_file ids) ever is.
    private static final Set<String> INVOICE_DETAIL_KEYS = Set.of(
            "invoice_id", "order_id", "version_number", "invoice_date", "due_date", "details_of_sale_snapshot",
            "sale_price_ex_gst", "sale_price_inc_gst", "total_paid", "balance_due", "created_by_user_id",
            "created_at", "pdf_download_path", "accepted_at", "accepted_customer_name",
            "accepted_signature_present", "accepted_signature_download_path", "last_emailed_at",
            "terms_html", "terms_source");

    private static final InvoiceTermsSanitizer TERMS_SANITIZER = new InvoiceTermsSanitizer();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private EntityManager entityManager;

    private int termsSeq = 141_000;

    // ---- fixtures ----

    /**
     * Detach every hydrated entity: the MockMvc calls of one test share the test transaction's persistence
     * context, so after a raw JDBC write a later JPA read could otherwise return a stale entity.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    /** Business 1's live per-flooring-type terms (V13 columns); rolled back with the test. */
    private void setTenantTerms(String termsSoft, String termsHard) {
        jdbcTemplate.update("UPDATE business SET terms_soft = ?, terms_hard = ? WHERE business_id = ?",
                termsSoft, termsHard, BUSINESS_AUSSIE);
        clearJpaCache();
    }

    /** The live terms exactly as an InvoiceDetail must carry them: through the sanitizer. */
    private static String sanitized(String rawTerms) {
        String html = TERMS_SANITIZER.sanitize(rawTerms);
        Assertions.assertNotNull(html, () -> "fixture: the raw terms must keep visible text: " + rawTerms);
        return html;
    }

    /**
     * An invoice-ready order of the given flooring type: the D.1 email gate and all 9 preconditions pass
     * (customer with a valid email, INSTALLATION + BILLING addresses, details of sale, proposed lay date
     * 2026-12-01 CONFIRMED, ONE priced charge line of 100.00 ex / cost 40.00, so 110.00 inc).
     */
    private long insertInvoiceReadyOrder(String flooringType) {
        int s = ++termsSeq;
        // order_number must match chk_sales_order_number_format (V6): {code}.{LL#}.{#####}
        long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, "
                        + " details_of_sale, proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?::flooring_type, 'LEAD'::order_status, 1, 2026, "
                        + " ?, DATE '2026-12-01', 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, s,
                TERMS_ORDER_PREFIX + String.format("%05d", s % 100_000), flooringType, TERMS_DETAILS_OF_SALE);
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Terms', 'Tester', 'terms.tester@example.com', '0412345678')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, '7', 'Install Street', 'Sydney', 'NSW', '2000')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, '12', 'Billing Street', 'Sydney', 'NSW', '2000')",
                orderId);
        String code = TERMS_CHARGE_CODE_PREFIX + s;
        BigDecimal price = new BigDecimal("100.00");
        BigDecimal cost = new BigDecimal("40.00");
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, ?::flooring_type, ?, 'Terms source test charge', ?, ?) RETURNING charge_id",
                Long.class, STORE_SYD_CBD, flooringType, code, price, cost);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Terms source test charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code, price, cost, price, price, cost);
        clearJpaCache();
        return orderId;
    }

    /**
     * A bare quote_version of THIS order (only the V16 NOT NULL columns + its frozen terms), as the
     * source a Path A invoice row would reference. D.2 and D.3 never read the source quote itself (only
     * the invoice row's own V19 columns), so no real issue/accept chain is needed for these tests.
     */
    private long insertSourceQuoteVersionRow(long orderId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO quote_version (order_id, version_number, status, itemised, quote_total_ex_gst, "
                        + " quote_total_inc_gst, flooring_type_snapshot, terms_snapshot, accepted_at, "
                        + " accepted_customer_name, created_by_user_id) "
                        + "VALUES (?, 1, 'ACCEPTED', FALSE, 100.00, 110.00, 'SOFT', ?, "
                        + " TIMESTAMP '2026-10-01 10:15:30', 'Terms Tester', ?) RETURNING quote_version_id",
                Long.class, orderId, FROZEN_TERMS_SNAPSHOT, USER_LIAM);
    }

    // ---- requests ----

    /** D.1 Create (empty body) -> 201 v1; returns data.invoice (exact key set checked). */
    private JsonNode createInvoiceReturningDetail(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(post(invoicesUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Invoice created."))
                .andExpect(jsonPath("$.data.invoice.version_number").value(1))
                .andReturn();
        clearJpaCache();
        return invoiceDetail(result);
    }

    /** D.2 Rewrite (empty body) -> 201 with the expected version; returns the raw result. */
    private MvcResult rewrite(long orderId, int expectedVersion) throws Exception {
        MvcResult result = mockMvc.perform(post(rewriteUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Invoice rewritten."))
                .andExpect(jsonPath("$.data.invoice.version_number").value(expectedVersion))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(false))
                .andReturn();
        clearJpaCache();
        return result;
    }

    /** D.3 GET current -> 200 (no top-level message); returns the raw result. */
    private MvcResult getCurrent(long orderId) throws Exception {
        clearJpaCache();
        return mockMvc.perform(get(currentInvoiceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andReturn();
    }

    // ---- assertions ----

    /** data.invoice of an InvoiceDetail response: the exact key set, and no internal value leaks. */
    private static JsonNode invoiceDetail(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode invoice = JSON.readTree(body).path("data").path("invoice");
        Assertions.assertTrue(invoice.isObject(), () -> "data.invoice must be an object: " + body);
        Set<String> keys = new TreeSet<>();
        invoice.fieldNames().forEachRemaining(keys::add);
        Assertions.assertEquals(new TreeSet<>(INVOICE_DETAIL_KEYS), keys,
                "InvoiceDetail must carry exactly its contract keys (terms_html + terms_source always present)");
        for (String internal : List.of("source_quote_version_id", "terms_snapshot", "stored_file_id",
                "accepted_signature_file_id", "storage_path", "/uploads/")) {
            Assertions.assertFalse(body.contains(internal), () -> "must not leak " + internal + ": " + body);
        }
        return invoice;
    }

    /** terms_source == expectedSource; terms_html == expectedTermsHtml, or PRESENT as JSON null when null. */
    private static void assertTerms(JsonNode invoice, String expectedSource, String expectedTermsHtml, String label) {
        JsonNode source = invoice.get("terms_source");
        Assertions.assertTrue(source != null && source.isTextual(),
                () -> label + ": terms_source must be a JSON string: " + invoice);
        Assertions.assertEquals(expectedSource, source.asText(), () -> label + ": terms_source");
        Assertions.assertTrue(invoice.has("terms_html"), () -> label + ": terms_html must always be present: " + invoice);
        JsonNode html = invoice.get("terms_html");
        if (expectedTermsHtml == null) {
            Assertions.assertTrue(html.isNull(), () -> label + ": terms_html must be JSON null but was " + html);
        } else {
            Assertions.assertTrue(html.isTextual(), () -> label + ": terms_html must be a JSON string: " + html);
            Assertions.assertEquals(expectedTermsHtml, html.asText(), () -> label + ": terms_html");
        }
    }

    private Map<String, Object> v19Columns(long invoiceId) {
        return jdbcTemplate.queryForMap(
                "SELECT source_quote_version_id, terms_snapshot FROM invoice WHERE invoice_id = ?", invoiceId);
    }

    private void assertV19ColumnsNull(long invoiceId, String label) {
        Map<String, Object> v19 = v19Columns(invoiceId);
        Assertions.assertNull(v19.get("source_quote_version_id"), () -> label + ": source_quote_version_id must be NULL");
        Assertions.assertNull(v19.get("terms_snapshot"), () -> label + ": terms_snapshot must be NULL");
    }

    /** The invoice version's PDF read from disk, checked against its stored_file row. */
    private byte[] invoicePdfOnDisk(long invoiceId) throws IOException {
        Map<String, Object> file = jdbcTemplate.queryForMap(
                "SELECT sf.file_name, sf.storage_path, sf.mime_type, sf.file_size FROM invoice i "
                        + "JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id WHERE i.invoice_id = ?",
                invoiceId);
        Assertions.assertEquals("application/pdf", file.get("mime_type"));
        String storagePath = (String) file.get("storage_path");
        Assertions.assertTrue(diskFileExists(storagePath), () -> "the invoice PDF must exist on disk: " + storagePath);
        byte[] bytes = Files.readAllBytes(tempStorageDir.resolve(storagePath.substring(1)));
        Assertions.assertEquals(((Number) file.get("file_size")).longValue(), bytes.length,
                "stored file_size matches the bytes on disk");
        Assertions.assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII), "valid PDF header");
        return bytes;
    }

    private static int pdfPageCount(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        }
    }

    /** Whitespace-flattened text of one page (1-based), or of the whole document when page is 0. */
    private static String pdfText(byte[] pdf, int page) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            if (page > 0) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
            }
            return stripper.getText(document).replaceAll("\\s+", " ");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /** Terms on a dedicated page 2 (never page 1) carrying expectedText and never absentText; footer once. */
    private static void assertTermsPage(byte[] pdf, String expectedText, String absentText, String label)
            throws IOException {
        Assertions.assertEquals(2, pdfPageCount(pdf), () -> label + ": terms present -> a dedicated page 2");
        String page1 = pdfText(pdf, 1);
        String page2 = pdfText(pdf, 2);
        String all = pdfText(pdf, 0);
        Assertions.assertFalse(page1.contains("TERMS") || page1.contains(expectedText),
                () -> label + ": page 1 never carries the terms: " + page1);
        Assertions.assertTrue(page2.contains("TERMS"), () -> label + ": the terms heading must be on page 2: " + page2);
        Assertions.assertTrue(page2.contains(expectedText),
                () -> label + ": page 2 must carry [" + expectedText + "]: " + page2);
        Assertions.assertFalse(all.contains(absentText), () -> label + ": [" + absentText + "] must not render: " + all);
        Assertions.assertEquals(1, countOccurrences(all, PDF_FOOTER), () -> label + ": footer exactly once");
    }

    private static void assertNoTermsPage(byte[] pdf, String label) throws IOException {
        Assertions.assertEquals(1, pdfPageCount(pdf), () -> label + ": no terms -> a single page");
        String all = pdfText(pdf, 0);
        Assertions.assertFalse(all.contains("TERMS"), () -> label + ": no terms heading: " + all);
        Assertions.assertEquals(1, countOccurrences(all, PDF_FOOTER), () -> label + ": footer exactly once");
    }

    // ---- D.1 Create ----

    @Test
    void create_softOrder_returnsLiveSourceAndSanitizedSoftTerms_writesNullV19Columns_andRendersThoseTerms()
            throws Exception {
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        long orderId = insertInvoiceReadyOrder("SOFT");
        String expectedTerms = sanitized(LIVE_SOFT_TERMS_RAW);
        Assertions.assertNotEquals(LIVE_SOFT_TERMS_RAW, expectedTerms,
                "fixture: the raw soft terms carry markup the sanitizer strips");

        JsonNode invoice = createInvoiceReturningDetail(orderId);

        assertTerms(invoice, "LIVE", expectedTerms, "D.1 SOFT");
        long invoiceId = latestInvoiceId(orderId);
        assertV19ColumnsNull(invoiceId, "D.1 SOFT v1");
        assertTermsPage(invoicePdfOnDisk(invoiceId), SOFT_TERMS_TEXT, HARD_TERMS_TEXT, "D.1 SOFT PDF");
    }

    @Test
    void create_hardOrder_returnsLiveSourceAndSanitizedHardTerms_writesNullV19Columns_andRendersThoseTerms()
            throws Exception {
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        long orderId = insertInvoiceReadyOrder("HARD");
        String expectedTerms = sanitized(LIVE_HARD_TERMS_RAW);
        Assertions.assertNotEquals(LIVE_HARD_TERMS_RAW, expectedTerms,
                "fixture: the raw hard terms carry markup the sanitizer strips");

        JsonNode invoice = createInvoiceReturningDetail(orderId);

        assertTerms(invoice, "LIVE", expectedTerms, "D.1 HARD");
        long invoiceId = latestInvoiceId(orderId);
        assertV19ColumnsNull(invoiceId, "D.1 HARD v1");
        assertTermsPage(invoicePdfOnDisk(invoiceId), HARD_TERMS_TEXT, SOFT_TERMS_TEXT, "D.1 HARD PDF");
    }

    @Test
    void create_withNoLiveTermsForTheOrdersType_returnsPresentNullTermsHtml_andRendersNoTermsPage()
            throws Exception {
        // terms_soft unset (NULL), then blank, while terms_hard is set: a SOFT invoice never falls back to
        // the other type's terms (or the legacy block) - terms_html is PRESENT as JSON null.
        for (String softTerms : Arrays.asList(null, "   ")) {
            setTenantTerms(softTerms, LIVE_HARD_TERMS_RAW);
            long orderId = insertInvoiceReadyOrder("SOFT");

            MvcResult result = mockMvc.perform(post(invoicesUrl(orderId)).session(liamStore1Session())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.invoice.version_number").value(1))
                    .andReturn();
            clearJpaCache();

            String label = "D.1 SOFT with terms_soft [" + softTerms + "]";
            String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            Assertions.assertTrue(json.contains("\"terms_html\":null"), () -> label + ": null key present: " + json);
            Assertions.assertTrue(json.contains("\"terms_source\":\"LIVE\""), () -> label + ": " + json);
            assertTerms(invoiceDetail(result), "LIVE", null, label);
            long invoiceId = latestInvoiceId(orderId);
            assertV19ColumnsNull(invoiceId, label);
            assertNoTermsPage(invoicePdfOnDisk(invoiceId), label + " PDF");
        }
    }

    // ---- D.2 Rewrite ----

    @Test
    void rewrite_ofAQuoteSourcedCurrentInvoice_writesNullV19Columns_andSwitchesBackToTheLiveTerms()
            throws Exception {
        // A Path A row carries a source quote version + its frozen terms (and the inherited acceptance).
        // A manual Rewrite is Path B: it must write NULL/NULL (never carry the current row's V19 values)
        // and report / render the LIVE terms. The current row's V19 columns are set by SQL; D.2 reads only
        // the current invoice row, never the source quote.
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        long orderId = insertInvoiceReadyOrder("SOFT");
        createInvoiceReturningDetail(orderId);
        long v1 = latestInvoiceId(orderId);
        long sourceQuoteVersionId = insertSourceQuoteVersionRow(orderId);
        jdbcTemplate.update("UPDATE invoice SET source_quote_version_id = ?, terms_snapshot = ?, "
                        + "accepted_at = TIMESTAMP '2026-10-01 10:15:30', accepted_customer_name = 'Terms Tester' "
                        + "WHERE invoice_id = ?",
                sourceQuoteVersionId, FROZEN_TERMS_SNAPSHOT, v1);
        clearJpaCache();
        // Precondition through D.3: the quote-sourced current row reads QUOTE + its frozen terms, not live.
        assertTerms(invoiceDetail(getCurrent(orderId)), "QUOTE", FROZEN_TERMS_SNAPSHOT, "D.3 quote-sourced v1");

        JsonNode rewritten = invoiceDetail(rewrite(orderId, 2));

        assertTerms(rewritten, "LIVE", sanitized(LIVE_SOFT_TERMS_RAW), "D.2 rewrite of a quote-sourced invoice");
        Assertions.assertTrue(rewritten.get("accepted_at").isNull(), "the rewrite clears the inherited acceptance");
        long v2 = latestInvoiceId(orderId);
        assertV19ColumnsNull(v2, "D.2 v2");
        // Append-only: v1 keeps its quote source + frozen terms untouched.
        Map<String, Object> v1Columns = v19Columns(v1);
        Assertions.assertEquals(sourceQuoteVersionId, ((Number) v1Columns.get("source_quote_version_id")).longValue());
        Assertions.assertEquals(FROZEN_TERMS_SNAPSHOT, v1Columns.get("terms_snapshot"));
        // The rewritten PDF renders the live SOFT terms, never the frozen ones.
        assertTermsPage(invoicePdfOnDisk(v2), SOFT_TERMS_TEXT, FROZEN_TERMS_TEXT, "D.2 PDF");
        // D.3 now resolves the rewritten (LIVE) current invoice.
        assertTerms(invoiceDetail(getCurrent(orderId)), "LIVE", sanitized(LIVE_SOFT_TERMS_RAW), "D.3 after D.2");
    }

    @Test
    void rewrite_hardOrderAfterTheTenantClearsItsTerms_returnsPresentNullTermsHtml_andRendersNoTermsPage()
            throws Exception {
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        long orderId = insertInvoiceReadyOrder("HARD");
        assertTerms(createInvoiceReturningDetail(orderId), "LIVE", sanitized(LIVE_HARD_TERMS_RAW), "D.1 HARD");
        long v1 = latestInvoiceId(orderId);
        assertTermsPage(invoicePdfOnDisk(v1), HARD_TERMS_TEXT, SOFT_TERMS_TEXT, "D.1 HARD PDF");

        setTenantTerms(null, null);
        MvcResult result = rewrite(orderId, 2);

        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertTrue(json.contains("\"terms_html\":null"), () -> "D.2: null key present: " + json);
        assertTerms(invoiceDetail(result), "LIVE", null, "D.2 HARD with no live terms");
        long v2 = latestInvoiceId(orderId);
        assertV19ColumnsNull(v2, "D.2 v2");
        assertV19ColumnsNull(v1, "D.1 v1");
        assertNoTermsPage(invoicePdfOnDisk(v2), "D.2 HARD PDF with no live terms");
    }

    // ---- D.3 GET current ----

    @Test
    void getCurrent_pathBInvoice_returnsTheBusinessCurrentLiveTermsPerType_notTermsFromCreateTime()
            throws Exception {
        setTenantTerms(LIVE_SOFT_TERMS_RAW, LIVE_HARD_TERMS_RAW);
        long softOrder = insertInvoiceReadyOrder("SOFT");
        long hardOrder = insertInvoiceReadyOrder("HARD");
        createInvoiceReturningDetail(softOrder);
        createInvoiceReturningDetail(hardOrder);

        assertTerms(invoiceDetail(getCurrent(softOrder)), "LIVE", sanitized(LIVE_SOFT_TERMS_RAW), "D.3 SOFT");
        assertTerms(invoiceDetail(getCurrent(hardOrder)), "LIVE", sanitized(LIVE_HARD_TERMS_RAW), "D.3 HARD");

        // The business edits its terms: a LIVE invoice reads the CURRENT terms (nothing froze at create).
        setTenantTerms(UPDATED_SOFT_TERMS_RAW, UPDATED_HARD_TERMS_RAW);
        assertTerms(invoiceDetail(getCurrent(softOrder)), "LIVE", sanitized(UPDATED_SOFT_TERMS_RAW),
                "D.3 SOFT after the terms edit");
        assertTerms(invoiceDetail(getCurrent(hardOrder)), "LIVE", sanitized(UPDATED_HARD_TERMS_RAW),
                "D.3 HARD after the terms edit");

        // The business clears its terms: terms_html stays PRESENT as JSON null.
        setTenantTerms(null, null);
        for (long orderId : new long[] {softOrder, hardOrder}) {
            MvcResult result = getCurrent(orderId);
            String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            Assertions.assertTrue(json.contains("\"terms_html\":null"), () -> "D.3: null key present: " + json);
            assertTerms(invoiceDetail(result), "LIVE", null, "D.3 order " + orderId + " with no live terms");
        }

        // D.3 is a read: no version appended, the V19 columns untouched (NULL).
        for (long orderId : new long[] {softOrder, hardOrder}) {
            Assertions.assertEquals(1, countInvoices(orderId));
            assertV19ColumnsNull(latestInvoiceId(orderId), "order " + orderId + " v1");
        }
    }
}
