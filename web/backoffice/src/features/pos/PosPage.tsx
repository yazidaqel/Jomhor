import { useRef, useState, type ReactNode } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { AlertCircle, Banknote, Minus, Plus, Store } from 'lucide-react'
import { Controller, useForm, useWatch, type UseFormReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { BrandEmpty, ErrorNotice, LoadingRows, PageHeader } from '@/components/page'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useEvent, useEvents, usePrices, type EventSummary, type Price } from '@/features/events/api'
import { ApiError } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { formatDh } from '@/lib/money'
import { formatDateTime } from '@/lib/time'
import { useNow } from '@/lib/useNow'
import { cn } from '@/lib/utils'
import {
  changeDue,
  codeStatus,
  displayCode,
  holderField,
  normalizeCode,
  readCode,
  saleItems,
  saleSchema,
  useCodeLookup,
  useCollect,
  usePosSession,
  useSale,
  type CodeLookup,
  type PosSession,
  type SaleOrder,
  type SaleValues,
} from './api'
import { CloseSessionDialog, OpenSessionDialog } from './SessionDialogs'

// B11 Guichet (DESIGN §Lot 3 adjustments): the till session, a walk-up sale and a cash code's collect, each paid
// order then opened in the print view. Sale and collect need the caller's open till (else 409 POS_SESSION_REQUIRED).

/** Copy for a sale or collect error; PER_ACCOUNT_LIMIT says the cap. */
function useErrorMessage() {
  const { t } = useTranslation()
  return (e: unknown) =>
    e instanceof ApiError && e.code === 'PER_ACCOUNT_LIMIT' ? t('errors.PER_ACCOUNT_LIMIT', { limit: Number(e.details.limit) }) : t(errorKey(e))
}

/** Whether the caller has an open till: sale and collect are offered only then. */
const useTillOpen = () => !!usePosSession().data?.session

/** B11 Guichet: `/pos`. */
export function PosPage() {
  const { t } = useTranslation()
  const [tab, setTab] = useState('sale')
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('pos.title')} description={t('pos.description')} />
      <SessionBar />
      <Tabs value={tab} onValueChange={setTab} className="gap-4">
        <TabsList>
          <TabsTrigger value="sale">{t('pos.tabs.sale')}</TabsTrigger>
          <TabsTrigger value="code">{t('pos.tabs.code')}</TabsTrigger>
        </TabsList>
        {/* Kept mounted while hidden: the sale in progress (and its `sale_id`) survives a look at a code. */}
        <TabsContent value="sale" forceMount hidden={tab !== 'sale'}>
          <SaleTab />
        </TabsContent>
        <TabsContent value="code">
          <CodeTab />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function SessionBar() {
  const query = usePosSession()
  if (query.isPending) return <Skeleton className="h-[74px] w-full rounded-xl" />
  if (query.isError) return <Card className="py-0"><ErrorNotice error={query.error} onRetry={() => void query.refetch()} /></Card>
  return <SessionCard session={query.data.session} />
}

