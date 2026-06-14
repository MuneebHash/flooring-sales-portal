import { useEffect, type ReactNode } from 'react'
import {
  BrowserRouter,
  Navigate,
  Outlet,
  Route,
  Routes,
  useParams,
} from 'react-router-dom'
import { AuthProvider, useAuth } from './lib/auth'
import {
  DEFAULT_BUSINESS_SLUG,
  MARKETING_URL,
  isReservedBusinessSlug,
} from './lib/tenant'
import { DashboardPage } from './components/DashboardPage'
import { Login } from './components/Login'
import { OrderWorkspace } from './components/OrderWorkspace'
import { StoreSelection } from './components/StoreSelection'

function RequireAuth({ children }: { children: ReactNode }) {
  const { slug } = useParams<{ slug: string }>()
  const { isAuthenticated } = useAuth()
  if (!isAuthenticated) return <Navigate to={`/${slug}/login`} replace />
  return <>{children}</>
}

function RequireStore({ children }: { children: ReactNode }) {
  const { slug } = useParams<{ slug: string }>()
  const { activeStore } = useAuth()
  if (!activeStore) return <Navigate to={`/${slug}/select-store`} replace />
  return <>{children}</>
}

function RootRedirect() {
  const { slug } = useParams<{ slug: string }>()
  const { isAuthenticated, activeStore } = useAuth()
  // Rendered only under the :slug route, so slug is always present; the fallback
  // is purely to satisfy the (string | undefined) type from useParams.
  const base = `/${slug ?? DEFAULT_BUSINESS_SLUG}`
  if (!isAuthenticated) return <Navigate to={`${base}/login`} replace />
  if (!activeStore) return <Navigate to={`${base}/select-store`} replace />
  return <Navigate to={`${base}/dashboard`} replace />
}

// Bare "/" and any path without a tenant slug leave the app entirely for the
// external marketing site. This MUST be a real browser navigation — React
// Router <Navigate> only moves within this SPA and cannot reach another domain.
function MarketingRedirect() {
  useEffect(() => {
    window.location.href = MARKETING_URL
  }, [])
  return null
}

// Central reserved-slug guard for the :slug route. If the first URL segment is a
// reserved app/system word (RESERVED_BUSINESS_SLUGS in lib/tenant.ts, which
// mirrors backend V11 chk_business_slug_reserved) it is NOT a tenant — leave for
// the marketing site, same as bare "/". Otherwise render the tenant routes. This
// single guard replaces per-word route entries so the reserved list lives in one
// place and cannot drift.
function TenantGuard() {
  const { slug } = useParams<{ slug: string }>()
  if (slug && isReservedBusinessSlug(slug)) return <MarketingRedirect />
  return <Outlet />
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path=":slug" element={<TenantGuard />}>
            <Route path="login" element={<Login />} />
            <Route
              path="select-store"
              element={
                <RequireAuth>
                  <StoreSelection />
                </RequireAuth>
              }
            />
            <Route
              path="dashboard"
              element={
                <RequireAuth>
                  <RequireStore>
                    <DashboardPage />
                  </RequireStore>
                </RequireAuth>
              }
            />
            <Route
              path="orders/new"
              element={
                <RequireAuth>
                  <RequireStore>
                    <OrderWorkspace />
                  </RequireStore>
                </RequireAuth>
              }
            />
            <Route
              path="orders/:orderId"
              element={
                <RequireAuth>
                  <RequireStore>
                    <OrderWorkspace />
                  </RequireStore>
                </RequireAuth>
              }
            />
            <Route index element={<RootRedirect />} />
            <Route path="*" element={<RootRedirect />} />
          </Route>
          <Route path="/" element={<MarketingRedirect />} />
          <Route path="*" element={<MarketingRedirect />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}

export default App
