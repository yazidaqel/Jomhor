// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { renderToString } from 'react-dom/server'
import { getRedirectStatusCodeFromError, getURLFromRedirectError } from 'next/dist/client/components/redirect'
import { isRedirectError } from 'next/dist/client/components/redirect-error'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { POST as createOrder } from '@/app/api/orders/route'
import { POST as joinQueue } from '@/app/api/queue/route'
import { getEvent } from '@/lib/events'
import { json, NOW, rawEvent, SECRETS } from '@/lib/events.fixture'
import { getDictionary } from '@/lib/i18n'
import { placeOrder } from '@/lib/order-client'
import { admissionKey, litDots, waitText } from '@/lib/queue'
import { CheckoutForm } from '../../../../orders/new/CheckoutForm'
import { EventView } from '../event-view'
import QueuePage from './page'
import { QueueRoom } from './QueueRoom'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const { router } = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn(), refresh: vi.fn() } }))
vi.mock('next/navigation', async (importOriginal) => ({ ...(await importOriginal<typeof import('next/navigation')>()), useRouter: () => router }))

let jar: Record<string, string> = {} // request cookies (lib/i18n/server.ts, lib/http.ts currentSession)
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: (name: string) => (jar[name] ? { name, value: jar[name] } : undefined) }),
  headers: async () => new Headers(),
}))

const fr = getDictionary('fr')
const ar = getDictionary('ar')
const SESSION = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsImF1ZCI6ImZhbiJ9.c2Vzc2lvbg'
const QT1 = 'eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJxdWV1ZSJ9.cXQx'
const QT2 = 'eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJxdWV1ZSIsInJuayI6NDB9.cXQy'
const ADM = 'eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJhZG1pc3Npb24ifQ.YWRt'
const W2 = '/atlas-fc/events/12-atlas-fc-vs-rapid-oued'
const W8 = `${W2}/queue`
const LOGIN_W8 = `/login?next=${encodeURIComponent(W8)}`
const API = 'https://api.test/v1'
const iso = (ms: number) => new Date(ms).toISOString()

// Every instant is relative to NOW (2026-02-01T12:00Z, in the past): nothing here falls due on the real calendar.
const room = (over: Record<string, unknown> = {}) => ({
  status: 'SCHEDULED',
  opens_at: iso(NOW + 5_000), // 13:00 in Africa/Casablanca
  eligibility: { verified_before: '2026-01-25T12:30:05Z', member_joined_before: null, min_events_attended: null },
  ...over,
})
/** 04 GET /fan/queue. */
const queue = (over: Record<string, unknown> = {}) => ({
  event_id: 12,
  status: 'WAITING',
  sale_starts_at: iso(NOW - 60_000),
  position: 40,
  in_room: 900,
  rate_per_min: 500,
  estimated_wait_s: 300,
  sold_out: false,
  admission: null,
  queue_token: QT1,
  poll_after_s: 5,
  server_time: iso(NOW),
  ...over,
})

const fetchMock = vi.fn<typeof fetch>()
let root: Root
let el: HTMLDivElement
let hidden = false
beforeEach(() => {
  jar = {}
  hidden = false
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval', 'performance'] })
  vi.setSystemTime(NOW)
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubEnv('API_PUBLIC_URL', API)
  vi.stubEnv('SHOP_ORIGIN', 'http://shop.test')
  vi.stubGlobal('fetch', fetchMock)
  vi.spyOn(console, 'error').mockImplementation(() => {})
  el = document.createElement('div')
  document.body.append(el)
  root = createRoot(el)
})
afterEach(() => {
  act(() => root.unmount())
  el.remove()
  sessionStorage.clear()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
  fetchMock.mockReset()
  router.push.mockReset()
  router.replace.mockReset()
  router.refresh.mockReset()
})

const advance = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms))
const text = () => el.textContent ?? ''
const cardButtons = () => [...el.querySelectorAll<HTMLButtonElement>('#zones button')]
async function detail(over: Record<string, unknown>) {
  fetchMock.mockResolvedValueOnce(json(200, rawEvent(over)))
  return (await getEvent(12))!
}
async function thrown(p: Promise<unknown>) {
  try {
    await p
  } catch (e) {
    return e
  }
  throw new Error('expected a throw')
}
const sent = (n: number) => {
  const [url, init] = fetchMock.mock.calls[n]!
  return { url: String(url), init: init ?? {}, headers: new Headers(init?.headers) }
}

