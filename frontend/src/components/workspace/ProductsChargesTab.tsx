import { useEffect, useRef, useState } from 'react'
import { Button } from '../ui/Button'
import { ChevronDownIcon, SearchIcon, TrashIcon } from '../icons'
import { FLOORING_LABELS, type FlooringType } from '../../lib/flooring'
import { ApiError } from '../../lib/api/ApiError'
import {
  addChargeLine,
  addProductLine,
  deleteChargeLine,
  deleteProductLine,
  fetchAvailableCharges,
  fetchAvailableProducts,
  fetchOrderLines,
  updateChargeLine,
  updateProductLine,
} from '../../lib/api/orderLinesApi'
import type {
  AddChargeLineRequest,
  AddProductLineRequest,
  AvailableCharge,
  AvailableProduct,
  ChargeLineRead,
  OrderFinancialSummary,
  PatchChargeLineRequest,
  PatchProductLineRequest,
  ProductLineRead,
} from '../../lib/api/orderLinesApi'

type Props = {
  orderId: number
  flooringType: FlooringType
  // Locked is true when the order is LAID. It disables all product/charge
  // mutations while still allowing the lines to be read/displayed.
  locked: boolean
  // Single shared financial-summary recency guard from OrderWorkspace. Each
  // summary-bearing op claims a sequence via beginFinancialSummaryRequest() at
  // issue time and applies the response via applyFinancialSummaryFromRequest(),
  // so a stale (older) response cannot overwrite a newer one in the header.
  beginFinancialSummaryRequest: () => number
  applyFinancialSummaryFromRequest: (
    seq: number,
    summary: OrderFinancialSummary,
  ) => boolean
}

// Editable string mirror of a product line's three editable fields. quantity_sqm
// is derived live from quantity_lm (and vice-versa) using the line's snapshotted
// sqm_per_lm factor — this is input preview only; the backend remains the source
// of truth after every save.
type ProductRowDraft = {
  quantity_lm: string
  quantity_sqm: string
  unit_price: string
}

type ChargeRowDraft = {
  quantity: string
  unit_price: string
}

// Which quantity field the user last typed into in the add panel — decides which
// single quantity we send (the request must carry exactly one of lm/sqm).
type QuantityField = 'LM' | 'SQM'

const MONEY_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

function formatMoney(value: number): string {
  return MONEY_FORMATTER.format(value)
}

// Parse a decimal input string. Returns null for blank / non-finite input.
// Never clamps — callers apply the > 0 rule the backend enforces.
function parseNumber(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : null
}

function round2(value: number): number {
  return Math.round(value * 100) / 100
}

function toFixed2(value: number): string {
  return value.toFixed(2)
}

// Per-product / per-line conversion. factor is the catalog sqm_per_lm (add form)
// or the line's sqm_per_lm_snapshot (existing line) — never a hardcoded constant.
function deriveSqmFromLm(lmString: string, factor: number): string {
  const lm = parseNumber(lmString)
  if (lm === null || lm <= 0 || !(factor > 0)) return ''
  return toFixed2(round2(lm * factor))
}

function deriveLmFromSqm(sqmString: string, factor: number): string {
  const sqm = parseNumber(sqmString)
  if (sqm === null || sqm <= 0 || !(factor > 0)) return ''
  return toFixed2(round2(sqm / factor))
}

function productDraftFrom(line: ProductLineRead): ProductRowDraft {
  return {
    quantity_lm: toFixed2(line.quantity_lm),
    quantity_sqm: toFixed2(line.quantity_sqm),
    unit_price: toFixed2(line.unit_price),
  }
}

function chargeDraftFrom(line: ChargeLineRead): ChargeRowDraft {
  return {
    quantity: toFixed2(line.quantity),
    unit_price: toFixed2(line.unit_price),
  }
}

function buildProductDrafts(
  lines: ProductLineRead[],
): Record<number, ProductRowDraft> {
  const map: Record<number, ProductRowDraft> = {}
  for (const line of lines) {
    map[line.order_product_line_id] = productDraftFrom(line)
  }
  return map
}

function buildChargeDrafts(
  lines: ChargeLineRead[],
): Record<number, ChargeRowDraft> {
  const map: Record<number, ChargeRowDraft> = {}
  for (const line of lines) {
    map[line.order_charge_line_id] = chargeDraftFrom(line)
  }
  return map
}

function apiErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.message.length > 0) return err.message
  return 'Something went wrong. Please try again.'
}

const CELL_INPUT_CLASS =
  'w-full h-8 rounded-md border border-slate-300 bg-white px-2 text-sm text-slate-900 text-right tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed'

const CARD_INPUT_CLASS =
  'w-full h-9 mt-0.5 rounded-md border border-slate-300 bg-white px-2 text-sm text-slate-900 text-right tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed'

const PANEL_INPUT_CLASS =
  'w-full h-10 mt-1 rounded-md border border-slate-300 bg-white px-3 text-sm text-slate-900 placeholder:text-slate-400 tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed'

const REMOVE_BUTTON_CLASS =
  'inline-flex items-center justify-center w-7 h-7 rounded-md text-slate-400 hover:bg-rose-50 hover:text-rose-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500/30 transition-colors disabled:opacity-50 disabled:cursor-not-allowed'

