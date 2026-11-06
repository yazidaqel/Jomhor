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
  total_mad: number
  reserved_until: string | null
  paid_at: string | null
  items: { price_category_id: string; name: string; qty: number; unit_price_mad: number; labels: string[] }[]
  /** Tickets the caller holds (a friend's ticket is never listed to the buyer). */
  ticket_count: number
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
  }
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
