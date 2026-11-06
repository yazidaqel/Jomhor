import type { ReactNode } from 'react'
import { ChevronsUpDown } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { NavLink, Outlet } from 'react-router'
import { Button } from '@/components/ui/button'

// Lit up lot by lot; only Événements and Paramètres › Lieux exist in Lot 0.
const DISABLED = ['sales', 'liveGates', 'devices', 'invitations', 'finance', 'crm'] as const

function Item({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Button
      asChild
      variant="ghost"
      className="justify-start aria-[current=page]:bg-accent aria-[current=page]:font-semibold"
    >
      <NavLink to={to}>{children}</NavLink>
    </Button>
  )
}

export function Shell() {
  const { t } = useTranslation()
  return (
    // DESIGN §Back-office: sidebar 1 1 240px, content 999 1 560px; they wrap (stack) when content would go under 560px.
    <div className="flex min-h-screen flex-wrap">
      <nav aria-label={t('nav.label')} className="flex flex-[1_1_240px] flex-col gap-1 border-r bg-card px-3 py-4">
        <div className="flex items-center gap-2.5 px-2.5 pb-4">
          <img src="/jomhor-mark.svg" alt="" className="size-7 rounded-lg" />
          <span className="text-[15px] font-bold">{t('app.name')}</span>
        </div>
        {/* Organizer switcher slot (BO-2). */}
        <Button variant="outline" disabled className="mb-2 justify-between">
          {t('shell.organizer')}
          <ChevronsUpDown className="text-muted-foreground" />
        </Button>
        <Item to="/events">{t('nav.events')}</Item>
        {DISABLED.map((key) => (
          <Button key={key} variant="ghost" disabled className="justify-start">
            {t(`nav.${key}`)}
          </Button>
        ))}
        <div className="px-4 pt-2 pb-1 text-sm font-medium">{t('nav.settings')}</div>
        <div className="flex flex-col pl-3">
          <Item to="/settings/venues">{t('nav.venues')}</Item>
        </div>
        {/* User + role slot (BO-2). */}
        <div className="mt-auto px-2.5 pt-4 text-xs text-muted-foreground">{t('shell.user')}</div>
      </nav>
      <main className="min-w-0 flex-[999_1_560px] px-7 py-6">
        <Outlet />
      </main>
    </div>
  )
}
