import { useEffect, useRef, useState } from 'react'
import { Button } from '../ui/Button'
import { Field } from '../ui/Field'
import { Input } from '../ui/Input'
import { Textarea } from '../ui/Textarea'
import { CheckCircleIcon } from '../icons'
import { ApiError } from '../../lib/api/ApiError'
import { saveEnquiry } from '../../lib/api/orderWorkspaceApi'
import type {
  EnquirySaveRequest,
  LeadType,
  OrderEnquiry,
} from '../../lib/api/orderWorkspaceApi'

// Phase 15F Lead Enquiry form — rendered inside the Customer tab as its own
// sub-section with a SEPARATE "Save Lead Enquiry" button AND background autosave.
// It never touches the existing customer/address save. The backend is the source
// of truth: the form seeds ONCE from the saved enquiry (null = empty form) and the
// confirmed row is lifted to workspace state via onSaved. The local form stays the
// active source for in-progress edits — it is deliberately NOT re-seeded from the
// enquiry prop after mount (that would let an older server response clobber newer
// unsaved edits, since a successful save updates the prop through onSaved).

// Debounce after a change before autosaving. Short enough that clicking away saves
// promptly, long enough to not fire on every keystroke. Text fields also flush on
// blur, and any pending dirty edit is flushed when the component unmounts.
const AUTOSAVE_DEBOUNCE_MS = 800

type Props = {
  orderId: number
  // LAID lock — disables every input and the Save button and suppresses autosave.
  locked: boolean
  enquiry?: OrderEnquiry | null
  // Lifts the server-confirmed enquiry row up so workspace state stays in sync
  // without a refetch (mirrors the customer-save lift). The payload carries the
  // order id the PUT was addressed to so the parent can drop a late callback from
  // a previous order.
  onSaved: (payload: { orderId: number; enquiry: OrderEnquiry }) => void
}

// Controlled-form mirror of OrderEnquiry: text fields are strings (never null) so
// the inputs stay controlled; the four tri-state fields keep boolean | null so
// "unanswered" round-trips; lead_type is the single-select enum or null.
type EnquiryForm = {
  lead_type: LeadType | null
  carpet: boolean
  hard_floor: boolean
  product_fit_timing: string
  product_usage_context: string
  rooms_to_cover: string
  textured_or_plain: string
  light_or_dark_tones: string
  colours_interested: string
  current_flooring_and_reason: string
  products_discussed: string
  fully_installed: boolean | null
  uplift: boolean | null
  furniture: boolean | null
  stairs: boolean | null
  subfloor_concrete: boolean
  subfloor_timber: boolean
  subfloor_tile: boolean
  brief_summary: string
}

function enquiryFromProps(e?: OrderEnquiry | null): EnquiryForm {
  return {
    lead_type: e?.lead_type ?? null,
    carpet: e?.carpet ?? false,
    hard_floor: e?.hard_floor ?? false,
    product_fit_timing: e?.product_fit_timing ?? '',
    product_usage_context: e?.product_usage_context ?? '',
    rooms_to_cover: e?.rooms_to_cover ?? '',
    textured_or_plain: e?.textured_or_plain ?? '',
    light_or_dark_tones: e?.light_or_dark_tones ?? '',
    colours_interested: e?.colours_interested ?? '',
    current_flooring_and_reason: e?.current_flooring_and_reason ?? '',
    products_discussed: e?.products_discussed ?? '',
    // Tri-state: preserve null (unanswered); only fall back to null when absent.
    fully_installed: e?.fully_installed ?? null,
    uplift: e?.uplift ?? null,
    furniture: e?.furniture ?? null,
    stairs: e?.stairs ?? null,
    subfloor_concrete: e?.subfloor_concrete ?? false,
    subfloor_timber: e?.subfloor_timber ?? false,
    subfloor_tile: e?.subfloor_tile ?? false,
    brief_summary: e?.brief_summary ?? '',
  }
}