function SessionCard({ session: s }: { session: PosSession | null }) {
  const { t, i18n } = useTranslation()
  const [opening, setOpening] = useState(false)
  // The session is kept for the close dialog: once closed, the bar shows "Aucune caisse" behind the frozen result.
  const [closing, setClosing] = useState<{ open: boolean; session?: PosSession }>({ open: false })
  return (
    <Card className="py-4">
      <CardContent className="flex flex-wrap items-center gap-x-8 gap-y-3 px-5">
        <div className="flex min-w-0 items-center gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-muted">
            <Store className="size-5 text-foreground/70" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <div className="text-xs font-medium text-muted-foreground">{t('pos.session.title')}</div>
            <div className="truncate font-semibold">{s ? s.label : t('pos.session.none')}</div>
          </div>
        </div>
        {s ? (
          <>
            <div className="text-sm">
              <div className="text-muted-foreground">{t('pos.session.openedAt', { time: formatDateTime(s.opened_at, i18n.language) })}</div>
              <div className="text-muted-foreground tabular-nums">{t('pos.session.stats', { count: s.orders, tickets: s.tickets, prints: s.prints })}</div>
            </div>
            <div>
              <div className="text-xs font-medium text-muted-foreground">{t('pos.session.collected')}</div>
              <div className="text-xl font-semibold tabular-nums">{t('pos.amount', { amount: formatDh(s.collected_mad, i18n.language) })}</div>
            </div>
            <Button variant="outline" className="ml-auto" onClick={() => setClosing({ open: true, session: s })}>
              {t('pos.session.close')}
            </Button>
          </>
        ) : (
          <>
            <p className="text-sm text-muted-foreground">{t('pos.session.noneHint')}</p>
            <Button className="ml-auto" onClick={() => setOpening(true)}>
              {t('pos.session.open')}
            </Button>
          </>
        )}
      </CardContent>
      <OpenSessionDialog open={opening} onOpenChange={setOpening} />
      <CloseSessionDialog session={closing.session} open={closing.open} onOpenChange={(open) => setClosing((c) => ({ ...c, open }))} />
    </Card>
  )
}

// ---- Vente ----

const HOUR = 3_600_000

/**
 * Events a till may sell (Q49): on sale and before the sales close. The list has no `refund_cutoff_at`; it is
 * `starts_at − 60 min` (04-api publish), and the selected event's own `refund_cutoff_at` is checked again below.
 */
function SaleTab() {
  const { t } = useTranslation()
  const events = useEvents()
  const now = useNow(30_000)
  if (events.isPending) return <Card><LoadingRows className="px-5" /></Card>
  if (events.isError) return <Card className="py-0"><ErrorNotice error={events.error} onRetry={() => void events.refetch()} /></Card>
  const open = events.data.filter((e) => e.status === 'ON_SALE' && Date.parse(e.starts_at) - HOUR > now)
  if (open.length === 0) return <Card><BrandEmpty title={t('pos.tabs.sale')} description={t('pos.sale.noEvents')} /></Card>
  return <SaleForm events={open} />
}


/**
 * `sale_id` (→ `orders.id`) is drawn once per sale and sent again on every retry of it, so a sale whose answer was
 * lost is never made twice (the server answers 200 with that order); a new one only after a success or a reset
 * ("Nouvelle vente", another event). Two outcomes stop the form until the cashier acts:
 * - 422 TAKEN on `sale_id`: the sale was recorded in an earlier till (closed since): no second payment, "Nouvelle vente".
 * - an answer whose total is not the one on screen (a replay of a sale edited after its answer was lost): collect
 *   that total, then print.
 */
