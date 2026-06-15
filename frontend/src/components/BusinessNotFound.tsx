import { Panel } from './ui/Panel'

// Shown when the public tenant lookup returns 404 for the slug in the URL — the
// business does not exist (or is inactive). Deliberately simple: no marketing
// redirect, no branding, no redesign.
export function BusinessNotFound() {
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex items-center justify-center px-4 py-8">
      <Panel className="w-full max-w-[420px] p-8 text-center">
        <h1 className="text-xl font-semibold tracking-tight text-slate-900">
          Business Not Found
        </h1>
        <p className="text-sm text-slate-500 mt-2">
          We could not find this business. Please check the link and try again.
        </p>
      </Panel>
    </div>
  )
}
