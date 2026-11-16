import { Fragment, useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { ChevronRight, MessageCircle, Printer, Snowflake } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { z } from 'zod'
import { BrandEmpty, ErrorNotice, LoadingRows, STICKY_TABLE } from '@/components/page'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCanWrite, useOrganizer } from '@/features/auth/organizer'
import type { EventDetail } from '@/features/events/api'
import { ACTION_ROLES, useTicketAction } from '@/features/tickets/api'
import { TicketStatusBadge } from '@/features/tickets/TicketsPage'
import { showFormError } from '@/lib/forms'
import { formatDateTime } from '@/lib/time'
import { useNow } from '@/lib/useNow'
import { cn } from '@/lib/utils'
import { printPath, useInvitations, type Delivery, type Invitation, type InvitationTicket } from './api'

const FREEZE_ID = 'invitations-freeze'
/** 04 refund: VALID or BLOCKED → REFUNDED (else TICKET_STATUS). */
const revocable = (tk: InvitationTicket) => tk.status === 'VALID' || tk.status === 'BLOCKED'
const count = (inv: Invitation, d: Delivery) => inv.tickets.filter((tk) => tk.delivery === d).length

/**
 * The event's invitation batches (each an order, 04 Q211), newest first: date in the venue zone, who issued it, its
 * categories, places and how they go out; a row opens on its holders. "Imprimer" when a paper ticket is still VALID
 * (ADMIN, OPS); "Révoquer" per ticket (ADMIN, the refund), off from the refund cutoff.
 */
