import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
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
import type { Settlement, SettlementDetail, Settlements } from './api'

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

// source: contract (docs/04-api.md §Finance and the settlements rows, figures of plan lot-5 §13.1 exit 5b at 500 bp,
// VAT 20 %). To be replaced by answers captured from the real API once BE-71 is merged (BO-28 real run).
const row = (r: Partial<Settlement> & Pick<Settlement, 'id' | 'day'>): Settlement => ({
  status: 'DUE',
  overdue: false,
  collected_mad: 0,
  till_mad: 0,
  fee_rate_bp: 500,
  fee_ht_mad: 0,
  fee_vat_mad: 0,
  carried_in_mad: 0,
  payout_mad: 0,
  due_on: addDays(r.day, 2),
  paid_at: null,
  paid_by: null,
  payout_reference: null,
  invoice: null,
  ...r,
})
// Sat 28: 200 DH online, carries Friday's −48 DH: 200 − 10 − 2 − 48 = 140 DH, due Mon 30.
const SAT = row({ id: 's-28', day: '2026-11-28', collected_mad: 20_000, fee_ht_mad: 1000, fee_vat_mad: 200, carried_in_mad: -4800, payout_mad: 14_000, due_on: '2026-11-30', invoice: { id: 'i-5', number: 'JMH-2026-000005' } })
// Fri 27: tills only, 800 DH: fees 40 + 8 DH, nothing to pay, −48 DH carried.
const FRI = row({ id: 's-27', day: '2026-11-27', status: 'NONE', till_mad: 80_000, fee_ht_mad: 4000, fee_vat_mad: 800, due_on: '2026-11-30', invoice: { id: 'i-4', number: 'JMH-2026-000004' } })
// Thu 26: 4 000 DH online + 1 000 DH at the tills: fees ⌊500 000 × 500 ÷ 10 000⌋ = 250 DH + 50 DH VAT → 3 700 DH, due Mon 30.
const THU = row({ id: 's-26', day: '2026-11-26', collected_mad: 400_000, till_mad: 100_000, fee_ht_mad: 25_000, fee_vat_mad: 5000, payout_mad: 370_000, due_on: '2026-11-30', invoice: { id: 'i-3', number: 'JMH-2026-000003' } })
const LATE = row({ id: 's-10', day: '2026-11-10', overdue: true, collected_mad: 100_000, fee_ht_mad: 5000, fee_vat_mad: 1000, payout_mad: 94_000, due_on: '2026-11-12', invoice: { id: 'i-2', number: 'JMH-2026-000002' } })
const PAID = row({
  id: 's-09',
  day: '2026-11-09',
  status: 'PAID',
  collected_mad: 50_000,
  fee_ht_mad: 2500,
  fee_vat_mad: 500,
  payout_mad: 47_000,
  due_on: '2026-11-11',
  paid_at: '2026-11-12T09:00:00Z',
  paid_by: { id: 'u-2', name: 'Finance Dev' },
  payout_reference: 'VIR 0031',
  invoice: { id: 'i-1', number: 'JMH-2026-000001' },
})
const LIST: Settlements = {
  from: '2026-10-19',
  to: '2026-11-17',
  timezone: CASA,
  settings: { legal_name: 'Atlas Football Club SA', ice: '001234567000089', rib_last4: '4821', fee_rate_bp: 500, start_on: '2026-11-01' },
  open: { since: '2026-11-29T00:00:00Z', collected_mad: 25_000, till_mad: 0, payments: 1 },
  refunds_required: { count: 2, amount_mad: 30_000 },
  // Every DUE statement, whatever the range: the three below plus one older (250 DH), outside the shown range.
  due: { count: 4, amount_mad: 14_000 + 370_000 + 94_000 + 25_000 },
  vat_rate_bp: 2000,
  settlements: [SAT, FRI, THU, LATE, PAID],
}
const line = (l: Partial<SettlementDetail['lines'][number]> & Pick<SettlementDetail['lines'][number], 'title' | 'method' | 'collector' | 'payments' | 'amount_mad'>) => ({
  event_id: null,
  product_id: null,
  collector_name: l.collector === 'POS' ? 'Guichet' : l.collector,
  ...l,
})
// Thu 26's lines, by title, then method, then collector: card 3 000 DH on 2 events + 400 DH pass installment, wallet
// 150 DH membership, network cash 450 DH, tills 1 000 DH.
const THU_DETAIL: SettlementDetail = {
  ...THU,
  lines: [
    line({ product_id: 'p-pass', title: 'Abonnement saison', method: 'CARD', collector: 'PAYZONE', collector_name: 'Payzone', payments: 1, amount_mad: 40_000 }),
    line({ product_id: 'p-member', title: 'Adhésion 2026-27', method: 'WALLET', collector: 'PAYZONE', collector_name: 'Payzone', payments: 1, amount_mad: 15_000 }),
    line({ event_id: 59, title: 'Atlas FC · FUS Rabat', method: 'CARD', collector: 'PAYZONE', collector_name: 'Payzone', payments: 12, amount_mad: 180_000 }),
    line({ event_id: 59, title: 'Atlas FC · FUS Rabat', method: 'POS_CASH', collector: 'POS', payments: 5, amount_mad: 100_000 }),
    line({ event_id: 60, title: 'Atlas FC · Wydad', method: 'CARD', collector: 'PAYZONE', collector_name: 'Payzone', payments: 8, amount_mad: 120_000 }),
    line({ event_id: 60, title: 'Atlas FC · Wydad', method: 'CASH_AGENCY', collector: 'FAKE_CASH', collector_name: 'Cash Plus', payments: 3, amount_mad: 45_000 }),
  ],
}
const PAID_ROW: Settlement = { ...THU, status: 'PAID', paid_at: '2026-11-30T09:00:00Z', paid_by: { id: 'u-2', name: 'Finance Dev' }, payout_reference: 'VIR 0042' }
const ERR_STATUS = { code: 'SETTLEMENT_STATUS', message: 'Nothing to pay', details: { status: 'NONE' } }

