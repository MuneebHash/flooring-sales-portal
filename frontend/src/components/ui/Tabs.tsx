type Tab<T extends string> = {
  id: T
  label: string
}

type Props<T extends string> = {
  tabs: Tab<T>[]
  active: T
  onChange: (id: T) => void
}

export function Tabs<T extends string>({ tabs, active, onChange }: Props<T>) {
  return (
    <div
      role="tablist"
      className="flex items-center gap-1 border-b border-slate-200 px-3 overflow-x-auto"
    >
      {tabs.map((tab) => {
        const isActive = tab.id === active
        return (
          <button
            key={tab.id}
            role="tab"
            type="button"
            aria-selected={isActive}
            onClick={() => onChange(tab.id)}
            className={`whitespace-nowrap px-4 py-3 text-sm border-b-2 -mb-px transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 rounded-t ${
              isActive
                ? 'border-slate-900 text-slate-900 font-semibold'
                : 'border-transparent text-slate-500 hover:text-slate-800 font-medium'
            }`}
          >
            {tab.label}
          </button>
        )
      })}
    </div>
  )
}
