import { useState } from 'react'
import { Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { errorKey } from '@/lib/errors'
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
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-[22px] font-bold">{t('venues.title')}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{t('venues.description')}</p>
        </div>
        {canWrite && (
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
        )}
      </div>
      {notFound && <p className="text-sm text-destructive">{t('venues.notFound')}</p>}
      {!canWrite && <p className="text-sm text-muted-foreground">{t('venues.readOnly')}</p>}
      <Card className="py-2">
        <CardContent className="px-2">
          {venues.isPending ? (
            <p className="p-4 text-sm text-muted-foreground">{t('common.loading')}</p>
          ) : venues.isError ? (
            <div className="flex items-center gap-3 p-4 text-sm">
              <span className="text-destructive">{t(errorKey(venues.error))}</span>
              <Button variant="outline" size="sm" onClick={() => void venues.refetch()}>
                {t('common.retry')}
              </Button>
            </div>
          ) : venues.data.length === 0 ? (
            <p className="p-4 text-sm text-muted-foreground">{t('venues.empty')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('venues.name')}</TableHead>
                  <TableHead>{t('venues.city')}</TableHead>
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
