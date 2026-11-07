import { useState } from 'react'
import type { TFunction } from 'i18next'
import { Activity, ClipboardCheck, Download, Hand, LogIn, ShieldX, TriangleAlert } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { BrandEmpty, ErrorNotice, PageHeader, STICKY_TABLE } from '@/components/page'
import { FillBar } from '@/components/fill-bar'
import { Kpi } from '@/components/kpi'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { FieldError } from '@/components/ui/field'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCanWrite } from '@/features/auth/organizer'
import { useEvent, type EventDetail, type EventStatus } from '@/features/events/api'
import { EventPicker } from '@/features/events/EventPicker'
import { StatusBadge } from '@/features/events/EventsPage'
import { fr } from '@/i18n/fr'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { percent } from '@/lib/percent'
import { formatAgo, formatDateTime, formatTime } from '@/lib/time'
import { useNow } from '@/lib/useNow'
import { cn } from '@/lib/utils'
import { useReconciliationCsv } from '@/features/reconciliation/api'
import { charge, chargeLevel, CLOSABLE, SCANS_PER_DEVICE_MIN, useCloseEvent, useLive, type Live, type LiveGate } from './api'

const MINUTE = 60_000
// ponytail: with no doors time, the gates count as open from T0 − 3 h (the manifest lead); a real doors time wins.
const DEFAULT_DOORS_LEAD = 3 * 60 * MINUTE
const LEVEL_BAR = { ok: 'bg-success', busy: 'bg-warning', full: 'bg-destructive' } as const
type Result = keyof typeof fr.live.results

/** Sidebar "Portes en direct": the organizer's events, each opening its B4 page. */
export function LiveIndexPage() {
  const { t } = useTranslation()
  return <EventPicker title={t('live.title')} description={t('live.indexDescription')} empty={t('live.noEvents')} to={(id) => `/events/${encodeURIComponent(id)}/live`} />
}

/** B4 Portes en direct: `/events/:id/live`. ADMIN, OPS and FINANCE read; ADMIN/OPS close. */
export function LivePage() {
  const id = useParams().id ?? ''
  // Same id rule as the event page: integers only, `/01` and `/1` are one query key.
  if (!/^\d+$/.test(id)) return <Navigate to="/events" replace state={{ notFound: true }} />
  return <EventLivePage id={String(Number(id))} />
}

const isNotFound = (e: unknown) => e instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(e.code)

/** `T0 − 38 min` / `T0 + 1 h 05` / `T0 − 3 jours`, from `now` to `startsAt`. */
function t0Offset(t: TFunction, now: number, startsAt: number) {
  const m = Math.floor(Math.abs(now - startsAt) / MINUTE)
  if (m === 0) return t('live.t0.now')
  const time =
    m < 60
      ? t('live.duration.minutes', { m })
      : m < 24 * 60
        ? t('live.duration.hours', { h: Math.floor(m / 60), mm: String(m % 60).padStart(2, '0') })
        : t('live.duration.days', { count: Math.floor(m / (24 * 60)) })
  return t(now < startsAt ? 'live.t0.before' : 'live.t0.after', { time })
}

/** Q38: GATES_OPEN is never set in Lot 2, so "Portes ouvertes" comes from the clock (doors time, else T0 − 3 h). */
function LivePill({ status, event, now }: { status: EventStatus; event: EventDetail; now: number }) {
  const { t } = useTranslation()
  if (!CLOSABLE.includes(status)) return <StatusBadge status={status} />
  const startsAt = Date.parse(event.starts_at)
  const doors = event.doors_open_at ? Date.parse(event.doors_open_at) : startsAt - DEFAULT_DOORS_LEAD
  const open = now >= doors
  return (
    <Badge
      variant="outline"
      className={cn(
        'gap-1.5 rounded-full px-2.5 font-medium tabular-nums',
        open ? 'border-success-border bg-success-muted text-success-foreground' : 'border-border bg-secondary text-secondary-foreground',
      )}
    >
      <span className={cn('size-1.5 rounded-full', open ? 'bg-success' : 'bg-chart-3')} aria-hidden="true" />
      {t(open ? 'live.pill.open' : 'live.pill.onSale', { offset: t0Offset(t, now, startsAt) })}
    </Badge>
  )
}

