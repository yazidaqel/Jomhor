import { useState } from 'react'
import { Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { BrandEmpty, ErrorNotice, LoadingRows, STICKY_TABLE } from '@/components/page'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCanWrite } from '@/features/auth/organizer'
import { EDITABLE, type EventDetail } from '@/features/events/api'
import { errorKey } from '@/lib/errors'
import { formatDh } from '@/lib/money'
import { cn } from '@/lib/utils'
import { discountLabel, scopeLabel, usePromoCodes, usePromoMutation, type PromoCode } from './api'
import { PromoSheet } from './PromoSheet'

const NOT_EDITABLE_ID = 'promos-not-editable'

/**
 * B2 "Codes promo" (DESIGN §Lot 5 step 4 adjustments): the event's codes with their uses and the discount they gave.
 * ADMIN and OPS create (event DRAFT or ON_SALE, 04 POST), change the caps and switch a code on or off; FINANCE reads.
 */
export function PromosTab({ event }: { event: EventDetail }) {
  const { t, i18n } = useTranslation()
  const eventId = String(event.id)
  const canWrite = useCanWrite()
  const codes = usePromoCodes(eventId)
  const creatable = EDITABLE.includes(event.status)
  // `n` counts openings: the sheet's key, so each opening starts from a fresh form.
  const [sheet, setSheet] = useState<{ open: boolean; code?: PromoCode; n: number }>({ open: false, n: 0 })
  const open = (code?: PromoCode) => setSheet((s) => ({ open: true, code, n: s.n + 1 }))
  const n = (v: number) => v.toLocaleString(i18n.language)
  const dh = (centimes: number) => t('sales.amount', { amount: formatDh(centimes, i18n.language) })

  return (
    <Card className={cn('gap-0 overflow-hidden pb-0', STICKY_TABLE)}>
      <CardHeader className="border-b pb-5">
        <CardTitle role="heading" aria-level={2}>
          {t('promos.title')}
        </CardTitle>
        <CardDescription id={canWrite && !creatable ? NOT_EDITABLE_ID : undefined}>
          {canWrite && !creatable ? t('promos.notEditable') : t('promos.description')}
        </CardDescription>
        {canWrite && (
          <CardAction>
            <Button onClick={() => open()} disabled={!creatable} aria-describedby={creatable ? undefined : NOT_EDITABLE_ID}>
              <Plus />
              {t('promos.new')}
            </Button>
          </CardAction>
        )}
      </CardHeader>
      {codes.isPending ? (
        <LoadingRows className="p-5" />
      ) : codes.isError ? (
        <ErrorNotice error={codes.error} onRetry={() => void codes.refetch()} />
      ) : codes.data.length === 0 ? (
        <BrandEmpty title={t('promos.emptyTitle')} description={t(canWrite ? 'promos.empty' : 'promos.emptyReadOnly')} />
      ) : (
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>{t('promos.columns.code')}</TableHead>
              <TableHead>{t('promos.columns.discount')}</TableHead>
              <TableHead>{t('promos.columns.scope')}</TableHead>
              <TableHead>{t('promos.columns.uses')}</TableHead>
              <TableHead className="text-right">{t('promos.columns.perAccount')}</TableHead>
              <TableHead className="text-right">{t('promos.columns.given')}</TableHead>
              <TableHead>{t('promos.columns.active')}</TableHead>
              {canWrite && (
                <TableHead>
                  <span className="sr-only">{t('promos.columns.actions')}</span>
                </TableHead>
              )}
            </TableRow>
          </TableHeader>
          <TableBody>
            {codes.data.map((c) => (
              <TableRow key={c.id} className={cn(!c.active && 'text-muted-foreground')}>
                <TableCell className="font-mono font-semibold tracking-wide">{c.code}</TableCell>
                <TableCell className="tabular-nums">{discountLabel(c, t, i18n.language)}</TableCell>
                <TableCell>
                  {scopeLabel(c, t)}
                </TableCell>
                <TableCell className="tabular-nums">
                  {t('promos.uses', { count: c.uses.paid, paid: n(c.uses.paid), held: n(c.uses.held), max: c.max_uses === null ? t('promos.noCap') : n(c.max_uses) })}
                </TableCell>
                <TableCell className="text-right tabular-nums">{n(c.per_account_max)}</TableCell>
                <TableCell className="text-right tabular-nums">{dh(c.discount_mad)}</TableCell>
                <TableCell>
                  <ActiveSwitch eventId={eventId} code={c} readOnly={!canWrite} />
                </TableCell>
                {canWrite && (
                  <TableCell className="text-right">
                    <Button size="sm" variant="outline" aria-label={t('promos.editLabel', { code: c.code })} onClick={() => open(c)}>
                      {t('common.edit')}
                    </Button>
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {canWrite && (
        <PromoSheet key={sheet.n} eventId={eventId} code={sheet.code} open={sheet.open} onOpenChange={(o) => setSheet((s) => ({ ...s, open: o }))} />
      )}
    </Card>
  )
}

/** "Actif": PATCH `{active}` alone; shown as is, disabled, for FINANCE. */
function ActiveSwitch({ eventId, code, readOnly }: { eventId: string; code: PromoCode; readOnly: boolean }) {
  const { t } = useTranslation()
  const save = usePromoMutation(eventId)
  // While the PATCH runs, the switch shows where it is going; the refetch then brings the server copy.
  const checked = save.isPending ? !!save.variables.body.active : code.active
  return (
    <Switch
      checked={checked}
      disabled={readOnly || save.isPending}
      aria-label={t('promos.activeLabel', { code: code.code })}
      onCheckedChange={(active) =>
        save.mutate(
          { method: 'PATCH', promoId: code.id, body: { active } },
          {
            onSuccess: (row) => toast.success(t(row.active ? 'promos.activated' : 'promos.deactivated', { code: row.code }), { id: `promo-${row.id}` }),
            onError: (e) => void toast.error(t(errorKey(e))),
          },
        )
      }
    />
  )
}
