import type { ReactNode } from 'react'
import { ArrowRight, CalendarDays, ChartNoAxesColumn, ChevronRight, Banknote, Hourglass, MapPin, Plus, Tags, Ticket, Trophy, type LucideIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { BrandEmpty, ErrorNotice, PageHeader } from '@/components/page'
import { FillBar } from '@/components/fill-bar'
import { Kpi } from '@/components/kpi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useCanWrite, useMe, useOrganizer } from '@/features/auth/organizer'
import { useEvent, useEvents, type EventSummary } from '@/features/events/api'
import { StatusBadge } from '@/features/events/EventsPage'
import { useSales, type EventSales } from '@/features/sales/api'
import { formatDh } from '@/lib/money'
import { percent } from '@/lib/percent'
import { dateParts, formatDateTime, formatLongDateTime, formatTime } from '@/lib/time'
import { RISE } from '@/lib/motion'
import { cn } from '@/lib/utils'
import { useNow } from '@/lib/useNow'

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
const eventPath = (id: number) => `/events/${encodeURIComponent(id)}`
// Hover lift on clickable rows (DESIGN §6).
const LIFT = 'transition-[background-color,transform] duration-200 hover:-translate-y-px motion-reduce:transition-none motion-reduce:hover:translate-y-0'

/** Aperçu, the landing page: the next event (live first), its sales, what comes after, and shortcuts. */
export function OverviewPage() {
  const { t } = useTranslation()
  const events = useEvents()
  const me = useMe()
  const organizer = useOrganizer()
  const canWrite = useCanWrite()
  const now = useNow(30_000)

  const firstName = me.data?.user.display_name?.split(/\s+/)[0]
  const greeting = t(new Date(now).getHours() < 18 ? 'overview.greeting.morning' : 'overview.greeting.evening')
  const newEvent = canWrite && (
    <Button asChild>
      <Link to="/events/new">
        <Plus />
        {t('events.new')}
      </Link>
    </Button>
  )
  // No header action while the page itself is the empty state offering it.
  const empty = events.data?.length === 0
  const header = (
    <PageHeader
      title={firstName ? t('overview.greetingName', { greeting, name: firstName }) : greeting}
      description={t('overview.subtitle', { organizer: organizer.organizer_name })}
      actions={!empty && newEvent}
    />
  )

  if (events.isPending)
    return (
      <Page header={header}>
        <div aria-busy="true" className="flex flex-col gap-6">
          <span className="sr-only">{t('common.loading')}</span>
          <Skeleton className="h-64 rounded-xl" />
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-32 rounded-xl" />
            ))}
          </div>
        </div>
      </Page>
    )
  if (events.isError)
    return (
      <Page header={header}>
        <Card className="py-2">
          <ErrorNotice error={events.error} onRetry={() => void events.refetch()} />
        </Card>
      </Page>
    )

  const list = events.data
  if (empty)
    return (
      <Page header={header}>
        <Card>
          <BrandEmpty title={t('overview.empty.title')} description={t(canWrite ? 'overview.empty.body' : 'overview.empty.bodyReadOnly')} action={newEvent} />
        </Card>
      </Page>
    )

  // API order is by starts_at. An event with its gates open comes first: it is happening now.
  const ahead = list.filter((e) => e.status !== 'CLOSED' && Date.parse(e.starts_at) > now)
  const next = list.find((e) => e.status === 'GATES_OPEN') ?? ahead[0]
  const later = ahead.filter((e) => e !== next)

  return (
    <Page header={header}>
      {next ? (
        <NextEvent key={next.id} event={next} now={now} upcoming={ahead.length} onSale={ahead.filter((e) => e.status === 'ON_SALE').length} />
      ) : (
        <Card>
          <BrandEmpty title={t('overview.noUpcoming.title')} description={t(canWrite ? 'overview.noUpcoming.body' : 'overview.noUpcoming.bodyReadOnly')} action={newEvent} />
        </Card>
      )}
      <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-3">
        <Later events={later} className="lg:col-span-2" />
        <QuickActions next={next} canWrite={canWrite} />
      </div>
    </Page>
  )
}

function Page({ header, children }: { header: ReactNode; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-6">
      {header}
      {children}
    </div>
  )
}

