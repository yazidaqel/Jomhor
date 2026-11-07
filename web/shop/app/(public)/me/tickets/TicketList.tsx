'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { fmt, type Dict } from '@/lib/i18n'
import type { TicketView } from '@/lib/tickets'
import { countdown, formatDate, formatTime } from '@/lib/time'
import { club } from '@/lib/club'
import { JMark } from '../../../brand'
import { ArrowIcon, DownloadIcon, QrIcon } from '../../../icons'

type Copy = Pick<Dict, 'ticketsPage' | 'errors' | 'meta'>
type C = Copy['ticketsPage']

const cta = 'flex min-h-touch-min items-center justify-center gap-2 rounded-control px-5 text-cta transition-colors duration-200'
const primary = `${cta} bg-accent text-on-accent hover:bg-accent-hover`

type State = { tickets: TicketView[]; at: number } | 'loading' | 'error' | 'UNAUTHORIZED'

/** W5 wallet: `/api/tickets` once (and on "Réessayer"), upcoming then past, one paper ticket each, PDF when VALID. */
export function TicketList({ copy }: { copy: Copy }) {
  const c = copy.ticketsPage
  const [state, setState] = useState<State>('loading')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const abort = new AbortController()
    fetch('/api/tickets', { cache: 'no-store', signal: abort.signal })
      .then(async (res) => {
        if (res.status === 401) return setState('UNAUTHORIZED')
        if (!res.ok) return setState('error')
        setState({ ...((await res.json()) as { tickets: TicketView[] }), at: Date.now() })
      })
      .catch(() => abort.signal.aborted || setState('error'))
    return () => abort.abort()
  }, [attempt])

  if (state === 'loading') return <Skeleton label={c.loading} />
  if (state === 'UNAUTHORIZED' || state === 'error') {
    return (
      <div role="alert" className="flex flex-col items-start gap-4 rounded-card border border-border bg-surface p-6">
        <p className="text-body text-text-secondary">{state === 'error' ? c.error : copy.errors.UNAUTHORIZED}</p>
        {state === 'error' ? (
          <button type="button" onClick={() => (setState('loading'), setAttempt((n) => n + 1))} className={primary}>
            {c.retry}
          </button>
        ) : (
          <Link href={`/login?next=${encodeURIComponent('/me/tickets')}`} className={primary}>
            {c.login}
          </Link>
        )}
      </div>
    )
  }

  if (state.tickets.length === 0) {
    return (
      <div className="flex flex-col items-center gap-5 rounded-card-large border border-dashed border-border px-6 py-14 text-center motion-safe:animate-rise">
        <JMark size={96} />
        <p className="max-w-md text-body text-text-secondary">{c.empty}</p>
        <Link href="/" className={`${primary} group`}>
          {c.browse}
          <ArrowIcon className="size-5 transition-transform duration-200 motion-safe:group-hover:translate-x-0.5 rtl:motion-safe:group-hover:-translate-x-0.5" />
        </Link>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-12">
      <Group id="upcoming" title={c.upcoming} list={state.tickets.filter((t) => !t.past)} copy={copy} now={state.at} />
      <Group id="past" title={c.past} list={state.tickets.filter((t) => t.past)} copy={copy} now={state.at} />
    </div>
  )
}

function Group({ id, title, list, copy, now }: { id: string; title: string; list: TicketView[]; copy: Copy; now: number }) {
  if (list.length === 0) return null
  const c = copy.ticketsPage
  return (
    <section aria-labelledby={`tickets-${id}`} className="flex flex-col gap-5">
      <div className="flex items-baseline gap-3">
        <h2 id={`tickets-${id}`} className="font-display text-[32px] font-bold uppercase leading-none">
          {title}
        </h2>
        <p className="text-sm text-text-muted">{list.length === 1 ? c.countOne : fmt(c.count, { n: list.length })}</p>
      </div>
      <ul className="grid gap-8 sm:grid-cols-2 lg:grid-cols-3">
        {list.map((t, i) => (
          <TicketCard key={t.id} t={t} copy={copy} now={now} i={i} />
        ))}
      </ul>
    </section>
  )
}

/** "Dans 42 j 20 h", "Dans 5 h 12 min"; "C'est aujourd'hui" once started (until it counts as past). */
function until(c: C, iso: string, now: number) {
  const left = countdown(iso, now)
  if (!left) return c.soon
  const parts = left.days > 0 ? [fmt(c.days, { n: left.days }), fmt(c.hours, { n: left.hours })] : [fmt(c.hours, { n: left.hours }), fmt(c.minutes, { n: left.minutes })]
  return `${c.in} ${parts.join(' ')}`
}

/**
 * A ticket as a physical object (DESIGN.md: light surfaces are the ticket): accent band with the event, facts, holder
 * and number, perforation, then the stub with status and the PDF. No QR here: the shop holds no key (the app and the PDF
 * carry it).
 */
