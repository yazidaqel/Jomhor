import { api } from '@/lib/api'
import { UUID } from '@/lib/clubs'
import { errorResponse, forbidden, fromError, isSameOrigin, sessionToken, unauthorized } from '@/lib/http'
import { toOrderView } from '@/lib/orders'

/**
 * Lot 5 step 3b W5 "Payer l'échéance": `POST /fan/passes/{id}/installments` (no body) with the session token as Bearer.
 * Answers `{id, status, reserved_until, total_mad}` of the installment order with the backend's status (201 new, 200
 * the live one), or the backend error as is (409 PASS_STATUS, `PAID_UP` included; 404). The browser then opens W4.
 */
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const { id } = await params
  if (!UUID.test(id)) return errorResponse(404, 'NOT_FOUND', 'Unknown pass')
  try {
    let status = 201
    const o = toOrderView(await api(`/fan/passes/${id}/installments`, { method: 'POST', token, onStatus: (s) => (status = s) }))
    return Response.json({ id: o.id, status: o.status, reserved_until: o.reserved_until, total_mad: o.total_mad }, { status, headers: { 'cache-control': 'no-store' } })
  } catch (e) {
    return fromError(e)
  }
}
