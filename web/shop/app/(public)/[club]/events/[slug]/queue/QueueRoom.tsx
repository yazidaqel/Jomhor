'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { fmt, type Dict } from '@/lib/i18n'
import { errorCode, postJson, sleep, throwIfAborted, whenVisible, type Result } from '@/lib/order-client'
import { FINAL_QUEUE, firstPosition, litDots, mmss, saveAdmission, toQueue, waitText, type Queue } from '@/lib/queue'
import { useServerClock } from '@/lib/queue-hooks'
import { formatShortDate, formatTime } from '@/lib/time'
import { ArrowIcon, CheckIcon } from '../../../../../icons'

type Copy = Pick<Dict, 'room' | 'event' | 'errors' | 'meta'>
type Details = Record<string, unknown>

type View =
  | { kind: 'joining' }
  | { kind: 'queue'; q: Queue; first: number | null; offline: boolean }
  | { kind: 'notOpen'; opensAt: string }
  | { kind: 'limited'; left: number }
  | { kind: 'refused'; code: string; det: Details }

/** A join that waits for `opens_at` re-checks at least hourly (setTimeout can't hold weeks) and spreads the crowd. */
const NOT_OPEN_MAX_WAIT_MS = 3_600_000
const JOIN_JITTER_MS = 5000
/** Ceiling of the back-off while polls fail (network, 5xx). */
const POLL_MAX_MS = 30_000
/**
 * "Il reste {n} places" comes from the server render (event detail cached ≤ 15 s, plan Q163), so it is shown for
 * 5 min after the page load (server clock), then hidden; `sold_out` from the poll keeps saying when nothing is left.
 * ponytail: no live figure for long waits; upgrade = a stock figure in the `GET /fan/queue` response (contract change).
 */
const REMAINING_FRESH_MS = 300_000

const seconds = (v: unknown, fallback: number) => (Number.isSafeInteger(v) && (v as number) > 0 ? (v as number) : fallback)
const details = (r: Result) => (r.data?.details ?? {}) as Details

/**
 * W8 · the waiting room (DESIGN Lot 5, screen 12 states). Joins through `/api/queue` on mount (idempotent: a reload
 * keeps the place), then polls `GET {apiUrl}/fan/queue` straight from the browser with the queue token and no
 * credentials (plan Q152), at `poll_after_s`, only while the tab is visible, until ADMITTED, EXPIRED or ENDED.
 * The admitted token goes to this tab's sessionStorage for W2 and W3. Countdowns run on server time (`now`, then
 * each `server_time`, plus the page clock), never the device clock. A poll refused with 401 re-joins once; a
 * second 401 with no good poll in between stops on "Réessayer" (a backend refusing valid tokens must not turn every
 * fan into a join loop).
 */
