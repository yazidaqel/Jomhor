import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import type { EventDetail, EventSummary } from '@/features/events/api'
import { createQueryClient } from '@/lib/queryClient'
import { formatDh } from '@/lib/money'
import { session } from '@/lib/session'
import type { EventSales, Refund } from './api'

beforeAll(() => {
  // Radix (the shell's organizer select) needs these; jsdom does not implement them.
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  session.clear()
})

const CASA = 'Africa/Casablanca'
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const EVENT = {
  id: 1,
  venue_id: 'v-1',
  timezone: CASA,
  series_id: null,
  event_type: 'FOOTBALL_MATCH',
  title: 'Atlas FC vs Rapid Oued',
  starts_at: '2026-11-14T19:00:00Z',
  doors_open_at: null,
  sale_starts_at: null,
  status: 'ON_SALE',
  refund_cutoff_at: '2026-11-14T18:00:00Z',
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: {},
  created_at: '2026-11-01T10:00:00Z',
} satisfies EventDetail
const LIST: EventSummary[] = [
  { id: 1, title: EVENT.title, event_type: 'FOOTBALL_MATCH', starts_at: EVENT.starts_at, status: 'ON_SALE', venue: { id: 'v-1', name: 'Grand Stade', timezone: CASA }, quota: 39600, sold: 1250 },
]
const SALES: EventSales = {
  event_id: 1,
  quota: 39600,
  paid: 1250,
  held: 12,
  awaiting_cash: 7,
  refunded: 3,
  refunded_mad: 45_150,
  revenue_mad: 9_007_199_254_740_991, // 2^53 − 1 centimes: exact only if never divided as a float
  zones: [{ zone_id: 3, code: 'NORD', name: 'Virage Nord', quota: 12000, paid: 1000, held: 12, awaiting_cash: 5, refunded: 2, revenue_mad: 5_000_000 }],
  channels: [
    { channel: 'APP', paid: 900, revenue_mad: 4_500_050 },
    { channel: 'WEB', paid: 350, revenue_mad: 0 },
    { channel: 'POS', paid: 0, revenue_mad: 0 },
  ],
  methods: [
    { method: 'CARD', paid: 800, revenue_mad: 4_000_050 },
    { method: 'WALLET', paid: 0, revenue_mad: 0 },
    { method: 'CASH_AGENCY', paid: 300, revenue_mad: 300_000 },
    { method: 'POS_CASH', paid: 150, revenue_mad: 150_000 },
    { method: 'FREE', paid: 0, revenue_mad: 0 },
  ],
  refunds_required: [
    { payment_id: 'pay-2', order_id: 'ord-2', amount_mad: 15_000, reason: 'SOLD_OUT', at: '2026-11-10T12:00:00Z' },
    { payment_id: 'pay-1', order_id: 'ord-1', amount_mad: 5_005, reason: 'SOMETHING_NEW', at: '2026-11-09T12:00:00Z' },
  ],
}

type Call = { method: string; path: string; org: string | null; body: unknown }

