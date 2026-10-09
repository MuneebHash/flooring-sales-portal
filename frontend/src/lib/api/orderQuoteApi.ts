import { API_BASE_URL } from './config'
import { ApiError } from './ApiError'
import { getActiveSlug } from '../tenant'
import type { FlooringType } from '../flooring'
import { get, post, put } from './client'
import { apiPath } from './paths'
import type { ApiSuccess } from './types'
import type { InvoiceResponse } from './orderInvoicesApi'

// Phase 16D-B PR1 — quote draft wiring (workspace read, draft save, preview PDF).
// Phase 16E-B adds the delivery wrappers: send-email, cancel and the stored
// issued-PDF download. Phase 16F PR3 adds the accepted-quote reads (the stored
// signed PDF and the accepted signature image) and the Path A create-invoice
// action. Field names are snake_case to mirror the backend JSON verbatim (the
// backend serializes with SNAKE_CASE), matching orderInvoicesApi.ts /
// orderWorkspaceApi.ts.
//
// Wired quote endpoints: the workspace GET, the draft PUT, the on-demand
// preview-pdf POST, the send-email POST, the cancel POST, the stored-PDF GET
// (type=issued|accepted), the accepted-signature GET and the create-invoice POST.
// The backend send-sms endpoint exists (16E-A) but is DORMANT and deliberately
// NOT wrapped here (delivery is email only). The public token surface (view,
// viewed, PDF, accept) lives in publicQuoteApi.ts and never uses the session.
//
// Phase 16D-A decoupling (locked): PUT /quote/draft saves the quote draft ONLY.
// It never updates the order sale-price override or any sales_order header
// financial, so a quote save must never trigger an order/header financial
// refresh — the save response updates quote draft state only.
//
// COST DISCIPLINE (locked): quote payloads and responses carry NO cost. The
// backend rejects (400 VALIDATION_FAILED, "Not allowed.") any body key that is
// off the allow-list, contains "cost", or ends with "_snapshot" — so never
// spread a product/charge DTO into a quote body; the whole save would fail.

export type QuoteLineType = 'ITEM' | 'ADJUSTMENT'

// A draft line as READ from the backend (workspace GET / draft PUT response).
// quantity / unit_price_ex_gst are omitted (JsonInclude NON_NULL) for ADJUSTMENT
// lines; quote_draft_line_id is present on reads (the save response re-reads the
// persisted lines, so it carries real ids too) — typed optional defensively.
export type QuoteDraftLineRead = {
  quote_draft_line_id?: number
  line_type: QuoteLineType
  description: string
  quantity?: number
  unit_price_ex_gst?: number
  line_total_ex_gst: number
  sort_order: number
}

// Write shape — a discriminated union so the compiler enforces the backend's
// per-type rules:
//   ITEM: quantity (> 0) + unit_price_ex_gst (>= 0) + line_total_ex_gst are all
//     REQUIRED. line_total_ex_gst is required by the contract but IGNORED by the
//     server, which recomputes quantity x unit (HALF_UP 2dp) — send the same
//     rounded product so the response matches what was displayed.
//   ADJUSTMENT: quantity / unit_price_ex_gst must be OMITTED (a non-null value is
//     a 400 "Must be null for an adjustment line."); line_total_ex_gst is a
//     SIGNED amount (may be negative).
// NEVER include quote_draft_line_id, any *cost* field or any *_snapshot field in
// a write payload. sort_order must be an integer 0..1,000,000 (backend cap).
export type QuoteDraftItemLineInput = {
  line_type: 'ITEM'
  description: string
  quantity: number
  unit_price_ex_gst: number
  line_total_ex_gst: number
  sort_order: number
}

export type QuoteDraftAdjustmentLineInput = {
  line_type: 'ADJUSTMENT'
  description: string
  line_total_ex_gst: number
  sort_order: number
}

export type QuoteDraftLineInput =
  | QuoteDraftItemLineInput
  | QuoteDraftAdjustmentLineInput

