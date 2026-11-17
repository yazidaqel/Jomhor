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
import { LAYOUT_IDS, LAYOUTS, mapSchema, type VenueDetail } from './api'

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

const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})

// Captured from the real API (BO-29 real run, dev stack, recipe `backend/http/venue-map.http`, ATLAS FC's Grand Stade
// on the seeded STADIUM map): GET /org/venues/{id}, zones by code as the API lists them (EST=2, HONNEUR=3, NORD=1,
// PRESSE=19 unmapped, SUD=4). Only the id is the test's own and the seven gates are left out: the card reads neither.
const venue = (): VenueDetail => ({
  id: 'v-1',
  name: 'Grand Stade',
  city: 'Casablanca',
  capacity: 45000,
  timezone: 'Africa/Casablanca',
  layout: 'STADIUM',
  zones: [
    { id: 2, code: 'EST', name: 'Est', capacity: 14000, seated: false, map_slot: 'EAST' },
    { id: 3, code: 'HONNEUR', name: 'Honneur', capacity: 6000, seated: true, map_slot: 'WEST' },
    { id: 1, code: 'NORD', name: 'Nord', capacity: 12000, seated: false, map_slot: 'NORTH' },
    { id: 19, code: 'PRESSE', name: 'Tribune de presse', capacity: 60, seated: true, map_slot: null },
    { id: 4, code: 'SUD', name: 'Sud', capacity: 12000, seated: false, map_slot: 'SOUTH' },
  ],
  gates: [],
})

type Call = { method: string; path: string; org: string | null; body: unknown }

