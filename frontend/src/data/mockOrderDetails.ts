import type { FlooringType } from '../lib/flooring'
import type { OrderStatus } from '../lib/statuses'

export type CustomerDetails = {
  first_name: string
  middle_name: string | null
  last_name: string
  email: string
  mobile: string
  home_phone: string | null
  work_phone: string | null
  company_name: string | null
}

export type Address = {
  unit_number: string | null
  street_number: string
  street: string
  suburb: string
  state_code: string
  postcode: string
}

export type LayDateStatus = 'CONFIRMED' | 'TO_BE_CONFIRMED'

export type SaleDetails = {
  supply_only: boolean
  plan_numbers: string | null
  proposed_lay_date: string | null
  lay_date_status: LayDateStatus | null
  details_of_sale: string | null
}

export type OrderNote = {
  order_note_id: number
  note_text: string
  created_at: string
}

export type OrderAttachment = {
  order_attachment_id: number
  attachment_kind: 'PHOTO'
  file_name: string
  mime_type: string
  file_size: number
  created_at: string
}

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

export type InvoiceSummary = {
  invoice_total: number
  total_paid: number
  balance_due: number
  current_version: number | null
}

export type InvoiceVersion = {
  invoice_id: number
  version_number: number
  invoice_date: string
  due_date: string
  sale_price_inc_gst: number
  total_paid: number
  balance_due: number
  pdf_filename: string
  details_of_sale_snapshot: string
  created_at: string
}

export type OrderDetails = {
  order_id: number
  order_number: string
  flooring_type: FlooringType
  order_status: OrderStatus
  week_number: number
  week_year: number
  customer: CustomerDetails | null
  installation_address: Address | null
  billing_address: Address | null
  sale_details: SaleDetails | null
  notes: OrderNote[]
  attachments: OrderAttachment[]
  payments: OrderPayment[]
  payment_summary: PaymentSummary
  invoice_summary: InvoiceSummary
  invoice_versions: InvoiceVersion[]
}

export const MOCK_ORDER_DETAILS: Record<number, OrderDetails> = {
  1: {
    order_id: 1,
    order_number: 'SYD-CBD.LC1.00001',
    flooring_type: 'SOFT',
    order_status: 'ACCEPTED',
    week_number: 15,
    week_year: 2026,
    customer: {
      first_name: 'James',
      middle_name: null,
      last_name: 'Wilson',
      email: 'james.wilson@email.com',
      mobile: '0412345678',
      home_phone: '0298765432',
      work_phone: null,
      company_name: null,
    },
    installation_address: {
      unit_number: null,
      street_number: '42',
      street: 'Oxford Street',
      suburb: 'Paddington',
      state_code: 'NSW',
      postcode: '2021',
    },
    billing_address: {
      unit_number: '3',
      street_number: '15',
      street: 'Pitt Street',
      suburb: 'Sydney',
      state_code: 'NSW',
      postcode: '2000',
    },
    sale_details: {
      supply_only: false,
      plan_numbers: 'PLN-4420',
      proposed_lay_date: '2026-05-01',
      lay_date_status: 'CONFIRMED',
      details_of_sale:
        'Supply and install plush carpet to lounge and dining rooms. Furniture to be moved by installer.',
    },
    notes: [
      {
        order_note_id: 3,
        note_text:
          'Plan numbers updated after on-site measure — confirmed PLN-4420.',
        created_at: '2026-04-18T15:20:00',
      },
      {
        order_note_id: 2,
        note_text:
          'Confirmed underlay choice on follow-up call — 10mm dual-density.',
        created_at: '2026-04-16T11:45:00',
      },
      {
        order_note_id: 1,
        note_text:
          'Customer prefers Saturday morning installation. Side gate access from rear lane.',
        created_at: '2026-04-14T10:05:00',
      },
    ],
    attachments: [
      {
        order_attachment_id: 1,
        attachment_kind: 'PHOTO',
        file_name: 'lounge-before.jpg',
        mime_type: 'image/jpeg',
        file_size: 2148576,
        created_at: '2026-04-14T09:42:00',
      },
      {
        order_attachment_id: 2,
        attachment_kind: 'PHOTO',
        file_name: 'dining-before.jpg',
        mime_type: 'image/jpeg',
        file_size: 1843200,
        created_at: '2026-04-14T09:45:00',
      },
      {
        order_attachment_id: 3,
        attachment_kind: 'PHOTO',
        file_name: 'skirting-detail.jpg',
        mime_type: 'image/jpeg',
        file_size: 952400,
        created_at: '2026-04-14T09:48:00',
      },
      {
        order_attachment_id: 4,
        attachment_kind: 'PHOTO',
        file_name: 'site-measure-plan.png',
        mime_type: 'image/png',
        file_size: 3140800,
        created_at: '2026-04-18T15:18:00',
      },
    ],
    payments: [
      {
        payment_transaction_id: 1,
        payment_method: 'EFTPOS',
        amount: 500.0,
        payment_reference: 'EFTPOS-20260414',
        created_at: '2026-04-14T11:05:00',
      },
    ],
    payment_summary: {
      total_paid: 500.0,
      balance_due: 424.0,
    },
    invoice_summary: {
      invoice_total: 924.0,
      total_paid: 500.0,
      balance_due: 424.0,
      current_version: 1,
    },
    invoice_versions: [
      {
        invoice_id: 1,
        version_number: 1,
        invoice_date: '2026-04-14',
        due_date: '2026-04-29',
        sale_price_inc_gst: 924.0,
        total_paid: 500.0,
        balance_due: 424.0,
        pdf_filename: 'invoice-SYD-CBD.LC1.00001-v1.pdf',
        details_of_sale_snapshot:
          'Supply and install plush carpet to lounge and dining rooms. Furniture to be moved by installer.',
        created_at: '2026-04-14T11:00:00',
      },
    ],
  },
  2: {
    order_id: 2,
    order_number: 'SYD-CBD.LC1.00002',
    flooring_type: 'HARD',
    order_status: 'LEAD',
    week_number: 15,
    week_year: 2026,
    customer: null,
    installation_address: null,
    billing_address: null,
    sale_details: null,
    notes: [],
    attachments: [],
    payments: [],
    payment_summary: { total_paid: 0.0, balance_due: null },
    invoice_summary: {
      invoice_total: 0.0,
      total_paid: 0.0,
      balance_due: 0.0,
      current_version: null,
    },
    invoice_versions: [],
  },
  3: {
    order_id: 3,
    order_number: 'SYD-CBD.SN1.00003',
    flooring_type: 'SOFT',
    order_status: 'FOLLOW_UP',
    week_number: 14,
    week_year: 2026,
    customer: null,
    installation_address: null,
    billing_address: null,
    sale_details: null,
    notes: [],
    attachments: [],
    payments: [],
    payment_summary: { total_paid: 0.0, balance_due: null },
    invoice_summary: {
      invoice_total: 0.0,
      total_paid: 0.0,
      balance_due: 0.0,
      current_version: null,
    },
    invoice_versions: [],
  },
  4: {
    order_id: 4,
    order_number: 'SYD-CBD.SN1.00004',
    flooring_type: 'HARD',
    order_status: 'NEW_ACHIEVED_SALE',
    week_number: 14,
    week_year: 2026,
    customer: null,
    installation_address: null,
    billing_address: null,
    sale_details: null,
    notes: [],
    attachments: [],
    payments: [],
    payment_summary: { total_paid: 0.0, balance_due: null },
    invoice_summary: {
      invoice_total: 0.0,
      total_paid: 0.0,
      balance_due: 0.0,
      current_version: null,
    },
    invoice_versions: [],
  },
}
