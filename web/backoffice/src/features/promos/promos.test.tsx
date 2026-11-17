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

// source: contract (docs/04-api.md §Promo codes, GET/POST/PATCH /org/events/{id}/promo-codes; plan §12.1 exit 4b
// set-up). Replace with answers captured from the real API after BE-65 (BO-26 real run).
const CASA = 'Africa/Casablanca'
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const EVENT: EventDetail = {
  id: 1,
  venue_id: 'v-1',
  timezone: CASA,
  series_id: null,
  pass_tickets: false,
  event_type: 'FOOTBALL_MATCH',
  title: 'Atlas FC vs Rapid Oued',
  starts_at: '2027-01-24T19:00:00Z',
  doors_open_at: null,
  sale_starts_at: null,
  status: 'ON_SALE',
  refund_cutoff_at: '2027-01-24T18:00:00Z',
  reentry_policy: 'NONE',
  per_account_limit: 4,
  metadata: {},
  created_at: '2026-11-01T10:00:00Z',
}
const price = (id: string, name: string, audience: Price['audience'], zone: string, price_mad: number): Price => ({
  id,
  zone_id: 3,
  zone: { code: zone.toUpperCase(), name: zone },
  name,
  price_mad,
  quota: 100,
  sold: 0,
  audience,
})
const PLEIN = price('p-plein', 'Plein tarif', 'ALL', 'Tribune Est', 15_000)
const MEMBRES = price('p-membres', 'Membres', 'MEMBERS', 'Tribune Est', 10_000)
const PRICES = [PLEIN, MEMBRES, price('p-pass', 'Abonnés', 'PASS', 'Virage Nord', 0), price('p-presse', 'Presse', 'INVITE', 'Presse', 0)]
const ABONNES20: PromoCode = {
  id: 'pc-1',
  code: 'ABONNES20',
  kind: 'PERCENT',
  percent: 20,
  amount_mad: null,
  price_category: { id: PLEIN.id, name: 'Plein tarif', zone: { code: 'TRIBUNE EST', name: 'Tribune Est' } },
  max_uses: 100,
  per_account_max: 1,
  active: true,
  created_at: '2026-11-17T10:00:00Z',
  uses: { held: 1, paid: 1 },
  discount_mad: 6_000,
}
const DERBY50: PromoCode = {
  ...ABONNES20,
  id: 'pc-2',
  code: 'DERBY50',
  kind: 'AMOUNT',
  percent: null,
  amount_mad: 5_000,
  price_category: null,
  max_uses: null,
  per_account_max: 2,
  active: false,
  uses: { held: 0, paid: 3 },
  discount_mad: 15_000,
}

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
      if (key === 'GET /org/venues') return Response.json([{ id: 'v-1', name: 'Grand Stade', capacity: 40000, timezone: CASA }])
      if (key === 'GET /org/venues/v-1') return Response.json({ id: 'v-1', name: 'Grand Stade', capacity: 40000, timezone: CASA, zones: [], gates: [] })
      if (key === 'GET /org/series') return Response.json([])
      if (key === 'GET /org/events/1') return Response.json(event)
      if (key === 'GET /org/events/1/prices') return Response.json(PRICES)
      if (key === 'GET /org/events/1/promo-codes') return Response.json([ABONNES20, DERBY50])
      const patched = /^PATCH \/org\/events\/1\/promo-codes\/(pc-\d)$/.exec(key)
      if (patched) return Response.json({ ...[ABONNES20, DERBY50].find((c) => c.id === patched[1]), ...(call.body as object) })
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

function renderApp(path = '/events/1?tab=promos') {
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
  expect(parse({ code: ' abonnes20 ', percent: '20' }).data?.code).toBe('ABONNES20')
  for (const code of ['ab1', 'ab-12', 'été2026', 'A'.repeat(21), 'AB 12'])
    expect(parse({ code, percent: '20' }).error?.issues.map((i) => i.path.join('.'))).toEqual(['code'])
  for (const percent of ['0', '100', '12.5', '', '-5', '1e1']) expect(parse({ code: 'DERBY', percent }).success).toBe(false)
  for (const percent of ['1', '99', ' 05 ']) expect(parse({ code: 'DERBY', percent }).success).toBe(true)

  // "12,50" → 1250 by string and integer maths; 0, 3 decimals or a sign are refused.
  const amount = parse({ code: 'DERBY50', kind: 'AMOUNT', amount_mad: '12,50' })
  expect(createBody(amount.data!)).toEqual({ code: 'DERBY50', kind: 'AMOUNT', amount_mad: 1250, per_account_max: 1 })
  expect(createBody(parse({ code: 'DERBY50', kind: 'AMOUNT', amount_mad: '50' }).data!)).toMatchObject({ amount_mad: 5000 })
  for (const amount_mad of ['0', '0,00', '12,505', '-5', '1 000', '']) expect(parse({ code: 'DERBY50', kind: 'AMOUNT', amount_mad }).success).toBe(false)
  // The other kind's field is never checked nor sent.
  expect(parse({ code: 'DERBY50', kind: 'AMOUNT', amount_mad: '50', percent: 'x' }).success).toBe(true)

  expect(toForm().per_account_max).toBe('1')
  expect(createBody(parse({ code: 'abonnes20', percent: '20', price_category_id: PLEIN.id, max_uses: '100' }).data!)).toEqual({
    code: 'ABONNES20',
    kind: 'PERCENT',
    percent: 20,
    price_category_id: PLEIN.id,
    max_uses: 100,
    per_account_max: 1,
  })
  expect(toForm().price_category_id).toBe(WHOLE_EVENT)
  for (const per_account_max of ['0', '101', '']) expect(parse({ code: 'DERBY', percent: '5', per_account_max }).success).toBe(false)
  for (const max_uses of ['0', '2.5', 'x']) expect(parse({ code: 'DERBY', percent: '5', max_uses }).success).toBe(false)
})

