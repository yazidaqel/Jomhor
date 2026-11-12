import { expect, it } from 'vitest'
import { cashOptions, firstInvalid, hasFriendPhone, rowErrorsFrom422, shouldSaveName, splitSummary, validateRows, whatsappUrl } from './checkout'
import type { Result } from './order-client'

it('validateRows: normalised names, friend phones in E.164, the buyer row never sends a phone', () => {
  expect(
    validateRows([
      { name: ' Yazid  Aqel ', phone: '0611111111' }, // row 1 phone ignored
      { name: 'Salma Benali', phone: '06 98 76 54 32' },
      { name: 'Omar', phone: '  ' },
    ]),
  ).toEqual({
    errors: null,
    holders: [{ name: 'Yazid Aqel' }, { name: 'Salma Benali', phone: '+212698765432' }, { name: 'Omar' }],
  })
})

it('validateRows: each bad name or phone is marked on its row', () => {
  expect(
    validateRows([
      { name: 'Y', phone: '' },
      { name: 'Salma', phone: '0812' },
      { name: 'Omar', phone: '' },
      { name: '', phone: 'abc' },
    ]),
  ).toEqual({ holders: null, errors: [{ name: true }, { phone: true }, undefined, { name: true, phone: true }] })
})

it('hasFriendPhone: only a phone from ticket 2 on counts', () => {
  expect(hasFriendPhone([{ name: 'A', phone: '0611111111' }, { name: 'B', phone: '' }])).toBe(false)
  expect(hasFriendPhone([{ name: 'A', phone: '' }, { name: 'B', phone: '06' }])).toBe(true)
})

const unprocessable = (fields: Record<string, string>): Result => ({
  ok: false,
  status: 422,
  data: { code: 'VALIDATION_FAILED', message: '', details: { fields } },
})

it('rowErrorsFrom422 marks holder rows from the field paths and ignores the rest', () => {
  expect(
    rowErrorsFrom422(
      unprocessable({ 'items[0].holders[1].name': 'x', 'items[0].holders[2].phone': 'x', 'items[0].holders[1].phone': 'x', 'items[0].qty': 'x', event_id: 'x' }),
    ),
  ).toEqual([undefined, { name: true, phone: true }, { phone: true }])
  expect(rowErrorsFrom422(unprocessable({ 'items[1].holders[0].name': 'x', 'items[0].holders[999].name': 'x' }))).toEqual([])
  expect(rowErrorsFrom422({ ok: false, status: 409, data: { code: 'SOLD_OUT' } })).toEqual([])
})

it.each<[string, string | null, boolean]>([
  ['Yazid Aqel', 'Yazid Aqel', false],
  ['Yazid Aqel', null, true],
  ['Yazid A', 'Yazid Aqel', true],
])('shouldSaveName(%j, %j) → %s', (name, stored, save) => {
  expect(shouldSaveName(name, stored)).toBe(save)
})

it('firstInvalid: the first row with an error, name before phone', () => {
  expect(firstInvalid([undefined as never, { phone: true }, { name: true }])).toBe('phone-1')
  expect(firstInvalid([{ name: true, phone: true }])).toBe('name-0')
  expect(firstInvalid([])).toBeNull()
})

// Lot 4 · split pay (WS-8).

it('validateRows: pays: true only on that friend row with a phone; never on row 1, never without a phone', () => {
  expect(
    validateRows([
      { name: 'Yazid Aqel', phone: '', pays: true }, // the buyer never splits
      { name: 'Salma Benali', phone: '0612345678', pays: true },
      { name: 'Omar Idrissi', phone: '', pays: true }, // phone cleared: the switch is gone
      { name: 'Karim Bennani', phone: '0698765432', pays: false },
    ]),
  ).toEqual({
    errors: null,
    holders: [
      { name: 'Yazid Aqel' },
      { name: 'Salma Benali', phone: '+212612345678', pays: true },
      { name: 'Omar Idrissi' },
      { name: 'Karim Bennani', phone: '+212698765432' },
    ],
  })
})

