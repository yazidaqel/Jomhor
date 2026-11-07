// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { AccountMenu, initials } from './AccountMenu'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const d = getDictionary(defaultLocale)
const router = { replace: vi.fn(), refresh: vi.fn() }
let pathname = '/'
vi.mock('next/navigation', () => ({ usePathname: () => pathname, useRouter: () => router }))

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const fetchMock = vi.fn<typeof fetch>()
let root: Root
let el: HTMLDivElement
beforeEach(() => {
  pathname = '/'
  vi.stubGlobal('fetch', fetchMock)
  el = document.createElement('div')
  document.body.append(el)
  root = createRoot(el)
})
afterEach(() => {
  act(() => root.unmount())
  el.remove()
  vi.unstubAllGlobals()
  fetchMock.mockReset()
  router.replace.mockReset()
  router.refresh.mockReset()
})

const show = () => act(async () => root.render(<AccountMenu copy={d.header} />))
const login = () => [...el.querySelectorAll('a')].find((a) => a.textContent === d.header.login)
const button = (text: string) => [...el.querySelectorAll('button')].find((b) => b.textContent?.includes(text))

it('initials: two words at most, uppercase; nothing without a name', () => {
  expect([initials('Yazid Aqel'), initials('  salma  ben ali '), initials('Omar'), initials('\u{1D400}li'), initials(''), initials(null)]).toEqual(['YA', 'SB', 'O', '\u{1D400}', null, null])
})

it('signed out (401) → "Se connecter", back to Mes billets from home', async () => {
  fetchMock.mockResolvedValue(json(401, { code: 'UNAUTHORIZED', message: '', details: {} }))
  await show()
  expect(fetchMock).toHaveBeenCalledWith('/api/me', expect.objectContaining({ cache: 'no-store' }))
  expect(login()!.getAttribute('href')).toBe('/login?next=%2Fme%2Ftickets')
})

it('signed out on an event page → login comes back to it; after a navigation it asks again', async () => {
  pathname = '/events/12-derby'
  fetchMock.mockResolvedValueOnce(json(401, {})).mockResolvedValueOnce(json(200, { display_name: 'Yazid Aqel' }))
  await show()
  expect(login()!.getAttribute('href')).toBe('/login?next=%2Fevents%2F12-derby')
  pathname = '/me/tickets' // the login form sent the fan here
  await show()
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(login()).toBeUndefined()
  expect(el.querySelector('button[popovertarget="account-menu"] span')!.textContent).toBe('YA')
})

it('signed in → initials avatar naming the account, a menu with Mes billets; asked once per page load', async () => {
  fetchMock.mockResolvedValue(json(200, { display_name: 'Yazid Aqel' }))
  await show()
  const avatar = el.querySelector('button[popovertarget="account-menu"]')!
  expect(avatar.textContent).toBe('YAYazid') // initials, then the first name (hidden at phone width)
  expect(avatar.getAttribute('aria-label')).toBe('Compte de Yazid Aqel')
  expect(el.querySelector('#account-menu a[href="/me/tickets"]')!.textContent).toBe(d.header.tickets)
  pathname = '/events/12-derby'
  await show()
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

it('the backend unreachable → treated as signed out', async () => {
  fetchMock.mockRejectedValue(new TypeError('fetch failed'))
  await show()
  expect(login()).toBeDefined()
})

it('"Se déconnecter" posts the logout, shows "Se connecter" and leaves a private page', async () => {
  pathname = '/me/tickets'
  fetchMock.mockResolvedValueOnce(json(200, { display_name: null })).mockResolvedValueOnce(new Response(null, { status: 204 }))
  await show()
  expect(el.querySelector('button[popovertarget="account-menu"]')!.getAttribute('aria-label')).toBe(d.header.account)
  await act(async () => button(d.header.logout)!.click())
  expect(fetchMock).toHaveBeenLastCalledWith('/api/auth/logout', { method: 'POST' })
  expect(router.replace).toHaveBeenCalledWith('/')
  expect(router.refresh).toHaveBeenCalled()
  expect(login()).toBeDefined()
})

it('a failed logout keeps the signed-in state (the cookie is still there) and says so', async () => {
  fetchMock.mockResolvedValueOnce(json(200, { display_name: 'Yazid Aqel' })).mockResolvedValueOnce(json(403, { code: 'FORBIDDEN' }))
  await show()
  await act(async () => button(d.header.logout)!.click())
  expect(login()).toBeUndefined()
  expect(router.refresh).not.toHaveBeenCalled()
  expect(el.querySelector('[role="alert"]')!.textContent).toBe(d.header.logoutFailed)
})
