import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { api } from '@/lib/api'
import { errorKey } from '@/lib/errors'
import { session, useSessionToken } from '@/lib/session'
import type { Membership } from './organizer'
import { normalizePhone } from './phone'

const phoneSchema = z.object({
  phone: z
    .string()
    .refine((v) => normalizePhone(v) !== null)
    .transform((v) => normalizePhone(v)!),
})
const codeSchema = z.object({ code: z.string().trim().regex(/^\d{6}$/) })

type VerifyResponse = { token: string; expires_at: string; memberships: Membership[] }

const post = <T,>(path: string, body: unknown) => api<T>(path, { method: 'POST', body: JSON.stringify(body) })

export function LoginPage() {
  const { t } = useTranslation()
  const token = useSessionToken()
  const location = useLocation()
  const [phone, setPhone] = useState<string | null>(null)

  // Signed in (now or already): go where the user was headed, set by the shell guard.
  if (token) return <Navigate to={(location.state as { from?: string } | null)?.from ?? '/events'} replace />

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <div className="flex items-center gap-2.5 pb-2">
            <img src="/jomhor-mark.svg" alt="" className="size-8 rounded-lg" />
            <span className="text-[15px] font-bold">{t('app.name')}</span>
          </div>
          <CardTitle className="text-xl">{t('login.title')}</CardTitle>
          <CardDescription>{t('login.description')}</CardDescription>
        </CardHeader>
        <CardContent>
          {phone ? <CodeStep phone={phone} onChangePhone={() => setPhone(null)} /> : <PhoneStep onSent={setPhone} />}
        </CardContent>
      </Card>
    </main>
  )
}

function PhoneStep({ onSent }: { onSent: (phone: string) => void }) {
  const { t } = useTranslation()
  const form = useForm({ resolver: zodResolver(phoneSchema), defaultValues: { phone: '' } })
  // Always 202 (no staff enumeration): only transport errors can show here.
  const send = useMutation({ mutationFn: (phone: string) => post<void>('/org/auth/otp', { phone }), onSuccess: (_, p) => onSent(p) })
  const error = form.formState.errors.phone ? t('login.phoneInvalid') : send.error && t(errorKey(send.error))

  return (
    <form noValidate onSubmit={form.handleSubmit(({ phone }) => send.mutate(phone))}>
      <FieldGroup>
        <Field data-invalid={!!error}>
          <FieldLabel htmlFor="phone">{t('login.phoneLabel')}</FieldLabel>
          <Input
            id="phone"
            type="tel"
            autoComplete="tel"
            autoFocus
            placeholder={t('login.phonePlaceholder')}
            aria-invalid={!!error}
            aria-describedby={error ? 'phone-error' : undefined}
            {...form.register('phone')}
          />
          {error && <FieldError id="phone-error">{error}</FieldError>}
        </Field>
        <Button type="submit" disabled={send.isPending}>
          {t('login.sendCode')}
        </Button>
      </FieldGroup>
    </form>
  )
}

/** Seconds before "Renvoyer le code" is enabled, from the first send and after each resend. */
const RESEND_COOLDOWN_S = 30

function CodeStep({ phone, onChangePhone }: { phone: string; onChangePhone: () => void }) {
  const { t } = useTranslation()
  const form = useForm({ resolver: zodResolver(codeSchema), defaultValues: { code: '' } })
  const verify = useMutation({
    mutationFn: (code: string) => post<VerifyResponse>('/org/auth/verify', { phone, code }),
    // The token change re-renders LoginPage, which redirects.
    onSuccess: (r) => session.start(r.token, r.expires_at),
  })
  const [wait, setWait] = useState(RESEND_COOLDOWN_S)
  useEffect(() => {
    const id = setInterval(() => setWait((s) => Math.max(0, s - 1)), 1000)
    return () => clearInterval(id)
  }, [])
  const resend = useMutation({
    mutationFn: () => post<void>('/org/auth/otp', { phone }),
    onSuccess: () => {
      setWait(RESEND_COOLDOWN_S)
      verify.reset()
      form.reset()
    },
  })
  const failure = verify.error ?? resend.error
  const error = form.formState.errors.code ? t('login.codeInvalid') : failure && t(errorKey(failure))

  return (
    <form noValidate onSubmit={form.handleSubmit(({ code }) => verify.mutate(code))}>
      <FieldGroup>
        <Field data-invalid={!!error}>
          <FieldLabel htmlFor="code">{t('login.codeLabel')}</FieldLabel>
          <FieldDescription id="code-description">
            {t(resend.isSuccess ? 'login.codeResent' : 'login.codeSent', { phone })}
          </FieldDescription>
          <Input
            id="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            autoFocus
            aria-invalid={!!error}
            aria-describedby={error ? 'code-description code-error' : 'code-description'}
            {...form.register('code')}
          />
          {error && <FieldError id="code-error">{error}</FieldError>}
        </Field>
        <Button type="submit" disabled={verify.isPending}>
          {t('login.verify')}
        </Button>
        <div className="flex flex-wrap justify-between">
          <Button type="button" variant="link" className="px-0" onClick={onChangePhone}>
            {t('login.changePhone')}
          </Button>
          <Button
            type="button"
            variant="link"
            className="px-0"
            disabled={wait > 0 || resend.isPending}
            onClick={() => resend.mutate()}
          >
            {wait > 0 ? t('login.resendIn', { seconds: wait }) : t('login.resend')}
          </Button>
        </div>
      </FieldGroup>
    </form>
  )
}