it('splitSummary: integer places and centimes; a switch without a phone does not count', () => {
  const rows = [
    { name: 'A', phone: '', pays: true },
    { name: 'B', phone: '0612345678', pays: true },
    { name: 'C', phone: '', pays: true },
    { name: 'D', phone: '0698765432' },
  ]
  expect(splitSummary(rows, 12050)).toEqual({ mine: 3, mineMad: 36150, friends: 1 })
  expect(splitSummary(rows.slice(0, 1), 5000)).toEqual({ mine: 1, mineMad: 5000, friends: 0 })
})

it('rowErrorsFrom422: a pays field carries details.reason; PER_ACCOUNT_LIMIT details.field marks that friend', () => {
  expect(rowErrorsFrom422(unprocessable({ 'items[0].holders[2].pays': 'x' }))).toEqual([undefined, undefined, { pays: 'INVALID' }])
  const r = (reason: string): Result => ({
    ok: false,
    status: 422,
    data: { code: 'VALIDATION_FAILED', details: { fields: { 'items[0].holders[1].pays': 'x' }, reason } },
  })
  expect(rowErrorsFrom422(r('PHONE_REQUIRED'))[1]).toEqual({ pays: 'PHONE_REQUIRED' })
  expect(rowErrorsFrom422(r('TOO_LATE'))[1]).toEqual({ pays: 'TOO_LATE' })
  expect(
    rowErrorsFrom422({ ok: false, status: 422, data: { code: 'VALIDATION_FAILED', details: { fields: { items: 'x' }, reason: 'NO_BUYER_PLACE' } } }),
  ).toEqual([])
  const limit: Result = { ok: false, status: 409, data: { code: 'PER_ACCOUNT_LIMIT', details: { limit: 4, held: 3, field: 'items[0].holders[2]' } } }
  expect(rowErrorsFrom422(limit)).toEqual([undefined, undefined, { limit: { limit: 4, held: 3 } }])
  expect(firstInvalid(rowErrorsFrom422(limit))).toBe('phone-2')
  expect(rowErrorsFrom422({ ...limit, data: { code: 'PER_ACCOUNT_LIMIT', details: { limit: 4, held: 3 } } })).toEqual([]) // the buyer's own
})

it('whatsappUrl: wa.me without a phone, text and pay_url encoded', () => {
  const url = whatsappUrl('Je t’ai gardé une place & plus', 'https://shop.test/orders/m1?x=1')
  expect(url.startsWith('https://wa.me/?text=')).toBe(true)
  expect(url).toContain(encodeURIComponent('https://shop.test/orders/m1?x=1'))
  expect(new URL(url).searchParams.get('text')).toBe('Je t’ai gardé une place & plus https://shop.test/orders/m1?x=1')
})

it('cashOptions: hidden without cash_until, late from cash_until, code valid until least(now + 24 h, sales close)', () => {
  const now = Date.parse('2026-02-01T12:00:00Z')
  const e = { payment_methods: ['CARD', 'CASH_AGENCY'] as const, cash_until: '2026-02-07T16:00:00Z', sales_close_at: '2026-02-01T20:00:00Z' }
  expect(cashOptions({ ...e, payment_methods: [...e.payment_methods] }, now)).toEqual({ methods: ['CARD', 'CASH_AGENCY'], cashLate: false, cashUntil: '2026-02-01T20:00:00.000Z' })
  expect(cashOptions({ ...e, payment_methods: [...e.payment_methods], cash_until: null }, now).methods).toEqual(['CARD'])
  expect(cashOptions({ ...e, payment_methods: [...e.payment_methods], cash_until: '2026-02-01T11:00:00Z' }, now)).toMatchObject({ methods: ['CARD'], cashLate: true })
})
