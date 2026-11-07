// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defaultLocale, getDictionary, type Dict } from '@/lib/i18n'
import type { TicketView } from '@/lib/tickets'
import { TicketList } from './TicketList'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const d = getDictionary(defaultLocale)
const copyOf = (x: Dict) => ({
  ticketsPage: x.ticketsPage,
  transfer: x.transfer,
  errors: x.errors,
  meta: x.meta,
  holder: { nameInvalid: x.checkout.nameInvalid, namePlaceholder: x.checkout.namePlaceholder, phoneInvalid: x.checkout.phoneInvalid, phonePlaceholder: x.checkout.phonePlaceholder },
})
const copy = copyOf(d)

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
  transfer_until: '2026-02-07T17:30:00Z',
  transfer_open: true,
  transferred: null,
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

async function show(c = copy) {
  await act(async () => root.render(<TicketList copy={c} />))
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
    events: [...s.querySelectorAll('li h3')].map((h) => h.textContent),
  }))
  expect(groups).toEqual([
    { title: d.ticketsPage.upcoming, events: ['Match t-up-valid', 'Match t-up-blocked'] },
    { title: d.ticketsPage.past, events: ['Match t-past-valid', 'Match t-past-refunded'] },
  ])
  expect(pdfLinks()).toEqual(['/api/tickets/t-up-valid/pdf', '/api/tickets/t-past-valid/pdf'])
  expect(el.textContent).toContain(d.ticketsPage.BLOCKED)
  // 19:00Z in Africa/Casablanca (UTC+1 outside Ramadan): the date on the band, the time as kick-off.
  const first = el.querySelector('article')!
  expect(first.querySelector('header')!.textContent).toContain('Sam. 7 févr. 2026')
  expect(first.querySelector('dl')!.textContent).toContain(`${d.ticketsPage.kickoff}20:00`)
  expect(first.textContent).toContain('P10 · P4')
  expect(first.textContent).toContain(ticket('t-up-valid').ticket_no)
})

it('an upcoming VALID ticket counts down to the start, measured when the list loaded', async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-02-05T17:00:00Z'))
  fetchMock.mockResolvedValue(json(200, { tickets: [ticket('t-1'), ticket('t-2', { event: { ...ticket('t-2').event, starts_at: '2026-02-05T19:30:00Z' } })] }))
  await show()
  vi.useRealTimers()
  const [a, b] = [...el.querySelectorAll('article')]
  expect(a!.textContent).toContain('Dans 2 j 2 h')
  expect(b!.textContent).toContain('Dans 2 h 30 min')
  expect(el.textContent).toContain('2 billets')
})

it('no ticket → the empty state, no group', async () => {
  fetchMock.mockResolvedValue(json(200, { tickets: [] }))
  await show()
  expect(el.textContent).toContain(d.ticketsPage.empty)
  expect(el.querySelectorAll('section')).toHaveLength(0)
  expect(el.querySelector('a[href="/"]')!.textContent).toBe(d.ticketsPage.browse)
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

// jsdom has no modal dialog: open/close as the browser does (close fires `close`).
HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
  this.open = true
}
HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
  this.open = false
  this.dispatchEvent(new Event('close'))
}

const dialog = () => el.querySelector('dialog')
const transferButton = () => el.querySelector<HTMLButtonElement>('article button')!
function typeInto(id: string, value: string) {
  const input = el.querySelector<HTMLInputElement>(`#${id}`)!
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
  input.dispatchEvent(new Event('input', { bubbles: true }))
}
async function click(b: HTMLElement | undefined) {
  await act(async () => b!.click())
}
/** Opens the dialog on the first ticket, fills it and presses "Continuer". */
async function toConfirm(c = copy, phone = '06 12 34 56 78', name = 'Karim  Benali') {
  await click(transferButton())
  expect(dialog()!.open).toBe(true)
  act(() => (typeInto('transfer-phone', phone), typeInto('transfer-name', name)))
  await click(button(c.transfer.next))
}

