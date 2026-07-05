import { useEffect, useMemo, useRef, useState } from 'react'
import DOMPurify from 'dompurify'
import { Button } from '../ui/Button'
import { Input } from '../ui/Input'
import { Modal } from '../ui/Modal'
import { Tabs } from '../ui/Tabs'
import { CheckCircleIcon } from '../icons'
import { ApiError } from '../../lib/api/ApiError'
import {
  QUOTE_TOTAL_MAX,
  fetchQuotePreviewPdf,
  saveQuoteDraft,
  type QuoteDraftRead,
  type QuoteDraftSaveRequest,
} from '../../lib/api/orderQuoteApi'
import type {
  DetailsOfSaleFields,
  OrderAddress,
  OrderCustomer,
} from '../../lib/api/orderWorkspaceApi'
import {
  fetchTenantInvoiceConfig,
  type TenantInvoiceConfig,
} from '../../lib/api/tenantInvoiceConfigApi'
import { fetchPublicBusiness } from '../../lib/api/tenantApi'
import { getActiveSlug } from '../../lib/tenant'
import type { FlooringType } from '../../lib/flooring'

// Phase 16D-B PR1 — the salesperson Quote tab (docs/Phase16D-Quotation-UX-Lock.md).
//
// Three internal sub-tabs: Quote Draft (functional, NON-ITEMISED editing only in
// PR1), Customer Quote and Accepted Quote (real lifecycle tabs whose data arrives
// in 16E/16F — clean empty states until then). The Quote Draft is an invoice-style
// QUOTATION canvas mirroring InvoiceTab's document layout, NOT a generic form.
//
// This tab only ever mounts AFTER the shell's quote-workspace probe has
// SUCCEEDED (the tab is revealed either by that probe finding a saved draft, or
// by Create Quote — which is only enabled once the probe confirmed draft ===
// null). That ordering is the load-before-save guarantee: autosave can never
// fire against an unknown server state, so a full-replace PUT can never wipe a
// draft it hasn't seen (the lock doc's full-body-PUT wipe rule).
//
// Once revealed, OrderWorkspace keeps this tab MOUNTED across top-level tab
// switches (hidden with CSS, like CustomerTab), so in-progress quote work
// survives tab switches; the unmount flush below fires only on a real order
// switch / page leave.
//
// 16D-A decoupling (locked): a quote save never changes order/header pricing, so
// NOTHING here refreshes the workspace financial summary — the save response
// updates quote draft state only.
//
// COST DISCIPLINE (locked): no cost value is ever displayed or sent. gp_percent
// and below_cost are server-derived protected-surface signals and render OUTSIDE
// the customer-facing quotation canvas.

// Debounce after a change before autosaving — mirrors LeadEnquirySection.
const AUTOSAVE_DEBOUNCE_MS = 800

type QuoteSubTabId = 'draft' | 'customer' | 'accepted'

const SUB_TABS: Array<{ id: QuoteSubTabId; label: string }> = [
  { id: 'draft', label: 'Quote Draft' },
  { id: 'customer', label: 'Customer Quote' },
  { id: 'accepted', label: 'Accepted Quote' },
]

type Props = {
  orderId: number
  // LAID lock — reads/preview stay available; edits + autosave are disabled.
  locked: boolean
  flooringType: FlooringType
  orderNumber?: string
  customer?: OrderCustomer | null
  billingAddress?: OrderAddress | null
  saleDetails?: DetailsOfSaleFields | null
  // The draft returned by the shell's quote-workspace probe for THIS order
  // (null = confirmed no saved draft). The tab seeds ONCE from this and then owns
  // the live draft state; it is never re-seeded after mount.
  initialDraft: QuoteDraftRead | null
}

const MONEY_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

function formatMoney(value: number): string {
  return MONEY_FORMATTER.format(value)
}

function formatPercent(value: number | null): string {
  if (value === null) return '—'
  return `${value.toFixed(2)}%`
}

function composeFullName(customer: OrderCustomer | null | undefined): string {
  if (!customer) return ''
  return [customer.first_name, customer.middle_name, customer.last_name]
    .filter((part) => part && part.trim().length > 0)
    .join(' ')
}

function composeAddressLines(
  address: OrderAddress | null | undefined,
): string[] {
  if (!address) return []
  const streetParts: string[] = []
  if (address.unit_number) {
    streetParts.push(`${address.unit_number}/${address.street_number}`)
  } else {
    streetParts.push(address.street_number)
  }
  streetParts.push(address.street)
  const line1 = streetParts.filter(Boolean).join(' ')
  const line2 = [address.suburb, address.state_code, address.postcode]
    .filter(Boolean)
    .join(' ')
  return [line1, line2].filter((line) => line.length > 0)
}

// Trim a nullable/optional string to visible content or null (mirrors InvoiceTab).
function nonBlank(value: string | null | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed && trimmed.length > 0 ? trimmed : null
}

function toFixed2(value: number): string {
  return value.toFixed(2)
}

