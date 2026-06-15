import {
  createContext,
  useContext,
  useState,
  type ReactNode,
} from 'react'
import { ApiError } from './api/ApiError'
import {
  loginRequest,
  logoutRequest,
  selectStoreRequest,
} from './api/authApi'
import type { Store, User } from './authTypes'

type AuthState = {
  user: User | null
  stores: Store[]
  activeStore: Store | null
}

export type LoginResult = { ok: true } | { ok: false; error: string }
export type SelectStoreResult = { ok: true } | { ok: false; error: string }

type AuthContextValue = AuthState & {
  isAuthenticated: boolean
  login: (
    salesperson_code: string,
    password: string,
  ) => Promise<LoginResult>
  selectStore: (store_id: number) => Promise<SelectStoreResult>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

const EMPTY_STATE: AuthState = {
  user: null,
  stores: [],
  activeStore: null,
}

function errorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) return err.message
  if (err instanceof Error && err.message.length > 0) return err.message
  return fallback
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(EMPTY_STATE)

  const login = async (
    salesperson_code: string,
    password: string,
  ): Promise<LoginResult> => {
    try {
      const response = await loginRequest({ salesperson_code, password })
      const { user, stores, active_store_id } = response.data
      const activeStore =
        active_store_id !== null
          ? stores.find((s) => s.store_id === active_store_id) ?? null
          : null
      setState({ user, stores, activeStore })
      return { ok: true }
    } catch (err) {
      return {
        ok: false,
        error: errorMessage(err, 'Login failed. Please try again.'),
      }
    }
  }

  const selectStore = async (
    store_id: number,
  ): Promise<SelectStoreResult> => {
    try {
      const response = await selectStoreRequest({ store_id })
      const { active_store } = response.data
      setState((prev) => ({ ...prev, activeStore: active_store }))
      return { ok: true }
    } catch (err) {
      return {
        ok: false,
        error: errorMessage(err, 'Could not select store. Please try again.'),
      }
    }
  }

  const logout = async (): Promise<void> => {
    try {
      await logoutRequest()
    } catch {
      // Session already gone or network failure — still clear local state.
    }
    setState(EMPTY_STATE)
  }

  return (
    <AuthContext.Provider
      value={{
        ...state,
        isAuthenticated: state.user !== null,
        login,
        selectStore,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return ctx
}
