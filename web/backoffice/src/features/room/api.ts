import type { TFunction } from 'i18next'
import { z } from 'zod'
import { useQueryClient } from '@tanstack/react-query'
import { useOrganizer, useOrgMutation, useOrgQuery } from '@/features/auth/organizer'
import { path } from '@/lib/api'
import { utcToWall, wallToUtc } from '@/lib/time'

// GET /org/events/{id}/rooms, PUT …/rooms/{phase}, POST …/rooms/{phase}/{pause,resume,end} (04-api §Organizer,
// §Waiting room Phases). ADMIN, OPS write; FINANCE reads.
export type RoomStatus = 'SCHEDULED' | 'PRE_QUEUE' | 'OPEN' | 'PAUSED' | 'ENDED'
export type RoomFigures = {
  joined: number
  /** Ranked and not yet admitted, plus the unranked. */
  waiting: number
  admitted: number
  admitted_last_min: number
  active_holds: number
  max_active_holds: number
  paid: number
  quota: number
  remaining: number
  sold_out: boolean
  /** waiting × 60 ÷ admitted_last_min; null when nothing was admitted in the last minute. */
  estimated_drain_s: number | null
}
export type Eligibility = { min_account_age_days: number | null; member_joined_before: string | null; min_events_attended: number | null }
export type Room = {
  event_id: number
  /** 1–5, contiguous. */
  phase: number
  /** Shown to fans as typed; null = none. */
  label: string | null
  /** This phase's sale start; null for phase 1 (it follows the event's `sale_starts_at`). */
  starts_at: string | null
  status: RoomStatus
  pre_queue_min: number
  opens_at: string
  sale_starts_at: string
  admission_rate_per_min: number
  eligibility: Eligibility
  drawn_at: string | null
  figures: RoomFigures
}
/** GET shape, by phase (empty when none). PUT and the transitions answer that phase's `Room`. */
export type RoomsResponse = { rooms: Room[] }
export type RoomBody = {
  pre_queue_min: number
  admission_rate_per_min: number
  eligibility: Partial<Eligibility>
  label: string | null
  /** Phase ≥ 2 only: phase 1 never sends it (04, else 422). */
  starts_at?: string
}

export const MAX_PHASES = 5

/** DESIGN B13: refetch every 2 s while a phase is live (any not ENDED: a scheduled one starts on its own). */
export const ROOM_REFETCH_MS = 2000
export const isLive = (rooms: Room[] | undefined) => !!rooms?.some((r) => r.status !== 'ENDED')

const roomsPath = (eventId: string, ...rest: string[]) => path('org', 'events', eventId, 'rooms', ...rest)
// Under ['events', id]: an event write (sale_starts_at moves phase 1's opens_at) refetches the rooms too.
const roomsKey = (eventId: string) => ['events', eventId, 'rooms'] as const

export const useRooms = (eventId: string, poll = false) =>
  useOrgQuery<RoomsResponse>(roomsKey(eventId), roomsPath(eventId), {
    refetchInterval: poll ? (q) => (isLive(q.state.data?.rooms) ? ROOM_REFETCH_MS : false) : undefined,
  })

/** That phase replaced (or added) in the list, by phase. */
export const upsertRoom = (data: RoomsResponse | undefined, room: Room): RoomsResponse => ({
  rooms: [...(data?.rooms ?? []).filter((r) => r.phase !== room.phase), room].sort((a, b) => a.phase - b.phase),
})

/** One phase's writes. The answer lands in the cached list at once; the list is refetched after (useOrgMutation). */
export type RoomAction = { kind: 'put'; body: RoomBody } | { kind: 'pause' | 'resume' | 'end' }
export function useRoomMutation(eventId: string, phase: number) {
  const { organizer_id: organizerId } = useOrganizer()
  const queryClient = useQueryClient()
  return useOrgMutation(roomsKey(eventId), async (a: RoomAction, send) => {
    const room =
      a.kind === 'put'
        ? await send<Room>({ method: 'PUT', path: roomsPath(eventId, String(phase)), body: a.body })
        : await send<Room>({ method: 'POST', path: roomsPath(eventId, String(phase), a.kind) })
    queryClient.setQueryData<RoomsResponse>(['org', organizerId, ...roomsKey(eventId)], (d) => upsertRoom(d, room))
    return room
  })
}

