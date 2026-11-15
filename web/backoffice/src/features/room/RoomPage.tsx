import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { DoorOpen, Gauge, Hourglass, ShoppingCart, Ticket, Users } from 'lucide-react'
import { Controller, useForm, useWatch, type UseFormRegisterReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { ErrorNotice, LoadingRows, PageHeader } from '@/components/page'
import { FillBar } from '@/components/fill-bar'
import { Kpi } from '@/components/kpi'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel, FieldSet } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { useCanWrite, useOrganizer } from '@/features/auth/organizer'
import { useEvent, usePrices, type EventDetail } from '@/features/events/api'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'
import { formatDateTime, formatTime } from '@/lib/time'
import { cn } from '@/lib/utils'
import {
  canEnd,
  canPause,
  canResume,
  rateEstimate,
  rateOnlyBody,
  roomSchema,
  toBody,
  toForm,
  useRoom,
  useRoomMutation,
  type Room,
  type RoomBody,
  type RoomForm,
  type RoomResponse,
  type RoomStatus,
} from './api'

// Tinted like the event status badge: neutral scheduled, info pre-queue, success open, warning paused, muted ended.
const STATUS_STYLE: Record<RoomStatus, { badge: string; dot: string }> = {
  SCHEDULED: { badge: 'border-border bg-secondary text-secondary-foreground', dot: 'bg-chart-3' },
  PRE_QUEUE: { badge: 'border-info-border bg-info-muted text-info-foreground', dot: 'bg-info' },
  OPEN: { badge: 'border-success-border bg-success-muted text-success-foreground', dot: 'bg-success' },
  PAUSED: { badge: 'border-warning-border bg-warning-muted text-warning-foreground', dot: 'bg-warning' },
  ENDED: { badge: 'border-border bg-background text-muted-foreground', dot: 'bg-chart-4' },
}

const isNotFound = (e: unknown) => e instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(e.code)

/** B13 Salle d'attente: `/events/:id/room`. ADMIN and OPS configure and steer; FINANCE reads. */
export function RoomPage() {
  const id = useParams().id ?? ''
  // Same id rule as the event page: integers only, `/01` and `/1` are one query key.
  if (!/^\d+$/.test(id)) return <Navigate to="/events" replace state={{ notFound: true }} />
  return <EventRoomPage id={String(Number(id))} />
}

/** B2 Règles row: "File d'attente · {status}" and a link to B13. */
export function RoomRuleRow({ eventId }: { eventId: number }) {
  const { t } = useTranslation()
  const canWrite = useCanWrite()
  const room = useRoom(String(eventId))
  const status = room.data?.room?.status
  const value = room.data ? (status ? t(`room.statuses.${status}`) : t('room.none')) : room.isError ? t(errorKey(room.error)) : t('common.loading')
  return (
    <Field className="md:col-span-2">
      <FieldLabel htmlFor="event-room">{t('room.rule')}</FieldLabel>
      <div className="flex gap-2">
        <Input id="event-room" readOnly value={value} />
        <Button variant="outline" asChild>
          <Link to={`/events/${encodeURIComponent(eventId)}/room`}>{t(canWrite ? 'room.manage' : 'common.view')}</Link>
        </Button>
      </div>
    </Field>
  )
}

function RoomBadge({ room, zone }: { room: Room; zone: string }) {
  const { t, i18n } = useTranslation()
  const style = STATUS_STYLE[room.status]
  return (
    <Badge variant="outline" className={cn('gap-1.5 rounded-full px-2.5 font-medium', style.badge)}>
      <span className={cn('size-1.5 rounded-full', style.dot)} aria-hidden="true" />
      {room.status === 'PRE_QUEUE'
        ? t('room.preQueueBadge', { time: formatTime(room.sale_starts_at, i18n.language, zone) })
        : t(`room.statuses.${room.status}`)}
    </Badge>
  )
}

