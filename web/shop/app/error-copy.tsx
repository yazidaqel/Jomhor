'use client'

import { createContext } from 'react'
import type { Dict } from '@/lib/i18n'

/**
 * The error page's copy in the request's locale. error.tsx is a client component and can't read the cookie, so the root
 * layout hands it down; only these four strings reach the client, not the dictionaries.
 */
export const ErrorCopyContext = createContext<Dict['error'] | null>(null)

export function ErrorCopy({ value, children }: { value: Dict['error']; children: React.ReactNode }) {
  return <ErrorCopyContext value={value}>{children}</ErrorCopyContext>
}
