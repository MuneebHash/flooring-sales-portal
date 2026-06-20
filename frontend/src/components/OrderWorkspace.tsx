import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AppHeader } from './AppHeader'
import { ArrowLeftIcon } from './icons'
import { Button } from './ui/Button'
import { Panel } from './ui/Panel'
import { Tabs } from './ui/Tabs'
import { CustomerTab, type CustomerSavedPayload } from './workspace/CustomerTab'
import {
  DetailsOfSaleTab,
  buildDetailsBody,
  formFromProps,
} from './workspace/DetailsOfSaleTab'
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
import { useTenantSlug } from '../lib/useTenantSlug'
import { fetchOrderWorkspace, saveDetailsOfSale } from '../lib/api/orderWorkspaceApi'
import type {
  DetailsOfSaleFields,
  DetailsOfSaleSaveRequest,
  OrderAddress,
  OrderCustomer,
  OrderEnquiry,
  OrderWorkspace as OrderWorkspaceData,
} from '../lib/api/orderWorkspaceApi'
import { fetchOrderLines } from '../lib/api/orderLinesApi'
import type { OrderFinancialSummary } from '../lib/api/orderLinesApi'

// Friendly message for a failed details autosave. The autosave single-flight
// lock/loop lives in the always-mounted WorkspaceShell (below) so it survives the
// Details tab unmounting on a tab switch.
function detailsAutosaveErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.message.length > 0) return err.message
  return 'Could not save details. Please try again.'
}

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

