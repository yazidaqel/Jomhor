import Link from 'next/link'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { payable, type Installment, type PassView } from '@/lib/products'
import { formatShortDate } from '@/lib/time'
import { Crest } from '../../../brand'
import { ArrowIcon } from '../../../icons'
import { PayInstallment } from './PayInstallment'

/** Series dates are calendar days (`YYYY-MM-DD`): read and shown in UTC, so no zone shifts them by a day. */
const day = (ymd: string, intl: string) => formatShortDate(`${ymd}T00:00:00Z`, 'UTC', intl)

/**
 * W5 "Mes abonnements" (DESIGN Lot 5 step 3), server-rendered above the tickets: one row per pass (club, product, zone,
 * season, holder, status chip); PENDING with its purchase order → "Reprendre le paiement" (W4). Nothing when there is none.
 * 3b: SUSPENDED → the danger banner; a pass in installments lists its schedule (Payée / À payer / En retard against
 * `now`), and "Payer l'échéance · {amount}" while one is unpaid (PayInstallment → W4). Each row is `#pass-{id}`, the
 * target of a blocked ticket's "Payer l'échéance".
 */
export function MyPasses({ passes, now, d }: { passes: PassView[]; now: number; d: Dict }) {
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
              id={`pass-${pass.id}`}
              data-pass={pass.status}
              className="flex scroll-mt-24 flex-wrap items-center gap-5 rounded-card border border-border bg-surface p-5 motion-safe:animate-rise stagger"
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
              <Schedule pass={pass} now={now} d={d} />
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/**
 * Payée, En retard (its due date has passed) or À payer. A PENDING pass is a purchase being paid: installment 1 is due
 * at purchase, so nothing reads late until the pass is ACTIVE.
 */
const installmentState = (i: Installment, now: number, pending: boolean) =>
  i.paid_at ? 'paid' : !pending && Date.parse(i.due_at) <= now ? 'late' : 'due'

/** 3b, under a pass's header: the suspension banner, the schedule (in installments only) and "Payer l'échéance". */
function Schedule({ pass, now, d }: { pass: PassView; now: number; d: Dict }) {
  const p = d.passes
  const intl = d.meta.intl
  const n = pass.installments.length
  const due = pass.next_due
  const pay = payable(pass)
  if (!(pass.status === 'SUSPENDED' && due) && n < 2 && !pay) return null
  return (
    <div className="flex basis-full flex-col gap-4">
      {pass.status === 'SUSPENDED' && due && (
        <p data-suspended className="rounded-control border-2 border-danger px-4 py-3 text-body-small font-semibold text-danger">
          {fmt(p.suspendedBanner, { date: formatShortDate(due.due_at, pass.timezone, intl) })}
        </p>
      )}
      {n > 1 && (
        <section aria-labelledby={`schedule-${pass.id}`} className="flex flex-col gap-2">
          <h4 id={`schedule-${pass.id}`} className="text-label uppercase text-text-secondary">
            {p.schedule}
          </h4>
          <ol className="flex flex-col divide-y divide-border rounded-control border border-border bg-ground">
            {pass.installments.map((i) => {
              const state = installmentState(i, now, pass.status === 'PENDING')
              return (
                <li key={i.seq} data-installment={state} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 text-sm">
                  <span>{fmt(p.installment, { seq: i.seq, n, amount: formatDh(i.amount_mad), date: formatShortDate(i.due_at, pass.timezone, intl) })}</span>
                  <span
                    className={`rounded-chip px-2.5 py-1 text-xs font-semibold ${
                      state === 'paid' ? 'bg-success-bg text-success-text' : state === 'late' ? 'border border-danger text-danger' : 'bg-accent/12 text-accent-hover'
                    }`}
                  >
                    {state === 'paid' ? p.instPaid : state === 'late' ? p.instLate : p.instDue}
                  </span>
                </li>
              )
            })}
          </ol>
        </section>
      )}
      {pay && due && (
        <div className="sm:self-start">
          <PayInstallment passId={pass.id} label={fmt(p.payInstallment, { amount: formatDh(due.amount_mad) })} errors={d.errors} />
        </div>
      )}
    </div>
  )
}
