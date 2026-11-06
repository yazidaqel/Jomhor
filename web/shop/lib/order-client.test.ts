import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDictionary } from './i18n'
import {
  canRetryPay,
  errorText,
  JITTER_MS,
  placeOrder,
  POLL_MAX_MS,
  POLL_MS,
  pageClock,
  pollOrder,
  postJson,
  RETRY_PAY_AFTER_MS,
  withBusyRetry,
  type OrderStatus,
  type Poll,
  type Result,
} from './order-client'

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

const busy = (s: number): Result => ({ ok: false, status: 429, data: { code: 'SALE_BUSY', message: '', details: { retry_after_s: s } } })
const created: Result = { ok: true, status: 201, data: { id: 'o1', status: 'RESERVED' } }

it('SALE_BUSY: counts down retry_after_s, adds jitter, then retries the same request until it passes', async () => {
  const send = vi.fn<() => Promise<Result>>().mockResolvedValueOnce(busy(5)).mockResolvedValueOnce(busy(6)).mockResolvedValueOnce(created)
  const waits: number[] = []
  const done = withBusyRetry(send, (s) => waits.push(s), new AbortController().signal, () => 0.5)

  await vi.advanceTimersByTimeAsync(5000 + JITTER_MS / 2 - 1)
  expect(send).toHaveBeenCalledTimes(1) // not before retry_after_s + jitter
  await vi.advanceTimersByTimeAsync(1)
  expect(send).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(6000 + JITTER_MS / 2)

  expect(await done).toBe(created)
  expect(send).toHaveBeenCalledTimes(3)
  expect(waits).toEqual([5, 4, 3, 2, 1, 0, 6, 5, 4, 3, 2, 1, 0]) // seconds only: no queue position
})

it('jitter stays within [retry_after_s, retry_after_s + JITTER_MS)', async () => {
  for (const r of [0, 0.999]) {
    const send = vi.fn<() => Promise<Result>>().mockResolvedValueOnce(busy(5)).mockResolvedValueOnce(created)
    const done = withBusyRetry(send, () => {}, new AbortController().signal, () => r)
    await vi.advanceTimersByTimeAsync(5000 + Math.floor(r * JITTER_MS) + 1)
    expect(send).toHaveBeenCalledTimes(2)
    await done
  }
})

it('other answers come back at once; Annuler (abort) stops the retries', async () => {
  const conflict: Result = { ok: false, status: 409, data: { code: 'SOLD_OUT' } }
  expect(await withBusyRetry(async () => conflict, () => {}, new AbortController().signal)).toBe(conflict)

  const send = vi.fn<() => Promise<Result>>().mockResolvedValue(busy(10))
  const abort = new AbortController()
  const done = withBusyRetry(send, () => {}, abort.signal).catch((e: unknown) => e)
  await vi.advanceTimersByTimeAsync(3000)
  abort.abort()
  expect(await done).toBeInstanceOf(DOMException)
  await vi.advanceTimersByTimeAsync(60_000)
  expect(send).toHaveBeenCalledTimes(1)
})

const order = (status: string, over: Partial<OrderStatus> = {}): OrderStatus => ({
  status,
  reserved_until: '2026-02-01T12:08:00Z',
  ticket_count: status === 'PAID' ? 2 : 0,
  ...over,
})
const ok = (status: string): Poll<OrderStatus> => ({ order: order(status) })

it.each(['PAID', 'EXPIRED'])('polling every 3 s stops on %s', async (final) => {
  const fetchOrder = vi
    .fn<() => Promise<Poll<OrderStatus>>>()
    .mockResolvedValueOnce(ok('RESERVED'))
    .mockResolvedValueOnce(ok(final))
    .mockResolvedValue(ok('RESERVED'))
  const seen: string[] = []
  const done = pollOrder(fetchOrder, (o) => seen.push(o.status), new AbortController().signal)

  await vi.advanceTimersByTimeAsync(POLL_MS - 1)
  expect(fetchOrder).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(1 + POLL_MS)
  expect(await done).toMatchObject({ status: final })
  await vi.advanceTimersByTimeAsync(10 * POLL_MS)

  expect(fetchOrder).toHaveBeenCalledTimes(2)
  expect(seen).toEqual(['RESERVED', final])
})

it.each(['UNAUTHORIZED', 'NOT_FOUND'] as const)('polling stops for good on %s', async (stop) => {
  const fetchOrder = vi.fn<() => Promise<Poll<OrderStatus>>>().mockResolvedValueOnce({ stop }).mockResolvedValue(ok('RESERVED'))
  const done = pollOrder(fetchOrder, () => {}, new AbortController().signal)
  await vi.advanceTimersByTimeAsync(POLL_MS)
  expect(await done).toBe(stop)
  await vi.advanceTimersByTimeAsync(10 * POLL_MS)
  expect(fetchOrder).toHaveBeenCalledTimes(1)
})

