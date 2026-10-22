import { API_BASE_URL } from './config'
import { ApiError } from './ApiError'
import type { ApiSuccess } from './types'
import type { FlooringType } from '../flooring'

// Phase 16G PR 5 — PUBLIC, token-only invoice API (16G contract §9; the backend
// shipped in 16G PR 4): GET /api/v1/public/invoices/{token} (open the order's
// current invoice), GET .../pdf?version_number=N (the stored PDF of the version
// shown) and POST .../accept (remote signing). These endpoints are
// UNAUTHENTICATED: the secret token in the URL is the only credential.
//
// They must NOT go through apiPath(slug, …) (they are slugless, like
// publicQuoteApi.ts) and must NOT go through the shared client.ts request() —
// that helper hardcodes credentials: 'include', and the public surface is fetched
// WITHOUT credentials (credentials: 'omit'; no session, no cookies). That applies
// to EVERY request here, the accept POST included. Nothing here reads the active
// slug or any auth state.
//
// There is no viewed-tracking endpoint on the invoice surface, so there is no
// viewed helper. Field names are snake_case to mirror the backend JSON verbatim.
// The view is cost-free and id-free by contract, and this client keeps it so: it
// copies exactly the 27 documented keys out of a response (anything else is
// dropped, never typed or passed on) — no cost or GP, no internal id, no storage
// reference, no token or token hash.

export type PublicInvoiceState =
  | 'ACTIVE'
  | 'REPLACED'
  | 'REWRITTEN'
  | 'SIGNED'
  | 'EXPIRED'

const PUBLIC_INVOICE_STATES: readonly string[] = [
  'ACTIVE',
  'REPLACED',
  'REWRITTEN',
  'SIGNED',
  'EXPIRED',
]

// The public invoice view (openapi PublicInvoiceView; contract §9.3) — exactly
// these 27 keys. ACTIVE carries the order's CURRENT invoice version (its frozen
// Invoice To, details of sale and stored terms, its dates and money), the
// business presentation read live and the presented link's own expires_at, with
// message null. A dead state (REPLACED / REWRITTEN / SIGNED / EXPIRED) carries
// only state, business_name and its per-state message; every other key is null,
// so every document field is typed nullable. version_number is the page's
// identity for the PDF read and the accept.
export type PublicInvoiceView = {
  state: PublicInvoiceState
  business_name: string
  business_logo_url: string | null
  accent_color: string | null
  business_abn: string | null
  payment_account_name: string | null
  payment_bank_name: string | null
  payment_bsb: string | null
  payment_account_number: string | null
  payment_stripe_link_url: string | null
  order_number: string | null
  flooring_type: FlooringType | null
  version_number: number | null
  invoice_date: string | null
  due_date: string | null
  customer_name: string | null
  customer_address_line1: string | null
  customer_address_line2: string | null
  details_of_sale: string | null
  sale_price_ex_gst: number | null
  gst_amount: number | null
  sale_price_inc_gst: number | null
  total_paid: number | null
  balance_due: number | null
  terms_html: string | null
  expires_at: string | null
  message: string | null
}

function publicInvoiceUrl(token: string, suffix = ''): string {
  const base = API_BASE_URL.replace(/\/+$/, '')
  return `${base}/api/v1/public/invoices/${encodeURIComponent(token)}${suffix}`
}

// Parse the standard { error: { code, message } } envelope into an ApiError.
// The publicQuoteApi idiom, except for one thing: when the body carries no
// readable message, the message is left EMPTY instead of a generic client text,
// so the page applies the contract's own fallback for that answer ("Invoice not
// found." for a 404, "Invoice updated, please refresh" for a 409, ...). Never
// throws.
async function toPublicApiError(response: Response): Promise<ApiError> {
  let code: string | null = null
  let message = ''
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
    // Non-JSON / empty error body — no code and no message.
  }
  return new ApiError({ status: response.status, code, message })
}

// Thrown by the field readers below when a key holds a value of the wrong type.
class NotAPublicInvoiceView extends Error {}

// A nullable string key: an absent or JSON-null value is null.
function stringField(raw: Record<string, unknown>, key: string): string | null {
  const value = raw[key]
  if (value === null || value === undefined) return null
  if (typeof value !== 'string') throw new NotAPublicInvoiceView(key)
  return value
}

// A nullable money key: a finite JSON number, or null.
function numberField(raw: Record<string, unknown>, key: string): number | null {
  const value = raw[key]
  if (value === null || value === undefined) return null
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new NotAPublicInvoiceView(key)
  }
  return value
}

// version_number: a positive integer, or null.
function versionField(raw: Record<string, unknown>): number | null {
  const value = numberField(raw, 'version_number')
  if (value !== null && (!Number.isInteger(value) || value < 1)) {
    throw new NotAPublicInvoiceView('version_number')
  }
  return value
}

function flooringField(raw: Record<string, unknown>): FlooringType | null {
  const value = stringField(raw, 'flooring_type')
  if (value === null || value === 'SOFT' || value === 'HARD') return value
  throw new NotAPublicInvoiceView('flooring_type')
}

