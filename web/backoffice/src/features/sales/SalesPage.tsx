import type { ReactNode } from 'react'
import { ChevronRight } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useEvent, useEvents } from '@/features/events/api'
import { StatusBadge } from '@/features/events/EventsPage'
import { fr } from '@/i18n/fr'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { formatDh } from '@/lib/money'
import { formatDateTime } from '@/lib/time'
import { useSales, type EventSales } from './api'

type Reason = keyof typeof fr.sales.refunds.reasons

function ErrorRow({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-3 p-4 text-sm">
      <span className="text-destructive">{t(errorKey(error))}</span>
      <Button variant="outline" size="sm" onClick={onRetry}>
        {t('common.retry')}
      </Button>
    </div>
  )
}

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {title}
        </CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  )
}

function Kpi({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <Card className="gap-1 py-4">
      <CardContent className="px-4">
        <div className="text-sm text-muted-foreground">{label}</div>
        <div className="text-2xl font-bold tabular-nums">{value}</div>
        <div className="text-xs text-muted-foreground">{hint}</div>
      </CardContent>
    </Card>
  )
}

/** Sidebar "Ventes": the organizer's events, each opening its B3 page. */
export function SalesIndexPage() {
  const { t, i18n } = useTranslation()
  const events = useEvents()
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-[22px] font-bold">{t('sales.title')}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{t('sales.indexDescription')}</p>
      </div>
      <Card className="py-2">
        <CardContent className="px-2">
          {events.isPending ? (
            <p className="p-4 text-sm text-muted-foreground">{t('common.loading')}</p>
          ) : events.isError ? (
            <ErrorRow error={events.error} onRetry={() => void events.refetch()} />
          ) : events.data.length === 0 ? (
            <p className="p-4 text-sm text-muted-foreground">{t('sales.noEvents')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('events.columns.title')}</TableHead>
                  <TableHead>{t('events.columns.startsAt')}</TableHead>
                  <TableHead>{t('events.columns.status')}</TableHead>
                  <TableHead className="text-right">{t('events.columns.sold')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {events.data.map((e) => (
                  <TableRow key={e.id}>
                    <TableCell className="font-medium">
                      <Link to={`/events/${encodeURIComponent(e.id)}/sales`} className="hover:underline">
                        {e.title}
                      </Link>
                    </TableCell>
                    <TableCell className="tabular-nums">{formatDateTime(e.starts_at, i18n.language, e.venue.timezone)}</TableCell>
                    <TableCell>
                      <StatusBadge status={e.status} />
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{t('events.soldOfQuota', { sold: e.sold, quota: e.quota })}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

/** B3 Ventes: `/events/:id/sales`. ADMIN, OPS and FINANCE (the shell's roles); a 403 shows the FORBIDDEN copy. */
export function SalesPage() {
  const id = useParams().id ?? ''
  // Same id rule as the event page: integers only, `/01` and `/1` are one query key.
  if (!/^\d+$/.test(id)) return <Navigate to="/events" replace state={{ notFound: true }} />
  return <EventSalesPage id={String(Number(id))} />
}

const isNotFound = (e: unknown) => e instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(e.code)

function EventSalesPage({ id }: { id: string }) {
  const { t } = useTranslation()
  const event = useEvent(id)
  const sales = useSales(id)
  if (isNotFound(event.error) || isNotFound(sales.error)) return <Navigate to="/events" replace state={{ notFound: true }} />
  if (event.isPending) return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  if (!event.data) return <ErrorRow error={event.error} onRetry={() => void event.refetch()} />

  return (
    <div className="flex flex-col gap-6">
      <div className="min-w-0">
        <nav aria-label={t('events.breadcrumb')} className="flex items-center gap-1 text-sm text-muted-foreground">
          <Link to="/events" className="hover:text-foreground hover:underline">
            {t('events.title')}
          </Link>
          <ChevronRight className="size-4" aria-hidden="true" />
          <Link to={`/events/${encodeURIComponent(event.data.id)}`} className="truncate hover:text-foreground hover:underline">
            {event.data.title}
          </Link>
          <ChevronRight className="size-4" aria-hidden="true" />
          <span aria-current="page">{t('sales.title')}</span>
        </nav>
        <div className="mt-1 flex flex-wrap items-center gap-2.5">
          <h1 className="text-[22px] font-bold">{t('sales.title')}</h1>
          <StatusBadge status={event.data.status} />
        </div>
      </div>
      {sales.isPending ? (
        <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
      ) : sales.isError ? (
        <ErrorRow error={sales.error} onRetry={() => void sales.refetch()} />
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
        <Kpi label={t('sales.kpi.paid')} value={t('events.soldOfQuota', { sold: sales.paid, quota: sales.quota })} hint={t('sales.kpi.paidHint')} />
        <Kpi label={t('sales.kpi.held')} value={n(sales.held)} hint={t('sales.kpi.heldHint')} />
        <Kpi label={t('sales.kpi.revenue')} value={dh(sales.revenue_mad)} hint={t('sales.kpi.revenueHint')} />
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
                  <TableCell>
                    <span className="font-medium">{z.code}</span> <span className="text-muted-foreground">{z.name}</span>
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
