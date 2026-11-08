import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BACKEND_TIMEOUT_MS } from '@/lib/api'
import { GET as getPdf } from './[id]/pdf/route'
import { POST as transfer } from './[id]/transfer/route'
import { GET as getTickets } from './route'

const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsImF1ZCI6ImZhbiJ9.c2lnbmF0dXJl'
const TICKET_ID = '0190f3a2-7c1e-7b55-9a1e-3f1f4d5c6b7a'
const K_T = 'q0Vq3ztYB1kGAiZ1T0Eo8s1vVq3b9rC9JmUq2G8a3hY'

const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => {
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubGlobal('fetch', fetchMock)
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  fetchMock.mockReset()
})

const req = (path: string, cookie = `a=1; __Host-jomhor_session=${TOKEN}`) => new Request(`http://shop.test${path}`, { headers: { cookie } })
const params = (id: string) => ({ params: Promise.resolve({ id }) })
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const EVENT = {
  id: 12,
  title: 'ATLAS FC vs Rapid Oued',
  event_type: 'FOOTBALL_MATCH',
  status: 'ON_SALE',
  starts_at: '2026-02-07T19:00:00Z',
  doors_open_at: '2026-02-07T17:30:00Z',
  timezone: 'Africa/Casablanca',
  venue: { name: "Stade de l'Atlas", city: 'Rabat', secret: 'SECRET-VENUE' },
  metadata: { competition: 'Championnat', internal_note: 'SECRET-META' },
  organizer: { id: '0190f3a2-0000-7000-8000-000000000001', slug: 'atlas-fc', name: 'ATLAS FC', crest_url: null, settings: { theme: 'SECRET-THEME' } },
}
const ticket = (over: Record<string, unknown> = {}) => ({
  id: TICKET_ID,
  ticket_no: '4D5C 6B7A',
  status: 'VALID',
  holder_label: 'Yazid A.',
  seat_label: null,
  event: EVENT,
  zone: { id: 3, code: 'NORD', name: 'Tribune Nord', key: 'SECRET-ZONE' },
  gate_codes: ['P10', 'P4'],
  k_t: K_T,
  ...over,
})

it('/api/tickets: Bearer from the cookie, no with_keys; the answer has no k_t even when the backend sends one', async () => {
  fetchMock.mockResolvedValue(json(200, { server_time: '2026-02-07T20:00:00Z', k_e: 'SECRET-KE', tickets: [ticket(), ticket({ id: 'other', status: 'BLOCKED' })] }))
  const res = await getTickets(req('/api/tickets'))

  expect(res.status).toBe(200)
  expect(res.headers.get('cache-control')).toBe('no-store')
  const text = await res.text()
  for (const s of ['k_t', K_T, 'k_e', 'SECRET', 'key', 'metadata', 'settings', TOKEN]) expect(text).not.toContain(s)
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('http://backend.test/v1/fan/tickets') // never ?with_keys
  expect(new Headers(init!.headers).get('authorization')).toBe(`Bearer ${TOKEN}`)
  const body = JSON.parse(text)
  expect(body.tickets).toHaveLength(2)
  expect(body.tickets[0]).toEqual({
    id: TICKET_ID,
    ticket_no: '4D5C 6B7A',
    status: 'VALID',
    holder_label: 'Yazid A.',
    seat_label: null,
    event: {
      title: 'ATLAS FC vs Rapid Oued',
      status: 'ON_SALE',
      starts_at: '2026-02-07T19:00:00Z',
      timezone: 'Africa/Casablanca',
      venue: { name: "Stade de l'Atlas", city: 'Rabat' },
      organizer: { slug: 'atlas-fc', name: 'ATLAS FC', crest_url: null },
    },
    zone: { code: 'NORD', name: 'Tribune Nord' },
    gate_codes: ['P10', 'P4'],
    past: false,
    transfer_until: null,
    transfer_open: false,
    transferred: null,
  })
})

