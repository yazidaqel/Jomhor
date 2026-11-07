import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { BrandEmpty, ErrorNotice, LoadingRows, PageHeader, STICKY_TABLE } from '@/components/page'
import { FillBar } from '@/components/fill-bar'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatDateTime } from '@/lib/time'
import { cn } from '@/lib/utils'
import { useEvents } from './api'
import { StatusBadge } from './EventsPage'

/** A sidebar entry that needs an event (Ventes, Portes en direct): the organizer's events, each opening `to(id)`. */
export function EventPicker({ title, description, empty, to }: { title: string; description: string; empty: string; to: (id: number) => string }) {
  const { t, i18n } = useTranslation()
  const events = useEvents()
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={title} description={description} />
      <Card className={cn('gap-0 overflow-hidden py-0', STICKY_TABLE)}>
        {events.isPending ? (
          <LoadingRows className="p-5" />
        ) : events.isError ? (
          <ErrorNotice error={events.error} onRetry={() => void events.refetch()} />
        ) : events.data.length === 0 ? (
          <BrandEmpty title={t('events.emptyTitle')} description={empty} />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
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
                    <Link to={to(e.id)} className="rounded-sm outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50">
                      {e.title}
                    </Link>
                  </TableCell>
                  <TableCell className="tabular-nums">{formatDateTime(e.starts_at, i18n.language, e.venue.timezone)}</TableCell>
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
