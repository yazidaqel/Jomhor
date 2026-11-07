import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { focusManager, onlineManager, QueryClientProvider } from '@tanstack/react-query'
import QRCode from 'react-qr-code'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me } from '@/features/auth/organizer'
import type { EventDetail, EventSummary, Price } from '@/features/events/api'
import { createQueryClient } from '@/lib/queryClient'
import { formatDh } from '@/lib/money'
import { session } from '@/lib/session'
import { changeDue, codeStatus, displayCode, holderField, normalizeCode, readCode, saleItems, saleSchema, type ClosedSession, type CodeLookup, type PosSession, type PrintData } from './api'

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
  vi.unstubAllGlobals()
  focusManager.setFocused(undefined)
  onlineManager.setOnline(true)
  session.clear()
})

// Fixtures in the 04-api shapes (§Point of sale; prices with `zone`). BE-23 is contract-first.
const CASA = 'Africa/Casablanca'
const HOUR = 3_600_000
const at = (ms: number) => new Date(Date.now() + ms).toISOString()
const ME: Me = {
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role: 'OPS' }],
}
const SESSION: PosSession = { id: 's-1', label: 'Guichet Nord', opened_at: '2026-11-07T09:02:00Z', orders: 12, tickets: 31, prints: 14, collected_mad: 465_050 }
const STARTS = at(7 * 24 * HOUR)
const venue = { id: 'v-1', name: 'Grand Stade', timezone: CASA }
const EVENTS: EventSummary[] = [
  { id: 1, title: 'Atlas FC vs Rapid Oued', event_type: 'FOOTBALL_MATCH', starts_at: STARTS, status: 'ON_SALE', venue, quota: 39600, sold: 1250 },
  { id: 2, title: 'Brouillon', event_type: 'FOOTBALL_MATCH', starts_at: STARTS, status: 'DRAFT', venue, quota: 0, sold: 0 },
  { id: 4, title: 'Match dans 30 min', event_type: 'FOOTBALL_MATCH', starts_at: at(HOUR / 2), status: 'ON_SALE', venue, quota: 100, sold: 0 },
]
const EVENT: EventDetail = {
  id: 1,
  venue_id: 'v-1',
  timezone: CASA,
  series_id: null,
  event_type: 'FOOTBALL_MATCH',
  title: EVENTS[0]!.title,
  starts_at: STARTS,
  doors_open_at: null,
  sale_starts_at: null,
  status: 'ON_SALE',
  refund_cutoff_at: at(7 * 24 * HOUR - HOUR),
  reentry_policy: 'NONE',
  per_account_limit: 3,
  metadata: {},
  created_at: '2026-11-01T10:00:00Z',
}
// Two zones share "Plein tarif": only the zone tells them apart.
const PRICES: Price[] = [
  { id: 'p-nord', zone_id: 3, zone: { code: 'NORD', name: 'Virage Nord' }, name: 'Plein tarif', price_mad: 5_050, quota: 100, sold: 98 },
  { id: 'p-sud', zone_id: 4, zone: { code: 'SUD', name: 'Virage Sud' }, name: 'Plein tarif', price_mad: 5_000, quota: 100, sold: 0 },
  { id: 'p-trib', zone_id: 1, zone: { code: 'TRIB', name: 'Tribune' }, name: 'Honneur', price_mad: 15_000, quota: 10, sold: 10 },
]
const LOOKUP: CodeLookup = {
  order_id: '0192f0aa-0000-7000-8000-00000000abcd',
  status: 'AWAITING_CASH',
  payable: true,
  reserved_until: '2026-11-08T09:00:00Z',
  amount_mad: 30_000,
  places: 2,
  event: { id: 1, title: 'Atlas FC vs Rapid Oued', starts_at: STARTS, timezone: CASA },
  holders: ['Yazid A.', 'Salma B.'],
}
// `holder_name` is not in the print shape; a server sending it anyway must not get it on paper.
const PRINT = {
  order_id: '0192f0aa-0000-7000-8000-00004d5c6b7a',
  event: { id: 1, title: 'Atlas FC vs Rapid Oued', starts_at: '2026-11-14T19:00:00Z', timezone: CASA, venue: { name: 'Grand Stade' } },
  tickets: [
    { id: 't-1', ticket_no: '4D5C 6B7A', status: 'VALID', zone: { code: 'NORD', name: 'Virage Nord' }, gate_codes: ['A', 'B'], holder_label: 'Yazid A.', holder_name: 'Yazid Aqel', unit_price_mad: 5_050, qr: 'AQAAAAHxyz-token_one' },
    { id: 't-2', ticket_no: '9E8F 7A6B', status: 'VALID', zone: { code: 'NORD', name: 'Virage Nord' }, gate_codes: ['A'], holder_label: 'Salma B.', holder_name: 'Salma Bennani', unit_price_mad: 5_050, qr: 'AQAAAAHxyz-token_two' },
  ],
} satisfies PrintData & { tickets: { holder_name: string }[] }
const PRINT_PATH = `/org/pos/orders/${PRINT.order_id}/tickets`

