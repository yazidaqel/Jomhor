import Link from 'next/link'
import { club, shopName } from '@/lib/club'
import { defaultLocale, getDictionary } from '@/lib/i18n'

const d = getDictionary(defaultLocale)
const navLink = 'flex min-h-touch-min items-center rounded-chip px-3 text-text-secondary hover:text-text'

// ponytail: no language switch (Q26, Lot 4) and no signed-in state in the header (it would make every page read the
// session cookie); "Se connecter" leads to Mes billets.
export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="border-b border-border">
        <div className="mx-auto flex max-w-[1180px] flex-wrap items-center justify-between gap-x-4 gap-y-1 px-6 py-2.5">
          <Link href="/" className="flex min-h-touch-min items-center gap-3">
            <span
              aria-hidden="true"
              className="flex size-8 items-center justify-center rounded-pill bg-accent font-display text-lg font-extrabold text-on-accent"
            >
              {club.crestInitial}
            </span>
            <span className="font-display text-xl font-bold tracking-[0.5px]">{shopName}</span>
          </Link>
          <nav aria-label={d.header.nav} className="flex flex-wrap items-center gap-1 text-sm">
            <Link href="/" className={navLink}>
              {d.header.events}
            </Link>
            <Link href="/me/tickets" className={navLink}>
              {d.header.tickets}
            </Link>
            <Link
              href="/login?next=%2Fme%2Ftickets"
              className="ms-2 flex min-h-touch-min items-center rounded-chip border border-border px-4 font-medium text-text hover:border-border-muted"
            >
              {d.header.login}
            </Link>
          </nav>
        </div>
      </header>
      {children}
      <footer className="mt-auto border-t border-border">
        <p className="mx-auto max-w-[1180px] px-6 py-5 text-xs text-text-muted">{d.footer.poweredBy}</p>
      </footer>
    </div>
  )
}
