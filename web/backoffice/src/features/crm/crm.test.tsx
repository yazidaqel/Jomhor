import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import i18n from '@/i18n'
import { fr } from '@/i18n/fr'
import { routes } from '@/app/routes'
import type { Me, Role } from '@/features/auth/organizer'
import type { EventSummary } from '@/features/events/api'
import { createQueryClient } from '@/lib/queryClient'
import { session } from '@/lib/session'
import { CAMPAIGN_REFETCH_MS, type Campaign, type CampaignRow, type MembersSummary } from './api'

beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  session.clear()
})

const CASA = 'Africa/Casablanca'
const PARIS = 'Europe/Paris'
const me = (role: Role): Me => ({
  user: { id: 'u-1', phone: '+212600000001', display_name: 'Samira', locale: 'fr' },
  memberships: [{ organizer_id: 'org-a', organizer_name: 'Atlas FC', role }],
})
const event = (id: number, title: string, status: EventSummary['status'], timezone = CASA): EventSummary => ({
  id,
  title,
  event_type: 'FOOTBALL_MATCH',
  starts_at: '2026-11-20T19:00:00Z',
  status,
  venue: { id: `v-${id}`, name: 'Grand Stade', timezone },
  quota: 40000,
  sold: 100,
})
const EVENTS = [event(1, 'Atlas FC – Raja', 'ON_SALE'), event(2, 'Match amical', 'DRAFT'), event(3, 'Tournée Paris', 'ON_SALE', PARIS)]

const SUMMARY: MembersSummary = {
  members: 1234,
  joined_7d: 56,
  marketing_push: 800,
  marketing_whatsapp: 300,
  reachable_push: 640,
  tiers: [
    { code: 'MEMBRE', name: 'Membre', members: 1000 },
    { code: 'FIDELE', name: 'Fidèle', members: 200 },
    { code: 'ULTRA', name: 'Ultra', members: 34 },
  ],
}

const row = (over: Partial<CampaignRow>): CampaignRow => ({
  id: 'c-x',
  channel: 'PUSH',
  audience: 'MEMBERS',
  event_id: null,
  title: 'Titre',
  status: 'DRAFT',
  scheduled_at: null,
  started_at: null,
  finished_at: null,
  created_by: { id: 's-1', name: 'Samira' },
  recipients: 0,
  sent: 0,
  failed: 0,
  ...over,
})
const SENT = row({
  id: 'c-1',
  channel: 'WHATSAPP',
  audience: 'EVENT_HOLDERS',
  event_id: 1,
  title: 'Ouverture des portes à 18 h',
  status: 'SENT',
  scheduled_at: '2026-11-08T17:00:00Z',
  started_at: '2026-11-08T17:00:10Z',
  finished_at: '2026-11-08T17:30:00Z',
  recipients: 500,
  sent: 490,
  failed: 10,
})
const SCHEDULED = row({ id: 'c-2', event_id: 3, title: 'Billets en vente', status: 'SCHEDULED', scheduled_at: '2026-11-15T19:00:00Z' })
const DRAFT = row({ id: 'c-3', title: 'Brouillon du jeudi' })
const DRAFT_PARIS = row({ id: 'c-4', title: 'Concert à Paris', event_id: 3 })
const CAMPAIGNS = [SCHEDULED, SENT, DRAFT, DRAFT_PARIS]
const detail = (r: CampaignRow, audience_estimate = 742): Campaign => ({ ...r, body: 'Le texte du message', audience_estimate })

type Call = { method: string; path: string; org: string | null; body: unknown }
type Handler = (c: Call, n: number) => Response | undefined

