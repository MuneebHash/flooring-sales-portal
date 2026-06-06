package com.flooring.salesportal.order.dto;

import com.flooring.salesportal.common.api.PaginationMeta;

import java.util.List;

/**
 * The {@code data} object for D.6 GET /orders/{orderId}/payments. Serializes as
 * {@code { "data": { "payments": [ ...E.3... ], "payment_summary": { ...E.4... },
 * "pagination": { ... } } }} with NO top-level {@code message} (Chunk 4 D.6).
 *
 * <p>Unlike the standard list envelope ({@code ApiResponse.page}, where {@code pagination} is a
 * sibling of {@code data}), this endpoint nests {@code pagination} <em>inside</em> {@code data}
 * alongside {@code payments} and {@code payment_summary}, so the wrapper is built with
 * {@code ApiResponse.ok(data)} and the {@link PaginationMeta} lives on this record. {@code PaginationMeta}
 * serializes to {@code page} / {@code page_size} / {@code total_items} / {@code total_pages} via the
 * global snake_case strategy.
 */
public record PaymentsListResponse(
        List<PaymentTransactionReadDto> payments,
        PaymentSummaryDto paymentSummary,
        PaginationMeta pagination
) {
}
