package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Current/latest invoice detail (Phase 12 Chunk 4 E.2 {@code invoice_detail}, OpenAPI
 * {@code InvoiceDetail}). Returned inside {@link InvoiceResponse} by D.1 Create (and reused by the
 * later D.2 Rewrite / D.3 Read branches).
 *
 * <p>Only the thirteen contract columns are exposed. The internal {@code invoice.stored_file_id} and
 * {@code stored_file.storage_path} (server disk path) are NEVER returned — the PDF is reached only
 * via {@code pdf_download_path}, the relative URL of the current-invoice file endpoint, built
 * backend-side from the request slug + ids. Jackson's global snake_case strategy renders the field
 * names ({@code invoiceId} -> {@code invoice_id}); the date / timestamp formats match the existing
 * read DTOs. {@code due_date} is nullable in the schema (serialized as JSON {@code null}, never
 * omitted) but is always present on a freshly created invoice (precondition 7 guarantees
 * {@code proposed_lay_date}).
 */
public record InvoiceDetailDto(
        long invoiceId,
        long orderId,
        int versionNumber,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate invoiceDate,
        @JsonInclude(JsonInclude.Include.ALWAYS) @JsonFormat(pattern = "yyyy-MM-dd") LocalDate dueDate,
        String detailsOfSaleSnapshot,
        BigDecimal salePriceExGst,
        BigDecimal salePriceIncGst,
        BigDecimal totalPaid,
        BigDecimal balanceDue,
        long createdByUserId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        String pdfDownloadPath
) {
}
