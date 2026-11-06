import 'server-only'
import { api } from './api'

// Fan tickets (`GET /fan/tickets`, W5). The shop never sends `with_keys` (04 §Fan): it holds no ticket key. The answer
// is still mapped field by field, so a `k_t` (or anything else) sent by mistake never reaches the browser.

export type TicketView = {
  id: string
  ticket_no: string
  status: string
  holder_label: string | null
  seat_label: string | null
  event: {
    title: string
    /** `ON_SALE`, `GATES_OPEN`, `CLOSED`; null if the backend leaves it out. */
    status: string | null
    starts_at: string
    timezone: string
    venue: { name: string; city: string | null }
  }
  zone: { code: string; name: string }
  gate_codes: string[]
  /** 04: past = event `CLOSED` or `starts_at` more than 12 h ago (against `server_time`, not the shop's clock). */
  past: boolean
}

/** Path-safe ticket id: tickets are UUIDs. */
export const TICKET_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** 04 §Holder names: last 8 hex chars of the id, uppercase, grouped 4-4 → `4D5C 6B7A`. */
export const ticketNo = (id: string) => `${id.slice(-8, -4)} ${id.slice(-4)}`.toUpperCase()

const PAST_AFTER_MS = 12 * 3600_000

type Raw = Record<string, unknown>
const obj = (v: unknown): Raw => (v && typeof v === 'object' ? (v as Raw) : {})
const strOrNull = (v: unknown) => (typeof v === 'string' ? v : null)

export function toTickets(raw: unknown): TicketView[] {
  const r = obj(raw)
  const now = Date.parse(String(r.server_time))
  return (Array.isArray(r.tickets) ? r.tickets : []).map((v) => {
    const t = obj(v)
    const e = obj(t.event)
    const venue = obj(e.venue)
    const zone = obj(t.zone)
    const starts_at = String(e.starts_at)
    return {
      id: String(t.id),
      ticket_no: String(t.ticket_no),
      status: String(t.status),
      holder_label: strOrNull(t.holder_label),
      seat_label: strOrNull(t.seat_label),
      event: {
        title: String(e.title),
        status: strOrNull(e.status),
        starts_at,
        timezone: String(e.timezone),
        venue: { name: String(venue.name), city: strOrNull(venue.city) },
      },
      zone: { code: String(zone.code), name: String(zone.name) },
      gate_codes: Array.isArray(t.gate_codes) ? t.gate_codes.map(String) : [],
      past: e.status === 'CLOSED' || Date.parse(starts_at) + PAST_AFTER_MS <= now,
    }
  })
}

/** The caller's tickets, upcoming first then past (the backend's order). No `with_keys`, ever. */
export const getTickets = async (token: string) => toTickets(await api('/fan/tickets', { token }))