type Call = { method: string; path: string; org: string | null; auth: string | null; body: unknown }
type Handler = (c: Call) => Response | undefined

function stubApi(role: Role, handle?: Handler) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const method = init?.method ?? 'GET'
      const headers = new Headers(init?.headers)
      const call = { method, path, org: headers.get('X-Organizer-Id'), auth: headers.get('Authorization'), body: init?.body ? JSON.parse(String(init.body)) : undefined }
      calls.push(call)
      const custom = handle?.(call)
      if (custom) return custom
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events') return Response.json([])
      if (path === '/org/venues') return Response.json([{ id: 'v-1', name: 'Grand Stade', city: 'Casablanca', capacity: 45000, timezone: CASA }])
      if (path.startsWith('/org/finance/settlements?')) return Response.json(LIST)
      if (path === '/org/finance/settlements/s-26') return Response.json(THU_DETAIL)
      if (path.startsWith('/org/finance/settlements/s-')) {
        const r = LIST.settlements.find((s) => s.id === path.split('/').at(-1))
        if (r) return Response.json({ ...r, lines: [] })
      }
      if (path.startsWith('/org/finance/deposits?')) return Response.json({ deposits: [], undeposited: [] })
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
/** What getByText compares against: the DOM text with its narrow no-break spaces collapsed. */
const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const day = (d: string) => new Intl.DateTimeFormat('fr', { timeZone: 'UTC', dateStyle: 'medium' }).format(Date.parse(d))
const section = (title: string) => screen.getByRole('heading', { name: title }).closest('[data-slot="card"]') as HTMLElement
const cells = (r: HTMLElement) => within(r).getAllByRole('cell').map((c) => c.textContent)
const listCalls = (calls: Call[]) => calls.filter((c) => c.path.startsWith('/org/finance/settlements?'))
const table = () => within(section(fr.finance.settlements.list.title))
const statement = (d: string) => i18n.t('finance.settlements.list.open', { day: day(d) })

async function openSheet(user: ReturnType<typeof userEvent.setup>, d: string) {
  await user.click(await screen.findByRole('button', { name: statement(d) }))
  return within(await screen.findByRole('dialog'))
}

it.each(['ADMIN', 'FINANCE'] as const)('%s: /finance opens Relevés, first of three tabs; the query carries the organizer and the range', async (role) => {
  const calls = stubApi(role)
  const { router, queryClient } = renderApp('/finance')

  await waitFor(() => expect(router.state.location.pathname).toBe('/finance/settlements'))
  const tabs = await screen.findAllByRole('tab')
  expect(tabs.map((t) => t.textContent)).toEqual([fr.finance.tabs.settlements, fr.finance.tabs.cash, fr.finance.tabs.deposits])
  expect(tabs[0]!.getAttribute('aria-selected')).toBe('true')

  await screen.findByRole('heading', { name: fr.finance.settlements.list.title })
  const today = todayIn(CASA)
  const range = { from: addDays(today, -29), to: today }
  expect(listCalls(calls)).toEqual([expect.objectContaining({ path: `/org/finance/settlements?${new URLSearchParams(range)}`, org: 'org-a' })])
  expect(queryClient.getQueryCache().find({ queryKey: ['org', 'org-a', 'finance', 'settlements', range], exact: true })).toBeTruthy()
})

