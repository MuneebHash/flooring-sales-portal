import type { FlooringType } from '../flooring'
import { DEFAULT_BUSINESS_SLUG } from '../tenant'
import { del, get, patch, post, put } from './client'
import { apiPath } from './paths'
import type { ApiCollection, ApiSuccess } from './types'

// Phase 11 Chunk 3 — products, charges, order lines and the live order
// financial summary. Field names are snake_case to mirror the backend JSON
// verbatim (the backend serializes with SNAKE_CASE), matching ordersApi.ts and
// orderWorkspaceApi.ts.
//
// Cost is strictly one-directional. The client NEVER sends any cost / *_snapshot
// / line_total / line_cost field (the request bodies below omit them entirely).
// cost_snapshot is RECEIVED read-only on the line read DTOs for salesperson
// visibility, but B7 does not display it. Per-line line_cost is never returned.

// Per-product pricing unit — mirrors store_product.pricing_unit.
export type PricingUnit = 'LM' | 'SQM'

// GET /orders/{orderId}/available-products row. Catalog search is cost-free —
// no cost field is ever returned here.
export type AvailableProduct = {
  product_id: number
  code: string
  name: string
  flooring_type: FlooringType
  pricing_unit: PricingUnit
  price: number
  sqm_per_lm: number
  stock_quantity: number | null
  stock_unit: string | null
}

// GET /orders/{orderId}/available-charges row. Cost-free; charges carry no
// pricing unit or stock.
export type AvailableCharge = {
  charge_id: number
  code: string
  name: string
  flooring_type: FlooringType
  price: number
}

// Already-added product line (GET /lines + product-line mutation responses).
// cost_snapshot and sqm_per_lm_snapshot are immutable snapshots taken at line
// creation. cost_snapshot is read-only and not displayed in this branch.
export type ProductLineRead = {
  order_product_line_id: number
  product_id: number
  product_code_snapshot: string
  product_name_snapshot: string
  pricing_unit_snapshot: PricingUnit
  price_snapshot: number
  cost_snapshot: number
  sqm_per_lm_snapshot: number
  quantity_lm: number
  quantity_sqm: number
  unit_price: number
  line_total: number
  created_at: string
  updated_at: string
}

// Already-added charge line. Charges use a single quantity (no LM/SQM, no
// pricing unit). cost_snapshot is read-only and not displayed in this branch.
export type ChargeLineRead = {
  order_charge_line_id: number
  charge_id: number
  charge_code_snapshot: string
  charge_name_snapshot: string
  price_snapshot: number
  cost_snapshot: number
  quantity: number
  unit_price: number
  line_total: number
  created_at: string
  updated_at: string
}

// Live order financial summary returned by GET /lines and every line mutation.
// price_adjustment_inc_gst and gp_percent are nullable; negatives are never
// clamped. GP / GP% / gp_warning are rendered in the Details of Sale tab (B8).
export type OrderFinancialSummary = {
  product_subtotal: number
  charge_subtotal: number
  calculated_total_inc_gst: number
  price_adjustment_inc_gst: number | null
  final_sale_price_inc_gst: number
  sale_price_ex_gst: number
  total_cost: number
  gp: number
  gp_percent: number | null
  gp_warning: boolean
}

// GET /orders/{orderId}/lines — all lines plus the live summary (not paginated).
export type OrderLinesResponse = {
  product_lines: ProductLineRead[]
  charge_lines: ChargeLineRead[]
  order_financial_summary: OrderFinancialSummary
}

// --- Request bodies. ---
// Product add: exactly one of quantity_lm / quantity_sqm; product PATCH: at most
// one. The backend derives the other quantity from sqm_per_lm_snapshot and owns
// line_total / line_cost. unit_price is an optional manual override.

export type AddProductLineRequest = {
  product_id: number
  quantity_lm?: number
  quantity_sqm?: number
  unit_price?: number
}

export type PatchProductLineRequest = {
  quantity_lm?: number
  quantity_sqm?: number
  unit_price?: number
}

export type ProductLineMutationResponse = {
  product_line: ProductLineRead
  order_financial_summary: OrderFinancialSummary
}

export type ProductLineDeleteResponse = {
  deleted_product_line_id: number
  order_financial_summary: OrderFinancialSummary
}

export type AddChargeLineRequest = {
  charge_id: number
  quantity: number
  unit_price?: number
}

export type PatchChargeLineRequest = {
  quantity?: number
  unit_price?: number
}

export type ChargeLineMutationResponse = {
  charge_line: ChargeLineRead
  order_financial_summary: OrderFinancialSummary
}

export type ChargeLineDeleteResponse = {
  deleted_charge_line_id: number
  order_financial_summary: OrderFinancialSummary
}

// Catalog search query. search is matched against code OR name; the order's
// flooring type is applied server-side and is never sent here.
export type CatalogSearchQuery = {
  search?: string
  page?: number
  page_size?: number
}

