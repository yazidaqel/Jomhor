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
import type { Venue } from '@/features/venues/api'
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'
import type { EventDetail, EventSummary } from './api'
import { utcToWall, wallToUtc } from './time'

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

/** Offset of Africa/Casablanca at `ms`, in minutes, read from Intl's "GMT+01:00" / "GMT". */
function offsetMinutes(ms: number) {
  const name = new Intl.DateTimeFormat('en-US', { timeZone: 'Africa/Casablanca', timeZoneName: 'longOffset' })
    .formatToParts(ms)
    .find((p) => p.type === 'timeZoneName')!.value
  const m = /GMT([+-])(\d{2}):(\d{2})/.exec(name)
  return m ? (m[1] === '-' ? -1 : 1) * (Number(m[2]) * 60 + Number(m[3])) : 0
}
/** UTC instant of a Casablanca wall time far from a clock change. */
const expectedUtc = (wall: string) => {
  const naive = Date.parse(`${wall}:00Z`)
  return new Date(naive - offsetMinutes(naive) * 60_000).toISOString()
}
/** Casablanca wall time of an instant, through another Intl formatter (sv-SE prints `2026-11-14 19:00`). */
const expectedWall = (iso: string) =>
  new Intl.DateTimeFormat('sv-SE', { timeZone: 'Africa/Casablanca', dateStyle: 'short', timeStyle: 'short' }).format(Date.parse(iso)).replace(' ', 'T')
const RAMADAN = '2026-03-01T21:00' // Ramadan 1447: +00:00
const NORMAL = '2026-05-16T20:00' // outside Ramadan, before tzdata 2026c's switch to permanent +00:00 (2026-09-20): +01:00

describe('Casablanca ↔ UTC', () => {
  it('the two test dates are on different offsets (else the next test proves nothing)', () => {
    expect(offsetMinutes(Date.parse(`${RAMADAN}:00Z`))).not.toBe(offsetMinutes(Date.parse(`${NORMAL}:00Z`)))
  })

  it.each([RAMADAN, NORMAL])('%s wall time → the UTC instant of the IANA zone, and back', (wall) => {
    expect(wallToUtc(wall)).toBe(expectedUtc(wall))
    expect(utcToWall(wallToUtc(wall))).toBe(wall)
  })
})

// ---- Fake API ----

