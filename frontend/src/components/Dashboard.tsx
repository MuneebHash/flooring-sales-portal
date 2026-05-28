import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Badge } from './ui/Badge'
import { Button } from './ui/Button'
import { Panel } from './ui/Panel'
import { NewOrderModal } from './NewOrderModal'
import { StatusSelect } from './StatusSelect'
import {
  ChevronDownIcon,
  MailIcon,
  PlusIcon,
  SearchIcon,
} from './icons'
import type { OrderStatus } from '../lib/statuses'
import { STATUS_LABELS, STATUS_ORDER } from '../lib/statuses'
import { FLOORING_TONES } from '../lib/flooring'
import type { Order } from '../data/mockOrders'
import { ApiError } from '../lib/api/ApiError'
import {
  fetchDashboardOrders,
  updateOrderStatus,
} from '../lib/api/ordersApi'
import { apiRowToOrder } from '../lib/api/ordersAdapter'

type StatusFilter = OrderStatus | 'ALL'

export function Dashboard() {
  const navigate = useNavigate()
  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [statusError, setStatusError] = useState<string | null>(null)
  const [pendingStatusOrderIds, setPendingStatusOrderIds] = useState<
    Set<number>
  >(() => new Set())
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')
  const [newOrderOpen, setNewOrderOpen] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    fetchDashboardOrders()
      .then((response) => {
        if (cancelled) return
        setOrders(response.data.map(apiRowToOrder))
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const message =
          err instanceof ApiError && err.message.length > 0
            ? err.message
            : 'Could not load orders. Please try again.'
        setLoadError(message)
      })
      .finally(() => {
        if (cancelled) return
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return orders.filter((o) => {
      if (statusFilter !== 'ALL' && o.status !== statusFilter) return false
      if (!q) return true
      const haystack = [
        o.order_number,
        o.customer,
        o.email ?? '',
        o.address,
        o.flooring_type,
        STATUS_LABELS[o.status],
      ]
        .join(' ')
        .toLowerCase()
      return haystack.includes(q)
    })
  }, [orders, search, statusFilter])

  const handleStatusChange = async (
    orderId: number,
    current: OrderStatus,
    next: OrderStatus,
  ) => {
    if (next === current) return
    if (pendingStatusOrderIds.has(orderId)) return
    setStatusError(null)
    setPendingStatusOrderIds((prev) => new Set(prev).add(orderId))
    try {
      const response = await updateOrderStatus(orderId, next)
      const updated = response.data.order_status
      setOrders((prev) =>
        prev.map((o) =>
          o.order_id === orderId ? { ...o, status: updated } : o,
        ),
      )
    } catch (err) {
      const message =
        err instanceof ApiError && err.message.length > 0
          ? err.message
          : 'Could not update status. Please try again.'
      setStatusError(message)
    } finally {
      setPendingStatusOrderIds((prev) => {
        const nextPending = new Set(prev)
        nextPending.delete(orderId)
        return nextPending
      })
    }
  }

  return (
    <>
    <Panel className="p-6">
      <div className="flex items-center justify-between gap-4 mb-6">
        <div className="flex items-center gap-3">
          <h2 className="text-2xl font-bold text-slate-900 tracking-tight">
            Orders
          </h2>
          <span className="inline-flex items-center px-2.5 py-1 rounded-full bg-slate-100 text-slate-600 text-xs font-medium tabular-nums">
            {filtered.length} of {orders.length}
          </span>
        </div>
        <Button
          variant="success"
          size="md"
          onClick={() => setNewOrderOpen(true)}
        >
          <PlusIcon className="w-4 h-4" />
          New order
        </Button>
      </div>

      <div className="flex items-center gap-3 mb-5">
        <div className="relative flex-1">
          <SearchIcon
            aria-hidden="true"
            className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-slate-400 pointer-events-none"
          />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search orders by number, customer, email, address..."
            className="w-full h-12 rounded-xl border border-slate-200 bg-white pl-12 pr-4 text-sm text-slate-900 placeholder:text-slate-400 shadow-sm transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500"
          />
        </div>
        <div className="relative">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}
            className="h-12 rounded-xl border border-slate-200 bg-white pl-4 pr-10 text-sm font-medium text-slate-700 shadow-sm appearance-none cursor-pointer transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 focus-visible:border-indigo-500"
          >
            <option value="ALL">All statuses</option>
            {STATUS_ORDER.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <ChevronDownIcon
            aria-hidden="true"
            className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-500"
          />
        </div>
      </div>

      {statusError !== null ? (
        <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-2.5 text-sm text-red-700">
          {statusError}
        </div>
      ) : null}

      <div className="overflow-hidden border border-slate-200 rounded-xl">
        <table className="w-full table-fixed text-sm">
          <thead className="bg-slate-100 text-[11px] uppercase tracking-wider text-slate-600 border-b border-slate-200">
            <tr>
              <th className="text-left font-semibold px-4 py-2.5 w-[180px]">
                Order
              </th>
              <th className="text-left font-semibold px-4 py-2.5 w-[150px]">
                Customer
              </th>
              <th className="text-left font-semibold px-4 py-2.5">Address</th>
              <th className="text-left font-semibold px-4 py-2.5 w-[70px]">
                Week
              </th>
              <th className="text-left font-semibold px-4 py-2.5 w-[85px]">
                GP
              </th>
              <th className="text-left font-semibold px-4 py-2.5 w-[110px]">
                Last emailed
              </th>
              <th className="text-left font-semibold px-4 py-2.5 w-[180px]">
                Status
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center">
                  <span className="text-sm text-slate-500">
                    Loading orders…
                  </span>
                </td>
              </tr>
            ) : loadError !== null ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center">
                  <span className="text-sm text-red-600">{loadError}</span>
                </td>
              </tr>
            ) : filtered.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center">
                  <div className="inline-flex flex-col items-center gap-1">
                    <span className="text-sm text-slate-500">
                      {orders.length === 0
                        ? 'No orders yet.'
                        : 'No orders match your filters.'}
                    </span>
                    <span className="text-xs text-slate-400">
                      {orders.length === 0
                        ? 'New orders will appear here.'
                        : 'Try adjusting your search or status.'}
                    </span>
                  </div>
                </td>
              </tr>
            ) : (
              filtered.map((o) => {
                return (
                  <tr
                    key={o.order_id}
                    className="transition-colors hover:bg-slate-50"
                  >
                    <td className="px-4 py-3 align-top">
                      <div className="font-mono text-sm font-medium text-slate-900 whitespace-nowrap">
                        {o.order_number}
                      </div>
                      <div className="flex items-center gap-2 mt-1.5">
                        <Button
                          variant="success-outline"
                          size="sm"
                          onClick={() => navigate(`/orders/${o.order_id}`)}
                        >
                          Open
                        </Button>
                        <Badge tone={FLOORING_TONES[o.flooring_type]}>
                          {o.flooring_type}
                        </Badge>
                      </div>
                    </td>
                    <td className="px-4 py-3 align-top">
                      <div
                        className={`text-sm truncate ${o.customer === 'No customer yet' ? 'italic text-slate-400' : 'font-medium text-slate-900'}`}
                      >
                        {o.customer}
                      </div>
                      <div
                        className={`text-xs truncate mt-1 ${o.email ? 'text-blue-500' : 'text-slate-400'}`}
                      >
                        {o.email ?? '—'}
                      </div>
                    </td>
                    <td className="px-4 py-3 align-top">
                      <div
                        className={`text-sm leading-snug break-words ${o.address === 'No install address' ? 'italic text-slate-400' : 'text-slate-700'}`}
                      >
                        {o.address}
                      </div>
                    </td>
                    <td className="px-4 py-3 align-top text-sm text-slate-700 tabular-nums">
                      {o.week}
                    </td>
                    <td className="px-4 py-3 align-top">
                      <div className="text-sm font-medium text-slate-900 tabular-nums">
                        {o.gp}
                      </div>
                    </td>
                    <td className="px-4 py-3 align-top">
                      <div className="flex items-start gap-1.5 text-xs text-slate-500">
                        <MailIcon
                          aria-hidden="true"
                          className="w-4 h-4 mt-px text-slate-400 shrink-0"
                        />
                        <span className="break-words leading-snug">
                          {o.last_emailed}
                        </span>
                      </div>
                    </td>
                    <td className="px-4 py-3 align-top">
                      <StatusSelect
                        value={o.status}
                        disabled={pendingStatusOrderIds.has(o.order_id)}
                        onChange={(next) =>
                          handleStatusChange(o.order_id, o.status, next)
                        }
                      />
                    </td>
                  </tr>
                )
              })
            )}
          </tbody>
        </table>
      </div>
    </Panel>
    <NewOrderModal
      open={newOrderOpen}
      onClose={() => setNewOrderOpen(false)}
    />
    </>
  )
}
