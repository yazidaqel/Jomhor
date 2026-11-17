// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { renderToString } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import layouts from '../../../../../../../docs/venue-layouts.json'
import { getEvent, type Zone } from '@/lib/events'
import { json, NOW, rawEvent } from '@/lib/events.fixture'
import { getDictionary, locales, type Locale } from '@/lib/i18n'
import { firstOpen, LAYOUT_IDS, mapView } from '@/lib/venue-map'
import { EventView } from './event-view'
import { ZonePicker, type RoomView } from './ZonePicker'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

// source: contract (docs/04-api.md fan GET /fan/events/{id} + §Venue map: `layout`, `zones[].map_slot`; the dev seed's
// STADIUM mapping, plan Q242). VIP's slot isn't a STADIUM slot: unmapped, list only.
const ZONES = [
  {
    id: 3,
    code: 'NORD',
    name: 'Tribune Nord',
    seated: false,
    gate_codes: ['P4'],
    map_slot: 'NORTH',
    prices: [
      { price_category_id: 'pc-tribune', name: 'Tribune', price_mad: 12050, remaining: 30, audience: 'ALL' },
      { price_category_id: 'pc-membres', name: 'Membres', price_mad: 3000, remaining: 100, audience: 'MEMBERS' },
      { price_category_id: 'pc-virage', name: 'Virage', price_mad: 5000, remaining: 2300, audience: 'ALL' },
    ],
  },
  { id: 4, code: 'SUD', name: 'Tribune Sud', seated: true, gate_codes: ['P2'], map_slot: 'SOUTH', prices: [{ price_category_id: 'pc-sud', name: 'Plein', price_mad: 8000, remaining: 0, audience: 'ALL' }] },
  {
    id: 5,
    code: 'EST',
    name: 'Tribune Est',
    seated: true,
    gate_codes: [],
    map_slot: 'EAST',
    prices: [
      { price_category_id: 'pc-est', name: 'Plein', price_mad: 6000, remaining: 0, audience: 'ALL' },
      { price_category_id: 'pc-est-m', name: 'Membres', price_mad: 4000, remaining: 50, audience: 'MEMBERS' },
    ],
  },
  { id: 6, code: 'HONNEUR', name: 'Tribune d’honneur', seated: true, gate_codes: [], map_slot: 'WEST', prices: [{ price_category_id: 'pc-honneur', name: 'Plein', price_mad: 25000, remaining: 10, audience: 'ALL' }] },
  { id: 7, code: 'VIP', name: 'Salon VIP', seated: true, gate_codes: [], map_slot: 'ROOFTOP', prices: [{ price_category_id: 'pc-vip', name: 'Plein', price_mad: 90000, remaining: 5, audience: 'ALL' }] },
] satisfies Zone[]

const fr = getDictionary('fr')
const copy = (l: Locale) => {
  const d = getDictionary(l)
  return { event: d.event, order: d.order, meta: d.meta, room: d.room, waitlist: d.waitlist, errors: d.errors }
}

