import { getActiveSlug } from '../tenant'
import { get, post } from './client'
import { apiPath } from './paths'
import type { ApiCollection, ApiSuccess } from './types'

// Phase 11 Chunk 3 — order notes (D.12 GET list, D.13 POST append). Field names
// are snake_case to mirror the backend JSON verbatim (the backend serializes with
// SNAKE_CASE), matching ordersApi.ts / orderWorkspaceApi.ts / orderLinesApi.ts.
//
// Notes are append-only and immutable in the MVP (no edit/delete endpoints).
// Both endpoints are allowed when the order is LAID (notes are EXEMPT from the
// LAID lock — never ORDER_LOCKED). Adding a note does NOT recompute or return the
// order_financial_summary. The client sends ONLY note_text; every other column
// (order_id / created_by_user_id / created_at / *_snapshot, …) is backend-owned
// and rejected if sent (OpenAPI AddNoteRequest declares additionalProperties:false).

// One note in the GET list AND the POST data.note payload. Exactly the backend
// NoteRead contract: three fields only. order_id is scoping-only and never
// returned; the order_note table has no created_by_user_id / updated_at columns,
// so user attribution and an update timestamp are deliberately absent.
export type OrderNote = {
  order_note_id: number
  note_text: string
  created_at: string
}

// POST body — note_text is the ONLY field the backend accepts (required,
// non-blank after trim). Never add backend-controlled fields.
export type AddNoteRequest = {
  note_text: string
}

// POST 201 data envelope: the created note nested under a `note` key, wrapped by
// the standard ApiSuccess `data` envelope with the message "Note added.".
export type AddNoteResponse = {
  note: OrderNote
}

// GET /api/v1/{slug}/orders/{orderId}/notes — list notes, newest first
// (created_at DESC, order_note_id DESC). Page 1 / page_size 20 are the backend
// defaults, so no query is sent. Allowed on LAID orders (read-only).
export function fetchOrderNotes(
  orderId: number,
): Promise<ApiCollection<OrderNote>> {
  return get<ApiCollection<OrderNote>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/notes`),
  )
}

// POST /api/v1/{slug}/orders/{orderId}/notes — append one note. The response
// carries only the created note (no financial summary). Allowed on LAID orders.
export function addOrderNote(
  orderId: number,
  body: AddNoteRequest,
): Promise<ApiSuccess<AddNoteResponse>> {
  return post<ApiSuccess<AddNoteResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/notes`),
    body,
  )
}
