import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
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
import { rateEstimate, roomSchema, ROOM_REFETCH_MS, type Room, type RoomResponse, type RoomStatus } from './api'

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

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  session.clear()
})

const CASA = 'Africa/Casablanca'
const SALE = '2026-11-20T15:00:00Z'
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
  { id: 'p-1', zone_id: 1, zone: { code: 'NORD', name: 'Virage Nord' }, name: 'Plein tarif', price_mad: 5000, quota: 30000, sold: 0 },
  { id: 'p-2', zone_id: 2, zone: { code: 'HON', name: 'Tribune' }, name: 'Plein tarif', price_mad: 20000, quota: 6000, sold: 0 },
]

/** 04 GET /org/events/{id}/room `room`, OPEN with the "Fans vérifiés" preset (7 days). */
const ROOM = (status: RoomStatus = 'OPEN', over: Partial<Room> = {}): Room => ({
  event_id: 1,
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

type Call = { method: string; path: string; org: string | null; body?: unknown }
const err = (status: number, code: string, details: Record<string, unknown> = {}) => Response.json({ code, message: 'x', details }, { status })

/** In-memory backend: the room as 04 describes it; `put` / `action` may answer an error instead. */
function stubApi(role: Role, opts: { room?: Room | null; next?: (gets: number) => RoomStatus | undefined; put?: (body: unknown) => Response | undefined; action?: (kind: string) => Response | undefined } = {}) {
  const calls: Call[] = []
  let room: Room | null = opts.room === undefined ? null : opts.room
  const NEXT: Record<string, RoomStatus> = { pause: 'PAUSED', resume: 'OPEN', end: 'ENDED' }
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const method = init?.method ?? 'GET'
      const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      calls.push({ method, path, org: new Headers(init?.headers).get('X-Organizer-Id'), ...(body !== undefined && { body }) })
      const reply = (): Response => Response.json({ room } satisfies RoomResponse)
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events/1') return Response.json(EVENT)
      if (path === '/org/events/1/prices') return Response.json(PRICES)
      if (path === '/org/events/1/room' && method === 'GET') {
        const status = room && opts.next?.(roomGets(calls)) // the server moves it on (tick, refund cutoff)
        if (room && status) room = { ...room, status }
        return reply()
      }
      if (path === '/org/events/1/room' && method === 'PUT') {
        const refused = opts.put?.(body)
        if (refused) return refused
        const created = !room
        room = { ...(room ?? ROOM('SCHEDULED', { figures: { ...ROOM().figures, joined: 0, waiting: 0, admitted: 0 } })), ...body, eligibility: { min_account_age_days: null, member_joined_before: null, min_events_attended: null, ...body.eligibility } }
        return Response.json({ room }, { status: created ? 201 : 200 })
      }
      const action = /^\/org\/events\/1\/room\/(pause|resume|end)$/.exec(path)?.[1]
      if (action && method === 'POST') {
        const refused = opts.action?.(action)
        if (refused) return refused
        room = { ...room!, status: NEXT[action]! }
        return reply()
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
const roomGets = (calls: Call[]) => calls.filter((c) => c.method === 'GET' && c.path === '/org/events/1/room').length
const input = (label: string) => screen.getByLabelText(label) as HTMLInputElement
const verifiedSwitch = () => screen.getByRole('switch', { name: fr.room.form.verified })
const button = (name: string) => screen.queryByRole('button', { name })
const dateTime = (iso: string) => new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))

it('estimate: orders/min = rate × 0.7, sold out in quota ÷ 2.6 ÷ orders/min (36,000 places at 500/min ≈ 40 min)', () => {
  expect(rateEstimate(500, 36000)).toEqual({ orders: 350, minutes: 40 })
  expect(rateEstimate(1, 0)).toEqual({ orders: 1, minutes: null })
})

it('no room: intro + form with 30 / 500 / switch off, opening time and estimate; create PUTs the defaults; every key carries the organizer', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN')
  const { client } = renderApp('/events/1/room')

  expect(await screen.findByRole('heading', { name: fr.room.intro.title })).toBeTruthy()
  expect(screen.getByText(fr.room.intro.body)).toBeTruthy()
  expect(input(fr.room.form.preQueue).value).toBe('30')
  expect(input(fr.room.form.rate).value).toBe('500')
  expect(verifiedSwitch().getAttribute('aria-checked')).toBe('false')
  expect(screen.queryByLabelText(fr.room.form.accountAge)).toBeNull()
  expect(screen.getByText(spaced(i18n.t('room.form.preQueueHint', { date: dateTime('2026-11-20T14:30:00Z'), zone: CASA })))).toBeTruthy()
  expect(await screen.findByText(i18n.t('room.form.rateHint', { orders: 350, minutes: 40 }))).toBeTruthy()
  expect(screen.queryByText(fr.room.kpi.waiting)).toBeNull()

  await user.click(screen.getByRole('button', { name: fr.room.form.create }))
  expect(await screen.findByText(fr.room.form.created)).toBeTruthy()
  expect(writes(calls)).toEqual([
    { method: 'PUT', path: '/org/events/1/room', org: 'org-a', body: { pre_queue_min: 30, admission_rate_per_min: 500, eligibility: {} } },
  ])
  expect(await screen.findByText(fr.room.statuses.SCHEDULED)).toBeTruthy()
  expect(screen.getByText(fr.room.kpi.waiting)).toBeTruthy()

  expect(calls.filter((c) => c.path.startsWith('/org/events')).every((c) => c.org === 'org-a')).toBe(true)
  const keys = client.getQueryCache().getAll().map((q) => q.queryKey)
  expect(keys).toContainEqual(['org', 'org-a', 'events', '1', 'room'])
  expect(keys.filter((k) => k[0] !== 'me').every((k) => k[0] === 'org' && k[1] === 'org-a')).toBe(true)
})

it('zod ranges: 0–1440 min, 1–6000 fans/min, and with "Fans vérifiés" 1–365 days, a date up to the sale, 1–50 events, at least one rule', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS')
  renderApp('/events/1/room')
  await screen.findByRole('heading', { name: fr.room.intro.title })

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

  expect(await screen.findByText(fr.room.form.preQueueInvalid)).toBeTruthy()
  expect(screen.getByText(fr.room.form.rateInvalid)).toBeTruthy()
  expect(screen.getByText(fr.room.form.accountAgeInvalid)).toBeTruthy()
  expect(screen.getByText(fr.room.form.memberBeforeInvalid)).toBeTruthy()
  expect(screen.getByText(fr.room.form.eventsAttendedInvalid)).toBeTruthy()

  // Fixed, but every rule emptied: the switch needs one.
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
  expect(writes(calls)).toEqual([])
})

