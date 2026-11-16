import sharedNames from '../../../../../docs/holder-name-vectors.json'
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
import type { EventDetail, EventSummary, Price } from '@/features/events/api'
import type { PrintData, SaleOrder } from '@/features/pos/api'
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'
import { formatDateTime } from '@/lib/time'
import { deliverySplit, invitationItems, invitationSchema, type Invitation } from './api'

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
  session.clear()
})

// Fixtures in the 04-api shapes. source: contract (§Invitations; POST/GET /org/events/{id}/invitations,
// GET /org/invitations/{id}/tickets = the till's print shape; prices with audience INVITE). BO-25 is contract-first:
// the real run replaces these with captured answers.
const CASA = 'Africa/Casablanca'
const HOUR = 3_600_000
const at = (ms: number) => new Date(Date.now() + ms).toISOString()
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const STARTS = at(7 * 24 * HOUR)
const SUMMARY: EventSummary = {
  id: 1,
  title: 'Atlas FC vs Rapid Oued',
  event_type: 'FOOTBALL_MATCH',
  starts_at: STARTS,
  status: 'ON_SALE',
  venue: { id: 'v-1', name: 'Grand Stade', timezone: CASA },
  quota: 39600,
  sold: 1250,
}
const EVENT: EventDetail = {
  id: 1,
  venue_id: 'v-1',
  timezone: CASA,
  series_id: null,
  pass_tickets: false,
  event_type: 'FOOTBALL_MATCH',
  title: SUMMARY.title,
  starts_at: STARTS,
  doors_open_at: null,
  sale_starts_at: null,
  status: 'ON_SALE',
  refund_cutoff_at: at(7 * 24 * HOUR - HOUR),
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: {},
  created_at: '2026-11-01T10:00:00Z',
}
const PLEIN: Price = { id: 'p-plein', zone_id: 1, zone: { code: 'HONNEUR', name: "Tribune d'honneur" }, name: 'Plein tarif', price_mad: 20_000, quota: 3000, sold: 10, audience: 'ALL' }
const PRESSE: Price = { id: 'p-presse', zone_id: 7, zone: { code: 'PRESSE', name: 'Tribune de presse' }, name: 'Presse', price_mad: 0, quota: 40, sold: 3, audience: 'INVITE' }
const INVITES: Price = { id: 'p-invites', zone_id: 1, zone: { code: 'HONNEUR', name: "Tribune d'honneur" }, name: 'Invités', price_mad: 0, quota: 50, sold: 10, audience: 'INVITE' }
const PRICES = [PLEIN, PRESSE, INVITES]
const BATCH: Invitation = {
  id: '0192f0aa-0000-7000-8000-00000000b7a1',
  created_at: '2026-11-15T14:05:00Z',
  invited_by: { id: 'st-1', name: 'Youssef Amrani' },
  places: 3,
  items: [{ price_category_id: PRESSE.id, name: 'Presse', zone: { id: 7, code: 'PRESSE', name: 'Tribune de presse' }, qty: 3 }],
  tickets: [
    { id: 't-1', ticket_no: '4D5C 6B7A', status: 'VALID', holder_name: 'Nadia Alaoui', holder_label: 'Nadia A.', delivery: 'WHATSAPP' },
    { id: 't-2', ticket_no: '5E6F 7A8B', status: 'VALID', holder_name: 'Karim Tazi', holder_label: 'Karim T.', delivery: 'WHATSAPP' },
    { id: 't-3', ticket_no: '6F7A 8B9C', status: 'VALID', holder_name: 'Omar Benali', holder_label: 'Omar B.', delivery: 'PAPER' },
  ],
}
const PRINT: PrintData = {
  order_id: BATCH.id,
  event: { id: 1, title: SUMMARY.title, starts_at: STARTS, timezone: CASA, venue: { name: 'Grand Stade' } },
  tickets: [
    { id: 't-3', ticket_no: '6F7A 8B9C', status: 'VALID', zone: { code: 'PRESSE', name: 'Tribune de presse' }, gate_codes: ['P1'], holder_label: 'Omar B.', unit_price_mad: 0, qr: 'AQAAAAHxyz-invite_one' },
  ],
}
const ORDER: SaleOrder = { id: '0192f0aa-0000-7000-8000-00000000b7a2', status: 'PAID', channel: 'POS', total_mad: 0, ticket_ids: ['t-10', 't-11', 't-12'] }
const LIST = '/org/events/1/invitations'
const PRINT_PATH = `/org/invitations/${BATCH.id}/tickets`

