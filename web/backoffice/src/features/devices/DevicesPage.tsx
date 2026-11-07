import { useEffect, useRef, useState } from 'react'
import { Plus, RefreshCw } from 'lucide-react'
import QRCode from 'react-qr-code'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { BrandEmpty, ErrorNotice, LoadingRows, PageHeader, STICKY_TABLE } from '@/components/page'
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
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FieldError } from '@/components/ui/field'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useCanWrite } from '@/features/auth/organizer'
import { path } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import { cn } from '@/lib/utils'
import { useDeviceMutation, useDevices, type Device, type DeviceStatus, type EnrolCode } from './api'
import { StaffTab } from './StaffTab'

const STATUS_STYLE: Record<DeviceStatus, { badge: string; dot: string }> = {
  PENDING: { badge: 'border-warning-border bg-warning-muted text-warning-foreground', dot: 'bg-warning' },
  ACTIVE: { badge: 'border-success-border bg-success-muted text-success-foreground', dot: 'bg-success' },
  REVOKED: { badge: 'border-border bg-background text-muted-foreground', dot: 'bg-destructive' },
}

export function DeviceStatusBadge({ status }: { status: DeviceStatus }) {
  const { t } = useTranslation()
  return (
    <Badge variant="outline" className={cn('gap-1.5 rounded-full px-2.5 font-medium', STATUS_STYLE[status].badge)}>
      <span className={cn('size-1.5 rounded-full', STATUS_STYLE[status].dot)} aria-hidden="true" />
      {t(`devices.statuses.${status}`)}
    </Badge>
  )
}

/** B6 Appareils (Lot 2): enrolment, approval, revocation; and the gate team with their PINs. ADMIN/OPS only. */
export function DevicesPage() {
  const { t } = useTranslation()
  // 04-api: devices and staff are ADMIN/OPS; FINANCE would get 403 on every call, so none is made.
  const canManage = useCanWrite()
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('devices.title')} description={t('devices.description')} />
      {canManage ? (
        <Tabs defaultValue="devices">
          <TabsList>
            <TabsTrigger value="devices">{t('devices.tabs.devices')}</TabsTrigger>
            <TabsTrigger value="staff">{t('devices.tabs.staff')}</TabsTrigger>
          </TabsList>
          <TabsContent value="devices">
            <DevicesTab />
          </TabsContent>
          <TabsContent value="staff">
            <StaffTab />
          </TabsContent>
        </Tabs>
      ) : (
        <Card>
          <BrandEmpty title={t('devices.forbiddenTitle')} description={t('devices.forbidden')} />
        </Card>
      )}
    </div>
  )
}

type Action = { kind: 'approve' | 'revoke'; device: Device }

