import type { Metadata } from 'next'
import { notFound } from 'next/navigation'
import { getClub, readFollows } from '@/lib/clubs'
import { getEvents } from '@/lib/events'
import { fmt } from '@/lib/i18n'
import { getI18n } from '@/lib/i18n/server'
import { Crest } from '../../brand'
import { Empty, EventGrid, FollowForm, How } from '../event-list'

// Rendered per request (follows cookie, current time); the backend reads are cached 60 s (lib/clubs.ts, lib/events.ts).
export const dynamic = 'force-dynamic'

type Props = { params: Promise<{ club: string }> }

/** A listed club, else 404 (a slug failing the 03 rule never reaches the backend). */
async function load(slug: string) {
  const club = await getClub(slug)
  if (!club) notFound()
  return club
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const [club, { d }] = await Promise.all([load((await params).club), getI18n()])
  const title = fmt(d.clubs.metaTitle, { club: club.name })
  const description = fmt(d.clubs.description, { club: club.name })
  return {
    title,
    description,
    alternates: { canonical: `/${club.slug}` },
    openGraph: { title, description, type: 'website', url: `/${club.slug}`, siteName: d.app.name, locale: d.meta.og },
  }
}

/** W7 · Club: crest 64, name, "Suivre" / "Suivi", then the club's events as W1 cards or the screen 1 empty state. */
export default async function ClubPage({ params }: Props) {
  const club = await load((await params).club)
  const [{ d }, events, follows] = await Promise.all([getI18n(), getEvents([club.id]), readFollows()])
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  const now = Date.now()
  return (
    <main>
      <section className="relative isolate overflow-hidden border-b border-border bg-surface">
        <div aria-hidden="true" className="bg-crowd absolute inset-0 -z-10" />
        <div aria-hidden="true" className="absolute -top-40 -end-40 -z-10 size-[420px] glow opacity-[0.16] md:size-[620px]" />
        <div className="mx-auto flex max-w-[1180px] flex-wrap items-center gap-x-6 gap-y-4 px-6 pt-12 pb-10 motion-safe:animate-rise">
          <Crest club={club} size={64} />
          <h1 className="min-w-0 font-display text-hero uppercase text-balance sm:text-[64px]">
            <bdi>{club.name}</bdi>
          </h1>
          <FollowForm club={club} following={follows.some((c) => c.id === club.id)} next={`/${club.slug}`} d={d} className="rounded-chip border border-border" />
        </div>
      </section>
      <section aria-labelledby="club-events" className="mx-auto w-full max-w-[1180px] px-6 pt-12">
        <h2 id="club-events" className="font-display text-[32px] font-bold uppercase leading-none">
          {d.clubs.upcoming}
        </h2>
        {events.length > 0 ? (
          <EventGrid events={events} now={now} d={d} />
        ) : (
          <Empty text={club.kind === 'SPORTS_CLUB' ? d.clubs.emptyMatch : d.clubs.emptyEvent} seeAll d={d} />
        )}
      </section>
      <How d={d} />
    </main>
  )
}
