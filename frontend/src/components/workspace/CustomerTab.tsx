import { useEffect, useRef, useState } from 'react'
import { Button } from '../ui/Button'
import { Field } from '../ui/Field'
import { Input } from '../ui/Input'
import { Tabs } from '../ui/Tabs'
import { CheckCircleIcon } from '../icons'
import { LeadEnquirySection } from './LeadEnquirySection'
import { ApiError } from '../../lib/api/ApiError'
import {
  copyBillingFromInstallation,
  saveBillingAddress,
  saveCustomer,
  saveInstallationAddress,
} from '../../lib/api/orderWorkspaceApi'
import type {
  AddressUpsertRequest,
  CustomerSaveRequest,
  OrderAddress,
  OrderCustomer,
  OrderEnquiry,
} from '../../lib/api/orderWorkspaceApi'

// Confirmed server-saved data lifted to the parent, one section at a time as
// each call succeeds (so customer/installation survive even when a later
// billing step fails). Each field is optional and always sourced from the
// server response — billing from the billing PUT / copy response, never the
// local form.
export type CustomerSavedPayload = {
  customer?: OrderCustomer
  installAddress?: OrderAddress
  billingAddress?: OrderAddress
  // Phase 15F: confirmed lead-enquiry row, lifted through the same channel so the
  // workspace stays in sync without a refetch.
  enquiry?: OrderEnquiry
}

type Props = {
  orderId: number
  locked: boolean
  customer?: OrderCustomer | null
  installationAddress?: OrderAddress | null
  billingAddress?: OrderAddress | null
  enquiry?: OrderEnquiry | null
  onSaved: (saved: CustomerSavedPayload) => void
}

type CustomerForm = {
  first_name: string
  middle_name: string
  last_name: string
  email: string
  mobile: string
  home_phone: string
  work_phone: string
  company_name: string
}

type AddressForm = {
  unit_number: string
  street_number: string
  street: string
  suburb: string
  state_code: string
  postcode: string
}

type SubTabId = 'details' | 'addresses' | 'enquiry'

const SUB_TABS: Array<{ id: SubTabId; label: string }> = [
  { id: 'details', label: 'Details' },
  { id: 'addresses', label: 'Addresses' },
  { id: 'enquiry', label: 'Lead Enquiry' },
]

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

// Australian mobile, accepted in exactly two shapes: local 04XXXXXXXX (10 chars)
// or international +614XXXXXXXX (12 chars). Strict client-side UX validation;
// the backend remains the unchanged backstop.
const MOBILE_LOCAL_REGEX = /^04\d{8}$/
const MOBILE_INTL_REGEX = /^\+614\d{8}$/
const MOBILE_ERROR = 'Enter a valid mobile number.'

// One Save click runs up to three sequential calls — customer, installation,
// then billing (a PUT, or a copy-from-installation POST when "same as
// installation" is checked). Each section reports its own outcome so a partial
// failure shows exactly what persisted; "billing" covers both step-3 variants.
type SaveSection = 'customer' | 'installation' | 'billing'
type SectionOutcome = 'saved' | 'failed' | 'skipped'

type SaveRun = {
  ok: boolean
  customer: SectionOutcome
  installation: SectionOutcome
  billing: SectionOutcome
  failedSection: SaveSection | null
  failedMessage: string | null
}

const SAVE_SEQUENCE: SaveSection[] = ['customer', 'installation', 'billing']

const SECTION_LABELS: Record<SaveSection, string> = {
  customer: 'Customer',
  installation: 'Installation address',
  billing: 'Billing address',
}

const SECTION_ERROR_PREFIX: Record<SaveSection, string> = {
  customer: 'customer_',
  installation: 'install_',
  billing: 'billing_',
}

const FULL_SUCCESS_RUN: SaveRun = {
  ok: true,
  customer: 'saved',
  installation: 'saved',
  billing: 'saved',
  failedSection: null,
  failedMessage: null,
}

