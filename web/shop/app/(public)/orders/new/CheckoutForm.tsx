'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { firstInvalid, hasFriendPhone, rowErrorsFrom422, shouldSaveName, validateRows, type Row, type RowError } from '@/lib/checkout'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { errorCode, errorText, PAY_METHODS, pageClock, placeOrder, postJson, type PayMethod, type Result } from '@/lib/order-client'
import { formatTime } from '@/lib/time'
import { CardIcon, CashIcon, CheckIcon, ClockIcon, TicketIcon, WalletIcon } from '../../../icons'

type Copy = Pick<Dict, 'checkout' | 'errors' | 'meta'>

const input =
  'mt-1.5 block h-cta-height w-full rounded-control border border-border bg-ground px-4 text-body text-text placeholder:text-text-muted aria-invalid:border-danger'
const label = 'text-label uppercase text-text-secondary'
const ICONS = { CARD: CardIcon, WALLET: WalletIcon, CASH_AGENCY: CashIcon }

/**
 * W3 holder rows, payment method and payment. One row per place: a name (required, §Holder names) and, from ticket 2, an optional
 * WhatsApp number (that phone's account then holds the ticket; the buyer confirms the friends' consent, plan Q21).
 * Ticket 1 is the buyer, prefilled from /fan/me; an edited name is saved back. Methods come from the event's
 * `payment_methods` (DESIGN Lot 3 W3): wallet only when offered, cash disabled once too late, hidden when not set up. Then
 * lib/order-client placeOrder: create (SALE_BUSY → wait and retry), pay with the method, then follow the hosted page
 * (card, wallet) or open the order page with the code (cash). A method refused with METHOD_NOT_AVAILABLE keeps the
 * fan here on the order just created (holders locked, hold countdown shown), to pay it another way. "Annuler" or leaving stops all of it.
 */
