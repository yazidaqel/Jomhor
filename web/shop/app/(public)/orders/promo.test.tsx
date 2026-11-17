// @vitest-environment jsdom
// WS-17 (plan lot-5 §12.5, 4b): the promo code on W3 and the discount line on W4. No amount is computed by the shop:
// every discount and total below is the server's, shown as sent.
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { renderToString } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { json, rawEvent } from '@/lib/events.fixture'
import { fmt, getDictionary, ltr, type Locale } from '@/lib/i18n'
import { formatDh, formatDiscount } from '@/lib/money'
import { POST as createOrder } from '../../api/orders/route'
import OrderPage from './[id]/page'
import CheckoutPage from './new/page'
import { CheckoutForm } from './new/CheckoutForm'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsImF1ZCI6ImZhbiJ9.c2lnbmF0dXJl'
const ORDER_ID = '0190f3a2-7c1e-7b55-9a1e-3f1f6b2c9d10'
const RENDERED = Date.parse('2026-02-01T12:00:00Z')

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
const { push } = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('next/navigation', async (actual) => ({ ...(await actual<object>()), useRouter: () => ({ push, replace: () => {}, refresh: () => {} }) }))

// source: contract (docs/04-api.md POST /fan/orders and GET /fan/orders/{id} "Lot 5 step 4", §Promo codes, PROMO_REFUSED;
// numbers from plan lot-5 BE-65 (a): 20 % on 2 × 15 000 → total_mad 24 000, discount_mad 6 000).
const CREATED = { id: 'o1', status: 'RESERVED', reserved_until: '2026-02-01T12:09:00Z', total_mad: 24000, promo_code: 'BIENVENUE20', discount_mad: 6000 }
const ORDER = (over: Record<string, unknown> = {}) => ({
  id: ORDER_ID,
  event_id: 12,
  channel: 'WEB',
  status: 'RESERVED',
  total_mad: 24000,
  promo_code: 'BIENVENUE20',
  discount_mad: 6000,
  reserved_until: '2026-02-01T12:08:00Z',
  paid_at: null,
  items: [{ price_category_id: 'pc-virage', zone_id: 3, name: 'Plein tarif', qty: 2, unit_price_mad: 15000, holders: [{ label: 'Yazid A.' }, { label: 'Salma B.' }] }],
  ticket_ids: [],
  ...over,
})
const CASH = {
  status: 'AWAITING_CASH',
  payment_status: 'PENDING',
  payment_method: 'CASH_AGENCY',
  reserved_until: '2026-02-02T12:00:00Z',
  cash: { pay_code: '4F7K29', amount_mad: 24000, points: [], networks: ['Wafacash'] },
}
const refused = (reason: string) => json(409, { code: 'PROMO_REFUSED', message: '', details: { field: 'promo_code', reason } })

const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => {
  session = TOKEN
  locale = undefined
  vi.stubEnv('API_BASE_URL', 'http://backend.test/v1')
  vi.stubEnv('SHOP_ORIGIN', 'http://shop.test')
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
  push.mockReset()
})

const bodyOf = (n: number) => JSON.parse(String(fetchMock.mock.calls[n]![1]!.body)) as Record<string, unknown>

