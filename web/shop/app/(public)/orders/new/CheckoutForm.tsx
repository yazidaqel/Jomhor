'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import {
  canSplit,
  firstInvalid,
  hasFriendPhone,
  rowErrorsFrom422,
  shouldSaveName,
  splitSummary,
  validateRows,
  type Row,
  type RowError,
} from '@/lib/checkout'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { errorCode, errorText, pageClock, placeOrder, postJson, type PayMethod, type Result } from '@/lib/order-client'
import { parseAdmission, saveAdmission, storedAdmission } from '@/lib/queue'
import { formatTime } from '@/lib/time'
import { ClockIcon, TicketIcon } from '../../../icons'
import { ctaText, METHOD_ICONS, MethodRows } from '../MethodRows'

type Copy = Pick<Dict, 'checkout' | 'errors' | 'meta'>

const input =
  'mt-1.5 block h-cta-height w-full rounded-control border border-border bg-ground px-4 text-body text-text placeholder:text-text-muted aria-invalid:border-danger'
const label = 'text-label uppercase text-text-secondary'

/**
 * W3 holder rows, payment method and payment. One row per place: a name (required, §Holder names) and, from ticket 2, an optional
 * WhatsApp number (that phone's account then holds the ticket; the buyer confirms the friends' consent, plan Q21).
 * A row with a phone gets "Paie sa place" (split pay, 04 §Split pay: posts `pays: true`, default off); the summary
 * then splits the buyer's places and amount from the friends'. A 422 TOO_LATE turns the switches off for good.
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
  /** Price of one place, integer centimes. */
  unitMad: number
  methods: PayMethod[]
  /** Cash is set up but too late for this event: its row shows disabled. */
  cashLate: boolean
  /** Server clock at SSR (ms) and the venue time zone: the held order's countdown runs on them, never the device clock. */
  now: number
  timezone: string
  /** When a code issued now would expire, already formatted in the venue time zone. */
  cashDeadline: string
  /** W8 of this event: where 409 ADMISSION_REQUIRED (a live room, no valid turn) sends the fan. */
  queueHref?: string | null
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
  const [tooLate, setTooLate] = useState(false) // 422 TOO_LATE: split pay refused for this event now
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

  const CtaIcon = METHOD_ICONS[method ?? 'CARD']
  const friends = hasFriendPhone(rows)
  const split = splitSummary(rows, props.unitMad)
  const set = (i: number, field: keyof Row, value: string | boolean) => setRows((r) => r.map((row, j) => (j === i ? { ...row, [field]: value } : row)))
  const phoneError = (e: RowError | undefined) =>
    e?.phone
      ? c.phoneInvalid
      : e?.limit
        ? fmt(c.friendLimit, e.limit)
        : e?.pays
          ? e.pays === 'PHONE_REQUIRED'
            ? c.paysPhoneRequired
            : copy.errors.VALIDATION_FAILED
          : null

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
      // The W8 turn still running on server time, if any (the backend decides: ADMISSION_REQUIRED sends the fan to W8).
      const admission = parseAdmission(storedAdmission(props.eventId), props.now + (clock.current?.() ?? 0))?.token
      const placed = await placeOrder(order, method, setWait, ctl.signal, { existingId: orderId ?? undefined, admission })
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
    if (errorCode(r) === 'ADMISSION_REQUIRED' && props.queueHref) {
      saveAdmission(props.eventId, null)
      return router.push(props.queueHref)
    }
    const errs = rowErrorsFrom422(r)
    if (errs.some((e) => e?.pays === 'TOO_LATE')) {
      setTooLate(true)
      setRows((rs) => rs.map((row) => ({ ...row, pays: false })))
      return setError(c.splitTooLate)
    }
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
                aria-invalid={!!phoneError(rowErrors[i])}
                aria-describedby={phoneError(rowErrors[i]) ? `phone-${i}-hint phone-${i}-error` : `phone-${i}-hint`}
                className={input}
              />
              {phoneError(rowErrors[i]) && (
                <p id={`phone-${i}-error`} className="mt-1.5 text-body-small text-danger">
                  {phoneError(rowErrors[i])}
                </p>
              )}
            </div>
          )}
          {canSplit(row, i) && (
            <label className="flex min-h-touch-min cursor-pointer items-center justify-between gap-4 has-disabled:cursor-not-allowed">
              <span className="flex min-w-0 flex-col">
                <span className="font-semibold">{c.paysToggle}</span>
                <span id={`pays-${i}-hint`} className="text-body-small text-text-muted">
                  {tooLate ? c.splitTooLate : c.paysHint}
                </span>
              </span>
              <input
                id={`pays-${i}`}
                type="checkbox"
                role="switch"
                checked={!!row.pays}
                disabled={tooLate}
                onChange={(e) => set(i, 'pays', e.target.checked)}
                aria-describedby={`pays-${i}-hint`}
                className="peer sr-only"
              />
              <span
                aria-hidden="true"
                className="relative h-7 w-12 shrink-0 rounded-pill border border-border bg-ground transition-colors duration-200 peer-checked:border-accent peer-checked:bg-accent peer-disabled:opacity-50 peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-accent after:absolute after:start-1 after:top-1 after:size-5 after:rounded-pill after:bg-text-muted after:transition-transform after:duration-200 peer-checked:after:translate-x-5 peer-checked:after:bg-on-accent rtl:peer-checked:after:-translate-x-5"
              />
            </label>
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
      {split.friends > 0 && (
        <div data-split className="flex flex-col gap-1 rounded-control border border-border bg-surface px-4 py-3.5 text-sm">
          <p className="font-semibold">{fmt(c.splitMine, { n: split.mine, total: formatDh(split.mineMad) })}</p>
          <p className="text-text-secondary">{fmt(c.splitFriends, { m: split.friends })}</p>
          <p className="text-xs text-text-muted">{c.splitCaption}</p>
        </div>
      )}
      <MethodRows
        methods={props.methods}
        cashLate={props.cashLate}
        cashDeadline={props.cashDeadline}
        refused={refused}
        method={method}
        onPick={setMethod}
        disabled={pending}
        copy={c}
      />

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
        {pending && wait === null ? (method === 'CASH_AGENCY' ? c.creatingCode : c.paying) : ctaText(c, method, formatDh(split.mineMad))}
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
