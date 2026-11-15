import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import type { EventDetail, Price } from '@/features/events/api'
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'
import { wallToUtc } from '@/lib/time'
import { currentPhase, endBody, rateEstimate, roomSchema, ROOM_REFETCH_MS, type Room, type RoomsResponse, type RoomStatus } from './api'

beforeAll(() => {
  // Radix (the shell's organizer select, the dialogs) needs these; jsdom does not implement them.
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
  // The Radix switch measures its thumb.
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

// A controlled clock: a week before the sale, whatever the real date.
const NOW = Date.parse('2026-11-13T12:00:00Z')
beforeEach(() => vi.useFakeTimers({ now: NOW, toFake: ['Date'] }))

type Call = { method: string; path: string; org: string | null; body?: unknown }
let calls: Call[] = []
afterEach(() => {
  // Step 1's singular paths are gone (P118): nothing may call them.
  expect(calls.filter((c) => /\/room(\/|$)/.test(c.path))).toEqual([])
  calls = []
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  session.clear()
})

const CASA = 'Africa/Casablanca'
const SALE = '2026-11-20T15:00:00Z'
const SALE_2 = '2026-11-21T10:00:00Z'
// Read in the zone through Intl, as the page does: the offset is tzdata's, not hard-coded here.
const SALE_TIME = new Intl.DateTimeFormat('fr', { timeZone: CASA, timeStyle: 'short' }).format(Date.parse(SALE))

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
  starts_at: '2026-11-22T19:00:00Z',
  doors_open_at: null,
  sale_starts_at: SALE,
  status: 'ON_SALE',
  refund_cutoff_at: '2026-11-22T18:00:00Z',
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: {},
  created_at: '2026-11-01T10:00:00Z',
} satisfies EventDetail
const PRICES: Price[] = [
  { id: 'p-1', zone_id: 1, zone: { code: 'NORD', name: 'Virage Nord' }, name: 'Plein tarif', price_mad: 5000, quota: 30000, sold: 0, audience: 'ALL' },
  { id: 'p-2', zone_id: 2, zone: { code: 'HON', name: 'Tribune' }, name: 'Plein tarif', price_mad: 20000, quota: 6000, sold: 0, audience: 'ALL' },
]

/** 04 GET /org/events/{id}/rooms `room`: phase 1, OPEN with the "Fans vérifiés" preset (7 days). */
const ROOM = (status: RoomStatus = 'OPEN', over: Partial<Room> = {}): Room => ({
  event_id: 1,
  phase: 1,
  label: null,
  starts_at: null,
  status,
  pre_queue_min: 30,
  opens_at: '2026-11-20T14:30:00Z',
  sale_starts_at: SALE,
  admission_rate_per_min: 500,
  eligibility: { min_account_age_days: 7, member_joined_before: null, min_events_attended: null },
  drawn_at: status === 'SCHEDULED' || status === 'PRE_QUEUE' ? null : SALE,
  figures: {
    joined: 15400,
    waiting: 12400,
    admitted: 3000,
    admitted_last_min: 498,
    active_holds: 820,
    max_active_holds: 2000,
    paid: 5200,
    quota: 36000,
    remaining: 30000,
    sold_out: false,
    estimated_drain_s: 1494,
  },
  ...over,
})
const NO_RULE = { min_account_age_days: null, member_joined_before: null, min_events_attended: null }
/** Phase 2, the general sale, the day after phase 1, with no rule. */
const PHASE_2 = (status: RoomStatus = 'SCHEDULED', over: Partial<Room> = {}): Room =>
  ROOM(status, { phase: 2, label: 'Vente générale', starts_at: SALE_2, sale_starts_at: SALE_2, opens_at: '2026-11-21T09:30:00Z', eligibility: NO_RULE, ...over })

const err = (status: number, code: string, details: Record<string, unknown> = {}) => Response.json({ code, message: 'x', details }, { status })

/** In-memory backend: the phases as 04 describes them; `put` / `action` may answer an error instead. */
function stubApi(
  role: Role,
  opts: {
    rooms?: Room[]
    event?: Partial<EventDetail>
    /** The server moves phase 1 on (tick, refund cutoff), from the n-th GET. */
    next?: (gets: number) => RoomStatus | undefined
    put?: (body: unknown, phase: number) => Response | undefined
    action?: (kind: string, phase: number) => Response | undefined
  } = {},
) {
  let rooms: Room[] = opts.rooms ?? []
  const NEXT: Record<string, RoomStatus> = { pause: 'PAUSED', resume: 'OPEN', end: 'ENDED' }
  const set = (room: Room) => {
    rooms = [...rooms.filter((r) => r.phase !== room.phase), room].sort((a, b) => a.phase - b.phase)
    return room
  }
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const method = init?.method ?? 'GET'
      const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      calls.push({ method, path, org: new Headers(init?.headers).get('X-Organizer-Id'), ...(body !== undefined && { body }) })
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events/1') return Response.json({ ...EVENT, ...opts.event })
      if (path === '/org/events/1/prices') return Response.json(PRICES)
      if (path === '/org/events/1/rooms' && method === 'GET') {
        const status = rooms[0] && opts.next?.(roomGets(calls))
        if (status) set({ ...rooms[0]!, status })
        return Response.json({ rooms } satisfies RoomsResponse)
      }
      const put = /^\/org\/events\/1\/rooms\/(\d)$/.exec(path)
      if (put && method === 'PUT') {
        const phase = Number(put[1])
        const refused = opts.put?.(body, phase)
        if (refused) return refused
        const old = rooms.find((r) => r.phase === phase)
        const sale: string = body.starts_at ?? SALE
        const room = set({
          ...(old ?? ROOM('SCHEDULED', { figures: { ...ROOM().figures, joined: 0, waiting: 0, admitted: 0 } })),
          ...body,
          phase,
          starts_at: body.starts_at ?? null,
          sale_starts_at: sale,
          opens_at: new Date(Date.parse(sale) - body.pre_queue_min * 60_000).toISOString(),
          eligibility: { ...NO_RULE, ...body.eligibility },
        })
        return Response.json(room, { status: old ? 200 : 201 }) // that phase's GET shape
      }
      const action = /^\/org\/events\/1\/rooms\/(\d)\/(pause|resume|end)$/.exec(path)
      if (action && method === 'POST') {
        const phase = Number(action[1])
        const refused = opts.action?.(action[2]!, phase)
        if (refused) return refused
        return Response.json(set({ ...rooms.find((r) => r.phase === phase)!, status: NEXT[action[2]!]! }))
      }
      return err(404, 'NOT_FOUND')
    }),
  )
  return calls
}

