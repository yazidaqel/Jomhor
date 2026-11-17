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

// Answers captured from the real API (BO-28 real run, dev stack, recipe `backend/http/lot5-settlements.http`, ATLAS FC,
// FINANCE, range 2026-11-01..2026-11-17, 17 Nov 2026). The dev database held earlier real runs' payments on the two
// seeded days, so both statements came out DUE (card money) rather than the recipe's two NONE.
const REAL_16: Settlement = {"id": "d60bd5fc-f9e9-453d-8934-41f0fae6041f", "day": "2026-11-16", "status": "DUE", "overdue": false, "collected_mad": 208334, "till_mad": 98666, "fee_rate_bp": 500, "fee_ht_mad": 15350, "fee_vat_mad": 3070, "carried_in_mad": 0, "payout_mad": 189914, "due_on": "2026-11-18", "paid_at": null, "paid_by": null, "payout_reference": null, "invoice": {"id": "9fc1cd4f-8b30-494e-b3f5-957b0c89beac", "number": "JMH-2026-000002"}}
const REAL_15: Settlement = {"id": "1d1e4d77-7dc5-4b7b-8542-4abb832f292b", "day": "2026-11-15", "status": "DUE", "overdue": false, "collected_mad": 410000, "till_mad": 445000, "fee_rate_bp": 500, "fee_ht_mad": 42750, "fee_vat_mad": 8550, "carried_in_mad": 0, "payout_mad": 358700, "due_on": "2026-11-17", "paid_at": null, "paid_by": null, "payout_reference": null, "invoice": {"id": "b39b61a5-65a9-4650-8a1e-3bf68955481c", "number": "JMH-2026-000001"}}
// POST …/1d1e4d77…/paid {"reference": "  VIR 0042 "} → 200; the replay with another reference returned the same body.
const REAL_15_PAID: Settlement = {"id": "1d1e4d77-7dc5-4b7b-8542-4abb832f292b", "day": "2026-11-15", "status": "PAID", "overdue": false, "collected_mad": 410000, "till_mad": 445000, "fee_rate_bp": 500, "fee_ht_mad": 42750, "fee_vat_mad": 8550, "carried_in_mad": 0, "payout_mad": 358700, "due_on": "2026-11-17", "paid_at": "2026-11-17T17:46:13.481563Z", "paid_by": {"id": "a7a5fc00-0000-4000-8000-000000000204", "name": "Finance Dev"}, "payout_reference": "VIR 0042", "invoice": {"id": "b39b61a5-65a9-4650-8a1e-3bf68955481c", "number": "JMH-2026-000001"}}
const REAL_LIST: Settlements = {"from": "2026-11-01", "to": "2026-11-17", "timezone": "Africa/Casablanca", "settings": {"legal_name": "ATLAS FC SA", "ice": "001234567000089", "rib_last4": "1234", "fee_rate_bp": 500, "start_on": "2026-11-15"}, "open": {"since": "2026-11-17T00:00:00Z", "collected_mad": 72000, "till_mad": 269000, "payments": 13}, "refunds_required": {"count": 4, "amount_mad": 27000}, "due": {"count": 2, "amount_mad": 548614, "overdue": 0}, "vat_rate_bp": 2000, "settlements": [REAL_16, REAL_15]}
// The same list after the payout above.
const REAL_LIST_PAID: Settlements = { ...REAL_LIST, due: {"count": 1, "amount_mad": 189914, "overdue": 0}, settlements: [REAL_16, REAL_15_PAID] }
// Olympique Tensift (ADMIN's second organizer): no finance settings.
const REAL_INACTIVE: Settlements = {"from": "2026-11-01", "to": "2026-11-17", "timezone": "Africa/Casablanca", "settings": null, "open": null, "refunds_required": {"count": 0, "amount_mad": 0}, "due": {"count": 0, "amount_mad": 0, "overdue": 0}, "vat_rate_bp": 2000, "settlements": []}
// GET …/settlements/d60bd5fc… (titles are earlier real runs' events and products).
const REAL_DETAIL_16: SettlementDetail = {...REAL_16, "lines": [{"event_id": null, "product_id": "62db1d55-c869-4e30-8219-39ae2652e48d", "title": "Abonnement Est – WS-16 real run 19:14", "method": "CARD", "collector": "FAKE", "collector_name": "Payzone", "payments": 2, "amount_mad": 66667}, {"event_id": null, "product_id": "62db1d55-c869-4e30-8219-39ae2652e48d", "title": "Abonnement Est – WS-16 real run 19:14", "method": "CASH_AGENCY", "collector": "POS", "collector_name": "Guichet", "payments": 1, "amount_mad": 33333}, {"event_id": 1, "product_id": null, "title": "ATLAS FC vs Rapid Oued", "method": "POS_CASH", "collector": "POS", "collector_name": "Guichet", "payments": 1, "amount_mad": 32000}, {"event_id": null, "product_id": "a713802f-f754-4541-a1f9-03c5c36dc694", "title": "BO-24 real run pass", "method": "CARD", "collector": "FAKE", "collector_name": "Payzone", "payments": 2, "amount_mad": 75000}, {"event_id": null, "product_id": "83631642-11e5-441b-89dd-a7ac64adf063", "title": "FL-31 Abonnement Tribune Est", "method": "CARD", "collector": "FAKE", "collector_name": "Payzone", "payments": 2, "amount_mad": 66667}, {"event_id": null, "product_id": "83631642-11e5-441b-89dd-a7ac64adf063", "title": "FL-31 Abonnement Tribune Est", "method": "CASH_AGENCY", "collector": "POS", "collector_name": "Guichet", "payments": 1, "amount_mad": 33333}]}
// The first bytes of the real files: facture-JMH-2026-000001.pdf (its text holds "N° JMH-2026-000001") and
// releves-2026-11-01-2026-11-17.csv (BOM + header).
const REAL_PDF = [...new TextEncoder().encode('%PDF-1.6\n')]
const REAL_CSV = [0xef, 0xbb, 0xbf, ...new TextEncoder().encode('kind;jour;libelle;moyen;encaisseur;paiements;montant;en_ligne;guichets;frais_ht;tva;report;a_verser;echeance;statut;facture;reference\r\n')]

