import Link from 'next/link'
import { ViewTransition } from 'react'
import { saleState, type ShopEventDetail } from '@/lib/events'
import { fmt, type Dict } from '@/lib/i18n'
import { countdown, formatDateTime, formatTime } from '@/lib/time'
import { Watermark } from '../../../../brand'
import { BackIcon, CalendarIcon, CardIcon, PinIcon, QrIcon, TicketIcon } from '../../../../icons'
import { CountdownTiles } from '../../../countdown'
import { ClubLine } from '../../../event-list'
import { kicker, SaleChip, saleLabel } from '../../../sale'
import { ZonePicker } from './ZonePicker'

const chip = 'flex items-center gap-2 rounded-chip border border-border bg-ground px-3 py-2 text-sm'
const rise = 'motion-safe:animate-rise stagger'

/** W2 · Page événement: hero (server) + zone grid and summary (client, ZonePicker). */
export function EventView({ e, now, d }: { e: ShopEventDetail; now: number; d: Dict }) {
  const state = saleState(e, now)
  const label = kicker(d, e)
  const venue = e.venue.city ? `${e.venue.name}, ${e.venue.city}` : e.venue.name
  // The methods this event offers now ("carte bancaire, wallet mobile ou cash en agence").
  const paymentList = new Intl.ListFormat(d.meta.intl, { type: 'disjunction' }).format(e.payment_methods.map((m) => d.event[m]))
  return (
    <main>
      <section className="relative isolate overflow-hidden border-b border-border bg-surface">
        <div aria-hidden="true" className="bg-crowd absolute inset-0 -z-10" />
        <div aria-hidden="true" className="absolute -top-40 -end-40 -z-10 size-[420px] glow opacity-[0.2] md:-top-64 md:size-[680px]" />
        <Watermark club={e.organizer} />
        <div className="mx-auto max-w-[1180px] px-6 pt-6 pb-12 md:pb-16">
          <Link
            href="/"
            className="-ms-2 inline-flex min-h-touch-min items-center gap-2 rounded-chip px-2 text-sm text-text-secondary transition-colors hover:text-text"
          >
            <BackIcon className="size-4" />
            {d.event.back}
          </Link>
          <div className="mt-4 flex flex-wrap items-end justify-between gap-8 md:mt-8">
            <div className="flex min-w-0 flex-[1_1_560px] flex-col gap-4">
              <div className={`flex flex-col gap-2 ${rise}`}>
                <ClubLine e={e} d={d} link />
                <p className="text-label uppercase text-accent">{label || d.home.featured}</p>
              </div>
              <ViewTransition name={`event-title-${e.id}`} share="morph" default="none">
                <h1
                  className="font-display text-hero uppercase text-balance sm:text-[64px] lg:text-[80px] lg:leading-[0.92]"
                >
                  {e.title}
                </h1>
              </ViewTransition>
              <ul className={`mt-1 flex flex-wrap gap-2 ${rise}`} style={{ '--i': 2 } as React.CSSProperties}>
                <li className={chip}>
                  <CalendarIcon className="size-4 text-text-muted" />
                  {formatDateTime(e.starts_at, e.timezone, d.meta.intl)}
                </li>
                <li className={chip}>
                  <PinIcon className="size-4 text-text-muted" />
                  {e.doors_open_at ? fmt(d.event.doors, { venue, time: formatTime(e.doors_open_at, e.timezone, d.meta.intl) }) : venue}
                </li>
                <li className="flex">
                  <SaleChip d={d} event={e} state={state} />
                </li>
              </ul>
            </div>
            <div className={rise} style={{ '--i': 3 } as React.CSSProperties}>
              <CountdownTiles left={countdown(e.starts_at, now)} copy={d.event} />
            </div>
          </div>
        </div>
      </section>
      <ZonePicker
        eventId={e.id}
        zones={e.zones}
        limit={e.per_account_limit}
        closed={state === 'ON_SALE' ? null : saleLabel(d, e, state)}
        copy={{ event: d.event, order: d.order, meta: d.meta }}
      >
        <ul className="grid gap-3 rounded-control border border-border bg-surface p-4 text-body-small text-text-secondary sm:grid-cols-3">
          {(
            [
              [CardIcon, d.event.paymentTitle, fmt(d.event.payment, { methods: paymentList })],
              [TicketIcon, d.event.ticketsTitle, d.event.tickets],
              [QrIcon, d.event.accessTitle, d.event.access],
            ] as const
          ).map(([Icon, title, text]) => (
            <li key={title} className="flex gap-3">
              <Icon className="mt-0.5 size-5 text-accent" />
              <p>
                <strong className="text-text">{title}</strong>
                {text}
              </p>
            </li>
          ))}
        </ul>
      </ZonePicker>
    </main>
  )
}
