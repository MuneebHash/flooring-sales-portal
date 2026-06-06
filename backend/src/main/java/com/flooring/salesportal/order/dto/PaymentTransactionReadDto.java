package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One payment row (Phase 12 Chunk 4 E.3 {@code payment_transaction_read}, OpenAPI
 * {@code PaymentTransactionRead}). Used in the D.6 list rows and the D.7 response.
 *
 * <p>Only the five contract columns are exposed. The backend-only gateway fields
 * ({@code gateway_transaction_id}, {@code response_status}, {@code response_message}) are NEVER
 * returned — they stay {@code null} for manual MVP payments. Jackson's global snake_case strategy
 * renders the field names ({@code paymentTransactionId} -> {@code payment_transaction_id}).
 * {@code payment_reference} is nullable in the schema and is serialized as JSON {@code null} (never
 * omitted) so the contract's required key is always present.
 */
public record PaymentTransactionReadDto(
        long paymentTransactionId,
        String paymentMethod,
        BigDecimal amount,
        @JsonInclude(JsonInclude.Include.ALWAYS) String paymentReference,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt
) {
}
