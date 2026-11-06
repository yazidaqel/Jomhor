// Q7: organizers enter and read times in Africa/Casablanca; the API speaks RFC 3339 UTC.
// Morocco leaves +01:00 for +00:00 during Ramadan, so the offset always comes from the IANA zone, through
// Intl (in every browser and in Node/jsdom). Temporal would do this directly but Node 24 (tests) lacks it.
export const ZONE = 'Africa/Casablanca'

const parts = new Intl.DateTimeFormat('en-US', {
  timeZone: ZONE,
  hourCycle: 'h23',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
})

/** The Casablanca wall clock at instant `ms`, as the epoch ms those same digits would have in UTC. */
function wallMs(ms: number) {
  const p = Object.fromEntries(parts.formatToParts(ms).map((x) => [x.type, Number(x.value)]))
  return Date.UTC(p.year!, p.month! - 1, p.day!, p.hour!, p.minute!, p.second!)
}

/**
 * A `datetime-local` value (`YYYY-MM-DDTHH:mm`, Casablanca wall time) → RFC 3339 UTC (`…T…:00.000Z`).
 * Two passes so the offset used is the one in force at the result, not at the first guess.
 * ponytail: a wall time skipped by a clock change (02:00–03:00 the night Ramadan ends) moves 1 h forward and one
 * repeated by it (the night it starts) takes the later instant; fine for event times, revisit if 2–3 a.m. matters.
 */
export function wallToUtc(wall: string): string {
  const [y, mo, d, h, mi] = wall.split(/[-T:]/).map(Number) as [number, number, number, number, number]
  const asUtc = Date.UTC(y, mo - 1, d, h, mi)
  let ms = asUtc - (wallMs(asUtc) - asUtc)
  ms = asUtc - (wallMs(ms) - ms)
  return new Date(ms).toISOString()
}

/** RFC 3339 instant → Casablanca wall time as a `datetime-local` value (`YYYY-MM-DDTHH:mm`). */
export const utcToWall = (iso: string) => new Date(wallMs(Date.parse(iso))).toISOString().slice(0, 16)

/** RFC 3339 instant → date and time for display, in Casablanca. */
export const formatDateTime = (iso: string, locale: string) =>
  new Intl.DateTimeFormat(locale, { timeZone: ZONE, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))