function renderApp(path: string) {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const client = createQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { client, router }
}

// Testing Library collapses the DOM's whitespace (FR groups digits with U+202F) to spaces, but not the string it looks for.
const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const kpi = (label: string) => spaced(screen.getByText(label).closest('[data-slot="card"]')!.textContent ?? '')
const writes = (calls: Call[]) => calls.filter((c) => c.method !== 'GET')
const roomGets = (calls: Call[]) => calls.filter((c) => c.method === 'GET' && c.path === '/org/events/1/rooms').length
const input = (label: string) => screen.getByLabelText(label) as HTMLInputElement
const verifiedSwitch = () => screen.getByRole('switch', { name: fr.room.form.verified })
const button = (name: string) => screen.queryByRole('button', { name })
const dateTime = (iso: string) => new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))
const time = (iso: string) => new Intl.DateTimeFormat('fr', { timeZone: CASA, timeStyle: 'short' }).format(Date.parse(iso))
const phaseTitle = (n: number, label?: string) => (label ? i18n.t('room.phase.titleLabel', { n, label }) : i18n.t('room.phase.title', { n }))
/** A phase card, found by its heading ("Phase 2 · Vente générale"). */
const card = async (heading: string) => within((await screen.findByRole('heading', { name: heading })).closest<HTMLElement>('[data-slot="card"]')!)

it('estimate: orders/min = rate × 0.7, sold out in quota ÷ 2.6 ÷ orders/min (36,000 places at 500/min ≈ 40 min)', () => {
  expect(rateEstimate(500, 36000)).toEqual({ orders: 350, minutes: 40 })
  expect(rateEstimate(1, 0)).toEqual({ orders: 1, minutes: null })
})

it('current phase (Q167): the latest not ENDED whose queue has opened, else the earliest not ENDED, else none', () => {
  const p1 = ROOM('OPEN')
  const p2 = PHASE_2('SCHEDULED')
  expect(currentPhase([p1, p2], NOW)?.phase).toBe(1) // neither queue has opened: the earliest
  expect(currentPhase([p1, p2], Date.parse('2026-11-21T09:31:00Z'))?.phase).toBe(2) // phase 2's queue has opened
  expect(currentPhase([ROOM('ENDED'), p2], NOW)?.phase).toBe(2)
  expect(currentPhase([ROOM('ENDED')], NOW)).toBeNull()
})

it('no phase: intro + form with 30 / 500 / switch off, opening time and estimate; create PUTs /rooms/1 with no starts_at; every key carries the organizer', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN')
  const { client } = renderApp('/events/1/room')

  expect(await screen.findByRole('heading', { name: fr.room.intro.title })).toBeTruthy()
  expect(screen.getByText(fr.room.intro.body)).toBeTruthy()
  expect(input(fr.room.form.label).value).toBe('')
  expect(screen.queryByLabelText(fr.room.form.startsAt)).toBeNull() // phase 1 follows the event's sale opening
  expect(input(fr.room.form.preQueue).value).toBe('30')
  expect(input(fr.room.form.rate).value).toBe('500')
  expect(verifiedSwitch().getAttribute('aria-checked')).toBe('false')
  expect(screen.queryByLabelText(fr.room.form.accountAge)).toBeNull()
  expect(screen.getByText(spaced(i18n.t('room.form.preQueueHint', { date: dateTime('2026-11-20T14:30:00Z'), zone: CASA })))).toBeTruthy()
  expect(await screen.findByText(i18n.t('room.form.rateHint', { orders: 350, minutes: 40 }))).toBeTruthy()
  expect(button(fr.room.phase.add)).toBeNull() // phase 1 first

  await user.click(screen.getByRole('button', { name: fr.room.form.create }))
  expect(await screen.findByText(fr.room.form.created)).toBeTruthy()
  expect(writes(calls)).toEqual([
    { method: 'PUT', path: '/org/events/1/rooms/1', org: 'org-a', body: { label: null, pre_queue_min: 30, admission_rate_per_min: 500, eligibility: {} } },
  ])
  expect((await card(phaseTitle(1))).getByText(fr.room.statuses.SCHEDULED)).toBeTruthy()
  expect(screen.queryByText(fr.room.kpi.waiting)).toBeNull() // KPIs only while the phase runs
  expect(button(fr.room.phase.add)).toBeTruthy()

  expect(calls.filter((c) => c.path.startsWith('/org/events')).every((c) => c.org === 'org-a')).toBe(true)
  const keys = client.getQueryCache().getAll().map((q) => q.queryKey)
  expect(keys).toContainEqual(['org', 'org-a', 'events', '1', 'rooms'])
  expect(keys.filter((k) => k[0] !== 'me').every((k) => k[0] === 'org' && k[1] === 'org-a')).toBe(true)
})

