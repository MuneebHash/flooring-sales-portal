import {
  useEffect,
  useRef,
  useState,
  type Dispatch,
  type SetStateAction,
} from 'react'
import { Button } from '../ui/Button'
import { Field } from '../ui/Field'
import { Input } from '../ui/Input'
import { Select } from '../ui/Select'
import { Textarea } from '../ui/Textarea'
import { Modal } from '../ui/Modal'
import { CheckCircleIcon, InfoIcon } from '../icons'
import { ApiError } from '../../lib/api/ApiError'
import type {
  DetailsOfSaleFields,
  DetailsOfSaleSaveRequest,
  LayDateStatus,
} from '../../lib/api/orderWorkspaceApi'
import { overrideSalePrice, resetSalePrice } from '../../lib/api/orderLinesApi'
import type { OrderFinancialSummary } from '../../lib/api/orderLinesApi'
import {
  createInvoice,
  fetchCurrentInvoice,
  rewriteInvoice,
  type InvoiceDetail,
} from '../../lib/api/orderInvoicesApi'

type Props = {
  orderId: number
  locked: boolean
  // Details-of-sale draft form state is HOISTED to OrderWorkspace (the always-
  // mounted shell) so the latest unsaved/in-flight draft survives this tab's
  // unmount/remount. The tab is a controlled view over this draft and must NOT
  // re-seed from stale saved props on remount.
  detailsDraft: DetailsForm
  setDetailsDraft: Dispatch<SetStateAction<DetailsForm>>
  // Live order financial summary (Chunk 3 order_financial_summary), seeded and
  // kept fresh by OrderWorkspace. Null until the order has been priced.
  financialSummary: OrderFinancialSummary | null
  // Sale-price IN-FLIGHT serialization lives in OrderWorkspace (the always-mounted
  // shell) so it survives this tab's unmount/remount and prevents a second
  // override/reset while one is in flight.
  salePriceMutationInFlight: boolean
  beginSalePriceMutation: () => void
  finishSalePriceMutation: () => void
  // Sale-price override/reset responses are MUTATIONS: applying one bumps the
  // shared mutation version in OrderWorkspace, so a later read-only GET /lines can
  // never discard it. Applied before the mountedRef child-local guard.
  applyMutationFinancialSummary: (summary: OrderFinancialSummary) => void
  // Details autosave single-flight lives in OrderWorkspace (the always-mounted
  // shell) so it survives this tab's unmount/remount. The tab validates + builds
  // the full body and hands it to queueDetailsAutosave; the status props drive the
  // minimal Saving… / Saved / error indicator. The parent owns the lift to
  // workspace state on success.
  queueDetailsAutosave: (body: DetailsOfSaleSaveRequest) => void
  // Awaitable flush of any pending/in-flight Details autosave (lives in the always-
  // mounted shell). Resolves true once the latest draft is persisted, false if a
  // save failed. Awaited before a Create/Rewrite invoice action so the snapshot is
  // never stale (Codex P1).
  flushDetailsAutosave: () => Promise<boolean>
  detailsAutosaveSaving: boolean
  detailsAutosaveSaved: boolean
  detailsAutosaveError: string | null
  // Called ONLY after a successful invoice create/rewrite so the parent (the
  // always-mounted shell) can switch to the Invoice tab, where the new/rewritten
  // invoice mounts + refetches and is shown immediately. Not invoked on failure.
  onInvoiceReady: () => void
}

// Local form mirror of the five details-of-sale fields. lay_date_status keeps the
// empty-string "no selection" state of the <select>; it normalises to null at
// build time.
type DetailsForm = {
  supply_only: boolean
  plan_numbers: string
  proposed_lay_date: string
  lay_date_status: '' | LayDateStatus
  details_of_sale: string
}

const QUICK_DESCRIPTIONS = [
  'Floor to be clear and clean for installers arrival.',
  'Furniture to be moved by installer.',
  'Site measure required before installation.',
  'No pull up and disposal required.',
  'Additional floor preparation may be charged if required.',
]

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

function formatIsoDate(iso: string | null | undefined): string {
  if (!iso) return ''
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!match) return ''
  const [, year, month, day] = match
  const name = MONTH_NAMES[Number(month) - 1]
  if (!name) return ''
  return `${day} ${name} ${year}`
}

export function formFromProps(d?: DetailsOfSaleFields | null): DetailsForm {
  return {
    supply_only: d?.supply_only ?? false,
    plan_numbers: d?.plan_numbers ?? '',
    proposed_lay_date: d?.proposed_lay_date ?? '',
    lay_date_status: d?.lay_date_status ?? '',
    details_of_sale: d?.details_of_sale ?? '',
  }
}

