'use client'

import { useState } from 'react'
import Link from 'next/link'
import type { RoomStatus, Zone } from '@/lib/events'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { mmss } from '@/lib/queue'
import { useAdmission, useServerClock } from '@/lib/queue-hooks'
import { ArrowIcon, CheckIcon } from '../../../../icons'

type Copy = Pick<Dict, 'event' | 'order' | 'meta' | 'room'>

/** W2 room panel (DESIGN Lot 5): what the server knows, formatted in the venue time zone. */
export type RoomView = {
  status: RoomStatus
  opensAt: string
  /** "File d'attente dès 15:30". */
  opensLabel: string
  /** The eligibility line, or null without a rule. */
  rules: string | null
  /** W8, or the login page back to W8 when signed out. */
  join: string
  signedIn: boolean
  /** Server clock at SSR (ms): the flip at `opensAt` and the turn countdown run on it, never the device clock. */
  now: number
}

/** "Dernières places" at or under this many (DESIGN.md §Lot 1 adjustments, screen 2). */
const LAST_SEATS = 50
// Venue palette (DESIGN.md §Theming): zones take zoneA/B/C in order. Literal class names so Tailwind sees them.
const BARS = ['bg-zone-a', 'bg-zone-b', 'bg-zone-c']

/**
 * W2 zone grid + summary card. One card per price category (a zone can have several). Quantity 1 to
 * min(per_account_limit, remaining): the server re-checks both, including what the fan already holds.
 * `closed` = the sale status text when the sale isn't open; every card is disabled then.
 * `room` (Lot 5) = a live waiting room: until this tab holds a running W8 turn, cards show prices but are disabled
 * ("Accès par la file") and the summary becomes the room panel, which flips to "Rejoindre la file" at `opens_at`.
 */
