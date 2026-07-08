package com.flooring.salesportal.order.quote.dto;

import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionRow;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The active issued quote version summary (openapi {@code QuoteIssuedSummary}) — the
 * {@code current_issued} member of the quote workspace and the {@code data} of the send/cancel
 * responses (Phase 16E-A).
 *
 * <p>Every field is always serialized (openapi marks them all required; the nullable ones
 * serialize as JSON null — no NON_NULL). {@code tokenExpiresAt} is the ACTIVE token's expiry ONLY
 * — no token value, no token hash, no stored_file id, no storage path, no cost/GP field ever rides
 * on this DTO (contract §7.1: "active-token expires_at, channel, <b>no token</b>").
 */
public record QuoteIssuedSummaryDto(
        long quoteVersionId,
        int versionNumber,
        String status,
        boolean itemised,
        BigDecimal quoteTotalIncGst,
        String flooringType,
        String sentChannel,
        LocalDateTime firstSentAt,
        LocalDateTime lastSentAt,
        LocalDateTime lastEmailedAt,
        LocalDateTime viewedAt,
        LocalDateTime tokenExpiresAt
) {

    /** Build from a version row + its ACTIVE token expiry (null when the link is dead/absent). */
    public static QuoteIssuedSummaryDto from(QuoteVersionRow row, LocalDateTime tokenExpiresAt) {
        return new QuoteIssuedSummaryDto(
                row.quoteVersionId(),
                row.versionNumber(),
                row.status(),
                row.itemised(),
                row.quoteTotalIncGst(),
                row.flooringTypeSnapshot(),
                row.sentChannel(),
                row.firstSentAt(),
                row.lastSentAt(),
                row.lastEmailedAt(),
                row.viewedAt(),
                tokenExpiresAt);
    }
}
