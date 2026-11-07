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
import type { Venue } from '@/features/venues/api'
import { createQueryClient } from '@/lib/queryClient'
import { formatDh } from '@/lib/money'
import { session } from '@/lib/session'
import { addDays, dayRange, todayIn } from '@/lib/time'
import type { CashReport } from './api'

beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  session.clear()
})

const CASA = 'Africa/Casablanca'
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const venue = (timezone: string): Venue => ({ id: 'v-1', name: 'Grand Stade', city: 'Casablanca', capacity: 45000, timezone })

const REPORT: CashReport = {
  from: '2026-11-01T00:00:00Z',
  to: '2026-11-08T00:00:00Z',
  timezone: CASA,
  days: [
    { day: '2026-11-03', collector: 'POS', collector_name: 'Guichet', method: 'POS_CASH', payments: 12, amount_mad: 125_050 },
    { day: '2026-11-03', collector: 'FAKE_CASH', collector_name: '', method: 'CASH_AGENCY', payments: 3, amount_mad: 45_000 },
    { day: '2026-11-04', collector: 'CASHPLUS', collector_name: 'Cash Plus', method: 'CASH_AGENCY', payments: 1, amount_mad: 15_000 },
  ],
  sessions: [
    {
      id: 's-1',
      label: 'Guichet 1',
      staff: { id: 'st-1', name: 'Karim' },
      opened_at: '2026-11-03T08:00:00Z',
      closed_at: '2026-11-03T18:00:00Z',
      orders: 10,
      tickets: 14,
      prints: 14,
      expected_mad: 100_050,
      declared_mad: 99_050,
      variance_mad: -1000,
    },
    {
      id: 's-2',
      label: 'Guichet 2',
      staff: { id: 'st-2', name: 'Nadia' },
      opened_at: '2026-11-03T08:05:00Z',
      closed_at: '2026-11-03T18:10:00Z',
      orders: 2,
      tickets: 2,
      prints: 2,
      expected_mad: 25_000,
      declared_mad: 25_000,
      variance_mad: 0,
    },
    {
      id: 's-3',
      label: 'Guichet 1',
      staff: { id: 'st-1', name: 'Karim' },
      opened_at: '2026-11-04T08:00:00Z',
      closed_at: null,
      orders: 0,
      tickets: 0,
      prints: 0,
      expected_mad: 0,
      declared_mad: null,
      variance_mad: null,
    },
    {
      id: 's-4',
      label: 'Guichet 3',
      staff: { id: 'st-2', name: 'Nadia' },
      opened_at: '2026-11-04T09:00:00Z',
      closed_at: '2026-11-04T17:00:00Z',
      orders: 1,
      tickets: 1,
      prints: 1,
      expected_mad: 10_000,
      declared_mad: 10_500,
      variance_mad: 500,
    },
  ],
  totals: { collected_mad: 185_050, declared_mad: 124_050, variance_mad: -1000, open_sessions: 1 },
}

type Call = { path: string; org: string | null; auth: string | null }

const CSV_NAME = 'encaissements-2026-11-01-2026-11-07.csv' // 04: encaissements-{from}-{to}.csv, local dates

function stubApi(
  role: Role,
  { report = REPORT, venues = [venue(CASA)], csvName = CSV_NAME }: { report?: CashReport; venues?: Venue[] | Response; csvName?: string | null } = {},
) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const headers = new Headers(init?.headers)
      calls.push({ path, org: headers.get('X-Organizer-Id'), auth: headers.get('Authorization') })
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events') return Response.json([])
      if (path === '/org/venues') return venues instanceof Response ? venues : Response.json(venues)
      if (path.startsWith('/org/finance/cash.csv?'))
        return new Response(new Uint8Array([0xef, 0xbb, 0xbf, 0x6b, 0x69, 0x6e, 0x64]), {
          headers: { 'Content-Type': 'text/csv', ...(csvName && { 'Content-Disposition': `attachment; filename="${csvName}"` }) },
        })
      if (path.startsWith('/org/finance/cash?')) return Response.json(report)
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
const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const nav = () => within(screen.getByRole('navigation', { name: fr.nav.label }))
const section = (title: string) => screen.getByRole('heading', { name: title }).closest('[data-slot="card"]') as HTMLElement
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent)
const cashCalls = (calls: Call[]) => calls.filter((c) => c.path.startsWith('/org/finance/cash?')).map((c) => Object.fromEntries(new URLSearchParams(c.path.split('?')[1])))
const at = (iso: string) => new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))