// HALF_UP 2dp rounding with a float epsilon (copy of the DetailsOfSaleTab
// helper) so the value sent matches the backend's BigDecimal HALF_UP result and
// the saved baseline stays stable across response round-trips.
function round2(value: number): number {
  if (!Number.isFinite(value)) return value
  const scaled = value * 100
  const epsilon = 1e-9
  if (scaled >= 0) return Math.floor(scaled + 0.5 + epsilon) / 100
  return Math.ceil(scaled - 0.5 - epsilon) / 100
}

// Parse the quote-total input. Returns null for blank / non-numeric input; range
// rules are applied by buildNonItemisedBody.
function parseQuoteTotal(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : null
}

// Build the COMPLETE non-itemised full-replace body, or null when the current
// input cannot form a valid body (blank / non-numeric / out of range). A null
// body is NEVER sent — that is the "no partial/unloaded draft saves" rule.
// Module-scope + pure so the unmount flush can use it without stale closures.
function buildNonItemisedBody(
  totalInput: string,
): QuoteDraftSaveRequest | null {
  const parsed = parseQuoteTotal(totalInput)
  if (parsed === null || parsed < 0 || parsed > QUOTE_TOTAL_MAX) return null
  return {
    itemised: false,
    final_total_inc_gst: round2(parsed),
    lines: [],
  }
}

// Friendly message for a failed quote save. ORDER_LOCKED gets a specific line;
// backend messages (QUOTE_BELOW_COST / QUOTE_TOTAL_EXCEEDS_LINES /
// VALIDATION_FAILED / MALFORMED_JSON) surface VERBATIM.
function quoteSaveErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'ORDER_LOCKED') {
      return 'This order is laid and locked. The quote can no longer be edited.'
    }
    if (err.message.length > 0) return err.message
  }
  return 'Could not save the quote. Please try again.'
}

// Flatten VALIDATION_FAILED details[] ({ field?, message } or plain strings)
// into "field: message" lines (mirrors the PaymentsTab helper).
function quoteValidationDetails(details: unknown): string[] {
  if (!Array.isArray(details)) return []
  const out: string[] = []
  for (const item of details) {
    if (typeof item === 'string') {
      if (item.length > 0) out.push(item)
      continue
    }
    if (item && typeof item === 'object') {
      const rec = item as { field?: unknown; message?: unknown }
      const message = typeof rec.message === 'string' ? rec.message : ''
      const field = typeof rec.field === 'string' ? rec.field : null
      if (message.length > 0) out.push(field ? `${field}: ${message}` : message)
    }
  }
  return out
}

// Client-side fallback preview file name (the server Content-Disposition name is
// preferred when readable): quote-preview-{order_number}.pdf.
function previewFileName(orderNumber: string | undefined): string {
  const safeOrder =
    orderNumber && orderNumber.length > 0 ? orderNumber : 'order'
  return `quote-preview-${safeOrder}.pdf`
}

