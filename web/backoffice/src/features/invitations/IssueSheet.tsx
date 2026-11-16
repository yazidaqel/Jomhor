import { useRef } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { Send } from 'lucide-react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import type { EventDetail, Price } from '@/features/events/api'
import { holderField } from '@/features/pos/api'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import { deliverySplit, invitationItems, invitationSchema, MAX_PLACES, printPath, remaining, useIssueInvitation, type InvitationOutput, type InvitationValues } from './api'

/** The typed quantity as a place count for the rows and the summary: 0 until it is a whole number in range. */
const places = (qty: string) => (/^\s*\d+\s*$/.test(qty) && Number(qty) >= 1 && Number(qty) <= MAX_PLACES ? Number(qty) : 0)
const holderId = (i: number) => `invite-holder-${i}`

/**
 * "Nouvelle invitation" (B7): one INVITE category, its places, one holder per place as at the till (B11).
 * The form and `invitation_id` live here, outside the sheet's content: closing the sheet keeps both, so a request
 * whose answer was lost is sent again with the same id (the server answers 200 with that invitation, never a second
 * one). A new id only after a success.
 */
export function IssueSheet({ event, categories, open, onOpenChange }: { event: EventDetail; categories: Price[]; open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const issue = useIssueInvitation(String(event.id))
  const invitationId = useRef<string | null>(null)
  const defaults: InvitationValues = { price_category_id: categories.length === 1 ? categories[0]!.id : '', qty: '1', same_name: false, holders: [{ name: '', phone: '' }] }
  const form = useForm<InvitationValues, unknown, InvitationOutput>({
    resolver: zodResolver(invitationSchema(t, categories)),
    mode: 'onTouched',
    defaultValues: defaults,
  })
  const [categoryId, qtyText, sameName, holders] = useWatch({ control: form.control, name: ['price_category_id', 'qty', 'same_name', 'holders'] })
  const category = categories.find((p) => p.id === categoryId)
  const n = places(qtyText)
  const rows = sameName ? Math.min(n, 1) : n
  const split = deliverySplit(n, holders, sameName)
  const { errors, isSubmitting } = form.formState

  // One holder row per place: kept as typed when places are added, the last ones dropped when removed.
  const resize = (count: number) => {
    const current = form.getValues('holders')
    if (count > 0) form.setValue('holders', Array.from({ length: count }, (_, i) => current[i] ?? { name: '', phone: '' }))
  }

  /** The copy for a refusal; a 422 on a field lands on it (and the root says to check the form). */
  const message = (e: unknown, name: string, items: readonly { qty: number }[], same: boolean): string => {
    if (!(e instanceof ApiError)) return t(errorKey(e))
    if (e.code === 'SOLD_OUT') return t('invitations.errors.soldOut', { category: name })
    if (e.code === 'EVENT_NOT_ON_SALE')
      return e.details.reason === 'CLOSED' && typeof e.details.transfer_until === 'string'
        ? t('invitations.errors.closed', { date: formatDateTime(e.details.transfer_until, i18n.language, event.timezone) })
        : t('invitations.errors.notOnSale')
    if (e.code === 'VALIDATION_FAILED') {
      const fields = e.details.fields && typeof e.details.fields === 'object' ? Object.keys(e.details.fields) : []
      // Another order already has this id (never one of ours by chance): the next try draws a new one.
      if (fields.includes('invitation_id')) invitationId.current = null
      const marks = fields.flatMap((f) => {
        const row = holderField(f, items, same)
        return row ? [row] : f === 'items' || /^items\[\d+\]\.qty$/.test(f) ? (['qty'] as const) : /^items\[\d+\]\.price_category_id$/.test(f) ? (['price_category_id'] as const) : []
      })
      marks.forEach((f, i) => form.setError(f, { message: t('common.fieldInvalid') }, { shouldFocus: i === 0 }))
    }
    return t(errorKey(e))
  }

  const submit = async (v: InvitationOutput) => {
    const price = categories.find((p) => p.id === v.price_category_id)!
    const items = invitationItems(price, v.qty, v.holders, v.same_name)
    invitationId.current ??= crypto.randomUUID()
    try {
      // 201, or 200 for the same id again (an earlier answer lost): both read as issued, with the server's count.
      const order = await issue.mutateAsync({ invitation_id: invitationId.current, items })
      invitationId.current = null
      form.reset(defaults)
      onOpenChange(false)
      const paper = deliverySplit(v.qty, v.holders, v.same_name).paper
      toast.success(t('invitations.sheet.done', { count: order.ticket_ids.length, category: price.name }), {
        id: `invitation-${order.id}`,
        action: paper > 0 ? { label: t('invitations.print.action'), onClick: () => void navigate(printPath(event.id, order.id)) } : undefined,
      })
    } catch (e) {
      form.setError('root', { message: message(e, price.name, items, v.same_name) })
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full gap-0 sm:max-w-xl" closeLabel={t('common.close')}>
        <SheetHeader className="border-b p-5">
          <SheetTitle>{t('invitations.new')}</SheetTitle>
          <SheetDescription>{t('invitations.sheet.description')}</SheetDescription>
        </SheetHeader>
        <form id="invite-form" className="flex min-h-0 flex-1 flex-col" onSubmit={(e) => void form.handleSubmit(submit)(e)} noValidate>
          <div className="flex flex-1 flex-col gap-6 overflow-y-auto p-5">
            <FieldGroup className="grid gap-4 sm:grid-cols-[minmax(0,1fr)_8rem]">
              <Controller
                control={form.control}
                name="price_category_id"
                render={({ field, fieldState }) => (
                  <Field data-invalid={!!fieldState.error}>
                    <FieldLabel htmlFor="invite-category">{t('invitations.sheet.category')}</FieldLabel>
                    <Select name={field.name} value={field.value} onValueChange={field.onChange}>
                      <SelectTrigger
                        id="invite-category"
                        ref={field.ref}
                        onBlur={field.onBlur}
                        className="w-full"
                        aria-invalid={!!fieldState.error}
                        aria-describedby={fieldState.error ? 'invite-category-error' : undefined}
                      >
                        <SelectValue placeholder={t('invitations.sheet.categoryPlaceholder')} />
                      </SelectTrigger>
                      <SelectContent>
                        {categories.map((p) => (
                          <SelectItem key={p.id} value={p.id} disabled={remaining(p) === 0}>
                            <span className="flex min-w-0 items-baseline gap-2">
                              <span className="truncate font-medium">{t('pos.sale.priceName', { zone: p.zone.name, name: p.name })}</span>
                              <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
                                {remaining(p) === 0 ? t('invitations.full') : t('invitations.remaining', { count: remaining(p) })}
                              </span>
                            </span>
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {fieldState.error && <FieldError id="invite-category-error">{fieldState.error.message}</FieldError>}
                  </Field>
                )}
              />
              <Field data-invalid={!!errors.qty}>
                <FieldLabel htmlFor="invite-qty">{t('invitations.sheet.qty')}</FieldLabel>
                <Input
                  id="invite-qty"
                  inputMode="numeric"
                  autoComplete="off"
                  className="tabular-nums"
                  aria-invalid={!!errors.qty}
                  aria-describedby={errors.qty ? 'invite-qty-error invite-qty-hint' : 'invite-qty-hint'}
                  {...form.register('qty', { onChange: (e: { target: { value: string } }) => resize(places(e.target.value)) })}
                />
              </Field>
              <div className="sm:col-span-2">
                {errors.qty && <FieldError id="invite-qty-error">{errors.qty.message}</FieldError>}
                <FieldDescription id="invite-qty-hint">
                  {category ? t('invitations.sheet.qtyHintCategory', { count: remaining(category), max: MAX_PLACES }) : t('invitations.sheet.qtyHint', { max: MAX_PLACES })}
                </FieldDescription>
              </div>
            </FieldGroup>

            <section aria-labelledby="invite-holders" className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h3 id="invite-holders" className="text-sm font-medium">
                    {t('pos.sale.holders')}
                  </h3>
                  <p className="text-xs text-muted-foreground">{t('pos.sale.holdersHint')}</p>
                </div>
                <Controller
                  control={form.control}
                  name="same_name"
                  render={({ field }) => (
                    <Field orientation="horizontal" className="w-auto">
                      <Checkbox id="invite-same-name" checked={field.value} onCheckedChange={(c) => field.onChange(c === true)} />
                      <FieldLabel htmlFor="invite-same-name" className="font-normal">
                        {t('pos.sale.sameName')}
                      </FieldLabel>
                    </Field>
                  )}
                />
              </div>
              {rows === 0 ? (
                <p className="rounded-lg border border-dashed p-4 text-center text-sm text-muted-foreground">{t('invitations.sheet.pickQty')}</p>
              ) : (
                <FieldGroup className="gap-3">
                  {Array.from({ length: rows }, (_, i) => {
                    const err = errors.holders?.[i]
                    const id = holderId(i)
                    return (
                      <fieldset key={i} className="grid gap-3 rounded-lg border p-3 sm:grid-cols-2">
                        <legend className="px-1 text-xs font-medium text-muted-foreground">
                          {sameName ? t('pos.sale.allPlaces') : t('invitations.sheet.place', { n: i + 1 })}
                        </legend>
                        <Field data-invalid={!!err?.name}>
                          <FieldLabel htmlFor={`${id}-name`}>{t('pos.sale.name')}</FieldLabel>
                          <Input
                            id={`${id}-name`}
                            autoComplete="off"
                            dir="auto"
                            aria-invalid={!!err?.name}
                            aria-describedby={err?.name ? `${id}-name-error` : undefined}
                            {...form.register(`holders.${i}.name`)}
                          />
                          {err?.name && <FieldError id={`${id}-name-error`}>{err.name.message}</FieldError>}
                        </Field>
                        <Field data-invalid={!!err?.phone}>
                          <FieldLabel htmlFor={`${id}-phone`}>{t('pos.sale.phone')}</FieldLabel>
                          <Input
                            id={`${id}-phone`}
                            type="tel"
                            inputMode="tel"
                            autoComplete="off"
                            aria-invalid={!!err?.phone}
                            aria-describedby={err?.phone ? `${id}-phone-error invite-paper-only` : 'invite-paper-only'}
                            {...form.register(`holders.${i}.phone`)}
                          />
                          {err?.phone && <FieldError id={`${id}-phone-error`}>{err.phone.message}</FieldError>}
                        </Field>
                      </fieldset>
                    )
                  })}
                  <FieldDescription id="invite-paper-only">{t('invitations.sheet.paperOnly')}</FieldDescription>
                </FieldGroup>
              )}
            </section>
          </div>
          <SheetFooter className="gap-3 border-t bg-muted/30 p-5">
            <p aria-live="polite" className="text-sm text-muted-foreground tabular-nums">
              {t('invitations.sheet.summary', { count: n, whatsapp: split.whatsapp, paper: split.paper })}
            </p>
            {errors.root && (
              <p role="alert" className="text-sm font-medium text-destructive">
                {errors.root.message}
              </p>
            )}
            <Button type="submit" size="lg" disabled={isSubmitting || n === 0}>
              <Send />
              {t('invitations.sheet.submit', { count: n })}
            </Button>
          </SheetFooter>
        </form>
      </SheetContent>
    </Sheet>
  )
}
