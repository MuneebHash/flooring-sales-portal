import { AppHeader } from './AppHeader'
import { Dashboard } from './Dashboard'

export function DashboardPage() {
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader />
        <Dashboard />
      </div>
    </div>
  )
}
