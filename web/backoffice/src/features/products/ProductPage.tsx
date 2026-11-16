import { useState, type ReactNode } from 'react'
import { ArrowRight, BadgeCheck, Banknote, CalendarClock, CirclePause, Hourglass, Pencil, Users, Wallet } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, useParams } from 'react-router'
import { Kpi } from '@/components/kpi'
import { ErrorNotice, LoadingRows, PageHeader, STICKY_TABLE } from '@/components/page'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCanWrite } from '@/features/auth/organizer'
import { StatusBadge } from '@/features/events/EventsPage'
import { fr } from '@/i18n/fr'
import { ApiError } from '@/lib/api'
import { formatDh } from '@/lib/money'
import { formatDateTime } from '@/lib/time'
import { cn } from '@/lib/utils'
import { missingPlaces, productZone, useProduct, validDay, type MembershipFigures, type PassFigures, type ProductDetail } from './api'
import { OnSaleBadge } from './ProductsPage'
import { ProductSheet } from './ProductSheet'

type Reason = keyof typeof fr.sales.refunds.reasons

/** B14 detail `/products/:id`: figures, the per-match pass tickets, the refunds to make. FINANCE reads only. */
export function ProductPage() {
  const id = useParams().id!
  const product = useProduct(id)
  if (product.error instanceof ApiError && ['NOT_FOUND', 'BAD_REQUEST'].includes(product.error.code))
    return <Navigate to="/products" replace state={{ notFound: true }} />
  if (product.isPending) return <LoadingRows rows={6} />
  if (!product.data) return <ErrorNotice error={product.error} onRetry={() => void product.refetch()} className="p-0" />
  return <ProductView product={product.data} />
}

