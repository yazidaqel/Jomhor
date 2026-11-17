import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import { createQueryClient } from '@/lib/queryClient'
import { formatDh } from '@/lib/money'
import { session } from '@/lib/session'
import { addDays, todayIn } from '@/lib/time'
import type { Deposit, Deposits, DepositSession } from './api'

beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  session.clear()
})

const CASA = 'Africa/Casablanca'
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})

// source: contract (04 §Finance, GET /org/finance/deposits; demo 5a: Guichet Nord closed at 700 DH, Guichet Sud at 300 DH).
const NORD: DepositSession = { id: 's-nord', label: 'Guichet Nord', staff: { id: 'st-1', name: 'Salma' }, closed_at: '2026-11-25T18:00:00Z', declared_mad: 70_000 }
const SUD: DepositSession = { id: 's-sud', label: 'Guichet Sud', staff: { id: 'st-2', name: 'Omar' }, closed_at: '2026-11-25T18:30:00Z', declared_mad: 30_000 }
const EST: DepositSession = { id: 's-est', label: 'Guichet Est', staff: { id: 'st-2', name: 'Omar' }, closed_at: '2026-11-20T18:00:00Z', declared_mad: 50_000 }
const LIVE: Deposit = {
  id: 'd-2',
  deposited_on: '2026-11-21',
  amount_mad: 50_000,
  reference: 'Bordereau 448700',
  recorded_by: { id: 'u-2', name: 'Nadia' },
  recorded_at: '2026-11-21T10:00:00Z',
  voided_at: null,
  voided_by: null,
  void_reason: null,
  sessions: [EST],
  declared_mad: 50_000,
  variance_mad: 0,
}
const VOIDED: Deposit = {
  ...LIVE,
  id: 'd-1',
  deposited_on: '2026-11-20',
  amount_mad: 49_000,
  reference: 'Bordereau 448699',
  voided_at: '2026-11-20T12:00:00Z',
  voided_by: { id: 'u-1', name: 'Samira' },
  void_reason: 'montant mal saisi',
  sessions: [],
  declared_mad: null,
  variance_mad: null,
}
const GAP: Deposit = { ...LIVE, id: 'd-3', deposited_on: '2026-11-22', amount_mad: 49_000, reference: 'Bordereau 448750', variance_mad: -1000 }
const DEPOSITS: Deposits = { deposits: [GAP, LIVE, VOIDED], undeposited: [NORD, SUD] }

type Call = { method: string; path: string; org: string | null; body: unknown }
type Handler = (c: Call) => Response | undefined

function stubApi(role: Role, handle?: Handler) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const method = init?.method ?? 'GET'
      const call = { method, path, org: new Headers(init?.headers).get('X-Organizer-Id'), body: init?.body ? JSON.parse(String(init.body)) : undefined }
      calls.push(call)
      const custom = handle?.(call)
      if (custom) return custom
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events') return Response.json([])
      if (path === '/org/venues') return Response.json([{ id: 'v-1', name: 'Grand Stade', city: 'Casablanca', capacity: 45000, timezone: CASA }])
      if (path.startsWith('/org/finance/deposits?')) return Response.json(DEPOSITS)
      if (path.startsWith('/org/finance/cash?'))
        return Response.json({ from: '', to: '', timezone: CASA, days: [], sessions: [], totals: { collected_mad: 0, declared_mad: 0, variance_mad: 0, open_sessions: 0, undeposited_mad: 0 } })
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

function renderApp(path: string) {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const queryClient = createQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}

const dh = (centimes: number) => i18n.t('sales.amount', { amount: formatDh(centimes, 'fr') })
const signed = (centimes: number) => i18n.t('sales.amount', { amount: formatDh(centimes, 'fr', 'exceptZero') })
const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const section = (title: string) => screen.getByRole('heading', { name: title }).closest('[data-slot="card"]') as HTMLElement
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent)
const at = (iso: string) => new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))
const day = (d: string) => new Intl.DateTimeFormat('fr', { timeZone: 'UTC', dateStyle: 'medium' }).format(Date.parse(d))
const todo = () => within(section(fr.finance.deposits.todo.title))
const pick = (s: DepositSession) => i18n.t('finance.deposits.todo.pick', { label: s.label, cashier: s.staff.name })

