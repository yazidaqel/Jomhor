import Link from 'next/link'
import { ViewTransition } from 'react'
import { eventSlug, saleState, type ShopEvent } from '@/lib/events'
import { defaultLocale, fmt, getDictionary } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { countdown, formatDateTime } from '@/lib/time'
import { Crest, Watermark } from '../brand'
import { ArrowIcon, CalendarIcon, CardIcon, PinIcon, QrIcon, TicketIcon } from '../icons'
import { CountdownTiles } from './countdown'
import { kicker, SaleChip } from './sale'

const d = getDictionary(defaultLocale)
const rise = 'motion-safe:animate-rise stagger'
const at = (i: number) => ({ '--i': i }) as React.CSSProperties
const chip = 'flex items-center gap-2 rounded-chip border border-border bg-ground px-3 py-2 text-sm'

/** W1 body: the next event as the hero, the others as cards (each to its canonical W2 slug), then how it works. */
export function EventList({ events, now }: { events: ShopEvent[]; now: number }) {
  const [next, ...more] = events
  return (
    <main>
      {next ? (
        <Featured e={next} now={now} />
      ) : (
        <section className="mx-auto w-full max-w-[1180px] px-6 pt-12 pb-8">
          <h1 className="font-display text-hero uppercase">{d.home.title}</h1>
          <p className="mt-4 text-body text-text-secondary">{d.home.empty}</p>
        </section>
      )}
      {more.length > 0 && (
        <section aria-labelledby="more-title" className="mx-auto w-full max-w-[1180px] px-6 pt-12">
          <h2 id="more-title" className="font-display text-[32px] font-bold uppercase leading-none">
            {d.home.more}
          </h2>
          <ul className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
            {more.map((e, i) => (
              <li key={e.id} className={rise} style={at(i)}>
                <EventCard e={e} now={now} />
              </li>
            ))}
          </ul>
        </section>
      )}
      <How />
    </main>
  )
}

function Featured({ e, now }: { e: ShopEvent; now: number }) {
  const label = kicker(d, e)
  const href = `/events/${eventSlug(e)}`
  const state = saleState(e, now)
  return (
    <section aria-labelledby="featured-title" className="relative isolate overflow-hidden border-b border-border bg-surface">
      <div aria-hidden="true" className="bg-crowd absolute inset-0 -z-10" />
      <div aria-hidden="true" className="absolute -top-40 -end-40 -z-10 size-[420px] glow opacity-[0.22] md:-top-72 md:-end-48 md:size-[760px]" />
      <div aria-hidden="true" className="absolute -bottom-80 -start-64 -z-10 size-[620px] glow opacity-[0.07] max-md:hidden" />
      <Watermark />
      <div className="mx-auto flex max-w-[1180px] flex-col gap-10 px-6 pt-12 pb-14 md:pt-20 md:pb-20">
        <div className={`flex items-center gap-3 ${rise}`}>
          <Crest size="md" />
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

function EventCard({ e, now }: { e: ShopEvent; now: number }) {
  const label = kicker(d, e)
  return (
    <Link
      href={`/events/${eventSlug(e)}`}
      className="group relative flex h-full flex-col gap-3 overflow-hidden rounded-card border border-border bg-surface p-5 transition duration-200 hover:border-border-muted hover:shadow-2xl hover:shadow-ground motion-safe:hover:-translate-y-1"
    >
      <span aria-hidden="true" className="absolute -top-20 -end-20 size-44 rounded-pill bg-accent opacity-[0.07] blur-2xl transition-opacity duration-200 group-hover:opacity-[0.16]" />
      {label && <p className="text-label uppercase text-accent">{label}</p>}
      <ViewTransition name={`event-title-${e.id}`} share="morph" default="none">
        <h3 className="font-display text-[28px] font-extrabold uppercase leading-none">{e.title}</h3>
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
      <span className="flex items-center gap-1.5 text-body-small font-semibold text-accent">
        {d.home.see}
        <ArrowIcon className="size-4 transition-transform duration-200 motion-safe:group-hover:translate-x-0.5 rtl:motion-safe:group-hover:-translate-x-0.5" />
      </span>
    </Link>
  )
}

/** Three steps from zone to gate: what the fan gets, in the order they get it. */
function How() {
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
