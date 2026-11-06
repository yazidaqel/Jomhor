import { useEffect, useRef, useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { Plus } from 'lucide-react'
import { Controller, useForm, useWatch, type UseFormRegisterReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { FieldError } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useVenue, type VenueDetail, type Zone } from '@/features/venues/api'
import { DeleteButton, DeleteDialog, EditButton } from '@/features/venues/dialogs'
import { useDialog } from '@/features/venues/useDialog'
import { ApiError, path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { integerInput, showFormError } from '@/lib/forms'
import { dhInput, formatDh, parseDh } from '@/lib/money'
import { useEventMutation, usePrices, type EventDetail, type Price } from './api'

/**
 * B2 Tarifs & quotas: the event's price categories, edited inline one row at a time. `onEditingChange` tells the page
 * whether a row is open (publish waits for it).
 */
export function PricesTab({ event, editable, onEditingChange }: { event: EventDetail; editable: boolean; onEditingChange: (open: boolean) => void }) {
  const { t, i18n } = useTranslation()
  const eventId = String(event.id)
  const prices = usePrices(eventId)
  const venue = useVenue(event.venue_id)
  // The row being edited: a price id, 'new' for the add row, or none. A price that is gone after a refetch (deleted
  // elsewhere, NOT_FOUND) closes its row.
  const [editing, setEditing] = useState<string | null>(null)
  const open = editing === 'new' || (!!editing && !!prices.data?.some((p) => p.id === editing)) ? editing : null
  const dialog = useDialog<Price>()
  const heading = useRef<HTMLDivElement>(null)
  useEffect(() => onEditingChange(open !== null), [open, onEditingChange])

  const failed = prices.error ?? venue.error
  if (prices.isPending || venue.isPending) return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  if (!prices.data || !venue.data)
    return (
      <div className="flex items-center gap-3 text-sm">
        <span className="text-destructive">{t(errorKey(failed))}</span>
        <Button variant="outline" size="sm" onClick={() => void (prices.isError ? prices.refetch() : venue.refetch())}>
          {t('common.retry')}
        </Button>
      </div>
    )

  const zones = venue.data.zones
  const used = usage(prices.data)
  const totalQuota = prices.data.reduce((n, p) => n + p.quota, 0)
  const totalAmount = prices.data.reduce((n, p) => n + BigInt(p.quota) * BigInt(p.price_mad), 0n) // centimes, exact
  const done = () => setEditing(null)
  const columns = editable ? 7 : 6

  return (
    <Card>
      <CardHeader>
        <CardTitle ref={heading} tabIndex={-1} role="heading" aria-level={2}>
          {t('events.prices.title')}
        </CardTitle>
        <CardDescription>{t('events.prices.description')}</CardDescription>
        {editable && zones.length > 0 && (
          <CardAction>
            <Button variant="outline" size="sm" disabled={open !== null} onClick={() => setEditing('new')}>
              <Plus />
              {t('events.prices.add')}
            </Button>
          </CardAction>
        )}
      </CardHeader>
      <CardContent>
        {zones.length === 0 && <p className="mb-4 text-sm text-muted-foreground">{t('events.prices.noZones')}</p>}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t('events.prices.zone')}</TableHead>
              <TableHead>{t('events.prices.name')}</TableHead>
              <TableHead className="text-right">{t('events.prices.price')}</TableHead>
              <TableHead className="text-right">{t('events.prices.quota')}</TableHead>
              <TableHead className="text-right">{t('events.prices.sold')}</TableHead>
              <TableHead className="text-right">{t('events.prices.capacity')}</TableHead>
              {editable && <TableHead />}
            </TableRow>
          </TableHeader>
          <TableBody>
            {prices.data.map((p) =>
              open === p.id ? (
                <PriceRowForm key={p.id} eventId={eventId} venue={venue.data} used={used} price={p} onDone={done} />
              ) : (
                <TableRow key={p.id}>
                  <TableCell>{zoneName(zones, p.zone_id)}</TableCell>
                  <TableCell className="font-medium">{p.name}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatDh(p.price_mad, i18n.language)}</TableCell>
                  <TableCell className="text-right tabular-nums">{p.quota.toLocaleString(i18n.language)}</TableCell>
                  <TableCell className="text-right tabular-nums">{p.sold.toLocaleString(i18n.language)}</TableCell>
                  <ZoneUsage zone={zones.find((z) => z.id === p.zone_id)} used={used.get(p.zone_id) ?? 0} />
                  {editable && (
                    <TableCell className="text-right whitespace-nowrap">
                      <EditButton label={t('events.prices.editLabel', { name: p.name })} disabled={open !== null} onClick={() => setEditing(p.id)} />
                      <DeleteButton label={t('events.prices.deleteLabel', { name: p.name })} disabled={open !== null} onClick={() => dialog.remove(p)} />
                    </TableCell>
                  )}
                </TableRow>
              ),
            )}
            {open === 'new' && <PriceRowForm eventId={eventId} venue={venue.data} used={used} onDone={done} />}
            {prices.data.length === 0 && open !== 'new' && (
              <TableRow>
                <TableCell colSpan={columns} className="text-muted-foreground">
                  {t('events.prices.empty')}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
          {prices.data.length > 0 && (
            <TableFooter>
              <TableRow>
                <TableCell colSpan={columns} className="whitespace-normal">
                  {t('events.prices.total', { quota: totalQuota, capacity: venue.data.capacity, amount: formatDh(totalAmount, i18n.language) })}
                </TableCell>
              </TableRow>
            </TableFooter>
          )}
        </Table>
        {editable && (
          <DeleteDialog
            {...dialog.confirm}
            fallbackFocus={heading}
            scope={['events']}
            title={t('events.prices.deleteTitle', { name: dialog.item?.name ?? '' })}
            body={t('events.prices.deleteBody')}
            path={path('org', 'events', eventId, 'prices', dialog.item?.id ?? '')}
          />
        )}
      </CardContent>
    </Card>
  )
}

/** Σ quota per zone, from what the server holds. */
const usage = (prices: Price[]) => prices.reduce((m, p) => m.set(p.zone_id, (m.get(p.zone_id) ?? 0) + p.quota), new Map<number, number>())
const zoneName = (zones: Zone[], id: number) => zones.find((z) => z.id === id)?.name ?? String(id)

function ZoneUsage({ zone, used }: { zone?: Zone; used: number }) {
  const { t } = useTranslation()
  if (!zone) return <TableCell />
  const over = used > zone.capacity
  return (
    <TableCell className={over ? 'text-right text-destructive tabular-nums' : 'text-right text-muted-foreground tabular-nums'}>
      {t('events.prices.zoneUsage', { used, capacity: zone.capacity })}
      {over && <div className="text-xs whitespace-normal">{t('events.prices.zoneOver')}</div>}
    </TableCell>
  )
}

/**
 * One row as inputs: create (`price` undefined, zone chosen here) or edit (zone fixed). The row's <form> sits in the
 * actions cell; the inputs in the other cells join it through their `form` attribute, so Enter submits.
 */
function PriceRowForm({ eventId, venue, used, price, onDone }: { eventId: string; venue: VenueDetail; used: Map<number, number>; price?: Price; onDone: () => void }) {
  const { t, i18n } = useTranslation()
  const formId = `price-${price?.id ?? 'new'}`
  const schema = z.object({
    zone_id: z.string().min(1, t('events.prices.zoneRequired')),
    name: z.string().trim().min(1, t('events.prices.nameRequired')),
    // DH text → integer centimes (string parse, no float).
    price_mad: z
      .string()
      .refine((s) => parseDh(s) !== null, t('events.prices.priceInvalid'))
      .transform((s) => parseDh(s)!),
    quota: integerInput(t('events.prices.quotaInvalid'), 0),
  })
  const form = useForm({
    resolver: zodResolver(schema),
    defaultValues: {
      zone_id: price ? String(price.zone_id) : '',
      name: price?.name ?? '',
      price_mad: price ? dhInput(price.price_mad) : '',
      quota: price ? String(price.quota) : '',
    },
  })
  const save = useEventMutation<Price>()
  const { errors } = form.formState
  const zoneId = useWatch({ control: form.control, name: 'zone_id' })
  const zone = venue.zones.find((z) => String(z.id) === zoneId)
  const prices = path('org', 'events', eventId, 'prices')

  const onError = (e: unknown) => {
    if (e instanceof ApiError && e.code === 'QUOTA_EXCEEDS_CAPACITY')
      return form.setError('quota', { message: t('events.prices.quotaExceedsCapacity', { capacity: zone?.capacity ?? 0 }) }, { shouldFocus: true })
    if (e instanceof ApiError && e.code === 'QUOTA_BELOW_SOLD')
      return form.setError('quota', { message: t('events.prices.quotaBelowSold', { sold: price?.sold ?? 0 }) }, { shouldFocus: true })
    showFormError(e, form, t)
    // A name already used for this zone: 422 on `name` with `details.reason` NAME_TAKEN (04-api). Any other 422 on
    // `name` keeps the generic field copy.
    if (e instanceof ApiError && e.code === 'VALIDATION_FAILED' && e.details.reason === 'NAME_TAKEN')
      form.setError('name', { message: t('events.prices.nameTaken') })
  }
  const submit = form.handleSubmit(({ zone_id, ...v }) => {
    if (!price) return save.mutate({ method: 'POST', path: prices, body: { zone_id: Number(zone_id), ...v } }, { onSuccess: onDone, onError })
    const body = Object.fromEntries(Object.entries(v).filter(([k, x]) => price[k as keyof typeof v] !== x)) // PATCH: changed fields only
    if (Object.keys(body).length === 0) return onDone()
    save.mutate({ method: 'PATCH', path: `${prices}${path(price.id)}`, body }, { onSuccess: onDone, onError })
  })

  return (
    <>
      <TableRow className="align-top hover:bg-transparent">
        <TableCell>
          {price ? (
            zone?.name
          ) : (
            <Controller
              control={form.control}
              name="zone_id"
              render={({ field }) => (
                <>
                  <Select name={field.name} value={field.value} onValueChange={field.onChange} disabled={save.isPending}>
                    <SelectTrigger
                      ref={field.ref}
                      onBlur={field.onBlur}
                      aria-label={t('events.prices.zone')}
                      aria-invalid={!!errors.zone_id}
                      aria-describedby={errors.zone_id ? `${formId}-zone-error` : undefined}
                      className="w-40"
                    >
                      <SelectValue placeholder={t('events.prices.zonePlaceholder')} />
                    </SelectTrigger>
                    <SelectContent>
                      {venue.zones.map((z) => (
                        <SelectItem key={z.id} value={String(z.id)}>
                          {z.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {errors.zone_id && <FieldError id={`${formId}-zone-error`}>{errors.zone_id.message}</FieldError>}
                </>
              )}
            />
          )}
        </TableCell>
        <TableCell>
          <CellInput id={`${formId}-name`} form={formId} label={t('events.prices.name')} error={errors.name?.message} registration={form.register('name')} />
        </TableCell>
        <TableCell>
          <CellInput
            id={`${formId}-price`}
            form={formId}
            label={t('events.prices.price')}
            numeric="decimal"
            error={errors.price_mad?.message}
            registration={form.register('price_mad')}
          />
        </TableCell>
        <TableCell>
          <CellInput
            id={`${formId}-quota`}
            form={formId}
            label={t('events.prices.quota')}
            numeric="numeric"
            error={errors.quota?.message}
            registration={form.register('quota')}
          />
        </TableCell>
        <TableCell className="text-right tabular-nums">{(price?.sold ?? 0).toLocaleString(i18n.language)}</TableCell>
        <ZoneUsage zone={zone} used={used.get(zone?.id ?? -1) ?? 0} />
        <TableCell className="text-right whitespace-nowrap">
          <form id={formId} noValidate onSubmit={(e) => void submit(e)} className="inline-flex gap-2">
            <Button type="button" variant="outline" size="sm" disabled={save.isPending} onClick={onDone}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" size="sm" disabled={save.isPending}>
              {t(price ? 'common.save' : 'common.create')}
            </Button>
          </form>
        </TableCell>
      </TableRow>
      {errors.root?.message && (
        <TableRow className="hover:bg-transparent">
          <TableCell colSpan={7}>
            <FieldError>{errors.root.message}</FieldError>
          </TableCell>
        </TableRow>
      )}
    </>
  )
}

function CellInput(props: { id: string; form: string; label: string; numeric?: 'numeric' | 'decimal'; error?: string; registration: UseFormRegisterReturn }) {
  return (
    <div className="flex min-w-24 flex-col gap-1">
      <Input
        id={props.id}
        form={props.form}
        aria-label={props.label}
        inputMode={props.numeric}
        className={props.numeric ? 'text-right' : undefined}
        aria-invalid={!!props.error}
        aria-describedby={props.error ? `${props.id}-error` : undefined}
        {...props.registration}
      />
      {props.error && (
        <FieldError id={`${props.id}-error`} className="whitespace-normal">
          {props.error}
        </FieldError>
      )}
    </div>
  )
}
