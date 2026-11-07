import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import { Shell } from '@/app/Shell'
import { useOrgQuery, type Me } from '@/features/auth/organizer'
import { api } from '@/lib/api'
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'

beforeAll(() => {
  // Radix Select needs these; jsdom does not implement them.
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  session.clear()
})

const FUTURE = '2999-01-01T00:00:00Z'
const ME: Me = {
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [
    { organizer_id: 'org-a', organizer_name: 'Atlas FC', role: 'ADMIN' },
    { organizer_id: 'org-b', organizer_name: 'Raja', role: 'OPS' },
  ],
}

type Handler = (path: string, init: RequestInit & { headers: Headers }) => Response | Promise<Response>

/** Stubs fetch; `handler` gets the path without /v1 and the sent headers. /org/me answers ME unless handled. */
function stubApi(handler: Handler = () => new Response(null, { status: 404 })) {
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input).replace(/^\/v1/, '')
    const headers = new Headers(init?.headers)
    const res = await handler(path, { ...init, headers })
    if (res.status === 404 && path === '/org/me') return Response.json(ME)
    return res
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function Probe() {
  const q = useOrgQuery<{ name: string }>(['probe'], '/org/probe')
  return <p>{q.data ? `data:${q.data.name}` : 'pending'}</p>
}

function renderApp(path: string) {
  const queryClient = createQueryClient()
  const router = createMemoryRouter(
    [...routes, { element: <Shell />, children: [{ path: 'probe', element: <Probe /> }] }],
    { initialEntries: [path] },
  )
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}

it('signs in: phone → OTP → verify → session stored → /events', async () => {
  const user = userEvent.setup()
  const fetchMock = stubApi((path) =>
    path === '/org/auth/otp'
      ? new Response(null, { status: 202 })
      : path === '/org/auth/verify'
        ? Response.json({ token: 'tok-1', expires_at: FUTURE, memberships: ME.memberships })
        : new Response(null, { status: 404 }),
  )
  const { router } = renderApp('/events')

  await user.type(await screen.findByLabelText(fr.login.phoneLabel), '06 12 34 56 78')
  await user.click(screen.getByRole('button', { name: fr.login.sendCode }))
  await user.type(await screen.findByLabelText(fr.login.codeLabel), '123456')
  await user.click(screen.getByRole('button', { name: fr.login.verify }))

  expect(await screen.findByRole('heading', { name: fr.events.title })).toBeTruthy()
  expect(router.state.location.pathname).toBe('/events')
  expect(session.token).toBe('tok-1')
  expect(JSON.parse(sessionStorage.getItem('jomhor.session')!)).toEqual({ token: 'tok-1', expires_at: FUTURE })
  const bodies = fetchMock.mock.calls.filter(([u]) => String(u).includes('/auth/')).map(([, i]) => JSON.parse(String(i?.body)))
  expect(bodies).toEqual([{ phone: '+212612345678' }, { phone: '+212612345678', code: '123456' }])
})

it('rejects a malformed phone without calling the API', async () => {
  const user = userEvent.setup()
  const fetchMock = stubApi()
  renderApp('/login')

  await user.type(screen.getByLabelText(fr.login.phoneLabel), '12345')
  await user.click(screen.getByRole('button', { name: fr.login.sendCode }))

  expect(await screen.findByText(fr.login.phoneInvalid)).toBeTruthy()
  expect(fetchMock).not.toHaveBeenCalled()
})

it('shows the FR message for OTP_INVALID and stays signed out', async () => {
  const user = userEvent.setup()
  stubApi((path) =>
    path === '/org/auth/otp'
      ? new Response(null, { status: 202 })
      : Response.json({ code: 'OTP_INVALID', message: 'bad code', details: {} }, { status: 401 }),
  )
  const { router } = renderApp('/login')

  await user.type(screen.getByLabelText(fr.login.phoneLabel), '0612345678')
  await user.click(screen.getByRole('button', { name: fr.login.sendCode }))
  await user.type(await screen.findByLabelText(fr.login.codeLabel), '000000')
  await user.click(screen.getByRole('button', { name: fr.login.verify }))

  expect(await screen.findByText(fr.errors.OTP_INVALID)).toBeTruthy()
  const code = screen.getByLabelText(fr.login.codeLabel)
  expect(code.getAttribute('aria-invalid')).toBe('true')
  expect(code.getAttribute('aria-describedby')).toBe('code-description code-error')
  expect(document.getElementById('code-error')?.textContent).toBe(fr.errors.OTP_INVALID)
  expect(code.getAttribute('autocomplete')).toBe('one-time-code')
  expect(code.getAttribute('inputmode')).toBe('numeric')
  expect(session.token).toBeNull()
  expect(router.state.location.pathname).toBe('/login')
})

