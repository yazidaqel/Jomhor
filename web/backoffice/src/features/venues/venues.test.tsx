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
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'
import { isVenueZone } from '@/lib/time'
import type { VenueDetail } from './api'

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

const me = (role: Role, more: Me['memberships'] = []): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }, ...more],
})

// Zones by code, as the API lists them. HONNEUR=1, NORD=2, EST=3, SUD=4.
const venue = (): VenueDetail => ({
  id: 'v-1',
  name: 'Grand Stade',
  city: 'Casablanca',
  capacity: 45000,
  timezone: 'Africa/Casablanca',
  zones: [
    { id: 3, code: 'EST', name: 'Tribune Est', capacity: 12000, seated: false },
    { id: 1, code: 'HONNEUR', name: 'Tribune d’honneur', capacity: 3000, seated: true },
    { id: 2, code: 'NORD', name: 'Virage Nord', capacity: 15000, seated: false },
    { id: 4, code: 'SUD', name: 'Virage Sud', capacity: 15000, seated: false },
  ],
  gates: [
    { id: 'g-1', code: 'P1', name: 'Porte 1', zone_ids: [1] },
    { id: 'g-3', code: 'P3', name: 'Porte 3', zone_ids: [2, 3] },
    { id: 'g-7', code: 'P7', name: 'Porte 7', zone_ids: [4] },
  ],
})

type Call = { method: string; path: string; org: string | null; body: unknown }
type Handler = (call: Call) => Response | undefined

/** Fake API: /org/me, the venue list and detail (kept in `state`), PUT and DELETE; `handler` answers first. */
function stubApi(role: Role = 'ADMIN', handler: Handler = () => undefined, more: Me['memberships'] = []) {
  const state = venue()
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const headers = new Headers(init?.headers)
      const call: Call = {
        method: init?.method ?? 'GET',
        path: String(input).replace(/^\/v1/, ''),
        org: headers.get('X-Organizer-Id'),
        body: init?.body ? JSON.parse(String(init.body)) : undefined,
      }
      calls.push(call)
      const handled = handler(call)
      if (handled) return handled
      if (call.path === '/org/me') return Response.json(me(role, more))
      if (call.method === 'GET' && call.path === '/org/venues') return Response.json([state])
      if (call.method === 'GET' && call.path === '/org/venues/v-1') return Response.json(state)
      const put = call.method === 'PUT' && call.path.match(/^\/org\/gates\/(.+)\/zones$/)
      if (put) {
        const gate = state.gates.find((g) => g.id === put[1])!
        gate.zone_ids = (call.body as { zone_ids: number[] }).zone_ids
        return Response.json(gate)
      }
      const del = call.method === 'DELETE' && call.path.match(/^\/org\/venues\/v-1\/(zones|gates)\/(.+)$/)
      if (del) {
        if (del[1] === 'zones') state.zones = state.zones.filter((z) => String(z.id) !== del[2])
        else state.gates = state.gates.filter((g) => g.id !== del[2])
        return new Response(null, { status: 204 })
      }
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return { calls, state }
}

function renderApp(path: string) {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const queryClient = createQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}

const writes = (calls: Call[]) => calls.filter((c) => c.method !== 'GET')
const cell = (gate: string, zone: string) => screen.getByRole('checkbox', { name: i18n.t('venues.matrix.cell', { gate, zone }) })

it('matrix: saving sends one PUT per changed gate only, with the organizer header, then refetches the venue', async () => {
  const user = userEvent.setup()
  const { calls } = stubApi()
  renderApp('/settings/venues/v-1')
  expect(await screen.findByRole('heading', { name: 'Grand Stade' })).toBeTruthy()

  // The matrix shows the server state.
  expect(cell('P1', 'HONNEUR').getAttribute('aria-checked')).toBe('true')
  expect(cell('P3', 'NORD').getAttribute('aria-checked')).toBe('true')
  expect(cell('P3', 'SUD').getAttribute('aria-checked')).toBe('false')
  const save = screen.getByRole('button', { name: fr.common.save })
  expect(save).toHaveProperty('disabled', true)

  await user.click(cell('P1', 'NORD')) // P1: 1 → 1, 2
  await user.click(cell('P3', 'EST')) // P3: 2, 3 → 2
  await user.click(cell('P7', 'EST')) // P7: toggled on then off: unchanged
  await user.click(cell('P7', 'EST'))
  expect(screen.getByRole('status').textContent).toBe(i18n.t('venues.matrix.changed', { count: 2 }))
  const venueGets = () => calls.filter((c) => c.method === 'GET' && c.path === '/org/venues/v-1').length
  const before = venueGets()
  await user.click(save)

  expect(await screen.findByText(fr.venues.matrix.saved)).toBeTruthy()
  expect(writes(calls)).toEqual([
    { method: 'PUT', path: '/org/gates/g-1/zones', org: 'org-a', body: { zone_ids: [1, 2] } },
    { method: 'PUT', path: '/org/gates/g-3/zones', org: 'org-a', body: { zone_ids: [2] } },
  ])
  expect(venueGets()).toBe(before + 1)
  expect(cell('P1', 'NORD').getAttribute('aria-checked')).toBe('true')
  expect(cell('P3', 'EST').getAttribute('aria-checked')).toBe('false')
  expect(save).toHaveProperty('disabled', true)
})

