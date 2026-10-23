package com.flooring.salesportal.order.quote.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionLineRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionRow;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The active issued quote version summary (openapi {@code QuoteIssuedSummary}) — the
 * {@code current_issued} member of the quote workspace and the {@code data} of the send/cancel
 * responses (Phase 16E-A; body snapshot fields added in 16E-B).
 *
 * <p>Every field is always serialized (openapi marks them all required; the nullable ones
 * serialize as JSON null — no NON_NULL). {@code tokenExpiresAt} is the ACTIVE token's expiry ONLY
 * — no token value, no token hash, no stored_file id, no storage path, no cost/GP field ever rides
 * on this DTO (contract §7.1: "active-token expires_at, channel, <b>no token</b>").
 *
 * <p>Phase 16E-B adds the FROZEN issued body snapshot so the Customer Quote tab renders the sent
 * quote from {@code current_issued} only (never from live draft/order state):
 * {@code quoteTotalExGst}, {@code detailsOfSale} (the version's {@code details_of_sale_snapshot},
 * nullable) and {@code lines} — the {@code quote_version_line} snapshot rows in
 * {@code (sort_order, PK)} order. {@code lines} is ALWAYS an empty list for a non-itemised issued
 * quote (a non-itemised issue snapshots zero rows — the §6.1 dormant-row guard).
 *
 * <p>Phase 16G PR 6 (#124): the five timestamps are formatted to whole seconds
 * ({@code yyyy-MM-dd'T'HH:mm:ss}, the {@code InvoiceDetailDto} precedent); the stored values keep
 * their full precision. {@code lastEmailDelivered} is the delivery outcome of the latest email
 * attempt, derived in {@link #from} from the stored markers at full precision, before any
 * formatting (see {@link #lastEmailDelivered(QuoteVersionRow)}). It is never null, no column backs
 * it, and no public payload carries it.
 */
public record QuoteIssuedSummaryDto(
        long quoteVersionId,
        int versionNumber,
        String status,
        boolean itemised,
        BigDecimal quoteTotalExGst,
        BigDecimal quoteTotalIncGst,
        String flooringType,
        String sentChannel,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime firstSentAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastSentAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastEmailedAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime viewedAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime tokenExpiresAt,
        String detailsOfSale,
        List<Line> lines,
        boolean lastEmailDelivered
) {

    /**
     * Build from a version row + its ACTIVE token expiry (null when the link is dead/absent) +
     * the version's snapshot lines (already in {@code (sort_order, PK)} order from
     * {@code QuoteVersionRepository.findVersionLines}; empty for a non-itemised issue).
     */
    public static QuoteIssuedSummaryDto from(QuoteVersionRow row, LocalDateTime tokenExpiresAt,
                                             List<QuoteVersionLineRow> lines) {
        return new QuoteIssuedSummaryDto(
                row.quoteVersionId(),
                row.versionNumber(),
                row.status(),
                row.itemised(),
                row.quoteTotalExGst(),
                row.quoteTotalIncGst(),
                row.flooringTypeSnapshot(),
                row.sentChannel(),
                row.firstSentAt(),
                row.lastSentAt(),
                row.lastEmailedAt(),
                row.viewedAt(),
                tokenExpiresAt,
                row.detailsOfSaleSnapshot(),
                lines.stream().map(Line::from).toList(),
                lastEmailDelivered(row));
    }

    /**
     * 16G contract §13.5: true when {@code last_emailed_at} (the success marker, stamped only while
     * its own attempt is still the latest) is non-null and not before {@code last_sent_at} (the latest
     * attempt, stamped before delivery), compared on the stored values at full precision — never on
     * the whole-second strings, which can be identical for a delivered history and an undelivered one.
     * A null {@code last_sent_at} beside a non-null {@code last_emailed_at} counts as delivered (no later
     * attempt exists to have failed). The value is independent of the channel: the Customer Quote tab
     * applies it to the email channel only.
     */
    private static boolean lastEmailDelivered(QuoteVersionRow row) {
        LocalDateTime lastEmailedAt = row.lastEmailedAt();
        LocalDateTime lastSentAt = row.lastSentAt();
        return lastEmailedAt != null
                && (lastSentAt == null || !lastEmailedAt.isBefore(lastSentAt));
    }

    /**
     * One frozen issued snapshot line (openapi {@code QuoteIssuedLine}) — the customer-facing
     * {@code quote_version_line} fields only. {@code quantity} / {@code unitPriceExGst} are null
     * for ADJUSTMENT lines. No cost, no ids, no snapshot-internal fields.
     */
    public record Line(
            String lineType,
            String description,
            BigDecimal quantity,
            BigDecimal unitPriceExGst,
            BigDecimal lineTotalExGst,
            int sortOrder
    ) {

        static Line from(QuoteVersionLineRow row) {
            return new Line(
                    row.lineType(),
                    row.description(),
                    row.quantity(),
                    row.unitPriceExGst(),
                    row.lineTotalExGst(),
                    row.sortOrder());
        }
    }
}
