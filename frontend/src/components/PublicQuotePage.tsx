import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import DOMPurify from 'dompurify'
import { Button } from './ui/Button'
import { ApiError } from '../lib/api/ApiError'
import {
  fetchPublicQuote,
  fetchPublicQuotePdf,
  markPublicQuoteViewed,
  type PublicQuoteView,
} from '../lib/api/publicQuoteApi'

// Phase 16E-C — the PUBLIC customer quote page at /q/{token}: top-level,
// slugless, no login, no store selection, no authenticated app shell. The
// secret token in the URL is the only credential; every request goes through
// publicQuoteApi.ts WITHOUT credentials (no session cookie is ever attached).
//
// READ-ONLY document view: it mirrors the QUOTATION PDF ("Aire Compact",
// templates/quote.html) as closely as web rendering allows — header (logo
// fail-soft to the business name | flooring kicker + QUOTATION + document
// number), Quotation To, Details of Sale, the itemised line table OR the
// non-itemised single-amount presentation, totals, the display-only deposit
// sentence, a STATIC Customer Acceptance block, the frozen Terms & Conditions,
// and the document footer. Everything renders from the backend's frozen issued
// snapshot payload — never from live draft/order state.
//
// The Customer Acceptance block is DOCUMENT CONTENT ONLY in 16E-C: the
// checkbox squares and signature line are static print-style elements with no
// inputs, no handlers, no signature capture and no accept call. Phase 16F
// makes this area live. There is deliberately no signing, no acceptance, no
// SMS and no invoice conversion anywhere on this page.
//
// Dead links: EVERY failure — dead state (EXPIRED / SUPERSEDED / CANCELLED /
// INACTIVE), unknown/malformed token (404), or any fetch error — shows exactly
// ONE generic message (locked 16E-C decision; no state-specific wording):
const DEAD_LINK_MESSAGE =
  'This quote link is no longer available. Please contact the store.'

// PDF palette (templates/quote.html) so the web document reads like the PDF.
const INK = '#34423d'
const INK_DARK = '#0f3d36'
const INK_MUTED = '#5d716d'
const ACCENT_DEFAULT = '#0e7a70'
const HAIR = '#e7ebe8'

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

