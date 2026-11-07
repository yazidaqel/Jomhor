import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import type { Venue, VenueDetail } from '@/features/venues/api'
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'
import { formatDh } from '@/lib/money'
import type { EventDetail, EventSummary, Price } from './api'
import { offsetLabel, utcToWall, wallToUtc } from '@/lib/time'

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

// ---- Expected instants, from the IANA zone through Intl's own offset name (not from the code under test) ----

const CASA = 'Africa/Casablanca'
const PARIS = 'Europe/Paris'

/** Offset of `zone` at `ms`, in minutes, read from Intl's "GMT+01:00" / "GMT". */
function offsetMinutes(ms: number, zone = CASA) {
  const name = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'longOffset' })
    .formatToParts(ms)
    .find((p) => p.type === 'timeZoneName')!.value
  const m = /GMT([+-])(\d{2}):(\d{2})/.exec(name)
  return m ? (m[1] === '-' ? -1 : 1) * (Number(m[2]) * 60 + Number(m[3])) : 0
}
/** UTC instant of a wall time in `zone`, far from a clock change. */
const expectedUtc = (wall: string, zone = CASA) => {
  const naive = Date.parse(`${wall}:00Z`)
  return new Date(naive - offsetMinutes(naive, zone) * 60_000).toISOString()
}
/** Wall time in `zone` of an instant, through another Intl formatter (sv-SE prints `2026-11-14 19:00`). */
const expectedWall = (iso: string, zone = CASA) =>
  new Intl.DateTimeFormat('sv-SE', { timeZone: zone, dateStyle: 'short', timeStyle: 'short' }).format(Date.parse(iso)).replace(' ', 'T')
const display = (iso: string, zone: string) => new Intl.DateTimeFormat('fr', { timeZone: zone, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))
const RAMADAN = '2026-03-01T21:00' // Ramadan 1447: +00:00
const NORMAL = '2026-05-16T20:00' // outside Ramadan, before tzdata 2026c's switch to permanent +00:00 (2026-09-20): +01:00

describe('wall time in a venue zone ↔ UTC', () => {
  it('the two test dates are on different offsets (else the next test proves nothing)', () => {
    expect(offsetMinutes(Date.parse(`${RAMADAN}:00Z`))).not.toBe(offsetMinutes(Date.parse(`${NORMAL}:00Z`)))
  })

  it.each([RAMADAN, NORMAL])('%s wall time → the UTC instant of the IANA zone, and back', (wall) => {
    expect(wallToUtc(wall, CASA)).toBe(expectedUtc(wall))
    expect(utcToWall(wallToUtc(wall, CASA), CASA)).toBe(wall)
  })

  it('the same wall time in Casablanca and Paris is two different instants, each the IANA one', () => {
    const casa = wallToUtc(NORMAL, CASA)
    const paris = wallToUtc(NORMAL, PARIS)
    expect(casa).not.toBe(paris)
    expect(casa).toBe(expectedUtc(NORMAL, CASA))
    expect(paris).toBe(expectedUtc(NORMAL, PARIS))
    expect(utcToWall(paris, PARIS)).toBe(NORMAL)
  })

  it.each([
    [NORMAL, CASA],
    [RAMADAN, CASA],
    [NORMAL, PARIS],
    ['2026-07-01T12:00', 'America/St_Johns'], // a half-hour, negative offset
  ])('offset label at %s in %s matches Intl', (wall, zone) => {
    const ms = Date.parse(wallToUtc(wall, zone))
    const m = offsetMinutes(ms, zone)
    const abs = Math.abs(m)
    const expected = `UTC${m < 0 ? '-' : '+'}${String(Math.floor(abs / 60)).padStart(2, '0')}:${String(abs % 60).padStart(2, '0')}`
    expect(offsetLabel(ms, zone)).toBe(expected)
  })
})

// ---- Fake API ----

const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const VENUES: Venue[] = [
  { id: 'v-1', name: 'Grand Stade', city: 'Casablanca', capacity: 45000, timezone: CASA },
  { id: 'v-2', name: 'Salle Pleyel', city: 'Paris', capacity: 3000, timezone: PARIS },
]
const LIST: EventSummary[] = [
  {
    id: 1,
    title: 'Atlas FC vs Rapid Oued',
    event_type: 'FOOTBALL_MATCH',
    starts_at: '2026-11-14T19:00:00Z',
    status: 'ON_SALE',
    venue: { id: 'v-1', name: 'Grand Stade', timezone: CASA },
    quota: 39600,
    sold: 1250,
  },
  {
    id: 2,
    title: 'Gnaoua Live',
    event_type: 'CONCERT',
    starts_at: '2026-12-01T20:00:00Z',
    status: 'DRAFT',
    venue: { id: 'v-2', name: 'Salle Pleyel', timezone: PARIS },
    quota: 0,
    sold: 0,
  },
]
const detail = (over: Partial<EventDetail> = {}): EventDetail => ({
  id: 1,
  venue_id: 'v-1',
  timezone: CASA,
  series_id: null,
  event_type: 'FOOTBALL_MATCH',
  title: 'Atlas FC vs Rapid Oued',
  starts_at: '2026-11-14T19:00:00Z',
  doors_open_at: '2026-11-14T17:30:00Z',
  sale_starts_at: null,
  status: 'DRAFT',
  refund_cutoff_at: null,
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: { opponent: 'Rapid Oued', competition: 'Botola', matchday: 11, broadcaster: 'Arryadia' },
  created_at: '2026-11-01T10:00:00Z',
  ...over,
})

type Call = { method: string; path: string; org: string | null; body: unknown }
type Handler = (call: Call) => Response | undefined

