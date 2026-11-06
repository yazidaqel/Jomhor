import { api } from '@/lib/api'
import { badBody, errorResponse, forbidden, forwardedFor, fromError, isSameOrigin, readJson, sessionCookie } from '@/lib/http'

type User = { id: string; phone: string; display_name: string | null; locale: string }
type Verified = { token: string; expires_at: string; user: User }

/**
 * `POST /fan/auth/verify`, then the JWT goes into the httpOnly session cookie (Max-Age from `expires_at`).
 * The body carries the user only: the token never reaches client JS.
 */
export async function POST(req: Request) {
  if (!isSameOrigin(req)) return forbidden()
  const body = await readJson(req)
  if (!body) return badBody()
  try {
    const { token, expires_at, user } = await api<Verified>('/fan/auth/verify', {
      method: 'POST',
      json: { phone: body.phone, code: body.code },
      headers: forwardedFor(req),
    })
    const maxAge = Math.floor((Date.parse(expires_at) - Date.now()) / 1000)
    // A JWT is base64url segments and dots; anything else must not reach a Set-Cookie header.
    if (!/^[\w-]+\.[\w-]+\.[\w-]+$/.test(token) || !(maxAge > 0)) {
      return errorResponse(502, 'INTERNAL_ERROR', 'Unexpected session from backend')
    }
    const { id, phone, display_name, locale } = user
    return Response.json(
      { user: { id, phone, display_name, locale } },
      { headers: { 'set-cookie': sessionCookie(token, maxAge), 'cache-control': 'no-store' } },
    )
  } catch (e) {
    return fromError(e)
  }
}
