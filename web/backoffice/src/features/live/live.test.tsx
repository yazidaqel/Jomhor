import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { focusManager, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import type { EventDetail, EventStatus, EventSummary } from '@/features/events/api'
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'
import { charge, chargeLevel, LIVE_REFETCH_MS, type Live } from './api'

beforeAll(() => {
  // Radix (the shell's organizer select, the dialog) needs these; jsdom does not implement them.
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  focusManager.setFocused(undefined)
  session.clear()
})

const CASA = 'Africa/Casablanca'
const MIN = 60_000
const T0 = '2026-11-14T19:00:00Z'
const at = (offsetMin: number) => new Date(Date.parse(T0) + offsetMin * MIN).toISOString()

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
  status: 'ON_SALE',
  refund_cutoff_at: at(-60),
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: {},
  created_at: '2026-11-01T10:00:00Z',
} satisfies EventDetail
const LIST: EventSummary[] = [
  { id: 1, title: EVENT.title, event_type: 'FOOTBALL_MATCH', starts_at: T0, status: 'ON_SALE', venue: { id: 'v-1', name: 'Grand Stade', timezone: CASA }, quota: 39600, sold: 31200 },
]
const gate = (code: string, devices_total: number, devices_online: number, rate_per_min: number, last_sync_at: string | null, zone_codes = ['NORD']) => ({
  gate_id: `g-${code}`,
  code,
  zone_codes,
  devices_total,
  devices_online,
  entered: 1000,
  refused: 3,
  overrides: 0,
  rate_per_min,
  last_sync_at,
})
/** At T0 − 38 min, as the mockup. Clocks sit mid-minute so a slow run never shifts a label. */
const LIVE = (serverMin = -38.5): Live => ({
  event_id: 1,
  status: 'ON_SALE',
  starts_at: T0,
  server_time: at(serverMin),
  sold: 31200,
  entered: 18432,
  rate_per_min: 412,
  rate_per_min_prev: 374,
  refused: { total: 61, by_result: { DUPLICATE: 38, WRONG_ZONE: 14, UNKNOWN: 5, BAD_SIG: 4 } },
  duplicates_late: 1,
  overrides: { total: 9, cap_per_device: 20, by_device: [{ device_id: 'd-2', label: 'PDA 2', count: 6 }] },
  gates: [
    gate('P1', 2, 2, 14, at(serverMin - 0.15), ['HON']), // 14 / 48 = 29 %
    gate('P3', 6, 6, 126, at(serverMin - 2.5), ['NORD', 'EST']), // 126 / 144 = 87.5 %
    gate('P4', 8, 5, 185, at(serverMin - 6.5)), // 185 / 192 = 96 %, 3 offline
    gate('P7', 0, 0, 0, null), // scans only, no device left
  ],
  devices_stale: [
    { device_id: 'd-7', label: 'PDA 7', gate_id: 'g-P4', gate_code: 'P4', last_seen_at: at(serverMin - 6.5) },
    { device_id: 'd-8', label: 'PDA 8', gate_id: 'g-P4', gate_code: 'P4', last_seen_at: null },
  ],
})

type Call = { method: string; path: string; org: string | null }

