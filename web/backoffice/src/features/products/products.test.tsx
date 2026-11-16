import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import type { Series } from '@/features/events/api'
import type { Venue, VenueDetail } from '@/features/venues/api'
import { formatDh } from '@/lib/money'
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'
import { formatDay, wallToUtc } from '@/lib/time'
import type { Product, ProductDetail } from './api'

beforeAll(() => {
  // Radix needs these; jsdom does not implement them.
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  session.clear()
})

// ---- Fake API ----

const CASA = 'Africa/Casablanca'
/** End of `day` in Casablanca, computed apart from the code under test: 23:59 wall time + 59 s. */
function endOfDay(day: string) {
  const naive = Date.parse(`${day}T23:59:00Z`)
  const name = new Intl.DateTimeFormat('en-US', { timeZone: CASA, timeZoneName: 'longOffset' }).formatToParts(naive).find((x) => x.type === 'timeZoneName')!.value
  const m = /GMT([+-])(\d{2}):(\d{2})/.exec(name)
  const offset = m ? (m[1] === '-' ? -1 : 1) * (Number(m[2]) * 60 + Number(m[3])) : 0
  return new Date(naive - offset * 60_000 + 59_000).toISOString()
}
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const VENUES: Venue[] = [
  { id: 'v-1', name: 'Grand Stade', city: 'Casablanca', capacity: 45000, timezone: CASA },
  { id: 'v-2', name: 'Salle Pleyel', city: 'Paris', capacity: 3000, timezone: 'Europe/Paris' },
]
const VENUE_DETAILS: Record<string, VenueDetail> = {
  'v-1': {
    ...VENUES[0]!,
    zones: [
      { id: 1, code: 'EST', name: 'Tribune Est', capacity: 8000, seated: false },
      { id: 2, code: 'NORD', name: 'Tribune Nord', capacity: 20000, seated: false },
    ],
    gates: [],
  },
  'v-2': { ...VENUES[1]!, zones: [{ id: 3, code: 'PARTERRE', name: 'Parterre', capacity: 3000, seated: false }], gates: [] },
}
const SERIES: Series[] = [{ id: 's-1', name: 'Saison 2026-27', starts_on: '2026-08-15', ends_on: '2027-06-30' }]
const PASS: Product = {
  id: 'p-1',
  kind: 'PASS',
  name: 'Abonnement Tribune Est',
  price_mad: 150000,
  on_sale: true,
  sales_close_at: '2027-05-31T22:00:00Z',
  quota: 4,
  sold: 4,
  valid_until: null,
  series: { id: 's-1', name: 'Saison 2026-27' },
  zone: { id: 1, code: 'EST', name: 'Tribune Est', venue: { id: 'v-1', name: 'Grand Stade', timezone: CASA } },
  max_installments: 4,
}
const MEMBERSHIP: Product = {
  id: 'm-1',
  kind: 'MEMBERSHIP',
  name: 'Carte membre 2026-27',
  price_mad: 20000,
  on_sale: true,
  sales_close_at: '2027-05-31T22:00:00Z',
  quota: null,
  sold: 0,
  valid_until: '2027-06-30T22:59:59Z', // as the real API stores the end of 30 June (no milliseconds)
  series: null,
  zone: null,
  max_installments: 1,
}
// A pass at a venue outside Morocco: its times read in Europe/Paris.
const PARIS = 'Europe/Paris'
const PARIS_PASS: Product = {
  ...PASS,
  id: 'p-2',
  name: 'Pass Parterre',
  sales_close_at: '2027-05-31T20:00:00Z',
  zone: { id: 3, code: 'PARTERRE', name: 'Parterre', venue: { id: 'v-2', name: 'Salle Pleyel', timezone: PARIS } },
}
/** Expected display of an instant in `zone`, from Intl directly. */
const display = (iso: string, zone: string) => new Intl.DateTimeFormat('fr', { timeZone: zone, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))

