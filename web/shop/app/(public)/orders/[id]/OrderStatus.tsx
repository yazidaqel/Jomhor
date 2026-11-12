'use client'

import { useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { fmt, type Dict } from '@/lib/i18n'
import {
  canRetryPay,
  CASH_POLL_MS,
  errorText,
  FINAL,
  leftText,
  pageClock,
  pollOrder,
  POLL_MS,
  postJson,
  type OrderStatus as Status,
  type OnlineMethod,
  type Poll,
} from '@/lib/order-client'
import { formatDateTime, formatTime } from '@/lib/time'
import { CardIcon, CheckIcon, ClockIcon, TicketIcon, WalletIcon } from '../../../icons'
import { Steps } from '../../steps'

type Copy = Pick<Dict, 'orderPage' | 'errors' | 'meta' | 'steps'>

const cta = 'flex h-cta-height items-center justify-center rounded-control bg-accent px-6 text-cta text-on-accent hover:bg-accent-hover'
const isCash = (o: Status) => o.status === 'AWAITING_CASH' || o.payment_method === 'CASH_AGENCY'

/**
 * `/orders/[id]` live part: polls the order (every 3 s, every 10 s while a cash code waits) until it is final (PAID,
 * EXPIRED, CANCELLED, REFUNDED), shows the hold countdown from `reserved_until`, and on PAID links to Mes billets. A 401
 * or 404 stops it (login link / not found); 5xx and network failures back off; a hidden tab doesn't poll.
 * AWAITING_CASH shows `children` (the server-rendered code, CashDetails) with card / wallet as alternatives; an order
 * that turns AWAITING_CASH without them (code issued elsewhere) refreshes the server part. Paying online, as an
 * alternative or "pay again", only per canRetryPay (no double charge while a payment may still be confirmed).
 */
export function OrderStatus({
  id,
  initial,
  now: renderedAt,
  places,
  timezone,
  eventHref,
  methods,
  member = false,
  copy,
  children,
}: {
  id: string
  initial: Status
  now: number
  places: number
  timezone: string | null
  eventHref: string | null
  /** CARD and/or WALLET, as the event offers them. */
  methods: OnlineMethod[]
  /** A split-pay member's order (04 `group.role` MEMBER): once EXPIRED, "La place a été libérée". */
  member?: boolean
  copy: Copy
  children?: React.ReactNode
}) {
  const c = copy.orderPage
  const router = useRouter()
  const [order, setOrder] = useState(initial)
  const [wasCash, setWasCash] = useState(() => isCash(initial)) // for the "Code expiré" title
  // Monotonic ms since `renderedAt` arrived (lib/order-client pageClock): server time = renderedAt + onPageMs drives the
  // countdown and the "pay online" gate. Anchored once per server render (a router.refresh brings a new renderedAt and
  // restarts it, so time isn't counted twice). The device wall clock is never used: it can be minutes off.
  const [tick, setTick] = useState({ at: renderedAt, ms: 0 })
  const onPageMs = tick.at === renderedAt ? tick.ms : 0
  const clock = useRef<{ at: number; elapsed: () => number } | null>(null)
  const lastStatus = useRef(initial.status)
  const [payError, setPayError] = useState<string | null>(null)
  const [paying, setPaying] = useState(false)
  const [gone, setGone] = useState<'UNAUTHORIZED' | 'NOT_FOUND' | null>(null)
  const final = FINAL.includes(order.status)
  const awaiting = order.status === 'AWAITING_CASH'
  const hasCode = children != null && children !== false

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
    const update = (o: Status) => {
      setOrder(o)
      if (isCash(o)) setWasCash(true)
      // Turned AWAITING_CASH since the server render: fetch the server part (the code card) once.
      if (o.status === 'AWAITING_CASH' && lastStatus.current !== 'AWAITING_CASH') router.refresh()
      lastStatus.current = o.status
    }
    pollOrder(fetchOrder, update, abort.signal, { intervalMs: awaiting ? CASH_POLL_MS : POLL_MS })
      .then((r) => typeof r === 'string' && setGone(r))
      .catch(() => {}) // aborted on unmount, once final, or to change the period
    if (clock.current?.at !== renderedAt) clock.current = { at: renderedAt, elapsed: pageClock() }
    const { elapsed } = clock.current
    const timer = setInterval(() => setTick({ at: renderedAt, ms: elapsed() }), 1000)
    return () => {
      abort.abort()
      clearInterval(timer)
    }
  }, [id, final, gone, awaiting, router, renderedAt])

  async function payOnline(method: OnlineMethod) {
    setPaying(true)
    setPayError(null)
    const r = await postJson(`/api/orders/${encodeURIComponent(id)}/pay`, { method })
    if (r.ok) return window.location.assign(String(r.data!.redirect_url))
    setPaying(false)
    setPayError(errorText(copy.errors, r))
  }

  const until = order.reserved_until ? Date.parse(order.reserved_until) : NaN
  const left = Math.max(0, Math.floor((until - (renderedAt + onPageMs)) / 1000))
  const leftLabel = leftText(c, left)
  // A split-pay member's hold that has passed on server time with no attempt in flight (none, or FAILED): the place is
  // gone, the expiry job just hasn't run yet. Shown as EXPIRED ("La place a été libérée" + the event), never "paiement
  // en cours"; polling goes on until the backend says so. A PENDING card / wallet attempt keeps the normal view (late
  // success stays possible).
  const lapsed =
    member &&
    order.status === 'RESERVED' &&
    !!order.reserved_until &&
    (order.payment_status == null || order.payment_status === 'FAILED') &&
    !(until > renderedAt + onPageMs)
  const shown = lapsed ? 'EXPIRED' : order.status
  const done = final || lapsed
  const online = canRetryPay(order, onPageMs, renderedAt)
  const payLabel = { CARD: c.payCARD, WALLET: c.payWALLET }
  const retryLabel = { CARD: c.retryCARD, WALLET: c.retryWALLET }

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

  const payErrorLine = payError && (
    <p role="alert" className="w-full text-body-small text-danger">
      {payError}
    </p>
  )
  const statusChip = (
    <p className="flex flex-wrap gap-x-1 rounded-chip border border-border bg-surface px-3.5 py-3 text-xs text-text-muted">
      <span>{c.statusLabel}</span>
      <strong className={order.status === 'PAID' ? 'text-success-text' : 'text-accent-hover'}>{statusName(c, shown)}</strong>
      {!done && <span>{c.live}</span>}
    </p>
  )
  const banner = (text: string) => (
    <p className="flex items-center gap-3 rounded-control border border-accent/50 bg-accent/10 px-4 py-3.5 text-sm text-accent-hover">
      <ClockIcon className="size-5" />
      <span>{text}</span>
    </p>
  )

  const paid = order.status === 'PAID'
  return (
    <div className="flex flex-col gap-5">
      {/* One live region for both layouts, so a change (PAID, EXPIRED) is announced whichever view was showing. */}
      <p role="status" className="sr-only">
        {c.statusLabel} {statusName(c, shown)}
      </p>
      {awaiting ? (
        <>
          <Steps current={3} copy={copy.steps} />
          {order.reserved_until &&
            timezone &&
            banner(fmt(c.cashBanner, { time: formatDateTime(order.reserved_until, timezone, copy.meta.intl), left: leftLabel }))}
          <h1 className="font-display text-hero uppercase motion-safe:animate-rise">{hasCode ? c.cashTitle : c.awaitingTitle}</h1>
          {hasCode ? children : <p className="text-body text-text-secondary">{c.noCode}</p>}
          {online ? (
            <div data-pay-now className="flex flex-wrap items-center justify-between gap-4 rounded-control border border-border bg-surface px-4.5 py-4">
              <p className="text-sm text-text-secondary">{c.payNow}</p>
              <div className="flex flex-wrap gap-2.5">
                {methods.map((m) => (
                  <button
                    key={m}
                    type="button"
                    onClick={() => payOnline(m)}
                    disabled={paying}
                    aria-busy={paying}
                    className="flex min-h-touch-min items-center gap-2 rounded-chip border border-border px-3.5 text-sm font-semibold transition-colors duration-200 hover:border-accent hover:text-accent disabled:opacity-60"
                  >
                    {m === 'WALLET' ? <WalletIcon className="size-4.5" /> : <CardIcon className="size-4.5" />}
                    {payLabel[m]}
                  </button>
                ))}
              </div>
              {payErrorLine}
            </div>
          ) : (
            order.payment_status === 'PENDING' && <p className="text-body-small text-text-muted">{c.onlinePending}</p>
          )}
        </>
      ) : (
        <>
          <Steps current={paid ? 5 : 3} copy={copy.steps} />
          {shown === 'RESERVED' &&
            order.reserved_until &&
            timezone &&
            banner(fmt(c.banner, { time: formatTime(order.reserved_until, timezone, copy.meta.intl), left: leftLabel }))}
          <div className="mt-2 flex flex-col gap-3">
            <span
              key={shown}
              aria-hidden="true"
              className={`flex size-16 items-center justify-center rounded-pill motion-safe:animate-pop ${
                paid ? 'bg-success-bg text-success-text ring-4 ring-success' : done ? 'bg-surface2 text-text-muted' : 'relative text-accent'
              }`}
            >
              {paid ? (
                <CheckIcon className="size-8" />
              ) : done ? (
                <ClockIcon className="size-8" />
              ) : (
                <>
                  <span className="absolute inset-0 rounded-pill border-4 border-accent/20 border-t-accent motion-safe:animate-spin" />
                  <TicketIcon className="size-7" />
                </>
              )}
            </span>
            <h1 className="font-display text-hero uppercase sm:text-[56px]">{title(c, shown, wasCash, member)}</h1>
            <p className="text-body text-text-secondary">{body(c, shown, member)}</p>
            {order.status === 'PAID' && order.ticket_count < places && <p className="text-body text-text-secondary">{c.paidOthers}</p>}
          </div>
          {paid && (
            <Link href="/me/tickets" className={`${cta} gap-2 self-start motion-safe:animate-rise`}>
              <TicketIcon className="size-5" />
              {c.tickets}
            </Link>
          )}
          {shown === 'RESERVED' && online && (
            <div className="flex flex-col items-start gap-2">
              <p className="text-body-small text-text-muted">{c.retryNote}</p>
              {methods.map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => payOnline(m)}
                  disabled={paying}
                  aria-busy={paying}
                  className="min-h-touch-min text-body-small font-semibold text-accent hover:underline disabled:opacity-60"
                >
                  {retryLabel[m]}
                </button>
              ))}
              {payErrorLine}
            </div>
          )}
          {done && order.status !== 'PAID' && eventHref && (
            <Link href={eventHref} className={`${cta} self-start`}>
              {c.back}
            </Link>
          )}
        </>
      )}
      {statusChip}
    </div>
  )
}

type OrderCopy = Dict['orderPage']
const own = (c: OrderCopy, k: string) => (Object.hasOwn(c, k) ? (c as Record<string, string>)[k] : undefined)
const statusName = (c: OrderCopy, s: string) => own(c, s) ?? s
function title(c: OrderCopy, s: string, wasCash: boolean, member: boolean) {
  if (s === 'PAID') return c.paidTitle
  if (s === 'EXPIRED') return wasCash ? c.cashExpiredTitle : member ? c.memberExpiredTitle : c.expiredTitle
  if (s === 'CANCELLED') return c.cancelledTitle
  if (s === 'REFUNDED') return c.refundedTitle
  return c.pendingTitle
}
function body(c: OrderCopy, s: string, member: boolean) {
  if (s === 'PAID') return c.paid
  if (s === 'EXPIRED') return member ? c.memberExpired : c.expired
  if (s === 'CANCELLED') return c.cancelled
  if (s === 'REFUNDED') return c.refunded
  return c.pending
}