function stubApi(role: Role, { campaigns = () => CAMPAIGNS, handle }: { campaigns?: (n: number) => CampaignRow[]; handle?: Handler } = {}) {
  const calls: Call[] = []
  let listed = 0
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const path = String(input).replace(/^\/v1/, '')
      const method = init?.method ?? 'GET'
      const call = { method, path, org: new Headers(init?.headers).get('X-Organizer-Id'), body: init?.body ? JSON.parse(String(init.body)) : undefined }
      calls.push(call)
      const custom = handle?.(call, calls.filter((c) => c.method === method && c.path === path).length)
      if (custom) return custom
      if (path === '/org/me') return Response.json(me(role))
      if (path === '/org/events') return Response.json(EVENTS)
      if (path === '/org/members/summary') return Response.json(SUMMARY)
      if (path === '/org/campaigns' && method === 'GET') return Response.json(campaigns(++listed))
      const one = /^\/org\/campaigns\/([^/]+)$/.exec(path)
      if (one && method === 'GET') {
        const r = CAMPAIGNS.find((c) => c.id === one[1])
        return r ? Response.json(detail(r)) : Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
      }
      return Response.json({ code: 'NOT_FOUND', message: 'x', details: {} }, { status: 404 })
    }),
  )
  return calls
}

function renderApp(path: string) {
  session.start('tok-1', '2999-01-01T00:00:00Z')
  const queryClient = createQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}

const nav = () => within(screen.getByRole('navigation', { name: fr.nav.label }))
const listCalls = (calls: Call[]) => calls.filter((c) => c.method === 'GET' && c.path === '/org/campaigns').length
const writes = (calls: Call[]) => calls.filter((c) => c.method !== 'GET')
const at = (iso: string, zone: string) => new Intl.DateTimeFormat('fr', { timeZone: zone, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))
/** UTC instant of a wall time in `zone`, through Intl's "GMT+01:00" offset name (as events.test). */
function expectedUtc(wall: string, zone: string) {
  const naive = Date.parse(`${wall}:00Z`)
  const name = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'longOffset' }).formatToParts(naive).find((p) => p.type === 'timeZoneName')!.value
  const m = /GMT([+-])(\d{2}):(\d{2})/.exec(name)
  const minutes = m ? (m[1] === '-' ? -1 : 1) * (Number(m[2]) * 60 + Number(m[3])) : 0
  return new Date(naive - minutes * 60_000).toISOString()
}
const rowOf = (title: string) => screen.getByRole('cell', { name: title }).closest('tr') as HTMLElement
const cells = (tr: HTMLElement) => within(tr).getAllByRole('cell').map((c) => c.textContent)

async function openCampaigns(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('tab', { name: fr.crm.tabs.campaigns }))
  await screen.findByRole('cell', { name: SENT.title })
}

async function openComposer(user: ReturnType<typeof userEvent.setup>) {
  await openCampaigns(user)
  await user.click(screen.getByRole('button', { name: fr.crm.campaigns.new }))
  return within(await screen.findByRole('dialog'))
}

async function pick(user: ReturnType<typeof userEvent.setup>, dialog: ReturnType<typeof within>, label: string, option: string | RegExp) {
  await user.click(dialog.getByRole('combobox', { name: label }))
  await user.click(await screen.findByRole('option', { name: option }))
}

async function fill(user: ReturnType<typeof userEvent.setup>, dialog: ReturnType<typeof within>, title: string, body: string) {
  await user.click(dialog.getByLabelText(fr.crm.composer.titleLabel))
  await user.paste(title)
  await user.click(dialog.getByLabelText(fr.crm.composer.bodyLabel))
  await user.paste(body)
}

it.each(['ADMIN', 'OPS', 'FINANCE'] as const)('%s: "Fans · CRM" is a live nav entry to /crm', async (role) => {
  const user = userEvent.setup()
  stubApi(role)
  const { router } = renderApp('/events')
  await user.click(await screen.findByRole('link', { name: fr.nav.crm }))
  expect(router.state.location.pathname).toBe('/crm')
  expect(nav().getByRole('link', { name: fr.nav.crm }).getAttribute('aria-current')).toBe('page')
  expect(await screen.findByRole('heading', { level: 1, name: fr.crm.title })).toBeTruthy()
})

it('CASHIER has no CRM entry and lands on the Guichet', async () => {
  const calls = stubApi('CASHIER')
  const { router } = renderApp('/crm')
  await waitFor(() => expect(router.state.location.pathname).toBe('/pos'))
  await screen.findByRole('navigation', { name: fr.nav.label })
  expect(nav().queryByRole('link', { name: fr.nav.crm })).toBeNull()
  expect(calls.filter((c) => c.path.startsWith('/org/campaigns') || c.path.startsWith('/org/members'))).toEqual([])
})

