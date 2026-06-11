package com.flooring.salesportal.order;

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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Phase 12 Chunk 4 payments (Branch D): D.6 GET /orders/{orderId}/payments (list
 * payments + current official payment summary) and D.7 POST /orders/{orderId}/payments (record a manual
 * payment and atomically regenerate the current invoice carrying forward the latest official invoice's
 * sale snapshot).
 *
 * <p>Mirrors {@code OrderInvoiceControllerTest}: {@code @SpringBootTest @Transactional}, MockMvc, a
 * {@code MockHttpSession} carrying the seeded Liam / business 1 / store 1 identity, and
 * {@code JdbcTemplate} for data tweaks + persistence assertions (rolled back with the test transaction).
 * Invoice PDFs are written to a JUnit {@link TempDir} (via {@code app.storage.base-dir} bound through
 * {@link DynamicPropertySource}), so nothing is written into the repo.
 *
 * <p>Seed (V4 + V6): order 1 ({@code SYD-CBD.LC1.00001}, SOFT/ACCEPTED, store 1, business 1) is fully
 * populated — customer, install + billing addresses, live subtotal 840.00 / inc-GST 924.00, ONE seeded
 * payment (id 1, 500.00 EFTPOS, ref {@code EFTPOS-20260414}), and ONE seeded invoice (id 1, v1: ex
 * 840.00 / inc 924.00 / paid 500.00 / balance 424.00, due 2026-04-29). Order 2 = header-only LEAD (no
 * invoice, no payments). Order 5 = store 2 (cross-store), order 9 = business 2 (cross-business), order
 * 99999 = nonexistent.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderPaymentControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final long ORDER_FULL = 1L;            // has a seeded invoice (balance 424.00) + 1 payment
    private static final long ORDER_EMPTY = 2L;           // header-only LEAD (no invoice, no payments)
    private static final long ORDER_OTHER_STORE = 5L;     // store 2, business 1 (cross-store)
    private static final long ORDER_OTHER_BUSINESS = 9L;  // business 2 (cross-business)
    private static final long ORDER_DOES_NOT_EXIST = 99_999L;

    private static final String ORDER_FULL_NUMBER = "SYD-CBD.LC1.00001";
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

    private static String paymentsUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/payments";
    }

    private static String paymentsUrl(String slug, Object orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/payments";
    }

    private static String currentFileUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/invoices/current/file";
    }

    private void laidOrder(long orderId) {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
    }

    /** Remove order 1's seeded invoice (+ its PDF stored_file) so it has payments but NO invoice. */
    private void clearSeededInvoice() {
        Long storedFileId = jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE order_id = ? ORDER BY version_number DESC LIMIT 1",
                Long.class, ORDER_FULL);
        jdbcTemplate.update("DELETE FROM invoice WHERE order_id = ?", ORDER_FULL);
        if (storedFileId != null) {
            jdbcTemplate.update("DELETE FROM stored_file WHERE stored_file_id = ?", storedFileId);
        }
    }

    /** Insert a payment row with an explicit created_at so list ordering is deterministic. */
    private void insertPayment(long orderId, String method, String amount, String reference, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payment_transaction (order_id, payment_method, amount, payment_reference, created_at) "
                        + "VALUES (?, CAST(? AS payment_method), ?, ?, CAST(? AS timestamp))",
                orderId, method, new BigDecimal(amount), reference, createdAt);
    }

    private int countPayments(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transaction WHERE order_id = ?", Integer.class, orderId);
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

    private String invoiceText(String column, long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM invoice WHERE invoice_id = ?", String.class, invoiceId);
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

    private String body(String method, String amount, String reference) {
        String ref = reference == null ? "null" : "\"" + reference + "\"";
        return "{\"payment_method\":\"" + method + "\",\"amount\":" + amount + ",\"payment_reference\":" + ref + "}";
    }

    // ---- Phase 13 helpers (acceptance fields / dashboard mirror) ----

    private Timestamp orderLastEmailedAt(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId);
    }

    private void setOrderLastEmailedAt(long orderId, String timestamp) {
        jdbcTemplate.update(
                "UPDATE sales_order SET last_emailed_at = CAST(? AS timestamp) WHERE order_id = ?",
                timestamp, orderId);
    }

    // ================================================================
    // D.6 GET /payments — happy path / summary / ordering
    // ================================================================

    @Test
    void list_seededOrder_returnsPaymentsWithSummaryAndPagination() throws Exception {
        // Order 1: one seeded payment (500.00 EFTPOS, ref EFTPOS-20260414) + seeded invoice (balance 424.00).
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payments.length()").value(1))
                .andExpect(jsonPath("$.data.payments[0].payment_transaction_id").isNumber())
                .andExpect(jsonPath("$.data.payments[0].payment_method").value("EFTPOS"))
                .andExpect(jsonPath("$.data.payments[0].amount").value(500.00))
                .andExpect(jsonPath("$.data.payments[0].payment_reference").value("EFTPOS-20260414"))
                .andExpect(jsonPath("$.data.payments[0].created_at").exists())
                .andExpect(jsonPath("$.data.payment_summary.total_paid").value(500.00))
                .andExpect(jsonPath("$.data.payment_summary.balance_due").value(424.00))
                .andExpect(jsonPath("$.data.pagination.page").value(1))
                .andExpect(jsonPath("$.data.pagination.page_size").value(20))
                .andExpect(jsonPath("$.data.pagination.total_items").value(1))
                .andExpect(jsonPath("$.data.pagination.total_pages").value(1));
    }

    @Test
    void list_returnsPaymentsNewestFirst() throws Exception {
        // Order 2 has no seeded payments — insert three with controlled created_at; newest must come first.
        insertPayment(ORDER_EMPTY, "CASH", "10.00", "P1-OLDEST", "2020-01-01 10:00:00");
        insertPayment(ORDER_EMPTY, "EFTPOS", "20.00", "P2-MIDDLE", "2020-01-02 10:00:00");
        insertPayment(ORDER_EMPTY, "BANK_TRANSFER", "30.00", "P3-NEWEST", "2020-01-03 10:00:00");

        mockMvc.perform(get(paymentsUrl(ORDER_EMPTY)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payments.length()").value(3))
                .andExpect(jsonPath("$.data.payments[0].payment_reference").value("P3-NEWEST"))
                .andExpect(jsonPath("$.data.payments[1].payment_reference").value("P2-MIDDLE"))
                .andExpect(jsonPath("$.data.payments[2].payment_reference").value("P1-OLDEST"))
                .andExpect(jsonPath("$.data.payment_summary.total_paid").value(60.00));
    }

    @Test
    void list_noPayments_returnsEmptyListAndTotalPaidZero() throws Exception {
        // Order 2 has no payments. payments = [] and total_paid = 0.00.
        mockMvc.perform(get(paymentsUrl(ORDER_EMPTY)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payments").isArray())
                .andExpect(jsonPath("$.data.payments.length()").value(0))
                .andExpect(jsonPath("$.data.payment_summary.total_paid").value(0.00))
                .andExpect(jsonPath("$.data.pagination.total_items").value(0))
                .andExpect(jsonPath("$.data.pagination.total_pages").value(0));
    }

    @Test
    void list_noInvoice_balanceDueIsNull() throws Exception {
        // Order 2 has no invoice -> balance_due is JSON null (present key), total_paid still the literal sum.
        insertPayment(ORDER_EMPTY, "CASH", "25.00", null, "2020-01-01 10:00:00");
        mockMvc.perform(get(paymentsUrl(ORDER_EMPTY)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment_summary.total_paid").value(25.00))
                .andExpect(jsonPath("$.data.payment_summary.balance_due").doesNotExist())
                .andExpect(jsonPath("$.data.payment_summary").exists());
    }

    @Test
    void list_noInvoice_balanceDueKeyPresentAsNull() throws Exception {
        // jsonPath .doesNotExist() treats JSON null as absent; assert on the raw body that the key is null.
        MvcResult result = mockMvc.perform(get(paymentsUrl(ORDER_EMPTY)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"balance_due\":null"),
                () -> "balance_due must be present as null when no invoice: " + json);
    }

    // ---- pagination -------------------------------------------------

    @Test
    void list_paginationDefaults_pageOneSizeTwenty() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pagination.page").value(1))
                .andExpect(jsonPath("$.data.pagination.page_size").value(20));
    }

    @Test
    void list_paginationPageAndSize_work() throws Exception {
        insertPayment(ORDER_EMPTY, "CASH", "10.00", "A", "2020-01-01 10:00:00");
        insertPayment(ORDER_EMPTY, "CASH", "10.00", "B", "2020-01-02 10:00:00");
        insertPayment(ORDER_EMPTY, "CASH", "10.00", "C", "2020-01-03 10:00:00");

        // page 1, size 2 -> the two newest (C, B); total_items 3, total_pages 2.
        mockMvc.perform(get(paymentsUrl(ORDER_EMPTY)).session(liamStore1Session())
                        .param("page", "1").param("page_size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payments.length()").value(2))
                .andExpect(jsonPath("$.data.payments[0].payment_reference").value("C"))
                .andExpect(jsonPath("$.data.payments[1].payment_reference").value("B"))
                .andExpect(jsonPath("$.data.pagination.page").value(1))
                .andExpect(jsonPath("$.data.pagination.page_size").value(2))
                .andExpect(jsonPath("$.data.pagination.total_items").value(3))
                .andExpect(jsonPath("$.data.pagination.total_pages").value(2));

        // page 2, size 2 -> the remaining oldest (A).
        mockMvc.perform(get(paymentsUrl(ORDER_EMPTY)).session(liamStore1Session())
                        .param("page", "2").param("page_size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payments.length()").value(1))
                .andExpect(jsonPath("$.data.payments[0].payment_reference").value("A"))
                .andExpect(jsonPath("$.data.pagination.page").value(2));
    }

    @Test
    void list_invalidPage_returns400() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamStore1Session()).param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void list_invalidPageSize_returns400() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamStore1Session()).param("page_size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void list_pageSizeAboveMax_returns400() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamStore1Session()).param("page_size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void list_nonNumericPage_returns400() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamStore1Session()).param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void list_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(get(paymentsUrl("abc")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ---- LAID / no message / no leak --------------------------------

    @Test
    void list_allowedWhenLaid() throws Exception {
        laidOrder(ORDER_FULL);
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payments.length()").value(1));
    }

    @Test
    void list_responseHasNoTopLevelMessageField() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payments").exists())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void list_doesNotExposeForbiddenOrInternalFields() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payments[0].gateway_transaction_id").doesNotExist())
                .andExpect(jsonPath("$.data.payments[0].response_status").doesNotExist())
                .andExpect(jsonPath("$.data.payments[0].response_message").doesNotExist())
                .andExpect(jsonPath("$.data.payments[0].order_id").doesNotExist())
                .andExpect(jsonPath("$.data.payments[0].business_id").doesNotExist())
                .andExpect(jsonPath("$.data.payments[0].store_id").doesNotExist())
                .andExpect(jsonPath("$.data.payments[0].user_id").doesNotExist());
    }

    // ---- GET scoping / auth (no existence leak) ---------------------

    @Test
    void list_noSession_returns401() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void list_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_FULL)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void list_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_OTHER_STORE)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void list_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_OTHER_BUSINESS)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void list_nonexistentOrder_returns404() throws Exception {
        mockMvc.perform(get(paymentsUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void list_crossBusinessSlug_returns404NotFound() throws Exception {
        mockMvc.perform(get(paymentsUrl(SLUG_PREMIER, ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ================================================================
    // D.7 POST /payments — happy path
    // ================================================================

    @Test
    void record_validPayment_returns201_withAllParts() throws Exception {
        // Order 1: latest invoice balance 424.00, prior paid 500.00. Pay 100.00 -> paid 600.00, balance 324.00.
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EFTPOS", "100.00", "EFTPOS-20260422")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Payment recorded. Current invoice updated."))
                // payment_transaction (E.3)
                .andExpect(jsonPath("$.data.payment_transaction.payment_transaction_id").isNumber())
                .andExpect(jsonPath("$.data.payment_transaction.payment_method").value("EFTPOS"))
                .andExpect(jsonPath("$.data.payment_transaction.amount").value(100.00))
                .andExpect(jsonPath("$.data.payment_transaction.payment_reference").value("EFTPOS-20260422"))
                .andExpect(jsonPath("$.data.payment_transaction.created_at").exists())
                // payment_summary (E.4)
                .andExpect(jsonPath("$.data.payment_summary.total_paid").value(600.00))
                .andExpect(jsonPath("$.data.payment_summary.balance_due").value(324.00))
                // current_invoice (E.1 current_invoice_summary)
                .andExpect(jsonPath("$.data.current_invoice.invoice_id").isNumber())
                .andExpect(jsonPath("$.data.current_invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.current_invoice.invoice_date").exists())
                .andExpect(jsonPath("$.data.current_invoice.due_date").value("2026-04-29")) // carried forward
                .andExpect(jsonPath("$.data.current_invoice.sale_price_inc_gst").value(924.00))
                .andExpect(jsonPath("$.data.current_invoice.total_paid").value(600.00))
                .andExpect(jsonPath("$.data.current_invoice.balance_due").value(324.00))
                .andExpect(jsonPath("$.data.current_invoice.created_by_user_id").value(1))
                .andExpect(jsonPath("$.data.current_invoice.created_at").exists())
                .andExpect(jsonPath("$.data.current_invoice.pdf_download_path").value(EXPECTED_PDF_PATH));
    }

    @Test
    void record_createsNewInvoiceVersionMaxPlusOne() throws Exception {
        Assertions.assertEquals(1, countInvoices(ORDER_FULL));
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isCreated());

        Assertions.assertEquals(2, countInvoices(ORDER_FULL), "payment appends a new invoice version (v1 retained)");
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, ORDER_FULL);
        Assertions.assertEquals(2, maxVersion);
        Assertions.assertEquals(2, countPayments(ORDER_FULL), "the new payment is persisted (seed + new)");
    }

    @Test
    void record_currentInvoiceAfterPaymentIsTheNewVersion() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isCreated());
        long newId = latestInvoiceId(ORDER_FULL);

        mockMvc.perform(get("/api/v1/" + SLUG_AUSSIE + "/orders/" + ORDER_FULL + "/invoices/current")
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice.invoice_id").value((int) newId))
                .andExpect(jsonPath("$.data.invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.invoice.total_paid").value(600.00))
                .andExpect(jsonPath("$.data.invoice.balance_due").value(324.00));
    }

    @Test
    void record_currentInvoiceFileAfterPaymentIsTheNewPdf() throws Exception {
        // The seeded v1 PDF was never written to the test temp dir; the payment writes a real v2 PDF.
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("invoice-" + ORDER_FULL_NUMBER + "-v2.pdf")))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        Assertions.assertTrue(body.length > 0, "PDF body must be non-empty");
        Assertions.assertEquals("%PDF-", new String(body, 0, 5, StandardCharsets.US_ASCII),
                "current/file must stream the new payment-version PDF");
    }

    @Test
    void record_totalPaidIncludesPriorPayments() throws Exception {
        // Prior paid 500.00 (seed) + new 200.00 -> 700.00; balance 924.00 - 700.00 = 224.00.
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("BANK_TRANSFER", "200.00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.payment_summary.total_paid").value(700.00))
                .andExpect(jsonPath("$.data.payment_summary.balance_due").value(224.00))
                .andExpect(jsonPath("$.data.current_invoice.balance_due").value(224.00));
    }

    @Test
    void record_exactBalance_allowed_balanceDueZero() throws Exception {
        // Latest balance is 424.00; paying exactly 424.00 is allowed and drives balance_due to 0.00.
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CREDIT_CARD", "424.00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.payment_summary.total_paid").value(924.00))
                .andExpect(jsonPath("$.data.payment_summary.balance_due").value(0.00))
                .andExpect(jsonPath("$.data.current_invoice.balance_due").value(0.00));
    }

    @Test
    void record_carriesForwardSnapshot_doesNotUseUnsentLiveEdits() throws Exception {
        // CRITICAL: mutate the live order (details + a price adjustment that would make live inc-GST 1000.00)
        // BEFORE recording the payment. The payment-version must carry forward the latest invoice's sale
        // snapshot (details + 924.00/840.00), NOT the unsent live edits.
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'CHANGED LIVE - NOT OFFICIAL', "
                + "price_adjustment_inc_gst = 76.00 WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("EFTPOS", "100.00", null)))
                .andExpect(status().isCreated())
                // carried forward inc-GST (924.00), NOT the live-adjusted 1000.00; balance 924 - 600 = 324.
                .andExpect(jsonPath("$.data.current_invoice.sale_price_inc_gst").value(924.00))
                .andExpect(jsonPath("$.data.current_invoice.balance_due").value(324.00));

        long v2 = latestInvoiceId(ORDER_FULL);
        // The new version's stored sale snapshot is carried forward, not re-read from live order state.
        assertMoney("924.00", invoiceMoney("sale_price_inc_gst", v2));
        assertMoney("840.00", invoiceMoney("sale_price_ex_gst", v2)); // NOT 909.09 (live-adjusted)
        String snapshot = invoiceText("details_of_sale_snapshot", v2);
        Assertions.assertTrue(snapshot.startsWith("Supply and install plush carpet"),
                () -> "details snapshot must be carried forward, not the live edit: " + snapshot);
        Assertions.assertFalse(snapshot.contains("CHANGED LIVE"), "must NOT pick up the unsent live edit");
    }

    @Test
    void record_carriesForwardDueDateAndDetailsAndSalePrice() throws Exception {
        // due_date / details / sale price all carried forward from the latest invoice (v1).
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "50.00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.current_invoice.due_date").value("2026-04-29"))
                .andExpect(jsonPath("$.data.current_invoice.sale_price_inc_gst").value(924.00));

        long v2 = latestInvoiceId(ORDER_FULL);
        Assertions.assertEquals("2026-04-29", jdbcTemplate.queryForObject(
                "SELECT due_date::text FROM invoice WHERE invoice_id = ?", String.class, v2));
        Assertions.assertTrue(invoiceText("details_of_sale_snapshot", v2).startsWith("Supply and install plush carpet"));
        assertMoney("840.00", invoiceMoney("sale_price_ex_gst", v2));
    }

    @Test
    void record_allowedWhenLaid() throws Exception {
        laidOrder(ORDER_FULL);
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.current_invoice.version_number").value(2));
        Assertions.assertEquals(2, countInvoices(ORDER_FULL), "payment creates a new version even when LAID");
    }

    @Test
    void record_paymentReferenceOmitted_persistsNull() throws Exception {
        // No payment_reference key at all -> persists null and the response key is null.
        MvcResult result = mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\":\"CASH\",\"amount\":100.00}"))
                .andExpect(status().isCreated())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"payment_reference\":null"),
                () -> "omitted payment_reference must serialize as null: " + json);

        Long newPaymentId = jdbcTemplate.queryForObject(
                "SELECT MAX(payment_transaction_id) FROM payment_transaction WHERE order_id = ?", Long.class, ORDER_FULL);
        String ref = jdbcTemplate.queryForObject(
                "SELECT payment_reference FROM payment_transaction WHERE payment_transaction_id = ?",
                String.class, newPaymentId);
        Assertions.assertNull(ref, "omitted payment_reference must persist as SQL NULL");
    }

    @Test
    void record_paymentReferenceExplicitNull_persistsNull() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\":\"CASH\",\"amount\":100.00,\"payment_reference\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.payment_transaction.payment_reference").doesNotExist());

        Long newPaymentId = jdbcTemplate.queryForObject(
                "SELECT MAX(payment_transaction_id) FROM payment_transaction WHERE order_id = ?", Long.class, ORDER_FULL);
        String ref = jdbcTemplate.queryForObject(
                "SELECT payment_reference FROM payment_transaction WHERE payment_transaction_id = ?",
                String.class, newPaymentId);
        Assertions.assertNull(ref);
    }

    @Test
    void record_doesNotExposeForbiddenOrInternalFields() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("EFTPOS", "100.00", "REF-1")))
                .andExpect(status().isCreated())
                // gateway / internal fields never exposed on the payment row
                .andExpect(jsonPath("$.data.payment_transaction.gateway_transaction_id").doesNotExist())
                .andExpect(jsonPath("$.data.payment_transaction.response_status").doesNotExist())
                .andExpect(jsonPath("$.data.payment_transaction.response_message").doesNotExist())
                .andExpect(jsonPath("$.data.payment_transaction.order_id").doesNotExist())
                // current_invoice uses the summary shape: NO order_id / details / ex-GST / stored_file_id / path
                .andExpect(jsonPath("$.data.current_invoice.sale_price_ex_gst").doesNotExist())
                .andExpect(jsonPath("$.data.current_invoice.details_of_sale_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data.current_invoice.order_id").doesNotExist())
                .andExpect(jsonPath("$.data.current_invoice.stored_file_id").doesNotExist())
                .andExpect(jsonPath("$.data.current_invoice.storage_path").doesNotExist());
    }

    // ================================================================
    // D.7 POST /payments — business rules
    // ================================================================

    @Test
    void record_amountExceedsBalance_returns422_persistsNothing() throws Exception {
        // Latest balance is 424.00; 425.00 exceeds it -> 422, no payment and no invoice version created.
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("EFTPOS", "425.00", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_EXCEEDS_BALANCE"));
        Assertions.assertEquals(1, countPayments(ORDER_FULL), "no payment persisted on overpayment");
        Assertions.assertEquals(1, countInvoices(ORDER_FULL), "no new invoice version on overpayment");
    }

    @Test
    void record_noInvoice_returns422_invoiceRequired_persistsNothing() throws Exception {
        // Order 1 with its invoice cleared (its seed payment remains) -> 422 INVOICE_REQUIRED, nothing added.
        clearSeededInvoice();
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("EFTPOS", "100.00", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_REQUIRED"));
        Assertions.assertEquals(1, countPayments(ORDER_FULL), "no new payment when invoice is required");
        Assertions.assertEquals(0, countInvoices(ORDER_FULL));
    }

    @Test
    void record_bodyValidationPrecedesInvoiceRequired() throws Exception {
        // Gate ordering: a 400 body validation error precedes the 422 INVOICE_REQUIRED business rule.
        clearSeededInvoice();
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("PAYPAL", "100.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("payment_method"));
    }

    // ================================================================
    // D.7 POST /payments — body validation (400)
    // ================================================================

    @Test
    void record_invalidPaymentMethod_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("PAYPAL", "100.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("payment_method"));
    }

    @Test
    void record_missingPaymentMethod_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("payment_method"));
    }

    @Test
    void record_missingAmount_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payment_method\":\"CASH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("amount"));
    }

    @Test
    void record_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "0.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("amount"));
    }

    @Test
    void record_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "-50.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("amount"));
    }

    @Test
    void record_blankPaymentReference_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\":\"CASH\",\"amount\":100.00,\"payment_reference\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("payment_reference"));
    }

    @Test
    void record_paymentReferenceAt100Chars_accepted() throws Exception {
        // payment_transaction.payment_reference is VARCHAR(100): a 100-char reference is at the limit.
        String ref = "X".repeat(100);
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", ref)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.payment_transaction.payment_reference").value(ref));
    }

    @Test
    void record_paymentReferenceOver100Chars_returns400() throws Exception {
        // 101 chars exceeds VARCHAR(100) -> a clean 400 VALIDATION_FAILED, not a DB value-too-long 500.
        String ref = "X".repeat(101);
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", ref)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("payment_reference"));
    }

    @Test
    void record_paymentReferenceOver100Chars_persistsNothing() throws Exception {
        // The over-length value must be rejected before the inserts: no payment row and no invoice version.
        String ref = "X".repeat(101);
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", ref)))
                .andExpect(status().isBadRequest());
        Assertions.assertEquals(1, countPayments(ORDER_FULL), "no payment persisted on over-length reference");
        Assertions.assertEquals(1, countInvoices(ORDER_FULL), "no new invoice version on over-length reference");
    }

    @Test
    void record_forbiddenGatewayField_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\":\"CASH\",\"amount\":100.00,"
                                + "\"gateway_transaction_id\":\"ch_123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("gateway_transaction_id"));
    }

    @Test
    void record_forbiddenResponseStatusAndMessage_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\":\"CASH\",\"amount\":100.00,"
                                + "\"response_status\":\"APPROVED\",\"response_message\":\"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void record_unknownField_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\":\"CASH\",\"amount\":100.00,\"nonsense\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("nonsense"));
    }

    @Test
    void record_malformedJson_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void record_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(post(paymentsUrl("abc")).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ================================================================
    // D.7 POST /payments — scoping / auth (no existence leak)
    // ================================================================

    @Test
    void record_noSession_returns401() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL))
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void record_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void record_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_OTHER_STORE)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void record_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_OTHER_BUSINESS)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void record_nonexistentOrder_returns404() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void record_crossBusinessSlug_returns404NotFound() throws Exception {
        mockMvc.perform(post(paymentsUrl(SLUG_PREMIER, ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void record_crossStoreOrderWithBadBody_returns404_existenceWins() throws Exception {
        // Gate ordering: the 404 scope check precedes body validation, so a cross-store order with a bad
        // body is still 404 (never 400) — no existence leak.
        mockMvc.perform(post(paymentsUrl(ORDER_OTHER_STORE)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{not valid json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // ================================================================
    // Atomicity / rollback
    // ================================================================

    @Test
    void record_transactionRollback_removesPdfAndRollsBackPaymentAndInvoice() throws Exception {
        // The payment writes a new invoice version + PDF on disk inside the open transaction. On a rollback
        // the cleanup hook must delete the PDF and the payment + invoice rows must be rolled back.
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isCreated());

        long v2 = latestInvoiceId(ORDER_FULL);
        Long storedFileId = jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE invoice_id = ?", Long.class, v2);
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
        Assertions.assertTrue(diskFileExists(storagePath), "PDF written by the payment");
        Assertions.assertEquals(2, countPayments(ORDER_FULL));
        Assertions.assertEquals(2, countInvoices(ORDER_FULL));

        TestTransaction.flagForRollback();
        TestTransaction.end();

        Assertions.assertEquals(1, countPayments(ORDER_FULL), "payment rolled back to the seed");
        Assertions.assertEquals(1, countInvoices(ORDER_FULL), "invoice version rolled back to the seed");
        Assertions.assertFalse(diskFileExists(storagePath), "PDF removed on rollback (no orphan)");
    }

    // ================================================================
    // Phase 13 foundation — acceptance/email fields + dashboard mirror on the payment version
    // ================================================================

    @Test
    void record_currentInvoiceCarriesUnsignedAcceptanceAndEmailFields() throws Exception {
        // The E.1 current_invoice summary in the D.7 response carries the five Phase 13 fields. The
        // seeded invoice here is UNACCEPTED, so the payment-created version stays unaccepted with all
        // five resolving to null/false (the accepted-invoice carry-forward path is covered in
        // InvoiceAcceptanceControllerTest).
        MvcResult result = mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("EFTPOS", "100.00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.current_invoice.accepted_signature_present").value(false))
                .andReturn();

        // The five fields are ALWAYS present; null values serialize as null (jsonPath doesNotExist()
        // treats JSON null as absent, so assert on the raw body — these keys exist only on
        // current_invoice in this response).
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
    void record_newVersionPersistsNullAcceptanceColumns() throws Exception {
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isCreated());

        long v2 = latestInvoiceId(ORDER_FULL);
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT accepted_at FROM invoice WHERE invoice_id = ?", Timestamp.class, v2));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT accepted_customer_name FROM invoice WHERE invoice_id = ?", String.class, v2));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT accepted_signature_file_id FROM invoice WHERE invoice_id = ?", Long.class, v2));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM invoice WHERE invoice_id = ?", Timestamp.class, v2));
    }

    @Test
    void record_resetsSalesOrderLastEmailedAtMirrorToNull() throws Exception {
        // Phase 13 §11.1: the payment-created version starts unemailed, so the dashboard mirror is set
        // to that new version's null — never left showing a previous version's emailed time.
        setOrderLastEmailedAt(ORDER_FULL, "2026-01-05 09:30:00");

        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("CASH", "100.00", null)))
                .andExpect(status().isCreated());

        Assertions.assertNull(orderLastEmailedAt(ORDER_FULL), "mirror reset to the new version's null value");
    }

    @Test
    void record_rejectedPayment_leavesMirrorUntouched() throws Exception {
        // No new version (overpayment rejected) -> no mirror write.
        setOrderLastEmailedAt(ORDER_FULL, "2026-01-05 09:30:00");

        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body("EFTPOS", "425.00", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_EXCEEDS_BALANCE"));

        Assertions.assertNotNull(orderLastEmailedAt(ORDER_FULL), "mirror unchanged when no version is created");
    }

    // ================================================================
    // Scope guard: no Stripe / edit / delete / by-id endpoint creep
    // ================================================================

    @Test
    void noPaymentEditDeleteOrByIdEndpointsImplemented() throws Exception {
        // Branch D adds ONLY GET + POST /payments. There is no edit / delete / by-id payment route, and no
        // Stripe/gateway endpoint — these must remain unmapped (4xx, no handler), never a 2xx.
        mockMvc.perform(get(paymentsUrl(ORDER_FULL) + "/1").session(liamStore1Session()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(put(paymentsUrl(ORDER_FULL) + "/1").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(delete(paymentsUrl(ORDER_FULL) + "/1").session(liamStore1Session()))
                .andExpect(status().is4xxClientError());
    }
}
