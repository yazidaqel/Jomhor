import { z } from 'zod'
import { useOrgMutation, useOrgQuery } from '@/features/auth/organizer'
import { normalizePhone } from '@/features/auth/phone'
import type { Price } from '@/features/events/api'
import { path } from '@/lib/api'
import { parseDh } from '@/lib/money'

// docs/04-api.md §Point of sale and §Cash codes. Every `*_mad` is integer centimes.
export type PosSession = { id: string; label: string; opened_at: string; orders: number; tickets: number; prints: number; collected_mad: number }
export type CodeLookup = {
  order_id: string
  status: string
  payable: boolean
  reserved_until: string | null
  amount_mad: number
  places: number
  event: { id: number; title: string; starts_at: string; timezone: string }
  holders: string[]
}
export type TicketStatus = 'VALID' | 'BLOCKED' | 'REFUNDED' | 'CANCELLED'
export type PrintTicket = {
  id: string
  ticket_no: string
  status: TicketStatus
  zone: { code: string; name: string }
  gate_codes: string[]
  holder_label: string | null
  unit_price_mad: number
  /** The static v1 token, as on the PDF; null unless VALID. Rendered as is, never decoded. */
  qr: string | null
}
export type PrintData = {
  order_id: string
  event: { id: number; title: string; starts_at: string; timezone: string; venue: { name: string } }
  tickets: PrintTicket[]
}

/** The `/org/finance/cash` session shape, which the close answers with: `expected_mad` frozen, `variance_mad = declared − expected`. */
export type ClosedSession = {
  id: string
  label: string
  staff: { id: string; name: string }
  opened_at: string
  closed_at: string
  orders: number
  tickets: number
  prints: number
  expected_mad: number
  declared_mad: number
  variance_mad: number
}
export type SaleItem = ReturnType<typeof saleItems>[number]
export type SaleBody = { sale_id: string; event_id: number; items: SaleItem[] }
export type SaleOrder = { id: string; status: string; channel: string; total_mad: number; ticket_ids: string[] }

export const usePosSession = () => useOrgQuery<{ session: PosSession | null }>(['pos', 'session'], '/org/pos/session')

export const useOpenSession = () =>
  useOrgMutation(['pos', 'session'], (label: string, send) => send<PosSession>({ method: 'POST', path: '/org/pos/sessions', body: { label } }))

export const useCloseSession = () =>
  useOrgMutation(['pos', 'session'], ({ id, declared_mad }: { id: string; declared_mad: number }, send) =>
    send<ClosedSession>({ method: 'POST', path: path('org', 'pos', 'sessions', id, 'close'), body: { declared_mad } }),
  )

/** Scope: every query of this organizer, so the till's counters and the prices' `sold` both refresh. */
export const useSale = () => useOrgMutation([], (body: SaleBody, send) => send<SaleOrder>({ method: 'POST', path: '/org/pos/orders', body }))

/** `amount_mad`: the exact amount the lookup showed. Repeating it from the same session is a 200 (same order). */
export const useCollect = (code: string) =>
  useOrgMutation(['pos'], (amount_mad: number, send) =>
    send<{ order_id: string; status: string; ticket_ids: string[] }>({ method: 'POST', path: path('org', 'pos', 'codes', code, 'collect'), body: { amount_mad } }),
  )

/** `code`: the 6 normalised symbols; no call before the code is complete. */
export const useCodeLookup = (code: string | null) =>
  useOrgQuery<CodeLookup>(['pos', 'codes', code], path('org', 'pos', 'codes', code ?? ''), { enabled: code !== null })

/**
 * Each call is a print on the server (audit `pos.print`, the session's `prints`) and the answer carries QR tokens
 * (`no-store`): one fetch per visit of the print view, nothing kept once it is left, no refetch on focus or reconnect,
 * no automatic retry (a failed GET may still have been counted); "Réessayer" is the explicit second print.
 */
export const usePrintData = (orderId: string) =>
  useOrgQuery<PrintData>(['pos', 'print', orderId], path('org', 'pos', 'orders', orderId, 'tickets'), {
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  })

/** Payable, else why not: PAID, or EXPIRED (also an AWAITING_CASH order past its hold that the expiry job hasn't swept). */
export const codeStatus = (o: Pick<CodeLookup, 'payable' | 'status'>) =>
  o.payable ? 'payable' : o.status === 'PAID' ? 'PAID' : o.status === 'EXPIRED' || o.status === 'AWAITING_CASH' ? 'EXPIRED' : 'other'

