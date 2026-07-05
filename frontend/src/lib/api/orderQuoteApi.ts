import { API_BASE_URL } from './config'
import { ApiError } from './ApiError'
import { getActiveSlug } from '../tenant'
import { get, put } from './client'
import { apiPath } from './paths'
import type { ApiSuccess } from './types'

// Phase 16D-B PR1 — quote draft wiring (workspace read, draft save, preview PDF).
// Field names are snake_case to mirror the backend JSON verbatim (the backend
// serializes with SNAKE_CASE), matching orderInvoicesApi.ts / orderWorkspaceApi.ts.
//
// Only THREE quote endpoints exist on the backend (Phase 16C): the workspace GET,
// the draft PUT and the on-demand preview-pdf POST. Send (email/SMS), cancel,
// create-invoice, stored-PDF download and the public token surface are Phase
// 16E/16F — they must NOT be called or stubbed here.
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

// GET /quote/workspace payload. All three keys are always present. draft is null
// until the first successful draft save — that nullability is the LOCKED source
// of truth for revealing the Quote tab on order load. current_issued / accepted
// are ALWAYS null until Phase 16E/16F populate the issued/accepted layers.
export type QuoteWorkspace = {
  draft: QuoteDraftRead | null
  current_issued: null
  accepted: null
}

// PUT /quote/draft body — FULL-REPLACE upsert (a valid body with fewer lines
// replaces the stored lines, so callers must always send the COMPLETE draft).
//   Non-itemised (the only mode 16D-B PR1 edits): itemised MUST be false,
//   final_total_inc_gst (GST-INCLUSIVE, >= 0, <= QUOTE_TOTAL_MAX) is REQUIRED and
//   lines MUST be []. The backend stores a single synthetic internal line that is
//   never rendered.
//   Itemised: lines carry the draft; final_total_inc_gst below the line sum makes
//   the server insert a negative ADJUSTMENT, above it is a 422
//   QUOTE_TOTAL_EXCEEDS_LINES — itemised editing is Phase 16D-B PR2.
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