/** Fake API: /org/me, the venue (kept in `state`) and PUT …/map; `reply` answers the PUT when given. */
function stubApi(role: Role = 'ADMIN', state: VenueDetail = venue(), reply?: (state: VenueDetail) => Response) {
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
      if (call.path === '/org/me') return Response.json(me(role))
      if (call.method === 'GET' && call.path === '/org/venues/v-1') return Response.json(state)
      if (call.method === 'PUT' && call.path === '/org/venues/v-1/map') {
        if (reply) return reply(state)
        const body = call.body as { layout: string | null; zones: { zone_id: number; map_slot: string }[] }
        state.layout = body.layout
        state.zones = state.zones.map((z) => ({ ...z, map_slot: body.zones.find((p) => p.zone_id === z.id)?.map_slot ?? null }))
        return Response.json(state)
      }
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

async function renderVenue() {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const router = createMemoryRouter(routes, { initialEntries: ['/settings/venues/v-1'] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  expect(await screen.findByRole('heading', { name: 'Grand Stade' })).toBeTruthy()
}

const template = () => screen.getByRole('combobox', { name: fr.venues.map.layout })
const slotOf = (code: string) => screen.getByRole('combobox', { name: i18n.t('venues.map.slotLabel', { code }) })
const preview = () => screen.getByRole('group', { name: fr.venues.map.preview })
const saveButton = () => screen.getByRole('button', { name: fr.venues.map.save })
const puts = (calls: Call[]) => calls.filter((c) => c.method === 'PUT')

async function pick(user: ReturnType<typeof userEvent.setup>, trigger: HTMLElement, option: string) {
  await user.click(trigger)
  await user.click(screen.getByRole('option', { name: option }))
}

it('draws a preview per layout: each slot at its % position, free slots dashed with their label', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', { ...venue(), layout: null, zones: venue().zones.map((z) => ({ ...z, map_slot: null })) })
  await renderVenue()
  expect(screen.getByText(fr.venues.map.noLayout)).toBeTruthy()
  for (const id of LAYOUT_IDS) {
    await pick(user, template(), fr.venues.map.layouts[id])
    const layout = LAYOUTS[id]
    expect(within(preview()).getByText(fr.venues.map.center[layout.center.kind])).toBeTruthy()
    const blocks = preview().querySelectorAll<HTMLElement>('[data-slot]')
    expect([...blocks].map((b) => b.dataset.slot)).toEqual(layout.slots.map((s) => s.id))
    layout.slots.forEach(({ id: slot, rect: [x, y, w, h] }, i) => {
      const { style, textContent, className } = blocks[i]!
      const expected = [(x / 400) * 100, (y / 300) * 100, (w / 400) * 100, (h / 300) * 100]
      ;[style.left, style.top, style.width, style.height].forEach((v, k) => expect(Number.parseFloat(v)).toBeCloseTo(expected[k]!, 3))
      expect(textContent).toBe(fr.venues.map.slots[slot])
      expect(className).toContain('border-dashed')
    })
  }
})

it('sends the exact PUT body with the organizer header, toasts and refetches; Save is off while unchanged', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  await renderVenue()
  // The seeded map: codes on their slots, the corners free.
  const east = preview().querySelector<HTMLElement>('[data-slot=EAST]')!
  expect(east.textContent).toBe('EST')
  expect(preview().querySelector<HTMLElement>('[data-slot=SOUTH_EAST]')!.textContent).toBe('Sud-Est')
  expect(saveButton()).toHaveProperty('disabled', true)

  await pick(user, slotOf('EST'), 'Sud-Est')
  expect(preview().querySelector<HTMLElement>('[data-slot=SOUTH_EAST]')!.textContent).toBe('EST')
  expect(preview().querySelector<HTMLElement>('[data-slot=EAST]')!.textContent).toBe('Est')
  const venueGets = () => calls.filter((c) => c.method === 'GET' && c.path === '/org/venues/v-1').length
  const before = venueGets()
  await user.click(saveButton())

  expect(await screen.findByText(fr.venues.map.saved)).toBeTruthy()
  expect(puts(calls)).toEqual([
    {
      method: 'PUT',
      path: '/org/venues/v-1/map',
      org: 'org-a',
      body: {
        layout: 'STADIUM',
        zones: [
          { zone_id: 2, map_slot: 'SOUTH_EAST' },
          { zone_id: 3, map_slot: 'WEST' },
          { zone_id: 1, map_slot: 'NORTH' },
          { zone_id: 4, map_slot: 'SOUTH' },
        ],
      },
    },
  ])
  await waitFor(() => expect(venueGets()).toBeGreaterThan(before))
  // The form restarts from the saved map.
  expect(saveButton()).toHaveProperty('disabled', true)
  expect(slotOf('EST').textContent).toBe('Sud-Est')
})

it('offers a zone the free slots, its own and "Pas sur le plan", never a slot another zone holds', async () => {
  const user = userEvent.setup()
  stubApi()
  await renderVenue()
  await user.click(slotOf('EST'))
  expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([fr.venues.map.off, 'Nord-Ouest', 'Nord-Est', 'Est', 'Sud-Ouest', 'Sud-Est'])
})

it('switching template keeps the shared slot ids, lists the zones that drop off, and "Aucun plan" clears the map', async () => {
  const user = userEvent.setup()
  // EST saved on Sud-Est, not a CENTER_STAGE slot.
  const calls = stubApi('ADMIN', { ...venue(), zones: venue().zones.map((z) => (z.code === 'EST' ? { ...z, map_slot: 'SOUTH_EAST' } : z)) })
  await renderVenue()

  await pick(user, template(), fr.venues.map.layouts.CENTER_STAGE)
  expect(screen.getByText(i18n.t('venues.map.dropped', { codes: 'EST' })).getAttribute('role')).toBe('status')
  expect(slotOf('EST').textContent).toBe(fr.venues.map.off)
  expect([slotOf('HONNEUR'), slotOf('NORD'), slotOf('SUD')].map((s) => s.textContent)).toEqual(['Ouest', 'Nord', 'Sud'])

  await user.click(saveButton())
  await screen.findByText(fr.venues.map.saved)
  expect(puts(calls).at(-1)!.body).toEqual({
    layout: 'CENTER_STAGE',
    zones: [
      { zone_id: 3, map_slot: 'WEST' },
      { zone_id: 1, map_slot: 'NORTH' },
      { zone_id: 4, map_slot: 'SOUTH' },
    ],
  })

  // THEATRE shares no slot id with CENTER_STAGE: every zone drops off.
  await pick(user, template(), fr.venues.map.layouts.THEATRE)
  expect(screen.getByText(i18n.t('venues.map.dropped', { codes: 'HONNEUR, NORD, SUD' }))).toBeTruthy()
  expect(['EST', 'HONNEUR', 'NORD', 'SUD'].map((c) => slotOf(c).textContent)).toEqual(Array(4).fill(fr.venues.map.off))

  await pick(user, template(), fr.venues.map.layouts.none)
  expect(screen.getByText(i18n.t('venues.map.dropped', { codes: 'HONNEUR, NORD, SUD' }))).toBeTruthy()
  expect(screen.getByText(fr.venues.map.noLayout)).toBeTruthy()
  await user.click(saveButton())
  await waitFor(() => expect(puts(calls).at(-1)!.body).toEqual({ layout: null, zones: [] }))
})

it('Stade → Théâtre → Stade without saving restores the saved placements; manual edits drop on a switch', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  await renderVenue()
  const slots = () => ['EST', 'HONNEUR', 'NORD', 'SUD'].map((c) => slotOf(c).textContent)
  await pick(user, slotOf('EST'), 'Sud-Est') // a manual edit, lost on the switch

  await pick(user, template(), fr.venues.map.layouts.THEATRE)
  expect(slots()).toEqual(Array(4).fill(fr.venues.map.off))
  expect(screen.getByText(i18n.t('venues.map.dropped', { codes: 'EST, HONNEUR, NORD, SUD' }))).toBeTruthy()

  await pick(user, template(), fr.venues.map.layouts.STADIUM)
  expect(slots()).toEqual(['Est', 'Ouest', 'Nord', 'Sud'])
  expect(preview().querySelector<HTMLElement>('[data-slot=EAST]')!.textContent).toBe('EST')
  expect(screen.queryByText(/Quittent le plan/)).toBeNull()
  expect(saveButton()).toHaveProperty('disabled', true) // back to the saved map
  expect(puts(calls)).toEqual([])
})

