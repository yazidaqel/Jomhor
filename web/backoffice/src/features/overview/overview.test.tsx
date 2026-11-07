import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import type { EventDetail, EventSummary } from '@/features/events/api'
import type { EventSales } from '@/features/sales/api'
import { createQueryClient } from '@/lib/queryClient'
import { formatDh } from '@/lib/money'
import { session } from '@/lib/session'

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
const DAY = 86_400_000
// Relative to now: the countdown and "upcoming" depend on the clock.
const at = (ms: number) => new Date(Date.now() + ms).toISOString()
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira Alaoui', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const venue = { id: 'v-1', name: 'Grand Stade', timezone: CASA }
const summary = (id: number, title: string, startsAt: string, status: EventSummary['status'], sold = 0, quota = 0): EventSummary => ({
  id,
  title,
  event_type: 'FOOTBALL_MATCH',
  starts_at: startsAt,
  status,
  venue,
  quota,
  sold,
})
const NEXT_AT = at(3 * DAY + 2 * 3_600_000 + 5 * 60_000)
const LIST: EventSummary[] = [
  summary(9, 'Match de la saison passée', at(-30 * DAY), 'CLOSED', 30000, 30000),
  summary(1, 'Atlas FC vs Rapid Oued', NEXT_AT, 'ON_SALE', 1262, 39600),
  summary(2, 'Atlas FC vs Wydad', at(20 * DAY), 'DRAFT', 0, 20000),
]
const DETAIL = {
  id: 1,
  venue_id: 'v-1',
  timezone: CASA,
  series_id: null,
  event_type: 'FOOTBALL_MATCH',
  title: 'Atlas FC vs Rapid Oued',
  starts_at: NEXT_AT,
  doors_open_at: null,
  sale_starts_at: null,
  status: 'ON_SALE',
  refund_cutoff_at: null,
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: { competition: 'Botola Pro', matchday: 11 },
  created_at: '2026-11-01T10:00:00Z',
} satisfies EventDetail
const SALES: EventSales = {
  event_id: 1,
  quota: 39600,
  paid: 1250,
  held: 12,
  awaiting_cash: 0,
  refunded: 0,
  refunded_mad: 0,
  revenue_mad: 9_007_199_254_740_991, // 2^53 − 1 centimes: exact only through formatDh's bigint path
  zones: [
    { zone_id: 3, code: 'NORD', name: 'Virage Nord', quota: 12000, paid: 1000, held: 12, awaiting_cash: 0, refunded: 0, revenue_mad: 5_000_000 },
    { zone_id: 4, code: 'EST', name: 'Latérale Est', quota: 6000, paid: 250, held: 0, awaiting_cash: 0, refunded: 0, revenue_mad: 3_000_000 },
  ],
  channels: [
    { channel: 'APP', paid: 900, revenue_mad: 4_500_050 },
    { channel: 'WEB', paid: 350, revenue_mad: 3_500_000 },
    { channel: 'POS', paid: 0, revenue_mad: 0 },
  ],
  methods: (['CARD', 'WALLET', 'CASH_AGENCY', 'POS_CASH', 'FREE'] as const).map((method) => ({ method, paid: 0, revenue_mad: 0 })),
  refunds_required: [],
}

type Call = { path: string; org: string | null }

function stubApi(role: Role, events: EventSummary[] = LIST, detail: EventDetail = DETAIL, sales = (): Response => Response.json({ ...SALES, event_id: detail.id })) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      calls.push({ path, org: new Headers(init?.headers).get('X-Organizer-Id') })
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events') return Response.json(events)
      if (path === `/org/events/${detail.id}`) return Response.json(detail)
      if (path === `/org/events/${detail.id}/sales`) return sales()
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

