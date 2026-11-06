import { renderToString } from 'react-dom/server'
import { getAccessFallbackHTTPStatus, isHTTPAccessFallbackError } from 'next/dist/client/components/http-access-fallback/http-access-fallback'
import { getRedirectStatusCodeFromError, getURLFromRedirectError } from 'next/dist/client/components/redirect'
import { isRedirectError } from 'next/dist/client/components/redirect-error'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { json, NOW, ORG, rawEvent, rawListRow, SECRETS } from '@/lib/events.fixture'
import HomePage from '../../page'
import { EventView } from './event-view'
import EventPage from './page'
import { ZonePicker } from './ZonePicker'

const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => {
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubEnv('ORGANIZER_ID', ORG)
  vi.stubGlobal('fetch', fetchMock)
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
})

const page = (slug: string) => EventPage({ params: Promise.resolve({ slug }) })
async function html(slug: string, event = rawEvent()) {
  fetchMock.mockImplementation(async () => json(200, event))
  return renderToString(await page(slug))
}
async function thrown(p: Promise<unknown>) {
  try {
    await p
  } catch (e) {
    return e
  }
  throw new Error('expected a throw')
}
/** The `<button>` (zone card) whose content contains `text`. */
const card = (h: string, text: string) => [...h.matchAll(/<button[^>]*>.*?<\/button>/gs)].map((m) => m[0]).find((b) => b.includes(text))!

it.each(['12-old-title', '12', '12-ATLAS-FC-vs-Rapid-Oued', '12-atlas-fc-vs-rapid-oued-'])(
  'slug %j → 308 to the canonical slug',
  async (slug) => {
    fetchMock.mockResolvedValue(json(200, rawEvent()))
    const e = await thrown(page(slug))
    expect(isRedirectError(e)).toBe(true)
    expect(getRedirectStatusCodeFromError(e as never)).toBe(308)
    expect(getURLFromRedirectError(e as never)).toBe('/events/12-atlas-fc-vs-rapid-oued')
  },
)

it.each<[string, string, Response | null]>([
  ['an unknown or DRAFT event', '99-x', json(404, { code: 'NOT_FOUND', message: '', details: {} })],
  ["another organizer's event", '12-atlas-fc-vs-rapid-oued', json(200, rawEvent({ organizer_id: 'other' }))],
  ['a slug without an id', 'atlas-fc', null],
])('%s → 404', async (_, slug, res) => {
  if (res) fetchMock.mockResolvedValue(res)
  const e = await thrown(page(slug))
  expect(isHTTPAccessFallbackError(e)).toBe(true)
  expect(getAccessFallbackHTTPStatus(e as never)).toBe(404)
  if (!res) expect(fetchMock).not.toHaveBeenCalled()
})

it('renders the hero in the venue zone, the zone cards and the summary', async () => {
  const h = await html('12-atlas-fc-vs-rapid-oued')
  expect(h).toContain('ATLAS FC vs Rapid Oued')
  expect(h).toContain('Championnat · Journée 11')
  expect(h).toContain('Sam. 7 févr. 2026 · 20:00')
  expect(h).toContain('Stade de l&#x27;Atlas · Portes 18:30')
  expect(h).toContain('Vente ouverte · 4 billets max par compte')
  expect(h).toContain('120,50\u00a0DH') // price_mad 12050, integer formatter
  expect(h).toContain('2\u202f300 restants')
  expect(card(h, 'Tribune · Debout')).toContain('Dernières places') // remaining 30 ≤ 50
  expect(h).toContain('Choisis une zone pour continuer.')
})

it('a sold-out price category is a disabled "Complet" card; the others stay selectable', async () => {
  const h = await html('12-atlas-fc-vs-rapid-oued')
  const sud = card(h, 'Tribune Sud')
  expect(sud).toMatch(/<button[^>]* disabled=""/)
  expect(sud).toContain('Complet')
  expect(sud).not.toContain('80\u00a0DH')
  expect(card(h, 'Virage')).not.toMatch(/<button[^>]* disabled=""/)
})

