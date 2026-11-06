/** Arabic-Indic and extended Arabic-Indic digits → ASCII. */
export const asciiDigits = (s: string) =>
  s
    .replace(/[\u0660-\u0669]/g, (d) => String(d.charCodeAt(0) - 0x0660))
    .replace(/[\u06f0-\u06f9]/g, (d) => String(d.charCodeAt(0) - 0x06f0))

/**
 * Moroccan phone → E.164 (`+212` + 9 digits), or null. Accepts `06 12 34 56 78`, `+212 6…`, `+212 (0)6…`, `212…`,
 * `00212…`, with spaces, dots, dashes, parentheses or bidi marks, in ASCII or Arabic-Indic digits. Ranges 5, 6, 7.
 * Same rules as web/backoffice (the apps don't share code).
 */
export function normalizePhone(input: string): string | null {
  // Separators, plus bidi marks pasted from RTL text: ALM U+061C, LRM/RLM U+200E/F, embeddings/overrides
  // U+202A-202E, isolates U+2066-2069.
  const ascii = asciiDigits(input).replace(/[\s.()\-\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, '')
  const m = /^(?:(?:\+|00)?2120?|0)([5-7]\d{8})$/.exec(ascii)
  return m ? `+212${m[1]}` : null
}