function renderApp(path = '/') {
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
// Testing Library collapses the DOM's whitespace (FR groups digits with U+202F) to spaces, but not the string it looks for.
const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const greeting = new RegExp(`^(${fr.overview.greeting.morning}|${fr.overview.greeting.evening}), Samira$`)

it('ADMIN lands on Aperçu: the next event, its KPIs from /sales, zones, channels, what comes next and shortcuts', async () => {
  const calls = stubApi('ADMIN')
  renderApp()

  expect(await screen.findByRole('heading', { level: 1, name: greeting })).toBeTruthy()
  // The next upcoming event, not the closed one nor the later draft.
  expect(await screen.findByRole('heading', { level: 2, name: 'Atlas FC vs Rapid Oued' })).toBeTruthy()
  expect(await screen.findByText(spaced(dh(SALES.revenue_mad)))).toBeTruthy()
  expect(screen.getByText('Botola Pro · Journée 11')).toBeTruthy()
  expect(screen.getByText(fr.overview.sale.open)).toBeTruthy()

  // Countdown: 3 days, 2 hours (minutes depend on how long the test took to get here).
  const countdown = screen.getByText(fr.overview.countdown.label).parentElement!
  expect(within(countdown).getByText(i18n.t('overview.countdown.days', { count: 3 })).previousSibling?.textContent).toBe('3')
  expect(within(countdown).getByText(i18n.t('overview.countdown.hours', { count: 2 })).previousSibling?.textContent).toBe('2')

  // Ring: the same numbers in text for assistive tech.
  const ring = spaced(i18n.t('overview.ring.aria', { paid: 1250, quota: 39600, percent: '3,2 %' }))
  expect(screen.getByRole('img', { name: (name) => spaced(name) === ring })).toBeTruthy()
  expect(screen.getByText('1 250', { selector: '.tabular-nums' })).toBeTruthy()

  const zones = screen.getByRole('heading', { name: fr.overview.zones.title }).closest('[data-slot="card"]') as HTMLElement
  expect(within(zones).getByText(spaced(i18n.t('overview.zones.value', { paid: 1000, quota: 12000 })))).toBeTruthy()
  expect(within(zones).getByText('NORD')).toBeTruthy()
  const channels = screen.getByRole('heading', { name: fr.overview.channels.title }).closest('[data-slot="card"]') as HTMLElement
  expect(within(channels).getByText(spaced(dh(4_500_050)))).toBeTruthy()

  // Later: the draft, linked; the closed event is not listed.
  expect(screen.getByRole('link', { name: /Atlas FC vs Wydad/ }).getAttribute('href')).toBe('/events/2')
  expect(screen.queryByText('Match de la saison passée')).toBeNull()

  // Shortcuts: new event (writer), the next event's sales and prices tab.
  expect(screen.getByRole('link', { name: fr.events.new }).getAttribute('href')).toBe('/events/new')
  expect(screen.getByRole('link', { name: new RegExp(fr.overview.actions.newEventHint) }).getAttribute('href')).toBe('/events/new')
  expect(screen.getByRole('link', { name: new RegExp(fr.overview.actions.prices) }).getAttribute('href')).toBe('/events/1?tab=prices')
  expect(screen.getByRole('link', { name: new RegExp(fr.overview.actions.salesHint) }).getAttribute('href')).toBe('/events/1/sales')

  // Only the next event's sales are fetched, under the selected organizer.
  expect(calls.filter((c) => c.path.endsWith('/sales'))).toEqual([{ path: '/org/events/1/sales', org: 'org-a' }])
  const nav = within(screen.getByRole('navigation', { name: fr.nav.label }))
  expect(nav.getByRole('link', { name: fr.nav.overview }).getAttribute('aria-current')).toBe('page')
})

it('FINANCE sees the figures but no "Nouvel événement"', async () => {
  stubApi('FINANCE')
  renderApp()

  expect(await screen.findByText(spaced(dh(SALES.revenue_mad)))).toBeTruthy()
  expect(screen.queryByRole('link', { name: fr.events.new })).toBeNull()
  expect(screen.queryByRole('link', { name: new RegExp(fr.overview.actions.newEventHint) })).toBeNull()
  expect(screen.getByRole('link', { name: new RegExp(fr.overview.actions.salesHint) })).toBeTruthy()
  // The hero's secondary link reads "Voir", not "Modifier", for a read-only role.
  expect(screen.getByRole('link', { name: fr.common.view }).getAttribute('href')).toBe('/events/1')
  expect(screen.queryByRole('link', { name: fr.common.edit })).toBeNull()
})

it('a failed /sales keeps the hero and offers a retry, with no figures', async () => {
  const calls = stubApi('ADMIN', LIST, DETAIL, () => Response.json({ code: 'FORBIDDEN', message: 'x', details: {} }, { status: 403 }))
  renderApp()
  expect(await screen.findByText(fr.errors.FORBIDDEN)).toBeTruthy()
  expect(screen.getByRole('heading', { level: 2, name: 'Atlas FC vs Rapid Oued' })).toBeTruthy()
  expect(screen.getByRole('button', { name: fr.common.retry })).toBeTruthy()
  expect(screen.queryByText(fr.sales.kpi.revenue)).toBeNull()
  expect(calls.filter((c) => c.path.endsWith('/sales'))).toHaveLength(1) // a 4xx is not retried
})

it('sale window: a draft opens at publish or on its date; an event on sale with a later opening says when', async () => {
  const draft = { ...DETAIL, status: 'DRAFT' as const }
  stubApi('ADMIN', [summary(1, DETAIL.title, NEXT_AT, 'DRAFT')], draft)
  renderApp()
  expect(await screen.findByText(fr.overview.sale.atPublish)).toBeTruthy()
  cleanup()

  const opensAt = at(DAY)
  const date = new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(opensAt))
  for (const status of ['DRAFT', 'ON_SALE'] as const) {
    stubApi('ADMIN', [summary(1, DETAIL.title, NEXT_AT, status)], { ...DETAIL, status, sale_starts_at: opensAt })
    renderApp()
    expect(await screen.findByText(spaced(i18n.t('overview.sale.opensAt', { date })))).toBeTruthy()
    cleanup()
  }

  const since = at(-DAY)
  const sinceDate = new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(since))
  stubApi('ADMIN', LIST, { ...DETAIL, sale_starts_at: since })
  renderApp()
  expect(await screen.findByText(spaced(i18n.t('overview.sale.openSince', { date: sinceDate })))).toBeTruthy()
})

