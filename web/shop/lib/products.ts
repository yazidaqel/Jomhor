import 'server-only'
import { api, ApiError } from './api'
import { UUID } from './clubs'
import { LIST_REVALIDATE_S, methodsOf, toOrganizer, type Organizer } from './events'
import type { PayMethod } from './order-client'

// Lot 5 step 3: products (season passes, paid membership) and the fan's passes. `/fan/products*` is public (no
// Authorization header); `/fan/passes` takes the session. Rows are mapped field by field, like events: nothing else the
// backend sends reaches HTML or client props.

export type ProductKind = 'PASS' | 'MEMBERSHIP'
export type PassInfo = {
  series: { id: string; name: string; starts_on: string; ends_on: string }
  zone: { id: number; code: string; name: string }
  venue: { name: string; city: string | null; timezone: string }
  events: { id: number; title: string; event_type: string; starts_at: string }[]
}
export type Product = {
  id: string
  organizer_id: string
  /** The selling club (crest + name); `slug` null when unlisted (no W9 URL). */
  organizer: Organizer
  /** The time zone of the product's dates: the pass zone's venue, `Africa/Casablanca` for MEMBERSHIP. */
  timezone: string
  kind: ProductKind
  name: string
  /** Integer centimes. */
  price_mad: number
  sales_close_at: string
  /** PASS: passes left; null for MEMBERSHIP. */
  remaining: number | null
  sold_out: boolean
  /** MEMBERSHIP: the `paid_until` it grants. */
  valid_until: string | null
  pass: PassInfo | null
  /** How it can be paid: in full first, then (3b, PASS) the installment plan when offered. */
  plans: Plan[]
  payment_methods: PayMethod[]
  cash_until: string | null
}

/** 04 `plans[]`: `amounts_mad` (integer centimes, Σ = price) in due order; `interval_days` null for the in-full plan. */
export type Plan = { installments: number; amounts_mad: number[]; interval_days: number | null }

type Raw = Record<string, unknown>
const obj = (v: unknown): Raw => (v && typeof v === 'object' ? (v as Raw) : {})
const str = (v: unknown) => (typeof v === 'string' ? v : null)
const int = (v: unknown) => (Number.isSafeInteger(v) ? (v as number) : null)

function toPass(v: unknown): PassInfo | null {
  if (!v || typeof v !== 'object') return null
  const p = v as Raw
  const s = obj(p.series)
  const z = obj(p.zone)
  const venue = obj(p.venue)
  return {
    series: { id: String(s.id), name: String(s.name ?? ''), starts_on: String(s.starts_on), ends_on: String(s.ends_on) },
    zone: { id: int(z.id) ?? 0, code: String(z.code ?? ''), name: String(z.name ?? '') },
    venue: { name: String(venue.name ?? ''), city: str(venue.city), timezone: str(venue.timezone) ?? 'Africa/Casablanca' },
    events: (Array.isArray(p.events) ? p.events.map(obj) : []).map((e) => ({
      id: int(e.id) ?? 0,
      title: String(e.title ?? ''),
      event_type: String(e.event_type ?? ''),
      starts_at: String(e.starts_at),
    })),
  }
}

/**
 * 04 plans: always in full first (built from `price_mad`), then each N-payment plan (2–10) the backend offers, once per
 * N. One with amounts that aren't N positive integers, or without a non-negative integer `interval_days`, is dropped (0:
 * an interval under a day, whole days rounding down, as a demo clock gives).
 */
function toPlans(v: unknown, price: number): Plan[] {
  const plans: Plan[] = [{ installments: 1, amounts_mad: [price], interval_days: null }]
  for (const p of Array.isArray(v) ? v.map(obj) : []) {
    const n = int(p.installments)
    const days = int(p.interval_days)
    const amounts = Array.isArray(p.amounts_mad) ? p.amounts_mad.map(int) : []
    if (!n || n < 2 || n > 10 || days === null || days < 0 || amounts.length !== n || amounts.some((a) => a === null || a <= 0)) continue
    if (!plans.some((q) => q.installments === n)) plans.push({ installments: n, amounts_mad: amounts as number[], interval_days: days })
  }
  return plans
}

/** A `/fan/products*` row, or null when it isn't one the shop can sell (unknown kind, no id, a non-integer price). */
export function toProduct(v: unknown): Product | null {
  const r = obj(v)
  const price = int(r.price_mad)
  if (typeof r.id !== 'string' || (r.kind !== 'PASS' && r.kind !== 'MEMBERSHIP') || price === null) return null
  const pass = r.kind === 'PASS' ? toPass(r.pass) : null
  if (r.kind === 'PASS' && !pass) return null
  return {
    id: r.id,
    organizer_id: String(r.organizer_id),
    organizer: toOrganizer(r.organizer),
    timezone: str(r.timezone) ?? 'Africa/Casablanca',
    kind: r.kind,
    name: String(r.name ?? ''),
    price_mad: price,
    sales_close_at: String(r.sales_close_at),
    remaining: int(r.remaining),
    sold_out: r.sold_out === true,
    valid_until: str(r.valid_until),
    pass,
    plans: toPlans(r.plans, price),
    payment_methods: methodsOf(r.payment_methods),
    cash_until: str(r.cash_until),
  }
}