type Call = { path: string; org: string | null; method: string; body: unknown }
type Overrides = Partial<Record<string, () => Response | Promise<Response>>>
function stubApi(overrides: Overrides = {}) {
  const calls: Call[] = []
  const json: Record<string, unknown> = {
    '/org/me': ME,
    '/org/pos/session': { session: SESSION },
    '/org/events': EVENTS,
    '/org/events/1': EVENT,
    '/org/events/1/prices': PRICES,
    '/org/pos/codes/4F7K29': LOOKUP,
    [PRINT_PATH]: PRINT,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      calls.push({ path, org: new Headers(init?.headers).get('X-Organizer-Id'), method: init?.method ?? 'GET', body })
      const override = overrides[path]
      if (override) return override()
      if (path in json) return Response.json(json[path])
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
  return { queryClient, router }
}

const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const dh = (c: number) => spaced(i18n.t('pos.amount', { amount: formatDh(c, 'fr') }))
const qrPaths = (root: Element) => [...root.querySelectorAll('path')].map((p) => p.getAttribute('d'))
const priceName = (p: Price) => i18n.t('pos.sale.priceName', { zone: p.zone.name, name: p.name })
const NBSP = String.fromCharCode(0xa0)
const serverError = () => Response.json({ code: 'INTERNAL', message: 'x', details: {} }, { status: 500 })

it.each([
  ['JMH 4F7K 29', '4F7K29'],
  ['jmh-4f7k-29', '4F7K29'],
  ['4f7k29', '4F7K29'],
  [` 4F7K${NBSP}29 `, '4F7K29'],
  ['JMH4F7K29', '4F7K29'],
  ['oIl0Ai', '0110A1'], // O → 0, I and L → 1
  ['JMHABC', null], // a leading JMH is the prefix: no code starts with JMH (never issued, 04-api §Cash codes)
  ['4F7K2', null],
  ['4F7K290', null],
  ['4F7KU9', null], // U is not in Crockford base32
  ['', null],
])('cash code %j → %j', (input, code) => {
  expect(normalizeCode(input)).toBe(code)
})

it('cash code display: JMH, 4 symbols, 2 symbols, missing ones as underscores; bad characters reported', () => {
  expect(displayCode('4F7K29')).toBe('JMH 4F7K 29')
  expect(displayCode(readCode('jmh 4f').symbols)).toBe('JMH 4F__ __')
  expect(displayCode('')).toBe('JMH ____ __')
  expect(readCode('4f-u!').invalid).toEqual(['U', '!'])
})

it('code status: an AWAITING_CASH order that is no longer payable reads as expired', () => {
  expect(codeStatus({ payable: true, status: 'AWAITING_CASH' })).toBe('payable')
  expect(codeStatus({ payable: false, status: 'AWAITING_CASH' })).toBe('EXPIRED')
  expect(codeStatus({ payable: false, status: 'EXPIRED' })).toBe('EXPIRED')
  expect(codeStatus({ payable: false, status: 'PAID' })).toBe('PAID')
  expect(codeStatus({ payable: false, status: 'CANCELLED' })).toBe('other')
})

it('change calculator: received DH text − total centimes, integer maths', () => {
  expect(changeDue('200', 15_050)).toBe(4_950)
  expect(changeDue('150,5', 15_050)).toBe(0)
  expect(changeDue('150.50', 15_050)).toBe(0)
  expect(changeDue('100', 15_050)).toBe(-5_050)
  expect(changeDue('0,3', 10)).toBe(20) // 0.3 − 0.1 as floats is 0.19999…
  expect(changeDue('9999999,99', 1)).toBe(999_999_998)
  expect(changeDue('abc', 100)).toBeNull()
  expect(changeDue('1,234', 100)).toBeNull()
})

it('"même nom": only the first holder is checked, and every place is sent with it', () => {
  const schema = saleSchema((k) => k)
  const base = { event_id: '1', qty: { 'p-nord': 2, 'p-sud': 1 }, received: '' }
  const holders = [{ name: 'Yazid Aqel', phone: '06 12 34 56 78' }, { name: '', phone: 'x' }, { name: 'S', phone: '' }]
  expect(schema.safeParse({ ...base, same_name: true, holders }).success).toBe(true)
  const each = schema.safeParse({ ...base, same_name: false, holders })
  expect(each.success ? [] : each.error.issues.map((i) => i.path.join('.'))).toEqual(['holders.1.name', 'holders.1.phone', 'holders.2.name'])

  const yazid = { name: 'Yazid Aqel', phone: '+212612345678' }
  expect(saleItems(PRICES, base.qty, holders, true)).toEqual([
    { price_category_id: 'p-nord', qty: 2, holders: [yazid, yazid] },
    { price_category_id: 'p-sud', qty: 1, holders: [yazid] },
  ])
  const own = [{ name: ' Yazid Aqel ', phone: '' }, { name: 'Salma B', phone: '0612345679' }, { name: 'Omar K', phone: ' ' }]
  expect(saleItems(PRICES, base.qty, own, false)).toEqual([
    { price_category_id: 'p-nord', qty: 2, holders: [{ name: 'Yazid Aqel' }, { name: 'Salma B', phone: '+212612345679' }] },
    { price_category_id: 'p-sud', qty: 1, holders: [{ name: 'Omar K' }] },
  ])
})

it('B11 Vente: sidebar entry, open events only, zone with each price, cap at remaining and per_account_limit, total and change', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/pos')

  expect(await screen.findByRole('heading', { level: 1, name: fr.pos.title })).toBeTruthy()
  const nav = within(screen.getByRole('navigation', { name: fr.nav.label }))
  expect(nav.getByRole('link', { name: fr.nav.pos }).getAttribute('aria-current')).toBe('page')
  expect(await screen.findByText(SESSION.label)).toBeTruthy()
  expect(screen.getByText(dh(SESSION.collected_mad))).toBeTruthy()

  // The DRAFT event and the one past its sales close (T0 − 60) are not offered.
  await user.click(screen.getByRole('combobox', { name: fr.pos.sale.event }))
  expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([expect.stringContaining(EVENTS[0]!.title)])
  await user.keyboard('{Escape}')

  const [nord, sud, trib] = PRICES as [Price, Price, Price]
  const more = (p: Price) => screen.getByRole('button', { name: i18n.t('pos.sale.more', { name: priceName(p) }) })
  await waitFor(() => expect(more(nord)).toBeTruthy())
  expect(screen.getByText(spaced(i18n.t('pos.sale.limit', { count: 3 })))).toBeTruthy()
  expect(more(trib)).toHaveProperty('disabled', true) // sold out
  await user.click(more(nord))
  await user.click(more(nord))
  expect(more(nord)).toHaveProperty('disabled', true) // 100 − 98 = 2 left
  await user.click(more(sud))
  expect(more(sud)).toHaveProperty('disabled', true) // per_account_limit 3 reached
  expect(screen.getByText(spaced(i18n.t('pos.sale.place', { n: 1, name: priceName(nord) })))).toBeTruthy()
  expect(screen.getByText(spaced(i18n.t('pos.sale.place', { n: 3, name: priceName(sud) })))).toBeTruthy()

  // 2 × 50,50 + 50,00 = 151,00 DH.
  const summary = screen.getByText(fr.pos.sale.summary).closest('[data-slot="card"]') as HTMLElement
  expect(within(summary).getByText(dh(15_100))).toBeTruthy()
  const received = within(summary).getByLabelText(fr.pos.sale.received)
  await user.type(received, '12,')
  expect(within(summary).queryByText(fr.pos.sale.receivedInvalid)).toBeNull() // still on its way to an amount
  await user.clear(received)
  await user.type(received, '1,234')
  expect(within(summary).getByText(fr.pos.sale.receivedInvalid)).toBeTruthy() // can no longer become one
  expect(received.getAttribute('aria-describedby')).toBe('pos-received-error')
  await user.clear(received)
  await user.type(received, '200')
  expect(within(summary).getByText(fr.pos.sale.change)).toBeTruthy()
  expect(within(summary).getByText(dh(4_900))).toBeTruthy()
  await user.clear(received)
  await user.type(received, '100,5')
  expect(within(summary).getByText(fr.pos.sale.missing)).toBeTruthy()
  expect(within(summary).getByText(dh(5_050))).toBeTruthy()

  // A field error is announced with its field.
  const name = document.getElementById('pos-holder-0-name')!
  await user.type(name, 'Y')
  await user.tab()
  expect(name.getAttribute('aria-describedby')).toBe('pos-holder-0-name-error')
  expect(document.getElementById('pos-holder-0-name-error')!.textContent).toBe(fr.pos.sale.nameInvalid)

  await user.click(screen.getByLabelText(fr.pos.sale.sameName))
  expect(screen.getByText(fr.pos.sale.allPlaces)).toBeTruthy()
  expect(screen.getAllByLabelText(fr.pos.sale.name)).toHaveLength(1)

  expect(calls.filter((c) => c.path.startsWith('/org/')).every((c) => c.path === '/org/me' || c.org === 'org-a')).toBe(true)
  expect(calls.some((c) => c.path === '/org/events/1')).toBe(true)
})

