// @vitest-environment jsdom
import { act, StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { renderToString } from 'react-dom/server'
import { getRedirectStatusCodeFromError, getURLFromRedirectError } from 'next/dist/client/components/redirect'
import { isRedirectError } from 'next/dist/client/components/redirect-error'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DELETE, GET, PUT } from '@/app/api/waitlist/[eventId]/route'
import { getEvent } from '@/lib/events'
import { json, NOW, rawEvent, SECRETS } from '@/lib/events.fixture'
import { getDictionary } from '@/lib/i18n'
import { markJoin } from '@/lib/waitlist'
import OldEventPage from '../../../events/[slug]/page'
import { EventView } from './event-view'
import EventPage from './page'

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
const W2 = '/atlas-fc/events/12-atlas-fc-vs-rapid-oued'
const LOGIN_JOIN = `/login?next=${encodeURIComponent(`${W2}?waitlist=1`)}`
const API = '/api/waitlist/12'
const SOLD_OUT = {
  sold_out: true,
  from_price_mad: null,
  zones: [{ id: 3, code: 'NORD', name: 'Tribune Nord', seated: false, gate_codes: [], prices: [{ price_category_id: 'pc-n', name: 'Plein', price_mad: 5000, remaining: 0 }] }],
}
const WAITING = { entry: { joined_at: '2026-02-01T11:00:00Z', notified_at: null } }
const NOTIFIED = { entry: { joined_at: '2026-02-01T11:00:00Z', notified_at: '2026-02-01T11:30:00Z' } }
const closed = (reason: string) => ({ code: 'WAITLIST_CLOSED', message: '', details: { reason } })

const fetchMock = vi.fn<typeof fetch>()
let root: Root
let el: HTMLDivElement
beforeEach(() => {
  jar = {}
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval'] })
  vi.setSystemTime(NOW)
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
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
  history.replaceState(null, '', '/')
  sessionStorage.clear()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
  fetchMock.mockReset()
  router.refresh.mockReset()
})

