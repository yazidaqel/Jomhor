import { renderToString } from 'react-dom/server'
import { ltr } from '@/lib/i18n'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { json, NOW, ORG, ORG2, rawClub, rawListRow, SECRETS, TENSIFT, tensiftRow } from '@/lib/events.fixture'
import HomePage, { generateMetadata } from './page'

let follows: string | undefined // the `follows` cookie
let locale: string | undefined
vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) => {
      const value = name === 'follows' ? follows : name === 'locale' ? locale : undefined
      return value === undefined ? undefined : { name, value }
    },
  }),
  headers: async () => new Headers(),
}))

const fetchMock = vi.fn<typeof fetch>()
let atlas: unknown[]
let tensift: unknown[]
beforeEach(() => {
  follows = undefined
  locale = undefined
  atlas = [rawListRow()]
  tensift = [tensiftRow()]
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubGlobal('fetch', fetchMock)
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
  fetchMock.mockImplementation(async (url) => {
    const path = String(url).replace('http://backend.test/v1', '')
    if (path === '/fan/organizers') return json(200, [rawClub(), rawClub({ ...TENSIFT, kind: 'CONCERT_ORGANIZER' })])
    if (path === '/fan/events') return json(200, [...tensift, ...atlas])
    if (path === `/fan/events?organizer=${ORG}`) return json(200, atlas)
    if (path === `/fan/events?organizer=${ORG2}`) return json(200, tensift)
    return json(404, { code: 'NOT_FOUND', message: '', details: {} })
  })
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
})

const home = async (club?: string) => renderToString(await HomePage({ searchParams: Promise.resolve(club === undefined ? {} : { club }) }))
const eventReads = () => fetchMock.mock.calls.map(([u]) => String(u)).filter((u) => u.includes('/fan/events'))
/** The chip row: each chip's href, label and whether it is the current one. */
const chips = (h: string) => {
  const nav = h.slice(h.indexOf('<nav'), h.indexOf('</nav>'))
  return [...nav.matchAll(/<a ([^>]*)>.*?<bdi>(.*?)<\/bdi>/gs)].map(([, attrs, label]) => [/href="([^"]*)"/.exec(attrs!)![1]!.replaceAll('&amp;', '&'), label, attrs!.includes('aria-current="page"')])
}
const forms = (h: string) => [...h.matchAll(/<form [^>]*>.*?<\/form>/gs)].map((m) => m[0])

it('no cookie → Tout selected (the only chip), both clubs, every card with crest + club name', async () => {
  const h = await home()
  expect(chips(h)).toEqual([['/?club=all', 'Tout', true]])
  expect(eventReads()).toEqual(['http://backend.test/v1/fan/events'])
  expect(h).toContain('href="/olympique-tensift/events/21-olympique-tensift-vs-union-rif"')
  expect(h).toContain('href="/atlas-fc/events/12-atlas-fc-vs-rapid-oued"')
  const lines = [...h.matchAll(/<div data-club-line[^>]*>.*?<\/div>/gs)].map((m) => m[0])
  expect(lines).toHaveLength(2)
  expect(lines[0]).toMatch(/aria-hidden="true"[^>]*>O<\/span><bdi[^>]*>Olympique Tensift<\/bdi>/)
  expect(lines[1]).toMatch(/aria-hidden="true"[^>]*>A<\/span><bdi[^>]*>ATLAS FC<\/bdi>/)
  for (const s of SECRETS) expect(h).not.toContain(s)
})

it('Tout: a "Suivre" form on each unfollowed club card (works with JS off), back to Tout; "Suivi" on followed ones', async () => {
  follows = ORG2
  const h = await home('all')
  expect(chips(h)).toEqual([
    ['/?club=mine', 'Mes clubs', false],
    ['/?club=olympique-tensift', 'Olympique Tensift', false],
    ['/?club=all', 'Tout', true],
  ])
  const f = forms(h)
  expect(f).toHaveLength(1) // ATLAS FC only: Tensift is followed
  expect(f[0]).toMatch(/^<form [^>]*method="post"/)
  expect(f[0]).toMatch(/^<form [^>]*action="\/api\/follows"/)
  expect(f[0]).toContain(`name="organizer_id" value="${ORG}"`)
  expect(f[0]).toContain('name="follow" value="1"')
  expect(f[0]).toContain('name="next" value="/?club=all"')
  const lines = [...h.matchAll(/<div data-club-line[^>]*>.*?<\/div>/gs)].map((m) => m[0])
  const tensiftLine = lines.find((l) => l.includes('Olympique Tensift'))!
  expect(tensiftLine).toMatch(/<span data-following[^>]*><svg[^>]*>.*?<\/svg>Suivi<\/span>/s)
  expect(tensiftLine).not.toContain('<form')
  expect(tensiftLine).not.toContain('<button')
  expect(lines.find((l) => l.includes('ATLAS FC'))).not.toContain('data-following')
  expect(f[0]).toMatch(/<button type="submit" aria-pressed="false" aria-label="Suivre \u2068ATLAS FC\u2069"[^>]*>Suivre<\/button>/)
})

