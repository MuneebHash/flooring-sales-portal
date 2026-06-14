import { useEffect, type ReactNode } from 'react'
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useParams,
} from 'react-router-dom'
import { AuthProvider, useAuth } from './lib/auth'
import { DEFAULT_BUSINESS_SLUG, MARKETING_URL } from './lib/tenant'
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

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          {/*
            Reserved top-level app path names. Without these, React Router would
            capture the first segment of a missing-slug app URL (/login,
            /dashboard, /select-store, /orders/123, …) as a tenant :slug. These
            are NOT tenant URLs — like bare "/", they leave for the marketing
            site. Listed before :slug so the static segment wins over the
            dynamic slug for these exact names.

            This list must match every top-level app route name — if a new
            top-level route is added, add it here too, or it will be wrongly
            captured as a tenant slug.
          */}
          <Route path="/login" element={<MarketingRedirect />} />
          <Route path="/dashboard" element={<MarketingRedirect />} />
          <Route path="/select-store" element={<MarketingRedirect />} />
          <Route path="/orders" element={<MarketingRedirect />} />
          <Route path="/orders/new" element={<MarketingRedirect />} />
          <Route path="/orders/*" element={<MarketingRedirect />} />
          <Route path=":slug">
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
