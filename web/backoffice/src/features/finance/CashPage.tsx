import { useState, type ReactNode } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { Banknote, Download, Landmark, Scale, Store, Wallet } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { BrandEmpty, ErrorNotice, LoadingRows, STICKY_TABLE } from '@/components/page'
import { Kpi } from '@/components/kpi'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, FieldError, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { fr } from '@/i18n/fr'
import { formatDh } from '@/lib/money'
import { addDays, formatDateTime, formatDay, todayIn } from '@/lib/time'
import { cn } from '@/lib/utils'
import { useCashCsv, useCashReport, type CashRange, type CashReport } from './api'

/** The API refuses a range of more local days than this (422), both ends included. */
const MAX_DAYS = 92

type Collector = keyof typeof fr.finance.cash.collectors

/** Local dates as typed; `range` is what the report shows (the last valid submit). */
export type Days = { from: string; to: string }
const RANGE_FIELDS: (keyof Days)[] = ['from', 'to']

/** B8 Finance › Encaissements: `/finance/cash`, in the venue zone (`FinancePage`). */
export function CashTab({ zone }: { zone: string }) {
  const { t } = useTranslation()
  const [days, setDays] = useState<Days>(() => {
    const today = todayIn(zone)
    return { from: addDays(today, -6), to: today }
  })
  // Sent as picked: the server computes the instants (04 §/org/finance/cash).
  const range: CashRange = { ...days, timezone: zone }
  const report = useCashReport(range)
  const csv = useCashCsv()

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <RangeForm id="cash" initial={days} onSubmit={setDays} />
        <Button variant="outline" disabled={csv.isPending} onClick={() => csv.mutate({ range, fallback: `encaissements-${days.from}-${days.to}.csv` })}>
          <Download />
          {t('finance.cash.export')}
        </Button>
      </div>
      {report.isPending ? (
        <LoadingRows rows={6} />
      ) : report.isError ? (
        <Card className="py-2">
          <ErrorNotice error={report.error} onRetry={() => void report.refetch()} />
        </Card>
      ) : (
        <ReportBody report={report.data} zone={zone} />
      )}
    </>
  )
}

/** A range of local dates, both included, at most 92 days (the API's limit on both finance reports). `id` prefixes the inputs. */
export function RangeForm({ id, initial, onSubmit }: { id: string; initial: Days; onSubmit: (d: Days) => void }) {
  const { t } = useTranslation()
  const errorId = `${id}-range-error`
  const schema = z
    .object({ from: z.iso.date(t('finance.cash.range.invalid')), to: z.iso.date(t('finance.cash.range.invalid')) })
    .superRefine(({ from, to }, ctx) => {
      if (to < from) return void ctx.addIssue({ code: 'custom', path: ['to'], message: t('finance.cash.range.order') })
      // Calendar dates parse as UTC midnights: their difference is a whole number of days, clock changes aside.
      if ((Date.parse(to) - Date.parse(from)) / 86_400_000 + 1 > MAX_DAYS) ctx.addIssue({ code: 'custom', path: ['to'], message: t('finance.cash.range.tooLong') })
    })
  const form = useForm({ resolver: zodResolver(schema), defaultValues: initial })
  const { errors } = form.formState
  const field = (name: keyof Days) => (
    <Field key={name} data-invalid={!!errors[name]} className="w-44">
      <FieldLabel htmlFor={`${id}-${name}`}>{t(`finance.cash.range.${name}`)}</FieldLabel>
      <Input
        id={`${id}-${name}`}
        type="date"
        aria-invalid={!!errors[name]}
        aria-describedby={errors[name] ? errorId : undefined}
        {...form.register(name)}
      />
    </Field>
  )
  const error = errors.from?.message ?? errors.to?.message
  return (
    <form className="flex flex-col gap-2" onSubmit={form.handleSubmit(onSubmit)} noValidate aria-label={t('finance.cash.range.label')}>
      <div className="flex flex-wrap items-end gap-3">
        {RANGE_FIELDS.map(field)}
        <Button type="submit">{t('finance.cash.range.submit')}</Button>
      </div>
      {/* One message for the range; every invalid date points at it. */}
      {error && <FieldError id={errorId}>{error}</FieldError>}
    </form>
  )
}

