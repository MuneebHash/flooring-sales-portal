package com.flooring.salesportal.order.quote;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 16E-A — the in-memory snapshot of ONE quote issue, built exactly once per new issued
 * version ({@link QuoteSendService}) and consumed by BOTH the {@code quote_version} /
 * {@code quote_version_line} inserts and the issued-PDF render
 * ({@link QuotePdfModelAssembler#assembleIssued}). Feeding the database and the PDF from the same
 * object is what guarantees the stored rows and the stored PDF can never disagree.
 *
 * <p><b>Dormant-row guard (contract §6.1, the locked 16E rule).</b> {@code lines} is MODE-SCOPED
 * at construction ({@link #of}): an itemised draft snapshots its persisted draft lines; a
 * NON-itemised draft snapshots an EMPTY list — retained dormant itemised rows are draft-workspace
 * state only and are never copied into {@code quote_version_line} and never rendered on a
 * non-itemised issued PDF. This is the single place the issued line set is derived, so the guard
 * is structural, not incidental.
 *
 * <p>{@code termsHtml} is the per-flooring-type tenant terms, sanitized ONCE at issue time
 * ({@code InvoiceTermsSanitizer}) — it is stored verbatim as the frozen
 * {@code quote_version.terms_snapshot} and rendered verbatim on the issued PDF, so later tenant
 * term edits can never alter an already-issued quote. {@code detailsOfSale} / {@code flooringType}
 * are the order values frozen at the same moment.
 *
 * <p><b>Customer identity snapshot (V17 — Codex P1 fix).</b> {@code customerName} /
 * {@code customerAddressLine1} / {@code customerAddressLine2} freeze the customer-facing
 * "Quotation To" block at issue (nullable — an order may be issued with no saved customer name or
 * billing address). Derived by the caller with the exact customer-name/billing-line helpers the
 * issued PDF uses, so the stored PDF bytes and the snapshot columns can never disagree. The
 * PUBLIC payload renders ONLY these columns — a pre-LAID customer edit after issue can no longer
 * leak the new person's identity to the old token holder.
 */
public record QuoteIssueSnapshot(
        boolean itemised,
        BigDecimal quoteTotalExGst,
        BigDecimal quoteTotalIncGst,
        String flooringType,
        String termsHtml,
        String detailsOfSale,
        String customerName,
        String customerAddressLine1,
        String customerAddressLine2,
        List<Line> lines) {

    /** One issued line (immutable copy of a persisted draft line at issue; contract §4.4). */
    public record Line(
            String lineType,
            String description,
            BigDecimal quantity,
            BigDecimal unitPriceExGst,
            BigDecimal lineTotalExGst,
            int sortOrder) {
    }

    /**
     * Build the snapshot from the persisted draft + its persisted lines. The mode-scoping here IS
     * the dormant-row guard: {@code draftLines} is consulted ONLY when the draft is itemised.
     */
    public static QuoteIssueSnapshot of(QuoteDraft draft,
                                        List<QuoteDraftLine> draftLines,
                                        String flooringType,
                                        String sanitizedTermsHtml,
                                        String detailsOfSale,
                                        String customerName,
                                        String customerAddressLine1,
                                        String customerAddressLine2) {
        List<Line> snapshotLines = draft.isItemised()
                ? draftLines.stream()
                        .map(l -> new Line(l.getLineType(), l.getDescription(), l.getQuantity(),
                                l.getUnitPriceExGst(), l.getLineTotalExGst(), l.getSortOrder()))
                        .toList()
                : List.of();
        return new QuoteIssueSnapshot(
                draft.isItemised(),
                draft.getQuoteTotalExGst(),
                draft.getQuoteTotalIncGst(),
                flooringType,
                sanitizedTermsHtml,
                detailsOfSale,
                customerName,
                customerAddressLine1,
                customerAddressLine2,
                snapshotLines);
    }
}