// Read the view out of a 200 body, keeping exactly the 27 documented keys with
// their contract types. Returns null when the body is not a readable view: an
// unknown state, a missing business_name, a value of the wrong type, or an
// ACTIVE view without what the page signs against (version_number, invoice_date
// and the five amounts). A dead state keeps only state, business_name and
// message: every document key is null whatever arrived, so a dead view can never
// carry invoice content.
function readPublicInvoiceView(body: unknown): PublicInvoiceView | null {
  const data =
    body && typeof body === 'object' && 'data' in body
      ? (body as { data?: unknown }).data
      : null
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null
  const raw = data as Record<string, unknown>
  const state = raw.state
  if (typeof state !== 'string' || !PUBLIC_INVOICE_STATES.includes(state)) {
    return null
  }
  const businessName = raw.business_name
  if (typeof businessName !== 'string') return null
  try {
    const message = stringField(raw, 'message')
    if (state !== 'ACTIVE') {
      return {
        state: state as PublicInvoiceState,
        business_name: businessName,
        business_logo_url: null,
        accent_color: null,
        business_abn: null,
        payment_account_name: null,
        payment_bank_name: null,
        payment_bsb: null,
        payment_account_number: null,
        payment_stripe_link_url: null,
        order_number: null,
        flooring_type: null,
        version_number: null,
        invoice_date: null,
        due_date: null,
        customer_name: null,
        customer_address_line1: null,
        customer_address_line2: null,
        details_of_sale: null,
        sale_price_ex_gst: null,
        gst_amount: null,
        sale_price_inc_gst: null,
        total_paid: null,
        balance_due: null,
        terms_html: null,
        expires_at: null,
        message,
      }
    }
    const view: PublicInvoiceView = {
      state: 'ACTIVE',
      business_name: businessName,
      business_logo_url: stringField(raw, 'business_logo_url'),
      accent_color: stringField(raw, 'accent_color'),
      business_abn: stringField(raw, 'business_abn'),
      payment_account_name: stringField(raw, 'payment_account_name'),
      payment_bank_name: stringField(raw, 'payment_bank_name'),
      payment_bsb: stringField(raw, 'payment_bsb'),
      payment_account_number: stringField(raw, 'payment_account_number'),
      payment_stripe_link_url: stringField(raw, 'payment_stripe_link_url'),
      order_number: stringField(raw, 'order_number'),
      flooring_type: flooringField(raw),
      version_number: versionField(raw),
      invoice_date: stringField(raw, 'invoice_date'),
      due_date: stringField(raw, 'due_date'),
      customer_name: stringField(raw, 'customer_name'),
      customer_address_line1: stringField(raw, 'customer_address_line1'),
      customer_address_line2: stringField(raw, 'customer_address_line2'),
      details_of_sale: stringField(raw, 'details_of_sale'),
      sale_price_ex_gst: numberField(raw, 'sale_price_ex_gst'),
      gst_amount: numberField(raw, 'gst_amount'),
      sale_price_inc_gst: numberField(raw, 'sale_price_inc_gst'),
      total_paid: numberField(raw, 'total_paid'),
      balance_due: numberField(raw, 'balance_due'),
      terms_html: stringField(raw, 'terms_html'),
      expires_at: stringField(raw, 'expires_at'),
      message,
    }
    if (
      view.version_number === null ||
      view.invoice_date === null ||
      view.sale_price_ex_gst === null ||
      view.gst_amount === null ||
      view.sale_price_inc_gst === null ||
      view.total_paid === null ||
      view.balance_due === null
    ) {
      return null
    }
    return view
  } catch (err) {
    if (err instanceof NotAPublicInvoiceView) return null
    throw err
  }
}