it('no event yet: the brand empty state, with the create action for writers only', async () => {
  stubApi('ADMIN', [])
  renderApp()
  expect(await screen.findByText(fr.overview.empty.title)).toBeTruthy()
  expect(screen.getByText(fr.overview.empty.body)).toBeTruthy()
  expect(screen.getByRole('link', { name: fr.events.new }).getAttribute('href')).toBe('/events/new')
  cleanup()

  stubApi('FINANCE', [])
  renderApp()
  expect(await screen.findByText(fr.overview.empty.bodyReadOnly)).toBeTruthy()
  expect(screen.queryByRole('link', { name: fr.events.new })).toBeNull()
})

it('an event with its gates open comes first, as live, even after T0', async () => {
  const live = { ...DETAIL, id: 5, title: 'Atlas FC vs FAR', starts_at: at(-3_600_000), status: 'GATES_OPEN' as const }
  stubApi('OPS', [...LIST, summary(5, live.title, live.starts_at, 'GATES_OPEN', 100, 1000)], live)
  renderApp()

  expect(await screen.findByRole('heading', { level: 2, name: live.title })).toBeTruthy()
  expect(screen.getByText(fr.overview.live)).toBeTruthy()
  expect(screen.getByText(fr.overview.countdown.started)).toBeTruthy()
})

it('only past or closed events: "aucun événement à venir"', async () => {
  stubApi('ADMIN', [LIST[0]!])
  renderApp()
  expect(await screen.findByText(fr.overview.noUpcoming.title)).toBeTruthy()
})

it('an unknown path lands on Aperçu', async () => {
  stubApi('ADMIN')
  const router = renderApp('/nope')
  expect(await screen.findByRole('heading', { level: 1, name: greeting })).toBeTruthy()
  expect(router.state.location.pathname).toBe('/')
})
