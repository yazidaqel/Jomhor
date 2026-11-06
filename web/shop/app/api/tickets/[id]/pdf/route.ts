import { apiStream } from '@/lib/api'
import { errorResponse, fromError, sessionToken, unauthorized } from '@/lib/http'
import { TICKET_ID, ticketNo } from '@/lib/tickets'

/**
 * W6: streams `GET /fan/tickets/{id}/pdf` (holder only, VALID only) as a download `billet-4D5C-6B7A.pdf` (04),
 * never cached. Backend errors (404 not the holder's, 409 TICKET_NOT_VALID, 401) pass through in the 04 shape.
 */
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const token = sessionToken(req)
  if (!token) return unauthorized()
  const { id } = await params
  if (!TICKET_ID.test(id)) return errorResponse(404, 'NOT_FOUND', 'Unknown ticket')
  try {
    const res = await apiStream(`/fan/tickets/${id}/pdf`, { token, accept: 'application/pdf', signal: req.signal })
    if (!res.headers.get('content-type')?.startsWith('application/pdf')) {
      await res.body?.cancel()
      console.error('ticket pdf: unexpected content-type', res.headers.get('content-type'))
      return errorResponse(502, 'INTERNAL_ERROR', 'Internal error')
    }
    return new Response(res.body, {
      headers: {
        'content-type': 'application/pdf',
        'content-disposition': `attachment; filename="billet-${ticketNo(id).replace(' ', '-')}.pdf"`,
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff',
      },
    })
  } catch (e) {
    return fromError(e)
  }
}
