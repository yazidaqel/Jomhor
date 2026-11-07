import type { Metadata } from 'next'
import { redirect } from 'next/navigation'
import { currentSession } from '@/lib/http'
import { getI18n } from '@/lib/i18n/server'
import { loginUrl } from '@/lib/orders'
import { PhoneIcon } from '../../../icons'
import { PendingOrders } from './PendingOrders'
import { TicketList } from './TicketList'

export async function generateMetadata(): Promise<Metadata> {
  const { d } = await getI18n()
  return { title: d.ticketsPage.metaTitle, robots: { index: false } }
}

// ponytail: placeholder link. The fan app registers the `jomhor-fan` scheme but routes only `orders/{id}` (Android
// intent filter host `orders`); it needs a `tickets` host → `/tickets`. No store fallback when the app is missing.
const APP_TICKETS_URL = 'jomhor-fan://tickets'

/**
 * W5 · `/me/tickets`. Not signed in → login and back. The tickets themselves load in the browser from `/api/tickets`
 * (no ticket data, and never a key, in this HTML); the pending cash orders above them from `/api/orders`.
 */
export default async function TicketsPage() {
  if (!(await currentSession())) redirect(loginUrl('/me/tickets'))
  const { d } = await getI18n()
  const c = d.ticketsPage
  return (
    <main>
      <section className="relative isolate overflow-hidden border-b border-border bg-surface">
        <div aria-hidden="true" className="bg-crowd absolute inset-0 -z-10" />
        <div aria-hidden="true" className="absolute -top-56 -end-40 -z-10 size-[520px] glow opacity-[0.14]" />
        <div className="mx-auto flex max-w-[1180px] flex-wrap items-end justify-between gap-8 px-6 pt-12 pb-10">
          <div className="flex max-w-xl flex-col gap-3 motion-safe:animate-rise">
            <h1 className="font-display text-hero uppercase sm:text-[64px]">{c.title}</h1>
            <p className="text-body text-text-secondary">{c.intro}</p>
          </div>
          <div className="flex items-center gap-4 rounded-card border border-border bg-ground p-4 motion-safe:animate-rise stagger [--i:1]">
            <span className="flex size-12 shrink-0 items-center justify-center rounded-pill bg-accent/12 text-accent">
              <PhoneIcon className="size-6" />
            </span>
            <div className="flex flex-col gap-2">
              <p className="max-w-56 text-body-small text-text-secondary">{c.openAppHint}</p>
              <a
                href={APP_TICKETS_URL}
                className="flex min-h-touch-min items-center justify-center rounded-control border border-border px-4 text-sm font-semibold text-text transition-colors duration-200 hover:border-accent hover:text-accent"
              >
                {c.openApp}
              </a>
            </div>
          </div>
        </div>
      </section>
      <div className="mx-auto flex w-full max-w-[1180px] flex-col gap-12 px-6 pt-10 pb-16">
        <PendingOrders copy={{ ticketsPage: d.ticketsPage, meta: d.meta }} />
        <TicketList copy={{ ticketsPage: d.ticketsPage, errors: d.errors, meta: d.meta }} />
      </div>
    </main>
  )
}
