import { api } from '@/lib/api'
import { errorResponse, forbidden, fromError, isSameOrigin, sessionToken, unauthorized } from '@/lib/http'
import { toEntry } from '@/lib/waitlist'

// W2 sold-out waitlist (04 §Waitlist): `/fan/waitlist/{event_id}` with the session as Bearer, never cached. Answers
// `{entry: {joined_at, notified_at} | null}` rebuilt field by field, with the backend's status (PUT: 201 new or moved to
// the back, 200 already waiting; DELETE: 204). Backend errors pass through in the 04 shape (404, 401, 409
// WAITLIST_CLOSED NOT_SOLD_OUT / ROOM / CLOSED).

type Ctx = { params: Promise<{ eventId: string }> }

/** A positive int4 event id written plainly (no sign, zero padding or slug), else null. */
const eventIdOf = (raw: string) => (/^[1-9]\d{0,9}$/.test(raw) && Number(raw) <= 2_147_483_647 ? raw : null)

async function call(req: Request, ctx: Ctx, method: 'GET' | 'PUT' | 'DELETE') {
  // Not on GET: browsers omit Origin on a same-origin GET. A read changes nothing, and no CORS header lets another site read it.
  if (method !== 'GET' && !isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const id = eventIdOf((await ctx.params).eventId)
  let res: Response
  if (!id) res = errorResponse(404, 'NOT_FOUND', 'Unknown event')
  else {
    try {
      let status = 200
      const body = await api(`/fan/waitlist/${id}`, { method, token, onStatus: (s) => (status = s) })
      const entry = toEntry(body)
      if (method === 'DELETE') res = new Response(null, { status: 204 })
      else if (method === 'PUT' && !entry) res = errorResponse(502, 'INTERNAL_ERROR', 'Unexpected waitlist answer')
      else res = Response.json({ entry }, { status })
    } catch (e) {
      res = fromError(e)
    }
  }
  res.headers.set('cache-control', 'no-store')
  return res
}

export const GET = (req: Request, ctx: Ctx) => call(req, ctx, 'GET')
export const PUT = (req: Request, ctx: Ctx) => call(req, ctx, 'PUT')
export const DELETE = (req: Request, ctx: Ctx) => call(req, ctx, 'DELETE')
