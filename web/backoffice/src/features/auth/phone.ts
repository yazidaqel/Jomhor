/**
 * Moroccan phone → E.164 (`+212` + 9 digits), or null. Accepts the national form (`06 12 34 56 78`),
 * `+212 6…`, `+212 (0)6…`, `+212 06…`, `212…` and `00212…`, with spaces, dots, dashes or parentheses,
 * in ASCII or Arabic-Indic digits. Mobile (6, 7) and fixed (5) ranges.
 */
export function normalizePhone(input: string): string | null {
  const ascii = input
    .replace(/[\u0660-\u0669]/g, (d) => String(d.charCodeAt(0) - 0x0660)) // Arabic-Indic digits
    .replace(/[\u06f0-\u06f9]/g, (d) => String(d.charCodeAt(0) - 0x06f0)) // Extended Arabic-Indic digits
    // Separators, plus bidi controls pasted from RTL text: ALM U+061C, LRM/RLM U+200E/F, embeddings and overrides
    // LRE/RLE/PDF/LRO/RLO U+202A-202E, isolates U+2066-2069.
    .replace(/[\s.()\-\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, '')
  const m = /^(?:(?:\+|00)?2120?|0)([5-7]\d{8})$/.exec(ascii)
  return m ? `+212${m[1]}` : null
}