function stubApi(role: Role, opts: { live?: Live | ((n: number) => Live); close?: () => Response } = {}) {
  const calls: Call[] = []
  let status: EventStatus = 'ON_SALE'
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const method = init?.method ?? 'GET'
      calls.push({ method, path, org: new Headers(init?.headers).get('X-Organizer-Id') })
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events') return Response.json(LIST.map((e) => ({ ...e, status })))
      if (path === '/org/events/1') return Response.json({ ...EVENT, status })
      if (path === '/org/events/1/live') {
        const live = typeof opts.live === 'function' ? opts.live(liveCalls(calls)) : (opts.live ?? LIVE())
        return Response.json({ ...live, status })
      }
      if (path === '/org/events/1/close' && method === 'POST') {
        const res = opts.close?.() ?? Response.json({ ...EVENT, status: 'CLOSED' })
        if (res.ok) status = 'CLOSED'
        return res
      }
      if (path === '/org/events/1/reconciliation.csv') return new Response(new Uint8Array([0xef, 0xbb, 0xbf, 0x6b, 0x69, 0x6e, 0x64]), { headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename="reconciliation-1-serveur.csv"' } })
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

// Testing Library collapses the DOM's whitespace (FR groups digits with U+202F) to spaces, but not the string it looks for.
const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const nav = () => within(screen.getByRole('navigation', { name: fr.nav.label }))
const gatesCard = () => screen.getByRole('heading', { name: fr.live.gates.title }).closest('[data-slot="card"]') as HTMLElement
const gateRow = (code: string) => within(gatesCard()).getByRole('cell', { name: code }).closest('tr') as HTMLElement
const liveCalls = (calls: Call[]) => calls.filter((c) => c.path === '/org/events/1/live').length
const closeButton = () => screen.getByRole('button', { name: fr.live.close.action })

it('charge = rate ÷ (devices × 24): green under 70 %, amber under 90 %, red from 90 %, on the percent the label shows', () => {
  expect(charge({ rate_per_min: 126, devices_total: 6 })).toBe(0.875)
  expect(charge({ rate_per_min: 30, devices_total: 0 })).toBeNull()
  expect(chargeLevel(0)).toBe('ok')
  expect(chargeLevel(0.694)).toBe('ok') // "69 %"
  expect(chargeLevel(0.696)).toBe('busy') // "70 %"
  expect(chargeLevel(0.7)).toBe('busy')
  expect(chargeLevel(0.894)).toBe('busy') // "89 %"
  expect(chargeLevel(0.896)).toBe('full') // "90 %"
  expect(chargeLevel(0.9)).toBe('full')
  expect(chargeLevel(1.4)).toBe('full')
})

it('FINANCE opens B4: title, T0 pill, KPIs, per-gate rows with charge colours, scoped to the organizer, no close', async () => {
  const calls = stubApi('FINANCE')
  renderApp('/events/1/live')

  expect(await screen.findByText(spaced(i18n.t('live.kpi.rateValue', { rate: 412 })))).toBeTruthy()
  const date = new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(T0))
  expect(screen.getByRole('heading', { level: 1, name: `${EVENT.title} · ${date}` })).toBeTruthy()
  // Doors not set: open from T0 − 3 h; the offset comes from server_time.
  expect(screen.getByText('Portes ouvertes · T0 − 38 min')).toBeTruthy()

  expect(screen.getByText(spaced((18432).toLocaleString('fr')))).toBeTruthy()
  expect(screen.getByText(spaced(i18n.t('live.kpi.enteredHint', { sold: 31200, percent: '59 %' })))).toBeTruthy()
  expect(screen.getByText(i18n.t('live.kpi.lateDuplicates', { count: 1 }))).toBeTruthy()
  expect(screen.getByText('+38 par rapport aux 5 min précédentes')).toBeTruthy()
  expect(screen.getByText('Déjà utilisé : 38 · Mauvaise porte : 14 · Autres : 9')).toBeTruthy()
  expect(screen.getByText('PDA 2 : 6 / 20 par appareil')).toBeTruthy()

  const cells = (code: string) => within(gateRow(code)).getAllByRole('cell').map((c) => spaced(c.textContent ?? ''))
  expect(cells('P3')).toEqual(['P3', 'NORD · EST', '6 / 6', spaced((1000).toLocaleString('fr')), '3', '126', '88 %', 'il y a 2 min'])
  expect(cells('P4')[2]).toBe('5 / 8· 3 hors ligne')
  const level = (code: string) => gateRow(code).querySelector('[data-level]')?.getAttribute('data-level')
  expect([level('P1'), level('P3'), level('P4')]).toEqual(['ok', 'busy', 'full'])
  expect(within(gateRow('P7')).getByText(fr.live.gates.noDevice)).toBeTruthy()
  expect(within(gateRow('P7')).getByText(fr.live.gates.never)).toBeTruthy()
  expect(screen.getByText(fr.live.gates.footnote)).toBeTruthy()
  expect(within(gatesCard()).getByText(/^Mis à jour il y a \d s$/)).toBeTruthy()

  expect(screen.queryByRole('button', { name: fr.live.close.action })).toBeNull()
  expect(screen.getByRole('button', { name: fr.live.export })).toBeTruthy()
  expect(calls.filter((c) => c.path.startsWith('/org/events/1')).every((c) => c.org === 'org-a')).toBe(true)
  expect(nav().getByRole('link', { name: fr.nav.liveGates }).getAttribute('aria-current')).toBe('page')
})

it('stale devices are a warning, not an error: amber row with the offline-validation copy, the gate tinted', async () => {
  stubApi('OPS')
  renderApp('/events/1/live')

  const body = await screen.findByText(fr.live.stale.body)
  const alert = body.closest('div.rounded-xl') as HTMLElement
  expect(within(alert).getByText(i18n.t('live.stale.title', { count: 2 }))).toBeTruthy()
  expect(within(alert).getByText('PDA 7 (P4) · il y a 6 min')).toBeTruthy()
  expect(within(alert).getByText(`PDA 8 (P4) · ${fr.live.stale.never}`)).toBeTruthy()
  expect(alert.className).toContain('bg-warning-muted')
  expect(alert.className).not.toMatch(/destructive/)
  expect(screen.queryByRole('alert')).toBeNull()
  expect(within(alert).getByRole('link', { name: fr.live.stale.link }).getAttribute('href')).toBe('/events/1?tab=devices')

  expect(gateRow('P4').dataset.stale).toBe('true')
  expect(gateRow('P3').dataset.stale).toBeUndefined()
})

it.each([
  [14, false],
  [15, true], // 75 % of the cap
])('override count %i of 20: amber from 75 % of the cap is %s', async (count, amber) => {
  stubApi('FINANCE', { live: { ...LIVE(), overrides: { total: count, cap_per_device: 20, by_device: [{ device_id: 'd-2', label: 'PDA 2', count }] } } })
  renderApp('/events/1/live')
  const line = await screen.findByText(`PDA 2 : ${count} / 20 par appareil`)
  expect(line.className.includes('text-warning-foreground')).toBe(amber)
})

it('a failed refresh keeps the last figures and says so, with their age', async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  const calls = stubApi('FINANCE')
  const ok = vi.mocked(fetch).getMockImplementation()!
  renderApp('/events/1/live')
  await screen.findByText(fr.live.gates.footnote)

  // From now on /live is unreachable (network down); the 5xx-style retries run on real timers.
  vi.mocked(fetch).mockImplementation(async (input, init) => {
    if (String(input).endsWith('/live')) {
      calls.push({ method: 'GET', path: '/org/events/1/live', org: null })
      throw new TypeError('Failed to fetch')
    }
    return ok(input, init)
  })
  await act(() => vi.advanceTimersByTimeAsync(LIVE_REFETCH_MS))
  const warning = await screen.findByText(/^Mise à jour impossible, nouvel essai automatique\. Dernière réponse il y a \d+ s\.$/, {}, { timeout: 8000 })
  expect(warning.className).toContain('text-warning-foreground')
  expect(screen.getByText(spaced((18432).toLocaleString('fr')))).toBeTruthy() // the figures stay
  expect(gateRow('P4')).toBeTruthy()
})

it('no stale device: no amber row; an override cap reached says so', async () => {
  const live = { ...LIVE(), devices_stale: [], overrides: { total: 20, cap_per_device: 20, by_device: [{ device_id: 'd-2', label: 'PDA 2', count: 20 }] } }
  stubApi('ADMIN', { live })
  renderApp('/events/1/live')

  expect(await screen.findByText(i18n.t('live.kpi.capReached', { label: 'PDA 2', cap: 20 }))).toBeTruthy()
  expect(screen.queryByText(fr.live.stale.body)).toBeNull()
})

it(`refetches /live every ${LIVE_REFETCH_MS} ms, and not while the tab is hidden`, async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  // Each answer counts one more entry, so the page shows when an answer has landed.
  const calls = stubApi('FINANCE', { live: (n) => ({ ...LIVE(), entered: 5000 + n }) })
  renderApp('/events/1/live')
  await screen.findByText(spaced((5001).toLocaleString('fr')))
  expect(liveCalls(calls)).toBe(1)

  await act(() => vi.advanceTimersByTimeAsync(4999))
  expect(liveCalls(calls)).toBe(1)
  await act(() => vi.advanceTimersByTimeAsync(1))
  await screen.findByText(spaced((5002).toLocaleString('fr'))) // landed: the focus refetch below is not deduped into it

  act(() => focusManager.setFocused(false))
  await act(() => vi.advanceTimersByTimeAsync(15_000))
  expect(liveCalls(calls)).toBe(2)
  act(() => focusManager.setFocused(true)) // back on the tab: refetched at once
  await waitFor(() => expect(liveCalls(calls)).toBe(3))
})

