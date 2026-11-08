import Link from 'next/link'
import { ViewTransition } from 'react'
import { eventPath } from '@/lib/clubs'
import { saleState, type ShopEvent } from '@/lib/events'
import { fmt, isolate, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { countdown, formatDateTime } from '@/lib/time'
import { Crest, Watermark } from '../brand'
import { ArrowIcon, CalendarIcon, CardIcon, CheckIcon, PinIcon, QrIcon, TicketIcon } from '../icons'
import { CountdownTiles } from './countdown'
import { kicker, SaleChip } from './sale'

const rise = 'motion-safe:animate-rise stagger'
const at = (i: number) => ({ '--i': i }) as React.CSSProperties
const chip = 'flex items-center gap-2 rounded-chip border border-border bg-ground px-3 py-2 text-sm'

/** W1 `Tout`: "Suivre" form on unfollowed clubs' cards, "Suivi" on followed ones; `next` = where the form lands. */
export type Follow = { canFollow: (e: ShopEvent) => boolean; next: string }

/**
 * W1 body: the next event as the hero, the others as cards (each to its W2 URL). Empty → `empty`, plus "Voir tout"
 * (`/?club=all`) when `seeAll`.
 */
export function EventList({ events, now, d, empty, seeAll = false, follow }: { events: ShopEvent[]; now: number; d: Dict; empty: string; seeAll?: boolean; follow?: Follow }) {
  const [next, ...more] = events
  return (
    <>
      {next ? (
        <Featured e={next} now={now} d={d} follow={follow} />
      ) : (
        <section className="mx-auto w-full max-w-[1180px] px-6 pt-12 pb-8">
          <h1 className="font-display text-hero uppercase">{d.home.title}</h1>
          <Empty text={empty} seeAll={seeAll} d={d} />
        </section>
      )}
      {more.length > 0 && (
        <section aria-labelledby="more-title" className="mx-auto w-full max-w-[1180px] px-6 pt-12">
          <h2 id="more-title" className="font-display text-[32px] font-bold uppercase leading-none">
            {d.home.more}
          </h2>
          <EventGrid events={more} now={now} d={d} follow={follow} />
        </section>
      )}
    </>
  )
}

/** The screen 1 empty state: one line and, for a club or "Mes clubs", "Voir tout" → `Tout`. */
export function Empty({ text, seeAll, d }: { text: string; seeAll: boolean; d: Dict }) {
  return (
    <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
      <p className="text-body text-text-secondary">{text}</p>
      {seeAll && (
        <Link href="/?club=all" className="flex min-h-touch-min items-center gap-1.5 text-body-small font-semibold text-accent hover:underline">
          {d.clubs.seeAll}
          <ArrowIcon className="size-4" />
        </Link>
      )}
    </div>
  )
}

/** W1 / W7 cards. */
export function EventGrid({ events, now, d, follow }: { events: ShopEvent[]; now: number; d: Dict; follow?: Follow }) {
  return (
    <ul className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
      {events.map((e, i) => (
        <li key={e.id} className={rise} style={at(i)}>
          <EventCard e={e} now={now} d={d} follow={follow} />
        </li>
      ))}
    </ul>
  )
}

/**
 * "Suivre" / "Suivi" as a plain form (POST /api/follows, works with JS off). The accessible name is the visible word,
 * then the club, so several buttons on one page stay distinct.
 */
export function FollowForm({ club, following, next, d, className = '' }: { club: { id: string; name: string }; following: boolean; next: string; d: Dict; className?: string }) {
  const action = following ? d.clubs.following : d.clubs.follow
  return (
    <form method="post" action="/api/follows" className={`relative z-10 shrink-0 ${className}`}>
      <input type="hidden" name="organizer_id" value={club.id} />
      <input type="hidden" name="follow" value={following ? '0' : '1'} />
      <input type="hidden" name="next" value={next} />
      <button
        type="submit"
        aria-pressed={following}
        aria-label={fmt(d.clubs.followA11y, { action, club: isolate(club.name) })}
        className={`flex min-h-touch-min items-center rounded-chip px-3 text-sm font-semibold transition-colors duration-200 ${
          following ? 'bg-accent text-on-accent hover:bg-accent-hover' : 'text-accent hover:bg-accent/10'
        }`}
      >
        {action}
      </button>
    </form>
  )
}

/** The club line above an event title: crest 20 + club name (a link to the club page when `link`), then "Suivre" or "Suivi". */
export function ClubLine({ e, d, link = false, follow }: { e: ShopEvent; d: Dict; link?: boolean; follow?: Follow }) {
  const name = (
    <>
      <Crest club={e.organizer} size={20} />
      <bdi className="truncate">{e.organizer.name}</bdi>
    </>
  )
  return (
    <div data-club-line className="flex min-w-0 items-center gap-2 text-caption text-text-secondary">
      {link && e.organizer.slug ? (
        <Link href={`/${e.organizer.slug}`} className="relative z-10 flex min-h-touch-min min-w-0 items-center gap-2 transition-colors hover:text-text">
          {name}
        </Link>
      ) : (
        <p className="flex min-w-0 items-center gap-2">{name}</p>
      )}
      {follow &&
        (follow.canFollow(e) ? (
          <FollowForm club={{ id: e.organizer_id, name: e.organizer.name }} following={false} next={follow.next} d={d} className="-my-2" />
        ) : (
          <span data-following className="flex shrink-0 items-center gap-1 text-sm font-semibold text-accent">
            <CheckIcon className="size-4" />
            {d.clubs.following}
          </span>
        ))}
    </div>
  )
}

function Featured({ e, now, d, follow }: { e: ShopEvent; now: number; d: Dict; follow?: Follow }) {
  const label = kicker(d, e)
  const href = eventPath(e)!
  const state = saleState(e, now)
  return (
    <section aria-labelledby="featured-title" className="relative isolate overflow-hidden border-b border-border bg-surface">
      <div aria-hidden="true" className="bg-crowd absolute inset-0 -z-10" />
      <div aria-hidden="true" className="absolute -top-40 -end-40 -z-10 size-[420px] glow opacity-[0.22] md:-top-72 md:-end-48 md:size-[760px]" />
      <div aria-hidden="true" className="absolute -bottom-80 -start-64 -z-10 size-[620px] glow opacity-[0.07] max-md:hidden" />
      <Watermark club={e.organizer} />
      <div className="mx-auto flex max-w-[1180px] flex-col gap-10 px-6 pt-12 pb-14 md:pt-20 md:pb-20">
        <div className={`flex flex-col gap-2 ${rise}`}>
          <ClubLine e={e} d={d} link follow={follow} />
          <p className="text-label uppercase text-accent">{label || d.home.featured}</p>
        </div>
        <div className="flex flex-wrap items-end justify-between gap-x-10 gap-y-8">
          <div className="flex min-w-0 flex-[1_1_600px] flex-col gap-5">
            <ViewTransition name={`event-title-${e.id}`} share="morph" default="none">
              <h1
                id="featured-title"
                className="font-display text-hero uppercase text-balance sm:text-[72px] lg:text-[96px] lg:leading-[0.9]"
              >
                {e.title}
              </h1>
            </ViewTransition>
            <ul className={`flex flex-wrap gap-2 ${rise}`} style={at(2)}>
              <li className={chip}>
                <CalendarIcon className="size-4 text-text-muted" />
                {formatDateTime(e.starts_at, e.timezone, d.meta.intl)}
              </li>
              <li className={chip}>
                <PinIcon className="size-4 text-text-muted" />
                {e.venue.city ? `${e.venue.name}, ${e.venue.city}` : e.venue.name}
              </li>
              <li className="flex">
                <SaleChip d={d} event={e} state={state} />
              </li>
            </ul>
          </div>
          <div className={rise} style={at(3)}>
            <CountdownTiles left={countdown(e.starts_at, now)} copy={d.event} />
          </div>
        </div>
        <div className={`flex flex-wrap items-center gap-x-6 gap-y-3 ${rise}`} style={at(4)}>
          <Link
            href={href}
            data-sale={state}
            className="group flex h-cta-height items-center justify-center gap-2 rounded-control bg-accent px-7 text-cta text-on-accent transition-colors duration-200 hover:bg-accent-hover max-sm:w-full"
          >
            {state === 'ON_SALE' ? d.home.book : d.home.see}
            <ArrowIcon className="size-5 transition-transform duration-200 motion-safe:group-hover:translate-x-0.5 rtl:motion-safe:group-hover:-translate-x-0.5" />
          </Link>
          {e.from_price_mad !== null && (
            <p className="font-display text-[28px] font-bold leading-none text-text-secondary">
              {fmt(d.home.from, { price: formatDh(e.from_price_mad) })}
            </p>
          )}
        </div>
      </div>
    </section>
  )
}

/**
 * One card. The whole card opens W2 (the title link is stretched over it); the club line and its "Suivre" form sit
 * above that link, since a form can't live inside one.
 */
function EventCard({ e, now, d, follow }: { e: ShopEvent; now: number; d: Dict; follow?: Follow }) {
  const label = kicker(d, e)
  return (
    <article className="group relative flex h-full flex-col gap-3 overflow-hidden rounded-card border border-border bg-surface p-5 transition duration-200 hover:border-border-muted hover:shadow-2xl hover:shadow-ground motion-safe:hover:-translate-y-1">
      <span aria-hidden="true" className="absolute -top-20 -end-20 size-44 rounded-pill bg-accent opacity-[0.07] blur-2xl transition-opacity duration-200 group-hover:opacity-[0.16]" />
      <ClubLine e={e} d={d} follow={follow} />
      {label && <p className="text-label uppercase text-accent">{label}</p>}
      <ViewTransition name={`event-title-${e.id}`} share="morph" default="none">
        <h3 className="font-display text-[28px] font-extrabold uppercase leading-none">
          <Link href={eventPath(e)!} className="after:absolute after:inset-0">
            {e.title}
          </Link>
        </h3>
      </ViewTransition>
      <p className="text-body-small text-text-secondary">
        {formatDateTime(e.starts_at, e.timezone, d.meta.intl)}
        <br />
        {e.venue.name}
      </p>
      <div className="mt-auto flex flex-wrap items-center justify-between gap-2 pt-2">
        <SaleChip d={d} event={e} state={saleState(e, now)} />
        {e.from_price_mad !== null && (
          <span className="font-display text-number">{fmt(d.home.from, { price: formatDh(e.from_price_mad) })}</span>
        )}
      </div>
      <span aria-hidden="true" className="flex items-center gap-1.5 text-body-small font-semibold text-accent">
        {d.home.see}
        <ArrowIcon className="size-4 transition-transform duration-200 motion-safe:group-hover:translate-x-0.5 rtl:motion-safe:group-hover:-translate-x-0.5" />
      </span>
    </article>
  )
}

/** Three steps from zone to gate: what the fan gets, in the order they get it. */
export function How({ d }: { d: Dict }) {
  const steps = [
    [TicketIcon, d.home.how1Title, d.home.how1],
    [CardIcon, d.home.how2Title, d.home.how2],
    [QrIcon, d.home.how3Title, d.home.how3],
  ] as const
  return (
    <section aria-labelledby="how-title" className="mx-auto w-full max-w-[1180px] px-6 pt-14 pb-16">
      <h2 id="how-title" className="font-display text-[32px] font-bold uppercase leading-none">
        {d.home.howTitle}
      </h2>
      <ol className="mt-6 grid gap-4 md:grid-cols-3">
        {steps.map(([Icon, title, text], i) => (
          <li key={title} className="flex gap-4 rounded-card border border-border bg-surface p-5">
            <span className="flex size-12 shrink-0 items-center justify-center rounded-pill bg-accent/12 text-accent">
              <Icon className="size-6" />
            </span>
            <div className="flex flex-col gap-1">
              <p className="flex items-baseline gap-2">
                <span className="font-display text-xl font-extrabold text-accent">{i + 1}</span>
                <span className="font-display text-h2 uppercase">{title}</span>
              </p>
              <p className="text-body-small text-text-secondary">{text}</p>
            </div>
          </li>
        ))}
      </ol>
    </section>
  )
}
