import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { eventSlug, getEvent, getEvents, parseEventId, saleState, slugify } from './events'
import { json, NOW, ORG, ORG2, rawEvent, rawListRow, rawOrganizer, SECRETS, tensiftRow } from './events.fixture'

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

it.each([
  ['ATLAS FC vs Rapid Oued', 'atlas-fc-vs-rapid-oued'],
  ['Raja – Wydad : le Derby !', 'raja-wydad-le-derby'],
  ['Fête de la Musique · Été 2026', 'fete-de-la-musique-ete-2026'],
  ['  --Concert--  ', 'concert'],
  ['الرجاء ضد الوداد', ''],
  [`${'a'.repeat(79)} b`, 'a'.repeat(79)], // cut at 80, no trailing dash
])('slugify(%j) → %j', (title, slug) => {
  expect(slugify(title)).toBe(slug)
})

it('the canonical slug is {id}-{title}, or {id} alone', () => {
  expect(eventSlug({ id: 12, title: 'ATLAS FC vs Rapid Oued' })).toBe('12-atlas-fc-vs-rapid-oued')
  expect(eventSlug({ id: 12, title: 'الرجاء' })).toBe('12')
})

it.each<[string, number | null]>([
  ['12-atlas-fc-vs-rapid-oued', 12],
  ['12', 12],
  ['12-anything-else', 12],
  ['2147483647', 2147483647],
  ['2147483648', null],
  ['012-x', null],
  ['0', null],
  ['12abc', null],
  ['x-12', null],
  ['-12', null],
  ['', null],
])('parseEventId(%j) → %s', (slug, id) => {
  expect(parseEventId(slug)).toBe(id)
})

const at = (iso: string) => Date.parse(iso)
it.each<[string, Record<string, unknown>, string]>([
  ['open', {}, 'ON_SALE'],
  ['before the opening', { on_sale: false, sale_starts_at: '2026-02-03T10:00:00Z' }, 'NOT_STARTED'],
  ['sold out', { sold_out: true }, 'SOLD_OUT'],
  ['closed: not on sale and sales_close_at passed', { on_sale: false, sales_close_at: '2026-02-01T11:00:00Z' }, 'CLOSED'],
  ['window open but the read says not on sale (stale read or clock skew)', { on_sale: false }, 'OPENING'],
  ['a cached on_sale read past sales_close_at', { sales_close_at: '2026-02-01T11:59:00Z' }, 'CLOSED'],
  ['no opening time and not on sale', { on_sale: false, sale_starts_at: null }, 'CLOSED'],
])('saleState: %s', (_, over, state) => {
  expect(saleState({ ...rawEvent(), ...over } as never, at('2026-02-01T12:00:00Z'))).toBe(state)
})

it('Tout: one read of /fan/events with no organizer, without Authorization, cached 60 s', async () => {
  fetchMock.mockResolvedValue(json(200, [rawListRow(), tensiftRow()]))

  const events = await getEvents('all', NOW)

  expect(events.map((e) => e.id)).toEqual([12, 21])
  expect(fetchMock).toHaveBeenCalledTimes(1)
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('http://backend.test/v1/fan/events')
  expect(new Headers(init!.headers).has('authorization')).toBe(false)
  expect(init).toMatchObject({ next: { revalidate: 60 } })
  expect(init!.cache).toBeUndefined()
})

it('one club: ?organizer=<id>, cached 60 s; rows of another organizer are dropped', async () => {
  fetchMock.mockResolvedValue(json(200, [rawListRow(), tensiftRow()]))

  const events = await getEvents([ORG], NOW)

  expect(events.map((e) => e.id)).toEqual([12])
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe(`http://backend.test/v1/fan/events?organizer=${ORG}`)
  expect(init).toMatchObject({ next: { revalidate: 60 } })
})

it('Mes clubs: one cached read per club (never one per combination), merged by (starts_at, id), at most 50', async () => {
  const atlas = Array.from({ length: 40 }, (_, i) => rawListRow({ id: 100 + i, starts_at: `2026-02-${String(10 + (i % 15)).padStart(2, '0')}T19:00:00Z` }))
  const tensift = Array.from({ length: 30 }, (_, i) => tensiftRow({ id: 200 + i, starts_at: `2026-02-${String(10 + (i % 15)).padStart(2, '0')}T19:00:00Z` }))
  fetchMock.mockImplementation(async (url) => json(200, String(url).endsWith(ORG) ? atlas : tensift))

  const events = await getEvents([ORG, ORG2], NOW)

  expect(fetchMock.mock.calls.map(([url]) => url).sort()).toEqual([`http://backend.test/v1/fan/events?organizer=${ORG}`, `http://backend.test/v1/fan/events?organizer=${ORG2}`])
  for (const [, init] of fetchMock.mock.calls) expect(init).toMatchObject({ next: { revalidate: 60 } })
  expect(events).toHaveLength(50)
  const keys = events.map((e) => [Date.parse(e.starts_at), e.id] as const)
  expect(keys).toEqual([...keys].sort((a, b) => a[0] - b[0] || a[1] - b[1]))
  expect(new Set(events.map((e) => e.organizer.slug))).toEqual(new Set(['atlas-fc', 'olympique-tensift']))
})

it('no club → no read', async () => {
  expect(await getEvents([], NOW)).toEqual([])
  expect(fetchMock).not.toHaveBeenCalled()
})

