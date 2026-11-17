import { useState } from 'react'
import { ArrowRight, Download, FileText, HandCoins, Hourglass, Undo2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { BrandEmpty, ErrorNotice, LoadingRows } from '@/components/page'
import { Kpi } from '@/components/kpi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatDh } from '@/lib/money'
import { formatBp } from '@/lib/percent'
import { addDays, formatDay, todayIn } from '@/lib/time'
import { cn } from '@/lib/utils'
import { RangeForm, Section, type Days } from './CashPage'
import { SettlementSheet, StatusBadge } from './SettlementSheet'
import { invoicePdfPath, settlementsCsvPath, useOrgDownload, useSettlements, type Settlements } from './api'

/**
 * B8 Finance › Relevés (Lot 5 step 5b, 04 §Finance): the contract card, what is not yet in a statement, what Jomhor
 * still owes, and the daily statements of the range. ADMIN and FINANCE only (`FinancePage` keeps OPS out). Every amount
 * is the server's; the only client sum is frais TTC, an integer addition of the server's HT and VAT centimes.
 */
/** The last 30 local days in `zone`, today included. */
const lastDays = (zone: string): Days => {
  const today = todayIn(zone)
  return { from: addDays(today, -29), to: today }
}

export function SettlementsTab({ zone }: { zone: string }) {
  const { t } = useTranslation()
  // Statement days are in the finance timezone, which only the first answer tells: until a range is picked, the default
  // follows it (one refetch when it differs from the venue zone).
  const [range, setRange] = useState(() => ({ zone, days: lastDays(zone), picked: false }))
  const { days } = range
  const list = useSettlements(days)
  const timezone = list.data?.timezone
  if (timezone && !range.picked && timezone !== range.zone) setRange({ zone: timezone, days: lastDays(timezone), picked: false })
  const file = useOrgDownload()
  // `n` counts openings: the sheet's key, so each opening starts fresh (and its dialog with an empty form).
  const [sheet, setSheet] = useState<{ id: string; open: boolean; n: number } | null>(null)

  if (list.isPending) return <LoadingRows rows={6} />
  if (list.isError)
    return (
      <Card className="py-2">
        <ErrorNotice error={list.error} onRetry={() => void list.refetch()} />
      </Card>
    )
  const data = list.data
  const invoice = (inv: { id: string; number: string }) => file.mutate({ path: invoicePdfPath(inv.id), fallback: `facture-${inv.number}.pdf` })

  return (
    <>
      {data.settings ? (
        <>
          <ContractCard settings={data.settings} />
          <Kpis data={data} />
        </>
      ) : (
        <Card>
          <BrandEmpty title={t('finance.settlements.inactive.title')} description={t('finance.settlements.inactive.description')} />
        </Card>
      )}
      {/* Not activated and nothing ever issued: the empty state says it all. */}
      {(data.settings || data.settlements.length > 0) && (
        <>
          <div className="flex flex-wrap items-end justify-between gap-4">
            <RangeForm key={range.zone} id="settlements" initial={days} onSubmit={(d) => setRange((r) => ({ ...r, days: d, picked: true }))} />
            <Button variant="outline" disabled={file.isPending} onClick={() => file.mutate({ path: settlementsCsvPath(days), fallback: `releves-${days.from}-${days.to}.csv` })}>
              <Download />
              {t('finance.settlements.export')}
            </Button>
          </div>
          <StatementList
            data={data}
            onOpen={(id) => setSheet((s) => ({ id, open: true, n: (s?.n ?? 0) + 1 }))}
            onInvoice={invoice}
            busy={file.isPending}
          />
        </>
      )}
      {sheet && (
        <SettlementSheet
          key={sheet.n}
          id={sheet.id}
          zone={data.timezone}
          vatRateBp={data.vat_rate_bp}
          open={sheet.open}
          onOpenChange={(open) => setSheet((s) => s && { ...s, open })}
          onInvoice={invoice}
          busy={file.isPending}
        />
      )}
    </>
  )
}

/** Set by Jomhor ops from the contract and a bank certificate; never edited here. Only the RIB's last 4 digits exist client-side. */
function ContractCard({ settings }: { settings: NonNullable<Settlements['settings']> }) {
  const { t, i18n } = useTranslation()
  const items: [string, string][] = [
    [t('finance.settlements.contract.legalName'), settings.legal_name],
    [t('finance.settlements.contract.ice'), settings.ice],
    // slice: never more than 4 digits on screen, whatever arrives.
    [t('finance.settlements.contract.rib'), t('finance.settlements.contract.ribMasked', { last4: settings.rib_last4.slice(-4) })],
    [t('finance.settlements.contract.fee'), t('finance.settlements.contract.feeRate', { rate: formatBp(settings.fee_rate_bp, i18n.language) })],
    [t('finance.settlements.contract.since'), formatDay(settings.start_on, i18n.language)],
  ]
  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {t('finance.settlements.contract.title')}
        </CardTitle>
        <CardDescription>{t('finance.settlements.contract.managed')}</CardDescription>
      </CardHeader>
      <CardContent>
        <dl className="grid gap-x-6 gap-y-4 sm:grid-cols-2 lg:grid-cols-5">
          {items.map(([label, value]) => (
            <div key={label} className="flex min-w-0 flex-col gap-1">
              <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
              <dd className="truncate text-sm font-medium tabular-nums" title={value}>
                {value}
              </dd>
            </div>
          ))}
        </dl>
      </CardContent>
    </Card>
  )
}

