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
 * ponytail: a wall time skipped by a clock change moves 1 h forward and one repeated by it takes the later instant;
 * fine for event times, revisit if the small hours around a change matter.
 */
export function wallToUtc(wall: string, zone: string): string {
  const [y, mo, d, h, mi] = wall.split(/[-T:]/).map(Number) as [number, number, number, number, number]
  const asUtc = Date.UTC(y, mo - 1, d, h, mi)
  let ms = asUtc - (wallMs(asUtc, zone) - asUtc)
  ms = asUtc - (wallMs(ms, zone) - ms)
  return new Date(ms).toISOString()
}

/** RFC 3339 instant → wall time in `zone` as a `datetime-local` value (`YYYY-MM-DDTHH:mm`). */
export const utcToWall = (iso: string, zone: string) => new Date(wallMs(Date.parse(iso), zone)).toISOString().slice(0, 16)

/** RFC 3339 instant → date and time for display, in `zone`. */
export const formatDateTime = (iso: string, locale: string, zone: string) =>
  new Intl.DateTimeFormat(locale, { timeZone: zone, dateStyle: 'medium', timeStyle: 'short' }).format(Date.parse(iso))

/** `UTC+01:00` / `UTC-03:30` / `UTC+00:00`: the offset of `zone` at instant `ms`. */
export function offsetLabel(ms: number, zone: string) {
  const minutes = Math.round((wallMs(ms, zone) - Math.floor(ms / 1000) * 1000) / 60_000)
  const abs = Math.abs(minutes)
  return `UTC${minutes < 0 ? '-' : '+'}${String(Math.floor(abs / 60)).padStart(2, '0')}:${String(abs % 60).padStart(2, '0')}`
}