it('the contract card: legal name, ICE, the RIB masked to its last 4 digits, the rate, "Gérés par Jomhor"', async () => {
  stubApi('FINANCE')
  renderApp('/finance/settlements')
  await screen.findByRole('heading', { name: fr.finance.settlements.contract.title })
  const card = within(section(fr.finance.settlements.contract.title))
  expect(card.getByText('Atlas Football Club SA')).toBeTruthy()
  expect(card.getByText('001234567000089')).toBeTruthy()
  expect(card.getByText('RIB •••• 4821')).toBeTruthy()
  expect(card.getByText('Frais 5 % HT')).toBeTruthy()
  expect(card.getByText(day('2026-11-01'))).toBeTruthy()
  expect(card.getByText(fr.finance.settlements.contract.managed)).toBeTruthy()
})

it('a RIB longer than 4 digits on the wire still shows 4', async () => {
  stubApi('FINANCE', (c) => (c.path.startsWith('/org/finance/settlements?') ? Response.json({ ...LIST, settings: { ...LIST.settings!, rib_last4: '011780000012345678904821' } }) : undefined))
  renderApp('/finance/settlements')
  expect(await screen.findByText('RIB •••• 4821')).toBeTruthy()
  expect(screen.queryByText(/0117800000/u)).toBeNull()
})

it('settings null: the "pas encore activés" empty state, no KPI, no table', async () => {
  stubApi('ADMIN', (c) => (c.path.startsWith('/org/finance/settlements?') ? Response.json({ ...LIST, settings: null, open: null, settlements: [] }) : undefined))
  renderApp('/finance/settlements')
  expect(await screen.findByText(fr.finance.settlements.inactive.title)).toBeTruthy()
  expect(screen.getByText(fr.finance.settlements.inactive.description)).toBeTruthy()
  expect(screen.queryByText(fr.finance.settlements.kpi.open)).toBeNull()
  expect(screen.queryByText(fr.finance.settlements.contract.title)).toBeNull()
  expect(screen.queryByRole('table')).toBeNull()
  expect(screen.queryByRole('button', { name: fr.finance.settlements.export })).toBeNull()
})

it('KPIs: open from `open`, À recevoir from `due`, refunds from `refunds_required` linking to Ventes', async () => {
  stubApi('FINANCE')
  renderApp('/finance/settlements')
  await screen.findByRole('heading', { name: fr.finance.settlements.list.title })
  const kpi = (label: string) => within(screen.getByText(label).closest('[data-slot="card"]') as HTMLElement)

  const open = kpi(fr.finance.settlements.kpi.open)
  expect(open.getByText(spaced(dh(25_000)))).toBeTruthy()
  expect(open.getByText(spaced(i18n.t('finance.settlements.kpi.openTill', { amount: dh(0) })))).toBeTruthy()
  expect(open.getByText(spaced(i18n.t('finance.settlements.kpi.openSince', { count: 1, date: day('2026-11-29') })))).toBeTruthy()

  const due = kpi(fr.finance.settlements.kpi.due)
  expect(due.getByText(spaced(dh(503_000)))).toBeTruthy()
  expect(due.getByText(spaced(i18n.t('finance.settlements.kpi.dueHint', { count: 4 })))).toBeTruthy()

  const refunds = kpi(fr.finance.settlements.kpi.refunds)
  expect(refunds.getByText(spaced(i18n.t('finance.settlements.kpi.refundsValue', { count: 2, amount: dh(30_000) })))).toBeTruthy()
  expect(refunds.getByRole('link', { name: fr.finance.settlements.kpi.refundsLink }).getAttribute('href')).toBe('/sales')
})

