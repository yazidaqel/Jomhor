import { api } from '@/lib/api'
import { normalizeHolderName } from '@/lib/holder-name'
import { errorResponse, forbidden, fromError, isSameOrigin, readJson, sessionToken, unauthorized } from '@/lib/http'
import { normalizePhone } from '@/lib/phone'
import { TICKET_ID } from '@/lib/tickets'

/**
 * W5 "Transférer": `POST /fan/tickets/{id}/transfer {to_phone, name}` with the session token as Bearer. Both fields are
 * normalised first (E.164, 04 §Holder names) and a bad one is refused here (422 on that field, nothing sent). Answers
 * `{to_label, transferred_at}` (201; a 2xx without both → 502) and nothing else: the new ticket, its id and its key stay on the backend. Backend
 * errors pass through (404, 409 TICKET_NOT_VALID / TRANSFER_CLOSED, 422 on `to_phone` with `details.reason` SELF).
 */
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  if (!isSameOrigin(req)) return forbidden()
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const { id } = await params
  if (!TICKET_ID.test(id)) return errorResponse(404, 'NOT_FOUND', 'Unknown ticket')
  const body = await readJson(req)
  const to_phone = typeof body?.to_phone === 'string' ? normalizePhone(body.to_phone) : null
  const name = typeof body?.name === 'string' ? normalizeHolderName(body.name) : null
  if (!to_phone || !name) {
    const fields = { ...(!to_phone && { to_phone: 'invalid' }), ...(!name && { name: 'invalid' }) }
    return Response.json({ code: 'VALIDATION_FAILED', message: 'Invalid body', details: { fields } }, { status: 422 })
  }
  try {
    const r = await api<{ to_label?: unknown; transferred_at?: unknown }>(`/fan/tickets/${id}/transfer`, { method: 'POST', json: { to_phone, name }, token })
    if (typeof r?.to_label !== 'string' || typeof r.transferred_at !== 'string') {
      console.error('ticket transfer: 2xx without to_label or transferred_at')
      return errorResponse(502, 'INTERNAL_ERROR', 'Internal error')
    }
    return Response.json({ to_label: r.to_label, transferred_at: r.transferred_at }, { status: 201, headers: { 'cache-control': 'no-store' } })
  } catch (e) {
    return fromError(e)
  }
}