function customerFromProps(c?: OrderCustomer | null): CustomerForm {
  return {
    first_name: c?.first_name ?? '',
    middle_name: c?.middle_name ?? '',
    last_name: c?.last_name ?? '',
    email: c?.email ?? '',
    mobile: c?.mobile ?? '',
    home_phone: c?.home_phone ?? '',
    work_phone: c?.work_phone ?? '',
    company_name: c?.company_name ?? '',
  }
}

function addressFromProps(a?: OrderAddress | null): AddressForm {
  return {
    unit_number: a?.unit_number ?? '',
    street_number: a?.street_number ?? '',
    street: a?.street ?? '',
    suburb: a?.suburb ?? '',
    state_code: a?.state_code ?? '',
    postcode: a?.postcode ?? '',
  }
}

const ADDRESS_FIELDS: Array<keyof OrderAddress> = [
  'unit_number',
  'street_number',
  'street',
  'suburb',
  'state_code',
  'postcode',
]

// Normalise a field for comparison: null/undefined/empty-string all collapse to
// the same blank value, and surrounding whitespace is ignored.
function blankNorm(value: string | null | undefined): string {
  return (value ?? '').trim()
}

// True when two addresses match across all 6 fields under blank-normalised,
// trimmed comparison. A null/undefined address compares as all-blank.
function addressesEqual(
  a: OrderAddress | null | undefined,
  b: OrderAddress | null | undefined,
): boolean {
  return ADDRESS_FIELDS.every(
    (field) => blankNorm(a?.[field]) === blankNorm(b?.[field]),
  )
}

// Optional text field: trim, then collapse blank/whitespace to null so a
// full-replace PUT clears it rather than tripping the backend non-blank check.
function optionalField(value: string): string | null {
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function buildCustomerBody(form: CustomerForm): CustomerSaveRequest {
  return {
    first_name: form.first_name.trim(),
    middle_name: optionalField(form.middle_name),
    last_name: form.last_name.trim(),
    email: form.email.trim(),
    mobile: form.mobile.trim(),
    home_phone: optionalField(form.home_phone),
    work_phone: optionalField(form.work_phone),
    company_name: optionalField(form.company_name),
  }
}

function buildAddressBody(form: AddressForm): AddressUpsertRequest {
  return {
    unit_number: optionalField(form.unit_number),
    street_number: form.street_number.trim(),
    street: form.street.trim(),
    suburb: form.suburb.trim(),
    state_code: form.state_code.trim(),
    postcode: form.postcode.trim(),
  }
}

function apiErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.message.length > 0) return err.message
  return 'Something went wrong while saving. Please try again.'
}

// Best-effort map of a backend VALIDATION_FAILED details[] onto this tab's
// existing per-field error keys ({section}_{snake_field}). Anything that does
// not line up with a field still surfaces via the section-level message.
function mapDetailsToFieldErrors(
  section: SaveSection,
  err: unknown,
): Record<string, string> {
  const result: Record<string, string> = {}
  if (!(err instanceof ApiError) || err.code !== 'VALIDATION_FAILED') return result
  const details = err.details
  if (!Array.isArray(details)) return result
  const prefix = SECTION_ERROR_PREFIX[section]
  for (const detail of details) {
    if (!detail || typeof detail !== 'object') continue
    const field = (detail as { field?: unknown }).field
    const message = (detail as { message?: unknown }).message
    if (typeof field !== 'string' || field.length === 0) continue
    result[`${prefix}${field}`] =
      typeof message === 'string' && message.length > 0 ? message : 'Invalid value.'
  }
  return result
}

// A failure at `section` means earlier sequence steps already committed
// (server-side, not rolled back), this step failed, and later steps never ran.
function buildFailureRun(section: SaveSection, message: string): SaveRun {
  const failedIndex = SAVE_SEQUENCE.indexOf(section)
  const outcomeFor = (s: SaveSection): SectionOutcome => {
    const i = SAVE_SEQUENCE.indexOf(s)
    if (i < failedIndex) return 'saved'
    if (i === failedIndex) return 'failed'
    return 'skipped'
  }
  return {
    ok: false,
    customer: outcomeFor('customer'),
    installation: outcomeFor('installation'),
    billing: outcomeFor('billing'),
    failedSection: section,
    failedMessage: message,
  }
}

