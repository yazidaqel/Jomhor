import type { Metadata } from 'next'
import Link from 'next/link'
import { getI18n } from '@/lib/i18n/server'
import { safeNext } from '@/lib/next-path'
import { Lockup } from '../brand'
import { LanguageSwitcher } from '../(public)/LanguageSwitcher'
import { BackIcon, CheckIcon } from '../icons'
import { LoginForm } from './LoginForm'

export async function generateMetadata(): Promise<Metadata> {
  const { d } = await getI18n()
  return { title: d.login.metaTitle, robots: { index: false } }
}

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: string | string[] }> }) {
  const next = safeNext((await searchParams).next)
  const { locale, d } = await getI18n()
  return (
    <main className="grid min-h-dvh lg:grid-cols-[1.1fr_1fr]">
      <section className="relative flex flex-col px-5 py-6 lg:order-2 lg:justify-center lg:px-14">
        <div className="absolute end-3 top-6 flex lg:end-12">
          <LanguageSwitcher locale={locale} label={d.header.language} />
        </div>
        <Link
          href="/"
          className="-ms-2 inline-flex min-h-touch-min items-center gap-2 self-start rounded-chip px-2 text-sm text-text-secondary transition-colors hover:text-text lg:absolute lg:top-6"
        >
          <BackIcon className="size-4" />
          {d.login.back}
        </Link>
        <div className="mx-auto mt-6 flex w-full max-w-md flex-col gap-6 lg:mt-0">
          <Lockup height={44} label={d.app.name} className="lg:hidden" />
          <div className="rounded-card border border-border bg-surface p-6 shadow-2xl shadow-ground motion-safe:animate-rise sm:p-8">
            <h1 className="font-display text-[40px] font-extrabold uppercase leading-none">{d.login.title}</h1>
            <p className="mt-3 text-body-small text-text-secondary">{d.login.intro}</p>
            <LoginForm copy={{ login: d.login, errors: d.errors }} next={next} />
          </div>
        </div>
      </section>
      {/* Brand side: the Jomhor lockup, large; what signing in gets the fan; the tagline. After the form in the DOM (and at phone width); left of it from lg. */}
      <section className="relative isolate flex flex-col justify-between gap-10 overflow-hidden border-border bg-surface px-6 py-10 max-lg:border-t lg:order-1 lg:border-e lg:px-14 lg:py-12">
        <div aria-hidden="true" className="bg-crowd absolute inset-0 -z-10" />
        <div aria-hidden="true" className="absolute -top-56 -start-40 -z-10 size-[620px] glow opacity-[0.16]" />
        <Link href="/" className="self-start transition-opacity duration-200 hover:opacity-85 max-lg:hidden">
          <Lockup height={64} label={d.app.name} />
        </Link>
        <div className="flex flex-col gap-8">
          <p className="max-w-lg font-display text-hero uppercase text-balance lg:text-[72px] lg:leading-[0.92]">{d.login.pitch}</p>
          <ul className="flex flex-col gap-3">
            {[d.login.perk1, d.login.perk2, d.login.perk3].map((perk, i) => (
              <li key={perk} className="flex items-center gap-3 text-body text-text-secondary motion-safe:animate-rise stagger" style={{ '--i': i + 1 } as React.CSSProperties}>
                <span className="flex size-7 shrink-0 items-center justify-center rounded-pill bg-accent/15 text-accent">
                  <CheckIcon className="size-4" />
                </span>
                {perk}
              </li>
            ))}
          </ul>
        </div>
        <p className="font-display text-xl font-semibold tracking-[0.5px] text-text-secondary">{d.footer.tagline}</p>
      </section>

    </main>
  )
}
