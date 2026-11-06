import { expect, it } from 'vitest'
import { normalizePhone } from './phone'

it.each([
  '06 12 34 56 78',
  '0612345678',
  '+212 6 12 34 56 78',
  '212612345678',
  '00212612345678',
  '06.12.34.56.78',
  '06-12-34-56-78',
  '+212 (0)6 12 34 56 78',
  '+212 06 12 34 56 78',
  '\u0660\u0666\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668', // Arabic-Indic
  '\u06f0\u06f6\u06f1\u06f2\u06f3\u06f4\u06f5\u06f6\u06f7\u06f8', // Extended Arabic-Indic
  '+\u0662\u0661\u0662 \u0666 \u0661\u0662 \u0663\u0664 \u0665\u0666 \u0667\u0668',
  '\u200e+212 6 12 34 56 78\u200f', // LRM / RLM
  '\u2066+212\u2069 \u061c06 12 34 56 78\u2067\u2068', // isolates + Arabic letter mark
  '\u202b\u202a+212 6 12\u202c \u202d34 56\u202e 78\u202c', // embeddings LRE/RLE, overrides LRO/RLO, PDF
])('normalizes %j to E.164', (input) => expect(normalizePhone(input)).toBe('+212612345678'))

it.each(['', 'abc', '061234567', '06123456789', '0812345678', '+33612345678', '+21200612345678', '+212 6 12 34 56 7x', '(06) 12'])(
  'rejects %j',
  (input) => expect(normalizePhone(input)).toBeNull(),
)
