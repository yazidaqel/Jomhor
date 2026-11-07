import { api, ApiError } from '@/lib/api'
import { badBody, clearedSessionCookie, forbidden, fromError, isSameOrigin, readJson, sessionToken, unauthorized } from '@/lib/http'

/**
 * The header's signed-in state (`GET /fan/me`): `{display_name}` only, never the phone or the token. No cookie → 401
 * without a backend call; a revoked or expired session → 401 and the cookie is cleared. Read by the header island in
 * the browser, so public pages never read the cookie and stay cacheable.
 */
export async function GET(req: Request) {
  const token = sessionToken(req)
  if (!token) return unauthorized()
  try {
    const me = await api<{ display_name?: unknown }>('/fan/me', { token })
    const name = typeof me?.display_name === 'string' ? me.display_name : null
    return Response.json({ display_name: name }, { headers: { 'cache-control': 'no-store' } })
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      const res = unauthorized()
      res.headers.set('set-cookie', clearedSessionCookie())
      return res
    }
    return fromError(e)
  }
}

/** `PATCH /fan/me {display_name}` when the fan edits ticket 1's name on W3. Answers `{display_name}`. */
export async function PATCH(req: Request) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const body = await readJson(req)
  if (!body) return badBody()
  try {
    const me = await api<{ display_name?: unknown }>('/fan/me', { method: 'PATCH', json: { display_name: body.display_name }, token })
    return Response.json({ display_name: me?.display_name ?? null }, { headers: { 'cache-control': 'no-store' } })
  } catch (e) {
    return fromError(e)
  }
}
