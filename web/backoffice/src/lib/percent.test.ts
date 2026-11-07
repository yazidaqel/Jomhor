import { expect, it } from 'vitest'
import { percent } from './percent'

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