function Kpis({ data }: { data: Settlements }) {
  const { t, i18n } = useTranslation()
  const dh = (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) })
  const { open, due, refunds_required: refunds } = data
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {open && (
        <Kpi index={0} icon={Hourglass} label={t('finance.settlements.kpi.open')} value={dh(open.collected_mad)}>
          <span>{t('finance.settlements.kpi.openOnline')}</span>
          <span className="tabular-nums">{t('finance.settlements.kpi.openTill', { amount: dh(open.till_mad) })}</span>
          <span className="tabular-nums">
            {t('finance.settlements.kpi.openSince', { count: open.payments, date: formatDay(todayIn(data.timezone, Date.parse(open.since)), i18n.language) })}
          </span>
        </Kpi>
      )}
      <Kpi index={1} icon={HandCoins} label={t('finance.settlements.kpi.due')} value={dh(due.amount_mad)}>
        <span>{t('finance.settlements.kpi.dueHint', { count: due.count })}</span>
        {due.overdue > 0 && <span className="font-medium text-destructive">{t('finance.settlements.kpi.dueLate', { count: due.overdue })}</span>}
      </Kpi>
      <Kpi index={2} icon={Undo2} label={t('finance.settlements.kpi.refunds')} value={t('finance.settlements.kpi.refundsValue', { count: refunds.count, amount: dh(refunds.amount_mad) })}>
        <span>{t('finance.settlements.kpi.refundsHint')}</span>
        <Link to="/sales" className="inline-flex items-center gap-1 font-medium text-foreground underline-offset-4 hover:underline">
          {t('finance.settlements.kpi.refundsLink')}
          <ArrowRight className="size-3" aria-hidden="true" />
        </Link>
      </Kpi>
    </div>
  )
}

function StatementList(props: { data: Settlements; onOpen: (id: string) => void; onInvoice: (inv: { id: string; number: string }) => void; busy: boolean }) {
  const { t, i18n } = useTranslation()
  const dh = (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) })
  const { settlements, timezone } = props.data
  const right = 'text-right tabular-nums'
  return (
    <Section title={t('finance.settlements.list.title')} description={t('finance.settlements.list.description', { zone: timezone })}>
      {settlements.length === 0 ? (
        <BrandEmpty title={t('finance.settlements.list.emptyTitle')} description={t('finance.settlements.list.empty')} />
      ) : (
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>{t('finance.settlements.list.day')}</TableHead>
              <TableHead className="text-right">{t('finance.settlements.list.collected')}</TableHead>
              <TableHead className="text-right">{t('finance.settlements.list.till')}</TableHead>
              <TableHead className="text-right">{t('finance.settlements.list.fee')}</TableHead>
              <TableHead className="text-right">{t('finance.settlements.list.carried')}</TableHead>
              <TableHead className="text-right">{t('finance.settlements.list.payout')}</TableHead>
              <TableHead>{t('finance.settlements.list.dueOn')}</TableHead>
              <TableHead>{t('finance.settlements.list.status')}</TableHead>
              <TableHead>{t('finance.settlements.list.invoice')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {/* Newest day first, as the API sends them. */}
            {settlements.map((s) => {
              const day = formatDay(s.day, i18n.language)
              return (
                <TableRow key={s.id}>
                  <TableCell>
                    <Button variant="link" className="h-auto p-0 font-medium tabular-nums" aria-label={t('finance.settlements.list.open', { day })} onClick={() => props.onOpen(s.id)}>
                      {day}
                    </Button>
                  </TableCell>
                  <TableCell className={right}>{dh(s.collected_mad)}</TableCell>
                  <TableCell className={cn(right, 'text-muted-foreground')}>{dh(s.till_mad)}</TableCell>
                  {/* TTC = HT + TVA, both the server's integer centimes. */}
                  <TableCell className={right}>{dh(s.fee_ht_mad + s.fee_vat_mad)}</TableCell>
                  <TableCell className={cn(right, s.carried_in_mad < 0 ? 'text-destructive' : 'text-muted-foreground')}>
                    {s.carried_in_mad === 0 ? t('finance.settlements.none') : t('sales.amount', { amount: formatDh(s.carried_in_mad, i18n.language) })}
                  </TableCell>
                  <TableCell className={cn(right, 'font-semibold')}>{dh(s.payout_mad)}</TableCell>
                  <TableCell className="tabular-nums">{s.status === 'NONE' ? <span className="text-muted-foreground">{t('finance.settlements.none')}</span> : formatDay(s.due_on, i18n.language)}</TableCell>
                  <TableCell>
                    <StatusBadge settlement={s} zone={timezone} />
                  </TableCell>
                  <TableCell>
                    {s.invoice ? (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="-ml-2 tabular-nums"
                        disabled={props.busy}
                        aria-label={t('finance.settlements.invoiceLabel', { number: s.invoice.number })}
                        onClick={() => props.onInvoice(s.invoice!)}
                      >
                        <FileText />
                        {s.invoice.number}
                      </Button>
                    ) : (
                      <span className="text-muted-foreground">{t('finance.settlements.none')}</span>
                    )}
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
    </Section>
  )
}
