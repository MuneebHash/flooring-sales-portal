import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import DOMPurify from 'dompurify'
import { Button } from './ui/Button'
import { SignaturePad, type SignaturePadHandle } from './workspace/SignaturePad'
import { ApiError } from '../lib/api/ApiError'
import {
  acceptPublicInvoice,
  fetchPublicInvoice,
  fetchPublicInvoicePdf,
  type PublicInvoiceView,
} from '../lib/api/publicInvoiceApi'
import { toBoundedSignaturePng } from '../lib/signaturePng'

// Phase 16G PR 5 - the PUBLIC customer invoice page at /i/{token} (16G contract
// section 9.6, UX lock section 20.3): top-level, slugless, no login, no store
// selection, no authenticated app shell and no auth state. The secret token in
// the URL is the only credential; every request goes through publicInvoiceApi.ts
// WITHOUT credentials (no session cookie is ever attached).
//
// LAYOUT: the public quote page's document box with the Invoice tab's invoice
// wording: header (logo fail-soft to the business name, ABN | flooring kicker,
// TAX INVOICE + order number), Invoice To (the version's frozen name and billing
// lines) beside the two fixed invoice sentences and the dates, Details of Sale,
// payment methods + the five total rows, the stored terms, then the customer
// acceptance area and the footer. Everything renders from the payload of the
// order's CURRENT invoice version, never from live order, customer, address or
// Products & Charges data. There is no deposit sentence and no line table: the
// invoice is details plus totals.
//
// SIGNING: the two Invoice tab declarations are a frontend-only gate (nothing is
// sent for them) and, with real ink on the pad, enable Accept Invoice, which posts
// expected_version_number (the version on screen) and the signature image only.
// One synchronous gate (flowRef) lets exactly one flow run at a time - an accept
// (export, POST and any status re-read), a Preview PDF (fetch and any stale
// re-read) or a Check status read - so no result ever lands under another flow,
// and no late result can reopen actions after a final outcome. The page never
// re-posts on its own and never infers its own success from a re-read: only the
// documented 201 is a local success.
//
// DEAD LINKS (16G PR 5, #126): a dead state on load shows only its own
// data.message (replaced, rewritten, signed or expired) and a 404 shows the
// backend message ("Invoice not found."); a 404 or 410 from Preview PDF or
// Accept shows that response's message. Each is a terminal view with no invoice
// content, signature or action. Only a load that gets no readable answer shows
// the generic sentence, with a retry. There is no view-only mode after signing:
// the customer receives the signed PDF by email.
const LOAD_FAILED_MESSAGE = 'We could not load this invoice. Please try again.'
const INVOICE_NOT_FOUND_MESSAGE = 'Invoice not found.'
// Defensive fallback only, for a dead-link answer that carries no readable
// message (the backend always sends one). A known state shows its own message.
const LINK_UNAVAILABLE_MESSAGE =
  'This invoice link is no longer available. Please contact the store.'

const ACCEPTED_FALLBACK_MESSAGE = 'Invoice accepted.'
const ACCEPT_FAILED_FALLBACK_MESSAGE =
  'This invoice could not be accepted. Please try again.'
const NOT_CONFIRMED_MESSAGE =
  'We could not confirm your acceptance. Your signature is still here, so you can try again.'
const EXPORT_FAILED_MESSAGE =
  'Your signature could not be prepared for sending. Please clear it and sign again. If this keeps happening, reset the page zoom to 100% and try again.'
// The stale message: the default for a 409 that carries no message (a 409 with
// a message shows it verbatim), and the text shown when an ambiguous accept's
// re-read finds another version.
const INVOICE_STALE_MESSAGE = 'Invoice updated, please refresh'
const PDF_FAILED_MESSAGE = 'Could not open the invoice PDF. Please try again.'
const PDF_BLOCKED_MESSAGE =
  'The preview tab was blocked by the browser, so the PDF was downloaded instead.'

// The Invoice tab's declaration wording (InvoiceTab ACCEPTANCE_LINES), verbatim.
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

// The Invoice tab's document date: YYYY-MM-DD -> DD/MM/YYYY.
function formatDocDate(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso)
  if (!match) return ''
  const [, year, month, day] = match
  return `${day}/${month}/${year}`
}

