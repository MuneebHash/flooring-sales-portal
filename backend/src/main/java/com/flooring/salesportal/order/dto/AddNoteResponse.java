package com.flooring.salesportal.order.dto;

/**
 * Response payload for {@code POST /orders/{orderId}/notes} (201) — Chunk 3 D.13, OpenAPI
 * {@code AddNoteResponse}: the created note nested under a {@code note} key. Wrapped by the standard
 * {@code ApiResponse} {@code data} envelope with the message "Note added.", so the serialized body
 * is {@code { "data": { "note": { ... } }, "message": "Note added." }}.
 *
 * <p>Adding a note does NOT affect order financials, so — unlike the line mutation responses — there
 * is no {@code order_financial_summary} on this payload (Chunk 3 D.13 / F.10).
 */
public record AddNoteResponse(
        NoteReadDto note
) {
}
