import { useOrgMutation, useOrgQuery } from '@/features/auth/organizer'
import { path } from '@/lib/api'

// GET /org/events/{id}/sales (04-api §Organizer). Every `*_mad` is integer centimes.
export type SalesChannel = 'APP' | 'WEB' | 'POS'
export type PaymentMethod = 'CARD' | 'WALLET' | 'CASH_AGENCY' | 'POS_CASH' | 'FREE'
/** `held` = RESERVED only; `awaiting_cash` = AWAITING_CASH (a cash code not yet paid); `refunded` = REFUNDED tickets. */
export type ZoneSales = { zone_id: number; code: string; name: string; quota: number; paid: number; held: number; awaiting_cash: number; refunded: number; revenue_mad: number }
export type ChannelSales = { channel: SalesChannel; paid: number; revenue_mad: number }
/** By the PAID orders' `payment_method`, every method listed (zeros included). */
export type MethodSales = { method: PaymentMethod; paid: number; revenue_mad: number }
/** A `payment.refund_required` audit row: money taken with no ticket issued, refunded by hand in the gateway. */
export type RefundRequired = { payment_id: string; order_id: string; amount_mad: number; reason: string; at: string }
export type EventSales = {
  event_id: number
  quota: number
  paid: number
  held: number
  awaiting_cash: number
  /** `paid` and `revenue_mad` still count refunded tickets (gross); net = `revenue_mad − refunded_mad`. */
  refunded: number
  /** Σ refunds of the event, any status. */
  refunded_mad: number
  revenue_mad: number
  zones: ZoneSales[]
  channels: ChannelSales[]
  methods: MethodSales[]
  refunds_required: RefundRequired[]
}

export const useSales = (eventId: string, options?: { refetchInterval?: number }) =>
  useOrgQuery<EventSales>(['events', eventId, 'sales'], path('org', 'events', eventId, 'sales'), options)

// GET /org/refunds, POST /org/refunds/{id}/done (Lot 3, Q52): money owed back for a refunded ticket, paid back by hand.
export type Refund = {
  id: string
  event_id: number
  ticket_id: string
  ticket_no: string
  order_id: string
  amount_mad: number
  /** The order's payment method; null when there was no payment. */
  method: PaymentMethod | null
  /** Why the ticket was refunded, as typed by the ADMIN. */
  reason: string
  status: 'REQUIRED' | 'DONE'
  requested_by: { id: string; name: string }
  requested_at: string
  done_by: { id: string; name: string } | null
  done_at: string | null
  reference: string | null
}

/** The event's refunds still to pay back. Under the event's key: a ticket refund (B12) refetches it. */
export const useRefunds = (eventId: string) =>
  useOrgQuery<Refund[]>(['events', eventId, 'refunds'], `/org/refunds?status=REQUIRED&event_id=${encodeURIComponent(eventId)}`)

/** "Marquer remboursé": ADMIN, FINANCE (04-api role sentence). */
export const useRefundDone = (eventId: string) =>
  useOrgMutation(['events', eventId, 'refunds'], ({ id, reference }: { id: string; reference: string }, send) =>
    send<Refund>({ method: 'POST', path: `${path('org', 'refunds', id)}/done`, body: { reference } }),
  )