it('B11 Vente: no change row while the total is 0', async () => {
  const user = userEvent.setup()
  stubApi()
  renderApp('/pos')
  const summary = (await screen.findByText(fr.pos.sale.summary)).closest('[data-slot="card"]') as HTMLElement
  await user.type(within(summary).getByLabelText(fr.pos.sale.received), '200')
  expect(within(summary).queryByText(fr.pos.sale.change)).toBeNull()
})

it('B11 Code cash: typo-safe input, masked preview, lookup by the 6 symbols with the organizer', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/pos')

  await user.click(await screen.findByRole('tab', { name: fr.pos.tabs.code }))
  const input = screen.getByRole('textbox', { name: fr.pos.code.label })
  expect(input.getAttribute('placeholder')).toBe('JMH ____ __')
  expect(input.getAttribute('aria-describedby')).toBe('pos-code-hint')
  await user.type(input, 'jmh 4f7k-2')
  expect(screen.getByText('JMH 4F7K 2_')).toBeTruthy()
  expect(calls.some((c) => c.path.startsWith('/org/pos/codes'))).toBe(false)
  await user.type(input, '9')

  expect(await within(screen.getByRole('tabpanel', { name: fr.pos.tabs.code })).findByText(LOOKUP.event.title)).toBeTruthy()
  expect(calls.find((c) => c.path === '/org/pos/codes/4F7K29')!.org).toBe('org-a')
  expect(screen.getByText(dh(LOOKUP.amount_mad))).toBeTruthy()
  expect(screen.getByText('Yazid A.')).toBeTruthy()
  expect(screen.getByText(fr.pos.code.status.payable)).toBeTruthy()

  await user.clear(input)
  await user.type(input, '4F7KU9')
  expect(screen.getByText(i18n.t('pos.code.invalidChars', { chars: 'U' }))).toBeTruthy()
  expect(input.getAttribute('aria-describedby')).toBe('pos-code-error pos-code-hint')
})

