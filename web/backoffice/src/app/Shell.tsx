import type { ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { LogOut } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Navigate, NavLink, Outlet, useLocation } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { OrganizerContext, useMe, type Role } from '@/features/auth/organizer'
import { errorKey } from '@/lib/errors'
import { session, useSelectedOrganizerId, useSessionToken } from '@/lib/session'

// GATE and SUPERVISOR staff get 403 on every /org call (04-api §Organizer): not offered here.
const BACKOFFICE_ROLES: readonly Role[] = ['ADMIN', 'OPS', 'FINANCE']

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

function LogoutButton(props: { variant?: 'outline' | 'ghost'; iconOnly?: boolean }) {
  const { t } = useTranslation()
  return (
    <Button
      variant={props.variant ?? 'outline'}
      size={props.iconOnly ? 'icon' : 'default'}
      aria-label={t('shell.logout')}
      title={t('shell.logout')}
      onClick={() => session.clear()}
    >
      <LogOut />
      {!props.iconOnly && t('shell.logout')}
    </Button>
  )
}

/** Full-page message outside the shell layout: loading, /org/me failure, no membership. */
function Notice({ title, body, logout, onRetry }: { title: string; body?: string; logout?: boolean; onRetry?: () => void }) {
  const { t } = useTranslation()
  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>{title}</CardTitle>
          {body && <CardDescription>{body}</CardDescription>}
        </CardHeader>
        {logout && (
          <CardFooter className="gap-2">
            {onRetry && <Button onClick={onRetry}>{t('shell.retry')}</Button>}
            <LogoutButton />
          </CardFooter>
        )}
      </Card>
    </main>
  )
}

export function Shell() {
  const { t } = useTranslation()
  const token = useSessionToken()
  const selectedId = useSelectedOrganizerId()
  const location = useLocation()
  const queryClient = useQueryClient()
  const me = useMe(!!token)

  // No valid session (never signed in, expired, logout, or a 401 cleared it): back to login, then here.
  if (!token) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  if (me.isPending) return <Notice title={t('shell.loading')} />
  if (me.isError) return <Notice title={t(errorKey(me.error))} logout onRetry={() => void me.refetch()} />

  const { user } = me.data
  const memberships = me.data.memberships.filter((m) => BACKOFFICE_ROLES.includes(m.role))
  const current = memberships.find((m) => m.organizer_id === selectedId) ?? memberships[0]
  if (!current) return <Notice title={t('shell.noAccessTitle')} body={t('shell.noAccessBody')} logout />

  const switchTo = (id: string) => {
    // The new organizer's queries live under another key; drop the old ones rather than keep them around.
    queryClient.removeQueries({ queryKey: ['org', current.organizer_id] })
    session.selectOrganizer(id)
  }

  return (
    // DESIGN §Back-office: sidebar 1 1 240px, content 999 1 560px; they wrap (stack) when content would go under
    // 560px, i.e. below 240 + 560 = 800px. CSS cannot see the wrap, so the stacked look keys off that same width.
    // Single row: stretch so the sidebar is full height. Stacked: content-start so rows don't stretch.
    <div className="flex min-h-screen flex-wrap max-[800px]:content-start">
      <nav
        aria-label={t('nav.label')}
        className="flex flex-[1_1_240px] flex-col gap-1 border-r bg-card px-3 py-4 max-[800px]:border-r-0 max-[800px]:border-b"
      >
        <div className="flex items-center gap-2.5 px-2.5 pb-4">
          <img src="/jomhor-mark.svg" alt="" className="size-7 rounded-lg" />
          <span className="text-[15px] font-bold">{t('app.name')}</span>
        </div>
        <Select value={current.organizer_id} onValueChange={switchTo}>
          <SelectTrigger aria-label={t('shell.organizer')} className="mb-2 w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {memberships.map((m) => (
              <SelectItem key={m.organizer_id} value={m.organizer_id}>
                {m.organizer_name} · {t(`roles.${m.role}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
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
        <div className="mt-auto flex items-center gap-2 px-2.5 pt-4">
          <div className="min-w-0 flex-1 text-xs">
            <div className="truncate font-medium">{user.display_name ?? user.phone}</div>
            <div className="text-muted-foreground">{t(`roles.${current.role}`)}</div>
          </div>
          <LogoutButton variant="ghost" iconOnly />
        </div>
      </nav>
      <main className="min-w-0 flex-[999_1_560px] px-7 py-6">
        {/* key: a page never carries state from one organizer into the next. */}
        <OrganizerContext value={current} key={current.organizer_id}>
          <Outlet />
        </OrganizerContext>
      </main>
    </div>
  )
}
