// @vitest-environment node
import { NextRequest } from 'next/server'
import { unstable_doesMiddlewareMatch } from 'next/experimental/testing/server'
import { expect, it, vi } from 'vitest'
import { config, proxy } from './proxy'

const run = (url: string, cookie?: string) => proxy(new NextRequest(url, cookie ? { headers: { cookie } } : undefined))

it('?lang= sets the locale cookie for a year, for the whole site', () => {
  const res = run('https://shop.test/events/12-x?lang=ar&ref=wa')
  const c = res.cookies.get('locale')!
  expect(c.value).toBe('ar')
  expect(c.path).toBe('/')
  expect(c.maxAge).toBe(31_536_000)
  expect(c.httpOnly).toBe(true)
  expect(c.sameSite).toBe('lax')
})

it('?lang= also wins for this request: the page rendered now sees the new cookie, not the old one', () => {
  const res = run('https://shop.test/?lang=en', '__Host-jomhor_session=t; locale=ar')
  // NextResponse.next({ request }) forwards the rewritten Cookie header to the page.
  expect(res.headers.get('x-middleware-request-cookie')).toMatch(/locale=en/)
  expect(res.headers.get('x-middleware-request-cookie')).toMatch(/__Host-jomhor_session=t/)
})

it.each(['https://shop.test/', 'https://shop.test/?lang=de', 'https://shop.test/?lang='])('%s leaves the cookie alone', (url) => {
  expect(run(url, 'locale=ar').cookies.get('locale')).toBeUndefined()
})

it('Secure in production only (local dev is plain http)', () => {
  vi.stubEnv('NODE_ENV', 'production')
  expect(run('https://shop.test/?lang=ar').cookies.get('locale')!.secure).toBe(true)
  vi.unstubAllEnvs()
  expect(run('https://shop.test/?lang=ar').cookies.get('locale')!.secure).toBeFalsy()
})

it.each([
  ['/', true],
  ['/events/12-atlas-fc-vs-rapid-oued?lang=ar', true],
  ['/orders/new?event=12&lang=en', true],
  ['/login', true],
  ['/events/12-atlas-fc-vs-rapid-oued/opengraph-image-1rk9k5', false],
  ['/atlas-fc', true],
  ['/atlas-fc/events/12-x?lang=ar', true],
  ['/atlas-fc/opengraph-image-x', false],
  ['/atlas-fc/events/12-x/opengraph-image-1rk9k5', false],
  ['/api/follows', false],
  ['/api/me', false],
  ['/api/orders/1/pay', false],
  ['/_next/static/chunks/app.js', false],
  ['/favicon.ico', false],
])('the matcher runs the proxy on %s: %s', (path, runs) => {
  expect(unstable_doesMiddlewareMatch({ config, url: `https://shop.test${path}` })).toBe(runs)
})
