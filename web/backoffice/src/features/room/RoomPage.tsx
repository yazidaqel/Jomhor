import { useState, type ReactNode } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { DoorOpen, Gauge, Hourglass, Plus, ShoppingCart, Ticket, Users } from 'lucide-react'
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
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel, FieldSet } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { useCanWrite } from '@/features/auth/organizer'
import { useEvent, usePrices, type EventDetail } from '@/features/events/api'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'
import { formatDateTime, formatTime, utcToWall, wallToUtc } from '@/lib/time'
import { cn } from '@/lib/utils'
import {
  canEnd,
  canPause,
  canResume,
  currentPhase,
  endBody,
  MAX_PHASES,
  rateEstimate,
  rateOnlyBody,
  roomSchema,
  toBody,
  toForm,
  useRoomMutation,
  useRooms,
  type EndBody,
  type Room,
  type RoomBody,
  type RoomForm,
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
const isRunning = (s: RoomStatus) => s === 'PRE_QUEUE' || s === 'OPEN' || s === 'PAUSED'
const WALL = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

/** B13 Salle d'attente: `/events/:id/room`. ADMIN and OPS configure and steer each phase; FINANCE reads. */
export function RoomPage() {
  const id = useParams().id ?? ''
  // Same id rule as the event page: integers only, `/01` and `/1` are one query key.
  if (!/^\d+$/.test(id)) return <Navigate to="/events" replace state={{ notFound: true }} />
  return <EventRoomPage id={String(Number(id))} />
}

/** B2 Règles row: "File d'attente · {n} phase(s) · {current status}" and a link to B13. */
export function RoomRuleRow({ eventId }: { eventId: number }) {
  const { t } = useTranslation()
  const canWrite = useCanWrite()
  const rooms = useRooms(String(eventId))
  const list = rooms.data?.rooms
  const value = list
    ? list.length
      ? t('room.ruleValue', { count: list.length, status: t(`room.statuses.${currentPhase(list)?.status ?? 'ENDED'}`) })
      : t('room.none')
    : rooms.isError
      ? t(errorKey(rooms.error))
      : t('common.loading')
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
  const rooms = useRooms(id, true)
  const prices = usePrices(id)
  const [adding, setAdding] = useState(false)

  if (isNotFound(event.error) || isNotFound(rooms.error)) return <Navigate to="/events" replace state={{ notFound: true }} />
  if (event.isPending || rooms.isPending) return <LoadingRows rows={6} />
  if (!event.data) return <ErrorNotice error={event.error} onRetry={() => void event.refetch()} className="p-0" />
  // Data first: a failed background refetch keeps the last figures.
  if (!rooms.data) return <ErrorNotice error={rooms.error} onRetry={() => void rooms.refetch()} className="p-0" />

  const list = rooms.data.rooms
  const quota = prices.data?.reduce((sum, p) => sum + p.quota, 0) ?? 0
  const next = list.length + 1
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        crumbs={[{ label: t('events.title'), to: '/events' }, { label: event.data.title, to: `/events/${encodeURIComponent(event.data.id)}` }, { label: t('room.title') }]}
        title={t('room.title')}
        description={event.data.title}
      />
      {!canWrite && <p className="text-sm text-muted-foreground">{t('room.readOnly')}</p>}
      {list.length === 0 ? (
        <Card className="max-w-3xl">
          <CardHeader>
            <CardTitle role="heading" aria-level={2}>
              {t('room.intro.title')}
            </CardTitle>
            <CardDescription>{t('room.intro.body')}</CardDescription>
          </CardHeader>
          <CardContent>
            <RoomSettings event={event.data} phase={1} room={null} canWrite={canWrite} quota={quota} />
          </CardContent>
        </Card>
      ) : (
        <ol className="flex flex-col gap-4" aria-label={t('room.phase.list')}>
          {list.map((r) => (
            <li key={r.phase}>
              <PhaseCard event={event.data} room={r} rooms={list} canWrite={canWrite} quota={quota} />
            </li>
          ))}
        </ol>
      )}
      {canWrite && adding && list.length > 0 && next <= MAX_PHASES && (
        <Card className="border-dashed">
          <CardHeader>
            <PhaseHeading phase={next} title={t('room.phase.newTitle', { n: next })} />
            <CardDescription>{t('room.phase.newBody')}</CardDescription>
          </CardHeader>
          <CardContent>
            <RoomSettings key={next} event={event.data} phase={next} room={null} canWrite={canWrite} quota={quota} onDone={() => setAdding(false)} />
          </CardContent>
        </Card>
      )}
      {canWrite && !adding && list.length > 0 && (
        <div className="flex flex-wrap items-center gap-3">
          <Button variant="outline" disabled={next > MAX_PHASES} onClick={() => setAdding(true)}>
            <Plus />
            {t('room.phase.add')}
          </Button>
          <span className="text-sm text-muted-foreground">{t(next > MAX_PHASES ? 'room.phase.max' : 'room.phase.addHint')}</span>
        </div>
      )}
    </div>
  )
}

