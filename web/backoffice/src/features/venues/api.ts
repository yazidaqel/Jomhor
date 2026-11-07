import type { TFunction } from 'i18next'
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form'
import { z } from 'zod'
import { useOrgMutation, useOrgQuery, type Send } from '@/features/auth/organizer'
import { ApiError, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'

// docs/04-api.md §Organizer /org/venues*, /org/gates/{id}/zones.
export type Venue = { id: string; name: string; city: string | null; capacity: number; timezone: string }
export type Zone = { id: number; code: string; name: string; capacity: number; seated: boolean }
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
export type VenueDetail = Venue & { zones: Zone[]; gates: Gate[] }

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
