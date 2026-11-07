'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import type { PendingCashOrder } from '@/lib/orders'
import { formatDateTime } from '@/lib/time'
import { ArrowIcon, ClockIcon } from '../../../icons'

type Copy = Pick<Dict, 'ticketsPage' | 'meta'>

/**
 * W5 "Commandes en attente de paiement" (DESIGN Lot 3), above the tickets: `/api/orders` once, one row card per
 * AWAITING_CASH order (event, code, exact amount, deadline in the venue's zone) linking to its W4 page. Nothing while
 * loading, on error (the ticket list below reports those) or when there is none.
 */
export function PendingOrders({ copy }: { copy: Copy }) {
  const c = copy.ticketsPage
  const intl = copy.meta.intl
  const [orders, setOrders] = useState<PendingCashOrder[]>([])

  useEffect(() => {
    const abort = new AbortController()
    fetch('/api/orders', { cache: 'no-store', signal: abort.signal })
      .then(async (res) => res.ok && setOrders(((await res.json()) as { orders: PendingCashOrder[] }).orders))
      .catch(() => {})
    return () => abort.abort()
  }, [])

  if (orders.length === 0) return null
  return (
    <section aria-labelledby="tickets-pending" className="flex flex-col gap-5">
      <div className="flex flex-col gap-2">
        <h2 id="tickets-pending" className="font-display text-[32px] font-bold uppercase leading-none">
          {c.pendingTitle}
        </h2>
        <p className="text-body-small text-text-secondary">{c.pendingIntro}</p>
      </div>
      <ul className="flex flex-col gap-2.5">
        {orders.map((o, i) => (
          <li key={o.id} className="motion-safe:animate-rise stagger" style={{ '--i': i } as React.CSSProperties}>
            <Link
              href={`/orders/${encodeURIComponent(o.id)}`}
              className="group flex flex-wrap items-center gap-5 rounded-card border border-accent/50 bg-surface p-5 transition duration-200 hover:border-accent motion-safe:hover:-translate-y-0.5"
            >
              <div className="flex min-w-0 flex-[1_1_240px] flex-col gap-1.5">
                <p className="text-sm text-text-muted">{formatDateTime(o.event.starts_at, o.event.timezone, intl)}</p>
                <h3 className="font-display text-[28px] font-extrabold uppercase leading-[0.95] text-balance">{o.event.title}</h3>
                <p className="flex items-center gap-2 text-sm font-semibold text-accent-hover">
                  <ClockIcon className="size-4 shrink-0" />
                  {fmt(c.pendingBefore, { deadline: formatDateTime(o.reserved_until, o.event.timezone, intl) })}
                </p>
              </div>
              <div className="flex flex-col gap-1 rounded-control bg-ticket-paper px-4 py-3 text-ticket-ink">
                <span className="text-[11px] font-semibold uppercase tracking-[1px] text-ticket-muted">{c.pendingCode}</span>
                <span dir="ltr" data-pay-code className="font-display text-[28px] font-extrabold leading-none tracking-[3px]">
                  {o.code}
                </span>
                <span data-amount className="text-sm font-semibold">
                  {formatDh(o.amount_mad)}
                </span>
              </div>
              <ArrowIcon className="size-6 shrink-0 text-accent transition-transform duration-200 motion-safe:group-hover:translate-x-0.5 rtl:motion-safe:group-hover:-translate-x-0.5" />
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
