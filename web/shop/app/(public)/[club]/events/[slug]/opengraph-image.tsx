import { ImageResponse } from 'next/og'
import { notFound } from 'next/navigation'
import { eventAt } from '@/lib/clubs'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { logoDataUrl } from '@/lib/logo'
import { formatDateTime } from '@/lib/time'
import tokens from '@/lib/tokens'
import { initialOf } from '../../../../brand'
import { kicker } from '../../../sale'

// FR whatever the reader's locale: crawlers send no cookie, and the default OG font has no Arabic glyphs.
const d = getDictionary(defaultLocale)
const c = tokens.color

export const size = { width: 1200, height: 630 }
export const contentType = 'image/png'
export const alt = d.event.ogAlt

// ponytail: default OG font (no Barlow/Inter files bundled); add the TTFs to ImageResponse `fonts` if the look matters.
// ponytail: the crest stand-in only, never `crest_url` (no server-side crest fetch, plan §5); Satori fetch from the
// crest host once real crests exist (Q72).
/**
 * WhatsApp / social preview: the club (stand-in + name), the Jomhor lockup at the end, competition, title, date in the
 * venue's zone, venue. Same 404s as the page (another club's URL included).
 */
export default async function OpenGraphImage({ params }: { params: Promise<{ club: string; slug: string }> }) {
  const { club, slug } = await params
  const { e } = (await eventAt(club, slug)) ?? notFound()
  const label = kicker(d, e)
  return new ImageResponse(
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'space-between', padding: 72, background: c.ground, color: c.text }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
          <div style={{ width: 88, height: 88, borderRadius: 44, background: c.accent, color: c.onAccent, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 52, fontWeight: 800 }}>
            {initialOf(e.organizer.name)}
          </div>
          <div style={{ fontSize: 40, fontWeight: 700 }}>{e.organizer.name}</div>
        </div>
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
