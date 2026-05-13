import { useState } from 'react'
import { Button } from '../ui/Button'
import { ChevronDownIcon, SearchIcon, TrashIcon } from '../icons'
import type { FlooringType } from '../../lib/flooring'
import type {
  ChargeLine,
  PricingUnit,
  ProductLine,
} from '../../data/mockOrderDetails'

type Props = {
  flooringType: FlooringType
  productLines?: ProductLine[] | null
  chargeLines?: ChargeLine[] | null
}

type ProductDraft = {
  order_product_line_id: number
  product_code: string
  product_name: string
  pricing_unit: PricingUnit
  quantity_lm: string
  quantity_sqm: string
  unit_price: string
}

type ChargeDraft = {
  order_charge_line_id: number
  charge_code: string
  charge_name: string
  quantity: string
  unit_price: string
}

type ProductCatalogueEntry = {
  product_code: string
  product_name: string
  flooring_type: FlooringType
  pricing_unit: PricingUnit
  default_price: number
}

type ChargeCatalogueEntry = {
  charge_code: string
  charge_name: string
  default_price: number
}

const LM_TO_SQM = 3.66

const PRODUCT_CATALOGUE: ProductCatalogueEntry[] = [
  {
    product_code: 'PLU-001',
    product_name: 'Plush Carpet Premium',
    flooring_type: 'SOFT',
    pricing_unit: 'LM',
    default_price: 45.0,
  },
  {
    product_code: 'UND-010',
    product_name: 'Premium Foam Underlay 10mm',
    flooring_type: 'SOFT',
    pricing_unit: 'LM',
    default_price: 15.0,
  },
  {
    product_code: 'PLU-002',
    product_name: 'Wool Berber Carpet',
    flooring_type: 'SOFT',
    pricing_unit: 'LM',
    default_price: 32.0,
  },
  {
    product_code: 'VIN-020',
    product_name: 'Hybrid Vinyl Plank',
    flooring_type: 'HARD',
    pricing_unit: 'SQM',
    default_price: 58.0,
  },
  {
    product_code: 'TIM-030',
    product_name: 'Engineered Timber Oak',
    flooring_type: 'HARD',
    pricing_unit: 'SQM',
    default_price: 82.0,
  },
]

const CHARGE_CATALOGUE: ChargeCatalogueEntry[] = [
  {
    charge_code: 'INST-S',
    charge_name: 'Carpet Installation',
    default_price: 15.0,
  },
  {
    charge_code: 'DISP-S',
    charge_name: 'Removal & Disposal',
    default_price: 150.0,
  },
  {
    charge_code: 'FURN-MOVE',
    charge_name: 'Furniture Moving',
    default_price: 80.0,
  },
  {
    charge_code: 'FLOOR-PREP',
    charge_name: 'Floor Preparation',
    default_price: 120.0,
  },
]

const MONEY_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

function formatMoney(value: number): string {
  return MONEY_FORMATTER.format(value)
}

function parseDecimal(value: string): number {
  const n = parseFloat(value)
  return Number.isFinite(n) && n >= 0 ? n : 0
}

function round2(value: number): number {
  return Math.round(value * 100) / 100
}

function toFixed2(value: number): string {
  return value.toFixed(2)
}

function deriveSqmFromLm(lmString: string): string {
  const lm = parseDecimal(lmString)
  return lm > 0 ? toFixed2(round2(lm * LM_TO_SQM)) : ''
}

function deriveLmFromSqm(sqmString: string): string {
  const sqm = parseDecimal(sqmString)
  return sqm > 0 ? toFixed2(round2(sqm / LM_TO_SQM)) : ''
}

function computeProductSubtotal(line: ProductDraft): number {
  const qty =
    line.pricing_unit === 'LM'
      ? parseDecimal(line.quantity_lm)
      : parseDecimal(line.quantity_sqm)
  return round2(qty * parseDecimal(line.unit_price))
}

function computeChargeSubtotal(line: ChargeDraft): number {
  return round2(parseDecimal(line.quantity) * parseDecimal(line.unit_price))
}

function matchesQuery(query: string, code: string, name: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return false
  return code.toLowerCase().includes(q) || name.toLowerCase().includes(q)
}

