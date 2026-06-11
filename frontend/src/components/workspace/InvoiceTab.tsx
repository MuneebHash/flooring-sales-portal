import { useEffect, useRef, useState } from 'react'
import { Button } from '../ui/Button'
import { SignaturePad, type SignaturePadHandle } from './SignaturePad'
import { ApiError } from '../../lib/api/ApiError'
import {
  ACCEPTED_NAME_MAX_LENGTH,
  acceptCurrentInvoice,
  fetchCurrentInvoice,
  fetchCurrentInvoicePdf,
  fetchCurrentInvoiceSignature,
  resendCurrentInvoice,
  type InvoiceDetail,
} from '../../lib/api/orderInvoicesApi'
import type {
  DetailsOfSaleFields,
  OrderAddress,
  OrderCustomer,
} from '../../lib/api/orderWorkspaceApi'

type Props = {
  orderId: number
  // View/download + Phase 13 acceptance (sign / accept / re-send). Create + Rewrite
  // live in the Details of Sale tab now. NOTE: LAID/`locked` is deliberately NOT a
  // prop here — Accept, Re-send and the signature download are all allowed when
  // LAID (acceptance parallels payment, not rewrite), so the lock never gates this tab.
  orderNumber?: string
  customer?: OrderCustomer | null
  billingAddress?: OrderAddress | null
  saleDetails?: DetailsOfSaleFields | null
  // Switches the workspace to the Customer tab when an accept/resend fails with
  // CUSTOMER_EMAIL_REQUIRED / CUSTOMER_EMAIL_INVALID so the salesperson can fix the
  // email where it lives.
  onGoToCustomer?: () => void
}

const MONEY_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

function formatMoney(value: number): string {
  return MONEY_FORMATTER.format(value)
}

function formatDocDate(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso)
  if (!match) return ''
  const [, year, month, day] = match
  return `${day}/${month}/${year}`
}

// Tiny local timestamp formatter for accepted_at / last_emailed_at (matches the
// PaymentsTab/PhotoPreviewModal convention of small self-contained copies).
const MONTH_NAMES = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
]

function formatTimestamp(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso)
  if (!match) return ''
  const [, year, month, day, hours, minutes] = match
  const name = MONTH_NAMES[Number(month) - 1]
  if (!name) return ''
  return `${day} ${name} ${year}, ${hours}:${minutes}`
}

function composeFullName(customer: OrderCustomer | null | undefined): string {
  if (!customer) return ''
  return [customer.first_name, customer.middle_name, customer.last_name]
    .filter((part) => part && part.trim().length > 0)
    .join(' ')
}

function composeAddressLines(
  address: OrderAddress | null | undefined,
): string[] {
  if (!address) return []
  const streetParts: string[] = []
  if (address.unit_number) {
    streetParts.push(`${address.unit_number}/${address.street_number}`)
  } else {
    streetParts.push(address.street_number)
  }
  streetParts.push(address.street)
  const line1 = streetParts.filter(Boolean).join(' ')
  const line2 = [address.suburb, address.state_code, address.postcode]
    .filter(Boolean)
    .join(' ')
  return [line1, line2].filter((line) => line.length > 0)
}

// Download filename mirrors the backend Content-Disposition pattern
// (invoice-{order_number}-v{version}.pdf). A blob object URL does not carry the
// server's Content-Disposition, so the anchor sets the name explicitly.
function pdfFileName(
  orderNumber: string | undefined,
  versionNumber: number,
): string {
  const safeOrder =
    orderNumber && orderNumber.length > 0 ? orderNumber : 'order'
  return `invoice-${safeOrder}-v${versionNumber}.pdf`
}

const TERMS: string[] = [
  'The customer is responsible for providing clear and clean access to all areas to be installed.',
  'The customer agrees to be present at the premises during the installation where reasonably practical.',
  'The customer is responsible for the removal and re-fitting of any internal doors as required for the installation.',
  'Adequate mains power is to be provided at the installation site for installer equipment.',
  'Furniture removal and replacement remains the customer’s responsibility unless otherwise stated above.',
  'The price stated on this invoice is for laying to the area described; a per-metre rate has not been quoted.',
  'Manufacturers make every effort to match dye lots; colour shades may vary from samples shown.',
  'Flooring is installed to normal industry standards using standard underlays unless otherwise stated above.',
  'Where the customer raises a complaint, reasonable access must be granted to inspect the affected area.',
  'These terms together with the details set out above constitute the entire agreement between the parties.',
  'Any variation in GST or government charges imposed after the date of this invoice is payable by the customer.',
  'Carpet, timber and vinyl products are subject to natural characteristics which may include shading and minor variation.',
]

