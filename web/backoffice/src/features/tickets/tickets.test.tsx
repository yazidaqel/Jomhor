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
import type { EventDetail } from '@/features/events/api'
import { createQueryClient } from '@/lib/queryClient'
import { formatDh } from '@/lib/money'
import { session } from '@/lib/session'
import { lookupQuery, type TicketRow } from './api'

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

const CASA = 'Africa/Casablanca'
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const OPEN_CUTOFF = '2999-11-14T18:00:00Z'
const event = (refund_cutoff_at: string | null = OPEN_CUTOFF) =>
  ({
    id: 1,
    venue_id: 'v-1',
    timezone: CASA,
    series_id: null,
    event_type: 'FOOTBALL_MATCH',
    title: 'Atlas FC vs Rapid Oued',
    starts_at: '2999-11-14T19:00:00Z',
    doors_open_at: null,
    sale_starts_at: null,
    status: 'ON_SALE',
    refund_cutoff_at,
    reentry_policy: 'NONE',
    per_account_limit: 4,
    metadata: {},
    created_at: '2026-11-01T10:00:00Z',
  }) satisfies EventDetail

const row = (over: Partial<TicketRow>): TicketRow => ({
  id: 't-1',
  ticket_no: '4D5C 6B7A',
  status: 'VALID',
  zone: { id: 3, code: 'NORD', name: 'Virage Nord' },
  holder_name: 'Samira Alaoui',
  holder_label: 'Samira A.',
  unit_price_mad: 15_050,
  order_id: '0190f3a2-7c41-7d2e-9b1a-4c3d2e1fabcd',
  channel: 'WEB',
  payment_method: 'CARD',
  issued_at: '2026-11-05T10:00:00Z',
  refund: null,
  ...over,
})
const ROWS: TicketRow[] = [
  row({}),
  row({ id: 't-2', ticket_no: '1A2B 3C4D', status: 'BLOCKED', channel: 'POS', payment_method: 'POS_CASH', holder_label: null }),
  row({ id: 't-3', ticket_no: '9F8E 7D6C', status: 'REFUNDED', channel: 'APP', payment_method: 'CASH_AGENCY', refund: { id: 'r-1', status: 'REQUIRED', amount_mad: 15_050 } }),
]

type Call = { method: string; path: string; org: string | null; body: unknown }

function stubApi(role: Role, opts: { cutoff?: string | null; lookup?: () => Response; write?: (c: Call) => Response } = {}) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const call = {
        method: init?.method ?? 'GET',
        path: String(input).replace(/^\/v1/, ''),
        org: new Headers(init?.headers).get('X-Organizer-Id'),
        body: init?.body ? JSON.parse(String(init.body)) : undefined,
      }
      calls.push(call)
      if (call.path === '/org/me') return Response.json(me(role))
      if (call.path === '/org/events/1') return Response.json(event(opts.cutoff))
      if (call.method === 'POST' && call.path === '/org/events/1/tickets/search') return opts.lookup?.() ?? Response.json(ROWS)
      if (call.method === 'POST' && call.path.startsWith('/org/tickets/')) return opts.write?.(call) ?? Response.json(ROWS[0])
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

