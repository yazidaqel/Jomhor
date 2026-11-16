'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import type { Dict } from '@/lib/i18n'
import { loginUrl } from '@/lib/next-path'
import { errorCode, errorText, postJson } from '@/lib/order-client'
import { ArrowIcon } from '../../../icons'

/**
 * W5 "Payer l'échéance · {amount}" (DESIGN Lot 5 step 3b, the payment step): asks `/api/passes/{id}/installments` for
 * the installment order (a new one, or the live one) and opens W4 on it, where the fan picks the method. A refusal
 * (PASS_STATUS, PAID_UP…) shows under the button; 401 goes to login and back.
 */
export function PayInstallment({ passId, label, errors }: { passId: string; label: string; errors: Dict['errors'] }) {
  const router = useRouter()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const abort = useRef<AbortController | null>(null)
  useEffect(() => () => abort.current?.abort(), [])

  async function pay() {
    setPending(true)
    setError(null)
    const ctl = (abort.current = new AbortController())
    try {
      const r = await postJson(`/api/passes/${encodeURIComponent(passId)}/installments`, undefined, 'POST', ctl.signal)
      if (r.ok && typeof r.data?.id === 'string') return router.push(`/orders/${encodeURIComponent(r.data.id)}`)
      setPending(false)
      if (errorCode(r) === 'UNAUTHORIZED') return router.push(loginUrl('/me/tickets'))
      setError(errorText(errors, r))
    } catch {
      setPending(false) // aborted: the fan left the page
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <button
        type="button"
        onClick={pay}
        disabled={pending}
        aria-busy={pending}
        className="flex min-h-touch-min items-center justify-center gap-2 rounded-control bg-accent px-5 text-cta text-on-accent transition-colors duration-200 hover:bg-accent-hover disabled:opacity-60"
      >
        {label}
        {pending ? (
          <span aria-hidden="true" className="size-5 rounded-pill border-2 border-on-accent/30 border-t-on-accent motion-safe:animate-spin" />
        ) : (
          <ArrowIcon className="size-5" />
        )}
      </button>
      {error && (
        <p role="alert" className="text-body-small text-danger">
          {error}
        </p>
      )}
    </div>
  )
}