const CELL_INPUT_CLASS =
  'w-full h-8 rounded-md border border-slate-300 bg-white px-2 text-sm text-slate-900 text-right tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500'

const CARD_INPUT_CLASS =
  'w-full h-9 mt-0.5 rounded-md border border-slate-300 bg-white px-2 text-sm text-slate-900 text-right tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500'

const PANEL_INPUT_CLASS =
  'w-full h-10 mt-1 rounded-md border border-slate-300 bg-white px-3 text-sm text-slate-900 placeholder:text-slate-400 tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500'

const REMOVE_BUTTON_CLASS =
  'inline-flex items-center justify-center w-7 h-7 rounded-md text-slate-400 hover:bg-rose-50 hover:text-rose-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500/30 transition-colors'

export function ProductsChargesTab({
  flooringType,
  productLines,
  chargeLines,
}: Props) {
  const [products, setProducts] = useState<ProductDraft[]>(() =>
    (productLines ?? []).map((p) => ({
      order_product_line_id: p.order_product_line_id,
      product_code: p.product_code,
      product_name: p.product_name,
      pricing_unit: p.pricing_unit,
      quantity_lm: toFixed2(p.quantity_lm),
      quantity_sqm: toFixed2(p.quantity_sqm),
      unit_price: toFixed2(p.unit_price),
    })),
  )

  const [charges, setCharges] = useState<ChargeDraft[]>(() =>
    (chargeLines ?? []).map((c) => ({
      order_charge_line_id: c.order_charge_line_id,
      charge_code: c.charge_code,
      charge_name: c.charge_name,
      quantity: toFixed2(c.quantity),
      unit_price: toFixed2(c.unit_price),
    })),
  )

  const [productPanelOpen, setProductPanelOpen] = useState(false)
  const [chargePanelOpen, setChargePanelOpen] = useState(false)

  const [productSearch, setProductSearch] = useState('')
  const [selectedProductCode, setSelectedProductCode] = useState<string | null>(
    null,
  )
  const [productPanelLm, setProductPanelLm] = useState('')
  const [productPanelSqm, setProductPanelSqm] = useState('')
  const [productPanelPrice, setProductPanelPrice] = useState('')

  const [chargeSearch, setChargeSearch] = useState('')
  const [selectedChargeCode, setSelectedChargeCode] = useState<string | null>(
    null,
  )
  const [chargePanelQty, setChargePanelQty] = useState('')
  const [chargePanelPrice, setChargePanelPrice] = useState('')

  const selectedProduct =
    PRODUCT_CATALOGUE.find((p) => p.product_code === selectedProductCode) ??
    null
  const selectedCharge =
    CHARGE_CATALOGUE.find((c) => c.charge_code === selectedChargeCode) ?? null

  const productResults = productSearch.trim()
    ? PRODUCT_CATALOGUE.filter(
        (p) =>
          p.flooring_type === flooringType &&
          matchesQuery(productSearch, p.product_code, p.product_name),
      )
    : []
  const chargeResults = chargeSearch.trim()
    ? CHARGE_CATALOGUE.filter((c) =>
        matchesQuery(chargeSearch, c.charge_code, c.charge_name),
      )
    : []

  const productSubtotal = round2(
    products.reduce((sum, l) => sum + computeProductSubtotal(l), 0),
  )
  const chargeSubtotal = round2(
    charges.reduce((sum, l) => sum + computeChargeSubtotal(l), 0),
  )

  function handleProductLm(id: number, value: string) {
    setProducts((prev) =>
      prev.map((line) =>
        line.order_product_line_id === id
          ? {
              ...line,
              quantity_lm: value,
              quantity_sqm: deriveSqmFromLm(value),
            }
          : line,
      ),
    )
  }

  function handleProductSqm(id: number, value: string) {
    setProducts((prev) =>
      prev.map((line) =>
        line.order_product_line_id === id
          ? {
              ...line,
              quantity_sqm: value,
              quantity_lm: deriveLmFromSqm(value),
            }
          : line,
      ),
    )
  }

  function handleProductPrice(id: number, value: string) {
    setProducts((prev) =>
      prev.map((line) =>
        line.order_product_line_id === id
          ? { ...line, unit_price: value }
          : line,
      ),
    )
  }

  function handleRemoveProduct(id: number) {
    setProducts((prev) =>
      prev.filter((line) => line.order_product_line_id !== id),
    )
  }

  function handleChargeQty(id: number, value: string) {
    setCharges((prev) =>
      prev.map((line) =>
        line.order_charge_line_id === id ? { ...line, quantity: value } : line,
      ),
    )
  }

  function handleChargePrice(id: number, value: string) {
    setCharges((prev) =>
      prev.map((line) =>
        line.order_charge_line_id === id
          ? { ...line, unit_price: value }
          : line,
      ),
    )
  }

  function handleRemoveCharge(id: number) {
    setCharges((prev) =>
      prev.filter((line) => line.order_charge_line_id !== id),
    )
  }

  function handlePanelLm(value: string) {
    setProductPanelLm(value)
    setProductPanelSqm(deriveSqmFromLm(value))
  }

  function handlePanelSqm(value: string) {
    setProductPanelSqm(value)
    setProductPanelLm(deriveLmFromSqm(value))
  }

  function selectProduct(entry: ProductCatalogueEntry) {
    setSelectedProductCode(entry.product_code)
    setProductSearch('')
    setProductPanelLm('')
    setProductPanelSqm('')
    setProductPanelPrice(toFixed2(entry.default_price))
  }

  function clearProductSelection() {
    setSelectedProductCode(null)
    setProductPanelLm('')
    setProductPanelSqm('')
    setProductPanelPrice('')
  }

  function resetProductPanel() {
    setProductSearch('')
    setSelectedProductCode(null)
    setProductPanelLm('')
    setProductPanelSqm('')
    setProductPanelPrice('')
  }

  function handleAddProductLine() {
    if (!selectedProduct) return
    const lm = parseDecimal(productPanelLm)
    const sqm = parseDecimal(productPanelSqm)
    const price = parseDecimal(productPanelPrice)
    const newLine: ProductDraft = {
      order_product_line_id: Date.now(),
      product_code: selectedProduct.product_code,
      product_name: selectedProduct.product_name,
      pricing_unit: selectedProduct.pricing_unit,
      quantity_lm: toFixed2(lm),
      quantity_sqm: toFixed2(sqm),
      unit_price: toFixed2(price),
    }
    setProducts((prev) => [...prev, newLine])
    resetProductPanel()
  }

  function handleCancelProductPanel() {
    resetProductPanel()
    setProductPanelOpen(false)
  }

  function selectCharge(entry: ChargeCatalogueEntry) {
    setSelectedChargeCode(entry.charge_code)
    setChargeSearch('')
    setChargePanelQty('')
    setChargePanelPrice(toFixed2(entry.default_price))
  }

  function clearChargeSelection() {
    setSelectedChargeCode(null)
    setChargePanelQty('')
    setChargePanelPrice('')
  }

  function resetChargePanel() {
    setChargeSearch('')
    setSelectedChargeCode(null)
    setChargePanelQty('')
    setChargePanelPrice('')
  }

  function handleAddChargeLine() {
    if (!selectedCharge) return
    const qty = parseDecimal(chargePanelQty)
    const price = parseDecimal(chargePanelPrice)
    const newLine: ChargeDraft = {
      order_charge_line_id: Date.now(),
      charge_code: selectedCharge.charge_code,
      charge_name: selectedCharge.charge_name,
      quantity: toFixed2(qty),
      unit_price: toFixed2(price),
    }
    setCharges((prev) => [...prev, newLine])
    resetChargePanel()
  }

  function handleCancelChargePanel() {
    resetChargePanel()
    setChargePanelOpen(false)
  }

  return (
    <div>
      <div className="mb-5">
        <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
          Products & Charges
        </h2>
        <p className="text-sm text-slate-500 mt-1">
          Add flooring products, charges, pricing, cost and gross profit for
          this order.
        </p>
        <p className="text-[11px] text-slate-500 mt-1.5">
          Detailed costing and gross-profit calculations are static in this
          prototype. Backend calculation logic will be wired later.
        </p>
      </div>

      <div className="space-y-5">
        <section className="rounded-xl border border-slate-200 bg-slate-50/60 p-5">
          <div className="flex items-center justify-between mb-3 gap-3">
            <div className="flex items-center gap-3">
              <h3 className="text-sm font-semibold text-slate-900">
                Product lines
              </h3>
              <span className="text-[11px] font-medium text-slate-500">
                {products.length}{' '}
                {products.length === 1 ? 'product' : 'products'}
              </span>
            </div>
            <Button
              type="button"
              variant="success"
              size="sm"
              onClick={() => setProductPanelOpen((p) => !p)}
              aria-expanded={productPanelOpen}
            >
              Add product
              <ChevronDownIcon
                className={`w-3.5 h-3.5 transition-transform ${
                  productPanelOpen ? 'rotate-180' : ''
                }`}
              />
            </Button>
          </div>

          {productPanelOpen && (
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
                  {productSearch.trim() === '' ? (
                    <p className="text-xs text-slate-500 px-1 py-2">
                      Start typing to search the product catalogue.
                    </p>
                  ) : productResults.length === 0 ? (
                    <p className="text-xs text-slate-500 px-1 py-2">
                      No products match "{productSearch.trim()}".
                    </p>
                  ) : (
                    <ul className="rounded-md border border-slate-200 divide-y divide-slate-200 max-h-[220px] overflow-auto">
                      {productResults.map((entry) => (
                        <li key={entry.product_code}>
                          <button
                            type="button"
                            onClick={() => selectProduct(entry)}
                            className="w-full flex items-center justify-between gap-3 px-3 py-2 text-left hover:bg-slate-50 focus-visible:bg-slate-50 focus-visible:outline-none transition-colors"
                          >
                            <div className="min-w-0">
                              <div className="font-mono text-xs text-slate-700">
                                {entry.product_code}
                              </div>
                              <div className="text-sm text-slate-900 truncate">
                                {entry.product_name}
                              </div>
                            </div>
                            <div className="shrink-0 flex items-center gap-2 text-xs text-slate-600 tabular-nums">
                              <span className="font-mono text-slate-500">
                                {entry.pricing_unit}
                              </span>
                              <span>
                                {formatMoney(entry.default_price)} /{' '}
                                {entry.pricing_unit}
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
                        {selectedProduct.product_code}
                      </div>
                      <div className="text-sm font-medium text-slate-900 truncate">
                        {selectedProduct.product_name}
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
                      />
                    </div>
                  </div>
                </>
              )}
              <div className="mt-4 flex items-center justify-end gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={handleCancelProductPanel}
                >
                  Cancel
                </Button>
                <Button
                  type="button"
                  variant="success"
                  size="sm"
                  onClick={handleAddProductLine}
                  disabled={!selectedProduct}
                >
                  Add line
                </Button>
              </div>
            </div>
          )}

          {products.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-6 text-center">
              <p className="text-sm text-slate-500">
                No products added yet.
              </p>
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
                      <th className="text-right font-semibold px-3 py-2.5">
                        LM
                      </th>
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
                    {products.map((line) => (
                      <tr
                        key={line.order_product_line_id}
                        className="border-t border-slate-200"
                      >
                        <td className="px-3 py-2 whitespace-nowrap">
                          <span className="font-mono text-xs text-slate-700">
                            {line.product_code}
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <span
                            className="block text-sm text-slate-900 truncate"
                            title={line.product_name}
                          >
                            {line.product_name}
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <input
                            type="text"
                            inputMode="decimal"
                            value={line.quantity_lm}
                            onChange={(e) =>
                              handleProductLm(
                                line.order_product_line_id,
                                e.target.value,
                              )
                            }
                            className={CELL_INPUT_CLASS}
                            aria-label={`${line.product_code} LM quantity`}
                          />
                        </td>
                        <td className="px-3 py-2">
                          <input
                            type="text"
                            inputMode="decimal"
                            value={line.quantity_sqm}
                            onChange={(e) =>
                              handleProductSqm(
                                line.order_product_line_id,
                                e.target.value,
                              )
                            }
                            className={CELL_INPUT_CLASS}
                            aria-label={`${line.product_code} SQM quantity`}
                          />
                        </td>
                        <td className="px-3 py-2">
                          <div className="flex items-center gap-1">
                            <input
                              type="text"
                              inputMode="decimal"
                              value={line.unit_price}
                              onChange={(e) =>
                                handleProductPrice(
                                  line.order_product_line_id,
                                  e.target.value,
                                )
                              }
                              className={CELL_INPUT_CLASS}
                              aria-label={`${line.product_code} unit price`}
                            />
                            <span className="font-mono text-[10px] text-slate-500 whitespace-nowrap">
                              /{line.pricing_unit}
                            </span>
                          </div>
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums font-semibold text-slate-900 whitespace-nowrap">
                          {formatMoney(computeProductSubtotal(line))}
                        </td>
                        <td className="px-2 py-2 text-right">
                          <button
                            type="button"
                            onClick={() =>
                              handleRemoveProduct(line.order_product_line_id)
                            }
                            aria-label={`Remove ${line.product_code}`}
                            className={REMOVE_BUTTON_CLASS}
                          >
                            <TrashIcon className="w-4 h-4" />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <ul className="lg:hidden space-y-2">
                {products.map((line) => (
                  <li
                    key={line.order_product_line_id}
                    className="rounded-lg border border-slate-200 bg-white shadow-sm px-4 py-3 space-y-2"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0 flex flex-wrap items-center gap-2">
                        <span className="font-mono text-xs text-slate-600">
                          {line.product_code}
                        </span>
                        <span
                          className="text-sm font-medium text-slate-900 truncate"
                          title={line.product_name}
                        >
                          {line.product_name}
                        </span>
                      </div>
                      <div className="shrink-0 flex items-center gap-1">
                        <div className="text-sm font-semibold tabular-nums text-slate-900">
                          {formatMoney(computeProductSubtotal(line))}
                        </div>
                        <button
                          type="button"
                          onClick={() =>
                            handleRemoveProduct(line.order_product_line_id)
                          }
                          aria-label={`Remove ${line.product_code}`}
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
                          value={line.quantity_lm}
                          onChange={(e) =>
                            handleProductLm(
                              line.order_product_line_id,
                              e.target.value,
                            )
                          }
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
                          value={line.quantity_sqm}
                          onChange={(e) =>
                            handleProductSqm(
                              line.order_product_line_id,
                              e.target.value,
                            )
                          }
                          className={CARD_INPUT_CLASS}
                        />
                      </div>
                      <div>
                        <label className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                          Unit price / {line.pricing_unit}
                        </label>
                        <input
                          type="text"
                          inputMode="decimal"
                          value={line.unit_price}
                          onChange={(e) =>
                            handleProductPrice(
                              line.order_product_line_id,
                              e.target.value,
                            )
                          }
                          className={CARD_INPUT_CLASS}
                        />
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}

          {products.length > 0 && (
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
                {charges.length}{' '}
                {charges.length === 1 ? 'charge' : 'charges'}
              </span>
            </div>
            <Button
              type="button"
              variant="success"
              size="sm"
              onClick={() => setChargePanelOpen((p) => !p)}
              aria-expanded={chargePanelOpen}
            >
              Add charge
              <ChevronDownIcon
                className={`w-3.5 h-3.5 transition-transform ${
                  chargePanelOpen ? 'rotate-180' : ''
                }`}
              />
            </Button>
          </div>

          {chargePanelOpen && (
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
                  {chargeSearch.trim() === '' ? (
                    <p className="text-xs text-slate-500 px-1 py-2">
                      Start typing to search the charge catalogue.
                    </p>
                  ) : chargeResults.length === 0 ? (
                    <p className="text-xs text-slate-500 px-1 py-2">
                      No charges match "{chargeSearch.trim()}".
                    </p>
                  ) : (
                    <ul className="rounded-md border border-slate-200 divide-y divide-slate-200 max-h-[220px] overflow-auto">
                      {chargeResults.map((entry) => (
                        <li key={entry.charge_code}>
                          <button
                            type="button"
                            onClick={() => selectCharge(entry)}
                            className="w-full flex items-center justify-between gap-3 px-3 py-2 text-left hover:bg-slate-50 focus-visible:bg-slate-50 focus-visible:outline-none transition-colors"
                          >
                            <div className="min-w-0">
                              <div className="font-mono text-xs text-slate-700">
                                {entry.charge_code}
                              </div>
                              <div className="text-sm text-slate-900 truncate">
                                {entry.charge_name}
                              </div>
                            </div>
                            <div className="shrink-0 text-xs text-slate-600 tabular-nums">
                              {formatMoney(entry.default_price)}
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
                        {selectedCharge.charge_code}
                      </div>
                      <div className="text-sm font-medium text-slate-900 truncate">
                        {selectedCharge.charge_name}
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
                      />
                    </div>
                  </div>
                </>
              )}
              <div className="mt-4 flex items-center justify-end gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={handleCancelChargePanel}
                >
                  Cancel
                </Button>
                <Button
                  type="button"
                  variant="success"
                  size="sm"
                  onClick={handleAddChargeLine}
                  disabled={!selectedCharge}
                >
                  Add charge
                </Button>
              </div>
            </div>
          )}

          {charges.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-6 text-center">
              <p className="text-sm text-slate-500">
                No charges added yet.
              </p>
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
                    {charges.map((line) => (
                      <tr
                        key={line.order_charge_line_id}
                        className="border-t border-slate-200"
                      >
                        <td className="px-3 py-2 whitespace-nowrap">
                          <span className="font-mono text-xs text-slate-700">
                            {line.charge_code}
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <span
                            className="block text-sm text-slate-900 truncate"
                            title={line.charge_name}
                          >
                            {line.charge_name}
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <input
                            type="text"
                            inputMode="decimal"
                            value={line.quantity}
                            onChange={(e) =>
                              handleChargeQty(
                                line.order_charge_line_id,
                                e.target.value,
                              )
                            }
                            className={CELL_INPUT_CLASS}
                            aria-label={`${line.charge_code} quantity`}
                          />
                        </td>
                        <td className="px-3 py-2">
                          <input
                            type="text"
                            inputMode="decimal"
                            value={line.unit_price}
                            onChange={(e) =>
                              handleChargePrice(
                                line.order_charge_line_id,
                                e.target.value,
                              )
                            }
                            className={CELL_INPUT_CLASS}
                            aria-label={`${line.charge_code} unit price`}
                          />
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums font-semibold text-slate-900 whitespace-nowrap">
                          {formatMoney(computeChargeSubtotal(line))}
                        </td>
                        <td className="px-2 py-2 text-right">
                          <button
                            type="button"
                            onClick={() =>
                              handleRemoveCharge(line.order_charge_line_id)
                            }
                            aria-label={`Remove ${line.charge_code}`}
                            className={REMOVE_BUTTON_CLASS}
                          >
                            <TrashIcon className="w-4 h-4" />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <ul className="lg:hidden space-y-2">
                {charges.map((line) => (
                  <li
                    key={line.order_charge_line_id}
                    className="rounded-lg border border-slate-200 bg-white shadow-sm px-4 py-3 space-y-2"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0 flex flex-wrap items-center gap-2">
                        <span className="font-mono text-xs text-slate-600">
                          {line.charge_code}
                        </span>
                        <span
                          className="text-sm font-medium text-slate-900 truncate"
                          title={line.charge_name}
                        >
                          {line.charge_name}
                        </span>
                      </div>
                      <div className="shrink-0 flex items-center gap-1">
                        <div className="text-sm font-semibold tabular-nums text-slate-900">
                          {formatMoney(computeChargeSubtotal(line))}
                        </div>
                        <button
                          type="button"
                          onClick={() =>
                            handleRemoveCharge(line.order_charge_line_id)
                          }
                          aria-label={`Remove ${line.charge_code}`}
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
                          value={line.quantity}
                          onChange={(e) =>
                            handleChargeQty(
                              line.order_charge_line_id,
                              e.target.value,
                            )
                          }
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
                          value={line.unit_price}
                          onChange={(e) =>
                            handleChargePrice(
                              line.order_charge_line_id,
                              e.target.value,
                            )
                          }
                          className={CARD_INPUT_CLASS}
                        />
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}

          {charges.length > 0 && (
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
