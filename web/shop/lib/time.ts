// Event times are shown in the venue's IANA zone (`timezone`), never a fixed offset: Morocco moves to UTC+0 during
// Ramadan. `locale` is the dictionary's Intl tag (`d.meta.intl`).

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

/** `2026-02-07T19:00:00Z`, `Africa/Casablanca`, `fr-FR` → `Sam. 7 févr. 2026`. */
export const formatDate = (iso: string, timeZone: string, locale: string) =>
  cap(new Intl.DateTimeFormat(locale, { timeZone, weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(iso)))

/** → `20:00`. */
export const formatTime = (iso: string, timeZone: string, locale: string) =>
  new Intl.DateTimeFormat(locale, { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(iso))

/** → `Sam. 7 févr. 2026 · 20:00`. */
export const formatDateTime = (iso: string, timeZone: string, locale: string) =>
  `${formatDate(iso, timeZone, locale)} · ${formatTime(iso, timeZone, locale)}`

/** Whole days, hours and minutes from `now` to `iso`; null once it has passed. */
export function countdown(iso: string, now: number) {
  const min = Math.floor((Date.parse(iso) - now) / 60_000)
  if (!(min > 0)) return null
  return { days: Math.floor(min / 1440), hours: Math.floor((min % 1440) / 60), minutes: min % 60 }
}
