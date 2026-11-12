// Browser side of the order flow: calls to the shop's own route handlers, the SALE_BUSY retry loop, order placement,
// order polling. No token here: the route handlers add it from the httpOnly cookie.

import { fmt, type Dict } from './i18n'

export type Result = { ok: boolean; status: number; data: Record<string, unknown> | null }

/**
 * JSON request to a shop route handler. Network failure → status 0 and code `NETWORK`; an abort of `signal`
 * rethrows (the caller stopped caring: "Annuler" or leaving the page).
 */
export async function postJson(path: string, body?: unknown, method = 'POST', signal?: AbortSignal): Promise<Result> {
  try {
    const res = await fetch(path, {
      method,
      headers: body === undefined ? undefined : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
    const data = (await res.json().catch(() => null)) as Record<string, unknown> | null
    return { ok: res.ok, status: res.status, data }
  } catch (e) {
    if (signal?.aborted) throw e
    return { ok: false, status: 0, data: { code: 'NETWORK' } }
  }
}

export const errorCode = (r: Result) => (typeof r.data?.code === 'string' ? r.data.code : 'generic')

/** Resolves after `ms`, or rejects with the abort reason as soon as `signal` aborts. */
export function sleep(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) return reject(signal.reason)
    const t = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => (clearTimeout(t), reject(signal.reason)), { once: true })
  })
}

/** Rejects with the abort reason if `signal` has aborted: checked after every await that can outlive "Annuler". */
export function throwIfAborted(signal: AbortSignal) {
  if (signal.aborted) throw signal.reason
}

/** Extra wait added to the server's `retry_after_s` so that a crowd doesn't retry in lockstep. */
export const JITTER_MS = 2000

/**
 * `send()` again while it answers 429 SALE_BUSY (04 §On-sale): waits `details.retry_after_s` (5–30 s) plus up to
 * JITTER_MS, calling `onWait(seconds left)` each second and `onWait(0)` before the retry. No queue position exists,
 * so none is reported. Stops (rejects) when `signal` aborts, including while a request is in flight.
 */
export async function withBusyRetry(send: () => Promise<Result>, onWait: (seconds: number) => void, signal: AbortSignal, random = Math.random) {
  for (;;) {
    const r = await send()
    throwIfAborted(signal) // an answer that arrives after "Annuler" is ignored
    if (r.status !== 429 || errorCode(r) !== 'SALE_BUSY') return r
    const details = (r.data?.details ?? {}) as Record<string, unknown>
    const s = Number.isInteger(details.retry_after_s) ? Math.min(30, Math.max(1, details.retry_after_s as number)) : 10
    for (let left = s; left > 0; left--) {
      onWait(left)
      await sleep(1000, signal)
    }
    await sleep(Math.floor(random() * JITTER_MS), signal)
    onWait(0)
  }
}

/** `/pay` methods, in the order W3 lists them (04 `payment_methods`). */
export const PAY_METHODS = ['CARD', 'WALLET', 'CASH_AGENCY'] as const
export type PayMethod = (typeof PAY_METHODS)[number]
/** The hosted-page methods: alternatives to a cash code, and "pay again". */
export type OnlineMethod = Exclude<PayMethod, 'CASH_AGENCY'>
export const isPayMethod = (v: unknown): v is PayMethod => PAY_METHODS.includes(v as PayMethod)

export type Placed =
  | { kind: 'redirect'; url: string }
  | { kind: 'cash'; orderId: string }
  | { kind: 'failed'; result: Result }
  | { kind: 'payFailed'; orderId: string; result: Result; reservedUntil?: string | null }
  | { kind: 'held'; orderId: string }

/**
 * W3: create the order (SALE_BUSY → wait and retry), unless `existingId` names one already created, then pay it with
 * `method`. `redirect` = go to the hosted page (card, wallet); `cash` = the code was issued, show the order page;
 * `payFailed` = the order exists (places held, until `reservedUntil` when created here) but /pay failed; `failed` = no order; `held` = the order exists and
 * `signal` aborted after it was created (never paid nor followed, but not lost: its page can show it). An abort while
 * the order request itself is in flight rejects.
 */
