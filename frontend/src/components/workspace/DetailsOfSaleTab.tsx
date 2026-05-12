import { useState } from 'react'
import { Field } from '../ui/Field'
import { Input } from '../ui/Input'
import { Select } from '../ui/Select'
import { Textarea } from '../ui/Textarea'
import type { SaleDetails } from '../../data/mockOrderDetails'

type Props = {
  saleDetails?: SaleDetails | null
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

function formatIsoDate(iso: string | null | undefined): string {
  if (!iso) return ''
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!match) return ''
  const [, year, month, day] = match
  const name = MONTH_NAMES[Number(month) - 1]
  if (!name) return ''
  return `${day} ${name} ${year}`
}

export function DetailsOfSaleTab({ saleDetails }: Props) {
  const [description, setDescription] = useState(
    saleDetails?.details_of_sale ?? '',
  )
  const [proposedLayDate, setProposedLayDate] = useState(
    saleDetails?.proposed_lay_date ?? '',
  )

  function appendSnippet(snippet: string) {
    setDescription((prev) => (prev.trim() ? `${prev.trimEnd()}\n${snippet}` : snippet))
  }

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
            <Field label="Description" htmlFor="details_of_sale">
              <Textarea
                id="details_of_sale"
                rows={10}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
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
                      aria-label={`Add snippet: ${snippet}`}
                      className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md border border-teal-500 bg-white text-teal-600 hover:bg-teal-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30 transition-colors"
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
          <Field label="Proposed lay date" htmlFor="proposed_lay_date">
            <Input
              id="proposed_lay_date"
              type="date"
              value={proposedLayDate}
              onChange={(e) => setProposedLayDate(e.target.value)}
            />
            <p className="mt-1.5 text-xs text-slate-500">
              {formatIsoDate(proposedLayDate) || 'No date selected'}
            </p>
          </Field>
          <Field label="Lay date status" htmlFor="lay_date_status">
            <Select
              id="lay_date_status"
              defaultValue={saleDetails?.lay_date_status ?? ''}
            >
              <option value="">— Select —</option>
              <option value="CONFIRMED">Confirmed</option>
              <option value="TO_BE_CONFIRMED">To be confirmed</option>
            </Select>
          </Field>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Field label="Plan numbers" htmlFor="plan_numbers">
            <Input
              id="plan_numbers"
              type="text"
              defaultValue={saleDetails?.plan_numbers ?? ''}
            />
          </Field>
        </div>

        <label className="inline-flex items-center gap-2 text-sm text-slate-700 cursor-pointer">
          <input
            id="supply_only"
            type="checkbox"
            defaultChecked={saleDetails?.supply_only ?? false}
            className="h-4 w-4 rounded border-slate-300 text-teal-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/30"
          />
          <span>Supply only (no installation)</span>
        </label>
      </div>
    </div>
  )
}