function stubApi(role: Role, sales: () => Response = () => Response.json(SALES), refunds: Refund[] = []) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const method = init?.method ?? 'GET'
      calls.push({ method, path, org: new Headers(init?.headers).get('X-Organizer-Id'), body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events') return Response.json(LIST)
      if (path === '/org/events/1') return Response.json(EVENT)
      if (path === '/org/events/1/sales') return sales()
      if (path === '/org/refunds?status=REQUIRED&event_id=1') return Response.json(refunds)
      if (method === 'POST' && path === '/org/refunds/r-1/done') return Response.json({ ...refunds[0], status: 'DONE' })
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

function renderApp(path: string) {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const dh = (centimes: number) => i18n.t('sales.amount', { amount: formatDh(centimes, 'fr') })
const n = (v: number) => v.toLocaleString('fr')
// Testing Library collapses the DOM's whitespace (FR groups digits with U+202F) to spaces, but not the string it looks for.
const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const nav = () => within(screen.getByRole('navigation', { name: fr.nav.label }))
const section = (title: string) => screen.getByRole('heading', { name: title }).closest('[data-slot="card"]') as HTMLElement
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent)

it('FINANCE opens B3: KPIs, per zone, per channel and refunds, amounts from centimes, scoped to the organizer', async () => {
  const calls = stubApi('FINANCE')
  renderApp('/events/1/sales')

  expect(await screen.findByText(spaced(dh(SALES.revenue_mad)))).toBeTruthy()
  expect(dh(SALES.revenue_mad)).toBe(`${new Intl.NumberFormat('fr', { minimumFractionDigits: 2 }).format('90071992547409.91')} DH`)
  expect(screen.getByRole('heading', { level: 1, name: fr.sales.title })).toBeTruthy()
  expect(screen.getByText(spaced(i18n.t('events.soldOfQuota', { sold: 1250, quota: 39600 })))).toBeTruthy()
  expect(screen.getByText(n(12), { selector: '.text-2xl' })).toBeTruthy()
  // Lot 3: AWAITING_CASH places are their own KPI, not inside held; refunded tickets under the (gross) revenue.
  const awaiting = screen.getByText(fr.sales.kpi.awaitingCash, { selector: 'span' }).closest('[data-slot="card"]') as HTMLElement
  expect(within(awaiting).getByText(n(7), { selector: '.text-2xl' })).toBeTruthy()
  expect(screen.getByText(spaced(i18n.t('sales.kpi.refunded', { count: 3, amount: formatDh(45_150, 'fr') })))).toBeTruthy()
  expect(i18n.t('sales.kpi.refunded', { count: 3, amount: formatDh(45_150, 'fr') })).toBe('Dont 3 billets remboursés : 451,50 DH.')

  const [zone] = within(section(fr.sales.zones.title)).getAllByRole('row').slice(1)
  expect(cells(zone!)).toEqual(['NORD Virage Nord', n(12000), n(1000), n(12), n(5), n(2), dh(5_000_000)])

  // Every method, zeros included, in the API's order.
  const methods = within(section(fr.sales.byMethod.title)).getAllByRole('row').slice(1).map(cells)
  expect(methods).toEqual([
    [fr.sales.methods.CARD, n(800), dh(4_000_050)],
    [fr.sales.methods.WALLET, n(0), dh(0)],
    [fr.sales.methods.CASH_AGENCY, n(300), dh(300_000)],
    [fr.sales.methods.POS_CASH, n(150), dh(150_000)],
    [fr.sales.methods.FREE, n(0), dh(0)],
  ])

  const channels = within(section(fr.sales.channels.title)).getAllByRole('row').slice(1).map(cells)
  expect(channels).toEqual([
    [fr.sales.channels.names.APP, n(900), dh(4_500_050)],
    [fr.sales.channels.names.WEB, n(350), dh(0)],
    [fr.sales.channels.names.POS, n(0), dh(0)],
  ])
  expect(dh(0)).toMatch(/^0,00\sDH$/u)

  const refunds = within(section(fr.sales.refunds.title)).getAllByRole('row').slice(1).map(cells)
  const at = (iso: string) => new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))
  expect(refunds).toEqual([
    [at('2026-11-10T12:00:00Z'), fr.sales.refunds.reasons.SOLD_OUT, 'pay-2', 'ord-2', dh(15_000)],
    [at('2026-11-09T12:00:00Z'), 'SOMETHING_NEW', 'pay-1', 'ord-1', dh(5_005)], // unknown reason: the code as sent
  ])

  expect(calls.find((c) => c.path === '/org/events/1/sales')!.org).toBe('org-a')
  expect(nav().getByRole('link', { name: fr.nav.sales }).getAttribute('aria-current')).toBe('page')
  expect(nav().getByRole('link', { name: fr.nav.events }).getAttribute('aria-current')).toBeNull()
})

it('empty states: no refunds to make, no paid ticket yet, no zone', async () => {
  stubApi('ADMIN', () =>
    Response.json({
      ...SALES,
      paid: 0,
      held: 0,
      awaiting_cash: 0,
      refunded: 0,
      refunded_mad: 0,
      revenue_mad: 0,
      zones: [],
      channels: (['APP', 'WEB', 'POS'] as const).map((channel) => ({ channel, paid: 0, revenue_mad: 0 })),
      methods: (['CARD', 'WALLET', 'CASH_AGENCY', 'POS_CASH', 'FREE'] as const).map((method) => ({ method, paid: 0, revenue_mad: 0 })),
      refunds_required: [],
    }),
  )
  renderApp('/events/1/sales')

  expect(await screen.findByText(fr.sales.refunds.empty)).toBeTruthy()
  expect(await screen.findByText(fr.sales.refunds.tickets.empty)).toBeTruthy()
  expect(within(section(fr.sales.channels.title)).getByText(fr.sales.channels.empty)).toBeTruthy() // three zero rows: the notice, not a table
  expect(within(section(fr.sales.channels.title)).queryByRole('table')).toBeNull()
  expect(within(section(fr.sales.byMethod.title)).getByText(fr.sales.channels.empty)).toBeTruthy() // same rule per method
  expect(within(section(fr.sales.byMethod.title)).queryByRole('table')).toBeNull()
  expect(screen.getByText(i18n.t('sales.kpi.refunded', { count: 0, amount: formatDh(0, 'fr') }))).toBeTruthy()
  expect(screen.getByText(fr.sales.zones.empty)).toBeTruthy()
  expect(within(section(fr.sales.refunds.title)).queryByRole('table')).toBeNull()
})

