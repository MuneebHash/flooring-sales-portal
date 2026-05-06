import { Modal } from './ui/Modal'
import { FLOORING_LABELS, type FlooringType } from '../lib/flooring'

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
  const handleSelect = (type: FlooringType) => {
    onSelect?.(type)
    onClose()
  }

  return (
    <Modal open={open} onClose={onClose} labelledBy="new-order-title">
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
              className={`flex items-center justify-center h-11 px-5 rounded-lg text-white text-sm font-semibold tracking-wide transition-all focus-visible:outline-none focus-visible:ring-2 ${OPTION_STYLES[type]}`}
            >
              {FLOORING_LABELS[type]}
            </button>
          ))}
        </div>
      </div>
    </Modal>
  )
}
