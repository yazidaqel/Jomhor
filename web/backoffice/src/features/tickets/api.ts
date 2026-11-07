import { useQuery } from '@tanstack/react-query'
import { useOrganizer, useOrgMutation, type Role } from '@/features/auth/organizer'
import { normalizePhone } from '@/features/auth/phone'
import type { PaymentMethod, SalesChannel } from '@/features/sales/api'
import { api, path } from '@/lib/api'

// docs/04-api.md §Organizer: POST /org/events/{id}/tickets/search, POST /org/tickets/{id}/block|unblock|refund (Lot 3, Q52).
export type TicketStatus = 'VALID' | 'BLOCKED' | 'REFUNDED' | 'CANCELLED'
export type TicketRow = {
  id: string
  ticket_no: string
  status: TicketStatus
  zone: { id: number; code: string; name: string }
  holder_name: string | null
  holder_label: string | null
  /** What the holder paid for this place (centimes): the amount a refund owes back. */
  unit_price_mad: number
  order_id: string
  channel: SalesChannel
  payment_method: PaymentMethod | null
  issued_at: string
  /** Set once the ticket was refunded: the money owed back (centimes) and whether it went back yet. */
  refund: { id: string; status: 'REQUIRED' | 'DONE'; amount_mad: number } | null
  /** Lot 4: `ticket_no` of the ticket a transfer or re-issue made from this one (this one is then CANCELLED). */
  transferred_to: string | null
  /** Lot 4: `ticket_no` of the ticket this one replaced. */
  transferred_from: string | null
}
export type TicketAction = 'block' | 'unblock' | 'refund'

/** Who may do what (04-api role sentence): block/unblock ADMIN, OPS; refund ADMIN. FINANCE reads only. */
export const ACTION_ROLES: Record<TicketAction, readonly Role[]> = { block: ['ADMIN', 'OPS'], unblock: ['ADMIN', 'OPS'], refund: ['ADMIN'] }
/** The actions a ticket in `status` allows (else 409 TICKET_STATUS). */
export const actionsFor = (status: TicketStatus): TicketAction[] =>
  status === 'VALID' ? ['block', 'refund'] : status === 'BLOCKED' ? ['unblock', 'refund'] : []

/**
 * Search text → `q`: a Moroccan phone in any usual form goes as E.164 (the API matches it exactly), and so does any
 * `+` number once its separators are gone (a foreign holder); anything else (ticket number, cash code) as typed,
 * which the server normalises. No ticket number or code reads as a phone.
 */
export function lookupQuery(input: string) {
  const text = input.trim()
  return normalizePhone(text) ?? (/^\+[\d\s.()-]+$/.test(text) ? text.replace(/[\s.()-]/g, '') : text)
}

/**
 * The lookup is a POST (`{q}` in the body: a phone never goes in a URL) but a read: a query, keyed under the event
 * (`['org', organizerId, 'events', id, 'tickets', q]`) so a block or refund refetches it. `q` null: no call yet.
 * Not retried: a 422 on `q` is the answer, not a glitch.
 */
export function useTicketLookup(eventId: string, q: string | null) {
  const { organizer_id: organizerId } = useOrganizer()
  return useQuery({
    queryKey: ['org', organizerId, 'events', eventId, 'tickets', q],
    queryFn: ({ signal }) =>
      api<TicketRow[]>(`${path('org', 'events', eventId, 'tickets')}/search`, { method: 'POST', organizerId, signal, body: JSON.stringify({ q }) }),
    enabled: q !== null,
    retry: false,
  })
}

/** A refund also moves the event's sales, remaining places and refunds to-do: the whole event scope is refetched. */
export const useTicketAction = (eventId: string) =>
  useOrgMutation(['events', eventId], ({ kind, id, reason }: { kind: TicketAction; id: string; reason: string }, send) =>
    send<TicketRow>({ method: 'POST', path: `${path('org', 'tickets', id)}/${kind}`, body: { reason } }),
  )
