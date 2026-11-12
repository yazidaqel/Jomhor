// @vitest-environment jsdom
// Lot 4 · WS-8: split pay on the shop. W3 "Paie sa place", the W4 member variant, the initiator's group block.
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { renderToString } from 'react-dom/server'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { json, NOW, rawEvent } from '@/lib/events.fixture'
import { defaultLocale, dirOf, getDictionary, ltr } from '@/lib/i18n'
import { Refresh } from './[id]/Refresh'
import { MemberPay } from './[id]/MemberPay'
import { OrderStatus } from './[id]/OrderStatus'
import OrderPage from './[id]/page'
import CheckoutPage from './new/page'
import { CheckoutForm } from './new/CheckoutForm'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsImF1ZCI6ImZhbiJ9.c2lnbmF0dXJl'
const BUYER_ID = '0190f3a2-7c1e-7b55-9a1e-3f1f6b2c9d10'
const MEMBER_ID = '0190f3a2-7c1e-7b55-9a1e-3f1f6b2c9d11'
const FRIEND_PHONE = '+212612345678'

let session: string | undefined
let locale: string | undefined
vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) => {
      const value = name === '__Host-jomhor_session' ? session : name === 'locale' ? locale : undefined
      return value ? { name, value } : undefined
    },
  }),
  headers: async () => new Headers(),
}))
const { router } = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn(), refresh: vi.fn() } }))
vi.mock('next/navigation', async (actual) => ({ ...(await actual<object>()), useRouter: () => router }))

const d = getDictionary(defaultLocale)
const fetchMock = vi.fn<typeof fetch>()
let root: Root
let el: HTMLDivElement
beforeEach(() => {
  session = TOKEN
  locale = undefined
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubGlobal('fetch', fetchMock)
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval', 'performance'] })
  vi.setSystemTime(NOW)
  el = document.createElement('div')
  document.body.append(el)
  root = createRoot(el)
})
afterEach(() => {
  act(() => root.unmount())
  el.remove()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
  Object.values(router).forEach((f) => f.mockReset())
})

const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const $ = <T extends Element = HTMLInputElement>(sel: string) => el.querySelector<T & HTMLElement>(sel)!
function type(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
  input.dispatchEvent(new Event('input', { bubbles: true }))
}
const submit = () => act(async () => $<HTMLButtonElement>('button[type="submit"]').click())

// ---- W3 · "Paie sa place"

async function renderCheckout(qty = 3) {
  await act(async () =>
    root.render(
      <CheckoutForm
        eventId={12}
        priceCategoryId="pc-virage"
        qty={qty}
        displayName="Yazid Aqel"
        unitMad={5000}
        methods={['CARD', 'WALLET', 'CASH_AGENCY']}
        cashLate={false}
        cashDeadline="Lun. 2 févr. 2026 · 13:00"
        now={NOW}
        timezone="Africa/Casablanca"
        copy={{ checkout: d.checkout, errors: d.errors, meta: d.meta }}
      />,
    ),
  )
  await act(async () => {
    type($('#name-1'), 'Salma Benali')
    type($('#name-2'), 'Omar Idrissi')
    type($('#phone-2'), '0698765432')
  })
}

it('W3: the switch shows on friend rows once a phone is typed, default off; never on row 1', async () => {
  await renderCheckout()
  expect(el.querySelector('#pays-0')).toBeNull()
  expect(el.querySelector('#pays-1')).toBeNull() // no phone yet
  expect($('#pays-2').checked).toBe(false)
  expect($('#pays-2').getAttribute('role')).toBe('switch')
  expect(el.textContent).toContain(d.checkout.paysToggle)
  expect(el.querySelector('[data-split]')).toBeNull()
  await act(async () => type($('#phone-1'), '0612345678'))
  expect($('#pays-1').checked).toBe(false)
})

