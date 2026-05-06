import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { AppHeader } from './AppHeader'
import { ArrowLeftIcon } from './icons'
import { Button } from './ui/Button'
import { Panel } from './ui/Panel'
import { Tabs } from './ui/Tabs'
import { CustomerTab } from './workspace/CustomerTab'
import { PlaceholderTab } from './workspace/PlaceholderTab'
import {
  FLOORING_LABELS,
  FLOORING_TONES,
  type FlooringType,
} from '../lib/flooring'

const TAB_IDS = [
  'customer',
  'products',
  'details',
  'notes',
  'payments',
  'invoice',
] as const

type TabId = (typeof TAB_IDS)[number]

const TAB_LABELS: Record<TabId, string> = {
  customer: 'Customer',
  products: 'Products & Charges',
  details: 'Details of Sale',
  notes: 'Notes & Photos',
  payments: 'Payments',
  invoice: 'Invoice',
}

const TABS = TAB_IDS.map((id) => ({ id, label: TAB_LABELS[id] }))

function isFlooringType(value: string | null): value is FlooringType {
  return value === 'SOFT' || value === 'HARD'
}

export function OrderWorkspace() {
  const [searchParams] = useSearchParams()
  const flooringTypeRaw = searchParams.get('flooring_type')
  const [activeTab, setActiveTab] = useState<TabId>('customer')

  if (!isFlooringType(flooringTypeRaw)) {
    return <InvalidWorkspace />
  }

  const flooringType: FlooringType = flooringTypeRaw

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader />

        <Panel className="px-6 py-5">
          <Link
            to="/dashboard"
            className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-500 hover:text-slate-800 transition-colors"
          >
            <ArrowLeftIcon className="w-3.5 h-3.5" />
            Back to dashboard
          </Link>
          <div className="mt-3 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div className="flex items-center gap-3 flex-wrap">
              <h2 className="text-2xl font-bold text-slate-900 tracking-tight">
                New order
              </h2>
              <span
                className={`inline-flex items-center px-2.5 py-1 rounded-md border text-xs font-medium ${FLOORING_TONES[flooringType]}`}
              >
                {FLOORING_LABELS[flooringType]}
              </span>
            </div>
            <div className="text-left sm:text-right">
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Sale total
              </div>
              <div className="text-2xl font-bold tabular-nums text-slate-900 mt-0.5">
                $0.00
              </div>
            </div>
          </div>
        </Panel>

        <Panel className="overflow-hidden">
          <Tabs tabs={TABS} active={activeTab} onChange={setActiveTab} />
          <div className="p-6">
            {activeTab === 'customer' && <CustomerTab />}
            {activeTab === 'products' && (
              <PlaceholderTab
                title="Products & Charges"
                text="Products and charges will be added here."
              />
            )}
            {activeTab === 'details' && (
              <PlaceholderTab
                title="Details of Sale"
                text="Details of sale will be recorded here."
              />
            )}
            {activeTab === 'notes' && (
              <PlaceholderTab
                title="Notes & Photos"
                text="Notes and site photos will be added here."
              />
            )}
            {activeTab === 'payments' && (
              <PlaceholderTab
                title="Payments"
                text="Payments will be recorded here."
              />
            )}
            {activeTab === 'invoice' && (
              <PlaceholderTab
                title="Invoice"
                text="Invoice creation and history will appear here."
              />
            )}
          </div>
        </Panel>
      </div>
    </div>
  )
}

function InvalidWorkspace() {
  const navigate = useNavigate()
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        <AppHeader />
        <Panel className="p-8 max-w-[520px] mx-auto text-center">
          <h2 className="text-xl font-semibold text-slate-900 tracking-tight">
            Invalid order type
          </h2>
          <p className="text-sm text-slate-500 mt-2">
            Choose Soft flooring or Hard flooring from the Dashboard to start
            a new order.
          </p>
          <div className="mt-5">
            <Button
              variant="success"
              size="md"
              onClick={() => navigate('/dashboard')}
            >
              Back to dashboard
            </Button>
          </div>
        </Panel>
      </div>
    </div>
  )
}
