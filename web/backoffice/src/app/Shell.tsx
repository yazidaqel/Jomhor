import { useState, type CSSProperties } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  Building2,
  CalendarDays,
  ChartNoAxesColumn,
  IdCard,
  Landmark,
  LayoutDashboard,
  LogOut,
  MapPin,
  RadioTower,
  Smartphone,
  Store,
  Users,
  type LucideIcon,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, Outlet, useLocation } from 'react-router'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarRail,
  SidebarTrigger,
  useSidebar,
} from '@/components/ui/sidebar'
import { Toaster } from '@/components/ui/sonner'
import { OrganizerContext, useMe, type Me, type Membership, type Role } from '@/features/auth/organizer'
import { errorKey } from '@/lib/errors'
import { session, useSelectedOrganizerId, useSessionToken } from '@/lib/session'

// GATE and SUPERVISOR staff get 403 on every /org call (04-api §Organizer): not offered here.
const BACKOFFICE_ROLES: readonly Role[] = ['ADMIN', 'OPS', 'FINANCE', 'CASHIER']
const POS_ROLES: readonly Role[] = ['ADMIN', 'OPS', 'CASHIER']

/**
 * A CASHIER may call `/org/pos/**` and three event reads only (04-api §Organizer, Lot 3): the Guichet and its print
 * view are its whole back-office, and it lands there. FINANCE gets 403 on `/org/pos/**`: no Guichet.
 */
const home = (role: Role) => (role === 'CASHIER' ? '/pos' : '/')
const allowed = (role: Role, pathname: string) => (/^\/pos(\/|$)/.test(pathname) ? POS_ROLES.includes(role) : role !== 'CASHIER')

type Section = 'overview' | 'events' | 'sales' | 'liveGates' | 'venues' | 'devices' | 'pos' | 'finance'
type NavKey = Section | 'invitations' | 'crm'
// no `to`: lit up in a later lot; `roles`: shown to these roles only (the API answers 403 to the others); no `roles`:
// every role but CASHIER.
type NavItem = { key: NavKey; icon: LucideIcon; to?: string; roles?: readonly Role[] }
// Lit up lot by lot: Événements and Paramètres › Lieux in Lot 0, Ventes in Lot 1, Portes en direct and Appareils in Lot 2,
// Guichet and Finance (its Encaissements page) in Lot 3.
const NAV: { group: 'manage' | 'matchday' | 'relations' | 'settings'; items: NavItem[] }[] = [
  {
    group: 'manage',
    items: [
      { key: 'overview', icon: LayoutDashboard, to: '/' },
      { key: 'events', icon: CalendarDays, to: '/events' },
      { key: 'sales', icon: ChartNoAxesColumn, to: '/sales' },
    ],
  },
  {
    group: 'matchday',
    items: [
      { key: 'liveGates', icon: RadioTower, to: '/live' },
      { key: 'devices', icon: Smartphone, to: '/devices', roles: ['ADMIN', 'OPS'] },
      // Lot 3 (B11): a CASHIER's only entry.
      { key: 'pos', icon: Store, to: '/pos', roles: POS_ROLES },
    ],
  },
  {
    group: 'relations',
    items: [
      { key: 'invitations', icon: IdCard },
      { key: 'finance', icon: Landmark, to: '/finance/cash', roles: ['ADMIN', 'OPS', 'FINANCE'] },
      { key: 'crm', icon: Users },
    ],
  },
  { group: 'settings', items: [{ key: 'venues', icon: MapPin, to: '/settings/venues' }] },
]

/** `/events/:id/sales` and `/events/:id/live` live under Événements in the URL but under Ventes / Portes en direct in the sidebar. */
const section = (pathname: string): Section | null =>
  pathname === '/'
    ? 'overview'
    : /^\/(sales|events\/[^/]+\/sales)\/?$/.test(pathname)
      ? 'sales'
      : /^\/(live|events\/[^/]+\/live)\/?$/.test(pathname)
        ? 'liveGates'
        : pathname.startsWith('/events')
          ? 'events'
          : pathname.startsWith('/settings/venues')
            ? 'venues'
            : pathname.startsWith('/devices')
              ? 'devices'
              : pathname.startsWith('/pos')
                ? 'pos'
                : pathname.startsWith('/finance')
                  ? 'finance'
                  : null

const initials = (name: string) =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join('')

