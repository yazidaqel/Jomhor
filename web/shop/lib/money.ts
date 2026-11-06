// Money is integer centimes end to end (`*_mad`). Display only, string and bigint maths: no float ever holds an amount.

const NBSP = '\u00a0' // between the amount and "DH": never wraps
const NNBSP = '\u202f' // FR thousands separator (what Intl 'fr' uses)

/** FR display: 5050 → "50,50 DH", 5000 → "50 DH", 123456 → "1 234,56 DH" (no-break spaces). */
export function formatDh(centimes: number | bigint): string {
  if (typeof centimes === 'number' && !Number.isSafeInteger(centimes)) throw new RangeError(`not integer centimes: ${centimes}`)
  const c = BigInt(centimes)
  const abs = c < 0n ? -c : c
  const whole = (abs / 100n).toString().replace(/\B(?=(\d{3})+$)/g, NNBSP)
  const rest = abs % 100n
  const amount = rest === 0n ? whole : `${whole},${rest.toString().padStart(2, '0')}`
  return `${c < 0n ? '-' : ''}${amount}${NBSP}DH`
}
