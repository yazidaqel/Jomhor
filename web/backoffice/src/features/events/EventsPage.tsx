import { Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { BrandEmpty, ErrorNotice, LoadingRows, PageHeader, STICKY_TABLE } from '@/components/page'
import { FillBar } from '@/components/fill-bar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCanWrite } from '@/features/auth/organizer'
import { useNotFoundNotice } from '@/lib/useNotFoundNotice'
import { cn } from '@/lib/utils'
import { useEvents, type EventStatus } from './api'
import { formatDateTime } from '@/lib/time'

// Tinted badges from theme tokens (AA on their tint): neutral draft, success on sale, info live, muted closed.
const STATUS_STYLE: Record<EventStatus, { badge: string; dot: string }> = {
  DRAFT: { badge: 'border-border bg-secondary text-secondary-foreground', dot: 'bg-chart-3' },
  ON_SALE: { badge: 'border-success-border bg-success-muted text-success-foreground', dot: 'bg-success' },
  GATES_OPEN: { badge: 'border-info-border bg-info-muted text-info-foreground', dot: 'bg-info' },
  CLOSED: { badge: 'border-border bg-background text-muted-foreground', dot: 'bg-chart-4' },
}

export function StatusBadge({ status }: { status: EventStatus }) {
  const { t } = useTranslation()
  return (
    <Badge variant="outline" className={cn('gap-1.5 rounded-full px-2.5 font-medium', STATUS_STYLE[status].badge)}>
      <span className={cn('size-1.5 rounded-full', STATUS_STYLE[status].dot)} aria-hidden="true" />
      {t(`events.statuses.${status}`)}
    </Badge>
  )
}

/** B1 Événements: the organizer's events, in the API's order (by starts_at). */
export function EventsPage() {
  const { t, i18n } = useTranslation()
  const events = useEvents()
  const canWrite = useCanWrite()
  const notFound = useNotFoundNotice() // set by the event page on an unknown id
  const newEvent = canWrite && (
    <Button asChild>
      <Link to="/events/new">
        <Plus />
        {t('events.new')}
      </Link>
    </Button>
  )

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('events.title')} description={t('events.description')} actions={newEvent} />
      {notFound && <p className="text-sm text-destructive">{t('events.notFound')}</p>}
      {!canWrite && <p className="text-sm text-muted-foreground">{t('events.readOnly')}</p>}
      <Card className={cn('gap-0 overflow-hidden py-0', STICKY_TABLE)}>
        {events.isPending ? (
          <LoadingRows className="p-5" />
        ) : events.isError ? (
          <ErrorNotice error={events.error} onRetry={() => void events.refetch()} />
        ) : events.data.length === 0 ? (
          <BrandEmpty title={t('events.emptyTitle')} description={t('events.empty')} />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('events.columns.title')}</TableHead>
                <TableHead>{t('events.columns.type')}</TableHead>
                <TableHead>{t('events.columns.startsAt')}</TableHead>
                <TableHead>{t('events.columns.venue')}</TableHead>
                <TableHead>{t('events.columns.status')}</TableHead>
                <TableHead className="text-right">{t('events.columns.sold')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {events.data.map((e) => (
                <TableRow key={e.id}>
                  <TableCell className="font-medium">
                    <Link to={`/events/${encodeURIComponent(e.id)}`} className="rounded-sm outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50">
                      {e.title}
                    </Link>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{t(`events.types.${e.event_type}`)}</TableCell>
                  <TableCell>
                    {/* Each row in its venue's zone (Q7), named so a different zone stands out. */}
                    <div className="tabular-nums">{formatDateTime(e.starts_at, i18n.language, e.venue.timezone)}</div>
                    <div className="text-xs text-muted-foreground">{e.venue.timezone}</div>
                  </TableCell>
                  <TableCell>{e.venue.name}</TableCell>
                  <TableCell>
                    <StatusBadge status={e.status} />
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    <div>{t('events.soldOfQuota', { sold: e.sold, quota: e.quota })}</div>
                    <FillBar paid={e.sold} quota={e.quota} className="mt-1.5 ml-auto w-24" />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
    </div>
  )
}
