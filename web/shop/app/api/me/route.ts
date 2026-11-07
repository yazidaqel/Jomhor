import { api, ApiError } from '@/lib/api'
import { isLocale } from '@/lib/i18n'
import { badBody, clearedSessionCookie, forbidden, fromError, isSameOrigin, readJson, sessionToken, unauthorized } from '@/lib/http'

/**
 * The header's signed-in state (`GET /fan/me`): `{display_name}` only, never the phone or the token. No cookie → 401
 * without a backend call; a revoked or expired session → 401 and the cookie is cleared. Read by the header island in
 * the browser, so no page reads the session cookie for the header.
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

/**
 * `PATCH /fan/me` with `display_name` (the fan edits ticket 1's name on W3) and/or `locale` (the language switch, so
 * WhatsApp and push copy follow it; best-effort on the client). Only those two fields are forwarded; an unknown locale
 * is dropped. Answers `{display_name}`.
 */
export async function PATCH(req: Request) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const body = await readJson(req)
  if (!body) return badBody()
  try {
    const json = { display_name: body.display_name, ...(isLocale(body.locale) && { locale: body.locale }) }
    const me = await api<{ display_name?: unknown }>('/fan/me', { method: 'PATCH', json, token })
    return Response.json({ display_name: me?.display_name ?? null }, { headers: { 'cache-control': 'no-store' } })
  } catch (e) {
    return fromError(e)
  }
}
