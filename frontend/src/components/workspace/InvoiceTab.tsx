import { useEffect, useMemo, useRef, useState } from 'react'
import DOMPurify from 'dompurify'
import { Button } from '../ui/Button'
import { SignaturePad, type SignaturePadHandle } from './SignaturePad'
import { ApiError } from '../../lib/api/ApiError'
import {
  ACCEPTED_NAME_MAX_LENGTH,
  acceptCurrentInvoice,
  fetchCurrentInvoice,
  fetchCurrentInvoicePdf,
  fetchCurrentInvoiceSignature,
  resendCurrentInvoice,
  type InvoiceDetail,
} from '../../lib/api/orderInvoicesApi'
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

type Props = {
  orderId: number
  // The order's flooring type (SOFT or HARD). An order has exactly one type, which
  // selects WHICH per-tenant terms block (terms_soft vs terms_hard) renders on the
  // invoice — see termsText below.
  flooringType: FlooringType
  // View/download + Phase 13 acceptance (sign / accept / re-send). Create + Rewrite
  // live in the Details of Sale tab now. NOTE: LAID/`locked` is deliberately NOT a
  // prop here — Accept, Re-send and the signature download are all allowed when
  // LAID (acceptance parallels payment, not rewrite), so the lock never gates this tab.
  orderNumber?: string
  customer?: OrderCustomer | null
  billingAddress?: OrderAddress | null
  saleDetails?: DetailsOfSaleFields | null
  // Order-bound salesperson name (sales_order.user_id -> app_user first/last), the same
  // source the PDF uses — NOT the session user. Rendered in the document meta; hidden
  // (fail-soft) when null/blank, like every other optional tenant/store field.
  salespersonName?: string | null
  // Switches the workspace to the Customer tab when an accept/resend fails with
  // CUSTOMER_EMAIL_REQUIRED / CUSTOMER_EMAIL_INVALID so the salesperson can fix the
  // email where it lives.
  onGoToCustomer?: () => void
}

const MONEY_FORMATTER = new Intl.NumberFormat('en-AU', {
  style: 'currency',
  currency: 'AUD',
})

function formatMoney(value: number): string {
  return MONEY_FORMATTER.format(value)
}

function formatDocDate(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso)
  if (!match) return ''
  const [, year, month, day] = match
  return `${day}/${month}/${year}`
}

// Tiny local timestamp formatter for accepted_at / last_emailed_at (matches the
// PaymentsTab/PhotoPreviewModal convention of small self-contained copies).
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

function formatTimestamp(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso)
  if (!match) return ''
  const [, year, month, day, hours, minutes] = match
  const name = MONTH_NAMES[Number(month) - 1]
  if (!name) return ''
  return `${day} ${name} ${year}, ${hours}:${minutes}`
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

// Download filename mirrors the backend Content-Disposition pattern
// (invoice-{order_number}-v{version}.pdf). A blob object URL does not carry the
// server's Content-Disposition, so the anchor sets the name explicitly.
function pdfFileName(
  orderNumber: string | undefined,
  versionNumber: number,
): string {
  const safeOrder =
    orderNumber && orderNumber.length > 0 ? orderNumber : 'order'
  return `invoice-${safeOrder}-v${versionNumber}.pdf`
}

// Trim a nullable/optional string and return it only when it has visible content;
// otherwise null. Lets the invoice hide tenant-config rows that are null/blank rather
// than render an empty label, a sample fallback, or a "—" placeholder.
function nonBlank(value: string | null | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed && trimmed.length > 0 ? trimmed : null
}

const ACCEPTANCE_LINES: string[] = [
  'I agree to pay the balance before the installation date.',
  'I agree that no floor preparation costs are included unless otherwise stated above.',
]

