import { Button } from '../ui/Button'
import { ChevronDownIcon } from '../icons'
import type {
  Address,
  CustomerDetails,
  InvoiceSummary,
  InvoiceVersion,
  SaleDetails,
} from '../../data/mockOrderDetails'

type Props = {
  orderNumber?: string
  customer?: CustomerDetails | null
  billingAddress?: Address | null
  saleDetails?: SaleDetails | null
  invoiceSummary?: InvoiceSummary | null
  invoiceVersions?: InvoiceVersion[] | null
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

function formatTimestamp(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso)
  if (!match) return ''
  const [, year, month, day, hours, minutes] = match
  const name = MONTH_NAMES[Number(month) - 1]
  if (!name) return ''
  return `${day} ${name} ${year}, ${hours}:${minutes}`
}

function composeFullName(customer: CustomerDetails | null | undefined): string {
  if (!customer) return ''
  return [customer.first_name, customer.middle_name, customer.last_name]
    .filter((part) => part && part.trim().length > 0)
    .join(' ')
}

function composeAddressLines(
  address: Address | null | undefined,
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
  orderNumber,
  customer,
  billingAddress,
  saleDetails,
  invoiceSummary,
  invoiceVersions,
}: Props) {
  const summary: InvoiceSummary = invoiceSummary ?? {
    invoice_total: 0,
    total_paid: 0,
    balance_due: 0,
    current_version: null,
  }
  const versions = invoiceVersions ?? []
  const currentVersion = versions[0] ?? null
  const invoiceIssued = currentVersion !== null

  const customerName = composeFullName(customer)
  const billingAddressLines = composeAddressLines(billingAddress)

  const detailsOfSaleText =
    currentVersion?.details_of_sale_snapshot ??
    saleDetails?.details_of_sale ??
    ''

  return (
    <div>
      <div className="mb-4">
        <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
          Invoice
        </h2>
        <p className="text-sm text-slate-500 mt-1">
          View the current invoice for this order.
        </p>
      </div>

      {invoiceIssued && currentVersion ? (
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
                  {formatDocDate(currentVersion.invoice_date)}
                </span>
              </div>
              <div className="text-sm text-slate-600">
                Due Date :{' '}
                <span className="text-slate-900 tabular-nums">
                  {formatDocDate(currentVersion.due_date)}
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
                {formatMoney(summary.invoice_total)}
              </span>
            </div>
            <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
              <span className="text-sm text-slate-700">Payment Made</span>
              <span className="text-sm font-medium tabular-nums text-rose-600">
                {formatMoney(summary.total_paid)}
              </span>
            </div>
            <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
              <span className="text-sm text-slate-700">Balance Due</span>
              <span className="text-sm font-semibold tabular-nums text-slate-900">
                {formatMoney(summary.balance_due)}
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
            An invoice will appear here once it has been created for this
            order.
          </p>
        </div>
      )}

      <details className="group mt-5 rounded-lg border border-slate-200 bg-white">
        <summary className="flex items-center justify-between gap-3 px-4 py-2.5 cursor-pointer text-sm text-slate-700 list-none [&::-webkit-details-marker]:hidden [&::marker]:hidden">
          <span>
            Invoice version history{' '}
            <span className="text-slate-500">({versions.length})</span>
          </span>
          <ChevronDownIcon className="w-4 h-4 text-slate-500 transition-transform group-open:rotate-180" />
        </summary>
        <div className="border-t border-slate-200 px-4 py-3">
          {versions.length === 0 ? (
            <p className="text-xs text-slate-500">No invoice history yet.</p>
          ) : (
            <ul className="space-y-1.5">
              {[...versions]
                .sort((a, b) => b.version_number - a.version_number)
                .map((version) => (
                  <li
                    key={version.invoice_id}
                    className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-700"
                  >
                    <span className="font-mono text-slate-700">
                      v{version.version_number}
                    </span>
                    <span className="text-slate-500">·</span>
                    <span className="tabular-nums">
                      {formatTimestamp(version.created_at)}
                    </span>
                    <span className="text-slate-500">·</span>
                    <span className="tabular-nums">
                      Total {formatMoney(version.sale_price_inc_gst)}
                    </span>
                    <span className="text-slate-500">·</span>
                    <span className="tabular-nums">
                      Paid {formatMoney(version.total_paid)}
                    </span>
                    <span className="text-slate-500">·</span>
                    <span className="tabular-nums">
                      Balance {formatMoney(version.balance_due)}
                    </span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled
                      className="ml-auto"
                    >
                      Download PDF
                    </Button>
                  </li>
                ))}
            </ul>
          )}
        </div>
      </details>
    </div>
  )
}
