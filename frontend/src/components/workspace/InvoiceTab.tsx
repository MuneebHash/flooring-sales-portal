import { useEffect, useMemo, useRef, useState } from 'react'
import DOMPurify from 'dompurify'
import { Button } from '../ui/Button'
import { SignaturePad, type SignaturePadHandle } from './SignaturePad'
import { MailIcon } from '../icons'
import { ApiError } from '../../lib/api/ApiError'
import {
  acceptCurrentInvoice,
  fetchCurrentInvoice,
  fetchCurrentInvoicePdf,
  fetchCurrentInvoiceSignature,
  resendCurrentInvoice,
  sendInvoiceLink,
  type InvoiceDetail,
} from '../../lib/api/orderInvoicesApi'
import type { DetailsOfSaleFields } from '../../lib/api/orderWorkspaceApi'
import { fetchPublicBusiness } from '../../lib/api/tenantApi'
import { getActiveSlug } from '../../lib/tenant'
import { usePollWhileVisible } from '../../lib/usePollWhileVisible'

type Props = {
  orderId: number
  // Phase 16F PR3 - a one-shot success notice handed over when the workspace opens this
  // tab right after an action elsewhere (the Path A response message from the Quote tab,
  // which since 16G PR 3 also says whether the signed PDF was emailed). Shown above the
  // invoice until the next invoice action.
  notice?: string | null
  // View/preview + Phase 13 acceptance (sign / accept / re-send). Create + Rewrite
  // live in the Details of Sale tab now. NOTE: LAID/`locked` is deliberately NOT a
  // prop here — Accept, Re-send, the signature download and (16G PR 5) Accept Remotely
  // are all allowed when LAID (acceptance parallels payment, not rewrite), so the lock
  // never gates this tab.
  orderNumber?: string
  saleDetails?: DetailsOfSaleFields | null
  // Order-bound salesperson name (sales_order.user_id -> app_user first/last), the same
  // source the PDF uses — NOT the session user. Rendered in the document meta; hidden
  // (fail-soft) when null/blank, like every other optional tenant/store field.
  salespersonName?: string | null
  // Switches the workspace to the Customer tab when an accept / resend / send-link fails with
  // CUSTOMER_EMAIL_REQUIRED / CUSTOMER_EMAIL_INVALID so the salesperson can fix the
  // email where it lives.
  onGoToCustomer?: () => void
  // Batch A #107 (D1): awaitable flush of the Customer tab's customer, installation and
  // billing autosaves (shell-owned wrapper; it resolves true at once on a known LAID order,
  // so the lock still never gates this tab). Accept, Re-send and Accept Remotely email the
  // saved customer address, so each runs it before its request; false blocks the action.
  flushCustomerAutosave: () => Promise<boolean>
}

// Batch A #107 (D1) - the plain action error when the Customer tab flush fails.
const CUSTOMER_NOT_SAVED_MESSAGE =
  'Could not save the latest Customer tab changes. Complete or fix the customer details and addresses on the Customer tab, then try again.'

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

// Fallback name for the Preview PDF download that replaces a blocked popup, used
// only when the server's Content-Disposition name is absent or unreadable. It
// mirrors the backend pattern (invoice-{order_number}-v{version}.pdf); a blob
// object URL does not carry the server's Content-Disposition, so the anchor sets
// the name explicitly.
function pdfFileName(
  orderNumber: string | undefined,
  versionNumber: number,
): string {
  const safeOrder =
    orderNumber && orderNumber.length > 0 ? orderNumber : 'order'
  return `invoice-${safeOrder}-v${versionNumber}.pdf`
}

// Trim a nullable/optional string and return it only when it has visible content;
// otherwise null. Lets the invoice hide values (e.g. terms_html) that are null/blank rather
// than render an empty label, a sample fallback, or a "—" placeholder.
function nonBlank(value: string | null | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed && trimmed.length > 0 ? trimmed : null
}

const ACCEPTANCE_LINES: string[] = [
  'I agree to pay the balance before the installation date.',
  'I agree that no floor preparation costs are included unless otherwise stated above.',
]

// Phase 16G PR 3 - the D.8 stale-version 409 (INVOICE_VERSION_STALE) default message, shown when
// the response carries none (the backend message is otherwise surfaced verbatim).
const INVOICE_STALE_MESSAGE = 'Invoice updated, please refresh'

// Phase 16G PR 6 (#120) - InvoiceDetail.invoice_link_expires_at is a timezone-free server-local
// timestamp (YYYY-MM-DDTHH:mm:ss). It is read as LOCAL time and compared with this device's clock
// (no timezone policy change here; #34). Returns epoch milliseconds, or null for a missing or invalid
// value, which never permits a poll.
function parseLocalTimestamp(value: string | null | undefined): number | null {
  if (!value) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})$/.exec(value)
  if (!match) return null
  const [year, month, day, hours, minutes, seconds] = match.slice(1).map(Number)
  if (month < 1 || month > 12 || hours > 23 || minutes > 59 || seconds > 59) {
    return null
  }
  const date = new Date(year, month - 1, day, hours, minutes, seconds)
  // Reject a calendar overflow (31 February would roll over into March).
  if (
    date.getFullYear() !== year ||
    date.getMonth() !== month - 1 ||
    date.getDate() !== day
  ) {
    return null
  }
  const time = date.getTime()
  return Number.isFinite(time) ? time : null
}