describe('W2 room panel', () => {
  it('SCHEDULED: "File d\'attente dès {opens_at}", the rule, cards with prices but disabled "Accès par la file", sign-in link to W8', async () => {
    const e = await detail({ on_sale: false, sale_starts_at: iso(NOW + 1_805_000), room: room() })
    el.innerHTML = renderToString(EventView({ e, now: NOW, d: fr }))
    const panel = el.querySelector('[data-room]')!
    expect(panel.getAttribute('data-room')).toBe('scheduled')
    expect(panel.textContent).toContain("File d'attente dès 13:00")
    expect(panel.textContent).toContain('Réservée aux fans vérifiés : compte vérifié avant le 25 janv.')
    expect(panel.querySelector(`a[href="${LOGIN_W8}"]`)!.textContent).toBe(fr.room.loginNow)
    for (const b of cardButtons()) expect(b.disabled).toBe(true)
    const virage = cardButtons().find((b) => b.textContent!.includes('Virage'))!
    expect(virage.textContent).toContain('50\u00a0DH')
    expect(virage.textContent).toContain(fr.room.viaQueue)
    expect(el.querySelector('aside[aria-labelledby="order-title"]')).toBeNull() // the summary became the room panel
  })

  it('flips to "Rejoindre la file" at opens_at on the server clock, without a reload (device clock 1 h behind)', async () => {
    const e = await detail({ on_sale: false, sale_starts_at: iso(NOW + 1_805_000), room: room() })
    vi.setSystemTime(NOW - 3_600_000) // the device clock is wrong; the panel must not care
    await act(async () => root.render(EventView({ e, now: NOW, d: fr, signedIn: true })))
    const cta = () => el.querySelector('[data-room] .md\\:flex')!
    expect(cta().tagName).toBe('BUTTON')
    expect(cta().textContent).toBe("File d'attente dès 13:00")
    expect(text()).not.toContain(fr.room.loginNow) // signed in
    await advance(4_000)
    expect(cta().tagName).toBe('BUTTON')
    await advance(1_000)
    expect(cta().tagName).toBe('A')
    expect(cta().textContent).toBe(fr.room.join)
    expect(cta().getAttribute('href')).toBe(W8)
    for (const b of cardButtons()) expect(b.disabled).toBe(true) // still gated until admitted
  })

  it('PRE_QUEUE signed out: "Rejoindre la file" goes through the login, back to W8', async () => {
    const e = await detail({ on_sale: false, sale_starts_at: iso(NOW + 600_000), room: room({ status: 'PRE_QUEUE', opens_at: iso(NOW - 60_000) }) })
    el.innerHTML = renderToString(EventView({ e, now: NOW, d: fr }))
    expect(el.querySelector('[data-room]')!.getAttribute('data-room')).toBe('open')
    expect([...el.querySelectorAll('[data-room] a')].map((a) => a.getAttribute('href'))).toEqual([LOGIN_W8, LOGIN_W8]) // desktop + phone bar
  })

  it('admitted in this tab: zones enabled, the turn bar counts down on server time; an expired turn gates again', async () => {
    const e = await detail({ room: room({ status: 'OPEN', opens_at: iso(NOW - 1_800_000) }) })
    sessionStorage.setItem(admissionKey(12), JSON.stringify({ token: ADM, expires_at: iso(NOW + 600_000) }))
    await act(async () => root.render(EventView({ e, now: NOW, d: fr, signedIn: true })))
    expect(el.querySelector('[data-room]')).toBeNull()
    const virage = cardButtons().find((b) => b.textContent!.includes('Virage'))!
    expect(virage.disabled).toBe(false)
    expect(text()).not.toContain(fr.room.viaQueue)
    expect(el.querySelector('[data-turn]')!.textContent).toBe('Ton tour · 10:00')
    await advance(1_000)
    expect(el.querySelector('[data-turn]')!.textContent).toBe('Ton tour · 09:59')
    await act(async () => virage.click())
    expect(el.querySelector('a[href^="/orders/new?"]')).not.toBeNull()
    await advance(600_000)
    expect(el.querySelector('[data-room]')).not.toBeNull()
    for (const b of cardButtons()) expect(b.disabled).toBe(true)
  })
})

