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

export type Placed =
  | { kind: 'redirect'; url: string }
  | { kind: 'failed'; result: Result }
  | { kind: 'payFailed'; orderId: string }
  | { kind: 'held'; orderId: string }

/**
 * W3: create the order (SALE_BUSY → wait and retry), then start the card payment. `redirect` = go to the gateway;
 * `payFailed` = the order exists (places held 8 min) but /pay failed; `failed` = no order; `held` = the order exists
 * and `signal` aborted after it was created (never paid nor followed, but not lost: its page can show it). An abort
 * while the order request itself is in flight rejects.
 */
export async function placeOrder(order: unknown, onWait: (seconds: number) => void, signal: AbortSignal, random = Math.random): Promise<Placed> {
  const created = await withBusyRetry(() => postJson('/api/orders', order, 'POST', signal), onWait, signal, random)
  if (!created.ok) return { kind: 'failed', result: created }
  const orderId = String(created.data!.id)
  if (signal.aborted) return { kind: 'held', orderId }
  let paid: Result
  try {
    paid = await postJson(`/api/orders/${encodeURIComponent(orderId)}/pay`, undefined, 'POST', signal)
  } catch (e) {
    if (signal.aborted) return { kind: 'held', orderId }
    throw e
  }
  if (signal.aborted) return { kind: 'held', orderId }
  return paid.ok ? { kind: 'redirect', url: String(paid.data!.redirect_url) } : { kind: 'payFailed', orderId }
}

export type OrderStatus = {
  status: string
  reserved_until: string | null
  ticket_count: number
  /** Present once the backend sends it (04: PENDING / SUCCEEDED / FAILED, null before any /pay). */
  payment_status?: string | null
}

/** Order statuses after which nothing changes by itself (docs/06-glossary.md §Order statuses). */
export const FINAL = ['PAID', 'EXPIRED', 'CANCELLED', 'REFUNDED']

/** The order poll period on `/orders/[id]` (plan WS-3), and the ceiling of the back-off after failures. */
export const POLL_MS = 3000
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
 * Whether to offer "pay again" on a RESERVED order. A callback can land after the fan is back from the gateway and a
 * late success is honoured, so a second payment could double-charge: with `payment_status` (04), only when the last
 * attempt FAILED or there was none; without it, only after RETRY_PAY_AFTER_MS on the page.
 */
export function canRetryPay(o: OrderStatus, msOnPage: number): boolean {
  if (o.status !== 'RESERVED') return false
  if ('payment_status' in o) return o.payment_status === 'FAILED' || o.payment_status === null
  return msOnPage >= RETRY_PAY_AFTER_MS
}

/** FR copy for an error answer: reason-specific for EVENT_NOT_ON_SALE, limit numbers for PER_ACCOUNT_LIMIT. */
export function errorText(errors: Dict['errors'], r: Result): string {
  const code = errorCode(r)
  const details = (r.data?.details ?? {}) as Record<string, unknown>
  if (code === 'EVENT_NOT_ON_SALE' && (details.reason === 'NOT_STARTED' || details.reason === 'CLOSED')) return errors[details.reason]
  if (code === 'PER_ACCOUNT_LIMIT') return fmt(errors.PER_ACCOUNT_LIMIT, { limit: String(details.limit ?? ''), held: String(details.held ?? '') })
  return Object.hasOwn(errors, code) ? (errors as Record<string, string>)[code]! : errors.generic
}
