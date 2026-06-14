import { useState, type FormEvent } from 'react'
import { Navigate } from 'react-router-dom'
import { Button } from './ui/Button'
import { Field } from './ui/Field'
import { Input } from './ui/Input'
import { Panel } from './ui/Panel'
import { useAuth } from '../lib/auth'
import { useTenantSlug } from '../lib/useTenantSlug'

export function Login() {
  const { isAuthenticated, activeStore, login } = useAuth()
  const slug = useTenantSlug()

  const [salespersonCode, setSalespersonCode] = useState('')
  const [password, setPassword] = useState('')
  const [codeError, setCodeError] = useState<string | undefined>()
  const [passwordError, setPasswordError] = useState<string | undefined>()
  const [formError, setFormError] = useState<string | undefined>()
  const [loading, setLoading] = useState(false)

  if (isAuthenticated) {
    return (
      <Navigate
        to={`/${slug}/${activeStore ? 'dashboard' : 'select-store'}`}
        replace
      />
    )
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()

    const trimmedCode = salespersonCode.trim()
    let invalid = false

    if (!trimmedCode) {
      setCodeError('Salesperson code is required.')
      invalid = true
    } else {
      setCodeError(undefined)
    }

    if (!password) {
      setPasswordError('Password is required.')
      invalid = true
    } else {
      setPasswordError(undefined)
    }

    setFormError(undefined)
    if (invalid) return

    setLoading(true)
    const result = await login(trimmedCode, password)
    if (!result.ok) {
      setFormError(result.error)
      setLoading(false)
    }
    // On success, auth state updates → next render returns <Navigate>.
  }

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex items-center justify-center px-4 py-8">
      <Panel className="w-full max-w-[420px] p-8">
        <div className="mb-6 text-center">
          <h1 className="text-xl font-semibold tracking-tight text-slate-900">
            Flooring Sales Portal
          </h1>
          <p className="text-sm text-slate-500 mt-1">Aussie Floors Group</p>
        </div>

        {formError && (
          <div
            role="alert"
            className="mb-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2.5 text-sm text-red-700"
          >
            {formError}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <Field
            label="Salesperson code"
            htmlFor="salesperson_code"
            error={codeError}
          >
            <Input
              id="salesperson_code"
              type="text"
              value={salespersonCode}
              onChange={(e) => setSalespersonCode(e.target.value)}
              autoCapitalize="off"
              autoCorrect="off"
              autoComplete="username"
              spellCheck={false}
              autoFocus
              disabled={loading}
              invalid={!!codeError}
            />
          </Field>

          <Field label="Password" htmlFor="password" error={passwordError}>
            <Input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              disabled={loading}
              invalid={!!passwordError}
            />
          </Field>

          <Button
            type="submit"
            variant="success"
            size="md"
            disabled={loading}
            className="w-full"
          >
            {loading ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
      </Panel>
    </div>
  )
}
