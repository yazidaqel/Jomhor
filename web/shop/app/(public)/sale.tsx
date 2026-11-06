import type { SaleState, ShopEvent } from '@/lib/events'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDateTime } from '@/lib/time'

/** Shared by W1 and W2: the event's sale status in words, e.g. "Vente le Sam. 7 févr. 2026 · 10:00". */
export function saleLabel(d: Dict, e: ShopEvent, state: SaleState): string {
  switch (state) {
    case 'ON_SALE': {
      const n = e.per_account_limit
      return n > 0 ? `${d.sale.ON_SALE} · ${n === 1 ? d.sale.limitOne : fmt(d.sale.limit, { n })}` : d.sale.ON_SALE
    }
    case 'NOT_STARTED':
      return fmt(d.sale.NOT_STARTED, { date: formatDateTime(e.sale_starts_at!, e.timezone, d.meta.intl) })
    default:
      return d.sale[state]
  }
}

export const kicker = (d: Dict, e: ShopEvent) =>
  [e.competition, e.matchday === null ? null : fmt(d.event.matchday, { n: e.matchday })].filter(Boolean).join(' · ')

export function SaleChip({ d, event, state }: { d: Dict; event: ShopEvent; state: SaleState }) {
  const tone = state === 'ON_SALE' ? 'border border-success bg-success-bg text-success-text' : 'bg-ground text-text-secondary'
  return (
    <span data-sale={state} className={`inline-block rounded-chip px-3 py-2 text-sm ${tone}`}>
      {saleLabel(d, event, state)}
    </span>
  )
}
