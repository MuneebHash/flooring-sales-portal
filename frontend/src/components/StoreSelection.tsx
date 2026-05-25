import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { AppHeader } from './AppHeader'
import { Panel } from './ui/Panel'
import { ChevronRightIcon } from './icons'
import { useAuth } from '../lib/auth'

export function StoreSelection() {
  const { user, stores, activeStore, selectStore } = useAuth()

  const [pendingStoreId, setPendingStoreId] = useState<number | null>(null)
  const [error, setError] = useState<string | undefined>()

  if (!user) return <Navigate to="/login" replace />
  if (activeStore) return <Navigate to="/dashboard" replace />

  const handleSelect = async (storeId: number) => {
    if (pendingStoreId !== null) return
    setPendingStoreId(storeId)
    setError(undefined)
    const result = await selectStore(storeId)
    if (!result.ok) {
      setError(result.error)
      setPendingStoreId(null)
    }
    // On success, activeStore updates → next render returns <Navigate>.
  }

  const submitting = pendingStoreId !== null

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader />
        <Panel className="p-7 max-w-[640px] mx-auto">
          <div className="mb-5">
            <h2 className="text-2xl font-bold text-slate-900 tracking-tight">
              Select a store
            </h2>
            <p className="text-sm text-slate-500 mt-1">
              You have access to multiple stores. Choose one to continue.
            </p>
          </div>
          {error && (
            <div
              role="alert"
              className="mb-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2.5 text-sm text-red-700"
            >
              {error}
            </div>
          )}
          <div className="space-y-2">
            {stores.map((store) => {
              const isPending = pendingStoreId === store.store_id
              return (
                <button
                  key={store.store_id}
                  type="button"
                  onClick={() => handleSelect(store.store_id)}
                  disabled={submitting}
                  className="group w-full flex items-center justify-between gap-4 p-4 rounded-xl border border-slate-200 bg-white hover:border-teal-500 hover:bg-teal-50/30 transition-colors text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30 disabled:opacity-60 disabled:cursor-not-allowed disabled:hover:border-slate-200 disabled:hover:bg-white"
                >
                  <div className="min-w-0">
                    <div className="text-base font-semibold text-slate-900 truncate">
                      {store.name}
                    </div>
                    <div className="font-mono text-xs text-slate-500 mt-0.5">
                      {store.store_code}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {isPending && (
                      <span className="text-xs text-slate-500">
                        Selecting…
                      </span>
                    )}
                    <ChevronRightIcon
                      aria-hidden="true"
                      className="w-5 h-5 text-slate-400 group-hover:text-teal-600"
                    />
                  </div>
                </button>
              )
            })}
          </div>
        </Panel>
      </div>
    </div>
  )
}
