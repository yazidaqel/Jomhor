import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { GET as getMe, PATCH as patchMe } from '../me/route'
import { POST as pay } from './[id]/pay/route'
import { GET as getOrder } from './[id]/route'
import { formatPayCode, orderNo } from '@/lib/orders'
import { GET as listOrders, POST as createOrder } from './route'

const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsImF1ZCI6ImZhbiJ9.c2lnbmF0dXJl'
const ORDER_ID = '0190f3a2-7c1e-7b55-9a1e-3f1f6b2c9d10'

const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => {
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubEnv('SHOP_ORIGIN', 'http://shop.test')
  vi.stubEnv('API_PUBLIC_URL', 'https://api.test/v1')
  vi.stubGlobal('fetch', fetchMock)
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  fetchMock.mockReset()
})

function req(path: string, { method = 'POST', body, headers = {} }: { method?: string; body?: unknown; headers?: Record<string, string> } = {}) {
  return new Request(`http://shop.test${path}`, {
    method,
    headers: { origin: 'http://shop.test', cookie: `a=1; __Host-jomhor_session=${TOKEN}`, 'content-type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}
const params = (id: string) => ({ params: Promise.resolve({ id }) })
const upstream = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const sent = (n = 0) => {
  const [url, init] = fetchMock.mock.calls[n]!
  return { url, headers: new Headers(init!.headers), body: init!.body ? JSON.parse(init!.body as string) : undefined, init: init! }
}

const VALID = { event_id: 12, items: [{ price_category_id: 'pc-virage', qty: 1, holders: [{ name: 'Yazid Aqel' }] }] }

const ORDER = {
  id: ORDER_ID,
  status: 'RESERVED',
  reserved_until: '2026-02-01T12:08:00Z',
  total_mad: 10000,
  items: [{ price_category_id: 'pc-virage', zone_id: 3, name: 'Virage', qty: 2, unit_price_mad: 5000, holders: [{ label: 'Yazid A.' }, { label: 'Salma B.' }] }],
}

it('create: cookie token → Bearer, holders nested per item, only whitelisted fields, channel WEB; the token never comes back', async () => {
  fetchMock.mockResolvedValue(upstream(201, { ...ORDER, token: 'SECRET', extra: 'x' }))

  const res = await createOrder(
    req('/api/orders', {
      body: {
        event_id: 12,
        channel: 'POS', // the browser doesn't choose the channel
        user_id: 'someone-else',
        items: [
          {
            price_category_id: 'pc-virage',
            qty: 2,
            unit_price_mad: 1, // ignored: prices come from the server
            holders: [{ name: 'Yazid Aqel', phone: '', is_buyer: true }, { name: 'Salma Benali', phone: '+212612345678' }],
          },
        ],
      },
    }),
  )

  expect(res.status).toBe(201)
  const text = await res.text()
  expect(text).not.toContain(TOKEN)
  expect(text).not.toContain('SECRET')
  expect(JSON.parse(text)).toEqual({ id: ORDER_ID, status: 'RESERVED', reserved_until: '2026-02-01T12:08:00Z', total_mad: 10000 })
  expect(res.headers.get('set-cookie')).toBeNull()
  const s = sent()
  expect(s.url).toBe('http://backend.test/v1/fan/orders')
  expect(s.headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(s.body).toEqual({
    event_id: 12,
    channel: 'WEB',
    items: [{ price_category_id: 'pc-virage', qty: 2, holders: [{ name: 'Yazid Aqel' }, { name: 'Salma Benali', phone: '+212612345678' }] }],
  })
})

it.each([
  ['429 SALE_BUSY with retry_after_s', 429, { code: 'SALE_BUSY', message: '', details: { retry_after_s: 12 } }],
  ['409 PER_ACCOUNT_LIMIT', 409, { code: 'PER_ACCOUNT_LIMIT', message: '', details: { limit: 4, held: 3 } }],
  ['422 with field paths', 422, { code: 'VALIDATION_FAILED', message: '', details: { fields: { 'items[0].holders[1].name': 'invalid' } } }],
  ['401 (session revoked)', 401, { code: 'UNAUTHORIZED', message: '', details: {} }],
])('create passes %s through in the 04 shape', async (_, status, body) => {
  fetchMock.mockResolvedValue(upstream(status, body))
  const res = await createOrder(req('/api/orders', { body: VALID }))
  expect(res.status).toBe(status)
  expect(await res.json()).toEqual(body)
})

const item = (over: Record<string, unknown>) => ({ event_id: 12, items: [{ price_category_id: 'pc-virage', qty: 1, holders: [{ name: 'A B' }], ...over }] })
it.each<[string, unknown]>([
  ['items not a list', { event_id: 12, items: 'all' }],
  ['no items', { event_id: 12, items: [] }],
  ['event_id as a string', { ...VALID, event_id: '12' }],
  ['event_id as a float', { ...VALID, event_id: 12.5 }],
  ['qty as a string', item({ qty: '2' })],
  ['qty 0', item({ qty: 0 })],
  ['a price id with a slash', item({ price_category_id: '../x' })],
  ['holders not a list', item({ holders: { name: 'A B' } })],
  ['a name that is not a string', item({ holders: [{ name: 42 }] })],
  ['a phone that is not a string', item({ holders: [{ name: 'A B', phone: 612345678 }] })],
])('create refuses %s (422) and calls nothing', async (_, body) => {
  const res = await createOrder(req('/api/orders', { body }))
  expect(res.status).toBe(422)
  expect(fetchMock).not.toHaveBeenCalled()
})

it.each(['CARD', 'WALLET'])('pay %s: Bearer from the cookie, method forwarded, answers only redirect_url', async (method) => {
  fetchMock.mockResolvedValue(upstream(200, { payment_id: 'p1', redirect_url: 'https://api.test/v1/payments/card/p1/redirect' }))

  const res = await pay(req(`/api/orders/${ORDER_ID}/pay`, { body: { method, extra: 'x' } }), params(ORDER_ID))

  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ redirect_url: 'https://api.test/v1/payments/card/p1/redirect' })
  expect(res.headers.get('cache-control')).toBe('no-store')
  const s = sent()
  expect(s.url).toBe(`http://backend.test/v1/fan/orders/${ORDER_ID}/pay`)
  expect(s.headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(s.body).toEqual({ method })
})

it('pay CASH_AGENCY: method forwarded; answers {method} only, never the code payload nor the cookie token', async () => {
  fetchMock.mockResolvedValue(
    upstream(200, { payment_id: 'p1', method: 'CASH_AGENCY', pay_code: '4F7K29', reserved_until: '2026-02-02T12:00:00Z', amount_mad: 10000, token: TOKEN }),
  )
  const res = await pay(req(`/api/orders/${ORDER_ID}/pay`, { body: { method: 'CASH_AGENCY' } }), params(ORDER_ID))
  expect(res.status).toBe(200)
  expect(res.headers.get('cache-control')).toBe('no-store')
  const text = await res.text()
  expect(JSON.parse(text)).toEqual({ method: 'CASH_AGENCY' })
  expect(text).not.toContain(TOKEN)
  expect(sent().body).toEqual({ method: 'CASH_AGENCY' })
  expect(sent().headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
})

it.each([
  ['no body', undefined],
  ['no method', {}],
  ['POS_CASH (staff only)', { method: 'POS_CASH' }],
  ['FREE', { method: 'FREE' }],
  ['a lower-case method', { method: 'card' }],
  ['a number', { method: 1 }],
])('pay refuses %s (422) and calls nothing', async (_, body) => {
  const res = await pay(req(`/api/orders/${ORDER_ID}/pay`, { body }), params(ORDER_ID))
  expect(res.status).toBe(422)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('pay passes 409 METHOD_NOT_AVAILABLE through with its reason', async () => {
  const err = { code: 'METHOD_NOT_AVAILABLE', message: '', details: { reason: 'TOO_LATE', sales_close_at: '2026-02-07T18:00:00Z' } }
  fetchMock.mockResolvedValue(upstream(409, err))
  const res = await pay(req(`/api/orders/${ORDER_ID}/pay`, { body: { method: 'CASH_AGENCY' } }), params(ORDER_ID))
  expect(res.status).toBe(409)
  expect(await res.json()).toEqual(err)
})

it.each([
  ['a foreign https origin', 'https://evil.com/v1/payments/card/p1/redirect'],
  ['a look-alike host', 'https://api.test.evil.com/v1/payments/card/p1/redirect'],
  ['http when API_PUBLIC_URL is https', 'http://api.test/v1/payments/card/p1/redirect'],
  ['another port', 'https://api.test:8443/v1/payments/card/p1/redirect'],
  ['a wrong path', 'https://api.test/v1/payments/card/p1/return'],
  ['outside the base path', 'https://api.test/payments/card/p1/redirect'],
  ['a dot segment', 'https://api.test/v1/payments/card/../../admin/redirect'],
  ['credentials', 'https://user:pass@api.test/v1/payments/card/p1/redirect'],
  ['a query', 'https://api.test/v1/payments/card/p1/redirect?next=https://evil.com'],
  ['a fragment', 'https://api.test/v1/payments/card/p1/redirect#x'],
  ['javascript:', 'javascript:alert(1)'],
  ['data:', 'data:text/html,x'],
  ['not a URL', 'not a url'],
  ['a number', 42],
])('pay refuses a redirect_url with %s (502)', async (_, url) => {
  fetchMock.mockResolvedValue(upstream(200, { payment_id: 'p1', redirect_url: url }))
  expect((await pay(req(`/api/orders/${ORDER_ID}/pay`, { body: { method: 'CARD' } }), params(ORDER_ID))).status).toBe(502)
})

it.each([
  ['unset', undefined],
  ['malformed', 'api.test/v1'],
])('pay refuses every redirect when API_PUBLIC_URL is %s (fail closed)', async (_, conf) => {
  vi.stubEnv('API_PUBLIC_URL', conf)
  fetchMock.mockResolvedValue(upstream(200, { payment_id: 'p1', redirect_url: 'https://api.test/v1/payments/card/p1/redirect' }))
  expect((await pay(req(`/api/orders/${ORDER_ID}/pay`, { body: { method: 'CARD' } }), params(ORDER_ID))).status).toBe(502)
  expect(console.error).toHaveBeenCalledWith('API_PUBLIC_URL is not set or malformed: refusing payment redirects')
})

it('pay passes 409 ORDER_EXPIRED through', async () => {
  fetchMock.mockResolvedValue(upstream(409, { code: 'ORDER_EXPIRED', message: '', details: {} }))
  const res = await pay(req(`/api/orders/${ORDER_ID}/pay`, { body: { method: 'CARD' } }), params(ORDER_ID))
  expect(res.status).toBe(409)
  expect((await res.json()).code).toBe('ORDER_EXPIRED')
})

it('status: GET with Bearer, no-store, only status fields', async () => {
  fetchMock.mockResolvedValue(upstream(200, { ...ORDER, status: 'PAID', paid_at: '2026-02-01T12:03:00Z', ticket_ids: ['t1'] }))

  const res = await getOrder(req(`/api/orders/${ORDER_ID}`, { method: 'GET' }), params(ORDER_ID))

  expect(res.status).toBe(200)
  expect(res.headers.get('cache-control')).toBe('no-store')
  expect(await res.json()).toEqual({ status: 'PAID', reserved_until: '2026-02-01T12:08:00Z', ticket_count: 1 })
  const s = sent()
  expect(s.url).toBe(`http://backend.test/v1/fan/orders/${ORDER_ID}`)
  expect(s.headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(s.init.cache).toBe('no-store')
})

it.each([
  ['absent: not passed on', {}, undefined],
  ['null: passed on', { payment_status: null }, null],
  ['FAILED: passed on', { payment_status: 'FAILED' }, 'FAILED'],
])('status: payment_status %s', async (_, extra, expected) => {
  fetchMock.mockResolvedValue(upstream(200, { ...ORDER, ...extra }))
  const body = await (await getOrder(req(`/api/orders/${ORDER_ID}`, { method: 'GET' }), params(ORDER_ID))).json()
  expect('payment_status' in body).toBe(expected !== undefined)
  expect(body.payment_status).toBe(expected)
})

it('status of a cash order: payment_method passed on, the code itself is not polled', async () => {
  const cash = { pay_code: '4F7K29', amount_mad: 10000, points: [], networks: [] }
  fetchMock.mockResolvedValue(upstream(200, { ...ORDER, status: 'AWAITING_CASH', payment_status: 'PENDING', payment_method: 'CASH_AGENCY', cash }))
  const body = await (await getOrder(req(`/api/orders/${ORDER_ID}`, { method: 'GET' }), params(ORDER_ID))).json()
  expect(body).toEqual({
    status: 'AWAITING_CASH',
    reserved_until: '2026-02-01T12:08:00Z',
    ticket_count: 0,
    payment_status: 'PENDING',
    payment_method: 'CASH_AGENCY',
  })
})

it("status passes 404 through (someone else's order)", async () => {
  fetchMock.mockResolvedValue(upstream(404, { code: 'NOT_FOUND', message: '', details: {} }))
  expect((await getOrder(req(`/api/orders/${ORDER_ID}`, { method: 'GET' }), params(ORDER_ID))).status).toBe(404)
})

it.each(['..', '../me', 'a/b', 'x%2Fy', ''])('an id like %j never reaches the backend (404)', async (id) => {
  expect((await getOrder(req('/api/orders/x', { method: 'GET' }), params(id))).status).toBe(404)
  expect((await pay(req('/api/orders/x/pay'), params(id))).status).toBe(404)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('PATCH /api/me sends display_name only, with the Bearer', async () => {
  fetchMock.mockResolvedValue(upstream(200, { id: 'u1', phone: '+212600000000', display_name: 'Yazid Aqel', locale: 'fr' }))
  const res = await patchMe(req('/api/me', { method: 'PATCH', body: { display_name: 'Yazid Aqel', phone: '+212699999999' } }))
  expect(await res.json()).toEqual({ display_name: 'Yazid Aqel' })
  expect(sent().body).toEqual({ display_name: 'Yazid Aqel' })
  expect(sent().headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
})

it('PATCH /api/me {locale} (language switch) forwards the locale with the session as Bearer', async () => {
  fetchMock.mockResolvedValue(upstream(200, { id: 'u1', phone: '+212600000000', display_name: 'Yazid Aqel', locale: 'ar' }))
  const res = await patchMe(req('/api/me', { method: 'PATCH', body: { locale: 'ar' } }))
  expect(res.status).toBe(200)
  expect(sent().url).toBe('http://backend.test/v1/fan/me')
  expect(sent().init.method).toBe('PATCH')
  expect(sent().body).toEqual({ locale: 'ar' })
  expect(sent().headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
})

it('PATCH /api/me drops a locale outside fr, ar, en', async () => {
  fetchMock.mockResolvedValue(upstream(200, { id: 'u1', display_name: null, locale: 'fr' }))
  await patchMe(req('/api/me', { method: 'PATCH', body: { locale: 'de' } }))
  expect(sent().body).toEqual({})
})

it('PATCH /api/me {locale} without a session (anonymous visitor) → 401, the backend is not called', async () => {
  const res = await patchMe(req('/api/me', { method: 'PATCH', body: { locale: 'ar' }, headers: { cookie: 'locale=ar' } }))
  expect(res.status).toBe(401)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('GET /api/me (header island): display_name only, never the phone or the token, not cached', async () => {
  fetchMock.mockResolvedValue(upstream(200, { id: 'u1', phone: '+212600000000', display_name: 'Yazid Aqel', locale: 'fr' }))
  const res = await getMe(req('/api/me', { method: 'GET' }))
  expect(res.status).toBe(200)
  const text = await res.text()
  expect(JSON.parse(text)).toEqual({ display_name: 'Yazid Aqel' })
  expect(text).not.toContain('+212')
  expect(text).not.toContain(TOKEN)
  expect(res.headers.get('cache-control')).toBe('no-store')
  expect(sent().url).toBe('http://backend.test/v1/fan/me')
  expect(sent().headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
})

it('GET /api/me with a revoked session → 401 and the cookie is cleared', async () => {
  fetchMock.mockResolvedValue(upstream(401, { code: 'UNAUTHORIZED', message: '', details: {} }))
  const res = await getMe(req('/api/me', { method: 'GET' }))
  expect(res.status).toBe(401)
  expect(res.headers.get('set-cookie')).toBe('__Host-jomhor_session=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax')
})

it('GET /api/me with the backend down → 502, the cookie is kept', async () => {
  fetchMock.mockRejectedValue(new TypeError('fetch failed'))
  const res = await getMe(req('/api/me', { method: 'GET' }))
  expect(res.status).toBe(502)
  expect(res.headers.get('set-cookie')).toBeNull()
})

const mutating = [
  ['POST /api/orders', () => createOrder(req('/api/orders', { body: VALID, headers: { origin: 'https://evil.com' } }))],
  ['POST /api/orders/{id}/pay', () => pay(req(`/api/orders/${ORDER_ID}/pay`, { headers: { origin: 'https://evil.com' } }), params(ORDER_ID))],
  ['PATCH /api/me', () => patchMe(req('/api/me', { method: 'PATCH', body: { display_name: 'X Y' }, headers: { origin: 'https://evil.com' } }))],
] as const

it.each(mutating)('%s from a foreign Origin → 403, backend not called', async (_, call) => {
  expect((await call()).status).toBe(403)
  expect(fetchMock).not.toHaveBeenCalled()
})

it.each([
  ['POST /api/orders', () => createOrder(req('/api/orders', { body: { items: [] }, headers: { cookie: '' } }))],
  ['POST /api/orders/{id}/pay', () => pay(req(`/api/orders/${ORDER_ID}/pay`, { headers: { cookie: '' } }), params(ORDER_ID))],
  ['GET /api/orders/{id}', () => getOrder(req(`/api/orders/${ORDER_ID}`, { method: 'GET', headers: { cookie: '' } }), params(ORDER_ID))],
  ['PATCH /api/me', () => patchMe(req('/api/me', { method: 'PATCH', body: {}, headers: { cookie: '' } }))],
  ['GET /api/me', () => getMe(req('/api/me', { method: 'GET', headers: { cookie: '' } }))],
] as const)('%s without a session → 401 UNAUTHORIZED, backend not called', async (_, call) => {
  const res = await call()
  expect(res.status).toBe(401)
  expect((await res.json()).code).toBe('UNAUTHORIZED')
  expect(fetchMock).not.toHaveBeenCalled()
})

it.each([
  ['4F7K29', 'JMH 4F7K 29'], // the wire form (04 §Cash codes)
  ['4f7k29', 'JMH 4F7K 29'],
  ['JMH 4F7K 29', 'JMH 4F7K 29'],
  ['jmh-4f7k-29', 'JMH 4F7K 29'],
  ['4F7KO1', 'JMH 4F7K 01'], // O → 0
  ['ABCDEFG', 'ABCDEFG'], // not 6 symbols: shown as sent
  ['4F7KU9', '4F7KU9'], // U is not a Crockford symbol
])('formatPayCode(%j) → %j', (raw, shown) => expect(formatPayCode(raw)).toBe(shown))

it('orderNo: last 8 hex digits of the id, 4-4', () => expect(orderNo(ORDER_ID)).toBe('6B2C 9D10'))

it('list (W5 pending): cookie → Bearer on /fan/orders only; AWAITING_CASH with a code, still in time, mapped; no-store', async () => {
  const row = (id: string, status: string, reserved_until: string | null, pay_code: string | null) => ({
    id,
    event: { id: 12, title: 'Atlas FC – Raja', starts_at: '2099-02-07T19:00:00Z', timezone: 'Africa/Casablanca', venue: 'x' },
    channel: 'WEB',
    status,
    total_mad: 10000,
    reserved_until,
    payment_method: 'CASH_AGENCY',
    pay_code,
    created_at: '2026-02-01T12:00:00Z',
    user_phone: '+212600000000',
  })
  const CASH_ID = '0190f3a2-7c1e-7b55-9a1e-000000000001'
  fetchMock.mockResolvedValue(
    upstream(200, {
      orders: [
        row(CASH_ID, 'AWAITING_CASH', '2099-02-01T12:00:00Z', '4F7K29'),
        row('0190f3a2-7c1e-7b55-9a1e-000000000002', 'AWAITING_CASH', '2099-02-01T12:00:00Z', null), // code no longer PENDING
        row('0190f3a2-7c1e-7b55-9a1e-000000000003', 'AWAITING_CASH', '2020-02-01T12:00:00Z', 'AAAA11'), // hold over, not yet swept
        row('0190f3a2-7c1e-7b55-9a1e-000000000004', 'RESERVED', '2099-02-01T12:00:00Z', null),
        row('0190f3a2-7c1e-7b55-9a1e-000000000005', 'PAID', null, null),
        row('../../org/x', 'AWAITING_CASH', '2099-02-01T12:00:00Z', 'BBBB22'),
      ],
      k_t: 'SECRET',
    }),
  )

  const res = await listOrders(req('/api/orders', { method: 'GET', headers: { origin: '' } }))

  expect(res.status).toBe(200)
  expect(res.headers.get('cache-control')).toBe('no-store')
  const text = await res.text()
  expect(text).not.toContain(TOKEN)
  expect(text).not.toContain('SECRET')
  expect(text).not.toContain('+212600000000')
  expect(JSON.parse(text)).toEqual({
    orders: [
      {
        id: CASH_ID,
        event: { title: 'Atlas FC – Raja', starts_at: '2099-02-07T19:00:00Z', timezone: 'Africa/Casablanca' },
        code: 'JMH 4F7K 29',
        amount_mad: 10000,
        reserved_until: '2099-02-01T12:00:00Z',
      },
    ],
  })
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(sent().url).toBe('http://backend.test/v1/fan/orders')
  expect(sent().headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(sent().init.cache).toBe('no-store')
})

it('list: no session → 401 without calling the backend; backend error passes through', async () => {
  const res = await listOrders(new Request('http://shop.test/api/orders'))
  expect(res.status).toBe(401)
  expect(fetchMock).not.toHaveBeenCalled()

  fetchMock.mockResolvedValue(upstream(401, { code: 'UNAUTHORIZED', message: 'expired', details: {} }))
  const expired = await listOrders(req('/api/orders', { method: 'GET' }))
  expect(expired.status).toBe(401)
  expect((await expired.json()).code).toBe('UNAUTHORIZED')
})