/** /org/me, venues, the event list and event 1 (`event`); POST answers event 99, PATCH merges into 1. `handler` answers first. */
function stubApi(role: Role = 'ADMIN', handler: Handler = () => undefined, event = detail()) {
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
      if (key === 'GET /org/events') return Response.json(LIST)
      if (key === 'GET /org/events/1') return Response.json(event)
      if (key === 'PATCH /org/events/1') return Response.json((event = { ...event, ...(call.body as Partial<EventDetail>) }))
      if (key === 'POST /org/events') return Response.json({ ...detail(), ...(call.body as object), id: 99, status: 'DRAFT' }, { status: 201 })
      if (key === 'GET /org/events/99') return Response.json({ ...detail(), id: 99 })
      if (key === 'GET /org/events/1/prices') return Response.json([])
      if (key === 'GET /org/venues/v-1') return Response.json({ ...VENUES[0], zones: [], gates: [] })
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
const field = (label: string) => screen.getByLabelText(label) as HTMLInputElement
// user-event cannot type into datetime-local in jsdom; a change event is what the browser fires on pick.
const setDateTime = (label: string, value: string) => fireEvent.change(field(label), { target: { value } })
const saveDraft = () => screen.getByRole('button', { name: fr.events.saveDraft })

async function pick(user: ReturnType<typeof userEvent.setup>, combobox: string, option: string) {
  await user.click(screen.getByRole('combobox', { name: combobox }))
  await user.click(await screen.findByRole('option', { name: option }))
}

// ---- B1 ----

it('list: rows in API order with type, time in each venue zone, venue, status badge and sold / quota', async () => {
  const calls = stubApi()
  renderApp('/events')
  const rows = (await screen.findAllByRole('row')).slice(1)
  expect(rows.map((r) => within(r).getAllByRole('cell')[0]!.textContent)).toEqual(['Atlas FC vs Rapid Oued', 'Gnaoua Live'])

  const [first, second] = rows.map((r) => within(r).getAllByRole('cell').map((c) => c.textContent))
  expect(first).toEqual([
    'Atlas FC vs Rapid Oued',
    fr.events.types.FOOTBALL_MATCH,
    display('2026-11-14T19:00:00Z', CASA) + CASA,
    'Grand Stade',
    fr.events.statuses.ON_SALE,
    i18n.t('events.soldOfQuota', { sold: 1250, quota: 39600 }),
  ])
  expect(second![4]).toBe(fr.events.statuses.DRAFT)
  expect(second![2]).toBe(display('2026-12-01T20:00:00Z', PARIS) + PARIS) // its own venue's zone, not Casablanca
  expect(display('2026-12-01T20:00:00Z', PARIS)).not.toBe(display('2026-12-01T20:00:00Z', CASA))
  expect(first![5]).toMatch(/^1\s?250 \/ 39\s?600$/u)

  expect(calls.find((c) => c.path === '/org/events')!.org).toBe('org-a')
  expect(screen.getByRole('link', { name: fr.events.new }).getAttribute('href')).toBe('/events/new')
})

it('list: empty state', async () => {
  stubApi('ADMIN', (c) => (c.path === '/org/events' ? Response.json([]) : undefined))
  renderApp('/events')
  expect(await screen.findByText(fr.events.empty)).toBeTruthy()
})

// ---- B2 create ----

it('create: football metadata goes inside metadata, empty keys left out, T0 sent as UTC; then opens the event', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  const { router } = renderApp('/events/new')
  expect(await screen.findByRole('heading', { name: fr.events.new })).toBeTruthy()
  expect(screen.getByText(fr.events.statuses.DRAFT)).toBeTruthy()
  // Before the first save: Tarifs and Portes need an event; Règles is part of the form.
  expect(screen.getByRole('tab', { name: fr.events.tabs.general }).getAttribute('aria-selected')).toBe('true')
  expect(screen.getByRole('tab', { name: `${fr.events.tabs.prices} (${fr.events.tabs.afterSave})` })).toHaveProperty('disabled', true)
  expect(screen.getByRole('tab', { name: `${fr.events.tabs.gates} (${fr.events.tabs.afterSave})` })).toHaveProperty('disabled', true)
  expect(screen.getByRole('tab', { name: fr.events.tabs.rules })).toHaveProperty('disabled', false)
  expect(screen.getByText(fr.events.form.zoneUnknown, { selector: '#event-starts-at-hint' })).toBeTruthy()
  expect(screen.queryByLabelText(/Série/)).toBeNull() // Q11

  await user.type(field(fr.events.form.title), 'Atlas FC vs Rapid Oued')
  setDateTime(fr.events.form.startsAt, RAMADAN)
  setDateTime(fr.events.form.doorsOpenAt, '2026-03-01T19:30')
  await pick(user, fr.events.form.venue, i18n.t('events.form.venueOption', { name: 'Grand Stade', capacity: 45000 }))
  await user.type(field(fr.events.metadata.opponent), 'Rapid Oued')
  await user.type(field(fr.events.metadata.matchday), '11')
  await user.click(saveDraft())

  await waitFor(() => expect(router.state.location.pathname).toBe('/events/99'))
  expect(writes(calls)).toEqual([
    {
      method: 'POST',
      path: '/org/events',
      org: 'org-a',
      body: {
        venue_id: 'v-1',
        event_type: 'FOOTBALL_MATCH',
        title: 'Atlas FC vs Rapid Oued',
        starts_at: expectedUtc(RAMADAN),
        doors_open_at: expectedUtc('2026-03-01T19:30'),
        per_account_limit: 4,
        reentry_policy: 'NONE',
        metadata: { opponent: 'Rapid Oued', matchday: 11 }, // no competition, no null
      },
    },
  ])
})

it('create: a concert outside Ramadan sends headliner only and its own UTC offset', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/events/new')
  await screen.findByRole('heading', { name: fr.events.new })
  await pick(user, fr.events.form.type, fr.events.types.CONCERT)
  expect(screen.queryByLabelText(fr.events.metadata.opponent)).toBeNull()
  await user.type(field(fr.events.form.title), 'Gnaoua Live')
  setDateTime(fr.events.form.startsAt, NORMAL)
  await pick(user, fr.events.form.venue, i18n.t('events.form.venueOption', { name: 'Salle Pleyel', capacity: 3000 }))
  await user.type(field(fr.events.metadata.headliner), 'Maâlem Hamid')
  await user.click(saveDraft())

  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({
    venue_id: 'v-2',
    event_type: 'CONCERT',
    title: 'Gnaoua Live',
    starts_at: expectedUtc(NORMAL, PARIS), // the chosen venue's zone
    per_account_limit: 4,
    reentry_policy: 'NONE',
    metadata: { headliner: 'Maâlem Hamid' },
  })
})

