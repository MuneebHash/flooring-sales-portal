package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.api.PaginationMeta;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.FileUploadException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.OrderAttachmentReadRepository.AttachmentRow;
import com.flooring.salesportal.order.OrderAttachmentWriteRepository.AttachmentInsert;
import com.flooring.salesportal.order.dto.AttachmentDeleteResponse;
import com.flooring.salesportal.order.dto.AttachmentReadDto;
import com.flooring.salesportal.order.dto.AttachmentUploadResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunk 3 Branch 5 — order attachments / photos (D.14 GET list, D.15 POST upload, D.16 DELETE,
 * D.17 GET file). All four are standard-protected and scope the order to the session's
 * {@code (business_id, store_id)} (missing / cross-store / cross-business → 404 {@code ORDER_NOT_FOUND},
 * no existence leak); the single-attachment endpoints additionally require the attachment to belong to
 * the order in the path (else 404 {@code ATTACHMENT_NOT_FOUND}).
 *
 * <p>LAID lock (conventions §16 / Chunk 3 F.8): list, upload, and download are allowed on a LAID
 * order; only DELETE is blocked → 422 {@code ORDER_LOCKED}, so {@code requireNotLaid} is called only
 * on the delete path. No attachment action recomputes or persists the {@code sales_order} financial
 * header (Chunk 3 F.5), so none of these responses carry an {@code order_financial_summary}.
 *
 * <p>Gate ordering mirrors the sibling order endpoints: standard-protected guard → manual id parse
 * (VALIDATION_FAILED) → (GET: validate pagination) → scoped order lookup (404 ORDER_NOT_FOUND) →
 * (DELETE: LAID gate) → multipart / attachment validation.
 */
