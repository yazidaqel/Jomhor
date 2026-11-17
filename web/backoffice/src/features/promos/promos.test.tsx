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
import type { EventDetail, Price } from '@/features/events/api'
import { createQueryClient } from '@/lib/queryClient'
import { formatDh } from '@/lib/money'
import { session } from '@/lib/session'
import { createBody, patchBody, promoSchema, toForm, WHOLE_EVENT, type PromoCode, type PromoForm } from './api'

beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  session.clear()
})

// Fixtures: answers captured from the real API (BO-26 real run, recipe `backend/http/lot5-promo.http`, event 50): its
// prices (one INVITE category, never a scope), the list after the fan orders (BO26PCT20 1 paid; BO26AMT50 1 paid and
// 1 still held; BO26NOCAP no cap, switched off), the POST 201, the TAKEN refusal and the PATCH answers.
const CASA = 'Africa/Casablanca'
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
// The create answer as publish left it (ON_SALE, cutoff T0 − 60 min).
const EVENT: EventDetail = {
  id: 50,
  venue_id: 'a7a5fc00-0000-4000-8000-000000000301',
  timezone: CASA,
  series_id: null,
  pass_tickets: false,
  event_type: 'FOOTBALL_MATCH',
  title: 'BO-26 real run 142147',
  starts_at: '2027-02-21T19:00:00Z',
  doors_open_at: null,
  sale_starts_at: null,
  status: 'ON_SALE',
  refund_cutoff_at: '2027-02-21T18:00:00Z',
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: { opponent: 'BO-26' },
  created_at: '2026-11-17T14:21:47.604060Z',
}
const VENUE = EVENT.venue_id
const PLEIN_ID = 'f30e1b30-081d-4cae-b7f2-7a39f5505f0f'
const PRICES: Price[] = [
  { id: '4d858ba5-2b2f-40e9-9d16-5d4b43d84ca9', zone_id: 2, zone: { code: 'EST', name: 'Est' }, name: 'Membres', price_mad: 10000, quota: 20, sold: 0, audience: 'MEMBERS' },
  { id: PLEIN_ID, zone_id: 2, zone: { code: 'EST', name: 'Est' }, name: 'Plein tarif', price_mad: 15000, quota: 100, sold: 0, audience: 'ALL' },
  { id: '71565bfd-c778-4d64-a8ab-1e7f34436458', zone_id: 4, zone: { code: 'SUD', name: 'Sud' }, name: 'Invités', price_mad: 0, quota: 5, sold: 0, audience: 'INVITE' },
  { id: 'f1c35488-a1cb-42b3-bbfe-e6690e5888c1', zone_id: 4, zone: { code: 'SUD', name: 'Sud' }, name: 'Virage', price_mad: 5000, quota: 100, sold: 0, audience: 'ALL' },
]
const PLEIN_SCOPE = { id: PLEIN_ID, name: 'Plein tarif', zone: { code: 'EST', name: 'Est' } }
const AMT50: PromoCode = {
  id: 'c67c0904-005b-4ed1-b8d6-abb18227d890',
  code: 'BO26AMT50',
  kind: 'AMOUNT',
  percent: null,
  amount_mad: 5000,
  price_category: null,
  max_uses: 2,
  per_account_max: 1,
  active: true,
  created_at: '2026-11-17T14:21:47.840758Z',
  uses: { held: 1, paid: 1 },
  discount_mad: 5000,
}
const NOCAP: PromoCode = {
  id: '6a2255dd-3606-4aa7-a32c-9debee7e1158',
  code: 'BO26NOCAP',
  kind: 'PERCENT',
  percent: 10,
  amount_mad: null,
  price_category: null,
  max_uses: null,
  per_account_max: 3,
  active: false,
  created_at: '2026-11-17T14:25:15.332014Z',
  uses: { held: 0, paid: 0 },
  discount_mad: 0,
}
const PCT20: PromoCode = {
  id: 'f952e7b7-64f5-4af7-8784-2e58a20be68f',
  code: 'BO26PCT20',
  kind: 'PERCENT',
  percent: 20,
  amount_mad: null,
  price_category: PLEIN_SCOPE,
  max_uses: 150,
  per_account_max: 1,
  active: true,
  created_at: '2026-11-17T14:21:47.809762Z',
  uses: { held: 0, paid: 1 },
  discount_mad: 6000,
}
const LIST = [AMT50, NOCAP, PCT20] // by code, as sent
// POST {code: "bo26pct20", kind: PERCENT, percent: 20, price_category_id, max_uses: 100, per_account_max: 1} → 201.
const CREATED: PromoCode = { ...PCT20, max_uses: 100, uses: { held: 0, paid: 0 }, discount_mad: 0 }
// POST {code: "Bo26amt50", …} once BO26AMT50 exists → 422.
const TAKEN = { code: 'VALIDATION_FAILED', message: 'Invalid request body', details: { fields: { code: 'already used in this event' }, reason: 'TAKEN' } }
// PATCH {active: false} on BO26AMT50, and {max_uses: 150, per_account_max: 2} on BO26PCT20 (both before the orders).
const PATCHED: Record<string, PromoCode> = {
  [AMT50.id]: { ...AMT50, active: false, uses: { held: 0, paid: 0 }, discount_mad: 0 },
  [PCT20.id]: { ...PCT20, per_account_max: 2, uses: { held: 0, paid: 0 }, discount_mad: 0 },
}
const BASE = '/org/events/50'