it('a ticket transferred away is a muted "Transféré à …" row, not a ticket; the count leaves it out', async () => {
  fetchMock.mockResolvedValue(
    json(200, { tickets: [ticket('t-held'), ticket('t-gone', { status: 'CANCELLED', transfer_open: false, transferred: { to_label: 'Karim B.', at: '2026-02-03T10:00:00Z' } })] }),
  )
  await show()
  expect([...el.querySelectorAll('article h3')].map((h) => h.textContent)).toEqual(['Match t-held'])
  const row = el.querySelector('#ticket-row-t-gone')!
  expect(row.textContent).toContain('Match t-gone')
  expect(row.textContent).toContain('Transféré à \u2068Karim B.\u2069 · Mar. 3 févr. 2026')
  expect(row.querySelector('button, a')).toBeNull()
  expect(el.textContent).toContain('1 billet')
})

it('after transfer_until the action is disabled, with "Transferts fermés 1 h 30 min avant le début"', async () => {
  fetchMock.mockResolvedValue(json(200, { tickets: [ticket('t-1', { transfer_open: false })] }))
  await show()
  const b = transferButton()
  expect(b.disabled).toBe(true)
  const hint = el.querySelector(`#${b.getAttribute('aria-describedby')}`)!
  expect(hint.textContent).toBe('Transferts fermés 1 h 30 min avant le début')
})

it('no cutoff (transfer_until null) → disabled with the no-transfer copy; past tickets have no action', async () => {
  fetchMock.mockResolvedValue(json(200, { tickets: [ticket('t-1', { transfer_until: null, transfer_open: false }), ticket('t-2', { past: true })] }))
  await show()
  expect(el.querySelectorAll('article button')).toHaveLength(1)
  expect(transferButton().disabled).toBe(true)
  expect(el.textContent).toContain(d.transfer.unavailable)
})

it('invalid phone or name → field errors, nothing sent', async () => {
  fetchMock.mockResolvedValue(json(200, { tickets: [ticket('t-1')] }))
  await show()
  await toConfirm(copy, '12', 'Karim عقل')
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(el.querySelector('#transfer-phone-error')!.textContent).toBe(d.checkout.phoneInvalid)
  expect(el.querySelector('#transfer-name-error')!.textContent).toBe(d.checkout.nameInvalid)
  expect(el.querySelector('#transfer-phone')!.getAttribute('aria-invalid')).toBe('true')
})

it('form → explicit confirm → POST {to_phone, name} → done; on close the ticket becomes a "Transféré à" row', async () => {
  fetchMock
    .mockResolvedValueOnce(json(200, { tickets: [ticket('t-1')] }))
    .mockResolvedValueOnce(json(201, { to_label: 'Karim B.', transferred_at: '2026-02-05T17:00:00Z' }))
  await show()
  await toConfirm()
  // The confirm step says who gets it and that it is final; nothing is sent yet.
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(dialog()!.textContent).toContain('Ton QR ne marchera plus. \u2068Karim Benali\u2069 reçoit un nouveau billet.')
  expect(dialog()!.textContent).toContain(d.transfer.final)
  await click(button(d.transfer.confirm))

  const [url, init] = fetchMock.mock.calls[1]!
  expect(url).toBe('/api/tickets/t-1/transfer')
  expect(init!.method).toBe('POST')
  expect(JSON.parse(String(init!.body))).toEqual({ to_phone: '+212612345678', name: 'Karim Benali' })
  expect(dialog()!.textContent).toContain(d.transfer.doneTitle)
  expect(dialog()!.textContent).toContain('\u2068Karim B.\u2069 reçoit son billet')

  await click(button(d.transfer.close))
  expect(dialog()).toBeNull()
  expect(el.querySelector('article')).toBeNull()
  const row = el.querySelector('#ticket-row-t-1')!
  expect(row.textContent).toContain('Transféré à \u2068Karim B.\u2069')
  expect(document.activeElement).toBe(row)
})