it('Membres: the KPIs and the tier table from the summary, counts only (no name, no phone)', async () => {
  stubApi('OPS')
  renderApp('/crm')
  const panel = await screen.findByRole('tabpanel', { name: fr.crm.tabs.members })
  const kpi = (label: string) => within(within(panel).getByText(label, { selector: 'span' }).closest('[data-slot="card"]') as HTMLElement)
  const n = (v: number) => v.toLocaleString('fr').replace(/\s/gu, ' ')
  await within(panel).findByText(fr.crm.members.kpi.members, { selector: 'span' })
  const text = (el: HTMLElement) => el.textContent!.replace(/\s/gu, ' ')
  expect(text(kpi(fr.crm.members.kpi.members).getByText(/1.234/u))).toBe(n(1234))
  expect(kpi(fr.crm.members.kpi.joined7d).getByText('56')).toBeTruthy()
  expect(kpi(fr.crm.members.kpi.push).getByText('800')).toBeTruthy()
  expect(kpi(fr.crm.members.kpi.push).getByText(i18n.t('crm.members.kpi.reachable', { n: '640' }))).toBeTruthy()
  expect(kpi(fr.crm.members.kpi.whatsapp).getByText('300')).toBeTruthy()

  const rows = within(within(panel).getByRole('table')).getAllByRole('row').slice(1)
  expect(rows.map((r) => cells(r).map((c) => c!.replace(/\s/gu, ' ')))).toEqual([
    ['Membre', n(1000)],
    ['Fidèle', '200'],
    ['Ultra', '34'],
  ])
  // Nothing personal: no phone-like number, not the staff user's name either.
  expect(panel.textContent).not.toMatch(/\+?\d{9,}/)
  expect(panel.textContent).not.toContain('Samira')
})

it('Campagnes: rows with channel, audience, status, the time in the event venue zone and the counts; Annuler only while SCHEDULED', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN')
  renderApp('/crm')
  await openCampaigns(user)

  expect(cells(rowOf(SENT.title)).slice(0, 8)).toEqual([
    SENT.title,
    fr.crm.channels.WHATSAPP,
    `${fr.crm.campaigns.holders} · Atlas FC – Raja`,
    fr.crm.statuses.SENT,
    at(SENT.finished_at!, CASA), // SENT: when it finished
    '500',
    '490',
    '10',
  ])
  // SCHEDULED: the planned time, in its event's venue zone (Paris here).
  expect(cells(rowOf(SCHEDULED.title)).slice(1, 5)).toEqual([fr.crm.channels.PUSH, fr.crm.audiences.MEMBERS, fr.crm.statuses.SCHEDULED, at(SCHEDULED.scheduled_at!, PARIS)])
  expect(cells(rowOf(DRAFT.title))[4]).toBe(fr.crm.campaigns.noDate)
  expect(rowOf(SENT.title).querySelector('td')!.getAttribute('dir')).toBe('auto')
  expect(rowOf(SENT.title).querySelector('bdi')!.textContent).toBe('Atlas FC – Raja') // the event title is isolated

  const cancel = (title: string) => within(rowOf(title)).queryByRole('button', { name: i18n.t('crm.campaigns.cancelLabel', { title }) })
  expect(cancel(SCHEDULED.title)).toBeTruthy()
  expect(cancel(SENT.title)).toBeNull()
  expect(cancel(DRAFT.title)).toBeNull()
  expect(within(rowOf(DRAFT.title)).getByRole('button', { name: i18n.t('crm.campaigns.editLabel', { title: DRAFT.title }) })).toBeTruthy()
  expect(within(rowOf(SENT.title)).queryAllByRole('button')).toEqual([])
})

