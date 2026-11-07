import { ImageResponse } from 'next/og'
import { notFound } from 'next/navigation'
import { club } from '@/lib/club'
import { getEvent, parseEventId } from '@/lib/events'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { logoDataUrl } from '@/lib/logo'
import { formatDateTime } from '@/lib/time'
import tokens from '@/lib/tokens'
import { kicker } from '../../sale'

// FR whatever the reader's locale: crawlers send no cookie, and the default OG font has no Arabic glyphs.
const d = getDictionary(defaultLocale)
const c = tokens.color

export const size = { width: 1200, height: 630 }
export const contentType = 'image/png'
export const alt = d.event.ogAlt

// ponytail: default OG font (no Barlow/Inter files bundled); add the TTFs to ImageResponse `fonts` if the look matters.
/** WhatsApp / social preview: crest + club, the Jomhor lockup at the end, competition, title, date in the venue's zone, venue. */
export default async function OpenGraphImage({ params }: { params: Promise<{ slug: string }> }) {
  const id = parseEventId((await params).slug)
  const e = id === null ? null : await getEvent(id)
  if (!e) notFound()
  const label = kicker(d, e)
  return new ImageResponse(
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'space-between', padding: 72, background: c.ground, color: c.text }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
          <div style={{ width: 88, height: 88, borderRadius: 44, background: c.accent, color: c.onAccent, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 52, fontWeight: 800 }}>
            {club.crestInitial}
          </div>
          <div style={{ fontSize: 40, fontWeight: 700 }}>{club.name}</div>
        </div>
        {/* eslint-disable-next-line @next/next/no-img-element -- Satori draws <img>, not next/image */}
        <img src={await logoDataUrl('jomhor-lockup-dark.svg')} alt="" width={231} height={56} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
        {label && <div style={{ fontSize: 28, fontWeight: 600, letterSpacing: 3, color: c.accent, textTransform: 'uppercase' }}>{label}</div>}
        <div style={{ fontSize: 84, fontWeight: 800, lineHeight: 0.95, textTransform: 'uppercase' }}>{e.title}</div>
      </div>
      <div style={{ display: 'flex', gap: 20, fontSize: 34, color: c.textSecondary }}>
        <div style={{ display: 'flex', padding: '12px 22px', borderRadius: tokens.radius.chip, background: c.surface }}>{formatDateTime(e.starts_at, e.timezone, d.meta.intl)}</div>
        <div style={{ display: 'flex', padding: '12px 22px', borderRadius: tokens.radius.chip, background: c.surface }}>{e.venue.name}</div>
      </div>
    </div>,
    size,
  )
}