const SALE_TOTAL_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

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
  // Phase 15F lead enquiry (nullable until first saved), shown in the Customer tab.
  enquiry: OrderEnquiry | null
  saleDetails: DetailsOfSaleFields | null
  // Order-bound salesperson name for the invoice document header (Phase 15C PR2).
  salespersonName: string | null
  // Lifts confirmed server-saved customer/address/enquiry data up so workspace
  // state (and sibling tabs) stay in sync without a refetch.
  onCustomerSaved: (saved: CustomerSavedPayload) => void
  // Lifts the server-confirmed details-of-sale fields (and refreshed updated_at)
  // up so workspace state stays in sync without a refetch.
  onDetailsSaved: (saved: {
    fields: DetailsOfSaleFields
    updated_at: string
  }) => void
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
  enquiry,
  saleDetails,
  salespersonName,
  onCustomerSaved,
  onDetailsSaved,
}: ShellProps) {
  const slug = useTenantSlug()
  const [activeTab, setActiveTab] = useState<TabId>('customer')
  // Live order financial summary that backs the always-visible header Sale total.
  // Sourced from the Chunk 3 order_financial_summary, never from the nullable
  // persisted_financials.
  const [financialSummary, setFinancialSummary] =
    useState<OrderFinancialSummary | null>(null)

  // Read-vs-mutation recency for order_financial_summary applies. KEY RULE: a
  // read-only GET /lines must never make a mutation summary stale. A MUTATION
  // response (sale-price override/reset, product/charge add/edit/delete) is the
  // newest persisted state, so it ALWAYS applies and bumps the mutation version.
  // A READ (seed / ProductsChargesTab load) captures the mutation version at issue
  // time and applies only if no mutation has applied since — so a later read can
  // neither overwrite a mutation nor cause an in-flight mutation response to be
  // discarded. (True cross-surface mutation commit-ordering needs backend summary
  // versioning and is intentionally out of scope here.) The ref survives tab
  // remounts because this shell stays mounted across tab switches.
  const financialSummaryMutationVersionRef = useRef(0)
  const applyMutationFinancialSummary = useCallback(
    (summary: OrderFinancialSummary) => {
      financialSummaryMutationVersionRef.current += 1
      setFinancialSummary(summary)
    },
    [],
  )
  const beginFinancialSummaryRead = useCallback(
    () => financialSummaryMutationVersionRef.current,
    [],
  )
  const applyReadFinancialSummary = useCallback(
    (readVersion: number, summary: OrderFinancialSummary) => {
      // Discard the read if any mutation applied after this read began.
      if (readVersion !== financialSummaryMutationVersionRef.current) return false
      setFinancialSummary(summary)
      return true
    },
    [],
  )

  // Sale-price override/reset IN-FLIGHT serialization — SEPARATE from the recency
  // guard above. It lives here (the always-mounted shell) so it survives
  // DetailsOfSaleTab unmount/remount and prevents a second override/reset while
  // one is still in flight. Recency = "is this the newest summary across all
  // sources"; in-flight = "can the user fire another sale-price action / are the
  // controls disabled". Both are wired; neither replaces the other.
  const [salePriceMutationInFlight, setSalePriceMutationInFlight] =
    useState(false)
  const beginSalePriceMutation = useCallback(() => {
    setSalePriceMutationInFlight(true)
  }, [])
  const finishSalePriceMutation = useCallback(() => {
    setSalePriceMutationInFlight(false)
  }, [])

  // --- Details-of-sale autosave single-flight (Codex P1). ---
  // The lock + queue + last-persisted key live HERE in the always-mounted shell
  // (DetailsOfSaleTab unmounts on tab switch), so switching away and back can never
  // start a second concurrent saveDetailsOfSale PUT. Only one PUT is ever in
  // flight; concurrent autosaves collapse to the latest body and drain afterwards.
  // PER-ORDER: WorkspaceShell is keyed by orderId (see render), so all of this is
  // recreated fresh on every order switch and lastSavedDetailsRef is initialised
  // from THIS order's persisted details — nothing bleeds across orders.
  // Details-of-sale DRAFT form state is hoisted HERE so the latest unsaved/in-flight
  // draft survives DetailsOfSaleTab unmount/remount (the tab is a controlled view
  // over it). The initialiser runs once per WorkspaceShell instance, and the shell
  // is keyed by orderId, so the draft seeds from THIS order's saved details and
  // resets per order. It is intentionally NOT re-synced from saleDetails after mount,
  // so a save that settles later (lifting onDetailsSaved → saleDetails) cannot clobber
  // newer local edits the user has made in the meantime.
  const [detailsDraft, setDetailsDraft] = useState(() =>
    formFromProps(saleDetails),
  )
  // Mirror the latest draft into a ref so flushDetailsAutosave re-checks persistence
  // against the FRESHEST draft rather than a click-time closure value. Without this,
  // a re-edit during the in-flight flush could make the flush report a spurious
  // "could not save" and block a valid Create/Rewrite (a benign false negative — the
  // snapshot is never stale; this just avoids the needless re-click). Written during
  // render (cache-latest pattern); only read in async handlers, never for rendering.
  const detailsDraftRef = useRef(detailsDraft)
  detailsDraftRef.current = detailsDraft
  const detailsSavingRef = useRef(false)
  const pendingDetailsBodyRef = useRef<DetailsOfSaleSaveRequest | null>(null)
  const lastSavedDetailsRef = useRef<string>(
    JSON.stringify(buildDetailsBody(formFromProps(saleDetails))),
  )
  // Tracks the in-flight autosave drain as an awaitable promise so a Create/Rewrite
  // invoice action can flush pending Details of Sale saves before the backend
  // snapshots them (Codex P1). null when no chain is active; resolves only once the
  // WHOLE queue (the in-flight body + any collapsed pending bodies) has drained.
  const detailsSaveChainRef = useRef<Promise<void> | null>(null)
  const [detailsAutosaveSaving, setDetailsAutosaveSaving] = useState(false)
  const [detailsAutosaveSaved, setDetailsAutosaveSaved] = useState(false)
  const [detailsAutosaveError, setDetailsAutosaveError] = useState<string | null>(
    null,
  )

  // Tracks whether the SHELL (not the tab) is still mounted, so a save that settles
  // after an order switch does not lift stale fields into a different order's
  // workspace. The PUT itself still completes (the edit is persisted); only the
  // lift + status setState are skipped. A tab switch keeps the shell mounted, so
  // the lift still happens — which is the whole point of hoisting the lock here.
  const detailsShellMountedRef = useRef(true)
  useEffect(() => {
    detailsShellMountedRef.current = true
    return () => {
      detailsShellMountedRef.current = false
    }
  }, [])

  // Single-flight save loop (mirrors the sale-price drain). Sends exactly one PUT;
  // on settle, drains the latest queued body only if it differs from what is now
  // persisted. lastSavedDetailsRef is set ONLY after a successful response. The
  // recursive drain is AWAITED (not fire-and-forget), so the chain promise tracked
  // in detailsSaveChainRef resolves only once the whole queue has drained — that is
  // exactly what flushDetailsAutosave awaits (Codex P1).
  async function runDetailsSave(body: DetailsOfSaleSaveRequest) {
    detailsSavingRef.current = true
    if (detailsShellMountedRef.current) {
      setDetailsAutosaveSaving(true)
      setDetailsAutosaveSaved(false)
      setDetailsAutosaveError(null)
    }
    try {
      const res = await saveDetailsOfSale(orderId, body)
      // Mark persisted ONLY after a successful backend response.
      lastSavedDetailsRef.current = JSON.stringify(body)
      if (detailsShellMountedRef.current) {
        onDetailsSaved({
          fields: res.data.details_of_sale_fields,
          updated_at: res.data.updated_at,
        })
        setDetailsAutosaveSaved(true)
      }
    } catch (err) {
      // Do NOT mark this body persisted on failure.
      if (detailsShellMountedRef.current) {
        setDetailsAutosaveError(detailsAutosaveErrorMessage(err))
      }
    } finally {
      detailsSavingRef.current = false
      const pending = pendingDetailsBodyRef.current
      pendingDetailsBodyRef.current = null
      if (
        pending !== null &&
        JSON.stringify(pending) !== lastSavedDetailsRef.current
      ) {
        // AWAIT the drain so this chain's promise spans the full queue.
        await runDetailsSave(pending)
      } else if (detailsShellMountedRef.current) {
        setDetailsAutosaveSaving(false)
      }
    }
  }

  // Starts a save chain and records its promise so flushDetailsAutosave can await
  // the full drain. The ref is cleared when THIS chain settles (only if it is still
  // the current one). runDetailsSave never rejects (it catches its own error), so
  // the chain promise always resolves.
  function startDetailsSaveChain(body: DetailsOfSaleSaveRequest) {
    const chain = runDetailsSave(body)
    detailsSaveChainRef.current = chain
    void chain.finally(() => {
      if (detailsSaveChainRef.current === chain) {
        detailsSaveChainRef.current = null
      }
    })
  }

  // Parent-level autosave entry point passed to DetailsOfSaleTab. The tab still
  // validates and builds the full body; this owns the single-flight lock/queue so
  // it survives the tab's unmount/remount.
  function queueDetailsAutosave(body: DetailsOfSaleSaveRequest) {
    if (detailsSavingRef.current) {
      // A save is in flight — queue ONLY the latest body (collapse to latest).
      pendingDetailsBodyRef.current = body
      return
    }
    // Nothing changed since the last persisted save — skip the network call.
    if (JSON.stringify(body) === lastSavedDetailsRef.current) return
    startDetailsSaveChain(body)
  }

  // Awaitable flush of any pending/in-flight Details autosave, used by
  // DetailsOfSaleTab BEFORE Create / Rewrite so the official invoice never snapshots
  // stale Details of Sale data (Codex P1). Force-commits the latest draft if it
  // differs from the last persisted save (covers a click landing before the field's
  // blur autosave settles), then awaits the active save chain (which internally
  // drains all queued bodies). Returns true iff the latest draft is persisted
  // afterwards — a failed save leaves lastSavedDetailsRef unchanged, so this returns
  // false and the caller blocks the invoice action.
  async function flushDetailsAutosave(): Promise<boolean> {
    const startBody = buildDetailsBody(detailsDraftRef.current)
    if (JSON.stringify(startBody) !== lastSavedDetailsRef.current) {
      queueDetailsAutosave(startBody)
    }
    const chain = detailsSaveChainRef.current
    if (chain) {
      await chain
    }
    // Re-check against the FRESHEST draft (ref, not the click-time closure) so a
    // re-edit during the in-flight flush does not yield a spurious false negative.
    return (
      JSON.stringify(buildDetailsBody(detailsDraftRef.current)) ===
      lastSavedDetailsRef.current
    )
  }

  // Seed the header summary without waiting for the Products & Charges tab to be
  // mounted (it only mounts when its tab is active). The header is non-critical,
  // so a failed fetch is swallowed and the placeholder remains. The seed is a READ:
  // it captures the mutation version at issue time and is discarded if a mutation
  // applied meanwhile, so a slow seed can never clobber a newer mutation summary.
  useEffect(() => {
    let cancelled = false
    setFinancialSummary(null)
    const readVersion = beginFinancialSummaryRead()
    fetchOrderLines(orderId)
      .then((res) => {
        if (cancelled) return
        applyReadFinancialSummary(readVersion, res.data.order_financial_summary)
      })
      .catch(() => {
        // Header summary is best-effort; keep the placeholder on failure.
      })
    return () => {
      cancelled = true
    }
  }, [orderId, beginFinancialSummaryRead, applyReadFinancialSummary])

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader />

        <Panel className="px-6 py-5">
          <Link
            to={`/${slug}/dashboard`}
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
              {/* Sale total comes from the Chunk 3 order_financial_summary,
                  lifted from the Products & Charges tab once its lines load.
                  Until a summary is available, show a non-financial placeholder
                  rather than a stale/zero money value. */}
              {financialSummary ? (
                <>
                  <div className="text-2xl font-bold tabular-nums text-slate-900 mt-0.5">
                    {SALE_TOTAL_FORMATTER.format(
                      financialSummary.final_sale_price_inc_gst,
                    )}
                  </div>
                  <div className="mt-0.5 text-[11px] text-slate-500">
                    Inc GST
                  </div>
                </>
              ) : (
                <>
                  <div className="text-2xl font-bold tabular-nums text-slate-400 mt-0.5">
                    —
                  </div>
                  <div className="mt-0.5 text-[11px] text-slate-500">
                    Not priced yet
                  </div>
                </>
              )}
            </div>
          </div>
        </Panel>

        <Panel className="overflow-hidden">
          <Tabs tabs={TABS} active={activeTab} onChange={setActiveTab} />
          <div className="p-6">
            {/* Customer is ALWAYS mounted (hidden when not active), unlike the other
                tabs which stay conditionally rendered. This keeps the Lead Enquiry
                autosave draft / local form state alive across top-level tab switches
                (Customer -> Products & Charges -> Customer must NOT remount
                LeadEnquirySection), so an in-flight unmount-flush can't race a fresh
                stale-prop seed. Uses the same `activeTab === 'customer'` condition. */}
            <div className={activeTab === 'customer' ? '' : 'hidden'}>
              <CustomerTab
                key={orderId}
                orderId={orderId}
                locked={locked}
                customer={customer}
                installationAddress={installationAddress}
                billingAddress={billingAddress}
                enquiry={enquiry}
                onSaved={onCustomerSaved}
              />
            </div>
            {activeTab === 'products' && (
              <ProductsChargesTab
                orderId={orderId}
                flooringType={flooringType}
                locked={locked}
                beginFinancialSummaryRead={beginFinancialSummaryRead}
                applyReadFinancialSummary={applyReadFinancialSummary}
                applyMutationFinancialSummary={applyMutationFinancialSummary}
              />
            )}
            {activeTab === 'details' && (
              <DetailsOfSaleTab
                orderId={orderId}
                locked={locked}
                detailsDraft={detailsDraft}
                setDetailsDraft={setDetailsDraft}
                financialSummary={financialSummary}
                salePriceMutationInFlight={salePriceMutationInFlight}
                beginSalePriceMutation={beginSalePriceMutation}
                finishSalePriceMutation={finishSalePriceMutation}
                applyMutationFinancialSummary={applyMutationFinancialSummary}
                queueDetailsAutosave={queueDetailsAutosave}
                flushDetailsAutosave={flushDetailsAutosave}
                detailsAutosaveSaving={detailsAutosaveSaving}
                detailsAutosaveSaved={detailsAutosaveSaved}
                detailsAutosaveError={detailsAutosaveError}
                onInvoiceReady={() => setActiveTab('invoice')}
              />
            )}
            {/* `locked` (LAID) is passed for PHOTO-DELETE gating ONLY. Notes are
                LAID-exempt and photo list/upload/preview are all allowed on a
                LAID order, so the tab must never use `locked` to disable note-add
                or photo upload — only the photo delete control is gated by it. */}
            {activeTab === 'notes' && (
              <NotesPhotosTab orderId={orderId} locked={locked} />
            )}
            {activeTab === 'payments' && <PaymentsTab orderId={orderId} />}
            {activeTab === 'invoice' && (
              <InvoiceTab
                orderId={orderId}
                flooringType={flooringType}
                orderNumber={orderNumber}
                customer={customer}
                billingAddress={billingAddress}
                saleDetails={saleDetails}
                salespersonName={salespersonName}
                // Lets an accept/resend customer-email error send the user
                // straight to where the email is fixed.
                onGoToCustomer={() => setActiveTab('customer')}
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
      if (saved.enquiry !== undefined) next.enquiry = saved.enquiry
      return next
    })
  }

  // Details-of-sale saves are confirmed server-side. Fold the server-confirmed
  // fields back into workspace state as flat header fields (matching how the
  // workspace stores them and how saleDetails is recomputed per render), plus
  // the refreshed updated_at, so the tab re-seeds correctly and sibling tabs see
  // fresh data without a refetch.
  function handleDetailsSaved(saved: {
    fields: DetailsOfSaleFields
    updated_at: string
  }) {
    setWorkspace((prev) => {
      if (!prev) return prev
      return {
        ...prev,
        supply_only: saved.fields.supply_only,
        plan_numbers: saved.fields.plan_numbers,
        proposed_lay_date: saved.fields.proposed_lay_date,
        lay_date_status: saved.fields.lay_date_status,
        details_of_sale: saved.fields.details_of_sale,
        updated_at: saved.updated_at,
      }
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
      enquiry={workspace.enquiry}
      saleDetails={saleDetails}
      salespersonName={workspace.salesperson_name}
      onCustomerSaved={handleCustomerSaved}
      onDetailsSaved={handleDetailsSaved}
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
  const slug = useTenantSlug()
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
          onClick={() => navigate(`/${slug}/dashboard`)}
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
  const slug = useTenantSlug()
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
          onClick={() => navigate(`/${slug}/dashboard`)}
        >
          Back to dashboard
        </Button>
      </div>
    </WorkspaceMessage>
  )
}

function InvalidWorkspace() {
  const navigate = useNavigate()
  const slug = useTenantSlug()
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
          onClick={() => navigate(`/${slug}/dashboard`)}
        >
          Back to dashboard
        </Button>
      </div>
    </WorkspaceMessage>
  )
}

function NotFoundWorkspace() {
  const navigate = useNavigate()
  const slug = useTenantSlug()
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
          onClick={() => navigate(`/${slug}/dashboard`)}
        >
          Back to dashboard
        </Button>
      </div>
    </WorkspaceMessage>
  )
}