it('rows newest first: en ligne, guichets, frais TTC, report, à verser, échéance, badge (À verser, En retard red, Versé le, Rien à verser), facture', async () => {
  stubApi('FINANCE')
  renderApp('/finance/settlements')
  await screen.findByRole('heading', { name: fr.finance.settlements.list.title })
  const rows = table().getAllByRole('row').slice(1)
  const s = fr.finance.settlements
  expect(rows.map(cells)).toEqual([
    [day('2026-11-28'), dh(20_000), dh(0), dh(1200), dh(-4800), dh(14_000), day('2026-11-30'), s.status.due, 'JMH-2026-000005'],
    [day('2026-11-27'), dh(0), dh(80_000), dh(4800), s.none, dh(0), s.none, s.status.none, 'JMH-2026-000004'],
    [day('2026-11-26'), dh(400_000), dh(100_000), dh(30_000), s.none, dh(370_000), day('2026-11-30'), s.status.due, 'JMH-2026-000003'],
    [day('2026-11-10'), dh(100_000), dh(0), dh(6000), s.none, dh(94_000), day('2026-11-12'), s.status.overdue, 'JMH-2026-000002'],
    [day('2026-11-09'), dh(50_000), dh(0), dh(3000), s.none, dh(47_000), day('2026-11-11'), i18n.t('finance.settlements.status.paidOn', { date: day('2026-11-12') }), 'JMH-2026-000001'],
  ])
  expect(within(rows[3]!).getByText(s.status.overdue).getAttribute('data-variant')).toBe('destructive')
  expect(within(rows[0]!).getAllByRole('cell')[4]!.className).toContain('text-destructive') // a negative carry reads red
})

it('row → sheet: the lines grouped by event or product; the formula shows the server amounts only', async () => {
  const user = userEvent.setup()
  // A payout that does not match the other figures: shown as sent, proof that nothing is recomputed here.
  stubApi('FINANCE', (c) => (c.path === '/org/finance/settlements/s-26' ? Response.json({ ...THU_DETAIL, payout_mad: 123_456 }) : undefined))
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-26')

  expect(await sheet.findByText(spaced(i18n.t('finance.settlements.sheet.title', { day: day('2026-11-26') })))).toBeTruthy()
  const formula = sheet.getByRole('heading', { name: fr.finance.settlements.formula.title }).closest('section') as HTMLElement
  const pairs = [...formula.querySelectorAll('dt')].map((dt) => [dt.textContent, dt.nextElementSibling?.textContent])
  expect(pairs).toEqual([
    [fr.finance.settlements.formula.collected, dh(400_000)],
    [i18n.t('finance.settlements.formula.feeHt', { rate: '5' }), i18n.t('finance.settlements.formula.minus', { amount: dh(25_000) })],
    [i18n.t('finance.settlements.formula.vat', { rate: '20' }), i18n.t('finance.settlements.formula.minus', { amount: dh(5000) })],
    [fr.finance.settlements.formula.carried, dh(0)],
    [fr.finance.settlements.formula.payout, dh(123_456)],
  ])
  expect(within(formula).getByText(spaced(i18n.t('finance.settlements.formula.till', { amount: dh(100_000) })))).toBeTruthy()

  const lines = sheet.getByRole('heading', { name: fr.finance.settlements.lines.title }).closest('section') as HTMLElement
  expect(within(lines).getAllByRole('row').slice(1).map(cells)).toEqual([
    [`Abonnement saison ${fr.finance.settlements.lines.product}`],
    [fr.sales.methods.CARD, 'Payzone', '1', dh(40_000)],
    [`Adhésion 2026-27 ${fr.finance.settlements.lines.product}`],
    [fr.sales.methods.WALLET, 'Payzone', '1', dh(15_000)],
    [`Atlas FC · FUS Rabat ${fr.finance.settlements.lines.event}`],
    [fr.sales.methods.CARD, 'Payzone', '12', dh(180_000)],
    [fr.sales.methods.POS_CASH, 'Guichet', '5', dh(100_000)],
    [`Atlas FC · Wydad ${fr.finance.settlements.lines.event}`],
    [fr.sales.methods.CARD, 'Payzone', '8', dh(120_000)],
    [fr.sales.methods.CASH_AGENCY, 'Cash Plus', '3', dh(45_000)],
  ])
})

