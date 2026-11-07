import 'server-only'
import { api } from './api'

// Fan orders (`/fan/orders*`), called with the session token from the httpOnly cookie. Bodies are rebuilt field by
// field in both directions: the browser can't add fields the shop didn't mean to send, and gets back only these.

/** Path-safe id (order and price category ids are UUIDs): no slash, dot or encoded character reaches a backend path. */
export const SAFE_ID = /^[\w-]{1,64}$/

export type OrderView = {
  id: string
  event_id: number
  status: string
  /** Absent until the backend sends it (04: PENDING / SUCCEEDED / FAILED, null before any /pay). */
  payment_status?: string | null
  /** Lot 3: the SUCCEEDED attempt's method, else the latest attempt's; null before /pay; absent from older backends. */
  payment_method?: string | null
  /** Lot 3: the pay code and where to pay it, while AWAITING_CASH with a PENDING code; else null. */
  cash: CashView | null
  total_mad: number
  reserved_until: string | null
  paid_at: string | null
  items: { price_category_id: string; name: string; qty: number; unit_price_mad: number; labels: string[] }[]
  /** Tickets the caller holds (a friend's ticket is never listed to the buyer). */
  ticket_count: number
}

export type CashView = {
  pay_code: string
  amount_mad: number
  points: { name: string; address: string | null; hours: string | null }[]
  networks: string[]
}

type Raw = Record<string, unknown>
const obj = (v: unknown): Raw => (v && typeof v === 'object' ? (v as Raw) : {})
const list = (v: unknown): Raw[] => (Array.isArray(v) ? v.map(obj) : [])
const strOrNull = (v: unknown) => (typeof v === 'string' ? v : null)

export function toOrderView(raw: unknown): OrderView {
  const r = obj(raw)
  return {
    id: String(r.id),
    event_id: Number(r.event_id),
    status: String(r.status),
    ...('payment_status' in r && { payment_status: strOrNull(r.payment_status) }),
    ...('payment_method' in r && { payment_method: strOrNull(r.payment_method) }),
    total_mad: Number(r.total_mad),
    reserved_until: strOrNull(r.reserved_until),
    paid_at: strOrNull(r.paid_at),
    items: list(r.items).map((i) => ({
      price_category_id: String(i.price_category_id),
      name: String(i.name),
      qty: Number(i.qty),
      unit_price_mad: Number(i.unit_price_mad),
      labels: list(i.holders).map((h) => String(h.label)),
    })),
    ticket_count: Array.isArray(r.ticket_ids) ? r.ticket_ids.length : 0,
    cash: toCash(r.cash),
  }
}

function toCash(v: unknown): CashView | null {
  if (!v || typeof v !== 'object') return null
  const c = v as Raw
  if (typeof c.pay_code !== 'string' || !Number.isSafeInteger(c.amount_mad)) return null
  return {
    pay_code: c.pay_code,
    amount_mad: c.amount_mad as number,
    points: list(c.points).map((p) => ({ name: String(p.name), address: strOrNull(p.address), hours: strOrNull(p.hours) })),
    networks: Array.isArray(c.networks) ? c.networks.filter((n): n is string => typeof n === 'string') : [],
  }
}

/**
 * The pay code as shown (04 §Cash codes): `4f7k29`, `JMH4F7K29` or `JMH 4F7K 29` → `JMH 4F7K 29`. Anything that isn't
 * 6 Crockford symbols after the input rules is shown as sent.
 */
export function formatPayCode(raw: string): string {
  const t = raw.toUpperCase().replace(/[\s-]/g, '')
  // A leading JMH is the prefix only on 9 characters: a bare code can itself start with J, M, H.
  const s = (t.length === 9 && t.startsWith('JMH') ? t.slice(3) : t).replace(/O/g, '0').replace(/[IL]/g, '1')
  return /^[0-9A-HJKMNP-TV-Z]{6}$/.test(s) ? `JMH ${s.slice(0, 4)} ${s.slice(4)}` : raw
}

/** DESIGN Lot 3 W4: the last 8 hex digits of the order id, 4-4 (`…3f1f6b2c9d10` → `6B2C 9D10`). */
export function orderNo(id: string): string {
  const hex = id.replace(/[^0-9a-f]/gi, '').slice(-8).toUpperCase()
  return `${hex.slice(0, 4)} ${hex.slice(4)}`
}

/**
 * The `POST /fan/orders` body rebuilt by type from what the browser sent: `event_id` (positive integer), then per item
 * `price_category_id` (id string), `qty` (positive integer) and `holders[{name, phone?}]` (strings; an empty phone
 * is left out), channel WEB. Anything else is dropped. Null when a value has the wrong type or shape: the form never
 * sends that, so it is a tampered request (422). Name and phone rules are the backend's (422 with field paths).
 */
