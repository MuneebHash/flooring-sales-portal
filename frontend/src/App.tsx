import { AppHeader } from './components/AppHeader'
import { Dashboard } from './components/Dashboard'

const SESSION = {
  storeName: 'Aussie Floors Sydney CBD',
  storeCode: 'SYD-CBD',
  salesperson: 'Liam Carter',
  salespersonCode: 'LC1',
}

function App() {
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader {...SESSION} />
        <Dashboard />
      </div>
    </div>
  )
}

export default App
