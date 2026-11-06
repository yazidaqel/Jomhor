import { expect, it } from 'vitest'
import { dhInput, formatDh, parseDh } from './money'

it.each([
  ['50', 5000],
  ['50,5', 5050],
  ['50.50', 5050],
  ['50,05', 5005],
  ['0,05', 5],
  ['0', 0],
  [' 12 ', 1200],
  ['1234,56', 123456],
  ['10000000', 1_000_000_000], // the cap: 10 000 000 DH
  ['10000000,00', 1_000_000_000],
])('parseDh(%j) → %i centimes', (text, cents) => {
  expect(parseDh(text)).toBe(cents)
})

it.each(['50,555', '50.555', '-5', '+5', 'abc', '5O', '', ' ', '5e2', '1 200', '1.200,50', '50,', ',5', '.5', '0x10', '10000000,01', '10000001', '100000000', '90071992547410'])(
  'parseDh(%j) is rejected',
  (text) => {
    expect(parseDh(text)).toBeNull()
  },
)

it.each([0, 5, 5000, 5050, 5005, 123456])('dhInput(%i) reads back to the same centimes', (cents) => {
  expect(parseDh(dhInput(cents))).toBe(cents)
})

it('formatDh displays centimes in the FR locale with 2 decimals', () => {
  const nf = new Intl.NumberFormat('fr', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  expect(formatDh(123450, 'fr')).toBe(nf.format(1234.5))
  expect(formatDh(5, 'fr')).toBe(nf.format(0.05))
  expect(formatDh(5000, 'fr')).toBe(nf.format(50))
})

it('formatDh: 0 centimes, and the largest integer a JSON number carries exactly', () => {
  const nf = new Intl.NumberFormat('fr', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  expect(formatDh(0, 'fr')).toBe(nf.format(0))
  expect(formatDh(1, 'fr')).toBe(nf.format('0.01'))
  // 90 071 992 547 409,91 DH: Number(...) / 100 would round this; the string path does not.
  expect(formatDh(Number.MAX_SAFE_INTEGER, 'fr')).toBe(nf.format('90071992547409.91'))
  expect(formatDh(123_456_789_012, 'fr')).toBe(nf.format('1234567890.12'))
})

it('formatDh is exact for a bigint total past 2^53', () => {
  const total = 2n ** 64n + 1n // centimes
  const nf = new Intl.NumberFormat('fr', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  expect(formatDh(total, 'fr')).toBe(nf.format('184467440737095516.17'))
  expect(Number.isSafeInteger(Number(total))).toBe(false)
})
