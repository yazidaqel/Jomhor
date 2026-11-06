import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { TFunction } from 'i18next'
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form'
import { z } from 'zod'
import { useOrganizer, useOrgQuery, type Role } from '@/features/auth/organizer'
import { api, ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'

// docs/04-api.md §Organizer /org/venues*, /org/gates/{id}/zones.
export type Venue = { id: string; name: string; city: string | null; capacity: number }
export type Zone = { id: number; code: string; name: string; capacity: number; seated: boolean }
export type Gate = { id: string; code: string; name: string; zone_ids: number[] }
export type VenueDetail = Venue & { zones: Zone[]; gates: Gate[] }

/** `/a/b/c` with each segment URL-encoded. */
export const path = (...parts: (string | number)[]) => parts.map((p) => `/${encodeURIComponent(p)}`).join('')

export const useVenues = () => useOrgQuery<Venue[]>(['venues'], '/org/venues')
export const useVenue = (id: string) => useOrgQuery<VenueDetail>(['venues', id], path('org', 'venues', id))

/** Lot 0 roles: ADMIN and OPS write; anything else (FINANCE) reads only. */
export const useCanWrite = () => (['ADMIN', 'OPS'] as Role[]).includes(useOrganizer().role)

export type Request = { method: 'POST' | 'PATCH' | 'PUT' | 'DELETE'; path: string; body?: unknown }
export type Send = <R = unknown>(r: Request) => Promise<R>

/**
 * Organizer-scoped write: `run` sends its request(s) with `send`, which carries the same X-Organizer-Id as the
 * query keys. Afterwards, success or not (a NOT_FOUND means our copy is stale), this organizer's venue queries,
 * and only those, are refetched in the background: the mutation settles with its own response, so a dialog can
 * close (or show its error) before the refetch removes the row it was opened from.
 */
export function useVenueMutation<V, R = unknown>(run: (v: V, send: Send) => Promise<R>) {
  const { organizer_id: organizerId } = useOrganizer()
  const queryClient = useQueryClient()
  const send: Send = ({ method, path, body }) =>
    api(path, { method, organizerId, body: body === undefined ? undefined : JSON.stringify(body) })
  return useMutation({
    mutationFn: (v: V) => run(v, send),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['org', organizerId, 'venues'] }),
  })
}

/** i18n key for a venue write error: NOT_FOUND says the page is being refreshed (the write triggered it). */
export const venueErrorKey = (e: unknown) => (e instanceof ApiError && e.code === 'NOT_FOUND' ? 'venues.gone' : errorKey(e))

/** A whole number written with digits only: "12.5", "1e3", "abc" and "" are rejected; spaces around are fine. */
export const integerInput = (message: string, min: number) =>
  z
    .string()
    .trim()
    .regex(/^\d+$/, message)
    .transform(Number)
    .refine((n) => n >= min && n <= 2 ** 31 - 1, message) // the API column is a Postgres integer

/**
 * Maps an API error onto the form: CODE_TAKEN on `code`, VALIDATION_FAILED on the fields named in
 * `details.fields` (snake_case keys; our field names are single words), anything else as the form's root error.
 */
export function showApiError<T extends FieldValues, C, O extends FieldValues>(e: unknown, form: UseFormReturn<T, C, O>, t: TFunction) {
  const fields = Object.keys(form.getValues())
  if (e instanceof ApiError && e.code === 'CODE_TAKEN' && fields.includes('code')) {
    form.setError('code' as Path<T>, { message: t('errors.CODE_TAKEN') }, { shouldFocus: true })
    return
  }
  const invalid = e instanceof ApiError && e.code === 'VALIDATION_FAILED' ? z.record(z.string(), z.unknown()).safeParse(e.details.fields) : null
  const known = invalid?.success ? Object.keys(invalid.data).filter((f) => fields.includes(f)) : []
  if (known.length) {
    known.forEach((f, i) => form.setError(f as Path<T>, { message: t('venues.fieldInvalid') }, { shouldFocus: i === 0 }))
    return
  }
  form.setError('root', { message: t(venueErrorKey(e)) })
}
