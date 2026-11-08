import { readFileSync } from 'node:fs'
import { renderToString } from 'react-dom/server'
import { getAccessFallbackHTTPStatus } from 'next/dist/client/components/http-access-fallback/http-access-fallback'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { json, NOW, ORG2, rawEvent, rawOrganizer, SECRETS } from '@/lib/events.fixture'
import OpenGraphImage from './opengraph-image'

// Capture what would be drawn instead of rasterising it.
vi.mock('next/og', () => ({
  ImageResponse: class {
    constructor(
      readonly element: React.ReactElement,
      readonly options: unknown,
    ) {}
  },
}))

const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => {
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
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

const image = (slug: string, club = 'atlas-fc') => OpenGraphImage({ params: Promise.resolve({ club, slug }) })

it('carries the Jomhor lockup, the design/logo file as is', async () => {
  fetchMock.mockImplementation(async () => json(200, rawEvent()))
  const res = (await image('12-atlas-fc-vs-rapid-oued')) as unknown as { element: React.ReactElement }
  const lockup = readFileSync(new URL('../../../../../../../design/logo/jomhor-lockup-dark.svg', import.meta.url)).toString('base64')
  expect(renderToString(res.element)).toContain(`src="data:image/svg+xml;base64,${lockup}"`)
})

it("draws the organizer's initial and name, competition, title, and the date in the venue zone", async () => {
  fetchMock.mockImplementation(async () => json(200, rawEvent({ organizer_id: ORG2, organizer: rawOrganizer({ slug: 'olympique-tensift', name: 'Olympique Tensift', crest_url: 'https://assets.jomhor.ma/crests/ot.svg' }) })))
  const res = (await image('12-atlas-fc-vs-rapid-oued', 'olympique-tensift')) as unknown as { element: React.ReactElement; options: unknown }
  const h = renderToString(res.element)
  expect(h).toMatch(/>O<\/div>/)
  expect(h).toContain('Olympique Tensift')
  expect(h).not.toContain('assets.jomhor.ma') // the stand-in only: no server-side crest fetch
  expect(h).toContain('Championnat · Journée 11')
  expect(h).toContain('ATLAS FC vs Rapid Oued')
  expect(h).toContain('Sam. 7 févr. 2026 · 20:00') // 19:00Z in Africa/Casablanca (UTC+1)
  expect(h).toContain('Stade de l&#x27;Atlas')
  for (const s of SECRETS) expect(h).not.toContain(s)
  expect(res.options).toEqual({ width: 1200, height: 630 })
})

it("another club's slug in the URL → 404", async () => {
  fetchMock.mockImplementation(async () => json(200, rawEvent()))
  await expect(image('12-atlas-fc-vs-rapid-oued', 'olympique-tensift')).rejects.toSatisfy((e) => getAccessFallbackHTTPStatus(e as never) === 404)
})
