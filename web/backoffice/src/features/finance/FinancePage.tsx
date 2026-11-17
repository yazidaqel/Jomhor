import { useTranslation } from 'react-i18next'
import { Navigate, useNavigate } from 'react-router'
import { ErrorNotice, PageHeader } from '@/components/page'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useOrganizer, type Role } from '@/features/auth/organizer'
import { useVenues } from '@/features/venues/api'
import { DEFAULT_ZONE } from '@/lib/time'
import { CashTab } from './CashPage'
import { DepositsTab } from './DepositsTab'
import { SettlementsTab } from './SettlementsTab'
import { SETTLEMENT_ROLES } from './api'

export type FinanceTab = 'settlements' | 'cash' | 'deposits'
/** Relevés first, for the roles that may read them (04: ADMIN, FINANCE; OPS gets 403). */
const tabsOf = (role: Role): FinanceTab[] => (SETTLEMENT_ROLES.includes(role) ? ['settlements', 'cash', 'deposits'] : ['cash', 'deposits'])

/** `/finance`: the role's first tab (Relevés for ADMIN and FINANCE, Encaissements for OPS). */
export function FinanceHome() {
  return <Navigate to={`/finance/${tabsOf(useOrganizer().role)[0]}`} replace />
}

/**
 * B8 Finance: tabs Relevés (`/finance/settlements`, Lot 5 step 5b, ADMIN and FINANCE only), Encaissements
 * (`/finance/cash`, Lot 3) and Dépôts (`/finance/deposits`, Lot 5 step 5), one route each. ADMIN, OPS, FINANCE (the
 * sidebar shows it to them; the shell keeps CASHIER out, GATE and SUPERVISOR never get in). Dates are the organizer's venue zone: the one zone its venues share, else Africa/Casablanca (04: the report's
 * default).
 */
export function FinancePage({ tab }: { tab: FinanceTab }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const tabs = tabsOf(useOrganizer().role)
  const venues = useVenues()
  if (!tabs.includes(tab)) return <Navigate to={`/finance/${tabs[0]}`} replace />
  if (venues.isPending)
    return (
      <div aria-busy="true">
        <span className="sr-only">{t('common.loading')}</span>
        <Skeleton className="h-16 w-80" />
      </div>
    )
  // No silent fallback: without the venues the zone, so every day boundary, could be wrong.
  if (venues.isError) return <ErrorNotice error={venues.error} onRetry={() => void venues.refetch()} />
  const zones = [...new Set(venues.data.map((v) => v.timezone))]
  const zone = zones.length === 1 ? zones[0]! : DEFAULT_ZONE
  return (
    <div className="flex flex-col gap-6">
      <PageHeader crumbs={[{ label: t('nav.finance') }, { label: t(`finance.tabs.${tab}`) }]} title={t('nav.finance')} description={t(`finance.${tab}.description`, { zone })} />
      <Tabs value={tab} onValueChange={(v) => void navigate(`/finance/${v}`)} className="gap-6">
        <TabsList>
          {tabs.map((id) => (
            <TabsTrigger key={id} value={id}>
              {t(`finance.tabs.${id}`)}
            </TabsTrigger>
          ))}
        </TabsList>
        {/* key: a new zone starts a new default range. Settlements mount only while active (Radix), and OPS never gets
            there (redirect above): no 403 call. */}
        <TabsContent value="settlements" className="flex flex-col gap-6">
          <SettlementsTab key={zone} zone={zone} />
        </TabsContent>
        <TabsContent value="cash" className="flex flex-col gap-6">
          <CashTab key={zone} zone={zone} />
        </TabsContent>
        <TabsContent value="deposits" className="flex flex-col gap-6">
          <DepositsTab key={zone} zone={zone} />
        </TabsContent>
      </Tabs>
    </div>
  )
}
