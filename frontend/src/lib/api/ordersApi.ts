import type { FlooringType } from '../flooring'
import type { OrderStatus } from '../statuses'
import { getActiveSlug } from '../tenant'
import { get, patch } from './client'
import { apiPath } from './paths'
import type { ApiCollection, ApiSuccess } from './types'

export type DashboardCustomer = {
  first_name: string
  last_name: string
  email: string
}

export type DashboardInstallAddress = {
  unit_number: string | null
  street_number: string
  street: string
  suburb: string
  state_code: string
  postcode: string
}

export type DashboardOrderRow = {
  order_id: number
  order_number: string
  order_sequence_number: number
  flooring_type: FlooringType
  order_status: OrderStatus
  customer: DashboardCustomer | null
  install_address: DashboardInstallAddress | null
  last_emailed_at: string | null
  week_year: number
  week_number: number
  gp: number | null
}

export type OrderStatusUpdateResponse = {
  order_id: number
  order_number: string
  order_status: OrderStatus
  previous_order_status: OrderStatus
  locked: boolean
  updated_at: string
}

export function fetchDashboardOrders(): Promise<
  ApiCollection<DashboardOrderRow>
> {
  return get<ApiCollection<DashboardOrderRow>>(
    apiPath(getActiveSlug(), '/orders'),
    { query: { page: 1, page_size: 100 } },
  )
}

export function updateOrderStatus(
  orderId: number,
  nextStatus: OrderStatus,
): Promise<ApiSuccess<OrderStatusUpdateResponse>> {
  return patch<ApiSuccess<OrderStatusUpdateResponse>>(
    apiPath(getActiveSlug(), `/orders/${orderId}/status`),
    { order_status: nextStatus },
  )
}
