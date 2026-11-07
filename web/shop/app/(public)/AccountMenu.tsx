'use client'

import { useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import { fmt, type Dict } from '@/lib/i18n'
import { LogoutIcon, PersonIcon, TicketIcon } from '../icons'

type Copy = Pick<Dict['header'], 'login' | 'tickets' | 'account' | 'accountOf' | 'signedIn' | 'logout' | 'logoutFailed'>
type Me = { name: string | null } | 'out' | 'loading'

/** "Yazid Aqel" → "YA", "salma" → "S"; null without a usable name. */
export function initials(name: string | null): string | null {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean)
  const s = parts.slice(0, 2).map((p) => [...p][0]!.toUpperCase()).join('')
  return s || null
}

/**
 * The header's account island. The session cookie is httpOnly and the public pages never read it (they stay
 * cacheable), so the browser asks `GET /api/me` once per page load: signed in → initials avatar with a menu (Mes billets,
 * Se déconnecter via `POST /api/auth/logout`); otherwise "Se connecter". While signed out it asks again after each
 * navigation, so the header follows a login without a reload. Until the answer, an empty slot of the same width.
 */
export function AccountMenu({ copy }: { copy: Copy }) {
  const [me, setMe] = useState<Me>('loading')
  const [failed, setFailed] = useState(false)
  const pathname = usePathname()
  const router = useRouter()
  const menu = useRef<HTMLDivElement>(null)
  const recheck = me === 'loading' || me === 'out' ? pathname : null
  const known = useRef<string | null>(null) // a pathname whose answer is already known (just signed out there)

  useEffect(() => {
    if (recheck === null || recheck === known.current) return
    known.current = recheck
    const abort = new AbortController()
    fetch('/api/me', { cache: 'no-store', signal: abort.signal })
      .then(async (res) => setMe(res.ok ? { name: ((await res.json()) as { display_name: string | null }).display_name } : 'out'))
      .catch(() => abort.signal.aborted || setMe('out'))
    return () => {
      abort.abort()
      known.current = null
    }
  }, [recheck])

  const close = () => menu.current?.hidePopover?.()

  async function logout() {
    const res = await fetch('/api/auth/logout', { method: 'POST' }).catch(() => null)
    setFailed(!res?.ok)
    if (!res?.ok) return
    close()
    known.current = pathname
    setMe('out')
    if (/^\/(me|orders)(\/|$)/.test(pathname)) router.replace('/')
    router.refresh()
  }

  const slot = 'flex min-w-30 justify-end sm:min-w-44'
  if (me === 'loading') return <div aria-hidden="true" className={slot} />
  if (me === 'out') {
    return (
      <div className={slot}>
        <Link
          href={`/login?next=${encodeURIComponent(pathname === '/' ? '/me/tickets' : pathname)}`}
          className="flex min-h-touch-min items-center rounded-chip border border-border px-4 text-sm font-medium text-text transition-colors duration-200 hover:border-accent hover:text-accent"
        >
          {copy.login}
        </Link>
      </div>
    )
  }

  const short = initials(me.name)
  const item = 'flex min-h-touch-min w-full items-center gap-3 rounded-chip px-3 text-start text-sm text-text hover:bg-surface2'
  return (
    <div className={slot}>
      <button
        type="button"
        popoverTarget="account-menu"
        aria-label={me.name ? fmt(copy.accountOf, { name: me.name }) : copy.account}
        className="flex min-h-touch-min items-center gap-2.5 rounded-pill border border-border bg-surface p-1 transition-colors duration-200 hover:border-accent sm:pe-4"
      >
        <span className="flex size-9 items-center justify-center rounded-pill bg-accent font-display text-base font-bold text-on-accent">
          {short ?? <PersonIcon />}
        </span>
        {me.name && <span className="max-w-28 truncate text-sm font-semibold max-sm:hidden">{me.name.trim().split(/\s+/)[0]}</span>}
      </button>
      <div
        ref={menu}
        id="account-menu"
        popover="auto"
        className="inset-auto end-[max(1.5rem,calc((100vw-1180px)/2+1.5rem))] top-18 m-0 w-64 rounded-control border border-border bg-surface p-2 text-text shadow-2xl shadow-ground"
      >
        <p className="flex flex-col px-3 pt-2 pb-3">
          <span className="text-caption uppercase tracking-[1px] text-text-muted">{copy.signedIn}</span>
          {me.name && <span className="truncate font-semibold">{me.name}</span>}
        </p>
        <Link href="/me/tickets" onClick={close} className={item}>
          <TicketIcon className="size-5 text-text-muted" />
          {copy.tickets}
        </Link>
        <button type="button" onClick={logout} className={item}>
          <LogoutIcon className="size-5 text-text-muted" />
          {copy.logout}
        </button>
        {failed && (
          <p role="alert" className="px-3 pt-1 pb-2 text-body-small text-danger">
            {copy.logoutFailed}
          </p>
        )}
      </div>
    </div>
  )
}
