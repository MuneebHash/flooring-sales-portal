package com.flooring.salesportal.order;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Chunk 3 Branch 5 order attachment endpoints (D.14 GET list / D.15 POST
 * upload / D.16 DELETE / D.17 GET file).
 *
 * <p>Mirrors {@code OrderNoteControllerTest} / {@code OrderChargeLineControllerTest}:
 * {@code @SpringBootTest @Transactional}, MockMvc, a {@code MockHttpSession} carrying the seeded
 * Liam / business 1 / store 1 identity, and {@code JdbcTemplate} for data tweaks + persistence
 * assertions (rolled back with the test transaction). These are the first multipart tests in the
 * repo, so they use {@link MockMultipartFile} + {@code multipart(...)}.
 *
 * <p>Attachment files are written to a JUnit {@link TempDir} (via {@code app.storage.base-dir}
 * bound through {@link DynamicPropertySource}), so nothing is written into the repo and the dir is
 * cleaned up after the class. The {@code @Transactional} rollback only covers DB rows, not disk
 * files — the delete test asserts the on-disk file is removed by the endpoint itself.
 *
 * <p>Seed (V4): order 1 = SOFT/ACCEPTED, store 1, business 1, with ONE seeded attachment
 * (id {@link #SEEDED_ATTACHMENT_ID}: {@code site-photo-lounge.jpg}, image/jpeg, 2,048,576 bytes,
 * storage_path {@code /uploads/1/orders/1/site-photo-lounge.jpg} — note: NOT present on disk under
 * the test temp dir, so downloading it exercises the missing-file 500 path). Order 2 = HARD/LEAD,
 * store 1, no attachments; order 5 = store 2 (cross-store); order 9 = business 2 (cross-business).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderAttachmentControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final long ORDER_WITH_ATTACHMENT = 1L;   // store 1, business 1, ACCEPTED, 1 seeded photo
    private static final long ORDER_EMPTY = 2L;             // store 1, business 1, no attachments
    private static final long ORDER_OTHER_STORE = 5L;       // store 2, business 1 (cross-store)
    private static final long ORDER_OTHER_BUSINESS = 9L;    // business 2 (cross-business)
    private static final long ORDER_DOES_NOT_EXIST = 99_999L;

    private static final long SEEDED_ATTACHMENT_ID = 1L;
    private static final String SEEDED_FILE_NAME = "site-photo-lounge.jpg";
    private static final long SEEDED_FILE_SIZE = 2_048_576L;

    private static final long MAX_FILE_SIZE_BYTES = 10_485_760L;

    private static final byte[] JPEG_BYTES = "fake-jpeg-binary-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG_BYTES = "fake-png-binary-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WEBP_BYTES = "fake-webp-binary-content".getBytes(StandardCharsets.UTF_8);

    // JUnit-managed temp dir backing app.storage.base-dir; auto-deleted after the class.
    @TempDir
    static Path tempStorageDir;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        // Lazy supplier: evaluated at context refresh, after JUnit injects the static @TempDir field.
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

    private static String attachmentsUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/attachments";
    }

    private static String attachmentsUrl(String slug, Object orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/attachments";
    }

    private static String attachmentUrl(Object orderId, Object attachmentId) {
        return attachmentsUrl(orderId) + "/" + attachmentId;
    }

    private static String attachmentFileUrl(Object orderId, Object attachmentId) {
        return attachmentUrl(orderId, attachmentId) + "/file";
    }

    private static MockMultipartFile filePart(String name, String mime, byte[] bytes) {
        return new MockMultipartFile("file", name, mime, bytes);
    }

    private void laidOrder(long orderId) {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
    }

    private int countAttachments(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_attachment WHERE order_id = ?", Integer.class, orderId);
    }

    private int countStoredFile(long storedFileId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE stored_file_id = ?", Integer.class, storedFileId);
    }

    private Long latestAttachmentId(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_attachment_id FROM order_attachment WHERE order_id = ? ORDER BY order_attachment_id DESC LIMIT 1",
                Long.class, orderId);
    }

    private long storedFileIdOf(long attachmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM order_attachment WHERE order_attachment_id = ?", Long.class, attachmentId);
    }

    private String storagePathOf(long storedFileId) {
        return jdbcTemplate.queryForObject(
                "SELECT storage_path FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
    }

    private boolean diskFileExists(String storagePath) {
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        return Files.exists(tempStorageDir.resolve(relative));
    }

    /** Upload a valid jpeg to the order and return the created attachment id (queried from the DB). */
    private long uploadJpeg(long orderId, String fileName) throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(orderId))
                        .file(filePart(fileName, "image/jpeg", JPEG_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isCreated());
        return latestAttachmentId(orderId);
    }

    // ================================================================
    // D.14 GET /attachments — list
    // ================================================================

    @Test
    void listAttachments_noSession_returns401() throws Exception {
        mockMvc.perform(get(attachmentsUrl(ORDER_WITH_ATTACHMENT)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void listAttachments_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(get(attachmentsUrl(ORDER_WITH_ATTACHMENT))
                        .session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void listAttachments_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(get(attachmentsUrl("abc"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void listAttachments_crossBusinessSlug_returns404() throws Exception {
        mockMvc.perform(get(attachmentsUrl(SLUG_PREMIER, ORDER_WITH_ATTACHMENT))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void listAttachments_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(get(attachmentsUrl(ORDER_OTHER_STORE))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void listAttachments_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(get(attachmentsUrl(ORDER_OTHER_BUSINESS))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void listAttachments_seededOrder_returns200WithAttachmentAndPagination() throws Exception {
        mockMvc.perform(get(attachmentsUrl(ORDER_WITH_ATTACHMENT))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_attachment_id").value((int) SEEDED_ATTACHMENT_ID))
                .andExpect(jsonPath("$.data[0].attachment_kind").value("PHOTO"))
                .andExpect(jsonPath("$.data[0].file_name").value(SEEDED_FILE_NAME))
                .andExpect(jsonPath("$.data[0].mime_type").value("image/jpeg"))
                .andExpect(jsonPath("$.data[0].file_size").value((int) SEEDED_FILE_SIZE))
                .andExpect(jsonPath("$.data[0].download_path")
                        .value("/api/v1/" + SLUG_AUSSIE + "/orders/1/attachments/1/file"))
                .andExpect(jsonPath("$.data[0].created_at").exists())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.page_size").value(20))
                .andExpect(jsonPath("$.pagination.total_items").value(1))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));
    }

    @Test
    void listAttachments_doesNotExposeStoragePath() throws Exception {
        mockMvc.perform(get(attachmentsUrl(ORDER_WITH_ATTACHMENT))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].storage_path").doesNotExist())
                .andExpect(jsonPath("$.data[0].stored_file_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].order_id").doesNotExist());
    }

    @Test
    void listAttachments_emptyOrder_returnsEmptyDataArray() throws Exception {
        mockMvc.perform(get(attachmentsUrl(ORDER_EMPTY))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.pagination.total_items").value(0))
                .andExpect(jsonPath("$.pagination.total_pages").value(0));
    }

    @Test
    void listAttachments_invalidPage_returns400() throws Exception {
        mockMvc.perform(get(attachmentsUrl(ORDER_WITH_ATTACHMENT))
                        .session(liamStore1Session())
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void listAttachments_pageSizeTooHigh_returns400() throws Exception {
        mockMvc.perform(get(attachmentsUrl(ORDER_WITH_ATTACHMENT))
                        .session(liamStore1Session())
                        .param("page_size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void listAttachments_laidOrder_returns200() throws Exception {
        laidOrder(ORDER_WITH_ATTACHMENT);
        mockMvc.perform(get(attachmentsUrl(ORDER_WITH_ATTACHMENT))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_attachment_id").value((int) SEEDED_ATTACHMENT_ID));
    }

    // ================================================================
    // D.15 POST /attachments — upload
    // ================================================================

    @Test
    void uploadAttachment_jpeg_returns201_withDataAttachmentShapeAndMessage() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("lounge.jpg", "image/jpeg", JPEG_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Attachment uploaded."))
                .andExpect(jsonPath("$.data.attachment.order_attachment_id").isNumber())
                .andExpect(jsonPath("$.data.attachment.attachment_kind").value("PHOTO"))
                .andExpect(jsonPath("$.data.attachment.file_name").value("lounge.jpg"))
                .andExpect(jsonPath("$.data.attachment.mime_type").value("image/jpeg"))
                .andExpect(jsonPath("$.data.attachment.file_size").value(JPEG_BYTES.length))
                .andExpect(jsonPath("$.data.attachment.download_path",
                        startsWith("/api/v1/" + SLUG_AUSSIE + "/orders/2/attachments/")))
                .andExpect(jsonPath("$.data.attachment.download_path", endsWith("/file")))
                .andExpect(jsonPath("$.data.attachment.created_at").exists());
    }

    @Test
    void uploadAttachment_png_returns201() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("plan.png", "image/png", PNG_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attachment.mime_type").value("image/png"))
                .andExpect(jsonPath("$.data.attachment.attachment_kind").value("PHOTO"));
    }

    @Test
    void uploadAttachment_webp_returns201() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("room.webp", "image/webp", WEBP_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attachment.mime_type").value("image/webp"));
    }

    @Test
    void uploadAttachment_exactlyMaxSize_returns201() throws Exception {
        // == 10,485,760 bytes is allowed; only > is rejected.
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("max.jpg", "image/jpeg", new byte[(int) MAX_FILE_SIZE_BYTES]))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attachment.file_size").value(MAX_FILE_SIZE_BYTES));
    }

    @Test
    void uploadAttachment_persistsStoredFileAndOrderAttachment() throws Exception {
        long attachmentId = uploadJpeg(ORDER_EMPTY, "persisted.jpg");

        Assertions.assertEquals(1, countAttachments(ORDER_EMPTY));
        long storedFileId = storedFileIdOf(attachmentId);
        Assertions.assertEquals(1, countStoredFile(storedFileId));

        String fileName = jdbcTemplate.queryForObject(
                "SELECT file_name FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
        String mimeType = jdbcTemplate.queryForObject(
                "SELECT mime_type FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
        long fileSize = jdbcTemplate.queryForObject(
                "SELECT file_size FROM stored_file WHERE stored_file_id = ?", Long.class, storedFileId);
        String storagePath = storagePathOf(storedFileId);

        Assertions.assertEquals("persisted.jpg", fileName);
        Assertions.assertEquals("image/jpeg", mimeType);
        Assertions.assertEquals(JPEG_BYTES.length, fileSize);
        Assertions.assertTrue(storagePath.startsWith("/uploads/1/orders/2/"), () -> "storage_path=" + storagePath);
        Assertions.assertTrue(diskFileExists(storagePath), "uploaded file should exist on disk");
    }

    @Test
    void uploadAttachment_response_noStoragePath_noFinancialSummary() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("lounge.jpg", "image/jpeg", JPEG_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attachment.storage_path").doesNotExist())
                .andExpect(jsonPath("$.data.attachment.stored_file_id").doesNotExist())
                .andExpect(jsonPath("$.data.attachment.order_financial_summary").doesNotExist())
                .andExpect(jsonPath("$.data.order_financial_summary").doesNotExist());
    }

    @Test
    void uploadAttachment_doesNotChangeOrderFinancials() throws Exception {
        // Upload to the fully-priced order 1 and confirm its persisted financials are untouched.
        uploadJpeg(ORDER_WITH_ATTACHMENT, "extra.jpg");
        Assertions.assertEquals(0, new java.math.BigDecimal("840.00").compareTo(
                jdbcTemplate.queryForObject("SELECT sale_price_ex_gst FROM sales_order WHERE order_id = ?",
                        java.math.BigDecimal.class, ORDER_WITH_ATTACHMENT)));
        Assertions.assertEquals(0, new java.math.BigDecimal("408.00").compareTo(
                jdbcTemplate.queryForObject("SELECT gp FROM sales_order WHERE order_id = ?",
                        java.math.BigDecimal.class, ORDER_WITH_ATTACHMENT)));
    }

    @Test
    void uploadAttachment_missingFile_returns400() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("file"));
        Assertions.assertEquals(0, countAttachments(ORDER_EMPTY));
    }

    @Test
    void uploadAttachment_emptyFile_returns400() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("empty.jpg", "image/jpeg", new byte[0]))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("file"));
        Assertions.assertEquals(0, countAttachments(ORDER_EMPTY));
    }

    @Test
    void uploadAttachment_missingAttachmentKind_returns400() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("lounge.jpg", "image/jpeg", JPEG_BYTES))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_kind"));
        Assertions.assertEquals(0, countAttachments(ORDER_EMPTY));
    }

    @Test
    void uploadAttachment_signatureKind_returns400Validation() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("sig.jpg", "image/jpeg", JPEG_BYTES))
                        .param("attachment_kind", "SIGNATURE")
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_kind"));
        Assertions.assertEquals(0, countAttachments(ORDER_EMPTY));
    }

    @Test
    void uploadAttachment_unexpectedFormField_returns400Validation() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("lounge.jpg", "image/jpeg", JPEG_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .param("order_id", "5")
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
        Assertions.assertEquals(0, countAttachments(ORDER_EMPTY));
    }

    @Test
    void uploadAttachment_unsupportedMime_returns400UnsupportedFileType() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("doc.pdf", "application/pdf", JPEG_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));
        Assertions.assertEquals(0, countAttachments(ORDER_EMPTY));
    }

    @Test
    void uploadAttachment_overMaxSize_returns400FileTooLarge() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("big.jpg", "image/jpeg", new byte[(int) MAX_FILE_SIZE_BYTES + 1]))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        Assertions.assertEquals(0, countAttachments(ORDER_EMPTY));
    }

    @Test
    void uploadAttachment_laidOrder_returns201() throws Exception {
        laidOrder(ORDER_EMPTY);
        mockMvc.perform(multipart(attachmentsUrl(ORDER_EMPTY))
                        .file(filePart("laid.jpg", "image/jpeg", JPEG_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attachment.attachment_kind").value("PHOTO"));
        Assertions.assertEquals(1, countAttachments(ORDER_EMPTY));
    }

    @Test
    void uploadAttachment_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_OTHER_STORE))
                        .file(filePart("x.jpg", "image/jpeg", JPEG_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void uploadAttachment_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(multipart(attachmentsUrl(ORDER_OTHER_BUSINESS))
                        .file(filePart("x.jpg", "image/jpeg", JPEG_BYTES))
                        .param("attachment_kind", "PHOTO")
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // ================================================================
    // D.16 DELETE /attachments/{attachmentId}
    // ================================================================

    @Test
    void deleteAttachment_returns200_withDeletedIdAndMessage() throws Exception {
        long attachmentId = uploadJpeg(ORDER_EMPTY, "to-delete.jpg");

        mockMvc.perform(delete(attachmentUrl(ORDER_EMPTY, attachmentId))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Attachment removed."))
                .andExpect(jsonPath("$.data.deleted_attachment_id").value((int) attachmentId));
    }

    @Test
    void deleteAttachment_removesBothRowsAndDiskFile() throws Exception {
        long attachmentId = uploadJpeg(ORDER_EMPTY, "to-delete.jpg");
        long storedFileId = storedFileIdOf(attachmentId);
        String storagePath = storagePathOf(storedFileId);
        Assertions.assertTrue(diskFileExists(storagePath), "file should exist before delete");

        mockMvc.perform(delete(attachmentUrl(ORDER_EMPTY, attachmentId))
                        .session(liamStore1Session()))
                .andExpect(status().isOk());

        Assertions.assertEquals(0, countAttachments(ORDER_EMPTY), "order_attachment row removed");
        Assertions.assertEquals(0, countStoredFile(storedFileId), "stored_file row removed");
        Assertions.assertFalse(diskFileExists(storagePath), "disk file removed (best-effort)");
    }

    @Test
    void deleteAttachment_secondDelete_returns404AttachmentNotFound() throws Exception {
        long attachmentId = uploadJpeg(ORDER_EMPTY, "once.jpg");

        mockMvc.perform(delete(attachmentUrl(ORDER_EMPTY, attachmentId))
                        .session(liamStore1Session()))
                .andExpect(status().isOk());

        mockMvc.perform(delete(attachmentUrl(ORDER_EMPTY, attachmentId))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void deleteAttachment_attachmentOnDifferentOrder_returns404AttachmentNotFound() throws Exception {
        // Seeded attachment 1 belongs to order 1, not order 2 (both in scope; order 2 not LAID).
        mockMvc.perform(delete(attachmentUrl(ORDER_EMPTY, SEEDED_ATTACHMENT_ID))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_FOUND"));
        // The attachment is untouched on its real order.
        Assertions.assertEquals(1, countAttachments(ORDER_WITH_ATTACHMENT));
    }

    @Test
    void deleteAttachment_laidOrder_returns422OrderLocked() throws Exception {
        laidOrder(ORDER_WITH_ATTACHMENT);
        mockMvc.perform(delete(attachmentUrl(ORDER_WITH_ATTACHMENT, SEEDED_ATTACHMENT_ID))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
        // The attachment is NOT removed.
        Assertions.assertEquals(1, countAttachments(ORDER_WITH_ATTACHMENT));
    }

    @Test
    void deleteAttachment_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(delete(attachmentUrl("abc", 1))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void deleteAttachment_invalidAttachmentId_returns400() throws Exception {
        mockMvc.perform(delete(attachmentUrl(ORDER_WITH_ATTACHMENT, "abc"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_id"));
    }

    @Test
    void deleteAttachment_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(delete(attachmentUrl(ORDER_OTHER_STORE, 1))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void deleteAttachment_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(delete(attachmentUrl(ORDER_OTHER_BUSINESS, 1))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void deleteAttachment_noSession_returns401() throws Exception {
        mockMvc.perform(delete(attachmentUrl(ORDER_WITH_ATTACHMENT, SEEDED_ATTACHMENT_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ================================================================
    // D.17 GET /attachments/{attachmentId}/file
    // ================================================================

    @Test
    void downloadAttachment_uploaded_returns200RawBytesWithHeaders() throws Exception {
        long attachmentId = uploadJpeg(ORDER_EMPTY, "lounge.jpg");

        MvcResult result = mockMvc.perform(get(attachmentFileUrl(ORDER_EMPTY, attachmentId))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andReturn();

        Assertions.assertArrayEquals(JPEG_BYTES, result.getResponse().getContentAsByteArray());
        Assertions.assertEquals(JPEG_BYTES.length, result.getResponse().getContentLength(),
                "Content-Length matches stored file_size");

        // Content-Disposition is built via Spring's ContentDisposition (UTF-8 encoded). For an ASCII
        // name it still carries the filename inline; assert robustly (the exact token form — quoted
        // vs RFC 5987 filename* — is Spring's concern, not ours).
        String contentDisposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        Assertions.assertNotNull(contentDisposition, "Content-Disposition present");
        Assertions.assertTrue(contentDisposition.startsWith("inline"),
                () -> "starts with inline: " + contentDisposition);
        Assertions.assertTrue(contentDisposition.contains("lounge.jpg"),
                () -> "carries the filename: " + contentDisposition);
    }

    @Test
    void downloadAttachment_unicodeFilename_encodesContentDispositionSafely() throws Exception {
        // Codex fix: a non-ISO-8859-1 / Unicode filename must be RFC 5987-encoded, not placed raw in
        // the header (which is an ISO-8859-1 channel and would break / mangle the value).
        long attachmentId = uploadJpeg(ORDER_EMPTY, "測量.jpg");

        MvcResult result = mockMvc.perform(get(attachmentFileUrl(ORDER_EMPTY, attachmentId))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andReturn();

        // Download still succeeds and streams the exact bytes.
        Assertions.assertArrayEquals(JPEG_BYTES, result.getResponse().getContentAsByteArray());
        Assertions.assertEquals(JPEG_BYTES.length, result.getResponse().getContentLength());

        String contentDisposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        Assertions.assertNotNull(contentDisposition, "Content-Disposition present");
        Assertions.assertTrue(contentDisposition.startsWith("inline"),
                () -> "starts with inline: " + contentDisposition);
        // RFC 5987 form, with UTF-8 percent-encoding of 測量 = %E6%B8%AC%E9%87%8F (".jpg" left as-is).
        Assertions.assertTrue(contentDisposition.contains("filename*="),
                () -> "uses RFC 5987 filename*: " + contentDisposition);
        Assertions.assertTrue(contentDisposition.toUpperCase().contains("UTF-8"),
                () -> "declares UTF-8 charset: " + contentDisposition);
        Assertions.assertTrue(contentDisposition.contains("%E6%B8%AC%E9%87%8F"),
                () -> "percent-encodes the Unicode filename: " + contentDisposition);
        // The raw multi-byte characters must NOT appear unencoded — that is the bug being fixed.
        Assertions.assertFalse(contentDisposition.contains("測量"),
                () -> "raw Unicode must not appear in the header: " + contentDisposition);
        // The whole header value must be transmittable on the ISO-8859-1 HTTP header channel.
        Assertions.assertTrue(StandardCharsets.ISO_8859_1.newEncoder().canEncode(contentDisposition),
                () -> "header must be ISO-8859-1 encodable: " + contentDisposition);
    }

    @Test
    void downloadAttachment_laidOrder_returns200() throws Exception {
        long attachmentId = uploadJpeg(ORDER_EMPTY, "laid-dl.jpg");
        laidOrder(ORDER_EMPTY);

        mockMvc.perform(get(attachmentFileUrl(ORDER_EMPTY, attachmentId))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"));
    }

    @Test
    void downloadAttachment_missingOnDisk_returns500JsonError() throws Exception {
        // The seeded attachment's storage_path is not present under the test temp dir.
        mockMvc.perform(get(attachmentFileUrl(ORDER_WITH_ATTACHMENT, SEEDED_ATTACHMENT_ID))
                        .session(liamStore1Session()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void downloadAttachment_attachmentOnDifferentOrder_returns404AttachmentNotFound() throws Exception {
        // Seeded attachment 1 belongs to order 1, not order 2.
        mockMvc.perform(get(attachmentFileUrl(ORDER_EMPTY, SEEDED_ATTACHMENT_ID))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void downloadAttachment_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(get(attachmentFileUrl(ORDER_OTHER_STORE, 1))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void downloadAttachment_crossBusinessSlug_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/" + SLUG_PREMIER + "/orders/" + ORDER_WITH_ATTACHMENT
                        + "/attachments/" + SEEDED_ATTACHMENT_ID + "/file")
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void downloadAttachment_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(get(attachmentFileUrl("abc", 1))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void downloadAttachment_invalidAttachmentId_returns400() throws Exception {
        mockMvc.perform(get(attachmentFileUrl(ORDER_WITH_ATTACHMENT, "abc"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_id"));
    }

    @Test
    void downloadAttachment_noSession_returns401() throws Exception {
        mockMvc.perform(get(attachmentFileUrl(ORDER_WITH_ATTACHMENT, SEEDED_ATTACHMENT_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
