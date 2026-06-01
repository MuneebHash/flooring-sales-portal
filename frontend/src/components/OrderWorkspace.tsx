import { useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AppHeader } from './AppHeader'
import { ArrowLeftIcon } from './icons'
import { Button } from './ui/Button'
import { Panel } from './ui/Panel'
import { Tabs } from './ui/Tabs'
import { CustomerTab, type CustomerSavedPayload } from './workspace/CustomerTab'
import { DetailsOfSaleTab } from './workspace/DetailsOfSaleTab'
import { InvoiceTab } from './workspace/InvoiceTab'
import { NotesPhotosTab } from './workspace/NotesPhotosTab'
import { PaymentsTab } from './workspace/PaymentsTab'
import { ProductsChargesTab } from './workspace/ProductsChargesTab'
import {
  FLOORING_LABELS,
  FLOORING_TONES,
  type FlooringType,
} from '../lib/flooring'
import {
  STATUS_LABELS,
  STATUS_STYLES,
  type OrderStatus,
} from '../lib/statuses'
import { ApiError } from '../lib/api/ApiError'
import { fetchOrderWorkspace } from '../lib/api/orderWorkspaceApi'
import type {
  DetailsOfSaleFields,
  OrderAddress,
  OrderCustomer,
  OrderWorkspace as OrderWorkspaceData,
} from '../lib/api/orderWorkspaceApi'

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

type ShellProps = {
  orderId: number
  flooringType: FlooringType
  orderNumber: string
  orderStatus: OrderStatus
  // Locked drives the read-only badge here and disables Customer-tab edits/saves.
  locked: boolean
  customer: OrderCustomer | null
  installationAddress: OrderAddress | null
  billingAddress: OrderAddress | null
  saleDetails: DetailsOfSaleFields | null
  // Lifts confirmed server-saved customer/address data up so workspace state
  // (and sibling tabs) stay in sync without a refetch.
  onCustomerSaved: (saved: CustomerSavedPayload) => void
}