const PASS_DETAIL: ProductDetail = {
  ...PASS,
  figures: {
    passes: { pending: 1, active: 3, suspended: 2 },
    revenue_mad: 450000,
    installments: { overdue: 3, due_mad: 337500, collected_mad: 450000 },
    events: [
      { event_id: 1, title: 'Atlas FC vs Rapid Oued', starts_at: '2026-11-14T19:00:00Z', status: 'ON_SALE', issued: 2, expected: 3, pass_remaining: 0 },
      { event_id: 2, title: 'Atlas FC vs Wydad', starts_at: '2026-11-28T19:00:00Z', status: 'ON_SALE', issued: 3, expected: 3, pass_remaining: 1 },
      { event_id: 3, title: 'Atlas FC vs Raja', starts_at: '2026-12-12T19:00:00Z', status: 'DRAFT', issued: 0, expected: 3, pass_remaining: 3 },
    ],
  },
  refunds_required: [{ payment_id: 'pay-9', order_id: 'ord-9', amount_mad: 150000, reason: 'SOLD_OUT', at: '2026-11-10T12:00:00.813700Z' }], // microseconds, as the real API sends `at`
}

type Call = { method: string; path: string; org: string | null; body: unknown }
type Handler = (call: Call) => Response | undefined

function stubApi(role: Role = 'ADMIN', products: Product[] = [PASS], handler: Handler = () => undefined) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const call: Call = {
        method: init?.method ?? 'GET',
        path: String(input).replace(/^\/v1/, ''),
        org: new Headers(init?.headers).get('X-Organizer-Id'),
        body: init?.body ? JSON.parse(String(init.body)) : undefined,
      }
      calls.push(call)
      const handled = handler(call)
      if (handled) return handled
      const key = `${call.method} ${call.path}`
      if (key === 'GET /org/me') return Response.json(me(role))
      if (key === 'GET /org/venues') return Response.json(VENUES)
      if (key === 'GET /org/venues/v-1') return Response.json(VENUE_DETAILS['v-1'])
      if (key === 'GET /org/venues/v-2') return Response.json(VENUE_DETAILS['v-2'])
      if (key === 'GET /org/series') return Response.json(SERIES)
      if (key === 'GET /org/products') return Response.json(products)
      if (key === 'GET /org/products/p-1') return Response.json(PASS_DETAIL)
      if (key === 'GET /org/products/p-2')
        return Response.json({
          ...PARIS_PASS,
          figures: { ...PASS_DETAIL.figures, events: [{ event_id: 7, title: 'Gala Atlas', starts_at: '2026-12-05T19:30:00Z', status: 'ON_SALE', issued: 3, expected: 3, pass_remaining: 0 }] },
          refunds_required: [],
        })
      if (key === 'GET /org/products/m-1') return Response.json({ ...MEMBERSHIP, figures: { paid_members: 42, revenue_mad: 840000 }, refunds_required: [] })
      if (key === 'POST /org/products') return Response.json({ ...PASS, ...(call.body as object), id: 'p-new' }, { status: 201 })
      if (key === 'PATCH /org/products/p-1') return Response.json({ ...PASS, ...(call.body as object) })
      if (key === 'GET /org/products/p-new') return Response.json({ ...PASS_DETAIL, id: 'p-new' })
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

function renderApp(path: string) {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const queryClient = createQueryClient()
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}

const writes = (calls: Call[]) => calls.filter((c) => c.method !== 'GET')
const cellTexts = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent)
const seriesOption = (s: Series) => i18n.t('events.series.option', { name: s.name, from: formatDay(s.starts_on, 'fr'), to: formatDay(s.ends_on, 'fr') })
const zoneOption = (name: string, capacity: number) => i18n.t('products.form.zoneOption', { name, capacity })
const dh = (centimes: number) => i18n.t('products.amount', { amount: formatDh(centimes, 'fr') })

async function pick(user: ReturnType<typeof userEvent.setup>, combobox: string, option: string) {
  await user.click(screen.getByRole('combobox', { name: combobox }))
  await user.click(await screen.findByRole('option', { name: option }))
}

// ---- List ----

