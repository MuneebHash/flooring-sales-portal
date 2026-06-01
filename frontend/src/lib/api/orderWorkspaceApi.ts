import type { FlooringType } from '../flooring'
import type { OrderStatus } from '../statuses'
import { DEFAULT_BUSINESS_SLUG } from '../tenant'
import { get, post } from './client'
import { apiPath } from './paths'
import type { ApiSuccess } from './types'

// Phase 10 Chunk 2 order shell / workspace contract.
//
// All field names are kept snake_case to mirror the backend JSON verbatim
// (the backend serializes with SNAKE_CASE), matching the convention used by
// ordersApi.ts. The full type surface (customer / address / details / save
// request + response shapes) is defined here so later Phase 10E sub-branches
// reuse it; only the create + read methods are wired in 10E.1.

// Lay date status enum — mirrors the backend sales_order.lay_date_status
// check constraint. These are the only two valid values.
export type LayDateStatus = 'CONFIRMED' | 'TO_BE_CONFIRMED'

// Nested customer object on the workspace read and the customer save
// request/response. Mirrors the backend CustomerDto. first_name / last_name /
// email / mobile are required at save time; the rest are nullable.
export type OrderCustomer = {
  first_name: string
  middle_name: string | null
  last_name: string
  email: string
  mobile: string
  home_phone: string | null
  work_phone: string | null
  company_name: string | null
}

// Installation / billing address. Mirrors the backend AddressDto. unit_number
// is the only optional field; the address_type is determined by the endpoint
// path, never the body.
export type OrderAddress = {
  unit_number: string | null
  street_number: string
  street: string
  suburb: string
  state_code: string
  postcode: string
}

// Non-financial details-of-sale fields. Mirrors the backend
// DetailsOfSaleFieldsDto and the matching inline fields on the workspace read.
// proposed_lay_date / lay_date_status are coupled (both set or both null).
export type DetailsOfSaleFields = {
  supply_only: boolean
  plan_numbers: string | null
  proposed_lay_date: string | null
  lay_date_status: LayDateStatus | null
  details_of_sale: string | null
}

// Persisted financial read-back scalars. Always present as an object on the
// workspace read, but every scalar is null until Chunk 3 populates them.
// Deferred — not rendered in Phase 10E.
export type PersistedFinancials = {
  sale_price_ex_gst: number | null
  total_cost: number | null
  gp: number | null
  gp_percent: number | null
}

// Order header — returned by POST /orders and embedded in the workspace read.
// Mirrors the backend OrderHeaderResponse. order_id is the internal numeric
// identifier used for routing and all order-scoped endpoints.
export type OrderHeader = {
  order_id: number
  order_number: string
  order_sequence_number: number
  flooring_type: FlooringType
  order_status: OrderStatus
  supply_only: boolean
  plan_numbers: string | null
  proposed_lay_date: string | null
  lay_date_status: LayDateStatus | null
  details_of_sale: string | null
  last_emailed_at: string | null
  week_year: number
  week_number: number
  created_at: string
  updated_at: string
  locked: boolean
}

// Full order workspace read (GET /orders/{orderId}). Mirrors the backend
// OrderWorkspaceResponse: the header fields plus nested customer / addresses
// (each null when the row does not exist yet) and the always-present
// persisted_financials object.
export type OrderWorkspace = OrderHeader & {
  customer: OrderCustomer | null
  install_address: OrderAddress | null
  billing_address: OrderAddress | null
  persisted_financials: PersistedFinancials
}

// --- Save request / response surface for later Phase 10E sub-branches. ---
// Defined now so the customer / address / details save wiring reuses these
// shapes. No save methods are implemented in 10E.1.

export type CreateOrderRequest = {
  flooring_type: FlooringType
}

// PUT /orders/{orderId}/customer — full-replace upsert. Body is the same field
// set as OrderCustomer; omitting an optional field clears it server-side.
export type CustomerSaveRequest = OrderCustomer
export type CustomerSaveResponse = {
  customer: OrderCustomer
}

// PUT /orders/{orderId}/addresses/{installation|billing} — full-replace upsert.
// Body is the same shape as OrderAddress; the path selects the address type.
export type AddressUpsertRequest = OrderAddress
export type InstallationAddressResponse = {
  install_address: OrderAddress
}
export type BillingAddressResponse = {
  billing_address: OrderAddress
}

// PUT /orders/{orderId}/details-of-sale — full-replace of the non-financial
// details fields.
export type DetailsOfSaleSaveRequest = DetailsOfSaleFields
export type DetailsOfSaleSaveResponse = {
  details_of_sale_fields: DetailsOfSaleFields
  updated_at: string
}

// POST /api/v1/{slug}/orders — create an order shell for the chosen flooring
// type. The backend assigns order_id, order_number, week and a LEAD status.
export function createOrder(
  flooringType: FlooringType,
): Promise<ApiSuccess<OrderHeader>> {
  const body: CreateOrderRequest = { flooring_type: flooringType }
  return post<ApiSuccess<OrderHeader>>(
    apiPath(DEFAULT_BUSINESS_SLUG, '/orders'),
    body,
  )
}

// GET /api/v1/{slug}/orders/{orderId} — read the full order workspace by its
// internal numeric order_id.
export function fetchOrderWorkspace(
  orderId: number,
): Promise<ApiSuccess<OrderWorkspace>> {
  return get<ApiSuccess<OrderWorkspace>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}`),
  )
}
