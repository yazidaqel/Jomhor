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
 * Headers set by the caller are never overridden.
 */
export async function api<T>(path: string, { organizerId, ...init }: ApiInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (session.token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${session.token}`)
  if (organizerId && !headers.has('X-Organizer-Id')) headers.set('X-Organizer-Id', organizerId)
  if (typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  let res: Response
  try {
    res = await fetch(`/v1${path}`, { ...init, headers })
  } catch (e) {
    if (init.signal?.aborted) throw e // cancellation (e.g. TanStack Query) is not a network error
    throw new ApiError(0, 'NETWORK_ERROR', e instanceof Error ? e.message : String(e))
  }

  const text = await res.text()
  if (!res.ok) {
    const parsed = errorBody.safeParse(parseJson(text))
    throw parsed.success
      ? new ApiError(res.status, parsed.data.code, parsed.data.message, parsed.data.details)
      : new ApiError(res.status, `HTTP_${res.status}`, res.statusText)
  }
  return (text ? JSON.parse(text) : undefined) as T
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}
