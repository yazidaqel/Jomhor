'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { firstInvalid, hasFriendPhone, rowErrorsFrom422, shouldSaveName, validateRows, type Row, type RowError } from '@/lib/checkout'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { errorCode, errorText, placeOrder, postJson, type Result } from '@/lib/order-client'

type Copy = Pick<Dict, 'checkout' | 'errors'>

const input =
  'mt-1.5 block h-cta-height w-full rounded-control border border-border bg-ground px-4 text-body text-text placeholder:text-text-muted aria-invalid:border-danger'
const label = 'text-label uppercase text-text-secondary'

/**
 * W3 holder rows and card payment. One row per place: a name (required, §Holder names) and, from ticket 2, an optional
 * WhatsApp number (that phone's account then holds the ticket; the buyer confirms the friends' consent, plan Q21).
 * Ticket 1 is the buyer, prefilled from /fan/me; an edited name is saved back. Then lib/order-client placeOrder:
 * create (SALE_BUSY → wait and retry), pay, follow the gateway redirect. "Annuler" or leaving stops all of it.
 */
export function CheckoutForm(props: {
  eventId: number
  priceCategoryId: string
  qty: number
  displayName: string | null
  totalMad: number
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
  const [wait, setWait] = useState<number | null>(null) // SALE_BUSY: seconds before the next try
  const abort = useRef<AbortController | null>(null)
  const leaving = useRef(false) // unmounting: the fan went elsewhere, don't navigate them back
  useEffect(
    () => () => {
      leaving.current = true
      abort.current?.abort()
    },
    [],
  )

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
    const v = validateRows(rows)
    if (v.errors || (friends && !consent)) return showErrors(v.errors ?? [], friends && !consent)
    setRowErrors([])
    setConsentError(false)
    setError(null)
    setPending(true)
    const ctl = (abort.current = new AbortController())
    try {
      if (shouldSaveName(v.holders[0]!.name, props.displayName)) {
        await postJson('/api/me', { display_name: v.holders[0]!.name }, 'PATCH', ctl.signal) // best effort
      }
      const order = { event_id: props.eventId, items: [{ price_category_id: props.priceCategoryId, qty, holders: v.holders }] }
      const placed = await placeOrder(order, setWait, ctl.signal)
      setWait(null)
      if (placed.kind === 'failed') return fail(placed.result)
      // The order holds the places for 8 minutes: after a pay failure, or "Annuler" once it exists, its page shows the
      // countdown (and the retry), so the held places are never left out of sight.
      if (placed.kind === 'payFailed' || placed.kind === 'held') {
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
        <fieldset key={i} className="flex flex-col gap-3 rounded-card border border-border bg-surface p-5">
          <legend className="float-left mb-1 font-display text-h2">
            {fmt(c.ticket, { n: i + 1 })}
            {i === 0 && <span className="ms-2 text-body-small font-normal text-text-muted">{c.you}</span>}
          </legend>
          <div className="clear-left">
            <label htmlFor={`name-${i}`} className={label}>
              {c.name}
            </label>
            <input
              id={`name-${i}`}
              value={row.name}
              onChange={(e) => set(i, 'name', e.target.value)}
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

      <button
        type="submit"
        disabled={pending}
        aria-busy={pending}
        className="flex h-cta-height items-center justify-center rounded-control bg-accent text-cta text-on-accent hover:bg-accent-hover disabled:opacity-60"
      >
        {pending && wait === null ? c.paying : fmt(c.pay, { amount: formatDh(props.totalMad) })}
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
