import Link from 'next/link'
import { notFound, redirect } from 'next/navigation'
import { api, ApiError } from '@/lib/api'
import { cashOptions } from '@/lib/checkout'
import { UUID } from '@/lib/clubs'
import { currentSession } from '@/lib/http'
import { getI18n } from '@/lib/i18n/server'
import { formatDh } from '@/lib/money'
import { loginUrl } from '@/lib/orders'
import { buyable, getProduct, passesPath, productCheckoutPath } from '@/lib/products'
import { formatDateTime } from '@/lib/time'
import { Crest } from '../../../brand'
import { Steps } from '../../steps'
import { ProductForm } from './ProductForm'

/**
 * W3 product variant · `/orders/new?product={id}` (W9 "Acheter"). Malformed or unknown id, or a product off sale → 404;
 * not signed in (or session revoked) → login, back here after; sold out or past `sales_close_at` → W9, which says why.
 */
export async function ProductCheckout({ id }: { id: unknown }) {
  if (typeof id !== 'string' || !UUID.test(id)) notFound()
  const self = productCheckoutPath(id)
  const token = await currentSession()
  if (!token) redirect(loginUrl(self))
  const product = await getProduct(id)
  if (!product) notFound()
  const { d } = await getI18n()
  const club = product.organizer
  const back = passesPath(club)
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  const now = Date.now()
  if (!buyable(product, now)) redirect(back ?? '/')
  let me: { display_name?: unknown }
  try {
    me = await api('/fan/me', { token })
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) redirect(loginUrl(self))
    throw err
  }
  const tz = product.timezone
  const { methods, cashLate, cashUntil } = cashOptions(product, now)
  const p = d.passes
  const detail = product.pass ? `${product.pass.zone.name} · ${product.pass.venue.name}` : p.MEMBERSHIP

  return (
    <main className="mx-auto flex w-full max-w-[1180px] flex-wrap items-start gap-8 px-6 pt-8 pb-16">
      <section aria-labelledby="checkout-title" className="flex min-w-0 flex-[999_1_560px] flex-col gap-4">
        <Steps current={2} copy={d.steps} />
        <h1 id="checkout-title" className="mt-4 font-display text-hero uppercase motion-safe:animate-rise sm:text-[56px]">
          {p[product.kind]}
        </h1>
        <ProductForm
          productId={product.id}
          kind={product.kind}
          displayName={typeof me?.display_name === 'string' ? me.display_name : null}
          plans={product.plans}
          methods={methods}
          cashLate={cashLate}
          cashDeadline={formatDateTime(cashUntil, tz, d.meta.intl)}
          copy={{ checkout: d.checkout, passes: p, errors: d.errors }}
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
          <Crest club={club} size={44} />
          <div className="flex min-w-0 flex-col">
            <bdi className="text-caption text-text-secondary">{club.name}</bdi>
            <p className="font-display text-xl font-bold uppercase leading-tight">{product.name}</p>
            <p className="text-body-small text-text-muted">{detail}</p>
          </div>
        </div>
        <hr className="border-border" />
        <p className="flex justify-between gap-3 text-sm">
          <span>{product.name}</span>
          <span>{formatDh(product.price_mad)}</span>
        </p>
        <p className="flex justify-between gap-3 text-sm text-text-muted">
          <span>{d.checkout.fee}</span>
          <span>{formatDh(0)}</span>
        </p>
        <hr className="border-border" />
        <p className="flex items-baseline justify-between">
          <span className="font-semibold">{d.checkout.total}</span>
          <span className="font-display text-[34px] font-extrabold leading-none">{formatDh(product.price_mad)}</span>
        </p>
        {back && (
          <Link href={back} className="flex min-h-touch-min items-center text-body-small font-semibold text-accent hover:underline">
            {p.back}
          </Link>
        )}
      </aside>
    </main>
  )
}
