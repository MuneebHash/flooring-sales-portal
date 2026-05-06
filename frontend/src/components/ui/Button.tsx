import type { ButtonHTMLAttributes } from 'react'

type Variant =
  | 'primary'
  | 'secondary'
  | 'ghost'
  | 'success'
  | 'success-outline'
type Size = 'sm' | 'md'

const variantClasses: Record<Variant, string> = {
  primary:
    'bg-slate-900 text-white border border-slate-900 hover:bg-slate-800 shadow-sm',
  secondary:
    'bg-white text-slate-700 border border-slate-300 hover:bg-slate-50 shadow-sm',
  ghost:
    'bg-transparent text-slate-600 border border-transparent hover:bg-slate-100',
  success:
    'bg-teal-600 text-white border border-teal-600 hover:bg-teal-700 shadow-sm',
  'success-outline':
    'bg-white text-emerald-600 border border-emerald-500 hover:bg-emerald-50',
}

const sizeClasses: Record<Size, string> = {
  sm: 'h-7 px-3 text-[11px]',
  md: 'h-10 px-4 text-sm',
}

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant
  size?: Size
}

export function Button({
  variant = 'secondary',
  size = 'md',
  className = '',
  ...rest
}: Props) {
  return (
    <button
      className={`inline-flex items-center justify-center gap-1.5 rounded-lg font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 disabled:opacity-50 disabled:cursor-not-allowed ${variantClasses[variant]} ${sizeClasses[size]} ${className}`}
      {...rest}
    />
  )
}
