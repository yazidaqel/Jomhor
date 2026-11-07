'use client' // Next requires error boundaries to be client components.

import { use, useEffect } from 'react'
import Link from 'next/link'
import { ErrorCopyContext } from './error-copy'

/** Unexpected errors (backend down, bug), in the request's locale: retry re-fetches and re-renders the segment. */
export default function ErrorPage({ error, retry }: { error: unknown; retry: () => void }) {
  useEffect(() => console.error(error), [error])
  const d = use(ErrorCopyContext)! // the root layout always provides it
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col justify-center gap-4 px-5 py-12">
      <title>{d.title}</title>
      <h1 className="font-display text-h1 uppercase">{d.title}</h1>
      <p role="alert" className="text-body text-text-secondary">
        {d.body}
      </p>
      <button
        type="button"
        onClick={retry}
        className="flex h-cta-height items-center justify-center rounded-control bg-accent text-cta text-on-accent hover:bg-accent-hover"
      >
        {d.retry}
      </button>
      <Link href="/" className="flex min-h-touch-min items-center justify-center text-body-small font-semibold text-accent">
        {d.home}
      </Link>
    </main>
  )
}