it('create zone: the code is uppercased as typed, CODE_TAKEN shows on the code field', async () => {
  const user = userEvent.setup()
  const { calls } = stubApi('ADMIN', (c) =>
    c.method === 'POST' ? Response.json({ code: 'CODE_TAKEN', message: 'x', details: {} }, { status: 409 }) : undefined,
  )
  renderApp('/settings/venues/v-1')

  await user.click(await screen.findByRole('button', { name: fr.venues.zones.add }))
  const dialog = await screen.findByRole('dialog', { name: fr.venues.zones.newTitle })
  const code = within(dialog).getByLabelText(fr.venues.zones.code)
  await user.type(code, 'nord 2')
  expect(code).toHaveProperty('value', 'NORD 2')
  await user.type(within(dialog).getByLabelText(fr.venues.zones.name), 'Virage Nord bis')
  await user.type(within(dialog).getByLabelText(fr.venues.zones.capacity), ' 1200 ')
  await user.click(within(dialog).getByRole('checkbox', { name: fr.venues.zones.seated }))
  await user.click(within(dialog).getByRole('button', { name: fr.common.create }))

  await waitFor(() => expect(code.getAttribute('aria-invalid')).toBe('true'))
  expect(document.getElementById(code.getAttribute('aria-describedby')!.split(' ').at(-1)!)?.textContent).toBe(fr.errors.CODE_TAKEN)
  expect(screen.getByRole('dialog')).toBeTruthy() // still open, nothing lost
  expect(writes(calls)).toEqual([
    {
      method: 'POST',
      path: '/org/venues/v-1/zones',
      org: 'org-a',
      body: { code: 'NORD 2', name: 'Virage Nord bis', capacity: 1200, seated: true },
    },
  ])
})

it('delete zone asks for confirmation first, then shows ZONE_IN_USE', async () => {
  const user = userEvent.setup()
  const { calls } = stubApi('ADMIN', (c) =>
    c.method === 'DELETE' ? Response.json({ code: 'ZONE_IN_USE', message: 'x', details: {} }, { status: 409 }) : undefined,
  )
  renderApp('/settings/venues/v-1')
  const trash = await screen.findByRole('button', { name: i18n.t('venues.zones.deleteLabel', { code: 'NORD' }) })

  // Cancel: nothing sent.
  await user.click(trash)
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.common.cancel }))
  await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  expect(writes(calls)).toEqual([])

  await user.click(trash)
  const confirm = await screen.findByRole('alertdialog', { name: i18n.t('venues.zones.deleteTitle', { code: 'NORD' }) })
  expect(writes(calls)).toEqual([])
  await user.click(within(confirm).getByRole('button', { name: fr.common.delete }))

  expect(await within(confirm).findByText(fr.errors.ZONE_IN_USE)).toBeTruthy()
  expect(writes(calls)).toEqual([{ method: 'DELETE', path: '/org/venues/v-1/zones/2', org: 'org-a', body: undefined }])
})

it('FINANCE sees no write action', async () => {
  stubApi('FINANCE')
  renderApp('/settings/venues')
  expect(await screen.findByRole('link', { name: 'Grand Stade' })).toBeTruthy()
  expect(screen.queryByRole('button', { name: fr.venues.new })).toBeNull()
  cleanup()

  renderApp('/settings/venues/v-1')
  expect(await screen.findByRole('heading', { name: fr.venues.matrix.title })).toBeTruthy()
  expect(screen.getByText(fr.venues.readOnly)).toBeTruthy()
  const main = screen.getByRole('main')
  // Not a single button on the page (the "Lieux" back link is a link).
  expect(within(main).queryAllByRole('button')).toEqual([])
  expect(within(main).queryByRole('button', { name: fr.common.save })).toBeNull()
  const boxes = within(main).getAllByRole('checkbox')
  expect(boxes).toHaveLength(12)
  boxes.forEach((b) => expect(b).toHaveProperty('disabled', true))
})