function EventRoomPage({ id }: { id: string }) {
  const { t } = useTranslation()
  const canWrite = useCanWrite()
  const event = useEvent(id)
  const room = useRoom(id, true)
  const prices = usePrices(id)

  if (isNotFound(event.error) || isNotFound(room.error)) return <Navigate to="/events" replace state={{ notFound: true }} />
  if (event.isPending || room.isPending) return <LoadingRows rows={6} />
  if (!event.data) return <ErrorNotice error={event.error} onRetry={() => void event.refetch()} className="p-0" />
  // Data first: a failed background refetch keeps the last figures.
  if (!room.data) return <ErrorNotice error={room.error} onRetry={() => void room.refetch()} className="p-0" />

  const current = room.data.room
  const quota = prices.data?.reduce((sum, p) => sum + p.quota, 0) ?? 0
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        crumbs={[{ label: t('events.title'), to: '/events' }, { label: event.data.title, to: `/events/${encodeURIComponent(event.data.id)}` }, { label: t('room.title') }]}
        title={t('room.title')}
        badge={current && <RoomBadge room={current} zone={event.data.timezone} />}
        description={event.data.title}
        actions={current && canWrite && <RoomControls eventId={id} status={current.status} />}
      />
      {!canWrite && <p className="text-sm text-muted-foreground">{t('room.readOnly')}</p>}
      {current && <RoomKpis figures={current.figures} />}
      {/* Remounted when the room starts: an edit left from SCHEDULED must not linger in a now-disabled field. */}
      <RoomSettings key={current && current.status !== 'SCHEDULED' ? 'started' : 'setup'} event={event.data} room={current} canWrite={canWrite} quota={quota} />
    </div>
  )
}

function RoomKpis({ figures: f }: { figures: Room['figures'] }) {
  const { t } = useTranslation()
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      <Kpi index={0} icon={Users} label={t('room.kpi.waiting')} value={t('room.kpi.count', { n: f.waiting })}>
        <span>{t('room.kpi.joined', { joined: f.joined })}</span>
      </Kpi>
      <Kpi index={1} icon={DoorOpen} label={t('room.kpi.admitted')} value={t('room.kpi.count', { n: f.admitted })} />
      <Kpi index={2} icon={ShoppingCart} label={t('room.kpi.holds')} value={t('room.kpi.ofMax', { n: f.active_holds, max: f.max_active_holds })}>
        <FillBar paid={f.active_holds} quota={f.max_active_holds} className="mt-1" />
      </Kpi>
      <Kpi index={3} icon={Ticket} label={t('room.kpi.remaining')} value={t('room.kpi.ofMax', { n: f.remaining, max: f.quota })}>
        <FillBar paid={f.quota - f.remaining} quota={f.quota} className="mt-1" />
        <span>{f.sold_out ? t('room.kpi.soldOut') : t('room.kpi.paid', { paid: f.paid })}</span>
      </Kpi>
      <Kpi index={4} icon={Gauge} label={t('room.kpi.rate')} value={t('room.kpi.rateValue', { rate: f.admitted_last_min })} />
      <Kpi
        index={5}
        icon={Hourglass}
        label={t('room.kpi.drain')}
        value={f.estimated_drain_s === null ? t('room.kpi.drainNone') : t('room.kpi.drainValue', { minutes: Math.ceil(f.estimated_drain_s / 60) })}
      />
    </div>
  )
}

/** Pause / Reprendre / Terminer la file, as the status allows (04). A 409 ROOM_STATUS says so; the room is refetched. */
function RoomControls({ eventId, status }: { eventId: string; status: RoomStatus }) {
  const { t } = useTranslation()
  const { organizer_id: organizerId } = useOrganizer()
  const queryClient = useQueryClient()
  const action = useRoomMutation(eventId)
  const [ending, setEnding] = useState(false)
  const run = (kind: 'pause' | 'resume' | 'end', done: string) =>
    action.mutate(
      { kind },
      {
        onSuccess: (r) => {
          queryClient.setQueryData(['org', organizerId, 'events', eventId, 'room'], r)
          toast.success(done)
        },
        onError: (e) => void toast.error(t(errorKey(e))),
        onSettled: () => setEnding(false),
      },
    )
  return (
    <>
      {canPause(status) && (
        <Button variant="outline" disabled={action.isPending} onClick={() => run('pause', t('room.paused'))}>
          {t('room.actions.pause')}
        </Button>
      )}
      {canResume(status) && (
        <Button variant="outline" disabled={action.isPending} onClick={() => run('resume', t('room.resumed'))}>
          {t('room.actions.resume')}
        </Button>
      )}
      {canEnd(status) && (
        <AlertDialog open={ending} onOpenChange={setEnding}>
          <Button variant="destructive" disabled={action.isPending} onClick={() => setEnding(true)}>
            {t('room.actions.end')}
          </Button>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>{t('room.endConfirm.title')}</AlertDialogTitle>
              <AlertDialogDescription>{t('room.endConfirm.body')}</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
              <Button variant="destructive" disabled={action.isPending} onClick={() => run('end', t('room.endConfirm.done'))}>
                {t('room.endConfirm.confirm')}
              </Button>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      )}
    </>
  )
}

