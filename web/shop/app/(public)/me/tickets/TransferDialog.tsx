'use client'

import { useEffect, useRef, useState } from 'react'
import { normalizeHolderName } from '@/lib/holder-name'
import { fmt, isolate, ltr, type Dict } from '@/lib/i18n'
import { errorCode, postJson, type Result } from '@/lib/order-client'
import { normalizePhone } from '@/lib/phone'
import type { TicketView } from '@/lib/tickets'
import { formatDateTime } from '@/lib/time'
import { CheckIcon, ClockIcon, QrIcon, TicketIcon } from '../../../icons'

export type TransferCopy = Pick<Dict, 'ticketsPage' | 'transfer' | 'errors' | 'meta'> & {
  holder: Pick<Dict['checkout'], 'nameInvalid' | 'namePlaceholder' | 'phoneInvalid' | 'phonePlaceholder'>
}

/** What the list does once the dialog closes. */
export type Outcome =
  | { kind: 'none' }
  | { kind: 'transferred'; to_label: string; at: string }
  | { kind: 'closed'; transfer_until: string | null }
  | { kind: 'reload' }

type Step = 'form' | 'confirm' | 'done' | 'failed'
type FieldErrors = { phone?: string; name?: string }

const cta = 'flex min-h-touch-min items-center justify-center gap-2 rounded-control px-5 text-cta transition-colors duration-200 disabled:cursor-wait'
const primary = `${cta} h-cta-height bg-accent text-on-accent enabled:hover:bg-accent-hover disabled:opacity-70`
const ghost = `${cta} h-cta-height border border-border text-text enabled:hover:border-border-muted`
const input =
  'mt-1.5 block h-cta-height w-full rounded-control border border-border bg-ground px-4 text-body text-text placeholder:text-text-muted aria-invalid:border-danger'

/** "Transferts fermés 1 h 30 min avant le début" (lead = start − `transfer_until`), or the no-transfer line without one. */
export function closedCopy(copy: Pick<Dict, 'ticketsPage' | 'transfer'>, t: TicketView): string {
  const min = t.transfer_until ? Math.round((Date.parse(t.event.starts_at) - Date.parse(t.transfer_until)) / 60_000) : 0
  if (!(min > 0)) return copy.transfer.unavailable
  const [h, m] = [Math.floor(min / 60), min % 60]
  const lead = [h && fmt(copy.ticketsPage.hours, { n: h }), m && fmt(copy.ticketsPage.minutes, { n: m })].filter(Boolean).join(' ')
  return fmt(copy.transfer.closed, { lead })
}

/**
 * W5 "Transférer" (DESIGN.md Lot 4, screen 8 on the web): a modal in three calm steps. Who (WhatsApp number + full name,
 * checked as the server will), then an explicit confirm that says what can't be undone, then done. The web holds no
 * key, so nothing here touches one: `POST /api/tickets/{id}/transfer` answers the recipient's label only. A refused
 * field sends the fan back to it; any other failure ends on its copy and the list reloads on close (the transfer may
 * have happened: the fresh list says).
 */