// Optional text field: trim, then collapse blank/whitespace to null so a
// full-replace PUT clears it rather than tripping the backend non-blank check.
function optionalField(value: string): string | null {
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

// Always build the complete 5-field body (full-replace). Optionals collapse to
// null; supply_only is always a boolean; the <input type="date"> already yields
// either '' or a strict YYYY-MM-DD string, matching the backend's strict parse.
export function buildDetailsBody(detailsDraft: DetailsForm): DetailsOfSaleSaveRequest {
  return {
    supply_only: detailsDraft.supply_only,
    plan_numbers: optionalField(detailsDraft.plan_numbers),
    proposed_lay_date: detailsDraft.proposed_lay_date ? detailsDraft.proposed_lay_date : null,
    lay_date_status: detailsDraft.lay_date_status || null,
    details_of_sale: optionalField(detailsDraft.details_of_sale),
  }
}

// Client-side validation before any network call. The lay-date pair rule mirrors
// the backend (chk_sales_order_lay_date_pair) and is reported on lay_date_status,
// the same field the backend attaches it to.
function validateForm(detailsDraft: DetailsForm): Record<string, string> {
  const newErrors: Record<string, string> = {}
  const hasDate = detailsDraft.proposed_lay_date.trim().length > 0
  const hasStatus = detailsDraft.lay_date_status.length > 0
  if (hasDate !== hasStatus) {
    newErrors.lay_date_status =
      'Proposed lay date and lay date status must both be set or both be cleared.'
  }
  return newErrors
}

// Largest GST-inclusive sale price the backend accepts (DECIMAL(10,2)).
const SALE_PRICE_MAX = 99999999.99
const GST_MULTIPLIER = 1.1

const MONEY_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

// Formats money including negatives (Intl renders -$1,234.56). Derived values
// like GP and price adjustment can be negative and must never be clamped.
function formatMoney(value: number): string {
  return MONEY_FORMATTER.format(value)
}

// Inline message for the overpaid-rewrite guard. Uses the exact amounts when they
// are reliably known; otherwise an amount-free fallback. NO cost/GP wording.
function overpaidRewriteMessage(
  amounts: { paid: number; newTotal: number } | null,
): string {
  if (amounts) {
    return `The new invoice total (${formatMoney(amounts.newTotal)}) is lower than the amount already paid (${formatMoney(amounts.paid)}). Please adjust the sale total before rewriting the invoice.`
  }
  return 'The new invoice total is lower than the amount already paid. Please adjust the sale total before rewriting the invoice.'
}

// gp_percent is nullable (null when sale_price_ex_gst <= 0); render null as a
// dash. Negative percentages are shown verbatim.
function formatPercent(value: number | null): string {
  if (value === null) return '—'
  return `${value.toFixed(2)}%`
}

// GP health tone for the info modal — VISUAL ONLY. Does not affect the backend
// GP/GP% calculation or the backend gp_warning threshold; it only colours the
// modal's GP rows so a salesperson can read the result at a glance.
// >15 green · 10–15 amber · <10 (incl. negative) red · null neutral.
function gpTone(
  gpPercent: number | null,
): 'success' | 'warning' | 'danger' | 'neutral' {
  if (gpPercent === null) return 'neutral'
  if (gpPercent > 15) return 'success'
  if (gpPercent >= 10) return 'warning'
  return 'danger'
}

const GP_TONE_ROW: Record<ReturnType<typeof gpTone>, string> = {
  success: 'bg-emerald-50 border border-emerald-200',
  warning: 'bg-amber-50 border border-amber-200',
  danger: 'bg-rose-50 border border-rose-200',
  neutral: 'border border-transparent',
}

const GP_TONE_TEXT: Record<ReturnType<typeof gpTone>, string> = {
  success: 'text-emerald-700',
  warning: 'text-amber-800',
  danger: 'text-rose-700',
  neutral: 'text-slate-900',
}

// GP + GP% rows for the Sale Information modal, colour-highlighted by GP health.
function GpInfoRows({ summary }: { summary: OrderFinancialSummary }) {
  const tone = gpTone(summary.gp_percent)
  const text = GP_TONE_TEXT[tone]
  return (
    <div className={`rounded-lg px-2.5 py-2 space-y-1 ${GP_TONE_ROW[tone]}`}>
      <div className="flex items-center justify-between">
        <dt className={`font-semibold ${text}`}>Gross profit (GP)</dt>
        <dd className={`font-bold tabular-nums ${text}`}>
          {formatMoney(summary.gp)}
        </dd>
      </div>
      <div className="flex items-center justify-between">
        <dt className={`font-medium ${text}`}>GP %</dt>
        <dd className={`font-bold tabular-nums ${text}`}>
          {formatPercent(summary.gp_percent)}
        </dd>
      </div>
    </div>
  )
}

function toFixed2(value: number): string {
  return value.toFixed(2)
}

function round2(value: number): number {
  if (!Number.isFinite(value)) return value
  const scaled = value * 100
  // Java BigDecimal HALF_UP rounds halves away from zero. Use a small epsilon to
  // avoid binary-float values like 11764.999999 rounding down by accident.
  const epsilon = 1e-9
  if (scaled >= 0) return Math.floor(scaled + 0.5 + epsilon) / 100
  return Math.ceil(scaled - 0.5 - epsilon) / 100
}

// Parse the sale-price input. Returns null for blank / non-finite input. Never
// clamps — the > 0 and <= max rules are applied by the caller before the API call.
function parseSalePrice(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : null
}

function parseTargetGp(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : null
}

function targetGpCostUnavailableMessage(
  summary: OrderFinancialSummary | null,
): string | null {
  if (!summary) {
    return 'Add products and charges before applying a target GP.'
  }
  if (!Number.isFinite(summary.total_cost) || summary.total_cost <= 0) {
    return 'Target GP needs a positive total cost before it can calculate a sale price.'
  }
  return null
}

function calculateTargetSalePriceIncGst(
  totalCostExGst: number,
  targetGpPercent: number,
): number | null {
  const targetRate = targetGpPercent / 100
  const salePriceExGst = totalCostExGst / (1 - targetRate)
  const naiveIncGst = round2(salePriceExGst * GST_MULTIPLIER)
  return chooseBackendRoundedTargetPrice(
    naiveIncGst,
    totalCostExGst,
    targetGpPercent,
  )
}

function simulateBackendGpPercent(
  finalSalePriceIncGst: number,
  totalCostExGst: number,
): number | null {
  const salePriceExGst = round2(finalSalePriceIncGst / GST_MULTIPLIER)
  if (!Number.isFinite(salePriceExGst) || salePriceExGst <= 0) return null
  const gp = round2(salePriceExGst - totalCostExGst)
  const gpPercent = round2((gp / salePriceExGst) * 100)
  return Number.isFinite(gpPercent) ? gpPercent : null
}

type TargetPriceCandidate = {
  finalSalePriceIncGst: number
  gpPercent: number
}

// Boundary cases: total_cost = 100.01 and target GP = 15: naive 129.42
// would display 14.99, so choose 129.43 / 15.00. When no exact display exists,
// total_cost = 10.00 and target GP = 15 should choose 12.95 / 15.04, not
// under-target 12.94 / 14.97.
function chooseBackendRoundedTargetPrice(
  naiveIncGst: number,
  totalCostExGst: number,
  targetGpPercent: number,
): number | null {
  if (!Number.isFinite(naiveIncGst)) return null
  const naiveCents = Math.round(round2(naiveIncGst) * 100)
  const candidates: TargetPriceCandidate[] = []

  for (let offset = -10; offset <= 10; offset += 1) {
    const cents = naiveCents + offset
    const finalSalePriceIncGst = cents / 100
    if (
      !Number.isFinite(finalSalePriceIncGst) ||
      finalSalePriceIncGst <= 0 ||
      finalSalePriceIncGst > SALE_PRICE_MAX
    ) {
      continue
    }
    const gpPercent = simulateBackendGpPercent(
      finalSalePriceIncGst,
      totalCostExGst,
    )
    if (gpPercent === null) continue
    candidates.push({ finalSalePriceIncGst, gpPercent })
  }

  if (candidates.length === 0) return null

  const targetDisplayed = round2(targetGpPercent)
  const naiveCandidate = candidates.find(
    (candidate) =>
      Math.round(candidate.finalSalePriceIncGst * 100) === naiveCents,
  )
  // Preserve the exact naive price when it already displays the requested target;
  // this keeps clean cases like total_cost = 8000, target GP = 20 at 11000.
  if (
    naiveCandidate &&
    naiveCandidate.gpPercent === targetDisplayed &&
    naiveCandidate.gpPercent >= targetGpPercent
  ) {
    return naiveCandidate.finalSalePriceIncGst
  }

  const exactMatches = candidates.filter(
    (candidate) => candidate.gpPercent === targetDisplayed,
  )
  if (exactMatches.length > 0) {
    return exactMatches.reduce((best, candidate) =>
      candidate.finalSalePriceIncGst < best.finalSalePriceIncGst
        ? candidate
        : best,
    ).finalSalePriceIncGst
  }

  const aboveTarget = candidates.filter(
    (candidate) => candidate.gpPercent > targetGpPercent,
  )
  if (aboveTarget.length > 0) {
    return aboveTarget.reduce((best, candidate) => {
      if (candidate.gpPercent < best.gpPercent) return candidate
      if (candidate.gpPercent > best.gpPercent) return best
      return candidate.finalSalePriceIncGst < best.finalSalePriceIncGst
        ? candidate
        : best
    }).finalSalePriceIncGst
  }

  return candidates.reduce((best, candidate) =>
    candidate.gpPercent > best.gpPercent ? candidate : best,
  ).finalSalePriceIncGst
}

// Surfaces a friendly message for sale-price override/reset failures. A LAID
// order returns 422 ORDER_LOCKED; everything else falls back to the backend
// message (e.g. VALIDATION_FAILED) or a generic line.
function salePriceErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'ORDER_LOCKED') {
      return 'This order is laid and locked. The sale price can no longer be changed.'
    }
    if (err.message.length > 0) return err.message
  }
  return 'Something went wrong while updating the sale price. Please try again.'
}