type Call = { path: string; org: string | null; method: string; body: unknown }
type Overrides = Partial<Record<string, (c: Call) => Response | Promise<Response>>>
function stubApi(role: Role = 'OPS', overrides: Overrides = {}) {
  const calls: Call[] = []
  const json: Record<string, unknown> = {
    '/org/me': me(role),
    '/org/events': [SUMMARY],
    '/org/events/1': EVENT,
    '/org/events/1/prices': PRICES,
    [LIST]: { invitations: [BATCH] },
    [PRINT_PATH]: PRINT,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      const call = { path, org: new Headers(init?.headers).get('X-Organizer-Id'), method: init?.method ?? 'GET', body }
      calls.push(call)
      const override = overrides[`${call.method} ${path}`] ?? overrides[path]
      if (override) return override(call)
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

type User = ReturnType<typeof userEvent.setup>
const posts = (calls: Call[]) => calls.filter((c) => c.method === 'POST')
const conflict = (code: string, details: Record<string, unknown>) => () => Response.json({ code, message: 'x', details }, { status: 409 })
const spaced = (s: string) => s.replace(/\s+/gu, ' ')

async function openSheet(user: User) {
  await user.click(await screen.findByRole('button', { name: fr.invitations.new }))
  return within(await screen.findByRole('dialog', { name: fr.invitations.new }))
}
async function fill(user: User, sheet: ReturnType<typeof within>, qty: string, names: string[], phones: string[] = []) {
  await user.click(sheet.getByRole('combobox', { name: fr.invitations.sheet.category }))
  await user.click(await screen.findByRole('option', { name: new RegExp(`^${PRESSE.zone.name} · ${PRESSE.name}`) }))
  const q = sheet.getByLabelText(fr.invitations.sheet.qty)
  await user.clear(q)
  await user.type(q, qty)
  for (const [i, name] of names.entries()) await user.type(document.getElementById(`invite-holder-${i}-name`)!, name)
  for (const [i, phone] of phones.entries()) if (phone) await user.type(document.getElementById(`invite-holder-${i}-phone`)!, phone)
}
const submit = (sheet: ReturnType<typeof within>, n: number) => sheet.getByRole('button', { name: i18n.t('invitations.sheet.submit', { count: n }) })

// ---- zod and pure helpers ----

const schema = invitationSchema(i18n.t, PRICES)
const parse = (v: Partial<{ price_category_id: string; qty: string; same_name: boolean; holders: { name: string; phone: string }[] }>) =>
  schema.safeParse({ price_category_id: PRESSE.id, qty: '1', same_name: false, holders: [{ name: 'Nadia Alaoui', phone: '' }], ...v })
const issues = (r: ReturnType<typeof parse>) => (r.success ? [] : r.error.issues.map((i) => i.path.join('.')))

it('zod: holder names per every shared §Holder names vector (docs/holder-name-vectors.json)', () => {
  const vectors: { input: string; ok: boolean }[] = sharedNames
  expect(vectors.length).toBeGreaterThan(0)
  for (const v of vectors) expect([v.input, parse({ holders: [{ name: v.input, phone: '' }] }).success]).toEqual([v.input, v.ok])
})

it('zod: phone optional (Moroccan → E.164); qty 1 to 500 and within the remaining quota; at least one row', () => {
  expect(parse({}).success).toBe(true)
  expect(parse({ holders: [{ name: 'Nadia Alaoui', phone: '06 12 34 56 78' }] }).success).toBe(true)
  expect(issues(parse({ holders: [{ name: 'Nadia Alaoui', phone: '12' }] }))).toEqual(['holders.0.phone'])
  expect(issues(parse({ holders: [] }))).toEqual(['holders'])
  expect(issues(parse({ qty: '0' }))).toEqual(['qty'])
  expect(issues(parse({ qty: '2.5' }))).toEqual(['qty'])
  const big: Price = { ...INVITES, quota: 2000, sold: 0 }
  const s = invitationSchema(i18n.t, [big])
  const rows = (n: number) => Array.from({ length: n }, () => ({ name: 'Invité Partenaire', phone: '' }))
  expect(s.safeParse({ price_category_id: big.id, qty: '500', same_name: false, holders: rows(500) }).success).toBe(true)
  expect(s.safeParse({ price_category_id: big.id, qty: '501', same_name: false, holders: rows(501) }).success).toBe(false)
  // Presse has 40 − 3 = 37 left.
  const over = parse({ qty: '38', same_name: true })
  expect(over.success ? null : over.error.issues[0]!.message).toBe(i18n.t('invitations.sheet.overQuota', { count: 37, category: 'Presse' }))
})

it('"même nom" fills every place with the first row (name and phone); the split counts WhatsApp and paper places', () => {
  const first = { name: ' Invité Partenaire ', phone: '' }
  expect(invitationItems(INVITES, 3, [first, { name: '', phone: 'x' }], true)).toEqual([
    { price_category_id: INVITES.id, qty: 3, holders: [{ name: 'Invité Partenaire' }, { name: 'Invité Partenaire' }, { name: 'Invité Partenaire' }] },
  ])
  const rows = [{ name: 'Nadia Alaoui', phone: '0612345678' }, { name: 'Karim Tazi', phone: '' }, { name: 'Omar Benali', phone: '+212 7 12 34 56 78' }]
  expect(invitationItems(PRESSE, 3, rows, false)[0]!.holders).toEqual([
    { name: 'Nadia Alaoui', phone: '+212612345678' },
    { name: 'Karim Tazi' },
    { name: 'Omar Benali', phone: '+212712345678' },
  ])
  expect(deliverySplit(3, rows, false)).toEqual({ whatsapp: 2, paper: 1 })
  expect(deliverySplit(10, rows, true)).toEqual({ whatsapp: 10, paper: 0 })
  expect(deliverySplit(10, [first], true)).toEqual({ whatsapp: 0, paper: 10 })
})

// ---- B7 ----

it('sidebar "Accréditations" → event picker → B7: one card per INVITE category, issued of quota; queries carry the organizer', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  const { queryClient, router } = renderApp('/')
  const nav = within(await screen.findByRole('navigation', { name: fr.nav.label }))
  await user.click(nav.getByRole('link', { name: fr.nav.invitations }))
  expect(await screen.findByRole('heading', { level: 1, name: fr.invitations.title })).toBeTruthy()
  await user.click(await screen.findByRole('link', { name: SUMMARY.title }))
  await waitFor(() => expect(router.state.location.pathname).toBe('/events/1/invitations'))
  expect(nav.getByRole('link', { name: fr.nav.invitations }).getAttribute('aria-current')).toBe('page')

  const cards = within(await screen.findByRole('region', { name: fr.invitations.categories }))
  expect(cards.getByText('Presse')).toBeTruthy()
  expect(cards.getByText('Tribune de presse')).toBeTruthy()
  expect(cards.getByText('Invités')).toBeTruthy()
  expect(cards.getByText(i18n.t('invitations.issuedOf', { quota: '40' }))).toBeTruthy()
  expect(cards.queryByText('Plein tarif')).toBeNull() // only INVITE categories
  await screen.findByText(BATCH.invited_by.name)

  const orgCalls = calls.filter((c) => c.path !== '/org/me')
  expect(orgCalls.map((c) => c.path)).toEqual(expect.arrayContaining([LIST, '/org/events/1/prices', '/org/events/1']))
  expect(new Set(orgCalls.map((c) => c.org))).toEqual(new Set(['org-a']))
  const keys = queryClient.getQueryCache().getAll().map((q) => q.queryKey)
  expect(keys).toContainEqual(['org', 'org-a', 'events', '1', 'invitations'])
  expect(keys.filter((k) => k[0] !== 'me').every((k) => k[0] === 'org' && k[1] === 'org-a')).toBe(true)
})

it('batches: date in the venue zone, émis par, category · zone, places; a row opens on its holders with status and WhatsApp/Papier', async () => {
  const user = userEvent.setup()
  stubApi()
  renderApp('/events/1/invitations')
  const date = formatDateTime(BATCH.created_at, 'fr', CASA)
  const toggle = await screen.findByRole('button', { name: spaced(i18n.t('invitations.batches.holders', { date })) })
  const row = toggle.closest('tr')!
  expect(within(row).getByText(BATCH.invited_by.name)).toBeTruthy()
  expect(row.textContent).toContain('Presse · Tribune de presse')
  expect(within(row).getByText(i18n.t('invitations.batches.split', { whatsapp: 2, paper: 1 }))).toBeTruthy()
  expect(toggle.getAttribute('aria-expanded')).toBe('false')
  await user.click(toggle)
  expect(toggle.getAttribute('aria-expanded')).toBe('true')
  const holders = within(screen.getByRole('list', { name: spaced(i18n.t('invitations.batches.holders', { date })) }))
  const items = holders.getAllByRole('listitem')
  expect(items).toHaveLength(3)
  expect(items[0]!.textContent).toContain('Nadia Alaoui')
  expect(items[0]!.textContent).toContain('4D5C 6B7A')
  expect(within(items[0]!).getByText(fr.tickets.statuses.VALID)).toBeTruthy()
  expect(within(items[0]!).getByText(fr.invitations.delivery.WHATSAPP)).toBeTruthy()
  expect(within(items[2]!).getByText(fr.invitations.delivery.PAPER)).toBeTruthy()
  // OPS: no Révoquer (ADMIN only).
  expect(holders.queryByRole('button', { name: i18n.t('invitations.revoke.label', { ticket: '4D5C 6B7A' }) })).toBeNull()
})

it('"Imprimer" (a VALID paper ticket) opens the print route: GET /org/invitations/{id}/tickets, the B11 slips, back to B7', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  const { router } = renderApp('/events/1/invitations')
  const date = formatDateTime(BATCH.created_at, 'fr', CASA)
  await user.click(await screen.findByRole('link', { name: spaced(i18n.t('invitations.print.label', { date })) }))
  await waitFor(() => expect(router.state.location.pathname).toBe(`/events/1/invitations/${BATCH.id}/print`))
  const slips = await screen.findAllByRole('article')
  expect(slips).toHaveLength(1)
  expect(slips[0]!.textContent).toContain('Omar B.')
  expect(slips[0]!.textContent).not.toContain('Omar Benali')
  expect(calls.filter((c) => c.path === PRINT_PATH)).toEqual([{ path: PRINT_PATH, org: 'org-a', method: 'GET', body: undefined }])
  await user.click(screen.getByRole('link', { name: fr.invitations.print.back }))
  expect(router.state.location.pathname).toBe('/events/1/invitations')
})

