// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import type { OrderStatus as Status } from '@/lib/order-client'
import { CopyCode } from './CopyCode'
import { OrderStatus } from './OrderStatus'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const { refresh, router } = vi.hoisted(() => {
  const refresh = vi.fn()
  return { refresh, router: { refresh, push: () => {}, replace: () => {} } } // stable, like Next's
})
vi.mock('next/navigation', () => ({ useRouter: () => router }))

const d = getDictionary(defaultLocale)
const copy = { orderPage: d.orderPage, errors: d.errors, meta: d.meta, steps: d.steps }
const RENDERED = Date.parse('2026-02-01T12:00:00Z')
const CASH: Status = {
  status: 'AWAITING_CASH',
  reserved_until: '2026-02-02T12:00:00Z',
  ticket_count: 0,
  payment_status: 'PENDING',
  payment_method: 'CASH_AGENCY',
}
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })

const fetchMock = vi.fn<typeof fetch>()
let root: Root
let el: HTMLDivElement
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval', 'performance'] })
  vi.setSystemTime(RENDERED)
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
  refresh.mockReset()
})

async function show(initial: Status, code = true, now = RENDERED) {
  await act(async () =>
    root.render(
      <OrderStatus
        id="o1"
        initial={initial}
        now={now}
        places={2}
        timezone="Africa/Casablanca"
        eventHref="/events/12"
        methods={['CARD', 'WALLET']}
        copy={copy}
      >
        {code && <p data-code>JMH 4F7K 29</p>}
      </OrderStatus>,
    ),
  )
}
const advance = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms))
const buttons = () => [...el.querySelectorAll('button')].map((b) => b.textContent)

it('AWAITING_CASH polls every 10 s and swaps to the PAID view', async () => {
  fetchMock.mockResolvedValueOnce(json(CASH)).mockResolvedValueOnce(json({ ...CASH, status: 'PAID', payment_status: 'SUCCEEDED', ticket_count: 2 }))
  await show(CASH)
  expect(el.querySelector('[data-code]')).not.toBeNull()
  expect(buttons()).toEqual([d.orderPage.payCARD, d.orderPage.payWALLET])

  await advance(9_999)
  expect(fetchMock).not.toHaveBeenCalled()
  await advance(1)
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock).toHaveBeenCalledWith('/api/orders/o1', expect.objectContaining({ cache: 'no-store' }))
  expect(el.querySelector('[data-code]')).not.toBeNull()

  await advance(10_000)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(el.querySelector('[data-code]')).toBeNull()
  expect(el.querySelector('h1')!.textContent).toBe(d.orderPage.paidTitle)
  expect(el.querySelector('a[href="/me/tickets"]')).not.toBeNull()
  await advance(60_000)
  expect(fetchMock).toHaveBeenCalledTimes(2) // final: polling stopped
})

it('card / wallet are hidden while a CARD attempt is PENDING, and come back once it FAILED', async () => {
  fetchMock.mockResolvedValueOnce(json({ ...CASH, payment_method: 'CARD', payment_status: 'FAILED' }))
  await show({ ...CASH, payment_method: 'CARD' })
  expect(buttons()).toEqual([])
  expect(el.textContent).toContain(d.orderPage.onlinePending)
  await advance(10_000)
  expect(buttons()).toEqual([d.orderPage.payCARD, d.orderPage.payWALLET])
})

it('the countdown runs to reserved_until, then EXPIRED shows "Code expiré" and the way back', async () => {
  fetchMock.mockResolvedValue(json({ ...CASH, status: 'EXPIRED' }))
  await show({ ...CASH, reserved_until: '2026-02-01T12:00:30Z' })
  expect(el.textContent).toContain('(0 min 30 s restantes)')
  await advance(10_000)
  expect(el.querySelector('h1')!.textContent).toBe(d.orderPage.cashExpiredTitle)
  expect(el.querySelector('a[href="/events/12"]')).not.toBeNull()
  expect(buttons()).toEqual([])
})

it('an order that turns AWAITING_CASH since the server render refreshes the server part once, not on every poll', async () => {
  fetchMock.mockResolvedValue(json(CASH))
  await show({ ...CASH, status: 'RESERVED', payment_status: null, payment_method: null }, false)
  await advance(3_000)
  expect(refresh).toHaveBeenCalledTimes(1)
  await advance(30_000) // more polls at 10 s, still AWAITING_CASH
  expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(3)
  expect(refresh).toHaveBeenCalledTimes(1)
})

