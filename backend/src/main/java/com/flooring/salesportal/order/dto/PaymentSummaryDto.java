package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Current official payment summary (Phase 12 Chunk 4 E.4 {@code payment_summary}, OpenAPI
 * {@code PaymentSummary}). Used in the D.6 list response and the D.7 record response.
 *
 * <p>{@code total_paid} is the rounded sum of all {@code payment_transaction.amount} for the order.
 * {@code balance_due} is the latest <em>official</em> invoice version's {@code balance_due}; it is
 * {@code null} on D.6 when no invoice exists yet, so it is annotated {@code ALWAYS} to serialize as
 * JSON {@code null} (the contract requires the key to be present). On D.7 an invoice always exists,
 * so {@code balance_due} is never {@code null} there.
 */
public record PaymentSummaryDto(
        BigDecimal totalPaid,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal balanceDue
) {
}
