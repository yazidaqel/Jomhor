import { ImageResponse } from 'next/og'
import { defaultLocale, getDictionary } from '@/lib/i18n'
import { logoDataUrl } from '@/lib/logo'
import tokens from '@/lib/tokens'

// FR: crawlers send no cookie, and the default OG font has no Arabic glyphs.
const d = getDictionary(defaultLocale)
const c = tokens.color

export const size = { width: 1200, height: 630 }
export const contentType = 'image/png'
export const alt = d.footer.tagline

/** Default social preview for every page without its own (BRAND.md: lockup on ink), plus the tagline. */
export default async function OpenGraphImage() {
  return new ImageResponse(
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 44, background: c.ground }}>
      {/* eslint-disable-next-line @next/next/no-img-element -- Satori draws <img>, not next/image */}
      <img src={await logoDataUrl('jomhor-lockup-dark.svg')} alt="" width={660} height={160} />
      <div style={{ fontSize: 40, fontWeight: 600, letterSpacing: 1, color: c.textSecondary }}>{d.footer.tagline}</div>
    </div>,
    size,
  )
}
