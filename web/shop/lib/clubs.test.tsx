import { renderToString } from 'react-dom/server'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Crest, initialOf } from '@/app/brand'
import { eventAt, eventPath, getClub, getClubs, parseFollows, readFollows } from './clubs'
import { json, NOW, ORG, ORG2, rawClub, rawEvent, rawOrganizer, SECRETS, TENSIFT } from './events.fixture'

let follows: string | undefined // the `follows` cookie
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: (name: string) => (name === 'follows' && follows !== undefined ? { name, value: follows } : undefined) }),
}))

const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => {
  follows = undefined
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  fetchMock.mockReset()
})

const DIRECTORY = [rawClub(), rawClub({ ...TENSIFT, crest_url: 'https://assets.jomhor.ma/crests/ot.svg' })]

it('the directory is mapped field by field, cached 60 s, without Authorization', async () => {
  fetchMock.mockResolvedValue(json(200, DIRECTORY))

  const clubs = await getClubs()

  expect(clubs).toEqual([
    { id: ORG, slug: 'atlas-fc', name: 'ATLAS FC', kind: 'SPORTS_CLUB', crest_url: null },
    { id: ORG2, slug: 'olympique-tensift', name: 'Olympique Tensift', kind: 'SPORTS_CLUB', crest_url: 'https://assets.jomhor.ma/crests/ot.svg' },
  ])
  const text = JSON.stringify(clubs)
  for (const s of SECRETS) expect(text).not.toContain(s)
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('http://backend.test/v1/fan/organizers')
  expect(init).toMatchObject({ next: { revalidate: 60 } })
  expect(new Headers(init!.headers).has('authorization')).toBe(false)
})

it('getClub reads /fan/organizers/{slug}; 404 → null; a malformed slug never reaches the backend', async () => {
  fetchMock.mockResolvedValueOnce(json(200, rawClub()))
  expect(await getClub('atlas-fc')).toMatchObject({ id: ORG, slug: 'atlas-fc' })
  expect(fetchMock.mock.calls[0]![0]).toBe('http://backend.test/v1/fan/organizers/atlas-fc')

  fetchMock.mockResolvedValueOnce(json(404, { code: 'NOT_FOUND', message: '', details: {} }))
  expect(await getClub('nope')).toBeNull()

  fetchMock.mockClear()
  for (const bad of ['ATLAS', '../x', 'a', 'atlas--fc', '-atlas', 'x'.repeat(41), 'atlas%2Ffc']) expect(await getClub(bad)).toBeNull()
  expect(fetchMock).not.toHaveBeenCalled()
})

it('readFollows drops malformed ids and ids missing from the directory, keeps follow order', async () => {
  fetchMock.mockResolvedValue(json(200, DIRECTORY))
  follows = `${ORG2},not-a-uuid,0190f3a2-0000-7000-8000-00000000dead,${ORG.toUpperCase()},${ORG2}`
  expect((await readFollows()).map((c) => c.slug)).toEqual(['olympique-tensift', 'atlas-fc'])
})

it('no follows cookie → no directory read', async () => {
  expect(await readFollows()).toEqual([])
  expect(fetchMock).not.toHaveBeenCalled()
})

it('parseFollows keeps the 50 newest valid ids', () => {
  const ids = Array.from({ length: 60 }, (_, i) => `0190f3a2-0000-7000-8000-${String(i).padStart(12, '0')}`)
  expect(parseFollows(ids.join(','))).toEqual(ids.slice(10))
  expect(parseFollows(undefined)).toEqual([])
})

it('eventPath = /{club}/events/{id}-{slug}; null for a club without a slug', () => {
  expect(eventPath({ id: 12, title: 'ATLAS FC vs Rapid Oued', organizer: { slug: 'atlas-fc', name: 'ATLAS FC', crest_url: null } })).toBe('/atlas-fc/events/12-atlas-fc-vs-rapid-oued')
  expect(eventPath({ id: 12, title: 'X', organizer: { slug: null, name: 'Club X', crest_url: null } })).toBeNull()
})

it('eventAt: another club in the URL or an unlisted club → null; the event slug spelling is left to the page', async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
  fetchMock.mockImplementation(async () => json(200, rawEvent()))
  expect(await eventAt('atlas-fc', '12-old')).toMatchObject({ path: '/atlas-fc/events/12-atlas-fc-vs-rapid-oued' })
  expect(await eventAt('olympique-tensift', '12-atlas-fc-vs-rapid-oued')).toBeNull()
  fetchMock.mockImplementation(async () => json(200, rawEvent({ organizer: rawOrganizer({ slug: null }) })))
  expect(await eventAt('atlas-fc', '12-atlas-fc-vs-rapid-oued')).toBeNull()
  vi.useRealTimers()
})

it.each([
  ['Olympique Tensift', 'O'],
  ['  atlas fc', 'A'],
  ['الرجاء الرياضي', 'ا'],
  ['Étoile', 'É'],
  ['', ''],
])('initialOf(%j) = %j', (name, initial) => {
  expect(initialOf(name)).toBe(initial)
})

it('Crest: a crest_url is loaded by the browser as is (unoptimized: no /_next/image proxy, no remotePatterns); null → the stand-in', () => {
  const url = 'https://assets.jomhor.ma/crests/atlas-fc.svg'
  const img = renderToString(<Crest club={{ name: 'ATLAS FC', crest_url: url }} size={20} />)
  expect(img).toMatch(new RegExp(`^<img alt="" [^>]*src="${url}"`))
  expect(img).not.toContain('_next/image')
  expect(img).not.toContain('bg-accent') // never on a disc
  expect(renderToString(<Crest club={{ name: 'ATLAS FC', crest_url: null }} size={20} />)).toMatch(/^<span aria-hidden="true"[^>]*bg-accent[^>]*>A<\/span>$/)
})
