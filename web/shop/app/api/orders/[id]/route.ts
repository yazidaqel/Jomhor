import { errorResponse, fromError, sessionToken, unauthorized } from '@/lib/http'
import { getOrder, SAFE_ID } from '@/lib/orders'

/** Order status for the `/orders/[id]` poll: `GET /fan/orders/{id}` (buyer only, 404 otherwise), never cached. */
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const { id } = await params
  if (!SAFE_ID.test(id)) return errorResponse(404, 'NOT_FOUND', 'Unknown order')
  try {
    const { status, reserved_until, ticket_count, payment_status, payment_method } = await getOrder(id, token)
    // payment_status / payment_method only when the backend sends them: the page tells "absent" from null. The cash
    // code is not polled: the page renders it on the server and refreshes when an order turns AWAITING_CASH.
    const body = {
      status,
      reserved_until,
      ticket_count,
      ...(payment_status !== undefined && { payment_status }),
      ...(payment_method !== undefined && { payment_method }),
    }
    return Response.json(body, { headers: { 'cache-control': 'no-store' } })
  } catch (e) {
    return fromError(e)
  }
}