it.each<[string, string, unknown, boolean]>([
  ['11 h 59 min 59 s after the start', '2026-02-08T06:59:59Z', 'GATES_OPEN', false],
  ['12 h after the start', '2026-02-08T07:00:00Z', 'GATES_OPEN', true],
  ['CLOSED, 1 h after the start', '2026-02-07T20:00:00Z', 'CLOSED', true],
  ['status absent, 1 h after the start', '2026-02-07T20:00:00Z', undefined, false],
])('past per 04, on server_time: %s → %s', async (_, server_time, status, past) => {
  fetchMock.mockResolvedValue(json(200, { server_time, tickets: [ticket({ event: { ...EVENT, status } })] }))
  const body = await (await getTickets(req('/api/tickets'))).json()
  expect(body.tickets[0].past).toBe(past)
})

it('/api/tickets without a session → 401, no backend call; a backend 401 passes through', async () => {
  expect((await getTickets(req('/api/tickets', ''))).status).toBe(401)
  expect(fetchMock).not.toHaveBeenCalled()
  fetchMock.mockResolvedValue(json(401, { code: 'UNAUTHORIZED', message: '', details: {} }))
  const res = await getTickets(req('/api/tickets'))
  expect(res.status).toBe(401)
  expect(await res.json()).toMatchObject({ code: 'UNAUTHORIZED' })
})

const PDF = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37, 0x0a]) // %PDF-1.7