it('"Imprimer" is not offered when no paper ticket is VALID', async () => {
  const onlyApp: Invitation = { ...BATCH, tickets: BATCH.tickets.map((tk) => (tk.delivery === 'PAPER' ? { ...tk, status: 'REFUNDED' } : tk)) }
  stubApi('OPS', { [LIST]: () => Response.json({ invitations: [onlyApp] }) })
  renderApp('/events/1/invitations')
  await screen.findByText(BATCH.invited_by.name)
  expect(screen.queryByRole('link', { name: new RegExp(fr.invitations.print.action) })).toBeNull()
})

it('issue: one invitation_id (UUID) per invitation, the same on a retry after a network error; a 200 replay reads as issued', async () => {
  const user = userEvent.setup()
  let n = 0
  const calls = stubApi('OPS', {
    [`POST ${LIST}`]: () => (++n === 1 ? Promise.reject(new TypeError('Failed to fetch')) : Response.json(ORDER, { status: 200 })),
  })
  renderApp('/events/1/invitations')
  const sheet = await openSheet(user)
  await fill(user, sheet, '3', ['Nadia Alaoui', 'Karim Tazi', 'Omar Benali'], ['0612345678', '0612345679'])
  expect(sheet.getByText(i18n.t('invitations.sheet.summary', { count: 3, whatsapp: 2, paper: 1 }))).toBeTruthy()
  await user.click(submit(sheet, 3))
  expect(await sheet.findByText(fr.errors.NETWORK_ERROR)).toBeTruthy()
  await user.click(submit(sheet, 3))
  expect(await screen.findByText(i18n.t('invitations.sheet.done', { count: 3, category: 'Presse' }))).toBeTruthy()
  await waitFor(() => expect(screen.queryByRole('dialog', { name: fr.invitations.new })).toBeNull())

  const [first, second] = posts(calls)
  expect(first!.path).toBe(LIST)
  expect(first!.org).toBe('org-a')
  const body = first!.body as { invitation_id: string }
  expect(body.invitation_id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  expect(second!.body).toEqual(first!.body)
  expect(first!.body).toEqual({
    invitation_id: body.invitation_id,
    items: [
      {
        price_category_id: PRESSE.id,
        qty: 3,
        holders: [{ name: 'Nadia Alaoui', phone: '+212612345678' }, { name: 'Karim Tazi', phone: '+212612345679' }, { name: 'Omar Benali' }],
      },
    ],
  })
  // The issue refetches the event's invitations and prices (sold).
  await waitFor(() => expect(calls.filter((c) => c.path === LIST).length).toBeGreaterThan(1))

  // The next invitation gets a new id.
  const again = await openSheet(user)
  await fill(user, again, '1', ['Salma Idrissi'])
  await user.click(submit(again, 1))
  await waitFor(() => expect(posts(calls)).toHaveLength(3))
  expect((posts(calls)[2]!.body as { invitation_id: string }).invitation_id).not.toBe(body.invitation_id)
})

it('issue: "même nom" with no phone sends the name on every place, all on paper', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS', { [`POST ${LIST}`]: () => Response.json(ORDER, { status: 201 }) })
  renderApp('/events/1/invitations')
  const sheet = await openSheet(user)
  await user.click(sheet.getByLabelText(fr.pos.sale.sameName))
  await fill(user, sheet, '3', ['Partenaire Atlas'])
  expect(document.getElementById('invite-holder-1-name')).toBeNull() // one row for every place
  expect(sheet.getByText(i18n.t('invitations.sheet.summary', { count: 3, whatsapp: 0, paper: 3 }))).toBeTruthy()
  await user.click(submit(sheet, 3))
  await waitFor(() => expect(posts(calls)).toHaveLength(1))
  const holders = (posts(calls)[0]!.body as { items: { holders: unknown[] }[] }).items[0]!.holders
  expect(holders).toEqual([{ name: 'Partenaire Atlas' }, { name: 'Partenaire Atlas' }, { name: 'Partenaire Atlas' }])
})

