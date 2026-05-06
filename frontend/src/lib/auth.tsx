import {
  createContext,
  useContext,
  useState,
  type ReactNode,
} from 'react'
import { MOCK_AUTH_SCENARIOS } from '../data/mockAuth'

export type User = {
  user_id: number
  first_name: string
  last_name: string
  salesperson_code: string
}

export type Store = {
  store_id: number
  name: string
  store_code: string
}

type AuthState = {
  user: User | null
  stores: Store[]
  activeStore: Store | null
}

export type LoginResult = { ok: true } | { ok: false; error: string }

type AuthContextValue = AuthState & {
  isAuthenticated: boolean
  login: (
    salesperson_code: string,
    password: string,
  ) => Promise<LoginResult>
  selectStore: (store_id: number) => void
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    user: null,
    stores: [],
    activeStore: null,
  })

  const login = async (
    salesperson_code: string,
    password: string,
  ): Promise<LoginResult> => {
    await new Promise((resolve) => setTimeout(resolve, 300))
    const scenario = MOCK_AUTH_SCENARIOS.find(
      (s) =>
        s.salesperson_code === salesperson_code && s.password === password,
    )
    if (!scenario) {
      return { ok: false, error: 'Invalid salesperson code or password.' }
    }
    const activeStore =
      scenario.active_store_id !== null
        ? scenario.stores.find(
            (s) => s.store_id === scenario.active_store_id,
          ) ?? null
        : null
    setState({
      user: scenario.user,
      stores: scenario.stores,
      activeStore,
    })
    return { ok: true }
  }

  const selectStore = (store_id: number) => {
    setState((prev) => {
      const found = prev.stores.find((s) => s.store_id === store_id)
      if (!found) return prev
      return { ...prev, activeStore: found }
    })
  }

  const logout = () => {
    setState({ user: null, stores: [], activeStore: null })
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
