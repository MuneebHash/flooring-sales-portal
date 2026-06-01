import { useEffect, useRef, useState } from 'react'
import { Button } from '../ui/Button'
import { Field } from '../ui/Field'
import { Input } from '../ui/Input'
import { Select } from '../ui/Select'
import { Textarea } from '../ui/Textarea'
import { CheckCircleIcon } from '../icons'
import { ApiError } from '../../lib/api/ApiError'
import { saveDetailsOfSale } from '../../lib/api/orderWorkspaceApi'
import type {
  DetailsOfSaleFields,
  DetailsOfSaleSaveRequest,
  LayDateStatus,
} from '../../lib/api/orderWorkspaceApi'

type Props = {
  orderId: number
  locked: boolean
  saleDetails?: DetailsOfSaleFields | null
  // Lifts the server-confirmed details fields (and refreshed updated_at) up so
  // workspace state — and sibling tabs that read it — stay in sync without a
  // refetch.
  onSaved: (saved: { fields: DetailsOfSaleFields; updated_at: string }) => void
}

// Local form mirror of the five details-of-sale fields. lay_date_status keeps the
// empty-string "no selection" state of the <select>; it normalises to null at
// build time.
type DetailsForm = {
  supply_only: boolean
  plan_numbers: string
  proposed_lay_date: string
  lay_date_status: '' | LayDateStatus
  details_of_sale: string
}

const QUICK_DESCRIPTIONS = [
  'Floor to be clear and clean for installers arrival.',
  'Furniture to be moved by installer.',
  'Site measure required before installation.',
  'No pull up and disposal required.',
  'Additional floor preparation may be charged if required.',
]

const MONTH_NAMES = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
]

// Backend VALIDATION_FAILED details[].field values this tab can surface inline.
// Anything outside this set falls back to the top-level error message.
const DETAILS_FIELD_KEYS = new Set([
  'supply_only',
  'plan_numbers',
  'proposed_lay_date',
  'lay_date_status',
  'details_of_sale',
])

function formatIsoDate(iso: string | null | undefined): string {
  if (!iso) return ''
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!match) return ''
  const [, year, month, day] = match
  const name = MONTH_NAMES[Number(month) - 1]
  if (!name) return ''
  return `${day} ${name} ${year}`
}

function formFromProps(d?: DetailsOfSaleFields | null): DetailsForm {
  return {
    supply_only: d?.supply_only ?? false,
    plan_numbers: d?.plan_numbers ?? '',
    proposed_lay_date: d?.proposed_lay_date ?? '',
    lay_date_status: d?.lay_date_status ?? '',
    details_of_sale: d?.details_of_sale ?? '',
  }
}

// Optional text field: trim, then collapse blank/whitespace to null so a
// full-replace PUT clears it rather than tripping the backend non-blank check.
function optionalField(value: string): string | null {
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

// Always build the complete 5-field body (full-replace). Optionals collapse to
// null; supply_only is always a boolean; the <input type="date"> already yields
// either '' or a strict YYYY-MM-DD string, matching the backend's strict parse.
function buildDetailsBody(form: DetailsForm): DetailsOfSaleSaveRequest {
  return {
    supply_only: form.supply_only,
    plan_numbers: optionalField(form.plan_numbers),
    proposed_lay_date: form.proposed_lay_date ? form.proposed_lay_date : null,
    lay_date_status: form.lay_date_status || null,
    details_of_sale: optionalField(form.details_of_sale),
  }
}

// Client-side validation before any network call. The lay-date pair rule mirrors
// the backend (chk_sales_order_lay_date_pair) and is reported on lay_date_status,
// the same field the backend attaches it to.
function validateForm(form: DetailsForm): Record<string, string> {
  const newErrors: Record<string, string> = {}
  const hasDate = form.proposed_lay_date.trim().length > 0
  const hasStatus = form.lay_date_status.length > 0
  if (hasDate !== hasStatus) {
    newErrors.lay_date_status =
      'Proposed lay date and lay date status must both be set or both be cleared.'
  }
  return newErrors
}

function apiErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.message.length > 0) return err.message
  return 'Something went wrong while saving. Please try again.'
}

