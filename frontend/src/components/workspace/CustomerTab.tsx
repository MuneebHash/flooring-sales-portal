import { useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'
import { Field } from '../ui/Field'
import { Input } from '../ui/Input'
import { Tabs } from '../ui/Tabs'
import { CheckCircleIcon } from '../icons'
import { LeadEnquirySection } from './LeadEnquirySection'
import { ApiError } from '../../lib/api/ApiError'
import type { ApiSuccess } from '../../lib/api/types'
import {
  copyBillingFromInstallation,
  saveBillingAddress,
  saveCustomer,
  saveInstallationAddress,
} from '../../lib/api/orderWorkspaceApi'
import type {
  AddressUpsertRequest,
  BillingAddressResponse,
  CustomerSaveRequest,
  CustomerSaveResponse,
  InstallationAddressResponse,
  OrderAddress,
  OrderCustomer,
  OrderEnquiry,
} from '../../lib/api/orderWorkspaceApi'
import { registerPendingSave } from '../../lib/pendingSaveRegistry'
import {
  useSectionAutosave,
  type SectionAutosave,
} from '../../lib/useSectionAutosave'

// Confirmed server-saved data lifted to the parent. The customer, installation and
// billing sections autosave independently (Batch A #107), so each confirmed row is
// lifted on its own as its save succeeds; an invalid or failing section never holds
// another back, except that a ticked billing copy waits for a successful installation
// save. Each field is optional and always sourced from the server response —
// billing from the billing PUT / copy response, never the local form. CustomerTab
// lifts only while mounted (Lead Enquiry, which shares this channel, also lifts the
// result of its unmount flush).
//
// orderId is the id the save was ADDRESSED to (captured before the await). The
// parent uses it to drop late callbacks from a previous order — see
// ExistingOrderWorkspace.handleCustomerSaved.
export type CustomerSavedPayload = {
  orderId: number
  customer?: OrderCustomer
  installAddress?: OrderAddress
  billingAddress?: OrderAddress
  // Phase 15F: confirmed lead-enquiry row, lifted through the same channel so the
  // workspace stays in sync without a refetch.
  enquiry?: OrderEnquiry
}

// Batch A #107 (D1): what the workspace shell calls before an action freezes or
// delivers customer data (quote preview / send / Create Invoice from the accepted
// quote, invoice create / rewrite, accept / re-send / Accept Remotely).
export type CustomerTabHandle = {
  // Flushes the customer, installation and billing autosaves (installation before any
  // billing copy) and awaits their full drains. Resolves true only when all three
  // sections are complete, valid and confirmed saved against the latest form; never
  // rejects.
  flushCustomerAutosave: () => Promise<boolean>
}

type Props = {
  orderId: number
  locked: boolean
  customer?: OrderCustomer | null
  installationAddress?: OrderAddress | null
  billingAddress?: OrderAddress | null
  enquiry?: OrderEnquiry | null
  onSaved: (saved: CustomerSavedPayload) => void
  ref?: Ref<CustomerTabHandle>
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

// The three independently autosaved sections. "billing" covers both the billing PUT
// and the copy-from-installation POST. Field error keys are {prefix}{snake_field}.
type SaveSection = 'customer' | 'installation' | 'billing'

const SECTION_LABELS: Record<SaveSection, string> = {
  customer: 'Customer details',
  installation: 'Installation address',
  billing: 'Billing address',
}

const SECTION_ERROR_PREFIX: Record<SaveSection, string> = {
  customer: 'customer_',
  installation: 'install_',
  billing: 'billing_',
}

const CUSTOMER_FIELDS: Array<keyof CustomerForm> = [
  'first_name',
  'middle_name',
  'last_name',
  'email',
  'mobile',
  'home_phone',
  'work_phone',
  'company_name',
]

// Batch A #107: the backend's VARCHAR limits (OrderService MAX_*), checked on the
// same trimmed values the backend checks, so an over-long value gets its field error
// before any request instead of a generic 400.
const CUSTOMER_MAX_LENGTHS: Record<keyof CustomerForm, number> = {
  first_name: 100,
  middle_name: 100,
  last_name: 100,
  email: 255,
  mobile: 20,
  home_phone: 20,
  work_phone: 20,
  company_name: 150,
}

const ADDRESS_MAX_LENGTHS: Record<keyof AddressForm, number> = {
  unit_number: 20,
  street_number: 20,
  street: 255,
  suburb: 100,
  state_code: 20,
  postcode: 10,
}

// The billing section's pending intent: copy the SAVED installation row ("same as
// installation"; `source` is the generation of the installation save the copy
// copies, from installSaveGenRef when the copy is built), or PUT the billing form.
type BillingIntent =
  | { kind: 'copy'; owedSeq: number; source: string }
  | { kind: 'put'; body: AddressUpsertRequest }

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

function maxLengthMessage(max: number): string {
  return `Must be at most ${max} characters.`
}

// Strict client validation of the customer section ({customer_<field>: message};
// empty when valid). Required fields and the email/mobile shapes first, then the
// length limit of every field that has no error yet.
function validateCustomer(form: CustomerForm): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!form.first_name.trim()) {
    errors.customer_first_name = 'First name is required.'
  }
  if (!form.last_name.trim()) {
    errors.customer_last_name = 'Last name is required.'
  }
  const mobileTrim = form.mobile.trim()
  if (!mobileTrim) {
    errors.customer_mobile = 'Mobile number is required.'
  } else if (
    !MOBILE_LOCAL_REGEX.test(mobileTrim) &&
    !MOBILE_INTL_REGEX.test(mobileTrim)
  ) {
    errors.customer_mobile = MOBILE_ERROR
  }
  const emailTrim = form.email.trim()
  if (!emailTrim) {
    errors.customer_email = 'Email is required.'
  } else if (!EMAIL_REGEX.test(emailTrim)) {
    errors.customer_email = 'Enter a valid email address.'
  }
  for (const field of CUSTOMER_FIELDS) {
    const key = `customer_${field}`
    if (key in errors) continue
    const max = CUSTOMER_MAX_LENGTHS[field]
    if (form[field].trim().length > max) errors[key] = maxLengthMessage(max)
  }
  return errors
}

