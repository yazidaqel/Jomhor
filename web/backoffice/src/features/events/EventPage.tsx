import { useRef, useState, type ComponentProps, type RefObject } from 'react'
import { flushSync } from 'react-dom'
import { useQueryClient } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { ChevronRight } from 'lucide-react'
import { Controller, useForm, useWatch, type FieldError as RhfFieldError, type UseFormRegisterReturn, type UseFormReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
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
  REENTRY_POLICIES,
  toForm,
  useEvent,
  useEventMutation,
  type EventDetail,
  type EventForm,
  type EventValues,
} from './api'
import { StatusBadge } from './EventsPage'
import { PricesTab } from './PricesTab'

type Tab = 'general' | 'prices' | 'gates' | 'rules'
// Règles fields; every other form field is on Général. Both tabs are one form (one save, one PATCH).
const RULES_FIELDS: readonly string[] = ['reentry_policy', 'per_account_limit']
// These tabs need a saved event.
const AFTER_SAVE: readonly Tab[] = ['prices', 'gates']
const TABS: readonly Tab[] = ['general', 'prices', 'gates', 'rules']
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
  const { t } = useTranslation()
  const event = useEvent(id)
  if (event.error instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(event.error.code)) {
    // Unknown id, another organizer's, or not an id at all.
    return <Navigate to="/events" replace state={{ notFound: true }} />
  }
  if (event.isPending) return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  if (!event.data)
    return (
      <div className="flex items-center gap-3 text-sm">
        <span className="text-destructive">{t(errorKey(event.error))}</span>
        <Button variant="outline" size="sm" onClick={() => void event.refetch()}>
          {t('common.retry')}
        </Button>
      </div>
    )
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
  const [tab, setTab] = useState<Tab>('general')
  const [notice, setNotice] = useState<'saved' | 'published' | null>(null)
  const [rowEditing, setRowEditing] = useState(false) // a price row is open on Tarifs & quotas
  const [publishRefused, setPublishRefused] = useState(false) // EVENT_NOT_EDITABLE on publish, shown on the page
  const refusedAlert = useRef<HTMLParagraphElement>(null)

  // doors_open_at cannot be cleared in Lot 0 (04-api §Organizer): once set, it stays required.
  // Edit: `values` follows the server copy (refetch on focus, after a write); fields the user changed keep their value,
  // and errors on screen (zod or VALIDATION_FAILED) stay.
  const form = useForm({
    resolver: zodResolver(eventSchema(t, !!event?.doors_open_at)),
    defaultValues: event ? toForm(event) : emptyForm(),
    values: event && toForm(event),
    resetOptions: { keepDirtyValues: true, keepErrors: true },
  })
  const save = useEventMutation()
  const { errors, isDirty } = form.formState
  const [type, venueId] = useWatch({ control: form.control, name: ['event_type', 'venue_id'] })
  const metadataKeys = METADATA_KEYS[type]
  // Wall times are in the venue's zone: the event's once saved, the chosen venue's before.
  const zone = event?.timezone ?? venues.data?.find((v) => v.id === venueId)?.timezone

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
      setNotice('saved')
    }
    queryClient.setQueryData(['org', organizerId, 'events', String(e.id)], e)
    if (!event) void navigate(`/events/${encodeURIComponent(e.id)}`)
  }
  const onError = (e: unknown) => {
    if (event && e instanceof ApiError && e.code === 'NOT_FOUND') return void navigate('/events', { replace: true, state: { notFound: true } })
    const fields = e instanceof ApiError && e.code === 'VALIDATION_FAILED' ? Object.keys(Object(e.details.fields)) : []
    reveal(fields) // before showFormError focuses the first of them
    showFormError(e, form, t)
    // Create with a venue deleted meanwhile (or not this organizer's): 422 on venue_id. Refresh the picker.
    if (!event && fields.includes('venue_id')) {
      form.setError('venue_id', { message: t('events.form.venueGone') })
      void queryClient.invalidateQueries({ queryKey: ['org', organizerId, 'venues'] })
    }
  }
  const submit = form.handleSubmit(
    (v) => {
      setNotice(null)
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
  const noticeText = notice && !isDirty ? t(notice === 'saved' ? 'events.saved' : 'events.publish.done') : null
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <nav aria-label={t('events.breadcrumb')} className="flex items-center gap-1 text-sm text-muted-foreground">
            <Link to="/events" className="hover:text-foreground hover:underline">
              {t('events.title')}
            </Link>
            <ChevronRight className="size-4" aria-hidden="true" />
            <span aria-current="page" className="truncate">
              {title}
            </span>
          </nav>
          <div className="mt-1 flex flex-wrap items-center gap-2.5">
            <h1 className="text-[22px] font-bold">{title}</h1>
            <StatusBadge status={event?.status ?? 'DRAFT'} />
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
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
                setNotice('published')
              }}
              onRefused={() => setPublishRefused(true)}
              refusedFocus={refusedAlert}
            />
          )}
        </div>
      </div>
      {!canWrite && <p className="text-sm text-muted-foreground">{t('events.readOnly')}</p>}
      {canWrite && !editable && <p className="text-sm text-muted-foreground">{t('events.notEditable')}</p>}
      {canPublish && unsaved && <p className="text-sm text-muted-foreground">{t('events.publish.saveFirst')}</p>}
      {/* Outlives the publish button: the refetch that follows shows the event is no longer a draft. */}
      {publishRefused && (
        <p ref={refusedAlert} tabIndex={-1} role="alert" className="text-sm text-destructive">
          {t('events.publish.EVENT_NOT_EDITABLE')}
        </p>
      )}
      {/* The live region stays in the accessibility tree (sr-only, out of the layout) so its text is announced; the
          visible copy beside it is hidden from assistive tech to avoid reading it twice. */}
      <p role="status" className="sr-only">
        {noticeText}
      </p>
      {noticeText && (
        <p aria-hidden="true" className="text-sm text-muted-foreground">
          {noticeText}
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
                    <Controller
                      control={form.control}
                      name="reentry_policy"
                      render={({ field, fieldState }) => (
                        <Field data-invalid={!!fieldState.error}>
                          <FieldLabel htmlFor="event-reentry">{t('events.rules.reentry')}</FieldLabel>
                          <Select name={field.name} value={field.value} onValueChange={field.onChange}>
                            <SelectTrigger
                              id="event-reentry"
                              ref={field.ref}
                              onBlur={field.onBlur}
                              className="w-full"
                              aria-invalid={!!fieldState.error}
                              aria-describedby={fieldState.error ? 'event-reentry-error' : undefined}
                            >
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              {REENTRY_POLICIES.map((r) => (
                                <SelectItem key={r} value={r}>
                                  {t(`events.rules.reentryPolicies.${r}`)}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                          {fieldState.error && <FieldError id="event-reentry-error">{fieldState.error.message}</FieldError>}
                        </Field>
                      )}
                    />
                    <TextInput
                      id="event-limit"
                      inputMode="numeric"
                      label={t('events.form.perAccountLimit')}
                      error={err(errors.per_account_limit)}
                      registration={form.register('per_account_limit')}
                    />
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
  if (venue.isPending) return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
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
  zone,
  name,
  form,
  error,
}: {
  id: string
  label: string
  zone?: string
  name: 'starts_at' | 'doors_open_at'
  form: UseFormReturn<EventForm, unknown, EventValues>
  error?: string
}) {
  const { t } = useTranslation()
  const wall = useWatch({ control: form.control, name })
  const [now] = useState(() => Date.now())
  const at = WALL.test(wall) && zone ? Date.parse(wallToUtc(wall, zone)) : now
  const hint = zone ? t('events.form.zoneHint', { zone, offset: offsetLabel(at, zone) }) : t('events.form.zoneUnknown')
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