// GET /api/v1/public/invoices/{token} — open the order's current invoice. 200
// for EVERY found token, active or dead (data.state tells them apart); only an
// unknown or malformed token is a 404 (INVOICE_TOKEN_NOT_FOUND, "Invoice not
// found."). Nothing is stamped on a read. A network failure is an ApiError with
// status 0; a 2xx whose body is not a readable view (see readPublicInvoiceView)
// is an ApiError carrying that 2xx status, so the caller can treat it as no
// readable answer.
export async function fetchPublicInvoice(
  token: string,
): Promise<PublicInvoiceView> {
  let response: Response
  try {
    response = await fetch(publicInvoiceUrl(token), {
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
    throw await toPublicApiError(response)
  }
  let body: unknown
  try {
    body = await response.json()
  } catch (err) {
    throw new ApiError({
      status: response.status,
      code: null,
      message: 'The invoice response could not be read.',
      details: err,
    })
  }
  const view = readPublicInvoiceView(body)
  if (view === null) {
    throw new ApiError({
      status: response.status,
      code: null,
      message: 'Response body is not a public invoice view.',
    })
  }
  return view
}

// Result of a public stored invoice-PDF fetch: raw bytes plus the server file
// name from Content-Disposition (inline; filename="invoice-{order_number}-v{N}.pdf").
// fileName is null when the header is absent or yields no usable name; the
// caller then falls back to invoice.pdf.
export type PublicInvoicePdfDownload = {
  blob: Blob
  fileName: string | null
}

// Reduce a candidate file name to a safe basename (module-private copy of the
// publicQuoteApi helper): strip path segments, control characters and
// surrounding quotes/whitespace.
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
// publicQuoteApi helper. Never throws.
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

// GET /api/v1/public/invoices/{token}/pdf?version_number=N — the STORED PDF of
// the version the page shows (never regenerated; no other version is
// reachable), as raw bytes, WITHOUT credentials. versionNumber must be the
// version_number of the view on screen, never a later one: the server only
// compares it with the current version and answers 409 INVOICE_VERSION_STALE
// ("Invoice updated, please refresh") when another version is current. Other
// errors (standard JSON envelope): 400 VALIDATION_FAILED; 404
// INVOICE_TOKEN_NOT_FOUND; 410 INVOICE_LINK_REPLACED / _REWRITTEN / _SIGNED /
// _EXPIRED.
export async function fetchPublicInvoicePdf(
  token: string,
  versionNumber: number,
): Promise<PublicInvoicePdfDownload> {
  let response: Response
  try {
    response = await fetch(
      publicInvoiceUrl(
        token,
        `/pdf?version_number=${encodeURIComponent(String(versionNumber))}`,
      ),
      {
        credentials: 'omit',
      },
    )
  } catch (err) {
    throw new ApiError({
      status: 0,
      code: null,
      message: 'Network request failed.',
      details: err,
    })
  }
  if (!response.ok) {
    throw await toPublicApiError(response)
  }
  const fileName = parseContentDispositionFilename(
    response.headers.get('Content-Disposition'),
  )
  const blob = await response.blob()
  return { blob, fileName }
}

// The data of a successful public accept (openapi PublicInvoiceAcceptResult):
// always state SIGNED (the invoice is signed and the link is now dead). No id,
// amount, name, timestamp, file reference, token or email outcome is returned.
export type PublicInvoiceAcceptResult = {
  state: 'SIGNED'
}

// POST /api/v1/public/invoices/{token}/accept — the customer signs the invoice
// version the page shows. multipart/form-data with EXACTLY two parts: the string
// form field expected_version_number (the version_number of the view on screen)
// and the FILE part signature (image/png, filename signature.png). Nothing else
// is ever appended: no name (the server takes the version's frozen customer
// name), no declaration flags (a frontend-only gate), no money values or ids. The
// browser sets the multipart Content-Type and boundary (no Content-Type header is
// set here). Sent WITHOUT credentials.
//
// The caller must pass a PNG inside the backend's safe-decode bounds (at most
// 8192 px per side, 4,000,000 px in total and 2,097,152 bytes; see
// lib/signaturePng.ts).
//
// 201 { data: { state: "SIGNED" }, message: "Invoice accepted." }: the response
// never says whether the signed PDF was emailed. Errors keep the backend
// code/message (standard envelope): 404 INVOICE_TOKEN_NOT_FOUND; 410
// INVOICE_LINK_*; 409 INVOICE_VERSION_STALE; 400 VALIDATION_FAILED /
// SIGNATURE_INVALID / FILE_TOO_LARGE; 415; 422 SIGNATURE_REQUIRED and
// ACCEPTED_CUSTOMER_NAME_REQUIRED ("This invoice can no longer be accepted
// online. Please contact the store."). A network failure is an ApiError with
// status 0. A 2xx whose body is not the documented result (data.state SIGNED) is
// an ApiError carrying that 2xx status: the caller must treat it as an UNKNOWN
// outcome (the acceptance may have gone through) and re-read the link, never as
// success and never as a definite failure.
export async function acceptPublicInvoice(
  token: string,
  expectedVersionNumber: number,
  signatureBlob: Blob,
): Promise<ApiSuccess<PublicInvoiceAcceptResult>> {
  const formData = new FormData()
  formData.append('expected_version_number', String(expectedVersionNumber))
  // The explicit filename marks this as a FILE part; its Content-Type comes from
  // the Blob's own type, which must be exactly image/png.
  formData.append('signature', signatureBlob, 'signature.png')
  let response: Response
  try {
    response = await fetch(publicInvoiceUrl(token, '/accept'), {
      method: 'POST',
      credentials: 'omit',
      body: formData,
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
    throw await toPublicApiError(response)
  }
  let body: unknown
  try {
    body = await response.json()
  } catch (err) {
    throw new ApiError({
      status: response.status,
      code: null,
      message: 'The invoice acceptance response could not be read.',
      details: err,
    })
  }
  const data =
    body && typeof body === 'object' && 'data' in body
      ? (body as { data?: unknown }).data
      : null
  const state =
    data && typeof data === 'object' && 'state' in data
      ? (data as { state?: unknown }).state
      : null
  if (state !== 'SIGNED') {
    throw new ApiError({
      status: response.status,
      code: null,
      message: 'The invoice acceptance response could not be read.',
    })
  }
  const message = (body as { message?: unknown }).message
  return {
    data: { state: 'SIGNED' },
    message:
      typeof message === 'string' && message.length > 0 ? message : undefined,
  }
}
