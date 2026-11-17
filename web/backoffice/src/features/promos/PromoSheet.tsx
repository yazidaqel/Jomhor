import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { Controller, useForm, useWatch, type UseFormRegisterReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { ErrorNotice, LoadingRows } from '@/components/page'
import { Button } from '@/components/ui/button'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { usePrices, type Price } from '@/features/events/api'
import { ApiError } from '@/lib/api'
import { showFormError } from '@/lib/forms'
import { cn } from '@/lib/utils'
import {
  createBody,
  patchBody,
  PROMO_KINDS,
  promoSchema,
  scopeCategories,
  toForm,
  usePromoMutation,
  WHOLE_EVENT,
  type PromoCode,
  type PromoForm,
  type PromoValues,
} from './api'

/** "Nouveau code" (no `code`) or the edit sheet of one code (its caps only). Mounted per opening (keyed by the caller). */
export function PromoSheet(props: { eventId: string; code?: PromoCode; open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation()
  const { code } = props
  const prices = usePrices(props.eventId)
  const close = () => props.onOpenChange(false)
  return (
    <Sheet open={props.open} onOpenChange={props.onOpenChange}>
      <SheetContent className="w-full gap-0 overflow-y-auto sm:max-w-lg" closeLabel={t('common.close')}>
        <SheetHeader className="border-b p-5">
          <SheetTitle>{code ? t('promos.editTitle', { code: code.code }) : t('promos.newTitle')}</SheetTitle>
          <SheetDescription>{t(code ? 'promos.editDescription' : 'promos.newDescription')}</SheetDescription>
        </SheetHeader>
        <div className="p-5">
          {/* The scope select needs the categories; an existing code shows its own. */}
          {!code && prices.isPending ? (
            <LoadingRows />
          ) : !code && prices.isError ? (
            <ErrorNotice error={prices.error} onRetry={() => void prices.refetch()} className="p-0" />
          ) : (
            <PromoFormBody eventId={props.eventId} code={code} categories={scopeCategories(prices.data ?? [])} onDone={close} />
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}

function PromoFormBody({ eventId, code, categories, onDone }: { eventId: string; code?: PromoCode; categories: Price[]; onDone: () => void }) {
  const { t } = useTranslation()
  const save = usePromoMutation(eventId)
  const [initial] = useState(() => toForm(code))
  const form = useForm<PromoForm, unknown, PromoValues>({ resolver: zodResolver(promoSchema(t, code)), defaultValues: initial })
  const { errors } = form.formState
  const kind = useWatch({ control: form.control, name: 'kind' })
  const fixed = code ? t('promos.form.fixed') : undefined

  const onError = (e: unknown) => {
    showFormError(e, form, t)
    // 04: the code is already used in this event.
    if (e instanceof ApiError && e.code === 'VALIDATION_FAILED' && e.details.reason === 'TAKEN' && Object.hasOwn(Object(e.details.fields), 'code'))
      form.setError('code', { message: t('promos.form.codeTaken') }, { shouldFocus: true })
  }
  const submit = form.handleSubmit((v) => {
    if (!code)
      return save.mutate(
        { method: 'POST', body: createBody(v) },
        {
          onSuccess: (row) => {
            toast.success(t('promos.created', { code: row.code }))
            onDone()
          },
          onError,
        },
      )
    const body = patchBody(code, v)
    if (Object.keys(body).length === 0) return onDone()
    save.mutate(
      { method: 'PATCH', promoId: code.id, body },
      {
        onSuccess: (row) => {
          toast.success(t('promos.saved', { code: row.code }), { id: `promo-${row.id}` })
          onDone()
        },
        onError,
      },
    )
  })

  return (
    <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-6">
      <FieldGroup className="grid gap-5 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <TextField
            id="promo-code"
            label={t('promos.form.code')}
            hint={fixed ?? t('promos.form.codeHint')}
            placeholder={t('promos.form.codePlaceholder')}
            className="font-mono tracking-wide uppercase placeholder:font-sans placeholder:tracking-normal placeholder:normal-case"
            maxLength={20}
            readOnly={!!code}
            error={errors.code?.message}
            registration={form.register('code')}
          />
        </div>
        <Controller
          control={form.control}
          name="kind"
          render={({ field }) => (
            <Field>
              <FieldLabel htmlFor="promo-kind">{t('promos.form.kind')}</FieldLabel>
              <Select name={field.name} value={field.value} onValueChange={field.onChange} disabled={!!code}>
                <SelectTrigger id="promo-kind" ref={field.ref} onBlur={field.onBlur} className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PROMO_KINDS.map((k) => (
                    <SelectItem key={k} value={k}>
                      {t(`promos.kinds.${k}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
          )}
        />
        {kind === 'PERCENT' ? (
          <TextField
            key="percent"
            id="promo-percent"
            label={t('promos.form.percent')}
            inputMode="numeric"
            hint={fixed ?? t('promos.form.percentHint')}
            readOnly={!!code}
            error={errors.percent?.message}
            registration={form.register('percent')}
          />
        ) : (
          <TextField
            key="amount"
            id="promo-amount"
            label={t('promos.form.amount')}
            inputMode="decimal"
            hint={fixed ?? t('promos.form.amountHint')}
            readOnly={!!code}
            error={errors.amount_mad?.message}
            registration={form.register('amount_mad')}
          />
        )}
        <Controller
          control={form.control}
          name="price_category_id"
          render={({ field }) => (
            <Field data-invalid={!!errors.price_category_id} className="sm:col-span-2">
              <FieldLabel htmlFor="promo-scope">{t('promos.form.scope')}</FieldLabel>
              <Select name={field.name} value={field.value} onValueChange={field.onChange} disabled={!!code}>
                <SelectTrigger
                  id="promo-scope"
                  ref={field.ref}
                  onBlur={field.onBlur}
                  className="w-full"
                  aria-invalid={!!errors.price_category_id}
                  aria-describedby={['promo-scope-hint', errors.price_category_id && 'promo-scope-error'].filter(Boolean).join(' ')}
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={WHOLE_EVENT}>{t('promos.wholeEvent')}</SelectItem>
                  {/* An existing code keeps its category even if it is no longer offered. */}
                  {code?.price_category && !categories.some((p) => p.id === code.price_category!.id) && (
                    <SelectItem value={code.price_category.id}>{t('promos.scopeCategory', { name: code.price_category.name, zone: code.price_category.zone.name })}</SelectItem>
                  )}
                  {categories.map((p) => (
                    <SelectItem key={p.id} value={p.id}>
                      {t('promos.scopeCategory', { name: p.name, zone: p.zone.name })}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FieldDescription id="promo-scope-hint">{fixed ?? t('promos.form.scopeHint')}</FieldDescription>
              {errors.price_category_id && <FieldError id="promo-scope-error">{errors.price_category_id.message}</FieldError>}
            </Field>
          )}
        />
        <TextField
          id="promo-max-uses"
          label={t('promos.form.maxUses')}
          inputMode="numeric"
          hint={t(code?.max_uses != null ? 'promos.form.maxUsesKeptHint' : 'promos.form.maxUsesHint')}
          error={errors.max_uses?.message}
          registration={form.register('max_uses')}
        />
        <TextField
          id="promo-per-account"
          label={t('promos.form.perAccount')}
          inputMode="numeric"
          hint={t('promos.form.perAccountHint')}
          error={errors.per_account_max?.message}
          registration={form.register('per_account_max')}
        />
      </FieldGroup>
      {errors.root?.message && <FieldError>{errors.root.message}</FieldError>}
      <SheetFooter className="flex-row justify-end gap-2 p-0">
        <Button type="button" variant="outline" onClick={onDone}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={save.isPending}>
          {t(code ? 'common.save' : 'promos.form.create')}
        </Button>
      </SheetFooter>
    </form>
  )
}

function TextField(props: {
  id: string
  label: string
  inputMode?: 'decimal' | 'numeric'
  hint?: string
  placeholder?: string
  className?: string
  maxLength?: number
  readOnly?: boolean
  error?: string
  registration: UseFormRegisterReturn
}) {
  const described = [props.hint && `${props.id}-hint`, props.error && `${props.id}-error`].filter(Boolean).join(' ')
  return (
    <Field data-invalid={!!props.error}>
      <FieldLabel htmlFor={props.id}>{props.label}</FieldLabel>
      <Input
        id={props.id}
        inputMode={props.inputMode}
        placeholder={props.placeholder}
        maxLength={props.maxLength}
        readOnly={props.readOnly}
        className={cn(props.inputMode && 'tabular-nums', props.readOnly && 'bg-muted/50', props.className)}
        aria-invalid={!!props.error}
        aria-describedby={described || undefined}
        {...props.registration}
      />
      {props.hint && <FieldDescription id={`${props.id}-hint`}>{props.hint}</FieldDescription>}
      {props.error && <FieldError id={`${props.id}-error`}>{props.error}</FieldError>}
    </Field>
  )
}
