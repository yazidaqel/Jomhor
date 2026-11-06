import { expect, it } from 'vitest'
import { safeNext } from './next-path'

it.each([
  '/',
  '/me/tickets',
  '/events/12-atlas-fc-vs-raja?zone=3#zones',
  '/orders/0190f3a2-7c1e-7b55-9a1e-3f1f6b2c9d10',
  '/%2F%2Fevil.com', // encoded slashes stay a path on this origin
  '/a//b',
])('keeps %j', (raw) => {
  expect(safeNext(raw)).toBe(raw)
})

it.each([
  ['/a/../me/tickets', '/me/tickets'],
  ['/./me?x=1#y', '/me?x=1#y'],
])('normalises %j → %j', (raw, path) => {
  expect(safeNext(raw)).toBe(path)
})

it.each([
  undefined,
  '',
  ['/me/tickets', '/x'], // repeated ?next=
  '//evil.com',
  '///evil.com',
  '/\\evil.com',
  '\\\\evil.com',
  '/\\/evil.com',
  '/.//evil.com', // dot segments that resolve to //evil.com
  '/..//evil.com',
  '/a/..//evil.com',
  'https://evil.com',
  'http:evil.com',
  'javascript:alert(1)',
  'data:text/html,x',
  'evil.com',
  'me/tickets',
  ' /me',
  '/\t/evil.com',
  '/\n/evil.com',
  '/\r/evil.com',
  '/\x00',
  '/a b',
  `/${'a'.repeat(2048)}`,
])('rejects %j → "/"', (raw) => {
  expect(safeNext(raw)).toBe('/')
})
