import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import DOMPurify from 'dompurify'
import { Button } from './ui/Button'
import { SignaturePad, type SignaturePadHandle } from './workspace/SignaturePad'
import { ApiError } from '../lib/api/ApiError'
import {
  acceptPublicQuote,
  fetchPublicQuote,
  fetchPublicQuotePdf,
  markPublicQuoteViewed,
  type PublicQuoteView,
} from '../lib/api/publicQuoteApi'
import { toBoundedSignaturePng } from '../lib/signaturePng'

// Phase 16E-C + 16F PR3 - the PUBLIC customer quote page at /q/{token}:
// top-level, slugless, no login, no store selection, no authenticated app shell.
// The secret token in the URL is the only credential; every request goes through
// publicQuoteApi.ts WITHOUT credentials (no session cookie is ever attached).
//
// LAYOUT: the Invoice tab's document box (the part below the workspace tab bar),
// reworded for a quotation, without any staff navigation: header (logo fail-soft
// to the business name | QUOTATION + document number), Quotation To, Details of
// Sale, the itemised line table OR the non-itemised presentation (details and
// totals only, no line table), payment methods + totals, the display-only
// deposit sentence, the frozen terms, then the customer acceptance area and the
// footer. Everything renders from the backend's frozen issued snapshot payload,
// never from live draft/order state.
//
// SIGNING (16F PR3): the acceptance area sits AFTER the terms. The two
// declaration checkboxes are a frontend-only gate (decision D8: nothing is sent
// for them) and, with real ink on the pad, enable Accept. There is NO name
// field: the accepted name is the issue-time snapshot, taken server-side
// (decision D1). Accept posts the signature image ONLY (one multipart part,
// bounded to the backend's safe-decode limits). A synchronous guard makes the
// submit single-flight and is held through any status re-read. On 201 the page
// shows a LOCAL confirmation (no re-read: the link is now dead) and removes the
// PDF and signing actions. An ambiguous failure (no readable answer) re-reads
// the link state ONCE before another accept is offered and never re-posts on
// its own.
//
// Dead links on an ordinary load: EVERY failure (dead state EXPIRED /
// SUPERSEDED / CANCELLED / INACTIVE, unknown/malformed token, any fetch error)
// shows exactly ONE generic message (locked 16E-C decision; no state-specific
// wording). The accept response is the exception: its 410/422 messages are shown
// verbatim.
const DEAD_LINK_MESSAGE =
  'This quote link is no longer available. Please contact the store.'

const ACCEPTED_FALLBACK_MESSAGE = 'Quote accepted.'
const ACCEPT_FAILED_FALLBACK_MESSAGE =
  'This quote could not be accepted. Please try again.'
const NOT_CONFIRMED_MESSAGE =
  'We could not confirm your acceptance. Your signature is still here, so you can try again.'
const EXPORT_FAILED_MESSAGE =
  'Your signature could not be prepared for sending. Please clear it and sign again. If this keeps happening, reset the page zoom to 100% and try again.'

// The invoice declaration wording (InvoiceTab ACCEPTANCE_LINES), reused verbatim.
const ACCEPTANCE_LINES: string[] = [
  'I agree to pay the balance before the installation date.',
  'I agree that no floor preparation costs are included unless otherwise stated above.',
]

const ACCENT_DEFAULT = '#0e7a70'

const MONEY_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

function formatMoney(value: number): string {
  return MONEY_FORMATTER.format(value)
}

// Quantity without trailing zeros (2.00 -> "2", 1.50 -> "1.5") — the PDF's
// stripTrailingZeros presentation. Values are DECIMAL(10,2), safe as numbers.
function formatQuantity(value: number): string {
  return String(Number(value))
}

function flooringLabel(flooringType: string | null): string | null {
  if (flooringType === 'SOFT') return 'Soft Flooring'
  if (flooringType === 'HARD') return 'Hard Flooring'
  return null
}

type LoadState =
  | { phase: 'loading' }
  | { phase: 'dead' }
  | { phase: 'active'; quote: PublicQuoteView }

