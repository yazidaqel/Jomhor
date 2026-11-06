import { api } from '@/lib/api'
import { badBody, forbidden, forwardedFor, fromError, isSameOrigin, readJson } from '@/lib/http'

/** Proxy to `POST /fan/auth/otp`: 202 (accepted, not delivered), or the backend error (422, 429). */
export async function POST(req: Request) {
  if (!isSameOrigin(req)) return forbidden()
  const body = await readJson(req)
  if (!body) return badBody()
  try {
    await api('/fan/auth/otp', { method: 'POST', json: { phone: body.phone }, headers: forwardedFor(req) })
    return new Response(null, { status: 202 })
  } catch (e) {
    return fromError(e)
  }
}