it('list: tabs by kind, the PASS row (series · zone, price, sold/quota, status, close); query keys and header carry the organizer', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  const { queryClient } = renderApp('/products')
  expect(await screen.findByRole('heading', { name: fr.products.title })).toBeTruthy()
  expect(screen.getByRole('link', { name: fr.nav.products }).getAttribute('aria-current')).toBe('page')
  expect(screen.getByRole('tab', { name: fr.products.tabs.PASS }).getAttribute('aria-selected')).toBe('true')

  const link = await screen.findByRole('link', { name: 'Abonnement Tribune Est' })
  const row = link.closest('tr')!
  expect(cellTexts(row).slice(0, 4)).toEqual([
    'Abonnement Tribune EstSaison 2026-27 · Tribune Est',
    dh(150000),
    i18n.t('products.soldOfQuota', { sold: 4, quota: 4 }),
    fr.products.statuses.onSale,
  ])
  expect(link.getAttribute('href')).toBe('/products/p-1')

  await user.click(screen.getByRole('tab', { name: fr.products.tabs.MEMBERSHIP }))
  expect(await screen.findByText(fr.products.emptyTitle.MEMBERSHIP)).toBeTruthy()
  expect(screen.getByRole('button', { name: fr.products.new.MEMBERSHIP })).toBeTruthy()

  expect(calls.find((c) => c.path === '/org/products')!.org).toBe('org-a')
  expect(queryClient.getQueryData(['org', 'org-a', 'products'])).toEqual([PASS])
  expect(queryClient.getQueryCache().findAll({ queryKey: ['products'] })).toHaveLength(0) // never without the organizer
})

// ---- PASS form ----

it('PASS create: zod requires name, series, zone, a DH price, an integer quota and a close; then posts centimes and opens the product', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  const { router } = renderApp('/products')
  await user.click(await screen.findByRole('button', { name: fr.products.new.PASS }))
  const sheet = await screen.findByRole('dialog', { name: fr.products.newTitle.PASS })
  await within(sheet).findByLabelText(fr.products.form.name)
  expect(within(sheet).queryByText(fr.products.membershipWarning.title)).toBeNull()

  await user.type(within(sheet).getByLabelText(fr.products.form.quota), '2.5')
  await user.type(within(sheet).getByLabelText(fr.products.form.price), '0')
  await user.click(within(sheet).getByRole('button', { name: fr.common.create }))
  expect(await within(sheet).findByText(fr.products.form.nameInvalid)).toBeTruthy()
  expect(within(sheet).getByText(fr.products.form.seriesRequired)).toBeTruthy()
  expect(within(sheet).getByText(fr.products.form.zoneRequired)).toBeTruthy()
  expect(within(sheet).getByText(fr.products.form.priceInvalid)).toBeTruthy() // 0 DH: a price is > 0
  expect(within(sheet).getByText(fr.products.form.quotaInvalid)).toBeTruthy()
  expect(within(sheet).getByText(fr.products.form.closeRequired)).toBeTruthy()
  expect(writes(calls)).toEqual([])

  await user.type(within(sheet).getByLabelText(fr.products.form.name), 'Abonnement Tribune Est')
  await pick(user, fr.events.series.label, seriesOption(SERIES[0]!))
  // Zones grouped by venue.
  await user.click(screen.getByRole('combobox', { name: fr.products.form.zone }))
  const listbox = await screen.findByRole('listbox')
  expect(within(listbox).getByText('Grand Stade')).toBeTruthy()
  expect(within(listbox).getByText('Salle Pleyel')).toBeTruthy()
  await user.click(within(listbox).getByRole('option', { name: zoneOption('Tribune Est', 8000) }))
  const price = within(sheet).getByLabelText(fr.products.form.price)
  await user.clear(price)
  await user.type(price, '1500,5')
  const quota = within(sheet).getByLabelText(fr.products.form.quota)
  await user.clear(quota)
  await user.type(quota, '3')
  fireEvent.change(within(sheet).getByLabelText(fr.products.form.salesCloseAt), { target: { value: '2027-05-31T22:00' } })
  await user.click(within(sheet).getByRole('switch', { name: fr.products.form.onSale }))
  await user.click(within(sheet).getByRole('button', { name: fr.common.create }))

  await waitFor(() => expect(router.state.location.pathname).toBe('/products/p-new'))
  expect(writes(calls)).toEqual([
    {
      method: 'POST',
      path: '/org/products',
      org: 'org-a',
      body: {
        kind: 'PASS',
        name: 'Abonnement Tribune Est',
        price_mad: 150050, // "1500,5" DH, integer maths
        quota: 3,
        series_id: 's-1',
        zone_id: 1,
        sales_close_at: wallToUtc('2027-05-31T22:00', CASA), // the zone's venue's time zone
        on_sale: true,
        max_installments: 1, // default: in one payment
      },
    },
  ])
})