// The accept flow on an ACTIVE page.
//   idle        - signing open.
//   submitting  - exporting the signature / accept POST in flight.
//   reconciling - re-reading the link state after an ambiguous failure.
//   retry       - a definite failure, or a re-read that found the link still
//                 ACTIVE: message shown, signature and ticks kept, Accept
//                 available again (an explicit customer action, never automatic).
//   unknown     - ambiguous failure AND the status re-read failed: no success
//                 claim, no Accept; only "Check status".
//   link-dead   - the accept POST returned 404/410: its message verbatim,
//                 signing disabled for this link.
//   accepted    - the local success confirmation for this submit session.
type AcceptState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'reconciling' }
  | { kind: 'retry'; message: string }
  | { kind: 'unknown' }
  | { kind: 'link-dead'; message: string }
  | { kind: 'accepted'; message: string }

// Classify a failed accept POST.
//   ambiguous - no readable answer from the quote service: a network failure
//               (status 0), a gateway/timeout status (502 / 503 / 504; the accept
//               endpoint never returns these itself) or a 2xx whose body could
//               not be read. The acceptance MAY have gone through.
//   dead      - 404 / 410: this link cannot be signed.
//   definite  - any other HTTP error (400 / 415 / 422 / 500 ...): the backend
//               answered and nothing was accepted.
function classifyAcceptFailure(err: unknown): 'ambiguous' | 'dead' | 'definite' {
  if (!(err instanceof ApiError)) return 'ambiguous'
  if (err.status === 0) return 'ambiguous'
  if (err.status >= 200 && err.status < 300) return 'ambiguous'
  if (err.status === 502 || err.status === 503 || err.status === 504) {
    return 'ambiguous'
  }
  if (err.status === 404 || err.status === 410) return 'dead'
  return 'definite'
}

// The page is keyed by token: a different token remounts everything, so no
// tick, ink, in-flight request, outcome or object URL can leak across links.
export function PublicQuotePage() {
  const { token } = useParams<{ token: string }>()
  return <PublicQuoteDocument key={token ?? ''} token={token ?? ''} />
}