it('venue capacity: rejects "12.5" and "abc", sends an integer', async () => {
  const user = userEvent.setup()
  const { calls } = stubApi('ADMIN', (c) =>
    c.method === 'POST' ? Response.json({ id: 'v-2', name: 'Salle', city: null, capacity: 800 }, { status: 201 }) : undefined,
  )
  const { router } = renderApp('/settings/venues')

  await user.click(await screen.findByRole('button', { name: fr.venues.new }))
  const dialog = await screen.findByRole('dialog')
  await user.type(within(dialog).getByLabelText(fr.venues.name), 'Salle')
  const capacity = within(dialog).getByLabelText(fr.venues.capacity)
  for (const bad of ['12.5', 'abc', '0', '1e3']) {
    await user.clear(capacity)
    await user.type(capacity, bad)
    await user.click(within(dialog).getByRole('button', { name: fr.common.create }))
    expect(await within(dialog).findByText(fr.venues.venueCapacityInvalid)).toBeTruthy()
  }
  expect(writes(calls)).toEqual([])

  await user.clear(capacity)
  await user.type(capacity, '800')
  await user.click(within(dialog).getByRole('button', { name: fr.common.create }))

  await waitFor(() => expect(router.state.location.pathname).toBe('/settings/venues/v-2'))
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/venues', org: 'org-a', body: { name: 'Salle', city: '', capacity: 800, timezone: 'Africa/Casablanca' } }])
})

it('VALIDATION_FAILED is shown on the fields named in details.fields', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', (c) =>
    c.method === 'PATCH'
      ? Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { capacity: 'must be greater than 0' } } }, { status: 422 })
      : undefined,
  )
  renderApp('/settings/venues/v-1')

  await user.click(await screen.findByRole('button', { name: fr.common.edit }))
  const dialog = await screen.findByRole('dialog', { name: fr.venues.editTitle })
  expect(within(dialog).getByLabelText(fr.venues.name)).toHaveProperty('value', 'Grand Stade')
  await user.click(within(dialog).getByRole('button', { name: fr.common.save }))

  const capacity = within(dialog).getByLabelText(fr.venues.capacity)
  await waitFor(() => expect(capacity.getAttribute('aria-invalid')).toBe('true'))
  expect(within(dialog).getByText(fr.common.fieldInvalid)).toBeTruthy()
  expect(within(dialog).getByLabelText(fr.venues.name).getAttribute('aria-invalid')).toBe('false')
})

it.each([
  ['an unknown venue (NOT_FOUND)', 'v-404'],
  ['a malformed id (BAD_REQUEST)', 'not-a-uuid'],
])('%s goes back to the list, and the notice is not kept in history', async (_, id) => {
  stubApi('ADMIN', (c) =>
    c.path === '/org/venues/not-a-uuid' ? Response.json({ code: 'BAD_REQUEST', message: 'x', details: {} }, { status: 400 }) : undefined,
  )
  const { router } = renderApp(`/settings/venues/${id}`)

  expect(await screen.findByText(fr.venues.notFound)).toBeTruthy()
  expect(router.state.location.pathname).toBe('/settings/venues')
  await waitFor(() => expect(router.state.location.state).toBeNull()) // back or reload won't show it again
  expect(screen.getByText(fr.venues.notFound)).toBeTruthy() // still shown this time
})

it('venue query keys start with org, organizer, venues; writes invalidate only those', async () => {
  const user = userEvent.setup()
  stubApi()
  const { queryClient } = renderApp('/settings/venues')
  await user.click(await screen.findByRole('link', { name: 'Grand Stade' }))
  await screen.findByRole('heading', { name: 'Grand Stade' })
  queryClient.setQueryData(['org', 'org-a', 'events'], [])

  await user.click(screen.getByRole('button', { name: i18n.t('venues.gates.deleteLabel', { code: 'P7' }) }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.common.delete }))
  await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())

  const keys = queryClient
    .getQueryCache()
    .getAll()
    .map((q) => q.queryKey)
  expect(keys).toEqual(expect.arrayContaining([['org', 'org-a', 'venues'], ['org', 'org-a', 'venues', 'v-1']]))
  for (const key of keys.filter((k) => k[0] === 'org' && k[2] !== 'events')) expect(key.slice(0, 3)).toEqual(['org', 'org-a', 'venues'])
  expect(keys.filter((k) => k[0] !== 'org')).toEqual([['me']])
  expect(queryClient.getQueryState(['org', 'org-a', 'events'])?.isInvalidated).toBe(false)
  await waitFor(() => expect(queryClient.getQueryState(['org', 'org-a', 'venues', 'v-1'])?.dataUpdateCount).toBeGreaterThan(1))
})

