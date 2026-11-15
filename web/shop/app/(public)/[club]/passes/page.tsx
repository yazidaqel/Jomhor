import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { getClub } from '@/lib/clubs'
import { currentSession } from '@/lib/http'
import { fmt, type Dict } from '@/lib/i18n'
import { getI18n } from '@/lib/i18n/server'
import { formatDh } from '@/lib/money'
import { loginUrl } from '@/lib/orders'
import { buyable, getProducts, productCheckoutPath, type Product } from '@/lib/products'
import { formatDate, formatDateTime } from '@/lib/time'
import { Crest } from '../../../brand'
import { ArrowIcon, PinIcon, TicketIcon } from '../../../icons'

// Rendered per request (signed-in state for the CTA); the backend read is cached 60 s (lib/products.ts).
export const dynamic = 'force-dynamic'

type Props = { params: Promise<{ club: string }> }

/** A listed club, else 404 (a slug failing the 03 rule never reaches the backend). */
async function load(slug: string) {
  const club = await getClub(slug)
  if (!club) notFound()
  return club
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const [club, { d }] = await Promise.all([load((await params).club), getI18n()])
  const title = fmt(d.passes.metaTitle, { club: club.name })
  const description = fmt(d.passes.description, { club: club.name })
  const url = `/${club.slug}/passes`
  return {
    title,
    description,
    alternates: { canonical: url },
    // The club's OG image (crest + name): this page's openGraph replaces the inherited one, images included.
    openGraph: { title, description, type: 'website', url, siteName: d.app.name, locale: d.meta.og, images: [`/${club.slug}/opengraph-image`] },
  }
}

/**
 * W9 · Abonnements (DESIGN Lot 5 step 3): the club header as W7, then one card per product on sale
 * (`GET /fan/products?organizer=`): season passes (zone, venue, the announced matches) and the member card. "Acheter" →
 * W3 `?product=`, through login when signed out.
 */
export default async function PassesPage({ params }: Props) {
  const club = await load((await params).club)
  const [{ d }, products, signedIn] = await Promise.all([getI18n(), getProducts(club.id), currentSession().then(Boolean)])
  const p = d.passes
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  const now = Date.now()
  return (
    <main>
      <section className="relative isolate overflow-hidden border-b border-border bg-surface">
        <div aria-hidden="true" className="bg-crowd absolute inset-0 -z-10" />
        <div aria-hidden="true" className="absolute -top-40 -end-40 -z-10 size-[420px] glow opacity-[0.16] md:size-[620px]" />
        <div className="mx-auto flex max-w-[1180px] flex-col gap-4 px-6 pt-12 pb-10 motion-safe:animate-rise">
          <Link href={`/${club.slug}`} className="flex min-h-touch-min w-fit items-center gap-6 transition-opacity hover:opacity-85">
            <Crest club={club} size={64} />
            <span className="min-w-0 font-display text-h1 uppercase text-balance">
              <bdi>{club.name}</bdi>
            </span>
          </Link>
          <h1 className="font-display text-hero uppercase sm:text-[64px]">{p.title}</h1>
          <p className="max-w-xl text-body text-text-secondary">{p.intro}</p>
        </div>
      </section>
      <section aria-label={p.title} className="mx-auto w-full max-w-[1180px] px-6 pt-12 pb-16">
        {products.length > 0 ? (
          <ul className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
            {products.map((product, i) => (
              <li key={product.id} className="motion-safe:animate-rise stagger" style={{ '--i': i } as React.CSSProperties}>
                <ProductCard product={product} now={now} href={signedIn ? productCheckoutPath(product.id) : loginUrl(productCheckoutPath(product.id))} d={d} />
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-body text-text-secondary">{p.empty}</p>
        )}
      </section>
    </main>
  )
}

function ProductCard({ product, now, href, d }: { product: Product; now: number; href: string; d: Dict }) {
  const p = d.passes
  const tz = product.timezone
  const pass = product.pass
  const n = pass?.events.length ?? 0
  const matches = n === 0 ? p.matchesNone : n === 1 ? p.matchesOne : fmt(p.matches, { n })
  const id = `product-${product.id}`
  return (
    <article aria-labelledby={id} className="flex h-full flex-col gap-4 rounded-card border border-border bg-surface p-5">
      <div className="flex flex-col gap-1.5">
        <p className="text-label uppercase text-accent">{pass ? `${p.PASS} · ${pass.series.name}` : p.MEMBERSHIP}</p>
        <h2 id={id} className="font-display text-[28px] font-extrabold uppercase leading-[0.95] text-balance">
          {product.name}
        </h2>
      </div>
      <p data-price className="font-display text-[34px] font-extrabold leading-none">
        {formatDh(product.price_mad)}
      </p>
      {pass ? (
        <>
          <p className="flex items-center gap-2 text-sm text-text-secondary">
            <PinIcon className="size-4 shrink-0 text-text-muted" />
            {[pass.zone.name, pass.venue.name, pass.venue.city].filter(Boolean).join(' · ')}
          </p>
          <p className="flex items-center gap-2 text-sm text-text-secondary">
            <TicketIcon className="size-4 shrink-0 text-text-muted" />
            {p.perMatch}
          </p>
          {n > 0 ? (
            <details className="rounded-control border border-border bg-ground">
              <summary className="min-h-touch-min cursor-pointer px-4 py-3 text-sm font-semibold">{matches}</summary>
              <ul className="flex flex-col gap-2 px-4 pb-3 text-sm">
                {pass.events.map((e) => (
                  <li key={e.id} className="flex flex-col">
                    <span className="font-semibold">{e.title}</span>
                    <span className="text-text-muted">{formatDateTime(e.starts_at, tz, d.meta.intl)}</span>
                  </li>
                ))}
              </ul>
            </details>
          ) : (
            <p className="text-sm text-text-muted">{matches}</p>
          )}
        </>
      ) : (
        product.valid_until && (
          <p className="text-sm text-text-secondary">
            {fmt(p.membership, { price: formatDh(product.price_mad), date: formatDate(product.valid_until, tz, d.meta.intl) })}
          </p>
        )
      )}
      <div className="mt-auto pt-2">
        {/* Sold out, or past `sales_close_at` (a cached list can still carry it): no way to buy, W3 would send it back here. */}
        {!buyable(product, now) ? (
          <p aria-disabled="true" className="flex h-cta-height items-center justify-center rounded-control border border-border text-cta text-text-muted">
            {product.sold_out ? p.soldOut : p.closed}
          </p>
        ) : (
          <Link
            href={href}
            aria-describedby={id}
            className="flex h-cta-height items-center justify-center gap-2 rounded-control bg-accent text-cta text-on-accent transition-colors duration-200 hover:bg-accent-hover"
          >
            {p.buy}
            <ArrowIcon className="size-5" />
          </Link>
        )}
      </div>
    </article>
  )
}
