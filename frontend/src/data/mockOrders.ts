import type { OrderStatus } from '../lib/statuses'
import type { FlooringType } from '../lib/flooring'

export type Order = {
  order_number: string
  flooring_type: FlooringType
  status: OrderStatus
  customer: string
  email: string | null
  address: string
  week: string
  gp: string
  gp_percent: number | null
  last_emailed: string
}

export const MOCK_ORDERS: Order[] = [
  {
    order_number: 'SYD-CBD.LC1.00001',
    flooring_type: 'SOFT',
    status: 'ACCEPTED',
    customer: 'James Wilson',
    email: 'james.wilson@email.com',
    address: '42 Oxford Street, Paddington NSW 2021',
    week: '15 / 2026',
    gp: '$408.00',
    gp_percent: 48.57,
    last_emailed: 'Not emailed',
  },
  {
    order_number: 'SYD-CBD.LC1.00002',
    flooring_type: 'HARD',
    status: 'LEAD',
    customer: 'No customer yet',
    email: null,
    address: 'No install address',
    week: '15 / 2026',
    gp: '—',
    gp_percent: null,
    last_emailed: 'Not emailed',
  },
  {
    order_number: 'SYD-CBD.SN1.00003',
    flooring_type: 'SOFT',
    status: 'FOLLOW_UP',
    customer: 'No customer yet',
    email: null,
    address: 'No install address',
    week: '14 / 2026',
    gp: '—',
    gp_percent: null,
    last_emailed: 'Not emailed',
  },
  {
    order_number: 'SYD-CBD.SN1.00004',
    flooring_type: 'HARD',
    status: 'NEW_ACHIEVED_SALE',
    customer: 'No customer yet',
    email: null,
    address: 'No install address',
    week: '14 / 2026',
    gp: '—',
    gp_percent: null,
    last_emailed: 'Not emailed',
  },
]
