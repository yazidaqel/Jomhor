// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import type { TicketView } from '@/lib/tickets'
import { TicketList } from './TicketList'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const d = getDictionary(defaultLocale)
const copy = { ticketsPage: d.ticketsPage, errors: d.errors, meta: d.meta }

const ticket = (id: string, over: Partial<TicketView> = {}): TicketView => ({
  id,
  ticket_no: id.slice(-9).toUpperCase(),
  status: 'VALID',
  holder_label: 'Yazid A.',
  seat_label: null,
  event: { title: `Match ${id}`, status: 'ON_SALE', starts_at: '2026-02-07T19:00:00Z', timezone: 'Africa/Casablanca', venue: { name: 'Stade', city: 'Rabat' } },
  zone: { code: 'NORD', name: 'Tribune Nord' },
  gate_codes: ['P10', 'P4'],
  past: false,
  ...over,
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

async function show() {
  await act(async () => root.render(<TicketList copy={copy} />))
}
const pdfLinks = () => [...el.querySelectorAll<HTMLAnchorElement>('a[download]')].map((a) => a.getAttribute('href'))
const button = (text: string) => [...el.querySelectorAll('button')].find((b) => b.textContent === text)

it('upcoming then past; a PDF link only for VALID tickets', async () => {
  fetchMock.mockResolvedValue(
    json(200, {
      tickets: [
        ticket('t-up-valid'),
        ticket('t-up-blocked', { status: 'BLOCKED' }),
        ticket('t-past-valid', { past: true }),
        ticket('t-past-refunded', { status: 'REFUNDED', past: true }),
      ],
    }),
  )
  await show()

  expect(fetchMock).toHaveBeenCalledWith('/api/tickets', expect.objectContaining({ cache: 'no-store' }))
  const groups = [...el.querySelectorAll('section')].map((s) => ({
    title: s.querySelector('h2')!.textContent,
    events: [...s.querySelectorAll('li p:first-child')].map((p) => p.textContent),
  }))
  expect(groups).toEqual([
    { title: d.ticketsPage.upcoming, events: ['Match t-up-valid', 'Match t-up-blocked'] },
    { title: d.ticketsPage.past, events: ['Match t-past-valid', 'Match t-past-refunded'] },
  ])
  expect(pdfLinks()).toEqual(['/api/tickets/t-up-valid/pdf', '/api/tickets/t-past-valid/pdf'])
  expect(el.textContent).toContain(d.ticketsPage.BLOCKED)
  expect(el.textContent).toContain('Sam. 7 févr. 2026 · 20:00') // 19:00Z in Africa/Casablanca (UTC+1 outside Ramadan)
  expect(el.querySelector('a[href="jomhor-fan://tickets"]')!.textContent).toBe(d.ticketsPage.openApp)
})

it('no ticket → the empty state, no group', async () => {
  fetchMock.mockResolvedValue(json(200, { tickets: [] }))
  await show()
  expect(el.textContent).toContain(d.ticketsPage.empty)
  expect(el.querySelectorAll('section')).toHaveLength(0)
})

it('401 → the session copy and a login link back here', async () => {
  fetchMock.mockResolvedValue(json(401, { code: 'UNAUTHORIZED', message: '', details: {} }))
  await show()
  expect(el.querySelector('[role="alert"]')!.textContent).toContain(d.errors.UNAUTHORIZED)
  expect(el.querySelector('a')!.getAttribute('href')).toBe('/login?next=%2Fme%2Ftickets')
})

it.each([
  ['a 5xx', () => Promise.resolve(json(502, { code: 'INTERNAL_ERROR', message: '', details: {} }))],
  ['a network failure', () => Promise.reject(new TypeError('fetch failed'))],
])('%s → "Réessayer", which loads again', async (_, fail) => {
  fetchMock.mockImplementationOnce(fail).mockResolvedValueOnce(json(200, { tickets: [ticket('t-1')] }))
  await show()
  expect(el.textContent).toContain(d.ticketsPage.error)
  await act(async () => button(d.ticketsPage.retry)!.click())
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(pdfLinks()).toEqual(['/api/tickets/t-1/pdf'])
})