it('organizer keeps slug, name and crest_url only; a non-https crest is dropped', async () => {
  fetchMock.mockResolvedValue(json(200, [rawListRow(), rawListRow({ id: 13, organizer: rawOrganizer({ crest_url: 'https://assets.jomhor.ma/crests/atlas-fc.svg' }) }), rawListRow({ id: 14, organizer: rawOrganizer({ crest_url: 'http://evil.test/x.svg' }) })]))

  const events = await getEvents('all', NOW)

  expect(events.map((e) => e.organizer)).toEqual([
    { slug: 'atlas-fc', name: 'ATLAS FC', crest_url: null },
    { slug: 'atlas-fc', name: 'ATLAS FC', crest_url: 'https://assets.jomhor.ma/crests/atlas-fc.svg' },
    { slug: 'atlas-fc', name: 'ATLAS FC', crest_url: null },
  ])
  const text = JSON.stringify(events)
  for (const x of SECRETS) expect(text).not.toContain(x)
})

it('a row of an unlisted club (slug null: no shop URL) is left out of lists', async () => {
  fetchMock.mockResolvedValue(json(200, [rawListRow(), rawListRow({ id: 13, organizer: rawOrganizer({ slug: null }) })]))
  expect((await getEvents([ORG], NOW)).map((e) => e.id)).toEqual([12])
})

it('getEvent maps field by field: nothing outside the contract survives', async () => {
  fetchMock.mockResolvedValue(json(200, rawEvent()))

  const e = await getEvent(12, NOW)

  const text = JSON.stringify(e)
  for (const s of SECRETS) expect(text).not.toContain(s)
  expect(e).toMatchObject({ id: 12, organizer_id: ORG, organizer: { slug: 'atlas-fc', name: 'ATLAS FC', crest_url: null }, competition: 'Championnat', matchday: 11, per_account_limit: 4 })
  expect(e!.zones[0]!.prices[0]).toEqual({ price_category_id: 'pc-tribune', name: 'Tribune', price_mad: 12050, remaining: 30 })
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('http://backend.test/v1/fan/events/12')
  expect(init).toMatchObject({ next: { revalidate: 15 } })
})

it('getEvent → null for an unknown or DRAFT event (404)', async () => {
  fetchMock.mockResolvedValue(json(404, { code: 'NOT_FOUND', message: '', details: {} }))
  expect(await getEvent(12, NOW)).toBeNull()
})

it("getEvent returns any club's event, an unlisted one included (the page checks the URL's club)", async () => {
  fetchMock.mockResolvedValue(json(200, rawEvent({ organizer_id: ORG2, organizer: rawOrganizer({ slug: null, name: 'Club X' }) })))
  expect((await getEvent(12, NOW))!.organizer).toEqual({ slug: null, name: 'Club X', crest_url: null })
})

it('getEvent lets other backend errors through', async () => {
  fetchMock.mockResolvedValue(json(500, { code: 'INTERNAL_ERROR', message: '', details: {} }))
  await expect(getEvent(12, NOW)).rejects.toMatchObject({ status: 500 })
})


const opened = (secondsAgo: number) => new Date(NOW - secondsAgo * 1000).toISOString()

it.each([
  ['20 s after the opening (cached 10 s before it)', 20],
  ['10 min after the opening (stale-while-revalidate keeps old copies)', 600],
])('a cached "not on sale" read %s is fetched again, uncached', async (_, ago) => {
  fetchMock
    .mockResolvedValueOnce(json(200, rawEvent({ on_sale: false, sale_starts_at: opened(ago) })))
    .mockResolvedValueOnce(json(200, rawEvent({ on_sale: true, sale_starts_at: opened(ago) })))

  const e = await getEvent(12, NOW)

  expect(e!.on_sale).toBe(true)
  expect(saleState(e!, NOW)).toBe('ON_SALE')
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(fetchMock.mock.calls[1]![1]).toMatchObject({ cache: 'no-store' })
  expect(fetchMock.mock.calls[1]![1]).not.toHaveProperty('next')
})

it('clock skew (the uncached read still says not on sale) gives OPENING, never CLOSED', async () => {
  fetchMock.mockImplementation(async () => json(200, rawEvent({ on_sale: false, sale_starts_at: opened(3) })))

  const e = await getEvent(12, NOW)

  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(saleState(e!, NOW)).toBe('OPENING')
})

it('the list is re-read the same way when one row contradicts its sale window', async () => {
  fetchMock
    .mockResolvedValueOnce(json(200, [rawListRow({ on_sale: false, sale_starts_at: opened(20) })]))
    .mockResolvedValueOnce(json(200, [rawListRow()]))
  expect((await getEvents([ORG], NOW))[0]!.on_sale).toBe(true)
  expect(fetchMock).toHaveBeenCalledTimes(2)
})

it.each([
  ['before the opening', { on_sale: false, sale_starts_at: '2026-02-03T10:00:00Z' }],
  ['after sales_close_at', { on_sale: false, sales_close_at: '2026-02-01T11:00:00Z' }],
  ['without an opening time (e.g. GATES_OPEN)', { on_sale: false, sale_starts_at: null }],
  ['on sale', {}],
])('a read %s is not fetched again', async (_, over) => {
  fetchMock.mockResolvedValue(json(200, rawEvent(over)))
  await getEvent(12, NOW)
  expect(fetchMock).toHaveBeenCalledTimes(1)
})
