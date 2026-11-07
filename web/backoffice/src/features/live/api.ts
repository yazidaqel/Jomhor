import { useOrgMutation, useOrgQuery } from '@/features/auth/organizer'
import type { EventDetail, EventStatus } from '@/features/events/api'
import { path } from '@/lib/api'

// GET /org/events/{id}/live and POST /org/events/{id}/close (04-api §Organizer, §Gate reports).
// ADMIN, OPS and FINANCE read /live; close is ADMIN/OPS.
export type LiveGate = {
  gate_id: string
  code: string
  zone_codes: string[]
  /** Assigned ACTIVE devices (revoked ones are not counted). */
  devices_total: number
  /** Assigned ACTIVE devices seen within 2 min. */
  devices_online: number
  entered: number
  refused: number
  overrides: number
  rate_per_min: number
  last_sync_at: string | null
}
/** Assigned, ACTIVE, not yet acked the close, and silent for more than 5 min (or never seen). */
export type StaleDevice = { device_id: string; label: string; gate_id: string; gate_code: string; last_seen_at: string | null }
export type Live = {
  event_id: number
  status: EventStatus
  starts_at: string
  server_time: string
  sold: number
  /** Tickets with a canonical (first admitted) scan. */
  entered: number
  /** Admitted scans over the last 5 min ÷ 5; `_prev` the 5 min before. */
  rate_per_min: number
  rate_per_min_prev: number
  /** Refused result codes only (never OK/OVERRIDE), counts > 0. */
  refused: { total: number; by_result: Record<string, number> }
  /** Admitted − canonical: one ticket let in by two offline devices. */
  duplicates_late: number
  overrides: { total: number; cap_per_device: number; by_device: { device_id: string; label: string; count: number }[] }
  gates: LiveGate[]
  devices_stale: StaleDevice[]
}

/** DESIGN B4 (Q32): DB polling every 5 s; TanStack's default pauses it while the tab is hidden. */
export const LIVE_REFETCH_MS = 5000

// Under ['events', id]: closing the event refetches it, its list and this.
export const useLive = (eventId: string) =>
  useOrgQuery<Live>(['events', eventId, 'live'], `${path('org', 'events', eventId)}/live`, { refetchInterval: LIVE_REFETCH_MS })

export const useCloseEvent = (eventId: string) =>
  useOrgMutation(['events'], (_: void, send) => send<EventDetail>({ method: 'POST', path: `${path('org', 'events', eventId)}/close` }))

/** Close is accepted from these, at or after T0 (04: else 409 EVENT_NOT_EDITABLE). GATES_OPEN is never set in Lot 2 (Q38). */
export const CLOSABLE: readonly EventStatus[] = ['ON_SALE', 'GATES_OPEN']

/** Scans one device handles per minute (720 per 30 min): the gate's capacity is this × its devices. */
export const SCANS_PER_DEVICE_MIN = 24

/** Current rate ÷ the gate's scan capacity, as a ratio (1 = full); null without a device. */
export const charge = (g: Pick<LiveGate, 'rate_per_min' | 'devices_total'>) =>
  g.devices_total > 0 ? g.rate_per_min / (g.devices_total * SCANS_PER_DEVICE_MIN) : null

/**
 * < 70 % green, < 90 % amber, else red (plan lot-2 BO-10), on the whole percent the label shows: 0.696 reads "70 %",
 * so it is amber.
 */
export function chargeLevel(ratio: number) {
  const pct = Math.round(ratio * 100)
  return pct < 70 ? 'ok' : pct < 90 ? 'busy' : 'full'
}
