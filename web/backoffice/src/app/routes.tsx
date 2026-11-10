import { Navigate, type RouteObject } from 'react-router'
import { Shell } from '@/app/Shell'
import { LoginPage } from '@/features/auth/LoginPage'
import { CrmPage } from '@/features/crm/CrmPage'
import { DevicesPage } from '@/features/devices/DevicesPage'
import { EventPage } from '@/features/events/EventPage'
import { EventsPage } from '@/features/events/EventsPage'
import { CashPage } from '@/features/finance/CashPage'
import { LiveIndexPage, LivePage } from '@/features/live/LivePage'
import { ReconciliationPage } from '@/features/reconciliation/ReconciliationPage'
import { OverviewPage } from '@/features/overview/OverviewPage'
import { PosPage } from '@/features/pos/PosPage'
import { PrintPage } from '@/features/pos/PrintPage'
import { SalesIndexPage, SalesPage } from '@/features/sales/SalesPage'
import { TicketsPage } from '@/features/tickets/TicketsPage'
import { VenuePage } from '@/features/venues/VenuePage'
import { VenuesPage } from '@/features/venues/VenuesPage'

export const routes: RouteObject[] = [
  { path: 'login', element: <LoginPage /> },
  // POS slips print without the shell chrome. Both shells send a role to its landing page off its routes (CASHIER: /pos).
  { element: <Shell bare />, children: [{ path: 'pos/print/:orderId', element: <PrintPage /> }] },
  {
    element: <Shell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'events', element: <EventsPage /> },
      { path: 'events/new', element: <EventPage /> },
      { path: 'events/:id', element: <EventPage /> },
      { path: 'events/:id/sales', element: <SalesPage /> },
      { path: 'events/:id/tickets', element: <TicketsPage /> },
      { path: 'events/:id/live', element: <LivePage /> },
      { path: 'events/:id/reconciliation', element: <ReconciliationPage /> },
      { path: 'sales', element: <SalesIndexPage /> },
      { path: 'live', element: <LiveIndexPage /> },
      { path: 'devices', element: <DevicesPage /> },
      { path: 'pos', element: <PosPage /> },
      { path: 'finance/cash', element: <CashPage /> },
      { path: 'crm', element: <CrmPage /> },
      { path: 'settings/venues', element: <VenuesPage /> },
      { path: 'settings/venues/:id', element: <VenuePage /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]
