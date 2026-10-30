import { useEffect, useRef, useState } from 'react'
import { ApiError } from './api/ApiError'

// Batch A #107 - the shared queue-first autosave of ONE full-replace section. The Customer tab
// runs one instance each for the customer details, the installation address and the billing
// address.
//
// Contract:
//   - Triggers: schedule() (the debounce after a change), flushNow() (a blur, and the owner's
//     action and unmount flushes) and request() (an immediate programmatic trigger). Every
//     trigger runs in this order: the locked check FIRST; build the current body (null means
//     invalid or nothing to send, and is never sent); while a save is in flight, replace the
//     queued body with this latest one and stop; otherwise compare with the confirmed
//     baseline; then save.
//   - One save in flight per section, pending work collapsed to the latest body. When a save
//     settles, the drain sends the queued body only when it still differs from the POST-save
//     baseline, so a revert to the saved value made while a save was in flight is never
//     dropped (#96). The chain promise spans the whole recursive drain and never rejects.
//   - The baseline advances ONLY after a success, to the key the owner returns for the body
//     that actually succeeded. A failure leaves it, so the edit stays dirty for the next
//     trigger (including one queued while the failed save was in flight); nothing retries
//     automatically. Server responses are never written back into a form: the owner only
//     lifts confirmed rows.
//   - A body refused with 422 ORDER_LOCKED (the order was laid in another session) is not
//     sent again — by a later trigger, a queued duplicate or the unmount flush — until a
//     save of this section succeeds (or the owner calls forgetRefusals for keys another
//     save has shown may go), and nothing here switches the form to read-only.
//   - The error shown stays truthful when nothing is sent: a body equal to the confirmed
//     baseline clears an older DEFINITE failure, and a refused body shows its refusal again.
//     After an ambiguous failure (no readable answer: the save may have committed) the error
//     is kept until a save succeeds, as QuoteTab keeps it; the unknown-outcome problem is not
//     solved here.
//   - It has no unmount flush of its own (unmounting only stops the debounce): the owner
//     flushes its sections in their dependency order and registers that whole sequence.
// The order id each save addresses is captured before its first await and passed to send,
// onSaved and onFailed.

export const SECTION_AUTOSAVE_DEBOUNCE_MS = 800

export type SectionAutosaveConfig<Body, Result> = {
  orderId: number
  locked: boolean
  // Key of the body the server is believed to hold when the section mounts (read on the first
  // render only). The owner derives it from the seeded state, so opening the tab never writes.
  initialBaselineKey: string | null
  // The full-replace body for the CURRENT form or intent, read through refs, or null.
  buildBody: () => Body | null
  // Comparison key of a body.
  bodyKey: (body: Body) => string
  // Whether a body still has to be sent against the confirmed baseline (by default: its key
  // differs from the baseline key).
  needsSave?: (body: Body, baselineKey: string | null) => boolean
  send: (orderId: number, body: Body) => Promise<Result>
  // A save succeeded (before its drain). Returns the new baseline key.
  onSaved: (saved: { orderId: number; body: Body; result: Result }) => string | null
  onFailed?: (failed: { orderId: number; body: Body; error: unknown }) => void
  // A finished drain, or a trigger that found nothing to send, left this section with nothing
  // in flight or queued (the Customer tab starts the billing copy that waits for installation).
  onIdle?: () => void
}

export type SectionAutosave = {
  // Render state: a save (or its drain) is running; the section error (the last failure, or
  // the ORDER_LOCKED refusal shown again for a refused body; cleared when a save starts, when
  // a trigger or drain finds the body equal to the confirmed baseline with no ambiguous
  // failure since the last success, or by the owner); the confirmed baseline key; a save
  // succeeded since the section mounted.
  saving: boolean
  error: unknown
  baselineKey: string | null
  savedThisSession: boolean
  schedule: () => void
  flushNow: () => void
  request: () => void
  clearError: () => void
  // Forget the ORDER_LOCKED refusals whose keys match (all when no matcher is given), when
  // another save shows those bodies may go again.
  forgetRefusals: (match?: (key: string) => boolean) => void
  // Imperative reads for event handlers and async continuations (never stale).
  isBusy: () => boolean
  baseline: () => string | null
  activeChain: () => Promise<void> | null
  // Resolves once this section has no save in flight or queued.
  whenIdle: () => Promise<void>
}

function isOrderLocked(err: unknown): boolean {
  return err instanceof ApiError && err.code === 'ORDER_LOCKED'
}

// No readable answer from the service (a network failure, a gateway or timeout status, or a
// success whose body could not be read): the save MAY have committed. QuoteTab's rule plus the
// unreadable-2xx case.
function isAmbiguousFailure(err: unknown): boolean {
  if (!(err instanceof ApiError)) return true
  return (
    err.status === 0 ||
    (err.status >= 200 && err.status < 300) ||
    err.status === 502 ||
    err.status === 503 ||
    err.status === 504
  )
}

