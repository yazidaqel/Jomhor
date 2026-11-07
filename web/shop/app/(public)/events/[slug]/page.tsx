import type { Metadata } from 'next'
import { notFound, permanentRedirect } from 'next/navigation'
import { eventSlug, getEvent, parseEventId } from '@/lib/events'
import { getI18n } from '@/lib/i18n/server'
import { formatDateTime } from '@/lib/time'
import { EventView } from './event-view'

// Rendered per request so the sale state and countdown use the current time; the backend read is cached 15 s
// (lib/events.ts). Indexable: SSR HTML, canonical URL, Open Graph image (opengraph-image.tsx).
export const dynamic = 'force-dynamic'

type Props = { params: Promise<{ slug: string }> }

/** The event a slug points to; unknown, DRAFT, another organizer's or a malformed slug → 404. */
async function load(slug: string) {
  const id = parseEventId(slug)
  const e = id === null ? null : await getEvent(id)
  if (!e) notFound()
  return e
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const [e, { d }] = await Promise.all([load((await params).slug), getI18n()])
  const description = `${formatDateTime(e.starts_at, e.timezone, d.meta.intl)} · ${e.venue.name}`
  return {
    title: e.title,
    description,
    alternates: { canonical: `/events/${eventSlug(e)}` },
    // Child openGraph replaces the root one (shallow merge): repeat site name and locale.
    openGraph: { title: e.title, description, type: 'website', url: `/events/${eventSlug(e)}`, siteName: d.app.name, locale: d.meta.og },
  }
}

/** W2 · `/events/{id}-{slug}`; any other spelling of the slug → 308 to the canonical one. */
export default async function EventPage({ params }: Props) {
  const { slug } = await params
  const [e, { d }] = await Promise.all([load(slug), getI18n()])
  const canonical = eventSlug(e)
  if (slug !== canonical) permanentRedirect(`/events/${canonical}`)
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  return <EventView e={e} now={Date.now()} d={d} />
}
