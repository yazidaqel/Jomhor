import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { formatPayCode, type CashView } from '@/lib/orders'
import { PinIcon } from '../../../icons'
import { CopyCode } from './CopyCode'

/**
 * W4 cash code (server-rendered, DESIGN W4 + Lot 3 adjustments): the white code card with the grouped code and the
 * exact amount, three numbered steps (where → code + amount → tickets), and "Où payer" from `cash.points` and
 * `cash.networks`. No WhatsApp button (plan Q53). OrderStatus shows it only while the order is AWAITING_CASH.
 */
export function CashDetails({ cash, copy: c, intl }: { cash: CashView; copy: Dict['orderPage']; intl: string }) {
  const code = formatPayCode(cash.pay_code)
  const amount = formatDh(cash.amount_mad)
  const list = (type: Intl.ListFormatType) => new Intl.ListFormat(intl, { type }).format(cash.networks)
  const networks = list('disjunction') // "Wafacash ou Cash Plus"
  const allNetworks = list('conjunction') // "Wafacash et Cash Plus"
  const where = cash.networks.length
    ? fmt(cash.points.length ? c.step1Both : c.step1Networks, { networks })
    : c.step1Points
  const steps = [
    [c.step1Title, where],
    [c.step2Title, fmt(c.step2, { code, amount })],
    [c.step3Title, c.step3],
  ]
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-6 rounded-card-large bg-ticket-paper p-7 text-ticket-ink shadow-2xl shadow-ground motion-safe:animate-rise">
        <div className="flex flex-col gap-1.5">
          <p className="text-label uppercase text-ticket-muted">{c.codeLabel}</p>
          <p dir="ltr" data-pay-code className="select-all font-display text-[44px] font-extrabold leading-none tracking-[3px] sm:text-[56px]">
            {code}
          </p>
          <p className="text-body-small text-ticket-muted">
            {c.amount} <strong data-amount className="text-ticket-ink">{amount}</strong>
          </p>
        </div>
        <CopyCode code={code} label={c.copy} done={c.copied} failed={c.copyFailed} />
      </div>

      <ol className="grid gap-3 sm:grid-cols-3">
        {steps.map(([title, body], i) => (
          <li
            key={title}
            style={{ '--i': i + 1 } as React.CSSProperties}
            className="flex flex-col gap-2 rounded-control border border-border bg-surface p-4 motion-safe:animate-rise stagger"
          >
            <span aria-hidden="true" className="flex size-7 items-center justify-center rounded-pill bg-accent text-sm font-bold text-on-accent">
              {i + 1}
            </span>
            <p className="font-semibold">{title}</p>
            <p className="text-body-small text-text-muted">{body}</p>
          </li>
        ))}
      </ol>

      {(cash.points.length > 0 || cash.networks.length > 0) && (
        <section aria-labelledby="cash-where" className="flex flex-col gap-3 rounded-control border border-border bg-surface p-4">
          <h2 id="cash-where" className="font-display text-h2 uppercase">
            {c.where}
          </h2>
          {cash.points.length > 0 && (
            <ul className="flex flex-col gap-3">
              {cash.points.map((p, i) => (
                <li key={i} className="flex gap-3">
                  <PinIcon className="mt-0.5 size-5 text-accent" />
                  <div className="flex min-w-0 flex-col">
                    <p className="font-semibold">{p.name}</p>
                    {p.address && <p className="text-body-small text-text-secondary">{p.address}</p>}
                    {p.hours && <p className="text-body-small text-text-muted">{p.hours}</p>}
                  </div>
                </li>
              ))}
            </ul>
          )}
          {cash.networks.length > 0 && <p className="text-body-small text-text-secondary">{fmt(c.networks, { networks: allNetworks })}</p>}
        </section>
      )}
    </>
  )
}
