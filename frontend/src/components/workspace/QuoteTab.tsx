import { useEffect, useMemo, useRef, useState } from 'react'
import DOMPurify from 'dompurify'
import { Button } from '../ui/Button'
import { Input } from '../ui/Input'
import { Modal } from '../ui/Modal'
import { Tabs } from '../ui/Tabs'
import { CheckCircleIcon, ChevronDownIcon, PlusIcon, TrashIcon } from '../icons'
import { ApiError } from '../../lib/api/ApiError'
import {
  QUOTE_TOTAL_MAX,
  cancelQuote,
  createInvoiceFromQuote,
  fetchAcceptedQuoteSignature,
  fetchQuotePreviewPdf,
  fetchQuoteStoredPdf,
  fetchQuoteWorkspace,
  saveQuoteDraft,
  sendQuoteEmail,
  type QuoteAcceptedSummary,
  type QuoteChannel,
  type QuoteDraftLineInput,
  type QuoteDraftLineRead,
  type QuoteDraftRead,
  type QuoteDraftSaveRequest,
  type QuoteIssuedSummary,
  type QuoteLineType,
} from '../../lib/api/orderQuoteApi'
import {
  fetchCurrentInvoice,
  type InvoiceDetail,
} from '../../lib/api/orderInvoicesApi'
import {
  fetchOrderLines,
  type OrderLinesResponse,
} from '../../lib/api/orderLinesApi'
import type {
  DetailsOfSaleFields,
  OrderAddress,
  OrderCustomer,
} from '../../lib/api/orderWorkspaceApi'
import {
  fetchTenantInvoiceConfig,
  type TenantInvoiceConfig,
} from '../../lib/api/tenantInvoiceConfigApi'
import { fetchPublicBusiness } from '../../lib/api/tenantApi'
import { getActiveSlug } from '../../lib/tenant'
import type { FlooringType } from '../../lib/flooring'

// Phase 16D-B PR1 + PR2B + 16E-B + 16F PR3 - the salesperson Quote tab
// (docs/Phase16D-Quotation-UX-Lock.md).
//
// Three internal sub-tabs: Quote Draft (functional), Customer Quote (functional
// since 16E-B - the ISSUED quote surface) and Accepted Quote (functional since
// 16F PR3 - the latest ACCEPTED quote). The Quote Draft is an invoice-style
// QUOTATION canvas mirroring InvoiceTab's document layout, NOT a generic form.
//
// 16F PR3 rules (locked):
//   - Accepted Quote renders from the workspace `accepted` summary ONLY (the
//     frozen accepted snapshot): never the live draft, order customer/address or
//     tenant terms. Signature image via accepted.signature_download_path
//     (verbatim, credentialed blob); Preview signed PDF streams the STORED signed
//     PDF (no flush, no regeneration); Create Invoice (Path A) is enabled by the
//     server's invoice_eligible only (never LAID, a draft, a newer issued quote or
//     any client-side date comparison), confirms first, posts exactly {} and then
//     re-reads the snapshots and switches to the Invoice tab.
//   - Issued/accepted refresh: the tab re-reads the workspace snapshots when the
//     top-level tab becomes active, on a sub-tab change and before the Send
//     confirmation opens (the D9 warning needs the current accepted state). ONE
//     recency mechanism covers every snapshot read and every send / cancel /
//     conversion result (see refreshSnapshots), and a refresh only ever replaces
//     `issued` + `accepted` together. It NEVER touches the draft rows, mode, total
//     input, server-draft baseline, save queue or autosave status: the response's
//     draft is ignored (#101 stays out of scope), so a refresh never causes a PUT.
//
// 16E-B delivery rules (locked):
//   - Send by Email goes through the confirmation modal ONLY (no one-click
//     send), runs the SAME double-flush as Preview PDF (details autosave, then
//     quote autosave — the backend issues from PERSISTED state), and is blocked
//     when either flush fails. An in-flight guard prevents duplicate sends.
//   - SMS stays DISABLED ("Available soon") — no sendQuoteSms wrapper exists;
//     frontend SMS enablement is 16E-C.
//   - The Customer Quote sub-tab renders from the `issued` summary state
//     (seeded from the probe's current_issued, replaced by send responses and
//     snapshot refreshes, nulled by a successful cancel) and from it ONLY - never
//     from the live draft rows / totals / details / terms, which may have drifted
//     since the issue was frozen. With no active issued quote but an accepted
//     one, it shows the accepted state instead of the never-sent empty state.
//   - LAID: Send/Resend disabled; Cancel quote and issued Preview PDF stay
//     enabled (cancel only kills a public link; the stored PDF is a read).
//
// PR2B adds ITEMISED editing on top of PR1's non-itemised total:
//   - a toggle switches the draft between the two modes (no warnings/modals);
//   - toggle ON restores the draft's existing lines[] (retained dormant rows
//     included — Phase 16D-B PR2A returns them on reads even when
//     itemised=false), and only seeds from Products & Charges when the draft
//     has NO lines at all;
//   - toggle OFF copies the itemised inc-GST total into the non-itemised total
//     and saves {itemised:false, final_total_inc_gst, lines:[]} — the backend
//     retains the itemised rows (PR2A), and the local rows are kept too;
//   - itemised saves OMIT final_total_inc_gst (locked wire rule): the client
//     keeps total = sum of visible rows and manages ADJUSTMENT rows itself, so
//     the backend's hidden auto-adjustment lever is never engaged.
// Quote rows are INDEPENDENT of the order's Products & Charges after seeding:
// editing them never mutates product/charge lines, order pricing, costs or the
// sale-price override, and never refreshes the order/header financials.
//
// This tab only ever mounts AFTER the shell's quote-workspace probe has
// SUCCEEDED (the tab is revealed either by that probe finding a saved draft, or
// by Create Quote — which is only enabled once the probe confirmed draft ===
// null). That ordering is the load-before-save guarantee: autosave can never
// fire against an unknown server state, so a full-replace PUT can never wipe a
// draft it hasn't seen (the lock doc's full-body-PUT wipe rule).
//
// Once revealed, OrderWorkspace keeps this tab MOUNTED across top-level tab
// switches (hidden with CSS, like CustomerTab), so in-progress quote work
// survives tab switches; the unmount flush below fires only on a real order
// switch / page leave.
//
// 16D-A decoupling (locked): a quote save never changes order/header pricing, so
// NOTHING here refreshes the workspace financial summary — the save response
// updates quote draft state only.
//
// COST DISCIPLINE (locked): no cost value is ever displayed or sent. gp_percent
// and below_cost are server-derived protected-surface signals and render OUTSIDE
// the customer-facing quotation canvas. Seeding picks EXPLICIT fields off the
// order-line DTOs — never a spread — so cost_snapshot/price_snapshot/ids can
// never leak into a quote payload (the backend 400s any such key anyway).

// Debounce after a change before autosaving — mirrors LeadEnquirySection.
const AUTOSAVE_DEBOUNCE_MS = 800

// Backend caps a quote line description at 500 chars (trimmed).
const DESCRIPTION_MAX = 500

// Description of the ADJUSTMENT row the intended-total helper appends. A plain
// visible row — never hidden, user-editable/deletable like any other line.
const ADJUSTMENT_HELPER_DESCRIPTION = 'Discount / price adjustment'

type QuoteSubTabId = 'draft' | 'customer' | 'accepted'

const SUB_TABS: Array<{ id: QuoteSubTabId; label: string }> = [
  { id: 'draft', label: 'Quote Draft' },
  { id: 'customer', label: 'Customer Quote' },
  { id: 'accepted', label: 'Accepted Quote' },
]

type Props = {
  orderId: number
  // LAID lock — reads/preview stay available; edits + toggle + autosave are
  // disabled. Create Invoice from the accepted quote is NOT gated by it.
  locked: boolean
  // Phase 16F PR3: true while the top-level Quote tab is the visible tab. Each
  // false -> true transition re-reads the issued/accepted snapshots.
  active: boolean
  flooringType: FlooringType
  orderNumber?: string
  customer?: OrderCustomer | null
  billingAddress?: OrderAddress | null
  saleDetails?: DetailsOfSaleFields | null
  // The draft returned by the shell's quote-workspace probe for THIS order
  // (null = confirmed no saved draft). The tab seeds ONCE from this and then owns
  // the live draft state; it is never re-seeded after mount.
  initialDraft: QuoteDraftRead | null
  // The active issued quote summary from the same probe (null = nothing issued).
  // Seeds the Customer Quote sub-tab ONCE; after mount the tab owns the issued
  // state (send responses and snapshot refreshes replace it; a successful cancel
  // nulls it).
  initialIssued: QuoteIssuedSummary | null
  // Phase 16F: the latest accepted quote from the same probe (null = none
  // accepted). Seeds the Accepted Quote sub-tab ONCE, alongside initialIssued.
  initialAccepted: QuoteAcceptedSummary | null
  // Phase 16F PR3: called with the backend success message after Create Invoice
  // from the accepted quote succeeds; the workspace switches to the Invoice tab.
  onInvoiceReady: (message: string) => void
  // Switches the workspace to the Customer tab when a send fails with
  // CUSTOMER_EMAIL_REQUIRED / CUSTOMER_EMAIL_INVALID so the salesperson can fix
  // the email where it lives (the InvoiceTab accept/resend precedent).
  onGoToCustomer?: () => void
  // Awaitable flush of any pending/in-flight Details of Sale autosave (lives in
  // the always-mounted shell — the SAME flush that gates invoice create/rewrite).
  // Codex P2: the quote preview PDF renders sales_order.details_of_sale from the
  // PERSISTED order, so Preview must flush pending details edits first or the
  // screen could show fresh details while the generated PDF shows stale ones.
  // Resolves true once the latest details draft is persisted; false blocks the
  // preview.
  flushDetailsAutosave: () => Promise<boolean>
}

// An editable quote line as held in component state. Numeric fields are RAW
// INPUT STRINGS (like PR1's total input) so typing is never fought; they are
// parsed/validated on every body build. `key` is a stable local identity for
// React keys across reorder/remove — NEVER quote_draft_line_id (ids are
// read-only and must not be sent back).
type ItemEditorRow = {
  key: string
  line_type: 'ITEM'
  description: string
  quantityInput: string
  unitPriceInput: string
}

type AdjustmentEditorRow = {
  key: string
  line_type: 'ADJUSTMENT'
  description: string
  amountInput: string
}

type EditorRow = ItemEditorRow | AdjustmentEditorRow

// Module-level key sequence: unique across remounts, no Date/random needed.
let rowKeySeq = 0
function nextRowKey(): string {
  rowKeySeq += 1
  return `qrow-${rowKeySeq}`
}

const MONEY_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

function formatMoney(value: number): string {
  return MONEY_FORMATTER.format(value)
}

function formatPercent(value: number | null): string {
  if (value === null) return '—'
  return `${value.toFixed(2)}%`
}

// Compact timestamp for the issued summary panel (copy of the InvoiceTab /
// PaymentsTab helper): "08 Jul 2026, 14:05". Returns '' for an unparsable value.
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

// Human label for the issued summary's delivery channel.
function channelLabel(channel: QuoteChannel | null): string {
  if (channel === 'EMAIL') return 'Email'
  if (channel === 'SMS') return 'SMS'
  return '—'
}

// True when the LATEST email send attempt was not delivered (Codex P2 rounds
// 1+3). last_emailed_at is the SUCCESS-ONLY stamp and on a successful send it
// always lands strictly AFTER the attempt marker (last_sent_at: stamped in the
// pre-delivery transaction; the success stamp in a follow-up transaction), so:
//   - no success stamp at all            -> the only/first attempt failed;
//   - success stamp OLDER than the latest -> a later resend failed: the stamp
//     attempt marker                        belongs to a PRIOR attempt whose
//                                           link the failed resend already
//                                           replaced (token REPLACED), so the
//                                           customer holds nothing usable;
//   - success stamp equal-or-later       -> the latest attempt was delivered.
// Both timestamps are backend LocalDateTime strings in the same clock, so the
// Date comparison is sound. SMS has no success marker — never "not delivered".
function emailNotDelivered(issued: QuoteIssuedSummary): boolean {
  if (issued.sent_channel !== 'EMAIL') return false
  if (issued.last_emailed_at === null) return true
  if (issued.last_sent_at === null) return false
  return (
    new Date(issued.last_emailed_at).getTime() <
    new Date(issued.last_sent_at).getTime()
  )
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

// Trim a nullable/optional string to visible content or null (mirrors InvoiceTab).
function nonBlank(value: string | null | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed && trimmed.length > 0 ? trimmed : null
}

function toFixed2(value: number): string {
  return value.toFixed(2)
}

// HALF_UP 2dp rounding with a float epsilon (copy of the DetailsOfSaleTab
// helper) so the value sent matches the backend's BigDecimal HALF_UP result and
// the saved baseline stays stable across response round-trips. Normalises -0 to
// 0 so a negative fraction that rounds to zero can never leak "-0" into JSON
// comparisons or display. Safe for typed inputs and for division by 1.1 (a /1.1
// quotient can never land on an exact half-cent); PRODUCTS of two 2dp values
// and the ×1.10 gross-up CAN land on exact halves where float error flips the
// result — those use the exact-cents helpers below instead.
function round2(value: number): number {
  if (!Number.isFinite(value)) return value
  const scaled = value * 100
  const epsilon = 1e-9
  const rounded =
    scaled >= 0
      ? Math.floor(scaled + 0.5 + epsilon) / 100
      : Math.ceil(scaled - 0.5 - epsilon) / 100
  return rounded === 0 ? 0 : rounded
}

// Exact ITEM line total: quantity × unit price for two 2dp values, multiplied
// in INTEGER CENTS so an exact-half product (e.g. 33.05 × 1987.10 = 65673.655)
// rounds HALF_UP identically to the server's BigDecimal recompute — float
// multiplication rounds such halves DOWN once its error exceeds round2's
// epsilon, and the screen would then contradict the persisted quote/PDF by one
// cent. Inputs are non-negative here (quantity > 0, unit >= 0). Returns null
// when the raw product leaves safe-integer range (astronomically over the
// DECIMAL(10,2) cap — the caller's QUOTE_TOTAL_MAX check would reject it
// anyway, but the comparison itself is only trustworthy on exact values).
function itemLineTotal(quantity: number, unitPrice: number): number | null {
  const qCents = Math.round(quantity * 100)
  const uCents = Math.round(unitPrice * 100)
  const raw = qCents * uCents
  if (!Number.isSafeInteger(raw)) return null
  return Math.floor((raw + 50) / 100) / 100
}

// Exact inc-GST derivation from a 2dp ex-GST total: ex × 1.10 in integer cents
// (an ex total ending in an odd 5-cents digit lands the gross-up on an exact
// half). HALF_UP with sign support — the ex sum can be negative via adjustment
// rows. |exCents| <= ~1e10, so raw stays a safe integer. Matches the backend's
// itemised inc derivation (BigDecimal ×1.10 HALF_UP 2dp) exactly.
function incFromExGst(ex: number): number {
  const exCents = Math.round(ex * 100)
  const raw = exCents * 110
  const cents =
    raw >= 0 ? Math.floor((raw + 50) / 100) : Math.ceil((raw - 50) / 100)
  return cents === 0 ? 0 : cents / 100
}

// Parse a money-like input string. Returns null for blank / non-numeric input;
// range rules are applied by the callers (each field has its own bounds).
function parseMoneyInput(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : null
}

// Build the COMPLETE non-itemised full-replace body, or null when the current
// input cannot form a valid body (blank / non-numeric / out of range). A null
// body is NEVER sent — that is the "no partial/unloaded draft saves" rule.
// Module-scope + pure so the unmount flush can use it without stale closures.
function buildNonItemisedBody(
  totalInput: string,
): QuoteDraftSaveRequest | null {
  const parsed = parseMoneyInput(totalInput)
  if (parsed === null || parsed < 0 || parsed > QUOTE_TOTAL_MAX) return null
  return {
    itemised: false,
    final_total_inc_gst: round2(parsed),
    lines: [],
  }
}

// Parse ONE editor row to its wire line + signed ex-GST amount, or null when
// any field is invalid (blank/NaN/out of range). Mirrors the backend's
// per-line validation exactly:
//   ITEM        — description trimmed 1..500; quantity > 0; unit price >= 0;
//                 line_total = quantity × unit price, HALF_UP 2dp (the server
//                 recomputes the same product, so what is sent/displayed always
//                 matches what persists).
//   ADJUSTMENT  — description trimmed 1..500; SIGNED amount; quantity and
//                 unit_price_ex_gst are OMITTED (a present value is a 400).
// quote_draft_line_id / cost / *_snapshot fields are never constructed here.
function parseEditorRow(
  row: EditorRow,
  sortOrder: number,
): { amount: number; line: QuoteDraftLineInput } | null {
  const description = row.description.trim()
  if (description.length === 0 || description.length > DESCRIPTION_MAX) {
    return null
  }
  if (row.line_type === 'ITEM') {
    const quantityParsed = parseMoneyInput(row.quantityInput)
    const unitParsed = parseMoneyInput(row.unitPriceInput)
    if (quantityParsed === null || unitParsed === null) return null
    // Round to 2dp FIRST (the server parses both as 2dp HALF_UP money), then
    // validate the rounded values — "0.001" rounds to 0.00 and must fail the
    // quantity > 0 rule here exactly as it would on the server.
    const quantity = round2(quantityParsed)
    const unitPrice = round2(unitParsed)
    if (quantity <= 0 || quantity > QUOTE_TOTAL_MAX) return null
    if (unitPrice < 0 || unitPrice > QUOTE_TOTAL_MAX) return null
    const lineTotal = itemLineTotal(quantity, unitPrice)
    if (lineTotal === null || lineTotal > QUOTE_TOTAL_MAX) return null
    return {
      amount: lineTotal,
      line: {
        line_type: 'ITEM',
        description,
        quantity,
        unit_price_ex_gst: unitPrice,
        line_total_ex_gst: lineTotal,
        sort_order: sortOrder,
      },
    }
  }
  const amountParsed = parseMoneyInput(row.amountInput)
  if (amountParsed === null) return null
  const amount = round2(amountParsed)
  if (Math.abs(amount) > QUOTE_TOTAL_MAX) return null
  return {
    amount,
    line: {
      line_type: 'ADJUSTMENT',
      description,
      line_total_ex_gst: amount,
      sort_order: sortOrder,
    },
  }
}

// Build the COMPLETE itemised full-replace body, or null when ANY row is
// invalid or the derived totals overflow DECIMAL(10,2). sort_order is
// reassigned 0..n-1 from the visible row order on EVERY build, so reorders are
// always reflected in the payload. final_total_inc_gst is deliberately ABSENT
// (locked wire rule — see the header comment).
function buildItemisedBody(rows: EditorRow[]): QuoteDraftSaveRequest | null {
  const lines: QuoteDraftLineInput[] = []
  let exSum = 0
  for (const row of rows) {
    const parsed = parseEditorRow(row, lines.length)
    if (parsed === null) return null
    exSum += parsed.amount
    lines.push(parsed.line)
  }
  exSum = round2(exSum)
  if (Math.abs(exSum) > QUOTE_TOTAL_MAX) return null
  if (Math.abs(incFromExGst(exSum)) > QUOTE_TOTAL_MAX) return null
  return { itemised: true, lines }
}

// The single mode-aware body builder every save path goes through (autosave,
// blur flush, preview flush, unmount flush). Exactly one of the two shapes is
// ever produced: {itemised:false, final_total_inc_gst, lines:[]} or
// {itemised:true, lines:[...]} with NO final_total_inc_gst key.
function buildDraftBody(
  itemised: boolean,
  totalInput: string,
  rows: EditorRow[],
): QuoteDraftSaveRequest | null {
  return itemised ? buildItemisedBody(rows) : buildNonItemisedBody(totalInput)
}

// Live itemised totals from the VISIBLE rows only. Invalid rows contribute
// nothing: they block every itemised save, and toggle OFF falls back to the
// last PERSISTED total while a row is broken — so a partial-sum total is only
// ever persisted when no draft has been saved at all. inc = ex × 1.10 HALF_UP
// 2dp in exact cents, matching the backend's itemised derivation exactly.
function computeItemisedTotals(rows: EditorRow[]): { ex: number; inc: number } {
  let ex = 0
  for (const row of rows) {
    const parsed = parseEditorRow(row, 0)
    if (parsed !== null) ex += parsed.amount
  }
  const exRounded = round2(ex)
  return { ex: exRounded, inc: incFromExGst(exRounded) }
}

// Per-field validity flags for row input styling (parseEditorRow collapses a
// row to valid/invalid; the UI needs to highlight WHICH field is wrong).
function rowFieldFlags(row: EditorRow): {
  description: boolean
  quantity: boolean
  unitPrice: boolean
  amount: boolean
} {
  const description = row.description.trim()
  const descriptionInvalid =
    description.length === 0 || description.length > DESCRIPTION_MAX
  if (row.line_type === 'ITEM') {
    const quantityParsed = parseMoneyInput(row.quantityInput)
    const quantity = quantityParsed === null ? null : round2(quantityParsed)
    const unitParsed = parseMoneyInput(row.unitPriceInput)
    const unitPrice = unitParsed === null ? null : round2(unitParsed)
    return {
      description: descriptionInvalid,
      quantity: quantity === null || quantity <= 0 || quantity > QUOTE_TOTAL_MAX,
      unitPrice:
        unitPrice === null || unitPrice < 0 || unitPrice > QUOTE_TOTAL_MAX,
      amount: false,
    }
  }
  const amountParsed = parseMoneyInput(row.amountInput)
  const amount = amountParsed === null ? null : round2(amountParsed)
  return {
    description: descriptionInvalid,
    quantity: false,
    unitPrice: false,
    amount: amount === null || Math.abs(amount) > QUOTE_TOTAL_MAX,
  }
}

// Convert persisted draft lines (workspace probe / save response) into editable
// rows, in sort_order order. Server-validated data, so ITEM quantity/unit are
// always present — the ?? 0 fallbacks are purely defensive (a 0 quantity would
// render as an invalid row the user must fix, never a silent wrong save).
function readLinesToRows(lines: QuoteDraftLineRead[]): EditorRow[] {
  return [...lines]
    .sort((a, b) => a.sort_order - b.sort_order)
    .map((line): EditorRow => {
      if (line.line_type === 'ITEM') {
        return {
          key: nextRowKey(),
          line_type: 'ITEM',
          description: line.description,
          quantityInput: toFixed2(line.quantity ?? 0),
          unitPriceInput: toFixed2(line.unit_price_ex_gst ?? 0),
        }
      }
      return {
        key: nextRowKey(),
        line_type: 'ADJUSTMENT',
        description: line.description,
        amountInput: toFixed2(line.line_total_ex_gst),
      }
    })
}

// Seed itemised rows from the order's REAL Products & Charges — the one
// automatic seed case (draft has no lines at all). EXPLICIT field picking only:
// the source DTOs carry cost_snapshot / price_snapshot / ids that must never
// reach a quote payload, so nothing here spreads a source object.
//   - product lines first, charge lines after (locked seed order);
//   - quantity follows the pricing unit (LM → quantity_lm, SQM → quantity_sqm);
//   - unit price / amount are the sale-side EX-GST values copied directly (no
//     GST conversion — both sides are ex-GST). The order backend computes
//     line_total off the pricing-unit quantity, so the editor's derived
//     quantity × unit price equals the sale-side line total.
function seedRowsFromOrderLines(data: OrderLinesResponse): EditorRow[] {
  const rows: EditorRow[] = []
  for (const line of data.product_lines) {
    const quantity =
      line.pricing_unit_snapshot === 'LM' ? line.quantity_lm : line.quantity_sqm
    rows.push({
      key: nextRowKey(),
      line_type: 'ITEM',
      description: line.product_name_snapshot,
      quantityInput: toFixed2(quantity),
      unitPriceInput: toFixed2(line.unit_price),
    })
  }
  for (const line of data.charge_lines) {
    rows.push({
      key: nextRowKey(),
      line_type: 'ITEM',
      description: line.charge_name_snapshot,
      quantityInput: toFixed2(line.quantity),
      unitPriceInput: toFixed2(line.unit_price),
    })
  }
  return rows
}

// The persisted-baseline JSON for the probe result, mode-aware. For an itemised
// draft the baseline is built through the SAME builder as live edits, so an
// untouched restored draft is never re-saved (identical construction ⇒
// identical JSON). A null return means "no persisted baseline" and the first
// valid body autosaves.
function initialBaseline(initialDraft: QuoteDraftRead | null): string | null {
  if (initialDraft === null) return null
  if (!initialDraft.itemised) {
    const body = buildNonItemisedBody(toFixed2(initialDraft.quote_total_inc_gst))
    return body === null ? null : JSON.stringify(body)
  }
  const body = buildItemisedBody(readLinesToRows(initialDraft.lines))
  return body === null ? null : JSON.stringify(body)
}

// Friendly message for a failed quote save. ORDER_LOCKED gets a specific line;
// backend messages (QUOTE_BELOW_COST / QUOTE_TOTAL_EXCEEDS_LINES /
// VALIDATION_FAILED / MALFORMED_JSON) surface VERBATIM.
function quoteSaveErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'ORDER_LOCKED') {
      return 'This order is laid and locked. The quote can no longer be edited.'
    }
    if (err.message.length > 0) return err.message
  }
  return 'Could not save the quote. Please try again.'
}