function renderApp(path = '/events/1/tickets') {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const spaced = (s: string) => s.replace(/\s+/gu, ' ')
const lookups = (calls: Call[]) => calls.filter((c) => c.path === '/org/events/1/tickets/search')
const writes = (calls: Call[]) => calls.filter((c) => c.method === 'POST' && c.path.startsWith('/org/tickets/')).map(({ method, path, org, body }) => ({ method, path, org, body }))
const rowOf = (ticketNo: string) => screen.getByText(ticketNo).closest('tr') as HTMLElement
const buttons = (r: HTMLElement) => within(r).queryAllByRole('button').map((b) => b.textContent)

async function search(text: string) {
  const user = userEvent.setup()
  await user.type(await screen.findByRole('searchbox', { name: fr.tickets.search.label }), text)
  await user.click(screen.getByRole('button', { name: fr.tickets.search.submit }))
  return user
}

it('search text → q: phones as E.164, ticket numbers and cash codes as typed', () => {
  expect(lookupQuery(' 06 12 34 56 78 ')).toBe('+212612345678')
  expect(lookupQuery('+212 6 12 34 56 78')).toBe('+212612345678')
  expect(lookupQuery(' 4d5c 6b7a ')).toBe('4d5c 6b7a')
  expect(lookupQuery('12345678')).toBe('12345678') // 8 digits: a ticket number, never a phone
  expect(lookupQuery('jmh 4f7k 29')).toBe('jmh 4f7k 29')
  expect(lookupQuery(' +33 6 12-34.56 (78) ')).toBe('+33612345678') // a foreign holder: E.164, separators gone
  expect(lookupQuery('+33 abc')).toBe('+33 abc') // not a number: as typed, the server answers 422
})

it('ADMIN searches by phone: E.164 sent with the organizer, rows with status, refund owed, order, and actions per status', async () => {
  const calls = stubApi('ADMIN')
  renderApp()
  expect(await screen.findByText(fr.tickets.search.prompt)).toBeTruthy()
  expect(lookups(calls)).toHaveLength(0) // nothing searched yet: no call

  await search('06 12 34 56 78')
  await screen.findByText('4D5C 6B7A')
  expect(lookups(calls)).toEqual([{ method: 'POST', path: '/org/events/1/tickets/search', org: 'org-a', body: { q: '+212612345678' } }]) // never in the URL

  const valid = rowOf('4D5C 6B7A')
  expect(within(valid).getByText(fr.tickets.statuses.VALID)).toBeTruthy()
  expect(within(valid).getByText('Samira A.')).toBeTruthy()
  expect(within(valid).getByText(i18n.t('tickets.order', { no: '2E1F ABCD' }))).toBeTruthy() // last 8 hex of the order id, 4-4
  expect(within(valid).getByText(`${fr.sales.channels.names.WEB} · ${fr.sales.methods.CARD}`)).toBeTruthy()
  expect(buttons(valid)).toEqual([fr.tickets.actions.block.action, fr.tickets.actions.refund.action])

  const blocked = rowOf('1A2B 3C4D')
  expect(within(blocked).getByText(fr.tickets.statuses.BLOCKED)).toBeTruthy()
  expect(within(blocked).getByText('Samira Alaoui')).toBeTruthy() // no label (Lot 0 ticket): the name
  expect(within(blocked).getByText(`${fr.sales.channels.names.POS} · ${fr.sales.methods.POS_CASH}`)).toBeTruthy()
  expect(buttons(blocked)).toEqual([fr.tickets.actions.unblock.action, fr.tickets.actions.refund.action])

  const refunded = rowOf('9F8E 7D6C')
  expect(within(refunded).getByText(fr.tickets.statuses.REFUNDED)).toBeTruthy()
  expect(within(refunded).getByText(spaced(i18n.t('tickets.refund.REQUIRED', { amount: formatDh(15_050, 'fr') })))).toBeTruthy()
  expect(i18n.t('tickets.refund.REQUIRED', { amount: formatDh(15_050, 'fr') })).toBe('150,50 DH à rendre')
  expect(buttons(refunded)).toEqual([])
  expect(screen.getByText(spaced(i18n.t('tickets.freezeAt', { date: new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(OPEN_CUTOFF)) })))).toBeTruthy()
})

