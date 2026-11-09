import type { TFunction } from 'i18next'
import { z } from 'zod'
import { useOrgMutation, useOrgQuery, type Request } from '@/features/auth/organizer'
import type { EventSummary } from '@/features/events/api'
import { path } from '@/lib/api'
import { DEFAULT_ZONE, wallToUtc } from '@/lib/time'

// docs/04-api.md §Organizer /org/members/summary, /org/campaigns*, §Campaigns. ADMIN, OPS write; FINANCE reads.
export type MembersSummary = {
  members: number
  joined_7d: number
  marketing_push: number
  marketing_whatsapp: number
  /** Push opt-in and a live token. */
  reachable_push: number
  tiers: { code: string; name: string; members: number }[]
}

export const CHANNELS = ['PUSH', 'WHATSAPP'] as const
export type Channel = (typeof CHANNELS)[number]
export const AUDIENCES = ['MEMBERS', 'EVENT_HOLDERS'] as const
export type Audience = (typeof AUDIENCES)[number]
export type CampaignStatus = 'DRAFT' | 'SCHEDULED' | 'SENDING' | 'SENT' | 'CANCELLED'

export type CampaignRow = {
  id: string
  channel: Channel
  audience: Audience
  event_id: number | null
  title: string
  status: CampaignStatus
  scheduled_at: string | null
  started_at: string | null
  finished_at: string | null
  created_by: { id: string; name: string }
  recipients: number
  sent: number
  failed: number
}
/** GET /org/campaigns/{id}, also the answer of POST, PATCH, /send and /cancel. */
export type Campaign = CampaignRow & { body: string; audience_estimate: number }

export const CAMPAIGN_REFETCH_MS = 10_000

export const useMembersSummary = () => useOrgQuery<MembersSummary>(['members', 'summary'], '/org/members/summary')
// Polls only while a campaign is draining: its sent / failed counts move then, and nothing else does.
export const useCampaigns = () =>
  useOrgQuery<CampaignRow[]>(['campaigns'], '/org/campaigns', {
    refetchInterval: (q) => (q.state.data?.some((c) => c.status === 'SENDING') ? CAMPAIGN_REFETCH_MS : false),
  })
export const useCampaign = (id: string | undefined) =>
  useOrgQuery<Campaign>(['campaigns', id], path('org', 'campaigns', id ?? ''), { enabled: !!id })
/** Every campaign write answers the GET shape; the list and details are refetched after it. */
export const useCampaignMutation = () => useOrgMutation(['campaigns'], (r: Request, send) => send<Campaign>(r))

/**
 * Q97: a campaign's wall times are in its event's venue zone; no event, Africa/Casablanca. Undefined while the event
 * is not known (events loading or failed): never a guessed zone.
 */
export const zoneOf = (events: EventSummary[] | undefined, eventId: number | string | null): string | undefined =>
  eventId === null || eventId === '' ? DEFAULT_ZONE : events?.find((e) => String(e.id) === String(eventId))?.venue.timezone

export const TITLE_MAX = 60
export const BODY_MAX = 500

/** Composer: lengths after trim (any script), and an event for EVENT_HOLDERS. `event_id` is '' when none is picked. */
export function campaignSchema(t: TFunction) {
  return z
    .object({
      channel: z.enum(CHANNELS),
      audience: z.enum(AUDIENCES),
      event_id: z.string(),
      title: z
        .string()
        .trim()
        .min(1, t('crm.composer.titleRequired'))
        .max(TITLE_MAX, t('crm.composer.tooLong', { max: TITLE_MAX })),
      body: z
        .string()
        .trim()
        .min(1, t('crm.composer.bodyRequired'))
        .max(BODY_MAX, t('crm.composer.tooLong', { max: BODY_MAX })),
    })
    .refine((v) => v.audience !== 'EVENT_HOLDERS' || v.event_id !== '', { path: ['event_id'], message: t('crm.composer.eventRequired') })
}
export type CampaignForm = z.input<ReturnType<typeof campaignSchema>>

const WALL = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/ // datetime-local value
const MAX_AHEAD_MS = 30 * 86_400_000

/** "Programmer": a wall time in `zone` → RFC 3339, strictly in the future and at most 30 days ahead (04 /send). */
export const scheduleSchema = (t: TFunction, zone: string) =>
  z.object({
    scheduled_at: z
      .string()
      .regex(WALL, t('crm.schedule.invalid'))
      .transform((wall) => wallToUtc(wall, zone))
      .refine((iso) => Date.parse(iso) > Date.now(), t('crm.schedule.past'))
      .refine((iso) => Date.parse(iso) <= Date.now() + MAX_AHEAD_MS, t('crm.schedule.tooFar')),
  })
