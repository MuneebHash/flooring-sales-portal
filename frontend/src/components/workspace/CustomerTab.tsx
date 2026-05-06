import { Field } from '../ui/Field'
import { Input } from '../ui/Input'

export function CustomerTab() {
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

      <div className="space-y-8">
        <section>
          <h3 className="text-sm font-semibold text-slate-900 mb-4">
            Customer details
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            <Field label="First name" htmlFor="first_name">
              <Input id="first_name" type="text" autoComplete="given-name" />
            </Field>
            <Field label="Middle name" htmlFor="middle_name">
              <Input
                id="middle_name"
                type="text"
                autoComplete="additional-name"
              />
            </Field>
            <Field label="Last name" htmlFor="last_name">
              <Input id="last_name" type="text" autoComplete="family-name" />
            </Field>
            <Field label="Email" htmlFor="email">
              <Input id="email" type="email" autoComplete="email" />
            </Field>
            <Field label="Mobile number" htmlFor="mobile_number">
              <Input id="mobile_number" type="tel" autoComplete="tel" />
            </Field>
            <Field label="Home number" htmlFor="home_number">
              <Input
                id="home_number"
                type="tel"
                autoComplete="tel-national"
              />
            </Field>
            <Field label="Work number" htmlFor="work_number">
              <Input id="work_number" type="tel" />
            </Field>
            <Field label="Company" htmlFor="company">
              <Input id="company" type="text" autoComplete="organization" />
            </Field>
          </div>
        </section>

        <section>
          <h3 className="text-sm font-semibold text-slate-900 mb-4">
            Installation address
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            <Field label="Unit number" htmlFor="install_unit_number">
              <Input id="install_unit_number" type="text" />
            </Field>
            <Field label="Street number" htmlFor="install_street_number">
              <Input id="install_street_number" type="text" />
            </Field>
            <Field label="Street" htmlFor="install_street">
              <Input id="install_street" type="text" />
            </Field>
            <Field label="Suburb" htmlFor="install_suburb">
              <Input id="install_suburb" type="text" />
            </Field>
            <Field label="State" htmlFor="install_state">
              <Input id="install_state" type="text" />
            </Field>
            <Field label="Postcode" htmlFor="install_postcode">
              <Input id="install_postcode" type="text" inputMode="numeric" />
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
              className="h-4 w-4 rounded border-slate-300 text-teal-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30"
            />
            <span>Same as installation address</span>
          </label>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            <Field label="Unit number" htmlFor="billing_unit_number">
              <Input id="billing_unit_number" type="text" />
            </Field>
            <Field label="Street number" htmlFor="billing_street_number">
              <Input id="billing_street_number" type="text" />
            </Field>
            <Field label="Street" htmlFor="billing_street">
              <Input id="billing_street" type="text" />
            </Field>
            <Field label="Suburb" htmlFor="billing_suburb">
              <Input id="billing_suburb" type="text" />
            </Field>
            <Field label="State" htmlFor="billing_state">
              <Input id="billing_state" type="text" />
            </Field>
            <Field label="Postcode" htmlFor="billing_postcode">
              <Input id="billing_postcode" type="text" inputMode="numeric" />
            </Field>
          </div>
        </section>
      </div>
    </div>
  )
}