export function useSectionAutosave<Body, Result>(
  config: SectionAutosaveConfig<Body, Result>,
): SectionAutosave {
  const configRef = useRef(config)
  configRef.current = config

  const baselineRef = useRef<string | null>(config.initialBaselineKey)
  const savingRef = useRef(false)
  const pendingRef = useRef<Body | null>(null)
  const chainRef = useRef<Promise<void> | null>(null)
  const debounceRef = useRef<number | null>(null)
  // Keys of the bodies the server refused with ORDER_LOCKED, and that refusal; both are
  // cleared by a later success of this section.
  const refusedKeysRef = useRef<Set<string>>(new Set())
  const refusalRef = useRef<unknown>(null)
  // A save failed without a readable answer since the last success, so the confirmed baseline
  // is uncertain (reset only by a success).
  const ambiguousFailureRef = useRef(false)
  const mountedRef = useRef(true)

  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [baselineKey, setBaselineKey] = useState<string | null>(
    config.initialBaselineKey,
  )
  const [savedThisSession, setSavedThisSession] = useState(false)

  function clearDebounce() {
    if (debounceRef.current !== null) {
      window.clearTimeout(debounceRef.current)
      debounceRef.current = null
    }
  }

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      clearDebounce()
    }
  }, [])

  function needsSave(body: Body): boolean {
    const current = configRef.current
    if (refusedKeysRef.current.has(current.bodyKey(body))) return false
    return current.needsSave
      ? current.needsSave(body, baselineRef.current)
      : current.bodyKey(body) !== baselineRef.current
  }

  // A trigger or drain decided not to send `body`: keep the section error truthful. A body
  // equal to the confirmed baseline is saved, so a definite failure of an older body is stale
  // (but after an ambiguous failure since the last success the error is kept: the baseline
  // is uncertain); a body the server refused as locked shows that refusal again.
  function settleErrorFor(body: Body) {
    if (!mountedRef.current) return
    const key = configRef.current.bodyKey(body)
    if (refusedKeysRef.current.has(key)) setError(refusalRef.current)
    else if (key === baselineRef.current && !ambiguousFailureRef.current) {
      setError(null)
    }
  }

  async function runSave(body: Body): Promise<void> {
    savingRef.current = true
    if (mountedRef.current) {
      setSaving(true)
      setError(null)
    }
    // Capture the order id this save is addressed to BEFORE awaiting.
    const saveOrderId = configRef.current.orderId
    try {
      const result = await configRef.current.send(saveOrderId, body)
      const nextBaseline = configRef.current.onSaved({
        orderId: saveOrderId,
        body,
        result,
      })
      baselineRef.current = nextBaseline
      refusedKeysRef.current.clear()
      refusalRef.current = null
      ambiguousFailureRef.current = false
      if (mountedRef.current) {
        setBaselineKey(nextBaseline)
        setSavedThisSession(true)
      }
    } catch (err) {
      if (isOrderLocked(err)) {
        refusedKeysRef.current.add(configRef.current.bodyKey(body))
        refusalRef.current = err
      }
      if (isAmbiguousFailure(err)) ambiguousFailureRef.current = true
      configRef.current.onFailed?.({ orderId: saveOrderId, body, error: err })
      if (mountedRef.current) setError(err)
    } finally {
      savingRef.current = false
      const pending = pendingRef.current
      pendingRef.current = null
      if (pending !== null && needsSave(pending)) {
        // AWAIT the drain so the chain promise spans the whole queue.
        await runSave(pending)
      } else {
        // Settle the error against the queued body, then against the freshest body (an
        // edit made after the last trigger is still in its debounce).
        if (pending !== null) settleErrorFor(pending)
        const latest = configRef.current.buildBody()
        if (latest !== null) settleErrorFor(latest)
        if (mountedRef.current) setSaving(false)
        configRef.current.onIdle?.()
      }
    }
  }

  function startChain(body: Body) {
    // runSave catches every save failure itself; this catch only keeps a throwing owner
    // callback from rejecting the chain that flushes and registry entries await.
    const chain = runSave(body).catch(() => {})
    chainRef.current = chain
    void chain.finally(() => {
      if (chainRef.current === chain) chainRef.current = null
    })
  }

  function request() {
    const current = configRef.current
    if (current.locked) return
    const body = current.buildBody()
    if (body === null) {
      if (!savingRef.current) current.onIdle?.()
      return
    }
    if (savingRef.current) {
      // Queue-first: the baseline is one save stale while a save is in flight, so the latest
      // body is queued and the drain compares it with the post-save baseline.
      pendingRef.current = body
      return
    }
    if (!needsSave(body)) {
      settleErrorFor(body)
      current.onIdle?.()
      return
    }
    startChain(body)
  }

  function schedule() {
    if (configRef.current.locked) return
    clearDebounce()
    debounceRef.current = window.setTimeout(() => {
      debounceRef.current = null
      request()
    }, SECTION_AUTOSAVE_DEBOUNCE_MS)
  }

  function flushNow() {
    clearDebounce()
    request()
  }

  async function whenIdle(): Promise<void> {
    // A drain belongs to its chain; settling also clears chainRef first (its finally callback
    // was attached before this await), so the loop only continues for a chain started since.
    let chain = chainRef.current
    while (chain !== null) {
      await chain
      chain = chainRef.current
    }
  }

  return {
    saving,
    error,
    baselineKey,
    savedThisSession,
    schedule,
    flushNow,
    request,
    clearError: () => setError(null),
    forgetRefusals: (match) => {
      for (const key of [...refusedKeysRef.current]) {
        if (match === undefined || match(key)) refusedKeysRef.current.delete(key)
      }
      if (refusedKeysRef.current.size === 0) refusalRef.current = null
    },
    isBusy: () => savingRef.current || pendingRef.current !== null,
    baseline: () => baselineRef.current,
    activeChain: () => chainRef.current,
    whenIdle,
  }
}