/** Hero (title, date in the venue's zone, countdown, sale window, paid ring), KPIs, then zones and channels. */
function NextEvent({ event, now, upcoming, onSale }: { event: EventSummary; now: number; upcoming: number; onSale: number }) {
  const { t, i18n } = useTranslation()
  const id = String(event.id)
  const detail = useEvent(id).data
  // Figures move while the sale is open: refetched every 30 s (and on focus) so the page stays live.
  const sales = useSales(id, { refetchInterval: 30_000 })
  const canWrite = useCanWrite()
  const zone = event.venue.timezone
  const left = Date.parse(event.starts_at) - now
  const competition = typeof detail?.metadata.competition === 'string' ? detail.metadata.competition : null
  const matchday = typeof detail?.metadata.matchday === 'number' ? detail.metadata.matchday : null

  return (
    <>
      <Card className={cn('gap-0 overflow-hidden py-0', RISE)}>
        <div className="grid sm:grid-cols-[minmax(0,1fr)_auto] lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="min-w-0 space-y-5 p-6 md:p-8">
            <div className="flex flex-wrap items-center gap-3">
              <span className="inline-flex items-center gap-2 text-xs font-semibold tracking-wider text-muted-foreground uppercase">
                <span className="size-1.5 rounded-full bg-brand-amber" aria-hidden="true" />
                {t(event.status === 'GATES_OPEN' ? 'overview.live' : 'overview.next')}
              </span>
              <StatusBadge status={event.status} />
            </div>
            <div className="space-y-3">
              <h2 className="text-3xl font-semibold tracking-tight text-balance md:text-[2rem] md:leading-tight">
                <Link to={eventPath(event.id)} className="rounded-sm outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50">
                  {event.title}
                </Link>
              </h2>
              <ul className="flex flex-wrap gap-x-5 gap-y-2 text-sm text-muted-foreground">
                <Meta icon={CalendarDays}>
                  <span className="text-foreground first-letter:uppercase">{formatLongDateTime(event.starts_at, i18n.language, zone)}</span>
                  <span className="ml-1.5 text-xs">{zone}</span>
                </Meta>
                <Meta icon={MapPin}>{event.venue.name}</Meta>
                {(competition || matchday !== null) && (
                  <Meta icon={Trophy}>
                    {[competition, matchday !== null && t('overview.matchday', { n: matchday })].filter(Boolean).join(' · ')}
                  </Meta>
                )}
              </ul>
            </div>
            <div className="flex flex-wrap items-end gap-x-6 gap-y-4">
              <Countdown left={left} />
              <div className="space-y-1 text-sm">
                {detail && <SaleWindow status={event.status} saleStartsAt={detail.sale_starts_at} now={now} zone={zone} />}
                {detail?.doors_open_at && <p className="text-muted-foreground">{t('overview.doors', { date: formatTime(detail.doors_open_at, i18n.language, zone) })}</p>}
              </div>
            </div>
            <div className="flex flex-wrap gap-2 pt-1">
              <Button asChild>
                <Link to={`${eventPath(event.id)}/sales`}>
                  {t('sales.title')}
                  <ArrowRight />
                </Link>
              </Button>
              <Button asChild variant="outline">
                <Link to={eventPath(event.id)}>{t(canWrite ? 'common.edit' : 'common.view')}</Link>
              </Button>
            </div>
          </div>
          {/* Beside the details, smaller, until lg; its own panel from lg. */}
          <div className="flex items-start justify-center px-6 pb-6 sm:py-8 sm:pr-8 sm:pl-0 lg:items-center lg:border-l lg:bg-muted/40 lg:p-6">
            {sales.data ? <PaidRing paid={sales.data.paid} quota={sales.data.quota} /> : <Skeleton className="size-32 rounded-full lg:size-48" />}
          </div>
        </div>
      </Card>

      {sales.isError ? (
        <Card className="py-2">
          <ErrorNotice error={sales.error} onRetry={() => void sales.refetch()} />
        </Card>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            {sales.data ? (
              <>
                <Kpi index={0} icon={Ticket} label={t('sales.kpi.paid')} value={sales.data.paid.toLocaleString(i18n.language)}>
                  <FillBar paid={sales.data.paid} held={sales.data.held} quota={sales.data.quota} className="mt-1" />
                  <span>
                    {t('overview.ring.of', { quota: sales.data.quota })} · {percent(sales.data.paid, sales.data.quota, i18n.language)}
                  </span>
                </Kpi>
                <Kpi index={1} icon={Hourglass} label={t('sales.kpi.held')} value={sales.data.held.toLocaleString(i18n.language)}>
                  {t('sales.kpi.heldHint')}
                </Kpi>
                <Kpi index={2} icon={Banknote} label={t('sales.kpi.revenue')} value={t('sales.amount', { amount: formatDh(sales.data.revenue_mad, i18n.language) })}>
                  {t('sales.kpi.revenueHint')}
                </Kpi>
              </>
            ) : (
              [0, 1, 2].map((i) => <Skeleton key={i} aria-hidden="true" className="h-32 rounded-xl" />)
            )}
            <Kpi index={3} icon={CalendarDays} label={t('overview.kpi.upcoming')} value={upcoming.toLocaleString(i18n.language)}>
              {t('overview.kpi.upcomingHint', { count: onSale })}
            </Kpi>
          </div>
          {sales.data && (
            <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-3">
              <Zones sales={sales.data} className="lg:col-span-2" />
              <Channels sales={sales.data} />
            </div>
          )}
        </>
      )}
    </>
  )
}