export async function placeOrder(
  order: unknown,
  method: PayMethod,
  onWait: (seconds: number) => void,
  signal: AbortSignal,
  { random = Math.random, existingId }: { random?: () => number; existingId?: string } = {},
): Promise<Placed> {
  let orderId = existingId
  let reservedUntil: string | null | undefined // the hold of an order created here
  if (!orderId) {
    const created = await withBusyRetry(() => postJson('/api/orders', order, 'POST', signal), onWait, signal, random)
    if (!created.ok) return { kind: 'failed', result: created }
    orderId = String(created.data!.id)
    reservedUntil = typeof created.data!.reserved_until === 'string' ? created.data!.reserved_until : null
  }
  if (signal.aborted) return { kind: 'held', orderId }
  let paid: Result
  try {
    paid = await postJson(`/api/orders/${encodeURIComponent(orderId)}/pay`, { method }, 'POST', signal)
  } catch (e) {
    if (signal.aborted) return { kind: 'held', orderId }
    throw e
  }
  if (signal.aborted) return { kind: 'held', orderId }
  if (!paid.ok) return { kind: 'payFailed', orderId, result: paid, ...(reservedUntil !== undefined && { reservedUntil }) }
  return method === 'CASH_AGENCY' ? { kind: 'cash', orderId } : { kind: 'redirect', url: String(paid.data!.redirect_url) }
}

export type OrderStatus = {
  status: string
  reserved_until: string | null
  ticket_count: number
  /** Present once the backend sends it (04: PENDING / SUCCEEDED / FAILED, null before any /pay). */
  payment_status?: string | null
  /** Lot 3: the SUCCEEDED attempt's method, else the latest attempt's; null before any /pay. */
  payment_method?: string | null
}

/** Order statuses after which nothing changes by itself (docs/06-glossary.md §Order statuses). */
export const FINAL = ['PAID', 'EXPIRED', 'CANCELLED', 'REFUNDED']

/** The order poll period on `/orders/[id]` (plan WS-3), and the ceiling of the back-off after failures. */
export const POLL_MS = 3000
/** The poll period while a cash code waits to be paid (DESIGN W4: every 10 s). */
export const CASH_POLL_MS = 10_000
export const POLL_MAX_MS = 30_000

/** One poll: the order, a reason to stop for good (401, 404), or null for a failure worth retrying (5xx, network). */
export type Poll<T> = { order: T } | { stop: 'UNAUTHORIZED' | 'NOT_FOUND' } | null