function PublicQuoteDocument({ token }: { token: string }) {
  const [state, setState] = useState<LoadState>({ phase: 'loading' })
  const [logoFailed, setLogoFailed] = useState(false)
  const [pdfBusy, setPdfBusy] = useState(false)
  const [pdfError, setPdfError] = useState<string | null>(null)

  // Signing state (frontend gate: both declarations ticked + real ink).
  const [acceptanceChecked, setAcceptanceChecked] = useState<boolean[]>(() =>
    ACCEPTANCE_LINES.map(() => false),
  )
  const [hasInk, setHasInk] = useState(false)
  const [acceptState, setAcceptState] = useState<AcceptState>({ kind: 'idle' })
  const padRef = useRef<SignaturePadHandle | null>(null)

  // Synchronous single-flight guard for the accept flow: taken BEFORE any await
  // (a double tap sends one POST), held through the signature export, the POST
  // and any status re-read, and released only when another explicit accept is
  // genuinely allowed (a definite failure, or a re-read that found the link
  // still ACTIVE).
  const submitGuardRef = useRef(false)
  // Single-flight for the status re-read ("Check status" double taps).
  const reconcilingRef = useRef(false)
  // Set once this page's link outcome is final (accepted locally, or dead):
  // no late load / PDF / viewed result may change what the page shows after it.
  const finalRef = useRef(false)

  const mountedRef = useRef(true)
  const objectUrlsRef = useRef<string[]>([])
  useEffect(() => {
    mountedRef.current = true
    const urls = objectUrlsRef.current
    return () => {
      mountedRef.current = false
      for (const url of urls) URL.revokeObjectURL(url)
      urls.length = 0
    }
  }, [])

  // Load the quote. ACTIVE -> render the document, then mark the view
  // (fire-and-forget and its result is IGNORED: the backend stamps viewed_at
  // write-once, so repeat visits and StrictMode double-effects are harmless
  // no-ops, a marking failure never disturbs the rendered page, and a late
  // answer can never restore ACTIVE after an acceptance). ANY other outcome -
  // dead state, 404, 410, network failure — is the one generic dead-link view.
  // A fresh load of an INACTIVE (signed) link is therefore the dead-link view;
  // the local confirmation exists only for the session that signed.
  useEffect(() => {
    if (!token) {
      setState({ phase: 'dead' })
      return
    }
    let cancelled = false
    setState({ phase: 'loading' })
    fetchPublicQuote(token)
      .then((quote) => {
        if (cancelled || finalRef.current) return
        if (quote.state === 'ACTIVE') {
          setState({ phase: 'active', quote })
          markPublicQuoteViewed(token).catch(() => {
            // First-view marking is best-effort; the page stays rendered.
          })
        } else {
          setState({ phase: 'dead' })
        }
      })
      .catch(() => {
        if (!cancelled && !finalRef.current) setState({ phase: 'dead' })
      })
    return () => {
      cancelled = true
    }
  }, [token])

  const quote = state.phase === 'active' ? state.quote : null

  // A fresh logo URL is a fresh chance to load (the InvoiceTab fail-soft rule).
  useEffect(() => {
    setLogoFailed(false)
  }, [quote?.business_logo_url])

  // Browser-tab / print-dialog title for the document view.
  useEffect(() => {
    if (quote) {
      document.title = quote.order_number
        ? `Quotation ${quote.order_number}`
        : 'Quotation'
    }
  }, [quote])

  // Frozen terms, re-sanitized client-side with the IDENTICAL DOMPurify config
  // the protected QuoteTab/InvoiceTab use (restricted allowlist, ALL attributes
  // stripped; a textless fragment hides the whole section). Defence in depth on
  // top of the backend's issue-time sanitization - do not weaken. A loaded
  // null/blank terms value legitimately hides the section (not a load failure).
  const sanitizedTermsHtml = useMemo(() => {
    const termsText = quote?.terms_html
    if (!termsText || termsText.trim().length === 0) return null
    const sanitized = DOMPurify.sanitize(termsText, {
      ALLOWED_TAGS: [
        'p',
        'br',
        'strong',
        'b',
        'em',
        'i',
        'u',
        'ol',
        'ul',
        'li',
        'table',
        'thead',
        'tbody',
        'tr',
        'th',
        'td',
        'div',
        'span',
        'small',
      ],
      ALLOWED_ATTR: [],
      ALLOW_DATA_ATTR: false,
      ALLOW_ARIA_ATTR: false,
    })
    if (!sanitized || sanitized.trim().length === 0) return null
    const doc = new DOMParser().parseFromString(sanitized, 'text/html')
    if (!doc.body.textContent?.trim()) return null
    return sanitized
  }, [quote?.terms_html])

  // Open the STORED issued PDF in a new tab — the exact frozen artifact that
  // was issued. Same popup-safe order as the protected QuoteTab: open a blank
  // tab synchronously in the click handler, fetch the blob (WITHOUT
  // credentials), then point the tab at the object URL; fall back to a
  // download only when the popup was blocked. Not offered once the link's
  // outcome is final or while an accept is in progress (public PDF access ends
  // when the quote is signed).
  async function handleOpenPdf() {
    if (pdfBusy || !token || finalRef.current || submitGuardRef.current) return
    setPdfError(null)
    const tab = window.open('', '_blank')
    setPdfBusy(true)
    try {
      const { blob, fileName } = await fetchPublicQuotePdf(token)
      if (!mountedRef.current) {
        tab?.close()
        return
      }
      const url = URL.createObjectURL(blob)
      if (tab && !tab.closed) {
        // Revoked on unmount only — revoking now could blank the loading tab.
        objectUrlsRef.current.push(url)
        tab.location.href = url
      } else if (tab === null) {
        // Popup blocked — fall back to a normal download. One-shot URL:
        // consumed by the click, revoke now.
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download = fileName ?? 'quotation.pdf'
        document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
        if (!finalRef.current) {
          setPdfError(
            'The PDF tab was blocked by the browser, so the PDF was downloaded instead.',
          )
        }
      } else {
        // The user closed the blank tab while the PDF was being fetched —
        // treat it as a cancel: no forced download, no error note.
        URL.revokeObjectURL(url)
      }
    } catch (err) {
      tab?.close()
      if (!mountedRef.current || finalRef.current) return
      // A 404/410 means the link died after the page rendered (cancelled /
      // replaced / expired mid-visit). The locked rule is ONE generic customer
      // message with no state-specific wording, so the backend's per-state
      // error text is never surfaced here; other failures get a neutral retry.
      const status = err instanceof ApiError ? err.status : 0
      setPdfError(
        status === 404 || status === 410
          ? DEAD_LINK_MESSAGE
          : 'Could not open the quote PDF. Please try again.',
      )
    } finally {
      if (mountedRef.current) setPdfBusy(false)
    }
  }

  function markAccepted(message: string) {
    finalRef.current = true
    setPdfError(null)
    setAcceptState({ kind: 'accepted', message })
  }

  function markDeadLink() {
    finalRef.current = true
    setPdfError(null)
    setState({ phase: 'dead' })
  }

  // One status re-read of this same token after an ambiguous accept failure (or
  // when the customer asks to check again). The submit guard stays HELD here; it
  // is released only when the link is confirmed still ACTIVE.
  //   INACTIVE           - the signature went through: the local confirmation.
  //   ACTIVE             - not consumed when re-read: keep the signature and
  //                        ticks, allow an explicit retry (never an automatic
  //                        repost). The wording does not claim the request was
  //                        lost: after a gateway timeout the first request can
  //                        still commit later, and a retry then gets its 410
  //                        message verbatim.
  //   EXPIRED / SUPERSEDED / CANCELLED, or 404 - the existing dead-link view; no
  //                        success claim, no signing retry.
  //   re-read failed     - state unknown: neutral "Check status" only.
  async function reconcileAcceptance() {
    if (reconcilingRef.current) return
    reconcilingRef.current = true
    setAcceptState({ kind: 'reconciling' })
    try {
      let view: PublicQuoteView
      try {
        view = await fetchPublicQuote(token)
      } catch (err) {
        if (!mountedRef.current) return
        if (err instanceof ApiError && err.status === 404) {
          markDeadLink()
          return
        }
        setAcceptState({ kind: 'unknown' })
        return
      }
      if (!mountedRef.current) return
      if (view.state === 'INACTIVE') {
        markAccepted(ACCEPTED_FALLBACK_MESSAGE)
        return
      }
      if (view.state === 'ACTIVE') {
        submitGuardRef.current = false
        setAcceptState({ kind: 'retry', message: NOT_CONFIRMED_MESSAGE })
        return
      }
      markDeadLink()
    } finally {
      reconcilingRef.current = false
    }
  }

  async function handleAccept() {
    // Synchronous guard first: nothing below may run twice for one tap.
    if (submitGuardRef.current || finalRef.current) return
    if (state.phase !== 'active') return
    if (!hasInk || !acceptanceChecked.every(Boolean)) return
    submitGuardRef.current = true
    setAcceptState({ kind: 'submitting' })
    setPdfError(null)
    let releaseGuard = true
    try {
      // Export the pad as a PNG inside the backend's safe-decode bounds
      // (8192 px per side, 4,000,000 px in total, 2,097,152 bytes). A failed
      // export is never submitted.
      let signature: Blob
      try {
        const raw = (await padRef.current?.toBlob()) ?? null
        signature = await toBoundedSignaturePng(raw)
      } catch {
        if (!mountedRef.current) return
        setAcceptState({ kind: 'retry', message: EXPORT_FAILED_MESSAGE })
        return
      }
      if (!mountedRef.current) return
      try {
        const res = await acceptPublicQuote(token, signature)
        if (!mountedRef.current) return
        releaseGuard = false
        markAccepted(
          res.message && res.message.length > 0
            ? res.message
            : ACCEPTED_FALLBACK_MESSAGE,
        )
      } catch (err) {
        if (!mountedRef.current) return
        const failure = classifyAcceptFailure(err)
        if (failure === 'ambiguous') {
          releaseGuard = false
          await reconcileAcceptance()
          return
        }
        // Backend messages verbatim (never the error code): the 410 link
        // messages and the public-safe 422 sentence are customer wording.
        const message =
          err instanceof ApiError && err.message.length > 0
            ? err.message
            : ACCEPT_FAILED_FALLBACK_MESSAGE
        if (failure === 'dead') {
          releaseGuard = false
          finalRef.current = true
          setAcceptState({ kind: 'link-dead', message })
          return
        }
        // Definite failure (e.g. a recoverable 422 or a 400): keep the
        // signature and ticks so the customer can try again.
        setAcceptState({ kind: 'retry', message })
      }
    } finally {
      if (releaseGuard) submitGuardRef.current = false
    }
  }

  function handleCheckStatus() {
    if (acceptState.kind !== 'unknown') return
    void reconcileAcceptance()
  }

  if (state.phase === 'loading') {
    return (
      <div className="min-h-screen bg-slate-100 flex items-center justify-center px-4">
        <p className="text-sm text-slate-500">Loading quote…</p>
      </div>
    )
  }

  if (state.phase === 'dead' || !quote) {
    return (
      <div className="min-h-screen bg-slate-100 flex items-center justify-center px-4">
        <div className="max-w-md w-full rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-8 text-center">
          <p className="text-sm text-slate-700">{DEAD_LINK_MESSAGE}</p>
        </div>
      </div>
    )
  }

  const accent = quote.accent_color ?? ACCENT_DEFAULT
  const kicker = flooringLabel(quote.flooring_type)
  const itemised = quote.itemised === true
  const lines = itemised && quote.lines ? quote.lines : []
  const showLogo = Boolean(quote.business_logo_url) && !logoFailed
  // Payment methods (the PDF's left money column): the direct-deposit block gates on the same
  // any-field rule as the PDF; the Pay-online button gates on the (already server-sanitised
  // HTTPS-only) Stripe link. Neither renders anything when unconfigured.
  const hasDirectDeposit = Boolean(
    quote.payment_account_name ||
      quote.payment_bank_name ||
      quote.payment_bsb ||
      quote.payment_account_number,
  )
  const payOnlineUrl = quote.payment_stripe_link_url
  const showPaymentMethods = hasDirectDeposit || payOnlineUrl !== null
  const totalRows: Array<{ label: string; value: number; strong: boolean }> = []
  if (quote.quote_total_ex_gst !== null) {
    totalRows.push({
      label: 'Subtotal (ex GST)',
      value: quote.quote_total_ex_gst,
      strong: false,
    })
  }
  if (quote.gst_amount !== null) {
    totalRows.push({ label: 'GST', value: quote.gst_amount, strong: false })
  }
  if (quote.quote_total_inc_gst !== null) {
    totalRows.push({
      label: 'Total (inc GST)',
      value: quote.quote_total_inc_gst,
      strong: true,
    })
  }

  const accepted = acceptState.kind === 'accepted'
  // Signing controls are live only while no accept is running and the outcome
  // is not final; the PDF action follows the same rule.
  const signingOpen = acceptState.kind === 'idle' || acceptState.kind === 'retry'
  const allChecked = acceptanceChecked.every(Boolean)
  const canAccept = signingOpen && hasInk && allChecked
  const showAcceptBar =
    signingOpen ||
    acceptState.kind === 'submitting' ||
    acceptState.kind === 'reconciling' ||
    acceptState.kind === 'unknown'

  return (
    <div className="min-h-screen bg-slate-100 print:bg-white">
      <div className="mx-auto max-w-[980px] px-3 py-4 sm:px-6 sm:py-8 print:max-w-none print:p-0">
        {/* Action bar - PDF anchored to the card's LEFT edge, Print to its RIGHT;
            above the document, never printed. The PDF action exists only while
            the link can still be signed (public PDF access ends once signed). */}
        <div className="mb-3 flex items-center justify-between gap-3 print:hidden">
          {signingOpen ? (
            <Button
              variant="secondary"
              onClick={() => void handleOpenPdf()}
              disabled={pdfBusy}
            >
              {pdfBusy ? 'Opening PDF…' : 'PDF'}
            </Button>
          ) : (
            <span />
          )}
          <Button variant="secondary" onClick={() => window.print()}>
            Print
          </Button>
        </div>
        {pdfError && signingOpen && (
          <p className="mb-3 text-left text-xs text-amber-700 print:hidden">
            {pdfError}
          </p>
        )}

        {/* THE DOCUMENT - the Invoice tab's document box with quotation wording. */}
        <article className="rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-6 sm:px-8 sm:py-8 lg:px-10 lg:py-8 print:rounded-none print:border-0 print:shadow-none">
          <header className="grid grid-cols-1 sm:grid-cols-3 gap-6 items-start">
            <div className="sm:col-span-1">
              {showLogo ? (
                <img
                  src={quote.business_logo_url ?? undefined}
                  alt={quote.business_name ?? 'Business logo'}
                  referrerPolicy="no-referrer"
                  className="h-auto w-auto max-h-16 max-w-[240px] object-contain"
                  onError={() => setLogoFailed(true)}
                />
              ) : (
                quote.business_name && (
                  <div className="text-base font-bold text-slate-900 tracking-tight">
                    {quote.business_name}
                  </div>
                )
              )}
              {quote.business_abn && (
                <div className="mt-1 text-xs tracking-wide text-slate-500">
                  ABN {quote.business_abn}
                </div>
              )}
            </div>
            <div className="hidden sm:block sm:col-span-1" />
            <div className="sm:col-span-1 sm:text-right">
              {kicker && (
                <div
                  className="text-[11px] font-bold uppercase tracking-widest"
                  style={{ color: accent }}
                >
                  {kicker}
                </div>
              )}
              <div className="text-3xl font-bold text-slate-900 tracking-tight">
                QUOTATION
              </div>
              {quote.order_number && (
                <div className="mt-1 text-sm font-mono text-slate-700">
                  {quote.order_number}
                </div>
              )}
            </div>
          </header>

          <div className="my-6 border-t border-slate-200" />

          {/* QUOTATION TO - the customer identity frozen at issue (V17). */}
          <div>
            <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
              Quotation To
            </div>
            {quote.customer_name && (
              <div className="mt-2 text-sm font-semibold text-slate-900 break-words">
                {quote.customer_name}
              </div>
            )}
            {(quote.customer_address_line1 || quote.customer_address_line2) && (
              <div className="mt-1 text-sm text-slate-700 leading-relaxed">
                {quote.customer_address_line1 && (
                  <div>{quote.customer_address_line1}</div>
                )}
                {quote.customer_address_line2 && (
                  <div>{quote.customer_address_line2}</div>
                )}
              </div>
            )}
          </div>

          {/* DETAILS OF SALE (hidden when blank, as on the PDF) */}
          {quote.details_of_sale && (
            <div className="mt-8">
              <div className="text-base font-semibold text-slate-900">
                Details Of Sale
              </div>
              <p className="mt-2 text-sm text-slate-700 leading-relaxed whitespace-pre-wrap break-words">
                {quote.details_of_sale}
              </p>
            </div>
          )}

          {/* ITEMISED: the frozen line table. ADJUSTMENT rows arrive with null
              qty/unit and a signed amount, rendered blank/signed like the PDF.
              NON-ITEMISED: no line table and no filler section. */}
          {itemised && (
            <div className="mt-8 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-[11px] uppercase tracking-wider text-slate-500">
                    <th className="py-2 pr-3 text-left font-semibold">
                      Description
                    </th>
                    <th className="py-2 px-3 text-right font-semibold">Qty</th>
                    <th className="py-2 px-3 text-right font-semibold">
                      Unit price (ex GST)
                    </th>
                    <th className="py-2 pl-3 text-right font-semibold">
                      Amount (ex GST)
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {lines.map((line, index) => (
                    <tr key={index} className="border-b border-slate-100 align-top">
                      <td className="py-2 pr-3 text-slate-800">
                        {line.description}
                      </td>
                      <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                        {line.quantity !== null
                          ? formatQuantity(line.quantity)
                          : ''}
                      </td>
                      <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                        {line.unit_price_ex_gst !== null
                          ? formatMoney(line.unit_price_ex_gst)
                          : ''}
                      </td>
                      <td className="py-2 pl-3 text-right tabular-nums text-slate-900">
                        {formatMoney(line.line_total_ex_gst)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* PAYMENT METHODS + TOTALS - direct deposit / Pay online on the left
              (whole block omitted when unconfigured), the invoice-style totals
              card on the right (no invoice-only Payment Made / Balance Due rows),
              then the display-only deposit sentence. */}
          <div
            className={`mt-8 flex flex-col gap-6 sm:flex-row ${
              showPaymentMethods ? 'sm:items-start sm:justify-between' : 'sm:justify-end'
            }`}
          >
            {showPaymentMethods && (
              <div className="sm:flex-1 sm:pr-8">
                {hasDirectDeposit && (
                  <div>
                    <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                      Payment Methods &#8212; Direct Deposit
                    </div>
                    <div className="mt-2 text-sm leading-relaxed text-slate-800">
                      {quote.payment_account_name && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            Account
                          </span>
                          <span>{quote.payment_account_name}</span>
                        </div>
                      )}
                      {quote.payment_bank_name && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            Bank
                          </span>
                          <span>{quote.payment_bank_name}</span>
                        </div>
                      )}
                      {quote.payment_bsb && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            BSB
                          </span>
                          <span>{quote.payment_bsb}</span>
                        </div>
                      )}
                      {quote.payment_account_number && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            Account No.
                          </span>
                          <span>{quote.payment_account_number}</span>
                        </div>
                      )}
                      {quote.order_number && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            Reference
                          </span>
                          <span className="font-mono">{quote.order_number}</span>
                        </div>
                      )}
                    </div>
                  </div>
                )}
                {/* Pay online — the tenant's Stripe payment link (server-sanitised to
                    HTTPS-only). Outline button themed with the document accent, never
                    printed; a plain anchor, no JS handler. */}
                {payOnlineUrl && (
                  <a
                    href={payOnlineUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="mt-3 inline-flex h-9 items-center justify-center rounded-lg border bg-white px-4 text-sm font-medium transition-colors hover:bg-[#0e7a70]/5 print:hidden"
                    style={{ borderColor: accent, color: accent }}
                  >
                    Pay online
                  </a>
                )}
              </div>
            )}
            <div className="w-full sm:w-[360px] sm:shrink-0">
              {totalRows.length > 0 && (
                <div className="rounded-md border border-slate-200">
                  {totalRows.map((row, index) => (
                    <div
                      key={row.label}
                      className={`flex items-center justify-between px-4 py-2.5 ${
                        index > 0 ? 'border-t border-slate-200' : ''
                      }`}
                    >
                      <span className="text-sm text-slate-700">{row.label}</span>
                      <span
                        className={`text-sm tabular-nums text-slate-900 ${
                          row.strong ? 'font-semibold' : 'font-medium'
                        }`}
                      >
                        {formatMoney(row.value)}
                      </span>
                    </div>
                  ))}
                </div>
              )}
              {/* DEPOSIT - display-only sentence, server-computed 40% (PDF parity). */}
              {quote.deposit_amount !== null && (
                <p className="mt-3 text-right text-sm font-semibold text-slate-900">
                  A deposit of {formatMoney(quote.deposit_amount)} is required to
                  proceed with this quotation.
                </p>
              )}
            </div>
          </div>

          <div className="my-8 border-t border-slate-200" />

          {/* TERMS - the FROZEN terms_snapshot (never live tenant terms). The PDF
              renders these on a dedicated page 2; on screen they come BEFORE the
              acceptance area so the customer sees them before signing, and in
              print they start on a fresh page. */}
          {sanitizedTermsHtml && (
            <div className="print:break-before-page">
              <div className="text-center text-xs font-semibold uppercase tracking-wider text-slate-800">
                Terms and Conditions applicable to this quotation
              </div>
              {/* `.invoice-terms` (index.css) is the app's generic sanitized-terms
                  styling - the same class the protected Quote/Invoice tabs use. */}
              <div
                className="invoice-terms mt-4 text-xs text-slate-700 leading-relaxed"
                dangerouslySetInnerHTML={{ __html: sanitizedTermsHtml }}
              />
            </div>
          )}

          {/* CUSTOMER ACCEPTANCE - the Invoice tab's composition, AFTER the terms:
              responsibility line + the two declarations + the agreement on the
              left, the signature on the right, the full-width Accept bar below. */}
          <section
            aria-label="Customer acceptance"
            className="mt-4 grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-5 sm:items-start"
          >
            <div>
              <p className="text-[11px] text-slate-600 leading-snug">
                Furniture removal and replacement, take up of old floor coverings,
                floor preparation and adjustment of door heights are the
                customer&rsquo;s responsibility unless otherwise stated above.
              </p>
              <div className="mt-3 space-y-1.5">
                {ACCEPTANCE_LINES.map((line, index) => (
                  <label
                    key={line}
                    className="flex items-start gap-2 text-[11px] text-slate-800 leading-snug"
                  >
                    <input
                      type="checkbox"
                      // Accepted: shown ticked and read-only. Otherwise real
                      // controls gating Accept (never sent to the backend).
                      checked={accepted || acceptanceChecked[index]}
                      disabled={!signingOpen}
                      onChange={(event) => {
                        if (!signingOpen) return
                        const checked = event.target.checked
                        setAcceptanceChecked((prev) => {
                          const next = [...prev]
                          next[index] = checked
                          return next
                        })
                      }}
                      className="mt-0.5 h-4 w-4 shrink-0 rounded border-slate-300 text-teal-600"
                    />
                    <span className="uppercase tracking-wide font-semibold">
                      {line}
                    </span>
                  </label>
                ))}
              </div>
              <p className="mt-3 text-xs font-bold uppercase tracking-wide text-slate-900 leading-snug">
                This agreement is for the sale and installation of the goods
                described above at the value shown on this quotation and upon the
                terms and conditions stated herein.
              </p>
              <p className="mt-2 text-xs font-bold uppercase tracking-wider text-slate-900">
                I accept the terms and conditions of this quotation.
              </p>
            </div>

            <div className="flex flex-col items-center">
              <div className="text-sm font-semibold text-slate-700">
                Customer Signature
              </div>
              {accepted ? (
                // Local confirmation from THIS submit session (the 201 message,
                // or the re-read that found the link signed). No signed document,
                // timestamp or server-side name is requested or shown.
                <div
                  role="status"
                  aria-live="polite"
                  className="mt-2 w-full max-w-[380px] rounded-md border border-teal-200 bg-teal-50 px-4 py-4 text-center"
                >
                  <p className="text-base font-semibold text-teal-800">
                    {acceptState.message}
                  </p>
                  <p className="mt-1 text-sm text-teal-700">
                    Thank you. This quote link is now inactive.
                  </p>
                </div>
              ) : acceptState.kind === 'link-dead' ? (
                <div
                  role="alert"
                  className="mt-2 w-full max-w-[380px] rounded-md border border-amber-200 bg-amber-50 px-4 py-4 text-center"
                >
                  <p className="text-sm font-medium text-amber-800">
                    {acceptState.message}
                  </p>
                </div>
              ) : (
                <div className="mt-2 w-full max-w-[380px]">
                  {/* Mouse, finger and pen (pointer events). The shallow-pad rule
                      is the Invoice tab's own. */}
                  <div className="invoice-signature-pad rounded-md border border-slate-200 bg-white px-1.5 pt-1.5 pb-1">
                    <SignaturePad
                      ref={padRef}
                      disabled={!signingOpen}
                      onInkChange={setHasInk}
                    />
                  </div>
                  <div className="mt-1 flex items-center justify-between gap-3">
                    <span className="min-w-0 break-words text-sm font-medium text-slate-800">
                      {quote.customer_name ?? ''}
                    </span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        if (signingOpen) padRef.current?.clear()
                      }}
                      disabled={!signingOpen || !hasInk}
                      className="print:hidden"
                    >
                      Clear signature
                    </Button>
                  </div>
                </div>
              )}
            </div>
          </section>

          {acceptState.kind === 'retry' && (
            <div
              role="alert"
              className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 print:hidden"
            >
              <p className="text-sm font-medium text-rose-700">
                {acceptState.message}
              </p>
            </div>
          )}

          {acceptState.kind === 'unknown' && (
            <div
              role="status"
              aria-live="polite"
              className="mt-4 flex flex-col gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 sm:flex-row sm:items-center sm:justify-between print:hidden"
            >
              <p className="text-sm font-medium text-amber-800">
                We could not confirm whether your acceptance was received. Please
                check the status before trying again.
              </p>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={handleCheckStatus}
              >
                Check status
              </Button>
            </div>
          )}

          {/* Full-width bottom action bar (the Invoice tab's Accept bar). */}
          {showAcceptBar && (
            <div className="mt-3 print:hidden">
              <button
                type="button"
                onClick={() => void handleAccept()}
                disabled={!canAccept}
                className="flex w-full items-center justify-center rounded-md bg-teal-600 px-6 py-3.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {acceptState.kind === 'submitting'
                  ? 'Accepting…'
                  : acceptState.kind === 'reconciling'
                    ? 'Checking…'
                    : 'Accept Quote'}
              </button>
              {signingOpen && !canAccept && (
                <p className="mt-2 text-center text-xs text-slate-500">
                  Tick both statements and sign above to accept this quotation.
                </p>
              )}
            </div>
          )}

          <div className="my-6 border-t border-slate-200" />

          {/* FOOTER — exactly once, with or without terms (the PDF rule). */}
          <footer className="text-center text-xs tracking-wide text-slate-500">
            Generated by {quote.business_name ?? 'the Flooring Sales Portal'}{' '}
            &#183; Quotation &#183; GST included where applicable
          </footer>
        </article>
      </div>
    </div>
  )
}
