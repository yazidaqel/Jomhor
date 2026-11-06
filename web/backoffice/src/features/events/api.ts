import type { TFunction } from 'i18next'
import { z } from 'zod'
import { useOrgMutation, useOrgQuery, type Request } from '@/features/auth/organizer'
import { path } from '@/lib/api'
import { integerInput } from '@/lib/forms'
import { utcToWall, wallToUtc } from '@/lib/time'

// docs/04-api.md §Organizer /org/events*, §Event metadata.
export const EVENT_TYPES = ['FOOTBALL_MATCH', 'CONCERT', 'FESTIVAL_DAY', 'CONFERENCE', 'OTHER'] as const
export type EventType = (typeof EVENT_TYPES)[number]
export type EventStatus = 'DRAFT' | 'ON_SALE' | 'GATES_OPEN' | 'CLOSED'
// Event ids are integers (the 4-byte event_id of the QR); route params and query keys use their string form.
export type EventSummary = {
  id: number
  title: string
  event_type: EventType
  starts_at: string
  status: EventStatus
  venue: { id: string; name: string; timezone: string }
  quota: number
  sold: number
}
export type EventDetail = {
  id: number
  venue_id: string
  /** The venue's IANA zone: every wall time of this event is in it. */
  timezone: string
  series_id: string | null
  event_type: EventType
  title: string
  starts_at: string
  doors_open_at: string | null
  /** Sale opening; null = at publish. Cannot be cleared once set (04-api Lot 1). */
  sale_starts_at: string | null
  status: EventStatus
  refund_cutoff_at: string | null
  reentry_policy: 'NONE' | 'ONE_EXIT'
  per_account_limit: number
  metadata: Record<string, unknown>
  created_at: string
}

export const REENTRY_POLICIES = ['NONE', 'ONE_EXIT'] as const
export type Price = { id: string; zone_id: number; name: string; price_mad: number; quota: number; sold: number }

export const useEvents = () => useOrgQuery<EventSummary[]>(['events'], '/org/events')
export const useEvent = (id: string) => useOrgQuery<EventDetail>(['events', id], path('org', 'events', id))
export const usePrices = (eventId: string) => useOrgQuery<Price[]>(['events', eventId, 'prices'], path('org', 'events', eventId, 'prices'))
/** Event write (event, prices, publish): refetches this organizer's event list, details and prices only. */
export const useEventMutation = <R = EventDetail>() => useOrgMutation(['events'], (r: Request, send) => send<R>(r))

/** PATCH is refused outside these (EVENT_NOT_EDITABLE). */
export const EDITABLE: readonly EventStatus[] = ['DRAFT', 'ON_SALE']

/** Known metadata keys per type, all edited as text; `matchday` is sent as an integer. Other keys are kept as they are. */
export const METADATA_KEYS = {
  FOOTBALL_MATCH: ['opponent', 'competition', 'matchday'],
  CONCERT: ['headliner'],
  FESTIVAL_DAY: ['headliner'],
  CONFERENCE: [],
  OTHER: [],
} as const satisfies Record<EventType, readonly string[]>
type MetadataKey = 'opponent' | 'competition' | 'matchday' | 'headliner'

// Form fields carry the API names (`metadata.matchday` included) so VALIDATION_FAILED keys map 1:1.
const WALL = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/ // datetime-local value, wall time in the venue's zone

const HOUR = 3_600_000

/**
 * `zone`: the venue's, in which wall times are read (before a venue is picked, wall order; the venue is required anyway).
 * `doorsRequired` / `saleRequired`: the stored event has that time set, and it cannot be cleared.
 */
