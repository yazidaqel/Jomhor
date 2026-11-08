import type { Metadata } from 'next'
import { notFound, redirect } from 'next/navigation'
import { ApiError } from '@/lib/api'
import { eventPath } from '@/lib/clubs'
import { getEvent } from '@/lib/events'
import { currentSession } from '@/lib/http'
import { fmt } from '@/lib/i18n'
import { getI18n } from '@/lib/i18n/server'
import { formatDh } from '@/lib/money'
import type { OnlineMethod } from '@/lib/order-client'
import { getOrder, loginUrl, orderNo, SAFE_ID, type OrderView } from '@/lib/orders'
import { formatDateTime } from '@/lib/time'
import { Crest } from '../../../brand'
import { CashDetails } from './CashDetails'
import { OrderStatus } from './OrderStatus'

export async function generateMetadata(): Promise<Metadata> {
  const { d } = await getI18n()
  return { title: d.orderPage.metaTitle, robots: { index: false } }
}

/**
 * W4 · `/orders/{id}`: where the gateway's `/return` lands (WEB) and where a cash code is shown (Lot 3). SSR of the
 * order (the code card is server-rendered, CashDetails), then OrderStatus polls it. Not signed in → login and back;
 * not the buyer's or unknown → 404.
 */
export default async function OrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  if (!SAFE_ID.test(id)) notFound()
  const token = await currentSession()
  if (!token) redirect(loginUrl(`/orders/${id}`))
  let order: OrderView
  try {
    order = await getOrder(id, token)
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) redirect(loginUrl(`/orders/${id}`))
    if (e instanceof ApiError && e.status === 404) notFound()
    throw e
  }
  const [e, { d }] = await Promise.all([getEvent(order.event_id), getI18n()])
  const places = order.items.reduce((n, i) => n + i.qty, 0)
  const labels = order.items.flatMap((i) => i.labels)
  const when = e && `${formatDateTime(e.starts_at, e.timezone, d.meta.intl)} · ${e.venue.name}`
  // Card and wallet as alternatives to a code, or to pay again; wallet only if the event offers it.
  const online = (e?.payment_methods ?? ['CARD']).filter((m): m is OnlineMethod => m !== 'CASH_AGENCY')

  return (
    <main className="mx-auto flex w-full max-w-[1180px] flex-wrap items-start gap-8 px-6 pt-8 pb-16">
      <section className="flex min-w-0 flex-[999_1_560px] flex-col gap-5">
        <OrderStatus
          id={order.id}
          initial={{
            status: order.status,
            reserved_until: order.reserved_until,
            ticket_count: order.ticket_count,
            ...(order.payment_status !== undefined && { payment_status: order.payment_status }),
            ...(order.payment_method !== undefined && { payment_method: order.payment_method }),
          }}
          // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
          now={Date.now()}
          places={places}
          timezone={e?.timezone ?? null}
          eventHref={e && eventPath(e)}
          methods={online}
          copy={{ orderPage: d.orderPage, errors: d.errors, meta: d.meta, steps: d.steps }}
        >
          {order.cash && <CashDetails cash={order.cash} copy={d.orderPage} intl={d.meta.intl} />}
        </OrderStatus>
      </section>
      <aside
        aria-labelledby="order-summary"
        className="flex w-full flex-[1_1_320px] flex-col gap-3.5 rounded-card border border-border bg-surface p-5 shadow-2xl shadow-ground md:sticky md:top-24 md:max-w-[380px]"
      >
        <div className="flex items-baseline justify-between gap-3">
          <h2 id="order-summary" className="font-display text-h2 uppercase">
            {d.orderPage.summary}
          </h2>
          <p className="text-body-small text-text-muted">
            {fmt(d.orderPage.order, { no: orderNo(order.id) })}
          </p>
        </div>
        {e && (
          <div className="flex items-center gap-3 rounded-control bg-ground p-3">
            <Crest club={e.organizer} size={44} />
            <div className="flex min-w-0 flex-col">
              <p className="font-display text-xl font-bold uppercase leading-tight">{e.title}</p>
              <p className="text-body-small text-text-muted">{when}</p>
            </div>
          </div>
        )}
        <hr className="border-border" />
        {order.items.map((i) => (
          <p key={i.price_category_id} className="flex justify-between gap-3 text-sm">
            <span>{fmt(d.orderPage.line, { qty: i.qty, name: i.name })}</span>
            <span>{formatDh(i.unit_price_mad * i.qty)}</span>
          </p>
        ))}
        <hr className="border-border" />
        <p className="flex items-baseline justify-between">
          <span className="font-semibold">{d.orderPage.total}</span>
          <span className="font-display text-[34px] font-extrabold leading-none">{formatDh(order.total_mad)}</span>
        </p>
        <ul className="flex flex-col gap-2 text-body-small text-text-secondary">
          {labels.map((label, n) => (
            <li key={n} className="flex justify-between gap-3">
              <span>{fmt(d.orderPage.ticket, { n: n + 1 })}</span>
              <bdi>{label}</bdi>
            </li>
          ))}
        </ul>
      </aside>
    </main>
  )
}
