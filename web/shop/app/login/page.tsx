import type { Metadata } from 'next'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { safeNext } from '@/lib/next-path'
import { LoginForm } from './LoginForm'

const d = getDictionary(defaultLocale)

export const metadata: Metadata = { title: d.login.metaTitle, robots: { index: false } }

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: string | string[] }> }) {
  const next = safeNext((await searchParams).next)
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col justify-center px-5 py-12">
      <div className="rounded-card border border-border bg-surface p-5">
        <h1 className="font-display text-h1 uppercase">{d.login.title}</h1>
        <p className="mt-3 text-body-small text-text-secondary">{d.login.intro}</p>
        <LoginForm copy={{ login: d.login, errors: d.errors }} next={next} />
      </div>
    </main>
  )
}