it('FINANCE: both tabs render, no write button anywhere', async () => {
  const user = userEvent.setup()
  const calls = stubApi('FINANCE')
  renderApp('/crm')
  expect(await screen.findByText(fr.crm.members.tiers.title)).toBeTruthy()
  await openCampaigns(user)
  const panel = screen.getByRole('tabpanel', { name: fr.crm.tabs.campaigns })
  expect(within(panel).queryAllByRole('button')).toEqual([])
  expect(screen.queryByRole('button', { name: fr.crm.campaigns.new })).toBeNull()
  expect(writes(calls)).toEqual([])
})

it('composer: a 61-character title and a 501-character body are refused, nothing is sent', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS')
  renderApp('/crm')
  const dialog = await openComposer(user)
  await fill(user, dialog, 'a'.repeat(61), 'b'.repeat(501))
  expect(dialog.getByText(i18n.t('crm.composer.counter', { count: 61, max: 60 })).className).toContain('text-destructive')
  await user.click(dialog.getByRole('button', { name: fr.crm.composer.save }))
  expect(await dialog.findByText(i18n.t('crm.composer.tooLong', { max: 60 }))).toBeTruthy()
  expect(dialog.getByText(i18n.t('crm.composer.tooLong', { max: 500 }))).toBeTruthy()
  expect(writes(calls)).toEqual([])

  // 60 and 500 after trim go through.
  await user.clear(dialog.getByLabelText(fr.crm.composer.titleLabel))
  await user.paste(`  ${'a'.repeat(60)}  `)
  await user.clear(dialog.getByLabelText(fr.crm.composer.bodyLabel))
  await user.paste('b'.repeat(500))
  await user.click(dialog.getByRole('button', { name: fr.crm.composer.save }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({ channel: 'PUSH', audience: 'MEMBERS', title: 'a'.repeat(60), body: 'b'.repeat(500) })
})

it('composer: EVENT_HOLDERS without an event is refused; the note says service info only; DRAFT events are not offered', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN', {
    handle: (c) => (c.method === 'POST' && c.path === '/org/campaigns' ? Response.json(detail(row({ id: 'c-9', audience: 'EVENT_HOLDERS', event_id: 1 }), 3), { status: 201 }) : undefined),
  })
  renderApp('/crm')
  const dialog = await openComposer(user)
  await pick(user, dialog, fr.crm.composer.audience, fr.crm.audiences.EVENT_HOLDERS)
  expect(dialog.getByText(fr.crm.composer.holdersNote)).toBeTruthy()
  await fill(user, dialog, 'Portes', 'Ouverture à 18 h')
  await user.click(dialog.getByRole('button', { name: fr.crm.composer.save }))
  const error = await dialog.findByText(fr.crm.composer.eventRequired, { selector: '[data-slot="field-error"]' })
  expect(dialog.getByRole('combobox', { name: fr.crm.composer.event }).getAttribute('aria-describedby')).toContain(error.id)
  expect(writes(calls)).toEqual([])

  await user.click(dialog.getByRole('combobox', { name: fr.crm.composer.event }))
  const options = (await screen.findAllByRole('option')).map((o) => o.textContent)
  expect(options.some((o) => o!.includes('Match amical'))).toBe(false)
  expect(options).toHaveLength(2)
  await user.click(screen.getByRole('option', { name: /Atlas FC – Raja/ }))
  await user.click(dialog.getByRole('button', { name: fr.crm.composer.save }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({ channel: 'PUSH', audience: 'EVENT_HOLDERS', event_id: 1, title: 'Portes', body: 'Ouverture à 18 h' })
})

