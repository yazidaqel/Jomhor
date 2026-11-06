import { Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCanWrite } from '@/features/auth/organizer'
import { errorKey } from '@/lib/errors'
import { useNotFoundNotice } from '@/lib/useNotFoundNotice'
import { useEvents, type EventStatus } from './api'
import { formatDateTime } from '@/lib/time'

const STATUS_VARIANT = { DRAFT: 'secondary', ON_SALE: 'default', GATES_OPEN: 'default', CLOSED: 'outline' } as const

export function StatusBadge({ status }: { status: EventStatus }) {
  const { t } = useTranslation()
  return <Badge variant={STATUS_VARIANT[status]}>{t(`events.statuses.${status}`)}</Badge>
}

/** B1 Événements: the organizer's events, in the API's order (by starts_at). */
export function EventsPage() {
  const { t, i18n } = useTranslation()
  const events = useEvents()
  const canWrite = useCanWrite()
  const notFound = useNotFoundNotice() // set by the event page on an unknown id

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-[22px] font-bold">{t('events.title')}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{t('events.description')}</p>
        </div>
        {canWrite && (
          <Button asChild>
            <Link to="/events/new">
              <Plus />
              {t('events.new')}
            </Link>
          </Button>
        )}
      </div>
      {notFound && <p className="text-sm text-destructive">{t('events.notFound')}</p>}
      {!canWrite && <p className="text-sm text-muted-foreground">{t('events.readOnly')}</p>}
      <Card className="py-2">
        <CardContent className="px-2">
          {events.isPending ? (
            <p className="p-4 text-sm text-muted-foreground">{t('common.loading')}</p>
          ) : events.isError ? (
            <div className="flex items-center gap-3 p-4 text-sm">
              <span className="text-destructive">{t(errorKey(events.error))}</span>
              <Button variant="outline" size="sm" onClick={() => void events.refetch()}>
                {t('common.retry')}
              </Button>
            </div>
          ) : events.data.length === 0 ? (
            <p className="p-4 text-sm text-muted-foreground">{t('events.empty')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
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
                      <Link to={`/events/${encodeURIComponent(e.id)}`} className="hover:underline">
                        {e.title}
                      </Link>
                    </TableCell>
                    <TableCell>{t(`events.types.${e.event_type}`)}</TableCell>
                    <TableCell>
                      {/* Each row in its venue's zone (Q7), named so a different zone stands out. */}
                      <div className="tabular-nums">{formatDateTime(e.starts_at, i18n.language, e.venue.timezone)}</div>
                      <div className="text-xs text-muted-foreground">{e.venue.timezone}</div>
                    </TableCell>
                    <TableCell>{e.venue.name}</TableCell>
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