/** Resolves when the page is visible (now, or at the next visibilitychange); rejects if `signal` aborts first. */
export function whenVisible(signal: AbortSignal): Promise<void> {
  if (typeof document === 'undefined' || !document.hidden) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const done = () => {
      document.removeEventListener('visibilitychange', onChange)
      signal.removeEventListener('abort', onAbort)
    }
    const onChange = () => !document.hidden && (done(), resolve())
    const onAbort = () => (done(), reject(signal.reason))
    document.addEventListener('visibilitychange', onChange)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * Polls every `intervalMs` until the status is final (returns the order) or a poll says stop (returns the reason).
 * Failures double the wait up to POLL_MAX_MS; a good answer resets it. Waits while the page is hidden. Rejects when
 * `signal` aborts.
 */
export async function pollOrder<T extends OrderStatus>(
  fetchOrder: () => Promise<Poll<T>>,
  onUpdate: (o: T) => void,
  signal: AbortSignal,
  { intervalMs = POLL_MS, visible = whenVisible }: { intervalMs?: number; visible?: (s: AbortSignal) => Promise<void> } = {},
): Promise<T | 'UNAUTHORIZED' | 'NOT_FOUND'> {
  let wait = intervalMs
  for (;;) {
    await sleep(wait, signal)
    await visible(signal)
    const r = await fetchOrder()
    throwIfAborted(signal)
    if (!r) {
      wait = Math.min(POLL_MAX_MS, wait * 2)
      continue
    }
    if ('stop' in r) return r.stop
    wait = intervalMs
    onUpdate(r.order)
    if (FINAL.includes(r.order.status)) return r.order
  }
}

/**
 * Milliseconds since this call, on the monotonic page clock (performance.now): unaffected by the device's wall clock,
 * which can be minutes off the server's.
 */
export function pageClock(now: () => number = () => performance.now()) {
  const start = now()
  return () => now() - start
}

/** Seconds on `/orders/[id]` before "pay again" is offered when the backend doesn't tell the payment state. */
export const RETRY_PAY_AFTER_MS = 60_000

/**
 * Whether to offer paying online now (04 GET /fan/orders/{id}; same rule as apps/fan `payAgain`), while the hold still
 * runs on server time (`renderedAt`, the server's clock at SSR, plus `msOnPage` on the monotonic page clock; the
 * device wall clock is never used).
 * - AWAITING_CASH: card / wallet as alternatives to the code, unless the latest attempt is a CARD or WALLET one still
 *   PENDING (or one already SUCCEEDED): the code stays payable meanwhile.
 * - RESERVED ("pay again"): a callback can land after the fan is back from the gateway and a late success is
 *   honoured, so a second payment could double-charge: with `payment_status`, only when the last attempt FAILED or
 *   there was none; without it, only after RETRY_PAY_AFTER_MS on the page.
 */
export function canRetryPay(o: OrderStatus, msOnPage: number, renderedAt: number): boolean {
  if (o.status !== 'RESERVED' && o.status !== 'AWAITING_CASH') return false
  // ponytail: the page clock starts at mount, a second or so after the SSR, so this can run that long past the hold;
  // the backend then answers ORDER_EXPIRED.
  if (!o.reserved_until || !(Date.parse(o.reserved_until) > renderedAt + msOnPage)) return false
  if (o.status === 'AWAITING_CASH') {
    const online = o.payment_method === 'CARD' || o.payment_method === 'WALLET'
    return !(online && o.payment_status === 'PENDING') && o.payment_status !== 'SUCCEEDED'
  }
  if ('payment_status' in o) return o.payment_status === 'FAILED' || o.payment_status === null
  return msOnPage >= RETRY_PAY_AFTER_MS
}

/**
 * Copy for an error answer: reason-specific for EVENT_NOT_ON_SALE, METHOD_NOT_AVAILABLE and split pay's
 * NO_BUYER_PLACE (a 422 on `items`, no row to mark), limit numbers for PER_ACCOUNT_LIMIT.
 */
export function errorText(errors: Dict['errors'], r: Result): string {
  const code = errorCode(r)
  const details = (r.data?.details ?? {}) as Record<string, unknown>
  if (code === 'EVENT_NOT_ON_SALE' && (details.reason === 'NOT_STARTED' || details.reason === 'CLOSED')) return errors[details.reason]
  if (code === 'METHOD_NOT_AVAILABLE' && (details.reason === 'TOO_LATE' || details.reason === 'CASH_CAP' || details.reason === 'NOT_CONFIGURED')) {
    return errors[`METHOD_${details.reason}`]
  }
  if (code === 'VALIDATION_FAILED' && details.reason === 'NO_BUYER_PLACE') return errors.NO_BUYER_PLACE
  if (code === 'PER_ACCOUNT_LIMIT') return fmt(errors.PER_ACCOUNT_LIMIT, { limit: String(details.limit ?? ''), held: String(details.held ?? '') })
  return Object.hasOwn(errors, code) ? (errors as Record<string, string>)[code]! : errors.generic
}

/** Time left on a hold, as shown next to its deadline: `7 min 59 s`, or `1 h 52 min` from an hour on. */
export function leftText(c: Pick<Dict['orderPage'], 'left' | 'leftHours'>, seconds: number) {
  const s = Math.max(0, seconds)
  return s >= 3600 ? fmt(c.leftHours, { h: Math.floor(s / 3600), m: Math.floor((s % 3600) / 60) }) : fmt(c.left, { m: Math.floor(s / 60), s: s % 60 })
}