it('composer: Arabic is accepted as typed; title, body and the push preview are dir="auto"', async () => {
  const user = userEvent.setup()
  const calls = stubApi('OPS', {
    handle: (c) => (c.method === 'POST' && c.path === '/org/campaigns' ? Response.json(detail(row({ id: 'c-9' })), { status: 201 }) : undefined),
  })
  renderApp('/crm')
  const dialog = await openComposer(user)
  const title = 'افتتاح الأبواب'
  const body = 'الأبواب كتحل مع 6 ديال العشية'
  await fill(user, dialog, title, body)
  expect(dialog.getByLabelText(fr.crm.composer.titleLabel).getAttribute('dir')).toBe('auto')
  expect(dialog.getByLabelText(fr.crm.composer.bodyLabel).getAttribute('dir')).toBe('auto')
  const preview = within(dialog.getByRole('figure', { name: fr.crm.composer.preview }))
  expect(preview.getByText(title).getAttribute('dir')).toBe('auto')
  expect(preview.getByText(body).getAttribute('dir')).toBe('auto')
  expect(preview.getByText('Atlas FC')).toBeTruthy()

  await user.click(dialog.getByRole('button', { name: fr.crm.composer.save }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  expect(writes(calls)[0]!.body).toEqual({ channel: 'PUSH', audience: 'MEMBERS', title, body })
})

it('save → POST → the estimate from the answer; "Envoyer maintenant" confirms with a fresh GET estimate → /send {}', async () => {
  const user = userEvent.setup()
  const created = row({ id: 'c-9', title: 'Bienvenue' })
  const calls = stubApi('OPS', {
    handle: (c, n) => {
      if (c.method === 'POST' && c.path === '/org/campaigns') return Response.json(detail(created, 742), { status: 201 })
      // The estimate moves between save and send: the confirm shows the latest one.
      if (c.method === 'GET' && c.path === '/org/campaigns/c-9') return Response.json(detail(created, n === 1 ? 742 : 745))
      if (c.method === 'POST' && c.path === '/org/campaigns/c-9/send') return Response.json({ ...detail(created, 745), status: 'SCHEDULED' })
      return undefined
    },
  })
  renderApp('/crm')
  const dialog = await openComposer(user)
  await fill(user, dialog, 'Bienvenue', 'Merci de nous suivre')
  await user.click(dialog.getByRole('button', { name: fr.crm.composer.save }))
  expect(await dialog.findByText(i18n.t('crm.composer.estimate', { count: 742 }))).toBeTruthy()
  expect(writes(calls)[0]).toMatchObject({ method: 'POST', path: '/org/campaigns', org: 'org-a' })

  await waitFor(() => expect(dialog.getByRole('button', { name: fr.crm.composer.sendNow })).toHaveProperty('disabled', false))
  const gets = () => calls.filter((c) => c.path === '/org/campaigns/c-9').length
  await waitFor(() => expect(gets()).toBe(1)) // the detail query, mounted after the save
  await user.click(dialog.getByRole('button', { name: fr.crm.composer.sendNow }))
  const confirm = within(await screen.findByRole('alertdialog'))
  expect(confirm.getByRole('heading', { name: 'Envoyer à 745 fans ?' })).toBeTruthy()
  expect(gets()).toBe(2)
  expect(writes(calls)).toHaveLength(1)

  await user.click(confirm.getByRole('button', { name: fr.crm.composer.confirm }))
  await waitFor(() => expect(writes(calls)).toHaveLength(2))
  expect(writes(calls)[1]).toEqual({ method: 'POST', path: '/org/campaigns/c-9/send', org: 'org-a', body: {} })
  expect(await screen.findByText(fr.crm.composer.sent)).toBeTruthy()
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
})

it('Programmer: 20 Nov 2026 20:00 in Africa/Casablanca is sent as its exact UTC instant; past and > 30 days are refused', async () => {
  const user = userEvent.setup()
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-11-09T12:00:00Z'))
  const calls = stubApi('ADMIN', {
    handle: (c) => (c.method === 'POST' && c.path === '/org/campaigns/c-3/send' ? Response.json({ ...detail(DRAFT), status: 'SCHEDULED' }) : undefined),
  })
  renderApp('/crm')
  await openCampaigns(user)
  await user.click(screen.getByRole('button', { name: i18n.t('crm.campaigns.editLabel', { title: DRAFT.title }) }))
  const dialog = within(await screen.findByRole('dialog'))
  // A MEMBERS campaign with no event: Africa/Casablanca (Q97).
  expect(await dialog.findByText(i18n.t('crm.schedule.hint', { zone: CASA }))).toBeTruthy()
  expect((dialog.getByLabelText(fr.crm.composer.titleLabel) as HTMLInputElement).value).toBe(DRAFT.title)
  const input = dialog.getByLabelText(fr.crm.schedule.label)
  expect(input.getAttribute('type')).toBe('datetime-local')
  const submit = dialog.getByRole('button', { name: fr.crm.schedule.submit })

  fireEvent.change(input, { target: { value: '2026-11-09T11:00' } }) // at most 11:00Z (+00:00 or +01:00), before "now"
  await user.click(submit)
  expect(await dialog.findByText(fr.crm.schedule.past)).toBeTruthy()
  fireEvent.change(input, { target: { value: '2026-12-09T13:30' } }) // at least 30 days and 30 min ahead
  await user.click(submit)
  expect(await dialog.findByText(fr.crm.schedule.tooFar)).toBeTruthy()
  expect(writes(calls)).toEqual([])

  fireEvent.change(input, { target: { value: '2026-11-20T20:00' } })
  await user.click(submit)
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  // tzdata 2026c: Morocco is on permanent +00:00 from 2026-09-20 (+01:00 before): 20:00Z. Derived from Intl's offset name,
  // not from the code under test, so an older tzdata (19:00Z) checks the same way.
  const expected = expectedUtc('2026-11-20T20:00', CASA)
  expect(expected).toMatch(/^2026-11-20T(19|20):00:00\.000Z$/)
  expect(writes(calls)[0]).toEqual({ method: 'POST', path: '/org/campaigns/c-3/send', org: 'org-a', body: { scheduled_at: expected } })
  expect(await screen.findByText(i18n.t('crm.schedule.done', { date: at(expected, CASA) }))).toBeTruthy()
})

it('NOT_CONFIGURED on save: the error sits on the channel field', async () => {
  const user = userEvent.setup()
  stubApi('OPS', {
    handle: (c) =>
      c.method === 'POST' && c.path === '/org/campaigns'
        ? Response.json({ code: 'VALIDATION_FAILED', message: 'x', details: { fields: { channel: 'not configured' }, reason: 'NOT_CONFIGURED' } }, { status: 422 })
        : undefined,
  })
  renderApp('/crm')
  const dialog = await openComposer(user)
  await fill(user, dialog, 'Bienvenue', 'Merci')
  await user.click(dialog.getByRole('button', { name: fr.crm.composer.save }))
  const error = await dialog.findByText(fr.crm.composer.notConfigured)
  const channel = dialog.getByRole('combobox', { name: fr.crm.composer.channel })
  expect(channel.getAttribute('aria-invalid')).toBe('true')
  expect(channel.getAttribute('aria-describedby')).toBe(error.id)
  expect(dialog.queryByText(fr.crm.composer.estimate_other, { exact: false })).toBeNull()
})

it('Annuler a SCHEDULED campaign → /cancel; a 409 CAMPAIGN_STATUS says so and refetches the list', async () => {
  const user = userEvent.setup()
  let status = 409
  const calls = stubApi('OPS', {
    handle: (c) =>
      c.path === '/org/campaigns/c-2/cancel'
        ? status === 409
          ? Response.json({ code: 'CAMPAIGN_STATUS', message: 'x', details: { status: 'SENDING' } }, { status: 409 })
          : Response.json({ ...detail(SCHEDULED), status: 'CANCELLED' })
        : undefined,
  })
  renderApp('/crm')
  await openCampaigns(user)
  const before = listCalls(calls)
  await user.click(screen.getByRole('button', { name: i18n.t('crm.campaigns.cancelLabel', { title: SCHEDULED.title }) }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.crm.campaigns.cancelConfirm }))
  expect(await screen.findByText(fr.errors.CAMPAIGN_STATUS)).toBeTruthy()
  await waitFor(() => expect(listCalls(calls)).toBe(before + 1))
  await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())

  status = 200
  await user.click(screen.getByRole('button', { name: i18n.t('crm.campaigns.cancelLabel', { title: SCHEDULED.title }) }))
  await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: fr.crm.campaigns.cancelConfirm }))
  expect(await screen.findByText(fr.crm.campaigns.cancelled)).toBeTruthy()
  expect(writes(calls).map((c) => c.path)).toEqual(['/org/campaigns/c-2/cancel', '/org/campaigns/c-2/cancel'])
})

