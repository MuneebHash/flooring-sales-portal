import { API_BASE_URL } from './config'
import { ApiError } from './ApiError'
import { getActiveSlug } from '../tenant'
import { get, post } from './client'
import { apiPath } from './paths'
import type { ApiSuccess } from './types'

// Phase 12 Chunk 4 — current invoice wiring (D.1 create, D.2 rewrite, D.3 read current,
// D.4 download current PDF) — plus Phase 13 acceptance (D.8 accept, D.9 resend, D.10 signature
// download). Field names are snake_case to mirror the backend JSON verbatim (the backend
// serializes with SNAKE_CASE), matching orderNotesApi.ts / orderAttachmentsApi.ts /
// orderWorkspaceApi.ts.
//
// MVP exposes ONLY the current/latest invoice — no history list, no revision dropdown, and no
// access to older versions. The invoice table keeps version_number internally, but the API and
// frontend expose a single current invoice. Phase 13 adds NO invoice-status enum: acceptance/email
// state derives from the nullable fields below.
//
// LAID split (conventions §16): Create is ALLOWED on a LAID order (an existing invoice is caught
// by 409 INVOICE_ALREADY_EXISTS regardless of status); read + PDF download are ALLOWED; only
// manual Rewrite is BLOCKED (422 ORDER_LOCKED). The `locked` flag therefore gates Rewrite ONLY in
// the UI — never create/read/download. Phase 13 Accept / Resend / signature download are ALL
// allowed when LAID (acceptance parallels payment, not rewrite), so none of them is `locked`-gated.

// The current/latest invoice — exactly the backend InvoiceDetail (E.2 invoice_detail). The
// internal stored_file_id / accepted_signature_file_id / storage_path are never returned.
// invoice_id / order_id / created_by_user_id / pdf_download_path are present in the contract but
// are internal/plumbing and must NOT be rendered in the UI. due_date is nullable (always
// serialized, never omitted).
//
// Phase 13 acceptance/email fields are ALWAYS present (nullable values serialized as null):
// an unaccepted invoice has accepted_at / accepted_customer_name /
// accepted_signature_download_path / last_emailed_at all null and accepted_signature_present
// false. accepted_signature_download_path is the backend-built relative URL of the D.10
// signature stream, consumed verbatim by fetchCurrentInvoiceSignature. last_emailed_at is a
// delivery marker: null = this invoice version has never been emailed (including when an
// Accept's auto-email failed — Re-send retries it).
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
  accepted_at: string | null
  accepted_customer_name: string | null
  accepted_signature_present: boolean
  accepted_signature_download_path: string | null
  last_emailed_at: string | null
}

// Create (201) / Rewrite (201) / Read current (200) / Accept (201) / Resend (200) all wrap the
// invoice under an `invoice` key inside the standard ApiSuccess `data` envelope. Create/Rewrite
// include a top-level message ("Invoice created." / "Invoice rewritten."); Accept/Resend include
// the Phase 13 messages (see acceptCurrentInvoice / resendCurrentInvoice); Read current does not.
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
    apiPath(getActiveSlug(), `/orders/${orderId}/invoices/current`),
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
    apiPath(getActiveSlug(), `/orders/${orderId}/invoices`),
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
    apiPath(getActiveSlug(), `/orders/${orderId}/invoices/rewrite`),
    {},
  )
}

// Matches invoice.accepted_customer_name VARCHAR(150) / the backend trim+length check
// (a trimmed name longer than this is a 400 VALIDATION_FAILED, not a 422).
export const ACCEPTED_NAME_MAX_LENGTH = 150