it('edit: only the caps are checked and only the changed ones are sent; a set max_uses cannot be cleared', () => {
  const edit = (over: Partial<PromoForm>, code = ABONNES20) => promoSchema(t, code).safeParse({ ...toForm(code), ...over })
  expect(patchBody(ABONNES20, edit({}).data!)).toEqual({})
  expect(patchBody(ABONNES20, edit({ per_account_max: '3' }).data!)).toEqual({ per_account_max: 3 })
  expect(patchBody(ABONNES20, edit({ max_uses: '150' }).data!)).toEqual({ max_uses: 150 })
  expect(edit({ max_uses: '' }).error?.issues[0]?.message).toBe(fr.promos.form.maxUsesKept)
  // No cap yet: one may be set, or left empty.
  expect(patchBody(DERBY50, edit({ max_uses: '' }, DERBY50).data!)).toEqual({})
  expect(patchBody(DERBY50, edit({ max_uses: '2' }, DERBY50).data!)).toEqual({ max_uses: 2 })
})

it('ADMIN list: code, remise, portée, uses "{paid} payées · {held} en cours / ∞", par compte, Σ discount, Actif; keys carry organizer and event', async () => {
  const calls = stubApi()
  const queryClient = renderApp()

  expect(await screen.findByRole('heading', { name: fr.promos.title })).toBeTruthy()
  expect(screen.getByRole('tab', { name: fr.events.tabs.promos }).getAttribute('aria-selected')).toBe('true')
  const abonnes = await row('ABONNES20')
  expect(cells(abonnes).slice(0, 6).map((c) => spaced(c ?? ''))).toEqual([
    'ABONNES20',
    '−20 %',
    'Plein tarif · Tribune Est',
    '1 payée · 1 en cours / 100',
    '1',
    spaced(t('sales.amount', { amount: formatDh(6_000, 'fr') })),
  ])
  expect(cells(await row('DERBY50')).slice(0, 6).map((c) => spaced(c ?? ''))).toEqual([
    'DERBY50',
    '−50,00 DH',
    fr.promos.wholeEvent,
    '3 payées · 0 en cours / ∞',
    '2',
    spaced(t('sales.amount', { amount: formatDh(15_000, 'fr') })),
  ])
  expect(screen.getByRole('switch', { name: t('promos.activeLabel', { code: 'ABONNES20' }) }).getAttribute('aria-checked')).toBe('true')
  expect(screen.getByRole('switch', { name: t('promos.activeLabel', { code: 'DERBY50' }) }).getAttribute('aria-checked')).toBe('false')

  expect(calls.find((c) => c.path === '/org/events/1/promo-codes')!.org).toBe('org-a')
  expect(queryClient.getQueryData(['org', 'org-a', 'events', '1', 'promo-codes'])).toHaveLength(2)
})

it('the Actif switch PATCHes `active` alone', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp()

  await user.click(await screen.findByRole('switch', { name: t('promos.activeLabel', { code: 'ABONNES20' }) }))
  await waitFor(() => expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/org/events/1/promo-codes/pc-1', org: 'org-a', body: { active: false } }]))
  expect(await screen.findByText(t('promos.deactivated', { code: 'ABONNES20' }))).toBeTruthy()
})

