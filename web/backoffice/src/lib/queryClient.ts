import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './api'
import { session } from './session'

export function createQueryClient() {
  const queryClient = new QueryClient({
    defaultOptions: {
      // A 4xx will not change on retry; network errors and 5xx get two more tries.
      queries: { retry: (n, e) => n < 2 && !(e instanceof ApiError && e.status >= 400 && e.status < 500) },
    },
  })
  // Every session starts from an empty cache: logout, a 401, expiry (all session.clear()) and a new
  // login (session.start) change the token, and nothing cached under the old one survives.
  let token = session.token
  session.subscribe(() => {
    if (session.token === token) return
    token = session.token
    queryClient.clear()
  })
  return queryClient
}
