// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import type { Plan } from '@/lib/products'
import { ProductForm } from './ProductForm'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const { push, router } = vi.hoisted(() => {
  const push = vi.fn()
  return { push, router: { push, replace: () => {}, refresh: () => {} } }
})
vi.mock('next/navigation', () => ({ useRouter: () => router }))

const d = getDictionary(defaultLocale)
const PRODUCT = '0190f3a2-0000-7000-8000-00000000aa01'
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const fetchMock = vi.fn<typeof fetch>()
let root: Root
let el: HTMLDivElement
beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  el = document.createElement('div')
  document.body.append(el)
  root = createRoot(el)
})
afterEach(() => {
  act(() => root.unmount())
  el.remove()
  vi.unstubAllGlobals()
  fetchMock.mockReset()
  push.mockReset()
})

const IN_FULL: Plan = { installments: 1, amounts_mad: [150000], interval_days: null }
const IN_4: Plan = { installments: 4, amounts_mad: [37500, 37500, 37500, 37500], interval_days: 30 }
const show = (kind: 'PASS' | 'MEMBERSHIP' = 'PASS', plans = [IN_FULL]) =>
  act(async () =>
    root.render(
      <ProductForm
        productId={PRODUCT}
        kind={kind}
        displayName="Yazid Aqel"
        plans={plans}
        methods={['CARD', 'CASH_AGENCY']}
        cashLate={false}
        cashDeadline="Lun. 2 févr. 2026 · 13:00"
        copy={{ checkout: d.checkout, passes: d.passes, errors: d.errors }}
      />,
    ),
  )
const submit = () => act(async () => el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click())
const calls = () => fetchMock.mock.calls.map(([url, init]) => [url, init?.body ? JSON.parse(init.body as string) : undefined])

it('W3 product: holder prefilled, cash → posts through /api/product-orders, pays, lands on W4', async () => {
  await show()
  expect(el.querySelector<HTMLInputElement>('#holder-name')!.value).toBe('Yazid Aqel')
  expect(el.textContent).toContain(d.passes.captionPASS)
  expect(el.querySelector('button[type="submit"]')!.textContent).toContain(formatDh(150000)) // the price on the CTA
  await act(async () => el.querySelector<HTMLInputElement>('input[name="method"][value="CASH_AGENCY"]')!.click())
  fetchMock
    .mockResolvedValueOnce(json(201, { id: 'o1', status: 'RESERVED', reserved_until: '2026-02-01T12:08:00Z', total_mad: 150000 }))
    .mockResolvedValueOnce(json(200, { payment_id: 'p1', method: 'CASH_AGENCY', pay_code: '4F7K29' }))
  await submit()
  // The name is unchanged: no profile save. No quantity or zone; in full, so installments 1.
  expect(el.querySelector('input[name="plan"]')).toBeNull() // one plan: no choice to make
  expect(calls()).toEqual([
    ['/api/product-orders', { product_id: PRODUCT, holder_name: 'Yazid Aqel', installments: 1 }],
    ['/api/orders/o1/pay', { method: 'CASH_AGENCY' }],
  ])
  expect(push).toHaveBeenCalledWith('/orders/o1')
})

it('409 PASS_HELD → the reason and a link to Mes billets; 422 on holder_name marks the field', async () => {
  await show()
  fetchMock.mockResolvedValueOnce(json(409, { code: 'PASS_HELD', message: '', details: { pass_id: 'x', status: 'PENDING' } }))
  await submit()
  const alert = el.querySelector('[role="alert"]')!
  expect(alert.textContent).toContain(d.errors.PASS_HELD)
  expect(alert.querySelector('a')!.getAttribute('href')).toBe('/me/tickets')
  expect(push).not.toHaveBeenCalled()

  fetchMock.mockResolvedValueOnce(json(422, { code: 'VALIDATION_FAILED', message: '', details: { fields: { holder_name: 'invalid' } } }))
  await submit()
  expect(el.querySelector('#holder-name')!.getAttribute('aria-invalid')).toBe('true')
  expect(el.querySelector('#holder-error')!.textContent).toBe(d.checkout.nameInvalid)
})

it('3b plan choice: chips from plans, the CTA shows installment 1, the order is sent with installments 4', async () => {
  await show('PASS', [IN_FULL, IN_4])
  const radios = [...el.querySelectorAll<HTMLInputElement>('input[name="plan"]')]
  expect(radios.map((r) => r.value)).toEqual(['1', '4'])
  expect(radios[0]!.checked).toBe(true)
  expect(el.textContent).toContain(`En une fois · ${formatDh(150000)}`)
  expect(el.textContent).toContain(`En 4 fois · ${formatDh(37500)} puis ${formatDh(37500)} tous les 30 jours`)
  await act(async () => radios[1]!.click())
  expect(el.querySelector('button[type="submit"]')!.textContent).toContain(formatDh(37500))
  fetchMock
    .mockResolvedValueOnce(json(201, { id: 'o1', status: 'RESERVED', reserved_until: '2026-02-01T12:08:00Z', total_mad: 37500 }))
    .mockResolvedValueOnce(json(200, { payment_id: 'p1', method: 'CARD', redirect_url: 'http://backend.test/payments/card/p1/redirect' }))
  const assign = vi.fn()
  vi.stubGlobal('location', { ...window.location, assign, pathname: '/orders/new', search: '' })
  await submit()
  expect(calls()[0]).toEqual(['/api/product-orders', { product_id: PRODUCT, holder_name: 'Yazid Aqel', installments: 4 }])
  expect(assign).toHaveBeenCalledWith('http://backend.test/payments/card/p1/redirect')
})

it('3b: a plan no longer offered (422 NOT_OFFERED) says so', async () => {
  await show('PASS', [IN_FULL, IN_4])
  fetchMock.mockResolvedValueOnce(json(422, { code: 'VALIDATION_FAILED', message: '', details: { fields: { installments: 'invalid' }, reason: 'NOT_OFFERED' } }))
  await submit()
  expect(el.querySelector('[role="alert"]')!.textContent).toContain(d.errors.PLAN_NOT_OFFERED)
  expect(el.querySelector('#holder-name')!.getAttribute('aria-invalid')).toBe('false')
})

it('membership: its own hint and caption; a pass sold out in the meantime says so', async () => {
  await show('MEMBERSHIP')
  expect(el.textContent).toContain(d.passes.holderHintMEMBERSHIP)
  expect(el.textContent).toContain(d.passes.captionMEMBERSHIP)
  fetchMock.mockResolvedValueOnce(json(409, { code: 'SOLD_OUT', message: '', details: { product_id: PRODUCT } }))
  await submit()
  expect(el.querySelector('[role="alert"]')!.textContent).toContain(d.errors.PASS_SOLD_OUT)
})
