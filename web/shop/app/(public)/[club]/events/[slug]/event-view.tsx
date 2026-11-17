import Link from 'next/link'
import { ViewTransition } from 'react'
import { eventPath } from '@/lib/clubs'
import { saleState, type ShopEventDetail } from '@/lib/events'
import { fmt, type Dict } from '@/lib/i18n'
import { loginUrl } from '@/lib/orders'
import { nextLine, roomTime } from '@/lib/queue'
import { countdown, formatDateTime, formatShortDate, formatTime } from '@/lib/time'
import { JOIN_PARAM } from '@/lib/waitlist'
import { Watermark } from '../../../../brand'
import { BackIcon, CalendarIcon, CardIcon, PinIcon, QrIcon, TicketIcon } from '../../../../icons'
import { CountdownTiles } from '../../../countdown'
import { ClubLine } from '../../../event-list'
import { kicker, SaleChip, saleLabel } from '../../../sale'
import { ZonePicker, type RoomView } from './ZonePicker'

const chip = 'flex items-center gap-2 rounded-chip border border-border bg-ground px-3 py-2 text-sm'
const rise = 'motion-safe:animate-rise stagger'

/**
 * The W2 room panel's server part (DESIGN Lot 5): the current phase's line ("{label} · {status}"), the next phase,
 * the rule (matchs or événements by `event_type`, Q182), labels in the venue time zone, the way to W8.
 */
function roomView(e: ShopEventDetail, now: number, d: Dict, signedIn: boolean): RoomView | null {
  const path = e.room && eventPath(e)
  if (!e.room || !path) return null
  const { opens_at, eligibility: el, status, label } = e.room
  const tz = e.timezone
  const intl = d.meta.intl
  const time = roomTime(opens_at, tz, intl, now)
  const rules = el && [
    el.verified_before && fmt(d.room.verifiedBefore, { date: formatShortDate(el.verified_before, tz, intl) }),
    el.member_joined_before && fmt(d.room.memberBefore, { date: formatShortDate(el.member_joined_before, tz, intl) }),
    el.min_events_attended !== null && fmt(e.event_type === 'FOOTBALL_MATCH' ? d.room.minEvents : d.room.minEventsOther, { n: el.min_events_attended }),
  ].filter(Boolean)
  const queue = `${path}/queue`
  const statusText = fmt(d.room.phase[status], { time: status === 'PRE_QUEUE' ? roomTime(e.room.sale_starts_at, tz, intl, now) : time })
  return {
    status,
    phase: e.room.phase,
    banner: label ? `${label} · ${statusText}` : null,
    next: nextLine(d.room, e.room.next, tz, intl, now),
    opensAt: opens_at,
    opensLabel: fmt(d.room.opensAt, { time }),
    rules: rules?.length ? fmt(d.room.eligibility, { rules: rules.join(' · ') }) : null,
    join: signedIn ? queue : loginUrl(queue),
    signedIn,
    now,
  }
}

/** W2 · Page événement: hero (server) + zone grid and summary (client, ZonePicker); Lot 5: the room panel, the waitlist. */
export function EventView({ e, now, d, signedIn = false }: { e: ShopEventDetail; now: number; d: Dict; signedIn?: boolean }) {
  const state = saleState(e, now)
  const label = kicker(d, e)
  const venue = e.venue.city ? `${e.venue.name}, ${e.venue.city}` : e.venue.name
  // The methods this event offers now ("carte bancaire, wallet mobile ou cash en agence").
  const paymentList = new Intl.ListFormat(d.meta.intl, { type: 'disjunction' }).format(e.payment_methods.map((m) => d.event[m]))
  // Sold out, on sale, no live room (04 PUT /fan/waitlist: the room is the queue while a phase is live, Q179).
  const path = eventPath(e)
  const waitlist = state === 'SOLD_OUT' && e.on_sale && !e.room && path ? { eventId: e.id, signedIn, login: loginUrl(`${path}?${JOIN_PARAM}=1`) } : null
  return (
    <main>
      <section className="relative isolate overflow-hidden border-b border-border bg-surface">
        <div aria-hidden="true" className="bg-crowd absolute inset-0 -z-10" />
        <div aria-hidden="true" className="absolute -top-40 -end-40 -z-10 size-[420px] glow opacity-[0.2] md:-top-64 md:size-[680px]" />
        <Watermark club={e.organizer} />
        <div className="mx-auto max-w-[1180px] px-6 pt-6 pb-12 md:pb-16">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <Link
              href="/"
              className="-ms-2 inline-flex min-h-touch-min items-center gap-2 rounded-chip px-2 text-sm text-text-secondary transition-colors hover:text-text"
            >
              <BackIcon className="size-4" />
              {d.event.back}
            </Link>
            {/* Lot 5 step 3: the club's season passes (W9). */}
            {e.organizer.slug && (
              <Link
                href={`/${e.organizer.slug}/passes`}
                className="inline-flex min-h-touch-min items-center gap-2 rounded-chip border border-border px-3 text-sm font-semibold text-text-secondary transition-colors hover:border-accent hover:text-accent"
              >
                <TicketIcon className="size-4" />
                {d.passes.nav}
              </Link>
            )}
          </div>
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
        layout={e.layout}
        limit={e.per_account_limit}
        closed={state === 'ON_SALE' ? null : saleLabel(d, e, state)}
        room={roomView(e, now, d, signedIn)}
        waitlist={waitlist}
        membersNote={e.sale_starts_at ? fmt(d.event.membersNote, { date: formatShortDate(e.sale_starts_at, e.timezone, d.meta.intl) }) : d.event.membersNoteAny}
        copy={{ event: d.event, order: d.order, meta: d.meta, room: d.room, waitlist: d.waitlist, errors: d.errors }}
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
