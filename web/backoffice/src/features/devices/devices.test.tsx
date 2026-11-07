import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import QRCode from 'react-qr-code'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import type { EventDetail } from '@/features/events/api'
import { createQueryClient } from '@/lib/queryClient'
import { formatDateTime } from '@/lib/time'
import { session } from '@/lib/session'
import type { Assignment, Device, Staff } from './api'

beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  session.clear()
})

// ---- Fake API ----

const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const dev = (id: string, label: string, status: Device['status'], over: Partial<Device> = {}): Device => ({
  id,
  label,
  status,
  fingerprint: `${id.toUpperCase().padEnd(4, '0').slice(0, 4)} CD34`,
  enrolled_at: '2026-11-10T09:00:00Z',
  approved_at: status === 'PENDING' ? null : '2026-11-10T09:05:00Z',
  last_seen_at: null,
  ...over,
})
// API order: PENDING first, then by label.
const DEVICES: Device[] = [
  dev('d4', 'PDA 12', 'PENDING', { fingerprint: 'AB12 CD34' }),
  dev('d1', 'PDA 1', 'ACTIVE', { last_seen_at: '2026-11-14T18:40:00Z' }),
  dev('d2', 'PDA 2', 'ACTIVE'),
  dev('d3', 'PDA 3', 'REVOKED'),
  dev('d5', 'PDA 5', 'ACTIVE'),
]
const STAFF: Staff[] = [
  { id: 's-admin', name: 'Samira Admin', role: 'ADMIN', active: true, has_pin: false },
  { id: 's1', name: 'Karim Bennani', role: 'GATE', active: true, has_pin: true },
  { id: 's2', name: 'Nadia Alaoui', role: 'SUPERVISOR', active: false, has_pin: true },
  { id: 's3', name: 'Omar Tazi', role: 'CASHIER', active: true, has_pin: false },
]
const EVENT: EventDetail = {
  id: 1,
  venue_id: 'v-1',
  timezone: 'Africa/Casablanca',
  series_id: null,
  event_type: 'FOOTBALL_MATCH',
  title: 'Atlas FC vs Rapid Oued',
  starts_at: '2026-11-14T19:00:00Z',
  doors_open_at: null,
  sale_starts_at: null,
  status: 'ON_SALE',
  refund_cutoff_at: null,
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: {},
  created_at: '2026-11-01T10:00:00Z',
}
const GATES = [
  { id: 'g1', code: 'P1', name: 'Porte Marathon', zone_ids: [] },
  { id: 'g2', code: 'P2', name: 'Porte Nord', zone_ids: [] },
]
const ASSIGNED: Assignment[] = [
  { device_id: 'd1', label: 'PDA 1', status: 'ACTIVE', gate_id: 'g1', gate_code: 'P1', manifest_version: 3, last_seen_at: '2026-11-14T23:10:00Z', closed_ack_at: '2026-11-14T23:30:00Z' },
  { device_id: 'd3', label: 'PDA 3', status: 'REVOKED', gate_id: 'g2', gate_code: 'P2', manifest_version: null, last_seen_at: null, closed_ack_at: null },
]

type Call = { method: string; path: string; org: string | null; body: unknown }
type Handler = (call: Call) => Response | undefined