async function openSheet(user: ReturnType<typeof userEvent.setup>, sessions: DepositSession[] = [NORD, SUD]) {
  await screen.findByRole('heading', { name: fr.finance.deposits.todo.title })
  for (const s of sessions) await user.click(todo().getByRole('checkbox', { name: pick(s) }))
  await user.click(todo().getByRole('button', { name: fr.finance.deposits.record }))
  return within(await screen.findByRole('dialog'))
}

it('FINANCE: tick both tills → sheet; "12,5" and "990,00" read as integer centimes, the gap previews live, POST sends centimes', async () => {
  const user = userEvent.setup()
  const calls = stubApi('FINANCE', (c) => (c.method === 'POST' && c.path === '/org/finance/deposits' ? Response.json({ ...LIVE, id: 'd-9', reference: 'Bordereau 448812' }, { status: 201 }) : undefined))
  renderApp('/finance/deposits')

  // À déposer: oldest first as sent, closed time in the venue zone.
  await screen.findByRole('heading', { name: fr.finance.deposits.todo.title })
  expect(todo().getAllByRole('row').slice(1).map(cells)).toEqual([
    ['', 'Guichet Nord', 'Salma', at(NORD.closed_at), dh(70_000)],
    ['', 'Guichet Sud', 'Omar', at(SUD.closed_at), dh(30_000)],
  ])
  expect((todo().getByRole('button', { name: fr.finance.deposits.record }) as HTMLButtonElement).disabled).toBe(true)
  const sheet = await openSheet(user)
  expect(sheet.getByText(spaced(dh(100_000)))).toBeTruthy() // Déclaré Σ
  const variance = () => screen.getByTestId('deposit-variance')
  expect(variance().textContent).toBe(fr.finance.deposits.sheet.varianceWaiting)

  await user.type(sheet.getByLabelText(fr.finance.deposits.sheet.amount), '12,5')
  expect(variance().textContent).toBe(signed(1250 - 100_000)) // 1250 centimes, not 1249.9999
  await user.clear(sheet.getByLabelText(fr.finance.deposits.sheet.amount))
  await user.type(sheet.getByLabelText(fr.finance.deposits.sheet.amount), '990,00')
  expect(variance().textContent).toMatch(/^-10,00\sDH$/u)
  expect(variance().className).toContain('text-destructive')
  await user.type(sheet.getByLabelText(fr.finance.deposits.sheet.reference), '  Bordereau 448812 ')
  await user.click(sheet.getByRole('button', { name: fr.finance.deposits.sheet.submit }))

  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const post = calls.find((c) => c.method === 'POST')!
  expect(post).toEqual({
    method: 'POST',
    path: '/org/finance/deposits',
    org: 'org-a',
    body: { deposited_on: todayIn(CASA), amount_mad: 99_000, reference: 'Bordereau 448812', session_ids: ['s-nord', 's-sud'] },
  })
  expect(await screen.findByText(i18n.t('finance.deposits.sheet.done', { reference: 'Bordereau 448812' }))).toBeTruthy()
  // Every finance query of the organizer is refetched.
  await waitFor(() => expect(calls.filter((c) => c.path.startsWith('/org/finance/deposits?'))).toHaveLength(2))
})

it('the sheet refuses a bad amount, an empty reference and a future date; nothing is sent', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN')
  renderApp('/finance/deposits')
  const sheet = await openSheet(user, [NORD])

  await user.type(sheet.getByLabelText(fr.finance.deposits.sheet.amount), '12,345')
  fireEvent.change(sheet.getByLabelText(fr.finance.deposits.sheet.deposited_on), { target: { value: addDays(todayIn(CASA), 1) } })
  await user.click(sheet.getByRole('button', { name: fr.finance.deposits.sheet.submit }))
  expect(await sheet.findByText(fr.finance.deposits.sheet.amountInvalid)).toBeTruthy()
  expect(sheet.getByText(fr.finance.deposits.sheet.referenceInvalid)).toBeTruthy()
  expect(sheet.getByText(fr.finance.deposits.sheet.FUTURE)).toBeTruthy()
  expect(screen.getByTestId('deposit-variance').textContent).toBe(fr.finance.deposits.sheet.varianceWaiting)
  expect(calls.filter((c) => c.method === 'POST')).toEqual([])
})