it(`refetches the list every ${CAMPAIGN_REFETCH_MS} ms only while a campaign is SENDING`, async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  const sending = row({ id: 'c-5', title: 'En route', status: 'SENDING', recipients: 100, sent: 40 })
  const calls = stubApi('FINANCE', { campaigns: (n) => (n < 3 ? [sending] : [{ ...sending, status: 'SENT', sent: 100, finished_at: '2026-11-09T12:00:00Z' }]) })
  renderApp('/crm')
  fireEvent.mouseDown(await screen.findByRole('tab', { name: fr.crm.tabs.campaigns }))
  await screen.findByText(fr.crm.statuses.SENDING)
  expect(listCalls(calls)).toBe(1)

  await act(() => vi.advanceTimersByTimeAsync(CAMPAIGN_REFETCH_MS - 1))
  expect(listCalls(calls)).toBe(1)
  await act(() => vi.advanceTimersByTimeAsync(1))
  await waitFor(() => expect(listCalls(calls)).toBe(2))
  await act(() => vi.advanceTimersByTimeAsync(CAMPAIGN_REFETCH_MS))
  await screen.findByText(fr.crm.statuses.SENT)
  expect(listCalls(calls)).toBe(3)

  // SENT: no more polling.
  await act(() => vi.advanceTimersByTimeAsync(5 * CAMPAIGN_REFETCH_MS))
  expect(listCalls(calls)).toBe(3)
})