function LogoutButton() {
  const { t } = useTranslation()
  return (
    <Button variant="outline" onClick={() => session.clear()}>
      <LogOut />
      {t('shell.logout')}
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
          <img src="/jomhor-mark-dark.svg" alt="" className="mb-2 size-10 rounded-lg" />
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

/** While /org/me loads: the mark, breathing (still under reduced motion), and the word for assistive tech. */
function Splash() {
  const { t } = useTranslation()
  return (
    <main aria-busy="true" className="flex min-h-screen items-center justify-center">
      <img src="/jomhor-mark-dark.svg" alt="" className="size-12 animate-pulse rounded-xl motion-reduce:animate-none" />
      <p className="sr-only">{t('shell.loading')}</p>
    </main>
  )
}

const visible = (item: NavItem, role: Role) => (item.roles ? item.roles.includes(role) : role !== 'CASHIER')

function AppSidebar({ active, role }: { active: Section | null; role: Role }) {
  const { t } = useTranslation()
  // Labels are hidden only in icon mode: tooltips there, none otherwise (one Radix tooltip per item is not free).
  const { state, isMobile, setOpenMobile } = useSidebar()
  const iconMode = state === 'collapsed' && !isMobile
  // The phone sheet closes once a destination is picked (DESIGN §Back-office).
  const closeSheet = () => setOpenMobile(false)
  const groups = NAV.map(({ group, items }) => ({ group, items: items.filter((i) => visible(i, role)) })).filter((g) => g.items.length > 0)
  return (
    <Sidebar collapsible="icon" label={t('nav.label')}>
      <SidebarHeader className="h-14 justify-center border-b">
        <Link
          to={home(role)}
          onClick={closeSheet}
          className="flex items-center gap-2.5 rounded-md px-1 outline-none focus-visible:ring-[3px] focus-visible:ring-sidebar-ring/50 group-data-[collapsible=icon]:px-0"
        >
          {/* BRAND §Where the logo appears: the lockup (132 px, above the 120 px minimum), the mark alone in icon mode. */}
          <img src="/jomhor-lockup-light.svg" alt={t('app.name')} className="h-8 w-auto group-data-[collapsible=icon]:hidden" />
          <img src="/jomhor-mark-dark.svg" alt={t('app.name')} className="hidden size-7 shrink-0 rounded-lg group-data-[collapsible=icon]:block" />
        </Link>
      </SidebarHeader>
      <SidebarContent>
        <nav aria-label={t('nav.label')}>
          {groups.map(({ group, items }) => (
            <SidebarGroup key={group}>
              <SidebarGroupLabel>{t(`nav.groups.${group}`)}</SidebarGroupLabel>
              <SidebarGroupContent>
                <SidebarMenu>
                  {items.map(({ key, icon: Icon, to }) => (
                    <SidebarMenuItem key={key}>
                      {to ? (
                        <SidebarMenuButton
                          asChild
                          isActive={active === key}
                          tooltip={iconMode ? t(`nav.${key}`) : undefined}
                          className="transition-[background-color,box-shadow,width,height,padding] duration-[220ms] data-[active=true]:font-semibold data-[active=true]:shadow-[inset_2px_0_0_var(--sidebar-primary)]"
                        >
                          <Link to={to} aria-current={active === key ? 'page' : undefined} onClick={closeSheet}>
                            <Icon />
                            <span>{t(`nav.${key}`)}</span>
                          </Link>
                        </SidebarMenuButton>
                      ) : (
                        <>
                          {/* aria-disabled, not disabled: stays focusable, so icon mode can still say what it is and that it comes later. */}
                          <SidebarMenuButton
                            aria-disabled="true"
                            tooltip={iconMode ? t('nav.soonLabel', { label: t(`nav.${key}`) }) : undefined}
                            className="aria-disabled:pointer-events-auto aria-disabled:cursor-default aria-disabled:hover:bg-transparent"
                          >
                            <Icon />
                            <span>{t(`nav.${key}`)}</span>
                          </SidebarMenuButton>
                          <SidebarMenuBadge className="text-[10px] font-medium text-muted-foreground">{t('nav.soon')}</SidebarMenuBadge>
                        </>
                      )}
                    </SidebarMenuItem>
                  ))}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>
          ))}
        </nav>
      </SidebarContent>
      <SidebarRail label={t('shell.toggleSidebar')} />
    </Sidebar>
  )
}

function UserMenu({ user, role }: { user: Me['user']; role: Role }) {
  const { t } = useTranslation()
  const name = user.display_name ?? user.phone
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        {/* Its accessible name is the visible name + role (WCAG 2.5.3); below md they stay for screen readers only. */}
        <Button variant="ghost" className="h-10 gap-2.5 px-2">
          <Avatar className="size-7">
            <AvatarFallback className="bg-primary text-[11px] font-semibold text-primary-foreground">{initials(user.display_name ?? '') || '·'}</AvatarFallback>
          </Avatar>
          <span className="sr-only min-w-0 flex-col items-start text-left leading-tight md:not-sr-only md:flex">
            <span className="max-w-40 truncate text-sm font-medium">{name}</span>
            <span className="text-xs font-normal text-muted-foreground">{t(`roles.${role}`)}</span>
          </span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60">
        <DropdownMenuLabel className="font-normal">
          <div className="truncate text-sm font-medium">{name}</div>
          <div className="truncate text-xs text-muted-foreground">{user.phone}</div>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => session.clear()}>
          <LogOut />
          {t('shell.logout')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function OrganizerSwitcher({ memberships, current, onSwitch }: { memberships: Membership[]; current: Membership; onSwitch: (id: string) => void }) {
  const { t } = useTranslation()
  return (
    <Select value={current.organizer_id} onValueChange={onSwitch}>
      <SelectTrigger aria-label={t('shell.organizer')} className="h-9 max-w-72 gap-2 border-transparent bg-transparent font-medium shadow-none hover:bg-accent">
        <Building2 className="text-muted-foreground" aria-hidden="true" />
        {/* The trigger shows the organizer only; the list also gives each role. */}
        <SelectValue>{current.organizer_name}</SelectValue>
      </SelectTrigger>
      <SelectContent>
        {memberships.map((m) => (
          <SelectItem key={m.organizer_id} value={m.organizer_id}>
            {m.organizer_name} · {t(`roles.${m.role}`)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

/** `bare`: the same session and organizer checks without the chrome (sidebar, top bar), for the POS print view. */
export function Shell({ bare = false }: { bare?: boolean }) {
  const { t } = useTranslation()
  const token = useSessionToken()
  const selectedId = useSelectedOrganizerId()
  const location = useLocation()
  const queryClient = useQueryClient()
  const me = useMe(!!token)
  // Wide screens start with the full sidebar, 768-1023 px with icons only (more room for tables); the user toggles.
  const [sidebarOpen] = useState(() => window.innerWidth >= 1024)

  // No valid session (never signed in, expired, logout, or a 401 cleared it): back to login, then here.
  if (!token) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  if (me.isPending) return <Splash />
  // Only when nothing was ever loaded: a failed background refetch keeps the page (and the print view) mounted.
  if (!me.data) return <Notice title={t(errorKey(me.error))} logout onRetry={() => void me.refetch()} />

  const { user } = me.data
  const memberships = me.data.memberships.filter((m) => BACKOFFICE_ROLES.includes(m.role))
  const current = memberships.find((m) => m.organizer_id === selectedId) ?? memberships[0]
  if (!current) return <Notice title={t('shell.noAccessTitle')} body={t('shell.noAccessBody')} logout />

  // A page this role can't use (a CASHIER outside the Guichet, FINANCE on it, or after a switch): its landing page.
  if (!allowed(current.role, location.pathname)) return <Navigate to={home(current.role)} replace />

  const switchTo = (id: string) => {
    // The new organizer's queries live under another key; drop the old ones rather than keep them around.
    queryClient.removeQueries({ queryKey: ['org', current.organizer_id] })
    session.selectOrganizer(id)
  }

  const active = section(location.pathname)
  // Browser tab: "Jomhor · <section>" (BRAND §Where the logo appears); React hoists it into <head>.
  const title = <title>{active ? t('app.pageTitle', { page: t(`nav.${active}`) }) : t('app.name')}</title>

  if (bare)
    return (
      <OrganizerContext value={current} key={current.organizer_id}>
        {title}
        <Outlet />
      </OrganizerContext>
    )

  return (
    // DESIGN §Back-office: 240 px grouped sidebar, icons only from 768 to 1023 px, a sheet below 768 px (closes on
    // navigation); sticky top bar with the toggle, the organizer switcher and the user menu.
    <SidebarProvider defaultOpen={sidebarOpen} style={{ '--sidebar-width': '15rem' } as CSSProperties}>
      {title}
      <a
        href="#content"
        className="sr-only z-50 rounded-md bg-primary px-3 py-2 text-sm text-primary-foreground focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        {t('shell.skipToContent')}
      </a>
      <AppSidebar active={active} role={current.role} />
      <SidebarInset className="min-w-0">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b bg-background/80 px-3 backdrop-blur-md md:px-5">
          <SidebarTrigger label={t('shell.toggleSidebar')} title={t('shell.toggleSidebar')} />
          <Separator orientation="vertical" className="mx-1 data-[orientation=vertical]:h-5" />
          <OrganizerSwitcher memberships={memberships} current={current} onSwitch={switchTo} />
          <div className="ml-auto">
            <UserMenu user={user} role={current.role} />
          </div>
        </header>
        <main id="content" tabIndex={-1} className="mx-auto w-full max-w-[1320px] px-4 py-6 outline-none md:px-6 lg:px-8 lg:py-8">
          {/* key: a page never carries state from one organizer into the next. */}
          <OrganizerContext value={current} key={current.organizer_id}>
            {/* Page cross-fade (DESIGN §6): each page fades in on arrival; off under reduced motion. */}
            <div key={location.pathname} className="animate-in duration-200 fade-in-0 motion-reduce:animate-none">
              <Outlet />
            </div>
          </OrganizerContext>
        </main>
      </SidebarInset>
      <Toaster position="bottom-right" containerAriaLabel={t('shell.notifications')} />
    </SidebarProvider>
  )
}
