import Link from 'next/link'
import { ViewTransition } from 'react'
import { shopName } from '@/lib/club'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { Crest, JMark } from '../brand'
import { AccountMenu } from './AccountMenu'

const d = getDictionary(defaultLocale)
const navLink = 'flex min-h-touch-min items-center rounded-chip px-3 text-text-secondary transition-colors duration-200 hover:text-text'

// ponytail: no language switch (Q26, Lot 4). The signed-in state is a client island (AccountMenu → GET /api/me), so this
// layout never reads the session cookie and public pages keep their caching.
export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-30 border-b border-border bg-ground/85 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-[1180px] items-center justify-between gap-4 px-5 sm:px-6">
          <Link href="/" className="group flex min-h-touch-min min-w-0 items-center gap-3">
            <Crest size="sm" className="transition-transform duration-200 group-hover:scale-110" />
            <span className="truncate font-display text-xl font-bold tracking-[0.5px]">{shopName}</span>
          </Link>
          <nav aria-label={d.header.nav} className="flex items-center gap-1 text-sm">
            <Link href="/" className={`${navLink} max-sm:hidden`}>
              {d.header.events}
            </Link>
            <Link href="/me/tickets" className={`${navLink} me-2 max-sm:hidden`}>
              {d.header.tickets}
            </Link>
            <AccountMenu copy={d.header} />
          </nav>
        </div>
      </header>
      <ViewTransition>{children}</ViewTransition>
      <footer className="mt-auto border-t border-border">
        <div className="mx-auto flex max-w-[1180px] items-center gap-2.5 px-6 py-6 text-xs text-text-muted">
          <JMark size={24} />
          <p>{d.footer.poweredBy}</p>
        </div>
      </footer>
    </div>
  )
}
