// Client hooks for the waiting room (W2 room panel, W8). Kept out of lib/queue.ts, which route handlers import.

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { pageClock } from './order-client'
import { holdsPlace, parseAdmission, storedAdmission, storedPlace, type Admission } from './queue'

/**
 * Server time, ticking every second while `active`: the server clock at SSR (`initial`) plus the monotonic page clock
 * (pageClock), never the device's wall clock. `resync(serverMs)` re-bases it on a fresher server time (`server_time`);
 * `read()` gives it now, for callbacks.
 */
export function useServerClock(initial: number, active = true) {
  const [now, setNow] = useState(initial)
  const base = useRef({ server: initial, at: 0 })
  const clock = useRef<(() => number) | null>(null)
  useEffect(() => {
    const c = (clock.current ??= pageClock()) // one clock for the component's life: `base.at` readings stay comparable
    if (!active) return
    const t = setInterval(() => setNow(base.current.server + c() - base.current.at), 1000)
    return () => clearInterval(t)
  }, [active])
  const resync = useCallback((server: number) => {
    base.current = { server, at: clock.current?.() ?? 0 }
    setNow(server)
  }, [])
  /** The server time right now, for callbacks (the state value lags up to a second). */
  const read = useCallback(() => base.current.server + (clock.current?.() ?? 0) - base.current.at, [])
  return [now, resync, read] as const
}

const noSubscribe = () => () => {}

/**
 * This tab's W8 turn for the event while it runs at `serverNow`, won in sale `phase`; null on the server render, after
 * it ends, and for a turn of another phase.
 */
export function useAdmission(eventId: number, serverNow: number, phase: number): Admission | null {
  const raw = useSyncExternalStore(noSubscribe, () => storedAdmission(eventId), () => null)
  return parseAdmission(raw, serverNow, phase)
}

/** Whether this browser has a place in the event's current `phase` (W2 "Reprendre ma place"); false on the server render. */
export function useHeldPlace(eventId: number, phase: number): boolean {
  return holdsPlace(useSyncExternalStore(noSubscribe, () => storedPlace(eventId), () => null), phase)
}

/**
 * Keeps the screen on while the page is visible (DESIGN Lot 5 W8: Wake Lock API). The browser drops the lock when the
 * tab is hidden; it is released then too and taken again when visible. Unsupported or refused → nothing, silently.
 */
export function useWakeLock() {
  useEffect(() => {
    let lock: WakeLockSentinel | null = null
    let asking = false
    let done = false
    const drop = () => {
      lock?.release().catch(() => {})
      lock = null
    }
    const take = () => {
      if (done || asking || lock || document.hidden || !('wakeLock' in navigator)) return
      asking = true
      navigator.wakeLock.request('screen').then(
        (l) => {
          asking = false
          lock = l
          // Released by the browser (tab hidden, battery saver): forget it, so the next visibility takes a new one.
          l.addEventListener('release', () => {
            if (lock === l) lock = null
          })
          if (done || document.hidden) drop() // hidden or left while asking
        },
        () => {
          asking = false
        },
      )
    }
    const onVisibility = () => (document.hidden ? drop() : take())
    take()
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      done = true
      document.removeEventListener('visibilitychange', onVisibility)
      drop()
    }
  }, [])
}
