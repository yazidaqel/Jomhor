import { useRef, useState } from 'react'
import { ChevronLeft, Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { path, useCanWrite, useVenue, type Gate, type VenueDetail, type Zone } from './api'
import { DeleteButton, DeleteDialog, EditButton, GateDialog, VenueDialog, ZoneDialog } from './dialogs'
import { GateZoneMatrix } from './GateZoneMatrix'
import { useDialog } from './useDialog'

/** B10 Lieux, one venue: zones, gates and the gate → zone matrix. */
export function VenuePage() {
  const { t, i18n } = useTranslation()
  const id = useParams().id!
  const venue = useVenue(id)
  const canWrite = useCanWrite()
  const [editing, setEditing] = useState(false)

  if (venue.error instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(venue.error.code)) {
    // Unknown id, another organizer's, or not an id at all.
    return <Navigate to="/settings/venues" replace state={{ notFound: true }} />
  }

  return (
    <div className="flex flex-col gap-6">
      <Button asChild variant="link" className="self-start px-0 text-muted-foreground">
        <Link to="/settings/venues">
          <ChevronLeft />
          {t('venues.back')}
        </Link>
      </Button>
      {venue.isPending ? (
        <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
      ) : !venue.data ? (
        <div className="flex items-center gap-3 text-sm">
          <span className="text-destructive">{t(errorKey(venue.error))}</span>
          <Button variant="outline" size="sm" onClick={() => void venue.refetch()}>
            {t('common.retry')}
          </Button>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h1 className="text-[22px] font-bold">{venue.data.name}</h1>
              <p className="mt-1 text-sm text-muted-foreground">
                {[venue.data.city, t('venues.capacityPlaces', { count: venue.data.capacity })].filter(Boolean).join(' · ')}
              </p>
            </div>
            {canWrite && (
              <>
                <Button variant="outline" onClick={() => setEditing(true)}>
                  {t('common.edit')}
                </Button>
                <VenueDialog venue={venue.data} open={editing} onOpenChange={setEditing} />
              </>
            )}
          </div>
          {!canWrite && <p className="text-sm text-muted-foreground">{t('venues.readOnly')}</p>}
          <div className="grid gap-6 xl:grid-cols-2">
            <Zones venue={venue.data} canWrite={canWrite} locale={i18n.language} />
            <Gates venue={venue.data} canWrite={canWrite} />
          </div>
          <GateZoneMatrix venue={venue.data} />
        </>
      )}
    </div>
  )
}

function Zones({ venue, canWrite, locale }: { venue: VenueDetail; canWrite: boolean; locale: string }) {
  const { t } = useTranslation()
  const dialog = useDialog<Zone>()
  // Focus lands here when the zone a dialog was opened from is deleted.
  const heading = useRef<HTMLDivElement>(null)
  const zone = dialog.item
  return (
    <Card>
      <CardHeader>
        <CardTitle ref={heading} tabIndex={-1} role="heading" aria-level={2}>
          {t('venues.zones.title')}
        </CardTitle>
        {canWrite && (
          <CardAction>
            <Button variant="outline" size="sm" onClick={() => dialog.edit()}>
              <Plus />
              {t('venues.zones.add')}
            </Button>
          </CardAction>
        )}
      </CardHeader>
      <CardContent>
        {venue.zones.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('venues.zones.empty')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('venues.zones.code')}</TableHead>
                <TableHead>{t('venues.zones.name')}</TableHead>
                <TableHead className="text-right">{t('venues.zones.capacity')}</TableHead>
                <TableHead>{t('venues.zones.seated')}</TableHead>
                {canWrite && <TableHead />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {venue.zones.map((z) => (
                <TableRow key={z.id}>
                  <TableCell className="font-semibold">{z.code}</TableCell>
                  <TableCell>{z.name}</TableCell>
                  <TableCell className="text-right tabular-nums">{z.capacity.toLocaleString(locale)}</TableCell>
                  <TableCell>
                    <Badge variant={z.seated ? 'secondary' : 'outline'}>{t(z.seated ? 'common.yes' : 'common.no')}</Badge>
                  </TableCell>
                  {canWrite && (
                    <TableCell className="text-right whitespace-nowrap">
                      <EditButton label={t('venues.zones.editLabel', { code: z.code })} onClick={() => dialog.edit(z)} />
                      <DeleteButton label={t('venues.zones.deleteLabel', { code: z.code })} onClick={() => dialog.remove(z)} />
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {canWrite && (
          <>
            <ZoneDialog venueId={venue.id} zone={zone} {...dialog.form} fallbackFocus={heading} />
            <DeleteDialog
              {...dialog.confirm}
              fallbackFocus={heading}
              title={t('venues.zones.deleteTitle', { code: zone?.code ?? '' })}
              body={t('venues.zones.deleteBody')}
              path={path('org', 'venues', venue.id, 'zones', zone?.id ?? '')}
            />
          </>
        )}
      </CardContent>
    </Card>
  )
}

function Gates({ venue, canWrite }: { venue: VenueDetail; canWrite: boolean }) {
  const { t } = useTranslation()
  const dialog = useDialog<Gate>()
  // Focus lands here when the gate a dialog was opened from is deleted.
  const heading = useRef<HTMLDivElement>(null)
  const gate = dialog.item
  return (
    <Card>
      <CardHeader>
        <CardTitle ref={heading} tabIndex={-1} role="heading" aria-level={2}>
          {t('venues.gates.title')}
        </CardTitle>
        {canWrite && (
          <CardAction>
            <Button variant="outline" size="sm" onClick={() => dialog.edit()}>
              <Plus />
              {t('venues.gates.add')}
            </Button>
          </CardAction>
        )}
      </CardHeader>
      <CardContent>
        {venue.gates.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('venues.gates.empty')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('venues.gates.code')}</TableHead>
                <TableHead>{t('venues.name')}</TableHead>
                {canWrite && <TableHead />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {venue.gates.map((g) => (
                <TableRow key={g.id}>
                  <TableCell className="font-semibold">{g.code}</TableCell>
                  <TableCell>{g.name}</TableCell>
                  {canWrite && (
                    <TableCell className="text-right whitespace-nowrap">
                      <EditButton label={t('venues.gates.editLabel', { code: g.code })} onClick={() => dialog.edit(g)} />
                      <DeleteButton label={t('venues.gates.deleteLabel', { code: g.code })} onClick={() => dialog.remove(g)} />
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {canWrite && (
          <>
            <GateDialog venueId={venue.id} gate={gate} {...dialog.form} fallbackFocus={heading} />
            <DeleteDialog
              {...dialog.confirm}
              fallbackFocus={heading}
              title={t('venues.gates.deleteTitle', { code: gate?.code ?? '' })}
              body={t('venues.gates.deleteBody')}
              path={path('org', 'venues', venue.id, 'gates', gate?.id ?? '')}
            />
          </>
        )}
      </CardContent>
    </Card>
  )
}