// Strict client validation of one address section ({prefix}<field>: message).
function validateAddress(
  form: AddressForm,
  prefix: string,
): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!form.street_number.trim()) {
    errors[`${prefix}street_number`] = 'Street number is required.'
  }
  if (!form.street.trim()) {
    errors[`${prefix}street`] = 'Street is required.'
  }
  if (!form.suburb.trim()) {
    errors[`${prefix}suburb`] = 'Suburb is required.'
  }
  if (!form.state_code.trim()) {
    errors[`${prefix}state_code`] = 'State is required.'
  }
  if (!form.postcode.trim()) {
    errors[`${prefix}postcode`] = 'Postcode is required.'
  }
  for (const field of ADDRESS_FIELDS) {
    const key = `${prefix}${field}`
    if (key in errors) continue
    const max = ADDRESS_MAX_LENGTHS[field]
    if (form[field].trim().length > max) errors[key] = maxLengthMessage(max)
  }
  return errors
}

// The full-replace body of a VALID section, or null: an invalid section is never sent.
function validCustomerBody(form: CustomerForm): CustomerSaveRequest | null {
  return Object.keys(validateCustomer(form)).length === 0
    ? buildCustomerBody(form)
    : null
}

function validAddressBody(form: AddressForm): AddressUpsertRequest | null {
  return Object.keys(validateAddress(form, '')).length === 0
    ? buildAddressBody(form)
    : null
}

function apiErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.message.length > 0) return err.message
  return 'Something went wrong while saving. Please try again.'
}

// Best-effort map of a backend VALIDATION_FAILED details[] onto this tab's per-field
// error keys ({prefix}{snake_field}), kept only for fields whose current value still
// equals the value the FAILED body sent, so an error for an older body never attaches
// to input edited since. Anything that does not line up with a field still surfaces
// through the section's status message.
function mapDetailsToFieldErrors(
  prefix: string,
  err: unknown,
  sent: Record<string, string | null>,
  current: Record<string, string | null>,
): Record<string, string> {
  const result: Record<string, string> = {}
  if (!(err instanceof ApiError) || err.code !== 'VALIDATION_FAILED') return result
  const details = err.details
  if (!Array.isArray(details)) return result
  for (const detail of details) {
    if (!detail || typeof detail !== 'object') continue
    const field = (detail as { field?: unknown }).field
    const message = (detail as { message?: unknown }).message
    if (typeof field !== 'string' || field.length === 0) continue
    if (!(field in sent) || sent[field] !== current[field]) continue
    result[`${prefix}${field}`] =
      typeof message === 'string' && message.length > 0 ? message : 'Invalid value.'
  }
  return result
}

// Replace every error of one section (key prefix) with `next`; other sections keep theirs.
function replaceSectionErrors(
  prev: Record<string, string>,
  prefix: string,
  next: Record<string, string>,
): Record<string, string> {
  const merged: Record<string, string> = {}
  for (const [key, message] of Object.entries(prev)) {
    if (!key.startsWith(prefix)) merged[key] = message
  }
  return { ...merged, ...next }
}

