import { useQueries } from '@tanstack/react-query'
import type { TFunction } from 'i18next'
import { z } from 'zod'
import { useOrganizer, useOrgMutation, useOrgQuery, type Request } from '@/features/auth/organizer'
import type { EventStatus } from '@/features/events/api'
import type { RefundRequired } from '@/features/sales/api'
import type { Venue, VenueDetail, Zone } from '@/features/venues/api'
import { api, path } from '@/lib/api'
import { integerInput } from '@/lib/forms'
import { dhInput, parseDh } from '@/lib/money'
import { DEFAULT_ZONE, utcToWall, wallToUtc } from '@/lib/time'

// docs/04-api.md §Organizer /org/products*, §Products (Lot 5 step 3). Every `*_mad` is integer centimes.
export const PRODUCT_KINDS = ['PASS', 'MEMBERSHIP'] as const
export type ProductKind = (typeof PRODUCT_KINDS)[number]
export type Product = {
  id: string
  kind: ProductKind
  name: string
  price_mad: number
  on_sale: boolean
  sales_close_at: string
  /** PASS: passes on sale; null for MEMBERSHIP. */
  quota: number | null
  /** PASS: passes PENDING, ACTIVE or SUSPENDED; 0 for MEMBERSHIP. */
  sold: number
  /** MEMBERSHIP: the `paid_until` it grants; null for PASS. */
  valid_until: string | null
  series: { id: string; name: string } | null
  /** PASS: its zone, with the venue whose time zone its dates are in. */
  zone: { id: number; code: string; name: string; venue: { id: string; name: string; timezone: string } } | null
  /** 3b: the most installments a fan may choose (1–10, every 30 days, Q195); always 1 for MEMBERSHIP. */
  max_installments: number
}
export type PassEventFigures = {
  event_id: number
  title: string
  starts_at: string
  status: EventStatus
  /** Pass ticket orders of the event. */
  issued: number
  /** ACTIVE passes. */
  expected: number
  /** Σ remaining of the event's PASS categories in the product's zone. */
  pass_remaining: number
}
/** 3b, over the product's passes not CANCELLED: unpaid installments past `due_at`, Σ unpaid, Σ paid (centimes). */
export type InstallmentFigures = { overdue: number; due_mad: number; collected_mad: number }
export type PassFigures = {
  passes: { pending: number; active: number; suspended: number }
  revenue_mad: number
  events: PassEventFigures[]
  installments: InstallmentFigures
}
export type MembershipFigures = { paid_members: number; revenue_mad: number }
export type ProductDetail = Product & { figures: PassFigures | MembershipFigures; refunds_required: RefundRequired[] }

export const useProducts = () => useOrgQuery<Product[]>(['products'], '/org/products')
export const useProduct = (id: string) => useOrgQuery<ProductDetail>(['products', id], path('org', 'products', id))
/** Product write: refetches this organizer's product list and details only. */
export const useProductMutation = () => useOrgMutation(['products'], (r: Request, send) => send<Product>(r))

/**
 * Every venue of the organizer with its zones (one `GET /org/venues/{id}` each, under the same keys as `useVenue`),
 * for the zone picker of a new PASS (grouped by venue). `enabled` false: nothing is fetched.
 * ponytail: one request per venue; a club has one or two. An `/org/zones` read would do it in one.
 */
export function useVenueZones(enabled = true) {
  const { organizer_id: organizerId } = useOrganizer()
  const venues = useOrgQuery<Venue[]>(['venues'], '/org/venues', { enabled })
  const details = useQueries({
    queries: (venues.data ?? []).map((v) => ({
      queryKey: ['org', organizerId, 'venues', v.id],
      queryFn: ({ signal }: { signal: AbortSignal }) => api<VenueDetail>(path('org', 'venues', v.id), { organizerId, signal }),
    })),
  })
  const loaded = details.flatMap((d) => (d.data ? [d.data] : []))
  return {
    venues: loaded,
    isPending: venues.isPending || details.some((d) => d.isPending),
    error: venues.error ?? details.find((d) => d.error)?.error ?? null,
    refetch: () => {
      void venues.refetch()
      details.forEach((d) => void d.refetch())
    },
    /** The venue holding zone `id`, once loaded. */
    venueOf: (id: number | string | undefined) => loaded.find((v) => v.zones.some((z: Zone) => String(z.id) === String(id))),
  }
}

/** The zone a product's dates are entered and shown in: its zone's venue's for a PASS, Africa/Casablanca for a MEMBERSHIP (04 §Notifications). */
export const productZone = (p: Pick<Product, 'kind' | 'zone'>) => (p.kind === 'MEMBERSHIP' ? DEFAULT_ZONE : (p.zone?.venue.timezone ?? DEFAULT_ZONE))

/** `valid_until` as the day it reads in Africa/Casablanca (`30 juin 2027`). */
export const validDay = (iso: string, locale: string) => new Intl.DateTimeFormat(locale, { timeZone: DEFAULT_ZONE, dateStyle: 'long' }).format(Date.parse(iso))

/**
 * Places still to add for a match: the active passes without a ticket, less the PASS places left (the job fills those
 * within 30 s). Over 0 = the organizer must add places in B2 Tarifs (Q187).
 */
export const missingPlaces = (e: PassEventFigures) => Math.max(0, e.expected - e.issued - e.pass_remaining)

const WALL = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/
const DAY = /^\d{4}-\d{2}-\d{2}$/

/**
 * "Valable jusqu'au" is a day; the membership is paid through the end of it (23:59:59 in Africa/Casablanca), so the
 * stored instant reads back as the same day. wallToUtc takes minutes: the 59 s are added after.
 */