// ---- MEMBERSHIP form ----

it('MEMBERSHIP create: the paid-membership warning before the first one; valid_until must follow the close', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/products?kind=MEMBERSHIP')
  await user.click(await screen.findByRole('button', { name: fr.products.new.MEMBERSHIP }))
  const sheet = await screen.findByRole('dialog', { name: fr.products.newTitle.MEMBERSHIP })
  expect(within(sheet).getByRole('note').textContent).toBe(fr.products.membershipWarning.title + fr.products.membershipWarning.body)
  expect(within(sheet).queryByLabelText(fr.products.form.quota)).toBeNull()
  expect(within(sheet).queryByLabelText(fr.events.series.label)).toBeNull()
  expect(within(sheet).queryByLabelText(fr.products.form.installments)).toBeNull() // PASS only

  await user.type(within(sheet).getByLabelText(fr.products.form.name), 'Carte membre 2026-27')
  await user.type(within(sheet).getByLabelText(fr.products.form.price), '200')
  fireEvent.change(within(sheet).getByLabelText(fr.products.form.salesCloseAt), { target: { value: '2027-06-01T12:00' } })
  fireEvent.change(within(sheet).getByLabelText(fr.products.form.validUntil), { target: { value: '2027-05-31' } })
  await user.click(within(sheet).getByRole('button', { name: fr.common.create }))
  expect(await within(sheet).findByText(fr.products.form.validBeforeClose)).toBeTruthy()
  expect(writes(calls)).toEqual([])

  fireEvent.change(within(sheet).getByLabelText(fr.products.form.validUntil), { target: { value: '2027-06-30' } })
  await user.click(within(sheet).getByRole('button', { name: fr.common.create }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({
    kind: 'MEMBERSHIP',
    name: 'Carte membre 2026-27',
    price_mad: 20000,
    sales_close_at: wallToUtc('2027-06-01T12:00', CASA),
    valid_until: endOfDay('2027-06-30'), // paid through the end of that day (23:59:59)
    on_sale: false,
  })
})

it('MEMBERSHIP create: no warning once a membership product exists', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', [PASS, MEMBERSHIP])
  renderApp('/products?kind=MEMBERSHIP')
  expect(await screen.findByRole('link', { name: 'Carte membre 2026-27' })).toBeTruthy()
  await user.click(screen.getByRole('button', { name: fr.products.new.MEMBERSHIP }))
  const sheet = await screen.findByRole('dialog', { name: fr.products.newTitle.MEMBERSHIP })
  await within(sheet).findByLabelText(fr.products.form.name)
  expect(within(sheet).queryByRole('note')).toBeNull()
})

// ---- Edit ----

