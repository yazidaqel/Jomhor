import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { FieldError } from '@/components/ui/field'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useOrganizer } from '@/features/auth/organizer'
import { path, useCanWrite, useVenueMutation, venueErrorKey, type Gate, type VenueDetail } from './api'

type Change = { gateId: string; zone_ids: number[] }

const sorted = (ids: Iterable<number>) => [...new Set(ids)].sort((a, b) => a - b)

/**
 * Gates that differ from the server, with their full new zone set (ids ascending, unknown zones dropped).
 * `edits` holds the gates the user touched; toggling a cell back makes the gate unchanged again.
 */
function changedGates(venue: VenueDetail, edits: Record<string, number[]>): Change[] {
  const zoneIds = new Set(venue.zones.map((z) => z.id))
  return venue.gates.flatMap((g) => {
    const edit = edits[g.id]
    if (!edit) return []
    const wanted = sorted(edit.filter((id) => zoneIds.has(id)))
    return wanted.join() === sorted(g.zone_ids).join() ? [] : [{ gateId: g.id, zone_ids: wanted }]
  })
}

/** Portes → zones: rows = gates, columns = zones. Save sends one PUT per changed gate, then refetches the venue. */
export function GateZoneMatrix({ venue }: { venue: VenueDetail }) {
  const { t } = useTranslation()
  const canWrite = useCanWrite()
  const { organizer_id: organizerId } = useOrganizer()
  const queryClient = useQueryClient()
  const [edits, setEdits] = useState<Record<string, number[]>>({})
  const changes = changedGates(venue, edits)
  const save = useVenueMutation(async (list: Change[], send) => {
    const results = await Promise.allSettled(
      list.map((c) => send<Gate>({ method: 'PUT', path: `${path('org', 'gates', c.gateId)}/zones`, body: { zone_ids: c.zone_ids } })),
    )
    // Each PUT answers with the gate as stored: the cache shows it even if the background refetch fails.
    const saved = new Map(results.flatMap((r) => (r.status === 'fulfilled' ? [[r.value.id, r.value] as const] : [])))
    queryClient.setQueryData<VenueDetail>(['org', organizerId, 'venues', venue.id], (v) =>
      v && { ...v, gates: v.gates.map((g) => saved.get(g.id) ?? g) },
    )
    return results
  })
  const failure = save.data?.find((r) => r.status === 'rejected')

  const toggle = (gateId: string, current: number[], zoneId: number, on: boolean) => {
    save.reset()
    setEdits((e) => ({ ...e, [gateId]: on ? [...current, zoneId] : current.filter((id) => id !== zoneId) }))
  }
  const submit = () =>
    save.mutate(changes, {
      // The cache now holds what the server took: drop those edits, keep the failed ones on screen.
      onSuccess: (results) =>
        setEdits((e) => {
          const next = { ...e }
          results.forEach((r, i) => r.status === 'fulfilled' && delete next[changes[i]!.gateId])
          return next
        }),
    })

  return (
    <Card>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>{t('venues.matrix.title')}</CardTitle>
        <CardDescription>{t('venues.matrix.description')}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {venue.zones.length === 0 || venue.gates.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('venues.matrix.empty')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">{t('venues.matrix.gate')}</TableHead>
                {venue.zones.map((z) => (
                  <TableHead key={z.id} scope="col" className="h-auto py-2 text-center">
                    <div>{z.code}</div>
                    <div className="text-xs font-normal text-muted-foreground">{z.name}</div>
                  </TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {venue.gates.map((g) => {
                const current = edits[g.id] ?? g.zone_ids
                return (
                  <TableRow key={g.id}>
                    <TableHead scope="row" className="font-semibold">
                      {g.code}
                    </TableHead>
                    {venue.zones.map((z) => (
                      <TableCell key={z.id} className="text-center">
                        <Checkbox
                          aria-label={t('venues.matrix.cell', { gate: g.code, zone: z.code })}
                          checked={current.includes(z.id)}
                          disabled={!canWrite || save.isPending}
                          onCheckedChange={(c) => toggle(g.id, current, z.id, c === true)}
                        />
                      </TableCell>
                    ))}
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
        {canWrite && (
          <div className="flex flex-wrap items-center justify-end gap-3">
            <p role="status" className="mr-auto text-sm text-muted-foreground">
              {changes.length > 0
                ? t('venues.matrix.changed', { count: changes.length })
                : save.isSuccess && !failure && t('venues.matrix.saved')}
            </p>
            {failure && <FieldError className="basis-full">{t(venueErrorKey(failure.reason))}</FieldError>}
            <Button
              variant="outline"
              disabled={changes.length === 0 || save.isPending}
              onClick={() => {
                setEdits({})
                save.reset()
              }}
            >
              {t('venues.matrix.reset')}
            </Button>
            <Button disabled={changes.length === 0 || save.isPending} onClick={submit}>
              {t('common.save')}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
