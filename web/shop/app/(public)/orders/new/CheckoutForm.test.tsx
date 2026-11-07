// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { CheckoutForm } from './CheckoutForm'

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
const RENDERED = Date.parse('2026-02-01T12:00:00Z')
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const fetchMock = vi.fn<typeof fetch>()
let root: Root
let el: HTMLDivElement
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval', 'performance'] })
  vi.setSystemTime(RENDERED + 300_000) // the device clock runs 5 min fast: the countdown must not care
  vi.stubGlobal('fetch', fetchMock)
  el = document.createElement('div')
  document.body.append(el)
  root = createRoot(el)
})
afterEach(() => {
  act(() => root.unmount())
  el.remove()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
  push.mockReset()
})

const radio = (m: string) => el.querySelector<HTMLInputElement>(`input[name="method"][value="${m}"]`)!
const hint = (m: string) => el.querySelector(`#method-${m}-hint`)!.textContent
const submit = () => act(async () => el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click())

it('cash refused (METHOD_NOT_AVAILABLE): stays on the held order with its countdown, cash off without its deadline, then pays it by card', async () => {
  await act(async () =>
    root.render(
      <CheckoutForm
        eventId={12}
        priceCategoryId="pc-virage"
        qty={1}
        displayName="Yazid Aqel"
        totalMad={5000}
        methods={['CARD', 'CASH_AGENCY']}
        cashLate={false}
        cashDeadline="Lun. 2 févr. 2026 · 13:00"
        now={RENDERED}
        timezone="Africa/Casablanca"
        copy={{ checkout: d.checkout, errors: d.errors, meta: d.meta }}
      />,
    ),
  )
  expect(hint('CASH_AGENCY')).toBe('Avant Lun. 2 févr. 2026 · 13:00')
  await act(() => vi.advanceTimersByTimeAsync(60_000)) // a minute filling the form: the order is created at 12:01Z
  await act(async () => radio('CASH_AGENCY').click())
  expect(el.querySelector('button[type="submit"]')!.textContent).toBe(d.checkout.payCash)

  fetchMock
    .mockResolvedValueOnce(json(201, { id: 'o1', status: 'RESERVED', reserved_until: '2026-02-01T12:09:00Z', total_mad: 5000 }))
    .mockResolvedValueOnce(json(409, { code: 'METHOD_NOT_AVAILABLE', message: '', details: { reason: 'CASH_CAP' } }))
  await submit()

  expect(push).not.toHaveBeenCalled()
  expect(el.querySelector('[role="alert"]')!.textContent).toBe(`${d.errors.METHOD_CASH_CAP} ${d.checkout.orderHeld}`)
  expect(el.querySelector('[role="alert"]')!.textContent).not.toMatch(/wallet/i) // this event offers no wallet
  // 12:09Z in Africa/Casablanca; 8 min left on server time from the first frame, whatever the device clock says.
  expect(el.querySelector('[data-held]')!.textContent).toBe('Places réservées jusqu’à 13:09 · encore 8 min 0 s')
  await act(() => vi.advanceTimersByTimeAsync(1_000))
  expect(el.querySelector('[data-held]')!.textContent).toContain('encore 7 min 59 s')
  expect(radio('CASH_AGENCY').disabled).toBe(true)
  expect(hint('CASH_AGENCY')).toBe(d.checkout.unavailable)
  expect(radio('CARD').checked).toBe(true)
  expect(el.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(true) // holder rows locked

  const assign = vi.fn()
  vi.stubGlobal('location', { ...window.location, assign })
  fetchMock.mockResolvedValueOnce(json(200, { redirect_url: 'https://api.test/v1/payments/card/p2/redirect' }))
  await submit()
  expect(fetchMock.mock.calls.slice(2).map(([u, init]) => [u, init!.body])).toEqual([['/api/orders/o1/pay', JSON.stringify({ method: 'CARD' })]])
  expect(assign).toHaveBeenCalledWith('https://api.test/v1/payments/card/p2/redirect')
})
