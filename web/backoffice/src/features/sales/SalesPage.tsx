import type { ReactNode } from 'react'
import { Banknote, Hourglass, Ticket } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Navigate, useParams } from 'react-router'
import { ErrorNotice, PageHeader } from '@/components/page'
import { FillBar } from '@/components/fill-bar'
import { Kpi } from '@/components/kpi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useEvent } from '@/features/events/api'
import { EventPicker } from '@/features/events/EventPicker'
import { StatusBadge } from '@/features/events/EventsPage'
import { fr } from '@/i18n/fr'
import { ApiError } from '@/lib/api'
import { formatDh } from '@/lib/money'
import { percent } from '@/lib/percent'
import { formatDateTime } from '@/lib/time'
import { useSales, type EventSales } from './api'

type Reason = keyof typeof fr.sales.refunds.reasons

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {title}
        </CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent className="[&_th]:text-xs [&_th]:font-medium [&_th]:text-muted-foreground">{children}</CardContent>
    </Card>
  )
}

/** Sidebar "Ventes": the organizer's events, each opening its B3 page. */
export function SalesIndexPage() {
  const { t } = useTranslation()
  return <EventPicker title={t('sales.title')} description={t('sales.indexDescription')} empty={t('sales.noEvents')} to={(id) => `/events/${encodeURIComponent(id)}/sales`} />
}

/** B3 Ventes: `/events/:id/sales`. ADMIN, OPS and FINANCE (the shell's roles); a 403 shows the FORBIDDEN copy. */
export function SalesPage() {
  const id = useParams().id ?? ''
  // Same id rule as the event page: integers only, `/01` and `/1` are one query key.
  if (!/^\d+$/.test(id)) return <Navigate to="/events" replace state={{ notFound: true }} />
  return <EventSalesPage id={String(Number(id))} />
}

const isNotFound = (e: unknown) => e instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(e.code)

/** Same rhythm as the loaded page (header, three KPI cards, a table), so nothing jumps when data lands. */
function SalesSkeleton({ header = true }: { header?: boolean }) {
  const { t } = useTranslation()
  return (
    <div aria-busy="true" className="flex flex-col gap-6">
      <span className="sr-only">{t('common.loading')}</span>
      {header && <Skeleton className="h-16 w-80" />}
      <div className="grid gap-4 sm:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-36 rounded-xl" />
        ))}
      </div>
      <Skeleton className="h-56 rounded-xl" />
    </div>
  )
}

function EventSalesPage({ id }: { id: string }) {
  const { t, i18n } = useTranslation()
  const event = useEvent(id)
  const sales = useSales(id)
  if (isNotFound(event.error) || isNotFound(sales.error)) return <Navigate to="/events" replace state={{ notFound: true }} />
  if (event.isPending) return <SalesSkeleton />
  if (!event.data) return <ErrorNotice error={event.error} onRetry={() => void event.refetch()} />
  const eventPath = `/events/${encodeURIComponent(event.data.id)}`

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        crumbs={[{ label: t('events.title'), to: '/events' }, { label: event.data.title, to: eventPath }, { label: t('sales.title') }]}
        title={t('sales.title')}
        badge={<StatusBadge status={event.data.status} />}
        description={t('sales.eventLine', { date: formatDateTime(event.data.starts_at, i18n.language, event.data.timezone), zone: event.data.timezone })}
      />
      {sales.isPending ? (
        <SalesSkeleton header={false} />
      ) : sales.isError ? (
        <Card className="py-2">
          <ErrorNotice error={sales.error} onRetry={() => void sales.refetch()} />
        </Card>
      ) : (
        <SalesBody sales={sales.data} zone={event.data.timezone} />
      )}
    </div>
  )
}