export function PublicQuotePage() {
  const { token } = useParams<{ token: string }>()

  const [state, setState] = useState<LoadState>({ phase: 'loading' })
  const [logoFailed, setLogoFailed] = useState(false)
  const [pdfBusy, setPdfBusy] = useState(false)
  const [pdfError, setPdfError] = useState<string | null>(null)

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
  // (fire-and-forget: the backend stamps viewed_at write-once, so repeat
  // visits and StrictMode double-effects are harmless no-ops; a marking
  // failure never disturbs the already-rendered page). ANY other outcome —
  // dead state, 404, 410, network failure — is the one generic dead-link view.
  useEffect(() => {
    if (!token) {
      setState({ phase: 'dead' })
      return
    }
    let cancelled = false
    setState({ phase: 'loading' })
    fetchPublicQuote(token)
      .then((quote) => {
        if (cancelled) return
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
        if (!cancelled) setState({ phase: 'dead' })
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
  // top of the backend's issue-time sanitization — do not weaken.
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
  // was emailed. Same popup-safe order as the protected QuoteTab: open a blank
  // tab synchronously in the click handler, fetch the blob (WITHOUT
  // credentials), then point the tab at the object URL; fall back to a
  // download only when the popup was blocked.
  async function handleOpenPdf() {
    if (pdfBusy || !token) return
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
        setPdfError(
          'The PDF tab was blocked by the browser, so the PDF was downloaded instead.',
        )
      } else {
        // The user closed the blank tab while the PDF was being fetched —
        // treat it as a cancel: no forced download, no error note.
        URL.revokeObjectURL(url)
      }
    } catch (err) {
      tab?.close()
      if (!mountedRef.current) return
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

  return (
    <div className="min-h-screen bg-slate-100 print:bg-white">
      {/* ~932px document width on desktop (980 − 2×24 wrapper padding) — Invoice Simple scale. */}
      <div className="mx-auto max-w-[980px] px-3 py-4 sm:px-6 sm:py-8 print:max-w-none print:p-0">
        {/* Action bar — PDF anchored to the card's LEFT edge, Print to its RIGHT
            (Invoice Simple layout); simple, above the document, never printed. */}
        <div className="mb-3 flex items-center justify-between print:hidden">
          <Button
            variant="secondary"
            onClick={() => void handleOpenPdf()}
            disabled={pdfBusy}
          >
            {pdfBusy ? 'Opening PDF…' : 'PDF'}
          </Button>
          <Button variant="secondary" onClick={() => window.print()}>
            Print
          </Button>
        </div>
        {pdfError && (
          <p className="mb-3 text-left text-xs text-amber-700 print:hidden">
            {pdfError}
          </p>
        )}

        {/* THE DOCUMENT — mirrors templates/quote.html section by section. The soft
            two-layer shadow (tinted with the document ink) lifts the sheet off the
            grey canvas without a heavy border glow; print strips all card chrome. */}
        <article
          className="rounded-lg border border-slate-200 bg-white shadow-[0_1px_2px_rgba(15,61,54,0.06),0_16px_40px_-16px_rgba(15,61,54,0.22)] px-5 py-6 sm:px-10 sm:py-10 print:rounded-none print:border-0 print:shadow-none"
          style={{ color: INK }}
        >
          {/* HEADER: brand (logo fail-soft to business name) | document. */}
          <header className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div>
              {showLogo ? (
                <img
                  src={quote.business_logo_url ?? undefined}
                  alt={quote.business_name ?? 'Business logo'}
                  referrerPolicy="no-referrer"
                  className="h-auto w-auto max-h-14 max-w-[200px] object-contain"
                  onError={() => setLogoFailed(true)}
                />
              ) : (
                quote.business_name && (
                  <div
                    className="text-lg font-bold tracking-tight"
                    style={{ color: INK_DARK }}
                  >
                    {quote.business_name}
                  </div>
                )
              )}
              {/* ABN directly beneath the brand — the PDF's .abn line. */}
              {quote.business_abn && (
                <div
                  className="mt-1 text-xs tracking-wide"
                  style={{ color: INK_MUTED }}
                >
                  ABN {quote.business_abn}
                </div>
              )}
            </div>
            <div className="sm:text-right">
              {kicker && (
                <div
                  className="text-[11px] font-bold uppercase tracking-widest"
                  style={{ color: accent }}
                >
                  {kicker}
                </div>
              )}
              <div
                className="text-3xl font-bold tracking-tight"
                style={{ color: INK_DARK }}
              >
                QUOTATION
              </div>
              {quote.order_number && (
                <div className="mt-1 font-mono text-sm text-slate-600">
                  {quote.order_number}
                </div>
              )}
            </div>
          </header>

          <div className="mt-5 border-t-2" style={{ borderColor: accent }} />

          {/* QUOTATION TO */}
          <section className="mt-5">
            <div
              className="text-[10px] font-bold uppercase tracking-wider"
              style={{ color: accent }}
            >
              Quotation To
            </div>
            {quote.customer_name && (
              <div
                className="mt-1 text-base font-bold"
                style={{ color: INK_DARK }}
              >
                {quote.customer_name}
              </div>
            )}
            {quote.customer_address_line1 && (
              <div className="mt-0.5 text-sm">
                {quote.customer_address_line1}
              </div>
            )}
            {quote.customer_address_line2 && (
              <div className="mt-0.5 text-sm">
                {quote.customer_address_line2}
              </div>
            )}
          </section>

          <div className="my-5 border-t" style={{ borderColor: HAIR }} />

          {/* DETAILS OF SALE (hidden when blank) */}
          {quote.details_of_sale && (
            <>
              <section>
                <div
                  className="text-[10px] font-bold uppercase tracking-wider"
                  style={{ color: INK_MUTED }}
                >
                  Details of Sale
                </div>
                <div className="mt-2 whitespace-pre-wrap text-sm leading-relaxed">
                  {quote.details_of_sale}
                </div>
              </section>
              <div className="my-5 border-t" style={{ borderColor: HAIR }} />
            </>
          )}

          {/* ITEMISED: line table. ADJUSTMENT rows arrive with null qty/unit
              and a signed amount — rendered blank/signed exactly like the PDF.
              NON-ITEMISED: no line table and no filler section — the work is
              described in Details of Sale and the document flows straight into
              the single-amount totals presentation. */}
          {itemised && (
            <section className="overflow-x-auto">
              <table className="w-full border-collapse text-sm">
                <thead>
                  <tr
                    className="text-[10px] font-bold uppercase tracking-wider"
                    style={{ color: INK_MUTED }}
                  >
                    <th
                      className="border-b pb-2 text-left"
                      style={{ borderColor: '#cbd5d0' }}
                    >
                      Description
                    </th>
                    <th
                      className="border-b pb-2 pl-3 text-right"
                      style={{ borderColor: '#cbd5d0' }}
                    >
                      Qty
                    </th>
                    <th
                      className="border-b pb-2 pl-3 text-right"
                      style={{ borderColor: '#cbd5d0' }}
                    >
                      Unit (ex GST)
                    </th>
                    <th
                      className="border-b pb-2 pl-3 text-right"
                      style={{ borderColor: '#cbd5d0' }}
                    >
                      Amount (ex GST)
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {lines.map((line, index) => (
                    <tr key={index}>
                      <td
                        className="border-b py-2 pr-3 align-top"
                        style={{ borderColor: HAIR }}
                      >
                        {line.description}
                      </td>
                      <td
                        className="border-b py-2 pl-3 text-right align-top"
                        style={{ borderColor: HAIR }}
                      >
                        {line.quantity !== null
                          ? formatQuantity(line.quantity)
                          : ''}
                      </td>
                      <td
                        className="border-b py-2 pl-3 text-right align-top"
                        style={{ borderColor: HAIR }}
                      >
                        {line.unit_price_ex_gst !== null
                          ? formatMoney(line.unit_price_ex_gst)
                          : ''}
                      </td>
                      <td
                        className="border-b py-2 pl-3 text-right align-top"
                        style={{ borderColor: HAIR }}
                      >
                        {formatMoney(line.line_total_ex_gst)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}

          {/* PAYMENT METHODS + TOTALS — the PDF's two-column money row: direct
              deposit on the left (blank-hidden, whole block omitted when no
              payment config), totals right-anchored with clean rules, no box.
              When there is no payment config the totals keep the full row. */}
          <section
            className={`mt-4 flex flex-col gap-6 sm:flex-row ${
              showPaymentMethods ? 'sm:items-start sm:justify-between' : 'sm:justify-end'
            }`}
          >
            {showPaymentMethods && (
              <div className="sm:flex-1 sm:pr-8">
                {hasDirectDeposit && (
                  <div>
                    <div
                      className="text-[10px] font-bold uppercase tracking-wider"
                      style={{ color: INK_MUTED }}
                    >
                      Payment Methods &#8212; Direct Deposit
                    </div>
                    <div className="mt-2 text-sm leading-relaxed">
                      {quote.payment_account_name && (
                        <div>
                          <span
                            className="inline-block w-28"
                            style={{ color: INK_MUTED }}
                          >
                            Account
                          </span>
                          <span>{quote.payment_account_name}</span>
                        </div>
                      )}
                      {quote.payment_bank_name && (
                        <div>
                          <span
                            className="inline-block w-28"
                            style={{ color: INK_MUTED }}
                          >
                            Bank
                          </span>
                          <span>{quote.payment_bank_name}</span>
                        </div>
                      )}
                      {quote.payment_bsb && (
                        <div>
                          <span
                            className="inline-block w-28"
                            style={{ color: INK_MUTED }}
                          >
                            BSB
                          </span>
                          <span>{quote.payment_bsb}</span>
                        </div>
                      )}
                      {quote.payment_account_number && (
                        <div>
                          <span
                            className="inline-block w-28"
                            style={{ color: INK_MUTED }}
                          >
                            Account No.
                          </span>
                          <span>{quote.payment_account_number}</span>
                        </div>
                      )}
                      {quote.order_number && (
                        <div>
                          <span
                            className="inline-block w-28"
                            style={{ color: INK_MUTED }}
                          >
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
            <div className="w-full sm:w-72 sm:shrink-0">
              {quote.quote_total_ex_gst !== null && (
                <div
                  className="flex items-center justify-between border-b py-2 text-sm"
                  style={{ borderColor: HAIR }}
                >
                  <span>Subtotal (ex GST)</span>
                  <span className="font-bold" style={{ color: INK_DARK }}>
                    {formatMoney(quote.quote_total_ex_gst)}
                  </span>
                </div>
              )}
              {quote.gst_amount !== null && (
                <div
                  className="flex items-center justify-between border-b py-2 text-sm"
                  style={{ borderColor: HAIR }}
                >
                  <span>GST</span>
                  <span className="font-bold" style={{ color: INK_DARK }}>
                    {formatMoney(quote.gst_amount)}
                  </span>
                </div>
              )}
              {quote.quote_total_inc_gst !== null && (
                <div
                  className="mt-1 flex items-center justify-between border-t-2 pt-3 text-lg font-bold"
                  style={{ borderColor: INK_DARK, color: INK_DARK }}
                >
                  <span>Total (inc GST)</span>
                  <span>{formatMoney(quote.quote_total_inc_gst)}</span>
                </div>
              )}
            </div>
          </section>

          {/* DEPOSIT — display-only sentence, server-computed 40% (PDF parity). */}
          {quote.deposit_amount !== null && (
            <p
              className="mt-3 text-right text-sm font-bold"
              style={{ color: INK_DARK }}
            >
              A deposit of {formatMoney(quote.deposit_amount)} is required to
              proceed with this quotation.
            </p>
          )}

          <div className="my-5 border-t" style={{ borderColor: HAIR }} />

          {/* CUSTOMER ACCEPTANCE — STATIC document content only (16E-C). The
              squares and the signature line are print-style visuals: no
              inputs, no handlers, no signature capture, no accept call.
              Phase 16F makes this area live. Text mirrors the PDF verbatim. */}
          <section aria-label="Customer acceptance (specimen — not interactive)">
            <div
              className="text-[10px] font-bold uppercase tracking-wider"
              style={{ color: INK_MUTED }}
            >
              Customer Acceptance
            </div>
            <p className="mt-2 text-[13px] leading-relaxed">
              Furniture removal and replacement, take up of old floor
              coverings, floor preparation and adjustment of door heights are
              the customer&rsquo;s responsibility unless otherwise stated
              above.
            </p>
            <p
              className="mt-2 text-[13px] font-bold leading-relaxed"
              style={{ color: INK_DARK }}
            >
              This agreement is for the sale and installation of the goods
              described above at the value shown on this quotation and upon the
              terms and conditions stated herein.
            </p>
            <div className="mt-3 space-y-2">
              <div className="flex items-start gap-2">
                <span
                  aria-hidden="true"
                  className="mt-0.5 inline-block h-3 w-3 shrink-0 border"
                  style={{ borderColor: INK_MUTED }}
                />
                <span
                  className="text-[13px] font-bold"
                  style={{ color: INK_DARK }}
                >
                  I agree to pay the balance before the installation date.
                </span>
              </div>
              <div className="flex items-start gap-2">
                <span
                  aria-hidden="true"
                  className="mt-0.5 inline-block h-3 w-3 shrink-0 border"
                  style={{ borderColor: INK_MUTED }}
                />
                <span
                  className="text-[13px] font-bold"
                  style={{ color: INK_DARK }}
                >
                  I agree that no floor preparation costs are included unless
                  otherwise stated above.
                </span>
              </div>
            </div>
            {/* Signing strip: the h-14 pen space above the rule (plus the row's top
                margin) gives the signature ~56px of air below the checkboxes — a
                signing area like the PDF, not a void. sm:items-end keeps the accept
                sentence baseline-aligned with the signature rule (the PDF idiom). */}
            <div className="mt-8 flex flex-col gap-6 sm:flex-row sm:items-end sm:justify-between">
              <p
                className="text-[13px] font-bold sm:max-w-[50%]"
                style={{ color: INK_DARK }}
              >
                I accept the terms and conditions of this quotation.
              </p>
              <div className="w-full sm:w-64">
                <div
                  className="h-14 border-b"
                  style={{ borderColor: '#aebab3' }}
                />
                <div className="mt-1 text-xs" style={{ color: INK_MUTED }}>
                  Customer signature
                </div>
              </div>
            </div>
          </section>

          <div className="my-5 border-t" style={{ borderColor: HAIR }} />

          {/* TERMS — the FROZEN terms_snapshot (never live tenant terms). The
              PDF renders these on a dedicated page 2; on screen they follow
              the document, and in print they start on a fresh page. */}
          {sanitizedTermsHtml && (
            <>
              <section className="print:break-before-page">
                <div
                  className="text-center text-[10px] font-bold uppercase tracking-wider"
                  style={{ color: INK_MUTED }}
                >
                  Terms &amp; Conditions of this Quotation
                </div>
                {/* `.invoice-terms` (index.css) is the app's generic sanitized-terms
                    styling — the same class the protected Quote/Invoice tabs use. */}
                <div
                  className="invoice-terms mt-3 text-xs leading-relaxed"
                  style={{ color: '#3c4a44' }}
                  dangerouslySetInnerHTML={{ __html: sanitizedTermsHtml }}
                />
              </section>
              <div className="my-5 border-t" style={{ borderColor: HAIR }} />
            </>
          )}

          {/* FOOTER — exactly once, with or without terms (the PDF rule). */}
          <footer
            className="text-center text-xs tracking-wide"
            style={{ color: INK_MUTED }}
          >
            Generated by {quote.business_name ?? 'the Flooring Sales Portal'}{' '}
            &#183; Quotation &#183; GST included where applicable
          </footer>
        </article>
      </div>
    </div>
  )
}
