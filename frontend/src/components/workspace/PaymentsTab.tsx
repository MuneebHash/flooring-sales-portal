import { useEffect, useRef, useState } from 'react'
import { Badge } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Field } from '../ui/Field'
import { Input } from '../ui/Input'
import { Select } from '../ui/Select'
import { ApiError } from '../../lib/api/ApiError'
import {
  fetchOrderPayments,
  recordOrderPayment,
  type PaymentMethod,
  type PaymentSummary,
  type PaymentTransaction,
  type RecordPaymentRequest,
} from '../../lib/api/orderPaymentsApi'

type Props = {
  // orderId drives the payments fetch + record POST. NOTE: LAID/`locked` is
  // deliberately NOT a prop here — payments are LAID-exempt (the record form is
  // never disabled by the lock). The form is gated only by whether an invoice
  // exists (payment_summary.balance_due !== null), which is the invoice-first rule.
  orderId: number
}

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

const METHOD_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Cash',
  CREDIT_CARD: 'Credit card',
  EFTPOS: 'EFTPOS',
  BANK_TRANSFER: 'Bank transfer',
}

const METHOD_TONES: Record<PaymentMethod, string> = {
  CASH: 'bg-emerald-100 text-emerald-700 border-emerald-200',
  CREDIT_CARD: 'bg-indigo-100 text-indigo-700 border-indigo-200',
  EFTPOS: 'bg-sky-100 text-sky-700 border-sky-200',
  BANK_TRANSFER: 'bg-slate-100 text-slate-700 border-slate-300',
}

const MONEY_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

// Matches payment_transaction.payment_reference VARCHAR(100) / the backend trim+length check.
const MAX_REFERENCE_LENGTH = 100

function formatMoney(value: number): string {
  return MONEY_FORMATTER.format(value)
}

function formatTimestamp(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso)
  if (!match) return ''
  const [, year, month, day, hours, minutes] = match
  const name = MONTH_NAMES[Number(month) - 1]
  if (!name) return ''
  return `${day} ${name} ${year}, ${hours}:${minutes}`
}

// A VALIDATION_FAILED error carries error.details[] of { field?, message }. Flatten to
// "field: message" lines (also tolerating plain strings) so the UI can list the specific reasons.
function parseValidationDetails(details: unknown): string[] {
  if (!Array.isArray(details)) return []
  const out: string[] = []
  for (const item of details) {
    if (typeof item === 'string') {
      if (item.length > 0) out.push(item)
      continue
    }
    if (item && typeof item === 'object') {
      const rec = item as { field?: unknown; message?: unknown }
      const message = typeof rec.message === 'string' ? rec.message : ''
      const field = typeof rec.field === 'string' ? rec.field : null
      if (message.length > 0) out.push(field ? `${field}: ${message}` : message)
    }
  }
  return out
}

