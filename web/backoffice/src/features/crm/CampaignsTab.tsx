import { useState } from 'react'
import { Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
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
import { FieldError } from '@/components/ui/field'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCanWrite } from '@/features/auth/organizer'
import { useEvents } from '@/features/events/api'
import { errorKey } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import { cn } from '@/lib/utils'
import { ApiError, path } from '@/lib/api'
import { useCampaignMutation, useCampaigns, zoneOf, type CampaignRow, type CampaignStatus } from './api'
import { CampaignSheet } from './CampaignSheet'

const STATUS_STYLE: Record<CampaignStatus, string> = {
  DRAFT: 'border-border bg-background text-muted-foreground',
  SCHEDULED: 'border-warning-border bg-warning-muted text-warning-foreground',
  SENDING: 'border-warning-border bg-warning-muted text-warning-foreground',
  SENT: 'border-success-border bg-success-muted text-success-foreground',
  CANCELLED: 'border-border bg-muted text-muted-foreground',
}

export function CampaignsTab() {
  const { t, i18n } = useTranslation()
  const canWrite = useCanWrite()
  const campaigns = useCampaigns()
  const events = useEvents()
  // `n` counts openings: the sheet's key, so each opening starts from a fresh form.
  const [sheet, setSheet] = useState<{ open: boolean; id?: string; n: number }>({ open: false, n: 0 })
  const [cancelling, setCancelling] = useState<CampaignRow | null>(null)
  const open = (id?: string) => setSheet((s) => ({ open: true, id, n: s.n + 1 }))
  const n = (v: number) => v.toLocaleString(i18n.language)

  const when = (c: CampaignRow) => {
    const iso = c.status === 'SENT' ? c.finished_at : c.scheduled_at
    const zone = zoneOf(events.data, c.event_id)
    return iso && zone ? formatDateTime(iso, i18n.language, zone) : null
  }
  const eventTitle = (id: number | null) => events.data?.find((e) => e.id === id)?.title

  return (
    <Card className={cn('mt-2 gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
      <CardHeader className="border-b pb-5">
        <CardTitle role="heading" aria-level={2}>
          {t('crm.campaigns.title')}
        </CardTitle>
        <CardDescription>{t('crm.campaigns.description')}</CardDescription>
        {canWrite && (
          <CardAction>
            <Button onClick={() => open()}>
              <Plus />
              {t('crm.campaigns.new')}
            </Button>
          </CardAction>
        )}
      </CardHeader>
      {campaigns.isPending ? (
        <LoadingRows className="p-5" />
      ) : campaigns.isError ? (
        <ErrorNotice error={campaigns.error} onRetry={() => void campaigns.refetch()} />
      ) : campaigns.data.length === 0 ? (
        <BrandEmpty title={t('crm.campaigns.emptyTitle')} description={t('crm.campaigns.empty')} />
      ) : (
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>{t('crm.campaigns.columns.title')}</TableHead>
              <TableHead>{t('crm.campaigns.columns.channel')}</TableHead>
              <TableHead>{t('crm.campaigns.columns.audience')}</TableHead>
              <TableHead>{t('crm.campaigns.columns.status')}</TableHead>
              <TableHead>{t('crm.campaigns.columns.when')}</TableHead>
              <TableHead className="text-right">{t('crm.campaigns.columns.recipients')}</TableHead>
              <TableHead className="text-right">{t('crm.campaigns.columns.sent')}</TableHead>
              <TableHead className="text-right">{t('crm.campaigns.columns.failed')}</TableHead>
              {canWrite && (
                <TableHead className="text-right">
                  <span className="sr-only">{t('crm.campaigns.columns.actions')}</span>
                </TableHead>
              )}
            </TableRow>
          </TableHeader>
          <TableBody>
            {campaigns.data.map((c) => (
              <TableRow key={c.id}>
                <TableCell className="max-w-64 truncate font-medium" dir="auto">
                  {c.title}
                </TableCell>
                <TableCell>{t(`crm.channels.${c.channel}`)}</TableCell>
                <TableCell className="text-muted-foreground">
                  {c.audience === 'EVENT_HOLDERS' && eventTitle(c.event_id) !== undefined ? (
                    <>
                      {t('crm.campaigns.holders')} · <bdi>{eventTitle(c.event_id)}</bdi>
                    </>
                  ) : (
                    t(`crm.audiences.${c.audience}`)
                  )}
                </TableCell>
                <TableCell>
                  <Badge variant="outline" className={cn('rounded-full px-2.5 font-medium', STATUS_STYLE[c.status])}>
                    {t(`crm.statuses.${c.status}`)}
                  </Badge>
                </TableCell>
                <TableCell className="text-muted-foreground">{when(c) ?? t('crm.campaigns.noDate')}</TableCell>
                <TableCell className="text-right tabular-nums">{n(c.recipients)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(c.sent)}</TableCell>
                <TableCell className={cn('text-right tabular-nums', c.failed > 0 && 'text-destructive')}>{n(c.failed)}</TableCell>
                {canWrite && (
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      {c.status === 'DRAFT' && (
                        <Button size="sm" variant="outline" aria-label={t('crm.campaigns.editLabel', { title: c.title })} onClick={() => open(c.id)}>
                          {t('crm.campaigns.edit')}
                        </Button>
                      )}
                      {c.status === 'SCHEDULED' && (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                          aria-label={t('crm.campaigns.cancelLabel', { title: c.title })}
                          onClick={() => setCancelling(c)}
                        >
                          {t('crm.campaigns.cancel')}
                        </Button>
                      )}
                    </div>
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {canWrite && (
        <CampaignSheet key={sheet.n} open={sheet.open} id={sheet.id} onOpenChange={(o) => setSheet((s) => ({ ...s, open: o }))} />
      )}
      {cancelling && <CancelDialog key={cancelling.id} campaign={cancelling} onClose={() => setCancelling(null)} />}
    </Card>
  )
}

function CancelDialog({ campaign, onClose }: { campaign: CampaignRow; onClose: () => void }) {
  const { t } = useTranslation()
  const cancel = useCampaignMutation()
  return (
    <AlertDialog open onOpenChange={(o) => !o && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle dir="auto">{t('crm.campaigns.cancelTitle', { title: campaign.title })}</AlertDialogTitle>
          <AlertDialogDescription>{t('crm.campaigns.cancelBody')}</AlertDialogDescription>
        </AlertDialogHeader>
        {cancel.error && <FieldError>{t(errorKey(cancel.error))}</FieldError>}
        <AlertDialogFooter>
          <AlertDialogCancel>{t('crm.campaigns.keep')}</AlertDialogCancel>
          <Button
            variant="destructive"
            disabled={cancel.isPending}
            onClick={() =>
              cancel.mutate(
                { method: 'POST', path: `${path('org', 'campaigns', campaign.id)}/cancel` },
                {
                  onSuccess: () => {
                    toast.success(t('crm.campaigns.cancelled'))
                    onClose()
                  },
                  // Sent meanwhile: say so and close; the list is refetched by the mutation.
                  onError: (e) => {
                    if (!(e instanceof ApiError && e.code === 'CAMPAIGN_STATUS')) return
                    toast.error(t(errorKey(e)))
                    onClose()
                  },
                },
              )
            }
          >
            {t('crm.campaigns.cancelConfirm')}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
