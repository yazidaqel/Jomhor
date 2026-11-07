import { useState } from 'react'
import { Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { BrandEmpty, ErrorNotice, LoadingRows, PageHeader, STICKY_TABLE } from '@/components/page'
import { cn } from '@/lib/utils'
import { useNotFoundNotice } from '@/lib/useNotFoundNotice'
import { useCanWrite } from '@/features/auth/organizer'
import { useVenues } from './api'
import { VenueDialog } from './dialogs'

/** B10 Lieux: the organizer's venues. */
export function VenuesPage() {
  const { t, i18n } = useTranslation()
  const venues = useVenues()
  const canWrite = useCanWrite()
  const navigate = useNavigate()
  const [creating, setCreating] = useState(false)
  const notFound = useNotFoundNotice() // set by the venue page on an unknown id

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={t('venues.title')}
        description={t('venues.description')}
        actions={
          canWrite && (
            <>
            <Button onClick={() => setCreating(true)}>
              <Plus />
              {t('venues.new')}
            </Button>
            <VenueDialog
              open={creating}
              onOpenChange={setCreating}
              onSaved={(v) => void navigate(`/settings/venues/${encodeURIComponent(v.id)}`)}
            />
            </>
          )
        }
      />
      {notFound && <p className="text-sm text-destructive">{t('venues.notFound')}</p>}
      {!canWrite && <p className="text-sm text-muted-foreground">{t('venues.readOnly')}</p>}
      <Card className={cn('gap-0 overflow-hidden py-0', STICKY_TABLE)}>
        <CardContent className="px-0">
          {venues.isPending ? (
            <LoadingRows className="p-5" />
          ) : venues.isError ? (
            <ErrorNotice error={venues.error} onRetry={() => void venues.refetch()} />
          ) : venues.data.length === 0 ? (
            <BrandEmpty title={t('venues.emptyTitle')} description={t('venues.empty')} />
          ) : (
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>{t('venues.name')}</TableHead>
                  <TableHead>{t('venues.city')}</TableHead>
                  <TableHead>{t('venues.timezone')}</TableHead>
                  <TableHead className="text-right">{t('venues.capacity')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {venues.data.map((v) => (
                  <TableRow key={v.id}>
                    <TableCell className="font-medium">
                      <Link to={`/settings/venues/${encodeURIComponent(v.id)}`} className="hover:underline">
                        {v.name}
                      </Link>
                    </TableCell>
                    <TableCell>{v.city}</TableCell>
                    <TableCell>{v.timezone}</TableCell>
                    <TableCell className="text-right tabular-nums">{v.capacity.toLocaleString(i18n.language)}</TableCell>
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
