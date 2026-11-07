import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import type { EventDetail } from '@/features/events/api'
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'
import { RECONCILIATION_REFETCH_MS, sum, type Reconciliation } from './api'

beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  session.clear()
})

const CASA = 'Africa/Casablanca'
const T0 = '2026-11-14T19:00:00Z'
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
  starts_at: T0,
  doors_open_at: null,
  sale_starts_at: null,
  status: 'CLOSED',
  refund_cutoff_at: '2026-11-14T18:00:00Z',
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: {},
  created_at: '2026-11-01T10:00:00Z',
} satisfies EventDetail

/** Closed, one device of three still to ack: provisional. Zones and gates add up to the totals. */
const PROVISIONAL: Reconciliation = {
  event_id: 1,
  status: 'CLOSED',
  generated_at: '2026-11-14T21:30:00Z',
  final: false,
  devices: {
    assigned: 3,
    acked: 1,
    unsynced: [
      { device_id: 'd-4', label: 'PDA Porte 4-C', gate_code: 'P4', last_seen_at: '2026-11-14T20:47:00Z' },
      { device_id: 'd-5', label: 'PDA Porte 4-D', gate_code: 'P4', last_seen_at: null },
    ],
  },
  totals: {
    sold: 31200,
    entered: 28410,
    no_show: 2790,
    refused_by_result: { DUPLICATE: 38, WRONG_ZONE: 14, UNKNOWN: 5 },
    duplicates_late: 2,
    overrides_by_reason: { ID_CHECKED: 6, DAMAGED_TICKET: 3 },
  },
  zones: [
    { zone_id: 1, code: 'HON', name: 'Tribune d’honneur', sold: 2200, entered: 2100, no_show: 100, overrides: 1 },
    { zone_id: 2, code: 'NORD', name: 'Virage Nord', sold: 19000, entered: 17410, no_show: 1590, overrides: 6 },
    { zone_id: 3, code: 'EST', name: 'Tribune Est', sold: 10000, entered: 8900, no_show: 1100, overrides: 2 },
  ],
  gates: [
    { gate_id: 'g-P1', code: 'P1', name: 'Porte Honneur', zone_codes: ['HON'], entered: 2100, refused_by_result: { DUPLICATE: 3 }, duplicates_late: 0, overrides: 1 },
    { gate_id: 'g-P3', code: 'P3', name: 'Porte Nord-Est', zone_codes: ['NORD', 'EST'], entered: 12000, refused_by_result: { DUPLICATE: 20, WRONG_ZONE: 9 }, duplicates_late: 2, overrides: 6 },
    { gate_id: 'g-P4', code: 'P4', name: 'Porte Nord', zone_codes: ['NORD'], entered: 14310, refused_by_result: { DUPLICATE: 15, WRONG_ZONE: 5, UNKNOWN: 5 }, duplicates_late: 0, overrides: 2 },
  ],
}
const FINAL: Reconciliation = { ...PROVISIONAL, final: true, devices: { assigned: 3, acked: 3, unsynced: [] } }

type Call = { path: string; org: string | null; auth: string | null }

function stubApi(role: Role, recon: Reconciliation | ((n: number) => Reconciliation) = PROVISIONAL, event: EventDetail = EVENT) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const headers = new Headers(init?.headers)
      calls.push({ path, org: headers.get('X-Organizer-Id'), auth: headers.get('Authorization') })
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events/1') return Response.json(event)
      if (path === '/org/events/1/reconciliation') return Response.json(typeof recon === 'function' ? recon(reconCalls(calls)) : recon)
      if (path === '/org/events/1/live') return Response.json({ code: 'FORBIDDEN', message: 'x', details: {} }, { status: 403 })
      if (path === '/org/events/1/reconciliation.csv')
        return new Response(new Uint8Array([0xef, 0xbb, 0xbf, 0x6b, 0x69, 0x6e, 0x64]), {
          headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename="reconciliation-1-atlas-fc.csv"' },
        })
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

const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const reconCalls = (calls: Call[]) => calls.filter((c) => c.path === '/org/events/1/reconciliation').length
// Row headers (zone, gate, Total) then cells, in order.
const cells = (row: HTMLElement) => [...row.querySelectorAll('th, td')].map((c) => spaced(c.textContent ?? ''))
const n = (v: number) => spaced(v.toLocaleString('fr'))
const date = (iso: string) => spaced(new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso)))
const badge = () => document.querySelector('[data-final]') as HTMLElement

