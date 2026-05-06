import type { ReactNode } from 'react'
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
} from 'react-router-dom'
import { AuthProvider, useAuth } from './lib/auth'
import { DashboardPage } from './components/DashboardPage'
import { Login } from './components/Login'
import { StoreSelection } from './components/StoreSelection'

function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth()
  if (!isAuthenticated) return <Navigate to="/login" replace />
  return <>{children}</>
}

function RequireStore({ children }: { children: ReactNode }) {
  const { activeStore } = useAuth()
  if (!activeStore) return <Navigate to="/select-store" replace />
  return <>{children}</>
}

function RootRedirect() {
  const { isAuthenticated, activeStore } = useAuth()
  if (!isAuthenticated) return <Navigate to="/login" replace />
  if (!activeStore) return <Navigate to="/select-store" replace />
  return <Navigate to="/dashboard" replace />
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            path="/select-store"
            element={
              <RequireAuth>
                <StoreSelection />
              </RequireAuth>
            }
          />
          <Route
            path="/dashboard"
            element={
              <RequireAuth>
                <RequireStore>
                  <DashboardPage />
                </RequireStore>
              </RequireAuth>
            }
          />
          <Route path="/" element={<RootRedirect />} />
          <Route path="*" element={<RootRedirect />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}

export default App