const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const VENUES: Venue[] = [
  { id: 'v-1', name: 'Grand Stade', city: 'Casablanca', capacity: 45000 },
  { id: 'v-2', name: 'Salle Moulay', city: 'Rabat', capacity: 3000 },
]
const LIST: EventSummary[] = [
  {
    id: 'e-1',
    title: 'Atlas FC vs Rapid Oued',
    event_type: 'FOOTBALL_MATCH',
    starts_at: '2026-11-14T19:00:00Z',
    status: 'ON_SALE',
    venue: { id: 'v-1', name: 'Grand Stade' },
    quota: 39600,
    sold: 1250,
  },
  {
    id: 'e-2',
    title: 'Gnaoua Live',
    event_type: 'CONCERT',
    starts_at: '2026-12-01T20:00:00Z',
    status: 'DRAFT',
    venue: { id: 'v-2', name: 'Salle Moulay' },
    quota: 0,
    sold: 0,
  },
]
const detail = (over: Partial<EventDetail> = {}): EventDetail => ({
  id: 'e-1',
  venue_id: 'v-1',
  series_id: null,
  event_type: 'FOOTBALL_MATCH',
  title: 'Atlas FC vs Rapid Oued',
  starts_at: '2026-11-14T19:00:00Z',
  doors_open_at: '2026-11-14T17:30:00Z',
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

/** /org/me, venues, the event list and e-1 (`event`); POST answers e-new, PATCH merges into e-1. `handler` answers first. */
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
      if (key === 'GET /org/events/e-1') return Response.json(event)
      if (key === 'PATCH /org/events/e-1') return Response.json((event = { ...event, ...(call.body as Partial<EventDetail>) }))
      if (key === 'POST /org/events') return Response.json({ ...detail(), ...(call.body as object), id: 'e-new', status: 'DRAFT' }, { status: 201 })
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

it('list: rows in API order with type, Casablanca time, venue, status badge and sold / quota', async () => {
  const calls = stubApi()
  renderApp('/events')
  const rows = (await screen.findAllByRole('row')).slice(1)
  expect(rows.map((r) => within(r).getAllByRole('cell')[0]!.textContent)).toEqual(['Atlas FC vs Rapid Oued', 'Gnaoua Live'])

  const [first, second] = rows.map((r) => within(r).getAllByRole('cell').map((c) => c.textContent))
  expect(first).toEqual([
    'Atlas FC vs Rapid Oued',
    fr.events.types.FOOTBALL_MATCH,
    new Intl.DateTimeFormat('fr', { timeZone: 'Africa/Casablanca', dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse('2026-11-14T19:00:00Z')),
    'Grand Stade',
    fr.events.statuses.ON_SALE,
    i18n.t('events.soldOfQuota', { sold: 1250, quota: 39600 }),
  ])
  expect(second![4]).toBe(fr.events.statuses.DRAFT)
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
  // Lot 0: Général only; the other tabs are BO-5.
  expect(screen.getByRole('tab', { name: fr.events.tabs.general }).getAttribute('aria-selected')).toBe('true')
  expect(screen.getByRole('tab', { name: `${fr.events.tabs.prices} (${fr.events.tabs.soon})` })).toHaveProperty('disabled', true)
  expect(screen.queryByLabelText(/Série/)).toBeNull() // Q11

  await user.type(field(fr.events.form.title), 'Atlas FC vs Rapid Oued')
  setDateTime(fr.events.form.startsAt, RAMADAN)
  setDateTime(fr.events.form.doorsOpenAt, '2026-03-01T19:30')
  await pick(user, fr.events.form.venue, i18n.t('events.form.venueOption', { name: 'Grand Stade', capacity: 45000 }))
  await user.type(field(fr.events.metadata.opponent), 'Rapid Oued')
  await user.type(field(fr.events.metadata.matchday), '11')
  await user.click(saveDraft())

  await waitFor(() => expect(router.state.location.pathname).toBe('/events/e-new'))
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
  await pick(user, fr.events.form.venue, i18n.t('events.form.venueOption', { name: 'Salle Moulay', capacity: 3000 }))
  await user.type(field(fr.events.metadata.headliner), 'Maâlem Hamid')
  await user.click(saveDraft())

  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({
    venue_id: 'v-2',
    event_type: 'CONCERT',
    title: 'Gnaoua Live',
    starts_at: expectedUtc(NORMAL),
    per_account_limit: 4,
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
  await pick(user, fr.events.form.venue, i18n.t('events.form.venueOption', { name: 'Salle Moulay', capacity: 3000 }))
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
    if (c.path !== '/org/events/e-1') return undefined
    if (c.method === 'PATCH') current = { ...current, ...(c.body as Partial<EventDetail>) }
    return Response.json(current)
  })
  const { queryClient } = renderApp('/events/e-1')
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
    if (c.path !== '/org/events/e-1') return undefined
    if (c.method === 'PATCH')
      return Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { per_account_limit: 'range' } } }, { status: 422 })
    return Response.json(current)
  })
  const { queryClient } = renderApp('/events/e-1')
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
  renderApp('/events/e-1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  setDateTime(fr.events.form.doorsOpenAt, '')
  await user.click(saveDraft())
  expect(await screen.findByText(fr.events.form.doorsRequired)).toBeTruthy()
  expect(writes(calls)).toEqual([])
})

it('edit: a concert with a stored free-form matchday saves without validating it, and keeps it', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', undefined, detail({ event_type: 'CONCERT', metadata: { headliner: 'Hamid', matchday: 'n/a' } }))
  renderApp('/events/e-1')
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
  renderApp('/events/e-1')
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
  expect((await screen.findByRole('status')).textContent).toBe(fr.events.saved)
  expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/org/events/e-1', org: 'org-a', body: { title: 'Atlas FC vs Rapid Oued (J11)' } }])

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
  renderApp('/events/e-1')
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
  renderApp('/events/e-1')
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
  renderApp('/events/e-1')
  expect(await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })).toBeTruthy()
  expect(screen.queryByRole('button', { name: fr.events.saveDraft })).toBeNull()
  expect(field(fr.events.form.title).matches(':disabled')).toBe(true)
  expect(field(fr.events.metadata.opponent).matches(':disabled')).toBe(true)
})