export function CheckoutForm(props: {
  eventId: number
  priceCategoryId: string
  qty: number
  displayName: string | null
  totalMad: number
  methods: PayMethod[]
  /** Cash is set up but too late for this event: its row shows disabled. */
  cashLate: boolean
  /** Server clock at SSR (ms) and the venue time zone: the held order's countdown runs on them, never the device clock. */
  now: number
  timezone: string
  /** When a code issued now would expire, already formatted in the venue time zone. */
  cashDeadline: string
  copy: Copy
}) {
  const { copy, qty } = props
  const c = copy.checkout
  const router = useRouter()
  const form = useRef<HTMLFormElement>(null)
  const [rows, setRows] = useState<Row[]>(() =>
    Array.from({ length: qty }, (_, i) => ({ name: i === 0 ? (props.displayName ?? '') : '', phone: '' })),
  )
  const [rowErrors, setRowErrors] = useState<RowError[]>([])
  const [consent, setConsent] = useState(false)
  const [consentError, setConsentError] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [refused, setRefused] = useState<PayMethod[]>([]) // METHOD_NOT_AVAILABLE on this order
  const usable = props.methods.filter((m) => !refused.includes(m))
  const [picked, setMethod] = useState<PayMethod | undefined>(props.methods[0])
  const method = picked && usable.includes(picked) ? picked : usable[0]
  // Created, but its /pay refused the method: the order id and its hold (`reserved_until`).
  const [held, setHeld] = useState<{ id: string; until: string | null } | null>(null)
  const orderId = held?.id ?? null
  const clock = useRef<(() => number) | null>(null) // monotonic ms since mount (pageClock)
  const [onPageMs, setOnPageMs] = useState(0)
  useEffect(() => {
    if (!held?.until) return
    const t = setInterval(() => setOnPageMs(clock.current!()), 1000)
    return () => clearInterval(t)
  }, [held])
  const [wait, setWait] = useState<number | null>(null) // SALE_BUSY: seconds before the next try
  const abort = useRef<AbortController | null>(null)
  const leaving = useRef(false) // unmounting: the fan went elsewhere, don't navigate them back
  useEffect(() => {
    clock.current = pageClock()
    return () => {
      leaving.current = true
      abort.current?.abort()
    }
  }, [])

  const CtaIcon = ICONS[method ?? 'CARD']
  const friends = hasFriendPhone(rows)
  const set = (i: number, field: keyof Row, value: string) => setRows((r) => r.map((row, j) => (j === i ? { ...row, [field]: value } : row)))

  function showErrors(errs: RowError[], consentMissing = false) {
    setRowErrors(errs)
    setConsentError(consentMissing)
    setError(c.fixErrors)
    const id = firstInvalid(errs) ?? (consentMissing ? 'consent' : null)
    if (id) form.current?.querySelector<HTMLElement>(`#${id}`)?.focus()
  }

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!method) return
    const v = validateRows(rows)
    if (v.errors || (friends && !consent)) return showErrors(v.errors ?? [], friends && !consent)
    setRowErrors([])
    setConsentError(false)
    setError(null)
    setPending(true)
    const ctl = (abort.current = new AbortController())
    try {
      if (!orderId && shouldSaveName(v.holders[0]!.name, props.displayName)) {
        await postJson('/api/me', { display_name: v.holders[0]!.name }, 'PATCH', ctl.signal) // best effort
      }
      const order = { event_id: props.eventId, items: [{ price_category_id: props.priceCategoryId, qty, holders: v.holders }] }
      const placed = await placeOrder(order, method, setWait, ctl.signal, { existingId: orderId ?? undefined })
      setWait(null)
      if (placed.kind === 'failed') return fail(placed.result)
      if (placed.kind === 'payFailed' && errorCode(placed.result) === 'METHOD_NOT_AVAILABLE' && !leaving.current) {
        setHeld((h) => ({ id: placed.orderId, until: placed.reservedUntil ?? h?.until ?? null }))
        setOnPageMs(clock.current?.() ?? 0)
        setRefused((r) => [...r, method])
        setPending(false)
        setError(`${errorText(copy.errors, placed.result)} ${c.orderHeld}`)
        return
      }
      // The order holds the places: after a pay failure, "Annuler" once it exists, or a cash code, its page shows the
      // countdown (and the retry or the code), so the held places are never left out of sight.
      if (placed.kind !== 'redirect') {
        if (!leaving.current) router.push(`/orders/${encodeURIComponent(placed.orderId)}`)
        return
      }
      if (!ctl.signal.aborted) window.location.assign(placed.url)
    } catch {
      // Aborted during the order request ("Annuler" or leaving the page): nothing was paid or followed.
      setWait(null)
      setPending(false)
    }
  }

  function fail(r: Result) {
    setPending(false)
    if (errorCode(r) === 'UNAUTHORIZED') {
      return router.push(`/login?next=${encodeURIComponent(window.location.pathname + window.location.search)}`)
    }
    const errs = rowErrorsFrom422(r)
    if (errs.length) return showErrors(errs)
    setError(errorText(copy.errors, r))
  }

  return (
    <form ref={form} noValidate onSubmit={submit} className="flex flex-col gap-4">
      {rows.map((row, i) => (
        <fieldset
          key={i}
          disabled={!!orderId}
          style={{ '--i': i } as React.CSSProperties}
          className="flex flex-col gap-3 rounded-card border border-border bg-surface p-5 transition-colors duration-200 focus-within:border-border-muted motion-safe:animate-rise stagger"
        >
          <legend className="float-start mb-1 flex items-center gap-2.5 font-display text-h2 uppercase">
            <TicketIcon className="size-5 text-accent" />
            {fmt(c.ticket, { n: i + 1 })}
            {i === 0 && <span className="font-body text-body-small font-normal normal-case text-text-muted">{c.you}</span>}
          </legend>
          <div className="clear-start">
            <label htmlFor={`name-${i}`} className={label}>
              {c.name}
            </label>
            <input
              id={`name-${i}`}
              value={row.name}
              onChange={(e) => set(i, 'name', e.target.value)}
              dir="auto"
              autoComplete={i === 0 ? 'name' : 'off'}
              placeholder={c.namePlaceholder}
              aria-invalid={!!rowErrors[i]?.name}
              aria-describedby={rowErrors[i]?.name ? `name-${i}-error` : undefined}
              className={input}
            />
            {rowErrors[i]?.name && (
              <p id={`name-${i}-error`} className="mt-1.5 text-body-small text-danger">
                {c.nameInvalid}
              </p>
            )}
          </div>
          {i > 0 && (
            <div>
              <label htmlFor={`phone-${i}`} className={label}>
                {c.phone}
              </label>
              <p id={`phone-${i}-hint`} className="mt-1 text-body-small text-text-muted">
                {c.phoneHint}
              </p>
              <input
                id={`phone-${i}`}
                type="tel"
                dir="ltr"
                value={row.phone}
                onChange={(e) => set(i, 'phone', e.target.value)}
                autoComplete="off"
                placeholder={c.phonePlaceholder}
                aria-invalid={!!rowErrors[i]?.phone}
                aria-describedby={rowErrors[i]?.phone ? `phone-${i}-hint phone-${i}-error` : `phone-${i}-hint`}
                className={input}
              />
              {rowErrors[i]?.phone && (
                <p id={`phone-${i}-error`} className="mt-1.5 text-body-small text-danger">
                  {c.phoneInvalid}
                </p>
              )}
            </div>
          )}
        </fieldset>
      ))}

      {friends && (
        <div>
          <label className="flex min-h-touch-min cursor-pointer items-start gap-3 text-body-small text-text-secondary">
            <input
              id="consent"
              type="checkbox"
              checked={consent}
              onChange={(e) => setConsent(e.target.checked)}
              required
              aria-invalid={consentError}
              aria-describedby={consentError ? 'consent-error' : undefined}
              className="mt-0.5 size-5 shrink-0 accent-accent"
            />
            <span>{c.consent}</span>
          </label>
          {consentError && (
            <p id="consent-error" className="mt-1 text-body-small text-danger">
              {c.consentRequired}
            </p>
          )}
        </div>
      )}

      {wait !== null && (
        <div className="flex flex-col gap-1 rounded-control border border-accent/50 bg-accent/10 px-4 py-3.5 text-sm text-accent-hover">
          {/* Only the title and "Nouvel essai…" are announced, not the per-second countdown. */}
          <p aria-live="polite" className="flex flex-col">
            <strong className="font-semibold">{c.busyTitle}</strong>
            {wait === 0 && <span>{c.busyRetrying}</span>}
          </p>
          {wait > 0 && <span>{fmt(c.busy, { s: wait })}</span>}
          <span className="text-text-secondary">{c.busyNote}</span>
        </div>
      )}
      {error && (
        <p role="alert" className="text-body-small text-danger">
          {error}
        </p>
      )}

      {held?.until && (
        <p data-held className="flex items-center gap-3 rounded-control border border-accent/50 bg-accent/10 px-4 py-3.5 text-sm text-accent-hover">
          <ClockIcon className="size-5" />
          <span>{heldText(c, held.until, props.timezone, copy.meta.intl, props.now + onPageMs)}</span>
        </p>
      )}
      <fieldset className="flex flex-col gap-2.5 rounded-card border border-border bg-surface p-5">
        <legend className="float-start mb-1 font-display text-h2 uppercase">{c.methodTitle}</legend>
        {PAY_METHODS.filter((m) => props.methods.includes(m) || (m === 'CASH_AGENCY' && props.cashLate)).map((m) => {
          const off = !usable.includes(m)
          const Icon = ICONS[m]
          const hint = refused.includes(m)
            ? c.unavailable
            : m !== 'CASH_AGENCY'
              ? c.instant
              : props.methods.includes(m)
                ? fmt(c.cashBefore, { deadline: props.cashDeadline })
                : c.cashTooLate
          return (
            <label
              key={m}
              className={`clear-start flex min-h-touch-min items-center gap-3 rounded-control border-2 bg-ground px-4 py-3 transition-colors duration-200 has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent ${
                off ? 'cursor-not-allowed border-border text-text-muted' : m === method ? 'cursor-pointer border-accent' : 'cursor-pointer border-border hover:border-border-muted'
              }`}
            >
              <input
                type="radio"
                name="method"
                value={m}
                checked={m === method}
                disabled={off || pending}
                onChange={() => setMethod(m)}
                aria-describedby={`method-${m}-hint`}
                className="sr-only"
              />
              <Icon className={`size-6 ${off ? '' : 'text-accent'}`} />
              <span className="flex min-w-0 flex-1 flex-col gap-x-3 sm:flex-row sm:items-center sm:justify-between">
                <span className="font-semibold">{c[m]}</span>
                <span id={`method-${m}-hint`} className="text-body-small text-text-muted sm:text-end">
                  {hint}
                </span>
              </span>
              <span
                aria-hidden="true"
                className={`flex size-6 shrink-0 items-center justify-center rounded-pill border-2 ${m === method ? 'border-accent bg-accent text-on-accent' : 'border-border'}`}
              >
                {m === method && <CheckIcon key={m} className="size-3.5 motion-safe:animate-mark" />}
              </span>
            </label>
          )
        })}
        {method === 'CASH_AGENCY' && <p className="text-body-small text-text-secondary">{c.cashNote}</p>}
      </fieldset>

      <button
        type="submit"
        disabled={pending || !method}
        aria-busy={pending}
        className="flex h-cta-height items-center justify-center gap-2.5 rounded-control bg-accent text-cta text-on-accent transition-colors duration-200 hover:bg-accent-hover disabled:opacity-60"
      >
        {pending ? (
          <span aria-hidden="true" className="size-5 rounded-pill border-2 border-on-accent/30 border-t-on-accent motion-safe:animate-spin" />
        ) : (
          <CtaIcon className="size-5" />
        )}
        {pending && wait === null ? (method === 'CASH_AGENCY' ? c.creatingCode : c.paying) : ctaText(c, method, formatDh(props.totalMad))}
      </button>
      {pending && (
        <button
          type="button"
          onClick={() => abort.current?.abort()}
          className="min-h-touch-min text-body-small font-semibold text-accent hover:underline"
        >
          {c.cancel}
        </button>
      )}
      <p className="text-center text-xs text-text-muted">
        {c.delivery}
        <br />
        {c.hold}
      </p>
    </form>
  )
}

function heldText(c: Copy['checkout'], until: string, timezone: string, intl: string, serverNow: number) {
  const left = Math.max(0, Math.floor((Date.parse(until) - serverNow) / 1000))
  return fmt(c.held, { time: formatTime(until, timezone, intl), m: Math.floor(left / 60), s: left % 60 })
}

function ctaText(c: Copy['checkout'], m: PayMethod | undefined, amount: string) {
  if (m === 'CASH_AGENCY') return c.payCash
  if (m === 'WALLET') return fmt(c.payWallet, { amount })
  return fmt(c.pay, { amount })
}
