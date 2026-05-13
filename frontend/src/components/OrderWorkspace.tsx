import { useState } from 'react'
import {
  Link,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom'
import { AppHeader } from './AppHeader'
import { ArrowLeftIcon } from './icons'
import { Button } from './ui/Button'
import { Panel } from './ui/Panel'
import { Tabs } from './ui/Tabs'
import { CustomerTab } from './workspace/CustomerTab'
import { DetailsOfSaleTab } from './workspace/DetailsOfSaleTab'
import { NotesPhotosTab } from './workspace/NotesPhotosTab'
import { PaymentsTab } from './workspace/PaymentsTab'
import { PlaceholderTab } from './workspace/PlaceholderTab'
import {
  FLOORING_LABELS,
  FLOORING_TONES,
  type FlooringType,
} from '../lib/flooring'
import type {
  Address,
  CustomerDetails,
  OrderAttachment,
  OrderNote,
  OrderPayment,
  PaymentSummary,
  SaleDetails,
} from '../data/mockOrderDetails'
import { MOCK_ORDER_DETAILS } from '../data/mockOrderDetails'

const TAB_IDS = [
  'customer',
  'products',
  'details',
  'notes',
  'payments',
  'invoice',
] as const

type TabId = (typeof TAB_IDS)[number]

const TAB_LABELS: Record<TabId, string> = {
  customer: 'Customer',
  products: 'Products & Charges',
  details: 'Details of Sale',
  notes: 'Notes & Photos',
  payments: 'Payments',
  invoice: 'Invoice',
}

const TABS = TAB_IDS.map((id) => ({ id, label: TAB_LABELS[id] }))

function isFlooringType(value: string | null): value is FlooringType {
  return value === 'SOFT' || value === 'HARD'
}

type ShellProps = {
  mode: 'new' | 'existing'
  flooringType: FlooringType
  orderNumber?: string
  customer?: CustomerDetails | null
  installationAddress?: Address | null
  billingAddress?: Address | null
  saleDetails?: SaleDetails | null
  notes?: OrderNote[] | null
  attachments?: OrderAttachment[] | null
  payments?: OrderPayment[] | null
  paymentSummary?: PaymentSummary | null
}

function WorkspaceShell({
  mode,
  flooringType,
  orderNumber,
  customer,
  installationAddress,
  billingAddress,
  saleDetails,
  notes,
  attachments,
  payments,
  paymentSummary,
}: ShellProps) {
  const [activeTab, setActiveTab] = useState<TabId>('customer')

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader />

        <Panel className="px-6 py-5">
          <Link
            to="/dashboard"
            className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-500 hover:text-slate-800 transition-colors"
          >
            <ArrowLeftIcon className="w-3.5 h-3.5" />
            Back to dashboard
          </Link>
          <div className="mt-3 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div className="flex items-center gap-3 flex-wrap">
              {mode === 'new' ? (
                <h2 className="text-2xl font-bold text-slate-900 tracking-tight">
                  New order
                </h2>
              ) : (
                <h2 className="text-2xl font-bold font-mono tracking-tight text-slate-900">
                  {orderNumber}
                </h2>
              )}
              <span
                className={`inline-flex items-center px-2.5 py-1 rounded-md border text-xs font-medium ${FLOORING_TONES[flooringType]}`}
              >
                {FLOORING_LABELS[flooringType]}
              </span>
            </div>
            <div className="text-left sm:text-right">
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Sale total
              </div>
              <div className="text-2xl font-bold tabular-nums text-slate-900 mt-0.5">
                $0.00
              </div>
            </div>
          </div>
        </Panel>

        <Panel className="overflow-hidden">
          <Tabs tabs={TABS} active={activeTab} onChange={setActiveTab} />
          <div className="p-6">
            {activeTab === 'customer' && (
              <CustomerTab
                customer={customer}
                installationAddress={installationAddress}
                billingAddress={billingAddress}
              />
            )}
            {activeTab === 'products' && (
              <PlaceholderTab
                title="Products & Charges"
                text="Products and charges will be added here."
              />
            )}
            {activeTab === 'details' && (
              <DetailsOfSaleTab saleDetails={saleDetails} />
            )}
            {activeTab === 'notes' && (
              <NotesPhotosTab notes={notes} attachments={attachments} />
            )}
            {activeTab === 'payments' && (
              <PaymentsTab
                payments={payments}
                paymentSummary={paymentSummary}
              />
            )}
            {activeTab === 'invoice' && (
              <PlaceholderTab
                title="Invoice"
                text="Invoice creation and history will appear here."
              />
            )}
          </div>
        </Panel>
      </div>
    </div>
  )
}

export function OrderWorkspace() {
  const params = useParams<{ orderId: string }>()
  const [searchParams] = useSearchParams()

  if (params.orderId !== undefined) {
    const orderId = Number.parseInt(params.orderId, 10)
    const details = Number.isFinite(orderId)
      ? MOCK_ORDER_DETAILS[orderId]
      : undefined
    if (!details) return <NotFoundWorkspace />
    return (
      <WorkspaceShell
        key={`existing-${orderId}`}
        mode="existing"
        flooringType={details.flooring_type}
        orderNumber={details.order_number}
        customer={details.customer}
        installationAddress={details.installation_address}
        billingAddress={details.billing_address}
        saleDetails={details.sale_details}
        notes={details.notes}
        attachments={details.attachments}
        payments={details.payments}
        paymentSummary={details.payment_summary}
      />
    )
  }

  const flooringTypeRaw = searchParams.get('flooring_type')
  if (!isFlooringType(flooringTypeRaw)) {
    return <InvalidWorkspace />
  }
  return (
    <WorkspaceShell
      key={`new-${flooringTypeRaw}`}
      mode="new"
      flooringType={flooringTypeRaw}
    />
  )
}

function InvalidWorkspace() {
  const navigate = useNavigate()
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader />
        <Panel className="p-8 max-w-[520px] mx-auto text-center">
          <h2 className="text-xl font-semibold text-slate-900 tracking-tight">
            Invalid order type
          </h2>
          <p className="text-sm text-slate-500 mt-2">
            Choose Soft flooring or Hard flooring from the Dashboard to start
            a new order.
          </p>
          <div className="mt-5">
            <Button
              variant="success"
              size="md"
              onClick={() => navigate('/dashboard')}
            >
              Back to dashboard
            </Button>
          </div>
        </Panel>
      </div>
    </div>
  )
}

function NotFoundWorkspace() {
  const navigate = useNavigate()
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader />
        <Panel className="p-8 max-w-[520px] mx-auto text-center">
          <h2 className="text-xl font-semibold text-slate-900 tracking-tight">
            Order not found
          </h2>
          <p className="text-sm text-slate-500 mt-2">
            We couldn't find that order. It may have been removed or the link
            is incorrect.
          </p>
          <div className="mt-5">
            <Button
              variant="success"
              size="md"
              onClick={() => navigate('/dashboard')}
            >
              Back to dashboard
            </Button>
          </div>
        </Panel>
      </div>
    </div>
  )
}
