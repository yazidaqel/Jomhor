import { useState, type ReactNode } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { Landmark } from 'lucide-react'
import { useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { z } from 'zod'
import { BrandEmpty, ErrorNotice, LoadingRows, STICKY_TABLE } from '@/components/page'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Textarea } from '@/components/ui/textarea'
import { useOrganizer } from '@/features/auth/organizer'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { formatDh, parseDh } from '@/lib/money'
import { addDays, DEFAULT_ZONE, formatDateTime, formatDay, todayIn } from '@/lib/time'
import { cn } from '@/lib/utils'
import { RangeForm, type Days } from './CashPage'
import { RECORD_ROLES, useDeposits, useRecordDeposit, useVoidDeposit, VOID_ROLES, type Deposit, type DepositSession } from './api'

const useMoney = () => {
  const { t, i18n } = useTranslation()
  return {
    dh: (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) }),
    // A gap reads signed; zero says so in words.
    gap: (centimes: number) => (centimes === 0 ? t('finance.cash.noGap') : t('sales.amount', { amount: formatDh(centimes, i18n.language, 'exceptZero') })),
  }
}
const sum = (sessions: DepositSession[]) => sessions.reduce((a, s) => a + s.declared_mad, 0)
/** A voided deposit's figures read struck through. */
const struck = (voided: boolean) => cn('tabular-nums', voided && 'text-muted-foreground line-through')

/**
 * B8 Finance › Dépôts (Lot 5 step 5, 04 §Finance): closed till sessions not yet in a bank deposit, and the deposits of
 * the range. ADMIN and FINANCE record a deposit; only ADMIN voids one; OPS reads.
 */
export function DepositsTab({ zone }: { zone: string }) {
  const { role } = useOrganizer()
  const canRecord = RECORD_ROLES.includes(role)
  const canVoid = VOID_ROLES.includes(role)
  const [days, setDays] = useState<Days>(() => {
    const today = todayIn(zone)
    return { from: addDays(today, -29), to: today }
  })
  const deposits = useDeposits(days)
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  // `n` counts openings: the key, so each opening starts with an empty form and a fresh mutation.
  const [sheet, setSheet] = useState<{ sessions: DepositSession[]; open: boolean; n: number } | null>(null)
  const [voiding, setVoiding] = useState<{ deposit: Deposit; open: boolean; n: number } | null>(null)

  if (deposits.isPending) return <LoadingRows rows={6} />
  if (deposits.isError)
    return (
      <Card className="py-2">
        <ErrorNotice error={deposits.error} onRetry={() => void deposits.refetch()} />
      </Card>
    )
  const { undeposited } = deposits.data
  // Ids gone from the list (deposited meanwhile, here or elsewhere) drop out of the selection by themselves.
  const picked = undeposited.filter((s) => selected.has(s.id))

  return (
    <>
      <Undeposited
        sessions={undeposited}
        zone={zone}
        selectable={canRecord}
        picked={picked}
        onPick={setSelected}
        onRecord={() => setSheet((s) => ({ sessions: picked, open: true, n: (s?.n ?? 0) + 1 }))}
      />
      <div className="flex flex-wrap items-end justify-between gap-4">
        <RangeForm id="deposits" initial={days} onSubmit={setDays} />
      </div>
      <DepositList deposits={deposits.data.deposits} zone={zone} onVoid={canVoid ? (d) => setVoiding((v) => ({ deposit: d, open: true, n: (v?.n ?? 0) + 1 })) : undefined} />
      {sheet && (
        <DepositSheet
          key={sheet.n}
          sessions={sheet.sessions}
          zone={zone}
          open={sheet.open}
          onOpenChange={(open) => setSheet((s) => s && { ...s, open })}
          onDone={() => setSelected(new Set())}
        />
      )}
      {voiding && <VoidDialog key={voiding.n} deposit={voiding.deposit} open={voiding.open} onOpenChange={(open) => setVoiding((v) => v && { ...v, open })} />}
    </>
  )
}

function Section({ title, description, footer, children }: { title: string; description: string; footer?: ReactNode; children: ReactNode }) {
  return (
    <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
      <CardHeader className="border-b pb-5">
        <CardTitle role="heading" aria-level={2}>
          {title}
        </CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      {children}
      {footer}
    </Card>
  )
}

