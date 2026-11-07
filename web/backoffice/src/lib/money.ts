// Money is integer centimes end to end (`*_mad`). DH text is parsed with string and integer maths only: no float
// ever holds an amount.

/** Highest price accepted: 10 000 000 DH, in centimes. */
export const MAX_PRICE_CENTS = 10_000_000 * 100

/**
 * `"50"` → 5000, `"50,5"` / `"50.50"` → 5050, `" 0,05 "` → 5; anything else (more than 2 decimals, a sign, letters,
 * thousands separators, empty, over 10 000 000 DH) → null.
 */
export function parseDh(text: string): number | null {
  const m = /^(\d{1,8})(?:[.,](\d{1,2}))?$/.exec(text.trim()) // 8 digits: the cap below keeps every step exact
  if (!m) return null
  const cents = Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'))
  return cents <= MAX_PRICE_CENTS ? cents : null
}

/** 5050 → `"50,50"`, 5000 → `"50"`: what an edit input starts with (parseDh reads it back). */
export function dhInput(cents: number) {
  const rest = cents % 100
  const whole = (cents - rest) / 100 // exact: cents - rest is a multiple of 100
  return rest === 0 ? String(whole) : `${whole},${String(rest).padStart(2, '0')}`
}

/**
 * Centimes → `"1 234,50"` in `locale` (display only). Bigint maths and a decimal string handed to Intl, so a total of
 * Σ quota × price is exact past 2^53 too. Negative amounts keep their sign; `signDisplay: 'exceptZero'` adds a `+`
 * (a till's gap).
 */
export function formatDh(cents: number | bigint, locale: string, signDisplay: 'auto' | 'exceptZero' = 'auto') {
  const c = BigInt(cents)
  const a = c < 0n ? -c : c
  const decimal = `${c < 0n ? '-' : ''}${a / 100n}.${String(a % 100n).padStart(2, '0')}` as `${number}`
  return new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay }).format(decimal)
}
