import type { ReactNode } from 'react'
import { Button } from './ui/Button'
import { Panel } from './ui/Panel'
import { LogoutIcon, StoreIcon, UserIcon } from './icons'

type Props = {
  storeName: string
  storeCode: string
  salesperson: string
  salespersonCode: string
}

function ContextBlock({
  icon,
  name,
  code,
}: {
  icon: ReactNode
  name: string
  code: string
}) {
  return (
    <div className="flex items-center gap-3 min-w-0">
      <span className="shrink-0 text-slate-700">{icon}</span>
      <div className="leading-tight min-w-0">
        <div className="text-sm font-semibold text-slate-900 truncate">
          {name}
        </div>
        <div className="text-xs font-medium text-blue-500 mt-0.5">{code}</div>
      </div>
    </div>
  )
}

export function AppHeader({
  storeName,
  storeCode,
  salesperson,
  salespersonCode,
}: Props) {
  return (
    <Panel className="px-7 py-4">
      <div className="flex items-center gap-6">
        <h1 className="text-xl font-semibold tracking-tight text-slate-900 whitespace-nowrap">
          Flooring Sales Portal
        </h1>
        <div className="h-12 w-px bg-slate-200" aria-hidden />
        <ContextBlock
          icon={<StoreIcon className="w-7 h-7" />}
          name={storeName}
          code={storeCode}
        />
        <div className="h-12 w-px bg-slate-200" aria-hidden />
        <ContextBlock
          icon={<UserIcon className="w-7 h-7" />}
          name={salesperson}
          code={salespersonCode}
        />
        <div className="ml-auto">
          <Button variant="secondary" size="md">
            <LogoutIcon className="w-4 h-4 text-slate-500" />
            Logout
          </Button>
        </div>
      </div>
    </Panel>
  )
}