function flooringLabel(flooringType: string | null): string | null {
  if (flooringType === 'SOFT') return 'Soft Flooring'
  if (flooringType === 'HARD') return 'Hard Flooring'
  return null
}

// The trimmed value when it has visible content, otherwise null (the Invoice
// tab's helper): a blank frozen line is simply not shown.
function nonBlank(value: string | null | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed && trimmed.length > 0 ? trimmed : null
}

type LoadState =
  | { phase: 'loading' }
  | { phase: 'load-failed' }
  | { phase: 'dead'; message: string }
  | { phase: 'active'; invoice: PublicInvoiceView }

// The synchronous gate shared by Accept, Preview PDF and Check status.
//   open    - no flow is running: Accept and Preview PDF may start.
//   busy    - an accept, a preview or a status read is running: nothing else may
//             start until it ends.
//   unknown - an outcome could not be read: only Check status may start.
//   final   - accepted locally, or the link is dead: nothing may start.
type Flow = 'open' | 'busy' | 'unknown' | 'final'

// What a status re-read of the link resolves (see readLinkStatus).
//   ambiguous - an accept got no readable answer; versionNumber is the version
//               that was submitted (the one on screen).
//   stale     - a 409 INVOICE_VERSION_STALE; message is the one to show, place
//               where to show it ('pdf' beside Preview PDF, 'accept' beside the
//               Accept bar). The draft was already dropped.
type StatusRead =
  | { reason: 'ambiguous'; versionNumber: number }
  | { reason: 'stale'; message: string; place: 'pdf' | 'accept' }

// The accept flow on an ACTIVE page.
//   idle        - signing open.
//   submitting  - exporting the signature / accept POST in flight.
//   reconciling - re-reading the link (after an ambiguous or stale answer, or on
//                 Check status).
//   retry       - signing open with a message: a definite refusal or a failed
//                 export (ink and ticks kept), the same version still ACTIVE
//                 after an ambiguous answer (ink and ticks kept), or a stale
//                 version replaced (ink and ticks dropped).
//   unknown     - the re-read failed: no Accept and no success claim; only
//                 "Check status".
//   accepted    - the local confirmation for this submit session.
type AcceptState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'reconciling' }
  | { kind: 'retry'; message: string }
  | { kind: 'unknown'; read: StatusRead }
  | { kind: 'accepted'; message: string }

// No readable answer from the invoice service: a network failure (status 0), a
// 2xx whose body is not the documented result, or a gateway/timeout status
// (502 / 503 / 504; the endpoint never returns these itself). The acceptance MAY
// have gone through.
function isNoReadableAnswer(err: ApiError): boolean {
  return (
    err.status === 0 ||
    (err.status >= 200 && err.status < 300) ||
    err.status === 502 ||
    err.status === 503 ||
    err.status === 504
  )
}

// A stale answer from the accept or the PDF read: 409 INVOICE_VERSION_STALE, the
// only 409 those endpoints return (contract sections 9.4 and 9.5), so a 409
// whose body could not be read (no code) is treated as stale too.
function isStaleAnswer(err: ApiError): boolean {
  return (
    err.status === 409 &&
    (err.code === null || err.code === 'INVOICE_VERSION_STALE')
  )
}

// The backend's own message, verbatim, or the given fallback when the answer
// carried none (publicInvoiceApi leaves the message empty then, never a generic
// client text).
function backendMessage(err: ApiError, fallback: string): string {
  return err.message.trim().length > 0 ? err.message : fallback
}

// The message of a 404 / 410 answer.
function deadErrorMessage(err: ApiError): string {
  return backendMessage(
    err,
    err.status === 404 ? INVOICE_NOT_FOUND_MESSAGE : LINK_UNAVAILABLE_MESSAGE,
  )
}

// The message of a dead state's 200 payload.
function deadViewMessage(view: PublicInvoiceView): string {
  return nonBlank(view.message) ?? LINK_UNAVAILABLE_MESSAGE
}

// The page is keyed by token: a different token remounts everything, so no
// tick, ink, in-flight request, outcome or object URL can leak across links.
export function PublicInvoicePage() {
  const { token } = useParams<{ token: string }>()
  return <PublicInvoiceDocument key={token ?? ''} token={token ?? ''} />
}

