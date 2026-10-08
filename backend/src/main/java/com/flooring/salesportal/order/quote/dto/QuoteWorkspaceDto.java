package com.flooring.salesportal.order.quote.dto;

/**
 * Full quote state for the Quote tab (openapi {@code QuoteWorkspace}): {@code { draft, current_issued,
 * accepted }}. All three keys are always present and may be null.
 *
 * <p>{@code currentIssued} is the active {@code ISSUED} version summary, or null when no active issued
 * quote exists (never sent yet, or the latest issued version was superseded / cancelled / expired /
 * accepted away). {@code accepted} (Phase 16F PR1) is the LATEST {@code ACCEPTED} version summary, or
 * null when none exists — selected independently of the draft and of {@code currentIssued}, so an
 * accepted quote stays visible beside a newer draft or a newer issued version.
 */
public record QuoteWorkspaceDto(
        QuoteDraftDto draft,
        QuoteIssuedSummaryDto currentIssued,
        QuoteAcceptedSummaryDto accepted
) {

    public static QuoteWorkspaceDto of(QuoteDraftDto draft,
                                       QuoteIssuedSummaryDto currentIssued,
                                       QuoteAcceptedSummaryDto accepted) {
        return new QuoteWorkspaceDto(draft, currentIssued, accepted);
    }
}