// Not reachable in the dev run: a statement is only issued for a past day after the latest one, and 16 Nov was the
// latest, so no NONE, overdue or carried-debt statement could be issued that day. Derived from the real rows, with the
// fields the server would set; the formulas match 04 §Finance (500 bp, VAT 20 %).
const NONE_ROW: Settlement = { ...REAL_16, id: 'derived-none', day: '2026-11-13', status: 'NONE', collected_mad: 0, till_mad: 80_000, fee_ht_mad: 4000, fee_vat_mad: 800, payout_mad: 0, due_on: '2026-11-16', invoice: { id: 'derived-i-none', number: 'JMH-2026-900001' } }
const LATE_ROW: Settlement = { ...REAL_16, id: 'derived-late', day: '2026-11-10', overdue: true, collected_mad: 100_000, till_mad: 0, fee_ht_mad: 5000, fee_vat_mad: 1000, carried_in_mad: -4800, payout_mad: 89_200, due_on: '2026-11-12', invoice: { id: 'derived-i-late', number: 'JMH-2026-900002' } }
const LIST: Settlements = { ...REAL_LIST_PAID, due: { count: 2, amount_mad: 189_914 + 89_200, overdue: 1 }, settlements: [REAL_16, REAL_15_PAID, NONE_ROW, LATE_ROW] }
// 409 body as SettlementService throws it and SettlementPaidApiTest asserts it (no NONE statement in the dev run).
const ERR_STATUS = { code: 'SETTLEMENT_STATUS', message: 'Nothing to pay out', details: { status: 'NONE' } }

type Call = { method: string; path: string; org: string | null; auth: string | null; body: unknown }
type Handler = (c: Call) => Response | undefined