function Meta({ icon: Icon, children }: { icon: LucideIcon; children: ReactNode }) {
  return (
    <li className="flex min-w-0 items-center gap-1.5">
      <Icon className="size-4 shrink-0" aria-hidden="true" />
      <span className="min-w-0">{children}</span>
    </li>
  )
}

/** Days / hours / minutes to T0; "Commencé" once it has passed. */
function Countdown({ left }: { left: number }) {
  const { t } = useTranslation()
  if (left <= 0)
    return <span className="rounded-full bg-muted px-3 py-1 text-sm font-medium">{t('overview.countdown.started')}</span>
  const days = Math.floor(left / DAY)
  const hours = Math.floor((left % DAY) / HOUR)
  const minutes = Math.floor((left % HOUR) / MINUTE)
  const tile = (value: number, label: string) => (
    <div className="min-w-16 rounded-xl border bg-background/70 px-3 py-2 text-center">
      <div className="text-2xl leading-none font-bold tabular-nums">{value}</div>
      <div className="mt-1 text-[11px] font-medium text-muted-foreground">{label}</div>
    </div>
  )
  return (
    <div>
      <p className="mb-2 text-xs font-medium text-muted-foreground">{t('overview.countdown.label')}</p>
      <div className="flex gap-2">
        {tile(days, t('overview.countdown.days', { count: days }))}
        {tile(hours, t('overview.countdown.hours', { count: hours }))}
        {tile(minutes, t('overview.countdown.minutes'))}
      </div>
    </div>
  )
}

function SaleWindow({ status, saleStartsAt, now, zone }: { status: EventSummary['status']; saleStartsAt: string | null; now: number; zone: string }) {
  const { t, i18n } = useTranslation()
  const date = saleStartsAt ? formatDateTime(saleStartsAt, i18n.language, zone) : ''
  const scheduled = !!saleStartsAt && Date.parse(saleStartsAt) > now
  const text =
    status === 'DRAFT'
      ? date
        ? t('overview.sale.opensAt', { date })
        : t('overview.sale.atPublish')
      : status !== 'ON_SALE'
        ? null
        : scheduled
          ? t('overview.sale.opensAt', { date })
          : date
            ? t('overview.sale.openSince', { date })
            : t('overview.sale.open')
  if (!text) return null
  const open = status === 'ON_SALE' && !scheduled
  return (
    <p className="flex items-center gap-2 font-medium">
      <span className={cn('size-2 rounded-full', open ? 'bg-success' : 'bg-chart-4')} aria-hidden="true" />
      {text}
    </p>
  )
}

/**
 * Paid / quota as a ring: a track circle and an arc drawn with stroke-dasharray (no chart library). Decorative SVG;
 * the wrapper's label and the numbers in the middle say the same in text.
 */
