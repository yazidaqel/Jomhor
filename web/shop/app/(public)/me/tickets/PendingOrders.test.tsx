// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import type { PendingCashOrder } from '@/lib/orders'
import { PendingOrders } from './PendingOrders'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const d = getDictionary(defaultLocale)
const copy = { ticketsPage: d.ticketsPage, meta: d.meta }

const order = (id: string): PendingCashOrder => ({
  id,
  event: { title: `Match ${id}`, starts_at: '2026-02-07T19:00:00Z', timezone: 'Africa/Casablanca' },
  code: 'JMH 4F7K 29',
  amount_mad: 15050,
  reserved_until: '2026-02-06T23:30:00Z',
})
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
})

const show = () => act(async () => root.render(<PendingOrders copy={copy} />))

it('one row per pending cash order: event, code, amount, deadline in the venue zone, linking to /orders/[id]', async () => {
  fetchMock.mockResolvedValue(json(200, { orders: [order('o-1'), order('o-2')] }))
  await show()

  expect(fetchMock).toHaveBeenCalledWith('/api/orders', expect.objectContaining({ cache: 'no-store' }))
  expect(el.querySelector('h2')!.textContent).toBe('Commandes en attente de paiement')
  const links = [...el.querySelectorAll('a')]
  expect(links.map((a) => a.getAttribute('href'))).toEqual(['/orders/o-1', '/orders/o-2'])
  const first = links[0]!
  expect(first.querySelector('h3')!.textContent).toBe('Match o-1')
  expect(first.querySelector('[data-pay-code]')!.textContent).toBe('JMH 4F7K 29')
  expect(first.querySelector('[data-pay-code]')!.getAttribute('dir')).toBe('ltr')
  expect(first.querySelector('[data-amount]')!.textContent).toBe('150,50\u00a0DH')
  // 23:30 UTC is 00:30 the next day in Casablanca (UTC+1 outside Ramadan).
  expect(first.textContent).toContain('À payer avant Sam. 7 févr. 2026 · 00:30')
})

it.each([
  ['no pending order', json(200, { orders: [] })],
  ['an error', json(500, { code: 'INTERNAL_ERROR', message: '', details: {} })],
  ['signed out', json(401, { code: 'UNAUTHORIZED', message: '', details: {} })],
])('hidden on %s', async (_, res) => {
  fetchMock.mockResolvedValue(res)
  await show()
  expect(el.innerHTML).toBe('')
})
