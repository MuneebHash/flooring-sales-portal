import { getActiveSlug } from '../tenant'
import { get, post } from './client'
import { apiPath } from './paths'
import type { ApiSuccess, Pagination } from './types'

// Phase 12 Chunk 4 — order payments (D.6 GET list, D.7 POST record). Field names are
// snake_case to mirror the backend JSON verbatim (the backend serializes with SNAKE_CASE),
// matching orderInvoicesApi.ts / orderNotesApi.ts / orderAttachmentsApi.ts.
//
// Payment is invoice-first: recording a payment requires an existing invoice (else 422
// INVOICE_REQUIRED), but does NOT require invoice acceptance. Payments are ALLOWED when the
// order is LAID (conventions §16: add payment is in the LAID-allowed list — payments never hit
// ORDER_LOCKED). Recording a payment atomically regenerates the current invoice (a new internal
// version) updating only total_paid / balance_due; it never makes unsent live order edits official.
//
// The client sends ONLY payment_method / amount / payment_reference. Every other column —
// the gateway fields (gateway_transaction_id / response_status / response_message), ids, and any
// *_snapshot field — is backend-owned and rejected if sent (OpenAPI PaymentCreateRequest declares
// additionalProperties: false → 400 VALIDATION_FAILED).
//
// MVP exposes ONLY the current/latest invoice in the POST response (current_invoice); there is no
// invoice history, revision dropdown, or old-version PDF access here.

// Locked payment_method enum (conventions §13 / OpenAPI PaymentMethod).
export type PaymentMethod = 'CASH' | 'CREDIT_CARD' | 'EFTPOS' | 'BANK_TRANSFER'

// One payment row — exactly the backend PaymentTransactionRead (E.3). Used both as a GET list row
// and as the POST data.payment_transaction payload. The backend-only gateway columns are never
// returned. payment_reference is nullable (always serialized, never omitted); created_at is the
// "yyyy-MM-dd'T'HH:mm:ss" timestamp.
//
// Phase 15D soft-void markers: voided_at (the "yyyy-MM-dd'T'HH:mm:ss" void timestamp; null = active)
// and voided_by_name (the display name of the session actor who voided it; null = active) are ALWAYS
// serialized. There is NO is_voided field — derive voided state from voided_at !== null. The raw
// voided_by_user_id is deliberately never exposed.
export type PaymentTransaction = {
  payment_transaction_id: number
  payment_method: PaymentMethod
  amount: number
  payment_reference: string | null
  created_at: string
  voided_at: string | null
  voided_by_name: string | null
}

// Current official payment summary (E.4). total_paid = rounded sum of all payments for the order.
// balance_due is the latest official invoice version's balance_due — NULL on the list (D.6) when no
// invoice exists yet, never null on the record response (D.7) where an invoice always exists.
export type PaymentSummary = {
  total_paid: number
  balance_due: number | null
}

// The `data` object for GET /payments (D.6). NOTE: this is NOT the standard ApiCollection list
// envelope — pagination is nested INSIDE data alongside payments + payment_summary, and there is no
// top-level message. So the call is typed ApiSuccess<PaymentsListResponse>, and the rows live at
// data.payments (not data). Backend defaults: page 1 / page_size 20, newest first.
export type PaymentsListResponse = {
  payments: PaymentTransaction[]
  payment_summary: PaymentSummary
  pagination: Pagination
}

// POST body — the ONLY three fields the backend accepts. payment_reference is optional; when present
// it must be a non-blank, trimmed string of at most 100 chars. OMIT the key entirely (never send ""
// or null) when there is no reference. Never add gateway / id / snapshot fields.
export type RecordPaymentRequest = {
  payment_method: PaymentMethod
  amount: number
  payment_reference?: string
}

// The payment-driven current invoice returned in the D.7 response (E.1 current_invoice_summary).
// This is the SUMMARY shape — deliberately NOT the full InvoiceDetail (E.2): it omits order_id,
// details_of_sale_snapshot and sale_price_ex_gst. The Payments tab does not render it (the Invoice
// tab refetches its own current invoice on remount), so it is typed only for contract completeness.
//
// Phase 13/15D: the five acceptance/email fields are ALWAYS present (nullable values serialized as
// null). A payment (or a void) on an ACCEPTED invoice carries acceptance forward onto the new
// version (accepted_at / accepted_customer_name / signature preserved — the customer does NOT
// re-sign). Phase 15D: neither recording nor voiding a payment auto-emails — last_emailed_at starts
// null on the regenerated version (the dashboard mirror is reset to null) and manual Re-send Invoice
// is the only email action. A payment/void on an unaccepted invoice leaves the acceptance fields
// null/false.
export type PaymentCurrentInvoiceSummary = {
  invoice_id: number
  version_number: number
  invoice_date: string
  due_date: string | null
  sale_price_inc_gst: number
  total_paid: number
  balance_due: number
  created_by_user_id: number
  created_at: string
  pdf_download_path: string
  accepted_at: string | null
  accepted_customer_name: string | null
  accepted_signature_present: boolean
  accepted_signature_download_path: string | null
  last_emailed_at: string | null
}