describe('W8 page (server)', () => {
  const page = (slug = '12-atlas-fc-vs-rapid-oued') => QueuePage({ params: Promise.resolve({ club: 'atlas-fc', slug }) })
  const withRoom = () => fetchMock.mockImplementation(async () => json(200, rawEvent({ room: room({ status: 'OPEN', opens_at: iso(NOW - 60_000) }) })))

  it('signed out → /login?next=…/queue', async () => {
    withRoom()
    const e = await thrown(page())
    expect(isRedirectError(e)).toBe(true)
    expect(getURLFromRedirectError(e as never)).toBe(LOGIN_W8)
  })

  it('no live room → W2; another slug spelling → 308 to the canonical W8', async () => {
    jar['__Host-jomhor_session'] = SESSION
    fetchMock.mockImplementation(async () => json(200, rawEvent({ room: null })))
    expect(getURLFromRedirectError((await thrown(page())) as never)).toBe(W2)
    withRoom()
    const e = await thrown(page('12'))
    expect(getRedirectStatusCodeFromError(e as never)).toBe(308)
    expect(getURLFromRedirectError(e as never)).toBe(W8)
  })

  it('no session JWT, queue token or key in the HTML or the QueueRoom props (RSC payload)', async () => {
    jar['__Host-jomhor_session'] = SESSION
    withRoom()
    const tree = (await page()) as React.ReactElement<{ children: React.ReactNode[] }>
    const h = renderToString(tree)
    expect(h).toContain(fr.room.joining)
    for (const s of [SESSION, ...SECRETS]) expect(h).not.toContain(s)
    const section = tree.props.children[0] as React.ReactElement<{ children: React.ReactNode[] }>
    const props = (section.props.children as React.ReactElement[]).find((c) => c?.type === QueueRoom)!.props as Record<string, unknown>
    const payload = JSON.stringify(props)
    expect(props).toMatchObject({ eventId: 12, apiUrl: API, eventHref: W2, loginHref: LOGIN_W8, now: NOW, remaining: 2330, verified: true })
    for (const s of [SESSION, 'eyJ', ...SECRETS]) expect(payload).not.toContain(s)
  })

  it('renders in AR (locale cookie)', async () => {
    jar = { '__Host-jomhor_session': SESSION, locale: 'ar' }
    withRoom()
    const h = renderToString((await page()) as React.ReactElement)
    expect(h).toContain(ar.room.title)
    expect(h).toContain(ar.room.joining)
    expect(h).not.toContain(fr.room.title)
  })
})