it('W3: the switch posts pays: true on that holder only; the summary splits places and amount in integers', async () => {
  await renderCheckout()
  await act(async () => $('#pays-2').click())
  expect($('[data-split]').textContent).toBe(
    `Tu paies 2 place(s) · ${ltr('100\u00a0DH')}1 place(s) payée(s) par tes amis${d.checkout.splitCaption}`,
  )
  expect($('button[type="submit"]').textContent).toBe(`Payer ${ltr('100\u00a0DH')}`)
  await act(async () => $('#consent').click())

  const assign = vi.fn()
  vi.stubGlobal('location', { ...window.location, assign })
  fetchMock
    .mockResolvedValueOnce(reply(201, { id: 'o1', status: 'RESERVED', reserved_until: '2026-02-01T12:08:00Z', total_mad: 10000 }))
    .mockResolvedValueOnce(reply(200, { redirect_url: 'https://api.test/v1/payments/card/p1/redirect' }))
  await submit()

  const [url, init] = fetchMock.mock.calls[0]!
  expect(url).toBe('/api/orders')
  expect(JSON.parse(init!.body as string).items[0].holders).toEqual([
    { name: 'Yazid Aqel' },
    { name: 'Salma Benali' },
    { name: 'Omar Idrissi', phone: '+212698765432', pays: true },
  ])
  expect(assign).toHaveBeenCalledWith('https://api.test/v1/payments/card/p1/redirect')
})

it('W3: 422 TOO_LATE turns the switches off and disables them with "Trop tard pour partager"', async () => {
  await renderCheckout()
  await act(async () => $('#pays-2').click())
  await act(async () => $('#consent').click())
  fetchMock.mockResolvedValueOnce(
    reply(422, { code: 'VALIDATION_FAILED', message: '', details: { fields: { 'items[0].holders[2].pays': 'too late' }, reason: 'TOO_LATE' } }),
  )
  await submit()
  expect($('#pays-2').disabled).toBe(true)
  expect($('#pays-2').checked).toBe(false)
  expect($('#pays-2-hint').textContent).toBe(d.checkout.splitTooLate)
  expect($('[role="alert"]').textContent).toBe(d.checkout.splitTooLate)
  expect(el.querySelector('[data-split]')).toBeNull()
  expect($('button[type="submit"]').textContent).toBe(`Payer ${ltr('150\u00a0DH')}`)
})

it('W3: PHONE_REQUIRED marks that row; a member over their limit (409 details.field) marks theirs', async () => {
  await renderCheckout()
  await act(async () => $('#pays-2').click())
  await act(async () => $('#consent').click())
  fetchMock.mockResolvedValueOnce(
    reply(422, { code: 'VALIDATION_FAILED', message: '', details: { fields: { 'items[0].holders[2].pays': 'x' }, reason: 'PHONE_REQUIRED' } }),
  )
  await submit()
  expect($('#phone-2').getAttribute('aria-invalid')).toBe('true')
  expect($('#phone-2-error').textContent).toBe(d.checkout.paysPhoneRequired)
  expect(document.activeElement).toBe($('#phone-2'))

  fetchMock.mockResolvedValueOnce(reply(409, { code: 'PER_ACCOUNT_LIMIT', message: '', details: { limit: 4, held: 4, field: 'items[0].holders[2]' } }))
  await submit()
  expect($('#phone-2-error').textContent).toBe('Cette personne a déjà 4 billet(s) pour cet événement : 4 maximum par compte.')
  expect($('#phone-1').getAttribute('aria-invalid')).toBe('false')
})

it('W3: NO_BUYER_PLACE (422 on items) says so at form level', async () => {
  await renderCheckout()
  fetchMock.mockResolvedValueOnce(reply(422, { code: 'VALIDATION_FAILED', message: '', details: { fields: { items: 'x' }, reason: 'NO_BUYER_PLACE' } }))
  await act(async () => $('#consent').click())
  await submit()
  expect($('[role="alert"]').textContent).toBe(d.errors.NO_BUYER_PLACE)
})

it('W3 page: the sidebar total is neutral ("Total"), never "À payer" next to the full order', async () => {
  fetchMock.mockImplementation(async (url) =>
    String(url).endsWith('/fan/me')
      ? json(200, { id: 'u1', display_name: 'Yazid Aqel', locale: 'fr' })
      : String(url).endsWith('/fan/events/12')
        ? json(200, rawEvent())
        : json(404, { code: 'NOT_FOUND', message: '', details: {} }),
  )
  const h = renderToString(await CheckoutPage({ searchParams: Promise.resolve({ event: '12', price: 'pc-virage', qty: '3' }) }))
  expect(h).toContain('>Total</span>')
  expect(h).not.toContain('À payer')
})

