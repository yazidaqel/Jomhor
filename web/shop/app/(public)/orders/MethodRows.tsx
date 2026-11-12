import { fmt, type Dict } from '@/lib/i18n'
import { PAY_METHODS, type PayMethod } from '@/lib/order-client'
import { CardIcon, CashIcon, CheckIcon, WalletIcon } from '../../icons'

type Copy = Dict['checkout']

export const METHOD_ICONS = { CARD: CardIcon, WALLET: WalletIcon, CASH_AGENCY: CashIcon }

/**
 * Payment method rows (DESIGN Lot 3 W3), for W3 and the W4 split-pay member: the event's `methods`, cash shown
 * disabled when `cashLate` ("Trop tard pour le cash"), a method in `refused` (METHOD_NOT_AVAILABLE on this order)
 * disabled as "Indisponible". Used inside client forms only (`onPick`).
 */
export function MethodRows({
  methods,
  cashLate,
  cashDeadline,
  refused,
  method,
  onPick,
  disabled,
  copy: c,
}: {
  methods: PayMethod[]
  cashLate: boolean
  /** When a code issued now would expire, already formatted in the venue time zone. */
  cashDeadline: string
  refused: PayMethod[]
  method: PayMethod | undefined
  onPick: (m: PayMethod) => void
  disabled: boolean
  copy: Copy
}) {
  return (
    <fieldset className="flex flex-col gap-2.5 rounded-card border border-border bg-surface p-5">
      <legend className="float-start mb-1 font-display text-h2 uppercase">{c.methodTitle}</legend>
      {PAY_METHODS.filter((m) => methods.includes(m) || (m === 'CASH_AGENCY' && cashLate)).map((m) => {
        const off = !methods.includes(m) || refused.includes(m)
        const Icon = METHOD_ICONS[m]
        const hint = refused.includes(m)
          ? c.unavailable
          : m !== 'CASH_AGENCY'
            ? c.instant
            : methods.includes(m)
              ? fmt(c.cashBefore, { deadline: cashDeadline })
              : c.cashTooLate
        return (
          <label
            key={m}
            className={`clear-start flex min-h-touch-min items-center gap-3 rounded-control border-2 bg-ground px-4 py-3 transition-colors duration-200 has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent ${
              off ? 'cursor-not-allowed border-border text-text-muted' : m === method ? 'cursor-pointer border-accent' : 'cursor-pointer border-border hover:border-border-muted'
            }`}
          >
            <input
              type="radio"
              name="method"
              value={m}
              checked={m === method}
              disabled={off || disabled}
              onChange={() => onPick(m)}
              aria-describedby={`method-${m}-hint`}
              className="sr-only"
            />
            <Icon className={`size-6 ${off ? '' : 'text-accent'}`} />
            <span className="flex min-w-0 flex-1 flex-col gap-x-3 sm:flex-row sm:items-center sm:justify-between">
              <span className="font-semibold">{c[m]}</span>
              <span id={`method-${m}-hint`} className="text-body-small text-text-muted sm:text-end">
                {hint}
              </span>
            </span>
            <span
              aria-hidden="true"
              className={`flex size-6 shrink-0 items-center justify-center rounded-pill border-2 ${m === method ? 'border-accent bg-accent text-on-accent' : 'border-border'}`}
            >
              {m === method && <CheckIcon key={m} className="size-3.5 motion-safe:animate-mark" />}
            </span>
          </label>
        )
      })}
      {method === 'CASH_AGENCY' && <p className="text-body-small text-text-secondary">{c.cashNote}</p>}
    </fieldset>
  )
}

export function ctaText(c: Copy, m: PayMethod | undefined, amount: string) {
  if (m === 'CASH_AGENCY') return c.payCash
  if (m === 'WALLET') return fmt(c.payWallet, { amount })
  return fmt(c.pay, { amount })
}
