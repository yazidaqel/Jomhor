import { renderToString } from 'react-dom/server'
import { getAccessFallbackHTTPStatus } from 'next/dist/client/components/http-access-fallback/http-access-fallback'
import { getURLFromRedirectError } from 'next/dist/client/components/redirect'
import { isRedirectError } from 'next/dist/client/components/redirect-error'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { json, NOW, ORG, rawEvent } from '@/lib/events.fixture'
import { safeNext } from '@/lib/next-path'
import OrderPage from './[id]/page'
import CheckoutPage from './new/page'

const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsImF1ZCI6ImZhbiJ9.c2lnbmF0dXJl'
const ORDER_ID = '0190f3a2-7c1e-7b55-9a1e-3f1f6b2c9d10'

let session: string | undefined
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: (name: string) => (name === '__Host-jomhor_session' && session ? { name, value: session } : undefined) }),
}))

// The checkout form is a client component using the router; there is no mounted app router in a server render test.
vi.mock('next/navigation', async (actual) => ({ ...(await actual<object>()), useRouter: () => ({ push: () => {}, replace: () => {} }) }))

const fetchMock = vi.fn<typeof fetch>()
/** Backend stub by path: the event detail, /fan/me and the order. */
function backend(routes: Record<string, () => Response>) {
  fetchMock.mockImplementation(async (url) => {
    const path = String(url).replace('http://backend.test/v1', '')
    const route = routes[path]
    return route ? route() : json(404, { code: 'NOT_FOUND', message: '', details: {} })
  })
}
beforeEach(() => {
  session = TOKEN
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubEnv('ORGANIZER_ID', ORG)
  vi.stubGlobal('fetch', fetchMock)
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
})

async function thrown(p: Promise<unknown>) {
  try {
    await p
  } catch (e) {
    return e
  }
  throw new Error('expected a throw')
}
const checkout = (q: Record<string, string | string[] | undefined>) => CheckoutPage({ searchParams: Promise.resolve(q) })
const orderPage = (id: string) => OrderPage({ params: Promise.resolve({ id }) })
const ME = () => json(200, { id: 'u1', phone: '+212600000000', display_name: 'Yazid Aqel', locale: 'fr' })
const ORDER = (over: Record<string, unknown> = {}) => () =>
  json(200, {
    id: ORDER_ID,
    event_id: 12,
    channel: 'WEB',
    status: 'RESERVED',
    total_mad: 10000,
    reserved_until: '2026-02-01T12:08:00Z',
    paid_at: null,
    items: [{ price_category_id: 'pc-virage', zone_id: 3, name: 'Virage', qty: 2, unit_price_mad: 5000, holders: [{ label: 'Yazid A.' }, { label: 'Salma B.' }] }],
    ticket_ids: [],
    ...over,
  })

it('W3 not signed in → login, with a same-site next that comes back to this checkout', async () => {
  session = undefined
  const e = await thrown(checkout({ event: '12', price: 'pc-virage', qty: '2' }))
  expect(isRedirectError(e)).toBe(true)
  const url = new URL(getURLFromRedirectError(e as never)!, 'https://shop.test')
  expect(url.pathname).toBe('/login')
  const next = url.searchParams.get('next')!
  expect(next).toBe('/orders/new?event=12&price=pc-virage&qty=2')
  expect(safeNext(next)).toBe(next)
  expect(fetchMock).not.toHaveBeenCalled()
})

