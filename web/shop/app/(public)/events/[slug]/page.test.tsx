import { getAccessFallbackHTTPStatus } from 'next/dist/client/components/http-access-fallback/http-access-fallback'
import { getRedirectStatusCodeFromError, getURLFromRedirectError } from 'next/dist/client/components/redirect'
import { isRedirectError } from 'next/dist/client/components/redirect-error'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { json, rawEvent, rawOrganizer } from '@/lib/events.fixture'
import OldEventPage from './page'

const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => {
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  fetchMock.mockReset()
})

const thrown = (slug: string) => OldEventPage({ params: Promise.resolve({ slug }) }).then(
  () => {
    throw new Error('expected a throw')
  },
  (e: unknown) => e,
)

it.each(['12-atlas-fc-vs-rapid-oued', '12-old-title', '12'])('/events/%s → 308 to /atlas-fc/events/12-atlas-fc-vs-rapid-oued', async (slug) => {
  fetchMock.mockResolvedValue(json(200, rawEvent()))
  const e = await thrown(slug)
  expect(isRedirectError(e)).toBe(true)
  expect(getRedirectStatusCodeFromError(e as never)).toBe(308)
  expect(getURLFromRedirectError(e as never)).toBe('/atlas-fc/events/12-atlas-fc-vs-rapid-oued')
})

it.each<[string, string, Response | null]>([
  ['unknown', '99-x', json(404, { code: 'NOT_FOUND', message: '', details: {} })],
  ['a club without a slug', '12-x', json(200, rawEvent({ organizer: rawOrganizer({ slug: null }) }))],
  ['no id', 'atlas-fc', null],
])('%s → 404', async (_, slug, res) => {
  if (res) fetchMock.mockResolvedValue(res)
  expect(getAccessFallbackHTTPStatus((await thrown(slug)) as never)).toBe(404)
})