it('GET /rooms: one card per phase with N°, label, status, sale start and queue time in the venue zone; KPIs in the running one only', async () => {
  stubApi('OPS', { rooms: [ROOM('OPEN', { label: 'Prévente membres' }), PHASE_2()] })
  renderApp('/events/1/room')

  const one = await card(phaseTitle(1, 'Prévente membres'))
  const two = await card(phaseTitle(2, 'Vente générale'))
  expect(within(screen.getByRole('list', { name: fr.room.phase.list })).getAllByRole('listitem')).toHaveLength(2)
  expect(one.getByText(fr.room.statuses.OPEN)).toBeTruthy()
  expect(two.getByText(fr.room.statuses.SCHEDULED)).toBeTruthy()
  expect(two.getByText(spaced(i18n.t('room.phase.when', { date: dateTime(SALE_2), time: time('2026-11-21T09:30:00Z'), zone: CASA })))).toBeTruthy()
  expect(one.getByText(fr.room.kpi.waiting)).toBeTruthy()
  expect(two.queryByText(fr.room.kpi.waiting)).toBeNull()
  // Each card holds its own settings: phase 2 shows its own sale start (wall time in the zone), phase 1 has none.
  expect((two.getByLabelText(fr.room.form.startsAt) as HTMLInputElement).value).toBe(
    new Intl.DateTimeFormat('sv', { timeZone: CASA, dateStyle: 'short', timeStyle: 'short' }).format(Date.parse(SALE_2)).replace(' ', 'T'),
  )
  expect(one.queryByLabelText(fr.room.form.startsAt)).toBeNull()
  expect((one.getByLabelText(fr.room.form.label) as HTMLInputElement).value).toBe('Prévente membres')
})

it('"Ajouter une phase": phase 2 needs "Début de la vente" (zod), then PUTs /rooms/2 with label and starts_at in the venue zone', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', { rooms: [ROOM('OPEN', { label: 'Prévente membres' })] })
  renderApp('/events/1/room')
  await card(phaseTitle(1, 'Prévente membres'))

  await user.click(screen.getByRole('button', { name: fr.room.phase.add }))
  const added = await card(i18n.t('room.phase.newTitle', { n: 2 }))
  expect(button(fr.room.phase.add)).toBeNull()
  await user.type(added.getByLabelText(fr.room.form.label), '  Vente générale  ')
  await user.click(added.getByRole('button', { name: i18n.t('room.phase.create', { n: 2 }) }))
  expect(await added.findByText(fr.room.form.startsAtRequired)).toBeTruthy()
  expect(writes(calls)).toEqual([])

  fireEvent.change(added.getByLabelText(fr.room.form.startsAt), { target: { value: '2026-11-21T11:00' } })
  // The queue opening follows this phase's own sale start.
  const opens = new Date(Date.parse(wallToUtc('2026-11-21T11:00', CASA)) - 30 * 60_000).toISOString()
  expect(added.getByText(spaced(i18n.t('room.form.preQueueHint', { date: dateTime(opens), zone: CASA })))).toBeTruthy()
  await user.click(added.getByRole('button', { name: i18n.t('room.phase.create', { n: 2 }) }))

  expect(await screen.findByText(i18n.t('room.phase.created', { n: 2 }))).toBeTruthy()
  expect(writes(calls)).toEqual([
    {
      method: 'PUT',
      path: '/org/events/1/rooms/2',
      org: 'org-a',
      body: { label: 'Vente générale', starts_at: wallToUtc('2026-11-21T11:00', CASA), pre_queue_min: 30, admission_rate_per_min: 500, eligibility: {} },
    },
  ])
  expect(await card(phaseTitle(2, 'Vente générale'))).toBeTruthy()
  expect(screen.queryByRole('heading', { name: i18n.t('room.phase.newTitle', { n: 2 }) })).toBeNull()
})

it('"Ajouter une phase": Annuler closes the new card; at 5 phases the button is off with "Cinq phases au plus."', async () => {
  const user = userEvent.setup()
  const later = (n: number) => PHASE_2('SCHEDULED', { phase: n, label: null })
  stubApi('ADMIN', { rooms: [ROOM('ENDED'), later(2), later(3), later(4)] })
  renderApp('/events/1/room')
  await card(phaseTitle(4))
  await user.click(screen.getByRole('button', { name: fr.room.phase.add }))
  await user.click((await card(i18n.t('room.phase.newTitle', { n: 5 }))).getByRole('button', { name: fr.common.cancel }))
  expect(screen.queryByRole('heading', { name: i18n.t('room.phase.newTitle', { n: 5 }) })).toBeNull()

  cleanup()
  stubApi('ADMIN', { rooms: [ROOM('ENDED'), later(2), later(3), later(4), later(5)] })
  renderApp('/events/1/room')
  await card(phaseTitle(5))
  expect(button(fr.room.phase.add)).toHaveProperty('disabled', true)
  expect(screen.getByText(fr.room.phase.max)).toBeTruthy()
})