/** `list`: the settlements list answer; a row's sheet is the real 16 Nov one, else that row with no lines. */
function stubApi(role: Role, handle?: Handler, list: Settlements = LIST) {
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
      if (path.startsWith('/org/finance/settlements?')) return Response.json(list)
      if (path === `/org/finance/settlements/${REAL_16.id}`) return Response.json(REAL_DETAIL_16)
      if (path.startsWith('/org/finance/settlements/') && method === 'GET') {
        // Lines left out for the other rows (the real 15 Nov sheet has 18).
        const r = list.settlements.find((s) => s.id === path.split('/').at(-1))
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
  stubApi('FINANCE', undefined, REAL_LIST)
  renderApp('/finance/settlements')
  await screen.findByRole('heading', { name: fr.finance.settlements.contract.title })
  const card = within(section(fr.finance.settlements.contract.title))
  expect(card.getByText('ATLAS FC SA')).toBeTruthy()
  expect(card.getByText('001234567000089')).toBeTruthy()
  expect(card.getByText('RIB •••• 1234')).toBeTruthy()
  expect(card.getByText('Frais 5 % HT')).toBeTruthy()
  expect(card.getByText(day('2026-11-15'))).toBeTruthy()
  expect(card.getByText(fr.finance.settlements.contract.managed)).toBeTruthy()
})

it('a RIB longer than 4 digits on the wire still shows 4', async () => {
  stubApi('FINANCE', undefined, { ...REAL_LIST, settings: { ...REAL_LIST.settings!, rib_last4: '011780000012345678904821' } })
  renderApp('/finance/settlements')
  expect(await screen.findByText('RIB •••• 4821')).toBeTruthy()
  expect(screen.queryByText(/0117800000/u)).toBeNull()
})

it('settings null: the "pas encore activés" empty state, no KPI, no table', async () => {
  stubApi('ADMIN', undefined, REAL_INACTIVE)
  renderApp('/finance/settlements')
  expect(await screen.findByText(fr.finance.settlements.inactive.title)).toBeTruthy()
  expect(screen.getByText(fr.finance.settlements.inactive.description)).toBeTruthy()
  expect(screen.queryByText(fr.finance.settlements.kpi.open)).toBeNull()
  expect(screen.queryByText(fr.finance.settlements.contract.title)).toBeNull()
  expect(screen.queryByRole('table')).toBeNull()
  expect(screen.queryByRole('button', { name: fr.finance.settlements.export })).toBeNull()
})

it('KPIs: open from `open`, À recevoir from `due`, refunds from `refunds_required` linking to Ventes', async () => {
  stubApi('FINANCE', undefined, REAL_LIST)
  renderApp('/finance/settlements')
  await screen.findByRole('heading', { name: fr.finance.settlements.list.title })
  const kpi = (label: string) => within(screen.getByText(label).closest('[data-slot="card"]') as HTMLElement)

  const open = kpi(fr.finance.settlements.kpi.open)
  expect(open.getByText(spaced(dh(72_000)))).toBeTruthy()
  expect(open.getByText(spaced(i18n.t('finance.settlements.kpi.openTill', { amount: dh(269_000) })))).toBeTruthy()
  expect(open.getByText(spaced(i18n.t('finance.settlements.kpi.openSince', { count: 13, date: day('2026-11-17') })))).toBeTruthy()

  const due = kpi(fr.finance.settlements.kpi.due)
  expect(due.getByText(spaced(dh(548_614)))).toBeTruthy()
  expect(due.getByText(spaced(i18n.t('finance.settlements.kpi.dueHint', { count: 2 })))).toBeTruthy()
  expect(due.queryByText(/en retard/u)).toBeNull() // overdue 0

  const refunds = kpi(fr.finance.settlements.kpi.refunds)
  expect(refunds.getByText(spaced(i18n.t('finance.settlements.kpi.refundsValue', { count: 4, amount: dh(27_000) })))).toBeTruthy()
  expect(refunds.getByRole('link', { name: fr.finance.settlements.kpi.refundsLink }).getAttribute('href')).toBe('/sales')
})

it('rows newest first: en ligne, guichets, frais TTC, report, à verser, échéance, badge (À verser, En retard red, Versé le, Rien à verser), facture', async () => {
  stubApi('FINANCE')
  renderApp('/finance/settlements')
  await screen.findByRole('heading', { name: fr.finance.settlements.list.title })
  const rows = table().getAllByRole('row').slice(1)
  const s = fr.finance.settlements
  expect(rows.map(cells)).toEqual([
    [day('2026-11-16'), dh(208_334), dh(98_666), dh(18_420), s.none, dh(189_914), day('2026-11-18'), s.status.due, 'JMH-2026-000002'],
    [day('2026-11-15'), dh(410_000), dh(445_000), dh(51_300), s.none, dh(358_700), day('2026-11-17'), i18n.t('finance.settlements.status.paidOn', { date: day('2026-11-17') }), 'JMH-2026-000001'],
    [day('2026-11-13'), dh(0), dh(80_000), dh(4800), s.none, dh(0), s.none, s.status.none, 'JMH-2026-900001'],
    [day('2026-11-10'), dh(100_000), dh(0), dh(6000), dh(-4800), dh(89_200), day('2026-11-12'), s.status.overdue, 'JMH-2026-900002'],
  ])
  expect(within(rows[3]!).getByText(s.status.overdue).getAttribute('data-variant')).toBe('destructive')
  expect(within(rows[3]!).getAllByRole('cell')[4]!.className).toContain('text-destructive') // a negative carry reads red
})

