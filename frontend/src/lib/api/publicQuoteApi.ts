import { API_BASE_URL } from './config'
import { ApiError } from './ApiError'

// Phase 16E-C — PUBLIC, token-only quote API (GET /api/v1/public/quotes/{token},
// POST .../viewed, GET .../pdf). These endpoints are UNAUTHENTICATED: the secret
// token in the URL is the only credential.
//
// They must NOT go through apiPath(slug, …) (they are slugless, like
// tenantApi.ts) and must NOT go through the shared client.ts request() — that
// helper hardcodes credentials: 'include', and the public surface is fetched
// WITHOUT credentials (credentials: 'omit'; no session, no cookies — the
// browser must never attach SP_SESSION to a public quote request).
//
// Field names are snake_case to mirror the backend JSON verbatim. The payload
// is cost-free and internal-ID-free by contract: no token hash, no storage
// path, no stored_file id, no cost/GP field is ever returned — and none may be
// typed here. There is deliberately NO signing/acceptance helper (Phase 16F)
// and NO SMS helper.

export type PublicQuoteState =
  | 'ACTIVE'
  | 'EXPIRED'
  | 'SUPERSEDED'
  | 'CANCELLED'
  | 'INACTIVE'

// One public quote line (openapi PublicQuoteLine). Narrower than the protected
// QuoteIssuedLine: no line_type and no sort_order ride on the public surface —
// order is positional, and an ADJUSTMENT row is simply a null-quantity /
// null-unit signed amount.
export type PublicQuoteLine = {
  description: string
  quantity: number | null
  unit_price_ex_gst: number | null
  line_total_ex_gst: number
}

// The public quote view (openapi PublicQuoteView). state === 'ACTIVE' carries
// the full document payload (frozen issued snapshot body + live presentation
// context); every dead state (EXPIRED / SUPERSEDED / CANCELLED / INACTIVE) is
// the minimal payload — state, business_name, message, all document fields
// null. The page shows ONE generic dead-link message regardless of which dead
// state arrives (locked 16E-C decision — no state-specific customer wording).
export type PublicQuoteView = {
  state: PublicQuoteState
  business_name: string | null
  business_logo_url: string | null
  accent_color: string | null
  business_abn: string | null
  payment_account_name: string | null
  payment_bank_name: string | null
  payment_bsb: string | null
  payment_account_number: string | null
  payment_stripe_link_url: string | null
  order_number: string | null
  flooring_type: string | null
  customer_name: string | null
  customer_address_line1: string | null
  customer_address_line2: string | null
  details_of_sale: string | null
  itemised: boolean | null
  lines: PublicQuoteLine[] | null
  quote_total_ex_gst: number | null
  gst_amount: number | null
  quote_total_inc_gst: number | null
  deposit_amount: number | null
  terms_html: string | null
  expires_at: string | null
  message: string | null
}

function publicQuoteUrl(token: string, suffix = ''): string {
  const base = API_BASE_URL.replace(/\/+$/, '')
  return `${base}/api/v1/public/quotes/${encodeURIComponent(token)}${suffix}`
}

// Parse the standard { error: { code, message } } envelope into an ApiError.
// Module-private copy of the orderQuoteApi idiom. Never throws.
async function toPublicApiError(
  response: Response,
  fallbackMessage: string,
): Promise<ApiError> {
  let code: string | null = null
  let message = fallbackMessage
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
  return new ApiError({ status: response.status, code, message })
}

// GET /api/v1/public/quotes/{token} — open the quote. 200 for EVERY found
// token (data.state distinguishes ACTIVE from the dead states); only an
// unknown/malformed token is a 404 (QUOTE_TOKEN_NOT_FOUND). Does NOT mark the
// quote viewed — callers fire markPublicQuoteViewed after a successful ACTIVE
// render.
export async function fetchPublicQuote(token: string): Promise<PublicQuoteView> {
  let response: Response
  try {
    response = await fetch(publicQuoteUrl(token), {
      credentials: 'omit',
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
    throw await toPublicApiError(response, 'Could not load the quote.')
  }
  const body = (await response.json()) as { data?: PublicQuoteView }
  if (!body || typeof body !== 'object' || !body.data) {
    throw new ApiError({
      status: response.status,
      code: null,
      message: 'Response body is not a public quote view.',
    })
  }
  return body.data
}

// POST /api/v1/public/quotes/{token}/viewed — mark the FIRST public view.
// Idempotent server-side (viewed_at is write-once; repeats are no-ops), so the
// page fires this after every successful ACTIVE load and ignores the result.
// Errors: 404 unknown token; 410 dead link; both are non-fatal to the caller
// (the quote already rendered) and are surfaced only as a rejected promise.
export async function markPublicQuoteViewed(token: string): Promise<void> {
  let response: Response
  try {
    response = await fetch(publicQuoteUrl(token, '/viewed'), {
      method: 'POST',
      credentials: 'omit',
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
    throw await toPublicApiError(response, 'Could not record the quote view.')
  }
}

// Result of a public stored quote-PDF fetch: raw bytes plus the server file
// name from Content-Disposition (inline; filename="quote-{order_number}-v{n}.pdf").
export type PublicQuotePdfDownload = {
  blob: Blob
  fileName: string | null
}

// Reduce a candidate file name to a safe basename (module-private copy of the
// orderQuoteApi helper, which is not exported): strip path segments, control
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
// orderQuoteApi helper. Never throws.
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

// GET /api/v1/public/quotes/{token}/pdf — fetch the STORED issued quote PDF
// (the exact frozen artifact that was emailed; the backend never regenerates
// it) as raw bytes, WITHOUT credentials. Errors (standard JSON envelope): 404
// QUOTE_TOKEN_NOT_FOUND / QUOTE_PDF_NOT_FOUND; 410 QUOTE_LINK_* (dead link).
export async function fetchPublicQuotePdf(
  token: string,
): Promise<PublicQuotePdfDownload> {
  let response: Response
  try {
    response = await fetch(publicQuoteUrl(token, '/pdf'), {
      credentials: 'omit',
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
    throw await toPublicApiError(response, 'Could not download the quote PDF.')
  }
  const fileName = parseContentDispositionFilename(
    response.headers.get('Content-Disposition'),
  )
  const blob = await response.blob()
  return { blob, fileName }
}
