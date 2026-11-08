import { ImageResponse } from 'next/og'
import { notFound } from 'next/navigation'
import { getClub } from '@/lib/clubs'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { logoDataUrl } from '@/lib/logo'
import tokens from '@/lib/tokens'
import { initialOf } from '../../brand'

// FR whatever the reader's locale: crawlers send no cookie, and the default OG font has no Arabic glyphs.
const d = getDictionary(defaultLocale)
const c = tokens.color

export const size = { width: 1200, height: 630 }
export const contentType = 'image/png'
export const alt = d.clubs.ogAlt

// ponytail: the crest stand-in only, never `crest_url` (no server-side crest fetch, plan §5); Satori fetch from the
// crest host once real crests exist (Q72).
/** W7 social preview: crest stand-in, club name, "Billetterie", the Jomhor lockup. Jomhor ink + amber only. */
export default async function OpenGraphImage({ params }: { params: Promise<{ club: string }> }) {
  const club = (await getClub((await params).club)) ?? notFound()
  return new ImageResponse(
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'space-between', padding: 72, background: c.ground, color: c.text }}>
      <img src={await logoDataUrl('jomhor-lockup-dark.svg')} alt="" width={231} height={56} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 40 }}>
        <div style={{ width: 160, height: 160, borderRadius: 80, background: c.accent, color: c.onAccent, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 92, fontWeight: 800 }}>
          {initialOf(club.name)}
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ fontSize: 84, fontWeight: 800, lineHeight: 0.95, textTransform: 'uppercase' }}>{club.name}</div>
          <div style={{ fontSize: 36, fontWeight: 600, color: c.textSecondary }}>{d.header.shop}</div>
        </div>
      </div>
      <div style={{ display: 'flex', fontSize: 30, color: c.textSecondary }}>{d.footer.tagline}</div>
    </div>,
    size,
  )
}
