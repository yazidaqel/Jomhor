import { createContext, use } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

export type Role = 'ADMIN' | 'OPS' | 'FINANCE' | 'GATE' | 'SUPERVISOR'
export type Membership = { organizer_id: string; organizer_name: string; role: Role }
export type Me = {
  user: { id: string; phone: string; display_name: string | null; locale: string | null }
  memberships: Membership[]
}

/** GET /org/me: not organizer-scoped (no X-Organizer-Id), feeds the switcher. */
export function useMe(enabled = true) {
  return useQuery({ queryKey: ['me'], queryFn: ({ signal }) => api<Me>('/org/me', { signal }), enabled })
}

/** The selected organizer, provided by the shell once /org/me has loaded. */
export const OrganizerContext = createContext<Membership | null>(null)

export function useOrganizer(): Membership {
  const membership = use(OrganizerContext)
  if (!membership) throw new Error('useOrganizer() outside the shell')
  return membership
}

/**
 * Organizer-scoped GET. The key is `['org', organizerId, ...key]` and the request carries the same
 * organizer as X-Organizer-Id, so two organizers' data never share a cache entry.
 * Use this (and `useOrganizer().organizer_id` in mutations) for every /org/* call except /org/me.
 */
export function useOrgQuery<T>(key: readonly unknown[], path: string, options?: { refetchInterval?: number }) {
  const { organizer_id: organizerId } = useOrganizer()
  return useQuery({
    queryKey: ['org', organizerId, ...key],
    queryFn: ({ signal }) => api<T>(path, { organizerId, signal }),
    ...options,
  })
}

/** Lot 0 roles: ADMIN and OPS write; anything else (FINANCE) reads only. */
export const useCanWrite = () => (['ADMIN', 'OPS'] as Role[]).includes(useOrganizer().role)

export type Request = { method: 'POST' | 'PATCH' | 'PUT' | 'DELETE'; path: string; body?: unknown }
export type Send = <R = unknown>(r: Request) => Promise<R>

/**
 * Organizer-scoped write: `run` sends its request(s) with `send`, which carries the same X-Organizer-Id as the
 * query keys. Afterwards, success or not (a NOT_FOUND means our copy is stale), this organizer's queries under
 * `scope`, and only those, are refetched in the background: the mutation settles with its own response, so a dialog
 * can close (or show its error) before the refetch removes the row it was opened from.
 */
export function useOrgMutation<V, R = unknown>(scope: readonly unknown[], run: (v: V, send: Send) => Promise<R>) {
  const { organizer_id: organizerId } = useOrganizer()
  const queryClient = useQueryClient()
  const send: Send = ({ method, path, body }) =>
    api(path, { method, organizerId, body: body === undefined ? undefined : JSON.stringify(body) })
  return useMutation({
    mutationFn: (v: V) => run(v, send),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['org', organizerId, ...scope] }),
  })
}