it('scopes org queries by organizer and switching never shows the previous organizer’s data', async () => {
  const user = userEvent.setup()
  session.start('tok-1', FUTURE)
  let releaseB = () => {}
  const fetchMock = stubApi(async (path, { headers }) => {
    if (path !== '/org/probe') return new Response(null, { status: 404 })
    const org = headers.get('X-Organizer-Id')
    if (org === 'org-b') await new Promise<void>((r) => (releaseB = r))
    return Response.json({ name: org })
  })
  const { queryClient } = renderApp('/probe')

  expect(await screen.findByText('data:org-a')).toBeTruthy()
  expect(queryClient.getQueryData(['org', 'org-a', 'probe'])).toEqual({ name: 'org-a' })

  await user.click(screen.getByRole('combobox', { name: fr.shell.organizer }))
  await user.click(await screen.findByRole('option', { name: `Raja · ${fr.roles.OPS}` }))

  // While org-b loads, org-a's data is gone from the page and from the cache.
  expect(await screen.findByText('pending')).toBeTruthy()
  expect(screen.queryByText('data:org-a')).toBeNull()
  expect(queryClient.getQueryData(['org', 'org-a', 'probe'])).toBeUndefined()
  releaseB()
  expect(await screen.findByText('data:org-b')).toBeTruthy()

  const probeOrgs = fetchMock.mock.calls
    .filter(([u]) => String(u) === '/v1/org/probe')
    .map(([, i]) => new Headers(i?.headers).get('X-Organizer-Id'))
  expect(probeOrgs).toEqual(['org-a', 'org-b'])
  expect(sessionStorage.getItem('jomhor.organizer')).toBe('org-b')
  // /org/me carries no organizer.
  const me = fetchMock.mock.calls.find(([u]) => String(u) === '/v1/org/me')!
  expect(new Headers(me[1]?.headers).has('X-Organizer-Id')).toBe(false)
})

it('shows the user and the role for the selected organizer', async () => {
  session.start('tok-1', FUTURE)
  session.selectOrganizer('org-b')
  stubApi()
  renderApp('/events')

  expect(await screen.findByText('Samira')).toBeTruthy()
  expect(screen.getByText(fr.roles.OPS)).toBeTruthy()
})

it('a 401 clears the session and the cache and redirects to /login, keeping the intended path', async () => {
  session.start('tok-1', FUTURE)
  stubApi((path) =>
    path === '/org/probe'
      ? Response.json({ code: 'UNAUTHORIZED', message: 'expired', details: {} }, { status: 401 })
      : new Response(null, { status: 404 }),
  )
  const { router, queryClient } = renderApp('/probe?x=1')

  expect(await screen.findByLabelText(fr.login.phoneLabel)).toBeTruthy()
  expect(router.state.location.pathname).toBe('/login')
  expect(router.state.location.state).toEqual({ from: '/probe?x=1' })
  expect(sessionStorage.getItem('jomhor.session')).toBeNull()
  expect(queryClient.getQueryCache().getAll().map((q) => q.state.data)).toEqual([undefined]) // only the shell's disabled /org/me, empty
})

it('logout clears the session and the cache', async () => {
  const user = userEvent.setup()
  session.start('tok-1', FUTURE)
  stubApi()
  const { router, queryClient } = renderApp('/events')

  await user.click(await screen.findByRole('button', { name: /Samira/ })) // user menu (name + role), top bar
  await user.click(await screen.findByRole('menuitem', { name: fr.shell.logout }))

  await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
  expect(sessionStorage.length).toBe(0)
  expect(queryClient.getQueryCache().getAll().map((q) => q.state.data)).toEqual([undefined]) // only the shell's disabled /org/me, empty
})

it('an expired token is treated as signed out', async () => {
  session.start('tok-old', '2000-01-01T00:00:00Z')
  stubApi()
  const { router } = renderApp('/events')

  expect(await screen.findByLabelText(fr.login.phoneLabel)).toBeTruthy()
  expect(router.state.location.pathname).toBe('/login')
})

it('a user without membership sees "aucun accès" and can log out', async () => {
  session.start('tok-1', FUTURE)
  stubApi((path) => (path === '/org/me' ? Response.json({ ...ME, memberships: [] }) : new Response(null, { status: 404 })))
  renderApp('/events')

  expect(await screen.findByText(fr.shell.noAccessTitle)).toBeTruthy()
  expect(screen.getByRole('button', { name: fr.shell.logout })).toBeTruthy()
})