it('PDF: streams the backend bytes as a no-store attachment billet-{ticket_no}.pdf', async () => {
  fetchMock.mockResolvedValue(new Response(PDF, { status: 200, headers: { 'content-type': 'application/pdf', 'set-cookie': 'x=1' } }))
  const res = await getPdf(req(`/api/tickets/${TICKET_ID}/pdf`), params(TICKET_ID))

  expect(res.status).toBe(200)
  expect(res.headers.get('content-type')).toBe('application/pdf')
  expect(res.headers.get('cache-control')).toBe('no-store')
  expect(res.headers.get('content-disposition')).toBe('attachment; filename="billet-4D5C-6B7A.pdf"')
  expect(res.headers.get('set-cookie')).toBeNull()
  expect(new Uint8Array(await res.arrayBuffer())).toEqual(PDF)
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe(`http://backend.test/v1/fan/tickets/${TICKET_ID}/pdf`)
  const h = new Headers(init!.headers)
  expect(h.get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(h.get('accept')).toBe('application/pdf')
})

it.each([
  [404, { code: 'NOT_FOUND', message: 'Unknown ticket', details: {} }],
  [409, { code: 'TICKET_NOT_VALID', message: 'Ticket is not valid', details: {} }],
  [401, { code: 'UNAUTHORIZED', message: '', details: {} }],
])('PDF: a backend %i keeps its status and 04 body', async (status, body) => {
  fetchMock.mockResolvedValue(json(status, body))
  const res = await getPdf(req(`/api/tickets/${TICKET_ID}/pdf`), params(TICKET_ID))
  expect(res.status).toBe(status)
  expect(await res.json()).toEqual(body)
})

it('PDF: a 200 that is not a PDF is refused (502), never handed to the browser', async () => {
  fetchMock.mockResolvedValue(json(200, { k_t: K_T }))
  const res = await getPdf(req(`/api/tickets/${TICKET_ID}/pdf`), params(TICKET_ID))
  expect(res.status).toBe(502)
  expect(await res.text()).not.toContain(K_T)
})

it('PDF: backend unreachable → 502 in the 04 shape', async () => {
  fetchMock.mockRejectedValue(new TypeError('fetch failed'))
  const res = await getPdf(req(`/api/tickets/${TICKET_ID}/pdf`), params(TICKET_ID))
  expect(res.status).toBe(502)
  expect(await res.json()).toMatchObject({ code: 'INTERNAL_ERROR' })
})

it.each(['..', '../me', 'x%2Fy', '12', `${TICKET_ID}x`, TICKET_ID.replace(/-/g, '')])('PDF: an id like %j never reaches the backend (404)', async (id) => {
  const res = await getPdf(req('/api/tickets/x/pdf'), params(id))
  expect(res.status).toBe(404)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('PDF without a session → 401, no backend call', async () => {
  expect((await getPdf(req(`/api/tickets/${TICKET_ID}/pdf`, ''), params(TICKET_ID))).status).toBe(401)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('PDF: the browser going away aborts the backend request', async () => {
  fetchMock.mockImplementation(
    (_, init) => new Promise((_, reject) => init!.signal!.addEventListener('abort', () => reject(init!.signal!.reason), { once: true })),
  )
  const browser = new AbortController()
  const r = new Request(`http://shop.test/api/tickets/${TICKET_ID}/pdf`, { headers: { cookie: `__Host-jomhor_session=${TOKEN}` }, signal: browser.signal })
  const res = getPdf(r, params(TICKET_ID))
  await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())
  const signal = fetchMock.mock.calls[0]![1]!.signal!
  expect(signal.aborted).toBe(false)
  browser.abort()
  expect(signal.aborted).toBe(true)
  expect((await res).status).toBe(502)
})

it('PDF: no headers within BACKEND_TIMEOUT_MS → 502; a slow body after the headers is not cut by that timer', async () => {
  vi.useFakeTimers()
  try {
    fetchMock.mockImplementationOnce(
      (_, init) => new Promise((_, reject) => init!.signal!.addEventListener('abort', () => reject(init!.signal!.reason), { once: true })),
    )
    const stuck = getPdf(req(`/api/tickets/${TICKET_ID}/pdf`), params(TICKET_ID))
    await vi.advanceTimersByTimeAsync(BACKEND_TIMEOUT_MS - 1)
    expect(fetchMock.mock.calls[0]![1]!.signal!.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect((await stuck).status).toBe(502)

    let push: (c: Uint8Array) => void = () => {}
    let end = () => {}
    const body = new ReadableStream<Uint8Array>({ start: (c) => ((push = (b) => c.enqueue(b)), (end = () => c.close())) })
    fetchMock.mockResolvedValueOnce(new Response(body, { status: 200, headers: { 'content-type': 'application/pdf' } }))
    const res = await getPdf(req(`/api/tickets/${TICKET_ID}/pdf`), params(TICKET_ID))
    expect(res.status).toBe(200)
    const read = res.arrayBuffer()
    push(PDF.slice(0, 4))
    await vi.advanceTimersByTimeAsync(3 * BACKEND_TIMEOUT_MS)
    expect(fetchMock.mock.calls[1]![1]!.signal!.aborted).toBe(false)
    push(PDF.slice(4))
    end()
    expect(new Uint8Array(await read)).toEqual(PDF)
  } finally {
    vi.useRealTimers()
  }
})

it('Lot 4 fields: transfer_until, transfer_open against server_time, transferred', async () => {
  fetchMock.mockResolvedValue(
    json(200, {
      server_time: '2026-02-07T17:29:59Z',
      tickets: [
        ticket({ transfer_until: '2026-02-07T17:30:00Z', transferred: null }),
        ticket({ transfer_until: '2026-02-07T17:29:59Z' }),
        ticket({ transfer_until: null }),
        ticket({ status: 'CANCELLED', transfer_until: '2026-02-07T17:30:00Z', transferred: { to_label: 'Karim B.', at: '2026-02-03T10:00:00Z', to_phone: '+212600000000' } }),
      ],
    }),
  )
  const res = await getTickets(req('/api/tickets'))
  const text = await res.text()
  expect(text).not.toContain('+212600000000')
  const t = JSON.parse(text).tickets
  expect(t.map((x: { transfer_open: boolean }) => x.transfer_open)).toEqual([true, false, false, true])
  expect(t[0].transfer_until).toBe('2026-02-07T17:30:00Z')
  expect(t[2].transfer_until).toBeNull()
  expect(t[0].transferred).toBeNull()
  expect(t[3].transferred).toEqual({ to_label: 'Karim B.', at: '2026-02-03T10:00:00Z' })
})

const transferReq = (body: unknown, headers: Record<string, string> = {}, cookie = `a=1; __Host-jomhor_session=${TOKEN}`) =>
  new Request(`http://shop.test/api/tickets/${TICKET_ID}/transfer`, {
    method: 'POST',
    headers: { origin: 'http://shop.test', cookie, 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })

describe('POST /api/tickets/{id}/transfer', () => {
  beforeEach(() => vi.stubEnv('SHOP_ORIGIN', 'http://shop.test'))

  it('forwards {to_phone, name} (normalised) with the cookie as Bearer; answers the label only, no token or key', async () => {
    fetchMock.mockResolvedValue(
      json(201, { transfer_id: 'tr-1', to_label: 'Karim B.', transferred_at: '2026-02-05T17:00:00Z', ticket_id: 'NEW-TICKET-ID', k_t: K_T, token: 'SECRET-TOKEN' }),
    )
    const res = await transfer(transferReq({ to_phone: '06 12 34 56 78', name: ' Karim  Benali ', extra: 'x' }), params(TICKET_ID))

    expect(res.status).toBe(201)
    expect(res.headers.get('cache-control')).toBe('no-store')
    const text = await res.text()
    expect(JSON.parse(text)).toEqual({ to_label: 'Karim B.', transferred_at: '2026-02-05T17:00:00Z' })
    for (const s of [K_T, 'k_t', 'NEW-TICKET-ID', 'SECRET', TOKEN]) expect(text).not.toContain(s)
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe(`http://backend.test/v1/fan/tickets/${TICKET_ID}/transfer`)
    expect(init!.method).toBe('POST')
    expect(new Headers(init!.headers).get('authorization')).toBe(`Bearer ${TOKEN}`)
    expect(JSON.parse(String(init!.body))).toEqual({ to_phone: '+212612345678', name: 'Karim Benali' })
  })

  it.each([{}, { to_label: 'Karim B.' }, { transferred_at: '2026-02-05T17:00:00Z' }, { to_label: 1, transferred_at: '2026-02-05T17:00:00Z' }])(
    'a 2xx without a string to_label and transferred_at (%j) → 502',
    async (body) => {
      fetchMock.mockResolvedValue(json(201, body))
      const res = await transfer(transferReq({ to_phone: '0612345678', name: 'Karim Benali' }), params(TICKET_ID))
      expect(res.status).toBe(502)
      expect(await res.json()).toMatchObject({ code: 'INTERNAL_ERROR' })
    },
  )

  it.each<[string, unknown, string[]]>([
    ['a bad phone', { to_phone: '12', name: 'Karim Benali' }, ['to_phone']],
    ['a mixed-script name', { to_phone: '0612345678', name: 'Karim عقل' }, ['name']],
    ['no body fields', {}, ['to_phone', 'name']],
  ])('%s → 422 on that field, nothing sent', async (_, body, fields) => {
    const res = await transfer(transferReq(body), params(TICKET_ID))
    expect(res.status).toBe(422)
    const b = await res.json()
    expect(b.code).toBe('VALIDATION_FAILED')
    expect(Object.keys(b.details.fields)).toEqual(fields)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each([
    [409, { code: 'TRANSFER_CLOSED', message: '', details: { transfer_until: '2026-02-07T17:30:00Z' } }],
    [409, { code: 'TICKET_NOT_VALID', message: '', details: {} }],
    [422, { code: 'VALIDATION_FAILED', message: '', details: { fields: { to_phone: 'self' }, reason: 'SELF' } }],
    [404, { code: 'NOT_FOUND', message: '', details: {} }],
  ])('a backend %i keeps its status and 04 body', async (status, body) => {
    fetchMock.mockResolvedValue(json(status, body))
    const res = await transfer(transferReq({ to_phone: '0612345678', name: 'Karim Benali' }), params(TICKET_ID))
    expect(res.status).toBe(status)
    expect(await res.json()).toEqual(body)
  })

  it('cross-origin → 403, no session → 401, a bad id → 404; none reaches the backend', async () => {
    const body = { to_phone: '0612345678', name: 'Karim Benali' }
    expect((await transfer(transferReq(body, { origin: 'https://evil.com' }), params(TICKET_ID))).status).toBe(403)
    expect((await transfer(transferReq(body, {}, ''), params(TICKET_ID))).status).toBe(401)
    expect((await transfer(transferReq(body), params('../me'))).status).toBe(404)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