export function QueueRoom(props: {
  eventId: number
  /** W2, and the login page back here. */
  eventHref: string
  loginHref: string
  /** API_PUBLIC_URL (with /v1), from the server. */
  apiUrl: string
  now: number
  timezone: string
  limit: number
  /** Σ remaining over the event's prices (event detail, cached ≤ 15 s; plan Q163). */
  remaining: number
  /** The event has an eligibility rule: a fan in the room passed it. */
  verified: boolean
  copy: Copy
}) {
  const { eventId, eventHref, loginHref, apiUrl, timezone, copy } = props
  const c = copy.room
  const router = useRouter()
  const [serverNow, resync, readNow] = useServerClock(props.now)
  const [view, setView] = useState<View>({ kind: 'joining' })
  const [attempt, setAttempt] = useState(0) // "Reprendre une place" / "Réessayer" start over

  useEffect(() => {
    const ctl = new AbortController()
    const { signal } = ctl

    /** A fresh answer, or (`offline`) the last one kept on screen after a failed poll. */
    function show(q: Queue, offline = false) {
      if (!offline) resync(Date.parse(q.server_time))
      if (q.status === 'ADMITTED' && q.admission) saveAdmission(eventId, q.admission)
      if (q.status === 'ENDED') router.replace(eventHref) // no room any more: buy on W2
      setView({ kind: 'queue', q, first: q.position === null ? null : firstPosition(eventId, q.position), offline })
    }

    async function join(): Promise<Queue | null> {
      let retried = false
      for (;;) {
        const r = await postJson('/api/queue', { event_id: eventId }, 'POST', signal)
        throwIfAborted(signal)
        const q = r.ok ? toQueue(r.data) : null
        if (q) return q
        const code = errorCode(r)
        const det = details(r)
        if (r.status === 401) {
          router.replace(loginHref)
          return null
        }
        if (code === 'QUEUE_NOT_OPEN' && det.reason === 'NOT_STARTED' && typeof det.opens_at === 'string') {
          setView({ kind: 'notOpen', opensAt: det.opens_at })
          const wait = Math.min(NOT_OPEN_MAX_WAIT_MS, Math.max(0, Date.parse(det.opens_at) - readNow()))
          await sleep(wait + Math.floor(Math.random() * JOIN_JITTER_MS), signal)
          continue
        }
        if ((code === 'QUEUE_NOT_OPEN' && det.reason !== 'CLOSED') || r.status === 404) {
          router.replace(eventHref) // ENDED or no room: buy without the room
          return null
        }
        if (code === 'QUEUE_RATE_LIMITED' && !retried) {
          retried = true // one automatic retry at retry_after_s, then a button
          for (let left = seconds(det.retry_after_s, 60); left > 0; left--) {
            setView({ kind: 'limited', left })
            await sleep(1000, signal)
          }
          continue
        }
        setView({ kind: 'refused', code, det })
        return null
      }
    }

    /** Polls until a final status (null), or until the token is refused (401): then the count of good polls before. */
    async function poll(q: Queue): Promise<number | null> {
      let delay = q.poll_after_s * 1000
      let fails = 0
      let good = 0
      while (!FINAL_QUEUE.includes(q.status) && q.poll_after_s > 0) {
        await sleep(delay, signal)
        await whenVisible(signal)
        let res: Response | null = null
        let data: { code?: unknown; details?: Details } | null = null
        try {
          res = await fetch(`${apiUrl}/fan/queue`, { headers: { 'x-queue-token': q.queue_token }, credentials: 'omit', cache: 'no-store', signal })
          data = await res.json().catch(() => null)
        } catch {
          // network: below
        }
        throwIfAborted(signal)
        const next = res?.ok ? toQueue(data) : null
        if (next) {
          q = next
          fails = 0
          good++
          delay = q.poll_after_s * 1000
          show(q)
        } else if (res?.status === 401) {
          return good
        } else if (res?.status === 429 && data?.code === 'QUEUE_RATE_LIMITED') {
          delay = seconds(data.details?.retry_after_s, 1) * 1000 // silent
        } else {
          fails++
          delay = Math.min(POLL_MAX_MS, Math.max(2000, q.poll_after_s * 1000) * 2 ** fails)
          show(q, true) // last state + "Hors ligne · ta place est gardée"
        }
      }
      return null
    }

    ;(async () => {
      let refused = 0 // 401s on polls with no good poll in between
      for (;;) {
        const q = await join()
        if (!q) return
        show(q)
        const good = await poll(q)
        if (good === null) return
        refused = good > 0 ? 1 : refused + 1
        if (refused > 1) return setView({ kind: 'refused', code: '', det: {} })
      }
    })().catch(() => {
      if (!signal.aborted) setView({ kind: 'refused', code: '', det: {} })
    })
    return () => ctl.abort()
  }, [attempt, eventId, eventHref, loginHref, apiUrl, router, resync, readNow])

  const again = (label: string) => (
    <button
      type="button"
      onClick={() => {
        setView({ kind: 'joining' })
        setAttempt((a) => a + 1)
      }}
      className="flex h-cta-height items-center justify-center rounded-control bg-accent px-6 text-cta text-on-accent transition-colors duration-200 hover:bg-accent-hover sm:self-start"
    >
      {label}
    </button>
  )

  if (view.kind !== 'queue') {
    const shown: [string, string | null] | null =
      view.kind === 'joining'
        ? [c.joining, null]
        : view.kind === 'notOpen'
          ? [c.notOpenTitle, fmt(c.notOpen, { time: formatTime(view.opensAt, timezone, copy.meta.intl) })]
          : view.kind === 'limited'
            ? [c.title, fmt(c.rateLimited, { n: view.left })]
            : null
    const refused = view.kind === 'refused' ? refusal(copy, view.code, view.det, timezone) : null
    const [title, text] = shown ?? [refused!.title, refused!.text]
    return (
      <div className="flex flex-col gap-6">
        <Title title={title} />
        {text && (
          <p data-state={view.kind} className="max-w-[560px] text-body text-text-secondary">
            {text}
          </p>
        )}
        <Dots running={view.kind === 'joining'} lit={0} label={c.progress} />
        {refused?.retry && again(c.retry)}
      </div>
    )
  }

  const { q, first, offline } = view
  const turnLeft = q.admission ? Math.ceil((Date.parse(q.admission.expires_at) - serverNow) / 1000) : 0
  const status = q.status === 'ADMITTED' && turnLeft <= 0 ? 'EXPIRED' : q.status
  const intl = copy.meta.intl
  const n = (v: number) => new Intl.NumberFormat(intl).format(v)
  const rows = [
    c.keepPlace,
    fmt(c.limit, { n: props.limit }),
    q.sold_out ? c.soldOut : serverNow - props.now < REMAINING_FRESH_MS ? fmt(c.remaining, { n: n(props.remaining) }) : null,
  ].filter((r): r is string => r !== null)

  let body: React.ReactNode
  if (status === 'ADMITTED') {
    body = (
      <>
        <Title label={c.title} title={c.turnTitle} />
        <p className="text-body text-text-secondary">{fmt(c.turnLeft, { time: mmss(turnLeft) })}</p>
        <Dots lit={11} turn label={c.progress} />
        <Link
          href={`${eventHref}#zones`}
          className="group flex h-cta-height items-center justify-center gap-2 rounded-control bg-accent px-6 text-cta text-on-accent transition-colors duration-200 hover:bg-accent-hover sm:self-start"
        >
          {c.choose}
          <ArrowIcon className="size-5" />
        </Link>
      </>
    )
  } else if (status === 'EXPIRED') {
    body = (
      <>
        <Title label={c.title} title={c.expiredTitle} />
        <Dots lit={0} label={c.progress} />
        {again(c.rejoin)}
      </>
    )
  } else if (status === 'PRE_QUEUE') {
    const left = Math.max(0, Math.ceil((Date.parse(q.sale_starts_at) - serverNow) / 1000))
    const time = formatTime(q.sale_starts_at, timezone, intl)
    body = (
      <>
        <Title title={fmt(c.drawLabel, { time })} />
        <div role="timer" className="flex gap-2">
          {(
            [
              [Math.floor(left / 3600), copy.event.hours],
              [Math.floor((left % 3600) / 60), copy.event.minutes],
              [left % 60, c.seconds],
            ] as const
          ).map(([v, unit]) => (
            <p key={unit} className="flex min-w-[76px] flex-col items-center gap-1 rounded-chip border border-border bg-surface px-3 pt-3 pb-2.5">
              <span className="font-display text-[36px] font-extrabold leading-none">{String(v).padStart(2, '0')}</span>
              <span className="text-caption uppercase tracking-[1px] text-text-muted">{unit}</span>
            </p>
          ))}
        </div>
        <p className="max-w-[560px] text-body text-text-secondary">{fmt(c.drawText, { time })}</p>
        <p className="font-semibold">{fmt(c.inRoom, { n: n(q.in_room) })}</p>
        <Dots running lit={0} label={c.progress} />
      </>
    )
  } else if (q.position === null) {
    body = (
      <>
        <Title title={c.rankingTitle} />
        <p className="text-body text-text-secondary">{c.ranking}</p>
        <p className="font-semibold">{fmt(c.inRoom, { n: n(q.in_room) })}</p>
        <Dots running lit={0} label={c.progress} />
      </>
    )
  } else {
    const ahead = q.position - 1
    body = (
      <>
        <Title label={c.positionLabel} title={null} />
        {/* Re-mounted on change so the figure rolls in (motion-safe). */}
        <p key={q.position} className="font-display text-[72px] font-extrabold leading-none motion-safe:animate-rise sm:text-[88px]">
          {fmt(c.position, { n: n(q.position) })}
        </p>
        <p className="text-body font-semibold">{ahead === 0 ? c.aheadNone : ahead === 1 ? c.aheadOne : fmt(c.ahead, { n: n(ahead) })}</p>
        {status === 'PAUSED' ? (
          <p role="status" className="text-body font-semibold text-accent">
            {c.paused}
          </p>
        ) : (
          q.estimated_wait_s !== null && <p className="text-body text-text-secondary">{fmt(c.wait, { time: waitText(c, q.estimated_wait_s) })}</p>
        )}
        <Dots lit={litDots(q.position, first ?? q.position)} label={c.progress} />
      </>
    )
  }

  return (
    <div data-status={status} className="flex flex-col gap-6">
      {body}
      {offline && (
        <p role="status" className="rounded-chip border border-border bg-surface px-3 py-2 text-body-small text-text-secondary">
          {c.offline}
        </p>
      )}
      {status !== 'ADMITTED' && status !== 'EXPIRED' && (
        <ul className="flex flex-col gap-2 rounded-card border border-border bg-surface p-5 text-body-small text-text-secondary">
          {props.verified && (
            <li className="flex items-center gap-2 font-semibold text-accent">
              <CheckIcon className="size-4" />
              {c.verified}
            </li>
          )}
          {rows.map((r) => (
            <li key={r}>{r}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** The state title, announced politely when it changes (the position figure is not: it moves every few seconds). */
function Title({ label, title }: { label?: string; title: string | null }) {
  return (
    <div aria-live="polite" aria-atomic="true" className="flex flex-col gap-2">
      {label && <p className="text-label uppercase text-accent">{label}</p>}
      {title && <h2 className="font-display text-[40px] font-extrabold uppercase leading-[0.95] text-balance sm:text-[56px]">{title}</h2>}
    </div>
  )
}

/**
 * The twelve dots (DESIGN principle 6, Lot 5): `lit` white dots fill in reading order (from the right in AR); the
 * twelfth is amber, outlined until the turn, then filled and swelling once. `running` = the light running along
 * them (indeterminate). A straight row, never the J mark.
 */
function Dots({ lit, running = false, turn = false, label }: { lit: number; running?: boolean; turn?: boolean; label: string }) {
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={12}
      aria-valuenow={running ? undefined : turn ? 12 : lit}
      className="flex items-center gap-2.5 sm:gap-3"
    >
      {Array.from({ length: 12 }, (_, i) =>
        i === 11 ? (
          <span key={i} data-dot={turn ? 'turn' : 'twelfth'} className={`size-4 rounded-pill sm:size-5 ${turn ? 'bg-accent motion-safe:animate-swell' : 'border-2 border-accent'}`} />
        ) : (
          <span
            key={i}
            data-dot={i < lit || turn ? 'lit' : 'off'}
            style={running ? { animationDelay: `${i * 120}ms` } : undefined}
            className={`size-4 rounded-pill transition-colors duration-200 sm:size-5 ${
              running ? 'bg-text opacity-20 motion-safe:animate-run' : i < lit || turn ? 'bg-text' : 'bg-border'
            }`}
          />
        ),
      )}
    </div>
  )
}

/** Copy for a refused join (DESIGN screen 12 other states). */
function refusal(copy: Copy, code: string, det: Details, timezone: string): { title: string; text: string; retry: boolean } {
  const c = copy.room
  const date = (v: unknown) => (typeof v === 'string' ? formatShortDate(v, timezone, copy.meta.intl) : '')
  switch (code) {
    case 'NOT_ELIGIBLE': {
      const text =
        det.reason === 'ACCOUNT_TOO_NEW'
          ? fmt(c.ACCOUNT_TOO_NEW, { date: date(det.verified_before) })
          : det.reason === 'NOT_MEMBER'
            ? fmt(c.NOT_MEMBER, { date: date(det.member_joined_before) })
            : det.reason === 'NOT_ENOUGH_EVENTS'
              ? fmt(c.NOT_ENOUGH_EVENTS, { n: String(det.min_events_attended ?? ''), attended: String(det.attended ?? '') })
              : ''
      return { title: c.NOT_ELIGIBLE, text, retry: false }
    }
    case 'QUEUE_DEVICE_LIMIT':
      return { title: c.title, text: c.QUEUE_DEVICE_LIMIT, retry: false }
    case 'QUEUE_RATE_LIMITED':
      return { title: c.title, text: c.rateLimitedAgain, retry: true }
    case 'QUEUE_NOT_OPEN':
      return { title: c.title, text: c.closed, retry: false }
    default:
      return { title: c.title, text: Object.hasOwn(copy.errors, code) ? (copy.errors as Record<string, string>)[code]! : copy.errors.generic, retry: true }
  }
}