export function QuoteTab({
  orderId,
  locked,
  flooringType,
  orderNumber,
  customer,
  billingAddress,
  saleDetails,
  initialDraft,
}: Props) {
  const [subTab, setSubTab] = useState<QuoteSubTabId>('draft')

  // Latest server-confirmed draft state (totals / gp_percent / below_cost /
  // lines / updated_at). Seeded from the shell probe; replaced by each successful
  // save response. Quote-only state — never lifted into workspace financials.
  const [serverDraft, setServerDraft] = useState<QuoteDraftRead | null>(
    initialDraft,
  )

  // PR1 edits NON-ITEMISED drafts only. An existing itemised draft (only
  // creatable outside this UI today) renders READ-ONLY — no editing controls, no
  // autosave, no toggle — so a full-replace save can never convert/wipe it.
  // Constant per mount: the only in-UI transition (none in PR1) would remount.
  const itemisedReadOnly = initialDraft !== null && initialDraft.itemised

  // The single editable field of a non-itemised draft: the GST-inclusive quote
  // total, as a raw input string. Seeded ONCE from the probe result.
  const [totalInput, setTotalInput] = useState<string>(() =>
    initialDraft !== null && !initialDraft.itemised
      ? toFixed2(initialDraft.quote_total_inc_gst)
      : '',
  )

  // Autosave status trio + the send-quote modal + preview state.
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saveErrorDetails, setSaveErrorDetails] = useState<string[]>([])
  const [previewing, setPreviewing] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [sendModalOpen, setSendModalOpen] = useState(false)

  // Branding + per-type quote terms — same two INDEPENDENT fail-soft fetch
  // chains as InvoiceTab (business name/logo is optional branding; the tenant
  // invoice config is the TERMS source). The invoice acceptance-gating semantics
  // are deliberately NOT copied — a quote draft has nothing to gate; the config
  // status here only drives the terms section + a small retry notice.
  const [tenantConfig, setTenantConfig] = useState<TenantInvoiceConfig | null>(
    null,
  )
  const [tenantConfigLoading, setTenantConfigLoading] = useState(true)
  const [tenantConfigError, setTenantConfigError] = useState(false)
  const [businessName, setBusinessName] = useState<string | null>(null)
  const [logoPath, setLogoPath] = useState<string | null>(null)
  const [logoFailed, setLogoFailed] = useState(false)
  const [brandingReloadToken, setBrandingReloadToken] = useState(0)

  // Latest values mirrored into refs so the debounce / blur / flush / unmount
  // paths always read fresh data (mirrors LeadEnquirySection).
  const totalInputRef = useRef(totalInput)
  totalInputRef.current = totalInput
  const lockedRef = useRef(locked)
  lockedRef.current = locked
  const orderIdRef = useRef(orderId)
  orderIdRef.current = orderId

  const mountedRef = useRef(true)
  // JSON of the last body the backend confirmed persisted — the dirty baseline.
  // null = NO persisted draft yet (fresh Create Quote flow), so the first valid
  // total is always dirty and autosaves. Initialised from the probe result so a
  // freshly-loaded saved draft is never re-saved until it changes. Advanced ONLY
  // on a successful save response — a failed save stays dirty and keeps edits.
  const lastSavedBodyRef = useRef<string | null>(
    initialDraft !== null && !initialDraft.itemised
      ? JSON.stringify(
          buildNonItemisedBody(toFixed2(initialDraft.quote_total_inc_gst)),
        )
      : null,
  )
  const savingRef = useRef(false)
  const pendingBodyRef = useRef<QuoteDraftSaveRequest | null>(null)
  const debounceRef = useRef<number | null>(null)
  // Awaitable single-flight chain (mirrors the shell's details chain) so Preview
  // PDF can flush pending saves and only proceed once the WHOLE queue drained.
  const saveChainRef = useRef<Promise<void> | null>(null)
  // Object URLs handed to the preview tab. Revoking immediately can blank the
  // tab mid-load, so they are revoked on unmount only.
  const previewUrlsRef = useRef<string[]>([])

  // Single-flight save loop: exactly one PUT in flight; on settle, drains the
  // latest queued body only if it differs from what is now persisted.
  async function runSave(body: QuoteDraftSaveRequest) {
    savingRef.current = true
    if (mountedRef.current) {
      setSaving(true)
      setSaved(false)
      setSaveError(null)
      setSaveErrorDetails([])
    }
    // Capture the order id this PUT is addressed to BEFORE awaiting.
    const saveOrderId = orderIdRef.current
    try {
      const res = await saveQuoteDraft(saveOrderId, body)
      // Mark persisted ONLY after a successful backend response.
      lastSavedBodyRef.current = JSON.stringify(body)
      if (mountedRef.current) {
        // Quote draft state only — NEVER an order/header financial refresh.
        setServerDraft(res.data)
        // Canonical-total sync: the backend persists ex = round2(final/1.1) and
        // reports inc = round2(ex*1.1), which can differ from the typed total by
        // one cent (e.g. 4900 -> 4900.01). If the user has NOT typed since this
        // body was sent, sync the input + baseline to the server-canonical inc
        // total so the screen never contradicts the persisted quote / the PDF.
        // (The canonical value round-trips stably, so this cannot oscillate.)
        // The ref is written immediately (not just via the render mirror) so an
        // awaiting flush re-checks against the synced value, not a stale one.
        if (!res.data.itemised) {
          const currentBody = buildNonItemisedBody(totalInputRef.current)
          if (
            currentBody !== null &&
            JSON.stringify(currentBody) === lastSavedBodyRef.current
          ) {
            const canonical = toFixed2(res.data.quote_total_inc_gst)
            const canonicalBody = buildNonItemisedBody(canonical)
            if (canonicalBody !== null) {
              lastSavedBodyRef.current = JSON.stringify(canonicalBody)
              totalInputRef.current = canonical
              setTotalInput(canonical)
            }
          }
        }
        setSaved(true)
      }
    } catch (err) {
      // Baseline NOT advanced — local edits stay dirty and retry on next change.
      if (mountedRef.current) {
        setSaveError(quoteSaveErrorMessage(err))
        setSaveErrorDetails(
          err instanceof ApiError && err.code === 'VALIDATION_FAILED'
            ? quoteValidationDetails(err.details)
            : [],
        )
      }
    } finally {
      savingRef.current = false
      const pending = pendingBodyRef.current
      pendingBodyRef.current = null
      if (
        pending !== null &&
        JSON.stringify(pending) !== lastSavedBodyRef.current
      ) {
        // AWAIT the drain so the chain promise spans the full queue.
        await runSave(pending)
      } else if (mountedRef.current) {
        setSaving(false)
      }
    }
  }

  // Starts a save chain and records its promise so flushQuoteAutosave can await
  // the full drain. runSave never rejects, so the chain always resolves.
  function startSaveChain(body: QuoteDraftSaveRequest) {
    const chain = runSave(body)
    saveChainRef.current = chain
    void chain.finally(() => {
      if (saveChainRef.current === chain) {
        saveChainRef.current = null
      }
    })
  }

  // Saves the current draft iff it forms a VALID complete body that differs from
  // the persisted baseline. Invalid/blank input is never sent (no partial saves).
  // QUEUE-FIRST while a PUT is in flight (matching the shell's details chain,
  // deliberately NOT LeadEnquirySection's check-baseline-first ordering): the
  // baseline is STALE mid-flight, so comparing against it here would silently
  // drop a revert-to-baseline edit (type 100, then retype the original before
  // the PUT settles -> the wrong total would persist). The drain compares the
  // queued body against the POST-save baseline and skips unchanged bodies.
  function requestSave() {
    if (lockedRef.current || itemisedReadOnly) return
    const body = buildNonItemisedBody(totalInputRef.current)
    if (body === null) return
    if (savingRef.current) {
      // Collapse to the latest complete body while a PUT is in flight.
      pendingBodyRef.current = body
      return
    }
    if (JSON.stringify(body) === lastSavedBodyRef.current) return
    startSaveChain(body)
  }

  function clearDebounce() {
    if (debounceRef.current !== null) {
      window.clearTimeout(debounceRef.current)
      debounceRef.current = null
    }
  }

  function scheduleAutosave() {
    if (lockedRef.current || itemisedReadOnly) return
    clearDebounce()
    debounceRef.current = window.setTimeout(() => {
      debounceRef.current = null
      requestSave()
    }, AUTOSAVE_DEBOUNCE_MS)
  }

  // Flush a pending debounce immediately (text-field blur).
  function flushNow() {
    clearDebounce()
    requestSave()
  }

  function handleTotalChange(value: string) {
    setTotalInput(value)
    setSaved(false)
    setSaveError(null)
    setSaveErrorDetails([])
    // A stale preview error must not sit next to a later "Saved" status.
    setPreviewError(null)
    scheduleAutosave()
  }

  // Awaitable flush used by Preview PDF: force-commit the freshest draft if it
  // is valid + dirty, await the whole save chain, then report a DISCRIMINATED
  // outcome so the preview error message never lies:
  //   'saved'               — the freshest draft is now the persisted one.
  //   'invalid-input'       — the current input cannot form a valid body.
  //   'changed-during-flush'— the flushed body persisted, but the user typed a
  //                           newer (valid) total while the flush ran.
  //   'save-failed'         — a save failed and the baseline did not advance.
  async function flushQuoteAutosave(): Promise<
    'saved' | 'invalid-input' | 'changed-during-flush' | 'save-failed'
  > {
    clearDebounce()
    const startBody = buildNonItemisedBody(totalInputRef.current)
    if (startBody === null) return 'invalid-input'
    const startJson = JSON.stringify(startBody)
    if (savingRef.current) {
      // QUEUE-FIRST (see requestSave): the mid-flight baseline is stale, so the
      // freshest body is always queued for the drain to reconcile.
      pendingBodyRef.current = startBody
    } else if (startJson !== lastSavedBodyRef.current) {
      startSaveChain(startBody)
    }
    const chain = saveChainRef.current
    if (chain) {
      await chain
    }
    const finalBody = buildNonItemisedBody(totalInputRef.current)
    if (finalBody === null) return 'invalid-input'
    if (JSON.stringify(finalBody) === lastSavedBodyRef.current) return 'saved'
    // The freshest input is not the persisted body. If the body we flushed IS
    // persisted, the user typed during the flush; otherwise the save failed.
    // (On success the canonical-total sync rewrites input+baseline together, so
    // an un-touched input still lands in the 'saved' branch above.)
    if (startJson === lastSavedBodyRef.current) return 'changed-during-flush'
    return 'save-failed'
  }

  // Mount/unmount guard + unmount flush. Because OrderWorkspace keeps this tab
  // mounted (hidden) across top-level tab switches, this cleanup fires only on a
  // REAL unmount: an order switch or leaving the workspace. Fire-and-forget with
  // the order id captured up front so a late settle can never target another
  // order. StrictMode's setup/cleanup/setup cycle is safe: at that point the
  // draft still equals the baseline, so nothing is sent.
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      clearDebounce()
      for (const url of previewUrlsRef.current) {
        URL.revokeObjectURL(url)
      }
      previewUrlsRef.current = []
      if (lockedRef.current || itemisedReadOnly) return
      const body = buildNonItemisedBody(totalInputRef.current)
      if (body === null) return
      if (savingRef.current) {
        // A save is in flight — QUEUE-FIRST (the mid-flight baseline is stale;
        // see requestSave): its drain picks up this latest body and reconciles
        // against the post-save baseline. (StrictMode is unaffected — no save
        // is ever in flight during its immediate setup/cleanup cycle.)
        pendingBodyRef.current = body
        return
      }
      if (JSON.stringify(body) === lastSavedBodyRef.current) return
      const saveOrderId = orderIdRef.current
      saveQuoteDraft(saveOrderId, body).catch(() => {})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Branding + terms — two independent fail-soft chains (see InvoiceTab). The
  // config chain drives ONLY the terms section here (no acceptance gate); the
  // public-business chain only ever sets name/logo.
  useEffect(() => {
    let cancelled = false

    setTenantConfigLoading(true)
    setTenantConfigError(false)
    fetchTenantInvoiceConfig()
      .then((config) => {
        if (cancelled) return
        setTenantConfig(config)
        setTenantConfigError(false)
      })
      .catch(() => {
        if (cancelled) return
        setTenantConfig(null)
        setTenantConfigError(true)
      })
      .finally(() => {
        if (cancelled) return
        setTenantConfigLoading(false)
      })

    fetchPublicBusiness(getActiveSlug())
      .then((business) => {
        if (cancelled) return
        setBusinessName(nonBlank(business.name))
        setLogoPath(nonBlank(business.logo_path))
      })
      .catch(() => {
        if (cancelled) return
        setBusinessName(null)
        setLogoPath(null)
      })

    return () => {
      cancelled = true
    }
  }, [brandingReloadToken])

  // A new logo path is a fresh chance to load; clear any prior <img> failure.
  useEffect(() => {
    setLogoFailed(false)
  }, [logoPath])

  // Preview PDF — the backend renders the PERSISTED draft, so flush first (the
  // popup-safe order is locked: open a blank tab synchronously in the click
  // handler, flush, fetch the blob, then point the tab at the object URL; close
  // the tab instead on any failure so no stale/blank tab is left behind).
  async function handlePreviewPdf() {
    if (previewing || !previewEnabled) return
    setPreviewError(null)
    // tab === null right here means a popup blocker intervened; a tab that
    // exists now but is CLOSED by the time the blob is ready means the user
    // deliberately closed it (a cancel — no download, no error).
    const tab = window.open('', '_blank')
    setPreviewing(true)
    try {
      if (!locked && !itemisedReadOnly) {
        const flush = await flushQuoteAutosave()
        if (flush !== 'saved') {
          tab?.close()
          if (mountedRef.current) {
            setPreviewError(
              flush === 'invalid-input'
                ? 'Enter a valid quote total before previewing the PDF.'
                : flush === 'changed-during-flush'
                  ? 'The quote total changed while preparing the preview. Try Preview PDF again.'
                  : 'The quote could not be saved, so the preview was not opened. Fix the save error and try again.',
            )
          }
          return
        }
      }
      const { blob, fileName } = await fetchQuotePreviewPdf(orderIdRef.current)
      if (!mountedRef.current) {
        tab?.close()
        return
      }
      const url = URL.createObjectURL(blob)
      if (tab && !tab.closed) {
        // Revoked on unmount only — revoking now could blank the loading tab.
        previewUrlsRef.current.push(url)
        tab.location.href = url
      } else if (tab === null) {
        // Popup blocked — fall back to a normal download so the preview is
        // still reachable. One-shot URL: consumed by the click, revoke now.
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download = fileName ?? previewFileName(orderNumber)
        document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
        setPreviewError(
          'The preview tab was blocked by the browser, so the PDF was downloaded instead.',
        )
      } else {
        // The user closed the blank tab while the preview was being prepared —
        // treat it as a cancel: no forced download, no error banner.
        URL.revokeObjectURL(url)
      }
    } catch (err) {
      tab?.close()
      if (!mountedRef.current) return
      if (err instanceof ApiError && err.code === 'QUOTE_NOT_FOUND') {
        setPreviewError('Save the quote before previewing the PDF.')
        return
      }
      setPreviewError(
        err instanceof ApiError && err.message.length > 0
          ? err.message
          : 'Could not generate the quote preview PDF. Please try again.',
      )
    } finally {
      if (mountedRef.current) setPreviewing(false)
    }
  }

  const customerName = composeFullName(customer)
  const billingAddressLines = composeAddressLines(billingAddress)
  const detailsOfSaleText = saleDetails?.details_of_sale || ''

  // Per-flooring-type quote terms — same selection + blank-hides-section rule as
  // the invoice (SOFT -> terms_soft, HARD -> terms_hard, no legacy fallback).
  const termsText = nonBlank(
    flooringType === 'SOFT'
      ? tenantConfig?.terms_soft
      : tenantConfig?.terms_hard,
  )
  // IDENTICAL sanitizer config to InvoiceTab (Codex P2) — restricted allowlist,
  // ALL attributes stripped, textless fragments hide the whole section. Do not
  // weaken. dangerouslySetInnerHTML below only ever receives this value.
  const sanitizedTermsHtml = useMemo(() => {
    if (!termsText) return null
    const sanitized = nonBlank(
      DOMPurify.sanitize(termsText, {
        ALLOWED_TAGS: [
          'p',
          'br',
          'strong',
          'b',
          'em',
          'i',
          'u',
          'ol',
          'ul',
          'li',
          'table',
          'thead',
          'tbody',
          'tr',
          'th',
          'td',
          'div',
          'span',
          'small',
        ],
        ALLOWED_ATTR: [],
        ALLOW_DATA_ATTR: false,
        ALLOW_ARIA_ATTR: false,
      }),
    )
    if (!sanitized) return null
    const doc = new DOMParser().parseFromString(sanitized, 'text/html')
    if (!doc.body.textContent?.trim()) return null
    return sanitized
  }, [termsText])

  // --- Derived draft/dirty/validation state (non-itemised editing). ---
  const currentBody = buildNonItemisedBody(totalInput)
  const currentBodyJson = currentBody ? JSON.stringify(currentBody) : null
  const persistedDraftExists = serverDraft !== null
  const totalTrimmed = totalInput.trim()
  // Invalid = non-blank input that cannot form a valid body, OR a cleared input
  // when a draft is already persisted (there is no way to "unsave" a total).
  const totalInvalid =
    !itemisedReadOnly &&
    !locked &&
    ((totalTrimmed !== '' && currentBody === null) ||
      (totalTrimmed === '' && persistedDraftExists))
  // Dirty = the current (valid or invalid) draft differs from the baseline.
  const dirty =
    !itemisedReadOnly &&
    !locked &&
    (currentBody === null
      ? totalInvalid
      : currentBodyJson !== lastSavedBodyRef.current)

  // Preview needs a PERSISTED draft (the backend 404s QUOTE_NOT_FOUND without
  // one). Editable mode may rely on the pre-preview flush persisting the current
  // valid input; locked / read-only modes need the draft already persisted.
  const previewEnabled = itemisedReadOnly
    ? true
    : locked
      ? persistedDraftExists
      : persistedDraftExists || currentBody !== null

  const draftEditable = !locked && !itemisedReadOnly

  return (
    <div>
      <div className="mb-4 flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
            Quote
          </h2>
          <p className="text-sm text-slate-500 mt-1">
            {draftEditable
              ? 'Changes are saved automatically.'
              : 'View the quote for this order.'}
          </p>
        </div>
        {/* Autosave status — the four locked states: Unsaved changes / Saving… /
            Saved / Could not save. Precedence: saving > error > unsaved > saved. */}
        {draftEditable && (
          <div className="shrink-0 pt-0.5 text-xs">
            {saving ? (
              <span className="text-slate-500">Saving…</span>
            ) : saveError ? (
              <span className="text-red-600">Could not save</span>
            ) : dirty ? (
              <span className="text-slate-500">Unsaved changes</span>
            ) : saved ? (
              <span className="inline-flex items-center gap-1 text-teal-700">
                <CheckCircleIcon className="w-3.5 h-3.5" />
                Saved
              </span>
            ) : null}
          </div>
        )}
      </div>

      {locked && (
        <div className="mb-5 rounded-lg border border-slate-200 bg-slate-50 px-4 py-2.5 text-xs text-slate-600">
          This order is laid and locked. The quote can be viewed but not edited.
        </div>
      )}

      <div className="mb-5 -mx-3">
        <Tabs tabs={SUB_TABS} active={subTab} onChange={setSubTab} />
      </div>

      {subTab === 'draft' && (
        <div>
          {/* Protected-surface signals (never inside the customer-facing canvas):
              server-derived quote GP% and the below-cost block. No raw cost. */}
          {serverDraft && (
            <div className="mb-4 flex flex-col gap-1">
              {serverDraft.gp_percent !== null && (
                <span className="text-xs text-slate-500 tabular-nums">
                  Quote GP: {formatPercent(serverDraft.gp_percent)}
                </span>
              )}
              {serverDraft.below_cost && (
                <span className="text-xs font-medium text-red-600">
                  This quote is below cost and cannot be saved or sent until the
                  total covers cost.
                </span>
              )}
            </div>
          )}

          {itemisedReadOnly && (
            <div className="mb-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-xs text-amber-900">
              This quote draft is itemised. Itemised quote editing is coming in
              the next phase — the draft is shown read-only below.
            </div>
          )}

          {saveError && (
            <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
              <p className="text-sm font-medium text-rose-700">{saveError}</p>
              {saveErrorDetails.length > 0 && (
                <ul className="mt-1 space-y-0.5">
                  {saveErrorDetails.map((line, idx) => (
                    <li
                      key={`${line}-${idx}`}
                      className="text-xs text-rose-700 leading-relaxed"
                    >
                      {line}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          {previewError && (
            <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
              <p className="text-sm font-medium text-rose-700">
                {previewError}
              </p>
            </div>
          )}

          {/* The QUOTATION document canvas — mirrors the InvoiceTab document
              layout/styling, reworded for a quotation. Customer-facing: no GP,
              no cost, no acceptance/signature/payment elements. */}
          <article className="rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-6 sm:px-8 sm:py-8 lg:px-10 lg:py-8">
            <header className="grid grid-cols-1 sm:grid-cols-3 gap-6 items-start">
              <div className="sm:col-span-1">
                {/* Tenant logo as a browser <img>, failing soft to the
                    business-name text (same chain as InvoiceTab). */}
                {logoPath && !logoFailed ? (
                  <img
                    src={logoPath}
                    alt={businessName ?? 'Business logo'}
                    className="h-auto w-auto max-h-16 max-w-[240px] object-contain"
                    onError={() => setLogoFailed(true)}
                  />
                ) : (
                  businessName && (
                    <div className="text-base font-bold text-slate-900 tracking-tight">
                      {businessName}
                    </div>
                  )
                )}
              </div>
              <div className="hidden sm:block sm:col-span-1" />
              <div className="sm:col-span-1 sm:text-right">
                <div className="text-3xl font-bold text-slate-900 tracking-tight">
                  QUOTATION
                </div>
                {/* Header right meta is the order number ONLY — matching the
                    invoice screen's Phase 16A minimalism (no store/salesperson). */}
                {orderNumber && (
                  <div className="mt-1 text-sm font-mono text-slate-700">
                    {orderNumber}
                  </div>
                )}
              </div>
            </header>

            <div className="my-6 border-t border-slate-200" />

            <div>
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Quotation To
              </div>
              {customerName ? (
                <div className="mt-2 text-sm font-semibold text-slate-900">
                  {customerName}
                </div>
              ) : (
                <div className="mt-2 text-sm text-slate-400">—</div>
              )}
              {billingAddressLines.length > 0 && (
                <div className="mt-1 text-sm text-slate-700 leading-relaxed">
                  {billingAddressLines.map((line) => (
                    <div key={line}>{line}</div>
                  ))}
                </div>
              )}
            </div>

            <div className="mt-8">
              <div className="text-base font-semibold text-slate-900">
                Details Of Sale
              </div>
              <p className="mt-2 text-sm text-slate-700 leading-relaxed whitespace-pre-wrap">
                {detailsOfSaleText || '—'}
              </p>
            </div>

            {/* Itemised drafts (read-only in PR1): show the saved customer-facing
                lines verbatim. Non-itemised: NO line breakdown, no synthetic
                "Quoted works" row — the backend's internal synthetic line is
                never rendered. */}
            {itemisedReadOnly && serverDraft && (
              <div className="mt-8 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-slate-200 text-[11px] uppercase tracking-wider text-slate-500">
                      <th className="py-2 pr-3 text-left font-semibold">
                        Description
                      </th>
                      <th className="py-2 px-3 text-right font-semibold">
                        Qty
                      </th>
                      <th className="py-2 px-3 text-right font-semibold">
                        Unit price
                      </th>
                      <th className="py-2 pl-3 text-right font-semibold">
                        Amount
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {serverDraft.lines.map((line, idx) => (
                      <tr
                        key={line.quote_draft_line_id ?? `line-${idx}`}
                        className="border-b border-slate-100"
                      >
                        <td className="py-2 pr-3 text-slate-800">
                          {line.description}
                        </td>
                        <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                          {line.quantity !== undefined
                            ? toFixed2(line.quantity)
                            : '—'}
                        </td>
                        <td className="py-2 px-3 text-right tabular-nums text-slate-700">
                          {line.unit_price_ex_gst !== undefined
                            ? formatMoney(line.unit_price_ex_gst)
                            : '—'}
                        </td>
                        <td className="py-2 pl-3 text-right tabular-nums text-slate-900">
                          {formatMoney(line.line_total_ex_gst)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* Totals — right-aligned card like the invoice, WITHOUT the
                invoice-only Payment Made / Balance Due rows. Non-itemised: the
                GST-inclusive quote total is the editable field. */}
            <div className="mt-8 sm:ml-auto sm:w-[360px] rounded-md border border-slate-200">
              {itemisedReadOnly && serverDraft ? (
                <>
                  <div className="flex items-center justify-between px-4 py-2.5">
                    <span className="text-sm text-slate-700">
                      Total Ex. GST
                    </span>
                    <span className="text-sm font-medium tabular-nums text-slate-900">
                      {formatMoney(serverDraft.quote_total_ex_gst)}
                    </span>
                  </div>
                  <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
                    <span className="text-sm text-slate-700">
                      Quote Total Inc. GST
                    </span>
                    <span className="text-sm font-semibold tabular-nums text-slate-900">
                      {formatMoney(serverDraft.quote_total_inc_gst)}
                    </span>
                  </div>
                </>
              ) : (
                <>
                  <div className="px-4 py-2.5">
                    <label
                      htmlFor="quote_total_inc_gst"
                      className="block text-sm text-slate-700 mb-1.5"
                    >
                      Quote Total Inc. GST
                    </label>
                    <Input
                      id="quote_total_inc_gst"
                      type="text"
                      inputMode="decimal"
                      value={totalInput}
                      onChange={(e) => handleTotalChange(e.target.value)}
                      onBlur={flushNow}
                      invalid={totalInvalid}
                      disabled={!draftEditable}
                      placeholder="0.00"
                      className="font-semibold tabular-nums text-right"
                    />
                    {totalInvalid ? (
                      <p className="mt-1.5 text-xs text-red-600" role="alert">
                        Enter a quote total between 0 and 99,999,999.99.
                      </p>
                    ) : totalTrimmed === '' && !persistedDraftExists ? (
                      <p className="mt-1.5 text-xs text-slate-400">
                        Enter the customer-facing quote total to start this
                        quote.
                      </p>
                    ) : null}
                  </div>
                  {serverDraft && !serverDraft.itemised && (
                    <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
                      <span className="text-sm text-slate-700">
                        Total Ex. GST
                      </span>
                      <span className="text-sm font-medium tabular-nums text-slate-900">
                        {formatMoney(serverDraft.quote_total_ex_gst)}
                      </span>
                    </div>
                  )}
                </>
              )}
            </div>

            <div className="my-8 border-t border-slate-200" />

            {sanitizedTermsHtml ? (
              <div>
                <div className="text-center text-xs font-semibold uppercase tracking-wider text-slate-800">
                  Terms and Conditions — applicable to this quote
                </div>
                {/* Sanitized HTML only (see sanitizedTermsHtml). The scoped
                    `.invoice-terms` CSS in index.css is generic terms styling
                    (lists/tables restored past Tailwind preflight) — reused here
                    unchanged; screen-only. */}
                <div
                  className="invoice-terms mt-4 text-xs text-slate-700 leading-relaxed"
                  dangerouslySetInnerHTML={{ __html: sanitizedTermsHtml }}
                />
              </div>
            ) : tenantConfigError ? (
              <div className="text-center">
                <p className="text-xs text-slate-500">
                  Quote terms could not be loaded.
                </p>
                <div className="mt-2 flex justify-center">
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    onClick={() =>
                      setBrandingReloadToken((token) => token + 1)
                    }
                  >
                    Try again
                  </Button>
                </div>
              </div>
            ) : tenantConfigLoading ? (
              <p className="text-center text-xs text-slate-400">
                Loading quote terms…
              </p>
            ) : null}

            {/* Bottom action bar — Preview PDF + Send Quote (locked labels).
                Native buttons for the full-width document-anchor styling, like
                the invoice action bar. No Save button — the draft autosaves. */}
            <div className="mt-8 flex flex-col sm:flex-row gap-3">
              <button
                type="button"
                onClick={handlePreviewPdf}
                disabled={previewing || !previewEnabled}
                title={
                  !previewEnabled
                    ? 'Save a quote total before previewing the PDF.'
                    : undefined
                }
                className="flex w-full items-center justify-center rounded-md border border-teal-600 bg-white px-6 py-3.5 text-sm font-semibold text-teal-700 shadow-sm transition-colors hover:bg-teal-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {previewing ? 'Preparing…' : 'Preview PDF'}
              </button>
              <button
                type="button"
                onClick={() => setSendModalOpen(true)}
                className="flex w-full items-center justify-center rounded-md bg-teal-600 px-6 py-3.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40"
              >
                Send Quote
              </button>
            </div>
          </article>
        </div>
      )}

      {subTab === 'customer' && (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-10 text-center">
          <p className="text-base font-medium text-slate-700">
            No quote has been sent yet.
          </p>
        </div>
      )}

      {subTab === 'accepted' && (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-10 text-center">
          <p className="text-base font-medium text-slate-700">
            No quote has been accepted yet.
          </p>
        </div>
      )}

      {/* Send Quote confirmation — 16D-B shows the modal ONLY. No send endpoint
          exists on main; Email/SMS arrive in 16E, always through this modal
          (hard rule: no accidental one-click sending). */}
      <Modal
        open={sendModalOpen}
        onClose={() => setSendModalOpen(false)}
        labelledBy="send-quote-title"
      >
        <div className="p-6">
          <h3
            id="send-quote-title"
            className="text-lg font-semibold text-slate-900 tracking-tight"
          >
            Are you sure you want to send this quote?
          </h3>
          <p className="mt-2 text-sm text-slate-600 leading-relaxed">
            Please double-check all quote details before sending.
          </p>
          <div className="mt-5 space-y-2">
            <Button
              type="button"
              variant="success"
              size="md"
              disabled
              className="w-full"
            >
              Send by Email
            </Button>
            <Button
              type="button"
              variant="success"
              size="md"
              disabled
              className="w-full"
            >
              Send by Phone/SMS
            </Button>
            <p className="text-center text-[11px] text-slate-500">
              Sending by Email and Phone/SMS is coming in 16E.
            </p>
          </div>
          <div className="mt-4 flex justify-end">
            <Button
              type="button"
              variant="secondary"
              size="md"
              onClick={() => setSendModalOpen(false)}
            >
              Cancel
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