it.each([
  { event: '12', price: '//evil.com', qty: '2' },
  { event: '12', price: 'pc-virage', qty: '0' },
  { event: '12-x', price: 'pc-virage', qty: '2' },
  { event: '12', price: 'pc-virage' },
  { event: ['12', '13'], price: 'pc-virage', qty: '2' },
])('W3 with a malformed query %j → 404 before anything else', async (q) => {
  session = undefined
  expect(getAccessFallbackHTTPStatus((await thrown(checkout(q))) as never)).toBe(404)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('W3 with a revoked session (/fan/me 401) → login', async () => {
  backend({ '/fan/events/12': () => json(200, rawEvent()), '/fan/me': () => json(401, { code: 'UNAUTHORIZED', message: '', details: {} }) })
  const e = await thrown(checkout({ event: '12', price: 'pc-virage', qty: '2' }))
  expect(getURLFromRedirectError(e as never)).toBe('/login?next=%2Forders%2Fnew%3Fevent%3D12%26price%3Dpc-virage%26qty%3D2')
})

it('W3 for a price that is not on sale any more → the event page', async () => {
  backend({ '/fan/events/12': () => json(200, rawEvent()), '/fan/me': ME })
  const e = await thrown(checkout({ event: '12', price: 'pc-sud', qty: '1' })) // remaining 0
  expect(getURLFromRedirectError(e as never)).toBe('/events/12-atlas-fc-vs-rapid-oued')
})

it('W3 renders one row per place, ticket 1 prefilled from /fan/me, phone only from ticket 2, integer total', async () => {
  backend({ '/fan/events/12': () => json(200, rawEvent()), '/fan/me': ME })
  const h = renderToString(await checkout({ event: '12', price: 'pc-virage', qty: '2' }))
  expect(h).toContain('Billet 1')
  expect(h).toContain('Billet 2')
  expect(h).toMatch(/id="name-0"[^>]*value="Yazid Aqel"/)
  expect(h).toMatch(/id="name-1"[^>]*value=""/)
  expect(h).not.toContain('id="phone-0"')
  expect(h).toContain('id="phone-1"')
  expect(h).toContain('2 × Tribune Nord · Virage')
  expect(h).toContain('Payer 100\u00a0DH')
  expect(h).not.toContain(TOKEN)
  const me = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/fan/me'))!
  expect(new Headers(me[1]!.headers).get('authorization')).toBe(`Bearer ${TOKEN}`)
})

it('W3 clamps qty to the per-account limit and says so', async () => {
  backend({ '/fan/events/12': () => json(200, rawEvent()), '/fan/me': ME })
  const h = renderToString(await checkout({ event: '12', price: 'pc-virage', qty: '9' }))
  expect(h).toContain('Billet 4')
  expect(h).not.toContain('Billet 5')
  expect(h).toContain('Limité à 4 billets : le maximum par compte pour cet événement.')
})

it('W3 clamps qty to what is left at that price and says so', async () => {
  const event = rawEvent()
  event.zones[0]!.prices[1]!.remaining = 2 // Virage
  backend({ '/fan/events/12': () => json(200, event), '/fan/me': ME })
  const h = renderToString(await checkout({ event: '12', price: 'pc-virage', qty: '3' }))
  expect(h).toContain('Billet 2')
  expect(h).not.toContain('Billet 3')
  expect(h).toContain('Il ne reste que 2 places à ce tarif')
})

it('W3 asking for what is available shows no capping line, and states the WhatsApp delivery', async () => {
  backend({ '/fan/events/12': () => json(200, rawEvent()), '/fan/me': ME })
  const h = renderToString(await checkout({ event: '12', price: 'pc-virage', qty: '2' }))
  expect(h).not.toContain('data-capped')
  expect(h).toContain('Billets envoyés dans l&#x27;app et sur WhatsApp')
})

it('/orders/[id] not signed in → login and back here', async () => {
  session = undefined
  const e = await thrown(orderPage(ORDER_ID))
  expect(getURLFromRedirectError(e as never)).toBe(`/login?next=%2Forders%2F${ORDER_ID}`)
})

it("/orders/[id] of someone else's order (backend 404) → 404", async () => {
  backend({})
  expect(getAccessFallbackHTTPStatus((await thrown(orderPage(ORDER_ID))) as never)).toBe(404)
})

it('/orders/[id] while RESERVED: "paiement en cours", hold banner in the venue zone, holder labels, no token', async () => {
  backend({ [`/fan/orders/${ORDER_ID}`]: ORDER(), '/fan/events/12': () => json(200, rawEvent()) })
  const h = renderToString(await orderPage(ORDER_ID))
  expect(h).toContain('Paiement en cours')
  expect(h).toContain('Places réservées jusqu\u2019à 13:08 · encore 8 min 0 s') // 12:08Z in Africa/Casablanca
  expect(h).toContain('Yazid A.')
  expect(h).toContain('Salma B.')
  expect(h).toContain('100\u00a0DH')
  expect(h).not.toContain(TOKEN)
  const call = fetchMock.mock.calls.find(([u]) => String(u).includes('/fan/orders/'))!
  expect(new Headers(call[1]!.headers).get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(call[1]!.cache).toBe('no-store')
})

it('/orders/[id] once PAID: link to Mes billets, and a note when a friend holds some of the tickets', async () => {
  backend({ [`/fan/orders/${ORDER_ID}`]: ORDER({ status: 'PAID', ticket_ids: ['t1'] }), '/fan/events/12': () => json(200, rawEvent()) })
  const h = renderToString(await orderPage(ORDER_ID))
  expect(h).toContain('Paiement confirmé')
  expect(h).toContain('href="/me/tickets"')
  expect(h).toContain('leur sont envoyés directement')
  expect(h).not.toContain('jusqu\u2019à')
})

it('/orders/[id] once EXPIRED: back to the event, no pay button', async () => {
  backend({ [`/fan/orders/${ORDER_ID}`]: ORDER({ status: 'EXPIRED' }), '/fan/events/12': () => json(200, rawEvent()) })
  const h = renderToString(await orderPage(ORDER_ID))
  expect(h).toContain('Réservation expirée')
  expect(h).toContain('href="/events/12-atlas-fc-vs-rapid-oued"')
  expect(h).not.toContain('Payer à nouveau')
})