type Call = { method: string; path: string; org: string | null; body: unknown }
type Handler = (c: Call) => Response | undefined

function stubApi(role: Role = 'ADMIN', handler: Handler = () => undefined, event = EVENT) {
  const calls: Call[] = []
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
      if (key === 'GET /org/venues') return Response.json([{ id: VENUE, name: 'Grand Stade', capacity: 40000, timezone: CASA }])
      if (key === `GET /org/venues/${VENUE}`) return Response.json({ id: VENUE, name: 'Grand Stade', capacity: 40000, timezone: CASA, zones: [], gates: [] })
      if (key === 'GET /org/series') return Response.json([])
      if (key === `GET ${BASE}`) return Response.json(event)
      if (key === `GET ${BASE}/prices`) return Response.json(PRICES)
      if (key === `GET ${BASE}/promo-codes`) return Response.json(LIST)
      const patched = new RegExp(`^PATCH ${BASE}/promo-codes/(.+)$`).exec(key)
      if (patched && PATCHED[patched[1]!]) return Response.json(PATCHED[patched[1]!])
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

function renderApp(path = '/events/50?tab=promos') {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const queryClient = createQueryClient()
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={createMemoryRouter(routes, { initialEntries: [path] })} />
    </QueryClientProvider>,
  )
  return queryClient
}

const t = i18n.t.bind(i18n)
const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const writes = (calls: Call[]) => calls.filter((c) => c.method !== 'GET')
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent)
const row = async (code: string) => (await screen.findByText(code)).closest('tr') as HTMLElement
const sheet = () => within(screen.getByRole('dialog'))

/** The sheet's form values: what a fresh "Nouveau code" holds, with `over` typed in. */
const form = (over: Partial<PromoForm>): PromoForm => ({ ...toForm(), ...over })
const parse = (over: Partial<PromoForm>, existing?: PromoCode) => promoSchema(t, existing).safeParse(form(over))

it('zod: the code is uppercased then ^[A-Z0-9]{4,20}$; percent a whole 1–99; DH → integer centimes; par compte 1 by default', () => {
  expect(parse({ code: ' bo26pct20 ', percent: '20' }).data?.code).toBe('BO26PCT20')
  for (const code of ['ab1', 'ab-12', 'été2026', 'A'.repeat(21), 'AB 12'])
    expect(parse({ code, percent: '20' }).error?.issues.map((i) => i.path.join('.'))).toEqual(['code'])
  for (const percent of ['0', '100', '12.5', '', '-5', '1e1']) expect(parse({ code: 'DERBY', percent }).success).toBe(false)
  for (const percent of ['1', '99', ' 05 ']) expect(parse({ code: 'DERBY', percent }).success).toBe(true)

  // "12,50" → 1250 by string and integer maths; 0, 3 decimals or a sign are refused.
  const amount = parse({ code: 'BO26AMT50', kind: 'AMOUNT', amount_mad: '12,50' })
  expect(createBody(amount.data!)).toEqual({ code: 'BO26AMT50', kind: 'AMOUNT', amount_mad: 1250, per_account_max: 1 })
  expect(createBody(parse({ code: 'BO26AMT50', kind: 'AMOUNT', amount_mad: '50' }).data!)).toMatchObject({ amount_mad: 5000 })
  for (const amount_mad of ['0', '0,00', '12,505', '-5', '1 000', '']) expect(parse({ code: 'BO26AMT50', kind: 'AMOUNT', amount_mad }).success).toBe(false)
  // The other kind's field is never checked nor sent.
  expect(parse({ code: 'BO26AMT50', kind: 'AMOUNT', amount_mad: '50', percent: 'x' }).success).toBe(true)

  expect(toForm().per_account_max).toBe('1')
  expect(createBody(parse({ code: 'bo26pct20', percent: '20', price_category_id: PLEIN_ID, max_uses: '100' }).data!)).toEqual({
    code: 'BO26PCT20',
    kind: 'PERCENT',
    percent: 20,
    price_category_id: PLEIN_ID,
    max_uses: 100,
    per_account_max: 1,
  })
  expect(toForm().price_category_id).toBe(WHOLE_EVENT)
  for (const per_account_max of ['0', '101', '']) expect(parse({ code: 'DERBY', percent: '5', per_account_max }).success).toBe(false)
  for (const max_uses of ['0', '2.5', 'x']) expect(parse({ code: 'DERBY', percent: '5', max_uses }).success).toBe(false)
})

