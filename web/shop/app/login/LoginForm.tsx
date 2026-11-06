'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { fmt, type Dict } from '@/lib/i18n'
import { asciiDigits, normalizePhone } from '@/lib/phone'

type Copy = Pick<Dict, 'login' | 'errors'>

/** POSTs to a shop route handler; null on success, else the 04 error code (`NETWORK` when unreachable). */
async function post(path: string, body: unknown): Promise<string | null> {
  try {
    const res = await fetch(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
    if (res.ok) return null
    const json: unknown = await res.json().catch(() => null)
    const code = (json as { code?: unknown } | null)?.code
    return typeof code === 'string' ? code : 'generic'
  } catch {
    return 'NETWORK'
  }
}

// Own keys only: a code such as "constructor" must not pick up an Object.prototype member.
const message = (copy: Copy, code: string) =>
  Object.hasOwn(copy.errors, code) ? copy.errors[code as keyof Copy['errors']] : copy.errors.generic

const input =
  'mt-2 block h-cta-height w-full rounded-control border border-border bg-ground px-4 text-body text-text placeholder:text-text-muted aria-invalid:border-danger'
const primary =
  'mt-4 flex h-cta-height w-full items-center justify-center rounded-control bg-accent text-base font-semibold text-on-accent hover:bg-accent-hover disabled:opacity-60'
const link = 'min-h-touch-min text-body-small font-semibold text-accent underline-offset-4 hover:underline disabled:text-text-disabled disabled:no-underline'

/** Phone → OTP → httpOnly session cookie (set by /api/auth/verify), then `next` (already validated server-side). */
export function LoginForm({ copy, next }: { copy: Copy; next: string }) {
  const [phone, setPhone] = useState<string | null>(null)
  return phone ? (
    <CodeStep copy={copy} next={next} phone={phone} onChangePhone={() => setPhone(null)} />
  ) : (
    <PhoneStep copy={copy} onSent={setPhone} />
  )
}

function PhoneStep({ copy, onSent }: { copy: Copy; onSent: (phone: string) => void }) {
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const phone = normalizePhone(String(new FormData(e.currentTarget).get('phone') ?? ''))
    if (!phone) return setError(copy.login.phoneInvalid)
    setPending(true)
    const failure = await post('/api/auth/otp', { phone })
    setPending(false)
    if (failure) setError(message(copy, failure))
    else onSent(phone)
  }

  return (
    <form noValidate onSubmit={submit} className="mt-6">
      <label htmlFor="phone" className="text-label uppercase text-text-secondary">
        {copy.login.phoneLabel}
      </label>
      <input
        id="phone"
        name="phone"
        type="tel"
        autoComplete="tel"
        autoFocus
        dir="ltr"
        placeholder={copy.login.phonePlaceholder}
        aria-invalid={!!error}
        aria-describedby={error ? 'phone-error' : undefined}
        className={input}
      />
      <p id="phone-error" role="alert" className="mt-2 text-body-small text-danger empty:hidden">
        {error}
      </p>
      <button type="submit" disabled={pending} aria-busy={pending} className={primary}>
        {copy.login.sendCode}
      </button>
    </form>
  )
}

/** Seconds before "Renvoyer le code" is enabled, after the first send and after each resend. */
const RESEND_COOLDOWN_S = 30

function CodeStep({ copy, next, phone, onChangePhone }: { copy: Copy; next: string; phone: string; onChangePhone: () => void }) {
  const router = useRouter()
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [resent, setResent] = useState(false)
  const [wait, setWait] = useState(RESEND_COOLDOWN_S)
  useEffect(() => {
    const id = setInterval(() => setWait((s) => Math.max(0, s - 1)), 1000)
    return () => clearInterval(id)
  }, [])

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const code = asciiDigits(String(new FormData(e.currentTarget).get('code') ?? '')).replace(/\s/g, '')
    if (!/^\d{6}$/.test(code)) return setError(copy.login.codeInvalid)
    setPending(true)
    const failure = await post('/api/auth/verify', { phone, code })
    if (!failure) {
      router.replace(next)
      router.refresh()
      return
    }
    setPending(false)
    setError(message(copy, failure))
  }

  async function resend() {
    setWait(RESEND_COOLDOWN_S)
    const failure = await post('/api/auth/otp', { phone })
    setError(failure && message(copy, failure))
    setResent(!failure)
  }

  return (
    <form noValidate onSubmit={submit} className="mt-6">
      <label htmlFor="code" className="text-label uppercase text-text-secondary">
        {copy.login.codeLabel}
      </label>
      <p id="code-description" className="mt-1 text-body-small text-text-muted">
        {fmt(resent ? copy.login.codeResent : copy.login.codeSent, { phone })}
      </p>
      <input
        id="code"
        name="code"
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={6}
        autoFocus
        dir="ltr"
        aria-invalid={!!error}
        aria-describedby={error ? 'code-description code-error' : 'code-description'}
        className={`${input} font-display text-number tracking-[0.3em]`}
      />
      <p id="code-error" role="alert" className="mt-2 text-body-small text-danger empty:hidden">
        {error}
      </p>
      <button type="submit" disabled={pending} aria-busy={pending} className={primary}>
        {copy.login.verify}
      </button>
      <div className="mt-2 flex flex-wrap justify-between gap-2">
        <button type="button" onClick={onChangePhone} className={link}>
          {copy.login.changePhone}
        </button>
        <button type="button" onClick={resend} disabled={wait > 0} className={link}>
          {wait > 0 ? fmt(copy.login.resendIn, { seconds: wait }) : copy.login.resend}
        </button>
      </div>
    </form>
  )
}
