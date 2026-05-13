import type { SelectHTMLAttributes } from 'react'

type Props = SelectHTMLAttributes<HTMLSelectElement> & {
  invalid?: boolean
}

export function Select({
  invalid = false,
  className = '',
  children,
  ...rest
}: Props) {
  const stateClasses = invalid
    ? 'border-red-400 focus-visible:border-red-500 focus-visible:ring-red-500/30'
    : 'border-slate-300 focus-visible:border-indigo-500 focus-visible:ring-indigo-500/30'

  return (
    <select
      className={`w-full h-11 rounded-lg border bg-white px-3.5 pr-9 text-sm text-slate-900 shadow-sm transition focus-visible:outline-none focus-visible:ring-2 disabled:bg-slate-50 disabled:text-slate-400 disabled:cursor-not-allowed appearance-none bg-no-repeat bg-[right_0.75rem_center] bg-[length:1rem_1rem] bg-[url("data:image/svg+xml;charset=utf-8,%3Csvg%20xmlns%3D%27http%3A//www.w3.org/2000/svg%27%20fill%3D%27none%27%20viewBox%3D%270%200%2020%2020%27%3E%3Cpath%20stroke%3D%27%2364748b%27%20stroke-linecap%3D%27round%27%20stroke-linejoin%3D%27round%27%20stroke-width%3D%271.5%27%20d%3D%27m6%208%204%204%204-4%27/%3E%3C/svg%3E')] ${stateClasses} ${className}`}
      {...rest}
    >
      {children}
    </select>
  )
}
