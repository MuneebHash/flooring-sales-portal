package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The public, token-only invoice view (Phase 16G PR 4; 16G contract §9.3; OpenAPI
 * {@code PublicInvoiceView}) - the {@code data} of {@code GET /api/v1/public/invoices/{token}}. Its own
 * record: it reuses no protected invoice / order DTO and not the public quote view.
 *
 * <p><b>Exactly these 27 keys, always present</b> (null values serialized, never omitted). {@code ACTIVE}
 * carries the order's CURRENT invoice version: its stored words (Invoice To, details of sale, terms),
 * dates and money, verbatim (nulls and whitespace too; {@code gst_amount} = inc − ex of the version),
 * the order number and flooring type, the presented link's own {@code expires_at}, and the business
 * presentation read live (name, logo path verbatim, accent, ABN, direct-deposit details, HTTPS-only
 * Stripe link); {@code message} is null. A dead state ({@code REPLACED} / {@code REWRITTEN} /
 * {@code SIGNED} / {@code EXPIRED}) carries only {@code state}, {@code business_name} and its
 * {@code message}; every other key is null.
 *
 * <p><b>Allowlist by construction.</b> There is no field for any id (invoice, order, token, quote
 * version, source quote version, stored file), storage path or file name, the token or its hash, the
 * customer email / mobile / phone, any user, staff, store-contact or salesperson value, a delivery
 * marker, any {@code accepted_*} field, {@code terms_source}, the protected
 * {@code invoice_link_expires_at}, cost / GP, or any line or deposit value.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicInvoiceViewDto(
        String state,
        String businessName,
        String businessLogoUrl,
        String accentColor,
        String businessAbn,
        String paymentAccountName,
        String paymentBankName,
        String paymentBsb,
        String paymentAccountNumber,
        String paymentStripeLinkUrl,
        String orderNumber,
        String flooringType,
        Integer versionNumber,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate invoiceDate,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate dueDate,
        String customerName,
        String customerAddressLine1,
        String customerAddressLine2,
        String detailsOfSale,
        BigDecimal salePriceExGst,
        BigDecimal gstAmount,
        BigDecimal salePriceIncGst,
        BigDecimal totalPaid,
        BigDecimal balanceDue,
        String termsHtml,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime expiresAt,
        String message
) {

    /**
     * The minimal dead-state payload: {@code state}, {@code business_name} and the per-state
     * {@code message}; every document field null.
     */
    public static PublicInvoiceViewDto dead(String state, String businessName, String message) {
        return new PublicInvoiceViewDto(
                state, businessName,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null,
                message);
    }
}
