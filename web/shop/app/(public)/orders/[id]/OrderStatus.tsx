'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { fmt, type Dict } from '@/lib/i18n'
import { canRetryPay, errorText, FINAL, pageClock, pollOrder, postJson, type OrderStatus as Status, type Poll } from '@/lib/order-client'
import { formatTime } from '@/lib/time'

type Copy = Pick<Dict, 'orderPage' | 'errors' | 'meta'>

const cta = 'flex h-cta-height items-center justify-center rounded-control bg-accent px-6 text-cta text-on-accent hover:bg-accent-hover'

/**
 * `/orders/[id]` live part: polls the order every 3 s until it is final (PAID, EXPIRED, CANCELLED, REFUNDED), shows
 * the hold countdown from `reserved_until`, and on PAID links to Mes billets. A 401 or 404 stops it (login link / not
 * found); 5xx and network failures back off; a hidden tab doesn't poll. "Pay again" only per canRetryPay (no double
 * charge while a payment may still be confirmed).
 */
export function OrderStatus({
  id,
  initial,
  now: renderedAt,
  places,
  timezone,
  eventHref,
  copy,
}: {
  id: string
  initial: Status
  now: number
  places: number
  timezone: string | null
  eventHref: string | null
  copy: Copy
}) {
  const c = copy.orderPage
  const [order, setOrder] = useState(initial)
  const [now, setNow] = useState(renderedAt) // wall clock, for the countdown only (server time on the first render)
  const [onPageMs, setOnPageMs] = useState(0) // monotonic, from mount: gates "pay again"
  const [payError, setPayError] = useState<string | null>(null)
  const [paying, setPaying] = useState(false)
  const [gone, setGone] = useState<'UNAUTHORIZED' | 'NOT_FOUND' | null>(null)
  const final = FINAL.includes(order.status)

  useEffect(() => {
    if (final || gone) return
    const abort = new AbortController()
    const fetchOrder = async (): Promise<Poll<Status>> => {
      try {
        const res = await fetch(`/api/orders/${encodeURIComponent(id)}`, { cache: 'no-store', signal: abort.signal })
        if (res.ok) return { order: (await res.json()) as Status }
        if (res.status === 401) return { stop: 'UNAUTHORIZED' }
        if (res.status === 404) return { stop: 'NOT_FOUND' }
        return null
      } catch {
        return null
      }
    }
    pollOrder(fetchOrder, setOrder, abort.signal)
      .then((r) => typeof r === 'string' && setGone(r))
      .catch(() => {}) // aborted on unmount or once final
    const elapsed = pageClock()
    const tick = setInterval(() => {
      setNow(Date.now())
      setOnPageMs(elapsed())
    }, 1000)
    return () => {
      abort.abort()
      clearInterval(tick)
    }
  }, [id, final, gone])

  async function payAgain() {
    setPaying(true)
    const r = await postJson(`/api/orders/${encodeURIComponent(id)}/pay`)
    if (r.ok) return window.location.assign(String(r.data!.redirect_url))
    setPaying(false)
    setPayError(errorText(copy.errors, r))
  }

  const until = order.reserved_until ? Date.parse(order.reserved_until) : NaN
  const left = Math.max(0, Math.floor((until - now) / 1000))

  if (gone) {
    return (
      <div role="alert" className="flex flex-col gap-4">
        <p className="text-body text-text-secondary">{gone === 'UNAUTHORIZED' ? copy.errors.UNAUTHORIZED : c.notFound}</p>
        {gone === 'UNAUTHORIZED' && (
          <Link href={`/login?next=${encodeURIComponent(`/orders/${id}`)}`} className={`${cta} self-start`}>
            {c.login}
          </Link>
        )}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-5">
      {order.status === 'RESERVED' && order.reserved_until && timezone && (
        <p className="flex items-center gap-3 rounded-control border border-accent/50 bg-accent/10 px-4 py-3.5 text-sm text-accent-hover">
          <svg aria-hidden="true" viewBox="0 0 24 24" className="size-5 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <circle cx="12" cy="12" r="9" />
            <path d="M12 7v5l3 2" />
          </svg>
          <span>
            {fmt(c.banner, {
              time: formatTime(order.reserved_until, timezone, copy.meta.intl),
              left: fmt(c.left, { m: Math.floor(left / 60), s: left % 60 }),
            })}
          </span>
        </p>
      )}
      <div aria-live="polite" className="flex flex-col gap-3">
        <h1 className="font-display text-hero uppercase">{title(c, order.status)}</h1>
        <p className="text-body text-text-secondary">{body(c, order.status)}</p>
        {order.status === 'PAID' && order.ticket_count < places && <p className="text-body text-text-secondary">{c.paidOthers}</p>}
      </div>
      {order.status === 'PAID' && (
        <Link href="/me/tickets" className={`${cta} self-start`}>
          {c.tickets}
        </Link>
      )}
      {canRetryPay(order, onPageMs, renderedAt) && (
        <div className="flex flex-col items-start gap-2">
          <p className="text-body-small text-text-muted">{c.retryNote}</p>
          <button
            type="button"
            onClick={payAgain}
            disabled={paying}
            aria-busy={paying}
            className="min-h-touch-min text-body-small font-semibold text-accent hover:underline disabled:opacity-60"
          >
            {c.retryPay}
          </button>
          {payError && (
            <p role="alert" className="text-body-small text-danger">
              {payError}
            </p>
          )}
        </div>
      )}
      {final && order.status !== 'PAID' && eventHref && (
        <Link href={eventHref} className={`${cta} self-start`}>
          {c.back}
        </Link>
      )}
      <p className="flex flex-wrap gap-x-1 rounded-chip bg-ground px-3.5 py-3 text-xs text-text-muted">
        <span>{c.statusLabel}</span>
        <strong className={order.status === 'PAID' ? 'text-success-text' : 'text-accent-hover'}>{statusName(c, order.status)}</strong>
        {!final && <span>{c.live}</span>}
      </p>
    </div>
  )
}

type OrderCopy = Dict['orderPage']
const own = (c: OrderCopy, k: string) => (Object.hasOwn(c, k) ? (c as Record<string, string>)[k] : undefined)
const statusName = (c: OrderCopy, s: string) => own(c, s) ?? s
function title(c: OrderCopy, s: string) {
  if (s === 'PAID') return c.paidTitle
  if (s === 'EXPIRED') return c.expiredTitle
  if (s === 'CANCELLED') return c.cancelledTitle
  if (s === 'REFUNDED') return c.refundedTitle
  return c.pendingTitle
}
function body(c: OrderCopy, s: string) {
  if (s === 'PAID') return c.paid
  if (s === 'EXPIRED') return c.expired
  if (s === 'CANCELLED') return c.cancelled
  if (s === 'REFUNDED') return c.refunded
  return c.pending
}
