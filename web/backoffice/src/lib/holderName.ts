// docs/04-api.md §Holder names, mirrored from backend common/HolderNames.java (same as web/shop/lib/holder-name.ts) so
// a form says what the server will refuse. The server stays the authority.

/** Latin letters only (not Latin-script symbols); the rest also allows space, apostrophe, dot, hyphen. */
const LATIN = /^(?=\p{L})\p{Script=Latin}(?:(?=\p{L})\p{Script=Latin}|[ '.-]){1,59}$/u
/** The 36 Arabic base letters (U+0621-063A, U+0641-064A: the CIN Arabic line, what the PDF shapes); extended letters
 * (ڭ, ڤ, گ, پ…), digits and punctuation are refused. The rest also allows space and hyphen. */
const ARABIC = /^[\u0621-\u063a\u0641-\u064a][\u0621-\u063a\u0641-\u064a -]{1,59}$/u
/** Tatweel and the Arabic combining marks (harakat, shadda, Quranic marks): removed from every name, Latin too. */
// eslint-disable-next-line no-misleading-character-class -- escaped code point ranges of 04 §Holder names, nothing combines
const ARABIC_MARKS = /[\u0640\u0610-\u061a\u064b-\u065f\u0670\u06d6-\u06ed]/g

/**
 * Holder name as the server will store it, or null (04 §Holder names). Order: refuse letter-numbers (Roman numerals,
 * which NFKC would turn into ASCII letters), NFKC (fullwidth → ASCII, Arabic presentation forms → letters), typographic
 * apostrophe → ', tatweel and Arabic marks removed, `\p{White_Space}` runs (no-break spaces in, U+FEFF out) → one
 * space, trim, then 2–60 code points in one script: Latin letters, space, apostrophe, dot, hyphen; or Arabic base
 * letters, space, hyphen. Mixed scripts, digits and format characters (bidi marks, ZWSP, BOM) → null. The `u` flag
 * makes `{1,59}` count code points.
 */
export function normalizeHolderName(raw: string): string | null {
  if (/\p{Nl}/u.test(raw)) return null
  // \p{White_Space} and an end trim of what is left, as Java's (?U)\s then strip(): JS \s and trim() would also
  // remove U+FEFF, which Java keeps (and then refuses).
  const name = raw
    .normalize('NFKC')
    .replace(/\u2019/g, "'")
    .replace(ARABIC_MARKS, '')
    .replace(/\p{White_Space}+/gu, ' ')
    // eslint-disable-next-line no-control-regex -- U+001C-001F: Java strip() removes them at the ends, so this does too
    .replace(/^[ \x1c-\x1f]+|[ \x1c-\x1f]+$/g, '')
  return LATIN.test(name) || ARABIC.test(name) ? name : null
}