function PaidRing({ paid, quota }: { paid: number; quota: number }) {
  const { t, i18n } = useTranslation()
  const pct = percent(paid, quota, i18n.language)
  const r = 42
  const length = 2 * Math.PI * r
  // A first sale keeps a visible sliver (as FillBar's 2 px), never more than the full ring.
  const share = quota > 0 ? Math.min(1, paid / quota) : 0
  const arc = paid > 0 ? Math.max(share * length, 1.5) : 0
  return (
    <div role="img" aria-label={t('overview.ring.aria', { paid, quota, percent: pct })} className="relative size-32 shrink-0 lg:size-48">
      <svg viewBox="0 0 100 100" aria-hidden="true" className="size-full -rotate-90">
        <circle cx="50" cy="50" r={r} fill="none" strokeWidth="8" className="stroke-chart-5" />
        <circle
          cx="50"
          cy="50"
          r={r}
          fill="none"
          strokeWidth="8"
          strokeLinecap="round"
          strokeDasharray={`${arc} ${length}`}
          className="stroke-chart-1 transition-[stroke-dasharray] duration-700 ease-out motion-reduce:transition-none"
        />
      </svg>
      <div aria-hidden="true" className="absolute inset-0 flex flex-col items-center justify-center text-center">
        <span className="text-xl font-bold tracking-tight tabular-nums lg:text-3xl">{pct}</span>
        <span className="mt-0.5 text-[11px] text-muted-foreground lg:text-xs">{t('overview.ring.label')}</span>
        <span className="mt-1 hidden text-xs font-medium tabular-nums lg:block">{t('overview.zones.value', { paid, quota })}</span>
      </div>
    </div>
  )
}