describe('route /api/orders', () => {
  const req = (body: unknown) =>
    new Request('http://shop.test/api/orders', {
      method: 'POST',
      headers: { origin: 'http://shop.test', cookie: `__Host-jomhor_session=${TOKEN}`, 'content-type': 'application/json' },
      body: JSON.stringify(body),
    })
  const VALID = { event_id: 12, items: [{ price_category_id: 'pc-virage', qty: 2, holders: [{ name: 'Yazid Aqel' }, { name: 'Salma Benali' }] }] }

  it('forwards the code trimmed (the backend normalizes it) and answers the server discount and total', async () => {
    fetchMock.mockResolvedValue(json(201, { ...ORDER(), id: 'o1', token: 'SECRET' }))
    const res = await createOrder(req({ ...VALID, promo_code: '  bienvenue20 ' }))
    expect(res.status).toBe(201)
    expect(bodyOf(0).promo_code).toBe('bienvenue20')
    const text = await res.text()
    expect(JSON.parse(text)).toEqual({ id: 'o1', status: 'RESERVED', reserved_until: '2026-02-01T12:08:00Z', total_mad: 24000, promo_code: 'BIENVENUE20', discount_mad: 6000 })
    expect(text).not.toContain(TOKEN)
    expect(text).not.toContain('SECRET')
  })

  it.each([[''], ['   '], [null]])('an empty code (%j) is left out of the backend body', async (promo_code) => {
    fetchMock.mockResolvedValue(json(201, ORDER({ promo_code: null, discount_mad: 0 })))
    await createOrder(req({ ...VALID, promo_code }))
    expect(bodyOf(0)).not.toHaveProperty('promo_code')
  })

  it('a code that is not a string is a tampered body: 422 with no backend call', async () => {
    expect((await createOrder(req({ ...VALID, promo_code: 20 }))).status).toBe(422)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('PROMO_REFUSED passes through in the 04 shape', async () => {
    fetchMock.mockResolvedValue(refused('USED_UP'))
    const res = await createOrder(req({ ...VALID, promo_code: 'BIENVENUE20' }))
    expect(res.status).toBe(409)
    expect(await res.json()).toEqual({ code: 'PROMO_REFUSED', message: '', details: { field: 'promo_code', reason: 'USED_UP' } })
  })
})

describe('W3 promo code', () => {
  let root: Root
  let el: HTMLDivElement
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval', 'performance'] })
    vi.setSystemTime(RENDERED)
    el = document.createElement('div')
    document.body.append(el)
    root = createRoot(el)
  })
  afterEach(() => {
    act(() => root.unmount())
    el.remove()
  })

  const render = (loc: Locale = 'fr') => {
    const d = getDictionary(loc)
    return act(async () =>
      root.render(
        <CheckoutForm
          eventId={12}
          priceCategoryId="pc-virage"
          qty={1}
          displayName="Yazid Aqel"
          unitMad={15000}
          methods={['CARD', 'CASH_AGENCY']}
          cashLate={false}
          cashDeadline="Lun. 2 févr. 2026 · 13:00"
          now={RENDERED}
          timezone="Africa/Casablanca"
          copy={{ checkout: d.checkout, errors: d.errors, meta: d.meta }}
        />,
      ),
    )
  }
  const button = (text: string) => [...el.querySelectorAll('button')].find((b) => b.textContent === text)!
  const cta = () => el.querySelector<HTMLButtonElement>('button[type="submit"]')!
  const submit = () => act(async () => cta().click())
  const promoInput = () => el.querySelector<HTMLInputElement>('#promo')!
  async function typeCode(text: string, d = getDictionary('fr')) {
    await act(async () => button(d.checkout.promoOpen).click())
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(promoInput(), text)
      promoInput().dispatchEvent(new Event('input', { bubbles: true }))
    })
  }
  const d = getDictionary('fr')

  it('no code: placeOrder unchanged (create without promo_code, then /pay, then the hosted page)', async () => {
    await render()
    expect(cta().textContent).toBe(fmt(d.checkout.pay, { amount: formatDh(15000) }))
    const assign = vi.fn()
    vi.stubGlobal('location', { ...window.location, assign })
    fetchMock
      .mockResolvedValueOnce(json(201, { ...CREATED, total_mad: 15000, promo_code: null, discount_mad: 0 }))
      .mockResolvedValueOnce(json(200, { redirect_url: 'https://api.test/v1/payments/card/p1/redirect' }))
    await submit()
    expect(fetchMock.mock.calls.map(([u]) => u)).toEqual(['/api/orders', '/api/orders/o1/pay'])
    expect(bodyOf(0)).not.toHaveProperty('promo_code')
    expect(assign).toHaveBeenCalledWith('https://api.test/v1/payments/card/p1/redirect')
  })

  it('with a code: the body carries promo_code, it stops before /pay, shows the server discount and total, then pays that total', async () => {
    await render()
    await typeCode('bienvenue20')
    expect(document.activeElement).toBe(promoInput()) // autoFocus when "J'ai un code promo" opens it
    expect(promoInput().value).toBe('BIENVENUE20')
    expect(cta().textContent).toBe(d.checkout.promoReserve)
    fetchMock.mockResolvedValueOnce(json(201, CREATED))
    await submit()

    expect(fetchMock).toHaveBeenCalledTimes(1) // no /pay yet
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/orders')
    expect(bodyOf(0).promo_code).toBe('BIENVENUE20')
    expect(push).not.toHaveBeenCalled()
    expect(el.querySelector('[data-promo]')!.textContent).toBe(
      fmt(d.checkout.promoLine, { code: ltr('BIENVENUE20'), discount: formatDiscount(6000) }) + d.checkout.total + formatDh(24000),
    )
    expect(el.querySelector('[data-total]')!.textContent).toBe(formatDh(24000))
    expect(el.querySelector('[data-held]')!.textContent).toBe('Places réservées jusqu’à 13:09 · encore 9 min 0 s')
    expect(el.querySelector('#promo')).toBeNull() // the code is in the order now
    expect(el.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(true)
    expect(cta().textContent).toBe(fmt(d.checkout.pay, { amount: formatDh(24000) }))

    const assign = vi.fn()
    vi.stubGlobal('location', { ...window.location, assign })
    fetchMock.mockResolvedValueOnce(json(200, { redirect_url: 'https://api.test/v1/payments/card/p2/redirect' }))
    await submit()
    expect(fetchMock.mock.calls.slice(1).map(([u, init]) => [u, init!.body])).toEqual([['/api/orders/o1/pay', JSON.stringify({ method: 'CARD' })]])
    expect(assign).toHaveBeenCalledWith('https://api.test/v1/payments/card/p2/redirect')
  })

  it('with a code and cash: the code card opens on the order page after the hold', async () => {
    await render()
    await typeCode('BIENVENUE20')
    await act(async () => el.querySelector<HTMLInputElement>('input[name="method"][value="CASH_AGENCY"]')!.click())
    expect(cta().textContent).toBe(d.checkout.promoReserve)
    fetchMock.mockResolvedValueOnce(json(201, CREATED))
    await submit()
    expect(cta().textContent).toBe(d.checkout.payCash)
    fetchMock.mockResolvedValueOnce(json(200, { pay_code: '4F7K29' }))
    await submit()
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/orders/o1/pay')
    expect(push).toHaveBeenCalledWith('/orders/o1')
  })

  it.each([
    ['UNKNOWN', refused('UNKNOWN'), d.checkout.promoRefused.UNKNOWN],
    ['NOT_APPLICABLE', refused('NOT_APPLICABLE'), d.checkout.promoRefused.NOT_APPLICABLE],
    ['USED_UP', refused('USED_UP'), d.checkout.promoRefused.USED_UP],
    ['ACCOUNT_LIMIT', refused('ACCOUNT_LIMIT'), d.checkout.promoRefused.ACCOUNT_LIMIT],
    ['422 on promo_code', json(422, { code: 'VALIDATION_FAILED', message: '', details: { fields: { promo_code: 'invalid' } } }), d.checkout.promoRefused.UNKNOWN],
  ])('refusal %s: its copy on the field, no order, no /pay, the fan can retry', async (_, answer, copy) => {
    await render()
    await typeCode('BIENVENUE20')
    fetchMock.mockResolvedValueOnce(answer)
    await submit()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(el.querySelector('#promo-error')!.textContent).toBe(copy)
    expect(promoInput().getAttribute('aria-invalid')).toBe('true')
    expect(promoInput().getAttribute('aria-describedby')).toBe('promo-error')
    expect(document.activeElement).toBe(promoInput())
    expect(el.querySelector('[data-held]')).toBeNull()
    expect(el.querySelector('[data-promo]')).toBeNull()
    expect(push).not.toHaveBeenCalled()
    expect(cta().disabled).toBe(false)
    expect(cta().textContent).toBe(d.checkout.promoReserve)
  })

  it('a hold answer without an integer total_mad is a failure: nothing to show or pay, no /pay', async () => {
    await render()
    await typeCode('BIENVENUE20')
    fetchMock.mockResolvedValueOnce(json(201, { ...CREATED, total_mad: '240' }))
    await submit()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(el.querySelector('[data-promo]')).toBeNull()
    expect(el.querySelector('[role="alert"]')!.textContent).toBe(d.errors.generic)
    expect(push).not.toHaveBeenCalled()
  })

  it('"Retirer" clears the code before the hold: the flow is the one without a code', async () => {
    await render()
    await typeCode('BIENVENUE20')
    await act(async () => button(d.checkout.promoRemove).click())
    expect(el.querySelector('#promo')).toBeNull()
    expect(cta().textContent).toBe(fmt(d.checkout.pay, { amount: formatDh(15000) }))
    vi.stubGlobal('location', { ...window.location, assign: vi.fn() })
    fetchMock
      .mockResolvedValueOnce(json(201, { ...CREATED, total_mad: 15000, promo_code: null, discount_mad: 0 }))
      .mockResolvedValueOnce(json(200, { redirect_url: 'https://api.test/v1/payments/card/p1/redirect' }))
    await submit()
    expect(bodyOf(0)).not.toHaveProperty('promo_code')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('renders in ar: the field, the CTA, the server line and a refusal', async () => {
    const ar = getDictionary('ar')
    await render('ar')
    await typeCode('BIENVENUE20', ar)
    expect(el.querySelector('label[for="promo"]')!.textContent).toBe(ar.checkout.promoLabel)
    expect(promoInput().getAttribute('dir')).toBe('ltr')
    expect(cta().textContent).toBe(ar.checkout.promoReserve)
    fetchMock.mockResolvedValueOnce(refused('ACCOUNT_LIMIT'))
    await submit()
    expect(el.querySelector('#promo-error')!.textContent).toBe(ar.checkout.promoRefused.ACCOUNT_LIMIT)
    fetchMock.mockResolvedValueOnce(json(201, CREATED))
    await submit()
    expect(el.querySelector('[data-promo] p')!.textContent).toBe(fmt(ar.checkout.promoLine, { code: ltr('BIENVENUE20'), discount: formatDiscount(6000) }))
  })
})

describe('W4 discount line', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(RENDERED)
  })
  function backend(order: Record<string, unknown>) {
    fetchMock.mockImplementation(async (url) => {
      const path = String(url).replace('http://backend.test/v1', '')
      if (path === `/fan/orders/${ORDER_ID}`) return json(200, order)
      if (path === '/fan/events/12') return json(200, rawEvent())
      return json(404, { code: 'NOT_FOUND', message: '', details: {} })
    })
  }
  const page = async () => renderToString(await OrderPage({ params: Promise.resolve({ id: ORDER_ID }) }))
  const line = (loc: Locale) => fmt(getDictionary(loc).orderPage.promoLine, { code: ltr('BIENVENUE20'), discount: formatDiscount(6000) })

  it('renders "Code X · −Y" from the server, list prices per line and the total after the discount; no JWT or K_t', async () => {
    backend(ORDER())
    const h = await page()
    expect(h).toMatch(new RegExp(`data-promo="true"[^>]*>${line('fr')}<`))
    expect(h).toContain(formatDh(30000)) // the line at list price, as sent (2 × 15 000)
    expect(h).toContain(formatDh(24000)) // total_mad
    for (const secret of [TOKEN, 'k_t', 'K_t', 'event_key', 'authorization']) expect(h).not.toContain(secret)
  })

  it('no discount line without a discount', async () => {
    backend(ORDER({ promo_code: null, discount_mad: 0, total_mad: 30000 }))
    const h = await page()
    expect(h).not.toContain('data-promo')
    expect(h).not.toContain('Code ')
  })

  it('the cash code card shows total_mad (the code amount the server set)', async () => {
    backend(ORDER(CASH))
    const h = await page()
    expect(h).toMatch(new RegExp(`data-amount="true"[^>]*>${formatDh(24000)}<`))
    expect(h).toContain(line('fr'))
  })

  it('renders in ar', async () => {
    locale = 'ar'
    backend(ORDER())
    const h = await page()
    expect(h).toContain(line('ar'))
    expect(h).not.toContain(TOKEN)
  })
})

