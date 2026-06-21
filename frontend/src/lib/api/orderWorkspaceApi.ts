import type { FlooringType } from '../flooring'
import type { OrderStatus } from '../statuses'
import { getActiveSlug } from '../tenant'
import { get, post, put } from './client'
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

// Phase 15F lead enquiry form — the one-per-order order_enquiry row, embedded as
// the nullable `enquiry` object on the workspace read (null when no row saved yet)
// and the body/response of PUT /orders/{orderId}/enquiry. Mirrors the backend
// EnquiryDto (19 data fields, snake_case).
//
// carpet / hard_floor are the products the customer is looking for — independent
// booleans (both/neither may be true) and deliberately decoupled from the order's
// SOFT/HARD flooring_type. subfloor_* are independent multi-select booleans.
// fully_installed / uplift / furniture / stairs are TRI-STATE: true = Yes,
// false = No, null = unanswered — the null must round-trip, never collapse to false.
// lead_type is a SINGLE choice (or null = unanswered).

// How the lead came in. Single-select; null = unanswered. Mirrors the backend
// order_enquiry.lead_type CHECK (FLOOR / PHONE / INTERNET or NULL).
export type LeadType = 'FLOOR' | 'PHONE' | 'INTERNET'

export type OrderEnquiry = {
  lead_type: LeadType | null
  carpet: boolean
  hard_floor: boolean
  product_fit_timing: string | null
  product_usage_context: string | null
  rooms_to_cover: string | null
  textured_or_plain: string | null
  light_or_dark_tones: string | null
  colours_interested: string | null
  current_flooring_and_reason: string | null
  products_discussed: string | null
  fully_installed: boolean | null
  uplift: boolean | null
  furniture: boolean | null
  stairs: boolean | null
  subfloor_concrete: boolean
  subfloor_timber: boolean
  subfloor_tile: boolean
  brief_summary: string | null
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
// OrderWorkspaceResponse: the header fields plus the order-bound salesperson_name,
// nested customer / addresses (each null when the row does not exist yet) and the
// always-present persisted_financials object.
//
// salesperson_name (Phase 15C PR2) is order-bound (sales_order.user_id ->
// app_user first_name + last_name), the same source the invoice PDF uses — NOT the
// session user. It lives ONLY on the workspace read, never on OrderHeader (the POST
// create shell). Null when the user row is missing.
export type OrderWorkspace = OrderHeader & {
  salesperson_name: string | null
  customer: OrderCustomer | null
  install_address: OrderAddress | null
  billing_address: OrderAddress | null
  persisted_financials: PersistedFinancials
  // Phase 15F: nullable when no enquiry row has been saved for this order yet.
  enquiry: OrderEnquiry | null
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

// PUT /orders/{orderId}/enquiry — full-replace upsert of the one order_enquiry
// row. Body is the complete OrderEnquiry field set; omitted text clears to null,
// omitted NOT NULL flags default false, omitted tri-state fields become null
// (unanswered). The enquiry is READ back via the workspace GET (R1) — there is no
// standalone GET enquiry endpoint.
export type EnquirySaveRequest = OrderEnquiry
export type EnquirySaveResponse = {
  enquiry: OrderEnquiry
}

// POST /api/v1/{slug}/orders — create an order shell for the chosen flooring
// type. The backend assigns order_id, order_number, week and a LEAD status.
export function createOrder(
  flooringType: FlooringType,
): Promise<ApiSuccess<OrderHeader>> {
  const body: CreateOrderRequest = { flooring_type: flooringType }
  return post<ApiSuccess<OrderHeader>>(
    apiPath(getActiveSlug(), '/orders'),
    body,
  )
}

// GET /api/v1/{slug}/orders/{orderId} — read the full order workspace by its
// internal numeric order_id.
export function fetchOrderWorkspace(
  orderId: number,
): Promise<ApiSuccess<OrderWorkspace>> {
  return get<ApiSuccess<OrderWorkspace>>(
    apiPath(getActiveSlug(), `/orders/${orderId}`),
  )
}

// PUT /api/v1/{slug}/orders/{orderId}/customer — full-replace upsert of the one
// customer row. Optional fields omitted/null are cleared server-side, so the
// caller must send the complete field set (see CustomerSaveRequest).
export function saveCustomer(
  orderId: number,
  body: CustomerSaveRequest,
): Promise<ApiSuccess<CustomerSaveResponse>> {
  return put<ApiSuccess<CustomerSaveResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/customer`),
    body,
  )
}

// PUT /api/v1/{slug}/orders/{orderId}/addresses/installation — full-replace
// upsert of the installation address row. The path selects address_type.
export function saveInstallationAddress(
  orderId: number,
  body: AddressUpsertRequest,
): Promise<ApiSuccess<InstallationAddressResponse>> {
  return put<ApiSuccess<InstallationAddressResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/addresses/installation`),
    body,
  )
}

// PUT /api/v1/{slug}/orders/{orderId}/addresses/billing — full-replace upsert of
// the billing address row. The path selects address_type.
export function saveBillingAddress(
  orderId: number,
  body: AddressUpsertRequest,
): Promise<ApiSuccess<BillingAddressResponse>> {
  return put<ApiSuccess<BillingAddressResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/addresses/billing`),
    body,
  )
}

// POST /api/v1/{slug}/orders/{orderId}/addresses/billing/copy-from-installation
// — backend copies the persisted installation row into billing. No request body.
// Returns 422 INSTALLATION_ADDRESS_REQUIRED when no installation row exists, so
// the caller must save the installation address first in the same run.
export function copyBillingFromInstallation(
  orderId: number,
): Promise<ApiSuccess<BillingAddressResponse>> {
  return post<ApiSuccess<BillingAddressResponse>>(
    apiPath(
      getActiveSlug(),
      `/orders/${orderId}/addresses/billing/copy-from-installation`,
    ),
  )
}

// PUT /api/v1/{slug}/orders/{orderId}/details-of-sale — full-replace of the five
// non-line, non-payment details-of-sale fields on sales_order. Optional fields
// sent as null are cleared server-side, so the caller must send the complete
// field set (see DetailsOfSaleSaveRequest). The response returns the
// server-normalised fields plus the refreshed updated_at — no financial summary.
export function saveDetailsOfSale(
  orderId: number,
  body: DetailsOfSaleSaveRequest,
): Promise<ApiSuccess<DetailsOfSaleSaveResponse>> {
  return put<ApiSuccess<DetailsOfSaleSaveResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/details-of-sale`),
    body,
  )
}

// PUT /api/v1/{slug}/orders/{orderId}/enquiry — full-replace upsert of the one
// lead-enquiry row (Phase 15F). The caller sends the complete OrderEnquiry field
// set (see EnquirySaveRequest); the response returns the server-normalised row.
export function saveEnquiry(
  orderId: number,
  body: EnquirySaveRequest,
): Promise<ApiSuccess<EnquirySaveResponse>> {
  return put<ApiSuccess<EnquirySaveResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/enquiry`),
    body,
  )
}
