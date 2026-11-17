import type { TFunction } from 'i18next'
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form'
import { z } from 'zod'
import venueLayouts from '../../../../../docs/venue-layouts.json'
import { useOrgMutation, useOrgQuery, type Send } from '@/features/auth/organizer'
import { ApiError, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'
import { fr } from '@/i18n/fr'

// docs/04-api.md §Organizer /org/venues*, /org/gates/{id}/zones.
export type Venue = { id: string; name: string; city: string | null; capacity: number; timezone: string }
/** `map_slot`: venue map, set only by PUT /org/venues/{id}/map. */
export type Zone = { id: number; code: string; name: string; capacity: number; seated: boolean; map_slot: string | null }
/** `directions`, `latitude`, `longitude`: Lot 4, fan app only (never in the manifest). */
export type Gate = {
  id: string
  code: string
  name: string
  zone_ids: number[]
  directions: string | null
  latitude: number | null
  longitude: number | null
}
/** `layout`: venue map (also on the list, not read there). */
export type VenueDetail = Venue & { layout: string | null; zones: Zone[]; gates: Gate[] }

export const useVenues = () => useOrgQuery<Venue[]>(['venues'], '/org/venues')
export const useVenue = (id: string) => useOrgQuery<VenueDetail>(['venues', id], path('org', 'venues', id))

/** Venue write: refetches this organizer's venue queries only (see useOrgMutation). */
export const useVenueMutation = <V, R = unknown>(run: (v: V, send: Send) => Promise<R>) => useOrgMutation(['venues'], run)

/** i18n key for a venue write error: NOT_FOUND says the page is being refreshed (the write triggered it). */
export const venueErrorKey = (e: unknown) => (e instanceof ApiError && e.code === 'NOT_FOUND' ? 'venues.gone' : errorKey(e))

// Refusals that belong to one field of the form.
const FIELD_ERRORS = { CODE_TAKEN: 'code', VENUE_IN_USE: 'timezone' } as const

/** showFormError plus CODE_TAKEN on `code` and VENUE_IN_USE on `timezone`; the root error uses venueErrorKey. */
export function showApiError<T extends FieldValues, C, O extends FieldValues>(e: unknown, form: UseFormReturn<T, C, O>, t: TFunction) {
  const code = e instanceof ApiError && Object.hasOwn(FIELD_ERRORS, e.code) ? (e.code as keyof typeof FIELD_ERRORS) : null
  if (code && Object.hasOwn(form.getValues(), FIELD_ERRORS[code])) {
    form.setError(FIELD_ERRORS[code] as Path<T>, { message: t(`errors.${code}`) }, { shouldFocus: true })
    return
  }
  showFormError(e, form, t, (e) => t(venueErrorKey(e)))
  // The server refuses some zones the browser accepts (fixed offsets…): same copy as the client-side rule.
  if (e instanceof ApiError && e.code === 'VALIDATION_FAILED' && Object.hasOwn(Object(e.details.fields), 'timezone') && Object.hasOwn(form.getValues(), 'timezone'))
    form.setError('timezone' as Path<T>, { message: t('venues.timezoneInvalid') })
}

/** "33.5731" or "33,5731" (French keyboards), at most 6 decimals, within ±max; "" = not set. */
const coordinate = (max: number, message: string) =>
  z
    .string()
    .trim()
    .transform((s) => s.replace(',', '.'))
    .refine((s) => s === '' || (/^-?\d{1,3}(\.\d{1,6})?$/.test(s) && Math.abs(Number(s)) <= max), message)

/**
 * Gate form → POST/PATCH body. `directions` always sent ("" clears it on PATCH). Coordinates both or neither, sent as
 * JSON numbers: a decimal of at most 9 significant digits round-trips exactly through a double and JSON.stringify, so
 * the server's numeric(8,6)/(9,6) gets the digits typed. Emptying both on a located gate sends the null pair (clears it).
 */
export const gateSchema = (t: TFunction, gate?: Gate) =>
  z
    .object({
      code: z.string().trim().min(1, t('venues.zones.codeRequired')),
      name: z.string().trim().min(1, t('venues.nameRequired')),
      directions: z.string().trim().max(200, t('venues.gates.directionsTooLong')),
      latitude: coordinate(90, t('venues.gates.latitudeInvalid')),
      longitude: coordinate(180, t('venues.gates.longitudeInvalid')),
    })
    .superRefine(({ latitude, longitude }, ctx) => {
      const missing = latitude === '' ? 'latitude' : longitude === '' ? 'longitude' : null
      if (missing && latitude !== longitude) ctx.addIssue({ code: 'custom', path: [missing], message: t('venues.gates.bothOrNeither') })
    })
    .transform(({ latitude, longitude, ...rest }) =>
      latitude
        ? { ...rest, latitude: Number(latitude), longitude: Number(longitude) }
        : gate?.latitude != null
          ? { ...rest, latitude: null, longitude: null }
          : rest,
    )

/** Google Maps search URL for a point (opens the app on phones); coordinates are a gate's, never personal data. */
export const mapUrl = (latitude: number, longitude: number) =>
  `https://www.google.com/maps/search/?${new URLSearchParams({ api: '1', query: `${latitude},${longitude}` })}`

// ---- Venue map (docs/04-api.md §Venue map, PUT /org/venues/{id}/map) ----

type Rect = [x: number, y: number, w: number, h: number]
export type LayoutId = keyof typeof venueLayouts.layouts
/** Slot ids are the keys of their French labels; the map tests check every slot of the JSON has one. */
export type SlotId = keyof typeof fr.venues.map.slots
export type Layout = { center: { kind: 'FIELD' | 'STAGE'; rect: Rect }; slots: { id: SlotId; rect: Rect }[] }
/** The templates, built in from docs/venue-layouts.json (never fetched), in the file's order. */
export const LAYOUTS = venueLayouts.layouts as unknown as Record<LayoutId, Layout>
export const LAYOUT_IDS = Object.keys(LAYOUTS) as LayoutId[]
export const CANVAS = venueLayouts.canvas
/** Select values for "Aucun plan (liste)" and "Pas sur le plan": lowercase, so never a layout or slot id. */
export const NO_LAYOUT = 'none'
export const OFF_MAP = 'off'

type Placement = { zone_id: number; map_slot: SlotId }
export type MapForm = { layout: LayoutId | typeof NO_LAYOUT; zones: { zone_id: number; map_slot: SlotId | typeof OFF_MAP }[] }
export type MapBody = { layout: LayoutId | null; zones: Placement[] }

const isLayout = (id: string | null): id is LayoutId => id != null && Object.hasOwn(LAYOUTS, id)
const slotIds = (layout: MapForm['layout']): string[] => (layout === NO_LAYOUT ? [] : LAYOUTS[layout].slots.map((s) => s.id))

/** Server map → form, one row per zone in the API order. Unknown layout or slot = unmapped (04 §Venue map). */
export function toMapForm(venue: VenueDetail): MapForm {
  const layout = isLayout(venue.layout) ? venue.layout : NO_LAYOUT
  const slots = slotIds(layout)
  return {
    layout,
    zones: venue.zones.map((z) => ({ zone_id: z.id, map_slot: z.map_slot != null && slots.includes(z.map_slot) ? (z.map_slot as SlotId) : OFF_MAP })),
  }
}

/** Form → PUT body: the zones placed on a slot, in the form order; no layout = `{layout: null, zones: []}`. */
export const mapBody = ({ layout, zones }: MapForm): MapBody =>
  layout === NO_LAYOUT ? { layout: null, zones: [] } : { layout, zones: zones.filter((z): z is Placement => z.map_slot !== OFF_MAP) }

/** Keeps the slots that exist in the new layout, takes the others off the map. */
export function switchLayout(form: MapForm, layout: MapForm['layout']): MapForm {
  const slots = slotIds(layout)
  return { layout, zones: form.zones.map((z) => ({ ...z, map_slot: slots.includes(z.map_slot) ? z.map_slot : OFF_MAP })) }
}

export const mapSchema = (t: TFunction) =>
  z
    .object({
      layout: z.enum([NO_LAYOUT, ...LAYOUT_IDS]),
      zones: z.array(z.object({ zone_id: z.number(), map_slot: z.enum([OFF_MAP, ...(Object.keys(fr.venues.map.slots) as SlotId[])]) })),
    })
    .superRefine(({ zones }, ctx) => {
      const seen = new Set<string>()
      zones.forEach(({ map_slot }, i) => {
        if (map_slot === OFF_MAP) return
        if (seen.has(map_slot)) ctx.addIssue({ code: 'custom', path: ['zones', i, 'map_slot'], message: t('venues.map.slotTaken') })
        seen.add(map_slot)
      })
    })

/**
 * The form field a 422 of the PUT lands on: `layout` on the template Select, `zones[i].*` on that zone's Select (i
 * indexes the body, the form has every zone). `zones` itself, or anything else: null (root error).
 */
export function mapErrorField(
  e: unknown,
  form: MapForm,
  body: MapBody,
): { name: 'layout' | `zones.${number}.map_slot`; key: 'venues.map.layoutInvalid' | 'venues.map.zoneGone' | 'venues.map.slotInvalid' } | null {
  if (!(e instanceof ApiError) || e.code !== 'VALIDATION_FAILED') return null
  for (const key of Object.keys(Object(e.details.fields))) {
    if (key === 'layout') return { name: 'layout', key: 'venues.map.layoutInvalid' }
    const m = /^zones\[(\d+)\]\.(zone_id|map_slot)$/.exec(key)
    const row = m ? form.zones.findIndex((z) => z.zone_id === body.zones[Number(m[1])]?.zone_id) : -1
    if (row >= 0) return { name: `zones.${row}.map_slot`, key: m![2] === 'zone_id' ? 'venues.map.zoneGone' : 'venues.map.slotInvalid' }
  }
  return null
}

/** PUT the whole map (answers the venue detail); then this organizer's venue queries are refetched. */
export const useSaveMap = (venueId: string) =>
  useVenueMutation((body: MapBody, send) => send<VenueDetail>({ method: 'PUT', path: `${path('org', 'venues', venueId)}/map`, body }))
