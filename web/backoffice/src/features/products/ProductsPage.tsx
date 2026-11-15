import { useState } from 'react'
import { Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { BrandEmpty, ErrorNotice, LoadingRows, PageHeader, STICKY_TABLE } from '@/components/page'
import { FillBar } from '@/components/fill-bar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useCanWrite } from '@/features/auth/organizer'
import { formatDh } from '@/lib/money'
import { formatDateTime } from '@/lib/time'
import { useNotFoundNotice } from '@/lib/useNotFoundNotice'
import { cn } from '@/lib/utils'
import { PRODUCT_KINDS, productZone, useProducts, validDay, type Product, type ProductKind } from './api'
import { ProductSheet } from './ProductSheet'

export function OnSaleBadge({ onSale }: { onSale: boolean }) {
  const { t } = useTranslation()
  return (
    <Badge
      variant="outline"
      className={cn(
        'gap-1.5 rounded-full px-2.5 font-medium',
        onSale ? 'border-success-border bg-success-muted text-success-foreground' : 'border-border bg-background text-muted-foreground',
      )}
    >
      <span className={cn('size-1.5 rounded-full', onSale ? 'bg-success' : 'bg-chart-4')} aria-hidden="true" />
      {t(onSale ? 'products.statuses.onSale' : 'products.statuses.offSale')}
    </Badge>
  )
}

/** B14 Abonnements & adhésions: tabs by kind, one table each, "Nouveau" sheet. ADMIN, OPS write; FINANCE reads. */
export function ProductsPage() {
  const { t } = useTranslation()
  const canWrite = useCanWrite()
  const notFound = useNotFoundNotice() // set by the product page on an unknown id
  const [search, setSearch] = useSearchParams()
  const kind: ProductKind = search.get('kind') === 'MEMBERSHIP' ? 'MEMBERSHIP' : 'PASS'
  const products = useProducts()
  // `n` counts openings: the sheet's key, so each opening starts from a fresh form.
  const [sheet, setSheet] = useState({ open: false, n: 0 })

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={t('products.title')}
        description={t('products.description')}
        actions={
          canWrite && (
            <Button onClick={() => setSheet((s) => ({ open: true, n: s.n + 1 }))}>
              <Plus />
              {t(`products.new.${kind}`)}
            </Button>
          )
        }
      />
      {notFound && <p className="text-sm text-destructive">{t('products.notFound')}</p>}
      {!canWrite && <p className="text-sm text-muted-foreground">{t('products.readOnly')}</p>}
      <Tabs value={kind} onValueChange={(k) => setSearch(k === 'PASS' ? {} : { kind: k }, { replace: true })}>
        <TabsList>
          {PRODUCT_KINDS.map((k) => (
            <TabsTrigger key={k} value={k}>
              {t(`products.tabs.${k}`)}
            </TabsTrigger>
          ))}
        </TabsList>
        {PRODUCT_KINDS.map((k) => (
          <TabsContent key={k} value={k}>
            <Card className={cn('mt-2 gap-0 overflow-hidden py-0', STICKY_TABLE)}>
              {products.isPending ? (
                <LoadingRows className="p-5" />
              ) : products.isError ? (
                <ErrorNotice error={products.error} onRetry={() => void products.refetch()} />
              ) : (
                <ProductTable kind={k} rows={products.data.filter((p) => p.kind === k)} />
              )}
            </Card>
          </TabsContent>
        ))}
      </Tabs>
      {canWrite && (
        <ProductSheet
          key={sheet.n}
          kind={kind}
          firstMembership={!!products.data && !products.data.some((p) => p.kind === 'MEMBERSHIP')}
          open={sheet.open}
          onOpenChange={(open) => setSheet((s) => ({ ...s, open }))}
        />
      )}
    </div>
  )
}

function ProductTable({ kind, rows }: { kind: ProductKind; rows: Product[] }) {
  const { t, i18n } = useTranslation()
  if (rows.length === 0) return <BrandEmpty title={t(`products.emptyTitle.${kind}`)} description={t(`products.empty.${kind}`)} />
  const pass = kind === 'PASS'
  return (
    <Table>
      <TableHeader>
        <TableRow className="hover:bg-transparent">
          <TableHead>{t('products.columns.name')}</TableHead>
          <TableHead className="text-right">{t('products.columns.price')}</TableHead>
          <TableHead className="text-right">{t(pass ? 'products.columns.sold' : 'products.columns.validUntil')}</TableHead>
          <TableHead>{t('products.columns.status')}</TableHead>
          <TableHead>{t('products.columns.close')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((p) => {
          const zone = productZone(p)
          return (
            <TableRow key={p.id}>
              <TableCell>
                <Link
                  to={`/products/${encodeURIComponent(p.id)}`}
                  dir="auto"
                  className="rounded-sm font-medium outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50"
                >
                  {p.name}
                </Link>
                {p.series && p.zone && (
                  <div className="text-xs text-muted-foreground">
                    <bdi>{p.series.name}</bdi> · <bdi>{p.zone.name}</bdi>
                  </div>
                )}
              </TableCell>
              <TableCell className="text-right tabular-nums">{t('products.amount', { amount: formatDh(p.price_mad, i18n.language) })}</TableCell>
              <TableCell className="text-right tabular-nums">
                {pass ? (
                  <>
                    <div>{t('products.soldOfQuota', { sold: p.sold, quota: p.quota ?? 0 })}</div>
                    <FillBar paid={p.sold} quota={p.quota ?? 0} className="mt-1.5 ml-auto w-24" />
                  </>
                ) : (
                  p.valid_until && validDay(p.valid_until, i18n.language)
                )}
              </TableCell>
              <TableCell>
                <OnSaleBadge onSale={p.on_sale} />
              </TableCell>
              <TableCell className="text-muted-foreground tabular-nums">
                {formatDateTime(p.sales_close_at, i18n.language, zone)}
              </TableCell>
            </TableRow>
          )
        })}
      </TableBody>
    </Table>
  )
}
