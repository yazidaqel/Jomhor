import { Navigate, type RouteObject } from 'react-router'
import { Shell } from '@/app/Shell'
import { LoginPage } from '@/features/auth/LoginPage'
import { EventsPage } from '@/features/events/EventsPage'
import { VenuesPage } from '@/features/venues/VenuesPage'

export const routes: RouteObject[] = [
  { path: 'login', element: <LoginPage /> },
  {
    element: <Shell />,
    children: [
      { path: 'events', element: <EventsPage /> },
      { path: 'settings/venues', element: <VenuesPage /> },
      { path: '*', element: <Navigate to="/events" replace /> },
    ],
  },
]
