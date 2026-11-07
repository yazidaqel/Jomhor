import { expect, it } from 'vitest'
import { dayRange } from './time'

it('dayRange: a skipped midnight starts the day at the clock change, not 23:00 the day before', () => {
  // America/Santiago, 6 September 2026: 24:00 at UTC-04:00 becomes 01:00 at UTC-03:00 (04:00Z).
  expect(dayRange('2026-09-06', '2026-09-06', 'America/Santiago')).toEqual({ from: '2026-09-06T04:00:00.000Z', to: '2026-09-07T03:00:00.000Z' })
  expect(dayRange('2026-09-05', '2026-09-05', 'America/Santiago').to).toBe('2026-09-06T04:00:00.000Z')
  // An ordinary day is untouched.
  expect(dayRange('2026-11-01', '2026-11-01', 'Europe/Paris')).toEqual({ from: '2026-10-31T23:00:00.000Z', to: '2026-11-01T23:00:00.000Z' })
})