it('FINANCE opens Finance › Encaissements: last 7 local days, per day × collector with totals, every till with its gap', async () => {
  const user = userEvent.setup()
  const calls = stubApi('FINANCE')
  const router = renderApp('/events')

  await screen.findByRole('navigation', { name: fr.nav.label })
  await user.click(await nav().findByRole('link', { name: fr.nav.finance }))
  expect(router.state.location.pathname).toBe('/finance/cash')
  expect(await screen.findByRole('heading', { name: fr.finance.cash.days.title })).toBeTruthy()
  expect(nav().getByRole('link', { name: fr.nav.finance }).getAttribute('aria-current')).toBe('page')

  // Default range: today and the 6 days before, in the venue zone, sent as RFC 3339 bounds with that zone.
  const today = todayIn(CASA)
  const [query] = cashCalls(calls)
  expect(query).toEqual({ ...dayRange(addDays(today, -6), today, CASA), timezone: CASA })
  expect((screen.getByLabelText(fr.finance.cash.range.from) as HTMLInputElement).value).toBe(addDays(today, -6))
  expect((screen.getByLabelText(fr.finance.cash.range.to) as HTMLInputElement).value).toBe(today)
  expect(calls.find((c) => c.path.startsWith('/org/finance/cash?'))!.org).toBe('org-a')

  const days = within(section(fr.finance.cash.days.title)).getAllByRole('row').slice(1).map(cells)
  const day = (d: string) => new Intl.DateTimeFormat('fr', { timeZone: 'UTC', dateStyle: 'medium' }).format(Date.parse(d))
  expect(days).toEqual([
    [day('2026-11-03'), 'Guichet', fr.sales.methods.POS_CASH, '12', dh(125_050)],
    [day('2026-11-03'), fr.finance.cash.collectors.FAKE_CASH, fr.sales.methods.CASH_AGENCY, '3', dh(45_000)], // no name sent: the copy map
    [day('2026-11-04'), 'Cash Plus', fr.sales.methods.CASH_AGENCY, '1', dh(15_000)], // the API's display name
    [fr.finance.cash.total, '16', dh(185_050)],
  ])
  expect(dh(125_050)).toMatch(/^1\s250,50\sDH$/u) // integer maths: 125050 centimes

  const sessions = within(section(fr.finance.cash.sessions.title)).getAllByRole('row').slice(1)
  expect(sessions.map(cells)).toEqual([
    ['Guichet 1', 'Karim', at('2026-11-03T08:00:00Z'), at('2026-11-03T18:00:00Z'), '10', dh(100_050), dh(99_050), spaced(i18n.t('sales.amount', { amount: formatDh(-1000, 'fr', 'exceptZero') }))],
    ['Guichet 2', 'Nadia', at('2026-11-03T08:05:00Z'), at('2026-11-03T18:10:00Z'), '2', dh(25_000), dh(25_000), fr.finance.cash.noGap],
    ['Guichet 1', 'Karim', at('2026-11-04T08:00:00Z'), fr.finance.cash.sessions.open, '0', dh(0), fr.finance.cash.sessions.atClose, fr.finance.cash.sessions.atClose],
    ['Guichet 3', 'Nadia', at('2026-11-04T09:00:00Z'), at('2026-11-04T17:00:00Z'), '1', dh(10_000), dh(10_500), spaced(i18n.t('sales.amount', { amount: formatDh(500, 'fr', 'exceptZero') }))],
  ])
  expect(cells(sessions[3]!).at(-1)).toMatch(/^\+5,00\sDH$/u) // a surplus is a gap too: signed, red
  // Red only when the gap is not zero.
  const gapCell = (row: HTMLElement) => within(row).getAllByRole('cell').at(-1)!
  expect(gapCell(sessions[0]!).className).toContain('text-destructive')
  expect(gapCell(sessions[3]!).className).toContain('text-destructive')
  expect(gapCell(sessions[1]!).className).not.toContain('text-destructive')
  expect(gapCell(sessions[2]!).className).not.toContain('text-destructive')

  // Totals from the server.
  const kpi = (label: string) => within(screen.getByText(label, { selector: 'span' }).closest('[data-slot="card"]') as HTMLElement)
  expect(kpi(fr.finance.cash.kpi.collected).getByText(spaced(dh(185_050)))).toBeTruthy()
  expect(kpi(fr.finance.cash.kpi.declared).getByText(spaced(dh(124_050)))).toBeTruthy()
  expect(kpi(fr.finance.cash.kpi.variance).getByText(/^-10,00\sDH$/u).className).toContain('text-destructive')
  expect(kpi(fr.finance.cash.kpi.open).getByText('1')).toBeTruthy()
})

