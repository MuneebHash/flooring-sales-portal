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
import { fetchQuickDescriptions } from '../../lib/api/quickDescriptionsApi'

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
  // Batch A #107 (D1): awaitable flush of the Customer tab's customer,
  // installation and billing autosaves (shell-owned wrapper; true without waiting
  // on a known LAID order). Create / Rewrite freeze the saved Invoice To name and
  // billing lines, so they run it before the Details flush (the Details draft is
  // validated again and flushed last); false blocks them.
  flushCustomerAutosave: () => Promise<boolean>
  detailsAutosaveSaving: boolean
  detailsAutosaveSaved: boolean
  detailsAutosaveError: string | null
  // Called ONLY after a successful invoice create/rewrite so the parent (the
  // always-mounted shell) can switch to the Invoice tab, where the new/rewritten
  // invoice mounts + refetches and is shown immediately. Not invoked on failure.
  onInvoiceReady: () => void
  // Phase 16D-B: whether the action modal may offer Create Quote. Owned by the
  // shell (it runs the quote-workspace probe): 'hidden' once the Quote tab
  // exists for this order; 'checking' while the probe is unresolved; 'error'
  // when it failed (STRICT rule: never treat a failed probe as "no quote
  // exists" — the option stays disabled with a retry); 'enabled' only after the
  // probe confirmed no saved draft.
  createQuoteAvailability: CreateQuoteAvailability
  // Reveals the Quote tab + switches to it (shell-owned). Never creates or
  // rewrites an invoice and never mutates order pricing.
  onCreateQuote: () => void
  // Re-runs the shell's quote-workspace probe after a failure.
  onRetryQuoteProbe: () => void
}

// Phase 16D-B: Create Quote availability in the invoice action modal (see the
// prop docs above). Exported so OrderWorkspace shares the exact union.
export type CreateQuoteAvailability = 'hidden' | 'checking' | 'error' | 'enabled'

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

// Compact timestamp for the Rewrite confirmation's signed warning (copy of the
// QuoteTab / InvoiceTab helper): "08 Jul 2026, 14:05". Returns '' for an
// unparsable value.
function formatTimestamp(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso)
  if (!match) return ''
  const [, year, month, day, hours, minutes] = match
  const name = MONTH_NAMES[Number(month) - 1]
  if (!name) return ''
  return `${day} ${name} ${year}, ${hours}:${minutes}`
}

// Batch A PR 2 (#149) - the stale Rewrite 409 (INVOICE_VERSION_STALE) default
// message, shown when the response carries none (the backend message is
// otherwise surfaced verbatim; the InvoiceTab constant).
const INVOICE_STALE_MESSAGE = 'Invoice updated, please refresh'
const NO_INVOICE_TO_REWRITE_MESSAGE =
  'This order has no invoice to rewrite any more. Use Create invoice instead.'

// Batch A PR 2 (#149) - the Rewrite confirmation's binding to ONE fresh read of
// the current invoice, started when that confirmation opens (key = that
// opening). Only a 'ready' binding for the open key and this order enables
// Confirm; its versionNumber is the expected_version_number the Rewrite POST
// carries, and its acceptance fields drive the signed warning shown with it.
type RewriteBinding =
  | { status: 'loading'; key: number; orderId: number }
  | { status: 'error'; key: number; orderId: number }
  | {
      status: 'ready'
      key: number
      orderId: number
      invoiceId: number
      versionNumber: number
      acceptedAt: string | null
      acceptedCustomerName: string | null
      totalPaid: number
    }