it('create: zod rejects a missing title, venue and T0, doors after T0, a bad limit and matchday; nothing is sent', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/events/new')
  await screen.findByRole('heading', { name: fr.events.new })
  await user.clear(field(fr.events.form.perAccountLimit))
  await user.type(field(fr.events.form.perAccountLimit), '21')
  await user.type(field(fr.events.metadata.matchday), '2.5')
  await user.click(saveDraft())

  expect(await screen.findByText(fr.events.form.titleRequired)).toBeTruthy()
  expect(screen.getByText(fr.events.form.venueRequired)).toBeTruthy()
  expect(screen.getByText(fr.events.form.startsAtRequired)).toBeTruthy()
  expect(screen.getByText(fr.events.form.limitInvalid)).toBeTruthy()
  expect(screen.getByText(fr.events.form.matchdayInvalid)).toBeTruthy()

  setDateTime(fr.events.form.startsAt, NORMAL)
  setDateTime(fr.events.form.doorsOpenAt, '2026-05-16T20:01')
  await user.click(saveDraft())
  expect(await screen.findByText(fr.events.form.doorsAfterStart)).toBeTruthy()
  expect(screen.getByText(fr.events.form.titleRequired)).toBeTruthy() // shown together
  expect(writes(calls)).toEqual([])
})

it('create: a value typed for the previous type is dropped, not validated, after a type switch', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/events/new')
  await screen.findByRole('heading', { name: fr.events.new })
  await user.type(field(fr.events.metadata.matchday), 'abc') // football field, invalid
  await user.type(field(fr.events.metadata.opponent), 'Rapid Oued')
  await pick(user, fr.events.form.type, fr.events.types.CONCERT)
  await user.type(field(fr.events.form.title), 'Gnaoua Live')
  setDateTime(fr.events.form.startsAt, NORMAL)
  await pick(user, fr.events.form.venue, i18n.t('events.form.venueOption', { name: 'Salle Pleyel', capacity: 3000 }))
  await user.click(saveDraft())

  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  const body = writes(calls)[0]!.body as Record<string, unknown>
  expect(body.event_type).toBe('CONCERT')
  expect(body).not.toHaveProperty('metadata') // no headliner typed; nothing of the football fields
})

it('create: a venue refused on venue_id (deleted meanwhile) says so and refreshes the venue picker', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', (c) =>
    c.method === 'POST'
      ? Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { venue_id: 'unknown' } } }, { status: 422 })
      : undefined,
  )
  renderApp('/events/new')
  await screen.findByRole('heading', { name: fr.events.new })
  await user.type(field(fr.events.form.title), 'Atlas FC vs Rapid Oued')
  setDateTime(fr.events.form.startsAt, NORMAL)
  await pick(user, fr.events.form.venue, i18n.t('events.form.venueOption', { name: 'Grand Stade', capacity: 45000 }))
  await user.click(saveDraft())

  expect(await screen.findByText(fr.events.form.venueGone)).toBeTruthy()
  await waitFor(() => expect(calls.filter((c) => c.path === '/org/venues')).toHaveLength(2))
})

// ---- B2 edit ----

it('edit: a field changed on the server meanwhile is shown and not re-sent; the field the user changed is', async () => {
  const user = userEvent.setup()
  let current = detail()
  const calls = stubApi('ADMIN', (c) => {
    if (c.path !== '/org/events/1') return undefined
    if (c.method === 'PATCH') current = { ...current, ...(c.body as Partial<EventDetail>) }
    return Response.json(current)
  })
  const { queryClient } = renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  await user.clear(field(fr.events.form.perAccountLimit))
  await user.type(field(fr.events.form.perAccountLimit), '6')

  current = detail({ title: 'Renommé par un collègue' }) // someone else's PATCH
  await queryClient.invalidateQueries({ queryKey: ['org', 'org-a', 'events'] })
  await waitFor(() => expect(field(fr.events.form.title).value).toBe('Renommé par un collègue'))
  expect(field(fr.events.form.perAccountLimit).value).toBe('6') // still the user's

  await user.click(saveDraft())
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({ per_account_limit: 6 })
})

it('edit: errors on screen survive a background refresh of the server copy', async () => {
  const user = userEvent.setup()
  let current = detail()
  stubApi('ADMIN', (c) => {
    if (c.path !== '/org/events/1') return undefined
    if (c.method === 'PATCH')
      return Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { per_account_limit: 'range' } } }, { status: 422 })
    return Response.json(current)
  })
  const { queryClient } = renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  await user.clear(field(fr.events.form.perAccountLimit))
  await user.type(field(fr.events.form.perAccountLimit), '5')
  await user.click(saveDraft()) // server: VALIDATION_FAILED on per_account_limit
  await waitFor(() => expect(field(fr.events.form.perAccountLimit).getAttribute('aria-invalid')).toBe('true'))
  await user.clear(field(fr.events.form.title)) // zod, re-validated on change after a submit
  expect(await screen.findByText(fr.events.form.titleRequired)).toBeTruthy()

  current = detail({ status: 'DRAFT', metadata: { opponent: 'Raja' } }) // a colleague's save
  await queryClient.invalidateQueries({ queryKey: ['org', 'org-a', 'events'] })
  await waitFor(() => expect(field(fr.events.metadata.opponent).value).toBe('Raja'))
  expect(screen.getByText(fr.events.form.titleRequired)).toBeTruthy()
  expect(field(fr.events.form.perAccountLimit).getAttribute('aria-invalid')).toBe('true')
})

it('edit: clearing an already set doors opening is refused and nothing is sent', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  setDateTime(fr.events.form.doorsOpenAt, '')
  await user.click(saveDraft())
  expect(await screen.findByText(fr.events.form.doorsRequired)).toBeTruthy()
  expect(writes(calls)).toEqual([])
})

it('edit: a concert with a stored free-form matchday saves without validating it, and keeps it', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', undefined, detail({ event_type: 'CONCERT', metadata: { headliner: 'Hamid', matchday: 'n/a' } }))
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  await user.clear(field(fr.events.metadata.headliner))
  await user.type(field(fr.events.metadata.headliner), 'Hindi Zahra')
  await user.click(saveDraft())
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({ metadata: { headliner: 'Hindi Zahra', matchday: 'n/a' } })
})