function outcomeLabel(outcome: SectionOutcome): string {
  if (outcome === 'saved') return 'Saved'
  if (outcome === 'failed') return 'Failed'
  return 'Did not run'
}

export function CustomerTab({
  orderId,
  locked,
  customer,
  installationAddress,
  billingAddress,
  enquiry,
  onSaved,
}: Props) {
  const [customerForm, setCustomerForm] = useState<CustomerForm>(() =>
    customerFromProps(customer),
  )
  const [installForm, setInstallForm] = useState<AddressForm>(() =>
    addressFromProps(installationAddress),
  )
  // Default "same as installation" checked when there is no saved billing
  // address, OR when the saved billing address equals the saved installation
  // address (blank-normalised, trimmed, across all 6 fields). Default unchecked
  // only when a saved billing address exists AND differs from installation —
  // that distinct address must be preserved and shown, never silently
  // overwritten. Seed the form from installation when checked, from the saved
  // billing address when unchecked.
  const hasSavedBilling = billingAddress != null
  const defaultSameAsInstall =
    !hasSavedBilling || addressesEqual(billingAddress, installationAddress)
  const [billingForm, setBillingForm] = useState<AddressForm>(() =>
    defaultSameAsInstall
      ? addressFromProps(installationAddress)
      : addressFromProps(billingAddress),
  )
  const [sameAsInstall, setSameAsInstall] = useState(defaultSameAsInstall)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [saveRun, setSaveRun] = useState<SaveRun | null>(null)
  const [activeSubTab, setActiveSubTab] = useState<SubTabId>('details')

  // Guards setState after the tab unmounts (e.g. the order route changes while
  // a multi-call save is still in flight).
  const mountedRef = useRef(true)
  useEffect(() => {
    // Reset on setup so a StrictMode setup→cleanup→setup cycle (or any remount)
    // leaves the ref true for the live component; cleanup marks a real unmount.
    // Without this reset the ref can stick at false and strand `saving`/forms.
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

  function clearErrorsWithPrefix(prefix: string) {
    setErrors((prev) => {
      let changed = false
      const next: Record<string, string> = {}
      for (const [k, v] of Object.entries(prev)) {
        if (k.startsWith(prefix)) {
          changed = true
        } else {
          next[k] = v
        }
      }
      return changed ? next : prev
    })
  }

  function updateCustomer<K extends keyof CustomerForm>(
    key: K,
    value: CustomerForm[K],
  ) {
    setCustomerForm((prev) => ({ ...prev, [key]: value }))
    clearError(`customer_${key}`)
    setSaveRun(null)
  }

  function updateInstall<K extends keyof AddressForm>(
    key: K,
    value: AddressForm[K],
  ) {
    setInstallForm((prev) => ({ ...prev, [key]: value }))
    if (sameAsInstall) {
      setBillingForm((prev) => ({ ...prev, [key]: value }))
    }
    clearError(`install_${key}`)
    setSaveRun(null)
  }

  function updateBilling<K extends keyof AddressForm>(
    key: K,
    value: AddressForm[K],
  ) {
    setBillingForm((prev) => ({ ...prev, [key]: value }))
    clearError(`billing_${key}`)
    setSaveRun(null)
  }

  function toggleSameAsInstall(checked: boolean) {
    setSameAsInstall(checked)
    if (checked) {
      setBillingForm({ ...installForm })
      clearErrorsWithPrefix('billing_')
    }
    setSaveRun(null)
  }

  function validateForms(): Record<string, string> {
    const newErrors: Record<string, string> = {}

    if (!customerForm.first_name.trim()) {
      newErrors.customer_first_name = 'First name is required.'
    }
    if (!customerForm.last_name.trim()) {
      newErrors.customer_last_name = 'Last name is required.'
    }
    const mobileTrim = customerForm.mobile.trim()
    if (!mobileTrim) {
      newErrors.customer_mobile = 'Mobile number is required.'
    } else if (
      !MOBILE_LOCAL_REGEX.test(mobileTrim) &&
      !MOBILE_INTL_REGEX.test(mobileTrim)
    ) {
      newErrors.customer_mobile = MOBILE_ERROR
    }
    const emailTrim = customerForm.email.trim()
    if (!emailTrim) {
      newErrors.customer_email = 'Email is required.'
    } else if (!EMAIL_REGEX.test(emailTrim)) {
      newErrors.customer_email = 'Enter a valid email address.'
    }

    if (!installForm.street_number.trim()) {
      newErrors.install_street_number = 'Street number is required.'
    }
    if (!installForm.street.trim()) {
      newErrors.install_street = 'Street is required.'
    }
    if (!installForm.suburb.trim()) {
      newErrors.install_suburb = 'Suburb is required.'
    }
    if (!installForm.state_code.trim()) {
      newErrors.install_state_code = 'State is required.'
    }
    if (!installForm.postcode.trim()) {
      newErrors.install_postcode = 'Postcode is required.'
    }

    if (!sameAsInstall) {
      if (!billingForm.street_number.trim()) {
        newErrors.billing_street_number = 'Street number is required.'
      }
      if (!billingForm.street.trim()) {
        newErrors.billing_street = 'Street is required.'
      }
      if (!billingForm.suburb.trim()) {
        newErrors.billing_suburb = 'Suburb is required.'
      }
      if (!billingForm.state_code.trim()) {
        newErrors.billing_state_code = 'State is required.'
      }
      if (!billingForm.postcode.trim()) {
        newErrors.billing_postcode = 'Postcode is required.'
      }
    }

    return newErrors
  }

  async function handleSave() {
    if (locked || saving) return

    // Strict client validation first — no network call when the form is invalid.
    const validationErrors = validateForms()
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors)
      setSaveRun(null)
      const hasCustomerErrors = Object.keys(validationErrors).some((k) =>
        k.startsWith('customer_'),
      )
      const hasAddressErrors = Object.keys(validationErrors).some(
        (k) => k.startsWith('install_') || k.startsWith('billing_'),
      )
      if (hasCustomerErrors) {
        setActiveSubTab('details')
      } else if (hasAddressErrors) {
        setActiveSubTab('addresses')
      }
      return
    }
    setErrors({})

    // Always send the complete field set (full-replace PUT). Optionals collapse
    // blank -> null here so omitted-as-empty does not get written as "".
    const customerBody = buildCustomerBody(customerForm)
    const installBody = buildAddressBody(installForm)
    const billingBody = buildAddressBody(billingForm)
    const copyBilling = sameAsInstall

    setSaving(true)
    setSaveRun(null)

    // Sequential, stop-on-first-failure. Earlier successes are NOT rolled back
    // (no cross-endpoint transaction), so partial state must be surfaced.
    const fail = (section: SaveSection, err: unknown) => {
      if (!mountedRef.current) return
      const fieldErrors = mapDetailsToFieldErrors(section, err)
      if (Object.keys(fieldErrors).length > 0) {
        setErrors((prev) => ({ ...prev, ...fieldErrors }))
      }
      setSaveRun(buildFailureRun(section, apiErrorMessage(err)))
      setActiveSubTab(section === 'customer' ? 'details' : 'addresses')
    }

    try {
      // 1. Customer.
      let savedCustomer: OrderCustomer
      try {
        const res = await saveCustomer(orderId, customerBody)
        savedCustomer = res.data.customer
      } catch (err) {
        fail('customer', err)
        return
      }
      if (!mountedRef.current) return
      setCustomerForm(customerFromProps(savedCustomer))
      // Lift the confirmed customer row up immediately — it must survive even if
      // a later section (installation/billing) fails in this same run.
      onSaved({ customer: savedCustomer })

      // 2. Installation address (must persist before any copy-from-installation).
      let savedInstall: OrderAddress
      try {
        const res = await saveInstallationAddress(orderId, installBody)
        savedInstall = res.data.install_address
      } catch (err) {
        fail('installation', err)
        return
      }
      if (!mountedRef.current) return
      setInstallForm(addressFromProps(savedInstall))
      // Lift the confirmed installation row up — survives a later billing failure.
      onSaved({ installAddress: savedInstall })

      // 3. Billing — copy-from-installation when "same as installation" is
      //    checked (the installation row exists from step 2), otherwise a full
      //    billing PUT. Either variant reports as the single "Billing" section.
      let savedBilling: OrderAddress
      try {
        const res = copyBilling
          ? await copyBillingFromInstallation(orderId)
          : await saveBillingAddress(orderId, billingBody)
        savedBilling = res.data.billing_address
      } catch (err) {
        fail('billing', err)
        return
      }
      if (!mountedRef.current) return
      setBillingForm(addressFromProps(savedBilling))
      // Lift the confirmed billing row up.
      onSaved({ billingAddress: savedBilling })

      // Every required call in this run succeeded — only now a global success.
      // Parent state was already updated per-section above (per-step only —
      // never a combined call here as well).
      setSaveRun(FULL_SUCCESS_RUN)
    } finally {
      if (mountedRef.current) setSaving(false)
    }
  }

  const editingDisabled = locked || saving
  const billingDisabled = editingDisabled || sameAsInstall

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
          Customer
        </h2>
        <p className="text-sm text-slate-500 mt-1">
          Enter customer details and addresses.
        </p>
      </div>

      <div className="mb-6">
        <Tabs
          tabs={SUB_TABS}
          active={activeSubTab}
          onChange={setActiveSubTab}
        />
      </div>

      {activeSubTab === 'details' && (
        <section>
          <h3 className="text-sm font-semibold text-slate-900 mb-4">
            Customer details
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            <Field
              label="First name"
              htmlFor="first_name"
              error={errors.customer_first_name}
            >
              <Input
                id="first_name"
                type="text"
                autoComplete="given-name"
                value={customerForm.first_name}
                onChange={(e) => updateCustomer('first_name', e.target.value)}
                invalid={!!errors.customer_first_name}
                disabled={editingDisabled}
              />
            </Field>
            <Field label="Middle name" htmlFor="middle_name">
              <Input
                id="middle_name"
                type="text"
                autoComplete="additional-name"
                value={customerForm.middle_name}
                onChange={(e) =>
                  updateCustomer('middle_name', e.target.value)
                }
                disabled={editingDisabled}
              />
            </Field>
            <Field
              label="Last name"
              htmlFor="last_name"
              error={errors.customer_last_name}
            >
              <Input
                id="last_name"
                type="text"
                autoComplete="family-name"
                value={customerForm.last_name}
                onChange={(e) => updateCustomer('last_name', e.target.value)}
                invalid={!!errors.customer_last_name}
                disabled={editingDisabled}
              />
            </Field>
            <Field
              label="Email"
              htmlFor="email"
              error={errors.customer_email}
            >
              <Input
                id="email"
                type="email"
                autoComplete="email"
                value={customerForm.email}
                onChange={(e) => updateCustomer('email', e.target.value)}
                invalid={!!errors.customer_email}
                disabled={editingDisabled}
              />
            </Field>
            <Field
              label="Mobile number"
              htmlFor="mobile_number"
              error={errors.customer_mobile}
            >
              <Input
                id="mobile_number"
                type="tel"
                autoComplete="tel"
                value={customerForm.mobile}
                onChange={(e) => updateCustomer('mobile', e.target.value)}
                invalid={!!errors.customer_mobile}
                disabled={editingDisabled}
              />
            </Field>
            <Field label="Home number" htmlFor="home_number">
              <Input
                id="home_number"
                type="tel"
                autoComplete="tel-national"
                value={customerForm.home_phone}
                onChange={(e) =>
                  updateCustomer('home_phone', e.target.value)
                }
                disabled={editingDisabled}
              />
            </Field>
            <Field label="Work number" htmlFor="work_number">
              <Input
                id="work_number"
                type="tel"
                value={customerForm.work_phone}
                onChange={(e) =>
                  updateCustomer('work_phone', e.target.value)
                }
                disabled={editingDisabled}
              />
            </Field>
            <Field label="Company" htmlFor="company">
              <Input
                id="company"
                type="text"
                autoComplete="organization"
                value={customerForm.company_name}
                onChange={(e) =>
                  updateCustomer('company_name', e.target.value)
                }
                disabled={editingDisabled}
              />
            </Field>
          </div>
        </section>
      )}

      {activeSubTab === 'addresses' && (
        <div className="space-y-8">
          <section>
            <h3 className="text-sm font-semibold text-slate-900 mb-4">
              Installation address
            </h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
              <Field label="Unit number" htmlFor="install_unit_number">
                <Input
                  id="install_unit_number"
                  type="text"
                  value={installForm.unit_number}
                  onChange={(e) =>
                    updateInstall('unit_number', e.target.value)
                  }
                  disabled={editingDisabled}
                />
              </Field>
              <Field
                label="Street number"
                htmlFor="install_street_number"
                error={errors.install_street_number}
              >
                <Input
                  id="install_street_number"
                  type="text"
                  value={installForm.street_number}
                  onChange={(e) =>
                    updateInstall('street_number', e.target.value)
                  }
                  invalid={!!errors.install_street_number}
                  disabled={editingDisabled}
                />
              </Field>
              <Field
                label="Street"
                htmlFor="install_street"
                error={errors.install_street}
              >
                <Input
                  id="install_street"
                  type="text"
                  value={installForm.street}
                  onChange={(e) => updateInstall('street', e.target.value)}
                  invalid={!!errors.install_street}
                  disabled={editingDisabled}
                />
              </Field>
              <Field
                label="Suburb"
                htmlFor="install_suburb"
                error={errors.install_suburb}
              >
                <Input
                  id="install_suburb"
                  type="text"
                  value={installForm.suburb}
                  onChange={(e) => updateInstall('suburb', e.target.value)}
                  invalid={!!errors.install_suburb}
                  disabled={editingDisabled}
                />
              </Field>
              <Field
                label="State"
                htmlFor="install_state"
                error={errors.install_state_code}
              >
                <Input
                  id="install_state"
                  type="text"
                  value={installForm.state_code}
                  onChange={(e) =>
                    updateInstall('state_code', e.target.value)
                  }
                  invalid={!!errors.install_state_code}
                  disabled={editingDisabled}
                />
              </Field>
              <Field
                label="Postcode"
                htmlFor="install_postcode"
                error={errors.install_postcode}
              >
                <Input
                  id="install_postcode"
                  type="text"
                  inputMode="numeric"
                  value={installForm.postcode}
                  onChange={(e) =>
                    updateInstall('postcode', e.target.value)
                  }
                  invalid={!!errors.install_postcode}
                  disabled={editingDisabled}
                />
              </Field>
            </div>
          </section>

          <section>
            <h3 className="text-sm font-semibold text-slate-900 mb-4">
              Billing address
            </h3>
            <label className="inline-flex items-center gap-2 text-sm text-slate-700 mb-4 cursor-pointer">
              <input
                type="checkbox"
                checked={sameAsInstall}
                onChange={(e) => toggleSameAsInstall(e.target.checked)}
                disabled={editingDisabled}
                className="h-4 w-4 rounded border-slate-300 text-teal-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30"
              />
              <span>Same as installation address</span>
            </label>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
              <Field label="Unit number" htmlFor="billing_unit_number">
                <Input
                  id="billing_unit_number"
                  type="text"
                  value={billingForm.unit_number}
                  onChange={(e) =>
                    updateBilling('unit_number', e.target.value)
                  }
                  disabled={billingDisabled}
                />
              </Field>
              <Field
                label="Street number"
                htmlFor="billing_street_number"
                error={errors.billing_street_number}
              >
                <Input
                  id="billing_street_number"
                  type="text"
                  value={billingForm.street_number}
                  onChange={(e) =>
                    updateBilling('street_number', e.target.value)
                  }
                  disabled={billingDisabled}
                  invalid={!!errors.billing_street_number}
                />
              </Field>
              <Field
                label="Street"
                htmlFor="billing_street"
                error={errors.billing_street}
              >
                <Input
                  id="billing_street"
                  type="text"
                  value={billingForm.street}
                  onChange={(e) =>
                    updateBilling('street', e.target.value)
                  }
                  disabled={billingDisabled}
                  invalid={!!errors.billing_street}
                />
              </Field>
              <Field
                label="Suburb"
                htmlFor="billing_suburb"
                error={errors.billing_suburb}
              >
                <Input
                  id="billing_suburb"
                  type="text"
                  value={billingForm.suburb}
                  onChange={(e) =>
                    updateBilling('suburb', e.target.value)
                  }
                  disabled={billingDisabled}
                  invalid={!!errors.billing_suburb}
                />
              </Field>
              <Field
                label="State"
                htmlFor="billing_state"
                error={errors.billing_state_code}
              >
                <Input
                  id="billing_state"
                  type="text"
                  value={billingForm.state_code}
                  onChange={(e) =>
                    updateBilling('state_code', e.target.value)
                  }
                  disabled={billingDisabled}
                  invalid={!!errors.billing_state_code}
                />
              </Field>
              <Field
                label="Postcode"
                htmlFor="billing_postcode"
                error={errors.billing_postcode}
              >
                <Input
                  id="billing_postcode"
                  type="text"
                  inputMode="numeric"
                  value={billingForm.postcode}
                  onChange={(e) =>
                    updateBilling('postcode', e.target.value)
                  }
                  disabled={billingDisabled}
                  invalid={!!errors.billing_postcode}
                />
              </Field>
            </div>
          </section>
        </div>
      )}

      {/* Kept mounted (toggled with `hidden`) so unsaved enquiry edits survive a
          sub-tab switch, the same way the customer/address fields persist via
          CustomerTab state. */}
      <div className={activeSubTab === 'enquiry' ? '' : 'hidden'}>
        <LeadEnquirySection
          orderId={orderId}
          locked={locked}
          enquiry={enquiry}
          onSaved={(savedEnquiry) => onSaved({ enquiry: savedEnquiry })}
        />
      </div>

      {/* Customer/address save footer — only for the Details/Addresses sub-tabs.
          The Lead enquiry sub-tab has its own separate Save Lead Enquiry button. */}
      {activeSubTab !== 'enquiry' && (
      <div className="mt-8 pt-5 border-t border-slate-200 space-y-4">
        {locked && (
          <p className="text-xs text-slate-500">
            This order is laid and locked. Editing is disabled.
          </p>
        )}

        {saveRun?.ok && (
          <div className="inline-flex items-center gap-1.5 text-xs text-teal-700">
            <CheckCircleIcon className="w-3.5 h-3.5" />
            Customer and addresses saved.
          </div>
        )}

        {saveRun && !saveRun.ok && (
          <div className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-xs text-amber-900">
            <p className="font-semibold">Some sections didn’t save</p>
            <ul className="mt-2 space-y-0.5">
              <li>
                {SECTION_LABELS.customer} — {outcomeLabel(saveRun.customer)}
              </li>
              <li>
                {SECTION_LABELS.installation} —{' '}
                {outcomeLabel(saveRun.installation)}
              </li>
              <li>
                {SECTION_LABELS.billing} — {outcomeLabel(saveRun.billing)}
              </li>
            </ul>
            {saveRun.failedSection && saveRun.failedMessage && (
              <p className="mt-2">
                {SECTION_LABELS[saveRun.failedSection]}:{' '}
                {saveRun.failedMessage}
              </p>
            )}
            <p className="mt-2 text-amber-700">
              Sections marked “Saved” are stored on the server and don’t need
              re-entering.
            </p>
          </div>
        )}

        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <p className="text-[11px] text-slate-500">
            Customer and billing address details are required before creating
            an invoice.
          </p>
          <Button
            type="button"
            variant="success"
            size="md"
            onClick={handleSave}
            disabled={editingDisabled}
          >
            {saving ? 'Saving…' : 'Save customer'}
          </Button>
        </div>
      </div>
      )}
    </div>
  )
}
