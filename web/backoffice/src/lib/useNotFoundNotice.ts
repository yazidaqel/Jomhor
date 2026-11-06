import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'

/**
 * True when a detail page sent us here on an unknown id (`state: { notFound: true }`). Shown once: the state is
 * cleared from the history entry so back/reload don't repeat it.
 */
export function useNotFoundNotice() {
  const location = useLocation()
  const navigate = useNavigate()
  const arrived = !!(location.state as { notFound?: boolean } | null)?.notFound
  const [notFound] = useState(arrived)
  useEffect(() => {
    if (arrived) void navigate(location.pathname, { replace: true, state: null })
  }, [arrived, location.pathname, navigate])
  return notFound
}