// Optional free-text field: trim, then collapse blank/whitespace to null so a
// full-replace PUT clears it (mirrors the customer tab's optionalField).
function optionalField(value: string): string | null {
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

// Every save sends the COMPLETE body (all 19 fields) — the backend PUT is
// full-replace, so a partial body would clear the omitted fields.
function buildEnquiryBody(form: EnquiryForm): EnquirySaveRequest {
  return {
    lead_type: form.lead_type,
    carpet: form.carpet,
    hard_floor: form.hard_floor,
    product_fit_timing: optionalField(form.product_fit_timing),
    product_usage_context: optionalField(form.product_usage_context),
    rooms_to_cover: optionalField(form.rooms_to_cover),
    textured_or_plain: optionalField(form.textured_or_plain),
    light_or_dark_tones: optionalField(form.light_or_dark_tones),
    colours_interested: optionalField(form.colours_interested),
    current_flooring_and_reason: optionalField(form.current_flooring_and_reason),
    products_discussed: optionalField(form.products_discussed),
    // Tri-state passes through unchanged: true / false / null.
    fully_installed: form.fully_installed,
    uplift: form.uplift,
    furniture: form.furniture,
    stairs: form.stairs,
    subfloor_concrete: form.subfloor_concrete,
    subfloor_timber: form.subfloor_timber,
    subfloor_tile: form.subfloor_tile,
    brief_summary: optionalField(form.brief_summary),
  }
}

function apiErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.message.length > 0) return err.message
  return 'Something went wrong while saving. Please try again.'
}

// Lead Type is a SINGLE choice (or unanswered). The controls look like checkboxes
// but only one may be selected at a time; clicking the selected one clears it.
const LEAD_TYPE_OPTIONS: Array<{ value: LeadType; label: string }> = [
  { value: 'FLOOR', label: 'Floor' },
  { value: 'PHONE', label: 'Phone' },
  { value: 'INTERNET', label: 'Internet' },
]

// Compact native checkbox (same styling as the other inline checkboxes in the
// workspace) for the independent product / subfloor flags and the single-select
// Lead Type options.
function CheckboxField({
  id,
  label,
  checked,
  onChange,
  disabled,
}: {
  id: string
  label: string
  checked: boolean
  onChange: (checked: boolean) => void
  disabled: boolean
}) {
  return (
    <label
      htmlFor={id}
      className="inline-flex items-center gap-2 text-sm text-slate-700 cursor-pointer"
    >
      <input
        id={id}
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        disabled={disabled}
        className="h-4 w-4 rounded border-slate-300 text-teal-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30 disabled:opacity-50 disabled:cursor-not-allowed"
      />
      <span>{label}</span>
    </label>
  )
}

// Tri-state Yes / No / Unanswered segmented control. The selected state is
// visually explicit and "Unanswered" (null) is a real, selectable option so the
// backend never sees an answered-by-default value.
const YES_NO_OPTIONS: Array<{ key: string; label: string; value: boolean | null }> = [
  { key: 'yes', label: 'Yes', value: true },
  { key: 'no', label: 'No', value: false },
  { key: 'unanswered', label: 'Unanswered', value: null },
]

function YesNoField({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string
  value: boolean | null
  onChange: (value: boolean | null) => void
  disabled: boolean
}) {
  return (
    <div>
      <span className="block text-xs font-medium text-slate-600 mb-1.5">
        {label}
      </span>
      <div className="inline-flex rounded-lg border border-slate-300 overflow-hidden">
        {YES_NO_OPTIONS.map((opt, i) => {
          const selected = value === opt.value
          return (
            <button
              key={opt.key}
              type="button"
              disabled={disabled}
              aria-pressed={selected}
              onClick={() => onChange(opt.value)}
              className={[
                'px-3 py-1.5 text-xs font-medium transition-colors',
                i > 0 ? 'border-l border-slate-300' : '',
                selected
                  ? 'bg-teal-600 text-white'
                  : 'bg-white text-slate-600 hover:bg-slate-50',
                disabled ? 'opacity-50 cursor-not-allowed' : '',
              ].join(' ')}
            >
              {opt.label}
            </button>
          )
        })}
      </div>
    </div>
  )
}