// The outcome of one current-invoice read (see readCurrentInvoice): applied (an
// invoice, or null for 404 INVOICE_NOT_FOUND when the caller installs that
// state), ignored (nothing installed: unmounted, another order, a newer read
// applied first, an OLDER version than the one known, or a 404 the caller does
// not install), failed (no readable answer and nothing newer applied).
type CurrentInvoiceReadOutcome =
  | { status: 'applied'; invoice: InvoiceDetail | null }
  | { status: 'ignored' }
  | { status: 'failed'; error: unknown }

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
  flushCustomerAutosave,
  detailsAutosaveSaving,
  detailsAutosaveSaved,
  detailsAutosaveError,
  onInvoiceReady,
  createQuoteAvailability,
  onCreateQuote,
  onRetryQuoteProbe,
}: Props) {
  const [errors, setErrors] = useState<Record<string, string>>({})

  // Phase 14C: quick-add presets are TENANT data (GET /api/v1/{slug}/quick-descriptions),
  // fetched on mount with the [BUSINESS_NAME] token already substituted server-side. They
  // REPLACE the former hardcoded list. Empty result OR any fetch failure => no quick-add
  // buttons (the panel is hidden); the failure is swallowed so it never blocks the Details
  // tab or the sale/financial flow. Per-run cancelled guard, mirroring the invoice probe.
  const [quickDescriptions, setQuickDescriptions] = useState<string[]>([])
  useEffect(() => {
    let cancelled = false
    fetchQuickDescriptions()
      .then((descriptions) => {
        if (cancelled) return
        setQuickDescriptions(descriptions)
      })
      .catch(() => {
        if (cancelled) return
        setQuickDescriptions([])
      })
    return () => {
      cancelled = true
    }
  }, [])

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
  // user switches tabs, while a save is still in flight). Batch A PR 2 (#149): an
  // unmount (tab switch, order change, leaving the order) also drops any Rewrite
  // confirmation binding, so a late binding read lands nowhere.
  const mountedRef = useRef(true)
  useEffect(() => {
    // Reset on setup so a StrictMode setup→cleanup→setup cycle (or any remount)
    // leaves the ref true for the live component; cleanup marks a real unmount.
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      rewriteBindingRef.current = null
    }
  }, [])
  // Batch A PR 2 (#149) - mirrors read by async continuations: the order this tab
  // reads and writes, and the live summary (the overpaid fallback after a failed
  // Rewrite reads the sale total current at that moment).
  const orderIdRef = useRef(orderId)
  orderIdRef.current = orderId
  const financialSummaryRef = useRef(financialSummary)
  financialSummaryRef.current = financialSummary

  // Seed the sale-price input from the live summary, but never overwrite an
  // active edit. After a successful override/reset we clear `salePriceDirty` and
  // re-seed from the returned summary, so this also re-seeds then, as it does when
  // the workspace re-reads the summary (16G PR 6: on opening this tab, and after a
  // remote quote acceptance). When the order is not yet priced (summary null) the
  // input is cleared and the panel shows a neutral empty state instead.
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
    // 16G PR 6 (#121): only an EDITED input is ever submitted (the button is disabled
    // until then), so a stale, unedited price can never overwrite one a remote quote
    // acceptance wrote (D6b).
    if (!salePriceDirty) return
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
  // mount to choose Create (no invoice yet) vs Rewrite (an invoice exists).
  // invoiceExists === null means still probing. The Invoice tab is view/download
  // only. Batch A PR 2 (#149): the probe is NOT what a Rewrite carries - the
  // Rewrite confirmation makes its own fresh, keyed read when it opens and binds
  // the version it shows (see RewriteBinding); every read installs through the
  // one sequenced path below.
  const [invoiceExists, setInvoiceExists] = useState<boolean | null>(null)
  // The last current invoice installed by a read (the mount probe, a Rewrite
  // binding read or the re-read after a stale Rewrite) — kept (not just
  // invoiceExists) so the pre-open overpaid-rewrite guard can read its
  // total_paid. null while probing / no invoice. A read's answer is written
  // only through installCurrentInvoice; the mount probe effect alone also
  // resets it to null when it starts and when its read fails (then Create).
  const [currentInvoice, setCurrentInvoice] = useState<InvoiceDetail | null>(null)
  const currentInvoiceRef = useRef<InvoiceDetail | null>(null)
  // Batch A PR 2 (#149) - ONE recency rule for every current-invoice read (the
  // InvoiceTab pattern): invoiceReadSeqRef numbers each read when it is ISSUED,
  // invoiceReadFloorRef is the newest number whose answer has been installed. A
  // read installs only while it is above the floor, and an answer OLDER than the
  // known version is never installed (versions only grow), so no late read rolls
  // back a newer one.
  const invoiceReadSeqRef = useRef(0)
  const invoiceReadFloorRef = useRef(0)
  // Batch A PR 2 (#149) - the open Rewrite confirmation's binding (state for
  // rendering, ref for async continuations and Confirm), its key sequence, and
  // the SYNCHRONOUS in-flight flag of the Rewrite POST.
  const [rewriteBinding, setRewriteBinding] = useState<RewriteBinding | null>(
    null,
  )
  const rewriteBindingRef = useRef<RewriteBinding | null>(null)
  const rewriteKeySeqRef = useRef(0)
  const rewriteInFlightRef = useRef(false)
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

  // The ONE place a read's answer changes the known invoice (and with it Create
  // vs Rewrite). The only other writes are the mount probe's own resets: to
  // "probing" (null) when it starts and to Create after a failed read.
  function installCurrentInvoice(next: InvoiceDetail | null) {
    currentInvoiceRef.current = next
    setCurrentInvoice(next)
    setInvoiceExists(next !== null)
  }

  // Read the current invoice and install the answer through installCurrentInvoice,
  // but ONLY while this tab is still mounted, still on the order the read was issued
  // for, no newer read has been installed since it was issued, and the caller's
  // optional canInstall still returns true (checked together with those guards,
  // before the floor advances or anything installs); an answer older than the
  // known version, or for another order, is never installed. 404
  // INVOICE_NOT_FOUND installs the no-invoice state only when installNotFound is
  // set. Whenever one of those guards fails (canInstall included) the read is
  // ignored: no install and no floor change, for a 404 as well. Never throws.
  async function readCurrentInvoice(
    installNotFound: boolean,
    canInstall?: () => boolean,
  ): Promise<CurrentInvoiceReadOutcome> {
    invoiceReadSeqRef.current += 1
    const seq = invoiceReadSeqRef.current
    const requestOrderId = orderIdRef.current
    const stillCurrent = () =>
      mountedRef.current &&
      orderIdRef.current === requestOrderId &&
      seq > invoiceReadFloorRef.current &&
      (canInstall === undefined || canInstall())
    let next: InvoiceDetail | null
    try {
      const res = await fetchCurrentInvoice(requestOrderId)
      next = res.data.invoice
    } catch (err) {
      if (!(err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND')) {
        return stillCurrent()
          ? { status: 'failed', error: err }
          : { status: 'ignored' }
      }
      if (!installNotFound) return { status: 'ignored' }
      next = null
    }
    if (!stillCurrent()) return { status: 'ignored' }
    if (next !== null && next.order_id !== requestOrderId) {
      return { status: 'ignored' }
    }
    const known = currentInvoiceRef.current
    if (
      next !== null &&
      known !== null &&
      next.version_number < known.version_number
    ) {
      return { status: 'ignored' }
    }
    invoiceReadFloorRef.current = seq
    installCurrentInvoice(next)
    return { status: 'applied', invoice: next }
  }

  // Probe the current invoice on mount (the tab refetches fresh on each open) to
  // decide Create vs Rewrite. 404 INVOICE_NOT_FOUND = no invoice yet = Create. Any
  // other failure also defaults to Create — the backend stays authoritative (a real
  // existing invoice is caught by 409 INVOICE_ALREADY_EXISTS, which flips this to
  // Rewrite). The probe never binds a Rewrite (Batch A PR 2, #149): it only informs
  // the button and the pre-open overpaid hint. Uses a per-run cancelled guard plus
  // the sequenced install above.
  useEffect(() => {
    let cancelled = false
    setInvoiceExists(null)
    currentInvoiceRef.current = null
    setCurrentInvoice(null)
    setInvoiceActionError(null)
    setInvoicePreconditions([])
    void readCurrentInvoice(true).then((outcome) => {
      if (cancelled) return
      if (outcome.status === 'failed') {
        // No readable answer and nothing newer installed: default to Create.
        currentInvoiceRef.current = null
        setCurrentInvoice(null)
        setInvoiceExists(false)
      }
    })
    return () => {
      cancelled = true
    }
    // readCurrentInvoice reads everything through refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId])

  // GP health flag already computed by the backend financial summary (Chunk 3).
  // The GP warning in the confirmation is a FRONTEND hint only — it never changes
  // the create/rewrite call or invents backend manager-approval behaviour. (Since
  // Batch A PR 2, #149, the Rewrite confirmation also carries the version it was
  // bound to, which the server checks authoritatively; see RewriteBinding.)
  const gpWarning = financialSummary?.gp_warning ?? false
  // Busy guard for ALL invoice actions. Includes salePriceMutationInFlight (Update
  // Sale Price / Reset Price, in flight in the always-mounted shell) so a Create /
  // Rewrite cannot fire while the sale total is still being persisted — otherwise the
  // backend could snapshot the previous sale total (Codex P1). This single flag gates
  // the Create/Rewrite buttons, the confirm-modal button, and every click/run guard.
  const invoiceActionBusy =
    creatingInvoice || rewritingInvoice || salePriceMutationInFlight

  // Overpaid-rewrite guard (MVP, frontend-only — no refunds, no negative balance,
  // no payment/backend changes; the backend's own overpaid backstop, #135, is
  // unchanged). A Rewrite regenerates the invoice with the NEW live sale total; if
  // payments already recorded on the current invoice exceed that new total, the
  // rewrite would push the balance negative (the backend rejects it). Detect it
  // from already-available state: a current invoice's total_paid vs the live final
  // sale price inc GST (order_financial_summary). NO cost/GP data is used. Returns
  // the amounts when paid exceeds the new total by at least a cent (avoids a
  // float-rounding false positive at exact equality), else null (also null when
  // either value is not safely available, so the guard never blocks on unknown
  // data). overpaidRewriteAmounts is the pre-open hint (the last installed
  // invoice); since Batch A PR 2 (#149) the same check runs again against the
  // Rewrite binding's own total_paid once that read lands, and the fallback after
  // a failed Rewrite uses the bound total_paid too.
  function overpaidAmountsFor(
    paid: number | undefined,
    newTotal: number | undefined,
  ): { paid: number; newTotal: number } | null {
    if (typeof paid !== 'number' || typeof newTotal !== 'number') return null
    if (paid - newTotal > 0.005) return { paid, newTotal }
    return null
  }

  function overpaidRewriteAmounts(): { paid: number; newTotal: number } | null {
    return overpaidAmountsFor(
      currentInvoice?.total_paid,
      financialSummary?.final_sale_price_inc_gst,
    )
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

  // Batch A #107 (D1): the latest draft prop, so the invoice flush validates the
  // FRESHEST draft after the Customer tab wait, never the click-time closure (the
  // inputs stay editable during that wait). Written during render; read only in
  // async handlers, never for rendering.
  const detailsDraftRef = useRef(detailsDraft)
  detailsDraftRef.current = detailsDraft

  // Codex P1: before the backend snapshots Details of Sale into the official
  // invoice, make sure the latest draft is actually saved. A field blur only QUEUES
  // an async autosave, so a Create/Rewrite click can otherwise run before that PUT
  // settles and snapshot stale data. Validate with the same rule autosave uses
  // (invalid -> surface the field + invoice errors and abort). Batch A #107 (D1):
  // then flush the Customer tab; then validate the FRESHEST draft again and
  // force-save it, awaiting the Details autosave drain LAST. That flush re-checks
  // the freshest draft after its wait and nothing is awaited after it, so a Details
  // edit made during either wait is saved or blocks the action (an unknown save
  // outcome is not resolved here). True only when the Customer tab and the details
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
    // Batch A #107 (D1): the invoice also freezes the saved Invoice To name and
    // billing lines, so pending Customer tab saves are flushed too. An invalid
    // section or a failed save blocks the create / rewrite (no request).
    const customerSaved = await flushCustomerAutosave()
    if (!mountedRef.current) return false
    if (!customerSaved) {
      setInvoicePreconditions([])
      setInvoiceActionError(
        'Could not save the latest Customer tab changes. Complete or fix the customer details and addresses on the Customer tab before creating or rewriting the invoice.',
      )
      return false
    }
    // The Details inputs stayed editable during the Customer tab wait.
    const latestErrors = validateForm(detailsDraftRef.current)
    if (Object.keys(latestErrors).length > 0) {
      setErrors(latestErrors)
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
      // Codex P1 + Batch A #107: flush the Customer tab and then, last, the pending
      // Details of Sale autosaves so the invoice snapshots the latest SAVED customer
      // and details. On failure stay on Details (the inline error is set by the flush
      // helper) and do NOT create.
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
      // An invoice already exists (state drifted) — flip the action to Rewrite and
      // show the server message (Batch A PR 2, #149). No Rewrite binding is
      // created here: the next Rewrite click makes its own fresh read.
      if (err instanceof ApiError && err.code === 'INVOICE_ALREADY_EXISTS') {
        setInvoiceExists(true)
        setInvoicePreconditions([])
        setInvoiceActionError(
          err.message.length > 0
            ? err.message
            : 'An invoice already exists for this order. Use Rewrite Invoice to create a new version.',
        )
        return
      }
      applyInvoiceActionError(
        err,
        'Could not create the invoice. Please try again.',
      )
    } finally {
      if (mountedRef.current) {
        setCreatingInvoice(false)
        closeInvoiceActionModal()
      }
    }
  }

  // Batch A PR 2 (#149) - the ONE close path of the invoice action modal (Cancel,
  // Escape, the backdrop, Create Quote, and the end of a Create / Rewrite). It drops
  // the Rewrite binding, so a binding read that lands later is discarded and a later
  // Confirm can never post. The modal's own onClose still refuses to dismiss while a
  // real Create / Rewrite is in flight.
  function closeInvoiceActionModal() {
    rewriteBindingRef.current = null
    setRewriteBinding(null)
    setConfirmAction(null)
  }

  // Batch A PR 2 (#149) - the Rewrite confirmation's fresh read of the current
  // invoice: a new key, the binding cleared to 'loading', then GET
  // .../invoices/current. Started from the click handler (and from Try again),
  // never from an effect, and never sets the invoice-action busy flag, so Cancel,
  // Escape, the backdrop and Create Quote keep working while it waits (no timeout).
  // The read may install only while the open binding is still this key for this
  // order (its canInstall, checked by readCurrentInvoice before the floor advances
  // or anything installs). Once Cancel, Escape, the backdrop, Create Quote or an
  // unmount has cleared the binding, or a newer opening has replaced it, a late
  // 200 or 404 changes nothing: no install, no floor change, no binding. A usable
  // 200 for the open key installs through the sequenced path and binds the version
  // shown; only 404 INVOICE_NOT_FOUND means "no invoice" (close, flip to Create,
  // say so); any other failure — or an older / foreign / superseded answer —
  // leaves the modal on its error with Try again. Nothing is retried automatically.
  async function startRewriteBindingRead() {
    rewriteKeySeqRef.current += 1
    const key = rewriteKeySeqRef.current
    const requestOrderId = orderIdRef.current
    const loading: RewriteBinding = {
      status: 'loading',
      key,
      orderId: requestOrderId,
    }
    rewriteBindingRef.current = loading
    setRewriteBinding(loading)
    const outcome = await readCurrentInvoice(true, () => {
      const binding = rewriteBindingRef.current
      return (
        binding !== null &&
        binding.key === key &&
        binding.orderId === requestOrderId
      )
    })
    const open = rewriteBindingRef.current
    if (
      !mountedRef.current ||
      open === null ||
      open.key !== key ||
      orderIdRef.current !== requestOrderId
    ) {
      return
    }
    if (outcome.status === 'applied') {
      if (outcome.invoice === null) {
        closeInvoiceActionModal()
        setInvoicePreconditions([])
        setInvoiceActionError(NO_INVOICE_TO_REWRITE_MESSAGE)
        return
      }
      const invoice = outcome.invoice
      const ready: RewriteBinding = {
        status: 'ready',
        key,
        orderId: requestOrderId,
        invoiceId: invoice.invoice_id,
        versionNumber: invoice.version_number,
        acceptedAt: invoice.accepted_at,
        acceptedCustomerName: invoice.accepted_customer_name,
        totalPaid: invoice.total_paid,
      }
      rewriteBindingRef.current = ready
      setRewriteBinding(ready)
      return
    }
    const failed: RewriteBinding = { status: 'error', key, orderId: requestOrderId }
    rewriteBindingRef.current = failed
    setRewriteBinding(failed)
  }

  function handleRetryRewriteBinding() {
    if (confirmAction !== 'rewrite' || invoiceActionBusy) return
    void startRewriteBindingRead()
  }

  // Batch A PR 2 (#149) - after a stale Rewrite: re-read the current invoice in the
  // background (never awaited by the failed request) so the known invoice — and
  // with it the pre-open overpaid hint — catches up. It never creates a binding
  // and never re-posts; a failure keeps the last known state and the stale
  // message, and a 404 changes nothing.
  function refreshCurrentInvoiceInBackground() {
    void readCurrentInvoice(false)
  }

  async function runRewriteInvoice() {
    // Rewrite is blocked when LAID — the backend is the authority (422
    // ORDER_LOCKED); this just disables the path early. Batch A PR 2 (#149): a
    // synchronous in-flight ref, and a READY binding for the open confirmation of
    // THIS order, whose version is captured before any await: the POST carries
    // that version and nothing else (never the mount probe, the known invoice or a
    // later read). An overpaid binding cannot be confirmed.
    if (invoiceActionBusy || locked || rewriteInFlightRef.current) return
    const binding = rewriteBindingRef.current
    if (
      binding === null ||
      binding.status !== 'ready' ||
      binding.orderId !== orderIdRef.current
    ) {
      return
    }
    if (
      overpaidAmountsFor(
        binding.totalPaid,
        financialSummaryRef.current?.final_sale_price_inc_gst,
      ) !== null
    ) {
      return
    }
    const expectedVersionNumber = binding.versionNumber
    const boundTotalPaid = binding.totalPaid
    const requestOrderId = binding.orderId
    rewriteInFlightRef.current = true
    setRewritingInvoice(true)
    setInvoiceActionError(null)
    setInvoicePreconditions([])
    try {
      // Codex P1 + Batch A #107: flush the Customer tab and then, last, the pending
      // Details of Sale autosaves so the rewritten invoice snapshots the latest SAVED
      // customer and details. On failure stay on Details and do NOT rewrite.
      const detailsReady = await flushDetailsBeforeInvoice()
      if (!detailsReady) return
      if (!mountedRef.current || orderIdRef.current !== requestOrderId) return
      // D.2 rewrite — since Batch A PR 2 (#149) the body is exactly
      // {"expected_version_number": N}, the version the confirmation was bound to;
      // the server compares it under the order lock. Regenerates the current
      // invoice. On success switch straight to the Invoice tab (no success notice,
      // no View button) so the rewritten invoice is shown immediately.
      await rewriteInvoice(requestOrderId, expectedVersionNumber)
      if (!mountedRef.current) return
      setInvoiceExists(true)
      onInvoiceReady()
    } catch (err) {
      if (!mountedRef.current) return
      // Batch A PR 2 (#149): another version became current after the
      // confirmation was bound (a signature, a payment, a void, Path A or another
      // Rewrite). Handled FIRST, before the overpaid fallback, so it is never
      // masked. Show the server message, drop the binding and re-read in the
      // background (not awaited here, so finally still closes the modal and frees
      // the busy flag). Never re-posted: a new click opens a new confirmation,
      // bound to a fresh read (and warns when that version is signed).
      if (err instanceof ApiError && err.code === 'INVOICE_VERSION_STALE') {
        rewriteBindingRef.current = null
        setRewriteBinding(null)
        setInvoicePreconditions([])
        setInvoiceActionError(
          err.message.length > 0 ? err.message : INVOICE_STALE_MESSAGE,
        )
        refreshCurrentInvoiceInBackground()
        return
      }
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
      // Defensive: if the rewrite still failed and the BOUND invoice's payments
      // already exceed the new total, show the friendly overpayment message rather
      // than a generic "unexpected error".
      const overpaid = overpaidAmountsFor(
        boundTotalPaid,
        financialSummaryRef.current?.final_sale_price_inc_gst,
      )
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
      rewriteInFlightRef.current = false
      if (mountedRef.current) {
        setRewritingInvoice(false)
        closeInvoiceActionModal()
      }
    }
  }

  // Create: ALWAYS opens the action modal (Phase 16D-B, doc-locked — the button
  // no longer creates directly). The modal carries the GP warning when active
  // and offers Create Quote while no quote exists for this order; confirming
  // runs the unchanged runCreateInvoice. Deliberately does NOT clear
  // invoiceActionError/invoicePreconditions here (unlike the rewrite click): a
  // visible precondition checklist must survive an open-then-cancel so the user
  // can still fix the listed items; runCreateInvoice clears both on confirm.
  function handleCreateInvoiceClick() {
    if (invoiceActionBusy || invoiceExists === null) return
    setConfirmAction('create')
  }

  // Phase 16D-B: Create Quote from the action modal. Closes the modal (through the
  // shared close path, which also drops any Rewrite binding, so a Confirm can never
  // post afterwards) and hands off to the shell (reveal Quote tab + switch). No
  // invoice call, no order mutation. Only actionable once the quote probe
  // confirmed no saved draft.
  function handleCreateQuoteClick() {
    if (invoiceActionBusy || createQuoteAvailability !== 'enabled') return
    closeInvoiceActionModal()
    onCreateQuote()
  }

  // Rewrite: ALWAYS confirm (it regenerates the invoice and makes the changes
  // official). The GP warning, when active, is shown in the same modal. Batch A
  // PR 2 (#149): only from a mounted tab with a known invoice, nothing in flight
  // and NO modal already open (keyboard focus can reach this button behind the
  // modal); every opening gets a fresh key and its own read of the current invoice
  // (startRewriteBindingRead), and Confirm stays disabled until that read is bound.
  function handleRewriteInvoiceClick() {
    if (!mountedRef.current) return
    if (
      invoiceActionBusy ||
      rewriteInFlightRef.current ||
      locked ||
      invoiceExists !== true ||
      confirmAction !== null
    ) {
      return
    }
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
    void startRewriteBindingRead()
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
  // When there are no tenant quick-add presets the panel is hidden, so the
  // description field takes the full grid width instead of leaving an empty column.
  const hasQuickDescriptions = quickDescriptions.length > 0
  // Whether the order has a live financial summary yet (priced).
  const priced = financialSummary !== null
  // Sale-price input + Update are disabled when locked, mid-request, or unpriced.
  // The in-flight flag comes from the parent so it survives this tab's remount.
  // Update Sale Price is ALSO disabled until the input has been edited (16G PR 6,
  // #121; see updateSalePriceDisabled below) — the input itself stays editable.
  const salePriceControlsDisabled =
    locked || salePriceMutationInFlight || !priced
  const updateSalePriceDisabled = salePriceControlsDisabled || !salePriceDirty
  // Reset is idempotent on the backend (clearing an already-NULL adjustment is a
  // no-op), so it only depends on the order being editable and no request being
  // in flight — never on the input state or whether an adjustment exists.
  const resetDisabled = locked || salePriceMutationInFlight
  const targetGpCostMessage = targetGpCostUnavailableMessage(financialSummary)
  const targetGpControlsDisabled =
    locked || salePriceMutationInFlight || targetGpCostMessage !== null
  // Codex round 2: the ONLY Create Quote entry normally lives in the action
  // modal, but the overpaid-rewrite guard makes handleRewriteInvoiceClick
  // return BEFORE opening that modal — so on an unlocked order with an existing
  // invoice in the overpaid state, quotes would be unreachable even though
  // quote creation is independent of rewriting. When (and only when) the modal
  // is unreachable for exactly that reason, surface an inline Create Quote with
  // the SAME availability semantics as the modal button. Never on locked orders
  // (LAID UX unchanged — availability is 'hidden' there anyway when no draft),
  // never while probing invoiceExists, and never while the modal is open (no
  // duplicate Create Quote controls).
  const overpaidInlineQuoteVisible =
    !locked &&
    invoiceExists === true &&
    confirmAction === null &&
    overpaidRewriteAmounts() !== null &&
    createQuoteAvailability !== 'hidden'
  // Batch A PR 2 (#149): the open Rewrite confirmation's binding, only for THIS
  // order. Confirm is enabled exactly when it is ready and not overpaid; the
  // signed warning renders from the same binding, so it appears in the same render
  // that enables Confirm.
  const openRewriteBinding =
    confirmAction === 'rewrite' &&
    rewriteBinding !== null &&
    rewriteBinding.orderId === orderId
      ? rewriteBinding
      : null
  const rewriteBindingOverpaid =
    openRewriteBinding !== null && openRewriteBinding.status === 'ready'
      ? overpaidAmountsFor(
          openRewriteBinding.totalPaid,
          financialSummary?.final_sale_price_inc_gst,
        )
      : null
  const rewriteConfirmReady =
    openRewriteBinding !== null &&
    openRewriteBinding.status === 'ready' &&
    rewriteBindingOverpaid === null

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
          <div className={hasQuickDescriptions ? 'lg:col-span-2' : 'lg:col-span-3'}>
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

          {hasQuickDescriptions && (
            <div className="lg:col-span-1">
              <div className="rounded-lg border border-slate-200 bg-slate-50/60 p-4 h-full">
                <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-600">
                  Quick add descriptions
                </h3>
                <p className="text-[11px] text-slate-500 mt-1">
                  Tap + to append a preset to the description.
                </p>
                <ul className="mt-3 space-y-2">
                  {quickDescriptions.map((snippet) => (
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
          )}
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
              disabled={updateSalePriceDisabled}
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

        {/* Phase 16D-B: inline quote-probe failure notice. The action modal also
            shows this, but the modal is not always reachable (Rewrite is
            LAID-disabled; the overpaid guard returns before opening), so a
            failed probe must be retryable from here too — otherwise a saved
            quote could stay hidden for the whole session. Suppressed while the
            overpaid inline Create Quote block below is showing — that block
            renders its own Retry for the error state (no duplicate controls). */}
        {createQuoteAvailability === 'error' && !overpaidInlineQuoteVisible && (
          <div className="mt-2 flex items-center gap-2 sm:justify-end">
            <span className="text-xs font-medium text-amber-700">
              Could not check this order's quote status.
            </span>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={onRetryQuoteProbe}
            >
              Retry
            </Button>
          </div>
        )}

        {/* Codex round 2: inline Create Quote, shown ONLY while the
            overpaid-rewrite guard makes the action modal unreachable (see
            overpaidInlineQuoteVisible). Same availability semantics and the
            same handler as the modal's Create Quote button — it never opens
            the modal, never calls create/rewrite invoice, never mutates order
            pricing. */}
        {overpaidInlineQuoteVisible && (
          <div className="mt-2 flex flex-col items-start gap-1.5 sm:items-end">
            <p className="text-xs text-slate-600">
              You can still create a quote for this order.
            </p>
            <div className="flex items-center gap-2">
              {createQuoteAvailability === 'error' && (
                <>
                  <span className="text-xs font-medium text-amber-700">
                    Could not check this order's quote status.
                  </span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={onRetryQuoteProbe}
                  >
                    Retry
                  </Button>
                </>
              )}
              <Button
                type="button"
                variant="secondary"
                size="md"
                onClick={handleCreateQuoteClick}
                disabled={
                  invoiceActionBusy || createQuoteAvailability !== 'enabled'
                }
              >
                {createQuoteAvailability === 'checking'
                  ? 'Checking…'
                  : 'Create Quote'}
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* Invoice ACTION modal (Phase 16D-B): both Create and Rewrite now ALWAYS
          confirm here. The GP warning, when active, is shown so the user
          explicitly accepts the below-threshold price — it never removes or
          blocks Create Quote. While no quote exists for this order the modal
          also offers Create Quote (a separate sibling action). The Create
          confirmation and the GP warning are frontend steps only. Batch A PR 2
          (#149): the Rewrite confirmation is bound to its own fresh read of the
          current invoice (Checking… / an error with Try again / ready, with the
          signed warning when that version is signed); Confirm posts that
          version, which the server compares under the order lock. */}
      <Modal
        open={confirmAction !== null}
        onClose={() => {
          if (!invoiceActionBusy) closeInvoiceActionModal()
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

          {/* Batch A PR 2 (#149): the binding read for this opening. No version
              number is shown (the MVP shows no version labels). */}
          {confirmAction === 'rewrite' &&
            (openRewriteBinding === null ||
            openRewriteBinding.status === 'loading' ? (
              <p className="mt-3 text-sm text-slate-500">
                Checking the current invoice…
              </p>
            ) : openRewriteBinding.status === 'error' ? (
              <div className="mt-3 flex flex-col gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                <p className="text-sm text-amber-800">
                  The current invoice could not be checked, so the rewrite cannot
                  be confirmed yet.
                </p>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  disabled={invoiceActionBusy}
                  onClick={handleRetryRewriteBinding}
                >
                  Try again
                </Button>
              </div>
            ) : (
              <>
                {openRewriteBinding.acceptedAt !== null && (
                  <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
                    <p className="break-words text-sm font-semibold text-amber-900">
                      This invoice was signed by{' '}
                      {openRewriteBinding.acceptedCustomerName ?? 'the customer'}{' '}
                      on {formatTimestamp(openRewriteBinding.acceptedAt)}.
                      Rewriting it clears the signature, and the customer will
                      need to sign the new invoice again.
                    </p>
                  </div>
                )}
                {rewriteBindingOverpaid !== null && (
                  <p className="mt-3 text-sm font-medium text-red-600" role="alert">
                    {overpaidRewriteMessage(rewriteBindingOverpaid)}
                  </p>
                )}
              </>
            ))}

          {/* Customer-safe manager-approval warning. Always plain red text (no
              yellow background/border, no GP-tier colours, not derived from GP%) —
              the salesperson may show this screen to the customer. The GP-warning
              LOGIC is unchanged; only the wording/style here is customer-safe. */}
          {gpWarning && (
            <p className="mt-3 text-sm font-semibold text-red-600">
              Manager approval required before proceeding with this invoice.
            </p>
          )}

          {/* Phase 16D-B: Create Quote — offered only while the Quote tab does
              not exist for this order ('hidden' otherwise). Disabled while the
              quote probe is unresolved or failed (never "safely available" on a
              failed probe), and NOT gated on the GP warning. */}
          {createQuoteAvailability !== 'hidden' && (
            <div className="mt-4 rounded-lg border border-slate-200 bg-slate-50/60 px-4 py-3">
              <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
                <p className="text-xs text-slate-600 leading-relaxed">
                  Want to quote the customer first? A quote is separate from the
                  invoice and does not change the order.
                </p>
                <Button
                  type="button"
                  variant="secondary"
                  size="md"
                  onClick={handleCreateQuoteClick}
                  disabled={
                    invoiceActionBusy || createQuoteAvailability !== 'enabled'
                  }
                  className="shrink-0"
                >
                  {createQuoteAvailability === 'checking'
                    ? 'Checking…'
                    : 'Create Quote'}
                </Button>
              </div>
              {createQuoteAvailability === 'error' && (
                <div className="mt-2 flex items-center gap-2">
                  <p className="text-xs font-medium text-amber-700">
                    Could not check this order's quote status.
                  </p>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={onRetryQuoteProbe}
                  >
                    Retry
                  </Button>
                </div>
              )}
            </div>
          )}

          <div className="mt-6 flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              size="md"
              onClick={closeInvoiceActionModal}
              disabled={invoiceActionBusy}
            >
              Cancel
            </Button>
            <Button
              type="button"
              variant="success"
              size="md"
              onClick={handleConfirmInvoiceAction}
              disabled={
                invoiceActionBusy ||
                (confirmAction === 'rewrite' && !rewriteConfirmReady)
              }
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
