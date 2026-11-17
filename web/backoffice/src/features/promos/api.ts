import type { TFunction } from 'i18next'
import { z } from 'zod'
import { useOrgMutation, useOrgQuery } from '@/features/auth/organizer'
import { sellable, type Price } from '@/features/events/api'
import { path } from '@/lib/api'
import { integerInput } from '@/lib/forms'
import { dhInput, formatDh, parseDh } from '@/lib/money'

// docs/04-api.md §Promo codes; §Organizer GET, POST /org/events/{id}/promo-codes, PATCH …/promo-codes/{promo_id}.
// Every `*_mad` is integer centimes.
export const PROMO_KINDS = ['PERCENT', 'AMOUNT'] as const
export type PromoKind = (typeof PROMO_KINDS)[number]
export type PromoCode = {
  id: string
  code: string
  kind: PromoKind
  /** PERCENT: 1–99; null for AMOUNT. */
  percent: number | null
  /** AMOUNT: centimes off, once per order; null for PERCENT. */
  amount_mad: number | null
  /** null = every ALL/MEMBERS category of the event. */
  price_category: { id: string; name: string; zone: { code: string; name: string } } | null
  /** null = no cap. */
  max_uses: number | null
  per_account_max: number
  active: boolean
  created_at: string
  /** held: RESERVED/AWAITING_CASH orders still held; paid: PAID orders. */
  uses: { held: number; paid: number }
  /** Σ discount of its PAID orders. */
  discount_mad: number
}

/** Under the event's key: the list is this organizer's and this event's only. Sorted by `code` by the server. */
export const usePromoCodes = (eventId: string) =>
  useOrgQuery<PromoCode[]>(['events', eventId, 'promo-codes'], path('org', 'events', eventId, 'promo-codes'))

export type PromoRequest = { method: 'POST' | 'PATCH'; promoId?: string; body: Record<string, unknown> }
/**
 * Create (POST) or change (PATCH) a code; both answer a list row. Refetches this event's queries (its codes, and its
 * status, which an EVENT_NOT_EDITABLE says is stale).
 */
export const usePromoMutation = (eventId: string) =>
  useOrgMutation(['events', eventId], ({ method, promoId, body }: PromoRequest, send) =>
    send<PromoCode>({ method, path: path('org', 'events', eventId, 'promo-codes', ...(promoId ? [promoId] : [])), body }),
  )

/** "−20 %" or "−50,00 DH". */
export const discountLabel = (c: Pick<PromoCode, 'kind' | 'percent' | 'amount_mad'>, t: TFunction, locale: string) =>
  c.kind === 'PERCENT' ? t('promos.percentOff', { percent: c.percent ?? 0 }) : t('promos.amountOff', { amount: formatDh(c.amount_mad ?? 0, locale) })

/** "Tout l'événement" or "Plein tarif · Tribune Est". */
export const scopeLabel = (c: Pick<PromoCode, 'price_category'>, t: TFunction) =>
  c.price_category ? t('promos.scopeCategory', { name: c.price_category.name, zone: c.price_category.zone.name }) : t('promos.wholeEvent')

/** The scope select's options: the event's ALL/MEMBERS categories (04: never PASS or INVITE). */
export const scopeCategories = (prices: Price[]) => prices.filter(sellable)

/** The scope select's value for "Tout l'événement" (a Radix select item can't be ''). */
export const WHOLE_EVENT = 'event'
const CODE = /^[A-Z0-9]{4,20}$/
const MAX_INT = 2 ** 31 - 1

/** Form values as typed; fields carry the API names so a VALIDATION_FAILED maps 1:1. */
export type PromoForm = {
  code: string
  kind: PromoKind
  percent: string
  amount_mad: string
  price_category_id: string
  max_uses: string
  per_account_max: string
}

export const toForm = (p?: PromoCode): PromoForm => ({
  code: p?.code ?? '',
  kind: p?.kind ?? 'PERCENT',
  percent: p?.percent == null ? '' : String(p.percent),
  amount_mad: p?.amount_mad == null ? '' : dhInput(p.amount_mad),
  price_category_id: p?.price_category?.id ?? WHOLE_EVENT,
  max_uses: p?.max_uses == null ? '' : String(p.max_uses),
  per_account_max: String(p?.per_account_max ?? 1),
})

/**
 * zod for the "Nouveau code" sheet (`existing` undefined) or the edit sheet. Create checks every field; edit checks the
 * two caps only (the rest is fixed after create). `max_uses` is optional, but once set it can't be cleared (04 PATCH).
 */
export function promoSchema(t: TFunction, existing?: PromoCode) {
  const fixed = z.string()
  const maxUses = z
    .string()
    .trim()
    .refine((s) => s === '' || (/^\d+$/.test(s) && Number(s) >= 1 && Number(s) <= MAX_INT), t('promos.form.maxUsesInvalid'))
    .refine((s) => s !== '' || existing?.max_uses == null, t('promos.form.maxUsesKept'))
    .transform((s) => (s === '' ? null : Number(s)))
  return z
    .object({
      code: existing ? fixed : z.string().trim().toUpperCase().regex(CODE, t('promos.form.codeInvalid')),
      kind: z.enum(PROMO_KINDS),
      percent: fixed,
      amount_mad: fixed,
      price_category_id: fixed,
      max_uses: maxUses,
      per_account_max: integerInput(t('promos.form.perAccountInvalid'), 1, 100),
    })
    .superRefine((v, ctx) => {
      if (existing) return
      // A whole number 1–99: two digits at most, not zero.
      if (v.kind === 'PERCENT' && !(/^\d{1,2}$/.test(v.percent.trim()) && Number(v.percent.trim()) >= 1))
        ctx.addIssue({ code: 'custom', path: ['percent'], message: t('promos.form.percentInvalid') })
      // DH text → integer centimes (string parse, no float); > 0 (04).
      if (v.kind === 'AMOUNT' && !((parseDh(v.amount_mad) ?? 0) > 0)) ctx.addIssue({ code: 'custom', path: ['amount_mad'], message: t('promos.form.amountInvalid') })
    })
}
export type PromoValues = z.output<ReturnType<typeof promoSchema>>

/** POST body: the kind's value only (04: the other one → 422), the scope only when a category is picked. */
export function createBody(v: PromoValues) {
  return {
    code: v.code,
    kind: v.kind,
    ...(v.kind === 'PERCENT' ? { percent: Number(v.percent.trim()) } : { amount_mad: parseDh(v.amount_mad)! }),
    ...(v.price_category_id === WHOLE_EVENT ? {} : { price_category_id: v.price_category_id }),
    ...(v.max_uses === null ? {} : { max_uses: v.max_uses }),
    per_account_max: v.per_account_max,
  }
}

/** PATCH body: the caps that differ from the stored code, nothing else (the fixed fields are never sent). */
export function patchBody(p: PromoCode, v: PromoValues) {
  return {
    ...(v.max_uses !== null && v.max_uses !== p.max_uses ? { max_uses: v.max_uses } : {}),
    ...(v.per_account_max !== p.per_account_max ? { per_account_max: v.per_account_max } : {}),
  }
}
