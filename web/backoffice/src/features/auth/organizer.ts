import { createContext, use } from 'react'
import { useQuery } from '@tanstack/react-query'
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
export function useOrgQuery<T>(key: readonly unknown[], path: string) {
  const { organizer_id: organizerId } = useOrganizer()
  return useQuery({
    queryKey: ['org', organizerId, ...key],
    queryFn: ({ signal }) => api<T>(path, { organizerId, signal }),
  })
}
