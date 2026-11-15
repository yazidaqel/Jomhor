'use client'

import { useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import type { Dict } from '@/lib/i18n'
import { postJson, type Result } from '@/lib/order-client'
import { closedReason, isWaiting, JOIN_PARAM, markJoin, takeJoin, toEntry, type WaitlistEntry } from '@/lib/waitlist'
import { CheckIcon } from '../../../../icons'

/** W2 sold-out waitlist (server part): the event, whether a session cookie is there, and the login back to W2 + join. */
export type WaitlistView = { eventId: number; signedIn: boolean; login: string }
type Copy = Pick<Dict, 'event' | 'room' | 'waitlist' | 'errors'>

const cta = 'h-cta-height items-center justify-center gap-2 rounded-control text-cta'
const filled = `${cta} bg-accent text-on-accent transition-colors duration-200 hover:bg-accent-hover disabled:bg-surface2 disabled:text-text-disabled`
const outlined = `${cta} cursor-pointer border-2 border-accent text-accent`
const SHEET = 'waitlist-sheet'

/**
 * The W2 summary aside while the event is sold out, on sale and without a live room (DESIGN Lot 5, as screens 1 and 2):
 * "Liste d'attente" (signed out → login, back with `?waitlist=1`, which joins when this tab's CTA set the one-shot
 * marker), then the outlined "Sur la liste d'attente" that opens "Premier arrivé, premier servi" + "Quitter la liste".
 * Phone width: the control is a sticky bottom bar; on the list it opens and scrolls to the sheet. The entry is read once
 * on open, never polled. `WAITLIST_CLOSED` shows its reason and re-reads the event.
 */
export function WaitlistPanel({ view, copy }: { view: WaitlistView; copy: Copy }) {
  const router = useRouter()
  const [signedIn, setSignedIn] = useState(view.signedIn)
  const [entry, setEntry] = useState<WaitlistEntry | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [sheet, setSheet] = useState(false)
  const inline = useRef<HTMLElement | null>(null)
  const phone = useRef<HTMLElement | null>(null)
  const sheetRef = useRef<HTMLDetailsElement>(null)
  const refocus = useRef(false) // the button the fan pressed is replaced: focus its successor
  const url = `/api/waitlist/${view.eventId}`
  const state = !signedIn ? 'signed-out' : isWaiting(entry) ? 'joined' : 'open'

  const apply = (r: Result) => {
    if (r.ok) {
      setEntry(toEntry(r.data)) // DELETE 204: no body, no entry
      setSheet(false)
      setError('')
    } else if (r.status === 401) {
      setSignedIn(false) // the cookie was there but the session is over
      setError(copy.errors.UNAUTHORIZED)
    } else {
      const reason = closedReason(r.data)
      setError(reason ? copy.waitlist[reason] : r.status === 0 ? copy.errors.NETWORK : r.status === 404 ? copy.errors.NOT_FOUND : copy.errors.generic)
      if (reason) router.refresh()
    }
  }

  // Decided once per mount: StrictMode (dev) runs the effect twice and its cleanup aborts the first PUT, so the second
  // run must still join (PUT is idempotent) instead of re-reading the marker and the param the first run consumed.
  const joinOnOpen = useRef<boolean | null>(null)
  useEffect(() => {
    if (!view.signedIn) return
    const stop = new AbortController()
    if (joinOnOpen.current === null) {
      const back = new URLSearchParams(location.search).has(JOIN_PARAM)
      joinOnOpen.current = takeJoin(view.eventId) && back // a crafted ?waitlist=1 without this tab's marker only reads
      if (back) history.replaceState(history.state, '', location.pathname) // once: a reload doesn't join again
    }
    const join = joinOnOpen.current
    postJson(url, undefined, join ? 'PUT' : 'GET', stop.signal).then(
      (r) => (join || r.ok || r.status === 401) && apply(r), // a failed read stays quiet: the CTA joins anyway
      () => {}, // left the page
    )
    return () => stop.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- read once when W2 opens (never polled)
  }, [])

  useEffect(() => {
    if (!refocus.current) return
    refocus.current = false
    // The visible one: the inline control from md up, the bottom bar below (no matchMedia, as in tests: inline).
    const wide = typeof matchMedia === 'function' ? matchMedia('(min-width: 48rem)').matches : true
    ;(wide ? inline : phone).current?.focus()
  }, [state])

  const send = async (method: 'PUT' | 'DELETE') => {
    setBusy(true)
    refocus.current = true
    apply(await postJson(url, undefined, method))
    setBusy(false)
  }
  const openSheet = () => {
    setSheet(true)
    sheetRef.current?.scrollIntoView?.({ block: 'nearest' })
  }

  /** The current control: inline (from md up) or in the phone bar (below md). On the list, inline is the sheet's summary. */
  const control = (bar: boolean) => {
    const shown = bar ? 'flex w-full' : 'hidden md:flex w-full'
    const ref = (n: HTMLElement | null) => {
      ;(bar ? phone : inline).current = n
    }
    if (state === 'signed-out')
      return (
        <Link ref={ref} href={view.login} onClick={() => markJoin(view.eventId)} className={`${shown} ${filled}`}>
          {copy.waitlist.join}
        </Link>
      )
    if (state === 'open')
      return (
        <button ref={ref} type="button" disabled={busy} onClick={() => send('PUT')} className={`${shown} ${filled}`}>
          {copy.waitlist.join}
        </button>
      )
    const label = (
      <>
        <CheckIcon className="size-5" />
        {copy.waitlist.joined}
      </>
    )
    return bar ? (
      <button ref={ref} type="button" aria-expanded={sheet} aria-controls={SHEET} onClick={openSheet} className={`${shown} ${outlined}`}>
        {label}
      </button>
    ) : (
      <summary ref={ref} className={`${shown} ${outlined} list-none [&::-webkit-details-marker]:hidden`}>
        {label}
      </summary>
    )
  }

  return (
    <aside
      aria-labelledby="waitlist-title"
      data-waitlist={state}
      className="flex w-full flex-[1_1_320px] flex-col gap-3.5 rounded-card border border-border bg-surface p-5 shadow-2xl shadow-ground md:sticky md:top-24 md:max-w-[380px]"
    >
      <h2 id="waitlist-title" className="font-display text-h2 uppercase">
        {copy.event.soldOut}
      </h2>
      <p className="text-body-small text-text-secondary">{copy.room.soldOut}</p>
      {state === 'joined' ? (
        <details id={SHEET} ref={sheetRef} open={sheet} onToggle={(e) => setSheet(e.currentTarget.open)} className="scroll-mb-28 rounded-control">
          {control(false)}
          <p className="mt-3 text-body-small text-text-secondary">{copy.waitlist.sheet}</p>
          <button
            type="button"
            disabled={busy}
            onClick={() => send('DELETE')}
            className="mt-1 flex min-h-touch-min w-full items-center justify-center rounded-control text-body-small font-semibold text-accent hover:underline disabled:text-text-disabled"
          >
            {copy.waitlist.leave}
          </button>
        </details>
      ) : (
        control(false)
      )}
      <p className="text-center text-xs text-text-muted">{copy.waitlist.channels}</p>
      <p aria-live="polite" className="text-body-small text-danger empty:hidden">
        {error}
      </p>
      {/* Phone width: the control is a sticky bottom bar (DESIGN screen 2 "sticky CTA"), as the room panel's. */}
      <div data-waitlist-bar className="fixed inset-x-0 bottom-0 z-20 border-t border-border bg-surface px-5 pt-3 pb-[max(12px,env(safe-area-inset-bottom))] md:hidden">
        {control(true)}
      </div>
    </aside>
  )
}