it('the fixtures add up: sold = entered + no-show, zones and gates sum to the totals', () => {
  const { totals, zones, gates } = PROVISIONAL
  expect(totals.entered + totals.no_show).toBe(totals.sold)
  for (const k of ['sold', 'entered', 'no_show'] as const) expect(zones.reduce((a, z) => a + z[k], 0)).toBe(totals[k])
  expect(zones.reduce((a, z) => a + z.overrides, 0)).toBe(sum(totals.overrides_by_reason))
  expect(gates.reduce((a, g) => a + g.entered, 0)).toBe(totals.entered)
  expect(gates.reduce((a, g) => a + g.duplicates_late, 0)).toBe(totals.duplicates_late)
  expect(gates.reduce((a, g) => a + g.overrides, 0)).toBe(sum(totals.overrides_by_reason))
  expect(gates.reduce((a, g) => a + sum(g.refused_by_result), 0)).toBe(sum(totals.refused_by_result))
})

it('FINANCE reads a provisional reconciliation: Provisoire badge with why, totals cards, zone and gate tables with totals, scoped to the organizer', async () => {
  const calls = stubApi('FINANCE')
  renderApp('/events/1/reconciliation')

  expect(await screen.findByRole('heading', { name: fr.reconciliation.zones.title })).toBeTruthy()
  expect(screen.getByRole('heading', { level: 1, name: spaced(`${EVENT.title} · ${date(T0)}`) })).toBeTruthy()
  expect(badge().textContent).toBe(fr.reconciliation.badge.provisional)
  expect(badge().dataset.final).toBe('false')
  expect(
    screen.getByText(spaced(`${i18n.t('reconciliation.why.unacked', { count: 2 })} ${i18n.t('reconciliation.generated', { date: date(PROVISIONAL.generated_at) })}`)),
  ).toBeTruthy()

  // Totals cards
  expect(screen.getByText(i18n.t('reconciliation.kpi.enteredHint', { percent: '91 %' }))).toBeTruthy()
  expect(screen.getByText(i18n.t('reconciliation.kpi.noShowHint', { percent: '8,9 %' }))).toBeTruthy()
  expect(screen.getByText('Déjà utilisé : 38 · Mauvaise porte : 14 · Inconnu : 5')).toBeTruthy()
  expect(screen.getByText('Identité vérifiée : 6 · Billet abîmé : 3')).toBeTruthy()
  expect(screen.getByText(fr.reconciliation.kpi.lateDuplicatesHint)).toBeTruthy()

  // Zone table: rows, then the server totals
  const zones = screen.getByRole('table', { name: fr.reconciliation.zones.title })
  expect(within(zones).getByRole('rowheader', { name: 'NORD Virage Nord' })).toBeTruthy()
  expect(within(zones).getAllByRole('columnheader').every((h) => h.getAttribute('scope') === 'col')).toBe(true)
  const zoneRows = within(zones).getAllByRole('row')
  expect(cells(zoneRows[2]!)).toEqual(['NORD Virage Nord', n(19000), n(17410), '92 %', n(1590), '6'])
  expect(cells(zoneRows.at(-1)!)).toEqual([fr.reconciliation.total, n(31200), n(28410), '91 %', n(2790), '9'])
  expect([...within(zones).getAllByRole('columnheader')].slice(1).every((h) => h.className.includes('text-right'))).toBe(true)

  // Gate table
  const gates = screen.getByRole('table', { name: fr.reconciliation.gates.title })
  expect(within(gates).getByRole('rowheader', { name: fr.reconciliation.total })).toBeTruthy()
  const gateRows = within(gates).getAllByRole('row')
  expect(cells(gateRows[2]!)).toEqual(['P3 Porte Nord-Est', 'NORD · EST', n(12000), '29', 'Déjà utilisé : 20 · Mauvaise porte : 9', '2', '6'])
  expect(cells(gateRows[3]!)).toEqual(['P4 Porte Nord', 'NORD', n(14310), '25', 'Déjà utilisé : 15 · Mauvaise porte : 5 · Inconnu : 5', '0', '2'])
  expect(cells(gateRows.at(-1)!)).toEqual([fr.reconciliation.total, '', n(28410), '57', '', '2', '9'])

  expect(calls.filter((c) => c.path.startsWith('/org/events/1')).every((c) => c.org === 'org-a')).toBe(true)
})