// Best-effort map of a backend VALIDATION_FAILED details[] onto this tab's
// field error keys (the snake_case field names). Anything that does not line up
// with a known field still surfaces via the top-level error message.
function mapDetailsToFieldErrors(err: unknown): Record<string, string> {
  const result: Record<string, string> = {}
  if (!(err instanceof ApiError) || err.code !== 'VALIDATION_FAILED') return result
  const details = err.details
  if (!Array.isArray(details)) return result
  for (const detail of details) {
    if (!detail || typeof detail !== 'object') continue
    const field = (detail as { field?: unknown }).field
    const message = (detail as { message?: unknown }).message
    if (typeof field !== 'string' || !DETAILS_FIELD_KEYS.has(field)) continue
    result[field] =
      typeof message === 'string' && message.length > 0
        ? message
        : 'Invalid value.'
  }
  return result
}

export function DetailsOfSaleTab({ orderId, locked, saleDetails, onSaved }: Props) {
  const [form, setForm] = useState<DetailsForm>(() => formFromProps(saleDetails))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  // Guards setState after the tab unmounts (e.g. the order route changes, or the
  // user switches tabs, while a save is still in flight).
  const mountedRef = useRef(true)
  useEffect(() => {
    // Reset on setup so a StrictMode setup→cleanup→setup cycle (or any remount)
    // leaves the ref true for the live component; cleanup marks a real unmount.
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  function clearError(key: string) {
    setErrors((prev) => {
      if (!(key in prev)) return prev
      const next = { ...prev }
      delete next[key]
      return next
    })
  }

  function update<K extends keyof DetailsForm>(key: K, value: DetailsForm[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
    clearError(key)
    // The pair error spans both lay-date fields and is keyed on lay_date_status,
    // so editing either field should clear it.
    if (key === 'proposed_lay_date' || key === 'lay_date_status') {
      clearError('lay_date_status')
    }
    setSaved(false)
    setSaveError(null)
  }

  function appendSnippet(snippet: string) {
    setForm((prev) => ({
      ...prev,
      details_of_sale: prev.details_of_sale.trim()
        ? `${prev.details_of_sale.trimEnd()}\n${snippet}`
        : snippet,
    }))
    clearError('details_of_sale')
    setSaved(false)
    setSaveError(null)
  }

  async function handleSave() {
    if (locked || saving) return

    // Strict client validation first — no network call when the form is invalid.
    const validationErrors = validateForm(form)
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors)
      setSaved(false)
      setSaveError(null)
      return
    }
    setErrors({})

    // Always send the complete field set (full-replace PUT).
    const body = buildDetailsBody(form)

    setSaving(true)
    setSaved(false)
    setSaveError(null)

    try {
      const res = await saveDetailsOfSale(orderId, body)
      if (!mountedRef.current) return
      const fields = res.data.details_of_sale_fields
      // Re-seed the form from the server-confirmed values, then lift them up.
      setForm(formFromProps(fields))
      setSaved(true)
      onSaved({ fields, updated_at: res.data.updated_at })
    } catch (err) {
      if (!mountedRef.current) return
      const fieldErrors = mapDetailsToFieldErrors(err)
      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors)
      }
      // Surfaces VALIDATION_FAILED, ORDER_LOCKED, and network/server errors.
      setSaveError(apiErrorMessage(err))
    } finally {
      if (mountedRef.current) setSaving(false)
    }
  }

  const editingDisabled = locked || saving

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
          Details of Sale
        </h2>
        <p className="text-sm text-slate-500 mt-1">
          Record description, lay date and supply terms for this order.
        </p>
      </div>

      <div className="space-y-6">
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <div className="lg:col-span-2">
            <Field
              label="Description"
              htmlFor="details_of_sale"
              error={errors.details_of_sale}
            >
              <Textarea
                id="details_of_sale"
                rows={10}
                value={form.details_of_sale}
                onChange={(e) => update('details_of_sale', e.target.value)}
                invalid={!!errors.details_of_sale}
                disabled={editingDisabled}
                placeholder="Describe the supply, installation and any site notes for this order."
              />
            </Field>
          </div>

          <div className="lg:col-span-1">
            <div className="rounded-lg border border-slate-200 bg-slate-50/60 p-4 h-full">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-600">
                Quick add descriptions
              </h3>
              <p className="text-[11px] text-slate-500 mt-1">
                Tap + to append a preset to the description.
              </p>
              <ul className="mt-3 space-y-2">
                {QUICK_DESCRIPTIONS.map((snippet) => (
                  <li
                    key={snippet}
                    className="flex items-start gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 shadow-sm"
                  >
                    <span className="flex-1 text-xs text-slate-700 leading-snug">
                      {snippet}
                    </span>
                    <button
                      type="button"
                      onClick={() => appendSnippet(snippet)}
                      disabled={editingDisabled}
                      aria-label={`Add snippet: ${snippet}`}
                      className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md border border-teal-500 bg-white text-teal-600 hover:bg-teal-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      <span aria-hidden="true" className="text-base leading-none">
                        +
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Field
            label="Proposed lay date"
            htmlFor="proposed_lay_date"
            error={errors.proposed_lay_date}
          >
            <Input
              id="proposed_lay_date"
              type="date"
              value={form.proposed_lay_date}
              onChange={(e) => update('proposed_lay_date', e.target.value)}
              invalid={!!errors.proposed_lay_date}
              disabled={editingDisabled}
            />
            <p className="mt-1.5 text-xs text-slate-500">
              {formatIsoDate(form.proposed_lay_date) || 'No date selected'}
            </p>
          </Field>
          <Field
            label="Lay date status"
            htmlFor="lay_date_status"
            error={errors.lay_date_status}
          >
            <Select
              id="lay_date_status"
              value={form.lay_date_status}
              onChange={(e) =>
                update('lay_date_status', e.target.value as '' | LayDateStatus)
              }
              invalid={!!errors.lay_date_status}
              disabled={editingDisabled}
            >
              <option value="">— Select —</option>
              <option value="CONFIRMED">Confirmed</option>
              <option value="TO_BE_CONFIRMED">To be confirmed</option>
            </Select>
          </Field>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Field
            label="Plan numbers"
            htmlFor="plan_numbers"
            error={errors.plan_numbers}
          >
            <Input
              id="plan_numbers"
              type="text"
              value={form.plan_numbers}
              onChange={(e) => update('plan_numbers', e.target.value)}
              invalid={!!errors.plan_numbers}
              disabled={editingDisabled}
            />
          </Field>
        </div>

        <label className="inline-flex items-center gap-2 text-sm text-slate-700 cursor-pointer">
          <input
            id="supply_only"
            type="checkbox"
            checked={form.supply_only}
            onChange={(e) => update('supply_only', e.target.checked)}
            disabled={editingDisabled}
            className="h-4 w-4 rounded border-slate-300 text-teal-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30 disabled:opacity-50 disabled:cursor-not-allowed"
          />
          <span>Supply only (no installation)</span>
        </label>
      </div>

      <div className="mt-8 pt-5 border-t border-slate-200 space-y-4">
        {locked && (
          <p className="text-xs text-slate-500">
            This order is laid and locked. Editing is disabled.
          </p>
        )}

        {saved && (
          <div className="inline-flex items-center gap-1.5 text-xs text-teal-700">
            <CheckCircleIcon className="w-3.5 h-3.5" />
            Details of sale saved.
          </div>
        )}

        {saveError && (
          <div className="rounded-lg border border-red-300 bg-red-50 px-4 py-3 text-xs text-red-800">
            {saveError}
          </div>
        )}

        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <p className="text-[11px] text-slate-500">
            Save description, supply terms, plan numbers and lay date details
            for this order.
          </p>
          <Button
            type="button"
            variant="success"
            size="md"
            onClick={handleSave}
            disabled={editingDisabled}
          >
            {saving ? 'Saving…' : 'Save details'}
          </Button>
        </div>
      </div>
    </div>
  )
}
