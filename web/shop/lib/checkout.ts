// W3 form logic, kept out of the component so it can be tested: row validation, the 422 → row mapping, whether to
// save ticket 1's name, which field to focus.

import { normalizeHolderName } from './holder-name'
import type { PayMethod, Result } from './order-client'
import { normalizePhone } from './phone'

/** `pays`: the friend pays their own place (04 §Split pay); only read from ticket 2 on, once a phone is typed. */
export type Row = { name: string; phone: string; pays?: boolean }
/** `pays`: the 422 reason on that row's `pays`; `limit`: that friend is over their own per-account limit (409). */
export type RowError = { name?: boolean; phone?: boolean; pays?: string; limit?: { limit: number; held: number } }
export type Holder = { name: string; phone?: string; pays?: true }

/**
 * Holders as the server will read them (names normalised, phones in E.164), or the rows' errors. Row 1 is the buyer:
 * its phone is never sent. An empty phone means "the buyer holds this ticket".
 */
export function validateRows(rows: Row[]): { holders: Holder[]; errors: null } | { holders: null; errors: RowError[] } {
  const errors: RowError[] = []
  const holders = rows.map((row, i): Holder => {
    const name = normalizeHolderName(row.name)
    const phone = i === 0 || row.phone.trim() === '' ? undefined : normalizePhone(row.phone)
    if (!name || phone === null) errors[i] = { ...(!name && { name: true }), ...(phone === null && { phone: true }) }
    if (!phone) return { name: name ?? '' }
    return row.pays ? { name: name ?? '', phone, pays: true } : { name: name ?? '', phone }
  })
  return errors.length ? { holders: null, errors } : { holders, errors: null }
}

/** Whether a row shows the "Paie sa place" switch: a friend's row (ticket 2 on) with a phone typed. */
export const canSplit = (row: Row, i: number) => i > 0 && row.phone.trim() !== ''

/** Whether a friend's phone is set on any row from ticket 2 on (then the buyer confirms their consent, plan Q21). */
export const hasFriendPhone = (rows: Row[]) => rows.some(canSplit)

/** W3 summary (integers only): places the buyer pays and their amount in centimes, places friends pay. */
export function splitSummary(rows: Row[], unitMad: number) {
  const friends = rows.filter((r, i) => canSplit(r, i) && r.pays).length
  const mine = rows.length - friends
  return { mine, mineMad: mine * unitMad, friends }
}

/**
 * The rows an error answer marks. 422: `details.fields` keys `items[0].holders[j].name|phone|pays` (a `pays` one
 * carries `details.reason`: PHONE_REQUIRED, TOO_LATE). 409 PER_ACCOUNT_LIMIT with `details.field`
 * `items[0].holders[j]`: that friend is over their own limit. Other fields (`items` for NO_BUYER_PLACE) are ignored.
 */
export function rowErrorsFrom422(r: Result): RowError[] {
  const errors: RowError[] = []
  const details = (r.data?.details ?? {}) as Record<string, unknown>
  for (const f of Object.keys((details.fields ?? {}) as object)) {
    const m = /^items\[0\]\.holders\[(\d{1,2})\]\.(name|phone|pays)$/.exec(f)
    if (m) errors[Number(m[1])] = { ...errors[Number(m[1])], [m[2]!]: m[2] === 'pays' ? String(details.reason ?? 'INVALID') : true }
  }
  const m = r.data?.code === 'PER_ACCOUNT_LIMIT' && typeof details.field === 'string' && /^items\[0\]\.holders\[(\d{1,2})\]$/.exec(details.field)
  if (m) errors[Number(m[1])] = { ...errors[Number(m[1])], limit: { limit: Number(details.limit) || 0, held: Number(details.held) || 0 } }
  return errors
}

/** "Relancer sur WhatsApp" (DESIGN 9 Groupe): wa.me with the copy and the member's `pay_url`, no phone number. */
export const whatsappUrl = (text: string, payUrl: string) => `https://wa.me/?text=${encodeURIComponent(`${text} ${payUrl}`)}`

/**
 * Payment method rows for an order paid now (W3, and the W4 split-pay member), from the event (04 GET
 * /fan/events/{id}): cash hidden when not set up (`cash_until` null), `cashLate` from `cash_until` on (also when a
 * cached read still lists it). A code issued now is valid until least(now + 24 h, sales close): `cashUntil` (ISO).
 */
export function cashOptions(e: { payment_methods: PayMethod[]; cash_until: string | null; sales_close_at: string | null }, now: number) {
  const cashLate = !!e.cash_until && (now >= Date.parse(e.cash_until) || !e.payment_methods.includes('CASH_AGENCY'))
  const methods = e.payment_methods.filter((m) => m !== 'CASH_AGENCY' || (e.cash_until && !cashLate))
  const cashUntil = Math.min(now + 24 * 3_600_000, e.sales_close_at ? Date.parse(e.sales_close_at) : Infinity)
  return { methods, cashLate, cashUntil: new Date(cashUntil).toISOString() }
}

/** Ticket 1's name is saved to the profile (PATCH /fan/me) only when it differs from the stored one. */
export const shouldSaveName = (name: string, displayName: string | null) => name !== displayName

/** The id of the first field to focus after errors (`name-0`, `phone-1`…), or null. */
export function firstInvalid(errors: RowError[]): string | null {
  for (const [i, e] of errors.entries()) {
    if (e?.name) return `name-${i}`
    if (e?.phone || e?.limit || e?.pays) return `phone-${i}`
  }
  return null
}
