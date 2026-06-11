package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Current invoice summary (Phase 12 Chunk 4 E.1 {@code current_invoice_summary}, OpenAPI
 * {@code CurrentInvoiceSummary}). Returned inside the D.7 payment response as {@code current_invoice}.
 *
 * <p>Unlike {@link InvoiceDetailDto} (E.2), this summary omits {@code order_id},
 * {@code details_of_sale_snapshot}, and {@code sale_price_ex_gst} — only the ten Phase 12 contract
 * fields plus the five Phase 13 acceptance/email fields are exposed. The internal
 * {@code invoice.stored_file_id}, {@code invoice.accepted_signature_file_id}, and
 * {@code stored_file.storage_path} are NEVER returned; the PDF is reached only via
 * {@code pdf_download_path} (the relative URL of the current-invoice file endpoint, D.4) and the
 * signature only via {@code accepted_signature_download_path}. {@code due_date} is nullable in the
 * schema (serialized as JSON {@code null}, never omitted) but is always present on a payment-driven
 * version (carried forward from the latest official invoice).
 *
 * <p>The five Phase 13 fields are ALWAYS present in the JSON; null values serialize as {@code null}.
 * On this branch every payment-created version is unsigned/unaccepted, so they return
 * {@code accepted_at: null}, {@code accepted_customer_name: null},
 * {@code accepted_signature_present: false}, {@code accepted_signature_download_path: null},
 * {@code last_emailed_at: null} (acceptance carry-forward is a later Phase 13 branch).
 */
public record CurrentInvoiceSummaryDto(
        long invoiceId,
        int versionNumber,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate invoiceDate,
        @JsonInclude(JsonInclude.Include.ALWAYS) @JsonFormat(pattern = "yyyy-MM-dd") LocalDate dueDate,
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