it('"Nouveau code": the scope lists ALL/MEMBERS categories only; TAKEN → field error; then the POST body', async () => {
  const user = userEvent.setup()
  let taken = true
  const calls = stubApi('OPS', (c) => {
    if (c.method !== 'POST') return undefined
    if (taken) {
      taken = false
      return Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { code: 'taken' }, reason: 'TAKEN' } }, { status: 422 })
    }
    return Response.json({ ...ABONNES20, id: 'pc-3', code: 'ABONNES25', uses: { held: 0, paid: 0 }, discount_mad: 0 }, { status: 201 })
  })
  renderApp()

  await user.click(await screen.findByRole('button', { name: fr.promos.new }))
  const s = sheet()
  await user.type(s.getByLabelText(fr.promos.form.code), 'abonnes20')
  await user.type(s.getByLabelText(fr.promos.form.percent), '20')
  await user.click(s.getByRole('combobox', { name: fr.promos.form.scope }))
  const options = (await screen.findAllByRole('option')).map((o) => o.textContent)
  expect(options).toEqual([fr.promos.wholeEvent, 'Plein tarif · Tribune Est', 'Membres · Tribune Est']) // never PASS or INVITE
  await user.click(screen.getByRole('option', { name: 'Plein tarif · Tribune Est' }))
  await user.type(s.getByLabelText(fr.promos.form.maxUses), '100')
  expect((s.getByLabelText(fr.promos.form.perAccount) as HTMLInputElement).value).toBe('1')
  await user.click(s.getByRole('button', { name: fr.promos.form.create }))

  expect(await s.findByText(fr.promos.form.codeTaken)).toBeTruthy()
  const code = s.getByLabelText(fr.promos.form.code) as HTMLInputElement
  await user.clear(code)
  await user.type(code, 'Abonnes25')
  await user.click(s.getByRole('button', { name: fr.promos.form.create }))
  expect(await screen.findByText(t('promos.created', { code: 'ABONNES25' }))).toBeTruthy()

  const body = { kind: 'PERCENT', percent: 20, price_category_id: PLEIN.id, max_uses: 100, per_account_max: 1 }
  expect(writes(calls)).toEqual([
    { method: 'POST', path: '/org/events/1/promo-codes', org: 'org-a', body: { code: 'ABONNES20', ...body } },
    { method: 'POST', path: '/org/events/1/promo-codes', org: 'org-a', body: { code: 'ABONNES25', ...body } },
  ])
})

it('edit sheet: fixed fields read-only, PATCH sends only the changed cap; clearing max_uses is refused locally', async () => {
  const user = userEvent.setup()
  const calls = stubApi()
  renderApp()

  await user.click(await screen.findByRole('button', { name: t('promos.editLabel', { code: 'ABONNES20' }) }))
  const s = sheet()
  expect((s.getByLabelText(fr.promos.form.code) as HTMLInputElement).readOnly).toBe(true)
  expect((s.getByLabelText(fr.promos.form.percent) as HTMLInputElement).readOnly).toBe(true)
  expect(s.getByRole('combobox', { name: fr.promos.form.kind }).hasAttribute('disabled')).toBe(true)
  const scope = s.getByRole('combobox', { name: fr.promos.form.scope })
  expect(scope.hasAttribute('disabled')).toBe(true)
  expect(scope.textContent).toBe('Plein tarif · Tribune Est')

  const maxUses = s.getByLabelText(fr.promos.form.maxUses)
  await user.clear(maxUses)
  await user.click(s.getByRole('button', { name: fr.common.save }))
  expect(await s.findByText(fr.promos.form.maxUsesKept)).toBeTruthy()
  expect(writes(calls)).toEqual([])

  await user.type(maxUses, '100') // as stored: not sent
  const perAccount = s.getByLabelText(fr.promos.form.perAccount)
  await user.clear(perAccount)
  await user.type(perAccount, '2')
  await user.click(s.getByRole('button', { name: fr.common.save }))
  expect(await screen.findByText(t('promos.saved', { code: 'ABONNES20' }))).toBeTruthy()
  expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/org/events/1/promo-codes/pc-1', org: 'org-a', body: { per_account_max: 2 } }])
})

it('FINANCE reads only: no "Nouveau code", no edit, the switches disabled', async () => {
  stubApi('FINANCE')
  renderApp()

  await row('ABONNES20')
  expect(screen.queryByRole('button', { name: fr.promos.new })).toBeNull()
  expect(screen.queryByRole('button', { name: t('promos.editLabel', { code: 'ABONNES20' }) })).toBeNull()
  for (const sw of screen.getAllByRole('switch')) expect(sw.hasAttribute('disabled')).toBe(true)
})

it('a GATES_OPEN event takes no new code (04: DRAFT or ON_SALE); its codes can still be switched off', async () => {
  stubApi('ADMIN', undefined, { ...EVENT, status: 'GATES_OPEN' })
  renderApp()

  await row('ABONNES20')
  expect(screen.getByRole('button', { name: fr.promos.new }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByText(fr.promos.notEditable)).toBeTruthy()
  expect(screen.getByRole('switch', { name: t('promos.activeLabel', { code: 'ABONNES20' }) }).hasAttribute('disabled')).toBe(false)
})