it('the confirm dialog needs a reason; block then sends it and refetches the list', async () => {
  const calls = stubApi('OPS')
  renderApp()
  const user = await search('4D5C 6B7A')
  await screen.findByText('4D5C 6B7A')
  expect(lookups(calls)[0]!.body).toEqual({ q: '4D5C 6B7A' }) // as typed: the server normalises
  await user.click(screen.getByRole('button', { name: i18n.t('tickets.actions.block.label', { ticket: '4D5C 6B7A' }) }))
  const dialog = await screen.findByRole('dialog', { name: i18n.t('tickets.actions.block.title', { ticket: '4D5C 6B7A' }) })

  await user.click(within(dialog).getByRole('button', { name: fr.tickets.actions.block.confirm }))
  expect(await within(dialog).findByText(fr.tickets.reasonInvalid)).toBeTruthy()
  await user.type(within(dialog).getByRole('textbox', { name: fr.tickets.reason }), '   ')
  await user.click(within(dialog).getByRole('button', { name: fr.tickets.actions.block.confirm }))
  expect(writes(calls)).toEqual([]) // blank reason: nothing sent

  await user.type(within(dialog).getByRole('textbox', { name: fr.tickets.reason }), 'Revente au noir')
  await user.click(within(dialog).getByRole('button', { name: fr.tickets.actions.block.confirm }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/tickets/t-1/block', org: 'org-a', body: { reason: 'Revente au noir' } }])
  expect(await screen.findByText(i18n.t('tickets.actions.block.done', { ticket: '4D5C 6B7A' }))).toBeTruthy()
  await waitFor(() => expect(lookups(calls)).toHaveLength(2)) // refetched after the write
})

it('ADMIN refunds a blocked ticket with a reason', async () => {
  const calls = stubApi('ADMIN')
  renderApp()
  const user = await search('1A2B3C4D')
  await user.click(await screen.findByRole('button', { name: i18n.t('tickets.actions.refund.label', { ticket: '1A2B 3C4D' }) }))
  const dialog = await screen.findByRole('dialog', { name: i18n.t('tickets.actions.refund.title', { ticket: '1A2B 3C4D' }) })
  expect(within(dialog).getByText(fr.tickets.actions.refund.body)).toBeTruthy()
  // The amount owed back, from the row's unit price, before confirming.
  expect(within(dialog).getByText(spaced(i18n.t('tickets.actions.refund.amount', { amount: formatDh(15_050, 'fr') })))).toBeTruthy()
  expect(i18n.t('tickets.actions.refund.amount', { amount: formatDh(15_050, 'fr') })).toMatch(/150,50\sDH$/u)
  await user.type(within(dialog).getByRole('textbox', { name: fr.tickets.reason }), 'Match reporté pour ce fan')
  await user.click(within(dialog).getByRole('button', { name: fr.tickets.actions.refund.confirm }))
  await waitFor(() => expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/tickets/t-2/refund', org: 'org-a', body: { reason: 'Match reporté pour ce fan' } }]))
})

it.each([
  ['block', 'REFUND_FROZEN'],
  ['block', 'TICKET_STATUS'],
  ['refund', 'REFUND_FROZEN'],
  ['refund', 'TICKET_STATUS'],
] as const)('%s answered 409 %s keeps the dialog open with its own copy', async (kind, code) => {
  stubApi('ADMIN', { write: () => Response.json({ code, message: 'x', details: { status: 'REFUNDED' } }, { status: 409 }) })
  renderApp()
  const user = await search('4D5C 6B7A')
  await user.click(await screen.findByRole('button', { name: i18n.t(`tickets.actions.${kind}.label`, { ticket: '4D5C 6B7A' }) }))
  const dialog = await screen.findByRole('dialog')
  await user.type(within(dialog).getByRole('textbox', { name: fr.tickets.reason }), 'Fraude')
  await user.click(within(dialog).getByRole('button', { name: fr.tickets.actions[kind].confirm }))
  expect(await within(dialog).findByText(fr.errors[code])).toBeTruthy()
})

it('OPS unblocks a blocked ticket end to end', async () => {
  const calls = stubApi('OPS', { write: () => Response.json({ ...ROWS[1], status: 'VALID' }) })
  renderApp()
  const user = await search('1A2B 3C4D')
  await user.click(await screen.findByRole('button', { name: i18n.t('tickets.actions.unblock.label', { ticket: '1A2B 3C4D' }) }))
  const dialog = await screen.findByRole('dialog', { name: i18n.t('tickets.actions.unblock.title', { ticket: '1A2B 3C4D' }) })
  await user.click(within(dialog).getByRole('button', { name: fr.tickets.actions.unblock.confirm }))
  expect(await within(dialog).findByText(fr.tickets.reasonInvalid)).toBeTruthy()
  await user.type(within(dialog).getByRole('textbox', { name: fr.tickets.reason }), 'Erreur de saisie')
  await user.click(within(dialog).getByRole('button', { name: fr.tickets.actions.unblock.confirm }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(writes(calls)).toEqual([{ method: 'POST', path: '/org/tickets/t-2/unblock', org: 'org-a', body: { reason: 'Erreur de saisie' } }])
  expect(await screen.findByText(i18n.t('tickets.actions.unblock.done', { ticket: '1A2B 3C4D' }))).toBeTruthy()
  await waitFor(() => expect(lookups(calls)).toHaveLength(2))
})

it('each opening of a dialog starts fresh: no reason or error left from the last one', async () => {
  stubApi('ADMIN', { write: () => Response.json({ code: 'TICKET_STATUS', message: 'x', details: {} }, { status: 409 }) })
  renderApp()
  const user = await search('4D5C 6B7A')
  const open = async () => {
    await user.click(await screen.findByRole('button', { name: i18n.t('tickets.actions.block.label', { ticket: '4D5C 6B7A' }) }))
    return screen.findByRole('dialog')
  }
  let dialog = await open()
  await user.type(within(dialog).getByRole('textbox', { name: fr.tickets.reason }), 'Fraude')
  await user.click(within(dialog).getByRole('button', { name: fr.tickets.actions.block.confirm }))
  await within(dialog).findByText(fr.errors.TICKET_STATUS)
  await user.click(within(dialog).getByRole('button', { name: fr.common.cancel }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

  dialog = await open()
  expect((within(dialog).getByRole('textbox', { name: fr.tickets.reason }) as HTMLInputElement).value).toBe('')
  expect(within(dialog).queryByText(fr.errors.TICKET_STATUS)).toBeNull()
})

it('after refund_cutoff_at every action is disabled, with the freeze notice', async () => {
  stubApi('ADMIN', { cutoff: '2020-01-01T18:00:00Z' })
  renderApp()
  await search('4D5C 6B7A')
  await screen.findByText('4D5C 6B7A')
  const notice = screen.getByText(spaced(i18n.t('tickets.frozen', { date: new Intl.DateTimeFormat('fr', { timeZone: CASA, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse('2020-01-01T18:00:00Z')) })))
  const actions = [...within(rowOf('4D5C 6B7A')).getAllByRole('button'), ...within(rowOf('1A2B 3C4D')).getAllByRole('button')]
  expect(actions).toHaveLength(4)
  const user = userEvent.setup()
  for (const b of actions) {
    // aria-disabled, still focusable, so the notice it points at is reachable by keyboard; a click does nothing.
    expect(b.getAttribute('aria-disabled')).toBe('true')
    expect((b as HTMLButtonElement).disabled).toBe(false)
    expect(b.getAttribute('aria-describedby')).toBe(notice.id)
    await user.click(b)
  }
  expect(screen.queryByRole('dialog')).toBeNull()
})

it('roles: OPS blocks but cannot refund, FINANCE reads only', async () => {
  stubApi('OPS')
  renderApp()
  await search('4D5C 6B7A')
  await screen.findByText('4D5C 6B7A')
  expect(buttons(rowOf('4D5C 6B7A'))).toEqual([fr.tickets.actions.block.action])
  expect(buttons(rowOf('1A2B 3C4D'))).toEqual([fr.tickets.actions.unblock.action])
  cleanup()
  vi.unstubAllGlobals()

  stubApi('FINANCE')
  renderApp()
  expect(await screen.findByText(fr.tickets.search.promptReadOnly)).toBeTruthy()
  await search('4D5C 6B7A')
  await screen.findByText('4D5C 6B7A')
  expect(screen.getByRole('table').querySelectorAll('tbody button')).toHaveLength(0)
  expect(screen.queryByRole('columnheader', { name: fr.tickets.columns.actions })).toBeNull()
})

it('a CASHIER never reaches B12: back to the Guichet', async () => {
  const calls = stubApi('CASHIER')
  const router = renderApp()
  await waitFor(() => expect(router.state.location.pathname).toBe('/pos'))
  expect(lookups(calls)).toHaveLength(0)
})

it('empty input, an unreadable q (422) and no match each have their copy', async () => {
  let answer = () => Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { q: 'INVALID' } } }, { status: 422 })
  const calls = stubApi('ADMIN', { lookup: () => answer() })
  renderApp()
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: fr.tickets.search.submit }))
  expect(await screen.findByText(fr.tickets.search.required)).toBeTruthy()
  expect(lookups(calls)).toHaveLength(0)

  await search('zz')
  expect(await screen.findByText(fr.tickets.search.invalid)).toBeTruthy()

  answer = () => Response.json([])
  const input = screen.getByRole('searchbox', { name: fr.tickets.search.label })
  await user.clear(input)
  await search('0000 0000')
  expect(await screen.findByText(i18n.t('tickets.search.empty', { q: '0000 0000' }))).toBeTruthy()
})

it('the event page links to B12 once published', async () => {
  stubApi('FINANCE')
  const router = renderApp('/events/1')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('link', { name: fr.tickets.title }))
  expect(router.state.location.pathname).toBe('/events/1/tickets')
})
