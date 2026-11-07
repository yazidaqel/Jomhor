import { zodResolver } from '@hookform/resolvers/zod'
import { useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { showFormError } from '@/lib/forms'
import { formatDh, parseDh } from '@/lib/money'
import { cn } from '@/lib/utils'
import { useCloseSession, useOpenSession, type PosSession } from './api'

type DialogProps = { open: boolean; onOpenChange: (open: boolean) => void }

/** "Ouvrir une caisse": POST /org/pos/sessions `{label}` (1–40). The form mounts on open, so it starts empty. */
export function OpenSessionDialog({ open, onOpenChange }: DialogProps) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{t('pos.open.title')}</DialogTitle>
        </DialogHeader>
        <OpenSessionForm close={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

function OpenSessionForm({ close }: { close: () => void }) {
  const { t } = useTranslation()
  const openSession = useOpenSession()
  const form = useForm({
    resolver: zodResolver(z.object({ label: z.string().trim().min(1, t('pos.open.labelInvalid')).max(40, t('pos.open.labelInvalid')) })),
    defaultValues: { label: '' },
  })
  const error = form.formState.errors.label?.message
  const onSubmit = form.handleSubmit(({ label }) =>
    openSession.mutateAsync(label).then(close, (e: unknown) => showFormError(e, form, t)),
  )
  return (
    <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
      <Field data-invalid={!!error}>
        <FieldLabel htmlFor="pos-open-label">{t('pos.open.label')}</FieldLabel>
        <Input
          id="pos-open-label"
          autoComplete="off"
          aria-invalid={!!error}
          aria-describedby={error ? 'pos-open-label-error pos-open-label-hint' : 'pos-open-label-hint'}
          {...form.register('label')}
        />
        <FieldDescription id="pos-open-label-hint">{t('pos.open.hint')}</FieldDescription>
        {error && <FieldError id="pos-open-label-error">{error}</FieldError>}
      </Field>
      {form.formState.errors.root && <FieldError>{form.formState.errors.root.message}</FieldError>}
      <DialogFooter>
        <DialogClose asChild>
          <Button type="button" variant="outline">
            {t('common.cancel')}
          </Button>
        </DialogClose>
        <Button type="submit" disabled={form.formState.isSubmitting}>
          {t('pos.open.submit')}
        </Button>
      </DialogFooter>
    </form>
  )
}

/**
 * "Clôturer la caisse": the counted cash in DH (→ centimes, integer maths), the gap against what the till collected
 * shown before confirming, then the server's frozen `expected_mad`, `declared_mad` and `variance_mad`.
 * `session` is kept by the caller once the dialog opened: the bar goes back to "Aucune caisse" behind it.
 */
export function CloseSessionDialog({ session, open, onOpenChange }: DialogProps & { session: PosSession | undefined }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>{session && <CloseSessionForm session={session} close={() => onOpenChange(false)} />}</DialogContent>
    </Dialog>
  )
}

function Amounts({ rows }: { rows: { label: string; cents: number | null; gap?: boolean }[] }) {
  const { t, i18n } = useTranslation()
  return (
    <dl className="flex flex-col divide-y rounded-lg border">
      {rows.map(({ label, cents, gap }) => (
        <div
          key={label}
          className={cn('flex items-baseline justify-between gap-3 px-4 py-3', gap && cents !== null && cents !== 0 && 'bg-destructive/10 text-destructive')}
        >
          <dt className="text-sm font-medium">{label}</dt>
          <dd aria-live={gap ? 'polite' : undefined} className="text-lg font-semibold tabular-nums">
            {cents === null ? null : gap && cents === 0 ? t('pos.close.noGap') : t('pos.amount', { amount: formatDh(cents, i18n.language, gap ? 'exceptZero' : 'auto') })}
          </dd>
        </div>
      ))}
    </dl>
  )
}

function CloseSessionForm({ session, close }: { session: PosSession; close: () => void }) {
  const { t } = useTranslation()
  const closeSession = useCloseSession()
  const schema = z.object({ declared: z.string().refine((s) => parseDh(s) !== null, t('pos.close.declaredInvalid')) })
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { declared: '' } })
  const declared = parseDh(useWatch({ control: form.control, name: 'declared' }))
  const error = form.formState.errors.declared?.message
  const done = closeSession.data

  if (done)
    return (
      <>
        <DialogHeader>
          <DialogTitle>{t('pos.close.doneTitle', { label: done.label })}</DialogTitle>
          <DialogDescription>{t('pos.close.doneDescription')}</DialogDescription>
        </DialogHeader>
        <Amounts
          rows={[
            { label: t('pos.close.expected'), cents: done.expected_mad },
            { label: t('pos.close.declaredDone'), cents: done.declared_mad },
            { label: t('pos.close.gap'), cents: done.variance_mad, gap: true },
          ]}
        />
        <DialogFooter>
          <Button onClick={close}>{t('common.close')}</Button>
        </DialogFooter>
      </>
    )

  const onSubmit = form.handleSubmit(({ declared }) =>
    closeSession.mutateAsync({ id: session.id, declared_mad: parseDh(declared)! }).catch((e: unknown) => showFormError(e, form, t)),
  )
  return (
    <>
      <DialogHeader>
        <DialogTitle>{t('pos.close.title', { label: session.label })}</DialogTitle>
        <DialogDescription>{t('pos.close.description')}</DialogDescription>
      </DialogHeader>
      <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field data-invalid={!!error}>
          <FieldLabel htmlFor="pos-declared">{t('pos.close.declared')}</FieldLabel>
          <Input
            id="pos-declared"
            inputMode="decimal"
            autoComplete="off"
            className="h-11 text-lg tabular-nums"
            aria-invalid={!!error}
            aria-describedby={error ? 'pos-declared-error' : undefined}
            {...form.register('declared')}
          />
          {error && <FieldError id="pos-declared-error">{error}</FieldError>}
        </Field>
        <Amounts
          rows={[
            { label: t('pos.close.expected'), cents: session.collected_mad },
            { label: t('pos.close.gap'), cents: declared === null ? null : declared - session.collected_mad, gap: true },
          ]}
        />
        {form.formState.errors.root && <FieldError>{form.formState.errors.root.message}</FieldError>}
        <DialogFooter>
          <DialogClose asChild>
            <Button type="button" variant="outline">
              {t('common.cancel')}
            </Button>
          </DialogClose>
          <Button type="submit" disabled={form.formState.isSubmitting}>
            {t('pos.close.submit')}
          </Button>
        </DialogFooter>
      </form>
    </>
  )
}