/** The phase number in a disc, then the title (and badge): one heading per phase card. */
function PhaseHeading({ phase, title, badge }: { phase: number; title: string; badge?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <span
        aria-hidden="true"
        className="flex size-8 shrink-0 items-center justify-center rounded-full border bg-muted text-sm font-semibold text-foreground tabular-nums"
      >
        {phase}
      </span>
      <CardTitle role="heading" aria-level={2}>
        {title}
      </CardTitle>
      {badge}
    </div>
  )
}

/**
 * One phase: N°, label, status, its sale start and queue opening in the venue zone, its controls; the live KPIs while it
 * runs; its settings.
 */
function PhaseCard({ event, room, rooms, canWrite, quota }: { event: EventDetail; room: Room; rooms: Room[]; canWrite: boolean; quota: number }) {
  const { t, i18n } = useTranslation()
  const zone = event.timezone
  const later = rooms.filter((r) => r.phase > room.phase)
  const started = room.status !== 'SCHEDULED'
  const note =
    room.status === 'ENDED' ? t(later.some((r) => r.status !== 'ENDED') ? 'room.phase.ended' : 'room.ended') : started ? t('room.started') : undefined
  return (
    <Card className={cn(isRunning(room.status) && 'ring-1 ring-success-border', room.status === 'ENDED' && 'bg-muted/30')}>
      <CardHeader>
        <PhaseHeading
          phase={room.phase}
          title={room.label ? t('room.phase.titleLabel', { n: room.phase, label: room.label }) : t('room.phase.title', { n: room.phase })}
          badge={<RoomBadge room={room} zone={zone} />}
        />
        <CardDescription>
          {/* The pre-queue can open up to 1440 min before: then its own date too. */}
          {utcToWall(room.opens_at, zone).slice(0, 10) === utcToWall(room.sale_starts_at, zone).slice(0, 10)
            ? t('room.phase.when', { date: formatDateTime(room.sale_starts_at, i18n.language, zone), time: formatTime(room.opens_at, i18n.language, zone), zone })
            : t('room.phase.whenOtherDay', {
                date: formatDateTime(room.sale_starts_at, i18n.language, zone),
                opens: formatDateTime(room.opens_at, i18n.language, zone),
                zone,
              })}
        </CardDescription>
        {canWrite && (
          <CardAction className="flex flex-wrap justify-end gap-2">
            <RoomControls eventId={String(event.id)} room={room} body={endBody(rooms, room.phase)} />
          </CardAction>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        {isRunning(room.status) && <RoomKpis figures={room.figures} />}
        {/* Remounted when the phase starts: an edit left from SCHEDULED must not linger in a now-disabled field. */}
        <RoomSettings key={started ? 'started' : 'setup'} event={event} phase={room.phase} room={room} canWrite={canWrite} quota={quota} note={note} />
      </CardContent>
    </Card>
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

/** Pause / Reprendre / Terminer la file of one phase, as its status allows (04). A 409 ROOM_STATUS says so; the list is refetched. */
function RoomControls({ eventId, room, body }: { eventId: string; room: Room; body: EndBody }) {
  const { t } = useTranslation()
  const action = useRoomMutation(eventId, room.phase)
  const [ending, setEnding] = useState(false)
  const { status, phase } = room
  const run = (kind: 'pause' | 'resume' | 'end', done: string) =>
    action.mutate(
      { kind },
      {
        onSuccess: () => void toast.success(done),
        onError: (e) => void toast.error(t(errorKey(e))),
        onSettled: () => setEnding(false),
      },
    )
  return (
    <>
      {canPause(status) && (
        <Button variant="outline" disabled={action.isPending} onClick={() => run('pause', t('room.paused', { n: phase }))}>
          {t('room.actions.pause')}
        </Button>
      )}
      {canResume(status) && (
        <Button variant="outline" disabled={action.isPending} onClick={() => run('resume', t('room.resumed', { n: phase }))}>
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
              <AlertDialogTitle>{t('room.endConfirm.title', { n: phase })}</AlertDialogTitle>
              <AlertDialogDescription>{t(body)}</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
              <Button variant="destructive" disabled={action.isPending} onClick={() => run('end', t('room.endConfirm.done', { n: phase }))}>
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
 * One phase's settings. Not configured: the form (PUT creates, then `onDone`). SCHEDULED: the same form (PUT replaces).
 * Once started only the rate stays editable, applied after a confirm with the rest sent unchanged. ENDED and FINANCE:
 * read-only. Every phase has "Libellé"; phase ≥ 2 adds "Début de la vente".
 */
function RoomSettings({
  event,
  phase,
  room,
  canWrite,
  quota,
  note,
  onDone,
}: {
  event: EventDetail
  phase: number
  room: Room | null
  canWrite: boolean
  quota: number
  note?: string
  onDone?: () => void
}) {
  const { t, i18n } = useTranslation()
  const zone = event.timezone
  const started = !!room && room.status !== 'SCHEDULED'
  const editable = canWrite && room?.status !== 'ENDED'
  const form = useForm<RoomForm>({
    resolver: zodResolver(roomSchema(t, zone, event.sale_starts_at, started, phase)),
    defaultValues: toForm(room, zone),
    // Follows the polled server copy; fields being edited keep their value.
    values: room ? toForm(room, zone) : undefined,
    resetOptions: { keepDirtyValues: true, keepErrors: true },
  })
  const save = useRoomMutation(String(event.id), phase)
  const [confirmRate, setConfirmRate] = useState<number | null>(null)
  const { errors, isDirty } = form.formState
  const [startsAt, preQueue, rate, verified] = useWatch({ control: form.control, name: ['starts_at', 'pre_queue_min', 'admission_rate_per_min', 'verified'] })

  const put = (body: RoomBody, done: string) =>
    save.mutate(
      { kind: 'put', body },
      {
        onSuccess: (r) => {
          form.reset(toForm(r, zone))
          toast.success(done)
          onDone?.()
        },
        onError: (e) => {
          if (e instanceof ApiError && e.code === 'ROOM_STATUS') return void toast.error(t(errorKey(e)))
          if (e instanceof ApiError && e.details.reason === 'SALE_START_REQUIRED') return form.setError('root', { message: t('room.form.saleStartRequired') })
          if (e instanceof ApiError && e.details.reason === 'PHASE_ORDER') {
            // 422 on `starts_at` or `pre_queue_min` (04); neither named: the previous phase is missing.
            const fields = Object.keys(Object(e.details.fields)).filter((f): f is 'starts_at' | 'pre_queue_min' => f === 'starts_at' || f === 'pre_queue_min')
            if (!fields.length) return form.setError('root', { message: t('room.form.phaseOrder') })
            return fields.forEach((f, i) => form.setError(f, { message: t('room.form.phaseOrder') }, { shouldFocus: i === 0 }))
          }
          showFormError(e, form, t)
        },
        onSettled: () => setConfirmRate(null),
      },
    )
  const submit = form.handleSubmit((v) => {
    if (started) return setConfirmRate(Number(v.admission_rate_per_min))
    put(toBody(v, zone, phase), room ? t('room.form.saved') : phase === 1 ? t('room.form.created') : t('room.phase.created', { n: phase }))
  })

  // The computed opening, in the venue zone, from this phase's sale start.
  const saleStart = phase === 1 ? event.sale_starts_at : WALL.test(startsAt) ? wallToUtc(startsAt, zone) : null
  const preQueueMin = /^\d+$/.test(preQueue.trim()) ? Number(preQueue) : null
  const opensAt = saleStart && preQueueMin !== null ? new Date(Date.parse(saleStart) - preQueueMin * 60_000).toISOString() : null
  const rateN = /^\d+$/.test(rate.trim()) ? Number(rate) : 0
  const estimate = rateN > 0 ? rateEstimate(rateN, quota) : null
  const locked = started || !editable
  const submitLabel = room ? t(started ? 'room.form.apply' : 'room.form.save') : phase === 1 ? t('room.form.create') : t('room.phase.create', { n: phase })

  return (
    <div className="flex max-w-3xl flex-col gap-4">
      {room && (
        <div className="flex flex-col gap-1">
          <h3 className="text-sm font-medium">{t('room.form.title')}</h3>
          {note && <p className="text-sm text-muted-foreground">{note}</p>}
        </div>
      )}
      <form noValidate onSubmit={(e) => void submit(e)}>
        <FieldSet disabled={!editable || save.isPending}>
          <FieldGroup className="grid gap-6 md:grid-cols-2">
            <TextField
              id={`room-${phase}-label`}
              type="text"
              label={t('room.form.label')}
              hint={t('room.form.labelHint')}
              error={errors.label?.message}
              disabled={locked}
              className={phase === 1 ? 'md:col-span-2' : undefined}
              registration={form.register('label')}
            />
            {phase >= 2 && (
              <TextField
                id={`room-${phase}-starts-at`}
                type="datetime-local"
                label={t('room.form.startsAt')}
                hint={t('room.form.startsAtHint', { zone })}
                error={errors.starts_at?.message}
                disabled={locked}
                registration={form.register('starts_at')}
              />
            )}
            <TextField
              id={`room-${phase}-pre-queue`}
              label={t('room.form.preQueue')}
              hint={
                phase === 1 && !event.sale_starts_at
                  ? t('room.form.preQueueNoSale')
                  : opensAt
                    ? t('room.form.preQueueHint', { date: formatDateTime(opensAt, i18n.language, zone), zone })
                    : undefined
              }
              error={errors.pre_queue_min?.message}
              disabled={locked}
              registration={form.register('pre_queue_min')}
            />
            <TextField
              id={`room-${phase}-rate`}
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
                    id={`room-${phase}-verified`}
                    ref={field.ref}
                    checked={field.value}
                    onCheckedChange={field.onChange}
                    onBlur={field.onBlur}
                    disabled={locked}
                    aria-invalid={!!errors.verified}
                    aria-describedby={errors.verified ? `room-${phase}-verified-hint room-${phase}-verified-error` : `room-${phase}-verified-hint`}
                  />
                  <div className="flex flex-col gap-1">
                    <FieldLabel htmlFor={`room-${phase}-verified`}>{t('room.form.verified')}</FieldLabel>
                    <FieldDescription id={`room-${phase}-verified-hint`}>{t('room.form.verifiedHint')}</FieldDescription>
                    {errors.verified?.message && <FieldError id={`room-${phase}-verified-error`}>{errors.verified.message}</FieldError>}
                  </div>
                </Field>
              )}
            />
            {verified && (
              <FieldSet className="rounded-lg bg-muted/60 p-4 md:col-span-2">
                <FieldGroup className="grid gap-4 md:grid-cols-3">
                  <TextField
                    id={`room-${phase}-account-age`}
                    label={t('room.form.accountAge')}
                    error={errors.eligibility?.min_account_age_days?.message}
                    disabled={locked}
                    registration={form.register('eligibility.min_account_age_days')}
                  />
                  <TextField
                    id={`room-${phase}-member-before`}
                    type="date"
                    label={t('room.form.memberBefore')}
                    error={errors.eligibility?.member_joined_before?.message}
                    disabled={locked}
                    registration={form.register('eligibility.member_joined_before')}
                  />
                  <TextField
                    id={`room-${phase}-events-attended`}
                    // Q182: "matchs au stade" for a football match, "événements" otherwise.
                    label={t(event.event_type === 'FOOTBALL_MATCH' ? 'room.form.eventsAttended' : 'room.form.eventsAttendedOther')}
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
            <div className="mt-6 flex justify-end gap-2">
              {onDone && (
                <Button type="button" variant="outline" disabled={save.isPending} onClick={onDone}>
                  {t('common.cancel')}
                </Button>
              )}
              <Button type="submit" disabled={save.isPending || (!!room && !isDirty)}>
                {submitLabel}
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
    </div>
  )
}

/** A labelled input: numeric by default; `type` for the label and the dates. */
function TextField({
  id,
  label,
  hint,
  error,
  disabled,
  type,
  className,
  registration,
}: {
  id: string
  label: string
  hint?: string
  error?: string
  disabled?: boolean
  type?: 'text' | 'date' | 'datetime-local'
  className?: string
  registration: UseFormRegisterReturn
}) {
  const described = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ')
  return (
    <Field data-invalid={!!error} className={className}>
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
