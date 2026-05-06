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
  },
}