function LiveSkeleton({ header = true }: { header?: boolean }) {
  const { t } = useTranslation()
  return (
    <div aria-busy="true" className="flex flex-col gap-6">
      <span className="sr-only">{t('common.loading')}</span>
      {header && <Skeleton className="h-16 w-96" />}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-32 rounded-xl" />
        ))}
      </div>
      <Skeleton className="h-72 rounded-xl" />
    </div>
  )
}

function EventLivePage({ id }: { id: string }) {
  const { t, i18n } = useTranslation()
  const canWrite = useCanWrite()
  const event = useEvent(id)
  const live = useLive(id)
  const tick = useNow(1000)
  const [closing, setClosing] = useState(false)
  const exportCsv = useReconciliationCsv(id)

  if (isNotFound(event.error) || isNotFound(live.error)) return <Navigate to="/events" replace state={{ notFound: true }} />
  if (event.isPending) return <LiveSkeleton />
  if (!event.data) return <ErrorNotice error={event.error} onRetry={() => void event.refetch()} />

  const zone = event.data.timezone
  const data = live.data
  // The server's clock, carried forward by the browser's since the last answer: T0 offset, close and "il y a".
  // `tick` can predate the answer by up to a second: never run the clock backwards.
  const age = Math.max(0, tick - live.dataUpdatedAt)
  const now = data ? Date.parse(data.server_time) + age : tick
  const status = data?.status ?? event.data.status
  const startsAt = Date.parse(event.data.starts_at)
  const beforeT0 = now < startsAt
  const closable = CLOSABLE.includes(status)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        crumbs={[{ label: t('live.title'), to: '/live' }, { label: event.data.title, to: `/events/${encodeURIComponent(event.data.id)}` }]}
        title={t('live.heading', { title: event.data.title, date: formatDateTime(event.data.starts_at, i18n.language, zone) })}
        badge={<LivePill status={status} event={event.data} now={now} />}
        description={canWrite && closable && beforeT0 ? t('live.closeHint', { time: formatTime(event.data.starts_at, i18n.language, zone) }) : undefined}
        actions={
          <>
            <Button variant="outline" asChild>
              <Link to={`/events/${encodeURIComponent(event.data.id)}/reconciliation`}>
                <ClipboardCheck />
                {t('reconciliation.title')}
              </Link>
            </Button>
            <Button variant="outline" disabled={exportCsv.isPending} onClick={() => exportCsv.mutate()}>
              <Download />
              {t('live.export')}
            </Button>
            {canWrite && (
              <Button disabled={!closable || beforeT0} onClick={() => setClosing(true)}>
                {t('live.close.action')}
              </Button>
            )}
          </>
        }
      />
      {live.isPending ? (
        <LiveSkeleton header={false} />
      ) : !data ? (
        <Card className="py-2">
          <ErrorNotice error={live.error} onRetry={() => void live.refetch()} />
        </Card>
      ) : (
        <LiveBody live={data} now={now} age={age} failed={live.isError} eventId={event.data.id} canWrite={canWrite} />
      )}
      {canWrite && <CloseDialog open={closing} onOpenChange={setClosing} event={event.data} stale={data?.devices_stale.length} />}
    </div>
  )
}

