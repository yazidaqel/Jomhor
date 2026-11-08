import 'server-only'
import { cookies } from 'next/headers'
import { cache } from 'react'
import { api, ApiError } from './api'
import { crestUrl, eventSlug, getEvent, LIST_REVALIDATE_S, parseEventId, type ShopEvent } from './events'

// The club directory (04 §Club directory, `GET /fan/organizers*`, public: no Authorization header) and the fan's
// follows (the `follows` cookie, device only). Rows are mapped field by field: nothing else from `organizers` reaches
// HTML or props. A club is content (crest + name), never a skin: no colour is read.

export type Club = { id: string; slug: string; name: string; kind: string; crest_url: string | null }

/** docs/03-schema.sql `organizers_slug` (reserved-slugs.test.ts keeps them equal). */
export const CLUB_SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/
export const isClubSlug = (s: string) => s.length >= 2 && s.length <= 40 && CLUB_SLUG.test(s)

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Followed organizer ids, comma-joined, oldest first (plan §5). Written by app/api/follows/route.ts. */
export const FOLLOWS_COOKIE = 'follows'
export const FOLLOWS_MAX = 50

type Raw = Record<string, unknown>

function toClub(v: unknown): Club | null {
  const r = (v && typeof v === 'object' ? v : {}) as Raw
  if (typeof r.id !== 'string' || typeof r.slug !== 'string' || typeof r.name !== 'string') return null
  return { id: r.id, slug: r.slug, name: r.name, kind: String(r.kind ?? ''), crest_url: crestUrl(r.crest_url) }
}

/** Listed clubs, by name then id (the backend's order). Cached 60 s. */
export const getClubs = cache(async (): Promise<Club[]> => {
  const rows = await api<unknown[]>('/fan/organizers', { next: { revalidate: LIST_REVALIDATE_S } })
  return (Array.isArray(rows) ? rows : []).map(toClub).filter((c) => c !== null)
})

/** One listed club, or null (unknown, unlisted or malformed slug: no backend call for the latter). Cached 60 s. */
export const getClub = cache(async (slug: string): Promise<Club | null> => {
  if (!isClubSlug(slug)) return null
  try {
    return toClub(await api(`/fan/organizers/${slug}`, { next: { revalidate: LIST_REVALIDATE_S } }))
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
})

/** Cookie value → valid UUIDs, de-duplicated, oldest first, at most FOLLOWS_MAX (the newest kept). */
export function parseFollows(raw: string | undefined): string[] {
  const ids = [...new Set((raw ?? '').split(',').map((s) => s.trim().toLowerCase()).filter((s) => UUID.test(s)))]
  return ids.slice(-FOLLOWS_MAX)
}

/** The followed clubs that are in the directory, in follow order. Ids that aren't (unlisted, gone, junk) are dropped. */
export const readFollows = cache(async (): Promise<Club[]> => {
  const ids = parseFollows((await cookies()).get(FOLLOWS_COOKIE)?.value)
  if (ids.length === 0) return []
  const clubs = await getClubs()
  return ids.flatMap((id) => clubs.find((c) => c.id.toLowerCase() === id) ?? [])
})

/** W2 URL: `/{club slug}/events/{id}-{title slug}`, or null for a club without a slug (unlisted: no shop URL). */
export const eventPath = (e: Pick<ShopEvent, 'id' | 'title' | 'organizer'>) =>
  e.organizer.slug ? `/${e.organizer.slug}/events/${eventSlug(e)}` : null

/**
 * The event behind `/{club}/events/{slug}` and its canonical path, or null: unknown, DRAFT or malformed slug, a club
 * without a slug (unlisted: no shop URL), or another club's slug in the URL (plan Q71: 404, not a redirect). The event
 * slug's spelling is not checked here: the page answers 308 to `path`.
 */
export async function eventAt(club: string, slug: string) {
  const id = parseEventId(slug)
  const e = id === null ? null : await getEvent(id)
  if (!e || !e.organizer.slug || e.organizer.slug !== club) return null
  return { e, path: eventPath(e)! }
}
