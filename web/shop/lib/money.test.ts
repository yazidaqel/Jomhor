import { expect, it } from 'vitest'
import { ltr } from './i18n'
import { formatDh } from './money'

const n = '\u202f' // thousands
const s = '\u00a0' // before DH

it.each<[number | bigint, string]>([
  [5050, `50,50${s}DH`],
  [5000, `50${s}DH`],
  [5005, `50,05${s}DH`],
  [5, `0,05${s}DH`],
  [0, `0${s}DH`],
  [99, `0,99${s}DH`],
  [100000, `1${n}000${s}DH`],
  [123456, `1${n}234,56${s}DH`],
  [-5050, `-50,50${s}DH`],
  [Number.MAX_SAFE_INTEGER, `90${n}071${n}992${n}547${n}409,91${s}DH`],
  [2n ** 64n + 1n, `184${n}467${n}440${n}737${n}095${n}516,17${s}DH`], // past 2^53, exact
])('formatDh(%s) → %j', (centimes, text) => {
  expect(formatDh(centimes)).toBe(ltr(text))
})

it('matches Intl fr grouping and decimals', () => {
  const nf = new Intl.NumberFormat('fr', { minimumFractionDigits: 2 })
  expect(formatDh(123456)).toBe(ltr(`${nf.format('1234.56' as `${number}`)}${s}DH`))
})

it.each([50.5, Number.NaN, Infinity, 2 ** 53])('rejects %s (not integer centimes)', (v) => {
  expect(() => formatDh(v)).toThrow(RangeError)
})

it('is an LTR isolate, so an RTL line keeps the amount whole', () => {
  expect(formatDh(5050)).toBe('\u206650,50\u00a0DH\u2069')
})
