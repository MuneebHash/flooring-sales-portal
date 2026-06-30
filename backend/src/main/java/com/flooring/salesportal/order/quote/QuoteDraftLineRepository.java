package com.flooring.salesportal.order.quote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Read access to {@code quote_draft_line} rows for a draft, returned in display order. The write
 * path (full-replace delete + insert) lives in {@link QuoteDraftWriteRepository}.
 */
public interface QuoteDraftLineRepository extends JpaRepository<QuoteDraftLine, Long> {

    List<QuoteDraftLine> findByQuoteDraftIdOrderBySortOrderAsc(Long quoteDraftId);
}