it.each([
  ['DEPOSITED', 'session_ids[1]', 'Guichet Sud'],
  ['OPEN', 'session_ids[0]', 'Guichet Nord'],
] as const)('a 422 %s on %s: its copy on that session row', async (reason, field, label) => {
  const user = userEvent.setup()
  stubApi('FINANCE', (c) =>
    c.method === 'POST' ? Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { [field]: 'invalid' }, reason } }, { status: 422 }) : undefined,
  )
  renderApp('/finance/deposits')
  const sheet = await openSheet(user)
  await user.type(sheet.getByLabelText(fr.finance.deposits.sheet.amount), '1000')
  await user.type(sheet.getByLabelText(fr.finance.deposits.sheet.reference), 'B-2')
  await user.click(sheet.getByRole('button', { name: fr.finance.deposits.sheet.submit }))

  const message = await sheet.findByText(fr.finance.deposits.sheet[reason])
  expect(message.closest('li')!.textContent).toContain(label)
  expect(sheet.getAllByText(fr.finance.deposits.sheet[reason])).toHaveLength(1)
  expect(screen.getByRole('dialog')).toBeTruthy() // stays open
})

it('a 422 BEFORE_CLOSE on deposited_on: its copy on the date field', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE', (c) =>
    c.method === 'POST'
      ? Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { deposited_on: 'invalid' }, reason: 'BEFORE_CLOSE' } }, { status: 422 })
      : undefined,
  )
  renderApp('/finance/deposits')
  const sheet = await openSheet(user, [SUD])
  await user.type(sheet.getByLabelText(fr.finance.deposits.sheet.amount), '300')
  await user.type(sheet.getByLabelText(fr.finance.deposits.sheet.reference), 'B-3')
  await user.click(sheet.getByRole('button', { name: fr.finance.deposits.sheet.submit }))

  const message = await sheet.findByText(fr.finance.deposits.sheet.BEFORE_CLOSE)
  const date = sheet.getByLabelText(fr.finance.deposits.sheet.deposited_on)
  expect(date.getAttribute('aria-invalid')).toBe('true')
  expect(date.getAttribute('aria-describedby')).toBe(message.id)
})

it('the deposits table: date, référence, montant, déclaré, écart (red when ≠ 0), caisses, enregistré par; a voided row struck through with its reason', async () => {
  stubApi('OPS')
  renderApp('/finance/deposits')
  await screen.findByRole('heading', { name: fr.finance.deposits.list.title })
  const rows = within(section(fr.finance.deposits.list.title)).getAllByRole('row').slice(1)
  expect(rows.map(cells)).toEqual([
    [day('2026-11-22'), 'Bordereau 448750', dh(49_000), dh(50_000), spaced(signed(-1000)), 'Guichet Est · Omar', `Nadia${at(LIVE.recorded_at)}`],
    [day('2026-11-21'), 'Bordereau 448700', dh(50_000), dh(50_000), fr.finance.cash.noGap, 'Guichet Est · Omar', `Nadia${at(LIVE.recorded_at)}`],
    [day('2026-11-20'), 'Bordereau 448699', dh(49_000), '', '', i18n.t('finance.deposits.list.voided', { reason: 'montant mal saisi' }), `Nadia${at(LIVE.recorded_at)}`],
  ])
  const gap = (row: HTMLElement) => within(row).getAllByRole('cell')[4]!
  expect(gap(rows[0]!).className).toContain('text-destructive')
  expect(gap(rows[1]!).className).not.toContain('text-destructive')
  expect(within(rows[2]!).getAllByRole('cell')[1]!.className).toContain('line-through')
})

it('OPS reads only: no checkbox, no "Enregistrer un dépôt", no "Annuler"', async () => {
  stubApi('OPS')
  renderApp('/finance/deposits')
  await screen.findByRole('heading', { name: fr.finance.deposits.list.title })
  expect(screen.getByText('Guichet Nord')).toBeTruthy()
  expect(screen.queryByRole('checkbox')).toBeNull()
  expect(screen.queryByRole('button', { name: fr.finance.deposits.record })).toBeNull()
  expect(screen.queryByRole('button', { name: i18n.t('finance.deposits.void.label', { reference: LIVE.reference }) })).toBeNull()
})

