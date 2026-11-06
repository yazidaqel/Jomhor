import { saleState, type ShopEventDetail } from '@/lib/events'
import { defaultLocale, fmt, getDictionary } from '@/lib/i18n'
import { countdown, formatDateTime, formatTime } from '@/lib/time'
import { kicker, SaleChip, saleLabel } from '../../sale'
import { ZonePicker } from './ZonePicker'

const d = getDictionary(defaultLocale)
const chip = 'rounded-chip bg-ground px-3 py-2 text-sm'
const pad = (n: number) => String(n).padStart(2, '0')

/** W2 · Page événement: hero (server) + zone grid and summary (client, ZonePicker). */
export function EventView({ e, now }: { e: ShopEventDetail; now: number }) {
  const state = saleState(e, now)
  const label = kicker(d, e)
  const left = countdown(e.starts_at, now)
  return (
    <main>
      <section className="relative overflow-hidden border-b border-border bg-surface">
        <div aria-hidden="true" className="absolute -top-40 -right-30 size-120 rounded-pill bg-accent opacity-10" />
        <div className="relative mx-auto flex max-w-[1180px] flex-wrap items-end justify-between gap-8 px-6 py-10">
          <div className="flex min-w-0 flex-[1_1_520px] flex-col gap-3">
            {label && <p className="text-label uppercase text-accent">{label}</p>}
            <h1 className="font-display text-hero uppercase md:text-[64px]">{e.title}</h1>
            <ul className="mt-1 flex flex-wrap gap-2">
              <li className={chip}>{formatDateTime(e.starts_at, e.timezone, d.meta.intl)}</li>
              <li className={chip}>
                {e.doors_open_at ? fmt(d.event.doors, { venue: e.venue.name, time: formatTime(e.doors_open_at, e.timezone, d.meta.intl) }) : e.venue.name}
              </li>
              <li>
                <SaleChip d={d} event={e} state={state} />
              </li>
            </ul>
          </div>
          {left && (
            <div role="group" aria-label={d.event.countdown} className="flex gap-2">
              {[
                [left.days, d.event.days],
                [left.hours, d.event.hours],
                [left.minutes, d.event.minutes],
              ].map(([n, unit]) => (
                <p key={unit} className="flex min-w-18 flex-col items-center rounded-chip bg-ground px-3 py-2.5">
                  <span className="font-display text-[32px] font-extrabold leading-none">{pad(n as number)}</span>
                  <span className="text-caption uppercase tracking-[1px] text-text-muted">{unit}</span>
                </p>
              ))}
            </div>
          )}
        </div>
      </section>
      <ZonePicker
        eventId={e.id}
        zones={e.zones}
        limit={e.per_account_limit}
        closed={state === 'ON_SALE' ? null : saleLabel(d, e, state)}
        copy={{ event: d.event, order: d.order, meta: d.meta }}
      >
        <div className="flex flex-wrap gap-x-8 gap-y-4 rounded-control border border-border bg-surface px-[18px] py-4 text-body-small text-text-secondary">
          <p>
            <strong className="text-text">{d.event.paymentTitle}</strong>
            {d.event.payment}
          </p>
          <p>
            <strong className="text-text">{d.event.ticketsTitle}</strong>
            {d.event.tickets}
          </p>
          <p>
            <strong className="text-text">{d.event.accessTitle}</strong>
            {d.event.access}
          </p>
        </div>
      </ZonePicker>
    </main>
  )
}
