import { renderToString } from 'react-dom/server'
import { getAccessFallbackHTTPStatus } from 'next/dist/client/components/http-access-fallback/http-access-fallback'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { json, NOW, ORG, ORG2, rawClub, rawListRow, SECRETS, TENSIFT, tensiftRow } from '@/lib/events.fixture'
import OpenGraphImage from './opengraph-image'
import ClubPage, { generateMetadata } from './page'

let follows: string | undefined
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: (name: string) => (name === 'follows' && follows ? { name, value: follows } : undefined) }),
  headers: async () => new Headers(),
}))
vi.mock('next/og', () => ({
  ImageResponse: class {
    constructor(readonly element: React.ReactElement) {}
  },
}))

const fetchMock = vi.fn<typeof fetch>()
let atlas: unknown[]
beforeEach(() => {
  follows = undefined
  atlas = [rawListRow()]
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubGlobal('fetch', fetchMock)
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
  fetchMock.mockImplementation(async (url) => {
    const path = String(url).replace('http://backend.test/v1', '')
    if (path === '/fan/organizers') return json(200, [rawClub(), rawClub(TENSIFT)])
    if (path === '/fan/organizers/atlas-fc') return json(200, rawClub())
    // A per-club read that (by a backend bug) also returns another club's row: dropped.
    if (path === `/fan/events?organizer=${ORG}`) return json(200, [...atlas, tensiftRow()])
    return json(404, { code: 'NOT_FOUND', message: '', details: {} })
  })
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
})

const params = (club: string) => ({ params: Promise.resolve({ club }) })
const html = async (club = 'atlas-fc') => renderToString(await ClubPage(params(club)))

it.each([
  ['an unknown slug', 'nope', true],
  ['a slug failing the 03 rule (no backend call)', 'ATLAS', false],
  ['a dotted path', '..', false],
])('%s → 404', async (_, slug, called) => {
  await expect(ClubPage(params(slug))).rejects.toSatisfy((e) => getAccessFallbackHTTPStatus(e as never) === 404)
  expect(fetchMock.mock.calls.length > 0).toBe(called)
})

it('title "Billetterie ATLAS FC", canonical /atlas-fc', async () => {
  const m = await generateMetadata(params('atlas-fc'))
  expect(m.title).toBe('Billetterie ATLAS FC')
  expect(m.alternates).toEqual({ canonical: '/atlas-fc' })
  expect(m.openGraph).toMatchObject({ url: '/atlas-fc' })
})

it('header: crest 64 + name (h1) + "Suivre"; then only this club\'s events, read with ?organizer=<id>', async () => {
  const h = await html()
  expect(h).toMatch(/<h1[^>]*><bdi>ATLAS FC<\/bdi><\/h1>/)
  expect(h).toMatch(/style="width:64px;height:64px;font-size:32px"[^>]*>A<\/span>/)
  expect(h).toContain(`name="organizer_id" value="${ORG}"`)
  expect(h).toContain('name="follow" value="1"')
  expect(h).toContain('name="next" value="/atlas-fc"')
  expect(h).toMatch(/aria-pressed="false"[^>]*>Suivre<\/button>/)
  expect(h).toContain('href="/atlas-fc/events/12-atlas-fc-vs-rapid-oued"')
  expect(h).not.toContain('Union Rif')
  expect(fetchMock.mock.calls.map(([u]) => String(u))).toContain(`http://backend.test/v1/fan/events?organizer=${ORG}`)
  for (const s of SECRETS) expect(h).not.toContain(s)
})

it('followed → "Suivi" (pressed), which unfollows', async () => {
  follows = `${ORG2},${ORG}`
  const h = await html()
  expect(h).toContain('name="follow" value="0"')
  expect(h).toMatch(/aria-pressed="true"[^>]*>Suivi<\/button>/)
})

it('empty → "Pas de match à venir" + "Voir tout" → /?club=all', async () => {
  atlas = []
  const h = await html()
  expect(h).toContain('Pas de match à venir')
  expect(h).toMatch(/href="\/\?club=all"[^>]*>Voir tout/)
})

it('OG: crest stand-in, club name, "Billetterie" and the lockup; unknown club → 404', async () => {
  const res = (await OpenGraphImage(params('atlas-fc'))) as unknown as { element: React.ReactElement }
  const h = renderToString(res.element)
  expect(h).toMatch(/>A<\/div>/)
  expect(h).toContain('ATLAS FC')
  expect(h).toContain('Billetterie')
  expect(h).toContain('src="data:image/svg+xml;base64,')
  for (const s of SECRETS) expect(h).not.toContain(s)
  await expect(OpenGraphImage(params('nope'))).rejects.toSatisfy((e) => getAccessFallbackHTTPStatus(e as never) === 404)
})