it.each(['ADMIN', 'FINANCE'] as const)('%s: "Versement reçu" on a DUE statement, reference 1–100 trimmed → POST, toast, the list refetched', async (role) => {
  const user = userEvent.setup()
  const calls = stubApi(role, (c) => (c.method === 'POST' && c.path === '/org/finance/settlements/s-26/paid' ? Response.json(PAID_ROW) : undefined))
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-26')
  await user.click(await sheet.findByRole('button', { name: fr.finance.settlements.payout.action }))

  const dialog = within(await screen.findByRole('dialog', { name: i18n.t('finance.settlements.payout.title', { day: day('2026-11-26') }) }))
  const input = dialog.getByLabelText(fr.finance.settlements.payout.reference)
  await user.click(dialog.getByRole('button', { name: fr.finance.settlements.payout.submit }))
  expect(await dialog.findByText(fr.finance.settlements.payout.referenceInvalid)).toBeTruthy()
  await user.type(input, 'x'.repeat(101))
  await user.click(dialog.getByRole('button', { name: fr.finance.settlements.payout.submit }))
  expect(await dialog.findByText(fr.finance.settlements.payout.referenceInvalid)).toBeTruthy()
  expect(calls.filter((c) => c.method === 'POST')).toEqual([])

  await user.clear(input)
  await user.type(input, '  VIR 0042 ')
  await user.click(dialog.getByRole('button', { name: fr.finance.settlements.payout.submit }))
  expect(await screen.findByText(spaced(i18n.t('finance.settlements.payout.done', { day: day('2026-11-26') })))).toBeTruthy()
  expect(calls.filter((c) => c.method === 'POST')).toEqual([
    { method: 'POST', path: '/org/finance/settlements/s-26/paid', org: 'org-a', auth: 'Bearer tok-1', body: { reference: 'VIR 0042' } },
  ])
  await waitFor(() => expect(listCalls(calls)).toHaveLength(2))
})

it('SETTLEMENT_STATUS (nothing to pay any more): the dialog closes with a toast', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE', (c) => (c.method === 'POST' ? Response.json(ERR_STATUS, { status: 409 }) : undefined))
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-28')
  await user.click(await sheet.findByRole('button', { name: fr.finance.settlements.payout.action }))
  const dialog = within(await screen.findByRole('dialog', { name: i18n.t('finance.settlements.payout.title', { day: day('2026-11-28') }) }))
  await user.type(dialog.getByLabelText(fr.finance.settlements.payout.reference), 'VIR 0043')
  await user.click(dialog.getByRole('button', { name: fr.finance.settlements.payout.submit }))
  expect(await screen.findByText(fr.errors.SETTLEMENT_STATUS)).toBeTruthy()
  await waitFor(() => expect(screen.queryByRole('dialog', { name: i18n.t('finance.settlements.payout.title', { day: day('2026-11-28') }) })).toBeNull())
})

it('no "Versement reçu" on NONE or PAID; PAID says when, who and the reference', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN')
  renderApp('/finance/settlements')
  let sheet = await openSheet(user, '2026-11-27')
  expect(await sheet.findByText(fr.finance.settlements.payout.none)).toBeTruthy()
  expect(sheet.getByText(fr.finance.settlements.formula.nothing)).toBeTruthy()
  expect(sheet.queryByRole('button', { name: fr.finance.settlements.payout.action })).toBeNull()
  await user.keyboard('{Escape}')
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

  sheet = await openSheet(user, '2026-11-09')
  const at = new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(PAID.paid_at!))
  expect(await sheet.findByText(spaced(i18n.t('finance.settlements.payout.paid', { date: at, name: 'Finance Dev' })))).toBeTruthy()
  expect(sheet.getByText(spaced(i18n.t('finance.settlements.payout.paidReference', { reference: 'VIR 0031' })))).toBeTruthy()
  expect(sheet.queryByRole('button', { name: fr.finance.settlements.payout.action })).toBeNull()
})

it('an overdue DUE statement says so in red in the sheet', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE')
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-10')
  expect((await sheet.findByText(spaced(i18n.t('finance.settlements.payout.overdue', { amount: dh(94_000), date: day('2026-11-12') })))).className).toContain('text-destructive')
})

function stubDownloads() {
  let blob: Blob | undefined
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: (b: Blob) => ((blob = b), 'blob:x'), revokeObjectURL: vi.fn() }))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  return { click, blob: () => blob! }
}
const file = (bytes: number[], type: string, name: string | null) =>
  new Response(new Uint8Array(bytes), { headers: { 'Content-Type': type, ...(name ? { 'Content-Disposition': `attachment; filename="${name}"` } : {}) } })