it('print view: one slip per ticket, one 80 mm page each, QR value = `qr` exactly, holder label only, no full name', async () => {
  const calls = stubApi()
  const { queryClient } = renderApp(`/pos/print/${PRINT.order_id}`)

  const slips = await screen.findAllByRole('article')
  expect(slips).toHaveLength(PRINT.tickets.length)
  slips.forEach((s) => expect(s.style.height).toBe('140mm'))
  expect([...document.querySelectorAll('style')].some((s) => s.textContent?.includes('@page { size: 80mm 140mm'))).toBe(true)
  expect(screen.queryByRole('navigation')).toBeNull() // outside the shell chrome
  expect(screen.getByRole('main').contains(slips[0]!)).toBe(true)

  PRINT.tickets.forEach((ticket, i) => {
    const slip = within(slips[i]!)
    const expected = render(<QRCode value={ticket.qr} size={256} level="M" />)
    expect(qrPaths(slip.getByRole('img', { name: i18n.t('pos.print.qr', { no: ticket.ticket_no }) }))).toEqual(qrPaths(expected.container))
    expected.unmount()
    expect(slip.getByText(ticket.ticket_no)).toBeTruthy()
    expect(slip.getByText(ticket.holder_label)).toBeTruthy()
    expect(slip.getByText('Atlas FC')).toBeTruthy() // club name
    expect(slip.getByText(spaced(i18n.t('pos.print.order', { no: '4D5C 6B7A' })))).toBeTruthy()
  })
  const other = render(<QRCode value={PRINT.tickets[1]!.qr} size={256} level="M" />)
  expect(qrPaths(slips[0]!.querySelector('svg')!)).not.toEqual(qrPaths(other.container))
  other.unmount()

  for (const t of PRINT.tickets) expect(document.body.textContent).not.toContain(t.holder_name)
  expect(document.body.textContent).not.toMatch(/Aqel|Bennani/)

  // One GET per visit (every call is a print): not on focus, reconnect, or a failed /org/me refetch.
  act(() => focusManager.setFocused(false))
  act(() => focusManager.setFocused(true))
  act(() => onlineManager.setOnline(false))
  act(() => onlineManager.setOnline(true))
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input) => {
      calls.push({ path: String(input).replace(/^\/v1/, ''), org: null, method: 'GET', body: undefined })
      return serverError()
    }),
  )
  await act(() => queryClient.refetchQueries({ queryKey: ['me'] }))
  expect(screen.getAllByRole('article')).toHaveLength(2) // the page stays mounted
  expect(calls.filter((c) => c.path === PRINT_PATH)).toHaveLength(1)
  expect(calls.find((c) => c.path === PRINT_PATH)!.org).toBe('org-a')
})

it('print view: a ticket that is not VALID (qr null) prints "Billet non valable" and no QR', async () => {
  const blocked = { ...PRINT, tickets: [{ ...PRINT.tickets[0]!, status: 'BLOCKED' as const, qr: null }] }
  stubApi({ [PRINT_PATH]: () => Response.json(blocked) })
  renderApp(`/pos/print/${PRINT.order_id}`)
  const [slip] = await screen.findAllByRole('article')
  expect(within(slip!).getByText(i18n.t('pos.print.notValid', { status: fr.pos.print.status.BLOCKED }))).toBeTruthy()
  expect(slip!.querySelector('svg')).toBeNull()
  expect(within(slip!).getByText(PRINT.tickets[0]!.ticket_no)).toBeTruthy()
})

it('print view: a failed print GET is not retried by itself; "Réessayer" is the explicit reprint', async () => {
  const user = userEvent.setup()
  let fail = true
  const calls = stubApi({ [PRINT_PATH]: () => (fail ? serverError() : Response.json(PRINT)) })
  renderApp(`/pos/print/${PRINT.order_id}`)

  const retry = await screen.findByRole('button', { name: fr.common.retry })
  await new Promise((r) => setTimeout(r, 1_500)) // past the default first retry delay (1 s)
  expect(calls.filter((c) => c.path === PRINT_PATH)).toHaveLength(1)

  fail = false
  await user.click(retry)
  expect(await screen.findAllByRole('article')).toHaveLength(2)
  expect(calls.filter((c) => c.path === PRINT_PATH)).toHaveLength(2)
})

// ---- BO-13b: the writes and the CASHIER shell ----

const SALE_ORDER = { id: PRINT.order_id, status: 'PAID', channel: 'POS', total_mad: 5_000, items: [], ticket_ids: ['t-1'] }
const [, SUD] = PRICES as [Price, Price, Price]
const submitSale = (cents: number) => screen.getByRole('button', { name: spaced(i18n.t('pos.sale.submit', { amount: formatDh(cents, 'fr') })) })
const apiError = (status: number, code: string, details: Record<string, unknown> = {}) => Response.json({ code, message: 'x', details }, { status })
const withRole = (role: Me['memberships'][number]['role']): Me => ({ ...ME, memberships: [{ ...ME.memberships[0]!, role }] })

/** One Virage Sud place for "Yazid Aqel", ready to submit. */
async function fillSale(user: ReturnType<typeof userEvent.setup>) {
  const more = await screen.findByRole('button', { name: i18n.t('pos.sale.more', { name: priceName(SUD) }) })
  await user.click(more)
  await user.type(document.getElementById('pos-holder-0-name')!, 'Yazid Aqel')
}

it('a 422 holder field of the sale maps to its form row (places in item order; "même nom" → row 0)', () => {
  const items = [{ qty: 2 }, { qty: 1 }]
  expect(holderField('items[0].holders[1].name', items, false)).toBe('holders.1.name')
  expect(holderField('items[1].holders[0].phone', items, false)).toBe('holders.2.phone')
  expect(holderField('items[1].holders[0].name', items, true)).toBe('holders.0.name')
  expect(holderField('items[1].holders', items, false)).toBeNull()
  expect(holderField('sale_id', items, false)).toBeNull()
})