it('FINANCE records but cannot void', async () => {
  stubApi('FINANCE')
  renderApp('/finance/deposits')
  await screen.findByRole('heading', { name: fr.finance.deposits.list.title })
  expect(todo().getByRole('button', { name: fr.finance.deposits.record })).toBeTruthy()
  expect(screen.queryByRole('button', { name: i18n.t('finance.deposits.void.label', { reference: LIVE.reference }) })).toBeNull()
})

it('ADMIN voids a live deposit with a reason (required); a voided row offers no Annuler', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', (c) =>
    c.method === 'POST' && c.path === '/org/finance/deposits/d-2/void' ? Response.json({ ...LIVE, voided_at: '2026-11-26T10:00:00Z', void_reason: 'montant mal saisi', sessions: [] }) : undefined,
  )
  renderApp('/finance/deposits')
  await screen.findByRole('heading', { name: fr.finance.deposits.list.title })
  expect(screen.queryByRole('button', { name: i18n.t('finance.deposits.void.label', { reference: VOIDED.reference }) })).toBeNull()
  await user.click(screen.getByRole('button', { name: i18n.t('finance.deposits.void.label', { reference: LIVE.reference }) }))

  const dialog = within(await screen.findByRole('dialog'))
  expect(dialog.getByText(fr.finance.deposits.void.description)).toBeTruthy()
  await user.click(dialog.getByRole('button', { name: fr.finance.deposits.void.submit }))
  expect(await dialog.findByText(fr.finance.deposits.void.reasonInvalid)).toBeTruthy()
  expect(calls.filter((c) => c.method === 'POST')).toEqual([])

  await user.type(dialog.getByLabelText(fr.finance.deposits.void.reason), 'montant mal saisi')
  await user.click(dialog.getByRole('button', { name: fr.finance.deposits.void.submit }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(calls.filter((c) => c.method === 'POST')).toEqual([{ method: 'POST', path: '/org/finance/deposits/d-2/void', org: 'org-a', body: { reason: 'montant mal saisi' } }])
})

it('query keys start with org, <organizer>; requests carry the same X-Organizer-Id; the range is sent as local dates', async () => {
  const calls = stubApi('FINANCE')
  const { queryClient } = renderApp('/finance/deposits')
  await screen.findByRole('heading', { name: fr.finance.deposits.list.title })
  const keys = queryClient
    .getQueryCache()
    .getAll()
    .map((q) => q.queryKey)
    .filter((k) => k[0] !== 'me')
  expect(keys.length).toBeGreaterThan(0)
  for (const k of keys) expect(k.slice(0, 2)).toEqual(['org', 'org-a'])
  expect(keys).toContainEqual(['org', 'org-a', 'finance', 'deposits', { from: addDays(todayIn(CASA), -29), to: todayIn(CASA) }])
  for (const c of calls.filter((c) => c.path.startsWith('/org/') && c.path !== '/org/me')) expect(c.org).toBe('org-a')
})

it('B8 tabs: Encaissements and Dépôts, one route each; /finance opens Encaissements', async () => {
  const user = userEvent.setup()
  stubApi('OPS')
  const { router } = renderApp('/finance')
  await waitFor(() => expect(router.state.location.pathname).toBe('/finance/cash'))
  expect((await screen.findByRole('tab', { name: fr.finance.tabs.cash })).getAttribute('aria-selected')).toBe('true')
  await user.click(screen.getByRole('tab', { name: fr.finance.tabs.deposits }))
  await waitFor(() => expect(router.state.location.pathname).toBe('/finance/deposits'))
  expect(await screen.findByRole('heading', { name: fr.finance.deposits.todo.title })).toBeTruthy()
})

it('nothing to deposit and no deposit in the range: both cards say so', async () => {
  stubApi('FINANCE', (c) => (c.path.startsWith('/org/finance/deposits?') ? Response.json({ deposits: [], undeposited: [] }) : undefined))
  renderApp('/finance/deposits')
  expect(await screen.findByText(fr.finance.deposits.todo.emptyTitle)).toBeTruthy()
  expect(screen.getByText(fr.finance.deposits.list.emptyTitle)).toBeTruthy()
  expect(screen.queryByRole('button', { name: fr.finance.deposits.record })).toBeNull()
})
