import { useSyncExternalStore } from 'react'

// Session in sessionStorage: survives a reload in the tab, gone when the tab closes.
// The API client reads `session.token`. The selected organizer stays private to this module: the shell
// reads it through useSelectedOrganizerId() and hands it down; features get it from useOrganizer().
const TOKEN = 'jomhor.session'
const ORGANIZER = 'jomhor.organizer'
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())
let expiryTimer: ReturnType<typeof setTimeout> | undefined

function stored(): { token?: unknown; expires_at?: unknown } {
  try {
    return JSON.parse(sessionStorage.getItem(TOKEN) ?? '{}') ?? {}
  } catch {
    return {}
  }
}

/** Ends the session (storage removed, subscribers notified) when `expires_at` passes, even while idle. */
function scheduleExpiry() {
  clearTimeout(expiryTimer)
  const expiresAt = Date.parse(String(stored().expires_at))
  if (Number.isNaN(expiresAt)) return
  // setTimeout caps at ~24.8 days; on an early wake-up, schedule again.
  const delay = Math.min(Math.max(expiresAt - Date.now(), 0), 2 ** 31 - 1)
  expiryTimer = setTimeout(() => (session.token ? scheduleExpiry() : session.clear()), delay)
}

export const session = {
  /** The bearer token, or null when absent or expired (an expired token is a logged-out session). */
  get token(): string | null {
    const { token, expires_at } = stored()
    return typeof token === 'string' && Date.parse(String(expires_at)) > Date.now() ? token : null
  },
  /** A new session never inherits the previous one's organizer (subscribers also drop the Query cache). */
  start(token: string, expiresAt: string) {
    sessionStorage.setItem(TOKEN, JSON.stringify({ token, expires_at: expiresAt }))
    sessionStorage.removeItem(ORGANIZER)
    scheduleExpiry()
    emit()
  },
  selectOrganizer(id: string) {
    sessionStorage.setItem(ORGANIZER, id)
    emit()
  },
  clear() {
    clearTimeout(expiryTimer)
    sessionStorage.removeItem(TOKEN)
    sessionStorage.removeItem(ORGANIZER)
    emit()
  },
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => void listeners.delete(listener)
  },
}

scheduleExpiry() // a session restored on reload (an already expired one is cleared right away)

export function useSessionToken() {
  return useSyncExternalStore(session.subscribe, () => session.token)
}

/** The organizer chosen in the switcher, if any; the shell validates it against the memberships. */
export function useSelectedOrganizerId() {
  return useSyncExternalStore(session.subscribe, () => sessionStorage.getItem(ORGANIZER))
}