// Crockford base32 (04-api §Cash codes).
const SYMBOLS = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/**
 * Typed text → the code's symbols so far, and the characters that can't be one (04-api §Cash codes input:
 * case-insensitive, spaces and hyphens ignored, a leading `JMH` ignored, `O` → `0`, `I`/`L` → `1`).
 * A leading JMH always goes: no code starts with JMH (04-api §Cash codes, never issued), so this is never ambiguous.
 */
export function readCode(input: string) {
  let s = input.toUpperCase().replace(/[\s-]/g, '')
  if (s.startsWith('JMH')) s = s.slice(3)
  s = s.replace(/O/g, '0').replace(/[IL]/g, '1')
  return { symbols: s, invalid: [...new Set([...s].filter((c) => !SYMBOLS.includes(c)))] }
}

/** The 6 symbols to look up, or null (not a code: wrong length or a character outside the alphabet). */
export function normalizeCode(input: string) {
  const { symbols, invalid } = readCode(input)
  return invalid.length === 0 && symbols.length === 6 ? symbols : null
}

/** `4F7K29` → `JMH 4F7K 29`; missing symbols show as `_` (`4F` → `JMH 4F__ __`). */
export function displayCode(symbols: string) {
  const s = symbols.slice(0, 6).padEnd(6, '_')
  return `JMH ${s.slice(0, 4)} ${s.slice(4)}`
}

/** Change to give back, in centimes: received (DH text) − total; negative = still missing; null = not an amount. */
export function changeDue(received: string, totalMad: number) {
  const cents = parseDh(received)
  return cents === null ? null : cents - totalMad
}

/** Last 8 hex of a UUID, uppercase, 4-4 (`Commande ABCD 1234`, as W4). */
export const shortNo = (id: string) => {
  const s = id.replace(/-/g, '').slice(-8).toUpperCase()
  return `${s.slice(0, 4)} ${s.slice(4)}`
}

export type Holder = { name: string; phone: string }
/** One item per category with places, in row order; `sameName`: every place gets holders[0] (name and phone). */
export function saleItems(prices: Price[], qty: Record<string, number>, holders: Holder[], sameName: boolean) {
  let place = 0
  return prices
    .filter((p) => (qty[p.id] ?? 0) > 0)
    .map((p) => ({
      price_category_id: p.id,
      qty: qty[p.id]!,
      holders: Array.from({ length: qty[p.id]! }, () => {
        const h = holders[sameName ? 0 : place++]!
        const phone = h.phone.trim() === '' ? null : normalizePhone(h.phone)
        return phone ? { name: h.name.trim(), phone } : { name: h.name.trim() }
      }),
    }))
}

/**
 * A 422 field of POST /org/pos/orders (`items[1].holders[0].name`, as /fan/orders) → the form's holder row
 * (`holders.2.name`): rows are the places in item order; with "même nom" every place is row 0. Null: not a holder field.
 */
export function holderField(key: string, items: readonly { qty: number }[], sameName: boolean) {
  const m = /^items\[(\d+)\]\.holders\[(\d+)\]\.(name|phone)$/.exec(key)
  if (!m) return null
  const row = sameName ? 0 : items.slice(0, Number(m[1])).reduce((n, it) => n + it.qty, 0) + Number(m[2])
  return `holders.${row}.${m[3] as 'name' | 'phone'}` as const
}

type Messages = (k: 'pos.sale.nameInvalid' | 'pos.sale.phoneInvalid') => string

/** The Vente form (B11); `t` gives the two messages. */
export function saleSchema(t: Messages) {
  const holder = z.object({ name: z.string(), phone: z.string() })
  return z
    .object({
      event_id: z.string().min(1),
      qty: z.record(z.string(), z.number().int().min(0)),
      same_name: z.boolean(),
      holders: z.array(holder),
      received: z.string(),
    })
    .superRefine((v, ctx) => {
      // With "même nom" only the first row is shown and sent for every place (saleItems): only it is checked.
      const rows = v.same_name ? v.holders.slice(0, 1) : v.holders
      rows.forEach((h, i) => {
        // 04-api §Holder names: the server normalises and has the last word (Latin letters, 2-60); here, the length.
        const name = h.name.trim()
        if (name.length < 2 || name.length > 60) ctx.addIssue({ code: 'custom', path: ['holders', i, 'name'], message: t('pos.sale.nameInvalid') })
        if (h.phone.trim() !== '' && normalizePhone(h.phone) === null) ctx.addIssue({ code: 'custom', path: ['holders', i, 'phone'], message: t('pos.sale.phoneInvalid') })
      })
    })
}
export type SaleValues = z.input<ReturnType<typeof saleSchema>>