export function PaymentsTab({ orderId }: Props) {
  const [payments, setPayments] = useState<PaymentTransaction[]>([])
  const [summary, setSummary] = useState<PaymentSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<string[]>([])
  const [recording, setRecording] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)

  const [method, setMethod] = useState<PaymentMethod | ''>('')
  const [amount, setAmount] = useState('')
  const [reference, setReference] = useState('')

  // Guard against setState after the tab unmounts on a tab switch. The mount
  // fetch below uses its own per-run `cancelled` flag.
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // Fetch payments + current payment summary on mount (the tab unmounts on a tab
  // switch, so this refetches fresh each open) and whenever reloadToken bumps
  // (after a successful record or a drift error). Allowed regardless of LAID. The
  // list has no 404-as-empty: an order with no payments returns 200 with an empty
  // array; ORDER_NOT_FOUND is a genuine load error.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    fetchOrderPayments(orderId)
      .then((res) => {
        if (cancelled) return
        setPayments(res.data.payments)
        setSummary(res.data.payment_summary)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setLoadError(
          err instanceof ApiError && err.message.length > 0
            ? err.message
            : 'Could not load payments. Please try again.',
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

  const summaryView: PaymentSummary = summary ?? {
    total_paid: 0,
    balance_due: null,
  }
  const balanceDue = summaryView.balance_due
  const invoiceIssued = balanceDue !== null

  // --- Client-side validation (the backend remains the authority). ---
  const trimmedReference = reference.trim()
  const amountNumber = Number(amount)
  const amountValid =
    amount.trim().length > 0 &&
    Number.isFinite(amountNumber) &&
    amountNumber > 0
  const referenceTooLong = trimmedReference.length > MAX_REFERENCE_LENGTH
  const withinBalance =
    balanceDue === null || (amountValid && amountNumber <= balanceDue)
  const formValid =
    invoiceIssued &&
    method !== '' &&
    amountValid &&
    withinBalance &&
    !referenceTooLong

  // The whole record form is disabled while a record is in flight, or until an
  // invoice exists. It is intentionally NEVER disabled by LAID/`locked`.
  const formDisabled = recording || !invoiceIssued

  const formHint = (() => {
    if (!invoiceIssued)
      return 'An invoice must exist before you can record a payment. Create it on the Invoice tab.'
    if (amount.trim().length > 0 && !amountValid)
      return 'Enter an amount greater than 0.'
    if (amountValid && balanceDue !== null && amountNumber > balanceDue)
      return `Amount cannot exceed the balance due (${formatMoney(balanceDue)}).`
    if (referenceTooLong)
      return `Reference must be ${MAX_REFERENCE_LENGTH} characters or fewer.`
    return null
  })()

  function applyActionError(err: unknown) {
    if (err instanceof ApiError) {
      if (err.code === 'INVOICE_REQUIRED') {
        setActionError(
          'An invoice must be created before recording a payment. Create the invoice on the Invoice tab first.',
        )
        // State drifted (form was enabled without an invoice) — resync.
        refetch()
        return
      }
      if (err.code === 'PAYMENT_EXCEEDS_BALANCE') {
        setActionError(
          err.message.length > 0
            ? err.message
            : 'Payment amount cannot exceed the current balance due.',
        )
        // The balance may have moved under us — pull the latest summary.
        refetch()
        return
      }
      if (err.code === 'VALIDATION_FAILED') {
        setFieldErrors(parseValidationDetails(err.details))
        setActionError(
          err.message.length > 0
            ? err.message
            : 'Please check the payment details and try again.',
        )
        return
      }
      if (err.code === 'ORDER_NOT_FOUND') {
        setActionError('This order is no longer available.')
        return
      }
      setActionError(
        err.message.length > 0
          ? err.message
          : 'Could not record the payment. Please try again.',
      )
      return
    }
    setActionError('Could not record the payment. Please try again.')
  }

  async function handleRecord() {
    if (recording) return
    // Defensive guards (the button is already disabled for these) so a stray call
    // can never POST an invalid body.
    if (!invoiceIssued) return
    if (method === '') {
      setActionError('Select a payment method.')
      return
    }
    if (!amountValid) {
      setActionError('Enter an amount greater than 0.')
      return
    }
    if (balanceDue !== null && amountNumber > balanceDue) {
      setActionError('Payment amount cannot exceed the balance due.')
      return
    }
    if (referenceTooLong) {
      setActionError(
        `Payment reference must be ${MAX_REFERENCE_LENGTH} characters or fewer.`,
      )
      return
    }

    setRecording(true)
    setActionError(null)
    setFieldErrors([])
    try {
      // Send ONLY the three client-owned fields. Omit payment_reference entirely
      // when blank (never send "" or null). No gateway / id / snapshot fields.
      const body: RecordPaymentRequest = {
        payment_method: method,
        amount: amountNumber,
        ...(trimmedReference.length > 0
          ? { payment_reference: trimmedReference }
          : {}),
      }
      await recordOrderPayment(orderId, body)
      if (!mountedRef.current) return
      // Reset the form, then refetch so the list + summary reflect the new payment
      // (newest first) and the regenerated balance consistently.
      setMethod('')
      setAmount('')
      setReference('')
      refetch()
    } catch (err) {
      if (!mountedRef.current) return
      applyActionError(err)
    } finally {
      if (mountedRef.current) setRecording(false)
    }
  }

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
          Payments
        </h2>
        <p className="text-sm text-slate-500 mt-1">
          Record and review payments for this order.
        </p>
      </div>

      {loading ? (
        <div className="rounded-xl border border-slate-200 bg-white px-6 py-10 text-center">
          <p className="text-sm text-slate-500">Loading payments…</p>
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
      ) : (
        <>
          {actionError !== null && (
            <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
              <p className="text-sm font-medium text-rose-700">{actionError}</p>
              {fieldErrors.length > 0 && (
                <ul className="mt-2 space-y-1">
                  {fieldErrors.map((msg, idx) => (
                    <li
                      key={`${idx}-${msg}`}
                      className="text-xs text-rose-700 leading-relaxed"
                    >
                      {msg}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-5">
            <div className="rounded-lg border border-slate-200 bg-slate-50/60 p-4">
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Total paid
              </div>
              <div className="mt-1 text-2xl font-bold tabular-nums text-slate-900">
                {formatMoney(summaryView.total_paid)}
              </div>
            </div>

            <div className="rounded-lg border border-slate-200 bg-slate-50/60 p-4">
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Balance due
              </div>
              {balanceDue === null ? (
                <>
                  <div className="mt-1 text-2xl font-bold tabular-nums text-slate-400">
                    —
                  </div>
                  <div className="mt-0.5 text-[11px] text-slate-500">
                    No invoice issued yet
                  </div>
                </>
              ) : (
                <div className="mt-1 text-2xl font-bold tabular-nums text-slate-900">
                  {formatMoney(balanceDue)}
                </div>
              )}
            </div>

            <div className="rounded-lg border border-slate-200 bg-slate-50/60 p-4">
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Invoice status
              </div>
              <div className="mt-1.5">
                {invoiceIssued ? (
                  <Badge tone="bg-teal-100 text-teal-700 border-teal-200">
                    Invoice issued
                  </Badge>
                ) : (
                  <Badge tone="bg-slate-100 text-slate-600 border-slate-300">
                    No invoice yet
                  </Badge>
                )}
              </div>
            </div>
          </div>

          <div className="space-y-5">
            <section className="rounded-xl border border-slate-200 bg-slate-50/60 p-5">
              <div className="flex items-center justify-between mb-3">
                <h3 className="text-sm font-semibold text-slate-900">
                  Record payment
                </h3>
              </div>

              <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                  <Field label="Payment method" htmlFor="payment_method">
                    <Select
                      id="payment_method"
                      value={method}
                      disabled={formDisabled}
                      onChange={(e) =>
                        setMethod(e.target.value as PaymentMethod | '')
                      }
                    >
                      <option value="">— Select —</option>
                      <option value="CASH">Cash</option>
                      <option value="CREDIT_CARD">Credit card</option>
                      <option value="EFTPOS">EFTPOS</option>
                      <option value="BANK_TRANSFER">Bank transfer</option>
                    </Select>
                  </Field>
                  <Field label="Amount" htmlFor="payment_amount">
                    <Input
                      id="payment_amount"
                      type="number"
                      step="0.01"
                      min="0"
                      inputMode="decimal"
                      placeholder="0.00"
                      value={amount}
                      disabled={formDisabled}
                      onChange={(e) => setAmount(e.target.value)}
                    />
                  </Field>
                  <Field
                    label="Payment reference (optional)"
                    htmlFor="payment_reference"
                  >
                    <Input
                      id="payment_reference"
                      type="text"
                      maxLength={MAX_REFERENCE_LENGTH}
                      placeholder="e.g. EFTPOS-20260514"
                      value={reference}
                      disabled={formDisabled}
                      onChange={(e) => setReference(e.target.value)}
                    />
                  </Field>
                </div>
                <div className="mt-4 flex items-center justify-end gap-3">
                  {formHint && (
                    <span className="text-[11px] text-slate-500">
                      {formHint}
                    </span>
                  )}
                  <Button
                    type="button"
                    variant="success"
                    size="md"
                    onClick={handleRecord}
                    disabled={formDisabled || !formValid}
                  >
                    {recording ? 'Recording…' : 'Record payment'}
                  </Button>
                </div>
              </div>
            </section>

            <section className="rounded-xl border border-slate-200 bg-slate-50/60 p-5">
              <div className="flex items-center justify-between mb-3">
                <h3 className="text-sm font-semibold text-slate-900">
                  Payment history
                </h3>
                <span className="text-[11px] font-medium text-slate-500">
                  {payments.length}{' '}
                  {payments.length === 1 ? 'payment' : 'payments'}
                </span>
              </div>

              {payments.length === 0 ? (
                <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-6 text-center">
                  <p className="text-sm text-slate-500">
                    No payments recorded yet.
                  </p>
                </div>
              ) : (
                <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
                  <table className="w-full text-sm table-fixed">
                    <colgroup>
                      <col className="w-[15%]" />
                      <col className="w-[15%]" />
                      <col className="w-[45%]" />
                      <col className="w-[25%]" />
                    </colgroup>
                    <thead>
                      <tr className="bg-slate-100 text-[11px] uppercase tracking-wider text-slate-600">
                        <th className="text-left font-semibold px-4 py-2.5">
                          Method
                        </th>
                        <th className="text-right font-semibold px-4 py-2.5">
                          Amount
                        </th>
                        <th className="text-left font-semibold px-4 py-2.5">
                          Reference
                        </th>
                        <th className="text-right font-semibold px-4 py-2.5">
                          Recorded
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {payments.map((payment) => (
                        <tr
                          key={payment.payment_transaction_id}
                          className="border-t border-slate-200"
                        >
                          <td className="px-4 py-3 whitespace-nowrap">
                            <Badge tone={METHOD_TONES[payment.payment_method]}>
                              {METHOD_LABELS[payment.payment_method]}
                            </Badge>
                          </td>
                          <td className="px-4 py-3 text-right tabular-nums font-medium text-slate-900 whitespace-nowrap">
                            {formatMoney(payment.amount)}
                          </td>
                          <td className="px-4 py-3 text-left">
                            {payment.payment_reference ? (
                              <span className="font-mono text-xs text-slate-700 truncate block">
                                {payment.payment_reference}
                              </span>
                            ) : (
                              <span className="text-slate-400">—</span>
                            )}
                          </td>
                          <td className="px-4 py-3 text-right text-slate-600 tabular-nums whitespace-nowrap">
                            {formatTimestamp(payment.created_at)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>
        </>
      )}
    </div>
  )
}