it('edit: only the caps are checked and only the changed ones are sent; a set max_uses cannot be cleared', () => {
  const edit = (over: Partial<PromoForm>, code = PCT20) => promoSchema(t, code).safeParse({ ...toForm(code), ...over })
  expect(patchBody(PCT20, edit({}).data!)).toEqual({})
  expect(patchBody(PCT20, edit({ per_account_max: '3' }).data!)).toEqual({ per_account_max: 3 })
  expect(patchBody(PCT20, edit({ max_uses: '200' }).data!)).toEqual({ max_uses: 200 })
  expect(edit({ max_uses: '' }).error?.issues[0]?.message).toBe(fr.promos.form.maxUsesKept)
  // No cap yet: one may be set, or left empty.
  expect(patchBody(NOCAP, edit({ max_uses: '' }, NOCAP).data!)).toEqual({})
  expect(patchBody(NOCAP, edit({ max_uses: '2' }, NOCAP).data!)).toEqual({ max_uses: 2 })
})

it('ADMIN list: code, remise, portée, uses "{paid} payées · {held} en cours / ∞", par compte, Σ discount, Actif; keys carry organizer and event', async () => {
  const calls = stubApi()
  const queryClient = renderApp()

  expect(await screen.findByRole('heading', { name: fr.promos.title })).toBeTruthy()
  expect(screen.getByRole('tab', { name: fr.events.tabs.promos }).getAttribute('aria-selected')).toBe('true')
  const dh = (c: number) => spaced(t('sales.amount', { amount: formatDh(c, 'fr') }))
  const shown = async (code: string) => cells(await row(code)).slice(0, 6).map((c) => spaced(c ?? ''))
  expect(await shown('BO26AMT50')).toEqual(['BO26AMT50', '−50,00 DH', fr.promos.wholeEvent, '1 payée · 1 en cours / 2', '1', dh(5000)])
  expect(await shown('BO26NOCAP')).toEqual(['BO26NOCAP', '−10 %', fr.promos.wholeEvent, '0 payée · 0 en cours / ∞', '3', dh(0)])
  expect(await shown('BO26PCT20')).toEqual(['BO26PCT20', '−20 %', 'Plein tarif · Est', '1 payée · 0 en cours / 150', '1', dh(6000)])
  const sw = (code: string) => screen.getByRole('switch', { name: t('promos.activeLabel', { code }) }).getAttribute('aria-checked')
  expect([sw('BO26AMT50'), sw('BO26NOCAP'), sw('BO26PCT20')]).toEqual(['true', 'false', 'true'])
  expect(t('promos.uses', { count: 2, paid: '2', held: '0', max: '∞' })).toBe('2 payées · 0 en cours / ∞')

  expect(calls.find((c) => c.path === `${BASE}/promo-codes`)!.org).toBe('org-a')
  expect(queryClient.getQueryData(['org', 'org-a', 'events', '50', 'promo-codes'])).toHaveLength(3)
})

it('the Actif switch PATCHes `active` alone', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp()

  await user.click(await screen.findByRole('switch', { name: t('promos.activeLabel', { code: 'BO26AMT50' }) }))
  await waitFor(() => expect(writes(calls)).toEqual([{ method: 'PATCH', path: `${BASE}/promo-codes/${AMT50.id}`, org: 'org-a', body: { active: false } }]))
  expect(await screen.findByText(t('promos.deactivated', { code: 'BO26AMT50' }))).toBeTruthy()
})