// ---- W4 · server render

const backend = (routes: Record<string, () => Response>) =>
  fetchMock.mockImplementation(async (url) => {
    const route = routes[String(url).replace('http://backend.test/v1', '')]
    return route ? route() : json(404, { code: 'NOT_FOUND', message: '', details: {} })
  })
const item = (label: string) => ({ price_category_id: 'pc-virage', zone_id: 3, name: 'Virage', qty: 1, unit_price_mad: 5000, holders: [{ label }] })
const memberOrder = (over: Record<string, unknown> = {}) => ({
  id: MEMBER_ID,
  event_id: 12,
  channel: 'APP',
  status: 'RESERVED',
  payment_status: null,
  payment_method: null,
  total_mad: 5000,
  reserved_until: '2026-02-01T14:00:00Z',
  paid_at: null,
  items: [item('Salma B.')],
  ticket_ids: [],
  cash: null,
  group: { role: 'MEMBER', initiator_label: 'Yazid A.', initiator_status: 'PAID' },
  ...over,
})
const page = async (order: Record<string, unknown>, event = rawEvent()) => {
  backend({ [`/fan/orders/${order.id}`]: () => json(200, order), '/fan/events/12': () => json(200, event) })
  return renderToString(await OrderPage({ params: Promise.resolve({ id: String(order.id) }) }))
}
const methodRows = (h: string) =>
  [...h.matchAll(/<input[^>]*name="method"[^>]*>/g)].map(([tag]) => `${/value="(\w+)"/.exec(tag)![1]}${/disabled/.test(tag) ? ' (off)' : ''}`)

it('W4 member: event header, "{label} t’a gardé une place" in an isolate, amount, countdown on the server clock, method rows, CTA', async () => {
  const h = await page(memberOrder())
  expect(h).toContain('ATLAS FC')
  expect(h).toContain('ATLAS FC vs Rapid Oued')
  expect(h).toContain('<bdi>Yazid A.</bdi> t’a gardé une place')
  expect(h).toMatch(/data-amount="true"[^>]*>\u206650\u00a0DH\u2069</)
  expect(h).toContain('Ta place est gardée jusqu’à 15:00 · encore 2 h 0 min') // 14:00Z in Africa/Casablanca
  expect(methodRows(h)).toEqual(['CARD', 'WALLET', 'CASH_AGENCY'])
  expect(h).toContain('Avant Lun. 2 févr. 2026 · 13:00')
  expect(h).toContain(`Payer ${ltr('50\u00a0DH')}`)
  expect(h).not.toContain('Paiement en cours')
  expect(h).not.toContain(TOKEN)
})

it('W4 member without initiator_label: "Un ami t’a gardé une place"', async () => {
  const h = await page(memberOrder({ group: { role: 'MEMBER', initiator_label: null, initiator_status: 'PAID' } }))
  expect(h).toMatch(/<h1[^>]*>Un ami t’a gardé une place<\/h1>/)
  expect(h).not.toContain('<bdi>Yazid')
})

it('W4 member after an attempt (cash code, or a card still pending): the existing layouts, not the member variant', async () => {
  const cash = await page(
    memberOrder({ status: 'AWAITING_CASH', payment_status: 'PENDING', payment_method: 'CASH_AGENCY', cash: { pay_code: '4F7K29', amount_mad: 5000, points: [], networks: ['Wafacash'] } }),
  )
  expect(cash).toContain('JMH 4F7K 29')
  expect(cash).not.toContain('t’a gardé une place')
  const pending = await page(memberOrder({ payment_status: 'PENDING', payment_method: 'CARD' }))
  expect(pending).toContain('Paiement en cours')
  const failed = await page(memberOrder({ payment_status: 'FAILED', payment_method: 'CARD' }))
  expect(failed).toContain('t’a gardé une place')
})

it.each([
  ['no attempt', null],
  ['a FAILED attempt', 'FAILED'],
])('W4 member whose hold has passed while still RESERVED (expiry job lag), %s: "La place a été libérée" + the event, never "Paiement en cours"', async (_, payment_status) => {
  const h = await page(memberOrder({ reserved_until: '2026-02-01T11:59:00Z', payment_status }))
  expect(h).not.toContain('t’a gardé une place')
  expect(h).toContain(d.orderPage.memberExpiredTitle)
  expect(h).toContain(d.orderPage.memberExpired)
  expect(h).toContain('href="/atlas-fc/events/12-atlas-fc-vs-rapid-oued"')
  expect(h).not.toContain('Paiement en cours')
  expect(h).not.toContain('jusqu’à') // no hold banner
  expect(methodRows(h)).toEqual([])
  expect(h).not.toContain('Payer à nouveau')
})