// The editable quote draft as the backend returns it. gp_percent / below_cost
// are server-DERIVED protected-surface signals (never raw cost): gp_percent is
// null when not computable; below_cost true means save/send/accept are blocked.
// NOTE: a workspace READ recomputes both against the order's CURRENT cost lines,
// so below_cost can be true even though the last save succeeded.
export type QuoteDraftRead = {
  itemised: boolean
  quote_total_ex_gst: number
  quote_total_inc_gst: number
  gp_percent: number | null
  below_cost: boolean
  lines: QuoteDraftLineRead[]
  updated_at: string | null
}

// Delivery channel of the latest send attempt (backend QuoteChannel).
export type QuoteChannel = 'EMAIL' | 'SMS'

// One FROZEN issued snapshot line (backend QuoteIssuedSummaryDto.Line / openapi
// QuoteIssuedLine): the customer-facing quote_version_line fields only.
// quantity / unit_price_ex_gst are null for ADJUSTMENT lines. Read-only issued
// history — never sent back in any payload. No cost fields, no token fields, no
// storage fields, no ids.
export type QuoteIssuedLine = {
  line_type: QuoteLineType
  description: string
  quantity: number | null
  unit_price_ex_gst: number | null
  line_total_ex_gst: number
  sort_order: number
}

// The active issued quote version summary (backend QuoteIssuedSummaryDto /
// openapi QuoteIssuedSummary): workspace `current_issued` plus the `data` of
// the send-email / cancel responses. Every field is always present (nullable
// ones arrive as JSON null). token_expires_at is the ACTIVE link's expiry ONLY
// — the token value/hash, stored_file ids, storage paths and any cost/GP field
// are NEVER returned by the backend and must never be typed here.
// details_of_sale + lines are the FROZEN issue snapshot (lines is ALWAYS []
// for a non-itemised issued quote) — the Customer Quote tab renders from this
// object alone, never from live draft/order state.
export type QuoteIssuedSummary = {
  quote_version_id: number
  version_number: number
  status: string
  itemised: boolean
  quote_total_ex_gst: number
  quote_total_inc_gst: number
  flooring_type: string
  sent_channel: QuoteChannel | null
  first_sent_at: string | null
  last_sent_at: string | null
  last_emailed_at: string | null
  viewed_at: string | null
  token_expires_at: string | null
  details_of_sale: string | null
  lines: QuoteIssuedLine[]
}

// The order's LATEST accepted quote version (backend QuoteAcceptedSummaryDto /
// openapi QuoteAcceptedSummary, Phase 16F PR1; invoice_eligible amended in PR2):
// workspace `accepted`. All fourteen fields are always present (nullable ones
// arrive as JSON null). Every body field is the FROZEN accepted snapshot, never
// the live draft or order: lines reuse the issued-line shape (null quantity /
// unit price on ADJUSTMENT rows) and are ALWAYS [] for a non-itemised version.
// accepted_customer_name is the V17 issue-snapshot name (decision D1), never
// typed and never truncated. accepted_at is the backend timestamp string,
// serialized WITH fractional seconds (unlike InvoiceDetail.accepted_at, which is
// formatted to whole seconds). signature_download_path is the backend-built
// protected path of the accepted signature stream, consumed VERBATIM (null when
// no signature is stored); the signed PDF is read via
// fetchQuoteStoredPdf(orderId, 'accepted') when signed_pdf_available is true.
// invoice_eligible is the server's signature-precedence rule for
// createInvoiceFromQuote (decision D5(b) as amended on 8 October 2026): never
// recompute it client-side. No file id, storage path, token, cost or GP field
// is ever returned - and none may be typed here.
export type QuoteAcceptedSummary = {
  quote_version_id: number
  version_number: number
  quote_total_inc_gst: number
  accepted_at: string
  accepted_customer_name: string
  accepted_signature_present: boolean
  invoice_eligible: boolean
  quote_total_ex_gst: number
  itemised: boolean
  flooring_type: FlooringType
  details_of_sale: string | null
  lines: QuoteIssuedLine[]
  signature_download_path: string | null
  signed_pdf_available: boolean
}

