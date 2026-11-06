import { expect, it } from 'vitest'
import { firstInvalid, hasFriendPhone, rowErrorsFrom422, shouldSaveName, validateRows } from './checkout'
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