it('no SENDING row: no polling at all', async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  const calls = stubApi('ADMIN')
  renderApp('/crm')
  fireEvent.mouseDown(await screen.findByRole('tab', { name: fr.crm.tabs.campaigns }))
  await screen.findByRole('cell', { name: SENT.title })
  await act(() => vi.advanceTimersByTimeAsync(3 * CAMPAIGN_REFETCH_MS))
  expect(listCalls(calls)).toBe(1)
})

it('every CRM query key starts with org, <organizer>, and requests carry the same X-Organizer-Id', async () => {
  const user = userEvent.setup()
  const calls = stubApi('ADMIN')
  const { queryClient } = renderApp('/crm')
  await screen.findByText(fr.crm.members.tiers.title)
  await openCampaigns(user)
  await user.click(screen.getByRole('button', { name: i18n.t('crm.campaigns.editLabel', { title: DRAFT.title }) }))
  await within(await screen.findByRole('dialog')).findByText(i18n.t('crm.composer.estimate', { count: 742 }))

  const keys = queryClient
    .getQueryCache()
    .getAll()
    .map((q) => q.queryKey)
  expect(keys).toEqual(
    expect.arrayContaining([
      ['org', 'org-a', 'members', 'summary'],
      ['org', 'org-a', 'campaigns'],
      ['org', 'org-a', 'campaigns', 'c-3'],
      ['org', 'org-a', 'events'],
    ]),
  )
  expect(keys.filter((k) => k[0] !== 'org')).toEqual([['me']])
  for (const key of keys.filter((k) => k[0] === 'org')) expect(key[1]).toBe('org-a')
  for (const c of calls.filter((c) => c.path !== '/org/me')) expect(c.org).toBe('org-a')
})

const ok = (r: CampaignRow) => (c: Call) =>
  c.method === 'POST' && c.path === `/org/campaigns/${r.id}/send` ? Response.json({ ...detail(r), status: 'SCHEDULED' }) : undefined

async function openDraft(user: ReturnType<typeof userEvent.setup>, r: CampaignRow) {
  await openCampaigns(user)
  await user.click(screen.getByRole('button', { name: i18n.t('crm.campaigns.editLabel', { title: r.title }) }))
  const dialog = within(await screen.findByRole('dialog'))
  await dialog.findByText(i18n.t('crm.composer.estimate', { count: 742 }))
  return dialog
}

