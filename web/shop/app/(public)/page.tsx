import type { Metadata } from 'next'
import Link from 'next/link'
import { getClubs, readFollows, type Club } from '@/lib/clubs'
import { getEvents } from '@/lib/events'
import type { Dict } from '@/lib/i18n'
import { getI18n } from '@/lib/i18n/server'
import { Crest } from '../brand'
import { EventList, How } from './event-list'

// Rendered per request (follows cookie, current time); the backend reads are cached 60 s (lib/events.ts, lib/clubs.ts).
export const dynamic = 'force-dynamic'

export async function generateMetadata(): Promise<Metadata> {
  const { d } = await getI18n()
  return { title: d.header.shop, description: d.home.description, alternates: { canonical: '/' } }
}

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> }

/** Plan Q79: `mine`, `all` or a listed club's slug; absent = `mine` when following, else `all`; anything else = `all`. */
function pickView(q: unknown, clubs: Club[], follows: Club[]): 'mine' | 'all' | Club {
  if (q === undefined || q === 'mine') return follows.length ? 'mine' : 'all'
  return clubs.find((c) => c.slug === q) ?? 'all'
}

const isSports = (clubs: Club[]) => clubs.length > 0 && clubs.every((c) => c.kind === 'SPORTS_CLUB')

/** W1 · Événements: chip row, events of the chosen view, the clubs strip, how it works. */
export default async function HomePage({ searchParams }: Props) {
  const [{ d }, clubs, follows, q] = await Promise.all([getI18n(), getClubs(), readFollows(), searchParams.then((p) => p.club)])
  const view = pickView(q, clubs, follows)
  const shown = view === 'all' ? clubs : view === 'mine' ? follows : [view]
  const events = await getEvents(view === 'all' ? 'all' : shown.map((c) => c.id))
  const followed = new Set(follows.map((c) => c.id))
  const chips = [...follows, ...(typeof view === 'object' && !followed.has(view.id) ? [view] : [])]
  return (
    <main>
      <nav aria-label={d.clubs.filter} className="mx-auto w-full max-w-[1180px] overflow-x-auto px-6 py-3">
        <ul className="flex w-max gap-2">
          {follows.length > 0 && <Chip href="/?club=mine" current={view === 'mine'} label={d.clubs.mine} />}
          {chips.map((c) => (
            <Chip key={c.id} href={`/?club=${c.slug}`} current={typeof view === 'object' && view.id === c.id} label={c.name} club={c} />
          ))}
          <Chip href="/?club=all" current={view === 'all'} label={d.clubs.all} />
        </ul>
      </nav>
      <EventList
        events={events}
        // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
        now={Date.now()}
        d={d}
        empty={view === 'all' ? d.home.empty : isSports(shown) ? d.clubs.emptyMatch : d.clubs.emptyEvent}
        seeAll={view !== 'all'}
        // Following from Tout stays on Tout (DESIGN §Multi-club: "Suivre" becomes "Suivi"); the new chips appear above.
        follow={view === 'all' ? { canFollow: (e) => !followed.has(e.organizer_id), next: '/?club=all' } : undefined}
      />
      {clubs.length > 0 && <ClubsStrip clubs={clubs} d={d} />}
      <How d={d} />
    </main>
  )
}

/** A screen 1 chip as a link: 36 px pill in a 44 px target; the selected one is amber with `aria-current="page"`. */
function Chip({ href, current, label, club }: { href: string; current: boolean; label: string; club?: Club }) {
  return (
    <li>
      <Link href={href} aria-current={current ? 'page' : undefined} className="group flex min-h-touch-min items-center">
        <span
          className={`flex h-9 items-center gap-2 rounded-pill px-4 text-sm font-semibold whitespace-nowrap transition-colors duration-200 ${
            current ? 'bg-accent text-on-accent' : 'border border-border bg-surface text-text-secondary group-hover:text-text'
          }`}
        >
          {club && <Crest club={club} size={20} standIn={current ? 'bg-on-accent text-accent' : undefined} />}
          <bdi>{label}</bdi>
        </span>
      </Link>
    </li>
  )
}

/** Every listed club: crest 56 + name, each to its club page. Scrolls sideways on phones, wraps from md. */
function ClubsStrip({ clubs, d }: { clubs: Club[]; d: Dict }) {
  return (
    <section aria-labelledby="clubs-title" className="mx-auto w-full max-w-[1180px] px-6 pt-14">
      <h2 id="clubs-title" className="font-display text-[32px] font-bold uppercase leading-none">
        {d.clubs.stripTitle}
      </h2>
      <ul className="mt-6 flex gap-3 overflow-x-auto pb-2 md:flex-wrap md:overflow-visible">
        {clubs.map((c) => (
          <li key={c.id} className="shrink-0">
            <Link
              href={`/${c.slug}`}
              className="flex min-h-touch-min w-36 flex-col items-center gap-3 rounded-card border border-border bg-surface p-4 text-center transition-colors duration-200 hover:border-border-muted"
            >
              <Crest club={c} size={56} />
              <bdi className="font-display text-lg font-bold uppercase leading-tight">{c.name}</bdi>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
