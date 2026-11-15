// The sold-out waitlist (04 §Waitlist, DESIGN Lot 5 W2): notify only, first come, no hold (plan Q176). Shared by the
// route handler and the browser; no secret here. Read when W2 opens, never polled.

/** 04 `GET /fan/waitlist/{event_id}` `entry`: when the fan joined, and when the notice went out (null = still waiting). */
export type WaitlistEntry = { joined_at: string; notified_at: string | null }

/** 04 `WAITLIST_CLOSED` `details.reason`. */
export const WAITLIST_REASONS = ['NOT_SOLD_OUT', 'ROOM', 'CLOSED'] as const
export type WaitlistReason = (typeof WAITLIST_REASONS)[number]

/** W2 `?waitlist=1`: the login's way back, so a signed-out fan joins on return (DESIGN screen 1 "login, then join"). */
export const JOIN_PARAM = 'waitlist'

/**
 * The signed-out CTA's one-shot marker (this tab): `?waitlist=1` joins only when it is there for the event, so a crafted
 * link can't put a signed-in fan on a list they never asked for. Unavailable storage → no auto-join (the CTA still works).
 */
const JOIN_MARK = 'jomhor.waitlist-join'
export function markJoin(eventId: number) {
  try {
    sessionStorage.setItem(JOIN_MARK, String(eventId))
  } catch {
    // not kept: the fan taps "Liste d'attente" again after the login
  }
}
/** Whether the marker is there for `eventId`; cleared either way. */
export function takeJoin(eventId: number): boolean {
  try {
    const v = sessionStorage.getItem(JOIN_MARK)
    sessionStorage.removeItem(JOIN_MARK)
    return v === String(eventId)
  } catch {
    return false
  }
}

/** The `{entry}` body's entry, field by field, or null (none, or not the 04 shape). */
export function toEntry(body: unknown): WaitlistEntry | null {
  const e = (body as { entry?: unknown } | null)?.entry as Record<string, unknown> | null | undefined
  if (!e || typeof e.joined_at !== 'string') return null
  return { joined_at: e.joined_at, notified_at: typeof e.notified_at === 'string' ? e.notified_at : null }
}

/** On the list: an entry not yet notified. A notified entry waits no more; joining again puts the fan at the back. */
export const isWaiting = (e: WaitlistEntry | null) => !!e && e.notified_at === null

/** The `WAITLIST_CLOSED` reason of an error body, or null. */
export function closedReason(body: unknown): WaitlistReason | null {
  const b = (body ?? {}) as { code?: unknown; details?: { reason?: unknown } }
  return b.code === 'WAITLIST_CLOSED' ? (WAITLIST_REASONS.find((r) => r === b.details?.reason) ?? null) : null
}
