import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { ErrorNotice, LoadingRows } from '@/components/page'
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
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Textarea } from '@/components/ui/textarea'
import { useOrganizer } from '@/features/auth/organizer'
import { useEvents } from '@/features/events/api'
import { ApiError, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'
import { DEFAULT_ZONE, formatDateTime } from '@/lib/time'
import { cn } from '@/lib/utils'
import {
  AUDIENCES,
  BODY_MAX,
  CHANNELS,
  TITLE_MAX,
  campaignSchema,
  scheduleSchema,
  useCampaign,
  useCampaignMutation,
  type Campaign,
  type CampaignForm,
  zoneOf,
} from './api'

const NO_EVENT = 'none' // Radix Select items cannot carry ''

const formValues = (c?: Campaign): CampaignForm => ({
  channel: c?.channel ?? 'PUSH',
  audience: c?.audience ?? 'MEMBERS',
  event_id: c?.event_id == null ? '' : String(c.event_id),
  title: c?.title ?? '',
  body: c?.body ?? '',
})

const isStatusConflict = (e: unknown) => e instanceof ApiError && e.code === 'CAMPAIGN_STATUS'

/** "Nouvelle campagne" (no `id`) or a DRAFT being edited. Mounted per opening (keyed by the tab). */
export function CampaignSheet({ open, onOpenChange, id }: { open: boolean; onOpenChange: (open: boolean) => void; id?: string }) {
  const { t } = useTranslation()
  const close = () => onOpenChange(false)
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full gap-0 overflow-y-auto sm:max-w-xl" closeLabel={t('common.close')}>
        <SheetHeader className="border-b p-5">
          <SheetTitle>{t(id ? 'crm.composer.editTitle' : 'crm.composer.newTitle')}</SheetTitle>
          <SheetDescription>{t('crm.composer.description')}</SheetDescription>
        </SheetHeader>
        <div className="p-5">{id ? <Draft id={id} onDone={close} /> : <Composer onDone={close} />}</div>
      </SheetContent>
    </Sheet>
  )
}

function Draft({ id, onDone }: { id: string; onDone: () => void }) {
  const campaign = useCampaign(id)
  // Data first: a failed background refetch must not unmount the form being edited.
  if (campaign.data) return <Composer initial={campaign.data} onDone={onDone} />
  if (campaign.isError) return <ErrorNotice error={campaign.error} onRetry={() => void campaign.refetch()} className="p-0" />
  return <LoadingRows />
}