// BO-21 real run: the backend's answers verbatim (details.fields carries prose; the page reads the keys only).
it.each([
  [{ starts_at: "the phase must open after phase 1's sale start" }, fr.room.form.startsAt],
  [{ pre_queue_min: "the phase must open after phase 1's sale start" }, fr.room.form.preQueue],
  [{ phase: 'phase 1 must exist first' }, null],
])('422 PHASE_ORDER on %j → "Doit ouvrir après le début de la phase précédente" on that field (none named: under the form)', async (fields, label) => {
  const user = userEvent.setup()
  stubApi('ADMIN', { rooms: [ROOM('SCHEDULED')], put: (_, phase) => (phase === 2 ? err(422, 'VALIDATION_FAILED', { reason: 'PHASE_ORDER', fields }) : undefined) })
  renderApp('/events/1/room')
  await card(phaseTitle(1))
  await user.click(screen.getByRole('button', { name: fr.room.phase.add }))
  const added = await card(i18n.t('room.phase.newTitle', { n: 2 }))
  fireEvent.change(added.getByLabelText(fr.room.form.startsAt), { target: { value: '2026-11-20T15:00' } })
  await user.click(added.getByRole('button', { name: i18n.t('room.phase.create', { n: 2 }) }))

  expect(await added.findByText(fr.room.form.phaseOrder)).toBeTruthy()
  expect(fr.room.form.phaseOrder).toBe('Doit ouvrir après le début de la phase précédente')
  for (const l of [fr.room.form.startsAt, fr.room.form.preQueue])
    expect(added.getByLabelText(l).getAttribute('aria-invalid')).toBe(String(l === label))
})

it('zod ranges: 0–1440 min, 1–6000 fans/min, label ≤ 40, and with "Fans vérifiés" 1–365 days, a date up to the sale, 1–50 events, at least one rule', async () => {
  const user = userEvent.setup()
  stubApi('OPS')
  renderApp('/events/1/room')
  await screen.findByRole('heading', { name: fr.room.intro.title })

  await user.type(input(fr.room.form.label), 'x'.repeat(41))
  await user.clear(input(fr.room.form.preQueue))
  await user.type(input(fr.room.form.preQueue), '1441')
  await user.clear(input(fr.room.form.rate))
  await user.type(input(fr.room.form.rate), '0')
  await user.click(verifiedSwitch())
  expect(input(fr.room.form.accountAge).value).toBe('7') // the preset, ready when switched on
  await user.clear(input(fr.room.form.accountAge))
  await user.type(input(fr.room.form.accountAge), '366')
  await user.type(input(fr.room.form.memberBefore), '2026-11-21') // the day after the sale opens
  await user.type(input(fr.room.form.eventsAttended), '51')
  await user.click(screen.getByRole('button', { name: fr.room.form.create }))

  expect(await screen.findByText(fr.room.form.labelInvalid)).toBeTruthy()
  expect(screen.getByText(fr.room.form.preQueueInvalid)).toBeTruthy()
  expect(screen.getByText(fr.room.form.rateInvalid)).toBeTruthy()
  expect(screen.getByText(fr.room.form.accountAgeInvalid)).toBeTruthy()
  expect(screen.getByText(fr.room.form.memberBeforeInvalid)).toBeTruthy()
  expect(screen.getByText(fr.room.form.eventsAttendedInvalid)).toBeTruthy()

  // Fixed, but every rule emptied: the switch needs one.
  await user.clear(input(fr.room.form.label))
  await user.clear(input(fr.room.form.preQueue))
  await user.type(input(fr.room.form.preQueue), '1440')
  await user.clear(input(fr.room.form.rate))
  await user.type(input(fr.room.form.rate), '6001')
  await user.clear(input(fr.room.form.accountAge))
  await user.clear(input(fr.room.form.memberBefore))
  await user.clear(input(fr.room.form.eventsAttended))
  await user.click(screen.getByRole('button', { name: fr.room.form.create }))
  const ruleError = await screen.findByText(fr.room.form.ruleRequired)
  // The switch carries its error: invalid, and described by the message.
  expect(verifiedSwitch().getAttribute('aria-invalid')).toBe('true')
  expect(verifiedSwitch().getAttribute('aria-describedby')!.split(' ')).toContain(ruleError.id)
  expect(screen.getByText(fr.room.form.rateInvalid)).toBeTruthy()
  expect(screen.queryByText(fr.room.form.preQueueInvalid)).toBeNull()
  expect(screen.queryByText(fr.room.form.labelInvalid)).toBeNull()
  expect(writes(calls)).toEqual([])
})

it('"Fans vérifiés" on: PUT sends the filled rules only, the member date as midnight in the venue zone, the label trimmed', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN')
  renderApp('/events/1/room')
  await screen.findByRole('heading', { name: fr.room.intro.title })

  await user.type(input(fr.room.form.label), ' Prévente membres ')
  await user.clear(input(fr.room.form.preQueue))
  await user.type(input(fr.room.form.preQueue), '0')
  await user.clear(input(fr.room.form.rate))
  await user.type(input(fr.room.form.rate), '800')
  await user.click(verifiedSwitch())
  await user.type(input(fr.room.form.memberBefore), '2026-11-01')
  await user.type(input(fr.room.form.eventsAttended), '3')
  await user.click(screen.getByRole('button', { name: fr.room.form.create }))

  await screen.findByText(fr.room.form.created)
  expect(writes(calls).map((c) => c.body)).toEqual([
    {
      label: 'Prévente membres',
      pre_queue_min: 0,
      admission_rate_per_min: 800,
      eligibility: { min_account_age_days: 7, member_joined_before: wallToUtc('2026-11-01T00:00', CASA), min_events_attended: 3 },
    },
  ])
})

it('eligibility label by event_type (Q182): "matchs au stade" for a football match, "événements" otherwise', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', { event: { event_type: 'CONCERT' } })
  renderApp('/events/1/room')
  await screen.findByRole('heading', { name: fr.room.intro.title })
  await user.click(verifiedSwitch())
  expect(input(fr.room.form.eventsAttendedOther)).toBeTruthy()
  expect(screen.queryByLabelText(fr.room.form.eventsAttended)).toBeNull()

  cleanup()
  stubApi('ADMIN', { rooms: [ROOM('SCHEDULED')] })
  renderApp('/events/1/room')
  expect(await screen.findByLabelText(fr.room.form.eventsAttended)).toBeTruthy()
})

