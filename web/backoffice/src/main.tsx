import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, Navigate } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import './index.css'
import './i18n'
import { Shell } from '@/app/Shell'
import { EventsPage } from '@/features/events/EventsPage'
import { VenuesPage } from '@/features/venues/VenuesPage'

const queryClient = new QueryClient()

const router = createBrowserRouter([
  {
    element: <Shell />,
    children: [
      { path: 'events', element: <EventsPage /> },
      { path: 'settings/venues', element: <VenuesPage /> },
      { path: '*', element: <Navigate to="/events" replace /> },
    ],
  },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