it('"Renvoyer le code" re-sends the OTP after a 30 s cooldown', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  const fetchMock = stubApi((path) =>
    path === '/org/auth/otp' ? new Response(null, { status: 202 }) : new Response(null, { status: 404 }),
  )
  renderApp('/login')
  const otpCalls = () => fetchMock.mock.calls.filter(([u]) => String(u) === '/v1/org/auth/otp').length

  await user.type(screen.getByLabelText(fr.login.phoneLabel), '0612345678')
  await user.click(screen.getByRole('button', { name: fr.login.sendCode }))
  const waiting = await screen.findByRole('button', { name: i18n.t('login.resendIn', { seconds: 30 }) })
  expect(waiting).toHaveProperty('disabled', true)

  act(() => vi.advanceTimersByTime(30_000))
  await user.click(screen.getByRole('button', { name: fr.login.resend }))

  await waitFor(() => expect(otpCalls()).toBe(2))
  expect(JSON.parse(String(fetchMock.mock.calls.at(-1)![1]?.body))).toEqual({ phone: '+212612345678' })
  expect(await screen.findByText(i18n.t('login.codeResent', { phone: '+212612345678' }))).toBeTruthy()
  expect(screen.getByRole('button', { name: i18n.t('login.resendIn', { seconds: 30 }) })).toHaveProperty('disabled', true)
})

it('after expiry, the next user on the tab never sees the previous session data', async () => {
  const user = userEvent.setup()
  session.start('tok-A', new Date(Date.now() + 1500).toISOString())
  session.selectOrganizer('org-a')
  // B is also a member of org-a: a leftover organizer selection would land B there instead of on org-c.
  const meB: Me = {
    user: { id: 'u-2', phone: '+212600000002', display_name: 'Youssef', locale: 'fr' },
    memberships: [
      { organizer_id: 'org-c', organizer_name: 'Wydad', role: 'ADMIN' },
      { organizer_id: 'org-a', organizer_name: 'Atlas FC', role: 'OPS' },
    ],
  }
  let releaseMeB = () => {}
  stubApi(async (path, { headers }) => {
    const auth = headers.get('Authorization')
    if (path === '/org/auth/otp') return new Response(null, { status: 202 })
    if (path === '/org/auth/verify') {
      return Response.json({ token: 'tok-B', expires_at: FUTURE, memberships: meB.memberships })
    }
    if (path === '/org/me' && auth === 'Bearer tok-B') {
      await new Promise<void>((r) => (releaseMeB = r))
      return Response.json(meB)
    }
    if (path === '/org/probe') return Response.json({ name: `${headers.get('X-Organizer-Id')} ${auth}` })
    return new Response(null, { status: 404 })
  })
  const { router, queryClient } = renderApp('/probe')
  expect(await screen.findByText('data:org-a Bearer tok-A')).toBeTruthy()
  expect(screen.getByText('Samira')).toBeTruthy()

  // Idle until the token expires: no request is needed to end the session.
  const phone = await screen.findByLabelText(fr.login.phoneLabel, {}, { timeout: 6000 })
  expect(router.state.location.pathname).toBe('/login')
  expect(sessionStorage.length).toBe(0)
  expect(queryClient.getQueryCache().getAll().filter((q) => q.state.data !== undefined)).toEqual([])

  const shown: string[] = []
  const observer = new MutationObserver(() => shown.push(document.body.textContent ?? ''))
  observer.observe(document.body, { subtree: true, childList: true, characterData: true })

  await user.type(phone, '0600000002')
  await user.click(screen.getByRole('button', { name: fr.login.sendCode }))
  await user.type(await screen.findByLabelText(fr.login.codeLabel), '123456')
  await user.click(screen.getByRole('button', { name: fr.login.verify }))

  expect(await screen.findByText(fr.shell.loading)).toBeTruthy() // B's /org/me in flight: nothing of A shown
  expect(screen.queryByText('Samira')).toBeNull()
  releaseMeB()
  expect(await screen.findByText('data:org-c Bearer tok-B')).toBeTruthy()
  expect(screen.getByText('Youssef')).toBeTruthy()
  expect(router.state.location.pathname).toBe('/probe')

  observer.disconnect()
  expect(shown.filter((text) => /Samira|tok-A/.test(text))).toEqual([])
})