it('matrix partial failure: the saved gate comes from its PUT response, the failed gate keeps its edit and shows the error', async () => {
  const user = userEvent.setup()
  let failGets = false
  const { calls } = stubApi('ADMIN', (c) => {
    if (c.method === 'PUT') failGets = true
    if (c.method === 'PUT' && c.path === '/org/gates/g-3/zones') {
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }
    // The refetch after saving fails too: what shows for P1 can only come from its PUT response.
    if (failGets && c.method === 'GET' && c.path === '/org/venues/v-1') {
      return Response.json({ code: 'INTERNAL_ERROR', message: 'x', details: {} }, { status: 500 })
    }
    return undefined
  })
  renderApp('/settings/venues/v-1')
  await screen.findByRole('heading', { name: 'Grand Stade' })

  await user.click(cell('P1', 'NORD'))
  await user.click(cell('P3', 'SUD'))
  await user.click(screen.getByRole('button', { name: fr.common.save }))

  expect(await screen.findByText(fr.venues.gone)).toBeTruthy()
  expect(writes(calls).map((c) => [c.path, c.body])).toEqual([
    ['/org/gates/g-1/zones', { zone_ids: [1, 2] }],
    ['/org/gates/g-3/zones', { zone_ids: [2, 3, 4] }],
  ])
  expect(cell('P1', 'NORD').getAttribute('aria-checked')).toBe('true') // saved, edit dropped
  expect(cell('P3', 'SUD').getAttribute('aria-checked')).toBe('true') // failed, edit kept
  expect(screen.getByRole('status').textContent).toBe(i18n.t('venues.matrix.changed', { count: 1 }))

  // "Annuler les modifications" drops the edit and the stale error.
  await user.click(screen.getByRole('button', { name: fr.venues.matrix.reset }))
  expect(screen.queryByText(fr.venues.gone)).toBeNull()
  expect(cell('P3', 'SUD').getAttribute('aria-checked')).toBe('false')
  expect(cell('P1', 'NORD').getAttribute('aria-checked')).toBe('true')
})

it('matrix has row and column headers and labelled cells; capacity is pluralised', async () => {
  stubApi()
  renderApp('/settings/venues/v-1')
  await screen.findByRole('heading', { name: 'Grand Stade' })

  expect(screen.getByText(/^Casablanca · 45\s000 places · Africa\/Casablanca$/)).toBeTruthy()
  expect(i18n.t('venues.capacityPlaces', { count: 1 })).toBe('1 place')
  expect(screen.getByRole('rowheader', { name: 'P3' })).toBeTruthy()
  expect(screen.getByRole('columnheader', { name: 'NORD Virage Nord' })).toBeTruthy()
  expect(cell('P3', 'NORD').getAttribute('aria-label')).toBe('Porte P3, zone NORD')
  // Phone-width regression guard (jsdom has no layout, so this pins the class): the Zones / Portes grid needs an
  // explicit minmax(0, 1fr) column, or the tables' min-content width widens the cards past the screen.
  const grid = screen.getByRole('heading', { name: fr.venues.zones.title }).closest('.grid.gap-6')
  expect(grid?.classList.contains('grid-cols-1')).toBe(true)
})

it('after a delete, focus goes to the section heading and the page is interactive again', async () => {
  const user = userEvent.setup()
  stubApi()
  renderApp('/settings/venues/v-1')
  const trash = { name: i18n.t('venues.gates.deleteLabel', { code: 'P7' }) }

  await user.click(await screen.findByRole('button', trash))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.common.delete }))

  await waitFor(() => expect(screen.queryByRole('button', trash)).toBeNull())
  expect(screen.queryByRole('alertdialog')).toBeNull()
  expect(document.activeElement).toBe(screen.getByRole('heading', { name: fr.venues.gates.title }))
  expect(document.body.style.pointerEvents).toBe('')
})