it('edit: series and zone fixed (disabled); PATCH sends only the changed fields', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/products/p-1')
  await screen.findByRole('heading', { name: 'Abonnement Tribune Est' })
  await user.click(screen.getByRole('button', { name: fr.common.edit }))
  const sheet = await screen.findByRole('dialog', { name: i18n.t('products.editTitle', { name: 'Abonnement Tribune Est' }) })
  const price = (await within(sheet).findByLabelText(fr.products.form.price)) as HTMLInputElement
  expect(price.value).toBe('1500')
  expect(screen.getByRole('combobox', { name: fr.events.series.label })).toHaveProperty('disabled', true)
  const zone = screen.getByRole('combobox', { name: fr.products.form.zone })
  expect(zone).toHaveProperty('disabled', true)
  expect(zone.textContent).toBe(i18n.t('products.form.zoneFixedOption', { name: 'Tribune Est', venue: 'Grand Stade' }))
  expect(within(sheet).queryByRole('button', { name: fr.events.series.new })).toBeNull()

  await user.clear(price)
  await user.type(price, '1500,00') // same amount, other spelling: not sent
  const quota = within(sheet).getByLabelText(fr.products.form.quota)
  await user.clear(quota)
  await user.type(quota, '5')
  await user.click(within(sheet).getByRole('switch', { name: fr.products.form.onSale }))
  await user.click(within(sheet).getByRole('button', { name: fr.common.save }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/org/products/p-1', org: 'org-a', body: { quota: 5, on_sale: false } }])
})

it('edit: "Paiement en plusieurs fois" is a whole number 1–10; PATCH sends only it', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/products/p-1')
  await screen.findByRole('heading', { name: 'Abonnement Tribune Est' })
  await user.click(screen.getByRole('button', { name: fr.common.edit }))
  const sheet = await screen.findByRole('dialog')
  const field = (await within(sheet).findByLabelText(fr.products.form.installments)) as HTMLInputElement
  expect(field.value).toBe('4')
  for (const bad of ['0', '11', '2.5', '']) {
    await user.clear(field)
    if (bad) await user.type(field, bad)
    await user.click(within(sheet).getByRole('button', { name: fr.common.save }))
    expect(await within(sheet).findByText(fr.products.form.installmentsInvalid)).toBeTruthy()
  }
  expect(writes(calls)).toEqual([])
  await user.clear(field)
  await user.type(field, '10')
  await user.click(within(sheet).getByRole('button', { name: fr.common.save }))
  await waitFor(() => expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/org/products/p-1', org: 'org-a', body: { max_installments: 10 } }]))
})

it('edit: a quota below the passes sold (422 on quota) says so on the field', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', [PASS], (c) =>
    c.method === 'PATCH' ? Response.json({ code: 'QUOTA_BELOW_SOLD', message: 'x', details: {} }, { status: 422 }) : undefined,
  )
  renderApp('/products/p-1')
  await screen.findByRole('heading', { name: 'Abonnement Tribune Est' })
  await user.click(screen.getByRole('button', { name: fr.common.edit }))
  const sheet = await screen.findByRole('dialog')
  const quota = await within(sheet).findByLabelText(fr.products.form.quota)
  await user.clear(quota)
  await user.type(quota, '2')
  await user.click(within(sheet).getByRole('button', { name: fr.common.save }))
  expect(await within(sheet).findByText(i18n.t('products.form.quotaBelowSold', { sold: 4 }))).toBeTruthy()
  expect(quota.getAttribute('aria-invalid')).toBe('true')
})

// ---- Detail ----

it('detail: KPIs, the per-match table with the amber row and its B2 Tarifs link, the refunds to make', async () => {
  stubApi()
  renderApp('/products/p-1')
  await screen.findByRole('heading', { name: 'Abonnement Tribune Est' })
  const kpi = (label: string) => screen.getByText(label).closest('[data-slot=card]')!.textContent
  expect(kpi(fr.products.kpi.active)).toContain('3')
  expect(kpi(fr.products.kpi.active)).toContain(i18n.t('products.soldOfQuotaLong', { sold: 4, quota: 4 }))
  expect(kpi(fr.products.kpi.pending)).toContain('1')
  expect(kpi(fr.products.kpi.revenue)).toContain(dh(450000))
  // 3b installments.
  expect(kpi(fr.products.kpi.suspended)).toContain('2')
  expect(kpi(fr.products.kpi.overdue)).toContain('3')
  expect(kpi(fr.products.kpi.due)).toContain(dh(337500))

  const short = screen.getByRole('link', { name: 'Atlas FC vs Rapid Oued' }).closest('tr')!
  expect(short.className).toContain('bg-warning-muted')
  expect(cellTexts(short)[2]).toBe(i18n.t('products.events.issuedOf', { issued: 2, expected: 3 }))
  const add = within(short).getByRole('link', { name: i18n.t('products.events.add', { count: 1, zone: 'Tribune Est' }) })
  expect(add.getAttribute('href')).toBe('/events/1?tab=prices')

  const full = screen.getByRole('link', { name: 'Atlas FC vs Wydad' }).closest('tr')!
  expect(full.className).not.toContain('bg-warning-muted')
  expect(within(full).queryAllByRole('link')).toHaveLength(1) // the match only

  // A draft with enough pass places: tickets come at publish, nothing to add.
  const draft = screen.getByRole('link', { name: 'Atlas FC vs Raja' }).closest('tr')!
  expect(cellTexts(draft)[4]).toBe(fr.products.events.atPublish)

  expect(screen.getByRole('heading', { name: fr.sales.refunds.title })).toBeTruthy()
  const refund = screen.getByText('pay-9').closest('tr')!
  expect(cellTexts(refund).slice(1)).toEqual([fr.sales.refunds.reasons.SOLD_OUT, 'pay-9', 'ord-9', dh(150000)])
})