it('a 403 shows the FORBIDDEN copy, no figures, and is not retried', async () => {
  const calls = stubApi('OPS', () => Response.json({ code: 'FORBIDDEN', message: 'x', details: {} }, { status: 403 }))
  renderApp('/events/1/sales')

  expect(await screen.findByText(fr.errors.FORBIDDEN)).toBeTruthy()
  expect(screen.queryByText(fr.sales.kpi.revenue)).toBeNull()
  expect(calls.filter((c) => c.path === '/org/events/1/sales')).toHaveLength(1)
})

it('an unknown or other organizer’s event goes back to the list with the not-found notice', async () => {
  stubApi('ADMIN')
  const router = renderApp('/events/7/sales')
  await waitFor(() => expect(router.state.location.pathname).toBe('/events'))
  expect(await screen.findByText(fr.events.notFound)).toBeTruthy()
})

it('sidebar Ventes is enabled: event list → the event’s sales page', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE')
  const router = renderApp('/events')

  await screen.findByRole('navigation', { name: fr.nav.label })
  await user.click(await nav().findByRole('link', { name: fr.nav.sales }))
  expect(await screen.findByText(fr.sales.indexDescription)).toBeTruthy()
  await user.click(await screen.findByRole('link', { name: EVENT.title }))
  expect(router.state.location.pathname).toBe('/events/1/sales')
  expect(await screen.findByText(spaced(dh(SALES.revenue_mad)))).toBeTruthy()
})

const REFUND: Refund = {
  id: 'r-1',
  event_id: 1,
  ticket_id: 't-3',
  ticket_no: '9F8E 7D6C',
  order_id: 'ord-3',
  amount_mad: 15_050,
  method: 'CASH_AGENCY',
  reason: 'Fan malade',
  status: 'REQUIRED',
  requested_by: { id: 'u-2', name: 'Karim' },
  requested_at: '2026-11-11T09:30:00Z',
  done_by: null,
  done_at: null,
  reference: null,
}

it.each(['FINANCE', 'ADMIN'] as const)('%s marks a ticket refund done: the reference is required and sent', async (role) => {
  const user = userEvent.setup()
  const calls = stubApi(role, undefined, [REFUND, { ...REFUND, id: 'r-2', ticket_no: '1A2B 3C4D', method: null, amount_mad: 5_005 }])
  renderApp('/events/1/sales')

  const row = (await screen.findByText('9F8E 7D6C')).closest('tr') as HTMLElement
  const at = new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(REFUND.requested_at))
  expect(cells(row)).toEqual([at, '9F8E 7D6C', 'Fan malade', fr.sales.methods.CASH_AGENCY, 'Karim', dh(15_050), fr.sales.refunds.tickets.markDone])
  expect(dh(15_050)).toMatch(/^150,50\sDH$/u)
  expect(cells(screen.getByText('1A2B 3C4D').closest('tr') as HTMLElement)[3]).toBe(fr.sales.refunds.tickets.noMethod)
  expect(calls.find((c) => c.path.startsWith('/org/refunds'))!.org).toBe('org-a')

  await user.click(within(row).getByRole('button', { name: i18n.t('sales.refunds.tickets.markDoneLabel', { ticket: '9F8E 7D6C' }) }))
  const dialog = await screen.findByRole('dialog', { name: i18n.t('sales.refunds.tickets.done.title', { ticket: '9F8E 7D6C' }) })
  expect(within(dialog).getByText(spaced(i18n.t('sales.refunds.tickets.done.description', { amount: formatDh(15_050, 'fr'), method: fr.sales.methods.CASH_AGENCY })))).toBeTruthy()
  const submit = within(dialog).getByRole('button', { name: fr.sales.refunds.tickets.done.submit })
  await user.click(submit)
  expect(await within(dialog).findByText(fr.sales.refunds.tickets.done.referenceInvalid)).toBeTruthy()
  expect(calls.filter((c) => c.method === 'POST')).toEqual([])

  await user.type(within(dialog).getByRole('textbox', { name: fr.sales.refunds.tickets.done.reference }), '  REC-2026-0042 ')
  await user.click(submit)
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(calls.filter((c) => c.method === 'POST')).toEqual([{ method: 'POST', path: '/org/refunds/r-1/done', org: 'org-a', body: { reference: 'REC-2026-0042' } }])
  expect(await screen.findByText(i18n.t('sales.refunds.tickets.done.toast', { ticket: '9F8E 7D6C' }))).toBeTruthy()
  await waitFor(() => expect(calls.filter((c) => c.path.startsWith('/org/refunds?'))).toHaveLength(2)) // refetched
})

it('OPS sees the ticket refunds to make but cannot mark them done', async () => {
  stubApi('OPS', undefined, [REFUND])
  renderApp('/events/1/sales')
  const row = (await screen.findByText('9F8E 7D6C')).closest('tr') as HTMLElement
  expect(within(row).queryByRole('button')).toBeNull()
  expect(screen.queryByRole('button', { name: new RegExp(fr.sales.refunds.tickets.markDone) })).toBeNull()
})
