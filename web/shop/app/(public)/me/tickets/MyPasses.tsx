import Link from 'next/link'
import { fmt, type Dict } from '@/lib/i18n'
import type { PassView } from '@/lib/products'
import { formatShortDate } from '@/lib/time'
import { Crest } from '../../../brand'
import { ArrowIcon } from '../../../icons'

/** Series dates are calendar days (`YYYY-MM-DD`): read and shown in UTC, so no zone shifts them by a day. */
const day = (ymd: string, intl: string) => formatShortDate(`${ymd}T00:00:00Z`, 'UTC', intl)

/**
 * W5 "Mes abonnements" (DESIGN Lot 5 step 3), server-rendered above the tickets: one row per pass (club, product, zone,
 * season, holder, status chip); PENDING with its purchase order → "Reprendre le paiement" (W4). Nothing when there is none.
 */
export function MyPasses({ passes, d }: { passes: PassView[]; d: Dict }) {
  if (passes.length === 0) return null
  const p = d.passes
  const intl = d.meta.intl
  return (
    <section aria-labelledby="tickets-passes" className="flex flex-col gap-5">
      <h2 id="tickets-passes" className="font-display text-[32px] font-bold uppercase leading-none">
        {p.mine}
      </h2>
      <ul className="flex flex-col gap-2.5">
        {passes.map((pass, i) => {
          const status = pass.status === 'PENDING' || pass.status === 'ACTIVE' || pass.status === 'SUSPENDED' ? p[pass.status] : null // a status this shop doesn't know: no chip
          return (
            <li
              key={pass.id}
              data-pass={pass.status}
              className="flex flex-wrap items-center gap-5 rounded-card border border-border bg-surface p-5 motion-safe:animate-rise stagger"
              style={{ '--i': i } as React.CSSProperties}
            >
              <div className="flex min-w-0 flex-[1_1_240px] flex-col gap-1.5">
                <p className="flex min-w-0 items-center gap-2 text-caption text-text-secondary">
                  <Crest club={pass.organizer} size={20} />
                  <bdi className="truncate">{pass.organizer.name}</bdi>
                </p>
                <h3 className="font-display text-[28px] font-extrabold uppercase leading-[0.95] text-balance">{pass.product.name}</h3>
                <p className="text-sm text-text-secondary">
                  {[pass.zone.name, pass.series.name, fmt(p.season, { from: day(pass.series.starts_on, intl), to: day(pass.series.ends_on, intl) })].join(' · ')}
                </p>
                {pass.holder_label && (
                  <p className="text-sm text-text-muted">
                    {p.holder} · <bdi className="font-semibold text-text">{pass.holder_label}</bdi>
                  </p>
                )}
              </div>
              {status && (
                <span
                  className={`rounded-chip px-3 py-1.5 text-sm font-semibold ${
                    pass.status === 'ACTIVE' ? 'bg-success-bg text-success-text' : pass.status === 'SUSPENDED' ? 'border border-danger text-danger' : 'bg-accent/12 text-accent-hover'
                  }`}
                >
                  {status}
                </span>
              )}
              {pass.order_id && (
                <Link
                  href={`/orders/${encodeURIComponent(pass.order_id)}`}
                  className="flex min-h-touch-min items-center gap-2 rounded-control bg-accent px-5 text-cta text-on-accent transition-colors duration-200 hover:bg-accent-hover"
                >
                  {p.resume}
                  <ArrowIcon className="size-5" />
                </Link>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
