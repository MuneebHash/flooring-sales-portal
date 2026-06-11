package com.flooring.salesportal.order;

import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Phase 13B: D.8 POST /orders/{orderId}/invoices/current/accept (signature
 * multipart + signed PDF + post-commit auto-email), D.9 POST /orders/{orderId}/invoices/current/resend
 * (re-email the accepted PDF), D.10 GET /orders/{orderId}/invoices/current/signature, and the §11
 * dashboard mirror behaviour after accept.
 *
 * <p>Mirrors {@code OrderInvoiceControllerTest} / {@code OrderAttachmentControllerTest}:
 * {@code @SpringBootTest @Transactional}, MockMvc, the seeded Liam / business 1 / store 1 session,
 * {@code JdbcTemplate} for data tweaks + persistence assertions (rolled back with the test), and a
 * JUnit {@link TempDir} bound to {@code app.storage.base-dir}. Multipart requests use
 * {@code MockMultipartFile} exactly like the attachment-upload tests.
 *
 * <p>Emails go through the singleton {@link RecordingInvoiceEmailSender} — its in-memory state
 * survives the per-test transaction rollback, so it is {@code reset()} in BOTH {@code @BeforeEach}
 * (clean slate) and {@code @AfterEach} (never leak an armed failNextSend to another test class).
 *
 * <p>Seed (V4 + V6): order 1 ({@code SYD-CBD.LC1.00001}) is fully populated with ONE seeded invoice
 * (v1, unaccepted) and a valid customer email; order 2 = header-only LEAD (no invoice, no customer);
 * order 5 = cross-store; order 9 = cross-business; order 99999 = nonexistent. New invoice versions are
 * asserted STATE-DERIVED ({@code maxInvoiceVersion() + 1}, count-before/after) — never hardcoded
 * against the shared dirty local DB.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class InvoiceAcceptanceControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final long ORDER_FULL = 1L;            // fully populated + seeded invoice v1
    private static final long ORDER_EMPTY = 2L;           // header-only LEAD (no invoice, no customer)
    private static final long ORDER_OTHER_STORE = 5L;     // store 2, business 1 (cross-store)
    private static final long ORDER_OTHER_BUSINESS = 9L;  // business 2 (cross-business)
    private static final long ORDER_DOES_NOT_EXIST = 99_999L;

    private static final String ORDER_FULL_NUMBER = "SYD-CBD.LC1.00001";
    private static final String SIGNATURE_PART = "signature";
    private static final String ACCEPTED_NAME_FIELD = "accepted_customer_name";
    private static final long MAX_SIGNATURE_SIZE_BYTES = 2_097_152L;

    private static final String ACCEPT_EMAILED_MESSAGE = "Invoice accepted and emailed to the customer.";
    private static final String ACCEPT_EMAIL_FAILED_MESSAGE =
            "Invoice accepted. The invoice could not be emailed — use Re-send Invoice to try again.";
    private static final String RESEND_MESSAGE = "Invoice re-sent to the customer.";

    // A real, decodable 1x1 PNG: the accept flow embeds the uploaded bytes into the signed PDF via a
    // base64 data URI, so fake bytes would break rendering with a 500 instead of exercising the flow.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

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

    @Autowired
    private RecordingInvoiceEmailSender recordingInvoiceEmailSender;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        recordingInvoiceEmailSender.reset();
    }

    @AfterEach
    void tearDown() {
        // The sender is a singleton — never leak recorded sends or an armed failNextSend.
        recordingInvoiceEmailSender.reset();
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

    private static String acceptUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/invoices/current/accept";
    }

    private static String resendUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/invoices/current/resend";
    }

    private static String signatureUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/invoices/current/signature";
    }

    private static String dashboardUrl(String search) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders?search=" + search;
    }

    private static MockMultipartFile signaturePart(byte[] bytes) {
        return new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", bytes);
    }

    /** A fully valid accept request (signature + name + session); tests mutate what they need. */
    private MockMultipartHttpServletRequestBuilder validAccept(long orderId) {
        return (MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(orderId))
                .file(signaturePart(ONE_PIXEL_PNG))
                .param(ACCEPTED_NAME_FIELD, "James Wilson");
    }

    private void laidOrder(long orderId) {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
    }

    /** Mark the CURRENT invoice accepted directly in the DB (no signature file — D.9 needs only accepted_at). */
    private void seedAcceptance(long orderId) {
        jdbcTemplate.update("""
                UPDATE invoice
                SET accepted_at = TIMESTAMP '2026-04-22 14:31:10', accepted_customer_name = 'James Wilson'
                WHERE invoice_id = (SELECT invoice_id FROM invoice WHERE order_id = ?
                                    ORDER BY version_number DESC LIMIT 1)
                """, orderId);
    }

    private int countInvoices(long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice WHERE order_id = ?", Integer.class, orderId);
    }

    private int countStoredFiles() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file", Integer.class);
    }

    private int maxInvoiceVersion(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) FROM invoice WHERE order_id = ?", Integer.class, orderId);
    }

    private long latestInvoiceId(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT invoice_id FROM invoice WHERE order_id = ? ORDER BY version_number DESC LIMIT 1",
                Long.class, orderId);
    }

    private Timestamp invoiceLastEmailedAt(long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM invoice WHERE invoice_id = ?", Timestamp.class, invoiceId);
    }

    private Timestamp orderLastEmailedAt(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId);
    }

    private Timestamp invoiceAcceptedAt(long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT accepted_at FROM invoice WHERE invoice_id = ?", Timestamp.class, invoiceId);
    }

    private Long acceptedSignatureFileId(long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT accepted_signature_file_id FROM invoice WHERE invoice_id = ?", Long.class, invoiceId);
    }

    private String storagePathOf(long storedFileId) {
        return jdbcTemplate.queryForObject(
                "SELECT storage_path FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
    }

    private String customerEmail(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT email FROM order_customer WHERE order_id = ?", String.class, orderId);
    }

    private void setCustomerEmail(long orderId, String email) {
        jdbcTemplate.update("UPDATE order_customer SET email = ? WHERE order_id = ?", email, orderId);
    }

    /** Drop the email NOT NULL + format CHECK (transactional DDL, rolled back) — mirrors the D.1 tests. */
    private void relaxCustomerEmailDbConstraints() {
        jdbcTemplate.execute("ALTER TABLE order_customer DROP CONSTRAINT chk_order_customer_email_format");
        jdbcTemplate.execute("ALTER TABLE order_customer ALTER COLUMN email DROP NOT NULL");
    }

    private boolean diskFileExists(String storagePath) {
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        return Files.exists(tempStorageDir.resolve(relative));
    }

    private static void assertMoneyEquals(BigDecimal expected, BigDecimal actual) {
        Assertions.assertNotNull(expected);
        Assertions.assertNotNull(actual);
        Assertions.assertEquals(0, expected.compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    // ================================================================
    // D.8 Accept — happy path (auto-email success)
    // ================================================================

    @Test
    void accept_validRequest_returns201_appendsAcceptedVersion_andEmails() throws Exception {
        int versionsBefore = countInvoices(ORDER_FULL);
        int nextVersion = maxInvoiceVersion(ORDER_FULL) + 1;
        int storedFilesBefore = countStoredFiles();
        String expectedRecipient = customerEmail(ORDER_FULL).trim();

        MvcResult result = mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAILED_MESSAGE))
                .andExpect(jsonPath("$.data.invoice.version_number").value(nextVersion))
                .andExpect(jsonPath("$.data.invoice.accepted_at").isNotEmpty())
                .andExpect(jsonPath("$.data.invoice.accepted_customer_name").value("James Wilson"))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(true))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_download_path")
                        .value(signatureUrl(ORDER_FULL)))
                // Final post-email state: the auto-email succeeded, so last_emailed_at is SET.
                .andExpect(jsonPath("$.data.invoice.last_emailed_at").isNotEmpty())
                .andReturn();

        // Appended version (max + 1), never an in-place mutation; the old version is untouched.
        Assertions.assertEquals(versionsBefore + 1, countInvoices(ORDER_FULL));
        // Two new stored_file rows: the signature PNG + the signed PDF.
        Assertions.assertEquals(storedFilesBefore + 2, countStoredFiles());

        long newInvoiceId = latestInvoiceId(ORDER_FULL);
        Assertions.assertNotNull(invoiceAcceptedAt(newInvoiceId));
        Assertions.assertNotNull(acceptedSignatureFileId(newInvoiceId));

        // §11.1 mirror: invoice.last_emailed_at and sales_order.last_emailed_at carry the SAME timestamp.
        Timestamp invoiceStamp = invoiceLastEmailedAt(newInvoiceId);
        Timestamp mirrorStamp = orderLastEmailedAt(ORDER_FULL);
        Assertions.assertNotNull(invoiceStamp, "invoice.last_emailed_at must be stamped on email success");
        Assertions.assertEquals(invoiceStamp, mirrorStamp, "sales_order mirror must equal the invoice stamp");

        // The recording sender captured exactly one email with the signed PDF attached.
        List<InvoiceEmailRequest> sent = recordingInvoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size());
        InvoiceEmailRequest email = sent.get(0);
        Assertions.assertEquals(expectedRecipient, email.recipientEmail());
        Assertions.assertEquals("Your invoice from Aussie Floors Group", email.subject());
        Assertions.assertEquals("invoice-" + ORDER_FULL_NUMBER + "-v" + nextVersion + ".pdf", email.pdfFileName());
        Assertions.assertEquals("%PDF-", new String(email.pdfBytes(), 0, 5, StandardCharsets.US_ASCII),
                "attached bytes must be a PDF");

        // Server-internal ids/paths never leak.
        String json = result.getResponse().getContentAsString();
        Assertions.assertFalse(json.contains("accepted_signature_file_id"), json);
        Assertions.assertFalse(json.contains("stored_file_id"), json);
        Assertions.assertFalse(json.contains("storage_path"), json);
        Assertions.assertFalse(json.contains("/uploads/"), json);

        // The signature stored_file row carries the uploaded bytes' metadata.
        long signatureFileId = acceptedSignatureFileId(newInvoiceId);
        Assertions.assertEquals("image/png", jdbcTemplate.queryForObject(
                "SELECT mime_type FROM stored_file WHERE stored_file_id = ?", String.class, signatureFileId));
        Assertions.assertEquals((long) ONE_PIXEL_PNG.length, jdbcTemplate.queryForObject(
                "SELECT file_size FROM stored_file WHERE stored_file_id = ?", Long.class, signatureFileId));
        String signaturePath = storagePathOf(signatureFileId);
        Assertions.assertTrue(signaturePath.startsWith("/uploads/1/orders/1/"), signaturePath);
        Assertions.assertTrue(signaturePath.endsWith(".png"), signaturePath);
        Assertions.assertTrue(diskFileExists(signaturePath), "signature PNG must exist on disk");
    }

    @Test
    void accept_carriesSnapshotForward_neverRecomputesFromLiveEdits() throws Exception {
        long previousInvoiceId = latestInvoiceId(ORDER_FULL);
        String frozenSnapshot = jdbcTemplate.queryForObject(
                "SELECT details_of_sale_snapshot FROM invoice WHERE invoice_id = ?", String.class, previousInvoiceId);
        BigDecimal frozenIncGst = jdbcTemplate.queryForObject(
                "SELECT sale_price_inc_gst FROM invoice WHERE invoice_id = ?", BigDecimal.class, previousInvoiceId);
        BigDecimal frozenTotalPaid = jdbcTemplate.queryForObject(
                "SELECT total_paid FROM invoice WHERE invoice_id = ?", BigDecimal.class, previousInvoiceId);
        BigDecimal frozenBalance = jdbcTemplate.queryForObject(
                "SELECT balance_due FROM invoice WHERE invoice_id = ?", BigDecimal.class, previousInvoiceId);

        // Unsent live edit: must NOT become official through acceptance (that is what Rewrite is for).
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = 'LIVE EDIT not on any invoice' "
                + "WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated());

        long newInvoiceId = latestInvoiceId(ORDER_FULL);
        Assertions.assertNotEquals(previousInvoiceId, newInvoiceId);
        Assertions.assertEquals(frozenSnapshot, jdbcTemplate.queryForObject(
                "SELECT details_of_sale_snapshot FROM invoice WHERE invoice_id = ?", String.class, newInvoiceId));
        assertMoneyEquals(frozenIncGst, jdbcTemplate.queryForObject(
                "SELECT sale_price_inc_gst FROM invoice WHERE invoice_id = ?", BigDecimal.class, newInvoiceId));
        assertMoneyEquals(frozenTotalPaid, jdbcTemplate.queryForObject(
                "SELECT total_paid FROM invoice WHERE invoice_id = ?", BigDecimal.class, newInvoiceId));
        assertMoneyEquals(frozenBalance, jdbcTemplate.queryForObject(
                "SELECT balance_due FROM invoice WHERE invoice_id = ?", BigDecimal.class, newInvoiceId));

        // The previous version is untouched (append-only).
        Assertions.assertNull(invoiceAcceptedAt(previousInvoiceId));
    }

    @Test
    void accept_laidOrder_isAllowed() throws Exception {
        laidOrder(ORDER_FULL);
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(true));
    }

    @Test
    void accept_signatureExactlyTwoMegabytes_isAllowed() throws Exception {
        // A real PNG padded with trailing bytes to EXACTLY 2 MB: decoders read to IEND and ignore the
        // tail, so the signed-PDF embed still works while the size boundary (== allowed) is exercised.
        byte[] exactlyMax = new byte[(int) MAX_SIGNATURE_SIZE_BYTES];
        System.arraycopy(ONE_PIXEL_PNG, 0, exactlyMax, 0, ONE_PIXEL_PNG.length);

        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(exactlyMax))
                        .param(ACCEPTED_NAME_FIELD, "James Wilson"))
                        .session(liamStore1Session()))
                .andExpect(status().isCreated());
    }

    @Test
    void accept_nameAtExactly150Chars_isAllowed_and151Rejected() throws Exception {
        // 151 first: once an accept succeeds, the already-accepted 409 (step 3) would fire before the
        // name validation (step 4), so the over-length rejection must be tested on an unaccepted invoice.
        int countBefore = countInvoices(ORDER_FULL);
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, "X".repeat(151)))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value(ACCEPTED_NAME_FIELD));
        Assertions.assertEquals(countBefore, countInvoices(ORDER_FULL));

        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, "X".repeat(150)))
                        .session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.accepted_customer_name").value("X".repeat(150)));
    }

    @Test
    void accept_trailingRootDotEmail_passesTheGate() throws Exception {
        // Frontend-parity rule: /^[^\s@]+@[^\s@]+\.[^\s@]+$/ tolerates "james@example.com.".
        setCustomerEmail(ORDER_FULL, "james@example.com.");
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated());
    }

    // ================================================================
    // D.8 Accept — email failure (non-fatal, still 201)
    // ================================================================

    @Test
    void accept_emailFailure_still201_acceptancePersisted_timestampsNull() throws Exception {
        int nextVersion = maxInvoiceVersion(ORDER_FULL) + 1;
        int storedFilesBefore = countStoredFiles();
        recordingInvoiceEmailSender.failNextSend();

        MvcResult result = mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAIL_FAILED_MESSAGE))
                .andExpect(jsonPath("$.data.invoice.version_number").value(nextVersion))
                .andExpect(jsonPath("$.data.invoice.accepted_at").isNotEmpty())
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(true))
                .andReturn();

        // Final post-email state: send failed, so last_emailed_at is null in the response body.
        String json = result.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"last_emailed_at\":null"),
                () -> "response must carry last_emailed_at null after a failed send: " + json);

        // Acceptance + signature + signed PDF are durably persisted; both timestamps stay null.
        long newInvoiceId = latestInvoiceId(ORDER_FULL);
        Assertions.assertNotNull(invoiceAcceptedAt(newInvoiceId));
        Assertions.assertNotNull(acceptedSignatureFileId(newInvoiceId));
        Assertions.assertEquals(storedFilesBefore + 2, countStoredFiles());
        Assertions.assertNull(invoiceLastEmailedAt(newInvoiceId));
        Assertions.assertNull(orderLastEmailedAt(ORDER_FULL));

        // The attempt was recorded as failed; nothing was "sent".
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertEquals(1, recordingInvoiceEmailSender.failedEmails().size());
    }

    // ================================================================
    // D.8 Accept — validation chain (contract §5.1 order)
    // ================================================================

    @Test
    void accept_noCurrentInvoice_returns422_invoiceRequired_beforeEmailGate() throws Exception {
        // ORDER_EMPTY has no invoice AND no customer: INVOICE_REQUIRED (step 2) must win over the
        // email gate (step 7).
        mockMvc.perform(validAccept(ORDER_EMPTY).session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_REQUIRED"));
    }

    @Test
    void accept_alreadyAccepted_returns409() throws Exception {
        seedAcceptance(ORDER_FULL);
        int countBefore = countInvoices(ORDER_FULL);

        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVOICE_ALREADY_ACCEPTED"));
        Assertions.assertEquals(countBefore, countInvoices(ORDER_FULL));
    }

    @Test
    void accept_missingName_returns422_acceptedCustomerNameRequired() throws Exception {
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(ONE_PIXEL_PNG)))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ACCEPTED_CUSTOMER_NAME_REQUIRED"));
    }

    @Test
    void accept_blankName_returns422_acceptedCustomerNameRequired() throws Exception {
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, "   "))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ACCEPTED_CUSTOMER_NAME_REQUIRED"));
    }

    @Test
    void accept_missingSignaturePart_returns422_signatureRequired() throws Exception {
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .param(ACCEPTED_NAME_FIELD, "James Wilson"))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SIGNATURE_REQUIRED"));
    }

    @Test
    void accept_emptySignature_returns422_signatureRequired() throws Exception {
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(new byte[0]))
                        .param(ACCEPTED_NAME_FIELD, "James Wilson"))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SIGNATURE_REQUIRED"));
    }

    @Test
    void accept_wrongMimeType_returns400_signatureInvalid() throws Exception {
        MockMultipartFile jpeg = new MockMultipartFile(SIGNATURE_PART, "signature.jpg", "image/jpeg", ONE_PIXEL_PNG);
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(jpeg)
                        .param(ACCEPTED_NAME_FIELD, "James Wilson"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));
    }

    @Test
    void accept_oversizeSignature_returns400_signatureInvalid() throws Exception {
        byte[] overMax = new byte[(int) MAX_SIGNATURE_SIZE_BYTES + 1];
        int countBefore = countInvoices(ORDER_FULL);
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(overMax))
                        .param(ACCEPTED_NAME_FIELD, "James Wilson"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));
        Assertions.assertEquals(countBefore, countInvoices(ORDER_FULL));
    }

    @Test
    void accept_unexpectedExtraPart_returns400_validationFailed() throws Exception {
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, "James Wilson")
                        .param("order_id", "5"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void accept_unexpectedExtraFilePart_returns400_validationFailed() throws Exception {
        MockMultipartFile extra = new MockMultipartFile("file", "x.png", "image/png", ONE_PIXEL_PNG);
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .file(extra)
                        .param(ACCEPTED_NAME_FIELD, "James Wilson"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("file"));
    }

    @Test
    void accept_duplicateSignatureParts_returns400_validationFailed() throws Exception {
        // getFile() would silently pick one of the two — a repeated signature part must be rejected.
        int countBefore = countInvoices(ORDER_FULL);
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, "James Wilson"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value(SIGNATURE_PART));
        Assertions.assertEquals(countBefore, countInvoices(ORDER_FULL));
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
    }

    @Test
    void accept_duplicateAcceptedCustomerNameValues_returns400_validationFailed() throws Exception {
        // getParameter() would silently pick one of the two values — duplicates must be rejected.
        int countBefore = countInvoices(ORDER_FULL);
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(ORDER_FULL))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, "James Wilson")
                        .param(ACCEPTED_NAME_FIELD, "Someone Else"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value(ACCEPTED_NAME_FIELD));
        Assertions.assertEquals(countBefore, countInvoices(ORDER_FULL));
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
    }

    @Test
    void accept_missingCustomerEmail_returns422_customerEmailRequired() throws Exception {
        relaxCustomerEmailDbConstraints();
        setCustomerEmail(ORDER_FULL, null);
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_REQUIRED"));
    }

    @Test
    void accept_malformedCustomerEmail_returns422_customerEmailInvalid() throws Exception {
        setCustomerEmail(ORDER_FULL, "james@");
        int countBefore = countInvoices(ORDER_FULL);
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_INVALID"));
        Assertions.assertEquals(countBefore, countInvoices(ORDER_FULL));
        // The email gate fires AFTER the signature checks (contract order) but BEFORE any persist —
        // no email attempt was made either.
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertEquals(0, recordingInvoiceEmailSender.failedEmails().size());
    }

    @Test
    void accept_scopeMisses_return404_orderNotFound() throws Exception {
        for (long orderId : new long[] {ORDER_OTHER_STORE, ORDER_OTHER_BUSINESS, ORDER_DOES_NOT_EXIST}) {
            mockMvc.perform(validAccept(orderId).session(liamStore1Session()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        }
    }

    @Test
    void accept_noSession_returns401_andNoStore_returns403() throws Exception {
        mockMvc.perform(validAccept(ORDER_FULL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(validAccept(ORDER_FULL).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ================================================================
    // D.8 Accept — transaction rollback removes both files + all rows
    // ================================================================

    @Test
    void accept_transactionRollback_removesSignatureAndPdfFilesAndRowsRoll() throws Exception {
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated());

        long newInvoiceId = latestInvoiceId(ORDER_FULL);
        long signatureFileId = acceptedSignatureFileId(newInvoiceId);
        long pdfFileId = jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE invoice_id = ?", Long.class, newInvoiceId);
        String signaturePath = storagePathOf(signatureFileId);
        String pdfPath = storagePathOf(pdfFileId);
        Assertions.assertTrue(diskFileExists(signaturePath));
        Assertions.assertTrue(diskFileExists(pdfPath));

        // Roll the test transaction back: rows disappear AND the rollback hooks remove both files.
        TestTransaction.flagForRollback();
        TestTransaction.end();

        Assertions.assertFalse(diskFileExists(signaturePath), "rolled-back signature file must be deleted");
        Assertions.assertFalse(diskFileExists(pdfPath), "rolled-back signed PDF must be deleted");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE invoice_id = ?", Integer.class, newInvoiceId);
        Assertions.assertEquals(0, rows, "accepted invoice row must be rolled back");
    }

    // ================================================================
    // D.9 Resend
    // ================================================================

    /**
     * Accept through the endpoint with a FAILING auto-email: yields a real accepted current invoice
     * whose signed PDF exists in the test TempDir (the V4-seeded invoice's stored_file has no file on
     * disk, so Resend's PDF read would 500) and whose {@code last_emailed_at} is still null. The
     * recorder is reset afterwards so resend assertions start from a clean slate.
     */
    private long acceptWithFailedEmail(long orderId) throws Exception {
        recordingInvoiceEmailSender.failNextSend();
        mockMvc.perform(validAccept(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated());
        recordingInvoiceEmailSender.reset();
        return latestInvoiceId(orderId);
    }

    @Test
    void resend_acceptedInvoice_returns200_stampsBothTimestampsInPlace() throws Exception {
        long invoiceId = acceptWithFailedEmail(ORDER_FULL);
        // Precondition: accepted but never emailed (the failed-auto-email shape) — Resend must work.
        Assertions.assertNull(invoiceLastEmailedAt(invoiceId));
        int versionsBefore = countInvoices(ORDER_FULL);
        int storedFilesBefore = countStoredFiles();

        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESEND_MESSAGE))
                .andExpect(jsonPath("$.data.invoice.invoice_id").value(invoiceId))
                .andExpect(jsonPath("$.data.invoice.last_emailed_at").isNotEmpty());

        // In place: no new version, no new files, no PDF regeneration.
        Assertions.assertEquals(versionsBefore, countInvoices(ORDER_FULL));
        Assertions.assertEquals(storedFilesBefore, countStoredFiles());

        Timestamp invoiceStamp = invoiceLastEmailedAt(invoiceId);
        Assertions.assertNotNull(invoiceStamp);
        Assertions.assertEquals(invoiceStamp, orderLastEmailedAt(ORDER_FULL),
                "sales_order mirror must equal the invoice stamp");

        List<InvoiceEmailRequest> sent = recordingInvoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size());
        Assertions.assertEquals("%PDF-",
                new String(sent.get(0).pdfBytes(), 0, 5, StandardCharsets.US_ASCII));
    }

    @Test
    void resend_providerFailure_returns502_andWritesNothing() throws Exception {
        long invoiceId = acceptWithFailedEmail(ORDER_FULL);
        int versionsBefore = countInvoices(ORDER_FULL);
        int storedFilesBefore = countStoredFiles();
        recordingInvoiceEmailSender.failNextSend();

        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("EMAIL_SEND_FAILED"));

        // Nothing written: both timestamps unchanged (null), no new version, no new files.
        Assertions.assertNull(invoiceLastEmailedAt(invoiceId));
        Assertions.assertNull(orderLastEmailedAt(ORDER_FULL));
        Assertions.assertEquals(versionsBefore, countInvoices(ORDER_FULL));
        Assertions.assertEquals(storedFilesBefore, countStoredFiles());
        Assertions.assertEquals(1, recordingInvoiceEmailSender.failedEmails().size());
    }

    @Test
    void resend_unacceptedInvoice_returns422_invoiceNotAccepted() throws Exception {
        // Seeded v1 is unaccepted. Also locks gate order: a bad email must NOT mask the accepted check.
        setCustomerEmail(ORDER_FULL, "james@");
        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_ACCEPTED"));
    }

    @Test
    void resend_noInvoice_returns422_invoiceRequired() throws Exception {
        mockMvc.perform(post(resendUrl(ORDER_EMPTY)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_REQUIRED"));
    }

    @Test
    void resend_malformedCustomerEmail_returns422_customerEmailInvalid() throws Exception {
        seedAcceptance(ORDER_FULL);
        setCustomerEmail(ORDER_FULL, "james@");
        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_INVALID"));
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
    }

    @Test
    void resend_nonEmptyBody_returns400_validationFailed() throws Exception {
        seedAcceptance(ORDER_FULL);
        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{\"force\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("force"));
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
    }

    @Test
    void resend_laidOrder_isAllowed() throws Exception {
        acceptWithFailedEmail(ORDER_FULL);
        laidOrder(ORDER_FULL);
        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void resend_afterFailedAcceptEmail_succeedsAndStamps() throws Exception {
        // End-to-end: accept with a failing provider (201, timestamps null), then Re-send recovers.
        recordingInvoiceEmailSender.failNextSend();
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAIL_FAILED_MESSAGE));
        long acceptedInvoiceId = latestInvoiceId(ORDER_FULL);
        Assertions.assertNull(invoiceLastEmailedAt(acceptedInvoiceId));

        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        Timestamp stamp = invoiceLastEmailedAt(acceptedInvoiceId);
        Assertions.assertNotNull(stamp);
        Assertions.assertEquals(stamp, orderLastEmailedAt(ORDER_FULL));
        Assertions.assertEquals(1, recordingInvoiceEmailSender.sentEmails().size());
    }

    // ================================================================
    // D.10 Signature download
    // ================================================================

    @Test
    void signature_afterAccept_streamsUploadedBytesWithHeaders() throws Exception {
        int nextVersion = maxInvoiceVersion(ORDER_FULL) + 1;
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated());

        String expectedFileName = "signature-" + ORDER_FULL_NUMBER + "-v" + nextVersion + ".png";
        MvcResult result = mockMvc.perform(get(signatureUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inline")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(expectedFileName)))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(ONE_PIXEL_PNG, body, "streamed bytes must match the uploaded signature");
        Assertions.assertEquals(body.length, result.getResponse().getContentLength());

        String headers = result.getResponse().getHeaderNames().toString()
                + result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        Assertions.assertFalse(headers.contains("/uploads/"), "storage_path must never leak");
    }

    @Test
    void signature_scopeMiss_returns404_orderNotFound() throws Exception {
        mockMvc.perform(get(signatureUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(get(signatureUrl(ORDER_OTHER_STORE)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void signature_noInvoice_returns404_invoiceNotFound() throws Exception {
        mockMvc.perform(get(signatureUrl(ORDER_EMPTY)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_FOUND"));
    }

    @Test
    void signature_unacceptedInvoice_returns422_invoiceNotAccepted() throws Exception {
        // Seeded v1 is unaccepted.
        mockMvc.perform(get(signatureUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_ACCEPTED"));
    }

    @Test
    void signature_acceptedWithoutSignatureFile_returns422_invoiceNotAccepted() throws Exception {
        // Defensive guard: accepted_at set but no signature file id (SQL-seeded acceptance).
        seedAcceptance(ORDER_FULL);
        mockMvc.perform(get(signatureUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_ACCEPTED"));
    }

    @Test
    void signature_laidOrder_isAllowed() throws Exception {
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated());
        laidOrder(ORDER_FULL);
        mockMvc.perform(get(signatureUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"));
    }

    // ================================================================
    // Dashboard mirror (§11 / §11.1) after accept
    // ================================================================

    @Test
    void dashboard_afterAcceptWithEmailSuccess_showsAcceptedTrueAndMirrorTimestamp() throws Exception {
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated());

        mockMvc.perform(get(dashboardUrl("LC1.00001")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].invoice_accepted").value(true))
                .andExpect(jsonPath("$.data[0].last_emailed_at").isNotEmpty());
    }

    @Test
    void dashboard_afterAcceptWithEmailFailure_showsAcceptedTrueAndNullMirror() throws Exception {
        recordingInvoiceEmailSender.failNextSend();
        mockMvc.perform(validAccept(ORDER_FULL).session(liamStore1Session()))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get(dashboardUrl("LC1.00001")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].invoice_accepted").value(true))
                .andReturn();
        // jsonPath(...).doesNotExist() treats JSON null as absent, so the null mirror is asserted on
        // the raw body (the search returns exactly the one matching row).
        String json = result.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"last_emailed_at\":null"),
                () -> "dashboard mirror must stay null after a failed accept email: " + json);
    }
}
