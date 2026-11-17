import { useState } from 'react'
import { IdCard, Plus, Tags } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useParams } from 'react-router'
import { BrandEmpty, ErrorNotice, PageHeader } from '@/components/page'
import { FillBar } from '@/components/fill-bar'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useCanWrite } from '@/features/auth/organizer'
import { useEvent, usePrices, type EventStatus, type Price } from '@/features/events/api'
import { EventPicker } from '@/features/events/EventPicker'
import { StatusBadge } from '@/features/events/EventsPage'
import { PrintPage } from '@/features/pos/PrintPage'
import { ApiError } from '@/lib/api'
import { formatDateTime } from '@/lib/time'
import { invitationsPath, inviteCategories, useInvitationPrint } from './api'
import { Batches } from './Batches'
import { IssueSheet } from './IssueSheet'

// B7 Accréditations (DESIGN §Lot 5 step 4 adjustments): an accreditation is an invitation (Q202), a named FREE ticket
// from an INVITE category. ADMIN and OPS issue and print; ADMIN revokes; FINANCE reads.

/** 04 Q207: issued while the event is on sale or its gates are open (until `transfer_until`, which the server checks). */
const OPEN: readonly EventStatus[] = ['ON_SALE', 'GATES_OPEN']

/** Sidebar "Accréditations": the organizer's events, each opening its B7 page. */
export function InvitationsIndexPage() {
  const { t } = useTranslation()
  return <EventPicker title={t('invitations.title')} description={t('invitations.indexDescription')} empty={t('invitations.noEvents')} to={invitationsPath} />
}

/** B7: `/events/:id/invitations`. */
export function InvitationsPage() {
  const id = useParams().id ?? ''
  // Same id rule as the event page: integers only, `/01` and `/1` are one query key.
  if (!/^\d+$/.test(id)) return <Navigate to="/invitations" replace />
  return <EventInvitations id={String(Number(id))} />
}

/** The 80 mm slips of one invitation (the B11 slip), outside the shell chrome. FINANCE gets 403 on it: sent back. */
export function InvitationPrintPage() {
  const { t } = useTranslation()
  const back = invitationsPath(useParams().id ?? '')
  if (!useCanWrite()) return <Navigate to={back} replace />
  return <PrintPage useData={useInvitationPrint} back={{ to: back, label: t('invitations.print.back') }} />
}

const isNotFound = (e: unknown) => e instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(e.code)

function EventInvitations({ id }: { id: string }) {
  const { t, i18n } = useTranslation()
  const event = useEvent(id)
  const prices = usePrices(id)
  const canWrite = useCanWrite()
  const [issuing, setIssuing] = useState(false)
  if (isNotFound(event.error)) return <Navigate to="/invitations" replace />
  if (event.isPending) return <Skeleton aria-busy="true" className="h-16 w-80" />
  if (!event.data) return <ErrorNotice error={event.error} onRetry={() => void event.refetch()} />

  const e = event.data
  const categories = inviteCategories(prices.data ?? [])
  const open = OPEN.includes(e.status)
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        crumbs={[{ label: t('invitations.title'), to: '/invitations' }, { label: e.title }]}
        title={t('invitations.title')}
        badge={<StatusBadge status={e.status} />}
        description={t('sales.eventLine', { date: formatDateTime(e.starts_at, i18n.language, e.timezone), zone: e.timezone })}
        actions={
          canWrite &&
          categories.length > 0 && (
            <Button onClick={() => setIssuing(true)} disabled={!open} aria-describedby={open ? undefined : 'invitations-not-open'}>
              <Plus />
              {t('invitations.new')}
            </Button>
          )
        }
      />
      {canWrite && categories.length > 0 && !open && (
        <p id="invitations-not-open" className="-mt-3 text-sm text-muted-foreground">
          {t(e.status === 'DRAFT' ? 'invitations.notOpenDraft' : 'invitations.notOpenClosed')}
        </p>
      )}
      {prices.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-32 rounded-xl" />
          ))}
        </div>
      ) : prices.isError ? (
        <Card className="py-0">
          <ErrorNotice error={prices.error} onRetry={() => void prices.refetch()} />
        </Card>
      ) : categories.length === 0 ? (
        <Card>
          <BrandEmpty
            title={t('invitations.emptyTitle')}
            description={t('invitations.emptyDescription')}
            action={
              <Button variant="outline" asChild>
                <Link to={`/events/${encodeURIComponent(e.id)}?tab=prices`}>
                  <Tags />
                  {t('invitations.emptyAction')}
                </Link>
              </Button>
            }
          />
        </Card>
      ) : (
        <section aria-label={t('invitations.categories')} className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {categories.map((p) => (
            <CategoryCard key={p.id} price={p} />
          ))}
        </section>
      )}
      <Batches event={e} />
      {canWrite && categories.length > 0 && <IssueSheet event={e} categories={categories} open={issuing} onOpenChange={setIssuing} />}
    </div>
  )
}

/** One INVITE category: its name is the kind ("Presse", "Invités"), its zone, issued of quota. */
function CategoryCard({ price }: { price: Price }) {
  const { t, i18n } = useTranslation()
  const full = price.sold >= price.quota
  return (
    <Card className="gap-3">
      <CardHeader className="grid-cols-[1fr_auto]">
        <CardTitle className="truncate text-base">{price.name}</CardTitle>
        <CardDescription className="truncate">{price.zone.name}</CardDescription>
        <span className="col-start-2 row-span-2 row-start-1 flex size-9 items-center justify-center self-start rounded-lg bg-muted">
          <IdCard className="size-4 text-foreground/70" aria-hidden="true" />
        </span>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        <p className="flex items-baseline gap-1.5 tabular-nums">
          <span className="text-2xl font-semibold tracking-tight">{price.sold.toLocaleString(i18n.language)}</span>
          <span className="text-sm text-muted-foreground">{t('invitations.issuedOf', { quota: price.quota.toLocaleString(i18n.language) })}</span>
        </p>
        <FillBar paid={price.sold} quota={price.quota} />
        <p className={full ? 'text-xs font-medium text-destructive' : 'text-xs text-muted-foreground'}>
          {full ? t('invitations.full') : t('invitations.remaining', { count: price.quota - price.sold })}
        </p>
      </CardContent>
    </Card>
  )
}
