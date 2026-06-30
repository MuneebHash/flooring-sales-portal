package com.flooring.salesportal.order.quote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Read access to the one-per-order {@code quote_draft} row (uq_quote_draft_order). Empty when no
 * draft has been saved yet. The write path is the native upsert in {@link QuoteDraftWriteRepository}.
 */
public interface QuoteDraftRepository extends JpaRepository<QuoteDraft, Long> {

    Optional<QuoteDraft> findByOrderId(Long orderId);
}