it('before the opening: "Vente le {date}" chip and every card disabled', async () => {
  const h = await html('12-atlas-fc-vs-rapid-oued', rawEvent({ on_sale: false, sale_starts_at: '2026-02-03T09:00:00Z' }))
  expect(h).toContain('data-sale="NOT_STARTED"')
  expect(h).toContain('Vente le Mar. 3 févr. 2026 · 10:00')
  for (const zone of ['Tribune', 'Virage', 'Tribune Sud']) expect(card(h, zone)).toMatch(/<button[^>]* disabled=""/)
})

it('window open but the backend still says not on sale (clock skew): "ouverture imminente", never "Vente terminée"', async () => {
  const h = await html('12-atlas-fc-vs-rapid-oued', rawEvent({ on_sale: false, sale_starts_at: '2026-02-01T11:59:50Z' }))
  expect(h).toContain('data-sale="OPENING"')
  expect(h).toContain('Ouverture de la vente imminente')
  expect(h).not.toContain('Vente terminée')
  for (const zone of ['Tribune', 'Virage']) expect(card(h, zone)).toMatch(/<button[^>]* disabled=""/)
})

/** Depth-first search of a rendered element tree for the first element of `type`. */
function findElement(node: unknown, type: unknown): React.ReactElement<Record<string, unknown>> | undefined {
  if (Array.isArray(node)) {
    for (const n of node) {
      const hit = findElement(n, type)
      if (hit) return hit
    }
    return undefined
  }
  if (!node || typeof node !== 'object' || !('props' in node)) return undefined
  const el = node as React.ReactElement<Record<string, unknown>>
  return el.type === type ? el : findElement(el.props.children, type)
}

it('the ZonePicker client props (the RSC payload) carry no key and nothing outside the contract', async () => {
  fetchMock.mockImplementation(async () => json(200, rawEvent()))
  const view = (await page('12-atlas-fc-vs-rapid-oued')) as React.ReactElement<Parameters<typeof EventView>[0]>
  const picker = findElement(EventView(view.props), ZonePicker)!
  const props: Record<string, unknown> = { ...picker.props }
  delete props.children // server-rendered markup, not client props
  const payload = JSON.stringify(props)
  expect(payload).toContain('pc-virage')
  for (const s of SECRETS) expect(payload).not.toContain(s)
})

it('the HTML carries no key and nothing outside the contract', async () => {
  const h = await html('12-atlas-fc-vs-rapid-oued')
  for (const s of SECRETS) expect(h).not.toContain(s)
})

it('W1 lists the organizer events with state, from-price and canonical links', async () => {
  fetchMock.mockResolvedValue(
    json(200, [
      rawListRow(),
      rawListRow({ id: 13, title: 'Concert de clôture', event_type: 'CONCERT', on_sale: false, sale_starts_at: '2026-02-03T09:00:00Z', from_price_mad: 15000 }),
      rawListRow({ id: 14, title: 'Derby', sold_out: true, from_price_mad: null }),
    ]),
  )
  const h = renderToString(await HomePage())
  expect(h).toContain('href="/events/12-atlas-fc-vs-rapid-oued"')
  expect(h).toContain('href="/events/13-concert-de-cloture"')
  expect(h).toContain('Vente ouverte · 4 billets max par compte')
  expect(h).toContain('Vente le Mar. 3 févr. 2026 · 10:00')
  expect(h).toContain('data-sale="SOLD_OUT"')
  expect(h).toContain('Dès 50\u00a0DH')
  expect(h).toContain('Dès 150\u00a0DH')
  for (const s of SECRETS) expect(h).not.toContain(s)
})

it('W1 says so when nothing is on sale', async () => {
  fetchMock.mockResolvedValue(json(200, []))
  expect(renderToString(await HomePage())).toContain("Aucun événement en vente pour l&#x27;instant.")
})
