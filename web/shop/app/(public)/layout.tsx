import Link from 'next/link'
import { ViewTransition } from 'react'
import { shopName } from '@/lib/club'
import { getI18n } from '@/lib/i18n/server'
import { Lockup } from '../brand'
import { AccountMenu } from './AccountMenu'
import { LanguageSwitcher } from './LanguageSwitcher'

const navLink = 'flex min-h-touch-min items-center rounded-chip px-3 text-text-secondary transition-colors duration-200 hover:text-text'

// The signed-in state is a client island (AccountMenu → GET /api/me), so this layout never reads the session cookie.
export default async function PublicLayout({ children }: { children: React.ReactNode }) {
  const { locale, d } = await getI18n()
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-30 border-b border-border bg-ground/85 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-[1180px] items-center justify-between gap-4 px-5 sm:px-6">
          {/* Jomhor first (BRAND.md: not white-label); the club is the secondary line, from sm. */}
          <Link href="/" className="group flex min-h-touch-min min-w-0 items-center gap-4">
            <Lockup height={32} label={d.app.name} className="transition-opacity duration-200 group-hover:opacity-85" />
            <span aria-hidden="true" className="h-6 w-px shrink-0 bg-border max-sm:hidden" />
            <span className="truncate text-sm font-medium text-text-secondary transition-colors duration-200 group-hover:text-text max-sm:sr-only">
              {shopName(d)}
            </span>
          </Link>
          <nav aria-label={d.header.nav} className="flex items-center gap-1 text-sm">
            <Link href="/" className={`${navLink} max-sm:hidden`}>
              {d.header.events}
            </Link>
            <Link href="/me/tickets" className={`${navLink} max-sm:hidden`}>
              {d.header.tickets}
            </Link>
            <div className="flex sm:me-2">
              <LanguageSwitcher locale={locale} label={d.header.language} />
            </div>
            <AccountMenu copy={d.header} />
          </nav>
        </div>
      </header>
      <ViewTransition>{children}</ViewTransition>
      <footer className="mt-auto border-t border-border">
        <div className="mx-auto flex max-w-[1180px] flex-col gap-3 px-6 py-10 sm:flex-row sm:items-center sm:justify-between">
          <Lockup height={32} label={d.app.name} />
          <p className="font-display text-lg font-semibold tracking-[0.5px] text-text-secondary">{d.footer.tagline}</p>
        </div>
      </footer>
    </div>
  )
}