/**
 * Q167 current phase: the latest not ENDED whose `opens_at` has passed, else the earliest not ENDED; null when every
 * phase has ended (or there is none).
 */
export function currentPhase(rooms: Room[], now = Date.now()) {
  const open = rooms.filter((r) => r.status !== 'ENDED')
  return open.findLast((r) => Date.parse(r.opens_at) <= now) ?? open[0] ?? null
}

export type EndBody = 'room.endConfirm.body' | 'room.endConfirm.bodyNext' | 'room.endConfirm.bodyOthers'
/**
 * The end confirm's body for `phase`. "Les fans achètent sans file" only when every other phase has ended; the earlier
 * ones ended and a later one scheduled: sales stay closed until it opens (Q169); otherwise the others carry on.
 */
export function endBody(rooms: Room[], phase: number): EndBody {
  const others = rooms.filter((r) => r.phase !== phase)
  if (others.every((r) => r.status === 'ENDED')) return 'room.endConfirm.body'
  const earlierEnded = others.every((r) => r.phase > phase || r.status === 'ENDED')
  if (earlierEnded && others.some((r) => r.phase > phase && r.status === 'SCHEDULED')) return 'room.endConfirm.bodyNext'
  return 'room.endConfirm.bodyOthers'
}

/** Which control each status offers (04: pause from OPEN, resume from PAUSED, end from anything but ENDED). */
export const canPause = (s: RoomStatus) => s === 'OPEN'
export const canResume = (s: RoomStatus) => s === 'PAUSED'
export const canEnd = (s: RoomStatus) => s !== 'ENDED'

// Form fields carry the API names (`eligibility.min_account_age_days`), so VALIDATION_FAILED keys map 1:1.
// `member_joined_before` is a date (`YYYY-MM-DD`): midnight in the venue zone.
export type RoomForm = {
  label: string
  /** Phase ≥ 2: `datetime-local`, wall time in the venue zone. */
  starts_at: string
  pre_queue_min: string
  admission_rate_per_min: string
  verified: boolean
  eligibility: { min_account_age_days: string; member_joined_before: string; min_events_attended: string }
}

const isInt = (s: string, min: number, max: number) => /^\d+$/.test(s.trim()) && Number(s) >= min && Number(s) <= max
const DAY = /^\d{4}-\d{2}-\d{2}$/
const WALL = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/
const dayToUtc = (day: string, zone: string) => wallToUtc(`${day}T00:00`, zone)

/**
 * Ranges per 03 `waiting_rooms`. With "Fans vérifiés" on, each filled rule is checked and at least one is required.
 * `rateOnly` (the room has started): the other fields are the server's and disabled, so only the rate is checked.
 * `saleStartsAt`: the event's (phase 1's). Phase ≥ 2 needs its own "Début de la vente"; the phase order and its range
 * (future, before T0 − 60 min) are the server's (422, PHASE_ORDER).
 */
