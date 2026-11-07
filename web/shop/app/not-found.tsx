import type { Metadata } from 'next'
import Link from 'next/link'
import { getI18n } from '@/lib/i18n/server'
import { Lockup } from './brand'

export async function generateMetadata(): Promise<Metadata> {
  const { d } = await getI18n()
  return { title: d.notFound.metaTitle, robots: { index: false } }
}

export default async function NotFound() {
  const { d } = await getI18n()
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col justify-center gap-4 px-5 py-12">
      <Link href="/" className="mb-8 self-start">
        <Lockup height={36} label={d.app.name} />
      </Link>
      <h1 className="font-display text-h1 uppercase">{d.notFound.title}</h1>
      <p className="text-body text-text-secondary">{d.notFound.body}</p>
      <Link href="/" className="flex h-cta-height items-center justify-center rounded-control bg-accent text-cta text-on-accent hover:bg-accent-hover">
        {d.notFound.back}
      </Link>
    </main>
  )
}
