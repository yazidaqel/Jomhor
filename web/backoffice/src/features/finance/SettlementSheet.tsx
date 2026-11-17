import { useState, type ReactNode } from 'react'
import { BadgeCheck, FileText } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { ErrorNotice, LoadingRows } from '@/components/page'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useOrganizer } from '@/features/auth/organizer'
import { fr } from '@/i18n/fr'
import { formatDh } from '@/lib/money'
import { formatBp } from '@/lib/percent'
import { formatDateTime, formatDay, todayIn } from '@/lib/time'
import { cn } from '@/lib/utils'
import { PayoutDialog } from './PayoutDialog'
import { SETTLEMENT_ROLES, useSettlement, type Settlement, type SettlementDetail, type SettlementLine } from './api'

type Method = keyof typeof fr.sales.methods
const PILL = 'rounded-full'

/** À verser · En retard (red) · Versé le {date} · Rien à verser. `zone`: the finance timezone (`paid_at`'s local day). */
export function StatusBadge({ settlement: s, zone }: { settlement: Settlement; zone: string }) {
  const { t, i18n } = useTranslation()
  if (s.status === 'PAID')
    return (
      <Badge variant="outline" className={cn(PILL, 'border-success-border bg-success-muted text-success-foreground')}>
        {s.paid_at ? t('finance.settlements.status.paidOn', { date: formatDay(todayIn(zone, Date.parse(s.paid_at)), i18n.language) }) : t('finance.settlements.status.paid')}
      </Badge>
    )
  if (s.status === 'NONE')
    return (
      <Badge variant="outline" className={cn(PILL, 'text-muted-foreground')}>
        {t('finance.settlements.status.none')}
      </Badge>
    )
  if (s.overdue)
    return (
      <Badge variant="destructive" className={PILL}>
        {t('finance.settlements.status.overdue')}
      </Badge>
    )
  return (
    <Badge variant="outline" className={cn(PILL, 'border-warning-border bg-warning-muted text-warning-foreground')}>
      {t('finance.settlements.status.due')}
    </Badge>
  )
}

/**
 * A statement (`GET /org/finance/settlements/{id}`): the server's formula, the payout, the invoice and the lines per
 * event or product × method × collector. "Versement reçu" for ADMIN/FINANCE while it is `DUE`.
 */
export function SettlementSheet(props: {
  id: string
  zone: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onInvoice: (inv: { id: string; number: string }) => void
  busy: boolean
}) {
  const { t, i18n } = useTranslation()
  const canPay = SETTLEMENT_ROLES.includes(useOrganizer().role)
  const detail = useSettlement(props.id)
  const [paying, setPaying] = useState<{ open: boolean; n: number } | null>(null)
  const s = detail.data

  return (
    <Sheet open={props.open} onOpenChange={props.onOpenChange}>
      <SheetContent className="w-full gap-0 sm:max-w-xl" closeLabel={t('common.close')}>
        <SheetHeader className="border-b p-5">
          <SheetTitle>{s ? t('finance.settlements.sheet.title', { day: formatDay(s.day, i18n.language) }) : t('finance.settlements.sheet.loading')}</SheetTitle>
          <SheetDescription>{t('finance.settlements.sheet.description')}</SheetDescription>
          {s && (
            <div className="pt-1">
              <StatusBadge settlement={s} zone={props.zone} />
            </div>
          )}
        </SheetHeader>
        <div className="flex flex-1 flex-col gap-6 overflow-y-auto p-5">
          {detail.isPending ? (
            <LoadingRows rows={5} />
          ) : detail.isError ? (
            <ErrorNotice error={detail.error} onRetry={() => void detail.refetch()} className="px-0" />
          ) : (
            <>
              <Formula s={detail.data} />
              <Payout s={detail.data} zone={props.zone} onInvoice={props.onInvoice} busy={props.busy} />
              <Lines lines={detail.data.lines} />
            </>
          )}
        </div>
        <SheetFooter className="border-t p-5 sm:flex-row sm:justify-end">
          <Button type="button" variant="outline" onClick={() => props.onOpenChange(false)}>
            {t('common.close')}
          </Button>
          {canPay && s?.status === 'DUE' && (
            <Button onClick={() => setPaying((p) => ({ open: true, n: (p?.n ?? 0) + 1 }))}>
              <BadgeCheck />
              {t('finance.settlements.payout.action')}
            </Button>
          )}
        </SheetFooter>
        {/* Inside the sheet's content: Radix stacks the dialog over it (focus, Escape) instead of fighting it. */}
        {paying && s && <PayoutDialog key={paying.n} settlement={s} open={paying.open} onOpenChange={(open) => setPaying((p) => p && { ...p, open })} />}
      </SheetContent>
    </Sheet>
  )
}

