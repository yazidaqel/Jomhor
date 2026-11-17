import { useState } from 'react'
import { BadgeCheck, BellRing, Download, MessageCircle, ShieldCheck, UserPlus, Users } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Kpi } from '@/components/kpi'
import { BrandEmpty, ErrorNotice, LoadingRows, STICKY_TABLE } from '@/components/page'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import { FieldError } from '@/components/ui/field'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useOrganizer } from '@/features/auth/organizer'
import { errorKey } from '@/lib/errors'
import { DEFAULT_ZONE, todayIn } from '@/lib/time'
import { cn } from '@/lib/utils'
import { useCrmExport, useMembersSummary, type MembersSummary } from './api'

/** Membres: counts only (no name, no phone on screen); ADMIN can download the CRM export (Lot 5 step 5). */
export function MembersTab() {
  const { t, i18n } = useTranslation()
  const summary = useMembersSummary()
  const { role } = useOrganizer()
  const n = (v: number) => v.toLocaleString(i18n.language)

  if (summary.isPending) return <LoadingRows className="pt-2" />
  if (summary.isError) return <ErrorNotice error={summary.error} onRetry={() => void summary.refetch()} />
  const s = summary.data
  return (
    <div className="flex flex-col gap-6 pt-2">
      {/* ADMIN only (04 role line: the export is 403 for every other role). */}
      {role === 'ADMIN' && (
        <div className="flex justify-end">
          <ExportDialog summary={s} />
        </div>
      )}
      <div className={cn('grid gap-4 sm:grid-cols-2', s.paid_required ? 'xl:grid-cols-5' : 'xl:grid-cols-4')}>
        <Kpi index={0} icon={Users} label={t('crm.members.kpi.members')} value={n(s.members)} />
        {s.paid_required && (
          <Kpi index={1} icon={BadgeCheck} label={t('crm.members.kpi.paid')} value={n(s.paid)}>
            {t('crm.members.kpi.paidOf', { n: n(s.members) })}
          </Kpi>
        )}
        <Kpi index={s.paid_required ? 2 : 1} icon={UserPlus} label={t('crm.members.kpi.joined7d')} value={n(s.joined_7d)} />
        <Kpi index={s.paid_required ? 3 : 2} icon={BellRing} label={t('crm.members.kpi.push')} value={n(s.marketing_push)}>
          {t('crm.members.kpi.reachable', { n: n(s.reachable_push) })}
        </Kpi>
        <Kpi index={s.paid_required ? 4 : 3} icon={MessageCircle} label={t('crm.members.kpi.whatsapp')} value={n(s.marketing_whatsapp)} />
      </div>
      <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
        <CardHeader className="border-b pb-5">
          <CardTitle role="heading" aria-level={2}>
            {t('crm.members.tiers.title')}
          </CardTitle>
          <CardDescription>
            {t('crm.members.tiers.description')} {t('crm.members.countsOnly')}
          </CardDescription>
        </CardHeader>
        {s.tiers.length === 0 ? (
          <BrandEmpty title={t('crm.members.tiers.emptyTitle')} description={t('crm.members.tiers.empty')} />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('crm.members.tiers.tier')}</TableHead>
                <TableHead className="text-right">{t('crm.members.tiers.members')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {s.tiers.map((tier) => (
                <TableRow key={tier.code}>
                  <TableCell className="font-medium" dir="auto">
                    {tier.name}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{n(tier.members)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
    </div>
  )
}

/** "Exporter (CSV)": who is in the file, how many with a phone, the CNDP reminder, then the download. */
function ExportDialog({ summary }: { summary: MembersSummary }) {
  const { t, i18n } = useTranslation()
  const [open, setOpen] = useState(false)
  const csv = useCrmExport()
  const n = (v: number) => v.toLocaleString(i18n.language)
  const onDownload = () =>
    csv.mutate(`fans-${todayIn(DEFAULT_ZONE)}.csv`, {
      onSuccess: () => {
        toast.success(t('crm.export.done'), { id: 'crm-export' })
        setOpen(false)
      },
    })
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        setOpen(o)
        if (!o) csv.reset()
      }}
    >
      <DialogTrigger asChild>
        <Button variant="outline">
          <Download />
          {t('crm.export.action')}
        </Button>
      </DialogTrigger>
      <DialogContent closeLabel={t('common.close')}>
        <DialogHeader>
          <DialogTitle>{t('crm.export.title')}</DialogTitle>
          <DialogDescription className="text-base font-medium text-foreground tabular-nums">
            {t('crm.export.counts', { count: summary.exportable, fans: n(summary.exportable), phones: n(summary.marketing_whatsapp) })}
          </DialogDescription>
        </DialogHeader>
        <div className="flex gap-3 rounded-lg border bg-muted/40 p-4 text-sm text-muted-foreground">
          <ShieldCheck className="mt-0.5 size-4 shrink-0 text-foreground/70" aria-hidden="true" />
          <p>{t('crm.export.notice')}</p>
        </div>
        {csv.isError && <FieldError>{t(errorKey(csv.error))}</FieldError>}
        <DialogFooter>
          <DialogClose asChild>
            <Button type="button" variant="outline">
              {t('common.cancel')}
            </Button>
          </DialogClose>
          <Button disabled={csv.isPending || summary.exportable === 0} onClick={onDownload}>
            <Download />
            {t('crm.export.download')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
