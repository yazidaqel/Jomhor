import { useState, type ComponentProps } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { ChevronRight } from 'lucide-react'
import { Controller, useForm, useWatch, type FieldError as RhfFieldError, type UseFormRegisterReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel, FieldLegend, FieldSet } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useCanWrite, useOrganizer } from '@/features/auth/organizer'
import { useVenues } from '@/features/venues/api'
import { ApiError, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'
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
} from './api'
import { StatusBadge } from './EventsPage'

// Built in BO-5.
const SOON_TABS = ['prices', 'gates', 'rules'] as const

/** B2 Configuration événement: `/events/new` (create) and `/events/:id` (edit). Lot 0: tab Général only. */
export function EventPage() {
  const id = useParams().id
  return id ? <EditEvent id={id} /> : <EventEditor />
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
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { organizer_id: organizerId } = useOrganizer()
  const canWrite = useCanWrite()
  const editable = canWrite && (!event || EDITABLE.includes(event.status))
  const venues = useVenues()
  const [saved, setSaved] = useState(false)

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
  const type = useWatch({ control: form.control, name: 'event_type' })
  const metadataKeys = METADATA_KEYS[type]

  const onSaved = (e: EventDetail) => {
    if (event) {
      form.reset(toForm(e)) // nothing dirty any more, before `values` follows the new server copy
      setSaved(true)
    }
    queryClient.setQueryData(['org', organizerId, 'events', e.id], e)
    if (!event) void navigate(`/events/${encodeURIComponent(e.id)}`)
  }
  const onError = (e: unknown) => {
    if (event && e instanceof ApiError && e.code === 'NOT_FOUND') return void navigate('/events', { replace: true, state: { notFound: true } })
    showFormError(e, form, t)
    // Create with a venue deleted meanwhile (or not this organizer's): 422 on venue_id. Refresh the picker.
    if (!event && e instanceof ApiError && e.code === 'VALIDATION_FAILED' && Object.hasOwn(Object(e.details.fields), 'venue_id')) {
      form.setError('venue_id', { message: t('events.form.venueGone') })
      void queryClient.invalidateQueries({ queryKey: ['org', organizerId, 'venues'] })
    }
  }
  const submit = form.handleSubmit((v) => {
    setSaved(false)
    if (!event) return save.mutate({ method: 'POST', path: '/org/events', body: createBody(v) }, { onSuccess: onSaved, onError })
    const body = patchBody(event, v)
    if (Object.keys(body).length === 0) return form.reset(form.getValues()) // edited back to what is saved
    save.mutate({ method: 'PATCH', path: path('org', 'events', event.id), body }, { onSuccess: onSaved, onError })
  })

  const title = event?.title ?? t('events.new')
  const err = (e?: RhfFieldError) => e?.message
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
        {editable && (
          <Button type="submit" form="event-form" variant="outline" disabled={save.isPending || (!!event && !isDirty)}>
            {t(event?.status === 'ON_SALE' ? 'events.saveChanges' : 'events.saveDraft')}
          </Button>
        )}
      </div>
      {!canWrite && <p className="text-sm text-muted-foreground">{t('events.readOnly')}</p>}
      {canWrite && !editable && <p className="text-sm text-muted-foreground">{t('events.notEditable')}</p>}
      {/* The live region stays in the accessibility tree (sr-only, out of the layout) so its text is announced; the
          visible copy beside it is hidden from assistive tech to avoid reading it twice. */}
      <p role="status" className="sr-only">
        {saved && !isDirty && t('events.saved')}
      </p>
      {saved && !isDirty && (
        <p aria-hidden="true" className="text-sm text-muted-foreground">
          {t('events.saved')}
        </p>
      )}

      <Tabs value="general">
        <TabsList>
          <TabsTrigger value="general">{t('events.tabs.general')}</TabsTrigger>
          {SOON_TABS.map((tab) => (
            <TabsTrigger key={tab} value={tab} disabled>
              {t(`events.tabs.${tab}`)}{' '}
              <span className="text-xs font-normal">({t('events.tabs.soon')})</span>
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="general">
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
                    <TextInput
                      id="event-starts-at"
                      type="datetime-local"
                      label={t('events.form.startsAt')}
                      hint={t('events.form.timeHint')}
                      error={err(errors.starts_at)}
                      registration={form.register('starts_at')}
                    />
                    <TextInput
                      id="event-doors-open-at"
                      type="datetime-local"
                      label={t('events.form.doorsOpenAt')}
                      hint={t('events.form.timeHint')}
                      error={err(errors.doors_open_at)}
                      registration={form.register('doors_open_at')}
                    />
                    <Controller
                      control={form.control}
                      name="venue_id"
                      render={({ field }) => {
                        const error = err(errors.venue_id)
                        const empty = venues.data?.length === 0 && !event
                        const described = [(event || empty) && 'event-venue-hint', error && 'event-venue-error'].filter(Boolean).join(' ')
                        return (
                          <Field data-invalid={!!error}>
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
                    <TextInput
                      id="event-limit"
                      inputMode="numeric"
                      label={t('events.form.perAccountLimit')}
                      error={err(errors.per_account_limit)}
                      registration={form.register('per_account_limit')}
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
                  {errors.root?.message && <FieldError>{errors.root.message}</FieldError>}
                </FieldSet>
              </form>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
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