it('invoice PDF: a blob fetched with Bearer + organizer, saved as the server names it, from the table and the sheet', async () => {
  const user = userEvent.setup()
  const pdf = [0x25, 0x50, 0x44, 0x46] // %PDF
  const calls = stubApi('FINANCE', (c) => (c.path === '/org/finance/invoices/i-3.pdf' ? file(pdf, 'application/pdf', 'facture-JMH-2026-000003.pdf') : undefined))
  const { click, blob } = stubDownloads()
  renderApp('/finance/settlements')

  await user.click(await screen.findByRole('button', { name: i18n.t('finance.settlements.invoiceLabel', { number: 'JMH-2026-000003' }) }))
  await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
  expect(calls.find((c) => c.path === '/org/finance/invoices/i-3.pdf')).toEqual(expect.objectContaining({ org: 'org-a', auth: 'Bearer tok-1' }))
  expect([...new Uint8Array(await blob().arrayBuffer())]).toEqual(pdf)
  expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('facture-JMH-2026-000003.pdf')

  const sheet = await openSheet(user, '2026-11-26')
  await user.click(await sheet.findByRole('button', { name: fr.finance.settlements.sheet.invoicePdf }))
  await waitFor(() => expect(click).toHaveBeenCalledTimes(2))
})

it('Exporter CSV: the range of the list, BOM intact, server filename; releves-{from}-{to}.csv without one', async () => {
  const user = userEvent.setup()
  let name: string | null = 'releves-serveur.csv'
  const calls = stubApi('FINANCE', (c) => (c.path.startsWith('/org/finance/settlements.csv?') ? file([0xef, 0xbb, 0xbf, 0x6b], 'text/csv', name) : undefined))
  const { click, blob } = stubDownloads()
  renderApp('/finance/settlements')

  await user.click(await screen.findByRole('button', { name: fr.finance.settlements.export }))
  await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
  const csv = calls.find((c) => c.path.startsWith('/org/finance/settlements.csv?'))!
  expect(csv).toEqual(expect.objectContaining({ org: 'org-a', auth: 'Bearer tok-1' }))
  expect(csv.path.split('?')[1]).toBe(listCalls(calls)[0]!.path.split('?')[1])
  expect([...new Uint8Array(await blob().arrayBuffer())].slice(0, 3)).toEqual([0xef, 0xbb, 0xbf])
  expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('releves-serveur.csv')

  name = null
  await user.click(screen.getByRole('button', { name: fr.finance.settlements.export }))
  await waitFor(() => expect(click).toHaveBeenCalledTimes(2))
  const today = todayIn(CASA)
  expect((click.mock.contexts[1] as HTMLAnchorElement).download).toBe(`releves-${addDays(today, -29)}-${today}.csv`)
})

it('OPS: no Relevés tab, /finance opens Encaissements, /finance/settlements redirects there, no settlements call', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS')
  const { router } = renderApp('/finance')
  await waitFor(() => expect(router.state.location.pathname).toBe('/finance/cash'))
  const tabs = await screen.findAllByRole('tab')
  expect(tabs.map((t) => t.textContent)).toEqual([fr.finance.tabs.cash, fr.finance.tabs.deposits])
  await user.click(tabs[1]!)
  await waitFor(() => expect(router.state.location.pathname).toBe('/finance/deposits'))

  await router.navigate('/finance/settlements')
  await waitFor(() => expect(router.state.location.pathname).toBe('/finance/cash'))
  expect(calls.filter((c) => c.path.startsWith('/org/finance/settlements'))).toEqual([])
})

it('À recevoir is `due` as sent, not a sum of the shown rows: a range with no DUE row still shows every DUE statement', async () => {
  stubApi('FINANCE', (c) => (c.path.startsWith('/org/finance/settlements?') ? Response.json({ ...LIST, settlements: [FRI, PAID], due: { count: 1, amount_mad: 25_000 } }) : undefined))
  renderApp('/finance/settlements')
  await screen.findByRole('heading', { name: fr.finance.settlements.list.title })
  const due = within(screen.getByText(fr.finance.settlements.kpi.due).closest('[data-slot="card"]') as HTMLElement)
  expect(due.getByText(spaced(dh(25_000)))).toBeTruthy()
  expect(due.getByText(spaced(i18n.t('finance.settlements.kpi.dueHint', { count: 1 })))).toBeTruthy()
})

it('the VAT line reads the rate from `vat_rate_bp`', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE', (c) => (c.path.startsWith('/org/finance/settlements?') ? Response.json({ ...LIST, vat_rate_bp: 1050 }) : undefined))
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-26')
  expect(await sheet.findByText(i18n.t('finance.settlements.formula.vat', { rate: '10,5' }))).toBeTruthy()
  expect(sheet.queryByText(/TVA \(20/u)).toBeNull()
})
