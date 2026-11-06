import Link from 'next/link'
import { eventSlug, saleState, type ShopEvent } from '@/lib/events'
import { defaultLocale, fmt, getDictionary } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { formatDateTime } from '@/lib/time'
import { kicker, SaleChip } from './sale'

const d = getDictionary(defaultLocale)

/** W1 body: one card per upcoming event, linking to its canonical W2 slug. */
export function EventList({ events, now }: { events: ShopEvent[]; now: number }) {
  return (
    <main className="mx-auto w-full max-w-[1180px] px-6 pt-8 pb-12">
      <h1 className="font-display text-h1 uppercase">{d.home.title}</h1>
      {events.length === 0 ? (
        <p className="mt-4 text-body text-text-secondary">{d.home.empty}</p>
      ) : (
        <ul className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {events.map((e) => (
            <li key={e.id}>
              <EventCard e={e} now={now} />
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}

function EventCard({ e, now }: { e: ShopEvent; now: number }) {
  const label = kicker(d, e)
  return (
    <Link
      href={`/events/${eventSlug(e)}`}
      className="flex h-full flex-col gap-3 rounded-card border border-border bg-surface p-5 hover:border-border-muted"
    >
      {label && <p className="text-label uppercase text-accent">{label}</p>}
      <h2 className="font-display text-h2 uppercase">{e.title}</h2>
      <p className="text-body-small text-text-secondary">
        {formatDateTime(e.starts_at, e.timezone, d.meta.intl)}
        <br />
        {e.venue.name}
      </p>
      <div className="mt-auto flex flex-wrap items-center justify-between gap-2">
        <SaleChip d={d} event={e} state={saleState(e, now)} />
        {e.from_price_mad !== null && (
          <span className="font-display text-number">{fmt(d.home.from, { price: formatDh(e.from_price_mad) })}</span>
        )}
      </div>
      <span className="text-body-small font-semibold text-accent">{d.home.see}</span>
    </Link>
  )
}
