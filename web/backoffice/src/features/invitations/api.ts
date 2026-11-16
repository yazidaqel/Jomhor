import type { TFunction } from 'i18next'
import { z } from 'zod'
import { useOrgMutation, useOrgQuery } from '@/features/auth/organizer'
import { normalizePhone } from '@/features/auth/phone'
import type { Price } from '@/features/events/api'
import { checkHolders, saleItems, type Holder, type PrintData, type SaleItem, type SaleOrder } from '@/features/pos/api'
import type { TicketStatus } from '@/features/tickets/api'
import { path } from '@/lib/api'
import { integerInput } from '@/lib/forms'

// docs/04-api.md §Invitations; §Organizer POST/GET /org/events/{id}/invitations, GET /org/invitations/{id}/tickets.
export type Delivery = 'WHATSAPP' | 'PAPER'
export type InvitationTicket = {
  id: string
  ticket_no: string
  status: TicketStatus
  holder_name: string
  holder_label: string | null
  /** WHATSAPP: the ticket has a holder account (app, W5, WhatsApp); PAPER: printed from B7. No phone, ever. */
  delivery: Delivery
}
export type Invitation = {
  id: string
  created_at: string
  invited_by: { id: string; name: string }
  places: number
  items: { price_category_id: string; name: string; zone: { id: number; code: string; name: string }; qty: number }[]
  tickets: InvitationTicket[]
}
export type InvitationBody = { invitation_id: string; items: SaleItem[] }

export const invitationsPath = (eventId: string | number) => `/events/${encodeURIComponent(eventId)}/invitations`
export const printPath = (eventId: string | number, orderId: string) => `${invitationsPath(eventId)}/${encodeURIComponent(orderId)}/print`

/** 04: at most 500 places per request; the real cap is the INVITE quotas. */
export const MAX_PLACES = 500

/** Places issued from Accréditations (04 §Invitations): the event's INVITE categories, the only ones B7 offers. */
export const inviteCategories = (prices: Price[]) => prices.filter((p) => p.audience === 'INVITE')
export const remaining = (p: Price) => Math.max(0, p.quota - p.sold)

/** Under the event's key: a ticket refund (Révoquer) and an issue refetch it with the prices' `sold`. Newest first, ≤ 200. */
export const useInvitations = (eventId: string) =>
  useOrgQuery<{ invitations: Invitation[] }>(['events', eventId, 'invitations'], path('org', 'events', eventId, 'invitations'))

/** 201 created, or 200 with the same `invitation_id` again: both are the §Point of sale sale answer. */
export const useIssueInvitation = (eventId: string) =>
  useOrgMutation(['events', eventId], (body: InvitationBody, send) => send<SaleOrder>({ method: 'POST', path: path('org', 'events', eventId, 'invitations'), body }))

/**
 * Print data, the till's shape. As there, each GET is an audited print (`invitation.print`) carrying QR tokens
 * (`no-store`): one fetch per visit, nothing kept, no refetch on focus or reconnect, no automatic retry.
 */
export const useInvitationPrint = (orderId: string) =>
  useOrgQuery<PrintData>(['invitations', 'print', orderId], path('org', 'invitations', orderId, 'tickets'), {
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  })

/** The "Nouvelle invitation" sheet: one INVITE category, 1 to min(500, remaining) places, holder rows as B11. */
export function invitationSchema(t: TFunction, prices: Price[]) {
  return z
    .object({
      price_category_id: z.string().min(1, t('invitations.sheet.categoryRequired')),
      qty: integerInput(t('invitations.sheet.qtyInvalid', { max: MAX_PLACES }), 1, MAX_PLACES),
      same_name: z.boolean(),
      holders: z.array(z.object({ name: z.string(), phone: z.string() })).min(1),
    })
    .superRefine((v, ctx) => {
      const price = prices.find((p) => p.id === v.price_category_id)
      if (price && v.qty > remaining(price))
        ctx.addIssue({ code: 'custom', path: ['qty'], message: t('invitations.sheet.overQuota', { count: remaining(price), category: price.name }) })
      checkHolders(v.holders, v.same_name, ctx, t)
    })
}
export type InvitationValues = z.input<ReturnType<typeof invitationSchema>>
export type InvitationOutput = z.output<ReturnType<typeof invitationSchema>>

/** The request's items: one category, one holder per place (with "même nom", the first row for every place). */
export const invitationItems = (price: Price, qty: number, holders: Holder[], sameName: boolean) => saleItems([price], { [price.id]: qty }, holders, sameName)

/** "{n} billets · {m} sur WhatsApp · {k} à imprimer": a place goes on WhatsApp when its row has a valid phone. */
export function deliverySplit(qty: number, holders: Holder[], sameName: boolean) {
  const hasPhone = (h?: Holder) => !!h && normalizePhone(h.phone) !== null
  const whatsapp = sameName ? (hasPhone(holders[0]) ? qty : 0) : holders.slice(0, qty).filter(hasPhone).length
  return { whatsapp, paper: qty - whatsapp }
}
