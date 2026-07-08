package com.flooring.salesportal.order.quote.dto;

/**
 * Full quote state for the Quote tab (openapi {@code QuoteWorkspace}): {@code { draft, current_issued,
 * accepted }}. All three keys are always present and may be null.
 *
 * <p>Phase 16E-A populates {@code currentIssued} — the active {@code ISSUED} version summary, or
 * null when no active issued quote exists (never sent yet, or the latest issued version was
 * superseded / cancelled / expired / accepted away). {@code accepted} is the 16F acceptance layer
 * and is ALWAYS null until then; it stays typed {@link Object} rather than building its summary DTO
 * early — the JSON shape {@code {draft, current_issued, accepted: null}} is what the 16D/16E
 * frontend consumes.
 */
public record QuoteWorkspaceDto(
        QuoteDraftDto draft,
        QuoteIssuedSummaryDto currentIssued,
        Object accepted
) {

    public static QuoteWorkspaceDto of(QuoteDraftDto draft, QuoteIssuedSummaryDto currentIssued) {
        return new QuoteWorkspaceDto(draft, currentIssued, null);
    }
}
