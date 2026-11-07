import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { Search, Snowflake } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Navigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { z } from 'zod'
import { BrandEmpty, ErrorNotice, LoadingRows, PageHeader, STICKY_TABLE } from '@/components/page'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useOrganizer } from '@/features/auth/organizer'
import { useEvent } from '@/features/events/api'
import { StatusBadge } from '@/features/events/EventsPage'
import { shortNo } from '@/features/pos/api'
import { ApiError } from '@/lib/api'
import { showFormError } from '@/lib/forms'
import { formatDh } from '@/lib/money'
import { formatDateTime } from '@/lib/time'
import { useNow } from '@/lib/useNow'
import { cn } from '@/lib/utils'
import { ACTION_ROLES, actionsFor, lookupQuery, useTicketAction, useTicketLookup, type TicketAction, type TicketRow, type TicketStatus } from './api'

const STATUS_STYLE: Record<TicketStatus, { badge: string; dot: string }> = {
  VALID: { badge: 'border-success-border bg-success-muted text-success-foreground', dot: 'bg-success' },
  BLOCKED: { badge: 'border-warning-border bg-warning-muted text-warning-foreground', dot: 'bg-warning' },
  REFUNDED: { badge: 'border-border bg-background text-muted-foreground', dot: 'bg-chart-4' },
  CANCELLED: { badge: 'border-border bg-background text-muted-foreground', dot: 'bg-destructive' },
}

function TicketStatusBadge({ status }: { status: TicketStatus }) {
  const { t } = useTranslation()
  return (
    <Badge variant="outline" className={cn('gap-1.5 rounded-full px-2.5 font-medium', STATUS_STYLE[status].badge)}>
      <span className={cn('size-1.5 rounded-full', STATUS_STYLE[status].dot)} aria-hidden="true" />
      {t(`tickets.statuses.${status}`)}
    </Badge>
  )
}

/** B12 Billets: `/events/:id/tickets`. ADMIN, OPS, FINANCE (the shell keeps CASHIER out); FINANCE reads only. */
export function TicketsPage() {
  const id = useParams().id ?? ''
  if (!/^\d+$/.test(id)) return <Navigate to="/events" replace state={{ notFound: true }} />
  return <EventTicketsPage id={String(Number(id))} />
}

const isNotFound = (e: unknown) => e instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(e.code)
const FREEZE_ID = 'tickets-freeze'

/** `n` counts openings: the dialog's key, so each opening starts with an empty form and a fresh mutation. */
type Action = { kind: TicketAction; ticket: TicketRow; open: boolean; n: number }