function Undeposited(props: {
  sessions: DepositSession[]
  zone: string
  selectable: boolean
  picked: DepositSession[]
  onPick: (ids: ReadonlySet<string>) => void
  onRecord: () => void
}) {
  const { t, i18n } = useTranslation()
  const { dh } = useMoney()
  const { sessions, picked, selectable } = props
  const ids = new Set(picked.map((s) => s.id))
  const toggle = (id: string, on: boolean) => {
    const next = new Set(ids)
    if (on) next.add(id)
    else next.delete(id)
    props.onPick(next)
  }
  const all = sessions.length > 0 && picked.length === sessions.length

  return (
    <Section
      title={t('finance.deposits.todo.title')}
      description={t('finance.deposits.todo.description')}
      footer={
        selectable &&
        sessions.length > 0 && (
          <CardFooter className="flex flex-wrap items-center justify-between gap-3 border-t py-4">
            <span className="text-sm text-muted-foreground tabular-nums" aria-live="polite">
              {t('finance.deposits.todo.picked', { count: picked.length, amount: formatDh(sum(picked), i18n.language) })}
            </span>
            <Button disabled={picked.length === 0} onClick={props.onRecord}>
              <Landmark />
              {t('finance.deposits.record')}
            </Button>
          </CardFooter>
        )
      }
    >
      {sessions.length === 0 ? (
        <BrandEmpty title={t('finance.deposits.todo.emptyTitle')} description={t('finance.deposits.todo.empty')} />
      ) : (
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              {selectable && (
                <TableHead className="w-10">
                  <Checkbox
                    aria-label={t('finance.deposits.todo.pickAll')}
                    checked={all ? true : picked.length > 0 ? 'indeterminate' : false}
                    onCheckedChange={(c) => props.onPick(new Set(c === true ? sessions.map((s) => s.id) : []))}
                  />
                </TableHead>
              )}
              <TableHead>{t('finance.cash.sessions.label')}</TableHead>
              <TableHead>{t('finance.cash.sessions.cashier')}</TableHead>
              <TableHead>{t('finance.cash.sessions.closed')}</TableHead>
              <TableHead className="text-right">{t('finance.cash.sessions.declared')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {sessions.map((s) => (
              <TableRow key={s.id} data-state={ids.has(s.id) ? 'selected' : undefined}>
                {selectable && (
                  <TableCell>
                    <Checkbox
                      aria-label={t('finance.deposits.todo.pick', { label: s.label, cashier: s.staff.name })}
                      checked={ids.has(s.id)}
                      onCheckedChange={(c) => toggle(s.id, c === true)}
                    />
                  </TableCell>
                )}
                <TableCell className="font-medium">{s.label}</TableCell>
                <TableCell>{s.staff.name}</TableCell>
                <TableCell className="tabular-nums">{formatDateTime(s.closed_at, i18n.language, props.zone)}</TableCell>
                <TableCell className="text-right tabular-nums">{dh(s.declared_mad)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Section>
  )
}

function DepositList({ deposits, zone, onVoid }: { deposits: Deposit[]; zone: string; onVoid?: (d: Deposit) => void }) {
  const { t, i18n } = useTranslation()
  const { dh, gap } = useMoney()
  return (
    <Section title={t('finance.deposits.list.title')} description={t('finance.deposits.list.description')}>
      {deposits.length === 0 ? (
        <BrandEmpty title={t('finance.deposits.list.emptyTitle')} description={t('finance.deposits.list.empty')} />
      ) : (
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>{t('finance.deposits.list.date')}</TableHead>
              <TableHead>{t('finance.deposits.list.reference')}</TableHead>
              <TableHead className="text-right">{t('finance.deposits.list.amount')}</TableHead>
              <TableHead className="text-right">{t('finance.deposits.list.declared')}</TableHead>
              <TableHead className="text-right">{t('finance.deposits.list.variance')}</TableHead>
              <TableHead>{t('finance.deposits.list.sessions')}</TableHead>
              <TableHead>{t('finance.deposits.list.recordedBy')}</TableHead>
              {onVoid && (
                <TableHead>
                  <span className="sr-only">{t('finance.deposits.list.actions')}</span>
                </TableHead>
              )}
            </TableRow>
          </TableHeader>
          <TableBody>
            {/* Newest slip first, voided ones included, as the API sends them. */}
            {deposits.map((d) => {
              const voided = d.voided_at !== null
              const strike = struck(voided)
              return (
                <TableRow key={d.id}>
                  <TableCell className={strike}>{formatDay(d.deposited_on, i18n.language)}</TableCell>
                  <TableCell className={cn(strike, 'font-medium')}>{d.reference}</TableCell>
                  <TableCell className={cn(strike, 'text-right')}>{dh(d.amount_mad)}</TableCell>
                  <TableCell className={cn(strike, 'text-right')}>{d.declared_mad === null ? '' : dh(d.declared_mad)}</TableCell>
                  <TableCell className={cn(strike, 'text-right', d.variance_mad !== null && (d.variance_mad === 0 ? 'text-muted-foreground' : 'text-destructive'))}>
                    {d.variance_mad === null ? '' : gap(d.variance_mad)}
                  </TableCell>
                  <TableCell className="max-w-72 whitespace-normal">
                    {voided ? (
                      <Badge variant="outline" className="h-auto max-w-full rounded-full whitespace-normal text-muted-foreground">
                        {t('finance.deposits.list.voided', { reason: d.void_reason ?? '' })}
                      </Badge>
                    ) : (
                      <ul className="flex flex-col gap-0.5 text-sm">
                        {d.sessions.map((s) => (
                          <li key={s.id}>
                            {s.label} <span className="text-muted-foreground">· {s.staff.name}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </TableCell>
                  <TableCell className="text-sm">
                    {d.recorded_by.name}
                    <span className="block text-xs text-muted-foreground tabular-nums">{formatDateTime(d.recorded_at, i18n.language, zone)}</span>
                  </TableCell>
                  {onVoid && (
                    <TableCell className="text-right">
                      {!voided && (
                        <Button size="sm" variant="outline" aria-label={t('finance.deposits.void.label', { reference: d.reference })} onClick={() => onVoid(d)}>
                          {t('finance.deposits.void.action')}
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
    </Section>
  )
}

const SESSION_REASONS = ['OPEN', 'DEPOSITED', 'UNKNOWN'] as const
const DATE_REASONS = ['FUTURE', 'BEFORE_CLOSE'] as const
const reasonOf = <R extends string>(e: ApiError, reasons: readonly R[]) => (reasons as readonly unknown[]).includes(e.details.reason) ? (e.details.reason as R) : null

/**
 * "Enregistrer un dépôt": the slip's date (≤ today in Africa/Casablanca, the server's rule), its amount in DH (integer
 * centimes, no float) and reference, for the sessions picked when it opened → `POST /org/finance/deposits`.
 */
function DepositSheet(props: { sessions: DepositSession[]; zone: string; open: boolean; onOpenChange: (open: boolean) => void; onDone: () => void }) {
  const { t, i18n } = useTranslation()
  const { dh, gap } = useMoney()
  const record = useRecordDeposit()
  const { sessions } = props
  const today = todayIn(DEFAULT_ZONE)
  const declared = sum(sessions)
  // Per session id: why the server refused it (422 on `session_ids[i]`).
  const [refused, setRefused] = useState<Record<string, string>>({})
  const referenceInvalid = t('finance.deposits.sheet.referenceInvalid')
  const schema = z.object({
    deposited_on: z.iso.date(t('finance.deposits.sheet.dateInvalid')).refine((d) => d <= today, t('finance.deposits.sheet.FUTURE')),
    amount: z.string().refine((s) => parseDh(s) !== null, t('finance.deposits.sheet.amountInvalid')),
    reference: z.string().trim().min(1, referenceInvalid).max(100, referenceInvalid),
  })
  const form = useForm({ resolver: zodResolver(schema), mode: 'onTouched', defaultValues: { deposited_on: today, amount: '', reference: '' } })
  const amount = parseDh(useWatch({ control: form.control, name: 'amount' }))
  const { errors, isSubmitting } = form.formState

  const onSubmit = form.handleSubmit(async (v) => {
    const sessionIds = sessions.map((s) => s.id)
    setRefused({})
    try {
      const d = await record.mutateAsync({ deposited_on: v.deposited_on, amount_mad: parseDh(v.amount)!, reference: v.reference, session_ids: sessionIds })
      toast.success(t('finance.deposits.sheet.done', { reference: d.reference }), { id: `deposit-${d.id}` })
      props.onDone()
      props.onOpenChange(false)
    } catch (e) {
      const fields = e instanceof ApiError && e.code === 'VALIDATION_FAILED' ? Object.keys(Object(e.details.fields)) : []
      const rows: Record<string, string> = {}
      for (const f of fields) {
        const i = /^session_ids\[(\d+)\]$/.exec(f)?.[1]
        const id = i === undefined ? undefined : sessionIds[Number(i)]
        if (id) rows[id] = t(`finance.deposits.sheet.${reasonOf(e as ApiError, SESSION_REASONS) ?? 'UNKNOWN'}`)
        else if (f === 'deposited_on') {
          const r = reasonOf(e as ApiError, DATE_REASONS)
          form.setError('deposited_on', { message: r ? t(`finance.deposits.sheet.${r}`) : t('common.fieldInvalid') }, { shouldFocus: true })
        } else if (f === 'amount_mad') form.setError('amount', { message: t('common.fieldInvalid') }, { shouldFocus: true })
        else if (f === 'reference') form.setError('reference', { message: t('common.fieldInvalid') }, { shouldFocus: true })
      }
      setRefused(rows)
      form.setError('root', { message: t(errorKey(e)) })
    }
  })

  const field = (name: 'deposited_on' | 'amount' | 'reference', input: ReactNode, hint?: string) => (
    <Field data-invalid={!!errors[name]}>
      <FieldLabel htmlFor={`deposit-${name}`}>{t(`finance.deposits.sheet.${name}`)}</FieldLabel>
      {input}
      {hint && <FieldDescription id={`deposit-${name}-hint`}>{hint}</FieldDescription>}
      {errors[name] && <FieldError id={`deposit-${name}-error`}>{errors[name].message}</FieldError>}
    </Field>
  )
  const describedBy = (name: 'deposited_on' | 'amount' | 'reference', hint = false) =>
    [errors[name] && `deposit-${name}-error`, hint && `deposit-${name}-hint`].filter(Boolean).join(' ') || undefined

  const dateField = field(
    'deposited_on',
    <Input id="deposit-deposited_on" type="date" max={today} aria-invalid={!!errors.deposited_on} aria-describedby={describedBy('deposited_on')} {...form.register('deposited_on')} />,
  )
  const amountField = field(
    'amount',
    <Input
      id="deposit-amount"
      inputMode="decimal"
      autoComplete="off"
      className="tabular-nums"
      aria-invalid={!!errors.amount}
      aria-describedby={describedBy('amount', true)}
      {...form.register('amount')}
    />,
    t('finance.deposits.sheet.amountHint'),
  )
  const referenceField = field(
    'reference',
    <Input id="deposit-reference" autoComplete="off" aria-invalid={!!errors.reference} aria-describedby={describedBy('reference')} {...form.register('reference')} />,
  )

  return (
    <Sheet open={props.open} onOpenChange={props.onOpenChange}>
      <SheetContent className="w-full gap-0 sm:max-w-lg" closeLabel={t('common.close')}>
        <SheetHeader className="border-b p-5">
          <SheetTitle>{t('finance.deposits.record')}</SheetTitle>
          <SheetDescription>{t('finance.deposits.sheet.description')}</SheetDescription>
        </SheetHeader>
        <form className="flex min-h-0 flex-1 flex-col" onSubmit={(e) => void onSubmit(e)} noValidate>
          <div className="flex flex-1 flex-col gap-6 overflow-y-auto p-5">
            <FieldGroup className="grid gap-4 sm:grid-cols-2">
              {dateField}
              {amountField}
              <div className="sm:col-span-2">{referenceField}</div>
            </FieldGroup>

            <section aria-labelledby="deposit-sessions" className="flex flex-col gap-3">
              <h3 id="deposit-sessions" className="text-sm font-medium">
                {t('finance.deposits.sheet.sessions', { count: sessions.length })}
              </h3>
              <ul className="divide-y rounded-lg border">
                {sessions.map((s) => (
                  <li key={s.id} className="flex flex-col gap-1 px-3 py-2.5 text-sm">
                    <div className="flex items-baseline justify-between gap-3">
                      <span>
                        <span className="font-medium">{s.label}</span> <span className="text-muted-foreground">· {s.staff.name}</span>
                        <span className="block text-xs text-muted-foreground tabular-nums">{formatDateTime(s.closed_at, i18n.language, props.zone)}</span>
                      </span>
                      <span className="tabular-nums">{dh(s.declared_mad)}</span>
                    </div>
                    {refused[s.id] && <p className="text-sm text-destructive">{refused[s.id]}</p>}
                  </li>
                ))}
              </ul>
              <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1.5 rounded-lg bg-muted/60 px-3 py-3 text-sm tabular-nums">
                <dt className="text-muted-foreground">{t('finance.deposits.sheet.declared')}</dt>
                <dd className="text-right font-medium">{dh(declared)}</dd>
                <dt className="text-muted-foreground">{t('finance.deposits.sheet.variance')}</dt>
                <dd
                  data-testid="deposit-variance"
                  aria-live="polite"
                  className={cn('text-right font-semibold', amount === null ? 'text-muted-foreground' : amount - declared === 0 ? 'text-foreground' : 'text-destructive')}
                >
                  {amount === null ? t('finance.deposits.sheet.varianceWaiting') : gap(amount - declared)}
                </dd>
              </dl>
            </section>
            {errors.root && <FieldError>{errors.root.message}</FieldError>}
          </div>
          <SheetFooter className="border-t p-5 sm:flex-row sm:justify-end">
            <Button type="button" variant="outline" onClick={() => props.onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {t('finance.deposits.sheet.submit')}
            </Button>
          </SheetFooter>
        </form>
      </SheetContent>
    </Sheet>
  )
}

/** "Annuler" (ADMIN): a reason, 1–200 → `POST /org/finance/deposits/{id}/void`; its sessions go back to "À déposer". */
function VoidDialog({ deposit, open, onOpenChange }: { deposit: Deposit; open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation()
  const voidDeposit = useVoidDeposit()
  const invalid = t('finance.deposits.void.reasonInvalid')
  const form = useForm({ resolver: zodResolver(z.object({ reason: z.string().trim().min(1, invalid).max(200, invalid) })), defaultValues: { reason: '' } })
  const error = form.formState.errors.reason?.message
  const vars = { reference: deposit.reference }
  const onSubmit = form.handleSubmit(({ reason }) =>
    voidDeposit.mutateAsync({ id: deposit.id, reason }).then(
      () => {
        toast.success(t('finance.deposits.void.done', vars), { id: `deposit-void-${deposit.id}` })
        onOpenChange(false)
      },
      (e: unknown) => form.setError('root', { message: t(errorKey(e)) }),
    ),
  )
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent closeLabel={t('common.close')}>
        <DialogHeader>
          <DialogTitle>{t('finance.deposits.void.title', vars)}</DialogTitle>
          <DialogDescription>{t('finance.deposits.void.description')}</DialogDescription>
        </DialogHeader>
        <form className="flex flex-col gap-4" onSubmit={(e) => void onSubmit(e)} noValidate>
          <Field data-invalid={!!error}>
            <FieldLabel htmlFor="deposit-void-reason">{t('finance.deposits.void.reason')}</FieldLabel>
            <Textarea id="deposit-void-reason" rows={3} aria-invalid={!!error} aria-describedby={error ? 'deposit-void-reason-error' : undefined} {...form.register('reason')} />
            {error && <FieldError id="deposit-void-reason-error">{error}</FieldError>}
          </Field>
          {form.formState.errors.root && <FieldError>{form.formState.errors.root.message}</FieldError>}
          <DialogFooter>
            <DialogClose asChild>
              <Button type="button" variant="outline">
                {t('finance.deposits.void.keep')}
              </Button>
            </DialogClose>
            <Button type="submit" variant="destructive" disabled={form.formState.isSubmitting}>
              {t('finance.deposits.void.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