it('live: KPIs from the figures, status badge, the rules shown as saved', async () => {
  stubApi('ADMIN', { rooms: [ROOM('OPEN')] })
  renderApp('/events/1/room')

  expect(await screen.findByText(fr.room.statuses.OPEN)).toBeTruthy()
  expect(kpi(fr.room.kpi.waiting)).toContain(spaced((12400).toLocaleString('fr')))
  expect(kpi(fr.room.kpi.waiting)).toContain(spaced(i18n.t('room.kpi.joined', { joined: 15400 })))
  expect(kpi(fr.room.kpi.admitted)).toContain(spaced((3000).toLocaleString('fr')))
  expect(kpi(fr.room.kpi.holds)).toContain(spaced(`820 / ${(2000).toLocaleString('fr')}`))
  expect(kpi(fr.room.kpi.remaining)).toContain(spaced(`${(30000).toLocaleString('fr')} / ${(36000).toLocaleString('fr')}`))
  expect(kpi(fr.room.kpi.remaining)).toContain(spaced(i18n.t('room.kpi.paid', { paid: 5200 })))
  expect(kpi(fr.room.kpi.rate)).toContain('498/min')
  expect(kpi(fr.room.kpi.drain)).toContain('~25 min') // ⌈1494 s ÷ 60⌉
  expect(verifiedSwitch().getAttribute('aria-checked')).toBe('true')
  expect(input(fr.room.form.accountAge).value).toBe('7')
  expect(input(fr.room.form.memberBefore).value).toBe('')
})

it('sold out and no recent admission: honest copy, no drain estimate', async () => {
  stubApi('FINANCE', { rooms: [ROOM('OPEN', { figures: { ...ROOM().figures, remaining: 0, sold_out: true, admitted_last_min: 0, estimated_drain_s: null } })] })
  renderApp('/events/1/room')
  expect(await screen.findByText(fr.room.kpi.soldOut)).toBeTruthy()
  expect(kpi(fr.room.kpi.drain)).toContain(fr.room.kpi.drainNone)
})

it(`refetches every ${ROOM_REFETCH_MS} ms while a phase is not ENDED, and stops once every phase is`, async () => {
  vi.useFakeTimers({ now: NOW, toFake: ['Date', 'setInterval', 'clearInterval'] })
  stubApi('OPS', { rooms: [ROOM('OPEN')], next: (gets) => (gets >= 3 ? 'ENDED' : undefined) })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.OPEN)
  expect(roomGets(calls)).toBe(1)

  await act(() => vi.advanceTimersByTimeAsync(ROOM_REFETCH_MS - 1))
  expect(roomGets(calls)).toBe(1)
  await act(() => vi.advanceTimersByTimeAsync(1))
  await waitFor(() => expect(roomGets(calls)).toBe(2))

  // The third answer is ENDED: polling stops there.
  await act(() => vi.advanceTimersByTimeAsync(ROOM_REFETCH_MS))
  expect(await screen.findByText(fr.room.statuses.ENDED)).toBeTruthy()
  await act(() => vi.advanceTimersByTimeAsync(ROOM_REFETCH_MS * 5))
  expect(roomGets(calls)).toBe(3)
})

it.each([
  ['phase 1 ENDED, phase 2 SCHEDULED (it starts on its own)', [ROOM('ENDED'), PHASE_2('SCHEDULED')], true],
  ['every phase ENDED', [ROOM('ENDED'), PHASE_2('ENDED')], false],
  ['no phase', [], false],
])('polling with %s: %s', async (_, rooms, polls) => {
  vi.useFakeTimers({ now: NOW, toFake: ['Date', 'setInterval', 'clearInterval'] })
  stubApi('OPS', { rooms })
  renderApp('/events/1/room')
  await screen.findByRole('heading', { name: rooms.length ? phaseTitle(2, 'Vente générale') : fr.room.intro.title })
  expect(roomGets(calls)).toBe(1)
  await act(() => vi.advanceTimersByTimeAsync(ROOM_REFETCH_MS * 3))
  if (polls) await waitFor(() => expect(roomGets(calls)).toBe(4))
  else expect(roomGets(calls)).toBe(1)
})

it('an edit left from SCHEDULED is dropped when the phase starts: the field shows the server value, Apply sends it', async () => {
  vi.useFakeTimers({ now: NOW, toFake: ['Date', 'setInterval', 'clearInterval'] })
  const user = userEvent.setup()
  stubApi('OPS', { rooms: [ROOM('SCHEDULED')], next: (gets) => (gets >= 2 ? 'OPEN' : undefined) })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.SCHEDULED)
  await user.clear(input(fr.room.form.preQueue))
  await user.type(input(fr.room.form.preQueue), '5000') // invalid, never saved

  await act(() => vi.advanceTimersByTimeAsync(ROOM_REFETCH_MS)) // the next answer: the pre-queue is over
  expect(await screen.findByText(fr.room.statuses.OPEN)).toBeTruthy()
  await waitFor(() => expect(input(fr.room.form.preQueue).value).toBe('30'))
  expect(input(fr.room.form.preQueue).disabled).toBe(true)
  expect(screen.queryByText(fr.room.form.preQueueInvalid)).toBeNull()

  await user.clear(input(fr.room.form.rate))
  await user.type(input(fr.room.form.rate), '800')
  await user.click(screen.getByRole('button', { name: fr.room.form.apply }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.room.form.apply }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({ label: null, pre_queue_min: 30, admission_rate_per_min: 800, eligibility: { min_account_age_days: 7 } })
})

