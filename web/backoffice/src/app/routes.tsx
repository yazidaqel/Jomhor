import { Navigate, type RouteObject } from 'react-router'
import { Shell } from '@/app/Shell'
import { LoginPage } from '@/features/auth/LoginPage'
import { EventPage } from '@/features/events/EventPage'
import { EventsPage } from '@/features/events/EventsPage'
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
      { path: 'settings/venues', element: <VenuesPage /> },
      { path: 'settings/venues/:id', element: <VenuePage /> },
      { path: '*', element: <Navigate to="/events" replace /> },
    ],
  },
]