it('row → sheet: the lines grouped by event or product; the formula shows the server amounts only', async () => {
  const user = userEvent.setup()
  // A payout that does not match the other figures: shown as sent, proof that nothing is recomputed here.
  stubApi('FINANCE', (c) => (c.path === `/org/finance/settlements/${REAL_16.id}` ? Response.json({ ...REAL_DETAIL_16, payout_mad: 123_456 }) : undefined))
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-16')

  expect(await sheet.findByText(spaced(i18n.t('finance.settlements.sheet.title', { day: day('2026-11-16') })))).toBeTruthy()
  const formula = sheet.getByRole('heading', { name: fr.finance.settlements.formula.title }).closest('section') as HTMLElement
  const pairs = [...formula.querySelectorAll('dt')].map((dt) => [dt.textContent, dt.nextElementSibling?.textContent])
  expect(pairs).toEqual([
    [fr.finance.settlements.formula.collected, dh(208_334)],
    [i18n.t('finance.settlements.formula.feeHt', { rate: '5' }), i18n.t('finance.settlements.formula.minus', { amount: dh(15_350) })],
    [i18n.t('finance.settlements.formula.vat', { rate: '20' }), i18n.t('finance.settlements.formula.minus', { amount: dh(3070) })],
    [fr.finance.settlements.formula.carried, dh(0)],
    [fr.finance.settlements.formula.payout, dh(123_456)],
  ])
  expect(within(formula).getByText(spaced(i18n.t('finance.settlements.formula.till', { amount: dh(98_666) })))).toBeTruthy()

  const lines = sheet.getByRole('heading', { name: fr.finance.settlements.lines.title }).closest('section') as HTMLElement
  expect(within(lines).getAllByRole('row').slice(1).map(cells)).toEqual([
    [`Abonnement Est – WS-16 real run 19:14 ${fr.finance.settlements.lines.product}`],
    [fr.sales.methods.CARD, 'Payzone', '2', dh(66_667)],
    [fr.sales.methods.CASH_AGENCY, 'Guichet', '1', dh(33_333)], // a cash code collected at a till
    [`ATLAS FC vs Rapid Oued ${fr.finance.settlements.lines.event}`],
    [fr.sales.methods.POS_CASH, 'Guichet', '1', dh(32_000)],
    [`BO-24 real run pass ${fr.finance.settlements.lines.product}`],
    [fr.sales.methods.CARD, 'Payzone', '2', dh(75_000)],
    [`FL-31 Abonnement Tribune Est ${fr.finance.settlements.lines.product}`],
    [fr.sales.methods.CARD, 'Payzone', '2', dh(66_667)],
    [fr.sales.methods.CASH_AGENCY, 'Guichet', '1', dh(33_333)],
  ])
})

it.each(['ADMIN', 'FINANCE'] as const)('%s: "Versement reçu" on a DUE statement, reference 1–100 trimmed → POST, toast, the list refetched', async (role) => {
  const user = userEvent.setup()
  const calls = stubApi(role, (c) => (c.method === 'POST' && c.path === `/org/finance/settlements/${REAL_15.id}/paid` ? Response.json(REAL_15_PAID) : undefined), REAL_LIST)
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-15')
  await user.click(await sheet.findByRole('button', { name: fr.finance.settlements.payout.action }))

  const dialog = within(await screen.findByRole('dialog', { name: i18n.t('finance.settlements.payout.title', { day: day('2026-11-15') }) }))
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
  expect(await screen.findByText(spaced(i18n.t('finance.settlements.payout.done', { day: day('2026-11-15') })))).toBeTruthy()
  expect(calls.filter((c) => c.method === 'POST')).toEqual([
    { method: 'POST', path: `/org/finance/settlements/${REAL_15.id}/paid`, org: 'org-a', auth: 'Bearer tok-1', body: { reference: 'VIR 0042' } },
  ])
  await waitFor(() => expect(listCalls(calls)).toHaveLength(2))
})

