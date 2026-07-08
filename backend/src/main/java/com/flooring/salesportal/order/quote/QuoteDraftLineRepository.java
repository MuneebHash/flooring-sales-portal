package com.flooring.salesportal.order.quote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Read access to {@code quote_draft_line} rows for a draft, returned in display order. The write
 * path (full-replace delete + insert) lives in {@link QuoteDraftWriteRepository}.
 *
 * <p>The PK is a SECONDARY sort key (Phase 16E-A review fix): duplicate {@code sort_order} values
 * are contract-legal ("display order", no uniqueness constraint), and without a tiebreaker the
 * relative order of tied rows is unspecified — which would make the send flow's positional
 * changed-detection compare nondeterministic (an unchanged draft could spuriously issue a new
 * version). Ascending PK = insertion order of the last itemised save, so every reader (workspace,
 * preview PDF, issue snapshot, changed-detection) sees ONE deterministic order.
 */
public interface QuoteDraftLineRepository extends JpaRepository<QuoteDraftLine, Long> {

    List<QuoteDraftLine> findByQuoteDraftIdOrderBySortOrderAscQuoteDraftLineIdAsc(Long quoteDraftId);
}
