import type { TFunction } from 'i18next'
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form'
import { useOrgMutation, useOrgQuery, type Send } from '@/features/auth/organizer'
import { ApiError, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'

// docs/04-api.md §Organizer /org/venues*, /org/gates/{id}/zones.
export type Venue = { id: string; name: string; city: string | null; capacity: number }
export type Zone = { id: number; code: string; name: string; capacity: number; seated: boolean }
export type Gate = { id: string; code: string; name: string; zone_ids: number[] }
export type VenueDetail = Venue & { zones: Zone[]; gates: Gate[] }

export const useVenues = () => useOrgQuery<Venue[]>(['venues'], '/org/venues')
export const useVenue = (id: string) => useOrgQuery<VenueDetail>(['venues', id], path('org', 'venues', id))

/** Venue write: refetches this organizer's venue queries only (see useOrgMutation). */
export const useVenueMutation = <V, R = unknown>(run: (v: V, send: Send) => Promise<R>) => useOrgMutation(['venues'], run)

/** i18n key for a venue write error: NOT_FOUND says the page is being refreshed (the write triggered it). */
export const venueErrorKey = (e: unknown) => (e instanceof ApiError && e.code === 'NOT_FOUND' ? 'venues.gone' : errorKey(e))

/** showFormError plus CODE_TAKEN on `code`; the root error uses venueErrorKey. */
export function showApiError<T extends FieldValues, C, O extends FieldValues>(e: unknown, form: UseFormReturn<T, C, O>, t: TFunction) {
  if (e instanceof ApiError && e.code === 'CODE_TAKEN' && Object.hasOwn(form.getValues(), 'code')) {
    form.setError('code' as Path<T>, { message: t('errors.CODE_TAKEN') }, { shouldFocus: true })
    return
  }
  showFormError(e, form, t, (e) => t(venueErrorKey(e)))
}