it('NOT_FOUND on edit keeps the dialog open with its message while the row goes; closing focuses the heading', async () => {
  const user = userEvent.setup()
  const { state } = stubApi('ADMIN', (c) => {
    if (c.method !== 'PATCH') return undefined
    state.zones = state.zones.filter((z) => z.code !== 'SUD') // deleted by someone else meanwhile
    return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
  })
  renderApp('/settings/venues/v-1')
  const pencil = { name: i18n.t('venues.zones.editLabel', { code: 'SUD' }) }

  await user.click(await screen.findByRole('button', pencil))
  const dialog = await screen.findByRole('dialog', { name: i18n.t('venues.zones.editTitle', { code: 'SUD' }) })
  await user.click(within(dialog).getByRole('button', { name: fr.common.save }))

  expect(await within(dialog).findByText(fr.venues.gone)).toBeTruthy()
  await waitFor(() => expect(screen.queryByRole('button', pencil)).toBeNull())
  expect(screen.getByRole('dialog')).toBe(dialog)

  await user.click(within(dialog).getByRole('button', { name: fr.common.cancel }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(document.activeElement).toBe(screen.getByRole('heading', { name: fr.venues.zones.title }))
  expect(document.body.style.pointerEvents).toBe('')
})

it('switching organizer: writes and refetches are scoped to the selected one; the other one’s keys are never touched', async () => {
  const user = userEvent.setup()
  const { calls } = stubApi('ADMIN', undefined, [{ organizer_id: 'org-b', organizer_name: 'Raja', role: 'OPS' }])
  const { queryClient } = renderApp('/settings/venues/v-1')
  await screen.findByRole('heading', { name: 'Grand Stade' })
  // org-b's venue in the cache too: an org-a write must leave it alone.
  queryClient.setQueryData(['org', 'org-b', 'venues', 'v-1'], venue())
  const stateOf = (org: string) => queryClient.getQueryState(['org', org, 'venues', 'v-1'])

  await user.click(screen.getByRole('button', { name: i18n.t('venues.gates.deleteLabel', { code: 'P7' }) }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.common.delete }))
  await waitFor(() => expect(stateOf('org-a')?.dataUpdateCount).toBe(2))
  expect(stateOf('org-b')?.isInvalidated).toBe(false)
  expect(stateOf('org-b')?.dataUpdateCount).toBe(1)
  const beforeSwitch = calls.length

  // Switch: org-a's queries leave the cache; org-b's page reads and writes with org-b.
  await user.click(screen.getByRole('combobox', { name: fr.shell.organizer }))
  await user.click(await screen.findByRole('option', { name: `Raja · ${fr.roles.OPS}` }))
  await user.click(await screen.findByRole('button', { name: i18n.t('venues.zones.deleteLabel', { code: 'NORD' }) }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.common.delete }))
  await waitFor(() => expect(stateOf('org-b')?.dataUpdateCount).toBeGreaterThan(2))

  const scoped = (list: Call[]) => list.filter((c) => c.path !== '/org/me').map((c) => c.org)
  expect(new Set(scoped(calls.slice(0, beforeSwitch)))).toEqual(new Set(['org-a']))
  expect(new Set(scoped(calls.slice(beforeSwitch)))).toEqual(new Set(['org-b']))
  expect(writes(calls).map((c) => [c.method, c.path, c.org])).toEqual([
    ['DELETE', '/org/venues/v-1/gates/g-7', 'org-a'],
    ['DELETE', '/org/venues/v-1/zones/2', 'org-b'],
  ])
  expect(queryClient.getQueryCache().findAll({ queryKey: ['org', 'org-a'] })).toEqual([])
})

