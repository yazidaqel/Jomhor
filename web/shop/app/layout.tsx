import type { Metadata } from 'next'
import { Barlow_Condensed, Cairo, IBM_Plex_Sans_Arabic, Inter } from 'next/font/google'
import { shopName } from '@/lib/club'
import { dirOf } from '@/lib/i18n'
import { getI18n } from '@/lib/i18n/server'
import { ErrorCopy } from './error-copy'
import './globals.css'

// Families and weights of design/tokens.json `font` (next/font needs literals).
const display = Barlow_Condensed({ weight: ['600', '700', '800'], subsets: ['latin'], variable: '--font-display' })
const body = Inter({ weight: ['400', '500', '600'], subsets: ['latin'], variable: '--font-body' })
// `arabicDisplay` (variable: 700/800 and anything between) and `arabicBody` (+700: AR labels get weight, not tracking).
// Not preloaded: FR and EN pages would pay for them; Arabic pages fetch them from the stylesheet.
const arDisplay = Cairo({ subsets: ['arabic'], preload: false, variable: '--font-ar-display' })
const arBody = IBM_Plex_Sans_Arabic({ weight: ['400', '500', '600', '700'], subsets: ['arabic'], preload: false, variable: '--font-ar-body' })

/**
 * AR stacks: the Latin face first (its faces only cover Latin, so digits, codes, prices and Latin names stay Barlow /
 * Inter), then the Arabic face for Arabic letters, then the Arabic face's metric-matched fallback.
 */
const arabic = (latin: { style: { fontFamily: string } }, arVar: string) => `${latin.style.fontFamily.split(',')[0]}, var(${arVar})`

/** Read per request: `SHOP_ORIGIN` makes canonical and Open Graph URLs absolute (unset at build time). */
export async function generateMetadata(): Promise<Metadata> {
  const { d } = await getI18n()
  const origin = process.env.SHOP_ORIGIN
  return {
    title: { default: shopName(d), template: `%s · ${shopName(d)}` },
    ...(URL.canParse(origin ?? '') && { metadataBase: new URL(origin!) }),
    openGraph: { siteName: shopName(d), locale: d.meta.og, type: 'website' },
  }
}

/** `<html lang dir>` from the request's locale (plan Q65: `?lang=`, cookie, Accept-Language, FR). */
export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const { locale, d } = await getI18n()
  const fonts =
    locale === 'ar'
      ? ({ '--font-display': arabic(display, '--font-ar-display'), '--font-body': arabic(body, '--font-ar-body') } as React.CSSProperties)
      : undefined
  return (
    <html lang={locale} dir={dirOf(locale)} className={`${display.variable} ${body.variable} ${arDisplay.variable} ${arBody.variable}`} style={fonts}>
      <body className="min-h-dvh">
        <ErrorCopy value={d.error}>{children}</ErrorCopy>
      </body>
    </html>
  )
}
