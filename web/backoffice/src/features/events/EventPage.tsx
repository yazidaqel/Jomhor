import { useRef, useState, type ComponentProps, type RefObject } from 'react'
import { flushSync } from 'react-dom'
import { useQueryClient } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { ChartNoAxesColumn, ClipboardCheck } from 'lucide-react'
import { Controller, useForm, useWatch, type FieldError as RhfFieldError, type UseFormRegisterReturn, type UseFormReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { ErrorNotice, LoadingRows, PageHeader } from '@/components/page'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel, FieldLegend, FieldSet } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useCanWrite, useOrganizer } from '@/features/auth/organizer'
import { useVenue, useVenues } from '@/features/venues/api'
import { GateZoneMatrix } from '@/features/venues/GateZoneMatrix'
import { ApiError, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'
import { formatDateTime, offsetLabel, wallToUtc } from '@/lib/time'
import {
  createBody,
  EDITABLE,
  emptyForm,
  EVENT_TYPES,
  eventSchema,
  METADATA_KEYS,
  patchBody,
  toForm,
  useEvent,
  useEventMutation,
  type EventDetail,
  type EventForm,
  type EventValues,
} from './api'
import { StatusBadge } from './EventsPage'
import { DevicesTab } from './DevicesTab'
import { PricesTab } from './PricesTab'

type Tab = 'general' | 'prices' | 'gates' | 'devices' | 'rules'
// Règles fields; every other form field is on Général. Both tabs are one form (one save, one PATCH).
const RULES_FIELDS: readonly string[] = ['per_account_limit']
// These tabs need a saved event.
const AFTER_SAVE: readonly Tab[] = ['prices', 'gates', 'devices']
const ALL_TABS: readonly Tab[] = ['general', 'prices', 'gates', 'devices', 'rules']
const WALL = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

/** B2 Configuration événement: `/events/new` (create) and `/events/:id` (edit). */
export function EventPage() {
  const id = useParams().id
  if (id === undefined) return <EventEditor />
  // Event ids are integers: `/events/01` and `/events/1` are one event, one query key. Anything else is unknown.
  if (!/^\d+$/.test(id)) return <Navigate to="/events" replace state={{ notFound: true }} />
  return <EditEvent id={String(Number(id))} />
}

function EditEvent({ id }: { id: string }) {
  const event = useEvent(id)
  if (event.error instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(event.error.code)) {
    // Unknown id, another organizer's, or not an id at all.
    return <Navigate to="/events" replace state={{ notFound: true }} />
  }
  if (event.isPending) return <LoadingRows rows={6} />
  if (!event.data) return <ErrorNotice error={event.error} onRetry={() => void event.refetch()} className="p-0" />
  return <EventEditor key={event.data.id} event={event.data} />
}

function EventEditor({ event }: { event?: EventDetail }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { organizer_id: organizerId } = useOrganizer()
  const canWrite = useCanWrite()
  const editable = canWrite && (!event || EDITABLE.includes(event.status))
  const venues = useVenues()
  // `?tab=prices` (Aperçu's "Tarifs & quotas" shortcut) opens on that tab once the event exists.
  const [search] = useSearchParams()
  // Appareils is ADMIN/OPS (FINANCE gets 403 on assignments): not offered to read-only roles.
  const TABS = canWrite ? ALL_TABS : ALL_TABS.filter((x) => x !== 'devices')
  const [tab, setTab] = useState<Tab>(() => {
    const asked = TABS.find((x) => x === search.get('tab'))
    return asked && (event || !AFTER_SAVE.includes(asked)) ? asked : 'general'
  })
  const [rowEditing, setRowEditing] = useState(false) // a price row is open on Tarifs & quotas
  const [publishRefused, setPublishRefused] = useState(false) // EVENT_NOT_EDITABLE on publish, shown on the page
  const refusedAlert = useRef<HTMLParagraphElement>(null)

  // doors_open_at and sale_starts_at cannot be cleared (04-api §Organizer): once set, they stay required.
  // Edit: `values` follows the server copy (refetch on focus, after a write); fields the user changed keep their value,
  // and errors on screen (zod or VALIDATION_FAILED) stay.
  // Wall times are in the venue's zone: the event's once saved, the chosen venue's before.
  const zoneOf = (venueId: string) => event?.timezone ?? venues.data?.find((v) => v.id === venueId)?.timezone
  const form = useForm<EventForm, unknown, EventValues>({
    resolver: (values, ctx, options) =>
      zodResolver(eventSchema(t, zoneOf(values.venue_id), !!event?.doors_open_at, !!event?.sale_starts_at))(values, ctx, options),
    defaultValues: event ? toForm(event) : emptyForm(),
    values: event && toForm(event),
    resetOptions: { keepDirtyValues: true, keepErrors: true },
  })
  const save = useEventMutation()
  const { errors, isDirty } = form.formState
  const [type, venueId] = useWatch({ control: form.control, name: ['event_type', 'venue_id'] })
  const metadataKeys = METADATA_KEYS[type]
  const zone = zoneOf(venueId)

  /**
   * Shows the tab holding the first of these fields (a hidden field's error would go unseen), synchronously so the
   * focus RHF gives the field right after lands on a visible input.
   */
  const reveal = (fields: string[]) =>
    flushSync(() => {
      if (fields.some((f) => !RULES_FIELDS.includes(f.split('.')[0]!))) setTab('general')
      else if (fields.length) setTab('rules')
    })
  const onSaved = (e: EventDetail) => {
    if (event) {
      form.reset(toForm(e)) // nothing dirty any more, before `values` follows the new server copy
      toast.success(t('events.saved'))
    }
    queryClient.setQueryData(['org', organizerId, 'events', String(e.id)], e)
    if (!event) void navigate(`/events/${encodeURIComponent(e.id)}`)
  }
  const onError = (e: unknown) => {
    if (event && e instanceof ApiError && e.code === 'NOT_FOUND') return void navigate('/events', { replace: true, state: { notFound: true } })
    const fields = e instanceof ApiError && e.code === 'VALIDATION_FAILED' ? Object.keys(Object(e.details.fields)) : []
    reveal(fields) // before showFormError focuses the first of them
    showFormError(e, form, t)
    // Its one rule (also broken by a T0 change alone): before T0 − 60 min.
    if (fields.includes('sale_starts_at')) form.setError('sale_starts_at', { message: t('events.form.saleBeforeStart') })
    // Create with a venue deleted meanwhile (or not this organizer's): 422 on venue_id. Refresh the picker.
    if (!event && fields.includes('venue_id')) {
      form.setError('venue_id', { message: t('events.form.venueGone') })
      void queryClient.invalidateQueries({ queryKey: ['org', organizerId, 'venues'] })
    }
  }
  const submit = form.handleSubmit(
    (v) => {
      if (!event) {
        if (!zone) return form.setError('venue_id', { message: t('events.form.venueRequired') })
        return save.mutate({ method: 'POST', path: '/org/events', body: createBody(v, zone) }, { onSuccess: onSaved, onError })
      }
      const body = patchBody(event, v)
      if (Object.keys(body).length === 0) return form.reset(form.getValues()) // edited back to what is saved
      save.mutate({ method: 'PATCH', path: path('org', 'events', event.id), body }, { onSuccess: onSaved, onError })
    },
    (invalid) => reveal(Object.keys(invalid)),
  )

  const title = event?.title ?? t('events.new')
  const err = (e?: RhfFieldError) => e?.message
  const canPublish = !!event && canWrite && event.status === 'DRAFT'
  const unsaved = isDirty || rowEditing
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        crumbs={[{ label: t('events.title'), to: '/events' }, { label: title }]}
        title={title}
        badge={<StatusBadge status={event?.status ?? 'DRAFT'} />}
        actions={
          <>
            {event && (
              <Button variant="outline" asChild>
                <Link to={`/events/${encodeURIComponent(event.id)}/sales`}>
                  <ChartNoAxesColumn />
                  {t('nav.sales')}
                </Link>
              </Button>
            )}
            {/* A draft has no ticket and no scan: nothing to reconcile yet. */}
            {event && event.status !== 'DRAFT' && (
              <Button variant="outline" asChild>
                <Link to={`/events/${encodeURIComponent(event.id)}/reconciliation`}>
                  <ClipboardCheck />
                  {t('reconciliation.title')}
                </Link>
              </Button>
            )}
            {editable && (
              <Button type="submit" form="event-form" variant="outline" disabled={save.isPending || (!!event && !isDirty)}>
                {t(event?.status === 'ON_SALE' ? 'events.saveChanges' : 'events.saveDraft')}
              </Button>
            )}
            {canPublish && (
              <PublishAction
                event={event}
                dirty={unsaved}
                onPublished={(e) => {
                  queryClient.setQueryData(['org', organizerId, 'events', String(e.id)], e)
                  setPublishRefused(false)
                  toast.success(t('events.publish.done'))
                }}
                onRefused={() => setPublishRefused(true)}
                refusedFocus={refusedAlert}
              />
            )}
          </>
        }
      />
      {!canWrite && <p className="text-sm text-muted-foreground">{t('events.readOnly')}</p>}
      {canWrite && !editable && <p className="text-sm text-muted-foreground">{t('events.notEditable')}</p>}
      {canPublish && unsaved && <p className="text-sm text-muted-foreground">{t('events.publish.saveFirst')}</p>}
      {/* Outlives the publish button: the refetch that follows shows the event is no longer a draft. */}
      {publishRefused && (
        <p ref={refusedAlert} tabIndex={-1} role="alert" className="text-sm text-destructive">
          {t('events.publish.EVENT_NOT_EDITABLE')}
        </p>
      )}
      {errors.root?.message && <FieldError>{errors.root.message}</FieldError>}

      <Tabs value={tab} onValueChange={(v) => setTab(v as Tab)}>
        <TabsList>
          {TABS.map((id) => {
            const later = !event && AFTER_SAVE.includes(id)
            return (
              <TabsTrigger key={id} value={id} disabled={later}>
                {t(`events.tabs.${id}`)}
                {later && (
                  <>
                    {' '}
                    <span className="text-xs font-normal">({t('events.tabs.afterSave')})</span>
                  </>
                )}
              </TabsTrigger>
            )
          })}
        </TabsList>

        {/* Général and Règles stay mounted (hidden when inactive): their fields live in one form across tabs. */}
        <TabsContent value="general" forceMount className="data-[state=inactive]:hidden">
          <Card className="max-w-3xl">
            <CardHeader>
              <CardTitle role="heading" aria-level={2}>
                {t('events.tabs.general')}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <form id="event-form" noValidate onSubmit={(e) => void submit(e)}>
                <FieldSet disabled={!editable || save.isPending}>
                  <FieldGroup className="grid gap-6 md:grid-cols-2">
                    <Controller
                      control={form.control}
                      name="event_type"
                      render={({ field }) => (
                        <Field>
                          <FieldLabel htmlFor="event-type">{t('events.form.type')}</FieldLabel>
                          <Select name={field.name} value={field.value} onValueChange={field.onChange} disabled={!!event}>
                            <SelectTrigger id="event-type" ref={field.ref} onBlur={field.onBlur} className="w-full" aria-describedby={event ? 'event-type-hint' : undefined}>
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              {EVENT_TYPES.map((ty) => (
                                <SelectItem key={ty} value={ty}>
                                  {t(`events.types.${ty}`)}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                          {event && <FieldDescription id="event-type-hint">{t('events.form.fixed')}</FieldDescription>}
                        </Field>
                      )}
                    />
                    <TextInput id="event-title" label={t('events.form.title')} error={err(errors.title)} registration={form.register('title')} />
                    <Controller
                      control={form.control}
                      name="venue_id"
                      render={({ field }) => {
                        const error = err(errors.venue_id)
                        const empty = venues.data?.length === 0 && !event
                        const described = [(event || empty) && 'event-venue-hint', error && 'event-venue-error'].filter(Boolean).join(' ')
                        return (
                          <Field data-invalid={!!error} className="md:col-span-2">
                            <FieldLabel htmlFor="event-venue">{t('events.form.venue')}</FieldLabel>
                            <Select name={field.name} value={field.value} onValueChange={field.onChange} disabled={!!event}>
                              <SelectTrigger
                                id="event-venue"
                                ref={field.ref}
                                onBlur={field.onBlur}
                                className="w-full"
                                aria-invalid={!!error}
                                aria-describedby={described || undefined}
                              >
                                <SelectValue placeholder={t('events.form.venuePlaceholder')} />
                              </SelectTrigger>
                              <SelectContent>
                                {venues.data?.map((v) => (
                                  <SelectItem key={v.id} value={v.id}>
                                    {t('events.form.venueOption', { name: v.name, capacity: v.capacity })}
                                  </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                            {event ? (
                              <FieldDescription id="event-venue-hint">{t('events.form.fixed')}</FieldDescription>
                            ) : (
                              empty && (
                                <FieldDescription id="event-venue-hint">
                                  {t('events.form.noVenue')} <Link to="/settings/venues">{t('events.form.createVenue')}</Link>
                                </FieldDescription>
                              )
                            )}
                            {venues.isError && <FieldDescription className="text-destructive">{t(errorKey(venues.error))}</FieldDescription>}
                            {error && <FieldError id="event-venue-error">{error}</FieldError>}
                          </Field>
                        )
                      }}
                    />
                    <WallTimeInput
                      id="event-starts-at"
                      label={t('events.form.startsAt')}
                      zone={zone}
                      name="starts_at"
                      form={form}
                      error={err(errors.starts_at)}
                    />
                    <WallTimeInput
                      id="event-doors-open-at"
                      label={t('events.form.doorsOpenAt')}
                      zone={zone}
                      name="doors_open_at"
                      form={form}
                      error={err(errors.doors_open_at)}
                    />
                    <WallTimeInput
                      id="event-sale-starts-at"
                      label={t('events.form.saleStartsAt')}
                      note={t('events.form.saleStartsAtHint')}
                      zone={zone}
                      name="sale_starts_at"
                      form={form}
                      error={err(errors.sale_starts_at)}
                    />
                  </FieldGroup>

                  {metadataKeys.length > 0 && (
                    <FieldSet className="rounded-lg bg-muted/60 p-4">
                      <FieldLegend>{t('events.metadata.title')}</FieldLegend>
                      <FieldDescription>{t('events.metadata.description')}</FieldDescription>
                      <FieldGroup className="grid gap-4 md:grid-cols-3">
                        {metadataKeys.map((k) => (
                          <TextInput
                            key={`${type}-${k}`}
                            id={`event-metadata-${k}`}
                            inputMode={k === 'matchday' ? 'numeric' : undefined}
                            label={t(`events.metadata.${k}`)}
                            error={err(errors.metadata?.[k])}
                            registration={form.register(`metadata.${k}`)}
                          />
                        ))}
                      </FieldGroup>
                      {errors.metadata?.message && <FieldError>{errors.metadata.message}</FieldError>}
                    </FieldSet>
                  )}
                </FieldSet>
              </form>
            </CardContent>
          </Card>
        </TabsContent>

        {/* Mounted too, so an open price row survives a tab switch (publish waits for it). */}
        <TabsContent value="prices" forceMount className="data-[state=inactive]:hidden">
          {event && <PricesTab event={event} editable={editable} onEditingChange={setRowEditing} />}
        </TabsContent>

        <TabsContent value="gates">{event && <GatesTab venueId={event.venue_id} />}</TabsContent>

        {/* Mounted too, so unsaved assignments survive a tab switch. */}
        {canWrite && (
          <TabsContent value="devices" forceMount className="data-[state=inactive]:hidden">
            {event && <DevicesTab event={event} />}
          </TabsContent>
        )}

        <TabsContent value="rules" forceMount className="data-[state=inactive]:hidden">
          <Card className="max-w-3xl">
            <CardHeader>
              <CardTitle role="heading" aria-level={2}>
                {t('events.rules.title')}
              </CardTitle>
            </CardHeader>
            <CardContent>
              {/* Same handler as Général: Enter here saves the whole event form. */}
              <form noValidate onSubmit={(e) => void submit(e)}>
                <FieldSet disabled={!editable || save.isPending}>
                  <FieldGroup className="grid gap-6 md:grid-cols-2">
                    {/* Lot 2: NONE only (plan Q33); shown, never edited or sent on PATCH. */}
                    <Field>
                      <FieldLabel htmlFor="event-reentry">{t('events.rules.reentry')}</FieldLabel>
                      <Input
                        id="event-reentry"
                        readOnly
                        value={t(`events.rules.reentryPolicies.${event?.reentry_policy ?? 'NONE'}`)}
                        aria-describedby="event-reentry-hint"
                      />
                      <FieldDescription id="event-reentry-hint">{t('events.rules.reentryHint')}</FieldDescription>
                    </Field>
                    <TextInput
                      id="event-limit"
                      inputMode="numeric"
                      label={t('events.form.perAccountLimit')}
                      error={err(errors.per_account_limit)}
                      registration={form.register('per_account_limit')}
                    />
                    <Field className="md:col-span-2">
                      <FieldLabel htmlFor="event-named">{t('events.rules.named')}</FieldLabel>
                      <Input id="event-named" readOnly value={t('events.rules.namedValue')} aria-describedby="event-named-hint" />
                      <FieldDescription id="event-named-hint">{t('events.rules.namedHint')}</FieldDescription>
                    </Field>
                    <Field className="md:col-span-2">
                      <FieldLabel htmlFor="event-refund-freeze">{t('events.rules.refundFreeze')}</FieldLabel>
                      <Input id="event-refund-freeze" readOnly value={t('events.rules.refundFreezeValue')} aria-describedby="event-refund-freeze-hint" />
                      <FieldDescription id="event-refund-freeze-hint">
                        {event?.refund_cutoff_at
                          ? t('events.rules.refundFreezeAt', { date: formatDateTime(event.refund_cutoff_at, i18n.language, event.timezone), zone: event.timezone })
                          : t('events.rules.refundFreezeHint')}
                      </FieldDescription>
                    </Field>
                  </FieldGroup>
                </FieldSet>
              </form>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}

/** Portes & zones: the venue's matrix, read-only (Q5: per venue in Lot 0). */
function GatesTab({ venueId }: { venueId: string }) {
  const { t } = useTranslation()
  const venue = useVenue(venueId)
  if (venue.isPending) return <LoadingRows rows={5} />
  if (!venue.data) return <p className="text-sm text-destructive">{t(errorKey(venue.error))}</p>
  return <GateZoneMatrix venue={venue.data} readOnly description={t('events.gates.description')} />
}

/** "Publier · ouvrir la vente": confirm, POST publish; refusals stay in the dialog with their FR copy. */
function PublishAction({
  event,
  dirty,
  onPublished,
  onRefused,
  refusedFocus,
}: {
  event: EventDetail
  dirty: boolean
  onPublished: (e: EventDetail) => void
  /** EVENT_NOT_EDITABLE: the event is no longer a draft, so this button (and its dialog) is about to go. */
  onRefused: () => void
  /** Where focus goes after that refusal: the publish button it would return to is about to unmount. */
  refusedFocus: RefObject<HTMLElement | null>
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const refused = useRef(false)
  const publish = useEventMutation()
  return (
    <AlertDialog
      open={open}
      onOpenChange={(o) => {
        setOpen(o)
        publish.reset()
      }}
    >
      <Button disabled={dirty} onClick={() => setOpen(true)}>
        {t('events.publish.action')}
      </Button>
      <AlertDialogContent
        onOpenAutoFocus={() => void (refused.current = false)}
        onCloseAutoFocus={(e) => {
          if (!refused.current || !refusedFocus.current) return
          e.preventDefault()
          refusedFocus.current.focus()
        }}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>{t('events.publish.title')}</AlertDialogTitle>
          <AlertDialogDescription>{t('events.publish.body')}</AlertDialogDescription>
        </AlertDialogHeader>
        {publish.error && <FieldError>{t(publishErrorKey(publish.error))}</FieldError>}
        <AlertDialogFooter>
          <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
          <Button
            disabled={publish.isPending}
            onClick={() =>
              publish.mutate(
                { method: 'POST', path: path('org', 'events', event.id, 'publish') },
                {
                  onSuccess: (e) => {
                    onPublished(e)
                    setOpen(false)
                  },
                  onError: (e) => {
                    if (!(e instanceof ApiError && e.code === 'EVENT_NOT_EDITABLE')) return // shown in the dialog
                    refused.current = true
                    onRefused()
                    setOpen(false)
                  },
                },
              )
            }
          >
            {t('events.publish.confirm')}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/** 422 `details.reason` NO_QUOTA / STARTS_IN_PAST have their own copy (EVENT_NOT_EDITABLE goes on the page). */
function publishErrorKey(e: unknown) {
  if (e instanceof ApiError && e.code === 'VALIDATION_FAILED' && (e.details.reason === 'NO_QUOTA' || e.details.reason === 'STARTS_IN_PAST'))
    return `events.publish.${e.details.reason}` as const
  return errorKey(e)
}

/** A `datetime-local` input with the zone it is read in and that zone's offset at the entered instant. */
function WallTimeInput({
  id,
  label,
  note,
  zone,
  name,
  form,
  error,
}: {
  id: string
  label: string
  /** Shown after the zone hint. */
  note?: string
  zone?: string
  name: 'starts_at' | 'doors_open_at' | 'sale_starts_at'
  form: UseFormReturn<EventForm, unknown, EventValues>
  error?: string
}) {
  const { t } = useTranslation()
  const wall = useWatch({ control: form.control, name })
  const [now] = useState(() => Date.now())
  const at = WALL.test(wall) && zone ? Date.parse(wallToUtc(wall, zone)) : now
  const zoneHint = zone ? t('events.form.zoneHint', { zone, offset: offsetLabel(at, zone) }) : t('events.form.zoneUnknown')
  const hint = note ? `${zoneHint} · ${note}` : zoneHint
  return <TextInput id={id} type="datetime-local" label={label} hint={hint} error={error} registration={form.register(name)} />
}

function TextInput({
  id,
  label,
  hint,
  error,
  registration,
  ...input
}: { id: string; label: string; hint?: string; error?: string; registration: UseFormRegisterReturn } & Pick<ComponentProps<'input'>, 'type' | 'inputMode'>) {
  const described = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ')
  return (
    <Field data-invalid={!!error}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Input id={id} aria-invalid={!!error} aria-describedby={described || undefined} {...input} {...registration} />
      {hint && <FieldDescription id={`${id}-hint`}>{hint}</FieldDescription>}
      {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
    </Field>
  )
}
