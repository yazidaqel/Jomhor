// Q7: wall times are entered and shown in the venue's IANA `timezone`; the API speaks RFC 3339 UTC. Offsets always
// come from the zone (Morocco alone has moved between +01:00 and +00:00 several times), through Intl: in every
// browser and in Node/jsdom. Temporal would do this directly but Node 24 (tests) lacks it.
export const DEFAULT_ZONE = 'Africa/Casablanca' // new venues (04-api §Organizer /org/venues)

const formatters = new Map<string, Intl.DateTimeFormat>()
const partsIn = (zone: string) => {
  let f = formatters.get(zone)
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
    formatters.set(zone, f)
  }
  return f
}

/** The wall clock in `zone` at instant `ms`, as the epoch ms those same digits would have in UTC. */
function wallMs(ms: number, zone: string) {
  const p = Object.fromEntries(partsIn(zone).formatToParts(ms).map((x) => [x.type, Number(x.value)]))
  return Date.UTC(p.year!, p.month! - 1, p.day!, p.hour!, p.minute!, p.second!)
}

// A geographic IANA id, case as in tzdata. Leaves out what the backend refuses for a venue: UTC, GMT, EST,
// Etc/*, SystemV/*, fixed offsets like +01:00, lowercase spellings.
const VENUE_ZONE = /^(Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Europe|Indian|Pacific)\/[A-Za-z0-9_+-]+(\/[A-Za-z0-9_+-]+)*$/

/**
 * True for a zone a venue can use: a geographic IANA id that Intl also knows. Not an exact match against
 * `Intl.supportedValuesOf` on purpose: V8 lists Europe/Kiev and Asia/Calcutta where the backend (Java) prefers
 * Europe/Kyiv and Asia/Kolkata; both spellings are accepted. The server stays the authority (422 on `timezone`).
 */
export function isVenueZone(zone: string) {
  if (!VENUE_ZONE.test(zone)) return false
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone })
    return true
  } catch {
    return false
  }
}

/**
 * A `datetime-local` value (`YYYY-MM-DDTHH:mm`, wall time in `zone`) → RFC 3339 UTC (`…T…:00.000Z`).
 * Two passes so the offset used is the one in force at the result, not at the first guess.
 * ponytail: a wall time skipped by a clock change lands on either side of the gap, off by the gap's length (Europe
 * 02:30 -> 03:30; Santiago's skipped midnight -> 23:00 the day before), and one repeated by it takes the later
 * instant; fine for event times, revisit if the small hours around a change matter. `dayRange` corrects a skipped
 * midnight.
 */
export function wallToUtc(wall: string, zone: string): string {
  const [y, mo, d, h, mi] = wall.split(/[-T:]/).map(Number) as [number, number, number, number, number]
  const asUtc = Date.UTC(y, mo - 1, d, h, mi)
  let ms = asUtc - (wallMs(asUtc, zone) - asUtc)
  ms = asUtc - (wallMs(ms, zone) - ms)
  return new Date(ms).toISOString()
}

/** A `YYYY-MM-DD` date `n` days later (calendar maths in UTC, where every day has 24 h). */
export const addDays = (day: string, n: number) => new Date(Date.parse(day) + n * 86_400_000).toISOString().slice(0, 10)

/** Today's date (`YYYY-MM-DD`) in `zone`. */
export const todayIn = (zone: string, ms = Date.now()) => new Date(wallMs(ms, zone)).toISOString().slice(0, 10)

/**
 * Local dates `from` to `to`, both included, in `zone` → the RFC 3339 bounds of a [from, to) range: midnight at the
 * start of `from`, midnight at the end of `to`. A day with a clock change is 23 or 25 h long, as it was locally.
 */
export const dayRange = (from: string, to: string, zone: string) => ({ from: startOfDay(from, zone), to: startOfDay(addDays(to, 1), zone) })

/**
 * The first instant of local date `day` in `zone`. Where a clock change skips midnight (America/Santiago in
 * September), wallToUtc can land on the day before: the day then starts at the change, i.e. midnight in the offset in
 * force just before it.
 */
function startOfDay(day: string, zone: string) {
  const iso = wallToUtc(`${day}T00:00`, zone)
  const ms = Date.parse(iso)
  return todayIn(zone, ms) >= day ? iso : new Date(Date.parse(day) - (wallMs(ms, zone) - ms)).toISOString()
}

/** A `YYYY-MM-DD` date for display (`3 nov. 2026`): a calendar date, in no zone. */
export const formatDay = (day: string, locale: string) => new Intl.DateTimeFormat(locale, { timeZone: 'UTC', dateStyle: 'medium' }).format(Date.parse(day))

/** RFC 3339 instant → wall time in `zone` as a `datetime-local` value (`YYYY-MM-DDTHH:mm`). */
export const utcToWall = (iso: string, zone: string) => new Date(wallMs(Date.parse(iso), zone)).toISOString().slice(0, 16)

/** RFC 3339 instant → date and time for display, in `zone` (none: the browser's, for times not tied to a venue). */
export const formatDateTime = (iso: string, locale: string, zone?: string) =>
  new Intl.DateTimeFormat(locale, { timeZone: zone, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))

/** `UTC+01:00` / `UTC-03:30` / `UTC+00:00`: the offset of `zone` at instant `ms`. */
export function offsetLabel(ms: number, zone: string) {
  const minutes = Math.round((wallMs(ms, zone) - Math.floor(ms / 1000) * 1000) / 60_000)
  const abs = Math.abs(minutes)
  return `UTC${minutes < 0 ? '-' : '+'}${String(Math.floor(abs / 60)).padStart(2, '0')}:${String(abs % 60).padStart(2, '0')}`
}

/** RFC 3339 instant → long date and time for display (`samedi 19 décembre 2026 à 20:00`), in `zone`. */
export const formatLongDateTime = (iso: string, locale: string, zone: string) =>
  new Intl.DateTimeFormat(locale, { timeZone: zone, dateStyle: 'full', timeStyle: 'short' }).format(Date.parse(iso))

/** RFC 3339 instant → time of day (`18:30`), in `zone`. */
export const formatTime = (iso: string, locale: string, zone: string) =>
  new Intl.DateTimeFormat(locale, { timeZone: zone, timeStyle: 'short' }).format(Date.parse(iso))

/** RFC 3339 instant → `{ day: '19', month: 'déc.' }` in `zone`: the date block of an event row. */
export function dateParts(iso: string, locale: string, zone: string) {
  const parts = new Intl.DateTimeFormat(locale, { timeZone: zone, day: 'numeric', month: 'short' }).formatToParts(Date.parse(iso))
  return { day: parts.find((p) => p.type === 'day')?.value ?? '', month: parts.find((p) => p.type === 'month')?.value ?? '' }
}

/** `ms` ago as `il y a 9 s` / `il y a 6 min` / `il y a 2 h` (a negative age, from clock drift, reads as 0 s). */
export function formatAgo(ms: number, locale: string) {
  const s = Math.max(0, Math.floor(ms / 1000))
  const f = new Intl.RelativeTimeFormat(locale, { style: 'short' })
  return s < 60 ? f.format(-s, 'second') : s < 3600 ? f.format(-Math.floor(s / 60), 'minute') : f.format(-Math.floor(s / 3600), 'hour')
}