it('a local-date range becomes RFC 3339 bounds in the venue zone, a clock change included; bad ranges are not sent', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', { report: { ...REPORT, totals: { ...REPORT.totals, variance_mad: 0 } } })
  renderApp('/finance/cash')

  await screen.findByRole('heading', { name: fr.finance.cash.days.title })
  // tzdata 2026c: Casablanca is UTC+00:00 from 15 February to 22 March 2026, UTC+01:00 then until 20 September.
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.from), { target: { value: '2026-03-20' } })
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.to), { target: { value: '2026-03-23' } })
  await user.click(screen.getByRole('button', { name: fr.finance.cash.range.submit }))
  await waitFor(() => expect(cashCalls(calls)).toHaveLength(2))
  expect(cashCalls(calls)[1]).toEqual({ from: '2026-03-20T00:00:00.000Z', to: '2026-03-23T23:00:00.000Z', timezone: CASA })
  // No gap overall: no red, the words instead.
  const variance = screen.getByText(fr.finance.cash.kpi.variance, { selector: 'span' }).closest('[data-slot="card"]') as HTMLElement
  expect(within(variance).getByText(fr.finance.cash.noGap).className).not.toContain('text-destructive')

  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.to), { target: { value: '2026-03-19' } })
  await user.click(screen.getByRole('button', { name: fr.finance.cash.range.submit }))
  expect(await screen.findByText(fr.finance.cash.range.order)).toBeTruthy()

  // At most 92 local days, both ends included: 1 July to 1 October is 93 and refused.
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.from), { target: { value: '2026-07-01' } })
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.to), { target: { value: '2026-10-01' } })
  await user.click(screen.getByRole('button', { name: fr.finance.cash.range.submit }))
  expect(await screen.findByText(fr.finance.cash.range.tooLong)).toBeTruthy()

  // 1 July to 30 September is 92 local days: sent, although 20 September (UTC+01:00 → UTC+00:00) lasts 25 h.
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.to), { target: { value: '2026-09-30' } })
  await user.click(screen.getByRole('button', { name: fr.finance.cash.range.submit }))
  await waitFor(() => expect(cashCalls(calls)).toHaveLength(3))
  expect(cashCalls(calls)[2]).toEqual({ from: '2026-06-30T23:00:00.000Z', to: '2026-10-01T00:00:00.000Z', timezone: CASA })
  expect(screen.queryByText(fr.finance.cash.range.tooLong)).toBeNull()
  expect(screen.queryByText(fr.finance.cash.range.tooLong)).toBeNull()
})

it('the zone is the venues’ zone: Europe/Paris midnight is 23:00 UTC the day before in November', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS', { venues: [venue('Europe/Paris'), { ...venue('Europe/Paris'), id: 'v-2' }] })
  renderApp('/finance/cash')

  await screen.findByRole('heading', { name: fr.finance.cash.days.title })
  expect(cashCalls(calls)[0]!.timezone).toBe('Europe/Paris')
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.from), { target: { value: '2026-11-01' } })
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.to), { target: { value: '2026-11-01' } })
  await user.click(screen.getByRole('button', { name: fr.finance.cash.range.submit }))
  await waitFor(() => expect(cashCalls(calls)).toHaveLength(2))
  expect(cashCalls(calls)[1]).toEqual({ from: '2026-10-31T23:00:00.000Z', to: '2026-11-01T23:00:00.000Z', timezone: 'Europe/Paris' })
})

it('venues in two zones: the report falls back to Africa/Casablanca', async () => {
  const calls = stubApi('ADMIN', { venues: [venue('Europe/Paris'), { ...venue(CASA), id: 'v-2' }] })
  renderApp('/finance/cash')
  await screen.findByRole('heading', { name: fr.finance.cash.days.title })
  expect(cashCalls(calls)[0]!.timezone).toBe(CASA)
})