// GET /api/v1/{slug}/orders/{orderId}/available-products — catalog search,
// filtered to the order's flooring type server-side. Cost-free.
export function fetchAvailableProducts(
  orderId: number,
  query: CatalogSearchQuery = {},
): Promise<ApiCollection<AvailableProduct>> {
  return get<ApiCollection<AvailableProduct>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/available-products`),
    { query },
  )
}

// GET /api/v1/{slug}/orders/{orderId}/available-charges — catalog search,
// filtered to the order's flooring type server-side. Cost-free.
export function fetchAvailableCharges(
  orderId: number,
  query: CatalogSearchQuery = {},
): Promise<ApiCollection<AvailableCharge>> {
  return get<ApiCollection<AvailableCharge>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/available-charges`),
    { query },
  )
}

// GET /api/v1/{slug}/orders/{orderId}/lines — every product/charge line plus the
// live order_financial_summary. Allowed on LAID orders (read-only).
export function fetchOrderLines(
  orderId: number,
): Promise<ApiSuccess<OrderLinesResponse>> {
  return get<ApiSuccess<OrderLinesResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/lines`),
  )
}

// POST /api/v1/{slug}/orders/{orderId}/product-lines — add a product line. The
// response carries the created line plus the recomputed financial summary.
export function addProductLine(
  orderId: number,
  body: AddProductLineRequest,
): Promise<ApiSuccess<ProductLineMutationResponse>> {
  return post<ApiSuccess<ProductLineMutationResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/product-lines`),
    body,
  )
}

// PATCH /api/v1/{slug}/orders/{orderId}/product-lines/{lineId} — edit a product
// line's quantity (at most one of lm/sqm) and/or unit_price.
export function updateProductLine(
  orderId: number,
  lineId: number,
  body: PatchProductLineRequest,
): Promise<ApiSuccess<ProductLineMutationResponse>> {
  return patch<ApiSuccess<ProductLineMutationResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/product-lines/${lineId}`),
    body,
  )
}

// DELETE /api/v1/{slug}/orders/{orderId}/product-lines/{lineId}.
export function deleteProductLine(
  orderId: number,
  lineId: number,
): Promise<ApiSuccess<ProductLineDeleteResponse>> {
  return del<ApiSuccess<ProductLineDeleteResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/product-lines/${lineId}`),
  )
}

// POST /api/v1/{slug}/orders/{orderId}/charge-lines — add a charge line.
export function addChargeLine(
  orderId: number,
  body: AddChargeLineRequest,
): Promise<ApiSuccess<ChargeLineMutationResponse>> {
  return post<ApiSuccess<ChargeLineMutationResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/charge-lines`),
    body,
  )
}

// PATCH /api/v1/{slug}/orders/{orderId}/charge-lines/{lineId} — edit a charge
// line's quantity and/or unit_price.
export function updateChargeLine(
  orderId: number,
  lineId: number,
  body: PatchChargeLineRequest,
): Promise<ApiSuccess<ChargeLineMutationResponse>> {
  return patch<ApiSuccess<ChargeLineMutationResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/charge-lines/${lineId}`),
    body,
  )
}

// DELETE /api/v1/{slug}/orders/{orderId}/charge-lines/{lineId}.
export function deleteChargeLine(
  orderId: number,
  lineId: number,
): Promise<ApiSuccess<ChargeLineDeleteResponse>> {
  return del<ApiSuccess<ChargeLineDeleteResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/charge-lines/${lineId}`),
  )
}

// --- Sale price override / reset (Chunk 3 D.10 / D.11). ---
// The client sends ONLY the GST-inclusive final sale price; the backend derives
// and persists price_adjustment_inc_gst (never sent by the client). Both
// endpoints return the recomputed live order_financial_summary — the same shape
// the line endpoints return — and are blocked on LAID orders (422 ORDER_LOCKED).

export type SalePriceOverrideRequest = {
  final_sale_price_inc_gst: number
}

export type SalePriceMutationResponse = {
  order_financial_summary: OrderFinancialSummary
}

// PUT /api/v1/{slug}/orders/{orderId}/sale-price — manual GST-inclusive sale
// price override. final_sale_price_inc_gst must be > 0 and <= 99999999.99; the
// backend rounds extra decimals HALF_UP and derives the price adjustment.
export function overrideSalePrice(
  orderId: number,
  body: SalePriceOverrideRequest,
): Promise<ApiSuccess<SalePriceMutationResponse>> {
  return put<ApiSuccess<SalePriceMutationResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/sale-price`),
    body,
  )
}

// POST /api/v1/{slug}/orders/{orderId}/sale-price/reset — clear the manual
// adjustment (price_adjustment_inc_gst -> null). No request body; idempotent.
export function resetSalePrice(
  orderId: number,
): Promise<ApiSuccess<SalePriceMutationResponse>> {
  return post<ApiSuccess<SalePriceMutationResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/sale-price/reset`),
  )
}
