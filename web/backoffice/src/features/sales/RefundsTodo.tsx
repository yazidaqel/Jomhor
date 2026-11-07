import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { z } from 'zod'
import { ErrorNotice, LoadingRows } from '@/components/page'
import { Button } from '@/components/ui/button'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useOrganizer, type Role } from '@/features/auth/organizer'
import { showFormError } from '@/lib/forms'
import { formatDh } from '@/lib/money'
import { formatDateTime } from '@/lib/time'
import { useRefundDone, useRefunds, type Refund } from './api'

/** "Marquer remboursé": ADMIN and FINANCE (04-api role sentence); OPS sees the list only. */
const DONE_ROLES: readonly Role[] = ['ADMIN', 'FINANCE']

/** B3 "Remboursements à faire" › Billets remboursés: `GET /org/refunds` REQUIRED rows of the event (Lot 3, Q52). */
export function RefundsTodo({ eventId, zone }: { eventId: string; zone: string }) {
  const { t, i18n } = useTranslation()
  const canMark = DONE_ROLES.includes(useOrganizer().role)
  const refunds = useRefunds(eventId)
  // `n` counts openings: the dialog's key, so each opening starts with an empty form and a fresh mutation.
  const [marking, setMarking] = useState<{ refund: Refund; open: boolean; n: number } | null>(null)
  const dh = (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) })
  const method = (r: Refund) => (r.method ? t(`sales.methods.${r.method}`) : t('sales.refunds.tickets.noMethod'))

  if (refunds.isPending) return <LoadingRows rows={2} />
  if (refunds.isError) return <ErrorNotice error={refunds.error} onRetry={() => void refunds.refetch()} className="px-0" />
  if (refunds.data.length === 0) return <p className="text-sm text-muted-foreground">{t('sales.refunds.tickets.empty')}</p>
  return (
    <>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t('sales.refunds.at')}</TableHead>
            <TableHead>{t('sales.refunds.tickets.ticket')}</TableHead>
            <TableHead>{t('sales.refunds.tickets.reason')}</TableHead>
            <TableHead>{t('sales.refunds.tickets.method')}</TableHead>
            <TableHead>{t('sales.refunds.tickets.requestedBy')}</TableHead>
            <TableHead className="text-right">{t('sales.refunds.amount')}</TableHead>
            {canMark && (
              <TableHead className="text-right">
                <span className="sr-only">{t('sales.refunds.tickets.actions')}</span>
              </TableHead>
            )}
          </TableRow>
        </TableHeader>
        <TableBody>
          {refunds.data.map((r) => (
            <TableRow key={r.id}>
              <TableCell className="tabular-nums">{formatDateTime(r.requested_at, i18n.language, zone)}</TableCell>
              <TableCell className="font-mono text-xs">{r.ticket_no}</TableCell>
              <TableCell className="max-w-64 whitespace-normal">{r.reason}</TableCell>
              <TableCell>{method(r)}</TableCell>
              <TableCell>{r.requested_by.name}</TableCell>
              <TableCell className="text-right tabular-nums">{dh(r.amount_mad)}</TableCell>
              {canMark && (
                <TableCell className="text-right">
                  <Button
                    size="sm"
                    variant="outline"
                    aria-label={t('sales.refunds.tickets.markDoneLabel', { ticket: r.ticket_no })}
                    onClick={() => setMarking((m) => ({ refund: r, open: true, n: (m?.n ?? 0) + 1 }))}
                  >
                    {t('sales.refunds.tickets.markDone')}
                  </Button>
                </TableCell>
              )}
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {marking && (
        <DoneDialog
          key={marking.n}
          eventId={eventId}
          refund={marking.refund}
          method={method(marking.refund)}
          open={marking.open}
          onOpenChange={(open) => setMarking((m) => m && { ...m, open })}
        />
      )}
    </>
  )
}

/** The money went back by hand: how (`reference`, 1–100, required by the API) → `POST /org/refunds/{id}/done`. */
function DoneDialog(props: { eventId: string; refund: Refund; method: string; open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t, i18n } = useTranslation()
  const { refund } = props
  const done = useRefundDone(props.eventId)
  const invalid = t('sales.refunds.tickets.done.referenceInvalid')
  const form = useForm({
    resolver: zodResolver(z.object({ reference: z.string().trim().min(1, invalid).max(100, invalid) })),
    defaultValues: { reference: '' },
  })
  const error = form.formState.errors.reference?.message
  const vars = { ticket: refund.ticket_no }
  const onSubmit = form.handleSubmit(({ reference }) =>
    done.mutateAsync({ id: refund.id, reference }).then(
      () => {
        toast.success(t('sales.refunds.tickets.done.toast', vars))
        props.onOpenChange(false)
      },
      (e: unknown) => showFormError(e, form, t),
    ),
  )
  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      <DialogContent closeLabel={t('common.close')}>
        <DialogHeader>
          <DialogTitle>{t('sales.refunds.tickets.done.title', vars)}</DialogTitle>
          <DialogDescription>
            {t('sales.refunds.tickets.done.description', { amount: formatDh(refund.amount_mad, i18n.language), method: props.method })}
          </DialogDescription>
        </DialogHeader>
        <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
          <Field data-invalid={!!error}>
            <FieldLabel htmlFor="refund-reference">{t('sales.refunds.tickets.done.reference')}</FieldLabel>
            <Input
              id="refund-reference"
              autoComplete="off"
              aria-invalid={!!error}
              aria-describedby={error ? 'refund-reference-error refund-reference-hint' : 'refund-reference-hint'}
              {...form.register('reference')}
            />
            <FieldDescription id="refund-reference-hint">{t('sales.refunds.tickets.done.hint')}</FieldDescription>
            {error && <FieldError id="refund-reference-error">{error}</FieldError>}
          </Field>
          {form.formState.errors.root && <FieldError>{form.formState.errors.root.message}</FieldError>}
          <DialogFooter>
            <DialogClose asChild>
              <Button type="button" variant="outline">
                {t('common.cancel')}
              </Button>
            </DialogClose>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {t('sales.refunds.tickets.done.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
