import 'server-only'
import { isIP } from 'node:net'
import { ApiError } from './api'

// Helpers for route handlers: same-origin guard, error responses, the session cookie.

/** httpOnly cookie holding the fan JWT. `__Host-`: Secure, Path=/, no Domain. Never readable by client JS. */
export const SESSION_COOKIE = '__Host-jomhor_session'

/**
 * Mutating handlers accept same-origin browser requests only: `Origin` must equal `SHOP_ORIGIN` (scheme, host and
 * port). Host and X-Forwarded-Host are ignored. Unset `SHOP_ORIGIN` → every request is refused (fail closed).
 */
export function isSameOrigin(req: Request): boolean {
  const origin = req.headers.get('origin')
  const shop = process.env.SHOP_ORIGIN
  if (!shop) console.error('SHOP_ORIGIN is not set: refusing mutating requests')
  if (!origin || !shop) return false
  try {
    return new URL(origin).origin === new URL(shop).origin
  } catch {
    return false // "null" and garbage
  }
}

export const errorResponse = (status: number, code: string, message = '') =>
  Response.json({ code, message, details: {} }, { status })

export const forbidden = () => errorResponse(403, 'FORBIDDEN', 'Cross-origin request')

export const badBody = () => errorResponse(422, 'VALIDATION_FAILED', 'Invalid JSON body')

/** Backend errors pass through in the 04 shape; anything else is a 500 with a fixed message. */
export function fromError(e: unknown): Response {
  if (e instanceof ApiError) return Response.json(e.body, { status: e.status })
  console.error(e)
  return errorResponse(500, 'INTERNAL_ERROR', 'Internal error')
}

/** The request body as a JSON object, or null. */
export async function readJson(req: Request): Promise<Record<string, unknown> | null> {
  try {
    const v: unknown = await req.json()
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

/**
 * The fan's address: the X-Forwarded-For entry appended by the outermost of `TRUSTED_PROXY_HOPS` proxies in front of
 * the shop (default 1, the ALB), i.e. index `length - hops`. Entries to its left are client-supplied and ignored.
 * Null when hops is 0 or invalid, the list is too short, or the entry is not an IP.
 */
export function clientIp(req: Request): string | null {
  const hops = Number(process.env.TRUSTED_PROXY_HOPS ?? '1')
  const chain = (req.headers.get('x-forwarded-for') ?? '').split(',').map((s) => s.trim())
  const ip = Number.isSafeInteger(hops) && hops > 0 ? chain[chain.length - hops] : undefined
  return ip && isIP(ip) ? ip : null
}

/**
 * Exactly one `X-Forwarded-For` for the backend's per-IP OTP limit (the backend trusts it only from its configured
 * internal proxies). No usable address → no header, so the backend counts the shop's own address (fail closed).
 */
export function forwardedFor(req: Request): Record<string, string> {
  const ip = clientIp(req)
  if (!ip) console.error('No trusted client IP in X-Forwarded-For; not forwarding one')
  return ip ? { 'x-forwarded-for': ip } : {}
}

export const sessionCookie = (token: string, maxAge: number) =>
  `${SESSION_COOKIE}=${token}; Path=/; Max-Age=${maxAge}; HttpOnly; Secure; SameSite=Lax`

export const clearedSessionCookie = () => sessionCookie('', 0)

/** The fan JWT from the request's cookies. */
export function sessionToken(req: Request): string | undefined {
  for (const part of req.headers.get('cookie')?.split(';') ?? []) {
    const i = part.indexOf('=')
    if (i > 0 && part.slice(0, i).trim() === SESSION_COOKIE) return part.slice(i + 1).trim() || undefined
  }
  return undefined
}
