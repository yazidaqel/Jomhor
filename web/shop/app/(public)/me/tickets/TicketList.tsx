'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { fmt, type Dict } from '@/lib/i18n'
import type { TicketView } from '@/lib/tickets'
import { formatDateTime } from '@/lib/time'

type Copy = Pick<Dict, 'ticketsPage' | 'errors' | 'meta'>

// ponytail: placeholder link. The fan app registers the `jomhor-fan` scheme but routes only `orders/{id}` (Android
// intent filter host `orders`); it needs a `tickets` host → `/tickets`. No store fallback when the app is missing.
const APP_TICKETS_URL = 'jomhor-fan://tickets'

const cta = 'flex min-h-touch-min items-center justify-center rounded-control px-5 text-cta'

type State = { tickets: TicketView[] } | 'loading' | 'error' | 'UNAUTHORIZED'

/** W5 list: `/api/tickets` once (and on "Réessayer"), upcoming then past, a PDF link per valid ticket. */
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
        setState((await res.json()) as { tickets: TicketView[] })
      })
      .catch(() => abort.signal.aborted || setState('error'))
    return () => abort.abort()
  }, [attempt])

  if (state === 'loading') return <p aria-live="polite" className="text-body text-text-secondary">{c.loading}</p>
  if (state === 'UNAUTHORIZED' || state === 'error') {
    return (
      <div role="alert" className="flex flex-col items-start gap-4">
        <p className="text-body text-text-secondary">{state === 'error' ? c.error : copy.errors.UNAUTHORIZED}</p>
        {state === 'error' ? (
          <button type="button" onClick={() => (setState('loading'), setAttempt((n) => n + 1))} className={`${cta} bg-accent text-on-accent hover:bg-accent-hover`}>
            {c.retry}
          </button>
        ) : (
          <Link href={`/login?next=${encodeURIComponent('/me/tickets')}`} className={`${cta} bg-accent text-on-accent hover:bg-accent-hover`}>
            {c.login}
          </Link>
        )}
      </div>
    )
  }

  const upcoming = state.tickets.filter((t) => !t.past)
  const past = state.tickets.filter((t) => t.past)
  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-col items-start gap-2">
        <p className="text-body text-text-secondary">{c.intro}</p>
        <a href={APP_TICKETS_URL} className={`${cta} border border-border text-text hover:border-border-muted`}>
          {c.openApp}
        </a>
        <p className="text-body-small text-text-muted">{c.openAppHint}</p>
      </div>
      {state.tickets.length === 0 && (
        <div className="flex flex-col items-start gap-4">
          <p className="text-body text-text-secondary">{c.empty}</p>
          <Link href="/" className={`${cta} bg-accent text-on-accent hover:bg-accent-hover`}>
            {c.browse}
          </Link>
        </div>
      )}
      <Group id="upcoming" title={c.upcoming} list={upcoming} copy={copy} />
      <Group id="past" title={c.past} list={past} copy={copy} />
    </div>
  )
}

function Group({ id, title, list, copy }: { id: string; title: string; list: TicketView[]; copy: Copy }) {
  if (list.length === 0) return null
  return (
    <section aria-labelledby={`tickets-${id}`} className="flex flex-col gap-4">
      <h2 id={`tickets-${id}`} className="font-display text-h2">
        {title}
      </h2>
      <ul className="grid gap-4 md:grid-cols-2">
        {list.map((t) => (
          <TicketCard key={t.id} t={t} copy={copy} />
        ))}
      </ul>
    </section>
  )
}

function TicketCard({ t, copy }: { t: TicketView; copy: Copy }) {
  const c = copy.ticketsPage
  const valid = t.status === 'VALID'
  const status = Object.hasOwn(c, t.status) ? (c as Record<string, string>)[t.status] : t.status
  const venue = t.event.venue.city ? `${t.event.venue.name}, ${t.event.venue.city}` : t.event.venue.name
  const row = (label: string, value: string) => (
    <div className="flex justify-between gap-3">
      <dt className="text-text-muted">{label}</dt>
      <dd className="text-end font-medium">{value}</dd>
    </div>
  )
  return (
    <li className={`flex flex-col gap-3 rounded-card border border-border bg-surface p-5 ${t.past ? 'opacity-80' : ''}`}>
      <div className="flex flex-col gap-1">
        <p className="font-display text-xl font-bold uppercase">{t.event.title}</p>
        <p className="text-body-small text-text-secondary">{formatDateTime(t.event.starts_at, t.event.timezone, copy.meta.intl)}</p>
        <p className="text-body-small text-text-muted">{venue}</p>
      </div>
      <dl className="flex flex-col gap-1.5 text-sm">
        {t.holder_label && row(c.holder, t.holder_label)}
        {row(c.zone, t.zone.name)}
        {t.gate_codes.length > 0 && row(c.gates, t.gate_codes.join(' · '))}
        {t.seat_label && row(c.seat, t.seat_label)}
        {row(c.number, t.ticket_no)}
        <div className="flex justify-between gap-3">
          <dt className="text-text-muted">{c.status}</dt>
          <dd className={`text-end font-semibold ${valid ? 'text-success-text' : 'text-danger'}`}>{status}</dd>
        </div>
      </dl>
      {/* ponytail: a plain download link, so a 404/409 (ticket blocked or gone since the list loaded) saves the JSON
          error as a file. Fix: fetch the PDF, save the blob, and show the error copy on a non-2xx. */}
      {valid && (
        <a
          href={`/api/tickets/${encodeURIComponent(t.id)}/pdf`}
          download
          aria-label={fmt(c.pdfLabel, { no: t.ticket_no })}
          className={`${cta} self-start bg-accent text-on-accent hover:bg-accent-hover`}
        >
          {c.pdf}
        </a>
      )}
    </li>
  )
}
