import { renderToString } from 'react-dom/server'
import { getURLFromRedirectError } from 'next/dist/client/components/redirect'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { safeNext } from '@/lib/next-path'
import TicketsPage from './page'

let session: string | undefined
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: (name: string) => (name === '__Host-jomhor_session' && session ? { name, value: session } : undefined) }),
  headers: async () => new Headers(),
}))

const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => vi.stubGlobal('fetch', fetchMock))
afterEach(() => {
  vi.unstubAllGlobals()
  fetchMock.mockReset()
})

it('W5 not signed in → login, back to /me/tickets', async () => {
  session = undefined
  const e = await TicketsPage().catch((e: unknown) => e)
  const url = getURLFromRedirectError(e as never)!
  expect(url).toBe('/login?next=%2Fme%2Ftickets')
  expect(safeNext(new URL(url, 'https://shop.test').searchParams.get('next')!)).toBe('/me/tickets')
})

it('W5 signed in: the server HTML carries no ticket data (the list loads from /api/tickets in the browser)', async () => {
  session = 'eyJ.token.sig'
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ passes: [] }), { status: 200 }))
  const h = renderToString(await TicketsPage())
  expect(h).toContain('Mes billets')
  expect(h).toContain('Chargement de tes billets')
  expect(h).toContain('href="jomhor-fan://tickets"')
  expect(h).not.toContain(session)
  expect(h).not.toContain('Mes abonnements') // none held: no section
  // Lot 5 step 3: only the passes are read on the server (W5 "Mes abonnements"), never the tickets.
  expect(fetchMock.mock.calls.map(([u]) => String(u))).toEqual(['http://backend.test/v1/fan/passes'])
  vi.unstubAllEnvs()
})
