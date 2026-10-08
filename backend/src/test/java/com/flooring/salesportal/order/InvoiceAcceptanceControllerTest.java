package com.flooring.salesportal.order;

import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 *
 * <p>Phase 16F PR1 (decision D5(c)) — the section at the end proves that a successful D.8 cancels the
 * order's ISSUED quote version + its ACTIVE quote token inside D.8's own persist transaction, and
 * nothing else. Those tests SELF-SEED (Phase 14D go-forward rule; V4 business 1 / store 1 / user 1
 * only — never ORDER_FULL): their own invoice-ready order, invoice v1 through D.1, and — where needed —
 * a quote draft saved and ISSUED through the protected quote endpoints. They also drive the singleton
 * {@link RecordingQuoteEmailSender} (the token's only carrier) and
 * {@link RecordingQuoteAcceptanceNotificationSender} (public accept), reset like the invoice sender.
 * Two of them COMMIT their fixture (the rollback proof and the durable post-commit proof) and delete it
 * in {@code finally}.
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

    // Phase 16F PR1 (D5(c) section): the quote send records the link-only email (the plaintext token's
    // only carrier) and the public accept records the store notification.
    @Autowired
    private RecordingQuoteEmailSender recordingQuoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender recordingQuoteAcceptanceNotificationSender;

    @Autowired
    private EntityManager entityManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        recordingInvoiceEmailSender.reset();
        recordingQuoteEmailSender.reset();
        recordingQuoteAcceptanceNotificationSender.reset();
    }

    @AfterEach
    void tearDown() {
        // The senders are singletons — never leak recorded sends or an armed failNextSend.
        recordingInvoiceEmailSender.reset();
        recordingQuoteEmailSender.reset();
        recordingQuoteAcceptanceNotificationSender.reset();
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

    // ================================================================
    // Phase 15D — payment after acceptance: carry-forward, NO auto-email (D.7)
    // ================================================================

    private static final String PAYMENT_RECORDED_MESSAGE = "Payment recorded. Current invoice updated.";

    private static String paymentsUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/payments";
    }

    private static String currentFileUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/invoices/current/file";
    }

    private static String voidUrl(Object orderId, Object paymentId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/payments/" + paymentId + "/void";
    }

    /** Record a payment (D.7) and return its payment_transaction_id, for a subsequent void (D.10). */
    private long recordPaymentReturningId(long orderId, String amount) throws Exception {
        mockMvc.perform(post(paymentsUrl(orderId)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody(amount)))
                .andExpect(status().isCreated());
        return jdbcTemplate.queryForObject(
                "SELECT payment_transaction_id FROM payment_transaction WHERE order_id = ? "
                        + "ORDER BY payment_transaction_id DESC LIMIT 1", Long.class, orderId);
    }

    private static String paymentBody(String amount) {
        return "{\"payment_method\":\"EFTPOS\",\"amount\":" + amount + ",\"payment_reference\":null}";
    }

    private static String extractPdfText(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    @Test
    void paymentOnAccepted_carriesAcceptanceForward_sameSignatureFile_dashboardStaysAccepted() throws Exception {
        long acceptedInvoiceId = acceptWithFailedEmail(ORDER_FULL);
        Timestamp acceptedAt = invoiceAcceptedAt(acceptedInvoiceId);
        long signatureFileId = acceptedSignatureFileId(acceptedInvoiceId);
        int nextVersion = maxInvoiceVersion(ORDER_FULL) + 1;
        int storedFilesBefore = countStoredFiles();

        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody("100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.current_invoice.version_number").value(nextVersion))
                .andExpect(jsonPath("$.data.current_invoice.accepted_at").isNotEmpty())
                .andExpect(jsonPath("$.data.current_invoice.accepted_customer_name").value("James Wilson"))
                .andExpect(jsonPath("$.data.current_invoice.accepted_signature_present").value(true))
                .andExpect(jsonPath("$.data.current_invoice.accepted_signature_download_path")
                        .value(signatureUrl(ORDER_FULL)));

        // Acceptance metadata carried UNCHANGED; the SAME signature stored_file is referenced (the V10
        // FK is deliberately non-unique) — only ONE new stored_file row exists: the regenerated PDF.
        long newInvoiceId = latestInvoiceId(ORDER_FULL);
        Assertions.assertNotEquals(acceptedInvoiceId, newInvoiceId);
        Assertions.assertEquals(acceptedAt, invoiceAcceptedAt(newInvoiceId));
        Assertions.assertEquals(signatureFileId, (long) acceptedSignatureFileId(newInvoiceId),
                "payment version must reference the SAME signature stored_file");
        Assertions.assertEquals(storedFilesBefore + 1, countStoredFiles(),
                "exactly one new stored_file (the regenerated PDF), never a new signature row");

        mockMvc.perform(get(dashboardUrl("LC1.00001")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].invoice_accepted").value(true));
    }

    @Test
    void paymentOnAccepted_regeneratesSignedPdf_withAcceptanceBlock_noAutoEmail() throws Exception {
        acceptWithFailedEmail(ORDER_FULL);

        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody("100.00")))
                .andExpect(status().isCreated());

        // Phase 15D: NO email is sent — but the payment-created version is still a signed PDF carrying the
        // acceptance block. Read it from the current-invoice file endpoint (not an email attachment).
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertEquals(0, recordingInvoiceEmailSender.failedEmails().size());

        MvcResult result = mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andReturn();
        byte[] pdf = result.getResponse().getContentAsByteArray();
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
        String text = extractPdfText(pdf);
        Assertions.assertTrue(text.contains("Accepted by"), () -> "missing acceptance caption: " + text);
        Assertions.assertTrue(text.contains("James Wilson"), () -> "missing carried accepted name: " + text);
        Assertions.assertFalse(text.contains("Customer signature"),
                () -> "signed PDF must not show the blank-area caption: " + text);
    }

    @Test
    void paymentOnAccepted_noAutoEmail_lastEmailedStaysNull() throws Exception {
        acceptWithFailedEmail(ORDER_FULL);

        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody("100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(PAYMENT_RECORDED_MESSAGE));

        // Phase 15D: recording a payment never emails, so the new version stays unemailed and the §11.1
        // mirror is reset to null (DB state is the unambiguous assertion — the JSON field is always null).
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertEquals(0, recordingInvoiceEmailSender.failedEmails().size());
        Assertions.assertNull(invoiceLastEmailedAt(latestInvoiceId(ORDER_FULL)));
        Assertions.assertNull(orderLastEmailedAt(ORDER_FULL),
                "sales_order mirror must be reset to null on the payment-created version");
    }

    @Test
    void paymentOnAccepted_noEmail_acceptedPreserved_resendStillAvailable() throws Exception {
        long acceptedInvoiceId = acceptWithFailedEmail(ORDER_FULL);
        long signatureFileId = acceptedSignatureFileId(acceptedInvoiceId);

        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody("100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(PAYMENT_RECORDED_MESSAGE))
                .andExpect(jsonPath("$.data.current_invoice.accepted_signature_present").value(true));

        // Payment + carried acceptance persisted; NO email sent (Phase 15D); both timestamps stay null.
        long newInvoiceId = latestInvoiceId(ORDER_FULL);
        Assertions.assertNotNull(invoiceAcceptedAt(newInvoiceId));
        Assertions.assertEquals(signatureFileId, (long) acceptedSignatureFileId(newInvoiceId));
        Assertions.assertNull(invoiceLastEmailedAt(newInvoiceId));
        Assertions.assertNull(orderLastEmailedAt(ORDER_FULL));
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertEquals(0, recordingInvoiceEmailSender.failedEmails().size());

        // Manual Re-send remains the only email path: the current invoice is still accepted.
        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESEND_MESSAGE));
        Assertions.assertEquals(1, recordingInvoiceEmailSender.sentEmails().size());
    }

    @Test
    void signatureDownload_afterPaymentOnAccepted_stillStreamsTheCarriedSignature() throws Exception {
        acceptWithFailedEmail(ORDER_FULL);
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody("100.00")))
                .andExpect(status().isCreated());

        int currentVersion = maxInvoiceVersion(ORDER_FULL);
        MvcResult result = mockMvc.perform(get(signatureUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("signature-" + ORDER_FULL_NUMBER + "-v" + currentVersion + ".png")))
                .andReturn();
        Assertions.assertArrayEquals(ONE_PIXEL_PNG, result.getResponse().getContentAsByteArray(),
                "carried-forward signature bytes must still stream after a payment");
    }

    @Test
    void resend_afterPaymentOnAccepted_reemailsCurrentVersion_withoutNewVersion() throws Exception {
        acceptWithFailedEmail(ORDER_FULL);
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody("100.00")))
                .andExpect(status().isCreated());
        int versionsAfterPayment = countInvoices(ORDER_FULL);
        int currentVersion = maxInvoiceVersion(ORDER_FULL);
        recordingInvoiceEmailSender.reset();

        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        Assertions.assertEquals(versionsAfterPayment, countInvoices(ORDER_FULL), "resend appends no version");
        List<InvoiceEmailRequest> sent = recordingInvoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size());
        Assertions.assertEquals("invoice-" + ORDER_FULL_NUMBER + "-v" + currentVersion + ".pdf",
                sent.get(0).pdfFileName(), "resend must email the payment-created current version's PDF");
    }

    @Test
    void paymentOnUnaccepted_remainsUnaccepted_andSendsNoEmail() throws Exception {
        // Seeded v1 is unaccepted — existing D.7 behavior is unchanged: no acceptance, no email attempt.
        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody("100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Payment recorded. Current invoice updated."))
                .andExpect(jsonPath("$.data.current_invoice.accepted_signature_present").value(false));

        Assertions.assertNull(invoiceAcceptedAt(latestInvoiceId(ORDER_FULL)));
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertEquals(0, recordingInvoiceEmailSender.failedEmails().size());
    }

    @Test
    void paymentOnAccepted_missingCustomerEmail_still201_noEmail_acceptancePreserved() throws Exception {
        long acceptedInvoiceId = acceptWithFailedEmail(ORDER_FULL);
        long signatureFileId = acceptedSignatureFileId(acceptedInvoiceId);
        // Payment never gates on the customer email. Phase 15D: it never emails either, so a missing email
        // changes nothing — 201, acceptance carried, no send. (The accept above loaded the OrderCustomer
        // into this transaction's persistence context, so the JdbcTemplate update below is followed by
        // flush()+clear() to avoid re-reading the cached entity — a test-only artifact; production
        // requests each have their own persistence context.)
        relaxCustomerEmailDbConstraints();
        setCustomerEmail(ORDER_FULL, null);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody("100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(PAYMENT_RECORDED_MESSAGE))
                .andExpect(jsonPath("$.data.current_invoice.accepted_signature_present").value(true));

        // No CUSTOMER_EMAIL_* gate fired; no send was attempted; acceptance carried; timestamps null.
        long newInvoiceId = latestInvoiceId(ORDER_FULL);
        Assertions.assertNotNull(invoiceAcceptedAt(newInvoiceId));
        Assertions.assertEquals(signatureFileId, (long) acceptedSignatureFileId(newInvoiceId));
        Assertions.assertNull(invoiceLastEmailedAt(newInvoiceId));
        Assertions.assertNull(orderLastEmailedAt(ORDER_FULL));
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertEquals(0, recordingInvoiceEmailSender.failedEmails().size());
    }

    @Test
    void paymentOnAccepted_responseDoesNotLeakInternalFields() throws Exception {
        acceptWithFailedEmail(ORDER_FULL);

        MvcResult result = mockMvc.perform(post(paymentsUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content(paymentBody("100.00")))
                .andExpect(status().isCreated())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        Assertions.assertFalse(json.contains("accepted_signature_file_id"), json);
        Assertions.assertFalse(json.contains("stored_file_id"), json);
        Assertions.assertFalse(json.contains("storage_path"), json);
        Assertions.assertFalse(json.contains("/uploads/"), json);
    }

    // ================================================================
    // Phase 15D — payment VOID after acceptance: carry-forward, NO re-sign, NO auto-email (D.10)
    // ================================================================

    @Test
    void voidOnAccepted_carriesAcceptanceForward_sameSignatureFile_dashboardStaysAccepted() throws Exception {
        long acceptedInvoiceId = acceptWithFailedEmail(ORDER_FULL);
        Timestamp acceptedAt = invoiceAcceptedAt(acceptedInvoiceId);
        long signatureFileId = acceptedSignatureFileId(acceptedInvoiceId);
        long paymentId = recordPaymentReturningId(ORDER_FULL, "100.00");
        int versionBeforeVoid = maxInvoiceVersion(ORDER_FULL);

        mockMvc.perform(post(voidUrl(ORDER_FULL, paymentId)).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Payment voided. Current invoice updated."))
                .andExpect(jsonPath("$.data.current_invoice.version_number").value(versionBeforeVoid + 1))
                .andExpect(jsonPath("$.data.current_invoice.accepted_at").isNotEmpty())
                .andExpect(jsonPath("$.data.current_invoice.accepted_customer_name").value("James Wilson"))
                .andExpect(jsonPath("$.data.current_invoice.accepted_signature_present").value(true))
                // Voiding the $100 payment drops total_paid back to the seeded baseline (the seeded
                // 500.00 EFTPOS payment stays active) and balance rises from 324.00 to 424.00.
                .andExpect(jsonPath("$.data.payment_summary.total_paid").value(500.00))
                .andExpect(jsonPath("$.data.payment_summary.balance_due").value(424.00))
                // the voided payment row reports who voided it.
                .andExpect(jsonPath("$.data.payment_transaction.voided_at").isNotEmpty())
                .andExpect(jsonPath("$.data.payment_transaction.voided_by_name").value("Liam Carter"));

        // Acceptance carried UNCHANGED onto the void-created version; SAME signature stored_file; customer
        // never re-signs.
        long newInvoiceId = latestInvoiceId(ORDER_FULL);
        Assertions.assertEquals(acceptedAt, invoiceAcceptedAt(newInvoiceId));
        Assertions.assertEquals(signatureFileId, (long) acceptedSignatureFileId(newInvoiceId),
                "void version must reference the SAME signature stored_file");
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertEquals(0, recordingInvoiceEmailSender.failedEmails().size());
        Assertions.assertNull(invoiceLastEmailedAt(newInvoiceId));
        Assertions.assertNull(orderLastEmailedAt(ORDER_FULL),
                "void resets the §11.1 mirror to null on the new version");

        mockMvc.perform(get(dashboardUrl("LC1.00001")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].invoice_accepted").value(true));
    }

    @Test
    void voidOnAccepted_regeneratesSignedPdf_withAcceptanceBlock() throws Exception {
        acceptWithFailedEmail(ORDER_FULL);
        long paymentId = recordPaymentReturningId(ORDER_FULL, "100.00");

        mockMvc.perform(post(voidUrl(ORDER_FULL, paymentId)).session(liamStore1Session()))
                .andExpect(status().isCreated());

        // The void-created version is still a signed PDF carrying the acceptance block; no email is sent.
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        MvcResult result = mockMvc.perform(get(currentFileUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andReturn();
        String text = extractPdfText(result.getResponse().getContentAsByteArray());
        Assertions.assertTrue(text.contains("Accepted by"), () -> "missing acceptance caption: " + text);
        Assertions.assertTrue(text.contains("James Wilson"), () -> "missing carried accepted name: " + text);
    }

    @Test
    void voidOnUnaccepted_remainsUnaccepted_andSendsNoEmail() throws Exception {
        // Seeded v1 is unaccepted; record then void — no acceptance, no email at any point.
        long paymentId = recordPaymentReturningId(ORDER_FULL, "100.00");

        mockMvc.perform(post(voidUrl(ORDER_FULL, paymentId)).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Payment voided. Current invoice updated."))
                .andExpect(jsonPath("$.data.current_invoice.accepted_signature_present").value(false));

        Assertions.assertNull(invoiceAcceptedAt(latestInvoiceId(ORDER_FULL)));
        Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertEquals(0, recordingInvoiceEmailSender.failedEmails().size());
    }

    @Test
    void resend_afterVoidOnAccepted_reemailsCurrentVersion_withoutNewVersion() throws Exception {
        acceptWithFailedEmail(ORDER_FULL);
        long paymentId = recordPaymentReturningId(ORDER_FULL, "100.00");
        mockMvc.perform(post(voidUrl(ORDER_FULL, paymentId)).session(liamStore1Session()))
                .andExpect(status().isCreated());
        int versionsAfterVoid = countInvoices(ORDER_FULL);
        int currentVersion = maxInvoiceVersion(ORDER_FULL);
        recordingInvoiceEmailSender.reset();

        mockMvc.perform(post(resendUrl(ORDER_FULL)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

        Assertions.assertEquals(versionsAfterVoid, countInvoices(ORDER_FULL), "resend appends no version");
        List<InvoiceEmailRequest> sent = recordingInvoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size());
        Assertions.assertEquals("invoice-" + ORDER_FULL_NUMBER + "-v" + currentVersion + ".pdf",
                sent.get(0).pdfFileName(), "resend must email the void-created current version's PDF");
    }

    // ================================================================
    // Phase 16F PR1 — decision D5(c): an in-app invoice acceptance (D.8) kills an ACTIVE quote link
    // ================================================================
    //
    // A successful D.8 moves the order's ISSUED quote_version to CANCELLED and its ACTIVE quote_token to
    // CANCELLED (dead_at = the appended invoice version's accepted_at) inside D.8's own persist
    // transaction, under the order row lock. No ISSUED version -> silent no-op. ACCEPTED / SUPERSEDED /
    // EXPIRED / CANCELLED versions and their (already dead) tokens are never touched, and an accepted
    // quote never blocks D.8 (D5(d), one direction only).
    //
    // Self-seeded (Phase 14D go-forward rule) — the V4 ORDER_FULL fixture is NOT used here: each test
    // INSERTs its own invoice-ready SOFT LEAD order (V4 business 1 / store 1 / user 1 only), creates
    // invoice v1 through D.1 and, where the scenario needs a quote, saves a draft through the protected
    // PUT and ISSUES it through POST .../quote/send-email; the plaintext token is read from the recorded
    // link-only quote email (the only place it ever appears).

    private static final String D5C_ORDER_NUMBER_PREFIX = "IACQT.ZZ9.";
    // Rolled-back tests allocate from this class's own fixed range (their rows never outlive the test);
    // the two tests that COMMIT their fixture take the next FREE business sequence instead (see
    // insertCommittedD5cOrder), so a leftover from a crashed run can never block a re-run.
    private static final int D5C_SEQ_BASE = 31_000;
    private static final String D5C_FIRST_NAME = "Dana";
    private static final String D5C_LAST_NAME = "Quoteholder";
    private static final String D5C_CUSTOMER_NAME = D5C_FIRST_NAME + " " + D5C_LAST_NAME;
    private static final String D5C_CUSTOMER_EMAIL = "dana.quoteholder@example.com";
    private static final String D5C_DETAILS_OF_SALE = "Supply and lay carpet throughout";
    // The public link inside the recorded link-only quote email: <app-base>/q/{token}.
    private static final Pattern QUOTE_LINK_TOKEN_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]{43,128})");

    private int d5cSeq = D5C_SEQ_BASE;

    /** A self-seeded order with an unaccepted invoice v1 + an ISSUED quote v1 and its ACTIVE link's token. */
    private record IssuedQuoteOrder(long orderId, String token) {
    }

    // ---- URLs ----

    private static String invoicesUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/invoices";
    }

    private static String quoteUrl(Object orderId, String action) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/" + action;
    }

    private static String publicQuoteUrl(String token) {
        return "/api/v1/public/quotes/" + token;
    }

    // ---- fixtures ----

    /**
     * Detach every hydrated entity: the MockMvc calls of one test share the test transaction's
     * persistence context, so after a raw JDBC write a later JPA read could return a stale entity.
     * clear() only — every write in these flows is native JDBC (no dirty managed entity to lose), and
     * clear() is also safe outside a transaction, where flush() would throw.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    /** Rolled-back tests: the next number of this class's fixed sequence range. */
    private long insertD5cOrder() {
        return insertD5cOrder(++d5cSeq);
    }

    /**
     * COMMITTED fixtures: the next FREE business sequence (MAX + 1, as order creation allocates it), so
     * a row left behind by a crashed run (finally never ran) can never collide with a re-run.
     */
    private long insertCommittedD5cOrder() {
        Integer next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(order_sequence_number), 0) + 1 FROM sales_order WHERE business_id = ?",
                Integer.class, BUSINESS_AUSSIE);
        Assertions.assertNotNull(next, "setup: no next order_sequence_number");
        return insertD5cOrder(next);
    }

    /**
     * INSERT a SOFT LEAD order header that already carries D.1's order-level preconditions (details of
     * sale, proposed lay date + lay date status). V4 business 1 / store 1 / user 1 only.
     */
    private long insertD5cOrder(int seq) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, "
                        + " details_of_sale, proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 1, 2026, "
                        + " ?, DATE '2026-12-01', 'CONFIRMED'::lay_date_status) "
                        + "RETURNING order_id",
                Long.class,
                BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, seq,
                D5C_ORDER_NUMBER_PREFIX + String.format("%05d", seq % 100_000),
                D5C_DETAILS_OF_SALE);
    }

    private int orderSequenceNumber(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_sequence_number FROM sales_order WHERE order_id = ?", Integer.class, orderId);
    }

    private static String d5cChargeCode(int orderSequenceNumber) {
        return "IAQ" + orderSequenceNumber;
    }

    /**
     * Make the order pass D.1's email gate + 9 preconditions: customer (first/last name, valid email),
     * INSTALLATION + BILLING addresses and ONE priced charge line on a self-seeded store_charge
     * (500.00 ex -> invoice 550.00 inc; cost 200.00, so the 250.00-ex quote below is not below cost).
     */
    private void seedD5cInvoiceReadiness(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, ?, ?, ?, '0412345678')",
                orderId, D5C_FIRST_NAME, D5C_LAST_NAME, D5C_CUSTOMER_EMAIL);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, '7', 'Install Street', 'Sydney', 'NSW', '2000')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, '12', 'Billing Street', 'Sydney', 'NSW', '2000')",
                orderId);
        String code = d5cChargeCode(orderSequenceNumber(orderId));
        BigDecimal lineTotal = new BigDecimal("500.00");
        BigDecimal lineCost = new BigDecimal("200.00");
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'D5c test charge', ?, ?) RETURNING charge_id",
                Long.class, STORE_SYD_CBD, code, lineTotal, lineCost);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'D5c test charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code, lineTotal, lineCost, lineTotal, lineTotal, lineCost);
    }

    /** D.1 POST .../invoices — create the unaccepted invoice v1 from the live order (empty body). */
    private void createInvoice(long orderId) throws Exception {
        mockMvc.perform(post(invoicesUrl(orderId)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.version_number").value(1));
        clearJpaCache();
    }

    /** A self-seeded order with invoice v1 created through D.1 and NO quote rows at all. */
    private long seedInvoicedOrder() throws Exception {
        long orderId = insertD5cOrder();
        seedD5cInvoiceReadiness(orderId);
        createInvoice(orderId);
        return orderId;
    }

    /** PUT .../quote/draft — itemised Carpet 2x100 + Underlay 1x50 = 250.00 ex / 275.00 inc. */
    private void saveItemisedQuoteDraft(long orderId) throws Exception {
        mockMvc.perform(put(quoteUrl(orderId, "draft")).session(liamStore1Session())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    /** PUT .../quote/draft — non-itemised, the given final inc-GST total (header-only save). */
    private void saveNonItemisedQuoteDraft(long orderId, String finalIncTotal) throws Exception {
        mockMvc.perform(put(quoteUrl(orderId, "draft")).session(liamStore1Session())
                        .contentType(APPLICATION_JSON)
                        .content("{\"itemised\": false, \"final_total_inc_gst\": " + finalIncTotal
                                + ", \"lines\": []}"))
                .andExpect(status().isOk());
        clearJpaCache();
    }

    /**
     * POST .../quote/send-email (issue a new version, or resend the unchanged one) -> 201, then return
     * the plaintext token from the LATEST recorded link-only quote email.
     */
    private String sendQuoteEmail(long orderId) throws Exception {
        clearJpaCache();
        mockMvc.perform(post(quoteUrl(orderId, "send-email")).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        clearJpaCache();
        List<QuoteEmailRequest> sent = recordingQuoteEmailSender.sentEmails();
        Assertions.assertFalse(sent.isEmpty(), "a quote email must have been recorded");
        String body = sent.get(sent.size() - 1).bodyText();
        Matcher matcher = QUOTE_LINK_TOKEN_PATTERN.matcher(body);
        Assertions.assertTrue(matcher.find(), () -> "the quote email must carry the /q/{token} link: " + body);
        return matcher.group(1);
    }

    /** {@link #seedInvoicedOrder()} + the itemised draft ISSUED by email: quote v1 ISSUED + its ACTIVE token. */
    private IssuedQuoteOrder seedInvoicedOrderWithIssuedQuote() throws Exception {
        long orderId = seedInvoicedOrder();
        saveItemisedQuoteDraft(orderId);
        return new IssuedQuoteOrder(orderId, sendQuoteEmail(orderId));
    }

    /** A valid D.8 request for a self-seeded order: the signature PNG + the saved customer's name. */
    private MockMultipartHttpServletRequestBuilder signInvoice(long orderId) {
        return (MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(orderId))
                .file(signaturePart(ONE_PIXEL_PNG))
                .param(ACCEPTED_NAME_FIELD, D5C_CUSTOMER_NAME);
    }

    /**
     * Sign the ISSUED quote through the REAL token-only public endpoint (no session; exactly one
     * signature PNG part) -> 201 INACTIVE. Inside the test transaction the acceptance joins it, so its
     * rows roll back with the test and its files are removed by the rollback hooks.
     */
    private void acceptQuoteViaPublicLink(String token) throws Exception {
        clearJpaCache();
        mockMvc.perform(multipart(publicQuoteUrl(token) + "/accept").file(signaturePart(ONE_PIXEL_PNG)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"));
        clearJpaCache();
    }

    /** Public GET (no session): 200 with the link's customer-facing state. */
    private void assertPublicQuoteState(String token, String expectedState) throws Exception {
        mockMvc.perform(get(publicQuoteUrl(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value(expectedState));
    }

    /** Public accept of a dead link: the token gate answers 410 before the (valid) signature is read. */
    private void assertPublicAcceptGone(String token, String expectedCode) throws Exception {
        mockMvc.perform(multipart(publicQuoteUrl(token) + "/accept").file(signaturePart(ONE_PIXEL_PNG)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value(expectedCode));
    }

    // ---- DB probes ----

    private Map<String, Object> quoteVersionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private List<Map<String, Object>> quoteVersionRows(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId);
    }

    private List<String> quoteVersionStatuses(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM quote_version WHERE order_id = ? ORDER BY version_number", String.class, orderId);
    }

    private int quoteVersionCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
    }

    /** The token row behind a plaintext token (only its SHA-256 hash is stored). */
    private Map<String, Object> quoteTokenRow(String plainToken) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_token WHERE token_hash = ?", sha256Hex(plainToken));
    }

    private List<Map<String, Object>> quoteTokenRows(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY t.quote_token_id", orderId);
    }

    private List<String> quoteTokenStatuses(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT t.status FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY t.quote_token_id", String.class, orderId);
    }

    private int quoteTokenCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ?", Integer.class, orderId);
    }

    private Map<String, Object> quoteDraftRow(long orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_draft WHERE order_id = ?", orderId);
    }

    private List<Map<String, Object>> quoteDraftLineRows(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT l.* FROM quote_draft_line l JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id "
                        + "WHERE d.order_id = ? ORDER BY l.quote_draft_line_id", orderId);
    }

    private int quoteDraftCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_draft WHERE order_id = ?", Integer.class, orderId);
    }

    /** FileStorageService virtual-path prefix of everything stored for this order. */
    private static String orderStoragePrefix(long orderId) {
        return "/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/";
    }

    private int storedFileCountForOrder(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ?", Integer.class,
                orderStoragePrefix(orderId) + "%");
    }

    private long invoicePdfFileId(long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE invoice_id = ?", Long.class, invoiceId);
    }

    /** The order's working-price header (what the D6b quote acceptance writes; D.8 must never move it). */
    private Map<String, Object> orderWorkingPrice(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT price_adjustment_inc_gst, sale_price_ex_gst, total_cost, gp, gp_percent, updated_at "
                        + "FROM sales_order WHERE order_id = ?", orderId);
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_status::text FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private void backdateQuoteTokenExpiry(String plainToken) {
        int updated = jdbcTemplate.update(
                "UPDATE quote_token SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
                sha256Hex(plainToken));
        Assertions.assertEquals(1, updated, "expected to backdate exactly one quote token");
        clearJpaCache();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A copy of {@code row} with the given (column, value) pairs overridden. */
    private static Map<String, Object> withColumns(Map<String, Object> row, Object... columnValuePairs) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        for (int i = 0; i < columnValuePairs.length; i += 2) {
            copy.put((String) columnValuePairs[i], columnValuePairs[i + 1]);
        }
        return copy;
    }

    /**
     * D5(c) outcome for one link: the version moved ISSUED -> CANCELLED and NOTHING else on it changed
     * (no acceptance fields, no snapshot edit, no marker); its token moved ACTIVE -> CANCELLED with
     * {@code dead_at} == the appended invoice version's {@code accepted_at} (same instant, same
     * transaction) and nothing else on it changed.
     */
    private void assertLinkCancelledByInvoiceAcceptance(long orderId, int versionNumber, String token,
                                                        Map<String, Object> versionBefore,
                                                        Map<String, Object> tokenBefore) {
        Assertions.assertEquals("ISSUED", versionBefore.get("status"), "precondition: the version was ISSUED");
        Assertions.assertEquals("ACTIVE", tokenBefore.get("status"), "precondition: the token was ACTIVE");
        Timestamp invoiceAcceptedAt = invoiceAcceptedAt(latestInvoiceId(orderId));
        Assertions.assertNotNull(invoiceAcceptedAt, "D.8 must have appended an ACCEPTED invoice version");
        Assertions.assertEquals(withColumns(versionBefore, "status", "CANCELLED"),
                quoteVersionRow(orderId, versionNumber),
                "D.8 may change ONLY the quote version's status (ISSUED -> CANCELLED)");
        Assertions.assertEquals(withColumns(tokenBefore, "status", "CANCELLED", "dead_at", invoiceAcceptedAt),
                quoteTokenRow(token),
                "D.8 may change ONLY the token's status + dead_at (ACTIVE -> CANCELLED at the acceptance time)");
    }

    /** The link is exactly as it was: version row + token row unchanged. */
    private void assertQuoteLinkUntouched(String step, long orderId, int versionNumber, String token,
                                          Map<String, Object> versionBefore, Map<String, Object> tokenBefore) {
        Assertions.assertEquals(versionBefore, quoteVersionRow(orderId, versionNumber),
                step + ": the quote version must be untouched");
        Assertions.assertEquals(tokenBefore, quoteTokenRow(token), step + ": the quote token must be untouched");
    }

    /**
     * Delete everything a COMMITTED fixture order owns — autocommit, children first (FK order). The
     * order's stored_file rows (invoice PDFs, signature PNGs, issued quote PDFs) are matched by the
     * FileStorageService virtual-path prefix; their bytes live in the class TempDir, which JUnit
     * removes. Must run outside any test-managed transaction (or the deletes would roll back too).
     */
    private void deleteCommittedD5cOrder(long orderId) {
        List<Integer> seqs = jdbcTemplate.queryForList(
                "SELECT order_sequence_number FROM sales_order WHERE order_id = ?", Integer.class, orderId);
        jdbcTemplate.update("DELETE FROM quote_token WHERE quote_version_id IN "
                + "(SELECT quote_version_id FROM quote_version WHERE order_id = ?)", orderId);
        jdbcTemplate.update("DELETE FROM invoice WHERE order_id = ?", orderId);
        jdbcTemplate.update("DELETE FROM quote_version WHERE order_id = ?", orderId); // version lines cascade
        jdbcTemplate.update("DELETE FROM quote_draft WHERE order_id = ?", orderId);   // draft lines cascade
        for (String table : List.of("payment_transaction", "order_attachment", "order_note", "order_enquiry",
                "order_charge_line", "order_product_line", "order_address", "order_customer")) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id = ?", orderId);
        }
        jdbcTemplate.update("DELETE FROM stored_file WHERE storage_path LIKE ?", orderStoragePrefix(orderId) + "%");
        jdbcTemplate.update("DELETE FROM sales_order WHERE order_id = ?", orderId);
        for (Integer seq : seqs) {
            jdbcTemplate.update("DELETE FROM store_charge WHERE store_id = ? AND code = ?",
                    STORE_SYD_CBD, d5cChargeCode(seq));
        }
    }

    // ---- 1. success: the ISSUED version + ACTIVE token die with the invoice signature ----

    @Test
    void accept_withIssuedQuote_cancelsIssuedVersionAndActiveToken_linkIsDead() throws Exception {
        IssuedQuoteOrder fx = seedInvoicedOrderWithIssuedQuote();
        long orderId = fx.orderId();
        Map<String, Object> versionBefore = quoteVersionRow(orderId, 1);
        Map<String, Object> tokenBefore = quoteTokenRow(fx.token());
        Map<String, Object> draftBefore = quoteDraftRow(orderId);
        List<Map<String, Object>> draftLinesBefore = quoteDraftLineRows(orderId);
        int storedFilesBefore = storedFileCountForOrder(orderId);
        int quoteEmailsBefore = recordingQuoteEmailSender.sentEmails().size();
        mockMvc.perform(get(quoteUrl(orderId, "workspace")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued.version_number").value(1))
                .andExpect(jsonPath("$.data.current_issued.status").value("ISSUED"));

        MvcResult result = mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAILED_MESSAGE))
                .andExpect(jsonPath("$.data.invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.invoice.accepted_customer_name").value(D5C_CUSTOMER_NAME))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(true))
                .andReturn();

        // The link is dead: ONLY version.status (ISSUED -> CANCELLED) and token.status + dead_at (ACTIVE ->
        // CANCELLED at the invoice's accepted_at) moved; no quote row was added.
        assertLinkCancelledByInvoiceAcceptance(orderId, 1, fx.token(), versionBefore, tokenBefore);
        Assertions.assertEquals(1, quoteVersionCount(orderId), "no quote version may be added");
        Assertions.assertEquals(1, quoteTokenCount(orderId), "no quote token may be added");

        // Nothing else quote-related moved: draft + its lines unchanged, no quote email, no stored_file
        // beyond D.8's own two (signature PNG + signed invoice PDF), no acceptance notification.
        Assertions.assertEquals(draftBefore, quoteDraftRow(orderId), "the quote draft must be untouched");
        Assertions.assertEquals(draftLinesBefore, quoteDraftLineRows(orderId), "draft lines must be untouched");
        Assertions.assertEquals(quoteEmailsBefore, recordingQuoteEmailSender.sentEmails().size());
        Assertions.assertEquals(storedFilesBefore + 2, storedFileCountForOrder(orderId));
        Assertions.assertTrue(recordingQuoteAcceptanceNotificationSender.sentNotifications().isEmpty());
        // D.8's own contract is unchanged: exactly one auto-email; no quote token material in the response.
        Assertions.assertEquals(1, recordingInvoiceEmailSender.sentEmails().size());
        String json = result.getResponse().getContentAsString();
        Assertions.assertFalse(json.contains(fx.token()), "the quote token must never appear in D.8's response");
        Assertions.assertFalse(json.contains("/q/"), "no public quote link in D.8's response");

        // Protected workspace: no active issued quote any more, nothing accepted, the draft is still there.
        mockMvc.perform(get(quoteUrl(orderId, "workspace")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()))
                .andExpect(jsonPath("$.data.accepted").value(nullValue()))
                .andExpect(jsonPath("$.data.draft").value(notNullValue()));

        // Public surface: GET reports CANCELLED (200); the accept is 410 QUOTE_LINK_CANCELLED and writes nothing.
        Map<String, Object> versionCancelled = quoteVersionRow(orderId, 1);
        Map<String, Object> tokenCancelled = quoteTokenRow(fx.token());
        assertPublicQuoteState(fx.token(), "CANCELLED");
        assertPublicAcceptGone(fx.token(), "QUOTE_LINK_CANCELLED");
        Assertions.assertEquals(versionCancelled, quoteVersionRow(orderId, 1));
        Assertions.assertEquals(tokenCancelled, quoteTokenRow(fx.token()));
        Assertions.assertEquals(storedFilesBefore + 2, storedFileCountForOrder(orderId));
        Assertions.assertTrue(recordingQuoteAcceptanceNotificationSender.sentNotifications().isEmpty());
    }

    // ---- 2. rejected D.8 requests never touch the link (the kill happens only in the persist) ----

    @Test
    void accept_rejectedRequests_neverTouchTheQuoteLink_thenAValidAcceptCancelsIt() throws Exception {
        IssuedQuoteOrder fx = seedInvoicedOrderWithIssuedQuote();
        long orderId = fx.orderId();
        String token = fx.token();
        Map<String, Object> versionBefore = quoteVersionRow(orderId, 1);
        Map<String, Object> tokenBefore = quoteTokenRow(token);
        int invoicesBefore = countInvoices(orderId);
        int storedFilesBefore = storedFileCountForOrder(orderId);

        // 401 / 403: the session guard, before the order is even read.
        mockMvc.perform(signInvoice(orderId))
                .andExpect(status().isUnauthorized());
        assertQuoteLinkUntouched("401 no session", orderId, 1, token, versionBefore, tokenBefore);
        mockMvc.perform(signInvoice(orderId).session(liamSessionNoStore()))
                .andExpect(status().isForbidden());
        assertQuoteLinkUntouched("403 no store", orderId, 1, token, versionBefore, tokenBefore);

        // 400: an unexpected form field — even a quote-shaped one — is refused before any write.
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(orderId))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, D5C_CUSTOMER_NAME)
                        .param("quote_version_id", "1"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("quote_version_id"));
        assertQuoteLinkUntouched("400 extra form field", orderId, 1, token, versionBefore, tokenBefore);

        // 400: an unexpected extra FILE part.
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(orderId))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .file(new MockMultipartFile("quote_signature", "x.png", "image/png", ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, D5C_CUSTOMER_NAME))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("quote_signature"));
        assertQuoteLinkUntouched("400 extra file part", orderId, 1, token, versionBefore, tokenBefore);

        // 422: blank accepted name.
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(orderId))
                        .file(signaturePart(ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, "   "))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ACCEPTED_CUSTOMER_NAME_REQUIRED"));
        assertQuoteLinkUntouched("422 blank name", orderId, 1, token, versionBefore, tokenBefore);

        // 422: missing signature part.
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(orderId))
                        .param(ACCEPTED_NAME_FIELD, D5C_CUSTOMER_NAME))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SIGNATURE_REQUIRED"));
        assertQuoteLinkUntouched("422 missing signature", orderId, 1, token, versionBefore, tokenBefore);

        // 400: a signature declared as a non-PNG type.
        mockMvc.perform(((MockMultipartHttpServletRequestBuilder) multipart(acceptUrl(orderId))
                        .file(new MockMultipartFile(SIGNATURE_PART, "signature.jpg", "image/jpeg", ONE_PIXEL_PNG))
                        .param(ACCEPTED_NAME_FIELD, D5C_CUSTOMER_NAME))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));
        assertQuoteLinkUntouched("400 non-PNG signature", orderId, 1, token, versionBefore, tokenBefore);

        // 422: the customer-email gate — D.8's LAST validation before the persist (and therefore before
        // the link kill): a fully valid request against a malformed saved email must leave the link live.
        setCustomerEmail(orderId, "dana@");
        clearJpaCache();
        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_EMAIL_INVALID"));
        assertQuoteLinkUntouched("422 customer email gate", orderId, 1, token, versionBefore, tokenBefore);

        // No rejection appended an invoice version, wrote a file or attempted an email; the customer's link
        // still opens as ACTIVE.
        Assertions.assertEquals(invoicesBefore, countInvoices(orderId));
        Assertions.assertEquals(storedFilesBefore, storedFileCountForOrder(orderId));
        Assertions.assertTrue(recordingInvoiceEmailSender.sentEmails().isEmpty());
        Assertions.assertTrue(recordingInvoiceEmailSender.failedEmails().isEmpty());
        assertPublicQuoteState(token, "ACTIVE");

        // The link survived every rejection; a valid acceptance now kills exactly that link.
        setCustomerEmail(orderId, D5C_CUSTOMER_EMAIL);
        clearJpaCache();
        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated());
        assertLinkCancelledByInvoiceAcceptance(orderId, 1, token, versionBefore, tokenBefore);
    }

    @Test
    void accept_alreadyAcceptedInvoice409_neverTouchesTheQuoteLink() throws Exception {
        // An accepted current invoice + a live ISSUED quote is reachable (e.g. a quote issued after the
        // in-app signature — D5(d) is one direction only — or a double-submit losing the race). The 409
        // fires before the persist, so it must never kill the link.
        IssuedQuoteOrder fx = seedInvoicedOrderWithIssuedQuote();
        long orderId = fx.orderId();
        Map<String, Object> versionBefore = quoteVersionRow(orderId, 1);
        Map<String, Object> tokenBefore = quoteTokenRow(fx.token());
        seedAcceptance(orderId);
        int invoicesBefore = countInvoices(orderId);

        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVOICE_ALREADY_ACCEPTED"));

        assertQuoteLinkUntouched("409 already accepted", orderId, 1, fx.token(), versionBefore, tokenBefore);
        Assertions.assertEquals(invoicesBefore, countInvoices(orderId));
        assertPublicQuoteState(fx.token(), "ACTIVE");
    }

    // ---- 3. a rolled-back D.8 rolls the link kill back with it ----

    /**
     * Observable approach: the fixture (order, invoice v1, ISSUED quote v1 + its ACTIVE token) is
     * COMMITTED first, so it is real state that survives a later rollback. D.8 then runs in a fresh
     * test-managed transaction (its programmatic TransactionTemplate joins it, exactly like the existing
     * {@code accept_transactionRollback_...} test), the in-flight cancel is asserted, and that
     * transaction is rolled back. Afterwards only COMMITTED state is visible (autocommit reads): the
     * committed version is ISSUED again and the committed token ACTIVE with no {@code dead_at} — the link
     * kill was part of the SAME unit of work as the invoice acceptance. Had it committed on its own (e.g.
     * a REQUIRES_NEW / separately committed write) the committed rows would still read CANCELLED here. (A
     * quote seeded INSIDE the rolled-back transaction would simply vanish, which proves nothing.) The
     * auto-email recorded during D.8 is a test artifact: in production it is only attempted after a real
     * commit. The committed fixture is deleted in {@code finally}.
     */
    @Test
    void accept_rolledBack_committedQuoteLinkIsStillIssuedAndActive() throws Exception {
        // Phase 1 — seed + invoice + issue inside the test-managed transaction, then COMMIT it.
        long orderId = insertCommittedD5cOrder();
        seedD5cInvoiceReadiness(orderId);
        createInvoice(orderId);
        saveItemisedQuoteDraft(orderId);
        String token = sendQuoteEmail(orderId);
        Map<String, Object> committedVersion = quoteVersionRow(orderId, 1);
        Map<String, Object> committedToken = quoteTokenRow(token);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            // Phase 2 — D.8 in a fresh test-managed transaction, then roll it back.
            TestTransaction.start();
            mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                    .andExpect(status().isCreated());
            long acceptedInvoiceId = latestInvoiceId(orderId);
            String signaturePath = storagePathOf(acceptedSignatureFileId(acceptedInvoiceId));
            String signedPdfPath = storagePathOf(invoicePdfFileId(acceptedInvoiceId));
            Assertions.assertTrue(diskFileExists(signaturePath));
            Assertions.assertTrue(diskFileExists(signedPdfPath));
            // In flight (same transaction): the link IS cancelled, together with the acceptance.
            assertLinkCancelledByInvoiceAcceptance(orderId, 1, token, committedVersion, committedToken);

            TestTransaction.flagForRollback();
            TestTransaction.end();

            // Phase 3 — no transaction: only COMMITTED state is visible. The link kill rolled back WITH
            // the acceptance.
            Assertions.assertEquals(committedVersion, quoteVersionRow(orderId, 1),
                    "the rolled-back D.8 must leave the committed quote version ISSUED, unchanged");
            Assertions.assertEquals(committedToken, quoteTokenRow(token),
                    "the rolled-back D.8 must leave the committed token ACTIVE with no dead_at");
            Assertions.assertEquals(1, countInvoices(orderId), "the accepted invoice version must be rolled back");
            Assertions.assertNull(invoiceAcceptedAt(latestInvoiceId(orderId)), "v1 stays the unsigned current invoice");
            Assertions.assertFalse(diskFileExists(signaturePath), "rolled-back signature file must be deleted");
            Assertions.assertFalse(diskFileExists(signedPdfPath), "rolled-back signed PDF must be deleted");
            // ...and the customer's link still works on the real public surface.
            assertPublicQuoteState(token, "ACTIVE");
        } finally {
            if (TestTransaction.isActive()) {
                TestTransaction.flagForRollback();
                TestTransaction.end();
            }
            deleteCommittedD5cOrder(orderId);
        }
    }

    // ---- 4. the post-commit auto-email failure never resurrects the link (durable, real commits) ----

    /**
     * Runs OUTSIDE the test transaction, so every MockMvc call commits for real (production semantics):
     * D.8 commits the acceptance + the link kill, and only THEN attempts the auto-email, which is forced
     * to fail. The committed state proves the email failure undid nothing and the link stays CANCELLED.
     * Self-cleaning: everything committed is deleted in {@code finally}.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void accept_postCommitEmailFailure_quoteLinkStaysCancelled_outsideTestTransaction() throws Exception {
        long orderId = insertCommittedD5cOrder(); // autocommit — from here on everything is committed state
        try {
            seedD5cInvoiceReadiness(orderId);
            createInvoice(orderId);
            saveItemisedQuoteDraft(orderId);
            String token = sendQuoteEmail(orderId);
            Map<String, Object> versionBefore = quoteVersionRow(orderId, 1);
            Map<String, Object> tokenBefore = quoteTokenRow(token);

            recordingInvoiceEmailSender.failNextSend();
            mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value(ACCEPT_EMAIL_FAILED_MESSAGE))
                    .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(true));

            long acceptedInvoiceId = latestInvoiceId(orderId);
            Assertions.assertNotNull(invoiceAcceptedAt(acceptedInvoiceId), "acceptance is committed");
            Assertions.assertNull(invoiceLastEmailedAt(acceptedInvoiceId));
            Assertions.assertNull(orderLastEmailedAt(orderId));
            Assertions.assertEquals(0, recordingInvoiceEmailSender.sentEmails().size());
            Assertions.assertEquals(1, recordingInvoiceEmailSender.failedEmails().size());
            assertLinkCancelledByInvoiceAcceptance(orderId, 1, token, versionBefore, tokenBefore);

            // Real public requests (each in its own transaction) see the durable dead link.
            Map<String, Object> versionCancelled = quoteVersionRow(orderId, 1);
            Map<String, Object> tokenCancelled = quoteTokenRow(token);
            assertPublicQuoteState(token, "CANCELLED");
            assertPublicAcceptGone(token, "QUOTE_LINK_CANCELLED");
            Assertions.assertEquals(versionCancelled, quoteVersionRow(orderId, 1));
            Assertions.assertEquals(tokenCancelled, quoteTokenRow(token));
            Assertions.assertTrue(recordingQuoteAcceptanceNotificationSender.sentNotifications().isEmpty());
        } finally {
            deleteCommittedD5cOrder(orderId);
        }
    }

    // ---- 5 / 6. an ACCEPTED quote never blocks D.8 and is never touched by it ----

    @Test
    void accept_withAcceptedQuote_isNeverBlocked_andLeavesTheAcceptedQuoteUntouched() throws Exception {
        IssuedQuoteOrder fx = seedInvoicedOrderWithIssuedQuote();
        long orderId = fx.orderId();
        acceptQuoteViaPublicLink(fx.token());
        Map<String, Object> acceptedVersion = quoteVersionRow(orderId, 1);
        Map<String, Object> consumedToken = quoteTokenRow(fx.token());
        Assertions.assertEquals("ACCEPTED", acceptedVersion.get("status"));
        Assertions.assertEquals("CONSUMED", consumedToken.get("status"));
        Map<String, Object> signedWorkingPrice = orderWorkingPrice(orderId);
        mockMvc.perform(get(quoteUrl(orderId, "workspace")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()))
                .andExpect(jsonPath("$.data.accepted.version_number").value(1))
                .andExpect(jsonPath("$.data.accepted.invoice_eligible").value(true));

        // D5(d): the accepted quote never blocks the in-app signature — D.8 behaves exactly as usual...
        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAILED_MESSAGE))
                .andExpect(jsonPath("$.data.invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(true));

        // ...and never revokes it: the ACCEPTED version (acceptance fields, signature + signed-PDF refs) and
        // its CONSUMED token are unchanged; nothing was cancelled or added.
        Assertions.assertEquals(acceptedVersion, quoteVersionRow(orderId, 1));
        Assertions.assertEquals(consumedToken, quoteTokenRow(fx.token()));
        Assertions.assertEquals(List.of("ACCEPTED"), quoteVersionStatuses(orderId));
        Assertions.assertEquals(List.of("CONSUMED"), quoteTokenStatuses(orderId));
        // D.8 carries the invoice snapshot forward and never rewrites the price the signed quote set (D6b).
        Assertions.assertEquals(signedWorkingPrice, orderWorkingPrice(orderId));

        // The accepted quote stays visible; conversion is now unavailable because the CURRENT invoice was
        // signed AFTER the quote was accepted (amended D5(b): only a quote signature strictly later than
        // the current invoice's signature may be invoiced, so invoice_eligible = false). The consumed link
        // stays INACTIVE (never re-labelled).
        mockMvc.perform(get(quoteUrl(orderId, "workspace")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()))
                .andExpect(jsonPath("$.data.accepted.version_number").value(1))
                .andExpect(jsonPath("$.data.accepted.invoice_eligible").value(false));
        assertPublicQuoteState(fx.token(), "INACTIVE");
    }

    @Test
    void accept_withAcceptedV1AndIssuedV2_cancelsOnlyV2AndItsToken() throws Exception {
        IssuedQuoteOrder fx = seedInvoicedOrderWithIssuedQuote();
        long orderId = fx.orderId();
        acceptQuoteViaPublicLink(fx.token());
        // D9: a changed quote sent after the acceptance is a NEW version (v2) with a fresh ACTIVE link.
        saveNonItemisedQuoteDraft(orderId, "440.00");
        String v2Token = sendQuoteEmail(orderId);
        Map<String, Object> v1 = quoteVersionRow(orderId, 1);
        Map<String, Object> v1Token = quoteTokenRow(fx.token());
        Map<String, Object> v2 = quoteVersionRow(orderId, 2);
        Map<String, Object> v2TokenBefore = quoteTokenRow(v2Token);
        Assertions.assertEquals("ACCEPTED", v1.get("status"));
        Assertions.assertEquals("CONSUMED", v1Token.get("status"));
        Assertions.assertEquals(v2.get("quote_version_id"), v2TokenBefore.get("quote_version_id"),
                "the ACTIVE link belongs to v2");

        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated());

        assertLinkCancelledByInvoiceAcceptance(orderId, 2, v2Token, v2, v2TokenBefore);
        Assertions.assertEquals(v1, quoteVersionRow(orderId, 1), "the ACCEPTED v1 is never touched");
        Assertions.assertEquals(v1Token, quoteTokenRow(fx.token()), "v1's CONSUMED token is never touched");
        Assertions.assertEquals(List.of("ACCEPTED", "CANCELLED"), quoteVersionStatuses(orderId));
        Assertions.assertEquals(List.of("CONSUMED", "CANCELLED"), quoteTokenStatuses(orderId));

        assertPublicQuoteState(v2Token, "CANCELLED");
        assertPublicAcceptGone(v2Token, "QUOTE_LINK_CANCELLED");
        assertPublicQuoteState(fx.token(), "INACTIVE");
        assertPublicAcceptGone(fx.token(), "QUOTE_LINK_INACTIVE");
        mockMvc.perform(get(quoteUrl(orderId, "workspace")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()))
                .andExpect(jsonPath("$.data.accepted.version_number").value(1));
    }

    @Test
    void accept_afterQuoteResend_cancelsOnlyTheActiveToken_replacedTokenKeepsItsReason() throws Exception {
        IssuedQuoteOrder fx = seedInvoicedOrderWithIssuedQuote();
        long orderId = fx.orderId();
        // Unchanged resend: the SAME ISSUED v1, its first token -> REPLACED, a fresh ACTIVE token.
        String liveToken = sendQuoteEmail(orderId);
        Assertions.assertNotEquals(fx.token(), liveToken);
        Assertions.assertEquals(1, quoteVersionCount(orderId));
        Map<String, Object> versionBefore = quoteVersionRow(orderId, 1);
        Map<String, Object> replacedToken = quoteTokenRow(fx.token());
        Map<String, Object> liveTokenBefore = quoteTokenRow(liveToken);
        Assertions.assertEquals("REPLACED", replacedToken.get("status"));
        Assertions.assertNotNull(replacedToken.get("dead_at"));

        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated());

        assertLinkCancelledByInvoiceAcceptance(orderId, 1, liveToken, versionBefore, liveTokenBefore);
        Assertions.assertEquals(replacedToken, quoteTokenRow(fx.token()),
                "an already-dead token keeps its REPLACED reason and its original dead_at");
        assertPublicQuoteState(fx.token(), "SUPERSEDED");
        assertPublicQuoteState(liveToken, "CANCELLED");
    }

    // ---- 7. no ISSUED version -> D.8 is unchanged and touches no quote row ----

    @Test
    void accept_withNoQuoteAtAll_isUnchanged_andCreatesNoQuoteRows() throws Exception {
        long orderId = seedInvoicedOrder();

        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAILED_MESSAGE))
                .andExpect(jsonPath("$.data.invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(true));

        Assertions.assertNotNull(invoiceAcceptedAt(latestInvoiceId(orderId)));
        Assertions.assertEquals(0, quoteDraftCount(orderId));
        Assertions.assertEquals(0, quoteVersionCount(orderId));
        Assertions.assertEquals(0, quoteTokenCount(orderId));
        Assertions.assertEquals(1, recordingInvoiceEmailSender.sentEmails().size());
        Assertions.assertTrue(recordingQuoteEmailSender.sentEmails().isEmpty());
        mockMvc.perform(get(quoteUrl(orderId, "workspace")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft").value(nullValue()))
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()))
                .andExpect(jsonPath("$.data.accepted").value(nullValue()));
    }

    @Test
    void accept_withDraftOnlyQuote_isUnchanged_andLeavesTheDraftUntouched() throws Exception {
        long orderId = seedInvoicedOrder();
        saveItemisedQuoteDraft(orderId);
        Map<String, Object> draftBefore = quoteDraftRow(orderId);
        List<Map<String, Object>> draftLinesBefore = quoteDraftLineRows(orderId);
        Assertions.assertEquals(2, draftLinesBefore.size());

        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAILED_MESSAGE))
                .andExpect(jsonPath("$.data.invoice.version_number").value(2));

        Assertions.assertEquals(draftBefore, quoteDraftRow(orderId), "the draft header must be untouched");
        Assertions.assertEquals(draftLinesBefore, quoteDraftLineRows(orderId), "the draft lines must be untouched");
        Assertions.assertEquals(0, quoteVersionCount(orderId));
        Assertions.assertEquals(0, quoteTokenCount(orderId));
        Assertions.assertTrue(recordingQuoteEmailSender.sentEmails().isEmpty());
        mockMvc.perform(get(quoteUrl(orderId, "workspace")).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft.itemised").value(true))
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()))
                .andExpect(jsonPath("$.data.accepted").value(nullValue()));
    }

    @Test
    void accept_withOnlyDeadQuoteHistory_touchesNothing() throws Exception {
        IssuedQuoteOrder fx = seedInvoicedOrderWithIssuedQuote();      // v1 ISSUED (token A)
        long orderId = fx.orderId();
        sendQuoteEmail(orderId);                                      // unchanged resend: A REPLACED, B ACTIVE
        saveNonItemisedQuoteDraft(orderId, "330.00");
        sendQuoteEmail(orderId);                                      // changed: v1 SUPERSEDED (B too), v2 ISSUED (C)
        mockMvc.perform(post(quoteUrl(orderId, "cancel")).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());                          // protected cancel: v2 + C CANCELLED
        clearJpaCache();
        String expiringToken = sendQuoteEmail(orderId);               // nothing ISSUED: v3 ISSUED (D)
        backdateQuoteTokenExpiry(expiringToken);
        assertPublicQuoteState(expiringToken, "EXPIRED");             // lazy expiry: v3 + D EXPIRED
        Assertions.assertEquals(List.of("SUPERSEDED", "CANCELLED", "EXPIRED"), quoteVersionStatuses(orderId));
        Assertions.assertEquals(List.of("REPLACED", "SUPERSEDED", "CANCELLED", "EXPIRED"),
                quoteTokenStatuses(orderId));
        List<Map<String, Object>> versionsBefore = quoteVersionRows(orderId);
        List<Map<String, Object>> tokensBefore = quoteTokenRows(orderId);

        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAILED_MESSAGE));

        // No ISSUED version -> no-op: every dead version and token keeps its own status, reason and dead_at.
        Assertions.assertEquals(versionsBefore, quoteVersionRows(orderId),
                "SUPERSEDED / CANCELLED / EXPIRED versions must never be touched");
        Assertions.assertEquals(tokensBefore, quoteTokenRows(orderId),
                "already-dead tokens must never be touched (no re-labelling, no new dead_at)");
    }

    // ---- 8. LAID: D.8 stays allowed and still kills the link ----

    @Test
    void accept_laidOrderWithIssuedQuote_isAllowed_andCancelsTheQuoteLink() throws Exception {
        // Issued while the order was editable (a quote send is LAID-blocked), then the order is laid.
        IssuedQuoteOrder fx = seedInvoicedOrderWithIssuedQuote();
        long orderId = fx.orderId();
        laidOrder(orderId);
        clearJpaCache();
        Map<String, Object> versionBefore = quoteVersionRow(orderId, 1);
        Map<String, Object> tokenBefore = quoteTokenRow(fx.token());

        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(true));

        assertLinkCancelledByInvoiceAcceptance(orderId, 1, fx.token(), versionBefore, tokenBefore);
        Assertions.assertEquals("LAID", orderStatus(orderId), "neither D.8 nor the link kill changes the order status");
        // The public accept is LAID-allowed (D4), but the link is dead: 410, never a resurrected acceptance.
        assertPublicQuoteState(fx.token(), "CANCELLED");
        assertPublicAcceptGone(fx.token(), "QUOTE_LINK_CANCELLED");
    }

    // ================================================================
    // Phase 16F PR2 - D.8 / D.9 InvoiceDetail terms on a Path B invoice
    // ================================================================
    //
    // A Path B invoice (no source quote version) reports terms_source LIVE and terms_html = the business's
    // CURRENT per-flooring-type terms through the InvoiceTermsSanitizer (JSON null, key present, when there
    // are none) on both the D.8 201 and the D.9 200 response. D.8 carries the current row's two V19
    // columns verbatim, so the signed version of a Path B invoice keeps both NULL. Self-seeded with the
    // D5(c) fixtures above (own SOFT order + invoice v1 through D.1); business 1's terms are set here.

    private static final String PR2_SOFT_TERMS_TEXT = "Live soft acceptance terms apply.";
    private static final String PR2_UPDATED_SOFT_TERMS_TEXT = "Updated soft acceptance terms apply.";
    private static final String PR2_HARD_TERMS_TEXT = "Live hard acceptance terms apply.";
    // Markup the sanitizer strips, so an equal-to-sanitized terms_html proves the sanitizer ran.
    private static final String PR2_LIVE_SOFT_TERMS_RAW =
            "<p style=\"color:red\" onclick=\"steal()\">" + PR2_SOFT_TERMS_TEXT + "</p>";
    private static final String PR2_UPDATED_SOFT_TERMS_RAW = "<p id=\"updated\">" + PR2_UPDATED_SOFT_TERMS_TEXT + "</p>";
    private static final String PR2_LIVE_HARD_TERMS_RAW = "<p>" + PR2_HARD_TERMS_TEXT + "</p>";

    /** Business 1's live per-flooring-type terms (V13 columns); rolled back with the test. */
    private void setTenantTerms(String termsSoft, String termsHard) {
        jdbcTemplate.update("UPDATE business SET terms_soft = ?, terms_hard = ? WHERE business_id = ?",
                termsSoft, termsHard, BUSINESS_AUSSIE);
        clearJpaCache();
    }

    private static int pdfPageCount(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        }
    }

    /** Whitespace-flattened text of one page (1-based). */
    private static String pdfPageText(byte[] pdf, int page) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document).replaceAll("\\s+", " ");
        }
    }

    @Test
    void acceptAndResend_pathBInvoice_reportLiveTermsSource_withTheBusinessCurrentSanitizedTerms() throws Exception {
        setTenantTerms(PR2_LIVE_SOFT_TERMS_RAW, PR2_LIVE_HARD_TERMS_RAW);
        long orderId = seedInvoicedOrder();
        Assertions.assertNull(jdbcTemplate.queryForObject(
                        "SELECT source_quote_version_id FROM invoice WHERE invoice_id = ?", Long.class,
                        latestInvoiceId(orderId)),
                "precondition: v1 (D.1) is a Path B invoice");
        InvoiceTermsSanitizer sanitizer = new InvoiceTermsSanitizer();
        String softTerms = sanitizer.sanitize(PR2_LIVE_SOFT_TERMS_RAW);
        Assertions.assertNotNull(softTerms);
        Assertions.assertNotEquals(PR2_LIVE_SOFT_TERMS_RAW, softTerms,
                "fixture: the raw terms carry markup the sanitizer strips");

        // D.8 (201): the appended signed version reports LIVE + the sanitized SOFT terms.
        mockMvc.perform(signInvoice(orderId).session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(ACCEPT_EMAILED_MESSAGE))
                .andExpect(jsonPath("$.data.invoice.version_number").value(2))
                .andExpect(jsonPath("$.data.invoice.accepted_signature_present").value(true))
                .andExpect(jsonPath("$.data.invoice.terms_source").value("LIVE"))
                .andExpect(jsonPath("$.data.invoice.terms_html").value(softTerms));
        clearJpaCache();

        // D.8 carries the current row's V19 columns verbatim: the signed Path B version keeps both NULL...
        long signedInvoiceId = latestInvoiceId(orderId);
        Map<String, Object> v19 = jdbcTemplate.queryForMap(
                "SELECT source_quote_version_id, terms_snapshot FROM invoice WHERE invoice_id = ?", signedInvoiceId);
        Assertions.assertNull(v19.get("source_quote_version_id"), "D.8 v2: source_quote_version_id must be NULL");
        Assertions.assertNull(v19.get("terms_snapshot"), "D.8 v2: terms_snapshot must be NULL");
        // ...and its signed PDF (the stored file, which is also the auto-emailed attachment) renders those
        // live SOFT terms on a dedicated page 2, never the HARD ones.
        String signedPdfPath = storagePathOf(invoicePdfFileId(signedInvoiceId));
        Assertions.assertTrue(diskFileExists(signedPdfPath), "the signed PDF must exist on disk");
        byte[] signedPdf = Files.readAllBytes(tempStorageDir.resolve(signedPdfPath.substring(1)));
        List<InvoiceEmailRequest> sent = recordingInvoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "D.8 auto-emails once");
        Assertions.assertArrayEquals(signedPdf, sent.get(0).pdfBytes(), "the auto-email attaches the stored signed PDF");
        Assertions.assertEquals(2, pdfPageCount(signedPdf), "live terms -> a dedicated terms page 2");
        String page1 = pdfPageText(signedPdf, 1);
        String page2 = pdfPageText(signedPdf, 2);
        Assertions.assertFalse(page1.contains("TERMS"), () -> "page 1 never carries the terms: " + page1);
        Assertions.assertTrue(page2.contains("TERMS") && page2.contains(PR2_SOFT_TERMS_TEXT),
                () -> "page 2 must carry the live soft terms: " + page2);
        String signedText = extractPdfText(signedPdf);
        Assertions.assertFalse(signedText.contains(PR2_HARD_TERMS_TEXT), () -> "no hard terms on SOFT: " + signedText);
        Assertions.assertTrue(signedText.contains("Accepted by") && signedText.contains(D5C_CUSTOMER_NAME),
                () -> "the signed PDF carries the acceptance caption: " + signedText);

        // D.9 (200) after the business edits its SOFT terms: LIVE reports the CURRENT terms.
        setTenantTerms(PR2_UPDATED_SOFT_TERMS_RAW, PR2_LIVE_HARD_TERMS_RAW);
        mockMvc.perform(post(resendUrl(orderId)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESEND_MESSAGE))
                .andExpect(jsonPath("$.data.invoice.invoice_id").value(signedInvoiceId))
                .andExpect(jsonPath("$.data.invoice.terms_source").value("LIVE"))
                .andExpect(jsonPath("$.data.invoice.terms_html").value(sanitizer.sanitize(PR2_UPDATED_SOFT_TERMS_RAW)));
        clearJpaCache();

        // D.9 (200) with no SOFT terms: terms_html stays PRESENT as JSON null (no fallback to the HARD terms).
        setTenantTerms(null, PR2_LIVE_HARD_TERMS_RAW);
        MvcResult cleared = mockMvc.perform(post(resendUrl(orderId)).session(liamStore1Session())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice.terms_source").value("LIVE"))
                .andReturn();
        clearJpaCache();
        String json = cleared.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"terms_html\":null"), () -> "terms_html must be present as null: " + json);
        for (String internal : List.of("source_quote_version_id", "terms_snapshot")) {
            Assertions.assertFalse(json.contains(internal), () -> "must not leak " + internal + ": " + json);
        }

        // Neither resend appended a version; each one emailed the stored PDF.
        Assertions.assertEquals(2, countInvoices(orderId));
        Assertions.assertEquals(signedInvoiceId, latestInvoiceId(orderId));
        Assertions.assertEquals(3, recordingInvoiceEmailSender.sentEmails().size());
    }
}