it('edit: sends only the changed fields, and the full metadata (unknown keys kept) when metadata changed', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/events/1')
  expect(await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })).toBeTruthy()
  // Stored UTC shown as Casablanca wall time; type and venue fixed.
  expect(field(fr.events.form.startsAt).value).toBe(expectedWall('2026-11-14T19:00:00Z'))
  expect(field(fr.events.form.doorsOpenAt).value).toBe(expectedWall('2026-11-14T17:30:00Z'))
  expect(screen.getByRole('combobox', { name: fr.events.form.type })).toHaveProperty('disabled', true)
  expect(screen.getByRole('combobox', { name: fr.events.form.venue })).toHaveProperty('disabled', true)
  expect(saveDraft()).toHaveProperty('disabled', true) // nothing changed yet

  await user.clear(field(fr.events.form.title))
  await user.type(field(fr.events.form.title), 'Atlas FC vs Rapid Oued (J11)')
  await user.click(saveDraft())
  expect(await screen.findByText(fr.events.saved)).toBeTruthy() // toast
  expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/org/events/1', org: 'org-a', body: { title: 'Atlas FC vs Rapid Oued (J11)' } }])

  await user.clear(field(fr.events.metadata.competition))
  await user.clear(field(fr.events.metadata.matchday))
  await user.type(field(fr.events.metadata.matchday), '12')
  setDateTime(fr.events.form.startsAt, '2026-11-14T21:30')
  await user.click(saveDraft())
  await waitFor(() => expect(writes(calls)).toHaveLength(2))
  expect(writes(calls)[1]!.body).toEqual({
    starts_at: expectedUtc('2026-11-14T21:30'),
    metadata: { opponent: 'Rapid Oued', matchday: 12, broadcaster: 'Arryadia' }, // competition cleared → left out
  })
})

it('edit: VALIDATION_FAILED keys (snake_case and metadata.x) land on their fields; EVENT_NOT_EDITABLE on the form', async () => {
  const user = userEvent.setup()
  let reply = Response.json(
    { code: 'VALIDATION_FAILED', message: 'x', details: { fields: { per_account_limit: 'range', 'metadata.matchday': 'type' } } },
    { status: 422 },
  )
  stubApi('ADMIN', (c) => (c.method === 'PATCH' ? reply : undefined))
  renderApp('/events/1')
  await user.clear(await screen.findByLabelText(fr.events.form.perAccountLimit))
  await user.type(field(fr.events.form.perAccountLimit), '5')
  await user.click(saveDraft())
  await waitFor(() => expect(field(fr.events.form.perAccountLimit).getAttribute('aria-invalid')).toBe('true'))
  expect(field(fr.events.metadata.matchday).getAttribute('aria-invalid')).toBe('true')

  reply = Response.json({ code: 'EVENT_NOT_EDITABLE', message: 'x', details: {} }, { status: 409 })
  await user.type(field(fr.events.form.perAccountLimit), '{Backspace}6')
  await user.click(saveDraft())
  expect(await screen.findByText(fr.errors.EVENT_NOT_EDITABLE)).toBeTruthy()
})

it('edit: an unknown id goes back to the list with a notice', async () => {
  stubApi()
  const { router } = renderApp('/events/nope')
  expect(await screen.findByText(fr.events.notFound)).toBeTruthy()
  expect(router.state.location.pathname).toBe('/events')
})

it.each(['GATES_OPEN', 'CLOSED'] as const)('edit: a %s event is read-only for ADMIN', async (status) => {
  stubApi('ADMIN', undefined, detail({ status }))
  renderApp('/events/1')
  expect(await screen.findByText(fr.events.notEditable)).toBeTruthy()
  expect(screen.queryByRole('button', { name: fr.events.saveDraft })).toBeNull()
  expect(field(fr.events.form.title).matches(':disabled')).toBe(true)
})

it('FINANCE: no write action on the list or the event, fields read-only', async () => {
  stubApi('FINANCE')
  renderApp('/events')
  expect(await screen.findByText('Gnaoua Live')).toBeTruthy()
  expect(screen.getByText(fr.events.readOnly)).toBeTruthy()
  expect(screen.queryByRole('link', { name: fr.events.new })).toBeNull()

  cleanup()
  renderApp('/events/1')
  expect(await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })).toBeTruthy()
  expect(screen.queryByRole('button', { name: fr.events.saveDraft })).toBeNull()
  expect(field(fr.events.form.title).matches(':disabled')).toBe(true)
  expect(field(fr.events.metadata.opponent).matches(':disabled')).toBe(true)
})

// ---- B2 tabs (BO-5) ----

const VENUE_DETAIL: VenueDetail = {
  ...VENUES[0]!,
  zones: [
    { id: 1, code: 'HONNEUR', name: 'Tribune d’honneur', capacity: 3000, seated: true },
    { id: 2, code: 'NORD', name: 'Tribune Nord', capacity: 20000, seated: false },
  ],
  gates: [
    { id: 'g-1', code: 'P1', name: 'Porte 1', zone_ids: [1], directions: null, latitude: null, longitude: null },
    { id: 'g-2', code: 'P2', name: 'Porte 2', zone_ids: [2], directions: null, latitude: null, longitude: null },
  ],
}
// By zone_id then name, as the API lists them.
const PRICES: Price[] = [
  { id: 'p-1', zone_id: 1, zone: { code: 'HONNEUR', name: 'Tribune d’honneur' }, name: 'Plein tarif', price_mad: 20000, quota: 3000, sold: 10 },
  { id: 'p-2', zone_id: 2, zone: { code: 'NORD', name: 'Tribune Nord' }, name: 'Abonnés', price_mad: 5050, quota: 18000, sold: 0 },
]

/** stubApi plus the venue detail, prices (kept in state) and publish; event 1 is kept in state too. `handler` answers first. */
function stubB2(role: Role = 'ADMIN', handler: Handler = () => undefined, initial = detail()) {
  let event = initial
  let prices = PRICES.map((p) => ({ ...p }))
  return stubApi(
    role,
    (c) => {
      const handled = handler(c)
      if (handled) return handled
      const key = `${c.method} ${c.path}`
      if (key === 'GET /org/events/1') return Response.json(event)
      if (key === 'GET /org/venues/v-1') return Response.json(VENUE_DETAIL)
      if (key === 'GET /org/events/1/prices') return Response.json(prices)
      if (key === 'POST /org/events/1/prices') {
        const p = { id: 'p-new', sold: 0, ...(c.body as Omit<Price, 'id' | 'sold'>) }
        prices = [...prices, p]
        return Response.json(p, { status: 201 })
      }
      const one = /^(PATCH|DELETE) \/org\/events\/1\/prices\/(.+)$/.exec(key)
      if (one?.[1] === 'PATCH') {
        prices = prices.map((p) => (p.id === one[2] ? { ...p, ...(c.body as Partial<Price>) } : p))
        return Response.json(prices.find((p) => p.id === one[2]))
      }
      if (one?.[1] === 'DELETE') {
        prices = prices.filter((p) => p.id !== one[2])
        return new Response(null, { status: 204 })
      }
      if (key === 'POST /org/events/1/publish') {
        event = { ...event, status: 'ON_SALE', refund_cutoff_at: '2026-11-14T18:00:00Z' }
        return Response.json(event)
      }
      return undefined
    },
    initial,
  )
}

