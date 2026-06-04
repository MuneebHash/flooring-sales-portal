import { useState } from 'react'
import { Badge } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Field } from '../ui/Field'
import { Input } from '../ui/Input'
import { Select } from '../ui/Select'
import type { OrderPayment, PaymentMethod, PaymentSummary } from './types'

type Props = {
  payments?: OrderPayment[] | null
  paymentSummary?: PaymentSummary | null
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

export function PaymentsTab({ payments, paymentSummary }: Props) {
  const paymentList = payments ?? []
  const summary: PaymentSummary = paymentSummary ?? {
    total_paid: 0,
    balance_due: null,
  }
  const invoiceIssued = summary.balance_due !== null

  const [method, setMethod] = useState<PaymentMethod | ''>('')
  const [amount, setAmount] = useState('')
  const [reference, setReference] = useState('')

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

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-5">
        <div className="rounded-lg border border-slate-200 bg-slate-50/60 p-4">
          <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
            Total paid
          </div>
          <div className="mt-1 text-2xl font-bold tabular-nums text-slate-900">
            {formatMoney(summary.total_paid)}
          </div>
        </div>

        <div className="rounded-lg border border-slate-200 bg-slate-50/60 p-4">
          <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
            Balance due
          </div>
          {summary.balance_due === null ? (
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
              {formatMoney(summary.balance_due)}
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
          <div className="mt-1.5 text-[11px] text-slate-500 leading-snug">
            Deposit requirements will appear once payments are wired.
          </div>
        </div>
      </div>

      <div className="space-y-5">
        <section className="rounded-xl border border-slate-200 bg-slate-50/60 p-5">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-slate-900">
              Record payment
            </h3>
            <span className="text-[11px] font-medium text-slate-500">
              Visual prototype only — not saved.
            </span>
          </div>

          <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <Field label="Payment method" htmlFor="payment_method">
                <Select
                  id="payment_method"
                  value={method}
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
                  inputMode="decimal"
                  placeholder="0.00"
                  value={amount}
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
                  placeholder="e.g. EFTPOS-20260514"
                  value={reference}
                  onChange={(e) => setReference(e.target.value)}
                />
              </Field>
            </div>
            <div className="mt-4 flex items-center justify-end gap-3">
              <span className="text-[11px] text-slate-500">
                No backend wired up yet — submit is disabled.
              </span>
              <Button type="button" variant="success" size="md" disabled>
                Record payment
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
              {paymentList.length}{' '}
              {paymentList.length === 1 ? 'payment' : 'payments'}
            </span>
          </div>

          {paymentList.length === 0 ? (
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
                  {paymentList.map((payment) => (
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
    </div>
  )
}
