import { api } from '@/lib/api'
import { badBody, forbidden, fromError, isSameOrigin, readJson, sessionToken, unauthorized } from '@/lib/http'
import { getPendingCash, orderPayload, toOrderView } from '@/lib/orders'
import { isJwt } from '@/lib/queue'

/**
 * `POST /fan/orders` (channel WEB) with the session token as Bearer. Answers `{id, status, reserved_until,
 * total_mad}` (201), or the backend error as is (409 EVENT_NOT_ON_SALE / SOLD_OUT / PER_ACCOUNT_LIMIT, 422 with field
 * paths, 429 SALE_BUSY with `retry_after_s`). Lot 5: the browser's `X-Admission-Token` (W8) is forwarded as is when
 * it has a JWT's shape; without one, an event with a live room answers 409 ADMISSION_REQUIRED.
 */
export async function POST(req: Request) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const body = await readJson(req)
  const payload = body && orderPayload(body)
  if (!payload) return badBody()
  const admission = req.headers.get('x-admission-token')
  const headers = isJwt(admission) ? { 'x-admission-token': admission } : undefined
  try {
    const o = toOrderView(await api('/fan/orders', { method: 'POST', json: payload, token, headers }))
    return Response.json(
      { id: o.id, status: o.status, reserved_until: o.reserved_until, total_mad: o.total_mad },
      { status: 201, headers: { 'cache-control': 'no-store' } },
    )
  } catch (e) {
    return fromError(e)
  }
}

/**
 * W5 "Commandes en attente de paiement": `GET /fan/orders` with the session token, reduced to the AWAITING_CASH orders
 * still payable, each with its code (lib/orders getPendingCash). Buyer only, never cached.
 */
export async function GET(req: Request) {
  const token = sessionToken(req)
  if (!token) return unauthorized()
  try {
    return Response.json({ orders: await getPendingCash(token) }, { headers: { 'cache-control': 'no-store' } })
  } catch (e) {
    return fromError(e)
  }
}
