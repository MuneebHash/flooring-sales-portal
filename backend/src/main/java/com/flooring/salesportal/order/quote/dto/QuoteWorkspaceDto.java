package com.flooring.salesportal.order.quote.dto;

/**
 * Full quote state for the Quote tab (openapi {@code QuoteWorkspace}): {@code { draft, current_issued,
 * accepted }}. All three keys are always present and may be null.
 *
 * <p>Phase 16C PR1 (money/data core) implements only the draft layer, so {@code currentIssued} and
 * {@code accepted} are ALWAYS null here — the issued/accepted layers are populated in Phase 16E/16F.
 * They are typed as {@link Object} (always null) rather than building their summary DTOs early; the
 * JSON shape {@code {draft, current_issued: null, accepted: null}} is preserved for the 16D frontend.
 */
public record QuoteWorkspaceDto(
        QuoteDraftDto draft,
        Object currentIssued,
        Object accepted
) {

    public static QuoteWorkspaceDto draftOnly(QuoteDraftDto draft) {
        return new QuoteWorkspaceDto(draft, null, null);
    }
}
