import type { Metadata } from 'next'
import { club, shopName } from '@/lib/club'
import { getEvents } from '@/lib/events'
import { defaultLocale, fmt, getDictionary } from '@/lib/i18n'
import { EventList } from './event-list'

const d = getDictionary(defaultLocale)

// Rendered per request so sale states use the current time; the backend read is cached 60 s (lib/events.ts).
export const dynamic = 'force-dynamic'

export const metadata: Metadata = {
  title: { absolute: shopName },
  description: fmt(d.home.description, { club: club.name }),
}

/** W1 · Événements. */
export default async function HomePage() {
  const events = await getEvents()
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  return <EventList events={events} now={Date.now()} />
}
