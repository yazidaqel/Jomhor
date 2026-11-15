import type { Metadata } from 'next'
import { notFound, permanentRedirect } from 'next/navigation'
import { eventAt } from '@/lib/clubs'
import { currentSession } from '@/lib/http'
import { getI18n } from '@/lib/i18n/server'
import { formatDateTime } from '@/lib/time'
import { EventView } from './event-view'

// Rendered per request so the sale state and countdown use the current time; the backend read is cached 15 s
// (lib/events.ts). Indexable: SSR HTML, canonical URL, Open Graph image (opengraph-image.tsx).
export const dynamic = 'force-dynamic'

type Params = { club: string; slug: string }
type Props = { params: Promise<Params> }

/** The event at this URL, else 404 (lib/clubs.ts `eventAt`: unknown, unlisted club, or another club's URL, Q71). */
async function load({ club, slug }: Params) {
  const hit = await eventAt(club, slug)
  if (!hit) notFound()
  return hit
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const [{ e, path }, { d }] = await Promise.all([load(await params), getI18n()])
  const description = `${formatDateTime(e.starts_at, e.timezone, d.meta.intl)} · ${e.venue.name}`
  return {
    title: e.title,
    description,
    alternates: { canonical: path },
    // Child openGraph replaces the root one (shallow merge): repeat site name and locale.
    openGraph: { title: e.title, description, type: 'website', url: path, siteName: d.app.name, locale: d.meta.og },
  }
}

/** W2 · `/{club}/events/{id}-{slug}`; any other spelling of the event slug → 308 to the canonical URL. */
export default async function EventPage({ params }: Props) {
  const p = await params
  const [{ e, path }, { d }] = await Promise.all([load(p), getI18n()])
  if (`/${p.club}/events/${p.slug}` !== path) permanentRedirect(path)
  // Signed in or not (the room panel's way to W8); the session itself never leaves the server.
  const signedIn = e.room ? !!(await currentSession()) : false
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  return <EventView e={e} now={Date.now()} d={d} signedIn={signedIn} />
}