export function orderPayload(body: Raw) {
  const posInt = (v: unknown) => Number.isSafeInteger(v) && (v as number) > 0
  if (!posInt(body.event_id) || !Array.isArray(body.items) || body.items.length === 0) return null
  const items = []
  for (const raw of body.items) {
    const i = obj(raw)
    if (typeof i.price_category_id !== 'string' || !SAFE_ID.test(i.price_category_id) || !posInt(i.qty) || !Array.isArray(i.holders)) return null
    const holders = []
    for (const h of i.holders.map(obj)) {
      if (typeof h.name !== 'string' || (h.phone !== undefined && typeof h.phone !== 'string')) return null
      holders.push(h.phone ? { name: h.name, phone: h.phone } : { name: h.name })
    }
    items.push({ price_category_id: i.price_category_id, qty: i.qty as number, holders })
  }
  return { event_id: body.event_id as number, channel: 'WEB' as const, items }
}

/**
 * Whether `redirect_url` from `/pay` is the backend's own card redirect: same origin as `API_PUBLIC_URL` (the
 * backend's public base, with /v1), path `{base}/payments/card/{payment_id}/redirect`, no credentials, query or
 * fragment. Unset or malformed `API_PUBLIC_URL` → nothing is accepted (logged).
 */
export function isCardRedirect(raw: unknown): raw is string {
  const conf = process.env.API_PUBLIC_URL
  if (!conf || !URL.canParse(conf) || !/^https?:$/.test(new URL(conf).protocol)) {
    console.error('API_PUBLIC_URL is not set or malformed: refusing payment redirects')
    return false
  }
  if (typeof raw !== 'string' || !URL.canParse(raw)) return false
  const base = new URL(conf)
  const url = new URL(raw)
  const prefix = `${base.pathname.replace(/\/+$/, '')}/payments/card/`
  return (
    url.origin === base.origin &&
    !url.username &&
    !url.password &&
    !url.search &&
    !url.hash &&
    url.pathname.startsWith(prefix) &&
    /^[\w-]{1,64}\/redirect$/.test(url.pathname.slice(prefix.length))
  )
}

export const getOrder = async (id: string, token: string) => toOrderView(await api(`/fan/orders/${id}`, { token }))

export type CheckoutQuery = { event: number; price: string; qty: number }

/** W3 query `?event=&price=&qty=` (the W2 Continuer link), or null when any part is missing or malformed. */
export function parseCheckout(q: Record<string, string | string[] | undefined>): CheckoutQuery | null {
  const { event, price, qty } = q
  if (typeof event !== 'string' || !/^[1-9]\d{0,9}$/.test(event) || Number(event) > 2_147_483_647) return null
  if (typeof price !== 'string' || !SAFE_ID.test(price)) return null
  if (typeof qty !== 'string' || !/^[1-9]\d?$/.test(qty)) return null
  return { event: Number(event), price, qty: Number(qty) }
}

/** The W3 URL rebuilt from validated values only: safe to hand to `/login?next=`. */
export const checkoutPath = (c: CheckoutQuery) =>
  `/orders/new?${new URLSearchParams({ event: String(c.event), price: c.price, qty: String(c.qty) })}`

export const loginUrl = (next: string) => `/login?next=${encodeURIComponent(next)}`

/** W5 "Commandes en attente de paiement": an AWAITING_CASH order still payable, with its code. Nothing else. */
export type PendingCashOrder = {
  id: string
  event: { title: string; starts_at: string; timezone: string }
  /** As shown: `JMH 4F7K 29` (formatPayCode). */
  code: string
  amount_mad: number
  reserved_until: string
}

/**
 * The buyer's AWAITING_CASH orders with a PENDING code (`pay_code` not null) whose hold is still open by the shop
 * server's clock (not the fan's device), in the backend's order, from `GET /fan/orders` alone.
 */
export async function getPendingCash(token: string, now = Date.now()): Promise<PendingCashOrder[]> {
  return list(obj(await api('/fan/orders', { token })).orders)
    .filter(
      (o) =>
        o.status === 'AWAITING_CASH' &&
        typeof o.pay_code === 'string' &&
        typeof o.id === 'string' &&
        SAFE_ID.test(o.id) &&
        Number.isSafeInteger(o.total_mad) &&
        Date.parse(String(o.reserved_until)) > now,
    )
    .map((o) => {
      const e = obj(o.event)
      return {
        id: o.id as string,
        event: { title: String(e.title), starts_at: String(e.starts_at), timezone: String(e.timezone) },
        code: formatPayCode(o.pay_code as string),
        amount_mad: o.total_mad as number,
        reserved_until: String(o.reserved_until),
      }
    })
}