/**
 * Not configured: the intro and the form (PUT creates). SCHEDULED: the same form (PUT replaces). Once started only the
 * rate stays editable, applied after a confirm with the rest sent unchanged. ENDED and FINANCE: read-only.
 */
function RoomSettings({ event, room, canWrite, quota }: { event: EventDetail; room: Room | null; canWrite: boolean; quota: number }) {
  const { t, i18n } = useTranslation()
  const { organizer_id: organizerId } = useOrganizer()
  const queryClient = useQueryClient()
  const zone = event.timezone
  const started = !!room && room.status !== 'SCHEDULED'
  const editable = canWrite && room?.status !== 'ENDED'
  const form = useForm<RoomForm>({
    resolver: zodResolver(roomSchema(t, zone, event.sale_starts_at, started)),
    defaultValues: toForm(room, zone),
    // Follows the polled server copy; fields being edited keep their value.
    values: room ? toForm(room, zone) : undefined,
    resetOptions: { keepDirtyValues: true, keepErrors: true },
  })
  const save = useRoomMutation(String(event.id))
  const [confirmRate, setConfirmRate] = useState<number | null>(null)
  const { errors, isDirty } = form.formState
  const [preQueue, rate, verified] = useWatch({ control: form.control, name: ['pre_queue_min', 'admission_rate_per_min', 'verified'] })

  const put = (body: RoomBody, done: string) =>
    save.mutate(
      { kind: 'put', body },
      {
        onSuccess: (r: RoomResponse) => {
          queryClient.setQueryData(['org', organizerId, 'events', String(event.id), 'room'], r)
          form.reset(toForm(r.room, zone))
          toast.success(done)
        },
        onError: (e) => {
          if (e instanceof ApiError && e.code === 'ROOM_STATUS') return void toast.error(t(errorKey(e)))
          if (e instanceof ApiError && e.details.reason === 'SALE_START_REQUIRED') return form.setError('root', { message: t('room.form.saleStartRequired') })
          showFormError(e, form, t)
        },
        onSettled: () => setConfirmRate(null),
      },
    )
  const submit = form.handleSubmit((v) => {
    if (started) return setConfirmRate(Number(v.admission_rate_per_min))
    put(toBody(v, zone), t(room ? 'room.form.saved' : 'room.form.created'))
  })

  // The computed opening, in the venue zone.
  const preQueueMin = /^\d+$/.test(preQueue.trim()) ? Number(preQueue) : null
  const opensAt = event.sale_starts_at && preQueueMin !== null ? new Date(Date.parse(event.sale_starts_at) - preQueueMin * 60_000).toISOString() : null
  const rateN = /^\d+$/.test(rate.trim()) ? Number(rate) : 0
  const estimate = rateN > 0 ? rateEstimate(rateN, quota) : null
  const locked = started || !editable

  return (
    <Card className="max-w-3xl">
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {t(room ? 'room.form.title' : 'room.intro.title')}
        </CardTitle>
        <CardDescription>{t(room?.status === 'ENDED' ? 'room.ended' : started ? 'room.started' : 'room.intro.body')}</CardDescription>
      </CardHeader>
      <CardContent>
        <form noValidate onSubmit={(e) => void submit(e)}>
          <FieldSet disabled={!editable || save.isPending}>
            <FieldGroup className="grid gap-6 md:grid-cols-2">
              <NumberField
                id="room-pre-queue"
                label={t('room.form.preQueue')}
                hint={
                  !event.sale_starts_at
                    ? t('room.form.preQueueNoSale')
                    : opensAt
                      ? t('room.form.preQueueHint', { date: formatDateTime(opensAt, i18n.language, zone), zone })
                      : undefined
                }
                error={errors.pre_queue_min?.message}
                disabled={locked}
                registration={form.register('pre_queue_min')}
              />
              <NumberField
                id="room-rate"
                label={t('room.form.rate')}
                hint={
                  !estimate
                    ? undefined
                    : estimate.minutes === null
                      ? t('room.form.rateHintNoQuota', { orders: estimate.orders })
                      : t('room.form.rateHint', { orders: estimate.orders, minutes: estimate.minutes })
                }
                error={errors.admission_rate_per_min?.message}
                registration={form.register('admission_rate_per_min')}
              />
              <Controller
                control={form.control}
                name="verified"
                render={({ field }) => (
                  <Field orientation="horizontal" className="md:col-span-2" data-invalid={!!errors.verified}>
                    <Switch
                      id="room-verified"
                      ref={field.ref}
                      checked={field.value}
                      onCheckedChange={field.onChange}
                      onBlur={field.onBlur}
                      disabled={locked}
                      aria-invalid={!!errors.verified}
                      aria-describedby={errors.verified ? 'room-verified-hint room-verified-error' : 'room-verified-hint'}
                    />
                    <div className="flex flex-col gap-1">
                      <FieldLabel htmlFor="room-verified">{t('room.form.verified')}</FieldLabel>
                      <FieldDescription id="room-verified-hint">{t('room.form.verifiedHint')}</FieldDescription>
                      {errors.verified?.message && <FieldError id="room-verified-error">{errors.verified.message}</FieldError>}
                    </div>
                  </Field>
                )}
              />
              {verified && (
                <FieldSet className="rounded-lg bg-muted/60 p-4 md:col-span-2">
                  <FieldGroup className="grid gap-4 md:grid-cols-3">
                    <NumberField
                      id="room-account-age"
                      label={t('room.form.accountAge')}
                      error={errors.eligibility?.min_account_age_days?.message}
                      disabled={locked}
                      registration={form.register('eligibility.min_account_age_days')}
                    />
                    <NumberField
                      id="room-member-before"
                      type="date"
                      label={t('room.form.memberBefore')}
                      error={errors.eligibility?.member_joined_before?.message}
                      disabled={locked}
                      registration={form.register('eligibility.member_joined_before')}
                    />
                    <NumberField
                      id="room-events-attended"
                      label={t('room.form.eventsAttended')}
                      error={errors.eligibility?.min_events_attended?.message}
                      disabled={locked}
                      registration={form.register('eligibility.min_events_attended')}
                    />
                  </FieldGroup>
                </FieldSet>
              )}
            </FieldGroup>
            {errors.root?.message && <FieldError className="mt-4">{errors.root.message}</FieldError>}
            {editable && (
              <div className="mt-6 flex justify-end">
                <Button type="submit" disabled={save.isPending || (!!room && !isDirty)}>
                  {t(!room ? 'room.form.create' : started ? 'room.form.apply' : 'room.form.save')}
                </Button>
              </div>
            )}
          </FieldSet>
        </form>
        <AlertDialog open={confirmRate !== null} onOpenChange={(o) => !o && setConfirmRate(null)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>{t('room.applyConfirm.title', { rate: confirmRate ?? 0 })}</AlertDialogTitle>
              <AlertDialogDescription>{t('room.applyConfirm.body')}</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
              <Button disabled={save.isPending} onClick={() => confirmRate !== null && put(rateOnlyBody(room!, confirmRate), t('room.applyConfirm.done', { rate: confirmRate }))}>
                {t('room.form.apply')}
              </Button>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </CardContent>
    </Card>
  )
}

function NumberField({
  id,
  label,
  hint,
  error,
  disabled,
  type,
  registration,
}: {
  id: string
  label: string
  hint?: string
  error?: string
  disabled?: boolean
  type?: 'date'
  registration: UseFormRegisterReturn
}) {
  const described = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ')
  return (
    <Field data-invalid={!!error}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Input
        id={id}
        type={type}
        inputMode={type ? undefined : 'numeric'}
        disabled={disabled}
        aria-invalid={!!error}
        aria-describedby={described || undefined}
        {...registration}
      />
      {hint && <FieldDescription id={`${id}-hint`}>{hint}</FieldDescription>}
      {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
    </Field>
  )
}
