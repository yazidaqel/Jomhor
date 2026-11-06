import { fromError, sessionToken, unauthorized } from '@/lib/http'
import { getTickets } from '@/lib/tickets'

/** W5 list: `GET /fan/tickets` without `with_keys`, mapped to TicketView (no key field). Holder only, never cached. */
export async function GET(req: Request) {
  const token = sessionToken(req)
  if (!token) return unauthorized()
  try {
    return Response.json({ tickets: await getTickets(token) }, { headers: { 'cache-control': 'no-store' } })
  } catch (e) {
    return fromError(e)
  }
}
