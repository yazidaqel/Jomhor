import { expect, it } from 'vitest'
import { formatBp, percent } from './percent'

it('formats shares of a quota', () => {
  const nbsp = (s: string) => s.replace(/\s/gu, ' ')
  expect(nbsp(percent(0, 8500, 'fr'))).toBe('0 %')
  expect(nbsp(percent(5, 0, 'fr'))).toBe('0 %')
  expect(nbsp(percent(2, 8500, 'fr'))).toBe('< 0,1 %')
  expect(nbsp(percent(1250, 39600, 'fr'))).toBe('3,2 %')
  expect(nbsp(percent(6000, 12000, 'fr'))).toBe('50 %')
  expect(nbsp(percent(12000, 12000, 'fr'))).toBe('100 %')
  expect(nbsp(percent(11994, 12000, 'fr'))).toBe('> 99 %') // 99,95 %: not sold out
  expect(nbsp(percent(9940, 10000, 'fr'))).toBe('99 %')
})

it('formats basis points as an exact rate', () => {
  expect(formatBp(500, 'fr')).toBe('5')
  expect(formatBp(250, 'fr')).toBe('2,5')
  expect(formatBp(125, 'fr')).toBe('1,25')
  expect(formatBp(7, 'fr')).toBe('0,07')
  expect(formatBp(0, 'fr')).toBe('0')
  expect(formatBp(10_000, 'fr')).toBe('100')
})
