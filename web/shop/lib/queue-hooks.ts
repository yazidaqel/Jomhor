// Client hooks for the waiting room (W2 room panel, W8). Kept out of lib/queue.ts, which route handlers import.

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { pageClock } from './order-client'
import { parseAdmission, storedAdmission, type Admission } from './queue'

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

/** This tab's W8 turn for the event while it runs at `serverNow`; null on the server render and after it ends. */
export function useAdmission(eventId: number, serverNow: number): Admission | null {
  const raw = useSyncExternalStore(noSubscribe, () => storedAdmission(eventId), () => null)
  return parseAdmission(raw, serverNow)
}
