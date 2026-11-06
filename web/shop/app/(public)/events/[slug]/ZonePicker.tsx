'use client'

import { useState } from 'react'
import Link from 'next/link'
import type { Zone } from '@/lib/events'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'

type Copy = Pick<Dict, 'event' | 'order' | 'meta'>

/** "Dernières places" at or under this many (DESIGN.md §Lot 1 adjustments, screen 2). */
const LAST_SEATS = 50
// Venue palette (DESIGN.md §Theming): zones take zoneA/B/C in order. Literal class names so Tailwind sees them.
const BARS = ['bg-zone-a', 'bg-zone-b', 'bg-zone-c']

/**
 * W2 zone grid + summary card. One card per price category (a zone can have several). Quantity 1 to
 * min(per_account_limit, remaining): the server re-checks both, including what the fan already holds.
 * `closed` = the sale status text when the sale isn't open; every card is disabled then.
 */
export function ZonePicker({
  eventId,
  zones,
  limit,
  closed,
  copy,
  children,
}: {
  eventId: number
  zones: Zone[]
  limit: number
  closed: string | null
  copy: Copy
  children?: React.ReactNode
}) {
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
  const opt = closed ? undefined : options.find((o) => o.price_category_id === selected && o.remaining > 0)
  const max = opt ? Math.max(1, Math.min(limit, opt.remaining)) : 1
  const n = Math.min(qty, max)
  const total = opt ? opt.price_mad * n : 0 // integer centimes × integer count: exact
  const href = opt && `/orders/new?${new URLSearchParams({ event: String(eventId), price: opt.price_category_id, qty: String(n) })}`

  return (
    <div className="mx-auto flex w-full max-w-[1180px] flex-wrap items-start gap-8 px-6 pt-8 pb-32 md:pb-12">
      <section aria-labelledby="zones-title" className="flex min-w-0 flex-[999_1_560px] flex-col gap-4">
        <h2 id="zones-title" className="font-display text-[28px] font-bold leading-none">
          {copy.event.zonesTitle}
        </h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {options.map((o) => {
            const soldOut = o.remaining === 0
            const on = o === opt
            return (
              <button
                key={o.price_category_id}
                type="button"
                disabled={soldOut || !!closed}
                aria-pressed={on}
                onClick={() => setSelected(o.price_category_id)}
                className={`flex flex-col gap-2 rounded-[18px] border-2 bg-surface p-[18px] text-start disabled:cursor-not-allowed ${
                  on ? 'border-accent' : 'border-border'
                } ${soldOut ? 'text-text-disabled' : 'text-text hover:enabled:border-border-muted'}`}
              >
                <span className="flex items-center gap-2.5">
                  <span aria-hidden="true" className={`h-7 w-2.5 rounded-[5px] ${soldOut ? 'bg-zone-disabled' : o.bar}`} />
                  <span className="text-base font-semibold">{o.zone}</span>
                </span>
                <span className={`text-body-small ${soldOut ? '' : 'text-text-muted'}`}>{o.detail}</span>
                {soldOut ? (
                  <span className="text-sm font-semibold">{copy.event.soldOut}</span>
                ) : (
                  <span className="flex items-baseline justify-between gap-2">
                    <span className="font-display text-[28px] font-extrabold leading-none">{formatDh(o.price_mad)}</span>
                    {o.remaining <= LAST_SEATS ? (
                      <span className="text-xs text-danger">{copy.event.lastSeats}</span>
                    ) : (
                      <span className="text-xs text-success-text">{fmt(copy.event.remaining, { n: new Intl.NumberFormat(copy.meta.intl).format(o.remaining) })}</span>
                    )}
                  </span>
                )}
              </button>
            )
          })}
        </div>
        {children}
      </section>

      <aside
        aria-labelledby="order-title"
        className="flex w-full flex-[1_1_320px] flex-col gap-3.5 rounded-card border border-border bg-surface p-5 md:sticky md:top-6 md:max-w-[380px]"
      >
        <h2 id="order-title" className="font-display text-h2">
          {copy.order.title}
        </h2>
        {opt ? (
          <>
            <div className="flex items-center justify-between gap-3 text-sm">
              <span>{opt.zone}</span>
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
              <span className="font-display text-[30px] font-extrabold leading-none">{formatDh(total)}</span>
            </p>
          </>
        ) : (
          <p className="text-body-small text-text-secondary">{closed ?? copy.order.pick}</p>
        )}
        <Cta href={href} label={copy.order.cta} className="hidden md:flex" />
        <p className="text-center text-xs text-text-muted">{copy.order.hold}</p>
      </aside>

      {/* Phone width: the CTA is a sticky bottom bar (DESIGN.md §Web shop). */}
      {opt && (
        <div className="fixed inset-x-0 bottom-0 z-10 flex items-center gap-4 border-t border-border bg-surface px-5 py-3 md:hidden">
          <p className="flex flex-col">
            <span className="text-xs text-text-muted">{copy.order.total}</span>
            <span className="font-display text-number">{formatDh(total)}</span>
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
  const base = `${className} h-cta-height items-center justify-center rounded-control text-cta`
  return href ? (
    <Link href={href} className={`${base} bg-accent text-on-accent hover:bg-accent-hover`}>
      {label}
    </Link>
  ) : (
    <button type="button" disabled className={`${base} bg-surface2 text-text-disabled`}>
      {label}
    </button>
  )
}
