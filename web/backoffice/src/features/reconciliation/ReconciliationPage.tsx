import type { TFunction } from 'i18next'
import { Copy, Download, Hand, LogIn, ShieldX, Ticket, TriangleAlert, UserX } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Navigate, useParams } from 'react-router'
import { BrandEmpty, ErrorNotice, PageHeader, STICKY_TABLE } from '@/components/page'
import { FillBar } from '@/components/fill-bar'
import { Kpi } from '@/components/kpi'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useEvent } from '@/features/events/api'
import { fr } from '@/i18n/fr'
import { ApiError } from '@/lib/api'
import { percent } from '@/lib/percent'
import { formatAgo, formatDateTime } from '@/lib/time'
import { useNow } from '@/lib/useNow'
import { cn } from '@/lib/utils'
import { sum, useReconciliation, useReconciliationCsv, type Reconciliation } from './api'

type Result = keyof typeof fr.live.results
type Reason = keyof typeof fr.reconciliation.reasons

/** B5 Réconciliation: `/events/:id/reconciliation`. ADMIN, OPS and FINANCE read. */
export function ReconciliationPage() {
  const id = useParams().id ?? ''
  // Same id rule as the event page: integers only, `/01` and `/1` are one query key.
  if (!/^\d+$/.test(id)) return <Navigate to="/events" replace state={{ notFound: true }} />
  return <EventReconciliationPage id={String(Number(id))} />
}

const isNotFound = (e: unknown) => e instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(e.code)

/** `Déjà utilisé : 38 · Mauvaise porte : 14`, biggest first; unknown codes as themselves. */
function breakdown(t: TFunction, counts: Record<string, number | undefined>, label: (code: string) => string) {
  return Object.entries(counts)
    .filter((e): e is [string, number] => (e[1] ?? 0) > 0)
    .sort((a, b) => b[1] - a[1])
    .map(([code, count]) => t('reconciliation.kpi.part', { label: label(code), count }))
    .join(' · ')
}

function EventReconciliationPage({ id }: { id: string }) {
  const { t, i18n } = useTranslation()
  const event = useEvent(id)
  const recon = useReconciliation(id)
  const csv = useReconciliationCsv(id)

  if (isNotFound(event.error) || isNotFound(recon.error)) return <Navigate to="/events" replace state={{ notFound: true }} />
  if (event.isPending) return <ReconciliationSkeleton />
  if (!event.data) return <ErrorNotice error={event.error} onRetry={() => void event.refetch()} />

  const zone = event.data.timezone
  const data = recon.data
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        crumbs={[
          { label: t('events.title'), to: '/events' },
          { label: event.data.title, to: `/events/${encodeURIComponent(event.data.id)}` },
          { label: t('reconciliation.title') },
        ]}
        title={t('reconciliation.heading', { title: event.data.title, date: formatDateTime(event.data.starts_at, i18n.language, zone) })}
        badge={data && <FinalBadge final={data.final} />}
        description={data && `${why(t, data)} ${t('reconciliation.generated', { date: formatDateTime(data.generated_at, i18n.language, zone) })}`}
        actions={
          <Button variant="outline" disabled={csv.isPending} onClick={() => csv.mutate()}>
            <Download />
            {t('reconciliation.export')}
          </Button>
        }
      />
      {data && recon.isError && <UpdateFailed updatedAt={recon.dataUpdatedAt} />}
      {recon.isPending ? (
        <ReconciliationSkeleton header={false} />
      ) : !data ? (
        <Card className="py-2">
          <ErrorNotice error={recon.error} onRetry={() => void recon.refetch()} />
        </Card>
      ) : (
        <ReconciliationBody recon={data} zone={zone} />
      )}
    </div>
  )
}

/** A failed background refresh: the last figures stay, with their age (B4 does the same). */
function UpdateFailed({ updatedAt }: { updatedAt: number }) {
  const { t, i18n } = useTranslation()
  const now = useNow(1000)
  return (
    <p role="status" className="-mt-2 text-sm font-medium text-warning-foreground tabular-nums">
      {t('reconciliation.updateFailed', { ago: formatAgo(now - updatedAt, i18n.language) })}
    </p>
  )
}

/** Why the figures can still move (or that they will not). */
function why(t: TFunction, r: Reconciliation) {
  if (r.final) return t('reconciliation.why.final', { count: r.devices.assigned })
  if (r.status !== 'CLOSED') return t('reconciliation.why.notClosed')
  return t('reconciliation.why.unacked', { count: r.devices.unsynced.length })
}