it("once started the schema checks the rate only (the other fields are the server's)", () => {
  const values = {
    label: 'x'.repeat(41),
    starts_at: '',
    pre_queue_min: '5000',
    admission_rate_per_min: '800',
    verified: true,
    eligibility: { min_account_age_days: '', member_joined_before: '', min_events_attended: '' },
  }
  expect(roomSchema(i18n.t, CASA, SALE, true, 2).safeParse(values).success).toBe(true)
  expect(roomSchema(i18n.t, CASA, SALE, true, 2).safeParse({ ...values, admission_rate_per_min: '0' }).success).toBe(false)
  expect(roomSchema(i18n.t, CASA, SALE, false, 2).safeParse(values).success).toBe(false)
})

it('a started phase 2: Appliquer re-sends its label and starts_at unchanged with the new rate', async () => {
  const user = userEvent.setup()
  stubApi('OPS', { rooms: [ROOM('ENDED'), PHASE_2('OPEN')] })
  renderApp('/events/1/room')
  const two = await card(phaseTitle(2, 'Vente générale'))
  expect(two.getByLabelText(fr.room.form.startsAt)).toHaveProperty('disabled', true)
  expect(two.getByLabelText(fr.room.form.label)).toHaveProperty('disabled', true)
  await user.clear(two.getByLabelText(fr.room.form.rate))
  await user.type(two.getByLabelText(fr.room.form.rate), '900')
  await user.click(two.getByRole('button', { name: fr.room.form.apply }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.room.form.apply }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]).toEqual({
    method: 'PUT',
    path: '/org/events/1/rooms/2',
    org: 'org-a',
    body: { label: 'Vente générale', starts_at: SALE_2, pre_queue_min: 30, admission_rate_per_min: 900, eligibility: {} },
  })
})

it('once started only the rate is editable; Appliquer confirms, then PUTs the new rate with the rest unchanged', async () => {
  const user = userEvent.setup()
  stubApi('OPS', { rooms: [ROOM('OPEN')] })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.OPEN)

  expect(screen.getByText(fr.room.started)).toBeTruthy()
  expect(input(fr.room.form.preQueue).disabled).toBe(true)
  expect(input(fr.room.form.label).disabled).toBe(true)
  expect(input(fr.room.form.accountAge).disabled).toBe(true)
  expect(input(fr.room.form.memberBefore).disabled).toBe(true)
  expect(verifiedSwitch()).toHaveProperty('disabled', true)
  expect(input(fr.room.form.rate).disabled).toBe(false)
  expect(button(fr.room.form.apply)).toHaveProperty('disabled', true) // nothing changed yet

  await user.clear(input(fr.room.form.rate))
  await user.type(input(fr.room.form.rate), '800')
  await user.click(screen.getByRole('button', { name: fr.room.form.apply }))
  const dialog = await screen.findByRole('alertdialog')
  expect(within(dialog).getByText(i18n.t('room.applyConfirm.title', { rate: 800 }))).toBeTruthy()
  expect(writes(calls)).toEqual([])
  await user.click(within(dialog).getByRole('button', { name: fr.room.form.apply }))

  const done = i18n.t('room.applyConfirm.done', { rate: 800 })
  expect(await screen.findByText(done)).toBeTruthy()
  expect(writes(calls)).toEqual([
    {
      method: 'PUT',
      path: '/org/events/1/rooms/1',
      org: 'org-a',
      body: { label: null, pre_queue_min: 30, admission_rate_per_min: 800, eligibility: { min_account_age_days: 7 } },
    },
  ])
  await waitFor(() => expect(input(fr.room.form.rate).value).toBe('800'))
  expect(screen.getAllByText(done)).toHaveLength(1) // one PUT, one toast, once settled
})

it.each([
  ['SCHEDULED', [fr.room.actions.end], fr.room.form.save],
  ['PRE_QUEUE', [fr.room.actions.end], fr.room.form.apply],
  ['OPEN', [fr.room.actions.pause, fr.room.actions.end], fr.room.form.apply],
  ['PAUSED', [fr.room.actions.resume, fr.room.actions.end], fr.room.form.apply],
  ['ENDED', [], null],
] as const)('%s: controls %j, submit %s', async (status, shown, submit) => {
  stubApi('ADMIN', { rooms: [ROOM(status)] })
  renderApp('/events/1/room')
  await card(phaseTitle(1))

  const all = [fr.room.actions.pause, fr.room.actions.resume, fr.room.actions.end]
  expect(all.filter((name) => button(name))).toEqual(shown)
  for (const name of [fr.room.form.save, fr.room.form.apply, fr.room.form.create]) expect(!!button(name)).toBe(name === submit)
  if (status === 'PRE_QUEUE') expect(screen.getByText(i18n.t('room.preQueueBadge', { time: SALE_TIME }))).toBeTruthy()
  if (status === 'SCHEDULED') expect(input(fr.room.form.preQueue).disabled).toBe(false)
  if (status === 'ENDED') {
    expect(screen.getByText(fr.room.ended)).toBeTruthy()
    expect(input(fr.room.form.rate).matches(':disabled')).toBe(true)
  }
})

it('Terminer la file, last phase: the irreversible copy, then POST /rooms/1/end → ENDED', async () => {
  const user = userEvent.setup()
  stubApi('OPS', { rooms: [ROOM('PAUSED')] })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.PAUSED)

  await user.click(screen.getByRole('button', { name: fr.room.actions.end }))
  const dialog = await screen.findByRole('alertdialog')
  expect(within(dialog).getByText(i18n.t('room.endConfirm.title', { n: 1 }))).toBeTruthy()
  expect(within(dialog).getByText('Les fans achètent sans file. Irréversible.')).toBeTruthy()
  await user.click(within(dialog).getByRole('button', { name: fr.room.endConfirm.confirm }))

  expect(await screen.findByText(i18n.t('room.endConfirm.done', { n: 1 }))).toBeTruthy()
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/events/1/rooms/1/end', org: 'org-a' }])
  expect(await screen.findByText(fr.room.statuses.ENDED)).toBeTruthy()
  expect(button(fr.room.actions.end)).toBeNull()
})