it('issue refusals: SOLD_OUT says "Plus de places {category}"; CLOSED gives transfer_until in the venue zone; a 422 holder lands on its row', async () => {
  const user = userEvent.setup()
  const until = '2026-11-22T17:30:00Z'
  let answer = conflict('SOLD_OUT', {})
  stubApi('OPS', { [`POST ${LIST}`]: () => answer() })
  renderApp('/events/1/invitations')
  const sheet = await openSheet(user)
  await fill(user, sheet, '1', ['Nadia Alaoui'])
  await user.click(submit(sheet, 1))
  expect(await sheet.findByText(i18n.t('invitations.errors.soldOut', { category: 'Presse' }))).toBeTruthy()

  answer = conflict('EVENT_NOT_ON_SALE', { reason: 'CLOSED', transfer_until: until })
  await user.click(submit(sheet, 1))
  expect(await sheet.findByText(spaced(i18n.t('invitations.errors.closed', { date: formatDateTime(until, 'fr', CASA) })))).toBeTruthy()

  answer = conflict('EVENT_NOT_ON_SALE', { reason: 'NOT_ON_SALE' })
  await user.click(submit(sheet, 1))
  expect(await sheet.findByText(fr.invitations.errors.notOnSale)).toBeTruthy()

  answer = () => Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { 'items[0].holders[0].name': 'x' } } }, { status: 422 })
  await user.click(submit(sheet, 1))
  await waitFor(() => expect(document.getElementById('invite-holder-0-name')!.getAttribute('aria-invalid')).toBe('true'))
})