function SalesBody({ sales, zone }: { sales: EventSales; zone: string }) {
  const { t, i18n } = useTranslation()
  const n = (count: number) => count.toLocaleString(i18n.language)
  const dh = (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) })
  const reason = (code: string) => (Object.hasOwn(fr.sales.refunds.reasons, code) ? t(`sales.refunds.reasons.${code as Reason}`) : code)

  return (
    <>
      <div className="grid gap-4 sm:grid-cols-3">
        <Kpi index={0} icon={Ticket} label={t('sales.kpi.paid')} value={t('events.soldOfQuota', { sold: sales.paid, quota: sales.quota })}>
          <div className="flex items-center gap-3">
            <FillBar paid={sales.paid} held={sales.held} quota={sales.quota} />
            <span className="shrink-0 font-medium text-foreground tabular-nums">{percent(sales.paid, sales.quota, i18n.language)}</span>
          </div>
          <span>{t('sales.kpi.paidHint')}</span>
        </Kpi>
        <Kpi index={1} icon={Hourglass} label={t('sales.kpi.held')} value={n(sales.held)}>
          {t('sales.kpi.heldHint')}
        </Kpi>
        <Kpi index={2} icon={Banknote} label={t('sales.kpi.revenue')} value={dh(sales.revenue_mad)}>
          {t('sales.kpi.revenueHint')}
        </Kpi>
      </div>

      <Section title={t('sales.zones.title')}>
        {sales.zones.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('sales.zones.empty')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('sales.zones.zone')}</TableHead>
                <TableHead className="text-right">{t('sales.columns.quota')}</TableHead>
                <TableHead className="text-right">{t('sales.columns.paid')}</TableHead>
                <TableHead className="text-right">{t('sales.columns.held')}</TableHead>
                <TableHead className="text-right">{t('sales.columns.revenue')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {sales.zones.map((z) => (
                <TableRow key={z.zone_id}>
                  <TableCell className="min-w-48">
                    <span className="font-medium">{z.code}</span> <span className="text-muted-foreground">{z.name}</span>
                    <FillBar paid={z.paid} held={z.held} quota={z.quota} className="mt-2 max-w-56" />
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{n(z.quota)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(z.paid)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(z.held)}</TableCell>
                  <TableCell className="text-right tabular-nums">{dh(z.revenue_mad)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Section>

      <Section title={t('sales.channels.title')}>
        {/* 04: channels always lists APP, WEB and POS, zeros included; all-zero rows say nothing a notice doesn't. */}
        {sales.paid === 0 ? (
          <p className="text-sm text-muted-foreground">{t('sales.channels.empty')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('sales.channels.channel')}</TableHead>
                <TableHead className="text-right">{t('sales.columns.paid')}</TableHead>
                <TableHead className="text-right">{t('sales.columns.revenue')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {sales.channels.map((c) => (
                <TableRow key={c.channel}>
                  <TableCell>{t(`sales.channels.names.${c.channel}`)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(c.paid)}</TableCell>
                  <TableCell className="text-right tabular-nums">{dh(c.revenue_mad)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Section>

      <Section title={t('sales.refunds.title')} description={t('sales.refunds.description')}>
        {sales.refunds_required.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('sales.refunds.empty')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('sales.refunds.at')}</TableHead>
                <TableHead>{t('sales.refunds.reason')}</TableHead>
                <TableHead>{t('sales.refunds.payment')}</TableHead>
                <TableHead>{t('sales.refunds.order')}</TableHead>
                <TableHead className="text-right">{t('sales.refunds.amount')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {/* Newest first, as the API sends them. */}
              {sales.refunds_required.map((r) => (
                <TableRow key={`${r.payment_id} ${r.at}`}>
                  <TableCell className="tabular-nums">{formatDateTime(r.at, i18n.language, zone)}</TableCell>
                  <TableCell>{reason(r.reason)}</TableCell>
                  <TableCell className="font-mono text-xs">{r.payment_id}</TableCell>
                  <TableCell className="font-mono text-xs">{r.order_id}</TableCell>
                  <TableCell className="text-right tabular-nums">{dh(r.amount_mad)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Section>
    </>
  )
}
