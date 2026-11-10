import { BellRing, MessageCircle, UserPlus, Users } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Kpi } from '@/components/kpi'
import { BrandEmpty, ErrorNotice, LoadingRows, STICKY_TABLE } from '@/components/page'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { useMembersSummary } from './api'

/** Membres: counts only (no name, no phone; the export is Lot 5). */
export function MembersTab() {
  const { t, i18n } = useTranslation()
  const summary = useMembersSummary()
  const n = (v: number) => v.toLocaleString(i18n.language)

  if (summary.isPending) return <LoadingRows className="pt-2" />
  if (summary.isError) return <ErrorNotice error={summary.error} onRetry={() => void summary.refetch()} />
  const s = summary.data
  return (
    <div className="flex flex-col gap-6 pt-2">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi index={0} icon={Users} label={t('crm.members.kpi.members')} value={n(s.members)} />
        <Kpi index={1} icon={UserPlus} label={t('crm.members.kpi.joined7d')} value={n(s.joined_7d)} />
        <Kpi index={2} icon={BellRing} label={t('crm.members.kpi.push')} value={n(s.marketing_push)}>
          {t('crm.members.kpi.reachable', { n: n(s.reachable_push) })}
        </Kpi>
        <Kpi index={3} icon={MessageCircle} label={t('crm.members.kpi.whatsapp')} value={n(s.marketing_whatsapp)} />
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
