import type { TFunction } from 'i18next'
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form'
import { useOrgMutation, useOrgQuery, type Send } from '@/features/auth/organizer'
import { ApiError, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'

// docs/04-api.md §Organizer /org/venues*, /org/gates/{id}/zones.
export type Venue = { id: string; name: string; city: string | null; capacity: number; timezone: string }
export type Zone = { id: number; code: string; name: string; capacity: number; seated: boolean }
export type Gate = { id: string; code: string; name: string; zone_ids: number[] }
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