it('close is disabled before T0, with the hint', async () => {
  stubApi('ADMIN')
  renderApp('/events/1/live')
  await screen.findByText(fr.live.gates.footnote)

  expect(closeButton()).toHaveProperty('disabled', true)
  const time = new Intl.DateTimeFormat('fr', { timeZone: CASA, timeStyle: 'short' }).format(Date.parse(T0))
  expect(screen.getByText(i18n.t('live.closeHint', { time }))).toBeTruthy()
})

it('after T0: confirm shows the stale count → POST /close → toast, CLOSED badge, close disabled', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS', { live: LIVE(12.5) })
  renderApp('/events/1/live')
  await screen.findByText('Portes ouvertes · T0 + 12 min')

  await user.click(closeButton())
  const dialog = await screen.findByRole('alertdialog')
  expect(within(dialog).getByText(i18n.t('live.close.stale', { count: 2 }))).toBeTruthy()
  await user.click(within(dialog).getByRole('button', { name: fr.live.close.confirm }))

  expect(await screen.findByText(i18n.t('live.close.done', { title: EVENT.title }))).toBeTruthy()
  const post = calls.find((c) => c.method === 'POST')!
  expect(post).toEqual({ method: 'POST', path: '/org/events/1/close', org: 'org-a' })
  expect(await screen.findByText(fr.events.statuses.CLOSED)).toBeTruthy()
  await waitFor(() => expect(closeButton()).toHaveProperty('disabled', true))
  expect(screen.queryByRole('alertdialog')).toBeNull()
})

