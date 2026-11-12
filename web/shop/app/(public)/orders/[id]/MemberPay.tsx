'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { fmt, type Dict } from '@/lib/i18n'
import { formatDh } from '@/lib/money'
import { errorCode, errorText, leftText, pageClock, placeOrder, type PayMethod } from '@/lib/order-client'
import { formatTime } from '@/lib/time'
import { ClockIcon } from '../../../icons'
import { ctaText, METHOD_ICONS, MethodRows } from '../MethodRows'

type Copy = Pick<Dict, 'checkout' | 'orderPage' | 'errors' | 'meta'>

/**
 * W4 split-pay member (DESIGN Lot 4, 9 Groupe member): the hold countdown on server time (`now` at SSR + the monotonic
 * page clock, never the device clock), the W3 method rows and the CTA. Pays the existing order through the shop's
 * `/pay` handler (lib/order-client placeOrder with `existingId`): card and wallet follow the hosted page; a cash code
 * refreshes the server part, which then shows the code (CashDetails). A 401 goes to login and back. At the deadline:
 * "La place a été libérée", then one refresh hands over to OrderStatus.
 */
export function MemberPay(props: {
  id: string
  amountMad: number
  reservedUntil: string
  now: number
  timezone: string
  methods: PayMethod[]
  cashLate: boolean
  cashDeadline: string
  copy: Copy
}) {
  const { copy } = props
  const c = copy.checkout
  const op = copy.orderPage
  const router = useRouter()
  const [refused, setRefused] = useState<PayMethod[]>([])
  const usable = props.methods.filter((m) => !refused.includes(m))
  const [picked, setPicked] = useState<PayMethod | undefined>(props.methods[0])
  const method = picked && usable.includes(picked) ? picked : usable[0]
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [onPageMs, setOnPageMs] = useState(0)
  const abort = useRef<AbortController | null>(null)
  useEffect(() => {
    const elapsed = pageClock()
    const t = setInterval(() => setOnPageMs(elapsed()), 1000)
    return () => {
      clearInterval(t)
      abort.current?.abort()
    }
  }, [])

  const left = Math.floor((Date.parse(props.reservedUntil) - (props.now + onPageMs)) / 1000)
  const expired = !(left > 0)
  // At the deadline, once: the server part re-renders without the member variant (the page shows it only while the hold
  // runs), so OrderStatus takes over with "La place a été libérée" and the way back to the event.
  useEffect(() => {
    if (expired) router.refresh()
  }, [expired, router])
  if (expired) {
    return (
      <div role="status" className="flex flex-col gap-2">
        <p className="font-display text-h1 uppercase">{op.memberExpiredTitle}</p>
        <p className="text-body text-text-secondary">{op.memberExpired}</p>
      </div>
    )
  }

  async function pay(e: React.FormEvent) {
    e.preventDefault()
    if (!method) return
    setPending(true)
    setError(null)
    const ctl = (abort.current = new AbortController())
    try {
      const placed = await placeOrder(null, method, () => {}, ctl.signal, { existingId: props.id })
      if (placed.kind === 'redirect') return window.location.assign(placed.url)
      if (placed.kind === 'cash') return router.refresh()
      if (placed.kind !== 'payFailed') return setPending(false)
      setPending(false)
      const code = errorCode(placed.result)
      if (code === 'UNAUTHORIZED') return router.push(`/login?next=${encodeURIComponent(`/orders/${props.id}`)}`)
      setError(errorText(copy.errors, placed.result))
      if (code === 'METHOD_NOT_AVAILABLE') setRefused((r) => [...r, method])
      if (code === 'ORDER_EXPIRED' || code === 'ORDER_NOT_PAYABLE') router.refresh()
    } catch {
      setPending(false) // aborted: the fan left the page
    }
  }

  const CtaIcon = METHOD_ICONS[method ?? 'CARD']
  return (
    <form onSubmit={pay} className="flex flex-col gap-4">
      <p data-member-hold className="flex items-center gap-3 rounded-control border border-accent/50 bg-accent/10 px-4 py-3.5 text-sm text-accent-hover">
        <ClockIcon className="size-5" />
        <span>{fmt(op.memberBanner, { time: formatTime(props.reservedUntil, props.timezone, copy.meta.intl), left: leftText(op, left) })}</span>
      </p>
      <MethodRows
        methods={props.methods}
        cashLate={props.cashLate}
        cashDeadline={props.cashDeadline}
        refused={refused}
        method={method}
        onPick={setPicked}
        disabled={pending}
        copy={c}
      />
      {error && (
        <p role="alert" className="text-body-small text-danger">
          {error}
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
        {pending ? (method === 'CASH_AGENCY' ? c.creatingCode : c.paying) : ctaText(c, method, formatDh(props.amountMad))}
      </button>
    </form>
  )
}