function Composer({ initial, onDone }: { initial?: Campaign; onDone: () => void }) {
  const { t, i18n } = useTranslation()
  const { organizer_id: organizerId, organizer_name: organizerName } = useOrganizer()
  const queryClient = useQueryClient()
  const events = useEvents()
  // 04: a campaign's event must not be DRAFT.
  const choices = events.data?.filter((e) => e.status !== 'DRAFT') ?? []

  const form = useForm({ resolver: zodResolver(campaignSchema(t)), defaultValues: formValues(initial) })
  const [channel, audience, eventId, title, body] = useWatch({ control: form.control, name: ['channel', 'audience', 'event_id', 'title', 'body'] })
  const [id, setId] = useState(initial?.id)
  const campaign = useCampaign(id)
  const save = useCampaignMutation()
  const send = useCampaignMutation()
  const [confirm, setConfirm] = useState<number | null>(null)
  // Q97: the event's venue zone; no event, Africa/Casablanca. Sending waits for a clean save, so this is the saved event.
  // Unknown while the events load or failed: Programmer waits rather than guess a zone.
  const zone = zoneOf(events.data, eventId)
  const schedule = useForm({ resolver: zodResolver(scheduleSchema(t, zone ?? DEFAULT_ZONE)), defaultValues: { scheduled_at: '' } })

  const { errors, isDirty, defaultValues } = form.formState
  const canSend = !!id && !isDirty && !save.isPending && !send.isPending
  // Q101: PATCH cannot clear `event_id` (null = unchanged), so a saved draft's event can be changed, not removed.
  const eventLocked = !!id && !!defaultValues?.event_id

  // Sent or cancelled meanwhile: say so and close; the list is refetched by the mutation.
  const conflict = (e: unknown) => {
    toast.error(t(errorKey(e)))
    onDone()
  }

  const onSave = form.handleSubmit((v) => {
    const payload = { channel: v.channel, audience: v.audience, title: v.title, body: v.body, ...(v.event_id && { event_id: Number(v.event_id) }) }
    save.mutate(id ? { method: 'PATCH', path: path('org', 'campaigns', id), body: payload } : { method: 'POST', path: '/org/campaigns', body: payload }, {
      onSuccess: (c) => {
        // The answer is the GET shape: shown at once (the estimate included) while the refetch runs.
        queryClient.setQueryData(['org', organizerId, 'campaigns', c.id], c)
        setId(c.id)
        form.reset(formValues(c))
        toast.success(t('crm.composer.saved'))
      },
      onError: (e) => {
        if (e instanceof ApiError && e.details.reason === 'NOT_CONFIGURED')
          return form.setError('channel', { message: t('crm.composer.notConfigured') }, { shouldFocus: true })
        if (isStatusConflict(e)) return conflict(e)
        showFormError(e, form, t)
      },
    })
  })

  const sendNow = async () => {
    // A fresh estimate, computed by the server on read.
    const r = await campaign.refetch()
    // A failed refetch keeps the previous data: never confirm on it.
    if (!r.isSuccess) return void toast.error(t(errorKey(r.error)))
    send.reset() // no error left over from Programmer inside the confirm
    setConfirm(r.data.audience_estimate)
  }

  const onSchedule = schedule.handleSubmit(({ scheduled_at }) =>
    zone &&
    send.mutate(
      { method: 'POST', path: `${path('org', 'campaigns', id!)}/send`, body: { scheduled_at } },
      {
        onSuccess: () => {
          toast.success(t('crm.schedule.done', { date: formatDateTime(scheduled_at, i18n.language, zone) }))
          onDone()
        },
        onError: (e) => (isStatusConflict(e) ? conflict(e) : showFormError(e, schedule, t)),
      },
    ),
  )

  const estimate = campaign.data?.audience_estimate

  return (
    <div className="flex flex-col gap-6">
      <form noValidate onSubmit={(e) => void onSave(e)}>
        <FieldGroup>
          <Controller
            control={form.control}
            name="channel"
            render={({ field, fieldState }) => (
              <Field data-invalid={!!fieldState.error}>
                <FieldLabel htmlFor="campaign-channel">{t('crm.composer.channel')}</FieldLabel>
                <Select name={field.name} value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger
                    id="campaign-channel"
                    ref={field.ref}
                    onBlur={field.onBlur}
                    className="w-full"
                    aria-invalid={!!fieldState.error}
                    aria-describedby={fieldState.error ? 'campaign-channel-error' : undefined}
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {CHANNELS.map((c) => (
                      <SelectItem key={c} value={c}>
                        {t(`crm.channels.${c}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {field.value === 'WHATSAPP' && <FieldDescription>{t('crm.composer.whatsappHint')}</FieldDescription>}
                {fieldState.error && <FieldError id="campaign-channel-error">{fieldState.error.message}</FieldError>}
              </Field>
            )}
          />
          <Controller
            control={form.control}
            name="audience"
            render={({ field }) => (
              <Field>
                <FieldLabel htmlFor="campaign-audience">{t('crm.composer.audience')}</FieldLabel>
                <Select name={field.name} value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger id="campaign-audience" ref={field.ref} onBlur={field.onBlur} className="w-full" aria-describedby="campaign-audience-hint">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {AUDIENCES.map((a) => (
                      <SelectItem key={a} value={a}>
                        {t(`crm.audiences.${a}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {/* Q62: holders get service information about their event only; members are marketing, opt-in only. */}
                {field.value === 'EVENT_HOLDERS' ? (
                  <FieldDescription id="campaign-audience-hint" className="rounded-md border border-warning-border bg-warning-muted px-3 py-2 text-warning-foreground">
                    {t('crm.composer.holdersNote')}
                  </FieldDescription>
                ) : (
                  <FieldDescription id="campaign-audience-hint">{t('crm.composer.membersHint')}</FieldDescription>
                )}
              </Field>
            )}
          />
          <Controller
            control={form.control}
            name="event_id"
            render={({ field, fieldState }) => (
              <Field data-invalid={!!fieldState.error}>
                <FieldLabel htmlFor="campaign-event">{t(audience === 'EVENT_HOLDERS' ? 'crm.composer.event' : 'crm.composer.eventOptional')}</FieldLabel>
                <Select name={field.name} value={field.value || (audience === 'MEMBERS' ? NO_EVENT : '')} onValueChange={(v) => field.onChange(v === NO_EVENT ? '' : v)}>
                  <SelectTrigger
                    id="campaign-event"
                    ref={field.ref}
                    onBlur={field.onBlur}
                    className="w-full"
                    aria-invalid={!!fieldState.error}
                    aria-describedby={cn('campaign-event-hint', eventLocked && 'campaign-event-locked', fieldState.error && 'campaign-event-error')}
                  >
                    <SelectValue placeholder={t('crm.composer.eventRequired')} />
                  </SelectTrigger>
                  <SelectContent>
                    {audience === 'MEMBERS' && !eventLocked && <SelectItem value={NO_EVENT}>{t('crm.composer.noEvent')}</SelectItem>}
                    {choices.map((e) => (
                      <SelectItem key={e.id} value={String(e.id)}>
                        <span dir="auto">{e.title}</span> · {formatDateTime(e.starts_at, i18n.language, e.venue.timezone)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FieldDescription id="campaign-event-hint">{t('crm.composer.eventHint')}</FieldDescription>
                {eventLocked && <FieldDescription id="campaign-event-locked">{t('crm.composer.eventLocked')}</FieldDescription>}
                {fieldState.error && <FieldError id="campaign-event-error">{fieldState.error.message}</FieldError>}
              </Field>
            )}
          />
          <Field data-invalid={!!errors.title}>
            <FieldLabel htmlFor="campaign-title">{t('crm.composer.titleLabel')}</FieldLabel>
            <Input
              id="campaign-title"
              dir="auto"
              autoComplete="off"
              aria-invalid={!!errors.title}
              aria-describedby={cn('campaign-title-count', errors.title && 'campaign-title-error')}
              {...form.register('title')}
            />
            <Counter id="campaign-title-count" value={title} max={TITLE_MAX} />
            {errors.title && <FieldError id="campaign-title-error">{errors.title.message}</FieldError>}
          </Field>
          <Field data-invalid={!!errors.body}>
            <FieldLabel htmlFor="campaign-body">{t('crm.composer.bodyLabel')}</FieldLabel>
            <Textarea
              id="campaign-body"
              dir="auto"
              rows={5}
              aria-invalid={!!errors.body}
              aria-describedby={cn('campaign-body-count', errors.body && 'campaign-body-error')}
              {...form.register('body')}
            />
            <Counter id="campaign-body-count" value={body} max={BODY_MAX} />
            {errors.body && <FieldError id="campaign-body-error">{errors.body.message}</FieldError>}
          </Field>
          {channel === 'PUSH' && <PushPreview organizer={organizerName} title={title} body={body} />}
          {errors.root?.message && <FieldError>{errors.root.message}</FieldError>}
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p role="status" className="text-sm font-medium">
              {estimate !== undefined && t('crm.composer.estimate', { count: estimate })}
            </p>
            <Button type="submit" variant="outline" disabled={save.isPending || (!!id && !isDirty)}>
              {t('crm.composer.save')}
            </Button>
          </div>
        </FieldGroup>
      </form>

      {id && (
        <>
          <Separator />
          {isDirty && <p className="text-sm text-muted-foreground">{t('crm.composer.saveFirst')}</p>}
          <Button disabled={!canSend} onClick={() => void sendNow()}>
            {t('crm.composer.sendNow')}
          </Button>
          <form noValidate onSubmit={(e) => void onSchedule(e)} className="flex flex-wrap items-start gap-3">
            <Field data-invalid={!!schedule.formState.errors.scheduled_at} className="min-w-56 flex-1">
              <FieldLabel htmlFor="campaign-schedule">{t('crm.schedule.label')}</FieldLabel>
              <Input
                id="campaign-schedule"
                type="datetime-local"
                aria-invalid={!!schedule.formState.errors.scheduled_at}
                aria-describedby={cn('campaign-schedule-hint', schedule.formState.errors.scheduled_at && 'campaign-schedule-error')}
                {...schedule.register('scheduled_at')}
              />
              {zone ? (
                <FieldDescription id="campaign-schedule-hint">{t('crm.schedule.hint', { zone })}</FieldDescription>
              ) : events.isError ? (
                <ErrorNotice error={events.error} onRetry={() => void events.refetch()} className="p-0" />
              ) : (
                <FieldDescription id="campaign-schedule-hint">{t('crm.schedule.zoneLoading')}</FieldDescription>
              )}
              {schedule.formState.errors.scheduled_at && <FieldError id="campaign-schedule-error">{schedule.formState.errors.scheduled_at.message}</FieldError>}
              {schedule.formState.errors.root?.message && <FieldError>{schedule.formState.errors.root.message}</FieldError>}
            </Field>
            <Button type="submit" variant="outline" className="mt-[1.375rem]" disabled={!canSend || !zone}>
              {t('crm.schedule.submit')}
            </Button>
          </form>
        </>
      )}

      <AlertDialog open={confirm !== null} onOpenChange={(o) => !o && setConfirm(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('crm.composer.confirmTitle', { count: confirm ?? 0 })}</AlertDialogTitle>
            <AlertDialogDescription>{t('crm.composer.confirmBody')}</AlertDialogDescription>
          </AlertDialogHeader>
          {send.error && !isStatusConflict(send.error) && <FieldError>{t(errorKey(send.error))}</FieldError>}
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <Button
              disabled={send.isPending}
              onClick={() =>
                send.mutate(
                  { method: 'POST', path: `${path('org', 'campaigns', id!)}/send`, body: {} },
                  {
                    onSuccess: () => {
                      toast.success(t('crm.composer.sent'))
                      setConfirm(null)
                      onDone()
                    },
                    onError: (e) => {
                      if (!isStatusConflict(e)) return
                      setConfirm(null)
                      conflict(e)
                    },
                  },
                )
              }
            >
              {t('crm.composer.confirm')}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

/** Characters after trim, as the server counts them; red past the limit. */
function Counter({ id, value, max }: { id: string; value: string; max: number }) {
  const { t } = useTranslation()
  const count = value.trim().length
  return (
    <FieldDescription id={id} className={cn('text-right tabular-nums', count > max && 'text-destructive')}>
      {t('crm.composer.counter', { count, max })}
    </FieldDescription>
  )
}

/** What a fan's lock screen shows: the app, the club, the title and the start of the message. Free text: `dir="auto"`. */
function PushPreview({ organizer, title, body }: { organizer: string; title: string; body: string }) {
  const { t } = useTranslation()
  return (
    <figure aria-label={t('crm.composer.preview')} className="mx-auto w-full max-w-xs rounded-[2rem] border-[6px] border-foreground/85 bg-gradient-to-b from-muted to-muted/40 px-3 pt-8 pb-10 shadow-sm">
      <div className="rounded-2xl bg-background/95 p-3 shadow-md">
        <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
          <img src="/jomhor-mark-dark.svg" alt="" className="size-4 rounded" />
          <span className="truncate font-medium uppercase tracking-wide">{t('app.name')}</span>
          <span className="ml-auto shrink-0">{t('crm.composer.previewNow')}</span>
        </div>
        <p className="mt-1.5 text-[11px] text-muted-foreground" dir="auto">
          {organizer}
        </p>
        <p className="truncate text-sm font-semibold" dir="auto">
          {title.trim() || t('crm.composer.previewTitle')}
        </p>
        <p className="line-clamp-3 text-sm text-muted-foreground" dir="auto">
          {body.trim() || t('crm.composer.previewBody')}
        </p>
      </div>
    </figure>
  )
}