it.each([
  ['NOT_STARTED', { reason: 'NOT_STARTED', starts_at: T0 }, (): string => i18n.t('live.close.notStarted', { time: new Intl.DateTimeFormat('fr', { timeZone: CASA, timeStyle: 'short' }).format(Date.parse(T0)) })],
  ['NOT_OPEN', { reason: 'NOT_OPEN' }, (): string => fr.live.close.notOpen],
  ['no reason', {}, (): string => fr.live.close.notEditable],
])('a 409 on close (%s) says why and refetches', async (_, details, copy) => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', {
    live: { ...LIVE(12.5), devices_stale: [] },
    close: () => Response.json({ code: 'EVENT_NOT_EDITABLE', message: 'x', details }, { status: 409 }),
  })
  renderApp('/events/1/live')
  await screen.findByText('Portes ouvertes · T0 + 12 min')
  const before = liveCalls(calls)

  await user.click(closeButton())
  const dialog = await screen.findByRole('alertdialog')
  expect(within(dialog).getByText(fr.live.close.allSynced)).toBeTruthy()
  await user.click(within(dialog).getByRole('button', { name: fr.live.close.confirm }))

  expect(await within(dialog).findByText(copy())).toBeTruthy()
  await waitFor(() => expect(liveCalls(calls)).toBeGreaterThan(before))
})

it('Exporter CSV downloads the reconciliation CSV with the organizer header, BOM intact, under the server filename', async () => {
  const user = userEvent.setup()
  const calls = stubApi('FINANCE')
  let blob: Blob | undefined
  const revoke = vi.fn()
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: (b: Blob) => ((blob = b), 'blob:x'), revokeObjectURL: revoke }))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  renderApp('/events/1/live')

  await user.click(await screen.findByRole('button', { name: fr.live.export }))

  await waitFor(() => expect(click).toHaveBeenCalled())
  expect(revoke).not.toHaveBeenCalled() // deferred: Firefox and Safari may still be reading the URL
  await waitFor(() => expect(revoke).toHaveBeenCalledWith('blob:x'), { timeout: 3000 })
  expect(calls.find((c) => c.path === '/org/events/1/reconciliation.csv')!.org).toBe('org-a')
  expect([...new Uint8Array(await blob!.arrayBuffer())].slice(0, 3)).toEqual([0xef, 0xbb, 0xbf])
  expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('reconciliation-1-serveur.csv')
})

it('a 403 on /live shows the FORBIDDEN copy, no figures', async () => {
  vi.stubGlobal('fetch', async (input: RequestInfo) => {
    const path = String(input).replace(/^\/v1/, '')
    if (path === '/org/me') return Response.json(me('ADMIN'))
    if (path === '/org/events/1') return Response.json(EVENT)
    return Response.json({ code: 'FORBIDDEN', message: 'x', details: {} }, { status: 403 })
  })
  renderApp('/events/1/live')
  expect(await screen.findByText(fr.errors.FORBIDDEN)).toBeTruthy()
  expect(screen.queryByText(fr.live.kpi.entered)).toBeNull()
})

it('sidebar "Portes en direct" is enabled: event picker → the event’s live page', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE')
  const router = renderApp('/events')

  await screen.findByRole('navigation', { name: fr.nav.label })
  await user.click(await nav().findByRole('link', { name: fr.nav.liveGates }))
  expect(await screen.findByText(fr.live.indexDescription)).toBeTruthy()
  await user.click(await screen.findByRole('link', { name: EVENT.title }))
  expect(router.state.location.pathname).toBe('/events/1/live')
  expect(await screen.findByText(fr.live.gates.footnote)).toBeTruthy()
})