it('Programmer on a draft linked to a Paris-venue event: the Paris wall time is sent as its UTC instant', async () => {
  const user = userEvent.setup()
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-11-09T12:00:00Z'))
  const calls = stubApi('OPS', { handle: ok(DRAFT_PARIS) })
  renderApp('/crm')
  const dialog = await openDraft(user, DRAFT_PARIS)
  expect(dialog.getByText(i18n.t('crm.schedule.hint', { zone: PARIS }))).toBeTruthy()
  fireEvent.change(dialog.getByLabelText(fr.crm.schedule.label), { target: { value: '2026-11-20T20:00' } })
  await user.click(dialog.getByRole('button', { name: fr.crm.schedule.submit }))
  await waitFor(() => expect(writes(calls)).toHaveLength(1))
  const expected = expectedUtc('2026-11-20T20:00', PARIS)
  expect(expected).toBe('2026-11-20T19:00:00.000Z') // CET, +01:00
  expect(writes(calls)[0]!.body).toEqual({ scheduled_at: expected })
})

it('a saved draft with an event: the event can be changed, not removed (Q101); a new campaign may have none', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN')
  renderApp('/crm')
  const dialog = await openDraft(user, DRAFT_PARIS)
  const select = dialog.getByRole('combobox', { name: fr.crm.composer.eventOptional })
  const locked = dialog.getByText(fr.crm.composer.eventLocked)
  expect(select.getAttribute('aria-describedby')).toContain(locked.id)
  await user.click(select)
  const options = (await screen.findAllByRole('option')).map((o) => o.textContent)
  expect(options).not.toContain(fr.crm.composer.noEvent)
  expect(options).toHaveLength(2)
  await user.keyboard('{Escape}')
  await user.click(dialog.getByRole('button', { name: fr.common.close }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

  await user.click(screen.getByRole('button', { name: fr.crm.campaigns.new }))
  const fresh = within(await screen.findByRole('dialog'))
  expect(fresh.queryByText(fr.crm.composer.eventLocked)).toBeNull()
  await user.click(fresh.getByRole('combobox', { name: fr.crm.composer.eventOptional }))
  expect(await screen.findByRole('option', { name: fr.crm.composer.noEvent })).toBeTruthy()
})

it('"Envoyer maintenant" when the fresh GET fails: the error, no confirm on the old estimate', async () => {
  const user = userEvent.setup()
  let fail = false
  const calls = stubApi('OPS', {
    handle: (c) =>
      fail && c.method === 'GET' && c.path === '/org/campaigns/c-3' ? Response.json({ code: 'FORBIDDEN', message: 'x', details: {} }, { status: 403 }) : undefined,
  })
  renderApp('/crm')
  const dialog = await openDraft(user, DRAFT)
  fail = true
  await user.click(dialog.getByRole('button', { name: fr.crm.composer.sendNow }))
  expect(await screen.findByText(fr.errors.FORBIDDEN)).toBeTruthy()
  expect(screen.queryByRole('alertdialog')).toBeNull()
  expect(writes(calls)).toEqual([])
})

it('events not loaded: no time in a guessed zone, and Programmer waits for the event zone', async () => {
  const user = userEvent.setup()
  stubApi('ADMIN', { handle: (c) => (c.path === '/org/events' ? Response.json({ code: 'FORBIDDEN', message: 'x', details: {} }, { status: 403 }) : undefined) })
  renderApp('/crm')
  await openCampaigns(user)
  expect(cells(rowOf(SCHEDULED.title))[4]).toBe(fr.crm.campaigns.noDate) // event 3's zone is unknown
  expect(cells(rowOf(SENT.title))[4]).toBe(fr.crm.campaigns.noDate)

  await user.click(screen.getByRole('button', { name: i18n.t('crm.campaigns.editLabel', { title: DRAFT_PARIS.title }) }))
  const dialog = within(await screen.findByRole('dialog'))
  await dialog.findByText(i18n.t('crm.composer.estimate', { count: 742 }))
  expect(dialog.getByRole('button', { name: fr.crm.schedule.submit })).toHaveProperty('disabled', true)
  expect(dialog.getByText(fr.errors.FORBIDDEN)).toBeTruthy()
})