/** "En ligne − Frais HT − TVA + Report = À verser", each figure as the server stored it: no money maths here. */
function Formula({ s }: { s: SettlementDetail }) {
  const { t, i18n } = useTranslation()
  const dh = (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) })
  const rows: [string, string, string?][] = [
    [t('finance.settlements.formula.collected'), dh(s.collected_mad)],
    [t('finance.settlements.formula.feeHt', { rate: formatBp(s.fee_rate_bp, i18n.language) }), t('finance.settlements.formula.minus', { amount: dh(s.fee_ht_mad) })],
    [t('finance.settlements.formula.vat'), t('finance.settlements.formula.minus', { amount: dh(s.fee_vat_mad) })],
    [t('finance.settlements.formula.carried'), dh(s.carried_in_mad), s.carried_in_mad < 0 ? 'text-destructive' : undefined],
  ]
  return (
    <section aria-labelledby="settlement-formula" className="flex flex-col gap-3">
      <h3 id="settlement-formula" className="text-sm font-medium">
        {t('finance.settlements.formula.title')}
      </h3>
      <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-2 rounded-lg bg-muted/60 px-4 py-3 text-sm tabular-nums">
        {rows.map(([label, value, cls]) => (
          <div key={label} className="contents">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className={cn('text-right', cls)}>{value}</dd>
          </div>
        ))}
        <div className="col-span-2 border-t" aria-hidden="true" />
        <dt className="font-medium">{t('finance.settlements.formula.payout')}</dt>
        <dd data-testid="settlement-payout" className="text-right text-base font-semibold">
          {dh(s.payout_mad)}
        </dd>
      </dl>
      <p className="text-xs text-muted-foreground">{t('finance.settlements.formula.till', { amount: dh(s.till_mad) })}</p>
      {s.status === 'NONE' && <p className="text-xs text-muted-foreground">{t('finance.settlements.formula.nothing')}</p>}
    </section>
  )
}

function Payout({ s, zone, onInvoice, busy }: { s: SettlementDetail; zone: string; onInvoice: (inv: { id: string; number: string }) => void; busy: boolean }) {
  const { t, i18n } = useTranslation()
  const dh = (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) })
  const dueOn = formatDay(s.due_on, i18n.language)
  return (
    <section className="flex flex-col gap-3 rounded-lg border px-4 py-3 text-sm">
      {s.status === 'PAID' ? (
        <p>
          {t('finance.settlements.payout.paid', { date: s.paid_at ? formatDateTime(s.paid_at, i18n.language, zone) : '', name: s.paid_by?.name ?? '' })}
          <span className="block text-muted-foreground">{t('finance.settlements.payout.paidReference', { reference: s.payout_reference ?? '' })}</span>
        </p>
      ) : s.status === 'DUE' ? (
        <p className={cn(s.overdue && 'font-medium text-destructive')}>
          {t(s.overdue ? 'finance.settlements.payout.overdue' : 'finance.settlements.payout.due', { amount: dh(s.payout_mad), date: dueOn })}
        </p>
      ) : (
        <p className="text-muted-foreground">{t('finance.settlements.payout.none')}</p>
      )}
      <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-3">
        {s.invoice ? (
          <>
            <span className="text-muted-foreground">{t('finance.settlements.sheet.invoice', { number: s.invoice.number })}</span>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => onInvoice(s.invoice!)}>
              <FileText />
              {t('finance.settlements.sheet.invoicePdf')}
            </Button>
          </>
        ) : (
          <span className="text-muted-foreground">{t('finance.settlements.sheet.noInvoice')}</span>
        )}
      </div>
    </section>
  )
}

/** Lines grouped by subject (an event or a product), in the API's order: title, then method, then collector. */
function Lines({ lines }: { lines: SettlementLine[] }) {
  const { t, i18n } = useTranslation()
  const dh = (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) })
  const method = (m: string) => (Object.hasOwn(fr.sales.methods, m) ? t(`sales.methods.${m as Method}`) : m)
  const groups = new Map<string, SettlementLine[]>()
  for (const l of lines) {
    const key = l.event_id !== null ? `event ${l.event_id}` : `product ${l.product_id}`
    groups.set(key, [...(groups.get(key) ?? []), l])
  }
  return (
    <section aria-labelledby="settlement-lines" className="flex flex-col gap-3">
      <h3 id="settlement-lines" className="text-sm font-medium">
        {t('finance.settlements.lines.title')}
      </h3>
      {lines.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('finance.settlements.lines.empty')}</p>
      ) : (
        <div className="overflow-hidden rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('finance.settlements.lines.method')}</TableHead>
                <TableHead>{t('finance.settlements.lines.collector')}</TableHead>
                <TableHead className="text-right">{t('finance.settlements.lines.payments')}</TableHead>
                <TableHead className="text-right">{t('finance.settlements.lines.amount')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...groups].map(([key, group]) => (
                <Group key={key} title={group[0]!.title} kind={group[0]!.event_id !== null ? 'event' : 'product'}>
                  {group.map((l) => (
                    <TableRow key={`${l.method} ${l.collector}`}>
                      <TableCell className="pl-6">{method(l.method)}</TableCell>
                      <TableCell className="text-muted-foreground">{l.collector_name || l.collector}</TableCell>
                      <TableCell className="text-right tabular-nums">{l.payments.toLocaleString(i18n.language)}</TableCell>
                      <TableCell className="text-right tabular-nums">{dh(l.amount_mad)}</TableCell>
                    </TableRow>
                  ))}
                </Group>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </section>
  )
}

function Group({ title, kind, children }: { title: string; kind: 'event' | 'product'; children: ReactNode }) {
  const { t } = useTranslation()
  return (
    <>
      <TableRow className="bg-muted/40 hover:bg-muted/40">
        <TableCell colSpan={4} className="font-medium whitespace-normal">
          {title} <span className="ml-1 text-xs font-normal text-muted-foreground">{t(`finance.settlements.lines.${kind}`)}</span>
        </TableCell>
      </TableRow>
      {children}
    </>
  )
}
