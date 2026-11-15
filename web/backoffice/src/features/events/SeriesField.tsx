import { useState, type Ref } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { Plus } from 'lucide-react'
import { useForm, type UseFormRegisterReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useOrganizer } from '@/features/auth/organizer'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'
import { formatDay } from '@/lib/time'
import { useSeries, useSeriesMutation, type Series } from './api'

/**
 * "Série" select (the organizer's series) + "Nouvelle série" (DESIGN Lot 5 step 3, B2 Général and B14 PASS form).
 * `locked`: shown disabled with its reason as a tooltip (B2: PASS_TICKETS; B14: fixed after create).
 */
export function SeriesField({
  fieldRef,
  ...props
}: {
  id: string
  value: string
  onChange: (id: string) => void
  onBlur?: () => void
  fieldRef?: Ref<HTMLButtonElement>
  disabled?: boolean
  locked?: string
  hint?: string
  error?: string
}) {
  const { t, i18n } = useTranslation()
  const series = useSeries()
  const [creating, setCreating] = useState({ open: false, n: 0 })
  const disabled = props.disabled || !!props.locked
  const described = [props.hint && `${props.id}-hint`, props.locked && `${props.id}-locked`, props.error && `${props.id}-error`].filter(Boolean).join(' ')
  const label = (s: Series) => t('events.series.option', { name: s.name, from: formatDay(s.starts_on, i18n.language), to: formatDay(s.ends_on, i18n.language) })

  const trigger = (
    <SelectTrigger
      id={props.id}
      ref={fieldRef}
      onBlur={props.onBlur}
      className="w-full min-w-0"
      aria-invalid={!!props.error}
      aria-describedby={described || undefined}
    >
      <SelectValue placeholder={t('events.series.placeholder')} />
    </SelectTrigger>
  )
  return (
    <Field data-invalid={!!props.error}>
      <FieldLabel htmlFor={props.id}>{t('events.series.label')}</FieldLabel>
      <div className="flex gap-2">
        <Select value={props.value} onValueChange={props.onChange} disabled={disabled}>
          {props.locked ? (
            // A disabled control gets no pointer or focus: the wrapper carries the tooltip.
            <Tooltip>
              <TooltipTrigger asChild>
                <span tabIndex={0} className="min-w-0 flex-1 rounded-md outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50">
                  {trigger}
                </span>
              </TooltipTrigger>
              <TooltipContent className="max-w-72">{props.locked}</TooltipContent>
            </Tooltip>
          ) : (
            <div className="min-w-0 flex-1">{trigger}</div>
          )}
          <SelectContent>
            {series.data?.map((s) => (
              <SelectItem key={s.id} value={s.id}>
                {label(s)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {!disabled && (
          <Button type="button" variant="outline" onClick={() => setCreating((c) => ({ open: true, n: c.n + 1 }))}>
            <Plus />
            {t('events.series.new')}
          </Button>
        )}
      </div>
      {props.hint && <FieldDescription id={`${props.id}-hint`}>{props.hint}</FieldDescription>}
      {props.locked && (
        <FieldDescription id={`${props.id}-locked`} className="sr-only">
          {props.locked}
        </FieldDescription>
      )}
      {series.isError && <FieldDescription className="text-destructive">{t(errorKey(series.error))}</FieldDescription>}
      {props.error && <FieldError id={`${props.id}-error`}>{props.error}</FieldError>}
      <NewSeriesDialog key={creating.n} open={creating.open} onOpenChange={(open) => setCreating((c) => ({ ...c, open }))} onCreated={props.onChange} />
    </Field>
  )
}

const DAY = /^\d{4}-\d{2}-\d{2}$/

/** "Nouvelle série" (nom, du, au) → POST /org/series; the new series is then picked in the select. */
function NewSeriesDialog({ open, onOpenChange, onCreated }: { open: boolean; onOpenChange: (open: boolean) => void; onCreated: (id: string) => void }) {
  const { t } = useTranslation()
  const { organizer_id: organizerId } = useOrganizer()
  const queryClient = useQueryClient()
  const create = useSeriesMutation()
  const schema = z
    .object({
      name: z.string().trim().min(1, t('events.series.nameInvalid')).max(60, t('events.series.nameInvalid')),
      starts_on: z.string().regex(DAY, t('events.series.dateRequired')),
      ends_on: z.string().regex(DAY, t('events.series.dateRequired')),
    })
    // 04: ends_on ≥ starts_on (ISO dates compare as strings).
    .refine((v) => v.ends_on >= v.starts_on, { message: t('events.series.endsBeforeStart'), path: ['ends_on'] })
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { name: '', starts_on: '', ends_on: '' } })
  const { errors } = form.formState
  const submit = form.handleSubmit((body) =>
    create.mutateAsync(body).then(
      (s) => {
        // In the select's list at once, before the refetch the mutation triggers.
        queryClient.setQueryData<Series[]>(['org', organizerId, 'series'], (old) => (old?.some((x) => x.id === s.id) ? old : [...(old ?? []), s]))
        onCreated(s.id)
        toast.success(t('events.series.created', { name: s.name }))
        onOpenChange(false)
      },
      (e: unknown) => showFormError(e, form, t),
    ),
  )
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent closeLabel={t('common.close')}>
        <DialogHeader>
          <DialogTitle>{t('events.series.new')}</DialogTitle>
          <DialogDescription>{t('events.series.dialogDescription')}</DialogDescription>
        </DialogHeader>
        {/* Portalled, but React events still bubble to the page's form: stop the submit here. */}
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.stopPropagation()
            void submit(e)
          }}
        >
          <FieldGroup className="grid gap-4 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <DialogField id="series-name" label={t('events.series.name')} error={errors.name?.message} registration={form.register('name')} />
            </div>
            <DialogField id="series-starts-on" type="date" label={t('events.series.startsOn')} error={errors.starts_on?.message} registration={form.register('starts_on')} />
            <DialogField id="series-ends-on" type="date" label={t('events.series.endsOn')} error={errors.ends_on?.message} registration={form.register('ends_on')} />
          </FieldGroup>
          {errors.root?.message && <FieldError>{errors.root.message}</FieldError>}
          <DialogFooter>
            <DialogClose asChild>
              <Button type="button" variant="outline">
                {t('common.cancel')}
              </Button>
            </DialogClose>
            <Button type="submit" disabled={create.isPending}>
              {t('common.create')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function DialogField(props: { id: string; label: string; type?: 'date'; error?: string; registration: UseFormRegisterReturn }) {
  return (
    <Field data-invalid={!!props.error}>
      <FieldLabel htmlFor={props.id}>{props.label}</FieldLabel>
      <Input id={props.id} type={props.type} aria-invalid={!!props.error} aria-describedby={props.error ? `${props.id}-error` : undefined} {...props.registration} />
      {props.error && <FieldError id={`${props.id}-error`}>{props.error}</FieldError>}
    </Field>
  )
}