export function eventSchema(t: TFunction, zone: string | undefined, doorsRequired: boolean, saleRequired = false) {
  // Instants, as the server compares them (wall order differs across a clock change).
  const at = (wall: string) => Date.parse(wallToUtc(wall, zone ?? 'UTC'))
  const text = z.string().trim()
  return z
    .object({
      event_type: z.enum(EVENT_TYPES),
      title: text.min(1, t('events.form.titleRequired')),
      starts_at: z.string().regex(WALL, t('events.form.startsAtRequired')),
      doors_open_at: z
        .string()
        .refine((s) => (s === '' ? !doorsRequired : WALL.test(s)), t(doorsRequired ? 'events.form.doorsRequired' : 'events.form.dateInvalid')),
      sale_starts_at: z
        .string()
        .refine((s) => (s === '' ? !saleRequired : WALL.test(s)), t(saleRequired ? 'events.form.saleRequired' : 'events.form.dateInvalid')),
      venue_id: z.string().min(1, t('events.form.venueRequired')),
      per_account_limit: integerInput(t('events.form.limitInvalid'), 1, 20),
      reentry_policy: z.enum(REENTRY_POLICIES),
      // Raw text for every type's fields; only the current type's are checked (below) and sent (toBody). The others
      // may hold a hidden value from a type picked before, or a stored key that is free for this type.
      metadata: z.object({ opponent: z.string(), competition: z.string(), headliner: z.string(), matchday: z.string() }),
    })
    .refine((v) => isMatchday(v.metadata.matchday), {
      message: t('events.form.matchdayInvalid'),
      path: ['metadata', 'matchday'],
      when: ({ value }) => {
        const v = value as { event_type?: unknown; metadata?: { matchday?: unknown } }
        return v.event_type === 'FOOTBALL_MATCH' && typeof v.metadata?.matchday === 'string'
      },
    })
    .refine((v) => !v.doors_open_at || at(v.doors_open_at) <= at(v.starts_at), {
      message: t('events.form.doorsAfterStart'),
      path: ['doors_open_at'],
      // Also when other fields are invalid, so every error shows at once; only needs both times to be valid.
      when: ({ value }) => {
        const v = value as { starts_at?: unknown; doors_open_at?: unknown }
        return typeof v.starts_at === 'string' && WALL.test(v.starts_at) && typeof v.doors_open_at === 'string' && WALL.test(v.doors_open_at)
      },
    })
    // Server rule: sale_starts_at strictly before starts_at − 60 min.
    .refine((v) => !v.sale_starts_at || at(v.sale_starts_at) < at(v.starts_at) - HOUR, {
      message: t('events.form.saleBeforeStart'),
      path: ['sale_starts_at'],
      when: ({ value }) => {
        const v = value as { starts_at?: unknown; sale_starts_at?: unknown }
        return typeof v.starts_at === 'string' && WALL.test(v.starts_at) && typeof v.sale_starts_at === 'string' && WALL.test(v.sale_starts_at)
      },
    })
}
export type EventForm = z.input<ReturnType<typeof eventSchema>>
export type EventValues = z.output<ReturnType<typeof eventSchema>>

const isMatchday = (s: string) => s.trim() === '' || (/^\d+$/.test(s.trim()) && Number(s) <= 2 ** 31 - 1)
const str = (v: unknown) => (v === undefined || v === null ? '' : String(v))

export const emptyForm = (): EventForm => ({
  event_type: 'FOOTBALL_MATCH',
  title: '',
  starts_at: '',
  doors_open_at: '',
  sale_starts_at: '',
  venue_id: '',
  per_account_limit: '4',
  reentry_policy: 'NONE',
  metadata: { opponent: '', competition: '', headliner: '', matchday: '' },
})

export const toForm = (e: EventDetail): EventForm => ({
  event_type: e.event_type,
  title: e.title,
  starts_at: utcToWall(e.starts_at, e.timezone),
  doors_open_at: e.doors_open_at ? utcToWall(e.doors_open_at, e.timezone) : '',
  sale_starts_at: e.sale_starts_at ? utcToWall(e.sale_starts_at, e.timezone) : '',
  venue_id: e.venue_id,
  per_account_limit: String(e.per_account_limit),
  reentry_policy: e.reentry_policy,
  metadata: { opponent: str(e.metadata.opponent), competition: str(e.metadata.competition), headliner: str(e.metadata.headliner), matchday: str(e.metadata.matchday) },
})

/**
 * The full API body for these values, wall times read in `zone`. Metadata holds the type's known keys that are filled in (empty ones are left
 * out: a known key sent as null fails) plus every other key of `existing`, since a PATCH replaces the whole object.
 */
function toBody(v: EventValues, existing: Record<string, unknown>, zone: string) {
  const known: readonly MetadataKey[] = METADATA_KEYS[v.event_type]
  const metadata: Record<string, unknown> = Object.fromEntries(Object.entries(existing).filter(([k]) => !(known as readonly string[]).includes(k)))
  for (const k of known) {
    const s = v.metadata[k].trim()
    if (s !== '') metadata[k] = k === 'matchday' ? Number(s) : s
  }
  return {
    venue_id: v.venue_id,
    event_type: v.event_type,
    title: v.title,
    starts_at: wallToUtc(v.starts_at, zone),
    ...(v.doors_open_at && { doors_open_at: wallToUtc(v.doors_open_at, zone) }),
    ...(v.sale_starts_at && { sale_starts_at: wallToUtc(v.sale_starts_at, zone) }),
    per_account_limit: v.per_account_limit,
    reentry_policy: v.reentry_policy,
    metadata,
  }
}

/** POST /org/events body, times in the chosen venue's `zone`; no metadata when there is none. */
export function createBody(v: EventValues, zone: string) {
  const { metadata, ...body } = toBody(v, {}, zone)
  return Object.keys(metadata).length ? { ...body, metadata } : body
}

/**
 * PATCH body: only the fields that differ from `event`, the latest server copy (metadata, when it differs, in full).
 * The form follows that copy for the fields the user has not touched, so a change saved by someone else meanwhile
 * is neither re-sent nor reverted.
 */
export function patchBody(event: EventDetail, v: EventValues) {
  const f = toForm(event)
  const before: Record<string, unknown> = toBody({ ...f, per_account_limit: event.per_account_limit }, event.metadata, event.timezone)
  return Object.fromEntries(Object.entries(toBody(v, event.metadata, event.timezone)).filter(([k, x]) => JSON.stringify(x) !== JSON.stringify(before[k])))
}