it('"Fans vérifiés" on: PUT sends the filled rules only, the member date as midnight in the venue zone', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN')
  renderApp('/events/1/room')
  await screen.findByRole('heading', { name: fr.room.intro.title })

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
      pre_queue_min: 0,
      admission_rate_per_min: 800,
      eligibility: { min_account_age_days: 7, member_joined_before: wallToUtc('2026-11-01T00:00', CASA), min_events_attended: 3 },
    },
  ])
})

it('live: KPIs from the figures, status badge, the rules shown as saved', async () => {
  stubApi('ADMIN', { room: ROOM('OPEN') })
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
  stubApi('FINANCE', { room: ROOM('OPEN', { figures: { ...ROOM().figures, remaining: 0, sold_out: true, admitted_last_min: 0, estimated_drain_s: null } }) })
  renderApp('/events/1/room')
  expect(await screen.findByText(fr.room.kpi.soldOut)).toBeTruthy()
  expect(kpi(fr.room.kpi.drain)).toContain(fr.room.kpi.drainNone)
})

it(`refetches every ${ROOM_REFETCH_MS} ms while not ENDED, and stops once ENDED`, async () => {
  vi.useFakeTimers({ now: NOW, toFake: ['Date', 'setInterval', 'clearInterval'] })
  const calls = stubApi('OPS', { room: ROOM('OPEN'), next: (gets) => (gets >= 3 ? 'ENDED' : undefined) })
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

it('an edit left from SCHEDULED is dropped when the room starts: the field shows the server value, Apply sends it', async () => {
  vi.useFakeTimers({ now: NOW, toFake: ['Date', 'setInterval', 'clearInterval'] })
  const user = userEvent.setup()
  const calls = stubApi('OPS', { room: ROOM('SCHEDULED'), next: (gets) => (gets >= 2 ? 'OPEN' : undefined) })
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
  expect(writes(calls)[0]!.body).toEqual({ pre_queue_min: 30, admission_rate_per_min: 800, eligibility: { min_account_age_days: 7 } })
})

it('once started the schema checks the rate only (the other fields are the server\'s)', () => {
  const values = { pre_queue_min: '5000', admission_rate_per_min: '800', verified: true, eligibility: { min_account_age_days: '', member_joined_before: '', min_events_attended: '' } }
  expect(roomSchema(i18n.t, CASA, SALE, true).safeParse(values).success).toBe(true)
  expect(roomSchema(i18n.t, CASA, SALE, true).safeParse({ ...values, admission_rate_per_min: '0' }).success).toBe(false)
  expect(roomSchema(i18n.t, CASA, SALE).safeParse(values).success).toBe(false)
})

it('no room: no polling', async () => {
  vi.useFakeTimers({ now: NOW, toFake: ['Date', 'setInterval', 'clearInterval'] })
  const calls = stubApi('ADMIN')
  renderApp('/events/1/room')
  await screen.findByRole('heading', { name: fr.room.intro.title })
  await act(() => vi.advanceTimersByTimeAsync(ROOM_REFETCH_MS * 3))
  expect(roomGets(calls)).toBe(1)
})

it('once started only the rate is editable; Appliquer confirms, then PUTs the new rate with the rest unchanged', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS', { room: ROOM('OPEN') })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.OPEN)

  expect(screen.getByText(fr.room.started)).toBeTruthy()
  expect(input(fr.room.form.preQueue).disabled).toBe(true)
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

  expect(await screen.findByText(i18n.t('room.applyConfirm.done', { rate: 800 }))).toBeTruthy()
  expect(writes(calls)).toEqual([
    { method: 'PUT', path: '/org/events/1/room', org: 'org-a', body: { pre_queue_min: 30, admission_rate_per_min: 800, eligibility: { min_account_age_days: 7 } } },
  ])
  await waitFor(() => expect(input(fr.room.form.rate).value).toBe('800'))
})

it.each([
  ['SCHEDULED', [fr.room.actions.end], fr.room.form.save],
  ['PRE_QUEUE', [fr.room.actions.end], fr.room.form.apply],
  ['OPEN', [fr.room.actions.pause, fr.room.actions.end], fr.room.form.apply],
  ['PAUSED', [fr.room.actions.resume, fr.room.actions.end], fr.room.form.apply],
  ['ENDED', [], null],
] as const)('%s: controls %j, submit %s', async (status, shown, submit) => {
  stubApi('ADMIN', { room: ROOM(status) })
  renderApp('/events/1/room')
  await screen.findByRole('heading', { name: fr.room.form.title })

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

it('Terminer la file: the irreversible copy, then POST /end → ENDED', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS', { room: ROOM('PAUSED') })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.PAUSED)

  await user.click(screen.getByRole('button', { name: fr.room.actions.end }))
  const dialog = await screen.findByRole('alertdialog')
  expect(within(dialog).getByText(fr.room.endConfirm.title)).toBeTruthy()
  expect(within(dialog).getByText('Les fans achètent sans file. Irréversible.')).toBeTruthy()
  await user.click(within(dialog).getByRole('button', { name: fr.room.endConfirm.confirm }))

  expect(await screen.findByText(fr.room.endConfirm.done)).toBeTruthy()
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/events/1/room/end', org: 'org-a' }])
  expect(await screen.findByText(fr.room.statuses.ENDED)).toBeTruthy()
  expect(button(fr.room.actions.end)).toBeNull()
})

it('Pause then Reprendre: POST /pause, /resume, each with its toast', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', { room: ROOM('OPEN') })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.OPEN)

  await user.click(screen.getByRole('button', { name: fr.room.actions.pause }))
  expect(await screen.findByText(fr.room.paused)).toBeTruthy()
  await user.click(await screen.findByRole('button', { name: fr.room.actions.resume }))
  expect(await screen.findByText(fr.room.resumed)).toBeTruthy()
  expect(writes(calls).map((c) => c.path)).toEqual(['/org/events/1/room/pause', '/org/events/1/room/resume'])
})

it('a 409 ROOM_STATUS (pause, or a rate change) is a toast, and the room is refetched', async () => {
  const user = userEvent.setup()
  const conflict = () => err(409, 'ROOM_STATUS', { status: 'ENDED' })
  const calls = stubApi('OPS', { room: ROOM('OPEN'), action: conflict, put: conflict })
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
    room: ROOM('SCHEDULED'),
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

// BE-40's answer verbatim (event 123 there; the page reads nothing from event_id).
const BE40 =
  '{"room":{"event_id":123,"status":"SCHEDULED","pre_queue_min":30,"opens_at":"2099-04-01T09:30:00Z","sale_starts_at":"2099-04-01T10:00:00Z","admission_rate_per_min":500,"eligibility":{"min_account_age_days":null,"member_joined_before":null,"min_events_attended":null},"drawn_at":null,"figures":{"joined":0,"waiting":0,"admitted":0,"admitted_last_min":0,"active_holds":0,"max_active_holds":2000,"paid":0,"quota":100,"remaining":100,"sold_out":false,"estimated_drain_s":null}}}'

it("BE-40's real JSON: a SCHEDULED room with no rule renders, and a save sends the full body (empty rule as {})", async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS', { room: (JSON.parse(BE40) as RoomResponse).room })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.SCHEDULED)

  expect(verifiedSwitch().getAttribute('aria-checked')).toBe('false')
  expect(input(fr.room.form.preQueue).value).toBe('30')
  expect(kpi(fr.room.kpi.holds)).toContain(spaced(`0 / ${(2000).toLocaleString('fr')}`))
  expect(kpi(fr.room.kpi.drain)).toContain(fr.room.kpi.drainNone)

  await user.clear(input(fr.room.form.preQueue))
  await user.type(input(fr.room.form.preQueue), '45')
  await user.click(screen.getByRole('button', { name: fr.room.form.save }))
  expect(await screen.findByText(fr.room.form.saved)).toBeTruthy()
  expect(writes(calls)[0]!.body).toEqual({ pre_queue_min: 45, admission_rate_per_min: 500, eligibility: {} })
})

it('FINANCE reads: figures and settings, no write buttons, every field disabled', async () => {
  stubApi('FINANCE', { room: ROOM('SCHEDULED') })
  renderApp('/events/1/room')
  await screen.findByText(fr.room.statuses.SCHEDULED)

  expect(screen.getByText(fr.room.readOnly)).toBeTruthy()
  for (const name of [fr.room.actions.pause, fr.room.actions.resume, fr.room.actions.end, fr.room.form.save, fr.room.form.apply, fr.room.form.create])
    expect(button(name)).toBeNull()
  expect(input(fr.room.form.preQueue).matches(':disabled')).toBe(true)
  expect(input(fr.room.form.rate).matches(':disabled')).toBe(true)
  expect(verifiedSwitch().matches(':disabled')).toBe(true)
})

it('B2 Règles: "File d\'attente · {status}" with "Gérer" → B13', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', { room: ROOM('PRE_QUEUE') })
  const { router } = renderApp('/events/1')

  await user.click(await screen.findByRole('tab', { name: fr.events.tabs.rules }))
  await waitFor(() => expect(input(fr.room.rule).value).toBe(fr.room.statuses.PRE_QUEUE))
  await user.click(screen.getByRole('link', { name: fr.room.manage }))
  expect(router.state.location.pathname).toBe('/events/1/room')
  expect(await screen.findByText(i18n.t('room.preQueueBadge', { time: SALE_TIME }))).toBeTruthy()
})

it('B2 Règles without a room: "Non configurée"; FINANCE gets "Voir"', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE')
  renderApp('/events/1')
  await user.click(await screen.findByRole('tab', { name: fr.events.tabs.rules }))
  await waitFor(() => expect(input(fr.room.rule).value).toBe(fr.room.none))
  expect(screen.getByRole('link', { name: fr.common.view }).getAttribute('href')).toBe('/events/1/room')
})
