import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound, redirect } from 'next/navigation'
import { api, ApiError } from '@/lib/api'
import { eventPath } from '@/lib/clubs'
import { getEvent, saleState } from '@/lib/events'
import { currentSession } from '@/lib/http'
import { fmt } from '@/lib/i18n'
import { getI18n } from '@/lib/i18n/server'
import { cashOptions } from '@/lib/checkout'
import { formatDh } from '@/lib/money'
import { checkoutPath, loginUrl, parseCheckout } from '@/lib/orders'
import { formatDateTime } from '@/lib/time'
import { Crest } from '../../../brand'
import { Steps } from '../../steps'
import { CheckoutForm } from './CheckoutForm'

export async function generateMetadata(): Promise<Metadata> {
  const { d } = await getI18n()
  return { title: d.checkout.metaTitle, robots: { index: false } }
}

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> }

/**
 * W3 · `/orders/new?event=&price=&qty=` (W2 Continuer). Malformed query or unknown event/price → 404; not signed in
 * (or session revoked) → login, back here after; sale not open or price sold out → the event page, which says why.
 */
export default async function CheckoutPage({ searchParams }: Props) {
  const q = parseCheckout(await searchParams)
  if (!q) notFound()
  const token = await currentSession()
  if (!token) redirect(loginUrl(checkoutPath(q)))
  const e = await getEvent(q.event)
  const zone = e?.zones.find((z) => z.prices.some((p) => p.price_category_id === q.price))
  const price = zone?.prices.find((p) => p.price_category_id === q.price)
  if (!e || !zone || !price) notFound()
  const { d } = await getI18n()
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  const now = Date.now()
  const back = eventPath(e)
  if (saleState(e, now) !== 'ON_SALE' || price.remaining === 0) redirect(back ?? '/')
  let me: { display_name?: unknown }
  try {
    me = await api('/fan/me', { token })
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) redirect(loginUrl(checkoutPath(q)))
    throw err
  }
  const qty = Math.max(1, Math.min(q.qty, e.per_account_limit || q.qty, price.remaining))
  // Say why when fewer places than asked: the stock (remaining) or the per-account limit.
  const capped =
    qty >= q.qty
      ? null
      : qty === price.remaining
        ? fmt(d.checkout.cappedStock, { n: qty })
        : fmt(d.checkout.cappedLimit, { n: qty })
  const total = price.price_mad * qty // integer centimes × integer count: exact
  const zoneLabel = zone.prices.length > 1 ? `${zone.name} · ${price.name}` : zone.name
  const when = `${formatDateTime(e.starts_at, e.timezone, d.meta.intl)} · ${e.venue.name}`
  const { methods, cashLate, cashUntil } = cashOptions(e, now)

  return (
    <main className="mx-auto flex w-full max-w-[1180px] flex-wrap items-start gap-8 px-6 pt-8 pb-16">
      <section aria-labelledby="checkout-title" className="flex min-w-0 flex-[999_1_560px] flex-col gap-4">
        <Steps current={2} copy={d.steps} />
        <h1 id="checkout-title" className="mt-4 font-display text-hero uppercase motion-safe:animate-rise sm:text-[56px]">
          {d.checkout.title}
        </h1>
        <p className="text-body text-text-secondary">{d.checkout.intro}</p>
        {capped && (
          <p data-capped className="rounded-control border border-accent/50 bg-accent/10 px-4 py-3 text-body-small text-accent-hover">
            {capped}
          </p>
        )}
        <CheckoutForm
          eventId={e.id}
          priceCategoryId={price.price_category_id}
          qty={qty}
          displayName={typeof me?.display_name === 'string' ? me.display_name : null}
          unitMad={price.price_mad}
          methods={methods}
          cashLate={cashLate}
          now={now}
          timezone={e.timezone}
          cashDeadline={formatDateTime(cashUntil, e.timezone, d.meta.intl)}
          queueHref={back && `${back}/queue`}
          copy={{ checkout: d.checkout, errors: d.errors, meta: d.meta }}
        />
      </section>
      <aside
        aria-labelledby="summary-title"
        className="flex w-full flex-[1_1_320px] flex-col gap-3.5 overflow-hidden rounded-card border border-border bg-surface p-5 shadow-2xl shadow-ground md:sticky md:top-24 md:max-w-[380px]"
      >
        <h2 id="summary-title" className="font-display text-h2 uppercase">
          {d.checkout.summary}
        </h2>
        <div className="flex items-center gap-3 rounded-control bg-ground p-3">
          <Crest club={e.organizer} size={44} />
          <div className="flex min-w-0 flex-col">
            <p className="font-display text-xl font-bold uppercase leading-tight">{e.title}</p>
            <p className="text-body-small text-text-muted">{when}</p>
          </div>
        </div>
        <hr className="border-border" />
        <p className="flex justify-between gap-3 text-sm">
          <span>{fmt(d.checkout.line, { qty, zone: zoneLabel })}</span>
          <span>{formatDh(total)}</span>
        </p>
        <p className="flex justify-between gap-3 text-sm text-text-muted">
          <span>{d.checkout.fee}</span>
          <span>{formatDh(0)}</span>
        </p>
        <hr className="border-border" />
        <p className="flex items-baseline justify-between">
          <span className="font-semibold">{d.checkout.total}</span>
          <span className="font-display text-[34px] font-extrabold leading-none">{formatDh(total)}</span>
        </p>
        {back && (
          <Link href={back} className="flex min-h-touch-min items-center text-body-small font-semibold text-accent hover:underline">
            {d.checkout.back}
          </Link>
        )}
      </aside>
    </main>
  )
}