// Phase 16G PR 6 - the outcome of one current-invoice read (see readCurrentInvoice).
//   applied - the answer was installed: an invoice, or null for 404 INVOICE_NOT_FOUND when the
//             caller lets the no-invoice state be installed;
//   ignored - nothing was installed: the tab unmounted, the order changed, a newer read or a mutation
//             applied first, the answer was an OLDER version than the one on screen, or (for a poll)
//             the answer was a 404;
//   failed  - no readable answer (and nothing newer applied meanwhile).
type CurrentInvoiceReadOutcome =
  | { status: 'applied'; invoice: InvoiceDetail | null }
  | { status: 'ignored' }
  | { status: 'failed'; error: unknown }

// Phase 16G PR 3 - the frozen accepted name: D.8 records the version's own customer_name_snapshot
// (no name is sent), so a blank one disables Accept. Blankness only: no length cap.
function hasFrozenAcceptedName(invoice: InvoiceDetail | null): boolean {
  return (
    invoice !== null &&
    invoice.customer_name_snapshot !== null &&
    invoice.customer_name_snapshot.trim().length > 0
  )
}

// Phase 16F PR3 - the plain terms-source label. It reports the API's own terms_source
// (QUOTE = the terms frozen on the signed quote; BUSINESS, since 16G PR 2 = the business's
// terms, frozen into this version when it was created or rewritten). No wording promises a
// live terms lookup (UX lock section 20).
function termsSourceLabel(
  source: InvoiceDetail['terms_source'],
  hasTerms: boolean,
): string {
  if (source === 'QUOTE') {
    return hasTerms
      ? 'Terms source: frozen from the signed quote.'
      : 'Terms source: frozen from the signed quote, which has no terms.'
  }
  return hasTerms
    ? 'Terms source: the business’s terms, frozen when this invoice was created or rewritten.'
    : 'Terms source: the business’s terms, frozen when this invoice was created or rewritten (none were set).'
}