it('Terminer la file with a later phase scheduled: "Les ventes restent fermées jusqu\'à la phase suivante. Irréversible."', async () => {
  const user = userEvent.setup()
  stubApi('OPS', { rooms: [ROOM('OPEN', { label: 'Prévente membres' }), PHASE_2()] })
  renderApp('/events/1/room')
  const one = await card(phaseTitle(1, 'Prévente membres'))

  await user.click(one.getByRole('button', { name: fr.room.actions.end }))
  const dialog = await screen.findByRole('alertdialog')
  expect(within(dialog).getByText("Les ventes restent fermées jusqu'à la phase suivante. Irréversible.")).toBeTruthy()
  expect(within(dialog).queryByText(fr.room.endConfirm.body)).toBeNull()
  await user.click(within(dialog).getByRole('button', { name: fr.room.endConfirm.confirm }))
  await waitFor(() => expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/events/1/rooms/1/end', org: 'org-a' }]))
  expect(await one.findByText(fr.room.phase.ended)).toBeTruthy()
})

it('end confirm body: "sans file" only when every other phase has ended', () => {
  expect(endBody([ROOM('OPEN')], 1)).toBe('room.endConfirm.body')
  expect(endBody([ROOM('ENDED'), PHASE_2('OPEN')], 2)).toBe('room.endConfirm.body')
  expect(endBody([ROOM('OPEN'), PHASE_2()], 1)).toBe('room.endConfirm.bodyNext')
  expect(endBody([ROOM('OPEN'), PHASE_2()], 2)).toBe('room.endConfirm.bodyOthers') // phase 1 still sells
  expect(endBody([ROOM('OPEN'), PHASE_2(), PHASE_2('SCHEDULED', { phase: 3 })], 2)).toBe('room.endConfirm.bodyOthers')
})

it('ending a scheduled phase 2 while phase 1 sells: "Les autres phases ne changent pas.", never "sans file"', async () => {
  const user = userEvent.setup()
  stubApi('OPS', { rooms: [ROOM('OPEN'), PHASE_2()] })
  renderApp('/events/1/room')
  const two = await card(phaseTitle(2, 'Vente générale'))
  await user.click(two.getByRole('button', { name: fr.room.actions.end }))
  const dialog = await screen.findByRole('alertdialog')
  expect(within(dialog).getByText('Les autres phases ne changent pas. Irréversible.')).toBeTruthy()
  expect(within(dialog).queryByText(fr.room.endConfirm.body)).toBeNull()
  expect(within(dialog).queryByText(fr.room.endConfirm.bodyNext)).toBeNull()
})

it('a queue opening on another day than the sale (venue zone) shows its own date', async () => {
  // 1440 min before: the day before the sale.
  stubApi('OPS', { rooms: [ROOM('SCHEDULED', { pre_queue_min: 1440, opens_at: '2026-11-19T15:00:00Z' })] })
  renderApp('/events/1/room')
  const one = await card(phaseTitle(1))
  expect(one.getByText(spaced(i18n.t('room.phase.whenOtherDay', { date: dateTime(SALE), opens: dateTime('2026-11-19T15:00:00Z'), zone: CASA })))).toBeTruthy()
  expect(one.queryByText(spaced(i18n.t('room.phase.when', { date: dateTime(SALE), time: time('2026-11-19T15:00:00Z'), zone: CASA })))).toBeNull()
})

it('controls hit their own phase: Pause then Reprendre on phase 2 POST /rooms/2/pause, /rooms/2/resume', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', { rooms: [ROOM('ENDED'), PHASE_2('OPEN')] })
  renderApp('/events/1/room')
  const two = await card(phaseTitle(2, 'Vente générale'))
  expect((await card(phaseTitle(1))).queryByRole('button', { name: fr.room.actions.end })).toBeNull() // ENDED: no controls

  await user.click(two.getByRole('button', { name: fr.room.actions.pause }))
  expect(await screen.findByText(i18n.t('room.paused', { n: 2 }))).toBeTruthy()
  await user.click(await two.findByRole('button', { name: fr.room.actions.resume }))
  expect(await screen.findByText(i18n.t('room.resumed', { n: 2 }))).toBeTruthy()
  expect(writes(calls).map((c) => c.path)).toEqual(['/org/events/1/rooms/2/pause', '/org/events/1/rooms/2/resume'])
})

it('a 409 ROOM_STATUS (pause, or a rate change) is a toast, and the phases are refetched', async () => {
  const user = userEvent.setup()
  const conflict = () => err(409, 'ROOM_STATUS', { status: 'ENDED' })
  stubApi('OPS', { rooms: [ROOM('OPEN')], action: conflict, put: conflict })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.OPEN)
  const before = roomGets(calls)

  await user.click(screen.getByRole('button', { name: fr.room.actions.pause }))
  expect(await screen.findByText(fr.errors.ROOM_STATUS)).toBeTruthy()
  await waitFor(() => expect(roomGets(calls)).toBeGreaterThan(before))

  await user.clear(input(fr.room.form.rate))
  await user.type(input(fr.room.form.rate), '600')
  await user.click(screen.getByRole('button', { name: fr.room.form.apply }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.room.form.apply }))
  await waitFor(() => expect(screen.getAllByText(fr.errors.ROOM_STATUS).length).toBeGreaterThan(0))
  expect(writes(calls).map((c) => c.method)).toEqual(['POST', 'PUT'])
})

