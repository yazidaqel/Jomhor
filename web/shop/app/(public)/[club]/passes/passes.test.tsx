import { renderToString } from 'react-dom/server'
import { getAccessFallbackHTTPStatus } from 'next/dist/client/components/http-access-fallback/http-access-fallback'
import { getURLFromRedirectError } from 'next/dist/client/components/redirect'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { POST as payInstallment } from '@/app/api/passes/[id]/installments/route'
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
  plans: [{ installments: 1, amounts_mad: [150000], interval_days: null }],
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
  cash_until: '2026-02-28T22:00:00Z',
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
  installment: null,
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

it('POST /api/product-orders: the cookie token → Bearer, the body rebuilt (channel WEB, 3b installments), only id/status/hold/total back', async () => {
  routes['/fan/product-orders'] = () => json(201, { ...productOrder(), token: 'SECRET' })
  const res = await createProductOrder(req({ product_id: PASS_ID, holder_name: 'Yazid Aqel', channel: 'POS', installments: 3, price_mad: 1 }))
  expect(res.status).toBe(201)
  expect(res.headers.get('cache-control')).toBe('no-store')
  expect(await res.json()).toEqual({ id: ORDER_ID, status: 'RESERVED', reserved_until: '2026-02-01T12:08:00Z', total_mad: 150000 })
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('http://backend.test/v1/fan/product-orders')
  expect(init!.method).toBe('POST')
  expect(new Headers(init!.headers).get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(JSON.parse(init!.body as string)).toEqual({ product_id: PASS_ID, channel: 'WEB', holder_name: 'Yazid Aqel', installments: 3 })
  // No installments sent → none forwarded (a pre-3b client).
  await createProductOrder(req({ product_id: PASS_ID, holder_name: 'Yazid Aqel' }))
  expect(JSON.parse(fetchMock.mock.calls[1]![1]!.body as string)).toEqual({ product_id: PASS_ID, channel: 'WEB', holder_name: 'Yazid Aqel' })
})

it('POST /api/product-orders: installments not an integer 1–10 → 422, nothing sent', async () => {
  for (const installments of [0, 11, 2.5, '4', null]) expect((await createProductOrder(req({ product_id: PASS_ID, holder_name: 'Yazid Aqel', installments }))).status).toBe(422)
  expect(fetchMock).not.toHaveBeenCalled()
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

// ---------- 3b · WS-16: installments ----------
// Contract-first: docs/04-api.md §Fan `plans`, `installments` on POST /fan/product-orders, the `/fan/passes` 3b
// fields, POST /fan/passes/{id}/installments, `/fan/tickets` effective status; §Passes Installments / Suspension.

const PLANS = [
  { installments: 1, amounts_mad: [150000], interval_days: null },
  { installments: 4, amounts_mad: [37503, 37499, 37499, 37499], interval_days: 30 },
]

it('W9 3b: plan chips from `plans` (in full, then N payments every 30 days); a single plan shows none', async () => {
  routes[`/fan/products?organizer=${ORG}`] = () => json(200, [rawPass({ plans: PLANS }), rawSold()])
  const h = await w9()
  expect(h).toContain(`En une fois · ${formatDh(150000)}`)
  expect(h).toContain(`En 4 fois · ${formatDh(37503)} puis ${formatDh(37499)} tous les 30 jours`)
  expect(h.match(/data-plan="/g)).toHaveLength(2) // the sold-out product has one plan: no chips
})

it('W9 3b: a malformed plan is dropped (amounts, interval_days missing or bad), never shown', async () => {
  const bad = [
    PLANS[0],
    { installments: 3, amounts_mad: [50000, 50000], interval_days: 30 },
    { installments: 2, amounts_mad: [75000.5, 75000], interval_days: 30 },
    { installments: 4, amounts_mad: [37500, 37500, 37500, 37500] }, // no interval_days: never assumed 30
    { installments: 5, amounts_mad: [30000, 30000, 30000, 30000, 30000], interval_days: -1 },
  ]
  routes[`/fan/products?organizer=${ORG}`] = () => json(200, [rawPass({ plans: bad })])
  const h = await w9()
  expect(h).not.toContain('data-plan=')
  for (const n of [3, 2, 4, 5]) expect(h).not.toContain(`En ${n} fois`)
})

// WS-16 real run: the dev fast clock (PASSES_INSTALLMENT_INTERVAL=10m) answers `interval_days` 0, whole days rounding
// down. The plan is real: shown, without "tous les 0 jours".
it('W9 3b: interval_days 0 (under a day) keeps the plan, without the interval', async () => {
  const plans = [PLANS[0], { installments: 3, amounts_mad: [50002, 49999, 49999], interval_days: 0 }]
  routes[`/fan/products?organizer=${ORG}`] = () => json(200, [rawPass({ plans })])
  const h = await w9()
  expect(h).toContain(`En 3 fois · ${formatDh(50002)} puis ${formatDh(49999)}<`)
  expect(h).not.toContain('tous les')
})

it('W9 3b: in full always first, from price_mad; one plan per N', async () => {
  const plans = [PLANS[1], { installments: 1, amounts_mad: [1], interval_days: null }, { ...PLANS[1], amounts_mad: [37500, 37500, 37500, 37500] }]
  routes[`/fan/products?organizer=${ORG}`] = () => json(200, [rawPass({ plans })])
  const h = await w9()
  expect([...h.matchAll(/data-plan="(\d+)"/g)].map((m) => m[1])).toEqual(['1', '4'])
  expect(h).toContain(`En une fois · ${formatDh(150000)}`)
  expect(h).toContain(`En 4 fois · ${formatDh(37503)} puis ${formatDh(37499)}`) // the first N=4 plan
  expect(h).not.toContain(formatDh(1))
})

it('W3 3b: the product variant offers the plans (in full checked first)', async () => {
  session = TOKEN
  routes[`/fan/products/${PASS_ID}`] = () => json(200, rawPass({ plans: PLANS }))
  const h = renderToString(await checkout({ product: PASS_ID }))
  expect(h).toContain(fr.passes.planTitle)
  expect(h).toMatch(/name="plan" checked="" value="1"/)
  expect(h).toMatch(/name="plan" value="4"/)
  expect(h).toContain(`En 4 fois · ${formatDh(37503)} puis ${formatDh(37499)} tous les 30 jours`)
  noSecrets(h)
})

// ---------- POST /api/passes/{id}/installments ----------

const PASS_UUID = '0190f3a2-0000-7000-8000-0000000bb001'
const instReq = (id = PASS_UUID, headers: Record<string, string> = {}) =>
  new Request(`http://shop.test/api/passes/${id}/installments`, {
    method: 'POST',
    headers: { origin: 'http://shop.test', cookie: `__Host-jomhor_session=${TOKEN}`, ...headers },
  })
const instParams = (id = PASS_UUID) => ({ params: Promise.resolve({ id }) })
const installmentOrder = () =>
  productOrder({
    pass_id: PASS_UUID,
    total_mad: 37499,
    items: [{ ...productOrder().items[0], unit_price_mad: 37499 }],
    installment: { seq: 2, of: 4 },
    product: orderProduct({ cash_until: null }),
    token: 'SECRET',
  })

it('POST /api/passes/{id}/installments: cross-origin or no Origin → 403, nothing sent; no session → 401; malformed id → 404', async () => {
  expect((await payInstallment(instReq(PASS_UUID, { origin: 'https://evil.test' }), instParams())).status).toBe(403)
  const noOrigin = new Request(`http://shop.test/api/passes/${PASS_UUID}/installments`, { method: 'POST', headers: { cookie: `__Host-jomhor_session=${TOKEN}` } })
  expect((await payInstallment(noOrigin, instParams())).status).toBe(403)
  expect((await payInstallment(instReq(PASS_UUID, { cookie: '' }), instParams())).status).toBe(401)
  expect((await payInstallment(instReq('x'), instParams('../fan/me'))).status).toBe(404)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('POST /api/passes/{id}/installments: Bearer forwarded, no body; 201 new / 200 live order, only id/status/hold/total back', async () => {
  routes[`/fan/passes/${PASS_UUID}/installments`] = () => json(201, installmentOrder())
  let res = await payInstallment(instReq(), instParams())
  expect(res.status).toBe(201)
  expect(res.headers.get('cache-control')).toBe('no-store')
  expect(await res.json()).toEqual({ id: ORDER_ID, status: 'RESERVED', reserved_until: '2026-02-01T12:08:00Z', total_mad: 37499 })
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe(`http://backend.test/v1/fan/passes/${PASS_UUID}/installments`)
  expect(init!.method).toBe('POST')
  expect(init!.body).toBeUndefined()
  expect(new Headers(init!.headers).get('authorization')).toBe(`Bearer ${TOKEN}`)

  routes[`/fan/passes/${PASS_UUID}/installments`] = () => json(200, installmentOrder())
  res = await payInstallment(instReq(), instParams())
  expect(res.status).toBe(200)
})

it('POST /api/passes/{id}/installments: 409 PASS_STATUS (PAID_UP) and 404 pass through', async () => {
  routes[`/fan/passes/${PASS_UUID}/installments`] = () => json(409, { code: 'PASS_STATUS', message: '', details: { reason: 'PAID_UP' } })
  const res = await payInstallment(instReq(), instParams())
  expect(res.status).toBe(409)
  expect(await res.json()).toEqual({ code: 'PASS_STATUS', message: '', details: { reason: 'PAID_UP' } })
  routes[`/fan/passes/${PASS_UUID}/installments`] = () => json(404, { code: 'NOT_FOUND', message: '', details: {} })
  expect((await payInstallment(instReq(), instParams())).status).toBe(404)
})

// ---------- W5 3b ----------

const rawOwned = (over: Record<string, unknown> = {}) => ({
  id: PASS_UUID,
  status: 'ACTIVE',
  product: { id: PASS_ID, name: 'Abonnement Virage Nord' },
  organizer: { id: ORG, slug: 'atlas-fc', name: 'ATLAS FC', crest_url: null },
  series: { id: 's1', name: 'Saison 2025-26', starts_on: '2025-08-15', ends_on: '2026-06-30' },
  zone: { id: 3, code: 'NORD', name: 'Tribune Nord' },
  timezone: 'Africa/Casablanca',
  holder_label: 'Yazid A.',
  created_at: '2025-12-03T10:00:00Z',
  order_id: null,
  events: [],
  suspended_at: null,
  // NOW is 2026-02-01T12:00Z: seq 1 paid, seq 2 and 3 overdue, seq 4 to come.
  installments: [
    { seq: 1, amount_mad: 37503, due_at: '2025-12-03T10:00:00Z', paid_at: '2025-12-03T10:02:00Z' },
    { seq: 2, amount_mad: 37499, due_at: '2026-01-02T10:00:00Z', paid_at: null },
    { seq: 3, amount_mad: 37499, due_at: '2026-02-01T10:00:00Z', paid_at: null },
    { seq: 4, amount_mad: 37499, due_at: '2026-03-03T10:00:00Z', paid_at: null },
  ],
  next_due: { seq: 2, amount_mad: 37499, due_at: '2026-01-02T10:00:00Z', overdue: true },
  pay_order_id: null,
  ...over,
})
const w5 = async () => renderToString(await TicketsPage())

it('W5 3b: the schedule "{seq}/{n} · {amount} · {date}" with Payée / En retard / À payer, and "Payer l’échéance · {amount}"', async () => {
  session = TOKEN
  routes['/fan/passes'] = () => json(200, { passes: [rawOwned()] })
  const h = await w5()
  expect(h).toContain(fr.passes.schedule)
  expect(h).toContain(`1/4 · ${formatDh(37503)} · 3 déc.`)
  expect(h).toContain(`2/4 · ${formatDh(37499)} · 2 janv.`)
  expect(h).toContain(`4/4 · ${formatDh(37499)} · 3 mars`)
  expect([...h.matchAll(/data-installment="(\w+)"/g)].map((m) => m[1])).toEqual(['paid', 'late', 'late', 'due'])
  expect(h).toContain(fr.passes.instPaid)
  expect(h).toContain(fr.passes.instLate)
  expect(h).toContain(fr.passes.instDue)
  expect(h).toContain(fmt(fr.passes.payInstallment, { amount: formatDh(37499) }))
  expect(h).toContain(`id="pass-${PASS_UUID}"`) // the blocked tickets' "Payer l'échéance" target
  expect(h).not.toContain('data-suspended') // ACTIVE (inside the grace period): no banner
  noSecrets(h)
})

it('W5 3b SUSPENDED: the danger banner with the overdue installment date, the status chip, and the pay CTA', async () => {
  session = TOKEN
  routes['/fan/passes'] = () => json(200, { passes: [rawOwned({ status: 'SUSPENDED', suspended_at: '2026-01-09T10:00:30Z' })] })
  const h = await w5()
  expect(h).toContain(fmt(fr.passes.suspendedBanner, { date: '2 janv.' }))
  expect(h).toContain(fr.passes.SUSPENDED)
  expect(h).toContain(fmt(fr.passes.payInstallment, { amount: formatDh(37499) }))
})

it('W5 3b: paid in full → no schedule, no CTA; paid up in installments → schedule, no CTA; PENDING → resume, no CTA', async () => {
  session = TOKEN
  const paid = rawOwned().installments.map((i) => ({ ...i, paid_at: '2026-01-01T00:00:00Z' }))
  routes['/fan/passes'] = () =>
    json(200, {
      passes: [
        rawOwned({ id: 'a', installments: [{ seq: 1, amount_mad: 150000, due_at: '2025-12-03T10:00:00Z', paid_at: '2025-12-03T10:02:00Z' }], next_due: null }),
        rawOwned({ id: 'b', installments: paid, next_due: null }),
        rawOwned({ id: 'c', status: 'PENDING', order_id: ORDER_ID }),
      ],
    })
  const h = await w5()
  expect(h.match(new RegExp(fr.passes.schedule, 'g'))).toHaveLength(2) // b and c, not a
  expect(h).not.toContain('Payer l’échéance')
  expect(h.match(new RegExp(fr.passes.resume, 'g'))).toHaveLength(1)
})

// WS-16 real run: right after the purchase, installment 1 is due at `created_at`, so it had passed by the first W5 read.
it('W5 3b PENDING: the purchase in progress reads À payer, never En retard', async () => {
  session = TOKEN
  const fresh = rawOwned().installments.map((i) => ({ ...i, paid_at: null }))
  routes['/fan/passes'] = () => json(200, { passes: [rawOwned({ status: 'PENDING', order_id: ORDER_ID, installments: fresh })] })
  const h = await w5()
  expect([...h.matchAll(/data-installment="(\w+)"/g)].map((m) => m[1])).toEqual(['due', 'due', 'due', 'due'])
  expect(h).not.toContain(fr.passes.instLate)
})

it('W5 3b renders in ar (schedule, banner, CTA)', async () => {
  session = TOKEN
  locale = 'ar'
  const ar = getDictionary('ar')
  routes['/fan/passes'] = () => json(200, { passes: [rawOwned({ status: 'SUSPENDED', suspended_at: '2026-01-09T10:00:30Z' })] })
  const h = await w5()
  expect(h).toContain(ar.passes.schedule)
  expect(h).toContain(ar.passes.instLate)
  expect(h).toContain(fmt(ar.passes.payInstallment, { amount: formatDh(37499) }))
  expect(h).toContain(ar.passes.suspendedBanner.split(' · ')[0])
  expect(h).not.toContain(fr.passes.schedule)
})

// ---------- W4 3b: an installment order ----------

it('W4 installment order: "Échéance 2/4" in the header and the summary; cash from the order (24 h hold), no product read', async () => {
  session = TOKEN
  routes[`/fan/products/${PASS_ID}`] = () => json(404, { code: 'NOT_FOUND', message: '', details: {} }) // off sale mid-season
  routes[`/fan/orders/${ORDER_ID}`] = () =>
    json(200, { ...installmentOrder(), installment: { seq: 2, of: 4 }, product: orderProduct({ cash_until: null }) })
  const h = await w4()
  expect(h).toMatch(/<h1[^>]*>Abonnement Virage Nord<\/h1>/)
  expect(h.match(/Échéance 2\/4/g)).toHaveLength(2) // the header and the summary line
  expect(h).toContain('value="CASH_AGENCY"')
  expect(h).toContain('Avant Lun. 2 févr. 2026 · 13:00') // now + 24 h in Africa/Casablanca, no sales close
  expect(h).toContain(formatDh(37499))
  expect(paths()).not.toContain(`/fan/products/${PASS_ID}`)
  noSecrets(h)
})

it('W4 installment order: cash only when the order offers it; a set cash_until still bounds it; once paid the summary reads "Échéance 2/4"', async () => {
  session = TOKEN
  routes[`/fan/orders/${ORDER_ID}`] = () =>
    json(200, { ...installmentOrder(), installment: { seq: 2, of: 4 }, product: orderProduct({ payment_methods: ['CARD'], cash_until: null }) })
  let h = await w4()
  expect(h).toContain('value="CARD"')
  expect(h).not.toContain('value="CASH_AGENCY"')

  routes[`/fan/orders/${ORDER_ID}`] = () =>
    json(200, { ...installmentOrder(), installment: { seq: 2, of: 4 }, product: orderProduct({ cash_until: '2026-02-01T11:00:00Z' }) })
  h = await w4()
  expect(h).toContain(fr.checkout.cashTooLate)

  routes[`/fan/orders/${ORDER_ID}`] = () =>
    json(200, { ...installmentOrder(), status: 'PAID', payment_status: 'SUCCEEDED', paid_at: '2026-02-01T12:03:00Z', installment: { seq: 2, of: 4 } })
  h = await w4()
  expect(h.match(/Échéance 2\/4/g)).toHaveLength(3) // the aside header, its summary line, "Échéance 2/4 payée"
  expect(h).not.toContain(fmt(fr.orderPage.line, { qty: 1, name: 'Abonnement Virage Nord' }))
  expect(paths()).not.toContain(`/fan/products/${PASS_ID}`)
})

it('W4 installment order renders in ar', async () => {
  session = TOKEN
  locale = 'ar'
  routes[`/fan/orders/${ORDER_ID}`] = () => json(200, { ...installmentOrder(), installment: { seq: 2, of: 4 } })
  expect(await w4()).toContain(fmt(getDictionary('ar').passes.installmentOf, { k: 2, n: 4 }))
})

it('W4 installment order EXPIRED: its own copy, and back to its pass on Mes billets (not W9, where a rebuy is PASS_HELD)', async () => {
  session = TOKEN
  routes[`/fan/orders/${ORDER_ID}`] = () =>
    json(200, { ...installmentOrder(), status: 'EXPIRED', reserved_until: '2026-02-01T11:50:00Z', installment: { seq: 2, of: 4 } })
  const h = await w4()
  expect(h).toContain(fr.passes.installmentExpired)
  expect(h).not.toContain(fr.passes.holdExpired)
  expect(h).toMatch(new RegExp(`href="/me/tickets#pass-${PASS_UUID}"[^>]*>${fr.passes.installmentBack}<`))
  expect(h).not.toContain('href="/atlas-fc/passes"')
})

it('W4 installment order PAID: "Échéance 2/4 payée" (never "abonnement actif": another installment may still be overdue) + Mes billets', async () => {
  session = TOKEN
  routes[`/fan/orders/${ORDER_ID}`] = () =>
    json(200, { ...installmentOrder(), status: 'PAID', payment_status: 'SUCCEEDED', paid_at: '2026-02-01T12:03:00Z', installment: { seq: 2, of: 4 } })
  const h = await w4()
  expect(h).toContain(fmt(fr.passes.installmentPaid, { k: 2, n: 4 }))
  expect(h).not.toContain(fr.passes.paidPASS)
  expect(h).toContain('href="/me/tickets"')
  expect(getDictionary('ar').passes.installmentPaid).not.toBe(fr.passes.installmentPaid)
  expect(getDictionary('en').passes.installmentExpired).not.toBe(fr.passes.installmentExpired)
})
