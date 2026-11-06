import { api } from '@/lib/api'
import { clearedSessionCookie, forbidden, isSameOrigin, readJson, sessionToken } from '@/lib/http'

/** Revokes the session (`POST /fan/me/logout`, `{all}` passed through) when there is one, and always clears the cookie. */
export async function POST(req: Request) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (token) {
    const all = (await readJson(req))?.all === true
    // Already expired or revoked (401), or backend down: the fan is still signed out of this browser.
    await api('/fan/me/logout', { method: 'POST', json: { all }, token }).catch((e: unknown) => console.error('logout', e))
  }
  return new Response(null, { status: 204, headers: { 'set-cookie': clearedSessionCookie() } })
}