function stubApi(role: Role = 'ADMIN', handler: Handler = () => undefined) {
  const calls: Call[] = []
  let codes = 0
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const call: Call = {
        method: init?.method ?? 'GET',
        path: String(input).replace(/^\/v1/, ''),
        org: new Headers(init?.headers).get('X-Organizer-Id'),
        body: init?.body ? JSON.parse(String(init.body)) : undefined,
      }
      calls.push(call)
      const handled = handler(call)
      if (handled) return handled
      const key = `${call.method} ${call.path}`
      if (key === 'GET /org/me') return Response.json(me(role))
      if (key === 'GET /org/devices') return Response.json(DEVICES)
      if (key === 'POST /org/devices/enrol-codes')
        return Response.json({ enrol_code: `eyJ.enrol-${++codes}.sig`, expires_at: new Date(Date.now() + 90_000).toISOString() }, { status: 201 })
      const action = /^POST \/org\/devices\/(\w+)\/(approve|revoke)$/.exec(key)
      if (action) {
        const d = DEVICES.find((x) => x.id === action[1])!
        return Response.json({ ...d, status: action[2] === 'approve' ? 'ACTIVE' : 'REVOKED' })
      }
      if (key === 'GET /org/staff') return Response.json(STAFF)
      if (key === 'POST /org/staff') {
        const { name, role } = call.body as { name: string; role: string }
        // 04-api: a CASHIER answers has_pin false and no `pin` key; GATE/SUPERVISOR get their PIN once.
        const pin = role === 'CASHIER' ? { has_pin: false } : { has_pin: true, pin: '4821' }
        return Response.json({ id: 's9', name, role, active: true, ...pin }, { status: 201 })
      }
      if (key === 'POST /org/staff/s1/pin') return Response.json({ pin: '0937' })
      if (key === 'PATCH /org/staff/s1') return Response.json({ ...STAFF[1], ...(call.body as object) })
      if (key === 'GET /org/events/1') return Response.json(EVENT)
      if (key === 'GET /org/events/1/prices') return Response.json([])
      if (key === 'GET /org/venues') return Response.json([])
      if (key === 'GET /org/venues/v-1')
        return Response.json({ id: 'v-1', name: 'Grand Stade', city: null, capacity: 45000, timezone: 'Africa/Casablanca', zones: [], gates: GATES })
      if (key === 'GET /org/events/1/devices') return Response.json(ASSIGNED)
      if (key === 'PUT /org/events/1/devices') {
        const rows = (call.body as { assignments: { device_id: string; gate_id: string }[] }).assignments
        return Response.json(
          rows.map((r) => {
            const d = DEVICES.find((x) => x.id === r.device_id)!
            const g = GATES.find((x) => x.id === r.gate_id)!
            return { device_id: d.id, label: d.label, status: d.status, gate_id: g.id, gate_code: g.code, manifest_version: null, last_seen_at: null, closed_ack_at: null }
          }),
        )
      }
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

function renderApp(path: string, user?: ReturnType<typeof userEvent.setup>) {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const queryClient = createQueryClient()
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient, user: user ?? userEvent.setup() }
}

const writes = (calls: Call[]) => calls.filter((c) => c.method !== 'GET')
const err = (status: number, code: string, details: object = {}) => Response.json({ code, message: 'x', details }, { status })
/** Everything TanStack Query holds: query data and mutation state (variables, data, error). */
const cacheDump = (qc: QueryClient) =>
  JSON.stringify([qc.getQueryCache().getAll().map((q) => q.state), qc.getMutationCache().getAll().map((m) => m.state)])
const qrPaths = (root: ParentNode) => [...root.querySelectorAll('svg path')].map((p) => p.getAttribute('d'))
const rowOf = (label: string) => screen.getByRole('cell', { name: label }).closest('tr') as HTMLElement

// ---- Roles and navigation ----

it.each(['ADMIN', 'OPS'] as const)('%s: the sidebar lists Appareils as a link to /devices', async (role) => {
  stubApi(role)
  renderApp('/devices')
  const nav = within(await screen.findByRole('navigation', { name: fr.nav.label }))
  const link = nav.getByRole('link', { name: fr.nav.devices })
  expect(link.getAttribute('href')).toBe('/devices')
  expect(link.getAttribute('aria-current')).toBe('page')
  expect(await screen.findByRole('heading', { level: 1, name: fr.devices.title })).toBeTruthy()
})