export function InvoiceTab({
  orderId,
  notice,
  orderNumber,
  saleDetails,
  onGoToCustomer,
  flushCustomerAutosave,
}: Props) {
  // Only ever the CURRENT/latest invoice — no version list, no history.
  const [invoice, setInvoice] = useState<InvoiceDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  // Backend success messages (accept / resend / send-link) surfaced VERBATIM.
  const [actionMessage, setActionMessage] = useState<string | null>(null)
  // Phase 16F PR3 - the handed-over success notice (see Props.notice), captured once at
  // mount (the workspace remounts this tab on every open and on every handover). Cleared
  // by the next action.
  const [handoverNotice, setHandoverNotice] = useState<string | null>(
    notice ?? null,
  )
  // True when the last accept / resend / send-link failed on the customer-email gate, so the
  // error banner can point the salesperson at the Customer tab.
  const [emailFixNeeded, setEmailFixNeeded] = useState(false)
  const [previewingPdf, setPreviewingPdf] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const [resending, setResending] = useState(false)
  // 16G PR 5 - Accept Remotely (send-link) in flight.
  const [sendingLink, setSendingLink] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)
  // 16G PR 5 - ONE synchronous in-flight rule for the three mutations (Accept Invoice,
  // Re-send Invoice, Accept Remotely): taken before a handler's first await and released in
  // its finally, after any error recovery, so a rapid second tap can never start a second or
  // conflicting mutation (the accepting / resending / sendingLink state only drives labels
  // and disabled buttons, one render later).
  const mutationInFlightRef = useRef(false)
  // Object URLs handed to the Preview PDF tab. Revoking immediately can blank the tab
  // mid-load, so they are revoked on unmount only (the QuoteTab registry).
  const previewUrlsRef = useRef<string[]>([])

  // Business name + logo for the document header, from the fail-soft public-business lookup
  // in a SEPARATE, order-independent effect below: a failure hides the brand, never blocks
  // the invoice document or acceptance, and never routes through loadError. (Phase 16A PR1:
  // store details are no longer shown on this screen, so the auth store context is not read.)
  // Phase 16F PR3: the terms no longer come from the private tenant invoice config - they
  // ride on the InvoiceDetail itself (terms_html / terms_source), so that config is not
  // fetched here at all.
  const [businessName, setBusinessName] = useState<string | null>(null)
  // Phase 16A PR1 - demo/tenant logo for the document header, rendered as a browser
  // <img> that fails soft to the business-name text. logoFailed flips on an <img> load error
  // and resets whenever the path changes (effect below).
  const [logoPath, setLogoPath] = useState<string | null>(null)
  const [logoFailed, setLogoFailed] = useState(false)

  // Acceptance draft (unaccepted invoice only): whether the pad has at least one
  // drawn stroke. The accepted customer name is NOT typed — it is the displayed
  // version's frozen customer_name_snapshot (see frozenAcceptedName below).
  const [hasInk, setHasInk] = useState(false)
  const padRef = useRef<SignaturePadHandle | null>(null)

  // Phase 16A PR1 — acceptance checkboxes (frontend-only gate; NEVER sent to the backend —
  // the accept contract is exactly { expected_version_number, signature } since 16G PR 3). One
  // boolean per ACCEPTANCE_LINES entry. Reset on an invoice identity/version change by
  // installInvoice (16G PR 6), in the same update as the new version, with the keyed effect below
  // as a backstop, so a tick never leaks across a reload, rewrite, payment/void regeneration or a
  // version installed by a refresh or poll.
  const [acceptanceChecked, setAcceptanceChecked] = useState<boolean[]>(() =>
    Array(ACCEPTANCE_LINES.length).fill(false),
  )

  // Phase 16G PR 6 — mirrors read by async continuations: the displayed invoice (written ONLY by
  // installInvoice) and the order this tab reads.
  const invoiceRef = useRef<InvoiceDetail | null>(null)
  const orderIdRef = useRef(orderId)
  orderIdRef.current = orderId
  // Phase 16G PR 6 — ONE recency mechanism for every current-invoice response (the QuoteTab
  // snapshot pattern). readSeqRef numbers each read when it is ISSUED and each mutation barrier or
  // result (accept, re-send, send-link); readFloorRef is the newest number whose data (or barrier)
  // has applied. A read applies only when its number is ABOVE the floor, and then raises the floor
  // to it, so an older read never overwrites a newer one and a read issued before a mutation started
  // (a pending poll included) never lands after it.
  const readSeqRef = useRef(0)
  const readFloorRef = useRef(0)
  // Current-invoice reads in flight, whatever triggered them: a poll tick is skipped while one is
  // pending, so automatic reads stay single-flight.
  const readsInFlightRef = useRef(0)
  // The invoice_link_expires_at value a poll tick found elapsed or unreadable: polling stops for it
  // without a request, until a read installs another value.
  const [elapsedLinkExpiry, setElapsedLinkExpiry] = useState<string | null>(
    null,
  )

  // Stored accepted-signature display (object URL owned by the effect below).
  // While the fetch is in flight, signatureUrl is null and signatureFailed false —
  // the render's accepted_signature_present branch shows "Loading signature…".
  const [signatureUrl, setSignatureUrl] = useState<string | null>(null)
  const [signatureFailed, setSignatureFailed] = useState(false)

  // Tracks whether THIS tab is still mounted so async action handlers never
  // setState after the tab unmounts on a tab switch. The mount fetch below uses
  // its own per-run `cancelled` guard. Unmount also revokes the Preview PDF URLs.
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    const previewUrls = previewUrlsRef.current
    return () => {
      mountedRef.current = false
      for (const url of previewUrls) URL.revokeObjectURL(url)
      previewUrls.length = 0
    }
  }, [])

  // Phase 16G PR 6 — the ONE place the displayed invoice changes (every read and every mutation
  // result goes through here). Installing a different invoice identity or version, or none,
  // invalidates the signing draft of the replaced version IN THE SAME UPDATE: the pad is cleared
  // (which also drops hasInk) and both declaration ticks reset, so the new version never renders
  // with Accept Invoice enabled by the old ink or ticks (16G contract §13.2). Installing the
  // unchanged version keeps the ink and ticks.
  function installInvoice(next: InvoiceDetail | null) {
    const displayed = invoiceRef.current
    const versionChanged =
      displayed === null ||
      next === null ||
      displayed.invoice_id !== next.invoice_id ||
      displayed.version_number !== next.version_number
    if (versionChanged) {
      padRef.current?.clear()
      setHasInk(false)
      setAcceptanceChecked(Array(ACCEPTANCE_LINES.length).fill(false))
    }
    invoiceRef.current = next
    setInvoice(next)
  }

  // A mutation (accept, re-send, send-link) is starting or has applied its own result: no
  // current-invoice read issued before this point may apply afterwards.
  function raiseReadFloor() {
    readSeqRef.current += 1
    readFloorRef.current = readSeqRef.current
  }

  // Read the current invoice and install the answer through installInvoice, but ONLY while this
  // tab is still mounted, still on the order the read was issued for, and no newer read or
  // mutation has applied since the read was issued; an answer OLDER than the version on screen is
  // never installed (versions only grow). 404 INVOICE_NOT_FOUND installs the no-invoice state only
  // when installNotFound is set (a poll passes false: its 404 changes nothing). Never throws.
  async function readCurrentInvoice(
    installNotFound: boolean,
  ): Promise<CurrentInvoiceReadOutcome> {
    readSeqRef.current += 1
    const seq = readSeqRef.current
    const requestOrderId = orderIdRef.current
    const stillCurrent = () =>
      mountedRef.current &&
      orderIdRef.current === requestOrderId &&
      seq > readFloorRef.current
    readsInFlightRef.current += 1
    try {
      let next: InvoiceDetail | null
      try {
        const res = await fetchCurrentInvoice(requestOrderId)
        next = res.data.invoice
      } catch (err) {
        if (!(err instanceof ApiError && err.code === 'INVOICE_NOT_FOUND')) {
          return stillCurrent()
            ? { status: 'failed', error: err }
            : { status: 'ignored' }
        }
        if (!installNotFound) return { status: 'ignored' }
        next = null
      }
      if (!stillCurrent()) return { status: 'ignored' }
      const displayed = invoiceRef.current
      if (
        next !== null &&
        displayed !== null &&
        next.version_number < displayed.version_number
      ) {
        return { status: 'ignored' }
      }
      readFloorRef.current = seq
      installInvoice(next)
      return { status: 'applied', invoice: next }
    } finally {
      readsInFlightRef.current -= 1
    }
  }

  // Fetch the current invoice on mount (the tab unmounts on a tab switch, so this
  // refetches fresh each open) and whenever reloadToken bumps. A 404
  // INVOICE_NOT_FOUND is the clean "no invoice yet" empty state, NOT an error.
  // This is the only read that shows the loading state; refreshes and polls of a
  // loaded invoice never do.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    setActionError(null)
    setActionMessage(null)
    setEmailFixNeeded(false)
    // A reload is a fresh start: no earlier read may land after it, and the
    // displayed invoice goes with its acceptance draft (the pad below remounts
    // empty, so the ink gate must match it).
    raiseReadFloor()
    installInvoice(null)
    void readCurrentInvoice(true).then((outcome) => {
      if (cancelled) return
      if (outcome.status === 'failed') {
        const err = outcome.error
        setLoadError(
          err instanceof ApiError && err.message.length > 0
            ? err.message
            : 'Could not load the invoice. Please try again.',
        )
      }
      setLoading(false)
    })
    return () => {
      cancelled = true
    }
    // readCurrentInvoice / installInvoice / raiseReadFloor read everything through refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId, reloadToken])

  // Optional branding - tenant-scoped and order-independent (never keyed on orderId),
  // refreshed on reloadToken so "Try again" refreshes it too. It only ever sets
  // businessName / logoPath: its failure or hang fails soft, never gates acceptance and
  // never touches invoice / loadError.
  useEffect(() => {
    let cancelled = false
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

  // Legal-risk gate (Codex P1; source moved in Phase 16F PR3) - the customer must not sign
  // before the terms this invoice is accepted under have rendered. Those terms now ride on
  // the CURRENT InvoiceDetail itself (terms_html / terms_source), so the gate is that
  // response: while the invoice is loading, failed or missing, the pad is disabled AND any
  // already-drawn ink is dropped, so a signature drawn before the terms rendered can never
  // become submittable. A loaded terms_html of null is valid (no terms), not an error, and
  // branding never gates acceptance. Keyed on the RAW flags (exactly
  // !invoiceReadyForAcceptance) so this hook groups with the other effects above the JSX
  // return and stays hook-order-safe if an early return is ever added later.
  const invoiceMissing = invoice === null
  useEffect(() => {
    if (loading || loadError !== null || invoiceMissing) {
      padRef.current?.clear()
      setHasInk(false)
    }
  }, [loading, loadError, invoiceMissing])

  // Phase 16A PR1 — reset the acceptance checkboxes whenever the invoice IDENTITY or VERSION
  // changes (initial load, accept, drift resync, rewrite, payment/void regeneration). Since 16G
  // PR 6 installInvoice resets them (and the ink) in the same update as the new version; this
  // keyed reset stays as a backstop and never fires for an unchanged version. The ACCEPTED
  // branch shows the boxes checked/read-only from the persisted acceptance, never from this
  // state (which has just been reset).
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

  // Inline current-invoice re-read after a mutation: the drift errors (409
  // INVOICE_ALREADY_ACCEPTED, 422 INVOICE_REQUIRED / INVOICE_NOT_ACCEPTED), the stale 409 and,
  // since 16G PR 6, every Accept Remotely attempt. Deliberately NOT refetch(): a reloadToken bump
  // clears the banners, and these cases need the explanatory banner to survive while the fresh
  // state (accepted / empty / a new link expiry) renders. It goes through the guarded install
  // path (readCurrentInvoice), so a DIFFERENT version drops the drawn-but-unsubmitted ink and
  // both ticks (stale strokes must never be submittable against another version), the unchanged
  // version keeps them, and 404 INVOICE_NOT_FOUND shows the empty state. Other failures keep the
  // current view — the banner already explains the situation.
  async function resyncCurrentInvoice() {
    await readCurrentInvoice(true)
  }

  // Shared accept / resend / send-link (Accept Remotely, 16G PR 5) error handling.
  // Backend messages are surfaced VERBATIM whenever present; the per-code branches
  // only add the right recovery behaviour. Resolves true when it re-read the current
  // invoice (the stale and drift branches), so Accept Remotely does not issue a
  // second read for the same attempt (16G PR 6).
  async function applyAcceptResendError(
    err: unknown,
    fallback: string,
  ): Promise<boolean> {
    if (err instanceof ApiError) {
      const message = err.message.length > 0 ? err.message : fallback
      if (err.code === 'INVOICE_VERSION_STALE') {
        // Phase 16G PR 3 - the server's authoritative version check (D.8 only) refused the
        // signature: another invoice version is current now. Invalidate the signing draft FIRST
        // (the ink and both ticks), so it is dropped even if the re-read below fails, keep the
        // message, and re-read the current invoice. Never retry: a fresh review, both ticks and a
        // fresh signature are needed before Accept is enabled again.
        padRef.current?.clear()
        setHasInk(false)
        setAcceptanceChecked(Array(ACCEPTANCE_LINES.length).fill(false))
        setActionError(
          err.message.length > 0 ? err.message : INVOICE_STALE_MESSAGE,
        )
        await resyncCurrentInvoice()
        return true
      }
      if (err.code === 'INVOICE_ALREADY_ACCEPTED') {
        // Drift: this invoice was accepted elsewhere. Surface the backend message
        // and pull the accepted state.
        setActionError(message)
        await resyncCurrentInvoice()
        return true
      }
      if (
        err.code === 'CUSTOMER_EMAIL_REQUIRED' ||
        err.code === 'CUSTOMER_EMAIL_INVALID'
      ) {
        // The fix lives on the Customer tab — the banner adds the pointer/button.
        setActionError(message)
        setEmailFixNeeded(true)
        return false
      }
      if (
        err.code === 'INVOICE_REQUIRED' ||
        err.code === 'INVOICE_NOT_ACCEPTED'
      ) {
        // Drift: the current invoice vanished (-> empty state) or is no longer
        // accepted (e.g. rewritten elsewhere -> signature pad again).
        setActionError(message)
        await resyncCurrentInvoice()
        return true
      }
      // Everything else — including 502 EMAIL_SEND_FAILED. On resend the backend
      // wrote NOTHING; on send-link it kept the new link ACTIVE (undelivered) but
      // wrote no invoice row. Either way no invoice state changes here (Accept
      // Remotely's own follow-up re-read still learns the link's expiry).
      setActionError(message)
      return false
    }
    setActionError(fallback)
    return false
  }

  async function handleAccept() {
    // Stray-call guard: never accept while the current invoice (and with it the terms it
    // is accepted under) is not safely loaded (Codex P1).
    if (
      mutationInFlightRef.current ||
      accepting ||
      resending ||
      sendingLink ||
      invoice === null ||
      !invoiceReadyForAcceptance ||
      !acceptanceChecked.every(Boolean)
    )
      return
    // The version the customer reviewed and signed: the POST below carries ITS
    // version_number (never one adopted from a later read).
    const displayed = invoice
    // The accepted name is the displayed version's frozen customer_name_snapshot
    // (16G PR 3): never typed, never the live customer, and no name is sent. A blank
    // frozen name disables Accept in the render; this guard keeps a stray call from
    // POSTing a signature the server would refuse.
    if (!hasFrozenAcceptedName(displayed) || !hasInk) {
      return
    }
    mutationInFlightRef.current = true
    setAccepting(true)
    setActionError(null)
    setActionMessage(null)
    setHandoverNotice(null)
    setEmailFixNeeded(false)
    // 16G PR 6 - the accept starts: no read issued before this point (a pending
    // poll included) may land after it.
    raiseReadFloor()
    try {
      // Batch A #107 (D1): pending Customer tab saves are flushed before the version
      // check and the accept (the signed PDF is emailed to the saved address). A failed
      // flush stops here: nothing is posted and the ink and both ticks are kept.
      const customerSaved = await flushCustomerAutosave()
      if (!mountedRef.current) return
      if (!customerSaved) {
        setActionError(CUSTOMER_NOT_SAVED_MESSAGE)
        return
      }
      // BEST-EFFORT STALE-TAB HINT: the accept endpoint targets /current, but
      // another session may have recorded a payment or rewritten the invoice
      // after this tab loaded — the customer would have reviewed/signed an
      // invoice that is no longer /current. Re-read and compare the version
      // identity (invoice_id + version_number) BEFORE accepting; on mismatch,
      // show the fresh invoice and require a re-sign instead of silently
      // accepting a different version. This is an early hint only: since 16G
      // PR 3 the authority is the server, which compares expected_version_number
      // (the displayed version, below) under the order lock and answers 409
      // INVOICE_VERSION_STALE (handled in applyAcceptResendError) when a version
      // was committed between this check and the POST. Since 16G PR 6 the
      // re-read installs its answer through the guarded install path: a changed
      // version clears the unsigned signature draft (ink and both ticks) as it
      // is rendered, the unchanged version keeps it.
      const check = await readCurrentInvoice(true)
      if (!mountedRef.current) return
      if (check.status === 'failed') {
        // Any other pre-check failure blocks the accept (freshness unverified)
        // and falls through to the shared error handler below.
        throw check.error
      }
      if (check.status === 'ignored') {
        // Not installed (an answer older than the version on screen):
        // freshness unverified, so never accept.
        setActionError('Could not accept the invoice. Please try again.')
        return
      }
      const fresh = check.invoice
      if (fresh === null) {
        // The invoice vanished server-side — never accept. The no-invoice empty
        // state (and the dropped draft) is already installed.
        setActionError('This invoice is no longer available.')
        return
      }
      if (
        fresh.invoice_id !== displayed.invoice_id ||
        fresh.version_number !== displayed.version_number
      ) {
        // The displayed invoice is stale. The fresh one is installed (the
        // accepted state renders normally if it is already accepted) and the
        // unsigned signature draft dropped: do NOT accept.
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
      const res = await acceptCurrentInvoice(
        orderId,
        displayed.version_number,
        blob,
      )
      if (!mountedRef.current) return
      // The 201 body is the post-email state as recorded (accepted_at set;
      // last_emailed_at non-null only when the delivery's success stamp was
      // recorded — a delivered email whose stamp failed keeps the emailed message
      // with last_emailed_at null, and the tab follows the marker) — no follow-up GET.
      // It is the newest state: it applies, no earlier read may land after it, and
      // installing the signed version drops the local unsigned draft.
      raiseReadFloor()
      installInvoice(res.data.invoice)
      setActionMessage(
        res.message && res.message.length > 0 ? res.message : 'Invoice accepted.',
      )
    } catch (err) {
      if (!mountedRef.current) return
      await applyAcceptResendError(
        err,
        'Could not accept the invoice. Please try again.',
      )
    } finally {
      mutationInFlightRef.current = false
      if (mountedRef.current) setAccepting(false)
    }
  }

  async function handleResend() {
    if (
      mutationInFlightRef.current ||
      accepting ||
      resending ||
      sendingLink ||
      invoice === null
    )
      return
    mutationInFlightRef.current = true
    setResending(true)
    setActionError(null)
    setActionMessage(null)
    setHandoverNotice(null)
    setEmailFixNeeded(false)
    // 16G PR 6 - the re-send starts: no earlier read may land after it.
    raiseReadFloor()
    try {
      // Batch A #107 (D1): the invoice is emailed to the saved customer address, so
      // pending Customer tab saves are flushed first; a failed flush sends nothing.
      const customerSaved = await flushCustomerAutosave()
      if (!mountedRef.current) return
      if (!customerSaved) {
        setActionError(CUSTOMER_NOT_SAVED_MESSAGE)
        return
      }
      const res = await resendCurrentInvoice(orderId)
      if (!mountedRef.current) return
      // Success bumps only last_emailed_at (no new version) — apply the returned state
      // as the newest state.
      raiseReadFloor()
      installInvoice(res.data.invoice)
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
      mutationInFlightRef.current = false
      if (mountedRef.current) setResending(false)
    }
  }

  // 16G PR 5 - Accept Remotely: email the customer a link to review and sign the CURRENT
  // invoice (POST …/invoices/current/send-link, to the SAVED customer email). It needs no ink,
  // no ticks and no version value, is allowed on a LAID order, and only for an unsigned,
  // safely loaded invoice. The send itself never touches the signing draft. Success shows the
  // response message; there is no link status, expiry display or cancel. Failures share the
  // accept/resend recovery: the 502 message verbatim, the customer-email pointer, and the
  // existing drift resync for 409 INVOICE_ALREADY_ACCEPTED / 422 INVOICE_REQUIRED.
  // 16G PR 6 (#120): every attempt, whatever its outcome, is followed by ONE re-read of the
  // current invoice (a drift resync already made by the error handler counts), so the tab learns
  // invoice_link_expires_at at once and starts or stops polling; that re-read keeps the ink and
  // ticks unless it installs another version. The single-flight hold covers it too.
  async function handleSendLink() {
    if (
      mutationInFlightRef.current ||
      accepting ||
      resending ||
      sendingLink ||
      invoice === null ||
      !invoiceReadyForAcceptance ||
      invoice.accepted_at !== null
    )
      return
    mutationInFlightRef.current = true
    setSendingLink(true)
    setActionError(null)
    setActionMessage(null)
    setHandoverNotice(null)
    setEmailFixNeeded(false)
    // 16G PR 6 - the send starts: no earlier read may land after it.
    raiseReadFloor()
    try {
      // Batch A #107 (D1): the link is emailed to the saved customer address, so pending
      // Customer tab saves are flushed first. A failed flush is not a send attempt: it
      // returns before the request and before the follow-up re-read.
      const customerSaved = await flushCustomerAutosave()
      if (!mountedRef.current) return
      if (!customerSaved) {
        setActionError(CUSTOMER_NOT_SAVED_MESSAGE)
        return
      }
      let reread = false
      try {
        const res = await sendInvoiceLink(orderId)
        if (!mountedRef.current) return
        // The send's result (a new ACTIVE link) is newer than any read issued before it.
        raiseReadFloor()
        setActionMessage(
          res.message && res.message.length > 0
            ? res.message
            : 'Invoice link emailed to the customer.',
        )
      } catch (err) {
        if (!mountedRef.current) return
        reread = await applyAcceptResendError(
          err,
          'Could not email the invoice link. Please try again.',
        )
      }
      // The follow-up re-read (201, a 502 that kept its new link ACTIVE, 422, a network
      // failure ...). A failed re-read changes nothing and keeps the outcome banner above.
      if (!reread && mountedRef.current) {
        await resyncCurrentInvoice()
      }
    } finally {
      mutationInFlightRef.current = false
      if (mountedRef.current) setSendingLink(false)
    }
  }

  function handleClearSignature() {
    if (accepting) return
    // clear() also fires onInkChange(false), which disables Accept.
    padRef.current?.clear()
  }

  // 16G PR 5 - Preview PDF: open the current version's STORED PDF (the unchanged D.4 read,
  // …/invoices/current/file; never regenerated, nothing flushed) in a new tab. Popup-safe
  // order (the QuoteTab previews): open a blank tab synchronously in the click handler, fetch
  // the credentialed blob, then point the tab at the object URL; download instead only when
  // the popup was blocked; close the blank tab on any failure.
  async function handlePreviewPdf() {
    if (previewingPdf || invoice === null) return
    const current = invoice
    setActionError(null)
    setActionMessage(null)
    setHandoverNotice(null)
    setEmailFixNeeded(false)
    // tab === null right here means a popup blocker intervened; a tab that exists now but is
    // CLOSED by the time the blob is ready means the user deliberately closed it (a cancel).
    const tab = window.open('', '_blank')
    setPreviewingPdf(true)
    try {
      const { blob, fileName } = await fetchCurrentInvoicePdf(orderId)
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
        // Popup blocked — fall back to a normal download. One-shot URL: consumed by the
        // click, revoke now. Prefer the server file name from the D.4 Content-Disposition
        // (authoritative for the CURRENT invoice the backend just streamed, so it stays correct
        // even if the invoice changed since the last read); fall back to the client-side name
        // only when the header is absent/unreadable.
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download =
          fileName ?? pdfFileName(orderNumber, current.version_number)
        document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
        setActionError(
          'The preview tab was blocked by the browser, so the PDF was downloaded instead.',
        )
      } else {
        // The user closed the blank tab while the PDF was being fetched — treat it as a
        // cancel: no forced download, no error banner.
        URL.revokeObjectURL(url)
      }
    } catch (err) {
      tab?.close()
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
          : 'Could not open the invoice PDF. Please try again.',
      )
    } finally {
      if (mountedRef.current) setPreviewingPdf(false)
    }
  }

  // Phase 16G PR 3 - the name under the signature pad is the one D.8 records: the
  // displayed version's frozen customer_name_snapshot, never typed and never the live
  // customer. A blank frozen name blocks Accept with an inline notice (the fix is a saved
  // name plus a Rewrite); there is no length limit, so a long name simply wraps. The
  // ACCEPTED state renders invoice.accepted_customer_name (the stored acceptance), not this.
  const frozenAcceptedName = hasFrozenAcceptedName(invoice)
    ? invoice?.customer_name_snapshot ?? null
    : null
  // Phase 16G PR 2 (#125) - Invoice To is the version's FROZEN words only (InvoiceDetail's
  // customer_*_snapshot fields), on signed and unsigned invoices alike: never the live
  // customer or billing address, and a null name or line is simply not shown (no sample or
  // placeholder).
  const invoiceToName = nonBlank(invoice?.customer_name_snapshot)
  const invoiceToLine1 = nonBlank(invoice?.customer_address_line1_snapshot)
  const invoiceToLine2 = nonBlank(invoice?.customer_address_line2_snapshot)
  const detailsOfSaleText =
    invoice?.details_of_sale_snapshot || saleDetails?.details_of_sale || ''
  const dueDateText =
    invoice && invoice.due_date ? formatDocDate(invoice.due_date) : ''

  // Phase 16A PR1 — the on-screen Invoice header is intentionally minimal to match the
  // CarpetCall reference: business logo (or business-name fallback) on the left, and
  // TAX INVOICE + number on the right. Store name/address/phone/email, ABN, bank/payment
  // details, Flooring Type and Salesperson are deliberately NOT rendered on this screen. All of
  // that data is unchanged in auth/workspace/tenant-config and still appears on the PDF.
  // Terms (Phase 16F PR3, decision D7; 16G PR 2): rendered ONLY from the invoice's own
  // terms_html - the terms stored on this version, the same terms its PDF renders, for both
  // terms sources. Null means no terms and never falls back to the tenant's terms. There is no
  // tenant-config lookup and no fallback of any kind here.
  const termsText = nonBlank(invoice?.terms_html)
  // Terms are stored as HTML (sanitized server-side). Render them as HTML on screen too, but
  // ALWAYS sanitize client-side first (defense in depth) with an EXPLICIT restricted allowlist
  // - NOT DOMPurify defaults. Everything outside the list (script, style, iframe/object/embed,
  // form controls, img, a, event handlers, javascript: URLs, remote images) is stripped.
  // Memoised on termsText so a large block isn't re-sanitized every render. The terms section
  // hides when termsText is blank, sanitization yields a blank string, OR the sanitized HTML
  // has no visible text (textless markup). The dangerouslySetInnerHTML below receives ONLY
  // this sanitized value, never raw termsText.
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

  // Accept is gated on the current invoice - and so the terms it is accepted under - being
  // SAFELY loaded (Codex P1): not loading, not failed, present. A null terms_html on a
  // loaded invoice is a valid "no terms" state, not a failure. (Branding failure is soft and
  // never affects this flag.)
  const invoiceReadyForAcceptance =
    !loading && loadError === null && invoice !== null
  // Phase 16F PR3 - the plain terms-source label (shown even when no terms render, so a
  // frozen "no terms" invoice still says where its terms come from).
  const termsSource =
    invoice !== null
      ? termsSourceLabel(invoice.terms_source, sanitizedTermsHtml !== null)
      : null
  // Phase 16A PR1 — all acceptance checkboxes ticked (frontend-only gate for Accept).
  const allAcceptanceChecked = acceptanceChecked.every(Boolean)
  // Phase 16A PR1 — drives the bottom composition (checkbox read-only state + Accept vs
  // Re-send action bar). Persisted by the invoice acceptance event, never by local state.
  const invoiceAccepted = invoice !== null && invoice.accepted_at !== null

  // Phase 16G PR 6 (#120) - the remote-signature poll: re-read the current invoice every 5 seconds
  // (since Batch A PR 2, Muneeb's decision 29 October 2026; 15 seconds in 16G PR 6) while the LAST
  // APPLIED InvoiceDetail shows invoice_link_expires_at non-null and still
  // in the future (an ACTIVE, unexpired invoice link) and the displayed invoice is unsigned, only
  // while the browser tab is visible (this tab is mounted only while it is the active workspace
  // tab). The condition is server state only, so it holds after a reload, for a link sent from
  // another session and for a link kept ACTIVE after a failed send. It stops when a read shows the
  // invoice signed or the field null, or when that expiry passes. The field is never displayed.
  const linkExpiresAt = invoice?.invoice_link_expires_at ?? null
  const linkExpiryMs = parseLocalTimestamp(linkExpiresAt)
  const invoicePollEnabled =
    invoiceReadyForAcceptance &&
    !invoiceAccepted &&
    linkExpiresAt !== null &&
    linkExpiresAt !== elapsedLinkExpiry &&
    linkExpiryMs !== null &&
    linkExpiryMs > Date.now()

  // One poll tick: skipped while any current-invoice read or a mutation (accept, re-send,
  // send-link) is in flight. The expiry is re-checked BEFORE any request, so an elapsed (or
  // unreadable) expiry stops polling without a request and without a successful response. The read
  // itself is silent: no loading state, no banner change, and a failure or a 404 changes nothing; an
  // answer is installed through the guarded path, so only a different version clears the ink and
  // ticks.
  function pollCurrentInvoice() {
    if (
      !mountedRef.current ||
      mutationInFlightRef.current ||
      readsInFlightRef.current > 0
    ) {
      return
    }
    const current = invoiceRef.current
    if (current === null || current.accepted_at !== null) return
    const expiresAtMs = parseLocalTimestamp(current.invoice_link_expires_at)
    if (expiresAtMs === null || expiresAtMs <= Date.now()) {
      setElapsedLinkExpiry(current.invoice_link_expires_at)
      return
    }
    void readCurrentInvoice(false)
  }
  usePollWhileVisible(invoicePollEnabled, pollCurrentInvoice)

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
              onClick={handlePreviewPdf}
              disabled={previewingPdf}
            >
              {previewingPdf ? 'Opening PDF…' : 'Preview PDF'}
            </Button>
            {/* Phase 16A PR1 — the top action area holds ONLY the PDF action (Preview PDF
                since 16G PR 5; it opens the stored PDF in a new tab, and the browser's
                viewer downloads it). Re-send is no longer duplicated here; it lives solely in
                the accepted signature block below (near the signature), matching the
                CarpetCall layout. */}
          </div>
        )}
      </div>

      {handoverNotice !== null && (
        <div className="mb-4 rounded-lg border border-teal-200 bg-teal-50 px-4 py-3">
          <p className="text-sm font-medium text-teal-700">{handoverNotice}</p>
        </div>
      )}

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
              {invoiceToName !== null && (
                <div className="mt-2 text-sm font-semibold text-slate-900">
                  {invoiceToName}
                </div>
              )}
              {(invoiceToLine1 !== null || invoiceToLine2 !== null) && (
                <div
                  className={`${
                    invoiceToName !== null ? 'mt-1' : 'mt-2'
                  } text-sm text-slate-700 leading-relaxed`}
                >
                  {invoiceToLine1 !== null && <div>{invoiceToLine1}</div>}
                  {invoiceToLine2 !== null && <div>{invoiceToLine2}</div>}
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
          {/* Phase 16F PR3 - where these terms come from (InvoiceDetail.terms_source). Shown
              on its own line, so a frozen "no terms" invoice (terms hidden) still says so. */}
          {termsSource !== null && (
            <p
              className={`text-center text-[11px] text-slate-500 ${
                sanitizedTermsHtml ? 'mt-3' : ''
              }`}
            >
              {termsSource}
            </p>
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
                  {/* Display-only and never truncated: an accepted name (the version's frozen
                      name, or one inherited by Path A) has no length limit, so it must wrap,
                      not overflow. */}
                  <div className="mt-1.5 w-full break-words text-sm font-medium text-slate-800">
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
                      mouse). Shallow pad with a light border; the version's frozen customer
                      name sits directly below it (the customer never types it). */}
                  <div className="invoice-signature-pad rounded-md border border-slate-200 bg-white px-1.5 pt-1.5 pb-1">
                    <SignaturePad
                      ref={padRef}
                      disabled={accepting || !invoiceReadyForAcceptance}
                      onInkChange={setHasInk}
                    />
                  </div>
                  <div className="mt-1 flex items-center justify-between gap-3">
                    {/* Never truncated: a long frozen name wraps instead of overflowing. */}
                    <span className="min-w-0 break-words text-sm font-medium text-slate-800">
                      {frozenAcceptedName ?? '—'}
                    </span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={handleClearSignature}
                      disabled={accepting || !hasInk}
                      // The name beside it takes all the shrink, so a long frozen name never
                      // squeezes this label onto two lines.
                      className="shrink-0 whitespace-nowrap"
                    >
                      Clear signature
                    </Button>
                  </div>
                  {frozenAcceptedName === null ? (
                    <div className="mt-2 flex flex-col items-center gap-2 text-center">
                      <p className="text-[11px] font-medium text-amber-700">
                        This invoice has no customer name. Save the customer's
                        name on the Customer tab, then rewrite the invoice before
                        accepting it.
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
                  {/* Codex P1 legal-risk gate: this pad only renders inside the loaded
                      invoice document, whose own terms_html has already rendered above, so
                      there is no separate terms loading/error state here any more (Phase 16F
                      PR3). invoiceReadyForAcceptance still guards the pad and Accept. */}
                </div>
              )}
            </div>
          </div>

          {/* Full-width bottom action bar — anchors the document (CarpetCall-style). Unaccepted
              => Accept Invoice (same handler/gates as before) | Accept Remotely (16G PR 5: emails
              the customer the invoice link; no ink or ticks needed); accepted => Re-send Invoice
              only (the ONLY Re-send; the top one was removed; Accept Remotely is never offered
              for a signed invoice). Native buttons for precise full-width sizing. */}
          <div className="mt-3">
            {invoiceAccepted ? (
              <button
                type="button"
                onClick={handleResend}
                disabled={resending || accepting || sendingLink}
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
              <div className="flex flex-col gap-2 sm:flex-row">
                <button
                  type="button"
                  onClick={handleAccept}
                  disabled={
                    accepting ||
                    resending ||
                    sendingLink ||
                    !hasInk ||
                    !allAcceptanceChecked ||
                    frozenAcceptedName === null ||
                    !invoiceReadyForAcceptance
                  }
                  className="flex w-full items-center justify-center rounded-md bg-teal-600 px-6 py-3.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50 sm:flex-1"
                >
                  {accepting ? 'Accepting…' : 'Accept Invoice'}
                </button>
                <button
                  type="button"
                  onClick={handleSendLink}
                  disabled={
                    accepting ||
                    resending ||
                    sendingLink ||
                    !invoiceReadyForAcceptance
                  }
                  className="flex w-full items-center justify-center gap-2 rounded-md bg-teal-600 px-6 py-3.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-500/40 disabled:cursor-not-allowed disabled:opacity-50 sm:flex-1"
                >
                  <MailIcon aria-hidden="true" className="h-4 w-4" />
                  {sendingLink ? 'Sending link…' : 'Accept Remotely'}
                </button>
              </div>
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
