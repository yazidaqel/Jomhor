import type { Metadata } from 'next'
import Link from 'next/link'
import { defaultLocale, getDictionary } from '@/lib/i18n'

const d = getDictionary(defaultLocale)

export const metadata: Metadata = { title: d.notFound.metaTitle, robots: { index: false } }

export default function NotFound() {
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col justify-center gap-4 px-5 py-12">
      <h1 className="font-display text-h1 uppercase">{d.notFound.title}</h1>
      <p className="text-body text-text-secondary">{d.notFound.body}</p>
      <Link href="/" className="flex h-cta-height items-center justify-center rounded-control bg-accent text-cta text-on-accent hover:bg-accent-hover">
        {d.notFound.back}
      </Link>
    </main>
  )
}