it('B11 Vente: a retried sale sends the same sale_id; success opens the print view; the next sale gets a new id', async () => {
  const user = userEvent.setup()
  let attempt = 0
  const calls = stubApi({
    '/org/pos/orders': () => {
      attempt += 1
      if (attempt === 1) throw new TypeError('Failed to fetch') // the answer is lost: the sale may exist
      return Response.json(SALE_ORDER, { status: attempt === 2 ? 200 : 201 })
    },
  })
  const { queryClient, router } = renderApp('/pos')

  await fillSale(user)
  await user.click(submitSale(5_000))
  expect(await screen.findByText(fr.errors.NETWORK_ERROR)).toBeTruthy()
  expect(router.state.location.pathname).toBe('/pos')
  await user.click(submitSale(5_000))
  await waitFor(() => expect(router.state.location.pathname).toBe(`/pos/print/${PRINT.order_id}`))
  expect(await screen.findAllByRole('article')).toHaveLength(PRINT.tickets.length)

  const posts = calls.filter((c) => c.method === 'POST' && c.path === '/org/pos/orders')
  expect(posts).toHaveLength(2)
  const first = posts[0]!.body as { sale_id: string }
  expect(first.sale_id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  expect(posts[1]!.body).toEqual(first)
  expect(first).toEqual({ sale_id: first.sale_id, event_id: 1, items: [{ price_category_id: SUD.id, qty: 1, holders: [{ name: 'Yazid Aqel' }] }] })
  expect(posts.every((c) => c.org === 'org-a')).toBe(true)

  // Back at the till: a fresh form, a new sale_id.
  await user.click(screen.getByRole('link', { name: fr.pos.print.back }))
  await fillSale(user)
  await user.click(submitSale(5_000))
  await waitFor(() => expect(calls.filter((c) => c.method === 'POST' && c.path === '/org/pos/orders')).toHaveLength(3))
  const third = calls.filter((c) => c.path === '/org/pos/orders').at(-1)!.body as { sale_id: string }
  expect(third.sale_id).not.toBe(first.sale_id)

  // Every cached query is this organizer's (or /org/me, which has none).
  const keys = queryClient.getQueryCache().getAll().map((q) => q.queryKey)
  expect(keys.every((k) => (k[0] === 'org' && k[1] === 'org-a') || k[0] === 'me')).toBe(true)
  expect(keys.some((k) => k[2] === 'pos')).toBe(true)
})

it('B11 Vente: server refusals have their copy; a 422 on a holder name lands on that row', async () => {
  const user = userEvent.setup()
  let answer = () => apiError(409, 'PER_ACCOUNT_LIMIT', { limit: 3, held: 0 })
  stubApi({ '/org/pos/orders': () => answer() })
  renderApp('/pos')
  await fillSale(user)

  await user.click(submitSale(5_000))
  expect((await screen.findByRole('alert')).textContent).toBe(i18n.t('errors.PER_ACCOUNT_LIMIT', { limit: 3 }))

  answer = () => apiError(409, 'POS_SESSION_REQUIRED')
  await user.click(submitSale(5_000))
  expect(await screen.findByText(fr.errors.POS_SESSION_REQUIRED)).toBeTruthy()

  answer = () => apiError(422, 'VALIDATION_FAILED', { fields: { 'items[0].holders[0].name': 'pattern' } })
  await user.click(submitSale(5_000))
  await waitFor(() => expect(document.getElementById('pos-holder-0-name-error')?.textContent).toBe(fr.common.fieldInvalid))
  expect(screen.queryByText(fr.errors.VALIDATION_FAILED)).toBeNull()
})

it('B11: without an open till, sale and collect are disabled; opening one posts its label', async () => {
  const user = userEvent.setup()
  let current: PosSession | null = null
  const calls = stubApi({
    '/org/pos/session': () => Response.json({ session: current }),
    '/org/pos/sessions': () => {
      current = { ...SESSION, label: 'Guichet Sud', orders: 0, tickets: 0, prints: 0, collected_mad: 0 }
      return Response.json(current, { status: 201 })
    },
  })
  renderApp('/pos')

  expect(await screen.findByText(fr.pos.session.none)).toBeTruthy()
  await fillSale(user)
  expect(submitSale(5_000)).toHaveProperty('disabled', true)
  expect(screen.getByText(fr.pos.sale.needSession)).toBeTruthy()

  await user.click(screen.getByRole('button', { name: fr.pos.session.open }))
  const dialog = within(await screen.findByRole('dialog'))
  await user.click(dialog.getByRole('button', { name: fr.pos.open.submit }))
  expect(await dialog.findByText(fr.pos.open.labelInvalid)).toBeTruthy()
  await user.type(dialog.getByLabelText(fr.pos.open.label), '  Guichet Sud ')
  await user.click(dialog.getByRole('button', { name: fr.pos.open.submit }))

  expect(await screen.findByText('Guichet Sud')).toBeTruthy()
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(calls.find((c) => c.method === 'POST')).toMatchObject({ path: '/org/pos/sessions', org: 'org-a', body: { label: 'Guichet Sud' } })
  await waitFor(() => expect(submitSale(5_000)).toHaveProperty('disabled', false))
})

it('B11 close: the counted DH become centimes, the gap shows before confirming, then the frozen variance', async () => {
  const user = userEvent.setup()
  let current: PosSession | null = SESSION
  const closed: ClosedSession = {
    ...SESSION,
    staff: { id: 'st-1', name: 'Samira' },
    closed_at: '2026-11-07T18:00:00Z',
    expected_mad: 465_050,
    declared_mad: 464_050,
    variance_mad: -1_000,
  }
  const calls = stubApi({
    '/org/pos/session': () => Response.json({ session: current }),
    '/org/pos/sessions/s-1/close': () => {
      current = null
      return Response.json(closed)
    },
  })
  renderApp('/pos')

  await user.click(await screen.findByRole('button', { name: fr.pos.session.close }))
  const dialog = within(await screen.findByRole('dialog'))
  expect(dialog.getByText(spaced(i18n.t('pos.close.title', { label: SESSION.label })))).toBeTruthy()
  expect(dialog.getByText(dh(SESSION.collected_mad))).toBeTruthy() // expected

  const declared = dialog.getByLabelText(fr.pos.close.declared)
  await user.type(declared, '4650,50')
  expect(dialog.getByText(fr.pos.close.noGap)).toBeTruthy()
  await user.clear(declared)
  await user.type(declared, '4660,50')
  expect(dialog.getByText(spaced(i18n.t('pos.amount', { amount: formatDh(1_000, 'fr', 'exceptZero') })))).toBeTruthy()
  await user.clear(declared)
  await user.type(declared, '4640,50')
  const gap = spaced(i18n.t('pos.amount', { amount: formatDh(-1_000, 'fr') }))
  expect(dialog.getByText(gap)).toBeTruthy()
  expect(calls.some((c) => c.method === 'POST')).toBe(false) // nothing sent before confirming

  await user.click(dialog.getByRole('button', { name: fr.pos.close.submit }))
  expect(await dialog.findByText(spaced(i18n.t('pos.close.doneTitle', { label: SESSION.label })))).toBeTruthy()
  expect(dialog.getByText(dh(closed.declared_mad))).toBeTruthy()
  expect(dialog.getByText(gap)).toBeTruthy()
  expect(calls.find((c) => c.method === 'POST')).toMatchObject({ path: '/org/pos/sessions/s-1/close', org: 'org-a', body: { declared_mad: 464_050 } })

  // The bar behind has no till any more; the frozen result stays until closed.
  expect(await screen.findByText(fr.pos.session.none)).toBeTruthy()
  await user.click(dialog.getByRole('button', { name: fr.common.close }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
})

it('B11 close: an amount that is not DH is refused, nothing sent', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp('/pos')
  await user.click(await screen.findByRole('button', { name: fr.pos.session.close }))
  const dialog = within(await screen.findByRole('dialog'))
  await user.type(dialog.getByLabelText(fr.pos.close.declared), '4 640,505')
  await user.click(dialog.getByRole('button', { name: fr.pos.close.submit }))
  expect(await dialog.findByText(fr.pos.close.declaredInvalid)).toBeTruthy()
  expect(calls.some((c) => c.method === 'POST')).toBe(false)
})

it('B11 Code cash: collect sends the exact amount and opens the print view', async () => {
  const user = userEvent.setup()
  const calls = stubApi({ '/org/pos/codes/4F7K29/collect': () => Response.json({ order_id: PRINT.order_id, status: 'PAID', ticket_ids: ['t-1', 't-2'] }) })
  const { router } = renderApp('/pos')
  await user.click(await screen.findByRole('tab', { name: fr.pos.tabs.code }))
  await user.type(screen.getByRole('textbox', { name: fr.pos.code.label }), 'jmh 4f7k 29')
  await user.click(await screen.findByRole('button', { name: spaced(i18n.t('pos.code.submit', { amount: formatDh(LOOKUP.amount_mad, 'fr') })) }))
  await waitFor(() => expect(router.state.location.pathname).toBe(`/pos/print/${PRINT.order_id}`))
  expect(calls.find((c) => c.method === 'POST')).toMatchObject({ path: '/org/pos/codes/4F7K29/collect', org: 'org-a', body: { amount_mad: 30_000 } })
})

it('B11 Code cash: a 409 CODE_NOT_PAYABLE tells the cashier not to keep the money, and collect stays off', async () => {
  const user = userEvent.setup()
  stubApi({ '/org/pos/codes/4F7K29/collect': () => apiError(409, 'CODE_NOT_PAYABLE', { status: 'EXPIRED' }) })
  const { router } = renderApp('/pos')
  await user.click(await screen.findByRole('tab', { name: fr.pos.tabs.code }))
  await user.type(screen.getByRole('textbox', { name: fr.pos.code.label }), '4F7K29')
  const collect = await screen.findByRole('button', { name: spaced(i18n.t('pos.code.submit', { amount: formatDh(LOOKUP.amount_mad, 'fr') })) })
  await user.click(collect)
  expect((await screen.findByRole('alert')).textContent).toBe(fr.pos.code.notPayable)
  expect(fr.pos.code.notPayable).toContain("ne gardez pas l'argent")
  expect(collect).toHaveProperty('disabled', true)
  expect(router.state.location.pathname).toBe('/pos')
})

it('CASHIER: lands on the Guichet, the sidebar shows only "Guichet", every other page sends back to it', async () => {
  const user = userEvent.setup()
  const calls = stubApi({ '/org/me': () => Response.json(withRole('CASHIER')) })
  const { router } = renderApp('/')
  expect(await screen.findByRole('heading', { level: 1, name: fr.pos.title })).toBeTruthy()
  expect(router.state.location.pathname).toBe('/pos')
  const nav = within(screen.getByRole('navigation', { name: fr.nav.label }))
  expect(nav.getAllByRole('link').map((l) => l.textContent)).toEqual([fr.nav.pos])
  expect(nav.queryByText(fr.nav.soon)).toBeNull()
  expect(screen.getAllByText(fr.roles.CASHIER).length).toBeGreaterThan(0)

  for (const path of ['/events', '/events/1/sales', '/settings/venues', '/devices', '/live', '/nowhere']) {
    await act(() => router.navigate(path))
    await waitFor(() => expect(router.state.location.pathname).toBe('/pos'))
  }
  await act(() => router.navigate(`/pos/print/${PRINT.order_id}`))
  expect(await screen.findAllByRole('article')).toHaveLength(PRINT.tickets.length)
  await user.click(screen.getByRole('link', { name: fr.pos.print.back }))
  expect(router.state.location.pathname).toBe('/pos')
  await user.click(await screen.findByRole('tab', { name: fr.pos.tabs.code }))
  await user.type(screen.getByRole('textbox', { name: fr.pos.code.label }), '4F7K29')
  expect(await within(screen.getByRole('tabpanel', { name: fr.pos.tabs.code })).findByText(LOOKUP.event.title)).toBeTruthy()

  // Only what the API lets a CASHIER call (04-api §Organizer, Lot 3).
  const allowed = (c: Call) =>
    c.path === '/org/me' || c.path.startsWith('/org/pos/') || (c.method === 'GET' && /^\/org\/events(\/\d+(\/prices)?)?$/.test(c.path))
  expect(calls.length).toBeGreaterThan(0)
  expect(calls.filter((c) => !allowed(c))).toEqual([])
  expect(calls.some((c) => c.path === '/org/events/1/prices')).toBe(true)
})

it.each(['ADMIN', 'OPS'] as const)('%s keeps the whole back-office and the Guichet', async (role) => {
  stubApi({ '/org/me': () => Response.json(withRole(role)) })
  const { router } = renderApp('/events')
  const nav = within(await screen.findByRole('navigation', { name: fr.nav.label }))
  expect(router.state.location.pathname).toBe('/events')
  expect(nav.getByRole('link', { name: fr.nav.pos })).toBeTruthy()
  expect(nav.getByRole('link', { name: fr.nav.events })).toBeTruthy()
  await act(() => router.navigate('/pos'))
  expect(await screen.findByRole('heading', { level: 1, name: fr.pos.title })).toBeTruthy()
})

it('FINANCE (403 on /org/pos/**) has no Guichet: /pos and the print view send it home', async () => {
  const calls = stubApi({ '/org/me': () => Response.json(withRole('FINANCE')) })
  const { router } = renderApp('/pos')
  const nav = within(await screen.findByRole('navigation', { name: fr.nav.label }))
  expect(router.state.location.pathname).toBe('/')
  expect(nav.queryByRole('link', { name: fr.nav.pos })).toBeNull()
  await act(() => router.navigate(`/pos/print/${PRINT.order_id}`))
  await waitFor(() => expect(router.state.location.pathname).toBe('/'))
  expect(calls.some((c) => c.path.startsWith('/org/pos'))).toBe(false)
})

// ---- BO-13b review: lost answers and stops ----

const collectButton = () => screen.findByRole('button', { name: spaced(i18n.t('pos.code.submit', { amount: formatDh(LOOKUP.amount_mad, 'fr') })) })
const salePosts = (calls: Call[]) => calls.filter((c) => c.method === 'POST' && c.path === '/org/pos/orders').map((c) => c.body as { sale_id: string })

it('B11 Code cash: a lost collect answer, then a lookup that reads PAID, keeps Encaisser on; the retry (200) opens the print view', async () => {
  const user = userEvent.setup()
  let collected = false
  let attempt = 0
  const calls = stubApi({
    '/org/pos/codes/4F7K29': () => Response.json(collected ? { ...LOOKUP, status: 'PAID', payable: false, reserved_until: null } : LOOKUP),
    '/org/pos/codes/4F7K29/collect': () => {
      attempt += 1
      collected = true // the server took it the first time
      if (attempt === 1) throw new TypeError('Failed to fetch')
      return Response.json({ order_id: PRINT.order_id, status: 'PAID', ticket_ids: ['t-1', 't-2'] })
    },
  })
  const { router } = renderApp('/pos')
  await user.click(await screen.findByRole('tab', { name: fr.pos.tabs.code }))
  await user.type(screen.getByRole('textbox', { name: fr.pos.code.label }), '4F7K29')
  await user.click(await collectButton())

  expect(await screen.findByText(fr.errors.NETWORK_ERROR)).toBeTruthy()
  expect(await screen.findByText(fr.pos.code.paidHint)).toBeTruthy() // the lookup refetched: PAID
  expect(screen.queryByText(fr.pos.code.notPayable)).toBeNull()
  const collect = await collectButton()
  expect(collect).toHaveProperty('disabled', false)

  await user.click(collect)
  await waitFor(() => expect(router.state.location.pathname).toBe(`/pos/print/${PRINT.order_id}`))
  expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{ amount_mad: 30_000 }, { amount_mad: 30_000 }])
})

