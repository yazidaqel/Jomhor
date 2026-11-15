import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound, permanentRedirect, redirect } from 'next/navigation'
import { eventAt } from '@/lib/clubs'
import { currentSession } from '@/lib/http'
import { getI18n } from '@/lib/i18n/server'
import { loginUrl } from '@/lib/orders'
import { formatDateTime } from '@/lib/time'
import { Crest } from '../../../../../brand'
import { BackIcon } from '../../../../../icons'
import { QueueRoom } from './QueueRoom'

// Per request: the session decides between the room and the login. The event read is the cached W2 detail (15 s).
export const dynamic = 'force-dynamic'

type Params = { club: string; slug: string }
type Props = { params: Promise<Params> }

async function load({ club, slug }: Params) {
  const hit = await eventAt(club, slug)
  if (!hit) notFound()
  return hit
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const [{ e }, { d }] = await Promise.all([load(await params), getI18n()])
  return { title: `${d.room.metaTitle} · ${e.title}`, robots: { index: false } }
}

/**
 * W8 · `/{club}/events/{slug}/queue`: SSR shell (event aside from the cached detail) + the client room (QueueRoom),
 * which joins through `/api/queue` and polls the API directly. Signed out → login, back here after (plan Q140). No
 * room (none, or ENDED) → W2, where the sale runs without it. Nothing about the fan's entry is rendered here: the
 * queue and admission tokens only ever reach the browser as JSON.
 */
export default async function QueuePage({ params }: Props) {
  const p = await params
  const [{ e, path }, { d }] = await Promise.all([load(p), getI18n()])
  const here = `${path}/queue`
  if (`/${p.club}/events/${p.slug}/queue` !== here) permanentRedirect(here)
  if (!e.room) redirect(path)
  if (!(await currentSession())) redirect(loginUrl(here))
  const apiUrl = process.env.API_PUBLIC_URL?.replace(/\/+$/, '')
  if (!apiUrl) throw new Error('API_PUBLIC_URL is not set')
  // eslint-disable-next-line react-hooks/purity -- server component: rendered once per request, never re-rendered
  const now = Date.now()
  const remaining = e.zones.reduce((sum, z) => sum + z.prices.reduce((s, pr) => s + pr.remaining, 0), 0)
  return (
    <main className="mx-auto flex w-full max-w-[1180px] flex-wrap items-start gap-8 px-6 pt-6 pb-16">
      <section aria-labelledby="queue-title" className="flex min-w-0 flex-[999_1_560px] flex-col gap-6">
        <Link
          href={path}
          className="-ms-2 inline-flex min-h-touch-min items-center gap-2 self-start rounded-chip px-2 text-sm text-text-secondary transition-colors hover:text-text"
        >
          <BackIcon className="size-4" />
          {d.room.back}
        </Link>
        <h1 id="queue-title" className="text-label uppercase text-accent">
          {d.room.title}
        </h1>
        <QueueRoom
          eventId={e.id}
          eventHref={path}
          loginHref={loginUrl(here)}
          apiUrl={apiUrl}
          now={now}
          timezone={e.timezone}
          limit={e.per_account_limit}
          remaining={remaining}
          verified={e.room.eligibility !== null}
          copy={{ room: d.room, event: d.event, errors: d.errors, meta: d.meta }}
        />
      </section>
      <aside
        aria-label={e.title}
        className="flex w-full flex-[1_1_320px] flex-col gap-4 rounded-card border border-border bg-surface p-5 md:sticky md:top-24 md:max-w-[380px]"
      >
        <div className="flex items-center gap-3">
          <Crest club={e.organizer} size={44} />
          <p className="text-body-small text-text-secondary">
            <bdi>{e.organizer.name}</bdi>
          </p>
        </div>
        <p className="font-display text-h1 uppercase">{e.title}</p>
        <p className="text-body-small text-text-muted">
          {`${formatDateTime(e.starts_at, e.timezone, d.meta.intl)} · ${e.venue.name}`}
        </p>
      </aside>
    </main>
  )
}
