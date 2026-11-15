// The waiting room (04 §Waiting room, DESIGN W2 room panel and W8): the queue shape, copy helpers and the admission
// kept in the tab. Shared by the join route handler and the browser; no secret here. The admission token is useless
// without the httpOnly session (bound to the user, plan Q152); it lives in sessionStorage, never in HTML.

import { fmt, type Dict } from './i18n'

export const QUEUE_STATUSES = ['PRE_QUEUE', 'WAITING', 'ADMITTED', 'EXPIRED', 'PAUSED', 'ENDED'] as const
export type QueueStatus = (typeof QUEUE_STATUSES)[number]
export type Admission = { token: string; expires_at: string }

/** `GET /fan/queue` (and `POST /fan/queue`), field by field. */
export type Queue = {
  event_id: number
  status: QueueStatus
  sale_starts_at: string
  position: number | null
  in_room: number
  rate_per_min: number
  estimated_wait_s: number | null
  sold_out: boolean
  admission: Admission | null
  queue_token: string
  poll_after_s: number
  server_time: string
}

type Raw = Record<string, unknown>
const int = (v: unknown) => (Number.isSafeInteger(v) ? (v as number) : null)
const str = (v: unknown) => (typeof v === 'string' ? v : null)

/** A JWT's shape (three base64url parts), so a token is safe to put in a header. */
export const isJwt = (v: unknown): v is string => typeof v === 'string' && v.length <= 4096 && /^[\w-]+\.[\w-]+\.[\w-]+$/.test(v)

/** The 04 shape, or null when the body isn't one (unknown status, no token, no server time). */
export function toQueue(v: unknown): Queue | null {
  const r = (v && typeof v === 'object' ? v : {}) as Raw
  const a = (r.admission && typeof r.admission === 'object' ? r.admission : null) as Raw | null
  const status = QUEUE_STATUSES.find((s) => s === r.status)
  const serverTime = str(r.server_time)
  if (!status || !isJwt(r.queue_token) || !serverTime || Number.isNaN(Date.parse(serverTime))) return null
  return {
    event_id: int(r.event_id) ?? 0,
    status,
    sale_starts_at: str(r.sale_starts_at) ?? serverTime,
    position: int(r.position),
    in_room: int(r.in_room) ?? 0,
    rate_per_min: int(r.rate_per_min) ?? 0,
    estimated_wait_s: int(r.estimated_wait_s),
    sold_out: r.sold_out === true,
    admission: a && isJwt(a.token) && str(a.expires_at) ? { token: a.token, expires_at: a.expires_at as string } : null,
    queue_token: r.queue_token,
    poll_after_s: Math.max(0, int(r.poll_after_s) ?? 5),
    server_time: serverTime,
  }
}

/** Polling stops on these (04 `poll_after_s` 0). */
export const FINAL_QUEUE: readonly QueueStatus[] = ['ADMITTED', 'EXPIRED', 'ENDED']

/** Plan Q165, rounded honestly: "moins d'une minute", "environ {n} min", above an hour "plus d'une heure". */
export function waitText(c: Pick<Dict['room'], 'waitLess' | 'waitMin' | 'waitHour'>, seconds: number) {
  if (seconds < 60) return c.waitLess
  if (seconds > 3600) return c.waitHour
  return fmt(c.waitMin, { n: Math.ceil(seconds / 60) })
}

/** DESIGN screen 12: lit dots = ⌊12 × (1 − position ÷ first position seen)⌋, at most 11 until the turn. */
export const litDots = (position: number, first: number) => Math.min(11, Math.max(0, Math.floor(12 * (1 - position / Math.max(first, position)))))

/** `mm:ss` (a turn lasts at most 10 min). */
export const mmss = (seconds: number) => {
  const s = Math.max(0, seconds)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

// Tab storage, per event. Private mode or a full quota → nothing stored: the fan goes through W8 again.
const store = {
  get: (k: string) => {
    try {
      return sessionStorage.getItem(k)
    } catch {
      return null
    }
  },
  set: (k: string, v: string | null) => {
    try {
      if (v === null) sessionStorage.removeItem(k)
      else sessionStorage.setItem(k, v)
    } catch {
      // not kept
    }
  },
}
export const admissionKey = (eventId: number) => `jomhor.admission.${eventId}`
const firstKey = (eventId: number) => `jomhor.queue-first.${eventId}`

/** The raw stored admission (a stable string, for useSyncExternalStore), or null. */
export const storedAdmission = (eventId: number) => store.get(admissionKey(eventId))
export const saveAdmission = (eventId: number, a: Admission | null) => store.set(admissionKey(eventId), a && JSON.stringify(a))

/** The admission still valid at `serverNow` (server clock, never the device's), or null. */
export function parseAdmission(raw: string | null, serverNow: number): Admission | null {
  try {
    const a = raw ? (JSON.parse(raw) as Raw) : null
    return a && isJwt(a.token) && typeof a.expires_at === 'string' && Date.parse(a.expires_at) > serverNow ? { token: a.token, expires_at: a.expires_at } : null
  } catch {
    return null
  }
}

/** The first position this tab saw (the dots' scale), kept across reloads; set when absent. */
export function firstPosition(eventId: number, position: number): number {
  const seen = Number(store.get(firstKey(eventId)))
  if (Number.isSafeInteger(seen) && seen >= position) return seen
  store.set(firstKey(eventId), String(position))
  return position
}