it('ADMIN revokes a ticket: POST /org/tickets/{id}/refund with the reason, the refetched row reads Remboursé', async () => {
  const user = userEvent.setup()
  let revoked = false
  const calls = stubApi('ADMIN', {
    [LIST]: () =>
      Response.json({ invitations: [{ ...BATCH, tickets: BATCH.tickets.map((tk) => (revoked && tk.id === 't-3' ? { ...tk, status: 'REFUNDED' } : tk)) }] }),
    'POST /org/tickets/t-3/refund': () => {
      revoked = true
      return Response.json({ id: 't-3', status: 'REFUNDED' })
    },
  })
  renderApp('/events/1/invitations')
  const date = formatDateTime(BATCH.created_at, 'fr', CASA)
  await user.click(await screen.findByRole('button', { name: spaced(i18n.t('invitations.batches.holders', { date })) }))
  await user.click(screen.getByRole('button', { name: i18n.t('invitations.revoke.label', { ticket: '6F7A 8B9C' }) }))
  const dialog = within(await screen.findByRole('dialog'))
  expect(dialog.getByText(fr.invitations.revoke.body)).toBeTruthy()
  await user.type(dialog.getByLabelText(fr.tickets.reason), 'Journaliste non accrédité')
  await user.click(dialog.getByRole('button', { name: fr.invitations.revoke.confirm }))
  await waitFor(() => expect(posts(calls)).toEqual([{ path: '/org/tickets/t-3/refund', org: 'org-a', method: 'POST', body: { reason: 'Journaliste non accrédité' } }]))
  const item = (await screen.findByText(fr.tickets.statuses.REFUNDED)).closest('li')!
  expect(item.textContent).toContain('Omar Benali')
  expect(within(item).getByRole('button', { name: i18n.t('invitations.revoke.label', { ticket: '6F7A 8B9C' }) })).toHaveProperty('disabled', true)
})

