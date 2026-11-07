import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { normalizeHolderName } from './holder-name'

// docs/holder-name-vectors.json: the case list Java, Dart and TS share (04 §Holder names). The shop only normalizes
// (labels and initials come from the server), so it checks `ok` and `normalized`.
type Vector = { input: string; ok: boolean; normalized?: string }
const vectors: Vector[] = JSON.parse(readFileSync(new URL('../../../docs/holder-name-vectors.json', import.meta.url), 'utf8'))

it('the shared vectors file is not empty', () => expect(vectors.length).toBeGreaterThan(0))

it.each(vectors.map((v) => [v.input, v.ok ? v.normalized! : null] as const))('shared vector %j → %j', (input, normalized) => {
  expect(normalizeHolderName(input)).toBe(normalized)
})

// Shop extras on top of the shared vectors (some overlap them).
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
  // Arabic (Lot 4)
  ['يزيد عقل', 'يزيد عقل'],
  [' يزيد\u00a0\u202f عقل ', 'يزيد عقل'],
  ['ي\u064eز\u0650يد ع\u064eق\u0652ل', 'يزيد عقل'], // harakat removed
  ['ي\u0640زيد', 'يزيد'], // tatweel removed
  ['\ufef3\ufeb0\ufef4\ufeaa', 'يزيد'], // presentation forms → letters (NFKC)
  ['عبد \ufdf2', 'عبد الله'], // ligature: NFKC, then shadda and superscript alef removed
  ['عبد-الرحيم', 'عبد-الرحيم'],
  ['Ali\u064b', 'Ali'], // marks are removed from every name, Latin too
  ['Yazid\u064e', 'Yazid'],
  [`A${'\u{1df00}'.repeat(59)}`, `A${'\u{1df00}'.repeat(59)}`], // 60 code points (119 UTF-16 units): Latin Extended-G
  ['\u0621\u063a \u0641\u064a', '\u0621\u063a \u0641\u064a'], // the ends of both base ranges
  ['ب'.repeat(60), 'ب'.repeat(60)],
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
  // Arabic (Lot 4)
  'يزيد Aqel', // mixed scripts
  'Yazid عقل',
  'يزيد \u0662', // Arabic-Indic digit: Arabic script, not a letter
  'ع',
  "ي'زيد", // apostrophe and dot are Latin-only
  'يزيد.',
  '-يزيد',
  'يزيد\u060c', // Arabic comma
  'ب'.repeat(61),
  '\u0640\u0640\u0640', // only tatweel: nothing left
  `A${'\u{1df00}'.repeat(60)}`, // 61 code points
  // extended Arabic letters: ڭ ڤ گ پ ک ی ٹ
  'يزيد \u06ad',
  'يزيد \u06a4',
  'يزيد \u06af',
  'يزيد \u067e',
  'يزيد \u06a9',
  'يزيد \u06cc',
  'يزيد \u0679',
  // format characters, anywhere
  'Yazid\u200bAqel',
  'Yazid\u200cAqel',
  'Yazid\u200dAqel',
  'Yazid\u200eAqel',
  'Yazid\u200fAqel',
  'Yazid\u202aAqel',
  'Yazid\u202eAqel',
  'Yazid\u2066Aqel',
  'Yazid\u2069Aqel',
  'Yazid\ufeffAqel',
  'يزيد\u061c عقل',
  '\u2066يزيد عقل\u2069',
  'يزيد \u0661', // Arabic-Indic digit
  'Ali \u06f1', // Extended Arabic-Indic digit
])('refuses %j', (raw) => {
  expect(normalizeHolderName(raw)).toBeNull()
})
