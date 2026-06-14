import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Modal } from './ui/Modal'
import { FLOORING_LABELS, type FlooringType } from '../lib/flooring'
import { ApiError } from '../lib/api/ApiError'
import { createOrder } from '../lib/api/orderWorkspaceApi'
import { useTenantSlug } from '../lib/useTenantSlug'

type Props = {
  open: boolean
  onClose: () => void
  onSelect?: (type: FlooringType) => void
}

const OPTIONS: FlooringType[] = ['SOFT', 'HARD']

const OPTION_STYLES: Record<FlooringType, string> = {
  SOFT: 'bg-gradient-to-b from-rose-400 to-rose-500 hover:from-rose-500 hover:to-rose-600 shadow-sm shadow-rose-900/20 focus-visible:ring-rose-400/50',
  HARD: 'bg-gradient-to-b from-emerald-400 to-emerald-500 hover:from-emerald-500 hover:to-emerald-600 shadow-sm shadow-emerald-900/20 focus-visible:ring-emerald-400/50',
}

export function NewOrderModal({ open, onClose, onSelect }: Props) {
  const navigate = useNavigate()
  const slug = useTenantSlug()
  const [pending, setPending] = useState<FlooringType | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Clear any stale error each time the modal is opened.
  useEffect(() => {
    if (open) setError(null)
  }, [open])

  const handleClose = () => {
    // Don't close mid-create — let the in-flight request resolve and navigate.
    if (pending !== null) return
    setError(null)
    onClose()
  }

  const handleSelect = async (type: FlooringType) => {
    if (pending !== null) return
    setError(null)
    setPending(type)
    try {
      const response = await createOrder(type)
      onSelect?.(type)
      onClose()
      navigate(`/${slug}/orders/${response.data.order_id}`)
    } catch (err) {
      const message =
        err instanceof ApiError && err.message.length > 0
          ? err.message
          : 'Could not create the order. Please try again.'
      setError(message)
    } finally {
      setPending(null)
    }
  }

  return (
    <Modal open={open} onClose={handleClose} labelledBy="new-order-title">
      <div className="p-6">
        <div className="mb-5">
          <h3
            id="new-order-title"
            className="text-lg font-semibold text-slate-900 tracking-tight"
          >
            Start new order
          </h3>
          <p className="text-sm text-slate-500 mt-1">
            Choose the flooring type for this order.
          </p>
        </div>

        <div className="grid grid-cols-2 gap-3">
          {OPTIONS.map((type) => (
            <button
              key={type}
              type="button"
              onClick={() => handleSelect(type)}
              disabled={pending !== null}
              className={`flex items-center justify-center h-11 px-5 rounded-lg text-white text-sm font-semibold tracking-wide transition-all focus-visible:outline-none focus-visible:ring-2 disabled:opacity-60 disabled:cursor-not-allowed ${OPTION_STYLES[type]}`}
            >
              {pending === type ? 'Creating…' : FLOORING_LABELS[type]}
            </button>
          ))}
        </div>

        {error !== null && (
          <p className="mt-4 text-sm text-red-600" role="alert">
            {error}
          </p>
        )}
      </div>
    </Modal>
  )
}
