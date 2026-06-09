import { useEffect, useRef, useState } from 'react'
import { Button } from '../ui/Button'
import { ApiError } from '../../lib/api/ApiError'
import {
  createInvoice,
  fetchCurrentInvoice,
  fetchCurrentInvoicePdf,
  rewriteInvoice,
  type InvoiceDetail,
} from '../../lib/api/orderInvoicesApi'
import type {
  DetailsOfSaleFields,
  OrderAddress,
  OrderCustomer,
} from '../../lib/api/orderWorkspaceApi'

type Props = {
  orderId: number
  // LAID lock: gates the Rewrite action ONLY. Create / read / PDF download stay
  // allowed on a locked order (matches the backend LAID split).
  locked: boolean
  orderNumber?: string
  customer?: OrderCustomer | null
  billingAddress?: OrderAddress | null
  saleDetails?: DetailsOfSaleFields | null
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

// INVOICE_PRECONDITIONS_NOT_MET carries a details[] of backend ErrorDetail
// ({ section?, field?, message }). Parse defensively (also tolerating plain
// strings) so the UI can list the specific reasons the order is not yet
// invoice-ready.
type PreconditionFailure = {
  label: string | null
  message: string
}

function parsePreconditionFailures(details: unknown): PreconditionFailure[] {
  if (!Array.isArray(details)) return []
  const out: PreconditionFailure[] = []
  for (const item of details) {
    if (typeof item === 'string') {
      if (item.length > 0) out.push({ label: null, message: item })
      continue
    }
    if (item && typeof item === 'object') {
      const rec = item as {
        section?: unknown
        field?: unknown
        message?: unknown
      }
      const message = typeof rec.message === 'string' ? rec.message : ''
      const field = typeof rec.field === 'string' ? rec.field : null
      const section = typeof rec.section === 'string' ? rec.section : null
      if (message.length > 0) out.push({ label: field ?? section, message })
    }
  }
  return out
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

function SignatureScribble() {
  return (
    <svg
      viewBox="0 0 220 60"
      aria-hidden="true"
      className="h-20 w-64 text-slate-800"
    >
      <path
        d="M8 42 C 22 8, 40 6, 52 38 C 62 58, 78 50, 90 30 C 100 14, 116 22, 128 36 L 138 26 C 150 38, 166 40, 180 18 C 190 8, 200 32, 214 28"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

export function InvoiceTab({
  orderId,
  locked,
  orderNumber,
  customer,
  billingAddress,
  saleDetails,
}: Props) {
  // Only ever the CURRENT/latest invoice — no version list, no history.
  const [invoice, setInvoice] = useState<InvoiceDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [preconditionFailures, setPreconditionFailures] = useState<
    PreconditionFailure[]
  >([])
  const [creating, setCreating] = useState(false)
  const [rewriting, setRewriting] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)

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
    setPreconditionFailures([])
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

  function applyActionError(err: unknown, fallback: string) {
    if (err instanceof ApiError && err.code === 'INVOICE_PRECONDITIONS_NOT_MET') {
      setPreconditionFailures(parsePreconditionFailures(err.details))
      setActionError(
        err.message.length > 0
          ? err.message
          : 'This order is not ready to be invoiced.',
      )
      return
    }
    setActionError(
      err instanceof ApiError && err.message.length > 0 ? err.message : fallback,
    )
  }

  async function handleCreate() {
    if (creating) return
    setCreating(true)
    setActionError(null)
    setPreconditionFailures([])
    try {
      const res = await createInvoice(orderId)
      if (!mountedRef.current) return
      setInvoice(res.data.invoice)
    } catch (err) {
      if (!mountedRef.current) return
      // An invoice already exists (state drifted) — refetch the current one.
      if (err instanceof ApiError && err.code === 'INVOICE_ALREADY_EXISTS') {
        refetch()
        return
      }
      applyActionError(err, 'Could not create the invoice. Please try again.')
    } finally {
      if (mountedRef.current) setCreating(false)
    }
  }

  async function handleRewrite() {
    // Rewrite is blocked when LAID — the backend is the authority (422
    // ORDER_LOCKED), this just disables the path early.
    if (rewriting || locked) return
    setRewriting(true)
    setActionError(null)
    setPreconditionFailures([])
    try {
      const res = await rewriteInvoice(orderId)
      if (!mountedRef.current) return
      setInvoice(res.data.invoice)
    } catch (err) {
      if (!mountedRef.current) return
      // No invoice to rewrite (state drifted) — refetch.
      if (err instanceof ApiError && err.code === 'INVOICE_REQUIRED') {
        refetch()
        return
      }
      if (err instanceof ApiError && err.code === 'ORDER_LOCKED') {
        setActionError(
          'This order is locked (LAID). The invoice cannot be rewritten.',
        )
        return
      }
      applyActionError(err, 'Could not rewrite the invoice. Please try again.')
    } finally {
      if (mountedRef.current) setRewriting(false)
    }
  }

  async function handleDownload() {
    if (downloading || invoice === null) return
    const current = invoice
    setDownloading(true)
    setActionError(null)
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
      applyActionError(
        err,
        'Could not download the invoice PDF. Please try again.',
      )
    } finally {
      if (mountedRef.current) setDownloading(false)
    }
  }

  const customerName = composeFullName(customer)
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
        {!loading && loadError === null && (
          <div className="flex items-center gap-2 shrink-0">
            {invoice === null ? (
              <Button
                type="button"
                variant="success"
                size="md"
                onClick={handleCreate}
                disabled={creating}
              >
                {creating ? 'Creating…' : 'Create invoice'}
              </Button>
            ) : (
              <>
                <Button
                  type="button"
                  variant="secondary"
                  size="md"
                  onClick={handleDownload}
                  disabled={downloading}
                >
                  {downloading ? 'Preparing…' : 'Download PDF'}
                </Button>
                <Button
                  type="button"
                  variant="success"
                  size="md"
                  onClick={handleRewrite}
                  disabled={locked || rewriting}
                  title={
                    locked
                      ? 'This order is locked (LAID) and cannot be rewritten.'
                      : undefined
                  }
                >
                  {rewriting ? 'Rewriting…' : 'Rewrite invoice'}
                </Button>
              </>
            )}
          </div>
        )}
      </div>

      {actionError !== null && (
        <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
          <p className="text-sm font-medium text-rose-700">{actionError}</p>
          {preconditionFailures.length > 0 && (
            <ul className="mt-2 space-y-1">
              {preconditionFailures.map((failure, idx) => (
                <li
                  key={`${failure.label ?? ''}-${idx}`}
                  className="text-xs text-rose-700 leading-relaxed"
                >
                  {failure.label ? (
                    <span className="font-medium">{failure.label}: </span>
                  ) : null}
                  {failure.message}
                </li>
              ))}
            </ul>
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
              <div className="mt-2 rounded-md border border-slate-300 bg-slate-50/60 px-6 py-6 min-h-[140px] flex items-center justify-center">
                <SignatureScribble />
              </div>
              <div className="mt-2 text-sm font-medium text-slate-800">
                {customerName || '—'}
              </div>
            </div>
          </div>
        </article>
      ) : (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-10 text-center">
          <p className="text-base font-medium text-slate-700">
            No invoice has been created yet.
          </p>
          <p className="mt-1 text-sm text-slate-500">
            Use “Create invoice” to generate the current invoice for this order.
          </p>
        </div>
      )}
    </div>
  )
}