it('B11 Code cash: an expired lookup says not to keep the money and offers no collect', async () => {
  stubApi({ '/org/pos/codes/4F7K29': () => Response.json({ ...LOOKUP, status: 'EXPIRED', payable: false }) })
  const user = userEvent.setup()
  renderApp('/pos')
  await user.click(await screen.findByRole('tab', { name: fr.pos.tabs.code }))
  await user.type(screen.getByRole('textbox', { name: fr.pos.code.label }), '4F7K29')
  expect(await screen.findByText(fr.pos.code.notPayable)).toBeTruthy()
  expect(await collectButton()).toHaveProperty('disabled', true)
})

it('B11 Vente: 422 TAKEN on sale_id stops the form ("n\'encaissez pas une deuxième fois") until "Nouvelle vente"; then a new id', async () => {
  const user = userEvent.setup()
  let taken = true
  const calls = stubApi({
    '/org/pos/orders': () =>
      taken ? apiError(422, 'VALIDATION_FAILED', { fields: { sale_id: 'already used' }, reason: 'TAKEN' }) : Response.json(SALE_ORDER, { status: 201 }),
  })
  const { router } = renderApp('/pos')
  await fillSale(user)
  await user.click(submitSale(5_000))

  expect((await screen.findByRole('alert')).textContent).toContain(fr.pos.sale.taken)
  expect(submitSale(5_000)).toHaveProperty('disabled', true)
  expect(salePosts(calls)).toHaveLength(1)

  await user.click(screen.getByRole('button', { name: fr.pos.sale.newSale }))
  expect(screen.queryByText(fr.pos.sale.taken)).toBeNull()
  expect(document.getElementById('pos-holder-0-name')).toBeNull() // the form is empty again
  taken = false
  await fillSale(user)
  await user.click(submitSale(5_000))
  await waitFor(() => expect(router.state.location.pathname).toBe(`/pos/print/${PRINT.order_id}`))
  const [first, second] = salePosts(calls)
  expect(second!.sale_id).not.toBe(first!.sale_id)
})