it('422 SALE_START_REQUIRED says to set a future sale opening', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', { put: () => err(422, 'VALIDATION_FAILED', { fields: { sale_starts_at: 'required' }, reason: 'SALE_START_REQUIRED' }) })
  renderApp('/events/1/room')
  await user.click(await screen.findByRole('button', { name: fr.room.form.create }))
  expect(await screen.findByText(fr.room.form.saleStartRequired)).toBeTruthy()
})

it('a range 422 lands on the fields named by details.fields (API keys, eligibility dotted)', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', {
    rooms: [ROOM('SCHEDULED')],
    put: () => err(422, 'VALIDATION_FAILED', { fields: { pre_queue_min: 'range', 'eligibility.min_account_age_days': 'range' } }),
  })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.SCHEDULED)
  await user.clear(input(fr.room.form.rate))
  await user.type(input(fr.room.form.rate), '600')
  await user.click(screen.getByRole('button', { name: fr.room.form.save }))
  await waitFor(() => expect(input(fr.room.form.preQueue).getAttribute('aria-invalid')).toBe('true'))
  expect(input(fr.room.form.accountAge).getAttribute('aria-invalid')).toBe('true')
  expect(input(fr.room.form.rate).getAttribute('aria-invalid')).toBe('false')
})

// BE-40's step-1 room verbatim plus BE-48's phase fields (event 123 there; the page reads nothing from event_id).
const BE40 =
  '{"event_id":123,"phase":1,"label":null,"starts_at":null,"status":"SCHEDULED","pre_queue_min":30,"opens_at":"2099-04-01T09:30:00Z","sale_starts_at":"2099-04-01T10:00:00Z","admission_rate_per_min":500,"eligibility":{"min_account_age_days":null,"member_joined_before":null,"min_events_attended":null},"drawn_at":null,"figures":{"joined":0,"waiting":0,"admitted":0,"admitted_last_min":0,"active_holds":0,"max_active_holds":2000,"paid":0,"quota":100,"remaining":100,"sold_out":false,"estimated_drain_s":null}}'

it('a SCHEDULED phase with no rule renders, and a save sends the full body (empty rule as {})', async () => {
  const user = userEvent.setup()
  stubApi('OPS', { rooms: [JSON.parse(BE40) as Room] })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.SCHEDULED)

  expect(verifiedSwitch().getAttribute('aria-checked')).toBe('false')
  expect(input(fr.room.form.preQueue).value).toBe('30')

  await user.clear(input(fr.room.form.preQueue))
  await user.type(input(fr.room.form.preQueue), '45')
  await user.click(screen.getByRole('button', { name: fr.room.form.save }))
  expect(await screen.findByText(fr.room.form.saved)).toBeTruthy()
  expect(writes(calls)[0]!.body).toEqual({ label: null, pre_queue_min: 45, admission_rate_per_min: 500, eligibility: {} })
})

it('FINANCE reads: every phase, no write buttons (controls, save, add), every field disabled', async () => {
  stubApi('FINANCE', { rooms: [ROOM('OPEN'), PHASE_2()] })
  renderApp('/events/1/room')
  await card(phaseTitle(2, 'Vente générale'))

  expect(screen.getByText(fr.room.readOnly)).toBeTruthy()
  for (const name of [fr.room.actions.pause, fr.room.actions.resume, fr.room.actions.end, fr.room.form.save, fr.room.form.apply, fr.room.form.create, fr.room.phase.add])
    expect(button(name)).toBeNull()
  const fields = [fr.room.form.label, fr.room.form.preQueue, fr.room.form.rate].flatMap((l) => screen.getAllByLabelText(l))
  expect(fields).toHaveLength(6)
  for (const el of [...fields, ...screen.getAllByRole('switch')]) expect(el.matches(':disabled')).toBe(true)
  expect(writes(calls)).toEqual([])
})

it('B2 Règles: "File d\'attente · {n} phase(s) · {current status}" with "Gérer" → B13', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', { rooms: [ROOM('PRE_QUEUE')] })
  const { router } = renderApp('/events/1')

  await user.click(await screen.findByRole('tab', { name: fr.events.tabs.rules }))
  await waitFor(() => expect(input(fr.room.rule).value).toBe('1 phase · Pré-file'))
  await user.click(screen.getByRole('link', { name: fr.room.manage }))
  expect(router.state.location.pathname).toBe('/events/1/room')
  expect(await screen.findByText(i18n.t('room.preQueueBadge', { time: SALE_TIME }))).toBeTruthy()
})

it.each([
  [[ROOM('ENDED'), PHASE_2('SCHEDULED')], '2 phases · Programmée'],
  [[ROOM('ENDED'), PHASE_2('ENDED')], '2 phases · Terminée'],
])('B2 Règles with phases %#: "%s"', async (rooms, value) => {
  const user = userEvent.setup()
  stubApi('OPS', { rooms })
  renderApp('/events/1')
  await user.click(await screen.findByRole('tab', { name: fr.events.tabs.rules }))
  await waitFor(() => expect(input(fr.room.rule).value).toBe(value))
})

it('B2 Règles without a phase: "Non configurée"; FINANCE gets "Voir"', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE')
  renderApp('/events/1')
  await user.click(await screen.findByRole('tab', { name: fr.events.tabs.rules }))
  await waitFor(() => expect(input(fr.room.rule).value).toBe(fr.room.none))
  expect(screen.getByRole('link', { name: fr.common.view }).getAttribute('href')).toBe('/events/1/room')
})