describe('join handler POST /api/queue', () => {
  const req = (headers: Record<string, string> = {}, body: unknown = { event_id: 12 }) =>
    new Request('http://shop.test/api/queue', {
      method: 'POST',
      headers: { origin: 'http://shop.test', cookie: `__Host-jomhor_session=${SESSION}`, 'content-type': 'application/json', 'x-forwarded-for': '203.0.113.7', ...headers },
      body: JSON.stringify(body),
    })

  it('cross-origin → 403, no backend call', async () => {
    const res = await joinQueue(req({ origin: 'http://evil.test' }))
    expect(res.status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('forwards Bearer, device_id from __Host-jdid and the client IP; answers the queue shape only', async () => {
    fetchMock.mockResolvedValueOnce(json(201, { ...queue(), user_id: 'SECRET-USER', draw_key: 'SECRET-DRAW' }))
    const res = await joinQueue(req({ cookie: `__Host-jomhor_session=${SESSION}; __Host-jdid=AbCdEfGhIjKlMnOpQrSt_-12` }))
    expect(res.status).toBe(200)
    expect(res.headers.get('cache-control')).toBe('no-store')
    expect(res.headers.get('set-cookie')).toBeNull() // already has one
    expect(await res.json()).toEqual(queue())
    const s = sent(0)
    expect(s.url).toBe('http://backend.test/v1/fan/queue')
    expect(s.init.method).toBe('POST')
    expect(s.headers.get('authorization')).toBe(`Bearer ${SESSION}`)
    expect(s.headers.get('x-forwarded-for')).toBe('203.0.113.7')
    expect(JSON.parse(s.init.body as string)).toEqual({ event_id: 12, device_id: 'AbCdEfGhIjKlMnOpQrSt_-12' })
  })

  it('no (or a malformed) __Host-jdid: a random one is sent and set httpOnly, Secure, SameSite=Lax, 1 year; a plain jdid is ignored', async () => {
    fetchMock.mockImplementation(async () => json(201, queue()))
    const res = await joinQueue(req({ cookie: `__Host-jomhor_session=${SESSION}; __Host-jdid=short; jdid=PlantedBySiblingDomain_1` }))
    const id = JSON.parse(sent(0).init.body as string).device_id as string
    expect(id).toMatch(/^[\w-]{32}$/)
    expect(res.headers.get('set-cookie')).toBe(`__Host-jdid=${id}; Path=/; Max-Age=31536000; HttpOnly; Secure; SameSite=Lax`)
  })

  it('signed out → 401; backend errors pass through in the 04 shape (and the device cookie is still set)', async () => {
    expect((await joinQueue(req({ cookie: '' }))).status).toBe(401)
    const err = { code: 'QUEUE_RATE_LIMITED', message: '', details: { reason: 'IP', retry_after_s: 60 } }
    fetchMock.mockResolvedValueOnce(json(429, err))
    const res = await joinQueue(req())
    expect(res.status).toBe(429)
    expect(await res.json()).toEqual(err)
    expect(res.headers.get('set-cookie')).toMatch(/^__Host-jdid=/)
  })
})

describe('W8 room (client)', () => {
  const show = (over: Partial<Parameters<typeof QueueRoom>[0]> = {}, d = fr) =>
    act(async () =>
      root.render(
        <QueueRoom
          eventId={12}
          eventHref={W2}
          loginHref={LOGIN_W8}
          apiUrl={API}
          now={NOW}
          timezone="Africa/Casablanca"
          limit={4}
          remaining={2330}
          verified
          copy={{ room: d.room, event: d.event, errors: d.errors, meta: d.meta }}
          {...over}
        />,
      ),
    )
  const polls = () => fetchMock.mock.calls.filter(([u]) => String(u).startsWith(API))

  it('joins, then polls the API directly with X-Queue-Token and no credentials, at poll_after_s, paused while hidden, until ADMITTED', async () => {
    fetchMock
      .mockResolvedValueOnce(json(201, queue({ status: 'WAITING', position: null, poll_after_s: 2 }))) // join
      .mockResolvedValueOnce(json(200, queue({ position: 40, queue_token: QT2, poll_after_s: 5, server_time: iso(NOW + 2_000) })))
      .mockResolvedValueOnce(json(200, queue({ position: 10, queue_token: QT2, poll_after_s: 15, estimated_wait_s: 30 })))
      .mockResolvedValueOnce(json(200, queue({ status: 'ADMITTED', position: null, queue_token: QT2, poll_after_s: 0, server_time: iso(NOW + 30_000), admission: { token: ADM, expires_at: iso(NOW + 630_000) } })))
    await show()
    expect(sent(0).url).toBe('/api/queue')
    expect(JSON.parse(sent(0).init.body as string)).toEqual({ event_id: 12 })
    expect(text()).toContain(fr.room.rankingTitle)
    expect(text()).toContain('900 fans dans la file')

    await advance(1_999)
    expect(polls()).toHaveLength(0)
    await advance(1)
    expect(polls()).toHaveLength(1)
    const p = sent(1)
    expect(p.url).toBe(`${API}/fan/queue`)
    expect(p.headers.get('x-queue-token')).toBe(QT1)
    expect(p.headers.get('authorization')).toBeNull()
    expect(p.init.credentials).toBe('omit')
    expect(p.init.cache).toBe('no-store')
    expect(text()).toContain('N° 40')
    expect(text()).toContain('39 fans devant toi')
    expect(text()).toContain('Attente estimée · environ 5 min')
    expect(text()).toContain('Il reste 2\u202f330 places')
    expect(text()).toContain(fr.room.verified)
    expect(el.querySelector('[role="progressbar"]')!.getAttribute('aria-valuenow')).toBe('0') // first position seen

    hidden = true // the tab goes to the background: no poll at 5 s
    await advance(20_000)
    expect(polls()).toHaveLength(1)
    hidden = false
    await act(async () => document.dispatchEvent(new Event('visibilitychange')))
    expect(polls()).toHaveLength(2)
    expect(sent(2).headers.get('x-queue-token')).toBe(QT2) // the refreshed token
    expect(text()).toContain('N° 10')
    expect(text()).toContain("Attente estimée · moins d'une minute")
    expect(el.querySelector('[role="progressbar"]')!.getAttribute('aria-valuenow')).toBe('9') // ⌊12 × (1 − 10/40)⌋

    await advance(14_999)
    expect(polls()).toHaveLength(2)
    await advance(1)
    expect(polls()).toHaveLength(3)
    expect(text()).toContain("C'est ton tour")
    expect(text()).toContain('Tu as 10:00 pour réserver')
    expect(el.querySelector(`a[href="${W2}#zones"]`)!.textContent).toBe(fr.room.choose)
    expect(el.querySelector('[data-dot="turn"]')).not.toBeNull()
    expect(JSON.parse(sessionStorage.getItem(admissionKey(12))!)).toEqual({ token: ADM, expires_at: iso(NOW + 630_000) })
    await advance(1_000)
    expect(text()).toContain('Tu as 09:59 pour réserver')
    await advance(60_000)
    expect(polls()).toHaveLength(3) // stopped
  })

  it('the turn ends on server time: "Ton tour est passé" → "Reprendre une place" joins again', async () => {
    fetchMock
      .mockResolvedValueOnce(json(200, queue({ status: 'ADMITTED', position: null, poll_after_s: 0, admission: { token: ADM, expires_at: iso(NOW + 3_000) } })))
      .mockResolvedValueOnce(json(201, queue({ position: 900 })))
    await show()
    expect(text()).toContain('Tu as 00:03 pour réserver')
    await advance(3_000)
    expect(text()).toContain(fr.room.expiredTitle)
    await act(async () => [...el.querySelectorAll('button')].find((b) => b.textContent === fr.room.rejoin)!.click())
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(sent(1).url).toBe('/api/queue')
    expect(text()).toContain('N° 900')
  })

  it('PRE_QUEUE: the draw time, a countdown on server time, the plain sentence, the crowd count, the running light', async () => {
    fetchMock.mockResolvedValueOnce(json(201, queue({ status: 'PRE_QUEUE', position: null, sale_starts_at: iso(NOW + 5 * 60_000 + 5_000), poll_after_s: 30 })))
    await show({ verified: false })
    expect(text()).toContain('Tirage au sort à 13:05')
    expect(el.querySelector('[role="timer"]')!.textContent).toBe('00Heures05Min05Sec')
    expect(text()).toContain('Tous ceux qui sont là avant 13:05 ont la même chance')
    expect(text()).toContain('900 fans dans la file')
    expect(el.querySelector('[role="progressbar"]')!.hasAttribute('aria-valuenow')).toBe(false)
    expect(text()).not.toContain(fr.room.verified)
    await advance(1_000)
    expect(el.querySelector('[role="timer"]')!.textContent).toBe('00Heures05Min04Sec')
  })

  it('QUEUE_RATE_LIMITED on join: the copy with a countdown, one automatic retry, then a button', async () => {
    const limited = () => json(429, { code: 'QUEUE_RATE_LIMITED', message: '', details: { reason: 'IP', retry_after_s: 3 } })
    fetchMock.mockResolvedValueOnce(limited()).mockResolvedValueOnce(limited())
    await show()
    expect(text()).toContain('Beaucoup de fans sur ce réseau · nouvel essai dans 3 s')
    await advance(1_000)
    expect(text()).toContain('nouvel essai dans 2 s')
    await advance(2_000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(text()).toContain(fr.room.rateLimitedAgain)
    await advance(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(2) // one automatic retry only
    expect([...el.querySelectorAll('button')].map((b) => b.textContent)).toEqual([fr.room.retry])
  })

  it('QUEUE_RATE_LIMITED on a poll: silent, the next poll waits retry_after_s', async () => {
    fetchMock
      .mockResolvedValueOnce(json(201, queue({ poll_after_s: 15 })))
      .mockResolvedValueOnce(json(429, { code: 'QUEUE_RATE_LIMITED', message: '', details: { reason: 'POLL', retry_after_s: 1 } }))
      .mockResolvedValueOnce(json(200, queue({ position: 39 })))
    await show()
    await advance(15_000)
    expect(polls()).toHaveLength(1)
    const before = text()
    expect(before).toContain('N° 40')
    expect(before).not.toContain(fr.room.offline)
    await advance(999)
    expect(polls()).toHaveLength(1)
    await advance(1)
    expect(polls()).toHaveLength(2)
    expect(text()).toContain('N° 39')
  })

  it('a failed poll keeps the last state with "Hors ligne · ta place est gardée"', async () => {
    fetchMock.mockResolvedValueOnce(json(201, queue({ poll_after_s: 5 }))).mockRejectedValueOnce(new TypeError('offline'))
    await show()
    await advance(5_000)
    expect(text()).toContain('N° 40')
    expect(text()).toContain(fr.room.offline)
  })

  it.each<[string, Record<string, unknown>, string]>([
    ['NOT_ELIGIBLE ACCOUNT_TOO_NEW', { code: 'NOT_ELIGIBLE', details: { reason: 'ACCOUNT_TOO_NEW', verified_before: '2026-01-25T12:30:05Z' } }, 'Vente réservée aux fans vérifiésCompte vérifié après le 25 janv.'],
    ['NOT_ENOUGH_EVENTS', { code: 'NOT_ELIGIBLE', details: { reason: 'NOT_ENOUGH_EVENTS', min_events_attended: 3, attended: 1 } }, 'Il faut 3 matchs au stade · tu en as 1'],
    ['QUEUE_DEVICE_LIMIT', { code: 'QUEUE_DEVICE_LIMIT', details: { limit: 3 } }, fr.room.QUEUE_DEVICE_LIMIT],
    ['QUEUE_NOT_OPEN CLOSED', { code: 'QUEUE_NOT_OPEN', details: { reason: 'CLOSED' } }, fr.room.closed],
  ])('%s: says why, no retry', async (_, body, copy) => {
    fetchMock.mockResolvedValueOnce(json(409, { message: '', ...body }))
    await show()
    expect(text()).toContain(copy)
    expect(el.querySelector('button')).toBeNull()
  })

  it('QUEUE_NOT_OPEN NOT_STARTED: waits for opens_at on server time, then joins; ENDED → W2; 401 → login', async () => {
    fetchMock
      .mockResolvedValueOnce(json(409, { code: 'QUEUE_NOT_OPEN', message: '', details: { reason: 'NOT_STARTED', opens_at: iso(NOW + 60_000) } }))
      .mockResolvedValueOnce(json(409, { code: 'QUEUE_NOT_OPEN', message: '', details: { reason: 'ENDED' } }))
    vi.spyOn(Math, 'random').mockReturnValue(0)
    await show()
    expect(text()).toContain("File d'attente dès 13:01")
    await advance(59_999)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await advance(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(router.replace).toHaveBeenCalledWith(W2)

    act(() => root.unmount())
    root = createRoot(el)
    fetchMock.mockResolvedValueOnce(json(401, { code: 'UNAUTHORIZED', message: '', details: {} }))
    await show()
    expect(router.replace).toHaveBeenLastCalledWith(LOGIN_W8)
  })

  it('a poll refused with 401 re-joins once; a second 401 with no good poll in between stops on "Réessayer"', async () => {
    const refused = () => json(401, { code: 'UNAUTHORIZED', message: '', details: {} })
    fetchMock
      .mockResolvedValueOnce(json(201, queue())) // join 1
      .mockResolvedValueOnce(refused())
      .mockResolvedValueOnce(json(200, queue({ queue_token: QT2 }))) // join 2: a fresh token
      .mockResolvedValueOnce(json(200, queue({ position: 39, queue_token: QT2 })))
      .mockResolvedValueOnce(refused()) // after a good poll: one more re-join
      .mockResolvedValueOnce(json(200, queue({ position: 39 }))) // join 3
      .mockResolvedValueOnce(refused()) // 401 again, no good poll in between: stop
    await show()
    const joins = () => fetchMock.mock.calls.filter(([u]) => u === '/api/queue')
    await advance(5_000)
    expect(joins()).toHaveLength(2)
    await advance(5_000)
    expect(sent(3).headers.get('x-queue-token')).toBe(QT2)
    expect(text()).toContain('N° 39')
    await advance(5_000)
    expect(joins()).toHaveLength(3)
    await advance(5_000)
    expect(text()).toContain(fr.errors.generic)
    expect([...el.querySelectorAll('button')].map((b) => b.textContent)).toEqual([fr.room.retry])
    await advance(120_000)
    expect(fetchMock).toHaveBeenCalledTimes(7) // no loop
    await act(async () => el.querySelector('button')!.click())
    expect(joins()).toHaveLength(4)
  })

  it('"Il reste {n} places" shows for 5 min after the load on server time, then hides; sold_out still shows; never a router.refresh', async () => {
    vi.setSystemTime(NOW + 3_600_000) // the device clock is an hour off: the window runs on the server clock
    const server = () => iso(Date.now() - 3_600_000) // each answer's server_time moves with the (fake) time
    fetchMock.mockImplementation(async (u) => json(u === '/api/queue' ? 201 : 200, queue({ poll_after_s: 15, server_time: server() })))
    await show()
    expect(text()).toContain('Il reste 2\u202f330 places')
    await advance(299_000)
    expect(text()).toContain('Il reste 2\u202f330 places')
    await advance(1_000)
    expect(text()).not.toContain('Il reste')
    expect(text()).toContain(fr.room.keepPlace) // the other rows stay
    fetchMock.mockImplementation(async () => json(200, queue({ poll_after_s: 15, sold_out: true, server_time: server() })))
    await advance(15_000)
    expect(text()).toContain(fr.room.soldOut)
    await advance(3_600_000)
    expect(router.refresh).not.toHaveBeenCalled()
  })

  it('renders in AR: Darija copy, Western digits', async () => {
    fetchMock.mockResolvedValueOnce(json(201, queue()))
    await show({}, ar)
    expect(text()).toContain('رقم 40')
    expect(text()).toContain('39 فان قدامك')
    expect(text()).toContain(ar.room.keepPlace)
    expect(text()).not.toMatch(/[٠-٩]/)
  })
})

describe('admission → orders', () => {
  it('/api/orders forwards X-Admission-Token (JWT-shaped only) to POST /fan/orders', async () => {
    const order = { id: 'o1', status: 'RESERVED', reserved_until: iso(NOW + 480_000), total_mad: 5000, items: [] }
    fetchMock.mockImplementation(async () => json(201, order))
    const body = JSON.stringify({ event_id: 12, items: [{ price_category_id: 'pc-virage', qty: 1, holders: [{ name: 'Yazid Aqel' }] }] })
    const headers = { origin: 'http://shop.test', cookie: `__Host-jomhor_session=${SESSION}`, 'content-type': 'application/json' }
    await createOrder(new Request('http://shop.test/api/orders', { method: 'POST', headers: { ...headers, 'x-admission-token': ADM }, body }))
    expect(sent(0).headers.get('x-admission-token')).toBe(ADM)
    await createOrder(new Request('http://shop.test/api/orders', { method: 'POST', headers: { ...headers, 'x-admission-token': 'not a token' }, body }))
    expect(sent(1).headers.get('x-admission-token')).toBeNull()
  })

  it('placeOrder sends the admission to /api/orders as X-Admission-Token', async () => {
    fetchMock.mockResolvedValueOnce(json(201, { id: 'o1', reserved_until: null })).mockResolvedValueOnce(json(200, { redirect_url: 'x' }))
    await placeOrder({ event_id: 12 }, 'CARD', () => {}, new AbortController().signal, { admission: ADM })
    expect(sent(0).url).toBe('/api/orders')
    expect(sent(0).headers.get('x-admission-token')).toBe(ADM)
  })

  it('W3 sends the stored turn; ADMISSION_REQUIRED → back to W8, the stale turn dropped', async () => {
    sessionStorage.setItem(admissionKey(12), JSON.stringify({ token: ADM, expires_at: iso(NOW + 600_000) }))
    fetchMock.mockResolvedValueOnce(json(409, { code: 'ADMISSION_REQUIRED', message: '', details: { reason: 'EXPIRED' } }))
    await act(async () =>
      root.render(
        <CheckoutForm
          eventId={12}
          priceCategoryId="pc-virage"
          qty={1}
          displayName="Yazid Aqel"
          unitMad={5000}
          methods={['CARD']}
          cashLate={false}
          cashDeadline=""
          now={NOW}
          timezone="Africa/Casablanca"
          queueHref={W8}
          copy={{ checkout: fr.checkout, errors: fr.errors, meta: fr.meta }}
        />,
      ),
    )
    await act(async () => el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click())
    expect(sent(0).url).toBe('/api/orders')
    expect(sent(0).headers.get('x-admission-token')).toBe(ADM)
    expect(router.push).toHaveBeenCalledWith(W8)
    expect(sessionStorage.getItem(admissionKey(12))).toBeNull()
  })
})

it('Q165 wait copy and the dots scale', () => {
  expect([59, 60, 61, 3600, 3601].map((s) => waitText(fr.room, s))).toEqual(["moins d'une minute", 'environ 1 min', 'environ 2 min', 'environ 60 min', "plus d'une heure"])
  expect([litDots(40, 40), litDots(10, 40), litDots(1, 40), litDots(900, 40)]).toEqual([0, 9, 11, 0])
})