export function TransferDialog({ ticket: t, copy, onClose }: { ticket: TicketView; copy: TransferCopy; onClose: (o: Outcome) => void }) {
  const tr = copy.transfer
  const dialog = useRef<HTMLDialogElement>(null)
  const title = useRef<HTMLHeadingElement>(null)
  const outcome = useRef<Outcome>({ kind: 'none' })
  // A send in flight: a dialog closed now (Chromium closes on a second Esc despite preventDefault) can't know the
  // result, so the list reloads.
  const inFlight = useRef(false)
  const [step, setStep] = useState<Step>('form')
  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})
  const [message, setMessage] = useState('')
  const [pending, setPending] = useState(false)
  const [toLabel, setToLabel] = useState('')

  useEffect(() => dialog.current?.showModal(), [])
  useEffect(() => {
    if (step !== 'form') title.current?.focus()
    else document.getElementById(errors.name && !errors.phone ? 'transfer-name' : 'transfer-phone')?.focus()
  }, [step, errors])

  const close = () => dialog.current?.close()
  const recipient = normalizeHolderName(name) ?? name.trim()

  function next(e: React.FormEvent) {
    e.preventDefault()
    const errs: FieldErrors = {
      ...(!normalizePhone(phone) && { phone: copy.holder.phoneInvalid }),
      ...(!normalizeHolderName(name) && { name: copy.holder.nameInvalid }),
    }
    setErrors(errs)
    if (!errs.phone && !errs.name) setStep('confirm')
  }

  async function send() {
    setPending(true)
    inFlight.current = true
    const r = await postJson(`/api/tickets/${encodeURIComponent(t.id)}/transfer`, { to_phone: normalizePhone(phone), name: normalizeHolderName(name) })
    inFlight.current = false
    setPending(false)
    if (r.ok) {
      const label = String(r.data?.to_label)
      outcome.current = { kind: 'transferred', to_label: label, at: String(r.data?.transferred_at) }
      setToLabel(label)
      return setStep('done')
    }
    const fields = refusedFields(copy, r)
    if (fields) return (setErrors(fields), setStep('form'))
    const code = errorCode(r)
    const until = (r.data?.details as Record<string, unknown> | undefined)?.transfer_until
    const transfer_until = typeof until === 'string' ? until : null
    outcome.current = code === 'TRANSFER_CLOSED' ? { kind: 'closed', transfer_until } : { kind: 'reload' }
    setMessage(
      code === 'TRANSFER_CLOSED'
        ? closedCopy(copy, { ...t, transfer_until })
        : code === 'TICKET_NOT_VALID'
          ? tr.notValid
          : code === 'UNAUTHORIZED' || code === 'NOT_FOUND'
            ? copy.errors[code]
            : tr.unknown,
    )
    setStep('failed')
  }

  const heading = (text: string) => (
    <h2 id="transfer-title" ref={title} tabIndex={-1} className="font-display text-[32px] font-extrabold uppercase leading-none outline-none">
      {text}
    </h2>
  )
  const eyebrow = <p className="text-label uppercase text-accent">{tr.eyebrow}</p>
  const stub = (
    <div className="flex items-center gap-3 rounded-card border border-border bg-ground px-4 py-3">
      <TicketIcon className="size-5 text-accent" />
      <div className="min-w-0">
        <p className="truncate text-body font-semibold">{t.event.title}</p>
        <p className="text-body-small text-text-muted">
          {t.zone.name} · <span dir="ltr" className="font-display text-[15px] font-bold tracking-[1.5px]">{t.ticket_no}</span>
        </p>
      </div>
    </div>
  )

  return (
    <dialog
      ref={dialog}
      aria-labelledby="transfer-title"
      onCancel={(e) => pending && e.preventDefault()}
      onClose={() => onClose(inFlight.current ? { kind: 'reload' } : outcome.current)}
      className="m-auto w-[calc(100%-2rem)] max-w-md rounded-card-large border border-border bg-surface p-0 text-text shadow-2xl shadow-ground backdrop:bg-ground/80 backdrop:backdrop-blur-sm motion-safe:open:animate-rise max-sm:mb-0 max-sm:w-full max-sm:max-w-none max-sm:rounded-b-none"
    >
      <div className="flex flex-col gap-6 p-6 sm:p-7">
        {step === 'form' && (
          <form noValidate onSubmit={next} className="flex flex-col gap-6">
            <header className="flex flex-col gap-2">
              {eyebrow}
              {heading(tr.title)}
            </header>
            {stub}
            <Field id="transfer-phone" label={tr.phone} hint={tr.phoneHint} error={errors.phone}>
              {(a) => (
                <input {...a} type="tel" inputMode="tel" dir="ltr" autoComplete="off" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder={copy.holder.phonePlaceholder} className={input} />
              )}
            </Field>
            <Field id="transfer-name" label={tr.name} hint={tr.nameHint} error={errors.name}>
              {(a) => <input {...a} dir="auto" autoComplete="off" value={name} onChange={(e) => setName(e.target.value)} placeholder={copy.holder.namePlaceholder} className={input} />}
            </Field>
            {t.transfer_until && (
              <p className="flex items-center gap-2 text-body-small text-text-muted">
                <ClockIcon className="size-4" />
                {fmt(tr.until, { when: formatDateTime(t.transfer_until, t.event.timezone, copy.meta.intl) })}
              </p>
            )}
            <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
              <button type="button" onClick={close} className={ghost}>
                {tr.cancel}
              </button>
              <button type="submit" className={primary}>
                {tr.next}
              </button>
            </div>
          </form>
        )}

        {step === 'confirm' && (
          <>
            <header className="flex flex-col gap-2">
              {eyebrow}
              {heading(tr.confirmTitle)}
            </header>
            <dl className="flex flex-col divide-y divide-border rounded-card border border-border bg-ground">
              <div className="flex items-start justify-between gap-4 px-4 py-3">
                <dt className="text-label uppercase text-text-muted">{tr.to}</dt>
                <dd className="flex min-w-0 flex-col items-end text-end">
                  <bdi className="text-body font-semibold">{recipient}</bdi>
                  <span dir="ltr" className="text-body-small text-text-secondary">{normalizePhone(phone)}</span>
                </dd>
              </div>
              <div className="flex items-start justify-between gap-4 px-4 py-3">
                <dt className="text-label uppercase text-text-muted">{tr.ticket}</dt>
                <dd className="flex min-w-0 flex-col items-end text-end">
                  <span className="max-w-full truncate text-body font-semibold">{t.event.title}</span>
                  <span className="text-body-small text-text-secondary">
                    {t.zone.name} · {ltr(t.ticket_no)}
                  </span>
                </dd>
              </div>
            </dl>
            <div className="flex gap-3 rounded-card border border-accent/40 bg-accent/[0.07] p-4">
              <QrIcon className="mt-0.5 size-5 text-accent" />
              <div className="flex flex-col gap-1">
                <p className="text-body font-semibold">{fmt(tr.warning, { name: isolate(recipient) })}</p>
                <p className="text-body-small text-text-secondary">{tr.final}</p>
              </div>
            </div>
            <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
              <button type="button" onClick={() => setStep('form')} disabled={pending} className={ghost}>
                {tr.edit}
              </button>
              <button type="button" onClick={send} disabled={pending} aria-busy={pending} className={primary}>
                {pending ? tr.pending : tr.confirm}
              </button>
            </div>
          </>
        )}

        {step === 'done' && (
          <div className="flex flex-col items-center gap-4 py-2 text-center">
            <span className="flex size-16 items-center justify-center rounded-pill bg-success-bg text-success-text motion-safe:animate-pop">
              <CheckIcon className="size-8" />
            </span>
            {heading(tr.doneTitle)}
            <p className="text-body text-text-secondary">{fmt(tr.done, { name: isolate(toLabel) })}</p>
            <button type="button" onClick={close} className={`${primary} mt-2 w-full`}>
              {tr.close}
            </button>
          </div>
        )}

        {step === 'failed' && (
          <>
            <header className="flex flex-col gap-2">
              {eyebrow}
              {heading(tr.confirmTitle)}
            </header>
            <p role="alert" className="rounded-card border border-danger/40 bg-danger/10 p-4 text-body text-danger">
              {message}
            </p>
            <button type="button" onClick={close} className={ghost}>
              {tr.close}
            </button>
          </>
        )}
      </div>
    </dialog>
  )
}

