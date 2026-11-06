import { useOrgQuery } from '@/features/auth/organizer'
import { path } from '@/lib/api'

// GET /org/events/{id}/sales (04-api §Organizer). Every `*_mad` is integer centimes.
export type SalesChannel = 'APP' | 'WEB' | 'POS'
export type ZoneSales = { zone_id: number; code: string; name: string; quota: number; paid: number; held: number; revenue_mad: number }
export type ChannelSales = { channel: SalesChannel; paid: number; revenue_mad: number }
/** A `payment.refund_required` audit row: money taken with no ticket issued, refunded by hand in the gateway. */
export type RefundRequired = { payment_id: string; order_id: string; amount_mad: number; reason: string; at: string }
export type EventSales = {
  event_id: number
  quota: number
  paid: number
  held: number
  revenue_mad: number
  zones: ZoneSales[]
  channels: ChannelSales[]
  refunds_required: RefundRequired[]
}

export const useSales = (eventId: string) =>
  useOrgQuery<EventSales>(['events', eventId, 'sales'], path('org', 'events', eventId, 'sales'))