export function ZonePicker({
  eventId,
  zones,
  limit,
  closed,
  room = null,
  copy,
  children,
}: {
  eventId: number
  zones: Zone[]
  limit: number
  closed: string | null
  room?: RoomView | null
  copy: Copy
  children?: React.ReactNode
}) {
  const [serverNow] = useServerClock(room?.now ?? 0, !!room)
  const stored = useAdmission(eventId, serverNow)
  const turn = room ? stored : null // a turn left from a room that has ended means nothing now
  const gated = !!room && !turn
  const options = zones.flatMap((z, i) =>
    z.prices.map((p) => ({
      ...p,
      zone: z.name,
      detail: [z.prices.length > 1 ? p.name : null, z.seated ? copy.event.seated : copy.event.standing, gates(z, copy)]
        .filter(Boolean)
        .join(' · '),
      bar: BARS[i % BARS.length],
    })),
  )
  const [selected, setSelected] = useState<string | null>(null)
  const [qty, setQty] = useState(1)
  const opt = closed || gated ? undefined : options.find((o) => o.price_category_id === selected && o.remaining > 0)
  const max = opt ? Math.max(1, Math.min(limit, opt.remaining)) : 1
  const n = Math.min(qty, max)
  const total = opt ? opt.price_mad * n : 0 // integer centimes × integer count: exact
  const href = opt && `/orders/new?${new URLSearchParams({ event: String(eventId), price: opt.price_category_id, qty: String(n) })}`

  return (
    <div className="mx-auto flex w-full max-w-[1180px] flex-wrap items-start gap-8 px-6 pt-10 pb-32 md:pb-16">
      <section id="zones" aria-labelledby="zones-title" className="flex min-w-0 flex-[999_1_560px] flex-col gap-4">
        <h2 id="zones-title" className="font-display text-[32px] font-bold uppercase leading-none">
          {copy.event.zonesTitle}
        </h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {options.map((o, i) => {
            const soldOut = o.remaining === 0
            const on = o === opt
            return (
              <button
                key={o.price_category_id}
                type="button"
                disabled={soldOut || !!closed || gated}
                aria-pressed={on}
                onClick={() => setSelected(o.price_category_id)}
                style={{ '--i': i } as React.CSSProperties}
                className={`group relative flex flex-col gap-2 rounded-[18px] border-2 p-[18px] text-start transition duration-200 motion-safe:animate-rise stagger disabled:cursor-not-allowed ${
                  on ? 'border-accent bg-accent/[0.07] ring-4 ring-accent/15' : 'border-border bg-surface'
                } ${soldOut ? 'text-text-disabled' : 'text-text hover:enabled:border-border-muted motion-safe:hover:enabled:-translate-y-0.5 hover:enabled:shadow-xl hover:enabled:shadow-ground'}`}
              >
                <span className="flex items-center gap-2.5 pe-8">
                  <span aria-hidden="true" className={`h-7 w-2.5 rounded-[5px] ${soldOut ? 'bg-zone-disabled' : o.bar}`} />
                  <span className="text-base font-semibold">{o.zone}</span>
                </span>
                {!soldOut && !closed && !gated && (
                  <span
                    aria-hidden="true"
                    className={`absolute top-4 end-4 flex size-6 items-center justify-center rounded-pill border-2 transition-colors duration-200 ${
                      on ? 'border-accent bg-accent text-on-accent' : 'border-border-muted group-hover:border-text-muted'
                    }`}
                  >
                    {on && <CheckIcon className="size-4 motion-safe:animate-mark" />}
                  </span>
                )}
                <span className={`text-body-small ${soldOut ? '' : 'text-text-muted'}`}>{o.detail}</span>
                {soldOut ? (
                  <span className="text-sm font-semibold">{copy.event.soldOut}</span>
                ) : (
                  <span className="flex items-baseline justify-between gap-2">
                    <span className="font-display text-[32px] font-extrabold leading-none">{formatDh(o.price_mad)}</span>
                    {o.remaining <= LAST_SEATS ? (
                      <span className="text-xs text-danger">{copy.event.lastSeats}</span>
                    ) : (
                      <span className="text-xs text-success-text">{fmt(copy.event.remaining, { n: new Intl.NumberFormat(copy.meta.intl).format(o.remaining) })}</span>
                    )}
                  </span>
                )}
                {gated && !soldOut && <span className="text-xs font-semibold text-accent">{copy.room.viaQueue}</span>}
              </button>
            )
          })}
        </div>
        {children}
      </section>

      {gated && room ? (
        <RoomPanel room={room} open={room.status !== 'SCHEDULED' || serverNow >= Date.parse(room.opensAt)} copy={copy.room} />
      ) : (
        <aside
          aria-labelledby="order-title"
          className="flex w-full flex-[1_1_320px] flex-col gap-3.5 rounded-card border border-border bg-surface p-5 shadow-2xl shadow-ground md:sticky md:top-24 md:max-w-[380px]"
        >
          <h2 id="order-title" className="font-display text-h2 uppercase">
            {copy.order.title}
          </h2>
          {opt ? (
            <>
              <div className="flex items-center justify-between gap-3 text-sm">
                <span className="flex items-center gap-2.5 font-semibold">
                  <span aria-hidden="true" className={`h-5 w-1.5 rounded-pill ${opt.bar}`} />
                  {opt.zone}
                </span>
                <div role="group" aria-label={copy.order.quantity} className="flex items-center gap-2.5">
                  <Step label={copy.order.less} disabled={n <= 1} onClick={() => setQty(n - 1)} path="M5 12h14" />
                  <output aria-live="polite" className="w-5 text-center font-semibold">
                    {n}
                  </output>
                  <Step label={copy.order.more} disabled={n >= max} onClick={() => setQty(n + 1)} path="M5 12h14M12 5v14" />
                </div>
              </div>
              <Line left={fmt(copy.order.line, { qty: n, price: formatDh(opt.price_mad) })} right={formatDh(total)} />
              <Line left={copy.order.fee} right={formatDh(0)} />
              <hr className="border-border" />
              <p className="flex items-baseline justify-between">
                <span className="font-semibold">{copy.order.total}</span>
                <span className="font-display text-[34px] font-extrabold leading-none">{formatDh(total)}</span>
              </p>
            </>
          ) : (
            <p className="rounded-chip border border-dashed border-border px-4 py-5 text-center text-body-small text-text-secondary">{closed ?? copy.order.pick}</p>
          )}
          {turn && (
            <p data-turn className="flex items-center justify-center gap-2 rounded-chip bg-accent/10 px-3 py-2 text-sm font-semibold text-accent">
              {fmt(copy.room.turnBar, { time: mmss(Math.ceil((Date.parse(turn.expires_at) - serverNow) / 1000)) })}
            </p>
          )}
          <Cta href={href} label={copy.order.cta} className="hidden md:flex" />
          <p className="text-center text-xs text-text-muted">{copy.order.hold}</p>
        </aside>
      )}

      {/* Phone width: the CTA is a sticky bottom bar (DESIGN.md §Web shop). */}
      {opt && (
        <div className="fixed inset-x-0 bottom-0 z-20 flex items-center gap-4 border-t border-border bg-surface px-5 pt-3 pb-[max(12px,env(safe-area-inset-bottom))] motion-safe:animate-rise md:hidden">
          <p className="flex flex-col">
            <span className="text-xs text-text-muted">{copy.order.total}</span>
            <span className="font-display text-number">{formatDh(total)}</span>
            <span className="text-xs text-text-muted">{fmt(copy.order.zoneLine, { qty: n, zone: opt.zone })}</span>
          </p>
          <Cta href={href} label={copy.order.cta} className="flex flex-1" />
        </div>
      )}
    </div>
  )
}

