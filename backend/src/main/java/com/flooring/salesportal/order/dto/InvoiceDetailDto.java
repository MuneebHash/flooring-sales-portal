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
 * <p>Only the thirteen Phase 12 contract columns plus the five Phase 13 acceptance/email fields are
 * exposed. The internal {@code invoice.stored_file_id}, {@code invoice.accepted_signature_file_id},
 * and {@code stored_file.storage_path} (server disk path) are NEVER returned — the PDF is reached only
 * via {@code pdf_download_path} and the signature only via {@code accepted_signature_download_path},
 * relative URLs built backend-side from the request slug + ids. Jackson's global snake_case strategy
 * renders the field names ({@code invoiceId} -> {@code invoice_id}); the date / timestamp formats match
 * the existing read DTOs. {@code due_date} is nullable in the schema (serialized as JSON {@code null},
 * never omitted) but is always present on a freshly created invoice (precondition 7 guarantees
 * {@code proposed_lay_date}).
 *
 * <p>The five Phase 13 fields are ALWAYS present in the JSON; null values serialize as {@code null}.
 * An unsigned/unaccepted invoice (every Create / Rewrite / payment version on this branch) returns
 * {@code accepted_at: null}, {@code accepted_customer_name: null},
 * {@code accepted_signature_present: false}, {@code accepted_signature_download_path: null},
 * {@code last_emailed_at: null}.
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
        String pdfDownloadPath,
        @JsonInclude(JsonInclude.Include.ALWAYS) @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime acceptedAt,
        @JsonInclude(JsonInclude.Include.ALWAYS) String acceptedCustomerName,
        boolean acceptedSignaturePresent,
        @JsonInclude(JsonInclude.Include.ALWAYS) String acceptedSignatureDownloadPath,
        @JsonInclude(JsonInclude.Include.ALWAYS) @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastEmailedAt
) {
}
