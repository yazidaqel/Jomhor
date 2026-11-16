import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { TriangleAlert } from 'lucide-react'
import { Controller, useForm, useWatch, type UseFormRegisterReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { ErrorNotice, LoadingRows } from '@/components/page'
import { Button } from '@/components/ui/button'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Switch } from '@/components/ui/switch'
import { SeriesField } from '@/features/events/SeriesField'
import { ApiError, path } from '@/lib/api'
import { showFormError } from '@/lib/forms'
import { DEFAULT_ZONE, offsetLabel, wallToUtc } from '@/lib/time'
import {
  createBody,
  patchBody,
  productSchema,
  productZone,
  toForm,
  useProductMutation,
  useVenueZones,
  type Product,
  type ProductForm,
  type ProductKind,
  type ProductValues,
} from './api'

/**
 * B14 "Nouveau" (no `product`) or "Modifier": the PASS or MEMBERSHIP form in a sheet. Mounted per opening (keyed by
 * the caller). `firstMembership`: no MEMBERSHIP product exists yet, so creating one makes membership paid (Q189).
 */
export function ProductSheet(props: { kind: ProductKind; product?: Product; firstMembership?: boolean; open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation()
  const { kind, product } = props
  // Only a new PASS picks its zone; an existing product carries its zone and venue time zone.
  const picking = kind === 'PASS' && !product
  const zones = useVenueZones(picking && props.open) // not while the sheet is closed
  const close = () => props.onOpenChange(false)
  return (
    <Sheet open={props.open} onOpenChange={props.onOpenChange}>
      <SheetContent className="w-full gap-0 overflow-y-auto sm:max-w-xl" closeLabel={t('common.close')}>
        <SheetHeader className="border-b p-5">
          <SheetTitle>{product ? t('products.editTitle', { name: product.name }) : t(`products.newTitle.${kind}`)}</SheetTitle>
          <SheetDescription>{t(`products.sheetDescription.${kind}`)}</SheetDescription>
        </SheetHeader>
        <div className="p-5">
          {/* A new PASS's times are in the picked zone's venue's time zone: wait for the venues. */}
          {picking && zones.isPending ? (
            <LoadingRows />
          ) : picking && zones.error ? (
            <ErrorNotice error={zones.error} onRetry={zones.refetch} className="p-0" />
          ) : (
            <ProductFormBody kind={kind} product={product} firstMembership={!!props.firstMembership} zones={zones} onDone={close} />
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}

function ProductFormBody({
  kind,
  product,
  firstMembership,
  zones,
  onDone,
}: {
  kind: ProductKind
  product?: Product
  firstMembership: boolean
  zones: ReturnType<typeof useVenueZones>
  onDone: () => void
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const save = useProductMutation()
  const zoneOf = (zoneId: string) => (product ? productZone(product) : kind === 'MEMBERSHIP' ? DEFAULT_ZONE : zones.venueOf(zoneId)?.timezone)
  // The baseline of "changed fields", frozen as the sheet opened: a refetch of the product never moves it.
  const [initial] = useState(() => toForm(product))
  const form = useForm<ProductForm, unknown, ProductValues>({
    resolver: (values, ctx, options) =>
      zodResolver(productSchema(t, kind, zoneOf(values.zone_id), product ? initial.sales_close_at : undefined))(values, ctx, options),
    defaultValues: initial,
  })
  const { errors } = form.formState
  const zoneId = useWatch({ control: form.control, name: 'zone_id' })
  const closeAt = useWatch({ control: form.control, name: 'sales_close_at' })
  const zone = zoneOf(zoneId)
  const [now] = useState(() => Date.now())
  const fixed = product ? t('products.form.fixed') : undefined

  const onError = (e: unknown) => {
    if (product && e instanceof ApiError && e.code === 'NOT_FOUND') {
      toast.error(t('products.gone'))
      onDone()
      return void navigate('/products', { replace: true })
    }
    // 04: quota ≥ sold.
    if (product && e instanceof ApiError && e.code === 'QUOTA_BELOW_SOLD')
      return form.setError('quota', { message: t('products.form.quotaBelowSold', { sold: product.sold }) }, { shouldFocus: true })
    showFormError(e, form, t)
  }
  const submit = form.handleSubmit((v) => {
    if (!zone) return form.setError('zone_id', { message: t('products.form.zoneRequired') })
    if (!product)
      return save.mutate(
        { method: 'POST', path: '/org/products', body: createBody(kind, v, zone) },
        {
          onSuccess: (p) => {
            toast.success(t('products.created', { name: p.name }))
            onDone()
            void navigate(`/products/${encodeURIComponent(p.id)}`)
          },
          onError,
        },
      )
    const body = patchBody(kind, initial, v, form.getValues(), zone)
    if (Object.keys(body).length === 0) return onDone()
    save.mutate(
      { method: 'PATCH', path: path('org', 'products', product.id), body },
      {
        onSuccess: () => {
          toast.success(t('products.saved'), { id: 'product-saved' })
          onDone()
        },
        onError,
      },
    )
  })

  const closeHint = zone
    ? t('events.form.zoneHint', { zone, offset: offsetLabel(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(closeAt) ? Date.parse(wallToUtc(closeAt, zone)) : now, zone) })
    : t('products.form.zoneFirst')

  return (
    <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-6">
      {kind === 'MEMBERSHIP' && !product && firstMembership && (
        <div role="note" className="flex gap-3 rounded-lg border border-warning-border bg-warning-muted p-4 text-sm text-warning-foreground">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <div className="flex flex-col gap-1">
            <p className="font-semibold">{t('products.membershipWarning.title')}</p>
            <p>{t('products.membershipWarning.body')}</p>
          </div>
        </div>
      )}
      <FieldGroup className="grid gap-5 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <TextField id="product-name" label={t('products.form.name')} error={errors.name?.message} registration={form.register('name')} />
        </div>
        {kind === 'PASS' && (
          <>
            <Controller
              control={form.control}
              name="series_id"
              render={({ field }) => (
                <div className="sm:col-span-2">
                  <SeriesField
                    id="product-series"
                    value={field.value}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    fieldRef={field.ref}
                    locked={fixed}
                    hint={t('products.form.seriesHint')}
                    error={errors.series_id?.message}
                  />
                </div>
              )}
            />
            <Controller
              control={form.control}
              name="zone_id"
              render={({ field }) => (
                <Field data-invalid={!!errors.zone_id} className="sm:col-span-2">
                  <FieldLabel htmlFor="product-zone">{t('products.form.zone')}</FieldLabel>
                  <Select name={field.name} value={field.value} onValueChange={field.onChange} disabled={!!product}>
                    <SelectTrigger
                      id="product-zone"
                      ref={field.ref}
                      onBlur={field.onBlur}
                      className="w-full"
                      aria-invalid={!!errors.zone_id}
                      aria-describedby={['product-zone-hint', errors.zone_id && 'product-zone-error'].filter(Boolean).join(' ')}
                    >
                      <SelectValue placeholder={t('products.form.zonePlaceholder')} />
                    </SelectTrigger>
                    <SelectContent>
                      {product?.zone && (
                        <SelectItem value={String(product.zone.id)}>{t('products.form.zoneFixedOption', { name: product.zone.name, venue: product.zone.venue.name })}</SelectItem>
                      )}
                      {/* DESIGN B14: zones grouped by venue. */}
                      {zones.venues.map((v) => (
                        <SelectGroup key={v.id}>
                          <SelectLabel>{v.name}</SelectLabel>
                          {v.zones.map((z) => (
                            <SelectItem key={z.id} value={String(z.id)}>
                              {t('products.form.zoneOption', { name: z.name, capacity: z.capacity })}
                            </SelectItem>
                          ))}
                        </SelectGroup>
                      ))}
                    </SelectContent>
                  </Select>
                  <FieldDescription id="product-zone-hint">{fixed ?? t('products.form.zoneHint')}</FieldDescription>
                  {errors.zone_id && <FieldError id="product-zone-error">{errors.zone_id.message}</FieldError>}
                </Field>
              )}
            />
          </>
        )}
        <TextField id="product-price" label={t('products.form.price')} inputMode="decimal" hint={t('products.form.priceHint')} error={errors.price_mad?.message} registration={form.register('price_mad')} />
        {kind === 'PASS' ? (
          <>
            <TextField id="product-quota" label={t('products.form.quota')} inputMode="numeric" hint={t('products.form.quotaHint')} error={errors.quota?.message} registration={form.register('quota')} />
            <div className="sm:col-span-2">
              <TextField
                id="product-installments"
                label={t('products.form.installments')}
                inputMode="numeric"
                hint={t('products.form.installmentsHint')}
                error={errors.max_installments?.message}
                registration={form.register('max_installments')}
              />
            </div>
          </>
        ) : (
          <TextField
            id="product-valid-until"
            type="date"
            label={t('products.form.validUntil')}
            hint={t('products.form.validUntilHint', { zone: DEFAULT_ZONE })}
            error={errors.valid_until?.message}
            registration={form.register('valid_until')}
          />
        )}
        <div className="sm:col-span-2">
          <TextField
            id="product-close"
            type="datetime-local"
            label={t('products.form.salesCloseAt')}
            hint={closeHint}
            error={errors.sales_close_at?.message}
            registration={form.register('sales_close_at')}
          />
        </div>
        <Controller
          control={form.control}
          name="on_sale"
          render={({ field }) => (
            <Field orientation="horizontal" className="rounded-lg border p-4 sm:col-span-2">
              <Switch id="product-on-sale" ref={field.ref} checked={field.value} onCheckedChange={field.onChange} onBlur={field.onBlur} aria-describedby="product-on-sale-hint" />
              <div className="flex flex-col gap-1">
                <FieldLabel htmlFor="product-on-sale">{t('products.form.onSale')}</FieldLabel>
                <FieldDescription id="product-on-sale-hint">{t(`products.form.onSaleHint.${kind}`)}</FieldDescription>
              </div>
            </Field>
          )}
        />
      </FieldGroup>
      {errors.root?.message && <FieldError>{errors.root.message}</FieldError>}
      <SheetFooter className="flex-row justify-end gap-2 p-0">
        <Button type="button" variant="outline" onClick={onDone}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={save.isPending}>
          {t(product ? 'common.save' : 'common.create')}
        </Button>
      </SheetFooter>
    </form>
  )
}

function TextField(props: {
  id: string
  label: string
  type?: 'date' | 'datetime-local'
  inputMode?: 'decimal' | 'numeric'
  hint?: string
  error?: string
  registration: UseFormRegisterReturn
}) {
  const described = [props.hint && `${props.id}-hint`, props.error && `${props.id}-error`].filter(Boolean).join(' ')
  return (
    <Field data-invalid={!!props.error}>
      <FieldLabel htmlFor={props.id}>{props.label}</FieldLabel>
      <Input
        id={props.id}
        type={props.type}
        inputMode={props.inputMode}
        className={props.inputMode ? 'tabular-nums' : undefined}
        aria-invalid={!!props.error}
        aria-describedby={described || undefined}
        {...props.registration}
      />
      {props.hint && <FieldDescription id={`${props.id}-hint`}>{props.hint}</FieldDescription>}
      {props.error && <FieldError id={`${props.id}-error`}>{props.error}</FieldError>}
    </Field>
  )
}
