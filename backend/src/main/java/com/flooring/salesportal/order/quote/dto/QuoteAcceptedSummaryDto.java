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
 * <p>{@code invoiceEligible} is false when the order's CURRENT invoice is already accepted (16F
 * decision D5(b): conversion is unavailable — "Invoice already accepted"); an unsigned invoice, an
 * unsigned draft or a newer issued quote does not by itself make it false. It is a read-only
 * eligibility flag; the conversion itself (Path A) is PR2. Every field is always serialized (nullable
 * ones as JSON null), matching {@link QuoteIssuedSummaryDto}.
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