/** W9: the organizer's products on sale, by kind then name (the backend's order). Cached 60 s. */
export async function getProducts(organizerId: string): Promise<Product[]> {
  const rows = await api<unknown[]>(`/fan/products?organizer=${encodeURIComponent(organizerId)}`, { next: { revalidate: LIST_REVALIDATE_S } })
  return (Array.isArray(rows) ? rows : []).map(toProduct).filter((p) => p !== null)
}

/**
 * One product on sale (W3; W4 for its `cash_until`), or null: unknown or off sale (404). Uncached: Next's fetch cache
 * keeps only 200 answers, so a cached read would serve the product as on sale forever once it goes off sale.
 */
export async function getProduct(id: string): Promise<Product | null> {
  try {
    return toProduct(await api(`/fan/products/${id}`))
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}

/**
 * The `POST /fan/product-orders` body rebuilt from what the browser sent: `product_id` (UUID), `holder_name` (string;
 * the backend applies §Holder names, 422 on `holder_name`) and, 3b, `installments` when sent (an integer 1–10; the
 * backend checks it is one of the product's plans, 422 `NOT_OFFERED`), channel WEB. Null for any other shape.
 */
export function productOrderPayload(body: Raw) {
  if (typeof body.product_id !== 'string' || !UUID.test(body.product_id) || typeof body.holder_name !== 'string') return null
  const n = body.installments
  if (n !== undefined && !(Number.isSafeInteger(n) && (n as number) >= 1 && (n as number) <= 10)) return null
  return { product_id: body.product_id, channel: 'WEB' as const, holder_name: body.holder_name, ...(n !== undefined && { installments: n as number }) }
}

/** 3b: one row of a pass's schedule (integer centimes; `paid_at` null while unpaid). */
export type Installment = { seq: number; amount_mad: number; due_at: string; paid_at: string | null }

/** W5 "Mes abonnements" row (`GET /fan/passes`). `order_id`: the purchase order to resume while PENDING, else null. */
export type PassView = {
  id: string
  status: string
  product: { id: string; name: string }
  organizer: { slug: string | null; name: string; crest_url: string | null }
  series: { name: string; starts_on: string; ends_on: string }
  zone: { name: string }
  /** The zone venue's time zone: due dates are shown in it. */
  timezone: string
  holder_label: string | null
  order_id: string | null
  /** 3b: set while SUSPENDED. */
  suspended_at: string | null
  /** 3b: the schedule by `seq` (one row for a pass paid in full; empty from a pre-3b backend). */
  installments: Installment[]
  /** 3b: the earliest unpaid installment, null when paid up; `overdue` = its `due_at` has passed. */
  next_due: { seq: number; amount_mad: number; due_at: string; overdue: boolean } | null
}

export function toPasses(raw: unknown): PassView[] {
  return (Array.isArray(obj(raw).passes) ? (obj(raw).passes as unknown[]) : []).map((v) => {
    const p = obj(v)
    const s = obj(p.series)
    const order = str(p.order_id)
    return {
      id: String(p.id),
      status: String(p.status),
      product: { id: String(obj(p.product).id), name: String(obj(p.product).name ?? '') },
      organizer: toOrganizer(p.organizer),
      series: { name: String(s.name ?? ''), starts_on: String(s.starts_on), ends_on: String(s.ends_on) },
      zone: { name: String(obj(p.zone).name ?? '') },
      timezone: str(p.timezone) ?? 'Africa/Casablanca',
      holder_label: str(p.holder_label),
      order_id: p.status === 'PENDING' && order && UUID.test(order) ? order : null,
      suspended_at: str(p.suspended_at),
      installments: (Array.isArray(p.installments) ? p.installments.map(obj) : []).flatMap((i) => {
        const seq = int(i.seq)
        const amount = int(i.amount_mad)
        return seq !== null && amount !== null && typeof i.due_at === 'string' ? [{ seq, amount_mad: amount, due_at: i.due_at, paid_at: str(i.paid_at) }] : []
      }),
      next_due: toNextDue(p.next_due),
    }
  })
}

function toNextDue(v: unknown): PassView['next_due'] {
  const n = obj(v)
  const seq = int(n.seq)
  const amount = int(n.amount_mad)
  return seq !== null && amount !== null && typeof n.due_at === 'string' ? { seq, amount_mad: amount, due_at: n.due_at, overdue: n.overdue === true } : null
}

/** 3b: "Payer l'échéance" is offered on an ACTIVE or SUSPENDED pass with an unpaid installment (paying ahead is allowed). */
export const payable = (p: Pick<PassView, 'status' | 'next_due'>) => p.next_due !== null && (p.status === 'ACTIVE' || p.status === 'SUSPENDED')

/** The caller's passes that aren't CANCELLED, newest first. */
export const getPasses = async (token: string) => toPasses(await api('/fan/passes', { token }))

/** `/orders/new?product={id}` (W9 "Acheter"), from a validated id only: safe to hand to `/login?next=`. */
export const productCheckoutPath = (id: string) => `/orders/new?${new URLSearchParams({ product: id })}`

/** W9 of a product's club, or null when the club is unlisted (no shop URL). */
export const passesPath = (o: Pick<Organizer, 'slug'>) => (o.slug ? `/${o.slug}/passes` : null)

/** Buyable now: not sold out and before `sales_close_at` (else the backend answers PRODUCT_NOT_ON_SALE). */
export const buyable = (p: Pick<Product, 'sold_out' | 'sales_close_at'>, now: number) => !p.sold_out && now < Date.parse(p.sales_close_at)