it('failures (5xx, network) back off 3 → 6 → 12 → 24 → 30 s, a good answer resets to 3 s', async () => {
  const fetchOrder = vi.fn<() => Promise<Poll<OrderStatus>>>().mockResolvedValue(null)
  const at: number[] = []
  fetchOrder.mockImplementation(async () => (at.push(Date.now()), at.length === 6 ? ok('RESERVED') : null))
  const abort = new AbortController()
  const start = Date.now()
  pollOrder(fetchOrder, () => {}, abort.signal).catch(() => {})
  await vi.advanceTimersByTimeAsync(3000 + 6000 + 12_000 + 24_000 + POLL_MAX_MS + 30_000 + POLL_MS)
  abort.abort()
  expect(at.map((t) => t - start)).toEqual([3000, 9000, 21_000, 45_000, 75_000, 105_000, 108_000])
})

it('a hidden page is not polled until it is visible again', async () => {
  let show = () => {}
  const visible = vi.fn(() => new Promise<void>((r) => (show = r)))
  const fetchOrder = vi.fn<() => Promise<Poll<OrderStatus>>>().mockResolvedValue(ok('PAID'))
  const done = pollOrder(fetchOrder, () => {}, new AbortController().signal, { visible })
  await vi.advanceTimersByTimeAsync(10 * POLL_MS)
  expect(visible).toHaveBeenCalledTimes(1)
  expect(fetchOrder).not.toHaveBeenCalled()
  show()
  expect(await done).toMatchObject({ status: 'PAID' })
})

it('polling stops when aborted (page left)', async () => {
  const fetchOrder = vi.fn<() => Promise<Poll<OrderStatus>>>().mockResolvedValue(ok('RESERVED'))
  const abort = new AbortController()
  const done = pollOrder(fetchOrder, () => {}, abort.signal).catch((e: unknown) => e)
  await vi.advanceTimersByTimeAsync(POLL_MS)
  abort.abort()
  await done
  await vi.advanceTimersByTimeAsync(10 * POLL_MS)
  expect(fetchOrder).toHaveBeenCalledTimes(1)
})

it.each<[string, OrderStatus, number, boolean]>([
  ['no payment_status field, just back from the gateway', order('RESERVED'), 5_000, false],
  ['no payment_status field, after 60 s on the page', order('RESERVED'), RETRY_PAY_AFTER_MS, true],
  ['payment_status FAILED', order('RESERVED', { payment_status: 'FAILED' }), 0, true],
  ['payment_status null (no /pay yet)', order('RESERVED', { payment_status: null }), 0, true],
  ['payment_status PENDING, even after minutes', order('RESERVED', { payment_status: 'PENDING' }), 600_000, false],
  ['payment_status SUCCEEDED (callback in, order not yet PAID)', order('RESERVED', { payment_status: 'SUCCEEDED' }), 600_000, false],
  ['an order that is not RESERVED', order('EXPIRED', { payment_status: 'FAILED' }), 600_000, false],
])('canRetryPay: %s → %s', (_, o, ms, offered) => {
  expect(canRetryPay(o, ms)).toBe(offered)
})

it('the "pay again" wait runs on the page clock: a device wall clock 2 min ahead of the server changes nothing', () => {
  vi.useFakeTimers({ toFake: ['Date', 'performance', 'setTimeout'] })
  const serverRenderedAt = Date.parse('2026-02-01T12:00:00Z')
  vi.setSystemTime(serverRenderedAt + 120_000) // the phone's clock runs 2 min fast
  const elapsed = pageClock()
  const reserved = order('RESERVED')
  expect(canRetryPay(reserved, elapsed())).toBe(false)
  expect(canRetryPay(reserved, Date.now() - serverRenderedAt)).toBe(true) // what comparing wall clocks would have done
  vi.advanceTimersByTime(RETRY_PAY_AFTER_MS - 1000)
  expect(canRetryPay(reserved, elapsed())).toBe(false)
  vi.advanceTimersByTime(1000)
  expect(canRetryPay(reserved, elapsed())).toBe(true)
})

