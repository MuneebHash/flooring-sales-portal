import type { Order } from '../../data/mockOrders'
import type {
  DashboardCustomer,
  DashboardInstallAddress,
  DashboardOrderRow,
} from './ordersApi'

const MONTH_NAMES = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
]

function formatCustomer(customer: DashboardCustomer | null): string {
  if (customer === null) return 'No customer yet'
  return `${customer.first_name} ${customer.last_name}`
}

function formatAddress(address: DashboardInstallAddress | null): string {
  if (address === null) return 'No install address'
  const head =
    address.unit_number !== null && address.unit_number.length > 0
      ? `${address.unit_number}/${address.street_number} ${address.street}`
      : `${address.street_number} ${address.street}`
  return `${head}, ${address.suburb} ${address.state_code} ${address.postcode}`
}

function formatGp(gp: number | null): string {
  if (gp === null) return '—'
  return `$${gp.toFixed(2)}`
}

function formatLastEmailed(timestamp: string | null): string {
  if (timestamp === null) return 'Not emailed'
  const datePart = timestamp.slice(0, 10)
  const [yearStr, monthStr, dayStr] = datePart.split('-')
  const year = Number(yearStr)
  const monthIndex = Number(monthStr) - 1
  const day = Number(dayStr)
  if (
    !Number.isFinite(year) ||
    !Number.isFinite(monthIndex) ||
    !Number.isFinite(day) ||
    monthIndex < 0 ||
    monthIndex > 11
  ) {
    return 'Not emailed'
  }
  return `${day} ${MONTH_NAMES[monthIndex]} ${year}`
}

export function apiRowToOrder(row: DashboardOrderRow): Order {
  return {
    order_id: row.order_id,
    order_number: row.order_number,
    flooring_type: row.flooring_type,
    status: row.order_status,
    customer: formatCustomer(row.customer),
    email: row.customer?.email ?? null,
    address: formatAddress(row.install_address),
    week: `${row.week_number} / ${row.week_year}`,
    gp: formatGp(row.gp),
    last_emailed: formatLastEmailed(row.last_emailed_at),
  }
}