function FinalBadge({ final }: { final: boolean }) {
  const { t } = useTranslation()
  return (
    <Badge
      variant="outline"
      data-final={final}
      className={cn(
        'gap-1.5 rounded-full px-2.5 font-medium',
        final ? 'border-success-border bg-success-muted text-success-foreground' : 'border-warning-border bg-warning-muted text-warning-foreground',
      )}
    >
      <span className={cn('size-1.5 rounded-full', final ? 'bg-success' : 'bg-warning')} aria-hidden="true" />
      {t(final ? 'reconciliation.badge.final' : 'reconciliation.badge.provisional')}
    </Badge>
  )
}

function ReconciliationSkeleton({ header = true }: { header?: boolean }) {
  const { t } = useTranslation()
  return (
    <div aria-busy="true" className="flex flex-col gap-6">
      <span className="sr-only">{t('common.loading')}</span>
      {header && <Skeleton className="h-16 w-96" />}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {[0, 1, 2, 3, 4, 5].map((i) => (
          <Skeleton key={i} className="h-32 rounded-xl" />
        ))}
      </div>
      <Skeleton className="h-72 rounded-xl" />
    </div>
  )
}

function ReconciliationBody({ recon, zone }: { recon: Reconciliation; zone: string }) {
  const { t, i18n } = useTranslation()
  const n = (v: number) => v.toLocaleString(i18n.language)
  const pct = (part: number, whole: number) => percent(part, whole, i18n.language)
  const result = (code: string) => (Object.hasOwn(fr.live.results, code) ? t(`live.results.${code as Result}`) : code)
  const reason = (code: string) => (Object.hasOwn(fr.reconciliation.reasons, code) ? t(`reconciliation.reasons.${code as Reason}`) : code)
  const { totals } = recon
  const refused = sum(totals.refused_by_result)
  const overrides = sum(totals.overrides_by_reason)
  const unsynced = recon.devices.unsynced

  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <Kpi index={0} icon={Ticket} label={t('reconciliation.kpi.sold')} value={n(totals.sold)}>
          <span>{t('reconciliation.kpi.soldHint')}</span>
        </Kpi>
        <Kpi index={1} icon={LogIn} label={t('reconciliation.kpi.entered')} value={n(totals.entered)}>
          <FillBar paid={totals.entered} quota={totals.sold} className="mt-1" />
          <span>{t('reconciliation.kpi.enteredHint', { percent: pct(totals.entered, totals.sold) })}</span>
        </Kpi>
        <Kpi index={2} icon={UserX} label={t('reconciliation.kpi.noShow')} value={n(totals.no_show)}>
          <span>{t('reconciliation.kpi.noShowHint', { percent: pct(totals.no_show, totals.sold) })}</span>
        </Kpi>
        <Kpi index={3} icon={ShieldX} label={t('reconciliation.kpi.refused')} value={n(refused)}>
          <span>{refused === 0 ? t('reconciliation.kpi.refusedNone') : breakdown(t, totals.refused_by_result, result)}</span>
        </Kpi>
        <Kpi index={4} icon={Copy} label={t('reconciliation.kpi.lateDuplicates')} value={n(totals.duplicates_late)}>
          <span>{t('reconciliation.kpi.lateDuplicatesHint')}</span>
        </Kpi>
        <Kpi index={5} icon={Hand} label={t('reconciliation.kpi.overrides')} value={n(overrides)}>
          <span>{overrides === 0 ? t('reconciliation.kpi.overridesNone') : breakdown(t, totals.overrides_by_reason, reason)}</span>
        </Kpi>
      </div>

      {unsynced.length > 0 &&
        (recon.status !== 'CLOSED' ? (
          // Before the close, no device has acked yet: the normal state, said once, in neutral.
          <p className="text-sm text-muted-foreground">{t('reconciliation.unsynced.open', { count: unsynced.length })}</p>
        ) : (
          // A warning, never an error: these devices validated offline and their entries are not lost.
          <section
            aria-labelledby="unsynced-title"
            className="flex items-start gap-3 rounded-xl border border-warning-border bg-warning-muted px-4 py-3 text-sm text-warning-foreground"
          >
            <TriangleAlert className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden="true" />
            <div className="min-w-0 flex-1 space-y-1">
              <h2 id="unsynced-title" className="font-semibold">
                {t('reconciliation.unsynced.title', { count: unsynced.length })}
              </h2>
              <p>{t('reconciliation.unsynced.body')}</p>
              <ul className="grid gap-x-6 gap-y-0.5 text-xs sm:grid-cols-2 lg:grid-cols-3">
                {unsynced.map((d) => (
                  <li key={d.device_id}>
                    {d.last_seen_at
                      ? t('reconciliation.unsynced.lastSeen', { label: d.label, gate: d.gate_code, date: formatDateTime(d.last_seen_at, i18n.language, zone) })
                      : t('reconciliation.unsynced.never', { label: d.label, gate: d.gate_code })}
                  </li>
                ))}
              </ul>
            </div>
          </section>
        ))}

      <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
        <CardHeader className="border-b pb-5">
          <CardTitle id="recon-zones-title" role="heading" aria-level={2}>
            {t('reconciliation.zones.title')}
          </CardTitle>
        </CardHeader>
        {recon.zones.length === 0 ? (
          <BrandEmpty title={t('reconciliation.zones.emptyTitle')} description={t('reconciliation.zones.empty')} />
        ) : (
          <Table className="min-w-[680px]" aria-labelledby="recon-zones-title">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead scope="col">{t('reconciliation.zones.zone')}</TableHead>
                <TableHead scope="col" className="text-right">{t('reconciliation.zones.sold')}</TableHead>
                <TableHead scope="col" className="text-right">{t('reconciliation.zones.entered')}</TableHead>
                <TableHead scope="col" className="text-right">{t('reconciliation.zones.rate')}</TableHead>
                <TableHead scope="col" className="text-right">{t('reconciliation.zones.noShow')}</TableHead>
                <TableHead scope="col" className="text-right">{t('reconciliation.zones.overrides')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {recon.zones.map((z) => (
                <TableRow key={z.zone_id}>
                  <TableHead scope="row">
                    <span className="font-semibold">{z.code}</span>{' '}
                    <span className="ml-1 font-normal text-muted-foreground">{z.name}</span>
                  </TableHead>
                  <TableCell className="text-right tabular-nums">{n(z.sold)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(z.entered)}</TableCell>
                  <TableCell className="text-right tabular-nums">{pct(z.entered, z.sold)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(z.no_show)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(z.overrides)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            {/* The server's totals, not a client sum: the CSV's total row says the same. */}
            <TableFooter>
              <TableRow className="font-semibold hover:bg-transparent">
                <TableHead scope="row">{t('reconciliation.total')}</TableHead>
                <TableCell className="text-right tabular-nums">{n(totals.sold)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(totals.entered)}</TableCell>
                <TableCell className="text-right tabular-nums">{pct(totals.entered, totals.sold)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(totals.no_show)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(overrides)}</TableCell>
              </TableRow>
            </TableFooter>
          </Table>
        )}
      </Card>

      <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
        <CardHeader className="border-b pb-5">
          <CardTitle id="recon-gates-title" role="heading" aria-level={2}>
            {t('reconciliation.gates.title')}
          </CardTitle>
        </CardHeader>
        {recon.gates.length === 0 ? (
          <BrandEmpty title={t('reconciliation.gates.emptyTitle')} description={t('reconciliation.gates.empty')} />
        ) : (
          <Table className="min-w-[860px]" aria-labelledby="recon-gates-title">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead scope="col">{t('reconciliation.gates.gate')}</TableHead>
                <TableHead scope="col">{t('reconciliation.gates.zones')}</TableHead>
                <TableHead scope="col" className="text-right">{t('reconciliation.gates.entered')}</TableHead>
                <TableHead scope="col" className="text-right">{t('reconciliation.gates.refused')}</TableHead>
                <TableHead scope="col">{t('reconciliation.gates.detail')}</TableHead>
                <TableHead scope="col" className="text-right">{t('reconciliation.gates.lateDuplicates')}</TableHead>
                <TableHead scope="col" className="text-right">{t('reconciliation.gates.overrides')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {recon.gates.map((g) => (
                <TableRow key={g.gate_id}>
                  <TableHead scope="row">
                    <span className="font-semibold">{g.code}</span>{' '}
                    <span className="ml-1 font-normal text-muted-foreground">{g.name}</span>
                  </TableHead>
                  <TableCell>{g.zone_codes.join(' · ')}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(g.entered)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(sum(g.refused_by_result))}</TableCell>
                  <TableCell className="text-xs whitespace-normal text-muted-foreground">{breakdown(t, g.refused_by_result, result)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(g.duplicates_late)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(g.overrides)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            <TableFooter>
              <TableRow className="font-semibold hover:bg-transparent">
                <TableHead scope="row">{t('reconciliation.total')}</TableHead>
                <TableCell />
                <TableCell className="text-right tabular-nums">{n(totals.entered)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(refused)}</TableCell>
                <TableCell />
                <TableCell className="text-right tabular-nums">{n(totals.duplicates_late)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(overrides)}</TableCell>
              </TableRow>
            </TableFooter>
          </Table>
        )}
      </Card>

    </>
  )
}