export function InvoiceTab({
  orderId,
  flooringType,
  orderNumber,
  customer,
  billingAddress,
  saleDetails,
  onGoToCustomer,
}: Props) {
  // Only ever the CURRENT/latest invoice — no version list, no history.
  const [invoice, setInvoice] = useState<InvoiceDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  // Backend success messages (accept / resend) surfaced VERBATIM.
  const [actionMessage, setActionMessage] = useState<string | null>(null)
  // True when the last accept/resend failed on the customer-email gate, so the
  // error banner can point the salesperson at the Customer tab.
  const [emailFixNeeded, setEmailFixNeeded] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const [resending, setResending] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)

  // Phase 15B — per-tenant invoice metadata for the document terms. The business name +
  // private invoice-config are fetched in a SEPARATE, order-independent effect below and FAIL
  // SOFT: a failure hides the affected rows, never blocks the invoice document, and never
  // routes through loadError. (Phase 16A PR1: store details are no longer shown on this screen,
  // so the auth store context is no longer read here.)
  const [tenantConfig, setTenantConfig] = useState<TenantInvoiceConfig | null>(
    null,
  )
  const [businessName, setBusinessName] = useState<string | null>(null)
  // Phase 16A PR1 — demo/tenant logo for the document header. Sourced from the fail-soft
  // public-business lookup (NOT the acceptance-gating tenant config), rendered as a browser
  // <img> that fails soft to the business-name text. logoFailed flips on an <img> load error
  // and resets whenever the path changes (effect below).
  const [logoPath, setLogoPath] = useState<string | null>(null)
  const [logoFailed, setLogoFailed] = useState(false)
  // Legal-risk gate (Codex P1): Accept must be unavailable until the tenant invoice
  // config is SAFELY known. Configured per-type terms (terms_soft/terms_hard) may exist
  // but not yet have rendered, so the customer must not sign/accept while the config is
  // still loading or failed to load. `tenantConfigLoading` is true until the config request resolves;
  // `tenantConfigError` is true when it failed. (The public business / name request
  // stays soft — see the effect below — and never gates Accept.)
  const [tenantConfigLoading, setTenantConfigLoading] = useState(true)
  const [tenantConfigError, setTenantConfigError] = useState(false)

  // Acceptance draft (unaccepted invoice only): whether the pad has at least one
  // drawn stroke. The accepted customer name is NOT typed — it is derived from the
  // order's saved customer (see derivedAcceptedName below).
  const [hasInk, setHasInk] = useState(false)
  const padRef = useRef<SignaturePadHandle | null>(null)

  // Phase 16A PR1 — acceptance checkboxes (frontend-only gate; NEVER sent to the backend —
  // the accept contract stays exactly { accepted_customer_name, signature }). One boolean per
  // ACCEPTANCE_LINES entry. Reset structurally on invoice identity/version change (effect
  // below) so a tick never leaks across a reload, rewrite, or payment/void regeneration.
  const [acceptanceChecked, setAcceptanceChecked] = useState<boolean[]>(() =>
    Array(ACCEPTANCE_LINES.length).fill(false),
  )

  // Stored accepted-signature display (object URL owned by the effect below).
  // While the fetch is in flight, signatureUrl is null and signatureFailed false —
  // the render's accepted_signature_present branch shows "Loading signature…".
  const [signatureUrl, setSignatureUrl] = useState<string | null>(null)
  const [signatureFailed, setSignatureFailed] = useState(false)

  // Tracks whether THIS tab is still mounted so async action handlers never
  // setState after the tab unmounts on a tab switch. The mount fetch below uses
  // its own per-run `cancelled` guard.
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // Fetch the current invoice on mount (the tab unmounts on a tab switch, so this
  // refetches fresh each open) and whenever reloadToken bumps. A 404
  // INVOICE_NOT_FOUND is the clean "no invoice yet" empty state, NOT an error.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    setActionError(null)
    setActionMessage(null)
    setEmailFixNeeded(false)
    // A reload is a fresh start for the acceptance draft too (the pad below
    // remounts empty, so the ink gate must match it).
    setHasInk(false)
    setInvoice(null)
    fetchCurrentInvoice(orderId)
      .then((res) => {
        if (cancelled) return
        setInvoice(res.data.invoice)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
          setInvoice(null)
          return
        }
        setLoadError(
          err instanceof ApiError && err.message.length > 0
            ? err.message
            : 'Could not load the invoice. Please try again.',
        )
      })
      .finally(() => {
        if (cancelled) return
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [orderId, reloadToken])

  // Phase 15B — load tenant invoice metadata. SEPARATE from the invoice fetch:
  // tenant-scoped and order-independent (never keyed on orderId), refreshed on
  // reloadToken so "Try again" refreshes it too. The two requests run as FULLY
  // INDEPENDENT chains (no Promise.all/allSettled, no awaiting one before the other),
  // so a slow/hung optional lookup can never hold up the legal gate (Codex P2):
  //   - fetchTenantInvoiceConfig is the legal-risk gate (Codex P1) and is the SOLE
  //     driver of tenantConfig / tenantConfigLoading / tenantConfigError. Its loading
  //     flag clears on its OWN settlement, so once the config has safely loaded Accept
  //     is no longer blocked even if the public business lookup is still pending.
  //   - fetchPublicBusiness is optional branding: it only ever sets businessName and
  //     NEVER touches the config gate, so its failure/hang fails soft (the store name
  //     still comes from auth). Neither chain ever touches invoice/loadError.
  useEffect(() => {
    let cancelled = false

    // Legal-risk gate chain: re-enter the gated state on every (re)load, then let ONLY
    // this chain's settlement clear the loading flag — on BOTH success and failure.
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
        // Config failed → null it and raise the error so Accept stays disabled
        // (configured terms may exist but failed to load).
        setTenantConfig(null)
        setTenantConfigError(true)
      })
      .finally(() => {
        if (cancelled) return
        setTenantConfigLoading(false)
      })

    // Optional branding chain — independent; only ever sets businessName and NEVER
    // touches tenantConfig / tenantConfigLoading / tenantConfigError under any outcome.
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
  }, [reloadToken])

  // Codex (signature-before-terms) — keep the signature gate and the ink in sync: while
  // the tenant invoice config is loading or failed (i.e. NOT ready for acceptance), the
  // pad is disabled AND any already-drawn ink is dropped, so a signature drawn before the
  // terms rendered can never become submittable once the config later loads. Keyed on the
  // RAW flags (tenantConfigLoading || tenantConfigError, exactly
  // !tenantConfigReadyForAcceptance) so this hook groups with the other effects above the
  // JSX return rather than depending on a const declared just before render — and stays
  // hook-order-safe if an early return is ever added later.
  useEffect(() => {
    if (tenantConfigLoading || tenantConfigError) {
      padRef.current?.clear()
      setHasInk(false)
    }
  }, [tenantConfigLoading, tenantConfigError])

  // Phase 16A PR1 — reset the acceptance checkboxes whenever the invoice IDENTITY or VERSION
  // changes (initial load, accept, drift resync, rewrite, payment/void regeneration). A keyed
  // reset, NOT a manual list of setHasInk(false) sites, so it cannot drift if those sites
  // change. The ACCEPTED branch shows the boxes checked/read-only from the persisted
  // acceptance, never from this state (which has just been reset).
  useEffect(() => {
    setAcceptanceChecked(Array(ACCEPTANCE_LINES.length).fill(false))
  }, [invoice?.invoice_id, invoice?.version_number])

  // Phase 16A PR1 — a new logo path is a fresh chance to load; clear any prior <img> failure.
  useEffect(() => {
    setLogoFailed(false)
  }, [logoPath])

  function refetch() {
    setReloadToken((token) => token + 1)
  }

  // The D.10 stream is only fetched when a stored signature actually exists.
  const signaturePath =
    invoice !== null && invoice.accepted_signature_present
      ? invoice.accepted_signature_download_path
      : null
  // Version identity for the signature effect key. The download path is the
  // STABLE /current/signature URL for every invoice version, so keying on the
  // path alone would NOT refetch when a DIFFERENT accepted version arrives with
  // the same path (e.g. the 409 INVOICE_ALREADY_ACCEPTED resync after another
  // session rewrote and re-accepted) — the previous version's image would stay
  // displayed against the new version's name/timestamp. invoice_id is unique
  // per version row; a Re-send (same row, no new version) keeps the same id and
  // deliberately does not refetch the unchanged image.
  const signatureInvoiceId = invoice !== null ? invoice.invoice_id : null

  // Fetch + display the stored accepted signature (credentialed blob -> object
  // URL). OBJECT URL OWNERSHIP (mirrors PhotoPreviewModal): the closure-tracked
  // `objectUrl` + `cancelled` guard ensure the URL is revoked on unmount, on
  // invoice change/reload (signaturePath transitions), and when the blob resolves
  // AFTER cleanup — no leak, no setState-after-unmount. A signature fetch failure
  // degrades to a small inline notice; it never breaks the invoice tab.
  useEffect(() => {
    if (signaturePath === null) {
      setSignatureUrl(null)
      setSignatureFailed(false)
      return
    }
    let cancelled = false
    let objectUrl: string | null = null // closure-tracked — authoritative for revoke
    setSignatureUrl(null)
    setSignatureFailed(false)
    fetchCurrentInvoiceSignature(signaturePath)
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob)
        if (cancelled) {
          // Resolved after cleanup: revoke now, never set state.
          URL.revokeObjectURL(objectUrl)
          objectUrl = null
          return
        }
        setSignatureUrl(objectUrl)
      })
      .catch(() => {
        // Includes the defensive backend guards (404 INVOICE_NOT_FOUND /
        // 422 INVOICE_NOT_ACCEPTED) — show the inline fallback, keep the tab usable.
        if (!cancelled) setSignatureFailed(true)
      })
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [signaturePath, signatureInvoiceId])

  // Inline current-invoice resync for drift errors (409 INVOICE_ALREADY_ACCEPTED,
  // 422 INVOICE_REQUIRED / INVOICE_NOT_ACCEPTED). Deliberately NOT refetch(): a
  // reloadToken bump clears the banners, and these cases need the explanatory
  // banner to survive while the fresh state (accepted / empty) renders.
  async function resyncCurrentInvoice() {
    try {
      const res = await fetchCurrentInvoice(orderId)
      if (!mountedRef.current) return
      // Also wipe drawn-but-unsubmitted ink: when the fresh invoice is still
      // unaccepted the pad stays MOUNTED, and stale strokes must never be
      // submittable against a different version (hasInk alone only gates the
      // button — the old ink would otherwise ride along with the next stroke).
      padRef.current?.clear()
      setHasInk(false)
      setInvoice(res.data.invoice)
    } catch (err) {
      if (!mountedRef.current) return
      if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
        padRef.current?.clear()
        setHasInk(false)
        setInvoice(null)
        return
      }
      // Other resync failures: keep the current view — the banner already
      // explains the situation and the user can switch tabs to refresh.
    }
  }

  // Shared accept/resend error handling. Backend messages are surfaced VERBATIM
  // whenever present; the per-code branches only add the right recovery behaviour.
  async function applyAcceptResendError(err: unknown, fallback: string) {
    if (err instanceof ApiError) {
      const message = err.message.length > 0 ? err.message : fallback
      if (err.code === 'INVOICE_ALREADY_ACCEPTED') {
        // Drift: this invoice was accepted elsewhere. Surface the backend message
        // and pull the accepted state.
        setActionError(message)
        await resyncCurrentInvoice()
        return
      }
      if (
        err.code === 'CUSTOMER_EMAIL_REQUIRED' ||
        err.code === 'CUSTOMER_EMAIL_INVALID'
      ) {
        // The fix lives on the Customer tab — the banner adds the pointer/button.
        setActionError(message)
        setEmailFixNeeded(true)
        return
      }
      if (
        err.code === 'INVOICE_REQUIRED' ||
        err.code === 'INVOICE_NOT_ACCEPTED'
      ) {
        // Drift: the current invoice vanished (-> empty state) or is no longer
        // accepted (e.g. rewritten elsewhere -> signature pad again).
        setActionError(message)
        await resyncCurrentInvoice()
        return
      }
      // Everything else — including 502 EMAIL_SEND_FAILED on resend, where the
      // backend wrote NOTHING, so the invoice state must stay untouched.
      setActionError(message)
      return
    }
    setActionError(fallback)
  }

  async function handleAccept() {
    // Stray-call guard: never accept while the tenant invoice config is not safely
    // loaded (Codex P1) — configured terms may exist but not yet have rendered.
    if (
      accepting ||
      resending ||
      invoice === null ||
      !tenantConfigReadyForAcceptance ||
      !acceptanceChecked.every(Boolean)
    )
      return
    const displayed = invoice
    // accepted_customer_name is auto-derived from the order's saved customer —
    // the customer never types it. A blank or over-length name disables Accept
    // in the render; these guards keep a stray call from POSTing an invalid part
    // (over-length is blocked outright, never silently truncated).
    const name = derivedAcceptedName
    if (
      name.length === 0 ||
      name.length > ACCEPTED_NAME_MAX_LENGTH ||
      !hasInk
    ) {
      return
    }
    setAccepting(true)
    setActionError(null)
    setActionMessage(null)
    setEmailFixNeeded(false)
    try {
      // BEST-EFFORT STALE-TAB GUARD: the accept endpoint targets /current, but
      // another session may have recorded a payment or rewritten the invoice
      // after this tab loaded — the customer would have reviewed/signed an
      // invoice that is no longer /current. Refetch and compare the version
      // identity (invoice_id + version_number) BEFORE accepting; on mismatch,
      // show the fresh invoice and require a re-sign instead of silently
      // accepting a different version. This is best-effort only: a
      // millisecond-level race window remains between this check and the accept
      // POST because the backend has no version precondition on accept.
      let fresh: InvoiceDetail
      try {
        const check = await fetchCurrentInvoice(orderId)
        fresh = check.data.invoice
      } catch (err) {
        if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
          // The invoice vanished server-side — never accept; drop the unsigned
          // draft and render the no-invoice empty state.
          if (!mountedRef.current) return
          padRef.current?.clear()
          setHasInk(false)
          setInvoice(null)
          setActionError('This invoice is no longer available.')
          return
        }
        // Any other pre-check failure blocks the accept (freshness unverified)
        // and falls through to the shared error handler below.
        throw err
      }
      if (!mountedRef.current) return
      if (
        fresh.invoice_id !== displayed.invoice_id ||
        fresh.version_number !== displayed.version_number
      ) {
        // The displayed invoice is stale. Render the fresh one (the accepted
        // state renders normally if it is already accepted), drop the unsigned
        // signature draft, and do NOT accept.
        padRef.current?.clear()
        setHasInk(false)
        setInvoice(fresh)
        setActionError(
          'The invoice changed since it was loaded. Please review the updated invoice and sign again.',
        )
        return
      }
      // Unchanged — continue with the normal accept flow.
      // canvas.toBlob('image/png') — the exact declared content type the backend
      // requires for the `signature` part.
      const blob = await padRef.current?.toBlob()
      if (!mountedRef.current) return
      if (!blob) {
        setActionError('Could not read the signature. Please sign again.')
        return
      }
      const res = await acceptCurrentInvoice(orderId, name, blob)
      if (!mountedRef.current) return
      // The 201 body is the FINAL post-email state (accepted_at set;
      // last_emailed_at set iff the auto-email succeeded) — no follow-up GET.
      setInvoice(res.data.invoice)
      setActionMessage(
        res.message && res.message.length > 0 ? res.message : 'Invoice accepted.',
      )
      // The signed state replaces the pad; drop the local unsigned draft.
      setHasInk(false)
    } catch (err) {
      if (!mountedRef.current) return
      await applyAcceptResendError(
        err,
        'Could not accept the invoice. Please try again.',
      )
    } finally {
      if (mountedRef.current) setAccepting(false)
    }
  }

  async function handleResend() {
    if (accepting || resending || invoice === null) return
    setResending(true)
    setActionError(null)
    setActionMessage(null)
    setEmailFixNeeded(false)
    try {
      const res = await resendCurrentInvoice(orderId)
      if (!mountedRef.current) return
      // Success bumps only last_emailed_at (no new version) — apply the returned state.
      setInvoice(res.data.invoice)
      setActionMessage(
        res.message && res.message.length > 0
          ? res.message
          : 'Invoice re-sent to the customer.',
      )
    } catch (err) {
      if (!mountedRef.current) return
      await applyAcceptResendError(
        err,
        'Could not re-send the invoice. Please try again.',
      )
    } finally {
      if (mountedRef.current) setResending(false)
    }
  }

  function handleClearSignature() {
    if (accepting) return
    // clear() also fires onInkChange(false), which disables Accept.
    padRef.current?.clear()
  }

  async function handleDownload() {
    if (downloading || invoice === null) return
    const current = invoice
    setDownloading(true)
    setActionError(null)
    setActionMessage(null)
    setEmailFixNeeded(false)
    try {
      const { blob, fileName } = await fetchCurrentInvoicePdf(orderId)
      if (!mountedRef.current) return
      // One-shot object URL: create, trigger the download, revoke immediately. Prefer the server
      // file name from the D.4 Content-Disposition (authoritative for the CURRENT invoice the
      // backend just streamed, so it stays correct even if the invoice changed since the last
      // read); fall back to the client-side name only when the header is absent/unreadable.
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download =
        fileName ?? pdfFileName(orderNumber, current.version_number)
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (err) {
      if (!mountedRef.current) return
      // The invoice vanished server-side — show a message and refetch state.
      if (err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND') {
        setActionError('This invoice is no longer available. Refreshing…')
        refetch()
        return
      }
      setActionError(
        err instanceof ApiError && err.message.length > 0
          ? err.message
          : 'Could not download the invoice PDF. Please try again.',
      )
    } finally {
      if (mountedRef.current) setDownloading(false)
    }
  }

  const customerName = composeFullName(customer)
  // accepted_customer_name sent on Accept — auto-derived from the order's saved
  // customer (same composeFullName source as the document's "Invoice To" block),
  // trimmed, never typed by the customer. Blank (no saved customer) or
  // over-length (> 150 chars) blocks Accept with an inline notice — the name is
  // never silently truncated. The ACCEPTED state renders
  // invoice.accepted_customer_name (the immutable response snapshot), not this
  // live value.
  const derivedAcceptedName = customerName.trim()
  const derivedNameTooLong =
    derivedAcceptedName.length > ACCEPTED_NAME_MAX_LENGTH
  const billingAddressLines = composeAddressLines(billingAddress)
  const detailsOfSaleText =
    invoice?.details_of_sale_snapshot || saleDetails?.details_of_sale || ''
  const dueDateText =
    invoice && invoice.due_date ? formatDocDate(invoice.due_date) : ''

  // Phase 16A PR1 — the on-screen Invoice header is intentionally minimal to match the
  // CarpetCall reference: business logo (or business-name fallback) on the left, and
  // TAX INVOICE + number on the right. Store name/address/phone/email, ABN, bank/payment
  // details, Flooring Type and Salesperson are deliberately NOT rendered on this screen. All of
  // that data is unchanged in auth/workspace/tenant-config and still appears on the PDF.
  // Per-flooring-type terms (Phase 15B-2): a SOFT order shows terms_soft, a HARD order
  // shows terms_hard. Exactly one block renders. NO fallback to the legacy single-block
  // terms_and_conditions — an unset per-type terms block hides like any other null/blank
  // tenant-config row.
  const termsText = nonBlank(
    flooringType === 'SOFT'
      ? tenantConfig?.terms_soft
      : tenantConfig?.terms_hard,
  )
  // Tenant per-type terms are stored as HTML (sanitized server-side for the PDF). Render them
  // as HTML on screen too, but ALWAYS sanitize client-side first (defense in depth) with an
  // EXPLICIT restricted allowlist — NOT DOMPurify defaults. Everything outside the list (script,
  // style, iframe/object/embed, form controls, img, a, event handlers, javascript: URLs, remote
  // images) is stripped. Memoised on termsText so a large block isn't re-sanitized every render.
  // The terms section hides when termsText is blank, sanitization yields a blank string, OR
  // the sanitized HTML has no visible text (textless markup). The dangerouslySetInnerHTML
  // below receives ONLY this sanitized value, never raw termsText.
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
        // No tenant-provided attributes survive. Codex P2: tenant terms are injected into the
        // live app DOM with Tailwind loaded, so allowing `class` let tenant terms apply app
        // utilities (hidden, fixed, inset-0, z-50, …) to hide themselves past the visible-text
        // check or overlay the acceptance screen. Strip ALL attributes — tenant class, data-*,
        // aria-*, event handlers, and URLs are all removed. Readability (list markers, table
        // borders) comes solely from our scoped `.invoice-terms` CSS, never tenant classes.
        ALLOWED_ATTR: [],
        ALLOW_DATA_ATTR: false,
        ALLOW_ARIA_ATTR: false,
      }),
    )
    if (!sanitized) return null
    // A sanitized-but-TEXTLESS fragment (e.g. <p></p>, <div class="x"></div>, an empty
    // table) is still a non-blank STRING yet renders nothing — it must hide the whole
    // section (no empty "Terms and Conditions" heading). Parse the sanitized HTML into an
    // INERT document (parseFromString does not execute scripts or load resources) and
    // require visible text before returning. A real table/list WITH text still renders.
    const doc = new DOMParser().parseFromString(sanitized, 'text/html')
    if (!doc.body.textContent?.trim()) return null
    return sanitized
  }, [termsText])

  // Accept is gated on the tenant invoice config being SAFELY loaded (Codex P1): not
  // loading and not errored. Configured terms may exist but not yet have rendered, so
  // signing/accepting before this is true is a legal risk. (Business-name failure is
  // soft and never affects this flag.)
  const tenantConfigReadyForAcceptance =
    !tenantConfigLoading && !tenantConfigError
  // Phase 16A PR1 — all acceptance checkboxes ticked (frontend-only gate for Accept).
  const allAcceptanceChecked = acceptanceChecked.every(Boolean)
  // Phase 16A PR1 — drives the bottom composition (checkbox read-only state + Accept vs
  // Re-send action bar). Persisted by the invoice acceptance event, never by local state.
  const invoiceAccepted = invoice !== null && invoice.accepted_at !== null

  return (
    <div>
      <div className="mb-4 flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
            Invoice
          </h2>
          <p className="text-sm text-slate-500 mt-1">
            View the current invoice for this order.
          </p>
        </div>
        {!loading && loadError === null && invoice !== null && (
          <div className="flex items-center gap-2 shrink-0">
            <Button
              type="button"
              variant="secondary"
              size="md"
              onClick={handleDownload}
              disabled={downloading}
            >
              {downloading ? 'Preparing…' : 'Download PDF'}
            </Button>
            {/* Phase 16A PR1 — the top action area holds ONLY Download PDF. Re-send is no
                longer duplicated here; it lives solely in the accepted signature block below
                (near the signature), matching the CarpetCall layout. */}
          </div>
        )}
      </div>

      {actionMessage !== null && (
        <div className="mb-4 rounded-lg border border-teal-200 bg-teal-50 px-4 py-3">
          <p className="text-sm font-medium text-teal-700">{actionMessage}</p>
        </div>
      )}

      {actionError !== null && (
        <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
          <p className="text-sm font-medium text-rose-700">{actionError}</p>
          {emailFixNeeded && (
            <div className="mt-2 flex flex-col sm:flex-row sm:items-center gap-2">
              <p className="text-xs text-rose-700">
                Add or fix the customer's email on the Customer tab, then try
                again.
              </p>
              {onGoToCustomer && (
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={onGoToCustomer}
                >
                  Go to Customer tab
                </Button>
              )}
            </div>
          )}
        </div>
      )}

      {loading ? (
        <div className="rounded-xl border border-slate-200 bg-white px-6 py-10 text-center">
          <p className="text-sm text-slate-500">Loading invoice…</p>
        </div>
      ) : loadError !== null ? (
        <div className="rounded-xl border border-rose-200 bg-white px-6 py-10 text-center">
          <p className="text-base font-medium text-slate-700">{loadError}</p>
          <div className="mt-4">
            <Button
              type="button"
              variant="secondary"
              size="md"
              onClick={refetch}
            >
              Try again
            </Button>
          </div>
        </div>
      ) : invoice !== null ? (
        <article className="rounded-lg border border-slate-200 bg-white shadow-sm px-6 py-6 sm:px-8 sm:py-8 lg:px-10 lg:py-8">
          <header className="grid grid-cols-1 sm:grid-cols-3 gap-6 items-start">
            <div className="sm:col-span-1">
              {/* Phase 16A PR1 — render the tenant/demo logo as a browser <img> when a
                  non-blank path is present and has not failed to load; otherwise fall back to
                  the business-name text. The path comes from the fail-soft public-business
                  lookup, so a logo problem never affects the acceptance gate, and there is no
                  hardcoded /demo-logos value here (the value is whatever the API returns). */}
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
              {/* Phase 16A PR1 — store name/address/phone/email + ABN are intentionally NOT
                  shown in the header (closer to the CarpetCall reference): logo, or the
                  business-name fallback, only. The store/ABN data is unchanged in
                  auth/workspace/tenant-config and on the PDF. */}
            </div>
            <div className="hidden sm:block sm:col-span-1" />
            <div className="sm:col-span-1 sm:text-right">
              <div className="text-3xl font-bold text-slate-900 tracking-tight">
                TAX INVOICE
              </div>
              {orderNumber && (
                <div className="mt-1 text-sm font-mono text-slate-700">
                  {orderNumber}
                </div>
              )}
            </div>
          </header>

          <div className="my-6 border-t border-slate-200" />

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
            <div>
              <div className="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
                Invoice To
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
            <div className="sm:text-right space-y-1.5">
              <p className="text-sm text-slate-700">
                This is your only invoice. Pay as agreed herein.
              </p>
              <p className="text-sm font-semibold text-slate-900 uppercase tracking-wide">
                Customer to confirm lay date with the store before
                installation.
              </p>
              <div className="pt-2 text-sm text-slate-600">
                Invoice Date :{' '}
                <span className="text-slate-900 tabular-nums">
                  {formatDocDate(invoice.invoice_date)}
                </span>
              </div>
              <div className="text-sm text-slate-600">
                Due Date :{' '}
                <span className="text-slate-900 tabular-nums">
                  {dueDateText || '—'}
                </span>
              </div>
            </div>
          </div>

          <div className="mt-8">
            <div className="text-base font-semibold text-slate-900">
              Details Of Sale
            </div>
            <p className="mt-2 text-sm text-slate-700 leading-relaxed whitespace-pre-wrap">
              {detailsOfSaleText || '—'}
            </p>
          </div>

          <div className="mt-8 sm:ml-auto sm:w-[360px] rounded-md border border-slate-200">
            <div className="flex items-center justify-between px-4 py-2.5">
              <span className="text-sm text-slate-700">Total Inc. GST</span>
              <span className="text-sm font-medium tabular-nums text-slate-900">
                {formatMoney(invoice.sale_price_inc_gst)}
              </span>
            </div>
            <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
              <span className="text-sm text-slate-700">Payment Made</span>
              <span className="text-sm font-medium tabular-nums text-rose-600">
                {formatMoney(invoice.total_paid)}
              </span>
            </div>
            <div className="flex items-center justify-between px-4 py-2.5 border-t border-slate-200">
              <span className="text-sm text-slate-700">Balance Due</span>
              <span className="text-sm font-semibold tabular-nums text-slate-900">
                {formatMoney(invoice.balance_due)}
              </span>
            </div>
          </div>

          <div className="my-8 border-t border-slate-200" />

          {sanitizedTermsHtml && (
            <div>
              <div className="text-center text-xs font-semibold uppercase tracking-wider text-slate-800">
                Terms and Conditions — applicable to this invoice
              </div>
              {/* Terms are HTML (sanitized server-side for the PDF), re-sanitized client-side
                  with a restricted allowlist (see sanitizedTermsHtml). dangerouslySetInnerHTML
                  NEVER receives raw termsText. No whitespace-pre-wrap — the HTML carries its
                  own block structure. */}
              <div
                className="invoice-terms mt-4 text-xs text-slate-700 leading-relaxed"
                dangerouslySetInnerHTML={{ __html: sanitizedTermsHtml }}
              />
            </div>
          )}

          {/* Phase 16A PR1 — CarpetCall-style bottom composition: acceptance checkboxes +
              bold agreement on the left, the customer signature (centered) on the right, and a
              full-width Accept / Re-send action bar that anchors the document. Dense spacing,
              top-aligned (sm:items-start) so the signature sits beside the checkboxes with no
              float gap; the signature is NOT wrapped in a large empty box. */}
          <div className="mt-4 grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-5 sm:items-start">
            {/* LEFT — responsibility line + acceptance checkboxes + bold agreement */}
            <div>
              <p className="text-[11px] text-slate-600 leading-snug">
                Furniture removal and replacement, take up of old floor coverings,
                floor preparation and adjustment of door heights are the
                customer's responsibility unless otherwise stated above.
              </p>
              <div className="mt-3 space-y-1.5">
                {ACCEPTANCE_LINES.map((line, index) => (
                  <label
                    key={line}
                    className="flex items-start gap-2 text-[11px] text-slate-800 leading-snug"
                  >
                    <input
                      type="checkbox"
                      // ACCEPTED is persisted by the invoice acceptance event, so the boxes
                      // show ticked + read-only regardless of local state (which was reset on
                      // the version change). UNACCEPTED: real interactive controls gating Accept.
                      checked={invoiceAccepted || acceptanceChecked[index]}
                      disabled={invoiceAccepted || accepting}
                      onChange={(event) =>
                        setAcceptanceChecked((prev) => {
                          const next = [...prev]
                          next[index] = event.target.checked
                          return next
                        })
                      }
                      className="mt-0.5 h-4 w-4 shrink-0 rounded border-slate-300 text-teal-600"
                    />
                    <span className="uppercase tracking-wide font-semibold">
                      {line}
                    </span>
                  </label>
                ))}
              </div>
              <p className="mt-3 text-xs font-bold uppercase tracking-wide text-slate-900 leading-snug">
                This agreement is for the sale and installation of the goods
                described above at the value shown on this invoice and upon the
                terms and conditions stated herein.
              </p>
              <p className="mt-2 text-xs font-bold uppercase tracking-wider text-slate-900">
                I accept the terms and conditions of this invoice.
              </p>
            </div>

            {/* RIGHT — customer signature, centered like CarpetCall */}
            <div className="flex flex-col items-center">
              <div className="text-sm font-semibold text-slate-700">
                Customer Signature
              </div>
              {invoice.accepted_at !== null ? (
                <div className="mt-2 flex w-full max-w-[340px] flex-col items-center text-center">
                  {/* Accepted — the stored signature sits on a thin signature line (no large
                      empty box); the customer name is centered directly below it. */}
                  <div className="flex h-16 w-full items-end justify-center border-b border-slate-300">
                    {signatureUrl !== null ? (
                      <img
                        src={signatureUrl}
                        alt={`Signature of ${invoice.accepted_customer_name ?? 'the customer'}`}
                        className="max-h-14 w-auto pb-0.5"
                      />
                    ) : signatureFailed ? (
                      <p className="pb-2 text-[11px] text-slate-500">
                        The signature image could not be loaded.
                      </p>
                    ) : invoice.accepted_signature_present ? (
                      <p className="pb-2 text-[11px] text-slate-500">
                        Loading signature…
                      </p>
                    ) : (
                      <p className="pb-2 text-[11px] text-slate-500">
                        No signature image stored.
                      </p>
                    )}
                  </div>
                  <div className="mt-1.5 text-sm font-medium text-slate-800">
                    {invoice.accepted_customer_name || '—'}
                  </div>
                  {/* Email state derives from last_emailed_at, never from message text. */}
                  <div className="mt-0.5 text-[11px] text-slate-500 tabular-nums">
                    Accepted on {formatTimestamp(invoice.accepted_at)}
                  </div>
                  {invoice.last_emailed_at !== null ? (
                    <div className="mt-0.5 text-[11px] text-teal-700 tabular-nums">
                      Emailed to the customer on{' '}
                      {formatTimestamp(invoice.last_emailed_at)}
                    </div>
                  ) : (
                    <div className="mt-0.5 text-[11px] font-medium text-amber-700">
                      Accepted but not emailed yet — use Re-send Invoice below.
                    </div>
                  )}
                </div>
              ) : (
                <div className="mt-2 w-full max-w-[380px]">
                  {/* Unaccepted — capture a real signature (pointer events: touch, pen and
                      mouse). Shallow pad with a light border; the derived customer name sits
                      directly below it (the customer never types it). */}
                  <div className="invoice-signature-pad rounded-md border border-slate-200 bg-white px-1.5 pt-1.5 pb-1">
                    <SignaturePad
                      ref={padRef}
                      disabled={accepting || !tenantConfigReadyForAcceptance}
                      onInkChange={setHasInk}
                    />
                  </div>
                  <div className="mt-1 flex items-center justify-between gap-3">
                    <span className="text-sm font-medium text-slate-800">
                      {derivedAcceptedName || '—'}
                    </span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={handleClearSignature}
                      disabled={accepting || !hasInk}
                    >
                      Clear signature
                    </Button>
                  </div>
                  {derivedAcceptedName.length === 0 ? (
                    <div className="mt-2 flex flex-col items-center gap-2 text-center">
                      <p className="text-[11px] font-medium text-amber-700">
                        Save the customer's name on the Customer tab before
                        accepting this invoice.
                      </p>
                      {onGoToCustomer && (
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={onGoToCustomer}
                        >
                          Go to Customer tab
                        </Button>
                      )}
                    </div>
                  ) : derivedNameTooLong ? (
                    <div className="mt-2 flex flex-col items-center gap-2 text-center">
                      <p className="text-[11px] font-medium text-amber-700">
                        The customer name is longer than{' '}
                        {ACCEPTED_NAME_MAX_LENGTH} characters, so the invoice
                        cannot be accepted. Shorten it on the Customer tab.
                      </p>
                      {onGoToCustomer && (
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={onGoToCustomer}
                        >
                          Go to Customer tab
                        </Button>
                      )}
                    </div>
                  ) : null}
                  {/* Codex P1 legal-risk gate — exactly one state: loading XOR error, never
                      both. Accept stays disabled until tenant config is safely loaded. */}
                  {tenantConfigLoading ? (
                    <p className="mt-2 text-center text-[11px] text-slate-500">
                      Loading invoice terms and business details…
                    </p>
                  ) : tenantConfigError ? (
                    <div className="mt-2 flex flex-col items-center gap-2 text-center">
                      <p className="text-[11px] font-medium text-amber-700">
                        Invoice terms and business details could not be loaded.
                        Try again before accepting.
                      </p>
                      <Button
                        type="button"
                        variant="secondary"
                        size="sm"
                        onClick={refetch}
                      >
                        Try again
                      </Button>
                    </div>
                  ) : null}
                </div>
              )}
            </div>
          </div>

          {/* Full-width bottom action bar — anchors the document (CarpetCall-style). Unaccepted
              => Accept Invoice (same handler/gates as before); accepted => Re-send Invoice (the
              ONLY Re-send; the top one was removed). Native button for precise full-width sizing. */}
          <div className="mt-3">
            {invoiceAccepted ? (
              <button
                type="button"
                onClick={handleResend}
                disabled={resending || accepting}
                className="flex w-full items-center justify-center gap-2 rounded-md bg-teal-600 px-6 py-3.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <svg
                  viewBox="0 0 20 20"
                  fill="currentColor"
                  aria-hidden="true"
                  className="h-4 w-4"
                >
                  <path d="M1.7 9.1 17.3 2.2c.7-.3 1.4.4 1.1 1.1l-6.9 15.6c-.3.7-1.3.6-1.5-.1l-1.7-5.3a1 1 0 0 0-.6-.6L2.4 11c-.8-.3-.8-1.3-.1-1.6Z" />
                </svg>
                {resending ? 'Re-sending…' : 'Re-send Invoice'}
              </button>
            ) : (
              <button
                type="button"
                onClick={handleAccept}
                disabled={
                  accepting ||
                  resending ||
                  !hasInk ||
                  !allAcceptanceChecked ||
                  derivedAcceptedName.length === 0 ||
                  derivedNameTooLong ||
                  !tenantConfigReadyForAcceptance
                }
                className="flex w-full items-center justify-center rounded-md bg-teal-600 px-6 py-3.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {accepting ? 'Accepting…' : 'Accept Invoice'}
              </button>
            )}
          </div>
        </article>
      ) : (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-10 text-center">
          <p className="text-base font-medium text-slate-700">
            No invoice has been created yet.
          </p>
          <p className="mt-1 text-sm text-slate-500">
            Create the invoice from the Details of Sale tab to view and download
            it here.
          </p>
        </div>
      )}
    </div>
  )
}
