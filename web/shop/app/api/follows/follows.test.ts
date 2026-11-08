import { NextRequest } from 'next/server'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ORG, ORG2 } from '@/lib/events.fixture'
import { POST } from './route'

beforeEach(() => vi.stubEnv('SHOP_ORIGIN', 'https://shop.test'))
afterEach(() => vi.unstubAllEnvs())

function post(fields: Record<string, string>, { origin = 'https://shop.test', cookie }: { origin?: string | null; cookie?: string } = {}) {
  const headers = new Headers()
  if (origin) headers.set('origin', origin)
  if (cookie) headers.set('cookie', cookie)
  return POST(new NextRequest('https://shop.test/api/follows', { method: 'POST', headers, body: new URLSearchParams(fields) }))
}
const follows = (res: Response) => decodeURIComponent(/(?:^|, )follows=([^;]*)/.exec(res.headers.get('set-cookie') ?? '')?.[1] ?? 'unset')

it.each([['https://evil.test'], [null], ['null']])('Origin %s → 403, no cookie', async (origin) => {
  const res = await post({ organizer_id: ORG, follow: '1', next: '/' }, { origin })
  expect(res.status).toBe(403)
  expect(res.headers.get('set-cookie')).toBeNull()
})

it.each([
  [{ organizer_id: 'atlas-fc', follow: '1' }],
  [{ organizer_id: `${ORG},${ORG2}`, follow: '1' }],
  [{ organizer_id: ORG, follow: 'yes' }],
  [{ follow: '1' }],
])('%j → 422', async (fields) => {
  const res = await post(fields)
  expect(res.status).toBe(422)
  expect(res.headers.get('set-cookie')).toBeNull()
})

it('follow: appends the id; HttpOnly, SameSite=Lax, one year, Path=/; 303 to next', async () => {
  const res = await post({ organizer_id: ORG2, follow: '1', next: '/?club=all' }, { cookie: `follows=${ORG}` })
  expect(res.status).toBe(303)
  expect(res.headers.get('location')).toBe('/?club=all')
  expect(follows(res)).toBe(`${ORG},${ORG2}`)
  const c = res.headers.get('set-cookie')!
  expect(c).toMatch(/HttpOnly/i)
  expect(c).toMatch(/SameSite=lax/i)
  expect(c).toMatch(/Max-Age=31536000/)
  expect(c).toMatch(/Path=\//)
  expect(c).not.toMatch(/Secure/i)
})

it('Secure in production', async () => {
  vi.stubEnv('NODE_ENV', 'production')
  expect((await post({ organizer_id: ORG, follow: '1' })).headers.get('set-cookie')).toMatch(/Secure/i)
})

it('unfollow removes the id; following twice keeps one copy (newest last)', async () => {
  expect(follows(await post({ organizer_id: ORG, follow: '0', next: '/atlas-fc' }, { cookie: `follows=${ORG},${ORG2}` }))).toBe(ORG2)
  expect(follows(await post({ organizer_id: ORG.toUpperCase(), follow: '1' }, { cookie: `follows=${ORG},${ORG2}` }))).toBe(`${ORG2},${ORG}`)
})

it('keeps at most 50 ids, the oldest dropped; junk in the old cookie is dropped', async () => {
  const ids = Array.from({ length: 50 }, (_, i) => `0190f3a2-0000-7000-9000-${String(i).padStart(12, '0')}`)
  const kept = follows(await post({ organizer_id: ORG2, follow: '1' }, { cookie: `follows=${['junk', ...ids].join(',')}` })).split(',')
  expect(kept).toHaveLength(50)
  expect(kept[0]).toBe(ids[1])
  expect(kept.at(-1)).toBe(ORG2)
})

it.each([
  ['//evil.test', '/'],
  ['https://evil.test/', '/'],
  [undefined, '/'],
  ['/olympique-tensift', '/olympique-tensift'],
])('next %j → Location %j', async (next, location) => {
  const res = await post({ organizer_id: ORG, follow: '1', ...(next !== undefined && { next }) })
  expect(res.headers.get('location')).toBe(location)
})