it('follows cookie (A) → Mes clubs selected by default, only A, one read per followed club; no "Suivre" outside Tout', async () => {
  follows = ORG
  const h = await home()
  expect(chips(h)).toEqual([
    ['/?club=mine', 'Mes clubs', true],
    ['/?club=atlas-fc', 'ATLAS FC', false],
    ['/?club=all', 'Tout', false],
  ])
  expect(eventReads()).toEqual([`http://backend.test/v1/fan/events?organizer=${ORG}`])
  expect(h).toContain('ATLAS FC vs Rapid Oued')
  expect(h).not.toContain('Union Rif')
  expect(forms(h)).toEqual([])
  expect(h).not.toContain('data-following')
})

it('Mes clubs of two: per-club reads merged by start time', async () => {
  follows = `${ORG},${ORG2}`
  const h = await home('mine')
  expect(eventReads().sort()).toEqual([`http://backend.test/v1/fan/events?organizer=${ORG}`, `http://backend.test/v1/fan/events?organizer=${ORG2}`])
  expect(h.indexOf('Union Rif')).toBeLessThan(h.indexOf('Rapid Oued')) // Tensift plays first
})

it.each<[string | undefined, string | undefined, string]>([
  ['all', ORG, 'Tout'],
  ['mine', ORG, 'Mes clubs'],
  ['mine', undefined, 'Tout'], // nothing followed
  ['atlas-fc', ORG, 'ATLAS FC'],
  ['olympique-tensift', ORG, 'Olympique Tensift'], // a club not followed gets its chip while selected
  ['unknown', ORG, 'Tout'],
  ['ATLAS-FC', undefined, 'Tout'],
  [undefined, 'not-a-uuid', 'Tout'], // junk cookie = no follows
])('?club=%s with cookie %s → %s selected', async (club, cookie, selected) => {
  follows = cookie
  const current = chips(await home(club)).filter(([, , cur]) => cur)
  expect(current.map(([, label]) => label)).toEqual([selected])
})

it('?club=<slug> reads that club only', async () => {
  const h = await home('olympique-tensift')
  expect(eventReads()).toEqual([`http://backend.test/v1/fan/events?organizer=${ORG2}`])
  expect(h).not.toContain('Rapid Oued')
})

it('an empty club: "Pas de match à venir" (sports club) or "Pas d\'événement à venir", and "Voir tout" → Tout', async () => {
  atlas = []
  tensift = []
  let h = await home('atlas-fc')
  expect(h).toContain('Pas de match à venir')
  expect(h).toMatch(/<a [^>]*href="\/\?club=all"[^>]*>Voir tout/)
  h = await home('olympique-tensift')
  expect(h).toContain('Pas d&#x27;événement à venir')
})

it('the clubs strip links every listed club to its page', async () => {
  const h = await home()
  const strip = h.slice(h.indexOf('id="clubs-title"'))
  expect([...strip.matchAll(/<a [^>]*href="(\/[^"]*)"/g)].map((m) => m[1]).slice(0, 2)).toEqual(['/atlas-fc', '/olympique-tensift'])
})

it('W1 cards: sale state, from-price and the hero CTA', async () => {
  atlas = [
    rawListRow(),
    rawListRow({ id: 13, title: 'Concert de clôture', event_type: 'CONCERT', starts_at: '2026-02-08T19:00:00Z', on_sale: false, sale_starts_at: '2026-02-03T09:00:00Z', from_price_mad: 15000 }),
    rawListRow({ id: 14, title: 'Derby', starts_at: '2026-02-09T19:00:00Z', sold_out: true, from_price_mad: null }),
  ]
  tensift = []
  const h = await home()
  expect(h).toMatch(/data-sale="ON_SALE"[^>]*>Réserver mes places/)
  expect(h).toContain('href="/atlas-fc/events/13-concert-de-cloture"')
  expect(h).toContain('Vente le Mar. 3 févr. 2026 · 10:00')
  expect(h).toContain('data-sale="SOLD_OUT"')
  expect(h).toContain(`Dès ${ltr('50\u00a0DH')}`)
  expect(h).toContain(`Dès ${ltr('150\u00a0DH')}`)
})

it('W1 says so when nothing is on sale', async () => {
  atlas = []
  tensift = []
  expect(await home()).toContain("Aucun événement en vente pour l&#x27;instant.")
})

it('AR: the chip row and the strip are in Arabic and mirror (logical CSS only); club names isolated', async () => {
  locale = 'ar'
  follows = ORG
  const h = await home()
  expect(chips(h).map(([, label]) => label)).toEqual(['الفرق ديالي', 'ATLAS FC', 'كلشي'])
})

it('W1 is indexable with the bare canonical URL and the "Billetterie" title', async () => {
  const m = await generateMetadata()
  expect(m.alternates).toEqual({ canonical: '/' })
  expect(m.title).toBe('Billetterie')
})