it.each(['fr', 'ar', 'en'] as const)('%s: 422 SELF → back to the phone field with its copy', async (locale) => {
  const x = getDictionary(locale)
  fetchMock
    .mockResolvedValueOnce(json(200, { tickets: [ticket('t-1')] }))
    .mockResolvedValueOnce(json(422, { code: 'VALIDATION_FAILED', message: '', details: { fields: { to_phone: 'self' }, reason: 'SELF' } }))
  await show(copyOf(x))
  await toConfirm(copyOf(x))
  await click(button(x.transfer.confirm))
  expect(el.querySelector('#transfer-phone-error')!.textContent).toBe(x.transfer.self)
  expect(document.activeElement).toBe(el.querySelector('#transfer-phone'))
})

it.each<[string, number, Record<string, unknown>, string, boolean]>([
  ['TRANSFER_CLOSED (details.transfer_until)', 409, { code: 'TRANSFER_CLOSED', message: '', details: { transfer_until: '2026-02-07T17:00:00Z' } }, 'Transferts fermés 2 h avant le début', false],
  ['TRANSFER_CLOSED without transfer_until', 409, { code: 'TRANSFER_CLOSED', message: '', details: {} }, d.transfer.unavailable, false],
  ['TICKET_NOT_VALID', 409, { code: 'TICKET_NOT_VALID', message: '', details: {} }, d.transfer.notValid, true],
  ['NOT_FOUND', 404, { code: 'NOT_FOUND', message: '', details: {} }, d.errors.NOT_FOUND, true],
  ['a 5xx', 502, { code: 'INTERNAL_ERROR', message: '', details: {} }, d.transfer.unknown, true],
])('%s → its copy in the dialog, then the list follows on close', async (_, status, body, text, reloads) => {
  fetchMock
    .mockResolvedValueOnce(json(200, { tickets: [ticket('t-1')] }))
    .mockResolvedValueOnce(json(status, body))
    .mockResolvedValueOnce(json(200, { tickets: [ticket('t-1', { status: 'BLOCKED' })] }))
  await show()
  await toConfirm()
  await click(button(d.transfer.confirm))
  expect(el.querySelector('dialog [role="alert"]')!.textContent).toBe(text)
  await click(button(d.transfer.close))
  expect(fetchMock).toHaveBeenCalledTimes(reloads ? 3 : 2)
  if (!reloads) {
    expect(transferButton().disabled).toBe(true)
    expect(el.querySelector(`#${transferButton().getAttribute('aria-describedby')}`)!.textContent).toBe(text)
  }
})

it('the dialog closed while the send is in flight (a second Esc in Chromium) → the list reloads', async () => {
  let answer: (r: Response) => void = () => {}
  fetchMock
    .mockResolvedValueOnce(json(200, { tickets: [ticket('t-1')] }))
    .mockImplementationOnce(() => new Promise((r) => (answer = r)))
    .mockResolvedValueOnce(json(200, { tickets: [ticket('t-1', { status: 'CANCELLED', transferred: { to_label: 'Karim B.', at: '2026-02-05T17:00:00Z' } })] }))
  await show()
  await toConfirm()
  await click(button(d.transfer.confirm))
  expect(button(d.transfer.pending)!.disabled).toBe(true)
  await act(async () => dialog()!.close())
  expect(dialog()).toBeNull()
  expect(fetchMock).toHaveBeenCalledTimes(3)
  await act(async () => answer(json(201, { to_label: 'Karim B.', transferred_at: '2026-02-05T17:00:00Z' })))
  expect(el.querySelector('#ticket-row-t-1')!.textContent).toContain('Karim B.')
})

it('"Annuler" closes without sending; "Modifier" goes back to the filled form', async () => {
  fetchMock.mockResolvedValue(json(200, { tickets: [ticket('t-1')] }))
  await show()
  await toConfirm()
  expect(dialog()!.querySelector('dd span[dir="ltr"]')!.textContent).toBe('+212612345678')
  await click(button(d.transfer.edit))
  expect(el.querySelector<HTMLInputElement>('#transfer-name')!.value).toBe('Karim  Benali')
  expect(document.activeElement).toBe(el.querySelector('#transfer-phone'))
  await click(button(d.transfer.cancel))
  expect(dialog()).toBeNull()
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(el.querySelector('article')).not.toBeNull()
})