type InputA11y = { id: string; 'aria-invalid': boolean; 'aria-describedby': string }

function Field({ id, label, hint, error, children }: { id: string; label: string; hint: string; error?: string; children: (a: InputA11y) => React.ReactNode }) {
  const describedBy = error ? `${id}-hint ${id}-error` : `${id}-hint`
  return (
    <div>
      <label htmlFor={id} className="text-label uppercase text-text-secondary">
        {label}
      </label>
      {children({ id, 'aria-invalid': !!error, 'aria-describedby': describedBy })}
      <p id={`${id}-hint`} className="mt-1.5 text-body-small text-text-muted">
        {hint}
      </p>
      {error && (
        <p id={`${id}-error`} className="mt-1 text-body-small text-danger">
          {error}
        </p>
      )}
    </div>
  )
}

/** A 422 on `to_phone` (SELF: the caller's own number) or `name` → the field errors to show; otherwise null. */
function refusedFields(copy: TransferCopy, r: Result): FieldErrors | null {
  if (r.status !== 422) return null
  const details = (r.data?.details ?? {}) as Record<string, unknown>
  const fields = Object.keys((details.fields ?? {}) as object)
  const self = details.reason === 'SELF'
  const errs: FieldErrors = {
    ...((self || fields.includes('to_phone')) && { phone: self ? copy.transfer.self : copy.holder.phoneInvalid }),
    ...(fields.includes('name') && { name: copy.holder.nameInvalid }),
  }
  return errs.phone || errs.name ? errs : null
}
