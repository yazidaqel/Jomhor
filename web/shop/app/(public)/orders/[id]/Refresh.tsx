'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'

/** Re-renders the server part every `ms` while the tab is visible (plan Q111). Mounted only while it is needed. */
export function Refresh({ ms = 10_000 }: { ms?: number }) {
  const router = useRouter()
  useEffect(() => {
    const t = setInterval(() => !document.hidden && router.refresh(), ms)
    return () => clearInterval(t)
  }, [router, ms])
  return null
}
