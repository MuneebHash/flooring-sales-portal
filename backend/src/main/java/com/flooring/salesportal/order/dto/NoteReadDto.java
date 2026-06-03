package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * One note in the order notes responses (Chunk 3 D.12 GET / D.13 POST, OpenAPI {@code NoteRead}).
 *
 * <p>Sourced from {@code order_note}. Only the three contract columns are exposed: {@code order_id}
 * is scoping-only (never returned) and the table has no {@code created_by_user_id} / {@code
 * updated_at} columns, so user attribution and an update timestamp are deliberately absent (notes
 * are append-only / immutable in the MVP). Jackson's global snake_case strategy renders the field
 * names ({@code orderNoteId} → {@code order_note_id}); {@code createdAt} matches the existing read
 * DTOs' {@code yyyy-MM-dd'T'HH:mm:ss} format.
 */
public record NoteReadDto(
        long orderNoteId,
        String noteText,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt
) {
}
