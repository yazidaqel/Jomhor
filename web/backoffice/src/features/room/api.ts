import type { TFunction } from 'i18next'
import { z } from 'zod'
import { useOrgMutation, useOrgQuery } from '@/features/auth/organizer'
import { path } from '@/lib/api'
import { utcToWall, wallToUtc } from '@/lib/time'

// GET/PUT /org/events/{id}/room, POST …/room/{pause,resume,end} (04-api §Organizer, §Waiting room).
// ADMIN, OPS write; FINANCE reads.
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
  status: RoomStatus
  pre_queue_min: number
  opens_at: string
  sale_starts_at: string
  admission_rate_per_min: number
  eligibility: Eligibility
  drawn_at: string | null
  figures: RoomFigures
}
/** GET shape; PUT and the transitions answer it too. */
export type RoomResponse = { room: Room | null }
export type RoomBody = { pre_queue_min: number; admission_rate_per_min: number; eligibility: Partial<Eligibility> }

/** DESIGN B13: refetch every 2 s while the room is live (not ENDED, and not before it exists). */
export const ROOM_REFETCH_MS = 2000

const roomPath = (eventId: string) => path('org', 'events', eventId, 'room')

// Under ['events', id]: an event write (sale_starts_at moves opens_at) refetches the room too.
export const useRoom = (eventId: string, live = false) =>
  useOrgQuery<RoomResponse>(['events', eventId, 'room'], roomPath(eventId), {
    refetchInterval: live ? (q) => (q.state.data?.room && q.state.data.room.status !== 'ENDED' ? ROOM_REFETCH_MS : false) : undefined,
  })

export type RoomAction = { kind: 'put'; body: RoomBody } | { kind: 'pause' | 'resume' | 'end' }
export const useRoomMutation = (eventId: string) =>
  useOrgMutation(['events', eventId, 'room'], (a: RoomAction, send) =>
    a.kind === 'put'
      ? send<RoomResponse>({ method: 'PUT', path: roomPath(eventId), body: a.body })
      : send<RoomResponse>({ method: 'POST', path: `${roomPath(eventId)}/${a.kind}` }),
  )

/** Which control each status offers (04: pause from OPEN, resume from PAUSED, end from anything but ENDED). */
export const canPause = (s: RoomStatus) => s === 'OPEN'
export const canResume = (s: RoomStatus) => s === 'PAUSED'
export const canEnd = (s: RoomStatus) => s !== 'ENDED'

// Form fields carry the API names (`eligibility.min_account_age_days`), so VALIDATION_FAILED keys map 1:1.
// `member_joined_before` is a date (`YYYY-MM-DD`): midnight in the venue zone.
export type RoomForm = {
  pre_queue_min: string
  admission_rate_per_min: string
  verified: boolean
  eligibility: { min_account_age_days: string; member_joined_before: string; min_events_attended: string }
}

const isInt = (s: string, min: number, max: number) => /^\d+$/.test(s.trim()) && Number(s) >= min && Number(s) <= max
const DAY = /^\d{4}-\d{2}-\d{2}$/
const dayToUtc = (day: string, zone: string) => wallToUtc(`${day}T00:00`, zone)

/**
 * Ranges per 03 `waiting_rooms`. With "Fans vérifiés" on, each filled rule is checked and at least one is required.
 * `rateOnly` (the room has started): the other fields are the server's and disabled, so only the rate is checked.
 */
export function roomSchema(t: TFunction, zone: string, saleStartsAt: string | null, rateOnly = false) {
  const text = z.string()
  return z
    .object({
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
      if (!isInt(v.pre_queue_min, 0, 1440)) fail(['pre_queue_min'], t('room.form.preQueueInvalid'))
      if (!v.verified) return
      const { min_account_age_days: age, member_joined_before: member, min_events_attended: events } = v.eligibility
      if (age.trim() && !isInt(age, 1, 365)) fail(['eligibility', 'min_account_age_days'], t('room.form.accountAgeInvalid'))
      if (member && (!DAY.test(member) || (saleStartsAt && Date.parse(dayToUtc(member, zone)) > Date.parse(saleStartsAt))))
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

/** PUT body from valid form values. */
export function toBody(v: RoomForm, zone: string): RoomBody {
  const e = v.eligibility
  return {
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
