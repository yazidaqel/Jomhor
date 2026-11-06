import 'server-only'

// Backend client for route handlers and server components. Env is read per call, so `next build` needs none.

export type ApiErrorBody = { code: string; message: string; details: Record<string, unknown> }

/** A backend error in the docs/04-api.md shape, with its HTTP status. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: Record<string, unknown> = {},
  ) {
    super(message)
  }

  get body(): ApiErrorBody {
    return { code: this.code, message: this.message, details: this.details }
  }
}

function env(name: 'API_BASE_URL' | 'ORGANIZER_ID'): string {
  const v = process.env[name]
  if (!v) throw new Error(`${name} is not set`)
  return v
}

/** The club this shop sells for (`/fan/events?organizer=`). */
export const organizerId = () => env('ORGANIZER_ID')

/** A backend call that takes longer fails as 502 instead of holding the fan's request. */
export const BACKEND_TIMEOUT_MS = 10_000

type Options =Omit<RequestInit, 'body'> & { json?: unknown; token?: string }

/** Calls `${API_BASE_URL}${path}`. 2xx → parsed JSON (undefined when empty); otherwise throws ApiError. */
export async function api<T>(path: string, { json, token, headers, ...init }: Options = {}): Promise<T> {
  const h = new Headers(headers)
  h.set('accept', 'application/json')
  if (json !== undefined) h.set('content-type', 'application/json')
  if (token) h.set('authorization', `Bearer ${token}`)
  let res: Response
  try {
    res = await fetch(`${env('API_BASE_URL')}${path}`, {
      cache: 'no-store',
      signal: AbortSignal.timeout(BACKEND_TIMEOUT_MS),
      ...init,
      headers: h,
      body: json === undefined ? undefined : JSON.stringify(json),
    })
  } catch (e) {
    console.error('backend call failed', path, e)
    throw new ApiError(502, 'INTERNAL_ERROR', 'Backend unreachable')
  }
  const text = await res.text()
  const data = text ? parse(text) : undefined
  if (res.ok) return data as T
  throw toApiError(res.status, data)
}

function parse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

function toApiError(status: number, data: unknown): ApiError {
  const b = (data ?? {}) as Partial<ApiErrorBody>
  if (typeof b.code === 'string') {
    const details = b.details && typeof b.details === 'object' ? b.details : {}
    return new ApiError(status, b.code, typeof b.message === 'string' ? b.message : '', details)
  }
  return new ApiError(status, status >= 500 ? 'INTERNAL_ERROR' : 'BAD_REQUEST', `HTTP ${status}`)
}