// GET /quote/workspace payload. All three keys are always present. draft is null
// until the first successful draft save. current_issued is the active ISSUED
// version summary (null when nothing is issued - never sent yet, or the latest
// issued version was cancelled/superseded/expired/accepted away). accepted is the
// latest ACCEPTED version (Phase 16F), selected independently of the draft and
// of current_issued, or null when no version has been accepted. A non-null
// value in ANY of the three reveals the Quote tab on order load.
export type QuoteWorkspace = {
  draft: QuoteDraftRead | null
  current_issued: QuoteIssuedSummary | null
  accepted: QuoteAcceptedSummary | null
}

// PUT /quote/draft body — FULL-REPLACE upsert (a valid body with fewer lines
// replaces the stored lines, so callers must always send the COMPLETE draft).
//   Non-itemised: itemised MUST be false, final_total_inc_gst (GST-INCLUSIVE,
//   >= 0, <= QUOTE_TOTAL_MAX) is REQUIRED and lines MUST be []. The save is
//   header-only (Phase 16D-B PR2A): no synthetic line is stored and previously
//   persisted itemised rows are RETAINED untouched (returned on reads as
//   dormant lines — never rendered in non-itemised mode).
//   Itemised (Phase 16D-B PR2B): lines carry the draft and final_total_inc_gst
//   MUST BE OMITTED (locked wire rule) — the client keeps total = sum of the
//   visible rows and manages ADJUSTMENT rows itself. (If it WERE sent: below
//   the line sum the server inserts a hidden negative ADJUSTMENT, above it is
//   a 422 QUOTE_TOTAL_EXCEEDS_LINES — behaviour the frontend must not lean on.)
export type QuoteDraftSaveRequest = {
  itemised: boolean
  final_total_inc_gst?: number
  lines: QuoteDraftLineInput[]
}

// Largest GST-inclusive quote total the backend accepts (DECIMAL(10,2)).
export const QUOTE_TOTAL_MAX = 99999999.99

// GET /api/v1/{slug}/orders/{orderId}/quote/workspace — load the full quote
// state for the Quote tab. Never 404s for a missing draft (data.draft is null).
// Allowed on LAID orders (read).
export function fetchQuoteWorkspace(
  orderId: number,
): Promise<ApiSuccess<QuoteWorkspace>> {
  return get<ApiSuccess<QuoteWorkspace>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/quote/workspace`),
  )
}

// PUT /api/v1/{slug}/orders/{orderId}/quote/draft — full-replace upsert of the
// editable draft. Success is 200 (not 201) and `data` IS the recomputed
// QuoteDraftRead directly (NOT nested under a `draft`/`invoice`-style key).
// Errors: 400 VALIDATION_FAILED / MALFORMED_JSON; 404 ORDER_NOT_FOUND; 422
// ORDER_LOCKED (LAID — checked before body parse) / QUOTE_BELOW_COST /
// QUOTE_TOTAL_EXCEEDS_LINES. Does NOT touch order/header pricing (16D-A).
export function saveQuoteDraft(
  orderId: number,
  body: QuoteDraftSaveRequest,
): Promise<ApiSuccess<QuoteDraftRead>> {
  return put<ApiSuccess<QuoteDraftRead>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/quote/draft`),
    body,
  )
}

// Result of a quote preview-PDF fetch: raw bytes plus the server file name from
// Content-Disposition (inline; filename="quote-preview-{order_number}.pdf").
export type QuotePreviewPdfDownload = {
  blob: Blob
  fileName: string | null
}

// Reduce a candidate file name to a safe basename (module-private copy of the
// orderInvoicesApi helper, which is not exported): strip path segments, control
// characters and surrounding quotes/whitespace.
function sanitizeFileName(name: string): string | null {
  const base = name.split(/[\\/]/).pop() ?? ''
  let printable = ''
  for (const ch of base) {
    const code = ch.codePointAt(0) ?? 0
    if (code >= 0x20 && code !== 0x7f) printable += ch
  }
  const cleaned = printable.replace(/^["']+|["']+$/g, '').trim()
  return cleaned.length > 0 ? cleaned : null
}

// Parse the file name out of a Content-Disposition header (RFC 5987 extended
// form preferred, then quoted, then bare). Module-private copy of the
// orderInvoicesApi helper. Never throws.
function parseContentDispositionFilename(header: string | null): string | null {
  if (!header) return null
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
  const quotedMatch = /filename\s*=\s*"([^"]*)"/i.exec(header)
  if (quotedMatch) {
    const cleaned = sanitizeFileName(quotedMatch[1])
    if (cleaned) return cleaned
  }
  const bareMatch = /filename\s*=\s*([^;]+)/i.exec(header)
  if (bareMatch) {
    const cleaned = sanitizeFileName(bareMatch[1])
    if (cleaned) return cleaned
  }
  return null
}