it('B11 Vente: a replay whose total is not the one on screen warns before printing', async () => {
  const user = userEvent.setup()
  stubApi({ '/org/pos/orders': () => Response.json({ ...SALE_ORDER, total_mad: 10_050 }) })
  const { router } = renderApp('/pos')
  await fillSale(user)
  await user.click(submitSale(5_000))

  const alert = await screen.findByRole('alert')
  expect(spaced(alert.textContent!)).toContain(spaced(i18n.t('pos.sale.replayed', { amount: formatDh(10_050, 'fr'), shown: formatDh(5_000, 'fr') })))
  expect(router.state.location.pathname).toBe('/pos')
  expect(submitSale(5_000)).toHaveProperty('disabled', true)
  await user.click(screen.getByRole('button', { name: fr.pos.sale.printTickets }))
  await waitFor(() => expect(router.state.location.pathname).toBe(`/pos/print/${PRINT.order_id}`))
})

it('B11 Vente: the sale in progress and its sale_id survive a switch to Code cash and back', async () => {
  const user = userEvent.setup()
  let attempt = 0
  const calls = stubApi({
    '/org/pos/orders': () => {
      attempt += 1
      if (attempt === 1) throw new TypeError('Failed to fetch')
      return Response.json(SALE_ORDER)
    },
  })
  const { router } = renderApp('/pos')
  await fillSale(user)
  await user.click(submitSale(5_000))
  expect(await screen.findByText(fr.errors.NETWORK_ERROR)).toBeTruthy()

  await user.click(screen.getByRole('tab', { name: fr.pos.tabs.code }))
  expect(screen.queryByRole('button', { name: spaced(i18n.t('pos.sale.submit', { amount: formatDh(5_000, 'fr') })) })).toBeNull() // hidden
  await user.click(screen.getByRole('tab', { name: fr.pos.tabs.sale }))
  expect((document.getElementById('pos-holder-0-name') as HTMLInputElement).value).toBe('Yazid Aqel')

  await user.click(submitSale(5_000))
  await waitFor(() => expect(router.state.location.pathname).toBe(`/pos/print/${PRINT.order_id}`))
  const [first, second] = salePosts(calls)
  expect(second).toEqual(first)
})

