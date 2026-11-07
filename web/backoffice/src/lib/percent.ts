/**
 * `part / whole` as a percentage for display: one decimal under 10 %, `< 0,1 %` for a tiny non-zero share (so a first
 * sale never reads as 0 %), `> 99 %` just short of full (never a false 100 %), 0 % when there is no quota. Counts only, never money.
 */
export function percent(part: number, whole: number, locale: string) {
  const fmt = (r: number, digits: number) => new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: digits }).format(r)
  if (whole <= 0 || part <= 0) return fmt(0, 0)
  const r = part / whole
  if (r < 0.001) return `< ${fmt(0.001, 1)}`
  if (r >= 0.995 && r < 1) return `> ${fmt(0.99, 0)}` // rounding would claim a sell-out that has not happened
  return fmt(r, r < 0.1 ? 1 : 0)
}
