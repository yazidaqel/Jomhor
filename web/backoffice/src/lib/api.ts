import { z } from 'zod'
import { session } from './session'

// Error body from docs/04-api.md: { code, message, details }.
const errorBody = z.object({
  code: z.string(),
  message: z.string(),
  details: z
    .record(z.string(), z.unknown())
    .nullish()
    .transform((d) => d ?? {}),
})

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(status: number, code: string, message: string, details: Record<string, unknown> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

export type ApiInit = RequestInit & {
  /** Sent as X-Organizer-Id. Pass the same organizer as the query key; omit for /org/me and /org/auth/*. */
  organizerId?: string
}

/**
 * Calls `/v1{path}` with the session's bearer token; throws ApiError on non-2xx or network failure.
 * Headers set by the caller are never overridden. A 401 outside /org/auth/* clears the session it was sent with.
 */
export async function api<T>(path: string, init: ApiInit = {}): Promise<T> {
  const text = await request(path, init, (res) => res.text())
  return (text ? JSON.parse(text) : undefined) as T
}

/**
 * Same as `api`, for a file (CSV export): the raw bytes, so a UTF-8 BOM survives (`text()` would strip it), then
 * saved under the server's `Content-Disposition` filename, else `fallback`. A plain link cannot carry the bearer
 * token and X-Organizer-Id.
 */
export async function download(path: string, fallback: string, init: ApiInit = {}) {
  const { blob, name } = await request(path, init, async (res) => ({
    blob: await res.blob(),
    name: /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1],
  }))
  const url = URL.createObjectURL(blob)
  const a = Object.assign(document.createElement('a'), { href: url, download: name ?? fallback })
  a.click()
  // Not at once: Firefox and Safari can still be reading the URL when click() returns.
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function request<B>(path: string, { organizerId, ...init }: ApiInit, read: (res: Response) => Promise<B>): Promise<B> {
  const headers = new Headers(init.headers)
  const token = session.token
  if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)
  if (organizerId && !headers.has('X-Organizer-Id')) headers.set('X-Organizer-Id', organizerId)
  if (typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  let res: Response
  let body: B | string
  try {
    res = await fetch(`/v1${path}`, { ...init, headers })
    body = res.ok ? await read(res) : await res.text() // can fail too: connection dropped mid-body
  } catch (e) {
    if (init.signal?.aborted) throw e // cancellation (e.g. TanStack Query) is not a network error
    throw new ApiError(0, 'NETWORK_ERROR', e instanceof Error ? e.message : String(e))
  }

  if (!res.ok) {
    // A rejected token ends the session (the shell then redirects to /login), unless a new session
    // started while this request was in flight. /org/auth/* answers 401 for a wrong OTP: not a session problem.
    if (res.status === 401 && !path.startsWith('/org/auth/') && session.token === token) session.clear()
    const parsed = errorBody.safeParse(parseJson(body as string))
    throw parsed.success
      ? new ApiError(res.status, parsed.data.code, parsed.data.message, parsed.data.details)
      : new ApiError(res.status, `HTTP_${res.status}`, res.statusText)
  }
  return body as B
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/** `/a/b/c` with each segment URL-encoded. */
export const path = (...parts: (string | number)[]) => parts.map((p) => `/${encodeURIComponent(p)}`).join('')
