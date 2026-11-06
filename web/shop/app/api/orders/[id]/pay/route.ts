import { api } from '@/lib/api'
import { errorResponse, forbidden, fromError, isSameOrigin, sessionToken, unauthorized } from '@/lib/http'
import { isCardRedirect, SAFE_ID } from '@/lib/orders'

/**
 * `POST /fan/orders/{id}/pay {method: CARD}` (card only in Lot 1). Answers `{redirect_url}` for the browser to follow
 * to the gateway, only if it is the backend's card redirect (lib/orders isCardRedirect, else 502); 409 ORDER_EXPIRED /
 * ORDER_NOT_PAYABLE pass through.
 */
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const { id } = await params
  if (!SAFE_ID.test(id)) return errorResponse(404, 'NOT_FOUND', 'Unknown order')
  try {
    const r = await api<{ redirect_url?: unknown }>(`/fan/orders/${id}/pay`, { method: 'POST', json: { method: 'CARD' }, token })
    if (!isCardRedirect(r?.redirect_url)) {
      console.error('payment redirect refused', r?.redirect_url)
      return errorResponse(502, 'INTERNAL_ERROR', 'Unexpected payment redirect from backend')
    }
    return Response.json({ redirect_url: new URL(r.redirect_url).href }, { headers: { 'cache-control': 'no-store' } })
  } catch (e) {
    return fromError(e)
  }
}
