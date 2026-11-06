import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { clientIp } from '@/lib/http'
import { POST as logout } from './logout/route'
import { POST as otp } from './otp/route'
import { POST as verify } from './verify/route'

const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsImF1ZCI6ImZhbiJ9.c2lnbmF0dXJl'
const USER = { id: 'u1', phone: '+212612345678', display_name: 'Yassine', locale: 'fr' }

const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => {
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubEnv('SHOP_ORIGIN', 'http://shop.test')
  vi.stubEnv('TRUSTED_PROXY_HOPS', undefined)
  vi.stubGlobal('fetch', fetchMock)
  vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-06T12:00:00Z'))
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  vi.restoreAllMocks()
  fetchMock.mockReset()
})

function req(path: string, body?: unknown, headers: Record<string, string> = {}) {
  return new Request(`http://shop.test${path}`, {
    method: 'POST',
    headers: { host: 'shop.test', origin: 'http://shop.test', 'content-type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

const upstream = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

it('verify sets the token as an HttpOnly, Secure, SameSite=Lax cookie and keeps it out of the body', async () => {
  fetchMock.mockResolvedValue(upstream(200, { token: TOKEN, expires_at: '2026-12-06T12:00:00Z', user: USER }))

  const res = await verify(req('/api/auth/verify', { phone: USER.phone, code: '123456' }))

  expect(res.status).toBe(200)
  const cookie = res.headers.get('set-cookie')!
  expect(cookie).toBe(`__Host-jomhor_session=${TOKEN}; Path=/; Max-Age=2592000; HttpOnly; Secure; SameSite=Lax`)
  const text = await res.text()
  expect(text).not.toContain(TOKEN)
  expect(JSON.parse(text)).toEqual({ user: USER })
  expect(res.headers.get('cache-control')).toBe('no-store')

  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('http://backend.test/v1/fan/auth/verify')
  expect(JSON.parse(init!.body as string)).toEqual({ phone: USER.phone, code: '123456' })
})

it('verify passes a backend error through in the 04 shape and sets no cookie', async () => {
  fetchMock.mockResolvedValue(upstream(401, { code: 'OTP_INVALID', message: 'Invalid code', details: {} }))

  const res = await verify(req('/api/auth/verify', { phone: USER.phone, code: '000000' }))

  expect(res.status).toBe(401)
  expect(await res.json()).toEqual({ code: 'OTP_INVALID', message: 'Invalid code', details: {} })
  expect(res.headers.get('set-cookie')).toBeNull()
})

it.each([
  ['an expired session', { token: TOKEN, expires_at: '2026-11-06T11:59:59Z', user: USER }],
  ['a token unsafe for a header', { token: `${TOKEN}; Domain=evil.com`, expires_at: '2026-12-06T12:00:00Z', user: USER }],
])('verify refuses %s from the backend', async (_, body) => {
  fetchMock.mockResolvedValue(upstream(200, body))
  const res = await verify(req('/api/auth/verify', { phone: USER.phone, code: '123456' }))
  expect(res.status).toBe(502)
  expect(res.headers.get('set-cookie')).toBeNull()
})

it.each([
  ['a foreign Origin', { origin: 'https://evil.com' }],
  ['a sibling subdomain', { origin: 'http://evil.shop.test' }],
  ['Origin: null', { origin: 'null' }],
  ['a foreign Origin behind a proxy', { 'x-forwarded-host': 'shop.jomhor.ma', origin: 'https://shop.test.evil.com' }],
])('every mutating handler answers 403 to %s without calling the backend', async (_, headers) => {
  for (const [handler, path] of [
    [otp, '/api/auth/otp'],
    [verify, '/api/auth/verify'],
    [logout, '/api/auth/logout'],
  ] as const) {
    const res = await handler(req(path, { phone: USER.phone, code: '123456' }, { ...headers, cookie: `__Host-jomhor_session=${TOKEN}` }))
    expect(res.status, path).toBe(403)
    expect(res.headers.get('set-cookie')).toBeNull()
  }
  expect(fetchMock).not.toHaveBeenCalled()
})

it('a POST with no Origin header is refused', async () => {
  const r = new Request('http://shop.test/api/auth/otp', { method: 'POST', headers: { host: 'shop.test' }, body: '{}' })
  expect((await otp(r)).status).toBe(403)
})

it('Host and X-Forwarded-Host are ignored: only SHOP_ORIGIN counts', async () => {
  const spoofed = { host: 'evil.com', 'x-forwarded-host': 'evil.com', origin: 'https://evil.com' }
  expect((await otp(req('/api/auth/otp', { phone: USER.phone }, spoofed))).status).toBe(403)

  vi.stubEnv('SHOP_ORIGIN', 'https://shop.jomhor.ma')
  fetchMock.mockResolvedValue(upstream(202))
  const proxied = { host: '10.0.0.5:3000', 'x-forwarded-host': 'other.test', origin: 'https://shop.jomhor.ma' }
  expect((await otp(req('/api/auth/otp', { phone: USER.phone }, proxied))).status).toBe(202)
})

it('an http:// Origin on the shop host is refused when the shop is https', async () => {
  vi.stubEnv('SHOP_ORIGIN', 'https://shop.test')
  const res = await otp(req('/api/auth/otp', { phone: USER.phone }, { origin: 'http://shop.test' }))
  expect(res.status).toBe(403)
  expect(fetchMock).not.toHaveBeenCalled()
})

it('every request is refused when SHOP_ORIGIN is not set', async () => {
  vi.stubEnv('SHOP_ORIGIN', undefined)
  expect((await otp(req('/api/auth/otp', { phone: USER.phone }))).status).toBe(403)
})

it.each<[string | undefined, string | undefined, string | null]>([
  [undefined, '1.2.3.4, 41.250.1.2', '41.250.1.2'], // default 1 hop (the ALB)
  ['1', '1.2.3.4, 41.250.1.2', '41.250.1.2'],
  ['1', '41.250.1.2', '41.250.1.2'],
  ['1', '6.6.6.6, 7.7.7.7, 41.250.1.2', '41.250.1.2'], // client-supplied entries change nothing
  ['1', '2001:db8::1', '2001:db8::1'],
  ['2', '6.6.6.6, 41.250.1.2, 10.0.0.1', '41.250.1.2'],
  ['2', '41.250.1.2', null], // shorter than the hops
  ['1', 'garbage', null],
  ['1', '1.2.3.4, ', null],
  ['1', '41.250.1.2:5555', null],
  ['1', undefined, null],
  ['0', '1.2.3.4, 41.250.1.2', null],
  ['abc', '41.250.1.2', null],
  ['-1', '41.250.1.2', null],
])('clientIp with TRUSTED_PROXY_HOPS=%s and X-Forwarded-For %j → %s', (hops, xff, ip) => {
  vi.stubEnv('TRUSTED_PROXY_HOPS', hops)
  expect(clientIp(req('/api/auth/otp', {}, xff === undefined ? {} : { 'x-forwarded-for': xff }))).toBe(ip)
})

it('otp proxies the phone only, forwards exactly one client IP, and answers 202', async () => {
  fetchMock.mockResolvedValue(upstream(202))

  const res = await otp(req('/api/auth/otp', { phone: USER.phone, extra: 'x' }, { 'x-forwarded-for': '1.2.3.4, 41.250.1.2' }))

  expect(res.status).toBe(202)
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('http://backend.test/v1/fan/auth/otp')
  expect(JSON.parse(init!.body as string)).toEqual({ phone: USER.phone })
  expect(new Headers(init!.headers).get('x-forwarded-for')).toBe('41.250.1.2')
})

it('otp and verify send no X-Forwarded-For when no trusted client IP is found', async () => {
  vi.stubEnv('TRUSTED_PROXY_HOPS', '2')
  fetchMock.mockResolvedValueOnce(upstream(202))
  fetchMock.mockResolvedValueOnce(upstream(401, { code: 'OTP_INVALID', message: '', details: {} }))

  await otp(req('/api/auth/otp', { phone: USER.phone }, { 'x-forwarded-for': '6.6.6.6' }))
  await verify(req('/api/auth/verify', { phone: USER.phone, code: '123456' }, { 'x-forwarded-for': 'garbage' }))

  for (const [, init] of fetchMock.mock.calls) expect(new Headers(init!.headers).has('x-forwarded-for')).toBe(false)
})

it('backend calls carry a timeout signal', async () => {
  fetchMock.mockResolvedValue(upstream(202))
  await otp(req('/api/auth/otp', { phone: USER.phone }))
  expect(fetchMock.mock.calls[0]![1]!.signal).toBeInstanceOf(AbortSignal)
})

it('otp passes 429 OTP_RATE_LIMITED through', async () => {
  fetchMock.mockResolvedValue(upstream(429, { code: 'OTP_RATE_LIMITED', message: 'Too many', details: {} }))
  const res = await otp(req('/api/auth/otp', { phone: USER.phone }))
  expect(res.status).toBe(429)
  expect((await res.json()).code).toBe('OTP_RATE_LIMITED')
})

it('otp answers 502 when the backend is unreachable', async () => {
  fetchMock.mockRejectedValue(new TypeError('fetch failed'))
  const res = await otp(req('/api/auth/otp', { phone: USER.phone }))
  expect(res.status).toBe(502)
  expect((await res.json()).code).toBe('INTERNAL_ERROR')
})

it('logout revokes the session with the cookie token as Bearer and clears the cookie', async () => {
  fetchMock.mockResolvedValue(upstream(204))

  const res = await logout(req('/api/auth/logout', { all: true }, { cookie: `a=1; __Host-jomhor_session=${TOKEN}` }))

  expect(res.status).toBe(204)
  expect(res.headers.get('set-cookie')).toBe('__Host-jomhor_session=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax')
  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('http://backend.test/v1/fan/me/logout')
  expect(new Headers(init!.headers).get('authorization')).toBe(`Bearer ${TOKEN}`)
  expect(JSON.parse(init!.body as string)).toEqual({ all: true })
})

it('logout still clears the cookie when the session is already revoked', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  fetchMock.mockResolvedValue(upstream(401, { code: 'UNAUTHORIZED', message: '', details: {} }))
  const res = await logout(req('/api/auth/logout', undefined, { cookie: `__Host-jomhor_session=${TOKEN}` }))
  expect(res.status).toBe(204)
  expect(res.headers.get('set-cookie')).toContain('Max-Age=0')
})

it('logout without a session cookie does not call the backend', async () => {
  const res = await logout(req('/api/auth/logout'))
  expect(res.status).toBe(204)
  expect(fetchMock).not.toHaveBeenCalled()
})