export function roomSchema(t: TFunction, zone: string, saleStartsAt: string | null, rateOnly = false, phase = 1) {
  const text = z.string()
  return z
    .object({
      label: text,
      starts_at: text,
      pre_queue_min: text,
      admission_rate_per_min: text,
      verified: z.boolean(),
      eligibility: z.object({ min_account_age_days: text, member_joined_before: text, min_events_attended: text }),
    })
    .superRefine((v, ctx) => {
      // One pass for every field, so every error shows at once.
      const fail = (path: string[], message: string) => ctx.addIssue({ code: 'custom', path, message })
      if (!isInt(v.admission_rate_per_min, 1, 6000)) fail(['admission_rate_per_min'], t('room.form.rateInvalid'))
      if (rateOnly) return
      if (v.label.trim().length > 40) fail(['label'], t('room.form.labelInvalid'))
      if (phase >= 2 && !WALL.test(v.starts_at)) fail(['starts_at'], t('room.form.startsAtRequired'))
      const saleStart = phase >= 2 ? (WALL.test(v.starts_at) ? wallToUtc(v.starts_at, zone) : null) : saleStartsAt
      if (!isInt(v.pre_queue_min, 0, 1440)) fail(['pre_queue_min'], t('room.form.preQueueInvalid'))
      if (!v.verified) return
      const { min_account_age_days: age, member_joined_before: member, min_events_attended: events } = v.eligibility
      if (age.trim() && !isInt(age, 1, 365)) fail(['eligibility', 'min_account_age_days'], t('room.form.accountAgeInvalid'))
      if (member && (!DAY.test(member) || (saleStart && Date.parse(dayToUtc(member, zone)) > Date.parse(saleStart))))
        fail(['eligibility', 'member_joined_before'], t('room.form.memberBeforeInvalid'))
      if (events.trim() && !isInt(events, 1, 50)) fail(['eligibility', 'min_events_attended'], t('room.form.eventsAttendedInvalid'))
      if (!age.trim() && !member && !events.trim()) fail(['verified'], t('room.form.ruleRequired'))
    })
}

/** Defaults per DESIGN B13: 30 min, 500 fans/min, "Fans vérifiés" off (7 days ready when switched on). */
export function toForm(room: Room | null, zone: string): RoomForm {
  const e = room?.eligibility
  const verified = !!e && Object.values(e).some((x) => x !== null)
  return {
    label: room?.label ?? '',
    starts_at: room?.starts_at ? utcToWall(room.starts_at, zone) : '',
    pre_queue_min: String(room?.pre_queue_min ?? 30),
    admission_rate_per_min: String(room?.admission_rate_per_min ?? 500),
    verified,
    eligibility:
      e && verified
        ? {
            min_account_age_days: e.min_account_age_days === null ? '' : String(e.min_account_age_days),
            member_joined_before: e.member_joined_before ? utcToWall(e.member_joined_before, zone).slice(0, 10) : '',
            min_events_attended: e.min_events_attended === null ? '' : String(e.min_events_attended),
          }
        : { min_account_age_days: '7', member_joined_before: '', min_events_attended: '' },
  }
}

/** Unset rules are left out (04: each is optional). */
const compact = (e: Partial<Eligibility>): Partial<Eligibility> => Object.fromEntries(Object.entries(e).filter(([, x]) => x !== null && x !== undefined && x !== ''))

/** PUT body from valid form values: `label` trimmed (empty = null), `starts_at` for phase ≥ 2 only. */
export function toBody(v: RoomForm, zone: string, phase: number): RoomBody {
  const e = v.eligibility
  return {
    label: v.label.trim() || null,
    ...(phase >= 2 && { starts_at: wallToUtc(v.starts_at, zone) }),
    pre_queue_min: Number(v.pre_queue_min),
    admission_rate_per_min: Number(v.admission_rate_per_min),
    eligibility: v.verified
      ? compact({
          min_account_age_days: e.min_account_age_days.trim() ? Number(e.min_account_age_days) : null,
          member_joined_before: e.member_joined_before ? dayToUtc(e.member_joined_before, zone) : null,
          min_events_attended: e.min_events_attended.trim() ? Number(e.min_events_attended) : null,
        })
      : {},
  }
}

/** Once the room has left SCHEDULED only the rate may differ (04, else ROOM_STATUS): the rest is sent as the server has it. */
export const rateOnlyBody = (room: Room, rate: number): RoomBody => ({
  label: room.label,
  ...(room.phase >= 2 && room.starts_at && { starts_at: room.starts_at }),
  pre_queue_min: room.pre_queue_min,
  admission_rate_per_min: rate,
  eligibility: compact(room.eligibility),
})

/**
 * DESIGN B13 helper: about 70 % of admitted fans place an order (2.6 places each), so orders/min ≈ rate × 0.7 and the
 * quota sells out in quota ÷ 2.6 ÷ orders/min. An estimate for the organizer, never a promise.
 */
export function rateEstimate(rate: number, quota: number) {
  const orders = rate * 0.7
  return { orders: Math.round(orders), minutes: quota > 0 ? Math.ceil(quota / 2.6 / orders) : null }
}