// POST 201 data envelope: the created payment, the refreshed payment_summary, and the regenerated
// current invoice, wrapped by the standard ApiSuccess `data` envelope. Phase 15D: recording a
// payment NEVER auto-emails — even on an accepted invoice the acceptance/signature carries forward
// and the signed PDF is regenerated, but no email is sent and last_emailed_at stays null (the
// dashboard mirror is reset to null); manual Re-send Invoice is the only email action after a
// payment change. The Payments tab surfaces the backend `message` VERBATIM.
export type RecordPaymentResponse = {
  payment_transaction: PaymentTransaction
  payment_summary: PaymentSummary
  current_invoice: PaymentCurrentInvoiceSummary
}

// POST 201 data envelope for D.10 soft-void: the now-voided payment row (voided_at / voided_by_name
// populated), the recalculated payment_summary (active total_paid EXCLUDES voided rows — always
// backend-computed, never re-summed client-side), and the regenerated current invoice. Backend
// `message` (surfaced VERBATIM): "Payment voided. Current invoice updated." Soft void only — the row
// stays visible in history marked voided; there is no hard delete, no refund/negative row, and no
// auto-email (manual Re-send Invoice remains the only email action).
export type VoidPaymentResponse = {
  payment_transaction: PaymentTransaction
  payment_summary: PaymentSummary
  current_invoice: PaymentCurrentInvoiceSummary
}

// The payments list has no pagination UI (MVP). Request the backend's max page_size (100) in a
// single fetch so older payments don't silently drop off after the default 20; an order
// realistically never has >100 manual payments. The backend caps page_size at 100, so this is the
// largest single page available.
const PAYMENTS_PAGE_SIZE = 100

// GET /api/v1/{slug}/orders/{orderId}/payments — list payments (newest first) + current official
// payment summary. Allowed regardless of order status (read-only, LAID-exempt). Sends page_size=100
// (single-page MVP view; no pager). Returns ApiSuccess (NOT ApiCollection): read the rows from
// res.data.payments and the summary from res.data.payment_summary.
export function fetchOrderPayments(
  orderId: number,
): Promise<ApiSuccess<PaymentsListResponse>> {
  return get<ApiSuccess<PaymentsListResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/payments`),
    { query: { page_size: PAYMENTS_PAGE_SIZE } },
  )
}

// POST /api/v1/{slug}/orders/{orderId}/payments — record a payment against the current invoice
// balance and atomically regenerate the current invoice. Allowed when LAID. 422 INVOICE_REQUIRED
// when no invoice exists; 422 PAYMENT_EXCEEDS_BALANCE when amount > balance_due; 400 VALIDATION_FAILED
// for a bad/forbidden field. Unwrap res.data.payment_transaction / res.data.payment_summary.
export function recordOrderPayment(
  orderId: number,
  body: RecordPaymentRequest,
): Promise<ApiSuccess<RecordPaymentResponse>> {
  return post<ApiSuccess<RecordPaymentResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/payments`),
    body,
  )
}

// POST /api/v1/{slug}/orders/{orderId}/payments/{paymentTransactionId}/void — soft-void a recorded
// payment (201) and atomically regenerate the current invoice. Allowed when LAID (the inverse of
// recording a payment). The endpoint takes NO request body: send {} — the backend rejects any body
// field with 400 VALIDATION_FAILED (matching the no-body POST pattern in orderInvoicesApi.ts). 404
// PAYMENT_NOT_FOUND when the payment isn't in this order; 409 PAYMENT_ALREADY_VOIDED on a re-void.
// Unwrap res.data.payment_transaction / res.data.payment_summary; the active total_paid is
// backend-recalculated (voided rows excluded) — never re-sum client-side.
export function voidOrderPayment(
  orderId: number,
  paymentTransactionId: number,
): Promise<ApiSuccess<VoidPaymentResponse>> {
  return post<ApiSuccess<VoidPaymentResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/payments/${paymentTransactionId}/void`),
    {},
  )
}