it('SETTLEMENT_STATUS (nothing to pay any more): the dialog closes with a toast', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE', (c) => (c.method === 'POST' ? Response.json(ERR_STATUS, { status: 409 }) : undefined))
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-16')
  await user.click(await sheet.findByRole('button', { name: fr.finance.settlements.payout.action }))
  const dialog = within(await screen.findByRole('dialog', { name: i18n.t('finance.settlements.payout.title', { day: day('2026-11-16') }) }))
  await user.type(dialog.getByLabelText(fr.finance.settlements.payout.reference), 'VIR 0043')
  await user.click(dialog.getByRole('button', { name: fr.finance.settlements.payout.submit }))
  expect(await screen.findByText(fr.errors.SETTLEMENT_STATUS)).toBeTruthy()
  await waitFor(() => expect(screen.queryByRole('dialog', { name: i18n.t('finance.settlements.payout.title', { day: day('2026-11-16') }) })).toBeNull())
})

it('no "Versement reçu" on NONE or PAID; PAID says when, who and the reference', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN')
  renderApp('/finance/settlements')
  let sheet = await openSheet(user, '2026-11-13')
  expect(await sheet.findByText(fr.finance.settlements.payout.none)).toBeTruthy()
  expect(sheet.getByText(fr.finance.settlements.formula.nothing)).toBeTruthy()
  expect(sheet.queryByRole('button', { name: fr.finance.settlements.payout.action })).toBeNull()
  await user.keyboard('{Escape}')
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

  sheet = await openSheet(user, '2026-11-15')
  const at = new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(REAL_15_PAID.paid_at!))
  expect(await sheet.findByText(spaced(i18n.t('finance.settlements.payout.paid', { date: at, name: 'Finance Dev' })))).toBeTruthy()
  expect(sheet.getByText(spaced(i18n.t('finance.settlements.payout.paidReference', { reference: 'VIR 0042' })))).toBeTruthy()
  expect(sheet.queryByRole('button', { name: fr.finance.settlements.payout.action })).toBeNull()
})

it('an overdue DUE statement says so in red in the sheet', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE')
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-10')
  expect((await sheet.findByText(spaced(i18n.t('finance.settlements.payout.overdue', { amount: dh(89_200), date: day('2026-11-12') })))).className).toContain('text-destructive')
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
  const path = `/org/finance/invoices/${REAL_16.invoice!.id}.pdf`
  const calls = stubApi('FINANCE', (c) => (c.path === path ? file(REAL_PDF, 'application/pdf', 'facture-JMH-2026-000002.pdf') : undefined))
  const { click, blob } = stubDownloads()
  renderApp('/finance/settlements')

  await user.click(await screen.findByRole('button', { name: i18n.t('finance.settlements.invoiceLabel', { number: 'JMH-2026-000002' }) }))
  await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
  expect(calls.find((c) => c.path === path)).toEqual(expect.objectContaining({ org: 'org-a', auth: 'Bearer tok-1' }))
  expect([...new Uint8Array(await blob().arrayBuffer())]).toEqual(REAL_PDF)
  expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('facture-JMH-2026-000002.pdf')

  const sheet = await openSheet(user, '2026-11-16')
  await user.click(await sheet.findByRole('button', { name: fr.finance.settlements.sheet.invoicePdf }))
  await waitFor(() => expect(click).toHaveBeenCalledTimes(2))
})

