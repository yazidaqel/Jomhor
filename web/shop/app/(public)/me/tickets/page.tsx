import type { Metadata } from 'next'
import { redirect } from 'next/navigation'
import { currentSession } from '@/lib/http'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { loginUrl } from '@/lib/orders'
import { TicketList } from './TicketList'

const d = getDictionary(defaultLocale)

export const metadata: Metadata = { title: d.ticketsPage.metaTitle, robots: { index: false } }

/**
 * W5 · `/me/tickets`. Not signed in → login and back. The tickets themselves load in the browser from `/api/tickets`
 * (no ticket data, and never a key, in this HTML).
 */
export default async function TicketsPage() {
  if (!(await currentSession())) redirect(loginUrl('/me/tickets'))
  return (
    <main className="mx-auto flex w-full max-w-[1180px] flex-col gap-6 px-6 pt-10 pb-14">
      <h1 className="font-display text-hero uppercase">{d.ticketsPage.title}</h1>
      <TicketList copy={{ ticketsPage: d.ticketsPage, errors: d.errors, meta: d.meta }} />
    </main>
  )
}