it('AWAITING_CASH without a payable code (cash null, code payment FAILED): says so and offers card / wallet', async () => {
  fetchMock.mockResolvedValue(json({ ...CASH, payment_status: 'FAILED' }))
  await show({ ...CASH, payment_status: 'FAILED' }, false)
  expect(el.querySelector('h1')!.textContent).toBe(d.orderPage.awaitingTitle)
  expect(el.textContent).toContain(d.orderPage.noCode)
  expect(buttons()).toEqual([d.orderPage.payCARD, d.orderPage.payWALLET])
  await advance(10_000)
  expect(refresh).not.toHaveBeenCalled() // already AWAITING_CASH at the server render
})

it('the countdown runs on the server clock plus the page clock: a device clock 2 min fast changes nothing', async () => {
  vi.setSystemTime(RENDERED + 120_000)
  const order = { ...CASH, reserved_until: '2026-02-01T12:01:30Z' }
  fetchMock.mockResolvedValue(json(order))
  await show(order)
  expect(el.textContent).toContain('(1 min 30 s restantes)')
  await advance(1_000)
  expect(el.textContent).toContain('(1 min 29 s restantes)')
  await advance(10_000) // a poll re-runs the effect: the page clock is not restarted
  expect(el.textContent).toContain('(1 min 19 s restantes)')
})

it('a new server render (router.refresh) re-anchors the page clock: time is not counted twice', async () => {
  const order = { ...CASH, reserved_until: '2026-02-01T12:01:30Z' }
  fetchMock.mockResolvedValue(json(order))
  await show(order)
  await advance(5_000)
  expect(el.textContent).toContain('(1 min 25 s restantes)')
  await show(order, true, RENDERED + 5_000) // the refreshed server part, rendered 5 s later
  expect(el.textContent).toContain('(1 min 25 s restantes)')
  await advance(1_000)
  expect(el.textContent).toContain('(1 min 24 s restantes)')
})

it('one live region, kept across the cash and general layouts, announces PAID', async () => {
  fetchMock.mockResolvedValueOnce(json({ ...CASH, status: 'PAID', payment_status: 'SUCCEEDED', ticket_count: 2 }))
  await show(CASH)
  const live = el.querySelector('[role="status"]')!
  expect(el.querySelectorAll('[aria-live], [role="status"]')).toHaveLength(1)
  await advance(10_000)
  expect(el.querySelector('[role="status"]')).toBe(live)
  expect(live.textContent).toBe(`${d.orderPage.statusLabel} ${d.orderPage.PAID}`)
})

it('"Payer par carte" posts the method and follows the redirect', async () => {
  const assign = vi.fn()
  vi.stubGlobal('location', { ...window.location, assign })
  fetchMock.mockResolvedValueOnce(json({ redirect_url: 'https://api.test/v1/payments/card/p2/redirect' }))
  await show(CASH)
  const card = [...el.querySelectorAll('button')].find((b) => b.textContent === d.orderPage.payCARD)!
  await act(async () => card.click())
  expect(fetchMock).toHaveBeenCalledWith('/api/orders/o1/pay', expect.objectContaining({ method: 'POST', body: JSON.stringify({ method: 'CARD' }) }))
  expect(assign).toHaveBeenCalledWith('https://api.test/v1/payments/card/p2/redirect')
})

describe('CopyCode', () => {
  const show = () => act(async () => root.render(<CopyCode code="JMH 4F7K 29" label="Copier" done="Copié" failed="Échec" />))
  const click = () => act(async () => el.querySelector('button')!.click())

  it('copies the grouped code; the label stays, the status says it, then clears', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    await show()
    await click()
    expect(writeText).toHaveBeenCalledWith('JMH 4F7K 29')
    expect(el.querySelector('button')!.textContent).toBe('Copier')
    expect(el.querySelector('[role="status"]')!.textContent).toBe('Copié')
    await advance(2_000)
    expect(el.querySelector('[role="status"]')!.textContent).toBe('')
  })

  it('says when copying failed', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    await show()
    await click()
    expect(el.querySelector('[role="status"]')!.textContent).toBe('Échec')
  })
})