it('ADMIN after the refund cutoff: "Révoquer" is disabled and says why (Gel des remboursements)', async () => {
  const user = userEvent.setup()
  const cutoff = at(-HOUR)
  stubApi('ADMIN', { '/org/events/1': () => Response.json({ ...EVENT, status: 'GATES_OPEN', refund_cutoff_at: cutoff }) })
  renderApp('/events/1/invitations')
  const date = formatDateTime(BATCH.created_at, 'fr', CASA)
  await user.click(await screen.findByRole('button', { name: spaced(i18n.t('invitations.batches.holders', { date })) }))
  const revoke = screen.getByRole('button', { name: i18n.t('invitations.revoke.label', { ticket: '4D5C 6B7A' }) })
  expect(revoke).toHaveProperty('disabled', true)
  const why = document.getElementById(revoke.getAttribute('aria-describedby')!)!
  expect(spaced(why.textContent!)).toBe(spaced(i18n.t('invitations.batches.frozen', { date: formatDateTime(cutoff, 'fr', CASA) })))
})

it('FINANCE reads only: no "Nouvelle invitation", no "Imprimer", no "Révoquer"; the print route sends it back', async () => {
  const user = userEvent.setup()
  const calls = stubApi('FINANCE')
  const { router } = renderApp('/events/1/invitations')
  const date = formatDateTime(BATCH.created_at, 'fr', CASA)
  await user.click(await screen.findByRole('button', { name: spaced(i18n.t('invitations.batches.holders', { date })) }))
  expect(screen.getByText('Nadia Alaoui')).toBeTruthy()
  expect(screen.queryByRole('button', { name: fr.invitations.new })).toBeNull()
  expect(screen.queryByRole('link', { name: new RegExp(fr.invitations.print.action) })).toBeNull()
  expect(screen.queryByRole('button', { name: new RegExp(fr.invitations.revoke.action) })).toBeNull()

  await router.navigate(`/events/1/invitations/${BATCH.id}/print`)
  await waitFor(() => expect(router.state.location.pathname).toBe('/events/1/invitations'))
  expect(calls.some((c) => c.path === PRINT_PATH)).toBe(false)
})

it('no INVITE category: the empty state links to B2 Tarifs & quotas; no sheet', async () => {
  const user = userEvent.setup()
  stubApi('OPS', { '/org/events/1/prices': () => Response.json([PLEIN]), [LIST]: () => Response.json({ invitations: [] }) })
  const { router } = renderApp('/events/1/invitations')
  expect(await screen.findByText(fr.invitations.emptyTitle)).toBeTruthy()
  expect(await screen.findByText(fr.invitations.batches.emptyTitle)).toBeTruthy()
  expect(screen.queryByRole('button', { name: fr.invitations.new })).toBeNull()
  await user.click(screen.getByRole('link', { name: fr.invitations.emptyAction }))
  await waitFor(() => expect(router.state.location.pathname + router.state.location.search).toBe('/events/1?tab=prices'))
})

it('a DRAFT event: "Nouvelle invitation" is disabled and says to publish first', async () => {
  stubApi('OPS', { '/org/events/1': () => Response.json({ ...EVENT, status: 'DRAFT', refund_cutoff_at: null }) })
  renderApp('/events/1/invitations')
  const button = await screen.findByRole('button', { name: fr.invitations.new })
  expect(button).toHaveProperty('disabled', true)
  expect(document.getElementById(button.getAttribute('aria-describedby')!)!.textContent).toBe(fr.invitations.notOpenDraft)
})