it('FINANCE: no Appareils in the sidebar, /devices says it is reserved and calls no device or staff endpoint', async () => {
  const calls = stubApi('FINANCE')
  renderApp('/devices')
  expect(await screen.findByText(fr.devices.forbidden)).toBeTruthy()
  const nav = within(screen.getByRole('navigation', { name: fr.nav.label }))
  expect(nav.queryByRole('link', { name: fr.nav.devices })).toBeNull()
  expect(nav.queryByRole('button', { name: new RegExp(fr.nav.devices) })).toBeNull()
  expect(calls.some((c) => c.path.startsWith('/org/devices') || c.path.startsWith('/org/staff'))).toBe(false)
  expect(screen.queryByRole('button', { name: fr.devices.add })).toBeNull()
})

it('GATE: no back-office access at all (the shell refuses), no device call', async () => {
  const calls = stubApi('GATE')
  renderApp('/devices')
  expect(await screen.findByText(fr.shell.noAccessTitle)).toBeTruthy()
  expect(calls.map((c) => c.path)).toEqual(['/org/me'])
})

// ---- Appareils tab ----

it('list: PENDING first (API order), status badge, fingerprint, last seen; query key and header carry the organizer', async () => {
  const calls = stubApi()
  const { queryClient } = renderApp('/devices')
  const rows = (await screen.findAllByRole('row')).slice(1)
  const cells = rows.map((r) => within(r).getAllByRole('cell').map((c) => c.textContent))
  expect(cells.map((c) => c[0])).toEqual(['PDA 12', 'PDA 1', 'PDA 2', 'PDA 3', 'PDA 5'])
  expect(cells[0]!.slice(1, 3)).toEqual([fr.devices.statuses.PENDING, 'AB12 CD34'])
  expect(cells[1]![1]).toBe(fr.devices.statuses.ACTIVE)
  expect(cells[3]![1]).toBe(fr.devices.statuses.REVOKED)
  expect(cells[2]![4]).toBe(fr.devices.never)
  // Approve only on PENDING, revoke on all but REVOKED.
  expect(within(rows[0]!).getByRole('button', { name: i18n.t('devices.approve.label', { label: 'PDA 12' }) })).toBeTruthy()
  expect(within(rows[1]!).queryByRole('button', { name: i18n.t('devices.approve.label', { label: 'PDA 1' }) })).toBeNull()
  expect(within(rows[3]!).queryAllByRole('button')).toEqual([])

  expect(queryClient.getQueryData(['org', 'org-a', 'devices'])).toEqual(DEVICES)
  expect(calls.find((c) => c.path === '/org/devices')!.org).toBe('org-a')
})

it('list: empty state with the brand mark', async () => {
  stubApi('ADMIN', (c) => (c.path === '/org/devices' ? Response.json([]) : undefined))
  renderApp('/devices')
  expect(await screen.findByText(fr.devices.emptyTitle)).toBeTruthy()
  expect(screen.getByText(fr.devices.empty)).toBeTruthy()
})

