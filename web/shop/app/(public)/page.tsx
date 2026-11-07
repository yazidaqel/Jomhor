import type { Metadata } from 'next'
import { club, shopName } from '@/lib/club'
import { getEvents } from '@/lib/events'
import { fmt } from '@/lib/i18n'
import { getI18n } from '@/lib/i18n/server'
import { EventList } from './event-list'

// Rendered per request so sale states use the current time; the backend read is cached 60 s (lib/events.ts).
export const dynamic = 'force-dynamic'

export async function generateMetadata(): Promise<Metadata> {
  const { d } = await getI18n()
  return { title: { absolute: shopName(d) }, description: fmt(d.home.description, { club: club.name }), alternates: { canonical: '/' } }
}

/** W1 · Événements. */
export default async function HomePage() {
  const [events, { d }] = await Promise.all([getEvents(), getI18n()])
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  return <EventList events={events} now={Date.now()} d={d} />
}