function withoutError(
  prev: Record<string, string>,
  key: string,
): Record<string, string> {
  if (!(key in prev)) return prev
  const next = { ...prev }
  delete next[key]
  return next
}

export function CustomerTab({
  orderId,
  locked,
  customer,
  installationAddress,
  billingAddress,
  enquiry,
  onSaved,
  ref,
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
  // Client validation errors (shown on blur) and server field errors (from a failed
  // save), merged per section; a field shows the client error first.
  const [clientErrors, setClientErrors] = useState<Record<string, string>>({})
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({})
  const [activeSubTab, setActiveSubTab] = useState<SubTabId>('details')

  // Latest values mirrored into refs so the debounce / blur / action / unmount
  // paths read the freshest data. Handlers also write a ref directly when they
  // change its value, so no path waits for a render to see the latest keystroke.
  const customerFormRef = useRef(customerForm)
  customerFormRef.current = customerForm
  const installFormRef = useRef(installForm)
  installFormRef.current = installForm
  const billingFormRef = useRef(billingForm)
  billingFormRef.current = billingForm
  const sameAsInstallRef = useRef(sameAsInstall)
  sameAsInstallRef.current = sameAsInstall
  const lockedRef = useRef(locked)
  lockedRef.current = locked
  const orderIdRef = useRef(orderId)
  orderIdRef.current = orderId
  const onSavedRef = useRef(onSaved)
  onSavedRef.current = onSaved

  // Guards setState and lifts after the tab unmounts (the order changes or the user
  // leaves the workspace). It never stops a save, its drain or the billing copy that
  // waits for installation: those complete after unmount, they just are not lifted.
  const mountedRef = useRef(true)

  // Whether each section has a saved row on the server. A section without one shows
  // its validation errors on blur only for the fields the user has touched.
  const customerRowRef = useRef(customer != null)
  const installRowRef = useRef(installationAddress != null)
  const billingRowRef = useRef(billingAddress != null)
  const [billingRowExists, setBillingRowExists] = useState(billingAddress != null)
  const touchedRef = useRef<Set<string>>(new Set())

  // Billing copy bookkeeping ("same as installation"). copyOwedSeqRef counts the
  // events that owe a copy: a successful installation save while the box is ticked,
  // ticking it, or an action flush while ticked with no billing row (owed as a
  // tick). copyDoneSeqRef is the count covered by the latest successful copy (or by
  // an untick, which cancels the owed copies); copyInFlightSeqRef is the count the
  // copy in flight covers. copyOwedByTickRef: the newest owing event owes as a tick.
  const copyOwedSeqRef = useRef(0)
  const copyDoneSeqRef = useRef(0)
  const copyInFlightSeqRef = useRef<number | null>(null)
  const copyOwedByTickRef = useRef(false)
  const [copyOwed, setCopyOwed] = useState(false)
  // Successful installation saves since the tab mounted (0 = the row loaded on
  // open): the generation a billing copy copies, which keys its refusal.
  const installSaveGenRef = useRef(0)
  // The separate billing values held when the box is ticked; an untick in this
  // session restores them (and saves them with their own PUT).
  const previousBillingFormRef = useRef<AddressForm | null>(null)

  const customerSave = useSectionAutosave<
    CustomerSaveRequest,
    ApiSuccess<CustomerSaveResponse>
  >({
    orderId,
    locked,
    // The seeded form's body, never a null "always dirty" baseline: opening the tab
    // (or a StrictMode setup/cleanup) never writes.
    initialBaselineKey: JSON.stringify(buildCustomerBody(customerFromProps(customer))),
    buildBody: () => validCustomerBody(customerFormRef.current),
    bodyKey: (body) => JSON.stringify(body),
    send: saveCustomer,
    onSaved: ({ orderId: savedOrderId, body, result }) => {
      customerRowRef.current = true
      if (mountedRef.current) {
        setServerErrors((prev) => replaceSectionErrors(prev, 'customer_', {}))
        onSavedRef.current({ orderId: savedOrderId, customer: result.data.customer })
      }
      return JSON.stringify(body)
    },
    onFailed: ({ body, error }) => {
      if (!mountedRef.current) return
      const current = buildCustomerBody(customerFormRef.current)
      setServerErrors((prev) =>
        replaceSectionErrors(
          prev,
          'customer_',
          mapDetailsToFieldErrors('customer_', error, body, current),
        ),
      )
    },
  })

  const installSave = useSectionAutosave<
    AddressUpsertRequest,
    ApiSuccess<InstallationAddressResponse>
  >({
    orderId,
    locked,
    initialBaselineKey: JSON.stringify(
      buildAddressBody(addressFromProps(installationAddress)),
    ),
    buildBody: () => validAddressBody(installFormRef.current),
    bodyKey: (body) => JSON.stringify(body),
    send: saveInstallationAddress,
    onSaved: ({ orderId: savedOrderId, body, result }) => {
      installRowRef.current = true
      installSaveGenRef.current += 1
      // The installation row must persist before any copy-from-installation: a
      // successful installation save is what owes the copy while the box is ticked,
      // and the copy itself waits until installation has nothing in flight or queued.
      // The success also shows the order is editable, so earlier copy refusals are
      // forgotten (redundant now that the copy this save owes has a new generation
      // key, but harmless); a refused billing PUT stays refused until billing succeeds.
      billingSave.forgetRefusals((key) => key.startsWith('copy:'))
      if (sameAsInstallRef.current) oweCopy(false)
      if (mountedRef.current) {
        setServerErrors((prev) => replaceSectionErrors(prev, 'install_', {}))
        onSavedRef.current({
          orderId: savedOrderId,
          installAddress: result.data.install_address,
        })
      }
      return JSON.stringify(body)
    },
    onFailed: ({ body, error }) => {
      if (!mountedRef.current) return
      const current = buildAddressBody(installFormRef.current)
      setServerErrors((prev) =>
        replaceSectionErrors(
          prev,
          'install_',
          mapDetailsToFieldErrors('install_', error, body, current),
        ),
      )
    },
    // Installation went idle: send the billing copy it gates, when one is owed.
    onIdle: () => {
      if (sameAsInstallRef.current) billingSave.request()
    },
  })

  const billingSave = useSectionAutosave<
    BillingIntent,
    ApiSuccess<BillingAddressResponse>
  >({
    orderId,
    locked,
    // The SAVED billing row (null when there is none), never the form: a pre-filled
    // form with no billing row is not a saved row, and it is not dirty either — billing
    // is sent only for an owed copy or a billing edit made while the box is unticked.
    initialBaselineKey:
      billingAddress != null
        ? JSON.stringify(buildAddressBody(addressFromProps(billingAddress)))
        : null,
    buildBody: buildBillingIntent,
    // A copy is keyed by the installation-save generation it copies, never by a tick:
    // a copy refusal (ORDER_LOCKED) that arrives late never blocks a copy owed by a
    // later successful installation save, even one that saved the same value again,
    // while a re-tick or a queued duplicate of the same generation is not re-sent.
    bodyKey: (intent) =>
      intent.kind === 'copy'
        ? `copy:${intent.source}`
        : JSON.stringify(intent.body),
    // Checked again when a queued intent drains: an untick cancels a queued copy, and a
    // tick makes a queued PUT of the old separate values obsolete.
    needsSave: (intent, baselineKey) =>
      intent.kind === 'copy'
        ? intent.owedSeq > copyDoneSeqRef.current && copyReady()
        : !sameAsInstallRef.current &&
          JSON.stringify(intent.body) !== baselineKey,
    send: (saveOrderId, intent) => {
      if (intent.kind === 'copy') {
        copyInFlightSeqRef.current = intent.owedSeq
        return copyBillingFromInstallation(saveOrderId)
      }
      return saveBillingAddress(saveOrderId, intent.body)
    },
    onSaved: ({ orderId: savedOrderId, body: intent, result }) => {
      billingRowRef.current = true
      if (intent.kind === 'copy') {
        copyInFlightSeqRef.current = null
        copyDoneSeqRef.current = Math.max(copyDoneSeqRef.current, intent.owedSeq)
      }
      if (mountedRef.current) {
        setBillingRowExists(true)
        setCopyOwed(copyOwedSeqRef.current > copyDoneSeqRef.current)
        setServerErrors((prev) => replaceSectionErrors(prev, 'billing_', {}))
        onSavedRef.current({
          orderId: savedOrderId,
          billingAddress: result.data.billing_address,
        })
      }
      // A copy sends no body: its baseline is the row the server copied.
      return intent.kind === 'copy'
        ? JSON.stringify(
            buildAddressBody(addressFromProps(result.data.billing_address)),
          )
        : JSON.stringify(intent.body)
    },
    onFailed: ({ body: intent, error }) => {
      if (intent.kind === 'copy') copyInFlightSeqRef.current = null
      if (!mountedRef.current || intent.kind !== 'put') return
      const current = buildAddressBody(billingFormRef.current)
      setServerErrors((prev) =>
        replaceSectionErrors(
          prev,
          'billing_',
          mapDetailsToFieldErrors('billing_', error, intent.body, current),
        ),
      )
    },
  })

  function oweCopy(byTick: boolean) {
    copyOwedSeqRef.current += 1
    copyOwedByTickRef.current = byTick
    if (mountedRef.current) setCopyOwed(true)
  }

  // A copy reads the SAVED installation row, so it runs only while installation has
  // nothing in flight or queued. A copy owed by a tick also needs the installation
  // form saved and unchanged (otherwise the next successful installation save owes
  // it again); one owed by a successful installation save copies that saved row.
  function copyReady(): boolean {
    if (installSave.isBusy()) return false
    if (!copyOwedByTickRef.current) return true
    const body = validAddressBody(installFormRef.current)
    return body !== null && JSON.stringify(body) === installSave.baseline()
  }

  function buildBillingIntent(): BillingIntent | null {
    if (sameAsInstallRef.current) {
      const owedSeq = copyOwedSeqRef.current
      if (owedSeq <= copyDoneSeqRef.current) return null
      if (copyInFlightSeqRef.current === owedSeq) return null
      if (!copyReady()) return null
      return { kind: 'copy', owedSeq, source: String(installSaveGenRef.current) }
    }
    const body = validAddressBody(billingFormRef.current)
    return body === null ? null : { kind: 'put', body }
  }

  function sectionSave(section: SaveSection): SectionAutosave {
    if (section === 'customer') return customerSave
    if (section === 'installation') return installSave
    return billingSave
  }

  // A section is confirmed when its latest valid body is the confirmed baseline with
  // nothing in flight or queued. Billing while ticked is confirmed only with a SAVED
  // billing row, no copy still owed and a confirmed installation (an absent billing
  // row behind a pre-filled form is never "saved").
  function customerConfirmed(): boolean {
    const body = validCustomerBody(customerFormRef.current)
    return (
      body !== null &&
      !customerSave.isBusy() &&
      JSON.stringify(body) === customerSave.baseline()
    )
  }

  function installationConfirmed(): boolean {
    const body = validAddressBody(installFormRef.current)
    return (
      body !== null &&
      !installSave.isBusy() &&
      JSON.stringify(body) === installSave.baseline()
    )
  }

  function billingConfirmed(): boolean {
    if (billingSave.isBusy()) return false
    if (sameAsInstallRef.current) {
      return (
        billingRowRef.current &&
        copyOwedSeqRef.current <= copyDoneSeqRef.current &&
        installationConfirmed()
      )
    }
    const body = validAddressBody(billingFormRef.current)
    return body !== null && JSON.stringify(body) === billingSave.baseline()
  }

  // The complete Customer save sequence, shared by the action flush and the unmount
  // registration: the customer and installation flushes and the billing PUT (which
  // does not depend on installation) start at once; an owed billing copy starts only
  // once installation is idle; then every chain is awaited until no section has work
  // left (a settling installation chain may have started the copy). Locked orders
  // start nothing new but still await work already running. Never rejects.
  async function flushAllSections(): Promise<void> {
    customerSave.flushNow()
    installSave.flushNow()
    const billingIndependent = !sameAsInstallRef.current
    if (billingIndependent) billingSave.flushNow()
    await installSave.whenIdle()
    if (!billingIndependent) billingSave.flushNow()
    for (;;) {
      const chains = [
        customerSave.activeChain(),
        installSave.activeChain(),
        billingSave.activeChain(),
      ].filter((chain): chain is Promise<void> => chain !== null)
      if (chains.length === 0) return
      await Promise.all(chains)
    }
  }

  // Batch A #107 (D1): validate, save every eligible section, await the full drains,
  // then re-check the FRESHEST forms and intent. An invalid section, an owed copy, an
  // absent billing row or a latest form that differs from its confirmed baseline (a
  // failed save leaves it so) is never reported as saved. Like QuoteTab, the re-check
  // ignores the section error: after an ambiguous failure (the save may have committed)
  // a form equal to its confirmed baseline passes while the status line keeps the
  // error; the unknown-outcome problem is not solved here. With "same as installation"
  // ticked and no billing row (an earlier copy failed, then a reload), this action
  // flush owes a copy as a tick does (Muneeb's decision, 30 October 2026): it runs
  // once installation is saved, unchanged and idle, otherwise after the next
  // successful installation save. Only the action flush owes it, never the mount, the
  // debounce, a blur or the unmount flush. On failure the invalid sections show all
  // their field errors; a failed save (a failed copy included) shows in the status
  // line; a copy that cannot run yet shows only as the empty Addresses circle.
  async function flushCustomerAutosave(): Promise<boolean> {
    if (!lockedRef.current && sameAsInstallRef.current && !billingRowRef.current) {
      oweCopy(true)
    }
    try {
      await flushAllSections()
    } catch {
      return false
    }
    const confirmed =
      customerConfirmed() && installationConfirmed() && billingConfirmed()
    if (!confirmed && mountedRef.current) {
      revealErrors('customer', true)
      revealErrors('installation', true)
      revealErrors('billing', true)
    }
    return confirmed
  }

  useImperativeHandle(ref, () => ({ flushCustomerAutosave }))

  // Mount/unmount guard + unmount flush. CustomerTab stays mounted (hidden) across
  // top-level tab switches, so this cleanup fires only on a REAL unmount: an order
  // switch or leaving the workspace. Batch A #101: the whole Customer save sequence
  // (work already running, the unmount flush of every valid dirty section and the
  // billing copy that waits for installation) is registered BEFORE returning, under
  // this order, so a reopen of the same order waits for it before reading. Locked
  // orders and invalid sections send nothing new; existing work is still registered.
  // StrictMode's setup/cleanup/setup cycle sends nothing: every section still equals
  // its seeded baseline and no copy is owed.
  useEffect(() => {
    // Reset on setup so a StrictMode setup→cleanup→setup cycle (or any remount)
    // leaves the ref true for the live component; cleanup marks a real unmount.
    // Without this reset the ref can stick at false and suppress every lift and
    // every guarded state update (server field errors, the billing-row and copy-owed
    // flags, the failed flush's error reveal).
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      registerPendingSave(orderIdRef.current, 'customer', flushAllSections())
    }
    // flushAllSections reads everything through refs and the hooks' ref-backed methods.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function clearFieldError(key: string) {
    setClientErrors((prev) => withoutError(prev, key))
    setServerErrors((prev) => withoutError(prev, key))
  }

  // Show a section's validation errors (blur, or a failed action flush with `all`).
  // A section without a saved row shows them only for touched fields unless `all`;
  // fields shown by `all` count as touched, so a later blur keeps showing them.
  function revealErrors(section: SaveSection, all: boolean) {
    const prefix = SECTION_ERROR_PREFIX[section]
    let found: Record<string, string> = {}
    if (section === 'customer') {
      found = validateCustomer(customerFormRef.current)
    } else if (section === 'installation') {
      found = validateAddress(installFormRef.current, prefix)
    } else if (!sameAsInstallRef.current) {
      found = validateAddress(billingFormRef.current, prefix)
    }
    const rowSaved =
      section === 'customer'
        ? customerRowRef.current
        : section === 'installation'
          ? installRowRef.current
          : billingRowRef.current
    const shown: Record<string, string> = {}
    for (const [key, message] of Object.entries(found)) {
      if (all) touchedRef.current.add(key)
      if (all || rowSaved || touchedRef.current.has(key)) shown[key] = message
    }
    setClientErrors((prev) => replaceSectionErrors(prev, prefix, shown))
  }

  // Blur: save the section now (no waiting for the debounce) and show its errors.
  function handleBlur(section: SaveSection, field: string) {
    touchedRef.current.add(`${SECTION_ERROR_PREFIX[section]}${field}`)
    sectionSave(section).flushNow()
    revealErrors(section, false)
  }

  function updateCustomer<K extends keyof CustomerForm>(
    key: K,
    value: CustomerForm[K],
  ) {
    const next = { ...customerFormRef.current, [key]: value }
    customerFormRef.current = next
    setCustomerForm(next)
    clearFieldError(`customer_${key}`)
    customerSave.clearError()
    customerSave.schedule()
  }

  function updateInstall<K extends keyof AddressForm>(
    key: K,
    value: AddressForm[K],
  ) {
    const next = { ...installFormRef.current, [key]: value }
    installFormRef.current = next
    setInstallForm(next)
    if (sameAsInstallRef.current) {
      const mirror = { ...billingFormRef.current, [key]: value }
      billingFormRef.current = mirror
      setBillingForm(mirror)
    }
    clearFieldError(`install_${key}`)
    installSave.clearError()
    installSave.schedule()
  }

  function updateBilling<K extends keyof AddressForm>(
    key: K,
    value: AddressForm[K],
  ) {
    const next = { ...billingFormRef.current, [key]: value }
    billingFormRef.current = next
    setBillingForm(next)
    clearFieldError(`billing_${key}`)
    billingSave.clearError()
    billingSave.schedule()
  }

  // Ticking "same as installation" is an explicit billing edit: it owes a copy, sent
  // at once when installation is saved, unchanged and idle (otherwise after the next
  // successful installation save), and it keeps the separate billing values for an
  // untick. Unticking cancels every owed copy (a copy already in flight still lands,
  // and the restored values are drained after it), restores those values and
  // schedules their own PUT (debounced, so a billing edit right after joins it).
  function toggleSameAsInstall(checked: boolean) {
    if (lockedRef.current) return
    sameAsInstallRef.current = checked
    setSameAsInstall(checked)
    if (checked) {
      previousBillingFormRef.current = billingFormRef.current
      const mirror = { ...installFormRef.current }
      billingFormRef.current = mirror
      setBillingForm(mirror)
      setClientErrors((prev) => replaceSectionErrors(prev, 'billing_', {}))
      setServerErrors((prev) => replaceSectionErrors(prev, 'billing_', {}))
      oweCopy(true)
    } else {
      copyDoneSeqRef.current = copyOwedSeqRef.current
      copyOwedByTickRef.current = false
      setCopyOwed(false)
      const previous = previousBillingFormRef.current
      if (previous !== null) {
        billingFormRef.current = previous
        setBillingForm(previous)
      }
    }
    billingSave.clearError()
    if (checked) billingSave.flushNow()
    else billingSave.schedule()
  }

  // Inputs stay editable while a section saves; only LAID locks them.
  const editingDisabled = locked
  const billingDisabled = editingDisabled || sameAsInstall
  const errors = { ...serverErrors, ...clientErrors }

  // Sub-tab circles: green only while the content is complete, valid and confirmed
  // saved, with no save running and no unresolved save error (Details = customer
  // details; Addresses = installation AND billing).
  const customerBody = validCustomerBody(customerForm)
  const detailsComplete =
    customerBody !== null &&
    !customerSave.saving &&
    customerSave.error === null &&
    JSON.stringify(customerBody) === customerSave.baselineKey
  const installBody = validAddressBody(installForm)
  const installComplete =
    installBody !== null &&
    !installSave.saving &&
    installSave.error === null &&
    JSON.stringify(installBody) === installSave.baselineKey
  const billingBody = validAddressBody(billingForm)
  const billingComplete =
    !billingSave.saving &&
    billingSave.error === null &&
    (sameAsInstall
      ? billingRowExists && !copyOwed && installComplete
      : billingBody !== null &&
        JSON.stringify(billingBody) === billingSave.baselineKey)
  const addressesComplete = installComplete && billingComplete
  const subTabs = SUB_TABS.map((tab) =>
    tab.id === 'enquiry'
      ? tab
      : {
          ...tab,
          indicator:
            (tab.id === 'details' ? detailsComplete : addressesComplete)
              ? ('complete' as const)
              : ('incomplete' as const),
        },
  )

  // Status line (Details of Sale style): Saving… / an error naming its section /
  // Saved. Saved shows only after a save in this session, and only while every
  // section is complete, valid and saved.
  const anySaving =
    customerSave.saving || installSave.saving || billingSave.saving
  const sectionErrors = (['customer', 'installation', 'billing'] as const)
    .filter((section) => sectionSave(section).error !== null)
    .map((section) => ({
      section,
      message: apiErrorMessage(sectionSave(section).error),
    }))
  const allSaved =
    detailsComplete &&
    addressesComplete &&
    (customerSave.savedThisSession ||
      installSave.savedThisSession ||
      billingSave.savedThisSession)

  return (
    <div>
      <div className="mb-6 flex items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
            Customer
          </h2>
          <p className="text-sm text-slate-500 mt-1">
            {locked
              ? 'Enter customer details and addresses.'
              : 'Enter customer details and addresses. Changes are saved automatically.'}
          </p>
        </div>
        {!locked && activeSubTab !== 'enquiry' && (
          <div className="shrink-0 pt-0.5 text-xs">
            {anySaving ? (
              <span className="text-slate-500">Saving…</span>
            ) : sectionErrors.length > 0 ? (
              <div className="space-y-0.5 text-right">
                {sectionErrors.map(({ section, message }) => (
                  <p key={section} className="text-red-600">
                    {SECTION_LABELS[section]}: {message}
                  </p>
                ))}
              </div>
            ) : allSaved ? (
              <span className="inline-flex items-center gap-1 text-teal-700">
                <CheckCircleIcon className="w-3.5 h-3.5" />
                Saved
              </span>
            ) : null}
          </div>
        )}
      </div>

      <div className="mb-6">
        <Tabs
          tabs={subTabs}
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
                onBlur={() => handleBlur('customer', 'first_name')}
                invalid={!!errors.customer_first_name}
                disabled={editingDisabled}
              />
            </Field>
            <Field
              label="Middle name"
              htmlFor="middle_name"
              error={errors.customer_middle_name}
            >
              <Input
                id="middle_name"
                type="text"
                autoComplete="additional-name"
                value={customerForm.middle_name}
                onChange={(e) =>
                  updateCustomer('middle_name', e.target.value)
                }
                onBlur={() => handleBlur('customer', 'middle_name')}
                invalid={!!errors.customer_middle_name}
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
                onBlur={() => handleBlur('customer', 'last_name')}
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
                onBlur={() => handleBlur('customer', 'email')}
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
                onBlur={() => handleBlur('customer', 'mobile')}
                invalid={!!errors.customer_mobile}
                disabled={editingDisabled}
              />
            </Field>
            <Field
              label="Home number"
              htmlFor="home_number"
              error={errors.customer_home_phone}
            >
              <Input
                id="home_number"
                type="tel"
                autoComplete="tel-national"
                value={customerForm.home_phone}
                onChange={(e) =>
                  updateCustomer('home_phone', e.target.value)
                }
                onBlur={() => handleBlur('customer', 'home_phone')}
                invalid={!!errors.customer_home_phone}
                disabled={editingDisabled}
              />
            </Field>
            <Field
              label="Work number"
              htmlFor="work_number"
              error={errors.customer_work_phone}
            >
              <Input
                id="work_number"
                type="tel"
                value={customerForm.work_phone}
                onChange={(e) =>
                  updateCustomer('work_phone', e.target.value)
                }
                onBlur={() => handleBlur('customer', 'work_phone')}
                invalid={!!errors.customer_work_phone}
                disabled={editingDisabled}
              />
            </Field>
            <Field
              label="Company"
              htmlFor="company"
              error={errors.customer_company_name}
            >
              <Input
                id="company"
                type="text"
                autoComplete="organization"
                value={customerForm.company_name}
                onChange={(e) =>
                  updateCustomer('company_name', e.target.value)
                }
                onBlur={() => handleBlur('customer', 'company_name')}
                invalid={!!errors.customer_company_name}
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
              <Field
                label="Unit number"
                htmlFor="install_unit_number"
                error={errors.install_unit_number}
              >
                <Input
                  id="install_unit_number"
                  type="text"
                  value={installForm.unit_number}
                  onChange={(e) =>
                    updateInstall('unit_number', e.target.value)
                  }
                  onBlur={() => handleBlur('installation', 'unit_number')}
                  invalid={!!errors.install_unit_number}
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
                  onBlur={() => handleBlur('installation', 'street_number')}
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
                  onBlur={() => handleBlur('installation', 'street')}
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
                  onBlur={() => handleBlur('installation', 'suburb')}
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
                  onBlur={() => handleBlur('installation', 'state_code')}
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
                  onBlur={() => handleBlur('installation', 'postcode')}
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
              <Field
                label="Unit number"
                htmlFor="billing_unit_number"
                error={errors.billing_unit_number}
              >
                <Input
                  id="billing_unit_number"
                  type="text"
                  value={billingForm.unit_number}
                  onChange={(e) =>
                    updateBilling('unit_number', e.target.value)
                  }
                  onBlur={() => handleBlur('billing', 'unit_number')}
                  disabled={billingDisabled}
                  invalid={!!errors.billing_unit_number}
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
                  onBlur={() => handleBlur('billing', 'street_number')}
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
                  onBlur={() => handleBlur('billing', 'street')}
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
                  onBlur={() => handleBlur('billing', 'suburb')}
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
                  onBlur={() => handleBlur('billing', 'state_code')}
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
                  onBlur={() => handleBlur('billing', 'postcode')}
                  disabled={billingDisabled}
                  invalid={!!errors.billing_postcode}
                />
              </Field>
            </div>
          </section>
        </div>
      )}

      {/* Kept mounted (toggled with `hidden`) so unsaved enquiry edits survive a
          sub-tab switch, the same way the customer/address fields and their
          pending autosaves persist via CustomerTab state. */}
      <div className={activeSubTab === 'enquiry' ? '' : 'hidden'}>
        <LeadEnquirySection
          orderId={orderId}
          locked={locked}
          enquiry={enquiry}
          // LeadEnquirySection captures the order id used for its PUT and passes
          // it through, so the parent can drop a late callback from a prior order.
          onSaved={(payload) => onSaved(payload)}
        />
      </div>

      {/* Customer/address footer — only for the Details/Addresses sub-tabs, which autosave (status above). */}
      {activeSubTab !== 'enquiry' && (
      <div className="mt-8 pt-5 border-t border-slate-200 space-y-4">
        {locked && (
          <p className="text-xs text-slate-500">
            This order is laid and locked. Editing is disabled.
          </p>
        )}

        <p className="text-[11px] text-slate-500">
          Customer and billing address details are required before creating
          an invoice.
        </p>
      </div>
      )}
    </div>
  )
}