@Service
public class OrderAttachmentService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE = 1;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private static final String STATUS_LAID = "LAID";

    // Chunk 3 D.15: only PHOTO is accepted on upload (SIGNATURE exists in the enum but is rejected).
    private static final String ATTACHMENT_KIND_PHOTO = "PHOTO";
    // Exactly 10 MB. size > this → FILE_TOO_LARGE; size == this is allowed.
    private static final long MAX_FILE_SIZE_BYTES = 10_485_760L;

    private static final String FILE_PART = "file";
    private static final String KIND_FIELD = "attachment_kind";

    // MIME allow-list and the safe on-disk extension derived from each (Chunk 3 D.15).
    private static final Map<String, String> EXTENSION_BY_MIME = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    private static final Set<String> ALLOWED_MIME_TYPES = EXTENSION_BY_MIME.keySet();

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderAttachmentReadRepository attachmentReadRepository;
    private final OrderAttachmentWriteRepository attachmentWriteRepository;
    private final FileStorageService fileStorageService;

    public OrderAttachmentService(RequestContextGuard requestContextGuard,
                                  SalesOrderRepository salesOrderRepository,
                                  OrderAttachmentReadRepository attachmentReadRepository,
                                  OrderAttachmentWriteRepository attachmentWriteRepository,
                                  FileStorageService fileStorageService) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.attachmentReadRepository = attachmentReadRepository;
        this.attachmentWriteRepository = attachmentWriteRepository;
        this.fileStorageService = fileStorageService;
    }

    // ------------------------------------------------------------------
    // D.14 GET /attachments — list (read-only; allowed on LAID; no financial summary)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ApiResponse<List<AttachmentReadDto>> listAttachments(String slug,
                                                                String orderIdRaw,
                                                                Integer page,
                                                                Integer pageSize,
                                                                HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        Paging paging = validatePaging(page, pageSize);
        requireOrderInScope(orderId, ctx);

        long totalItems = attachmentReadRepository.countByOrderId(orderId);
        int totalPages = totalPages(totalItems, paging.pageSize());

        List<AttachmentReadDto> rows = totalItems == 0
                ? List.of()
                : attachmentReadRepository.findByOrderId(orderId, paging.pageSize(), offset(paging)).stream()
                        .map(row -> toDto(slug, orderId, row))
                        .toList();

        return ApiResponse.page(rows, new PaginationMeta(paging.page(), paging.pageSize(), totalItems, totalPages));
    }

    // ------------------------------------------------------------------
    // D.15 POST /attachments — upload (multipart; allowed on LAID; no financial summary)
    // ------------------------------------------------------------------

    @Transactional
    public ApiResponse<AttachmentUploadResponse> uploadAttachment(String slug,
                                                                  String orderIdRaw,
                                                                  MultipartHttpServletRequest request) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, request);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        // Scope before validating the upload (gate-first). FOR UPDATE mirrors the write endpoints.
        // No LAID gate: attachments may be uploaded on a LAID order (conventions §16 / Chunk 3 F.8).
        requireOrderInScopeForUpdate(orderId, ctx);

        MultipartFile file = request.getFile(FILE_PART);
        String attachmentKind = request.getParameter(KIND_FIELD);

        // 1) Structural validation → a single 400 VALIDATION_FAILED (may carry several details).
        List<ErrorDetail> errors = new ArrayList<>();
        rejectUnexpectedParts(request, errors);
        validateAttachmentKind(attachmentKind, errors);
        validateFilePresent(file, errors);
        throwIfErrors(errors);

        // 2) MIME allow-list → 400 UNSUPPORTED_FILE_TYPE.
        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new FileUploadException(
                    ErrorCode.UNSUPPORTED_FILE_TYPE, ErrorCode.UNSUPPORTED_FILE_TYPE.defaultMessage());
        }

        // 3) Size cap → 400 FILE_TOO_LARGE (== 10 MB is allowed; > is rejected).
        long fileSize = file.getSize();
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw new FileUploadException(
                    ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.defaultMessage());
        }

        String safeFileName = safeDisplayName(file.getOriginalFilename(), EXTENSION_BY_MIME.get(mimeType));
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }

        // Write the file FIRST. A disk-write failure throws before any DB row is inserted (the
        // transaction has written nothing yet). If the DB inserts then fail, best-effort delete the
        // orphan file and rethrow so no orphan DB row OR orphan file remains (Chunk 3 D.15 / §10).
        String storagePath = fileStorageService.store(bytes, ctx.businessId(), orderId, EXTENSION_BY_MIME.get(mimeType));
        try {
            long storedFileId = attachmentWriteRepository.insertStoredFile(safeFileName, storagePath, mimeType, fileSize);
            AttachmentInsert inserted =
                    attachmentWriteRepository.insertAttachment(orderId, storedFileId, ATTACHMENT_KIND_PHOTO);

            AttachmentReadDto dto = new AttachmentReadDto(
                    inserted.orderAttachmentId(),
                    ATTACHMENT_KIND_PHOTO,
                    safeFileName,
                    mimeType,
                    fileSize,
                    buildDownloadPath(slug, orderId, inserted.orderAttachmentId()),
                    inserted.createdAt());
            return ApiResponse.ok(new AttachmentUploadResponse(dto), "Attachment uploaded.");
        } catch (RuntimeException ex) {
            fileStorageService.deleteQuietly(storagePath);
            throw ex;
        }
    }

    // ------------------------------------------------------------------
    // D.16 DELETE /attachments/{attachmentId} — blocked when LAID; no financial summary
    // ------------------------------------------------------------------

    @Transactional
    public ApiResponse<AttachmentDeleteResponse> deleteAttachment(String slug,
                                                                  String orderIdRaw,
                                                                  String attachmentIdRaw,
                                                                  HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        long attachmentId = parsePositiveLong(attachmentIdRaw, "attachment_id");

        SalesOrder order = loadOrderInScopeForUpdate(orderId, ctx);
        // LAID gate runs immediately after order scope (mirrors charge/product line delete), BEFORE
        // the attachment lookup: deletion is destructive and locked on a LAID order (Chunk 3 D.16).
        requireNotLaid(order);

        AttachmentRow row = attachmentReadRepository.findByAttachmentIdAndOrderId(attachmentId, orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ATTACHMENT_NOT_FOUND, "Attachment not found."));

        // order_attachment first (FK references stored_file), then the 1-to-1 stored_file row, then the
        // disk file (best-effort — must not roll back the committed DB deletes). No financial change.
        attachmentWriteRepository.deleteOrderAttachment(attachmentId, orderId);
        attachmentWriteRepository.deleteStoredFile(row.storedFileId());
        fileStorageService.deleteQuietly(row.storagePath());

        return ApiResponse.ok(new AttachmentDeleteResponse(attachmentId), "Attachment removed.");
    }

    // ------------------------------------------------------------------
    // D.17 GET /attachments/{attachmentId}/file — binary stream (allowed on LAID)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AttachmentDownload downloadAttachment(String slug,
                                                 String orderIdRaw,
                                                 String attachmentIdRaw,
                                                 HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parsePositiveLong(orderIdRaw, "order_id");
        long attachmentId = parsePositiveLong(attachmentIdRaw, "attachment_id");
        requireOrderInScope(orderId, ctx);

        AttachmentRow row = attachmentReadRepository.findByAttachmentIdAndOrderId(attachmentId, orderId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ATTACHMENT_NOT_FOUND, "Attachment not found."));

        // Missing-on-disk → UncheckedIOException → generic 500 with the standard JSON wrapper (D.17).
        byte[] bytes = fileStorageService.read(row.storagePath());
        return new AttachmentDownload(bytes, row.mimeType(), row.fileName(), row.fileSize());
    }

    // ------------------------------------------------------------------
    // Scoping
    // ------------------------------------------------------------------

    /** Read scope (no lock — GET). 404 ORDER_NOT_FOUND when out of the session's (business, store). */
    private void requireOrderInScope(long orderId, RequestContext ctx) {
        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
    }

    /** Write scope (FOR UPDATE — upload). 404 ORDER_NOT_FOUND when out of scope. */
    private void requireOrderInScopeForUpdate(long orderId, RequestContext ctx) {
        loadOrderInScopeForUpdate(orderId, ctx);
    }

    /** Write scope (FOR UPDATE) returning the order so the delete path can check the LAID lock. */
    private SalesOrder loadOrderInScopeForUpdate(long orderId, RequestContext ctx) {
        return salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
    }

    // Duplicated per service by the locked Branch 2/3 decision (not extracted). Only DELETE uses it.
    private void requireNotLaid(SalesOrder order) {
        if (STATUS_LAID.equals(order.getOrderStatus())) {
            throw new BusinessRuleException(ErrorCode.ORDER_LOCKED, ErrorCode.ORDER_LOCKED.defaultMessage());
        }
    }

    // ------------------------------------------------------------------
    // Pagination
    // ------------------------------------------------------------------

    private static Paging validatePaging(Integer pageRaw, Integer pageSizeRaw) {
        int page = pageRaw == null ? DEFAULT_PAGE : pageRaw;
        int pageSize = pageSizeRaw == null ? DEFAULT_PAGE_SIZE : pageSizeRaw;

        List<ErrorDetail> errors = new ArrayList<>();
        if (page < MIN_PAGE) {
            errors.add(new ErrorDetail(null, "page", "Must be greater than or equal to 1."));
        }
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            errors.add(new ErrorDetail(null, "page_size", "Must be between 1 and 100."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
        return new Paging(page, pageSize);
    }

    private static int totalPages(long totalItems, int pageSize) {
        return totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
    }

    private static long offset(Paging paging) {
        return ((long) (paging.page() - 1)) * (long) paging.pageSize();
    }

    // ------------------------------------------------------------------
    // Parsing / validation helpers
    // ------------------------------------------------------------------

    private static long parsePositiveLong(String raw, String field) {
        if (raw != null && !raw.isBlank()) {
            try {
                long parsed = Long.parseLong(raw);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to a single VALIDATION_FAILED below
            }
        }
        throw new ValidationException(
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new ErrorDetail(null, field, "Must be a positive integer.")));
    }

    /**
     * Reject any multipart part / form field other than {@code file} and {@code attachment_kind}
     * ("where practical" — Chunk 3 D.15). This also bars any backend-controlled field (business_id,
     * order_id, storage_path, stored_file_id, created_at, …) sent as a form field, since none is in
     * the allowed set.
     */
    private static void rejectUnexpectedParts(MultipartHttpServletRequest request, List<ErrorDetail> errors) {
        for (Iterator<String> fileNames = request.getFileNames(); fileNames.hasNext();) {
            String name = fileNames.next();
            if (!FILE_PART.equals(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
        for (Enumeration<String> paramNames = request.getParameterNames(); paramNames.hasMoreElements();) {
            String name = paramNames.nextElement();
            if (!KIND_FIELD.equals(name)) {
                errors.add(new ErrorDetail(null, name, "Not allowed."));
            }
        }
    }

    private static void validateAttachmentKind(String attachmentKind, List<ErrorDetail> errors) {
        if (attachmentKind == null || attachmentKind.isBlank()) {
            errors.add(new ErrorDetail(null, KIND_FIELD, "Required."));
        } else if (!ATTACHMENT_KIND_PHOTO.equals(attachmentKind)) {
            // Includes SIGNATURE — only PHOTO is accepted in Chunk 3.
            errors.add(new ErrorDetail(null, KIND_FIELD, "Must be PHOTO."));
        }
    }

    private static void validateFilePresent(MultipartFile file, List<ErrorDetail> errors) {
        if (file == null) {
            errors.add(new ErrorDetail(null, FILE_PART, "Required."));
        } else if (file.isEmpty()) {
            errors.add(new ErrorDetail(null, FILE_PART, "Must not be empty."));
        }
    }

    private static void throwIfErrors(List<ErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
    }

    /**
     * A safe value for {@code stored_file.file_name} and the {@code Content-Disposition} filename: the
     * client filename is stripped of any directory component and of control / quote / separator
     * characters (never trusted for the disk path), falling back to {@code attachment.<ext>} when
     * blank. Capped at the {@code stored_file.file_name} column width (255).
     */
    private static String safeDisplayName(String originalFilename, String extension) {
        String candidate = originalFilename == null ? "" : originalFilename;
        // Drop any path component (both separators) a client may have included.
        int lastSlash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            candidate = candidate.substring(lastSlash + 1);
        }
        // Remove control chars, quotes, and CR/LF (header-injection safe).
        candidate = candidate.replaceAll("[\\p{Cntrl}\"\\r\\n]", "").trim();
        if (candidate.isEmpty()) {
            candidate = "attachment." + extension;
        }
        if (candidate.length() > 255) {
            candidate = candidate.substring(0, 255);
        }
        return candidate;
    }

    private AttachmentReadDto toDto(String slug, long orderId, AttachmentRow row) {
        return new AttachmentReadDto(
                row.orderAttachmentId(),
                row.attachmentKind(),
                row.fileName(),
                row.mimeType(),
                row.fileSize(),
                buildDownloadPath(slug, orderId, row.orderAttachmentId()),
                row.createdAt());
    }

    private static String buildDownloadPath(String slug, long orderId, long attachmentId) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/attachments/" + attachmentId + "/file";
    }

    private record Paging(int page, int pageSize) {
    }

    /** Carrier for the D.17 binary response — the controller turns this into a raw {@code ResponseEntity}. */
    public record AttachmentDownload(byte[] bytes, String mimeType, String fileName, long fileSize) {
    }
}