describe('placeOrder', () => {
  const fetchMock = vi.fn<typeof fetch>()
  beforeEach(() => vi.stubGlobal('fetch', fetchMock))
  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })
  const res = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
  const ORDER = { event_id: 12, items: [] }

  it('creates, pays and returns the gateway redirect; the signal is passed to fetch', async () => {
    fetchMock
      .mockResolvedValueOnce(res(201, { id: 'o1', status: 'RESERVED' }))
      .mockResolvedValueOnce(res(200, { redirect_url: 'https://api.test/v1/payments/card/p1/redirect' }))
    const abort = new AbortController()
    expect(await placeOrder(ORDER, () => {}, abort.signal)).toEqual({ kind: 'redirect', url: 'https://api.test/v1/payments/card/p1/redirect' })
    expect(fetchMock.mock.calls.map(([u]) => u)).toEqual(['/api/orders', '/api/orders/o1/pay'])
    expect(fetchMock.mock.calls[0]![1]!.signal).toBe(abort.signal)
  })

  it('Annuler while the order request is in flight, then a 201 arrives: no /pay, no redirect', async () => {
    let answer: (r: Response) => void = () => {}
    fetchMock.mockImplementationOnce(() => new Promise<Response>((r) => (answer = r))) // a fetch that ignores abort
    const abort = new AbortController()
    const placed = placeOrder(ORDER, () => {}, abort.signal).then(
      (p) => p,
      (e: unknown) => ({ aborted: e }),
    )
    abort.abort()
    answer(res(201, { id: 'o1', status: 'RESERVED' }))
    expect(await placed).toHaveProperty('aborted')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('Annuler while /pay is in flight: no redirect, but the held order is handed back (its page shows it)', async () => {
    let answer: (r: Response) => void = () => {}
    fetchMock.mockResolvedValueOnce(res(201, { id: 'o1', status: 'RESERVED' })).mockImplementationOnce(() => new Promise<Response>((r) => (answer = r)))
    const abort = new AbortController()
    const placed = placeOrder(ORDER, () => {}, abort.signal)
    await vi.advanceTimersByTimeAsync(0)
    abort.abort()
    answer(res(200, { redirect_url: 'https://api.test/v1/payments/card/p1/redirect' }))
    expect(await placed).toEqual({ kind: 'held', orderId: 'o1' })
  })

  it('Annuler between the 201 and /pay: /pay is not called, the held order is handed back', async () => {
    const abort = new AbortController()
    fetchMock.mockImplementationOnce(async () => (abort.abort(), res(201, { id: 'o1', status: 'RESERVED' })))
    // The abort lands as the 201 arrives: withBusyRetry sees it first and rejects (order request still "in flight").
    await expect(placeOrder(ORDER, () => {}, abort.signal)).rejects.toBeDefined()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('a pay request rejected by an abort hands back the held order', async () => {
    const abort = new AbortController()
    fetchMock
      .mockResolvedValueOnce(res(201, { id: 'o1', status: 'RESERVED' }))
      .mockImplementationOnce(async () => {
        abort.abort()
        throw abort.signal.reason
      })
    expect(await placeOrder(ORDER, () => {}, abort.signal)).toEqual({ kind: 'held', orderId: 'o1' })
  })

  it('a /pay failure keeps the order id (its page offers the retry)', async () => {
    fetchMock.mockResolvedValueOnce(res(201, { id: 'o1', status: 'RESERVED' })).mockResolvedValueOnce(res(502, { code: 'INTERNAL_ERROR' }))
    expect(await placeOrder(ORDER, () => {}, new AbortController().signal)).toEqual({ kind: 'payFailed', orderId: 'o1' })
  })

  it('an order refusal comes back as failed, without /pay', async () => {
    fetchMock.mockResolvedValueOnce(res(409, { code: 'SOLD_OUT', message: '', details: {} }))
    expect(await placeOrder(ORDER, () => {}, new AbortController().signal)).toMatchObject({ kind: 'failed', result: { status: 409 } })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('postJson: a network failure is NETWORK, an abort rethrows', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('fetch failed'))
    expect(await postJson('/api/x', {})).toEqual({ ok: false, status: 0, data: { code: 'NETWORK' } })
    const abort = new AbortController()
    abort.abort()
    fetchMock.mockRejectedValueOnce(abort.signal.reason)
    await expect(postJson('/api/x', {}, 'POST', abort.signal)).rejects.toBe(abort.signal.reason)
  })
})

const errors = getDictionary('fr').errors
const fail = (code: string, details: Record<string, unknown> = {}): Result => ({ ok: false, status: 409, data: { code, message: '', details } })

it.each<[Result, string]>([
  [fail('EVENT_NOT_ON_SALE', { reason: 'NOT_STARTED' }), errors.NOT_STARTED],
  [fail('EVENT_NOT_ON_SALE', { reason: 'CLOSED' }), errors.CLOSED],
  [fail('EVENT_NOT_ON_SALE', { reason: 'NOT_ON_SALE' }), errors.EVENT_NOT_ON_SALE],
  [fail('SOLD_OUT', { price_category_id: 'pc' }), errors.SOLD_OUT],
  [fail('PER_ACCOUNT_LIMIT', { limit: 4, held: 3 }), 'Tu as atteint la limite de 4 billets par compte pour cet événement (déjà 3 réservés ou achetés).'],
  [fail('ORDER_EXPIRED'), errors.ORDER_EXPIRED],
  [{ ok: false, status: 0, data: { code: 'NETWORK' } }, errors.NETWORK],
  [fail('SOMETHING_NEW'), errors.generic],
  [fail('constructor'), errors.generic], // never an Object.prototype member
  [{ ok: false, status: 500, data: null }, errors.generic],
])('errorText %#', (r, text) => {
  expect(errorText(errors, r)).toBe(text)
})
