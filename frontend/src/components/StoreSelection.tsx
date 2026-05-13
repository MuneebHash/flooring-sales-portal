import { Navigate } from 'react-router-dom'
import { AppHeader } from './AppHeader'
import { Panel } from './ui/Panel'
import { ChevronRightIcon } from './icons'
import { useAuth } from '../lib/auth'

export function StoreSelection() {
  const { user, stores, activeStore, selectStore } = useAuth()

  if (!user) return <Navigate to="/login" replace />
  if (activeStore) return <Navigate to="/dashboard" replace />

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
          <div className="space-y-2">
            {stores.map((store) => (
              <button
                key={store.store_id}
                type="button"
                onClick={() => selectStore(store.store_id)}
                className="group w-full flex items-center justify-between gap-4 p-4 rounded-xl border border-slate-200 bg-white hover:border-teal-500 hover:bg-teal-50/30 transition-colors text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30"
              >
                <div className="min-w-0">
                  <div className="text-base font-semibold text-slate-900 truncate">
                    {store.name}
                  </div>
                  <div className="font-mono text-xs text-slate-500 mt-0.5">
                    {store.store_code}
                  </div>
                </div>
                <ChevronRightIcon
                  aria-hidden="true"
                  className="w-5 h-5 text-slate-400 group-hover:text-teal-600 shrink-0"
                />
              </button>
            ))}
          </div>
        </Panel>
      </div>
    </div>
  )
}
