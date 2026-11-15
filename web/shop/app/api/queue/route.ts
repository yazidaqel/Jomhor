import { randomBytes } from 'node:crypto'
import { api } from '@/lib/api'
import { badBody, errorResponse, forbidden, forwardedFor, fromError, isSameOrigin, readJson, sessionToken, unauthorized } from '@/lib/http'
import { toQueue } from '@/lib/queue'

/**
 * The shop's install id for the per-device limit (plan Q146): random, httpOnly, 1 year. Never logged or shown.
 * `__Host-` (Secure, Path=/, no Domain, like the session cookie): a sibling subdomain can't plant one.
 */
const DEVICE_COOKIE = '__Host-jdid'
const DEVICE_ID = /^[\w-]{16,64}$/ // 04 POST /fan/queue `device_id`

function deviceId(req: Request): { id: string; fresh: boolean } {
  for (const part of req.headers.get('cookie')?.split(';') ?? []) {
    const i = part.indexOf('=')
    if (i > 0 && part.slice(0, i).trim() === DEVICE_COOKIE) {
      const v = part.slice(i + 1).trim()
      if (DEVICE_ID.test(v)) return { id: v, fresh: false }
    }
  }
  return { id: randomBytes(24).toString('base64url'), fresh: true } // 32 chars
}

const deviceCookie = (id: string) =>
  `${DEVICE_COOKIE}=${id}; Path=/; Max-Age=31536000; HttpOnly; Secure; SameSite=Lax`

/**
 * W8 join (plan Q152): `POST /fan/queue` with the session as Bearer, `device_id` from the `__Host-jdid` cookie (set here
 * when absent) and the fan's IP for the per-IP join limit. Answers the queue shape (04 GET /fan/queue), or the
 * backend error as is (409 QUEUE_NOT_OPEN / NOT_ELIGIBLE / QUEUE_DEVICE_LIMIT, 429 QUEUE_RATE_LIMITED). Polls don't
 * come here: the browser calls the API directly with the queue token.
 */
export async function POST(req: Request) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const body = await readJson(req)
  const eventId = body?.event_id
  if (!Number.isSafeInteger(eventId) || (eventId as number) <= 0) return badBody()
  const device = deviceId(req)
  let res: Response
  try {
    const q = toQueue(await api('/fan/queue', { method: 'POST', json: { event_id: eventId, device_id: device.id }, token, headers: forwardedFor(req) }))
    res = q ? Response.json(q) : errorResponse(502, 'INTERNAL_ERROR', 'Unexpected queue answer')
  } catch (e) {
    res = fromError(e)
  }
  res.headers.set('cache-control', 'no-store')
  if (device.fresh) res.headers.append('set-cookie', deviceCookie(device.id))
  return res
}