it('a late 401 for a request sent with the old token does not end the new session', async () => {
  session.start('tok-A', FUTURE)
  let answer = () => {}
  stubApi(
    () =>
      new Promise<Response>((r) => {
        answer = () => r(Response.json({ code: 'UNAUTHORIZED', message: 'x' }, { status: 401 }))
      }),
  )

  const late = api('/org/events', { organizerId: 'org-a' }).catch(() => {})
  session.start('tok-B', FUTURE)
  answer()
  await late

  expect(session.token).toBe('tok-B')
})

it('offers only ADMIN, OPS and FINANCE memberships; GATE/SUPERVISOR-only users get "aucun accès"', async () => {
  const user = userEvent.setup()
  session.start('tok-1', FUTURE)
  const memberships: Me['memberships'] = [
    { organizer_id: 'org-g', organizer_name: 'Gate Org', role: 'GATE' },
    { organizer_id: 'org-f', organizer_name: 'Finance Org', role: 'FINANCE' },
    { organizer_id: 'org-s', organizer_name: 'Sup Org', role: 'SUPERVISOR' },
  ]
  stubApi((path) => (path === '/org/me' ? Response.json({ ...ME, memberships }) : new Response(null, { status: 404 })))
  renderApp('/events')

  await user.click(await screen.findByRole('combobox', { name: fr.shell.organizer }))
  const options = await screen.findAllByRole('option')
  expect(options.map((o) => o.textContent)).toEqual([`Finance Org · ${fr.roles.FINANCE}`])
  cleanup()

  const gateOnly = { ...ME, memberships: [memberships[0]!, memberships[2]!] }
  stubApi((path) => (path === '/org/me' ? Response.json(gateOnly) : new Response(null, { status: 404 })))
  renderApp('/events')
  expect(await screen.findByText(fr.shell.noAccessTitle)).toBeTruthy()
})

it('an /org/me failure offers a retry', async () => {
  const user = userEvent.setup()
  session.start('tok-1', FUTURE)
  let fail = true
  stubApi((path) =>
    path === '/org/me' && fail
      ? Response.json({ code: 'FORBIDDEN', message: 'x', details: {} }, { status: 403 })
      : new Response(null, { status: 404 }),
  )
  renderApp('/events')

  const retry = await screen.findByRole('button', { name: fr.shell.retry })
  fail = false
  await user.click(retry)

  expect(await screen.findByRole('heading', { name: fr.events.title })).toBeTruthy()
})

it('a new session starts from an empty cache and no organizer, even without logout in between', () => {
  const queryClient = createQueryClient()
  session.start('tok-A', FUTURE)
  session.selectOrganizer('org-a')
  queryClient.setQueryData(['me'], ME)
  queryClient.setQueryData(['org', 'org-a', 'probe'], { name: 'org-a' })

  session.selectOrganizer('org-a') // same token: the cache stays
  expect(queryClient.getQueryData(['me'])).toEqual(ME)

  session.start('tok-B', FUTURE)
  expect(queryClient.getQueryCache().getAll()).toEqual([])
  expect(sessionStorage.getItem('jomhor.organizer')).toBeNull()
})

it('phone width: the menu is a sheet that closes once a destination is picked', async () => {
  const user = userEvent.setup()
  const width = window.innerWidth
  Object.defineProperty(window, 'innerWidth', { value: 390, configurable: true })
  try {
    session.start('tok-1', FUTURE)
    stubApi((path) => (path === '/org/events' ? Response.json([]) : new Response(null, { status: 404 })))
    const { router } = renderApp('/settings/venues')

    expect(screen.queryByRole('dialog')).toBeNull()
    await user.click(await screen.findByRole('button', { name: fr.shell.toggleSidebar }))
    const sheet = await screen.findByRole('dialog', { name: fr.nav.label })
    await user.click(within(sheet).getByRole('link', { name: fr.nav.events }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/events'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  } finally {
    Object.defineProperty(window, 'innerWidth', { value: width, configurable: true })
  }
})

it('"Bientôt" items stay focusable but inert (aria-disabled), they are not links', async () => {
  session.start('tok-1', FUTURE)
  stubApi()
  renderApp('/settings/venues')
  const nav = within(await screen.findByRole('navigation', { name: fr.nav.label }))
  const soon = nav.getByRole('button', { name: new RegExp(fr.nav.invitations) })
  expect(soon.getAttribute('aria-disabled')).toBe('true')
  expect(soon).toHaveProperty('disabled', false)
  expect(nav.queryByRole('link', { name: new RegExp(fr.nav.invitations) })).toBeNull()
})