it('W4 member whose hold has passed with a card attempt PENDING: still "Paiement en cours" (a late success stays possible)', async () => {
  const h = await page(memberOrder({ reserved_until: '2026-02-01T11:59:00Z', payment_status: 'PENDING', payment_method: 'CARD' }))
  expect(h).toContain('Paiement en cours')
  expect(h).not.toContain(d.orderPage.memberExpiredTitle)
})

it('a buyer (not a member) whose hold has passed keeps the normal view until the backend says EXPIRED', async () => {
  const h = await page(memberOrder({ reserved_until: '2026-02-01T11:59:00Z', group: null }))
  expect(h).toContain('Paiement en cours')
  expect(h).not.toContain(d.orderPage.memberExpiredTitle)
})

it('W4 member EXPIRED: "La place a été libérée", back to the event, nothing to pay', async () => {
  const h = await page(memberOrder({ status: 'EXPIRED' }))
  expect(h).toContain(d.orderPage.memberExpiredTitle)
  expect(h).toContain('href="/atlas-fc/events/12-atlas-fc-vs-rapid-oued"')
  expect(methodRows(h)).toEqual([])
})

it('W4 member in AR: Darija copy, label isolated, Western digits; the layout sets dir=rtl', async () => {
  locale = 'ar'
  const ar = getDictionary('ar').orderPage
  const h = await page(memberOrder())
  expect(h).toContain(`<bdi>Yazid A.</bdi>${ar.memberTitle.replace('{label}', '')}`)
  expect(h).toContain('البلاصة ديالك محجوزة حتى 15:00 · باقي 2 س 0 د')
  expect(h).not.toContain('t’a gardé')
  expect(h).not.toMatch(/[٠-٩۰-۹]/)
  expect(dirOf('ar')).toBe('rtl')
})

const member = (order_id: string, holder_label: string, status: string, pay_url: string | null, extra: Record<string, unknown> = {}) => ({
  order_id,
  holder_label,
  places: 1,
  total_mad: 5000,
  status,
  reserved_until: status === 'RESERVED' ? '2026-02-01T14:00:00Z' : '2026-02-01T11:00:00Z',
  pay_url,
  ...extra,
})
const initiatorOrder = (members: unknown[]) => ({
  id: BUYER_ID,
  event_id: 12,
  channel: 'WEB',
  status: 'PAID',
  payment_status: 'SUCCEEDED',
  payment_method: 'CARD',
  total_mad: 5000,
  reserved_until: '2026-02-01T12:08:00Z',
  paid_at: '2026-02-01T12:00:00Z',
  items: [item('Yazid A.')],
  ticket_ids: ['t1'],
  cash: null,
  group: { role: 'INITIATOR', members },
})