it('unsynced devices are a warning, not an error: label and last seen in the venue zone, never seen said so', async () => {
  stubApi('OPS')
  renderApp('/events/1/reconciliation')

  const section = (await screen.findByRole('heading', { name: i18n.t('reconciliation.unsynced.title', { count: 2 }) })).closest('section')!
  expect(within(section).getByText(fr.reconciliation.unsynced.body)).toBeTruthy()
  expect(within(section).getByText(spaced(i18n.t('reconciliation.unsynced.lastSeen', { label: 'PDA Porte 4-C', gate: 'P4', date: date('2026-11-14T20:47:00Z') })))).toBeTruthy()
  expect(within(section).getByText(i18n.t('reconciliation.unsynced.never', { label: 'PDA Porte 4-D', gate: 'P4' }))).toBeTruthy()
  expect(section.className).toContain('bg-warning-muted')
  // Right after the totals cards, before the tables (as B4's stale row).
  const zones = screen.getByRole('table', { name: fr.reconciliation.zones.title })
  expect(section.compareDocumentPosition(zones) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(section.className).not.toMatch(/destructive/)
  expect(screen.queryByRole('alert')).toBeNull()
})

it('before the close: provisional, says the event is not closed, and the devices confirm after the close', async () => {
  stubApi('ADMIN', { ...PROVISIONAL, status: 'ON_SALE' }, { ...EVENT, status: 'ON_SALE' })
  renderApp('/events/1/reconciliation')

  // The normal state before the close: one neutral line, no amber list.
  const line = await screen.findByText(i18n.t('reconciliation.unsynced.open', { count: 2 }))
  expect(line.className).toContain('text-muted-foreground')
  expect(line.className).not.toMatch(/warning/)
  expect(screen.queryByText(/PDA Porte 4-C/)).toBeNull()
  expect(document.querySelector('section[aria-labelledby="unsynced-title"]')).toBeNull()
  expect(badge().textContent).toBe(fr.reconciliation.badge.provisional)
  expect(screen.getByText(new RegExp(`^${fr.reconciliation.why.notClosed}`))).toBeTruthy()
})

it('final: Définitive badge, every device confirmed, no unsynced list', async () => {
  stubApi('FINANCE', FINAL)
  renderApp('/events/1/reconciliation')

  await screen.findByRole('heading', { name: fr.reconciliation.zones.title })
  expect(badge().textContent).toBe(fr.reconciliation.badge.final)
  expect(badge().className).toContain('bg-success-muted')
  expect(screen.getByText(new RegExp(`^${i18n.t('reconciliation.why.final', { count: 3 })}`))).toBeTruthy()
  expect(screen.queryByText(fr.reconciliation.unsynced.body)).toBeNull()
})

it.each([
  [0, "L'événement est clôturé et aucun appareil n'y était affecté : ces chiffres ne bougeront plus."],
  [1, "L'événement est clôturé et l'appareil affecté a confirmé : ces chiffres ne bougeront plus."],
])('final with %i assigned device(s) reads right', (count, copy) => {
  expect(i18n.t('reconciliation.why.final', { count })).toBe(copy)
})

it(`refetches every ${RECONCILIATION_REFETCH_MS} ms while provisional, and stops once final`, async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  // 1st and 2nd answers provisional, the 3rd final.
  const calls = stubApi('FINANCE', (k) => (k >= 3 ? FINAL : { ...PROVISIONAL, totals: { ...PROVISIONAL.totals, duplicates_late: k } }))
  renderApp('/events/1/reconciliation')
  await screen.findByRole('heading', { name: fr.reconciliation.zones.title })
  expect(reconCalls(calls)).toBe(1)

  await act(() => vi.advanceTimersByTimeAsync(RECONCILIATION_REFETCH_MS - 1))
  expect(reconCalls(calls)).toBe(1)
  await act(() => vi.advanceTimersByTimeAsync(1))
  await waitFor(() => expect(reconCalls(calls)).toBe(2))
  await act(() => vi.advanceTimersByTimeAsync(RECONCILIATION_REFETCH_MS))
  await waitFor(() => expect(badge().textContent).toBe(fr.reconciliation.badge.final))
  expect(reconCalls(calls)).toBe(3)

  await act(() => vi.advanceTimersByTimeAsync(RECONCILIATION_REFETCH_MS * 4))
  expect(reconCalls(calls)).toBe(3)
})

