export type OrderStatus =
  | 'LEAD'
  | 'NEW_ACHIEVED_SALE'
  | 'FOLLOW_UP'
  | 'ACCEPTED'
  | 'LAID'
  | 'CANCELLED'

export const STATUS_LABELS: Record<OrderStatus, string> = {
  LEAD: 'Lead',
  NEW_ACHIEVED_SALE: 'New Sale',
  FOLLOW_UP: 'Follow Up',
  ACCEPTED: 'Accepted',
  LAID: 'Laid',
  CANCELLED: 'Cancelled',
}

export const STATUS_ORDER: OrderStatus[] = [
  'LEAD',
  'NEW_ACHIEVED_SALE',
  'FOLLOW_UP',
  'ACCEPTED',
  'LAID',
  'CANCELLED',
]

export const STATUS_STYLES: Record<OrderStatus, string> = {
  LEAD: 'bg-blue-50 text-blue-700 border-blue-200',
  NEW_ACHIEVED_SALE: 'bg-teal-50 text-teal-700 border-teal-200',
  FOLLOW_UP: 'bg-amber-50 text-amber-700 border-amber-200',
  ACCEPTED: 'bg-green-50 text-green-700 border-green-200',
  LAID: 'bg-indigo-50 text-indigo-700 border-indigo-200',
  CANCELLED: 'bg-red-50 text-red-700 border-red-200',
}
