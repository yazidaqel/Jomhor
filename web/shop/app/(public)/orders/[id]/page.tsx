import type { Metadata } from 'next'
import { notFound, redirect } from 'next/navigation'
import { ApiError } from '@/lib/api'
import { CASH_HOLD_MS, cashOptions } from '@/lib/checkout'
import { eventPath } from '@/lib/clubs'
import { getEvent, type Organizer, type ShopEventDetail } from '@/lib/events'
import { currentSession } from '@/lib/http'
import { fmt } from '@/lib/i18n'
import { getI18n } from '@/lib/i18n/server'
import { formatDh } from '@/lib/money'
import type { OnlineMethod } from '@/lib/order-client'
import { getOrder, loginUrl, orderNo, SAFE_ID, type OrderView } from '@/lib/orders'
import { getProduct, passesPath } from '@/lib/products'
import { formatDateTime } from '@/lib/time'
import { Crest } from '../../../brand'
import { CashDetails } from './CashDetails'
import { GroupProgress, slot } from './GroupProgress'
import { MemberPay } from './MemberPay'
import { OrderStatus } from './OrderStatus'

export async function generateMetadata(): Promise<Metadata> {
  const { d } = await getI18n()
  return { title: d.orderPage.metaTitle, robots: { index: false } }
}

/**
 * W4 · `/orders/{id}`: where the gateway's `/return` lands (WEB) and where a cash code is shown (Lot 3). SSR of the
 * order (the code card is server-rendered, CashDetails), then OrderStatus polls it. Not signed in → login and back;
 * not the buyer's or unknown → 404. Split pay (Lot 4): a member's order still RESERVED with no attempt (or a FAILED
 * one) shows the member variant (event header, "{initiator_label} t'a gardé une place", MemberPay); the initiator's
 * PAID order gets the group progress block under it. Lot 5 step 3, a product order (`event_id` null, no event read): the
 * product where the event header goes (`order.product`: its club, time zone and methods, even once off sale), and
 * while RESERVED with no attempt the method rows (ProductVariant); PAID says the pass is active or the member card up
 * to date; EXPIRED points back to W9.
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
  const product = order.product
  const kind = product?.kind
  const [e, { d }] = await Promise.all([order.event_id === null ? null : getEvent(order.event_id), getI18n()])
  // A product order's place is not a ticket the buyer is told about ("tes amis…"): 0.
  const places = kind ? 0 : order.items.reduce((n, i) => n + i.qty, 0)
  const labels = order.items.flatMap((i) => i.labels)
  const when = e && `${formatDateTime(e.starts_at, e.timezone, d.meta.intl)} · ${e.venue.name}`
  // Card and wallet as alternatives to a code, or to pay again; wallet only if the event (or product) offers it.
  const online = ((product ?? e)?.payment_methods ?? ['CARD']).filter((m): m is OnlineMethod => m !== 'CASH_AGENCY')
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  const now = Date.now()
  const p = d.passes
  // 3b: an installment order reads "Échéance {k}/{n}" where the kind goes, and as its summary line.
  const step = order.installment && fmt(p.installmentOf, { k: order.installment.seq, n: order.installment.of })
  const productHead = product && { name: product.name, club: product.organizer, detail: step || p[product.kind] }
  // An installment order goes back to its pass on W5 (W9 can't help: the pass is held, and often off sale by then).
  const passHref = order.installment ? (order.pass_id ? `/me/tickets#pass-${order.pass_id}` : '/me/tickets') : null
  const productCopy =
    kind &&
    (order.installment
      ? { paid: fmt(p.installmentPaid, { k: order.installment.seq, n: order.installment.of }), expired: p.installmentExpired, back: p.installmentBack, tickets: true }
      : { paid: kind === 'PASS' ? p.paidPASS : p.paidMEMBERSHIP, expired: p.holdExpired, back: p.back, tickets: kind === 'PASS' })
  const productPays =
    productHead &&
    order.status === 'RESERVED' &&
    order.reserved_until &&
    Date.parse(order.reserved_until) > now &&
    order.payment_status == null
      ? {
          head: productHead,
          product,
          // An installment order's cash comes from the order alone (no sales close, the product may be off sale).
          pay: order.installment ? installmentCash(product, now) : cashOptions({ payment_methods: product.payment_methods, ...(await cashOf(product.id)) }, now),
          until: order.reserved_until,
        }
      : null
  const group = order.group
  const memberPays =
    e &&
    group?.role === 'MEMBER' &&
    order.status === 'RESERVED' &&
    order.reserved_until &&
    Date.parse(order.reserved_until) > now && // a passed hold falls through to OrderStatus (member): title + event link
    (order.payment_status == null || order.payment_status === 'FAILED')
      ? { e, label: group.initiator_label, until: order.reserved_until }
      : null

  return (
    <main className="mx-auto flex w-full max-w-[1180px] flex-wrap items-start gap-8 px-6 pt-8 pb-16">
      <section className="flex min-w-0 flex-[999_1_560px] flex-col gap-5">
        {productPays ? (
          <ProductVariant {...productPays} order={order} now={now} d={d} />
        ) : memberPays ? (
          <MemberVariant {...memberPays} order={order} now={now} d={d} />
        ) : (
          <OrderStatus
            id={order.id}
            initial={{
              status: order.status,
              reserved_until: order.reserved_until,
              ticket_count: order.ticket_count,
              ...(order.payment_status !== undefined && { payment_status: order.payment_status }),
              ...(order.payment_method !== undefined && { payment_method: order.payment_method }),
            }}
            now={now}
            places={places}
            timezone={product ? product.timezone : (e?.timezone ?? null)}
            eventHref={product ? (passHref ?? passesPath(product.organizer)) : e && eventPath(e)}
            methods={online}
            member={group?.role === 'MEMBER'}
            product={productCopy}
            copy={{ orderPage: d.orderPage, errors: d.errors, meta: d.meta, steps: d.steps }}
          >
            {order.cash && <CashDetails cash={order.cash} copy={d.orderPage} intl={d.meta.intl} />}
          </OrderStatus>
        )}
        {e && group?.role === 'INITIATOR' && order.status === 'PAID' && group.members.length > 0 && (
          <GroupProgress members={group.members} buyerPlaces={places} event={e} now={now} copy={d.orderPage} intl={d.meta.intl} />
        )}
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
        {productHead && !productPays && <ProductHead {...productHead} size={44} />}
        {e && !memberPays && (
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
            <span>{step || fmt(d.orderPage.line, { qty: i.qty, name: i.name })}</span>
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
              <span>{kind ? p.holder : fmt(d.orderPage.ticket, { n: n + 1 })}</span>
              <bdi>{label}</bdi>
            </li>
          ))}
        </ul>
      </aside>
    </main>
  )
}

type Dictionary = Awaited<ReturnType<typeof getI18n>>['d']

/**
 * W4 split-pay member (DESIGN Lot 4, 9 Groupe member): the event header (crest + club name, title, date), "{label}
 * t'a gardé une place" (the label in a bidi isolate; null → "Un ami t'a gardé une place"), the amount, then MemberPay.
 */