it('a failed background refresh keeps the last figures and says so, with their age', async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  stubApi('FINANCE')
  const ok = vi.mocked(fetch).getMockImplementation()!
  renderApp('/events/1/reconciliation')
  await screen.findByRole('heading', { name: fr.reconciliation.zones.title })

  // From now on /reconciliation is unreachable; the network-error retries run on real timers.
  vi.mocked(fetch).mockImplementation(async (input, init) => {
    if (String(input).endsWith('/reconciliation')) throw new TypeError('Failed to fetch')
    return ok(input, init)
  })
  await act(() => vi.advanceTimersByTimeAsync(RECONCILIATION_REFETCH_MS))
  const warning = await screen.findByText(/^Mise à jour impossible, nouvel essai automatique\. Dernière réponse il y a \d+ s\.$/, {}, { timeout: 8000 })
  expect(warning.getAttribute('role')).toBe('status')
  expect(warning.className).toContain('text-warning-foreground')
  expect(screen.getByText(n(28410), { selector: '[data-slot="card-content"] div' })).toBeTruthy() // the figures stay
  expect(badge().textContent).toBe(fr.reconciliation.badge.provisional)
})

it('Exporter CSV: GET the .csv with the organizer header, BOM intact, under the server filename', async () => {
  const user = userEvent.setup()
  const calls = stubApi('FINANCE')
  let blob: Blob | undefined
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: (b: Blob) => ((blob = b), 'blob:x'), revokeObjectURL: vi.fn() }))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  renderApp('/events/1/reconciliation')

  await user.click(await screen.findByRole('button', { name: fr.reconciliation.export }))

  await waitFor(() => expect(click).toHaveBeenCalled())
  const call = calls.find((c) => c.path === '/org/events/1/reconciliation.csv')!
  expect(call.org).toBe('org-a')
  expect(call.auth).toBe('Bearer tok-1')
  expect([...new Uint8Array(await blob!.arrayBuffer())].slice(0, 3)).toEqual([0xef, 0xbb, 0xbf])
  expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('reconciliation-1-atlas-fc.csv') // the server's name, not the fallback reconciliation-1.csv
})

it('no zone and no gate yet: each table says so with its own title', async () => {
  stubApi('FINANCE', { ...FINAL, zones: [], gates: [] })
  renderApp('/events/1/reconciliation')
  expect(await screen.findByText(fr.reconciliation.zones.emptyTitle)).toBeTruthy()
  expect(screen.getByText(fr.reconciliation.zones.empty)).toBeTruthy()
  expect(screen.getByText(fr.reconciliation.gates.emptyTitle)).toBeTruthy()
  expect(screen.queryByRole('table')).toBeNull()
})

it('a 403 on /reconciliation shows the FORBIDDEN copy, no figures', async () => {
  vi.stubGlobal('fetch', async (input: RequestInfo) => {
    const path = String(input).replace(/^\/v1/, '')
    if (path === '/org/me') return Response.json(me('ADMIN'))
    if (path === '/org/events/1') return Response.json(EVENT)
    return Response.json({ code: 'FORBIDDEN', message: 'x', details: {} }, { status: 403 })
  })
  renderApp('/events/1/reconciliation')
  expect(await screen.findByText(fr.errors.FORBIDDEN)).toBeTruthy()
  expect(screen.queryByText(fr.reconciliation.kpi.sold)).toBeNull()
  expect(document.querySelector('[data-final]')).toBeNull()
})

it.each(['/events/2/reconciliation', '/events/abc/reconciliation'])('%s (unknown event or bad id) goes back to the events list', async (path) => {
  stubApi('ADMIN')
  const router = renderApp(path)
  await waitFor(() => expect(router.state.location.pathname).toBe('/events'))
})

it('reachable from the event page header and from B4', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN')
  const router = renderApp('/events/1')
  await user.click(await screen.findByRole('link', { name: fr.reconciliation.title }))
  expect(router.state.location.pathname).toBe('/events/1/reconciliation')

  await act(() => router.navigate('/events/1/live'))
  await user.click(await screen.findByRole('link', { name: fr.reconciliation.title }))
  expect(router.state.location.pathname).toBe('/events/1/reconciliation')
  expect(await screen.findByRole('heading', { name: fr.reconciliation.zones.title })).toBeTruthy()
})
