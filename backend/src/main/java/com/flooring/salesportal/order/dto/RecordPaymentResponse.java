package com.flooring.salesportal.order.dto;

/**
 * The {@code data} object for D.7 POST /orders/{orderId}/payments. Serializes as
 * {@code { "data": { "payment_transaction": { ...E.3... }, "payment_summary": { ...E.4... },
 * "current_invoice": { ...E.1... } }, "message": "Payment recorded. Current invoice updated." } }
 * (Chunk 4 D.7).
 *
 * <p>{@code current_invoice} uses the {@link CurrentInvoiceSummaryDto} (E.1) shape — the
 * payment-driven invoice version that was atomically created alongside the payment. The wrapper is
 * built with {@code ApiResponse.ok(data, message)}.
 */
public record RecordPaymentResponse(
        PaymentTransactionReadDto paymentTransaction,
        PaymentSummaryDto paymentSummary,
        CurrentInvoiceSummaryDto currentInvoice
) {
}
