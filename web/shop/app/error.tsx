'use client' // Next requires error boundaries to be client components.

import { useEffect } from 'react'
import Link from 'next/link'
import { defaultLocale, getDictionary } from '@/lib/i18n'

const d = getDictionary(defaultLocale)

/** FR fallback for unexpected errors (backend down, bug): retry re-fetches and re-renders the segment. */
export default function ErrorPage({ error, retry }: { error: unknown; retry: () => void }) {
  useEffect(() => console.error(error), [error])
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col justify-center gap-4 px-5 py-12">
      <title>{d.error.title}</title>
      <h1 className="font-display text-h1 uppercase">{d.error.title}</h1>
      <p role="alert" className="text-body text-text-secondary">
        {d.error.body}
      </p>
      <button
        type="button"
        onClick={retry}
        className="flex h-cta-height items-center justify-center rounded-control bg-accent text-cta text-on-accent hover:bg-accent-hover"
      >
        {d.error.retry}
      </button>
      <Link href="/" className="flex min-h-touch-min items-center justify-center text-body-small font-semibold text-accent">
        {d.error.home}
      </Link>
    </main>
  )
}