it('W4 initiator PAID: places paid over total, member rows with chips, WhatsApp + copy per unpaid member, live while one is payable', async () => {
  const h = await page(
    initiatorOrder([
      member('m1', 'Salma B.', 'PAID', 'https://shop.test/orders/m1'),
      member('m2', 'Karim B.', 'RESERVED', 'https://shop.test/orders/m2?x=1&y=2', { phone: FRIEND_PHONE, name: 'Karim Bennani' }),
      member('m3', 'Omar I.', 'EXPIRED', 'https://shop.test/orders/m3'),
      member('m4', 'يزيد ع.', 'RESERVED', 'javascript:alert(1)'),
    ]),
  )
  expect(h).toContain('Ta bande · ATLAS FC vs Rapid Oued')
  expect(h).toContain(`${ltr('2/5')}</span> payées`) // the buyer's place + Salma's, of 1 + 4
  const rows = [...h.matchAll(/data-member="true"[\s\S]*?<\/li>/g)].map(([r]) => r.replace(/<[^>]+>/g, '|').replace(/\|+/g, '|'))
  expect(rows).toHaveLength(4)
  expect(rows[0]).toContain('|SB|Salma B.|')
  expect(rows[0]).toContain('|Payé|')
  expect(rows[1]).toContain('|KB|Karim B.|')
  expect(rows[1]).toContain('À payer · avant 15:00')
  expect(rows[2]).toContain('|Expiré|')
  expect(rows[3]).toContain('|ي ع|يزيد ع.|') // Arabic initials spaced so they don't join
  const wa = [...h.matchAll(/href="(https:\/\/wa\.me\/[^"]*)"/g)].map(([, u]) => u!.replace(/&amp;/g, '&'))
  expect(wa).toHaveLength(1) // Karim only: Salma paid, Omar expired, m4's pay_url isn't http(s)
  expect(wa[0]).toContain(encodeURIComponent('https://shop.test/orders/m2?x=1&y=2'))
  expect(new URL(wa[0]!).searchParams.get('text')).toBe('Je t’ai gardé une place pour ATLAS FC vs Rapid Oued. Paie-la avant 15:00 ici : https://shop.test/orders/m2?x=1&y=2')
  expect(h.match(/Copier le lien/g)).toHaveLength(1)
  // The per-row controls name their member for screen readers.
  expect(h).toMatch(/<bdi id="member-m2"[^>]*>Karim B\.<\/bdi>/)
  expect(h).toMatch(/<a href="https:\/\/wa\.me\/[^"]*"[^>]*aria-describedby="member-m2"/)
  expect(h).toMatch(/<button[^>]*aria-describedby="member-m2"[^>]*>[\s\S]*?Copier le lien/)
  expect(h).toContain('data-live="true"')
  expect(h).not.toContain('javascript:')
  expect(h).not.toContain(FRIEND_PHONE)
  expect(h).not.toContain('612345678')
  expect(h).not.toContain('Karim Bennani')
})

it('W4 initiator PAID with nobody left to pay: no live refresh, no relance', async () => {
  const h = await page(initiatorOrder([member('m1', 'Salma B.', 'PAID', 'https://shop.test/orders/m1'), member('m3', 'Omar I.', 'EXPIRED', 'https://shop.test/orders/m3')]))
  expect(h).toContain(`${ltr('2/3')}</span> payées`)
  expect(h).not.toContain('data-live')
  expect(h).not.toContain('wa.me')
})

it('W4 initiator before paying: no group block yet', async () => {
  const h = await page({ ...initiatorOrder([member('m2', 'Karim B.', 'RESERVED', 'https://shop.test/orders/m2')]), status: 'RESERVED', payment_status: null })
  expect(h).not.toContain('Ta bande')
})

// ---- W4 · client parts

async function renderMember(over: Partial<Parameters<typeof MemberPay>[0]> = {}) {
  await act(async () =>
    root.render(
      <MemberPay
        id={MEMBER_ID}
        amountMad={5000}
        reservedUntil="2026-02-01T14:00:00Z"
        now={NOW}
        timezone="Africa/Casablanca"
        methods={['CARD', 'CASH_AGENCY']}
        cashLate={false}
        cashDeadline="Lun. 2 févr. 2026 · 13:00"
        copy={{ checkout: d.checkout, orderPage: d.orderPage, errors: d.errors, meta: d.meta }}
        {...over}
      />,
    ),
  )
}

it('MemberPay: countdown on server time, not the device clock', async () => {
  vi.setSystemTime(NOW + 300_000) // device 5 min fast
  await renderMember()
  expect($('[data-member-hold]').textContent).toBe('Ta place est gardée jusqu’à 15:00 · encore 2 h 0 min')
  await act(() => vi.advanceTimersByTimeAsync(61_000))
  expect($('[data-member-hold]').textContent).toContain('encore 1 h 58 min')
})

it('MemberPay: card goes through the shop /pay handler and follows the hosted page', async () => {
  await renderMember()
  const assign = vi.fn()
  vi.stubGlobal('location', { ...window.location, assign })
  fetchMock.mockResolvedValueOnce(reply(200, { redirect_url: 'https://api.test/v1/payments/card/p9/redirect' }))
  await submit()
  expect(fetchMock.mock.calls.map(([u, init]) => [u, init!.body])).toEqual([[`/api/orders/${MEMBER_ID}/pay`, JSON.stringify({ method: 'CARD' })]])
  expect(assign).toHaveBeenCalledWith('https://api.test/v1/payments/card/p9/redirect')
})