export function Batches({ event }: { event: EventDetail }) {
  const { t, i18n } = useTranslation()
  const id = String(event.id)
  const list = useInvitations(id)
  const canPrint = useCanWrite()
  const canRevoke = ACTION_ROLES.refund.includes(useOrganizer().role)
  // ponytail: browser clock, re-read every 30 s; the server enforces the freeze (409 REFUND_FROZEN) either way.
  const now = useNow(30_000)
  const frozen = event.refund_cutoff_at !== null && now >= Date.parse(event.refund_cutoff_at)
  const [openRows, setOpenRows] = useState<ReadonlySet<string>>(new Set())
  const [revoking, setRevoking] = useState<{ ticket: InvitationTicket; open: boolean; n: number } | null>(null)
  const toggle = (rowId: string) =>
    setOpenRows((s) => {
      const next = new Set(s)
      if (!next.delete(rowId)) next.add(rowId)
      return next
    })

  return (
    <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
      <CardHeader className="border-b pb-5">
        <CardTitle role="heading" aria-level={2}>
          {t('invitations.batches.title')}
        </CardTitle>
        {canRevoke && frozen ? (
          <CardDescription id={FREEZE_ID} className="flex items-center gap-2 font-medium text-warning-foreground">
            <Snowflake className="size-4 shrink-0" aria-hidden="true" />
            {t('invitations.batches.frozen', { date: formatDateTime(event.refund_cutoff_at!, i18n.language, event.timezone) })}
          </CardDescription>
        ) : (
          <CardDescription>{t('invitations.batches.description')}</CardDescription>
        )}
      </CardHeader>
      <CardContent className="p-0">
        {list.isPending ? (
          <LoadingRows className="p-5" />
        ) : list.isError ? (
          <ErrorNotice error={list.error} onRetry={() => void list.refetch()} />
        ) : list.data.invitations.length === 0 ? (
          <BrandEmpty title={t('invitations.batches.emptyTitle')} description={t('invitations.batches.empty')} />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('invitations.batches.date')}</TableHead>
                <TableHead>{t('invitations.batches.by')}</TableHead>
                <TableHead>{t('invitations.batches.category')}</TableHead>
                <TableHead className="text-right">{t('invitations.batches.places')}</TableHead>
                <TableHead>{t('invitations.batches.delivery')}</TableHead>
                <TableHead className="text-right">
                  <span className="sr-only">{t('invitations.batches.actions')}</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.data.invitations.map((inv) => {
                const expanded = openRows.has(inv.id)
                const panel = inv.id // the holders row's id (aria-controls)
                const date = formatDateTime(inv.created_at, i18n.language, event.timezone)
                const printable = canPrint && inv.tickets.some((tk) => tk.delivery === 'PAPER' && tk.status === 'VALID')
                return (
                  <Fragment key={inv.id}>
                    <TableRow data-state={expanded ? 'selected' : undefined}>
                      <TableCell>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="-ml-2 gap-1.5 px-2 font-medium tabular-nums"
                          aria-expanded={expanded}
                          aria-controls={panel}
                          aria-label={t('invitations.batches.holders', { date })}
                          onClick={() => toggle(inv.id)}
                        >
                          <ChevronRight className={cn('transition-transform duration-200 motion-reduce:transition-none', expanded && 'rotate-90')} aria-hidden="true" />
                          {date}
                        </Button>
                      </TableCell>
                      <TableCell>{inv.invited_by.name}</TableCell>
                      <TableCell className="whitespace-normal">
                        {inv.items.map((it) => (
                          <div key={it.price_category_id}>
                            <span className="font-medium">{it.name}</span>
                            <span className="text-muted-foreground"> · {it.zone.name}</span>
                          </div>
                        ))}
                      </TableCell>
                      <TableCell className="text-right font-medium tabular-nums">{inv.places.toLocaleString(i18n.language)}</TableCell>
                      <TableCell>
                        <span className="text-sm text-muted-foreground tabular-nums">
                          {t('invitations.batches.split', { whatsapp: count(inv, 'WHATSAPP'), paper: count(inv, 'PAPER') })}
                        </span>
                      </TableCell>
                      <TableCell className="text-right">
                        {printable && (
                          <Button variant="outline" size="sm" asChild>
                            <Link to={printPath(event.id, inv.id)} aria-label={t('invitations.print.label', { date })}>
                              <Printer />
                              {t('invitations.print.action')}
                            </Link>
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                    {expanded && (
                      <TableRow id={panel} className="bg-muted/30 hover:bg-muted/30">
                        <TableCell colSpan={6} className="p-0">
                          <ul aria-label={t('invitations.batches.holders', { date })} className="divide-y">
                            {inv.tickets.map((tk) => (
                              <li key={tk.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 py-2.5 pr-5 pl-12">
                                <span className="min-w-40 flex-1 font-medium">
                                  <bdi>{tk.holder_name}</bdi>
                                </span>
                                <span className="font-mono text-sm tracking-wider">{tk.ticket_no}</span>
                                <TicketStatusBadge row={{ status: tk.status, pass: null }} />
                                <DeliveryBadge delivery={tk.delivery} />
                                {canRevoke && (
                                  <Button
                                    variant="ghost"
                                    size="sm"
                                    className="ml-auto text-destructive hover:text-destructive"
                                    disabled={frozen || !revocable(tk)}
                                    aria-describedby={frozen ? FREEZE_ID : undefined}
                                    aria-label={t('invitations.revoke.label', { ticket: tk.ticket_no })}
                                    onClick={() => setRevoking((r) => ({ ticket: tk, open: true, n: (r?.n ?? 0) + 1 }))}
                                  >
                                    {t('invitations.revoke.action')}
                                  </Button>
                                )}
                              </li>
                            ))}
                          </ul>
                        </TableCell>
                      </TableRow>
                    )}
                  </Fragment>
                )
              })}
            </TableBody>
          </Table>
        )}
      </CardContent>
      {revoking && (
        <RevokeDialog key={revoking.n} eventId={id} ticket={revoking.ticket} open={revoking.open} onOpenChange={(open) => setRevoking((r) => r && { ...r, open })} />
      )}
    </Card>
  )
}

function DeliveryBadge({ delivery }: { delivery: Delivery }) {
  const { t } = useTranslation()
  const Icon = delivery === 'WHATSAPP' ? MessageCircle : Printer
  return (
    <Badge variant="secondary" className="gap-1 rounded-full font-normal">
      <Icon aria-hidden="true" />
      {t(`invitations.delivery.${delivery}`)}
    </Badge>
  )
}

/**
 * Révoquer = the ticket refund (04 §Invitations Revoke): REFUNDED, the place back in the quota, no money owed (amount
 * 0, no refunds row). The API needs a reason (1–200, audited). Mounted per opening.
 */
function RevokeDialog({ eventId, ticket, open, onOpenChange }: { eventId: string; ticket: InvitationTicket; open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation()
  const run = useTicketAction(eventId)
  const invalid = t('tickets.reasonInvalid')
  const form = useForm({
    resolver: zodResolver(z.object({ reason: z.string().trim().min(1, invalid).max(200, invalid) })),
    defaultValues: { reason: '' },
  })
  const error = form.formState.errors.reason?.message
  const vars = { ticket: ticket.ticket_no }
  const onSubmit = form.handleSubmit(({ reason }) =>
    run.mutateAsync({ kind: 'refund', id: ticket.id, reason }).then(
      () => {
        toast.success(t('invitations.revoke.done', vars), { id: `invitation-revoke-${ticket.id}` })
        onOpenChange(false)
      },
      (e: unknown) => showFormError(e, form, t),
    ),
  )
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent closeLabel={t('common.close')}>
        <DialogHeader>
          <DialogTitle>{t('invitations.revoke.title', vars)}</DialogTitle>
          <DialogDescription>{t('invitations.revoke.body')}</DialogDescription>
        </DialogHeader>
        <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
          <Field data-invalid={!!error}>
            <FieldLabel htmlFor="revoke-reason">{t('tickets.reason')}</FieldLabel>
            <Input
              id="revoke-reason"
              autoComplete="off"
              aria-invalid={!!error}
              aria-describedby={error ? 'revoke-reason-error revoke-reason-hint' : 'revoke-reason-hint'}
              {...form.register('reason')}
            />
            <FieldDescription id="revoke-reason-hint">{t('tickets.reasonHint')}</FieldDescription>
            {error && <FieldError id="revoke-reason-error">{error}</FieldError>}
          </Field>
          {form.formState.errors.root && <FieldError>{form.formState.errors.root.message}</FieldError>}
          <DialogFooter>
            <DialogClose asChild>
              <Button type="button" variant="outline">
                {t('common.cancel')}
              </Button>
            </DialogClose>
            <Button type="submit" variant="destructive" disabled={form.formState.isSubmitting}>
              {t('invitations.revoke.confirm')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
