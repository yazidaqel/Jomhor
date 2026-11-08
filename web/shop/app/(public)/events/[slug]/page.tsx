import { notFound, permanentRedirect } from 'next/navigation'
import { eventPath } from '@/lib/clubs'
import { getEvent, parseEventId } from '@/lib/events'

export const dynamic = 'force-dynamic'

/**
 * The single-club URL `/events/{id}-{slug}` (shared links, WhatsApp previews): 308 to `/{club}/events/{id}-{slug}`.
 * Unknown, DRAFT, malformed, or a club without a slug (no shop URL) → 404.
 */
export default async function OldEventPage({ params }: { params: Promise<{ slug: string }> }) {
  const id = parseEventId((await params).slug)
  const e = id === null ? null : await getEvent(id)
  const path = e && eventPath(e)
  if (!path) notFound()
  permanentRedirect(path)
}