function SaleForm({ events }: { events: EventSummary[] }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const message = useErrorMessage()
  const sale = useSale()
  const saleId = useRef<string | null>(null)
  const form = useForm<SaleValues>({
    resolver: zodResolver(saleSchema(t)),
    mode: 'onTouched',
    defaultValues: { event_id: String(events[0]!.id), qty: {}, same_name: false, holders: [], received: '' },
  })
  const eventId = useWatch({ control: form.control, name: 'event_id' })
  const prices = usePrices(eventId)
  const [stop, setStop] = useState<Stop | null>(null)

  const reset = (event_id: string) => {
    form.reset({ event_id, qty: {}, same_name: false, holders: [], received: '' })
    saleId.current = null
    setStop(null)
  }

  const submit = async (v: SaleValues) => {
    const list = prices.data ?? []
    const items = saleItems(list, v.qty, v.holders, v.same_name)
    const shown = list.reduce((sum, p) => sum + (v.qty[p.id] ?? 0) * p.price_mad, 0)
    saleId.current ??= crypto.randomUUID()
    try {
      const order = await sale.mutateAsync({ sale_id: saleId.current, event_id: Number(v.event_id), items })
      saleId.current = null
      if (order.total_mad !== shown) setStop({ kind: 'replayed', order, shown })
      else await navigate(`/pos/print/${order.id}`)
    } catch (e) {
      const fields = e instanceof ApiError && e.code === 'VALIDATION_FAILED' && e.details.fields && typeof e.details.fields === 'object' ? Object.keys(e.details.fields) : []
      if (fields.includes('sale_id')) return setStop({ kind: 'taken' })
      const rows = fields.map((f) => holderField(f, items, v.same_name)).filter((f) => f !== null)
      if (rows.length) rows.forEach((f, i) => form.setError(f, { message: t('common.fieldInvalid') }, { shouldFocus: i === 0 }))
      else form.setError('root', { message: message(e) })
    }
  }

  return (
    <form className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_360px]" onSubmit={(e) => void form.handleSubmit(submit)(e)} noValidate>
      <Card>
        <CardContent className="flex flex-col gap-6 px-5">
          <Field>
            <FieldLabel htmlFor="pos-event">{t('pos.sale.event')}</FieldLabel>
            <Controller
              control={form.control}
              name="event_id"
              render={({ field }) => (
                <Select
                  name={field.name}
                  value={field.value}
                  onValueChange={reset}
                >
                  <SelectTrigger id="pos-event" className="w-full">
                    <SelectValue placeholder={t('pos.sale.eventPlaceholder')} />
                  </SelectTrigger>
                  <SelectContent>
                    {events.map((e) => (
                      <SelectItem key={e.id} value={String(e.id)}>
                        <EventOption event={e} />
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            />
          </Field>
          <Places key={eventId} eventId={eventId} form={form} />
        </CardContent>
      </Card>
      <SaleSummary eventId={eventId} form={form} stop={stop && <SaleStop stop={stop} onNewSale={() => reset(eventId)} />} />
    </form>
  )
}

function EventOption({ event }: { event: EventSummary }) {
  const { i18n } = useTranslation()
  return (
    <span className="flex min-w-0 items-baseline gap-2">
      <span className="truncate font-medium">{event.title}</span>
      <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{formatDateTime(event.starts_at, i18n.language, event.venue.timezone)}</span>
    </span>
  )
}

const holderId = (i: number) => `pos-holder-${i}`
const placeCount = (qty: Record<string, number>) => Object.values(qty).reduce((a, b) => a + b, 0)

/**
 * Zone/price rows with a stepper, then one holder row per place (or one for all with "même nom"). The event detail
 * gives the cap (`per_account_limit`, Σ qty per sale) and the sales close (`refund_cutoff_at`).
 */
function Places({ eventId, form }: { eventId: string; form: UseFormReturn<SaleValues> }) {
  const { t, i18n } = useTranslation()
  const event = useEvent(eventId)
  const prices = usePrices(eventId)
  const now = useNow(30_000)
  const qty = useWatch({ control: form.control, name: 'qty' })
  const sameName = useWatch({ control: form.control, name: 'same_name' })
  if (event.isPending || prices.isPending) return <LoadingRows rows={3} />
  if (event.isError) return <ErrorNotice error={event.error} onRetry={() => void event.refetch()} className="p-0" />
  if (prices.isError) return <ErrorNotice error={prices.error} onRetry={() => void prices.refetch()} className="p-0" />

  const closed = event.data.status !== 'ON_SALE' || (event.data.refund_cutoff_at !== null && Date.parse(event.data.refund_cutoff_at) <= now)
  if (closed)
    return (
      <p role="alert" className="flex items-center gap-2 rounded-lg bg-muted px-4 py-3 text-sm">
        <AlertCircle className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        {t('pos.sale.closed')}
      </p>
    )

  const limit = event.data.per_account_limit
  const total = placeCount(qty)
  const setQty = (id: string, q: number) => {
    const next = { ...qty, [id]: q }
    const holders = form.getValues('holders')
    // One holder row per place: kept as typed when places are added, the last ones dropped when removed.
    form.setValue('qty', next)
    form.setValue('holders', Array.from({ length: placeCount(next) }, (_, i) => holders[i] ?? { name: '', phone: '' }))
  }
  const label = (p: Price) => t('pos.sale.priceName', { zone: p.zone.name, name: p.name })
  // Place i → its zone and category, in the order of the rows.
  const places = prices.data.flatMap((p) => Array<string>(qty[p.id] ?? 0).fill(label(p)))
  const { errors } = form.formState

  return (
    <>
      <section aria-labelledby="pos-places" className="flex flex-col gap-2">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="pos-places" className="text-sm font-medium">
            {t('pos.sale.places')}
          </h2>
          <p className={cn('text-xs tabular-nums', total >= limit ? 'font-medium text-foreground' : 'text-muted-foreground')}>{t('pos.sale.limit', { count: limit })}</p>
        </div>
        <ul className="flex flex-col divide-y rounded-lg border">
          {prices.data.map((p) => (
            <PriceRow key={p.id} price={p} label={label(p)} qty={qty[p.id] ?? 0} full={total >= limit} onQty={(q) => setQty(p.id, q)} locale={i18n.language} />
          ))}
        </ul>
      </section>
      <section aria-labelledby="pos-holders" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 id="pos-holders" className="text-sm font-medium">
              {t('pos.sale.holders')}
            </h2>
            <p className="text-xs text-muted-foreground">{t('pos.sale.holdersHint')}</p>
          </div>
          <Controller
            control={form.control}
            name="same_name"
            render={({ field }) => (
              <Field orientation="horizontal" className="w-auto">
                <Checkbox id="pos-same-name" checked={field.value} onCheckedChange={(c) => field.onChange(c === true)} />
                <FieldLabel htmlFor="pos-same-name" className="font-normal">
                  {t('pos.sale.sameName')}
                </FieldLabel>
              </Field>
            )}
          />
        </div>
        {places.length === 0 ? (
          <p className="rounded-lg border border-dashed p-4 text-center text-sm text-muted-foreground">{t('pos.sale.pickPlaces')}</p>
        ) : (
          <FieldGroup className="gap-3">
            {(sameName ? [t('pos.sale.allPlaces')] : places.map((name, i) => t('pos.sale.place', { n: i + 1, name }))).map((legend, i) => {
              const err = errors.holders?.[i]
              const id = holderId(i)
              return (
                <fieldset key={i} className="grid gap-3 rounded-lg border p-3 sm:grid-cols-2">
                  <legend className="px-1 text-xs font-medium text-muted-foreground">{legend}</legend>
                  <Field data-invalid={!!err?.name}>
                    <FieldLabel htmlFor={`${id}-name`}>{t('pos.sale.name')}</FieldLabel>
                    <Input
                      id={`${id}-name`}
                      autoComplete="off"
                      aria-invalid={!!err?.name}
                      aria-describedby={err?.name ? `${id}-name-error` : undefined}
                      {...form.register(`holders.${i}.name`)}
                    />
                    {err?.name && <FieldError id={`${id}-name-error`}>{err.name.message}</FieldError>}
                  </Field>
                  <Field data-invalid={!!err?.phone}>
                    <FieldLabel htmlFor={`${id}-phone`}>{t('pos.sale.phone')}</FieldLabel>
                    <Input
                      id={`${id}-phone`}
                      type="tel"
                      inputMode="tel"
                      autoComplete="off"
                      aria-invalid={!!err?.phone}
                      aria-describedby={err?.phone ? `${id}-phone-error pos-paper-only` : 'pos-paper-only'}
                      {...form.register(`holders.${i}.phone`)}
                    />
                    {err?.phone && <FieldError id={`${id}-phone-error`}>{err.phone.message}</FieldError>}
                  </Field>
                </fieldset>
              )
            })}
            <FieldDescription id="pos-paper-only">{t('pos.sale.paperOnly')}</FieldDescription>
          </FieldGroup>
        )}
      </section>
    </>
  )
}

function PriceRow(props: { price: Price; label: string; qty: number; full: boolean; onQty: (q: number) => void; locale: string }) {
  const { t } = useTranslation()
  const { price, label, qty, onQty } = props
  const remaining = Math.max(0, price.quota - price.sold)
  return (
    <li className="flex items-center gap-4 px-4 py-3">
      <div className="min-w-0 flex-1">
        <div className="truncate">
          <span className="font-medium">{price.zone.name}</span>
          <span className="text-muted-foreground"> · {price.name}</span>
        </div>
        <div className={cn('text-xs tabular-nums', remaining === 0 ? 'text-destructive' : 'text-muted-foreground')}>
          {remaining === 0 ? t('pos.sale.soldOut') : t('pos.sale.remaining', { count: remaining })}
        </div>
      </div>
      <div className="text-sm font-medium tabular-nums">{t('pos.amount', { amount: formatDh(price.price_mad, props.locale) })}</div>
      <div className="flex items-center gap-1">
        <Button type="button" variant="outline" size="icon" aria-label={t('pos.sale.less', { name: label })} disabled={qty === 0} onClick={() => onQty(qty - 1)}>
          <Minus />
        </Button>
        <output aria-label={t('pos.sale.qty', { name: label })} className="w-8 text-center font-semibold tabular-nums">
          {qty}
        </output>
        <Button type="button" variant="outline" size="icon" aria-label={t('pos.sale.more', { name: label })} disabled={qty >= remaining || props.full} onClick={() => onQty(qty + 1)}>
          <Plus />
        </Button>
      </div>
    </li>
  )
}

// What a DH amount can still become while it is typed (parseDh: 8 digits, 2 decimals): `12,` is on its way, `1,234` is not.
const DH_PREFIX = /^\d{0,8}(?:[.,]\d{0,2})?$/

type Stop = { kind: 'taken' } | { kind: 'replayed'; order: SaleOrder; shown: number }

function SaleStop({ stop, onNewSale }: { stop: Stop; onNewSale: () => void }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  return (
    <div role="alert" className="flex flex-col gap-3 rounded-lg bg-destructive/10 px-4 py-3 text-sm font-medium text-destructive">
      <p className="flex items-start gap-2">
        <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        {stop.kind === 'taken'
          ? t('pos.sale.taken')
          : t('pos.sale.replayed', { amount: formatDh(stop.order.total_mad, i18n.language), shown: formatDh(stop.shown, i18n.language) })}
      </p>
      {stop.kind === 'taken' ? (
        <Button type="button" variant="outline" onClick={onNewSale}>
          {t('pos.sale.newSale')}
        </Button>
      ) : (
        <Button type="button" onClick={() => void navigate(`/pos/print/${stop.order.id}`)}>
          {t('pos.sale.printTickets')}
        </Button>
      )}
    </div>
  )
}

/** Total, cash received and the change to give back (received − total, integer centimes, on the client). */
function SaleSummary({ eventId, form, stop }: { eventId: string; form: UseFormReturn<SaleValues>; stop: ReactNode }) {
  const { t, i18n } = useTranslation()
  const prices = usePrices(eventId)
  const qty = useWatch({ control: form.control, name: 'qty' })
  const received = useWatch({ control: form.control, name: 'received' })
  const touched = !!form.formState.touchedFields.received
  const total = (prices.data ?? []).reduce((sum, p) => sum + (qty[p.id] ?? 0) * p.price_mad, 0)
  const change = received.trim() === '' ? undefined : changeDue(received, total)
  // The error waits for the field to be left, unless what is typed can no longer become an amount.
  const invalid = change === null && (touched || !DH_PREFIX.test(received.trim()))
  const dh = (c: number) => t('pos.amount', { amount: formatDh(c, i18n.language) })
  const tillOpen = useTillOpen()
  const { isSubmitting, errors } = form.formState
  return (
    <Card className="lg:sticky lg:top-20">
      <CardHeader>
        <CardTitle>{t('pos.sale.summary')}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-5 px-6">
        <div className="flex items-baseline justify-between gap-3">
          <span className="text-sm text-muted-foreground">{t('pos.sale.total')}</span>
          <span className="text-3xl font-semibold tracking-tight tabular-nums">{dh(total)}</span>
        </div>
        <Separator />
        <Field data-invalid={invalid}>
          <FieldLabel htmlFor="pos-received">{t('pos.sale.received')}</FieldLabel>
          <Input
            id="pos-received"
            inputMode="decimal"
            autoComplete="off"
            className="h-11 text-lg tabular-nums"
            aria-invalid={invalid}
            aria-describedby={invalid ? 'pos-received-error' : undefined}
            {...form.register('received')}
          />
          {invalid && <FieldError id="pos-received-error">{t('pos.sale.receivedInvalid')}</FieldError>}
        </Field>
        {total > 0 && (
          <div aria-live="polite" className={cn('flex min-h-14 items-center justify-between gap-3 rounded-lg px-4', change != null && change < 0 ? 'bg-destructive/10 text-destructive' : 'bg-muted')}>
            {change != null && (
              <>
                <span className="text-sm font-medium">{change < 0 ? t('pos.sale.missing') : t('pos.sale.change')}</span>
                <span className="text-2xl font-semibold tabular-nums">{dh(Math.abs(change))}</span>
              </>
            )}
          </div>
        )}
        {errors.root && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {errors.root.message}
          </p>
        )}
        {stop}
        <Button
          type="submit"
          size="lg"
          className="h-12 text-base"
          disabled={!tillOpen || total === 0 || (change != null && change < 0) || isSubmitting || !!stop}
          aria-describedby={tillOpen ? undefined : 'pos-sale-need-session'}
        >
          <Banknote />
          {t('pos.sale.submit', { amount: formatDh(total, i18n.language) })}
        </Button>
        {!tillOpen && (
          <p id="pos-sale-need-session" className="text-center text-xs text-muted-foreground">
            {t('pos.sale.needSession')}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

// ---- Code cash ----

function CodeTab() {
  const { t } = useTranslation()
  const [text, setText] = useState('')
  const { symbols, invalid } = readCode(text)
  const code = normalizeCode(text)
  const problem = invalid.length ? t('pos.code.invalidChars', { chars: invalid.join(' ') }) : symbols.length > 6 ? t('pos.code.tooLong') : null
  return (
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <Card>
        <CardContent className="flex flex-col gap-4 px-5">
          <Field data-invalid={!!problem}>
            <FieldLabel htmlFor="pos-code">{t('pos.code.label')}</FieldLabel>
            <Input
              id="pos-code"
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={displayCode('')}
              autoComplete="off"
              autoCapitalize="characters"
              spellCheck={false}
              aria-invalid={!!problem}
              aria-describedby={problem ? 'pos-code-error pos-code-hint' : 'pos-code-hint'}
              className="h-14 font-mono text-2xl tracking-[0.15em] uppercase"
            />
            <FieldDescription id="pos-code-hint">{t('pos.code.hint')}</FieldDescription>
            {problem && <FieldError id="pos-code-error">{problem}</FieldError>}
          </Field>
          <div className="flex items-center justify-between gap-3 rounded-lg bg-muted px-4 py-3">
            <span className="text-sm text-muted-foreground">{t('pos.code.preview')}</span>
            <span className="font-mono text-xl font-semibold tracking-[0.15em] whitespace-pre">{displayCode(problem ? '' : symbols)}</span>
          </div>
        </CardContent>
      </Card>
      {code ? <CodeResult code={code} /> : <Card><BrandEmpty title={t('pos.code.label')} description={t('pos.code.empty')} /></Card>}
    </div>
  )
}

function CodeResult({ code }: { code: string }) {
  const { t } = useTranslation()
  const lookup = useCodeLookup(code)
  if (lookup.isPending) return <Card><LoadingRows className="px-5" /></Card>
  if (lookup.isError)
    return lookup.error instanceof ApiError && lookup.error.status === 404 ? (
      <Card role="alert" className="flex-row items-center gap-3 px-5 text-sm text-destructive">
        <AlertCircle className="size-4 shrink-0" aria-hidden="true" />
        {t('pos.code.notFound')}
      </Card>
    ) : (
      <Card className="py-0"><ErrorNotice error={lookup.error} onRetry={() => void lookup.refetch()} /></Card>
    )
  return <CodeCard code={code} order={lookup.data} />
}

/**
 * Collect sends the exact amount shown. A repeat from this till is a 200 (same order), so a lost answer is just
 * clicked again: a lookup that now reads PAID may be this till's own collect, so "Encaisser" stays on and the server
 * decides (200 replay, or 409). Only a 409 CODE_NOT_PAYABLE on the collect itself says "ne gardez pas l'argent".
 * An expired or otherwise unpayable lookup can't be this till's (a collected order is PAID): nothing to collect.
 */
function CodeCard({ code, order }: { code: string; order: CodeLookup }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const message = useErrorMessage()
  const collect = useCollect(code)
  const tillOpen = useTillOpen()
  const error = collect.error
  const refused = (error instanceof ApiError && error.code === 'CODE_NOT_PAYABLE') || (!order.payable && order.status !== 'PAID')
  const paidHint = !refused && !order.payable
  const errorText =
    !error || refused ? null : error instanceof ApiError && error.code === 'VALIDATION_FAILED' ? t('pos.code.amountChanged') : message(error)
  const onCollect = () => collect.mutate(order.amount_mad, { onSuccess: (r) => void navigate(`/pos/print/${r.order_id}`) })
  return (
    <Card>
      <CardHeader>
        <CardDescription className="font-mono tracking-[0.15em]">{displayCode(code)}</CardDescription>
        <CardTitle className="text-lg">{order.event.title}</CardTitle>
        <CardDescription>{formatDateTime(order.event.starts_at, i18n.language, order.event.timezone)}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5 px-6">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={order.payable ? 'default' : 'outline'}>{t(`pos.code.status.${codeStatus(order)}`)}</Badge>
          {order.payable && order.reserved_until && (
            <span className="text-sm text-muted-foreground">{t('pos.code.payableUntil', { time: formatDateTime(order.reserved_until, i18n.language, order.event.timezone) })}</span>
          )}
        </div>
        <dl className="grid grid-cols-2 gap-4">
          <div>
            <dt className="text-xs font-medium text-muted-foreground">{t('pos.code.amount')}</dt>
            <dd className="text-3xl font-semibold tracking-tight tabular-nums">{t('pos.amount', { amount: formatDh(order.amount_mad, i18n.language) })}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-muted-foreground">{t('pos.code.places')}</dt>
            <dd className="text-3xl font-semibold tabular-nums">{order.places}</dd>
          </div>
          <div className="col-span-2">
            <dt className="text-xs font-medium text-muted-foreground">{t('pos.code.holders')}</dt>
            <dd className="mt-1 flex flex-wrap gap-1.5">
              {order.holders.map((h, i) => (
                <Badge key={i} variant="secondary">
                  {h}
                </Badge>
              ))}
            </dd>
          </div>
        </dl>
        {refused && (
          <p role="alert" className="flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-3 text-sm font-medium text-destructive">
            <AlertCircle className="size-4 shrink-0" aria-hidden="true" />
            {t('pos.code.notPayable')}
          </p>
        )}
        {paidHint && <p className="rounded-lg bg-muted px-4 py-3 text-sm">{t('pos.code.paidHint')}</p>}
        {errorText && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {errorText}
          </p>
        )}
        <Button
          size="lg"
          className="h-12 text-base"
          disabled={!tillOpen || refused || collect.isPending}
          aria-describedby={tillOpen ? undefined : 'pos-code-need-session'}
          onClick={onCollect}
        >
          <Banknote />
          {t('pos.code.submit', { amount: formatDh(order.amount_mad, i18n.language) })}
        </Button>
        {!tillOpen && (
          <p id="pos-code-need-session" className="text-center text-xs text-muted-foreground">
            {t('pos.sale.needSession')}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
