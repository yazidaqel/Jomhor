import 'server-only'
import { cache } from 'react'
import { api, ApiError } from './api'
import { PAY_METHODS, type PayMethod } from './order-client'

// Public event reads (`GET /fan/events*`, no Authorization header). Responses are mapped field by field to the types
// below: whatever else the backend sends (now or by mistake) never reaches HTML or client props.

/**
 * The event's club as fans see it (04 §Club directory): `slug` null = unlisted (no shop URL). Nothing else from
 * `organizers` is kept.
 */
export type Organizer = { slug: string | null; name: string; crest_url: string | null }
export type Price = { price_category_id: string; name: string; price_mad: number; remaining: number }
export type Zone = { id: number; code: string; name: string; seated: boolean; gate_codes: string[]; prices: Price[] }
export type ShopEvent = {
  id: number
  organizer_id: string
  organizer: Organizer
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
/**
 * Lot 3: `payment_methods` = known methods in the 04 order (absent, a Lot 1 backend → card only); `cash_until` = when
 * cash stops being offered (refund cutoff − min window), null when the organizer has no cash set up.
 */
export type ShopEventDetail = ShopEvent & { zones: Zone[]; payment_methods: PayMethod[]; cash_until: string | null }

const methodsOf = (v: unknown): PayMethod[] => (Array.isArray(v) ? PAY_METHODS.filter((m) => v.includes(m)) : ['CARD'])

/** Seconds the backend response is reused (W1 list, W2 detail): DESIGN.md §Web shop. */
export const LIST_REVALIDATE_S = 60
export const DETAIL_REVALIDATE_S = 15

type Raw = Record<string, unknown>
const str = (v: unknown) => (typeof v === 'string' ? v : null)
const int = (v: unknown) => (Number.isSafeInteger(v) ? (v as number) : null)

/** `crest_url` is kept only as an https URL (the backend already allows Jomhor's crest host only). */
export const crestUrl = (v: unknown) => (typeof v === 'string' && v.startsWith('https://') ? v : null)

/** 04 §Club directory `organizer`, field by field. Also used for tickets (lib/tickets.ts). */
export function toOrganizer(v: unknown): Organizer {
  const o = (v && typeof v === 'object' ? v : {}) as Raw
  return { slug: str(o.slug), name: String(o.name ?? ''), crest_url: crestUrl(o.crest_url) }
}

function toEvent(r: Raw): ShopEvent {
  const venue = (r.venue ?? {}) as Raw
  const meta = (r.metadata ?? {}) as Raw
  return {
    id: r.id as number,
    organizer_id: String(r.organizer_id),
    organizer: toOrganizer(r.organizer),
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

/** The backend's cap on `GET /fan/events` (04), also applied to the merged "Mes clubs" list. */
export const LIST_MAX = 50

/**
 * One cached list read (one cache entry per URL), re-read uncached when a row contradicts its sale window. Rows of an
 * unlisted club (no slug, so no shop URL) are dropped.
 */
async function readList(path: string, keep: (r: Raw) => boolean, now: number) {
  const read = async (fresh: boolean) =>
    ((await api<Raw[]>(path, fresh ? {} : { next: { revalidate: LIST_REVALIDATE_S } })) ?? [])
      .filter(keep)
      .map(toEvent)
      .filter((e) => e.organizer.slug !== null)
  const events = await read(false)
  return events.some((e) => windowContradicts(e, now)) ? read(true) : events
}

const byStart = (a: ShopEvent, b: ShopEvent) => Date.parse(a.starts_at) - Date.parse(b.starts_at) || a.id - b.id

/**
 * Upcoming events (W1, W7). `'all'` = "Tout": one read without `organizer` (listed clubs only). Organizer ids = one
 * cached read per club (`?organizer=<id>`, rows of another organizer dropped), merged by (starts_at, id), at most 50:
 * the cache holds N clubs + 1 entries, never one per follow combination (plan §5).
 */
export async function getEvents(which: 'all' | readonly string[], now = Date.now()): Promise<ShopEvent[]> {
  if (which === 'all') return readList('/fan/events', () => true, now)
  const lists = await Promise.all(which.map((id) => readList(`/fan/events?organizer=${encodeURIComponent(id)}`, (r) => r.organizer_id === id, now)))
  return lists.flat().sort(byStart).slice(0, LIST_MAX)
}

/** One event (W2), or null when unknown or DRAFT. Any club, listed or not (the page checks the URL's club). */
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
  if (!raw) return null
  return { ...toEvent(raw), zones: (Array.isArray(raw.zones) ? (raw.zones as Raw[]) : []).map(toZone), payment_methods: methodsOf(raw.payment_methods), cash_until: str(raw.cash_until) }
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