// POST /api/v1/{slug}/orders/{orderId}/quote/preview-pdf — on-demand DRAFT
// preview PDF, rendered from the PERSISTED draft (never from a request body), so
// callers must flush any pending autosave FIRST. Nothing is stored server-side.
//
// This canNOT use post<T>(): the endpoint returns RAW BINARY (application/pdf)
// while request<T>/parseBody JSON-parses every 2xx body. It is session-protected,
// so a bare href/window.open(url) would not reliably carry context — hence a
// credentialed fetch -> Blob (-> object URL in the component). Unlike the
// existing GET blob helpers (fetchCurrentInvoicePdf / fetchAttachmentBlob) this
// is a POST whose body MUST be an empty JSON object {} (any field is a 400
// VALIDATION_FAILED), so the Content-Type is set explicitly.
//
// Errors (standard JSON envelope, parsed to preserve code/message): 404
// QUOTE_NOT_FOUND when no draft has been persisted yet; 404 ORDER_NOT_FOUND.
// LAID is allowed (read-only render); a below-cost draft still previews.
export async function fetchQuotePreviewPdf(
  orderId: number,
): Promise<QuotePreviewPdfDownload> {
  const base = API_BASE_URL.replace(/\/+$/, '')
  const path = apiPath(getActiveSlug(), `/orders/${orderId}/quote/preview-pdf`)
  let response: Response
  try {
    response = await fetch(`${base}${path}`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
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
    let message = 'Could not generate the quote preview PDF.'
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

// POST /api/v1/{slug}/orders/{orderId}/quote/send-email — issue (or resend) the
// quote and email it (stored issued PDF + public link). The body MUST be an
// empty JSON object {}. 201; `data` is the updated QuoteIssuedSummary (never
// the token). The backend issues from the PERSISTED draft, so callers must
// flush the quote autosave AND the details autosave first (same double-flush
// discipline as the preview).
// Errors: 400 VALIDATION_FAILED / MALFORMED_JSON; 404 ORDER_NOT_FOUND /
// QUOTE_NOT_FOUND (no saved draft); 422 ORDER_LOCKED (LAID) / QUOTE_BELOW_COST
// (live re-check) / CUSTOMER_EMAIL_REQUIRED / CUSTOMER_EMAIL_INVALID; 502
// EMAIL_SEND_FAILED (delivery failed — the issued version/PDF/link are KEPT
// server-side, so retrying is safe and never duplicates a version).
export function sendQuoteEmail(
  orderId: number,
): Promise<ApiSuccess<QuoteIssuedSummary>> {
  return post<ApiSuccess<QuoteIssuedSummary>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/quote/send-email`),
    {},
  )
}

// NOTE: there is deliberately NO sendQuoteSms wrapper. The backend send-sms
// endpoint exists (16E-A) but frontend SMS enablement is Phase 16E-C — the
// Send Quote modal keeps its SMS button disabled until then.

// POST /api/v1/{slug}/orders/{orderId}/quote/cancel — cancel the active issued
// quote (version + public link → CANCELLED; rows kept server-side). The body
// MUST be an empty JSON object {}. 200; `data` is the CANCELLED summary
// (status 'CANCELLED', token_expires_at null). After success the workspace no
// longer returns a current_issued, so callers should clear their local issued
// state to null. ALLOWED when LAID (kills a public link only).
// Errors: 404 ORDER_NOT_FOUND; 422 QUOTE_NOT_ISSUED (nothing active to
// cancel); 409 QUOTE_ALREADY_ACCEPTED (no issued version and an accepted one
// exists - reachable since 16F PR1; an accepted version is never cancelled).
export function cancelQuote(
  orderId: number,
): Promise<ApiSuccess<QuoteIssuedSummary>> {
  return post<ApiSuccess<QuoteIssuedSummary>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/quote/cancel`),
    {},
  )
}

// Result of a stored quote-PDF fetch: raw bytes plus the server file name from
// Content-Disposition (inline; filename="quote-{order_number}-v{n}.pdf" for the
// issued PDF, "quote-{order_number}-v{n}-signed.pdf" for the signed PDF).
export type QuoteStoredPdfDownload = {
  blob: Blob
  fileName: string | null
}

// GET /api/v1/{slug}/orders/{orderId}/quote/pdf?type=issued|accepted - fetch a
// STORED quote PDF as raw bytes: type=issued is the active issued version's PDF
// (the exact frozen artifact that was sent); type=accepted (Phase 16F) is the
// latest accepted version's stored SIGNED PDF. Both are streamed verbatim, never
// regenerated, and the signed PDF is portal-only. Same credentialed-fetch-to-Blob
// pattern as fetchQuotePreviewPdf / fetchCurrentInvoicePdf: the endpoint returns
// RAW BINARY and is session-protected, so a bare href/window.open(url) would not
// reliably carry context. Read-only; allowed on LAID orders.
// Errors (standard JSON envelope): 404 ORDER_NOT_FOUND / QUOTE_PDF_NOT_FOUND
// (issued: no active issued version; accepted: no accepted version or no stored
// signed PDF).
export async function fetchQuoteStoredPdf(
  orderId: number,
  type: 'issued' | 'accepted',
): Promise<QuoteStoredPdfDownload> {
  const base = API_BASE_URL.replace(/\/+$/, '')
  const path = apiPath(
    getActiveSlug(),
    `/orders/${orderId}/quote/pdf?type=${type}`,
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
    let message = 'Could not download the quote PDF.'
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

// GET {accepted.signature_download_path} - Phase 16F. Fetch the latest accepted
// quote version's stored signature image (raw image/png bytes, the
// server-normalised PNG) for display in the Accepted Quote sub-tab. This canNOT
// use request<T>: the endpoint returns RAW BINARY while request<T>/parseBody
// always JSON-parses the body. It is also session-protected, so a plain image
// URL would not carry the session cookie - hence a credentialed fetch -> Blob
// (-> object URL owned and revoked by the component).
//
// `downloadPath` is the backend-built relative signature_download_path, used
// VERBATIM (only prefixed with the API base) - never rebuilt from ids; mirrors
// orderInvoicesApi.fetchCurrentInvoiceSignature. Only call when the path is
// non-null. The backend guards (404 ORDER_NOT_FOUND / QUOTE_SIGNATURE_NOT_FOUND)
// return the standard JSON error envelope, parsed here to preserve the backend
// code/message. Allowed when LAID (read).
export async function fetchAcceptedQuoteSignature(
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
      // Non-JSON / empty error body - keep the friendly fallback message.
    }
    throw new ApiError({ status: response.status, code, message })
  }

  return response.blob()
}

// POST /api/v1/{slug}/orders/{orderId}/quote/create-invoice - Phase 16F Path A:
// create an invoice version from the order's LATEST accepted quote, inheriting
// its signature (the customer does not sign again) and carrying the recorded
// payments. The body MUST be exactly {}: the SERVER selects the quote version, so
// no version selector, price, terms or signature reference is ever sent (any
// field is a 400 VALIDATION_FAILED). Distinct name from
// orderInvoicesApi.createInvoice (the Details of Sale Path B create).
// 201; `data.invoice` is the new InvoiceDetail (accepted, terms_source QUOTE)
// and `message` is "Invoice created from accepted quote.". No email is sent.
// Allowed when LAID.
// Errors: 404 ORDER_NOT_FOUND; 409 INVOICE_ALREADY_ACCEPTED (the current invoice
// was signed at the same time or later than the quote); 422 QUOTE_NOT_ACCEPTED /
// INVOICE_PRECONDITIONS_NOT_MET (details[] of {section, field, message}) /
// BUSINESS_RULE_VIOLATION (recorded payments exceed the accepted quote total).
export function createInvoiceFromQuote(
  orderId: number,
): Promise<ApiSuccess<InvoiceResponse>> {
  return post<ApiSuccess<InvoiceResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/quote/create-invoice`),
    {},
  )
}