function ProductView({ product: p }: { product: ProductDetail }) {
  const { t, i18n } = useTranslation()
  const canWrite = useCanWrite()
  const [sheet, setSheet] = useState({ open: false, n: 0 })
  const zone = productZone(p)
  const dh = (centimes: number) => t('products.amount', { amount: formatDh(centimes, i18n.language) })
  const n = (v: number) => v.toLocaleString(i18n.language)

  const facts: [string, ReactNode][] = [
    [t('products.form.price'), dh(p.price_mad)],
    ...(p.series && p.zone
      ? ([
          [t('events.series.label'), <bdi key="s">{p.series.name}</bdi>],
          [t('products.form.zone'), <bdi key="z">{p.zone.name}</bdi>],
        ] as [string, ReactNode][])
      : []),
    ...(p.valid_until ? ([[t('products.form.validUntil'), validDay(p.valid_until, i18n.language)]] as [string, ReactNode][]) : []),
    [t('products.form.salesCloseAt'), formatDateTime(p.sales_close_at, i18n.language, zone)],
  ]

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        crumbs={[{ label: t('products.title'), to: p.kind === 'PASS' ? '/products' : '/products?kind=MEMBERSHIP' }, { label: p.name }]}
        title={<bdi>{p.name}</bdi>}
        badge={<OnSaleBadge onSale={p.on_sale} />}
        description={t(`products.kinds.${p.kind}`)}
        actions={
          canWrite && (
            <Button variant="outline" onClick={() => setSheet((s) => ({ open: true, n: s.n + 1 }))}>
              <Pencil />
              {t('common.edit')}
            </Button>
          )
        }
      />
      <dl className="flex flex-wrap gap-x-8 gap-y-3 rounded-xl border bg-card px-5 py-4 text-sm">
        {facts.map(([label, value]) => (
          <div key={label} className="flex flex-col gap-0.5">
            <dt className="text-xs text-muted-foreground">{label}</dt>
            <dd className="font-medium tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>

      {p.kind === 'PASS' ? (
        <PassFiguresView product={p} figures={p.figures as PassFigures} zone={zone} />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          <Kpi index={0} icon={BadgeCheck} label={t('products.kpi.paidMembers')} value={n((p.figures as MembershipFigures).paid_members)} />
          <Kpi index={1} icon={Banknote} label={t('products.kpi.revenue')} value={dh(p.figures.revenue_mad)} />
        </div>
      )}

      <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
        <CardHeader className="border-b pb-5">
          <CardTitle role="heading" aria-level={2}>
            {t('sales.refunds.title')}
          </CardTitle>
          <CardDescription>{t('sales.refunds.description')}</CardDescription>
        </CardHeader>
        {p.refunds_required.length === 0 ? (
          <p className="p-5 text-sm text-muted-foreground">{t('sales.refunds.empty')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('sales.refunds.at')}</TableHead>
                <TableHead>{t('sales.refunds.reason')}</TableHead>
                <TableHead>{t('sales.refunds.payment')}</TableHead>
                <TableHead>{t('sales.refunds.order')}</TableHead>
                <TableHead className="text-right">{t('sales.refunds.amount')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {/* Newest first, as the API sends them. */}
              {p.refunds_required.map((r) => (
                <TableRow key={`${r.payment_id} ${r.at}`}>
                  <TableCell className="tabular-nums">{formatDateTime(r.at, i18n.language, zone)}</TableCell>
                  <TableCell>{Object.hasOwn(fr.sales.refunds.reasons, r.reason) ? t(`sales.refunds.reasons.${r.reason as Reason}`) : r.reason}</TableCell>
                  <TableCell className="font-mono text-xs">{r.payment_id}</TableCell>
                  <TableCell className="font-mono text-xs">{r.order_id}</TableCell>
                  <TableCell className="text-right tabular-nums">{dh(r.amount_mad)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>

      {canWrite && <ProductSheet key={sheet.n} kind={p.kind} product={p} open={sheet.open} onOpenChange={(open) => setSheet((s) => ({ ...s, open }))} />}
    </div>
  )
}

function PassFiguresView({ product: p, figures: f, zone }: { product: ProductDetail; figures: PassFigures; zone: string }) {
  const { t, i18n } = useTranslation()
  const n = (v: number) => v.toLocaleString(i18n.language)
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-3">
        <Kpi index={0} icon={Users} label={t('products.kpi.active')} value={n(f.passes.active)}>
          {t('products.soldOfQuotaLong', { sold: p.sold, quota: p.quota ?? 0 })}
        </Kpi>
        <Kpi index={1} icon={Hourglass} label={t('products.kpi.pending')} value={n(f.passes.pending)} />
        <Kpi index={2} icon={Banknote} label={t('products.kpi.revenue')} value={t('products.amount', { amount: formatDh(f.revenue_mad, i18n.language) })} />
        {/* 3b: installments (Q195, Q197). */}
        <Kpi index={3} icon={CirclePause} label={t('products.kpi.suspended')} value={n(f.passes.suspended)} valueClassName={f.passes.suspended > 0 ? 'text-warning-foreground' : undefined} />
        <Kpi index={4} icon={CalendarClock} label={t('products.kpi.overdue')} value={n(f.installments.overdue)} valueClassName={f.installments.overdue > 0 ? 'text-warning-foreground' : undefined} />
        <Kpi index={5} icon={Wallet} label={t('products.kpi.due')} value={t('products.amount', { amount: formatDh(f.installments.due_mad, i18n.language) })} />
      </div>
      <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
        <CardHeader className="border-b pb-5">
          <CardTitle role="heading" aria-level={2}>
            {t('products.events.title')}
          </CardTitle>
          <CardDescription>{t('products.events.description')}</CardDescription>
        </CardHeader>
        {f.events.length === 0 ? (
          <p className="p-5 text-sm text-muted-foreground">{t('products.events.empty')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('products.events.match')}</TableHead>
                <TableHead>{t('products.events.date')}</TableHead>
                <TableHead className="text-right">{t('products.events.issued')}</TableHead>
                <TableHead className="text-right">{t('products.events.remaining')}</TableHead>
                <TableHead>
                  <span className="sr-only">{t('products.events.action')}</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {f.events.map((e) => {
                const short = e.issued < e.expected
                const missing = missingPlaces(e)
                return (
                  <TableRow key={e.event_id} className={cn(short && 'bg-warning-muted/60 hover:bg-warning-muted')}>
                    <TableCell>
                      <div className="flex flex-wrap items-center gap-2">
                        <Link
                          to={`/events/${encodeURIComponent(e.event_id)}`}
                          dir="auto"
                          className="rounded-sm font-medium outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50"
                        >
                          {e.title}
                        </Link>
                        <StatusBadge status={e.status} />
                      </div>
                    </TableCell>
                    <TableCell className="tabular-nums">{formatDateTime(e.starts_at, i18n.language, zone)}</TableCell>
                    <TableCell className={cn('text-right tabular-nums', short && 'font-semibold text-warning-foreground')}>
                      {t('products.events.issuedOf', { issued: e.issued, expected: e.expected })}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{n(e.pass_remaining)}</TableCell>
                    <TableCell className="text-right">
                      {missing > 0 ? (
                        <Button asChild size="sm" variant="outline" className="border-warning-border bg-background">
                          <Link to={`/events/${encodeURIComponent(e.event_id)}?tab=prices`}>
                            {t('products.events.add', { count: missing, zone: p.zone?.name ?? '' })}
                            <ArrowRight />
                          </Link>
                        </Button>
                      ) : (
                        short && <span className="text-xs text-muted-foreground">{t(e.status === 'DRAFT' ? 'products.events.atPublish' : 'products.events.issuing')}</span>
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
      </Card>
    </>
  )
}
