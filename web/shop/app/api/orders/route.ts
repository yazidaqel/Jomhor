import { api } from '@/lib/api'
import { badBody, forbidden, fromError, isSameOrigin, readJson, sessionToken, unauthorized } from '@/lib/http'
import { getPendingCash, orderPayload, toOrderView } from '@/lib/orders'

/**
 * `POST /fan/orders` (channel WEB) with the session token as Bearer. Answers `{id, status, reserved_until,
 * total_mad}` (201), or the backend error as is (409 EVENT_NOT_ON_SALE / SOLD_OUT / PER_ACCOUNT_LIMIT, 422 with field
 * paths, 429 SALE_BUSY with `retry_after_s`).
 */
export async function POST(req: Request) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const body = await readJson(req)
  const payload = body && orderPayload(body)
  if (!payload) return badBody()
  try {
    const o = toOrderView(await api('/fan/orders', { method: 'POST', json: payload, token }))
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
