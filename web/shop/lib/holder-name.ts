// docs/04-api.md §Holder names, mirrored from backend common/HolderNames.java so the form says what the server will
// refuse. The server stays the authority.

/** Latin-script letters only (not Latin-script symbols); the rest also allows space, apostrophe, dot, hyphen. */
const NAME = /^(?=\p{L})\p{Script=Latin}(?:(?=\p{L})\p{Script=Latin}|[ '.-]){1,59}$/u

/**
 * Holder name as the server will store it, or null. Order: refuse letter-numbers (Roman numerals, which NFKC would
 * turn into ASCII letters), NFKC (fullwidth → ASCII), typographic apostrophe → ', whitespace runs (no-break spaces
 * included) → one space, trim, then 2–60 Latin letters, space, apostrophe, dot, hyphen.
 */
export function normalizeHolderName(raw: string): string | null {
  if (/\p{Nl}/u.test(raw)) return null
  // \p{White_Space} and an end trim of what is left, as Java's (?U)\s then strip(): JS \s and trim() would also
  // remove U+FEFF, which Java keeps (and then refuses).
  const name = raw
    .normalize('NFKC')
    .replace(/\u2019/g, "'")
    .replace(/\p{White_Space}+/gu, ' ')
    // eslint-disable-next-line no-control-regex -- U+001C-001F: Java strip() removes them at the ends, so this does too
    .replace(/^[ \x1c-\x1f]+|[ \x1c-\x1f]+$/g, '')
  return NAME.test(name) ? name : null
}