it('MemberPay: cash asks for a code, then refreshes the page (which shows the code)', async () => {
  await renderMember()
  await act(async () => $('input[name="method"][value="CASH_AGENCY"]').click())
  fetchMock.mockResolvedValueOnce(reply(200, { method: 'CASH_AGENCY' }))
  await submit()
  expect(JSON.parse(fetchMock.mock.calls[0]![1]!.body as string)).toEqual({ method: 'CASH_AGENCY' })
  expect(router.refresh).toHaveBeenCalledTimes(1)
})

it('MemberPay: a refused method is marked unavailable and the error shown', async () => {
  await renderMember()
  await act(async () => $('input[name="method"][value="CASH_AGENCY"]').click())
  fetchMock.mockResolvedValueOnce(reply(409, { code: 'METHOD_NOT_AVAILABLE', message: '', details: { reason: 'TOO_LATE' } }))
  await submit()
  expect($('[role="alert"]').textContent).toBe(d.errors.METHOD_TOO_LATE)
  expect($('input[name="method"][value="CASH_AGENCY"]').disabled).toBe(true)
  expect($('input[name="method"][value="CARD"]').checked).toBe(true)
})

it('MemberPay at the deadline: "La place a été libérée", nothing to pay, one refresh to hand over to OrderStatus', async () => {
  await renderMember({ reservedUntil: '2026-02-01T12:00:02Z' })
  expect(el.querySelector('form')).not.toBeNull()
  expect(router.refresh).not.toHaveBeenCalled()
  await act(() => vi.advanceTimersByTimeAsync(3_000))
  expect(el.textContent).toContain(d.orderPage.memberExpiredTitle)
  expect(el.querySelector('form')).toBeNull()
  await act(() => vi.advanceTimersByTimeAsync(30_000))
  expect(router.refresh).toHaveBeenCalledTimes(1)
})

it('MemberPay: a 401 on /pay goes to login and back to this order', async () => {
  await renderMember()
  fetchMock.mockResolvedValueOnce(reply(401, { code: 'UNAUTHORIZED', message: '', details: {} }))
  await submit()
  expect(router.push).toHaveBeenCalledWith(`/login?next=${encodeURIComponent(`/orders/${MEMBER_ID}`)}`)
  expect(el.querySelector('[role="alert"]')).toBeNull()
})

it('Refresh: router.refresh every 10 s while the tab is visible, not while hidden', async () => {
  let hidden = false
  vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden)
  await act(async () => root.render(<Refresh />))
  await act(() => vi.advanceTimersByTimeAsync(10_000))
  expect(router.refresh).toHaveBeenCalledTimes(1)
  hidden = true
  await act(() => vi.advanceTimersByTimeAsync(30_000))
  expect(router.refresh).toHaveBeenCalledTimes(1)
})

it('OrderStatus (member): the hold passing on server time turns the view into "La place a été libérée" before the backend does', async () => {
  vi.setSystemTime(NOW - 600_000) // device clock 10 min slow: it must not matter
  const initial = { status: 'RESERVED', reserved_until: new Date(NOW + 2_000).toISOString(), ticket_count: 0, payment_status: null, payment_method: null }
  fetchMock.mockImplementation(async () => reply(200, initial)) // the expiry job hasn't run
  await act(async () =>
    root.render(
      <OrderStatus
        id={MEMBER_ID}
        initial={initial}
        now={NOW}
        places={1}
        timezone="Africa/Casablanca"
        eventHref="/atlas-fc/events/12-atlas-fc-vs-rapid-oued"
        methods={['CARD']}
        member
        copy={{ orderPage: d.orderPage, errors: d.errors, meta: d.meta, steps: d.steps }}
      />,
    ),
  )
  expect($('h1').textContent).toBe(d.orderPage.pendingTitle)
  await act(() => vi.advanceTimersByTimeAsync(3_000))
  expect($('h1').textContent).toBe(d.orderPage.memberExpiredTitle)
  expect($<HTMLAnchorElement>('a[href="/atlas-fc/events/12-atlas-fc-vs-rapid-oued"]')).not.toBeNull()
  expect(el.textContent).not.toContain('Paiement en cours')
})
