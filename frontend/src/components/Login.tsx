import { useEffect, useState, type FormEvent } from 'react'
import { Navigate } from 'react-router-dom'
import { Button } from './ui/Button'
import { Field } from './ui/Field'
import { Input } from './ui/Input'
import { Panel } from './ui/Panel'
import { BusinessNotFound } from './BusinessNotFound'
import { useAuth } from '../lib/auth'
import { useTenantSlug } from '../lib/useTenantSlug'
import { ApiError } from '../lib/api/ApiError'
import { fetchPublicBusiness } from '../lib/api/tenantApi'
import type { PublicBusiness } from '../lib/api/types'

type TenantState =
  | { status: 'loading' }
  | { status: 'ready'; tenant: PublicBusiness }
  | { status: 'not-found' }
  | { status: 'error' }

export function Login() {
  const { isAuthenticated, activeStore, login } = useAuth()
  const slug = useTenantSlug()

  const [salespersonCode, setSalespersonCode] = useState('')
  const [password, setPassword] = useState('')
  const [codeError, setCodeError] = useState<string | undefined>()
  const [passwordError, setPasswordError] = useState<string | undefined>()
  const [formError, setFormError] = useState<string | undefined>()
  const [loading, setLoading] = useState(false)

  const [tenantState, setTenantState] = useState<TenantState>({
    status: 'loading',
  })
  const [reloadToken, setReloadToken] = useState(0)

  // Validate the tenant slug against the public lookup before showing the form.
  // Skipped entirely when already authenticated — that user is redirected below
  // and never needs the loading / not-found / error states. Re-runs on slug
  // change and on retry (reloadToken); a cancel flag prevents a stale request
  // from writing state after the slug changed or the component unmounted.
  useEffect(() => {
    if (isAuthenticated) return
    let cancelled = false
    setTenantState({ status: 'loading' })
    fetchPublicBusiness(slug)
      .then((tenant) => {
        if (!cancelled) setTenantState({ status: 'ready', tenant })
      })
      .catch((err) => {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 404) {
          setTenantState({ status: 'not-found' })
        } else {
          setTenantState({ status: 'error' })
        }
      })
    return () => {
      cancelled = true
    }
  }, [slug, isAuthenticated, reloadToken])

  // Guard 1 (FIRST): an already-authenticated user goes straight to their
  // destination — never the loading / not-found / error states below.
  if (isAuthenticated) {
    return (
      <Navigate
        to={`/${slug}/${activeStore ? 'dashboard' : 'select-store'}`}
        replace
      />
    )
  }

  // Guard 2: tenant validation must resolve before the login form renders.
  if (tenantState.status === 'loading') {
    return (
      <div className="min-h-screen bg-slate-50 text-slate-900 flex items-center justify-center px-4 py-8">
        <Panel className="w-full max-w-[420px] p-8 text-center">
          <p className="text-sm text-slate-500">Loading…</p>
        </Panel>
      </div>
    )
  }

  if (tenantState.status === 'not-found') {
    return <BusinessNotFound />
  }

  if (tenantState.status === 'error') {
    return (
      <div className="min-h-screen bg-slate-50 text-slate-900 flex items-center justify-center px-4 py-8">
        <Panel className="w-full max-w-[420px] p-8 text-center">
          <h1 className="text-xl font-semibold tracking-tight text-slate-900">
            Something went wrong
          </h1>
          <p className="text-sm text-slate-500 mt-2">
            We could not load this business right now. Please try again.
          </p>
          <Button
            type="button"
            variant="success"
            size="md"
            className="mt-5 w-full"
            onClick={() => setReloadToken((n) => n + 1)}
          >
            Try again
          </Button>
        </Panel>
      </div>
    )
  }

  const tenant = tenantState.tenant

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
          <p className="text-sm text-slate-500 mt-1">{tenant.name}</p>
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