// Flatten VALIDATION_FAILED details[] ({ field?, message } or plain strings)
// into "field: message" lines (mirrors the PaymentsTab helper).
function quoteValidationDetails(details: unknown): string[] {
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

// Client-side fallback preview file name (the server Content-Disposition name is
// preferred when readable): quote-preview-{order_number}.pdf.
function previewFileName(orderNumber: string | undefined): string {
  const safeOrder =
    orderNumber && orderNumber.length > 0 ? orderNumber : 'order'
  return `quote-preview-${safeOrder}.pdf`
}

// --- Phase 16F PR3: Accepted Quote helpers. ---

// Explanation under a disabled Create Invoice (invoice_eligible false). The
// second sentence is the backend's own Path A refusal wording.
const ALREADY_CONVERTED_MESSAGE =
  'An invoice has already been created from this quote.'
const NEWER_SIGNATURE_REQUIRED_MESSAGE =
  'The current invoice was signed at the same time or later than this quote. A newer signed quote is required to create an invoice from a quote.'
const ACCEPTED_CHANGED_MESSAGE =
  'The accepted quote changed while the confirmation was open. Review the accepted quote before creating an invoice.'
const CONVERSION_UNKNOWN_MESSAGE =
  'The Create Invoice request did not complete, so its result is unknown. Check the Invoice tab before trying again.'

// Same instant? The two backend timestamp strings are compared as STRINGS (no
// Date arithmetic) at the precision both APIs share. accepted.accepted_at is
// serialized with fractional seconds (no @JsonFormat on the accepted summary)
// while InvoiceDetail.accepted_at is formatted to whole seconds, and a Path A
// invoice copies the quote's accepted_at exactly - so the first 19 characters
// (YYYY-MM-DDTHH:mm:ss) are the comparable form of both.
function sameSecondTimestamp(a: string, b: string): boolean {
  const left = a.slice(0, 19)
  return left.length === 19 && left === b.slice(0, 19)
}

// INVOICE_PRECONDITIONS_NOT_MET details[] ({ section?, field?, message } or
// plain strings) in the existing Details of Sale convention: "field: message",
// falling back to the section when there is no field.
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

// Statuses that mean "no readable answer from the service" (network failure, or
// a gateway / timeout in front of it): the request MAY have taken effect.
function isAmbiguousFailure(err: unknown): boolean {
  if (!(err instanceof ApiError)) return true
  return (
    err.status === 0 ||
    err.status === 502 ||
    err.status === 503 ||
    err.status === 504
  )
}

// The current-invoice read that supports the conversion wording: the
// confirmation text (no invoice / unsigned / signed) and the explanation under
// a disabled Create Invoice. INVOICE_NOT_FOUND is the genuine no-invoice state
// (invoice: null); any other failure is 'error' and never "no invoice".
type CurrentInvoiceLookup =
  | { status: 'idle' }
  | { status: 'loading'; key: number }
  | { status: 'ready'; key: number; invoice: InvoiceDetail | null }
  | { status: 'error'; key: number }

// The accepted version a Create Invoice confirmation was opened for. The POST
// itself carries no selector (the server converts the latest accepted quote);
// this binding only lets the frontend invalidate a confirmation that no longer
// matches what is on screen.
type ConvertTarget = {
  key: number
  orderId: number
  quoteVersionId: number
  versionNumber: number
  totalIncGst: number
}

// Outcome of one issued/accepted snapshot read (see refreshSnapshots).
type SnapshotRefreshOutcome = 'applied' | 'superseded' | 'failed' | 'gone'

// The pre-Send check re-issues a superseded read at most this many times in
// total before it reports a failure with a retry (a superseded read carries no
// confirmed state for the D9 warning).
const PRE_SEND_READ_ATTEMPTS = 3

export function QuoteTab({
  orderId,
  locked,
  active,
  flooringType,
  orderNumber,
  customer,
  billingAddress,
  saleDetails,
  initialDraft,
  initialIssued,
  initialAccepted,
  onInvoiceReady,
  flushDetailsAutosave,
  onGoToCustomer,
}: Props) {
  const [subTab, setSubTab] = useState<QuoteSubTabId>('draft')

  // Latest server-confirmed draft state (totals / gp_percent / below_cost /
  // lines / updated_at). Seeded from the shell probe; replaced by each successful
  // save response. Quote-only state — never lifted into workspace financials.
  const [serverDraft, setServerDraft] = useState<QuoteDraftRead | null>(
    initialDraft,
  )

  // The LIVE edit mode. Starts from the probe result; flipped by the toggle.
  // The persisted mode only changes when a save for the new mode succeeds —
  // toggling is itself a draft change that autosaves through the machinery.
  const [itemised, setItemised] = useState<boolean>(
    initialDraft !== null && initialDraft.itemised,
  )

  // The single editable field of a non-itemised draft: the GST-inclusive quote
  // total, as a raw input string. Seeded ONCE from the probe result; toggle OFF
  // rewrites it with the itemised inc total.
  const [totalInput, setTotalInput] = useState<string>(() =>
    initialDraft !== null && !initialDraft.itemised
      ? toFixed2(initialDraft.quote_total_inc_gst)
      : '',
  )

  // Editable itemised rows. Seeded from the probe when the draft is already
  // itemised; otherwise populated on toggle ON (restore or Products & Charges
  // seed). KEPT across toggle OFF (spec: local rows are never deleted) so a
  // later toggle ON restores the freshest local edits instantly.
  const [rows, setRows] = useState<EditorRow[]>(() =>
    initialDraft !== null && initialDraft.itemised
      ? readLinesToRows(initialDraft.lines)
      : [],
  )

  // Products & Charges seed lifecycle for toggle ON with an empty draft:
  // 'loading' shows the small inline state (mode does NOT flip yet — no
  // accidental empty itemised draft), 'error' shows the inline error + Retry.
  const [seedState, setSeedState] = useState<'idle' | 'loading' | 'error'>(
    'idle',
  )

  // Intended-total HELPER input (inc GST). Pure client-side aid: never sent to
  // the backend, never part of any save payload, never persisted. Drives only
  // the mismatch messages + the explicit "Add discount adjustment" button.
  const [intendedInput, setIntendedInput] = useState('')

  // Autosave status trio + the send-quote modal + preview state.
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saveErrorDetails, setSaveErrorDetails] = useState<string[]>([])
  const [previewing, setPreviewing] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [sendModalOpen, setSendModalOpen] = useState(false)

  // --- Phase 16E-B issued-quote state. ---
  // The active issued quote summary driving the Customer Quote sub-tab. Seeded
  // ONCE from the shell probe's current_issued; replaced by each successful
  // send-email response; set to NULL by a successful cancel — matching what a
  // page reload shows, because the workspace only returns current_issued for an
  // active ISSUED version. All Customer Quote rendering reads THIS object only,
  // never the live draft/details state (the issue snapshot is frozen).
  const [issued, setIssued] = useState<QuoteIssuedSummary | null>(initialIssued)
  // Send by Email lifecycle: in-flight flag (duplicate-send guard via ref),
  // in-modal error + the Customer-tab pointer flag for the CUSTOMER_EMAIL_*
  // codes (the InvoiceTab accept/resend precedent).
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)
  const [sendEmailFixNeeded, setSendEmailFixNeeded] = useState(false)
  // Cancel-quote confirmation lifecycle (its own modal, per the locked UX).
  const [cancelModalOpen, setCancelModalOpen] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState<string | null>(null)
  // One-line success notice ("Quote sent by email." / "Quote cancelled.") shown
  // above the sub-tab content so it is visible from any sub-tab.
  const [actionNotice, setActionNotice] = useState<string | null>(null)
  // Stored issued-PDF preview lifecycle (Customer Quote action row).
  const [issuedPreviewing, setIssuedPreviewing] = useState(false)
  const [issuedPreviewError, setIssuedPreviewError] = useState<string | null>(
    null,
  )

  // --- Phase 16F PR3: accepted quote + snapshot refresh state. ---
  // The latest accepted quote driving the Accepted Quote sub-tab (and the D9
  // warning / Customer Quote accepted state). Seeded ONCE from the shell probe;
  // afterwards replaced only by snapshot refreshes, which always apply it
  // TOGETHER with `issued` from one workspace response.
  const [accepted, setAccepted] = useState<QuoteAcceptedSummary | null>(
    initialAccepted,
  )
  // Snapshot refresh status: a retryable failure keeps the last confirmed
  // issued/accepted state on screen (never a fabricated empty state).
  const [snapshotRefreshing, setSnapshotRefreshing] = useState(false)
  const [snapshotRefreshError, setSnapshotRefreshError] = useState<
    string | null
  >(null)
  // Bumped on every APPLIED snapshot read, so reads that depend on the latest
  // server state (the ineligibility explanation) re-run after each refresh.
  const [snapshotApplyCount, setSnapshotApplyCount] = useState(0)
  // Pre-Send refresh (D9): the Send confirmation opens only after a successful
  // re-read of the snapshots; a failed read shows a retry instead.
  const [sendCheck, setSendCheck] = useState<'idle' | 'checking' | 'error'>(
    'idle',
  )
  // Accepted Quote sub-tab: the signature image (object URL owned by its
  // effect) loads only once the sub-tab has been opened.
  const [acceptedSubTabSeen, setAcceptedSubTabSeen] = useState(false)
  const [acceptedSignatureUrl, setAcceptedSignatureUrl] = useState<
    string | null
  >(null)
  const [acceptedSignatureFailed, setAcceptedSignatureFailed] = useState(false)
  const [acceptedSignatureReload, setAcceptedSignatureReload] = useState(0)
  const [acceptedPreviewing, setAcceptedPreviewing] = useState(false)
  const [acceptedPreviewError, setAcceptedPreviewError] = useState<
    string | null
  >(null)
  // Create Invoice (Path A) lifecycle: the confirmation bound to one accepted
  // version, its current-invoice read, the in-flight flag and the errors that
  // must outlive the modal (a drift refresh may invalidate it).
  const [convertModalOpen, setConvertModalOpen] = useState(false)
  const [convertTarget, setConvertTarget] = useState<ConvertTarget | null>(
    null,
  )
  const [convertLookup, setConvertLookup] = useState<CurrentInvoiceLookup>({
    status: 'idle',
  })
  const [converting, setConverting] = useState(false)
  const [convertError, setConvertError] = useState<string | null>(null)
  const [convertErrorDetails, setConvertErrorDetails] = useState<
    PreconditionFailure[]
  >([])
  const [convertNotice, setConvertNotice] = useState<string | null>(null)
  // After a conversion succeeded (or its outcome is unknown) Create Invoice stays
  // disabled until a snapshot read issued AFTER it applies - so a stale
  // invoice_eligible can never invite a duplicate conversion.
  const [conversionLock, setConversionLock] = useState<
    null | 'created' | 'unknown'
  >(null)
  // The explanation under a disabled Create Invoice needs the current invoice.
  const [eligibilityLookup, setEligibilityLookup] = useState<
    | { status: 'ready'; quoteVersionId: number; invoice: InvoiceDetail | null }
    | { status: 'error'; quoteVersionId: number }
    | null
  >(null)

  // Branding + per-type quote terms: two INDEPENDENT fail-soft fetch chains
  // (business name/logo is optional branding; the tenant invoice config is the
  // live TERMS source for the quote draft). Since 16F PR3 only this tab reads the
  // tenant config: InvoiceTab renders the server-selected InvoiceDetail
  // terms_html / terms_source instead. A quote draft has nothing to gate; the
  // config status here only drives the terms section + a small retry notice.
  const [tenantConfig, setTenantConfig] = useState<TenantInvoiceConfig | null>(
    null,
  )
  const [tenantConfigLoading, setTenantConfigLoading] = useState(true)
  const [tenantConfigError, setTenantConfigError] = useState(false)
  const [businessName, setBusinessName] = useState<string | null>(null)
  const [logoPath, setLogoPath] = useState<string | null>(null)
  const [logoFailed, setLogoFailed] = useState(false)
  const [brandingReloadToken, setBrandingReloadToken] = useState(0)

  // Latest values mirrored into refs so the debounce / blur / flush / unmount
  // paths always read fresh data (mirrors LeadEnquirySection). Handlers that
  // change these and then save synchronously ALSO write the ref directly —
  // the render mirror hasn't run yet at that point.
  const totalInputRef = useRef(totalInput)
  totalInputRef.current = totalInput
  const rowsRef = useRef(rows)
  rowsRef.current = rows
  const itemisedRef = useRef(itemised)
  itemisedRef.current = itemised
  const serverDraftRef = useRef(serverDraft)
  serverDraftRef.current = serverDraft
  const lockedRef = useRef(locked)
  lockedRef.current = locked
  const orderIdRef = useRef(orderId)
  orderIdRef.current = orderId
  // Phase 16F PR3 mirrors read by async continuations.
  const activeRef = useRef(active)
  activeRef.current = active
  const issuedRef = useRef(issued)
  issuedRef.current = issued
  const acceptedRef = useRef(accepted)
  acceptedRef.current = accepted
  const conversionLockRef = useRef(conversionLock)
  conversionLockRef.current = conversionLock
  const cancelModalOpenRef = useRef(cancelModalOpen)
  cancelModalOpenRef.current = cancelModalOpen
  const convertModalOpenRef = useRef(convertModalOpen)
  convertModalOpenRef.current = convertModalOpen

  // --- Phase 16F PR3: ONE recency mechanism for the issued/accepted snapshots.
  // snapshotSeqRef numbers every snapshot read when it is ISSUED, and every
  // mutation barrier/result (send, cancel, conversion). snapshotFloorRef is the
  // newest number whose data (or barrier) has been applied: a read applies only
  // when its number is ABOVE the floor, and then raises the floor to it. So an
  // older read can never overwrite a newer read, a read issued before a mutation
  // started can never land after it (the mutation raises the floor when it starts
  // and again when its own result applies), and a mutation's own result always
  // applies. All of it is per instance: an order change or unmount remounts the
  // tab (key={orderId}) and every continuation also checks mountedRef and the
  // captured order id.
  const snapshotSeqRef = useRef(0)
  const snapshotFloorRef = useRef(0)
  const snapshotReadsInFlightRef = useRef(0)
  // Snapshot sequence at which the conversion lock was taken; reads issued
  // after it release the lock when they apply.
  const conversionLockSeqRef = useRef(0)
  // Single-flight guards (refs, so a double click in one tick never runs twice).
  const sendCheckRef = useRef(false)
  const convertingRef = useRef(false)
  // The confirmation currently open (mirror of convertTarget) and its key
  // sequence: a lookup result is applied only for the key it was issued for.
  const convertTargetRef = useRef<ConvertTarget | null>(null)
  const convertKeySeqRef = useRef(0)

  const mountedRef = useRef(true)
  // JSON of the last body the backend confirmed persisted — the dirty baseline.
  // null = NO persisted draft yet (fresh Create Quote flow), so the first valid
  // body is always dirty and autosaves. Initialised from the probe result so a
  // freshly-loaded saved draft is never re-saved until it changes. Advanced ONLY
  // on a successful save response — a failed save stays dirty and keeps edits.
  const lastSavedBodyRef = useRef<string | null>(initialBaseline(initialDraft))
  const savingRef = useRef(false)
  const pendingBodyRef = useRef<QuoteDraftSaveRequest | null>(null)
  // Inc-GST total of the LATEST COMPLETE itemised body produced for a save —
  // sent, queued mid-flight, or skipped-as-unchanged — recorded at the single
  // build funnel (buildBodyForSave). The toggle-OFF broken-row fallback
  // prefers this over the last persisted total: while a valid itemised PUT is
  // still in flight, serverDraftRef is one save STALE, and copying it would
  // let the queued non-itemised save overwrite the just-saved newer total
  // (Codex P2: $100→$200 edit autosaves, add an incomplete row, toggle OFF →
  // the old $110 inc would persist instead of $220). null = no complete
  // itemised body was built this session. Cannot leak across drafts: the ref
  // is per instance and OrderWorkspace remounts this tab per order
  // (key={orderId}); a workspace reload remounts it too.
  const lastValidItemisedIncRef = useRef<number | null>(null)
  // Re-entrancy guard for the async toggle-OFF row flush: while the pre-flip
  // itemised flush is being awaited, further switch clicks are ignored (the
  // status chip already shows "Saving…"). Prevents double flips / duplicate
  // saves from rapid taps mid-flush.
  const toggleBusyRef = useRef(false)
  const debounceRef = useRef<number | null>(null)
  // In-flight guards for Send by Email and Cancel quote (16E-B): refs, not the
  // render state, so a double-click in the same tick can never fire twice.
  const sendingRef = useRef(false)
  const cancellingRef = useRef(false)
  // Awaitable single-flight chain (mirrors the shell's details chain) so Preview
  // PDF can flush pending saves and only proceed once the WHOLE queue drained.
  const saveChainRef = useRef<Promise<void> | null>(null)
  // Object URLs handed to the preview tab. Revoking immediately can blank the
  // tab mid-load, so they are revoked on unmount only.
  const previewUrlsRef = useRef<string[]>([])

  // Single-flight save loop: exactly one PUT in flight; on settle, drains the
  // latest queued body only if it differs from what is now persisted.
  async function runSave(body: QuoteDraftSaveRequest) {
    savingRef.current = true
    if (mountedRef.current) {
      setSaving(true)
      setSaved(false)
      setSaveError(null)
      setSaveErrorDetails([])
    }
    // Capture the order id this PUT is addressed to BEFORE awaiting.
    const saveOrderId = orderIdRef.current
    try {
      const res = await saveQuoteDraft(saveOrderId, body)
      // Mark persisted ONLY after a successful backend response.
      lastSavedBodyRef.current = JSON.stringify(body)
      if (mountedRef.current) {
        // Quote draft state only — NEVER an order/header financial refresh.
        setServerDraft(res.data)
        serverDraftRef.current = res.data
        // Canonical-total sync (non-itemised only, DEFENSIVE): if the server
        // ever reports an inc total numerically DIFFERENT from the one sent,
        // and the user has not typed a different value (and the mode has not
        // flipped) since this body was sent, sync the input + baseline to the
        // server-canonical total so the screen never contradicts the persisted
        // quote / the PDF. Since PR2A the backend carries final_total_inc_gst
        // through VERBATIM, so this branch should never fire — and the
        // equal-value case deliberately does NOT rewrite the input string: an
        // in-progress keystroke like '4900.' parses to the sent 4900 and would
        // otherwise be clobbered to '4900.00' mid-typing (caret jump, mangled
        // next keystroke). The ref is written immediately (not just via the
        // render mirror) so an awaiting flush re-checks against the synced
        // value, not a stale one. Itemised responses need NO sync: ITEM totals
        // are computed in exact cents (itemLineTotal) matching the server's
        // BigDecimal recompute, so sent === persisted.
        if (!res.data.itemised) {
          const currentBody = buildDraftBody(
            itemisedRef.current,
            totalInputRef.current,
            rowsRef.current,
          )
          if (
            currentBody !== null &&
            JSON.stringify(currentBody) === lastSavedBodyRef.current
          ) {
            const canonical = toFixed2(res.data.quote_total_inc_gst)
            const canonicalBody = buildNonItemisedBody(canonical)
            if (
              canonicalBody !== null &&
              JSON.stringify(canonicalBody) !== lastSavedBodyRef.current
            ) {
              lastSavedBodyRef.current = JSON.stringify(canonicalBody)
              totalInputRef.current = canonical
              setTotalInput(canonical)
            }
          }
        }
        setSaved(true)
      }
    } catch (err) {
      // Baseline NOT advanced — local edits stay dirty and retry on next change.
      if (mountedRef.current) {
        setSaveError(quoteSaveErrorMessage(err))
        setSaveErrorDetails(
          err instanceof ApiError && err.code === 'VALIDATION_FAILED'
            ? quoteValidationDetails(err.details)
            : [],
        )
      }
    } finally {
      savingRef.current = false
      const pending = pendingBodyRef.current
      pendingBodyRef.current = null
      if (
        pending !== null &&
        JSON.stringify(pending) !== lastSavedBodyRef.current
      ) {
        // AWAIT the drain so the chain promise spans the full queue.
        await runSave(pending)
      } else if (mountedRef.current) {
        setSaving(false)
      }
    }
  }

  // Starts a save chain and records its promise so flushQuoteAutosave can await
  // the full drain. runSave never rejects, so the chain always resolves.
  function startSaveChain(body: QuoteDraftSaveRequest) {
    const chain = runSave(body)
    saveChainRef.current = chain
    void chain.finally(() => {
      if (saveChainRef.current === chain) {
        saveChainRef.current = null
      }
    })
  }

  // Saves the current draft iff it forms a VALID complete body (for the CURRENT
  // mode) that differs from the persisted baseline. Invalid/blank input is never
  // sent (no partial saves — an itemised draft with any broken row sends
  // nothing).
  // QUEUE-FIRST while a PUT is in flight (matching the shell's details chain,
  // deliberately NOT LeadEnquirySection's check-baseline-first ordering): the
  // baseline is STALE mid-flight, so comparing against it here would silently
  // drop a revert-to-baseline edit (type 100, then retype the original before
  // the PUT settles -> the wrong total would persist). The drain compares the
  // queued body against the POST-save baseline and skips unchanged bodies.
  // The single funnel that builds the current mode-aware body for a SAVE
  // (autosave/blur via requestSave, preview via flushQuoteAutosave). Records
  // the inc total of every complete itemised body it produces — derived from
  // the body's own lines so it is exactly the total that save would persist —
  // for the toggle-OFF broken-row fallback (see lastValidItemisedIncRef).
  function buildBodyForSave(): QuoteDraftSaveRequest | null {
    const body = buildDraftBody(
      itemisedRef.current,
      totalInputRef.current,
      rowsRef.current,
    )
    if (body !== null && body.itemised) {
      let ex = 0
      for (const line of body.lines) ex += line.line_total_ex_gst
      lastValidItemisedIncRef.current = incFromExGst(round2(ex))
    }
    return body
  }

  function requestSave() {
    if (lockedRef.current) return
    const body = buildBodyForSave()
    if (body === null) return
    if (savingRef.current) {
      // Collapse to the latest complete body while a PUT is in flight.
      pendingBodyRef.current = body
      return
    }
    if (JSON.stringify(body) === lastSavedBodyRef.current) return
    startSaveChain(body)
  }

  function clearDebounce() {
    if (debounceRef.current !== null) {
      window.clearTimeout(debounceRef.current)
      debounceRef.current = null
    }
  }

  function scheduleAutosave() {
    if (lockedRef.current) return
    clearDebounce()
    debounceRef.current = window.setTimeout(() => {
      debounceRef.current = null
      requestSave()
    }, AUTOSAVE_DEBOUNCE_MS)
  }

  // Flush a pending debounce immediately (text-field blur).
  function flushNow() {
    clearDebounce()
    requestSave()
  }

  // Shared reset when the draft content changes: stale statuses must never sit
  // next to newer edits (a stale preview error next to a later "Saved" lies).
  function markDraftEdited() {
    setSaved(false)
    setSaveError(null)
    setSaveErrorDetails([])
    setPreviewError(null)
  }

  function handleTotalChange(value: string) {
    setTotalInput(value)
    markDraftEdited()
    // A lingering Products & Charges seed failure is stale once the user goes
    // on editing the non-itemised total.
    if (seedState === 'error') setSeedState('idle')
    scheduleAutosave()
  }

  // --- Itemised row editing. Every mutation goes through the same shape:
  // update state + ref synchronously, reset statuses, schedule/flush autosave.
  // Editing rows NEVER touches product/charge lines, order pricing or costs.

  function commitRows(next: EditorRow[]) {
    rowsRef.current = next
    setRows(next)
    markDraftEdited()
  }

  function handleRowFieldChange(
    key: string,
    field: 'description' | 'quantityInput' | 'unitPriceInput' | 'amountInput',
    value: string,
  ) {
    // The dynamic field only ever names a property of the row's own variant
    // (each input is rendered for its matching row type), so the cast is safe.
    const next = rowsRef.current.map((row) =>
      row.key === key ? ({ ...row, [field]: value } as EditorRow) : row,
    )
    commitRows(next)
    scheduleAutosave()
  }

  function handleAddRow(type: QuoteLineType) {
    const row: EditorRow =
      type === 'ITEM'
        ? {
            key: nextRowKey(),
            line_type: 'ITEM',
            description: '',
            quantityInput: '',
            unitPriceInput: '',
          }
        : {
            key: nextRowKey(),
            line_type: 'ADJUSTMENT',
            description: '',
            amountInput: '',
          }
    commitRows([...rowsRef.current, row])
    // The new blank row is invalid until filled, so this schedules a save that
    // requestSave will block — the draft simply shows "Unsaved changes" plus
    // the inline row validation until the row is complete.
    scheduleAutosave()
  }

  function handleRemoveRow(key: string) {
    commitRows(rowsRef.current.filter((row) => row.key !== key))
    scheduleAutosave()
  }

  function handleMoveRow(index: number, delta: -1 | 1) {
    const current = rowsRef.current
    const target = index + delta
    if (target < 0 || target >= current.length) return
    const next = [...current]
    const [moved] = next.splice(index, 1)
    next.splice(target, 0, moved)
    commitRows(next)
    // sort_order is reassigned 0..n-1 from array order on every body build, so
    // the reorder is fully captured by the autosave.
    scheduleAutosave()
  }

  // --- Itemised toggle. No warnings, no confirmation modals (locked UX). ---

  // The "previous quote total" used to prefill the intended-total helper when
  // toggling ON: the freshest non-itemised total the salesperson had — the
  // current input if it parses (it may not have autosaved yet), else the last
  // persisted total.
  function previousQuoteTotalIncGst(): number | null {
    const typed = parseMoneyInput(totalInputRef.current)
    if (typed !== null && typed >= 0 && typed <= QUOTE_TOTAL_MAX) {
      return round2(typed)
    }
    const draft = serverDraftRef.current
    return draft !== null ? draft.quote_total_inc_gst : null
  }

  // Prefill the helper input when the restored/seeded line sum differs from the
  // draft's previous quote total, so the salesperson immediately sees the
  // mismatch and gets offered the discount-adjustment fix. Cleared when they
  // already match (or there is no previous total).
  function prefillIntended(nextRows: EditorRow[], prevInc: number | null) {
    if (prevInc === null) {
      setIntendedInput('')
      return
    }
    const inc = computeItemisedTotals(nextRows).inc
    const prev = round2(prevInc)
    setIntendedInput(prev !== inc ? toFixed2(prev) : '')
  }

  function enterItemised(nextRows: EditorRow[], prevInc: number | null) {
    rowsRef.current = nextRows
    setRows(nextRows)
    itemisedRef.current = true
    setItemised(true)
    prefillIntended(nextRows, prevInc)
    // Toggling ON is itself a draft change: the itemised body autosaves through
    // the normal machinery so the persisted mode flips without a manual edit.
    requestSave()
  }

  // Seed from Products & Charges — ONLY when the draft has no lines[] at all.
  // The mode does not flip until the fetch succeeds, so a fetch failure can
  // never strand the editor on an accidental empty seed or autosave an empty
  // itemised draft.
  async function runSeed(prevInc: number | null) {
    setSeedState('loading')
    try {
      const res = await fetchOrderLines(orderIdRef.current)
      if (!mountedRef.current) return
      setSeedState('idle')
      enterItemised(seedRowsFromOrderLines(res.data), prevInc)
    } catch {
      if (!mountedRef.current) return
      setSeedState('error')
    }
  }

  async function handleToggleItemised() {
    if (
      lockedRef.current ||
      seedState === 'loading' ||
      toggleBusyRef.current
    ) {
      return
    }
    clearDebounce()
    markDraftEdited()
    if (itemisedRef.current) {
      // Toggle OFF: copy the current itemised inc-GST total into the
      // non-itemised total and save {itemised:false, final_total_inc_gst,
      // lines:[]}. The backend retains the itemised rows (PR2A) and the local
      // rows are deliberately kept, so toggling ON again restores them without
      // any re-seed.
      //
      // ROW-FLUSH GUARD (Codex P2 round 2): a row edit made inside the 800ms
      // debounce window would otherwise NEVER persist — the non-itemised save
      // is header-only (PR2A), so the server would keep the PREVIOUS itemised
      // rows and a reload (or 16E send) would silently lose the latest edits.
      // When the rows are all valid, flush the itemised body through the
      // normal single-flight machinery FIRST and only flip the mode once that
      // body IS the persisted state (an already-clean draft short-circuits
      // inside the flush — no extra PUT, so a no-pending-edit toggle still
      // issues exactly one non-itemised PUT). The flush also refreshes
      // lastValidItemisedIncRef via buildBodyForSave (round-1 consistency).
      // On any other outcome, stay itemised with the local edits intact — no
      // modal, no warning: 'save-failed' already surfaced the normal "Could
      // not save" status/error; 'changed-during-flush' / 'invalid-input' mean
      // the user kept editing mid-flush, and flipping then would re-open the
      // exact row-loss hole (their edits keep autosaving normally instead).
      const preBroken = rowsRef.current.some(
        (row) => parseEditorRow(row, 0) === null,
      )
      if (!preBroken) {
        toggleBusyRef.current = true
        const flushOutcome = await flushQuoteAutosave().finally(() => {
          toggleBusyRef.current = false
        })
        if (flushOutcome !== 'saved') return
        // Re-validate the world after the await: bail if the tab unmounted or
        // the mode was already flipped (defensive — the busy guard blocks the
        // switch itself while the flush runs).
        if (!mountedRef.current || lockedRef.current || !itemisedRef.current) {
          return
        }
      }
      // BROKEN-ROW GUARD: a mid-edit invalid row contributes nothing to the
      // visible sum, and while itemised that partial sum can never persist
      // (the invalid-row save block). A mode flip would convert it into a
      // VALID non-itemised body and silently persist an understated total —
      // so fall back to a truthful total instead, in freshness order:
      //   1. the latest COMPLETE itemised body built for save this session
      //      (lastValidItemisedIncRef) — NOT the persisted draft, which is one
      //      save STALE while that body's PUT is still in flight (Codex P2:
      //      copying it would overwrite the just-saved newer total);
      //   2. the last persisted total, when no complete itemised body was
      //      built this session;
      //   3. the partial visible sum, only when nothing was ever persisted
      //      AND no complete body exists (fresh unsaved draft — there is no
      //      truthful value to preserve).
      // Either way the copied total lands in the editable field where it is
      // fully visible.
      const currentRows = rowsRef.current
      const rowsBroken = currentRows.some(
        (row) => parseEditorRow(row, 0) === null,
      )
      const incTotal = rowsBroken
        ? (lastValidItemisedIncRef.current ??
          serverDraftRef.current?.quote_total_inc_gst ??
          computeItemisedTotals(currentRows).inc)
        : computeItemisedTotals(currentRows).inc
      const totalStr = toFixed2(incTotal)
      totalInputRef.current = totalStr
      setTotalInput(totalStr)
      itemisedRef.current = false
      setItemised(false)
      requestSave()
      return
    }
    // Toggle ON — restore-first, seed only when nothing to restore:
    //   1. local rows from this session (freshest edits, kept across toggle OFF);
    //   2. the draft's persisted lines[] (retained dormant rows — a legacy dev
    //      draft may restore a "Quoted works" row; deletable, never special-cased);
    //   3. seed from Products & Charges (the ONLY automatic seed case).
    // Existing rows are never silently re-seeded over.
    const prevInc = previousQuoteTotalIncGst()
    if (rowsRef.current.length > 0) {
      enterItemised(rowsRef.current, prevInc)
      return
    }
    const draft = serverDraftRef.current
    if (draft !== null && draft.lines.length > 0) {
      enterItemised(readLinesToRows(draft.lines), prevInc)
      return
    }
    void runSeed(prevInc)
  }

  // Append the visible discount ADJUSTMENT row the helper offers. Explicit
  // user click only — the helper never changes rows on its own. The amount is
  // the EX-GST difference (rows are ex-GST): target ex = round2(intended/1.1),
  // minus the current visible ex sum.
  function handleAddDiscountAdjustment() {
    const intendedParsed = parseMoneyInput(intendedInput)
    if (intendedParsed === null) return
    const intended = round2(intendedParsed)
    const totals = computeItemisedTotals(rowsRef.current)
    const adjustment = round2(round2(intended / 1.1) - totals.ex)
    setIntendedInput('')
    if (adjustment === 0) return
    const row: AdjustmentEditorRow = {
      key: nextRowKey(),
      line_type: 'ADJUSTMENT',
      description: ADJUSTMENT_HELPER_DESCRIPTION,
      amountInput: toFixed2(adjustment),
    }
    commitRows([...rowsRef.current, row])
    requestSave()
  }

  // Awaitable flush used by Preview PDF: force-commit the freshest draft if it
  // is valid + dirty, await the whole save chain, then report a DISCRIMINATED
  // outcome so the preview error message never lies:
  //   'saved'               — the freshest draft is now the persisted one.
  //   'invalid-input'       — the current draft cannot form a valid body.
  //   'changed-during-flush'— the flushed body persisted, but the user edited a
  //                           newer (valid) draft while the flush ran.
  //   'save-failed'         — a save failed and the baseline did not advance.
  async function flushQuoteAutosave(): Promise<
    'saved' | 'invalid-input' | 'changed-during-flush' | 'save-failed'
  > {
    clearDebounce()
    const startBody = buildBodyForSave()
    if (startBody === null) return 'invalid-input'
    const startJson = JSON.stringify(startBody)
    if (savingRef.current) {
      // QUEUE-FIRST (see requestSave): the mid-flight baseline is stale, so the
      // freshest body is always queued for the drain to reconcile.
      pendingBodyRef.current = startBody
    } else if (startJson !== lastSavedBodyRef.current) {
      startSaveChain(startBody)
    }
    const chain = saveChainRef.current
    if (chain) {
      await chain
    }
    const finalBody = buildDraftBody(
      itemisedRef.current,
      totalInputRef.current,
      rowsRef.current,
    )
    if (finalBody === null) return 'invalid-input'
    if (JSON.stringify(finalBody) === lastSavedBodyRef.current) return 'saved'
    // The freshest draft is not the persisted body. If the body we flushed IS
    // persisted, the user edited during the flush; otherwise the save failed.
    // (On success the canonical-total sync rewrites input+baseline together, so
    // an un-touched input still lands in the 'saved' branch above.)
    if (startJson === lastSavedBodyRef.current) return 'changed-during-flush'
    return 'save-failed'
  }

  // Mount/unmount guard + unmount flush. Because OrderWorkspace keeps this tab
  // mounted (hidden) across top-level tab switches, this cleanup fires only on a
  // REAL unmount: an order switch or leaving the workspace. Fire-and-forget with
  // the order id captured up front so a late settle can never target another
  // order. StrictMode's setup/cleanup/setup cycle is safe: at that point the
  // draft still equals the baseline, so nothing is sent.
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      clearDebounce()
      for (const url of previewUrlsRef.current) {
        URL.revokeObjectURL(url)
      }
      previewUrlsRef.current = []
      if (lockedRef.current) return
      const body = buildDraftBody(
        itemisedRef.current,
        totalInputRef.current,
        rowsRef.current,
      )
      if (body === null) return
      if (savingRef.current) {
        // A save is in flight — QUEUE-FIRST (the mid-flight baseline is stale;
        // see requestSave): its drain picks up this latest body and reconciles
        // against the post-save baseline. (StrictMode is unaffected — no save
        // is ever in flight during its immediate setup/cleanup cycle.)
        pendingBodyRef.current = body
        return
      }
      if (JSON.stringify(body) === lastSavedBodyRef.current) return
      const saveOrderId = orderIdRef.current
      saveQuoteDraft(saveOrderId, body).catch(() => {})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Branding + terms: two independent fail-soft chains. The config chain drives
  // ONLY the terms section here (no acceptance gate); the public-business chain
  // only ever sets name/logo (the same branding lookup InvoiceTab uses).
  useEffect(() => {
    let cancelled = false

    setTenantConfigLoading(true)
    setTenantConfigError(false)
    fetchTenantInvoiceConfig()
      .then((config) => {
        if (cancelled) return
        setTenantConfig(config)
        setTenantConfigError(false)
      })
      .catch(() => {
        if (cancelled) return
        setTenantConfig(null)
        setTenantConfigError(true)
      })
      .finally(() => {
        if (cancelled) return
        setTenantConfigLoading(false)
      })

    fetchPublicBusiness(getActiveSlug())
      .then((business) => {
        if (cancelled) return
        setBusinessName(nonBlank(business.name))
        setLogoPath(nonBlank(business.logo_path))
      })
      .catch(() => {
        if (cancelled) return
        setBusinessName(null)
        setLogoPath(null)
      })

    return () => {
      cancelled = true
    }
  }, [brandingReloadToken])

  // A new logo path is a fresh chance to load; clear any prior <img> failure.
  useEffect(() => {
    setLogoFailed(false)
  }, [logoPath])

  // Preview PDF — the backend renders the PERSISTED draft, so flush first (the
  // popup-safe order is locked: open a blank tab synchronously in the click
  // handler, flush, fetch the blob, then point the tab at the object URL; close
  // the tab instead on any failure so no stale/blank tab is left behind).
  async function handlePreviewPdf() {
    if (previewing || !previewEnabled || seedState === 'loading') return
    setPreviewError(null)
    // tab === null right here means a popup blocker intervened; a tab that
    // exists now but is CLOSED by the time the blob is ready means the user
    // deliberately closed it (a cancel — no download, no error).
    const tab = window.open('', '_blank')
    setPreviewing(true)
    try {
      if (!locked) {
        // Codex P2: the preview PDF renders sales_order.details_of_sale from
        // the PERSISTED order, so pending Details of Sale edits must be flushed
        // BEFORE the quote flush/fetch — otherwise the screen could show fresh
        // details while the generated PDF shows stale ones. LAID skips it:
        // edits are impossible and the flush machinery is suppressed anyway.
        const detailsSaved = await flushDetailsAutosave()
        if (!detailsSaved) {
          tab?.close()
          if (mountedRef.current) {
            setPreviewError(
              'Could not save the latest Details of Sale. Fix the details and try again.',
            )
          }
          return
        }
        const flush = await flushQuoteAutosave()
        if (flush !== 'saved') {
          tab?.close()
          if (mountedRef.current) {
            setPreviewError(
              flush === 'invalid-input'
                ? itemisedRef.current
                  ? 'Fix the quote line errors before previewing the PDF.'
                  : 'Enter a valid quote total before previewing the PDF.'
                : flush === 'changed-during-flush'
                  ? 'The quote changed while preparing the preview. Try Preview PDF again.'
                  : 'The quote could not be saved, so the preview was not opened. Fix the save error and try again.',
            )
          }
          return
        }
      }
      const { blob, fileName } = await fetchQuotePreviewPdf(orderIdRef.current)
      if (!mountedRef.current) {
        tab?.close()
        return
      }
      const url = URL.createObjectURL(blob)
      if (tab && !tab.closed) {
        // Revoked on unmount only — revoking now could blank the loading tab.
        previewUrlsRef.current.push(url)
        tab.location.href = url
      } else if (tab === null) {
        // Popup blocked — fall back to a normal download so the preview is
        // still reachable. One-shot URL: consumed by the click, revoke now.
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download = fileName ?? previewFileName(orderNumber)
        document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
        setPreviewError(
          'The preview tab was blocked by the browser, so the PDF was downloaded instead.',
        )
      } else {
        // The user closed the blank tab while the preview was being prepared —
        // treat it as a cancel: no forced download, no error banner.
        URL.revokeObjectURL(url)
      }
    } catch (err) {
      tab?.close()
      if (!mountedRef.current) return
      if (err instanceof ApiError && err.code === 'QUOTE_NOT_FOUND') {
        setPreviewError('Save the quote before previewing the PDF.')
        return
      }
      setPreviewError(
        err instanceof ApiError && err.message.length > 0
          ? err.message
          : 'Could not generate the quote preview PDF. Please try again.',
      )
    } finally {
      if (mountedRef.current) setPreviewing(false)
    }
  }

  // --- Phase 16F PR3: issued/accepted snapshot refresh (see snapshotSeqRef). ---

  // Install a new issued snapshot. A preview failure recorded against a
  // DIFFERENT issued artifact must not resurface against this one.
  function installIssued(next: QuoteIssuedSummary | null) {
    if (
      (issuedRef.current?.quote_version_id ?? null) !==
      (next?.quote_version_id ?? null)
    ) {
      setIssuedPreviewError(null)
    }
    issuedRef.current = next
    setIssued(next)
  }

  function installAccepted(next: QuoteAcceptedSummary | null) {
    if (
      (acceptedRef.current?.quote_version_id ?? null) !==
      (next?.quote_version_id ?? null)
    ) {
      setAcceptedPreviewError(null)
    }
    acceptedRef.current = next
    setAccepted(next)
  }

  // A mutation (send / cancel / conversion) is starting or has applied its own
  // result: no snapshot read issued before this point may apply afterwards.
  function raiseSnapshotFloor() {
    snapshotSeqRef.current += 1
    snapshotFloorRef.current = snapshotSeqRef.current
  }

  // A send / cancel result is the newest issued state: it always applies, and
  // older in-flight reads are discarded. (Mutations never change `accepted`.)
  function applyMutationIssued(next: QuoteIssuedSummary | null) {
    raiseSnapshotFloor()
    installIssued(next)
  }

  // Re-read the workspace and apply ONLY its server snapshots - current_issued
  // and accepted together, from this one response - when no newer read or
  // mutation has applied since this read was issued. The response's draft is
  // deliberately IGNORED: the live draft rows, mode, total input, server-draft
  // baseline, save queue and autosave status are never replaced by a refresh,
  // so a refresh can never trigger a draft PUT. A failure keeps the last
  // confirmed state; with reportError it shows the retryable notice (only when
  // nothing newer has applied meanwhile).
  async function refreshSnapshots(
    options: { reportError?: boolean } = {},
  ): Promise<SnapshotRefreshOutcome> {
    const reportError = options.reportError ?? true
    snapshotSeqRef.current += 1
    const seq = snapshotSeqRef.current
    const requestOrderId = orderIdRef.current
    snapshotReadsInFlightRef.current += 1
    setSnapshotRefreshing(true)
    try {
      const res = await fetchQuoteWorkspace(requestOrderId)
      if (!mountedRef.current || orderIdRef.current !== requestOrderId) {
        return 'gone'
      }
      if (seq <= snapshotFloorRef.current) return 'superseded'
      snapshotFloorRef.current = seq
      installIssued(res.data.current_issued)
      installAccepted(res.data.accepted)
      setSnapshotApplyCount((count) => count + 1)
      setSnapshotRefreshError(null)
      // A read issued after the conversion lock was taken reports the
      // post-conversion invoice_eligible: the server state takes over again.
      if (conversionLockRef.current !== null && seq > conversionLockSeqRef.current) {
        conversionLockRef.current = null
        setConversionLock(null)
      }
      return 'applied'
    } catch {
      if (!mountedRef.current || orderIdRef.current !== requestOrderId) {
        return 'gone'
      }
      if (reportError && seq > snapshotFloorRef.current) {
        setSnapshotRefreshError(
          'The latest quote status could not be loaded, so the last known status is shown.',
        )
      }
      return 'failed'
    } finally {
      snapshotReadsInFlightRef.current -= 1
      if (mountedRef.current && snapshotReadsInFlightRef.current === 0) {
        setSnapshotRefreshing(false)
      }
    }
  }

  // Refresh trigger 1: the top-level Quote tab became the visible tab (also on
  // mount when it mounts visible, e.g. straight after Create Quote).
  useEffect(() => {
    if (!active) return
    void refreshSnapshots()
    // refreshSnapshots reads everything through refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  // Refresh trigger 2: a quote sub-tab change.
  function handleSubTabChange(next: QuoteSubTabId) {
    if (next === subTab) return
    setSubTab(next)
    if (next === 'accepted') setAcceptedSubTabSeen(true)
    void refreshSnapshots()
  }

  // --- Phase 16E-B: Send by Email / Cancel quote / stored issued-PDF preview. ---

  // Open the Send Quote confirmation modal with a clean slate (used by the
  // draft action bar's Send Quote and the Customer Quote Resend — both go
  // through the SAME confirmation; there is no one-click send).
  // Refresh trigger 3 (Phase 16F PR3, decision D9): re-read the issued/accepted
  // snapshots FIRST and open the confirmation only after that read succeeded,
  // so the accepted-state warning reflects the current server state. A failed
  // read explains itself and offers a retry; it never opens an uninformed modal
  // and never sends anything.
  async function handleOpenSendModal() {
    if (sendCheckRef.current) return
    if (sendingRef.current || cancellingRef.current || convertingRef.current) {
      return
    }
    sendCheckRef.current = true
    setSendCheck('checking')
    setSendError(null)
    setSendEmailFixNeeded(false)
    setActionNotice(null)
    try {
      // Only a read that APPLIED informs the warning. 'superseded' means a newer
      // read OR a mutation barrier moved the floor while this read was pending, and
      // the latter carries no data, so re-issue the read (bounded) instead of
      // opening on state this check did not confirm.
      let outcome: SnapshotRefreshOutcome = 'superseded'
      for (
        let attempt = 0;
        attempt < PRE_SEND_READ_ATTEMPTS && outcome === 'superseded';
        attempt += 1
      ) {
        outcome = await refreshSnapshots({ reportError: false })
      }
      if (outcome === 'gone') return
      if (outcome !== 'applied') {
        setSendCheck('error')
        return
      }
      setSendCheck('idle')
      // Never open over another action: the tab must still be visible, no
      // mutation may be running and no other confirmation may be open.
      if (
        !activeRef.current ||
        sendingRef.current ||
        cancellingRef.current ||
        convertingRef.current ||
        cancelModalOpenRef.current ||
        convertModalOpenRef.current
      ) {
        return
      }
      setSendModalOpen(true)
    } finally {
      sendCheckRef.current = false
    }
  }

  // Map a failed send to its in-modal message. Backend messages surface
  // VERBATIM whenever present; the per-code branches only add the right
  // recovery pointer (Customer tab for the email gates; safe-retry for the
  // provider 502, where the backend KEPT the issued version/PDF/link).
  function applySendError(err: unknown) {
    const fallback = 'Could not send the quote. Please try again.'
    if (err instanceof ApiError) {
      const message = err.message.length > 0 ? err.message : fallback
      if (
        err.code === 'CUSTOMER_EMAIL_REQUIRED' ||
        err.code === 'CUSTOMER_EMAIL_INVALID'
      ) {
        // The fix lives on the Customer tab — the modal adds the pointer/button.
        setSendError(message)
        setSendEmailFixNeeded(true)
        return
      }
      if (err.code === 'EMAIL_SEND_FAILED') {
        // 502: delivery failed AFTER the issue was persisted. Nothing reached
        // the customer; retrying resends the same quote (no duplicate version).
        setSendError(
          `${message} Nothing was sent to the customer — it is safe to try again.`,
        )
        return
      }
      // Everything else verbatim: 422 QUOTE_BELOW_COST / ORDER_LOCKED, 404
      // ORDER_NOT_FOUND / QUOTE_NOT_FOUND, 400s.
      setSendError(message)
      return
    }
    setSendError(fallback)
  }

  // Send (or resend) the quote by email. Runs the SAME double-flush as Preview
  // PDF — the backend issues from the PERSISTED draft + PERSISTED order details
  // — and blocks the send when either flush fails. The ref guard makes the send
  // single-flight (no duplicate issue from a double-tap). Disabled when LAID.
  async function handleSendEmail() {
    // Cross in-flight guard: never send while a cancel or a Create Invoice is
    // running (and vice versa) - the mutations race server-side and the loser's
    // response could reinstall dead state.
    if (
      sendingRef.current ||
      cancellingRef.current ||
      convertingRef.current ||
      lockedRef.current
    )
      return
    sendingRef.current = true
    setSending(true)
    setSendError(null)
    setSendEmailFixNeeded(false)
    try {
      const detailsSaved = await flushDetailsAutosave()
      if (!detailsSaved) {
        if (mountedRef.current) {
          setSendError(
            'Could not save the latest Details of Sale. Fix the details and try again.',
          )
        }
        return
      }
      const flush = await flushQuoteAutosave()
      if (flush !== 'saved') {
        if (mountedRef.current) {
          setSendError(
            flush === 'invalid-input'
              ? itemisedRef.current
                ? 'Fix the quote line errors before sending the quote.'
                : 'Enter a valid quote total before sending the quote.'
              : flush === 'changed-during-flush'
                ? 'The quote changed while preparing the send. Try again.'
                : 'The quote could not be saved, so it was not sent. Fix the save error and try again.',
          )
        }
        return
      }
      // The send mutation starts: no snapshot read issued before this point may
      // land after it (16F PR3 recency mechanism).
      raiseSnapshotFloor()
      const res = await sendQuoteEmail(orderIdRef.current)
      if (!mountedRef.current) return
      // The 201 summary is the new issued state — the Customer Quote sub-tab
      // renders from it immediately (no workspace refetch, no financial
      // refresh: a quote send never changes order pricing). Defensive: only an
      // ISSUED summary is installed as the ACTIVE quote — if another session
      // cancelled between the send's issue and its success stamp, the re-read
      // summary can be CANCELLED, and rendering that as active would show a
      // live-looking quote whose link is dead (a reload would show null). A
      // send never changes the accepted version, so `accepted` is untouched.
      applyMutationIssued(res.data.status === 'ISSUED' ? res.data : null)
      // A preview failure recorded against the PREVIOUS issued artifact must
      // not resurface against this fresh one.
      setIssuedPreviewError(null)
      setSendModalOpen(false)
      setActionNotice(
        res.message && res.message.length > 0
          ? res.message
          : 'Quote sent by email.',
      )
    } catch (err) {
      if (mountedRef.current) applySendError(err)
      // Codex P2 round 2: a 502 EMAIL_SEND_FAILED means the backend ALREADY
      // persisted the issued version/PDF/link (locked §7.1 keep rule) — only
      // the delivery failed. The local issued state is therefore stale (still
      // null on a first send), and the Customer Quote sub-tab would misreport
      // until a reload. Resync it from the workspace; the refetched summary
      // carries no last_emailed_at, so the Not-delivered badge renders
      // naturally. The modal stays open with the error either way, and the
      // in-flight guard (reset in finally, after this await) keeps a retry
      // from racing the resync. 16F PR3: the resync is a guarded snapshot read
      // (issued + accepted together; older reads can never land after it); it
      // stays best-effort and silent on failure (the in-modal error already
      // covers the user and the last state is kept).
      if (err instanceof ApiError && err.code === 'EMAIL_SEND_FAILED') {
        raiseSnapshotFloor()
        await refreshSnapshots({ reportError: false })
      }
    } finally {
      sendingRef.current = false
      if (mountedRef.current) setSending(false)
    }
  }

  // Cancel the active issued quote (its own confirmation modal). ALLOWED when
  // LAID — cancelling only kills the public link. On success the local issued
  // state becomes NULL so the Customer Quote sub-tab returns to its empty
  // state, exactly matching a reload (the workspace only returns current_issued
  // for an active ISSUED version). Errors (422 QUOTE_NOT_ISSUED / 409
  // QUOTE_ALREADY_ACCEPTED / 404) surface verbatim inside the modal.
  async function handleCancelQuote() {
    // Cross in-flight guard (mirrors handleSendEmail): a cancel racing an
    // in-flight send (or Create Invoice) could let the later response reinstall
    // a summary the backend built AFTER the cancel committed.
    if (
      cancellingRef.current ||
      sendingRef.current ||
      convertingRef.current ||
      sendCheckRef.current
    ) {
      return
    }
    cancellingRef.current = true
    setCancelling(true)
    setCancelError(null)
    try {
      // The cancel mutation starts: older snapshot reads may not land after it.
      raiseSnapshotFloor()
      const res = await cancelQuote(orderIdRef.current)
      if (!mountedRef.current) return
      applyMutationIssued(null)
      // The stale-status rule: an old preview failure must not render against
      // whatever is issued next.
      setIssuedPreviewError(null)
      setCancelModalOpen(false)
      setActionNotice(
        res.message && res.message.length > 0
          ? res.message
          : 'Quote cancelled.',
      )
    } catch (err) {
      if (!mountedRef.current) return
      setCancelError(
        err instanceof ApiError && err.message.length > 0
          ? err.message
          : 'Could not cancel the quote. Please try again.',
      )
      // Drift resync: 422 QUOTE_NOT_ISSUED means the backend has NO active
      // issued quote and no accepted one (cancelled/superseded elsewhere) - the
      // local summary is stale. Null it at once, then (16F PR3) follow with a
      // guarded snapshot read so the coherent issued + accepted state shows.
      // 409 QUOTE_ALREADY_ACCEPTED (no issued version, an accepted one exists)
      // gets the same guarded read: the accepted state is representable now.
      // The issued summary is NOT nulled first: with no accepted summary to show
      // yet, that would fabricate the "No quote has been sent yet." empty state.
      // A failure of that read therefore shows the retryable notice over the
      // last known state. Other errors leave the state untouched. The error
      // stays in the modal.
      if (err instanceof ApiError && err.code === 'QUOTE_NOT_ISSUED') {
        applyMutationIssued(null)
        void refreshSnapshots({ reportError: false })
      } else if (
        err instanceof ApiError &&
        err.code === 'QUOTE_ALREADY_ACCEPTED'
      ) {
        void refreshSnapshots()
      }
    } finally {
      cancellingRef.current = false
      if (mountedRef.current) setCancelling(false)
    }
  }

  // Preview the STORED issued PDF in a new tab — the exact frozen artifact that
  // was emailed, so there is NO flush and NO regeneration (unlike the draft
  // preview). Same popup-safe order as the draft preview: open a blank tab
  // synchronously in the click handler, fetch the credentialed blob, then point
  // the tab at the object URL; fall back to a download only when the popup was
  // blocked. Allowed when LAID (read-only).
  async function handleIssuedPreviewPdf() {
    if (issuedPreviewing) return
    setIssuedPreviewError(null)
    const tab = window.open('', '_blank')
    setIssuedPreviewing(true)
    try {
      const { blob, fileName } = await fetchQuoteStoredPdf(
        orderIdRef.current,
        'issued',
      )
      if (!mountedRef.current) {
        tab?.close()
        return
      }
      const url = URL.createObjectURL(blob)
      if (tab && !tab.closed) {
        // Revoked on unmount only — revoking now could blank the loading tab.
        previewUrlsRef.current.push(url)
        tab.location.href = url
      } else if (tab === null) {
        // Popup blocked — fall back to a normal download so the stored PDF is
        // still reachable. One-shot URL: consumed by the click, revoke now.
        const issuedVersion = issued?.version_number
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download =
          fileName ??
          `quote-${orderNumber && orderNumber.length > 0 ? orderNumber : 'order'}${
            issuedVersion !== undefined ? `-v${issuedVersion}` : ''
          }.pdf`
        document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
        setIssuedPreviewError(
          'The preview tab was blocked by the browser, so the PDF was downloaded instead.',
        )
      } else {
        // The user closed the blank tab while the PDF was being fetched —
        // treat it as a cancel: no forced download, no error banner.
        URL.revokeObjectURL(url)
      }
    } catch (err) {
      tab?.close()
      if (!mountedRef.current) return
      setIssuedPreviewError(
        err instanceof ApiError && err.message.length > 0
          ? err.message
          : 'Could not open the issued quote PDF. Please try again.',
      )
    } finally {
      if (mountedRef.current) setIssuedPreviewing(false)
    }
  }

  // --- Phase 16F PR3: Accepted Quote (signature, signed PDF, Create Invoice). ---

  // The stored accepted signature (credentialed blob of the VERBATIM backend
  // path -> object URL). Keyed on the accepted VERSION as well as the path: the
  // path is the same stable /quote/accepted/signature URL for every accepted
  // version, so a newer accepted version must refetch even though the path did
  // not change. The old image is cleared at once on any identity change; a late
  // result after a change or unmount is discarded and its URL revoked (the
  // InvoiceTab ownership pattern). A failure shows a small retry notice only.
  // Loaded once the Accepted Quote sub-tab has been opened.
  const acceptedSignaturePath =
    acceptedSubTabSeen && accepted !== null
      ? accepted.signature_download_path
      : null
  const acceptedSignatureVersionId =
    accepted !== null ? accepted.quote_version_id : null
  useEffect(() => {
    if (acceptedSignaturePath === null) {
      setAcceptedSignatureUrl(null)
      setAcceptedSignatureFailed(false)
      return
    }
    let cancelled = false
    let objectUrl: string | null = null // closure-tracked - authoritative for revoke
    setAcceptedSignatureUrl(null)
    setAcceptedSignatureFailed(false)
    fetchAcceptedQuoteSignature(acceptedSignaturePath)
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob)
        if (cancelled) {
          URL.revokeObjectURL(objectUrl)
          objectUrl = null
          return
        }
        setAcceptedSignatureUrl(objectUrl)
      })
      .catch(() => {
        if (!cancelled) setAcceptedSignatureFailed(true)
      })
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [acceptedSignaturePath, acceptedSignatureVersionId, acceptedSignatureReload])

  // The current invoice behind a DISABLED Create Invoice (invoice_eligible
  // false), to choose its explanation. Re-read after every applied snapshot
  // refresh while the Accepted Quote sub-tab is in use; a result is used only for
  // the accepted version it was read for, and a late result after a change or
  // unmount is discarded.
  const eligibilityLookupKey =
    acceptedSubTabSeen && accepted !== null && !accepted.invoice_eligible
      ? `${accepted.quote_version_id}:${snapshotApplyCount}`
      : null
  const eligibilityLookupVersionId =
    accepted !== null ? accepted.quote_version_id : null
  useEffect(() => {
    if (eligibilityLookupKey === null || eligibilityLookupVersionId === null) {
      setEligibilityLookup(null)
      return
    }
    let cancelled = false
    const quoteVersionId = eligibilityLookupVersionId
    fetchCurrentInvoice(orderIdRef.current)
      .then((res) => {
        if (!cancelled) {
          setEligibilityLookup({
            status: 'ready',
            quoteVersionId,
            invoice: res.data.invoice,
          })
        }
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
          setEligibilityLookup({ status: 'ready', quoteVersionId, invoice: null })
          return
        }
        setEligibilityLookup({ status: 'error', quoteVersionId })
      })
    return () => {
      cancelled = true
    }
    // eligibilityLookupVersionId is part of eligibilityLookupKey.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eligibilityLookupKey])

  // Preview the STORED signed PDF (GET quote/pdf?type=accepted) in a new tab -
  // never the draft preview, never a regeneration, never an autosave flush. Same
  // popup-safe order as the other previews: blank tab opened synchronously in
  // the click handler, then the blob; download fallback only when blocked. No
  // separate download button. Allowed when LAID (read).
  async function handleAcceptedPreviewPdf() {
    if (acceptedPreviewing) return
    setAcceptedPreviewError(null)
    const tab = window.open('', '_blank')
    setAcceptedPreviewing(true)
    const versionNumber = acceptedRef.current?.version_number
    try {
      const { blob, fileName } = await fetchQuoteStoredPdf(
        orderIdRef.current,
        'accepted',
      )
      if (!mountedRef.current) {
        tab?.close()
        return
      }
      const url = URL.createObjectURL(blob)
      if (tab && !tab.closed) {
        // Revoked on unmount only - revoking now could blank the loading tab.
        previewUrlsRef.current.push(url)
        tab.location.href = url
      } else if (tab === null) {
        // Popup blocked - fall back to a normal download. One-shot URL.
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download =
          fileName ??
          `quote-${orderNumber && orderNumber.length > 0 ? orderNumber : 'order'}${
            versionNumber !== undefined ? `-v${versionNumber}` : ''
          }-signed.pdf`
        document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
        setAcceptedPreviewError(
          'The preview tab was blocked by the browser, so the PDF was downloaded instead.',
        )
      } else {
        // The user closed the blank tab while the PDF was being fetched.
        URL.revokeObjectURL(url)
      }
    } catch (err) {
      tab?.close()
      if (!mountedRef.current) return
      setAcceptedPreviewError(
        err instanceof ApiError && err.message.length > 0
          ? err.message
          : 'Could not open the signed quote PDF. Please try again.',
      )
    } finally {
      if (mountedRef.current) setAcceptedPreviewing(false)
    }
  }

  function closeConvertModal() {
    convertTargetRef.current = null
    setConvertTarget(null)
    setConvertLookup({ status: 'idle' })
    setConvertModalOpen(false)
  }

  // The current-invoice read for an open confirmation, bound to its key (order +
  // accepted version + attempt). Its only job is accurate confirmation wording:
  // it never replaces invoice_eligible or the server's own checks.
  async function lookupInvoiceForConvert(target: ConvertTarget) {
    setConvertLookup({ status: 'loading', key: target.key })
    try {
      const res = await fetchCurrentInvoice(target.orderId)
      if (!mountedRef.current || convertTargetRef.current?.key !== target.key) {
        return
      }
      setConvertLookup({
        status: 'ready',
        key: target.key,
        invoice: res.data.invoice,
      })
    } catch (err) {
      if (!mountedRef.current || convertTargetRef.current?.key !== target.key) {
        return
      }
      if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
        setConvertLookup({ status: 'ready', key: target.key, invoice: null })
        return
      }
      setConvertLookup({ status: 'error', key: target.key })
    }
  }

  function openConvertTarget(current: QuoteAcceptedSummary) {
    convertKeySeqRef.current += 1
    const target: ConvertTarget = {
      key: convertKeySeqRef.current,
      orderId: orderIdRef.current,
      quoteVersionId: current.quote_version_id,
      versionNumber: current.version_number,
      totalIncGst: current.quote_total_inc_gst,
    }
    convertTargetRef.current = target
    setConvertTarget(target)
    void lookupInvoiceForConvert(target)
  }

  // Create Invoice: open the confirmation (never a one-click POST).
  function handleOpenConvertModal() {
    const current = acceptedRef.current
    if (current === null || !current.invoice_eligible) return
    if (
      convertingRef.current ||
      sendingRef.current ||
      cancellingRef.current ||
      sendCheckRef.current ||
      conversionLockRef.current !== null
    ) {
      return
    }
    setConvertError(null)
    setConvertErrorDetails([])
    setConvertNotice(null)
    setActionNotice(null)
    openConvertTarget(current)
    setConvertModalOpen(true)
  }

  function handleRetryConvertLookup() {
    const target = convertTargetRef.current
    const current = acceptedRef.current
    if (target === null || current === null) return
    if (current.quote_version_id !== target.quoteVersionId) return
    openConvertTarget(current)
  }

  // A snapshot refresh changed the accepted quote (or made it ineligible) while
  // its confirmation was open: invalidate the confirmation and require a review
  // of the new accepted state. Never while the POST is in flight (its outcome
  // must not be lost).
  useEffect(() => {
    if (!convertModalOpen || converting) return
    const target = convertTargetRef.current
    if (target === null) return
    if (
      accepted === null ||
      accepted.quote_version_id !== target.quoteVersionId ||
      !accepted.invoice_eligible
    ) {
      closeConvertModal()
      setConvertNotice(ACCEPTED_CHANGED_MESSAGE)
    }
    // closeConvertModal only uses refs and state setters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accepted, convertModalOpen, converting])

  // Keep Create Invoice disabled until a snapshot read issued from NOW applies.
  function takeConversionLock(reason: 'created' | 'unknown') {
    conversionLockSeqRef.current = snapshotSeqRef.current
    conversionLockRef.current = reason
    setConversionLock(reason)
  }

  function applyConvertError(err: unknown) {
    const fallback =
      'Could not create the invoice from the accepted quote. Please try again.'
    if (isAmbiguousFailure(err)) {
      // No readable answer: the invoice MAY have been created. Never invite a
      // blind retry - lock the button and re-read the real eligibility.
      setConvertError(CONVERSION_UNKNOWN_MESSAGE)
      takeConversionLock('unknown')
      void refreshSnapshots()
      return
    }
    if (err instanceof ApiError) {
      // Backend messages VERBATIM (409 signature precedence, 422 preconditions /
      // overpayment / not accepted, 404 ...).
      setConvertError(err.message.length > 0 ? err.message : fallback)
      if (err.code === 'INVOICE_PRECONDITIONS_NOT_MET') {
        setConvertErrorDetails(parsePreconditionFailures(err.details))
      }
      // Drift (the signature precedence or the accepted version changed since
      // this tab last read them): refresh eligibility, keeping the error shown.
      if (err.status === 409 || err.code === 'QUOTE_NOT_ACCEPTED') {
        void refreshSnapshots()
      }
      return
    }
    setConvertError(fallback)
  }

  // Confirmed Create Invoice (Path A). POSTs exactly {} - the server converts the
  // LATEST accepted quote (no version selector, price, terms or signature
  // reference is ever sent). Single-flight; never overlaps a send or a cancel.
  async function handleConfirmCreateInvoice() {
    if (convertingRef.current || sendingRef.current || cancellingRef.current) {
      return
    }
    const target = convertTargetRef.current
    if (target === null) return
    const current = acceptedRef.current
    if (
      current === null ||
      current.quote_version_id !== target.quoteVersionId ||
      !current.invoice_eligible ||
      target.orderId !== orderIdRef.current
    ) {
      closeConvertModal()
      setConvertNotice(ACCEPTED_CHANGED_MESSAGE)
      return
    }
    if (convertLookup.status !== 'ready' || convertLookup.key !== target.key) {
      return
    }
    convertingRef.current = true
    setConverting(true)
    setConvertError(null)
    setConvertErrorDetails([])
    setConvertNotice(null)
    const requestOrderId = target.orderId
    try {
      // Codex P1: the backend reads the PERSISTED proposed lay date and lay date
      // status (due date and preconditions), so pending Details of Sale edits are
      // flushed first, as Send and Preview PDF do; a failed save blocks the
      // conversion. No quote draft flush: Path A never reads the draft.
      const detailsSaved = await flushDetailsAutosave()
      if (!mountedRef.current || orderIdRef.current !== requestOrderId) return
      if (!detailsSaved) {
        closeConvertModal()
        setConvertError(
          'Could not save the latest Details of Sale. Fix the details and try again.',
        )
        return
      }
      // A snapshot refresh can land during the flush, and the effect that closes
      // a stale confirmation is paused while converting: re-check the target.
      const latest = acceptedRef.current
      if (
        latest === null ||
        latest.quote_version_id !== target.quoteVersionId ||
        !latest.invoice_eligible ||
        target.orderId !== orderIdRef.current ||
        convertTargetRef.current?.key !== target.key
      ) {
        closeConvertModal()
        setConvertNotice(ACCEPTED_CHANGED_MESSAGE)
        return
      }
      // The conversion starts: older snapshot reads may not land after it.
      raiseSnapshotFloor()
      let message = 'Invoice created from accepted quote.'
      try {
        const res = await createInvoiceFromQuote(requestOrderId)
        if (res && typeof res.message === 'string' && res.message.length > 0) {
          message = res.message
        }
      } catch (err) {
        // A 2xx whose body could not be read (the shared client reports it as an
        // ApiError carrying the 2xx status) still CREATED the invoice: take the
        // success path with the default message rather than inviting a retry.
        if (!(err instanceof ApiError && err.status >= 200 && err.status < 300)) {
          throw err
        }
      }
      if (!mountedRef.current || orderIdRef.current !== requestOrderId) return
      takeConversionLock('created')
      closeConvertModal()
      setActionNotice(message)
      // Re-read the issued/accepted snapshots (the new eligibility) in the
      // background: a failed read never undoes or relabels the creation - it
      // shows the retryable refresh notice and the lock keeps a duplicate
      // conversion from being offered meanwhile.
      void refreshSnapshots()
      // Land on the Invoice tab, which reads the new current invoice itself.
      onInvoiceReady(message)
    } catch (err) {
      if (!mountedRef.current || orderIdRef.current !== requestOrderId) return
      // Errors live OUTSIDE the modal so a drift refresh cannot erase them.
      closeConvertModal()
      applyConvertError(err)
    } finally {
      convertingRef.current = false
      if (mountedRef.current) setConverting(false)
    }
  }

  const customerName = composeFullName(customer)
  const billingAddressLines = composeAddressLines(billingAddress)
  const detailsOfSaleText = saleDetails?.details_of_sale || ''

  // Per-flooring-type LIVE quote terms (SOFT -> terms_soft, HARD -> terms_hard, no
  // legacy fallback; blank hides the section). The invoice no longer selects terms
  // client-side: InvoiceTab shows the server-selected InvoiceDetail.terms_html.
  const termsText = nonBlank(
    flooringType === 'SOFT'
      ? tenantConfig?.terms_soft
      : tenantConfig?.terms_hard,
  )
  // IDENTICAL sanitizer config to InvoiceTab (Codex P2) — restricted allowlist,
  // ALL attributes stripped, textless fragments hide the whole section. Do not
  // weaken. dangerouslySetInnerHTML below only ever receives this value.
  const sanitizedTermsHtml = useMemo(() => {
    if (!termsText) return null
    const sanitized = nonBlank(
      DOMPurify.sanitize(termsText, {
        ALLOWED_TAGS: [
          'p',
          'br',
          'strong',
          'b',
          'em',
          'i',
          'u',
          'ol',
          'ul',
          'li',
          'table',
          'thead',
          'tbody',
          'tr',
          'th',
          'td',
          'div',
          'span',
          'small',
        ],
        ALLOWED_ATTR: [],
        ALLOW_DATA_ATTR: false,
        ALLOW_ARIA_ATTR: false,
      }),
    )
    if (!sanitized) return null
    const doc = new DOMParser().parseFromString(sanitized, 'text/html')
    if (!doc.body.textContent?.trim()) return null
    return sanitized
  }, [termsText])

  // --- Derived draft/dirty/validation state (mode-aware). ---
  const draftEditable = !locked
  const currentBody = buildDraftBody(itemised, totalInput, rows)
  const currentBodyJson = currentBody ? JSON.stringify(currentBody) : null
  const persistedDraftExists = serverDraft !== null
  const totalTrimmed = totalInput.trim()
  // Non-itemised invalidity: non-blank input that cannot form a valid body, OR
  // a cleared input when a draft is already persisted (no way to "unsave").
  const totalInvalid =
    !itemised &&
    !locked &&
    ((totalTrimmed !== '' && currentBody === null) ||
      (totalTrimmed === '' && persistedDraftExists))
  // Itemised invalidity: any broken row blocks the save; an all-valid row set
  // whose totals overflow DECIMAL(10,2) also blocks (body === null despite no
  // broken row).
  const rowsInvalid =
    itemised &&
    !locked &&
    rows.some((row) => parseEditorRow(row, 0) === null)
  const itemisedOverflow =
    itemised && !locked && !rowsInvalid && currentBody === null
  const draftInvalid = itemised ? rowsInvalid || itemisedOverflow : totalInvalid
  // Dirty = the current (valid or invalid) draft differs from the baseline.
  const dirty =
    !locked &&
    (currentBody === null
      ? draftInvalid
      : currentBodyJson !== lastSavedBodyRef.current)

  // Live itemised totals from the visible rows (editable itemised mode).
  const itemisedTotals = computeItemisedTotals(rows)

  // Intended-total helper derivations. Helper messages are suppressed while any
  // row is invalid — the visible sum would be misleading mid-fix.
  //
  // Mismatch is detected at the EX level (target ex = round2(intended/1.1) vs
  // the visible ex sum), NOT by comparing inc totals: itemised inc totals only
  // exist in round2(ex × 1.1) steps, so some typed inc values (e.g. 4900.00)
  // are unreachable by ANY adjustment. Comparing at the inc level would show a
  // "$0.01 above" banner whose fix button appends a $0.00 row — a visible
  // no-op. When the target ex already equals the sum, the intended total is
  // achieved as closely as 2dp money allows and no message shows. When they
  // differ, the appended adjustment is guaranteed non-zero, and the displayed
  // $X difference (inc level) is guaranteed non-zero too (the ex↔inc round
  // trip is stable, so ex-inequality implies inc-inequality).
  const intendedTrimmed = intendedInput.trim()
  const intendedParsed = parseMoneyInput(intendedInput)
  const intendedValue = intendedParsed === null ? null : round2(intendedParsed)
  const intendedInvalid =
    itemised &&
    draftEditable &&
    intendedTrimmed !== '' &&
    (intendedValue === null ||
      intendedValue < 0 ||
      intendedValue > QUOTE_TOTAL_MAX)
  const intendedTargetEx =
    intendedValue === null ? null : round2(intendedValue / 1.1)
  const intendedMismatch: 'lower' | 'higher' | null =
    itemised &&
    draftEditable &&
    !rowsInvalid &&
    intendedTrimmed !== '' &&
    !intendedInvalid &&
    intendedTargetEx !== null &&
    intendedTargetEx !== itemisedTotals.ex
      ? intendedTargetEx < itemisedTotals.ex
        ? 'lower'
        : 'higher'
      : null
  const intendedDiff =
    intendedMismatch !== null && intendedValue !== null
      ? round2(Math.abs(itemisedTotals.inc - intendedValue))
      : 0

  // Preview needs a PERSISTED draft (the backend 404s QUOTE_NOT_FOUND without
  // one). Editable mode may rely on the pre-preview flush persisting the current
  // valid draft; locked mode needs the draft already persisted.
  const previewEnabled = locked
    ? persistedDraftExists
    : persistedDraftExists || currentBody !== null

  // Locked itemised drafts render the persisted lines as a read-only table.
  const lockedItemised = locked && serverDraft !== null && serverDraft.itemised

  // --- Phase 16F PR3: Accepted Quote derivations. ---
  // Create Invoice: the server's invoice_eligible alone, plus the in-flight
  // gates and the post-conversion lock. Deliberately NOT gated by `locked`
  // (LAID), the draft, a newer issued quote or any client-side date comparison.
  const createInvoiceDisabled =
    accepted === null ||
    !accepted.invoice_eligible ||
    conversionLock !== null ||
    converting ||
    sending ||
    cancelling ||
    sendCheck === 'checking'
  // The note under Create Invoice. A just-created invoice says so (the lock
  // holds until a fresh read confirms the new eligibility); an unknown outcome
  // defers to the error above; an ineligible quote explains why, from the
  // current invoice read for THIS accepted version (string comparison of the
  // two backend timestamps, never Date arithmetic; a failed read gives the
  // general sentence).
  let createInvoiceNote: string | null = null
  if (accepted !== null) {
    if (conversionLock === 'created') {
      createInvoiceNote = ALREADY_CONVERTED_MESSAGE
    } else if (conversionLock === 'unknown') {
      createInvoiceNote = snapshotRefreshing
        ? 'Checking the latest quote status…'
        : 'Load the latest quote status (Try again above) before creating an invoice.'
    } else if (!accepted.invoice_eligible) {
      const lookup =
        eligibilityLookup !== null &&
        eligibilityLookup.quoteVersionId === accepted.quote_version_id
          ? eligibilityLookup
          : null
      if (lookup === null) {
        createInvoiceNote = 'Checking the current invoice…'
      } else if (
        lookup.status === 'ready' &&
        lookup.invoice !== null &&
        lookup.invoice.accepted_at !== null &&
        sameSecondTimestamp(lookup.invoice.accepted_at, accepted.accepted_at)
      ) {
        createInvoiceNote = ALREADY_CONVERTED_MESSAGE
      } else {
        createInvoiceNote = NEWER_SIGNATURE_REQUIRED_MESSAGE
      }
    }
  }
  // The open confirmation's current-invoice read, only for its own key.
  const convertInvoiceLookup =
    convertTarget !== null &&
    convertLookup.status !== 'idle' &&
    convertLookup.key === convertTarget.key
      ? convertLookup
      : null

  return (
    <div>
      <div className="mb-4 flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
            Quote
          </h2>
          <p className="text-sm text-slate-500 mt-1">
            {draftEditable
              ? 'Changes are saved automatically.'
              : 'View the quote for this order.'}
          </p>
        </div>
        {/* Autosave status — the four locked states: Unsaved changes / Saving… /
            Saved / Could not save. Precedence: saving > error > unsaved > saved. */}
        {draftEditable && (
          <div className="shrink-0 pt-0.5 text-xs">
            {saving ? (
              <span className="text-slate-500">Saving…</span>
            ) : saveError ? (
              <span className="text-red-600">Could not save</span>
            ) : dirty ? (
              <span className="text-slate-500">Unsaved changes</span>
            ) : saved ? (
              <span className="inline-flex items-center gap-1 text-teal-700">
                <CheckCircleIcon className="w-3.5 h-3.5" />
                Saved
              </span>
            ) : null}
          </div>
        )}
      </div>

      {locked && (
        <div className="mb-5 rounded-lg border border-slate-200 bg-slate-50 px-4 py-2.5 text-xs text-slate-600">
          This order is laid and locked. The quote can be viewed but not edited.
        </div>
      )}

      <div className="mb-5 -mx-3">
        <Tabs tabs={SUB_TABS} active={subTab} onChange={handleSubTabChange} />
      </div>

      {/* 16E-B success notice ("Quote sent by email." / "Quote cancelled.") —
          above the sub-tab content so it is visible from any sub-tab. */}
      {actionNotice && (
        <div className="mb-4 rounded-lg border border-teal-200 bg-teal-50 px-4 py-3">
          <p className="text-sm font-medium text-teal-700">{actionNotice}</p>
        </div>
      )}

      {/* 16F PR3: a failed issued/accepted refresh keeps the last confirmed
          state on screen and offers a retry (visible from any sub-tab). */}
      {snapshotRefreshError && (
        <div className="mb-4 flex flex-col gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-sm font-medium text-amber-800">
            {snapshotRefreshError}
          </p>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            disabled={snapshotRefreshing}
            onClick={() => void refreshSnapshots()}
          >
            {snapshotRefreshing ? 'Checking…' : 'Try again'}
          </Button>
        </div>
      )}

      {/* 16F PR3 (D9): the pre-Send status read failed, so the confirmation
          was not opened without the current accepted state. */}
      {sendCheck === 'error' && (
        <div className="mb-4 flex flex-col gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-sm font-medium text-amber-800">
            The latest quote status could not be checked, so the send
            confirmation was not opened. Check your connection and try again.
          </p>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            disabled={locked}
            onClick={() => void handleOpenSendModal()}
          >
            Try again
          </Button>
        </div>
      )}

      {subTab === 'draft' && (
        <div>
          {/* Protected-surface signals (never inside the customer-facing canvas):
              server-derived quote GP% and the below-cost block. No raw cost. */}
          {serverDraft && (
            <div className="mb-4 flex flex-col gap-1">
              {serverDraft.gp_percent !== null && (
                <span className="text-xs text-slate-500 tabular-nums">
                  Quote GP: {formatPercent(serverDraft.gp_percent)}
                </span>
              )}
              {serverDraft.below_cost && (
                <span className="text-xs font-medium text-red-600">
                  This quote is below cost and cannot be saved or sent until the
                  total covers cost.
                </span>
              )}
            </div>
          )}

          {/* Itemised mode toggle — a plain switch, no warnings or confirmation
              modals (locked UX rule). Hidden entirely when the order is laid
              (no toggle changes on locked orders). Disabled while the
              Products & Charges seed is loading so the flip can't race it. */}
          {draftEditable && (
            <div className="mb-4 flex flex-wrap items-center gap-3">
              <button
                type="button"
                role="switch"
                aria-checked={itemised}
                aria-label="Itemised quote"
                onClick={handleToggleItemised}
                disabled={seedState === 'loading'}
                className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50 ${
                  itemised ? 'bg-teal-600' : 'bg-slate-300'
                }`}
              >
                <span
                  className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${
                    itemised ? 'translate-x-6' : 'translate-x-1'
                  }`}
                />
              </button>
              <span className="text-sm font-medium text-slate-700">
                Itemised quote
              </span>
              {seedState === 'loading' && (
                <span className="text-xs text-slate-500">
                  Loading products &amp; charges…
                </span>
              )}
            </div>
          )}

          {/* Products & Charges seed failure — inline error + Retry. The mode
              was NOT switched: retrying re-runs the same toggle-ON decision. */}
          {seedState === 'error' && (
            <div className="mb-4 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
              <p className="text-sm font-medium text-rose-700">
                Could not load the order&apos;s products and charges to start
                the itemised quote.
              </p>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={handleToggleItemised}
              >
                Retry
              </Button>
            </div>
          )}

          {saveError && (
            <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
              <p className="text-sm font-medium text-rose-700">{saveError}</p>
              {saveErrorDetails.length > 0 && (
                <ul className="mt-1 space-y-0.5">
                  {saveErrorDetails.map((line, idx) => (
                    <li
                      key={`${line}-${idx}`}
                      className="text-xs text-rose-700 leading-relaxed"
                    >
                      {line}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          {previewError && (
            <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
              <p className="text-sm font-medium text-rose-700">
                {previewError}
              </p>
            </div>
          )}

          {/* The QUOTATION document canvas — mirrors the InvoiceTab document
              layout/styling, reworded for a quotation. Customer-facing: no GP,
              no cost, no acceptance/signature/payment elements. */}
          <article className="rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-6 sm:px-8 sm:py-8 lg:px-10 lg:py-8">
            <header className="grid grid-cols-1 sm:grid-cols-3 gap-6 items-start">
              <div className="sm:col-span-1">
                {/* Tenant logo as a browser <img>, failing soft to the
                    business-name text (same chain as InvoiceTab). */}
                {logoPath && !logoFailed ? (
                  <img
                    src={logoPath}
                    alt={businessName ?? 'Business logo'}
                    className="h-auto w-auto max-h-16 max-w-[240px] object-contain"
                    onError={() => setLogoFailed(true)}
                  />
                ) : (
                  businessName && (
                    <div className="text-base font-bold text-slate-900 tracking-tight">
                      {businessName}
                    </div>
                  )
                )}
              </div>
              <div className="hidden sm:block sm:col-span-1" />
              <div className="sm:col-span-1 sm:text-right">
                <div className="text-3xl font-bold text-slate-900 tracking-tight">
                  QUOTATION
                </div>
                {/* Header right meta is the order number ONLY — matching the
                    invoice screen's Phase 16A minimalism (no store/salesperson). */}
                {orderNumber && (
                  <div className="mt-1 text-sm font-mono text-slate-700">
                    {orderNumber}
                  </div>
                )}
              </div>
            </header>

            <div className="my-6 border-t border-slate-200" />

            <div>
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Quotation To
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

            <div className="mt-8">
              <div className="text-base font-semibold text-slate-900">
                Details Of Sale
              </div>
              <p className="mt-2 text-sm text-slate-700 leading-relaxed whitespace-pre-wrap">
                {detailsOfSaleText || '—'}
              </p>
            </div>

            {/* Locked itemised drafts: the persisted customer-facing lines,
                read-only. Non-itemised mode NEVER renders a line breakdown —
                retained dormant lines and any legacy synthetic "Quoted works"
                row stay invisible. */}
            {lockedItemised && serverDraft && (
              <div className="mt-8 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-slate-200 text-[11px] uppercase tracking-wider text-slate-500">
                      <th className="py-2 pr-3 text-left font-semibold">
                        Description
                      </th>
                      <th className="py-2 px-3 text-right font-semibold">
                        Qty
                      </th>
                      <th className="py-2 px-3 text-right font-semibold">
                        Unit price
                      </th>
                      <th className="py-2 pl-3 text-right font-semibold">
                        Amount
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {serverDraft.lines.map((line, idx) => (
                      <tr
                        key={line.quote_draft_line_id ?? `line-${idx}`}
                        className="border-b border-slate-100"
                      >
                        <td className="py-2 pr-3 text-slate-800">
                          {line.description}
                        </td>
                        <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                          {line.quantity !== undefined
                            ? toFixed2(line.quantity)
                            : '—'}
                        </td>
                        <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                          {line.unit_price_ex_gst !== undefined
                            ? formatMoney(line.unit_price_ex_gst)
                            : '—'}
                        </td>
                        <td className="py-2 pl-3 text-right tabular-nums text-slate-900">
                          {formatMoney(line.line_total_ex_gst)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* EDITABLE itemised line table (PR2B) — the customer-facing quote
                rows, fully independent of Products & Charges after seeding.
                Column order locked: Description / Quantity / Unit price /
                Amount. ITEM amounts are computed (qty × unit, HALF_UP 2dp);
                ADJUSTMENT amounts are edited directly (signed). No cost is
                rendered anywhere. */}
            {draftEditable && itemised && (
              <div className="mt-8">
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[640px] text-sm">
                    <thead>
                      <tr className="border-b border-slate-200 text-[11px] uppercase tracking-wider text-slate-500">
                        <th className="py-2 pr-3 text-left font-semibold">
                          Description
                        </th>
                        <th className="py-2 px-3 text-right font-semibold w-28">
                          Quantity
                        </th>
                        <th className="py-2 px-3 text-right font-semibold w-32">
                          Unit price
                        </th>
                        <th className="py-2 px-3 text-right font-semibold w-36">
                          Amount
                        </th>
                        <th className="py-2 pl-3 w-28">
                          <span className="sr-only">Row actions</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.length === 0 && (
                        <tr>
                          <td
                            colSpan={5}
                            className="py-6 text-center text-sm text-slate-400"
                          >
                            No quote lines yet. Add an item or adjustment
                            below.
                          </td>
                        </tr>
                      )}
                      {rows.map((row, idx) => {
                        const flags = rowFieldFlags(row)
                        const parsed = parseEditorRow(row, 0)
                        return (
                          <tr
                            key={row.key}
                            className="border-b border-slate-100 align-top"
                          >
                            <td className="py-2 pr-3">
                              <Input
                                type="text"
                                value={row.description}
                                onChange={(e) =>
                                  handleRowFieldChange(
                                    row.key,
                                    'description',
                                    e.target.value,
                                  )
                                }
                                onBlur={flushNow}
                                invalid={flags.description}
                                maxLength={DESCRIPTION_MAX}
                                placeholder="Description"
                                aria-label={`Line ${idx + 1} description`}
                                className="h-9 text-sm"
                              />
                              {row.line_type === 'ADJUSTMENT' && (
                                <span className="mt-1 block text-[10px] uppercase tracking-wider text-slate-400">
                                  Adjustment
                                </span>
                              )}
                            </td>
                            <td className="py-2 px-3">
                              {row.line_type === 'ITEM' ? (
                                <Input
                                  type="text"
                                  inputMode="decimal"
                                  value={row.quantityInput}
                                  onChange={(e) =>
                                    handleRowFieldChange(
                                      row.key,
                                      'quantityInput',
                                      e.target.value,
                                    )
                                  }
                                  onBlur={flushNow}
                                  invalid={flags.quantity}
                                  placeholder="0.00"
                                  aria-label={`Line ${idx + 1} quantity`}
                                  className="h-9 text-sm text-right tabular-nums"
                                />
                              ) : (
                                <div className="py-1.5 text-right text-sm text-slate-400">
                                  —
                                </div>
                              )}
                            </td>
                            <td className="py-2 px-3">
                              {row.line_type === 'ITEM' ? (
                                <Input
                                  type="text"
                                  inputMode="decimal"
                                  value={row.unitPriceInput}
                                  onChange={(e) =>
                                    handleRowFieldChange(
                                      row.key,
                                      'unitPriceInput',
                                      e.target.value,
                                    )
                                  }
                                  onBlur={flushNow}
                                  invalid={flags.unitPrice}
                                  placeholder="0.00"
                                  aria-label={`Line ${idx + 1} unit price`}
                                  className="h-9 text-sm text-right tabular-nums"
                                />
                              ) : (
                                <div className="py-1.5 text-right text-sm text-slate-400">
                                  —
                                </div>
                              )}
                            </td>
                            <td className="py-2 px-3">
                              {row.line_type === 'ITEM' ? (
                                <div className="py-1.5 text-right text-sm tabular-nums text-slate-900">
                                  {parsed !== null
                                    ? formatMoney(parsed.amount)
                                    : '—'}
                                </div>
                              ) : (
                                <Input
                                  type="text"
                                  inputMode="decimal"
                                  value={row.amountInput}
                                  onChange={(e) =>
                                    handleRowFieldChange(
                                      row.key,
                                      'amountInput',
                                      e.target.value,
                                    )
                                  }
                                  onBlur={flushNow}
                                  invalid={flags.amount}
                                  placeholder="0.00"
                                  aria-label={`Line ${idx + 1} amount`}
                                  className="h-9 text-sm text-right tabular-nums"
                                />
                              )}
                            </td>
                            <td className="py-2 pl-3">
                              <div className="flex items-center justify-end gap-1">
                                <button
                                  type="button"
                                  onClick={() => handleMoveRow(idx, -1)}
                                  disabled={idx === 0}
                                  aria-label={`Move line ${idx + 1} up`}
                                  className="flex h-8 w-8 items-center justify-center rounded-md text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-30"
                                >
                                  <ChevronDownIcon className="h-4 w-4 rotate-180" />
                                </button>
                                <button
                                  type="button"
                                  onClick={() => handleMoveRow(idx, 1)}
                                  disabled={idx === rows.length - 1}
                                  aria-label={`Move line ${idx + 1} down`}
                                  className="flex h-8 w-8 items-center justify-center rounded-md text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-30"
                                >
                                  <ChevronDownIcon className="h-4 w-4" />
                                </button>
                                <button
                                  type="button"
                                  onClick={() => handleRemoveRow(row.key)}
                                  aria-label={`Remove line ${idx + 1}`}
                                  className="flex h-8 w-8 items-center justify-center rounded-md text-slate-500 transition-colors hover:bg-rose-50 hover:text-rose-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500/40"
                                >
                                  <TrashIcon className="h-4 w-4" />
                                </button>
                              </div>
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>

                <div className="mt-3 flex flex-wrap gap-2">
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    onClick={() => handleAddRow('ITEM')}
                  >
                    <PlusIcon className="h-3.5 w-3.5" />
                    Add item
                  </Button>
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    onClick={() => handleAddRow('ADJUSTMENT')}
                  >
                    <PlusIcon className="h-3.5 w-3.5" />
                    Add adjustment
                  </Button>
                </div>

                {rowsInvalid && (
                  <p className="mt-2 text-xs text-red-600" role="alert">
                    Fix the highlighted line fields — changes are not saved
                    while a line is incomplete or invalid.
                  </p>
                )}
                {itemisedOverflow && (
                  <p className="mt-2 text-xs text-red-600" role="alert">
                    The quote total exceeds the maximum of $99,999,999.99, so
                    it cannot be saved.
                  </p>
                )}
              </div>
            )}

            {/* Totals — right-aligned card like the invoice, WITHOUT the
                invoice-only Payment Made / Balance Due rows. Non-itemised: the
                GST-inclusive quote total is the editable field. Itemised: both
                totals are derived from the visible rows only. */}
            <div className="mt-8 sm:ml-auto sm:w-[360px] rounded-md border border-slate-200">
              {lockedItemised && serverDraft ? (
                <>
                  <div className="flex items-center justify-between px-4 py-2.5">
                    <span className="text-sm text-slate-700">
                      Total Ex. GST
                    </span>
                    <span className="text-sm font-medium tabular-nums text-slate-900">
                      {formatMoney(serverDraft.quote_total_ex_gst)}
                    </span>
                  </div>
                  <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
                    <span className="text-sm text-slate-700">
                      Quote Total Inc. GST
                    </span>
                    <span className="text-sm font-semibold tabular-nums text-slate-900">
                      {formatMoney(serverDraft.quote_total_inc_gst)}
                    </span>
                  </div>
                </>
              ) : draftEditable && itemised ? (
                <>
                  <div className="flex items-center justify-between px-4 py-2.5">
                    <span className="text-sm text-slate-700">
                      Total Ex. GST
                    </span>
                    <span className="text-sm font-medium tabular-nums text-slate-900">
                      {formatMoney(itemisedTotals.ex)}
                    </span>
                  </div>
                  <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
                    <span className="text-sm text-slate-700">
                      Quote Total Inc. GST
                    </span>
                    <span className="text-sm font-semibold tabular-nums text-slate-900">
                      {formatMoney(itemisedTotals.inc)}
                    </span>
                  </div>
                </>
              ) : (
                <>
                  <div className="px-4 py-2.5">
                    <label
                      htmlFor="quote_total_inc_gst"
                      className="block text-sm text-slate-700 mb-1.5"
                    >
                      Quote Total Inc. GST
                    </label>
                    <Input
                      id="quote_total_inc_gst"
                      type="text"
                      inputMode="decimal"
                      value={totalInput}
                      onChange={(e) => handleTotalChange(e.target.value)}
                      onBlur={flushNow}
                      invalid={totalInvalid}
                      disabled={!draftEditable}
                      placeholder="0.00"
                      className="font-semibold tabular-nums text-right"
                    />
                    {totalInvalid ? (
                      <p className="mt-1.5 text-xs text-red-600" role="alert">
                        Enter a quote total between 0 and 99,999,999.99.
                      </p>
                    ) : totalTrimmed === '' && !persistedDraftExists ? (
                      <p className="mt-1.5 text-xs text-slate-400">
                        Enter the customer-facing quote total to start this
                        quote.
                      </p>
                    ) : null}
                  </div>
                  {serverDraft && !serverDraft.itemised && (
                    <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
                      <span className="text-sm text-slate-700">
                        Total Ex. GST
                      </span>
                      <span className="text-sm font-medium tabular-nums text-slate-900">
                        {formatMoney(serverDraft.quote_total_ex_gst)}
                      </span>
                    </div>
                  )}
                </>
              )}
            </div>

            {/* Intended-total HELPER (itemised editing only). Client-side aid:
                never sent, never saved. Offers the visible discount-adjustment
                fix when the intended total is BELOW the line sum; message-only
                when ABOVE (never a hidden/automatic increase). */}
            {draftEditable && itemised && (
              <div className="mt-3 sm:ml-auto sm:w-[360px]">
                <label
                  htmlFor="quote_intended_total"
                  className="block text-xs text-slate-500 mb-1"
                >
                  Intended quote total (inc GST) — helper only, never saved
                </label>
                <Input
                  id="quote_intended_total"
                  type="text"
                  inputMode="decimal"
                  value={intendedInput}
                  onChange={(e) => setIntendedInput(e.target.value)}
                  invalid={intendedInvalid}
                  placeholder="Optional"
                  className="h-9 text-sm text-right tabular-nums"
                />
                {intendedInvalid && (
                  <p className="mt-1 text-xs text-red-600" role="alert">
                    Enter a valid amount between 0 and 99,999,999.99.
                  </p>
                )}
                {intendedMismatch === 'lower' && (
                  <div className="mt-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2">
                    <p className="text-xs text-amber-900">
                      Itemised lines are {formatMoney(intendedDiff)} above the
                      quote total.
                    </p>
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      className="mt-2"
                      onClick={handleAddDiscountAdjustment}
                    >
                      Add discount adjustment
                    </Button>
                  </div>
                )}
                {intendedMismatch === 'higher' && (
                  <p className="mt-2 rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
                    Quote total is {formatMoney(intendedDiff)} above the
                    itemised lines. Adjust item prices or add an additional
                    works / price adjustment line.
                  </p>
                )}
              </div>
            )}

            <div className="my-8 border-t border-slate-200" />

            {sanitizedTermsHtml ? (
              <div>
                <div className="text-center text-xs font-semibold uppercase tracking-wider text-slate-800">
                  Terms and Conditions — applicable to this quote
                </div>
                {/* Sanitized HTML only (see sanitizedTermsHtml). The scoped
                    `.invoice-terms` CSS in index.css is generic terms styling
                    (lists/tables restored past Tailwind preflight) — reused here
                    unchanged; screen-only. */}
                <div
                  className="invoice-terms mt-4 text-xs text-slate-700 leading-relaxed"
                  dangerouslySetInnerHTML={{ __html: sanitizedTermsHtml }}
                />
              </div>
            ) : tenantConfigError ? (
              <div className="text-center">
                <p className="text-xs text-slate-500">
                  Quote terms could not be loaded.
                </p>
                <div className="mt-2 flex justify-center">
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    onClick={() =>
                      setBrandingReloadToken((token) => token + 1)
                    }
                  >
                    Try again
                  </Button>
                </div>
              </div>
            ) : tenantConfigLoading ? (
              <p className="text-center text-xs text-slate-400">
                Loading quote terms…
              </p>
            ) : null}

            {/* Bottom action bar — Preview PDF + Send Quote (locked labels).
                Native buttons for the full-width document-anchor styling, like
                the invoice action bar. No Save button — the draft autosaves. */}
            <div className="mt-8 flex flex-col sm:flex-row gap-3">
              <button
                type="button"
                onClick={handlePreviewPdf}
                disabled={
                  previewing || !previewEnabled || seedState === 'loading'
                }
                title={
                  !previewEnabled
                    ? 'Save the quote before previewing the PDF.'
                    : undefined
                }
                className="flex w-full items-center justify-center rounded-md border border-teal-600 bg-white px-6 py-3.5 text-sm font-semibold text-teal-700 shadow-sm transition-colors hover:bg-teal-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {previewing ? 'Preparing…' : 'Preview PDF'}
              </button>
              <button
                type="button"
                onClick={() => void handleOpenSendModal()}
                disabled={locked || sendCheck === 'checking' || converting}
                title={
                  locked
                    ? 'This order is laid and locked, so the quote cannot be sent.'
                    : undefined
                }
                className="flex w-full items-center justify-center rounded-md bg-teal-600 px-6 py-3.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {sendCheck === 'checking' ? 'Checking…' : 'Send Quote'}
              </button>
            </div>
          </article>
        </div>
      )}

      {/* Customer Quote — the ISSUED quote surface (16E-B). Everything below
          renders from the `issued` summary ONLY (the frozen issue snapshot):
          never the live draft rows/totals, live details, live terms or any
          autosave state, all of which may have drifted since the issue. No
          cost, GP, token, hash, storage path or file id is ever rendered. */}
      {subTab === 'customer' &&
        (issued === null ? (
          accepted !== null ? (
            // 16F PR3: no active issued quote, but one was accepted (its link
            // is consumed) - say so and point to Accepted Quote; never the
            // never-sent empty state.
            <div className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center">
              <span className="inline-flex items-center rounded-md border border-teal-200 bg-teal-50 px-2 py-0.5 text-xs font-medium text-teal-700">
                Accepted
              </span>
              <p className="mt-3 break-words text-base font-medium text-slate-700">
                Version {accepted.version_number} was accepted by{' '}
                {accepted.accepted_customer_name} on{' '}
                {formatTimestamp(accepted.accepted_at)}.
              </p>
              <p className="mt-1 text-sm text-slate-500">
                There is no quote waiting for a signature.
              </p>
              <div className="mt-4 flex justify-center">
                <Button
                  type="button"
                  variant="secondary"
                  size="md"
                  onClick={() => handleSubTabChange('accepted')}
                >
                  View accepted quote
                </Button>
              </div>
            </div>
          ) : (
            <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-10 text-center">
              <p className="text-base font-medium text-slate-700">
                No quote has been sent yet.
              </p>
            </div>
          )
        ) : (
          <div className="space-y-5">
            {issuedPreviewError && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
                <p className="text-sm font-medium text-rose-700">
                  {issuedPreviewError}
                </p>
              </div>
            )}

            {/* Summary panel: status/channel left, sent time/link expiry right. */}
            <div className="rounded-lg border border-slate-200 bg-white px-5 py-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3">
                <div className="space-y-3">
                  <div>
                    <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                      Status
                    </div>
                    <div className="mt-1">
                      {emailNotDelivered(issued) ? (
                        // The latest EMAIL attempt failed (see the
                        // emailNotDelivered helper for the timestamp
                        // reasoning) — "Sent" OR "Opened" would misreport it:
                        // an unchanged resend replaced the token, so a
                        // previously-opened link is dead and the failure is
                        // the actionable state; viewed_at is only settable
                        // from 16E-C onward. SMS keeps the plain Sent badge
                        // (no success marker exists).
                        <>
                          <span className="inline-flex items-center rounded-md border border-amber-300 bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-800">
                            Not delivered
                          </span>
                          <p className="mt-1 text-xs text-amber-800">
                            The email could not be delivered. Resend to try
                            again.
                          </p>
                        </>
                      ) : issued.viewed_at !== null ? (
                        <span className="inline-flex items-center rounded-md border border-teal-200 bg-teal-50 px-2 py-0.5 text-xs font-medium text-teal-700">
                          Opened
                        </span>
                      ) : (
                        <span className="inline-flex items-center rounded-md border border-slate-300 bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-700">
                          Sent
                        </span>
                      )}
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                      Channel
                    </div>
                    <div className="mt-1 text-sm text-slate-800">
                      {channelLabel(issued.sent_channel)}
                    </div>
                  </div>
                </div>
                <div className="space-y-3 sm:text-right">
                  <div>
                    <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                      Sent
                    </div>
                    <div className="mt-1 text-sm text-slate-800">
                      {issued.last_sent_at
                        ? formatTimestamp(issued.last_sent_at)
                        : '—'}
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                      Link expires
                    </div>
                    {/* Display-only: expiry enforcement is the public page's
                        concern (16E-C). */}
                    <div className="mt-1 text-sm text-slate-800">
                      {issued.token_expires_at
                        ? formatTimestamp(issued.token_expires_at)
                        : '—'}
                    </div>
                  </div>
                </div>
              </div>

              {/* Action row. LAID: Resend disabled (a send is an order-state
                  write); Preview PDF and Cancel quote stay ENABLED (stored-PDF
                  read / link kill only). */}
              <div className="mt-4 flex flex-wrap gap-2 border-t border-slate-100 pt-4">
                <Button
                  type="button"
                  variant="secondary"
                  size="md"
                  onClick={handleIssuedPreviewPdf}
                  disabled={issuedPreviewing}
                >
                  {issuedPreviewing ? 'Preparing…' : 'Preview PDF'}
                </Button>
                <Button
                  type="button"
                  variant="success"
                  size="md"
                  onClick={() => void handleOpenSendModal()}
                  disabled={locked || sendCheck === 'checking' || converting}
                  title={
                    locked
                      ? 'This order is laid and locked, so the quote cannot be resent.'
                      : undefined
                  }
                >
                  {sendCheck === 'checking' ? 'Checking…' : 'Resend'}
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  size="md"
                  // Not while the pre-Send status read is running: that read may
                  // open the Send confirmation, which must never stack on this one.
                  disabled={sendCheck === 'checking'}
                  onClick={() => {
                    if (sendCheckRef.current) return
                    setCancelError(null)
                    setActionNotice(null)
                    setCancelModalOpen(true)
                  }}
                >
                  Cancel quote
                </Button>
              </div>
            </div>

            {/* Read-only issued snapshot — a draft-style document view built
                from the FROZEN current_issued body only. No inputs, no
                editing, no autosave, no draft mutation. */}
            <article className="rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-6 sm:px-8 sm:py-8 lg:px-10 lg:py-8">
              <header className="flex items-start justify-between gap-4">
                <div className="text-xl font-bold text-slate-900 tracking-tight">
                  Issued quote
                </div>
                <div className="text-sm text-slate-500">
                  Version {issued.version_number}
                </div>
              </header>

              <div className="my-6 border-t border-slate-200" />

              <div>
                <div className="text-base font-semibold text-slate-900">
                  Details Of Sale
                </div>
                <p className="mt-2 text-sm text-slate-700 leading-relaxed whitespace-pre-wrap">
                  {issued.details_of_sale || '—'}
                </p>
              </div>

              {/* Itemised issue: the frozen snapshot lines (always [] for a
                  non-itemised issue — the itemised flag drives this branch, so
                  a non-itemised quote never renders a line table). */}
              {issued.itemised && (
                <div className="mt-8 overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-slate-200 text-[11px] uppercase tracking-wider text-slate-500">
                        <th className="py-2 pr-3 text-left font-semibold">
                          Description
                        </th>
                        <th className="py-2 px-3 text-right font-semibold">
                          Quantity
                        </th>
                        <th className="py-2 px-3 text-right font-semibold">
                          Unit price
                        </th>
                        <th className="py-2 pl-3 text-right font-semibold">
                          Amount
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {issued.lines.map((line, idx) => (
                        <tr
                          key={`issued-line-${idx}`}
                          className="border-b border-slate-100"
                        >
                          <td className="py-2 pr-3 text-slate-800">
                            {line.description}
                          </td>
                          <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                            {line.quantity !== null
                              ? toFixed2(line.quantity)
                              : '—'}
                          </td>
                          <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                            {line.unit_price_ex_gst !== null
                              ? formatMoney(line.unit_price_ex_gst)
                              : '—'}
                          </td>
                          <td className="py-2 pl-3 text-right tabular-nums text-slate-900">
                            {formatMoney(line.line_total_ex_gst)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {/* Totals from the frozen summary. For a non-itemised issue this
                  single quoted amount IS the whole presentation — no line
                  table. */}
              <div className="mt-8 sm:ml-auto sm:w-[360px] rounded-md border border-slate-200">
                <div className="flex items-center justify-between px-4 py-2.5">
                  <span className="text-sm text-slate-700">Total Ex. GST</span>
                  <span className="text-sm font-medium tabular-nums text-slate-900">
                    {formatMoney(issued.quote_total_ex_gst)}
                  </span>
                </div>
                <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
                  <span className="text-sm text-slate-700">
                    Quote Total Inc. GST
                  </span>
                  <span className="text-sm font-semibold tabular-nums text-slate-900">
                    {formatMoney(issued.quote_total_inc_gst)}
                  </span>
                </div>
              </div>
            </article>
          </div>
        ))}

      {/* Accepted Quote (16F PR3) - the latest ACCEPTED version, rendered from
          the `accepted` summary ONLY (the frozen accepted snapshot): never the
          live draft, the live order customer/address or live tenant terms (the
          full frozen document, terms included, is the signed PDF). No cost, GP,
          token, storage path or file id is rendered. */}
      {subTab === 'accepted' && (
        <div className="space-y-5">
          {/* The Create Invoice outcome banners render ABOVE both the canvas and
              the empty state: a drift refresh that finds no accepted quote any
              more must not erase the error it was triggered by. */}
          {convertNotice && (
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
              <p className="text-sm font-medium text-amber-800">
                {convertNotice}
              </p>
            </div>
          )}
          {/* Create Invoice errors live here (outside the confirmation) so a
              drift refresh can update eligibility without erasing them.
              Backend messages verbatim; precondition details in the Details
              of Sale "field: message" convention. */}
          {convertError && (
            <div
              role="alert"
              className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3"
            >
              <p className="text-sm font-medium text-rose-700">
                {convertError}
              </p>
              {convertErrorDetails.length > 0 && (
                <ul className="mt-1 space-y-0.5">
                  {convertErrorDetails.map((failure, idx) => (
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
          {acceptedPreviewError && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
              <p className="text-sm font-medium text-rose-700">
                {acceptedPreviewError}
              </p>
            </div>
          )}

          {accepted === null ? (
            <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-10 text-center">
              <p className="text-base font-medium text-slate-700">
                No quote has been accepted yet.
              </p>
            </div>
          ) : (
            <article className="rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-6 sm:px-8 sm:py-8 lg:px-10 lg:py-8">
              <header className="flex flex-wrap items-start justify-between gap-4">
                <div className="text-xl font-bold text-slate-900 tracking-tight">
                  Accepted quote
                </div>
                <div className="flex items-center gap-2">
                  <span className="inline-flex items-center rounded-md border border-teal-200 bg-teal-50 px-2 py-0.5 text-xs font-medium text-teal-700">
                    Accepted
                  </span>
                  <span className="text-sm text-slate-500">
                    Version {accepted.version_number}
                  </span>
                </div>
              </header>

              <div className="my-6 border-t border-slate-200" />

              <div>
                <div className="text-base font-semibold text-slate-900">
                  Details Of Sale
                </div>
                <p className="mt-2 text-sm text-slate-700 leading-relaxed whitespace-pre-wrap break-words">
                  {accepted.details_of_sale || '—'}
                </p>
              </div>

              {/* Frozen lines ONLY for an itemised accepted quote - the itemised
                  flag drives this branch, so a non-itemised quote never renders
                  a line table (whatever `lines` holds). */}
              {accepted.itemised && (
                <div className="mt-8 overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-slate-200 text-[11px] uppercase tracking-wider text-slate-500">
                        <th className="py-2 pr-3 text-left font-semibold">
                          Description
                        </th>
                        <th className="py-2 px-3 text-right font-semibold">
                          Quantity
                        </th>
                        <th className="py-2 px-3 text-right font-semibold">
                          Unit price
                        </th>
                        <th className="py-2 pl-3 text-right font-semibold">
                          Amount
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {accepted.lines.map((line, idx) => (
                        <tr
                          key={`accepted-line-${idx}`}
                          className="border-b border-slate-100"
                        >
                          <td className="py-2 pr-3 text-slate-800">
                            {line.description}
                          </td>
                          <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                            {line.quantity !== null
                              ? toFixed2(line.quantity)
                              : '—'}
                          </td>
                          <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                            {line.unit_price_ex_gst !== null
                              ? formatMoney(line.unit_price_ex_gst)
                              : '—'}
                          </td>
                          <td className="py-2 pl-3 text-right tabular-nums text-slate-900">
                            {formatMoney(line.line_total_ex_gst)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {/* Frozen totals, shown as returned (never recalculated). */}
              <div className="mt-8 sm:ml-auto sm:w-[360px] rounded-md border border-slate-200">
                <div className="flex items-center justify-between px-4 py-2.5">
                  <span className="text-sm text-slate-700">Total Ex. GST</span>
                  <span className="text-sm font-medium tabular-nums text-slate-900">
                    {formatMoney(accepted.quote_total_ex_gst)}
                  </span>
                </div>
                <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
                  <span className="text-sm text-slate-700">
                    Quote Total Inc. GST
                  </span>
                  <span className="text-sm font-semibold tabular-nums text-slate-900">
                    {formatMoney(accepted.quote_total_inc_gst)}
                  </span>
                </div>
              </div>

              <div className="my-8 border-t border-slate-200" />

              {/* The customer's signature (the stored image, consumed through the
                  verbatim backend path), the accepted name exactly as frozen
                  (never truncated) and the accepted time - the Invoice tab's
                  accepted-signature composition. */}
              <div className="flex flex-col items-center">
                <div className="text-sm font-semibold text-slate-700">
                  Customer Signature
                </div>
                <div className="mt-2 flex w-full max-w-[340px] flex-col items-center text-center">
                  <div className="flex h-16 w-full items-end justify-center border-b border-slate-300">
                    {acceptedSignatureUrl !== null ? (
                      <img
                        src={acceptedSignatureUrl}
                        alt={`Signature of ${accepted.accepted_customer_name}`}
                        className="max-h-14 w-auto pb-0.5"
                      />
                    ) : accepted.signature_download_path === null ? (
                      <p className="pb-2 text-[11px] text-slate-500">
                        No signature image stored.
                      </p>
                    ) : acceptedSignatureFailed ? (
                      <div className="flex items-center gap-2 pb-1">
                        <p className="text-[11px] text-slate-500">
                          The signature image could not be loaded.
                        </p>
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          onClick={() =>
                            setAcceptedSignatureReload((count) => count + 1)
                          }
                        >
                          Retry
                        </Button>
                      </div>
                    ) : (
                      <p className="pb-2 text-[11px] text-slate-500">
                        Loading signature…
                      </p>
                    )}
                  </div>
                  <div className="mt-1.5 w-full break-words text-sm font-medium text-slate-800">
                    {accepted.accepted_customer_name}
                  </div>
                  <div className="mt-0.5 text-[11px] text-slate-500 tabular-nums">
                    Accepted on {formatTimestamp(accepted.accepted_at)}
                  </div>
                </div>
              </div>

              {/* Actions: Preview signed PDF (the STORED signed PDF; no separate
                  download button) + Create Invoice (Path A). Create Invoice is
                  enabled by invoice_eligible alone (plus in-flight gates): never
                  disabled by LAID, a draft or a newer issued quote. */}
              <div className="mt-8 flex flex-col sm:flex-row gap-3">
                {accepted.signed_pdf_available && (
                  <button
                    type="button"
                    onClick={() => void handleAcceptedPreviewPdf()}
                    disabled={acceptedPreviewing}
                    className="flex w-full items-center justify-center rounded-md border border-teal-600 bg-white px-6 py-3.5 text-sm font-semibold text-teal-700 shadow-sm transition-colors hover:bg-teal-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {acceptedPreviewing ? 'Preparing…' : 'Preview signed PDF'}
                  </button>
                )}
                <button
                  type="button"
                  onClick={handleOpenConvertModal}
                  disabled={createInvoiceDisabled}
                  className="flex w-full items-center justify-center rounded-md bg-teal-600 px-6 py-3.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {converting ? 'Creating invoice…' : 'Create Invoice'}
                </button>
              </div>
              {createInvoiceNote !== null && (
                <p className="mt-2 text-xs text-slate-600 sm:text-right">
                  {createInvoiceNote}
                </p>
              )}
            </article>
          )}
        </div>
      )}

      {/* Send Quote confirmation (16E-B) — the ONLY send path (first send and
          Resend alike; hard rule: no accidental one-click sending). Send by
          Email flushes details + quote autosaves first, is single-flight, and
          is disabled when LAID. SMS stays disabled until 16E-C ("Available
          soon") — no backend SMS call is ever made from here. */}
      <Modal
        open={sendModalOpen}
        onClose={() => {
          // Keep the modal up while a send is in flight so its outcome
          // (success close / in-modal error) is never lost behind a dismiss.
          if (!sending) setSendModalOpen(false)
        }}
        labelledBy="send-quote-title"
      >
        <div className="p-6">
          {/* 16F PR3 (decision D9): with an accepted quote, the modal FIRST shows
              that accepted state (from the pre-Send refresh) and warns that
              sending issues a new version needing a new signature. The server
              still decides new version vs unchanged resend. */}
          {accepted !== null && (
            <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
              <div className="text-[11px] font-semibold uppercase tracking-wider text-amber-800">
                Accepted quote
              </div>
              <p className="mt-1 break-words text-sm text-amber-900">
                Version {accepted.version_number} was accepted by{' '}
                <span className="font-semibold">
                  {accepted.accepted_customer_name}
                </span>{' '}
                on {formatTimestamp(accepted.accepted_at)}.
              </p>
              <p className="mt-2 text-sm font-semibold text-amber-900">
                Sending again creates a new quote version that needs a new
                signature.
              </p>
            </div>
          )}
          <h3
            id="send-quote-title"
            className="text-lg font-semibold text-slate-900 tracking-tight"
          >
            Are you sure you want to send this quote?
          </h3>
          <p className="mt-2 text-sm text-slate-600 leading-relaxed">
            Please double-check all quote details before sending.
          </p>
          {sendError && (
            <div className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
              <p className="text-sm font-medium text-rose-700">{sendError}</p>
              {sendEmailFixNeeded && (
                <div className="mt-2 flex flex-col sm:flex-row sm:items-center gap-2">
                  <p className="text-xs text-rose-700">
                    Add or fix the customer's email on the Customer tab, then
                    try again.
                  </p>
                  {onGoToCustomer && (
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      onClick={() => {
                        setSendModalOpen(false)
                        onGoToCustomer()
                      }}
                    >
                      Go to Customer tab
                    </Button>
                  )}
                </div>
              )}
            </div>
          )}
          <div className="mt-5 space-y-2">
            <Button
              type="button"
              variant="success"
              size="md"
              disabled={locked || sending || cancelling || converting}
              title={
                locked
                  ? 'This order is laid and locked, so the quote cannot be sent.'
                  : undefined
              }
              onClick={handleSendEmail}
              className="w-full"
            >
              {sending ? 'Sending…' : 'Send by Email'}
            </Button>
            <Button
              type="button"
              variant="success"
              size="md"
              disabled
              title="Available soon"
              className="w-full"
            >
              Send by Phone/SMS
            </Button>
            <p className="text-center text-[11px] text-slate-500">
              Sending by Phone/SMS — Available soon.
            </p>
          </div>
          <div className="mt-4 flex justify-end">
            <Button
              type="button"
              variant="secondary"
              size="md"
              disabled={sending}
              onClick={() => setSendModalOpen(false)}
            >
              Cancel
            </Button>
          </div>
        </div>
      </Modal>

      {/* Cancel quote confirmation (16E-B) — its own confirmation, separate
          from the send modal. Allowed when LAID (kills the public link only). */}
      <Modal
        open={cancelModalOpen}
        onClose={() => {
          if (!cancelling) setCancelModalOpen(false)
        }}
        labelledBy="cancel-quote-title"
      >
        <div className="p-6">
          <h3
            id="cancel-quote-title"
            className="text-lg font-semibold text-slate-900 tracking-tight"
          >
            Cancel this quote?
          </h3>
          <p className="mt-2 text-sm text-slate-600 leading-relaxed">
            The customer's quote link will stop working. You can send a new
            quote at any time.
          </p>
          {cancelError && (
            <div className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
              <p className="text-sm font-medium text-rose-700">
                {cancelError}
              </p>
            </div>
          )}
          <div className="mt-5 flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              size="md"
              disabled={cancelling}
              onClick={() => setCancelModalOpen(false)}
            >
              Keep quote
            </Button>
            <Button
              type="button"
              variant="primary"
              size="md"
              disabled={cancelling || sending || converting}
              onClick={handleCancelQuote}
            >
              {cancelling ? 'Cancelling…' : 'Cancel quote'}
            </Button>
          </div>
        </div>
      </Modal>

      {/* Create Invoice confirmation (16F PR3, Path A) - bound to the accepted
          version it was opened for (a refresh that changes it closes this). The
          wording comes from a current-invoice read: no invoice / unsigned /
          signed. A failed read never counts as "no invoice", so confirming waits
          for a successful read. The POST body is exactly {}. */}
      <Modal
        open={convertModalOpen}
        onClose={() => {
          // Keep the modal up while the POST is in flight so its outcome is
          // never lost behind a dismiss.
          if (!converting) closeConvertModal()
        }}
        labelledBy="create-invoice-title"
      >
        <div className="p-6">
          <h3
            id="create-invoice-title"
            className="text-lg font-semibold text-slate-900 tracking-tight"
          >
            Create the invoice from the signed quote?
          </h3>
          {convertTarget !== null && (
            <p className="mt-2 text-sm text-slate-600 leading-relaxed">
              The invoice is created from the signed quote (version{' '}
              {convertTarget.versionNumber},{' '}
              {formatMoney(convertTarget.totalIncGst)} inc GST).
            </p>
          )}
          <ul className="mt-3 list-disc space-y-1.5 pl-5 text-sm text-slate-600 leading-relaxed">
            <li>
              It inherits the customer&apos;s signature from the quote, so the
              customer does not sign again.
            </li>
            <li>Any recorded payments are carried onto the invoice.</li>
          </ul>
          {convertInvoiceLookup === null ||
          convertInvoiceLookup.status === 'loading' ? (
            <p className="mt-3 text-sm text-slate-500">
              Checking the current invoice…
            </p>
          ) : convertInvoiceLookup.status === 'error' ? (
            <div className="mt-3 flex flex-col gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
              <p className="text-sm text-amber-800">
                The current invoice could not be checked, so this cannot be
                confirmed yet.
              </p>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                disabled={converting}
                onClick={handleRetryConvertLookup}
              >
                Try again
              </Button>
            </div>
          ) : convertInvoiceLookup.invoice === null ? (
            <p className="mt-3 text-sm text-slate-700">
              This order has no invoice yet, so this creates its first invoice.
            </p>
          ) : convertInvoiceLookup.invoice.accepted_at === null ? (
            <p className="mt-3 text-sm font-semibold text-slate-900">
              This replaces the current unsigned invoice.
            </p>
          ) : (
            <p className="mt-3 text-sm font-semibold text-slate-900">
              A new invoice version replaces the current signed invoice. The
              earlier signed version remains in history.
            </p>
          )}
          <div className="mt-5 flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              size="md"
              disabled={converting}
              onClick={closeConvertModal}
            >
              Cancel
            </Button>
            <Button
              type="button"
              variant="success"
              size="md"
              disabled={
                converting ||
                sending ||
                cancelling ||
                convertInvoiceLookup === null ||
                convertInvoiceLookup.status !== 'ready'
              }
              onClick={() => void handleConfirmCreateInvoice()}
            >
              {converting ? 'Creating invoice…' : 'Create Invoice'}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