function DevicesTab() {
  const { t, i18n } = useTranslation()
  const [enrolOpen, setEnrolOpen] = useState(false)
  // While the QR is on screen, newly enrolled devices show up within 5 s.
  const devices = useDevices(enrolOpen ? 5000 : undefined)
  // Like the PIN: the code goes to state and the mutation returns nothing, so the JWT never sits in the mutation cache.
  // `live` drops a code that lands after the dialog closed.
  const [code, setCode] = useState<EnrolCode | null>(null)
  const live = useRef(false)
  const enrol = useDeviceMutation(async (_: void, send): Promise<void> => {
    const c = await send<EnrolCode>({ method: 'POST', path: '/org/devices/enrol-codes' })
    if (live.current) setCode(c)
  })
  const [action, setAction] = useState<(Action & { open: boolean }) | null>(null)

  const openEnrol = () => {
    live.current = true
    enrol.mutate()
    setEnrolOpen(true)
  }
  const add = (
    <Button onClick={openEnrol}>
      <Plus />
      {t('devices.add')}
    </Button>
  )
  return (
    <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
      <CardHeader className="border-b pb-5">
        <CardTitle role="heading" aria-level={2}>
          {t('devices.listTitle')}
        </CardTitle>
        <CardDescription>{t('devices.listDescription')}</CardDescription>
        <CardAction>{add}</CardAction>
      </CardHeader>
      {devices.isPending ? (
        <LoadingRows className="p-5" />
      ) : devices.isError ? (
        <ErrorNotice error={devices.error} onRetry={() => void devices.refetch()} />
      ) : devices.data.length === 0 ? (
        <BrandEmpty title={t('devices.emptyTitle')} description={t('devices.empty')} />
      ) : (
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>{t('devices.label')}</TableHead>
              <TableHead>{t('devices.status')}</TableHead>
              <TableHead>{t('devices.fingerprint')}</TableHead>
              <TableHead>{t('devices.enrolledAt')}</TableHead>
              <TableHead>{t('devices.lastSeen')}</TableHead>
              <TableHead className="text-right">
                <span className="sr-only">{t('devices.actions')}</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {devices.data.map((d) => (
              <TableRow key={d.id} className={cn(d.status === 'PENDING' && 'bg-warning-muted/50')}>
                <TableCell className="font-medium">{d.label}</TableCell>
                <TableCell>
                  <DeviceStatusBadge status={d.status} />
                </TableCell>
                <TableCell>
                  <code className="font-mono text-sm tracking-wider">{d.fingerprint}</code>
                </TableCell>
                <TableCell className="text-muted-foreground">{formatDateTime(d.enrolled_at, i18n.language)}</TableCell>
                <TableCell className="text-muted-foreground">
                  {d.last_seen_at ? formatDateTime(d.last_seen_at, i18n.language) : t('devices.never')}
                </TableCell>
                <TableCell>
                  <div className="flex justify-end gap-2">
                    {d.status === 'PENDING' && (
                      <Button size="sm" aria-label={t('devices.approve.label', { label: d.label })} onClick={() => setAction({ kind: 'approve', device: d, open: true })}>
                        {t('devices.approve.action')}
                      </Button>
                    )}
                    {d.status !== 'REVOKED' && (
                      <Button
                        size="sm"
                        variant="ghost"
                        className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                        aria-label={t('devices.revoke.label', { label: d.label })}
                        onClick={() => setAction({ kind: 'revoke', device: d, open: true })}
                      >
                        {t('devices.revoke.action')}
                      </Button>
                    )}
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      <EnrolDialog
        open={enrolOpen}
        onOpenChange={(o) => {
          setEnrolOpen(o)
          if (o) return
          live.current = false
          setCode(null) // the code leaves memory with the dialog
          enrol.reset()
        }}
        code={code ?? undefined}
        error={enrol.error}
        pending={enrol.isPending}
        onRegenerate={() => enrol.mutate()}
        pendingDevices={devices.data?.filter((d) => d.status === 'PENDING').length ?? 0}
      />
      {action && <DeviceActionDialog key={`${action.kind}-${action.device.id}`} {...action} onOpenChange={(open) => setAction((a) => a && { ...a, open })} />}
    </Card>
  )
}

const mmss = (s: number) => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`

/** "Ajouter des appareils": the enrol code as a QR (Q30: valid 30 min, reusable for a batch), its countdown, a new code. */
function EnrolDialog(props: {
  open: boolean
  onOpenChange: (open: boolean) => void
  code?: EnrolCode
  error: unknown
  pending: boolean
  onRegenerate: () => void
  pendingDevices: number
}) {
  const { t } = useTranslation()
  const { code, error, pending } = props
  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      <DialogContent className="sm:max-w-xl" closeLabel={t('common.close')}>
        <DialogHeader>
          <DialogTitle>{t('devices.enrol.title')}</DialogTitle>
          <DialogDescription>{t('devices.enrol.description')}</DialogDescription>
        </DialogHeader>
        <div className="grid items-start gap-6 sm:grid-cols-[auto_1fr]">
          {code && !pending ? (
            <EnrolQr key={code.enrol_code} code={code} />
          ) : (
            <div className="flex flex-col items-center gap-3">
              <div className="grid size-60 place-items-center rounded-xl border bg-white p-3 shadow-sm">
                {error && !pending ? (
                  <ErrorNotice error={error} onRetry={props.onRegenerate} className="flex-col p-0 text-center" />
                ) : (
                  <Skeleton className="size-52" aria-busy="true" />
                )}
              </div>
              <div className="h-5" />
            </div>
          )}
          <div className="flex flex-col gap-4 pt-2">
            <ol className="list-decimal space-y-2 pl-5 text-sm text-muted-foreground marker:font-semibold marker:text-foreground">
              <li>{t('devices.enrol.step1')}</li>
              <li>{t('devices.enrol.step2')}</li>
              <li>{t('devices.enrol.step3')}</li>
            </ol>
            {props.pendingDevices > 0 && (
              <p role="status" className="text-sm font-medium text-warning-foreground animate-in fade-in-0 motion-reduce:animate-none">
                {t('devices.enrol.pending', { count: props.pendingDevices })}
              </p>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={pending} onClick={props.onRegenerate}>
            <RefreshCw />
            {t('devices.enrol.regenerate')}
          </Button>
          <Button onClick={() => props.onOpenChange(false)}>{t('devices.enrol.done')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** One code: its QR and a countdown to `expires_at`, ticking every second (keyed by the code: a new one starts fresh). */
function EnrolQr({ code }: { code: EnrolCode }) {
  const { t } = useTranslation()
  const [now, setNow] = useState(Date.now)
  // ponytail: read against the browser clock; one off by minutes shows a wrong countdown (the server still decides).
  const expiresAt = Date.parse(code.expires_at)
  const left = Math.max(0, Math.floor((expiresAt - now) / 1000))
  useEffect(() => {
    const id = setInterval(() => {
      const n = Date.now()
      setNow(n)
      if (n >= expiresAt) clearInterval(id)
    }, 1000)
    return () => clearInterval(id)
  }, [expiresAt])
  const expired = left === 0
  return (
    <div className="flex flex-col items-center gap-3">
      <div className="grid size-60 place-items-center rounded-xl border bg-white p-3 shadow-sm">
        <QRCode
          value={code.enrol_code}
          size={208}
          level="M"
          title={t('devices.enrol.qr')}
          role="img"
          className={cn('size-52 transition-opacity duration-200', expired && 'opacity-10')}
        />
      </div>
      {expired ? (
        <FieldError className="max-w-60 text-center">
          {t('devices.enrol.expired')}
        </FieldError>
      ) : (
        <p role="timer" className="h-5 text-sm font-medium tabular-nums">
          {t('devices.enrol.validFor', { time: mmss(left) })}
        </p>
      )}
    </div>
  )
}

/** Approuver (with the fingerprint to match on the device's screen) or Révoquer, each behind a confirmation. */
function DeviceActionDialog({ kind, device, open, onOpenChange }: Action & { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation()
  const run = useDeviceMutation((_: void, send) => send<Device>({ method: 'POST', path: `${path('org', 'devices', device.id)}/${kind}` }))
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t(`devices.${kind}.title`, { label: device.label })}</AlertDialogTitle>
          <AlertDialogDescription>{t(`devices.${kind}.body`)}</AlertDialogDescription>
        </AlertDialogHeader>
        {kind === 'approve' && (
          <p className="rounded-lg border bg-muted/50 py-5 text-center font-mono text-3xl font-semibold tracking-[0.2em] tabular-nums">
            {device.fingerprint}
          </p>
        )}
        {run.error && <FieldError>{t(errorKey(run.error))}</FieldError>}
        <AlertDialogFooter>
          <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
          <Button
            variant={kind === 'revoke' ? 'destructive' : 'default'}
            disabled={run.isPending}
            onClick={() =>
              run.mutate(undefined, {
                onSuccess: () => {
                  toast.success(t(`devices.${kind}.done`, { label: device.label }))
                  onOpenChange(false)
                },
              })
            }
          >
            {t(`devices.${kind}.confirm`)}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
