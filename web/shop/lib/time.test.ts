import { expect, it } from 'vitest'
import { countdown, formatDate, formatDateTime, formatTime } from './time'

it('formats in the venue zone: Africa/Casablanca is UTC+1, and UTC+0 during Ramadan', () => {
  expect(formatDateTime('2026-02-07T19:00:00Z', 'Africa/Casablanca', 'fr-FR')).toBe('Sam. 7 févr. 2026 · 20:00')
  expect(formatTime('2026-03-01T20:00:00Z', 'Africa/Casablanca', 'fr-FR')).toBe('20:00') // Ramadan 2026
  expect(formatTime('2026-02-07T19:00:00Z', 'Europe/Paris', 'fr-FR')).toBe('20:00')
  expect(formatDate('2026-02-07T23:30:00Z', 'Africa/Casablanca', 'fr-FR')).toBe('Dim. 8 févr. 2026') // date follows the zone
})

it('countdown splits whole minutes and stops once started', () => {
  const now = Date.parse('2026-02-01T12:00:00Z')
  expect(countdown('2026-02-07T19:00:00Z', now)).toEqual({ days: 6, hours: 7, minutes: 0 })
  expect(countdown('2026-02-01T12:00:59Z', now)).toBeNull()
  expect(countdown('2026-02-01T11:00:00Z', now)).toBeNull()
})

it('takes the Intl locale from the caller (the dictionary)', () => {
  expect(formatDate('2026-02-07T19:00:00Z', 'Africa/Casablanca', 'en-GB')).toBe('Sat, 7 Feb 2026')
})

it('AR (ar-MA) dates and numbers use Western digits 0-9 (DESIGN.md, Lot 4 AR/RTL)', () => {
  const s = formatDateTime('2026-02-07T19:00:00Z', 'Africa/Casablanca', 'ar-MA')
  expect(s).toMatch(/7/)
  expect(s).toContain('2026')
  expect(s).toContain('20:00')
  expect(s).not.toMatch(/[\u0660-\u0669\u06f0-\u06f9]/) // Arabic-Indic and Extended Arabic-Indic digits
  expect(new Intl.NumberFormat('ar-MA').format(1234)).toMatch(/^1.?234$/)
})
