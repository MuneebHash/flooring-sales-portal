import { useEffect, useRef } from 'react'

// Phase 16G PR 6 (#120) - the shared poll of the Quote tab (issued/accepted snapshots while a
// quote link waits for a signature) and the Invoice tab (the current invoice while its invoice
// link waits for a signature): every 5 seconds (since Batch A PR 2, Muneeb's decision 29 October
// 2026; 15 seconds in 16G PR 6).
const POLL_INTERVAL_MS = 5_000

// Calls onPoll every 5 seconds while `enabled` is true AND the browser tab is visible
// (document.visibilityState 'visible'). The first tick comes 5 seconds after the condition
// becomes true. Hiding the browser tab suspends the interval; showing it again starts a fresh
// interval WITHOUT an immediate poll, so the first poll after a return also comes 5 seconds later.
// The timer and the visibilitychange listener are removed when `enabled` turns false and on unmount.
//
// onPoll is read through a ref, so every tick runs the LATEST callback (fresh state, no stale
// closure) without restarting the interval on re-render. The hook makes no request itself: each
// caller enforces its own guards (in-flight reads and mutations, expiry) inside onPoll, and only
// ever reads.
export function usePollWhileVisible(enabled: boolean, onPoll: () => void): void {
  const onPollRef = useRef(onPoll)
  onPollRef.current = onPoll

  useEffect(() => {
    if (!enabled) return
    let timer: number | null = null
    const start = () => {
      if (timer !== null) return
      timer = window.setInterval(() => onPollRef.current(), POLL_INTERVAL_MS)
    }
    const stop = () => {
      if (timer === null) return
      window.clearInterval(timer)
      timer = null
    }
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        start()
      } else {
        stop()
      }
    }
    handleVisibilityChange()
    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      stop()
    }
  }, [enabled])
}