it('Exporter CSV: GET the .csv for the shown range with the organizer header, BOM intact, under the server filename', async () => {
  const user = userEvent.setup()
  const calls = stubApi('FINANCE')
  let blob: Blob | undefined
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: (b: Blob) => ((blob = b), 'blob:x'), revokeObjectURL: vi.fn() }))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  renderApp('/finance/cash')

  await screen.findByRole('heading', { name: fr.finance.cash.days.title })
  await user.click(screen.getByRole('button', { name: fr.finance.cash.export }))

  await waitFor(() => expect(click).toHaveBeenCalled())
  const call = calls.find((c) => c.path.startsWith('/org/finance/cash.csv?'))!
  expect(call.org).toBe('org-a')
  expect(call.auth).toBe('Bearer tok-1')
  expect(call.path.split('?')[1]).toBe(calls.find((c) => c.path.startsWith('/org/finance/cash?'))!.path.split('?')[1])
  expect([...new Uint8Array(await blob!.arrayBuffer())].slice(0, 3)).toEqual([0xef, 0xbb, 0xbf])
  expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe(CSV_NAME)
})

it('Exporter CSV with no Content-Disposition: saved as encaissements-{from}-{to}.csv, the shown local dates', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE', { csvName: null })
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: vi.fn() }))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  renderApp('/finance/cash')

  await screen.findByRole('heading', { name: fr.finance.cash.days.title })
  await user.click(screen.getByRole('button', { name: fr.finance.cash.export }))
  await waitFor(() => expect(click).toHaveBeenCalled())
  const today = todayIn(CASA)
  expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe(`encaissements-${addDays(today, -6)}-${today}.csv`)
})

it('/org/venues fails: the error and a retry, no report in a guessed zone', async () => {
  const calls = stubApi('FINANCE', { venues: Response.json({ code: 'FORBIDDEN', message: 'x', details: {} }, { status: 403 }) })
  renderApp('/finance/cash')
  expect(await screen.findByText(fr.errors.FORBIDDEN)).toBeTruthy()
  expect(screen.getByRole('button', { name: fr.common.retry })).toBeTruthy()
  expect(cashCalls(calls)).toEqual([])
})

it('both dates invalid: each points at the range message, which exists', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE')
  renderApp('/finance/cash')
  await screen.findByRole('heading', { name: fr.finance.cash.days.title })
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.from), { target: { value: '' } })
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.to), { target: { value: '' } })
  await user.click(screen.getByRole('button', { name: fr.finance.cash.range.submit }))
  const message = await screen.findByText(fr.finance.cash.range.invalid)
  for (const label of [fr.finance.cash.range.from, fr.finance.cash.range.to]) {
    const input = screen.getByLabelText(label)
    expect(input.getAttribute('aria-invalid')).toBe('true')
    expect(document.getElementById(input.getAttribute('aria-describedby')!)).toBe(message)
  }
})

it('empty period: both tables say so', async () => {
  stubApi('FINANCE', { report: { ...REPORT, days: [], sessions: [], totals: { collected_mad: 0, declared_mad: 0, variance_mad: 0, open_sessions: 0 } } })
  renderApp('/finance/cash')
  expect(await screen.findByText(fr.finance.cash.days.emptyTitle)).toBeTruthy()
  expect(screen.getByText(fr.finance.cash.days.empty)).toBeTruthy()
  expect(screen.getByText(fr.finance.cash.sessions.emptyTitle)).toBeTruthy()
  expect(screen.getByText(fr.finance.cash.sessions.empty)).toBeTruthy()
  expect(screen.queryByRole('table')).toBeNull()
})

it.each(['ADMIN', 'OPS', 'FINANCE'] as const)('%s has the Finance entry', async (role) => {
  stubApi(role)
  renderApp('/events')
  await screen.findByRole('navigation', { name: fr.nav.label })
  expect(await nav().findByRole('link', { name: fr.nav.finance })).toBeTruthy()
})

it('CASHIER has no Finance entry and lands on the Guichet', async () => {
  const calls = stubApi('CASHIER')
  const router = renderApp('/finance/cash')
  await waitFor(() => expect(router.state.location.pathname).toBe('/pos'))
  await screen.findByRole('navigation', { name: fr.nav.label })
  expect(nav().queryByRole('link', { name: fr.nav.finance })).toBeNull()
  expect(cashCalls(calls)).toEqual([])
})

it.each(['GATE', 'SUPERVISOR'] as const)('%s never gets in', async (role) => {
  const calls = stubApi(role)
  renderApp('/finance/cash')
  expect(await screen.findByText(fr.shell.noAccessTitle)).toBeTruthy()
  expect(cashCalls(calls)).toEqual([])
})
