import { useState } from 'react'
import { Button } from '../ui/Button'
import { Field } from '../ui/Field'
import { Input } from '../ui/Input'
import { Tabs } from '../ui/Tabs'
import { CheckCircleIcon } from '../icons'
import type {
  Address,
  CustomerDetails,
} from '../../data/mockOrderDetails'

type Props = {
  customer?: CustomerDetails | null
  installationAddress?: Address | null
  billingAddress?: Address | null
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

type SubTabId = 'details' | 'addresses'

const SUB_TABS: Array<{ id: SubTabId; label: string }> = [
  { id: 'details', label: 'Details' },
  { id: 'addresses', label: 'Addresses' },
]

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function customerFromProps(c?: CustomerDetails | null): CustomerForm {
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

function addressFromProps(a?: Address | null): AddressForm {
  return {
    unit_number: a?.unit_number ?? '',
    street_number: a?.street_number ?? '',
    street: a?.street ?? '',
    suburb: a?.suburb ?? '',
    state_code: a?.state_code ?? '',
    postcode: a?.postcode ?? '',
  }
}

export function CustomerTab({
  customer,
  installationAddress,
  billingAddress,
}: Props) {
  const [customerForm, setCustomerForm] = useState<CustomerForm>(() =>
    customerFromProps(customer),
  )
  const [installForm, setInstallForm] = useState<AddressForm>(() =>
    addressFromProps(installationAddress),
  )
  const [billingForm, setBillingForm] = useState<AddressForm>(() =>
    addressFromProps(billingAddress),
  )
  const [sameAsInstall, setSameAsInstall] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [showSuccess, setShowSuccess] = useState(false)
  const [activeSubTab, setActiveSubTab] = useState<SubTabId>('details')

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
    setShowSuccess(false)
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
    setShowSuccess(false)
  }

  function updateBilling<K extends keyof AddressForm>(
    key: K,
    value: AddressForm[K],
  ) {
    setBillingForm((prev) => ({ ...prev, [key]: value }))
    clearError(`billing_${key}`)
    setShowSuccess(false)
  }

  function toggleSameAsInstall(checked: boolean) {
    setSameAsInstall(checked)
    if (checked) {
      setBillingForm({ ...installForm })
      clearErrorsWithPrefix('billing_')
    }
    setShowSuccess(false)
  }

  function handleSave() {
    const newErrors: Record<string, string> = {}

    if (!customerForm.first_name.trim()) {
      newErrors.customer_first_name = 'First name is required.'
    }
    if (!customerForm.last_name.trim()) {
      newErrors.customer_last_name = 'Last name is required.'
    }
    if (!customerForm.mobile.trim()) {
      newErrors.customer_mobile = 'Mobile number is required.'
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

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors)
      setShowSuccess(false)
      const hasCustomerErrors = Object.keys(newErrors).some((k) =>
        k.startsWith('customer_'),
      )
      const hasAddressErrors = Object.keys(newErrors).some(
        (k) => k.startsWith('install_') || k.startsWith('billing_'),
      )
      if (hasCustomerErrors) {
        setActiveSubTab('details')
      } else if (hasAddressErrors) {
        setActiveSubTab('addresses')
      }
    } else {
      setErrors({})
      setShowSuccess(true)
    }
  }

  const billingDisabled = sameAsInstall

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

      <div className="mt-8 pt-5 border-t border-slate-200 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <p className="text-[11px] text-slate-500">
          Customer and billing address details are required before creating
          an invoice.
        </p>
        <div className="flex items-center gap-3">
          {showSuccess && (
            <span className="inline-flex items-center gap-1 text-xs text-teal-700">
              <CheckCircleIcon className="w-3.5 h-3.5" />
              Customer details look ready.
            </span>
          )}
          <Button
            type="button"
            variant="success"
            size="md"
            onClick={handleSave}
          >
            Save customer
          </Button>
        </div>
      </div>
    </div>
  )
}
