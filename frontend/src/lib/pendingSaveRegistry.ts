// Batch A #101 - the per-order registry of pending autosave work that outlives its tab.
//
// A tab that unmounts (the user leaves the order or switches to another order in place)
// registers the save work it leaves running: the in-flight save chain with its queued drain,
// or the bare unmount flush, and for the Customer tab the whole customer / installation /
// billing sequence including the billing copy that waits for installation. A reopen of the
// SAME order in this browser document then waits for that work before it reads, so it never
// seeds a form (and its saved baseline) from a read taken before those saves committed. Each
// order and surface has its own key: a slow quote save delays only the Quote tab's probe, and
// waiting for one order never blocks another.
//
// Entries never reject and are removed only when their own work settles: an older entry
// settling never removes a newer one, a newer registration never replaces unfinished work,
// and a reader's timeout never erases work that is still running. Settling is ordering
// evidence only, never proof that a save succeeded: a rejected save settles too, and the
// reopened tab then shows what the server holds.
//
// This coordination only orders pending saves and same-order reads within one browser document. It does not prevent stale writes from simultaneous screens/tabs/devices, survive a reload or new document, resolve unknown save outcomes, or preserve pending edits on logout. Server-side stale-write rejection is a separate post-pilot task.
//
// Separately, edits still inside an autosave debounce are lost on a reload or a browser tab
// close (there is no unload handler), and on a logout the unmount flushes send them only after
// the session has ended, so the server refuses them: that is lost work, not a stale write.

export type PendingSaveSurface = 'quote' | 'customer' | 'enquiry'

// The bounded wait of a same-order read (the workspace GET and the quote probe). A wait that
// runs out never lets the read proceed: the reader shows its existing error state with a retry,
// and the retry waits again.
export const PENDING_SAVE_WAIT_TIMEOUT_MS = 20_000

const pendingSaves = new Map<string, Set<Promise<void>>>()

function registryKey(orderId: number, surface: PendingSaveSurface): string {
  return `${orderId}:${surface}`
}

// Register `work` for this order and surface. Call it BEFORE returning from an unmount cleanup,
// and register work that is already running even when the cleanup then stops early (a locked
// order or an invalid body only prevents NEW writes).
export function registerPendingSave(
  orderId: number,
  surface: PendingSaveSurface,
  work: Promise<unknown>,
): void {
  const key = registryKey(orderId, surface)
  const entry: Promise<void> = work.then(
    () => undefined,
    () => undefined,
  )
  let entries = pendingSaves.get(key)
  if (entries === undefined) {
    entries = new Set()
    pendingSaves.set(key, entries)
  }
  entries.add(entry)
  void entry.then(() => {
    const current = pendingSaves.get(key)
    if (current === undefined) return
    current.delete(entry)
    if (current.size === 0) pendingSaves.delete(key)
  })
}

// Resolves true once every queried surface of this order has no pending entry, including work
// registered while waiting; false when the ONE deadline set at the start passes first (the
// entries stay registered). Never rejects.
export async function waitForPendingSaves(
  orderId: number,
  surfaces: readonly PendingSaveSurface[],
  timeoutMs: number = PENDING_SAVE_WAIT_TIMEOUT_MS,
): Promise<boolean> {
  const collect = (): Promise<void>[] => {
    const found: Promise<void>[] = []
    for (const surface of surfaces) {
      const entries = pendingSaves.get(registryKey(orderId, surface))
      if (entries !== undefined) found.push(...entries)
    }
    return found
  }
  let entries = collect()
  if (entries.length === 0) return true
  let timer: number | undefined
  const deadline = new Promise<'timeout'>((resolve) => {
    timer = window.setTimeout(() => resolve('timeout'), timeoutMs)
  })
  try {
    while (entries.length > 0) {
      const outcome = await Promise.race([
        Promise.all(entries).then(() => 'settled' as const),
        deadline,
      ])
      if (outcome === 'timeout') return false
      entries = collect()
    }
    return true
  } finally {
    window.clearTimeout(timer)
  }
}
