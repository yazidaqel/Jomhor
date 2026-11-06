import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PATCH as patchMe } from '../me/route'
import { POST as pay } from './[id]/pay/route'
import { GET as getOrder } from './[id]/route'
import { POST as createOrder } from './route'

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

it('pay: Bearer from the cookie, method CARD, answers only redirect_url', async () => {
  fetchMock.mockResolvedValue(upstream(200, { payment_id: 'p1', redirect_url: 'https://api.test/v1/payments/card/p1/redirect' }))

  const res = await pay(req(`/api/orders/${ORDER_ID}/pay`), params(ORDER_ID))

  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ redirect_url: 'https://api.test/v1/payments/card/p1/redirect' })
  expect(res.headers.get('cache-control')).toBe('no-store')
  const s = sent()
  expect(s.url).toBe(`http://backend.test/v1/fan/orders/${ORDER_ID}/pay`)
  expect(s.headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(s.body).toEqual({ method: 'CARD' })
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
  expect((await pay(req(`/api/orders/${ORDER_ID}/pay`), params(ORDER_ID))).status).toBe(502)
})

it.each([
  ['unset', undefined],
  ['malformed', 'api.test/v1'],
])('pay refuses every redirect when API_PUBLIC_URL is %s (fail closed)', async (_, conf) => {
  vi.stubEnv('API_PUBLIC_URL', conf)
  fetchMock.mockResolvedValue(upstream(200, { payment_id: 'p1', redirect_url: 'https://api.test/v1/payments/card/p1/redirect' }))
  expect((await pay(req(`/api/orders/${ORDER_ID}/pay`), params(ORDER_ID))).status).toBe(502)
  expect(console.error).toHaveBeenCalledWith('API_PUBLIC_URL is not set or malformed: refusing payment redirects')
})

it('pay passes 409 ORDER_EXPIRED through', async () => {
  fetchMock.mockResolvedValue(upstream(409, { code: 'ORDER_EXPIRED', message: '', details: {} }))
  const res = await pay(req(`/api/orders/${ORDER_ID}/pay`), params(ORDER_ID))
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
] as const)('%s without a session → 401 UNAUTHORIZED, backend not called', async (_, call) => {
  const res = await call()
  expect(res.status).toBe(401)
  expect((await res.json()).code).toBe('UNAUTHORIZED')
  expect(fetchMock).not.toHaveBeenCalled()
})
