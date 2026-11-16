'use client'

import { useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { planText, shouldSaveName } from '@/lib/checkout'
import { normalizeHolderName } from '@/lib/holder-name'
import type { Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { errorCode, errorText, placeOrder, postJson, type PayMethod } from '@/lib/order-client'
import type { Plan } from '@/lib/products'
import { CheckIcon } from '../../../icons'
import { ctaText, METHOD_ICONS, MethodRows } from '../MethodRows'

type Copy = Pick<Dict, 'checkout' | 'passes' | 'errors'>

const input =
  'mt-1.5 block h-cta-height w-full rounded-control border border-border bg-ground px-4 text-body text-text placeholder:text-text-muted aria-invalid:border-danger'

/**
 * W3 product variant (DESIGN Lot 5 step 3): the holder name (prefilled from /fan/me, §Holder names, an edited name is
 * saved back), 3b the plan (in full, or in N installments: the order is then for installment 1), the product's payment
 * methods and the CTA. Creates the order through `/api/product-orders`, pays it with
 * the method, then follows the hosted page (card, wallet) or opens W4 (cash code, or a pay refusal: W4 shows the
 * method rows again for a RESERVED order). 409 PASS_HELD links to Mes billets ("Mes abonnements").
 */
export function ProductForm(props: {
  productId: string
  kind: 'PASS' | 'MEMBERSHIP'
  displayName: string | null
  /** The product's plans, in full first (lib/products toPlans: never empty). */
  plans: Plan[]
  methods: PayMethod[]
  cashLate: boolean
  cashDeadline: string
  copy: Copy
}) {
  const { copy } = props
  const c = copy.checkout
  const p = copy.passes
  const router = useRouter()
  const [name, setName] = useState(props.displayName ?? '')
  const [nameError, setNameError] = useState(false)
  const [error, setError] = useState<{ text: string; held: boolean } | null>(null)
  const [pending, setPending] = useState(false)
  const [method, setMethod] = useState<PayMethod | undefined>(props.methods[0])
  const [plan, setPlan] = useState(props.plans[0]!)
  const abort = useRef<AbortController | null>(null)
  const nameInput = useRef<HTMLInputElement>(null)
  useEffect(() => () => abort.current?.abort(), [])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!method) return
    const holder = normalizeHolderName(name)
    if (!holder) {
      setNameError(true)
      return nameInput.current?.focus()
    }
    setNameError(false)
    setError(null)
    setPending(true)
    const ctl = (abort.current = new AbortController())
    try {
      if (shouldSaveName(holder, props.displayName)) await postJson('/api/me', { display_name: holder }, 'PATCH', ctl.signal) // best effort
      const order = { product_id: props.productId, holder_name: holder, installments: plan.installments }
      const placed = await placeOrder(order, method, () => {}, ctl.signal, { create: '/api/product-orders' })
      if (placed.kind === 'redirect') return window.location.assign(placed.url)
      if (placed.kind !== 'failed') return router.push(`/orders/${encodeURIComponent(placed.orderId)}`)
      setPending(false)
      const code = errorCode(placed.result)
      if (code === 'UNAUTHORIZED') return router.push(`/login?next=${encodeURIComponent(window.location.pathname + window.location.search)}`)
      const fields = (placed.result.data?.details as { fields?: object } | undefined)?.fields
      if (code === 'VALIDATION_FAILED' && fields && 'holder_name' in fields) {
        setNameError(true)
        return nameInput.current?.focus()
      }
      setError({ text: errorText(copy.errors, placed.result), held: code === 'PASS_HELD' })
    } catch {
      setPending(false) // aborted: the fan left the page
    }
  }

  const CtaIcon = METHOD_ICONS[method ?? 'CARD']
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1 rounded-card border border-border bg-surface p-5">
        <label htmlFor="holder-name" className="text-label uppercase text-text-secondary">
          {p.holder}
        </label>
        <p id="holder-hint" className="text-body-small text-text-muted">
          {props.kind === 'PASS' ? p.holderHintPASS : p.holderHintMEMBERSHIP}
        </p>
        <input
          ref={nameInput}
          id="holder-name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          dir="auto"
          autoComplete="name"
          placeholder={c.namePlaceholder}
          aria-invalid={nameError}
          aria-describedby={nameError ? 'holder-hint holder-error' : 'holder-hint'}
          disabled={pending}
          className={input}
        />
        {nameError && (
          <p id="holder-error" className="mt-1.5 text-body-small text-danger">
            {c.nameInvalid}
          </p>
        )}
      </div>
      {props.plans.length > 1 && (
        <fieldset className="flex flex-col gap-2.5 rounded-card border border-border bg-surface p-5">
          <legend className="float-start mb-1 font-display text-h2 uppercase">{p.planTitle}</legend>
          {props.plans.map((option) => {
            const on = option.installments === plan.installments
            return (
              <label
                key={option.installments}
                className={`clear-start flex min-h-touch-min cursor-pointer items-center gap-3 rounded-control border-2 bg-ground px-4 py-3 transition-colors duration-200 has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent ${
                  on ? 'border-accent' : 'border-border hover:border-border-muted'
                }`}
              >
                <input type="radio" name="plan" value={option.installments} checked={on} disabled={pending} onChange={() => setPlan(option)} className="sr-only" />
                <span className="min-w-0 flex-1 font-semibold">{planText(p, option)}</span>
                <span aria-hidden="true" className={`flex size-6 shrink-0 items-center justify-center rounded-pill border-2 ${on ? 'border-accent bg-accent text-on-accent' : 'border-border'}`}>
                  {on && <CheckIcon className="size-3.5 motion-safe:animate-mark" />}
                </span>
              </label>
            )
          })}
        </fieldset>
      )}
      <MethodRows
        methods={props.methods}
        cashLate={props.cashLate}
        cashDeadline={props.cashDeadline}
        refused={[]}
        method={method}
        onPick={setMethod}
        disabled={pending}
        copy={c}
      />
      {error && (
        <p role="alert" className="text-body-small text-danger">
          {error.text}{' '}
          {error.held && (
            <Link href="/me/tickets" className="font-semibold text-accent hover:underline">
              {p.mine}
            </Link>
          )}
        </p>
      )}
      <button
        type="submit"
        disabled={pending || !method}
        aria-busy={pending}
        className="flex h-cta-height items-center justify-center gap-2.5 rounded-control bg-accent text-cta text-on-accent transition-colors duration-200 hover:bg-accent-hover disabled:opacity-60"
      >
        {pending ? (
          <span aria-hidden="true" className="size-5 rounded-pill border-2 border-on-accent/30 border-t-on-accent motion-safe:animate-spin" />
        ) : (
          <CtaIcon className="size-5" />
        )}
        {pending ? (method === 'CASH_AGENCY' ? c.creatingCode : c.paying) : ctaText(c, method, formatDh(plan.amounts_mad[0]!))}
      </button>
      <p className="text-center text-xs text-text-muted">{props.kind === 'PASS' ? p.captionPASS : p.captionMEMBERSHIP}</p>
    </form>
  )
}