function MemberVariant({ e, label, until, order, now, d }: { e: ShopEventDetail; label: string | null; until: string; order: OrderView; now: number; d: Dictionary }) {
  const c = d.orderPage
  const { methods, cashLate, cashUntil } = cashOptions(e, now)
  return (
    <>
      <div className="flex items-center gap-4 rounded-card border border-border bg-surface p-5 motion-safe:animate-rise">
        <Crest club={e.organizer} size={64} />
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="text-label uppercase text-text-secondary">{e.organizer.name}</p>
          <p className="font-display text-h1 uppercase leading-tight">{e.title}</p>
          <p className="text-body-small text-text-muted">{`${formatDateTime(e.starts_at, e.timezone, d.meta.intl)} · ${e.venue.name}`}</p>
        </div>
      </div>
      <h1 className="font-display text-hero uppercase sm:text-[56px]">{label ? slot(c.memberTitle, <bdi>{label}</bdi>) : c.memberTitleAnon}</h1>
      <p className="flex items-baseline gap-3">
        <span className="text-body text-text-secondary">{c.memberAmount}</span>
        <strong data-amount className="font-display text-[34px] font-extrabold leading-none">
          {formatDh(order.total_mad)}
        </strong>
      </p>
      <MemberPay
        id={order.id}
        amountMad={order.total_mad}
        reservedUntil={until}
        now={now}
        timezone={e.timezone}
        methods={methods}
        cashLate={cashLate}
        cashDeadline={formatDateTime(cashUntil, e.timezone, d.meta.intl)}
        copy={{ checkout: d.checkout, orderPage: c, errors: d.errors, meta: d.meta }}
      />
    </>
  )
}