function Section({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return (
    <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
      <CardHeader className="border-b pb-5">
        <CardTitle role="heading" aria-level={2}>
          {title}
        </CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      {children}
    </Card>
  )
}

function ReportBody({ report, zone }: { report: CashReport; zone: string }) {
  const { t, i18n } = useTranslation()
  const n = (count: number) => count.toLocaleString(i18n.language)
  const dh = (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) })
  // A gap reads in red only when there is one; zero says so in words.
  const gap = (centimes: number) => (centimes === 0 ? t('finance.cash.noGap') : t('sales.amount', { amount: formatDh(centimes, i18n.language, 'exceptZero') }))
  const gapClass = (centimes: number) => (centimes === 0 ? 'text-muted-foreground' : 'text-destructive')
  // The API's display name; the copy map (then the code) only if it is missing.
  const collector = (name: string | null | undefined, c: string) => name || (Object.hasOwn(fr.finance.cash.collectors, c) ? t(`finance.cash.collectors.${c as Collector}`) : c)
  const { totals } = report
  const payments = report.days.reduce((a, d) => a + d.payments, 0)

  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-5">
        <Kpi index={0} icon={Banknote} label={t('finance.cash.kpi.collected')} value={dh(totals.collected_mad)}>
          {t('finance.cash.kpi.collectedHint')}
        </Kpi>
        <Kpi index={1} icon={Wallet} label={t('finance.cash.kpi.declared')} value={dh(totals.declared_mad)}>
          {t('finance.cash.kpi.declaredHint')}
        </Kpi>
        <Kpi index={2} icon={Scale} label={t('finance.cash.kpi.variance')} value={gap(totals.variance_mad)} valueClassName={cn(totals.variance_mad !== 0 && 'text-destructive')}>
          {t('finance.cash.kpi.varianceHint')}
        </Kpi>
        <Kpi index={3} icon={Store} label={t('finance.cash.kpi.open')} value={n(totals.open_sessions)}>
          {t('finance.cash.kpi.openHint')}
        </Kpi>
        {/* Lot 5 step 5: till cash not yet matched to a bank deposit (B8 Dépôts). */}
        <Kpi index={4} icon={Landmark} label={t('finance.cash.kpi.undeposited')} value={dh(totals.undeposited_mad)} valueClassName={cn(totals.undeposited_mad !== 0 && 'text-warning-foreground')}>
          {t('finance.cash.kpi.undepositedHint')}
        </Kpi>
      </div>

      <Section title={t('finance.cash.days.title')} description={t('finance.cash.days.description')}>
        {report.days.length === 0 ? (
          <BrandEmpty title={t('finance.cash.days.emptyTitle')} description={t('finance.cash.days.empty')} />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('finance.cash.days.day')}</TableHead>
                <TableHead>{t('finance.cash.days.collector')}</TableHead>
                <TableHead>{t('finance.cash.days.method')}</TableHead>
                <TableHead className="text-right">{t('finance.cash.days.payments')}</TableHead>
                <TableHead className="text-right">{t('finance.cash.days.amount')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {/* By day then collector, as the API sends them. */}
              {report.days.map((d) => (
                <TableRow key={`${d.day} ${d.collector} ${d.method}`}>
                  <TableCell className="tabular-nums">{formatDay(d.day, i18n.language)}</TableCell>
                  <TableCell className="font-medium">{collector(d.collector_name, d.collector)}</TableCell>
                  <TableCell className="text-muted-foreground">{t(`sales.methods.${d.method}`)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(d.payments)}</TableCell>
                  <TableCell className="text-right tabular-nums">{dh(d.amount_mad)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow>
                <TableCell colSpan={3} className="font-semibold">
                  {t('finance.cash.total')}
                </TableCell>
                <TableCell className="text-right font-semibold tabular-nums">{n(payments)}</TableCell>
                {/* The server's total, not a client sum: the CSV says the same. */}
                <TableCell className="text-right font-semibold tabular-nums">{dh(totals.collected_mad)}</TableCell>
              </TableRow>
            </TableFooter>
          </Table>
        )}
      </Section>

      <Section title={t('finance.cash.sessions.title')} description={t('finance.cash.sessions.description')}>
        {report.sessions.length === 0 ? (
          <BrandEmpty title={t('finance.cash.sessions.emptyTitle')} description={t('finance.cash.sessions.empty')} />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('finance.cash.sessions.label')}</TableHead>
                <TableHead>{t('finance.cash.sessions.cashier')}</TableHead>
                <TableHead>{t('finance.cash.sessions.opened')}</TableHead>
                <TableHead>{t('finance.cash.sessions.closed')}</TableHead>
                <TableHead className="text-right">{t('finance.cash.sessions.orders')}</TableHead>
                <TableHead className="text-right">{t('finance.cash.sessions.expected')}</TableHead>
                <TableHead className="text-right">{t('finance.cash.sessions.declared')}</TableHead>
                <TableHead className="text-right">{t('finance.cash.sessions.variance')}</TableHead>
                <TableHead>{t('finance.cash.sessions.deposit')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {report.sessions.map((s) => (
                <TableRow key={s.id}>
                  <TableCell className="font-medium">{s.label}</TableCell>
                  <TableCell>{s.staff.name}</TableCell>
                  <TableCell className="tabular-nums">{formatDateTime(s.opened_at, i18n.language, zone)}</TableCell>
                  <TableCell className="tabular-nums">
                    {s.closed_at ? (
                      formatDateTime(s.closed_at, i18n.language, zone)
                    ) : (
                      <Badge variant="outline" className="rounded-full border-warning-border bg-warning-muted text-warning-foreground">
                        {t('finance.cash.sessions.open')}
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{n(s.orders)}</TableCell>
                  <TableCell className="text-right tabular-nums">{dh(s.expected_mad)}</TableCell>
                  <TableCell className="text-right tabular-nums">{s.declared_mad === null ? <Pending /> : dh(s.declared_mad)}</TableCell>
                  <TableCell className={cn('text-right tabular-nums', s.variance_mad !== null && gapClass(s.variance_mad))}>
                    {s.variance_mad === null ? <Pending /> : gap(s.variance_mad)}
                  </TableCell>
                  <TableCell>
                    {s.deposit ? (
                      <span className="tabular-nums">{t('finance.cash.sessions.depositOf', { reference: s.deposit.reference, date: formatDay(s.deposit.deposited_on, i18n.language) })}</span>
                    ) : s.closed_at ? (
                      <Badge variant="outline" className="rounded-full border-warning-border bg-warning-muted text-warning-foreground">
                        {t('finance.cash.sessions.notDeposited')}
                      </Badge>
                    ) : (
                      // A till still open cannot be deposited yet.
                      <span className="text-muted-foreground">{t('finance.cash.sessions.noDeposit')}</span>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Section>
    </>
  )
}

/** Declared and gap of a till still open: known at its close. */
function Pending() {
  const { t } = useTranslation()
  return <span className="text-muted-foreground">{t('finance.cash.sessions.atClose')}</span>
}
