import { renderToString } from 'react-dom/server'
import { getAccessFallbackHTTPStatus } from 'next/dist/client/components/http-access-fallback/http-access-fallback'
import { getURLFromRedirectError } from 'next/dist/client/components/redirect'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { POST as createProductOrder } from '@/app/api/product-orders/route'
import { json, NOW, ORG, rawClub, rawOrganizer, SECRETS } from '@/lib/events.fixture'
import { fmt, getDictionary } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { getPendingCash } from '@/lib/orders'
import { formatDate } from '@/lib/time'
import TicketsPage from '../../me/tickets/page'
import OrderPage from '../../orders/[id]/page'
import CheckoutPage from '../../orders/new/page'
import PassesPage, { generateMetadata } from './page'

// Lot 5 step 3 WS-15 (plan §11.5): W9, the W3 product variant and its route handler, W4 for a product order, W5 passes.
// Contract-first: bodies per docs/04-api.md §Fan `/fan/products*`, `/fan/product-orders`, `/fan/passes`.

const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsImF1ZCI6ImZhbiJ9.c2lnbmF0dXJl'
const PASS_ID = '0190f3a2-0000-7000-8000-00000000aa01'
const MEMBER_ID = '0190f3a2-0000-7000-8000-00000000aa02'
const SOLD_ID = '0190f3a2-0000-7000-8000-00000000aa03'
const ORDER_ID = '0190f3a2-7c1e-7b55-9a1e-3f1f6b2c9d10'
const fr = getDictionary('fr')

let session: string | undefined
let locale: string | undefined
vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) => {
      const value = name === '__Host-jomhor_session' ? session : name === 'locale' ? locale : undefined
      return value ? { name, value } : undefined
    },
  }),
  headers: async () => new Headers(),
}))
// W3 and W4 render client components that use the router; no app router is mounted in a server render test.
vi.mock('next/navigation', async (actual) => ({ ...(await actual<object>()), useRouter: () => ({ push: () => {}, replace: () => {}, refresh: () => {} }) }))

const rawPass = (over: Record<string, unknown> = {}) => ({
  id: PASS_ID,
  organizer_id: ORG,
  organizer: rawOrganizer(), // + settings / SECRET fields that must not leak
  timezone: 'Africa/Casablanca',
  kind: 'PASS',
  name: 'Abonnement Virage Nord',
  price_mad: 150000,
  sales_close_at: '2026-03-01T00:00:00Z',
  remaining: 12,
  sold_out: false,
  valid_until: null,
  pass: {
    series: { id: 's1', name: 'Saison 2025-26', starts_on: '2025-08-15', ends_on: '2026-06-30' },
    zone: { id: 3, code: 'NORD', name: 'Tribune Nord' },
    venue: { name: 'Stade Atlas', city: 'Rabat', timezone: 'Africa/Casablanca' },
    events: [
      { id: 12, title: 'ATLAS FC vs Rapid Oued', event_type: 'FOOTBALL_MATCH', starts_at: '2026-02-07T19:00:00Z' },
      { id: 13, title: 'ATLAS FC vs Union Rif', event_type: 'FOOTBALL_MATCH', starts_at: '2026-02-21T19:00:00Z' },
    ],
  },
  plans: [{ installments: 1, amounts_mad: [150000] }],
  payment_methods: ['CARD', 'CASH_AGENCY'],
  cash_until: '2026-02-28T22:00:00Z',
  // Not in the contract: a backend bug must not leak them.
  quota: 100,
  sold: 88,
  k_t: 'SECRET-KT',
  ...over,
})
const rawMembership = () => ({
  ...rawPass({ id: MEMBER_ID, kind: 'MEMBERSHIP', name: 'Carte membre 2025-26', price_mad: 30000, remaining: null, pass: null }),
  valid_until: '2026-06-30T23:00:00Z',
})
const rawSold = () => rawPass({ id: SOLD_ID, name: 'Abonnement Tribune Sud', remaining: 0, sold_out: true })

