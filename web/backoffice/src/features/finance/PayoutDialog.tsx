import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'
import { formatDh } from '@/lib/money'
import { formatDay } from '@/lib/time'
import { usePayoutReceived, type Settlement } from './api'

/**
 * "Versement reçu" (ADMIN, FINANCE; `DUE` only): the reference of the bank transfer received, 1–100 →
 * `POST /org/finance/settlements/{id}/paid`. `SETTLEMENT_STATUS` (nothing to pay any more) closes with a toast.
 */
export function PayoutDialog({ settlement: s, open, onOpenChange }: { settlement: Settlement; open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t, i18n } = useTranslation()
  const paid = usePayoutReceived()
  const invalid = t('finance.settlements.payout.referenceInvalid')
  const form = useForm({ resolver: zodResolver(z.object({ reference: z.string().trim().min(1, invalid).max(100, invalid) })), defaultValues: { reference: '' } })
  const error = form.formState.errors.reference?.message
  const vars = { day: formatDay(s.day, i18n.language), amount: formatDh(s.payout_mad, i18n.language) }
  const onSubmit = form.handleSubmit(({ reference }) =>
    paid.mutateAsync({ id: s.id, reference }).then(
      () => {
        toast.success(t('finance.settlements.payout.done', vars), { id: `payout-${s.id}` })
        onOpenChange(false)
      },
      (e: unknown) => {
        if (e instanceof ApiError && e.code === 'SETTLEMENT_STATUS') {
          toast.error(t(errorKey(e)), { id: `payout-${s.id}` })
          onOpenChange(false)
        } else showFormError(e, form, t)
      },
    ),
  )
  const describedBy = ['payout-reference-hint', error && 'payout-reference-error'].filter(Boolean).join(' ')
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent closeLabel={t('common.close')}>
        <DialogHeader>
          <DialogTitle>{t('finance.settlements.payout.title', vars)}</DialogTitle>
          <DialogDescription>{t('finance.settlements.payout.description', vars)}</DialogDescription>
        </DialogHeader>
        <form className="flex flex-col gap-4" onSubmit={(e) => void onSubmit(e)} noValidate>
          <Field data-invalid={!!error}>
            <FieldLabel htmlFor="payout-reference">{t('finance.settlements.payout.reference')}</FieldLabel>
            <Input id="payout-reference" autoComplete="off" aria-invalid={!!error} aria-describedby={describedBy} {...form.register('reference')} />
            <FieldDescription id="payout-reference-hint">{t('finance.settlements.payout.referenceHint')}</FieldDescription>
            {error && <FieldError id="payout-reference-error">{error}</FieldError>}
          </Field>
          {form.formState.errors.root && <FieldError>{form.formState.errors.root.message}</FieldError>}
          <DialogFooter>
            <DialogClose asChild>
              <Button type="button" variant="outline">
                {t('common.cancel')}
              </Button>
            </DialogClose>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {t('finance.settlements.payout.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