export const endOfDay = (day: string) => new Date(Date.parse(wallToUtc(`${day}T23:59`, DEFAULT_ZONE)) + 59_000).toISOString()

/** Form values: text as typed (DH, integers, wall times); fields carry the API names so VALIDATION_FAILED maps 1:1. */
export type ProductForm = {
  name: string
  price_mad: string
  quota: string
  series_id: string
  zone_id: string
  sales_close_at: string
  valid_until: string
  on_sale: boolean
  max_installments: string
}

export const toForm = (p: Product | undefined): ProductForm => ({
  name: p?.name ?? '',
  price_mad: p ? dhInput(p.price_mad) : '',
  quota: p?.quota == null ? '' : String(p.quota),
  series_id: p?.series?.id ?? '',
  zone_id: p?.zone ? String(p.zone.id) : '',
  sales_close_at: p ? utcToWall(p.sales_close_at, productZone(p)) : '',
  valid_until: p?.valid_until ? utcToWall(p.valid_until, DEFAULT_ZONE).slice(0, 10) : '',
  on_sale: p?.on_sale ?? false,
  max_installments: String(p?.max_installments ?? 1),
})

/**
 * zod for the PASS or MEMBERSHIP sheet. `zone`: the time zone wall times are read in (unknown before a PASS zone is
 * picked; the zone is required anyway). `storedClose`: the closing time as the sheet opened it (edit), undefined on
 * create; a new or changed closing time must be in the future (04 POST and PATCH rules).
 */
export function productSchema(t: TFunction, kind: ProductKind, zone: string | undefined, storedClose?: string, now = Date.now()) {
  const at = (wall: string) => Date.parse(wallToUtc(wall, zone ?? 'UTC'))
  const pass = kind === 'PASS'
  return z
    .object({
      name: z.string().trim().min(1, t('products.form.nameInvalid')).max(60, t('products.form.nameInvalid')),
      // DH text → integer centimes (string parse, no float); > 0 (04).
      price_mad: z
        .string()
        .refine((s) => (parseDh(s) ?? 0) > 0, t('products.form.priceInvalid'))
        .transform((s) => parseDh(s)!),
      quota: pass ? integerInput(t('products.form.quotaInvalid'), 0) : z.string(),
      series_id: pass ? z.string().min(1, t('products.form.seriesRequired')) : z.string(),
      zone_id: pass ? z.string().min(1, t('products.form.zoneRequired')) : z.string(),
      sales_close_at: z
        .string()
        .regex(WALL, t('products.form.closeRequired'))
        .refine((s) => s === storedClose || !zone || !WALL.test(s) || at(s) > now, t('products.form.closeInPast')), // zod 4 runs it after a failed regex too
      valid_until: pass ? z.string() : z.string().regex(DAY, t('products.form.validUntilRequired')),
      on_sale: z.boolean(),
      max_installments: pass ? integerInput(t('products.form.installmentsInvalid'), 1, 10) : z.string(),
    })
    .refine((v) => pass || Date.parse(endOfDay(v.valid_until)) > at(v.sales_close_at), {
      message: t('products.form.validBeforeClose'),
      path: ['valid_until'],
      when: ({ value }) => {
        const v = value as { sales_close_at?: unknown; valid_until?: unknown }
        return !pass && typeof v.sales_close_at === 'string' && WALL.test(v.sales_close_at) && typeof v.valid_until === 'string' && DAY.test(v.valid_until)
      },
    })
}
export type ProductValues = z.output<ReturnType<typeof productSchema>>

/** The API value of each field, wall times read in `zone`. */
function apiValues(v: ProductValues, zone: string) {
  return {
    name: v.name,
    price_mad: v.price_mad,
    quota: typeof v.quota === 'number' ? v.quota : undefined,
    series_id: v.series_id || undefined,
    zone_id: v.zone_id ? Number(v.zone_id) : undefined,
    sales_close_at: wallToUtc(v.sales_close_at, zone),
    valid_until: v.valid_until ? endOfDay(v.valid_until) : undefined,
    on_sale: v.on_sale,
    max_installments: typeof v.max_installments === 'number' ? v.max_installments : undefined,
  }
}

/** POST /org/products body: the kind's fields only (04). */
export function createBody(kind: ProductKind, v: ProductValues, zone: string) {
  const { quota, series_id, zone_id, valid_until, max_installments, ...common } = apiValues(v, zone)
  return kind === 'PASS' ? { kind, ...common, quota, series_id, zone_id, max_installments } : { kind, ...common, valid_until }
}

/**
 * PATCH body: the fields edited away from `initial` (the form as the sheet opened it), compared as typed so an
 * untouched time is never re-sent. `kind`, `series_id` and `zone_id` are fixed after create: never sent.
 */
export function patchBody(kind: ProductKind, initial: ProductForm, v: ProductValues, form: ProductForm, zone: string) {
  const values = apiValues(v, zone)
  // max_installments: PASS only (a MEMBERSHIP → 422).
  const editable: readonly (keyof ProductForm)[] = ['name', 'price_mad', 'on_sale', 'sales_close_at', ...(kind === 'PASS' ? (['quota', 'max_installments'] as const) : (['valid_until'] as const))]
  // `form` holds the raw text: "50" and "50,00" are the same price, so the price compares in centimes.
  const changed = (k: keyof ProductForm) => (k === 'price_mad' ? parseDh(initial.price_mad) !== v.price_mad : String(initial[k]).trim() !== String(form[k]).trim())
  return Object.fromEntries(editable.filter(changed).map((k) => [k, values[k]]))
}
