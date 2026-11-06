// W3 form logic, kept out of the component so it can be tested: row validation, the 422 → row mapping, whether to
// save ticket 1's name, which field to focus.

import { normalizeHolderName } from './holder-name'
import type { Result } from './order-client'
import { normalizePhone } from './phone'

export type Row = { name: string; phone: string }
export type RowError = { name?: boolean; phone?: boolean }
export type Holder = { name: string; phone?: string }

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
    return phone ? { name: name ?? '', phone } : { name: name ?? '' }
  })
  return errors.length ? { holders: null, errors } : { holders, errors: null }
}

/** Whether a friend's phone is set on any row from ticket 2 on (then the buyer confirms their consent, plan Q21). */
export const hasFriendPhone = (rows: Row[]) => rows.some((r, i) => i > 0 && r.phone.trim() !== '')

/** A 422's `details.fields` keys `items[0].holders[j].name|phone` → the rows to mark. Other fields are ignored. */
export function rowErrorsFrom422(r: Result): RowError[] {
  const errors: RowError[] = []
  const details = (r.data?.details ?? {}) as Record<string, unknown>
  for (const f of Object.keys((details.fields ?? {}) as object)) {
    const m = /^items\[0\]\.holders\[(\d{1,2})\]\.(name|phone)$/.exec(f)
    if (m) errors[Number(m[1])] = { ...errors[Number(m[1])], [m[2]!]: true }
  }
  return errors
}

/** Ticket 1's name is saved to the profile (PATCH /fan/me) only when it differs from the stored one. */
export const shouldSaveName = (name: string, displayName: string | null) => name !== displayName

/** The id of the first field to focus after errors (`name-0`, `phone-1`…), or null. */
export function firstInvalid(errors: RowError[]): string | null {
  for (const [i, e] of errors.entries()) {
    if (e?.name) return `name-${i}`
    if (e?.phone) return `phone-${i}`
  }
  return null
}