describe('W3 side summary once a code is held', () => {
  const STRIKE = ['group-has-[[data-promo]]:line-through', 'group-has-[[data-promo]]:text-text-muted']

  it('strikes the list price line and total when the form holds [data-promo], and only then', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(Date.parse('2026-02-01T12:00:00Z'))
    fetchMock.mockImplementation(async (url) => {
      const path = String(url).replace('http://backend.test/v1', '')
      if (path === '/fan/events/12') return json(200, rawEvent())
      if (path === '/fan/me') return json(200, { id: 'u1', phone: '+212600000000', display_name: 'Yazid Aqel', locale: 'fr' })
      return json(404, { code: 'NOT_FOUND', message: '', details: {} })
    })
    const host = document.createElement('div')
    host.innerHTML = renderToString(await CheckoutPage({ searchParams: Promise.resolve({ event: '12', price: 'pc-virage', qty: '2' }) }))
    document.body.append(host)
    try {
      const main = host.querySelector('main')!
      const total = host.querySelector('[data-list-total]')!
      const line = [...host.querySelectorAll('aside p > span')].find((s) => s.textContent === total.textContent && s !== total)!
      expect(main.classList).toContain('group')
      for (const el of [total, line]) for (const c of STRIKE) expect(el.classList).toContain(c)
      expect(main.matches(':has([data-promo])')).toBe(false) // before the hold: the variant doesn't apply
      const held = document.createElement('div') // what CheckoutForm renders once the code is held
      held.setAttribute('data-promo', '')
      host.querySelector('form')!.append(held)
      expect(main.matches(':has([data-promo])')).toBe(true) // after: what the group-has variant selects
    } finally {
      host.remove()
    }
  })
})
