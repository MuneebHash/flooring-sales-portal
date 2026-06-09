// Workspace tab UI types that are NOT yet backed by an endpoint. Payments are
// wired in a later chunk; these shapes describe the local/prototype data the
// Payments tab renders until then. The Chunk 2 contract types (customer,
// address, details, header, financials) live in src/lib/api/orderWorkspaceApi.ts;
// the wired notes contract type (OrderNote) lives in src/lib/api/orderNotesApi.ts
// (Phase 11 Chunk 3, B9); the wired attachment contract type (OrderAttachment)
// lives in src/lib/api/orderAttachmentsApi.ts (Phase 11 Chunk 3, B10); the wired
// invoice contract type (InvoiceDetail) lives in src/lib/api/orderInvoicesApi.ts
// (Phase 12 Chunk 4).

export type PaymentMethod =
  | 'CASH'
  | 'CREDIT_CARD'
  | 'EFTPOS'
  | 'BANK_TRANSFER'

export type OrderPayment = {
  payment_transaction_id: number
  payment_method: PaymentMethod
  amount: number
  payment_reference: string | null
  created_at: string
}

export type PaymentSummary = {
  total_paid: number
  balance_due: number | null
}

export type PricingUnit = 'LM' | 'SQM'

export type ProductLine = {
  order_product_line_id: number
  product_code: string
  product_name: string
  pricing_unit: PricingUnit
  quantity_lm: number
  quantity_sqm: number
  unit_price: number
  line_total: number
}

export type ChargeLine = {
  order_charge_line_id: number
  charge_code: string
  charge_name: string
  quantity: number
  unit_price: number
  line_total: number
}