it('B11 close: a 409 POS_SESSION_CLOSED (an earlier answer lost) says the till is closed and where the result is', async () => {
  const user = userEvent.setup()
  stubApi({ '/org/pos/sessions/s-1/close': () => apiError(409, 'POS_SESSION_CLOSED') })
  renderApp('/pos')
  await user.click(await screen.findByRole('button', { name: fr.pos.session.close }))
  const dialog = within(await screen.findByRole('dialog'))
  await user.type(dialog.getByLabelText(fr.pos.close.declared), '4650,50')
  await user.click(dialog.getByRole('button', { name: fr.pos.close.submit }))
  expect(await dialog.findByText(fr.errors.POS_SESSION_CLOSED)).toBeTruthy()
  expect(fr.errors.POS_SESSION_CLOSED).toContain('Opérations')
})

it('B11 close: only the gap value is a live region', async () => {
  const user = userEvent.setup()
  stubApi()
  renderApp('/pos')
  await user.click(await screen.findByRole('button', { name: fr.pos.session.close }))
  const dialog = await screen.findByRole('dialog')
  await user.type(within(dialog).getByLabelText(fr.pos.close.declared), '4650,50')
  const live = [...dialog.querySelectorAll('[aria-live]')]
  expect(live).toHaveLength(1)
  expect(live[0]!.textContent).toBe(fr.pos.close.noGap)
})