type Head = { name: string; club: Organizer; detail: string }
type OrderProduct = NonNullable<OrderView['product']>

type Cash = { cash_until: string | null; sales_close_at: string | null }

/**
 * Cash needs the product's `cash_until` (and `sales_close_at`, which caps a code's hold), which only the live product
 * read carries. Off sale (404) or unreadable → none: cash is dropped, card / wallet stay.
 */
async function cashOf(id: string): Promise<Cash> {
  try {
    const live = await getProduct(id)
    return { cash_until: live?.cash_until ?? null, sales_close_at: live?.sales_close_at ?? null }
  } catch (e) {
    console.error('product read failed', id, e)
    return { cash_until: null, sales_close_at: null }
  }
}

/**
 * 3b installment order (04 GET /fan/orders/{id} `installment`): the order's own methods; `cash_until` null means only
 * the `/pay` hold applies (a code valid `now + 24 h`), else it bounds cash as for any order.
 */
function installmentCash(product: OrderProduct, now: number) {
  if (product.cash_until) return cashOptions({ payment_methods: product.payment_methods, cash_until: product.cash_until, sales_close_at: null }, now)
  return { methods: product.payment_methods, cashLate: false, cashUntil: new Date(now + CASH_HOLD_MS).toISOString() }
}

/** A product order's header (DESIGN Lot 5 step 3 W4): crest + club name, the product name and its kind. */
function ProductHead({ name, club, detail, size, title = false }: Head & { size: number; title?: boolean }) {
  const Name = title ? 'h1' : 'p'
  return (
    <div className={`flex items-center gap-4 ${title ? 'rounded-card border border-border bg-surface p-5 motion-safe:animate-rise' : 'rounded-control bg-ground p-3'}`}>
      <Crest club={club} size={size} />
      <div className="flex min-w-0 flex-col gap-0.5">
        <bdi className="text-label uppercase text-text-secondary">{club.name}</bdi>
        <Name className={`font-display uppercase leading-tight ${title ? 'text-h1' : 'text-xl font-bold'}`}>{name}</Name>
        <p className="text-body-small text-text-muted">{detail}</p>
      </div>
    </div>
  )
}

/**
 * W4 product order still RESERVED with no payment attempt (DESIGN Lot 5 step 3, as the split-pay member variant): the
 * product header, the amount, then MemberPay with the order's product methods, time zone and hold copy. Cash only while
 * the live product read gives its `cash_until` (none once off sale); 3b, an installment order's from the order itself.
 */
function ProductVariant({ head, product, pay, until, order, now, d }: { head: Head; product: OrderProduct; pay: ReturnType<typeof cashOptions>; until: string; order: OrderView; now: number; d: Dictionary }) {
  const p = d.passes
  const tz = product.timezone
  const { methods, cashLate, cashUntil } = pay
  return (
    <>
      <ProductHead {...head} size={64} title />
      <p className="flex items-baseline gap-3">
        <span className="text-body text-text-secondary">{d.checkout.total}</span>
        <strong data-amount className="font-display text-[34px] font-extrabold leading-none">
          {formatDh(order.total_mad)}
        </strong>
      </p>
      <MemberPay
        id={order.id}
        amountMad={order.total_mad}
        reservedUntil={until}
        now={now}
        timezone={tz}
        methods={methods}
        cashLate={cashLate}
        cashDeadline={formatDateTime(cashUntil, tz, d.meta.intl)}
        copy={{
          checkout: d.checkout,
          orderPage: { ...d.orderPage, memberBanner: p.holdBanner, memberExpiredTitle: p.holdExpiredTitle, memberExpired: order.installment ? p.installmentExpired : p.holdExpired },
          errors: d.errors,
          meta: d.meta,
        }}
      />
      <p className="text-xs text-text-muted">{product.kind === 'PASS' ? p.captionPASS : p.captionMEMBERSHIP}</p>
    </>
  )
}
