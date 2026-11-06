import { renderToString } from 'react-dom/server'
import { getAccessFallbackHTTPStatus } from 'next/dist/client/components/http-access-fallback/http-access-fallback'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { json, NOW, ORG, rawEvent, SECRETS } from '@/lib/events.fixture'
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

const image = (slug: string) => OpenGraphImage({ params: Promise.resolve({ slug }) })

it('draws crest, club, competition, title, and the date in the venue zone', async () => {
  fetchMock.mockImplementation(async () => json(200, rawEvent()))
  const res = (await image('12-atlas-fc-vs-rapid-oued')) as unknown as { element: React.ReactElement; options: unknown }
  const h = renderToString(res.element)
  expect(h).toContain('ATLAS FC')
  expect(h).toContain('Championnat · Journée 11')
  expect(h).toContain('ATLAS FC vs Rapid Oued')
  expect(h).toContain('Sam. 7 févr. 2026 · 20:00') // 19:00Z in Africa/Casablanca (UTC+1)
  expect(h).toContain('Stade de l&#x27;Atlas')
  for (const s of SECRETS) expect(h).not.toContain(s)
  expect(res.options).toEqual({ width: 1200, height: 630 })
})

it("another organizer's event → 404", async () => {
  fetchMock.mockImplementation(async () => json(200, rawEvent({ organizer_id: 'someone-else' })))
  await expect(image('12-atlas-fc-vs-rapid-oued')).rejects.toSatisfy((e) => getAccessFallbackHTTPStatus(e as never) === 404)
})
