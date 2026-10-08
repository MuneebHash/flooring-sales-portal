package com.flooring.salesportal.order.quote.dto;

import com.flooring.salesportal.order.quote.QuoteVersionRepository.AcceptedQuoteVersionRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionLineRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionRow;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The LATEST accepted quote version (openapi {@code QuoteAcceptedSummary}) — the {@code accepted}
 * member of the protected quote workspace (Phase 16F PR1). Null on the workspace when the order has no
 * {@code ACCEPTED} version; selected independently of the draft and of any newer issued version.
 *
 * <p><b>Frozen content only.</b> Every body field comes from the immutable accepted
 * {@code quote_version} / {@code quote_version_line} rows — never the live draft or order:
 * {@code quoteTotalExGst} / {@code quoteTotalIncGst} (the inc total is the legal billing number),
 * {@code itemised}, {@code flooringType}, {@code detailsOfSale} (nullable) and {@code lines}
 * ({@code (sort_order, PK)} order; ALWAYS an empty list for a non-itemised version).
 * {@code acceptedCustomerName} is the frozen name taken from the issue-time V17 snapshot.
 *
 * <p><b>No internal references.</b> {@code acceptedSignaturePresent} / {@code signedPdfAvailable} are
 * DERIVED from the server-internal stored_file ids, which are never serialized; the signature is read
 * through the backend-built protected {@code signatureDownloadPath} (null when no signature is stored;
 * clients consume it verbatim) and the signed PDF through {@code GET .../quote/pdf?type=accepted}.
 * No storage path, stored_file id, token, hash, cost or GP field ever rides on this DTO.
 *
 * <p>{@code invoiceEligible} (Phase 16F PR2, decision D5(b) as amended on 8 October 2026) is the
 * signature-precedence rule the create-invoice endpoint (Path A) enforces, shared through
 * {@code AcceptedQuoteInvoiceEligibility}: true when the order has no invoice, when its CURRENT invoice
 * is unsigned, or when this quote's {@code accepted_at} is STRICTLY later than the current invoice's
 * {@code accepted_at}; false when the current invoice was signed at the same time or later. It reflects
 * that rule only: LAID, a draft or newer issued quote, missing invoice preconditions, a blank details
 * of sale, a zero total or overpayment never make it false (the endpoint validates those itself).
 * Every field is always serialized (nullable ones as JSON null), matching {@link QuoteIssuedSummaryDto}.
 */
public record QuoteAcceptedSummaryDto(
        long quoteVersionId,
        int versionNumber,
        BigDecimal quoteTotalExGst,
        BigDecimal quoteTotalIncGst,
        boolean itemised,
        String flooringType,
        String detailsOfSale,
        List<QuoteIssuedSummaryDto.Line> lines,
        LocalDateTime acceptedAt,
        String acceptedCustomerName,
        boolean acceptedSignaturePresent,
        String signatureDownloadPath,
        boolean signedPdfAvailable,
        boolean invoiceEligible
) {

    /**
     * Build from the latest accepted row + its frozen snapshot lines (already in
     * {@code (sort_order, PK)} order from {@code QuoteVersionRepository.findVersionLines}) + the
     * backend-built protected signature path for this order + the invoice-eligibility flag. The lines
     * are re-gated on the frozen itemised flag (structurally empty for a non-itemised version).
     */
    public static QuoteAcceptedSummaryDto from(AcceptedQuoteVersionRow accepted,
                                               List<QuoteVersionLineRow> lines,
                                               String signatureDownloadPath,
                                               boolean invoiceEligible) {
        QuoteVersionRow version = accepted.version();
        boolean signaturePresent = accepted.acceptedSignatureFileId() != null;
        List<QuoteIssuedSummaryDto.Line> lineDtos = version.itemised()
                ? lines.stream().map(QuoteIssuedSummaryDto.Line::from).toList()
                : List.of();
        return new QuoteAcceptedSummaryDto(
                version.quoteVersionId(),
                version.versionNumber(),
                version.quoteTotalExGst(),
                version.quoteTotalIncGst(),
                version.itemised(),
                version.flooringTypeSnapshot(),
                version.detailsOfSaleSnapshot(),
                lineDtos,
                accepted.acceptedAt(),
                accepted.acceptedCustomerName(),
                signaturePresent,
                signaturePresent ? signatureDownloadPath : null,
                accepted.signedPdfFileId() != null,
                invoiceEligible);
    }
}
