'use client'

import { useEffect, useRef, useState } from 'react'
import { CheckIcon, CopyIcon } from '../../../icons'

/**
 * "Copier le code": puts the grouped code (`JMH 4F7K 29`) on the clipboard. The button label never changes; a separate
 * polite live region says "Code copié", or that copying failed (the code stays selectable by hand).
 */
export function CopyCode({ code, label, done, failed }: { code: string; label: string; done: string; failed: string }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined)
  useEffect(() => () => clearTimeout(timer.current), [])
  async function copy() {
    clearTimeout(timer.current)
    try {
      await navigator.clipboard.writeText(code)
      setState('copied')
      timer.current = setTimeout(() => setState('idle'), 2000)
    } catch {
      setState('failed') // no clipboard (permissions, old browser): stays until the next try
    }
  }
  return (
    <div className="flex flex-col items-start gap-1.5">
      <button
        type="button"
        onClick={copy}
        className="flex h-11 items-center gap-2 rounded-chip border border-ticket-rule bg-ticket-paper px-4 text-sm font-semibold text-ticket-ink transition-colors duration-200 hover:bg-ticket-rule focus-visible:outline-ticket-ink"
      >
        {state === 'copied' ? <CheckIcon key="done" className="size-4 motion-safe:animate-mark" /> : <CopyIcon className="size-4" />}
        {label}
      </button>
      <p role="status" className={state === 'failed' ? 'max-w-56 text-body-small text-ticket-muted' : 'sr-only'}>
        {state === 'copied' ? done : state === 'failed' ? failed : ''}
      </p>
    </div>
  )
}
