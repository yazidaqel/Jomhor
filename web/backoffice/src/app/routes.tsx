import { Navigate, type RouteObject } from 'react-router'
import { Shell } from '@/app/Shell'
import { LoginPage } from '@/features/auth/LoginPage'
import { EventPage } from '@/features/events/EventPage'
import { EventsPage } from '@/features/events/EventsPage'
import { SalesIndexPage, SalesPage } from '@/features/sales/SalesPage'
import { VenuePage } from '@/features/venues/VenuePage'
import { VenuesPage } from '@/features/venues/VenuesPage'

export const routes: RouteObject[] = [
  { path: 'login', element: <LoginPage /> },
  {
    element: <Shell />,
    children: [
      { path: 'events', element: <EventsPage /> },
      { path: 'events/new', element: <EventPage /> },
      { path: 'events/:id', element: <EventPage /> },
      { path: 'events/:id/sales', element: <SalesPage /> },
      { path: 'sales', element: <SalesIndexPage /> },
      { path: 'settings/venues', element: <VenuesPage /> },
      { path: 'settings/venues/:id', element: <VenuePage /> },
      { path: '*', element: <Navigate to="/events" replace /> },
    ],
  },
]