it('enrol: the QR encodes exactly enrol_code; countdown to expires_at, expired state, and a new code on regenerate', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  const calls = stubApi()
  const { queryClient } = renderApp('/devices', user)
  await user.click(await screen.findByRole('button', { name: fr.devices.add }))
  const dialog = await screen.findByRole('dialog', { name: fr.devices.enrol.title })
  await waitFor(() => expect(within(dialog).getByRole('timer')).toBeTruthy())
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/devices/enrol-codes', org: 'org-a', body: undefined }])

  // The same QR as one made from the code alone, and not the one of any other value.
  const expected = render(<QRCode value="eyJ.enrol-1.sig" size={208} level="M" />)
  const other = render(<QRCode value="eyJ.enrol-2.sig" size={208} level="M" />)
  const want = qrPaths(expected.container)
  expect(qrPaths(within(dialog).getByRole('img', { name: fr.devices.enrol.qr }))).toEqual(want)
  expect(cacheDump(queryClient)).not.toContain('enrol-1') // on screen, not in the mutation cache
  expect(qrPaths(other.container)).not.toEqual(want)
  expected.unmount()
  other.unmount()

  expect(within(dialog).getByRole('timer').textContent).toMatch(/01:(30|29)$/)
  await act(() => vi.advanceTimersByTimeAsync(31_000))
  expect(within(dialog).getByRole('timer').textContent).toMatch(/00:(59|58)$/)
  await act(() => vi.advanceTimersByTimeAsync(60_000))
  expect(within(dialog).queryByRole('timer')).toBeNull()
  expect(within(dialog).getByRole('alert').textContent).toBe(fr.devices.enrol.expired)

  await user.click(within(dialog).getByRole('button', { name: fr.devices.enrol.regenerate }))
  await waitFor(() => expect(within(dialog).getByRole('timer').textContent).toMatch(/01:(30|29)$/))
  expect(writes(calls)).toHaveLength(2)
  const fresh = render(<QRCode value="eyJ.enrol-2.sig" size={208} level="M" />)
  expect(qrPaths(within(dialog).getByTitle(fr.devices.enrol.qr).closest('svg')!)).toEqual(qrPaths(fresh.container))
  fresh.unmount()
  // A PENDING device in the list is reported while the QR is up.
  expect(within(dialog).getByRole('status').textContent).toBe(i18n.t('devices.enrol.pending', { count: 1 }))

  await user.click(within(dialog).getByRole('button', { name: fr.devices.enrol.done }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(document.body.innerHTML).not.toContain('enrol-2')
  expect(cacheDump(queryClient)).not.toMatch(/enrol-\d/)
})

it('approve: the dialog shows the fingerprint to match; confirm sends POST approve', async () => {
  const calls = stubApi()
  const { user } = renderApp('/devices')
  await user.click(await screen.findByRole('button', { name: i18n.t('devices.approve.label', { label: 'PDA 12' }) }))
  const dialog = await screen.findByRole('alertdialog', { name: i18n.t('devices.approve.title', { label: 'PDA 12' }) })
  expect(within(dialog).getByText('AB12 CD34')).toBeTruthy()
  expect(writes(calls)).toEqual([])
  await user.click(within(dialog).getByRole('button', { name: fr.devices.approve.confirm }))
  await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/devices/d4/approve', org: 'org-a', body: undefined }])
  expect(await screen.findByText(i18n.t('devices.approve.done', { label: 'PDA 12' }))).toBeTruthy()
})

it('approve: DEVICE_REVOKED stays in the dialog with its copy', async () => {
  stubApi('ADMIN', (c) => (c.path.endsWith('/approve') ? err(409, 'DEVICE_REVOKED') : undefined))
  const { user } = renderApp('/devices')
  await user.click(await screen.findByRole('button', { name: i18n.t('devices.approve.label', { label: 'PDA 12' }) }))
  const dialog = await screen.findByRole('alertdialog')
  await user.click(within(dialog).getByRole('button', { name: fr.devices.approve.confirm }))
  expect(await within(dialog).findByText(fr.errors.DEVICE_REVOKED)).toBeTruthy()
})

it('revoke asks for confirmation: cancel sends nothing, confirm sends POST revoke', async () => {
  const calls = stubApi()
  const { user } = renderApp('/devices')
  const revoke = await screen.findByRole('button', { name: i18n.t('devices.revoke.label', { label: 'PDA 1' }) })
  await user.click(revoke)
  let dialog = await screen.findByRole('alertdialog', { name: i18n.t('devices.revoke.title', { label: 'PDA 1' }) })
  await user.click(within(dialog).getByRole('button', { name: fr.common.cancel }))
  await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  expect(writes(calls)).toEqual([])

  await user.click(revoke)
  dialog = await screen.findByRole('alertdialog')
  await user.click(within(dialog).getByRole('button', { name: fr.devices.revoke.confirm }))
  await waitFor(() => expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/devices/d1/revoke', org: 'org-a', body: undefined }]))
})

// ---- Équipe ----

async function openStaff(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('tab', { name: fr.devices.tabs.staff }))
  return screen.findByRole('tabpanel', { name: fr.devices.tabs.staff })
}

it('staff: the gate team and the cashiers (no back-office member), role, PIN set, status', async () => {
  const { user } = (stubApi(), renderApp('/devices'))
  const panel = await openStaff(user)
  await within(panel).findByText('Karim Bennani')
  const rows = within(panel).getAllByRole('row').slice(1)
  expect(rows.map((r) => within(r).getAllByRole('cell').slice(0, 4).map((c) => c.textContent))).toEqual([
    ['Karim Bennani', fr.staff.roles.GATE, fr.staff.hasPin, fr.staff.active],
    ['Nadia Alaoui', fr.staff.roles.SUPERVISOR, fr.staff.hasPin, fr.staff.inactive],
    ['Omar Tazi', fr.staff.roles.CASHIER, fr.staff.phoneLogin, fr.staff.active],
  ])
  expect(within(panel).queryByText('Samira Admin')).toBeNull()
  // A CASHIER has no PIN to reset (`/pin` answers 404); deactivating still works.
  const cashier = within(rows[2]!)
  expect(cashier.queryByRole('button', { name: i18n.t('staff.resetPinLabel', { name: 'Omar Tazi' }) })).toBeNull()
  expect(cashier.getByRole('button', { name: i18n.t('staff.deactivateLabel', { name: 'Omar Tazi' }) })).toBeTruthy()
})

async function openCashierForm(user: ReturnType<typeof userEvent.setup>) {
  const panel = await openStaff(user)
  await user.click(within(panel).getByRole('button', { name: fr.staff.add }))
  const form = await screen.findByRole('dialog', { name: fr.staff.newTitle })
  expect(within(form).queryByLabelText(fr.staff.phone)).toBeNull() // GATE by default: no phone
  await user.type(within(form).getByLabelText(fr.staff.name), 'Omar Tazi')
  await user.click(within(form).getByRole('combobox', { name: fr.staff.role }))
  await user.click(await screen.findByRole('option', { name: fr.staff.roles.CASHIER }))
  return form
}

it('staff: add a Caissier: POST {name, role: CASHIER, phone} in E.164, no PIN dialog, the list refetches', async () => {
  const calls = stubApi()
  const { user } = renderApp('/devices')
  const form = await openCashierForm(user)
  await user.type(within(form).getByLabelText(fr.staff.phone), '06 12 34 56 78')
  await user.click(within(form).getByRole('button', { name: fr.staff.createCashier }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/staff', org: 'org-a', body: { name: 'Omar Tazi', role: 'CASHIER', phone: '+212612345678' } }])
  expect(await screen.findByText(i18n.t('staff.cashierCreated', { name: 'Omar Tazi' }))).toBeTruthy()
  expect(screen.queryByRole('dialog', { name: /PIN/ })).toBeNull()
  await waitFor(() => expect(calls.filter((c) => c.path === '/org/staff' && c.method === 'GET').length).toBeGreaterThan(1))
})

it('staff: a Caissier needs a valid phone; nothing is sent without one', async () => {
  const calls = stubApi()
  const { user } = renderApp('/devices')
  const form = await openCashierForm(user)
  await user.click(within(form).getByRole('button', { name: fr.staff.createCashier }))
  expect(await within(form).findByText(fr.staff.phoneInvalid)).toBeTruthy()
  await user.type(within(form).getByLabelText(fr.staff.phone), '12345')
  await user.click(within(form).getByRole('button', { name: fr.staff.createCashier }))
  expect(within(form).getByLabelText(fr.staff.phone).getAttribute('aria-invalid')).toBe('true')
  expect(writes(calls)).toEqual([])
})

it('staff: a phone already in the team (422 TAKEN on phone) is marked on the phone field; no PIN dialog', async () => {
  stubApi('ADMIN', (c) =>
    c.method === 'POST' && c.path === '/org/staff' ? err(422, 'VALIDATION_FAILED', { fields: { phone: 'taken' }, reason: 'TAKEN' }) : undefined,
  )
  const { user } = renderApp('/devices')
  const form = await openCashierForm(user)
  await user.type(within(form).getByLabelText(fr.staff.phone), '0612345678')
  await user.click(within(form).getByRole('button', { name: fr.staff.createCashier }))
  expect(await within(form).findByText(fr.staff.phoneTaken)).toBeTruthy()
  expect(within(form).getByLabelText(fr.staff.phone).getAttribute('aria-invalid')).toBe('true')
  expect(within(form).queryByText(fr.common.fieldInvalid)).toBeNull()
  expect(screen.queryByRole('dialog', { name: /PIN/ })).toBeNull()
})

it('staff: add → POST {name, role}, the PIN is shown once and never reaches the query cache; closing clears it', async () => {
  const log = [vi.spyOn(console, 'log'), vi.spyOn(console, 'info'), vi.spyOn(console, 'debug'), vi.spyOn(console, 'warn')]
  const calls = stubApi()
  const { user, queryClient } = renderApp('/devices')
  const panel = await openStaff(user)
  await user.click(within(panel).getByRole('button', { name: fr.staff.add }))
  const form = await screen.findByRole('dialog', { name: fr.staff.newTitle })
  await user.type(within(form).getByLabelText(fr.staff.name), '  Yazid   Aqel ')
  await user.click(within(form).getByRole('combobox', { name: fr.staff.role }))
  await user.click(await screen.findByRole('option', { name: fr.staff.roles.SUPERVISOR }))
  await user.click(within(form).getByRole('button', { name: fr.staff.create }))

  const pinDialog = await screen.findByRole('dialog', { name: i18n.t('staff.pin.title', { name: 'Yazid Aqel' }) })
  expect(within(pinDialog).getByText('4821')).toBeTruthy()
  expect(within(pinDialog).getByText(fr.staff.pin.once)).toBeTruthy()
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/staff', org: 'org-a', body: { name: 'Yazid Aqel', role: 'SUPERVISOR' } }])
  expect(cacheDump(queryClient)).not.toContain('4821') // shown, yet not cached

  await user.click(within(pinDialog).getByRole('button', { name: fr.staff.pin.done }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(screen.queryByText('4821')).toBeNull()
  expect(document.body.innerHTML).not.toContain('4821')
  await waitFor(() => expect(calls.filter((c) => c.path === '/org/staff' && c.method === 'GET').length).toBeGreaterThan(1)) // list refetched
  expect(cacheDump(queryClient)).not.toContain('4821')
  log.forEach((spy) => expect(JSON.stringify(spy.mock.calls)).not.toContain('4821'))
})

it('staff: a name the server refuses (422 on name) is marked on the field; no PIN dialog', async () => {
  stubApi('ADMIN', (c) => (c.method === 'POST' && c.path === '/org/staff' ? err(422, 'VALIDATION_FAILED', { fields: { name: 'pattern' } }) : undefined))
  const { user } = renderApp('/devices')
  const panel = await openStaff(user)
  await user.click(within(panel).getByRole('button', { name: fr.staff.add }))
  const form = await screen.findByRole('dialog', { name: fr.staff.newTitle })
  await user.type(within(form).getByLabelText(fr.staff.name), 'محمد علي')
  await user.click(within(form).getByRole('button', { name: fr.staff.create }))
  expect(await within(form).findByText(fr.staff.nameInvalid)).toBeTruthy()
  expect(within(form).getByLabelText(fr.staff.name).getAttribute('aria-invalid')).toBe('true')
  expect(screen.queryByRole('dialog', { name: /PIN/ })).toBeNull()
})

it('staff: reset PIN asks first, then shows the new PIN in the same dialog, uncached', async () => {
  const calls = stubApi()
  const { user, queryClient } = renderApp('/devices')
  const panel = await openStaff(user)
  await user.click(await within(panel).findByRole('button', { name: i18n.t('staff.resetPinLabel', { name: 'Karim Bennani' }) }))
  const confirm = await screen.findByRole('alertdialog', { name: i18n.t('staff.resetTitle', { name: 'Karim Bennani' }) })
  expect(writes(calls)).toEqual([])
  await user.click(within(confirm).getByRole('button', { name: fr.staff.resetConfirm }))
  const pinDialog = await screen.findByRole('dialog', { name: i18n.t('staff.pin.title', { name: 'Karim Bennani' }) })
  expect(within(pinDialog).getByText('0937')).toBeTruthy()
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/staff/s1/pin', org: 'org-a', body: undefined }])
  expect(cacheDump(queryClient)).not.toContain('0937')
  await user.keyboard('{Escape}')
  await waitFor(() => expect(document.body.innerHTML).not.toContain('0937'))
})

it('staff: deactivate sends PATCH {active: false}', async () => {
  const calls = stubApi()
  const { user } = renderApp('/devices')
  const panel = await openStaff(user)
  await user.click(await within(panel).findByRole('button', { name: i18n.t('staff.deactivateLabel', { name: 'Karim Bennani' }) }))
  await waitFor(() => expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/org/staff/s1', org: 'org-a', body: { active: false } }]))
  expect(await screen.findByText(i18n.t('staff.deactivated', { name: 'Karim Bennani' }))).toBeTruthy()
  // An inactive member is offered Réactiver instead.
  expect(within(panel).getByRole('button', { name: i18n.t('staff.reactivateLabel', { name: 'Nadia Alaoui' }) })).toBeTruthy()
})

// ---- B2 Appareils (BO-9) ----

async function openB2Devices(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('tab', { name: fr.events.tabs.devices }))
  const panel = await screen.findByRole('tabpanel', { name: fr.events.tabs.devices })
  await within(panel).findByRole('table')
  return panel
}
const gateSelect = (label: string) => screen.getByRole('combobox', { name: i18n.t('events.devices.gateLabel', { label }) })
async function pickGate(user: ReturnType<typeof userEvent.setup>, label: string, option: string) {
  await user.click(gateSelect(label))
  await user.click(await screen.findByRole('option', { name: option }))
}
const P2 = i18n.t('events.devices.gateOption', { code: 'P2', name: 'Porte Nord' })

it('B2 Appareils: assigned devices (revoked kept), then free ACTIVE ones; manifest, wipe; PUT sends every assigned row', async () => {
  const calls = stubApi()
  const { user } = renderApp('/events/1')
  const panel = await openB2Devices(user)
  const rows = within(panel).getAllByRole('row').slice(1)
  expect(rows.map((r) => within(r).getAllByRole('cell')[0]!.textContent)).toEqual(['PDA 1', `PDA 3${fr.devices.statuses.REVOKED}`, 'PDA 2', 'PDA 5'])
  expect(gateSelect('PDA 1').textContent).toBe(i18n.t('events.devices.gateOption', { code: 'P1', name: 'Porte Marathon' }))
  expect(gateSelect('PDA 2').textContent).toBe(fr.events.devices.none)
  expect(within(rows[0]!).getByText('v3')).toBeTruthy()
  expect(within(rows[1]!).getByText(fr.events.devices.manifestNone)).toBeTruthy()
  // Last seen and wipe ack in the event's zone; none yet → Jamais / Non.
  const [, , , seen, wiped] = within(rows[0]!).getAllByRole('cell').map((c) => c.textContent)
  expect(seen).toBe(formatDateTime('2026-11-14T23:10:00Z', 'fr', EVENT.timezone))
  expect(wiped).toBe(formatDateTime('2026-11-14T23:30:00Z', 'fr', EVENT.timezone))
  expect(within(rows[1]!).getAllByRole('cell').slice(3).map((c) => c.textContent)).toEqual([fr.events.devices.never, fr.events.devices.wipedNo])
  // A revoked device can only stay on its gate or leave.
  await user.click(gateSelect('PDA 3'))
  expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual([fr.events.devices.none, P2])
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('combobox', { name: i18n.t('events.devices.gateLabel', { label: 'PDA 12' }) })).toBeNull() // PENDING: not offered

  const save = within(panel).getByRole('button', { name: fr.common.save })
  expect(save).toHaveProperty('disabled', true)
  await pickGate(user, 'PDA 2', P2)
  expect(within(panel).getByText(i18n.t('events.devices.changed', { count: 1 }))).toBeTruthy()
  await user.click(save)
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]).toEqual({
    method: 'PUT',
    path: '/org/events/1/devices',
    org: 'org-a',
    body: { assignments: [{ device_id: 'd1', gate_id: 'g1' }, { device_id: 'd3', gate_id: 'g2' }, { device_id: 'd2', gate_id: 'g2' }] },
  })
  expect(await screen.findByText(fr.events.devices.saved)).toBeTruthy()
})

it('B2 Appareils: a 422 on assignments[i] is shown on the row of the i-th device sent', async () => {
  stubApi('ADMIN', (c) =>
    c.method === 'PUT'
      ? err(422, 'VALIDATION_FAILED', { fields: { 'assignments[1].device_id': 'not active', 'assignments[2].gate_id': 'other venue' } })
      : undefined,
  )
  const { user } = renderApp('/events/1')
  const panel = await openB2Devices(user)
  await pickGate(user, 'PDA 5', P2)
  await user.click(within(panel).getByRole('button', { name: fr.common.save }))
  // Sent: [d1, d3, d5] → [1] is PDA 3, [2] is PDA 5.
  expect(await within(rowOf(`PDA 3${fr.devices.statuses.REVOKED}`)).findByText(fr.events.devices.rowDevice)).toBeTruthy()
  expect(within(rowOf('PDA 5')).getByText(fr.events.devices.rowGate)).toBeTruthy()
  expect(gateSelect('PDA 5').getAttribute('aria-invalid')).toBe('true')
  expect(gateSelect('PDA 1').getAttribute('aria-invalid')).toBe('false')
  expect(within(rowOf('PDA 1')).queryByText(fr.events.devices.rowDevice)).toBeNull()
})

it('B2 Appareils: DEVICE_IN_USE names the devices that cannot be removed and marks their rows', async () => {
  stubApi('ADMIN', (c) => (c.method === 'PUT' ? err(409, 'DEVICE_IN_USE', { device_ids: ['d1'] }) : undefined))
  const { user } = renderApp('/events/1')
  const panel = await openB2Devices(user)
  await pickGate(user, 'PDA 1', fr.events.devices.none)
  await user.click(within(panel).getByRole('button', { name: fr.common.save }))
  expect(await within(panel).findByText(i18n.t('events.devices.inUse', { count: 1, labels: 'PDA 1' }))).toBeTruthy()
  expect(within(rowOf('PDA 1')).getByText(fr.events.devices.rowInUse)).toBeTruthy()
})

it('B2 Appareils: a closed event is read-only', async () => {
  stubApi('ADMIN', (c) => (c.method === 'GET' && c.path === '/org/events/1' ? Response.json({ ...EVENT, status: 'CLOSED' }) : undefined))
  const { user } = renderApp('/events/1')
  const panel = await openB2Devices(user)
  expect(within(panel).getByText(fr.events.devices.closed)).toBeTruthy()
  expect(gateSelect('PDA 1').matches(':disabled')).toBe(true)
  expect(within(panel).queryByRole('button', { name: fr.common.save })).toBeNull()
})

it('B2 Appareils: FINANCE has no Appareils tab and no assignment call', async () => {
  const calls = stubApi('FINANCE')
  renderApp('/events/1')
  await screen.findByRole('heading', { name: 'Atlas FC vs Rapid Oued' })
  expect(screen.queryByRole('tab', { name: fr.events.tabs.devices })).toBeNull()
  expect(calls.some((c) => c.path.includes('devices'))).toBe(false)
})