function TicketCard({ t, copy, now, i }: { t: TicketView; copy: Copy; now: number; i: number }) {
  const c = copy.ticketsPage
  const valid = t.status === 'VALID'
  const status = Object.hasOwn(c, t.status) ? (c as Record<string, string>)[t.status] : t.status
  const venue = t.event.venue.city ? `${t.event.venue.name} · ${t.event.venue.city}` : t.event.venue.name
  const live = valid && !t.past
  const label = 'text-[11px] font-semibold uppercase tracking-[1px] text-ticket-muted'
  const fact = (name: string, value: string) => (
    <div className="flex min-w-0 flex-col gap-1">
      <dt className={label}>{name}</dt>
      <dd className="truncate font-display text-[22px] font-bold leading-none">{value}</dd>
    </div>
  )
  return (
    <li className="motion-safe:animate-rise stagger" style={{ '--i': i } as React.CSSProperties}>
      <article
        aria-labelledby={`ticket-${t.id}`}
        className={`flex h-full flex-col overflow-hidden rounded-card-large bg-ticket-paper [&_:focus-visible]:outline-ticket-ink text-ticket-ink shadow-2xl shadow-ground transition duration-200 ${
          live ? 'motion-safe:hover:-translate-y-1' : 'grayscale-[0.6]'
        }`}
      >
        <header className={`relative isolate flex items-start justify-between gap-3 overflow-hidden px-5 pt-5 pb-4 ${live ? 'bg-accent text-on-accent' : 'bg-border-muted text-text'}`}>
          <div className="flex min-w-0 flex-col gap-1">
            <p className="truncate text-[11px] font-semibold uppercase tracking-[1.5px] opacity-75">{venue}</p>
            <h3 id={`ticket-${t.id}`} className="font-display text-[28px] font-extrabold uppercase leading-[0.95] text-balance">
              {t.event.title}
            </h3>
            <p className="text-sm font-medium opacity-85">{formatDate(t.event.starts_at, t.event.timezone, copy.meta.intl)}</p>
          </div>
          <span
            aria-hidden="true"
            className="flex size-11 shrink-0 items-center justify-center rounded-pill bg-ticket-ink font-display text-[22px] font-extrabold text-accent"
          >
            {club.crestInitial}
          </span>
        </header>

        <dl className="grid grid-cols-3 gap-3 px-5 pt-4">
          {fact(c.zone, t.zone.name)}
          {fact(c.gates, t.gate_codes.length ? t.gate_codes.join(' · ') : '-')}
          {fact(c.kickoff, formatTime(t.event.starts_at, t.event.timezone, copy.meta.intl))}
        </dl>
        <dl className="flex items-end justify-between gap-3 px-5 pt-4">
          <div className="flex min-w-0 flex-col gap-1">
            <dt className={label}>{t.seat_label ? c.seat : c.holder}</dt>
            <dd className="truncate text-[15px] font-semibold">{t.seat_label ?? t.holder_label ?? '-'}</dd>
          </div>
          <div className="flex flex-col items-end gap-1 text-end">
            <dt className={label}>{c.number}</dt>
            <dd dir="ltr" className="font-display text-[18px] font-bold tracking-[1.5px]">
              {t.ticket_no}
            </dd>
          </div>
        </dl>
        {t.seat_label && t.holder_label && (
          <p className="px-5 pt-2 text-sm text-ticket-muted">
            {c.holder} · <span className="font-semibold text-ticket-ink">{t.holder_label}</span>
          </p>
        )}

        <div aria-hidden="true" className="relative mt-4 flex h-7 items-center">
          <span className="absolute -start-3.5 size-7 rounded-pill bg-ground" />
          <span className="mx-6 grow border-t-2 border-dashed border-ticket-rule" />
          <span className="absolute -end-3.5 size-7 rounded-pill bg-ground" />
        </div>

        <div className="mt-auto flex flex-col gap-4 px-5 pt-2 pb-5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className={`rounded-chip px-3 py-1.5 text-sm font-semibold ${valid ? 'bg-success-bg text-success-text' : 'bg-ticket-rule text-ticket-ink'}`}>
              {status}
            </span>
            {valid && <span className="text-sm font-medium text-ticket-muted">{t.past ? c.pastChip : until(c, t.event.starts_at, now)}</span>}
          </div>
          {/* ponytail: a plain download link, so a 404/409 (ticket blocked or gone since the list loaded) saves the JSON
              error as a file. Fix: fetch the PDF, save the blob, and show the error copy on a non-2xx. */}
          {valid && (
            <a
              href={`/api/tickets/${encodeURIComponent(t.id)}/pdf`}
              download
              aria-label={fmt(c.pdfLabel, { no: t.ticket_no })}
              className={`${cta} h-12 bg-ticket-ink text-ticket-paper hover:bg-surface2`}
            >
              <DownloadIcon className="size-5" />
              {c.pdf}
            </a>
          )}
          <p className="flex items-center justify-center gap-2 text-xs text-ticket-muted">
            <QrIcon className="size-4" />
            {c.qrHint}
          </p>
        </div>
      </article>
    </li>
  )
}

/** Two ticket-shaped placeholders while the list loads (same footprint as a card: no jump when it arrives). */
function Skeleton({ label }: { label: string }) {
  const bar = 'rounded-pill bg-surface2'
  return (
    <div aria-busy="true">
      <p aria-live="polite" className="sr-only">
        {label}
      </p>
      <ul aria-hidden="true" className="grid gap-8 sm:grid-cols-2 lg:grid-cols-3">
        {[0, 1].map((n) => (
          <li key={n} className="flex h-[440px] flex-col overflow-hidden rounded-card-large border border-border bg-surface motion-safe:animate-pulse">
            <div className="flex flex-col gap-3 bg-surface2/60 p-5">
              <span className={`h-3 w-24 ${bar}`} />
              <span className={`h-7 w-4/5 ${bar}`} />
              <span className={`h-3 w-32 ${bar}`} />
            </div>
            <div className="grid grid-cols-3 gap-3 p-5">
              {[0, 1, 2].map((k) => (
                <span key={k} className={`h-10 ${bar}`} />
              ))}
            </div>
            <span className={`mx-5 mt-auto mb-5 h-12 rounded-control bg-surface2`} />
          </li>
        ))}
      </ul>
    </div>
  )
}