let root: Root
let el: HTMLDivElement
beforeEach(() => {
  el = document.createElement('div')
  document.body.append(el)
  root = createRoot(el)
})
afterEach(() => {
  act(() => root.unmount())
  el.remove()
  sessionStorage.clear()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function render({ layout = 'STADIUM' as string | null, zones = ZONES as Zone[], closed = null as string | null, room = null as RoomView | null, locale = 'fr' as Locale } = {}) {
  act(() => root.render(<ZonePicker eventId={12} zones={zones} layout={layout} limit={4} closed={closed} room={room} copy={copy(locale)} />))
}
const map = () => el.querySelector<HTMLElement>('[data-map]')
const block = (slot: string) => el.querySelector<HTMLButtonElement>(`button[data-slot="${slot}"]`)!
const blocks = () => [...el.querySelectorAll<HTMLButtonElement>('[data-map] button')]
/** The grid card of a price, found by the price's own line. */
const card = (text: string) => [...el.querySelectorAll<HTMLButtonElement>('#zones > .grid > button')].find((b) => b.textContent!.includes(text))!
const click = (b: HTMLElement) => act(() => b.click())

describe('StageMap', () => {
  it('each mapped zone sits at its % position on the 400×300 canvas; corners are inert and hidden from assistive tech', () => {
    render()
    expect(map()!.getAttribute('aria-label')).toBe('Plan du lieu')
    expect(block('NORTH').style.cssText).toMatch(/left: 20%; top: 0%; width: 60%; height: 20\.66/)
    for (const s of layouts.layouts.STADIUM.slots.filter((s) => ['NORTH', 'SOUTH', 'EAST', 'WEST'].includes(s.id))) {
      const [x, y, w, h] = s.rect as [number, number, number, number]
      const got = [block(s.id).style.left, block(s.id).style.top, block(s.id).style.width, block(s.id).style.height].map(parseFloat)
      ;[(x / 400) * 100, (y / 300) * 100, (w / 400) * 100, (h / 300) * 100].forEach((v, k) => expect(got[k], s.id).toBeCloseTo(v, 3))
    }
    const corner = el.querySelector<HTMLElement>('[data-slot="NORTH_WEST"]')!
    expect(corner.tagName).toBe('DIV')
    expect(corner.getAttribute('aria-hidden')).toBe('true')
    expect(corner.textContent).toBe('')
    expect(blocks().map((b) => b.dataset.slot)).toEqual(['NORTH', 'WEST', 'EAST', 'SOUTH']) // JSON order = focus order
    expect(map()!.textContent).toContain('Terrain')
  })

  it('block lines and accessible names: "dès {price}", "Membres", and the name in full', () => {
    render()
    expect(block('NORTH').textContent).toBe('Tribune Nord' + 'dès \u206650\u00a0DH\u2069' + 'Membres')
    expect(block('NORTH').getAttribute('aria-label')).toBe('Tribune Nord, dès \u206650\u00a0DH\u2069, membres')
    expect(block('WEST').getAttribute('aria-label')).toBe('Tribune d’honneur, dès \u2066250\u00a0DH\u2069')
  })

  it.each<[string, string | null, Zone[]]>([
    ['no layout', null, ZONES],
    ['an unknown layout', 'ARENA_2030', ZONES],
    ['a layout with no zone on it', 'STADIUM', ZONES.map((z) => ({ ...z, map_slot: null }))],
    ['only slots the layout lacks', 'STADIUM', ZONES.map((z) => ({ ...z, map_slot: z.id === 3 ? 'FLOOR_FRONT' : 'ROOFTOP' }))],
  ])('%s → no map, the grid shows every price', (_, layout, zones) => {
    render({ layout, zones })
    expect(map()).toBeNull()
    expect(el.querySelectorAll('#zones > .grid > button')).toHaveLength(8)
  })

  it('an unmapped zone (unknown slot) is only in the grid', () => {
    render()
    expect(map()!.textContent).not.toContain('Salon VIP')
    expect(card('Salon VIP')).toBeDefined()
  })

  it('a sold-out zone is a disabled block showing "Complet"', () => {
    render()
    expect(block('SOUTH').disabled).toBe(true)
    expect(block('SOUTH').textContent).toContain('Complet')
    expect(block('SOUTH').getAttribute('aria-label')).toBe('Tribune Sud, complet')
    expect(block('NORTH').disabled).toBe(false)
  })

  it('a tap selects the first open price: cheapest ALL before a cheaper MEMBERS one; a members-only zone → its members price', () => {
    render()
    click(block('NORTH'))
    expect(card('Virage').getAttribute('aria-pressed')).toBe('true')
    expect(block('NORTH').getAttribute('aria-pressed')).toBe('true')
    expect(el.querySelector('aside')!.textContent).toContain('Tribune Nord')

    click(block('EAST'))
    expect(card('Membres · Assis').getAttribute('aria-pressed')).toBe('true')
    expect(card('Membres · Assis').textContent).toContain('40\u00a0DH')
    expect(block('EAST').getAttribute('aria-pressed')).toBe('true')
    expect(block('NORTH').getAttribute('aria-pressed')).toBe('false')
  })

  it('a card tap moves the highlight to its zone (same state as a block tap)', () => {
    render()
    click(card('Tribune d’honneur'))
    expect(block('WEST').getAttribute('aria-pressed')).toBe('true')
    click(card('Tribune · Debout'))
    expect(block('NORTH').getAttribute('aria-pressed')).toBe('true')
    expect(block('WEST').getAttribute('aria-pressed')).toBe('false')
  })

  it('sale closed, or gated by a room without a turn → every block disabled', () => {
    render({ closed: 'Vente terminée' })
    expect(blocks().map((b) => b.disabled)).toEqual([true, true, true, true])
    const room: RoomView = { status: 'OPEN', phase: 1, banner: null, next: null, opensAt: '2026-02-01T11:00:00Z', opensLabel: '', rules: null, join: '/q', signedIn: true, now: NOW }
    render({ room })
    expect(blocks().map((b) => b.disabled)).toEqual([true, true, true, true])
  })

  it.each(LAYOUT_IDS)('layout %s renders every slot as a block, in JSON order, inside the canvas', (id) => {
    const slots = layouts.layouts[id as keyof typeof layouts.layouts].slots
    const zones = slots.map((s, i) => ({ ...ZONES[3]!, id: i + 1, name: `Z${i}`, map_slot: s.id, prices: [{ ...ZONES[3]!.prices[0]!, price_category_id: `pc-${i}` }] }))
    render({ layout: id, zones })
    expect(blocks().map((b) => b.dataset.slot)).toEqual(slots.map((s) => s.id))
    for (const b of blocks()) {
      const [l, t, w, h] = [b.style.left, b.style.top, b.style.width, b.style.height].map(parseFloat) as [number, number, number, number]
      expect(l >= 0 && t >= 0 && l + w <= 100.0001 && t + h <= 100.0001, b.dataset.slot).toBe(true)
      expect((w / 100) * 320 >= 44 && (h / 100) * 240 >= 44, `${b.dataset.slot} ≥ 44 px at 320 px`).toBe(true)
    }
  })

  it.each(locales)('the group copy shows under the stepper (%s)', (locale) => {
    render({ locale })
    click(block('WEST'))
    const group = el.querySelector('aside [data-group]')!
    expect(group.textContent).toBe(getDictionary(locale).order.group)
    expect(el.querySelector('aside [role="group"]')!.compareDocumentPosition(group) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('no side-by-side promise outside the group copy', () => {
    for (const l of locales) {
      const d = getDictionary(l)
      const all = JSON.stringify({ ...d, order: { ...d.order, group: '' } })
      expect(all).not.toMatch(/côte à côte|side by side|حدا بعضيات/i)
    }
  })
})

describe('W2 with the map (server render)', () => {
  beforeEach(() => {
    vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
    vi.stubGlobal('fetch', vi.fn(async () => json(200, rawEvent({ layout: 'STADIUM', zones: ZONES }))))
  })

  it('getEvent maps `layout` and `map_slot`; absent (an older backend) → null', async () => {
    const e = (await getEvent(12, NOW))!
    expect(e.layout).toBe('STADIUM')
    expect(e.zones.map((z) => z.map_slot)).toEqual(['NORTH', 'SOUTH', 'EAST', 'WEST', 'ROOFTOP'])
    vi.stubGlobal('fetch', vi.fn(async () => json(200, rawEvent())))
    const old = (await getEvent(12, NOW))!
    expect([old.layout, ...old.zones.map((z) => z.map_slot)]).toEqual([null, null, null])
  })

  it('/ar: the canvas keeps dir="ltr" (never mirrored) with Arabic labels; the map comes before the grid', async () => {
    const ar = getDictionary('ar')
    const h = renderToString(<EventView e={(await getEvent(12, NOW))!} now={NOW} d={ar} />)
    const canvas = /<div[^>]* data-map="true"[^>]*>/.exec(h)![0]
    expect(canvas).toContain('dir="ltr"')
    expect(canvas).toContain(`aria-label="${ar.event.map}"`)
    // The AR label rule skips LTR islands, so the canvas has its own (globals.css): hook + class on the label.
    expect(h).toContain(`<span class="relative text-label uppercase text-text-muted">${ar.event.mapField}</span>`)
    expect(readFileSync('app/globals.css', 'utf8')).toMatch(
      /html:lang\(ar\) \[data-map\] \.text-label \{\s*letter-spacing: 0 !important;\s*text-transform: none !important;\s*font-weight: 700 !important;/,
    )
    expect(h.indexOf('data-map')).toBeLessThan(h.indexOf(ar.event.seated)) // the first card's detail line
    expect(h).toContain(`aria-label="${ar.event.mapSoldOut.replace('{zone}', 'Tribune Sud')}"`)
  })

  it('FR: the server HTML has the map, and the page still renders without it for an unmapped event', async () => {
    expect(renderToString(<EventView e={(await getEvent(12, NOW))!} now={NOW} d={fr} />)).toContain('data-map')
    vi.stubGlobal('fetch', vi.fn(async () => json(200, rawEvent())))
    expect(renderToString(<EventView e={(await getEvent(12, NOW))!} now={NOW} d={fr} />)).not.toContain('data-map')
  })
})

it('firstOpen: open prices only, ALL before MEMBERS, then by price; undefined when sold out', () => {
  const p = (id: string, price_mad: number, remaining: number, audience: 'ALL' | 'MEMBERS') => ({ price_category_id: id, price_mad, remaining, audience })
  expect(firstOpen([p('a', 9000, 1, 'ALL'), p('m', 1000, 9, 'MEMBERS'), p('b', 5000, 0, 'ALL'), p('c', 7000, 3, 'ALL')])?.price_category_id).toBe('c')
  expect(firstOpen([p('m2', 3000, 1, 'MEMBERS'), p('m1', 2000, 1, 'MEMBERS'), p('a', 1000, 0, 'ALL')])?.price_category_id).toBe('m1')
  expect(firstOpen([p('a', 1000, 0, 'ALL')])).toBeUndefined()
  expect(mapView('toString', ZONES)).toBeNull() // an inherited property name is not a layout
})
