import type { HTMLAttributes } from 'react'

type Props = HTMLAttributes<HTMLSpanElement> & {
  tone?: string
}

export function Badge({
  tone = 'bg-slate-100 text-slate-700 border-slate-300',
  className = '',
  ...rest
}: Props) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-medium leading-none ${tone} ${className}`}
      {...rest}
    />
  )
}