// POST /api/v1/{slug}/orders/{orderId}/invoices/current/accept — Phase 13 D.8. Accept/sign the
// CURRENT invoice: appends a new current version carrying the sale snapshot forward, stores the
// signature, regenerates the signed PDF, and auto-emails it to the customer.
//
// The body is multipart/form-data with EXACTLY two parts — text field `accepted_customer_name`
// (trimmed here; non-blank; max 150 chars) and file part `signature` (declared content type must
// be exactly `image/png` — canvas.toBlob('image/png') produces it — non-empty, max 2 MB). Any
// other or duplicated part -> 400 VALIDATION_FAILED. The request client passes FormData through
// raw, so the browser sets the multipart Content-Type + boundary; never set Content-Type manually.
//
// Returns 201 in BOTH email outcomes (the auto-email is best-effort and never fails the call):
// email sent -> message "Invoice accepted and emailed to the customer." with last_emailed_at set;
// email failed -> message "Invoice accepted. The invoice could not be emailed — use Re-send
// Invoice to try again." with last_emailed_at null. Surface the message VERBATIM and derive email
// state from last_emailed_at, never from the message text. Errors: 409 INVOICE_ALREADY_ACCEPTED;
// 422 INVOICE_REQUIRED / ACCEPTED_CUSTOMER_NAME_REQUIRED / SIGNATURE_REQUIRED /
// CUSTOMER_EMAIL_REQUIRED / CUSTOMER_EMAIL_INVALID; 400 SIGNATURE_INVALID / VALIDATION_FAILED.
// Never 502. Allowed when LAID.
export function acceptCurrentInvoice(
  orderId: number,
  acceptedCustomerName: string,
  signatureBlob: Blob,
): Promise<ApiSuccess<InvoiceResponse>> {
  const formData = new FormData()
  formData.append('accepted_customer_name', acceptedCustomerName.trim())
  // The explicit filename marks this as a FILE part (not a form field); the part's Content-Type
  // comes from the Blob's own type, which must be exactly image/png.
  formData.append('signature', signatureBlob, 'signature.png')
  return post<ApiSuccess<InvoiceResponse>>(
    apiPath(
      getActiveSlug(),
      `/orders/${orderId}/invoices/current/accept`,
    ),
    formData,
  )
}

// POST /api/v1/{slug}/orders/{orderId}/invoices/current/resend — Phase 13 D.9. Re-email the
// CURRENT accepted invoice PDF. The body MUST be {} (any field -> 400 VALIDATION_FAILED). No new
// signature, no new invoice version, no PDF regeneration — on success only last_emailed_at is
// stamped in place. Availability is gated on the invoice being ACCEPTED only (it works when
// last_emailed_at is still null because the Accept auto-email failed). 200 with message "Invoice
// re-sent to the customer." Errors: 422 INVOICE_REQUIRED / INVOICE_NOT_ACCEPTED /
// CUSTOMER_EMAIL_REQUIRED / CUSTOMER_EMAIL_INVALID; 502 EMAIL_SEND_FAILED on provider failure
// (fatal HERE only — nothing is written, the invoice state is unchanged, the user may retry).
// Allowed when LAID.
export function resendCurrentInvoice(
  orderId: number,
): Promise<ApiSuccess<InvoiceResponse>> {
  return post<ApiSuccess<InvoiceResponse>>(
    apiPath(
      getActiveSlug(),
      `/orders/${orderId}/invoices/current/resend`,
    ),
    {},
  )
}

// GET {accepted_signature_download_path} — Phase 13 D.10. Fetch the accepted signature image
// (raw image/png bytes) for display. This canNOT use request<T>: the endpoint returns RAW BINARY
// while request<T>/parseBody always JSON-parses the body. It is also session-protected, so a bare
// <img src> would not carry the session cookie — hence a credentialed fetch -> Blob (-> object URL
// in the component, revoked by the component's lifecycle).
//
// `downloadPath` is the backend-provided relative accepted_signature_download_path, used VERBATIM
// (only prefixed with the API base) — mirrors orderAttachmentsApi.fetchAttachmentBlob. Only call
// when accepted_signature_present is true; the backend's own guards (404 ORDER_NOT_FOUND /
// INVOICE_NOT_FOUND, 422 INVOICE_NOT_ACCEPTED) return the standard JSON error envelope, parsed
// here to preserve the backend code/message. Allowed when LAID (read).
export async function fetchCurrentInvoiceSignature(
  downloadPath: string,
): Promise<Blob> {
  const base = API_BASE_URL.replace(/\/+$/, '')
  let response: Response
  try {
    response = await fetch(`${base}${downloadPath}`, {
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
    let message = 'Could not load the signature image.'
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
    getActiveSlug(),
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
