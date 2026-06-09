import { API_BASE_URL } from './config'
import { ApiError } from './ApiError'
import { DEFAULT_BUSINESS_SLUG } from '../tenant'
import { get, post } from './client'
import { apiPath } from './paths'
import type { ApiSuccess } from './types'

// Phase 12 Chunk 4 — current invoice wiring (D.1 create, D.2 rewrite, D.3 read current,
// D.4 download current PDF). Field names are snake_case to mirror the backend JSON verbatim
// (the backend serializes with SNAKE_CASE), matching orderNotesApi.ts / orderAttachmentsApi.ts
// / orderWorkspaceApi.ts.
//
// MVP exposes ONLY the current/latest invoice — no history list, no revision dropdown, and no
// access to older versions. The invoice table keeps version_number internally, but the API and
// frontend expose a single current invoice.
//
// LAID split (conventions §16): Create is ALLOWED on a LAID order (an existing invoice is caught
// by 409 INVOICE_ALREADY_EXISTS regardless of status); read + PDF download are ALLOWED; only
// manual Rewrite is BLOCKED (422 ORDER_LOCKED). The `locked` flag therefore gates Rewrite ONLY in
// the UI — never create/read/download.

// The current/latest invoice — exactly the backend InvoiceDetail (E.2 invoice_detail). The
// internal stored_file_id / storage_path are never returned. invoice_id / order_id /
// created_by_user_id / pdf_download_path are present in the contract but are internal/plumbing
// and must NOT be rendered in the UI. due_date is nullable (always serialized, never omitted).
export type InvoiceDetail = {
  invoice_id: number
  order_id: number
  version_number: number
  invoice_date: string
  due_date: string | null
  details_of_sale_snapshot: string
  sale_price_ex_gst: number
  sale_price_inc_gst: number
  total_paid: number
  balance_due: number
  created_by_user_id: number
  created_at: string
  pdf_download_path: string
}

// Create (201) / Rewrite (201) / Read current (200) all wrap the invoice under an `invoice` key
// inside the standard ApiSuccess `data` envelope. Create/Rewrite include a top-level message
// ("Invoice created." / "Invoice rewritten."); Read current does not.
export type InvoiceResponse = {
  invoice: InvoiceDetail
}

// GET /api/v1/{slug}/orders/{orderId}/invoices/current — read the current/latest invoice. When no
// invoice exists yet the backend returns 404 INVOICE_NOT_FOUND; the caller treats that as a clean
// empty state, NOT an error. Allowed on LAID orders (read-only).
export function fetchCurrentInvoice(
  orderId: number,
): Promise<ApiSuccess<InvoiceResponse>> {
  return get<ApiSuccess<InvoiceResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/invoices/current`),
  )
}

// POST /api/v1/{slug}/orders/{orderId}/invoices — create the first (version 1) invoice. The body
// MUST be an empty object {} (any field -> 400 VALIDATION_FAILED). No LAID gate; 409
// INVOICE_ALREADY_EXISTS if one already exists; 422 INVOICE_PRECONDITIONS_NOT_MET (with details[])
// when the order is not invoice-ready.
export function createInvoice(
  orderId: number,
): Promise<ApiSuccess<InvoiceResponse>> {
  return post<ApiSuccess<InvoiceResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/invoices`),
    {},
  )
}

// POST /api/v1/{slug}/orders/{orderId}/invoices/rewrite — regenerate the current invoice from the
// live order state. The body MUST be {}. BLOCKED on LAID orders (422 ORDER_LOCKED, checked first);
// 422 INVOICE_REQUIRED when there is no invoice to rewrite; 422 INVOICE_PRECONDITIONS_NOT_MET (with
// details[]) otherwise.
export function rewriteInvoice(
  orderId: number,
): Promise<ApiSuccess<InvoiceResponse>> {
  return post<ApiSuccess<InvoiceResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/invoices/rewrite`),
    {},
  )
}

// GET /api/v1/{slug}/orders/{orderId}/invoices/current/file — fetch the current invoice PDF as raw
// bytes. This canNOT use request<T>: the file endpoint returns RAW BINARY (application/pdf) while
// request<T>/parseBody always JSON-parses the body. It is also session-protected, so a bare href
// would not carry the session cookie — hence a credentialed fetch -> Blob (-> one-shot object URL
// in the component). Mirrors orderAttachmentsApi.fetchAttachmentBlob. The fixed /current/file path
// is assembled here so the download always targets the CURRENT invoice (pdf_download_path is
// neither relied on nor exposed). On a non-2xx response the body is the standard JSON error
// envelope (e.g. 404 INVOICE_NOT_FOUND), so parse it to preserve the backend code/message. Allowed
// on LAID orders.
export async function fetchCurrentInvoicePdf(orderId: number): Promise<Blob> {
  const base = API_BASE_URL.replace(/\/+$/, '')
  const path = apiPath(
    DEFAULT_BUSINESS_SLUG,
    `/orders/${orderId}/invoices/current/file`,
  )
  let response: Response
  try {
    response = await fetch(`${base}${path}`, {
      credentials: 'include',
    })
  } catch (err) {
    throw new ApiError({
      status: 0,
      code: null,
      message: 'Network request failed.',
      details: err,
    })
  }

  if (!response.ok) {
    let code: string | null = null
    let message = 'Could not download the invoice PDF.'
    try {
      const body: unknown = await response.json()
      const errorObj =
        body && typeof body === 'object' && 'error' in body
          ? (body as { error?: { code?: unknown; message?: unknown } }).error
          : null
      if (errorObj && typeof errorObj === 'object') {
        if (typeof errorObj.code === 'string' && errorObj.code.length > 0) {
          code = errorObj.code
        }
        if (
          typeof errorObj.message === 'string' &&
          errorObj.message.length > 0
        ) {
          message = errorObj.message
        }
      }
    } catch {
      // Non-JSON / empty error body — keep the friendly fallback message.
    }
    throw new ApiError({ status: response.status, code, message })
  }

  return response.blob()
}