it('Exporter CSV: the range of the list, BOM intact, server filename; releves-{from}-{to}.csv without one', async () => {
  const user = userEvent.setup()
  let name: string | null = 'releves-2026-11-01-2026-11-17.csv'
  const calls = stubApi('FINANCE', (c) => (c.path.startsWith('/org/finance/settlements.csv?') ? file(REAL_CSV, 'text/csv;charset=UTF-8', name) : undefined))
  const { click, blob } = stubDownloads()
  renderApp('/finance/settlements')

  await user.click(await screen.findByRole('button', { name: fr.finance.settlements.export }))
  await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
  const csv = calls.find((c) => c.path.startsWith('/org/finance/settlements.csv?'))!
  expect(csv).toEqual(expect.objectContaining({ org: 'org-a', auth: 'Bearer tok-1' }))
  expect(csv.path.split('?')[1]).toBe(listCalls(calls)[0]!.path.split('?')[1])
  expect([...new Uint8Array(await blob().arrayBuffer())].slice(0, 3)).toEqual([0xef, 0xbb, 0xbf])
  expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('releves-2026-11-01-2026-11-17.csv')

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
  // The real list after the payout, narrowed to 15 Nov (PAID): 16 Nov, still DUE, is outside the range.
  stubApi('FINANCE', undefined, { ...REAL_LIST_PAID, settlements: [REAL_15_PAID] })
  renderApp('/finance/settlements')
  await screen.findByRole('heading', { name: fr.finance.settlements.list.title })
  const due = within(screen.getByText(fr.finance.settlements.kpi.due).closest('[data-slot="card"]') as HTMLElement)
  expect(due.getByText(spaced(dh(189_914)))).toBeTruthy()
  expect(due.getByText(spaced(i18n.t('finance.settlements.kpi.dueHint', { count: 1 })))).toBeTruthy()
  expect(due.queryByText(/en retard/u)).toBeNull() // overdue 0: no line
})

it('"dont N en retard" comes from `due.overdue`: an overdue statement outside the range still counts, though no row shows it', async () => {
  stubApi('FINANCE', undefined, { ...REAL_LIST, due: { ...REAL_LIST.due, overdue: 2 } })
  renderApp('/finance/settlements')
  await screen.findByRole('heading', { name: fr.finance.settlements.list.title })
  expect(table().queryByText(fr.finance.settlements.status.overdue)).toBeNull() // no late row in the range
  const due = within(screen.getByText(fr.finance.settlements.kpi.due).closest('[data-slot="card"]') as HTMLElement)
  expect(due.getByText(i18n.t('finance.settlements.kpi.dueLate', { count: 2 })).className).toContain('text-destructive')
})

it('the VAT line reads the rate from `vat_rate_bp`', async () => {
  const user = userEvent.setup()
  stubApi('FINANCE', undefined, { ...LIST, vat_rate_bp: 1050 })
  renderApp('/finance/settlements')
  const sheet = await openSheet(user, '2026-11-16')
  expect(await sheet.findByText(i18n.t('finance.settlements.formula.vat', { rate: '10,5' }))).toBeTruthy()
  expect(sheet.queryByText(/TVA \(20/u)).toBeNull()
})

it('the default range follows the list `timezone` (finance), not the venue zone; a picked range stays', async () => {
  const user = userEvent.setup()
  // UTC+14 vs UTC−11: their dates always differ by one day.
  const venue = 'Pacific/Kiritimati'
  const finance = 'Pacific/Pago_Pago'
  const calls = stubApi('FINANCE', (c) =>
    c.path === '/org/venues'
      ? Response.json([{ id: 'v-1', name: 'Grand Stade', city: 'Casablanca', capacity: 45000, timezone: venue }])
      : c.path.startsWith('/org/finance/settlements?')
        ? Response.json({ ...REAL_LIST, timezone: finance })
        : undefined,
  )
  renderApp('/finance/settlements')
  const query = (z: string) => `/org/finance/settlements?${new URLSearchParams({ from: addDays(todayIn(z), -29), to: todayIn(z) })}`
  await waitFor(() => expect(listCalls(calls).map((c) => c.path)).toEqual([query(venue), query(finance)]))
  expect((await screen.findByLabelText(fr.finance.cash.range.to) as HTMLInputElement).value).toBe(todayIn(finance))

  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.from), { target: { value: '2026-11-01' } })
  fireEvent.change(screen.getByLabelText(fr.finance.cash.range.to), { target: { value: '2026-11-17' } })
  await user.click(screen.getByRole('button', { name: fr.finance.cash.range.submit }))
  await waitFor(() => expect(listCalls(calls).at(-1)!.path).toBe('/org/finance/settlements?from=2026-11-01&to=2026-11-17'))
  expect(listCalls(calls)).toHaveLength(3)
})
