import { api } from '@/lib/api'
import { badBody, errorResponse, forbidden, fromError, isSameOrigin, readJson, sessionToken, unauthorized } from '@/lib/http'
import { isPayMethod } from '@/lib/order-client'
import { isCardRedirect, SAFE_ID } from '@/lib/orders'

/**
 * `POST /fan/orders/{id}/pay {method}` with `method` ∈ CARD, WALLET, CASH_AGENCY (anything else → 422, nothing sent).
 * CARD and WALLET answer `{redirect_url}` for the browser to follow to the hosted page, only if it is the backend's
 * card redirect (lib/orders isCardRedirect, else 502). CASH_AGENCY answers `{method}` only: the code is shown by the
 * order page from `GET /fan/orders/{id}`. 409 ORDER_EXPIRED / ORDER_NOT_PAYABLE / METHOD_NOT_AVAILABLE pass through.
 */
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const { id } = await params
  if (!SAFE_ID.test(id)) return errorResponse(404, 'NOT_FOUND', 'Unknown order')
  const method = (await readJson(req))?.method
  if (!isPayMethod(method)) return badBody()
  const headers = { 'cache-control': 'no-store' }
  try {
    const r = await api<{ redirect_url?: unknown }>(`/fan/orders/${id}/pay`, { method: 'POST', json: { method }, token })
    if (method === 'CASH_AGENCY') return Response.json({ method }, { headers })
    if (!isCardRedirect(r?.redirect_url)) {
      console.error('payment redirect refused', r?.redirect_url)
      return errorResponse(502, 'INTERNAL_ERROR', 'Unexpected payment redirect from backend')
    }
    return Response.json({ redirect_url: new URL(r.redirect_url).href }, { headers })
  } catch (e) {
    return fromError(e)
  }
}