const ACCEPTANCE_LINES: string[] = [
  'I agree to pay the balance before the installation date.',
  'I agree that no floor preparation costs are included unless otherwise stated above.',
]

export function InvoiceTab({
  orderId,
  orderNumber,
  customer,
  billingAddress,
  saleDetails,
  onGoToCustomer,
}: Props) {
  // Only ever the CURRENT/latest invoice — no version list, no history.
  const [invoice, setInvoice] = useState<InvoiceDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  // Backend success messages (accept / resend) surfaced VERBATIM.
  const [actionMessage, setActionMessage] = useState<string | null>(null)
  // True when the last accept/resend failed on the customer-email gate, so the
  // error banner can point the salesperson at the Customer tab.
  const [emailFixNeeded, setEmailFixNeeded] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const [resending, setResending] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)

  // Acceptance draft (unaccepted invoice only): whether the pad has at least one
  // drawn stroke. The accepted customer name is NOT typed — it is derived from the
  // order's saved customer (see derivedAcceptedName below).
  const [hasInk, setHasInk] = useState(false)
  const padRef = useRef<SignaturePadHandle | null>(null)

  // Stored accepted-signature display (object URL owned by the effect below).
  // While the fetch is in flight, signatureUrl is null and signatureFailed false —
  // the render's accepted_signature_present branch shows "Loading signature…".
  const [signatureUrl, setSignatureUrl] = useState<string | null>(null)
  const [signatureFailed, setSignatureFailed] = useState(false)

  // Tracks whether THIS tab is still mounted so async action handlers never
  // setState after the tab unmounts on a tab switch. The mount fetch below uses
  // its own per-run `cancelled` guard.
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // Fetch the current invoice on mount (the tab unmounts on a tab switch, so this
  // refetches fresh each open) and whenever reloadToken bumps. A 404
  // INVOICE_NOT_FOUND is the clean "no invoice yet" empty state, NOT an error.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    setActionError(null)
    setActionMessage(null)
    setEmailFixNeeded(false)
    // A reload is a fresh start for the acceptance draft too (the pad below
    // remounts empty, so the ink gate must match it).
    setHasInk(false)
    setInvoice(null)
    fetchCurrentInvoice(orderId)
      .then((res) => {
        if (cancelled) return
        setInvoice(res.data.invoice)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
          setInvoice(null)
          return
        }
        setLoadError(
          err instanceof ApiError && err.message.length > 0
            ? err.message
            : 'Could not load the invoice. Please try again.',
        )
      })
      .finally(() => {
        if (cancelled) return
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [orderId, reloadToken])

  function refetch() {
    setReloadToken((token) => token + 1)
  }

  // The D.10 stream is only fetched when a stored signature actually exists.
  const signaturePath =
    invoice !== null && invoice.accepted_signature_present
      ? invoice.accepted_signature_download_path
      : null
  // Version identity for the signature effect key. The download path is the
  // STABLE /current/signature URL for every invoice version, so keying on the
  // path alone would NOT refetch when a DIFFERENT accepted version arrives with
  // the same path (e.g. the 409 INVOICE_ALREADY_ACCEPTED resync after another
  // session rewrote and re-accepted) — the previous version's image would stay
  // displayed against the new version's name/timestamp. invoice_id is unique
  // per version row; a Re-send (same row, no new version) keeps the same id and
  // deliberately does not refetch the unchanged image.
  const signatureInvoiceId = invoice !== null ? invoice.invoice_id : null

  // Fetch + display the stored accepted signature (credentialed blob -> object
  // URL). OBJECT URL OWNERSHIP (mirrors PhotoPreviewModal): the closure-tracked
  // `objectUrl` + `cancelled` guard ensure the URL is revoked on unmount, on
  // invoice change/reload (signaturePath transitions), and when the blob resolves
  // AFTER cleanup — no leak, no setState-after-unmount. A signature fetch failure
  // degrades to a small inline notice; it never breaks the invoice tab.
  useEffect(() => {
    if (signaturePath === null) {
      setSignatureUrl(null)
      setSignatureFailed(false)
      return
    }
    let cancelled = false
    let objectUrl: string | null = null // closure-tracked — authoritative for revoke
    setSignatureUrl(null)
    setSignatureFailed(false)
    fetchCurrentInvoiceSignature(signaturePath)
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob)
        if (cancelled) {
          // Resolved after cleanup: revoke now, never set state.
          URL.revokeObjectURL(objectUrl)
          objectUrl = null
          return
        }
        setSignatureUrl(objectUrl)
      })
      .catch(() => {
        // Includes the defensive backend guards (404 INVOICE_NOT_FOUND /
        // 422 INVOICE_NOT_ACCEPTED) — show the inline fallback, keep the tab usable.
        if (!cancelled) setSignatureFailed(true)
      })
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [signaturePath, signatureInvoiceId])

  // Inline current-invoice resync for drift errors (409 INVOICE_ALREADY_ACCEPTED,
  // 422 INVOICE_REQUIRED / INVOICE_NOT_ACCEPTED). Deliberately NOT refetch(): a
  // reloadToken bump clears the banners, and these cases need the explanatory
  // banner to survive while the fresh state (accepted / empty) renders.
  async function resyncCurrentInvoice() {
    try {
      const res = await fetchCurrentInvoice(orderId)
      if (!mountedRef.current) return
      // Also wipe drawn-but-unsubmitted ink: when the fresh invoice is still
      // unaccepted the pad stays MOUNTED, and stale strokes must never be
      // submittable against a different version (hasInk alone only gates the
      // button — the old ink would otherwise ride along with the next stroke).
      padRef.current?.clear()
      setHasInk(false)
      setInvoice(res.data.invoice)
    } catch (err) {
      if (!mountedRef.current) return
      if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
        padRef.current?.clear()
        setHasInk(false)
        setInvoice(null)
        return
      }
      // Other resync failures: keep the current view — the banner already
      // explains the situation and the user can switch tabs to refresh.
    }
  }

  // Shared accept/resend error handling. Backend messages are surfaced VERBATIM
  // whenever present; the per-code branches only add the right recovery behaviour.
  async function applyAcceptResendError(err: unknown, fallback: string) {
    if (err instanceof ApiError) {
      const message = err.message.length > 0 ? err.message : fallback
      if (err.code === 'INVOICE_ALREADY_ACCEPTED') {
        // Drift: this invoice was accepted elsewhere. Surface the backend message
        // and pull the accepted state.
        setActionError(message)
        await resyncCurrentInvoice()
        return
      }
      if (
        err.code === 'CUSTOMER_EMAIL_REQUIRED' ||
        err.code === 'CUSTOMER_EMAIL_INVALID'
      ) {
        // The fix lives on the Customer tab — the banner adds the pointer/button.
        setActionError(message)
        setEmailFixNeeded(true)
        return
      }
      if (
        err.code === 'INVOICE_REQUIRED' ||
        err.code === 'INVOICE_NOT_ACCEPTED'
      ) {
        // Drift: the current invoice vanished (-> empty state) or is no longer
        // accepted (e.g. rewritten elsewhere -> signature pad again).
        setActionError(message)
        await resyncCurrentInvoice()
        return
      }
      // Everything else — including 502 EMAIL_SEND_FAILED on resend, where the
      // backend wrote NOTHING, so the invoice state must stay untouched.
      setActionError(message)
      return
    }
    setActionError(fallback)
  }

  async function handleAccept() {
    if (accepting || resending || invoice === null) return
    const displayed = invoice
    // accepted_customer_name is auto-derived from the order's saved customer —
    // the customer never types it. A blank or over-length name disables Accept
    // in the render; these guards keep a stray call from POSTing an invalid part
    // (over-length is blocked outright, never silently truncated).
    const name = derivedAcceptedName
    if (
      name.length === 0 ||
      name.length > ACCEPTED_NAME_MAX_LENGTH ||
      !hasInk
    ) {
      return
    }
    setAccepting(true)
    setActionError(null)
    setActionMessage(null)
    setEmailFixNeeded(false)
    try {
      // BEST-EFFORT STALE-TAB GUARD: the accept endpoint targets /current, but
      // another session may have recorded a payment or rewritten the invoice
      // after this tab loaded — the customer would have reviewed/signed an
      // invoice that is no longer /current. Refetch and compare the version
      // identity (invoice_id + version_number) BEFORE accepting; on mismatch,
      // show the fresh invoice and require a re-sign instead of silently
      // accepting a different version. This is best-effort only: a
      // millisecond-level race window remains between this check and the accept
      // POST because the backend has no version precondition on accept.
      let fresh: InvoiceDetail
      try {
        const check = await fetchCurrentInvoice(orderId)
        fresh = check.data.invoice
      } catch (err) {
        if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
          // The invoice vanished server-side — never accept; drop the unsigned
          // draft and render the no-invoice empty state.
          if (!mountedRef.current) return
          padRef.current?.clear()
          setHasInk(false)
          setInvoice(null)
          setActionError('This invoice is no longer available.')
          return
        }
        // Any other pre-check failure blocks the accept (freshness unverified)
        // and falls through to the shared error handler below.
        throw err
      }
      if (!mountedRef.current) return
      if (
        fresh.invoice_id !== displayed.invoice_id ||
        fresh.version_number !== displayed.version_number
      ) {
        // The displayed invoice is stale. Render the fresh one (the accepted
        // state renders normally if it is already accepted), drop the unsigned
        // signature draft, and do NOT accept.
        padRef.current?.clear()
        setHasInk(false)
        setInvoice(fresh)
        setActionError(
          'The invoice changed since it was loaded. Please review the updated invoice and sign again.',
        )
        return
      }
      // Unchanged — continue with the normal accept flow.
      // canvas.toBlob('image/png') — the exact declared content type the backend
      // requires for the `signature` part.
      const blob = await padRef.current?.toBlob()
      if (!mountedRef.current) return
      if (!blob) {
        setActionError('Could not read the signature. Please sign again.')
        return
      }
      const res = await acceptCurrentInvoice(orderId, name, blob)
      if (!mountedRef.current) return
      // The 201 body is the FINAL post-email state (accepted_at set;
      // last_emailed_at set iff the auto-email succeeded) — no follow-up GET.
      setInvoice(res.data.invoice)
      setActionMessage(
        res.message && res.message.length > 0 ? res.message : 'Invoice accepted.',
      )
      // The signed state replaces the pad; drop the local unsigned draft.
      setHasInk(false)
    } catch (err) {
      if (!mountedRef.current) return
      await applyAcceptResendError(
        err,
        'Could not accept the invoice. Please try again.',
      )
    } finally {
      if (mountedRef.current) setAccepting(false)
    }
  }

  async function handleResend() {
    if (accepting || resending || invoice === null) return
    setResending(true)
    setActionError(null)
    setActionMessage(null)
    setEmailFixNeeded(false)
    try {
      const res = await resendCurrentInvoice(orderId)
      if (!mountedRef.current) return
      // Success bumps only last_emailed_at (no new version) — apply the returned state.
      setInvoice(res.data.invoice)
      setActionMessage(
        res.message && res.message.length > 0
          ? res.message
          : 'Invoice re-sent to the customer.',
      )
    } catch (err) {
      if (!mountedRef.current) return
      await applyAcceptResendError(
        err,
        'Could not re-send the invoice. Please try again.',
      )
    } finally {
      if (mountedRef.current) setResending(false)
    }
  }

  function handleClearSignature() {
    if (accepting) return
    // clear() also fires onInkChange(false), which disables Accept.
    padRef.current?.clear()
  }

  async function handleDownload() {
    if (downloading || invoice === null) return
    const current = invoice
    setDownloading(true)
    setActionError(null)
    setActionMessage(null)
    setEmailFixNeeded(false)
    try {
      const { blob, fileName } = await fetchCurrentInvoicePdf(orderId)
      if (!mountedRef.current) return
      // One-shot object URL: create, trigger the download, revoke immediately. Prefer the server
      // file name from the D.4 Content-Disposition (authoritative for the CURRENT invoice the
      // backend just streamed, so it stays correct even if the invoice changed since the last
      // read); fall back to the client-side name only when the header is absent/unreadable.
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download =
        fileName ?? pdfFileName(orderNumber, current.version_number)
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (err) {
      if (!mountedRef.current) return
      // The invoice vanished server-side — show a message and refetch state.
      if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
        setActionError('This invoice is no longer available. Refreshing…')
        refetch()
        return
      }
      setActionError(
        err instanceof ApiError && err.message.length > 0
          ? err.message
          : 'Could not download the invoice PDF. Please try again.',
      )
    } finally {
      if (mountedRef.current) setDownloading(false)
    }
  }

  const customerName = composeFullName(customer)
  // accepted_customer_name sent on Accept — auto-derived from the order's saved
  // customer (same composeFullName source as the document's "Invoice To" block),
  // trimmed, never typed by the customer. Blank (no saved customer) or
  // over-length (> 150 chars) blocks Accept with an inline notice — the name is
  // never silently truncated. The ACCEPTED state renders
  // invoice.accepted_customer_name (the immutable response snapshot), not this
  // live value.
  const derivedAcceptedName = customerName.trim()
  const derivedNameTooLong =
    derivedAcceptedName.length > ACCEPTED_NAME_MAX_LENGTH
  const billingAddressLines = composeAddressLines(billingAddress)
  const detailsOfSaleText =
    invoice?.details_of_sale_snapshot || saleDetails?.details_of_sale || ''
  const dueDateText =
    invoice && invoice.due_date ? formatDocDate(invoice.due_date) : ''

  return (
    <div>
      <div className="mb-4 flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
            Invoice
          </h2>
          <p className="text-sm text-slate-500 mt-1">
            View the current invoice for this order.
          </p>
        </div>
        {!loading && loadError === null && invoice !== null && (
          <div className="flex items-center gap-2 shrink-0">
            <Button
              type="button"
              variant="secondary"
              size="md"
              onClick={handleDownload}
              disabled={downloading}
            >
              {downloading ? 'Preparing…' : 'Download PDF'}
            </Button>
            {/* Re-send is gated on accepted_at ONLY — never on last_emailed_at.
                It shows for every accepted invoice: when the Accept auto-email
                failed (last_emailed_at null) AND after a successful email
                (sending another copy). A second, in-context Re-send lives in
                the accepted signature block below. */}
            {invoice.accepted_at !== null && (
              <Button
                type="button"
                variant="success"
                size="md"
                onClick={handleResend}
                disabled={resending || accepting}
              >
                {resending ? 'Re-sending…' : 'Re-send Invoice'}
              </Button>
            )}
          </div>
        )}
      </div>

      {actionMessage !== null && (
        <div className="mb-4 rounded-lg border border-teal-200 bg-teal-50 px-4 py-3">
          <p className="text-sm font-medium text-teal-700">{actionMessage}</p>
        </div>
      )}

      {actionError !== null && (
        <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
          <p className="text-sm font-medium text-rose-700">{actionError}</p>
          {emailFixNeeded && (
            <div className="mt-2 flex flex-col sm:flex-row sm:items-center gap-2">
              <p className="text-xs text-rose-700">
                Add or fix the customer's email on the Customer tab, then try
                again.
              </p>
              {onGoToCustomer && (
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={onGoToCustomer}
                >
                  Go to Customer tab
                </Button>
              )}
            </div>
          )}
        </div>
      )}

      {loading ? (
        <div className="rounded-xl border border-slate-200 bg-white px-6 py-10 text-center">
          <p className="text-sm text-slate-500">Loading invoice…</p>
        </div>
      ) : loadError !== null ? (
        <div className="rounded-xl border border-rose-200 bg-white px-6 py-10 text-center">
          <p className="text-base font-medium text-slate-700">{loadError}</p>
          <div className="mt-4">
            <Button
              type="button"
              variant="secondary"
              size="md"
              onClick={refetch}
            >
              Try again
            </Button>
          </div>
        </div>
      ) : invoice !== null ? (
        <article className="rounded-lg border border-slate-200 bg-white shadow-sm p-6 sm:p-8">
          <header className="grid grid-cols-1 sm:grid-cols-3 gap-6 items-start">
            <div className="sm:col-span-1">
              <div className="text-base font-bold text-slate-900 tracking-tight">
                Aussie Floors Group
              </div>
              <div className="mt-0.5 text-xs text-slate-600">
                Aussie Floors Sydney CBD
              </div>
              <div className="mt-2 text-[11px] text-slate-500">
                ABN: Sample only
              </div>
            </div>
            <div className="hidden sm:block sm:col-span-1" />
            <div className="sm:col-span-1 sm:text-right">
              <div className="text-2xl font-bold text-slate-900 tracking-tight">
                INVOICE
              </div>
              {orderNumber && (
                <div className="mt-1 text-sm font-mono text-slate-700">
                  {orderNumber}
                </div>
              )}
            </div>
          </header>

          <div className="my-6 border-t border-slate-200" />

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
            <div>
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Invoice To
              </div>
              {customerName ? (
                <div className="mt-2 text-sm font-semibold text-slate-900">
                  {customerName}
                </div>
              ) : (
                <div className="mt-2 text-sm text-slate-400">—</div>
              )}
              {billingAddressLines.length > 0 && (
                <div className="mt-1 text-sm text-slate-700 leading-relaxed">
                  {billingAddressLines.map((line) => (
                    <div key={line}>{line}</div>
                  ))}
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
                  {formatDocDate(invoice.invoice_date)}
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

          <div className="mt-8">
            <div className="text-base font-semibold text-slate-900">
              Details Of Sale
            </div>
            <p className="mt-2 text-sm text-slate-700 leading-relaxed whitespace-pre-wrap">
              {detailsOfSaleText || '—'}
            </p>
          </div>

          <div className="mt-8 sm:ml-auto sm:w-[360px] rounded-md border border-slate-200">
            <div className="flex items-center justify-between px-4 py-2.5">
              <span className="text-sm text-slate-700">Total Inc. GST</span>
              <span className="text-sm font-medium tabular-nums text-slate-900">
                {formatMoney(invoice.sale_price_inc_gst)}
              </span>
            </div>
            <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
              <span className="text-sm text-slate-700">Payment Made</span>
              <span className="text-sm font-medium tabular-nums text-rose-600">
                {formatMoney(invoice.total_paid)}
              </span>
            </div>
            <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
              <span className="text-sm text-slate-700">Balance Due</span>
              <span className="text-sm font-semibold tabular-nums text-slate-900">
                {formatMoney(invoice.balance_due)}
              </span>
            </div>
          </div>

          <div className="my-8 border-t border-slate-200" />

          <div>
            <div className="text-center text-xs font-semibold uppercase tracking-wider text-slate-800">
              Terms and Conditions — applicable to this invoice
            </div>
            <div className="mt-1 text-center text-[11px] text-slate-500">
              Sample terms for visual prototype only.
            </div>
            <div className="mt-4 grid grid-cols-1 lg:grid-cols-2 gap-x-8 gap-y-2">
              {TERMS.map((term, idx) => (
                <div
                  key={term}
                  className="flex gap-2 text-xs text-slate-700 leading-relaxed"
                >
                  <span className="text-slate-500 font-medium tabular-nums shrink-0">
                    {idx + 1}.
                  </span>
                  <span>{term}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="mt-8 grid grid-cols-1 sm:grid-cols-2 gap-6">
            <div className="space-y-2">
              {ACCEPTANCE_LINES.map((line) => (
                <label
                  key={line}
                  className="flex items-start gap-2 text-xs text-slate-700 leading-relaxed"
                >
                  <input
                    type="checkbox"
                    defaultChecked
                    disabled
                    className="mt-0.5 h-4 w-4 rounded border-slate-300 text-teal-600"
                  />
                  <span className="uppercase tracking-wide font-medium">
                    {line}
                  </span>
                </label>
              ))}
              <p className="pt-3 text-xs font-semibold uppercase tracking-wider text-slate-800">
                I accept the terms and conditions of this invoice.
              </p>
            </div>
            <div>
              <div className="text-xs uppercase tracking-wider text-slate-500 font-semibold">
                Customer Signature
              </div>
              {invoice.accepted_at !== null ? (
                <>
                  {/* Accepted state — render the stored signature + acceptance
                      metadata. No re-signing after acceptance. */}
                  <div className="mt-2 rounded-md border border-slate-300 bg-slate-50/60 px-6 py-6 min-h-[140px] flex items-center justify-center">
                    {signatureUrl !== null ? (
                      <img
                        src={signatureUrl}
                        alt={`Signature of ${invoice.accepted_customer_name ?? 'the customer'}`}
                        className="max-h-24 w-auto"
                      />
                    ) : signatureFailed ? (
                      <p className="text-xs text-slate-500">
                        The signature image could not be loaded.
                      </p>
                    ) : invoice.accepted_signature_present ? (
                      <p className="text-xs text-slate-500">
                        Loading signature…
                      </p>
                    ) : (
                      <p className="text-xs text-slate-500">
                        No signature image stored.
                      </p>
                    )}
                  </div>
                  <div className="mt-2 text-sm font-medium text-slate-800">
                    {invoice.accepted_customer_name || '—'}
                  </div>
                  <div className="mt-0.5 text-xs text-slate-500 tabular-nums">
                    Accepted on {formatTimestamp(invoice.accepted_at)}
                  </div>
                  {/* Email state derives from last_emailed_at, never from
                      message text: null = this version has not been emailed. */}
                  {invoice.last_emailed_at !== null ? (
                    <div className="mt-0.5 text-xs text-teal-700 tabular-nums">
                      Emailed to the customer on{' '}
                      {formatTimestamp(invoice.last_emailed_at)}
                    </div>
                  ) : (
                    <div className="mt-0.5 text-xs font-medium text-amber-700">
                      Accepted but not emailed yet — use Re-send Invoice to
                      email it to the customer.
                    </div>
                  )}
                  {/* In-context Re-send — rendered for EVERY accepted invoice
                      (this whole branch is accepted_at !== null), emailed or
                      not: re-sending is also how another copy is sent after a
                      successful email. Never gated on last_emailed_at. The
                      header carries the same action, but it sits a full
                      document-height away from this block, so the affordance
                      must live here too. */}
                  <div className="mt-3">
                    <Button
                      type="button"
                      variant="success"
                      size="sm"
                      onClick={handleResend}
                      disabled={resending || accepting}
                    >
                      {resending ? 'Re-sending…' : 'Re-send Invoice'}
                    </Button>
                  </div>
                </>
              ) : (
                <>
                  {/* Unaccepted — capture a real signature (pointer events:
                      touch, pen and mouse). The accepted customer name is
                      derived from the order's saved customer and shown
                      read-only below the signature box (like the printed
                      document) — the customer never types it. */}
                  <div className="mt-2 rounded-md border border-slate-300 bg-slate-50/60 p-2">
                    <SignaturePad
                      ref={padRef}
                      disabled={accepting}
                      onInkChange={setHasInk}
                    />
                  </div>
                  <div className="mt-2 flex items-center justify-between gap-3">
                    <p className="text-[11px] text-slate-500">
                      The customer signs above — touch, pen or mouse.
                    </p>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={handleClearSignature}
                      disabled={accepting || !hasInk}
                    >
                      Clear signature
                    </Button>
                  </div>
                  <div className="mt-2 text-sm font-medium text-slate-800">
                    {derivedAcceptedName || '—'}
                  </div>
                  {derivedAcceptedName.length === 0 ? (
                    <div className="mt-2 flex flex-col sm:flex-row sm:items-center gap-2">
                      <p className="text-xs font-medium text-amber-700">
                        Save the customer's name on the Customer tab before
                        accepting this invoice.
                      </p>
                      {onGoToCustomer && (
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={onGoToCustomer}
                        >
                          Go to Customer tab
                        </Button>
                      )}
                    </div>
                  ) : derivedNameTooLong ? (
                    <div className="mt-2 flex flex-col sm:flex-row sm:items-center gap-2">
                      <p className="text-xs font-medium text-amber-700">
                        The customer name is longer than{' '}
                        {ACCEPTED_NAME_MAX_LENGTH} characters, so the invoice
                        cannot be accepted. Shorten it on the Customer tab.
                      </p>
                      {onGoToCustomer && (
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={onGoToCustomer}
                        >
                          Go to Customer tab
                        </Button>
                      )}
                    </div>
                  ) : null}
                  <div className="mt-3 flex justify-end">
                    <Button
                      type="button"
                      variant="success"
                      size="md"
                      onClick={handleAccept}
                      disabled={
                        accepting ||
                        resending ||
                        !hasInk ||
                        derivedAcceptedName.length === 0 ||
                        derivedNameTooLong
                      }
                    >
                      {accepting ? 'Accepting…' : 'Accept Invoice'}
                    </Button>
                  </div>
                </>
              )}
            </div>
          </div>
        </article>
      ) : (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-10 text-center">
          <p className="text-base font-medium text-slate-700">
            No invoice has been created yet.
          </p>
          <p className="mt-1 text-sm text-slate-500">
            Create the invoice from the Details of Sale tab to view and download
            it here.
          </p>
        </div>
      )}
    </div>
  )
}
