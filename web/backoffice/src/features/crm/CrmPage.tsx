import { useTranslation } from 'react-i18next'
import { PageHeader } from '@/components/page'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { CampaignsTab } from './CampaignsTab'
import { MembersTab } from './MembersTab'

/** B9 Fans · CRM (Lot 4): member counts and campaigns. ADMIN, OPS write; FINANCE reads. The CSV export (Lot 5 step 5) is ADMIN only. */
export function CrmPage() {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('crm.title')} description={t('crm.description')} />
      <Tabs defaultValue="members">
        <TabsList>
          <TabsTrigger value="members">{t('crm.tabs.members')}</TabsTrigger>
          <TabsTrigger value="campaigns">{t('crm.tabs.campaigns')}</TabsTrigger>
        </TabsList>
        <TabsContent value="members">
          <MembersTab />
        </TabsContent>
        <TabsContent value="campaigns">
          <CampaignsTab />
        </TabsContent>
      </Tabs>
    </div>
  )
}