function PublicInvoiceDocument({ token }: { token: string }) {
  const [state, setState] = useState<LoadState>({ phase: 'loading' })
  // Bumped by "Try again" after a load that got no readable answer.
  const [loadAttempt, setLoadAttempt] = useState(0)
  const [logoFailed, setLogoFailed] = useState(false)
  const [pdfBusy, setPdfBusy] = useState(false)
  // A note beside Preview PDF: a failure, the blocked-popup download, or the
  // stale message after a preview found a newer version.
  const [pdfNote, setPdfNote] = useState<string | null>(null)

  // Signing state (frontend gate: both declarations ticked + real ink).
  const [acceptanceChecked, setAcceptanceChecked] = useState<boolean[]>(() =>
    ACCEPTANCE_LINES.map(() => false),
  )
  const [hasInk, setHasInk] = useState(false)
  const [acceptState, setAcceptState] = useState<AcceptState>({ kind: 'idle' })
  const padRef = useRef<SignaturePadHandle | null>(null)

  // The synchronous flow gate (see Flow): checked and taken BEFORE any await, so
  // a double tap starts one flow and no two flows ever overlap. A flow sets
  // 'unknown' or 'final' itself; otherwise it returns the gate to 'open'.
  const flowRef = useRef<Flow>('open')

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

  // Load the invoice. ACTIVE -> the document. A dead state -> its own message,
  // final for this token (a late answer can never restore ACTIVE content); a 404
  // -> the backend message, also final. No readable answer (a network failure,
  // an unreadable body or any other status) -> the generic sentence with
  // "Try again", which reruns this load. There is no viewed tracking.
  useEffect(() => {
    if (!token) {
      flowRef.current = 'final'
      setState({ phase: 'dead', message: INVOICE_NOT_FOUND_MESSAGE })
      return
    }
    let cancelled = false
    setState({ phase: 'loading' })
    fetchPublicInvoice(token)
      .then((view) => {
        if (cancelled || flowRef.current === 'final') return
        if (view.state === 'ACTIVE') {
          setState({ phase: 'active', invoice: view })
        } else {
          flowRef.current = 'final'
          setState({ phase: 'dead', message: deadViewMessage(view) })
        }
      })
      .catch((err: unknown) => {
        if (cancelled || flowRef.current === 'final') return
        if (err instanceof ApiError && err.status === 404) {
          flowRef.current = 'final'
          setState({ phase: 'dead', message: deadErrorMessage(err) })
          return
        }
        setState({ phase: 'load-failed' })
      })
    return () => {
      cancelled = true
    }
  }, [token, loadAttempt])

  const invoice = state.phase === 'active' ? state.invoice : null

  // A fresh logo URL is a fresh chance to load (the InvoiceTab fail-soft rule).
  useEffect(() => {
    setLogoFailed(false)
  }, [invoice?.business_logo_url])

  // Browser-tab / print-dialog title: "Invoice {order_number}" for the document,
  // otherwise "Invoice" (loading, load failure, and every dead view, whose payload
  // carries no order number), so a link that dies mid-visit drops the number.
  const titleOrderNumber = invoice !== null ? nonBlank(invoice.order_number) : null
  const documentTitle =
    titleOrderNumber !== null ? `Invoice ${titleOrderNumber}` : 'Invoice'
  useEffect(() => {
    document.title = documentTitle
  }, [documentTitle])

  // Stored terms, re-sanitized client-side with the IDENTICAL DOMPurify config
  // the public quote page and the protected QuoteTab/InvoiceTab use (restricted
  // allowlist, ALL attributes stripped; a textless fragment hides the whole
  // section). Defence in depth on top of the backend's sanitization when the
  // terms were frozen - do not weaken. A null/blank value legitimately hides the
  // section (no terms on this version), never a fallback.
  const sanitizedTermsHtml = useMemo(() => {
    const termsText = invoice?.terms_html
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
  }, [invoice?.terms_html])

  // The terminal dead view: the message only, with no invoice content,
  // signature or action, and nothing may start after it.
  function enterDead(message: string) {
    flowRef.current = 'final'
    setPdfNote(null)
    setState({ phase: 'dead', message })
  }

  function markAccepted(message: string) {
    flowRef.current = 'final'
    setPdfNote(null)
    setAcceptState({ kind: 'accepted', message })
  }

  // Drop the signing draft of a version known to be stale: the ink and both
  // ticks. A no-op on the pad when it is not mounted.
  function invalidateDraft() {
    padRef.current?.clear()
    setHasInk(false)
    setAcceptanceChecked(ACCEPTANCE_LINES.map(() => false))
  }

  // One status re-read of this same token, never a repost. The caller holds the
  // gate ('busy'); this sets it to 'open', 'unknown' or 'final'.
  //   ACTIVE, same version (ambiguous) - the signature went nowhere we can see:
  //                     keep the ink and ticks and allow an explicit retry. The
  //                     wording does not claim the request was lost: after a
  //                     gateway timeout the first request can still commit, and
  //                     a retry then gets its 410 message.
  //   ACTIVE otherwise  - the stale handling with the view from THIS read: drop the
  //                     draft (again, so nothing drawn meanwhile survives), show
  //                     the current version and the stale message; a fresh
  //                     review, ticks and signature are needed.
  //   dead state        - SIGNED included: that state's message. Never a local
  //                     success: only the documented 201 establishes that.
  //   404               - the not-found message.
  //   no readable answer - the status is unknown: only "Check status". An
  //                     ambiguous attempt keeps its ink and ticks for a later
  //                     same-version read; a stale draft stays dropped.
  async function readLinkStatus(read: StatusRead): Promise<void> {
    let view: PublicInvoiceView
    try {
      view = await fetchPublicInvoice(token)
    } catch (err) {
      if (!mountedRef.current) return
      if (err instanceof ApiError && err.status === 404) {
        enterDead(deadErrorMessage(err))
        return
      }
      flowRef.current = 'unknown'
      setAcceptState({ kind: 'unknown', read })
      return
    }
    if (!mountedRef.current) return
    if (view.state !== 'ACTIVE') {
      enterDead(deadViewMessage(view))
      return
    }
    if (
      read.reason === 'ambiguous' &&
      view.version_number === read.versionNumber
    ) {
      flowRef.current = 'open'
      setAcceptState({ kind: 'retry', message: NOT_CONFIRMED_MESSAGE })
      return
    }
    const message =
      read.reason === 'stale' ? read.message : INVOICE_STALE_MESSAGE
    const place = read.reason === 'stale' ? read.place : 'accept'
    invalidateDraft()
    setState({ phase: 'active', invoice: view })
    if (place === 'pdf') {
      setPdfNote(message)
      setAcceptState({ kind: 'idle' })
    } else {
      setPdfNote(null)
      setAcceptState({ kind: 'retry', message })
    }
    flowRef.current = 'open'
  }

  // Open the STORED PDF of the version on screen in a new tab (never another
  // version, never regenerated). Popup-safe order (UX lock section 11): open a
  // blank tab synchronously in the click handler, fetch the blob WITHOUT
  // credentials, then point the tab at the object URL; download instead only when
  // the popup was blocked. Not offered after a final outcome or while an accept,
  // its reconciliation or an unknown status is pending; the gate also keeps
  // Accept from starting while the preview runs.
  async function handlePreviewPdf() {
    if (flowRef.current !== 'open' || state.phase !== 'active') return
    const versionNumber = state.invoice.version_number
    if (versionNumber === null) return
    flowRef.current = 'busy'
    setPdfNote(null)
    setPdfBusy(true)
    let tab: Window | null = null
    try {
      // Opened synchronously, before the first await (inside the try only so an
      // exception here still releases the gate). tab === null right here means a
      // popup blocker intervened; a tab that exists now but is CLOSED by the time
      // the blob is ready means the customer closed it deliberately (a cancel: no
      // download, no note).
      tab = window.open('', '_blank')
      const { blob, fileName } = await fetchPublicInvoicePdf(
        token,
        versionNumber,
      )
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
        anchor.download = fileName ?? 'invoice.pdf'
        document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
        setPdfNote(PDF_BLOCKED_MESSAGE)
      } else {
        URL.revokeObjectURL(url)
      }
    } catch (err) {
      tab?.close()
      if (!mountedRef.current) return
      if (err instanceof ApiError && isStaleAnswer(err)) {
        // Another version is current: the stale handling. Drop the draft FIRST,
        // so it is unusable even if the re-read below fails, then re-read once.
        const message = backendMessage(err, INVOICE_STALE_MESSAGE)
        invalidateDraft()
        setPdfNote(message)
        setAcceptState({ kind: 'idle' })
        await readLinkStatus({ reason: 'stale', message, place: 'pdf' })
        return
      }
      if (err instanceof ApiError && (err.status === 404 || err.status === 410)) {
        // The link died after the page rendered: its message, signing ends.
        enterDead(deadErrorMessage(err))
        return
      }
      setPdfNote(PDF_FAILED_MESSAGE)
    } finally {
      if (flowRef.current === 'busy') flowRef.current = 'open'
      if (mountedRef.current) setPdfBusy(false)
    }
  }

  async function handleAccept() {
    // Synchronous gate first: nothing below may run twice for one tap, and no
    // accept may start under another flow.
    if (flowRef.current !== 'open' || state.phase !== 'active') return
    if (acceptState.kind !== 'idle' && acceptState.kind !== 'retry') return
    if (!hasInk || !acceptanceChecked.every(Boolean)) return
    // The version the customer reviewed and signed: the POST carries ITS number,
    // and an ambiguous answer is reconciled against it.
    const versionNumber = state.invoice.version_number
    if (versionNumber === null) return
    flowRef.current = 'busy'
    setAcceptState({ kind: 'submitting' })
    setPdfNote(null)
    try {
      // Export the pad as a PNG inside the backend's safe-decode bounds (8192 px
      // per side, 4,000,000 px in total, 2,097,152 bytes). A failed export is
      // never submitted; the ink and ticks stay.
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
        const res = await acceptPublicInvoice(token, versionNumber, signature)
        if (!mountedRef.current) return
        // A LOCAL confirmation (no re-read: the link is now dead). The response
        // does not report the email, so nothing here claims one was sent.
        markAccepted(nonBlank(res.message) ?? ACCEPTED_FALLBACK_MESSAGE)
      } catch (err) {
        if (!mountedRef.current) return
        if (!(err instanceof ApiError) || isNoReadableAnswer(err)) {
          setAcceptState({ kind: 'reconciling' })
          await readLinkStatus({ reason: 'ambiguous', versionNumber })
          return
        }
        if (isStaleAnswer(err)) {
          // Another version is current: drop the draft FIRST (so a failed
          // re-read cannot leave it submittable), then re-read once. Never retry.
          const message = backendMessage(err, INVOICE_STALE_MESSAGE)
          invalidateDraft()
          setAcceptState({ kind: 'reconciling' })
          await readLinkStatus({ reason: 'stale', message, place: 'accept' })
          return
        }
        if (err.status === 404 || err.status === 410) {
          enterDead(deadErrorMessage(err))
          return
        }
        // A definite refusal (422, 400, 415, 500, ...): the backend message
        // verbatim (never the code); the ink and ticks stay so the customer can
        // try again.
        setAcceptState({
          kind: 'retry',
          message: backendMessage(err, ACCEPT_FAILED_FALLBACK_MESSAGE),
        })
      }
    } finally {
      if (flowRef.current === 'busy') flowRef.current = 'open'
    }
  }

  // One guarded read of the link while its status is unknown - never a repost.
  async function handleCheckStatus() {
    if (flowRef.current !== 'unknown' || acceptState.kind !== 'unknown') return
    // The customer is beside the Accept bar now, so a stale message shows there
    // (and leaves the note under the action bar, so it is never shown twice).
    const read: StatusRead =
      acceptState.read.reason === 'stale'
        ? { ...acceptState.read, place: 'accept' }
        : acceptState.read
    flowRef.current = 'busy'
    setPdfNote(null)
    setAcceptState({ kind: 'reconciling' })
    try {
      await readLinkStatus(read)
    } finally {
      if (flowRef.current === 'busy') flowRef.current = 'open'
    }
  }

  function handleRetryLoad() {
    if (state.phase !== 'load-failed') return
    setLoadAttempt((attempt) => attempt + 1)
  }

  if (state.phase === 'loading') {
    return (
      <div className="min-h-screen bg-slate-100 flex items-center justify-center px-4">
        <p className="text-sm text-slate-500">Loading invoice…</p>
      </div>
    )
  }

  if (state.phase === 'load-failed') {
    return (
      <div className="min-h-screen bg-slate-100 flex items-center justify-center px-4">
        <div className="max-w-md w-full rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-8 text-center">
          <p className="text-sm text-slate-700">{LOAD_FAILED_MESSAGE}</p>
          <div className="mt-4">
            <Button type="button" variant="secondary" onClick={handleRetryLoad}>
              Try again
            </Button>
          </div>
        </div>
      </div>
    )
  }

  if (state.phase === 'dead') {
    return (
      <div className="min-h-screen bg-slate-100 flex items-center justify-center px-4">
        <div className="max-w-md w-full rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-8 text-center">
          <p className="text-sm text-slate-700">{state.message}</p>
        </div>
      </div>
    )
  }

  const view = state.invoice
  const accent = view.accent_color ?? ACCENT_DEFAULT
  const kicker = flooringLabel(view.flooring_type)
  const showLogo = Boolean(view.business_logo_url) && !logoFailed
  const businessName = nonBlank(view.business_name)
  const orderNumber = nonBlank(view.order_number)
  // Invoice To: the version's FROZEN words only; a blank name or line is simply
  // not shown (no live customer or address fallback).
  const invoiceToName = nonBlank(view.customer_name)
  const invoiceToLine1 = nonBlank(view.customer_address_line1)
  const invoiceToLine2 = nonBlank(view.customer_address_line2)
  const detailsOfSale = nonBlank(view.details_of_sale) ? view.details_of_sale : null
  const invoiceDateText = view.invoice_date ? formatDocDate(view.invoice_date) : ''
  const dueDateText = view.due_date ? formatDocDate(view.due_date) : ''
  // Payment methods (the public quote page's block): direct deposit when any bank
  // field is present; Pay online on the (server-sanitised, HTTPS-only) Stripe
  // link. Neither renders anything when unconfigured.
  const hasDirectDeposit = Boolean(
    view.payment_account_name ||
      view.payment_bank_name ||
      view.payment_bsb ||
      view.payment_account_number,
  )
  const payOnlineUrl = nonBlank(view.payment_stripe_link_url)
  const showPaymentMethods = hasDirectDeposit || payOnlineUrl !== null
  const totalRows: Array<{ label: string; value: number; strong: boolean }> = []
  if (view.sale_price_ex_gst !== null) {
    totalRows.push({
      label: 'Subtotal (ex GST)',
      value: view.sale_price_ex_gst,
      strong: false,
    })
  }
  if (view.gst_amount !== null) {
    totalRows.push({ label: 'GST', value: view.gst_amount, strong: false })
  }
  if (view.sale_price_inc_gst !== null) {
    totalRows.push({
      label: 'Total (inc GST)',
      value: view.sale_price_inc_gst,
      strong: true,
    })
  }
  if (view.total_paid !== null) {
    totalRows.push({
      label: 'Payment Made',
      value: view.total_paid,
      strong: false,
    })
  }
  if (view.balance_due !== null) {
    totalRows.push({
      label: 'Balance Due',
      value: view.balance_due,
      strong: true,
    })
  }

  const accepted = acceptState.kind === 'accepted'
  // Signing is open (Preview PDF offered, signing controls live) only while no
  // accept is running and the outcome is neither final nor unknown. The draft is
  // also frozen while a preview runs, since a stale preview drops it.
  const signingOpen = acceptState.kind === 'idle' || acceptState.kind === 'retry'
  const draftEditable = signingOpen && !pdfBusy
  const allChecked = acceptanceChecked.every(Boolean)
  const canAccept = draftEditable && hasInk && allChecked

  return (
    <div className="min-h-screen bg-slate-100 print:bg-white">
      <div className="mx-auto max-w-[980px] px-3 py-4 sm:px-6 sm:py-8 print:max-w-none print:p-0">
        {/* Action bar - Preview PDF anchored to the card's LEFT edge, Print to its
            RIGHT; above the document, never printed. Preview PDF exists only while
            the invoice can still be signed. */}
        <div className="mb-3 flex items-center justify-between gap-3 print:hidden">
          {signingOpen ? (
            <Button
              variant="secondary"
              onClick={() => void handlePreviewPdf()}
              disabled={pdfBusy}
            >
              {pdfBusy ? 'Opening PDF…' : 'Preview PDF'}
            </Button>
          ) : (
            <span />
          )}
          <Button variant="secondary" onClick={() => window.print()}>
            Print
          </Button>
        </div>
        {pdfNote !== null && (
          <p className="mb-3 text-left text-xs text-amber-700 print:hidden">
            {pdfNote}
          </p>
        )}

        {/* THE DOCUMENT - the public quote page's document box with the Invoice
            tab's invoice wording. */}
        <article className="rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-6 sm:px-8 sm:py-8 lg:px-10 lg:py-8 print:rounded-none print:border-0 print:shadow-none">
          <header className="grid grid-cols-1 sm:grid-cols-3 gap-6 items-start">
            <div className="sm:col-span-1">
              {showLogo ? (
                <img
                  src={view.business_logo_url ?? undefined}
                  alt={businessName ?? 'Business logo'}
                  referrerPolicy="no-referrer"
                  className="h-auto w-auto max-h-16 max-w-[240px] object-contain"
                  onError={() => setLogoFailed(true)}
                />
              ) : (
                businessName && (
                  <div className="text-base font-bold text-slate-900 tracking-tight">
                    {businessName}
                  </div>
                )
              )}
              {view.business_abn && (
                <div className="mt-1 text-xs tracking-wide text-slate-500">
                  ABN {view.business_abn}
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
                TAX INVOICE
              </div>
              {orderNumber && (
                <div className="mt-1 text-sm font-mono text-slate-700">
                  {orderNumber}
                </div>
              )}
            </div>
          </header>

          <div className="my-6 border-t border-slate-200" />

          {/* INVOICE TO - the words frozen on this version - beside the Invoice
              tab's two fixed sentences and the dates. */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
            <div>
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Invoice To
              </div>
              {invoiceToName !== null && (
                <div className="mt-2 text-sm font-semibold text-slate-900 break-words">
                  {invoiceToName}
                </div>
              )}
              {(invoiceToLine1 !== null || invoiceToLine2 !== null) && (
                <div
                  className={`${
                    invoiceToName !== null ? 'mt-1' : 'mt-2'
                  } text-sm text-slate-700 leading-relaxed`}
                >
                  {invoiceToLine1 !== null && <div>{invoiceToLine1}</div>}
                  {invoiceToLine2 !== null && <div>{invoiceToLine2}</div>}
                </div>
              )}
            </div>
            <div className="sm:text-right space-y-1.5">
              <p className="text-sm text-slate-700">
                This is your only invoice. Pay as agreed herein.
              </p>
              <p className="text-sm font-semibold text-slate-900 uppercase tracking-wide">
                Customer to confirm lay date with the store before
                installation.
              </p>
              <div className="pt-2 text-sm text-slate-600">
                Invoice Date :{' '}
                <span className="text-slate-900 tabular-nums">
                  {invoiceDateText || '—'}
                </span>
              </div>
              <div className="text-sm text-slate-600">
                Due Date :{' '}
                <span className="text-slate-900 tabular-nums">
                  {dueDateText || '—'}
                </span>
              </div>
            </div>
          </div>

          {/* DETAILS OF SALE - this version's own text, hidden when blank. */}
          {detailsOfSale !== null && (
            <div className="mt-8">
              <div className="text-base font-semibold text-slate-900">
                Details Of Sale
              </div>
              <p className="mt-2 text-sm text-slate-700 leading-relaxed whitespace-pre-wrap break-words">
                {detailsOfSale}
              </p>
            </div>
          )}

          {/* PAYMENT METHODS + TOTALS - direct deposit / Pay online on the left
              (whole block omitted when unconfigured), the five invoice totals on
              the right. No deposit sentence and no line table. */}
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
                      {view.payment_account_name && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            Account
                          </span>
                          <span>{view.payment_account_name}</span>
                        </div>
                      )}
                      {view.payment_bank_name && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            Bank
                          </span>
                          <span>{view.payment_bank_name}</span>
                        </div>
                      )}
                      {view.payment_bsb && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            BSB
                          </span>
                          <span>{view.payment_bsb}</span>
                        </div>
                      )}
                      {view.payment_account_number && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            Account No.
                          </span>
                          <span>{view.payment_account_number}</span>
                        </div>
                      )}
                      {orderNumber && (
                        <div>
                          <span className="inline-block w-28 text-slate-500">
                            Reference
                          </span>
                          <span className="font-mono">{orderNumber}</span>
                        </div>
                      )}
                    </div>
                  </div>
                )}
                {/* Pay online — the tenant's Stripe payment link exactly as the
                    backend returned it (HTTPS-only, server-sanitised). Outline
                    button themed with the document accent, never printed; a plain
                    anchor, no JS handler. */}
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
            </div>
          </div>

          <div className="my-8 border-t border-slate-200" />

          {/* TERMS - the terms stored on this version (never the business's
              current terms). On screen they come BEFORE the acceptance area so the
              customer sees them before signing; in print they start on a fresh
              page. */}
          {sanitizedTermsHtml && (
            <div className="print:break-before-page">
              <div className="text-center text-xs font-semibold uppercase tracking-wider text-slate-800">
                Terms and Conditions applicable to this invoice
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
                customer's responsibility unless otherwise stated above.
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
                      disabled={!draftEditable}
                      onChange={(event) => {
                        if (!draftEditable) return
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
                described above at the value shown on this invoice and upon the
                terms and conditions stated herein.
              </p>
              <p className="mt-2 text-xs font-bold uppercase tracking-wider text-slate-900">
                I accept the terms and conditions of this invoice.
              </p>
            </div>

            <div className="flex flex-col items-center">
              <div className="text-sm font-semibold text-slate-700">
                Customer Signature
              </div>
              {accepted ? (
                // Local confirmation from THIS submit session (the 201 message).
                // No signed document, timestamp, server-side name or email
                // outcome is requested or shown.
                <div
                  role="status"
                  aria-live="polite"
                  className="mt-2 w-full max-w-[380px] rounded-md border border-teal-200 bg-teal-50 px-4 py-4 text-center"
                >
                  <p className="text-base font-semibold text-teal-800">
                    {acceptState.message}
                  </p>
                  <p className="mt-1 text-sm text-teal-700">
                    Thank you. This invoice link is now inactive.
                  </p>
                </div>
              ) : (
                <div className="mt-2 w-full max-w-[380px]">
                  {/* Mouse, finger and pen (pointer events). The shallow-pad rule
                      is the Invoice tab's own. */}
                  <div className="invoice-signature-pad rounded-md border border-slate-200 bg-white px-1.5 pt-1.5 pb-1">
                    <SignaturePad
                      ref={padRef}
                      disabled={!draftEditable}
                      onInkChange={setHasInk}
                    />
                  </div>
                  <div className="mt-1 flex items-center justify-between gap-3">
                    {/* The version's frozen customer name, in full: never typed
                        and never truncated (a long name wraps). */}
                    <span className="min-w-0 break-words text-sm font-medium text-slate-800">
                      {view.customer_name ?? ''}
                    </span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        if (draftEditable) padRef.current?.clear()
                      }}
                      disabled={!draftEditable || !hasInk}
                      className="shrink-0 whitespace-nowrap print:hidden"
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
              {acceptState.read.reason === 'ambiguous' ? (
                <p className="text-sm font-medium text-amber-800">
                  We could not confirm whether your acceptance was received.
                  Please check the status before trying again.
                </p>
              ) : (
                // After a Preview PDF 409 the stale message is already in the
                // note under the action bar, so it is not repeated here.
                <div className="text-sm font-medium text-amber-800">
                  {acceptState.read.place === 'accept' && (
                    <p className="mb-1">{acceptState.read.message}</p>
                  )}
                  <p>
                    We could not load the updated invoice. Please check the
                    status before trying again.
                  </p>
                </div>
              )}
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => void handleCheckStatus()}
                className="shrink-0"
              >
                Check status
              </Button>
            </div>
          )}

          {/* Full-width bottom action bar (the Invoice tab's Accept bar). */}
          {!accepted && (
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
                    : 'Accept Invoice'}
              </button>
              {signingOpen && (!hasInk || !allChecked) && (
                <p className="mt-2 text-center text-xs text-slate-500">
                  Tick both statements and sign above to accept this invoice.
                </p>
              )}
            </div>
          )}

          <div className="my-6 border-t border-slate-200" />

          {/* FOOTER — exactly once, with or without terms. */}
          <footer className="text-center text-xs tracking-wide text-slate-500">
            Generated by {businessName ?? 'the Flooring Sales Portal'}{' '}
            &#183; Tax Invoice &#183; GST included where applicable
          </footer>
        </article>
      </div>
    </div>
  )
}