function EventTicketsPage({ id }: { id: string }) {
  const { t, i18n } = useTranslation()
  const { role } = useOrganizer()
  const event = useEvent(id)
  const [q, setQ] = useState<string | null>(null)
  // A clicked ticket number refills the search box: a new key remounts the form with it.
  const [prefill, setPrefill] = useState({ n: 0, q: '' })
  const lookup = useTicketLookup(id, q)
  const [action, setAction] = useState<Action | null>(null)
  // ponytail: browser clock, re-read every 30 s; the server enforces the freeze (409 REFUND_FROZEN) either way.
  const now = useNow(30_000)
  if (isNotFound(event.error)) return <Navigate to="/events" replace state={{ notFound: true }} />
  if (event.isPending) return <Skeleton aria-busy="true" className="h-16 w-80" />
  if (!event.data) return <ErrorNotice error={event.error} onRetry={() => void event.refetch()} />

  const { refund_cutoff_at: cutoff, timezone } = event.data
  const frozen = cutoff !== null && now >= Date.parse(cutoff)
  const canAct = (['block', 'refund'] as const).some((k) => ACTION_ROLES[k].includes(role))
  const eventPath = `/events/${encodeURIComponent(event.data.id)}`
  const search = (next: string) => (next === q ? void lookup.refetch() : setQ(next))
  const searchTicket = (no: string) => {
    setPrefill((p) => ({ n: p.n + 1, q: no }))
    search(no)
  }
  const ticketLink = (no: string | null) =>
    no && (
      <Button variant="link" size="sm" className="h-auto p-0 font-mono tracking-wider" aria-label={t('tickets.searchTicket', { ticket: no })} onClick={() => searchTicket(no)}>
        {no}
      </Button>
    )
  const freeze = cutoff === null ? t('tickets.noCutoff') : t(frozen ? 'tickets.frozen' : 'tickets.freezeAt', { date: formatDateTime(cutoff, i18n.language, timezone) })

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        crumbs={[{ label: t('events.title'), to: '/events' }, { label: event.data.title, to: eventPath }, { label: t('tickets.title') }]}
        title={t('tickets.title')}
        badge={<StatusBadge status={event.data.status} />}
        description={t('sales.eventLine', { date: formatDateTime(event.data.starts_at, i18n.language, timezone), zone: timezone })}
      />
      <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
        <CardHeader className="border-b pb-5">
          <CardTitle role="heading" aria-level={2}>
            {t('tickets.search.title')}
          </CardTitle>
          <CardDescription
            id={FREEZE_ID}
            className={cn('flex items-center gap-2', frozen && canAct && 'font-medium text-warning-foreground')}
          >
            {frozen && <Snowflake className="size-4 shrink-0" aria-hidden="true" />}
            {freeze}
          </CardDescription>
          <SearchForm key={prefill.n} initial={prefill.q} onSearch={search} />
        </CardHeader>
        <CardContent className="p-0">
          {q === null ? (
            <BrandEmpty title={t('tickets.search.title')} description={t(canAct ? 'tickets.search.prompt' : 'tickets.search.promptReadOnly')} />
          ) : lookup.isPending ? (
            <LoadingRows className="p-5" />
          ) : lookup.isError ? (
            lookup.error instanceof ApiError && lookup.error.code === 'VALIDATION_FAILED' ? (
              <p role="alert" className="p-5 text-sm text-destructive">
                {t('tickets.search.invalid')}
              </p>
            ) : (
              <ErrorNotice error={lookup.error} onRetry={() => void lookup.refetch()} />
            )
          ) : lookup.data.length === 0 ? (
            <p role="status" className="p-5 text-sm text-muted-foreground">
              {t('tickets.search.empty', { q })}
            </p>
          ) : (
            <>
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead>{t('tickets.columns.ticket')}</TableHead>
                    <TableHead>{t('tickets.columns.zone')}</TableHead>
                    <TableHead>{t('tickets.columns.holder')}</TableHead>
                    <TableHead>{t('tickets.columns.status')}</TableHead>
                    <TableHead>{t('tickets.columns.order')}</TableHead>
                    <TableHead>{t('tickets.columns.transferredTo')}</TableHead>
                    <TableHead>{t('tickets.columns.transferredFrom')}</TableHead>
                    {canAct && (
                      <TableHead className="text-right">
                        <span className="sr-only">{t('tickets.columns.actions')}</span>
                      </TableHead>
                    )}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {lookup.data.map((r) => (
                    <TableRow key={r.id}>
                      <TableCell>
                        <code className="font-mono text-sm tracking-wider">{r.ticket_no}</code>
                      </TableCell>
                      <TableCell>
                        <span className="font-medium">{r.zone.code}</span> <span className="text-muted-foreground">{r.zone.name}</span>
                      </TableCell>
                      <TableCell>
                        <bdi>{r.holder_label ?? r.holder_name}</bdi>
                      </TableCell>
                      <TableCell>
                        <TicketStatusBadge status={r.status} />
                        {r.refund && (
                          <div className="mt-1 text-xs text-muted-foreground tabular-nums">
                            {t(`tickets.refund.${r.refund.status}`, { amount: formatDh(r.refund.amount_mad, i18n.language) })}
                          </div>
                        )}
                      </TableCell>
                      <TableCell>
                        <div className="font-mono text-xs">{t('tickets.order', { no: shortNo(r.order_id) })}</div>
                        <div className="text-xs text-muted-foreground">
                          {[t(`sales.channels.names.${r.channel}`), r.payment_method && t(`sales.methods.${r.payment_method}`)].filter(Boolean).join(' · ')}
                        </div>
                      </TableCell>
                      <TableCell>{ticketLink(r.transferred_to)}</TableCell>
                      <TableCell>{ticketLink(r.transferred_from)}</TableCell>
                      {canAct && (
                        <TableCell>
                          <div className="flex justify-end gap-2">
                            {actionsFor(r.status)
                              .filter((k) => ACTION_ROLES[k].includes(role))
                              .map((kind) => (
                                <Button
                                  key={kind}
                                  size="sm"
                                  variant={kind === 'unblock' ? 'outline' : 'ghost'}
                                  className={cn(
                                    kind !== 'unblock' && 'text-destructive hover:bg-destructive/10 hover:text-destructive',
                                    'aria-disabled:cursor-not-allowed aria-disabled:opacity-50 aria-disabled:hover:bg-transparent',
                                  )}
                                  // aria-disabled, not disabled: still focusable, so the freeze notice it points at is heard.
                                  aria-disabled={frozen || undefined}
                                  aria-label={t(`tickets.actions.${kind}.label`, { ticket: r.ticket_no })}
                                  aria-describedby={frozen ? FREEZE_ID : undefined}
                                  onClick={() => frozen || setAction((a) => ({ kind, ticket: r, open: true, n: (a?.n ?? 0) + 1 }))}
                                >
                                  {t(`tickets.actions.${kind}.action`)}
                                </Button>
                              ))}
                          </div>
                        </TableCell>
                      )}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {lookup.data.length >= 50 && <p className="border-t p-4 text-sm text-muted-foreground">{t('tickets.search.capped')}</p>}
            </>
          )}
        </CardContent>
      </Card>
      {action && (
        <TicketActionDialog
          key={action.n}
          eventId={id}
          {...action}
          onOpenChange={(open) => setAction((a) => a && { ...a, open })}
        />
      )}
    </div>
  )
}

function SearchForm({ initial, onSearch }: { initial: string; onSearch: (q: string) => void }) {
  const { t } = useTranslation()
  const form = useForm({
    resolver: zodResolver(z.object({ q: z.string().trim().min(1, t('tickets.search.required')) })),
    defaultValues: { q: initial },
  })
  const error = form.formState.errors.q?.message
  return (
    <form role="search" className="mt-2 flex flex-wrap items-start gap-2" onSubmit={form.handleSubmit(({ q }) => onSearch(lookupQuery(q)))} noValidate>
      <Field data-invalid={!!error} className="max-w-md min-w-60 flex-1">
        <FieldLabel htmlFor="tickets-q" className="sr-only">
          {t('tickets.search.label')}
        </FieldLabel>
        <Input
          id="tickets-q"
          type="search"
          autoComplete="off"
          placeholder={t('tickets.search.placeholder')}
          aria-invalid={!!error}
          aria-describedby={error ? 'tickets-q-error' : undefined}
          {...form.register('q')}
        />
        {error && <FieldError id="tickets-q-error">{error}</FieldError>}
      </Field>
      <Button type="submit">
        <Search />
        {t('tickets.search.submit')}
      </Button>
    </form>
  )
}

/** Bloquer / Débloquer / Rembourser: a confirmation that needs a reason (1–200, audited). Mounted per opening. */
function TicketActionDialog({ kind, ticket, eventId, open, onOpenChange }: Action & { eventId: string; onOpenChange: (open: boolean) => void }) {
  const { t, i18n } = useTranslation()
  const run = useTicketAction(eventId)
  const invalid = t('tickets.reasonInvalid')
  const form = useForm({
    resolver: zodResolver(z.object({ reason: z.string().trim().min(1, invalid).max(200, invalid) })),
    defaultValues: { reason: '' },
  })
  const error = form.formState.errors.reason?.message
  const vars = { ticket: ticket.ticket_no }
  // A TICKET_STATUS or REFUND_FROZEN answer stays in the dialog (its copy says why); the list refetches behind it.
  const onSubmit = form.handleSubmit(({ reason }) =>
    run.mutateAsync({ kind, id: ticket.id, reason }).then(
      () => {
        toast.success(t(`tickets.actions.${kind}.done`, vars), { id: `ticket-${kind}-${ticket.id}` })
        onOpenChange(false)
      },
      (e: unknown) => showFormError(e, form, t),
    ),
  )
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent closeLabel={t('common.close')}>
        <DialogHeader>
          <DialogTitle>{t(`tickets.actions.${kind}.title`, vars)}</DialogTitle>
          <DialogDescription>{t(`tickets.actions.${kind}.body`)}</DialogDescription>
        </DialogHeader>
        {kind === 'refund' && (
          <p className="rounded-lg border bg-muted/50 px-4 py-3 text-sm font-medium tabular-nums">
            {t('tickets.actions.refund.amount', { amount: formatDh(ticket.unit_price_mad, i18n.language) })}
          </p>
        )}
        <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
          <Field data-invalid={!!error}>
            <FieldLabel htmlFor="ticket-reason">{t('tickets.reason')}</FieldLabel>
            <Input
              id="ticket-reason"
              autoComplete="off"
              aria-invalid={!!error}
              aria-describedby={error ? 'ticket-reason-error ticket-reason-hint' : 'ticket-reason-hint'}
              {...form.register('reason')}
            />
            <FieldDescription id="ticket-reason-hint">{t('tickets.reasonHint')}</FieldDescription>
            {error && <FieldError id="ticket-reason-error">{error}</FieldError>}
          </Field>
          {form.formState.errors.root && <FieldError>{form.formState.errors.root.message}</FieldError>}
          <DialogFooter>
            <DialogClose asChild>
              <Button type="button" variant="outline">
                {t('common.cancel')}
              </Button>
            </DialogClose>
            <Button type="submit" variant={kind === 'unblock' ? 'default' : 'destructive'} disabled={form.formState.isSubmitting}>
              {t(`tickets.actions.${kind}.confirm`)}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
