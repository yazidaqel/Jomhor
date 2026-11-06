import { expect, it } from 'vitest'
import { normalizeHolderName } from './holder-name'

// Same cases as backend HolderNamesTest, so client and server agree.
it.each([
  ['Yazid Aqel', 'Yazid Aqel'],
  [' Fe\u0301lix \t O\'Brien ', "Félix O'Brien"], // NFKC composes the accent, whitespace collapsed
  ['Yazid Aqel\u00a0', 'Yazid Aqel'],
  ['\u00a0Yazid Aqel', 'Yazid Aqel'],
  ['Yazid Aqel\u202f', 'Yazid Aqel'],
  ['\u202fYazid\u00a0\u202f Aqel', 'Yazid Aqel'],
  ['\uff39azid Aqel', 'Yazid Aqel'], // fullwidth Y → ASCII
  ['N\u2019Diaye', "N'Diaye"],
  ['Jean-Pierre de la Fontaine', 'Jean-Pierre de la Fontaine'],
  ['élodie d\'Arc', "élodie d'Arc"],
  ['J. R. R. Tolkien', 'J. R. R. Tolkien'],
  ['Yazid\u0085Aqel', 'Yazid Aqel'], // NEL is White_Space, as in Java (?U)\s
  ['Ab', 'Ab'],
  [`A${'b'.repeat(59)}`, `A${'b'.repeat(59)}`],
])('accepts %j as %j', (raw, name) => {
  expect(normalizeHolderName(raw)).toBe(name)
})

it.each([
  '',
  '   ',
  'A',
  `A${'b'.repeat(60)}`,
  '\u2160\u2161', // Roman numerals: refused before NFKC makes them "III"
  'Yazid \u2161',
  'Yazid \u2180',
  '\u064a\u0632\u064a\u062f', // Arabic script: Lot 4
  "'Ali",
  '-Ali',
  '.Ali',
  'Ali_B',
  'Yazid Aqel\ufeff', // BOM / ZWNBSP is not White_Space: Java keeps it and refuses the name (JS trim() would drop it)
  '\ufeffYazid Aqel',
  'Ali2',
  'Ali\u00b2', // superscript two → "2" after NFKC
  'Ali \u03a9', // Greek
  'q\u0301x', // no precomposed form: the combining mark stays and is not a letter
])('refuses %j', (raw) => {
  expect(normalizeHolderName(raw)).toBeNull()
})