function WorkspaceShell({
  orderId,
  flooringType,
  orderNumber,
  orderStatus,
  locked,
  customer,
  installationAddress,
  billingAddress,
  saleDetails,
  onCustomerSaved,
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
              <h2 className="text-2xl font-bold font-mono tracking-tight text-slate-900">
                {orderNumber}
              </h2>
              <span
                className={`inline-flex items-center px-2.5 py-1 rounded-md border text-xs font-medium ${FLOORING_TONES[flooringType]}`}
              >
                {FLOORING_LABELS[flooringType]}
              </span>
              <span
                className={`inline-flex items-center px-2.5 py-1 rounded-md border text-xs font-medium ${STATUS_STYLES[orderStatus]}`}
              >
                {STATUS_LABELS[orderStatus]}
              </span>
              {locked && (
                <span className="inline-flex items-center px-2 py-1 rounded-md border border-slate-300 bg-slate-100 text-slate-600 text-[11px] font-medium">
                  Locked
                </span>
              )}
            </div>
            <div className="text-left sm:text-right">
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Sale total
              </div>
              {/* Sale total is produced by the backend financial summary in
                  Chunk 3. Phase 10E.1 wires no financial logic, so show a
                  non-financial placeholder rather than a money value. */}
              <div className="text-2xl font-bold tabular-nums text-slate-400 mt-0.5">
                —
              </div>
              <div className="mt-0.5 text-[11px] text-slate-500">
                Not priced yet
              </div>
            </div>
          </div>
        </Panel>

        <Panel className="overflow-hidden">
          <Tabs tabs={TABS} active={activeTab} onChange={setActiveTab} />
          <div className="p-6">
            {activeTab === 'customer' && (
              <CustomerTab
                orderId={orderId}
                locked={locked}
                customer={customer}
                installationAddress={installationAddress}
                billingAddress={billingAddress}
                onSaved={onCustomerSaved}
              />
            )}
            {activeTab === 'products' && (
              <ProductsChargesTab flooringType={flooringType} />
            )}
            {activeTab === 'details' && (
              <DetailsOfSaleTab saleDetails={saleDetails} />
            )}
            {activeTab === 'notes' && <NotesPhotosTab />}
            {activeTab === 'payments' && <PaymentsTab />}
            {activeTab === 'invoice' && (
              <InvoiceTab
                orderNumber={orderNumber}
                customer={customer}
                billingAddress={billingAddress}
                saleDetails={saleDetails}
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

  // The static /orders/new route mounts this component with no :orderId. New
  // orders are created from the New Order modal (POST /orders), which then
  // navigates to /orders/{order_id}, so this route is only a direct-link
  // fallback rather than a place to fake an unsaved order shell.
  if (params.orderId === undefined) {
    return <NewOrderNotice />
  }

  const raw = params.orderId
  const orderId = Number(raw)
  const isValidOrderId =
    /^\d+$/.test(raw) && Number.isSafeInteger(orderId) && orderId > 0
  if (!isValidOrderId) {
    return <InvalidWorkspace />
  }

  return <ExistingOrderWorkspace orderId={orderId} />
}

function ExistingOrderWorkspace({ orderId }: { orderId: number }) {
  const [workspace, setWorkspace] = useState<OrderWorkspaceData | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    setNotFound(false)
    setWorkspace(null)
    fetchOrderWorkspace(orderId)
      .then((response) => {
        if (cancelled) return
        setWorkspace(response.data)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if (err instanceof ApiError && err.code === 'ORDER_NOT_FOUND') {
          setNotFound(true)
          return
        }
        const message =
          err instanceof ApiError && err.message.length > 0
            ? err.message
            : 'Could not load this order. Please try again.'
        setLoadError(message)
      })
      .finally(() => {
        if (cancelled) return
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [orderId, reloadToken])

  if (loading) return <LoadingWorkspace />
  if (notFound) return <NotFoundWorkspace />
  if (loadError !== null) {
    return (
      <ErrorWorkspace
        message={loadError}
        onRetry={() => setReloadToken((token) => token + 1)}
      />
    )
  }
  if (!workspace) return <NotFoundWorkspace />

  const saleDetails: DetailsOfSaleFields = {
    supply_only: workspace.supply_only,
    plan_numbers: workspace.plan_numbers,
    proposed_lay_date: workspace.proposed_lay_date,
    lay_date_status: workspace.lay_date_status,
    details_of_sale: workspace.details_of_sale,
  }

  // Customer-tab saves are confirmed server-side and arrive one section at a
  // time as each call succeeds. Fold each provided row back into workspace state
  // in place (leaving unprovided sections untouched) so confirmed customer /
  // installation rows persist even when a later billing step fails, and sibling
  // tabs see fresh data without a refetch.
  function handleCustomerSaved(saved: CustomerSavedPayload) {
    setWorkspace((prev) => {
      if (!prev) return prev
      const next = { ...prev }
      if (saved.customer !== undefined) next.customer = saved.customer
      if (saved.installAddress !== undefined) {
        next.install_address = saved.installAddress
      }
      if (saved.billingAddress !== undefined) {
        next.billing_address = saved.billingAddress
      }
      return next
    })
  }

  return (
    <WorkspaceShell
      key={orderId}
      orderId={orderId}
      flooringType={workspace.flooring_type}
      orderNumber={workspace.order_number}
      orderStatus={workspace.order_status}
      locked={workspace.locked}
      customer={workspace.customer}
      installationAddress={workspace.install_address}
      billingAddress={workspace.billing_address}
      saleDetails={saleDetails}
      onCustomerSaved={handleCustomerSaved}
    />
  )
}

function WorkspaceMessage({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader />
        <Panel className="p-8 max-w-[520px] mx-auto text-center">
          {children}
        </Panel>
      </div>
    </div>
  )
}

function LoadingWorkspace() {
  return (
    <WorkspaceMessage>
      <p className="text-sm text-slate-500">Loading order…</p>
    </WorkspaceMessage>
  )
}

function ErrorWorkspace({
  message,
  onRetry,
}: {
  message: string
  onRetry: () => void
}) {
  const navigate = useNavigate()
  return (
    <WorkspaceMessage>
      <h2 className="text-xl font-semibold text-slate-900 tracking-tight">
        Couldn't load this order
      </h2>
      <p className="text-sm text-slate-500 mt-2">{message}</p>
      <div className="mt-5 flex items-center justify-center gap-3">
        <Button
          variant="secondary"
          size="md"
          onClick={() => navigate('/dashboard')}
        >
          Back to dashboard
        </Button>
        <Button variant="success" size="md" onClick={onRetry}>
          Try again
        </Button>
      </div>
    </WorkspaceMessage>
  )
}

function NewOrderNotice() {
  const navigate = useNavigate()
  return (
    <WorkspaceMessage>
      <h2 className="text-xl font-semibold text-slate-900 tracking-tight">
        Start a new order
      </h2>
      <p className="text-sm text-slate-500 mt-2">
        Create a new order from the dashboard by choosing Soft flooring or
        Hard flooring.
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
    </WorkspaceMessage>
  )
}

function InvalidWorkspace() {
  const navigate = useNavigate()
  return (
    <WorkspaceMessage>
      <h2 className="text-xl font-semibold text-slate-900 tracking-tight">
        Invalid order
      </h2>
      <p className="text-sm text-slate-500 mt-2">
        That order link doesn't look right. Choose an order from the
        dashboard.
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
    </WorkspaceMessage>
  )
}

function NotFoundWorkspace() {
  const navigate = useNavigate()
  return (
    <WorkspaceMessage>
      <h2 className="text-xl font-semibold text-slate-900 tracking-tight">
        Order not found
      </h2>
      <p className="text-sm text-slate-500 mt-2">
        We couldn't find that order. It may have been removed or the link is
        incorrect.
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
    </WorkspaceMessage>
  )
}