function Zones({ sales, className }: { sales: EventSales; className?: string }) {
  const { t, i18n } = useTranslation()
  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {t('overview.zones.title')}
        </CardTitle>
        <CardDescription>{t('overview.zones.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {sales.zones.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('sales.zones.empty')}</p>
        ) : (
          <>
            <ul className="flex flex-col gap-4">
              {sales.zones.map((z) => (
                <li key={z.zone_id} className="space-y-1.5">
                  <div className="flex items-baseline justify-between gap-3 text-sm">
                    <span className="min-w-0 truncate">
                      <span className="font-semibold">{z.code}</span> <span className="text-muted-foreground">{z.name}</span>
                    </span>
                    <span className="shrink-0 tabular-nums">
                      {t('overview.zones.value', { paid: z.paid, quota: z.quota })}
                      <span className="ml-2 inline-block min-w-14 text-right text-muted-foreground">{percent(z.paid, z.quota, i18n.language)}</span>
                    </span>
                  </div>
                  <FillBar paid={z.paid} held={z.held} quota={z.quota} className="h-2" />
                </li>
              ))}
            </ul>
            <div className="mt-5 flex gap-4 text-xs text-muted-foreground" aria-hidden="true">
              <span className="flex items-center gap-1.5">
                <span className="size-2 rounded-full bg-chart-1" />
                {t('overview.zones.paid')}
              </span>
              <span className="flex items-center gap-1.5">
                <span className="size-2 rounded-full bg-chart-2" />
                {t('overview.zones.held')}
              </span>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}

/** Paid per channel: a bar per channel (its share of paid tickets) with the count and revenue in text. */
function Channels({ sales }: { sales: EventSales }) {
  const { t, i18n } = useTranslation()
  return (
    <Card>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {t('overview.channels.title')}
        </CardTitle>
        <CardDescription>{t('overview.channels.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {sales.paid === 0 ? (
          <p className="text-sm text-muted-foreground">{t('sales.channels.empty')}</p>
        ) : (
          <ul className="flex flex-col gap-4">
            {sales.channels.map((c) => (
              <li key={c.channel} className="space-y-1.5">
                <div className="flex items-baseline justify-between gap-3 text-sm">
                  <span>{t(`sales.channels.names.${c.channel}`)}</span>
                  <span className="tabular-nums">
                    {c.paid.toLocaleString(i18n.language)}
                    <span className="ml-3 text-muted-foreground">{t('sales.amount', { amount: formatDh(c.revenue_mad, i18n.language) })}</span>
                  </span>
                </div>
                <FillBar paid={c.paid} quota={sales.paid} className="h-2" />
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

function Later({ events, className }: { events: EventSummary[]; className?: string }) {
  const { t, i18n } = useTranslation()
  return (
    <Card className={className}>
      <CardHeader className="flex flex-row items-start justify-between gap-3">
        <CardTitle role="heading" aria-level={2}>
          {t('overview.upcoming.title')}
        </CardTitle>
        <Button asChild variant="ghost" size="sm" className="-my-1.5 text-muted-foreground">
          <Link to="/events">
            {t('overview.upcoming.all')}
            <ChevronRight />
          </Link>
        </Button>
      </CardHeader>
      <CardContent>
        {events.length === 0 ? (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed py-10 text-center">
            <span className="flex size-10 items-center justify-center rounded-full bg-muted">
              <CalendarDays className="size-5 text-muted-foreground" aria-hidden="true" />
            </span>
            <p className="max-w-xs text-sm text-muted-foreground">{t('overview.upcoming.empty')}</p>
          </div>
        ) : (
          <ul className="-mx-2 flex flex-col">
            {events.slice(0, 5).map((e) => {
              const d = dateParts(e.starts_at, i18n.language, e.venue.timezone)
              return (
                <li key={e.id}>
                  <Link
                    to={eventPath(e.id)}
                    className={cn('flex items-center gap-4 rounded-lg px-2 py-2.5 outline-none hover:bg-muted/60 focus-visible:ring-[3px] focus-visible:ring-ring/50', LIFT)}
                  >
                    <span className="flex w-12 shrink-0 flex-col items-center rounded-lg border bg-background py-1 leading-none" aria-hidden="true">
                      <span className="text-lg font-bold tabular-nums">{d.day}</span>
                      <span className="text-[11px] text-muted-foreground uppercase">{d.month}</span>
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium">{e.title}</span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {formatDateTime(e.starts_at, i18n.language, e.venue.timezone)} · {e.venue.name}
                      </span>
                    </span>
                    <span className="hidden w-32 shrink-0 flex-col gap-1.5 sm:flex">
                      <span className="text-right text-xs tabular-nums">{t('events.soldOfQuota', { sold: e.sold, quota: e.quota })}</span>
                      <FillBar paid={e.sold} quota={e.quota} />
                    </span>
                    <StatusBadge status={e.status} />
                  </Link>
                </li>
              )
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

function QuickActions({ next, canWrite }: { next?: EventSummary; canWrite: boolean }) {
  const { t } = useTranslation()
  const actions: { to: string; icon: LucideIcon; title: string; hint: string }[] = [
    ...(canWrite ? [{ to: '/events/new', icon: Plus, title: t('overview.actions.newEvent'), hint: t('overview.actions.newEventHint') }] : []),
    { to: next ? `${eventPath(next.id)}/sales` : '/sales', icon: ChartNoAxesColumn, title: t('overview.actions.sales'), hint: t('overview.actions.salesHint') },
    ...(next ? [{ to: `${eventPath(next.id)}?tab=prices`, icon: Tags, title: t('overview.actions.prices'), hint: t('overview.actions.pricesHint') }] : []),
  ]
  return (
    <Card>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {t('overview.actions.title')}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <ul className="-mx-2 flex flex-col gap-1">
          {actions.map((a) => (
            <li key={a.to}>
              <Link
                to={a.to}
                className={cn('group flex items-center gap-3 rounded-lg px-2 py-2.5 outline-none hover:bg-muted/60 focus-visible:ring-[3px] focus-visible:ring-ring/50', LIFT)}
              >
                <span className="flex size-9 shrink-0 items-center justify-center rounded-lg border bg-background">
                  <a.icon className="size-4" aria-hidden="true" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium">{a.title}</span>
                  <span className="block truncate text-xs text-muted-foreground">{a.hint}</span>
                </span>
                <ChevronRight
                  className="size-4 text-muted-foreground transition-transform group-hover:translate-x-0.5 motion-reduce:transition-none"
                  aria-hidden="true"
                />
              </Link>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}
