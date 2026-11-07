import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { BrandEmpty, ErrorNotice, LoadingRows, STICKY_TABLE } from '@/components/page'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { FieldError } from '@/components/ui/field'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useOrganizer } from '@/features/auth/organizer'
import { useAssignments, useDeviceMutation, useDevices, type Assignment } from '@/features/devices/api'
import { DeviceStatusBadge } from '@/features/devices/DevicesPage'
import { useVenue } from '@/features/venues/api'
import { ApiError, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import { cn } from '@/lib/utils'
import type { EventDetail } from './api'

const NONE = 'none' // Radix Select items cannot have an empty value
type Row = Omit<Assignment, 'gate_id' | 'gate_code'> & { gate_id: string | null }
type Pair = { device_id: string; gate_id: string }
const errorIdOf = (deviceId: string) => `assign-${deviceId}-error`

/**
 * Rows: this event's assignments (in the API's order, revoked devices included), then the organizer's other ACTIVE
 * devices by label, unassigned. Only ACTIVE devices can be added (04-api PUT /org/events/{id}/devices).
 */
function rowsOf(assigned: Assignment[], devices: { id: string; label: string; status: Row['status']; last_seen_at: string | null }[]): Row[] {
  const ids = new Set(assigned.map((a) => a.device_id))
  const free = devices
    .filter((d) => d.status === 'ACTIVE' && !ids.has(d.id))
    .sort((a, b) => a.label.localeCompare(b.label))
    .map((d): Row => ({ device_id: d.id, label: d.label, status: d.status, gate_id: null, manifest_version: null, last_seen_at: d.last_seen_at, closed_ack_at: null }))
  return [...assigned, ...free]
}

/** Per-device messages from a refused PUT, read against the rows that were sent (`assignments[i]` = sent[i]). */
function rowErrors(e: unknown, sent: Pair[] | undefined): Record<string, 'rowDevice' | 'rowGate' | 'rowInUse'> {
  if (!(e instanceof ApiError) || !sent) return {}
  if (e.code === 'DEVICE_IN_USE')
    return Object.fromEntries(([] as unknown[]).concat(e.details.device_ids ?? []).map((id) => [String(id), 'rowInUse' as const]))
  if (e.code !== 'VALIDATION_FAILED') return {}
  const out: Record<string, 'rowDevice' | 'rowGate'> = {}
  for (const key of Object.keys(Object(e.details.fields))) {
    const m = /^assignments\[(\d+)\]\.(device_id|gate_id)$/.exec(key)
    const id = m && sent[Number(m[1])]?.device_id
    if (id) out[id] = m[2] === 'device_id' ? 'rowDevice' : 'rowGate'
  }
  return out
}

/** B2 Appareils (Lot 2): device → gate for this event, manifest version, last seen, wipe acknowledgement. ADMIN/OPS. */
export function DevicesTab({ event }: { event: EventDetail }) {
  const { t, i18n } = useTranslation()
  const { organizer_id: organizerId } = useOrganizer()
  const queryClient = useQueryClient()
  const assignments = useAssignments(event.id)
  const devices = useDevices()
  const venue = useVenue(event.venue_id)
  const [edits, setEdits] = useState<Record<string, string>>({}) // device_id → gate_id or NONE
  const save = useDeviceMutation((assignments: Pair[], send) =>
    send<Assignment[]>({ method: 'PUT', path: `${path('org', 'events', event.id)}/devices`, body: { assignments } }),
  )
  const editable = event.status !== 'CLOSED' // CLOSED → 409 EVENT_NOT_EDITABLE

  const load = [assignments, devices, venue].find((q) => q.isError)
  if (load) return <ErrorNotice error={load.error} onRetry={() => [assignments, devices, venue].forEach((q) => void q.refetch())} className="p-0" />
  if (!assignments.data || !devices.data || !venue.data) return <LoadingRows rows={4} />

  const rows = rowsOf(assignments.data, devices.data)
  const gateOf = (r: Row) => edits[r.device_id] ?? r.gate_id ?? NONE
  const changed = rows.filter((r) => gateOf(r) !== (r.gate_id ?? NONE)).length
  const errors = rowErrors(save.error, save.variables)
  const inUse = save.error instanceof ApiError && save.error.code === 'DEVICE_IN_USE' ? Object.keys(errors) : []
  const labelOf = (id: string) => rows.find((r) => r.device_id === id)?.label ?? id
  const rootError = save.error && Object.keys(errors).length === 0 ? t(errorKey(save.error)) : null
  const when = (iso: string | null) => (iso ? formatDateTime(iso, i18n.language, event.timezone) : null)

  const submit = () =>
    // The PUT replaces the set: every row with a gate is sent, changed or not.
    save.mutate(
      rows.flatMap((r) => (gateOf(r) === NONE ? [] : [{ device_id: r.device_id, gate_id: gateOf(r) }])),
      {
        onSuccess: (saved) => {
          queryClient.setQueryData(['org', organizerId, 'devices', 'events', String(event.id)], saved)
          setEdits({})
          toast.success(t('events.devices.saved'))
        },
      },
    )

  return (
    <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
      <CardHeader className="border-b pb-5">
        <CardTitle role="heading" aria-level={2}>
          {t('events.devices.title')}
        </CardTitle>
        <CardDescription>{t('events.devices.description')}</CardDescription>
      </CardHeader>
      {rows.length === 0 ? (
        <BrandEmpty
          title={t('events.devices.emptyTitle')}
          description={t('events.devices.empty')}
          action={
            <Button asChild variant="outline">
              <Link to="/devices">{t('events.devices.goToDevices')}</Link>
            </Button>
          }
        />
      ) : (
        <CardContent className="px-0">
          {venue.data.gates.length === 0 && <p className="px-5 pt-4 text-sm text-muted-foreground">{t('events.devices.noGates')}</p>}
          {!editable && <p className="px-5 pt-4 text-sm text-muted-foreground">{t('events.devices.closed')}</p>}
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('events.devices.device')}</TableHead>
                <TableHead className="w-64">{t('events.devices.gate')}</TableHead>
                <TableHead>{t('events.devices.manifest')}</TableHead>
                <TableHead>{t('events.devices.lastSeen')}</TableHead>
                <TableHead>{t('events.devices.wiped')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => {
                const error = errors[r.device_id]
                const errorId = errorIdOf(r.device_id)
                return (
                  <TableRow key={r.device_id} data-invalid={!!error} className={cn(error && 'bg-destructive/5', gateOf(r) !== (r.gate_id ?? NONE) && 'bg-muted/40')}>
                    <TableCell className="font-medium">
                      <div className="flex flex-wrap items-center gap-2">
                        {r.label}
                        {r.status !== 'ACTIVE' && <DeviceStatusBadge status={r.status} />}
                      </div>
                    </TableCell>
                    <TableCell className="align-top">
                      <Select
                        value={gateOf(r)}
                        disabled={!editable || save.isPending}
                        onValueChange={(v) => {
                          save.reset()
                          setEdits((e) => ({ ...e, [r.device_id]: v }))
                        }}
                      >
                        <SelectTrigger
                          className="w-full"
                          aria-label={t('events.devices.gateLabel', { label: r.label })}
                          aria-invalid={!!error}
                          aria-describedby={error ? errorId : undefined}
                        >
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value={NONE}>{t('events.devices.none')}</SelectItem>
                          {/* A non-ACTIVE (revoked) device can only stay on its gate or leave (04-api PUT). */}
                          {venue.data.gates.filter((g) => r.status === 'ACTIVE' || g.id === r.gate_id).map((g) => (
                            <SelectItem key={g.id} value={g.id}>
                              {t('events.devices.gateOption', { code: g.code, name: g.name })}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      {error && (
                        <FieldError id={errorId} className="mt-1.5 max-w-64 text-xs">
                          {t(`events.devices.${error}`)}
                        </FieldError>
                      )}
                    </TableCell>
                    <TableCell className="tabular-nums">
                      {r.manifest_version === null ? (
                        <span className="text-muted-foreground">{t('events.devices.manifestNone')}</span>
                      ) : (
                        t('events.devices.manifestVersion', { version: r.manifest_version })
                      )}
                    </TableCell>
                    <TableCell className="text-muted-foreground">{when(r.last_seen_at) ?? t('events.devices.never')}</TableCell>
                    <TableCell className="text-muted-foreground">{when(r.closed_ack_at) ?? t('events.devices.wipedNo')}</TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
          {editable && (
            <div className="flex flex-wrap items-center justify-end gap-3 border-t px-5 py-4">
              <p role="status" className="mr-auto text-sm text-muted-foreground">
                {changed > 0 && t('events.devices.changed', { count: changed })}
              </p>
              {inUse.length > 0 && (
                <FieldError className="basis-full">
                  {t('events.devices.inUse', { count: inUse.length, labels: inUse.map(labelOf).join(', ') })}
                </FieldError>
              )}
              {rootError && (
                <FieldError className="basis-full">
                  {rootError}
                </FieldError>
              )}
              <Button
                variant="outline"
                disabled={changed === 0 || save.isPending}
                onClick={() => {
                  setEdits({})
                  save.reset()
                }}
              >
                {t('events.devices.reset')}
              </Button>
              <Button disabled={changed === 0 || save.isPending} onClick={submit}>
                {t('common.save')}
              </Button>
            </div>
          )}
        </CardContent>
      )}
    </Card>
  )
}