function gates(z: Zone, copy: Copy) {
  return z.gate_codes.length ? fmt(copy.event.gates, { codes: z.gate_codes.join(', ') }) : null
}

function Line({ left, right }: { left: string; right: string }) {
  return (
    <p className="flex justify-between gap-3 text-sm text-text-muted">
      <span>{left}</span>
      <span>{right}</span>
    </p>
  )
}

function Step({ label, disabled, onClick, path }: { label: string; disabled: boolean; onClick: () => void; path: string }) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="flex size-touch-min items-center justify-center rounded-pill border border-border bg-ground text-text disabled:text-text-disabled"
    >
      <svg aria-hidden="true" viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
        <path d={path} />
      </svg>
    </button>
  )
}

/** Continuer → the order step (WS-3, `/orders/new`); a disabled button until a zone is picked. */
function Cta({ href, label, className }: { href: string | undefined; label: string; className: string }) {
  const base = `${className} h-cta-height items-center justify-center gap-2 rounded-control text-cta`
  return href ? (
    <Link href={href} className={`${base} group bg-accent text-on-accent transition-colors duration-200 hover:bg-accent-hover`}>
      {label}
      <ArrowIcon className="size-5 transition-transform duration-200 motion-safe:group-hover:translate-x-0.5 rtl:motion-safe:group-hover:-translate-x-0.5" />
    </Link>
  ) : (
    <button type="button" disabled className={`${base} bg-surface2 text-text-disabled`}>
      {label}
    </button>
  )
}

/**
 * The summary aside while a room gates the sale: one line on the room, the eligibility rule, and the CTA:
 * before `opens_at` disabled "File d'attente dès {time}" (+ "Connecte-toi maintenant" when signed out), then
 * "Rejoindre la file" → W8 (through the login when signed out). Phone width: the CTA is a sticky bottom bar.
 */
function RoomPanel({ room, open, copy }: { room: RoomView; open: boolean; copy: Dict['room'] }) {
  const cta = (phone: boolean) => (
    <Cta href={open ? room.join : undefined} label={open ? copy.join : room.opensLabel} className={phone ? 'flex w-full' : 'hidden md:flex'} />
  )
  return (
    <aside
      aria-labelledby="room-title"
      data-room={open ? 'open' : 'scheduled'}
      className="flex w-full flex-[1_1_320px] flex-col gap-3.5 rounded-card border border-accent/40 bg-surface p-5 shadow-2xl shadow-ground md:sticky md:top-24 md:max-w-[380px]"
    >
      <p className="text-label uppercase text-accent">{copy.title}</p>
      <h2 id="room-title" className="font-display text-h2 uppercase">
        {copy.panelTitle}
      </h2>
      <p className="text-body-small text-text-secondary">{copy.panelIntro}</p>
      {room.rules && (
        <p className="flex gap-2 rounded-control bg-ground p-3 text-body-small text-text-secondary">
          <CheckIcon className="mt-0.5 size-4 shrink-0 text-accent" />
          {room.rules}
        </p>
      )}
      {cta(false)}
      {!open && !room.signedIn && (
        <Link href={room.join} className="flex min-h-touch-min items-center justify-center text-body-small font-semibold text-accent hover:underline">
          {copy.loginNow}
        </Link>
      )}
      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-border bg-surface px-5 pt-3 pb-[max(12px,env(safe-area-inset-bottom))] md:hidden">
        {cta(true)}
      </div>
    </aside>
  )
}