export function ProductsChargesTab({
  orderId,
  flooringType,
  locked,
  beginFinancialSummaryRequest,
  applyFinancialSummaryFromRequest,
}: Props) {
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const [productLines, setProductLines] = useState<ProductLineRead[]>([])
  const [chargeLines, setChargeLines] = useState<ChargeLineRead[]>([])
  const [summary, setSummary] = useState<OrderFinancialSummary | null>(null)

  const [productDrafts, setProductDrafts] = useState<
    Record<number, ProductRowDraft>
  >({})
  const [chargeDrafts, setChargeDrafts] = useState<
    Record<number, ChargeRowDraft>
  >({})

  // Lines currently mid-mutation — their row controls are disabled to prevent a
  // second in-flight request and to avoid stale responses fighting each other.
  const [mutatingProductIds, setMutatingProductIds] = useState<Set<number>>(
    () => new Set(),
  )
  const [mutatingChargeIds, setMutatingChargeIds] = useState<Set<number>>(
    () => new Set(),
  )
  const [mutationError, setMutationError] = useState<string | null>(null)

  // Add panels.
  const [productPanelOpen, setProductPanelOpen] = useState(false)
  const [chargePanelOpen, setChargePanelOpen] = useState(false)
  const [addingProduct, setAddingProduct] = useState(false)
  const [addingCharge, setAddingCharge] = useState(false)
  const [addProductError, setAddProductError] = useState<string | null>(null)
  const [addChargeError, setAddChargeError] = useState<string | null>(null)

  // Product catalog search.
  const [productSearch, setProductSearch] = useState('')
  const [productResults, setProductResults] = useState<AvailableProduct[]>([])
  const [productSearching, setProductSearching] = useState(false)
  const [productSearchError, setProductSearchError] = useState<string | null>(
    null,
  )
  const [selectedProduct, setSelectedProduct] = useState<AvailableProduct | null>(
    null,
  )
  const [productPanelLm, setProductPanelLm] = useState('')
  const [productPanelSqm, setProductPanelSqm] = useState('')
  const [productPanelPrice, setProductPanelPrice] = useState('')
  const [productPanelLastEdited, setProductPanelLastEdited] =
    useState<QuantityField>('LM')

  // Charge catalog search.
  const [chargeSearch, setChargeSearch] = useState('')
  const [chargeResults, setChargeResults] = useState<AvailableCharge[]>([])
  const [chargeSearching, setChargeSearching] = useState(false)
  const [chargeSearchError, setChargeSearchError] = useState<string | null>(null)
  const [selectedCharge, setSelectedCharge] = useState<AvailableCharge | null>(
    null,
  )
  const [chargePanelQty, setChargePanelQty] = useState('')
  const [chargePanelPrice, setChargePanelPrice] = useState('')

  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // Keep the parent guard callbacks in refs so the load effect never resubscribes
  // on them.
  const beginRequestRef = useRef(beginFinancialSummaryRequest)
  const applyRequestRef = useRef(applyFinancialSummaryFromRequest)
  useEffect(() => {
    beginRequestRef.current = beginFinancialSummaryRequest
    applyRequestRef.current = applyFinancialSummaryFromRequest
  }, [beginFinancialSummaryRequest, applyFinancialSummaryFromRequest])

  // Per-tab monotonic sequence for the LOCAL summary mirror only (B7). Guards the
  // child-local subtotal mirror against an out-of-order response. The workspace
  // header uses the parent's single shared recency guard (applyRequestRef).
  const reqSeqRef = useRef(0)
  const appliedSummarySeqRef = useRef(0)
  function applySummary(
    localSeq: number,
    parentSeq: number,
    next: OrderFinancialSummary,
  ) {
    // Workspace header: single shared recency guard across ALL summary sources.
    // Runs even if this tab has since unmounted (the shell is still mounted); the
    // parent discards it if a newer request from any source has been issued.
    applyRequestRef.current(parentSeq, next)
    // Child-local mirror: per-tab out-of-order guard; only updates while mounted.
    if (localSeq < appliedSummarySeqRef.current) return
    appliedSummarySeqRef.current = localSeq
    if (mountedRef.current) setSummary(next)
  }

  // Stale-search guards: only the latest issued search applies its results.
  const productSearchSeqRef = useRef(0)
  const chargeSearchSeqRef = useRef(0)

  // Load all lines + the live summary whenever the order changes or a retry is
  // requested.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    setMutationError(null)
    const seq = ++reqSeqRef.current
    const parentSeq = beginRequestRef.current()
    fetchOrderLines(orderId)
      .then((res) => {
        if (cancelled) return
        const data = res.data
        setProductLines(data.product_lines)
        setChargeLines(data.charge_lines)
        setProductDrafts(buildProductDrafts(data.product_lines))
        setChargeDrafts(buildChargeDrafts(data.charge_lines))
        applySummary(seq, parentSeq, data.order_financial_summary)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setLoadError(apiErrorMessage(err))
      })
      .finally(() => {
        if (cancelled) return
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
    // applySummary is stable (only reads refs); orderId/reloadToken drive reload.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId, reloadToken])

  // Debounced product catalog search. Each run bumps the seq so any in-flight
  // request from an earlier keystroke is ignored when it resolves.
  useEffect(() => {
    if (!productPanelOpen || selectedProduct) return
    const q = productSearch.trim()
    const seq = ++productSearchSeqRef.current
    if (q === '') {
      setProductResults([])
      setProductSearching(false)
      setProductSearchError(null)
      return
    }
    setProductSearching(true)
    setProductSearchError(null)
    const handle = setTimeout(() => {
      fetchAvailableProducts(orderId, { search: q, page_size: 50 })
        .then((res) => {
          if (productSearchSeqRef.current === seq) setProductResults(res.data)
        })
        .catch((err: unknown) => {
          if (productSearchSeqRef.current !== seq) return
          setProductResults([])
          setProductSearchError(apiErrorMessage(err))
        })
        .finally(() => {
          if (productSearchSeqRef.current === seq) setProductSearching(false)
        })
    }, 250)
    return () => clearTimeout(handle)
  }, [productSearch, productPanelOpen, selectedProduct, orderId])

  // Debounced charge catalog search.
  useEffect(() => {
    if (!chargePanelOpen || selectedCharge) return
    const q = chargeSearch.trim()
    const seq = ++chargeSearchSeqRef.current
    if (q === '') {
      setChargeResults([])
      setChargeSearching(false)
      setChargeSearchError(null)
      return
    }
    setChargeSearching(true)
    setChargeSearchError(null)
    const handle = setTimeout(() => {
      fetchAvailableCharges(orderId, { search: q, page_size: 50 })
        .then((res) => {
          if (chargeSearchSeqRef.current === seq) setChargeResults(res.data)
        })
        .catch((err: unknown) => {
          if (chargeSearchSeqRef.current !== seq) return
          setChargeResults([])
          setChargeSearchError(apiErrorMessage(err))
        })
        .finally(() => {
          if (chargeSearchSeqRef.current === seq) setChargeSearching(false)
        })
    }, 250)
    return () => clearTimeout(handle)
  }, [chargeSearch, chargePanelOpen, selectedCharge, orderId])

  function addMutating(set: Set<number>, id: number): Set<number> {
    const next = new Set(set)
    next.add(id)
    return next
  }

  function removeMutating(set: Set<number>, id: number): Set<number> {
    const next = new Set(set)
    next.delete(id)
    return next
  }

  // --- Product line editing (PATCH on blur of a single changed field). ---

  function setProductDraft(id: number, patch: Partial<ProductRowDraft>) {
    setProductDrafts((prev) => {
      const current = prev[id]
      if (!current) return prev
      return { ...prev, [id]: { ...current, ...patch } }
    })
  }

  function handleProductLmChange(line: ProductLineRead, value: string) {
    setProductDraft(line.order_product_line_id, {
      quantity_lm: value,
      quantity_sqm: deriveSqmFromLm(value, line.sqm_per_lm_snapshot),
    })
  }

  function handleProductSqmChange(line: ProductLineRead, value: string) {
    setProductDraft(line.order_product_line_id, {
      quantity_sqm: value,
      quantity_lm: deriveLmFromSqm(value, line.sqm_per_lm_snapshot),
    })
  }

  function handleProductPriceChange(line: ProductLineRead, value: string) {
    setProductDraft(line.order_product_line_id, { unit_price: value })
  }

  async function commitProductField(
    line: ProductLineRead,
    field: 'quantity_lm' | 'quantity_sqm' | 'unit_price',
  ) {
    if (locked || mutatingProductIds.has(line.order_product_line_id)) return
    const draft = productDrafts[line.order_product_line_id]
    if (!draft) return
    const raw =
      field === 'unit_price'
        ? draft.unit_price
        : field === 'quantity_lm'
          ? draft.quantity_lm
          : draft.quantity_sqm
    const persisted =
      field === 'unit_price'
        ? line.unit_price
        : field === 'quantity_lm'
          ? line.quantity_lm
          : line.quantity_sqm
    const parsed = parseNumber(raw)
    // Invalid / non-positive: discard the edit and surface a hint. The backend
    // requires every line numeric to be > 0, so we never send it.
    if (parsed === null || parsed <= 0) {
      setProductDrafts((prev) => ({
        ...prev,
        [line.order_product_line_id]: productDraftFrom(line),
      }))
      setMutationError('Quantity and unit price must be greater than 0.')
      return
    }
    // Unchanged: normalise the draft formatting, send nothing (no empty PATCH).
    if (round2(parsed) === round2(persisted)) {
      setProductDrafts((prev) => ({
        ...prev,
        [line.order_product_line_id]: productDraftFrom(line),
      }))
      return
    }
    const body: PatchProductLineRequest =
      field === 'unit_price'
        ? { unit_price: parsed }
        : field === 'quantity_lm'
          ? { quantity_lm: parsed }
          : { quantity_sqm: parsed }

    setMutationError(null)
    setMutatingProductIds((prev) => addMutating(prev, line.order_product_line_id))
    const seq = ++reqSeqRef.current
    const parentSeq = beginRequestRef.current()
    try {
      const res = await updateProductLine(
        orderId,
        line.order_product_line_id,
        body,
      )
      const updated = res.data.product_line
      // Lift the summary to the header first (runs even if the tab unmounted).
      applySummary(seq, parentSeq, res.data.order_financial_summary)
      if (!mountedRef.current) return
      setProductLines((prev) =>
        prev.map((l) =>
          l.order_product_line_id === updated.order_product_line_id
            ? updated
            : l,
        ),
      )
      setProductDrafts((prev) => ({
        ...prev,
        [updated.order_product_line_id]: productDraftFrom(updated),
      }))
    } catch (err) {
      if (!mountedRef.current) return
      setMutationError(apiErrorMessage(err))
      // Revert the draft to the last server-confirmed values.
      setProductDrafts((prev) => ({
        ...prev,
        [line.order_product_line_id]: productDraftFrom(line),
      }))
    } finally {
      if (mountedRef.current) {
        setMutatingProductIds((prev) =>
          removeMutating(prev, line.order_product_line_id),
        )
      }
    }
  }

  async function handleRemoveProduct(line: ProductLineRead) {
    if (locked || mutatingProductIds.has(line.order_product_line_id)) return
    setMutationError(null)
    setMutatingProductIds((prev) => addMutating(prev, line.order_product_line_id))
    const seq = ++reqSeqRef.current
    const parentSeq = beginRequestRef.current()
    try {
      const res = await deleteProductLine(orderId, line.order_product_line_id)
      const removedId = res.data.deleted_product_line_id
      // Lift the summary to the header first (runs even if the tab unmounted).
      applySummary(seq, parentSeq, res.data.order_financial_summary)
      if (!mountedRef.current) return
      setProductLines((prev) =>
        prev.filter((l) => l.order_product_line_id !== removedId),
      )
      setProductDrafts((prev) => {
        const next = { ...prev }
        delete next[removedId]
        return next
      })
    } catch (err) {
      if (!mountedRef.current) return
      setMutationError(apiErrorMessage(err))
    } finally {
      if (mountedRef.current) {
        setMutatingProductIds((prev) =>
          removeMutating(prev, line.order_product_line_id),
        )
      }
    }
  }

  // --- Charge line editing. ---

  function setChargeDraft(id: number, patch: Partial<ChargeRowDraft>) {
    setChargeDrafts((prev) => {
      const current = prev[id]
      if (!current) return prev
      return { ...prev, [id]: { ...current, ...patch } }
    })
  }

  function handleChargeQtyChange(line: ChargeLineRead, value: string) {
    setChargeDraft(line.order_charge_line_id, { quantity: value })
  }

  function handleChargePriceChange(line: ChargeLineRead, value: string) {
    setChargeDraft(line.order_charge_line_id, { unit_price: value })
  }

  async function commitChargeField(
    line: ChargeLineRead,
    field: 'quantity' | 'unit_price',
  ) {
    if (locked || mutatingChargeIds.has(line.order_charge_line_id)) return
    const draft = chargeDrafts[line.order_charge_line_id]
    if (!draft) return
    const raw = field === 'quantity' ? draft.quantity : draft.unit_price
    const persisted = field === 'quantity' ? line.quantity : line.unit_price
    const parsed = parseNumber(raw)
    if (parsed === null || parsed <= 0) {
      setChargeDrafts((prev) => ({
        ...prev,
        [line.order_charge_line_id]: chargeDraftFrom(line),
      }))
      setMutationError('Quantity and unit price must be greater than 0.')
      return
    }
    if (round2(parsed) === round2(persisted)) {
      setChargeDrafts((prev) => ({
        ...prev,
        [line.order_charge_line_id]: chargeDraftFrom(line),
      }))
      return
    }
    const body: PatchChargeLineRequest =
      field === 'quantity' ? { quantity: parsed } : { unit_price: parsed }

    setMutationError(null)
    setMutatingChargeIds((prev) => addMutating(prev, line.order_charge_line_id))
    const seq = ++reqSeqRef.current
    const parentSeq = beginRequestRef.current()
    try {
      const res = await updateChargeLine(
        orderId,
        line.order_charge_line_id,
        body,
      )
      const updated = res.data.charge_line
      // Lift the summary to the header first (runs even if the tab unmounted).
      applySummary(seq, parentSeq, res.data.order_financial_summary)
      if (!mountedRef.current) return
      setChargeLines((prev) =>
        prev.map((l) =>
          l.order_charge_line_id === updated.order_charge_line_id ? updated : l,
        ),
      )
      setChargeDrafts((prev) => ({
        ...prev,
        [updated.order_charge_line_id]: chargeDraftFrom(updated),
      }))
    } catch (err) {
      if (!mountedRef.current) return
      setMutationError(apiErrorMessage(err))
      setChargeDrafts((prev) => ({
        ...prev,
        [line.order_charge_line_id]: chargeDraftFrom(line),
      }))
    } finally {
      if (mountedRef.current) {
        setMutatingChargeIds((prev) =>
          removeMutating(prev, line.order_charge_line_id),
        )
      }
    }
  }

  async function handleRemoveCharge(line: ChargeLineRead) {
    if (locked || mutatingChargeIds.has(line.order_charge_line_id)) return
    setMutationError(null)
    setMutatingChargeIds((prev) => addMutating(prev, line.order_charge_line_id))
    const seq = ++reqSeqRef.current
    const parentSeq = beginRequestRef.current()
    try {
      const res = await deleteChargeLine(orderId, line.order_charge_line_id)
      const removedId = res.data.deleted_charge_line_id
      // Lift the summary to the header first (runs even if the tab unmounted).
      applySummary(seq, parentSeq, res.data.order_financial_summary)
      if (!mountedRef.current) return
      setChargeLines((prev) =>
        prev.filter((l) => l.order_charge_line_id !== removedId),
      )
      setChargeDrafts((prev) => {
        const next = { ...prev }
        delete next[removedId]
        return next
      })
    } catch (err) {
      if (!mountedRef.current) return
      setMutationError(apiErrorMessage(err))
    } finally {
      if (mountedRef.current) {
        setMutatingChargeIds((prev) =>
          removeMutating(prev, line.order_charge_line_id),
        )
      }
    }
  }

  // --- Product add panel. ---

  function handlePanelLm(value: string) {
    setProductPanelLastEdited('LM')
    setProductPanelLm(value)
    setProductPanelSqm(
      selectedProduct ? deriveSqmFromLm(value, selectedProduct.sqm_per_lm) : '',
    )
  }

  function handlePanelSqm(value: string) {
    setProductPanelLastEdited('SQM')
    setProductPanelSqm(value)
    setProductPanelLm(
      selectedProduct ? deriveLmFromSqm(value, selectedProduct.sqm_per_lm) : '',
    )
  }

  function selectProduct(entry: AvailableProduct) {
    setSelectedProduct(entry)
    setProductSearch('')
    setProductResults([])
    setProductSearchError(null)
    setProductPanelLm('')
    setProductPanelSqm('')
    setProductPanelPrice(toFixed2(entry.price))
    setProductPanelLastEdited(entry.pricing_unit === 'SQM' ? 'SQM' : 'LM')
    setAddProductError(null)
  }

  function clearProductSelection() {
    setSelectedProduct(null)
    setProductPanelLm('')
    setProductPanelSqm('')
    setProductPanelPrice('')
    setAddProductError(null)
  }

  function resetProductPanel() {
    setProductSearch('')
    setProductResults([])
    setProductSearchError(null)
    setSelectedProduct(null)
    setProductPanelLm('')
    setProductPanelSqm('')
    setProductPanelPrice('')
    setProductPanelLastEdited('LM')
    setAddProductError(null)
  }

  // Decide which single quantity to send: the field the user last edited if it
  // is valid, otherwise whichever field holds a valid (> 0) value.
  function chooseProductQuantity(): {
    field: 'quantity_lm' | 'quantity_sqm'
    value: number
  } | null {
    const lm = parseNumber(productPanelLm)
    const sqm = parseNumber(productPanelSqm)
    const lmValid = lm !== null && lm > 0
    const sqmValid = sqm !== null && sqm > 0
    if (productPanelLastEdited === 'SQM' && sqmValid) {
      return { field: 'quantity_sqm', value: sqm as number }
    }
    if (productPanelLastEdited === 'LM' && lmValid) {
      return { field: 'quantity_lm', value: lm as number }
    }
    if (lmValid) return { field: 'quantity_lm', value: lm as number }
    if (sqmValid) return { field: 'quantity_sqm', value: sqm as number }
    return null
  }

  async function handleAddProductLine() {
    if (!selectedProduct || locked || addingProduct) return
    const quantity = chooseProductQuantity()
    if (!quantity) {
      setAddProductError('Enter an LM or SQM quantity greater than 0.')
      return
    }
    const price = parseNumber(productPanelPrice)
    // Reject a blank / invalid / non-positive override instead of silently
    // omitting it (which the backend would treat as "use the catalog price").
    if (price === null || price <= 0) {
      setAddProductError('Enter a unit price greater than 0.')
      return
    }
    const body: AddProductLineRequest = {
      product_id: selectedProduct.product_id,
      [quantity.field]: quantity.value,
      unit_price: price,
    }

    setAddingProduct(true)
    setAddProductError(null)
    setMutationError(null)
    const seq = ++reqSeqRef.current
    const parentSeq = beginRequestRef.current()
    try {
      const res = await addProductLine(orderId, body)
      const line = res.data.product_line
      // Lift the summary to the header first (runs even if the tab unmounted).
      applySummary(seq, parentSeq, res.data.order_financial_summary)
      if (!mountedRef.current) return
      setProductLines((prev) => [...prev, line])
      setProductDrafts((prev) => ({
        ...prev,
        [line.order_product_line_id]: productDraftFrom(line),
      }))
      resetProductPanel()
      setProductPanelOpen(false)
    } catch (err) {
      if (!mountedRef.current) return
      setAddProductError(apiErrorMessage(err))
    } finally {
      if (mountedRef.current) setAddingProduct(false)
    }
  }

  function handleCancelProductPanel() {
    resetProductPanel()
    setProductPanelOpen(false)
  }

  // --- Charge add panel. ---

  function selectCharge(entry: AvailableCharge) {
    setSelectedCharge(entry)
    setChargeSearch('')
    setChargeResults([])
    setChargeSearchError(null)
    setChargePanelQty('')
    setChargePanelPrice(toFixed2(entry.price))
    setAddChargeError(null)
  }

  function clearChargeSelection() {
    setSelectedCharge(null)
    setChargePanelQty('')
    setChargePanelPrice('')
    setAddChargeError(null)
  }

  function resetChargePanel() {
    setChargeSearch('')
    setChargeResults([])
    setChargeSearchError(null)
    setSelectedCharge(null)
    setChargePanelQty('')
    setChargePanelPrice('')
    setAddChargeError(null)
  }

  async function handleAddChargeLine() {
    if (!selectedCharge || locked || addingCharge) return
    const qty = parseNumber(chargePanelQty)
    if (qty === null || qty <= 0) {
      setAddChargeError('Enter a quantity greater than 0.')
      return
    }
    const price = parseNumber(chargePanelPrice)
    // Reject a blank / invalid / non-positive override instead of silently
    // omitting it (which the backend would treat as "use the catalog price").
    if (price === null || price <= 0) {
      setAddChargeError('Enter a unit price greater than 0.')
      return
    }
    const body: AddChargeLineRequest = {
      charge_id: selectedCharge.charge_id,
      quantity: qty,
      unit_price: price,
    }

    setAddingCharge(true)
    setAddChargeError(null)
    setMutationError(null)
    const seq = ++reqSeqRef.current
    const parentSeq = beginRequestRef.current()
    try {
      const res = await addChargeLine(orderId, body)
      const line = res.data.charge_line
      // Lift the summary to the header first (runs even if the tab unmounted).
      applySummary(seq, parentSeq, res.data.order_financial_summary)
      if (!mountedRef.current) return
      setChargeLines((prev) => [...prev, line])
      setChargeDrafts((prev) => ({
        ...prev,
        [line.order_charge_line_id]: chargeDraftFrom(line),
      }))
      resetChargePanel()
      setChargePanelOpen(false)
    } catch (err) {
      if (!mountedRef.current) return
      setAddChargeError(apiErrorMessage(err))
    } finally {
      if (mountedRef.current) setAddingCharge(false)
    }
  }

  function handleCancelChargePanel() {
    resetChargePanel()
    setChargePanelOpen(false)
  }

  if (loading) {
    return (
      <div>
        <Header />
        <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-10 text-center">
          <p className="text-sm text-slate-500">Loading products &amp; charges…</p>
        </div>
      </div>
    )
  }

  if (loadError !== null) {
    return (
      <div>
        <Header />
        <div className="rounded-lg border border-red-300 bg-red-50 px-4 py-6 text-center space-y-3">
          <p className="text-sm text-red-800">{loadError}</p>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => setReloadToken((token) => token + 1)}
          >
            Try again
          </Button>
        </div>
      </div>
    )
  }

  const productSubtotal = summary ? summary.product_subtotal : 0
  const chargeSubtotal = summary ? summary.charge_subtotal : 0
  const productPanelPriceValue = parseNumber(productPanelPrice)
  const productPriceValid =
    productPanelPriceValue !== null && productPanelPriceValue > 0
  const canAddProduct =
    !!selectedProduct &&
    chooseProductQuantity() !== null &&
    productPriceValid &&
    !addingProduct
  const chargeQty = parseNumber(chargePanelQty)
  const chargePanelPriceValue = parseNumber(chargePanelPrice)
  const chargePriceValid =
    chargePanelPriceValue !== null && chargePanelPriceValue > 0
  const canAddCharge =
    !!selectedCharge &&
    chargeQty !== null &&
    chargeQty > 0 &&
    chargePriceValid &&
    !addingCharge

  return (
    <div>
      <Header />

      {mutationError && (
        <div className="mb-4 rounded-lg border border-red-300 bg-red-50 px-4 py-3 text-xs text-red-800">
          {mutationError}
        </div>
      )}

      {locked && (
        <div className="mb-4 rounded-lg border border-slate-200 bg-slate-50 px-4 py-2.5 text-xs text-slate-600">
          This order is laid and locked. Products and charges can be viewed but
          not edited.
        </div>
      )}

      <div className="space-y-5">
        <section className="rounded-xl border border-slate-200 bg-slate-50/60 p-5">
          <div className="flex items-center justify-between mb-3 gap-3">
            <div className="flex items-center gap-3">
              <h3 className="text-sm font-semibold text-slate-900">
                Product lines
              </h3>
              <span className="text-[11px] font-medium text-slate-500">
                {productLines.length}{' '}
                {productLines.length === 1 ? 'product' : 'products'}
              </span>
            </div>
            <Button
              type="button"
              variant="success"
              size="sm"
              onClick={() => setProductPanelOpen((p) => !p)}
              aria-expanded={productPanelOpen}
              disabled={locked}
            >
              Add product
              <ChevronDownIcon
                className={`w-3.5 h-3.5 transition-transform ${
                  productPanelOpen ? 'rotate-180' : ''
                }`}
              />
            </Button>
          </div>

          {productPanelOpen && !locked && (
            <div className="mb-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              {!selectedProduct ? (
                <>
                  <div className="relative mb-3">
                    <SearchIcon className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                    <input
                      type="text"
                      value={productSearch}
                      onChange={(e) => setProductSearch(e.target.value)}
                      placeholder="Search product code or name…"
                      className="w-full h-10 rounded-lg border border-slate-300 bg-white pl-9 pr-3 text-sm text-slate-900 placeholder:text-slate-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500"
                      autoFocus
                    />
                  </div>
                  {productSearchError ? (
                    <p className="text-xs text-red-700 px-1 py-2">
                      {productSearchError}
                    </p>
                  ) : productSearch.trim() === '' ? (
                    <p className="text-xs text-slate-500 px-1 py-2">
                      Start typing to search the {FLOORING_LABELS[flooringType]}{' '}
                      product catalogue.
                    </p>
                  ) : productSearching ? (
                    <p className="text-xs text-slate-500 px-1 py-2">Searching…</p>
                  ) : productResults.length === 0 ? (
                    <p className="text-xs text-slate-500 px-1 py-2">
                      No products match "{productSearch.trim()}".
                    </p>
                  ) : (
                    <ul className="rounded-md border border-slate-200 divide-y divide-slate-200 max-h-[220px] overflow-auto">
                      {productResults.map((entry) => (
                        <li key={entry.product_id}>
                          <button
                            type="button"
                            onClick={() => selectProduct(entry)}
                            className="w-full flex items-center justify-between gap-3 px-3 py-2 text-left hover:bg-slate-50 focus-visible:bg-slate-50 focus-visible:outline-none transition-colors"
                          >
                            <div className="min-w-0">
                              <div className="font-mono text-xs text-slate-700">
                                {entry.code}
                              </div>
                              <div className="text-sm text-slate-900 truncate">
                                {entry.name}
                              </div>
                            </div>
                            <div className="shrink-0 flex items-center gap-2 text-xs text-slate-600 tabular-nums">
                              <span className="font-mono text-slate-500">
                                {entry.pricing_unit}
                              </span>
                              <span>
                                {formatMoney(entry.price)} / {entry.pricing_unit}
                              </span>
                            </div>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              ) : (
                <>
                  <div className="mb-3 rounded-md border border-slate-200 bg-slate-50 px-3 py-3 flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <div className="font-mono text-xs text-slate-700">
                        {selectedProduct.code}
                      </div>
                      <div className="text-sm font-medium text-slate-900 truncate">
                        {selectedProduct.name}
                      </div>
                      <div className="mt-0.5 text-[11px] text-slate-500">
                        Priced per{' '}
                        <span className="font-mono">
                          {selectedProduct.pricing_unit}
                        </span>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={clearProductSelection}
                      className="shrink-0 text-[11px] font-medium text-indigo-600 hover:text-indigo-700"
                    >
                      Change
                    </button>
                  </div>
                  <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                    <div>
                      <label className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                        LM
                      </label>
                      <input
                        type="text"
                        inputMode="decimal"
                        value={productPanelLm}
                        onChange={(e) => handlePanelLm(e.target.value)}
                        placeholder="0.00"
                        className={PANEL_INPUT_CLASS}
                        disabled={addingProduct}
                      />
                    </div>
                    <div>
                      <label className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                        SQM
                      </label>
                      <input
                        type="text"
                        inputMode="decimal"
                        value={productPanelSqm}
                        onChange={(e) => handlePanelSqm(e.target.value)}
                        placeholder="0.00"
                        className={PANEL_INPUT_CLASS}
                        disabled={addingProduct}
                      />
                    </div>
                    <div>
                      <label className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                        Unit price / {selectedProduct.pricing_unit}
                      </label>
                      <input
                        type="text"
                        inputMode="decimal"
                        value={productPanelPrice}
                        onChange={(e) => setProductPanelPrice(e.target.value)}
                        placeholder="0.00"
                        className={PANEL_INPUT_CLASS}
                        disabled={addingProduct}
                      />
                      {!productPriceValid && (
                        <p className="mt-1 text-[11px] text-red-700">
                          Enter a unit price greater than 0.
                        </p>
                      )}
                    </div>
                  </div>
                </>
              )}
              {addProductError && (
                <p className="mt-3 text-xs text-red-700">{addProductError}</p>
              )}
              <div className="mt-4 flex items-center justify-end gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={handleCancelProductPanel}
                  disabled={addingProduct}
                >
                  Cancel
                </Button>
                <Button
                  type="button"
                  variant="success"
                  size="sm"
                  onClick={handleAddProductLine}
                  disabled={!canAddProduct}
                >
                  {addingProduct ? 'Adding…' : 'Add line'}
                </Button>
              </div>
            </div>
          )}

          {productLines.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-6 text-center">
              <p className="text-sm text-slate-500">No products added yet.</p>
            </div>
          ) : (
            <>
              <div className="hidden lg:block overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
                <table className="w-full text-sm table-fixed">
                  <colgroup>
                    <col className="w-[11%]" />
                    <col className="w-[24%]" />
                    <col className="w-[13%]" />
                    <col className="w-[13%]" />
                    <col className="w-[15%]" />
                    <col className="w-[17%]" />
                    <col className="w-[7%]" />
                  </colgroup>
                  <thead>
                    <tr className="bg-slate-100 text-[11px] uppercase tracking-wider text-slate-600">
                      <th className="text-left font-semibold px-3 py-2.5">
                        Code
                      </th>
                      <th className="text-left font-semibold px-3 py-2.5">
                        Name
                      </th>
                      <th className="text-right font-semibold px-3 py-2.5">LM</th>
                      <th className="text-right font-semibold px-3 py-2.5">
                        SQM
                      </th>
                      <th className="text-right font-semibold px-3 py-2.5">
                        Unit price
                      </th>
                      <th className="text-right font-semibold px-3 py-2.5">
                        Line total
                      </th>
                      <th className="px-3 py-2.5" aria-label="Actions" />
                    </tr>
                  </thead>
                  <tbody>
                    {productLines.map((line) => {
                      const draft =
                        productDrafts[line.order_product_line_id] ??
                        productDraftFrom(line)
                      const rowBusy =
                        locked ||
                        mutatingProductIds.has(line.order_product_line_id)
                      return (
                        <tr
                          key={line.order_product_line_id}
                          className="border-t border-slate-200"
                        >
                          <td className="px-3 py-2 whitespace-nowrap">
                            <span className="font-mono text-xs text-slate-700">
                              {line.product_code_snapshot}
                            </span>
                          </td>
                          <td className="px-3 py-2">
                            <span
                              className="block text-sm text-slate-900 truncate"
                              title={line.product_name_snapshot}
                            >
                              {line.product_name_snapshot}
                            </span>
                          </td>
                          <td className="px-3 py-2">
                            <input
                              type="text"
                              inputMode="decimal"
                              value={draft.quantity_lm}
                              onChange={(e) =>
                                handleProductLmChange(line, e.target.value)
                              }
                              onBlur={() =>
                                commitProductField(line, 'quantity_lm')
                              }
                              disabled={rowBusy}
                              className={CELL_INPUT_CLASS}
                              aria-label={`${line.product_code_snapshot} LM quantity`}
                            />
                          </td>
                          <td className="px-3 py-2">
                            <input
                              type="text"
                              inputMode="decimal"
                              value={draft.quantity_sqm}
                              onChange={(e) =>
                                handleProductSqmChange(line, e.target.value)
                              }
                              onBlur={() =>
                                commitProductField(line, 'quantity_sqm')
                              }
                              disabled={rowBusy}
                              className={CELL_INPUT_CLASS}
                              aria-label={`${line.product_code_snapshot} SQM quantity`}
                            />
                          </td>
                          <td className="px-3 py-2">
                            <div className="flex items-center gap-1">
                              <input
                                type="text"
                                inputMode="decimal"
                                value={draft.unit_price}
                                onChange={(e) =>
                                  handleProductPriceChange(line, e.target.value)
                                }
                                onBlur={() =>
                                  commitProductField(line, 'unit_price')
                                }
                                disabled={rowBusy}
                                className={CELL_INPUT_CLASS}
                                aria-label={`${line.product_code_snapshot} unit price`}
                              />
                              <span className="font-mono text-[10px] text-slate-500 whitespace-nowrap">
                                /{line.pricing_unit_snapshot}
                              </span>
                            </div>
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums font-semibold text-slate-900 whitespace-nowrap">
                            {formatMoney(line.line_total)}
                          </td>
                          <td className="px-2 py-2 text-right">
                            <button
                              type="button"
                              onClick={() => handleRemoveProduct(line)}
                              disabled={rowBusy}
                              aria-label={`Remove ${line.product_code_snapshot}`}
                              className={REMOVE_BUTTON_CLASS}
                            >
                              <TrashIcon className="w-4 h-4" />
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>

              <ul className="lg:hidden space-y-2">
                {productLines.map((line) => {
                  const draft =
                    productDrafts[line.order_product_line_id] ??
                    productDraftFrom(line)
                  const rowBusy =
                    locked || mutatingProductIds.has(line.order_product_line_id)
                  return (
                    <li
                      key={line.order_product_line_id}
                      className="rounded-lg border border-slate-200 bg-white shadow-sm px-4 py-3 space-y-2"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0 flex flex-wrap items-center gap-2">
                          <span className="font-mono text-xs text-slate-600">
                            {line.product_code_snapshot}
                          </span>
                          <span
                            className="text-sm font-medium text-slate-900 truncate"
                            title={line.product_name_snapshot}
                          >
                            {line.product_name_snapshot}
                          </span>
                        </div>
                        <div className="shrink-0 flex items-center gap-1">
                          <div className="text-sm font-semibold tabular-nums text-slate-900">
                            {formatMoney(line.line_total)}
                          </div>
                          <button
                            type="button"
                            onClick={() => handleRemoveProduct(line)}
                            disabled={rowBusy}
                            aria-label={`Remove ${line.product_code_snapshot}`}
                            className={REMOVE_BUTTON_CLASS}
                          >
                            <TrashIcon className="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                      <div className="grid grid-cols-3 gap-2">
                        <div>
                          <label className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                            LM
                          </label>
                          <input
                            type="text"
                            inputMode="decimal"
                            value={draft.quantity_lm}
                            onChange={(e) =>
                              handleProductLmChange(line, e.target.value)
                            }
                            onBlur={() => commitProductField(line, 'quantity_lm')}
                            disabled={rowBusy}
                            className={CARD_INPUT_CLASS}
                          />
                        </div>
                        <div>
                          <label className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                            SQM
                          </label>
                          <input
                            type="text"
                            inputMode="decimal"
                            value={draft.quantity_sqm}
                            onChange={(e) =>
                              handleProductSqmChange(line, e.target.value)
                            }
                            onBlur={() =>
                              commitProductField(line, 'quantity_sqm')
                            }
                            disabled={rowBusy}
                            className={CARD_INPUT_CLASS}
                          />
                        </div>
                        <div>
                          <label className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                            Unit price / {line.pricing_unit_snapshot}
                          </label>
                          <input
                            type="text"
                            inputMode="decimal"
                            value={draft.unit_price}
                            onChange={(e) =>
                              handleProductPriceChange(line, e.target.value)
                            }
                            onBlur={() => commitProductField(line, 'unit_price')}
                            disabled={rowBusy}
                            className={CARD_INPUT_CLASS}
                          />
                        </div>
                      </div>
                    </li>
                  )
                })}
              </ul>
            </>
          )}

          {productLines.length > 0 && (
            <div className="mt-3 text-right text-sm text-slate-700">
              Product subtotal:{' '}
              <span className="ml-1 font-semibold tabular-nums text-slate-900">
                {formatMoney(productSubtotal)}
              </span>
            </div>
          )}
        </section>

        <section className="rounded-xl border border-slate-200 bg-slate-50/60 p-5">
          <div className="flex items-center justify-between mb-3 gap-3">
            <div className="flex items-center gap-3">
              <h3 className="text-sm font-semibold text-slate-900">
                Charge lines
              </h3>
              <span className="text-[11px] font-medium text-slate-500">
                {chargeLines.length}{' '}
                {chargeLines.length === 1 ? 'charge' : 'charges'}
              </span>
            </div>
            <Button
              type="button"
              variant="success"
              size="sm"
              onClick={() => setChargePanelOpen((p) => !p)}
              aria-expanded={chargePanelOpen}
              disabled={locked}
            >
              Add charge
              <ChevronDownIcon
                className={`w-3.5 h-3.5 transition-transform ${
                  chargePanelOpen ? 'rotate-180' : ''
                }`}
              />
            </Button>
          </div>

          {chargePanelOpen && !locked && (
            <div className="mb-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              {!selectedCharge ? (
                <>
                  <div className="relative mb-3">
                    <SearchIcon className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                    <input
                      type="text"
                      value={chargeSearch}
                      onChange={(e) => setChargeSearch(e.target.value)}
                      placeholder="Search charge code or name…"
                      className="w-full h-10 rounded-lg border border-slate-300 bg-white pl-9 pr-3 text-sm text-slate-900 placeholder:text-slate-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500"
                      autoFocus
                    />
                  </div>
                  {chargeSearchError ? (
                    <p className="text-xs text-red-700 px-1 py-2">
                      {chargeSearchError}
                    </p>
                  ) : chargeSearch.trim() === '' ? (
                    <p className="text-xs text-slate-500 px-1 py-2">
                      Start typing to search the charge catalogue.
                    </p>
                  ) : chargeSearching ? (
                    <p className="text-xs text-slate-500 px-1 py-2">Searching…</p>
                  ) : chargeResults.length === 0 ? (
                    <p className="text-xs text-slate-500 px-1 py-2">
                      No charges match "{chargeSearch.trim()}".
                    </p>
                  ) : (
                    <ul className="rounded-md border border-slate-200 divide-y divide-slate-200 max-h-[220px] overflow-auto">
                      {chargeResults.map((entry) => (
                        <li key={entry.charge_id}>
                          <button
                            type="button"
                            onClick={() => selectCharge(entry)}
                            className="w-full flex items-center justify-between gap-3 px-3 py-2 text-left hover:bg-slate-50 focus-visible:bg-slate-50 focus-visible:outline-none transition-colors"
                          >
                            <div className="min-w-0">
                              <div className="font-mono text-xs text-slate-700">
                                {entry.code}
                              </div>
                              <div className="text-sm text-slate-900 truncate">
                                {entry.name}
                              </div>
                            </div>
                            <div className="shrink-0 text-xs text-slate-600 tabular-nums">
                              {formatMoney(entry.price)}
                            </div>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              ) : (
                <>
                  <div className="mb-3 rounded-md border border-slate-200 bg-slate-50 px-3 py-3 flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <div className="font-mono text-xs text-slate-700">
                        {selectedCharge.code}
                      </div>
                      <div className="text-sm font-medium text-slate-900 truncate">
                        {selectedCharge.name}
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={clearChargeSelection}
                      className="shrink-0 text-[11px] font-medium text-indigo-600 hover:text-indigo-700"
                    >
                      Change
                    </button>
                  </div>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                        Quantity
                      </label>
                      <input
                        type="text"
                        inputMode="decimal"
                        value={chargePanelQty}
                        onChange={(e) => setChargePanelQty(e.target.value)}
                        placeholder="0.00"
                        className={PANEL_INPUT_CLASS}
                        disabled={addingCharge}
                      />
                    </div>
                    <div>
                      <label className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                        Unit price
                      </label>
                      <input
                        type="text"
                        inputMode="decimal"
                        value={chargePanelPrice}
                        onChange={(e) => setChargePanelPrice(e.target.value)}
                        placeholder="0.00"
                        className={PANEL_INPUT_CLASS}
                        disabled={addingCharge}
                      />
                      {!chargePriceValid && (
                        <p className="mt-1 text-[11px] text-red-700">
                          Enter a unit price greater than 0.
                        </p>
                      )}
                    </div>
                  </div>
                </>
              )}
              {addChargeError && (
                <p className="mt-3 text-xs text-red-700">{addChargeError}</p>
              )}
              <div className="mt-4 flex items-center justify-end gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={handleCancelChargePanel}
                  disabled={addingCharge}
                >
                  Cancel
                </Button>
                <Button
                  type="button"
                  variant="success"
                  size="sm"
                  onClick={handleAddChargeLine}
                  disabled={!canAddCharge}
                >
                  {addingCharge ? 'Adding…' : 'Add charge'}
                </Button>
              </div>
            </div>
          )}

          {chargeLines.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-6 text-center">
              <p className="text-sm text-slate-500">No charges added yet.</p>
            </div>
          ) : (
            <>
              <div className="hidden lg:block overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
                <table className="w-full text-sm table-fixed">
                  <colgroup>
                    <col className="w-[11%]" />
                    <col className="w-[32%]" />
                    <col className="w-[16%]" />
                    <col className="w-[17%]" />
                    <col className="w-[17%]" />
                    <col className="w-[7%]" />
                  </colgroup>
                  <thead>
                    <tr className="bg-slate-100 text-[11px] uppercase tracking-wider text-slate-600">
                      <th className="text-left font-semibold px-3 py-2.5">
                        Code
                      </th>
                      <th className="text-left font-semibold px-3 py-2.5">
                        Name
                      </th>
                      <th className="text-right font-semibold px-3 py-2.5">
                        Quantity
                      </th>
                      <th className="text-right font-semibold px-3 py-2.5">
                        Unit price
                      </th>
                      <th className="text-right font-semibold px-3 py-2.5">
                        Line total
                      </th>
                      <th className="px-3 py-2.5" aria-label="Actions" />
                    </tr>
                  </thead>
                  <tbody>
                    {chargeLines.map((line) => {
                      const draft =
                        chargeDrafts[line.order_charge_line_id] ??
                        chargeDraftFrom(line)
                      const rowBusy =
                        locked ||
                        mutatingChargeIds.has(line.order_charge_line_id)
                      return (
                        <tr
                          key={line.order_charge_line_id}
                          className="border-t border-slate-200"
                        >
                          <td className="px-3 py-2 whitespace-nowrap">
                            <span className="font-mono text-xs text-slate-700">
                              {line.charge_code_snapshot}
                            </span>
                          </td>
                          <td className="px-3 py-2">
                            <span
                              className="block text-sm text-slate-900 truncate"
                              title={line.charge_name_snapshot}
                            >
                              {line.charge_name_snapshot}
                            </span>
                          </td>
                          <td className="px-3 py-2">
                            <input
                              type="text"
                              inputMode="decimal"
                              value={draft.quantity}
                              onChange={(e) =>
                                handleChargeQtyChange(line, e.target.value)
                              }
                              onBlur={() => commitChargeField(line, 'quantity')}
                              disabled={rowBusy}
                              className={CELL_INPUT_CLASS}
                              aria-label={`${line.charge_code_snapshot} quantity`}
                            />
                          </td>
                          <td className="px-3 py-2">
                            <input
                              type="text"
                              inputMode="decimal"
                              value={draft.unit_price}
                              onChange={(e) =>
                                handleChargePriceChange(line, e.target.value)
                              }
                              onBlur={() =>
                                commitChargeField(line, 'unit_price')
                              }
                              disabled={rowBusy}
                              className={CELL_INPUT_CLASS}
                              aria-label={`${line.charge_code_snapshot} unit price`}
                            />
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums font-semibold text-slate-900 whitespace-nowrap">
                            {formatMoney(line.line_total)}
                          </td>
                          <td className="px-2 py-2 text-right">
                            <button
                              type="button"
                              onClick={() => handleRemoveCharge(line)}
                              disabled={rowBusy}
                              aria-label={`Remove ${line.charge_code_snapshot}`}
                              className={REMOVE_BUTTON_CLASS}
                            >
                              <TrashIcon className="w-4 h-4" />
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>

              <ul className="lg:hidden space-y-2">
                {chargeLines.map((line) => {
                  const draft =
                    chargeDrafts[line.order_charge_line_id] ??
                    chargeDraftFrom(line)
                  const rowBusy =
                    locked || mutatingChargeIds.has(line.order_charge_line_id)
                  return (
                    <li
                      key={line.order_charge_line_id}
                      className="rounded-lg border border-slate-200 bg-white shadow-sm px-4 py-3 space-y-2"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0 flex flex-wrap items-center gap-2">
                          <span className="font-mono text-xs text-slate-600">
                            {line.charge_code_snapshot}
                          </span>
                          <span
                            className="text-sm font-medium text-slate-900 truncate"
                            title={line.charge_name_snapshot}
                          >
                            {line.charge_name_snapshot}
                          </span>
                        </div>
                        <div className="shrink-0 flex items-center gap-1">
                          <div className="text-sm font-semibold tabular-nums text-slate-900">
                            {formatMoney(line.line_total)}
                          </div>
                          <button
                            type="button"
                            onClick={() => handleRemoveCharge(line)}
                            disabled={rowBusy}
                            aria-label={`Remove ${line.charge_code_snapshot}`}
                            className={REMOVE_BUTTON_CLASS}
                          >
                            <TrashIcon className="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                      <div className="grid grid-cols-2 gap-2">
                        <div>
                          <label className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                            Quantity
                          </label>
                          <input
                            type="text"
                            inputMode="decimal"
                            value={draft.quantity}
                            onChange={(e) =>
                              handleChargeQtyChange(line, e.target.value)
                            }
                            onBlur={() => commitChargeField(line, 'quantity')}
                            disabled={rowBusy}
                            className={CARD_INPUT_CLASS}
                          />
                        </div>
                        <div>
                          <label className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                            Unit price
                          </label>
                          <input
                            type="text"
                            inputMode="decimal"
                            value={draft.unit_price}
                            onChange={(e) =>
                              handleChargePriceChange(line, e.target.value)
                            }
                            onBlur={() => commitChargeField(line, 'unit_price')}
                            disabled={rowBusy}
                            className={CARD_INPUT_CLASS}
                          />
                        </div>
                      </div>
                    </li>
                  )
                })}
              </ul>
            </>
          )}

          {chargeLines.length > 0 && (
            <div className="mt-3 text-right text-sm text-slate-700">
              Charge subtotal:{' '}
              <span className="ml-1 font-semibold tabular-nums text-slate-900">
                {formatMoney(chargeSubtotal)}
              </span>
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

function Header() {
  return (
    <div className="mb-5">
      <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
        Products &amp; Charges
      </h2>
      <p className="text-sm text-slate-500 mt-1">
        Search the catalogue, add flooring products and charges, and set
        quantities and pricing for this order.
      </p>
    </div>
  )
}
