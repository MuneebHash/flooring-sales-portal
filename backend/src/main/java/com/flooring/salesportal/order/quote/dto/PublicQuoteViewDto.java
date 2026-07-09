package com.flooring.salesportal.order.quote.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The public, token-only quote view (openapi {@code PublicQuoteView}) — the {@code data} of
 * {@code GET /api/v1/public/quotes/{token}} and of the viewed POST (Phase 16E-C; contract §7.2).
 *
 * <p><b>Cost-free / ID-free by construction.</b> This record carries NO cost, NO GP, NO token
 * value/hash, NO storage path, NO stored_file id and NO internal row id (there is no
 * quote_version_id / order_id / business_id field to leak). {@code orderNumber} is the
 * business-facing document number already printed on the issued PDF and quoted in the delivery
 * message — it is presentation, not an internal id. Every field is always serialized (the
 * nullable ones as JSON null — no NON_NULL), matching {@link QuoteIssuedSummaryDto}.
 *
 * <p><b>Two shapes, one record.</b> {@code state = ACTIVE} carries the full document payload —
 * frozen issued snapshot body (totals / lines / terms / details-of-sale / flooring type) plus the
 * live presentation context the issued PDF also reads live (business name/logo/accent/ABN,
 * direct-deposit payment fields + the Payments-tab Stripe link, customer name + billing lines) —
 * and a null {@code message}. Any dead state ({@code EXPIRED} /
 * {@code SUPERSEDED} / {@code CANCELLED} / {@code INACTIVE}) is the MINIMAL payload: state,
 * business name and the customer-facing {@code message}; every document field is null (contract
 * §7.2 "payload minimal" — a dead link renders no quote content).
 *
 * <p>{@code lines} is the issued snapshot in {@code (sort_order, PK)} order — ALWAYS an empty
 * list for an ACTIVE non-itemised quote (a non-itemised issue snapshots zero rows; dormant
 * retained draft rows can never appear here — §6.1) and null on a dead state. {@code gstAmount} /
 * {@code depositAmount} are server-computed with the exact issued-PDF math (inc − ex; 40% of inc,
 * HALF_UP 2dp) so the web document and the stored PDF can never disagree by a rounding cent.
 */
public record PublicQuoteViewDto(
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
        String customerName,
        String customerAddressLine1,
        String customerAddressLine2,
        String detailsOfSale,
        Boolean itemised,
        List<Line> lines,
        BigDecimal quoteTotalExGst,
        BigDecimal gstAmount,
        BigDecimal quoteTotalIncGst,
        BigDecimal depositAmount,
        String termsHtml,
        LocalDateTime expiresAt,
        String message
) {

    /**
     * One public quote line (openapi {@code PublicQuoteLine}) — description, quantity, unit and
     * signed amount ONLY. Deliberately narrower than the protected {@code QuoteIssuedLine}: no
     * {@code line_type} and no {@code sort_order} ride on the public surface (order is positional;
     * an ADJUSTMENT row is simply a null-quantity/null-unit signed amount).
     */
    public record Line(
            String description,
            BigDecimal quantity,
            BigDecimal unitPriceExGst,
            BigDecimal lineTotalExGst
    ) {
    }
}