it('venue time zone: defaults to Africa/Casablanca, rejects an unknown id, PATCH sends it only when changed', async () => {
  const user = userEvent.setup()
  const { calls } = stubApi('ADMIN', (c) => (c.method === 'PATCH' ? Response.json({ ...venue(), ...(c.body as object) }) : undefined))
  renderApp('/settings/venues/v-1')
  await user.click(await screen.findByRole('button', { name: fr.common.edit }))
  const dialog = await screen.findByRole('dialog', { name: fr.venues.editTitle })
  const zone = within(dialog).getByLabelText(fr.venues.timezone) as HTMLInputElement
  expect(zone.value).toBe('Africa/Casablanca')
  expect(document.querySelectorAll('#venue-timezones option').length).toBe(Intl.supportedValuesOf('timeZone').length) // suggestions

  await user.type(within(dialog).getByLabelText(fr.venues.cityOptional), ' Centre')
  await user.click(within(dialog).getByRole('button', { name: fr.common.save }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({ name: 'Grand Stade', city: 'Casablanca Centre', capacity: 45000 }) // no timezone

  await user.click(await screen.findByRole('button', { name: fr.common.edit }))
  const again = await screen.findByRole('dialog', { name: fr.venues.editTitle })
  const zone2 = within(again).getByLabelText(fr.venues.timezone)
  await user.clear(zone2)
  await user.type(zone2, 'Mars/Olympus')
  await user.click(within(again).getByRole('button', { name: fr.common.save }))
  expect(await within(again).findByText(fr.venues.timezoneInvalid)).toBeTruthy()
  await user.clear(zone2)
  await user.type(zone2, 'Europe/Paris')
  await user.click(within(again).getByRole('button', { name: fr.common.save }))
  await waitFor(() => expect(writes(calls)).toHaveLength(2))
  expect(writes(calls)[1]!.body).toEqual({ name: 'Grand Stade', city: 'Casablanca', capacity: 45000, timezone: 'Europe/Paris' }) // the stub keeps the stored city
})

it('venue time zone: VENUE_IN_USE shows on the time zone field', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', (c) =>
    c.method === 'PATCH' ? Response.json({ code: 'VENUE_IN_USE', message: 'x', details: {} }, { status: 409 }) : undefined,
  )
  renderApp('/settings/venues/v-1')
  await user.click(await screen.findByRole('button', { name: fr.common.edit }))
  const dialog = await screen.findByRole('dialog', { name: fr.venues.editTitle })
  const zone = within(dialog).getByLabelText(fr.venues.timezone)
  await user.clear(zone)
  await user.type(zone, 'Europe/Paris')
  await user.click(within(dialog).getByRole('button', { name: fr.common.save }))

  await waitFor(() => expect(zone.getAttribute('aria-invalid')).toBe('true'))
  expect(within(dialog).getByText(fr.errors.VENUE_IN_USE)).toBeTruthy()
  expect(fr.errors.VENUE_IN_USE).toBe('Ce lieu a déjà des événements : son fuseau horaire ne peut plus changer.')
})

it.each(['UTC', 'GMT', 'EST', 'Etc/GMT+1', 'Etc/UTC', 'SystemV/AST4', '+01:00', 'africa/casablanca', 'Africa/Nowhere', 'Africa/'])(
  'venue time zone %j is refused before sending',
  async (zoneId) => {
    expect(isVenueZone(zoneId)).toBe(false)
    const user = userEvent.setup()
    const { calls } = stubApi()
    renderApp('/settings/venues/v-1')
    await user.click(await screen.findByRole('button', { name: fr.common.edit }))
    const dialog = await screen.findByRole('dialog', { name: fr.venues.editTitle })
    const zone = within(dialog).getByLabelText(fr.venues.timezone)
    await user.clear(zone)
    await user.type(zone, zoneId)
    await user.click(within(dialog).getByRole('button', { name: fr.common.save }))
    expect(await within(dialog).findByText(fr.venues.timezoneInvalid)).toBeTruthy()
    expect(writes(calls)).toEqual([])
  },
)

it('venue time zone: geographic ids pass, both V8 and Java spellings; the suggestions follow the same rule', () => {
  for (const ok of ['Africa/Casablanca', 'Europe/Paris', 'Europe/Kyiv', 'Europe/Kiev', 'Asia/Kolkata', 'Asia/Calcutta', 'America/Argentina/Buenos_Aires'])
    expect(isVenueZone(ok)).toBe(true)
  const suggested = Intl.supportedValuesOf('timeZone').filter(isVenueZone)
  expect(suggested).toContain('Africa/Casablanca')
  expect(suggested.some((z) => z === 'UTC' || z.startsWith('Etc/'))).toBe(false)
})

it('venue time zone: a server VALIDATION_FAILED on timezone shows the time zone copy', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', (c) =>
    c.method === 'PATCH'
      ? Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { timezone: 'unknown zone' } } }, { status: 422 })
      : undefined,
  )
  renderApp('/settings/venues/v-1')
  await user.click(await screen.findByRole('button', { name: fr.common.edit }))
  const dialog = await screen.findByRole('dialog', { name: fr.venues.editTitle })
  const zone = within(dialog).getByLabelText(fr.venues.timezone)
  await user.clear(zone)
  await user.type(zone, 'Europe/Kyiv')
  await user.click(within(dialog).getByRole('button', { name: fr.common.save }))
  expect(await within(dialog).findByText(fr.venues.timezoneInvalid)).toBeTruthy()
  expect(zone.getAttribute('aria-invalid')).toBe('true')
})