// INVOICE_PRECONDITIONS_NOT_MET (returned by both create and rewrite) carries a
// details[] of backend ErrorDetail ({ section?, field?, message }). Parse
// defensively (also tolerating plain strings) so the UI can list the specific
// reasons the order is not yet invoice-ready.
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

export function DetailsOfSaleTab({
  orderId,
  locked,
  detailsDraft,
  setDetailsDraft,
  financialSummary,
  salePriceMutationInFlight,
  beginSalePriceMutation,
  finishSalePriceMutation,
  applyMutationFinancialSummary,
  queueDetailsAutosave,
  flushDetailsAutosave,
  detailsAutosaveSaving,
  detailsAutosaveSaved,
  detailsAutosaveError,
  onInvoiceReady,
}: Props) {
  const [errors, setErrors] = useState<Record<string, string>>({})

  // --- Sale price override / reset (independent of the details-of-sale save). ---
  // The input mirrors the GST-inclusive final sale price. `salePriceDirty` tracks
  // whether the user has edited it since the last server value, so an incoming
  // summary update never clobbers an active edit. The in-flight serialization
  // lives in OrderWorkspace (salePriceMutationInFlight) so it survives this tab's
  // unmount/remount; both buttons + the input disable while it is true.
  const [salePriceInput, setSalePriceInput] = useState('')
  const [salePriceDirty, setSalePriceDirty] = useState(false)
  const [salePriceError, setSalePriceError] = useState<string | null>(null)
  // Toggles the compact GP / financial info modal (full breakdown hidden by default).
  const [salePriceInfoOpen, setSalePriceInfoOpen] = useState(false)
  const [targetGpInput, setTargetGpInput] = useState('')
  const [targetGpError, setTargetGpError] = useState<string | null>(null)
  const [targetGpSuccess, setTargetGpSuccess] = useState<string | null>(null)

  // Guards setState after the tab unmounts (e.g. the order route changes, or the
  // user switches tabs, while a save is still in flight).
  const mountedRef = useRef(true)
  useEffect(() => {
    // Reset on setup so a StrictMode setup→cleanup→setup cycle (or any remount)
    // leaves the ref true for the live component; cleanup marks a real unmount.
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // Seed the sale-price input from the live summary, but never overwrite an
  // active edit. After a successful override/reset we clear `salePriceDirty` and
  // re-seed from the returned summary, so this also re-seeds then. When the order
  // is not yet priced (summary null) the input is cleared and the panel shows a
  // neutral empty state instead.
  useEffect(() => {
    if (salePriceDirty) return
    if (financialSummary) {
      setSalePriceInput(toFixed2(financialSummary.final_sale_price_inc_gst))
    } else {
      setSalePriceInput('')
    }
  }, [financialSummary, salePriceDirty])

  function handleSalePriceChange(value: string) {
    setSalePriceInput(value)
    setSalePriceDirty(true)
    setSalePriceError(null)
  }

  function handleTargetGpChange(value: string) {
    setTargetGpInput(value)
    setTargetGpError(null)
    setTargetGpSuccess(null)
  }

  async function bestEffortFlushDetailsBeforeSalePriceOverride() {
    try {
      await flushDetailsAutosave()
    } catch {
      // Sale price is independent from Details of Sale; invoice actions keep the
      // blocking flush, but price overrides continue if this best-effort save fails.
    }
  }

  async function submitSalePriceOverride(
    finalSalePriceIncGst: number,
    onError?: (message: string) => void,
  ): Promise<boolean> {
    if (locked || salePriceMutationInFlight) return false
    // Client-side guard mirrors the backend: required, finite, > 0, <= max.
    // Blank / zero / negative / non-finite never reaches the API.
    if (
      !Number.isFinite(finalSalePriceIncGst) ||
      finalSalePriceIncGst <= 0 ||
      finalSalePriceIncGst > SALE_PRICE_MAX
    ) {
      const message = 'Enter a sale price greater than 0 and at most 99,999,999.99.'
      setSalePriceError(message)
      onError?.(message)
      return false
    }

    setSalePriceError(null)
    // In-flight lock (parent) survives this tab unmounting.
    beginSalePriceMutation()
    try {
      await bestEffortFlushDetailsBeforeSalePriceOverride()
      const res = await overrideSalePrice(orderId, {
        final_sale_price_inc_gst: finalSalePriceIncGst,
      })
      const summary = res.data.order_financial_summary
      // A mutation response is the newest persisted state — apply it to the
      // workspace header FIRST (before the mountedRef guard, and even if this tab
      // has since unmounted). A later read-only GET /lines can never discard it.
      applyMutationFinancialSummary(summary)
      if (!mountedRef.current) return false
      setSalePriceInput(toFixed2(summary.final_sale_price_inc_gst))
      setSalePriceDirty(false)
      // The SAVED sale total just changed, so any invoice-action error (notably the
      // overpaid-rewrite message, which embeds the OLD total) is now stale. Clear it
      // so it isn't left behind; the next Rewrite re-evaluates against this new total.
      setInvoiceActionError(null)
      setInvoicePreconditions([])
      return true
    } catch (err) {
      if (!mountedRef.current) return false
      const message = salePriceErrorMessage(err)
      setSalePriceError(message)
      onError?.(message)
      return false
    } finally {
      // Always release the in-flight lock in the parent, even after unmount.
      finishSalePriceMutation()
    }
  }

  async function handleOverrideSalePrice() {
    const parsed = parseSalePrice(salePriceInput)
    await submitSalePriceOverride(parsed ?? NaN)
  }

  async function handleApplyTargetGp() {
    if (locked || salePriceMutationInFlight) return
    setTargetGpSuccess(null)
    setTargetGpError(null)
    setSalePriceError(null)

    const summary = financialSummary
    const costMessage = targetGpCostUnavailableMessage(summary)
    if (costMessage) {
      setTargetGpError(costMessage)
      return
    }
    if (!summary) return

    const parsed = parseTargetGp(targetGpInput)
    if (parsed === null) {
      setTargetGpError('Enter a target GP percentage.')
      return
    }
    if (parsed <= 0 || parsed >= 100) {
      setTargetGpError('Target GP must be greater than 0 and less than 100.')
      return
    }

    const nextSalePriceIncGst = calculateTargetSalePriceIncGst(
      summary.total_cost,
      parsed,
    )
    if (
      nextSalePriceIncGst === null ||
      nextSalePriceIncGst <= 0 ||
      nextSalePriceIncGst > SALE_PRICE_MAX
    ) {
      setTargetGpError(
        'That target GP would produce a sale price outside the supported range.',
      )
      return
    }

    const applied = await submitSalePriceOverride(
      nextSalePriceIncGst,
      setTargetGpError,
    )
    if (!mountedRef.current) return
    if (applied) setTargetGpSuccess('Target GP applied.')
  }

  async function handleResetSalePrice() {
    if (locked || salePriceMutationInFlight) return
    // Reset is idempotent on the backend (clears price_adjustment_inc_gst to
    // NULL), so it is safe to call even when there is no current adjustment.
    setSalePriceError(null)
    // In-flight lock (parent) survives this tab unmounting.
    beginSalePriceMutation()
    try {
      const res = await resetSalePrice(orderId)
      const summary = res.data.order_financial_summary
      // A mutation response is the newest persisted state — apply it to the
      // workspace header FIRST (before the mountedRef guard, and even if this tab
      // has since unmounted). A later read-only GET /lines can never discard it.
      applyMutationFinancialSummary(summary)
      if (!mountedRef.current) return
      setSalePriceInput(toFixed2(summary.final_sale_price_inc_gst))
      setSalePriceDirty(false)
      // The SAVED sale total just changed, so any invoice-action error (notably the
      // overpaid-rewrite message, which embeds the OLD total) is now stale. Clear it
      // so it isn't left behind; the next Rewrite re-evaluates against this new total.
      setInvoiceActionError(null)
      setInvoicePreconditions([])
    } catch (err) {
      if (!mountedRef.current) return
      setSalePriceError(salePriceErrorMessage(err))
    } finally {
      // Always release the in-flight lock in the parent, even after unmount.
      finishSalePriceMutation()
    }
  }

  // --- Invoice create / rewrite (D.1 create, D.2 rewrite). ---
  // The invoice ACTION lives in this tab (not the Invoice tab) so the invoice is
  // generated from this order's saved details. We probe GET /invoices/current on
  // mount ONLY to choose Create (no invoice yet) vs Rewrite (an invoice exists).
  // invoiceExists === null means still probing. The Invoice tab is view/download
  // only.
  const [invoiceExists, setInvoiceExists] = useState<boolean | null>(null)
  // The current invoice from the mount probe — kept (not just invoiceExists) so the
  // overpaid-rewrite guard can read its total_paid. null while probing / no invoice.
  const [currentInvoice, setCurrentInvoice] = useState<InvoiceDetail | null>(null)
  const [creatingInvoice, setCreatingInvoice] = useState(false)
  const [rewritingInvoice, setRewritingInvoice] = useState(false)
  const [invoiceActionError, setInvoiceActionError] = useState<string | null>(
    null,
  )
  const [invoicePreconditions, setInvoicePreconditions] = useState<
    PreconditionFailure[]
  >([])
  // Which action is awaiting explicit confirmation in the modal. Rewrite ALWAYS
  // confirms; Create only confirms when the GP warning is active. null = closed.
  const [confirmAction, setConfirmAction] = useState<'create' | 'rewrite' | null>(
    null,
  )

  // Probe the current invoice on mount (the tab refetches fresh on each open) to
  // decide Create vs Rewrite. 404 INVOICE_NOT_FOUND = no invoice yet = Create. Any
  // other failure also defaults to Create — the backend stays authoritative (a real
  // existing invoice is caught by 409 INVOICE_ALREADY_EXISTS, which flips this to
  // Rewrite). Uses a per-run cancelled guard, not mountedRef.
  useEffect(() => {
    let cancelled = false
    setInvoiceExists(null)
    setCurrentInvoice(null)
    setInvoiceActionError(null)
    setInvoicePreconditions([])
    fetchCurrentInvoice(orderId)
      .then((res) => {
        if (cancelled) return
        setInvoiceExists(true)
        // Keep the invoice so the rewrite guard can read total_paid.
        setCurrentInvoice(res.data.invoice)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setCurrentInvoice(null)
        if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
          setInvoiceExists(false)
          return
        }
        setInvoiceExists(false)
      })
    return () => {
      cancelled = true
    }
  }, [orderId])

  // GP health flag already computed by the backend financial summary (Chunk 3).
  // The confirmation popup is a FRONTEND guard only — it never changes the create/
  // rewrite call or invents backend manager-approval behaviour.
  const gpWarning = financialSummary?.gp_warning ?? false
  // Busy guard for ALL invoice actions. Includes salePriceMutationInFlight (Update
  // Sale Price / Reset Price, in flight in the always-mounted shell) so a Create /
  // Rewrite cannot fire while the sale total is still being persisted — otherwise the
  // backend could snapshot the previous sale total (Codex P1). This single flag gates
  // the Create/Rewrite buttons, the confirm-modal button, and every click/run guard.
  const invoiceActionBusy =
    creatingInvoice || rewritingInvoice || salePriceMutationInFlight

  // Overpaid-rewrite guard (MVP, frontend-only — no refunds, no negative balance,
  // no payment/backend changes). A Rewrite regenerates the invoice with the NEW
  // live sale total; if payments already recorded on the current invoice exceed
  // that new total, the rewrite would push the balance negative (the backend
  // rejects it). Detect it from already-available state: the current invoice's
  // total_paid (mount probe) vs the live final sale price inc GST
  // (order_financial_summary). NO cost/GP data is used. Returns the amounts when
  // paid exceeds the new total by at least a cent (avoids a float-rounding false
  // positive at exact equality), else null (also null when either value is not
  // safely available, so the guard never blocks on unknown data).
  function overpaidRewriteAmounts(): { paid: number; newTotal: number } | null {
    const paid = currentInvoice?.total_paid
    const newTotal = financialSummary?.final_sale_price_inc_gst
    if (typeof paid !== 'number' || typeof newTotal !== 'number') return null
    if (paid - newTotal > 0.005) return { paid, newTotal }
    return null
  }

  function applyInvoiceActionError(err: unknown, fallback: string) {
    if (err instanceof ApiError && err.code === 'INVOICE_PRECONDITIONS_NOT_MET') {
      setInvoicePreconditions(parsePreconditionFailures(err.details))
      setInvoiceActionError(
        err.message.length > 0
          ? err.message
          : 'This order is not ready to be invoiced.',
      )
      return
    }
    setInvoiceActionError(
      err instanceof ApiError && err.message.length > 0 ? err.message : fallback,
    )
  }

  // Codex P1: before the backend snapshots Details of Sale into the official
  // invoice, make sure the latest draft is actually saved. A field blur only QUEUES
  // an async autosave, so a Create/Rewrite click can otherwise run before that PUT
  // settles and snapshot stale data. Validate with the same rule autosave uses
  // (invalid -> surface the field + invoice errors and abort), then force-save the
  // latest draft and AWAIT the autosave drain. Returns true only when the details
  // are safely persisted; false blocks the create/rewrite and keeps the user here.
  async function flushDetailsBeforeInvoice(): Promise<boolean> {
    const validationErrors = validateForm(detailsDraft)
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors)
      setInvoicePreconditions([])
      setInvoiceActionError(
        'Please fix the highlighted Details of Sale fields before creating or rewriting the invoice.',
      )
      return false
    }
    const saved = await flushDetailsAutosave()
    if (!mountedRef.current) return false
    if (!saved) {
      setInvoicePreconditions([])
      setInvoiceActionError(
        'Could not save the latest Details of Sale changes. Please try again before creating or rewriting the invoice.',
      )
      return false
    }
    return true
  }

  async function runCreateInvoice() {
    if (invoiceActionBusy) return
    setCreatingInvoice(true)
    setInvoiceActionError(null)
    setInvoicePreconditions([])
    try {
      // Codex P1: flush pending Details of Sale autosaves first so the invoice
      // snapshots the latest SAVED details. On failure stay on Details (the inline
      // error is set by the flush helper) and do NOT create.
      const detailsReady = await flushDetailsBeforeInvoice()
      if (!detailsReady) return
      if (!mountedRef.current) return
      // D.1 create — body is always {}. Create is NOT LAID-gated. On success switch
      // straight to the Invoice tab (no success notice, no View button).
      await createInvoice(orderId)
      if (!mountedRef.current) return
      setInvoiceExists(true)
      onInvoiceReady()
    } catch (err) {
      if (!mountedRef.current) return
      // An invoice already exists (state drifted) — flip the action to Rewrite.
      if (err instanceof ApiError && err.code === 'INVOICE_ALREADY_EXISTS') {
        setInvoiceExists(true)
        return
      }
      applyInvoiceActionError(
        err,
        'Could not create the invoice. Please try again.',
      )
    } finally {
      if (mountedRef.current) {
        setCreatingInvoice(false)
        setConfirmAction(null)
      }
    }
  }

  async function runRewriteInvoice() {
    // Rewrite is blocked when LAID — the backend is the authority (422
    // ORDER_LOCKED); this just disables the path early.
    if (invoiceActionBusy || locked) return
    setRewritingInvoice(true)
    setInvoiceActionError(null)
    setInvoicePreconditions([])
    try {
      // Codex P1: flush pending Details of Sale autosaves first so the rewritten
      // invoice snapshots the latest SAVED details. On failure stay on Details and
      // do NOT rewrite.
      const detailsReady = await flushDetailsBeforeInvoice()
      if (!detailsReady) return
      if (!mountedRef.current) return
      // D.2 rewrite — body is always {}. Regenerates the current invoice. On
      // success switch straight to the Invoice tab (no success notice, no View
      // button) so the rewritten invoice is shown immediately.
      await rewriteInvoice(orderId)
      if (!mountedRef.current) return
      setInvoiceExists(true)
      onInvoiceReady()
    } catch (err) {
      if (!mountedRef.current) return
      // No invoice to rewrite (state drifted) — flip the action back to Create.
      if (err instanceof ApiError && err.code === 'INVOICE_REQUIRED') {
        setInvoiceExists(false)
        return
      }
      if (err instanceof ApiError && err.code === 'ORDER_LOCKED') {
        setInvoiceActionError(
          'This order is locked (LAID). The invoice cannot be rewritten.',
        )
        return
      }
      // Defensive: if the rewrite still failed and current state shows payments
      // already exceed the new total, show the friendly overpayment message rather
      // than a generic "unexpected error".
      const overpaid = overpaidRewriteAmounts()
      if (overpaid) {
        setInvoicePreconditions([])
        setInvoiceActionError(overpaidRewriteMessage(overpaid))
        return
      }
      applyInvoiceActionError(
        err,
        'Could not rewrite the invoice. Please try again.',
      )
    } finally {
      if (mountedRef.current) {
        setRewritingInvoice(false)
        setConfirmAction(null)
      }
    }
  }

  // Create: confirm only when the GP warning is active; otherwise create directly.
  function handleCreateInvoiceClick() {
    if (invoiceActionBusy || invoiceExists === null) return
    if (gpWarning) {
      setConfirmAction('create')
      return
    }
    void runCreateInvoice()
  }

  // Rewrite: ALWAYS confirm (it regenerates the invoice and makes the changes
  // official). The GP warning, when active, is shown in the same modal.
  function handleRewriteInvoiceClick() {
    if (invoiceActionBusy || locked || invoiceExists === null) return
    // Overpaid-rewrite guard: block BEFORE opening the confirmation / calling the
    // API when payments already exceed the new live total. Show the inline error
    // near the action row; do not open the modal, call rewriteInvoice, or navigate.
    const overpaid = overpaidRewriteAmounts()
    if (overpaid) {
      setInvoicePreconditions([])
      setInvoiceActionError(overpaidRewriteMessage(overpaid))
      return
    }
    setInvoiceActionError(null)
    setInvoicePreconditions([])
    setConfirmAction('rewrite')
  }

  function handleConfirmInvoiceAction() {
    if (invoiceActionBusy) return
    if (confirmAction === 'create') void runCreateInvoice()
    else if (confirmAction === 'rewrite') void runRewriteInvoice()
  }

  function clearError(key: string) {
    setErrors((prev) => {
      if (!(key in prev)) return prev
      const next = { ...prev }
      delete next[key]
      return next
    })
  }

  function update<K extends keyof DetailsForm>(key: K, value: DetailsForm[K]) {
    setDetailsDraft((prev) => ({ ...prev, [key]: value }))
    clearError(key)
    // The pair error spans both lay-date fields and is keyed on lay_date_status,
    // so editing either field should clear it.
    if (key === 'proposed_lay_date' || key === 'lay_date_status') {
      clearError('lay_date_status')
    }
  }

  function appendSnippet(snippet: string) {
    const nextDescription = detailsDraft.details_of_sale.trim()
      ? `${detailsDraft.details_of_sale.trimEnd()}\n${snippet}`
      : snippet
    const next = { ...detailsDraft, details_of_sale: nextDescription }
    setDetailsDraft(next)
    clearError('details_of_sale')
    // Quick-add is a deliberate action; autosave the appended description now.
    // (The + button preventDefaults mousedown so the textarea is not blurred.)
    commitDetails(next)
  }

  // Autosave the details-of-sale fields. Triggered on blur / change — never on
  // every keystroke and never debounced. The tab validates and builds the full
  // body, then hands it to the parent's single-flight queue (which lives in the
  // always-mounted shell, so the in-flight lock survives this tab's unmount/remount
  // and two concurrent full-replace PUTs can never overlap).
  function commitDetails(formToSave: DetailsForm) {
    if (locked) return

    // Strict client validation first — never autosave an invalid detailsDraft.
    const validationErrors = validateForm(formToSave)
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors)
      return
    }
    setErrors({})

    queueDetailsAutosave(buildDetailsBody(formToSave))
  }

  // Fields stay editable during a background autosave; only LAID locks them.
  const editingDisabled = locked
  // Whether the order has a live financial summary yet (priced).
  const priced = financialSummary !== null
  // Sale-price input + Update are disabled when locked, mid-request, or unpriced.
  // The in-flight flag comes from the parent so it survives this tab's remount.
  const salePriceControlsDisabled =
    locked || salePriceMutationInFlight || !priced
  // Reset is idempotent on the backend (clearing an already-NULL adjustment is a
  // no-op), so it only depends on the order being editable and no request being
  // in flight — never on the input state or whether an adjustment exists.
  const resetDisabled = locked || salePriceMutationInFlight
  const targetGpCostMessage = targetGpCostUnavailableMessage(financialSummary)
  const targetGpControlsDisabled =
    locked || salePriceMutationInFlight || targetGpCostMessage !== null

  return (
    <div>
      <div className="mb-6 flex items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
            Details of Sale
          </h2>
          <p className="text-sm text-slate-500 mt-1">
            Changes are saved automatically.
          </p>
        </div>
        <div className="shrink-0 pt-0.5 text-xs">
          {detailsAutosaveSaving ? (
            <span className="text-slate-500">Saving…</span>
          ) : detailsAutosaveError ? (
            <span className="text-red-600">{detailsAutosaveError}</span>
          ) : detailsAutosaveSaved ? (
            <span className="inline-flex items-center gap-1 text-teal-700">
              <CheckCircleIcon className="w-3.5 h-3.5" />
              Saved
            </span>
          ) : null}
        </div>
      </div>

      {locked && (
        <div className="mb-5 rounded-lg border border-slate-200 bg-slate-50 px-4 py-2.5 text-xs text-slate-600">
          This order is laid and locked. Fields are read-only.
        </div>
      )}

      <div className="space-y-6">
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <div className="lg:col-span-2">
            <Field
              label="Description"
              htmlFor="details_of_sale"
              error={errors.details_of_sale}
            >
              <Textarea
                id="details_of_sale"
                rows={10}
                value={detailsDraft.details_of_sale}
                onChange={(e) => update('details_of_sale', e.target.value)}
                onBlur={() => commitDetails(detailsDraft)}
                invalid={!!errors.details_of_sale}
                disabled={editingDisabled}
                placeholder="Describe the supply, installation and any site notes for this order."
              />
            </Field>
          </div>

          <div className="lg:col-span-1">
            <div className="rounded-lg border border-slate-200 bg-slate-50/60 p-4 h-full">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-600">
                Quick add descriptions
              </h3>
              <p className="text-[11px] text-slate-500 mt-1">
                Tap + to append a preset to the description.
              </p>
              <ul className="mt-3 space-y-2">
                {QUICK_DESCRIPTIONS.map((snippet) => (
                  <li
                    key={snippet}
                    className="flex items-start gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 shadow-sm"
                  >
                    <span className="flex-1 text-xs text-slate-700 leading-snug">
                      {snippet}
                    </span>
                    <button
                      type="button"
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={() => appendSnippet(snippet)}
                      disabled={editingDisabled}
                      aria-label={`Add snippet: ${snippet}`}
                      className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md border border-teal-500 bg-white text-teal-600 hover:bg-teal-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      <span aria-hidden="true" className="text-base leading-none">
                        +
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Field
            label="Proposed lay date"
            htmlFor="proposed_lay_date"
            error={errors.proposed_lay_date}
          >
            <Input
              id="proposed_lay_date"
              type="date"
              value={detailsDraft.proposed_lay_date}
              onChange={(e) => update('proposed_lay_date', e.target.value)}
              onBlur={() => commitDetails(detailsDraft)}
              invalid={!!errors.proposed_lay_date}
              disabled={editingDisabled}
            />
            <p className="mt-1.5 text-xs text-slate-500">
              {formatIsoDate(detailsDraft.proposed_lay_date) || 'No date selected'}
            </p>
          </Field>
          <Field
            label="Lay date status"
            htmlFor="lay_date_status"
            error={errors.lay_date_status}
          >
            <Select
              id="lay_date_status"
              value={detailsDraft.lay_date_status}
              onChange={(e) =>
                update('lay_date_status', e.target.value as '' | LayDateStatus)
              }
              onBlur={() => commitDetails(detailsDraft)}
              invalid={!!errors.lay_date_status}
              disabled={editingDisabled}
            >
              <option value="">— Select —</option>
              <option value="CONFIRMED">Confirmed</option>
              <option value="TO_BE_CONFIRMED">To be confirmed</option>
            </Select>
          </Field>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Field
            label="Plan numbers"
            htmlFor="plan_numbers"
            error={errors.plan_numbers}
          >
            <Input
              id="plan_numbers"
              type="text"
              value={detailsDraft.plan_numbers}
              onChange={(e) => update('plan_numbers', e.target.value)}
              onBlur={() => commitDetails(detailsDraft)}
              invalid={!!errors.plan_numbers}
              disabled={editingDisabled}
            />
          </Field>
        </div>

        <label className="inline-flex items-center gap-2 text-sm text-slate-700 cursor-pointer">
          <input
            id="supply_only"
            type="checkbox"
            checked={detailsDraft.supply_only}
            onChange={(e) => {
              const checked = e.target.checked
              const next = { ...detailsDraft, supply_only: checked }
              update('supply_only', checked)
              commitDetails(next)
            }}
            disabled={editingDisabled}
            className="h-4 w-4 rounded border-slate-300 text-teal-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30 disabled:opacity-50 disabled:cursor-not-allowed"
          />
          <span>Supply only (no installation)</span>
        </label>
      </div>

      <div className="mt-8">
        <div className="max-w-md">
          <label
            htmlFor="final_sale_price_inc_gst"
            className="block text-sm font-medium text-slate-700 mb-1.5"
          >
            Sale Price
          </label>
          <Input
            id="final_sale_price_inc_gst"
            type="text"
            inputMode="decimal"
            value={salePriceInput}
            onChange={(e) => handleSalePriceChange(e.target.value)}
            invalid={!!salePriceError}
            disabled={salePriceControlsDisabled}
            placeholder={priced ? '0.00' : 'Not priced yet'}
            className="font-semibold tabular-nums"
          />
          {salePriceError && (
            <p className="mt-1.5 text-xs text-red-600" role="alert">
              {salePriceError}
            </p>
          )}
          {!priced && !salePriceError && (
            <p className="mt-1.5 text-xs text-slate-400">
              Add products and charges to set a sale price.
            </p>
          )}
          {financialSummary?.gp_warning && (
            <p className="mt-1.5 text-xs font-medium text-red-600">
              Warning: This sales price is below approved sales persons costings.
            </p>
          )}
        </div>

        {/* Sale price controls (left) + manager-approval warning (right). */}
        <div className="mt-4 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="success"
              size="md"
              onClick={handleOverrideSalePrice}
              disabled={salePriceControlsDisabled}
            >
              {salePriceMutationInFlight ? 'Saving…' : 'Update Sale Price'}
            </Button>
            <Button
              type="button"
              variant="success-outline"
              size="md"
              onClick={handleResetSalePrice}
              disabled={resetDisabled}
            >
              Reset Price
            </Button>
            <button
              type="button"
              onClick={() => {
                setTargetGpError(null)
                setTargetGpSuccess(null)
                setSalePriceInfoOpen(true)
              }}
              disabled={!priced}
              aria-label="Show sale information"
              className="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-sky-500 text-white hover:bg-sky-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-500/30 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <InfoIcon className="w-4 h-4" />
            </button>
          </div>

          {/* Right side of the action row: the invoice action (Create when no
              invoice exists, Rewrite once it does), with the manager-approval
              warning beneath it — matching the reference layout. */}
          <div className="flex flex-col items-start sm:items-end gap-1.5">
            {invoiceExists ? (
              <Button
                type="button"
                variant="success"
                size="md"
                onClick={handleRewriteInvoiceClick}
                disabled={invoiceActionBusy || locked}
                title={
                  locked
                    ? 'This order is locked (LAID) and cannot be rewritten.'
                    : undefined
                }
              >
                {rewritingInvoice ? 'Rewriting…' : 'Rewrite invoice'}
              </Button>
            ) : (
              <Button
                type="button"
                variant="success"
                size="md"
                onClick={handleCreateInvoiceClick}
                disabled={invoiceExists === null || invoiceActionBusy}
              >
                {invoiceExists === null
                  ? 'Checking…'
                  : creatingInvoice
                    ? 'Creating…'
                    : 'Create invoice'}
              </Button>
            )}
            {financialSummary?.gp_warning && (
              <span className="text-xs font-medium text-red-600">
                Manager approval required!
              </span>
            )}
          </div>
        </div>

        {/* Invoice action errors (precondition reasons / failures) — inline text,
            no panel, mirroring the sale-price error treatment above. */}
        {invoiceActionError && (
          <div className="mt-2 sm:text-right" role="alert">
            <p className="text-xs font-medium text-red-600">
              {invoiceActionError}
            </p>
            {invoicePreconditions.length > 0 && (
              <ul className="mt-1 space-y-0.5">
                {invoicePreconditions.map((failure, idx) => (
                  <li
                    key={`${failure.label ?? ''}-${idx}`}
                    className="text-xs text-red-600 leading-relaxed"
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
      </div>

      {/* Create / Rewrite confirmation. Rewrite ALWAYS confirms; Create confirms
          only when the GP warning is active. The GP warning, when active, is shown
          here so the user explicitly accepts the below-threshold price. FRONTEND
          confirmation only — it does not change backend behaviour. */}
      <Modal
        open={confirmAction !== null}
        onClose={() => {
          if (!invoiceActionBusy) setConfirmAction(null)
        }}
        labelledBy="invoice-action-confirm-title"
      >
        <div className="p-6">
          <h3
            id="invoice-action-confirm-title"
            className="text-lg font-semibold text-slate-900 tracking-tight"
          >
            {confirmAction === 'rewrite' ? 'Rewrite invoice?' : 'Create invoice?'}
          </h3>

          {confirmAction === 'rewrite' && (
            <p className="mt-2 text-sm text-slate-600 leading-relaxed">
              Rewriting the invoice will regenerate the current invoice from the
              latest saved order details and make those changes official.
            </p>
          )}

          {/* Customer-safe manager-approval warning. Always plain red text (no
              yellow background/border, no GP-tier colours, not derived from GP%) —
              the salesperson may show this screen to the customer. The GP-warning
              LOGIC is unchanged; only the wording/style here is customer-safe. */}
          {gpWarning && (
            <p className="mt-3 text-sm font-semibold text-red-600">
              Manager approval required before proceeding with this invoice.
            </p>
          )}

          <div className="mt-6 flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              size="md"
              onClick={() => setConfirmAction(null)}
              disabled={invoiceActionBusy}
            >
              Cancel
            </Button>
            <Button
              type="button"
              variant="success"
              size="md"
              onClick={handleConfirmInvoiceAction}
              disabled={invoiceActionBusy}
            >
              {invoiceActionBusy
                ? confirmAction === 'rewrite'
                  ? 'Rewriting…'
                  : 'Creating…'
                : confirmAction === 'rewrite'
                  ? 'Rewrite invoice'
                  : 'Create invoice'}
            </Button>
          </div>
        </div>
      </Modal>

      {/* Compact GP / financial info — hidden by default, opened by the "i" button. */}
      <Modal
        open={salePriceInfoOpen}
        onClose={() => setSalePriceInfoOpen(false)}
        labelledBy="sale-information-title"
      >
        <div className="p-6">
          <div className="flex flex-col items-center text-center">
            <span className="inline-flex h-12 w-12 items-center justify-center rounded-full border-2 border-sky-300 text-sky-500">
              <InfoIcon className="w-6 h-6" />
            </span>
            <h3
              id="sale-information-title"
              className="mt-3 text-lg font-semibold text-slate-900 tracking-tight"
            >
              Sale Information
            </h3>
          </div>

          {financialSummary && (
            <dl className="mt-4 space-y-1.5 text-sm">
              <div className="flex items-center justify-between">
                <dt className="text-slate-500">Calculated total (inc GST)</dt>
                <dd className="font-medium tabular-nums text-slate-900">
                  {formatMoney(financialSummary.calculated_total_inc_gst)}
                </dd>
              </div>
              <div className="flex items-center justify-between">
                <dt className="text-slate-500">Price adjustment (inc GST)</dt>
                <dd className="font-medium tabular-nums text-slate-900">
                  {financialSummary.price_adjustment_inc_gst === null
                    ? 'None'
                    : formatMoney(financialSummary.price_adjustment_inc_gst)}
                </dd>
              </div>
              <div className="flex items-center justify-between border-t border-slate-200 pt-1.5">
                <dt className="font-semibold text-slate-700">
                  Final sale price (inc GST)
                </dt>
                <dd className="font-bold tabular-nums text-slate-900">
                  {formatMoney(financialSummary.final_sale_price_inc_gst)}
                </dd>
              </div>
              <div className="flex items-center justify-between">
                <dt className="text-slate-500">Sale price (ex GST)</dt>
                <dd className="font-medium tabular-nums text-slate-900">
                  {formatMoney(financialSummary.sale_price_ex_gst)}
                </dd>
              </div>
              <div className="flex items-center justify-between">
                <dt className="text-slate-500">Total cost</dt>
                <dd className="font-medium tabular-nums text-slate-900">
                  {formatMoney(financialSummary.total_cost)}
                </dd>
              </div>
              <GpInfoRows summary={financialSummary} />
            </dl>
          )}

          {financialSummary && (
            <div className="mt-4 rounded-lg border border-slate-200 bg-slate-50/70 p-3 text-left">
              <Field
                label="Target GP %"
                htmlFor="target_gp_percent"
                error={targetGpError ?? undefined}
              >
                <div className="flex flex-col gap-2 sm:flex-row">
                  <Input
                    id="target_gp_percent"
                    type="text"
                    inputMode="decimal"
                    value={targetGpInput}
                    onChange={(e) => handleTargetGpChange(e.target.value)}
                    invalid={!!targetGpError}
                    disabled={targetGpControlsDisabled}
                    placeholder="20"
                    className="font-semibold tabular-nums"
                  />
                  <Button
                    type="button"
                    variant="success"
                    size="md"
                    onClick={handleApplyTargetGp}
                    disabled={targetGpControlsDisabled}
                  >
                    {salePriceMutationInFlight ? 'Applying…' : 'Apply target GP'}
                  </Button>
                </div>
              </Field>
              {targetGpCostMessage && !targetGpError && (
                <p className="mt-1.5 text-xs text-slate-500">
                  {targetGpCostMessage}
                </p>
              )}
              {targetGpSuccess && !targetGpError && (
                <p className="mt-1.5 text-xs font-medium text-teal-700">
                  {targetGpSuccess}
                </p>
              )}
            </div>
          )}

          <div className="mt-5 flex justify-center">
            <Button
              type="button"
              variant="primary"
              size="md"
              onClick={() => setSalePriceInfoOpen(false)}
            >
              OK
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
