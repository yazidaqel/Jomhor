import { whatsappUrl } from '@/lib/checkout'
import { fmt, ltr, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import type { MemberView } from '@/lib/orders'
import { formatTime } from '@/lib/time'
import { CheckIcon } from '../../../icons'
import { CopyCode } from './CopyCode'
import { Refresh } from './Refresh'

/** A template with one `{…}` slot filled by a node (a `<bdi>` label, a big figure). */
export const slot = (t: string, node: React.ReactNode) => {
  const [before, after] = t.split(/\{\w+\}/)
  return (
    <>
      {before}
      {node}
      {after}
    </>
  )
}

/** DESIGN 9 Groupe: first letter of the first and last words (`Karim B.` → `KB`); Arabic letters spaced so they don't join. */
export function initials(label: string) {
  const words = label.replace(/\./g, '').trim().split(/\s+/).filter(Boolean)
  const letters = [words[0], words.length > 1 ? words.at(-1) : undefined].map((w) => (w ? Array.from(w)[0]!.toUpperCase() : '')).filter(Boolean)
  return letters.join(/[؀-ۿ]/.test(label) ? ' ' : '')
}

/** A member order that can still be paid, by the server's clock. */
export const payable = (m: MemberView, now: number) =>
  (m.status === 'RESERVED' || m.status === 'AWAITING_CASH') && !!m.reserved_until && Date.parse(m.reserved_until) > now

/**
 * W4 initiator once PAID (DESIGN Lot 4 W4 + 9 Groupe initiator), server-rendered under the order: "{paid}/{total}
 * payées" counting places (the buyer's + every member's, expired included; paid = places in PAID orders), one row per
 * member (initials, label, amount, chip), and per unpaid member "Relancer sur WhatsApp" (wa.me with the copy and its
 * `pay_url`, no phone) and "Copier le lien". Refreshed every 10 s while visible and a member is still payable.
 */
export function GroupProgress({
  members,
  buyerPlaces,
  event,
  now,
  copy: c,
  intl,
}: {
  members: MemberView[]
  buyerPlaces: number
  event: { title: string; timezone: string }
  now: number
  copy: Dict['orderPage']
  intl: string
}) {
  const total = buyerPlaces + members.reduce((n, m) => n + m.places, 0)
  const paid = buyerPlaces + members.reduce((n, m) => n + (m.status === 'PAID' ? m.places : 0), 0)
  const live = members.some((m) => payable(m, now))
  return (
    <section aria-labelledby="group-title" data-live={live || undefined} className="flex flex-col gap-4 rounded-card border border-border bg-surface p-5">
      {live && <Refresh />}
      <h2 id="group-title" className="font-display text-h2 uppercase">
        {fmt(c.groupTitle, { event: event.title })}
      </h2>
      <p role="status" className="flex items-baseline gap-2 text-text-secondary">
        {slot(
          c.groupPaid,
          <span key={paid} className="font-display text-[56px] leading-none font-extrabold text-text motion-safe:animate-rise">
            {ltr(`${paid}/${total}`)}
          </span>,
        )}
      </p>
      <ul className="flex flex-col gap-2.5">
        {members.map((m) => {
          const due = payable(m, now)
          const time = due ? formatTime(m.reserved_until!, event.timezone, intl) : ''
          return (
            <li key={m.order_id} data-member className="flex flex-wrap items-center gap-3 rounded-control bg-ground p-3">
              <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-pill bg-surface2 font-display text-base font-bold">
                {initials(m.holder_label)}
              </span>
              <div className="flex min-w-0 flex-1 flex-col">
                <bdi id={`member-${m.order_id}`} className="font-semibold">
                  {m.holder_label}
                </bdi>
                <span className="text-body-small text-text-muted">{formatDh(m.total_mad)}</span>
              </div>
              <span
                className={`flex items-center gap-1.5 rounded-chip px-3 py-1 text-xs font-semibold ${
                  m.status === 'PAID' ? 'bg-success-bg text-success-text' : due ? 'bg-accent/10 text-accent-hover' : 'bg-surface2 text-text-muted'
                }`}
              >
                {/* ponytail: on the web the paid check settles in once per mount (again on a reload); the app keeps
                    seen member orders per phone (Q108). Persist them here too if reloads replaying it bother anyone. */}
                {m.status === 'PAID' && <CheckIcon className="size-3.5 motion-safe:animate-mark" />}
                {m.status === 'PAID' ? c.chipPaid : due ? fmt(c.chipDue, { time }) : c.chipExpired}
              </span>
              {due && m.pay_url && (
                <div className="flex w-full flex-wrap gap-2.5">
                  <a
                    href={whatsappUrl(fmt(c.remindText, { event: event.title, time }), m.pay_url)}
                    target="_blank"
                    rel="noopener noreferrer"
                    aria-describedby={`member-${m.order_id}`}
                    className="flex min-h-touch-min items-center rounded-chip bg-accent px-3.5 text-sm font-semibold text-on-accent hover:bg-accent-hover"
                  >
                    {c.remind}
                  </a>
                  <CopyCode code={m.pay_url} label={c.copyLink} done={c.linkCopied} failed={c.linkCopyFailed} dark describedBy={`member-${m.order_id}`} />
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
