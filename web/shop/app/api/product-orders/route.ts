import { api } from '@/lib/api'
import { badBody, forbidden, fromError, isSameOrigin, readJson, sessionToken, unauthorized } from '@/lib/http'
import { toOrderView } from '@/lib/orders'
import { productOrderPayload } from '@/lib/products'

/**
 * Lot 5 step 3 W3 product variant: `POST /fan/product-orders` (channel WEB) with the session token as Bearer. Answers
 * `{id, status, reserved_until, total_mad}` (201), or the backend error as is (409 PRODUCT_NOT_ON_SALE / PASS_HELD /
 * SOLD_OUT / MEMBERSHIP_PAID, 422 on `holder_name`, 404).
 */
export async function POST(req: Request) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const body = await readJson(req)
  const payload = body && productOrderPayload(body)
  if (!payload) return badBody()
  try {
    const o = toOrderView(await api('/fan/product-orders', { method: 'POST', json: payload, token }))
    return Response.json(
      { id: o.id, status: o.status, reserved_until: o.reserved_until, total_mad: o.total_mad },
      { status: 201, headers: { 'cache-control': 'no-store' } },
    )
  } catch (e) {
    return fromError(e)
  }
}
