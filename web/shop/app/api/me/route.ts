import { api } from '@/lib/api'
import { badBody, forbidden, fromError, isSameOrigin, readJson, sessionToken, unauthorized } from '@/lib/http'

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