async function detail(over: Record<string, unknown> = {}) {
  fetchMock.mockResolvedValueOnce(json(200, rawEvent({ ...SOLD_OUT, ...over })))
  return (await getEvent(12))!
}
const panel = () => el.querySelector<HTMLElement>('[data-waitlist]')
/** The phone-width sticky bar's control (md:hidden bar; the inline control is `hidden md:flex`). */
const bar = () => el.querySelector<HTMLElement>('[data-waitlist-bar]')!.firstElementChild as HTMLElement
const calls = () => fetchMock.mock.calls.map(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}`)
const click = (node: Element | null | undefined) => act(async () => (node as HTMLElement).click())
const byText = (t: string) => [...el.querySelectorAll<HTMLElement>('button, a, summary')].find((n) => n.textContent === t)
/** W2 rendered in the browser (signed in), the waitlist read answered by `read`. */
async function mount(read: Response, over: Record<string, unknown> = {}) {
  const e = await detail(over)
  fetchMock.mockReset()
  fetchMock.mockResolvedValueOnce(read)
  await act(async () => root.render(EventView({ e, now: NOW, d: fr, signedIn: true })))
}

describe('W2 sold out (server)', () => {
  it('signed out: "Liste d\'attente" → /login?next=W2?waitlist=1, the channels line, no order summary', async () => {
    const e = await detail()
    el.innerHTML = renderToString(EventView({ e, now: NOW, d: fr }))
    expect(panel()!.getAttribute('data-waitlist')).toBe('signed-out')
    expect(panel()!.querySelector('h2')!.textContent).toBe('Complet')
    const cta = panel()!.querySelector('a')!
    expect(cta.textContent).toBe("Liste d'attente")
    expect(cta.getAttribute('href')).toBe(LOGIN_JOIN)
    expect(cta.className).toContain('hidden md:flex')
    expect(el.querySelector('[data-waitlist-bar]')!.className).toContain('md:hidden')
    expect(bar().tagName).toBe('A')
    expect(bar().getAttribute('href')).toBe(LOGIN_JOIN)
    expect(bar().textContent).toBe("Liste d'attente")
    expect(panel()!.textContent).toContain('On te prévient par notification et WhatsApp')
    expect(el.querySelector('#order-title')).toBeNull()
    for (const b of el.querySelectorAll<HTMLButtonElement>('#zones button')) expect(b.disabled).toBe(true)
  })

  it.each<[string, Record<string, unknown>]>([
    ['places left', { sold_out: false, zones: rawEvent().zones }],
    ['sales closed', { on_sale: false, sales_close_at: new Date(NOW - 1000).toISOString() }],
  ])('%s → no waitlist', async (_, over) => {
    const e = await detail(over)
    el.innerHTML = renderToString(EventView({ e, now: NOW, d: fr }))
    expect(panel()).toBeNull()
    expect(el.querySelector('#order-title')).not.toBeNull()
  })

  it('a live room → the room panel, not the waitlist (the room is the queue, Q179)', async () => {
    const room = { phase: 1, label: null, status: 'OPEN', opens_at: new Date(NOW - 60_000).toISOString(), sale_starts_at: new Date(NOW - 60_000).toISOString(), next: null, eligibility: null }
    const e = await detail({ room })
    el.innerHTML = renderToString(EventView({ e, now: NOW, d: fr }))
    expect(panel()).toBeNull()
    expect(el.querySelector('[data-room]')).not.toBeNull()
  })

  it('the page reads the session for a sold-out event; no session JWT or key in the HTML; renders in AR', async () => {
    jar = { '__Host-jomhor_session': SESSION, locale: 'ar' }
    fetchMock.mockImplementation(async () => json(200, rawEvent(SOLD_OUT)))
    const h = renderToString((await EventPage({ params: Promise.resolve({ club: 'atlas-fc', slug: '12-atlas-fc-vs-rapid-oued' }) })) as React.ReactElement)
    expect(h).toContain('data-waitlist="open"')
    expect(h).toContain(ar.waitlist.join)
    expect(h).toContain(ar.waitlist.channels)
    expect(h).not.toContain(fr.waitlist.channels)
    for (const s of [SESSION, ...SECRETS]) expect(h).not.toContain(s)
    expect(fetchMock.mock.calls.map(([u]) => String(u))).toEqual(['http://backend.test/v1/fan/events/12']) // the entry is read in the browser
  })

  it('/events/{id} (the WhatsApp link) → 308 to the canonical W2', async () => {
    fetchMock.mockResolvedValue(json(200, rawEvent(SOLD_OUT)))
    const e = await OldEventPage({ params: Promise.resolve({ slug: '12' }) }).catch((x: unknown) => x)
    expect(isRedirectError(e)).toBe(true)
    expect(getRedirectStatusCodeFromError(e as never)).toBe(308)
    expect(getURLFromRedirectError(e as never)).toBe(W2)
  })
})

describe('W2 waitlist panel (browser)', () => {
  it('reads once on open; join → "Sur la liste d\'attente" (focused) → sheet → "Quitter la liste" → join again; never polled', async () => {
    await mount(json(200, { entry: null }))
    expect(calls()).toEqual([`GET ${API}`])
    expect(panel()!.getAttribute('data-waitlist')).toBe('open')
    expect([bar().tagName, bar().textContent]).toEqual(['BUTTON', "Liste d'attente"])
    fetchMock.mockResolvedValueOnce(json(201, WAITING))
    await click(byText("Liste d'attente"))
    expect(calls()[1]).toBe(`PUT ${API}`)
    expect(panel()!.getAttribute('data-waitlist')).toBe('joined')
    const summary = el.querySelector('summary')!
    expect(summary.textContent).toBe("Sur la liste d'attente")
    expect(summary.querySelector('svg')).not.toBeNull() // the check icon
    expect(document.activeElement).toBe(summary)
    expect(summary.className).toContain('hidden md:flex')
    // Phone bar: the outlined "Sur la liste d'attente" opens the inline sheet.
    expect([bar().tagName, bar().textContent, bar().getAttribute('aria-expanded')]).toEqual(['BUTTON', "Sur la liste d'attente", 'false'])
    expect(bar().getAttribute('aria-controls')).toBe(el.querySelector('details')!.id)
    await click(bar())
    expect(el.querySelector('details')!.open).toBe(true)
    expect(bar().getAttribute('aria-expanded')).toBe('true')
    expect(el.querySelector('details')!.textContent).toContain('On te prévient dès que des places se libèrent. Premier arrivé, premier servi.')
    fetchMock.mockResolvedValueOnce(json(204))
    await click(byText('Quitter la liste'))
    expect(calls()[2]).toBe(`DELETE ${API}`)
    expect(panel()!.getAttribute('data-waitlist')).toBe('open')
    expect(document.activeElement).toBe(byText("Liste d'attente"))
    await act(() => vi.advanceTimersByTimeAsync(600_000))
    expect(calls()).toHaveLength(3)
  })

  it('already on the list → the outlined state; a notified entry waits no more → "Liste d\'attente"', async () => {
    await mount(json(200, WAITING))
    expect(panel()!.getAttribute('data-waitlist')).toBe('joined')
    act(() => root.unmount())
    root = createRoot(el)
    await mount(json(200, NOTIFIED))
    expect(panel()!.getAttribute('data-waitlist')).toBe('open')
  })

  it('a failed read stays quiet: the CTA still joins', async () => {
    await mount(json(502, { code: 'INTERNAL_ERROR', message: '', details: {} }))
    expect(panel()!.textContent).not.toContain(fr.errors.generic)
    expect(byText("Liste d'attente")).toBeDefined()
  })

  it.each<[string, string]>([
    ['NOT_SOLD_OUT', 'Des places sont de nouveau disponibles : choisis ta zone.'],
    ['ROOM', "La vente passe par la file d'attente."],
    ['CLOSED', 'La vente est terminée pour cet événement.'],
  ])('WAITLIST_CLOSED %s: its copy, and the event is read again', async (reason, copy) => {
    await mount(json(200, { entry: null }))
    fetchMock.mockResolvedValueOnce(json(409, closed(reason)))
    await click(byText("Liste d'attente"))
    expect(panel()!.querySelector('[aria-live]')!.textContent).toBe(copy)
    expect(router.refresh).toHaveBeenCalledTimes(1)
  })

  it('session over (401) → the login CTA and its copy; offline → the network copy', async () => {
    await mount(json(200, { entry: null }))
    fetchMock.mockResolvedValueOnce(json(401, { code: 'UNAUTHORIZED', message: '', details: {} }))
    await click(byText("Liste d'attente"))
    expect(panel()!.getAttribute('data-waitlist')).toBe('signed-out')
    expect(byText("Liste d'attente")!.getAttribute('href')).toBe(LOGIN_JOIN)
    expect(panel()!.textContent).toContain(fr.errors.UNAUTHORIZED)
    act(() => root.unmount())
    root = createRoot(el)
    await mount(json(200, { entry: null }))
    fetchMock.mockRejectedValueOnce(new TypeError('offline'))
    await click(byText("Liste d'attente"))
    expect(panel()!.textContent).toContain(fr.errors.NETWORK)
    expect(router.refresh).not.toHaveBeenCalled()
  })

  it('the signed-out CTA sets the one-shot marker; back with ?waitlist=1: joins at once (PUT, no read), drops the param and the marker', async () => {
    const e = await detail()
    await act(async () => root.render(EventView({ e, now: NOW, d: fr })))
    el.addEventListener('click', (ev) => ev.preventDefault(), true) // no navigation in jsdom
    await click(bar())
    expect(sessionStorage.length).toBe(1)
    act(() => root.unmount())
    root = createRoot(el)
    history.replaceState(null, '', `${W2}?waitlist=1`)
    await mount(json(201, WAITING))
    expect(calls()).toEqual([`PUT ${API}`])
    expect(location.pathname + location.search).toBe(W2)
    expect(panel()!.getAttribute('data-waitlist')).toBe('joined')
    expect(sessionStorage.length).toBe(0)
  })

  it('StrictMode (the dev server) runs the effect twice: the second run still joins (real run: the first PUT was aborted)', async () => {
    markJoin(12)
    history.replaceState(null, '', `${W2}?waitlist=1`)
    const e = await detail()
    fetchMock.mockReset()
    fetchMock.mockImplementation(async (_, init) => (init?.method === 'PUT' ? json(201, WAITING) : json(200, { entry: null })))
    await act(async () => root.render(<StrictMode>{EventView({ e, now: NOW, d: fr, signedIn: true })}</StrictMode>))
    expect(calls()).toEqual([`PUT ${API}`, `PUT ${API}`]) // PUT is idempotent (04: already waiting → 200, unchanged)
    expect(panel()!.getAttribute('data-waitlist')).toBe('joined')
    expect(location.pathname + location.search).toBe(W2)
    expect(sessionStorage.length).toBe(0)
  })

  it("a crafted ?waitlist=1 without the marker (or with another event's) only reads; the param is dropped", async () => {
    markJoin(99)
    history.replaceState(null, '', `${W2}?waitlist=1`)
    await mount(json(200, { entry: null }))
    expect(calls()).toEqual([`GET ${API}`])
    expect(location.pathname + location.search).toBe(W2)
    expect(sessionStorage.length).toBe(0)
  })
})

describe('route handler /api/waitlist/[eventId]', () => {
  const ctx = (eventId = '12') => ({ params: Promise.resolve({ eventId }) })
  const req = (method: string, headers: Record<string, string> = {}) =>
    new Request(`http://shop.test${API}`, { method, headers: { origin: 'http://shop.test', cookie: `__Host-jomhor_session=${SESSION}`, ...headers } })
  const sent = (n: number) => {
    const [url, init] = fetchMock.mock.calls[n]!
    return { url: String(url), method: init?.method, auth: new Headers(init?.headers).get('authorization') }
  }

  it('cross-origin or Origin-less PUT or DELETE → 403, no backend call', async () => {
    expect((await PUT(req('PUT', { origin: 'http://evil.test' }), ctx())).status).toBe(403)
    expect((await DELETE(req('DELETE', { origin: 'http://evil.test' }), ctx())).status).toBe(403)
    const bare = (method: string) => new Request(`http://shop.test${API}`, { method, headers: { cookie: `__Host-jomhor_session=${SESSION}` } })
    expect((await PUT(bare('PUT'), ctx())).status).toBe(403)
    expect((await DELETE(bare('DELETE'), ctx())).status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('signed out → 401; a malformed event id → 404; neither calls the backend', async () => {
    expect((await GET(req('GET', { cookie: '' }), ctx())).status).toBe(401)
    for (const id of ['0', '012', '12-x', '1e3', '2147483648', '..']) expect((await PUT(req('PUT'), ctx(id))).status, id).toBe(404)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('GET / PUT / DELETE pass through with the Bearer from the cookie, the backend status, the 04 shape only, no-store', async () => {
    const stray = { ...WAITING.entry, user_id: 'SECRET-USER', id: 7 }
    fetchMock
      .mockResolvedValueOnce(json(200, { entry: null }))
      .mockResolvedValueOnce(json(201, { entry: stray, organizer_id: 'SECRET-ORG' }))
      .mockResolvedValueOnce(json(200, { entry: stray }))
      .mockResolvedValueOnce(json(204))
    const get = await GET(new Request(`http://shop.test${API}`, { headers: { cookie: `__Host-jomhor_session=${SESSION}` } }), ctx()) // no Origin on a same-origin GET
    const put = await PUT(req('PUT'), ctx())
    const again = await PUT(req('PUT'), ctx())
    const del = await DELETE(req('DELETE'), ctx())
    expect([get.status, put.status, again.status, del.status]).toEqual([200, 201, 200, 204])
    expect(await get.json()).toEqual({ entry: null })
    expect(await put.json()).toEqual(WAITING)
    expect(await again.json()).toEqual(WAITING)
    expect(await del.text()).toBe('')
    for (const r of [get, put, again, del]) expect(r.headers.get('cache-control')).toBe('no-store')
    for (const [i, m] of ['GET', 'PUT', 'PUT', 'DELETE'].entries())
      expect(sent(i)).toEqual({ url: 'http://backend.test/v1/fan/waitlist/12', method: m, auth: `Bearer ${SESSION}` })
  })

  it('backend errors pass through (409 WAITLIST_CLOSED, 404); a PUT 2xx without an entry → 502', async () => {
    fetchMock.mockResolvedValueOnce(json(409, closed('ROOM'))).mockResolvedValueOnce(json(404, { code: 'NOT_FOUND', message: '', details: {} })).mockResolvedValueOnce(json(201, {}))
    const res = await PUT(req('PUT'), ctx())
    expect(res.status).toBe(409)
    expect(res.headers.get('cache-control')).toBe('no-store')
    expect(await res.json()).toEqual(closed('ROOM'))
    expect((await GET(req('GET'), ctx('99'))).status).toBe(404)
    expect((await PUT(req('PUT'), ctx())).status).toBe(502)
  })
})