/** A 422 as the real API answered it (BO-29 real run): one field, with the server's reason. */
const invalid = (key: string, reason: string) =>
  Response.json({ code: 'VALIDATION_FAILED', message: 'Invalid request body', details: { fields: { [key]: reason } } }, { status: 422 })

// 04: the first failing check answers 422 with that one field. `zones[i]` indexes the body: EST, HONNEUR, NORD, SUD
// (PRESSE is off the map, so not in the body).
it.each([
  ['layout', 'must be a layout of docs/venue-layouts.json', () => template(), fr.venues.map.layoutInvalid],
  ['zones[1].map_slot', 'is already listed', () => slotOf('HONNEUR'), fr.venues.map.slotInvalid],
])('a 422 on %s lands on its Select', async (key, reason, trigger, message) => {
  const user = userEvent.setup()
  stubApi('ADMIN', venue(), () => invalid(key, reason))
  await renderVenue()
  await pick(user, slotOf('EST'), 'Sud-Est')
  await user.click(saveButton())
  await waitFor(() => expect(trigger().getAttribute('aria-invalid')).toBe('true'))
  expect(document.getElementById(trigger().getAttribute('aria-describedby')!)!.textContent).toBe(message)
  expect(within(preview().closest<HTMLElement>('[data-slot=card]')!).getAllByRole('combobox').filter((c) => c.getAttribute('aria-invalid') === 'true')).toHaveLength(1)
})

it('a 422 on zones[i].zone_id (zone deleted meanwhile) shows as a toast; the refetch drops the zone', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', venue(), (state) => {
    state.zones = state.zones.filter((z) => z.id !== 1) // NORD, body index 2, deleted by someone else
    return invalid('zones[2].zone_id', 'must be a zone of this venue')
  })
  await renderVenue()
  await pick(user, slotOf('EST'), 'Sud-Est')
  await user.click(saveButton())
  expect(await screen.findByText(fr.venues.map.zoneGone)).toBeTruthy()
  await waitFor(() => expect(screen.queryByRole('combobox', { name: i18n.t('venues.map.slotLabel', { code: 'NORD' }) })).toBeNull())
  expect(slotOf('EST').textContent).toBe('Est') // the editor restarted from the refetched map
  expect(puts(calls)).toHaveLength(1)
})

it('a 422 on `zones` (no Select of its own) shows as the form error', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', venue(), () => invalid('zones', 'must be empty when layout is null'))
  await renderVenue()
  await pick(user, slotOf('EST'), 'Sud-Est')
  await user.click(saveButton())
  expect(await screen.findByText(fr.errors.VALIDATION_FAILED)).toBeTruthy()
})

it('FINANCE sees the template and the preview only: no Select, no save', async () => {
  stubApi('FINANCE')
  await renderVenue()
  expect(screen.getByText(i18n.t('venues.map.layoutValue', { layout: fr.venues.map.layouts.STADIUM }))).toBeTruthy()
  expect(preview().querySelector<HTMLElement>('[data-slot=NORTH]')!.textContent).toBe('NORD')
  const card = within(preview().closest<HTMLElement>('[data-slot=card]')!)
  expect(card.queryByRole('combobox')).toBeNull()
  expect(card.queryByRole('button')).toBeNull()
})

it('a venue without zones says to add zones first', async () => {
  stubApi('ADMIN', { ...venue(), layout: null, zones: [] })
  await renderVenue()
  expect(screen.getByText(fr.venues.map.empty)).toBeTruthy()
  expect(screen.queryByRole('combobox', { name: fr.venues.map.layout })).toBeNull()
})

it('an unknown layout or slot from the server reads as unmapped (04 §Venue map)', async () => {
  stubApi('FINANCE', { ...venue(), layout: 'ARENA_2030' })
  await renderVenue()
  expect(screen.getByText(i18n.t('venues.map.layoutValue', { layout: fr.venues.map.layouts.none }))).toBeTruthy()
})

it('every slot and layout of docs/venue-layouts.json has a French label; the schema refuses a slot used twice', () => {
  for (const id of LAYOUT_IDS) {
    expect(fr.venues.map.layouts[id]).toBeTruthy()
    for (const s of LAYOUTS[id].slots) expect(fr.venues.map.slots[s.id], s.id).toBeTruthy()
  }
  const parsed = mapSchema(i18n.t).safeParse({
    layout: 'STADIUM',
    zones: [
      { zone_id: 1, map_slot: 'NORTH' },
      { zone_id: 2, map_slot: 'NORTH' },
      { zone_id: 3, map_slot: 'off' },
      { zone_id: 4, map_slot: 'off' },
    ],
  })
  expect(parsed.error?.issues.map((i) => [i.path.join('.'), i.message])).toEqual([['zones.1.map_slot', fr.venues.map.slotTaken]])
})
