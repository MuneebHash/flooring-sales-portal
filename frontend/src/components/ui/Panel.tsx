import type { HTMLAttributes } from 'react'

export function Panel({
  className = '',
  ...rest
}: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={`bg-white rounded-2xl border border-slate-200 shadow-[0_1px_2px_rgba(15,23,42,0.06),0_18px_44px_-22px_rgba(15,23,42,0.20)] ${className}`}
      {...rest}
    />
  )
}