async function openTab(user: ReturnType<typeof userEvent.setup>, name: string) {
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  await user.click(screen.getByRole('tab', { name }))
  return screen.getByRole('tabpanel', { name })
}
const cellTexts = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent)

it('prices: rows with the DH price, quota, sold and zone usage, then the total line', async () => {
  const user = userEvent.setup()
  stubB2()
  renderApp('/events/1')
  const panel = await openTab(user, fr.events.tabs.prices)
  await within(panel).findByText('Plein tarif')
  const rows = within(panel).getAllByRole('row').slice(1)
  const n = (x: number) => x.toLocaleString('fr')
  expect(cellTexts(rows[0]!).slice(0, 6)).toEqual([
    'Tribune d’honneur',
    'Plein tarif',
    formatDh(20000, 'fr'),
    n(3000),
    n(10),
    i18n.t('events.prices.zoneUsage', { used: 3000, capacity: 3000 }),
  ])
  expect(cellTexts(rows[1]!)[2]).toBe(formatDh(5050, 'fr')) // "50,50"
  expect(cellTexts(rows[2]!)[0]).toBe(
    i18n.t('events.prices.total', { quota: 21000, capacity: 45000, amount: formatDh(3000 * 20000 + 18000 * 5050, 'fr') }),
  )
})

it('prices: add a category; "50,555" DH is refused, "50,5" is sent as 5050 centimes', async () => {
  const user = userEvent.setup()
  const calls = stubB2()
  renderApp('/events/1')
  const panel = await openTab(user, fr.events.tabs.prices)
  await user.click(await within(panel).findByRole('button', { name: fr.events.prices.add }))
  await pick(user, fr.events.prices.zone, 'Tribune Nord')
  await user.type(within(panel).getByLabelText(fr.events.prices.name), 'Étudiants')
  await user.type(within(panel).getByLabelText(fr.events.prices.price), '50,555')
  await user.type(within(panel).getByLabelText(fr.events.prices.quota), '100')
  await user.click(within(panel).getByRole('button', { name: fr.common.create }))
  expect(await within(panel).findByText(fr.events.prices.priceInvalid)).toBeTruthy()
  expect(writes(calls)).toEqual([])

  await user.clear(within(panel).getByLabelText(fr.events.prices.price))
  await user.type(within(panel).getByLabelText(fr.events.prices.price), '50,5{Enter}') // Enter submits the row
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]).toEqual({
    method: 'POST',
    path: '/org/events/1/prices',
    org: 'org-a',
    body: { zone_id: 2, name: 'Étudiants', price_mad: 5050, quota: 100 },
  })
  expect(await within(panel).findByText('Étudiants')).toBeTruthy() // refetched
})

it('prices: edit starts from the stored values and sends only what changed', async () => {
  const user = userEvent.setup()
  const calls = stubB2()
  renderApp('/events/1')
  const panel = await openTab(user, fr.events.tabs.prices)
  await user.click(await within(panel).findByRole('button', { name: i18n.t('events.prices.editLabel', { name: 'Abonnés' }) }))
  expect((within(panel).getByLabelText(fr.events.prices.price) as HTMLInputElement).value).toBe('50,50')
  const quota = within(panel).getByLabelText(fr.events.prices.quota)
  await user.clear(quota)
  await user.type(quota, '17000')
  await user.click(within(panel).getByRole('button', { name: fr.common.save }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/org/events/1/prices/p-2', org: 'org-a', body: { quota: 17000 } }])
})

it.each([
  ['QUOTA_BELOW_SOLD', 422, {}, 'quota', (): string => i18n.t('events.prices.quotaBelowSold', { sold: 10 })],
  ['QUOTA_EXCEEDS_CAPACITY', 422, {}, 'quota', (): string => i18n.t('events.prices.quotaExceedsCapacity', { capacity: 3000 })],
  // Duplicate: reason NAME_TAKEN. Any other 422 on name stays generic, whatever its message says.
  ['VALIDATION_FAILED', 422, { reason: 'NAME_TAKEN', fields: { name: 'x' } }, 'name', (): string => fr.events.prices.nameTaken],
  ['VALIDATION_FAILED', 422, { fields: { name: 'already used for this zone' } }, 'name', (): string => fr.common.fieldInvalid],
  ['EVENT_NOT_EDITABLE', 409, {}, null, (): string => fr.errors.EVENT_NOT_EDITABLE],
] as const)('prices: %s is shown on the right field', async (code, status, details, fieldName, message) => {
  const user = userEvent.setup()
  stubB2('ADMIN', (c) => (c.method === 'PATCH' ? Response.json({ code, message: 'x', details }, { status }) : undefined))
  renderApp('/events/1')
  const panel = await openTab(user, fr.events.tabs.prices)
  await user.click(await within(panel).findByRole('button', { name: i18n.t('events.prices.editLabel', { name: 'Plein tarif' }) }))
  await user.clear(within(panel).getByLabelText(fr.events.prices.quota))
  await user.type(within(panel).getByLabelText(fr.events.prices.quota), '5')
  await user.click(within(panel).getByRole('button', { name: fr.common.save }))
  // Testing Library collapses the DOM's whitespace (FR thousands separator U+202F included), not the matcher's.
  expect(await within(panel).findByText(message().replace(/\s+/gu, ' '))).toBeTruthy()
  if (fieldName) {
    const input = within(panel).getByLabelText(fieldName === 'name' ? fr.events.prices.name : fr.events.prices.quota)
    expect(input.getAttribute('aria-invalid')).toBe('true')
  }
})

it('prices: delete asks first; PRICE_IN_USE keeps the dialog open with its copy; a free one goes', async () => {
  const user = userEvent.setup()
  const calls = stubB2('ADMIN', (c) =>
    c.method === 'DELETE' && c.path.endsWith('/p-1') ? Response.json({ code: 'PRICE_IN_USE', message: 'x', details: {} }, { status: 409 }) : undefined,
  )
  renderApp('/events/1')
  const panel = await openTab(user, fr.events.tabs.prices)
  await user.click(await within(panel).findByRole('button', { name: i18n.t('events.prices.deleteLabel', { name: 'Plein tarif' }) }))
  const confirm = await screen.findByRole('alertdialog', { name: i18n.t('events.prices.deleteTitle', { name: 'Plein tarif' }) })
  await user.click(within(confirm).getByRole('button', { name: fr.common.delete }))
  expect(await within(confirm).findByText(fr.errors.PRICE_IN_USE)).toBeTruthy()
  await user.click(within(confirm).getByRole('button', { name: fr.common.cancel }))
  await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())

  await user.click(within(panel).getByRole('button', { name: i18n.t('events.prices.deleteLabel', { name: 'Abonnés' }) }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.common.delete }))
  await waitFor(() => expect(within(panel).queryByText('Abonnés')).toBeNull())
  expect(writes(calls).map((c) => `${c.method} ${c.path}`)).toEqual(['DELETE /org/events/1/prices/p-1', 'DELETE /org/events/1/prices/p-2'])
})