function LiveBody({ live, now, age, failed, eventId, canWrite }: { live: Live; now: number; age: number; failed: boolean; eventId: number; canWrite: boolean }) {
  const { t, i18n } = useTranslation()
  const n = (v: number) => v.toLocaleString(i18n.language)
  const delta = live.rate_per_min - live.rate_per_min_prev
  const staleGates = new Set(live.devices_stale.map((d) => d.gate_id))

  // Refusals: the two biggest results by name, the rest as "Autres".
  const results = Object.entries(live.refused.by_result).sort((a, b) => b[1] - a[1])
  const label = (code: string) => (Object.hasOwn(fr.live.results, code) ? t(`live.results.${code as Result}`) : code)
  const others = results.slice(2).reduce((sum, [, c]) => sum + c, 0)

  const top = live.overrides.by_device[0]
  const cap = live.overrides.cap_per_device

  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi index={0} icon={LogIn} label={t('live.kpi.entered')} value={n(live.entered)}>
          <FillBar paid={live.entered} quota={live.sold} className="mt-1" />
          <span>{t('live.kpi.enteredHint', { sold: live.sold, percent: percent(live.entered, live.sold, i18n.language) })}</span>
          {live.duplicates_late > 0 && <span>{t('live.kpi.lateDuplicates', { count: live.duplicates_late })}</span>}
        </Kpi>
        <Kpi index={1} icon={Activity} label={t('live.kpi.rate')} value={t('live.kpi.rateValue', { rate: live.rate_per_min })}>
          <span className={cn(delta > 0 && 'font-medium text-success-foreground')}>
            {t('live.kpi.rateDelta', { delta: new Intl.NumberFormat(i18n.language, { signDisplay: 'exceptZero' }).format(delta) })}
          </span>
        </Kpi>
        <Kpi index={2} icon={ShieldX} label={t('live.kpi.refused')} value={n(live.refused.total)}>
          <span>
            {results.length === 0
              ? t('live.kpi.refusedNone')
              : [
                  ...results.slice(0, 2).map(([code, count]) => t('live.kpi.refusedPart', { label: label(code), count })),
                  ...(others > 0 ? [t('live.kpi.refusedOther', { count: others })] : []),
                ].join(' · ')}
          </span>
        </Kpi>
        <Kpi index={3} icon={Hand} label={t('live.kpi.overrides')} value={n(live.overrides.total)}>
          {top ? (
            <span className={cn(top.count >= cap * 0.75 && 'font-medium text-warning-foreground')}>
              {top.count >= cap ? t('live.kpi.capReached', { label: top.label, cap }) : t('live.kpi.capTop', { label: top.label, count: top.count, cap })}
            </span>
          ) : (
            <span>{t('live.kpi.cap', { cap })}</span>
          )}
        </Kpi>
      </div>

      {live.devices_stale.length > 0 && (
        // A warning, never an error: the devices keep validating offline and sync later.
        <div className="flex items-start gap-3 rounded-xl border border-warning-border bg-warning-muted px-4 py-3 text-sm text-warning-foreground">
          <TriangleAlert className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden="true" />
          <div className="min-w-0 flex-1 space-y-1">
            <p className="font-semibold">{t('live.stale.title', { count: live.devices_stale.length })}</p>
            <p>{t('live.stale.body')}</p>
            <ul className="flex flex-wrap gap-x-4 gap-y-0.5 text-xs">
              {live.devices_stale.map((d) => (
                <li key={d.device_id} className="tabular-nums">
                  {t('live.stale.device', {
                    label: d.label,
                    gate: d.gate_code,
                    ago: d.last_seen_at ? formatAgo(now - Date.parse(d.last_seen_at), i18n.language) : t('live.stale.never'),
                  })}
                </li>
              ))}
            </ul>
          </div>
          {canWrite && (
            <Link
              to={`/events/${encodeURIComponent(eventId)}?tab=devices`}
              className="shrink-0 rounded-sm font-semibold underline-offset-4 outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50"
            >
              {t('live.stale.link')}
            </Link>
          )}
        </div>
      )}

      <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
        <CardHeader className="border-b pb-5">
          <CardTitle role="heading" aria-level={2}>
            {t('live.gates.title')}
          </CardTitle>
          <CardAction>
            <CardDescription className={cn('tabular-nums', failed && 'font-medium text-warning-foreground')}>
              {failed ? (
                <span role="status">{t('live.updateFailed', { ago: formatAgo(age, i18n.language) })}</span>
              ) : (
                t('live.updated', { ago: formatAgo(age, i18n.language) })
              )}
            </CardDescription>
          </CardAction>
        </CardHeader>
        {live.gates.length === 0 ? (
          <BrandEmpty title={t('live.gates.emptyTitle')} description={t('live.gates.empty')} />
        ) : (
          <Table className="min-w-[760px]">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('live.gates.gate')}</TableHead>
                <TableHead>{t('live.gates.zones')}</TableHead>
                <TableHead>{t('live.gates.devices')}</TableHead>
                <TableHead className="text-right">{t('live.gates.entered')}</TableHead>
                <TableHead className="text-right">{t('live.gates.refused')}</TableHead>
                <TableHead className="text-right">{t('live.gates.rate')}</TableHead>
                <TableHead>{t('live.gates.charge')}</TableHead>
                <TableHead>{t('live.gates.lastSync')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {live.gates.map((g) => {
                const stale = staleGates.has(g.gate_id)
                const offline = g.devices_total - g.devices_online
                return (
                  <TableRow key={g.gate_id} data-stale={stale || undefined} className={cn(stale && 'bg-warning-muted/70 hover:bg-warning-muted')}>
                    <TableCell className="font-semibold">{g.code}</TableCell>
                    <TableCell>{g.zone_codes.join(' · ')}</TableCell>
                    <TableCell className="tabular-nums">
                      {t('live.gates.devicesValue', { online: g.devices_online, total: g.devices_total })}
                      {offline > 0 && <span className="ml-1 text-warning-foreground">{t('live.gates.offline', { count: offline })}</span>}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{n(g.entered)}</TableCell>
                    <TableCell className="text-right tabular-nums">{n(g.refused)}</TableCell>
                    <TableCell className="text-right tabular-nums">{n(g.rate_per_min)}</TableCell>
                    <TableCell>
                      <ChargeBar gate={g} />
                    </TableCell>
                    <TableCell className={cn('tabular-nums', stale ? 'font-medium text-warning-foreground' : 'text-muted-foreground')}>
                      {g.last_sync_at ? formatAgo(now - Date.parse(g.last_sync_at), i18n.language) : t('live.gates.never')}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
      </Card>
      <p className="text-xs text-muted-foreground">{t('live.gates.footnote')}</p>
    </>
  )
}

/** Charge bar (green / amber / red) with its percentage in text: the colour is never the only signal. */
function ChargeBar({ gate }: { gate: LiveGate }) {
  const { t, i18n } = useTranslation()
  const ratio = charge(gate)
  if (ratio === null) return <span className="text-muted-foreground">{t('live.gates.noDevice')}</span>
  const level = chargeLevel(ratio)
  return (
    <div className="flex items-center gap-3">
      <div aria-hidden="true" className="h-1.5 w-28 overflow-hidden rounded-full bg-muted">
        <div
          data-level={level}
          className={cn('h-full rounded-full transition-[width] duration-700 ease-out motion-reduce:transition-none', LEVEL_BAR[level])}
          style={{ width: `${Math.min(100, ratio * 100)}%`, minWidth: ratio > 0 ? 2 : 0 }}
        />
      </div>
      <span className="w-12 text-xs tabular-nums">{percent(gate.rate_per_min, gate.devices_total * SCANS_PER_DEVICE_MIN, i18n.language)}</span>
    </div>
  )
}

/** 409 EVENT_NOT_EDITABLE says why (04 `details.reason`): before T0 (with `starts_at`), or not open (DRAFT, already CLOSED). */
function closeError(t: TFunction, e: unknown, time: (iso: string) => string) {
  if (!e) return null
  if (!(e instanceof ApiError) || e.code !== 'EVENT_NOT_EDITABLE') return t(errorKey(e))
  const { reason, starts_at: startsAt } = e.details
  if (reason === 'NOT_STARTED' && typeof startsAt === 'string') return t('live.close.notStarted', { time: time(startsAt) })
  return t(reason === 'NOT_OPEN' ? 'live.close.notOpen' : 'live.close.notEditable')
}

/** "Clôturer l'événement": the stale-device count, then POST /close; 409 when already closed or before T0. */
function CloseDialog({ open, onOpenChange, event, stale }: { open: boolean; onOpenChange: (open: boolean) => void; event: EventDetail; stale?: number }) {
  const { t, i18n } = useTranslation()
  const close = useCloseEvent(String(event.id))
  const error = closeError(t, close.error, (iso) => formatTime(iso, i18n.language, event.timezone))
  return (
    <AlertDialog
      open={open}
      onOpenChange={(o) => {
        onOpenChange(o)
        if (!o) close.reset()
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('live.close.title', { title: event.title })}</AlertDialogTitle>
          <AlertDialogDescription>{t('live.close.body')}</AlertDialogDescription>
        </AlertDialogHeader>
        {/* Unknown while /live has not answered: say nothing rather than "all synced". */}
        {stale !== undefined && (
          <p
            className={cn(
              'rounded-lg border px-3 py-2.5 text-sm',
              stale > 0 ? 'border-warning-border bg-warning-muted text-warning-foreground' : 'border-success-border bg-success-muted text-success-foreground',
            )}
          >
            {stale > 0 ? t('live.close.stale', { count: stale }) : t('live.close.allSynced')}
          </p>
        )}
        {error && <FieldError>{error}</FieldError>}
        <AlertDialogFooter>
          <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
          <Button
            disabled={close.isPending}
            onClick={() =>
              close.mutate(undefined, {
                onSuccess: () => {
                  toast.success(t('live.close.done', { title: event.title }))
                  onOpenChange(false)
                },
              })
            }
          >
            {t('live.close.confirm')}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
