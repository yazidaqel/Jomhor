import 'server-only'
import { cache } from 'react'
import { api, ApiError, organizerId } from './api'

// Public event reads (`GET /fan/events*`, no Authorization header). Responses are mapped field by field to the types
// below: whatever else the backend sends (now or by mistake) never reaches HTML or client props.

export type Price = { price_category_id: string; name: string; price_mad: number; remaining: number }
export type Zone = { id: number; code: string; name: string; seated: boolean; gate_codes: string[]; prices: Price[] }
export type ShopEvent = {
  id: number
  event_type: string
  title: string
  starts_at: string
  doors_open_at: string | null
  timezone: string
  venue: { name: string; city: string | null }
  /** Display label from `metadata` (competition · matchday); metadata itself is not kept. */
  competition: string | null
  matchday: number | null
  sale_starts_at: string | null
  sales_close_at: string | null
  on_sale: boolean
  sold_out: boolean
  from_price_mad: number | null
  per_account_limit: number
}
export type ShopEventDetail = ShopEvent & { zones: Zone[] }

/** Seconds the backend response is reused (W1 list, W2 detail): DESIGN.md §Web shop. */
export const LIST_REVALIDATE_S = 60
export const DETAIL_REVALIDATE_S = 15

type Raw = Record<string, unknown>
const str = (v: unknown) => (typeof v === 'string' ? v : null)
const int = (v: unknown) => (Number.isSafeInteger(v) ? (v as number) : null)

function toEvent(r: Raw): ShopEvent {
  const venue = (r.venue ?? {}) as Raw
  const meta = (r.metadata ?? {}) as Raw
  return {
    id: r.id as number,
    event_type: String(r.event_type),
    title: String(r.title),
    starts_at: String(r.starts_at),
    doors_open_at: str(r.doors_open_at),
    timezone: String(r.timezone),
    venue: { name: String(venue.name), city: str(venue.city) },
    competition: r.event_type === 'FOOTBALL_MATCH' ? str(meta.competition) : null,
    matchday: r.event_type === 'FOOTBALL_MATCH' ? int(meta.matchday) : null,
    sale_starts_at: str(r.sale_starts_at),
    sales_close_at: str(r.sales_close_at),
    on_sale: r.on_sale === true,
    sold_out: r.sold_out === true,
    from_price_mad: int(r.from_price_mad),
    per_account_limit: int(r.per_account_limit) ?? 0,
  }
}

function toZone(z: Raw): Zone {
  return {
    id: z.id as number,
    code: String(z.code),
    name: String(z.name),
    seated: z.seated === true,
    gate_codes: Array.isArray(z.gate_codes) ? z.gate_codes.map(String) : [],
    prices: (Array.isArray(z.prices) ? (z.prices as Raw[]) : []).map((p) => ({
      price_category_id: String(p.price_category_id),
      name: String(p.name),
      price_mad: int(p.price_mad) ?? 0,
      remaining: int(p.remaining) ?? 0,
    })),
  }
}

/**
 * The sale window says open (`sale_starts_at` passed, `sales_close_at` not reached) but the read says not on sale.
 * Either the read is a stale cached copy (Next's fetch cache is stale-while-revalidate, so it can be arbitrarily old)
 * or the shop clock runs ahead of the DB clock. Such reads are fetched again, uncached.
 */
export function windowContradicts(e: Pick<ShopEvent, 'on_sale' | 'sale_starts_at' | 'sales_close_at'>, now: number) {
  if (e.on_sale || !e.sale_starts_at) return false
  return Date.parse(e.sale_starts_at) <= now && (!e.sales_close_at || now < Date.parse(e.sales_close_at))
}

/** Upcoming events of this shop's organizer (W1). Rows of another organizer are dropped. */
export async function getEvents(now = Date.now()): Promise<ShopEvent[]> {
  const org = organizerId()
  const read = async (fresh: boolean) =>
    ((await api<Raw[]>(`/fan/events?organizer=${encodeURIComponent(org)}`, fresh ? {} : { next: { revalidate: LIST_REVALIDATE_S } })) ?? [])
      .filter((r) => r.organizer_id === org)
      .map(toEvent)
  const events = await read(false)
  return events.some((e) => windowContradicts(e, now)) ? read(true) : events
}

/** One event of this shop's organizer (W2), or null when unknown, DRAFT or another organizer's. */
export const getEvent = cache(async (id: number, now = Date.now()): Promise<ShopEventDetail | null> => {
  const read = async (fresh: boolean) => {
    try {
      return await api<Raw>(`/fan/events/${id}`, fresh ? {} : { next: { revalidate: DETAIL_REVALIDATE_S } })
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return null
      throw e
    }
  }
  let raw = await read(false)
  if (raw && windowContradicts(toEvent(raw), now)) raw = await read(true)
  if (!raw || raw.organizer_id !== organizerId()) return null
  return { ...toEvent(raw), zones: (Array.isArray(raw.zones) ? (raw.zones as Raw[]) : []).map(toZone) }
})

/** `ATLAS FC vs Rapid Oued` → `atlas-fc-vs-rapid-oued` (accents folded, at most 80 chars). */
export const slugify = (title: string) =>
  title
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .slice(0, 80)
    .replace(/^-+|-+$/g, '')

/** The canonical W2 slug: `{id}-{slugified title}`, or `{id}` when the title has no Latin letters or digits. */
export const eventSlug = (e: Pick<ShopEvent, 'id' | 'title'>) => [e.id, slugify(e.title)].filter(Boolean).join('-')

/** The event id a slug starts with (`12-atlas-fc…` → 12), or null. Ids are positive int4 (docs/03-schema.sql). */
export function parseEventId(slug: string): number | null {
  const m = /^([1-9]\d{0,9})(?:-|$)/.exec(slug)
  const id = m ? Number(m[1]) : NaN
  return id <= 2_147_483_647 ? id : null
}

export type SaleState = 'ON_SALE' | 'NOT_STARTED' | 'OPENING' | 'SOLD_OUT' | 'CLOSED'

/**
 * What the fan can do now. The server's `on_sale` decides; the local clock only moves the edges of a cached read.
 * While the window says open but even an uncached read says not on sale (clock skew), the state is OPENING
 * ("ouverture imminente", cards disabled), never CLOSED.
 */
export function saleState(e: Pick<ShopEvent, 'on_sale' | 'sold_out' | 'sale_starts_at' | 'sales_close_at'>, now: number): SaleState {
  if (e.sale_starts_at && now < Date.parse(e.sale_starts_at)) return 'NOT_STARTED'
  if (e.sold_out) return 'SOLD_OUT'
  if (windowContradicts(e, now)) return 'OPENING'
  if (!e.on_sale || (e.sales_close_at && now >= Date.parse(e.sales_close_at))) return 'CLOSED'
  return 'ON_SALE'
}