it('gates & zones: the venue matrix, read-only, with a note that it comes from the venue', async () => {
  const user = userEvent.setup()
  stubB2()
  renderApp('/events/1')
  const panel = await openTab(user, fr.events.tabs.gates)
  expect(await within(panel).findByText(fr.events.gates.description)).toBeTruthy()
  const boxes = within(panel).getAllByRole('checkbox')
  expect(boxes).toHaveLength(4)
  boxes.forEach((b) => expect(b).toHaveProperty('disabled', true))
  expect(within(panel).getByRole('checkbox', { name: i18n.t('venues.matrix.cell', { gate: 'P1', zone: 'HONNEUR' }) }).getAttribute('aria-checked')).toBe('true')
  expect(within(panel).queryAllByRole('button')).toEqual([])
})

it('rules: ré-entrée is read-only "Aucune" (Q33), never in the PATCH; the refund freeze shows its instant in the venue zone', async () => {
  const user = userEvent.setup()
  const calls = stubB2('ADMIN', undefined, detail({ status: 'ON_SALE', refund_cutoff_at: '2026-11-14T18:00:00Z' }))
  renderApp('/events/1')
  const panel = await openTab(user, fr.events.tabs.rules)
  expect(within(panel).getByText(i18n.t('events.rules.refundFreezeAt', { date: display('2026-11-14T18:00:00Z', CASA), zone: CASA }))).toBeTruthy()
  expect((within(panel).getByLabelText(fr.events.rules.refundFreeze) as HTMLInputElement).readOnly).toBe(true)
  const reentry = within(panel).getByLabelText(fr.events.rules.reentry) as HTMLInputElement
  expect(reentry.readOnly).toBe(true)
  expect(reentry.value).toBe(fr.events.rules.reentryPolicies.NONE)
  expect(within(panel).queryByRole('combobox', { name: fr.events.rules.reentry })).toBeNull()
  expect(screen.queryByText(fr.events.rules.reentryPolicies.ONE_EXIT)).toBeNull()

  await user.clear(within(panel).getByLabelText(fr.events.form.perAccountLimit))
  await user.type(within(panel).getByLabelText(fr.events.form.perAccountLimit), '6')
  await user.click(screen.getByRole('button', { name: fr.events.saveChanges }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/org/events/1', org: 'org-a', body: { per_account_limit: 6 } }])
})

it('rules: an invalid billets max is revealed by switching to the Règles tab', async () => {
  const user = userEvent.setup()
  stubB2()
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  await user.clear(field(fr.events.form.perAccountLimit))
  await user.type(field(fr.events.form.perAccountLimit), '30')
  await user.click(saveDraft())
  await waitFor(() => expect(screen.getByRole('tab', { name: fr.events.tabs.rules }).getAttribute('aria-selected')).toBe('true'))
  expect(screen.getByText(fr.events.form.limitInvalid)).toBeTruthy()
})

it('publish: confirm, POST publish, then the badge says En vente', async () => {
  const user = userEvent.setup()
  const calls = stubB2()
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  expect(screen.getByText(fr.events.statuses.DRAFT)).toBeTruthy()
  await user.click(screen.getByRole('button', { name: fr.events.publish.action }))
  const dialog = await screen.findByRole('alertdialog', { name: fr.events.publish.title })
  expect(writes(calls)).toEqual([])
  await user.click(within(dialog).getByRole('button', { name: fr.events.publish.confirm }))

  expect(await screen.findByText(fr.events.statuses.ON_SALE)).toBeTruthy()
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/events/1/publish', org: 'org-a', body: undefined }])
  expect(screen.queryByRole('button', { name: fr.events.publish.action })).toBeNull()
  expect(await screen.findByText(fr.events.publish.done)).toBeTruthy() // toast
})

it.each([
  [422, { code: 'VALIDATION_FAILED', details: { reason: 'NO_QUOTA', fields: { prices: 'empty' } } }, fr.events.publish.NO_QUOTA],
  [422, { code: 'VALIDATION_FAILED', details: { reason: 'STARTS_IN_PAST', fields: { starts_at: 'past' } } }, fr.events.publish.STARTS_IN_PAST],
] as const)('publish: %i %j shows its FR copy in the dialog', async (status, body, message) => {
  const user = userEvent.setup()
  stubB2('ADMIN', (c) => (c.path === '/org/events/1/publish' ? Response.json({ ...body, message: 'x' }, { status }) : undefined))
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  await user.click(screen.getByRole('button', { name: fr.events.publish.action }))
  const dialog = await screen.findByRole('alertdialog')
  await user.click(within(dialog).getByRole('button', { name: fr.events.publish.confirm }))
  expect(await within(dialog).findByText(message)).toBeTruthy()
  expect(screen.getByRole('alertdialog')).toBeTruthy()
})

it('time zone: an event of a Paris venue is entered and shown in Paris time, with the zone and its offset', async () => {
  stubB2('ADMIN', undefined, detail({ timezone: PARIS }))
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  expect(field(fr.events.form.startsAt).value).toBe(expectedWall('2026-11-14T19:00:00Z', PARIS))
  const m = offsetMinutes(Date.parse('2026-11-14T19:00:00Z'), PARIS)
  const offset = `UTC+${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
  expect(document.getElementById('event-starts-at-hint')!.textContent).toBe(i18n.t('events.form.zoneHint', { zone: PARIS, offset }))
})

it('FINANCE: read-only on every tab, no publish', async () => {
  const user = userEvent.setup()
  stubB2('FINANCE')
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  expect(screen.queryByRole('button', { name: fr.events.publish.action })).toBeNull()
  expect(screen.queryByRole('button', { name: fr.events.saveDraft })).toBeNull()

  const prices = await openTab(user, fr.events.tabs.prices)
  expect(await within(prices).findByText('Plein tarif')).toBeTruthy()
  expect(within(prices).queryAllByRole('button')).toEqual([])

  const gates = await openTab(user, fr.events.tabs.gates)
  const boxes = await within(gates).findAllByRole('checkbox')
  boxes.forEach((b) => expect(b).toHaveProperty('disabled', true))

  const rules = await openTab(user, fr.events.tabs.rules)
  expect((within(rules).getByLabelText(fr.events.rules.reentry) as HTMLInputElement).readOnly).toBe(true)
  expect(within(rules).getByLabelText(fr.events.form.perAccountLimit).matches(':disabled')).toBe(true)
})

it('publish: EVENT_NOT_EDITABLE stays visible on the page after the refetch shows the event on sale', async () => {
  const user = userEvent.setup()
  let status: EventDetail['status'] = 'DRAFT'
  stubB2('ADMIN', (c) => {
    if (c.path === '/org/events/1/publish') {
      status = 'ON_SALE' // a colleague published first
      return Response.json({ code: 'EVENT_NOT_EDITABLE', message: 'x', details: {} }, { status: 409 })
    }
    return c.method === 'GET' && c.path === '/org/events/1' ? Response.json(detail({ status })) : undefined
  })
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  await user.click(screen.getByRole('button', { name: fr.events.publish.action }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.events.publish.confirm }))

  expect(await screen.findByText(fr.events.statuses.ON_SALE)).toBeTruthy() // refetched
  expect(screen.queryByRole('button', { name: fr.events.publish.action })).toBeNull()
  expect(screen.getByRole('alert').textContent).toBe(fr.events.publish.EVENT_NOT_EDITABLE)
  expect(document.activeElement).toBe(screen.getByRole('alert')) // not <body> after the button unmounted
})

it('publish waits for an open price row; the row closes when its price is gone after a refetch', async () => {
  const user = userEvent.setup()
  let gone = false
  stubB2('ADMIN', (c) =>
    gone && c.method === 'GET' && c.path === '/org/events/1/prices' ? Response.json(PRICES.filter((p) => p.id !== 'p-2')) : undefined,
  )
  const { queryClient } = renderApp('/events/1')
  const panel = await openTab(user, fr.events.tabs.prices)
  const publish = screen.getByRole('button', { name: fr.events.publish.action })
  expect(publish).toHaveProperty('disabled', false)
  await user.click(await within(panel).findByRole('button', { name: i18n.t('events.prices.editLabel', { name: 'Abonnés' }) }))
  await waitFor(() => expect(publish).toHaveProperty('disabled', true))
  expect(screen.getByText(fr.events.publish.saveFirst)).toBeTruthy()

  gone = true // deleted elsewhere
  await queryClient.invalidateQueries({ queryKey: ['org', 'org-a', 'events'] })
  await waitFor(() => expect(within(panel).queryByText('Abonnés')).toBeNull())
  expect(within(panel).getByRole('button', { name: fr.events.prices.add })).toHaveProperty('disabled', false)
  await waitFor(() => expect(publish).toHaveProperty('disabled', false))
})

it('event ids: /events/01 reads the same event and key as /events/1; a non-integer id goes back to the list', async () => {
  const calls = stubB2()
  const { queryClient } = renderApp('/events/01')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  expect(calls.filter((c) => c.method === 'GET' && c.path.startsWith('/org/events/')).map((c) => c.path)).toContain('/org/events/1')
  expect(calls.some((c) => c.path.startsWith('/org/events/01'))).toBe(false)
  expect(queryClient.getQueryData(['org', 'org-a', 'events', '1'])).toBeTruthy()

  cleanup()
  stubB2()
  const { router } = renderApp('/events/1abc')
  expect(await screen.findByText(fr.events.notFound)).toBeTruthy()
  expect(router.state.location.pathname).toBe('/events')
})

// ---- B2 sale opening, nominative row (BO-6) ----

it.each([
  ['Grand Stade', 45000, CASA],
  ['Salle Pleyel', 3000, PARIS],
] as const)('create: the sale opening at %s is read in its zone (%s) and sent as UTC', async (name, capacity, zone) => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/events/new')
  await screen.findByRole('heading', { name: fr.events.new })
  await user.type(field(fr.events.form.title), 'Atlas FC vs Rapid Oued')
  setDateTime(fr.events.form.startsAt, NORMAL)
  setDateTime(fr.events.form.saleStartsAt, '2026-05-01T10:00')
  await pick(user, fr.events.form.venue, i18n.t('events.form.venueOption', { name, capacity }))
  const offset = offsetLabel(Date.parse(expectedUtc('2026-05-01T10:00', zone)), zone)
  expect(document.getElementById('event-sale-starts-at-hint')!.textContent).toBe(
    `${i18n.t('events.form.zoneHint', { zone, offset })} · ${fr.events.form.saleStartsAtHint}`,
  )
  await user.click(saveDraft())
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect((writes(calls)[0]!.body as Record<string, unknown>).sale_starts_at).toBe(expectedUtc('2026-05-01T10:00', zone))
  expect(expectedUtc('2026-05-01T10:00', CASA)).not.toBe(expectedUtc('2026-05-01T10:00', PARIS))
})

it('sale opening: must be strictly before T0 − 60 min (FR copy, nothing sent); one minute earlier passes', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/events/1') // T0 2026-11-14T19:00Z
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  const t0 = field(fr.events.form.startsAt).value
  const minus = (min: number) => utcToWall(new Date(Date.parse(wallToUtc(t0, CASA)) - min * 60_000).toISOString(), CASA)
  setDateTime(fr.events.form.saleStartsAt, minus(60))
  await user.click(saveDraft())
  expect(await screen.findByText(fr.events.form.saleBeforeStart)).toBeTruthy()
  expect(field(fr.events.form.saleStartsAt).getAttribute('aria-invalid')).toBe('true')
  expect(writes(calls)).toEqual([])

  setDateTime(fr.events.form.saleStartsAt, minus(61))
  await user.click(saveDraft())
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({ sale_starts_at: new Date(Date.parse('2026-11-14T19:00:00Z') - 61 * 60_000).toISOString() })
})

it('sale opening: a stored one is shown in the venue zone, not re-sent when unchanged, and cannot be cleared', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', undefined, detail({ sale_starts_at: '2026-11-01T09:00:00Z' }))
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  expect(field(fr.events.form.saleStartsAt).value).toBe(expectedWall('2026-11-01T09:00:00Z'))
  await user.clear(field(fr.events.form.title))
  await user.type(field(fr.events.form.title), 'Renommé')
  await user.click(saveDraft())
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({ title: 'Renommé' })

  setDateTime(fr.events.form.saleStartsAt, '')
  await user.click(saveDraft())
  expect(await screen.findByText(fr.events.form.saleRequired)).toBeTruthy()
  expect(writes(calls)).toHaveLength(1)
})

it('sale opening: a server 422 on sale_starts_at opens Général and shows the FR rule on the field', async () => {
  const user = userEvent.setup()
  stubB2('ADMIN', (c) =>
    c.method === 'PATCH'
      ? Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { sale_starts_at: 'too late' } } }, { status: 422 })
      : undefined,
  )
  renderApp('/events/1')
  const rules = await openTab(user, fr.events.tabs.rules)
  await user.clear(within(rules).getByLabelText(fr.events.form.perAccountLimit))
  await user.type(within(rules).getByLabelText(fr.events.form.perAccountLimit), '6')
  await user.click(saveDraft())
  await waitFor(() => expect(screen.getByRole('tab', { name: fr.events.tabs.general }).getAttribute('aria-selected')).toBe('true'))
  expect(screen.getByText(fr.events.form.saleBeforeStart)).toBeTruthy()
  expect(field(fr.events.form.saleStartsAt).getAttribute('aria-invalid')).toBe('true')
})

it('FINANCE: the sale opening is shown read-only', async () => {
  stubApi('FINANCE', undefined, detail({ sale_starts_at: '2026-11-01T09:00:00Z' }))
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  expect(field(fr.events.form.saleStartsAt).value).toBe(expectedWall('2026-11-01T09:00:00Z'))
  expect(field(fr.events.form.saleStartsAt).matches(':disabled')).toBe(true)
})

it('rules: "Billets nominatifs · toujours" is a read-only row with its explanation', async () => {
  const user = userEvent.setup()
  stubB2()
  renderApp('/events/1')
  const panel = await openTab(user, fr.events.tabs.rules)
  const named = within(panel).getByLabelText(fr.events.rules.named) as HTMLInputElement
  expect(named.value).toBe(fr.events.rules.namedValue)
  expect(named.readOnly).toBe(true)
  expect(document.getElementById(named.getAttribute('aria-describedby')!)!.textContent).toBe(fr.events.rules.namedHint)
})

it('sale opening: across clock changes the T0 − 60 rule compares instants, as the server does (Europe/Paris)', async () => {
  // Premises from Intl: spring forward 2026-03-29 (01:30 CET and 03:00 CEST are 30 min apart);
  // fall back 2026-10-25 (01:59 CEST and the later 02:59, CET, are 2 h apart).
  expect(expectedWall('2026-03-29T00:30:00Z', PARIS)).toBe('2026-03-29T01:30')
  expect(expectedWall('2026-03-29T01:00:00Z', PARIS)).toBe('2026-03-29T03:00')
  expect(expectedWall('2026-10-24T23:59:00Z', PARIS)).toBe('2026-10-25T01:59')
  expect(expectedWall('2026-10-24T23:00:00Z', PARIS)).toBe('2026-10-25T01:00')
  expect(expectedWall('2026-10-25T01:59:00Z', PARIS)).toBe('2026-10-25T02:59')
  expect(offsetMinutes(Date.parse('2026-10-25T01:59:00Z'), PARIS)).toBe(60) // CET: the later 02:59

  const user = userEvent.setup()
  const calls = stubApi('ADMIN', undefined, detail({ timezone: PARIS }))
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  setDateTime(fr.events.form.startsAt, '2026-03-29T03:00')
  setDateTime(fr.events.form.doorsOpenAt, '2026-03-29T01:00')
  setDateTime(fr.events.form.saleStartsAt, '2026-03-29T01:30') // 90 min on the clock, 30 min in time: refused
  await user.click(saveDraft())
  expect(await screen.findByText(fr.events.form.saleBeforeStart)).toBeTruthy()
  expect(writes(calls)).toEqual([])

  setDateTime(fr.events.form.startsAt, '2026-10-25T02:59')
  setDateTime(fr.events.form.doorsOpenAt, '2026-10-25T01:00') // CEST, as is the sale opening
  setDateTime(fr.events.form.saleStartsAt, '2026-10-25T01:59') // 60 min on the clock, 2 h in time: accepted
  await user.click(saveDraft())
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({
    starts_at: '2026-10-25T01:59:00.000Z',
    doors_open_at: '2026-10-24T23:00:00.000Z',
    sale_starts_at: '2026-10-24T23:59:00.000Z',
  })
})

it('?tab=prices (Aperçu shortcut) opens a saved event on Tarifs & quotas; a new event ignores it', async () => {
  stubB2()
  renderApp('/events/1?tab=prices')
  expect(await screen.findByRole('heading', { name: fr.events.prices.title })).toBeTruthy()
  expect(screen.getByRole('tab', { name: fr.events.tabs.prices }).getAttribute('aria-selected')).toBe('true')
  cleanup()

  stubB2()
  renderApp('/events/new?tab=prices')
  await screen.findByRole('heading', { name: fr.events.new })
  expect(screen.getByRole('tab', { name: fr.events.tabs.general }).getAttribute('aria-selected')).toBe('true')
})
