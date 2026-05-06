import type { InputHTMLAttributes } from 'react'

type Props = InputHTMLAttributes<HTMLInputElement> & {
  invalid?: boolean
}

export function Input({ invalid = false, className = '', ...rest }: Props) {
  const stateClasses = invalid
    ? 'border-red-400 focus-visible:border-red-500 focus-visible:ring-red-500/30'
    : 'border-slate-300 focus-visible:border-indigo-500 focus-visible:ring-indigo-500/30'

  return (
    <input
      className={`w-full h-11 rounded-lg border bg-white px-3.5 text-sm text-slate-900 placeholder:text-slate-400 shadow-sm transition focus-visible:outline-none focus-visible:ring-2 disabled:bg-slate-50 disabled:text-slate-400 disabled:cursor-not-allowed ${stateClasses} ${className}`}
      {...rest}
    />
  )
}
