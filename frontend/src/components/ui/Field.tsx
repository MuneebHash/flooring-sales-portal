import type { ReactNode } from 'react'

type Props = {
  label: string
  htmlFor: string
  error?: string
  children: ReactNode
}

export function Field({ label, htmlFor, error, children }: Props) {
  return (
    <div>
      <label
        htmlFor={htmlFor}
        className="block text-sm font-medium text-slate-700 mb-1.5"
      >
        {label}
      </label>
      {children}
      {error && (
        <p className="mt-1.5 text-xs text-red-600" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
