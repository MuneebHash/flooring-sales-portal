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

// Result of a current-invoice PDF download: the raw bytes plus the server-provided file name parsed
// from the D.4 Content-Disposition header. fileName is null when the header is absent or unreadable
// (e.g. a cross-origin response that does not expose Content-Disposition), so the caller can fall
// back to a client-side name.
export type InvoicePdfDownload = {
  blob: Blob
  fileName: string | null
}

// Reduce a candidate file name to a safe basename: strip any path segments, control characters and
// surrounding quotes/whitespace. Returns null when nothing usable remains.
function sanitizeFileName(name: string): string | null {
  const base = name.split(/[\\/]/).pop() ?? ''
  // Drop control characters (codepoint < 0x20 or DEL 0x7f) via codepoint filtering — no control
  // bytes embedded in a regex — then strip surrounding quotes/whitespace.
  let printable = ''
  for (const ch of base) {
    const code = ch.codePointAt(0) ?? 0
    if (code >= 0x20 && code !== 0x7f) printable += ch
  }
  const cleaned = printable.replace(/^["']+|["']+$/g, '').trim()
  return cleaned.length > 0 ? cleaned : null
}

// Parse the file name out of a Content-Disposition header, preferring the RFC 5987 extended form
// (filename*=UTF-8''…, which is what the backend actually emits) and falling back to the quoted and
// bare filename= forms. Returns null when no valid name is present so the caller can use a
// client-side fallback. Never throws.
function parseContentDispositionFilename(header: string | null): string | null {
  if (!header) return null
  // RFC 5987 extended: filename*=<charset>'<lang>'<percent-encoded-value>
  const extMatch = /filename\*\s*=\s*[^']*''([^;]+)/i.exec(header)
  if (extMatch) {
    let value = extMatch[1].trim()
    try {
      value = decodeURIComponent(value)
    } catch {
      // Malformed percent-encoding — keep the raw value and sanitize it.
    }
    const cleaned = sanitizeFileName(value)
    if (cleaned) return cleaned
  }
  // Quoted: filename="…"
  const quotedMatch = /filename\s*=\s*"([^"]*)"/i.exec(header)
  if (quotedMatch) {
    const cleaned = sanitizeFileName(quotedMatch[1])
    if (cleaned) return cleaned
  }
  // Bare: filename=… (up to the next ';' or end of header)
  const bareMatch = /filename\s*=\s*([^;]+)/i.exec(header)
  if (bareMatch) {
    const cleaned = sanitizeFileName(bareMatch[1])
    if (cleaned) return cleaned
  }
  return null
}

// GET /api/v1/{slug}/orders/{orderId}/invoices/current/file — fetch the current invoice PDF as raw
// bytes PLUS the server file name from Content-Disposition. This canNOT use request<T>: the file
// endpoint returns RAW BINARY (application/pdf) while request<T>/parseBody always JSON-parses the
// body. It is also session-protected, so a bare href would not carry the session cookie — hence a
// credentialed fetch -> Blob (-> one-shot object URL in the component). Mirrors
// orderAttachmentsApi.fetchAttachmentBlob. The fixed /current/file path is assembled here so the
// download always targets the CURRENT invoice (pdf_download_path is neither relied on nor exposed).
// The returned fileName is the authoritative name the backend just streamed for the CURRENT invoice,
// so it stays correct even if the invoice changed since the tab's last read; it is null when the
// header is missing/unreadable (e.g. cross-origin without Access-Control-Expose-Headers) and the
// caller falls back to a client-side name. On a non-2xx response the body is the standard JSON error
// envelope (e.g. 404 INVOICE_NOT_FOUND), so parse it to preserve the backend code/message. Allowed
// on LAID orders.
export async function fetchCurrentInvoicePdf(
  orderId: number,
): Promise<InvoicePdfDownload> {
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

  const fileName = parseContentDispositionFilename(
    response.headers.get('Content-Disposition'),
  )
  const blob = await response.blob()
  return { blob, fileName }
}