it('detail: an unknown id goes back to the list with a notice', async () => {
  stubApi()
  const { router } = renderApp('/products/nope')
  expect(await screen.findByText(fr.products.notFound)).toBeTruthy()
  expect(router.state.location.pathname).toBe('/products')
})

// ---- FINANCE ----

it('FINANCE: reads the list and the details, with no write button', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE', [PASS, MEMBERSHIP])
  renderApp('/products')
  expect(await screen.findByRole('link', { name: 'Abonnement Tribune Est' })).toBeTruthy()
  expect(screen.getByText(fr.products.readOnly)).toBeTruthy()
  expect(screen.queryByRole('button', { name: fr.products.new.PASS })).toBeNull()
  await user.click(screen.getByRole('tab', { name: fr.products.tabs.MEMBERSHIP }))
  expect(screen.queryByRole('button', { name: fr.products.new.MEMBERSHIP })).toBeNull()
  await user.click(await screen.findByRole('link', { name: 'Carte membre 2026-27' }))

  await screen.findByRole('heading', { name: 'Carte membre 2026-27' })
  expect(screen.getByText(fr.products.kpi.paidMembers).closest('[data-slot=card]')!.textContent).toContain('42')
  expect(screen.queryByRole('button', { name: fr.common.edit })).toBeNull()
  expect(screen.getByText(fr.sales.refunds.empty)).toBeTruthy()
})

it('edit: a changed close time must be in the future', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/products/p-1')
  await screen.findByRole('heading', { name: 'Abonnement Tribune Est' })
  await user.click(screen.getByRole('button', { name: fr.common.edit }))
  const sheet = await screen.findByRole('dialog')
  const close = await within(sheet).findByLabelText(fr.products.form.salesCloseAt)
  fireEvent.change(close, { target: { value: '2020-01-01T10:00' } })
  await user.click(within(sheet).getByRole('button', { name: fr.common.save }))
  expect(await within(sheet).findByText(fr.products.form.closeInPast)).toBeTruthy()
  expect(writes(calls)).toEqual([])
})

it('a PASS at a venue in another time zone shows its times in that zone, on the list and the detail page', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', [PASS, PARIS_PASS])
  renderApp('/products')
  const row = (await screen.findByRole('link', { name: 'Pass Parterre' })).closest('tr')!
  expect(cellTexts(row)[4]).toBe(display(PARIS_PASS.sales_close_at, PARIS))
  expect(display(PARIS_PASS.sales_close_at, PARIS)).not.toBe(display(PARIS_PASS.sales_close_at, CASA))
  const casa = screen.getByRole('link', { name: 'Abonnement Tribune Est' }).closest('tr')!
  expect(cellTexts(casa)[4]).toBe(display(PASS.sales_close_at, CASA))

  await user.click(screen.getByRole('link', { name: 'Pass Parterre' }))
  await screen.findByRole('heading', { name: 'Pass Parterre' })
  expect(screen.getByText(display(PARIS_PASS.sales_close_at, PARIS))).toBeTruthy()
  const match = screen.getByRole('link', { name: 'Gala Atlas' }).closest('tr')!
  expect(cellTexts(match)[1]).toBe(display('2026-12-05T19:30:00Z', PARIS))
  // From the product row itself: no venue lookups.
  expect(calls.some((c) => c.path.startsWith('/org/venues'))).toBe(false)
})
