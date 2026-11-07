import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { KeyRound, Plus } from 'lucide-react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { z } from 'zod'
import { BrandEmpty, ErrorNotice, LoadingRows, STICKY_TABLE } from '@/components/page'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { normalizePhone } from '@/features/auth/phone'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { showFormError } from '@/lib/forms'
import { cn } from '@/lib/utils'
import { STAFF_ROLES, useStaff, useStaffMutation, type Staff, type StaffRole } from './api'

type Pin = { pin: string; name: string }

/** Équipe: GATE and SUPERVISOR staff with their PIN (shown once, Q31), CASHIER staff (phone, OTP login, no PIN), deactivate. */
export function StaffTab() {
  const { t } = useTranslation()
  const staff = useStaff()
  // The PIN lives here and in the dialog only: never in a query or mutation result (see useStaffMutation).
  const [pin, setPin] = useState<Pin | null>(null)
  const showPin = (p: string, name: string) => setPin({ pin: p, name })
  const [adding, setAdding] = useState(false)
  // `n` counts openings: the dialog's key, so reopening it never shows the last attempt's state.
  const [resetting, setResetting] = useState<{ staff: Staff; open: boolean; n: number } | null>(null)
  const toggle = useStaffMutation(showPin)
  // Back-office members are listed too by the API (has_pin false); this tab is the gate team and the cashiers.
  const team = staff.data?.filter((s) => (STAFF_ROLES as readonly string[]).includes(s.role))

  const add = (
    <Button onClick={() => setAdding(true)}>
      <Plus />
      {t('staff.add')}
    </Button>
  )
  return (
    <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
      <CardHeader className="border-b pb-5">
        <CardTitle role="heading" aria-level={2}>
          {t('staff.title')}
        </CardTitle>
        <CardDescription>{t('staff.description')}</CardDescription>
        <CardAction>{add}</CardAction>
      </CardHeader>
      {staff.isPending ? (
        <LoadingRows className="p-5" />
      ) : staff.isError ? (
        <ErrorNotice error={staff.error} onRetry={() => void staff.refetch()} />
      ) : team!.length === 0 ? (
        <BrandEmpty title={t('staff.emptyTitle')} description={t('staff.empty')} />
      ) : (
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>{t('staff.name')}</TableHead>
              <TableHead>{t('staff.role')}</TableHead>
              <TableHead>{t('staff.pinColumn')}</TableHead>
              <TableHead>{t('staff.statusColumn')}</TableHead>
              <TableHead className="text-right">
                <span className="sr-only">{t('staff.actions')}</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {team!.map((s) => (
              <TableRow key={s.id} className={cn(!s.active && 'text-muted-foreground')}>
                <TableCell className="font-medium">{s.name}</TableCell>
                <TableCell>{t(`staff.roles.${s.role as StaffRole}`)}</TableCell>
                <TableCell>{t(s.role === 'CASHIER' ? 'staff.phoneLogin' : s.has_pin ? 'staff.hasPin' : 'staff.noPin')}</TableCell>
                <TableCell>
                  <Badge
                    variant="outline"
                    className={cn(
                      'rounded-full px-2.5 font-medium',
                      s.active ? 'border-success-border bg-success-muted text-success-foreground' : 'border-border bg-background text-muted-foreground',
                    )}
                  >
                    {t(s.active ? 'staff.active' : 'staff.inactive')}
                  </Badge>
                </TableCell>
                <TableCell>
                  <div className="flex justify-end gap-2">
                    {s.role !== 'CASHIER' && (
                      <Button
                        size="sm"
                        variant="outline"
                        aria-label={t('staff.resetPinLabel', { name: s.name })}
                        onClick={() => setResetting((r) => ({ staff: s, open: true, n: (r?.n ?? 0) + 1 }))}
                      >
                        <KeyRound />
                        {t('staff.resetPin')}
                      </Button>
                    )}
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={toggle.isPending}
                      aria-label={t(s.active ? 'staff.deactivateLabel' : 'staff.reactivateLabel', { name: s.name })}
                      onClick={() =>
                        toggle.mutate(
                          { kind: 'active', staff: s, active: !s.active },
                          {
                            onSuccess: () => toast.success(t(s.active ? 'staff.deactivated' : 'staff.reactivated', { name: s.name })),
                            onError: (e) => toast.error(t(errorKey(e))),
                          },
                        )
                      }
                    >
                      {t(s.active ? 'staff.deactivate' : 'staff.reactivate')}
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      <Dialog open={adding} onOpenChange={setAdding}>
        <DialogContent aria-describedby={undefined}>
          <DialogHeader>
            <DialogTitle>{t('staff.newTitle')}</DialogTitle>
          </DialogHeader>
          <AddStaffForm onPin={showPin} onDone={() => setAdding(false)} />
        </DialogContent>
      </Dialog>
      {resetting && (
        <ResetPinDialog
          key={resetting.n}
          staff={resetting.staff}
          open={resetting.open}
          onOpenChange={(open) => setResetting((r) => r && { ...r, open })}
          onPin={showPin}
        />
      )}
      <PinDialog pin={pin} onClose={() => setPin(null)} />
    </Card>
  )
}

function AddStaffForm({ onPin, onDone }: { onPin: (pin: string, name: string) => void; onDone: () => void }) {
  const { t } = useTranslation()
  // 04-api §Holder names: the server normalises and has the last word (Latin letters, 2-60); here, the length.
  // A CASHIER logs in by OTP: phone required (E.164); GATE/SUPERVISOR send none.
  const schema = z
    .object({
      name: z
        .string()
        .transform((s) => s.trim().replace(/\s+/g, ' '))
        .refine((s) => s.length >= 2 && s.length <= 60, t('staff.nameInvalid')),
      role: z.enum(['GATE', 'SUPERVISOR', 'CASHIER']),
      phone: z.string(),
    })
    .superRefine((v, ctx) => {
      if (v.role === 'CASHIER' && normalizePhone(v.phone) === null) ctx.addIssue({ code: 'custom', path: ['phone'], message: t('staff.phoneInvalid') })
    })
    .transform(({ name, role, phone }) => (role === 'CASHIER' ? { name, role, phone: normalizePhone(phone)! } : { name, role }))
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { name: '', role: 'GATE' as StaffRole, phone: '' } })
  const role = useWatch({ control: form.control, name: 'role' })
  const create = useStaffMutation(onPin)
  const { errors } = form.formState
  const submit = form.handleSubmit((body) =>
    create.mutate(
      { kind: 'create', body },
      {
        onSuccess: () => {
          if (body.role === 'CASHIER') toast.success(t('staff.cashierCreated', { name: body.name }))
          onDone()
        },
        onError: (e) => {
          showFormError(e, form, t)
          if (!(e instanceof ApiError && e.code === 'VALIDATION_FAILED')) return
          const fields = Object(e.details.fields) as object
          if (Object.hasOwn(fields, 'name')) form.setError('name', { message: t('staff.nameInvalid') })
          // 422 on `phone` with details.reason TAKEN: already staff of this organizer.
          if (Object.hasOwn(fields, 'phone'))
            form.setError('phone', { message: t(e.details.reason === 'TAKEN' ? 'staff.phoneTaken' : 'staff.phoneInvalid') }, { shouldFocus: true })
        },
      },
    ),
  )
  return (
    <form noValidate onSubmit={(e) => void submit(e)}>
      <FieldGroup>
        <Field data-invalid={!!errors.name}>
          <FieldLabel htmlFor="staff-name">{t('staff.name')}</FieldLabel>
          <Input
            id="staff-name"
            autoComplete="off"
            aria-invalid={!!errors.name}
            aria-describedby={errors.name ? 'staff-name-hint staff-name-error' : 'staff-name-hint'}
            {...form.register('name')}
          />
          <FieldDescription id="staff-name-hint">{t('staff.nameHint')}</FieldDescription>
          {errors.name && <FieldError id="staff-name-error">{errors.name.message}</FieldError>}
        </Field>
        <Controller
          control={form.control}
          name="role"
          render={({ field, fieldState }) => (
            <Field data-invalid={!!fieldState.error}>
              <FieldLabel htmlFor="staff-role">{t('staff.role')}</FieldLabel>
              <Select name={field.name} value={field.value} onValueChange={field.onChange}>
                <SelectTrigger id="staff-role" ref={field.ref} onBlur={field.onBlur} className="w-full" aria-describedby="staff-role-hint">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {STAFF_ROLES.map((r) => (
                    <SelectItem key={r} value={r}>
                      {t(`staff.roles.${r}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FieldDescription id="staff-role-hint">{t('staff.roleHint')}</FieldDescription>
              {fieldState.error && <FieldError>{fieldState.error.message}</FieldError>}
            </Field>
          )}
        />
        {role === 'CASHIER' && (
          <Field data-invalid={!!errors.phone}>
            <FieldLabel htmlFor="staff-phone">{t('staff.phone')}</FieldLabel>
            <Input
              id="staff-phone"
              type="tel"
              inputMode="tel"
              autoComplete="off"
              placeholder={t('login.phonePlaceholder')}
              aria-invalid={!!errors.phone}
              aria-describedby={errors.phone ? 'staff-phone-hint staff-phone-error' : 'staff-phone-hint'}
              {...form.register('phone')}
            />
            <FieldDescription id="staff-phone-hint">{t('staff.phoneHint')}</FieldDescription>
            {errors.phone && <FieldError id="staff-phone-error">{errors.phone.message}</FieldError>}
          </Field>
        )}
        {errors.root?.message && <FieldError>{errors.root.message}</FieldError>}
        <DialogFooter>
          <DialogClose asChild>
            <Button type="button" variant="outline">
              {t('common.cancel')}
            </Button>
          </DialogClose>
          <Button type="submit" disabled={create.isPending}>
            {t(role === 'CASHIER' ? 'staff.createCashier' : 'staff.create')}
          </Button>
        </DialogFooter>
      </FieldGroup>
    </form>
  )
}

function ResetPinDialog(props: { staff: Staff; open: boolean; onOpenChange: (open: boolean) => void; onPin: (pin: string, name: string) => void }) {
  const { t } = useTranslation()
  const reset = useStaffMutation(props.onPin)
  return (
    <AlertDialog open={props.open} onOpenChange={props.onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('staff.resetTitle', { name: props.staff.name })}</AlertDialogTitle>
          <AlertDialogDescription>{t('staff.resetBody')}</AlertDialogDescription>
        </AlertDialogHeader>
        {reset.error && <FieldError>{t(errorKey(reset.error))}</FieldError>}
        <AlertDialogFooter>
          <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
          <Button disabled={reset.isPending} onClick={() => reset.mutate({ kind: 'pin', staff: props.staff }, { onSuccess: () => props.onOpenChange(false) })}>
            {t('staff.resetConfirm')}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/** "PIN : 4821, affiché une seule fois". Closing drops it for good; an outside click does not close it by mistake. */
function PinDialog({ pin, onClose }: { pin: Pin | null; onClose: () => void }) {
  const { t } = useTranslation()
  return (
    <Dialog open={pin !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent onInteractOutside={(e) => e.preventDefault()} className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('staff.pin.title', { name: pin?.name ?? '' })}</DialogTitle>
          <DialogDescription>{t('staff.pin.body', { name: pin?.name ?? '' })}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col items-center gap-2 rounded-xl border bg-muted/50 py-6">
          <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{t('staff.pin.label')}</span>
          <output aria-label={t('staff.pin.label')} className="font-mono text-5xl font-semibold tracking-[0.35em] tabular-nums pl-[0.35em]">
            {pin?.pin}
          </output>
          <span className="text-sm text-muted-foreground">{t('staff.pin.once')}</span>
        </div>
        <DialogFooter>
          <Button onClick={onClose}>{t('staff.pin.done')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