const orderProduct = (over: Record<string, unknown> = {}) => ({
  id: PASS_ID,
  kind: 'PASS',
  name: 'Abonnement Virage Nord',
  organizer: rawOrganizer(),
  timezone: 'Africa/Casablanca',
  payment_methods: ['CARD', 'WALLET', 'CASH_AGENCY'],
  ...over,
})
const productOrder = (over: Record<string, unknown> = {}) => ({
  id: ORDER_ID,
  event_id: null,
  product: orderProduct(),
  pass_id: 'pass-1',
  channel: 'WEB',
  status: 'RESERVED',
  payment_status: null,
  payment_method: null,
  cash: null,
  total_mad: 150000,
  reserved_until: '2026-02-01T12:08:00Z',
  paid_at: null,
  items: [{ product_id: PASS_ID, price_category_id: null, zone_id: 3, zone_name: 'Tribune Nord', name: 'Abonnement Virage Nord', qty: 1, unit_price_mad: 150000, holders: [{ label: 'Yazid A.' }] }],
  ticket_ids: [],
  group: null,
  ...over,
})

const fetchMock = vi.fn<typeof fetch>()
let routes: Record<string, () => Response>
const paths = () => fetchMock.mock.calls.map(([u]) => String(u).replace('http://backend.test/v1', ''))
beforeEach(() => {
  session = undefined
  locale = undefined
  routes = {
    '/fan/organizers': () => json(200, [rawClub()]),
    '/fan/organizers/atlas-fc': () => json(200, rawClub()),
    [`/fan/products?organizer=${ORG}`]: () => json(200, [rawPass(), rawSold(), rawMembership()]),
    [`/fan/products/${PASS_ID}`]: () => json(200, rawPass()),
    [`/fan/products/${MEMBER_ID}`]: () => json(200, rawMembership()),
    [`/fan/products/${SOLD_ID}`]: () => json(200, rawSold()),
    '/fan/me': () => json(200, { id: 'u1', phone: '+212600000000', display_name: 'Yazid Aqel', locale: 'fr' }),
  }
  fetchMock.mockImplementation(async (url) => {
    const route = routes[String(url).replace('http://backend.test/v1', '')]
    return route ? route() : json(404, { code: 'NOT_FOUND', message: '', details: {} })
  })
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubEnv('SHOP_ORIGIN', 'http://shop.test')
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
const params = (club: string) => ({ params: Promise.resolve({ club }) })
const w9 = async (club = 'atlas-fc') => renderToString(await PassesPage(params(club)))
const checkoutPath = (id: string) => `/orders/new?product=${id}`
const loginHref = (id: string) => `/login?next=${encodeURIComponent(checkoutPath(id))}`
const noSecrets = (h: string) => {
  for (const s of [...SECRETS, TOKEN]) expect(h).not.toContain(s)
}

// ---------- W9 ----------

it('W9 signed out: products from GET /fan/products?organizer= (cached 60 s, no Authorization), "Acheter" through login', async () => {
  const h = await w9()
  const [, init] = fetchMock.mock.calls[paths().indexOf(`/fan/products?organizer=${ORG}`)]!
  expect((init as { next?: { revalidate?: number } }).next?.revalidate).toBe(60)
  expect(new Headers(init!.headers).get('authorization')).toBeNull()
  expect(h).toContain('ATLAS FC')
  expect(h).toContain('Abonnement Virage Nord')
  expect(h).toContain(formatDh(150000))
  expect(h).toContain('Tribune Nord · Stade Atlas · Rabat')
  expect(h).toContain('2 matchs annoncés')
  expect(h).toContain('ATLAS FC vs Union Rif') // the announced matches, in a <details>
  expect(h).toContain(fr.passes.perMatch)
  // The membership card copy (DESIGN W9), the date in Morocco's zone.
  const membership = fmt(fr.passes.membership, { price: formatDh(30000), date: formatDate('2026-06-30T23:00:00Z', 'Africa/Casablanca', 'fr-FR') })
  expect(h).toContain(membership)
  expect(h).toContain(`href="${loginHref(PASS_ID)}"`)
  expect(h).toContain(`href="${loginHref(MEMBER_ID)}"`)
  // Sold out: "Complet", no way to buy it.
  expect(h).toContain(fr.passes.soldOut)
  expect(h).not.toContain(`product=${SOLD_ID}`)
  expect(h).not.toContain(`product%3D${SOLD_ID}`)
  noSecrets(h)
})

it('W9 signed in: "Acheter" → W3 ?product=; the session never reaches the HTML', async () => {
  session = TOKEN
  const h = await w9()
  expect(h).toContain(`href="${checkoutPath(PASS_ID)}"`)
  expect(h).not.toContain('/login?next=')
  noSecrets(h)
  // The product read stays public: no Bearer even when signed in.
  for (const [, init] of fetchMock.mock.calls) expect(new Headers(init?.headers).get('authorization')).toBeNull()
})

it('W9 metadata: indexable, canonical /atlas-fc/passes, the club OG image; unknown club → 404; nothing on sale → empty copy', async () => {
  const m = await generateMetadata(params('atlas-fc'))
  expect(m.title).toBe('Abonnements ATLAS FC')
  expect(m.robots).toBeUndefined()
  expect(m.alternates).toEqual({ canonical: '/atlas-fc/passes' })
  expect(m.openGraph).toMatchObject({ url: '/atlas-fc/passes', images: ['/atlas-fc/opengraph-image'] })
  await expect(PassesPage(params('nope'))).rejects.toSatisfy((e) => getAccessFallbackHTTPStatus(e as never) === 404)
  routes[`/fan/products?organizer=${ORG}`] = () => json(200, [])
  expect(await w9()).toContain(fr.passes.empty)
})

it('W9: a product past sales_close_at (still in a cached list) reads "Vente terminée", with no way to buy it', async () => {
  routes[`/fan/products?organizer=${ORG}`] = () => json(200, [rawPass({ sales_close_at: '2026-02-01T11:59:59Z' }), rawMembership()])
  const h = await w9()
  expect(h).toContain(fr.passes.closed)
  expect(h).not.toContain(`product%3D${PASS_ID}`)
  expect(h).toContain(`product%3D${MEMBER_ID}`)
  expect(getDictionary('ar').passes.closed).not.toBe(fr.passes.closed)
})

it('W9 renders in ar', async () => {
  locale = 'ar'
  const ar = getDictionary('ar')
  const h = await w9()
  expect(h).toContain(ar.passes.title)
  expect(h).toContain(ar.passes.buy)
  expect(h).toContain(fmt(ar.passes.matches, { n: 2 }))
  expect(h).not.toContain(fr.passes.buy)
})

it('W2 and W7 link to W9 ("Abonnements")', async () => {
  const { default: ClubPage } = await import('../page')
  routes[`/fan/events?organizer=${ORG}`] = () => json(200, [])
  expect(renderToString(await ClubPage(params('atlas-fc')))).toContain('href="/atlas-fc/passes"')
  const { EventView } = await import('../events/[slug]/event-view')
  const { getEvent } = await import('@/lib/events')
  const { rawEvent } = await import('@/lib/events.fixture')
  routes['/fan/events/12'] = () => json(200, rawEvent())
  const e = (await getEvent(12))!
  expect(renderToString(<EventView e={e} now={NOW} d={fr} />)).toMatch(/href="\/atlas-fc\/passes"[^>]*>.*Abonnements<\/a>/)
})

// ---------- route handler ----------

const req = (body: unknown, headers: Record<string, string> = {}) =>
  new Request('http://shop.test/api/product-orders', {
    method: 'POST',
    headers: { origin: 'http://shop.test', cookie: `a=1; __Host-jomhor_session=${TOKEN}`, 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })

it('POST /api/product-orders: cross-origin → 403, nothing sent', async () => {
  const res = await createProductOrder(req({ product_id: PASS_ID, holder_name: 'Yazid Aqel' }, { origin: 'https://evil.test' }))
  expect(res.status).toBe(403)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('POST /api/product-orders: the cookie token → Bearer, the body rebuilt (channel WEB, no installments), only id/status/hold/total back', async () => {
  routes['/fan/product-orders'] = () => json(201, { ...productOrder(), token: 'SECRET' })
  const res = await createProductOrder(req({ product_id: PASS_ID, holder_name: 'Yazid Aqel', channel: 'POS', installments: 3, price_mad: 1 }))
  expect(res.status).toBe(201)
  expect(res.headers.get('cache-control')).toBe('no-store')
  expect(await res.json()).toEqual({ id: ORDER_ID, status: 'RESERVED', reserved_until: '2026-02-01T12:08:00Z', total_mad: 150000 })
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('http://backend.test/v1/fan/product-orders')
  expect(init!.method).toBe('POST')
  expect(new Headers(init!.headers).get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(JSON.parse(init!.body as string)).toEqual({ product_id: PASS_ID, channel: 'WEB', holder_name: 'Yazid Aqel' })
})

it('POST /api/product-orders: no session → 401; a malformed body → 422; a backend 409 passes through', async () => {
  expect((await createProductOrder(req({ product_id: PASS_ID, holder_name: 'x' }, { cookie: '' }))).status).toBe(401)
  expect((await createProductOrder(req({ product_id: '../x', holder_name: 'Yazid' }))).status).toBe(422)
  expect((await createProductOrder(req({ product_id: PASS_ID, holder_name: 7 }))).status).toBe(422)
  expect(fetchMock).not.toHaveBeenCalled()
  routes['/fan/product-orders'] = () => json(409, { code: 'PASS_HELD', message: '', details: { pass_id: 'p1', status: 'PENDING' } })
  const res = await createProductOrder(req({ product_id: PASS_ID, holder_name: 'Yazid Aqel' }))
  expect(res.status).toBe(409)
  expect((await res.json()).code).toBe('PASS_HELD')
})

// ---------- W3 ?product= ----------

const checkout = (q: Record<string, string>) => CheckoutPage({ searchParams: Promise.resolve(q) })

it('W3 ?product= signed out → login, back here; malformed id → 404 before any call', async () => {
  const e = await thrown(checkout({ product: PASS_ID }))
  expect(getURLFromRedirectError(e as never)).toBe(loginHref(PASS_ID))
  expect(getAccessFallbackHTTPStatus((await thrown(checkout({ product: '../fan/me' }))) as never)).toBe(404)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('W3 ?product= renders the product variant: holder prefilled, the product methods, the summary; no token in the HTML', async () => {
  session = TOKEN
  const h = renderToString(await checkout({ product: PASS_ID }))
  expect(h).toMatch(/id="holder-name"[^>]*value="Yazid Aqel"/)
  expect(h).toContain('value="CARD"')
  expect(h).toContain('value="CASH_AGENCY"')
  expect(h).not.toContain('value="WALLET"')
  expect(h).not.toContain('Billet 1') // no per-place rows
  expect(h).toContain('Abonnement Virage Nord')
  expect(h).toContain(fr.passes.captionPASS)
  expect(h).toContain('href="/atlas-fc/passes"') // from the product's organizer
  expect(paths()).not.toContain('/fan/organizers')
  expect(paths()).not.toContain('/fan/events/12')
  noSecrets(h)
})

it('W3 ?product= sold out → back to W9; unknown product → 404', async () => {
  session = TOKEN
  expect(getURLFromRedirectError((await thrown(checkout({ product: SOLD_ID }))) as never)).toBe('/atlas-fc/passes')
  expect(getAccessFallbackHTTPStatus((await thrown(checkout({ product: '0190f3a2-0000-7000-8000-00000000ffff' }))) as never)).toBe(404)
})

it('W3 reads the product uncached: Next keeps only 200 answers, so a cached read never sees its off-sale 404 (real run)', async () => {
  session = TOKEN
  await thrown(checkout({ product: SOLD_ID }))
  const [, init] = fetchMock.mock.calls[paths().indexOf(`/fan/products/${SOLD_ID}`)]!
  expect(init).toMatchObject({ cache: 'no-store' })
  expect((init as { next?: unknown }).next).toBeUndefined()
})

// ---------- W4 product order ----------

const w4 = async () => renderToString(await OrderPage({ params: Promise.resolve({ id: ORDER_ID }) }))

it('W4 product order RESERVED with no attempt: product header and method rows, no event read', async () => {
  session = TOKEN
  routes[`/fan/orders/${ORDER_ID}`] = () => json(200, productOrder())
  const h = await w4()
  expect(paths().some((p) => p.startsWith('/fan/events'))).toBe(false)
  expect(h).toMatch(/<h1[^>]*>Abonnement Virage Nord<\/h1>/)
  expect(h).toContain('value="CARD"')
  expect(h).toContain('value="CASH_AGENCY"')
  expect(h).toContain('Ta commande est gardée jusqu’à 13:08') // 12:08Z in Africa/Casablanca
  expect(h).toContain(fr.passes.holder)
  expect(h).toContain('Yazid A.')
  expect(h).toContain('ATLAS FC') // the club, from the order's product
  // The live product read is for cash only.
  expect(paths()).toContain(`/fan/products/${PASS_ID}`)
  noSecrets(h)
})

it('W4 product order off sale (product read 404): crest, club and the order methods all the same, cash dropped', async () => {
  session = TOKEN
  routes[`/fan/products/${PASS_ID}`] = () => json(404, { code: 'NOT_FOUND', message: '', details: {} })
  routes[`/fan/orders/${ORDER_ID}`] = () => json(200, productOrder())
  const h = await w4()
  expect(h).toMatch(/<h1[^>]*>Abonnement Virage Nord<\/h1>/)
  expect(h).toMatch(/style="width:64px;height:64px;font-size:32px"[^>]*>A<\/span>/) // the crest stand-in
  expect(h).toContain('ATLAS FC')
  expect(h).toContain('value="CARD"')
  expect(h).toContain('value="WALLET"')
  expect(h).not.toContain('value="CASH_AGENCY"')
  expect(h).toContain('Ta commande est gardée jusqu’à 13:08')
})

it('W4 product order of an unlisted club: its crest still shows; time zone from the order', async () => {
  session = TOKEN
  routes['/fan/organizers'] = () => json(200, [])
  routes[`/fan/orders/${ORDER_ID}`] = () =>
    json(200, productOrder({ product: orderProduct({ organizer: rawOrganizer({ slug: null, crest_url: 'https://cdn.jomhor.test/crest.png' }), timezone: 'Europe/Paris' }) }))
  const h = await w4()
  expect(h).toContain('src="https://cdn.jomhor.test/crest.png"')
  expect(h).toContain('ATLAS FC')
  expect(h).toContain('Ta commande est gardée jusqu’à 13:08') // 12:08Z in Europe/Paris (UTC+1 in February)
  expect(h).not.toContain('/passes"')
})

it('W4 product order EXPIRED: its own line (no "places libérées"), back to W9 as "Voir les abonnements"', async () => {
  session = TOKEN
  routes[`/fan/orders/${ORDER_ID}`] = () =>
    json(200, productOrder({ status: 'EXPIRED', reserved_until: '2026-02-01T11:50:00Z', product: orderProduct({ id: MEMBER_ID, kind: 'MEMBERSHIP', name: 'Carte membre 2025-26' }) }))
  const h = await w4()
  expect(h).toContain(fr.passes.holdExpired)
  expect(h).not.toContain(fr.orderPage.expired)
  expect(h).toMatch(new RegExp(`href="/atlas-fc/passes"[^>]*>${fr.passes.back}<`))
  expect(h).not.toContain(fr.orderPage.back)
})

it('W4 product order with an attempt: OrderStatus; PAID pass → active + Mes billets; PAID membership → up to date, no tickets link', async () => {
  session = TOKEN
  routes[`/fan/orders/${ORDER_ID}`] = () => json(200, productOrder({ payment_status: 'PENDING', payment_method: 'CARD' }))
  let h = await w4()
  expect(h).not.toContain('value="CASH_AGENCY"')
  expect(h).toContain(fr.orderPage.pendingTitle)

  routes[`/fan/orders/${ORDER_ID}`] = () => json(200, productOrder({ status: 'PAID', payment_status: 'SUCCEEDED', paid_at: '2026-02-01T12:03:00Z' }))
  h = await w4()
  expect(h).toContain(fr.passes.paidPASS)
  expect(h).toContain('href="/me/tickets"')
  expect(h).not.toContain(fr.orderPage.paidOthers)

  routes[`/fan/orders/${ORDER_ID}`] = () =>
    json(200, productOrder({ status: 'PAID', payment_status: 'SUCCEEDED', pass_id: null, product: orderProduct({ id: MEMBER_ID, kind: 'MEMBERSHIP', name: 'Carte membre 2025-26' }) }))
  h = await w4()
  expect(h).toContain(fr.passes.paidMEMBERSHIP)
  expect(h).not.toContain('href="/me/tickets"')
  expect(paths().some((p) => p.startsWith('/fan/events'))).toBe(false)
})

// ---------- W5 ----------

it('W5 "Mes abonnements": one row per pass with its status; PENDING resumes its order', async () => {
  session = TOKEN
  routes['/fan/passes'] = () =>
    json(200, {
      passes: [
        {
          id: 'p2',
          status: 'PENDING',
          product: { id: PASS_ID, name: 'Abonnement Virage Nord' },
          organizer: { id: ORG, slug: 'atlas-fc', name: 'ATLAS FC', crest_url: null },
          series: { id: 's1', name: 'Saison 2025-26', starts_on: '2025-08-15', ends_on: '2026-06-30' },
          zone: { id: 3, code: 'NORD', name: 'Tribune Nord' },
          holder_label: 'Yazid A.',
          created_at: '2026-02-01T11:00:00Z',
          order_id: ORDER_ID,
        },
        {
          id: 'p1',
          status: 'ACTIVE',
          product: { id: 'x', name: 'Abonnement Tribune Sud' },
          organizer: { id: ORG, slug: 'atlas-fc', name: 'ATLAS FC', crest_url: null },
          series: { id: 's0', name: 'Coupe', starts_on: '2026-01-01', ends_on: '2026-05-31' },
          zone: { id: 4, code: 'SUD', name: 'Tribune Sud' },
          holder_label: 'Yazid A.',
          created_at: '2026-01-01T11:00:00Z',
          order_id: null,
        },
      ],
    })
  const h = renderToString(await TicketsPage())
  expect(h).toContain(fr.passes.mine)
  expect(h).toContain(fr.passes.PENDING)
  expect(h).toContain(fr.passes.ACTIVE)
  expect(h).toContain(`href="/orders/${ORDER_ID}"`)
  expect(h.match(new RegExp(fr.passes.resume, 'g'))).toHaveLength(1)
  expect(h).toContain('Tribune Nord · Saison 2025-26 · 15 août – 30 juin')
  expect(h.indexOf(fr.passes.mine)).toBeLessThan(h.indexOf('Chargement de tes billets')) // above the tickets
  noSecrets(h)
})

it('W5 pending cash list: a product order (event null) shows its product name, no date', async () => {
  routes['/fan/orders'] = () =>
    json(200, {
      orders: [
        { id: ORDER_ID, event: null, product: { id: PASS_ID, kind: 'PASS', name: 'Abonnement Virage Nord', timezone: 'Europe/Paris' }, status: 'AWAITING_CASH', total_mad: 150000, reserved_until: '2026-02-02T12:00:00Z', pay_code: '4F7K29' },
      ],
    })
  expect(await getPendingCash(TOKEN, NOW)).toEqual([
    { id: ORDER_ID, event: { title: 'Abonnement Virage Nord', starts_at: null, timezone: 'Europe/Paris' }, code: 'JMH 4F7K 29', amount_mad: 150000, reserved_until: '2026-02-02T12:00:00Z' },
  ])
})