export function LeadEnquirySection({ orderId, locked, enquiry, onSaved }: Props) {
  // Seeded ONCE from the prop; never re-seeded from the prop after mount.
  const [form, setForm] = useState<EnquiryForm>(() => enquiryFromProps(enquiry))
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Latest values mirrored into refs so the debounce / blur / unmount-flush paths
  // read the freshest data and lift correctly even after this component unmounts.
  // CustomerTab is kept mounted across top-level workspace tab switches (it is
  // hidden, not unmounted, in OrderWorkspace), so a normal tab switch no longer
  // unmounts this section — the refs matter for actual order/page unmount.
  const formRef = useRef(form)
  formRef.current = form
  const lockedRef = useRef(locked)
  lockedRef.current = locked
  const onSavedRef = useRef(onSaved)
  onSavedRef.current = onSaved
  const orderIdRef = useRef(orderId)
  orderIdRef.current = orderId

  const mountedRef = useRef(true)
  // JSON of the last body we successfully sent — the dirty baseline. Initialised to
  // the seeded form so a freshly-loaded form is never autosaved until it changes.
  const lastSavedBodyRef = useRef<string>(JSON.stringify(buildEnquiryBody(form)))
  const savingRef = useRef(false)
  const pendingBodyRef = useRef<EnquirySaveRequest | null>(null)
  const debounceRef = useRef<number | null>(null)

  // Single-flight save loop: sends exactly one PUT; on settle, drains the latest
  // queued body only if it differs from what is now persisted. lastSavedBodyRef is
  // set ONLY after a successful response, so a failed save stays dirty and retries,
  // and a stale older response can never advance the baseline past a newer body.
  async function runSave(body: EnquirySaveRequest) {
    savingRef.current = true
    if (mountedRef.current) {
      setSaving(true)
      setSaved(false)
      setError(null)
    }
    // Capture the order id this PUT is addressed to BEFORE awaiting, and lift it
    // with the result — never read orderIdRef.current inside the .then, since by
    // then the order may have changed.
    const saveOrderId = orderIdRef.current
    try {
      const res = await saveEnquiry(saveOrderId, body)
      lastSavedBodyRef.current = JSON.stringify(body)
      // Lift to workspace state — safe even if this component already unmounted,
      // because the parent (OrderWorkspace) outlives it. The parent drops the
      // callback if saveOrderId is no longer the loaded order.
      onSavedRef.current({ orderId: saveOrderId, enquiry: res.data.enquiry })
      if (mountedRef.current) setSaved(true)
    } catch (err) {
      if (mountedRef.current) setError(apiErrorMessage(err))
    } finally {
      savingRef.current = false
      const pending = pendingBodyRef.current
      pendingBodyRef.current = null
      if (pending && JSON.stringify(pending) !== lastSavedBodyRef.current) {
        await runSave(pending)
      } else if (mountedRef.current) {
        setSaving(false)
      }
    }
  }

  // Saves the current form iff it differs from the last persisted body. If a save
  // is already in flight, queues the latest complete body for the drain.
  function requestSave() {
    if (lockedRef.current) return
    const body = buildEnquiryBody(formRef.current)
    if (JSON.stringify(body) === lastSavedBodyRef.current) return
    if (savingRef.current) {
      pendingBodyRef.current = body
      return
    }
    void runSave(body)
  }

  function clearDebounce() {
    if (debounceRef.current !== null) {
      window.clearTimeout(debounceRef.current)
      debounceRef.current = null
    }
  }

  function scheduleAutosave() {
    if (lockedRef.current) return
    clearDebounce()
    debounceRef.current = window.setTimeout(() => {
      debounceRef.current = null
      requestSave()
    }, AUTOSAVE_DEBOUNCE_MS)
  }

  function update<K extends keyof EnquiryForm>(key: K, value: EnquiryForm[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
    setSaved(false)
    setError(null)
    scheduleAutosave()
  }

  // Flush a pending debounce immediately (used on text-field blur so leaving a
  // field saves without waiting for the debounce).
  function flushNow() {
    clearDebounce()
    requestSave()
  }

  // Explicit Save button: flush the debounce and save now. If nothing changed,
  // just reassure with the saved confirmation rather than a redundant network call.
  function handleManualSave() {
    if (lockedRef.current) return
    clearDebounce()
    const body = buildEnquiryBody(formRef.current)
    if (JSON.stringify(body) === lastSavedBodyRef.current) {
      if (!savingRef.current && mountedRef.current) setSaved(true)
      return
    }
    if (savingRef.current) {
      pendingBodyRef.current = body
      return
    }
    void runSave(body)
  }

  // Flush the latest dirty edit when the section truly unmounts — i.e. the order
  // changes or the user leaves the workspace/page (CustomerTab is kept mounted
  // across top-level tab switches, so this no longer fires on a normal tab switch).
  // The debounce already covers in-tab/in-sub-tab edits. Fire-and-forget: the lift
  // target (workspace) outlives this component. No prop re-seed effect exists.
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      clearDebounce()
      if (lockedRef.current) return
      const body = buildEnquiryBody(formRef.current)
      if (JSON.stringify(body) === lastSavedBodyRef.current) return
      if (savingRef.current) {
        // A save is in flight — its drain will pick up this latest body.
        pendingBodyRef.current = body
        return
      }
      // Capture the order id before sending; never read orderIdRef.current inside
      // the .then (the order may have changed by the time it resolves).
      const saveOrderId = orderIdRef.current
      saveEnquiry(saveOrderId, body)
        .then((res) =>
          onSavedRef.current({ orderId: saveOrderId, enquiry: res.data.enquiry }),
        )
        .catch(() => {})
    }
  }, [])

  // Autosave must NOT disable inputs while a background save runs — only LAID does.
  const inputsDisabled = locked

  return (
    <div className="space-y-8">
      <div>
        <h3 className="text-sm font-semibold text-slate-900">Lead Enquiry</h3>
      </div>

      <section>
        <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
          Lead Type
        </h4>
        <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
          {LEAD_TYPE_OPTIONS.map((opt) => (
            <CheckboxField
              key={opt.value}
              id={`enquiry_lead_type_${opt.value}`}
              label={opt.label}
              checked={form.lead_type === opt.value}
              // Single-select: checking sets this value (others derive unchecked
              // from lead_type); clicking the selected one clears to null.
              onChange={(checked) =>
                update('lead_type', checked ? opt.value : null)
              }
              disabled={inputsDisabled}
            />
          ))}
        </div>
      </section>

      <section>
        <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
          Products looking for
        </h4>
        <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
          <CheckboxField
            id="enquiry_carpet"
            label="Carpet"
            checked={form.carpet}
            onChange={(v) => update('carpet', v)}
            disabled={inputsDisabled}
          />
          <CheckboxField
            id="enquiry_hard_floor"
            label="Hard floor"
            checked={form.hard_floor}
            onChange={(v) => update('hard_floor', v)}
            disabled={inputsDisabled}
          />
        </div>
      </section>

      <section>
        <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
          Requirements
        </h4>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Field label="When did you want the product fitted?" htmlFor="enquiry_fit_timing">
            <Input
              id="enquiry_fit_timing"
              type="text"
              value={form.product_fit_timing}
              onChange={(e) => update('product_fit_timing', e.target.value)}
              onBlur={flushNow}
              disabled={inputsDisabled}
            />
          </Field>
          <Field label="What areas/rooms are we covering?" htmlFor="enquiry_rooms">
            <Input
              id="enquiry_rooms"
              type="text"
              value={form.rooms_to_cover}
              onChange={(e) => update('rooms_to_cover', e.target.value)}
              onBlur={flushNow}
              disabled={inputsDisabled}
            />
          </Field>
          <Field label="Textured or plain?" htmlFor="enquiry_textured">
            <Input
              id="enquiry_textured"
              type="text"
              value={form.textured_or_plain}
              onChange={(e) => update('textured_or_plain', e.target.value)}
              onBlur={flushNow}
              disabled={inputsDisabled}
            />
          </Field>
          <Field label="Light or dark tones?" htmlFor="enquiry_tones">
            <Input
              id="enquiry_tones"
              type="text"
              value={form.light_or_dark_tones}
              onChange={(e) => update('light_or_dark_tones', e.target.value)}
              onBlur={flushNow}
              disabled={inputsDisabled}
            />
          </Field>
          <Field label="What colours interest you?" htmlFor="enquiry_colours">
            <Input
              id="enquiry_colours"
              type="text"
              value={form.colours_interested}
              onChange={(e) => update('colours_interested', e.target.value)}
              onBlur={flushNow}
              disabled={inputsDisabled}
            />
          </Field>
          <Field
            label="What usage do the products get, who lives there?"
            htmlFor="enquiry_usage"
          >
            <Input
              id="enquiry_usage"
              type="text"
              value={form.product_usage_context}
              onChange={(e) => update('product_usage_context', e.target.value)}
              onBlur={flushNow}
              disabled={inputsDisabled}
            />
          </Field>
          <Field
            label="What is there currently, why are you changing it?"
            htmlFor="enquiry_current"
          >
            <Textarea
              id="enquiry_current"
              rows={2}
              value={form.current_flooring_and_reason}
              onChange={(e) =>
                update('current_flooring_and_reason', e.target.value)
              }
              onBlur={flushNow}
              disabled={inputsDisabled}
            />
          </Field>
          <Field label="Products discussed" htmlFor="enquiry_products_discussed">
            <Textarea
              id="enquiry_products_discussed"
              rows={2}
              value={form.products_discussed}
              onChange={(e) => update('products_discussed', e.target.value)}
              onBlur={flushNow}
              disabled={inputsDisabled}
            />
          </Field>
        </div>
      </section>

      <section>
        <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
          Installation
        </h4>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <YesNoField
            label="Fully installed?"
            value={form.fully_installed}
            onChange={(v) => update('fully_installed', v)}
            disabled={inputsDisabled}
          />
          <YesNoField
            label="Uplift?"
            value={form.uplift}
            onChange={(v) => update('uplift', v)}
            disabled={inputsDisabled}
          />
          <YesNoField
            label="Furniture?"
            value={form.furniture}
            onChange={(v) => update('furniture', v)}
            disabled={inputsDisabled}
          />
          <YesNoField
            label="Stairs?"
            value={form.stairs}
            onChange={(v) => update('stairs', v)}
            disabled={inputsDisabled}
          />
        </div>
      </section>

      <section>
        <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
          Subfloor
        </h4>
        <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
          <CheckboxField
            id="enquiry_subfloor_concrete"
            label="Concrete"
            checked={form.subfloor_concrete}
            onChange={(v) => update('subfloor_concrete', v)}
            disabled={inputsDisabled}
          />
          <CheckboxField
            id="enquiry_subfloor_timber"
            label="Timber"
            checked={form.subfloor_timber}
            onChange={(v) => update('subfloor_timber', v)}
            disabled={inputsDisabled}
          />
          <CheckboxField
            id="enquiry_subfloor_tile"
            label="Tile"
            checked={form.subfloor_tile}
            onChange={(v) => update('subfloor_tile', v)}
            disabled={inputsDisabled}
          />
        </div>
      </section>

      <section>
        <Field label="Brief summary" htmlFor="enquiry_summary">
          <Textarea
            id="enquiry_summary"
            rows={3}
            value={form.brief_summary}
            onChange={(e) => update('brief_summary', e.target.value)}
            onBlur={flushNow}
            disabled={inputsDisabled}
          />
        </Field>
      </section>

      <div className="mt-2 pt-5 border-t border-slate-200 space-y-4">
        {locked && (
          <p className="text-xs text-slate-500">
            This order is laid and locked. Editing is disabled.
          </p>
        )}

        {!locked && saving && (
          <div className="inline-flex items-center gap-1.5 text-xs text-slate-500">
            Saving…
          </div>
        )}

        {!locked && !saving && saved && (
          <div className="inline-flex items-center gap-1.5 text-xs text-teal-700">
            <CheckCircleIcon className="w-3.5 h-3.5" />
            Lead Enquiry saved.
          </div>
        )}

        {error && (
          <div className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-xs text-amber-900">
            {error}
          </div>
        )}

        <div className="flex justify-end">
          <Button
            type="button"
            variant="success"
            size="md"
            onClick={handleManualSave}
            disabled={locked || saving}
          >
            {saving ? 'Saving…' : 'Save Lead Enquiry'}
          </Button>
        </div>
      </div>
    </div>
  )
}
