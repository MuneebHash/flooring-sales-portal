import type { OrderStatus } from '../lib/statuses'
import { STATUS_LABELS, STATUS_ORDER, STATUS_STYLES } from '../lib/statuses'
import { ChevronDownIcon } from './icons'

type Props = {
  value: OrderStatus
  onChange: (next: OrderStatus) => void
  disabled?: boolean
}

export function StatusSelect({ value, onChange, disabled = false }: Props) {
  return (
    <div className="relative inline-block">
      <select
        value={value}
        onChange={(e) => onChange(e.target.value as OrderStatus)}
        disabled={disabled}
        className={`h-7 rounded-md border pl-2.5 pr-7 text-xs font-medium appearance-none transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 ${STATUS_STYLES[value]} ${disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:opacity-90'}`}
      >
        {STATUS_ORDER.map((s) => (
          <option key={s} value={s} className="text-slate-900 bg-white">
            {STATUS_LABELS[s]}
          </option>
        ))}
      </select>
      <ChevronDownIcon
        aria-hidden="true"
        className="pointer-events-none absolute right-1.5 top-1/2 -translate-y-1/2 w-3 h-3 opacity-70"
      />
    </div>
  )
}