it('"Nouveau code": the scope lists ALL/MEMBERS categories only; TAKEN → field error; then the POST body', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS', (c) => {
    if (c.method !== 'POST') return undefined
    return (c.body as { code: string }).code === 'BO26AMT50' ? Response.json(TAKEN, { status: 422 }) : Response.json(CREATED, { status: 201 })
  })
  renderApp()

  await user.click(await screen.findByRole('button', { name: fr.promos.new }))
  const s = sheet()
  await user.type(s.getByLabelText(fr.promos.form.code), 'Bo26amt50')
  await user.type(s.getByLabelText(fr.promos.form.percent), '20')
  await user.click(s.getByRole('combobox', { name: fr.promos.form.scope }))
  const options = (await screen.findAllByRole('option')).map((o) => o.textContent)
  expect(options).toEqual([fr.promos.wholeEvent, 'Membres · Est', 'Plein tarif · Est', 'Virage · Sud']) // never the INVITE "Invités"
  await user.click(screen.getByRole('option', { name: 'Plein tarif · Est' }))
  await user.type(s.getByLabelText(fr.promos.form.maxUses), '100')
  expect((s.getByLabelText(fr.promos.form.perAccount) as HTMLInputElement).value).toBe('1')
  await user.click(s.getByRole('button', { name: fr.promos.form.create }))

  expect(await s.findByText(fr.promos.form.codeTaken)).toBeTruthy()
  const code = s.getByLabelText(fr.promos.form.code) as HTMLInputElement
  await user.clear(code)
  await user.type(code, 'bo26pct20')
  await user.click(s.getByRole('button', { name: fr.promos.form.create }))
  expect(await screen.findByText(t('promos.created', { code: 'BO26PCT20' }))).toBeTruthy()

  const body = { kind: 'PERCENT', percent: 20, price_category_id: PLEIN_ID, max_uses: 100, per_account_max: 1 }
  expect(writes(calls)).toEqual([
    { method: 'POST', path: `${BASE}/promo-codes`, org: 'org-a', body: { code: 'BO26AMT50', ...body } },
    { method: 'POST', path: `${BASE}/promo-codes`, org: 'org-a', body: { code: 'BO26PCT20', ...body } },
  ])
})

it('edit sheet: fixed fields read-only, PATCH sends only the changed cap; clearing max_uses is refused locally', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp()

  await user.click(await screen.findByRole('button', { name: t('promos.editLabel', { code: 'BO26PCT20' }) }))
  const s = sheet()
  expect((s.getByLabelText(fr.promos.form.code) as HTMLInputElement).readOnly).toBe(true)
  expect((s.getByLabelText(fr.promos.form.percent) as HTMLInputElement).readOnly).toBe(true)
  expect(s.getByRole('combobox', { name: fr.promos.form.kind }).hasAttribute('disabled')).toBe(true)
  const scope = s.getByRole('combobox', { name: fr.promos.form.scope })
  expect(scope.hasAttribute('disabled')).toBe(true)
  expect(scope.textContent).toBe('Plein tarif · Est')

  const maxUses = s.getByLabelText(fr.promos.form.maxUses)
  await user.clear(maxUses)
  await user.click(s.getByRole('button', { name: fr.common.save }))
  expect(await s.findByText(fr.promos.form.maxUsesKept)).toBeTruthy()
  expect(writes(calls)).toEqual([])

  await user.type(maxUses, '150') // as stored: not sent
  const perAccount = s.getByLabelText(fr.promos.form.perAccount)
  await user.clear(perAccount)
  await user.type(perAccount, '2')
  await user.click(s.getByRole('button', { name: fr.common.save }))
  expect(await screen.findByText(t('promos.saved', { code: 'BO26PCT20' }))).toBeTruthy()
  expect(writes(calls)).toEqual([{ method: 'PATCH', path: `${BASE}/promo-codes/${PCT20.id}`, org: 'org-a', body: { per_account_max: 2 } }])
})

it('FINANCE reads only: no "Nouveau code", no edit, the switches disabled', async () => {
  stubApi('FINANCE')
  renderApp()

  await row('BO26PCT20')
  expect(screen.queryByRole('button', { name: fr.promos.new })).toBeNull()
  expect(screen.queryByRole('button', { name: t('promos.editLabel', { code: 'BO26PCT20' }) })).toBeNull()
  for (const sw of screen.getAllByRole('switch')) expect(sw.hasAttribute('disabled')).toBe(true)
})

it('a CLOSED event takes no new code (real run: 409 EVENT_NOT_EDITABLE); its codes can still be switched (real run: PATCH 200)', async () => {
  stubApi('ADMIN', undefined, { ...EVENT, status: 'CLOSED' })
  renderApp()

  await row('BO26PCT20')
  expect(screen.getByRole('button', { name: fr.promos.new }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByText(fr.promos.notEditable)).toBeTruthy()
  expect(screen.getByRole('switch', { name: t('promos.activeLabel', { code: 'BO26PCT20' }) }).hasAttribute('disabled')).toBe(false)
})
